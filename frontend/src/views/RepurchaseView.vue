<script setup lang="ts">
/* ============================================================
 * M4-18 复购回访 /repurchase（复购 / 资产转移单据 + 知情同意 + 三方双签）
 * Desktop 优先 · 平板堆叠；布局复用 EMR 工作台骨架与同类名。
 * 红线：consentAck 未签不得建单；三方（客户/经办/店长）不得同一人；
 *       资产转移由后端同事务搬移来源卡余额/次数（账实校验）。
 * ============================================================ */
import { computed, onMounted, ref, watch } from 'vue'
import CCard from '@/components/CCard.vue'
import CWorkbenchShell from '@/components/CWorkbenchShell.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CTextarea from '@/components/CTextarea.vue'
import CSelect from '@/components/CSelect.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CKpi from '@/components/CKpi.vue'
import CIcon from '@/components/CIcon.vue'
import { useRepurchaseStore, type RepurchaseRecord } from '@/stores/repurchase'
import { useCustomerStore } from '@/stores/customer'
import { useToast } from '@/composables/useToast'
import { errMsg } from '@/stores/m5Coupon'
import { searchCustomers, listCustomerCards, type CustomerDTO, type MemberCardDTO } from '@/api/customer'

const repurchase = useRepurchaseStore()
const customer = useCustomerStore()
const toast = useToast()

onMounted(async () => {
  await repurchase.load()
})

type Tab = 'pending' | 'completed' | 'rejected'
const tab = ref<Tab>('pending')
const selectedNo = ref<string | null>(null)
const keyword = ref('')

const tabs = computed(() => [
  { k: 'pending' as Tab, label: `待签核 (${repurchase.pending.length})` },
  { k: 'completed' as Tab, label: `已完成 (${repurchase.completed.length})` },
  { k: 'rejected' as Tab, label: `已拒绝 (${repurchase.rejected.length})` },
])

const baseList = computed<RepurchaseRecord[]>(() => {
  if (tab.value === 'pending') return [...repurchase.pending].sort((a, b) => b.createdAt.localeCompare(a.createdAt))
  if (tab.value === 'completed') return [...repurchase.completed].sort((a, b) => b.createdAt.localeCompare(a.createdAt))
  return [...repurchase.rejected].sort((a, b) => b.createdAt.localeCompare(a.createdAt))
})
const list = computed<RepurchaseRecord[]>(() => {
  const kw = keyword.value.trim()
  if (!kw) return baseList.value
  return baseList.value.filter(
    (r) => customerNameOf(r).includes(kw) || r.repurchaseNo.includes(kw) || r.bizType.includes(kw)
      || r.targetProject.includes(kw),
  )
})

const selected = computed<RepurchaseRecord | null>(() => {
  if (selectedNo.value) return repurchase.get(selectedNo.value)
  return list.value[0] ?? null
})
function selectTab(t: Tab) { tab.value = t; selectedNo.value = null }

function customerNameOf(r: RepurchaseRecord) {
  return repurchase.customerName(r.customerId)
}

const STATUS_PILL: Record<string, 'warning' | 'success' | 'danger'> = {
  待签核: 'warning',
  已完成: 'success',
  已拒绝: 'danger',
}

const kpis = computed(() => [
  { label: '待签核', value: String(repurchase.pending.length), tone: 'warning' as const, icon: 'sign' },
  { label: '本月已完成', value: String(repurchase.completed.length), tone: 'success' as const, icon: 'check-square' },
  { label: '已拒绝', value: String(repurchase.rejected.length), tone: 'danger' as const, icon: 'alert' },
  { label: '单据总数', value: String(repurchase.records.length), tone: 'brand' as const, icon: 'order' },
])

