// ============================================================
// finSettlement —— M6-05 分账结算
// 业财一体红线：分账结算仅镜像展示"应收分账明细 + 结算批次状态"，
// 真实资金分账由支付渠道/银行完成，本 store 只登记分账单、勾稽 Outbox、
// 标记"已生成结算单/已回单"，绝不反向触达资金池。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

/** 分账接收方类型 */
export type SettleRole = 'PLATFORM' | 'STORE' | 'DOCTOR' | 'CONSULTANT' | 'CHANNEL' | 'TAX'
export type SettleStatus = 'PENDING' | 'CALCULATED' | 'SUBMITTED' | 'CONFIRMED' | 'FAILED'
export type SettleBizType = 'ORDER' | 'RECHARGE' | 'REFUND' | 'WRITEOFF'

export interface SettleItem {
  id: string
  batchNo: string          // 结算批次号
  date: string             // yyyy-MM-dd 业务日期
  role: SettleRole         // 分账接收方
  receiverName: string     // 接收方名称
  bizType: SettleBizType
  refNo: string            // 关联业务单号
  orderAmount: number      // 订单/业务金额
  /** 分账金额明细 */
  amount: number           // 本分账方应得
  ratio: number            // 分账比例 0~1
  channel: string          // 支付渠道
  outboxRef?: string       // 关联 Outbox txnNo（三方对账勾稽）
  status: SettleStatus
  submittedAt?: string
  confirmedAt?: string
  remark?: string
}

const ROLE_LABEL: Record<SettleRole, string> = {
  PLATFORM: '平台',
  STORE: '门店',
  DOCTOR: '医生',
  CONSULTANT: '咨询师',
  CHANNEL: '渠道分润',
  TAX: '代扣税费',
}
const BIZ_LABEL: Record<SettleBizType, string> = {
  ORDER: '消费订单',
  RECHARGE: '充值',
  REFUND: '退款',
  WRITEOFF: '划扣确认',
}
const STATUS_LABEL: Record<SettleStatus, string> = {
  PENDING: '待计算',
  CALCULATED: '已计算待提交',
  SUBMITTED: '已提交待回单',
  CONFIRMED: '已回单确认',
  FAILED: '分账失败',
}
const STATUS_PILL: Record<SettleStatus, 'warning' | 'primary' | 'info' | 'success' | 'danger'> = {
  PENDING: 'warning',
  CALCULATED: 'primary',
  SUBMITTED: 'info',
  CONFIRMED: 'success',
  FAILED: 'danger',
}

