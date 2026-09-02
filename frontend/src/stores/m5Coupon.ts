import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useM1MarketingStore } from '@/stores/m1Marketing'
import { useActivityStore } from '@/stores/activity'
import { useAuthStore } from '@/stores/auth'

// ============================================================
// M5-02 优惠券 / 券包管理
// - 复用 m1Marketing.coupons 作为活动关联券，本 store 管理全量券模板 + 发放记录
// - 防超发：领取数 received 不可超过库存 total
// - 券类型：AMOUNT 满减 / RATE 折扣 / PACKAGE 券包
// - 状态：DRAFT 草稿 → ACTIVE 进行中 → EXPIRED 已过期 / DISABLED 已停用
// - 联动：M4-15 收银应用、M5-12 核销、M6-03 对账
// ============================================================

export type CouponType = 'AMOUNT' | 'RATE' | 'PACKAGE'
export type CouponStatus = 'DRAFT' | 'ACTIVE' | 'EXPIRED' | 'DISABLED'
export type GrantScope = 'ALL' | 'NEW' | 'SEGMENT' | 'DESIGNATED'
export type GrantStatus = 'PENDING' | 'GRANTED' | 'FAILED'

let _id = 0
function nextId(p: string) { _id += 1; return `${p}-${Date.now().toString(36)}-${_id}` }
function dayOffset(n: number) {
  const d = new Date(); d.setDate(d.getDate() + n)
  return d.toISOString().slice(0, 10)
}

export interface CouponTemplate {
  id: string
  name: string
  type: CouponType
  value: number          // 满减金额 / 折扣率(如 8.5) / 券包子项总额
  threshold: number      // 使用门槛
  total: number          // 发放库存
  received: number       // 已领取
  used: number           // 已核销
  startDate: string
  endDate: string
  status: CouponStatus
  scope: GrantScope
  scopeName: string
  campaignId?: string
  packageItems?: { name: string; value: number }[]
}

export interface GrantRecord {
  id: string
  couponId: string
  couponName: string
  scope: GrantScope
  targetName: string
  count: number
  status: GrantStatus
  grantedAt: string
  operator: string
}

export const COUPON_TYPE_LABEL: Record<CouponType, string> = {
  AMOUNT: '满减券', RATE: '折扣券', PACKAGE: '券包',
}
export const COUPON_STATUS_LABEL: Record<CouponStatus, string> = {
  DRAFT: '草稿', ACTIVE: '进行中', EXPIRED: '已过期', DISABLED: '已停用',
}
export const COUPON_STATUS_PILL: Record<CouponStatus, 'default' | 'success' | 'warning' | 'danger'> = {
  DRAFT: 'default', ACTIVE: 'success', EXPIRED: 'warning', DISABLED: 'danger',
}
export const GRANT_STATUS_PILL: Record<GrantStatus, 'primary' | 'success' | 'danger'> = {
  PENDING: 'primary', GRANTED: 'success', FAILED: 'danger',
}

