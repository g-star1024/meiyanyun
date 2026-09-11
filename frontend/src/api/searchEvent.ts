// ============================================================
// 客户检索 outbox 事件处置台 API（B32）
// 收口 B30 客户检索 outbox 的 DEAD 事件：分页清单/状态 KPI/人工重试/立即重放/丢弃/全量重建。
// 全部端点挂 customer-service（/customer/search-events），统一要求 customer:search:admin（仅区域经理/超管）。
// ============================================================
import client from './client'

/** outbox 事件四态：PENDING=待投递；SENT=已投递；DEAD=投递失败；DISCARDED=人工丢弃终态。 */
export type SearchEventStatus = 'PENDING' | 'SENT' | 'DEAD' | 'DISCARDED'

/** 事件行（后端回查 PG 富化客户名/手机号/门店；客户已删时三者为 null）。 */
export interface SearchEventDTO {
  eventId: number
  eventType: string
  customerId: string
  status: SearchEventStatus
  statusLabel: string
  retryCount: number
  lastError: string | null
  createdAt: string | null
  sentAt: string | null
  resolvedAt: string | null
  resolvedBy: string | null
  resolveNote: string | null
  customerName: string | null
  phone: string | null
  storeCode: string | null
}

/** Spring Data 分页响应（page 0 起）。 */
export interface SearchEventPage {
  content: SearchEventDTO[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

/** 状态 KPI：四态计数。 */
export type SearchEventStats = Record<'pending' | 'sent' | 'dead' | 'discarded', number>

export interface SearchEventQuery {
  status?: SearchEventStatus | ''
  customerId?: string
  eventId?: number
  page?: number
  size?: number
}

/** 事件分页清单：status/customerId/eventId 过滤，后端固定 eventId 倒序。 */
export const listSearchEvents = (params: SearchEventQuery) =>
  client.get<SearchEventPage>('/customer/search-events', { params })

/** 状态 KPI：pending/sent/dead/discarded 四态计数。 */
export const statsSearchEvents = () =>
  client.get<SearchEventStats>('/customer/search-events/stats')

/** 人工重试：DEAD → PENDING 交回 10 秒中继（retryCount 归零）；PENDING 幂等返回。 */
export const retrySearchEvent = (eventId: number) =>
  client.post<SearchEventDTO>(`/customer/search-events/${eventId}/retry`)

/** 立即重放：当场回查 PG 同步 upsert，返回 SENT/RETRY/DEAD 结果，无需等待中继。 */
export const replaySearchEvent = (eventId: number) =>
  client.post<SearchEventDTO>(`/customer/search-events/${eventId}/replay`)

/** 丢弃：DEAD/PENDING → DISCARDED 终态，原因必填（≥2 字，随审计留痕）。 */
export const discardSearchEvent = (eventId: number, note: string) =>
  client.post<SearchEventDTO>(`/customer/search-events/${eventId}/discard`, { note })

/** 全量重建客户索引（复用 /customer/search/reindex，B32 起同权并补审计）。 */
export const reindexCustomerSearch = () =>
  client.post<{ indexed: number; index: string }>('/customer/search/reindex')
