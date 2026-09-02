// ============================================================
// 积分商城 store（M3-05/20）
// 商品管理 + 兑换审核 + 积分规则。
// 对齐设计稿 326:1 / 326:153：4 KPI + 商品表 + 审核队列 + 规则配置。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

export type ProductCategory = 'PROJECT' | 'PHYSICAL' | 'COUPON' | 'SERVICE'
export type ProductStatus = 'ON_SALE' | 'OFF_SHELF' | 'LOW_STOCK' | 'PENDING'

export interface PointsProduct {
  id: string
  sku: string
  name: string
  category: ProductCategory
  pointsCost: number
  stock: number
  redeemedCount: number
  status: ProductStatus
  imageText: string
  /** 商品说明（详情展示用，新建/编辑可填） */
  description?: string
}

export type RedemptionStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'FULFILLED'
export interface RedemptionRecord {
  id: string
  orderNo: string
  customerName: string
  productName: string
  pointsCost: number
  qty: number
  status: RedemptionStatus
  createdAt: string
  address?: string
  phone?: string
  note?: string
}

export interface PointsRule {
  earnPerYuan: number
  expireMonths: number
  signInReward: number
  birthdayMultiplier: number
  referralReward: number
  manualGrantEnabled: boolean
}

const CATEGORY_LABEL: Record<ProductCategory, string> = {
  PROJECT: '项目',
  PHYSICAL: '实物',
  COUPON: '优惠券',
  SERVICE: '服务',
}

const STATUS_LABEL: Record<ProductStatus, string> = {
  ON_SALE: '在售',
  OFF_SHELF: '已下架',
  LOW_STOCK: '低库存',
  PENDING: '审核中',
}

