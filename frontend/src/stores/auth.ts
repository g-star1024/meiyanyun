import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { DataScope, Role } from '@/types/domain'
import { login as apiLogin, devLogin as apiDevLogin, getPermissions as apiGetPermissions, type LoginResult, type PermissionMatrix } from '@/api/auth'
import { setToken, clearToken } from '@/api/client'

// 登录会话持久化键（localStorage）
const SESSION_KEY = 'meiyun_session'

// ============================================================
// 权限 / 鉴权 store（产品架构基线落地）
// 对齐 docs/permission-matrix.md
// - 角色 → 权限集合（RBAC），一人可多角色，权限取并集
// - 数据域（SELF / STORE / BRAND / REGION / GROUP），多角色取最大
// - 支持自定义角色及权限（registerCustomRole）
// - 开发期可用 ?as=<role>,<role> 或 loginAs([...]) 模拟角色叠加
// ============================================================

const ALL_VIEW = [
  'appointment:view', 'queue:view', 'reception:view', 'customer:view', 'customer:create',
  'complaint:view', 'followup:view', 'consult:view', 'prescription:view', 'cashier:view',
  'writeoff:view', 'refund:view', 'cardcancel:view', 'course:view', 'course:track',
  'transfer:view', 'contract:view', 'emr:view', 'recall:view', 'handover:view',
  'report:view', 'tenant:view', 'org:view', 'rbac:view', 'inventory:view', 'brand:view',
  'marketing:view', 'dispatch:view', 'compliance:view', 'audit:view', 'health:view',
  'sop:view', 'target:view', 'screen:view', 'settings:view',
  'schedule:view', 'approval:view',
  'workorder:view', 'daily:view',
  'requisition:view', 'wastage:view', 'room:view', 'equipment:view',
  'performance:view', 'weekly:view', 'pricelist:view', 'catalog:view',
  'writeoffdesk:view', 'checkin:view', 'inspection:view',
  'acquisition:view', 'reactivate:view', 'exception:view',
  'm2settings:view', 'help:view',
  'level:view', 'points:view', 'tag:view', 'journey:view', 'followuptask:view',
  'care:view', 'churn:view', 'referral:view', 'nps:view', 'private:view',
  'segment:view', 'io:view', 'risk:view', 'm3settings:view', 'insight:view',
  // M6 数据财务（红线区：只读镜像 + Outbox 对账，不碰资金池）
  'finance:view', 'finance:reconcile:view', 'finance:invoice:view', 'finance:settlement:view',
  'finance:cost:view', 'finance:commission:view', 'finance:writeoff:view', 'finance:prepay:view',
  'finance:cardbalance:view', 'finance:abnormal:view', 'finance:tax:view', 'finance:cashdaily:view',
  'finance:monthly:view', 'finance:budget:view', 'finance:settings:view',
  // M5 营销中心（活动→券→触达→核销→ROI 闭环；周频≤3 + 违禁词双重合规）
  'coupon:view', 'push:view', 'poster:view', 'live:view', 'roi:view',
  'channel:view', 'landing:view', 'calendar:view', 'referralCampaign:view',
  'couponWriteoff:view', 'asset:view', 'marketingDash:view', 'm5settings:view',
  // ===== Wave 5 · 四中台底座 =====
  // T1 权限中台（RBAC 真源：角色/权限矩阵/组织架构）
  'role:view', 'permission:view',
  // T2 数据中台（采集/治理/标签工厂/数据服务）
  'collect:view', 'govern:view', 'tagFactory:view', 'dataService:view',
  // T3 流程中台补全（工单中心=跨模块统一路由；集成中心=单向镜像+Outbox+T+1对账）
  'ticket:view', 'integration:view',
  // T4 AI 中台底座（模型仓库/算力/特征/监控，为 A1 铺路）
  'model:view', 'compute:view', 'feature:view', 'monitor:view',
  // ===== Wave 6 · A1 AI 中心（画像/预测/内容/风控/模型管理闭环；所有 AI 动作受控）=====
  'ai:view', 'aiProfile:view', 'aiRepurchase:view', 'aiSensitive:view',
  'aiDaily:view', 'aiScript:view', 'aiChatbot:view', 'aiScheduling:view',
  'aiChurn:view', 'aiContent:view', 'aiKnowledge:view',
  'aiGovern:view', 'aiPrivacy:view', 'aiGateway:view', 'aiAdmin:view',
] as const

