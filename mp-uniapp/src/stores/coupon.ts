/**
 * 优惠券 store（C 端）
 * 可领券列表 + 领取动作 + 我的持券。券由 B 端 M5-02 配置发放，收银 M4-15 核销。
 * C-B5 切真（DESIGN-C §四端点 #8）：列表 GET /c/coupons/claimable，领取 POST /c/coupons/:id/claim
 * （幂等键 couponId:customerId，重放 200 同 holdId；售罄 409 中文原话由 http 层弹出），
 * 我的持券 GET /c/coupons/mine（三 tab：可使用=claimable、已使用/已过期=mine）。
 * claimedIds 由 claimable 的 claimed 字段重建（服务端联查 coupon_hold），不再本地臆造。
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import {
  claimCoupon,
  fetchClaimableCoupons,
  fetchMyCoupons,
  type ClaimableCoupon,
  type MyCoupon,
} from '@/api/coupon'

export type Coupon = ClaimableCoupon
export type { MyCoupon }

export const useCouponStore = defineStore('mp-coupon', () => {
  const coupons = ref<Coupon[]>([])
  /** 我的持券（mine：HELD/USED/CANCELLED，页面派生已使用/已过期 tab） */
  const myCoupons = ref<MyCoupon[]>([])
  /** 我已领取的券 id（claimable.claimed 重建 + 领取成功即时并入） */
  const claimedIds = ref<string[]>([])

  function stockLeft(c: Coupon) {
    return Math.max(0, c.total - c.granted)
  }

  /** 领取（async）：成功并入 claimedIds 并同步 granted/claimed；失败由 http 层 toast 中文原话 */
  async function claim(id: string): Promise<{ status: 'GRANTED' | 'FAILED' }> {
    try {
      await claimCoupon(id)
      const c = coupons.value.find((x) => x.id === id)
      if (c && !c.claimed) {
        c.granted += 1
        c.claimed = true
      }
      if (!claimedIds.value.includes(id)) claimedIds.value.push(id)
      return { status: 'GRANTED' }
    } catch {
      return { status: 'FAILED' }
    }
  }

  let seeded = false
  async function seed() {
    if (seeded) return
    seeded = true
    try {
      const [list, mine] = await Promise.all([fetchClaimableCoupons(), fetchMyCoupons()])
      coupons.value = list
      myCoupons.value = mine
      claimedIds.value = list.filter((c) => c.claimed).map((c) => c.id)
    } catch {
      /* 未登录/后端未就绪：回滚 seeded 允许登录后 onShow 重拉（本地无假数据兜底） */
      seeded = false
    }
  }

  return { coupons, myCoupons, claimedIds, stockLeft, claim, seed }
})
