// ============================================================
// 全站数据字典库
// 统一管理所有状态、类型、枚举的映射关系
// 前后端对齐，避免硬编码散落各处
// ============================================================

// ===== 客户相关 =====
export const CUSTOMER_STATUS = {
  ACTIVE: { value: 'ACTIVE', label: '活跃', color: 'success' },
  INACTIVE: { value: 'INACTIVE', label: '不活跃', color: 'default' },
  CHURNED: { value: 'CHURNED', label: '已流失', color: 'danger' },
  POTENTIAL: { value: 'POTENTIAL', label: '潜在客户', color: 'info' },
} as const

export const CUSTOMER_LEVEL = {
  NORMAL: { value: 'NORMAL', label: '普通会员', color: 'default' },
  SILVER: { value: 'SILVER', label: '银卡会员', color: 'info' },
  GOLD: { value: 'GOLD', label: '金卡会员', color: 'warning' },
  PLATINUM: { value: 'PLATINUM', label: '铂金会员', color: 'brand' },
  DIAMOND: { value: 'DIAMOND', label: '钻石会员', color: 'danger' },
} as const

export const CUSTOMER_SOURCE = {
  WALK_IN: { value: 'WALK_IN', label: '到店' },
  REFERRAL: { value: 'REFERRAL', label: '老客推荐' },
  XIAOHONGSHU: { value: 'XIAOHONGSHU', label: '小红书' },
  WECHAT: { value: 'WECHAT', label: '微信' },
  DOUYIN: { value: 'DOUYIN', label: '抖音' },
  MEITUAN: { value: 'MEITUAN', label: '美团' },
  OTHER: { value: 'OTHER', label: '其他' },
} as const

// ===== 预约相关 =====
export const APPOINTMENT_STATUS = {
  NEW: { value: 'NEW', label: '新预约', color: 'info' },
  PENDING: { value: 'PENDING', label: '待确认', color: 'warning' },
  CONFIRMED: { value: 'CONFIRMED', label: '已确认', color: 'info' },
  ARRIVED: { value: 'ARRIVED', label: '已到店', color: 'success' },
  NO_SHOW: { value: 'NO_SHOW', label: '未到店', color: 'danger' },
  CANCELLED: { value: 'CANCELLED', label: '已取消', color: 'default' },
  COMPLETED: { value: 'COMPLETED', label: '已完成', color: 'success' },
} as const

// ===== 订单相关 =====
export const ORDER_STATUS = {
  PENDING_PAY: { value: 'PENDING_PAY', label: '待支付', color: 'warning' },
  PAID: { value: 'PAID', label: '已支付', color: 'info' },
  PARTIAL_WRITEOFF: { value: 'PARTIAL_WRITEOFF', label: '部分核销', color: 'info' },
  ALL_WRITEOFF: { value: 'ALL_WRITEOFF', label: '已核销', color: 'success' },
  REFUNDING: { value: 'REFUNDING', label: '退款中', color: 'warning' },
  REFUNDED: { value: 'REFUNDED', label: '已退款', color: 'danger' },
  CANCELLED: { value: 'CANCELLED', label: '已取消', color: 'default' },
} as const

export const PAY_METHOD = {
  CASH: { value: 'CASH', label: '现金' },
  WECHAT: { value: 'WECHAT', label: '微信支付' },
  ALIPAY: { value: 'ALIPAY', label: '支付宝' },
  CARD: { value: 'CARD', label: '会员卡' },
  CREDIT: { value: 'CREDIT', label: '信用卡' },
} as const

// ===== 面诊相关 =====
export const CONSULTATION_STATUS = {
  PENDING_REVIEW: { value: 'PENDING_REVIEW', label: '待审核', color: 'warning' },
  APPROVED: { value: 'APPROVED', label: '已通过', color: 'info' },
  READY_PAY: { value: 'READY_PAY', label: '待支付', color: 'warning' },
  PAID: { value: 'PAID', label: '已支付', color: 'success' },
  TREATING: { value: 'TREATING', label: '治疗中', color: 'info' },
  DONE: { value: 'DONE', label: '已完成', color: 'success' },
  CANCELLED: { value: 'CANCELLED', label: '已取消', color: 'default' },
} as const

