/* ============================================================
 * 业务域「频道首页」配置
 * 每个域一个聚合首页：KPI 概览 + 模块矩阵（自动从 NAV_GROUPS 聚合）+ 关键信息/待办
 * 模块矩阵无需在此配置——由 buildNavForDomain 自动生成（加菜单即自动出现）。
 * 这里只配：概览 KPI、右侧关键信息 highlights、待办 todos、红线/提示条。
 * ============================================================ */
import type { DomainKey } from './nav'

export interface HomeKpi {
  label: string
  value: string
  icon: string
  tone?: 'text' | 'brand' | 'teal' | 'orange' | 'warning' | 'danger' | 'success' | 'purple' | 'blue'
  trend?: string
  trendUp?: boolean
  trendGood?: boolean
}

export interface HomeHighlight {
  label: string
  value: string
  /** to 可选；带 to 点击跳转 */
  to?: string
  tone?: 'text' | 'brand' | 'teal' | 'orange' | 'warning' | 'danger' | 'success' | 'purple' | 'blue'
}

export interface HomeTodo {
  title: string
  desc: string
  time: string
  type: 'approval' | 'alert' | 'review' | 'info'
  to: string
}

export interface DomainHomeMeta {
  title: string
  subtitle: string
  /** 模块矩阵卡片主题色（soft 底 + 主色） */
  theme: 'brand' | 'teal' | 'orange' | 'purple' | 'blue' | 'success' | 'warning'
  kpis: HomeKpi[]
  highlightsTitle: string
  highlights: HomeHighlight[]
  todosTitle: string
  todos: HomeTodo[]
  /** 底部提示条（可选） */
  notice?: string
}

