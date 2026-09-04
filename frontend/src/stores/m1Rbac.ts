// ============================================================
// M1 集团管控 · RBAC store（对接 org-service /api/org/admin/*）
// - 角色 = 内置角色（后端矩阵权威源，只读）+ 自定义角色（可增删/改数据域/改功能权限）
// - 角色 CRUD / 功能权限 / 数据域 全部走真实端点（与 T1 权限中台同源）
// - 字段级权限矩阵（HIDE/MASK/READ/EDIT）后端能力尚未落地：
//   仅按内置角色默认策略只读展示规划草案，任何修改不落库、不生效（「字段级管控规划中」）。
// - 审计由后端落 audit-service；后端事实：角色无继承、超管 "*" 不落库（前端补）。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { DataScope } from '@/types/domain'
import {
  listRoles,
  getRolePermissions,
  getRoleMembers,
  createRole as apiCreateRole,
  updateRole as apiUpdateRole,
  deleteRole as apiDeleteRole,
  updateRolePermissions,
  type RoleDef,
} from '@/api/org'

export type FieldAccess = 'HIDE' | 'MASK' | 'READ' | 'EDIT'

export const FIELD_ACCESS_LABEL: Record<FieldAccess, string> = {
  HIDE: '隐藏', MASK: '脱敏', READ: '只读', EDIT: '可编辑',
}
export const FIELD_ACCESS_ORDER: FieldAccess[] = ['HIDE', 'MASK', 'READ', 'EDIT']

// 受字段级管控的敏感字段（按模块分组）——字段级能力后端规划中，此处仅作展示草案
export interface FieldDef { key: string; label: string; module?: string; desc?: string }
export const FIELD_GROUPS: { module: string; fields: FieldDef[] }[] = [
  {
    module: '客户',
    fields: [
      { key: 'customer.phone', label: '手机号', desc: '完整手机号属敏感个人信息' },
      { key: 'customer.idCard', label: '身份证号', desc: '法定敏感信息' },
      { key: 'customer.address', label: '联系地址' },
      { key: 'customer.consumption', label: '累计消费金额' },
    ],
  },
  {
    module: '订单 / 财务',
    fields: [
      { key: 'order.cost', label: '成本价', desc: '仅财务可见（finance:margin:view）' },
      { key: 'order.margin', label: '毛利', desc: '仅财务可见' },
      { key: 'order.commission', label: '提成金额' },
      { key: 'order.payment', label: '支付流水号' },
    ],
  },
  {
    module: '病历 / 医疗',
    fields: [
      { key: 'emr.diagnosis', label: '诊断结论' },
      { key: 'emr.allergy', label: '过敏史' },
      { key: 'emr.treatment', label: '治疗方案' },
      { key: 'emr.photo', label: '术前术后照片' },
    ],
  },
  {
    module: '员工',
    fields: [
      { key: 'staff.salary', label: '薪资' },
      { key: 'staff.performance', label: '业绩明细' },
      { key: 'staff.contract', label: '合同信息' },
    ],
  },
]
export const ALL_FIELDS: FieldDef[] = FIELD_GROUPS.flatMap((g) => g.fields)

/** 内置矩阵角色码（与后端 RbacAdminController.BUILTIN_ROLES 同源） */
const BUILTIN_ROLE_CODES = new Set([
  'SUPER_ADMIN', 'REGION_MGR', 'STORE_MGR', 'CONSULTANT',
  'DOCTOR', 'FRONT_DESK', 'OPERATOR', 'FINANCE',
])

