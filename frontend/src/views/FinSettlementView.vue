<script setup lang="ts">
/* ============================================================
 * M6-05 分账结算 /m6-settlement
 * 4 KPI（应分账总额/门店分成/专家提成/平台留存）
 * 上：分账规则与构成；中：分账明细列表；右：详情 + 回单双签
 * 红线：仅镜像登记分账单与回单状态，真实资金分账由渠道/银行完成
 * 适配已有 store: useFinSettlementStore
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CSelect from '@/components/CSelect.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import { useFinSettlementStore } from '@/stores/finSettlement'
import { useAuthStore } from '@/stores/auth'

const store = useFinSettlementStore()
const auth = useAuthStore()
onMounted(() => store.seed())

const selectedId = ref<string | null>(null)
const selected = computed(() => {
  if (selectedId.value) return store.get(selectedId.value) ?? null
  return store.filtered[0] ?? null
})

const canEdit = computed(() => auth.can('finance:settlement:edit'))

const expertShare = computed(() => (store.byRole.DOCTOR ?? 0) + (store.byRole.CONSULTANT ?? 0))
const platformShare = computed(() =>
  (store.byRole.PLATFORM ?? 0) + (store.byRole.CHANNEL ?? 0) + (store.byRole.TAX ?? 0),
)

const kpis = computed(() => [
  { label: '本期应分账总额', icon: 'finance', value: `¥${(store.totalAmount / 10000).toFixed(2)}万`, tone: 'brand' as const },
  { label: '门店分成', icon: 'store', value: `¥${((store.byRole.STORE ?? 0) / 10000).toFixed(2)}万`, tone: 'teal' as const },
  { label: '专家/咨询师提成', icon: 'chat', value: `¥${(expertShare.value / 10000).toFixed(2)}万`, tone: 'orange' as const },
  { label: '平台及税费', icon: 'finance', value: `¥${(platformShare.value / 10000).toFixed(2)}万`, tone: 'text' as const },
])

const statusOptions = [
  { value: 'ALL', label: '全部状态' },
  { value: 'PENDING', label: '待计算' },
  { value: 'CALCULATED', label: '已计算' },
  { value: 'SUBMITTED', label: '已提交' },
  { value: 'CONFIRMED', label: '已回单' },
  { value: 'FAILED', label: '分账失败' },
]
const roleOptions = [
  { value: 'ALL', label: '全部分账方' },
  { value: 'PLATFORM', label: '平台' },
  { value: 'STORE', label: '门店' },
  { value: 'DOCTOR', label: '医生' },
  { value: 'CONSULTANT', label: '咨询师' },
  { value: 'CHANNEL', label: '渠道分润' },
  { value: 'TAX', label: '代扣税费' },
]

function money(n: number) {
  return `¥${n.toLocaleString('zh-CN')}`
}
function pct(n: number) {
  return `${(n * 100).toFixed(1)}%`
}

// 分账构成（用于可视化）
const composition = computed(() => [
  { label: '门店', value: store.byRole.STORE ?? 0, color: 'var(--c-series-1)' },
  { label: '专家/咨询师', value: expertShare.value, color: 'var(--c-series-2)' },
  { label: '平台及税费', value: platformShare.value, color: 'var(--c-series-3)' },
])
const compTotal = computed(() => composition.value.reduce((s, x) => s + x.value, 0) || 1)

function doCalculate() {
  const today = new Date().toISOString().slice(0, 10)
  store.calculate(today)
}
function doSubmitBatch() {
  if (selected.value) store.submitBatch(selected.value.batchNo)
}

// 回单确认双签
const showConfirm = ref(false)
const confirmForm = ref({ reviewer: '', remark: '' })
function openConfirm() { confirmForm.value = { reviewer: '', remark: '' }; showConfirm.value = true }
const canConfirm = computed(() => confirmForm.value.reviewer.trim().length >= 2)
function submitConfirm() {
  if (!selected.value || !canConfirm.value) return
  store.confirm(selected.value.id, confirmForm.value.remark.trim())
  showConfirm.value = false
}

// 失败登记
const showFail = ref(false)
const failReason = ref('')
function openFail() { failReason.value = ''; showFail.value = true }
function submitFail() {
  if (!selected.value || !failReason.value.trim()) return
  store.markFailed(selected.value.id, failReason.value.trim())
  showFail.value = false
}
</script>

<template>
  <div class="st">
    <div class="st__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <!-- 分账构成卡 -->
    <CCard class="st__rule" padding="lg">
      <div class="rule">
        <div class="rule__head">
          <div class="rule__title"><CIcon name="finance" :size="16" /> 分账构成（本期镜像）</div>
          <div class="rule__base">
            待回单金额 <span class="rule__base-amt">{{ money(store.pendingAmount) }}</span>
            <span class="rule__fail" v-if="store.failedCount > 0">· {{ store.failedCount }} 笔失败</span>
          </div>
        </div>
        <div class="rule__bar">
          <div
            v-for="(seg, i) in composition" :key="i"
            class="rule__seg" :style="{ width: (seg.value / compTotal * 100) + '%', background: seg.color }"
            :title="`${seg.label} ${money(seg.value)}`"
          />
        </div>
        <div class="rule__legend">
          <div v-for="(seg, i) in composition" :key="i" class="rule__leg-item">
            <span class="rule__dot" :style="{ background: seg.color }" />
            <span class="rule__leg-label">{{ seg.label }}</span>
            <span class="rule__leg-amt">{{ money(seg.value) }}（{{ pct(seg.value / compTotal) }}）</span>
          </div>
        </div>
        <p class="rule__note">分账单为渠道/银行真实分账的本地镜像；本系统不发起真实资金分账，回单后登记确认。</p>
      </div>
    </CCard>

    <div class="st__body">
      <!-- 分账明细列表 -->
      <CCard class="st__list" padding="none">
        <div class="filters">
          <CSelect v-model="store.filterRole" :options="roleOptions" />
          <CSelect v-model="store.filterStatus" :options="statusOptions" />
          <div class="filters__right">
            <CButton variant="secondary" size="sm" v-perm.disable="'finance:export'">
              <CIcon name="export" :size="14" />导出
            </CButton>
            <CButton v-if="canEdit" variant="primary" size="sm" @click="doCalculate">
              <CIcon name="plus" :size="14" />生成结算单
            </CButton>
          </div>
        </div>
        <div class="list">
          <div v-if="store.filtered.length === 0" class="empty">
            <CIcon name="finance" :size="28" class="empty__icon" />
            <div>暂无分账数据</div>
          </div>
          <button
            v-for="s in store.filtered" :key="s.id"
            class="row" :class="{ 'row--active': selected?.id === s.id, 'row--fail': s.status === 'FAILED' }"
            @click="selectedId = s.id"
          >
            <div class="row__top">
              <span class="row__no">{{ s.batchNo }}</span>
              <CStatusPill :status="store.STATUS_PILL[s.status]" dot>{{ store.STATUS_LABEL[s.status] }}</CStatusPill>
            </div>
            <div class="row__main">{{ s.receiverName }}</div>
            <div class="row__sub">{{ s.date }} · {{ store.ROLE_LABEL[s.role] }} · {{ store.BIZ_LABEL[s.bizType] }}</div>
            <div class="row__bottom">
              <span class="row__amt">{{ money(s.amount) }}</span>
              <span class="row__pct">{{ pct(s.ratio) }}</span>
            </div>
          </button>
        </div>
      </CCard>

      <!-- 详情 -->
      <CCard v-if="selected" class="st__detail" padding="none">
        <template #header>
          <div class="st__detail-head">
            <div class="st__who">
              <h3 class="st__no">{{ selected.batchNo }}</h3>
              <div class="st__sub">{{ selected.receiverName }} · {{ store.ROLE_LABEL[selected.role] }}</div>
            </div>
            <CStatusPill :status="store.STATUS_PILL[selected.status]" dot>{{ store.STATUS_LABEL[selected.status] }}</CStatusPill>
          </div>
        </template>

        <div class="detail-body">
          <div class="stat-grid">
            <div class="stat">
              <div class="stat__label">分账金额</div>
              <div class="stat__value stat__value--brand">{{ money(selected.amount) }}</div>
            </div>
            <div class="stat">
              <div class="stat__label">业务金额</div>
              <div class="stat__value">{{ money(selected.orderAmount) }}</div>
            </div>
            <div class="stat">
              <div class="stat__label">分账比例</div>
              <div class="stat__value">{{ pct(selected.ratio) }}</div>
            </div>
            <div class="stat">
              <div class="stat__label">支付渠道</div>
              <div class="stat__value stat__value--sm">{{ selected.channel }}</div>
            </div>
          </div>

          <div class="kv">
            <div class="kv__row"><span class="kv__k">业务类型</span><span class="kv__v">{{ store.BIZ_LABEL[selected.bizType] }}</span></div>
            <div class="kv__row"><span class="kv__k">关联业务单</span><span class="kv__v">{{ selected.refNo }}</span></div>
            <div class="kv__row"><span class="kv__k">业务日期</span><span class="kv__v">{{ selected.date }}</span></div>
            <div class="kv__row"><span class="kv__k">Outbox 勾稽</span><span class="kv__v">{{ selected.outboxRef || '—' }}</span></div>
            <div v-if="selected.submittedAt" class="kv__row"><span class="kv__k">提交时间</span><span class="kv__v">{{ selected.submittedAt.slice(0, 16).replace('T', ' ') }}</span></div>
            <div v-if="selected.confirmedAt" class="kv__row"><span class="kv__k">回单时间</span><span class="kv__v kv__v--success">{{ selected.confirmedAt.slice(0, 16).replace('T', ' ') }}</span></div>
            <div v-if="selected.remark" class="kv__row"><span class="kv__k">备注</span><span class="kv__v" :class="{ 'kv__v--danger': selected.status === 'FAILED' }">{{ selected.remark }}</span></div>
          </div>

          <div class="ops">
            <CButton v-if="selected.status === 'CALCULATED'" variant="primary" size="sm" v-perm.disable="'finance:settlement:edit'" @click="doSubmitBatch">
              <CIcon name="upload" :size="14" />提交分账
            </CButton>
            <CButton v-if="selected.status === 'SUBMITTED'" variant="primary" size="sm" v-perm.disable="'finance:settlement:approve'" @click="openConfirm">
              <CIcon name="shield" :size="14" />回单确认（双签）
            </CButton>
            <CButton v-if="selected.status === 'CALCULATED' || selected.status === 'SUBMITTED'" variant="danger" size="sm" @click="openFail">
              <CIcon name="alert" :size="14" />标记失败
            </CButton>
            <span v-if="selected.status === 'CONFIRMED'" class="ops__hint ops__hint--done">
              <CIcon name="check" :size="14" /> 渠道已回单确认
            </span>
            <span v-if="selected.status === 'PENDING'" class="ops__hint">待计算分账金额，点击右上角「生成结算单」</span>
            <span v-if="selected.status === 'FAILED'" class="ops__hint ops__hint--danger">分账失败，需线下核对后重试</span>
          </div>
        </div>
      </CCard>

      <CCard v-else class="st__detail st__detail--empty" title="分账详情" padding="lg">
        <div class="detail-empty">
          <CIcon name="finance" :size="40" class="detail-empty__icon" />
          <p>请选择一条分账记录</p>
        </div>
      </CCard>
    </div>

    <!-- 回单确认双签弹层 -->
    <div v-if="showConfirm" class="modal-mask" @click.self="showConfirm = false">
      <CCard class="modal" title="分账回单确认（双签）" padding="lg">
        <div class="form">
          <div class="sign-box">
            <div class="sign-box__title"><CIcon name="shield" :size="16" /> 双签确认</div>
            <div class="sign-box__text">批次：{{ selected?.batchNo }}　|　{{ selected?.receiverName }}</div>
            <div class="sign-box__text">分账金额：<b>{{ selected ? money(selected.amount) : '' }}</b></div>
          </div>
          <div class="form__row">
            <label class="form__label">复核人 <span class="req">*</span></label>
            <CInput v-model="confirmForm.reviewer" placeholder="请输入复核人姓名，如：陈雅琳（财务主管）" />
          </div>
          <div class="form__row">
            <label class="form__label">回单说明（可选）</label>
            <CInput v-model="confirmForm.remark" placeholder="如：银行回单已核对一致" />
          </div>
          <p class="form__tip form__tip--warn">本操作仅登记渠道回单状态，不发起真实资金分账。</p>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showConfirm = false">取消</CButton>
          <CButton variant="primary" :disabled="!canConfirm" @click="submitConfirm">确认回单</CButton>
        </template>
      </CCard>
    </div>

    <!-- 失败登记弹层 -->
    <div v-if="showFail" class="modal-mask" @click.self="showFail = false">
      <CCard class="modal modal--sm" title="标记分账失败" padding="lg">
        <div class="form">
          <div class="form__row">
            <label class="form__label">失败原因 <span class="req">*</span></label>
            <CInput v-model="failReason" placeholder="如：接收方账户信息异常" />
          </div>
          <p class="form__tip">仅镜像登记失败状态，不重试动账；需线下核对后处理。</p>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showFail = false">取消</CButton>
          <CButton variant="danger" :disabled="!failReason.trim()" @click="submitFail">确认标记</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.st { display: flex; flex-direction: column; gap: var(--s-lg); }
.st__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .st__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }

.st__rule { width: 100%; }
.rule { display: flex; flex-direction: column; gap: var(--s-md); }
.rule__head { display: flex; align-items: center; justify-content: space-between; gap: var(--s-md); flex-wrap: wrap; }
.rule__title { display: flex; align-items: center; gap: var(--s-xs); font-size: var(--t-md); font-weight: 600; color: var(--c-text); }
.rule__base { font-size: var(--t-sm); color: var(--c-text-3); }
.rule__base-amt { font-weight: 700; color: var(--c-warning-fg); margin-left: var(--s-xs); font-variant-numeric: tabular-nums; }
.rule__fail { color: var(--c-danger-fg); margin-left: var(--s-xs); }
.rule__bar { display: flex; width: 100%; height: 14px; border-radius: 999px; overflow: hidden; background: var(--c-bg-right); }
.rule__seg { height: 100%; transition: width .3s ease; }
.rule__legend { display: flex; gap: var(--s-xl); flex-wrap: wrap; }
.rule__leg-item { display: flex; align-items: center; gap: var(--s-xs); font-size: var(--t-sm); color: var(--c-text-2); }
.rule__dot { width: 10px; height: 10px; border-radius: 3px; }
.rule__leg-label { color: var(--c-text-3); }
.rule__leg-amt { font-weight: 600; color: var(--c-text); font-variant-numeric: tabular-nums; }
.rule__note { font-size: var(--t-xs); color: var(--c-text-3); margin: 0; }

.st__body { display: grid; grid-template-columns: 400px 1fr; gap: var(--s-lg); align-items: start; }
.st__list { min-width: 0; }
.filters { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-md); border-bottom: 1px solid var(--c-border-light); flex-wrap: nowrap; overflow-x: auto; }
.filters__right { display: flex; align-items: center; gap: var(--s-sm); margin-left: auto; flex-shrink: 0; }
.list { max-height: 560px; overflow-y: auto; }
.empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); font-size: var(--t-sm); }
.empty__icon { color: var(--c-text-4); }

.row { display: block; width: 100%; text-align: left; padding: var(--s-md) var(--s-lg); background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer; }
.row:hover { background: var(--c-brand-soft); }
.row--active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.row--fail { border-left: none; }
.row__top { display: flex; align-items: center; justify-content: space-between; gap: var(--s-sm); margin-bottom: 4px; }
.row__no { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.row__main { font-size: var(--t-sm); color: var(--c-text-2); }
.row__sub { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }
.row__bottom { display: flex; align-items: center; justify-content: space-between; margin-top: var(--s-xs); }
.row__amt { font-size: var(--t-sm); font-weight: 700; color: var(--c-text); font-variant-numeric: tabular-nums; }
.row__pct { font-size: var(--t-xs); color: var(--c-text-3); }

.st__detail-head { display: flex; align-items: center; gap: var(--s-md); width: 100%; }
.st__who { flex: 1; min-width: 0; }
.st__no { font-size: var(--t-lg); font-weight: 700; margin: 0; }
.st__sub { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }

.detail-body { padding: var(--s-lg); display: flex; flex-direction: column; gap: var(--s-lg); }
.stat-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); }
.stat { background: var(--c-bg-right); border-radius: var(--r-md); padding: var(--s-md); }
.stat__label { font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: 4px; }
.stat__value { font-size: var(--t-md); font-weight: 700; color: var(--c-text); font-variant-numeric: tabular-nums; }
.stat__value--brand { color: var(--c-brand); }
.stat__value--sm { font-size: var(--t-sm); }

.kv { display: flex; flex-direction: column; gap: var(--s-sm); background: var(--c-bg-right); border-radius: var(--r-md); padding: var(--s-md); }
.kv__row { display: flex; justify-content: space-between; gap: var(--s-md); font-size: var(--t-sm); }
.kv__k { color: var(--c-text-3); flex-shrink: 0; }
.kv__v { color: var(--c-text-2); font-variant-numeric: tabular-nums; text-align: right; }
.kv__v--success { color: var(--c-success-fg); font-weight: 600; }
.kv__v--danger { color: var(--c-danger-fg); }

.ops { display: flex; align-items: center; gap: var(--s-sm); flex-wrap: wrap; }
.ops__hint { display: inline-flex; align-items: center; gap: var(--s-xs); font-size: var(--t-xs); color: var(--c-text-3); }
.ops__hint--done { color: var(--c-success-fg); font-weight: 600; }
.ops__hint--danger { color: var(--c-danger-fg); font-weight: 600; }

.detail-empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-md); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); }
.detail-empty__icon { color: var(--c-text-4); }

.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 460px; max-width: 100%; box-shadow: var(--shadow-pop); }
.modal--sm { width: 400px; }
.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.form__label { font-size: var(--t-xs); color: var(--c-text-3); }
.req { color: var(--c-danger-fg); }
.form__tip { font-size: var(--t-xs); color: var(--c-text-3); background: var(--c-bg-right); border-radius: var(--r-sm); padding: var(--s-sm); margin: 0; }
.form__tip--warn { background: var(--c-warning-bg); color: var(--c-warning-fg); }
.sign-box { background: var(--c-warning-bg); border: 1px solid var(--c-border-light); border-radius: var(--r-md); padding: var(--s-md); display: flex; flex-direction: column; gap: 4px; }
.sign-box__title { display: flex; align-items: center; gap: var(--s-xs); font-size: var(--t-sm); font-weight: 600; color: var(--c-warning-fg); }
.sign-box__text { font-size: var(--t-sm); color: var(--c-text-2); }

@media (max-width: 1024px) {
  .st__body { grid-template-columns: 1fr; }
  .stat-grid { grid-template-columns: repeat(2, 1fr); }
  .rule__legend { gap: var(--s-md); }
  .list { max-height: 360px; }
}
</style>