export const DOMAIN_HOME: Record<Exclude<DomainKey, 'ai'>, DomainHomeMeta> = {
  workbench: {
    title: '工作台',
    subtitle: '预约 · 咨询 · 开单 · 收银 · 核销 · 病历 · 协同',
    theme: 'brand',
    kpis: [
      { label: '今日预约', value: '42', icon: 'calendar', tone: 'brand', trend: '+6', trendUp: true, trendGood: true },
      { label: '待接待', value: '8', icon: 'home', tone: 'orange' },
      { label: '今日收款', value: '¥38,640', icon: 'pos', tone: 'teal', trend: '+7%', trendUp: true, trendGood: true },
      { label: '今日核销', value: '126', icon: 'check-square', tone: 'purple' },
    ],
    highlightsTitle: '一线作业',
    highlights: [
      { label: '咨询工作台', value: '6 进行中', to: '/consultation', tone: 'brand' },
      { label: '收款收银', value: '今日开单', to: '/order', tone: 'teal' },
      { label: '划扣核销', value: '18 待扣', to: '/writeoff', tone: 'orange' },
      { label: '电子病历', value: '28 新建', to: '/emr', tone: 'blue' },
    ],
    todosTitle: '今日待办',
    todos: [
      { title: '退款审批待处理', desc: '会员 C-208 申请退卡余额 ¥1,280，待店长审批', time: '15 分钟前', type: 'approval', to: '/approval' },
      { title: '14:00 预约将到店', desc: '王女士预约「面部护理」，建议提前 10 分钟提醒', time: '30 分钟前', type: 'info', to: '/appointment' },
      { title: '18 笔项目待划扣', desc: '今日已服务项目中 18 笔尚未划扣核销，请跟进', time: '1 小时前', type: 'review', to: '/m2-writeoff-desk' },
      { title: '交班未确认', desc: '昨日夜班收银交接单待店长签字确认', time: '3 小时前', type: 'approval', to: '/handover' },
    ],
    notice: '闭环提示：预约 → 接待 → 咨询 → 开单 → 收银 → 核销 → 病历 → 回访，一线服务动线数据自动打通。',
  },

  customer: {
    title: '客户运营',
    subtitle: '会员资产 · 客情洞察 · 复购与流失',
    theme: 'teal',
    kpis: [
      { label: '会员总数', value: '3,248', icon: 'customer', tone: 'teal', trend: '+38', trendUp: true, trendGood: true },
      { label: '本月新增', value: '126', icon: 'user-check', tone: 'brand' },
      { label: '高流失风险', value: '47', icon: 'alert', tone: 'danger', trend: '+9', trendUp: true, trendGood: false },
      { label: '待回访', value: '63', icon: 'bell', tone: 'warning' },
    ],
    highlightsTitle: '客户资产',
    highlights: [
      { label: '会员列表', value: '3,248 人', to: '/customers', tone: 'teal' },
      { label: '客户图谱', value: '撞单消解', to: '/customer-graph', tone: 'blue' },
      { label: '卡项疗程', value: '持有', to: '/card-course', tone: 'brand' },
      { label: '投诉工单', value: '2 处理中', to: '/complaint', tone: 'danger' },
    ],
    todosTitle: '客户预警与跟进',
    todos: [
      { title: '高价值客户流失预警', desc: 'C-201 近 60 天未到店，消费力 A 级，建议主动召回', time: '20 分钟前', type: 'alert', to: '/ai/churn-model' },
      { title: '复购时机到达', desc: '12 位会员护理周期到期，可推送复购项目', time: '1 小时前', type: 'info', to: '/ai/repurchase' },
      { title: '投诉待跟进', desc: 'C-455 反馈服务态度问题，店长需 24h 内回复', time: '2 小时前', type: 'review', to: '/complaint' },
    ],
  },

  store: {
    title: '门店运营',
    subtitle: '商品价格 · 排班考勤 · 库存物料 · 绩效报表 · 管控主数据',
    theme: 'blue',
    kpis: [
      { label: '在库 SKU', value: '486', icon: 'package', tone: 'brand' },
      { label: '库存预警', value: '6', icon: 'alert', tone: 'danger' },
      { label: '今日出勤', value: '18/20', icon: 'calendar', tone: 'teal' },
      { label: '设备完好率', value: '96%', icon: 'settings', tone: 'blue', trend: '+1.2%', trendUp: true, trendGood: true },
    ],
    highlightsTitle: '店务管理',
    highlights: [
      { label: '库存耗材', value: '6 预警', to: '/m2-inventory', tone: 'danger' },
      { label: '排班考勤', value: '本周', to: '/m2-schedule', tone: 'blue' },
      { label: '价目表', value: '卡项/项目', to: '/m2-pricelist', tone: 'brand' },
      { label: '员工绩效', value: '本月', to: '/m2-performance', tone: 'teal' },
    ],
    todosTitle: '门店异常与待办',
    todos: [
      { title: '耗材库存低于安全线', desc: '「玻尿酸原液」剩余 8 支，低于安全库存 15 支', time: '40 分钟前', type: 'alert', to: '/m2-inventory' },
      { title: '设备待校准', desc: '3 号美容仪超校准周期，已安排维修中 1 台', time: '2 小时前', type: 'review', to: '/m2-equipment' },
      { title: '物料申领待审批', desc: '2 条门店物料申领单待区域经理审批', time: '3 小时前', type: 'approval', to: '/m2-requisition' },
      { title: '服务工单超 SLA', desc: '2 个维修工单处理超时，需跟进技师进度', time: '昨天', type: 'alert', to: '/m2-workorder' },
    ],
  },

  marketing: {
    title: '营销中心',
    subtitle: '活动 · 优惠券 · 渠道裂变 · 直播',
    theme: 'orange',
    kpis: [
      { label: '进行中活动', value: '8', icon: 'marketing', tone: 'orange' },
      { label: '优惠券核销率', value: '34%', icon: 'card', tone: 'brand', trend: '+5.2%', trendUp: true, trendGood: true },
      { label: '本周拉新', value: '152', icon: 'user-check', tone: 'teal' },
      { label: '投放 ROI', value: '3.8', icon: 'trend-up', tone: 'success' },
    ],
    highlightsTitle: '营销模块',
    highlights: [
      { label: '营销活动', value: '8 进行中', to: '/m1-marketing', tone: 'orange' },
      { label: '优惠券', value: '发放/核销', to: '/m5-coupons', tone: 'brand' },
      { label: '渠道裂变', value: '拉新', to: '/m5-channel', tone: 'teal' },
      { label: '直播管理', value: '排期', to: '/m5-live', tone: 'purple' },
    ],
    todosTitle: '营销动态',
    todos: [
      { title: '活动今日结束', desc: '「夏日焕新 8 折券」今晚 24:00 结束，已核 286 张', time: '1 小时前', type: 'info', to: '/m1-marketing' },
      { title: '渠道反洗客预警', desc: '渠道「美团-B」新客异常占比偏高，待判定', time: '3 小时前', type: 'alert', to: '/m5-channel' },
      { title: '素材待授权', desc: '3 条推送素材未完成门店授权，无法下发', time: '昨天', type: 'review', to: '/m5-assets' },
    ],
  },

  finance: {
    title: '财务',
    subtitle: '收支 · 结算 · 退款 · 储值卡',
    theme: 'success',
    kpis: [
      { label: '今日营收', value: '¥8.6万', icon: 'finance', tone: 'success', trend: '+7%', trendUp: true, trendGood: true },
      { label: '待结算', value: '¥12.3万', icon: 'order', tone: 'orange' },
      { label: '待退款', value: '4', icon: 'refund', tone: 'danger' },
      { label: '储值余额', value: '¥86万', icon: 'card', tone: 'brand' },
    ],
    highlightsTitle: '财务模块',
    highlights: [
      { label: '收支流水', value: '今日', to: '/m6-ledger', tone: 'success' },
      { label: '退款审核', value: '4 待审', to: '/refund', tone: 'danger' },
      { label: '对账中心', value: '日清', to: '/m6-reconcile', tone: 'blue' },
      { label: '储值卡', value: '余额/充值', to: '/m6-card-balance', tone: 'brand' },
    ],
    todosTitle: '财务待办',
    todos: [
      { title: '退款单待审批', desc: 'C-208 退卡余额 ¥1,280，超店长额度需区域审批', time: '20 分钟前', type: 'approval', to: '/refund' },
      { title: '昨日对账差异', desc: 'POS 流水与第三方支付差 ¥38，待账实核对', time: '2 小时前', type: 'alert', to: '/m6-reconcile' },
      { title: '月度结算待确认', desc: '7 月门店分账报表已生成，待财务复核', time: '昨天', type: 'review', to: '/m6-settlement' },
    ],
  },

  admin: {
    title: '管理后台',
    subtitle: '组织 · 权限 · 数据 · 工单 · 合规',
    theme: 'purple',
    kpis: [
      { label: '员工总数', value: '186', icon: 'org', tone: 'purple' },
      { label: '待处理工单', value: '7', icon: 'tool', tone: 'orange' },
      { label: '权限变更待审', value: '3', icon: 'shield', tone: 'warning' },
      { label: '合规检查', value: '98%', icon: 'check-square', tone: 'success' },
    ],
    highlightsTitle: '管理模块',
    highlights: [
      { label: '组织架构', value: 'T1', to: '/admin/org', tone: 'purple' },
      { label: '角色权限', value: 'RBAC', to: '/admin/roles', tone: 'blue' },
      { label: '工单中心', value: '7 待处理', to: '/workorders', tone: 'orange' },
      { label: '合规审计', value: '日志', to: '/m1-compliance', tone: 'success' },
    ],
    todosTitle: '管理待办',
    todos: [
      { title: '权限申请待审批', desc: '咨询师林微申请「客户导出」权限，待管理员审批', time: '30 分钟前', type: 'approval', to: '/admin/roles' },
      { title: '高敏操作审计告警', desc: '检测到 1 次非工作时间批量客户数据导出', time: '1 小时前', type: 'alert', to: '/m1-audit-log' },
      { title: '工单超时提醒', desc: '「打印机故障」工单已 26h 未响应，超 SLA', time: '3 小时前', type: 'review', to: '/workorders' },
    ],
    notice: '管理操作全程留痕：组织/权限/数据导出均进入审计日志，敏感操作二次确认。',
  },
}

