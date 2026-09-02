// ============================================================
// 设置中心：业务可调参数的单一来源
// 两类参数：
//   - system  : 集团级，超管可改（双签阈值、号源释放、转介绍有效期、数据保留）
//   - store   : 门店级，店长可改（营业时间、候诊超时、跨店改派、提成）
// 注意：状态机合法迁移表、权限码、聚合字段属于"代码常量/结构"，不进设置中心。
// 后端就绪前，store 以 DEFAULT_SETTINGS + localStorage 兜底。
// ============================================================

/** 双签层级金额阈值（单位：元）。门店可在不低于集团下限内微调（见 allowStoreOverride）。 */
export interface DualSignThresholds {
  /** L1 单笔金额 ≥ 此值需一人签署 */
  l1: number
  /** L2 单笔金额 ≥ 此值需双签 */
  l2: number
  /** L3 单笔金额 ≥ 此值需三签/更高审批 */
  l3: number
  /** 退卡扣倒扣比例（已用疗程按原价倒扣的比例，0-1） */
  cardClawbackRate: number
}

/** 候诊/号源参数 */
export interface QueueSettings {
  /** 候诊超时（分钟），超时进候补 */
  waitingTimeoutMin: number
  /** 超时是否自动释放号源 */
  autoReleaseSlot: boolean
  /** 释放前宽限（分钟） */
  releaseGraceMin: number
  /** 单日号源提前生成天数 */
  slotPreloadDays: number
}

/** 转介绍/归属参数 */
export interface ReferralSettings {
  /** 转介绍归属有效期（天），到期可重新分配 */
  ownershipValidDays: number
  /** 是否要求被介绍客户确认/到店才生效 */
  requireConfirm: boolean
}

/** 数据/合规参数 */
export interface ComplianceSettings {
  /** 业务数据保留年限 */
  dataRetentionYears: number
  /** 客户敏感字段（手机号）脱敏是否默认开启 */
  phoneMaskByDefault: boolean
  /** 超管 impersonate 默认只读 */
  impersonateReadOnly: boolean
}

/** 品牌与基础信息 */
export interface BrandSettings {
  /** 品牌名称 */
  name: string
  /** 品牌主色（hex，用于登录/主题强调） */
  color: string
  /** 系统对外版本号（展示用，与 AppSettings.version 迁移版本区分） */
  versionLabel: string
}

/** 运行环境：生产 / 预发布 / 开发；切换需 T3-01 审批 */
export type EnvType = 'PROD' | 'STAGING' | 'DEV'

/** 通知渠道开关 */
export interface NotificationSettings {
  /** 审批待办站内信 */
  approvalInbox: boolean
  /** 退款/退卡结果短信 */
  refundSms: boolean
  /** 巡店/合规告警企业微信 */
  alertWecom: boolean
  /** 经营周报邮件 */
  weeklyEmail: boolean
}

/** 安全策略 */
export interface SecuritySettings {
  /** 登录会话超时（分钟） */
  sessionTimeoutMin: number
  /** 是否强制双因素认证 */
  require2FA: boolean
  /** 敏感操作二次确认（退款/退卡/环境切换等） */
  sensitiveConfirm: boolean
  /** 密码最短长度 */
  passwordMinLen: number
}

/** 集团级系统设置 */
export interface SystemSettings {
  brand: BrandSettings
  env: EnvType
  notifications: NotificationSettings
  security: SecuritySettings
  dualSign: DualSignThresholds
  queue: QueueSettings
  referral: ReferralSettings
  compliance: ComplianceSettings
}

/** 设置变更日志条目（审计用，append-only） */
export interface SettingsChangeLog {
  id: string
  time: string
  operator: string
  /** 变更项分组 */
  group: string
  /** 变更项名称 */
  field: string
  /** 旧值 → 新值（展示文本） */
  change: string
  /** 风险级别，决定日志标记色 */
  risk: 'LOW' | 'MEDIUM' | 'HIGH'
}

/** 门店营业时段 */
export interface BusinessHours {
  open: string // "09:00"
  close: string // "21:00"
  weekdayOff: number[] // 每周休，0=周日
}

/** 门店级设置 */
export interface StoreSettings {
  businessHours: BusinessHours
  /** 分诊是否允许跨门店改派 */
  allowCrossStoreTriage: boolean
  /** 到店超时（分钟），迟到标记 */
  arrivalLateMin: number
  /** 咨询后跟进默认间隔（天） */
  consultFollowupDays: number
  /** 门店提成比例（咨询师，0-1）——示例，后续可拆角色 */
  consultantCommissionRate: number
}

export interface AppSettings {
  system: SystemSettings
  store: StoreSettings
  /** 配置版本号，用于后端下发时做迁移 */
  version: number
}

/** 应用设置 + 变更日志（前端持久化用） */
export interface SettingsState {
  settings: AppSettings
  /** 待保存的脏标记：存在已修改但未点"保存配置"的项 */
  dirty: boolean
  /** 变更日志（append-only） */
  changeLog: SettingsChangeLog[]
}

export const DEFAULT_SETTINGS: AppSettings = {
  version: 2,
  system: {
    brand: {
      name: '颜研 YANRESEARCH',
      color: '#FF6B9D',
      versionLabel: 'v3.2.1',
    },
    env: 'PROD',
    notifications: {
      approvalInbox: true,
      refundSms: true,
      alertWecom: true,
      weeklyEmail: false,
    },
    security: {
      sessionTimeoutMin: 30,
      require2FA: false,
      sensitiveConfirm: true,
      passwordMinLen: 8,
    },
    dualSign: {
      l1: 1000,
      l2: 5000,
      l3: 20000,
      cardClawbackRate: 0.8,
    },
    queue: {
      waitingTimeoutMin: 30,
      autoReleaseSlot: true,
      releaseGraceMin: 10,
      slotPreloadDays: 7,
    },
    referral: {
      ownershipValidDays: 90,
      requireConfirm: true,
    },
    compliance: {
      dataRetentionYears: 5,
      phoneMaskByDefault: true,
      impersonateReadOnly: true,
    },
  },
  store: {
    businessHours: { open: '09:00', close: '21:00', weekdayOff: [] },
    allowCrossStoreTriage: false,
    arrivalLateMin: 15,
    consultFollowupDays: 3,
    consultantCommissionRate: 0.08,
  },
}

/** 根据单笔金额判定所需签署层级（与 business-flows §2.7 双签状态机对应） */
export function signTierForAmount(amount: number, t: DualSignThresholds): 'L1' | 'L2' | 'L3' {
  if (amount >= t.l3) return 'L3'
  if (amount >= t.l2) return 'L2'
  if (amount >= t.l1) return 'L1'
  return 'L1' // 未达阈值仍走 L1 基础签署
}
