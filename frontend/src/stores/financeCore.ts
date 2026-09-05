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
import {
  getLedger, getCardsBalance, getOutbox, getCosts,
  markReconciled as apiMarkReconciled, markDiff as apiMarkDiff, adjustOutbox as apiAdjustOutbox,
} from '@/api/finance'
import type { LedgerEntryDTO, OutboxRecord, CardBalanceDTO, CostAggregate } from '@/api/finance'

/** 分 → 元（两位小数） */
const fen2yuan = (fen: number) => Math.round((Number(fen) || 0)) / 100
/** 元两位小数 */
const r2 = (v: number) => Math.round(v * 100) / 100

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
  /** 混合支付标记：一单多渠道收款时 true，channel 为入账额最大一笔的主渠道 */
  mixed?: boolean
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
  /** 后端 outbox_record 主键（真实 API 项有值；演示 seed 为 null，禁写） */
  rawId: number | null
  bizType: 'ORDER_PAY' | 'REFUND' | 'RECHARGE' | 'WRITEOFF' | 'SETTLE'
  txnNo: string
  amount: number
  channel: string
  /** 三方状态：收银已记 / 渠道已回 / 银行已到（真实 API 项三方回单未接入，仅收银为 true） */
  cashier: boolean
  channelAck: boolean
  bankAck: boolean
  /**
   * 状态：MATCHED 已平（后端 RECONCILED）/ PENDING 待对账 / LONG 长款 / SHORT 短款 /
   * REVERSED 冲正（以上四态为演示 seed 三方回单口径）/ DIFF 人工标记差异（后端 DIFF，待调平）/
   * ADJUSTED 已调平留痕（后端 ADJUSTED，补 ADJUST 分录）
   */
  status: 'MATCHED' | 'PENDING' | 'LONG' | 'SHORT' | 'REVERSED' | 'DIFF' | 'ADJUSTED'
  occurredAt: string
  /**
   * 可写标记：来自 finance-service 真实 outbox（B3 起支持人工标记一致/差异/调平写）为 true；
   * 演示 seed（后端整体不可用时降级，含三方回单演示态）为 false——不对演示数据发起后端写。
   */
  writable: boolean
}

