// ============================================================
// 导航配置（单一来源，替代 App.vue 硬编码菜单）
// 每项带 permission 码 → 菜单按 auth.can() 过滤，路由守卫按 permissionForPath() 校验
// 对齐 docs/permission-matrix.md §7
//
// 结构（Wave 8 导航重构）：
//   DOMAINS   — 顶部 7 业务域 Tab（工作台/客户运营/门店运营/营销/财务/AI/管理后台）
//   NAV_GROUPS — 每个域下若干分组（每组 3-10 项）
//   TOPBAR_QUICK — 顶栏通用图标入口（搜索/消息/帮助/主题/个人）
// ============================================================
import type { PageTitle } from '@/components/CShellDesktop.vue'

export type DomainKey =
  | 'workbench'
  | 'customer'
  | 'store'
  | 'marketing'
  | 'finance'
  | 'ai'
  | 'admin'

export interface DomainMeta {
  key: DomainKey
  label: string
  icon: string
  /** 可访问该域所需的任一权限（任一命中即可见 Tab）；undefined=所有登录者可见 */
  anyPerm?: string[]
}

export interface NavItem {
  to: string
  label: string
  icon: string
  permission?: string // 见 permission-matrix.md；省略=登录即可见
  badge?: string | number
  /** 仅需登录态（不需要具体权限），守卫识别哨兵 '__AUTH__' */
  authOnly?: boolean
}
export interface NavGroup {
  title?: string
  domain: DomainKey
  items: NavItem[]
}
export interface QuickItem {
  to: string
  label: string
  icon: string
  authOnly?: boolean
}

// ============================================================
// 顶部 7 业务域 Tab
// ============================================================
export const DOMAINS: DomainMeta[] = [
  { key: 'workbench', label: '工作台', icon: 'dashboard' },
  { key: 'customer', label: '客户运营', icon: 'customer', anyPerm: ['customer:view', 'followup:view', 'complaint:view', 'course:view', 'churn:view'] },
  { key: 'store', label: '门店运营', icon: 'store', anyPerm: ['inventory:view', 'schedule:view', 'tenant:view', 'equipment:view', 'performance:view', 'catalog:view', 'room:view'] },
  { key: 'marketing', label: '营销中心', icon: 'marketing', anyPerm: ['marketing:view', 'coupon:view', 'push:view', 'live:view', 'roi:view'] },
  { key: 'finance', label: '财务', icon: 'order', anyPerm: ['finance:view', 'refund:view', 'cardcancel:view'] },
  { key: 'ai', label: 'AI 中心', icon: 'sign', anyPerm: ['ai:view', 'aiProfile:view', 'model:view'] },
  { key: 'admin', label: '管理后台', icon: 'shield', anyPerm: ['org:view', 'rbac:view', 'collect:view', 'ticket:view', 'compliance:view', 'report:view', 'settings:view'] },
]

