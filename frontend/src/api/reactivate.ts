import client from './client'

export interface ReactivateLogDto {
  id: number
  by: string
  at: string
  action: string
  channel?: string
  result?: string
}

export interface ReactivateCustomerDto {
  id: number
  no: string
  storeCode: string
  name: string
  level: string
  phone: string
  lastVisitDays: number
  cardBalance: number
  tier: string
  status: string
  assignee?: string
  channel?: string
  nextFollowAt?: string
  logs: ReactivateLogDto[]
}

export interface ReactivateAssignBody {
  assignee: string
  channel: string
}

export interface ReactivateVisitBody {
  result: string
  recovered: boolean
}

export function listReactivates(params: { storeCode?: string }) {
  return client.get<ReactivateCustomerDto[]>('/stores/reactivates', { params })
}

export function getReactivate(id: string | number) {
  return client.get<ReactivateCustomerDto>(`/stores/reactivates/${id}`)
}

export function assignReactivate(id: string | number, body: ReactivateAssignBody) {
  return client.post<ReactivateCustomerDto>(`/stores/reactivates/${id}/assign`, body)
}

export function visitReactivate(id: string | number, body: ReactivateVisitBody) {
  return client.post<ReactivateCustomerDto>(`/stores/reactivates/${id}/visit`, body)
}