// ===== 库存相关 =====
export const INVENTORY_STATUS = {
  NORMAL: { value: 'NORMAL', label: '正常', color: 'success' },
  LOW: { value: 'LOW', label: '库存不足', color: 'warning' },
  OUT: { value: 'OUT', label: '缺货', color: 'danger' },
  EXPIRING: { value: 'EXPIRING', label: '即将过期', color: 'warning' },
  EXPIRED: { value: 'EXPIRED', label: '已过期', color: 'danger' },
} as const

// ===== 通知相关 =====
export const NOTIFICATION_CATEGORY = {
  APPROVAL: { value: 'APPROVAL', label: '审批待办', icon: 'check-square' },
  CUSTOMER: { value: 'CUSTOMER', label: '客户提醒', icon: 'customer' },
  INVENTORY: { value: 'INVENTORY', label: '库存预警', icon: 'box' },
  MARKETING: { value: 'MARKETING', label: '营销任务', icon: 'marketing' },
  SYSTEM: { value: 'SYSTEM', label: '系统公告', icon: 'settings' },
} as const

export const NOTIFICATION_LEVEL = {
  INFO: { value: 'INFO', label: '通知', color: 'info' },
  WARNING: { value: 'WARNING', label: '提醒', color: 'warning' },
  URGENT: { value: 'URGENT', label: '紧急', color: 'danger' },
} as const

// ===== 营销相关 =====
export const COUPON_STATUS = {
  DRAFT: { value: 'DRAFT', label: '草稿', color: 'default' },
  ACTIVE: { value: 'ACTIVE', label: '进行中', color: 'success' },
  PAUSED: { value: 'PAUSED', label: '已暂停', color: 'warning' },
  ENDED: { value: 'ENDED', label: '已结束', color: 'default' },
} as const

export const PUSH_CHANNEL = {
  SMS: { value: 'SMS', label: '短信' },
  WECHAT: { value: 'WECHAT', label: '企微/微信' },
  EMAIL: { value: 'EMAIL', label: '邮件' },
  INBOX: { value: 'INBOX', label: '站内信' },
} as const

// ===== 财务相关 =====
export const SETTLEMENT_STATUS = {
  PENDING: { value: 'PENDING', label: '待结算', color: 'warning' },
  SETTLED: { value: 'SETTLED', label: '已结算', color: 'success' },
  FAILED: { value: 'FAILED', label: '结算失败', color: 'danger' },
} as const

export const REFUND_STATUS = {
  PENDING_REVIEW: { value: 'PENDING_REVIEW', label: '待审核', color: 'warning' },
  PENDING_FINANCE: { value: 'PENDING_FINANCE', label: '待财务复核', color: 'info' },
  REFUNDED: { value: 'REFUNDED', label: '已退款', color: 'success' },
  REJECTED: { value: 'REJECTED', label: '已驳回', color: 'danger' },
} as const

export const REFUND_CHANNEL = {
  ORIGINAL: { value: 'ORIGINAL', label: '原路退回' },
  CASH: { value: 'CASH', label: '现金' },
  TRANSFER: { value: 'TRANSFER', label: '转账' },
} as const

export const RECONCILE_STATUS = {
  MATCHED: { value: 'MATCHED', label: '已平', color: 'success' },
  PENDING: { value: 'PENDING', label: '待对账', color: 'warning' },
  LONG: { value: 'LONG', label: '长款', color: 'info' },
  SHORT: { value: 'SHORT', label: '短款', color: 'danger' },
  REVERSED: { value: 'REVERSED', label: '冲正', color: 'primary' },
} as const

