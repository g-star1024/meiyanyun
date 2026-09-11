<script setup lang="ts">
/* ============================================================
 * 术后回访与满意度 /followup（Desktop 优先 · 平板堆叠）
 * 状态机：待回访 → 已回访 / 无需回访。
 * 超期未回访高亮预警；满意度统计；不良反应提示转投诉跟进。
 * P5-B31：列表真分页（CPagination 1 起页码）+ 后端 stats 九键计数 +
 *         关键字 350ms 防抖下推；新建/完成/免回访均写真库。
 * ============================================================ */
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import CCard from '@/components/CCard.vue'
import CWorkbenchShell from '@/components/CWorkbenchShell.vue'
import CKpi from '@/components/CKpi.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CTextarea from '@/components/CTextarea.vue'
import CSelect from '@/components/CSelect.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CPagination from '@/components/CPagination.vue'
import {
  useFollowupStore,
  type Followup,
  type RecoveryStatus,
  SOP_STAGE_LABEL,
} from '@/stores/followup'
import { FOLLOWUP_STATUS, FOLLOWUP_METHOD, RECOVERY_STATUS, dictPill, type FollowupMethod } from '@/config/dictionary'
import { useAuthStore } from '@/stores/auth'
import { useCustomerStore } from '@/stores/customer'
import { useToast } from '@/composables/useToast'
import { errMsg } from '@/stores/m5Coupon'
import { searchCustomers, type CustomerDTO } from '@/api/customer'

const followup = useFollowupStore()
const customer = useCustomerStore()
const auth = useAuthStore()
const router = useRouter()
const toast = useToast()

type Tab = 'pending' | 'done' | 'skipped'
const TAB_STATUS: Record<Tab, string> = { pending: 'PENDING', done: 'DONE', skipped: 'SKIPPED' }
const tab = ref<Tab>('pending')
const selectedId = ref<string | null>(null)
const keyword = ref('')

// 当前 tab/status + keyword 下刷新分页（页码 1 起；写动作后末条迁出导致空页时自动回退）
async function reloadList(opts?: { page?: number }) {
  const targetPage = opts?.page ?? followup.page
  await followup.refresh(
    auth.user.storeId,
    { status: TAB_STATUS[tab.value], keyword: keyword.value.trim() || undefined },
    { page: targetPage },
  )
  if (followup.records.length === 0 && followup.total > 0 && followup.page > 1) {
    await reloadList({ page: Math.max(1, followup.totalPages) })
  }
}
async function selectTab(t: Tab) {
  if (tab.value === t) return
  tab.value = t
  selectedId.value = null
  await reloadList({ page: 1 })
}
async function onPage(p: number) {
  await reloadList({ page: p })
}
// 关键字防抖下推后端（客户/项目/订单三字段模糊），350ms 静默期后拉第一页
let kwTimer: ReturnType<typeof setTimeout> | undefined
watch(keyword, () => {
  clearTimeout(kwTimer)
  kwTimer = setTimeout(() => {
    selectedId.value = null
    void reloadList({ page: 1 })
  }, 350)
})

onMounted(() => {
  void reloadList({ page: 1 })
})

const tabs = computed(() => [
  { k: 'pending' as Tab, label: `待回访 (${followup.stats.pending})` },
  { k: 'done' as Tab, label: `已回访 (${followup.stats.done})` },
  { k: 'skipped' as Tab, label: `无需回访 (${followup.stats.skipped})` },
])

// 列表以后端分页 records 为准（后端固定 planDate,id 升序，超期自然排前）
const list = computed<Followup[]>(() => followup.records)

const selected = computed(() => {
  if (selectedId.value) return followup.get(selectedId.value) ?? null
  return list.value[0] ?? null
})
// 跨页/写动作后定位：当前页与缓存均未命中时拉单条详情
watch(selectedId, async (id) => {
  if (id && !followup.get(id)) await followup.fetchDetail(id)
})

