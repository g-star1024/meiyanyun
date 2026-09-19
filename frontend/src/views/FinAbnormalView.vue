<script setup lang="ts">
/* ============================================================
 * M6 异常账务 /m6-abnormal（B63 卡1 L84 接真）
 * 4 KPI（异常笔数/长款金额/短款金额/待处置）
 * 左：异常账单清单（SHORT 短款/LONG 长款/WRONG 错账）
 * 右：账单详情＝登记信息 + 审批状态卡（PENDING_APPROVAL/APPROVED/REJECTED/DISPOSED）
 * 闭环：登记（POST /finance/abnormal/bills）→ txn 审批 FIN_ADJUSTMENT
 *       → 终审回调 APPROVED/REJECTED → APPROVED 单「处置入账」落 ADJUST 调整分录。
 * 红线：禁直接人工 MATERIAL/LOSS 分录；动账只走终审后 ADJUST（RF-REVENUE），
 *       SHORT→OUT 冲减、LONG→IN 补收、WRONG 取登记方向。
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import { useToast } from '@/composables/useToast'
import { useStoreContext } from '@/stores/storeContext'
import { useFinAbnormalStore, type AbnormalItem, type AbnormalType } from '@/stores/finReports'

const store = useFinAbnormalStore()
const ctx = useStoreContext()
const toast = useToast()
onMounted(() => { void ctx.loadStores(); void store.seed() })

const selectedNo = ref<string | null>(null)
const selected = computed<AbnormalItem | null>(() => {
  if (selectedNo.value) return store.get(selectedNo.value) ?? null
  return store.filtered[0] ?? null
})

const kpis = computed(() => [
  { label: '异常笔数', icon: 'alert', value: String(store.totalCount), tone: store.totalCount ? ('danger' as const) : ('text' as const) },
  { label: '长款金额', icon: 'alert', value: money(store.longAmount), tone: 'success' as const },
  { label: '短款金额', icon: 'alert', value: money(store.shortAmount), tone: 'danger' as const },
  { label: '待处置', icon: 'check-square', value: String(store.openCount), tone: store.openCount ? ('warning' as const) : ('text' as const) },
])

const typeOptions = [
  { value: 'ALL', label: '全部类型' },
  { value: 'SHORT', label: '短款' },
  { value: 'LONG', label: '长款' },
  { value: 'WRONG', label: '错账' },
]

function money(n: number | null | undefined) {
  const v = n == null ? 0 : n
  return `¥${v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}
/** 分 → 元（账单金额后端 Long 存分） */
function yuan(fen: number | null | undefined) {
  return (fen == null ? 0 : Math.round(fen) / 100).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
/** 列表/详情带符号金额：短款 −（红）、长款 +（绿）、错账按登记方向 */
function signed(it: AbnormalItem) {
  const sign = it.type === 'SHORT' ? '−' : it.type === 'LONG' ? '+' : (it.direction === 'IN' ? '+' : '−')
  return `${sign}¥${yuan(it.amountFen)}`
}
function storeName(code: string) {
  return ctx.stores.find((s) => s.storeCode === code)?.storeName ?? code
}
function dt(s?: string | null) {
  return s ? s.slice(0, 16).replace('T', ' ') : '—'
}

// ----- 登记异常账单 -----
const showCreate = ref(false)
const form = ref({ storeCode: '', type: 'SHORT' as AbnormalType, direction: 'IN' as 'IN' | 'OUT', amount: '', reason: '' })
const formError = ref('')
const storeOptions = computed(() => ctx.stores.map((s) => ({ label: s.storeName, value: s.storeCode })))
const typeFormOptions = [
  { value: 'SHORT', label: '短款（实收 < 账面）' },
  { value: 'LONG', label: '长款（实收 > 账面）' },
  { value: 'WRONG', label: '错账（科目/方向记错）' },
]
const directionOptions = [
  { value: 'IN', label: '补收（IN）' },
  { value: 'OUT', label: '冲减（OUT）' },
]
function openCreate() {
  form.value = { storeCode: ctx.currentStoreCode || ctx.stores[0]?.storeCode || '', type: 'SHORT', direction: 'IN', amount: '', reason: '' }
  formError.value = ''
  showCreate.value = true
}
async function submitCreate() {
  formError.value = ''
  const amountYuan = Number(form.value.amount)
  try {
    const data = await store.create({
      storeCode: form.value.storeCode,
      type: form.value.type,
      direction: form.value.type === 'WRONG' ? form.value.direction : undefined,
      amountYuan,
      reason: form.value.reason,
    })
    showCreate.value = false
    selectedNo.value = data.billNo
    toast.success(`异常账单 ${data.billNo} 已登记，审批单 ${data.approvalNo || '流转中'}`)
  } catch (e: any) {
    const msg = e?.response?.data?.message || e?.message || '登记失败，请稍后重试'
    formError.value = msg
    toast.error(msg)
  }
}

// ----- 处置入账（终审通过后，落 ADJUST 调整分录） -----
const showDispose = ref(false)
function openDispose() { showDispose.value = true }
async function submitDispose() {
  if (!selected.value) return
  const billNo = selected.value.billNo
  try {
    const data = await store.dispose(billNo)
    showDispose.value = false
    toast.success(`账单 ${billNo} 已处置入账（ADJUST 调整分录 #${data.disposeFundEntryId ?? '-'}）`)
  } catch (e: any) {
    const msg = e?.response?.data?.message || e?.message || '处置失败，请稍后重试'
    toast.error(msg)
  }
}
</script>

<template>
  <div class="ab">
    <div class="ab__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <div class="ab__body">
      <!-- 左：异常账单清单 -->
      <CCard class="ab__list" padding="none">
        <div class="filters">
          <CSelect v-model="store.filterType" width="120px" :options="typeOptions" />
          <CButton variant="primary" size="sm" v-perm.disable="'finance:abnormal:dispose'" @click="openCreate">
            <CIcon name="alert" :size="14" />登记异常
          </CButton>
        </div>
        <div class="list">
          <div v-if="store.loading" class="empty">
            <CIcon name="loading" :size="28" class="empty__icon" />
            <div>正在加载异常账单…</div>
          </div>
          <div v-else-if="store.filtered.length === 0" class="empty">
            <CIcon name="check-square" :size="28" class="empty__icon" />
            <div>暂无异常账单</div>
            <div class="empty__hint">长短款/错账在此登记，提交后进入审批中心（FIN_ADJUSTMENT），终审通过方可处置入账</div>
          </div>
          <button
            v-for="it in store.filtered" :key="it.billNo"
            class="row" :class="{ 'row--active': selected?.billNo === it.billNo, 'row--resolved': it.status === 'DISPOSED' }"
            @click="selectedNo = it.billNo"
          >
            <div class="row__top">
              <span class="row__no">{{ it.billNo }}</span>
              <CStatusPill :status="store.ABNORMAL_TYPE_PILL[it.type]">{{ store.ABNORMAL_TYPE_LABEL[it.type] }}</CStatusPill>
            </div>
            <div class="row__sub">{{ storeName(it.storeCode) }} · {{ dt(it.createdAt) }}</div>
            <div class="row__bottom">
              <span class="row__amt" :class="{ 'row__amt--long': it.type === 'LONG' || (it.type === 'WRONG' && it.direction === 'IN'), 'row__amt--short': it.type === 'SHORT' || (it.type === 'WRONG' && it.direction === 'OUT') }">
                {{ signed(it) }}
              </span>
              <CStatusPill :status="store.ABNORMAL_STATUS_PILL[it.status]">{{ store.ABNORMAL_STATUS_LABEL[it.status] }}</CStatusPill>
            </div>
          </button>
        </div>
      </CCard>

      <!-- 右：详情 -->
      <CCard v-if="selected" class="ab__detail" padding="none">
        <template #header>
          <div class="ab__detail-head">
            <div>
              <h3 class="ab__no">{{ selected.billNo }}</h3>
              <div class="ab__sub">{{ storeName(selected.storeCode) }} · 登记于 {{ dt(selected.createdAt) }} · {{ selected.createdBy || '—' }}</div>
            </div>
            <div class="ab__detail-ops">
              <CStatusPill :status="store.ABNORMAL_STATUS_PILL[selected.status]" dot>{{ store.ABNORMAL_STATUS_LABEL[selected.status] }}</CStatusPill>
              <CButton variant="secondary" size="sm" :disabled="store.loading" @click="void store.refresh()">
                <CIcon name="loading" :size="14" />同步
              </CButton>
            </div>
          </div>
        </template>

        <div class="detail-body">
          <!-- 登记信息 -->
          <div class="block">
            <div class="block__title"><span>登记信息</span></div>
            <div class="tri">
              <div class="tri__col">
                <div class="tri__label">异常类型</div>
                <div class="tri__value">{{ store.ABNORMAL_TYPE_LABEL[selected.type] }}</div>
                <div class="tri__tag tri__tag--warn">{{ selected.type === 'WRONG' ? store.ABNORMAL_DIRECTION_LABEL[selected.direction ?? 'IN'] : (selected.type === 'LONG' ? '处置补收 IN' : '处置冲减 OUT') }}</div>
              </div>
              <div class="tri__op">
                <CIcon name="chevron-right" :size="16" />
              </div>
              <div class="tri__col">
                <div class="tri__label">涉及金额</div>
                <div class="tri__value">{{ signed(selected) }}</div>
                <div class="tri__tag" :class="selected.type === 'LONG' ? 'tri__tag--ok' : 'tri__tag--danger'">{{ selected.source === 'RECONCILE' ? '对账转入' : '人工登记' }}</div>
              </div>
              <div class="tri__op">
                <CIcon name="chevron-right" :size="16" />
              </div>
              <div class="tri__col">
                <div class="tri__label">审批单号</div>
                <div class="tri__value" :class="{ 'tri__value--miss': !selected.approvalNo }">{{ selected.approvalNo || '流转中' }}</div>
                <div class="tri__tag" :class="selected.status === 'DISPOSED' ? 'tri__tag--ok' : selected.status === 'REJECTED' ? 'tri__tag--danger' : 'tri__tag--warn'">{{ store.ABNORMAL_STATUS_LABEL[selected.status] }}</div>
              </div>
            </div>
            <div class="diff-bar">
              <span>异常事由</span>
              <b :class="selected.type === 'LONG' ? 'is-long' : 'is-short'">{{ selected.reason || '—' }}</b>
            </div>
          </div>

          <!-- 审批/处置状态 -->
          <div v-if="selected.status === 'DISPOSED'" class="resolved">
            <div class="resolved__head">
              <CIcon name="check-square" :size="16" />
              <span>已于 {{ dt(selected.disposedAt) }} 处置入账</span>
            </div>
            <div class="resolved__row"><span>终审人</span><b>{{ selected.reviewer || '—' }}</b></div>
            <div class="resolved__row"><span>终审时间</span><b>{{ dt(selected.approvedAt) }}</b></div>
            <div class="resolved__row"><span>ADJUST 调整分录</span><b>#{{ selected.disposeFundEntryId ?? '-' }}（RF-REVENUE / {{ selected.type === 'LONG' || (selected.type === 'WRONG' && selected.direction === 'IN') ? 'IN 补收' : 'OUT 冲减' }}）</b></div>
          </div>

          <div v-else-if="selected.status === 'APPROVED'" class="resolved">
            <div class="resolved__head">
              <CIcon name="shield" :size="16" />
              <span>终审通过（{{ dt(selected.approvedAt) }}），待处置入账</span>
            </div>
            <div class="resolved__row"><span>终审人</span><b>{{ selected.reviewer || '—' }}</b></div>
            <div class="resolved__row"><span>审批单号</span><b>{{ selected.approvalNo || '—' }}</b></div>
          </div>

          <div v-else-if="selected.status === 'REJECTED'" class="resolved">
            <div class="resolved__head">
              <CIcon name="alert" :size="16" />
              <span>审批已驳回，不可处置</span>
            </div>
            <div class="resolved__row"><span>驳回时间</span><b>{{ dt(selected.approvedAt) }}</b></div>
            <div class="resolved__row"><span>审批单号</span><b>{{ selected.approvalNo || '—' }}</b></div>
          </div>

          <!-- 操作 -->
          <div v-if="selected.status === 'APPROVED'" class="ops">
            <CButton variant="primary" size="sm" v-perm.disable="'finance:abnormal:dispose'" @click="openDispose">
              <CIcon name="check-square" :size="14" />处置入账（ADJUST 调整分录）
            </CButton>
            <span class="ops__hint">终审已通过：{{ selected.type === 'LONG' || (selected.type === 'WRONG' && selected.direction === 'IN') ? '补收 IN' : '冲减 OUT' }} ¥{{ yuan(selected.amountFen) }}，幂等可重放</span>
          </div>
          <div v-else-if="selected.status === 'PENDING_APPROVAL'" class="ops">
            <CButton variant="secondary" size="sm" disabled>
              <CIcon name="loading" :size="14" />审批中心流转中…
            </CButton>
            <span class="ops__hint">账单已提交审批（FIN_ADJUSTMENT），终审通过后本页出现「处置入账」按钮</span>
          </div>

          <p class="redline">
            <CIcon name="shield" :size="14" />
            红线：长短款/错账禁止直接人工 MATERIAL/LOSS 分录；动账只走终审通过后的 ADJUST 调整（科目 RF-REVENUE），短款冲减、长款补收、错账按登记方向。
          </p>
        </div>
      </CCard>

      <CCard v-else class="ab__detail ab__detail--empty" title="异常账单详情" padding="lg">
        <div class="detail-empty">
          <CIcon name="check-square" :size="40" class="detail-empty__icon" />
          <p>当前无异常账单</p>
          <p class="detail-empty__hint">点击左上角「登记异常」登记长短款/错账，账单进入审批中心（FIN_ADJUSTMENT）；终审通过后处置入账，全程留痕可审计。</p>
        </div>
      </CCard>
    </div>

    <!-- 登记异常账单弹层 -->
    <div v-if="showCreate" class="modal-mask" @click.self="showCreate = false">
      <CCard class="modal" title="登记异常账单（提交审批）" padding="lg">
        <div class="form">
          <div class="sign-box">
            <div class="sign-box__title"><CIcon name="shield" :size="16" /> 登记即提交审批</div>
            <div class="sign-box__text">短款＝实收少于账面（处置冲减 OUT）；长款＝实收多于账面（处置补收 IN）；错账须选择调整方向。</div>
          </div>
          <label class="form__label">登记门店 <span class="req">*</span></label>
          <CSelect v-model="form.storeCode" width="100%" :options="storeOptions" placeholder="请选择门店" />
          <label class="form__label">异常类型 <span class="req">*</span></label>
          <CSelect v-model="form.type" width="100%" :options="typeFormOptions" />
          <template v-if="form.type === 'WRONG'">
            <label class="form__label">调整方向 <span class="req">*</span></label>
            <CSelect v-model="form.direction" width="100%" :options="directionOptions" />
          </template>
          <label class="form__label">涉及金额（元） <span class="req">*</span></label>
          <CInput v-model="form.amount" type="number" placeholder="差额绝对值，如 86.50" />
          <label class="form__label">异常事由 <span class="req">*</span></label>
          <CInput v-model="form.reason" placeholder="说明发生场景与初步原因，如：夜班交班现金短款 86.5 元" />
          <p v-if="formError" class="form__tip form__tip--warn">{{ formError }}</p>
          <p class="form__tip">提交后账单进入审批中心（FIN_ADJUSTMENT，按金额 ¥1000/5000/20000 走 L1/L2/L3），终审通过前不动任何账务。</p>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showCreate = false">取消</CButton>
          <CButton variant="primary" :disabled="store.submitting" v-perm.disable="'finance:abnormal:dispose'" @click="submitCreate">提交登记</CButton>
        </template>
      </CCard>
    </div>

    <!-- 处置入账确认弹层 -->
    <div v-if="showDispose" class="modal-mask" @click.self="showDispose = false">
      <CCard class="modal" title="处置入账确认（终审后 ADJUST）" padding="lg">
        <div class="form">
          <div class="sign-box">
            <div class="sign-box__title"><CIcon name="shield" :size="16" /> 动账双确认</div>
            <div class="sign-box__text">异常账单：{{ selected?.billNo }}（{{ selected ? store.ABNORMAL_TYPE_LABEL[selected.type] : '' }}）</div>
            <div class="sign-box__text">
              调整方向/金额：<b>{{ selected ? (selected.type === 'LONG' || (selected.type === 'WRONG' && selected.direction === 'IN') ? '补收 IN' : '冲减 OUT') : '' }} ¥{{ selected ? yuan(selected.amountFen) : '0.00' }}</b>
            </div>
          </div>
          <p class="form__tip form__tip--warn">处置将经内部资金接口写一条 ADJUST 调整分录（科目 RF-REVENUE，来源 MANUAL，幂等键 ABNORMAL:{{ selected?.billNo }}），账单置「已处置入账」。此动作不可重复动账。</p>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showDispose = false">取消</CButton>
          <CButton variant="primary" :disabled="store.disposing" v-perm.disable="'finance:abnormal:dispose'" @click="submitDispose">确认处置入账</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.ab { display: flex; flex-direction: column; gap: var(--s-lg); }
.ab__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .ab__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }

