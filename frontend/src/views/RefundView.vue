<script setup lang="ts">
/* ============================================================
 * 退款管理 /refund（Desktop 优先 · 平板堆叠）
 * 逆向交易：退款 RF + 退卡 CC 共用双签状态机，层级取自设置中心。
 * 数据源：txn-service 真实 API（/txn/refund、/txn/card-cancel 列表 +
 *   /txn/{no}/approve|reject|confirm 审批动作）；金额后端存「分」，适配层转「元」。
 * 样式/模板沿用原版，仅替换数据源（mock refund store → 真实 API），未改布局与交互。
 * B95：发起表单增「关联合同」选择器（生效中合同下拉，选中按后端同款口径估算：
 *   冷静期内全额 / 期后扣违约金；金额按分计算与 ContractPenaltyJudge 逐分一致，
 *   提交 refundAmt=已付−违约金，否则后端 400）；支持合同页 ?contractNo= 跳转预选；
 *   详情展示判定时点合同快照块（旧单无快照不渲染）。
 * ============================================================ */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CTextarea from '@/components/CTextarea.vue'
import CSelect from '@/components/CSelect.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CFab from '@/components/CFab.vue'
import { useAuthStore } from '@/stores/auth'
import { useSettingsStore } from '@/stores/settings'
import { useContractStore } from '@/stores/contract'
import { useToast } from '@/composables/useToast'
import { parseContractSnapshot, type Refund, type RefundKind } from '@/stores/refund'
import {
  listRefunds, listCardCancels, createRefund, createCardCancel,
  approveTxn, rejectTxn, confirmTxn,
  type RefundDTO, type CardCancelDTO,
} from '@/api/refund'
import { REFUND_STATUS, REFUND_CHANNEL, dictPill, type RefundChannel } from '@/config/dictionary'

const auth = useAuthStore()
const settings = useSettingsStore()
const contract = useContractStore()
const route = useRoute()
const toast = useToast()

// ---- 适配层：后端实体（分）→ 模板原有 Refund 形状（元） ----
const fen2yuan = (f: number | null | undefined) => (f == null ? 0 : f / 100)
type Channel = 'ORIGINAL' | 'CASH' | 'TRANSFER'

function adaptRefund(d: RefundDTO): Refund {
  return {
    id: d.txnNo,
    refundNo: d.txnNo,
    kind: 'ORDER',
    customerId: d.customer,
    customerName: d.customerName || d.customer,
    project: d.project,
    paidAmount: fen2yuan(d.paidAmt),
    refundAmount: fen2yuan(d.refundAmt),
    channel: (d.channel as Channel) || 'ORIGINAL',
    reason: d.reason || '—',
    applicantName: d.applicant || '—',
    signTier: (d.signTier as Refund['signTier']) || 'L1',
    status: d.status as Refund['status'],
    createdAt: d.createdAt || '',
    contractNo: d.contractNo ?? undefined,
    contractSnapshot: d.contractSnapshot ?? undefined,
    reviewedByName: d.reviewedBy || undefined,
    reviewedAt: d.reviewedAt || undefined,
    financeByName: d.financeBy || undefined,
    refundedAt: d.refundedAt || undefined,
    rejectionReason: d.rejectionReason || undefined,
    rejectionByName: d.rejectedBy || undefined,
  }
}

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
    contractNo: d.contractNo ?? undefined,
    contractSnapshot: d.contractSnapshot ?? undefined,
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
    const [rfs, ccs] = await Promise.all([listRefunds(), listCardCancels()])
    refunds.value = [...rfs.data.map(adaptRefund), ...ccs.data.map(adaptCard)]
  } catch (e: any) {
    toast.error('退款数据加载失败：' + (e?.response?.data?.message || e?.message || '网络异常'))
  }
}

onMounted(async () => {
  await load()
  const qno = typeof route.query.contractNo === 'string' ? route.query.contractNo : ''
  if (!qno) return
  if (!contract.contracts.length) await contract.load()
  const c = contract.get(qno)
  showForm.value = true
  if (c && c.status === 'EFFECTIVE') {
    form.value.contractNo = c.contractNo
    form.value.customerName = c.customerName
    form.value.project = c.title
    form.value.paidAmount = c.totalAmount > 0 ? c.totalAmount.toFixed(2) : ''
  } else {
    toast.error(c ? `合同 ${qno} 非生效中，请手动关联` : `合同 ${qno} 不存在，请手动关联`)
  }
})

