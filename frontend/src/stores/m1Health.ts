import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

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

function mkTenants(): TenantHealth[] {
  const dims = (vals: Record<Dimension, number>): MetricScore[] =>
    (Object.keys(DIM_LABEL) as Dimension[]).map((d) => ({
      dimension: d, score: vals[d], weight: d === 'SAFETY' || d === 'COMPLIANCE' ? 2 : 1,
    }))
  return [
    {
      tenantId: 'T01', tenantName: '杭州西湖旗舰院', region: '华东区',
      scores: dims({ SAFETY: 92, SERVICE: 88, FINANCE: 90, COMPLIANCE: 95, STAFF: 86, EQUIPMENT: 84 }),
      lastCheckedAt: '2026-08-24', nextCheckAt: '2026-08-31', inspector: '王质控',
    },
    {
      tenantId: 'T02', tenantName: '上海静安分院', region: '华东区',
      scores: dims({ SAFETY: 78, SERVICE: 82, FINANCE: 65, COMPLIANCE: 88, STAFF: 72, EQUIPMENT: 70 }),
      lastCheckedAt: '2026-08-23', nextCheckAt: '2026-08-30', inspector: '李质控',
    },
    {
      tenantId: 'T03', tenantName: '北京朝阳分院', region: '华北区',
      scores: dims({ SAFETY: 62, SERVICE: 75, FINANCE: 58, COMPLIANCE: 70, STAFF: 68, EQUIPMENT: 55 }),
      lastCheckedAt: '2026-08-22', nextCheckAt: '2026-08-26', inspector: '张质控',
    },
    {
      tenantId: 'T04', tenantName: '广州天河分院', region: '华南区',
      scores: dims({ SAFETY: 88, SERVICE: 90, FINANCE: 82, COMPLIANCE: 91, STAFF: 85, EQUIPMENT: 80 }),
      lastCheckedAt: '2026-08-24', nextCheckAt: '2026-08-31', inspector: '陈质控',
    },
    {
      tenantId: 'T05', tenantName: '成都高新分院', region: '西南区',
      scores: dims({ SAFETY: 85, SERVICE: 80, FINANCE: 76, COMPLIANCE: 84, STAFF: 78, EQUIPMENT: 90 }),
      lastCheckedAt: '2026-08-20', nextCheckAt: '2026-08-27', inspector: '赵质控',
    },
  ]
}

function mkIssues(): HealthIssue[] {
  return [
    { id: 'I01', tenantId: 'T03', tenantName: '北京朝阳分院', dimension: 'EQUIPMENT', severity: 'HIGH',
      title: '热玛吉设备超期未校准', detail: '设备编号 RMJ-003 上次校准 2026-05-10，已超期 107 天，存在治疗安全隐患。',
      status: 'OPEN', assignee: '张院长', dueAt: '2026-08-27', createdAt: '2026-08-22' },
    { id: 'I02', tenantId: 'T03', tenantName: '北京朝阳分院', dimension: 'FINANCE', severity: 'HIGH',
      title: '应收账款周转异常', detail: '应收账款周转天数 62 天，超出集团红线 45 天，逾期款占比 28%。',
      status: 'PROCESSING', assignee: '刘财务', dueAt: '2026-08-30', createdAt: '2026-08-22' },
    { id: 'I03', tenantId: 'T02', tenantName: '上海静安分院', dimension: 'STAFF', severity: 'MEDIUM',
      title: '主诊医师配比不足', detail: '在岗主诊医师 3 人，按日均客流 80 人标准需 5 人，已启动招聘。',
      status: 'PROCESSING', assignee: '李院长', dueAt: '2026-09-15', createdAt: '2026-08-23' },
    { id: 'I04', tenantId: 'T02', tenantName: '上海静安分院', dimension: 'EQUIPMENT', severity: 'MEDIUM',
      title: '消毒记录不完整', detail: '8 月有 3 天高温高压消毒记录缺失生物监测结果。',
      status: 'OPEN', assignee: '王护士长', dueAt: '2026-08-28', createdAt: '2026-08-23' },
    { id: 'I05', tenantId: 'T03', tenantName: '北京朝阳分院', dimension: 'SAFETY', severity: 'HIGH',
      title: '急救药品近效期', detail: '肾上腺素 2 支、硝酸甘油 1 支将在 15 天内到期，需立即更换。',
      status: 'OPEN', assignee: '张院长', dueAt: '2026-08-26', createdAt: '2026-08-24' },
    { id: 'I06', tenantId: 'T05', tenantName: '成都高新分院', dimension: 'SERVICE', severity: 'LOW',
      title: '客户满意度环比下降', detail: '7 月满意度 91% 降至 88%，主要投诉集中在等待时长。',
      status: 'RESOLVED', assignee: '赵院长', createdAt: '2026-08-15',
      resolvedAt: '2026-08-22', resolution: '已增加周末排班，增开 2 间治疗室分流。' },
    { id: 'I07', tenantId: 'T04', tenantName: '广州天河分院', dimension: 'COMPLIANCE', severity: 'MEDIUM',
      title: '广告素材备案滞后', detail: '3 条线上推广素材上线前未完成医疗广告审查备案。',
      status: 'PROCESSING', assignee: '陈运营', dueAt: '2026-08-29', createdAt: '2026-08-24' },
  ]
}

export const useM1HealthStore = defineStore('m1Health', () => {
  const tenants = ref<TenantHealth[]>([])
  const issues = ref<HealthIssue[]>([])
  const seeded = ref(false)

  function seed() {
    if (seeded.value) return
    tenants.value = mkTenants()
    issues.value = mkIssues()
    seeded.value = true
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

  function startIssue(id: string) {
    const it = issues.value.find((x) => x.id === id)
    if (it && it.status === 'OPEN') it.status = 'PROCESSING'
  }
  function resolveIssue(id: string, resolution: string) {
    const it = issues.value.find((x) => x.id === id)
    if (!it) return
    it.status = 'RESOLVED'
    it.resolution = resolution
    it.resolvedAt = new Date().toISOString().slice(0, 10)
  }
  function ignoreIssue(id: string) {
    const it = issues.value.find((x) => x.id === id)
    if (it && (it.status === 'OPEN' || it.status === 'PROCESSING')) it.status = 'IGNORED'
  }
  // 重新巡检：根据未解决的严重问题动态重算分数（模拟）
  function rerun(tenantId: string, inspector: string) {
    const t = tenants.value.find((x) => x.tenantId === tenantId)
    if (!t) return
    const openHigh = issues.value.filter((i) => i.tenantId === tenantId && i.severity === 'HIGH' && (i.status === 'OPEN' || i.status === 'PROCESSING')).length
    const openMid = issues.value.filter((i) => i.tenantId === tenantId && i.severity === 'MEDIUM' && (i.status === 'OPEN' || i.status === 'PROCESSING')).length
    t.scores = t.scores.map((s) => ({
      ...s,
      score: Math.max(40, Math.min(98, s.score - openHigh * 8 - openMid * 3 + (openHigh + openMid === 0 ? 4 : 0))),
    }))
    t.lastCheckedAt = new Date().toISOString().slice(0, 10)
    t.nextCheckAt = new Date(Date.now() + 7 * 86400000).toISOString().slice(0, 10)
    t.inspector = inspector
  }

  return {
    tenants, issues, seeded, seed,
    overallScore, healthyCount, warningCount, criticalCount, openIssues, highRiskIssues,
    scoreOf, statusOf, startIssue, resolveIssue, ignoreIssue, rerun,
  }
})