.ab__body { display: grid; grid-template-columns: 380px 1fr; gap: var(--s-lg); align-items: start; }
.ab__list { min-width: 0; }
.filters { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-md); border-bottom: 1px solid var(--c-border-light); flex-wrap: nowrap; overflow-x: auto; }
.filters__right { display: flex; align-items: center; gap: var(--s-sm); margin-left: auto; flex-shrink: 0; }
.list { max-height: 640px; overflow-y: auto; }
.empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); font-size: var(--t-sm); }
.empty__icon { color: var(--c-success-fg); }
.empty__hint { font-size: var(--t-xs); color: var(--c-text-4); text-align: center; line-height: 1.7; }

.row { display: block; width: 100%; text-align: left; padding: var(--s-md) var(--s-lg); background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer; }
.row:hover { background: var(--c-brand-soft); }
.row--active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.row--resolved { opacity: 0.65; }
.row__top { display: flex; align-items: center; justify-content: space-between; gap: var(--s-sm); margin-bottom: 4px; }
.row__no { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); font-variant-numeric: tabular-nums; }
.row__sub { font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: 6px; }
.row__bottom { display: flex; align-items: center; justify-content: space-between; }
.row__amt { font-size: var(--t-md); font-weight: 700; font-variant-numeric: tabular-nums; }
.row__amt--long { color: var(--c-success-fg); }
.row__amt--short { color: var(--c-danger-fg); }

