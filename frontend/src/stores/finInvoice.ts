// ============================================================
// finInvoice —— M6-04 发票管理
// 业财一体红线：发票仅作为"业务凭证登记/镜像"，开票动作由外部开票系统完成，
// 本 store 只登记发票抬头、税额、关联订单、状态，并与 financeCore 流水勾稽。
// 不直接触达资金池。
// B5：持久化到 finance-service /finance/invoices（票号/税额服务端生成，
// 状态机 DRAFT→ISSUED→VOIDED/RED_FLUSHED，idemKey 幂等，全审计）。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { useStoreContext } from './storeContext'
import {
  getInvoices,
  createInvoice as apiCreateInvoice,
  issueInvoice as apiIssueInvoice,
  voidInvoice as apiVoidInvoice,
  redFlushInvoice as apiRedFlushInvoice,
  type InvoiceDTO,
} from '@/api/finance'

export type InvoiceType = 'NORMAL' | 'SPECIAL' | 'ELECTRONIC'
export type InvoiceStatus = 'DRAFT' | 'ISSUED' | 'VOIDED' | 'RED_FLUSHED'
export type InvoiceCategory = 'SERVICE' | 'PRODUCT' | 'MEMBERSHIP'

export interface InvoiceItem {
  id: string
  invoiceNo: string           // 发票号码
  type: InvoiceType           // 票种
  category: InvoiceCategory   // 项目类别
  title: string               // 抬头
  taxNo: string               // 税号
  amount: number              // 价税合计（元）
  taxAmount: number           // 税额
  taxRate: number             // 税率 0~1
  buyerName: string           // 购方客户
  orderRefs: string[]         // 关联订单号
  store: string
  status: InvoiceStatus
  issuedAt: string            // 开票时间
  operator: string
  reviewer?: string
  remark?: string
}

const TYPE_LABEL: Record<InvoiceType, string> = {
  NORMAL: '增值税普通发票',
  SPECIAL: '增值税专用发票',
  ELECTRONIC: '增值税电子普通发票',
}
const CATEGORY_LABEL: Record<InvoiceCategory, string> = {
  SERVICE: '医疗服务',
  PRODUCT: '产品销售',
  MEMBERSHIP: '会员卡/疗程',
}
const STATUS_LABEL: Record<InvoiceStatus, string> = {
  DRAFT: '待开票',
  ISSUED: '已开票',
  VOIDED: '已作废',
  RED_FLUSHED: '已红冲',
}
const STATUS_PILL: Record<InvoiceStatus, 'primary' | 'success' | 'disabled' | 'warning'> = {
  DRAFT: 'primary',
  ISSUED: 'success',
  VOIDED: 'disabled',
  RED_FLUSHED: 'warning',
}

const RATES = [0, 0.01, 0.03, 0.06, 0.13] as const

function dtoToItem(d: InvoiceDTO): InvoiceItem {
  return {
    id: String(d.id),
    invoiceNo: d.invoiceNo,
    type: d.type,
    category: d.category,
    title: d.title,
    taxNo: d.taxNo ?? '',
    amount: d.amount,
    taxAmount: d.taxAmount,
    taxRate: d.taxRate,
    buyerName: d.buyerName ?? '',
    orderRefs: d.orderRefs ?? [],
    store: d.store || d.storeCode,
    status: d.status,
    issuedAt: d.issuedAt ?? '',
    operator: d.operator ?? '',
    reviewer: d.reviewer ?? undefined,
    remark: d.remark ?? undefined,
  }
}

