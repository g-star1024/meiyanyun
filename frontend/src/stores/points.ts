// ============================================================
// 积分商城 store（M3-05/20）
// 商品管理 + 兑换审核 + 积分规则。
// 对齐设计稿 326:1 / 326:153：4 KPI + 商品表 + 审核队列 + 规则配置。
//
// 双数据源（铁律：链路先行，mock 是活规格）：
// - B 端 PointsMallView：onMounted 调 load()，商品/兑换单/规则全部走 customer-service
//   /customer/mall/* 真实接口；后端库内枚举为中文（已上架/已下架、项目/实物…、
//   待审核/已通过/已拒绝/已发放），本 store 适配层映射英文码喂前端字典。
// - C 端 11 个移动页：继续调 seed() 使用演示会员（陈美玲 C-201）本地 mock，
//   redeemFromMember/earnFromPurchase/grantPoints 保持纯前端联动不接真实接口
//   （C 端真实兑换/积分累计依赖事件流，列入后续 Backlog）。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { errMsg } from './m5Coupon'
import {
  listMallProducts, listMallExchanges, getMallRule, getPointsPool,
  createMallProduct, editMallProduct, toggleMallProduct, adjustMallProduct,
  saveMallRule, reviewMallExchange, fulfillMallExchange,
  type MallProductDTO, type MallExchangeDTO, type PointRuleDTO,
} from '@/api/customer'

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
  /** 积分抵扣比例（百分比；表单无此格，后端原值回传） */
  redeemRatio: number
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

/** 商品封面占位字（无 cover 时按分类派生） */
export const CATEGORY_IMG: Record<ProductCategory, string> = {
  PROJECT: '项目',
  PHYSICAL: '实物',
  COUPON: '券',
  SERVICE: '服务',
}

/** 后端中文类型 → 前端英文码（normalizeType 后接口只会回中文） */
function typeFromBack(t: string): ProductCategory {
  switch ((t || '').trim()) {
    case '项目': return 'PROJECT'
    case '实物': return 'PHYSICAL'
    case '优惠券': case '券': return 'COUPON'
    case '服务': return 'SERVICE'
    default: return 'PROJECT'
  }
}

/** 后端中文状态 + 库存 → 前端状态码（低库存≤50 为前端派生，不入库） */
function statusFromBack(status: string, stock: number): ProductStatus {
  if ((status || '').trim() === '已下架' || stock === 0) return 'OFF_SHELF'
  if (stock !== -1 && stock <= 50) return 'LOW_STOCK'
  return 'ON_SALE'
}

/** 后端兑换单中文状态 → 前端英文码 */
function exchangeStatusFromBack(s: string): RedemptionStatus {
  switch ((s || '').trim()) {
    case '已通过': return 'APPROVED'
    case '已拒绝': return 'REJECTED'
    case '已发放': return 'FULFILLED'
    case '待审核':
    default: return 'PENDING'
  }
}

function mapProduct(d: MallProductDTO): PointsProduct {
  const category = typeFromBack(d.productType)
  const stock = d.stock ?? 0
  return {
    id: d.productId,
    sku: d.productId,
    name: d.productName,
    category,
    pointsCost: d.pointsPrice,
    stock,
    redeemedCount: d.redeemedCount ?? 0,
    status: statusFromBack(d.status, stock),
    imageText: d.cover || CATEGORY_IMG[category],
    description: d.description || undefined,
  }
}

function mapExchange(d: MallExchangeDTO): RedemptionRecord {
  const qty = d.qty || 1
  return {
    id: d.exchangeId,
    orderNo: d.exchangeId,
    customerName: d.customerName || d.customerId,
    productName: d.productName || d.productId,
    pointsCost: qty > 0 ? Math.round((d.pointsSpent || 0) / qty) : (d.pointsSpent || 0),
    qty,
    status: exchangeStatusFromBack(d.status),
    createdAt: d.createdAt,
    address: d.shipAddress || undefined,
    phone: d.shipPhone || undefined,
    note: d.rejectReason || undefined,
  }
}

function mapRule(d: PointRuleDTO): PointsRule {
  return {
    earnPerYuan: Number(d.earnRate ?? 1),
    redeemRatio: Number(d.redeemRatio ?? 100),
    expireMonths: d.expireMonths ?? 12,
    signInReward: d.signInReward ?? 10,
    birthdayMultiplier: Number(d.birthdayMultiplier ?? 2),
    referralReward: d.referralReward ?? 500,
    manualGrantEnabled: d.manualGrantEnabled ?? true,
  }
}

