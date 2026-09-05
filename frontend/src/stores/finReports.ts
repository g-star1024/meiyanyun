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
import { getCardsBalance, getTax, getCosts } from '@/api/finance'
import type { CardBalanceDTO, Tax as TaxDTO, CostAggregate } from '@/api/finance'

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
  type: 'RECHARGE' | 'CONSUME' | 'REFUND' | 'FREEZE' | 'ADJUST'
  amount: number
  memo: string
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

// ============ 异常账务 ============
// LONG/SHORT/REVERSED/PENDING 为演示 seed 的三方回单差异态；DIFF 为真实台账人工标记差异（待双签调平）
export type AbnormalType = 'LONG' | 'SHORT' | 'REVERSED' | 'PENDING' | 'DIFF'
export type AbnormalStatus = 'OPEN' | 'PROCESSING' | 'RESOLVED'
export type DisposeMethod = 'ADJUST' | 'LOSS' | 'ACCOUNTABILITY' | 'PENDING'

export interface AbnormalItem {
  id: string
  txnNo: string
  type: AbnormalType
  amount: number
  channel: string
  occurredAt: string
  status: AbnormalStatus
  cashier: number   // 收银记账
  /** 渠道回单金额：真实台账三方回单 B6 接入前为 null（不伪造） */
  channelAck: number | null
  /** 银行到账金额：真实台账三方回单 B6 接入前为 null（不伪造） */
  bankAck: number | null
  /** 真实 finance-service outbox 项（可人工标记/调平）；演示 seed 为 false */
  writable?: boolean
  disposeMethod?: DisposeMethod
  reviewer?: string
  remark?: string
  disposedAt?: string
}

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
  RECHARGE: '充值', CONSUME: '消费', REFUND: '退款', FREEZE: '冻结', ADJUST: '调整',
}

