// ============================================================
// 卡项 / 疗程定义 store（M2-15）
// 商品定义侧（区别于客户资产侧 course store）：
// 门店售卖的卡项 / 疗程模板，含次数、有效期、价格、转赠、上下架。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

export type CatalogType = 'CARD' | 'COURSE'
export type CatalogStatus = 'ON_SHELF' | 'OFF_SHELF'

export interface CatalogProduct {
  id: string
  code: string
  name: string
  type: CatalogType
  category: string         // 如 抗衰卡 / 美肤疗程
  sessions: number         // 总次数（卡项通常 1，疗程多次）
  validityDays: number     // 有效期天数
  price: number            // 售价
  originalPrice: number    // 划线价
  transferable: boolean    // 是否允许转赠
  status: CatalogStatus
  includes: string[]       // 包含项目
  description: string
  updatedAt: string
  updatedBy: string
}

const TYPE_LABEL: Record<CatalogType, string> = { CARD: '卡项', COURSE: '疗程' }
const STATUS_LABEL: Record<CatalogStatus, string> = { ON_SHELF: '上架中', OFF_SHELF: '已下架' }
const STATUS_PILL: Record<CatalogStatus, 'success' | 'disabled'> = { ON_SHELF: 'success', OFF_SHELF: 'disabled' }