// ============================================================
// 业务导航分组
// ============================================================
export const NAV_GROUPS: NavGroup[] = [
  // ========== 1. 工作台（一线服务作业动线）==========
  // 重构：一线首页=全院流水牌+角色待办+客户档案，作业页按"客户动线阶段"分组，术后三页归同组。
  {
    domain: 'workbench',
    title: '一线首页',
    items: [
      { to: '/board', label: '全院流水牌', icon: 'dashboard', authOnly: true },
      { to: '/my-workbench', label: '我的工作台', icon: 'home', authOnly: true },
      { to: '/customers', label: '客户档案', icon: 'customer', permission: 'customer:view' },
    ],
  },
  {
    domain: 'workbench',
    title: '预约与接待',
    items: [
      { to: '/appointment', label: '预约看板', icon: 'dashboard', permission: 'appointment:view' },
      { to: '/queue', label: '智能排队候补', icon: 'clock', permission: 'queue:view' },
      { to: '/reception', label: '接待台', icon: 'home', permission: 'reception:view' },
      { to: '/guest-reg', label: '客情登记', icon: 'user', permission: 'customer:create' },
      { to: '/m2-checkin', label: '到店核销', icon: 'scan', permission: 'checkin:view' },
    ],
  },
  {
    domain: 'workbench',
    title: '咨询与诊疗',
    items: [
      { to: '/consultation', label: '咨询工作台', icon: 'chat', permission: 'consult:view' },
      { to: '/doctor', label: '医师工作台', icon: 'shield', permission: 'consult:review' },
      { to: '/prescription', label: '项目开方开单', icon: 'order', permission: 'prescription:view' },
      { to: '/emr', label: '电子病历管理', icon: 'box', permission: 'emr:view' },
    ],
  },
  {
    domain: 'workbench',
    title: '收银与核销',
    items: [
      { to: '/order', label: '收款收银', icon: 'pos', permission: 'cashier:view' },
      { to: '/writeoff', label: '划扣核销', icon: 'check-square', permission: 'writeoff:view' },
      { to: '/m2-writeoff-desk', label: '划扣执行台', icon: 'check-square', permission: 'writeoffdesk:view' },
    ],
  },
  {
    domain: 'workbench',
    title: '术后跟进',
    items: [
      { to: '/followup', label: '术后回访与满意度', icon: 'phone', permission: 'followup:view' },
      { to: '/sop', label: '术后 SOP 编排', icon: 'layers', permission: 'followup:view' },
      { to: '/recall', label: '复诊提醒管理', icon: 'bell', permission: 'recall:view' },
    ],
  },
  {
    domain: 'workbench',
    title: '协同与概览',
    items: [
      { to: '/conversion-funnel', label: '转化漏斗分析', icon: 'funnel', permission: 'consult:view' },
      { to: '/m1', label: '经营概览', icon: 'trend-up', permission: 'report:view' },
      { to: '/approval', label: '审批中心', icon: 'check-square', permission: 'approval:view' },
      { to: '/handover', label: '交接班', icon: 'handover', permission: 'handover:view' },
      { to: '/notifications', label: '消息通知', icon: 'bell', authOnly: true },
    ],
  },

  // ========== 2. 客户运营 ==========
  {
    domain: 'customer',
    title: '客情洞察',
    items: [
      { to: '/customers', label: '会员列表', icon: 'customer', permission: 'customer:view' },
      { to: '/customer-graph', label: '客户图谱与撞单消解', icon: 'customer', permission: 'customer:view' },
      { to: '/complaint', label: '投诉与医疗风险', icon: 'alert', permission: 'complaint:view' },
      { to: '/customers/merge', label: '客户合并去重', icon: 'org', permission: 'customer:merge' },
      { to: '/m3-io', label: '客户导入导出', icon: 'export', permission: 'io:view' },
    ],
  },
  {
    domain: 'customer',
    title: '客户资产',
    items: [
      { to: '/card-course', label: '卡项疗程', icon: 'card', permission: 'course:view' },
      { to: '/course-track', label: '疗程跟踪', icon: 'trend-up', permission: 'course:track' },
      { to: '/contract', label: '合同管理', icon: 'order', permission: 'contract:view' },
      { to: '/asset-transfer', label: '客户资产转移', icon: 'trend-up', permission: 'transfer:view' },
      { to: '/m3-levels', label: '等级体系', icon: 'sign', permission: 'level:view' },
      { to: '/m3-points-mall', label: '积分商城', icon: 'package', permission: 'points:view' },
      { to: '/m3-tags', label: '标签体系', icon: 'profile', permission: 'tag:view' },
      { to: '/m3-journey', label: '客户旅程', icon: 'trend-up', permission: 'journey:view' },
      { to: '/m3-tasks', label: '跟进任务', icon: 'check-square', permission: 'followuptask:view' },
      { to: '/m3-care', label: '生日节日关怀', icon: 'bell', permission: 'care:view' },
      { to: '/m3-nps', label: '满意度NPS', icon: 'check-square', permission: 'nps:view' },
      { to: '/m3-private', label: '私域运营', icon: 'chat', permission: 'private:view' },
    ],
  },
  {
    domain: 'customer',
    title: 'AI 增长与风控',
    items: [
      { to: '/m3-segment', label: 'AI客户分群', icon: 'marketing', permission: 'segment:view' },
      { to: '/m3-churn', label: '流失预警', icon: 'alert', permission: 'churn:view' },
      { to: '/m3-referral', label: '转介绍管理', icon: 'customer', permission: 'referral:view' },
      { to: '/m3-risk', label: '黑名单风控', icon: 'shield', permission: 'risk:view' },
      { to: '/m3-insight', label: '客户洞察报告', icon: 'trend-up', permission: 'insight:view' },
      { to: '/m3-settings', label: '客户域设置', icon: 'settings', permission: 'm3settings:view' },
    ],
  },

  // ========== 3. 门店运营（店务管理：商品价格/排班库存/绩效报表/管控/主数据）==========
  {
    domain: 'store',
    title: '商品与价格',
    items: [
      { to: '/m2-catalog', label: '卡项疗程定义', icon: 'card', permission: 'catalog:view' },
      { to: '/m2-projects', label: '医美项目库', icon: 'package', permission: 'pricelist:view' },
      { to: '/m2-pricelist', label: '价目表', icon: 'order', permission: 'pricelist:view' },
    ],
  },
  {
    domain: 'store',
    title: '门店日常',
    items: [
      { to: '/m2-schedule', label: '排班与考勤', icon: 'calendar', permission: 'schedule:view' },
      { to: '/m2-inventory', label: '库存耗材', icon: 'box', permission: 'inventory:view' },
      { to: '/m2-requisition', label: '物料申领', icon: 'package', permission: 'requisition:view' },
      { to: '/m2-wastage', label: '损耗报损', icon: 'alert', permission: 'wastage:view' },
      { to: '/m2-rooms', label: '床位房间', icon: 'home', permission: 'room:view' },
      { to: '/m2-equipment', label: '设备资产', icon: 'settings', permission: 'equipment:view' },
      { to: '/m2-workorder', label: '服务工单', icon: 'tool', permission: 'workorder:view' },
      { to: '/m2-daily', label: '门店日报', icon: 'dashboard', permission: 'daily:view' },
      { to: '/m2-weekly', label: '经营周报', icon: 'export', permission: 'weekly:view' },
      { to: '/m2-performance', label: '员工绩效', icon: 'trend-up', permission: 'performance:view' },
    ],
  },
  {
    domain: 'store',
    title: '门店管控',
    items: [
      { to: '/m2-inspection', label: '巡店检查', icon: 'shield', permission: 'inspection:view' },
      { to: '/m2-acquisition', label: '拓客活动', icon: 'marketing', permission: 'acquisition:view' },
      { to: '/m2-reactivate', label: '沉睡唤醒', icon: 'bell', permission: 'reactivate:view' },
      { to: '/m2-exception', label: '异常处理', icon: 'alert', permission: 'exception:view' },
      { to: '/m2-settings', label: '门店设置', icon: 'settings', permission: 'm2settings:view' },
      { to: '/m2-help', label: '帮助培训', icon: 'profile', permission: 'help:view' },
    ],
  },
  {
    domain: 'store',
    title: '门店主数据',
    items: [
      { to: '/m1-tenant', label: '门店主数据', icon: 'store', permission: 'tenant:view' },
      { to: '/m1-region', label: '区域管理', icon: 'box', permission: 'tenant:view' },
      { to: '/m1-procurement', label: '采购供应链', icon: 'package', permission: 'inventory:view' },
      { to: '/m1-dispatch', label: '调度中心', icon: 'calendar', permission: 'dispatch:view' },
    ],
  },

  // ========== 4. 营销中心 ==========
  {
    domain: 'marketing',
    title: '营销玩法',
    items: [
      { to: '/m1-marketing', label: '营销活动', icon: 'marketing', permission: 'marketing:view' },
      { to: '/m5-coupons', label: '优惠券管理', icon: 'card', permission: 'coupon:view' },
      { to: '/m5-writeoff', label: '优惠券核销', icon: 'check-square', permission: 'couponWriteoff:view' },
      { to: '/m5-push', label: '短信/企微推送', icon: 'bell', permission: 'push:view' },
      { to: '/m5-poster', label: '裂变海报', icon: 'scissors', permission: 'poster:view' },
      { to: '/m5-referral', label: '老带新', icon: 'customer', permission: 'referralCampaign:view' },
      { to: '/m5-calendar', label: '会员日/节日营销', icon: 'calendar', permission: 'calendar:view' },
    ],
  },
  {
    domain: 'marketing',
    title: '内容与渠道',
    items: [
      { to: '/m5-live', label: '直播/短视频', icon: 'volume', permission: 'live:view' },
      { to: '/m5-landing', label: '落地页搭建', icon: 'profile', permission: 'landing:view' },
      { to: '/m5-channel', label: '渠道管理', icon: 'org', permission: 'channel:view' },
      { to: '/m5-assets', label: '素材库', icon: 'package', permission: 'asset:view' },
    ],
  },
  {
    domain: 'marketing',
    title: '效果分析',
    items: [
      { to: '/m5-roi', label: '投放ROI', icon: 'trend-up', permission: 'roi:view' },
      { to: '/m5-dashboard', label: '营销数据看板', icon: 'dashboard', permission: 'marketingDash:view' },
      { to: '/m5-settings', label: '营销设置', icon: 'settings', permission: 'm5settings:view' },
    ],
  },

  // ========== 5. 财务 ==========
  {
    domain: 'finance',
    title: '退款与结算',
    items: [
      { to: '/refund', label: '退款管理', icon: 'refund', permission: 'refund:view' },
      { to: '/card-cancel', label: '退卡管理', icon: 'card', permission: 'cardcancel:view' },
      { to: '/m6-settlement', label: '分账结算', icon: 'refund', permission: 'finance:settlement:view' },
      { to: '/m6-reconcile', label: '对账中心', icon: 'check-square', permission: 'finance:reconcile:view' },
      { to: '/m6-prepay', label: '预收款监管', icon: 'shield', permission: 'finance:prepay:view' },
    ],
  },
  {
    domain: 'finance',
    title: '账务与流水',
    items: [
      { to: '/m6-ledger', label: '收支流水', icon: 'order', permission: 'finance:view' },
      { to: '/m6-writeoff', label: '划扣明细', icon: 'check-square', permission: 'finance:writeoff:view' },
      { to: '/m6-card-balance', label: '会员卡余额', icon: 'card', permission: 'finance:cardbalance:view' },
      { to: '/m6-invoice', label: '发票管理', icon: 'profile', permission: 'finance:invoice:view' },
      { to: '/m6-abnormal', label: '异常账务', icon: 'alert', permission: 'finance:abnormal:view' },
      { to: '/m6-tax', label: '税务报表', icon: 'profile', permission: 'finance:tax:view' },
      { to: '/m6-cash-daily', label: '资金日报', icon: 'dashboard', permission: 'finance:cashdaily:view' },
    ],
  },
  {
    domain: 'finance',
    title: '成本与报表',
    items: [
      { to: '/m6-cost', label: '成本分析', icon: 'box', permission: 'finance:cost:view' },
      { to: '/m6-margin', label: '毛利报表', icon: 'trend-up', permission: 'finance:cost:view' },
      { to: '/m6-commission', label: '咨询师提成', icon: 'sign', permission: 'finance:commission:view' },
      { to: '/m6-monthly', label: '经营月报', icon: 'export', permission: 'finance:monthly:view' },
      { to: '/m6-budget', label: '预算管控', icon: 'sign', permission: 'finance:budget:view' },
      { to: '/m6-settings', label: '财务设置', icon: 'settings', permission: 'finance:settings:view' },
    ],
  },

  // ========== 6. AI 中心 ==========
  {
    domain: 'ai',
    title: 'AI 总览',
    items: [
      { to: '/ai', label: '智能中心', icon: 'dashboard', permission: 'ai:view' },
      { to: '/ai/profile', label: '客户画像', icon: 'customer', permission: 'aiProfile:view' },
      { to: '/ai/repurchase', label: '复购预测', icon: 'marketing', permission: 'aiRepurchase:view' },
      { to: '/ai/churn-model', label: '流失预警', icon: 'alert', permission: 'aiChurn:view' },
      { to: '/ai/daily-report', label: 'AI 经营日报', icon: 'dashboard', permission: 'aiDaily:view' },
    ],
  },
  {
    domain: 'ai',
    title: '内容与话术',
    items: [
      { to: '/ai/scripts', label: '智能话术', icon: 'sign', permission: 'aiScript:view' },
      { to: '/ai/chatbot', label: 'AI 客服', icon: 'customer', permission: 'aiChatbot:view' },
      { to: '/ai/content', label: '内容生成', icon: 'marketing', permission: 'aiContent:view' },
      { to: '/ai/knowledge', label: '知识库', icon: 'box', permission: 'aiKnowledge:view' },
      { to: '/ai/scheduling', label: '智能排班', icon: 'calendar', permission: 'aiScheduling:view' },
    ],
  },
  {
    domain: 'ai',
    title: '模型与治理',
    items: [
      { to: '/ai/models', label: '模型仓库', icon: 'box', permission: 'model:view' },
      { to: '/ai/compute', label: '算力管理', icon: 'dashboard', permission: 'compute:view' },
      { to: '/ai/features', label: '特征平台', icon: 'sign', permission: 'feature:view' },
      { to: '/ai/monitor', label: '监控告警', icon: 'bell', permission: 'monitor:view' },
      { to: '/ai/sensitive', label: '敏感词检测', icon: 'alert', permission: 'aiSensitive:view' },
      { to: '/ai/govern', label: '审批与评估', icon: 'shield', permission: 'aiGovern:view' },
      { to: '/ai/privacy', label: '隐私合规', icon: 'shield', permission: 'aiPrivacy:view' },
      { to: '/ai/gateway', label: '网关与日志', icon: 'box', permission: 'aiGateway:view' },
      { to: '/ai/admin', label: '平台管理', icon: 'org', permission: 'aiAdmin:view' },
    ],
  },

  // ========== 7. 管理后台 ==========
  {
    domain: 'admin',
    title: '集团治理',
    items: [
      { to: '/m1-matrix', label: '指标矩阵', icon: 'trend-up', permission: 'report:view' },
      { to: '/m1-compare', label: '门店对标', icon: 'scan', permission: 'report:view' },
      { to: '/m1-screen', label: '数据大屏', icon: 'dashboard', permission: 'screen:view' },
      { to: '/m1-target', label: '目标管理', icon: 'trend-up', permission: 'target:view' },
      { to: '/m1-report', label: '报表中心', icon: 'export', permission: 'report:view' },
      { to: '/m1-compliance', label: '合规中心', icon: 'shield', permission: 'compliance:view' },
      { to: '/m1-audit-log', label: '审计日志', icon: 'check-square', permission: 'audit:view' },
      { to: '/m1-health', label: '健康度巡检', icon: 'bell', permission: 'health:view' },
      { to: '/m1-sop', label: '标准作业SOP', icon: 'check-square', permission: 'sop:view' },
      { to: '/m1-brand', label: '品牌品类', icon: 'mall', permission: 'brand:view' },
    ],
  },
  {
    domain: 'admin',
    title: '权限中台',
    items: [
      { to: '/m1-org', label: '组织架构权限', icon: 'org', permission: 'org:view' },
      { to: '/m1-rbac', label: '字段级RBAC', icon: 'sign', permission: 'rbac:view' },
      { to: '/admin/staff', label: '员工管理', icon: 'customer', permission: 'rbac:view' },
      { to: '/admin/roles', label: '角色管理', icon: 'shield', permission: 'role:view' },
      { to: '/admin/permissions', label: '权限矩阵', icon: 'check-square', permission: 'permission:view' },
      { to: '/admin/org', label: '组织架构(T1)', icon: 'org', permission: 'org:view' },
    ],
  },
  {
    domain: 'admin',
    title: '数据与流程中台',
    items: [
      { to: '/data/collect', label: '数据采集', icon: 'upload', permission: 'collect:view' },
      { to: '/data/govern', label: '数据治理', icon: 'shield', permission: 'govern:view' },
      { to: '/data/tags', label: '标签工厂', icon: 'profile', permission: 'tagFactory:view' },
      { to: '/data/service', label: '数据服务', icon: 'package', permission: 'dataService:view' },
      { to: '/workorders', label: '工单中心', icon: 'order', permission: 'ticket:view' },
      { to: '/integrations', label: '集成中心', icon: 'settings', permission: 'integration:view' },
      { to: '/admin/mp-settings', label: '小程序与支付', icon: 'tool', permission: 'integration:view' },
    ],
  },
  {
    domain: 'admin',
    title: '系统',
    items: [
      { to: '/m1-settings', label: '系统设置', icon: 'settings', permission: 'settings:view' },
      { to: '/admin/dictionary', label: '数据字典库', icon: 'layers', permission: 'settings:view' },
    ],
  },
]

