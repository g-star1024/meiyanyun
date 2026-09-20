// 目标管理：集团目标 → 区域/门店分解 → 进度追踪 + 考核（M1 集团屏 /m1-target · B49 卡8 接真）
// 数据源：finance-service /api/finance/targets（biz_target 表，种子栈 10 行三级分解）。
// 写路径：进度更新（仅 APPROVED）/ 提交（DRAFT→PENDING）/ 批准·驳回（PENDING 起，驳回原因必填）——
//   全部落 audit_log（bizType=BIZ_TARGET）；写成功后以后端返回行局部替换，无需整表刷新。
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import {
  listTargets, updateTargetProgress, submitTarget, approveTarget, rejectTarget, resetTarget,
  type TargetLineDTO,
} from '@/api/target'
import { useToast } from '@/composables/useToast'
import { errMsg } from '@/stores/m5Coupon'

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
  submittedBy?: string | null
  approvedBy?: string | null
  rejectReason?: string | null
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

function toLine(d: TargetLineDTO): TargetLine {
  return {
    id: d.id,
    ownerId: d.ownerId,
    ownerName: d.ownerName,
    ownerType: d.ownerType as TargetLine['ownerType'],
    metric: d.metric as TargetMetric,
    period: d.period as TargetPeriod,
    periodLabel: d.periodLabel,
    targetValue: Number(d.targetValue),
    currentValue: Number(d.currentValue),
    unit: d.unit,
    weight: Number(d.weight),
    approval: d.approval as ApprovalStatus,
    children: d.children ?? [],
    submittedBy: d.submittedBy ?? null,
    approvedBy: d.approvedBy ?? null,
    rejectReason: d.rejectReason ?? null,
  }
}

export const useM1TargetStore = defineStore('m1Target', () => {
  const toast = useToast()
  const lines = ref<TargetLine[]>([])
  const seeded = ref(false)
  const loading = ref(false)

  async function seed(force = false) {
    if (seeded.value && !force) return
    loading.value = true
    try {
      const resp = await listTargets()
      lines.value = (resp.data ?? []).map(toLine)
      seeded.value = true
    } catch (e) {
      toast.error(errMsg(e, '经营目标加载失败'))
    } finally {
      loading.value = false
    }
  }

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

  function replaceLine(updated: TargetLine) {
    const i = lines.value.findIndex((x) => x.id === updated.id)
    if (i >= 0) lines.value.splice(i, 1, updated)
  }

  async function updateProgress(id: string, value: number) {
    try {
      const resp = await updateTargetProgress(id, value)
      replaceLine(toLine(resp.data))
      toast.success('进度已更新')
    } catch (e) {
      toast.error(errMsg(e, '进度更新失败'))
    }
  }
  async function submit(id: string) {
    try {
      const resp = await submitTarget(id)
      replaceLine(toLine(resp.data))
      toast.success('已提交审批')
    } catch (e) {
      toast.error(errMsg(e, '提交审批失败'))
    }
  }
  async function approve(id: string) {
    try {
      const resp = await approveTarget(id)
      replaceLine(toLine(resp.data))
      toast.success('已批准')
    } catch (e) {
      toast.error(errMsg(e, '批准失败'))
    }
  }
  async function reject(id: string) {
    const reason = window.prompt('请填写驳回原因（必填，将写入审计日志）：')
    if (reason === null) return
    if (!reason.trim()) {
      toast.error('驳回原因不能为空')
      return
    }
    try {
      const resp = await rejectTarget(id, reason.trim())
      replaceLine(toLine(resp.data))
      toast.success('已驳回')
    } catch (e) {
      toast.error(errMsg(e, '驳回失败'))
    }
  }

  async function reset(id: string) {
    try {
      const resp = await resetTarget(id)
      replaceLine(toLine(resp.data))
      toast.success('已重置')
    } catch (e) {
      toast.error(errMsg(e, '重置失败'))
    }
  }

  return {
    lines, seeded, loading, seed, progress, groupLines, regionLines, storeLines,
    pendingApprovals, overallProgress, updateProgress, submit, approve, reject, reset,
  }
})
