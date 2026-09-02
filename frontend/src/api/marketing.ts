// ============================================================
// Marketing API（对接 marketing-service）
// 合规约束：触达 ≤3 条/周（见 getPushQuota）、违禁词校验在后端。
// 金额口径：后端所有金额字段 bigint 存「分」；前端 mock 活规格用「元」，换算在 store 适配层。
// 折扣券：后端 faceValue = 折扣×10（8.5 折 = 85），前端用 8.5。
// ============================================================
import client from './client'

// -------------------- 活动 --------------------

export interface CampaignDTO {
  campaignId: string
  campaignName: string
  campaignType: string
  status: string
  channels: string | null
  startDate: string | null
  endDate: string | null
  budget: number
  spent: number
  targetAmount: number
  actualAmount: number
  newCustomers: number
  storeScope: string | null
  owner: string | null
  remark: string | null
  createdAt: string
}

export interface CampaignCmd {
  name: string
  type: string
  channels: string[]
  startDate: string
  endDate: string
  budget: number
  targetAmount: number
  storeScope: string
  owner: string
  remark?: string
}

export interface TransitResult {
  changed: boolean
}

// -------------------- 优惠券 --------------------

export interface CouponTemplateDTO {
  couponId: string
  couponName: string
  couponType: string
  faceValue: number
  threshold: number
  totalQty: number
  issuedQty: number
  usedQty: number
  status: string
  grantScope: string
  grantScopeName: string | null
  packageItems: string | null
  campaignId: string | null
  couponCode: string | null
  validStart: string | null
  validEnd: string | null
  createdAt: string
}

export interface CouponCmd {
  name: string
  type: string
  value: number
  threshold: number
  total: number
  scope: string
  scopeName: string
  startDate: string
  endDate: string
  packageItems?: string
}

export interface GrantCmd {
  scope: string
  targetName: string
  count: number
}

export interface CouponGrantDTO {
  grantId: string
  couponId: string
  couponName: string
  grantScope: string
  targetName: string
  grantCount: number
  status: string
  grantedAt: string
  operator: string
}

// -------------------- M5-06 ROI 统计聚合（GET /stats/overview） --------------------
// 金额口径：bigint 存「分」，前端 store 适配层 fen2yuan；比率为 0~1 小数。

export interface CouponStatsRowDTO {
  couponId: string
  couponName: string
  couponType: string
  status: string
  totalQty: number
  issuedQty: number
  usedQty: number
  writeoffRate: number
  campaignId: string | null
}

export interface CouponStatsDTO {
  couponKinds: number
  totalStock: number
  totalIssued: number
  totalUsed: number
  writeoffRate: number
  grantBatches: number
  grantedPcs: number
  rows: CouponStatsRowDTO[]
}

export interface CampaignStatsRowDTO {
  campaignId: string
  campaignName: string
  campaignType: string
  status: string
  spent: number
  actualAmount: number
  budget: number
  targetAmount: number
  newCustomers: number
  roi: number
}

export interface CampaignStatsDTO {
  campaignCount: number
  runningCount: number
  totalSpent: number
  totalActualAmount: number
  totalBudget: number
  totalTargetAmount: number
  totalNewCustomers: number
  overallRoi: number
  achieveRate: number
  rows: CampaignStatsRowDTO[]
}

export interface MarketingStatsDTO {
  coupon: CouponStatsDTO
  campaign: CampaignStatsDTO
}

// -------------------- 触达 / 核销链 --------------------

export interface WriteoffChain {
  chainId: string
  segment: string
  cnt: number
}

export interface PushQuota {
  customerId: string
  sentLast7Days: number
  weeklyLimit: number
  remaining: number
}

/** 推送渠道码契约（与后端 MarketingController PUSH_TYPES 白名单一致；push_type 列 varchar(16)）。 */
export type PushTypeCode = 'SMS' | 'WECOM' | 'WECHAT_MP'

export interface PushCmd {
  customerId: string
  pushType: PushTypeCode
  content: string
}

/** 触达记录（GET /push/history/{customerId}，按 sentAt 倒序）。 */
export interface PushRecordDTO {
  pushId: number
  customerId: string
  pushType: string
  content: string
  sentAt: string
}