// ============================================================
// 顶栏通用图标入口（不占侧栏；均需登录态）
// ============================================================
export const TOPBAR_QUICK: QuickItem[] = [
  { to: '/search', label: '全局搜索', icon: 'search', authOnly: true },
  { to: '/notifications', label: '消息', icon: 'bell', authOnly: true },
  { to: '/help', label: '帮助中心', icon: 'chat', authOnly: true },
  { to: '/guide', label: '新手引导', icon: 'check-square', authOnly: true },
  { to: '/notif-settings', label: '消息设置', icon: 'settings', authOnly: true },
  { to: '/theme', label: '主题外观', icon: 'sun', authOnly: true },
  { to: '/about', label: '关于版本', icon: 'dashboard', authOnly: true },
  { to: '/profile', label: '个人中心', icon: 'user', authOnly: true },
]

export const PAGE_TITLES: Record<string, PageTitle> = {
  '/': { breadcrumb: '数据驾驶舱', title: '数据驾驶舱' },
  // 业务域频道聚合首页（大字频道名在上 + 小字模块描述在下）
  '/workbench': { breadcrumb: '工作台', title: '工作台', subtitle: '今日门店运营一览 · 预约 / 接待 / 协同' },
  '/board': { breadcrumb: '工作台 / 全院流水牌', title: '全院客户流水牌', subtitle: '候诊 → 咨询 → 审核 → 收费 → 治疗 → 回访 全院在院客户实时流转' },
  '/my-workbench': { breadcrumb: '工作台 / 我的工作台', title: '我的工作台', subtitle: '我的待办 · 高频操作 · 今日看板' },
  '/customer': { breadcrumb: '客户运营', title: '客户运营', subtitle: '会员资产 · 客情洞察 · 复购与流失' },
  '/store': { breadcrumb: '门店运营', title: '门店运营', subtitle: '咨询 · 收银 · 核销 · 库存 · 排班' },
  '/marketing': { breadcrumb: '营销中心', title: '营销中心', subtitle: '活动 · 优惠券 · 渠道裂变 · 直播' },
  '/finance': { breadcrumb: '财务', title: '财务', subtitle: '收支 · 结算 · 退款 · 储值卡' },
  '/ai': { breadcrumb: 'AI 中心', title: 'AI 智能中心', subtitle: '画像 · 预测 · 内容 · 风控 · 模型运营闭环' },
  '/admin': { breadcrumb: '管理后台', title: '管理后台', subtitle: '组织 · 权限 · 数据 · 工单 · 合规' },
  '/appointment': { breadcrumb: '预约管理 / 预约看板', title: '预约看板', hideTopbar: true },
  '/appointment/detail': { breadcrumb: '预约管理 / 预约详情', title: '预约详情', hideTopbar: true, hideTopbarDesktop: true },
  '/appointment/new': { breadcrumb: '预约管理 / 新建预约', title: '新建预约', hideTopbar: true },
  '/queue': { breadcrumb: '预约管理 / 智能排队候补', title: '智能排队与候补', hideTopbar: true },
  '/customers': { breadcrumb: '客户管理 / 会员列表', title: '会员列表' },
  '/reception': { breadcrumb: '接待管理 / 接待台', title: '接待台', hideTopbar: true },
  '/guest-reg': { breadcrumb: '客情管理 / 客情登记', title: '客情登记', hideTopbar: true },
  '/customer-graph': { breadcrumb: '客情管理 / 客户图谱与撞单消解', title: '客户图谱与撞单消解', hideTopbar: true },
  '/prescription': { breadcrumb: '工作台 / 项目开方开单', title: '项目开方开单', hideTopbar: true },
  '/followup': { breadcrumb: '术后管理 / 术后回访与满意度', title: '术后回访与满意度', hideTopbar: true },
  '/sop': { breadcrumb: '术后管理 / 术后 SOP 编排', title: '术后 SOP 编排与执行', subtitle: '随访节点模板 · 批次执行看板 · 超期升级' },
  '/conversion-funnel': { breadcrumb: '工作台 / 转化漏斗分析', title: '转化漏斗分析', subtitle: '线索 → 到院 → 咨询 → 成交 → 复购 全链路转化' },
  '/complaint': { breadcrumb: '术后管理 / 投诉与医疗风险处理', title: '投诉与医疗风险处理', hideTopbar: true },
  '/refund': { breadcrumb: '交易管理 / 退款管理', title: '退款管理', hideTopbar: true },
  '/customer-profile': { breadcrumb: '客户管理 / 客户画像', title: '客户画像' },
  '/consultation': { breadcrumb: '工作台 / 咨询工作台', title: '咨询工作台', hideTopbar: true },
  '/writeoff': { breadcrumb: '工作台 / 划扣核销', title: '划扣核销', hideTopbar: true },
  '/emr': { breadcrumb: '工作台 / 电子病历管理', title: '电子病历管理', hideTopbar: true },
  '/recall': { breadcrumb: '工作台 / 复诊提醒管理', title: '复诊提醒管理', hideTopbar: true },
  '/asset-transfer': { breadcrumb: '资产转移 / 客户资产转移', title: '客户资产转移', hideTopbar: true },
  '/contract': { breadcrumb: '合同管理 / 合同档案', title: '合同管理', hideTopbar: true },
  '/course-track': { breadcrumb: '疗程管理 / 疗程跟踪', title: '疗程跟踪', hideTopbar: true },
  '/card-cancel': { breadcrumb: '退卡管理 / 退卡申请', title: '退卡管理', hideTopbar: true },
  '/order': { breadcrumb: '工作台 / 收款收银', title: '收款收银', hideTopbar: true },
  '/inventory': { breadcrumb: '库存管理 / 库存耗材', title: '库存耗材' },
  '/settings': { breadcrumb: '系统管理 / 系统配置', title: '系统配置' },
  '/handover': { breadcrumb: '工作台 / 双签交接', title: '双签交接' },
  '/card-course': { breadcrumb: '卡项管理 / 卡项疗程', title: '卡项疗程' },
  '/closed-loop': { breadcrumb: '接待管理 / 闭环样板', title: '客户到店闭环样板', hideTopbar: true },
  /* 协同中台 */
  '/approval': { breadcrumb: '工作台 / 审批中心', title: '审批中心' },
  '/notifications': { breadcrumb: '工作台 / 消息通知', title: '消息通知' },
  /* 门店运营 M2 */
  '/m2-inventory': { breadcrumb: '门店运营 / 库存耗材', title: '库存耗材' },
  '/m2-schedule': { breadcrumb: '门店运营 / 排班与考勤', title: '排班与考勤' },
  '/m2-workorder': { breadcrumb: '门店运营 / 服务工单', title: '服务工单' },
  '/m2-daily': { breadcrumb: '门店运营 / 门店日报', title: '门店日报' },
  '/m2-requisition': { breadcrumb: '门店运营 / 物料申领', title: '物料申领' },
  '/m2-wastage': { breadcrumb: '门店运营 / 损耗报损', title: '损耗报损' },
  '/m2-rooms': { breadcrumb: '门店运营 / 床位房间', title: '床位房间管理' },
  '/m2-equipment': { breadcrumb: '门店运营 / 设备资产', title: '设备资产管理' },
  '/m2-performance': { breadcrumb: '门店运营 / 员工绩效', title: '员工绩效看板' },
  '/m2-weekly': { breadcrumb: '门店运营 / 经营周报', title: '经营周报' },
  '/m2-pricelist': { breadcrumb: '门店运营 / 价目表', title: '价目表管理' },
  '/m2-catalog': { breadcrumb: '门店运营 / 卡项疗程定义', title: '卡项疗程定义' },
  '/m2-projects': { breadcrumb: '门店运营 / 医美项目库', title: '医美项目库' },
  '/m2-writeoff-desk': { breadcrumb: '工作台 / 划扣执行台', title: '划扣执行台' },
  '/m2-checkin': { breadcrumb: '工作台 / 会员到店核销', title: '会员到店核销' },
  '/m2-inspection': { breadcrumb: '门店运营 / 巡店检查', title: '巡店检查' },
  '/m2-acquisition': { breadcrumb: '门店运营 / 拓客活动', title: '拓客活动' },
  '/m2-reactivate': { breadcrumb: '门店运营 / 沉睡唤醒', title: '沉睡客户唤醒' },
  '/m2-exception': { breadcrumb: '门店运营 / 异常处理', title: '异常处理' },
  '/m2-settings': { breadcrumb: '门店运营 / 门店设置', title: '门店设置' },
  '/m2-help': { breadcrumb: '门店运营 / 帮助培训', title: '帮助与培训' },

  // M3 客户资产
  '/customers/:id': { breadcrumb: '客户运营 / 客户360', title: '客户 360', hideTopbar: true, hideTopbarDesktop: true },
  '/customers/merge': { breadcrumb: '客户运营 / 合并去重', title: '客户合并去重' },
  '/m3-levels': { breadcrumb: '客户运营 / 等级体系', title: '会员等级体系' },
  '/m3-points-mall': { breadcrumb: '客户运营 / 积分商城', title: '积分商城管理' },
  '/m3-tags': { breadcrumb: '客户运营 / 标签体系', title: '标签体系' },
  '/m3-journey': { breadcrumb: '客户运营 / 客户旅程', title: '客户旅程' },
  '/m3-tasks': { breadcrumb: '客户运营 / 跟进任务', title: '跟进任务' },
  '/m3-care': { breadcrumb: '客户运营 / 生日节日关怀', title: '生日节日关怀' },
  '/m3-churn': { breadcrumb: '客户运营 / 流失预警', title: '客户流失预警' },
  '/m3-referral': { breadcrumb: '客户运营 / 转介绍管理', title: '转介绍管理' },
  '/m3-nps': { breadcrumb: '客户运营 / 满意度NPS', title: '满意度 / NPS' },
  '/m3-private': { breadcrumb: '客户运营 / 私域运营', title: '私域运营' },
  '/m3-segment': { breadcrumb: '客户运营 / AI客户分群', title: 'AI 客户分群' },
  '/m3-io': { breadcrumb: '客户运营 / 导入导出', title: '客户导入导出' },
  '/m3-risk': { breadcrumb: '客户运营 / 黑名单风控', title: '黑名单与风控' },
  '/m3-settings': { breadcrumb: '客户运营 / 客户域设置', title: '客户域设置' },
  '/m3-insight': { breadcrumb: '客户运营 / 客户洞察报告', title: '客户洞察报告' },

  // M5 营销中心
  '/m5-coupons': { breadcrumb: '营销中心 / 优惠券管理', title: '优惠券管理' },
  '/m5-push': { breadcrumb: '营销中心 / 短信企微推送', title: '短信 / 企微推送' },
  '/m5-poster': { breadcrumb: '营销中心 / 裂变海报', title: '裂变海报' },
  '/m5-live': { breadcrumb: '营销中心 / 直播短视频', title: '直播 / 短视频' },
  '/m5-roi': { breadcrumb: '营销中心 / 投放ROI', title: '投放 ROI' },
  '/m5-channel': { breadcrumb: '营销中心 / 渠道管理', title: '渠道管理' },
  '/m5-landing': { breadcrumb: '营销中心 / 落地页搭建', title: '落地页搭建' },
  '/m5-calendar': { breadcrumb: '营销中心 / 会员日节日营销', title: '会员日 / 节日营销' },
  '/m5-referral': { breadcrumb: '营销中心 / 老带新', title: '老带新' },
  '/m5-writeoff': { breadcrumb: '营销中心 / 优惠券核销', title: '优惠券核销' },
  '/m5-assets': { breadcrumb: '营销中心 / 素材库', title: '素材库' },
  '/m5-dashboard': { breadcrumb: '营销中心 / 营销数据看板', title: '营销数据看板' },
  '/m5-settings': { breadcrumb: '营销中心 / 营销设置', title: '营销设置' },

  // M6 数据财务
  '/m6-ledger': { breadcrumb: '财务 / 收支流水', title: '收支流水' },
  '/m6-reconcile': { breadcrumb: '财务 / 对账中心', title: '对账中心' },
  '/m6-invoice': { breadcrumb: '财务 / 发票管理', title: '发票管理' },
  '/m6-settlement': { breadcrumb: '财务 / 分账结算', title: '分账结算' },
  '/m6-cost': { breadcrumb: '财务 / 成本分析', title: '成本分析' },
  '/m6-margin': { breadcrumb: '财务 / 毛利报表', title: '毛利报表' },
  '/m6-commission': { breadcrumb: '财务 / 咨询师提成', title: '咨询师提成' },
  '/m6-writeoff': { breadcrumb: '财务 / 划扣明细', title: '划扣明细' },
  '/m6-prepay': { breadcrumb: '财务 / 预收款监管', title: '预收款监管' },
  '/m6-card-balance': { breadcrumb: '财务 / 会员卡余额', title: '会员卡余额' },
  '/m6-abnormal': { breadcrumb: '财务 / 异常账务', title: '异常账务' },
  '/m6-tax': { breadcrumb: '财务 / 税务报表', title: '税务报表' },
  '/m6-cash-daily': { breadcrumb: '财务 / 资金日报', title: '资金日报' },
  '/m6-monthly': { breadcrumb: '财务 / 经营月报', title: '经营月报' },
  '/m6-budget': { breadcrumb: '财务 / 预算管控', title: '预算管控' },
  '/m6-settings': { breadcrumb: '财务 / 财务设置', title: '财务设置' },
  /* 集团管控 */
  '/m1': { breadcrumb: '工作台 / 经营概览', title: '经营概览' },
  '/m1-matrix': { breadcrumb: '管理后台 / 指标矩阵', title: '指标矩阵' },
  '/m1-compare': { breadcrumb: '管理后台 / 门店对标', title: '门店对标' },
  '/m1-tenant': { breadcrumb: '门店运营 / 门店主数据', title: '门店主数据' },
  '/m1-org': { breadcrumb: '管理后台 / 组织架构权限', title: '组织架构权限' },
  '/m1-rbac': { breadcrumb: '管理后台 / 字段级RBAC', title: '字段级RBAC' },
  '/m1-procurement': { breadcrumb: '门店运营 / 采购供应链', title: '采购供应链' },
  '/m1-brand': { breadcrumb: '管理后台 / 品牌品类', title: '品牌品类' },
  '/m1-audit-log': { breadcrumb: '管理后台 / 审计日志', title: '审计日志' },
  '/m1-report': { breadcrumb: '管理后台 / 报表中心', title: '报表中心' },
  '/m1-dispatch': { breadcrumb: '门店运营 / 调度中心', title: '调度中心' },
  '/m1-compliance': { breadcrumb: '管理后台 / 合规中心', title: '合规中心' },
  '/m1-region': { breadcrumb: '门店运营 / 区域管理', title: '区域管理' },
  '/m1-marketing': { breadcrumb: '营销中心 / 营销活动', title: '营销活动' },
  '/m1-screen': { breadcrumb: '管理后台 / 数据大屏', title: '数据大屏' },
  '/m1-settings': { breadcrumb: '管理后台 / 系统设置', title: '系统设置' },
  '/admin/dictionary': { breadcrumb: '管理后台 / 数据字典库', title: '数据字典库', subtitle: '全站状态枚举 · 类型映射 · 颜色定义 统一查阅' },
  '/admin/mp-settings': { breadcrumb: '管理后台 / 小程序与支付', title: '小程序与支付配置', subtitle: 'AppID / 微信支付密钥（服务端） · 小程序运行时公开配置' },
  '/m1-health': { breadcrumb: '管理后台 / 健康度巡检', title: '健康度巡检' },
  '/m1-target': { breadcrumb: '管理后台 / 目标管理', title: '目标管理' },
  '/m1-sop': { breadcrumb: '管理后台 / 标准作业SOP', title: '标准作业SOP' },

  // ===== Wave 5 · 四中台底座 =====
  '/admin/staff': { breadcrumb: '管理后台 / 员工管理', title: '员工管理' },
  '/admin/roles': { breadcrumb: '管理后台 / 角色管理', title: '角色管理' },
  '/admin/permissions': { breadcrumb: '管理后台 / 权限矩阵', title: '权限矩阵' },
  '/admin/org': { breadcrumb: '管理后台 / 组织架构', title: '组织架构' },
  '/data/collect': { breadcrumb: '管理后台 / 数据采集', title: '数据采集' },
  '/data/govern': { breadcrumb: '管理后台 / 数据治理', title: '数据治理' },
  '/data/tags': { breadcrumb: '管理后台 / 标签工厂', title: '标签工厂' },
  '/data/service': { breadcrumb: '管理后台 / 数据服务', title: '数据服务' },
  '/workorders': { breadcrumb: '管理后台 / 工单中心', title: '工单中心' },
  '/integrations': { breadcrumb: '管理后台 / 集成中心', title: '集成中心' },
  // T4 AI 中台底座
  '/ai/models': { breadcrumb: 'AI 中心 / 模型仓库', title: '模型仓库' },
  '/ai/compute': { breadcrumb: 'AI 中心 / 算力管理', title: '算力管理' },
  '/ai/features': { breadcrumb: 'AI 中心 / 特征平台', title: '特征平台' },
  '/ai/monitor': { breadcrumb: 'AI 中心 / 监控告警', title: '监控告警' },
  // Wave 6 · A1 AI 中心（频道首页 '/ai' 标题已在上方定义）
  '/ai/profile': { breadcrumb: 'AI 中心 / 客户画像', title: '客户画像引擎' },
  '/ai/repurchase': { breadcrumb: 'AI 中心 / 复购预测', title: '复购预测' },
  '/ai/sensitive': { breadcrumb: 'AI 中心 / 敏感词检测', title: '敏感词检测' },
  '/ai/daily-report': { breadcrumb: 'AI 中心 / 经营日报', title: 'AI 经营日报' },
  '/ai/scripts': { breadcrumb: 'AI 中心 / 智能话术', title: '智能话术' },
  '/ai/chatbot': { breadcrumb: 'AI 中心 / AI 客服', title: 'AI 客服' },
  '/ai/scheduling': { breadcrumb: 'AI 中心 / 智能排班', title: '智能排班' },
  '/ai/churn-model': { breadcrumb: 'AI 中心 / 流失预警', title: '流失预警模型' },
  '/ai/content': { breadcrumb: 'AI 中心 / 内容生成', title: '内容生成' },
  '/ai/knowledge': { breadcrumb: 'AI 中心 / 知识库', title: '知识库' },
  '/ai/govern': { breadcrumb: 'AI 中心 / 审批与评估', title: '审批与效果评估' },
  '/ai/privacy': { breadcrumb: 'AI 中心 / 隐私合规', title: '隐私合规' },
  '/ai/gateway': { breadcrumb: 'AI 中心 / 网关与日志', title: 'API 网关与日志' },
  '/ai/admin': { breadcrumb: 'AI 中心 / 平台管理', title: '平台管理' },
  // Wave 7 · G 通用（顶栏入口）
  '/search': { breadcrumb: '通用 / 全局搜索', title: '全局搜索' },
  '/help': { breadcrumb: '通用 / 帮助中心', title: '帮助中心' },
  '/notif-settings': { breadcrumb: '通用 / 消息设置', title: '消息设置' },
  '/theme': { breadcrumb: '通用 / 主题外观', title: '主题外观' },
  '/about': { breadcrumb: '通用 / 关于版本', title: '关于版本' },
  '/guide': { breadcrumb: '通用 / 新手引导', title: '新手引导' },
  '/profile': { breadcrumb: '通用 / 个人中心', title: '个人中心' },
}