const kpis = computed(() => [
  { label: '待回访', value: String(followup.stats.pending), tone: 'warning' as const, icon: 'phone' as const },
  { label: '今日待回访', value: String(followup.stats.todayPending), tone: 'brand' as const, icon: 'clock' as const },
  { label: '平均满意度', value: followup.stats.avgSatisfaction ? followup.stats.avgSatisfaction.toFixed(1) + '★' : '—', tone: 'brand' as const, icon: 'trend-up' as const },
  { label: '不良反应跟进', value: String(followup.stats.adverseCount), tone: 'danger' as const, icon: 'alert' as const },
])

// 超期预警条：计数取 stats；最早超期文案取待回访首页首条（planDate 升序）
const earliestOverdueLabel = computed(() => {
  if (tab.value !== 'pending') return ''
  const f = list.value[0]
  return f && isOverdue(f) ? planLabel(f) : ''
})

function isOverdue(f: Followup) {
  if (f.status !== 'PENDING') return false
  const today = new Date(); today.setHours(0, 0, 0, 0)
  return new Date(f.planDate) < today
}
function daysFromNow(iso: string) {
  const today = new Date(); today.setHours(0, 0, 0, 0)
  const target = new Date(iso); target.setHours(0, 0, 0, 0)
  return Math.round((target.getTime() - today.getTime()) / 86400000)
}
function planLabel(f: Followup) {
  if (f.status !== 'PENDING') return f.planDate.slice(0, 10)
  const d = daysFromNow(f.planDate)
  if (d === 0) return '今日回访'
  if (d < 0) return `超期 ${-d} 天`
  if (d === 1) return '明日回访'
  return `${d} 天后回访`
}
function fmtDate(iso: string) { return iso.slice(0, 10) }

function goCustomer() {
  if (selected.value && selected.value.customerId !== 'C-NEW') {
    router.push(`/customers/${selected.value.customerId}`)
  } else {
    router.push('/customers')
  }
}

// 回访登记表单
const form = ref({
  satisfaction: 5,
  recovery: 'GOOD' as RecoveryStatus,
  adverseReaction: false,
  adverseNote: '',
  needRevisit: false,
  note: '',
  method: 'PHONE' as FollowupMethod,
})
watch(
  selected,
  (f) => {
    if (f && f.status === 'PENDING') {
      form.value = {
        satisfaction: 5, recovery: 'GOOD', adverseReaction: false, adverseNote: '',
        needRevisit: false, note: '', method: f.method,
      }
    }
  },
  { immediate: true },
)
function stars(n: number) { return '★'.repeat(n) + '☆'.repeat(5 - n) }
const canComplete = computed(() => !form.value.adverseReaction || form.value.adverseNote.trim().length > 0)

async function doComplete() {
  if (!selected.value || !canComplete.value) return
  const ok = await followup.complete(selected.value.id, { ...form.value })
  if (ok) {
    toast.success('回访结果已提交归档')
    await reloadList()
  }
}
const skipReason = ref('')
const showSkip = ref(false)
async function doSkip() {
  if (!selected.value || !skipReason.value.trim()) return
  const ok = await followup.skip(selected.value.id, skipReason.value.trim())
  if (ok) {
    showSkip.value = false; skipReason.value = ''
    toast.info('已标记为无需回访')
    await reloadList()
  }
}