.ab__detail-head { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--s-md); width: 100%; }
.ab__detail-ops { display: flex; align-items: center; gap: var(--s-sm); flex-shrink: 0; flex-wrap: wrap; justify-content: flex-end; }
.ab__no { font-size: var(--t-lg); font-weight: 700; margin: 0; }
.ab__sub { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }

.detail-body { padding: var(--s-lg); display: flex; flex-direction: column; gap: var(--s-lg); }
.block { display: flex; flex-direction: column; gap: var(--s-sm); }
.block__title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }

.tri { display: grid; grid-template-columns: 1fr auto 1fr auto 1fr; align-items: center; gap: var(--s-sm); }
.tri__col { background: var(--c-bg-right); border-radius: var(--r-md); padding: var(--s-md); text-align: center; display: flex; flex-direction: column; gap: 4px; align-items: center; }
.tri__label { font-size: var(--t-xs); color: var(--c-text-3); }
.tri__value { font-size: var(--t-md); font-weight: 700; color: var(--c-text); font-variant-numeric: tabular-nums; }
.tri__value--diff { color: var(--c-danger-fg); }
.tri__value--miss { color: var(--c-text-4); font-size: var(--t-sm); font-weight: 600; }
.tri__tag { font-size: var(--t-xs); padding: 2px 8px; border-radius: var(--r-sm); }
.tri__tag--ok { background: var(--c-success-bg); color: var(--c-success-fg); }
.tri__tag--warn { background: var(--c-warning-bg); color: var(--c-warning-fg); }
.tri__tag--danger { background: var(--c-danger-bg); color: var(--c-danger-fg); }
.tri__op { color: var(--c-text-4); display: flex; }
.diff-bar { display: flex; align-items: center; justify-content: space-between; gap: var(--s-md); padding: var(--s-sm) var(--s-md); background: var(--c-warn-soft-bg); border-radius: var(--r-sm); margin-top: var(--s-xs); }
.diff-bar span { font-size: var(--t-xs); color: var(--c-text-3); flex-shrink: 0; }
.diff-bar b { font-size: var(--t-sm); font-weight: 500; color: var(--c-text-2); text-align: right; }
.diff-bar b.is-long { color: var(--c-success-fg); }
.diff-bar b.is-short { color: var(--c-danger-fg); }

