// ============================================================
// 健康度巡检 API（对接 store-service health 域 · B49 卡10）
// 读：GET /api/stores/health/checks（门店六维评分，按 store_code 升序）
//     GET /api/stores/health/issues（storeCode/status 可选过滤，按 id 升序）
// 写：POST /api/stores/health/issues/{id}/start|resolve|ignore（状态机
//     OPEN→PROCESSING→RESOLVED / →IGNORED，resolve 需 resolution，
//     后端同事务写 HEALTH_ISSUE 审计）
//     POST /api/stores/health/checks/{storeCode}/rerun（按未决 HIGH/MID
//     重算六维分+巡检日期滚动，写 HEALTH_CHECK 审计，返回整行）
// ============================================================
import client from './client'

/** 门店健康行（scores 按 SAFETY/SERVICE/FINANCE/COMPLIANCE/STAFF/EQUIPMENT 序）。 */
export interface TenantHealthDTO {
  tenantId: string
  tenantName: string
  region: string
  scores: number[]
  lastCheckedAt: string
  nextCheckAt: string
  inspector: string
}

export interface HealthIssueDTO {
  id: string
  tenantId: string
  tenantName: string
  dimension: string
  severity: string
  title: string
  detail: string
  status: string
  assignee?: string | null
  dueAt?: string | null
  createdAt: string
  resolvedAt?: string | null
  resolution?: string | null
}

export interface HealthIssueFilter {
  storeCode?: string
  status?: string
}

export const getChecks = () => client.get<TenantHealthDTO[]>('/stores/health/checks')

export const getIssues = (f: HealthIssueFilter = {}) =>
  client.get<HealthIssueDTO[]>('/stores/health/issues', { params: f })

export const startIssue = (id: string) => client.post(`/stores/health/issues/${id}/start`)

export const resolveIssue = (id: string, resolution: string) =>
  client.post(`/stores/health/issues/${id}/resolve`, { resolution })

export const ignoreIssue = (id: string) => client.post(`/stores/health/issues/${id}/ignore`)

export const rerunCheck = (storeCode: string) =>
  client.post<TenantHealthDTO>(`/stores/health/checks/${storeCode}/rerun`)