// 新建回访计划（客户须先建档：姓名/手机号/客户编号检索后点选）
const showForm = ref(false)
const searching = ref(false)
const customerHits = ref<CustomerDTO[]>([])
const pickedCustomer = ref<CustomerDTO | null>(null)
const newPlan = ref({
  customerKeyword: '', project: '', relatedOrderNo: '',
  serviceDate: '', planDate: '', method: 'PHONE' as FollowupMethod,
})
const canSubmitPlan = computed(
  () => !!pickedCustomer.value && newPlan.value.project.trim() && newPlan.value.serviceDate && newPlan.value.planDate,
)
async function searchCustomer() {
  const kw = newPlan.value.customerKeyword.trim()
  if (!kw) { customerHits.value = []; return }
  searching.value = true
  try {
    const res = await searchCustomers(kw)
    customerHits.value = res.data ?? []
    if (!customerHits.value.length) toast.info('未检索到客户，请先建档或更换关键字')
  } catch (e) {
    toast.error(errMsg(e, '客户检索失败'))
  } finally {
    searching.value = false
  }
}
function pickCustomer(c: CustomerDTO) {
  pickedCustomer.value = c
  newPlan.value.customerKeyword = `${c.name}（${c.customerId}）`
  customerHits.value = []
  customer.hydrate([{ customerId: c.customerId, customerName: c.name }])
}
function resetPlanForm() {
  newPlan.value = {
    customerKeyword: '', project: '', relatedOrderNo: '',
    serviceDate: '', planDate: '', method: 'PHONE',
  }
  pickedCustomer.value = null
  customerHits.value = []
}
function closeForm() { showForm.value = false; resetPlanForm() }
async function submitPlan() {
  if (!canSubmitPlan.value || !pickedCustomer.value) return
  const f = await followup.create({
    customerId: pickedCustomer.value.customerId,
    project: newPlan.value.project.trim(),
    relatedOrderNo: newPlan.value.relatedOrderNo.trim() || undefined,
    serviceDate: newPlan.value.serviceDate,
    planDate: newPlan.value.planDate,
    method: newPlan.value.method,
  })
  if (f) {
    closeForm()
    selectedId.value = f.id
    tab.value = 'pending'
    await reloadList({ page: 1 })
  }
}
</script>