// -------------------- 活动接口 --------------------

export const listCampaigns = () => client.get<CampaignDTO[]>('/marketing/campaigns')
export const createCampaign = (cmd: CampaignCmd) => client.post<CampaignDTO>('/marketing/campaign', cmd)
export const transitCampaign = (id: string, to: string) =>
  client.post<TransitResult>(`/marketing/campaigns/${id}/transit`, { to })

// -------------------- 券接口 --------------------

export const listCoupons = () => client.get<CouponTemplateDTO[]>('/marketing/coupons')
export const createCoupon = (cmd: CouponCmd) => client.post<CouponTemplateDTO>('/marketing/coupons', cmd)
export const enableCoupon = (id: string) => client.post<TransitResult>(`/marketing/coupons/${id}/enable`)
export const disableCoupon = (id: string) => client.post<TransitResult>(`/marketing/coupons/${id}/disable`)
export const grantCoupon = (id: string, cmd: GrantCmd) =>
  client.post<CouponGrantDTO>(`/marketing/coupons/${id}/grant`, cmd)
export const listCouponGrants = () => client.get<CouponGrantDTO[]>('/marketing/coupon-grants')

// -------------------- 违禁词库（A1-04：DB + Redis 缓存 + 管理端维护） --------------------

export const FORBIDDEN_WORD_CATEGORIES = ['绝对化用语', '医疗承诺', '虚假宣传', '低俗诱导'] as const
export type ForbiddenWordCategory = (typeof FORBIDDEN_WORD_CATEGORIES)[number]

export interface ForbiddenWordDTO {
  wordId: number
  category: string
  word: string
  enabled: boolean
  createdAt: string
  updatedAt: string
}

export interface CheckCopyResult {
  passed: boolean
  hits: string[]
}

// -------------------- 触达 / 核销链接口 --------------------

/** M5-06 发券量/核销量/活动转化统计汇总（金额「分」，比率 0~1；空表全 0 + 空明细）。 */
export const getMarketingStats = () => client.get<MarketingStatsDTO>('/marketing/stats/overview')

export const getWriteoffChain = () => client.get<WriteoffChain[]>('/marketing/writeoff-chain')
export const getPushQuota = (customerId: string) =>
  client.get<PushQuota>(`/marketing/push/quota/${customerId}`)
/** 发送触达（后端顺序校验：渠道白名单 → 违禁词 → 周频；失败抛 400 中文，前端取 errMsg 展示）。 */
export const sendPush = (cmd: PushCmd) =>
  client.post<PushRecordDTO>('/marketing/push', cmd)
/** 某客户触达历史（按发送时间倒序）。 */
export const getPushHistory = (customerId: string) =>
  client.get<PushRecordDTO[]>(`/marketing/push/history/${customerId}`)

// -------------------- 违禁词库接口 --------------------

/** 启用词按类别分组（文案预检与词库展示；DB 空表时后端回落内置词库）。 */
export const getForbiddenWordGroups = () =>
  client.get<Record<string, string[]>>('/marketing/forbidden-words')
/** 管理端全量词列表（含停用词，带 wordId 供启停/删除）。 */
export const listForbiddenWords = () =>
  client.get<ForbiddenWordDTO[]>('/marketing/forbidden-words/list')
/** 文案预检：发送前实时校验，返回命中词（不抛错，供页面红字提示）。 */
export const checkCopy = (content: string) =>
  client.post<CheckCopyResult>('/marketing/forbidden-words/check', { content })
/** 新增违禁词（幂等：同类别同词已存在后端直接返回；非法类别/空词 400 中文错误）。 */
export const createForbiddenWord = (cmd: { category: string; word: string }) =>
  client.post<ForbiddenWordDTO>('/marketing/forbidden-words', cmd)
/** 启用/停用违禁词（幂等 changed）。 */
export const toggleForbiddenWord = (id: number, enabled: boolean) =>
  client.post<TransitResult>(`/marketing/forbidden-words/${id}/toggle`, { enabled })
/** 删除违禁词（幂等：不存在返回 changed=false）。 */
export const deleteForbiddenWord = (id: number) =>
  client.post<TransitResult>(`/marketing/forbidden-words/${id}/delete`)
