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

// -------------------- M5-14 营销看板：推送效果 / 漏斗 / 渠道 / 趋势 --------------------
// 口径（后端 MarketingStatsService 聚合，金额「分」）：push_record 无到达/点击/转化回执字段，
// 推送效果用「推送发送+内容曝光≈触达、内容互动≈点击、内容成交≈转化」真实表近似；
// 漏斗为到店核销 O2O 链路严格递减；渠道营收/成交/线索取内容表（平台映射），投放取活动 spent 均摊。

/** 推送/全域触达效果（ctr/cvr 为百分比，保留 1 位小数）。 */
export interface PushStatsDTO {
  sent: number
  delivered: number
  clicked: number
  converted: number
  ctr: number
  cvr: number
}

/** 漏斗阶段（ratio 为相对上一级的百分比，首段 100；key: exposure/coupon/arrival/deal）。 */
export interface FunnelStageDTO {
  key: string
  label: string
  value: number
  ratio: number
}

/** 渠道业绩排行（金额「分」，roi 为倍数两位；后端按营收降序）。 */
export interface ChannelRankRowDTO {
  key: string
  name: string
  revenue: number
  spent: number
  deals: number
  leads: number
  roi: number
}

/** 近 6 月触达/转化趋势点（month 为 yyyy-MM）。 */
export interface TrendPointDTO {
  month: string
  reach: number
  converted: number
}

