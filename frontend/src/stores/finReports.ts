// ============================================================
// finReports —— M6 报表聚合 store（卡余额 / 异常账务 / 税务 / 资金日报 / 经营月报）
// 业财一体红线：全部只读镜像 + 聚合展示，异常处置仅登记处置记录，绝不碰资金池。
// 数据源：本 store 自有镜像 seed + 只读消费 useFinanceCoreStore（depositBalance/
// outboxLong/outboxShort/outboxPending/netRevenue/totalCost/grossProfit/grossRate/entries/outbox）。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { useFinanceCoreStore } from './financeCore'
import { useStoreContext } from './storeContext'
import {
  getCardsBalance, getTax, getCosts, getCardTimeline, listWriteoffDetails,
  listAbnormalBills, createAbnormalBill, disposeAbnormalBill,
  getInputInvoiceSummary, getCurrentTaxPeriod,
} from '@/api/finance'
import type {
  CardBalanceDTO, Tax as TaxDTO, CostAggregate,
  CardTxnDTO, CardTimelineDTO, WriteoffDetailDTO,
  FinAbnormalType, FinAbnormalStatus, FinAbnormalBillDTO,
  InputInvoiceSummary, TaxPeriodDTO,
} from '@/api/finance'

/** 分 → 元（finance 卡余额/税务镜像金额以「分」存储，台账聚合已换算为元） */
const fen2yuan = (f: number | null | undefined) => (f == null ? 0 : Math.round(f) / 100)

/** finance 聚合卡余额 DTO → MemberCard（卡明细流水无数据源，txns 回落空数组） */
function adaptCard(dto: CardBalanceDTO): MemberCard {
  return {
    id: dto.id,
    cardNo: dto.cardNo,
    customerName: dto.customerName,
    type: dto.type,
    balance: dto.balance,
    giftBalance: dto.giftBalance,
    timesTotal: dto.timesTotal,
    timesRemain: dto.timesRemain,
    lastConsumeAt: dto.lastConsumeAt,
    status: dto.status,
    txns: [],
  }
}

// ============ 会员卡余额 ============
export type CardType = 'STORED' | 'TIMES' | 'GIFT'
export type CardStatus = 'NORMAL' | 'DORMANT' | 'FROZEN'

export interface CardTxn {
  id: string
  date: string
  /** card_ledger 仅 RECHARGE/CONSUME/REFUND/ADJUST；冻结以 ADJUST（amount=0）呈现，无独立 FREEZE */
  type: 'RECHARGE' | 'CONSUME' | 'REFUND' | 'ADJUST'
  amount: number       // 带符号变动额（元）：充值/退款正，消费负
  memo: string
  balanceAfter: number // 变动后储值（元）
  giftAmount: number   // 赠送金变动（元）
  giftAfter: number    // 变动后赠送金（元）
  refNo: string        // 业务单号
  orderNo: string      // 订单号
  operator: string     // 经办人
}

/** card_ledger 流水 DTO → CardTxn（金额已为「元」；memo 由业务单号/订单号/经办人组合） */
function adaptTxn(dto: CardTxnDTO): CardTxn {
  const parts = [dto.refNo, dto.orderNo, dto.operator ? `经办：${dto.operator}` : '']
    .map((s) => (s ?? '').trim())
    .filter(Boolean)
  return {
    id: String(dto.ledgerId),
    date: dto.date,
    type: (dto.kind || 'ADJUST') as CardTxn['type'],
    amount: dto.amount ?? 0,
    memo: parts.join(' · ') || '—',
    balanceAfter: dto.balanceAfter ?? 0,
    giftAmount: dto.giftAmount ?? 0,
    giftAfter: dto.giftAfter ?? 0,
    refNo: dto.refNo ?? '',
    orderNo: dto.orderNo ?? '',
    operator: dto.operator ?? '',
  }
}
export interface MemberCard {
  id: string
  cardNo: string
  customerName: string
  type: CardType
  balance: number        // 储值余额
  giftBalance: number    // 赠送金
  timesTotal: number     // 疗程总次
  timesRemain: number    // 剩余次
  lastConsumeAt: string
  status: CardStatus
  txns: CardTxn[]
}