/** 哨兵：表示需要登录态、但无具体权限码 */
export const AUTH_ONLY = '__AUTH__'

/** 动作型子页面（不在主导航出现，但需权限守卫）path → permission */
const ACTION_ROUTE_PERMISSION: Record<string, string> = {
  '/appointment/new': 'appointment:create',
  // 字典管理页（写页面）：与后端 /api/customer/dictionaries/manage 的 settings:view 门槛对齐；
  // 无 settings:view 者由路由守卫挡在 /no-auth，避免能进页面却被后端 403 打空白。
  '/admin/dictionary/manage': 'settings:view',
}

/** 动态路由前缀 → 所需权限（/customers/C-201、/customers/C-201/360 等；/m 前缀走独立会员体系不拦） */
const DYNAMIC_ROUTE_PERMISSION: { prefix: string; perm: string }[] = [
  { prefix: '/customers/', perm: 'customer:view' },
]

/** 登录即可访问的路径（G 通用页 + 业务域频道首页 + C 端壳外会员页） */
const AUTH_ONLY_PATHS = new Set<string>([
  '/search', '/help', '/guide', '/notif-settings', '/theme', '/about', '/profile',
  '/notifications',
  // 业务域聚合首页（模块网格内部按权限过滤）
  '/workbench', '/customer', '/store', '/marketing', '/finance', '/admin',
])