// ===== 核销/划扣相关 =====
export const WRITEOFF_RECORD_STATUS = {
  PENDING: { value: 'PENDING', label: '待核销', color: 'warning' },
  DONE: { value: 'DONE', label: '已核销', color: 'success' },
  ABNORMAL: { value: 'ABNORMAL', label: '异常', color: 'danger' },
  VOID: { value: 'VOID', label: '已作废', color: 'default' },
} as const

export const WRITEOFF_DESK_STATUS = {
  PENDING: { value: 'PENDING', label: '待执行', color: 'warning' },
  DONE: { value: 'DONE', label: '已划扣', color: 'success' },
  EXCEPTION: { value: 'EXCEPTION', label: '异常', color: 'danger' },
} as const

export const CHECKIN_STATUS = {
  DONE: { value: 'DONE', label: '已核销', color: 'success' },
  PENDING: { value: 'PENDING', label: '待确认', color: 'warning' },
  EXCEPTION: { value: 'EXCEPTION', label: '异常', color: 'danger' },
} as const

// ===== 客诉相关 =====
export const COMPLAINT_STATUS = {
  PENDING_ACCEPT: { value: 'PENDING_ACCEPT', label: '待受理', color: 'danger' },
  PROCESSING: { value: 'PROCESSING', label: '处理中', color: 'warning' },
  PENDING_REVIEW: { value: 'PENDING_REVIEW', label: '待结案审批', color: 'info' },
  CLOSED: { value: 'CLOSED', label: '已结案', color: 'success' },
  REJECTED: { value: 'REJECTED', label: '已驳回', color: 'danger' },
} as const

export const COMPLAINT_SEVERITY = {
  LOW: { value: 'LOW', label: '低', color: 'default' },
  MEDIUM: { value: 'MEDIUM', label: '中', color: 'warning' },
  HIGH: { value: 'HIGH', label: '高', color: 'danger' },
} as const

export const COMPLAINT_SOURCE = {
  STORE: { value: 'STORE', label: '到店' },
  PHONE: { value: 'PHONE', label: '电话' },
  ONLINE: { value: 'ONLINE', label: '线上' },
  THIRD_PARTY: { value: 'THIRD_PARTY', label: '第三方平台' },
} as const

export const COMPLAINT_CATEGORY = {
  SERVICE: { value: 'SERVICE', label: '服务态度' },
  MEDICAL: { value: 'MEDICAL', label: '医疗风险' },
  BILLING: { value: 'BILLING', label: '收费争议' },
  OUTCOME: { value: 'OUTCOME', label: '效果争议' },
  OTHER: { value: 'OTHER', label: '其他' },
} as const

// ===== 客户运营（流失/回访/关怀/拓客）=====
export const CHURN_STATUS = {
  PENDING: { value: 'PENDING', label: '待干预', color: 'warning' },
  INTERVENING: { value: 'INTERVENING', label: '干预中', color: 'primary' },
  RECOVERED: { value: 'RECOVERED', label: '已挽回', color: 'success' },
  LOST: { value: 'LOST', label: '已流失', color: 'danger' },
} as const

export const EXCEPTION_STATUS = {
  PENDING: { value: 'PENDING', label: '待处理', color: 'danger' },
  PROCESSING: { value: 'PROCESSING', label: '处理中', color: 'warning' },
  CLOSED: { value: 'CLOSED', label: '已闭环', color: 'success' },
} as const

export const CARE_STATUS = {
  PENDING: { value: 'PENDING', label: '待发送', color: 'warning' },
  SENT: { value: 'SENT', label: '已发送', color: 'primary' },
  REACHED: { value: 'REACHED', label: '已触达', color: 'success' },
} as const

export const ACQUISITION_STATUS = {
  ONGOING: { value: 'ONGOING', label: '进行中', color: 'primary' },
  ENDED: { value: 'ENDED', label: '已结束', color: 'success' },
  DRAFT: { value: 'DRAFT', label: '草稿', color: 'default' },
} as const

