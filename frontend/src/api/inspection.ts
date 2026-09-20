import client from './client'

export interface InspectionItemDto {
  name: string
  score: number
  note?: string | null
}

export interface RectifyIssueDto {
  id: number
  desc: string
  owner: string
  status: string
  dueAt: string
  hasPhoto: boolean
}

export interface InspectionDto {
  id: number
  no: string
  storeCode: string
  inspectedAt: string
  type: string
  totalScore: number
  issueCount: number
  status: string
  inspector: string
  items: InspectionItemDto[]
  issues: RectifyIssueDto[]
  createdAt: string
  completedAt?: string | null
}

export interface InspectionCreateBody {
  type: string
  inspector: string
  inspectedAt: string
  items: Array<{ name: string; score: number; note?: string | null }>
}

export function listInspections(params: { storeCode?: string }) {
  return client.get<InspectionDto[]>('/stores/inspections', { params })
}

export function getInspection(id: string | number) {
  return client.get<InspectionDto>(`/stores/inspections/${id}`)
}

export function createInspection(params: { storeCode?: string }, body: InspectionCreateBody) {
  return client.post<InspectionDto>('/stores/inspections', body, { params })
}

export function assignIssue(issueId: string | number, owner: string) {
  return client.post<InspectionDto>(`/stores/inspections/issues/${issueId}/assign`, { owner })
}

export function completeIssue(issueId: string | number) {
  return client.post<InspectionDto>(`/stores/inspections/issues/${issueId}/complete`)
}