/**
 * 路径 → 所需权限（供路由守卫）。
 * - 返回具体权限码：按 RBAC 校验
 * - 返回 '__AUTH__'：仅校验登录态
 * - 返回 undefined：公共页（如 /no-auth / closed-loop）
 * 动态路由 /appointment/1 → /appointment/detail 同权。
 */
export function permissionForPath(path: string): string | undefined {
  if (ACTION_ROUTE_PERMISSION[path]) return ACTION_ROUTE_PERMISSION[path]
  if (AUTH_ONLY_PATHS.has(path)) return AUTH_ONLY
  if (PAGE_TITLES[path]) {
    const item = NAV_GROUPS.flatMap((g) => g.items).find((i) => i.to === path)
    if (item?.authOnly) return AUTH_ONLY
    if (item?.permission) return item.permission
    return undefined
  }
  // 动态路由兜底：/appointment/123 → /appointment/detail
  const dyn = path.replace(/\/\d+/, '/detail')
  if (dyn !== path) return permissionForPath(dyn)
  // 动态前缀路由
  const dyn2 = DYNAMIC_ROUTE_PERMISSION.find((d) => path.startsWith(d.prefix))
  if (dyn2) return dyn2.perm
  return undefined
}

/** 按权限过滤出可见菜单；只返回指定域下的分组 */
export function buildNavForDomain(
  can: (p: string) => boolean,
  domain: DomainKey,
): NavGroup[] {
  return NAV_GROUPS
    .filter((g) => g.domain === domain)
    .map((g) => ({
      ...g,
      items: g.items.filter((i) => {
        if (i.authOnly) return true // 登录即可见
        if (!i.permission) return true
        return can(i.permission)
      }),
    }))
    .filter((g) => g.items.length > 0)
}

/** 某业务域是否对当前用户可见（任一 anyPerm 命中即可） */
export function isDomainVisible(domain: DomainMeta, can: (p: string) => boolean): boolean {
  if (!domain.anyPerm) return true
  return domain.anyPerm.some((p) => can(p))
}

/** 顶栏通用入口（登录即可见） */
export function buildQuickItems(_can: (p: string) => boolean): QuickItem[] {
  return TOPBAR_QUICK
}
