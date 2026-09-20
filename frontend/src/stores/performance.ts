// ============================================================
// 员工绩效看板 store（M2-07）
// 咨询师 / 医生本月业绩、目标完成率、提成。
// 切真：period（本月/上月）作 load 参数走后端；提成试算保留纯前端。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'
import * as perfApi from '@/api/performance'
import { useToast } from '@/composables/useToast'
import { errMsg } from '@/stores/m5Coupon'
import { useStoreContext } from '@/stores/storeContext'

export type StaffRole = 'CONSULTANT' | 'DOCTOR' | 'BEAUTICIAN'
export type PerformanceStatus = 'ON_DUTY' | 'LEAVE' | 'PROBATION'
export type PerformancePeriod = 'THIS_MONTH' | 'LAST_MONTH'

export interface PerformanceStaff {
  id: string
  name: string
  role: StaffRole
  title: string
  avatarLetter: string
  target: number
  actual: number
  orders: number
  commissionRate: number
  status: PerformanceStatus
  joinedAt: string
  trend: number[]
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

const COMMISSION_TIERS = [
  { min: 0, rate: 0.06, label: '基础 6%' },
  { min: 80000, rate: 0.08, label: '达标 8%' },
  { min: 150000, rate: 0.10, label: '卓越 10%' },
  { min: 250000, rate: 0.12, label: '冠军 12%' },
]

function adapt(d: perfApi.PerfStaffDto): PerformanceStaff {
  return {
    id: String(d.id),
    name: d.name,
    role: d.role as StaffRole,
    title: d.title,
    avatarLetter: d.avatarLetter,
    target: d.target,
    actual: d.actual,
    orders: d.orders,
    commissionRate: d.commissionRate,
    status: d.status as PerformanceStatus,
    joinedAt: d.joinedAt,
    trend: d.trend.slice(0, 6),
  }
}

export const usePerformanceStore = defineStore('performance', () => {
  const ctx = useStoreContext()
  const toast = useToast()

  const staff = ref<PerformanceStaff[]>([])
  const filterRole = ref<StaffRole | 'ALL'>('ALL')
  const period = ref<PerformancePeriod>('THIS_MONTH')
  const activePeriod = ref<string>('')

  const trendLabels = computed<string[]>(() => {
    if (!activePeriod.value) return []
    const [y, m] = activePeriod.value.split('-').map(Number)
    const labels: string[] = []
    for (let i = 5; i >= 1; i--) {
      const d = new Date(y, m - 1 - i, 1)
      labels.push(`${d.getMonth() + 1}月`)
    }
    labels.push(`${m}月`)
    return labels
  })

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

  async function updateTarget(id: string, target: number): Promise<boolean> {
    const s = staff.value.find((x) => x.id === id)
    if (!s) return false
    const value = Math.max(0, Math.round(target))
    try {
      const { data } = await perfApi.updatePerfTarget(id, value)
      replaceStaff(adapt(data))
      return true
    } catch (e) {
      toast.error(errMsg(e, '目标调整失败，请稍后重试'))
      return false
    }
  }

  function simulateCommission(id: string, amount: number): { rate: number; commission: number; delta: number; label: string } | null {
    const s = staff.value.find((x) => x.id === id)
    if (!s) return null
    const t = tierFor(amount)
    const newCommission = Math.round(amount * t.rate)
    const baseline = commission(s)
    return { rate: t.rate, commission: newCommission, delta: newCommission - baseline, label: t.label }
  }

  function replaceStaff(next: PerformanceStaff) {
    const idx = staff.value.findIndex((s) => s.id === next.id)
    if (idx >= 0) staff.value.splice(idx, 1, next)
    else staff.value.push(next)
  }

  async function load() {
    try {
      const sc = ctx.currentStoreCode
      const { data } = await perfApi.listPerfStaff({
        period: period.value,
        storeCode: sc || undefined,
      })
      staff.value = data.map(adapt)
      activePeriod.value = data.length ? data[0].period : resolvePeriodStr(period.value)
    } catch (e) {
      staff.value = []
      activePeriod.value = resolvePeriodStr(period.value)
      toast.error(errMsg(e, '员工绩效加载失败，请稍后重试'))
    }
  }

  function resolvePeriodStr(p: PerformancePeriod): string {
    const now = new Date()
    const d = new Date(now.getFullYear(), now.getMonth() - (p === 'LAST_MONTH' ? 1 : 0), 1)
    const y = d.getFullYear()
    const m = String(d.getMonth() + 1).padStart(2, '0')
    return `${y}-${m}`
  }

  async function seed() {
    await ctx.loadStores()
    await load()
  }

  watch(period, () => { void load() })

  return {
    staff, filterRole, period,
    trendLabels, activePeriod,
    onDuty, filtered, totalActual, totalTarget, achievement, topStaff,
    get, completion, commission, tierFor, simulateCommission, updateTarget, seed,
    ROLE_LABEL, STATUS_LABEL, STATUS_PILL, COMMISSION_TIERS,
  }
})
