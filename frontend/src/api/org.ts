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
  /** 中文落库：集团 | 区域 | 门店 | 部门 */
  orgType: string
  /** 英文枚举：GROUP | REGION | STORE | DEPT */
  orgTypeCode?: string
  parentCode?: string
  storeCode?: string
  region?: string
  brandId?: string
  /** 中文落库：启用 | 停用 */
  status?: string
  /** ACTIVE | INACTIVE */
  statusCode?: string
  leaderName?: string | null
  headcount?: number | null
  inactiveReason?: string | null
  remark?: string | null
  sortNo?: number
  createdAt?: string | null
  children?: OrgTreeNode[]
}

export interface OrgTreeNode extends OrgUnit {}

/** 新建组织单元（B33：后端仅允许在门店下新建部门） */
export interface OrgUnitCreatePayload {
  orgCode: string
  orgName: string
  parentCode: string
  leaderName?: string | null
  headcount?: number | null
  sortNo?: number | null
  remark?: string | null
}

/**
 * 编辑组织单元。可空文本字段语义与后端对齐：
 * 字段缺省（undefined）= 保持原值；空串 = 显式清空；非空 = 更新。
 * parentCode 仅部门可传（跨门店移动），其他类型传入即 400。
 */
export interface OrgUnitUpdatePayload {
  orgName?: string
  parentCode?: string
  leaderName?: string | null
  headcount?: number | null
  sortNo?: number | null
  remark?: string | null
}

export interface ToggleStatusPayload {
  enable: boolean
  /** 停用时必填（后端校验，留痕审计） */
  reason?: string
}

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

/** 门店主数据（store-service）；region/nature/status/openDate 为 B49 卡3 补声明的既有返回字段 */
export interface Store {
  storeCode: string
  storeName: string
  /** 大区（中文短名：华东/华南…） */
  region?: string
  /** 经营性质（直营/联营） */
  nature?: string
  /** 营业状态（中文落库：营业中/筹建中/已关店） */
  status?: string
  /** 开业日期 YYYY-MM-DD；筹建中门店为 null */
  openDate?: string | null
}

/** 区域门店分布统计（store-service /stores/regions/dist，集团聚合通道） */
export interface StoreRegionDist {
  region: string
  openCnt: number
  ownCnt: number
  jointCnt: number
  buildingCnt: number
  closedCnt: number
  total: number
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

/** 兼岗范围行（B87）：roleCode + orgCode（''=全局，否则 org_unit 大区/门店节点码） */
export interface StaffRoleScopeRow {
  roleCode: string
  orgCode: string
}

export interface StaffRolesView {
  staffId: string
  primaryRole: string
  roles: string[]
  /** 兼岗范围明细；org 旧版无此字段，调用方按 roles 映射 '' 回落 */
  rolesDetail?: StaffRoleScopeRow[]
}

// -------------------- 组织只读 --------------------

export const getOrgTree = () => client.get<OrgTreeNode>('/org/tree')
export const getRegions = () => client.get<{ region: string; storeCount: number }[]>('/org/regions')
export const listRoles = () => client.get<RoleDef[]>('/org/roles')
export const getRoleMatrix = () => client.get<Record<string, number>>('/org/role-matrix')
/**
 * 员工列表（/org/staff，DataScope 注入：STORE 本店 / REGION 本区含大区编制 / GROUP 全量）。
 * 兼容旧调用 listStaff(storeCode)；新调用可传 { storeCode, roleCode, region }：
 * - roleCode：匹配主角色 + staff_role 兼岗并集（候选过滤）；
 * - region：REGION 域被后端强制回收到登录人大区，禁止跨区取人。
 */
export const listStaff = (params?: string | { storeCode?: string; roleCode?: string; region?: string }) => {
  const normalized = typeof params === 'string' ? { storeCode: params || undefined } : (params || {})
  return client.get<Staff[]>('/org/staff', { params: normalized })
}
export const listStores = () => client.get<Store[]>('/stores')
/** 六区门店分布统计（营业/直营/联营/筹建/关店；DataScope 豁免的集团聚合通道） */
export const listStoreRegionDist = () => client.get<StoreRegionDist[]>('/stores/regions/dist')

// -------------------- 组织树写（B33） --------------------

/** 新建部门（唯一可新建的组织层级，父必须是门店） */
export const createOrgUnit = (payload: OrgUnitCreatePayload) =>
  client.post<OrgUnit>('/org/admin/org-units', payload)

/** 编辑节点 / 部门跨门店移动；返回原始实体，调用方需重载树 */
export const updateOrgUnit = (orgCode: string, payload: OrgUnitUpdatePayload) =>
  client.put<OrgUnit>(`/org/admin/org-units/${orgCode}`, payload)

/** 启用/停用节点；停用 reason 必填 */
export const toggleOrgUnitStatus = (orgCode: string, payload: ToggleStatusPayload) =>
  client.post<OrgUnit>(`/org/admin/org-units/${orgCode}/toggle-status`, payload)

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

/** 授予兼岗角色；orgCode 缺省 ''=全局，否则挂大区/门店节点码（B87） */
export const addStaffRole = (staffId: string, roleCode: string, orgCode = '') =>
  client.post<{ staffId: string; roleCode: string; orgCode: string; added: boolean }>(
    `/org/admin/staff/${staffId}/roles`,
    { roleCode, orgCode },
  )

/** 摘除兼岗角色；orgCode 缺省 ''=摘全局行（同角色多范围行需传真实节点码区分） */
export const removeStaffRole = (staffId: string, roleCode: string, orgCode = '') =>
  client.delete<{ staffId: string; roleCode: string; orgCode: string; removed: boolean }>(
    `/org/admin/staff/${staffId}/roles/${roleCode}`,
    { params: orgCode ? { orgCode } : undefined },
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