export const usePointsStore = defineStore('points', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const products = ref<PointsProduct[]>([])
  const redemptions = ref<RedemptionRecord[]>([])
  const rule = ref<PointsRule>({
    earnPerYuan: 1,
    expireMonths: 24,
    signInReward: 10,
    birthdayMultiplier: 2,
    referralReward: 500,
    manualGrantEnabled: true,
  })

  // 汇总
  const onSaleCount = computed(() => products.value.filter((p) => p.status === 'ON_SALE' || p.status === 'LOW_STOCK').length)
  const monthRedeemed = computed(() => redemptions.value.filter((r) => r.status !== 'REJECTED').reduce((s, r) => s + r.pointsCost * r.qty, 0))
  const pendingCount = computed(() => redemptions.value.filter((r) => r.status === 'PENDING').length)
  const totalPool = ref(8_640_000)

  const pendingRedemptions = computed(() => redemptions.value.filter((r) => r.status === 'PENDING'))

  // C 端会员上下文（演示用：一个登录会员）
  const member = ref({
    memberId: 'C-201',
    name: '陈美玲',
    phone: '138****1234',
    points: 8640,
    cardBalance: 12600,
    couponCount: 3,
  })
  const memberRedemptions = computed(() =>
    redemptions.value.filter((r) => r.customerName === member.value.name),
  )

  // 筛选
  const filterCategory = ref<ProductCategory | 'ALL'>('ALL')
  const filterStatus = ref<ProductStatus | 'ALL'>('ALL')
  const keyword = ref('')
  const filteredProducts = computed(() => {
    let list = products.value
    if (filterCategory.value !== 'ALL') list = list.filter((p) => p.category === filterCategory.value)
    if (filterStatus.value !== 'ALL') list = list.filter((p) => p.status === filterStatus.value)
    if (keyword.value.trim()) {
      const k = keyword.value.trim().toLowerCase()
      list = list.filter((p) => p.name.toLowerCase().includes(k) || p.sku.toLowerCase().includes(k))
    }
    return list
  })

  function get(id: string) {
    return products.value.find((p) => p.id === id)
  }

  function createProduct(input: Omit<PointsProduct, 'id' | 'sku' | 'redeemedCount' | 'status'>): PointsProduct | null {
    if (!auth.can('points:edit')) {
      console.warn('[points] 无 points:edit 权限')
      return null
    }
    const seq = products.value.length + 1
    const p: PointsProduct = {
      ...input,
      id: nextId('pt'),
      sku: `MALL-${String(seq).padStart(3, '0')}`,
      redeemedCount: 0,
      status: input.stock > 0 ? 'ON_SALE' : 'OFF_SHELF',
    }
    products.value.unshift(p)
    activity.log(auth.user.name, `新建积分商品 ${p.name}（${p.pointsCost} 分）`, p.id)
    return p
  }

  function updateProduct(id: string, patch: Partial<Pick<PointsProduct, 'name' | 'category' | 'pointsCost' | 'stock' | 'imageText'>>): boolean {
    const p = products.value.find((x) => x.id === id)
    if (!p || !auth.can('points:edit')) return false
    Object.assign(p, patch)
    if (patch.stock !== undefined) {
      if (patch.stock === 0) p.status = 'OFF_SHELF'
      else if (patch.stock <= 50 && p.status !== 'PENDING') p.status = 'LOW_STOCK'
      else if (p.status === 'OFF_SHELF' || p.status === 'LOW_STOCK') p.status = 'ON_SALE'
    }
    activity.log(auth.user.name, `编辑积分商品 ${p.name}`, p.id)
    return true
  }

  function toggleShelf(id: string): boolean {
    const p = products.value.find((x) => x.id === id)
    if (!p || !auth.can('points:edit')) return false
    if (p.status === 'OFF_SHELF') p.status = p.stock > 50 ? 'ON_SALE' : 'LOW_STOCK'
    else p.status = 'OFF_SHELF'
    activity.log(auth.user.name, `${p.status === 'OFF_SHELF' ? '下架' : '上架'}商品 ${p.name}`, p.id)
    return true
  }

  function approveRedemption(id: string): boolean {
    const r = redemptions.value.find((x) => x.id === id)
    if (!r || r.status !== 'PENDING' || !auth.can('points:approve')) return false
    r.status = 'APPROVED'
    activity.log(auth.user.name, `通过兑换 ${r.orderNo}（${r.customerName}）`, r.id)
    return true
  }
  function rejectRedemption(id: string, reason = '信息不全'): boolean {
    const r = redemptions.value.find((x) => x.id === id)
    if (!r || r.status !== 'PENDING' || !auth.can('points:approve')) return false
    r.status = 'REJECTED'
    r.note = reason
    activity.log(auth.user.name, `驳回兑换 ${r.orderNo}：${reason}`, r.id)
    return true
  }

  function saveRule(patch: Partial<PointsRule>): boolean {
    if (!auth.can('points:edit')) return false
    Object.assign(rule.value, patch)
    activity.log(auth.user.name, '保存积分规则')
    return true
  }

  function grantPoints(customerName: string, points: number, reason: string) {
    if (!auth.can('points:edit')) return false
    totalPool.value -= points
    activity.log(auth.user.name, `手动发放 ${points} 分给 ${customerName}：${reason}`)
    return true
  }

  /**
   * C 端会员兑换（B/C 联动 6-积分兑换）：
   * 1. 校验积分余额、库存
   * 2. 扣减会员积分、扣库存、加销量
   * 3. 生成 PENDING 兑换记录，进入 B 端 M3-20 审核队列
   * 不自动通过——必须由 B 端 approveRedemption 审核
   */
  function redeemFromMember(productId: string, qty = 1, address?: string, phone?: string): { ok: boolean; reason?: string; record?: RedemptionRecord } {
    const p = products.value.find((x) => x.id === productId)
    if (!p) return { ok: false, reason: '商品不存在' }
    if (p.status === 'OFF_SHELF') return { ok: false, reason: '商品已下架' }
    if (p.stock !== -1 && p.stock < qty) return { ok: false, reason: '库存不足' }
    const cost = p.pointsCost * qty
    if (member.value.points < cost) return { ok: false, reason: '积分不足' }

    member.value.points -= cost
    if (p.stock !== -1) p.stock -= qty
    p.redeemedCount += qty
    if (p.stock !== -1 && p.stock <= 50 && p.status === 'ON_SALE') p.status = 'LOW_STOCK'

    const seq = String(redemptions.value.length + 1).padStart(3, '0')
    const record: RedemptionRecord = {
      id: nextId('ex'),
      orderNo: `EX-C-${Date.now().toString().slice(-8)}-${seq}`,
      customerName: member.value.name,
      productName: p.name,
      pointsCost: p.pointsCost,
      qty,
      status: 'PENDING',
      createdAt: new Date().toISOString(),
      address,
      phone,
    }
    redemptions.value.unshift(record)
    activity.log('C端会员', `提交兑换 ${record.orderNo}（${p.name} ×${qty}），待 B 端审核`, record.id)
    return { ok: true, record }
  }

  /** B 端审核通过后履约通知（C 端可查到状态） */
  function fulfillRedemption(id: string): boolean {
    const r = redemptions.value.find((x) => x.id === id)
    if (!r || r.status !== 'APPROVED') return false
    r.status = 'FULFILLED'
    return true
  }

  /** 模拟 B 端收款后给 C 端累计积分（B/C 联动 2-积分累计） */
  function earnFromPurchase(customerName: string, amount: number) {
    const earn = Math.floor(amount * rule.value.earnPerYuan)
    if (customerName === member.value.name) member.value.points += earn
    totalPool.value -= earn
    return earn
  }

  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    const base: Array<Omit<PointsProduct, 'id' | 'sku'>> = [
      { name: '水光体验次卡', category: 'PROJECT', pointsCost: 2000, stock: 156, redeemedCount: 89, status: 'ON_SALE', imageText: '项目' },
      { name: '医用面膜 1 片装', category: 'PHYSICAL', pointsCost: 800, stock: 320, redeemedCount: 215, status: 'ON_SALE', imageText: '实物' },
      { name: '术后护理套装', category: 'PHYSICAL', pointsCost: 5800, stock: 42, redeemedCount: 23, status: 'LOW_STOCK', imageText: '实物' },
      { name: '清透防晒乳 SPF50+', category: 'PHYSICAL', pointsCost: 1500, stock: 0, redeemedCount: 178, status: 'OFF_SHELF', imageText: '实物' },
      { name: '满 500 减 100 优惠券', category: 'COUPON', pointsCost: 3000, stock: -1, redeemedCount: 45, status: 'ON_SALE', imageText: '券' },
      { name: 'VIP 专属皮肤检测 1 次', category: 'SERVICE', pointsCost: 1200, stock: 80, redeemedCount: 32, status: 'ON_SALE', imageText: '服务' },
      { name: '热玛吉体验券', category: 'PROJECT', pointsCost: 12000, stock: 20, redeemedCount: 8, status: 'LOW_STOCK', imageText: '项目' },
    ]
    base.forEach((b, i) => {
      products.value.push({ ...b, id: nextId('pt'), sku: `MALL-${String(i + 1).padStart(3, '0')}` })
    })
    const now = Date.now()
    const day = 86400_000
    const recBase: Array<Omit<RedemptionRecord, 'id'>> = [
      { orderNo: 'EX-20260825-001', customerName: '陈美玲', productName: '水光体验次卡', pointsCost: 2000, qty: 1, status: 'PENDING', createdAt: new Date(now - 1 * 3600_000).toISOString(), address: '上海市静安区南京西路 1266 号', phone: '138****1234' },
      { orderNo: 'EX-20260825-002', customerName: '赵雨晴', productName: '医用面膜 1 片装', pointsCost: 800, qty: 2, status: 'PENDING', createdAt: new Date(now - 3 * 3600_000).toISOString(), address: '上海市徐汇区淮海中路 999 号', phone: '139****5678' },
      { orderNo: 'EX-20260824-018', customerName: '孙佳宁', productName: '满 500 减 100 优惠券', pointsCost: 3000, qty: 1, status: 'PENDING', createdAt: new Date(now - 1 * day).toISOString() },
      { orderNo: 'EX-20260824-015', customerName: '王晓明', productName: 'VIP 专属皮肤检测 1 次', pointsCost: 1200, qty: 1, status: 'PENDING', createdAt: new Date(now - 1 * day - 3600_000).toISOString() },
      { orderNo: 'EX-20260824-009', customerName: '周岚', productName: '术后护理套装', pointsCost: 5800, qty: 1, status: 'PENDING', createdAt: new Date(now - 2 * day).toISOString(), address: '上海市浦东新区陆家嘴环路 1000 号', phone: '137****9012' },
      { orderNo: 'EX-20260823-031', customerName: '李娜', productName: '水光体验次卡', pointsCost: 2000, qty: 1, status: 'APPROVED', createdAt: new Date(now - 3 * day).toISOString() },
      { orderNo: 'EX-20260822-022', customerName: '吴桐', productName: '清透防晒乳 SPF50+', pointsCost: 1500, qty: 1, status: 'REJECTED', createdAt: new Date(now - 4 * day).toISOString(), note: '收货地址无法送达' },
    ]
    recBase.forEach((r) => redemptions.value.push({ ...r, id: nextId('ex') }))
  }

  return {
    products, redemptions, rule, member, memberRedemptions,
    onSaleCount, monthRedeemed, pendingCount, totalPool, pendingRedemptions,
    filterCategory, filterStatus, keyword, filteredProducts,
    get, createProduct, updateProduct, toggleShelf,
    approveRedemption, rejectRedemption, fulfillRedemption, saveRule, grantPoints,
    redeemFromMember, earnFromPurchase,
    seed, CATEGORY_LABEL, STATUS_LABEL,
  }
})
