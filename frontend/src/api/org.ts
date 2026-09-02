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
  dataScope: string
  roleSequence: string
  medical: boolean
  /** 自定义角色的权限码集合（内置角色为空，权限由前端 ROLE_PERMISSIONS 定义） */
  permissions?: string[]
}

export interface Staff {
  staffId: string
  staffName: string
  roleCode: string
  storeCode: string | null
  medicalLicensed: boolean
  status: string
}

export const getOrgTree = () => client.get<OrgTreeNode>('/org/tree')
export const getRegions = () => client.get<{ region: string; storeCount: number }[]>('/org/regions')
export const listRoles = () => client.get<RoleDef[]>('/org/roles')
export const getRoleMatrix = () => client.get<Record<string, number>>('/org/role-matrix')
export const listStaff = (storeCode?: string) =>
  client.get<Staff[]>('/org/staff', { params: { storeCode } })
export const listStores = () => client.get('/stores')
