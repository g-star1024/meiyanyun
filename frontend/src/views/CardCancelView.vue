<script setup lang="ts">
/* ============================================================
 * 退卡管理 /card-cancel（Desktop 优先 · 平板堆叠）
 * 退卡记录走 txn-service 真实 API（/txn/card-cancel 列表 +
 *   /txn/{no}/approve|reject|confirm 审批动作）；金额后端存「分」，适配层转「元」。
 * 客户/卡资产仍取 asset mock store（M4 无资产后端源），
 * 退卡应退金额按 settings.system.dualSign.cardClawbackRate 倒扣计算。
 * 权限：cardcancel:view / create / approve / sign。
 * 样式/模板沿用原版，仅替换退卡单数据源（mock refund store → 真实 API）。
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CTextarea from '@/components/CTextarea.vue'
import CSelect from '@/components/CSelect.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CFab from '@/components/CFab.vue'
import { useAuthStore } from '@/stores/auth'
import type { Refund } from '@/stores/refund'
import { useAssetStore } from '@/stores/asset'
import { useCustomerStore } from '@/stores/customer'
import { useSettingsStore } from '@/stores/settings'
import { useToast } from '@/composables/useToast'
import {
  listCardCancels, createCardCancel,
  approveTxn, rejectTxn, confirmTxn,
  type CardCancelDTO,
} from '@/api/refund'
import { REFUND_STATUS, REFUND_CHANNEL, dictPill, type RefundChannel } from '@/config/dictionary'

const auth = useAuthStore()
const asset = useAssetStore()
const customer = useCustomerStore()
const settings = useSettingsStore()
const toast = useToast()

// ---- 适配层：后端退卡实体（分）→ 模板原有 Refund 形状（元） ----
const fen2yuan = (f: number | null | undefined) => (f == null ? 0 : f / 100)
type Channel = 'ORIGINAL' | 'CASH' | 'TRANSFER'

function adaptCard(d: CardCancelDTO): Refund {
  return {
    id: d.txnNo,
    refundNo: d.txnNo,
    kind: 'CARD',
    customerId: d.customer,
    customerName: d.customerName || d.customer,
    project: d.cardItem,
    paidAmount: fen2yuan(d.balance),
    refundAmount: fen2yuan(d.refundAmt),
    channel: (d.channel as Channel) || 'ORIGINAL',
    reason: d.feeOverrideReason || '客户申请退卡',
    applicantName: d.applicant || '—',
    signTier: (d.signTier as Refund['signTier']) || 'L1',
    status: d.status as Refund['status'],
    createdAt: d.createdAt || '',
    assetId: d.cardNo,
    penaltyAmount: fen2yuan(d.fee),
    reviewedByName: d.reviewedBy || undefined,
    reviewedAt: d.reviewedAt || undefined,
    financeByName: d.financeBy || undefined,
    refundedAt: d.refundedAt || undefined,
    rejectionReason: d.rejectionReason || undefined,
    rejectionByName: d.rejectedBy || undefined,
  }
}

const refunds = ref<Refund[]>([])

async function load() {
  try {
    const res = await listCardCancels()
    refunds.value = res.data.map(adaptCard)
  } catch (e: any) {
    toast.error('退卡数据加载失败：' + (e?.response?.data?.message || e?.message || '网络异常'))
  }
}

onMounted(() => {
  asset.seed()
  load()
})

const actor = () => auth.user.staffId || 'cashier'

// ---- 模板所需的 store 形状适配（保持原版模板零改动）----
function get(id: string) {
  return refunds.value.find((r) => r.id === id)
}

async function create(input: {
  customerId: string
  customerName: string
  project: string
  paidAmount: number
  refundAmount: number
  channel: RefundChannel
  reason: string
  assetId?: string
  penaltyAmount?: number
  remainTimes?: number
}): Promise<Refund | null> {
  try {
    const balance = Math.round(input.paidAmount * 100)
    const refundCents = Math.round(input.refundAmount * 100)
    const feeCents = input.penaltyAmount != null
      ? Math.round(input.penaltyAmount * 100)
      : Math.max(0, balance - refundCents)
    const res = await createCardCancel({
      actor: actor(),
      cardNo: input.assetId || `CARD-${Date.now()}`,
      customer: input.customerId,
      customerName: input.customerName,
      cardItem: input.project,
      channel: input.channel,
      balance,
      remainTimes: input.remainTimes,
      feeManualOverride: true,
      feeCents,
      feeOverrideReason: input.reason || '前台退卡手动核算违约金',
    })
    await load()
    return adaptCard(res.data)
  } catch (e: any) {
    toast.error('提交失败：' + (e?.response?.data?.message || e?.message || '网络异常'))
    return null
  }
}

async function approve(id: string) {
  try {
    await approveTxn(id, actor())
    await load()
  } catch (e: any) {
    toast.error('审批失败：' + (e?.response?.data?.message || e?.message || '网络异常'))
  }
}
async function reject(id: string, reason: string) {
  try {
    await rejectTxn(id, actor(), reason)
    await load()
  } catch (e: any) {
    toast.error('驳回失败：' + (e?.response?.data?.message || e?.message || '网络异常'))
  }
}
async function confirmRefund(id: string) {
  try {
    await confirmTxn(id, actor())
    await load()
  } catch (e: any) {
    toast.error('确认退款失败：' + (e?.response?.data?.message || e?.message || '网络异常'))
  }
}

const refund = { get, create, approve, reject, confirmRefund }

type Tab = 'pending_review' | 'pending_finance' | 'refunded' | 'rejected'
const tab = ref<Tab>('pending_review')
const selectedId = ref<string | null>(null)

const cardRefunds = computed(() => refunds.value)
const pendingReview = computed(() => cardRefunds.value.filter((r) => r.status === 'PENDING_REVIEW'))
const pendingFinance = computed(() => cardRefunds.value.filter((r) => r.status === 'PENDING_FINANCE'))
const refundedList = computed(() => cardRefunds.value.filter((r) => r.status === 'REFUNDED'))
const rejectedList = computed(() => cardRefunds.value.filter((r) => r.status === 'REJECTED'))

const tabs = computed(() => [
  { k: 'pending_review' as Tab, label: `待审核 (${pendingReview.value.length})` },
  { k: 'pending_finance' as Tab, label: `待财务复核 (${pendingFinance.value.length})` },
  { k: 'refunded' as Tab, label: `已退卡 (${refundedList.value.length})` },
  { k: 'rejected' as Tab, label: `已驳回 (${rejectedList.value.length})` },
])

const list = computed<Refund[]>(() => {
  if (tab.value === 'pending_review') return pendingReview.value
  if (tab.value === 'pending_finance') return pendingFinance.value
  if (tab.value === 'refunded') return refundedList.value
  return rejectedList.value
})

const selected = computed(() => {
  if (selectedId.value) return refund.get(selectedId.value) ?? null
  return list.value[0] ?? null
})

function selectTab(t: Tab) {
  tab.value = t
  selectedId.value = null
}

const kpis = computed(() => [
  { label: '待审核退卡', icon: 'refund', value: String(pendingReview.value.length), tone: 'warning' as const },
  { label: '待财务复核', icon: 'check-square', value: String(pendingFinance.value.length), tone: 'warning' as const },
  { label: '本月已退卡', icon: 'refund', value: String(refundedList.value.length), tone: 'success' as const },
  { label: '退卡金额合计', icon: 'refund', value: '¥' + refundedList.value.reduce((s, r) => s + r.refundAmount, 0).toLocaleString('zh-CN'), tone: 'danger' as const },
])

function fmtMoney(n: number) { return '¥' + n.toLocaleString('zh-CN') }
function fmtTime(iso?: string) {
  if (!iso) return '—'
  const d = new Date(iso)
  return `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

// 审批
const rejectReason = ref('')
const showReject = ref(false)
function doApprove() { if (selected.value) refund.approve(selected.value.id) }
function doReject() {
  if (selected.value && rejectReason.value.trim()) {
    refund.reject(selected.value.id, rejectReason.value.trim())
    showReject.value = false
    rejectReason.value = ''
  }
}
function doConfirm() { if (selected.value) refund.confirmRefund(selected.value.id) }

// 发起退卡表单：选客户 → 选资产 → 自动算应退
const showForm = ref(false)
const formCustomerId = ref('')
const formAssetId = ref('')
const formChannel = ref<RefundChannel>('ORIGINAL')
const formReason = ref('')
const clawbackRate = computed(() => settings.system.dualSign.cardClawbackRate)

const customerAssets = computed(() => {
  if (!formCustomerId.value) return []
  const acct = asset.account(formCustomerId.value)
  return [
    ...acct.cashAssets.map((a) => ({ id: a.id, type: 'CASH' as const, name: '储值账户', total: a.balance + a.giftBalance, used: 0, remaining: a.balance + a.giftBalance })),
    ...acct.timesAssets.map((a) => ({ id: a.id, type: 'TIMES' as const, name: a.itemName, total: a.totalTimes, used: a.totalTimes - a.remainingTimes, remaining: a.remainingTimes })),
  ]
})
const selectedAsset = computed(() => customerAssets.value.find((a) => a.id === formAssetId.value))

// 退卡应退估算（演示期：次数型按 剩余次数/总次数 * 卡面值 - 已用次数倒扣；这里简化为剩余价值）
// 实际倒扣：已用次数按原价 * clawbackRate 倒扣
const formUnitPrice = ref('')
const refundCalc = computed(() => {
  if (!selectedAsset.value) return { paidAmount: 0, clawback: 0, refundAmount: 0 }
  const a = selectedAsset.value
  if (a.type === 'CASH') {
    return { paidAmount: a.remaining, clawback: 0, refundAmount: a.remaining }
  }
  const unit = Number(formUnitPrice.value) || 0
  const paidAmount = unit * a.total // 卡面总额
  const usedValue = unit * a.used // 已用价值
  const clawback = Math.round(usedValue * clawbackRate.value) // 倒扣（已用按比例）
  const refundAmount = Math.max(0, paidAmount - clawback)
  return { paidAmount, clawback, refundAmount }
})
const formTier = computed(() => settings.tierFor(refundCalc.value.refundAmount))
const canSubmit = computed(
  () =>
    formCustomerId.value &&
    formAssetId.value &&
    formReason.value.trim() &&
    (selectedAsset.value?.type === 'CASH' || Number(formUnitPrice.value) > 0) &&
    refundCalc.value.refundAmount > 0,
)

function openForm() {
  showForm.value = true
  formCustomerId.value = ''
  formAssetId.value = ''
  formChannel.value = 'ORIGINAL'
  formReason.value = ''
  formUnitPrice.value = ''
}

async function submitForm() {
  if (!canSubmit.value || !selectedAsset.value) return
  const c = customer.get(formCustomerId.value)
  const a = selectedAsset.value
  const calc = refundCalc.value
  const r = await refund.create({
    customerId: formCustomerId.value,
    customerName: c?.name || formCustomerId.value,
    project: a.type === 'CASH' ? `储值账户退卡（余额 ${fmtMoney(a.remaining)}）` : `${a.name}（剩余 ${a.remaining}/${a.total} 次）`,
    paidAmount: calc.paidAmount,
    refundAmount: calc.refundAmount,
    channel: formChannel.value,
    reason: formReason.value.trim(),
    assetId: a.id,
    penaltyAmount: calc.clawback,
    remainTimes: a.type === 'TIMES' ? a.remaining : undefined,
  })
  if (r) {
    showForm.value = false
    selectedId.value = r.id
    tab.value = r.status === 'PENDING_REVIEW' ? 'pending_review' : 'pending_finance'
  }
}
</script>

<template>
  <div class="ccx">
    <div class="ccx__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <div class="ccx__body">
      <!-- 左列 -->
      <CCard class="ccx__list" padding="none">
        <div class="tabs">
          <button
            v-for="t in tabs" :key="t.k"
            class="tab" :class="{ 'tab--active': tab === t.k }"
            @click="selectTab(t.k)"
          >{{ t.label }}</button>
        </div>
        <div class="list">
          <div v-if="list.length === 0" class="empty">
            <CIcon name="card" :size="28" class="empty__icon" />
            <div>暂无退卡记录</div>
          </div>
          <button
            v-for="r in list" :key="r.id"
            class="rec" :class="{ 'rec--active': selected?.id === r.id }"
            @click="selectedId = r.id"
          >
            <div class="rec__top">
              <span class="rec__no">{{ r.refundNo }}</span>
              <CStatusPill :status="dictPill(REFUND_STATUS[r.status]).status">{{ dictPill(REFUND_STATUS[r.status]).text }}</CStatusPill>
            </div>
            <div class="rec__cust">{{ r.customerName }}</div>
            <div class="rec__proj">{{ r.project }}</div>
            <div class="rec__meta">
              <span class="rec__amt">{{ fmtMoney(r.refundAmount) }}</span>
              <span>{{ r.signTier }} · {{ fmtTime(r.createdAt) }}</span>
            </div>
          </button>
          <CFab
            :actions="[{ icon: 'plus', label: '发起退卡', disabled: !auth.can('cardcancel:create'), onClick: openForm }]"
          />
        </div>
      </CCard>

      <!-- 右列详情 -->
      <CCard v-if="selected" class="ccx__detail" :title="selected.refundNo">
        <template #header>
          <h3 class="ccx__detail-title">{{ selected.refundNo }}</h3>
          <CStatusPill :status="dictPill(REFUND_STATUS[selected.status]).status">{{ dictPill(REFUND_STATUS[selected.status]).text }}</CStatusPill>
        </template>

        <div class="cust">
          <div class="cust__name">{{ selected.customerName }}</div>
          <div class="cust__sub">退卡 · 申请人 {{ selected.applicantName }}</div>
        </div>

        <div class="grid">
          <div class="field"><span class="field__label">卡项/资产</span><span class="field__val">{{ selected.project }}</span></div>
          <div class="field"><span class="field__label">退款渠道</span><span class="field__val">{{ REFUND_CHANNEL[selected.channel]?.label }}</span></div>
          <div class="field"><span class="field__label">卡面金额</span><span class="field__val">{{ fmtMoney(selected.paidAmount) }}</span></div>
          <div class="field"><span class="field__label">违约金（倒扣）</span><span class="field__val field__val--penalty">{{ selected.penaltyAmount ? fmtMoney(selected.penaltyAmount) : '—' }}</span></div>
          <div class="field field--full"><span class="field__label">实退金额</span><span class="field__val field__val--amt">{{ fmtMoney(selected.refundAmount) }}</span></div>
          <div class="field field--full"><span class="field__label">退卡原因</span><span class="field__val">{{ selected.reason }}</span></div>
          <div class="field"><span class="field__label">签署层级</span><span class="field__val">{{ selected.signTier }}（阈值来自设置中心）</span></div>
          <div class="field"><span class="field__label">发起时间</span><span class="field__val">{{ fmtTime(selected.createdAt) }}</span></div>
        </div>

        <div class="tip">
          <CIcon name="alert" :size="14" />
          退卡倒扣比例 {{ (clawbackRate * 100).toFixed(0) }}%（已用疗程按原价倒扣），参数可在设置中心调整。
        </div>

        <!-- 审批轨迹 -->
        <div class="trace">
          <div class="trace__step trace__step--done">
            <CIcon name="check" :size="14" /><span>发起申请 · {{ selected.applicantName }}</span>
          </div>
          <div class="trace__step" :class="{ 'trace__step--done': !!selected.reviewedByName, 'trace__step--active': selected.status === 'PENDING_REVIEW' }">
            <CIcon :name="selected.reviewedByName ? 'check' : 'sign'" :size="14" />
            <span>店长审批{{ selected.reviewedByName ? ' · ' + selected.reviewedByName : '' }}</span>
          </div>
          <div class="trace__step" :class="{ 'trace__step--done': !!selected.refundedAt, 'trace__step--active': selected.status === 'PENDING_FINANCE' }">
            <CIcon :name="selected.refundedAt ? 'check' : 'finance'" :size="14" />
            <span>财务退款{{ selected.financeByName ? ' · ' + selected.financeByName : '' }}</span>
          </div>
        </div>

        <div v-if="selected.rejectionReason" class="reject-note">
          <strong>驳回原因（{{ selected.rejectionByName }}）：</strong>{{ selected.rejectionReason }}
        </div>

        <div class="ops">
          <template v-if="selected.status === 'PENDING_REVIEW'">
            <CButton variant="ghost" v-perm.disable="'cardcancel:approve'" @click="showReject = true">驳回</CButton>
            <CButton variant="primary" v-perm.disable="'cardcancel:approve'" @click="doApprove">
              <CIcon name="check" :size="16" />审批通过
            </CButton>
          </template>
          <template v-else-if="selected.status === 'PENDING_FINANCE'">
            <CButton variant="ghost" v-perm.disable="'cardcancel:approve'" @click="showReject = true">驳回</CButton>
            <CButton variant="primary" v-perm.disable="'cardcancel:sign'" @click="doConfirm">
              <CIcon name="refund" :size="16" />确认退款 {{ fmtMoney(selected.refundAmount) }}
            </CButton>
          </template>
          <div v-else-if="selected.status === 'REFUNDED'" class="ops__done">
            <CIcon name="check-square" :size="16" />退卡已于 {{ fmtTime(selected.refundedAt) }} 完成
          </div>
        </div>

        <div v-if="showReject" class="reject-box">
          <CInput v-model="rejectReason" placeholder="请输入驳回原因（必填）" />
          <div class="reject-box__btns">
            <CButton variant="ghost" @click="showReject = false; rejectReason = ''">取消</CButton>
            <CButton variant="primary" :disabled="!rejectReason.trim()" @click="doReject">确认驳回</CButton>
          </div>
        </div>
      </CCard>

      <CCard v-else class="ccx__detail ccx__detail--empty" title="退卡详情">
        <div class="detail-empty">
          <CIcon name="card" :size="40" class="detail-empty__icon" />
          <p>请从左侧选择一笔退卡单</p>
        </div>
      </CCard>
    </div>

    <!-- 发起退卡弹层 -->
    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <CCard class="modal" title="发起退卡" padding="lg">
        <div class="form">
          <div class="form__row">
            <label class="form__label">客户</label>
            <CSelect
              v-model="formCustomerId"
              :options="asset.customersWithAssets.map(c => ({ value: c!.id, label: `${c!.name}（${c!.phoneMask}）` }))"
              placeholder="选择持卡客户"
              @update:model-value="formAssetId = ''"
            />
          </div>
          <div v-if="formCustomerId" class="form__row">
            <label class="form__label">选择退卡资产</label>
            <div class="asset-opts">
              <button
                v-for="a in customerAssets" :key="a.id"
                class="asset-opt" :class="{ 'asset-opt--on': formAssetId === a.id }"
                @click="formAssetId = a.id"
              >
                <span class="asset-opt__name">{{ a.name }}</span>
                <span class="asset-opt__sub">
                  {{ a.type === 'CASH' ? `余额 ${fmtMoney(a.remaining)}` : `剩余 ${a.remaining}/${a.total} 次` }}
                </span>
              </button>
            </div>
          </div>
          <div v-if="selectedAsset?.type === 'TIMES'" class="form__row">
            <label class="form__label">单次原价（元）</label>
            <CInput v-model="formUnitPrice" type="number" placeholder="用于计算卡面金额与倒扣" />
          </div>
          <div v-if="selectedAsset" class="calc">
            <div class="calc__row"><span>卡面金额</span><strong>{{ fmtMoney(refundCalc.paidAmount) }}</strong></div>
            <div v-if="selectedAsset.type === 'TIMES'" class="calc__row">
              <span>违约金倒扣（{{ (clawbackRate * 100).toFixed(0) }}% × 已用）</span>
              <strong class="calc__penalty">-{{ fmtMoney(refundCalc.clawback) }}</strong>
            </div>
            <div class="calc__row calc__row--total"><span>应退金额</span><strong>{{ fmtMoney(refundCalc.refundAmount) }}</strong></div>
            <div class="calc__tier">签署层级：<strong>{{ formTier }}</strong></div>
          </div>
          <div class="form__row">
            <label class="form__label">退款渠道</label>
            <CSelect v-model="formChannel" :options="[{value:'ORIGINAL',label:'原路退回'},{value:'CASH',label:'现金'},{value:'TRANSFER',label:'转账'}]" />
          </div>
          <div class="form__row">
            <label class="form__label">退卡原因</label>
            <CTextarea v-model="formReason" placeholder="请说明退卡原因" />
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showForm = false">取消</CButton>
          <CButton variant="primary" :disabled="!canSubmit" @click="submitForm">提交退卡申请</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.ccx { display: flex; flex-direction: column; gap: var(--s-lg); }
.ccx__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .ccx__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }

.ccx__body { display: grid; grid-template-columns: 380px 1fr; gap: var(--s-lg); align-items: start; }
.ccx__list { min-width: 0; display: flex; flex-direction: column; }
.ccx__detail-title { font-size: var(--t-md); font-weight: 700; color: var(--c-text); margin: 0; }

.tabs { display: flex; align-items: stretch; border-bottom: 1px solid var(--c-border); overflow-x: auto; padding: 0 var(--s-sm); gap: var(--s-xs); }
.tab {
  flex: 0 0 auto; padding: var(--s-md) var(--s-xs); font-size: var(--t-xs);
  color: var(--c-text-3); background: none; border: none; cursor: pointer;
  border-bottom: 2px solid transparent; white-space: nowrap;
}
.tab--active { color: var(--c-brand); border-bottom-color: var(--c-brand); font-weight: 600; }

.list { max-height: 560px; overflow-y: auto; display: flex; flex-direction: column; }
.empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); font-size: var(--t-sm); }
.empty__icon { color: var(--c-text-4); }

.rec {
  display: block; width: 100%; text-align: left; padding: var(--s-md) var(--s-lg);
  background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer;
}
.rec:hover { background: var(--c-brand-soft); }
.rec--active { background: var(--c-brand-soft); }
.rec__top { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--s-xs); }
.rec__no { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.rec__cust { font-size: var(--t-sm); color: var(--c-text); margin-bottom: 2px; }
.rec__proj { font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: var(--s-xs); }
.rec__meta { display: flex; justify-content: space-between; font-size: var(--t-xs); color: var(--c-text-3); }
.rec__amt { font-weight: 600; color: var(--c-danger-fg); }

.cust { padding-bottom: var(--s-md); border-bottom: 1px solid var(--c-border-light); }
.cust__name { font-size: var(--t-lg); font-weight: 700; color: var(--c-text); }
.cust__sub { font-size: var(--t-sm); color: var(--c-text-3); margin-top: 2px; }

.grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md) var(--s-lg); margin: var(--s-lg) 0; }
.field { display: flex; flex-direction: column; gap: 2px; }
.field--full { grid-column: 1 / -1; }
.field__label { font-size: var(--t-xs); color: var(--c-text-3); }
.field__val { font-size: var(--t-sm); color: var(--c-text); }
.field__val--amt { font-size: var(--t-lg); font-weight: 700; color: var(--c-danger-fg); }
.field__val--penalty { color: var(--c-orange-dark); font-weight: 600; }

.tip {
  display: flex; align-items: center; gap: var(--s-xs);
  font-size: var(--t-xs); color: var(--c-text-3);
  padding: var(--s-sm) var(--s-md); background: var(--c-brand-soft); border-radius: var(--r-md);
}
.tip :deep(svg) { color: var(--c-warning-fg); flex-shrink: 0; }

.trace { display: flex; flex-direction: column; gap: var(--s-sm); padding: var(--s-md); background: var(--c-bg-page); border-radius: var(--r-md); margin-top: var(--s-md); }
.trace__step { display: flex; align-items: center; gap: var(--s-sm); font-size: var(--t-sm); color: var(--c-text-3); }
.trace__step--done { color: var(--c-success-fg); }
.trace__step--active { color: var(--c-warning-fg); font-weight: 600; }

.reject-note { margin-top: var(--s-md); padding: var(--s-sm) var(--s-md); background: var(--c-danger-bg); color: var(--c-danger-fg); border-radius: var(--r-md); font-size: var(--t-sm); line-height: 1.6; }

.ops { display: flex; justify-content: flex-end; gap: var(--s-sm); margin-top: var(--s-lg); padding-top: var(--s-lg); border-top: 1px solid var(--c-border-light); }
.ops__done { display: flex; align-items: center; gap: var(--s-sm); font-size: var(--t-sm); color: var(--c-success-fg); font-weight: 600; margin-left: auto; }

.reject-box { margin-top: var(--s-md); display: flex; flex-direction: column; gap: var(--s-sm); }
.reject-box__btns { display: flex; justify-content: flex-end; gap: var(--s-sm); }

.detail-empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-md); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); }
.detail-empty__icon { color: var(--c-text-4); }

/* 弹层 */
.modal-mask { position: fixed; inset: 0; background: rgba(20,21,43,.45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 560px; max-width: 100%; max-height: 90vh; overflow-y: auto; box-shadow: var(--shadow-pop); }
.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.form__label { font-size: var(--t-xs); color: var(--c-text-3); }
.asset-opts { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-sm); }
.asset-opt {
  display: flex; flex-direction: column; gap: 2px; text-align: left;
  padding: var(--s-sm) var(--s-md); border: 1px solid var(--c-border);
  border-radius: var(--r-md); background: var(--c-surface); cursor: pointer;
}
.asset-opt:hover { border-color: var(--c-brand); }
.asset-opt--on { border-color: var(--c-brand); background: var(--c-brand-soft); }
.asset-opt__name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.asset-opt__sub { font-size: var(--t-xs); color: var(--c-text-3); }
.calc { background: var(--c-bg-page); border-radius: var(--r-md); padding: var(--s-md); display: flex; flex-direction: column; gap: var(--s-xs); }
.calc__row { display: flex; justify-content: space-between; font-size: var(--t-sm); color: var(--c-text-2); }
.calc__row--total { font-size: var(--t-base); font-weight: 700; color: var(--c-text); border-top: 1px solid var(--c-border-light); padding-top: var(--s-xs); margin-top: var(--s-xs); }
.calc__penalty { color: var(--c-orange-dark); }
.calc__tier { font-size: var(--t-xs); color: var(--c-text-3); margin-top: var(--s-xs); }
.calc__tier strong { color: var(--c-brand); }

@media (max-width: 1024px) {
  .ccx__body { grid-template-columns: 1fr; }
  .ccx__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .list { max-height: 320px; }
  .asset-opts { grid-template-columns: 1fr; }
}
</style>
