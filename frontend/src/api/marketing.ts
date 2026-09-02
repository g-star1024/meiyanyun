// ============================================================
// Marketing API（对接 marketing-service）
// 合规约束：触达 ≤3 条/周（见 getPushQuota）、违禁词校验在后端。
// ============================================================
import client from './client'

export interface Campaign {
  campaignId: string
  campaignName: string
  campaignType: string
  status: string
  budget: number
}

export interface CouponTemplate {
  couponId: string
  couponName: string
  faceValue: number
  totalQty: number
  issuedQty: number
  usedQty: number
  status: string
}

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

export const listCampaigns = () => client.get<Campaign[]>('/marketing/campaigns')
export const listCoupons = () => client.get<CouponTemplate[]>('/marketing/coupons')
export const getWriteoffChain = () => client.get<WriteoffChain[]>('/marketing/writeoff-chain')
export const getPushQuota = (customerId: string) =>
  client.get<PushQuota>(`/marketing/push/quota/${customerId}`)
