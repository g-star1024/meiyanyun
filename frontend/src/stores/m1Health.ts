// 健康度巡检（M1 集团屏 /m1-health · B49 卡10 接真）
// 数据源：store-service health 域——门店六维评分 GET /api/stores/health/checks
//   （health_check 头 + health_score 六维行，种子栈 5 店 SST01-SST05）；
//   整改任务 GET /api/stores/health/issues（health_issue 表，种子 7 条四态齐备，
//   id 镜像 mock 字面量 I01-I07）。
// 写路径：开始处理/解决/忽略 POST /issues/{id}/start|resolve|ignore（后端状态机
//   门控+同事务写 HEALTH_ISSUE 审计，成功后重拉任务列表同步状态）；
//   重新巡检 POST /checks/{storeCode}/rerun（rerun 算法服务端化：未决 HIGH×8+
//   MID×3 扣分、无未决 +4、clamp 40-98、日期滚动 +7d，inspector 后端从登录
//   上下文取，前端传参仅兼容契约），返回整行局部替换。
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import {
  getChecks, getIssues, startIssue as apiStart, resolveIssue as apiResolve,
  ignoreIssue as apiIgnore, rerunCheck, type TenantHealthDTO, type HealthIssueDTO,
} from '@/api/health'
import { useToast } from '@/composables/useToast'
import { errMsg } from '@/stores/m5Coupon'

// 健康度巡检：门店多维指标评分 + 异常整改任务
export type Dimension = 'SAFETY' | 'SERVICE' | 'FINANCE' | 'COMPLIANCE' | 'STAFF' | 'EQUIPMENT'
export type CheckStatus = 'HEALTHY' | 'WARNING' | 'CRITICAL' | 'PENDING'
export type IssueStatus = 'OPEN' | 'PROCESSING' | 'RESOLVED' | 'IGNORED'
export type Severity = 'HIGH' | 'MEDIUM' | 'LOW'

export interface MetricScore {
  dimension: Dimension
  score: number // 0-100
  weight: number // 权重
}

export interface HealthIssue {
  id: string
  tenantId: string
  tenantName: string
  dimension: Dimension
  severity: Severity
  title: string
  detail: string
  status: IssueStatus
  assignee?: string
  dueAt?: string
  createdAt: string
  resolvedAt?: string
  resolution?: string
}

export interface TenantHealth {
  tenantId: string
  tenantName: string
  region: string
  scores: MetricScore[]
  lastCheckedAt: string
  nextCheckAt: string
  inspector: string
}

export const DIM_LABEL: Record<Dimension, string> = {
  SAFETY: '医疗安全', SERVICE: '服务质量', FINANCE: '财务健康',
  COMPLIANCE: '合规经营', STAFF: '人员配置', EQUIPMENT: '设备运维',
}
export const DIM_ICON: Record<Dimension, string> = {
  SAFETY: 'shield', SERVICE: 'profile', FINANCE: 'finance',
  COMPLIANCE: 'scan', STAFF: 'customer', EQUIPMENT: 'box',
}

const DIMS = Object.keys(DIM_LABEL) as Dimension[]

function weightOf(d: Dimension): number {
  return d === 'SAFETY' || d === 'COMPLIANCE' ? 2 : 1
}

function overall(scores: MetricScore[]): number {
  const totalW = scores.reduce((s, x) => s + x.weight, 0)
  return Math.round(scores.reduce((s, x) => s + x.score * x.weight, 0) / totalW)
}
export function scoreStatus(score: number): CheckStatus {
  if (score >= 85) return 'HEALTHY'
  if (score >= 70) return 'WARNING'
  if (score >= 0) return 'CRITICAL'
  return 'PENDING'
}
export const STATUS_LABEL: Record<CheckStatus, string> = {
  HEALTHY: '健康', WARNING: '预警', CRITICAL: '严重', PENDING: '待巡检',
}
export const ISSUE_STATUS_LABEL: Record<IssueStatus, string> = {
  OPEN: '待处理', PROCESSING: '处理中', RESOLVED: '已解决', IGNORED: '已忽略',
}

/** 后端 scores int[]（按 DIMS 序）→ MetricScore[]。 */
function toTenant(d: TenantHealthDTO): TenantHealth {
  return {
    tenantId: d.tenantId,
    tenantName: d.tenantName,
    region: d.region,
    scores: DIMS.map((dim, i) => ({ dimension: dim, score: d.scores[i] ?? 0, weight: weightOf(dim) })),
    lastCheckedAt: d.lastCheckedAt,
    nextCheckAt: d.nextCheckAt,
    inspector: d.inspector,
  }
}

