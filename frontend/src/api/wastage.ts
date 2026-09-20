import client from './client'

export interface WastageNoteDto {
  by: string
  text: string
  at: string
}

export interface WastageDto {
  id: number
  wsNo: string
  storeCode: string
  status: string
  reason: string
  itemName: string
  spec: string | null
  qty: number
  unit: string
  amountYuan: number
  reporter: string
  location: string | null
  description: string | null
  approver: string | null
  rejectReason: string | null
  occurredAt: string
  approvedAt: string | null
  createdAt: string
  notes: WastageNoteDto[]
}

export interface CreateWastagePayload {
  storeCode: string
  itemName: string
  spec?: string | null
  qty: number
  unit: string
  amountFen: number
  reason: string
  reporter?: string
  location?: string | null
  description?: string | null
  occurredAt?: string | null
}

export function listWastages(params: {
  storeCode?: string
  status?: string
  reason?: string
}) {
  return client.get<WastageDto[]>('/stores/wastages', { params })
}

export function getWastage(id: string | number) {
  return client.get<WastageDto>(`/stores/wastages/${id}`)
}

export function createWastage(body: CreateWastagePayload) {
  return client.post<WastageDto>('/stores/wastages', body)
}

export function submitWastage(id: string | number) {
  return client.post<WastageDto>(`/stores/wastages/${id}/submit`, {})
}

export function approveWastage(id: string | number, note?: string) {
  return client.post<WastageDto>(`/stores/wastages/${id}/approve`, { note: note ?? null })
}

export function rejectWastage(id: string | number, reason: string) {
  return client.post<WastageDto>(`/stores/wastages/${id}/reject`, { reason })
}

export function addWastageNote(id: string | number, note: string) {
  return client.post<WastageDto>(`/stores/wastages/${id}/notes`, { note })
}