export const useFinInvoiceStore = defineStore('finInvoice', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()
  const storeCtx = useStoreContext()

  const items = ref<InvoiceItem[]>([])
  const filterStatus = ref<InvoiceStatus | 'ALL'>('ALL')
  const filterType = ref<InvoiceType | 'ALL'>('ALL')
  const keyword = ref('')
  const loaded = ref(false)

  const issued = computed(() => items.value.filter((i) => i.status === 'ISSUED'))
  const drafts = computed(() => items.value.filter((i) => i.status === 'DRAFT'))
  const voided = computed(() => items.value.filter((i) => i.status === 'VOIDED' || i.status === 'RED_FLUSHED'))

  /** 本月已开票价税合计 */
  const monthIssuedAmount = computed(() => {
    const now = new Date()
    return issued.value
      .filter((i) => {
        const d = new Date(i.issuedAt)
        return d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth()
      })
      .reduce((s, i) => s + i.amount, 0)
  })
  /** 本月税额合计 */
  const monthTaxAmount = computed(() => {
    const now = new Date()
    return issued.value
      .filter((i) => {
        const d = new Date(i.issuedAt)
        return d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth()
      })
      .reduce((s, i) => s + i.taxAmount, 0)
  })

  const filtered = computed(() => {
    let list = items.value
    if (filterStatus.value !== 'ALL') list = list.filter((i) => i.status === filterStatus.value)
    if (filterType.value !== 'ALL') list = list.filter((i) => i.type === filterType.value)
    const kw = keyword.value.trim().toLowerCase()
    if (kw) {
      list = list.filter(
        (i) =>
          i.invoiceNo.toLowerCase().includes(kw) ||
          i.title.toLowerCase().includes(kw) ||
          i.buyerName.toLowerCase().includes(kw) ||
          i.taxNo.toLowerCase().includes(kw),
      )
    }
    return [...list].sort((a, b) => {
      const ta = a.issuedAt ? Date.parse(a.issuedAt) : 0
      const tb = b.issuedAt ? Date.parse(b.issuedAt) : 0
      return tb - ta
    })
  })

  function get(id: string) {
    return items.value.find((i) => i.id === id)
  }

  /** 视图传入中文店名，按门店列表反查编码；查不到回落当前上下文门店 */
  function resolveStoreCode(nameOrCode: string): string {
    const hit = storeCtx.stores.find((s) => s.storeName === nameOrCode || s.storeCode === nameOrCode)
    return hit?.storeCode || storeCtx.currentStoreCode
  }

  async function fetchInvoices() {
    const { data } = await getInvoices()
    items.value = (data ?? []).map(dtoToItem)
    loaded.value = true
  }

  /** 新建发票草稿（票号/税额由服务端生成，idemKey 防重复提交） */
  async function create(
    input: Omit<InvoiceItem, 'id' | 'invoiceNo' | 'status' | 'operator' | 'issuedAt'>,
  ): Promise<InvoiceItem | null> {
    if (!auth.can('finance:invoice:edit')) {
      console.warn('[finInvoice] 无 finance:invoice:edit 权限')
      return null
    }
    try {
      const { data } = await apiCreateInvoice({
        type: input.type,
        category: input.category,
        title: input.title,
        taxNo: input.taxNo || undefined,
        amount: input.amount,
        taxRate: input.taxRate,
        buyerName: input.buyerName,
        orderRefs: input.orderRefs,
        storeCode: resolveStoreCode(input.store),
        idemKey: `WEB-${Date.now()}-${Math.round(input.amount * 100)}`,
      })
      const inv = dtoToItem(data)
      items.value.unshift(inv)
      activity.log(auth.user.name, `创建发票草稿 ${inv.invoiceNo}：${inv.title}`, inv.id)
      return inv
    } catch (e) {
      console.error('[finInvoice] 创建发票草稿失败', e)
      return null
    }
  }

  /** 标记已开票（模拟外部开票系统回传） */
  async function markIssued(id: string, reviewer: string): Promise<boolean> {
    if (!auth.can('finance:invoice:edit')) return false
    try {
      const { data } = await apiIssueInvoice(Number(id), reviewer.trim() || undefined)
      const idx = items.value.findIndex((i) => i.id === id)
      if (idx >= 0) items.value[idx] = dtoToItem(data)
      activity.log(auth.user.name, `发票 ${data.invoiceNo} 已开具，价税合计 ¥${data.amount}`, id)
      return true
    } catch (e) {
      console.error('[finInvoice] 开具失败', e)
      return false
    }
  }

  /** 作废（仅当月未抄税可作废） */
  async function voidInvoice(id: string, reason: string): Promise<boolean> {
    if (!auth.can('finance:invoice:edit')) return false
    try {
      const { data } = await apiVoidInvoice(Number(id), reason)
      const idx = items.value.findIndex((i) => i.id === id)
      if (idx >= 0) items.value[idx] = dtoToItem(data)
      activity.log(auth.user.name, `发票 ${data.invoiceNo} 作废：${reason}`, id)
      return true
    } catch (e) {
      console.error('[finInvoice] 作废失败', e)
      return false
    }
  }

  /** 红冲（跨月或已抄税） */
  async function redFlush(id: string, reason: string): Promise<boolean> {
    if (!auth.can('finance:invoice:approve')) return false
    try {
      const { data } = await apiRedFlushInvoice(Number(id), reason)
      const idx = items.value.findIndex((i) => i.id === id)
      if (idx >= 0) items.value[idx] = dtoToItem(data)
      activity.log(auth.user.name, `发票 ${data.invoiceNo} 红冲：${reason}`, id)
      return true
    } catch (e) {
      console.error('[finInvoice] 红冲失败', e)
      return false
    }
  }

  /** 按税率统计 */
  const taxBreakdown = computed(() => {
    const map = new Map<number, { rate: number; amount: number; tax: number }>()
    for (const it of issued.value) {
      const row = map.get(it.taxRate) ?? { rate: it.taxRate, amount: 0, tax: 0 }
      row.amount += it.amount
      row.tax += it.taxAmount
      map.set(it.taxRate, row)
    }
    return [...map.values()].sort((a, b) => a.rate - b.rate)
  })

  // ===== 加载：只拉真实发票数据；服务不可达时保持诚实空态，不注入演示数据（B24 卡1 去 mock） =====
  let seeding: Promise<void> | null = null
  function seed(force = false): Promise<void> {
    if (seeding && !force) return seeding
    if (loaded && !force) return Promise.resolve()
    seeding = (async () => {
      try {
        await storeCtx.loadStores()
        await fetchInvoices()
      } catch (e) {
        // 不造任何假发票：列表保持空态，loaded 留 false 以便重试 seed(true)
        console.error('[finInvoice] 加载发票失败，保持空态', e)
      } finally {
        seeding = null
      }
    })()
    return seeding
  }
  void seed()

  return {
    items, filterStatus, filterType, keyword, loaded,
    issued, drafts, voided, monthIssuedAmount, monthTaxAmount, filtered, taxBreakdown,
    get, create, markIssued, voidInvoice, redFlush, seed,
    TYPE_LABEL, CATEGORY_LABEL, STATUS_LABEL, STATUS_PILL, RATES,
  }
})