export const useM5CouponStore = defineStore('m5Coupon', () => {
  const m1 = useM1MarketingStore()
  const activity = useActivityStore()
  const auth = useAuthStore()

  const coupons = ref<CouponTemplate[]>([])
  const grants = ref<GrantRecord[]>([])
  const seeded = ref(false)

  const stats = computed(() => {
    const list = coupons.value
    return {
      active: list.filter((c) => c.status === 'ACTIVE').length,
      totalIssued: list.reduce((s, c) => s + c.total, 0),
      totalReceived: list.reduce((s, c) => s + c.received, 0),
      totalUsed: list.reduce((s, c) => s + c.used, 0),
      usageRate: list.reduce((s, c) => s + c.received, 0)
        ? Math.round((list.reduce((s, c) => s + c.used, 0) / list.reduce((s, c) => s + c.received, 0)) * 100)
        : 0,
    }
  })

  const stockLeft = (c: CouponTemplate) => c.total - c.received
  const isExpiringSoon = (c: CouponTemplate) => {
    const days = Math.ceil((new Date(c.endDate).getTime() - Date.now()) / 86400000)
    return days <= 7 && days >= 0 && c.status === 'ACTIVE'
  }

  function createCoupon(payload: Omit<CouponTemplate, 'id' | 'received' | 'used' | 'status'> & { status?: CouponStatus }): CouponTemplate {
    if (!auth.can('coupon:create')) throw new Error('无创建券权限')
    if (payload.total < 1) throw new Error('库存必须大于 0')
    if (payload.type === 'AMOUNT' && payload.value <= 0) throw new Error('满减金额必须大于 0')
    if (payload.type === 'RATE' && (payload.value <= 0 || payload.value >= 10)) throw new Error('折扣率应在 0-10 之间')
    const c: CouponTemplate = {
      ...payload, id: nextId('cpn'),
      received: 0, used: 0,
      status: payload.status ?? 'DRAFT',
    }
    coupons.value.unshift(c)
    activity.log(auth.user?.name ?? '系统', `创建优惠券「${c.name}」库存 ${c.total}`, c.id)
    return c
  }

  function enableCoupon(id: string) {
    const c = coupons.value.find((x) => x.id === id)
    if (c && (c.status === 'DRAFT' || c.status === 'DISABLED')) {
      c.status = 'ACTIVE'
      activity.log(auth.user?.name ?? '系统', `启用优惠券「${c.name}」`, id)
    }
  }

  function disableCoupon(id: string) {
    const c = coupons.value.find((x) => x.id === id)
    if (c && c.status === 'ACTIVE') { c.status = 'DISABLED'; activity.log(auth.user?.name ?? '系统', `停用优惠券「${c.name}」`, id) }
  }

  /** 发放券（防超发） */
  function grant(couponId: string, scope: GrantScope, targetName: string, count: number): GrantRecord {
    if (!auth.can('coupon:edit')) throw new Error('无发券权限')
    const c = coupons.value.find((x) => x.id === couponId)
    if (!c) throw new Error('券不存在')
    const left = stockLeft(c)
    const actual = Math.min(count, left)
    let status: GrantStatus = 'GRANTED'
    if (actual <= 0) { status = 'FAILED' }
    else if (actual < count) { status = 'GRANTED' } // 部分发放
    c.received += actual
    const rec: GrantRecord = {
      id: nextId('gr'), couponId, couponName: c.name, scope, targetName,
      count: actual, status, grantedAt: new Date().toISOString().slice(0, 16).replace('T', ' '),
      operator: auth.user?.name ?? '系统',
    }
    grants.value.unshift(rec)
    activity.log(auth.user?.name ?? '系统',
      status === 'FAILED' ? `发放「${c.name}」失败：库存不足` : `发放「${c.name}」${actual} 张至 ${targetName}`, rec.id)
    return rec
  }

  function seed() {
    if (seeded.value) return
    m1.seed()
    const mk = (c: Partial<CouponTemplate> & { name: string; type: CouponType; value: number; threshold: number; total: number; status: CouponStatus; scope: GrantScope; scopeName: string; startDate: string; endDate: string }): CouponTemplate => ({
      ...c, id: nextId('cpn'), received: c.received ?? 0, used: c.used ?? 0,
    })
    coupons.value = [
      mk({ name: '水光满3000减500', type: 'AMOUNT', value: 500, threshold: 3000, total: 200, received: 156, used: 89, status: 'ACTIVE', scope: 'ALL', scopeName: '全部客户', startDate: dayOffset(-20), endDate: dayOffset(20) }),
      mk({ name: '新客88元体验券', type: 'AMOUNT', value: 200, threshold: 500, total: 500, received: 218, used: 134, status: 'ACTIVE', scope: 'NEW', scopeName: '新客专享', startDate: dayOffset(-30), endDate: dayOffset(30) }),
      mk({ name: '热玛吉8.5折券', type: 'RATE', value: 8.5, threshold: 8000, total: 50, received: 32, used: 18, status: 'ACTIVE', scope: 'SEGMENT', scopeName: '高价值客户', startDate: dayOffset(-10), endDate: dayOffset(15) }),
      mk({ name: '会员日乔雅登满减包', type: 'PACKAGE', value: 1200, threshold: 5000, total: 100, received: 0, used: 0, status: 'DRAFT', scope: 'SEGMENT', scopeName: '会员等级V3+', startDate: dayOffset(3), endDate: dayOffset(10), packageItems: [{ name: '满5000减600', value: 600 }, { name: '满3000减300', value: 300 }, { name: '赠品券', value: 300 }] }),
      mk({ name: '光子嫩肤买3送1券', type: 'AMOUNT', value: 380, threshold: 1140, total: 80, received: 64, used: 64, status: 'EXPIRED', scope: 'ALL', scopeName: '全部客户', startDate: dayOffset(-60), endDate: dayOffset(-30) }),
      mk({ name: '老带新专属100元券', type: 'AMOUNT', value: 100, threshold: 300, total: 300, received: 86, used: 42, status: 'ACTIVE', scope: 'DESIGNATED', scopeName: '转介绍客户', startDate: dayOffset(-15), endDate: dayOffset(45) }),
      mk({ name: '七夕限定9折券', type: 'RATE', value: 9, threshold: 1000, total: 150, received: 0, used: 0, status: 'DISABLED', scope: 'ALL', scopeName: '全部客户', startDate: dayOffset(-5), endDate: dayOffset(5) }),
    ]
    grants.value = [
      { id: nextId('gr'), couponId: coupons.value[0].id, couponName: coupons.value[0].name, scope: 'ALL', targetName: '全部客户', count: 200, status: 'GRANTED', grantedAt: dayOffset(-18) + ' 10:00', operator: '白桥' },
      { id: nextId('gr'), couponId: coupons.value[1].id, couponName: coupons.value[1].name, scope: 'NEW', targetName: '本月新客', count: 218, status: 'GRANTED', grantedAt: dayOffset(-28) + ' 14:00', operator: '林微' },
      { id: nextId('gr'), couponId: coupons.value[2].id, couponName: coupons.value[2].name, scope: 'SEGMENT', targetName: '高价值客户(42人)', count: 32, status: 'GRANTED', grantedAt: dayOffset(-8) + ' 09:30', operator: '白桥' },
    ]
    seeded.value = true
  }

  return {
    coupons, grants, stats,
    COUPON_TYPE_LABEL, COUPON_STATUS_LABEL, COUPON_STATUS_PILL, GRANT_STATUS_PILL,
    stockLeft, isExpiringSoon, createCoupon, enableCoupon, disableCoupon, grant, seed,
  }
})
