// 领域类型（产品架构基线落地版）
// 对齐 docs/domain-model.md。仅放"跨页面流转"的核心聚合，避免与 api 层 DTO 重复。

/** 数据域：决定能看到多大范围的数据（SELF<STORE<BRAND<REGION<GROUP） */
export type DataScope = 'SELF' | 'STORE' | 'BRAND' | 'REGION' | 'GROUP'

/** 角色 key（对齐 docs/permission-matrix.md §2） */
export type Role =
  | 'SUPER_ADMIN'
  | 'REGION_MGR'
  | 'STORE_MGR'
  | 'CONSULTANT'
  | 'DOCTOR'
  | 'FRONT_DESK'
  | 'OPERATOR'
  | 'FINANCE'

/** 员工（角色载体，一人可多角色） */
export interface Staff {
  id: string
  name: string
  avatarLetter: string
  jobTitle: string
  storeId: string
  roles: Role[]
  dataScope: DataScope
}

/** 客户（全生命周期主语，CustomerID 贯穿全链路） */
export interface Customer {
  id: string
  name: string
  avatarLetter: string
  phoneMask: string
  phone?: string // 明文手机号，仅持 customer:phone:decrypt 可见
  channel: 'ONLINE_APPT' | 'WALK_IN' | 'REFERRAL' | 'MARKETING'
  level: 'NEW' | 'C' | 'B' | 'A' | 'KA'
  tags: string[]
  storeId: string
  ownerStaffId?: string // 归属咨询师（SELF 数据域依据）
  referralByCustomerId?: string // 转介绍人（经审核确认后生效）
  referralExpiresAt?: string // 转介绍归属有效期
  mergedFrom?: string[] // 被合并进来的旧 CustomerID（留痕可追溯）
  masterId?: string // 非空=本条已被合并到 masterId（作废）

  // —— M3 画像 / 360 扩展字段（可选，未填则不展示） ——
  memberNo?: string
  registerDate?: string
  totalSpend?: number
  ltv?: number
  visitCount?: number
  dormantDays?: number
  cardBalance?: number
  points?: number
  /** 5 维价值分 0-100：最近消费 R / 频次 F / 金额 M / 忠诚 / 活跃 */
  rfm?: { r: number; f: number; m: number; loyalty: number; active: number }
  /** 价值分 0-100（头像圆环展示） */
  valueScore?: number
  lifecycleStage?: string
  lifecycleStep?: number // 当前在生命周期的第几步（0 起）
  preferences?: { label: string; weight: number }[]
  allergies?: string[]
  rights?: string[]
  nextAppointment?: { date: string; project: string }
  transactions?: CustomerTransaction[]
  serviceTrack?: ServiceTrackItem[]
  cards?: CustomerCard[]
  /** 对比照（旧占位，纯文字；真实照片见 customer store photos） */
  comparePhotos?: { label: string; date: string; tone: 'before' | 'after' }[]
  /** 结构化面诊 / 皮肤检测报告（VISIA 指标 + 肤质分型 + 诉求），见 SkinReport */
  skinReports?: SkinReport[]
  /** 智能提醒 / 待办 */
  reminders?: { level: 'danger' | 'warning' | 'success'; text: string }[]
  /** 最近一次消费日期，用于合并去重列表展示 */
  lastVisitAt?: string
}

/** 消费记录（M3-03 360 画像 Tab） */
export interface CustomerTransaction {
  id: string
  customerId?: string
  project: string
  store: string
  operator: string
  amount: number // 正数=充值，负数=消费
  at: string
  payMethod?: string
  orderNo?: string
}

/** 服务轨迹时间轴项 */
export interface ServiceTrackItem {
  id: string
  customerId?: string
  date: string
  title: string
  detail: string
  operator: string
  tone?: 'brand' | 'teal' | 'orange' | 'purple'
}

/** 卡项余额（M3-03 360） */
export interface CustomerCard {
  id: string
  customerId?: string
  name: string
  remaining: string
  total: string
  expiresAt: string
  status: 'active' | 'expiring' | 'expired'
}