// ===== 审批/转移 =====
export const APPROVAL_STATUS = {
  PENDING: { value: 'PENDING', label: '待审批', color: 'warning' },
  APPROVED: { value: 'APPROVED', label: '已通过', color: 'success' },
  REJECTED: { value: 'REJECTED', label: '已驳回', color: 'danger' },
  TRANSFERRED: { value: 'TRANSFERRED', label: '已转交', color: 'info' },
} as const

export const TRANSFER_STATUS = {
  PENDING_REVIEW: { value: 'PENDING_REVIEW', label: '待审批', color: 'warning' },
  PENDING_FINANCE: { value: 'PENDING_FINANCE', label: '待执行', color: 'info' },
  TRANSFERRED: { value: 'TRANSFERRED', label: '已转移', color: 'success' },
  REJECTED: { value: 'REJECTED', label: '已驳回', color: 'danger' },
} as const

export const TRANSFER_ASSET_TYPE = {
  CASH: { value: 'CASH', label: '储值余额' },
  TIMES: { value: 'TIMES', label: '疗程次数' },
} as const

// ===== 积分商城 =====
export const PRODUCT_STATUS = {
  ON_SALE: { value: 'ON_SALE', label: '在售', color: 'success' },
  LOW_STOCK: { value: 'LOW_STOCK', label: '低库存', color: 'warning' },
  OFF_SHELF: { value: 'OFF_SHELF', label: '已下架', color: 'default' },
  PENDING: { value: 'PENDING', label: '审核中', color: 'primary' },
} as const

export const REDEMPTION_STATUS = {
  PENDING: { value: 'PENDING', label: '待审核', color: 'warning' },
  APPROVED: { value: 'APPROVED', label: '已通过', color: 'success' },
  REJECTED: { value: 'REJECTED', label: '已驳回', color: 'danger' },
  FULFILLED: { value: 'FULFILLED', label: '已发放', color: 'info' },
} as const

// ===== 巡检/回访 =====
export const INSPECTION_STATUS = {
  PENDING: { value: 'PENDING', label: '待整改', color: 'warning' },
  IN_PROGRESS: { value: 'IN_PROGRESS', label: '整改中', color: 'primary' },
  DONE: { value: 'DONE', label: '已完成', color: 'success' },
} as const

export const FOLLOW_TASK_STATUS = {
  PENDING: { value: 'PENDING', label: '待跟进', color: 'primary' },
  DONE: { value: 'DONE', label: '已完成', color: 'success' },
  OVERDUE: { value: 'OVERDUE', label: '已逾期', color: 'danger' },
} as const

export const FOLLOWUP_METHOD = {
  PHONE: { value: 'PHONE', label: '电话' },
  WECHAT: { value: 'WECHAT', label: '微信' },
  IN_STORE: { value: 'IN_STORE', label: '到店' },
} as const

// ===== 帮助中心 =====
export const HELP_TYPE = {
  DOC: { value: 'DOC', label: '文档', color: 'primary' },
  VIDEO: { value: 'VIDEO', label: '视频', color: 'info' },
  EXAM: { value: 'EXAM', label: '考核', color: 'warning' },
} as const

// ===== 合同相关 =====
export const CONTRACT_STATUS = {
  DRAFT: { value: 'DRAFT', label: '草稿', color: 'default' },
  PENDING_SIGN: { value: 'PENDING_SIGN', label: '待签署', color: 'warning' },
  ACTIVE: { value: 'ACTIVE', label: '生效中', color: 'success' },
  EXPIRED: { value: 'EXPIRED', label: '已过期', color: 'info' },
  TERMINATED: { value: 'TERMINATED', label: '已终止', color: 'danger' },
  COMPLETED: { value: 'COMPLETED', label: '已完成', color: 'success' },
} as const

