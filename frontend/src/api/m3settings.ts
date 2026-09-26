import client from './client'

/**
 * M3 客户域设置 API（M3-18 切真）。
 * 后端：customer-service M3SettingsController（/api/customer/m3/settings）。
 * client 响应拦截器不拆包，消费侧取 resp.data。
 */

export interface M3ChangeLogRow {
  id: string
  action: string
  by: string
  at: string
}

export interface M3SettingsPayload {
  settings: Record<string, unknown>
  logs: M3ChangeLogRow[]
  updatedAt: string | null
  updatedBy: string | null
}

export interface M3SettingsSaveResp {
  settings: Record<string, unknown>
  changedKeys: string[]
  saved: boolean
}

export function fetchM3Settings() {
  return client.get<M3SettingsPayload>('/customer/m3/settings')
}

export function putM3Settings(body: Record<string, unknown>) {
  return client.put<M3SettingsSaveResp>('/customer/m3/settings', body)
}

export function resetM3Settings() {
  return client.post<M3SettingsSaveResp>('/customer/m3/settings/reset-defaults')
}
