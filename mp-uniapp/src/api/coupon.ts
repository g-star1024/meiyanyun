/**
 * 优惠券域 API（C 端，C-B5）
 * 对齐 c-service CCouponController 实测契约：
 * - GET  /c/coupons/claimable → 可领券（AMOUNT 元数值 / DISCOUNT 折扣原值 85=8.5 折，claimed 由持券表联查）
 * - POST /c/coupons/{id}/claim → 领取（幂等键 couponId:customerId，重放 200 同 holdId；售罄 409 中文原话）
 * - GET  /c/coupons/mine → 我的持券（holdId/status HELD|USED|CANCELLED/usedAt/createdAt）
 * 列表拉取 silent（未登录不弹 toast，store 回滚 seeded 允许登录后重拉）；
 * 领取动作非 silent，后端中文 message 由 http 层 toast 原话弹出并随异常抛出。
 */
import { http } from '@/utils/request'

/** 可领券列表项（claimable 投影） */
export interface ClaimableCoupon {
  id: string
  name: string
  type: 'AMOUNT' | 'DISCOUNT'
  /** AMOUNT=元数值；DISCOUNT=折扣原值（85 → 8.5 折，页面 /10 展示） */
  value: number
  /** 使用门槛（元） */
  threshold: number
  total: number
  granted: number
  startDate: string
  endDate: string
  /** 本人是否已持券（服务端联查 coupon_hold） */
  claimed: boolean
  status: string
}

/** 我的持券项（mine 投影；total/granted 本接口不下发，恒 0） */
export interface MyCoupon {
  id: string
  name: string
  type: 'AMOUNT' | 'DISCOUNT'
  value: number
  threshold: number
  total: number
  granted: number
  startDate: string
  endDate: string
  holdId: number
  status: 'HELD' | 'USED' | 'CANCELLED'
  usedAt: string | null
  createdAt: string
}

/** 领取响应（marketing internal 投影） */
export interface ClaimResult {
  holdId: number
  couponId: string
  customerId: string
  status: string
  createdAt: string
}

export async function fetchClaimableCoupons(): Promise<ClaimableCoupon[]> {
  const list = await http.get<ClaimableCoupon[]>('/c/coupons/claimable', { silent: true })
  return Array.isArray(list) ? list : []
}

export async function fetchMyCoupons(): Promise<MyCoupon[]> {
  const list = await http.get<MyCoupon[]>('/c/coupons/mine', { silent: true })
  return Array.isArray(list) ? list : []
}

/** 领取（幂等：已领过重放 200 同 holdId；售罄 409 中文原话由 http 层弹出） */
export async function claimCoupon(id: string): Promise<ClaimResult> {
  return http.post<ClaimResult>(`/c/coupons/${encodeURIComponent(id)}/claim`, {})
}