/** 到店 / 排队 */
export type ArrivalStatus = 'WAITING' | 'TRIAGED' | 'CALLED' | 'DONE' | 'LEFT'

export interface Arrival {
  id: string
  customerId: string
  storeId: string
  arrivedAt: string
  channel: Customer['channel']
  queueNo: number
  status: ArrivalStatus
}

/** 分诊（可改派） */
export type TriageType = 'CONSULT' | 'MEDICAL' | 'SERVICE'

export interface Triage {
  id: string
  arrivalId: string
  customerId: string
  type: TriageType
  assignedTo: string // staffId
  forwardedTo?: string
  note: string
  editedBy?: string
  editedAt?: string
}

/**
 * 咨询单状态机（咨询 / 医师双工作台闭环）：
 * 【咨询工作台】PENDING 待咨询 → ACTIVE 咨询中（咨询师快捷开单）
 *   → PENDING_REVIEW 待医生审核
 * 【医师工作台】PENDING_REVIEW → APPROVED 审核通过·待写病历
 *   → 医生快捷写病历并签名 → READY_PAY 病历已签·缴费单待支付（系统自动生成缴费单）
 *   → 收银台收款 → PAID 已支付·待治疗
 *   → 术前核对 → TREATING 治疗中 → 写治疗记录 → DONE 完成归档
 * 打回：PENDING_REVIEW → REJECTED（咨询师改单重提）；任意前态可 ABANDONED（作废需主管/医生）。
 * 关键合规顺序：病历先于收费、收费先于治疗。
 */
export type ConsultStatus =
  | 'PENDING'
  | 'ACTIVE'
  | 'PENDING_REVIEW'
  | 'APPROVED'
  | 'REJECTED'
  | 'READY_PAY'
  | 'PAID'
  | 'TREATING'
  | 'DONE'
  | 'ABANDONED'

/** 方案单项目明细行（快捷开单） */
export interface PlanItem {
  /** pricelist 项目 id（自定义项目可为空） */
  itemId?: string
  name: string
  spec?: string
  qty: number
  price: number
  /** 项目命中的风险标签（禁忌初筛用，如 ANESTHESIA/INJECTION/LASER/PREGNANCY_RISK） */
  riskTags?: string[]
}

/** 面诊禁忌初筛结果（结构化勾选，阳性项硬阻断） */
export interface ConsultContraindication {
  pregnant: boolean // 妊娠期/哺乳期
  allergy: boolean // 药物/麻醉/食物过敏史
  scarConstitution: boolean // 瘢痕体质
  skinLesion: boolean // 治疗区皮损/炎症
  coagulationAbn: boolean // 凝血异常/服用抗凝药
  seriousIllness: boolean // 严重基础病（糖尿病/高血压/心脏病等）
  note?: string // 补充说明
}

/** VISIA / 皮肤检测单项指标（分值 + 同龄百分位） */
export interface SkinMetric {
  key: string // spots/wrinkle/texture/pores/uvSpots/brownSpots/redAreas/porphyrins
  label: string
  /** 严重度分值 0-100（越高问题越重） */
  score: number
  /** 同龄百分位 0-100（越高代表越优于同龄人） */
  percentile: number
}

/** 结构化面诊 / 皮肤检测报告 */
export interface SkinReport {
  id: string
  customerId: string
  /** 关联咨询单（面诊时生成） */
  consultId?: string
  /** 检测设备 / 方式 */
  device: string // VISIA 7 / 医师面诊
  checkedAt: string
  checkedByName: string
  /** 肤质分型（如 干性 / 屏障受损） */
  skinType: string
  /** 客户主诉 / 诉求 */
  chiefComplaint: string
  /** 8 项检测指标 */
  metrics: SkinMetric[]
  /** 面诊分析 / 建议（客观描述，禁止疗效承诺） */
  analysis: string
  /** 推荐项目（仅建议，由医生审核确认） */
  recommendations: string[]
}

