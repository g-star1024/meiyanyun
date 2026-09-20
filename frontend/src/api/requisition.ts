import client from './client'

export interface RequisitionItemDto {
  name: string
  spec: string | null
  qty: number
  unit: string
}

export interface RequisitionNoteDto {
  by: string
  text: string
  at: string
}

export interface RequisitionDto {
  id: number
  rqNo: string
  storeCode: string
  status: string
  applicant: string
  purpose: string
  remark: string | null
  approver: string | null
  receiver: string | null
  rejectReason: string | null
  approvedAt: string | null
  receivedAt: string | null
  createdAt: string
  items: RequisitionItemDto[]
  notes: RequisitionNoteDto[]
}

export interface RequisitionLinePayload {
  name: string
  spec?: string | null
  qty: number
  unit: string
}

export function listRequisitions(params: { storeCode?: string; status?: string }) {
  return client.get<RequisitionDto[]>('/stores/requisitions', { params })
}

export function getRequisition(id: string | number) {
  return client.get<RequisitionDto>(`/stores/requisitions/${id}`)
}

export function createRequisition(body: {
  storeCode: string
  applicant?: string
  purpose: string
  remark?: string | null
  items: RequisitionLinePayload[]
}) {
  return client.post<RequisitionDto>('/stores/requisitions', body)
}

export function submitRequisition(id: string | number) {
  return client.post<RequisitionDto>(`/stores/requisitions/${id}/submit`, {})
}

export function approveRequisition(id: string | number, note?: string) {
  return client.post<RequisitionDto>(`/stores/requisitions/${id}/approve`, { note: note ?? null })
}

export function rejectRequisition(id: string | number, reason: string) {
  return client.post<RequisitionDto>(`/stores/requisitions/${id}/reject`, { reason })
}

export function receiveRequisition(id: string | number) {
  return client.post<RequisitionDto>(`/stores/requisitions/${id}/receive`, {})
}

export function addRequisitionNote(id: string | number, note: string) {
  return client.post<RequisitionDto>(`/stores/requisitions/${id}/notes`, { note })
}