// 各角色权限集（草稿，待评审后微调）。SUPER_ADMIN 走通配。
const ROLE_PERMISSIONS: Record<Role, string[]> = {
  SUPER_ADMIN: ['*'],
  REGION_MGR: [
    ...ALL_VIEW,
    'customer:edit', 'appointment:create', 'appointment:edit', 'reception:edit', 'consult:edit',
    'prescription:edit', 'emr:edit', 'followup:edit', 'complaint:edit', 'course:edit',
    'contract:edit', 'marketing:edit', 'handover:edit', 'handover:create', 'sop:edit', 'dispatch:edit',
    'brand:edit', 'inventory:edit', 'tenant:edit', 'org:edit', 'rbac:edit',
    'consult:review', // 区域/门店主管可二次审核方案单
    'compliance:edit', 'target:edit', 'health:edit', 'recall:edit', 'recall:create',
    'transfer:edit', 'transfer:create', 'writeoff:edit', 'queue:edit', 'complaint:approve', 'transfer:approve',
    'target:approve', 'sop:approve', 'inventory:approve', 'brand:approve',
    'customer:merge', 'settings:edit',
    'report:export', 'schedule:edit', 'schedule:approve',
    'workorder:edit', 'workorder:create', 'workorder:close', 'daily:edit', 'daily:submit',
    'requisition:create', 'requisition:edit', 'requisition:sign', 'wastage:create', 'wastage:edit', 'wastage:sign',
    'room:edit', 'equipment:edit', 'performance:edit', 'weekly:submit',
    'pricelist:edit', 'catalog:edit', 'checkin:create', 'inspection:create', 'inspection:edit',
    'acquisition:edit', 'reactivate:edit', 'exception:edit', 'm2settings:edit',
    'level:edit', 'points:edit', 'points:approve', 'tag:edit', 'followuptask:edit',
    'care:edit', 'care:send', 'churn:edit', 'referral:edit', 'referral:approve',
    'nps:edit', 'private:edit', 'segment:edit', 'io:import', 'io:export',
    'risk:edit', 'risk:approve', 'm3settings:edit', 'insight:export',
    // M6 财务操作（资金链隔离：仅对账标记/调平/结算审批/导出，绝不碰资金池）
    'finance:reconcile', 'finance:reconcile:approve',
    'finance:settlement:approve', 'finance:commission:approve',
    'finance:budget:edit', 'finance:settings:edit', 'finance:abnormal:dispose', 'finance:export',
    // M5 营销操作（活动已有 marketing:edit；券/推送/渠道/核销/落地页/海报/日历）
    'coupon:create', 'coupon:edit',
    'push:create', 'push:send',
    'channel:edit', 'couponWriteoff:verify', 'landing:edit',
    'poster:edit', 'live:edit', 'calendar:edit', 'referralCampaign:edit',
    'asset:upload', 'm5settings:edit', 'marketing:export',
    // Wave 5 · T1 权限中台操作（角色/权限/组织 变更均走 T3-01 审批）
    'role:create', 'role:edit', 'role:delete', 'role:assign',
    'permission:edit', 'org:edit',
    // Wave 5 · T2 数据中台操作
    'collect:create', 'collect:edit', 'collect:sync',
    'govern:rule:create', 'govern:rule:edit',
    'tagFactory:create', 'tagFactory:edit', 'tagFactory:publish', 'tagFactory:approve',
    'dataService:publish', 'dataService:apply',
    // Wave 5 · T3 工单中心 + 集成中心
    'ticket:create', 'ticket:dispatch', 'ticket:close',
    'integration:create', 'integration:edit', 'integration:sync', 'integration:reconcile',
    // Wave 5 · T4 AI 中台（模型发布需 T3-01 审批；不自动上线）
    'model:register', 'model:version', 'model:release', 'model:rollback',
    'compute:alloc', 'compute:edit',
    'feature:register', 'feature:edit', 'feature:publish',
    'monitor:rule:create', 'monitor:rule:edit',
    // Wave 6 · A1 AI 中心：操作端页面（话术/排班/内容/知识库/治理/隐私/网关）尚未落地，
    // 对应操作码字典已回收；仅 AI 管理台配置码有真实端点门控，予以保留。
    'aiAdmin:edit',
  ],
  STORE_MGR: [
    ...ALL_VIEW,
    'customer:edit', 'appointment:create', 'appointment:edit', 'reception:edit', 'consult:edit',
    'prescription:edit', 'emr:edit', 'followup:edit', 'complaint:edit', 'course:edit',
    'contract:edit', 'marketing:edit', 'handover:edit', 'handover:create', 'sop:edit', 'dispatch:edit',
    'brand:edit', 'inventory:edit', 'refund:approve', 'cardcancel:approve',
    'complaint:create', 'complaint:approve', 'transfer:approve', 'tenant:edit', 'org:edit', 'rbac:edit',
    'compliance:edit', 'target:edit', 'health:edit', 'recall:edit', 'recall:create',
    'transfer:edit', 'transfer:create', 'writeoff:edit', 'writeoff:create', 'queue:edit', 'cashier:sign',
    'customer:merge', 'settings:edit',
    'consult:review', // 门店主管可二次审核/改单/作废审核中方案
    'report:export', 'schedule:edit', 'schedule:approve',
    'workorder:edit', 'workorder:create', 'workorder:close', 'daily:edit', 'daily:submit',
    'requisition:create', 'requisition:edit', 'requisition:sign', 'wastage:create', 'wastage:edit', 'wastage:sign',
    'room:edit', 'equipment:edit', 'performance:edit', 'weekly:submit',
    'pricelist:edit', 'catalog:edit', 'checkin:create', 'inspection:create', 'inspection:edit',
    'acquisition:edit', 'reactivate:edit', 'exception:edit', 'm2settings:edit',
    'level:edit', 'points:edit', 'points:approve', 'tag:edit', 'followuptask:edit',
    'care:edit', 'care:send', 'churn:edit', 'referral:edit', 'referral:approve',
    'nps:edit', 'private:edit', 'segment:edit', 'io:import', 'io:export',
    'risk:edit', 'risk:approve', 'm3settings:edit', 'insight:export',
    // M6 门店级财务：对账标记、异常处置、导出（开票/结算审批/财务设置归区域/财务）
    'finance:reconcile', 'finance:abnormal:dispose', 'finance:export',
    // M5 门店级营销：发券/推送发送/核销/海报/直播（审批、渠道配置、落地页、设置归区域）
    'coupon:create', 'coupon:edit', 'push:create', 'push:send',
    'couponWriteoff:verify', 'poster:edit', 'live:edit', 'calendar:edit', 'marketing:export',
    // Wave 5 · T1 门店级：可见但不可删角色/不可改组织架构
    'role:assign',
    // Wave 5 · T2 门店级：标签发布/数据服务申请
    'tagFactory:publish', 'dataService:apply',
    // Wave 5 · T3 门店级：工单创建/关闭（跨模块路由）
    'ticket:create', 'ticket:close',
    // Wave 5 · T4 门店级：模型/特征只读，无操作权限
    // Wave 6 · A1 门店级：AI 操作端页面尚未落地，操作码字典已回收（AI 各页 view 随 ALL_VIEW 可见）
  ],
  CONSULTANT: [
    'customer:view', 'customer:create', 'customer:edit',
    'consult:view', 'consult:create', 'consult:edit',
    'prescription:view', 'prescription:create', 'prescription:edit',
    'appointment:view', 'appointment:create', 'appointment:edit',
    'emr:view', 'emr:create', 'emr:edit',
    'followup:view', 'followup:create', 'followup:edit',
    'marketing:view', 'course:view', 'reception:view', 'complaint:view',
    'recall:view', 'recall:create', 'handover:view', 'report:view',
    'schedule:view',
    'customer:phone:decrypt', // 咨询师对本人客户可解密手机号
    'aiScript:view', // A1-06 智能话术工作台（操作端未落地，插入码字典已回收）
  ],
  DOCTOR: [
    'emr:view', 'emr:create', 'emr:edit',
    'prescription:view', 'prescription:create', 'prescription:edit',
    'consult:view', 'consult:create', 'consult:edit', 'consult:review', // 医生二次审核/改单
    'writeoff:view', 'writeoff:create',
    'appointment:view', 'appointment:create', 'customer:view', 'course:view', 'complaint:view',
    'followup:view', 'followup:create', 'followup:edit', 'reception:view',
    'recall:view', 'recall:create', 'schedule:view',
    'workorder:view', 'workorder:create', 'daily:view',
    'checkin:view', 'room:view', 'equipment:view',
    'customer:phone:decrypt',
  ],
  FRONT_DESK: [
    'reception:view', 'reception:edit',
    'queue:view', 'queue:edit',
    'appointment:view', 'appointment:create', 'appointment:edit',
    'customer:view', 'customer:create',
    'cashier:view', 'cashier:create', 'cashier:sign',
    'handover:view', 'handover:create',
    'consult:view', 'emr:view', 'course:view', 'followup:view', 'complaint:view', 'complaint:create',
    'recall:view', 'recall:edit', 'schedule:view',
    'workorder:view', 'workorder:create', 'daily:view',
    // 划扣核销台（M2-01 执行台 + /writeoff 整单核销）：前台可建单/执行/查看
    'writeoffdesk:view', 'writeoff:view', 'writeoff:create',
  ],
  OPERATOR: [
    'marketing:view', 'marketing:create', 'marketing:edit',
    'followup:view', 'followup:create', 'followup:edit',
    'complaint:view', 'complaint:create', 'course:view', 'customer:view', 'consult:view',
    'appointment:view', 'report:view', 'recall:view', 'recall:edit', 'contract:view', 'schedule:view',
  ],
  FINANCE: [
    'approval:view', // 财务需处理退款/结算/调拨等审批
    'refund:view', 'refund:create', 'refund:approve', 'refund:sign',
    'cardcancel:view', 'cardcancel:create', 'cardcancel:approve', 'cardcancel:sign',
    'report:view', 'cashier:view', 'audit:view',
    'course:view', 'customer:view', 'contract:view', 'target:view',
    'compliance:view', 'health:view', 'inventory:view', 'tenant:view',
    'org:view', 'rbac:view', 'brand:view', 'dispatch:view', 'marketing:view',
    'sop:view', 'screen:view', 'settings:view', 'transfer:view', 'transfer:approve', 'transfer:edit',
    'complaint:view', 'complaint:approve',
    'finance:margin:view', // 财务可见成本/毛利（字段级）
    'report:export', 'schedule:view',
    'workorder:view', 'daily:view',
    // M6 数据财务：财务角色拥有全部 view + 对账/调平/结算/开票/提成/异常处置/预算/设置/导出
    'finance:view', 'finance:reconcile:view', 'finance:invoice:view', 'finance:settlement:view',
    'finance:cost:view', 'finance:commission:view', 'finance:writeoff:view', 'finance:prepay:view',
    'finance:cardbalance:view', 'finance:abnormal:view', 'finance:tax:view', 'finance:cashdaily:view',
    'finance:monthly:view', 'finance:budget:view', 'finance:settings:view',
    'finance:reconcile', 'finance:reconcile:approve',
    'finance:settlement:approve', 'finance:settlement:edit',
    'finance:invoice:edit', 'finance:invoice:approve', 'finance:commission:edit', 'finance:commission:approve',
    'finance:budget:edit', 'finance:settings:edit', 'finance:abnormal:dispose', 'finance:export',
  ],
}