/** 客户档案照 / 面诊对比照（脱敏 + 水印，base64 本地演示） */
export interface CustomerPhoto {
  id: string
  customerId: string
  consultId?: string
  /** before 面诊/术前 · after 术后/复查 · record 档案照 */
  category: 'before' | 'after' | 'record'
  /** 部位 / 角度（正面/左 45°/右 45°/下颌等） */
  part: string
  /** 图片数据（演示用 base64 dataURL；生产为对象存储 URL） */
  dataUrl: string
  takenAt: string
  takenByName: string
  /** 已脱敏 / 已加水印（合规留痕） */
  desensitized: boolean
}

/** 审核/改单留痕（append-only，原值→改后值全程可溯） */
export interface PlanRevision {
  id: string
  at: string
  actorId: string
  actorName: string
  kind: 'SUBMIT' | 'RESUBMIT' | 'APPROVE' | 'REJECT' | 'DOCTOR_EDIT' | 'EMR_SIGN' | 'PAY' | 'TREAT_START' | 'TREAT_DONE'
  reason?: string
  /** DOCTOR_EDIT 时的字段变更：字段名/原值/新值 */
  changes?: { field: string; label: string; from: string; to: string }[]
}

/** 术前核对清单（四项全部确认方可开始治疗） */
export interface PreOpChecklist {
  consentChecked: boolean // 知情同意书已签
  contraChecked: boolean // 禁忌/过敏已复核
  drugChecked: boolean // 药品/耗材批号已核对
  siteChecked: boolean // 治疗部位/项目已与客户确认
  room?: string // 治疗室
  note?: string
}

export interface Consultation {
  id: string
  customerId: string
  arrivalId?: string
  consultantId: string
  doctorId?: string
  status: ConsultStatus
  conclusion: string
  startedAt?: string
  planAmount?: number
  planCost?: number // 成本，仅持 finance:margin:view 可见（字段级 RBAC）
  appointmentId?: string
  /** 方案单项目明细（审核标的） */
  planItems?: PlanItem[]
  /** 面诊禁忌初筛 */
  contraindications?: ConsultContraindication
  /** 知情同意电子签：咨询师已告知 + 客户已阅知（双确认） */
  consentConsultant?: boolean
  consentCustomer?: boolean
  consentAt?: string
  /** 客户手写电子签名（dataURL）+ 签署人姓名 + 同意书版本 */
  consentSignatureDataUrl?: string
  consentSignerName?: string
  consentDocVersion?: string
  /** 面诊 / 皮肤检测报告 id（结构化面诊报告） */
  skinReportId?: string
  /** 提交审核时间 / 审核人 */
  submittedAt?: string
  reviewedBy?: string
  reviewedByName?: string
  reviewedAt?: string
  rejectReason?: string
  /** 审核/改单留痕 */
  revisions?: PlanRevision[]
  /** 审核通过后生成的缴费单 / 首程病历（写后只读） */
  orderId?: string
  emrId?: string
  /** 病历签署时间（生成缴费单节点） */
  emrSignedAt?: string
  /** 支付时间 / 治疗开始与完成时间 */
  paidAt?: string
  treatingAt?: string
  treatedAt?: string
  /** 术前核对清单（PAID → TREATING 前必填） */
  preOp?: PreOpChecklist
  /** 治疗记录病历 / 术后随访 id */
  treatmentEmrId?: string
  followupId?: string
}

/** 预约 */
export type ApptStatus = 'NEW' | 'CONFIRMED' | 'ARRIVED' | 'COMPLETED' | 'NO_SHOW' | 'CANCELLED'

export interface Appointment {
  id: string
  customerId: string
  storeId: string
  consultantId?: string
  doctorId?: string
  timeSlot: string
  status: ApptStatus
  source: string
  project?: string
}

/** 活动流水（闭环可见性的核心） */
export interface Activity {
  id: string
  at: string
  actor: string // staff name
  text: string
  refId?: string
}