const ABNORMAL_TYPE_LABEL: Record<AbnormalType, string> = {
  LONG: '长款', SHORT: '短款', REVERSED: '冲正', PENDING: '待对账', DIFF: '人工标记差异',
}
const ABNORMAL_TYPE_PILL: Record<AbnormalType, 'success' | 'warning' | 'danger' | 'primary'> = {
  LONG: 'success', SHORT: 'danger', REVERSED: 'warning', PENDING: 'primary', DIFF: 'danger',
}
const ABNORMAL_STATUS_LABEL: Record<AbnormalStatus, string> = {
  OPEN: '待处置', PROCESSING: '处置中', RESOLVED: '已处置',
}
const ABNORMAL_STATUS_PILL: Record<AbnormalStatus, 'danger' | 'warning' | 'success'> = {
  OPEN: 'danger', PROCESSING: 'warning', RESOLVED: 'success',
}
const DISPOSE_LABEL: Record<DisposeMethod, string> = {
  ADJUST: '调平入账', LOSS: '报损核销', ACCOUNTABILITY: '追责赔偿', PENDING: '挂账待查',
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

  /** 演示数据（API 失败/未接通时回落，活规格） */
  function seedMock() {
    const d = (day: number) => `2026-08-${String(day).padStart(2, '0')}`
    const tx = (id: string, date: string, type: CardTxn['type'], amount: number, memo: string): CardTxn => ({ id, date, type, amount, memo })
    const data: Array<Omit<MemberCard, 'id'>> = [
      { cardNo: 'MC-8801-0001', customerName: '林微', type: 'STORED', balance: 15734, giftBalance: 500, timesTotal: 0, timesRemain: 0, lastConsumeAt: d(17), status: 'NORMAL',
        txns: [tx('t1', d(14), 'RECHARGE', 20000, '微信储值充值赠500'), tx('t2', d(15), 'CONSUME', 4266, '水光针疗程划扣'), tx('t3', d(16), 'CONSUME', 500, '产品购买')] },
      { cardNo: 'MC-8801-0002', customerName: '陈美玲', type: 'TIMES', balance: 0, giftBalance: 0, timesTotal: 6, timesRemain: 5, lastConsumeAt: d(15), status: 'NORMAL',
        txns: [tx('t1', d(10), 'RECHARGE', 12800, '热玛吉6次卡'), tx('t2', d(15), 'CONSUME', 0, '第1次热玛吉划扣')] },
      { cardNo: 'MC-8801-0003', customerName: '赵雨晴', type: 'STORED', balance: 29800, giftBalance: 1000, timesTotal: 0, timesRemain: 0, lastConsumeAt: d(16), status: 'NORMAL',
        txns: [tx('t1', d(16), 'RECHARGE', 30000, '刷卡储值赠1000'), tx('t2', d(16), 'CONSUME', 200, '产品抵扣')] },
      { cardNo: 'MC-8801-0004', customerName: '王诗涵', type: 'TIMES', balance: 0, giftBalance: 0, timesTotal: 10, timesRemain: 7, lastConsumeAt: d(10), status: 'DORMANT',
        txns: [tx('t1', d(1), 'RECHARGE', 6800, '水光针10次卡'), tx('t2', d(5), 'CONSUME', 0, '第1次'), tx('t3', d(10), 'CONSUME', 0, '第3次')] },
      { cardNo: 'MC-8802-0005', customerName: '孙佳宁', type: 'GIFT', balance: 0, giftBalance: 680, timesTotal: 0, timesRemain: 0, lastConsumeAt: d(17), status: 'NORMAL',
        txns: [tx('t1', d(17), 'ADJUST', 680, '生日赠送金到账')] },
      { cardNo: 'MC-8802-0006', customerName: '周慧敏', type: 'STORED', balance: 800, giftBalance: 0, timesTotal: 0, timesRemain: 0, lastConsumeAt: d(8), status: 'DORMANT',
        txns: [tx('t1', d(1), 'RECHARGE', 5000, '现金储值'), tx('t2', d(8), 'CONSUME', 4200, '疗程划扣')] },
      { cardNo: 'MC-8801-0007', customerName: '吴思琪', type: 'STORED', balance: 0, giftBalance: 0, timesTotal: 0, timesRemain: 0, lastConsumeAt: d(5), status: 'FROZEN',
        txns: [tx('t1', d(20), 'RECHARGE', 56000, '对公转账储值'), tx('t2', d(25), 'FREEZE', 56000, '争议冻结待核')] },
      { cardNo: 'MC-8801-0008', customerName: '李晓彤', type: 'TIMES', balance: 0, giftBalance: 0, timesTotal: 5, timesRemain: 2, lastConsumeAt: d(12), status: 'NORMAL',
        txns: [tx('t1', d(2), 'RECHARGE', 9800, '光子嫩肤5次卡'), tx('t2', d(12), 'CONSUME', 0, '第3次')] },
    ]
    data.forEach((row) => cards.value.push({ id: nextId('card'), ...row }))
  }

  let seeded = false
  let seeding: Promise<void> | null = null
  /** 从 finance-service 拉取真实会员卡余额（幂等；force 强制刷新）；失败回落演示数据 */
  function seed(force = false): Promise<void> {
    if (seeding && !force) return seeding
    if (seeded && !force) return Promise.resolve()
    seeding = (async () => {
      try {
        const { data } = await getCardsBalance()
        cards.value = data.cards.map(adaptCard)
        seeded = true
      } catch (e) {
        console.error('[finCardBalance] 加载卡余额失败，回落演示数据', e)
        if (cards.value.length === 0) seedMock()
      }
    })()
    return seeding
  }

  return {
    cards, filterStatus, keyword, totalBalance, activeBalance, dormantBalance, cardCount,
    composition, filtered, get, freeze, seed,
    CARD_TYPE_LABEL, CARD_STATUS_LABEL, CARD_STATUS_PILL, CARD_TXN_LABEL,
  }
})

