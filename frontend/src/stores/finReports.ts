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
export type AbnormalType = 'LONG' | 'SHORT' | 'REVERSED' | 'PENDING'
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
  channelAck: number // 渠道回单
  bankAck: number    // 银行到账
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
  LONG: '长款', SHORT: '短款', REVERSED: '冲正', PENDING: '待对账',
}
const ABNORMAL_TYPE_PILL: Record<AbnormalType, 'success' | 'warning' | 'danger' | 'primary'> = {
  LONG: 'success', SHORT: 'danger', REVERSED: 'warning', PENDING: 'primary',
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

  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
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
    data.forEach((d) => cards.value.push({ id: nextId('card'), ...d }))
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

  /** 从 financeCore.outbox 同步异常（LONG/SHORT/REVERSED/PENDING），幂等 */
  function syncFromCore() {
    for (const o of core.outbox) {
      if (o.status === 'MATCHED') continue
      const type: AbnormalType =
        o.status === 'LONG' ? 'LONG' :
        o.status === 'SHORT' ? 'SHORT' :
        o.status === 'REVERSED' ? 'REVERSED' : 'PENDING'
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
  function seed() {
    if (seeded) return
    seeded = true
    syncFromCore()
    // 补一条已处置样例
    items.value.push({
      id: nextId('ab'), txnNo: 'TX20260810022', type: 'SHORT', amount: 12,
      channel: '微信支付', occurredAt: '2026-08-10 15:20', status: 'RESOLVED',
      cashier: 3200, channelAck: 3188, bankAck: 3188,
      disposeMethod: 'ADJUST', reviewer: '陈雅琳（财务主管）',
      remark: '渠道手续费误扣，按手续费差额调平', disposedAt: '2026-08-11T09:30',
    })
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
export const useFinReportsStore = defineStore('finReports', () => {
  const core = useFinanceCoreStore()

  // ----- 税务 -----
  const taxRows = ref<TaxRow[]>([])
  const taxableRevenue = computed(() => taxRows.value.reduce((s, r) => s + r.base, 0))
  const outputTax = computed(() => taxRows.value.reduce((s, r) => s + r.amount, 0))
  const inputDeduct = ref(1860)
  const taxPayable = computed(() => Math.max(0, outputTax.value - inputDeduct.value))

  // ----- 资金日报 -----
  const dailyFlows = ref<DailyFlow[]>([])
  const channelFlows = ref<ChannelFlow[]>([])
  const todayIncome = computed(() => core.netRevenue > 0 ? core.totalRevenue : dailyFlows.value[dailyFlows.value.length - 1]?.income ?? 0)
  const todayExpense = computed(() => core.totalRefund + core.totalCost)
  const todayNet = computed(() => todayIncome.value - todayExpense.value)
  const endBalance = computed(() => core.depositBalance)

  // ----- 经营月报 -----
  const monthlyTrend = ref<MonthlyRow[]>([])
  const storeMonthly = ref<StoreMonthly[]>([])

  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true

    // 税务
    taxRows.value = [
      { id: nextId('tax'), taxName: '增值税（医疗服务6%）', base: 186000, rate: 0.06, amount: 10528.30 },
      { id: nextId('tax'), taxName: '增值税（产品销售13%）', base: 42600, rate: 0.13, amount: 4902.65 },
      { id: nextId('tax'), taxName: '城建税及附加', base: 15430.95, rate: 0.12, amount: 1851.71 },
      { id: nextId('tax'), taxName: '企业所得税（预缴）', base: 96800, rate: 0.25, amount: 24200 },
    ]

    // 近7日资金
    const days: DailyFlow[] = []
    let bal = 286000
    for (let i = 6; i >= 0; i--) {
      const day = 17 - i
      const income = [38600, 45200, 32100, 52800, 41300, 48900, 53000][6 - i]
      const expense = [12400, 18600, 9800, 21300, 15200, 17800, 16400][6 - i]
      bal += income - expense
      days.push({ date: `08-${String(day).padStart(2, '0')}`, income, expense, net: income - expense, balance: bal })
    }
    dailyFlows.value = days
    channelFlows.value = [
      { channel: '微信支付', income: 28600, expense: 2800 },
      { channel: '支付宝', income: 12400, expense: 0 },
      { channel: '刷卡', income: 8600, expense: 0 },
      { channel: '现金', income: 2200, expense: 0 },
      { channel: '储值划扣', income: 1200, expense: 0 },
      { channel: '退款支出', income: 0, expense: 2800 },
      { channel: '耗材/成本', income: 0, expense: 10800 },
    ]

    // 近6月趋势
    monthlyTrend.value = [
      { month: '3月', revenue: 486000, cost: 218000, grossProfit: 268000, grossRate: 55.1 },
      { month: '4月', revenue: 512000, cost: 232000, grossProfit: 280000, grossRate: 54.7 },
      { month: '5月', revenue: 568000, cost: 248000, grossProfit: 320000, grossRate: 56.3 },
      { month: '6月', revenue: 598000, cost: 265000, grossProfit: 333000, grossRate: 55.7 },
      { month: '7月', revenue: 642000, cost: 281000, grossProfit: 361000, grossRate: 56.2 },
      { month: '8月', revenue: Math.round(core.netRevenue + 620000), cost: core.totalCost + 268000, grossProfit: core.grossProfit + 352000, grossRate: 57.2 },
    ]
    storeMonthly.value = [
      { store: '静安旗舰店', revenue: 386000, cost: 162000, grossProfit: 224000, grossRate: 58.0 },
      { store: '万象城店', revenue: 218000, cost: 98000, grossProfit: 120000, grossRate: 55.0 },
      { store: '徐汇滨江店', revenue: 128000, cost: 62000, grossProfit: 66000, grossRate: 51.6 },
    ]
  }

  return {
    taxRows, taxableRevenue, outputTax, inputDeduct, taxPayable,
    dailyFlows, channelFlows, todayIncome, todayExpense, todayNet, endBalance,
    monthlyTrend, storeMonthly, seed,
  }
})