function toIssue(d: HealthIssueDTO): HealthIssue {
  return {
    id: d.id,
    tenantId: d.tenantId,
    tenantName: d.tenantName,
    dimension: d.dimension as Dimension,
    severity: d.severity as Severity,
    title: d.title,
    detail: d.detail,
    status: d.status as IssueStatus,
    assignee: d.assignee ?? undefined,
    dueAt: d.dueAt ?? undefined,
    createdAt: d.createdAt,
    resolvedAt: d.resolvedAt ?? undefined,
    resolution: d.resolution ?? undefined,
  }
}

export const useM1HealthStore = defineStore('m1Health', () => {
  const toast = useToast()
  const tenants = ref<TenantHealth[]>([])
  const issues = ref<HealthIssue[]>([])
  const seeded = ref(false)
  const loading = ref(false)

  async function seed(force = false) {
    if (seeded.value && !force) return
    loading.value = true
    try {
      const [checksResp, issuesResp] = await Promise.all([getChecks(), getIssues()])
      tenants.value = (checksResp.data ?? []).map(toTenant)
      issues.value = (issuesResp.data ?? []).map(toIssue)
      seeded.value = true
    } catch (e) {
      toast.error(errMsg(e, '健康度数据加载失败'))
    } finally {
      loading.value = false
    }
  }

  const overallScore = computed(() => {
    if (!tenants.value.length) return 0
    return Math.round(tenants.value.reduce((s, t) => s + overall(t.scores), 0) / tenants.value.length)
  })
  const healthyCount = computed(() => tenants.value.filter((t) => overall(t.scores) >= 85).length)
  const warningCount = computed(() => tenants.value.filter((t) => { const o = overall(t.scores); return o >= 70 && o < 85 }).length)
  const criticalCount = computed(() => tenants.value.filter((t) => overall(t.scores) < 70).length)
  const openIssues = computed(() => issues.value.filter((i) => i.status === 'OPEN' || i.status === 'PROCESSING'))
  const highRiskIssues = computed(() => openIssues.value.filter((i) => i.severity === 'HIGH'))

  function scoreOf(t: TenantHealth): number { return overall(t.scores) }
  function statusOf(t: TenantHealth): CheckStatus { return scoreStatus(scoreOf(t)) }

  /** 任务列表重拉（写操作响应不带实体，状态变更以服务端为准全量同步）。 */
  async function refreshIssues() {
    try {
      const resp = await getIssues()
      issues.value = (resp.data ?? []).map(toIssue)
    } catch (e) {
      toast.error(errMsg(e, '整改任务刷新失败'))
    }
  }

  async function startIssue(id: string) {
    try {
      await apiStart(id)
      toast.success('已开始处理')
    } catch (e) {
      toast.error(errMsg(e, '操作失败'))
    }
    await refreshIssues()
  }
  async function resolveIssue(id: string, resolution: string) {
    try {
      await apiResolve(id, resolution)
      toast.success('整改已解决')
    } catch (e) {
      toast.error(errMsg(e, '解决提交失败'))
    }
    await refreshIssues()
  }
  async function ignoreIssue(id: string) {
    try {
      await apiIgnore(id)
      toast.success('已忽略')
    } catch (e) {
      toast.error(errMsg(e, '操作失败'))
    }
    await refreshIssues()
  }
  // 重新巡检：算法服务端化（inspector 由后端从登录上下文取，前端传参仅兼容契约），
  // 返回门店整行局部替换。
  async function rerun(tenantId: string, _inspector: string) {
    try {
      const resp = await rerunCheck(tenantId)
      const updated = toTenant(resp.data)
      const i = tenants.value.findIndex((x) => x.tenantId === updated.tenantId)
      if (i >= 0) tenants.value.splice(i, 1, updated)
      toast.success('重新巡检完成')
    } catch (e) {
      toast.error(errMsg(e, '重新巡检失败'))
    }
  }

  return {
    tenants, issues, seeded, loading, seed,
    overallScore, healthyCount, warningCount, criticalCount, openIssues, highRiskIssues,
    scoreOf, statusOf, startIssue, resolveIssue, ignoreIssue, rerun,
  }
})
