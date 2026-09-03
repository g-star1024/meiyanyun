<script setup lang="ts">
/* ============================================================
 * 划扣执行台 /m2-writeoff-desk（M2-01）
 * 门店端今日待划扣队列 + 双签执行区。独立新页，不改 WriteoffView。
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CWorkbenchShell from '@/components/CWorkbenchShell.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CTextarea from '@/components/CTextarea.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import { useWriteoffDeskStore, type WriteoffDeskItem, type WdExceptionReason, type WdCardOption } from '@/stores/writeoffDesk'
import { searchCustomers, type CustomerDTO } from '@/api/customer'
import { WRITEOFF_DESK_STATUS, dictPill } from '@/config/dictionary'
import { useToast } from '@/composables/useToast'

const store = useWriteoffDeskStore()
const toast = useToast()
onMounted(() => store.seed())

const selectedId = ref<string | null>(null)
const selected = computed<WriteoffDeskItem | null>(() => {
  if (selectedId.value) return store.get(selectedId.value) ?? null
  return store.filtered[0] ?? null
})

const kpis = computed(() => [
  { label: '待划扣', icon: 'finance', value: String(store.pending.length), tone: 'warning' as const },
  { label: '已完成', icon: 'dashboard', value: String(store.done.length), tone: 'success' as const },
  { label: '异常', icon: 'alert', value: String(store.exception.length), tone: 'danger' as const },
  { label: '今日划扣金额', icon: 'finance', value: `¥${store.todayAmount.toLocaleString()}`, tone: 'brand' as const },
])

const sourceOptions = [
  { value: 'ALL', label: '全部来源' },
  { value: 'APPOINTMENT', label: '预约到店' },
  { value: 'WALKIN', label: '直接到店' },
]
const statusOptions = [
  { value: 'ALL', label: '全部状态' },
  { value: 'PENDING', label: '待执行' },
  { value: 'DONE', label: '已划扣' },
  { value: 'EXCEPTION', label: '异常' },
]
const exOptions = [
  { value: 'CUSTOMER_ABSENT', label: '客户未到' },
  { value: 'COUNT_MISMATCH', label: '次数不符' },
  { value: 'EQUIPMENT_FAULT', label: '设备故障' },
  { value: 'OTHER', label: '其他' },
]

function fmtTime(iso?: string) {
  if (!iso) return '—'
  const d = new Date(iso)
  return `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

// 执行划扣弹层（双签 + 卡选择器）
const showExec = ref(false)
const execForm = ref({ reviewer: '', remark: '', cardNo: '' })
const execCards = ref<WdCardOption[]>([])
const execSubmitting = ref(false)
const execCardOptions = computed(() =>
  execCards.value.map((c) => ({
    value: c.cardNo,
    label: `${c.cardName} · 余 ${c.remainTimes}/${c.totalTimes} 次 · ¥${c.unitAmount.toLocaleString()}/次`,
  })),
)
const execSingleCard = computed(() => (execCards.value.length === 1 ? execCards.value[0] : null))
const canExec = computed(() => execForm.value.reviewer.trim().length > 0 && !execSubmitting.value)
async function openExec() {
  if (!selected.value || selected.value.status !== 'PENDING') return
  execForm.value = { reviewer: '', remark: '', cardNo: selected.value.cardNo || '' }
  execCards.value = []
  showExec.value = true
  if (selected.value.customerId) {
    execCards.value = await store.customerCards(selected.value.customerId, selected.value.storeCode)
    const bound = execCards.value.find((c) => c.cardNo === selected.value?.cardNo)
    execForm.value.cardNo = bound ? bound.cardNo : (execCards.value[0]?.cardNo || '')
  }
}
async function submitExec() {
  if (!selected.value || !canExec.value) return
  execSubmitting.value = true
  try {
    const res = await store.execute(
      selected.value.id,
      execForm.value.reviewer,
      execForm.value.cardNo || undefined,
      execForm.value.remark || undefined,
    )
    if (res.ok) {
      showExec.value = false
      toast.success(`双签划扣完成，复核人 ${execForm.value.reviewer.trim()}`)
    } else {
      toast.error(res.reason || '划扣失败')
    }
  } finally {
    execSubmitting.value = false
  }
}

// 异常标记弹层
const showEx = ref(false)
const exForm = ref<{ reason: WdExceptionReason; note: string }>({ reason: 'CUSTOMER_ABSENT', note: '' })
const exSubmitting = ref(false)
function openEx() {
  if (!selected.value || selected.value.status === 'DONE') return
  exForm.value = { reason: 'CUSTOMER_ABSENT', note: '' }
  showEx.value = true
}
async function submitEx() {
  if (!selected.value || exSubmitting.value) return
  exSubmitting.value = true
  try {
    const res = await store.markException(selected.value.id, exForm.value.reason, exForm.value.note || undefined)
    if (res.ok) {
      showEx.value = false
      toast.error('已标记异常，该单暂不可划扣')
    } else {
      toast.error(res.reason || '标记失败')
    }
  } finally {
    exSubmitting.value = false
  }
}
async function doReset() {
  if (!selected.value) return
  const res = await store.resetToPending(selected.value.id)
  if (res.ok) toast.info('已解除异常，恢复待执行')
  else toast.error(res.reason || '操作失败')
}

// 老客未预约直接到店：手工建单弹层
const showWalkin = ref(false)
const walkinForm = ref({ customerId: '', project: '', cardNo: '' })
const walkinKeyword = ref('')
const walkinResults = ref<CustomerDTO[]>([])
const walkinCustomer = ref<CustomerDTO | null>(null)
const walkinCards = ref<WdCardOption[]>([])
const walkinSubmitting = ref(false)
let walkinTimer: ReturnType<typeof setTimeout> | null = null

function onWalkinSearch() {
  if (walkinTimer) clearTimeout(walkinTimer)
  const kw = walkinKeyword.value.trim()
  if (!kw) { walkinResults.value = []; return }
  walkinTimer = setTimeout(async () => {
    try {
      const res = await searchCustomers(kw)
      walkinResults.value = res.data || []
    } catch (e) {
      console.error('[writeoff-desk] 客户搜索失败', e)
    }
  }, 300)
}
async function pickWalkinCustomer(c: CustomerDTO) {
  walkinCustomer.value = c
  walkinForm.value.customerId = c.customerId
  walkinForm.value.cardNo = ''
  walkinKeyword.value = `${c.name}（${c.phone}）`
  walkinResults.value = []
  walkinCards.value = await store.customerCards(c.customerId)
}
const walkinCardOptions = computed(() => [
  ...(walkinCards.value.length > 1
    ? [{ value: '', label: '不指定，使用本店最新在用卡' }]
    : []),
  ...walkinCards.value.map((c) => ({
    value: c.cardNo,
    label: `${c.cardName} · 余 ${c.remainTimes}/${c.totalTimes} 次 · ¥${c.unitAmount.toLocaleString()}/次`,
  })),
])
const canWalkin = computed(
  () => !!walkinCustomer.value && walkinCards.value.length > 0
    && walkinForm.value.project.trim().length > 0 && !walkinSubmitting.value,
)
function openWalkin() {
  walkinForm.value = { customerId: '', project: '', cardNo: '' }
  walkinKeyword.value = ''
  walkinResults.value = []
  walkinCustomer.value = null
  walkinCards.value = []
  showWalkin.value = true
}
async function submitWalkin() {
  if (!canWalkin.value) return
  walkinSubmitting.value = true
  try {
    const res = await store.createWalkin({
      customerId: walkinForm.value.customerId,
      project: walkinForm.value.project,
      cardNo: walkinForm.value.cardNo || undefined,
    })
    if (res.ok && res.item) {
      showWalkin.value = false
      selectedId.value = res.item.id
      toast.success(`已建直接到店划扣任务 ${res.item.no}`)
    } else {
      toast.error(res.reason || '建单失败')
    }
  } finally {
    walkinSubmitting.value = false
  }
}
</script>

<template>
  <div class="wd">
    <CWorkbenchShell
      :has-selection="!!selected"
      empty-icon="check-square"
      empty-title="请选择一条划扣任务"
      empty-desc="待执行任务经双签划扣次数；异常单需先解除处理"
      list-width="380px"
    >
      <template #kpis>
        <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
      </template>

      <template #toolbar>
        <CButton variant="primary" size="sm" v-perm.disable="'writeoff:create'" @click="openWalkin">
          <CIcon name="plus" :size="14" />直接到店建单
        </CButton>
      </template>

      <!-- 左：列表 -->
      <template #list>
        <div class="filters">
          <CSelect v-model="store.filterSource" :options="sourceOptions" width="130px" />
          <CSelect v-model="store.filterStatus" :options="statusOptions" width="120px" />
        </div>
        <div class="list">
          <div v-if="store.filtered.length === 0" class="empty">
            <CIcon name="check-square" :size="28" class="empty__icon" />
            <div>暂无划扣任务</div>
          </div>
          <button
            v-for="it in store.filtered" :key="it.id"
            class="row" :class="{ 'row--active': selected?.id === it.id, 'row--ex': it.status === 'EXCEPTION' }"
            @click="selectedId = it.id"
          >
            <div class="row__top">
              <span class="row__no">{{ it.no }}</span>
              <CStatusPill :status="dictPill(WRITEOFF_DESK_STATUS[it.status]).status">{{ dictPill(WRITEOFF_DESK_STATUS[it.status]).text }}</CStatusPill>
            </div>
            <div class="row__title">{{ it.customerName }} · {{ it.project }}</div>
            <div class="row__meta">
              <span><CIcon name="clock" :size="12" /> {{ fmtTime(it.appointmentTime) }}</span>
              <span><CIcon name="card" :size="12" /> 余 {{ it.remainingCount }}/{{ it.totalCount }}</span>
            </div>
            <div class="row__amount">¥{{ it.amount.toLocaleString() }}</div>
          </button>
        </div>
      </template>

      <!-- 右：详情 -->
      <template #head>
        <div v-if="selected" class="wb-head">
          <h3 class="wd__detail-title">{{ selected.no }}</h3>
          <CStatusPill :status="dictPill(WRITEOFF_DESK_STATUS[selected.status]).status">{{ dictPill(WRITEOFF_DESK_STATUS[selected.status]).text }}</CStatusPill>
        </div>
      </template>

      <template v-if="selected">
        <div class="detail__head">
          <div>
            <div class="detail__title">{{ selected.customerName }}</div>
            <div class="detail__sub">
              <span class="tag tag--src"><CIcon :name="(selected.source === 'APPOINTMENT' ? 'calendar' : 'store') as any" :size="12" /> {{ store.SOURCE_LABEL[selected.source] }}</span>
              <span class="tag">{{ selected.phone }}</span>
            </div>
          </div>
          <div class="detail__assign">
            <div class="detail__assign-label">操作人</div>
            <div class="detail__assign-name">{{ selected.operator }}</div>
          </div>
        </div>

        <div class="detail__grid">
          <div class="field"><span class="field__label">核销项目</span><span class="field__val">{{ selected.project }}</span></div>
          <div class="field"><span class="field__label">所属卡项</span><span class="field__val">{{ selected.cardName }}</span></div>
          <div class="field"><span class="field__label">剩余次数</span><span class="field__val">{{ selected.remainingCount }} / {{ selected.totalCount }}</span></div>
          <div class="field"><span class="field__label">本次金额</span><span class="field__val is-brand">¥{{ selected.amount.toLocaleString() }}</span></div>
          <div class="field"><span class="field__label">预约/到店时间</span><span class="field__val">{{ fmtTime(selected.appointmentTime) }}</span></div>
          <div class="field"><span class="field__label">划扣时间</span><span class="field__val">{{ fmtTime(selected.executedAt) }}</span></div>
          <div v-if="selected.reviewer" class="field"><span class="field__label">复核人</span><span class="field__val">{{ selected.reviewer }}</span></div>
          <div v-if="selected.status === 'EXCEPTION'" class="field">
            <span class="field__label">异常原因</span>
            <span class="field__val is-danger">{{ store.EXCEPTION_LABEL[selected.exceptionReason] }}</span>
          </div>
        </div>

        <div class="detail__notes">
          <div class="detail__sec-title">操作记录</div>
          <div v-for="(t, i) in selected.timeline" :key="i" class="note">
            <span class="note__who">{{ t.by }}</span>
            <span class="note__text">{{ t.text }}</span>
            <span class="note__time">{{ fmtTime(t.at) }}</span>
          </div>
        </div>
      </template>

      <template #foot>
        <template v-if="selected">
          <template v-if="selected.status === 'PENDING'">
            <CButton variant="ghost" v-perm.disable="'writeoff:edit'" @click="openEx">
              <CIcon name="alert" :size="16" />标记异常
            </CButton>
            <CButton variant="primary" v-perm.disable="'writeoff:create'" @click="openExec">
              <CIcon name="check-square" :size="16" />双签划扣
            </CButton>
          </template>
          <template v-else-if="selected.status === 'EXCEPTION'">
            <CButton variant="ghost" v-perm.disable="'writeoff:edit'" @click="doReset">解除异常</CButton>
            <span class="ops__hint">异常单暂不可划扣，请先处理</span>
          </template>
          <template v-else>
            <span class="wbs-foot-done">
              <CIcon name="check" :size="15" />已于 {{ fmtTime(selected.executedAt) }} 划扣完成
            </span>
          </template>
        </template>
      </template>
    </CWorkbenchShell>

    <!-- 执行划扣弹层（双签） -->
    <div v-if="showExec" class="modal-mask" @click.self="showExec = false">
      <CCard class="modal" title="执行划扣（双签）" padding="lg">
        <div class="form">
          <div class="sign-box">
            <div class="sign-box__title"><CIcon name="shield" :size="16" /> 双签确认</div>
            <div class="sign-box__text">操作人：{{ selected?.operator }}　|　客户：{{ selected?.customerName }}（{{ selected?.project }}）</div>
          </div>
          <div v-if="execCards.length > 1" class="form__row">
            <label class="form__label">划扣所用卡</label>
            <CSelect v-model="execForm.cardNo" :options="execCardOptions" width="100%" />
          </div>
          <div v-else-if="execSingleCard" class="form__row">
            <label class="form__label">划扣所用卡</label>
            <div class="card-line">
              <CIcon name="card" :size="13" />
              {{ execSingleCard.cardName }} · 余 {{ execSingleCard.remainTimes }}/{{ execSingleCard.totalTimes }} 次 · ¥{{ execSingleCard.unitAmount.toLocaleString() }}/次
            </div>
          </div>
          <div class="form__row">
            <label class="form__label">复核人 <span class="req">*</span></label>
            <CInput v-model="execForm.reviewer" placeholder="请输入复核人姓名，如：陈雅琳（店长）" />
          </div>
          <div class="form__row">
            <label class="form__label">备注（可选）</label>
            <CTextarea v-model="execForm.remark" placeholder="可填写操作说明" />
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showExec = false">取消</CButton>
          <CButton variant="primary" :disabled="!canExec" @click="submitExec">确认划扣</CButton>
        </template>
      </CCard>
    </div>

    <!-- 异常标记弹层 -->
    <div v-if="showEx" class="modal-mask" @click.self="showEx = false">
      <CCard class="modal modal--sm" title="标记异常" padding="lg">
        <div class="form">
          <div class="form__row">
            <label class="form__label">异常原因</label>
            <CSelect v-model="exForm.reason" :options="exOptions" width="100%" />
          </div>
          <div class="form__row">
            <label class="form__label">补充说明（可选）</label>
            <CTextarea v-model="exForm.note" placeholder="描述具体情况" />
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showEx = false">取消</CButton>
          <CButton variant="danger" :disabled="exSubmitting" @click="submitEx">确认标记</CButton>
        </template>
      </CCard>
    </div>

    <!-- 老客未预约直接到店：手工建单弹层 -->
    <div v-if="showWalkin" class="modal-mask" @click.self="showWalkin = false">
      <CCard class="modal" title="直接到店建单" padding="lg">
        <div class="form">
          <div class="sign-box">
            <div class="sign-box__title"><CIcon name="store" :size="16" /> 老客未预约到店</div>
            <div class="sign-box__text">新客请先走前台「登记 → 分诊 → 预约」流程；此处仅为已有档案的老客直接建划扣任务。</div>
          </div>
          <div class="form__row">
            <label class="form__label">到店客户 <span class="req">*</span></label>
            <div class="cust-search">
              <CInput v-model="walkinKeyword" placeholder="输入客户姓名 / 手机号搜索" @input="onWalkinSearch" />
              <div v-if="walkinResults.length" class="cust-search__drop">
                <button
                  v-for="c in walkinResults" :key="c.customerId" type="button"
                  class="cust-search__item" @click="pickWalkinCustomer(c)"
                >
                  <span class="cust-search__name">{{ c.name }}</span>
                  <span class="cust-search__phone">{{ c.phone }}</span>
                  <span class="cust-search__level">{{ c.level }}</span>
                </button>
              </div>
            </div>
            <div v-if="walkinCustomer" class="card-line">
              <CIcon name="check" :size="13" /> 已选：{{ walkinCustomer.name }}（{{ walkinCustomer.phone }}）
            </div>
          </div>
          <div class="form__row">
            <label class="form__label">核销项目 <span class="req">*</span></label>
            <CInput v-model="walkinForm.project" placeholder="如：超声炮全脸提拉" />
          </div>
          <div v-if="walkinCardOptions.length > 0" class="form__row">
            <label class="form__label">划扣所用卡</label>
            <CSelect v-model="walkinForm.cardNo" :options="walkinCardOptions" width="100%" />
          </div>
          <div v-else-if="walkinCustomer && walkinCards.length === 0" class="form__row">
            <div class="card-line is-danger">该客户在本门店无在用会员卡，无法建单</div>
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showWalkin = false">取消</CButton>
          <CButton variant="primary" :disabled="!canWalkin" @click="submitWalkin">确认建单</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.wd { display: flex; flex-direction: column; gap: var(--s-lg); }

.filters { display: flex; gap: var(--s-sm); padding: var(--s-md); border-bottom: 1px solid var(--c-border-light); flex-shrink: 0; }
.list { flex: 1; min-height: 0; overflow-y: auto; }
.empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); font-size: var(--t-sm); }
.empty__icon { color: var(--c-text-4); }

.row {
  display: block; width: 100%; text-align: left; padding: var(--s-md) var(--s-lg);
  background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer;
}
.row:hover { background: var(--c-brand-soft); }
.row--active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.row--ex { border-left: none; }
.row--ex .row__title { color: var(--c-danger-fg); }
.row__top { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--s-xs); }
.row__no { font-size: var(--t-xs); color: var(--c-text-3); font-weight: 600; }
.row__title { font-size: var(--t-sm); color: var(--c-text); margin-bottom: var(--s-xs); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; font-weight: 600; }
.row__meta { display: flex; flex-wrap: wrap; gap: var(--s-sm); font-size: var(--t-xs); color: var(--c-text-3); align-items: center; }
.row__meta span { display: inline-flex; align-items: center; gap: 3px; }
.row__amount { margin-top: var(--s-xs); font-size: var(--t-sm); font-weight: 700; color: var(--c-brand); font-variant-numeric: tabular-nums; }

.wd__detail-title { font-size: var(--t-md); font-weight: 700; margin: 0; }
.wb-head { display: flex; justify-content: space-between; align-items: center; gap: var(--s-sm); }
.detail__head { display: flex; justify-content: space-between; gap: var(--s-md); padding-bottom: var(--s-md); border-bottom: 1px solid var(--c-border-light); }
.detail__title { font-size: var(--t-lg); font-weight: 700; color: var(--c-text); }
.detail__sub { display: flex; flex-wrap: wrap; gap: var(--s-xs); margin-top: var(--s-xs); }
.tag { font-size: var(--t-xs); padding: 2px 8px; border-radius: var(--r-sm); background: var(--c-disabled-bg); color: var(--c-text-2); display: inline-flex; align-items: center; gap: 3px; }
.tag--src { background: var(--c-brand-soft); color: var(--c-brand); }
.detail__assign { text-align: right; }
.detail__assign-label { font-size: var(--t-xs); color: var(--c-text-3); }
.detail__assign-name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }

.detail__grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md) var(--s-lg); margin: var(--s-lg) 0; }
.field { display: flex; flex-direction: column; gap: 2px; }
.field__label { font-size: var(--t-xs); color: var(--c-text-3); }
.field__val { font-size: var(--t-sm); color: var(--c-text); }
.is-brand { color: var(--c-brand); font-weight: 700; }
.is-danger { color: var(--c-danger-fg); font-weight: 600; }

.detail__notes { margin-bottom: 0; }
.detail__sec-title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); margin-bottom: var(--s-sm); }
.note { display: flex; gap: var(--s-sm); align-items: baseline; padding: var(--s-xs) 0; border-bottom: 1px solid var(--c-border-light); font-size: var(--t-sm); }
.note:last-child { border-bottom: none; }
.note__who { font-weight: 600; color: var(--c-text); flex-shrink: 0; }
.note__text { color: var(--c-text-2); flex: 1; }
.note__time { font-size: var(--t-xs); color: var(--c-text-3); flex-shrink: 0; }

.ops__hint { font-size: var(--t-sm); color: var(--c-text-3); margin-right: auto; }
.wbs-foot-done { display: inline-flex; align-items: center; gap: var(--s-xs); color: var(--c-success-fg); font-size: var(--t-sm); font-weight: 600; margin-right: auto; }

.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 520px; max-width: 100%; max-height: 90vh; overflow-y: auto; box-shadow: var(--shadow-pop); }
.modal--sm { width: 400px; }
.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.form__label { font-size: var(--t-xs); color: var(--c-text-3); }
.req { color: var(--c-danger-fg); }
.sign-box { background: var(--c-brand-soft); border-radius: var(--r-md); padding: var(--s-md); }
.sign-box__title { display: flex; align-items: center; gap: var(--s-xs); font-size: var(--t-sm); font-weight: 600; color: var(--c-brand); margin-bottom: var(--s-xxs); }
.sign-box__text { font-size: var(--t-sm); color: var(--c-text-2); }

.card-line { display: flex; align-items: center; gap: var(--s-xs); font-size: var(--t-sm); color: var(--c-text-2); background: var(--c-disabled-bg); border-radius: var(--r-sm); padding: var(--s-xs) var(--s-sm); }
.card-line.is-danger { color: var(--c-danger-fg); background: rgba(229,57,53,.08); }
.cust-search { position: relative; }
.cust-search__drop { position: absolute; z-index: 300; top: calc(100% + 4px); left: 0; right: 0; background: var(--c-bg, #fff); border: 1px solid var(--c-border); border-radius: var(--r-md); box-shadow: var(--shadow-pop); max-height: 220px; overflow-y: auto; padding: 4px; }
.cust-search__item { display: flex; align-items: center; gap: var(--s-sm); width: 100%; text-align: left; padding: var(--s-xs) var(--s-sm); background: none; border: none; border-radius: var(--r-sm); cursor: pointer; font-size: var(--t-sm); }
.cust-search__item:hover { background: var(--c-brand-soft); }
.cust-search__name { font-weight: 600; color: var(--c-text); }
.cust-search__phone { color: var(--c-text-3); font-variant-numeric: tabular-nums; }
.cust-search__level { margin-left: auto; font-size: var(--t-xs); color: var(--c-brand); background: var(--c-brand-soft); border-radius: var(--r-sm); padding: 1px 6px; }

@media (max-width: 1024px) {
  .detail__head { flex-direction: column; gap: var(--s-sm); }
  .detail__assign { text-align: left; }
}
</style>
