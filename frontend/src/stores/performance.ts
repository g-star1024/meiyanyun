// ============================================================
// 员工绩效看板 store（M2-07）
// 咨询师 / 医生本月业绩、目标完成率、提成。
// 对齐 list-detail 范式：seed ≥6、computed、action、nextId、activity.log。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

export type StaffRole = 'CONSULTANT' | 'DOCTOR' | 'BEAUTICIAN'
export type PerformanceStatus = 'ON_DUTY' | 'LEAVE' | 'PROBATION'

export interface PerformanceStaff {
  id: string
  name: string
  role: StaffRole
  title: string
  avatarLetter: string
  target: number          // 本月业绩目标（元）
  actual: number          // 本月实际业绩（元）
  orders: number          // 成交单数
  commissionRate: number  // 提成比例 0~1
  status: PerformanceStatus
  joinedAt: string        // 入职日期
  trend: number[]         // 近 6 个月业绩（用于迷你图）
}

const ROLE_LABEL: Record<StaffRole, string> = {
  CONSULTANT: '咨询师',
  DOCTOR: '医生',
  BEAUTICIAN: '美容师',
}

const STATUS_LABEL: Record<PerformanceStatus, string> = {
  ON_DUTY: '在岗',
  LEAVE: '休假',
  PROBATION: '试用期',
}

const STATUS_PILL: Record<PerformanceStatus, 'success' | 'warning' | 'info'> = {
  ON_DUTY: 'success',
  LEAVE: 'warning',
  PROBATION: 'info',
}

// 提成阶梯：业绩越高比例越高（本项目采用固定字段 commissionRate 作为当月实际比例，
// 阶梯仅用于"提成试算"按钮展示若按新比例重算的差额。）
const COMMISSION_TIERS = [
  { min: 0, rate: 0.06, label: '基础 6%' },
  { min: 80000, rate: 0.08, label: '达标 8%' },
  { min: 150000, rate: 0.10, label: '卓越 10%' },
  { min: 250000, rate: 0.12, label: '冠军 12%' },
]

export const usePerformanceStore = defineStore('performance', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const staff = ref<PerformanceStaff[]>([])
  const filterRole = ref<StaffRole | 'ALL'>('ALL')
  const period = ref<'THIS_MONTH' | 'LAST_MONTH'>('THIS_MONTH')

  const onDuty = computed(() => staff.value.filter((s) => s.status === 'ON_DUTY'))
  const filtered = computed(() => {
    let list = staff.value
    if (filterRole.value !== 'ALL') list = list.filter((s) => s.role === filterRole.value)
    return [...list].sort((a, b) => b.actual - a.actual)
  })

  const totalActual = computed(() => staff.value.reduce((s, x) => s + x.actual, 0))
  const totalTarget = computed(() => staff.value.reduce((s, x) => s + x.target, 0))
  const achievement = computed(() =>
    totalTarget.value > 0 ? Math.round((totalActual.value / totalTarget.value) * 100) : 0,
  )
  const topStaff = computed<PerformanceStaff | null>(() => {
    if (!staff.value.length) return null
    return [...staff.value].sort((a, b) => b.actual - a.actual)[0]
  })

  function get(id: string) {
    return staff.value.find((s) => s.id === id)
  }

  function completion(s: PerformanceStaff) {
    return s.target > 0 ? Math.round((s.actual / s.target) * 100) : 0
  }
  function commission(s: PerformanceStaff) {
    return Math.round(s.actual * s.commissionRate)
  }
  function tierFor(amount: number) {
    let t = COMMISSION_TIERS[0]
    for (const tier of COMMISSION_TIERS) if (amount >= tier.min) t = tier
    return t
  }

  function updateTarget(id: string, target: number): boolean {
    const s = staff.value.find((x) => x.id === id)
    if (!s || !auth.can('performance:edit')) return false
    s.target = Math.max(0, Math.round(target))
    activity.log(auth.user.name, `调整 ${s.name} 本月业绩目标为 ¥${s.target.toLocaleString()}`, s.id)
    return true
  }

  function simulateCommission(id: string, amount: number): { rate: number; commission: number; delta: number; label: string } | null {
    const s = staff.value.find((x) => x.id === id)
    if (!s) return null
    const t = tierFor(amount)
    const newCommission = Math.round(amount * t.rate)
    const baseline = commission(s)
    return { rate: t.rate, commission: newCommission, delta: newCommission - baseline, label: t.label }
  }

  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    const data: Array<Omit<PerformanceStaff, 'id'>> = [
      { name: '林微', role: 'CONSULTANT', title: '资深咨询师', avatarLetter: '林', target: 200000, actual: 246800, orders: 38, commissionRate: 0.10, status: 'ON_DUTY', joinedAt: '2022-03-15', trend: [168000, 182000, 175000, 201000, 223000, 246800] },
      { name: '顾屿', role: 'DOCTOR', title: '主治医师', avatarLetter: '顾', target: 180000, actual: 198400, orders: 52, commissionRate: 0.08, status: 'ON_DUTY', joinedAt: '2021-07-01', trend: [142000, 156000, 168000, 175000, 189000, 198400] },
      { name: '周敏', role: 'BEAUTICIAN', title: '高级美容师', avatarLetter: '周', target: 80000, actual: 92300, orders: 124, commissionRate: 0.08, status: 'ON_DUTY', joinedAt: '2023-01-20', trend: [62000, 70000, 75000, 81000, 88000, 92300] },
      { name: '苏婉', role: 'CONSULTANT', title: '咨询师', avatarLetter: '苏', target: 150000, actual: 132600, orders: 29, commissionRate: 0.06, status: 'ON_DUTY', joinedAt: '2023-09-10', trend: [98000, 108000, 118000, 124000, 128000, 132600] },
      { name: '陈珂', role: 'DOCTOR', title: '注射医师', avatarLetter: '陈', target: 160000, actual: 154800, orders: 47, commissionRate: 0.08, status: 'ON_DUTY', joinedAt: '2022-11-05', trend: [120000, 128000, 138000, 146000, 150000, 154800] },
      { name: '吴桐', role: 'BEAUTICIAN', title: '美容师', avatarLetter: '吴', target: 70000, actual: 58200, orders: 96, commissionRate: 0.06, status: 'LEAVE', joinedAt: '2024-02-18', trend: [42000, 48000, 52000, 55000, 57000, 58200] },
      { name: '何苗', role: 'CONSULTANT', title: '初级咨询师', avatarLetter: '何', target: 100000, actual: 67500, orders: 18, commissionRate: 0.06, status: 'PROBATION', joinedAt: '2025-05-06', trend: [0, 0, 28000, 42000, 56000, 67500] },
    ]
    data.forEach((d) => staff.value.push({ id: nextId('pf'), ...d }))
  }

  return {
    staff, filterRole, period,
    onDuty, filtered, totalActual, totalTarget, achievement, topStaff,
    get, completion, commission, tierFor, simulateCommission, updateTarget, seed,
    ROLE_LABEL, STATUS_LABEL, STATUS_PILL, COMMISSION_TIERS,
  }
})