// ===== 病历相关 =====
export const EMR_STATUS = {
  DRAFT: { value: 'DRAFT', label: '草稿', color: 'info' },
  SIGNED: { value: 'SIGNED', label: '已签名', color: 'success' },
  ARCHIVED: { value: 'ARCHIVED', label: '已归档', color: 'warning' },
} as const

// ===== 沉睡唤醒 =====
export const REACTIVATE_STATUS = {
  PENDING: { value: 'PENDING', label: '待触达', color: 'warning' },
  ASSIGNED: { value: 'ASSIGNED', label: '已分配', color: 'info' },
  CONTACTED: { value: 'CONTACTED', label: '已联系', color: 'primary' },
  INTERESTED: { value: 'INTERESTED', label: '有意向', color: 'info' },
  RECOVERED: { value: 'RECOVERED', label: '已到店', color: 'success' },
  VISITED: { value: 'VISITED', label: '已到访', color: 'success' },
} as const

// ===== 转介绍 =====
export const REFERRAL_STATUS = {
  PENDING: { value: 'PENDING', label: '待确认', color: 'warning' },
  CONFIRMED: { value: 'CONFIRMED', label: '已确认', color: 'info' },
  ACCEPTED: { value: 'ACCEPTED', label: '已接受', color: 'primary' },
  CONVERTED: { value: 'CONVERTED', label: '已转化', color: 'success' },
  REJECTED: { value: 'REJECTED', label: '已拒绝', color: 'danger' },
  VISITED: { value: 'VISITED', label: '已到访', color: 'success' },
  DEAL: { value: 'DEAL', label: '已成交', color: 'success' },
} as const

// ===== 工单相关 =====
export const WORK_ORDER_STATUS = {
  PENDING: { value: 'PENDING', label: '待处理', color: 'warning' },
  IN_PROGRESS: { value: 'IN_PROGRESS', label: '处理中', color: 'primary' },
  RESOLVED: { value: 'RESOLVED', label: '已解决', color: 'success' },
  DONE: { value: 'DONE', label: '已完成', color: 'success' },
  CLOSED: { value: 'CLOSED', label: '已关闭', color: 'danger' },
  ESCALATED: { value: 'ESCALATED', label: '已升级', color: 'danger' },
} as const

export const WORK_ORDER_PRIORITY = {
  HIGH: { value: 'HIGH', label: '高' },
  MEDIUM: { value: 'MEDIUM', label: '中' },
  LOW: { value: 'LOW', label: '低' },
} as const

// ===== 类型别名（兼容旧代码） =====
export type RefundChannel = 'ORIGINAL' | 'CASH' | 'TRANSFER'
export type FollowupMethod = 'PHONE' | 'WECHAT' | 'IN_STORE'
export type TransferAssetType = 'CASH' | 'TIMES'
export type WorkOrderPriority = 'HIGH' | 'MEDIUM' | 'LOW'
export type WorkOrderStatus = keyof typeof WORK_ORDER_STATUS
export type ContractStatus = keyof typeof CONTRACT_STATUS
export type ReferralStatus = keyof typeof REFERRAL_STATUS
export type ReactivateStatus = keyof typeof REACTIVATE_STATUS
export type ApptStatus = keyof typeof APPOINTMENT_STATUS
export type OrderStatus = keyof typeof ORDER_STATUS
export type ComplaintStatus = keyof typeof COMPLAINT_STATUS
export type RectifyStatus = keyof typeof RECTIFY_STATUS
export type RecallStatus = keyof typeof RECALL_STATUS
export type RewardStatus = keyof typeof REWARD_STATUS
export type CustomerTier = keyof typeof CUSTOMER_TIER

// ===== 回访相关 =====
export const FOLLOWUP_STATUS = {
  PENDING: { value: 'PENDING', label: '待回访', color: 'warning' },
  DONE: { value: 'DONE', label: '已完成', color: 'success' },
  SKIPPED: { value: 'SKIPPED', label: '无需回访', color: 'info' },
} as const