export interface MarketingStatsDTO {
  coupon: CouponStatsDTO
  campaign: CampaignStatsDTO
  push: PushStatsDTO
  funnel: { stages: FunnelStageDTO[] }
  channel: { rows: ChannelRankRowDTO[] }
  trend: { points: TrendPointDTO[] }
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

// -------------------- M5-12 券核销 --------------------

/** 核销流水 DTO（金额单位「分」；status: OK/DUPLICATE/FORGED/EXPIRED）。 */
export interface CouponWriteoffDTO {
  writeoffId: string
  couponCode: string
  couponId: string | null
  couponName: string
  customerName: string
  customerPhone: string
  storeCode: string
  storeName: string
  orderAmountFen: number
  discountFen: number
  channel: string
  status: string
  reason: string | null
  operator: string
  verifiedAt: string
}

/** 核销命令（金额单位「分」）。 */
export interface CouponWriteoffCmd {
  couponCode: string
  customerName: string
  customerPhone: string
  orderAmountFen: number
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

/** M5-12 券核销流水（按核销时间倒序；伪造/重复/过期均落流水，不抛错）。 */
export const listCouponWriteoffs = () => client.get<CouponWriteoffDTO[]>('/marketing/coupon-writeoffs')
/** 扫码核销：后端顺序校验券码存在性→重复核销→状态/有效期/库存→计算抵扣；参数非法抛 400 中文。 */
export const verifyCouponWriteoff = (cmd: CouponWriteoffCmd) =>
  client.post<CouponWriteoffDTO>('/marketing/coupon-writeoff', cmd)
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

// -------------------- M5-13 素材库 --------------------
// 金额口径：素材无金额；tags/storeCodes 为 JSON 数组字符串（前端 JSON.parse，失败回落 []）。

export interface MarketingAssetDTO {
  assetId: string
  assetName: string
  type: string
  tags: string
  scope: string
  storeCodes: string
  expireAt: string | null
  refCount: number
  accent: string
  content: string | null
  createdAt: string
}

export interface AssetCmd {
  name: string
  type: string
  tags: string[]
  scope: string
  storeCodes: string[]
  /** 有效期 yyyy-MM-dd，必传（空值后端 400「请选择素材有效期」）。 */
  expireAt: string | null
  accent: string
  /** COPY 文案正文，其余类型可空（null）。 */
  content: string | null
}

// -------------------- M5-04 裂变海报 --------------------
// 金额口径：dealAmount bigint 存「分」；commissionRate = 百分比×10（5% = 50），前端用 0~1。

export interface PosterTemplateDTO {
  templateId: string
  templateName: string
  style: string
  status: string
  uses: number
  accent: string
  defaultTitle: string
  defaultSubtitle: string
  createdAt: string
}

export interface PosterRecordDTO {
  posterId: string
  templateId: string
  templateName: string
  style: string
  accent: string
  title: string
  subtitle: string | null
  project: string
  referrerName: string | null
  status: string
  share: number
  scan: number
  lead: number
  visit: number
  deal: number
  dealAmount: number
  commissionRate: number
  createdAt: string
}

export interface PosterCmd {
  templateId: string
  title: string
  subtitle: string
  project: string
  referrerName: string
  commissionRate: number
}

// -------------------- M5-05 直播团购 / 短视频 --------------------
// 金额口径：dealAmount bigint 存「分」；startTime 为 LocalDateTime（ISO），前端展示 'yyyy-MM-dd HH:mm'；
// mountedCouponIds / tags 为 JSON 数组字符串；短视频无 createdAt，发布日用 publishedAt（LocalDate）。

export interface LiveSessionDTO {
  sessionId: string
  title: string
  platform: string
  status: string
  startTime: string
  viewers: number
  linkClicks: number
  dealCount: number
  dealAmount: number
  mountedCouponIds: string
  intro: string | null
  host: string | null
  createdAt: string
}

export interface ShortVideoDTO {
  videoId: string
  title: string
  platform: string
  plays: number
  likes: number
  dealCount: number
  dealAmount: number
  tags: string
  publishedAt: string
}

export interface SessionCmd {
  title: string
  platform: string
  startTime: string
  mountedCouponIds: string[]
  intro: string
}

// -------------------- 素材库接口 --------------------

export const listAssets = () => client.get<MarketingAssetDTO[]>('/marketing/assets')
export const createAsset = (cmd: AssetCmd) => client.post<MarketingAssetDTO>('/marketing/assets', cmd)
export const addAssetTag = (id: string, tag: string) =>
  client.post<TransitResult>(`/marketing/assets/${id}/tags`, { tag })
export const removeAssetTag = (id: string, tag: string) =>
  client.post<TransitResult>(`/marketing/assets/${id}/tags/remove`, { tag })
export const distributeAsset = (id: string, storeCodes: string[]) =>
  client.post<TransitResult>(`/marketing/assets/${id}/distribute`, { storeCodes })

// -------------------- 海报接口 --------------------

export const listPosterTemplates = () => client.get<PosterTemplateDTO[]>('/marketing/poster-templates')
export const listPosters = () => client.get<PosterRecordDTO[]>('/marketing/posters')
export const togglePosterTemplate = (id: string) =>
  client.post<TransitResult>(`/marketing/poster-templates/${id}/toggle`)
export const createPoster = (cmd: PosterCmd) => client.post<PosterRecordDTO>('/marketing/posters', cmd)

// -------------------- 直播 / 短视频接口 --------------------

export const listLiveSessions = () => client.get<LiveSessionDTO[]>('/marketing/live-sessions')
export const listShortVideos = () => client.get<ShortVideoDTO[]>('/marketing/short-videos')
export const createLiveSession = (cmd: SessionCmd) =>
  client.post<LiveSessionDTO>('/marketing/live-sessions', cmd)
export const startLiveSession = (id: string) =>
  client.post<TransitResult>(`/marketing/live-sessions/${id}/start`)
export const endLiveSession = (id: string) =>
  client.post<TransitResult>(`/marketing/live-sessions/${id}/end`)

// -------------------- M5-15 营销设置（GET/POST /config） --------------------
// 金额口径：largeCouponThresholdFen bigint 存「分」，前端活规格用「元」，换算在 store 适配层；
// defaultPushChannels/defaultAdChannels 后端存 JSON 数组文本，DTO 直接透出字符串由适配层 parse。

export interface MarketingCfgDTO {
  cfgId?: number
  referralArrivedReward?: number | null
  referralDealReward?: number | null
  commissionRate?: number | null
  weeklyPushLimit?: number | null
  quietHoursEnabled?: boolean | null
  quietStart?: string | null
  quietEnd?: string | null
  holidayExempt?: boolean | null
  largeCouponThresholdFen?: number | null
  pushRequiresApproval?: boolean | null
  approvalLevel?: number | null
  defaultPushChannels?: string | null
  defaultAdChannels?: string | null
}

export interface MarketingCfgCmd {
  weeklyLimit: number
  quietHoursEnabled: boolean
  quietStart: string
  quietEnd: string
  holidayExempt: boolean
  /** 大额券审批阈值（分）。 */
  largeCouponThresholdFen: number
  pushRequiresApproval: boolean
  approvalLevel: number
  defaultPushChannels: string[]
  defaultAdChannels: string[]
}

export const getMarketingConfig = () => client.get<MarketingCfgDTO>('/marketing/config')

export const saveMarketingConfig = (cmd: MarketingCfgCmd) =>
  client.post<TransitResult>('/marketing/config', cmd)