export const useFinSettlementStore = defineStore('finSettlement', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const items = ref<SettleItem[]>([])
  const filterStatus = ref<SettleStatus | 'ALL'>('ALL')
  const filterRole = ref<SettleRole | 'ALL'>('ALL')
  const keyword = ref('')

  const totalAmount = computed(() => items.value.reduce((s, i) => s + i.amount, 0))
  const confirmedAmount = computed(() => items.value.filter((i) => i.status === 'CONFIRMED').reduce((s, i) => s + i.amount, 0))
  const pendingAmount = computed(() => items.value.filter((i) => i.status === 'PENDING' || i.status === 'CALCULATED' || i.status === 'SUBMITTED').reduce((s, i) => s + i.amount, 0))
  const failedCount = computed(() => items.value.filter((i) => i.status === 'FAILED').length)

  const byRole = computed(() => {
    const m: Record<string, number> = {}
    for (const it of items.value) m[it.role] = (m[it.role] ?? 0) + it.amount
    return m
  })

  const filtered = computed(() => {
    let list = items.value
    if (filterStatus.value !== 'ALL') list = list.filter((i) => i.status === filterStatus.value)
    if (filterRole.value !== 'ALL') list = list.filter((i) => i.role === filterRole.value)
    const kw = keyword.value.trim().toLowerCase()
    if (kw) {
      list = list.filter(
        (i) =>
          i.batchNo.toLowerCase().includes(kw) ||
          i.receiverName.toLowerCase().includes(kw) ||
          i.refNo.toLowerCase().includes(kw),
      )
    }
    return [...list].sort((a, b) => (a.date < b.date ? 1 : a.date > b.date ? -1 : a.batchNo < b.batchNo ? 1 : -1))
  })

  function get(id: string) {
    return items.value.find((i) => i.id === id)
  }

  /**
   * 计算/重新计算批次分账（仅生成本地镜像分账单，不发起真实资金分账）。
   * 真实分账由支付渠道异步执行，回单后由 confirm 标记。
   */
  function calculate(date: string): number {
    if (!auth.can('finance:settlement:edit')) {
      console.warn('[finSettlement] 无 settlement:edit 权限')
      return 0
    }
    // 幂等：把该日 PENDING 标记为 CALCULATED
    let n = 0
    for (const it of items.value) {
      if (it.date === date && it.status === 'PENDING') {
        it.status = 'CALCULATED'
        n++
      }
    }
    activity.log(auth.user.name, `计算 ${date} 分账批次：${n} 条已计算`)
    return n
  }

  /** 提交分账（镜像：把已计算单标记为已提交，真实渠道分账在外部） */
  function submitBatch(batchNo: string): number {
    if (!auth.can('finance:settlement:edit')) return 0
    let n = 0
    const now = new Date().toISOString()
    for (const it of items.value) {
      if (it.batchNo === batchNo && it.status === 'CALCULATED') {
        it.status = 'SUBMITTED'
        it.submittedAt = now
        n++
      }
    }
    activity.log(auth.user.name, `提交分账批次 ${batchNo}：${n} 条已提交渠道`)
    return n
  }

  /** 渠道回单确认（镜像登记，不触达资金） */
  function confirm(id: string, remark?: string): boolean {
    const it = items.value.find((i) => i.id === id)
    if (!it || it.status !== 'SUBMITTED' || !auth.can('finance:settlement:approve')) return false
    it.status = 'CONFIRMED'
    it.confirmedAt = new Date().toISOString()
    if (remark) it.remark = remark
    activity.log(auth.user.name, `分账单 ${it.batchNo} 已回单确认：¥${it.amount} → ${it.receiverName}`)
    return true
  }

  /** 标记分账失败（渠道回单异常），不重试动账，仅镜像状态 */
  function markFailed(id: string, reason: string): boolean {
    const it = items.value.find((i) => i.id === id)
    if (!it || (it.status !== 'SUBMITTED' && it.status !== 'CALCULATED')) return false
    it.status = 'FAILED'
    it.remark = reason
    activity.log(auth.user.name, `分账单 ${it.batchNo} 失败：${reason}`)
    return true
  }

  // ===== 种子 =====
  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    const d = (day: number) => `2026-08-${String(day).padStart(2, '0')}`
    const data: Array<Omit<SettleItem, 'id'>> = [
      // 8/15 已全部回单
      { batchNo: 'STL-20260815-01', date: d(15), role: 'PLATFORM', receiverName: '美研云平台（技术服务费）', bizType: 'ORDER', refNo: 'ORD-20260815-01', orderAmount: 12800, amount: 640, ratio: 0.05, channel: '微信支付', outboxRef: 'TX20260815001', status: 'CONFIRMED', submittedAt: '2026-08-15T11:00', confirmedAt: '2026-08-15T14:00' },
      { batchNo: 'STL-20260815-01', date: d(15), role: 'STORE', receiverName: '静安旗舰店', bizType: 'ORDER', refNo: 'ORD-20260815-01', orderAmount: 12800, amount: 10880, ratio: 0.85, channel: '微信支付', outboxRef: 'TX20260815001', status: 'CONFIRMED', submittedAt: '2026-08-15T11:00', confirmedAt: '2026-08-15T14:00' },
      { batchNo: 'STL-20260815-01', date: d(15), role: 'CONSULTANT', receiverName: '苏晴（咨询师）', bizType: 'ORDER', refNo: 'ORD-20260815-01', orderAmount: 12800, amount: 768, ratio: 0.06, channel: '微信支付', outboxRef: 'TX20260815001', status: 'CONFIRMED', submittedAt: '2026-08-15T11:00', confirmedAt: '2026-08-15T14:00' },
      { batchNo: 'STL-20260815-01', date: d(15), role: 'TAX', receiverName: '代扣个税', bizType: 'ORDER', refNo: 'ORD-20260815-01', orderAmount: 12800, amount: 512, ratio: 0.04, channel: '微信支付', outboxRef: 'TX20260815001', status: 'CONFIRMED', submittedAt: '2026-08-15T11:00', confirmedAt: '2026-08-15T14:00' },
      // 8/16 已提交待回单
      { batchNo: 'STL-20260816-01', date: d(16), role: 'PLATFORM', receiverName: '美研云平台（技术服务费）', bizType: 'ORDER', refNo: 'ORD-20260816-02', orderAmount: 29800, amount: 1490, ratio: 0.05, channel: '刷卡', outboxRef: 'TX20260816002', status: 'SUBMITTED', submittedAt: '2026-08-16T17:00' },
      { batchNo: 'STL-20260816-01', date: d(16), role: 'STORE', receiverName: '静安旗舰店', bizType: 'ORDER', refNo: 'ORD-20260816-02', orderAmount: 29800, amount: 25330, ratio: 0.85, channel: '刷卡', outboxRef: 'TX20260816002', status: 'SUBMITTED', submittedAt: '2026-08-16T17:00' },
      { batchNo: 'STL-20260816-01', date: d(16), role: 'DOCTOR', receiverName: '王医生（主诊）', bizType: 'ORDER', refNo: 'ORD-20260816-02', orderAmount: 29800, amount: 1788, ratio: 0.06, channel: '刷卡', outboxRef: 'TX20260816002', status: 'SUBMITTED', submittedAt: '2026-08-16T17:00' },
      { batchNo: 'STL-20260816-01', date: d(16), role: 'CONSULTANT', receiverName: '苏晴（咨询师）', bizType: 'ORDER', refNo: 'ORD-20260816-02', orderAmount: 29800, amount: 1192, ratio: 0.04, channel: '刷卡', outboxRef: 'TX20260816002', status: 'SUBMITTED', submittedAt: '2026-08-16T17:00' },
      // 8/17 已计算待提交
      { batchNo: 'STL-20260817-01', date: d(17), role: 'PLATFORM', receiverName: '美研云平台', bizType: 'ORDER', refNo: 'ORD-20260817-05', orderAmount: 5400, amount: 270, ratio: 0.05, channel: '微信支付', status: 'CALCULATED' },
      { batchNo: 'STL-20260817-01', date: d(17), role: 'STORE', receiverName: '静安旗舰店', bizType: 'ORDER', refNo: 'ORD-20260817-05', orderAmount: 5400, amount: 4590, ratio: 0.85, channel: '微信支付', status: 'CALCULATED' },
      { batchNo: 'STL-20260817-01', date: d(17), role: 'CHANNEL', receiverName: '美团到店（分润）', bizType: 'ORDER', refNo: 'ORD-20260817-05', orderAmount: 5400, amount: 540, ratio: 0.1, channel: '微信支付', status: 'CALCULATED' },
      // 8/17 充值分账（预收不确认收入，只登记资金归属）
      { batchNo: 'STL-20260817-02', date: d(17), role: 'STORE', receiverName: '万象城店', bizType: 'RECHARGE', refNo: 'RC-20260817-01', orderAmount: 15000, amount: 15000, ratio: 1, channel: '银行转账', status: 'PENDING' },
      // 失败单
      { batchNo: 'STL-20260816-02', date: d(16), role: 'CONSULTANT', receiverName: '李娜（咨询师）', bizType: 'ORDER', refNo: 'ORD-20260816-09', orderAmount: 3600, amount: 216, ratio: 0.06, channel: '支付宝', status: 'FAILED', submittedAt: '2026-08-16T18:00', remark: '接收方账户信息异常，需线下核对后重试' },
    ]
    data.forEach((d) => items.value.push({ id: nextId('stl'), ...d }))
  }

  return {
    items, filterStatus, filterRole, keyword,
    totalAmount, confirmedAmount, pendingAmount, failedCount, byRole, filtered,
    get, calculate, submitBatch, confirm, markFailed, seed,
    ROLE_LABEL, BIZ_LABEL, STATUS_LABEL, STATUS_PILL,
  }
})