export const useCatalogStore = defineStore('catalog', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const items = ref<CatalogProduct[]>([])
  const filterType = ref<CatalogType | 'ALL'>('ALL')
  const filterStatus = ref<CatalogStatus | 'ALL'>('ALL')
  const keyword = ref('')

  const onShelf = computed(() => items.value.filter((x) => x.status === 'ON_SHELF'))
  const offShelf = computed(() => items.value.filter((x) => x.status === 'OFF_SHELF'))
  const cards = computed(() => items.value.filter((x) => x.type === 'CARD'))
  const courses = computed(() => items.value.filter((x) => x.type === 'COURSE'))

  const filtered = computed(() => {
    let list = items.value
    if (filterType.value !== 'ALL') list = list.filter((x) => x.type === filterType.value)
    if (filterStatus.value !== 'ALL') list = list.filter((x) => x.status === filterStatus.value)
    const kw = keyword.value.trim().toLowerCase()
    if (kw) list = list.filter((x) => x.name.toLowerCase().includes(kw) || x.code.toLowerCase().includes(kw))
    return [...list].sort((a, b) => a.code.localeCompare(b.code))
  })

  function get(id: string) {
    return items.value.find((x) => x.id === id)
  }

  function create(input: Omit<CatalogProduct, 'id' | 'updatedAt' | 'updatedBy' | 'code'> & { code?: string }): CatalogProduct | null {
    if (!auth.can('catalog:edit')) return null
    const now = new Date().toISOString()
    const seq = items.value.length + 1
    const p: CatalogProduct = {
      id: nextId('ct'),
      code: input.code || (input.type === 'CARD' ? `CD-${String(seq).padStart(3, '0')}` : `CS-${String(seq).padStart(3, '0')}`),
      name: input.name,
      type: input.type,
      category: input.category,
      sessions: input.sessions,
      validityDays: input.validityDays,
      price: input.price,
      originalPrice: input.originalPrice,
      transferable: input.transferable,
      status: input.status,
      includes: input.includes,
      description: input.description,
      updatedAt: now,
      updatedBy: auth.user.name,
    }
    items.value.unshift(p)
    activity.log(auth.user.name, `新建${TYPE_LABEL[p.type]}「${p.name}」`, p.id)
    return p
  }

  function update(id: string, patch: Partial<Pick<CatalogProduct,
    'name' | 'category' | 'sessions' | 'validityDays' | 'price' | 'originalPrice' |
    'transferable' | 'includes' | 'description'
  >>): boolean {
    const it = items.value.find((x) => x.id === id)
    if (!it || !auth.can('catalog:edit')) return false
    Object.assign(it, patch)
    it.updatedAt = new Date().toISOString()
    it.updatedBy = auth.user.name
    activity.log(auth.user.name, `编辑${TYPE_LABEL[it.type]}「${it.name}」`, it.id)
    return true
  }

  function toggleStatus(id: string): boolean {
    const it = items.value.find((x) => x.id === id)
    if (!it || !auth.can('catalog:edit')) return false
    it.status = it.status === 'ON_SHELF' ? 'OFF_SHELF' : 'ON_SHELF'
    it.updatedAt = new Date().toISOString()
    it.updatedBy = auth.user.name
    activity.log(auth.user.name, `${it.status === 'ON_SHELF' ? '上架' : '下架'}${TYPE_LABEL[it.type]}「${it.name}」`, it.id)
    return true
  }

  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    const now = new Date().toISOString()
    const data: Array<Omit<CatalogProduct, 'id'>> = [
      { code: 'CD-001', name: '焕颜抗衰储值卡', type: 'CARD', category: '储值卡', sessions: 1, validityDays: 365, price: 10000, originalPrice: 12000, transferable: true, status: 'ON_SHELF', includes: ['卡内余额 10000 元', '全场项目通用', '生日双倍积分'], description: '储值 1 万送 2 千，全场项目通用，有效期 1 年，支持亲友转赠。', updatedAt: now, updatedBy: '苏晴' },
      { code: 'CD-002', name: '闺蜜分享次卡', type: 'CARD', category: '次卡', sessions: 10, validityDays: 180, price: 3980, originalPrice: 5800, transferable: true, status: 'ON_SHELF', includes: ['基础水光 10 次', '可多人共用', '含面膜 10 片'], description: '10 次基础水光，支持与 1 位闺蜜共享，半年内有效。', updatedAt: now, updatedBy: '苏晴' },
      { code: 'CD-003', name: 'VIP 至尊年卡', type: 'CARD', category: '年卡', sessions: 1, validityDays: 365, price: 58800, originalPrice: 88800, transferable: false, status: 'ON_SHELF', includes: ['全年光电项目不限次', '专属皮肤管家', 'VIP 休息室', '生日月赠项目'], description: '全年光电类项目不限次，本人使用，含专属管家服务。', updatedAt: now, updatedBy: '苏晴' },
      { code: 'CS-001', name: '热玛吉紧致疗程', type: 'COURSE', category: '抗衰疗程', sessions: 3, validityDays: 365, price: 68800, originalPrice: 86400, transferable: false, status: 'ON_SHELF', includes: ['热玛吉 FLX 面部 3 次', '每次配术后修复面膜', 'VISIA 检测 2 次'], description: '3 次热玛吉面部紧致，分 3-6 个月完成，含术后护理。', updatedAt: now, updatedBy: '苏晴' },
      { code: 'CS-002', name: '光子嫩肤亮肤疗程', type: 'COURSE', category: '美肤疗程', sessions: 6, validityDays: 180, price: 8800, originalPrice: 11880, transferable: true, status: 'ON_SHELF', includes: ['M22 光子嫩肤 6 次', '小气泡清洁 2 次', '医用面膜 6 片'], description: '6 次光子嫩肤，改善肤色暗沉、毛孔粗大，半年有效。', updatedAt: now, updatedBy: '苏晴' },
      { code: 'CS-003', name: '瘦身塑形疗程', type: 'COURSE', category: '形体疗程', sessions: 8, validityDays: 120, price: 15800, originalPrice: 22400, transferable: false, status: 'ON_SHELF', includes: ['冷冻溶脂 4 部位', 'BTL 塑形 4 次', '体脂检测 3 次'], description: '4 个月完成，冷冻溶脂 + BTL 联合，定向塑形。', updatedAt: now, updatedBy: '苏晴' },
      { code: 'CS-004', name: '痘肌修复疗程', type: 'COURSE', category: '美肤疗程', sessions: 10, validityDays: 150, price: 6980, originalPrice: 9800, transferable: true, status: 'OFF_SHELF', includes: ['果酸焕肤 5 次', '红蓝光祛痘 5 次', '痘肌专用护理产品'], description: '针对中重度痘痘肌，10 次系统调理，已下架待升级新版。', updatedAt: now, updatedBy: '苏晴' },
      { code: 'CD-004', name: '体验官次卡', type: 'CARD', category: '次卡', sessions: 3, validityDays: 90, price: 598, originalPrice: 1280, transferable: false, status: 'OFF_SHELF', includes: ['小气泡 1 次', '光子嫩肤 1 次', '水光基础 1 次'], description: '新客体验卡，3 个项目 90 天内体验，活动结束已下架。', updatedAt: now, updatedBy: '苏晴' },
    ]
    data.forEach((d) => items.value.push({ id: nextId('ct'), ...d }))
  }

  return {
    items, filterType, filterStatus, keyword,
    onShelf, offShelf, cards, courses, filtered,
    get, create, update, toggleStatus, seed,
    TYPE_LABEL, STATUS_LABEL, STATUS_PILL,
  }
})
