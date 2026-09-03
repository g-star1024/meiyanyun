// ============================================================
// T1 权限中台 · RBAC 真源 store（对接 org-service /api/org/admin/*）
// 角色 CRUD + 权限包（功能码 + 数据范围）+ 成员关联
// 权限矩阵（角色×资源模块）+ 冲突检测
// 后端事实：角色无继承（parentId 恒 null）；超管 "*" 不落库（前端补）；
// 内置 8 角色为矩阵权威源（禁删/禁改权限/禁停用）；审计由后端落 audit-service。
// ============================================================
import { defineStore } from 'pinia'
import { computed, reactive, ref } from 'vue'
import { useAuthStore } from './auth'
import {
  listRoles,
  listAdminPermissions,
  getRolePermissions,
  getRoleMembers,
  listStores,
  createRole as apiCreateRole,
  updateRole as apiUpdateRole,
  deleteRole as apiDeleteRole,
  toggleRoleStatus,
  updateRolePermissions,
  removeStaffRole,
  type PermissionDef,
  type RoleDef,
  type Staff,
  type Store as OrgStore,
} from '@/api/org'

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
  /** 父角色 id（后端无继承，恒 null） */
  parentId: string | null
  /** 权限包 */
  permissions: PermissionPackage
  /** 角色状态 */
  status: 'ACTIVE' | 'INACTIVE'
  /** 是否系统内置（不可删除/改权限/停用） */
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

export interface MatrixModule {
  key: string
  label: string
  perms: string[]
}

const SCOPE_LABEL: Record<DataScope, string> = {
  SELF: '仅本人', STORE: '门店', BRAND: '品牌', REGION: '区域', GROUP: '集团',
}

const SCOPE_RANK: Record<DataScope, number> = {
  SELF: 0, STORE: 1, BRAND: 2, REGION: 3, GROUP: 4,
}

/** 内置矩阵角色码（与后端 RbacAdminController.BUILTIN_ROLES 同源） */
const BUILTIN_ROLE_CODES = new Set([
  'SUPER_ADMIN', 'REGION_MGR', 'STORE_MGR', 'CONSULTANT',
  'DOCTOR', 'FRONT_DESK', 'OPERATOR', 'FINANCE',
])

/**
 * 资源码 → 中文模块名（权限字典按 resourceCode 分组展示）。
 * 资源全集以后端 permission_def 为准；未覆盖的资源码回退显示原文，不外露英文缺口。
 */
const RESOURCE_LABEL: Record<string, string> = {
  appointment: '预约管理',
  queue: '排队叫号',
  reception: '前台接待',
  customer: '客户管理',
  complaint: '客诉管理',
  followup: '回访管理',
  consult: '咨询开单',
  prescription: '处方管理',
  cashier: '收银结算',
  writeoff: '核销管理',
  refund: '退款管理',
  cardcancel: '退卡管理',
  course: '疗程管理',
  transfer: '转店管理',
  contract: '合同管理',
  emr: '电子病历',
  recall: '召回管理',
  handover: '交接班',
  report: '报表中心',
  tenant: '租户管理',
  org: '组织管理',
  rbac: '权限管理',
  inventory: '库存耗材',
  brand: '品牌管理',
  marketing: '营销中心',
  dispatch: '调度管理',
  compliance: '合规管理',
  audit: '审计日志',
  health: '健康档案',
  sop: 'SOP 规程',
  target: '目标管理',
  screen: '大屏展示',
  settings: '系统设置',
  schedule: '排班管理',
  approval: '审批中心',
  notification: '消息通知',
  workorder: '工单管理',
  daily: '日报管理',
  requisition: '领用申请',
  wastage: '损耗管理',
  room: '房间管理',
  equipment: '设备管理',
  performance: '绩效管理',
  weekly: '周报管理',
  pricelist: '价目管理',
  catalog: '品项目录',
  writeoffdesk: '核销台',
  checkin: '签到管理',
  inspection: '巡检管理',
  acquisition: '获客管理',
  reactivate: '沉睡唤醒',
  exception: '异常管理',
  help: '帮助中心',
  level: '会员等级',
  points: '积分管理',
  tag: '客户标签',
  journey: '客户旅程',
  followuptask: '跟进任务',
  care: '客户关怀',
  churn: '流失管理',
  referral: '转介绍',
  nps: '满意度调研',
  private: '私域运营',
  segment: '客群分群',
  io: '数据导入导出',
  risk: '风控管理',
  insight: '经营洞察',
  finance: '财务中心',
  coupon: '优惠券',
  push: '消息推送',
  poster: '海报素材',
  live: '直播管理',
  roi: 'ROI 分析',
  channel: '渠道管理',
  landing: '落地页',
  calendar: '营销日历',
  referralCampaign: '裂变活动',
  couponWriteoff: '券核销',
  asset: '素材资产',
  marketingDash: '营销看板',
  role: '角色管理',
  permission: '权限字典',
  collect: '数据采集',
  govern: '数据治理',
  tagFactory: '标签工厂',
  dataService: '数据服务',
  ticket: '运维工单',
  integration: '集成中心',
  model: 'AI 模型',
  compute: '算力管理',
  feature: '特征工程',
  monitor: '监控告警',
  ai: 'AI 中心',
  aiProfile: 'AI 客户画像',
  aiRepurchase: 'AI 复购预测',
  aiSensitive: 'AI 敏感词',
  aiDaily: 'AI 日报',
  aiScript: 'AI 话术',
  aiChatbot: 'AI 客服',
  aiScheduling: 'AI 排班',
  aiChurn: 'AI 流失预警',
  aiContent: 'AI 内容',
  aiKnowledge: 'AI 知识库',
  aiGovern: 'AI 治理',
  aiPrivacy: 'AI 隐私',
  aiGateway: 'AI 网关',
  aiAdmin: 'AI 管理',
}