function fmtDateTime(iso?: string) {
  if (!iso) return '—'
  const d = new Date(iso)
  const date = iso.slice(0, 10)
  return `${date} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}
function yuan(fen: number) {
  return repurchase.yuan(fen)
}

// ==================== 新建单据弹层 ====================
const showForm = ref(false)
const searching = ref(false)
const customerHits = ref<CustomerDTO[]>([])
const pickedCustomer = ref<CustomerDTO | null>(null)
const cardsLoading = ref(false)
const customerCards = ref<MemberCardDTO[]>([])

const emptyForm = () => ({
  customerKeyword: '',
  bizType: '复购',
  targetProject: '',
  fromCardNo: '',
  toCardNo: '',
  transferTimes: '',
  transferAmount: '',
  consent: false,
  consentText: '',
  note: '',
})
const form = ref(emptyForm())

const isTransfer = computed(() => form.value.bizType === '资产转移')
const cardOptions = computed(() => customerCards.value.map((c) => ({
  value: c.cardNo,
  label: `${c.cardItem}（${c.cardNo}）余${c.remainTimes ?? 0}次/¥${(c.balance / 100).toFixed(2)}`,
})))
const cardByNo = (no: string) => customerCards.value.find((c) => c.cardNo === no) || null

async function searchCustomer() {
  const kw = form.value.customerKeyword.trim()
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

async function pickCustomer(c: CustomerDTO) {
  pickedCustomer.value = c
  form.value.customerKeyword = `${c.name}（${c.customerId}）`
  customerHits.value = []
  customer.hydrate([{ customerId: c.customerId, customerName: c.name, phone: c.phone, storeCode: c.storeCode }])
  if (isTransfer.value) await loadCards(c.customerId)
}

async function loadCards(customerId: string) {
  cardsLoading.value = true
  try {
    const res = await listCustomerCards(customerId)
    customerCards.value = res.data ?? []
  } catch (e) {
    customerCards.value = []
    toast.error(errMsg(e, '客户会员卡加载失败'))
  } finally {
    cardsLoading.value = false
  }
}

watch(() => form.value.bizType, async (v) => {
  form.value.fromCardNo = ''
  form.value.toCardNo = ''
  if (v === '资产转移' && pickedCustomer.value) await loadCards(pickedCustomer.value.customerId)
  if (v !== '资产转移') customerCards.value = []
})

function resetForm() {
  form.value = emptyForm()
  pickedCustomer.value = null
  customerHits.value = []
  customerCards.value = []
}
function closeForm() { showForm.value = false; resetForm() }

const transferTimesNum = computed(() => {
  const n = Number(form.value.transferTimes)
  return Number.isFinite(n) && n >= 0 ? Math.floor(n) : 0
})
const transferAmountNum = computed(() => {
  const n = Number(form.value.transferAmount)
  return Number.isFinite(n) && n >= 0 ? n : 0
})

const fromCard = computed(() => cardByNo(form.value.fromCardNo))
const accountError = computed(() => {
  if (!isTransfer.value) return ''
  if (!form.value.fromCardNo || !form.value.toCardNo) return '资产转移须选择来源卡与目标卡'
  if (form.value.fromCardNo === form.value.toCardNo) return '来源卡与目标卡不能相同'
  if (fromCard.value && fromCard.value.remainTimes != null && fromCard.value.remainTimes < transferTimesNum.value) {
    return `账实校验：来源卡剩余次数 ${fromCard.value.remainTimes} < 转移次数 ${transferTimesNum.value}`
  }
  if (fromCard.value && fromCard.value.balance < Math.round(transferAmountNum.value * 100)) {
    return `账实校验：来源卡余额 ¥${(fromCard.value.balance / 100).toFixed(2)} < 转移金额 ¥${transferAmountNum.value.toFixed(2)}`
  }
  return ''
})

const canCreate = computed(() => {
  if (!pickedCustomer.value) return false
  if (isTransfer.value) {
    if (accountError.value) return false
  } else if (!form.value.targetProject.trim()) {
    return false
  }
  return form.value.consent
})

async function submitCreate() {
  if (!canCreate.value || !pickedCustomer.value) return
  const r = await repurchase.create({
    customerId: pickedCustomer.value.customerId,
    bizType: form.value.bizType,
    targetProject: form.value.targetProject,
    fromCardNo: isTransfer.value ? form.value.fromCardNo : undefined,
    toCardNo: isTransfer.value ? form.value.toCardNo : undefined,
    transferTimes: isTransfer.value ? transferTimesNum.value : undefined,
    transferAmountYuan: isTransfer.value ? transferAmountNum.value : undefined,
    consentText: form.value.consentText,
    note: form.value.note,
  })
  if (r) {
    toast.success(`单据 ${r.repurchaseNo} 已创建，待三方签核`)
    closeForm()
    tab.value = 'pending'
    selectedNo.value = r.repurchaseNo
  }
}

// ==================== 三方双签 ====================
const signForm = ref({ sign1: '', sign2: '', sign3: '' })
watch(
  selected,
  (r) => {
    if (r && r.status === '待签核') {
      signForm.value = {
        sign1: r.sign1 || (pickedCustomer.value?.name ?? ''),
        sign2: r.sign2 || '',
        sign3: r.sign3 || '',
      }
    }
  },
  { immediate: true },
)
const signError = computed(() => {
  const s = signForm.value
  if (!s.sign1.trim() || !s.sign2.trim() || !s.sign3.trim()) return '请填写客户/经办/店长三方签名'
  const a = s.sign1.trim(); const b = s.sign2.trim(); const c = s.sign3.trim()
  if (a === b || a === c || b === c) return '三方签不得为同一人'
  return ''
})
const submitting = ref(false)
async function doSign(reject: boolean) {
  if (signError.value || !selected.value) {
    if (signError.value) toast.error(signError.value)
    return
  }
  submitting.value = true
  const ok = await repurchase.sign(selected.value.repurchaseNo, {
    sign1: signForm.value.sign1.trim(),
    sign1Role: '客户',
    sign2: signForm.value.sign2.trim(),
    sign2Role: '经办',
    sign3: signForm.value.sign3.trim(),
    sign3Role: '店长',
    reject,
  })
  submitting.value = false
  if (ok) toast.success(reject ? '单据已拒签并留痕' : '三方双签完成，单据已生效')
}
</script>

<template>
  <div class="rp">
    <CWorkbenchShell
      :has-selection="!!selected"
      empty-icon="order"
      empty-title="请从左侧选择一张复购/资产转移单"
      empty-desc="单据须经客户确认 + 经办 + 店长三方签核后方可生效；知情同意书为硬前置"
      list-width="380px"
    >
      <template #kpis>
        <CKpi v-for="k in kpis" :key="k.label" :value="String(k.value)" :label="k.label" :tone="k.tone" :icon="k.icon" />
      </template>

      <template #toolbar>
        <CInput v-model="keyword" placeholder="搜索客户 / 单号 / 项目" />
        <CButton variant="primary" v-perm.disable="'followup:create'" @click="showForm = true">
          <CIcon name="plus" :size="16" />新建单据
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
            <CIcon name="order" :size="28" class="empty__icon" />
            <div>暂无单据</div>
          </div>
          <button
            v-for="r in list" :key="r.repurchaseNo"
            class="rec" :class="{ 'rec--active': selected?.repurchaseNo === r.repurchaseNo }"
            @click="selectedNo = r.repurchaseNo"
          >
            <div class="rec__top">
              <span class="rec__name">{{ customerNameOf(r) }}</span>
              <CStatusPill :status="STATUS_PILL[r.status] ?? 'default'">{{ r.status }}</CStatusPill>
            </div>
            <div class="rec__sub">
              <span class="rec__type">{{ r.bizType }}</span>
              <span>{{ r.repurchaseNo }}</span>
            </div>
            <div class="rec__diag">{{ r.targetProject || (r.bizType === '资产转移' ? '卡余额/次数转移' : '（未填项目）') }}</div>
            <div class="rec__meta">
              <span><CIcon name="calendar" :size="12" />{{ fmtDateTime(r.createdAt) }}</span>
              <span v-if="r.transferAmount">¥{{ yuan(r.transferAmount) }}</span>
            </div>
          </button>
        </div>
      </template>

      <template #head>
        <div v-if="selected" class="wb-head">
          <h3 class="rp__detail-title">
            {{ customerNameOf(selected) }}
            <span class="rp__type-tag">{{ selected.bizType }}</span>
          </h3>
          <CStatusPill :status="STATUS_PILL[selected.status] ?? 'default'">{{ selected.status }}</CStatusPill>
        </div>
      </template>

      <template v-if="selected">
        <!-- 知情同意提示条 -->
        <div class="consent-bar">
          <CIcon name="shield" :size="16" />
          <span>
            知情同意书：<strong>已签署</strong>
            <template v-if="selected.consentText">；{{ selected.consentText }}</template>
          </span>
        </div>

        <div class="meta-row">
          <div class="meta-item"><span class="meta-label">单据编号</span><span class="meta-val">{{ selected.repurchaseNo }}</span></div>
          <div class="meta-item"><span class="meta-label">客户</span><span class="meta-val">{{ customerNameOf(selected) }}（{{ selected.customerId }}）</span></div>
          <div class="meta-item"><span class="meta-label">目标项目</span><span class="meta-val">{{ selected.targetProject || '—' }}</span></div>
          <div class="meta-item"><span class="meta-label">创建时间</span><span class="meta-val">{{ fmtDateTime(selected.createdAt) }}</span></div>
        </div>

        <!-- 资产转移明细 -->
        <div v-if="selected.bizType === '资产转移'" class="sec-block">
          <div class="sec-block__title">资产转移明细（账实一致，签核同事务搬移）</div>
          <div class="meta-row meta-row--inner">
            <div class="meta-item"><span class="meta-label">来源卡</span><span class="meta-val">{{ selected.fromCardNo || '—' }}</span></div>
            <div class="meta-item"><span class="meta-label">目标卡</span><span class="meta-val">{{ selected.toCardNo || '—' }}</span></div>
            <div class="meta-item"><span class="meta-label">转移次数</span><span class="meta-val">{{ selected.transferTimes }} 次</span></div>
            <div class="meta-item"><span class="meta-label">转移金额</span><span class="meta-val rp__amount">¥{{ yuan(selected.transferAmount) }}</span></div>
          </div>
        </div>

        <div v-if="selected.note" class="sec-block">
          <div class="sec-block__title">备注</div>
          <div class="rsec__val">{{ selected.note }}</div>
        </div>

        <!-- 签核留痕 -->
        <div class="sec-block">
          <div class="sec-block__title">三方签核留痕</div>
          <div class="sign-grid">
            <div class="sign-cell">
              <span class="sign-cell__role">客户确认</span>
              <span class="sign-cell__name">{{ selected.sign1 || '待签' }}</span>
              <span class="sign-cell__time">{{ selected.signedAt1 ? fmtDateTime(selected.signedAt1) : '—' }}</span>
            </div>
            <div class="sign-cell">
              <span class="sign-cell__role">经办（咨询师/前台）</span>
              <span class="sign-cell__name">{{ selected.sign2 || '待签' }}</span>
              <span class="sign-cell__time">{{ selected.signedAt2 ? fmtDateTime(selected.signedAt2) : '—' }}</span>
            </div>
            <div class="sign-cell">
              <span class="sign-cell__role">店长</span>
              <span class="sign-cell__name">{{ selected.sign3 || '待签' }}</span>
              <span class="sign-cell__time">{{ selected.signedAt3 ? fmtDateTime(selected.signedAt3) : '—' }}</span>
            </div>
          </div>
        </div>
      </template>

      <template #foot>
        <template v-if="selected && selected.status === '待签核'">
          <div class="foot-sign">
            <div class="foot-sign__row">
              <CInput v-model="signForm.sign1" placeholder="客户确认签名（姓名）" />
              <CInput v-model="signForm.sign2" placeholder="经办签名（咨询师/前台）" />
              <CInput v-model="signForm.sign3" placeholder="店长签名" />
            </div>
            <div v-if="signError" class="foot-sign__err">{{ signError }}</div>
          </div>
          <CButton variant="danger" :disabled="!!signError || submitting" v-perm.disable="'followup:edit'" @click="doSign(true)">
            拒签
          </CButton>
          <CButton variant="primary" :disabled="!!signError || submitting" v-perm.disable="'followup:edit'" @click="doSign(false)">
            <CIcon name="sign" :size="16" />三方签核通过
          </CButton>
        </template>
        <template v-else-if="selected">
          <span class="wbs-foot-done">
            <CIcon :name="selected.status === '已完成' ? 'check-square' : 'alert'" :size="15" />
            {{ selected.status === '已完成' ? '单据已生效，动作已入审计链' : '单据已拒绝并留痕' }}
          </span>
        </template>
      </template>
    </CWorkbenchShell>

    <!-- 新建单据弹层 -->
    <div v-if="showForm" class="modal-mask" @click.self="closeForm">
      <CCard class="modal" title="新建复购 / 资产转移单" padding="lg">
        <div class="nform">
          <div class="nform__row">
            <label class="nform__label">客户 <span class="req">*</span></label>
            <div class="pick">
              <CInput v-model="form.customerKeyword" placeholder="输入客户姓名 / 手机号 / 客户编号后点检索" />
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

          <div class="nform__row nform__row--2">
            <div>
              <label class="nform__label">业务类型 <span class="req">*</span></label>
              <CSelect v-model="form.bizType" width="100%" :options="[
                { value: '复购', label: '复购' },
                { value: '资产转移', label: '资产转移' },
              ]" />
            </div>
            <div v-if="!isTransfer">
              <label class="nform__label">目标项目 <span class="req">*</span></label>
              <CInput v-model="form.targetProject" placeholder="如：黄金射频微针 3 次套餐" />
            </div>
          </div>

          <template v-if="isTransfer">
            <div class="nform__group-title">卡余额 / 次数转移</div>
            <div v-if="cardsLoading" class="nform__hint">会员卡加载中…</div>
            <div v-else-if="!customerCards.length && pickedCustomer" class="nform__hint nform__hint--warn">
              该客户名下无可转移会员卡，请先开卡
            </div>
            <div class="nform__row nform__row--2">
              <div>
                <label class="nform__label">来源卡 <span class="req">*</span></label>
                <CSelect v-model="form.fromCardNo" width="100%" placeholder="选择扣出卡" :options="cardOptions" />
              </div>
              <div>
                <label class="nform__label">目标卡 <span class="req">*</span></label>
                <CSelect v-model="form.toCardNo" width="100%" placeholder="选择入卡" :options="cardOptions" />
              </div>
            </div>
            <div class="nform__row nform__row--2">
              <div>
                <label class="nform__label">转移次数</label>
                <CInput v-model="form.transferTimes" type="number" placeholder="0" />
              </div>
              <div>
                <label class="nform__label">转移金额（元）</label>
                <CInput v-model="form.transferAmount" type="number" placeholder="0.00" />
              </div>
            </div>
            <div v-if="accountError" class="nform__hint nform__hint--warn">{{ accountError }}</div>
          </template>

          <div class="nform__row">
            <label class="nform__label">备注</label>
            <CTextarea v-model="form.note" placeholder="经办人补充说明（选填）" />
          </div>

          <div class="nform__group-title">知情同意</div>
          <label class="consent">
            <input v-model="form.consent" type="checkbox" />
            <span>客户已阅读并签署《复购/资产转移知情同意书》，知悉项目内容、费用与卡项变动 <span class="req">*</span></span>
          </label>
          <div class="nform__row">
            <label class="nform__label">同意书文本/编号（选填）</label>
            <CInput v-model="form.consentText" placeholder="如：纸质同意书编号 CS-20260910-018" />
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="closeForm">取消</CButton>
          <CButton variant="primary" :disabled="!canCreate" @click="submitCreate">创建待签核单</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.rp { display: flex; flex-direction: column; gap: var(--s-lg); }

.rp__detail-title { font-size: var(--t-md); line-height: var(--lh-md); font-weight: 700; color: var(--c-text); margin: 0; display: flex; align-items: center; gap: var(--s-sm); }
.rp__type-tag { font-size: var(--t-xs); font-weight: 400; padding: 1px 8px; background: var(--c-brand-soft); color: var(--c-brand); border-radius: var(--r-pill); }
.rp__amount { color: var(--c-danger-fg); font-weight: 700; }
.wb-head { display: flex; justify-content: space-between; align-items: center; gap: var(--s-sm); }

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
}
.rec:hover { background: var(--c-brand-soft); }
.rec--active { background: var(--c-brand-soft); }
.rec__top { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; }
.rec__name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.rec__sub { display: flex; align-items: center; gap: var(--s-xs); font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: 4px; }
.rec__type { color: var(--c-brand); font-weight: 600; }
.rec__diag { font-size: var(--t-xs); color: var(--c-text-2); margin-bottom: var(--s-xs); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.rec__meta { display: flex; justify-content: space-between; font-size: var(--t-xs); color: var(--c-text-3); }
.rec__meta span { display: inline-flex; align-items: center; gap: 3px; }

.consent-bar {
  display: flex; gap: var(--s-sm); align-items: flex-start;
  padding: var(--s-md); background: var(--c-brand-soft); border-radius: var(--r-md);
  font-size: var(--t-sm); color: var(--c-text-2); line-height: 1.6; margin-bottom: var(--s-md);
}
.consent-bar strong { color: var(--c-success-fg, #389e0d); }

.meta-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); padding-bottom: var(--s-md); border-bottom: 1px solid var(--c-border-light); }
.meta-row--inner { border-bottom: none; padding-bottom: 0; }
.meta-item { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.meta-label { font-size: var(--t-xs); color: var(--c-text-3); }
.meta-val { font-size: var(--t-sm); color: var(--c-text); font-weight: 500; word-break: break-all; }

.sec-block { padding-top: var(--s-md); }
.sec-block__title { font-size: var(--t-xs); font-weight: 700; color: var(--c-text-2); margin-bottom: var(--s-sm); }
.rsec__val { font-size: var(--t-sm); color: var(--c-text); line-height: 1.7; white-space: pre-wrap; }

.sign-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--s-md); }
.sign-cell { display: flex; flex-direction: column; gap: 4px; padding: var(--s-md); background: var(--c-bg-page); border: 1px solid var(--c-border-light); border-radius: var(--r-md); }
.sign-cell__role { font-size: var(--t-xs); color: var(--c-text-3); }
.sign-cell__name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.sign-cell__time { font-size: var(--t-xs); color: var(--c-text-4); }

.wbs-foot-done { display: inline-flex; align-items: center; gap: var(--s-xs); color: var(--c-success-fg, #389e0d); font-size: var(--t-sm); font-weight: 600; margin-right: auto; }
.foot-sign { flex: 1; display: flex; flex-direction: column; gap: var(--s-xs); margin-right: auto; min-width: 0; }
.foot-sign__row { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--s-sm); width: 100%; }
.foot-sign__err { font-size: var(--t-xs); color: var(--c-danger-fg); }

.modal-mask { position: fixed; inset: 0; background: rgba(20,21,43,.45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 640px; max-width: 100%; max-height: 90vh; overflow-y: auto; box-shadow: var(--shadow-pop); }
.nform { display: flex; flex-direction: column; gap: var(--s-md); }
.nform__group-title {
  font-size: var(--t-xs); font-weight: 700; color: var(--c-text-2);
  padding-top: var(--s-sm); margin-top: var(--s-xs);
  border-top: 1px dashed var(--c-border);
  display: flex; align-items: center; gap: var(--s-xs);
}
.nform__group-title:first-child { border-top: none; padding-top: 0; margin-top: 0; }
.nform__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.nform__row--2 { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.nform__label { font-size: var(--t-xs); color: var(--c-text-3); }
.nform__hint { font-size: var(--t-xs); color: var(--c-text-3); }
.nform__hint--warn { color: var(--c-danger-fg); }
.req { color: var(--c-danger-fg); }

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

.consent { display: flex; align-items: flex-start; gap: var(--s-sm); font-size: var(--t-sm); color: var(--c-text-2); line-height: 1.6; cursor: pointer; }
.consent input { margin-top: 3px; }

@media (max-width: 1100px) {
  .meta-row { grid-template-columns: repeat(2, 1fr); }
  .sign-grid { grid-template-columns: 1fr; }
  .foot-sign__row { grid-template-columns: 1fr; }
}
</style>
