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

// -------------------- 触达 / 核销链接口 --------------------

export const getWriteoffChain = () => client.get<WriteoffChain[]>('/marketing/writeoff-chain')
export const getPushQuota = (customerId: string) =>
  client.get<PushQuota>(`/marketing/push/quota/${customerId}`)
