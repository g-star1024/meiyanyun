// ============================================================
// finCommission —— M6-08 咨询师提成
// 业财一体红线：提成仅基于"已双签划扣确认收入"镜像试算与审批，
// 提成发放走外部薪酬系统，本 store 只登记提成单、勾稽 financeCore.writeoffConfirmed，
// 绝不直接动账。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

export type CommissionStatus = 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'PAID' | 'REJECTED'
export type CommissionBase = 'ORDER' | 'WRITEOFF' | 'RECHARGE'
export type CommRole = 'CONSULTANT' | 'DOCTOR' | 'BEAUTICIAN'

export interface CommissionRule {
  id: string
  name: string
  base: CommissionBase
  /** 阶梯：业绩下限 ~ 比例 */
  tiers: { min: number; rate: number; label: string }[]
  /** 适用岗位 */
  role: CommRole | 'ALL'
  active: boolean
}

export interface CommissionItem {
  id: string
  period: string            // yyyy-MM 归属期间
  consultantId: string
  consultantName: string
  title: string
  ruleName: string
  /** 业绩基数（来自镜像：已双签划扣确认金额） */
  baseAmount: number
  /** 阶梯明细 */
  tiers: { label: string; amount: number; rate: number; commission: number }[]
  commission: number        // 提成合计
  status: CommissionStatus
  orderCount: number
  approver?: string
  approvedAt?: string
  paidAt?: string
  remark?: string
}

const STATUS_LABEL: Record<CommissionStatus, string> = {
  DRAFT: '待提交',
  SUBMITTED: '待审批',
  APPROVED: '已审批待发放',
  PAID: '已发放',
  REJECTED: '已驳回',
}
const STATUS_PILL: Record<CommissionStatus, 'warning' | 'primary' | 'info' | 'success' | 'danger'> = {
  DRAFT: 'warning',
  SUBMITTED: 'primary',
  APPROVED: 'info',
  PAID: 'success',
  REJECTED: 'danger',
}
const BASE_LABEL: Record<CommissionBase, string> = {
  ORDER: '成交额',
  WRITEOFF: '划扣确认收入',
  RECHARGE: '充值额',
}

