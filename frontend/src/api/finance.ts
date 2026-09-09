// ============================================================
// Finance API（对接 finance-service）
// 读：只读聚合视图；写：B3 起对账台账人工写（标记一致/差异/调平，全审计）
// ============================================================
import client from './client'

export interface PrepayPool {
  total: number
  pendingConsume: number
  refundable: number
  earnedPending: number
}

export interface Tax {
  cat: string
  base: number
  rate: number
  amount: number
}

export interface AccountMirror {
  acctId: string
  acctName: string
  balance: number
  acctType: string
}

export interface OutboxRecord {
  outboxId: number
  bizType: string
  txnNo: string
  amount: number // 分（退款为负数）
  channel: string
  /** 后端状态机：PENDING 待对账 / RECONCILED 已对账 / DIFF 差异 / ADJUSTED 已调平 */
  status: string
  createdAt?: string // ISO-8601
  reconciledAt?: string | null
}

/** 人工调平入参（direction 借贷方向、amountFen 调平分（正数）、科目/渠道/备注） */
export interface AdjustOutboxCmd {
  direction: 'IN' | 'OUT'
  amountFen: number
  subject: string
  channel?: string | null
  memo?: string
}

export interface RevenueMonthly {
  storeCode: string
  periodMonth: string // yyyy-MM-01
  revenue: number // 分
  cost: number
  grossProfit: number
  costRate: number
  grossRate: number
}

// ============================================================
// 读时聚合（P3 第一批）：台账流水 / 会员卡余额
// 后端 finance-service 跨 txn/customer/store 聚合，金额已换算为「元」
// ============================================================

/** 台账流水（对齐 financeCore.LedgerEntry，金额单位「元」） */
export interface LedgerEntryDTO {
  id: string
  txnId: string
  date: string // yyyy-MM-dd
  subject: string // RF-REVENUE / RF-REFUND / RF-DEPOSIT / ...
  direction: 'IN' | 'OUT'
  amount: number // 元
  channel?: string | null
  source: string // CASHIER / ERP
  refType: string // ORDER / REFUND / WRITEOFF
  refNo: string
  store: string // 中文店名
  memo: string
  reconciled: boolean
  /** 混合支付标记（一单多渠道收款，channel 为入账额最大一笔的主渠道） */
  mixed?: boolean | null
}

/** 会员卡余额行（对齐 finReports.MemberCard，金额单位「元」） */
export interface CardBalanceDTO {
  id: string
  cardNo: string
  customerName: string
  type: 'STORED' | 'TIMES' | 'GIFT'
  balance: number // 储值余额（元）
  giftBalance: number // 赠送金（元，无数据源投影 0）
  timesTotal: number
  timesRemain: number
  lastConsumeAt: string // 回落开卡时间 yyyy-MM-dd
  status: 'NORMAL' | 'DORMANT' | 'FROZEN'
  store: string // 中文店名
}

export interface CardBalanceBundle {
  cards: CardBalanceDTO[]
  storedTotal: number // 储值合计（元）
  giftTotal: number // 赠送金合计（元）
  timesValueTotal: number // 疗程估值合计（元）
}

// ============================================================
// B24 卡2：单卡流水时间线 + 核销双签明细（金额单位「元」）
// ============================================================

/** 单条卡流水（customer card_ledger 投影；RECHARGE/CONSUME/REFUND/ADJUST，无独立 FREEZE） */
export interface CardTxnDTO {
  ledgerId: number
  kind: 'RECHARGE' | 'CONSUME' | 'REFUND' | 'ADJUST' | string
  amount: number // 变动额（元，正充值/负消费）
  balanceAfter: number // 变动后储值（元）
  giftAmount: number // 赠送金变动（元）
  giftAfter: number // 变动后赠送金（元）
  refNo: string // 业务单号（RC/OD/WO/RF/CC 前缀）
  orderNo: string
  operator: string
  date: string // yyyy-MM-dd HH:mm:ss（上海时区）
}

/** 单卡时间线（卡快照 + 全量流水） */
export interface CardTimelineDTO {
  cardNo: string
  customerId: string
  customerName: string
  cardItem: string
  storeCode: string
  store: string
  cardType: string
  productCode: string
  type: 'TIMES' | 'STORED'
  balance: number
  giftBalance: number
  timesTotal: number
  timesRemain: number
  status: 'NORMAL' | 'DORMANT' | 'FROZEN'
  txns: CardTxnDTO[]
}

