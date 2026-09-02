/**
 * 优惠券 store（C 端）
 * 可领券列表 + 领取动作。券由 B 端 M5-02 配置发放，收银 M4-15 核销。
 * 后端就绪后：列表 GET /c/coupons/claimable，领取 POST /c/coupons/:id/claim。
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'

export interface Coupon {
  id: string
  name: string
  type: 'AMOUNT' | 'DISCOUNT'
  value: number
  threshold: number
  total: number
  granted: number
  startDate: string
  endDate: string
  status: 'ACTIVE' | 'INACTIVE'
}

let seq = 0
function nextId() {
  seq += 1
  return `cp-${seq}`
}

export const useCouponStore = defineStore('mp-coupon', () => {
  const coupons = ref<Coupon[]>([])
  /** 我已领取的券 id */
  const claimedIds = ref<string[]>([])

  function stockLeft(c: Coupon) {
    return Math.max(0, c.total - c.granted)
  }

  /** 领取 */
  function grant(id: string, _targetType: string, _name: string, _qty: number): { status: 'GRANTED' | 'FAILED' } {
    const c = coupons.value.find((x) => x.id === id)
    if (!c || c.status !== 'ACTIVE') return { status: 'FAILED' }
    if (stockLeft(c) <= 0) return { status: 'FAILED' }
    if (claimedIds.value.includes(id)) return { status: 'FAILED' }
    c.granted += 1
    claimedIds.value.push(id)
    return { status: 'GRANTED' }
  }

  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    const list: Array<Omit<Coupon, 'id'>> = [
      { name: '新客专享 8 折券', type: 'DISCOUNT', value: 8, threshold: 0, total: 500, granted: 120, startDate: '2026-08-01', endDate: '2026-09-30', status: 'ACTIVE' },
      { name: '满 1000 减 200', type: 'AMOUNT', value: 200, threshold: 1000, total: 300, granted: 90, startDate: '2026-08-10', endDate: '2026-09-15', status: 'ACTIVE' },
      { name: '光电项目满 5000 减 800', type: 'AMOUNT', value: 800, threshold: 5000, total: 200, granted: 60, startDate: '2026-08-15', endDate: '2026-10-15', status: 'ACTIVE' },
      { name: '皮肤管理 9 折券', type: 'DISCOUNT', value: 9, threshold: 300, total: 800, granted: 800, startDate: '2026-07-01', endDate: '2026-08-31', status: 'ACTIVE' },
    ]
    list.forEach((c) => coupons.value.push({ id: nextId(), ...c }))
  }

  return { coupons, claimedIds, stockLeft, grant, seed }
})