/* ============================================================
 * 模块卡关键数据（参考 AI 能力矩阵「今日 X 次调用」）
 * key = 模块路由 to，value = 展示在卡片名称下方的一行关键指标
 * 未配置的模块回退显示所属分组名；设置/帮助/DEMO/个人中心等不配数据。
 * ============================================================ */
export const MODULE_STATS: Record<string, string> = {
  // —— 工作台 ——
  '/appointment': '今日 42 场预约',
  '/queue': '3 人候补中',
  '/reception': '8 位待接待',
  '/guest-reg': '5 条客情待补录',
  '/m1': '今日营收 ¥38,640',
  '/approval': '5 笔待审批',
  '/notifications': '12 条未读',
  // —— 客户运营 ——
  '/customers': '12,480 位会员',
  '/customer-graph': '2 组撞单待消解',
  '/followup': '18 位待回访',
  '/complaint': '3 单处理中',
  '/m3-io': '本周导入 246 条',
  '/card-course': '1,860 张有效卡',
  '/course-track': '96 个疗程进行中',
  '/contract': '42 份合同待续签',
  '/asset-transfer': '2 笔转移待审核',
  '/m3-levels': '6 个会员等级',
  '/m3-points-mall': '8 件好礼可兑换',
  '/m3-tags': '128 个客户标签',
  '/m3-journey': '5 条自动化旅程',
  '/m3-tasks': '23 个跟进待办',
  '/m3-care': '今日 14 位会员生日',
  '/m3-nps': '本月 NPS 72',
  '/m3-private': '企微好友 8,920',
  '/m3-segment': '16 个智能客群',
  '/m3-churn': '37 位流失风险',
  '/m3-referral': '本月 28 组转介绍',
  '/m3-risk': '黑名单 12 人',
  '/m3-insight': '4 份洞察报告',
  // —— 门店运营 ——
  '/consultation': '6 个咨询进行中',
  '/prescription': '今日开单 54 张',
  '/order': '今日收款 ¥38,640',
  '/writeoff': '今日核销 126 次',
  '/m2-writeoff-desk': '18 笔待划扣',
  '/m2-checkin': '已到店 31 位',
  '/m2-catalog': '96 个卡项在售',
  '/m2-pricelist': '214 个价目 SKU',
  '/emr': '今日新建病历 28 份',
  '/recall': '45 位复诊到期',
  '/handover': '1 份交接待签字',
  '/m2-schedule': '今日在岗 12 人',
  '/m2-inventory': '3 项低于安全线',
  '/m2-requisition': '4 单申领待审批',
  '/m2-wastage': '本月报损 ¥1,280',
  '/m2-rooms': '8 间房 · 3 间空闲',
  '/m2-equipment': '1 台设备待校准',
  '/m2-workorder': '2 个工单处理中',
  '/m2-daily': '昨日营收 ¥42,180',
  '/m2-weekly': '本周环比 +6.2%',
  '/m2-performance': '12 人在册考核',
  '/m2-inspection': '2 项巡店待整改',
  '/m2-acquisition': '3 场拓客进行中',
  '/m2-reactivate': '本月唤醒 86 位',
  '/m2-exception': '1 笔异常待处理',
  // —— 营销中心 ——
  '/m1-marketing': '4 场活动进行中',
  '/m5-coupons': '6 张券在投放',
  '/m5-writeoff': '今日核销 286 张',
  '/m5-push': '今日送达 3,420 人',
  '/m5-poster': '12 套裂变模板',
  '/m5-referral': '本月老带新 58 人',
  '/m5-calendar': '下个会员日 9/1',
  '/m5-live': '2 场直播预告中',
  '/m5-landing': '8 个落地页',
  '/m5-channel': '9 个投放渠道',
  '/m5-assets': '3 条素材待授权',
  '/m5-roi': '综合 ROI 3.8',
  '/m5-dashboard': '本月拉新 486 人',
  // —— 财务 ——
  '/refund': '3 笔退款待审批',
  '/card-cancel': '1 笔退卡待处理',
  '/m6-settlement': '7 月分账待复核',
  '/m6-reconcile': '昨日对账差异 ¥38',
  '/m6-prepay': '预收款余额 ¥86.4万',
  '/m6-ledger': '今日流水 128 笔',
  '/m6-writeoff': '今日划扣 ¥12,460',
  '/m6-card-balance': '卡余总额 ¥218万',
  '/m6-invoice': '6 张发票待开具',
  '/m6-abnormal': '2 笔异常账务',
  '/m6-tax': '8 月税务申报待办',
  '/m6-cash-daily': '昨日现金 ¥6,820',
  '/m6-cost': '本月成本率 31.2%',
  '/m6-margin': '综合毛利率 68.8%',
  '/m6-commission': '本月提成 ¥28,640',
  '/m6-monthly': '7 月经营报已生成',
  '/m6-budget': '预算执行 72%',
  // —— 管理后台 ——
  '/m1-matrix': '142 项指标',
  '/m1-compare': '6 家门店对标',
  '/m1-screen': '数据大屏投屏中',
  '/m1-target': 'Q3 目标完成 68%',
  '/m1-report': '36 张报表',
  '/m1-compliance': '2 项合规待整改',
  '/m1-audit-log': '今日审计 1,284 条',
  '/m1-health': '系统健康分 92',
  '/m1-sop': '48 个标准 SOP',
  '/m1-brand': '3 品牌 · 12 品类',
  '/admin/org': '68 名员工',
  '/admin/roles': '1 条权限申请待审',
  '/workorders': '2 个工单超 SLA',
  '/integrations': '8 个系统已对接',
  '/m1-tenant': '6 家门店',
  '/m1-region': '2 个区域',
  '/m1-procurement': '4 单采购在途',
  '/m1-dispatch': '1 个调度任务',
}