/** 人工调平入参（金额前端「元」，store 提交时换算为「分」） */
export interface AdjustCmd {
  direction: 'IN' | 'OUT'
  amountYuan: number
  subject: string
  channel?: string | null
  memo?: string
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
  const base: Array<Omit<OutboxItem, 'rawId' | 'writable'>> = [
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
  return base.map((o) => ({ ...o, rawId: null, writable: false }))
}

/** finance 聚合台账 DTO → 前端台账模型（金额后端已换算为元，字段对齐） */
function adaptLedger(dto: LedgerEntryDTO): LedgerEntry {
  return {
    id: dto.id,
    txnId: dto.txnId,
    date: dto.date,
    subject: dto.subject as SubjectCode,
    direction: dto.direction,
    amount: dto.amount,
    channel: (dto.channel ?? undefined) as LedgerEntry['channel'],
    source: dto.source as MirrorSource,
    refType: dto.refType as LedgerEntry['refType'],
    refNo: dto.refNo,
    store: dto.store,
    memo: dto.memo,
    reconciled: !!dto.reconciled,
    mixed: !!dto.mixed,
  }
}

const BIZ_MAP: Record<string, OutboxItem['bizType']> = {
  ORDER: 'ORDER_PAY', ORDER_PAY: 'ORDER_PAY',
  REFUND: 'REFUND', RECHARGE: 'RECHARGE',
  WRITEOFF: 'WRITEOFF', SETTLE: 'SETTLE',
}

function mapOutboxStatus(s: string): OutboxItem['status'] {
  // B3 后端状态机：PENDING 待对账 / RECONCILED 已对账 / DIFF 人工标记差异 / ADJUSTED 已调平。
  // 兼容历史中文态（「已对账」）与演示 seed 的 MATCHED。
  switch (s) {
    case 'RECONCILED':
    case 'MATCHED':
    case '已对账':
      return 'MATCHED'
    case 'DIFF':
      return 'DIFF'
    case 'ADJUSTED':
      return 'ADJUSTED'
    default:
      return 'PENDING'
  }
}

function fmtOccurredAt(iso?: string | null): string {
  if (!iso) return '—'
  const t = new Date(iso)
  if (Number.isNaN(t.getTime())) return iso
  const p = (n: number) => String(n).padStart(2, '0')
  return `${t.getFullYear()}-${p(t.getMonth() + 1)}-${p(t.getDate())} ${p(t.getHours())}:${p(t.getMinutes())}`
}

/**
 * finance-service outbox DTO → 前端对账模型（B3 起为真实可写台账）。
 * 金额后端为「分」（退款为负），展示取绝对值换算为「元」；渠道/银行三方回单暂未接入，
 * 相应 ack 置 false（不伪造），收银事件已发生故 cashier=true；writable=true 开放人工
 * 标记一致/差异/调平（后端全审计），状态由后端状态机驱动（PENDING/RECONCILED/DIFF/ADJUSTED）。
 */
function adaptOutbox(dto: OutboxRecord): OutboxItem {
  return {
    outboxId: `OB-${dto.outboxId}`,
    rawId: dto.outboxId,
    bizType: BIZ_MAP[dto.bizType] ?? 'ORDER_PAY',
    txnNo: dto.txnNo,
    amount: Math.abs((dto.amount ?? 0) / 100),
    channel: dto.channel || '未标记渠道',
    cashier: true,
    channelAck: false,
    bankAck: false,
    status: mapOutboxStatus(dto.status),
    occurredAt: fmtOccurredAt(dto.reconciledAt ?? dto.createdAt),
    writable: true,
  }
}

export const useFinanceCoreStore = defineStore('financeCore', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  /**
   * 只读镜像流水：初始为演示 seed（开发降级），store 首次使用时 seed() 拉取
   * finance-service 读时聚合并替换；服务失败且未成功过则回落 seed。
   */
  const entries = ref<LedgerEntry[]>(seedLedger())
  /**
   * Outbox 对账台账：seed() 拉取 finance-service `/finance/outbox`（真实业务消息，writable=true，
   * B3 起支持人工标记一致/差异/调平，后端全审计）；渠道/银行三方回单暂未接入，长短款自动判定
   * 待 B6。仅当 finance-service 整体不可用（连台账都取不到）时回落演示 seed（writable=false，
   * 含三方回单演示态，可本地演示但不发起后端写）。
   */
  const outbox = ref<OutboxItem[]>([])

  /** 会员卡储值合计（元）——预收账款余额的会计真值（客户储值沉淀），null=未从 API 取得 */
  const cardStoredTotal = ref<number | null>(null)
  /** 会员卡赠送金合计（元，后端无数据源投影 0） */
  const cardGiftTotal = ref<number | null>(null)
  /** 会员卡疗程估值合计（元，剩余次数 × 单价） */
  const cardTimesValueTotal = ref<number | null>(null)
  /** 会员卡明细（finance 聚合 customer 域真实镜像；null=未取得，回落演示 seed） */
  const cards = ref<CardBalanceDTO[] | null>(null)

  /**
   * B5 成本权威源：/finance/cost 月×店四类成本聚合（Long 分）。
   * ledger 读时聚合不含 TK 成本分录，成本/毛利/毛利率必须以此为准；
   * null=未从 API 取得（离线回落 entries 演示分录的 TK-COST/TK-*）。
   */
  const costAggs = ref<CostAggregate[] | null>(null)
  /** 成本聚合四类合计（元），未取得时返回 null 以回落台账口径 */
  const costSummary = computed<{ material: number; depreciation: number; loss: number; labor: number } | null>(() => {
    const list = costAggs.value
    if (!list || list.length === 0) return null
    const sum = (pick: (a: CostAggregate) => number) =>
      list.reduce((s, a) => s + fen2yuan(pick(a)), 0)
    return {
      material: r2(sum((a) => a.material)),
      depreciation: r2(sum((a) => a.depreciation)),
      loss: r2(sum((a) => a.loss)),
      labor: r2(sum((a) => a.labor)),
    }
  })
  /** 沉睡沉淀：DORMANT（休眠）且仍有储值余额的卡——后端据末次消费判定，余额合计即沉淀，金额「元」 */
  const dormantCards = computed<CardBalanceDTO[]>(() =>
    (cards.value ?? []).filter((c) => c.status === 'DORMANT' && c.balance > 0))
  const dormantCount = computed(() => dormantCards.value.length)
  const dormantAmount = computed(() => dormantCards.value.reduce((s, c) => s + c.balance, 0))

  let seeded = false
  let seeding: Promise<void> | null = null
  /** 从 finance-service 拉取真实台账、卡余额与 outbox 镜像（幂等；force 强制刷新）；失败静默回落演示 seed */
  function seed(force = false): Promise<void> {
    if (seeding && !force) return seeding
    if (seeded && !force) return Promise.resolve()
    seeding = (async () => {
      try {
        const [ledger, bundle] = await Promise.all([getLedger(), getCardsBalance()])
        entries.value = ledger.data.map(adaptLedger)
        cardStoredTotal.value = bundle.data.storedTotal
        cardGiftTotal.value = bundle.data.giftTotal
        cardTimesValueTotal.value = bundle.data.timesValueTotal
        cards.value = bundle.data.cards ?? []
        seeded = true
      } catch (e) {
        console.error('[financeCore] 加载 finance 聚合数据失败，回落演示数据', e)
        if (!seeded) {
          entries.value = seedLedger()
          outbox.value = seedOutbox()
        }
      }
      // outbox 台账独立容错：台账成功即视为在线，outbox 拉失败则诚实置空（不回落演示三方数据）
      try {
        const ob = await getOutbox()
        outbox.value = (ob.data ?? []).map(adaptOutbox)
      } catch (e) {
        console.error('[financeCore] 加载 finance outbox 台账失败，置空（不伪造对账数据）', e)
        if (seeded) outbox.value = []
      }
      // B5 成本聚合独立容错：/finance/ledger 不含 TK 成本分录，成本/毛利以 /finance/cost 为权威源；
      // 拉失败则 costAggs 保持 null，成本回落台账演示分录（离线 seed）口径
      try {
        const cost = await getCosts()
        costAggs.value = cost.data ?? []
      } catch (e) {
        console.error('[financeCore] 加载成本聚合 /finance/cost 失败，成本回落台账口径', e)
        if (seeded) costAggs.value = null
      }
    })()
    return seeding
  }
  // store 首次被任一财务页使用时即自动拉取真实数据（失败回落 seed）
  void seed()

  // ---------- 资金类汇总（RF） ----------
  // 确认收入：直接收银消费（ORDER）+ 已双签的划扣确认（WRITEOFF，需 reconciled）
  const totalRevenue = computed(() =>
    entries.value
      .filter((e) => e.subject === 'RF-REVENUE' && e.direction === 'IN' && (e.refType !== 'WRITEOFF' || e.reconciled))
      .reduce((s, e) => s + e.amount, 0))
  const totalRefund = computed(() =>
    entries.value.filter((e) => e.subject === 'RF-REFUND' || (e.subject === 'RF-REVENUE' && e.direction === 'OUT')).reduce((s, e) => s + e.amount, 0))
  const netRevenue = computed(() => totalRevenue.value - totalRefund.value)

  // 收银实收按渠道拆分：口径与 totalRevenue 一致（含已双签划扣确认）；
  // 真实数据 order_payment 表空 → 订单无支付渠道，无渠道码者归入「未标记渠道」(__NONE__)，
  // 保证 Σ各渠道 = 收银实收（恒等式 2），不把渠道缺失的收入漏统为 0。
  const byChannel = computed(() => {
    const m: Record<string, number> = {}
    for (const e of entries.value) {
      if (e.subject === 'RF-REVENUE' && e.direction === 'IN' && (e.refType !== 'WRITEOFF' || e.reconciled)) {
        const key = e.channel ?? '__NONE__'
        m[key] = (m[key] ?? 0) + e.amount
      }
    }
    return m
  })
  const cashierTotal = computed(() => Object.values(byChannel.value).reduce((s, v) => s + v, 0))

  // 预收账款：充值 IN / 划扣 OUT 流水（本期发生额，种子库暂无充值/划扣流水→为 0）
  const depositRecharge = computed(() => entries.value.filter((e) => e.subject === 'RF-DEPOSIT' && e.direction === 'IN').reduce((s, e) => s + e.amount, 0))
  const depositConsume = computed(() => entries.value.filter((e) => e.subject === 'RF-DEPOSIT' && e.direction === 'OUT').reduce((s, e) => s + e.amount, 0))
  /**
   * 预收账款余额（负债）：会计上等于客户储值卡沉淀余额终值。
   * 种子库无充值流水台账（member_card 仅有余额终值），故以 finance 聚合的
   * 会员卡储值合计为真值；未取到卡余额时回落为演示流水滚存（充值-消耗-退款）。
   */
  const depositBalance = computed(() =>
    cardStoredTotal.value != null
      ? cardStoredTotal.value
      : depositRecharge.value - depositConsume.value - totalRefund.value)

  // 划扣确认收入（已双签）
  const writeoffConfirmed = computed(() =>
    entries.value.filter((e) => e.refType === 'WRITEOFF' && e.subject === 'RF-REVENUE' && e.reconciled).reduce((s, e) => s + e.amount, 0))
  const writeoffPending = computed(() =>
    entries.value.filter((e) => e.refType === 'WRITEOFF' && e.subject === 'RF-REVENUE' && !e.reconciled).reduce((s, e) => s + e.amount, 0))

  // ---------- 成本类汇总（TK） ----------
  // 真实环境 /finance/ledger 读时聚合不含 TK 成本分录：成本以 /finance/cost 月×店聚合为权威源
  //（耗材 TK-MATERIAL / 报损 TK-LOSS / 折旧 TK-DEPRECIATION / 人工 TK-LABOR）；
  // 离线/接口失败（costSummary 为 null）回落台账演示分录口径，
  // 其中 legacy 主体科目 TK-COST（主营业务成本）与 TK-MATERIAL（耗材库存）均计入耗材成本。
  const materialCost = computed(() =>
    costSummary.value?.material
    ?? r2(entries.value.filter((e) => e.subject === 'TK-COST' || e.subject === 'TK-MATERIAL').reduce((s, e) => s + e.amount, 0)))
  const depreciationCost = computed(() =>
    costSummary.value?.depreciation
    ?? r2(entries.value.filter((e) => e.subject === 'TK-DEPRECIATION').reduce((s, e) => s + e.amount, 0)))
  const lossCost = computed(() =>
    costSummary.value?.loss
    ?? r2(entries.value.filter((e) => e.subject === 'TK-LOSS').reduce((s, e) => s + e.amount, 0)))
  const laborCost = computed(() =>
    costSummary.value?.labor
    ?? r2(entries.value.filter((e) => e.subject === 'TK-LABOR').reduce((s, e) => s + e.amount, 0)))
  const totalCost = computed(() => r2(materialCost.value + depreciationCost.value + lossCost.value + laborCost.value))
  const grossProfit = computed(() => r2(writeoffConfirmed.value - totalCost.value))
  const grossRate = computed(() => writeoffConfirmed.value ? Math.round((grossProfit.value / writeoffConfirmed.value) * 1000) / 10 : 0)

  // ---------- Outbox 三方对账 ----------
  /** 已平笔数：已对账（MATCHED/RECONCILED）+ 已调平留痕（ADJUSTED） */
  const outboxMatched = computed(() => outbox.value.filter((o) => o.status === 'MATCHED' || o.status === 'ADJUSTED').length)
  const outboxLong = computed(() => outbox.value.filter((o) => o.status === 'LONG').reduce((s, o) => s + o.amount, 0))
  const outboxShort = computed(() => outbox.value.filter((o) => o.status === 'SHORT').reduce((s, o) => s + o.amount, 0))
  /** 待对账笔数：PENDING 待回单 / REVERSED 冲正（演示态）/ DIFF 人工标记差异待调平 */
  const outboxPending = computed(
    () => outbox.value.filter((o) => o.status === 'PENDING' || o.status === 'REVERSED' || o.status === 'DIFF').length)
  /** 差异笔数（B3 真实台账：人工标记 DIFF 待调平；演示态长短款不重复计入） */
  const outboxDiffCount = computed(() => outbox.value.filter((o) => o.status === 'DIFF').length)
  /** 真实可写台账笔数（writable=true，来自 finance-service）；0 = 演示/离线降级 */
  const outboxLiveCount = computed(() => outbox.value.filter((o) => o.writable).length)

  /** 一键对账（仅演示态三方回单本地轧平；真实台账见 markReconciled/markDiff/adjust）。
   *  真实可写项（writable）渠道/银行回单尚未接入（B6），不本地假装轧平；如存在待对账真实项，
   *  提示改走人工标记。不修改任何金额。 */
  function runReconcile(): number {
    if (!auth.can('finance:reconcile')) throw new Error('无对账权限')
    let n = 0
    for (const o of outbox.value) {
      if (o.writable) continue
      if (o.status === 'PENDING' && o.cashier && o.channelAck && o.bankAck) { o.status = 'MATCHED'; n++ }
    }
    // 同步演示流水对账标记
    for (const e of entries.value) {
      if (outbox.value.some((o) => !o.writable && o.txnNo === e.txnId && o.status === 'MATCHED')) e.reconciled = true
    }
    const livePending = outbox.value.filter((o) => o.writable && o.status === 'PENDING').length
    activity.log(auth.user.name, `执行三方对账：演示轧平 ${n} 笔${livePending ? `，真实台账 ${livePending} 笔待人工标记` : ''}`)
    if (!n && livePending) throw new Error(`三方回单未接入：真实台账 ${livePending} 笔待人工「标记一致 / 标记差异」`)
    return n
  }

  /** 取真实可写项（rawId 必须存在）；演示/离线项禁止后端写 */
  function requireWritable(outboxId: string): OutboxItem {
    const o = outbox.value.find((x) => x.outboxId === outboxId)
    if (!o) throw new Error('对账记录不存在')
    if (!o.writable || o.rawId == null) throw new Error('演示数据不可写：真实对账台账由 finance-service 提供')
    return o
  }

  /** 人工标记「三方一致」（PENDING → RECONCILED，后端全审计），写后强制刷新台账 */
  async function markReconciled(outboxId: string, remark?: string): Promise<void> {
    if (!auth.can('finance:reconcile')) throw new Error('无对账权限')
    const o = requireWritable(outboxId)
    await apiMarkReconciled(o.rawId!, remark)
    activity.log(auth.user.name, `人工对账标记一致 ${o.txnNo} ¥${o.amount}${remark ? `：${remark}` : ''}`)
    await seed(true)
  }

  /** 人工标记「存在差异」（PENDING → DIFF，待调平，后端全审计），写后强制刷新台账 */
  async function markDiff(outboxId: string, remark?: string): Promise<void> {
    if (!auth.can('finance:reconcile')) throw new Error('无对账权限')
    const o = requireWritable(outboxId)
    await apiMarkDiff(o.rawId!, remark)
    activity.log(auth.user.name, `人工对账标记差异 ${o.txnNo} ¥${o.amount}${remark ? `：${remark}` : ''}`)
    await seed(true)
  }

  /** 人工调平（DIFF → ADJUSTED，补 ADJUST 分录，不反向动业务账；需 finance:reconcile:approve）。
   *  演示态（writable=false）仅本地置已平用于演示；真实台账调后端写并刷新。 */
  async function adjustOutbox(outboxId: string, cmd: AdjustCmd | string, reviewer?: string): Promise<void> {
    if (!auth.can('finance:reconcile:approve')) throw new Error('需复核权限调平差异')
    const o = outbox.value.find((x) => x.outboxId === outboxId)
    if (!o) return
    // 演示/离线降级项（或旧签名传 remark 字符串）：仅本地置已平，不发起后端写
    if (typeof cmd === 'string' || !o.writable || o.rawId == null) {
      const remark = typeof cmd === 'string' ? cmd : (cmd.memo ?? '')
      o.status = 'MATCHED'
      activity.log(auth.user.name, `人工调平对账差异 ${o.txnNo} ¥${o.amount}：${remark}${reviewer ? `（复核：${reviewer}）` : ''}`)
      return
    }
    const amountFen = Math.round(cmd.amountYuan * 100)
    if (!Number.isFinite(cmd.amountYuan) || amountFen <= 0) throw new Error('调平金额必须为正数（元）')
    const memo = `${cmd.memo ?? '差异调平'}${reviewer ? `（复核：${reviewer}）` : ''}`
    await apiAdjustOutbox(o.rawId, {
      direction: cmd.direction,
      amountFen,
      subject: cmd.subject,
      channel: cmd.channel ?? null,
      memo,
    })
    activity.log(auth.user.name, `人工调平对账差异 ${o.txnNo} ¥${cmd.amountYuan}（${cmd.subject}/${cmd.direction}）：${memo}`)
    await seed(true)
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
      // 预收负债存量真值 = 会员卡储值合计（无充值流水台账时不适用流水滚存，与恒等式 8 同锚）；
      // 未取到卡余额（演示/降级）时回落流水滚存公式。
      { no: 3, label: '预收账款余额 = 会员卡储值沉淀合计', lhs: depositBalance.value, rhs: cardStoredTotal.value ?? (depositRecharge.value - depositConsume.value - totalRefund.value), passed: false, formula: '预收账款 = 卡储值合计（客户储值沉淀）' },
      { no: 4, label: '划扣确认收入 = Σ 已双签划扣', lhs: writeoffConfirmed.value, rhs: entries.value.filter((e) => e.refType === 'WRITEOFF' && e.subject === 'RF-REVENUE' && e.reconciled).reduce((s, e) => s + e.amount, 0), passed: false, formula: '确认收入 = 已双签划扣金额' },
      { no: 5, label: '营业成本 = 耗材+折旧+报损+人工', lhs: totalCost.value, rhs: materialCost.value + depreciationCost.value + lossCost.value + laborCost.value, passed: false, formula: '成本 = 耗材+折旧+报损+人工' },
      { no: 6, label: '毛利 = 确认收入 − 营业成本', lhs: grossProfit.value, rhs: writeoffConfirmed.value - totalCost.value, passed: false, formula: '毛利 = 收入 − 成本' },
      { no: 7, label: 'Outbox 无长短款（三方平衡）', lhs: outboxLong.value + outboxShort.value, rhs: 0, passed: false, formula: '长款+短款 = 0（差异已调平）' },
      { no: 8, label: '卡余额与预收账款相互印证', lhs: depositBalance.value, rhs: cardStoredTotal.value ?? (depositRecharge.value - depositConsume.value - totalRefund.value), passed: false, formula: '卡储值合计 ≈ 预收账款余额' },
    ]
    return list.map((x) => ({ ...x, passed: Math.abs(x.lhs - x.rhs) < 0.01 }))
  })

  const allIdentitiesPassed = computed(() => identities.value.every((i) => i.passed))

  return {
    entries, outbox,
    totalRevenue, totalRefund, netRevenue, byChannel, cashierTotal,
    depositRecharge, depositConsume, depositBalance,
    cardStoredTotal, cardGiftTotal, cardTimesValueTotal, cards, dormantCards, dormantCount, dormantAmount,
    writeoffConfirmed, writeoffPending,
    materialCost, depreciationCost, lossCost, laborCost, totalCost, grossProfit, grossRate,
    outboxMatched, outboxLong, outboxShort, outboxPending, outboxDiffCount, outboxLiveCount,
    identities, allIdentitiesPassed,
    runReconcile, markReconciled, markDiff, adjustOutbox, toggleReconciled,
    seed,
  }
})