// 内置角色的字段访问策略【规划草案】：后端字段级能力落地前仅只读展示，不代表实际生效
const BUILTIN_FIELD_DEFAULTS: Record<string, Partial<Record<string, FieldAccess>>> = {
  SUPER_ADMIN: Object.fromEntries(ALL_FIELDS.map((f) => [f.key, 'EDIT' as FieldAccess])),
  REGION_MGR: { 'customer.phone': 'READ', 'customer.address': 'READ', 'customer.consumption': 'READ', 'order.commission': 'READ', 'staff.performance': 'READ', 'emr.diagnosis': 'READ' },
  STORE_MGR: { 'customer.phone': 'MASK', 'customer.address': 'READ', 'customer.consumption': 'READ', 'order.commission': 'READ', 'staff.performance': 'READ', 'emr.diagnosis': 'READ' },
  CONSULTANT: { 'customer.phone': 'READ', 'customer.address': 'READ', 'customer.consumption': 'READ' },
  DOCTOR: { 'customer.phone': 'MASK', 'emr.diagnosis': 'EDIT', 'emr.allergy': 'EDIT', 'emr.treatment': 'EDIT', 'emr.photo': 'EDIT' },
  FRONT_DESK: { 'customer.phone': 'READ', 'customer.address': 'READ', 'order.payment': 'READ' },
  OPERATOR: { 'customer.phone': 'MASK', 'customer.consumption': 'READ' },
  FINANCE: { 'customer.phone': 'MASK', 'order.cost': 'EDIT', 'order.margin': 'EDIT', 'order.payment': 'READ', 'order.commission': 'READ' },
}

const SCOPE_LABEL: Record<DataScope, string> = {
  SELF: '仅本人', STORE: '本门店', BRAND: '本品牌', REGION: '本区域', GROUP: '集团',
}

export interface RbacRole {
  id: string
  key: string
  label: string
  builtin: boolean
  scope: DataScope
  permissions: string[] // 功能权限（resource:action），超管为 ['*']
  fields: Record<string, FieldAccess> // 字段 key -> 访问级别（规划草案，不落库）
  desc?: string
  headcount?: number // 挂该角色的人数（后端 role-members 聚合真实值）
  updatedAt: string
}

/** 后端中文数据域 → 前端枚举（后端仅 门店/区域/集团 三档） */
function scopeFromCn(cn: string | undefined): DataScope {
  if (cn === '区域') return 'REGION'
  if (cn === '集团') return 'GROUP'
  return 'STORE'
}

/** 前端枚举 → 后端中文数据域（SELF/BRAND 为视图历史选项，分别就近落 门店/区域） */
function scopeToCn(scope: DataScope): string {
  if (scope === 'REGION' || scope === 'BRAND') return '区域'
  if (scope === 'GROUP') return '集团'
  return '门店'
}

function errMsg(e: unknown, fallback = '网络异常，请稍后重试'): string {
  const anyE = e as { response?: { data?: { message?: string } }; message?: string }
  return anyE?.response?.data?.message || anyE?.message || fallback
}

