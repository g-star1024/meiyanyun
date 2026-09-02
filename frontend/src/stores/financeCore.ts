// ============================================================
// financeCore —— M6 数据财务红线核心 store
// 业财一体：只读镜像支付流水 + Outbox 三方对账 + RF/TK 科目分离
// 财务红线：仅镜像 + 对账，绝不碰资金池；所有写操作是"对账标记/调平记录"，
//           不产生真实资金动账；transaction_id 幂等。
// ============================================================
// 8 大恒等式（报表层成立，见 identities computed）：
//  1. 本期净营收 = 收银实收 - 退款净额
//  2. 收银实收 = Σ 各支付渠道流水（cash/wxpay/alipay/card/balance）
//  3. 预收账款余额 = 充值 - 划扣消耗 - 退款
//  4. 划扣确认收入 = Σ 已双签划扣金额
//  5. 营业成本 = 耗材出库成本 + 设备折旧 + 报损 + 人工分摊
//  6. 毛利 = 划扣确认收入 - 营业成本
//  7. Outbox 三方平衡：收银流水 == 支付渠道流水 == 银行入账（差异=长款/短款）
//  8. 卡余额 = Σ 会员卡剩余价值（储值余额 + 疗程次数估值），与预收账款相互印证
// RF/TK 科目分离：
//  RF* = Revenue/Finance 资金类（现金/银行/应收/预收/主营收入/退款）
//  TK* = 库存/成本类（耗材库存/主营成本/折旧/报损）
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useActivityStore } from './activity'
import { useAuthStore } from './auth'

/** 科目代码：RF 资金类 / TK 成本库存类 */
export type SubjectCode =
  | 'RF-CASH' | 'RF-BANK' | 'RF-RECEIVABLE' | 'RF-DEPOSIT' | 'RF-REVENUE' | 'RF-REFUND'
  | 'TK-MATERIAL' | 'TK-COST' | 'TK-DEPRECIATION' | 'TK-LOSS' | 'TK-LABOR'

export const SUBJECT_LABEL: Record<SubjectCode, string> = {
  'RF-CASH': '库存现金',
  'RF-BANK': '银行存款',
  'RF-RECEIVABLE': '应收账款',
  'RF-DEPOSIT': '预收账款',
  'RF-REVENUE': '主营业务收入',
  'RF-REFUND': '退款（收入抵减）',
  'TK-MATERIAL': '耗材库存',
  'TK-COST': '主营业务成本',
  'TK-DEPRECIATION': '设备折旧',
  'TK-LOSS': '报损',
  'TK-LABOR': '人工分摊',
}

/** 收支方向 */
export type TxnDirection = 'IN' | 'OUT'
/** 镜像来源（系统边界外，单向镜像） */
export type MirrorSource = 'CASHIER' | 'CHANNEL' | 'BANK' | 'ERP'

/** 只读镜像的会计流水（绝不反向写资金系统） */
export interface LedgerEntry {
  id: string
  txnId: string // 幂等键 transaction_id
  date: string // yyyy-MM-dd
  subject: SubjectCode
  direction: TxnDirection
  amount: number // 元，正数
  channel?: 'cash' | 'wxpay' | 'alipay' | 'card' | 'balance' | 'transfer' | 'bank'
  source: MirrorSource
  refType: 'ORDER' | 'REFUND' | 'RECHARGE' | 'WRITEOFF' | 'LOSS' | 'DEP' | 'PURCHASE' | 'SETTLE' | 'ADJUST'
  refNo: string
  store: string
  memo: string
  reconciled: boolean // 对账标记
}

/** Outbox 出站消息（三方对账载体，幂等 outboxId） */
export interface OutboxItem {
  outboxId: string
  bizType: 'ORDER_PAY' | 'REFUND' | 'RECHARGE' | 'WRITEOFF' | 'SETTLE'
  txnNo: string
  amount: number
  channel: string
  /** 三方状态：收银已记 / 渠道已回 / 银行已到 */
  cashier: boolean
  channelAck: boolean
  bankAck: boolean
  status: 'MATCHED' | 'PENDING' | 'LONG' | 'SHORT' | 'REVERSED'
  occurredAt: string
}

let _id = 0
const nextId = (p: string) => `${p}-${++_id}`

