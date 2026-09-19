// ============================================================
// finInputInvoice —— B63 卡4 L86 进项税抵扣链路
// 登记（UNCONFIRMED）→ 用途确认（DEDUCT/NO_DEDUCT/REFUND）→ 抵扣（OPEN 期）→ 进项转出。
// 与销项 finInvoice 不同：后端分页（Page JSON），写操作的中文 422/409 必须直达用户，
// 本 store 只在权限缺失时短路，业务错误一律 throw 交视图 toast。
// ============================================================
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { useStoreContext } from './storeContext'
import {
  listInputInvoices,
  registerInputInvoice as apiRegister,
  confirmInputInvoice as apiConfirm,
  revokeConfirmInputInvoice as apiRevoke,
  deductInputInvoice as apiDeduct,
  transferOutInputInvoice as apiTransferOut,
  markInputInvoiceNonDeductible as apiNonDeductible,
  getInputInvoiceSummary,
  getCurrentTaxPeriod,
  ensureTaxPeriod,
  type InputInvoiceDTO,
  type InputInvoiceKind,
  type InputInvoiceCategory,
  type InputInvoicePurpose,
  type InputInvoiceStatus,
  type InputNondeductReason,
  type InputInvoiceRegisterCmd,
  type InputInvoiceSummary,
  type TaxPeriodDTO,
  type TaxPeriodType,
} from '@/api/finance'

export type {
  InputInvoiceDTO,
  InputInvoiceKind,
  InputInvoiceCategory,
  InputInvoicePurpose,
  InputInvoiceStatus,
  InputNondeductReason,
  InputInvoiceSummary,
  TaxPeriodDTO,
  TaxPeriodType,
}

const KIND_LABEL: Record<InputInvoiceKind, string> = {
  SPECIAL: '增值税专用发票',
  CUSTOMS: '海关进口增值税专用缴款书',
  TOLL: '通行费电子普通发票',
  PASSENGER: '旅客运输凭证',
  OTHER: '其他扣税凭证',
}
const CATEGORY_LABEL: Record<InputInvoiceCategory, string> = {
  SERVICE: '服务',
  PRODUCT: '商品',
  MEMBERSHIP: '会员',
}
const PURPOSE_LABEL: Record<InputInvoicePurpose, string> = {
  PENDING: '待确认',
  DEDUCT: '抵扣',
  NO_DEDUCT: '不抵扣',
  REFUND: '退税',
}
const STATUS_LABEL: Record<InputInvoiceStatus, string> = {
  UNCONFIRMED: '待确认',
  CONFIRMED: '已用途确认',
  DEDUCTED: '已抵扣',
  TRANSFERRED_OUT: '已进项转出',
  NON_DEDUCTIBLE: '不抵扣',
}
const STATUS_PILL: Record<InputInvoiceStatus, 'draft' | 'primary' | 'success' | 'warning' | 'disabled'> = {
  UNCONFIRMED: 'draft',
  CONFIRMED: 'primary',
  DEDUCTED: 'success',
  TRANSFERRED_OUT: 'warning',
  NON_DEDUCTIBLE: 'disabled',
}
const REASON_LABEL: Record<InputNondeductReason, string> = {
  WELFARE: '简易计税/免税/集体福利/个人消费',
  LOSS_GOODS: '非正常损失购进货物及相关劳务/运输',
  LOSS_PRODUCT: '非正常损失在产品/产成品耗用购进',
  LOSS_REAL_ESTATE: '非正常损失不动产及所耗购进/设计/建筑',
  LOSS_CONSTRUCTION: '非正常损失不动产在建工程所耗',
  LOAN_DAILY: '贷款/餐饮/居民日常/娱乐服务',
  OTHER: '其他依法不得抵扣情形',
}

const RATES = [0, 0.01, 0.03, 0.06, 0.13] as const
const KIND_OPTIONS = (Object.keys(KIND_LABEL) as InputInvoiceKind[]).map((k) => ({ value: k, label: KIND_LABEL[k] }))
const CATEGORY_OPTIONS = (Object.keys(CATEGORY_LABEL) as InputInvoiceCategory[]).map((k) => ({ value: k, label: CATEGORY_LABEL[k] }))
const PURPOSE_OPTIONS: { value: InputInvoicePurpose; label: string }[] = [
  { value: 'DEDUCT', label: PURPOSE_LABEL.DEDUCT },
  { value: 'NO_DEDUCT', label: PURPOSE_LABEL.NO_DEDUCT },
  { value: 'REFUND', label: PURPOSE_LABEL.REFUND },
]
const REASON_OPTIONS = (Object.keys(REASON_LABEL) as InputNondeductReason[]).map((k) => ({ value: k, label: REASON_LABEL[k] }))
const STATUS_OPTIONS = (Object.keys(STATUS_LABEL) as InputInvoiceStatus[]).map((k) => ({ value: k, label: STATUS_LABEL[k] }))