/** 核销双签明细行（txn writeoff_record 投影） */
export interface WriteoffDetailDTO {
  writeoffId: string
  orderNo: string
  cardNo: string // 整单核销为空串
  storeCode: string
  store: string
  customerId: string
  customerName: string
  project: string
  timesUsed: number
  amount: number // 元（纯扣次为 0）
  status: 'DONE' | 'ABNORMAL' | 'VOID' | string
  operator: string
  sign1: string
  sign2: string
  abnormalReason: string
  date: string // yyyy-MM-dd
}

export interface WriteoffDetailQuery {
  storeCode?: string
  status?: string
  cardNo?: string
  customerId?: string
  keyword?: string
  from?: string
  to?: string
}

export const getPrepayPool = () => client.get<PrepayPool>('/finance/prepay-pool')
export const getTax = () => client.get<Tax[]>('/finance/tax')
export const getAccounts = () => client.get<AccountMirror[]>('/finance/accounts')
export const getOutbox = () => client.get<OutboxRecord[]>('/finance/outbox')
export const getReconcile = () =>
  client.get<{ totalRecords: number; byStatus: Record<string, number>; netAmount: number; diffRecords: number }>(
    '/finance/outbox/reconcile'
  )

// ============================================================
// 对账台账人工写（B3 §5.3）：标记一致 / 标记差异 / 差异调平
// 权限：mark-* 需 finance:reconcile；adjust 需 finance:reconcile:approve
// 后端全审计（audit_log），调平生成 ADJUST 分录并置 ADJUSTED 留痕
// ============================================================

/** 人工标记「三方一致」（PENDING → RECONCILED，全审计） */
export const markReconciled = (id: number, remark?: string) =>
  client.post<Record<string, unknown>>(`/finance/outbox/${id}/mark-reconciled`, { remark: remark || null })

/** 人工标记「存在差异」（PENDING → DIFF，待调平处理，全审计） */
export const markDiff = (id: number, remark?: string) =>
  client.post<Record<string, unknown>>(`/finance/outbox/${id}/mark-diff`, { remark: remark || null })

/** 差异调平（DIFF → ADJUSTED，补 ADJUST 分录，需复核权限） */
export const adjustOutbox = (id: number, cmd: AdjustOutboxCmd) =>
  client.post<Record<string, unknown>>(`/finance/outbox/${id}/adjust`, cmd)

// ============================================================
// 三方对账（B7 §9.1）：经营域（txn 四流）× 资金域（fund_entry 落账）× 现金日结（双签工单）
// 按日聚合，金额 Long「分」（*Yuan 字段为元）；现金方不可用时 cashAvailable=false 诚实降级
// ============================================================

/** 三方对账门店明细行（金额均为「分」） */
export interface TripartiteStoreRow {
  storeCode: string
  storeName: string
  bizNetFen: number
  bizNetYuan: number
  postedNetFen: number
  postedNetYuan: number
  netDiffFen: number
  netDiffYuan: number
  /** 财务域独有：期末成本录入（MANUAL），不参与账账差异判定 */
  manualCostFen: number
  /** 扣除期末成本后的账账差异（≠0 即账账不符） */
  unexplainedDiffFen: number
  bizCashNetFen: number
  postedCashNetFen: number
  /** 双签工单实点现金合计；现金方不可用时 null */
  cashHandoverFen: number | null
  cashTicketCount: number
  /** 现金账实差异（落账现金净额 − 实点现金）；现金方不可用时 null */
  cashDiffFen: number | null
  orderCount: number
  refundCount: number
  writeoffPairCount: number
  cardCancelCount: number
  postedEntryCount: number
  matched: boolean
}

/** 三方对账日结果（GET /finance/reconcile/tripartite） */
export interface TripartiteResult {
  date: string
  store: string
  currency: string
  unit: string
  matched: boolean
  diffStoreCount: number
  bizNetFen: number
  bizNetYuan: number
  postedNetFen: number
  postedNetYuan: number
  netDiffFen: number
  netDiffYuan: number
  postedManualCostFen: number
  unexplainedDiffFen: number
  bizCashNetFen: number
  postedCashNetFen: number
  /** 现金日方可用（txn 现金日结拉取成功）；false 时下列现金字段为 null，仅出账账结果 */
  cashAvailable: boolean
  cashHandoverFen: number | null
  cashHandoverYuan: number | null
  cashTicketCount: number | null
  cashDiffFen: number | null
  cashDiffYuan: number | null
  adjustCount: number
  adjustNetFen: number
  flowCounts: Record<string, number>
  stores: TripartiteStoreRow[]
  message: string
}

