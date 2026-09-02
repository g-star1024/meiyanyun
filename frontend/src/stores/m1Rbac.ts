import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { DataScope, Role } from '@/types/domain'
import { useAuthStore } from '@/stores/auth'

// ============================================================
// 字段级 RBAC store（M1 集团管控 / 字段级RBAC）
// - 角色 = 内置角色（只读）+ 自定义角色（可增删改）
// - 字段级权限：每个敏感字段可配置 访问级别（HIDE 隐藏 / MASK 脱敏 / READ 只读 / EDIT 可编辑）
// - 自定义角色保存时通过 auth.registerCustomRole 注册，实时生效于 v-perm/can()
// - 数据域 scope（SELF/STORE/BRAND/REGION/GROUP）
// ============================================================

export type FieldAccess = 'HIDE' | 'MASK' | 'READ' | 'EDIT'

export const FIELD_ACCESS_LABEL: Record<FieldAccess, string> = {
  HIDE: '隐藏', MASK: '脱敏', READ: '只读', EDIT: '可编辑',
}
export const FIELD_ACCESS_ORDER: FieldAccess[] = ['HIDE', 'MASK', 'READ', 'EDIT']

// 受字段级管控的敏感字段（按模块分组）
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

// 内置角色的默认字段访问策略（只读展示，不可改）
const BUILTIN_FIELD_DEFAULTS: Record<Role, Partial<Record<string, FieldAccess>>> = {
  SUPER_ADMIN: Object.fromEntries(ALL_FIELDS.map((f) => [f.key, 'EDIT' as FieldAccess])),
  REGION_MGR: { 'customer.phone': 'READ', 'customer.address': 'READ', 'customer.consumption': 'READ', 'order.commission': 'READ', 'staff.performance': 'READ', 'emr.diagnosis': 'READ' },
  STORE_MGR: { 'customer.phone': 'MASK', 'customer.address': 'READ', 'customer.consumption': 'READ', 'order.commission': 'READ', 'staff.performance': 'READ', 'emr.diagnosis': 'READ' },
  CONSULTANT: { 'customer.phone': 'READ', 'customer.address': 'READ', 'customer.consumption': 'READ' },
  DOCTOR: { 'customer.phone': 'MASK', 'emr.diagnosis': 'EDIT', 'emr.allergy': 'EDIT', 'emr.treatment': 'EDIT', 'emr.photo': 'EDIT' },
  FRONT_DESK: { 'customer.phone': 'READ', 'customer.address': 'READ', 'order.payment': 'READ' },
  OPERATOR: { 'customer.phone': 'MASK', 'customer.consumption': 'READ' },
  FINANCE: { 'customer.phone': 'MASK', 'order.cost': 'EDIT', 'order.margin': 'EDIT', 'order.payment': 'READ', 'order.commission': 'READ' },
}

const BUILTIN_LABEL: Record<Role, string> = {
  SUPER_ADMIN: '集团管理员', REGION_MGR: '区域经理', STORE_MGR: '门店店长',
  CONSULTANT: '咨询顾问', DOCTOR: '医生', FRONT_DESK: '前台/收银',
  OPERATOR: '运营', FINANCE: '财务',
}

const SCOPE_LABEL: Record<DataScope, string> = {
  SELF: '仅本人', STORE: '本门店', BRAND: '本品牌', REGION: '本区域', GROUP: '集团',
}

export interface RbacRole {
  id: string
  key: string
  label: string
  builtin: boolean
  builtinRole?: Role
  scope: DataScope
  permissions: string[] // 功能权限（resource:action）
  fields: Record<string, FieldAccess> // 字段 key -> 访问级别
  desc?: string
  headcount?: number // 挂该角色的人数（内置角色给固定值，自定义角色 seed）
  updatedAt: string
}

let _cid = 0
function cid(prefix: string) {
  _cid += 1
  return `${prefix}-${Date.now().toString(36)}-${_cid}`
}

function now() { return new Date().toISOString() }

