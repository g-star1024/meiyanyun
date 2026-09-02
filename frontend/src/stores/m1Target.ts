import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

// 目标管理：集团目标 → 区域/门店分解 → 进度追踪 + 考核
export type TargetPeriod = 'MONTH' | 'QUARTER' | 'YEAR'
export type TargetMetric = 'REVENUE' | 'NEW_CUSTOMER' | 'REPURCHASE_RATE' | 'PROCEDURE_COUNT' | 'SATISFACTION'
export type TargetStatus = 'ON_TRACK' | 'AT_RISK' | 'BEHIND' | 'ACHIEVED'
export type ApprovalStatus = 'DRAFT' | 'PENDING' | 'APPROVED' | 'REJECTED'

export interface TargetLine {
  id: string
  ownerId: string // 门店/区域 id
  ownerName: string
  ownerType: 'GROUP' | 'REGION' | 'STORE'
  metric: TargetMetric
  period: TargetPeriod
  periodLabel: string // 如 2026-Q3
  targetValue: number
  currentValue: number
  unit: string // 万元 / 人 / %
  weight: number
  approval: ApprovalStatus
  children?: string[] // 分解的子目标 id
}

export const METRIC_LABEL: Record<TargetMetric, string> = {
  REVENUE: '营收', NEW_CUSTOMER: '新客数', REPURCHASE_RATE: '复购率',
  PROCEDURE_COUNT: '治疗人次', SATISFACTION: '满意度',
}
export const METRIC_UNIT: Record<TargetMetric, string> = {
  REVENUE: '万元', NEW_CUSTOMER: '人', REPURCHASE_RATE: '%',
  PROCEDURE_COUNT: '人次', SATISFACTION: '%',
}
export const PERIOD_LABEL: Record<TargetPeriod, string> = { MONTH: '月度', QUARTER: '季度', YEAR: '年度' }
export const APPROVAL_LABEL: Record<ApprovalStatus, string> = {
  DRAFT: '草稿', PENDING: '待审批', APPROVED: '已批准', REJECTED: '已驳回',
}

export function statusOf(progress: number): TargetStatus {
  if (progress >= 100) return 'ACHIEVED'
  if (progress >= 85) return 'ON_TRACK'
  if (progress >= 60) return 'AT_RISK'
  return 'BEHIND'
}
export const STATUS_LABEL: Record<TargetStatus, string> = {
  ON_TRACK: '正常', AT_RISK: '风险', BEHIND: '落后', ACHIEVED: '已达成',
}

