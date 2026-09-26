import client from './client'

/**
 * NPS 客户体验 API（M3-12 切真）。
 * 后端：customer-service NpsController（/api/customer/m3/nps）。
 * client 响应拦截器不拆包，消费侧取 resp.data。
 */

export type NpsCategoryCode = 'PROMOTER' | 'PASSIVE' | 'DETRACTOR'
export type NpsFollowStatus = 'PENDING' | 'FOLLOWED'

export interface NpsRecordRow {
  recordNo: string
  customerId: string | null
  customerName: string
  score: number
  category: NpsCategoryCode
  service: string | null
  tags: string[]
  comment: string | null
  period: string
  followStatus: NpsFollowStatus
  followNote: string | null
  createdAt: string
  updatedAt: string
}

export interface NpsSummaryPayload {
  total: number
  promoters: number
  passives: number
  detractors: number
  pending: number
  npsScore: number
  promoterPct: number
  passivePct: number
  detractorPct: number
  reachCount: number
  responseRate: number
}

export interface NpsTrendPointRow {
  period: string
  nps: number
  promoters: number
  passives: number
  detractors: number
  total: number
}

export interface NpsSubmitBody {
  customer: string
  score: number
  service?: string
  tags?: string[]
  comment?: string
}

export function fetchNpsRecords() {
  return client.get<NpsRecordRow[]>('/customer/m3/nps/records')
}

export function fetchNpsSummary() {
  return client.get<NpsSummaryPayload>('/customer/m3/nps/summary')
}

export function fetchNpsTrends() {
  return client.get<NpsTrendPointRow[]>('/customer/m3/nps/trends')
}

export function submitNpsRecord(body: NpsSubmitBody) {
  return client.post<NpsRecordRow>('/customer/m3/nps/records', body)
}

export function followNpsRecord(recordNo: string, note: string) {
  return client.post<NpsRecordRow>(`/customer/m3/nps/records/${encodeURIComponent(recordNo)}/follow`, { note })
}