export const useM1RbacStore = defineStore('m1Rbac', () => {
  const auth = useAuthStore()

  const roles = ref<RbacRole[]>([])
  const seeded = ref(false)

  function fieldAccess(role: RbacRole, key: string): FieldAccess {
    return role.fields[key] ?? 'HIDE'
  }

  function buildBuiltin(): RbacRole[] {
    const counts: Record<Role, number> = {
      SUPER_ADMIN: 2, REGION_MGR: 5, STORE_MGR: 18, CONSULTANT: 64,
      DOCTOR: 42, FRONT_DESK: 30, OPERATOR: 12, FINANCE: 8,
    }
    return (Object.keys(BUILTIN_LABEL) as Role[]).map((r) => ({
      id: `builtin-${r}`,
      key: r,
      label: BUILTIN_LABEL[r],
      builtin: true,
      builtinRole: r,
      scope: authScopeFor(r),
      permissions: builtinPermissions(r),
      fields: Object.fromEntries(
        Object.entries(BUILTIN_FIELD_DEFAULTS[r] ?? {}).filter(([, v]) => v !== undefined),
      ) as Record<string, FieldAccess>,
      headcount: counts[r],
      updatedAt: now(),
    }))
  }

  function seed() {
    if (seeded.value) return
    roles.value = buildBuiltin()
    // 两笔自定义角色 seed
    roles.value.push({
      id: cid('role'), key: 'CLINIC_NURSE', label: '皮肤科护士', builtin: false,
      scope: 'STORE',
      permissions: ['emr:view', 'writeoff:view', 'writeoff:create', 'customer:view', 'appointment:view'],
      fields: { 'customer.phone': 'MASK', 'emr.diagnosis': 'READ', 'emr.treatment': 'READ', 'emr.allergy': 'READ' },
      desc: '门店护士：可执行核销、查看病历（只读）、手机号脱敏',
      headcount: 15, updatedAt: now(),
    })
    roles.value.push({
      id: cid('role'), key: 'CHAIN_AUDITOR', label: '连锁稽核', builtin: false,
      scope: 'GROUP',
      permissions: ['audit:view', 'compliance:view', 'report:view', 'finance:margin:view', 'tenant:view', 'org:view'],
      fields: { 'order.cost': 'READ', 'order.margin': 'READ', 'customer.phone': 'MASK', 'customer.idCard': 'MASK' },
      desc: '集团稽核：跨店只读、财务字段只读、敏感信息脱敏',
      headcount: 3, updatedAt: now(),
    })
    seeded.value = true
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

  // ---- 字段级权限更新 ----
  function setField(roleId: string, fieldKey: string, access: FieldAccess) {
    const r = get(roleId)
    if (!r || r.builtin) return
    if (access === 'HIDE') delete r.fields[fieldKey]
    else r.fields[fieldKey] = access
    r.updatedAt = now()
    registerToAuth(r)
  }

  function setScope(roleId: string, scope: DataScope) {
    const r = get(roleId)
    if (!r || r.builtin) return
    r.scope = scope
    r.updatedAt = now()
    registerToAuth(r)
  }

  function togglePermission(roleId: string, perm: string) {
    const r = get(roleId)
    if (!r || r.builtin) return
    const i = r.permissions.indexOf(perm)
    if (i >= 0) r.permissions.splice(i, 1)
    else r.permissions.push(perm)
    r.updatedAt = now()
    registerToAuth(r)
  }

  // ---- 增删 ----
  function create(payload: { key: string; label: string; scope: DataScope; desc?: string }): RbacRole {
    const role: RbacRole = {
      id: cid('role'), key: payload.key.trim().toUpperCase(), label: payload.label.trim(),
      builtin: false, scope: payload.scope, permissions: [], fields: {},
      desc: payload.desc?.trim() || undefined, headcount: 0, updatedAt: now(),
    }
    roles.value.push(role)
    registerToAuth(role)
    return role
  }

  function update(id: string, patch: Partial<Pick<RbacRole, 'label' | 'desc' | 'key'>>) {
    const r = get(id)
    if (!r || r.builtin) return
    if (patch.label !== undefined) r.label = patch.label
    if (patch.key !== undefined) r.key = patch.key.trim().toUpperCase()
    if (patch.desc !== undefined) r.desc = patch.desc.trim() || undefined
    r.updatedAt = now()
    registerToAuth(r)
  }

  function remove(id: string) {
    const r = get(id)
    if (!r || r.builtin) return
    roles.value = roles.value.filter((x) => x.id !== id)
  }

  // 注册到 auth store：自定义角色的功能权限 + 由字段权限推导出的功能点
  function registerToAuth(r: RbacRole) {
    const perms = new Set(r.permissions)
    // 字段权限 -> 对应功能点：任何字段可见(READ/MASK/EDIT) 即隐含该模块 :view
    const moduleView: Record<string, string> = {
      customer: 'customer:view', order: 'cashier:view', emr: 'emr:view', staff: 'org:view',
    }
    for (const f of ALL_FIELDS) {
      const acc = r.fields[f.key]
      if (acc === 'READ' || acc === 'MASK' || acc === 'EDIT') {
        const mod = f.key.split('.')[0]
        if (moduleView[mod]) perms.add(moduleView[mod])
      }
    }
    auth.registerCustomRole(r.key, { label: r.label, permissions: [...perms], scope: r.scope })
  }

  return {
    roles, builtinRoles, customRoles, stats, FIELD_GROUPS, ALL_FIELDS,
    FIELD_ACCESS_LABEL, FIELD_ACCESS_ORDER, SCOPE_LABEL,
    seed, get, fieldAccess, setField, setScope, togglePermission,
    create, update, remove,
  }
})

// ---- helpers（模块级，避免 store 内循环引用 ROLE_PERMISSIONS）----
function authScopeFor(r: Role): DataScope {
  const m: Record<Role, DataScope> = {
    SUPER_ADMIN: 'GROUP', REGION_MGR: 'REGION', STORE_MGR: 'STORE', CONSULTANT: 'SELF',
    DOCTOR: 'STORE', FRONT_DESK: 'STORE', OPERATOR: 'STORE', FINANCE: 'REGION',
  }
  return m[r]
}

// 从 auth store 读取内置角色权限集（此处通过 import 动态读取更稳妥，这里直接内联一份只读引用）
function builtinPermissions(r: Role): string[] {
  // 复用 auth 的 ROLE_PERMISSIONS 不直接导出，这里通过 useAuthStore 已在 seed 时可用；
  // 为避免耦合，返回一个代表性子集用于矩阵展示（实际鉴权以 auth 为准）。
  const map: Record<Role, string[]> = {
    SUPER_ADMIN: ['*'],
    REGION_MGR: ['tenant:edit', 'org:edit', 'rbac:edit', 'brand:edit', 'inventory:edit', 'compliance:edit', 'transfer:approve', 'complaint:approve', 'report:view'],
    STORE_MGR: ['tenant:edit', 'org:edit', 'rbac:edit', 'complaint:approve', 'refund:approve', 'transfer:create', 'cashier:sign', 'settings:edit'],
    CONSULTANT: ['customer:edit', 'consult:edit', 'appointment:create', 'emr:view', 'customer:phone:decrypt'],
    DOCTOR: ['emr:edit', 'prescription:edit', 'writeoff:sign', 'consult:edit', 'customer:phone:decrypt'],
    FRONT_DESK: ['reception:edit', 'cashier:sign', 'appointment:create', 'queue:edit', 'complaint:create'],
    OPERATOR: ['marketing:edit', 'followup:edit', 'recall:edit', 'complaint:create'],
    FINANCE: ['refund:approve', 'refund:sign', 'cashier:approve', 'finance:margin:view', 'transfer:approve', 'audit:view'],
  }
  return map[r]
}
