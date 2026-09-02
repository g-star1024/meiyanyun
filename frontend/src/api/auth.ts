import client from './client'

// ============================================================
// 认证 API（M7）：对接 org-service /api/org/auth/*
// - login      工号 + 密码登录，签发自包含 JWT
// - devLogin   开发期免密登录（联调/演示；?as= 角色切换走这里）
// - getPermissions 权限字典 + 角色权限矩阵（后端唯一真源）
// 注意：这三个请求在后端 public-paths 白名单内，无需 token。
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