/** 后端中文数据域 → 前端枚举 */
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

// 权限矩阵模块定义（seed 后按后端权限字典 resourceCode 分组原地重建）
// reactive 数组：视图侧 computed（kpiModules/kpiPerms）与 v-for 需追踪 splice 原地变更
export const MATRIX_MODULES = reactive<MatrixModule[]>([])

export const useT1RbacStore = defineStore('t1Rbac', () => {
  const auth = useAuthStore()

  const roles = ref<T1Role[]>([])
  const members = ref<Record<string, RoleMember[]>>({})
  const auditLog = ref<AuditEntry[]>([])
  const loaded = ref(false)
  const loading = ref(false)

  // ---- 查询 ----
  const activeRoles = computed(() =>
    roles.value.filter((r) => r.status === 'ACTIVE').sort((a, b) => a.sort - b.sort),
  )

  function get(id: string) {
    return roles.value.find((r) => r.id === id)
  }

  function children(parentId: string | null) {
    return roles.value.filter((r) => r.parentId === parentId).sort((a, b) => a.sort - b.sort)
  }

  /** 有效权限：后端无角色继承，直接返回角色自身权限包 */
  function effectivePermissions(roleId: string): PermissionPackage {
    const role = get(roleId)
    if (!role) return { actions: [], scope: 'SELF' }
    return { actions: [...role.permissions.actions], scope: role.permissions.scope }
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
        const rankGap = Math.abs(SCOPE_RANK[a.scope] - SCOPE_RANK[b.scope])
        if (rankGap >= 3 && a.actions.length > 5 && b.actions.length > 5) {
          const overlap = a.actions.filter((x) => b.actions.includes(x)).length
          const base = Math.min(a.actions.length, b.actions.length)
          if (overlap > base * 0.6) {
            conflicts.push({
              roleA: active[i].name,
              roleB: active[j].name,
              module: '数据范围',
              reason: `两角色功能权限重叠度 ${Math.round((overlap / base) * 100)}%，但数据范围差异大（${SCOPE_LABEL[a.scope]} vs ${SCOPE_LABEL[b.scope]}）`,
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
        row[mod.key] = mod.perms.some((p) => eff.actions.includes('*') || eff.actions.includes(p))
      }
      return { role, eff, row }
    })
  }

  /** 两角色权限差异对比 */
  function diffRoles(roleA: string, roleB: string) {
    const a = effectivePermissions(roleA)
    const b = effectivePermissions(roleB)
    const onlyA = a.actions.filter((x) => !b.actions.includes(x) && x !== '*')
    const onlyB = b.actions.filter((x) => !a.actions.includes(x) && x !== '*')
    const common = a.actions.filter((x) => b.actions.includes(x))
    return { onlyA, onlyB, common, scopeA: a.scope, scopeB: b.scope }
  }

  // ---- 适配映射 ----
  function adaptRole(
    r: RoleDef,
    perms: Record<string, string[]>,
    nowIso: string,
  ): T1Role {
    const builtin = BUILTIN_ROLE_CODES.has(r.roleCode)
    let actions = perms[r.roleCode] ? [...perms[r.roleCode]] : []
    // 超管 "*" 不落库：前端补通配，权限编辑器按通配置灰
    if (r.roleCode === 'SUPER_ADMIN') actions = ['*']
    return {
      id: r.roleCode,
      code: r.roleCode,
      name: r.roleName,
      description: r.description ?? '',
      parentId: null,
      permissions: { actions, scope: scopeFromCn(r.dataScope) },
      status: r.status === '停用' ? 'INACTIVE' : 'ACTIVE',
      builtin,
      sort: Number.parseInt(r.roleSequence ?? '90', 10) || 90,
      // RoleDef 无时间戳：内置角色给固定早兜底，自定义角色给当前时间
      createdAt: builtin ? '2024-01-01T00:00:00+08:00' : nowIso,
      updatedAt: nowIso,
    }
  }

  function adaptMember(s: Staff, storeMap: Map<string, string>): RoleMember {
    return {
      staffId: s.staffId,
      name: s.staffName,
      jobTitle: s.role?.roleName ?? '',
      storeName: (s.storeCode && storeMap.get(s.storeCode)) || s.storeCode || '—',
      addedAt: s.createdAt ?? new Date().toISOString(),
    }
  }

  /** 按权限字典重建矩阵模块（原地 mutate，保持模块级 const 的 live binding） */
  function rebuildModules(defs: PermissionDef[]) {
    const groups = new Map<string, string[]>()
    for (const d of defs) {
      const arr = groups.get(d.resourceCode)
      if (arr) arr.push(d.permissionCode)
      else groups.set(d.resourceCode, [d.permissionCode])
    }
    const modules: MatrixModule[] = []
    for (const [key, perms] of groups) {
      modules.push({
        key,
        label: RESOURCE_LABEL[key] || key,
        perms: perms.sort(),
      })
    }
    modules.sort((a, b) => a.label.localeCompare(b.label, 'zh-Hans-CN'))
    MATRIX_MODULES.splice(0, MATRIX_MODULES.length, ...modules)
  }

  // ---- 命令 ----
  function canEdit() { return auth.can('role:edit') }

  /**
   * 新建角色：视图同步取返回值 id（=roleCode，提交时已知），故乐观构造入列并同步返回；
   * 后端 POST /admin/roles + PUT permissions 异步执行，失败弹中文错误并回滚。
   */
  function createRole(input: {
    code: string; name: string; description: string; parentId: string | null
    permissions: PermissionPackage; sort?: number
  }): T1Role {
    if (!auth.can('role:create')) throw new Error('无角色创建权限')
    const nowIso = new Date().toISOString()
    const role: T1Role = {
      id: input.code,
      code: input.code,
      name: input.name,
      description: input.description,
      parentId: null,
      permissions: { actions: [...input.permissions.actions], scope: input.permissions.scope },
      status: 'ACTIVE',
      builtin: false,
      sort: input.sort ?? roles.value.length,
      createdAt: nowIso,
      updatedAt: nowIso,
    }
    roles.value.push(role)
    members.value[role.id] = []

    const payload = {
      roleCode: role.code,
      roleName: role.name,
      dataScope: scopeToCn(role.permissions.scope),
      description: role.description,
    }
    void (async () => {
      try {
        await apiCreateRole(payload)
        const codes = role.permissions.actions.filter((a) => a !== '*')
        if (codes.length > 0) {
          await updateRolePermissions(role.code, codes)
        }
      } catch (e) {
        // 回滚乐观更新
        roles.value = roles.value.filter((r) => r.id !== role.id)
        delete members.value[role.id]
        window.alert(errMsg(e, '角色创建失败，请稍后重试'))
      }
    })()
    return role
  }

  /**
   * 更新角色：名称/描述/数据域走 PUT /admin/roles；功能权限变更走 PUT permissions（全量覆写）。
   * 内置角色仅描述可改（后端强制），权限勾选不落库。fire-and-forget，失败弹中文错误。
   */
  function updateRole(id: string, patch: Partial<Pick<T1Role, 'name' | 'description' | 'parentId' | 'permissions' | 'sort'>>) {
    if (!auth.can('role:edit')) throw new Error('无角色编辑权限')
    const role = get(id)
    if (!role) return
    const prev = {
      name: role.name,
      description: role.description,
      scope: role.permissions.scope,
      actions: [...role.permissions.actions],
    }
    // 乐观更新
    if (patch.name !== undefined) role.name = patch.name
    if (patch.description !== undefined) role.description = patch.description
    if (patch.permissions) {
      role.permissions = {
        actions: [...patch.permissions.actions],
        scope: patch.permissions.scope,
      }
    }
    role.updatedAt = new Date().toISOString()

    void (async () => {
      try {
        await apiUpdateRole(role.code, {
          roleName: role.builtin ? undefined : role.name,
          description: role.description,
          dataScope: role.builtin ? undefined : scopeToCn(role.permissions.scope),
        })
        if (!role.builtin && patch.permissions) {
          const codes = role.permissions.actions.filter((a) => a !== '*')
          const same = codes.length === prev.actions.filter((a) => a !== '*').length
            && codes.every((c) => prev.actions.includes(c))
          if (!same) {
            await updateRolePermissions(role.code, codes)
          }
        }
      } catch (e) {
        // 回滚
        role.name = prev.name
        role.description = prev.description
        role.permissions = { actions: prev.actions, scope: prev.scope }
        window.alert(errMsg(e, '角色更新失败，请稍后重试'))
      }
    })()
  }

  function deleteRole(id: string) {
    if (!auth.can('role:delete')) throw new Error('无角色删除权限')
    const role = get(id)
    if (!role || role.builtin) return
    const prevRoles = roles.value
    const prevMembers = members.value[id]
    roles.value = roles.value.filter((r) => r.id !== id)
    delete members.value[id]

    void (async () => {
      try {
        await apiDeleteRole(role.code)
      } catch (e) {
        // 回滚
        roles.value = prevRoles
        if (prevMembers) members.value[id] = prevMembers
        window.alert(errMsg(e, '角色删除失败，请稍后重试'))
      }
    })()
  }

  function toggleStatus(id: string) {
    if (!auth.can('role:edit')) throw new Error('无角色编辑权限')
    const role = get(id)
    if (!role || role.builtin) return
    const prevStatus = role.status
    role.status = role.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
    role.updatedAt = new Date().toISOString()

    void (async () => {
      try {
        const res = await toggleRoleStatus(role.code)
        role.status = res.data.status === '停用' ? 'INACTIVE' : 'ACTIVE'
      } catch (e) {
        role.status = prevStatus
        window.alert(errMsg(e, '角色状态切换失败，请稍后重试'))
      }
    })()
  }

  /** 成员移除：摘除兼岗角色（DELETE /admin/staff/{staffId}/roles/{roleCode}） */
  function removeMember(roleId: string, staffId: string) {
    if (!auth.can('role:assign')) throw new Error('无角色分配权限')
    const prev = members.value[roleId]
    if (!prev) return
    const removed = prev.find((m) => m.staffId === staffId)
    if (!removed) return
    members.value[roleId] = prev.filter((m) => m.staffId !== staffId)

    void (async () => {
      try {
        await removeStaffRole(staffId, roleId)
      } catch (e) {
        members.value[roleId] = prev
        window.alert(errMsg(e, '成员移除失败，请稍后重试'))
      }
    })()
  }

  /** 兼岗分配入口保留（员工管理页经 api 直接调用；此处同步成员表） */
  function assignMember(roleId: string, member: Omit<RoleMember, 'addedAt'>) {
    if (!members.value[roleId]) members.value[roleId] = []
    if (members.value[roleId].some((m) => m.staffId === member.staffId)) return
    members.value[roleId].push({ ...member, addedAt: new Date().toISOString() })
  }

  // ---- 载入 ----
  async function seed() {
    if (loaded.value || loading.value) return
    loading.value = true
    try {
      const [rolesRes, permsRes, rolePermsRes, membersRes, storesRes] = await Promise.all([
        listRoles(),
        listAdminPermissions(),
        getRolePermissions(),
        getRoleMembers(),
        listStores(),
      ])
      const nowIso = new Date().toISOString()
      const rolePerms = rolePermsRes.data
      roles.value = rolesRes.data
        .map((r) => adaptRole(r, rolePerms, nowIso))
        .sort((a, b) => a.sort - b.sort)

      const storeMap = new Map<string, string>()
      for (const s of (storesRes.data as OrgStore[] | null) ?? []) {
        storeMap.set(s.storeCode, s.storeName)
      }
      const mem: Record<string, RoleMember[]> = {}
      for (const [roleCode, staffList] of Object.entries(membersRes.data || {})) {
        mem[roleCode] = staffList.map((s) => adaptMember(s, storeMap))
      }
      members.value = mem

      rebuildModules(permsRes.data)
      loaded.value = true
    } catch (e) {
      // 403/网络失败：保留空表，不弹扰（页面门控本身已按权限隐藏入口）
      console.error('[t1Rbac] 权限中台数据载入失败', e)
    } finally {
      loading.value = false
    }
  }

  return {
    roles, activeRoles, members, auditLog, MATRIX_MODULES, SCOPE_LABEL,
    get, children, effectivePermissions, getMembers, memberCount,
    detectConflicts, matrix, diffRoles, canEdit,
    createRole, updateRole, deleteRole, toggleStatus, assignMember, removeMember, seed,
  }
})
