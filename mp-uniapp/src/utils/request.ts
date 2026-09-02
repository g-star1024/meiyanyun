/**
 * uni.request 封装（对齐 B 端前端 /api 网关约定）
 * - 自动拼接 API_BASE、注入租户/鉴权头
 * - 401 时清理登录态并跳转
 * - 后端未就绪时，页面可直接用 store 的本地 seed 数据，不强制走这里
 */
import { apiUrl, TENANT_ID } from './config'

export interface ApiResult<T = unknown> {
  code: number
  message: string
  data: T
}

const TOKEN_KEY = 'mp_token'

export function getToken(): string {
  return uni.getStorageSync(TOKEN_KEY) || ''
}
export function setToken(token: string) {
  uni.setStorageSync(TOKEN_KEY, token)
}
export function clearToken() {
  uni.removeStorageSync(TOKEN_KEY)
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  data?: Record<string, unknown> | string
  /** 是否需要登录态（默认 true） */
  auth?: boolean
  /** 是否静默（不弹错误提示） */
  silent?: boolean
}

export function request<T = unknown>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', data, auth = true, silent = false } = options
  const header: Record<string, string> = {
    'Content-Type': 'application/json',
    'X-Tenant-Id': TENANT_ID,
  }
  const token = getToken()
  if (auth && token) header.Authorization = `Bearer ${token}`

  return new Promise<T>((resolve, reject) => {
    uni.request({
      url: apiUrl(path),
      method,
      data: data as any,
      header,
      success: (res) => {
        const body = res.data as ApiResult<T>
        if (res.statusCode === 401 || body?.code === 401) {
          clearToken()
          if (!silent) uni.showToast({ title: '请先登录', icon: 'none' })
          reject(new Error('UNAUTHORIZED'))
          return
        }
        if (res.statusCode >= 200 && res.statusCode < 300 && (body?.code === 0 || body?.code === 200 || body?.code === undefined)) {
          resolve((body?.data ?? (body as unknown)) as T)
          return
        }
        const msg = body?.message || `请求失败（${res.statusCode}）`
        if (!silent) uni.showToast({ title: msg, icon: 'none' })
        reject(new Error(msg))
      },
      fail: (err) => {
        if (!silent) uni.showToast({ title: '网络异常，请稍后重试', icon: 'none' })
        reject(new Error(err.errMsg || 'NETWORK_ERROR'))
      },
    })
  })
}

export const http = {
  get: <T = unknown>(p: string, o?: RequestOptions) => request<T>(p, { ...o, method: 'GET' }),
  post: <T = unknown>(p: string, data?: unknown, o?: RequestOptions) =>
    request<T>(p, { ...o, method: 'POST', data: data as Record<string, unknown> }),
  put: <T = unknown>(p: string, data?: unknown, o?: RequestOptions) =>
    request<T>(p, { ...o, method: 'PUT', data: data as Record<string, unknown> }),
  delete: <T = unknown>(p: string, o?: RequestOptions) => request<T>(p, { ...o, method: 'DELETE' }),
}
