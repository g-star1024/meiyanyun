// ============================================================
// T1 权限中台 · RBAC 真源 store
// 角色 CRUD + 继承树 + 权限包（功能+数据范围）+ 成员关联
// 权限矩阵（角色×模块）+ 冲突检测
// 变更走 T3-01 审批 + 写 T1-04 审计
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

// ---- 类型 ----
export type DataScope = 'SELF' | 'STORE' | 'BRAND' | 'REGION' | 'GROUP'

export interface PermissionPackage {
  /** 功能权限码列表 */
  actions: string[]
  /** 数据范围 */
  scope: DataScope
}

export interface RoleMember {
  staffId: string
  name: string
  jobTitle: string
  storeName: string
  addedAt: string
}

export interface T1Role {
  id: string
  code: string
  name: string
  description: string
  /** 父角色 id（继承） */
  parentId: string | null
  /** 权限包 */
  permissions: PermissionPackage
  /** 角色状态 */
  status: 'ACTIVE' | 'INACTIVE'
  /** 是否系统内置（不可删除） */
  builtin: boolean
  sort: number
  createdAt: string
  updatedAt: string
}

export interface PermissionConflict {
  roleA: string
  roleB: string
  module: string
  reason: string
  severity: 'HIGH' | 'MEDIUM'
}

export interface AuditEntry {
  id: string
  actor: string
  action: 'CREATE' | 'UPDATE' | 'DELETE' | 'ASSIGN' | 'STATUS'
  target: string
  detail: string
  at: string
}

const SCOPE_LABEL: Record<DataScope, string> = {
  SELF: '仅本人', STORE: '门店', BRAND: '品牌', REGION: '区域', GROUP: '集团',
}

const SCOPE_RANK: Record<DataScope, number> = {
  SELF: 0, STORE: 1, BRAND: 2, REGION: 3, GROUP: 4,
}

// 权限矩阵模块定义（模块 → 功能权限码）
export const MATRIX_MODULES = [
  { key: 'appointment', label: '预约接待', perms: ['appointment:view', 'appointment:create', 'appointment:edit'] },
  { key: 'customer', label: '客户管理', perms: ['customer:view', 'customer:create', 'customer:edit', 'customer:merge'] },
  { key: 'finance', label: '数据财务', perms: ['finance:view', 'finance:reconcile', 'finance:settlement:generate', 'finance:export'] },
  { key: 'inventory', label: '库存耗材', perms: ['inventory:view', 'inventory:edit', 'inventory:approve'] },
  { key: 'marketing', label: '营销中心', perms: ['marketing:view', 'marketing:edit', 'coupon:create', 'push:send'] },
  { key: 'role', label: '权限中台', perms: ['role:view', 'role:create', 'role:edit', 'role:delete', 'role:assign'] },
  { key: 'data', label: '数据中台', perms: ['collect:view', 'tagFactory:publish', 'dataService:apply'] },
  { key: 'integration', label: '集成中心', perms: ['integration:view', 'integration:sync', 'integration:reconcile'] },
  { key: 'model', label: 'AI 模型', perms: ['model:view', 'model:register', 'model:release', 'model:rollback'] },
  { key: 'report', label: '报表中心', perms: ['report:view', 'report:export'] },
] as const

