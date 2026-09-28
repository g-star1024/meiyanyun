/**
 * 会员 store（C 端）
 * 合并 B 端 points store 中 C 端所需部分：会员信息、卡余额/积分/券、
 * 积分商城商品、兑换记录、积分兑换动作。
 * C-B2 切真：seed() 拉取 GET /c/member/profile 覆盖会员字段（导出签名不变，铁律-1-B）；
 * C-B5 切真（DESIGN-C §四端点 #8）：卡包 GET /c/member/cards（次卡次数/储值分转元），
 * 商城商品 GET /c/mall/products（已上架投影），兑换记录 GET /c/mall/exchanges/mine，
 * 兑换 POST /c/mall/exchange（落库「待审核」，积分扣减在 B 端审核通过时生效，前端不预扣）。
 */
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { http } from '@/utils/request'

export interface Member {
  memberId: string
  name: string
  phone: string
  points: number
  cardBalance: number
  couponCount: number
  level: string
}

export type ProductCategory = 'PROJECT' | 'PHYSICAL' | 'COUPON' | 'SERVICE'
export type ProductStatus = 'ON_SALE' | 'OFF_SHELF' | 'LOW_STOCK'
export interface PointsProduct {
  id: string
  name: string
  category: ProductCategory
  pointsCost: number
  stock: number
  status: ProductStatus
  imageText: string
  /** C-B5：后端 mall_product 投影附带（cover 空时前端 imageText/首字兜底） */
  cover?: string
  description?: string
}
export interface RedemptionRecord {
  id: string
  orderNo: string
  productName: string
  pointsCost: number
  qty: number
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'FULFILLED'
  createdAt: string
}

/** C-B5：我的卡包项（GET /c/member/cards 投影；COURSE 次卡发次数，储值卡 balance/giftBalance 元） */
export interface MemberCard {
  cardNo: string
  name: string
  cardType: string
  totalTimes: number
  remainTimes: number
  balance: number
  giftBalance: number
  status: string
  /** 有效期（空串=长期有效） */
  expire: string
}

export const useMemberStore = defineStore('mp-member', () => {
  const member = ref<Member>({
    memberId: 'C-201',
    name: '陈美玲',
    phone: '138****1234',
    points: 8640,
    cardBalance: 12600,
    couponCount: 3,
    level: '黑金会员',
  })

  const products = ref<PointsProduct[]>([])
  const redemptions = ref<RedemptionRecord[]>([])
  /** C-B5：我的卡包（card 页卡项列表数据源） */
  const myCards = ref<MemberCard[]>([])

  const onSaleProducts = computed(() => products.value.filter((p) => p.status !== 'OFF_SHELF'))
  const myRedemptions = computed(() => redemptions.value)

  function getProduct(id: string) {
    return products.value.find((p) => p.id === id)
  }

  /**
   * 积分兑换（C-B5 async）：POST /c/mall/exchange，落库「待审核」，
   * 积分扣减在 B 端审核通过时生效（前端不预扣）；customer 中文错误（积分不足/库存不足/
   * 实物三要素缺失等）由 http 层 toast 原话弹出，此处如实返回失败原因。
   */
  async function redeem(productId: string, qty = 1): Promise<{ ok: boolean; reason?: string }> {
    const p = getProduct(productId)
    if (!p) return { ok: false, reason: '商品不存在' }
    try {
      const r = await http.post<RedemptionRecord>('/c/mall/exchange', { productId, qty })
      if (r) redemptions.value.unshift(r)
      return { ok: true }
    } catch (e) {
      return { ok: false, reason: e instanceof Error ? e.message : '兑换失败，请稍后重试' }
    }
  }

  let seeded = false
  async function seed() {
    if (seeded) return
    seeded = true
    try {
      const p = await http.get<Member>('/c/member/profile', { silent: true })
      if (p) {
        member.value = {
          memberId: p.memberId || '',
          name: p.name || '新会员',
          phone: p.phone || '',
          points: Number(p.points) || 0,
          cardBalance: Number(p.cardBalance) || 0,
          couponCount: Number(p.couponCount) || 0,
          level: p.level || '',
        }
      }
    } catch {
      /* 未登录/后端未就绪：保留本地默认档案，登录链路（C-B6）接通后消除 */
    }
    try {
      const [cards, prods, exs] = await Promise.all([
        http.get<MemberCard[]>('/c/member/cards', { silent: true }),
        http.get<PointsProduct[]>('/c/mall/products', { silent: true }),
        http.get<RedemptionRecord[]>('/c/mall/exchanges/mine', { silent: true }),
      ])
      if (Array.isArray(cards)) myCards.value = cards
      if (Array.isArray(prods)) products.value = prods
      if (Array.isArray(exs)) redemptions.value = exs
    } catch {
      /* 卡包/商城拉取失败：回滚 seeded 允许登录后 onShow 重拉（本地无假数据兜底） */
      seeded = false
    }
  }

  return { member, products, redemptions, myCards, onSaleProducts, myRedemptions, getProduct, redeem, seed }
})