// ============================================================
// Store 2: 异常账务
// ============================================================
export const useFinAbnormalStore = defineStore('finAbnormal', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()
  const core = useFinanceCoreStore()

  const items = ref<AbnormalItem[]>([])
  const filterType = ref<AbnormalType | 'ALL'>('ALL')

  const totalCount = computed(() => items.value.length)
  const openCount = computed(() => items.value.filter((i) => i.status === 'OPEN' || i.status === 'PROCESSING').length)
  const resolvedCount = computed(() => items.value.filter((i) => i.status === 'RESOLVED').length)
  const longAmount = computed(() => core.outboxLong)
  const shortAmount = computed(() => core.outboxShort)

  const filtered = computed(() => {
    let list = items.value
    if (filterType.value !== 'ALL') list = list.filter((i) => i.type === filterType.value)
    return [...list].sort((a, b) => (a.occurredAt < b.occurredAt ? 1 : -1))
  })

  function get(id: string) { return items.value.find((i) => i.id === id) }

  /** 从 financeCore.outbox 同步异常，幂等。
   *  演示 seed（writable=false）：LONG/SHORT/REVERSED/PENDING 为本地演示的三方回单差异，保留伪造 triad。
   *  真实台账（writable=true）：仅 DIFF（人工标记差异，待双签调平）纳入；PENDING 属正常待对账（回单 B6 未接入，
   *  不臆造差异），MATCHED/ADJUSTED 已闭环跳过；回单金额一律 null，页面诚实展示「回单未接入」。 */
  function syncFromCore() {
    for (const o of core.outbox) {
      if (o.writable) {
        if (o.status !== 'DIFF') continue
        if (!items.value.some((i) => i.txnNo === o.txnNo)) {
          items.value.unshift({
            id: nextId('ab'),
            txnNo: o.txnNo,
            type: 'DIFF',
            amount: o.amount,
            channel: o.channel,
            occurredAt: o.occurredAt,
            status: 'OPEN',
            cashier: o.amount,
            channelAck: null,
            bankAck: null,
            writable: true,
          })
        }
        continue
      }
      if (o.status === 'MATCHED' || o.status === 'ADJUSTED') continue
      const type: AbnormalType =
        o.status === 'LONG' ? 'LONG' :
        o.status === 'SHORT' ? 'SHORT' :
        o.status === 'REVERSED' ? 'REVERSED' :
        o.status === 'DIFF' ? 'DIFF' : 'PENDING'
      if (!items.value.some((i) => i.txnNo === o.txnNo)) {
        const base = o.amount
        items.value.unshift({
          id: nextId('ab'),
          txnNo: o.txnNo,
          type,
          amount: type === 'LONG' ? Math.round(base * 0.0185 * 100) / 100 : type === 'SHORT' ? 6 : base,
          channel: o.channel,
          occurredAt: o.occurredAt,
          status: 'OPEN',
          cashier: base,
          channelAck: type === 'LONG' ? base : base - 6,
          bankAck: type === 'LONG' ? base + Math.round(base * 0.0185 * 100) / 100 : base - 6,
          writable: false,
        })
      }
    }
  }

  /** 人工处置（双签：处置方式 + 复核人），仅登记，不反向动账 */
  function dispose(id: string, method: DisposeMethod, reviewer: string, remark: string): boolean {
    if (!auth.can('finance:abnormal:dispose')) return false
    const it = items.value.find((i) => i.id === id)
    if (!it || it.status === 'RESOLVED') return false
    it.status = 'RESOLVED'
    it.disposeMethod = method
    it.reviewer = reviewer.trim()
    it.remark = remark.trim()
    it.disposedAt = new Date().toISOString()
    activity.log(auth.user.name, `处置异常 ${it.txnNo}（${DISPOSE_LABEL[method]}）：${remark}`, it.id)
    return true
  }

  let seeded = false
  let seeding: Promise<void> | null = null
  /** 先确保 financeCore 真实台账拉取完成，再从 outbox 镜像派生异常（幂等；force 强制刷新）。
   *  注意：core 的台账是 store 初始化时 void seed() 触发的异步 fetch，本函数必须 await core.seed()
   *  之后再 syncFromCore()，否则会在 outbox 仍为空时快照成永久空列表（异步竞态）。
   *  镜像无三方回单差异时列表为空（不插入演示样例，避免伪造收银/渠道/银行三方金额与处置记录）。 */
  function seed(force = false): Promise<void> {
    if (seeding && !force) return seeding
    if (seeded && !force) return Promise.resolve()
    seeding = (async () => {
      await core.seed(force)
      syncFromCore()
      seeded = true
    })()
    return seeding
  }

  return {
    items, filterType, totalCount, openCount, resolvedCount, longAmount, shortAmount,
    filtered, get, syncFromCore, dispose, seed,
    ABNORMAL_TYPE_LABEL, ABNORMAL_TYPE_PILL, ABNORMAL_STATUS_LABEL, ABNORMAL_STATUS_PILL, DISPOSE_LABEL,
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

  // ----- 税务（finance 镜像端点；种子库 tax 表空→空态，金额「分」→「元」） -----
  const taxRows = ref<TaxRow[]>([])
  const taxableRevenue = computed(() => taxRows.value.reduce((s, r) => s + r.base, 0))
  const outputTax = computed(() => taxRows.value.reduce((s, r) => s + r.amount, 0))
  // 进项抵扣无数据源（采购/供应商发票未建），诚实为 0
  const inputDeduct = ref(0)
  const taxPayable = computed(() => Math.max(0, outputTax.value - inputDeduct.value))

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
    })()
    return seeding
  }

  return {
    taxRows, taxableRevenue, outputTax, inputDeduct, taxPayable,
    dailyFlows, channelFlows, latestDate, todayIncome, todayExpense, todayNet, endBalance,
    monthlyTrend, storeMonthly, seed,
  }
})
