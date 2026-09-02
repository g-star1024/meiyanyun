/**
 * 会员 store（C 端）
 * 合并 B 端 points store 中 C 端所需部分：会员信息、卡余额/积分/券、
 * 积分商城商品、兑换记录、积分兑换动作。
 * 后端就绪后把 seed() 替换为 GET /c/member、/c/points/products 等接口。
 */
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

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

let seq = 0
function nextId(prefix: string) {
  seq += 1
  return `${prefix}-${Date.now().toString(36)}-${seq}`
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

  const onSaleProducts = computed(() => products.value.filter((p) => p.status !== 'OFF_SHELF'))
  const myRedemptions = computed(() => redemptions.value)

  function getProduct(id: string) {
    return products.value.find((p) => p.id === id)
  }

  /** 积分兑换（演示：本地扣积分 + 生成待审核记录，后端就绪后改调接口） */
  function redeem(productId: string, qty = 1): { ok: boolean; reason?: string } {
    const p = getProduct(productId)
    if (!p) return { ok: false, reason: '商品不存在' }
    if (p.status === 'OFF_SHELF') return { ok: false, reason: '商品已下架' }
    if (p.stock !== -1 && p.stock < qty) return { ok: false, reason: '库存不足' }
    const cost = p.pointsCost * qty
    if (member.value.points < cost) return { ok: false, reason: '积分不足' }

    member.value.points -= cost
    if (p.stock > 0) p.stock -= qty
    redemptions.value.unshift({
      id: nextId('ex'),
      orderNo: `EX-${Date.now()}`,
      productName: p.name,
      pointsCost: cost,
      qty,
      status: 'PENDING',
      createdAt: new Date().toISOString(),
    })
    return { ok: true }
  }

  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    const base: Array<Omit<PointsProduct, 'id'>> = [
      { name: '水光体验次卡', category: 'PROJECT', pointsCost: 2000, stock: 156, status: 'ON_SALE', imageText: '项目' },
      { name: '医用面膜 1 片装', category: 'PHYSICAL', pointsCost: 800, stock: 320, status: 'ON_SALE', imageText: '实物' },
      { name: '术后护理套装', category: 'PHYSICAL', pointsCost: 5800, stock: 42, status: 'LOW_STOCK', imageText: '实物' },
      { name: '清透防晒乳 SPF50+', category: 'PHYSICAL', pointsCost: 1500, stock: 0, status: 'OFF_SHELF', imageText: '实物' },
      { name: '满 500 减 100 优惠券', category: 'COUPON', pointsCost: 3000, stock: -1, status: 'ON_SALE', imageText: '券' },
      { name: 'VIP 专属皮肤检测 1 次', category: 'SERVICE', pointsCost: 1200, stock: 80, status: 'ON_SALE', imageText: '服务' },
      { name: '热玛吉体验券', category: 'PROJECT', pointsCost: 12000, stock: 20, status: 'LOW_STOCK', imageText: '项目' },
    ]
    base.forEach((b) => products.value.push({ id: nextId('pt'), ...b }))
  }

  return { member, products, redemptions, onSaleProducts, myRedemptions, getProduct, redeem, seed }
})