const ROLE_SCOPE: Record<Role, DataScope> = {
  SUPER_ADMIN: 'GROUP',
  REGION_MGR: 'REGION',
  STORE_MGR: 'STORE',
  CONSULTANT: 'SELF',
  DOCTOR: 'STORE',
  FRONT_DESK: 'STORE',
  OPERATOR: 'STORE',
  FINANCE: 'REGION',
}

// 数据域优先级（多角色取最大范围）
const SCOPE_RANK: Record<DataScope, number> = {
  SELF: 0, STORE: 1, BRAND: 2, REGION: 3, GROUP: 4,
}

// 开发期花名册（每个角色一个代表，用于 ?as= 与切换器）
// 对齐后端 dev-login 真源：按工号升序每角色取第一人（org 服务种子 E 系列，实测 18096 实例）
const DEV_ROSTER: Record<Role, { staffId: string; name: string; avatarLetter: string; jobTitle: string; storeId: string }> = {
  SUPER_ADMIN: { staffId: 'E014', name: '蒋IT', avatarLetter: '蒋', jobTitle: '集团管理员', storeId: '' },
  REGION_MGR: { staffId: 'E011', name: '冯区域', avatarLetter: '冯', jobTitle: '区域经理', storeId: '' },
  STORE_MGR: { staffId: 'E005', name: '李店长', avatarLetter: '李', jobTitle: '门店店长', storeId: 'ST-SH-001' },
  CONSULTANT: { staffId: 'E004', name: '赵咨询师', avatarLetter: '赵', jobTitle: '咨询顾问', storeId: 'ST-SH-001' },
  DOCTOR: { staffId: 'E001', name: '刘治疗师', avatarLetter: '刘', jobTitle: '治疗师', storeId: 'ST-SH-001' },
  FRONT_DESK: { staffId: 'E002', name: '王前台', avatarLetter: '王', jobTitle: '前台/收银', storeId: 'ST-SH-001' },
  OPERATOR: { staffId: 'E013', name: '卫运营', avatarLetter: '卫', jobTitle: '运营', storeId: '' },
  FINANCE: { staffId: 'E012', name: '褚财务总监', avatarLetter: '褚', jobTitle: '财务', storeId: '' },
}