export const usePointsStore = defineStore('points', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const products = ref<PointsProduct[]>([])
  const redemptions = ref<RedemptionRecord[]>([])
  const rule = ref<PointsRule>({
    earnPerYuan: 1,
    redeemRatio: 100,
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

  // ==================== B 端：真实 API（/customer/mall/*） ====================

  /** B 端加载：商品 + 兑换单 + 积分规则 + 积分池统计并行拉取（兑换单后端已注入客户名/商品名） */
  async function load() {
    const [pRes, eRes, rRes, poolRes] = await Promise.all([
      listMallProducts(),
      listMallExchanges(),
      getMallRule(),
      getPointsPool(),
    ])
    products.value = (pRes.data || []).map(mapProduct)
    redemptions.value = (eRes.data || []).map(mapExchange)
    if (rRes.data) rule.value = mapRule(rRes.data)
    // 积分池读模型：累计发放（B23 卡2 起接真实统计，替换写死演示值）
    if (poolRes.data) totalPool.value = poolRes.data.totalIssued
  }

  /** 新建商品（后端发单号 MP+日期-序号；stock=0 待上架，>0/-1 直接上架） */
  async function createProduct(input: Omit<PointsProduct, 'id' | 'sku' | 'redeemedCount' | 'status'>): Promise<PointsProduct> {
    if (!auth.can('points:edit')) throw new Error('无积分商品编辑权限')
    try {
      const { data } = await createMallProduct({
        name: input.name,
        type: input.category,
        pointsPrice: input.pointsCost,
        stock: input.stock,
        cover: input.imageText,
        description: input.description,
      })
      const p = mapProduct(data)
      products.value.unshift(p)
      activity.log(auth.user.name, `新建积分商品 ${p.name}（${p.pointsCost} 分）`, p.id)
      return p
    } catch (e) {
      throw new Error(errMsg(e, '新建商品失败，请稍后重试'))
    }
  }

  /**
   * 编辑商品资料（名称/类型/定价/说明/封面走 PUT；库存单独走 /adjust）。
   * patch.stock 与当前不同时追加一次调整调用（库存 0 在售后端自动下架）。
   */
  async function updateProduct(
    id: string,
    patch: Partial<Pick<PointsProduct, 'name' | 'category' | 'pointsCost' | 'stock' | 'imageText' | 'description'>>,
  ): Promise<boolean> {
    const p = products.value.find((x) => x.id === id)
    if (!p) throw new Error('商品不存在')
    if (!auth.can('points:edit')) throw new Error('无积分商品编辑权限')
    try {
      const name = patch.name ?? p.name
      const category = patch.category ?? p.category
      const pointsCost = patch.pointsCost ?? p.pointsCost
      const { data } = await editMallProduct(id, {
        name,
        type: category,
        pointsPrice: pointsCost,
        cover: patch.imageText ?? CATEGORY_IMG[category],
        description: patch.description ?? p.description,
      })
      let updated = mapProduct(data)
      if (patch.stock !== undefined && patch.stock !== p.stock) {
        const adj = await adjustMallProduct(id, { stock: patch.stock })
        updated = mapProduct(adj.data)
      }
      const idx = products.value.findIndex((x) => x.id === id)
      if (idx >= 0) products.value[idx] = updated
      activity.log(auth.user.name, `编辑积分商品 ${updated.name}`, id)
      return true
    } catch (e) {
      throw new Error(errMsg(e, '编辑商品失败，请稍后重试'))
    }
  }

  /** 上下架切换（库存 0 上架后端 422；幂等） */
  async function toggleShelf(id: string): Promise<boolean> {
    const p = products.value.find((x) => x.id === id)
    if (!p) throw new Error('商品不存在')
    if (!auth.can('points:edit')) throw new Error('无积分商品编辑权限')
    try {
      const { data } = await toggleMallProduct(id)
      const updated = mapProduct(data)
      const idx = products.value.findIndex((x) => x.id === id)
      if (idx >= 0) products.value[idx] = updated
      activity.log(auth.user.name, `${updated.status === 'OFF_SHELF' ? '下架' : '上架'}商品 ${updated.name}`, id)
      return true
    } catch (e) {
      throw new Error(errMsg(e, '上下架操作失败，请稍后重试'))
    }
  }

  /**
   * 双签审核通过：店长初审 sign1 + 运营复核 sign2（两签不得同一人，后端强校验）。
   * 通过时后端扣库存/销量、扣客户积分；积分或库存不足返回 422。
   */
  async function approveRedemption(
    id: string,
    sign: { sign1: string; sign1Role?: string; sign2: string; sign2Role?: string },
  ): Promise<boolean> {
    const r = redemptions.value.find((x) => x.id === id)
    if (!r) throw new Error('兑换单不存在')
    if (r.status !== 'PENDING') throw new Error('兑换单已审核，不可重复操作')
    if (!auth.can('points:approve')) throw new Error('无兑换审核权限')
    try {
      const { data } = await reviewMallExchange(id, { ...sign, reject: false })
      const idx = redemptions.value.findIndex((x) => x.id === id)
      if (idx >= 0) redemptions.value[idx] = mapExchange(data)
      activity.log(auth.user.name, `双签通过兑换 ${r.orderNo}（${r.customerName}）：${sign.sign1}、${sign.sign2}`, id)
      return true
    } catch (e) {
      throw new Error(errMsg(e, '审核通过失败，请稍后重试'))
    }
  }

  /** 双签审核驳回（须填驳回原因；后端落 rejectReason） */
  async function rejectRedemption(
    id: string,
    reason: string,
    sign: { sign1: string; sign1Role?: string; sign2: string; sign2Role?: string },
  ): Promise<boolean> {
    const r = redemptions.value.find((x) => x.id === id)
    if (!r) throw new Error('兑换单不存在')
    if (r.status !== 'PENDING') throw new Error('兑换单已审核，不可重复操作')
    if (!auth.can('points:approve')) throw new Error('无兑换审核权限')
    try {
      const { data } = await reviewMallExchange(id, { ...sign, reject: true, rejectReason: reason })
      const idx = redemptions.value.findIndex((x) => x.id === id)
      if (idx >= 0) redemptions.value[idx] = mapExchange(data)
      activity.log(auth.user.name, `双签驳回兑换 ${r.orderNo}：${reason}（${sign.sign1}、${sign.sign2}）`, id)
      return true
    } catch (e) {
      throw new Error(errMsg(e, '驳回操作失败，请稍后重试'))
    }
  }

  /** 履约发放（仅「已通过」可履约；「已发放」后端幂等直返） */
  async function fulfillRedemption(id: string): Promise<boolean> {
    const r = redemptions.value.find((x) => x.id === id)
    if (!r) throw new Error('兑换单不存在')
    if (r.status === 'FULFILLED') return true
    if (r.status !== 'APPROVED') throw new Error('仅「已通过」的兑换单可履约发放')
    if (!auth.can('points:approve')) throw new Error('无兑换履约权限')
    try {
      const { data } = await fulfillMallExchange(id)
      const idx = redemptions.value.findIndex((x) => x.id === id)
      if (idx >= 0) redemptions.value[idx] = mapExchange(data)
      activity.log(auth.user.name, `履约发放兑换 ${r.orderNo}（${r.customerName}）`, id)
      return true
    } catch (e) {
      throw new Error(errMsg(e, '履约发放失败，请稍后重试'))
    }
  }

  /** 保存积分规则（redeemRatio 表单无此格，取库内原值回传） */
  async function saveRule(patch: Partial<PointsRule>): Promise<boolean> {
    if (!auth.can('points:edit')) throw new Error('无积分规则编辑权限')
    const next = { ...rule.value, ...patch }
    try {
      const { data } = await saveMallRule({
        earnRate: next.earnPerYuan,
        redeemRatio: next.redeemRatio,
        expireMonths: next.expireMonths,
        signInReward: next.signInReward,
        birthdayMultiplier: next.birthdayMultiplier,
        referralReward: next.referralReward,
        manualGrantEnabled: next.manualGrantEnabled,
      })
      if (data) rule.value = mapRule(data)
      else rule.value = next
      activity.log(auth.user.name, '保存积分规则')
      return true
    } catch (e) {
      throw new Error(errMsg(e, '保存规则失败，请稍后重试'))
    }
  }

  // ==================== C 端：演示 mock（移动页使用，不接真实接口） ====================

  function grantPoints(customerName: string, points: number, reason: string) {
    if (!auth.can('points:edit')) return false
    totalPool.value -= points
    activity.log(auth.user.name, `手动发放 ${points} 分给 ${customerName}：${reason}`)
    return true
  }

  /**
   * C 端会员兑换（B/C 联动 6-积分兑换，演示 mock）：
   * 1. 校验积分余额、库存
   * 2. 扣减会员积分、扣库存、加销量
   * 3. 生成 PENDING 兑换记录，进入 B 端 M3-20 审核队列
   * 不自动通过——必须由 B 端审核（真实链路走 placeMallExchange，依赖 C 端登录态，列 Backlog）
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

  /** 模拟 B 端收款后给 C 端累计积分（B/C 联动 2-积分累计，演示 mock） */
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
    get, load, createProduct, updateProduct, toggleShelf,
    approveRedemption, rejectRedemption, fulfillRedemption, saveRule, grantPoints,
    redeemFromMember, earnFromPurchase,
    seed, CATEGORY_LABEL, STATUS_LABEL, CATEGORY_IMG,
  }
})