export const useFinCommissionStore = defineStore('finCommission', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const rules = ref<CommissionRule[]>([
    {
      id: 'r1', name: '咨询师标准阶梯', base: 'WRITEOFF', role: 'CONSULTANT', active: true,
      tiers: [
        { min: 0, rate: 0.06, label: '基础 6%' },
        { min: 80000, rate: 0.08, label: '达标 8%' },
        { min: 150000, rate: 0.10, label: '卓越 10%' },
        { min: 250000, rate: 0.12, label: '冠军 12%' },
      ],
    },
    {
      id: 'r2', name: '医生操作提成', base: 'WRITEOFF', role: 'DOCTOR', active: true,
      tiers: [
        { min: 0, rate: 0.10, label: '固定 10%' },
        { min: 100000, rate: 0.12, label: '超额 12%' },
      ],
    },
  ])

  const items = ref<CommissionItem[]>([])
  const filterStatus = ref<CommissionStatus | 'ALL'>('ALL')
  const filterPeriod = ref<string>('ALL')

  const totalCommission = computed(() => items.value.reduce((s, i) => s + i.commission, 0))
  const approvedCommission = computed(() => items.value.filter((i) => i.status === 'APPROVED' || i.status === 'PAID').reduce((s, i) => s + i.commission, 0))
  const pendingCommission = computed(() => items.value.filter((i) => i.status === 'SUBMITTED').reduce((s, i) => s + i.commission, 0))
  const paidCommission = computed(() => items.value.filter((i) => i.status === 'PAID').reduce((s, i) => s + i.commission, 0))

  const periods = computed(() => {
    const set = new Set(items.value.map((i) => i.period))
    return [...set].sort().reverse()
  })

  const filtered = computed(() => {
    let list = items.value
    if (filterStatus.value !== 'ALL') list = list.filter((i) => i.status === filterStatus.value)
    if (filterPeriod.value !== 'ALL') list = list.filter((i) => i.period === filterPeriod.value)
    return [...list].sort((a, b) => (a.period < b.period ? 1 : a.consultantName.localeCompare(b.consultantName)))
  })

  function get(id: string) {
    return items.value.find((i) => i.id === id)
  }

  function activeRule(role: CommRole) {
    return rules.value.find((r) => r.active && (r.role === role || r.role === 'ALL'))
  }

  /** 按阶梯计算 */
  function calcTiers(rule: CommissionRule, amount: number) {
    const tiers: { label: string; amount: number; rate: number; commission: number }[] = []
    for (let i = 0; i < rule.tiers.length; i++) {
      const cur = rule.tiers[i]
      const next = rule.tiers[i + 1]
      const upper = next ? next.min : Infinity
      if (amount > cur.min) {
        const seg = Math.min(amount, upper) - cur.min
        tiers.push({ label: cur.label, amount: seg, rate: cur.rate, commission: Math.round(seg * cur.rate * 100) / 100 })
      }
    }
    return tiers
  }

  /** 生成/重算某期间某咨询师的提成单（草稿） */
  function generate(period: string, consultantId: string, consultantName: string, title: string, role: CommRole, baseAmount: number, orderCount: number): CommissionItem | null {
    if (!auth.can('finance:commission:edit')) {
      console.warn('[finCommission] 无 commission:edit 权限')
      return null
    }
    const rule = activeRule(role)
    if (!rule) return null
    const tiers = calcTiers(rule, baseAmount)
    const commission = Math.round(tiers.reduce((s, t) => s + t.commission, 0) * 100) / 100
    const existing = items.value.find((i) => i.period === period && i.consultantId === consultantId)
    if (existing) {
      existing.ruleName = rule.name
      existing.baseAmount = baseAmount
      existing.orderCount = orderCount
      existing.tiers = tiers
      existing.commission = commission
      if (existing.status !== 'PAID') existing.status = 'DRAFT'
      activity.log(auth.user.name, `重算 ${period} ${consultantName} 提成：¥${commission}`)
      return existing
    }
    const item: CommissionItem = {
      id: nextId('cm'), period, consultantId, consultantName, title,
      ruleName: rule.name, baseAmount, tiers, commission, status: 'DRAFT', orderCount,
    }
    items.value.unshift(item)
    activity.log(auth.user.name, `生成 ${period} ${consultantName} 提成单：¥${commission}`)
    return item
  }

  function submit(id: string): boolean {
    const it = items.value.find((i) => i.id === id)
    if (!it || (it.status !== 'DRAFT' && it.status !== 'REJECTED') || !auth.can('finance:commission:edit')) return false
    it.status = 'SUBMITTED'
    activity.log(auth.user.name, `提交提成单 ${it.consultantName} ${it.period}：¥${it.commission}`)
    return true
  }

  function approve(id: string, remark?: string): boolean {
    const it = items.value.find((i) => i.id === id)
    if (!it || it.status !== 'SUBMITTED' || !auth.can('finance:commission:approve')) return false
    it.status = 'APPROVED'
    it.approver = auth.user.name
    it.approvedAt = new Date().toISOString()
    if (remark) it.remark = remark
    activity.log(auth.user.name, `审批通过提成单 ${it.consultantName}：¥${it.commission}`)
    return true
  }

  function reject(id: string, reason: string): boolean {
    const it = items.value.find((i) => i.id === id)
    if (!it || it.status !== 'SUBMITTED' || !auth.can('finance:commission:approve')) return false
    it.status = 'REJECTED'
    it.remark = reason
    activity.log(auth.user.name, `驳回提成单 ${it.consultantName}：${reason}`)
    return true
  }

  /** 标记已发放（外部薪酬系统回传，仅镜像） */
  function markPaid(id: string): boolean {
    const it = items.value.find((i) => i.id === id)
    if (!it || it.status === 'PAID' || !auth.can('finance:commission:approve')) return false
    it.status = 'PAID'
    it.paidAt = new Date().toISOString()
    activity.log(auth.user.name, `提成单 ${it.consultantName} 已发放（薪酬系统回传）：¥${it.commission}`)
    return true
  }

  // ===== 种子 =====
  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    const data: Array<Omit<CommissionItem, 'id'>> = [
      {
        period: '2026-08', consultantId: 'u01', consultantName: '苏晴', title: '资深咨询师',
        ruleName: '咨询师标准阶梯', baseAmount: 186000, orderCount: 24,
        tiers: [
          { label: '基础 6%', amount: 80000, rate: 0.06, commission: 4800 },
          { label: '达标 8%', amount: 70000, rate: 0.08, commission: 5600 },
          { label: '卓越 10%', amount: 36000, rate: 0.1, commission: 3600 },
        ],
        commission: 14000, status: 'SUBMITTED',
      },
      {
        period: '2026-08', consultantId: 'u02', consultantName: '李娜', title: '咨询师',
        ruleName: '咨询师标准阶梯', baseAmount: 92000, orderCount: 18,
        tiers: [
          { label: '基础 6%', amount: 80000, rate: 0.06, commission: 4800 },
          { label: '达标 8%', amount: 12000, rate: 0.08, commission: 960 },
        ],
        commission: 5760, status: 'APPROVED', approver: '陈雅琳（店长）', approvedAt: '2026-08-20T10:00',
      },
      {
        period: '2026-08', consultantId: 'u03', consultantName: '王诗涵', title: '高级咨询师',
        ruleName: '咨询师标准阶梯', baseAmount: 268000, orderCount: 31,
        tiers: [
          { label: '基础 6%', amount: 80000, rate: 0.06, commission: 4800 },
          { label: '达标 8%', amount: 70000, rate: 0.08, commission: 5600 },
          { label: '卓越 10%', amount: 100000, rate: 0.1, commission: 10000 },
          { label: '冠军 12%', amount: 18000, rate: 0.12, commission: 2160 },
        ],
        commission: 22560, status: 'PAID', approver: '陈雅琳（店长）', approvedAt: '2026-08-18T10:00', paidAt: '2026-08-25T09:00',
      },
      {
        period: '2026-08', consultantId: 'u04', consultantName: '周慧敏', title: '咨询师',
        ruleName: '咨询师标准阶梯', baseAmount: 45000, orderCount: 9,
        tiers: [
          { label: '基础 6%', amount: 45000, rate: 0.06, commission: 2700 },
        ],
        commission: 2700, status: 'DRAFT',
      },
      {
        period: '2026-08', consultantId: 'u05', consultantName: '王医生', title: '主诊医生',
        ruleName: '医生操作提成', baseAmount: 156000, orderCount: 22,
        tiers: [
          { label: '固定 10%', amount: 100000, rate: 0.1, commission: 10000 },
          { label: '超额 12%', amount: 56000, rate: 0.12, commission: 6720 },
        ],
        commission: 16720, status: 'DRAFT',
      },
      {
        period: '2026-07', consultantId: 'u01', consultantName: '苏晴', title: '资深咨询师',
        ruleName: '咨询师标准阶梯', baseAmount: 168000, orderCount: 22,
        tiers: [
          { label: '基础 6%', amount: 80000, rate: 0.06, commission: 4800 },
          { label: '达标 8%', amount: 70000, rate: 0.08, commission: 5600 },
          { label: '卓越 10%', amount: 18000, rate: 0.1, commission: 1800 },
        ],
        commission: 12200, status: 'PAID', approver: '陈雅琳（店长）', approvedAt: '2026-07-28T10:00', paidAt: '2026-08-05T09:00',
      },
      {
        period: '2026-07', consultantId: 'u03', consultantName: '王诗涵', title: '高级咨询师',
        ruleName: '咨询师标准阶梯', baseAmount: 245000, orderCount: 28,
        tiers: [
          { label: '基础 6%', amount: 80000, rate: 0.06, commission: 4800 },
          { label: '达标 8%', amount: 70000, rate: 0.08, commission: 5600 },
          { label: '卓越 10%', amount: 95000, rate: 0.1, commission: 9500 },
        ],
        commission: 19900, status: 'PAID', approver: '陈雅琳（店长）', approvedAt: '2026-07-28T10:00', paidAt: '2026-08-05T09:00',
      },
    ]
    data.forEach((d) => items.value.push({ id: nextId('cm'), ...d }))
  }

  return {
    rules, items, filterStatus, filterPeriod,
    totalCommission, approvedCommission, pendingCommission, paidCommission,
    periods, filtered, get, generate, submit, approve, reject, markPaid, calcTiers, activeRule, seed,
    STATUS_LABEL, STATUS_PILL, BASE_LABEL,
  }
})