<template>
  <div class="fu">
    <!-- 超期预警条 -->
    <div v-if="followup.stats.overdue > 0" class="warnbar">
      <CIcon name="alert" :size="16" />
      <span>
        有 <strong>{{ followup.stats.overdue }}</strong> 位客户回访已超期，请优先联系<template v-if="earliestOverdueLabel">（最早超期 {{ earliestOverdueLabel }}）</template>。
      </span>
    </div>

    <CWorkbenchShell
      :has-selection="!!selected"
      empty-icon="phone"
      empty-title="请从左侧选择一条回访记录"
      empty-desc="待回访客户可登记满意度与恢复情况，不良反应将提示转投诉跟进"
      list-width="380px"
    >
      <template #kpis>
        <CKpi v-for="k in kpis" :key="k.label" :value="k.value" :label="k.label" :tone="k.tone" :icon="k.icon" />
      </template>

      <template #toolbar>
        <CInput v-model="keyword" placeholder="搜索客户 / 项目 / 订单" />
        <CButton variant="ghost" @click="router.push('/sop')">
          <CIcon name="layers" :size="16" />SOP 编排
        </CButton>
        <CButton variant="primary" v-perm.disable="'followup:create'" @click="showForm = true">
          <CIcon name="plus" :size="16" />新建回访
        </CButton>
      </template>

      <template #list>
        <div class="tabs">
          <button
            v-for="t in tabs" :key="t.k"
            class="tab" :class="{ 'tab--active': tab === t.k }"
            @click="selectTab(t.k)"
          >{{ t.label }}</button>
        </div>
        <div class="list">
          <div v-if="list.length === 0" class="empty">
            <CIcon name="phone" :size="28" class="empty__icon" />
            <div>暂无回访记录</div>
          </div>
          <button
            v-for="f in list" :key="f.id"
            class="rec" :class="{ 'rec--active': selected?.id === f.id, 'rec--overdue': isOverdue(f) }"
            @click="selectedId = f.id"
          >
            <div class="rec__top">
              <span class="rec__name">{{ f.customerName }}</span>
              <CStatusPill :status="dictPill(FOLLOWUP_STATUS[f.status]).status">{{ dictPill(FOLLOWUP_STATUS[f.status]).text }}</CStatusPill>
            </div>
            <div class="rec__proj">{{ f.project }}
              <span v-if="f.sopStage && f.sopStage !== 'MANUAL'" class="rec__sop">
                <CIcon name="bell" :size="11" />术后SOP·{{ SOP_STAGE_LABEL[f.sopStage] }}
              </span>
            </div>
            <div class="rec__meta">
              <span class="rec__plan" :class="{ 'rec__plan--overdue': isOverdue(f) }">
                <CIcon name="clock" :size="12" />{{ planLabel(f) }}
              </span>
              <span v-if="f.status === 'DONE' && f.satisfaction" class="rec__stars">{{ stars(f.satisfaction) }}</span>
              <span v-else>{{ FOLLOWUP_METHOD[f.method]?.label }}</span>
            </div>
            <div v-if="f.status === 'DONE' && f.adverseReaction" class="rec__risk">
              <CIcon name="alert" :size="12" />不良反应，需跟进
            </div>
          </button>
        </div>
        <CPagination
          v-if="followup.total > 0"
          :page="followup.page"
          :page-size="followup.pageSize"
          :total="followup.total"
          @update:page="onPage"
        />
      </template>

      <!-- 右列详情 -->
      <template #head>
        <div v-if="selected" class="wb-head">
          <h3 class="fu__detail-title">{{ selected.customerName }}</h3>
          <div class="fu__detail-tags">
            <CStatusPill v-if="selected.adverseReaction" status="danger" dot>不良反应</CStatusPill>
            <CStatusPill :status="dictPill(FOLLOWUP_STATUS[selected.status]).status">{{ dictPill(FOLLOWUP_STATUS[selected.status]).text }}</CStatusPill>
          </div>
        </div>
      </template>

      <template v-if="selected">
        <div class="cust">
          <div class="cust__name">{{ selected.project }}</div>
          <div class="cust__sub">
            {{ FOLLOWUP_METHOD[selected.method]?.label }}回访
            <template v-if="selected.relatedOrderNo"> · 订单 {{ selected.relatedOrderNo }}</template>
          </div>
        </div>

        <div class="grid">
          <div class="field"><span class="field__label">服务日期</span><span class="field__val">{{ fmtDate(selected.serviceDate) }}</span></div>
          <div class="field"><span class="field__label">计划回访</span><span class="field__val" :class="{ 'field__val--overdue': isOverdue(selected) }">{{ fmtDate(selected.planDate) }}（{{ planLabel(selected) }}）</span></div>
          <div v-if="selected.followupByName" class="field"><span class="field__label">回访人</span><span class="field__val">{{ selected.followupByName }}</span></div>
          <div v-if="selected.doneAt" class="field"><span class="field__label">回访时间</span><span class="field__val">{{ fmtDate(selected.doneAt) }}</span></div>
        </div>

        <!-- 已回访结果 -->
        <template v-if="selected.status === 'DONE'">
          <div class="result">
            <div class="result__row">
              <span class="result__label">满意度</span>
              <span class="result__stars">{{ stars(selected.satisfaction ?? 0) }}</span>
            </div>
            <div class="result__row">
              <span class="result__label">恢复情况</span>
              <CStatusPill v-if="selected.recovery" :status="dictPill(RECOVERY_STATUS[selected.recovery]).status">{{ dictPill(RECOVERY_STATUS[selected.recovery]).text }}</CStatusPill>
            </div>
            <div class="result__row">
              <span class="result__label">需要复诊</span>
              <span class="field__val">{{ selected.needRevisit ? '是，已安排复诊' : '否' }}</span>
            </div>
            <div v-if="selected.adverseReaction" class="result__adverse">
              <CIcon name="alert" :size="14" />
              <div>
                <strong>不良反应记录：</strong>{{ selected.adverseNote }}
                <div class="result__adverse-hint">建议转「投诉与医疗风险处理」跟进，并预约主诊医生复诊。</div>
              </div>
            </div>
            <div v-if="selected.note" class="result__note">
              <span class="result__label">回访备注</span>
              <p>{{ selected.note }}</p>
            </div>
          </div>
        </template>

        <!-- 无需回访原因 -->
        <div v-else-if="selected.status === 'SKIPPED'" class="result">
          <div class="result__note">
            <span class="result__label">标记原因</span>
            <p>{{ selected.note }}</p>
          </div>
        </div>

        <!-- 待回访：登记表单 -->
        <template v-else>
          <div class="form">
            <div class="form__row">
              <label class="form__label">回访方式</label>
              <CSelect v-model="form.method" width="200px" :options="[
                { value: 'PHONE', label: '电话' },
                { value: 'WECHAT', label: '微信' },
                { value: 'IN_STORE', label: '到店' },
              ]" />
            </div>
            <div class="form__row">
              <label class="form__label">满意度评分</label>
              <div class="stars-input">
                <button
                  v-for="n in 5" :key="n" type="button"
                  class="star" :class="{ 'star--on': n <= form.satisfaction }"
                  @click="form.satisfaction = n"
                >★</button>
                <span class="stars-input__txt">{{ form.satisfaction }} 星</span>
              </div>
            </div>
            <div class="form__row">
              <label class="form__label">恢复情况</label>
              <div class="seg">
                <button :class="{ 'seg--on': form.recovery === 'GOOD' }" @click="form.recovery = 'GOOD'">恢复良好</button>
                <button :class="{ 'seg--on': form.recovery === 'NORMAL' }" @click="form.recovery = 'NORMAL'">恢复一般</button>
                <button :class="{ 'seg--on': form.recovery === 'POOR' }" @click="form.recovery = 'POOR'">恢复不佳</button>
              </div>
            </div>
            <label class="form__check">
              <input type="checkbox" v-model="form.needRevisit" />
              <span>需要复诊（自动提醒预约主诊医生）</span>
            </label>
            <label class="form__check form__check--risk">
              <input type="checkbox" v-model="form.adverseReaction" />
              <span>存在不良反应（须填写情况，并建议转投诉跟进）</span>
            </label>
            <div v-if="form.adverseReaction" class="form__row">
              <label class="form__label">不良反应描述（必填）</label>
              <CTextarea v-model="form.adverseNote" placeholder="请描述症状、持续时间及已采取的处置措施" />
            </div>
            <div class="form__row">
              <label class="form__label">回访备注</label>
              <CTextarea v-model="form.note" placeholder="客户反馈、后续跟进事项等" />
            </div>
          </div>

          <div v-if="showSkip" class="inline-box">
            <CInput v-model="skipReason" placeholder="请输入标记无需回访的原因（必填）" />
            <div class="inline-box__btns">
              <CButton variant="ghost" @click="showSkip = false; skipReason = ''">取消</CButton>
              <CButton variant="primary" :disabled="!skipReason.trim()" @click="doSkip">确认</CButton>
            </div>
          </div>
        </template>
      </template>

      <template #foot>
        <template v-if="selected">
          <!-- 待回访：登记操作 -->
          <template v-if="selected.status === 'PENDING'">
            <CButton variant="ghost" v-perm.disable="'followup:edit'" @click="showSkip = true">标记无需回访</CButton>
            <CButton variant="primary" :disabled="!canComplete" v-perm.disable="'followup:edit'" @click="doComplete">
              <CIcon name="check" :size="16" />提交回访结果
            </CButton>
          </template>
          <!-- 已回访：下一步出口 -->
          <template v-else-if="selected.status === 'DONE'">
            <span class="wbs-foot-done">
              <CIcon name="check-square" :size="15" />
              回访已完成<template v-if="selected.satisfaction"> · 满意度 {{ stars(selected.satisfaction) }}</template>
            </span>
            <CButton v-if="selected.adverseReaction" variant="secondary" @click="router.push('/customers')">
              <CIcon name="alert" :size="14" />转客户档案跟进
            </CButton>
            <CButton v-if="selected.needRevisit" variant="secondary" @click="router.push('/recall')">
              <CIcon name="bell" :size="14" />复诊提醒
            </CButton>
            <CButton v-if="selected.customerId !== 'C-NEW'" variant="primary" @click="goCustomer">
              <CIcon name="customer" :size="14" />客户 360 · 跟进复诊
            </CButton>
          </template>
        </template>
      </template>
    </CWorkbenchShell>

    <!-- 新建回访计划弹层 -->
    <div v-if="showForm" class="modal-mask" @click.self="closeForm">
      <CCard class="modal" title="新建回访计划" padding="lg">
        <div class="form">
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">客户</label>
              <div class="pick">
                <CInput v-model="newPlan.customerKeyword" placeholder="姓名 / 手机号 / 客户编号" />
                <CButton variant="secondary" :disabled="searching" @click="searchCustomer">
                  <CIcon name="customer" :size="16" />检索
                </CButton>
              </div>
              <div v-if="customerHits.length" class="pick__panel">
                <button
                  v-for="c in customerHits" :key="c.customerId"
                  type="button" class="pick__opt"
                  @click="pickCustomer(c)"
                >
                  <span class="pick__name">{{ c.name }}</span>
                  <span class="pick__sub">{{ c.customerId }} · {{ c.phone || '无手机号' }} · {{ c.level }}</span>
                </button>
              </div>
            </div>
            <div>
              <label class="form__label">关联订单号（选填）</label>
              <CInput v-model="newPlan.relatedOrderNo" placeholder="如：SO20260824001" />
            </div>
          </div>
          <div class="form__row">
            <label class="form__label">项目/疗程</label>
            <CInput v-model="newPlan.project" placeholder="如：光子嫩肤" />
          </div>
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">服务日期</label>
              <input type="date" v-model="newPlan.serviceDate" class="date-input" />
            </div>
            <div>
              <label class="form__label">计划回访日期</label>
              <input type="date" v-model="newPlan.planDate" class="date-input" />
            </div>
          </div>
          <div class="form__row">
            <label class="form__label">默认回访方式</label>
            <CSelect v-model="newPlan.method" width="200px" :options="[
              { value: 'PHONE', label: '电话' },
              { value: 'WECHAT', label: '微信' },
              { value: 'IN_STORE', label: '到店' },
            ]" />
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="closeForm">取消</CButton>
          <CButton variant="primary" :disabled="!canSubmitPlan" @click="submitPlan">创建计划</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.fu { display: flex; flex-direction: column; gap: var(--s-lg); }