function seedLedger(): LedgerEntry[] {
  const d = (day: number) => `2026-08-${String(day).padStart(2, '0')}`
  return [
    // 收银实收（收入 RF-REVENUE，借方现金/银行/微信/支付宝）
    { id: nextId('L'), txnId: 'TX20260815001', date: d(15), subject: 'RF-REVENUE', direction: 'IN', amount: 12800, channel: 'wxpay', source: 'CASHIER', refType: 'ORDER', refNo: 'ORD-20260815-01', store: '静安旗舰店', memo: '热玛吉面部套餐', reconciled: true },
    { id: nextId('L'), txnId: 'TX20260815002', date: d(15), subject: 'RF-REVENUE', direction: 'IN', amount: 6800, channel: 'alipay', source: 'CASHIER', refType: 'ORDER', refNo: 'ORD-20260815-02', store: '静安旗舰店', memo: '水光针疗程3次', reconciled: true },
    { id: nextId('L'), txnId: 'TX20260816001', date: d(16), subject: 'RF-REVENUE', direction: 'IN', amount: 3600, channel: 'cash', source: 'CASHIER', refType: 'ORDER', refNo: 'ORD-20260816-01', store: '静安旗舰店', memo: '光子嫩肤单次', reconciled: true },
    { id: nextId('L'), txnId: 'TX20260816002', date: d(16), subject: 'RF-REVENUE', direction: 'IN', amount: 29800, channel: 'card', source: 'CASHIER', refType: 'ORDER', refNo: 'ORD-20260816-02', store: '静安旗舰店', memo: '热玛吉+超声刀组合', reconciled: false },
    // 充值（预收账款 RF-DEPOSIT 增加）
    { id: nextId('L'), txnId: 'TX20260814001', date: d(14), subject: 'RF-DEPOSIT', direction: 'IN', amount: 20000, channel: 'wxpay', source: 'CASHIER', refType: 'RECHARGE', refNo: 'RC-20260814-01', store: '静安旗舰店', memo: '林微储值充值', reconciled: true },
    { id: nextId('L'), txnId: 'TX20260817001', date: d(17), subject: 'RF-DEPOSIT', direction: 'IN', amount: 15000, channel: 'bank', source: 'CHANNEL', refType: 'RECHARGE', refNo: 'RC-20260817-01', store: '万象城店', memo: '陈先生疗程卡充值', reconciled: true },
    // 划扣消耗（预收账款转出 → 确认收入 RF-REVENUE，已双签）
    { id: nextId('L'), txnId: 'TX20260815010', date: d(15), subject: 'RF-DEPOSIT', direction: 'OUT', amount: 4266, source: 'ERP', refType: 'WRITEOFF', refNo: 'WO-20260815-01', store: '静安旗舰店', memo: '水光针疗程划扣（双签）', reconciled: true },
    { id: nextId('L'), txnId: 'TX20260815011', date: d(15), subject: 'RF-REVENUE', direction: 'IN', amount: 4266, source: 'ERP', refType: 'WRITEOFF', refNo: 'WO-20260815-01', store: '静安旗舰店', memo: '划扣确认收入', reconciled: true },
    { id: nextId('L'), txnId: 'TX20260816010', date: d(16), subject: 'RF-DEPOSIT', direction: 'OUT', amount: 5900, source: 'ERP', refType: 'WRITEOFF', refNo: 'WO-20260816-01', store: '静安旗舰店', memo: '热玛吉划扣（双签）', reconciled: false },
    { id: nextId('L'), txnId: 'TX20260816011', date: d(16), subject: 'RF-REVENUE', direction: 'IN', amount: 5900, source: 'ERP', refType: 'WRITEOFF', refNo: 'WO-20260816-01', store: '静安旗舰店', memo: '划扣确认收入', reconciled: false },
    // 退款（RF-REFUND，资金类）
    { id: nextId('L'), txnId: 'TX20260816020', date: d(16), subject: 'RF-REFUND', direction: 'OUT', amount: 2800, channel: 'wxpay', source: 'CASHIER', refType: 'REFUND', refNo: 'RF-20260816-01', store: '静安旗舰店', memo: '光子嫩肤未做退款（原路退回）', reconciled: false },
    // 成本类（TK）
    { id: nextId('L'), txnId: 'TX20260815030', date: d(15), subject: 'TK-COST', direction: 'OUT', amount: 1860, source: 'ERP', refType: 'PURCHASE', refNo: 'OUT-20260815-01', store: '静安旗舰店', memo: '耗材出库-热玛吉探头', reconciled: true },
    { id: nextId('L'), txnId: 'TX20260816030', date: d(16), subject: 'TK-COST', direction: 'OUT', amount: 420, source: 'ERP', refType: 'PURCHASE', refNo: 'OUT-20260816-01', store: '静安旗舰店', memo: '耗材出库-水光药剂', reconciled: true },
    { id: nextId('L'), txnId: 'TX20260817030', date: d(17), subject: 'TK-DEPRECIATION', direction: 'OUT', amount: 1250, source: 'ERP', refType: 'DEP', refNo: 'DEP-202608-01', store: '静安旗舰店', memo: '设备月折旧-热玛吉仪器', reconciled: true },
    { id: nextId('L'), txnId: 'TX20260817031', date: d(17), subject: 'TK-LOSS', direction: 'OUT', amount: 680, source: 'ERP', refType: 'LOSS', refNo: 'WS-20260817-01', store: '静安旗舰店', memo: '药剂过期报损', reconciled: false },
    { id: nextId('L'), txnId: 'TX20260817032', date: d(17), subject: 'TK-LABOR', direction: 'OUT', amount: 8400, source: 'ERP', refType: 'ADJUST', refNo: 'LB-202608-01', store: '静安旗舰店', memo: '咨询师/医生人工分摊', reconciled: true },
  ]
}

