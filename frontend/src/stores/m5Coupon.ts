import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useActivityStore } from '@/stores/activity'
import { useAuthStore } from '@/stores/auth'
import * as api from '@/api/marketing'
import type { CouponTemplateDTO, CouponGrantDTO } from '@/api/marketing'

// ============================================================
// M5-02 优惠券 / 券包管理（已接真实 marketing-service）
//
// 适配层（铁律：模板/样式零改动，只换数据源）：
//  - 后端金额 bigint 存「分」，前端活规格用「元」：fen2yuan / yuan2fen
//  - 折扣券：后端 faceValue = 折扣×10（8.5 折 = 85），前端用 8.5
//  - EXPIRED 已过期不落库，由有效期日期在适配层派生
//  - 字段名 couponId→id、issuedQty→received、usedQty→used、grantScope→scope
//  - 写动作经网关，后端 400/409 中文错误经 errMsg() 外露到视图 formError
// ============================================================

export type CouponType = 'AMOUNT' | 'RATE' | 'PACKAGE'
export type CouponStatus = 'DRAFT' | 'ACTIVE' | 'EXPIRED' | 'DISABLED'
export type GrantScope = 'ALL' | 'NEW' | 'SEGMENT' | 'DESIGNATED'
export type GrantStatus = 'PENDING' | 'GRANTED' | 'FAILED'

/** 分 → 元 */
const fen2yuan = (fen?: number | null): number => (fen == null ? 0 : fen / 100)
/** 元 → 分 */
const yuan2fen = (yuan: number): number => Math.round((yuan || 0) * 100)
/** 后端折扣（折扣×10，如 85）→ 前端折扣（8.5） */
const rate2view = (v: number): number => (v ? v / 10 : 0)
/** 前端折扣（8.5）→ 后端折扣（85） */
const view2rate = (v: number): number => Math.round((v || 0) * 10)

/** 从 axios 错误中取后端中文 message（与全平台视图错误范式一致） */
export function errMsg(e: unknown, fallback = '网络异常，请稍后重试'): string {
  const anyE = e as { response?: { data?: { message?: string } }; message?: string }
  return anyE?.response?.data?.message || anyE?.message || fallback
}

/** yyyy-MM-dd（后端 LocalDate 直接可用；ISO 时间串兜底截取） */
function dayOf(s?: string | null): string {
  return s ? s.slice(0, 10) : ''
}