// ============ 异常账务处置单（B63 卡1 L84，真源 /finance/abnormal/bills） ============
// 类型/状态/入参全部对齐 finance FinAbnormalBill 实体与 toView（铁律1 三处一致）。
// 旧 Outbox 镜像假长短款（LONG/SHORT/REVERSED/PENDING/DIFF + 伪造三方金额 + 本地 dispose 态）已消灭。
export type AbnormalType = FinAbnormalType
export type AbnormalStatus = FinAbnormalStatus
export type AbnormalItem = FinAbnormalBillDTO

// ============ 税务 ============
export interface TaxRow {
  id: string
  taxName: string
  base: number
  rate: number
  amount: number
}

// ============ 资金日报 / 经营月报 ============
export interface DailyFlow {
  date: string
  income: number
  expense: number
  net: number
  balance: number
}
export interface ChannelFlow {
  channel: string
  income: number
  expense: number
  reconciled: boolean
  /** 桶内含混合支付单（一单多渠道，按主渠道归桶）时展示「混合（主：xx）」 */
  mixed?: boolean
}
export interface MonthlyRow {
  month: string
  revenue: number
  cost: number
  grossProfit: number
  grossRate: number
}
export interface StoreMonthly {
  store: string
  revenue: number
  cost: number
  grossProfit: number
  grossRate: number
}

const CARD_TYPE_LABEL: Record<CardType, string> = {
  STORED: '储值卡', TIMES: '疗程卡', GIFT: '赠送金',
}
const CARD_STATUS_LABEL: Record<CardStatus, string> = {
  NORMAL: '正常', DORMANT: '沉睡', FROZEN: '冻结',
}
const CARD_STATUS_PILL: Record<CardStatus, 'success' | 'info' | 'warning' | 'danger'> = {
  NORMAL: 'success', DORMANT: 'info', FROZEN: 'danger',
}
const CARD_TXN_LABEL: Record<CardTxn['type'], string> = {
  RECHARGE: '充值', CONSUME: '划扣', REFUND: '退款', ADJUST: '调整',
}

const ABNORMAL_TYPE_LABEL: Record<AbnormalType, string> = {
  SHORT: '短款', LONG: '长款', WRONG: '错账',
}
const ABNORMAL_TYPE_PILL: Record<AbnormalType, 'success' | 'warning' | 'danger' | 'primary'> = {
  SHORT: 'danger', LONG: 'success', WRONG: 'warning',
}
const ABNORMAL_STATUS_LABEL: Record<AbnormalStatus, string> = {
  PENDING_APPROVAL: '待审批', APPROVED: '审批通过·待处置', REJECTED: '已驳回', DISPOSED: '已处置入账',
}
const ABNORMAL_STATUS_PILL: Record<AbnormalStatus, 'danger' | 'warning' | 'success' | 'primary' | 'info'> = {
  PENDING_APPROVAL: 'warning', APPROVED: 'primary', REJECTED: 'danger', DISPOSED: 'success',
}
/** 调整方向（SHORT 处置固定 OUT 冲减；LONG 固定 IN 补收；WRONG 取登记方向） */
const ABNORMAL_DIRECTION_LABEL: Record<'IN' | 'OUT', string> = {
  IN: '补收（IN）', OUT: '冲减（OUT）',
}