export const useM1RbacStore = defineStore('m1Rbac', () => {
  const roles = ref<RbacRole[]>([])
  const loaded = ref(false)
  const loading = ref(false)

  // ---- 适配：后端角色定义 + 角色权限 + 成员聚合 → 视图模型 ----
  function adaptRole(
    r: RoleDef,
    perms: Record<string, string[]>,
    members: Record<string, unknown[]>,
    nowIso: string,
  ): RbacRole {
    const builtin = BUILTIN_ROLE_CODES.has(r.roleCode)
    let actions = perms[r.roleCode] ? [...perms[r.roleCode]] : []
    // 超管 "*" 不落库：前端补通配，功能权限区按通配灰显
    if (r.roleCode === 'SUPER_ADMIN') actions = ['*']
    return {
      id: r.roleCode,
      key: r.roleCode,
      label: r.roleName,
      builtin,
      scope: scopeFromCn(r.dataScope),
      permissions: actions,
      // 字段级管控后端规划中：内置角色展示默认策略草案；自定义角色暂无字段策略（默认隐藏）
      fields: builtin
        ? Object.fromEntries(
            Object.entries(BUILTIN_FIELD_DEFAULTS[r.roleCode] ?? {}).filter(([, v]) => v !== undefined),
          ) as Record<string, FieldAccess>
        : {},
      desc: r.description || undefined,
      headcount: members[r.roleCode]?.length ?? 0,
      updatedAt: nowIso,
    }
  }

  function seed() {
    if (loaded.value || loading.value) return
    loading.value = true
    void (async () => {
      try {
        const [rolesRes, rolePermsRes, membersRes] = await Promise.all([
          listRoles(),
          getRolePermissions(),
          getRoleMembers(),
        ])
        const nowIso = new Date().toISOString()
        roles.value = rolesRes.data
          .map((r) => adaptRole(r, rolePermsRes.data, membersRes.data as Record<string, unknown[]>, nowIso))
          .sort((a, b) => (a.builtin === b.builtin ? a.key.localeCompare(b.key) : a.builtin ? -1 : 1))
        loaded.value = true
      } catch (e) {
        // 403/网络失败：保留空表，不弹扰（页面门控本身已按权限隐藏入口）
        console.error('[m1Rbac] 角色数据载入失败', e)
      } finally {
        loading.value = false
      }
    })()
  }

  const builtinRoles = computed(() => roles.value.filter((r) => r.builtin))
  const customRoles = computed(() => roles.value.filter((r) => !r.builtin))

  const stats = computed(() => ({
    total: roles.value.length,
    custom: customRoles.value.length,
    fields: ALL_FIELDS.length,
    headcount: roles.value.reduce((s, r) => s + (r.headcount ?? 0), 0),
  }))

  function get(id: string) {
    return roles.value.find((r) => r.id === id)
  }

  // ---- 字段级权限：后端能力规划中，修改不落库、不生效（视图矩阵整体只读 + 规划中标识） ----
  function setField(_roleId: string, _fieldKey: string, _access: FieldAccess) {
    // no-op：字段级管控待后端落地，禁止假保存
  }

  // ---- 数据域：自定义角色可改，PUT /admin/roles/{code} 全量乐观更新 + 失败回滚 ----
  function setScope(roleId: string, scope: DataScope) {
    const r = get(roleId)
    if (!r || r.builtin) return
    const prev = r.scope
    r.scope = scope
    r.updatedAt = new Date().toISOString()
    void (async () => {
      try {
        await apiUpdateRole(r.key, { dataScope: scopeToCn(scope) })
      } catch (e) {
        r.scope = prev
        window.alert(errMsg(e, '数据范围更新失败，请稍后重试'))
      }
    })()
  }

  // ---- 功能权限：勾选即全量覆写 PUT permissions（后端只存自定义角色授权集），失败回滚 ----
  function togglePermission(roleId: string, perm: string) {
    const r = get(roleId)
    if (!r || r.builtin) return
    const prev = [...r.permissions]
    const i = r.permissions.indexOf(perm)
    if (i >= 0) r.permissions.splice(i, 1)
    else r.permissions.push(perm)
    r.updatedAt = new Date().toISOString()
    void (async () => {
      try {
        await updateRolePermissions(r.key, [...r.permissions])
      } catch (e) {
        r.permissions = prev
        window.alert(errMsg(e, '功能权限更新失败，请稍后重试'))
      }
    })()
  }

  // ---- 新建自定义角色：乐观入列并返回（视图随即选中），POST roles 失败回滚 + 中文报错 ----
  function create(payload: { key: string; label: string; scope: DataScope; desc?: string }): RbacRole {
    const role: RbacRole = {
      id: payload.key.trim().toUpperCase(),
      key: payload.key.trim().toUpperCase(),
      label: payload.label.trim(),
      builtin: false,
      scope: payload.scope,
      permissions: [],
      fields: {},
      desc: payload.desc?.trim() || undefined,
      headcount: 0,
      updatedAt: new Date().toISOString(),
    }
    roles.value.push(role)

    void (async () => {
      try {
        await apiCreateRole({
          roleCode: role.key,
          roleName: role.label,
          dataScope: scopeToCn(role.scope),
          description: role.desc,
        })
      } catch (e) {
        roles.value = roles.value.filter((x) => x.id !== role.id)
        window.alert(errMsg(e, '角色创建失败，请稍后重试'))
      }
    })()
    return role
  }

  // ---- 删除自定义角色：乐观移除，DELETE 失败回滚 + 中文报错（内置角色后端 400） ----
  function remove(id: string) {
    const r = get(id)
    if (!r || r.builtin) return
    const prevRoles = roles.value
    roles.value = roles.value.filter((x) => x.id !== id)
    void (async () => {
      try {
        await apiDeleteRole(r.key)
      } catch (e) {
        roles.value = prevRoles
        window.alert(errMsg(e, '角色删除失败，请稍后重试'))
      }
    })()
  }

  return {
    roles, builtinRoles, customRoles, stats, FIELD_GROUPS, ALL_FIELDS,
    FIELD_ACCESS_LABEL, FIELD_ACCESS_ORDER, SCOPE_LABEL,
    seed, get, setField, setScope, togglePermission,
    create, remove,
  }
})