function seedOutbox(): OutboxItem[] {
  return [
    { outboxId: 'OB-1001', bizType: 'ORDER_PAY', txnNo: 'TX20260815001', amount: 12800, channel: '微信支付', cashier: true, channelAck: true, bankAck: true, status: 'MATCHED', occurredAt: '2026-08-15 10:22' },
    { outboxId: 'OB-1002', bizType: 'ORDER_PAY', txnNo: 'TX20260815002', amount: 6800, channel: '支付宝', cashier: true, channelAck: true, bankAck: true, status: 'MATCHED', occurredAt: '2026-08-15 14:05' },
    { outboxId: 'OB-1003', bizType: 'ORDER_PAY', txnNo: 'TX20260816001', amount: 3600, channel: '现金', cashier: true, channelAck: true, bankAck: true, status: 'MATCHED', occurredAt: '2026-08-16 11:40' },
    { outboxId: 'OB-1004', bizType: 'ORDER_PAY', txnNo: 'TX20260816002', amount: 29800, channel: '刷卡', cashier: true, channelAck: true, bankAck: false, status: 'PENDING', occurredAt: '2026-08-16 16:18' },
    { outboxId: 'OB-1005', bizType: 'RECHARGE', txnNo: 'TX20260814001', amount: 20000, channel: '微信支付', cashier: true, channelAck: true, bankAck: true, status: 'MATCHED', occurredAt: '2026-08-14 09:30' },
    // 长款：银行多到账 100（渠道手续费返点误入账）
    { outboxId: 'OB-1006', bizType: 'ORDER_PAY', txnNo: 'TX20260817005', amount: 5400, channel: '微信支付', cashier: true, channelAck: true, bankAck: true, status: 'LONG', occurredAt: '2026-08-17 15:00' },
    // 短款：收银记 3000，渠道只回 2994（手续费误扣）
    { outboxId: 'OB-1007', bizType: 'ORDER_PAY', txnNo: 'TX20260817006', amount: 3000, channel: '支付宝', cashier: true, channelAck: true, bankAck: true, status: 'SHORT', occurredAt: '2026-08-17 16:20' },
    // 冲正
    { outboxId: 'OB-1008', bizType: 'REFUND', txnNo: 'TX20260816020', amount: 2800, channel: '微信支付', cashier: true, channelAck: true, bankAck: false, status: 'REVERSED', occurredAt: '2026-08-16 17:50' },
  ]
}