export const useT1RbacStore = defineStore('t1Rbac', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const roles = ref<T1Role[]>([])
  const members = ref<Record<string, RoleMember[]>>({})
  const auditLog = ref<AuditEntry[]>([])
  const loaded = ref(false)

  // ---- 查询 ----
  const activeRoles = computed(() => roles.value.filter((r) => r.status === 'ACTIVE').sort((a, b) => a.sort - b.sort))

  function get(id: string) {
    return roles.value.find((r) => r.id === id)
  }

  function children(parentId: string | null) {
    return roles.value.filter((r) => r.parentId === parentId).sort((a, b) => a.sort - b.sort)
  }

  /** 继承链：获取某角色的所有祖先权限并集 */
  function effectivePermissions(roleId: string): PermissionPackage {
    const role = get(roleId)
    if (!role) return { actions: [], scope: 'SELF' }
    const actions = new Set(role.permissions.actions)
    let maxScope = role.permissions.scope
    let cur = role
    while (cur.parentId) {
      const parent = get(cur.parentId)
      if (!parent) break
      parent.permissions.actions.forEach((a) => actions.add(a))
      if (SCOPE_RANK[parent.permissions.scope] > SCOPE_RANK[maxScope]) {
        maxScope = parent.permissions.scope
      }
      cur = parent
    }
    return { actions: [...actions], scope: maxScope }
  }

  function getMembers(roleId: string) {
    return members.value[roleId] || []
  }

  function memberCount(roleId: string) {
    return getMembers(roleId).length
  }

  // ---- 冲突检测 ----
  function detectConflicts(): PermissionConflict[] {
    const conflicts: PermissionConflict[] = []
    const active = activeRoles.value
    for (let i = 0; i < active.length; i++) {
      for (let j = i + 1; j < active.length; j++) {
        const a = effectivePermissions(active[i].id)
        const b = effectivePermissions(active[j].id)
        // 检测：一个角色有审批权，另一个有同一业务的提交权（职责分离冲突）
        const approveA = a.actions.filter((p) => p.endsWith(':approve'))
        const createB = b.actions.filter((p) => p.endsWith(':create') || p.endsWith(':submit'))
        for (const ap of approveA) {
          const mod = ap.split(':')[0]
          if (createB.some((cp) => cp.startsWith(mod))) {
            conflicts.push({
              roleA: active[i].name,
              roleB: active[j].name,
              module: mod,
              reason: `「${active[i].name}」拥有 ${ap} 审批权，「${active[j].name}」拥有同模块提交权，存在自审风险`,
              severity: 'HIGH',
            })
          }
        }
        // 检测：数据范围差异过大（GROUP vs SELF）但功能权限高度重叠
        if (SCOPE_RANK[a.scope] - SCOPE_RANK[b.scope] >= 3 && a.actions.length > 5) {
          const overlap = a.actions.filter((x) => b.actions.includes(x)).length
          if (overlap > a.actions.length * 0.6) {
            conflicts.push({
              roleA: active[i].name,
              roleB: active[j].name,
              module: '数据范围',
              reason: `两角色功能权限重叠度 ${Math.round(overlap / a.actions.length * 100)}%，但数据范围差异大（${SCOPE_LABEL[a.scope]} vs ${SCOPE_LABEL[b.scope]}）`,
              severity: 'MEDIUM',
            })
          }
        }
      }
    }
    return conflicts
  }

  // ---- 权限矩阵 ----
  function matrix() {
    return activeRoles.value.map((role) => {
      const eff = effectivePermissions(role.id)
      const row: Record<string, boolean> = {}
      for (const mod of MATRIX_MODULES) {
        row[mod.key] = mod.perms.some((p) => eff.actions.includes(p))
      }
      return { role, eff, row }
    })
  }

  /** 两角色权限差异对比 */
  function diffRoles(roleA: string, roleB: string) {
    const a = effectivePermissions(roleA)
    const b = effectivePermissions(roleB)
    const onlyA = a.actions.filter((x) => !b.actions.includes(x))
    const onlyB = b.actions.filter((x) => !a.actions.includes(x))
    const common = a.actions.filter((x) => b.actions.includes(x))
    return { onlyA, onlyB, common, scopeA: a.scope, scopeB: b.scope }
  }

  // ---- 命令 ----
  function canEdit() { return auth.can('role:edit') }

  function createRole(input: {
    code: string; name: string; description: string; parentId: string | null
    permissions: PermissionPackage; sort?: number
  }): T1Role {
    if (!auth.can('role:create')) throw new Error('无角色创建权限')
    const now = new Date().toISOString()
    const role: T1Role = {
      id: nextId('role'),
      code: input.code,
      name: input.name,
      description: input.description,
      parentId: input.parentId,
      permissions: input.permissions,
      status: 'ACTIVE',
      builtin: false,
      sort: input.sort ?? roles.value.length,
      createdAt: now,
      updatedAt: now,
    }
    roles.value.push(role)
    members.value[role.id] = []
    logAudit('CREATE', role.name, `创建角色「${role.name}」（${input.code}）`)
    activity.log(auth.user.name, `创建角色「${role.name}」`, role.id)
    return role
  }

  function updateRole(id: string, patch: Partial<Pick<T1Role, 'name' | 'description' | 'parentId' | 'permissions' | 'sort'>>) {
    if (!auth.can('role:edit')) throw new Error('无角色编辑权限')
    const role = get(id)
    if (!role) return
    Object.assign(role, patch, { updatedAt: new Date().toISOString() })
    logAudit('UPDATE', role.name, `更新角色「${role.name}」信息`)
    activity.log(auth.user.name, `更新角色「${role.name}」`, id)
  }

  function deleteRole(id: string) {
    if (!auth.can('role:delete')) throw new Error('无角色删除权限')
    const role = get(id)
    if (!role || role.builtin) return
    // 检查是否有子角色
    if (children(id).length > 0) throw new Error('存在子角色，无法删除')
    if (getMembers(id).length > 0) throw new Error('角色下仍有成员，无法删除')
    roles.value = roles.value.filter((r) => r.id !== id)
    delete members.value[id]
    logAudit('DELETE', role.name, `删除角色「${role.name}」`)
    activity.log(auth.user.name, `删除角色「${role.name}」`, id)
  }

  function toggleStatus(id: string) {
    if (!auth.can('role:edit')) throw new Error('无角色编辑权限')
    const role = get(id)
    if (!role || role.builtin) return
    role.status = role.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
    role.updatedAt = new Date().toISOString()
    logAudit('STATUS', role.name, `${role.status === 'ACTIVE' ? '启用' : '停用'}角色「${role.name}」`)
  }

  function assignMember(roleId: string, member: Omit<RoleMember, 'addedAt'>) {
    if (!auth.can('role:assign')) throw new Error('无角色分配权限')
    if (!members.value[roleId]) members.value[roleId] = []
    if (members.value[roleId].some((m) => m.staffId === member.staffId)) return
    members.value[roleId].push({ ...member, addedAt: new Date().toISOString() })
    const role = get(roleId)
    logAudit('ASSIGN', role?.name || roleId, `分配成员「${member.name}」到角色「${role?.name}」`)
  }

  function removeMember(roleId: string, staffId: string) {
    if (!auth.can('role:assign')) throw new Error('无角色分配权限')
    if (!members.value[roleId]) return
    members.value[roleId] = members.value[roleId].filter((m) => m.staffId !== staffId)
  }

  function logAudit(action: AuditEntry['action'], target: string, detail: string) {
    auditLog.value.unshift({
      id: nextId('audit'),
      actor: auth.user.name,
      action, target, detail,
      at: new Date().toISOString(),
    })
  }

  // ---- 种子 ----
  function seed() {
    if (loaded.value) return
    loaded.value = true
    const now = Date.now()
    const daysAgo = (d: number) => new Date(now - d * 86400000).toISOString()

    const seedRoles: Array<Partial<T1Role> & Pick<T1Role, 'code' | 'name' | 'description' | 'permissions'>> = [
      {
        code: 'SUPER_ADMIN', name: '集团管理员', description: '拥有全平台所有权限，系统最高角色',
        parentId: null, permissions: { actions: ['*'], scope: 'GROUP' }, builtin: true, sort: 0,
      },
      {
        code: 'REGION_MGR', name: '区域经理', description: '负责区域内多门店运营管理，含审批权',
        parentId: null, permissions: {
          actions: ['appointment:view', 'appointment:edit', 'customer:view', 'customer:edit', 'finance:view', 'finance:reconcile', 'finance:export', 'inventory:view', 'inventory:edit', 'inventory:approve', 'marketing:view', 'marketing:edit', 'report:view', 'report:export', 'role:view', 'role:assign', 'collect:view', 'integration:view', 'model:view'],
          scope: 'REGION',
        },
        builtin: true, sort: 1,
      },
      {
        code: 'STORE_MGR', name: '门店店长', description: '单门店全面管理，含日常审批',
        parentId: null, permissions: {
          actions: ['appointment:view', 'appointment:create', 'appointment:edit', 'customer:view', 'customer:create', 'customer:edit', 'finance:view', 'finance:reconcile', 'inventory:view', 'inventory:edit', 'marketing:view', 'report:view', 'report:export', 'role:view', 'role:assign', 'ticket:create', 'ticket:close'],
          scope: 'STORE',
        },
        builtin: true, sort: 2,
      },
      {
        code: 'CONSULTANT', name: '咨询顾问', description: '客户咨询、开单、跟进',
        parentId: null, permissions: {
          actions: ['appointment:view', 'appointment:create', 'customer:view', 'customer:create', 'customer:edit', 'consult:view', 'consult:create', 'prescription:view', 'prescription:create'],
          scope: 'SELF',
        },
        builtin: true, sort: 3,
      },
      {
        code: 'DOCTOR', name: '医生', description: '电子病历、处方、核销',
        parentId: null, permissions: {
          actions: ['emr:view', 'emr:create', 'emr:edit', 'prescription:view', 'prescription:create', 'writeoff:view', 'writeoff:create'],
          scope: 'STORE',
        },
        builtin: true, sort: 4,
      },
      {
        code: 'FRONT_DESK', name: '前台/收银', description: '接待、排队、收银',
        parentId: null, permissions: {
          actions: ['reception:view', 'reception:create', 'queue:view', 'queue:create', 'cashier:view', 'cashier:create'],
          scope: 'STORE',
        },
        builtin: true, sort: 5,
      },
      {
        code: 'FINANCE', name: '财务', description: '财务审核、对账、结算',
        parentId: null, permissions: {
          actions: ['finance:view', 'finance:reconcile', 'finance:reconcile:approve', 'finance:settlement:generate', 'finance:settlement:approve', 'finance:invoice:apply', 'finance:export', 'refund:approve', 'refund:sign', 'audit:view'],
          scope: 'REGION',
        },
        builtin: true, sort: 6,
      },
      {
        code: 'DATA_ANALYST', name: '数据分析师', description: '数据中台操作、标签加工、模型管理（Wave 5 新增）',
        parentId: null, permissions: {
          actions: ['collect:view', 'collect:create', 'collect:sync', 'govern:view', 'tagFactory:view', 'tagFactory:create', 'tagFactory:edit', 'tagFactory:publish', 'dataService:view', 'dataService:publish', 'model:view', 'model:register', 'feature:view', 'feature:register', 'monitor:view', 'report:view', 'report:export'],
          scope: 'GROUP',
        },
        builtin: false, sort: 7,
      },
      {
        code: 'INTEGRATION_ENG', name: '集成工程师', description: '连接器配置、Outbox 监控、T+1 对账（Wave 5 新增）',
        parentId: null, permissions: {
          actions: ['integration:view', 'integration:create', 'integration:edit', 'integration:sync', 'integration:reconcile', 'monitor:view'],
          scope: 'GROUP',
        },
        builtin: false, sort: 8,
      },
    ]

    seedRoles.forEach((r) => {
      const id = nextId('role')
      roles.value.push({
        id,
        code: r.code!,
        name: r.name!,
        description: r.description!,
        parentId: r.parentId ?? null,
        permissions: r.permissions!,
        status: 'ACTIVE',
        builtin: r.builtin ?? false,
        sort: r.sort ?? 0,
        createdAt: daysAgo(120 - (r.sort ?? 0) * 10),
        updatedAt: daysAgo(3),
      })
    })

    // 种子成员
    const memberSeed: Record<string, RoleMember[]> = {
      [roles.value[0].id]: [
        { staffId: 'staff-zhou', name: '周岚', jobTitle: '集团运营总裁', storeName: '集团总部', addedAt: daysAgo(120) },
      ],
      [roles.value[1].id]: [
        { staffId: 'staff-chen', name: '陈野', jobTitle: '华东大区经理', storeName: '华东大区', addedAt: daysAgo(100) },
      ],
      [roles.value[2].id]: [
        { staffId: 'staff-su', name: '苏晴', jobTitle: '静安旗舰店店长', storeName: '静安旗舰店', addedAt: daysAgo(90) },
      ],
      [roles.value[3].id]: [
        { staffId: 'staff-lin', name: '林微', jobTitle: '资深咨询师', storeName: '静安旗舰店', addedAt: daysAgo(80) },
      ],
      [roles.value[4].id]: [
        { staffId: 'staff-gu', name: '顾屿', jobTitle: '主治医师', storeName: '静安旗舰店', addedAt: daysAgo(75) },
      ],
      [roles.value[5].id]: [
        { staffId: 'staff-xia', name: '夏沫', jobTitle: '前台/收银', storeName: '静安旗舰店', addedAt: daysAgo(60) },
      ],
      [roles.value[6].id]: [
        { staffId: 'staff-qian', name: '钱进', jobTitle: '财务审核', storeName: '华东大区', addedAt: daysAgo(50) },
      ],
      [roles.value[7].id]: [
        { staffId: 'staff-data', name: '数据分析师·张数', jobTitle: '高级数据工程师', storeName: '集团总部', addedAt: daysAgo(20) },
      ],
      [roles.value[8].id]: [
        { staffId: 'staff-int', name: '集成工程师·李联', jobTitle: '系统集成工程师', storeName: '集团总部', addedAt: daysAgo(15) },
      ],
    }
    Object.assign(members.value, memberSeed)

    // 种子审计日志
    auditLog.value = [
      { id: nextId('audit'), actor: '周岚', action: 'CREATE', target: '数据分析师', detail: '创建角色「数据分析师」', at: daysAgo(20) },
      { id: nextId('audit'), actor: '周岚', action: 'CREATE', target: '集成工程师', detail: '创建角色「集成工程师」', at: daysAgo(15) },
      { id: nextId('audit'), actor: '陈野', action: 'ASSIGN', target: '数据分析师', detail: '分配成员「张数」到角色「数据分析师」', at: daysAgo(18) },
      { id: nextId('audit'), actor: '陈野', action: 'UPDATE', target: '门店店长', detail: '更新角色「门店店长」权限：新增 ticket:create', at: daysAgo(5) },
    ]
  }

  return {
    roles, activeRoles, members, auditLog, MATRIX_MODULES, SCOPE_LABEL,
    get, children, effectivePermissions, getMembers, memberCount,
    detectConflicts, matrix, diffRoles, canEdit,
    createRole, updateRole, deleteRole, toggleStatus, assignMember, removeMember, seed,
  }
})
