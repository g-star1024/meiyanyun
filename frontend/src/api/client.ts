import axios from 'axios'

// 经 Vite dev proxy（或生产国密网关）转发到后端微服务。
const client = axios.create({
  baseURL: '/api',
  timeout: 10000,
})

// token 存储键（与 stores/auth.ts 约定一致；此处直接读写 localStorage 以避免与 store 循环依赖）
export const TOKEN_KEY = 'meiyun_token'
const SESSION_KEY = 'meiyun_session'

export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function setToken(token: string) {
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY)
}

/** 清空全部登录态（token + 会话），供 401 拦截器在 store 之外同步调用 */
export function clearAuth() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(SESSION_KEY)
}

// 请求拦截器：为所有 /api 请求注入 Bearer token（白名单公共路径除外）
const PUBLIC_PATHS = ['/org/auth/login', '/org/auth/dev-login']

client.interceptors.request.use((config) => {
  const url = config.url || ''
  const isPublic = PUBLIC_PATHS.some((p) => url.includes(p))
  if (!isPublic) {
    const token = getToken()
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
  }
  return config
})

// 响应拦截器：401（未登录/token 失效）清登录态并跳登录页；403 交由业务页/路由守卫处理
client.interceptors.response.use(
  (resp) => resp,
  (error) => {
    const status = error?.response?.status
    const url: string = error?.config?.url || ''
    const isAuthCall = PUBLIC_PATHS.some((p) => url.includes(p))
    if (status === 401 && !isAuthCall) {
      clearAuth()
      // 避免在登录页重复跳转
      if (!location.pathname.startsWith('/login')) {
        const redirect = encodeURIComponent(location.pathname + location.search)
        location.href = `/login?redirect=${redirect}`
      }
    }
    return Promise.reject(error)
  },
)

export default client