function mk(): TargetLine[] {
  return [
    // 集团年度目标
    { id: 'G1', ownerId: 'GROUP', ownerName: '美云集团', ownerType: 'GROUP',
      metric: 'REVENUE', period: 'YEAR', periodLabel: '2026年度', targetValue: 38000, currentValue: 23800,
      unit: '万元', weight: 40, approval: 'APPROVED', children: ['G1-E', 'G1-N', 'G1-S'] },
    { id: 'G1-E', ownerId: 'R-EAST', ownerName: '华东区', ownerType: 'REGION',
      metric: 'REVENUE', period: 'YEAR', periodLabel: '2026年度', targetValue: 18000, currentValue: 11900,
      unit: '万元', weight: 40, approval: 'APPROVED', children: ['T01-R', 'T02-R'] },
    { id: 'G1-N', ownerId: 'R-NORTH', ownerName: '华北区', ownerType: 'REGION',
      metric: 'REVENUE', period: 'YEAR', periodLabel: '2026年度', targetValue: 11000, currentValue: 5900,
      unit: '万元', weight: 40, approval: 'APPROVED' },
    { id: 'G1-S', ownerId: 'R-SOUTH', ownerName: '华南区', ownerType: 'REGION',
      metric: 'REVENUE', period: 'YEAR', periodLabel: '2026年度', targetValue: 9000, currentValue: 6000,
      unit: '万元', weight: 40, approval: 'APPROVED' },
    { id: 'T01-R', ownerId: 'T01', ownerName: '杭州西湖旗舰院', ownerType: 'STORE',
      metric: 'REVENUE', period: 'YEAR', periodLabel: '2026年度', targetValue: 10000, currentValue: 6800,
      unit: '万元', weight: 40, approval: 'APPROVED' },
    { id: 'T02-R', ownerId: 'T02', ownerName: '上海静安分院', ownerType: 'STORE',
      metric: 'REVENUE', period: 'YEAR', periodLabel: '2026年度', targetValue: 8000, currentValue: 5100,
      unit: '万元', weight: 40, approval: 'APPROVED' },
    // 新客
    { id: 'G2', ownerId: 'GROUP', ownerName: '美云集团', ownerType: 'GROUP',
      metric: 'NEW_CUSTOMER', period: 'QUARTER', periodLabel: '2026-Q3', targetValue: 4200, currentValue: 1980,
      unit: '人', weight: 20, approval: 'APPROVED' },
    // 复购率
    { id: 'G3', ownerId: 'GROUP', ownerName: '美云集团', ownerType: 'GROUP',
      metric: 'REPURCHASE_RATE', period: 'QUARTER', periodLabel: '2026-Q3', targetValue: 45, currentValue: 41,
      unit: '%', weight: 15, approval: 'PENDING' },
    // 满意度
    { id: 'G4', ownerId: 'GROUP', ownerName: '美云集团', ownerType: 'GROUP',
      metric: 'SATISFACTION', period: 'MONTH', periodLabel: '2026-08', targetValue: 95, currentValue: 93,
      unit: '%', weight: 15, approval: 'APPROVED' },
    // 草稿
    { id: 'G5', ownerId: 'R-SOUTH', ownerName: '华南区', ownerType: 'REGION',
      metric: 'PROCEDURE_COUNT', period: 'QUARTER', periodLabel: '2026-Q3', targetValue: 3000, currentValue: 0,
      unit: '人次', weight: 10, approval: 'DRAFT' },
  ]
}

export const useM1TargetStore = defineStore('m1Target', () => {
  const lines = ref<TargetLine[]>([])
  const seeded = ref(false)
  function seed() { if (!seeded.value) { lines.value = mk(); seeded.value = true } }

  function progress(l: TargetLine): number {
    if (!l.targetValue) return 0
    return Math.round((l.currentValue / l.targetValue) * 100)
  }
  const groupLines = computed(() => lines.value.filter((l) => l.ownerType === 'GROUP'))
  const regionLines = computed(() => lines.value.filter((l) => l.ownerType === 'REGION'))
  const storeLines = computed(() => lines.value.filter((l) => l.ownerType === 'STORE'))
  const pendingApprovals = computed(() => lines.value.filter((l) => l.approval === 'PENDING'))

  // 加权综合达成（只算已批准）
  const overallProgress = computed(() => {
    const approved = lines.value.filter((l) => l.approval === 'APPROVED' && l.ownerType === 'GROUP')
    if (!approved.length) return 0
    const totalW = approved.reduce((s, l) => s + l.weight, 0)
    return Math.round(approved.reduce((s, l) => s + progress(l) * l.weight, 0) / totalW)
  })

  function updateProgress(id: string, value: number) {
    const l = lines.value.find((x) => x.id === id)
    if (l) l.currentValue = value
  }
  function submit(id: string) {
    const l = lines.value.find((x) => x.id === id)
    if (l && l.approval === 'DRAFT') l.approval = 'PENDING'
  }
  function approve(id: string) {
    const l = lines.value.find((x) => x.id === id)
    if (l && l.approval === 'PENDING') l.approval = 'APPROVED'
  }
  function reject(id: string) {
    const l = lines.value.find((x) => x.id === id)
    if (l && l.approval === 'PENDING') l.approval = 'REJECTED'
  }

  return {
    lines, seeded, seed, progress, groupLines, regionLines, storeLines,
    pendingApprovals, overallProgress, updateProgress, submit, approve, reject,
  }
})