.resolved { background: var(--c-success-bg); border-radius: var(--r-md); padding: var(--s-md); display: flex; flex-direction: column; gap: var(--s-xs); }
.resolved__head { display: flex; align-items: center; gap: 6px; font-size: var(--t-sm); font-weight: 600; color: var(--c-success-fg); margin-bottom: var(--s-xs); }
.resolved__row { display: flex; justify-content: space-between; gap: var(--s-md); font-size: var(--t-sm); }
.resolved__row span { color: var(--c-text-3); flex-shrink: 0; }
.resolved__row b { color: var(--c-text-2); font-weight: 500; text-align: right; }

.ops { display: flex; align-items: center; gap: var(--s-sm); flex-wrap: wrap; }
.ops__hint { font-size: var(--t-xs); color: var(--c-text-3); }

.redline { display: flex; align-items: center; gap: 6px; font-size: var(--t-xs); color: var(--c-warning-fg); background: var(--c-warn-soft-bg); padding: var(--s-xs) var(--s-sm); border-radius: var(--r-sm); margin: 0; }
.detail-empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-md); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); }
.detail-empty__icon { color: var(--c-text-4); }
.detail-empty p { margin: 0; font-size: var(--t-sm); }
.detail-empty__hint { font-size: var(--t-xs) !important; color: var(--c-text-4); line-height: 1.8; text-align: center; max-width: 420px; }

.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 460px; max-width: 100%; box-shadow: var(--shadow-pop); }
.form { display: flex; flex-direction: column; gap: var(--s-sm); }
.form__label { font-size: var(--t-xs); color: var(--c-text-3); }
.req { color: var(--c-danger-fg); }
.form__tip { font-size: var(--t-xs); color: var(--c-text-3); background: var(--c-bg-right); border-radius: var(--r-sm); padding: var(--s-sm); margin: 0; }
.form__tip--warn { background: var(--c-warning-bg); color: var(--c-warning-fg); }
.sign-box { background: var(--c-warning-bg); border: 1px solid var(--c-border-light); border-radius: var(--r-md); padding: var(--s-md); display: flex; flex-direction: column; gap: 4px; }
.sign-box__title { display: flex; align-items: center; gap: var(--s-xs); font-size: var(--t-sm); font-weight: 600; color: var(--c-warning-fg); }
.sign-box__text { font-size: var(--t-sm); color: var(--c-text-2); }

@media (max-width: 1024px) {
  .ab__body { grid-template-columns: 1fr; }
  .tri { grid-template-columns: 1fr; }
  .tri__op { transform: rotate(90deg); justify-self: center; }
  .list { max-height: 320px; }
}
</style>
