<script setup lang="ts">
/* ============================================================
 * M6-04 发票管理 /m6-invoice
 * 4 KPI（本月开票额/待开笔数/红冲笔数/可开票金额）
 * 左：发票列表；右：发票详情 + 开具/红冲双签
 * 红线：发票仅作凭证登记，开票由外部开票系统完成，本页不触达资金
 * 适配已有 store: useFinInvoiceStore
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import { useFinInvoiceStore } from '@/stores/finInvoice'
import { useFinInputInvoiceStore, type RegisterForm, type InputNondeductReason } from '@/stores/finInputInvoice'
import { useAuthStore } from '@/stores/auth'
import { useStoreContext } from '@/stores/storeContext'
import { useToast } from '@/composables/useToast'
import { exportInvoiceCsv, type InvoiceStatus, type InvoiceType } from '@/api/finance'

const store = useFinInvoiceStore()
const inputStore = useFinInputInvoiceStore()
const auth = useAuthStore()
const storeCtx = useStoreContext()
const toast = useToast()
const exporting = ref(false)
onMounted(() => store.seed())

// B63 卡4：销项/进项顶层页签（标准 .tabs/.tab 母本，不新增路由）
const tab = ref<'output' | 'input'>('output')
const tabs = [
  { k: 'output' as const, label: '销项发票' },
  { k: 'input' as const, label: '进项发票' },
]
function selectTab(k: 'output' | 'input') {
  if (tab.value === k) return
  tab.value = k
  if (k === 'input' && inputStore.items.length === 0 && !inputStore.loading) {
    void inputStore.loadList(0).catch((e) => {
      toast.error('进项发票加载失败：' + (e?.response?.data?.message || e?.message || '网络异常'))
    })
  }
}

async function onExport() {
  if (exporting.value) return
  exporting.value = true
  try {
    await exportInvoiceCsv({
      status: store.filterStatus === 'ALL' ? undefined : (store.filterStatus as InvoiceStatus),
      type: store.filterType === 'ALL' ? undefined : (store.filterType as InvoiceType),
      keyword: store.keyword.trim() || undefined,
    })
  } catch (e) {
    toast.error(e instanceof Error ? e.message : '发票台账导出失败')
  } finally {
    exporting.value = false
  }
}

const selectedId = ref<string | null>(null)
const selected = computed(() => {
  if (selectedId.value) return store.get(selectedId.value) ?? null
  return store.filtered[0] ?? null
})

const canEdit = computed(() => auth.can('finance:invoice:edit'))

const kpis = computed(() => [
  { label: '本月开票额', icon: 'finance', value: `¥${(store.monthIssuedAmount / 10000).toFixed(1)}万`, tone: 'brand' as const },
  { label: '待开笔数', icon: 'pos', value: String(store.drafts.length), tone: 'warning' as const },
  { label: '红冲/作废', icon: 'refund', value: String(store.voided.length), tone: 'danger' as const },
  { label: '可开票金额', icon: 'finance', value: `¥${(store.drafts.reduce((s, i) => s + i.amount, 0) / 10000).toFixed(1)}万`, tone: 'text' as const },
])

const statusOptions = [
  { value: 'ALL', label: '全部状态' },
  { value: 'DRAFT', label: '待开票' },
  { value: 'ISSUED', label: '已开票' },
  { value: 'VOIDED', label: '已作废' },
  { value: 'RED_FLUSHED', label: '已红冲' },
]
const typeOptions = [
  { value: 'ALL', label: '全部票种' },
  { value: 'NORMAL', label: '增值税普通发票' },
  { value: 'SPECIAL', label: '增值税专用发票' },
  { value: 'ELECTRONIC', label: '增值税电子普通发票' },
]
const rateOptions = store.RATES.map((r) => ({ value: String(r), label: r === 0 ? '免税 0%' : `${(r * 100).toFixed(0)}%` }))

function money(n: number) {
  return `¥${n.toLocaleString('zh-CN')}`
}
function fmtDate(iso: string) {
  return iso ? iso.slice(0, 10) : '—'
}

// 申请开票（创建草稿）弹层
const showCreate = ref(false)
const form = ref({
  type: 'ELECTRONIC' as 'NORMAL' | 'SPECIAL' | 'ELECTRONIC',
  category: 'SERVICE' as 'SERVICE' | 'PRODUCT' | 'MEMBERSHIP',
  title: '', taxNo: '', buyerName: '', orderRefs: '',
  amount: 0, taxRate: 0.06, store: '静安旗舰店',
})
const formTax = computed(() => {
  const r = form.value.taxRate
  return r > 0 ? Math.round(form.value.amount - form.value.amount / (1 + r)) : 0
})
function openCreate() {
  form.value = { type: 'ELECTRONIC', category: 'SERVICE', title: '', taxNo: '', buyerName: '', orderRefs: '', amount: 0, taxRate: 0.06, store: '静安旗舰店' }
  showCreate.value = true
}
function submitCreate() {
  if (!form.value.title || !form.value.amount) return
  store.create({
    type: form.value.type, category: form.value.category,
    title: form.value.title, taxNo: form.value.taxNo, buyerName: form.value.buyerName,
    orderRefs: form.value.orderRefs ? form.value.orderRefs.split(/[,，\s]+/).filter(Boolean) : [],
    amount: Math.round(form.value.amount), taxAmount: formTax.value, taxRate: form.value.taxRate,
    store: form.value.store,
  })
  showCreate.value = false
}

// 开具双签
const showIssue = ref(false)
const issueReviewer = ref('')
function openIssue() { issueReviewer.value = ''; showIssue.value = true }
function submitIssue() {
  if (!selected.value || issueReviewer.value.trim().length < 2) return
  store.markIssued(selected.value.id, issueReviewer.value.trim())
  showIssue.value = false
}
const canIssue = computed(() => issueReviewer.value.trim().length >= 2)

// 红冲双签
const showFlush = ref(false)
const flushForm = ref({ reviewer: '', reason: '' })
function openFlush() { flushForm.value = { reviewer: '', reason: '' }; showFlush.value = true }
function submitFlush() {
  if (!selected.value || flushForm.value.reviewer.trim().length < 2 || !flushForm.value.reason.trim()) return
  store.redFlush(selected.value.id, flushForm.value.reason.trim())
  showFlush.value = false
}
const canFlush = computed(() => flushForm.value.reviewer.trim().length >= 2 && flushForm.value.reason.trim().length > 0)

// 作废
const showVoid = ref(false)
const voidReason = ref('')
function openVoid() { voidReason.value = ''; showVoid.value = true }
function submitVoid() {
  if (!selected.value || !voidReason.value.trim()) return
  store.voidInvoice(selected.value.id, voidReason.value.trim())
  showVoid.value = false
}

// ==================== B63 卡4 进项发票 ====================
const inputSelectedId = ref<number | null>(null)
const inputSelected = computed(() => {
  if (inputSelectedId.value !== null) return inputStore.get(inputSelectedId.value) ?? null
  return inputStore.items[0] ?? null
})

const inputStatusFilter = computed({
  get: () => inputStore.filterStatus,
  set: (v) => { inputStore.filterStatus = v },
})
const inputKindFilter = computed({
  get: () => inputStore.filterKind,
  set: (v) => { inputStore.filterKind = v },
})
const inputPurposeFilter = computed({
  get: () => inputStore.filterPurpose,
  set: (v) => { inputStore.filterPurpose = v },
})
const inputFilterStatusOptions = [{ value: '', label: '全部状态' }, ...inputStore.STATUS_OPTIONS]
const inputFilterKindOptions = [{ value: '', label: '全部票种' }, ...inputStore.KIND_OPTIONS]
const inputFilterPurposeOptions = [
  { value: '', label: '全部用途' },
  { value: 'DEDUCT', label: '抵扣' },
  { value: 'NO_DEDUCT', label: '不抵扣' },
  { value: 'REFUND', label: '退税' },
  { value: 'PENDING', label: '待确认' },
]
const inputStoreOptions = computed(() => storeCtx.stores.map((s) => ({ value: s.storeName, label: s.storeName })))
const inputRateOptions = inputStore.RATES.map((r) => ({ value: String(r), label: r === 0 ? '免税 0%' : `${(r * 100).toFixed(0)}%` }))
const inputReasonOptions = inputStore.REASON_OPTIONS
const inputPages = computed(() => Math.max(1, Math.ceil(inputStore.total / inputStore.size)))

function onInputFilter() {
  void inputStore.loadList(0).catch((e: unknown) => toast.error('进项发票加载失败：' + errMsg(e)))
}
function onInputKeyword() {
  void inputStore.loadList(0).catch((e: unknown) => toast.error('进项发票加载失败：' + errMsg(e)))
}
function gotoPage(p: number) {
  if (p < 0 || p >= inputPages.value || p === inputStore.page) return
  void inputStore.loadList(p).catch((e: unknown) => toast.error('进项发票加载失败：' + errMsg(e)))
}
function errMsg(e: unknown) {
  const anyE = e as { response?: { data?: { message?: string } }; message?: string }
  return anyE?.response?.data?.message || anyE?.message || '网络异常'
}

// 登记进项发票弹层
const showInputCreate = ref(false)
function todayStr() {
  const d = new Date()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${m}-${day}`
}
const inputForm = ref<RegisterForm>({
  invoiceKind: 'SPECIAL',
  category: 'SERVICE',
  sellerName: '', sellerTaxNo: '', invoiceNo: '', invoiceCode: '',
  supplierId: null, store: '', invoiceDate: todayStr(),
  amount: 0, taxRate: 0.06, remark: '',
})
const inputFormTax = computed(() => {
  const r = inputForm.value.taxRate
  return r > 0 ? Math.round(inputForm.value.amount - inputForm.value.amount / (1 + r)) : 0
})
function openInputCreate() {
  inputForm.value = {
    invoiceKind: 'SPECIAL', category: 'SERVICE',
    sellerName: '', sellerTaxNo: '', invoiceNo: '', invoiceCode: '',
    supplierId: null,
    store: storeCtx.currentStoreName || storeCtx.stores[0]?.storeName || '',
    invoiceDate: todayStr(), amount: 0, taxRate: 0.06, remark: '',
  }
  showInputCreate.value = true
}
const canSubmitInputCreate = computed(() =>
  inputForm.value.sellerName.trim()
  && inputForm.value.sellerTaxNo.trim()
  && inputForm.value.invoiceNo.trim()
  && inputForm.value.store
  && inputForm.value.invoiceDate
  && inputForm.value.amount > 0)
async function submitInputCreate() {
  if (!canSubmitInputCreate.value) return
  try {
    const d = await inputStore.register(inputForm.value)
    if (d) toast.success(`登记成功：${d.registerNo}`)
    showInputCreate.value = false
  } catch (e) {
    toast.error('登记失败：' + errMsg(e))
  }
}

// 用途确认弹层
const showInputConfirm = ref(false)
const confirmPurpose = ref<'DEDUCT' | 'NO_DEDUCT' | 'REFUND'>('DEDUCT')
const confirmReason = ref<InputNondeductReason>('WELFARE')
const confirmRemark = ref('')
function openInputConfirm() {
  confirmPurpose.value = 'DEDUCT'
  confirmReason.value = 'WELFARE'
  confirmRemark.value = ''
  showInputConfirm.value = true
}
async function submitInputConfirm() {
  if (!inputSelected.value) return
  try {
    await inputStore.confirm(
      inputSelected.value.id,
      confirmPurpose.value,
      confirmPurpose.value === 'NO_DEDUCT' ? confirmReason.value : undefined,
      confirmRemark.value,
    )
    toast.success('用途确认成功')
    showInputConfirm.value = false
  } catch (e) {
    toast.error('用途确认失败：' + errMsg(e))
  }
}

// 撤销确认
async function onInputRevoke() {
  if (!inputSelected.value) return
  try {
    await inputStore.revokeConfirm(inputSelected.value.id)
    toast.success('已撤销用途确认')
  } catch (e) {
    toast.error('撤销失败：' + errMsg(e))
  }
}

// 抵扣（periodId 缺省，后端懒创建当前月 OPEN 期）
const deducting = ref(false)
async function onInputDeduct() {
  if (!inputSelected.value || deducting.value) return
  deducting.value = true
  try {
    await inputStore.deduct(inputSelected.value.id)
    toast.success('申报抵扣成功')
  } catch (e) {
    toast.error('抵扣失败：' + errMsg(e))
  } finally {
    deducting.value = false
  }
}

// 进项转出弹层
const showTransferOut = ref(false)
const transferAmount = ref(0)
const transferReason = ref<InputNondeductReason>('WELFARE')
const transferRemark = ref('')
function openTransferOut() {
  transferAmount.value = inputSelected.value?.taxAmount ?? 0
  transferReason.value = 'WELFARE'
  transferRemark.value = ''
  showTransferOut.value = true
}
const canSubmitTransfer = computed(() =>
  inputSelected.value !== null
  && transferAmount.value > 0
  && transferAmount.value <= inputSelected.value.taxAmount)
async function submitTransferOut() {
  if (!inputSelected.value || !canSubmitTransfer.value) return
  try {
    await inputStore.transferOut(inputSelected.value.id, transferAmount.value, transferReason.value, transferRemark.value)
    toast.success('进项转出成功')
    showTransferOut.value = false
  } catch (e) {
    toast.error('进项转出失败：' + errMsg(e))
  }
}

// 标记不抵扣弹层
const showNonDeduct = ref(false)
const nonDeductReason = ref<InputNondeductReason>('WELFARE')
const nonDeductRemark = ref('')
function openNonDeduct() {
  nonDeductReason.value = 'WELFARE'
  nonDeductRemark.value = ''
  showNonDeduct.value = true
}
async function submitNonDeduct() {
  if (!inputSelected.value) return
  try {
    await inputStore.markNonDeductible(inputSelected.value.id, nonDeductReason.value, nonDeductRemark.value)
    toast.success('已标记不抵扣')
    showNonDeduct.value = false
  } catch (e) {
    toast.error('操作失败：' + errMsg(e))
  }
}
</script>

<template>
  <div class="inv">
    <!-- B63 卡4：销项/进项顶层页签（标准 tabs 母本） -->
    <div class="tabs">
      <button v-for="t in tabs" :key="t.k" class="tab"
        :class="{ 'tab--active': tab === t.k }" @click="selectTab(t.k)">{{ t.label }}</button>
    </div>

    <div v-if="tab === 'output'" class="inv__output">
    <div class="inv__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <div class="inv__body">
      <!-- 左：发票列表 -->
      <CCard class="inv__list" padding="none">
        <div class="filters">
          <CSelect v-model="store.filterType" :options="typeOptions" />
          <CSelect v-model="store.filterStatus" width="120px" :options="statusOptions" />
          <CButton variant="secondary" size="sm" :disabled="exporting"
            v-perm.disable="'finance:export'" @click="onExport">
            <CIcon name="export" :size="14" />{{ exporting ? '导出中…' : '导出' }}
          </CButton>
          <CButton v-if="canEdit" variant="primary" size="sm" @click="openCreate">
            <CIcon name="plus" :size="14" />申请开票
          </CButton>
        </div>
        <div class="list">
          <div v-if="store.filtered.length === 0" class="empty">
            <CIcon name="order" :size="28" class="empty__icon" />
            <div>暂无发票数据</div>
          </div>
          <button
            v-for="inv in store.filtered" :key="inv.id"
            class="row" :class="{ 'row--active': selected?.id === inv.id }"
            @click="selectedId = inv.id"
          >
            <div class="row__top">
              <span class="row__no">{{ inv.invoiceNo }}</span>
              <CStatusPill :status="store.STATUS_PILL[inv.status]" dot>{{ store.STATUS_LABEL[inv.status] }}</CStatusPill>
            </div>
            <div class="row__title">{{ inv.title }}</div>
            <div class="row__sub">{{ store.TYPE_LABEL[inv.type] }} · {{ inv.buyerName }}</div>
            <div class="row__bottom">
              <span class="row__amt">{{ money(inv.amount) }}</span>
              <span class="row__date">{{ fmtDate(inv.issuedAt) }}</span>
            </div>
          </button>
        </div>
      </CCard>

      <!-- 右：详情 -->
      <CCard v-if="selected" class="inv__detail" padding="none">
        <template #header>
          <div class="inv__detail-head">
            <div class="inv__who">
              <h3 class="inv__no">{{ selected.invoiceNo }}</h3>
              <div class="inv__sub">{{ selected.title }}</div>
            </div>
            <CStatusPill :status="store.STATUS_PILL[selected.status]" dot>{{ store.STATUS_LABEL[selected.status] }}</CStatusPill>
          </div>
        </template>

        <div class="detail-body">
          <div class="stat-grid">
            <div class="stat">
              <div class="stat__label">价税合计</div>
              <div class="stat__value stat__value--brand">{{ money(selected.amount) }}</div>
            </div>
            <div class="stat">
              <div class="stat__label">税额</div>
              <div class="stat__value">{{ money(selected.taxAmount) }}</div>
            </div>
            <div class="stat">
              <div class="stat__label">税率</div>
              <div class="stat__value">{{ selected.taxRate === 0 ? '免税' : (selected.taxRate * 100).toFixed(0) + '%' }}</div>
            </div>
            <div class="stat">
              <div class="stat__label">开票日期</div>
              <div class="stat__value">{{ fmtDate(selected.issuedAt) }}</div>
            </div>
          </div>

          <div class="kv">
            <div class="kv__row"><span class="kv__k">票种</span><span class="kv__v">{{ store.TYPE_LABEL[selected.type] }}</span></div>
            <div class="kv__row"><span class="kv__k">项目类别</span><span class="kv__v">{{ store.CATEGORY_LABEL[selected.category] }}</span></div>
            <div class="kv__row"><span class="kv__k">购方客户</span><span class="kv__v">{{ selected.buyerName }}</span></div>
            <div class="kv__row"><span class="kv__k">税号</span><span class="kv__v">{{ selected.taxNo || '—' }}</span></div>
            <div class="kv__row"><span class="kv__k">关联订单</span><span class="kv__v">{{ selected.orderRefs.join('、') || '—' }}</span></div>
            <div class="kv__row"><span class="kv__k">门店</span><span class="kv__v">{{ selected.store }}</span></div>
            <div class="kv__row"><span class="kv__k">开票人 / 复核</span><span class="kv__v">{{ selected.operator }}<template v-if="selected.reviewer"> / {{ selected.reviewer }}</template></span></div>
            <div v-if="selected.remark" class="kv__row"><span class="kv__k">备注</span><span class="kv__v kv__v--danger">{{ selected.remark }}</span></div>
          </div>

          <!-- 税额分税率统计 -->
          <div class="block">
            <div class="block__title"><span>税额分税率统计</span></div>
            <div class="taxrows">
              <div v-for="t in store.taxBreakdown" :key="t.rate" class="taxrow">
                <span class="taxrow__rate">{{ t.rate === 0 ? '免税' : (t.rate * 100).toFixed(0) + '%' }}</span>
                <span class="taxrow__amt">{{ money(t.amount) }}</span>
                <span class="taxrow__tax">税额 {{ money(t.tax) }}</span>
              </div>
              <div v-if="store.taxBreakdown.length === 0" class="taxrow taxrow--empty">暂无已开票数据</div>
            </div>
          </div>

          <div class="ops">
            <CButton v-if="selected.status === 'DRAFT'" variant="primary" size="sm" v-perm.disable="'finance:invoice:edit'" @click="openIssue">
              <CIcon name="check" :size="14" />开具发票（双签）
            </CButton>
            <template v-if="selected.status === 'ISSUED'">
              <CButton variant="secondary" size="sm" v-perm.disable="'finance:invoice:edit'" @click="openVoid">
                <CIcon name="close" :size="14" />作废
              </CButton>
              <CButton variant="danger" size="sm" v-perm.disable="'finance:invoice:approve'" @click="openFlush">
                <CIcon name="alert" :size="14" />红冲审批（双签）
              </CButton>
            </template>
            <span v-if="selected.status === 'VOIDED'" class="ops__hint ops__hint--danger">该发票已作废</span>
            <span v-if="selected.status === 'RED_FLUSHED'" class="ops__hint ops__hint--danger">该发票已红冲</span>
          </div>
        </div>
      </CCard>

      <CCard v-else class="inv__detail inv__detail--empty" title="发票详情" padding="lg">
        <div class="detail-empty">
          <CIcon name="order" :size="40" class="detail-empty__icon" />
          <p>请选择一张发票</p>
        </div>
      </CCard>
    </div>
    </div>
    <!-- /B63 卡4 销项整块包裹结束（销项样式零改） -->

    <!-- ==================== B63 卡4 进项发票 ==================== -->
    <div v-if="tab === 'input'" class="inv__body">
      <!-- 左：进项发票列表 -->
      <CCard class="inv__list" padding="none">
        <div class="filters">
          <CInput v-model="inputStore.keyword" placeholder="登记号/票号/销方" @keyup.enter="onInputKeyword" />
          <CButton variant="secondary" size="sm" @click="onInputKeyword">查询</CButton>
          <CButton v-if="auth.can('finance:input:edit')" variant="primary" size="sm" @click="openInputCreate">
            <CIcon name="plus" :size="14" />登记进项
          </CButton>
        </div>
        <div class="filters filters--2">
          <CSelect v-model="inputKindFilter" width="100%" :options="inputFilterKindOptions" @update:model-value="onInputFilter" />
          <CSelect v-model="inputStatusFilter" width="100%" :options="inputFilterStatusOptions" @update:model-value="onInputFilter" />
          <CSelect v-model="inputPurposeFilter" width="100%" :options="inputFilterPurposeOptions" @update:model-value="onInputFilter" />
        </div>
        <div class="list">
          <div v-if="inputStore.loading" class="empty">加载中…</div>
          <div v-else-if="inputStore.items.length === 0" class="empty">
            <CIcon name="order" :size="28" class="empty__icon" />
            <div>暂无进项发票数据</div>
          </div>
          <button
            v-for="inv in inputStore.items" :key="inv.id"
            class="row" :class="{ 'row--active': inputSelected?.id === inv.id }"
            @click="inputSelectedId = inv.id"
          >
            <div class="row__top">
              <span class="row__no">{{ inv.registerNo }}</span>
              <CStatusPill :status="inputStore.STATUS_PILL[inv.status]" dot>{{ inv.statusLabel }}</CStatusPill>
            </div>
            <div class="row__title">{{ inv.sellerName }}</div>
            <div class="row__sub">{{ inv.invoiceKindLabel }} · 票号 {{ inv.invoiceNo }}</div>
            <div class="row__bottom">
              <span class="row__amt">{{ money(inv.amount) }}</span>
              <span class="row__date">税额 {{ money(inv.taxAmount) }} · {{ inv.invoiceDate }}</span>
            </div>
          </button>
        </div>
        <div v-if="inputStore.total > 0" class="pager">
          <CButton variant="ghost" size="sm" :disabled="inputStore.page === 0" @click="gotoPage(inputStore.page - 1)">上一页</CButton>
          <span class="pager__info">{{ inputStore.page + 1 }} / {{ inputPages }}（共 {{ inputStore.total }} 条）</span>
          <CButton variant="ghost" size="sm" :disabled="inputStore.page + 1 >= inputPages" @click="gotoPage(inputStore.page + 1)">下一页</CButton>
        </div>
      </CCard>

      <!-- 右：进项发票详情 + 状态机操作 -->
      <CCard v-if="inputSelected" class="inv__detail" padding="none">
        <template #header>
          <div class="inv__detail-head">
            <div class="inv__who">
              <h3 class="inv__no">{{ inputSelected.registerNo }}</h3>
              <div class="inv__sub">{{ inputSelected.sellerName }} · {{ inputSelected.invoiceKindLabel }}</div>
            </div>
            <CStatusPill :status="inputStore.STATUS_PILL[inputSelected.status]" dot>{{ inputSelected.statusLabel }}</CStatusPill>
          </div>
        </template>

        <div class="detail-body">
          <div class="stat-grid">
            <div class="stat">
              <div class="stat__label">价税合计</div>
              <div class="stat__value stat__value--brand">{{ money(inputSelected.amount) }}</div>
            </div>
            <div class="stat">
              <div class="stat__label">税额</div>
              <div class="stat__value">{{ money(inputSelected.taxAmount) }}</div>
            </div>
            <div class="stat">
              <div class="stat__label">不含税金额</div>
              <div class="stat__value">{{ money(inputSelected.netAmount) }}</div>
            </div>
            <div class="stat">
              <div class="stat__label">税率</div>
              <div class="stat__value">{{ inputSelected.taxRate === 0 ? '免税' : (inputSelected.taxRate * 100).toFixed(0) + '%' }}</div>
            </div>
          </div>

          <div class="kv">
            <div class="kv__row"><span class="kv__k">发票号码</span><span class="kv__v">{{ inputSelected.invoiceNo }}</span></div>
            <div class="kv__row"><span class="kv__k">开票方</span><span class="kv__v">{{ inputSelected.sellerName }}</span></div>
            <div class="kv__row"><span class="kv__k">销方税号</span><span class="kv__v">{{ inputSelected.sellerTaxNo }}</span></div>
            <div class="kv__row"><span class="kv__k">开票日期</span><span class="kv__v">{{ inputSelected.invoiceDate }}（入账账龄 {{ inputSelected.ageDays }} 天）</span></div>
            <div class="kv__row"><span class="kv__k">采购用途</span><span class="kv__v">{{ inputSelected.categoryLabel }} · 当前用途：{{ inputSelected.purposeLabel }}</span></div>
            <div class="kv__row"><span class="kv__k">归属门店</span><span class="kv__v">{{ inputSelected.store }}</span></div>
            <div class="kv__row"><span class="kv__k">登记人 / 确认人</span><span class="kv__v">{{ inputSelected.operator }}<template v-if="inputSelected.confirmer"> / {{ inputSelected.confirmer }}</template></span></div>
            <div v-if="inputSelected.periodId" class="kv__row"><span class="kv__k">抵扣期间</span><span class="kv__v">#{{ inputSelected.periodId }}</span></div>
            <div v-if="inputSelected.transferOutAmount > 0" class="kv__row"><span class="kv__k">累计进项转出</span><span class="kv__v kv__v--danger">{{ money(inputSelected.transferOutAmount) }}</span></div>
            <div v-if="inputSelected.nondeductReasonLabel" class="kv__row"><span class="kv__k">不抵扣/转出原因</span><span class="kv__v kv__v--danger">{{ inputSelected.nondeductReasonLabel }}</span></div>
            <div v-if="inputSelected.remark" class="kv__row"><span class="kv__k">备注</span><span class="kv__v">{{ inputSelected.remark }}</span></div>
          </div>

          <div class="block">
            <div class="block__title"><span>状态机操作</span></div>
            <div class="ops">
              <template v-if="inputSelected.status === 'UNCONFIRMED'">
                <CButton variant="primary" size="sm" v-perm.disable="'finance:input:confirm'" @click="openInputConfirm">
                  <CIcon name="check" :size="14" />用途确认
                </CButton>
                <CButton variant="secondary" size="sm" v-perm.disable="'finance:input:edit'" @click="openNonDeduct">
                  <CIcon name="close" :size="14" />标记不抵扣
                </CButton>
              </template>
              <template v-if="inputSelected.status === 'CONFIRMED'">
                <CButton v-if="inputSelected.purpose === 'DEDUCT'" variant="primary" size="sm"
                  :disabled="deducting" v-perm.disable="'finance:input:confirm'" @click="onInputDeduct">
                  <CIcon name="check" :size="14" />{{ deducting ? '抵扣中…' : '申报抵扣' }}
                </CButton>
                <CButton variant="ghost" size="sm" v-perm.disable="'finance:input:confirm'" @click="onInputRevoke">撤销确认</CButton>
                <span v-if="inputSelected.purpose === 'REFUND'" class="ops__hint">退税勾选不在本卡办理抵扣，请走退税申报流程</span>
              </template>
              <CButton v-if="inputSelected.status === 'DEDUCTED'" variant="danger" size="sm" v-perm.disable="'finance:input:confirm'" @click="openTransferOut">
                <CIcon name="alert" :size="14" />进项转出
              </CButton>
              <span v-if="inputSelected.status === 'TRANSFERRED_OUT'" class="ops__hint ops__hint--danger">已进项转出（终态）</span>
              <span v-if="inputSelected.status === 'NON_DEDUCTIBLE'" class="ops__hint ops__hint--danger">不抵扣（终态）</span>
            </div>
          </div>
        </div>
      </CCard>

      <CCard v-else class="inv__detail inv__detail--empty" title="进项发票详情" padding="lg">
        <div class="detail-empty">
          <CIcon name="order" :size="40" class="detail-empty__icon" />
          <p>请选择一张进项发票</p>
        </div>
      </CCard>
    </div>

    <!-- 申请开票弹层 -->
    <div v-if="showCreate" class="modal-mask" @click.self="showCreate = false">
      <CCard class="modal" title="申请开票（创建草稿）" padding="lg">
        <div class="form">
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">票种 <span class="req">*</span></label>
              <CSelect v-model="form.type" width="100%" :options="typeOptions.filter(o => o.value !== 'ALL').map(o => ({ value: o.value, label: o.label }))" />
            </div>
            <div>
              <label class="form__label">项目类别</label>
              <CSelect v-model="form.category" width="100%" :options="[
                { value: 'SERVICE', label: '医疗服务' },
                { value: 'PRODUCT', label: '产品销售' },
                { value: 'MEMBERSHIP', label: '会员卡/疗程' },
              ]" />
            </div>
          </div>
          <div class="form__row">
            <label class="form__label">发票抬头 <span class="req">*</span></label>
            <CInput v-model="form.title" placeholder="企业全称或个人姓名" />
          </div>
          <div class="form__row">
            <label class="form__label">税号</label>
            <CInput v-model="form.taxNo" placeholder="企业税号，个人可留空" />
          </div>
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">购方客户</label>
              <CInput v-model="form.buyerName" placeholder="客户姓名" />
            </div>
            <div>
              <label class="form__label">关联订单号</label>
              <CInput v-model="form.orderRefs" placeholder="如 ORD-20260820-01" />
            </div>
          </div>
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">价税合计（元）<span class="req">*</span></label>
              <CInput :model-value="String(form.amount)" @update:model-value="form.amount = Number($event) || 0" placeholder="0" />
            </div>
            <div>
              <label class="form__label">税率</label>
              <CSelect :model-value="String(form.taxRate)" @update:model-value="form.taxRate = Number($event)" width="100%" :options="rateOptions" />
            </div>
          </div>
          <div class="form__calc">预计税额：<b>{{ money(formTax) }}</b>　不含税：<b>{{ money(form.amount - formTax) }}</b></div>
          <p class="form__tip">提交后仅生成发票草稿，实际开具由外部开票系统回传状态，不涉及资金动账。</p>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showCreate = false">取消</CButton>
          <CButton variant="primary" @click="submitCreate">提交草稿</CButton>
        </template>
      </CCard>
    </div>

    <!-- 开具双签弹层 -->
    <div v-if="showIssue" class="modal-mask" @click.self="showIssue = false">
      <CCard class="modal modal--sm" title="开具发票（双签）" padding="lg">
        <div class="form">
          <div class="sign-box">
            <div class="sign-box__title"><CIcon name="shield" :size="16" /> 双签确认</div>
            <div class="sign-box__text">发票：{{ selected?.invoiceNo }}　|　{{ selected?.title }}</div>
            <div class="sign-box__text">价税合计：<b>{{ selected ? money(selected.amount) : '' }}</b></div>
          </div>
          <div class="form__row">
            <label class="form__label">复核人 <span class="req">*</span></label>
            <CInput v-model="issueReviewer" placeholder="请输入复核人姓名，如：苏晴（店长）" />
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showIssue = false">取消</CButton>
          <CButton variant="primary" :disabled="!canIssue" @click="submitIssue">确认开具</CButton>
        </template>
      </CCard>
    </div>

    <!-- 作废弹层 -->
    <div v-if="showVoid" class="modal-mask" @click.self="showVoid = false">
      <CCard class="modal modal--sm" title="作废发票" padding="lg">
        <div class="form">
          <div class="form__row">
            <label class="form__label">作废原因 <span class="req">*</span></label>
            <CInput v-model="voidReason" placeholder="仅当月未抄税可作废" />
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showVoid = false">取消</CButton>
          <CButton variant="danger" :disabled="!voidReason.trim()" @click="submitVoid">确认作废</CButton>
        </template>
      </CCard>
    </div>

    <!-- 红冲双签弹层 -->
    <div v-if="showFlush" class="modal-mask" @click.self="showFlush = false">
      <CCard class="modal" title="红冲审批（双签）" padding="lg">
        <div class="form">
          <div class="sign-box">
            <div class="sign-box__title"><CIcon name="shield" :size="16" /> 双签确认</div>
            <div class="sign-box__text">发票：{{ selected?.invoiceNo }}　|　{{ selected?.title }}</div>
            <div class="sign-box__text sign-box__text--danger">红冲金额：{{ selected ? money(selected.amount) : '' }}（跨月/已抄税需开红字信息表）</div>
          </div>
          <div class="form__row">
            <label class="form__label">复核人 <span class="req">*</span></label>
            <CInput v-model="flushForm.reviewer" placeholder="请输入复核人姓名，如：陈雅琳（财务主管）" />
          </div>
          <div class="form__row">
            <label class="form__label">红冲原因 <span class="req">*</span></label>
            <CInput v-model="flushForm.reason" placeholder="如：发生退款、开票信息有误" />
          </div>
          <p class="form__tip">红冲仅登记红字发票状态，不做真实资金动账；需复核人确认后生效。</p>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showFlush = false">取消</CButton>
          <CButton variant="danger" :disabled="!canFlush" @click="submitFlush">确认红冲</CButton>
        </template>
      </CCard>
    </div>

    <!-- B63 卡4 进项：登记弹层 -->
    <div v-if="showInputCreate" class="modal-mask" @click.self="showInputCreate = false">
      <CCard class="modal" title="登记进项发票" padding="lg">
        <div class="form">
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">扣税凭证种类 <span class="req">*</span></label>
              <CSelect v-model="inputForm.invoiceKind" width="100%" :options="inputStore.KIND_OPTIONS" />
            </div>
            <div>
              <label class="form__label">采购用途</label>
              <CSelect v-model="inputForm.category" width="100%" :options="inputStore.CATEGORY_OPTIONS" />
            </div>
          </div>
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">开票方名称 <span class="req">*</span></label>
              <CInput v-model="inputForm.sellerName" placeholder="销方企业全称" />
            </div>
            <div>
              <label class="form__label">销方纳税人识别号 <span class="req">*</span></label>
              <CInput v-model="inputForm.sellerTaxNo" placeholder="统一社会信用代码/税号" />
            </div>
          </div>
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">发票号码 <span class="req">*</span></label>
              <CInput v-model="inputForm.invoiceNo" placeholder="发票号码" />
            </div>
            <div>
              <label class="form__label">发票代码</label>
              <CInput v-model="inputForm.invoiceCode" placeholder="选填" />
            </div>
          </div>
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">开票日期 <span class="req">*</span></label>
              <CInput v-model="inputForm.invoiceDate" placeholder="yyyy-MM-dd" />
            </div>
            <div>
              <label class="form__label">归属门店 <span class="req">*</span></label>
              <CSelect v-model="inputForm.store" width="100%" :options="inputStoreOptions" />
            </div>
          </div>
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">价税合计（元）<span class="req">*</span></label>
              <CInput :model-value="String(inputForm.amount)" @update:model-value="inputForm.amount = Number($event) || 0" placeholder="0" />
            </div>
            <div>
              <label class="form__label">税率</label>
              <CSelect :model-value="String(inputForm.taxRate)" @update:model-value="inputForm.taxRate = Number($event)" width="100%" :options="inputRateOptions" />
            </div>
          </div>
          <div class="form__calc">预计税额：<b>{{ money(inputFormTax) }}</b>　不含税：<b>{{ money(Math.max(0, inputForm.amount - inputFormTax)) }}</b></div>
          <div class="form__row">
            <label class="form__label">备注</label>
            <CInput v-model="inputForm.remark" placeholder="选填" />
          </div>
          <p class="form__tip">税额由服务端按五档税率价税分离，登记后进入「待确认」，需再做用途确认方可申报抵扣。</p>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showInputCreate = false">取消</CButton>
          <CButton variant="primary" :disabled="!canSubmitInputCreate" @click="submitInputCreate">提交登记</CButton>
        </template>
      </CCard>
    </div>

    <!-- B63 卡4 进项：用途确认弹层 -->
    <div v-if="showInputConfirm" class="modal-mask" @click.self="showInputConfirm = false">
      <CCard class="modal modal--sm" title="进项用途确认" padding="lg">
        <div class="form">
          <div class="sign-box">
            <div class="sign-box__title"><CIcon name="shield" :size="16" /> 用途确认</div>
            <div class="sign-box__text">登记号：{{ inputSelected?.registerNo }}　|　{{ inputSelected?.sellerName }}</div>
            <div class="sign-box__text">税额：<b>{{ inputSelected ? money(inputSelected.taxAmount) : '' }}</b></div>
          </div>
          <div class="form__row">
            <label class="form__label">用途 <span class="req">*</span></label>
            <CSelect v-model="confirmPurpose" width="100%" :options="inputStore.PURPOSE_OPTIONS" />
          </div>
          <div v-if="confirmPurpose === 'NO_DEDUCT'" class="form__row">
            <label class="form__label">不抵扣原因（七码）<span class="req">*</span></label>
            <CSelect v-model="confirmReason" width="100%" :options="inputReasonOptions" />
          </div>
          <div class="form__row">
            <label class="form__label">备注</label>
            <CInput v-model="confirmRemark" placeholder="选填" />
          </div>
          <p v-if="confirmPurpose === 'REFUND'" class="form__tip">退税勾选只确认用途，不在本卡办理抵扣，请走退税申报流程。</p>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showInputConfirm = false">取消</CButton>
          <CButton variant="primary" @click="submitInputConfirm">确认</CButton>
        </template>
      </CCard>
    </div>

    <!-- B63 卡4 进项：进项转出弹层 -->
    <div v-if="showTransferOut" class="modal-mask" @click.self="showTransferOut = false">
      <CCard class="modal modal--sm" title="进项转出" padding="lg">
        <div class="form">
          <div class="sign-box">
            <div class="sign-box__title"><CIcon name="alert" :size="16" /> 转出后为终态</div>
            <div class="sign-box__text">登记号：{{ inputSelected?.registerNo }}</div>
            <div class="sign-box__text sign-box__text--danger">票面税额：{{ inputSelected ? money(inputSelected.taxAmount) : '' }}（转出额不得超过）</div>
          </div>
          <div class="form__row">
            <label class="form__label">进项转出额（元）<span class="req">*</span></label>
            <CInput :model-value="String(transferAmount)" @update:model-value="transferAmount = Number($event) || 0" placeholder="0" />
          </div>
          <div class="form__row">
            <label class="form__label">转出原因（七码）<span class="req">*</span></label>
            <CSelect v-model="transferReason" width="100%" :options="inputReasonOptions" />
          </div>
          <div class="form__row">
            <label class="form__label">备注</label>
            <CInput v-model="transferRemark" placeholder="选填" />
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showTransferOut = false">取消</CButton>
          <CButton variant="danger" :disabled="!canSubmitTransfer" @click="submitTransferOut">确认转出</CButton>
        </template>
      </CCard>
    </div>

    <!-- B63 卡4 进项：标记不抵扣弹层 -->
    <div v-if="showNonDeduct" class="modal-mask" @click.self="showNonDeduct = false">
      <CCard class="modal modal--sm" title="标记不抵扣" padding="lg">
        <div class="form">
          <div class="sign-box">
            <div class="sign-box__title"><CIcon name="shield" :size="16" /> 终态操作</div>
            <div class="sign-box__text">登记号：{{ inputSelected?.registerNo }}　税额：{{ inputSelected ? money(inputSelected.taxAmount) : '' }}</div>
          </div>
          <div class="form__row">
            <label class="form__label">不抵扣原因（七码）<span class="req">*</span></label>
            <CSelect v-model="nonDeductReason" width="100%" :options="inputReasonOptions" />
          </div>
          <div class="form__row">
            <label class="form__label">备注</label>
            <CInput v-model="nonDeductRemark" placeholder="选填" />
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showNonDeduct = false">取消</CButton>
          <CButton variant="danger" @click="submitNonDeduct">确认不抵扣</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.inv { display: flex; flex-direction: column; gap: var(--s-lg); }
.inv__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .inv__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }

.inv__body { display: grid; grid-template-columns: 380px 1fr; gap: var(--s-lg); align-items: start; }
.inv__list { min-width: 0; }
.filters { display: flex; gap: var(--s-sm); padding: var(--s-md); border-bottom: 1px solid var(--c-border-light); }
.list { max-height: 640px; overflow-y: auto; }
.empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); font-size: var(--t-sm); }
.empty__icon { color: var(--c-text-4); }

.row {
  display: block; width: 100%; text-align: left; padding: var(--s-md) var(--s-lg);
  background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer;
}
.row:hover { background: var(--c-brand-soft); }
.row--active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.row__top { display: flex; align-items: center; justify-content: space-between; gap: var(--s-sm); margin-bottom: 4px; }
.row__no { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.row__title { font-size: var(--t-sm); color: var(--c-text-2); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.row__sub { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }
.row__bottom { display: flex; align-items: center; justify-content: space-between; margin-top: var(--s-xs); }
.row__amt { font-size: var(--t-sm); font-weight: 700; color: var(--c-text); font-variant-numeric: tabular-nums; }
.row__date { font-size: var(--t-xs); color: var(--c-text-3); }

.inv__detail-head { display: flex; align-items: center; gap: var(--s-md); width: 100%; }
.inv__who { flex: 1; min-width: 0; }
.inv__no { font-size: var(--t-lg); font-weight: 700; margin: 0; }
.inv__sub { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }

.detail-body { padding: var(--s-lg); display: flex; flex-direction: column; gap: var(--s-lg); }
.stat-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); }
.stat { background: var(--c-bg-right); border-radius: var(--r-md); padding: var(--s-md); }
.stat__label { font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: 4px; }
.stat__value { font-size: var(--t-md); font-weight: 700; color: var(--c-text); font-variant-numeric: tabular-nums; }
.stat__value--brand { color: var(--c-brand); }

.kv { display: flex; flex-direction: column; gap: var(--s-sm); background: var(--c-bg-right); border-radius: var(--r-md); padding: var(--s-md); }
.kv__row { display: flex; justify-content: space-between; gap: var(--s-md); font-size: var(--t-sm); }
.kv__k { color: var(--c-text-3); flex-shrink: 0; }
.kv__v { color: var(--c-text-2); font-variant-numeric: tabular-nums; text-align: right; word-break: break-all; }
.kv__v--danger { color: var(--c-danger-fg); }

.block { display: flex; flex-direction: column; gap: var(--s-sm); }
.block__title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.taxrows { display: flex; flex-direction: column; gap: var(--s-xs); }
.taxrow { display: flex; align-items: center; gap: var(--s-md); padding: var(--s-sm) var(--s-md); background: var(--c-bg-right); border-radius: var(--r-sm); font-size: var(--t-sm); }
.taxrow__rate { width: 56px; color: var(--c-brand); font-weight: 600; flex-shrink: 0; }
.taxrow__amt { flex: 1; color: var(--c-text); font-weight: 600; font-variant-numeric: tabular-nums; }
.taxrow__tax { font-size: var(--t-xs); color: var(--c-text-3); font-variant-numeric: tabular-nums; }
.taxrow--empty { color: var(--c-text-3); justify-content: center; }

.ops { display: flex; align-items: center; gap: var(--s-sm); flex-wrap: wrap; }
.ops__hint { font-size: var(--t-xs); color: var(--c-text-3); }
.ops__hint--danger { color: var(--c-danger-fg); font-weight: 600; }

.detail-empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-md); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); }
.detail-empty__icon { color: var(--c-text-4); }

.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 520px; max-width: 100%; box-shadow: var(--shadow-pop); }
.modal--sm { width: 400px; }
.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.form__row--2 { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.form__label { font-size: var(--t-xs); color: var(--c-text-3); }
.req { color: var(--c-danger-fg); }
.form__calc { font-size: var(--t-sm); color: var(--c-text-2); background: var(--c-bg-right); border-radius: var(--r-sm); padding: var(--s-sm) var(--s-md); }
.form__tip { font-size: var(--t-xs); color: var(--c-text-3); background: var(--c-bg-right); border-radius: var(--r-sm); padding: var(--s-sm); margin: 0; }
.sign-box { background: var(--c-warning-bg); border: 1px solid var(--c-border-light); border-radius: var(--r-md); padding: var(--s-md); display: flex; flex-direction: column; gap: 4px; }
.sign-box__title { display: flex; align-items: center; gap: var(--s-xs); font-size: var(--t-sm); font-weight: 600; color: var(--c-warning-fg); }
.sign-box__text { font-size: var(--t-sm); color: var(--c-text-2); }
.sign-box__text--danger { color: var(--c-danger-fg); font-weight: 600; }

@media (max-width: 1024px) {
  .inv__body { grid-template-columns: 1fr; }
  .inv__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .stat-grid { grid-template-columns: repeat(2, 1fr); }
  .form__row--2 { grid-template-columns: 1fr; }
  .list { max-height: 360px; }
}
</style>