export interface CouponTemplate {
  id: string
  code?: string          // 券码（couponCode，核销/兼容层用；无券码回落 id）
  name: string
  type: CouponType
  value: number          // 满减金额(元) / 折扣率(如 8.5) / 券包子项总额(元)
  threshold: number      // 使用门槛(元)
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

/** 后端券模板 → 前端活规格（分→元、折扣×10→8.5、EXPIRED 日期派生） */
function adaptCoupon(d: CouponTemplateDTO): CouponTemplate {
  const days = d.validEnd ? Math.ceil((new Date(d.validEnd).getTime() - Date.now()) / 86400000) : 999
  let status = d.status as CouponStatus
  if (status === 'ACTIVE' && days < 0) status = 'EXPIRED'
  let packageItems: { name: string; value: number }[] | undefined
  if (d.packageItems) {
    try {
      const arr = JSON.parse(d.packageItems) as { name: string; value: number }[]
      packageItems = arr.map((it) => ({ name: it.name, value: fen2yuan(it.value) }))
    } catch {
      packageItems = undefined
    }
  }
  const value = d.couponType === 'RATE'
    ? rate2view(d.faceValue)
    : d.couponType === 'PACKAGE'
      ? (packageItems ? packageItems.reduce((s, it) => s + it.value, 0) : fen2yuan(d.faceValue))
      : fen2yuan(d.faceValue)
  return {
    id: d.couponId,
    code: d.couponCode || d.couponId,
    name: d.couponName,
    type: d.couponType as CouponType,
    value,
    threshold: fen2yuan(d.threshold),
    total: d.totalQty,
    received: d.issuedQty,
    used: d.usedQty,
    startDate: dayOf(d.validStart),
    endDate: dayOf(d.validEnd),
    status,
    scope: (d.grantScope || 'ALL') as GrantScope,
    scopeName: d.grantScopeName || '',
    campaignId: d.campaignId || undefined,
    packageItems,
  }
}

/** 后端发券记录 → 前端活规格 */
function adaptGrant(d: CouponGrantDTO): GrantRecord {
  return {
    id: d.grantId,
    couponId: d.couponId,
    couponName: d.couponName,
    scope: (d.grantScope || 'ALL') as GrantScope,
    targetName: d.targetName || '—',
    count: d.grantCount,
    status: (d.status === 'GRANTED' ? 'GRANTED' : 'FAILED') as GrantStatus,
    grantedAt: d.grantedAt ? d.grantedAt.slice(0, 16).replace('T', ' ') : '',
    operator: d.operator || '系统',
  }
}

export const useM5CouponStore = defineStore('m5Coupon', () => {
  const activity = useActivityStore()
  const auth = useAuthStore()

  const coupons = ref<CouponTemplate[]>([])
  const grants = ref<GrantRecord[]>([])
  const loaded = ref(false)

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

  /** 拉取真实券模板 + 发放记录（幂等：已加载默认不重复，force 用于写后重拉） */
  async function seed(force = false) {
    if (loaded.value && !force) return
    const [couponRes, grantRes] = await Promise.all([api.listCoupons(), api.listCouponGrants()])
    coupons.value = couponRes.data.map(adaptCoupon)
    grants.value = grantRes.data.map(adaptGrant)
    loaded.value = true
  }

  async function createCoupon(
    payload: Omit<CouponTemplate, 'id' | 'received' | 'used' | 'status'> & { status?: CouponStatus },
  ): Promise<CouponTemplate> {
    if (!auth.can('coupon:create')) throw new Error('无创建券权限')
    if (payload.total < 1) throw new Error('库存必须大于 0')
    if (payload.type === 'AMOUNT' && payload.value <= 0) throw new Error('满减金额必须大于 0')
    if (payload.type === 'RATE' && (payload.value <= 0 || payload.value >= 10)) throw new Error('折扣率应在 0-10 之间')
    const cmd: api.CouponCmd = {
      name: payload.name,
      type: payload.type,
      value: payload.type === 'RATE' ? view2rate(payload.value) : yuan2fen(payload.value),
      threshold: yuan2fen(payload.threshold),
      total: payload.total,
      scope: payload.scope,
      scopeName: payload.scopeName,
      startDate: payload.startDate,
      endDate: payload.endDate,
    }
    if (payload.type === 'PACKAGE' && payload.packageItems?.length) {
      cmd.packageItems = JSON.stringify(payload.packageItems.map((it) => ({ name: it.name, value: yuan2fen(it.value) })))
    }
    const res = await api.createCoupon(cmd)
    activity.log(auth.user?.name ?? '系统', `创建优惠券「${payload.name}」库存 ${payload.total}`, res.data.couponId)
    await seed(true)
    const created = coupons.value.find((c) => c.id === res.data.couponId)
    return created ?? adaptCoupon(res.data)
  }

  async function enableCoupon(id: string) {
    await api.enableCoupon(id)
    const c = coupons.value.find((x) => x.id === id)
    if (c) activity.log(auth.user?.name ?? '系统', `启用优惠券「${c.name}」`, id)
    await seed(true)
  }

  async function disableCoupon(id: string) {
    await api.disableCoupon(id)
    const c = coupons.value.find((x) => x.id === id)
    if (c) activity.log(auth.user?.name ?? '系统', `停用优惠券「${c.name}」`, id)
    await seed(true)
  }

  /**
   * 发放券（防超发在后端：synchronized + 库存二次校验）。
   * 库存发完后端返回 409（中文 message 外露）；部分发放正常落 GRANTED 记录。
   */
  async function grant(
    couponId: string, scope: GrantScope, targetName: string, count: number,
  ): Promise<GrantRecord> {
    if (!auth.can('coupon:edit')) throw new Error('无发券权限')
    const c = coupons.value.find((x) => x.id === couponId)
    if (!c) throw new Error('券不存在')
    const res = await api.grantCoupon(couponId, { scope, targetName, count })
    const rec = adaptGrant(res.data)
    activity.log(auth.user?.name ?? '系统',
      `发放「${c.name}」${rec.count} 张至 ${rec.targetName}`, rec.id)
    await seed(true)
    return rec
  }

  return {
    coupons, grants, stats,
    COUPON_TYPE_LABEL, COUPON_STATUS_LABEL, COUPON_STATUS_PILL, GRANT_STATUS_PILL,
    stockLeft, isExpiringSoon, createCoupon, enableCoupon, disableCoupon, grant, seed,
  }
})