const ROLE_LABEL: Record<Role, string> = {
  SUPER_ADMIN: '集团管理员', REGION_MGR: '区域经理', STORE_MGR: '门店店长',
  CONSULTANT: '咨询顾问', DOCTOR: '医生', FRONT_DESK: '前台/收银',
  OPERATOR: '运营', FINANCE: '财务',
}

// 自定义角色（运行期注册，对齐"后续可自定义角色及权限"）
type RoleDef = { label: string; permissions: string[]; scope: DataScope }
const customRoles = ref<Record<string, RoleDef>>({})

// ?as= 角色参数（多角色逗号叠加）；非法角色忽略。登录后以会话真源为准。
function rolesFromQuery(): Role[] {
  const q = new URLSearchParams(location.search).get('as')
  if (!q) return []
  const list = q.split(',').map((s) => s.trim().toUpperCase()).filter(Boolean)
  return list.filter((r): r is Role => r in ROLE_PERMISSIONS)
}

// 会话信息（登录后由后端 JWT 响应填充；未登录为 null）
interface SessionInfo {
  token: string
  staffId: string
  staffName: string
  roles: Role[]
  storeCode: string
  scope: DataScope
  permissions: string[]
  devLogin: boolean
}

function loadSession(): SessionInfo | null {
  try {
    const raw = localStorage.getItem(SESSION_KEY)
    if (!raw) return null
    const s = JSON.parse(raw) as SessionInfo
    if (!s?.token || !Array.isArray(s.roles) || s.roles.length === 0) return null
    return s
  } catch {
    return null
  }
}

