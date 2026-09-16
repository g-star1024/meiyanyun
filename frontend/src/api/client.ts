import axios from 'axios'

// 经 Vite dev proxy（或生产国密网关）转发到后端微服务。
const client = axios.create({
  baseURL: '/api',
  timeout: 10000,
})

// token 存储键（与 stores/auth.ts 约定一致；此处直接读写 localStorage 以避免与 store 循环依赖）
export const TOKEN_KEY = 'meiyun_token'
const SESSION_KEY = 'meiyun_session'
// 代操作原会话快照键（sessionStorage，B55）：值为切换前真实超管的 SessionInfo JSON。
// 存 sessionStorage 而非 localStorage：刷新/重开标签页即失效——刷新语义=放弃代操作还原真实人。
const IMP_SNAPSHOT_KEY = 'meiyun_imp_snapshot'

export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function setToken(token: string) {
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY)
}

/** 读取代操作原会话快照（无快照返回 null） */
export function getImpSnapshot(): string | null {
  return sessionStorage.getItem(IMP_SNAPSHOT_KEY)
}

/** 落代操作原会话快照（开始代操作前调用） */
export function saveImpSnapshot(sessionJson: string) {
  sessionStorage.setItem(IMP_SNAPSHOT_KEY, sessionJson)
}

/** 清除代操作快照（正常退出/还原后调用） */
export function clearImpSnapshot() {
  sessionStorage.removeItem(IMP_SNAPSHOT_KEY)
}

/**
 * 代操作态本地还原：把快照中的真实超管会话写回 localStorage/token，返回快照 JSON。
 * 用于短 token 401 与页面刷新引导；无快照返回 null（交由常规登出流程）。
 */
export function restoreImpSnapshot(): string | null {
  const raw = getImpSnapshot()
  if (!raw) return null
  try {
    const s = JSON.parse(raw)
    if (s?.token) {
      setToken(s.token)
      localStorage.setItem(SESSION_KEY, raw)
    }
  } catch {
    return null
  }
  clearImpSnapshot()
  return raw
}

/** 清空全部登录态（token + 会话 + 代操作快照），供 401 拦截器在 store 之外同步调用 */
export function clearAuth() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(SESSION_KEY)
  clearImpSnapshot()
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

// 响应拦截器：
// - 401 且处于代操作态（存在原会话快照）：短 token 超时/失效，本地还原真实超管会话并提示，
//   不清登录态、不跳登录页、不自动重试原请求（写请求静默重试会以超管身份误提权）；
// - 其余 401（普通会话失效）：清登录态并跳登录页。403 交由业务页/路由守卫处理。
client.interceptors.response.use(
  (resp) => resp,
  async (error) => {
    const status = error?.response?.status
    const url: string = error?.config?.url || ''
    const isAuthCall = PUBLIC_PATHS.some((p) => url.includes(p))
    if (status === 401 && !isAuthCall && getImpSnapshot()) {
      const restored = restoreImpSnapshot()
      if (restored) {
        const [{ useAuthStore }, { useToast }] = await Promise.all([
          import('@/stores/auth'),
          import('@/composables/useToast'),
        ])
        useAuthStore().applyRestoredSnapshot(restored)
        useToast().warning('代操作会话已超时退出，已还原为您的真实账户')
        return Promise.reject(error)
      }
    }
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