.warnbar {
  display: flex; align-items: center; gap: var(--s-sm);
  padding: var(--s-sm) var(--s-md); border-radius: var(--r-md);
  background: var(--c-warning-bg); color: var(--c-warning-fg); font-size: var(--t-sm);
  border: 1px solid var(--c-warning-fg);
}
.warnbar strong { margin: 0 2px; }


.fu__detail-title { font-size: var(--t-md); line-height: var(--lh-md); font-weight: 700; color: var(--c-text); margin: 0; }
.fu__detail-tags { display: flex; gap: var(--s-xs); }
.wb-head { display: flex; justify-content: space-between; align-items: center; gap: var(--s-sm); }
.wbs-foot-done { display: inline-flex; align-items: center; gap: var(--s-xs); color: var(--c-success-fg, #389e0d); font-size: var(--t-sm); font-weight: 600; margin-right: auto; }

.tabs { display: flex; border-bottom: 1px solid var(--c-border); flex-shrink: 0; }
.tab {
  flex: 1; padding: var(--s-md) var(--s-xs); font-size: var(--t-xs); white-space: nowrap;
  color: var(--c-text-3); background: none; border: none; cursor: pointer;
  border-bottom: 2px solid transparent;
}
.tab--active { color: var(--c-brand); border-bottom-color: var(--c-brand); font-weight: 600; }

.list { flex: 1; min-height: 0; overflow-y: auto; }
.empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); font-size: var(--t-sm); }
.empty__icon { color: var(--c-text-4); }