export const useAuthStore = defineStore('auth', () => {
  // 已登录会话（null = 未登录）。启动时从 localStorage 恢复。
  const session = ref<SessionInfo | null>(loadSession())

  // 服务端权限矩阵真源（启动异步拉取；未就绪或失败时离线模式回退硬编码 ROLE_PERMISSIONS）
  const matrix = ref<PermissionMatrix | null>(null)

  // 当前激活的角色集合：已登录取会话角色；未登录且 URL 带 ?as= 时用查询角色（离线演示兜底）
  const currentRoles = ref<Role[]>(
    session.value ? session.value.roles : rolesFromQuery(),
  )
  const isAuthenticated = computed(() => !!session.value)
  const storeId = ref<string>(
    session.value?.storeCode
      || DEV_ROSTER[currentRoles.value[0] ?? 'STORE_MGR']?.storeId
      || 'ST-SH-001',
  )

  // 单角色权限解析：优先服务端 rolePermissions 真源，缺失时回退前端硬编码
  function rolePerms(role: Role): string[] {
    const remote = matrix.value?.rolePermissions?.[role]
    return remote && remote.length ? remote : (ROLE_PERMISSIONS[role] || [])
  }

  // 多角色权限取并集；任一为 SUPER_ADMIN 则通配
  // 已登录：以后端 JWT 下发的 permissions 为真源（超管为 ['*']）；
  // 未登录（?as= 离线演示）：优先服务端 /org/auth/permissions 矩阵，拉取失败回退硬编码。
  const isSuper = computed(() => currentRoles.value.includes('SUPER_ADMIN'))
  const permissions = computed<Set<string>>(() => {
    const set = new Set<string>()
    if (session.value) {
      session.value.permissions.forEach((p) => set.add(p))
    } else {
      for (const r of currentRoles.value) {
        rolePerms(r).forEach((p) => set.add(p))
      }
    }
    for (const def of Object.values(customRoles.value)) {
      def.permissions.forEach((p) => set.add(p))
    }
    return set
  })

  // 多角色数据域取最大范围；已登录直接用后端下发 scope
  const scope = computed<DataScope>(() => {
    if (session.value) return session.value.scope
    let best: DataScope = 'SELF'
    for (const r of currentRoles.value) {
      const s = ROLE_SCOPE[r]
      if (SCOPE_RANK[s] > SCOPE_RANK[best]) best = s
    }
    return best
  })

  // 展示用主角色（取第一个），同时保留叠加信息
  const primaryRole = computed<Role>(() => currentRoles.value[0] ?? 'STORE_MGR')
  const user = computed(() => {
    const fallback = DEV_ROSTER[primaryRole.value]
    const name = session.value?.staffName || fallback?.name || '未登录'
    return {
      staffId: session.value?.staffId || fallback?.staffId || '',
      name,
      avatarLetter: name.charAt(0),
      jobTitle: ROLE_LABEL[primaryRole.value] || fallback?.jobTitle || '',
      storeId: session.value?.storeCode || storeId.value,
      role: primaryRole.value,
      roles: [...currentRoles.value],
      roleLabels: currentRoles.value.map((x) => ROLE_LABEL[x]).filter(Boolean).join(' + '),
      scope: scope.value,
    }
  })

  /** 是否拥有某权限（支持通配 *） */
  function can(perm: string): boolean {
    if (isSuper.value) return true
    if (permissions.value.has('*')) return true
    return permissions.value.has(perm)
  }

  /** 用后端登录结果建立会话并持久化 */
  function applySession(res: LoginResult) {
    const roles = (res.roles || []).filter((r): r is Role => r in ROLE_PERMISSIONS)
    const info: SessionInfo = {
      token: res.token,
      staffId: res.staffId,
      staffName: res.staffName,
      roles: roles.length ? roles : [res.roleCode as Role],
      storeCode: res.storeCode,
      scope: (res.scope as DataScope) || 'STORE',
      permissions: res.permissions || [],
      devLogin: res.devLogin,
    }
    session.value = info
    currentRoles.value = [...info.roles]
    storeId.value = info.storeCode || storeId.value
    setToken(info.token)
    localStorage.setItem(SESSION_KEY, JSON.stringify(info))
  }

  /** 工号 + 密码登录 */
  async function login(loginName: string, password: string) {
    const res = await apiLogin(loginName, password)
    applySession(res)
  }

  /**
   * 以角色身份进入（开发期免密）：顶栏角色切换器 / 登录页快捷入口用。
   * 调后端 dev-login 取该角色第一名员工的真实 token；后端不可用时回退离线演示。
   */
  async function loginByRole(role: Role) {
    const res = await apiDevLogin({ role })
    applySession(res)
  }

  /** 指定工号免密登录（联调真实员工） */
  async function devLoginByStaff(staffId: string) {
    const res = await apiDevLogin({ staffId })
    applySession(res)
  }

  /** 兼容旧调用：以单角色视角进入（优先走真实 dev-login，仅后端网络不可达时回退离线） */
  async function loginAs(role: Role) {
    try {
      await loginByRole(role)
    } catch (e: any) {
      // 业务拒绝（如 403 开发期登录已关闭、404 角色无可用员工）必须如实抛出，
      // 不能静默落离线假会话（否则无 token 继续操作会全站 401 的假交互）；
      // 仅后端网络不可达（无 response）时才回退离线演示视角。
      if (e?.response) {
        const data = e.response.data
        throw new Error(data?.message || data?.error || '角色切换失败，请使用工号密码登录')
      }
      // 后端不可用时的离线演示兜底（不写 token）
      session.value = null
      currentRoles.value = [role]
      storeId.value = DEV_ROSTER[role]?.storeId || storeId.value
    }
  }

  /** 退出登录：清会话与 token，回到登录页 */
  function logout() {
    session.value = null
    currentRoles.value = []
    clearToken()
    localStorage.removeItem(SESSION_KEY)
  }

  /** 启动时恢复会话：localStorage 有会话则回填 token（供 axios 拦截器用） */
  function restoreSession() {
    const s = loadSession()
    if (s) {
      session.value = s
      currentRoles.value = [...s.roles]
      storeId.value = s.storeCode || storeId.value
      setToken(s.token)
      return true
    }
    return false
  }

  /**
   * 启动异步拉取服务端权限矩阵（角色→权限唯一真源）。
   * 失败静默：离线 ?as= 演示自动回退前端硬编码 ROLE_PERMISSIONS，不阻塞页面渲染。
   */
  async function loadMatrix(): Promise<boolean> {
    try {
      matrix.value = await apiGetPermissions()
      return true
    } catch {
      return false
    }
  }

  /** 叠加/移除一个角色（离线演示一人多角色权限并集；登录后以会话真源为准） */
  function toggleRole(role: Role) {
    const idx = currentRoles.value.indexOf(role)
    if (idx >= 0) {
      if (currentRoles.value.length > 1) currentRoles.value.splice(idx, 1)
    } else {
      currentRoles.value.push(role)
    }
  }

  /** 注册自定义角色及其权限（后续"角色管理"页落地用） */
  function registerCustomRole(key: string, def: RoleDef) {
    customRoles.value[key] = def
  }

  return {
    session, currentRoles, primaryRole, storeId, scope, permissions, isSuper, user,
    isAuthenticated,
    can, login, loginByRole, devLoginByStaff, loginAs, logout, restoreSession, loadMatrix,
    toggleRole, registerCustomRole,
  }
})
