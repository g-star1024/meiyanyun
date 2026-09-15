// ============================================================
// 审计日志（M1 集团管控 / 审计日志）API
// 数据源：audit-service 真实端点。
// - GET /audit/page：分页检索（bizType/actor/时间范围/关键字，按 id 倒序）
// - GET /audit/facets：统计面（总数/近24h/操作人数/模块分布）
// - GET /audit/verify：SHA-256 链完整性巡检
// 注意：GET /audit（全链 List）为既有契约（m5Settings 消费），本模块不复用。
// ============================================================
import client from './client'

/** audit_log 表行（Jackson camelCase 直出；payload 为 jsonb 文本）。 */
export interface AuditLogRow {
  id: number
  bizType: string
  txnNo: string | null
  actor: string
  action: string
  payload: string
  prevHash: string
  curHash: string
  createdAt: string
}

export interface AuditPageResult {
  items: AuditLogRow[]
  total: number
  page: number
  size: number
}

export interface AuditPageQuery {
  bizType?: string
  actor?: string
  keyword?: string
  /** ISO 8601 带偏移（如 2026-09-14T00:00:00+08:00） */
  from?: string
  to?: string
  page?: number
  size?: number
}

export interface AuditBizTypeFacet {
  bizType: string
  count: number
}

export interface AuditFacets {
  total: number
  last24: number
  actors: number
  bizTypes: AuditBizTypeFacet[]
}

export interface AuditChainBreak {
  id: number
  expectedPrev: string
  storedPrev: string
  curHash: string
  action: string
  actor: string
  createdAt: string
}

export interface AuditChainVerifyResult {
  ok: boolean
  /** 首处断链 id（保留旧口径兼容；全量清单见 breaks） */
  brokenAtId: number | null
  total: number
  /** 全量断链清单（按 id 升序） */
  breaks: AuditChainBreak[]
}

export const pageAuditLogs = (query: AuditPageQuery) =>
  client.get<AuditPageResult>('/audit/page', { params: query })

export const getAuditFacets = () => client.get<AuditFacets>('/audit/facets')

export const verifyAuditChain = () => client.get<AuditChainVerifyResult>('/audit/verify')