const actor = () => auth.user.staffId || 'cashier'

// ---- 模板所需的 store 形状适配（保持原版模板零改动）----
const pendingReview = computed(() => refunds.value.filter((r) => r.status === 'PENDING_REVIEW'))
const pendingFinance = computed(() => refunds.value.filter((r) => r.status === 'PENDING_FINANCE'))
const refunded = computed(() => refunds.value.filter((r) => r.status === 'REFUNDED'))
const rejected = computed(() => refunds.value.filter((r) => r.status === 'REJECTED'))
function get(id: string) {
  return refunds.value.find((r) => r.id === id)
}

async function create(input: {
  kind: RefundKind
  customerId: string
  customerName: string
  project: string
  paidAmount: number
  refundAmount: number
  channel: RefundChannel
  reason: string
  assetId?: string
  penaltyAmount?: number
  contractNo?: string
}): Promise<Refund | null> {
  try {
    if (input.kind === 'CARD') {
      const balance = Math.round(input.paidAmount * 100)
      const refundCents = Math.round(input.refundAmount * 100)
      const res = await createCardCancel({
        actor: actor(),
        cardNo: input.assetId || `CARD-${Date.now()}`,
        customer: input.customerId,
        customerName: input.customerName,
        cardItem: input.project,
        channel: input.channel,
        balance,
        ...(input.contractNo
          ? { contractNo: input.contractNo }
          : {
              feeManualOverride: true,
              feeCents: Math.max(0, balance - refundCents),
              feeOverrideReason: input.reason || '前台退卡手动核算违约金',
            }),
      })
      await load()
      return adaptCard(res.data)
    }
    const res = await createRefund({
      actor: actor(),
      customer: input.customerId,
      customerName: input.customerName,
      project: input.project,
      channel: input.channel,
      paidAmt: Math.round(input.paidAmount * 100),
      refundAmt: Math.round(input.refundAmount * 100),
      reason: input.reason,
      ...(input.contractNo ? { contractNo: input.contractNo } : {}),
    })
    await load()
    return adaptRefund(res.data)
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

const refund = {
  get pendingReview() { return pendingReview.value },
  get pendingFinance() { return pendingFinance.value },
  get refunded() { return refunded.value },
  get rejected() { return rejected.value },
  get, create, approve, reject, confirmRefund,
}

type Tab = 'pending_review' | 'pending_finance' | 'refunded' | 'rejected'
const tab = ref<Tab>('pending_review')
const selectedId = ref<string | null>(null)

const tabs = computed(() => [
  { k: 'pending_review' as Tab, label: `待审核 (${refund.pendingReview.length})` },
  { k: 'pending_finance' as Tab, label: `待财务复核 (${refund.pendingFinance.length})` },
  { k: 'refunded' as Tab, label: `已退款 (${refund.refunded.length})` },
  { k: 'rejected' as Tab, label: `已驳回 (${refund.rejected.length})` },
])

const list = computed<Refund[]>(() => {
  if (tab.value === 'pending_review') return refund.pendingReview
  if (tab.value === 'pending_finance') return refund.pendingFinance
  if (tab.value === 'refunded') return refund.refunded
  return refund.rejected
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
  { label: '待审核', value: refund.pendingReview.length, tone: 'warning' as const, icon: 'alert' as const },
  { label: '待财务复核', value: refund.pendingFinance.length, tone: 'warning' as const, icon: 'sign' as const },
  { label: '本月已退款', value: refund.refunded.length, tone: 'default' as const, icon: 'check-square' as const },
  { label: '退款金额合计', value: '¥' + refund.refunded.reduce((s, r) => s + r.refundAmount, 0).toLocaleString('zh-CN'), tone: 'danger' as const, icon: 'finance' as const },
])

function fmtMoney(n: number) { return '¥' + n.toLocaleString('zh-CN') }
function fmtTime(iso?: string) {
  if (!iso) return '—'
  const d = new Date(iso)
  return `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

// 审批 / 驳回 / 财务确认
const rejectReason = ref('')
const showReject = ref(false)

function doApprove() {
  if (selected.value) refund.approve(selected.value.id)
}
function doReject() {
  if (selected.value && rejectReason.value.trim()) {
    refund.reject(selected.value.id, rejectReason.value.trim())
    showReject.value = false
    rejectReason.value = ''
  }
}
function doConfirmRefund() {
  if (selected.value) refund.confirmRefund(selected.value.id)
}

// 发起退款表单
const showForm = ref(false)
const form = ref({
  kind: 'ORDER' as RefundKind,
  customerName: '',
  project: '',
  paidAmount: '',
  refundAmount: '',
  channel: 'ORIGINAL' as RefundChannel,
  reason: '',
  contractNo: '',
})
const paidNum = computed(() => Number(form.value.paidAmount) || 0)
const refundNum = computed(() => Number(form.value.refundAmount) || 0)
const formTier = computed(() => settings.tierFor(refundNum.value))

// B95：关联合同选择器（仅生效中合同可被引用，与后端 ContractPenaltyJudge 校验一致）
const contractOptions = computed(() => [
  { value: '', label: '不关联合同（手动核算）' },
  ...contract.effective.map((c) => ({
    value: c.contractNo,
    label: `${c.contractNo} · ${c.customerName} · ${c.title}`,
  })),
])
const selectedContract = computed(
  () => contract.effective.find((c) => c.contractNo === form.value.contractNo) ?? null,
)
// 后端同款口径估算（分单位逐分一致）：冷静期内违约金 0 全额退；期后按基点万分比倒扣
const contractEstimate = computed(() => {
  const c = selectedContract.value
  const paidCents = Math.round(paidNum.value * 100)
  if (!c || paidCents <= 0) return null
  const inCooling = contract.inCoolingPeriod(c)
  const penalty = inCooling ? 0 : Math.round((paidCents * Math.round(c.penaltyRate * 10000)) / 10000)
  return { inCooling, penaltyYuan: penalty / 100, refundYuan: (paidCents - penalty) / 100 }
})
// 挂合同后退款金额由合同口径锁定（后端强校验 refundAmt=已付−违约金，不符 400），自动回填防手误
watch(contractEstimate, (est) => {
  if (est && selectedContract.value) form.value.refundAmount = est.refundYuan.toFixed(2)
})
// 详情快照块（判定时点留存；旧单无快照回退 null 不渲染）
const selectedSnapshot = computed(() => parseContractSnapshot(selected.value?.contractSnapshot))

const canSubmit = computed(
  () =>
    form.value.customerName.trim() &&
    form.value.project.trim() &&
    form.value.reason.trim() &&
    paidNum.value > 0 &&
    refundNum.value > 0 &&
    refundNum.value <= paidNum.value,
)
async function submitForm() {
  if (!canSubmit.value) return
  const r = await refund.create({
    kind: form.value.kind,
    customerId: selectedContract.value?.customerId || 'C-NEW',
    customerName: form.value.customerName.trim(),
    project: form.value.project.trim(),
    paidAmount: paidNum.value,
    refundAmount: refundNum.value,
    channel: form.value.channel,
    reason: form.value.reason.trim(),
    contractNo: form.value.contractNo || undefined,
  })
  if (r) {
    showForm.value = false
    form.value = { kind: 'ORDER', customerName: '', project: '', paidAmount: '', refundAmount: '', channel: 'ORIGINAL', reason: '', contractNo: '' }
    selectedId.value = r.id
    tab.value = r.status === 'PENDING_REVIEW' ? 'pending_review' : 'pending_finance'
  }
}
</script>

<template>
  <div class="rf">
    <div class="rf__head">
      <div class="rf__kpis">
        <div v-for="k in kpis" :key="k.label" class="kpi-item">
          <div class="kpi-item__icon" :class="`kpi-item__icon--${k.tone}`">
            <CIcon :name="k.icon" :size="20" />
          </div>
          <div class="kpi-item__body">
            <div class="kpi-item__label">{{ k.label }}</div>
            <div class="kpi-item__value" :class="`kpi__value--${k.tone}`">{{ k.value }}</div>
          </div>
        </div>
      </div>
    </div>

    <div class="rf__body">
      <!-- 左列 -->
      <CCard class="rf__list" padding="none">
        <div class="tabs">
          <button
            v-for="t in tabs" :key="t.k"
            class="tab" :class="{ 'tab--active': tab === t.k }"
            @click="selectTab(t.k)"
          >{{ t.label }}</button>
        </div>
        <div class="list">
          <div v-if="list.length === 0" class="empty">
            <CIcon name="refund" :size="28" class="empty__icon" />
            <div>暂无记录</div>
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
            <div class="rec__cust">{{ r.customerName }} · {{ REFUND_CHANNEL[r.channel]?.label }}</div>
            <div class="rec__proj">{{ r.project }}</div>
            <div class="rec__meta">
              <span class="rec__amt">{{ fmtMoney(r.refundAmount) }}</span>
              <span>{{ r.signTier }} · {{ fmtTime(r.createdAt) }}</span>
            </div>
          </button>
          <CFab
            :actions="[{ icon: 'plus', label: '发起退款', disabled: !auth.can('refund:create'), onClick: () => { showForm = true } }]"
          />
        </div>
      </CCard>

      <!-- 右列详情 -->
      <CCard v-if="selected" class="rf__detail" :title="selected.refundNo">
        <template #header>
          <h3 class="rf__detail-title">{{ selected.refundNo }}</h3>
          <CStatusPill :status="dictPill(REFUND_STATUS[selected.status]).status">{{ dictPill(REFUND_STATUS[selected.status]).text }}</CStatusPill>
        </template>

        <div class="cust">
          <div class="cust__name">{{ selected.customerName }}</div>
          <div class="cust__sub">{{ selected.kind === 'CARD' ? '退卡' : '订单退款' }} · 申请人 {{ selected.applicantName }}</div>
        </div>

        <div class="grid">
          <div class="field"><span class="field__label">项目/卡项</span><span class="field__val">{{ selected.project }}</span></div>
          <div class="field"><span class="field__label">退款渠道</span><span class="field__val">{{ REFUND_CHANNEL[selected.channel]?.label }}</span></div>
          <div class="field"><span class="field__label">已付金额</span><span class="field__val">{{ fmtMoney(selected.paidAmount) }}</span></div>
          <div class="field"><span class="field__label">退款金额</span><span class="field__val field__val--amt">{{ fmtMoney(selected.refundAmount) }}</span></div>
          <div class="field field--full"><span class="field__label">退款原因</span><span class="field__val">{{ selected.reason }}</span></div>
          <div class="field"><span class="field__label">签署层级</span><span class="field__val">{{ selected.signTier }}（阈值来自设置中心）</span></div>
          <div class="field"><span class="field__label">发起时间</span><span class="field__val">{{ fmtTime(selected.createdAt) }}</span></div>
        </div>

        <!-- B95 合同判定快照（判定时点留存；旧单无快照不渲染，仅挂合同旧链显示合同号） -->
        <div v-if="selectedSnapshot" class="grid grid--snapshot">
          <div class="field"><span class="field__label">关联合同</span><span class="field__val">{{ selectedSnapshot.contractNo }}</span></div>
          <div class="field"><span class="field__label">合同标题</span><span class="field__val">{{ selectedSnapshot.title || '—' }}</span></div>
          <div class="field"><span class="field__label">冷静期</span><span class="field__val">{{ selectedSnapshot.coolingDays }} 天</span></div>
          <div class="field"><span class="field__label">违约金率</span><span class="field__val">{{ (selectedSnapshot.penaltyRate / 100).toFixed(1) }}%</span></div>
          <div class="field">
            <span class="field__label">判定结果</span>
            <span class="field__val">{{ selectedSnapshot.inCooling ? '冷静期内 · 全额退' : `已过冷静期 · 违约金 ${fmtMoney(selectedSnapshot.penaltyAmt / 100)}` }}</span>
          </div>
          <div class="field"><span class="field__label">判定时间</span><span class="field__val">{{ fmtTime(selectedSnapshot.judgedAt) }}</span></div>
        </div>
        <div v-else-if="selected.contractNo" class="grid grid--snapshot">
          <div class="field"><span class="field__label">关联合同</span><span class="field__val">{{ selected.contractNo }}</span></div>
        </div>

        <!-- 审批轨迹 -->
        <div class="trace">
          <div class="trace__step" :class="{ 'trace__step--done': true }">
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

        <!-- 操作区 -->
        <div class="ops">
          <template v-if="selected.status === 'PENDING_REVIEW'">
            <CButton variant="ghost" v-perm.disable="'refund:approve'" @click="showReject = true">驳回</CButton>
            <CButton variant="primary" v-perm.disable="'refund:approve'" @click="doApprove">
              <CIcon name="check" :size="16" />审批通过
            </CButton>
          </template>
          <template v-else-if="selected.status === 'PENDING_FINANCE'">
            <CButton variant="ghost" v-perm.disable="'refund:approve'" @click="showReject = true">驳回</CButton>
            <CButton variant="primary" v-perm.disable="'refund:sign'" @click="doConfirmRefund">
              <CIcon name="refund" :size="16" />确认退款 {{ fmtMoney(selected.refundAmount) }}
            </CButton>
          </template>
          <div v-else-if="selected.status === 'REFUNDED'" class="ops__done">
            <CIcon name="check-square" :size="16" />退款已于 {{ fmtTime(selected.refundedAt) }} 完成
          </div>
        </div>

        <!-- 驳回输入 -->
        <div v-if="showReject" class="reject-box">
          <CInput v-model="rejectReason" placeholder="请输入驳回原因（必填）" />
          <div class="reject-box__btns">
            <CButton variant="ghost" @click="showReject = false; rejectReason = ''">取消</CButton>
            <CButton variant="primary" :disabled="!rejectReason.trim()" @click="doReject">确认驳回</CButton>
          </div>
        </div>
      </CCard>

      <CCard v-else class="rf__detail rf__detail--empty" title="退款详情">
        <div class="detail-empty">
          <CIcon name="refund" :size="40" class="detail-empty__icon" />
          <p>请从左侧选择一笔退款单</p>
        </div>
      </CCard>
    </div>

    <!-- 发起退款弹层（页面内联卡片） -->
    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <CCard class="modal" title="发起退款 / 退卡" padding="lg">
        <div class="form">
          <div class="form__row">
            <label class="form__label">类型</label>
            <div class="form__seg">
              <button :class="{ 'form__seg--on': form.kind === 'ORDER' }" @click="form.kind = 'ORDER'">订单退款</button>
              <button :class="{ 'form__seg--on': form.kind === 'CARD' }" @click="form.kind = 'CARD'">退卡</button>
            </div>
          </div>
          <div class="form__row">
            <label class="form__label">关联合同</label>
            <CSelect v-model="form.contractNo" :options="contractOptions" placeholder="不关联合同（手动核算）" />
          </div>
          <div v-if="contractEstimate" class="form__estimate">
            <template v-if="contractEstimate.inCooling">
              冷静期内 · 全额退款 <strong>{{ fmtMoney(contractEstimate.refundYuan) }}</strong>
            </template>
            <template v-else>
              已过冷静期 · 违约金 <strong class="form__estimate-penalty">-{{ fmtMoney(contractEstimate.penaltyYuan) }}</strong>
              · 应退 <strong>{{ fmtMoney(contractEstimate.refundYuan) }}</strong>
            </template>
          </div>
          <div class="form__row">
            <label class="form__label">客户姓名</label>
            <CInput v-model="form.customerName" placeholder="如：王美丽" />
          </div>
          <div class="form__row">
            <label class="form__label">项目 / 卡项</label>
            <CInput v-model="form.project" placeholder="如：光子嫩肤 5 次卡" />
          </div>
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">已付金额</label>
              <CInput v-model="form.paidAmount" type="number" placeholder="0" />
            </div>
            <div>
              <label class="form__label">退款金额</label>
              <CInput v-model="form.refundAmount" type="number" placeholder="0" />
            </div>
          </div>
          <div class="form__row">
            <label class="form__label">退款渠道</label>
            <CSelect v-model="form.channel" :options="[{value:'ORIGINAL',label:'原路退回'},{value:'CASH',label:'现金'},{value:'TRANSFER',label:'转账'}]" />
          </div>
          <div class="form__row">
            <label class="form__label">退款原因</label>
            <CTextarea v-model="form.reason" placeholder="请说明退款原因" />
          </div>
          <div class="form__tier">
            签署层级：<strong>{{ formTier }}</strong>
            <span class="form__tier-hint">{{ formTier === 'L1' ? '直达财务复核' : '需店长/运营审批后再财务复核' }}</span>
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showForm = false">取消</CButton>
          <CButton variant="primary" :disabled="!canSubmit" @click="submitForm">提交申请</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.rf { display: flex; flex-direction: column; gap: var(--s-lg); }
.rf__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .rf__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
.rf__kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); }
.kpi-item {
  display: flex; align-items: center; gap: var(--s-md);
  background: var(--c-surface); border: 1px solid var(--c-border-light);
  border-radius: var(--r-xl); padding: var(--s-md);
}
.kpi-item__icon {
  width: 40px; height: 40px; border-radius: var(--r-lg);
  background: var(--c-brand-soft); color: var(--c-brand);
  display: flex; align-items: center; justify-content: center; flex-shrink: 0;
}
.kpi-item__icon--warning { background: var(--c-warning-bg, #fff7e6); color: var(--c-warning-fg); }
.kpi-item__icon--danger { background: var(--c-danger-bg, #fff1f0); color: var(--c-danger-fg); }
.kpi-item__icon--success { background: var(--c-success-bg, #f0fbf0); color: var(--c-success-fg); }
.kpi-item__body { min-width: 0; flex: 1; }
.kpi-item__label { font-size: var(--t-xs); color: var(--c-text-3); line-height: var(--lh-xs); }
.kpi-item__value { font-size: var(--t-lg); font-weight: 700; line-height: 1.3; font-variant-numeric: tabular-nums; }
.kpi__value--warning { color: var(--c-warning-fg); }
.kpi__value--danger { color: var(--c-danger-fg); }
.kpi__value--default { color: var(--c-success-fg); }

.rf__body { display: grid; grid-template-columns: 380px 1fr; gap: var(--s-lg); align-items: start; }
.rf__list { min-width: 0; display: flex; flex-direction: column; }

.rf__detail-title { font-size: var(--t-md); line-height: var(--lh-md); font-weight: 700; color: var(--c-text); margin: 0; }

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
.rec__proj { font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: var(--s-xs); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.rec__meta { display: flex; justify-content: space-between; font-size: var(--t-xs); color: var(--c-text-3); }
.rec__amt { font-weight: 600; color: var(--c-danger-fg); }

.cust { padding-bottom: var(--s-md); border-bottom: 1px solid var(--c-border-light); }
.cust__name { font-size: var(--t-lg); font-weight: 700; color: var(--c-text); }
.cust__sub { font-size: var(--t-sm); color: var(--c-text-3); margin-top: 2px; }

.grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md) var(--s-lg); margin: var(--s-lg) 0; }
.grid--snapshot { margin-top: 0; padding: var(--s-md); background: var(--c-bg-page); border-radius: var(--r-md); }
.field { display: flex; flex-direction: column; gap: 2px; }
.field--full { grid-column: 1 / -1; }
.field__label { font-size: var(--t-xs); color: var(--c-text-3); }
.field__val { font-size: var(--t-sm); color: var(--c-text); }
.field__val--amt { font-size: var(--t-lg); font-weight: 700; color: var(--c-danger-fg); }

.trace { display: flex; flex-direction: column; gap: var(--s-sm); padding: var(--s-md); background: var(--c-bg-page); border-radius: var(--r-md); }
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

/* 发起退款弹层 */
.modal-mask { position: fixed; inset: 0; background: rgba(20,21,43,.45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 560px; max-width: 100%; max-height: 90vh; overflow-y: auto; box-shadow: var(--shadow-pop); }
.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.form__row--2 { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.form__label { font-size: var(--t-xs); color: var(--c-text-3); }
.form__seg { display: inline-flex; border: 1px solid var(--c-border); border-radius: var(--r-capsule); overflow: hidden; align-self: flex-start; }
.form__seg button { padding: var(--s-xs) var(--s-md); font-size: var(--t-sm); background: none; border: none; cursor: pointer; color: var(--c-text-2); }
.form__seg button.form__seg--on { background: var(--c-brand); color: #fff; }
.form__tier { font-size: var(--t-sm); color: var(--c-text-2); padding: var(--s-sm) var(--s-md); background: var(--c-brand-soft); border-radius: var(--r-md); }
.form__tier strong { color: var(--c-brand); margin: 0 var(--s-xs); }
.form__tier-hint { color: var(--c-text-3); font-size: var(--t-xs); }
.form__estimate { font-size: var(--t-sm); color: var(--c-text-2); padding: var(--s-sm) var(--s-md); background: var(--c-brand-soft); border-radius: var(--r-md); }
.form__estimate strong { color: var(--c-brand); margin: 0 var(--s-xs); }
.form__estimate-penalty { color: var(--c-danger-fg) !important; }

@media (max-width: 1024px) {
  .rf__body { grid-template-columns: 1fr; }
  .rf__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .list { max-height: 320px; }
}
</style>
