import client from './client'

export interface WorkOrderNoteDto {
  by: string
  text: string
  at: string
}

export interface WorkOrderDto {
  id: number
  woNo: string
  storeCode: string
  type: string
  title: string
  description: string
  customerName: string | null
  project: string | null
  room: string | null
  assignee: string
  status: string
  priority: string
  deadline: string
  createdAt: string
  startedAt: string | null
  completedAt: string | null
  notes: WorkOrderNoteDto[]
}

export interface WorkOrderPayload {
  storeCode?: string
  type: string
  title: string
  description: string
  customerName?: string | null
  project?: string | null
  room?: string | null
  assignee?: string | null
  priority?: string
  deadline?: string | null
}

export function listWorkOrders(params: {
  storeCode?: string
  type?: string
  status?: string
  assignee?: string
}) {
  return client.get<WorkOrderDto[]>('/stores/work-orders', { params })
}

export function getWorkOrder(id: string | number) {
  return client.get<WorkOrderDto>(`/stores/work-orders/${id}`)
}

export function createWorkOrder(body: WorkOrderPayload) {
  return client.post<WorkOrderDto>('/stores/work-orders', body)
}

export function startWorkOrder(id: string | number) {
  return client.post<WorkOrderDto>(`/stores/work-orders/${id}/start`, {})
}

export function completeWorkOrder(id: string | number, note?: string) {
  return client.post<WorkOrderDto>(`/stores/work-orders/${id}/complete`, { note: note ?? null })
}

export function escalateWorkOrder(id: string | number, reason: string) {
  return client.post<WorkOrderDto>(`/stores/work-orders/${id}/escalate`, { reason })
}