// ============================================================
// 资产 / 合同 / 转移（2026-08-24 决议落地）
// 资产拆 CashAsset（余额型）/ TimesAsset（次数型），AssetAccount 只读汇总
// ============================================================

/** 储值型资产（充值卡/余额） */
export interface CashAsset {
  id: string
  customerId: string
  storeId: string
  balance: number
  giftBalance: number
  payType: 'CASH' | 'CARD' | 'MIX'
  contractId?: string
  status: 'ACTIVE' | 'FROZEN' | 'FINISHED' | 'REFUNDED'
  expiresAt?: string
}

/** 次数型资产（疗程/次卡） */
export interface TimesAsset {
  id: string
  customerId: string
  storeId: string
  itemSku: string
  itemName: string
  totalTimes: number
  remainingTimes: number
  contractId?: string
  status: 'ACTIVE' | 'FROZEN' | 'FINISHED' | 'REFUNDED'
  expiresAt?: string
}

/** 资产账户：只读聚合视图，不承载写逻辑 */
export interface AssetAccount {
  customerId: string
  cashAssets: CashAsset[]
  timesAssets: TimesAsset[]
  totalBalance: number
  totalRemainingTimes: number
}

/** 合同 / 开卡协议：一个合同可对应多订单、多资产 */
export interface Contract {
  id: string
  customerId: string
  storeId: string
  no: string
  type: 'CARD' | 'COURSE' | 'PACKAGE'
  amount: number
  terms: ContractTerms
  signedAt: string
  status: 'DRAFT' | 'SIGNED' | 'PERFORMING' | 'COMPLETED' | 'TERMINATED'
  orderIds: string[]
  assetIds: string[]
  consentDocUrl?: string
}

export interface ContractTerms {
  refundRule: string
  validMonths: number
  freezeAllowed: boolean
  transferAllowed: boolean
  clauses: string[]
}

/** 资产转移（客户间/跨店）。本期登记、后置实现 */
export type TransferStatus = 'APPLIED' | 'REVIEWING' | 'APPROVED' | 'DONE' | 'REJECTED'

export interface AssetTransfer {
  id: string
  kind: 'CUSTOMER' | 'STORE'
  assetId: string
  fromCustomerId?: string
  toCustomerId?: string
  fromStoreId?: string
  toStoreId?: string
  reason: string
  status: TransferStatus
  approvedBy?: string
  executedAt?: string
}

/** 逆向交易（退款/退卡共用，方案 A） */
export type RefundKind = 'ORDER' | 'CARD'
export type RefundStatus = 'APPLIED' | 'REVIEWING' | 'APPROVED' | 'REFUNDED' | 'REJECTED' | 'CANCELLED'

export interface Refund {
  id: string
  kind: RefundKind
  orderId?: string
  assetId?: string
  customerId: string
  storeId: string
  amount: number
  signTier: 'L1' | 'L2' | 'L3'
  status: RefundStatus
  reason: string
  appliedBy: string
  approvedBy?: string
  signedBy?: string
  refundedAt?: string
}

/** 撞单合并（受控：系统只关联，人工审批后执行） */
export type MergeStatus = 'PROPOSED' | 'REVIEWING' | 'APPROVED' | 'MERGED' | 'REJECTED'

export interface CustomerMerge {
  id: string
  masterId: string // 保留的主 CustomerID
  mergedIds: string[] // 被合并作废的 CustomerID
  reason: string
  evidence: string[] // 同手机/设备/证件等凭证
  status: MergeStatus
  requestedBy: string
  approvedBy?: string
  executedAt?: string
}

/** 疑似重复关联（系统自动识别，只读提示，不自动合并） */
export interface CustomerLink {
  id: string
  customerIdA: string
  customerIdB: string
  matchReason: ('PHONE' | 'DEVICE' | 'IDCARD' | 'NAME_BIRTHDAY')[]
  score: number // 相似度
}