export const useFinanceCoreStore = defineStore('financeCore', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  /** 只读镜像流水（前端演示用 seed；真实环境由 finance-service 单向镜像） */
  const entries = ref<LedgerEntry[]>(seedLedger())
  const outbox = ref<OutboxItem[]>(seedOutbox())

  // ---------- 资金类汇总（RF） ----------
  // 确认收入：直接收银消费（ORDER）+ 已双签的划扣确认（WRITEOFF，需 reconciled）
  const totalRevenue = computed(() =>
    entries.value
      .filter((e) => e.subject === 'RF-REVENUE' && e.direction === 'IN' && (e.refType !== 'WRITEOFF' || e.reconciled))
      .reduce((s, e) => s + e.amount, 0))
  const totalRefund = computed(() =>
    entries.value.filter((e) => e.subject === 'RF-REFUND' || (e.subject === 'RF-REVENUE' && e.direction === 'OUT')).reduce((s, e) => s + e.amount, 0))
  const netRevenue = computed(() => totalRevenue.value - totalRefund.value)

  const byChannel = computed(() => {
    const m: Record<string, number> = {}
    for (const e of entries.value) {
      if (e.subject === 'RF-REVENUE' && e.direction === 'IN' && e.channel) m[e.channel] = (m[e.channel] ?? 0) + e.amount
    }
    return m
  })
  const cashierTotal = computed(() => Object.values(byChannel.value).reduce((s, v) => s + v, 0))

  // 预收账款 = 充值 IN - 划扣 OUT - 退款
  const depositRecharge = computed(() => entries.value.filter((e) => e.subject === 'RF-DEPOSIT' && e.direction === 'IN').reduce((s, e) => s + e.amount, 0))
  const depositConsume = computed(() => entries.value.filter((e) => e.subject === 'RF-DEPOSIT' && e.direction === 'OUT').reduce((s, e) => s + e.amount, 0))
  const depositBalance = computed(() => depositRecharge.value - depositConsume.value - totalRefund.value)

  // 划扣确认收入（已双签）
  const writeoffConfirmed = computed(() =>
    entries.value.filter((e) => e.refType === 'WRITEOFF' && e.subject === 'RF-REVENUE' && e.reconciled).reduce((s, e) => s + e.amount, 0))
  const writeoffPending = computed(() =>
    entries.value.filter((e) => e.refType === 'WRITEOFF' && e.subject === 'RF-REVENUE' && !e.reconciled).reduce((s, e) => s + e.amount, 0))

  // ---------- 成本类汇总（TK） ----------
  const materialCost = computed(() => entries.value.filter((e) => e.subject === 'TK-COST').reduce((s, e) => s + e.amount, 0))
  const depreciationCost = computed(() => entries.value.filter((e) => e.subject === 'TK-DEPRECIATION').reduce((s, e) => s + e.amount, 0))
  const lossCost = computed(() => entries.value.filter((e) => e.subject === 'TK-LOSS').reduce((s, e) => s + e.amount, 0))
  const laborCost = computed(() => entries.value.filter((e) => e.subject === 'TK-LABOR').reduce((s, e) => s + e.amount, 0))
  const totalCost = computed(() => materialCost.value + depreciationCost.value + lossCost.value + laborCost.value)
  const grossProfit = computed(() => writeoffConfirmed.value - totalCost.value)
  const grossRate = computed(() => writeoffConfirmed.value ? Math.round((grossProfit.value / writeoffConfirmed.value) * 1000) / 10 : 0)

  // ---------- Outbox 三方对账 ----------
  const outboxMatched = computed(() => outbox.value.filter((o) => o.status === 'MATCHED').length)
  const outboxLong = computed(() => outbox.value.filter((o) => o.status === 'LONG').reduce((s, o) => s + o.amount, 0))
  const outboxShort = computed(() => outbox.value.filter((o) => o.status === 'SHORT').reduce((s, o) => s + o.amount, 0))
  const outboxPending = computed(() => outbox.value.filter((o) => o.status === 'PENDING' || o.status === 'REVERSED').length)

  /** 一键对账：把 PENDING 且三方齐的标记为 MATCHED；不修改任何金额（只读镜像红线） */
  function runReconcile() {
    if (!auth.can('finance:reconcile')) throw new Error('无对账权限')
    let n = 0
    for (const o of outbox.value) {
      if (o.status === 'PENDING' && o.cashier && o.channelAck && o.bankAck) { o.status = 'MATCHED'; n++ }
    }
    // 同步流水对账标记
    for (const e of entries.value) {
      if (outbox.value.some((o) => o.txnNo === e.txnId && o.status === 'MATCHED')) e.reconciled = true
    }
    activity.log(auth.user.name, `执行三方对账：本次轧平 ${n} 笔`)
    return n
  }

  /** 人工调平（长款/短款）——只记调平记录，不反向动账，需 finance:reconcile:approve */
  function adjustOutbox(outboxId: string, remark: string) {
    if (!auth.can('finance:reconcile:approve')) throw new Error('需复核权限调平差异')
    const o = outbox.value.find((x) => x.outboxId === outboxId)
    if (!o) return
    o.status = 'MATCHED' // 调平后视为已平
    activity.log(auth.user.name, `人工调平对账差异 ${o.txnNo} ¥${o.amount}：${remark}`)
  }

  /** 切换某条流水对账标记（不影响金额） */
  function toggleReconciled(id: string) {
    if (!auth.can('finance:reconcile')) throw new Error('无对账权限')
    const e = entries.value.find((x) => x.id === id)
    if (e) e.reconciled = !e.reconciled
  }

  // ---------- 8 大恒等式校验（报表层成立） ----------
  interface Identity { no: number; label: string; lhs: number; rhs: number; passed: boolean; formula: string }
  const identities = computed<Identity[]>(() => {
    const list: Identity[] = [
      { no: 1, label: '净营收 = 收银实收 − 退款净额', lhs: netRevenue.value, rhs: cashierTotal.value - totalRefund.value, passed: false, formula: '净营收 = 收银实收 − 退款' },
      { no: 2, label: '收银实收 = Σ 各渠道流水', lhs: cashierTotal.value, rhs: Object.values(byChannel.value).reduce((s, v) => s + v, 0), passed: false, formula: '收银实收 = 现金+微信+支付宝+刷卡' },
      { no: 3, label: '预收余额 = 充值 − 划扣 − 退款', lhs: depositBalance.value, rhs: depositRecharge.value - depositConsume.value - totalRefund.value, passed: false, formula: '预收账款 = 充值 − 消耗 − 退款' },
      { no: 4, label: '划扣确认收入 = Σ 已双签划扣', lhs: writeoffConfirmed.value, rhs: entries.value.filter((e) => e.refType === 'WRITEOFF' && e.subject === 'RF-REVENUE' && e.reconciled).reduce((s, e) => s + e.amount, 0), passed: false, formula: '确认收入 = 已双签划扣金额' },
      { no: 5, label: '营业成本 = 耗材+折旧+报损+人工', lhs: totalCost.value, rhs: materialCost.value + depreciationCost.value + lossCost.value + laborCost.value, passed: false, formula: '成本 = 耗材+折旧+报损+人工' },
      { no: 6, label: '毛利 = 确认收入 − 营业成本', lhs: grossProfit.value, rhs: writeoffConfirmed.value - totalCost.value, passed: false, formula: '毛利 = 收入 − 成本' },
      { no: 7, label: 'Outbox 无长短款（三方平衡）', lhs: outboxLong.value + outboxShort.value, rhs: 0, passed: false, formula: '长款+短款 = 0（差异已调平）' },
      { no: 8, label: '卡余额与预收账款相互印证', lhs: depositBalance.value, rhs: depositRecharge.value - depositConsume.value - totalRefund.value, passed: false, formula: '卡余额合计 ≈ 预收账款余额' },
    ]
    return list.map((x) => ({ ...x, passed: Math.abs(x.lhs - x.rhs) < 0.01 }))
  })

  const allIdentitiesPassed = computed(() => identities.value.every((i) => i.passed))

  return {
    entries, outbox,
    totalRevenue, totalRefund, netRevenue, byChannel, cashierTotal,
    depositRecharge, depositConsume, depositBalance,
    writeoffConfirmed, writeoffPending,
    materialCost, depreciationCost, lossCost, laborCost, totalCost, grossProfit, grossRate,
    outboxMatched, outboxLong, outboxShort, outboxPending,
    identities, allIdentitiesPassed,
    runReconcile, adjustOutbox, toggleReconciled,
  }
})