// ============================================================
// Store 1: 卡余额
// ============================================================
export const useFinCardBalanceStore = defineStore('finCardBalance', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()
  const core = useFinanceCoreStore()
  const cards = ref<MemberCard[]>([])
  const filterStatus = ref<CardStatus | 'ALL'>('ALL')
  const keyword = ref('')

  const totalBalance = computed(() => core.depositBalance)
  const activeBalance = computed(() => cards.value.filter((c) => c.status === 'NORMAL').reduce((s, c) => s + c.balance + c.giftBalance, 0))
  const dormantBalance = computed(() => cards.value.filter((c) => c.status === 'DORMANT').reduce((s, c) => s + c.balance + c.giftBalance, 0))
  const cardCount = computed(() => cards.value.length)

  const composition = computed(() => {
    const stored = cards.value.reduce((s, c) => s + c.balance, 0)
    const gift = cards.value.reduce((s, c) => s + c.giftBalance, 0)
    const times = cards.value.filter((c) => c.type === 'TIMES').reduce((s, c) => s + (c.timesRemain * 2000), 0) // 疗程估值
    return [
      { label: '储值余额', value: stored, color: 'var(--c-series-1)' },
      { label: '赠送金', value: gift, color: 'var(--c-series-4)' },
      { label: '疗程估值', value: times, color: 'var(--c-series-3)' },
    ]
  })

  const filtered = computed(() => {
    let list = cards.value
    if (filterStatus.value !== 'ALL') list = list.filter((c) => c.status === filterStatus.value)
    const kw = keyword.value.trim().toLowerCase()
    if (kw) list = list.filter((c) => c.cardNo.toLowerCase().includes(kw) || c.customerName.toLowerCase().includes(kw))
    return list
  })

  function get(id: string) { return cards.value.find((c) => c.id === id) }

  /** 冻结/解冻（仅镜像状态，不动资金） */
  function freeze(id: string, frozen: boolean) {
    if (!auth.can('finance:cardbalance:view')) return false
    const c = cards.value.find((x) => x.id === id)
    if (!c) return false
    c.status = frozen ? 'FROZEN' : 'NORMAL'
    activity.log(auth.user.name, `${frozen ? '冻结' : '解冻'}会员卡 ${c.cardNo}（${c.customerName}）`, c.id)
    return true
  }

  // ----- B24 卡2：选中卡的真实流水时间线（customer card_ledger 经 finance 聚合代理，按卡号懒加载） -----
  const timelineCardNo = ref<string | null>(null)
  const timeline = ref<CardTimelineDTO | null>(null)
  const timelineTxns = ref<CardTxn[]>([])
  const timelineLoading = ref(false)
  const timelineError = ref('')

  /** 加载单卡时间线（404=卡不存在/越权，与其他失败一样诚实提示，绝不伪造流水） */
  async function loadTimeline(cardNo: string) {
    if (!cardNo) return
    timelineCardNo.value = cardNo
    timeline.value = null
    timelineTxns.value = []
    timelineError.value = ''
    timelineLoading.value = true
    try {
      const { data } = await getCardTimeline(cardNo)
      if (timelineCardNo.value !== cardNo) return
      timeline.value = data
      timelineTxns.value = (data.txns ?? []).map(adaptTxn)
    } catch (e: any) {
      if (timelineCardNo.value !== cardNo) return
      const status = e?.response?.status
      timelineError.value = status === 404
        ? '卡不存在或无权查看该卡'
        : '卡流水加载失败，请稍后重试'
      console.error('[finCardBalance] 加载卡时间线失败', cardNo, e)
    } finally {
      if (timelineCardNo.value === cardNo) timelineLoading.value = false
    }
  }

  let seeded = false
  let seeding: Promise<void> | null = null
  /** 从 finance-service 拉取真实会员卡余额（幂等；force 强制刷新）；失败保持诚实空态，不回落假数据 */
  function seed(force = false): Promise<void> {
    if (seeding && !force) return seeding
    if (seeded && !force) return Promise.resolve()
    seeding = (async () => {
      try {
        const { data } = await getCardsBalance()
        cards.value = data.cards.map(adaptCard)
        seeded = true
      } catch (e) {
        console.error('[finCardBalance] 加载卡余额失败，保持空态', e)
      }
    })()
    return seeding
  }

  return {
    cards, filterStatus, keyword, totalBalance, activeBalance, dormantBalance, cardCount,
    composition, filtered, get, freeze, seed,
    timelineCardNo, timeline, timelineTxns, timelineLoading, timelineError, loadTimeline,
    CARD_TYPE_LABEL, CARD_STATUS_LABEL, CARD_STATUS_PILL, CARD_TXN_LABEL,
  }
})

