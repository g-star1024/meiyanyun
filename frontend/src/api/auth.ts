import client from './client'

// ============================================================
// 认证 API（M7）：对接 org-service /api/org/auth/*
// - login      工号 + 密码登录，签发自包含 JWT
// - devLogin   开发期免密登录（联调/演示；?as= 角色切换走这里）
// - getPermissions 权限字典 + 角色权限矩阵（后端唯一真源）
// - impersonate     超管代操作：签发 30 分钟短 token（sub=目标人，realSub=超管）
// - exitImpersonate 退出代操作：凭短 token 重签超管常规 TTL token
// 注意：login/dev-login 在后端 public-paths 白名单内，无需 token；
// impersonate/exit 需登录态，由请求拦截器自动带 Bearer。
// ============================================================

export interface LoginResult {
  token: string
  staffId: string
  staffName: string
  roles: string[]
  roleCode: string
  storeCode: string
  scope: string
  permissions: string[]
  devLogin: boolean
  // 代操作短 token 追加字段（B55）：普通登录时 impersonating=false、realSub/act 为 null
  impersonating?: boolean
  realSub?: string | null
  act?: string | null
  ttlSeconds?: number
}

export interface PermissionMatrix {
  permissionDefs: string[]
  rolePermissions: Record<string, string[]>
  roles: { roleCode: string; roleName: string; scope: string; medical: boolean }[]
}

/** 工号 + 密码登录 */
export const login = (loginName: string, password: string) =>
  client.post<LoginResult>('/org/auth/login', { loginName, password }).then((r) => r.data)

/**
 * 开发期免密登录：
 * - 传 staffId → 签发该员工真实 token；
 * - 传 role    → 取该角色第一名员工（顶栏角色切换器用）。
 */
export const devLogin = (body: { staffId?: string; role?: string }) =>
  client.post<LoginResult>('/org/auth/dev-login', body).then((r) => r.data)

/** 拉取权限字典 + 角色权限矩阵（前端启动/登录后同步真源） */
export const getPermissions = () =>
  client.get<PermissionMatrix>('/org/auth/permissions').then((r) => r.data)

/**
 * 超管代操作（B55）：仅真实超管可发起，目标为在职非超管员工。
 * 后端签发 30 分钟短 token（sub=目标人、realSub=超管、act=目标人），
 * 权限/数据域按目标身份收窄，同请求写 COMPLIANCE/IMPERSONATE_START 审计。
 */
export const impersonate = (body: { targetStaffId: string; reason: string }) =>
  client.post<LoginResult>('/org/auth/impersonate', body).then((r) => r.data)

/** 退出代操作：凭短 token realSub 重签超管常规 TTL token，并写 IMPERSONATE_END 审计 */
export const exitImpersonate = () =>
  client.post<LoginResult>('/org/auth/impersonate/exit').then((r) => r.data)