export const getTripartite = (params?: { date?: string; storeCode?: string }) =>
  client.get<TripartiteResult>('/finance/reconcile/tripartite', { params })

// ============================================================
// 封账（B7 §9.2）：日结 DAY / 月结 MONTH 期间锁
// 封账后该「期间 × 门店」新分录一律 422 拒绝，差错走 ADJUST（落当前期间）；封账永久无解封
// ============================================================

/** 封账台账行（GET /finance/settlement；netAmountFen 为封账时点净额快照，分，IN 正 OUT 负） */
export interface SettlementPeriod {
  settlementId: number
  periodType: 'DAY' | 'MONTH'
  periodKey: string // DAY: yyyy-MM-dd；MONTH: yyyy-MM（UTC 口径）
  storeCode: string
  storeName: string
  status: 'CLOSED'
  entryCount: number
  netAmountFen: number
  memo?: string | null
  closedBy: string
  closedAt: string
  duplicated?: boolean
  message?: string
}

export interface SettlementQuery {
  periodType?: 'DAY' | 'MONTH'
  periodKey?: string
  storeCode?: string
}

export const getSettlements = (params?: SettlementQuery) =>
  client.get<SettlementPeriod[]>('/finance/settlement', { params })

export interface SettlementCmd {
  periodType: 'DAY' | 'MONTH'
  periodKey: string // 日结 yyyy-MM-dd / 月结 yyyy-MM
  storeCode: string
  memo?: string
}

export const postSettlement = (cmd: SettlementCmd) =>
  client.post<SettlementPeriod>('/finance/settlement', cmd)

// ============================================================
// B8 运维报表导出（只读 CSV）：封账台账 / 三方对账 / 资金台账 / 四类成本
// 后端 UTF-8 BOM + 中文表头 + 金额「元」，浏览器直接触发下载
// ============================================================