// ============================================================
// Store 2: 异常账务处置单（B63 卡1 L84，真源 finance /abnormal/bills）
// 登记 → txn 审批（FIN_ADJUSTMENT）→ 终审回调 APPROVED/REJECTED → APPROVED 单 dispose 落 ADJUST 分录。
// 金额后端 Long「分」，对外派生金额一律 fen2yuan；禁止本地伪造长短款/处置态。
// ============================================================
export const useFinAbnormalStore = defineStore('finAbnormal', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()
  const ctx = useStoreContext()

  const items = ref<AbnormalItem[]>([])
  const loading = ref(false)
  const error = ref('')
  const filterType = ref<AbnormalType | 'ALL'>('ALL')
  /** 登记抽屉提交中（连点去重，另配 idemKey 双保险） */
  const submitting = ref(false)
  /** 处置入账提交中 */
  const disposing = ref(false)

  const totalCount = computed(() => items.value.length)
  /** 待处置 = 待审批 + 审批通过待处置（REJECTED/DISPOSED 已闭环） */
  const openCount = computed(() =>
    items.value.filter((i) => i.status === 'PENDING_APPROVAL' || i.status === 'APPROVED').length)
  const resolvedCount = computed(() => items.value.filter((i) => i.status === 'DISPOSED').length)
  /** 长款（LONG）登记金额合计（元）；短款（SHORT）合计（元）。错账不计入长短款 */
  const longAmount = computed(() =>
    fen2yuan(items.value.filter((i) => i.type === 'LONG').reduce((s, i) => s + (i.amountFen ?? 0), 0)))
  const shortAmount = computed(() =>
    fen2yuan(items.value.filter((i) => i.type === 'SHORT').reduce((s, i) => s + (i.amountFen ?? 0), 0)))

  /** 服务端已按创建时间倒序；前端仅做类型筛选（ALL/SHORT/LONG/WRONG） */
  const filtered = computed(() =>
    filterType.value === 'ALL' ? items.value : items.value.filter((i) => i.type === filterType.value))

  function get(billNo: string) { return items.value.find((i) => i.billNo === billNo) }

  /** 用登记/处置回包就地 upsert（列表实体无 message/entry，多余字段随 DTO 保留无害） */
  function upsert(bill: AbnormalItem) {
    const idx = items.value.findIndex((i) => i.billNo === bill.billNo)
    if (idx >= 0) items.value.splice(idx, 1, bill)
    else items.value.unshift(bill)
  }

  /** 拉取真实异常账单（门店域由服务端收敛）；失败诚实空态，不回落假数据 */
  async function fetchRows() {
    loading.value = true
    error.value = ''
    try {
      await ctx.loadStores()
      const { data } = await listAbnormalBills()
      items.value = data ?? []
    } catch (e) {
      console.error('[finAbnormal] 加载异常账单失败，保持空态', e)
      error.value = '异常账单加载失败，请稍后重试'
    } finally {
      loading.value = false
    }
  }

  /**
   * 登记异常账单：门店/类型/金额(元→分)/事由，WRONG 必带方向；
   * idemKey 前端生成连点去重，成功后审批由 txn 异步流转，重新拉取保证状态权威。
   * 失败抛错（含后端中文 message），由视图 toast 呈现，不本地造单。
   */
  async function create(input: {
    storeCode: string
    type: AbnormalType
    direction?: 'IN' | 'OUT'
    amountYuan: number
    reason: string
  }): Promise<AbnormalItem> {
    if (submitting.value) throw new Error('正在提交，请勿重复点击')
    if (!input.storeCode) throw new Error('请选择登记门店')
    if (!input.reason.trim()) throw new Error('请填写异常事由')
    const amountFen = Math.round((Number(input.amountYuan) || 0) * 100)
    if (!(amountFen > 0)) throw new Error('金额必须为大于 0 的数字')
    if (input.type === 'WRONG' && input.direction !== 'IN' && input.direction !== 'OUT') {
      throw new Error('错账单必须选择调整方向（补收/冲减）')
    }
    submitting.value = true
    try {
      const idemKey = `WEB:${Date.now()}:${Math.random().toString(36).slice(2, 8)}`
      const { data } = await createAbnormalBill({
        storeCode: input.storeCode,
        type: input.type,
        direction: input.type === 'WRONG' ? input.direction : undefined,
        amountFen,
        reason: input.reason.trim(),
        idemKey,
      })
      upsert(data)
      activity.log(auth.user?.name || '当前用户',
        `登记异常账单 ${data.billNo}（${ABNORMAL_TYPE_LABEL[data.type]} ¥${fen2yuan(data.amountFen)}）已提交审批 ${data.approvalNo ?? ''}`,
        data.billNo)
      await fetchRows()
      return data
    } finally {
      submitting.value = false
    }
  }

  /** 处置入账：仅 APPROVED 单可调；后端落 ADJUST 调整分录（幂等）→ DISPOSED */
  async function dispose(billNo: string): Promise<AbnormalItem> {
    if (disposing.value) throw new Error('正在处置，请勿重复点击')
    disposing.value = true
    try {
      const { data } = await disposeAbnormalBill(billNo)
      upsert(data)
      activity.log(auth.user?.name || '当前用户',
        `异常账单 ${billNo} 处置入账（ADJUST 调整分录 #${data.disposeFundEntryId ?? '-'}）`, billNo)
      await fetchRows()
      return data
    } finally {
      disposing.value = false
    }
  }

  let seeded = false
  let seeding: Promise<void> | null = null
  /** 拉取真实异常账单（幂等；force 强制刷新）；失败诚实空态，绝不编造账单 */
  function seed(force = false): Promise<void> {
    if (seeding && !force) return seeding
    if (seeded && !force) return Promise.resolve()
    seeding = fetchRows().then(() => { seeded = true })
    return seeding
  }

  return {
    items, loading, error, submitting, disposing, filterType,
    totalCount, openCount, resolvedCount, longAmount, shortAmount,
    filtered, get, create, dispose, seed, refresh: fetchRows,
    ABNORMAL_TYPE_LABEL, ABNORMAL_TYPE_PILL, ABNORMAL_STATUS_LABEL, ABNORMAL_STATUS_PILL,
    ABNORMAL_DIRECTION_LABEL,
  }
})

