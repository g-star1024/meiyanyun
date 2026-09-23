// ============================================================
// Referral API（对接 customer-service /api/customer/referral）
// B86 转介绍：page/stats/创建/确认/到访/成交/拒绝＋奖励登记/发放/驳回。
// 金额口径：后端 bigint 存「分」（dealAmountCents/rewardAmountCents），
// 前端 mock 活规格用「元」，换算在 stores/referral.ts 适配层（铁律 2）。
// 奖励类型：后端 POINT/GRANT/COUPON/COMMISSION（V44 chk），
// 前端 POINTS/COUPON/CASH，映射在适配层。
// ============================================================
import client from './client'

export interface ReferralRow {
  referralId: string
  referrerCustomerId: string
  refereeCustomerId: string
  campaignId: string | null
  status: string
  validDays: number
  boundAt: string | null
  expireAt: string | null
  confirmedAt: string | null
  visitedAt: string | null
  dealAt: string | null
  expiredAt: string | null
  rejectedAt: string | null
  rejectReason: string | null
  dealAmountCents: number | null
  storeCode: string
  remark: string | null
  createdBy: string | null
  createdAt: string
  referrerName: string | null
  referrerPhone: string | null
  refereeName: string | null
  refereePhone: string | null
  referrerLevel: string | null
  referrerTotal: number | null
  rewardId?: string | null
  rewardType?: string | null
  rewardAmountCents?: number | null
  rewardPoints?: number | null
  rewardStatus?: string | null
  rewardPaidAt?: string | null
}

export interface ReferralRewardRow {
  rewardId: string
  referralId: string
  rewardType: string
  triggerEvent: string
  amountCents: number | null
  points: number | null
  status: string
  grantedBy: string | null
  grantedAt: string | null
  idemKey: string
  remark: string | null
  createdAt: string
}

export interface ReferralStats {
  total: number
  pending: number
  visited: number
  dealAmountCents: number
}

export interface ReferralPage {
  content: ReferralRow[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

export interface ReferralPageParams {
  status?: string
  storeCode?: string
  referrerCustomerId?: string
  refereeCustomerId?: string
  kw?: string
  page?: number
  size?: number
}

export const fetchReferralPage = (params: ReferralPageParams) =>
  client.get<ReferralPage>('/customer/referral/page', { params })

export const fetchReferralStats = () => client.get<ReferralStats>('/customer/referral/stats')

export const createReferral = (body: {
  referrerCustomerId: string
  refereeCustomerId: string
  campaignId?: string
  validDays?: number
  remark?: string
  clientToken?: string
}) => client.post<ReferralRow>('/customer/referral', body)

export const confirmReferral = (id: string) =>
  client.put<ReferralRow>(`/customer/referral/${id}/confirm`)

export const visitReferral = (id: string) =>
  client.put<ReferralRow>(`/customer/referral/${id}/visit`)

export const dealReferral = (id: string, dealAmountCents: number) =>
  client.put<ReferralRow>(`/customer/referral/${id}/deal`, { dealAmountCents })

export const rejectReferral = (id: string, rejectReason: string) =>
  client.put<ReferralRow>(`/customer/referral/${id}/reject`, { rejectReason })

export const createReferralReward = (id: string, body: {
  rewardType: string
  triggerEvent: string
  amountCents?: number
  points?: number
  remark?: string
}) => client.post<ReferralRewardRow>(`/customer/referral/${id}/rewards`, body)

export const grantReferralReward = (rewardId: string) =>
  client.put<ReferralRewardRow>(`/customer/referral/rewards/${rewardId}/grant`)

export const rejectReferralReward = (rewardId: string, reason?: string) =>
  client.put<ReferralRewardRow>(`/customer/referral/rewards/${rewardId}/reject`, { reason })
