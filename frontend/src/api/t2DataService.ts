import client from './client'

export interface ServiceView {
  id: number
  name: string
  type: string
  endpoint: string | null
  method: string | null
  description: string
  owner: string
  status: string
  callCount24h: number
  avgLatency: number
  errorRate: number
  fields: string[]
  tags: string[]
  version: string
  createdAt: string
}

export interface PermissionView {
  id: number
  serviceId: number
  serviceName: string
  applicant: string
  reason: string
  status: string
  appliedAt: string
  decidedAt: string | null
  decidedBy: string | null
}

/** 服务新建请求体（对齐后端 ServiceReq）。 */
export interface ServiceReq {
  name: string
  type: string
  endpoint?: string
  method?: string
  description: string
  fields: string[]
  tags: string[]
}

/** 权限申请请求体（applicant 不收，后端取操作人）。 */
export interface ApplyReq {
  reason: string
}

export function fetchServices(params?: { type?: string; keyword?: string }): Promise<ServiceView[]> {
  return client.get('/customer/t2/dataservice/services', { params }).then((r) => r.data)
}

export function fetchPermissions(): Promise<PermissionView[]> {
  return client.get('/customer/t2/dataservice/permissions').then((r) => r.data)
}

export function createService(cmd: ServiceReq): Promise<ServiceView> {
  return client.post('/customer/t2/dataservice/services', cmd).then((r) => r.data)
}

export function publishService(id: number): Promise<ServiceView> {
  return client.post(`/customer/t2/dataservice/services/${id}/publish`).then((r) => r.data)
}

export function deprecateService(id: number): Promise<ServiceView> {
  return client.post(`/customer/t2/dataservice/services/${id}/deprecate`).then((r) => r.data)
}

export function applyPermission(id: number, cmd: ApplyReq): Promise<PermissionView> {
  return client.post(`/customer/t2/dataservice/services/${id}/apply`, cmd).then((r) => r.data)
}

export function approvePermission(id: number): Promise<PermissionView> {
  return client.post(`/customer/t2/dataservice/permissions/${id}/approve`).then((r) => r.data)
}

export function rejectPermission(id: number): Promise<PermissionView> {
  return client.post(`/customer/t2/dataservice/permissions/${id}/reject`).then((r) => r.data)
}