.rec {
  display: block; width: 100%; text-align: left; padding: var(--s-md) var(--s-lg);
  background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer;
  border-left: 3px solid transparent;
}
.rec:hover { background: var(--c-brand-soft); }
.rec--active { background: var(--c-brand-soft); }
.rec--overdue { border-left-color: var(--c-danger-fg); }
.rec__top { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; }
.rec__name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.rec__proj { font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: var(--s-xs); }
.rec__sop { display: inline-flex; align-items: center; gap: 3px; margin-left: 6px; padding: 1px 7px; border-radius: var(--r-pill); background: var(--c-brand-soft); color: var(--c-brand); font-size: 10px; font-weight: 600; }
.rec__meta { display: flex; justify-content: space-between; align-items: center; font-size: var(--t-xs); color: var(--c-text-3); }
.rec__plan { display: inline-flex; align-items: center; gap: 3px; }
.rec__plan--overdue { color: var(--c-danger-fg); font-weight: 600; }
.rec__stars { color: var(--c-warning-fg); letter-spacing: -1px; }
.rec__risk { margin-top: var(--s-xs); font-size: var(--t-xs); color: var(--c-danger-fg); display: flex; align-items: center; gap: 3px; }

.cust { padding-bottom: var(--s-md); border-bottom: 1px solid var(--c-border-light); }
.cust__name { font-size: var(--t-lg); font-weight: 700; color: var(--c-text); }
.cust__sub { font-size: var(--t-sm); color: var(--c-text-3); margin-top: 2px; }

.grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md) var(--s-lg); margin: var(--s-lg) 0; }
.field { display: flex; flex-direction: column; gap: 2px; }
.field__label { font-size: var(--t-xs); color: var(--c-text-3); }
.field__val { font-size: var(--t-sm); color: var(--c-text); }
.field__val--overdue { color: var(--c-danger-fg); font-weight: 600; }

.result { display: flex; flex-direction: column; gap: var(--s-md); margin-bottom: var(--s-md); }
.result__row { display: flex; align-items: center; gap: var(--s-md); }
.result__label { font-size: var(--t-xs); color: var(--c-text-3); width: 72px; flex-shrink: 0; }
.result__stars { color: var(--c-warning-fg); font-size: var(--t-lg); letter-spacing: -1px; }
.result__adverse {
  display: flex; gap: var(--s-sm); padding: var(--s-md);
  background: var(--c-danger-bg); color: var(--c-danger-fg); border-radius: var(--r-md); font-size: var(--t-sm); line-height: 1.6;
}
.result__adverse-hint { margin-top: 4px; font-size: var(--t-xs); }
.result__note { font-size: var(--t-sm); color: var(--c-text); }
.result__note p { margin: 4px 0 0; line-height: 1.7; color: var(--c-text-2); }

.form { display: flex; flex-direction: column; gap: var(--s-md); margin-bottom: var(--s-md); }
.form__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.form__row--2 { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.form__label { font-size: var(--t-xs); color: var(--c-text-3); }
.form__check { display: flex; align-items: center; gap: var(--s-xs); font-size: var(--t-sm); color: var(--c-text-2); cursor: pointer; }
.form__check--risk { color: var(--c-danger-fg); }

.stars-input { display: flex; align-items: center; gap: var(--s-xs); }
.star { background: none; border: none; cursor: pointer; font-size: 24px; color: var(--c-border); padding: 0; line-height: 1; }
.star--on { color: var(--c-warning-fg); }
.stars-input__txt { font-size: var(--t-sm); color: var(--c-text-3); margin-left: var(--s-xs); }

.seg { display: inline-flex; border: 1px solid var(--c-border); border-radius: var(--r-capsule); overflow: hidden; align-self: flex-start; }
.seg button { padding: var(--s-xs) var(--s-md); font-size: var(--t-sm); background: none; border: none; cursor: pointer; color: var(--c-text-2); }
.seg button.seg--on { background: var(--c-brand); color: #fff; }

.inline-box { margin-top: var(--s-md); display: flex; flex-direction: column; gap: var(--s-sm); }
.inline-box__btns { display: flex; justify-content: flex-end; gap: var(--s-sm); }

.date-input {
  padding: 10px; border: 1px solid var(--c-border); border-radius: var(--r-md);
  font-size: var(--t-sm); color: var(--c-text); background: #fff; font-family: inherit;
}
.date-input:focus { outline: none; border-color: var(--c-brand); }

.pick { display: flex; gap: var(--s-sm); align-items: center; }
.pick :deep(.cinput) { flex: 1; }
.pick__panel {
  margin-top: var(--s-xs); border: 1px solid var(--c-border); border-radius: var(--r-md);
  overflow: hidden; background: var(--c-surface); box-shadow: var(--shadow-card); max-height: 220px; overflow-y: auto;
}
.pick__opt { display: flex; flex-direction: column; gap: 2px; width: 100%; text-align: left; padding: var(--s-sm) var(--s-md); background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer; }
.pick__opt:hover { background: var(--c-brand-soft); }
.pick__name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.pick__sub { font-size: var(--t-xs); color: var(--c-text-3); }

.modal-mask { position: fixed; inset: 0; background: rgba(20,21,43,.45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 560px; max-width: 100%; max-height: 90vh; overflow-y: auto; box-shadow: var(--shadow-pop); }
</style>