// ============================================================
// Store 3: 三报表（税务 / 资金日报 / 经营月报）
// ============================================================
/** 渠道码 → 中文渠道名（对齐 order_payment 支付方式；order_payment 空→未标记渠道） */
const CHANNEL_LABEL: Record<string, string> = {
  cash: '现金', wxpay: '微信支付', alipay: '支付宝', card: '刷卡',
  balance: '储值余额', transfer: '转账', bank: '银行转账',
}
const r2 = (v: number) => Math.round(v * 100) / 100

export const useFinReportsStore = defineStore('finReports', () => {
  const core = useFinanceCoreStore()
  const ctx = useStoreContext()

  // ----- B5 成本聚合（/finance/cost，Long 分；月报/门店毛利的成本权威源） -----
  const costAggs = ref<CostAggregate[]>([])
  /** 门店编码 → 中文名（台账 store 为中文名，成本聚合为编码，需对齐） */
  const nameOf = (code: string) => ctx.stores.find((s) => s.storeCode === code)?.storeName ?? code

  // ----- 税务（销项取 finance 镜像端点；进项抵扣/留抵取申报期 summary 真源，元） -----
  const taxRows = ref<TaxRow[]>([])
  const taxableRevenue = computed(() => taxRows.value.reduce((s, r) => s + r.base, 0))
  const outputTax = computed(() => taxRows.value.reduce((s, r) => s + r.amount, 0))
  // 进项抵扣＝当前申报期净抵扣（已抵扣－进项转出）；拉取失败诚实回落 0，不再写死假值
  const inputSummary = ref<InputInvoiceSummary | null>(null)
  const inputDeduct = computed(() => r2(inputSummary.value?.netDeductible ?? 0))
  /** 期末留抵（净抵扣 > 销项时结转下期），供税务页副文案列示 */
  const retainedAmount = computed(() => r2(inputSummary.value?.retainedAmount ?? 0))
  const taxPayable = computed(() => Math.max(0, outputTax.value - inputDeduct.value))

  // ----- 当前申报期（/finance/tax-periods/current；未登记为 exists=false 预填骨架） -----
  const currentPeriod = ref<TaxPeriodDTO | null>(null)
  /** 期间标题，如「2026-09」；拉取失败不显示期间号 */
  const currentPeriodLabel = computed(() => currentPeriod.value?.period ?? '')
  /** 申报状态中文文案（后端权威：未申报/已申报/逾期补申报/更正申报/已归档） */
  const currentPeriodStatusLabel = computed(() => currentPeriod.value?.statusLabel ?? '未申报')
  /** 申报状态 pill 色调：未申报→info，已申报→success，逾期/更正→warning，归档→default */
  const currentPeriodStatusTone = computed<'info' | 'success' | 'warning' | 'default'>(() => {
    switch (currentPeriod.value?.status) {
      case 'FILED': return 'success'
      case 'LATE_FILED':
      case 'AMENDED': return 'warning'
      case 'CLOSED': return 'default'
      default: return 'info'
    }
  })

  // ----- 资金日报（从真实台账派生） -----
  // 收入 = RF-REVENUE IN；支出 = RF-REFUND/TK* OUT（RF-DEPOSIT OUT 为预收内部转出，不重复计流出）
  const dailyFlows = computed<DailyFlow[]>(() => {
    const byDate = new Map<string, { income: number; expense: number }>()
    for (const e of core.entries) {
      const slot = byDate.get(e.date) ?? { income: 0, expense: 0 }
      if (e.direction === 'IN' && e.subject === 'RF-REVENUE') slot.income += e.amount
      else if (e.direction === 'OUT' && (e.subject === 'RF-REFUND' || e.subject.startsWith('TK'))) slot.expense += e.amount
      byDate.set(e.date, slot)
    }
    let running = 0
    return [...byDate.entries()]
      .sort(([a], [b]) => (a < b ? -1 : 1))
      .map(([date, v]) => {
        running = r2(running + v.income - v.expense)
        return { date: date.slice(5), income: r2(v.income), expense: r2(v.expense), net: r2(v.income - v.expense), balance: running }
      })
  })

  // 渠道明细：与「本日」KPI 同口径——仅取最近营业日（按 RF-REVENUE/RF-REFUND 聚合），
  // 不把整期累计塞进「当日」表；order_payment 支付渠道未采集时渠道回落「未标记渠道」。
  const latestDate = computed<string | null>(() => {
    const dates = core.entries.map((e) => e.date).filter(Boolean).sort()
    return dates.length ? dates[dates.length - 1] : null
  })
  const channelFlows = computed<ChannelFlow[]>(() => {
    // 桶位按主渠道码归并（混合支付单取入账额最大一笔渠道，保证 Σ各渠道 = 收银实收）；
    // 展示名：桶内出现混合单 → 「混合（主：xx）」，无渠道码 → 「未标记渠道」。
    const m = new Map<string, ChannelFlow>()
    const ensure = (code: string) => {
      let row = m.get(code)
      if (!row) {
        const label = code === '__NONE__' ? '未标记渠道' : (CHANNEL_LABEL[code] ?? code)
        row = { channel: label, income: 0, expense: 0, reconciled: true }
        m.set(code, row)
      }
      return row
    }
    for (const e of core.entries) {
      if (e.date !== latestDate.value) continue
      if (e.subject !== 'RF-REVENUE' && e.subject !== 'RF-REFUND') continue
      const code = e.channel ?? '__NONE__'
      const row = ensure(code)
      if (e.mixed) {
        row.mixed = true
        row.channel = `混合（主：${CHANNEL_LABEL[code] ?? code}）`
      }
      if (e.subject === 'RF-REVENUE' && e.direction === 'IN') row.income = r2(row.income + e.amount)
      if (e.subject === 'RF-REFUND' && e.direction === 'OUT') row.expense = r2(row.expense + e.amount)
      if (!e.reconciled) row.reconciled = false
    }
    return [...m.values()]
  })

  // 「本日」取台账最近一个有流水的营业日（种子数据跨度多日，非严格自然今日），
  // 不拿整期累计充当本日，避免真实数据下 KPI 误导。
  const latestDay = computed<DailyFlow | null>(() =>
    dailyFlows.value.length ? dailyFlows.value[dailyFlows.value.length - 1] : null)
  const todayIncome = computed(() => latestDay.value?.income ?? 0)
  const todayExpense = computed(() => latestDay.value?.expense ?? 0)
  const todayNet = computed(() => todayIncome.value - todayExpense.value)
  const endBalance = computed(() => core.depositBalance)

  // ----- 经营月报：收入从真实台账派生，成本取 /finance/cost 月聚合（毛利=收入-成本） -----
  const costByMonth = computed<Map<string, number>>(() => {
    const m = new Map<string, number>()
    for (const a of costAggs.value) {
      const key = (a.periodMonth || '').slice(0, 7)
      m.set(key, r2((m.get(key) ?? 0) + fen2yuan(a.total)))
    }
    return m
  })

  const monthlyTrend = computed<MonthlyRow[]>(() => {
    const byMonth = new Map<string, number>()
    for (const e of core.entries) {
      if (e.subject === 'RF-REVENUE' && e.direction === 'IN') {
        const m = e.date.slice(0, 7)
        byMonth.set(m, (byMonth.get(m) ?? 0) + e.amount)
      }
    }
    for (const key of costByMonth.value.keys()) {
      if (!byMonth.has(key)) byMonth.set(key, 0)
    }
    return [...byMonth.entries()]
      .sort(([a], [b]) => (a < b ? -1 : 1))
      .map(([month, revenue]) => {
        const cost = costByMonth.value.get(month) ?? 0
        const grossProfit = r2(revenue - cost)
        return {
          month: `${Number(month.slice(5))}月`,
          revenue: r2(revenue),
          cost,
          grossProfit,
          grossRate: revenue ? Math.round((grossProfit / revenue) * 1000) / 10 : 0,
        }
      })
  })

  const costByStore = computed<Map<string, number>>(() => {
    // 键统一用门店中文名（与台账 e.store 对齐）
    const m = new Map<string, number>()
    for (const a of costAggs.value) {
      const key = nameOf(a.storeCode)
      m.set(key, r2((m.get(key) ?? 0) + fen2yuan(a.total)))
    }
    return m
  })

  const storeMonthly = computed<StoreMonthly[]>(() => {
    const byStore = new Map<string, number>()
    for (const e of core.entries) {
      if (e.subject === 'RF-REVENUE' && e.direction === 'IN') {
        byStore.set(e.store, (byStore.get(e.store) ?? 0) + e.amount)
      }
    }
    // 仅有成本无收入的门店（新开店/当月无营收）也要列示
    for (const key of costByStore.value.keys()) {
      if (!byStore.has(key)) byStore.set(key, 0)
    }
    return [...byStore.entries()]
      .sort(([, a], [, b]) => b - a)
      .map(([store, revenue]) => {
        const cost = costByStore.value.get(store) ?? 0
        const grossProfit = r2(revenue - cost)
        return {
          store,
          revenue: r2(revenue),
          cost,
          grossProfit,
          grossRate: revenue ? Math.round((grossProfit / revenue) * 1000) / 10 : 0,
        }
      })
  })

  let seeded = false
  let seeding: Promise<void> | null = null
  /** 拉取 finance 税务镜像 + B5 成本聚合（幂等；force 强制刷新）；失败静默回落空态 */
  function seed(force = false): Promise<void> {
    if (seeding && !force) return seeding
    if (seeded && !force) return Promise.resolve()
    seeding = (async () => {
      try {
        await ctx.loadStores()
        const [tax, cost] = await Promise.all([getTax(), getCosts()])
        taxRows.value = ((tax.data as TaxDTO[]) ?? []).map((t) => ({
          id: nextId('tax'),
          taxName: t.cat,
          base: fen2yuan(t.base),
          rate: t.rate,
          amount: fen2yuan(t.amount),
        }))
        costAggs.value = cost.data ?? []
        seeded = true
      } catch (e) {
        console.error('[finReports] 加载税务/成本镜像失败，回落空态', e)
      }
      // 进项抵扣汇总＋当前申报期：独立 best-effort，失败仅静默回落零值/空态，不拖垮销项与成本
      try {
        const [summary, period] = await Promise.all([
          getInputInvoiceSummary(),
          getCurrentTaxPeriod('MONTH'),
        ])
        inputSummary.value = summary.data ?? null
        currentPeriod.value = period.data ?? null
      } catch (e) {
        console.error('[finReports] 加载进项抵扣/申报期失败，进项口径回落 0', e)
      }
    })()
    return seeding
  }

  return {
    taxRows, taxableRevenue, outputTax, inputDeduct, taxPayable, retainedAmount,
    currentPeriodLabel, currentPeriodStatusLabel, currentPeriodStatusTone,
    dailyFlows, channelFlows, latestDate, todayIncome, todayExpense, todayNet, endBalance,
    monthlyTrend, storeMonthly, seed,
  }
})