export interface RegisterForm {
  invoiceKind: InputInvoiceKind
  category: InputInvoiceCategory
  sellerName: string
  sellerTaxNo: string
  invoiceNo: string
  invoiceCode?: string
  supplierId?: number | null
  store: string
  invoiceDate: string
  amount: number
  taxRate: number
  remark?: string
}

export const useFinInputInvoiceStore = defineStore('finInputInvoice', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()
  const storeCtx = useStoreContext()

  const items = ref<InputInvoiceDTO[]>([])
  const total = ref(0)
  const page = ref(0)
  const size = ref(20)
  const loading = ref(false)
  const filterStatus = ref<InputInvoiceStatus | ''>('')
  const filterKind = ref<InputInvoiceKind | ''>('')
  const filterPurpose = ref<InputInvoicePurpose | ''>('')
  const filterPeriodId = ref<number | null>(null)
  const keyword = ref('')

  function get(id: number) {
    return items.value.find((i) => i.id === id)
  }

  /** 视图传入中文店名，按门店列表反查编码；查不到回落当前上下文门店 */
  function resolveStoreCode(nameOrCode: string): string {
    const hit = storeCtx.stores.find((s) => s.storeName === nameOrCode || s.storeCode === nameOrCode)
    return hit?.storeCode || storeCtx.currentStoreCode
  }

  async function loadList(targetPage?: number) {
    if (!auth.can('finance:input:view')) {
      console.warn('[finInputInvoice] 无 finance:input:view 权限')
      return
    }
    if (targetPage !== undefined) page.value = targetPage
    loading.value = true
    try {
      await storeCtx.loadStores()
      const { data } = await listInputInvoices({
        status: filterStatus.value || undefined,
        invoiceKind: filterKind.value || undefined,
        purpose: filterPurpose.value || undefined,
        periodId: filterPeriodId.value ?? undefined,
        keyword: keyword.value.trim() || undefined,
        page: page.value,
        size: size.value,
      })
      items.value = data.content
      total.value = data.totalElements
    } finally {
      loading.value = false
    }
  }

  function resetFilters() {
    filterStatus.value = ''
    filterKind.value = ''
    filterPurpose.value = ''
    filterPeriodId.value = null
    keyword.value = ''
    void loadList(0)
  }

  /** 登记进项发票（服务端价税分离；成功后刷新当前页并返回新票） */
  async function register(form: RegisterForm): Promise<InputInvoiceDTO | null> {
    if (!auth.can('finance:input:edit')) {
      console.warn('[finInputInvoice] 无 finance:input:edit 权限')
      return null
    }
    const cmd: InputInvoiceRegisterCmd = {
      idemKey: `WEB-${Date.now()}-${Math.round(form.amount * 100)}`,
      invoiceKind: form.invoiceKind,
      category: form.category,
      sellerName: form.sellerName.trim(),
      sellerTaxNo: form.sellerTaxNo.trim(),
      invoiceNo: form.invoiceNo.trim(),
      invoiceCode: form.invoiceCode?.trim() || null,
      supplierId: form.supplierId ?? null,
      storeCode: resolveStoreCode(form.store),
      invoiceDate: form.invoiceDate,
      amount: form.amount,
      taxRate: form.taxRate,
      remark: form.remark?.trim() || null,
    }
    const { data } = await apiRegister(cmd)
    activity.log(auth.user.name, `登记进项发票 ${data.registerNo}：${data.sellerName}，税额 ¥${data.taxAmount}`, String(data.id))
    await loadList(page.value)
    return data
  }

  /** 用途确认：DEDUCT/REFUND → CONFIRMED；NO_DEDUCT → NON_DEDUCTIBLE（须七码原因） */
  async function confirm(
    id: number,
    purpose: 'DEDUCT' | 'NO_DEDUCT' | 'REFUND',
    reason?: InputNondeductReason,
    remark?: string,
  ): Promise<boolean> {
    if (!auth.can('finance:input:confirm')) return false
    const { data } = await apiConfirm(id, { purpose, reason, remark: remark?.trim() || undefined })
    patchItem(data)
    activity.log(auth.user.name, `进项发票 ${data.registerNo} 用途确认：${PURPOSE_LABEL[purpose]}`, String(id))
    return true
  }

  /** 撤销用途确认（仅 CONFIRMED 可回；已抵扣/已转出后端 422 中文） */
  async function revokeConfirm(id: number): Promise<boolean> {
    if (!auth.can('finance:input:confirm')) return false
    const { data } = await apiRevoke(id)
    patchItem(data)
    activity.log(auth.user.name, `进项发票 ${data.registerNo} 撤销用途确认`, String(id))
    return true
  }

  /** 申报抵扣（periodId 缺省由后端懒创建当前月 OPEN 期；期已申报则 422 中文） */
  async function deduct(id: number, periodId?: number | null): Promise<boolean> {
    if (!auth.can('finance:input:confirm')) return false
    const { data } = await apiDeduct(id, periodId ?? null)
    patchItem(data)
    activity.log(auth.user.name, `进项发票 ${data.registerNo} 已申报抵扣，税额 ¥${data.taxAmount}`, String(id))
    return true
  }

  /** 进项转出（DEDUCTED 终态；金额 1..票面税额，七码原因必填） */
  async function transferOut(id: number, amount: number, reason: InputNondeductReason, remark?: string): Promise<boolean> {
    if (!auth.can('finance:input:confirm')) return false
    const { data } = await apiTransferOut(id, { amount, reason, remark: remark?.trim() || undefined })
    patchItem(data)
    activity.log(auth.user.name, `进项发票 ${data.registerNo} 进项转出 ¥${amount}：${REASON_LABEL[reason]}`, String(id))
    return true
  }

  /** 标记不抵扣（UNCONFIRMED → NON_DEDUCTIBLE 终态旁路，edit 权限，七码原因） */
  async function markNonDeductible(id: number, reason: InputNondeductReason, remark?: string): Promise<boolean> {
    if (!auth.can('finance:input:edit')) return false
    const { data } = await apiNonDeductible(id, { reason, remark: remark?.trim() || undefined })
    patchItem(data)
    activity.log(auth.user.name, `进项发票 ${data.registerNo} 标记不抵扣：${REASON_LABEL[reason]}`, String(id))
    return true
  }

  /** 抵扣汇总（元）；periodId 优先，其次 type＋period，缺省当前月开放期；未登记返零金额骨架 */
  async function fetchSummary(params: { periodId?: number; type?: TaxPeriodType; period?: string } = {}): Promise<InputInvoiceSummary | null> {
    if (!auth.can('finance:tax:view')) return null
    const { data } = await getInputInvoiceSummary(params)
    return data
  }

  /** 当前开放期＋五金额（未登记 exists=false 骨架，不抛 404） */
  async function fetchCurrentPeriod(type: TaxPeriodType = 'MONTH'): Promise<TaxPeriodDTO | null> {
    if (!auth.can('finance:tax:view')) return null
    const { data } = await getCurrentTaxPeriod(type)
    return data
  }

  /** 幂等确保期间存在（edit 权限；periodType/period 缺省当前月期） */
  async function ensurePeriod(params: { periodType?: TaxPeriodType; period?: string } = {}): Promise<TaxPeriodDTO | null> {
    if (!auth.can('finance:input:edit')) return null
    const { data } = await ensureTaxPeriod(params)
    return data
  }

  function patchItem(d: InputInvoiceDTO) {
    const idx = items.value.findIndex((i) => i.id === d.id)
    if (idx >= 0) items.value[idx] = d
  }

  return {
    items, total, page, size, loading,
    filterStatus, filterKind, filterPurpose, filterPeriodId, keyword,
    get, loadList, resetFilters,
    register, confirm, revokeConfirm, deduct, transferOut, markNonDeductible,
    fetchSummary, fetchCurrentPeriod, ensurePeriod,
    KIND_LABEL, CATEGORY_LABEL, PURPOSE_LABEL, STATUS_LABEL, STATUS_PILL, REASON_LABEL,
    KIND_OPTIONS, CATEGORY_OPTIONS, PURPOSE_OPTIONS, REASON_OPTIONS, STATUS_OPTIONS, RATES,
  }
})
