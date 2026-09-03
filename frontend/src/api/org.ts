// ============================================================
// Organization / RBAC API（对接 org-service）
// 多租户层级：GROUP > BRAND/REGION > STORE。
// ============================================================
import client from './client'

export interface Tenant {
  tenantId: string
  tenantName: string
  brand: string
  status: string
}

export interface OrgUnit {
  orgCode: string
  orgName: string
  orgType: string // 集团 | 品牌 | 区域 | 门店
  storeCode?: string
  region?: string
  brandId?: string
  children?: OrgTreeNode[]
}

export interface OrgTreeNode extends OrgUnit {}

export interface RoleDef {
  roleCode: string
  roleName: string
  /** 数据域（中文落库）：门店 | 区域 | 集团 */
  dataScope: string
  roleSequence: string
  medical: boolean
  description?: string
  /** 状态（中文落库）：启用 | 停用；停用角色不可再授予员工 */
  status?: string
  /** 自定义角色的权限码集合（内置角色为空，权限由矩阵权威源定义） */
  permissions?: string[]
}

export interface Staff {
  staffId: string
  staffName: string
  roleCode: string
  storeCode: string | null
  region?: string | null
  medicalLicensed: boolean
  /** 在职 | 离职 */
  status: string
  loginName?: string
  createdAt?: string
  /** Controller 富化的主角色定义 */
  role?: RoleDef
}

/** 权限字典项（permission_def）：resource:action[:field] */
export interface PermissionDef {
  permissionCode: string
  resourceCode: string
  actionCode: string
  description?: string
}

/** 门店主数据（store-service） */
export interface Store {
  storeCode: string
  storeName: string
}

export interface StaffCreatePayload {
  staffId: string
  staffName: string
  roleCode: string
  storeCode?: string | null
  region?: string | null
  medicalLicensed?: boolean
}

export interface StaffTransferPayload {
  storeCode?: string | null
  region?: string | null
}

export interface RoleCreatePayload {
  roleCode: string
  roleName: string
  /** 中文：门店 | 区域 | 集团 */
  dataScope: string
  roleSequence?: string
  medical?: boolean
  description?: string
}

export interface RoleUpdatePayload {
  roleName?: string
  dataScope?: string
  roleSequence?: string
  medical?: boolean
  description?: string
}

export interface StaffRolesView {
  staffId: string
  primaryRole: string
  roles: string[]
}

// -------------------- 组织只读 --------------------

export const getOrgTree = () => client.get<OrgTreeNode>('/org/tree')
export const getRegions = () => client.get<{ region: string; storeCount: number }[]>('/org/regions')
export const listRoles = () => client.get<RoleDef[]>('/org/roles')
export const getRoleMatrix = () => client.get<Record<string, number>>('/org/role-matrix')
export const listStaff = (storeCode?: string) =>
  client.get<Staff[]>('/org/staff', { params: { storeCode } })
export const listStores = () => client.get<Store[]>('/stores')

// -------------------- RBAC 管理：员工 --------------------

export const createStaff = (payload: StaffCreatePayload) =>
  client.post<Staff>('/org/admin/staff', payload)

export const disableStaff = (staffId: string) =>
  client.post<Staff>(`/org/admin/staff/${staffId}/disable`)

export const resetStaffPassword = (staffId: string) =>
  client.post<{ staffId: string; reset: boolean; defaultPassword: string }>(
    `/org/admin/staff/${staffId}/reset-password`,
  )

export const transferStaff = (staffId: string, payload: StaffTransferPayload) =>
  client.post<Staff>(`/org/admin/staff/${staffId}/transfer`, payload)

export const setPrimaryRole = (staffId: string, roleCode: string) =>
  client.post<Staff>(`/org/admin/staff/${staffId}/primary-role`, { roleCode })

export const addStaffRole = (staffId: string, roleCode: string) =>
  client.post<{ staffId: string; roleCode: string; added: boolean }>(
    `/org/admin/staff/${staffId}/roles`,
    { roleCode },
  )

export const removeStaffRole = (staffId: string, roleCode: string) =>
  client.delete<{ staffId: string; roleCode: string; removed: boolean }>(
    `/org/admin/staff/${staffId}/roles/${roleCode}`,
  )

export const getStaffRoles = (staffId: string) =>
  client.get<StaffRolesView>(`/org/admin/staff/${staffId}/roles`)

// -------------------- RBAC 管理：角色 --------------------

export const createRole = (payload: RoleCreatePayload) =>
  client.post<RoleDef>('/org/admin/roles', payload)

export const updateRole = (roleCode: string, payload: RoleUpdatePayload) =>
  client.put<RoleDef>(`/org/admin/roles/${roleCode}`, payload)

/** 停用/启用切换（自定义角色；内置角色 400） */
export const toggleRoleStatus = (roleCode: string) =>
  client.post<RoleDef>(`/org/admin/roles/${roleCode}/toggle-status`)

export const deleteRole = (roleCode: string) =>
  client.delete<{ roleCode: string; deleted: boolean }>(`/org/admin/roles/${roleCode}`)

export const updateRolePermissions = (roleCode: string, permissionCodes: string[]) =>
  client.put<{ roleCode: string; permissionCount: number }>(
    `/org/admin/roles/${roleCode}/permissions`,
    { permissionCodes },
  )

// -------------------- RBAC 管理：只读聚合 --------------------

/** 权限字典全量（约 320 码，按 resource:action[:field]） */
export const listAdminPermissions = () =>
  client.get<PermissionDef[]>('/org/admin/permissions')

/** 角色 × 权限映射：{roleCode: [permissionCode...]}（超管 "*" 不落库，返回空集） */
export const getRolePermissions = () =>
  client.get<Record<string, string[]>>('/org/admin/role-permissions')

/** 角色 × 成员聚合：{roleCode: [Staff...]}（兼岗与主角色均归组，经数据域过滤） */
export const getRoleMembers = () =>
  client.get<Record<string, Staff[]>>('/org/admin/role-members')