// ===== 整改相关（巡店检查子状态） =====
export const RECTIFY_STATUS = {
  OPEN: { value: 'OPEN', label: '待整改', color: 'warning' },
  DOING: { value: 'DOING', label: '整改中', color: 'primary' },
  DONE: { value: 'DONE', label: '已完成', color: 'success' },
} as const

// ===== 奖励相关（转介绍） =====
export const REWARD_STATUS = {
  PENDING: { value: 'PENDING', label: '待发放', color: 'warning' },
  PAID: { value: 'PAID', label: '已发放', color: 'success' },
} as const

// ===== 恢复状态（术后回访） =====
export const RECOVERY_STATUS = {
  GOOD: { value: 'GOOD', label: '恢复良好', color: 'success' },
  NORMAL: { value: 'NORMAL', label: '恢复一般', color: 'warning' },
  POOR: { value: 'POOR', label: '恢复不佳', color: 'danger' },
} as const

// ===== 风险等级（流失/旅程） =====
export const RISK_LEVEL = {
  HIGH: { value: 'HIGH', label: '高风险', color: 'danger' },
  MEDIUM: { value: 'MEDIUM', label: '中风险', color: 'warning' },
  LOW: { value: 'LOW', label: '低风险', color: 'success' },
} as const

// ===== 交接班状态 =====
export const HANDOVER_STATUS = {
  DRAFT: { value: 'DRAFT', label: '草稿', color: 'default' },
  SUBMITTED: { value: 'SUBMITTED', label: '待确认', color: 'warning' },
  CONFIRMED: { value: 'CONFIRMED', label: '已交接', color: 'success' },
} as const

// ===== 复诊提醒状态 =====
export const RECALL_STATUS = {
  PENDING: { value: 'PENDING', label: '待提醒', color: 'warning' },
  NOTIFIED: { value: 'NOTIFIED', label: '已提醒', color: 'info' },
  CONFIRMED: { value: 'CONFIRMED', label: '已确认', color: 'success' },
  BOOKED: { value: 'BOOKED', label: '已预约', color: 'success' },
  SKIPPED: { value: 'SKIPPED', label: '已跳过', color: 'default' },
} as const

// ===== AI 能力状态 =====
export const AI_CAPABILITY_STATUS = {
  ONLINE: { value: 'ONLINE', label: '在线', color: 'success' },
  BETA: { value: 'BETA', label: '灰度', color: 'info' },
  COMING: { value: 'COMING', label: '敬请期待', color: 'default' },
} as const

// ===== 客户等级（业务分类 NEW/C/B/A/KA） =====
export const CUSTOMER_TIER = {
  NEW: { value: 'NEW', label: '新客', color: 'default' },
  C: { value: 'C', label: 'C级', color: 'info' },
  B: { value: 'B', label: 'B级', color: 'warning' },
  A: { value: 'A', label: 'A级', color: 'primary' },
  KA: { value: 'KA', label: '黑钻', color: 'danger' },
} as const

// ===== 工具函数 =====
/** 从字典条目中取 label */
export function getStatusLabel(statusMap: Record<string, { label: string }>, value: string): string {
  return statusMap[value]?.label || value
}

/** 从字典条目中取 color */
export function getStatusColor(statusMap: Record<string, { color: string }>, value: string): string {
  return statusMap[value]?.color || 'default'
}

/**
 * 通用 pill 适配器：将字典条目转为 CStatusPill 的 { status, text }
 * 页面可直接 `pill(DICT[value])` 使用
 */
export type PillStatus = 'success' | 'warning' | 'danger' | 'info' | 'default' | 'primary' | 'disabled' | 'draft'

export function dictPill(entry: { label: string; color: string } | undefined): { status: PillStatus; text: string } {
  if (!entry) return { status: 'default' as PillStatus, text: '—' }
  return { status: entry.color as PillStatus, text: entry.label }
}