/** 从 Content-Disposition 解析后端文件名（filename*=UTF-8'' 优先），取不到用兜底名 */
function downloadCsv(resp: { data?: BlobPart; headers?: unknown }, fallback: string) {
  const h = (resp?.headers ?? {}) as { get?(k: string): unknown } & Record<string, unknown>
  const raw = typeof h.get === 'function' ? h.get('content-disposition') : h['content-disposition']
  const disposition = String(raw ?? '')
  let filename = fallback
  const star = /filename\*=UTF-8''([^;]+)/i.exec(disposition)
  if (star && star[1]) {
    try {
      filename = decodeURIComponent(star[1])
    } catch {
      filename = star[1]
    }
  }
  const blob = new Blob([resp.data as BlobPart], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

/** 封账台账导出（权限 finance:settlement:view，过滤条件同封账页） */
export const exportSettlementCsv = async (params?: SettlementQuery) => {
  const resp = await client.get('/finance/export/settlement.csv', { params, responseType: 'blob' })
  downloadCsv(resp, '封账台账.csv')
}

/** 三方对账门店明细导出（按日） */
export const exportTripartiteCsv = async (params?: { date?: string; storeCode?: string }) => {
  const resp = await client.get('/finance/export/tripartite.csv', { params, responseType: 'blob' })
  downloadCsv(resp, '三方对账.csv')
}

/** 资金分录台账导出 */
export const exportLedgerCsv = async (params?: { storeCode?: string; from?: string; to?: string }) => {
  const resp = await client.get('/finance/export/ledger.csv', { params, responseType: 'blob' })
  downloadCsv(resp, '资金台账.csv')
}

/** 四类成本汇总导出 */
export const exportCostCsv = async (params?: { month?: string; storeCode?: string }) => {
  const resp = await client.get('/finance/export/cost.csv', { params, responseType: 'blob' })
  downloadCsv(resp, '成本汇总.csv')
}

/** 发票台账导出（过滤条件同发票列表；status/type 传 undefined 表示全部） */
export const exportInvoiceCsv = async (params?: {
  storeCode?: string
  status?: InvoiceStatus
  type?: InvoiceType
  keyword?: string
}) => {
  const resp = await client.get('/finance/export/invoices.csv', { params, responseType: 'blob' })
  downloadCsv(resp, '发票台账.csv')
}

export const getRevenue = (storeCode?: string, month?: string) =>
  client.get<RevenueMonthly[]>('/finance/revenue', { params: { storeCode, month } })

/** 台账流水（finance 读时聚合 txn 订单/退款/划扣，金额「元」） */
export const getLedger = (params?: { storeCode?: string; from?: string; to?: string }) =>
  client.get<LedgerEntryDTO[]>('/finance/ledger', { params })

/** 会员卡余额包（finance 读时聚合 customer 会员卡，金额「元」） */
export const getCardsBalance = (storeCode?: string) =>
  client.get<CardBalanceBundle>('/finance/cards/balance', { params: { storeCode } })

/** 单卡流水时间线（卡不存在或越权后端返回 404 中文） */
export const getCardTimeline = (cardNo: string) =>
  client.get<CardTimelineDTO>(`/finance/cards/${encodeURIComponent(cardNo)}/timeline`)

/** 核销双签明细列表（参数均可选，后端已按登录人门店域收敛） */
export const listWriteoffDetails = (params?: WriteoffDetailQuery) =>
  client.get<WriteoffDetailDTO[]>('/finance/writeoff-details', { params })

/** 单卡流水时间线导出 CSV */
export const exportCardLedgerCsv = async (cardNo: string) => {
  const resp = await client.get('/finance/export/card-ledger.csv', {
    params: { cardNo },
    responseType: 'blob',
  })
  downloadCsv(resp, `卡流水-${cardNo}.csv`)
}

/** 核销双签明细导出 CSV（过滤条件同列表） */
export const exportWriteoffCsv = async (params?: WriteoffDetailQuery) => {
  const resp = await client.get('/finance/export/writeoffs.csv', { params, responseType: 'blob' })
  downloadCsv(resp, '核销双签明细.csv')
}

// ============================================================
// B5 成本域：四类成本汇总 / 手工录入（折旧 DEPRECIATION、人工 LABOR）
// 耗材 MATERIAL / 报损 LOSS 由双签工单审批通过自动落账
// ============================================================

/** 成本行（按月份+门店汇总，金额 Long「分」；字段与后端 /finance/cost 对齐） */
export interface CostAggregate {
  periodMonth: string // yyyy-MM-01
  storeCode: string
  material: number // 耗材成本（分）
  loss: number // 报损（分）
  depreciation: number // 设备折旧（分）
  labor: number // 人工分摊（分）
  total: number // 合计（分）
}

export const getCosts = (params?: { month?: string; storeCode?: string }) =>
  client.get<CostAggregate[]>('/finance/cost', { params })

/** 手工成本录入（month 为 yyyy-MM-01；耗材/报损由双签终审自动落账，不在此入口） */
export interface CostAllocationCmd {
  costType: 'DEPRECIATION' | 'LABOR'
  amountFen: number
  month: string
  storeCode?: string | null
  memo?: string
}

export const postCostAllocation = (cmd: CostAllocationCmd) =>
  client.post<Record<string, unknown>>('/finance/cost-allocation', cmd)

// ============================================================
// B5 预算管控：年度预算（金额「元」）
// ============================================================

export interface BudgetSubjectDTO {
  subjectCode: string
  budgetYuan: number
  updatedBy?: string | null
}

export interface BudgetBundle {
  year: number
  subjects: BudgetSubjectDTO[]
}

export const getBudgets = (year?: number) =>
  client.get<BudgetBundle>('/finance/budgets', { params: { year } })

/** 批量保存年度预算（budgets 为 科目码 → 元） */
export const saveBudgets = (budgets: Record<string, number>, year?: number) =>
  client.put<{ year: number; changed: number }>('/finance/budgets', { year, budgets })

// ============================================================
// B5 发票管理：凭证登记 + 状态机（DRAFT/ISSUED/VOIDED/RED_FLUSHED）
// 金额「元」，税额服务端计算；门店返回中文名 store + 原始 storeCode
// ============================================================

export type InvoiceType = 'NORMAL' | 'SPECIAL' | 'ELECTRONIC'
export type InvoiceStatus = 'DRAFT' | 'ISSUED' | 'VOIDED' | 'RED_FLUSHED'
export type InvoiceCategory = 'SERVICE' | 'PRODUCT' | 'MEMBERSHIP'

export interface InvoiceDTO {
  id: number
  invoiceNo: string
  type: InvoiceType
  category: InvoiceCategory
  title: string
  taxNo: string
  amount: number // 元
  taxAmount: number // 元
  taxRate: number // 0~1
  buyerName: string
  orderRefs: string[]
  storeCode: string
  store: string // 中文店名
  status: InvoiceStatus
  issuedAt: string
  operator?: string | null
  reviewer?: string | null
  remark?: string | null
  duplicated?: boolean
}

export interface InvoiceCreateCmd {
  type: InvoiceType
  category: InvoiceCategory
  title: string
  taxNo?: string
  amount: number // 元
  taxRate: number
  buyerName: string
  orderRefs?: string[]
  storeCode: string
  remark?: string
  idemKey?: string
}

export const getInvoices = (params?: {
  storeCode?: string
  status?: InvoiceStatus | 'ALL'
  type?: InvoiceType | 'ALL'
  keyword?: string
}) =>
  client.get<InvoiceDTO[]>('/finance/invoices', {
    params: {
      storeCode: params?.storeCode,
      status: params?.status && params.status !== 'ALL' ? params.status : undefined,
      type: params?.type && params.type !== 'ALL' ? params.type : undefined,
      keyword: params?.keyword || undefined,
    },
  })

export const createInvoice = (cmd: InvoiceCreateCmd) =>
  client.post<InvoiceDTO>('/finance/invoices', cmd)

export const issueInvoice = (id: number, reviewer?: string) =>
  client.post<InvoiceDTO>(`/finance/invoices/${id}/issue`, { reviewer: reviewer || null })

export const voidInvoice = (id: number, reason: string) =>
  client.post<InvoiceDTO>(`/finance/invoices/${id}/void`, { reason })

export const redFlushInvoice = (id: number, reason: string) =>
  client.post<InvoiceDTO>(`/finance/invoices/${id}/red-flush`, { reason })

// ============================================================
// B5 财务设置：单例配置 + 科目启用表 + 变更日志
// 税率 0~1；diffThresholdYuan 为「元」（DB 存分）
// ============================================================

export interface FinSettingsDTO {
  vatRate: number
  surtaxRate: number
  incomeTaxRate: number
  settleDay: number
  commissionPayDay: number
  reconcileTn: number
  diffThresholdYuan: number
  mirrorKingdee: boolean
  mirrorYonyou: boolean
  outboxRetry: number
}

export interface SubjectEnableDTO {
  code: string
  name: string
  enabled: boolean
}

export interface FinChangeLogDTO {
  id: number
  by: string
  at: string
  field: string
  oldValue: string | null
  newValue: string | null
}

export interface FinSettingsBundle {
  settings: FinSettingsDTO
  subjects: SubjectEnableDTO[]
  logs: FinChangeLogDTO[]
}

export const getFinSettings = () =>
  client.get<FinSettingsBundle>('/finance/settings')

export interface FinSettingsSaveCmd {
  vatRate: number
  surtaxRate: number
  incomeTaxRate: number
  settleDay: number
  commissionPayDay: number
  reconcileTn: number
  diffThreshold: number // 元
  mirrorKingdee: boolean
  mirrorYonyou: boolean
  outboxRetry: number
  subjects: Array<{ code: string; enabled: boolean }>
}

export const saveFinSettings = (cmd: FinSettingsSaveCmd) =>
  client.put<{ changed: number; subjectChanged: number }>('/finance/settings', cmd)

// ============================================================
// B12 非现金渠道账实接入：渠道结算单 CSV 导入 + 月勾兑（挂 /api/finance）
// 资金红线：账单只写勾兑台账 pay_channel_bill，绝不据账单补造实付渠道分录；
//           账单未导入时后端诚实降级，不做账实相符结论。
// 权限：查询 finance:view；导入 finance:reconcile:edit。
// ============================================================

/** 渠道账单行（GET /finance/channel-bills；金额 Long「分」+「元」双字段） */
export interface ChannelBillDTO {
  billId: string
  channelCode: string
  storeCode: string
  /** 渠道商户单号（勾兑键）；空 = 手续费/结算扣费行 */
  orderNo: string | null
  txnAmountFen: number
  txnAmountYuan: number
  feeAmountFen: number
  feeAmountYuan: number
  netAmountFen: number
  netAmountYuan: number
  /** SUCCESS 成功 / REFUND 退款（正数金额）/ FAILED 失败（仅笔数） */
  billStatus: 'SUCCESS' | 'REFUND' | 'FAILED' | string
  /** ISO-8601（存 +8:00 绝对时刻） */
  billTime: string
  settleBatch: string
  importBatch: string
}

/** CSV 整批导入结果（POST /finance/channel-bills/import；重放整批幂等跳过） */
export interface ChannelBillImportResult {
  importBatch: string
  totalRows: number
  importedRows: number
  skippedRows: number
  idempotentReplay: boolean
  message: string
}

/** 勾兑逐单清单行（matched/missing/extra/amountMismatch 共用，字段按类别取用） */
export interface ChannelReconcileOrderRow {
  storeCode: string
  orderNo: string
  /** 系统收款额（分/元） */
  sysFen?: number
  sysYuan?: number
  /** 账单成功交易额（分/元） */
  billSuccessFen?: number
  billSuccessYuan?: number
  /** 账单退款额（分）与失败笔数（不参与收款勾兑，单列提示） */
  billRefundFen?: number
  billFailedCount?: number
  /** 金额不符：系统 − 账单（分/元） */
  diffFen?: number
  diffYuan?: number
  /** 漏单/多单中文原因（后端诚实给出排查方向） */
  reason?: string
}

/** 勾兑门店汇总行（GET /finance/channel-reconcile） */
export interface ChannelReconcileStoreRow {
  storeCode: string
  storeName: string
  sysFen: number
  sysYuan: number
  sysCount: number
  billSuccessFen: number
  billSuccessYuan: number
  billSuccessCount: number
  billRefundFen: number
  billRefundYuan: number
  billRefundCount: number
  billFailedCount: number
  feeFen: number
  feeYuan: number
}

/** 月勾兑结果（系统账 fund_entry × 渠道账单 pay_channel_bill，按订单号逐单比对） */
export interface ChannelReconcileResult {
  month: string // yyyy-MM-01
  channel: string
  channelLabel: string
  store: string // ALL = 数据域内全部门店
  currency: string
  unit: string
  /** 账单侧是否有数据（false = 未导入账单，仅提示不做账实结论） */
  billsAvailable: boolean
  /** 账实勾兑通过（有账单且无漏单/多单/金额不符） */
  matched: boolean
  sysFen: number
  sysYuan: number
  sysCount: number
  billSuccessFen: number
  billSuccessYuan: number
  billSuccessCount: number
  billRefundFen: number
  billRefundYuan: number
  billRefundCount: number
  billFailedCount: number
  billRowCount: number
  feeFen: number
  feeYuan: number
  feeOnlyRowCount: number
  feeOnlyFen: number
  feeOnlyYuan: number
  matchedCount: number
  matchedFen: number
  matchedYuan: number
  /** 漏单（系统有账单无） */
  missingCount: number
  /** 多单/未入账（账单有系统无） */
  extraCount: number
  amountMismatchCount: number
  stores: ChannelReconcileStoreRow[]
  matchedOrders: ChannelReconcileOrderRow[]
  missingOrders: ChannelReconcileOrderRow[]
  extraOrders: ChannelReconcileOrderRow[]
  amountMismatchOrders: ChannelReconcileOrderRow[]
  message: string
}

/** 账单行查询（渠道/门店/日区间可选，按交易时间升序，DataScope 逐行收敛） */
export const listChannelBills = (params?: { channel?: string; storeCode?: string; from?: string; to?: string }) =>
  client.get<ChannelBillDTO[]>('/finance/channel-bills', { params })

/** 导入渠道结算单 CSV（整批校验、错误行带物理行号中文原因、import_batch 幂等） */
export const importChannelBills = (cmd: { channel: string; storeCode?: string; importBatch?: string; csv: string }) =>
  client.post<ChannelBillImportResult>('/finance/channel-bills/import', cmd)

/** 月勾兑：month=yyyy-MM-01、channel 必传（wxpay/alipay/transfer）、storeCode 可选 */
export const getChannelReconcile = (params: { month: string; channel: string; storeCode?: string }) =>
  client.get<ChannelReconcileResult>('/finance/channel-reconcile', { params })