// ============================================================
// Store 4: 核销双签明细（B24 卡2，txn writeoff_record 经 finance 聚合代理）
// ============================================================
export type WriteoffStatus = 'DONE' | 'ABNORMAL' | 'VOID'

export const WRITEOFF_STATUS_LABEL: Record<WriteoffStatus, string> = {
  DONE: '已核销', ABNORMAL: '异常', VOID: '已作废',
}
export const WRITEOFF_STATUS_PILL: Record<WriteoffStatus, 'success' | 'danger' | 'info'> = {
  DONE: 'success', ABNORMAL: 'danger', VOID: 'info',
}

export const useFinWriteoffStore = defineStore('finWriteoff', () => {
  const ctx = useStoreContext()

  /** 全量明细（一次拉取，门店/状态/关键词在前端过滤；与导出 CSV 的服务端过滤参数口径一致） */
  const rows = ref<WriteoffDetailDTO[]>([])
  const loading = ref(false)
  const error = ref('')

  const filterStatus = ref<WriteoffStatus | 'ALL'>('ALL')
  const filterStore = ref<string>('ALL')
  const keyword = ref('')

  const storeOptions = computed(() => [
    { value: 'ALL', label: '全部门店' },
    ...ctx.stores
      .filter((s) => rows.value.some((r) => r.storeCode === s.storeCode))
      .map((s) => ({ value: s.storeCode, label: s.storeName })),
  ])

  const filtered = computed(() => {
    const kw = keyword.value.trim().toLowerCase()
    return rows.value.filter((r) => {
      if (filterStatus.value !== 'ALL' && r.status !== filterStatus.value) return false
      if (filterStore.value !== 'ALL' && r.storeCode !== filterStore.value) return false
      if (kw && ![r.writeoffId, r.orderNo, r.cardNo, r.customerName, r.project, r.abnormalReason]
        .some((v) => (v ?? '').toLowerCase().includes(kw))) return false
      return true
    })
  })

  const doneRows = computed(() => rows.value.filter((r) => r.status === 'DONE'))
  const abnormalRows = computed(() => rows.value.filter((r) => r.status === 'ABNORMAL'))
  const voidRows = computed(() => rows.value.filter((r) => r.status === 'VOID'))
  const sumAmount = (list: WriteoffDetailDTO[]) =>
    Math.round(list.reduce((s, r) => s + (r.amount ?? 0), 0) * 100) / 100
  const doneAmount = computed(() => sumAmount(doneRows.value))
  const abnormalAmount = computed(() => sumAmount(abnormalRows.value))

  async function fetchRows() {
    loading.value = true
    error.value = ''
    try {
      await ctx.loadStores()
      const { data } = await listWriteoffDetails()
      rows.value = data ?? []
    } catch (e) {
      console.error('[finWriteoff] 加载核销双签明细失败，保持空态', e)
      error.value = '核销明细加载失败，请稍后重试'
    } finally {
      loading.value = false
    }
  }

  let seeded = false
  let seeding: Promise<void> | null = null
  /** 拉取全量核销双签明细（幂等；force 强制刷新）；失败诚实空态，不编造双签记录 */
  function seed(force = false): Promise<void> {
    if (seeding && !force) return seeding
    if (seeded && !force) return Promise.resolve()
    seeding = fetchRows().then(() => { seeded = true })
    return seeding
  }

  return {
    rows, loading, error,
    filterStatus, filterStore, keyword,
    storeOptions, filtered,
    doneRows, abnormalRows, voidRows, doneAmount, abnormalAmount,
    seed, refresh: fetchRows,
    WRITEOFF_STATUS_LABEL, WRITEOFF_STATUS_PILL,
  }
})
