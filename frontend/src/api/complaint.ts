import client from './client'

export interface ComplaintTimelineItem {
  at: string
  by: string
  action: string
  note: string | null
}

export interface ComplaintView {
  id: number
  complaintNo: string
  customerId: string
  customerName: string
  source: string
  severity: string
  category: string
  medicalRisk: boolean
  description: string
  relatedOrderNo: string | null
  storeId: string | null
  storeName: string | null
  status: string
  compensationAmount: number
  signTier: string
  resolution: string | null
  createdAt: string
  acceptedByName: string | null
  acceptedAt: string | null
  submittedByName: string | null
  submittedAt: string | null
  closedByName: string | null
  closedAt: string | null
  rejectionReason: string | null
  timeline: ComplaintTimelineItem[]
}

export interface CreateComplaintCmd {
  customerId: string
  customerName: string
  source: string
  severity: string
  category: string
  medicalRisk: boolean
  description: string
  relatedOrderNo?: string
  compensationAmount?: number
}

export function listComplaints(status?: string, medicalOnly?: boolean): Promise<ComplaintView[]> {
  const params: Record<string, unknown> = {}
  if (status) params.status = status
  if (medicalOnly) params.medicalOnly = true
  return client.get('/customer/m3/complaint/records', { params }).then((r) => r.data)
}

export function createComplaint(cmd: CreateComplaintCmd): Promise<ComplaintView> {
  return client.post('/customer/m3/complaint/records', cmd).then((r) => r.data)
}

export function acceptComplaint(id: number): Promise<ComplaintView> {
  return client.post(`/customer/m3/complaint/${id}/accept`).then((r) => r.data)
}

export function submitComplaintResolution(id: number, resolution: string, compensationAmount?: number): Promise<ComplaintView> {
  return client.post(`/customer/m3/complaint/${id}/submit-resolution`, { resolution, compensationAmount }).then((r) => r.data)
}

export function approveComplaintClose(id: number): Promise<ComplaintView> {
  return client.post(`/customer/m3/complaint/${id}/approve-close`).then((r) => r.data)
}

export function sendBackComplaint(id: number, note: string): Promise<ComplaintView> {
  return client.post(`/customer/m3/complaint/${id}/send-back`, { note }).then((r) => r.data)
}

export function rejectComplaint(id: number, reason: string): Promise<ComplaintView> {
  return client.post(`/customer/m3/complaint/${id}/reject`, { reason }).then((r) => r.data)
}
