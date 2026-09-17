// ============================================================
// DSAR（个人行使权利请求）+ 同意生命周期 API（对接 customer-service）
// PIPL 第 44-49 条 DSAR 工单 + 第 14-16 条同意生命周期 + 第 15 条撤回权
// 详见 docs/DESIGN-P5-B57-CARD3-COMPLIANCE-2026-09-17.md §2-§3
// ============================================================
import client from './client'
import type { CustomerDTO } from './customer'

// ---- DSAR 工单 ----

export interface DsarRequestDTO {
  id: number
  requestNo: string
  customerId: string
  type: 'ACCESS' | 'DELETE' | 'RECTIFY' | 'PORTABILITY'
  status: 'SUBMITTED' | 'REVIEWING' | 'FULFILLED' | 'REJECTED'
  description?: string | null
  reviewer?: string | null
  rejectReason?: string | null
  requestedAt: string
  deadlineAt: string
  fulfilledAt?: string | null
  fulfillmentData?: string | null
  createdAt: string
}

export interface DsarPage {
  content: DsarRequestDTO[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

export interface DsarStats {
  byStatus: Record<string, number>
  overdue: number
}

export const listDsar = (params: {
  page?: number
  size?: number
  storeCode?: string
  status?: string
  type?: string
  customerId?: string
} = {}) => client.get<DsarPage>('/customer/dsar', { params })

export const getDsar = (id: number) =>
  client.get<DsarRequestDTO>(`/customer/dsar/${id}`)

export const createDsar = (payload: {
  customerId: string
  type: DsarRequestDTO['type']
  description: string
}) => client.post<DsarRequestDTO>('/customer/dsar', payload)

export const reviewDsar = (
  id: number,
  payload: { action: 'fulfill' | 'reject'; reviewer: string; rejectReason?: string },
) => client.put<DsarRequestDTO>(`/customer/dsar/${id}/review`, payload)

export const getDsarStats = () =>
  client.get<DsarStats>('/customer/dsar/stats')

// ---- 同意生命周期 ----

export interface ConsentStatusDTO {
  customerId: string
  consentVersion: number
  consentAt?: string | null
  consentWithdrawnAt?: string | null
}

export const getConsent = (customerId: string) =>
  client.get<ConsentStatusDTO>(`/customer/${customerId}/consent`)

export const grantConsent = (
  customerId: string,
  scene: 'REGISTER' | 'MARKETING' | 'APPOINTMENT' | 'PRESCRIPTION' = 'MARKETING',
) => client.post<CustomerDTO>(`/customer/${customerId}/consent/grant`, null, { params: { scene } })

export const withdrawConsent = (customerId: string) =>
  client.post<CustomerDTO>(`/customer/${customerId}/consent/withdraw`)

// ---- DSAR 工单类型 / 状态 中文映射（铁律 3：界面零英文） ----

export const DSAR_TYPE_LABEL: Record<DsarRequestDTO['type'], string> = {
  ACCESS: '访问',
  DELETE: '删除',
  RECTIFY: '更正',
  PORTABILITY: '可携带',
}

export const DSAR_STATUS_LABEL: Record<DsarRequestDTO['status'], string> = {
  SUBMITTED: '已提交',
  REVIEWING: '审核中',
  FULFILLED: '已完成',
  REJECTED: '已驳回',
}

/** 同意状态徽章：已同意 / 已撤回 / 未授权 */
export function consentBadge(s: ConsentStatusDTO | null): { text: string; tone: 'success' | 'warning' | 'default' } {
  if (!s || s.consentVersion === 0) return { text: '未授权', tone: 'default' }
  if (s.consentWithdrawnAt && s.consentAt && new Date(s.consentWithdrawnAt) > new Date(s.consentAt)) {
    return { text: '已撤回', tone: 'warning' }
  }
  return { text: '已同意', tone: 'success' }
}

/** DSAR 工单是否已超期（deadline_at < now 且未闭合） */
export function isDsarOverdue(r: DsarRequestDTO): boolean {
  if (r.status === 'FULFILLED' || r.status === 'REJECTED') return false
  return new Date(r.deadlineAt) < new Date()
}

/** DSAR 工单 7 日内将超期（deadline_at < now+7d 且未闭合，不含已超期） */
export function isDsarWarn(r: DsarRequestDTO): boolean {
  if (r.status === 'FULFILLED' || r.status === 'REJECTED') return false
  const deadline = new Date(r.deadlineAt).getTime()
  const now = Date.now()
  const sevenDays = 7 * 24 * 3600 * 1000
  return deadline < now + sevenDays && deadline >= now
}
