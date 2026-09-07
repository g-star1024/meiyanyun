// ============================================================
// 卡项 / 疗程定义 store（M2-15，B15 接真实 API）
// 商品定义侧（区别于客户资产侧 course store）：
// 门店售卖的卡项 / 疗程模板，含次数、有效期、价格、转赠、上下架。
// 权威源：store-service /stores/catalog；store_code 空串 = 集团通用模板（全门店可见可售）。
// 读金额单位「元」（priceYuan/originalPriceYuan，后端已由分换算，includes 数组化）；
// 写金额单位「分」（*Fen），前端由「元」换算；productCode 后端生成（CD-/CS- 全局递增）。
// 查询走 catalog:view；新建/编辑/上下架走 catalog:edit。
// API 不可用或空库时回落本地演示数据（demo 标记）。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { useStoreContext } from './storeContext'
import {
  listCatalog,
  createCatalog as apiCreate,
  updateCatalog as apiUpdate,
  toggleCatalog as apiToggle,
  type CatalogProductDTO,
} from '@/api/catalog'

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
  price: number            // 售价（元）
  originalPrice: number    // 划线价（元）
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

const yuan2fen = (yuan: number) => Math.round((Number(yuan) || 0) * 100)

export const useCatalogStore = defineStore('catalog', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()
  const ctx = useStoreContext()

  const items = ref<CatalogProduct[]>([])
  const filterType = ref<CatalogType | 'ALL'>('ALL')
  const filterStatus = ref<CatalogStatus | 'ALL'>('ALL')
  const keyword = ref('')
  const loaded = ref(false)
  const demo = ref(false)

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

  // ---- DTO 映射（读金额「元」直接取用，id bigint → String） ----
  function mapDto(d: CatalogProductDTO): CatalogProduct {
    return {
      id: String(d.id),
      code: d.code || d.productCode,
      name: d.name,
      type: d.type,
      category: d.category || '',
      sessions: d.sessions ?? 1,
      validityDays: d.validityDays ?? 365,
      price: Number(d.priceYuan) || 0,
      originalPrice: Number(d.originalPriceYuan) || 0,
      transferable: !!d.transferable,
      status: d.status,
      includes: d.includes ?? [],
      description: d.description || '',
      updatedAt: d.updatedAt || '',
      updatedBy: d.updatedBy || '系统',
    }
  }

  // ---- 拉取（按 ctx.currentStoreCode，后端合并集团通用模板；失败回落演示数据） ----
  let loading: Promise<void> | null = null
  function load(force = false): Promise<void> {
    if (loading && !force) return loading
    if (loaded.value && !force) return Promise.resolve()
    loading = (async () => {
      try {
        await ctx.loadStores()
        const resp = await listCatalog({ storeCode: ctx.currentStoreCode })
        const list = resp.data ?? []
        if (list.length > 0) {
          items.value = list.map(mapDto)
          demo.value = false
        } else {
          loadDemo()
          demo.value = true
        }
        loaded.value = true
      } catch (e) {
        console.error('[catalog] 加载卡项疗程目录失败，回落本地演示数据', e)
        loadDemo()
        demo.value = true
        loaded.value = true
      }
    })()
    return loading
  }
  /** 兼容旧调用：seed 即 load */
  function seed(force = false) {
    return load(force)
  }
  void load()

  /** 新建模板（catalog:edit；编码后端生成；本批写集团通用模板 storeCode 空串）。 */
  async function create(
    input: Omit<CatalogProduct, 'id' | 'updatedAt' | 'updatedBy' | 'code'> & { code?: string },
  ): Promise<CatalogProduct | null> {
    if (!auth.can('catalog:edit')) {
      console.warn('[catalog] 无 catalog:edit 权限')
      return null
    }
    await apiCreate({
      storeCode: '',
      productType: input.type,
      name: input.name,
      category: input.category || null,
      sessions: Number(input.sessions) || 1,
      validityDays: Number(input.validityDays) || 365,
      priceFen: yuan2fen(input.price),
      originalPriceFen: yuan2fen(input.originalPrice),
      transferable: !!input.transferable,
      status: input.status,
      includes: input.includes ?? [],
      description: input.description || null,
    })
    activity.log(auth.user.name, `新建${TYPE_LABEL[input.type]}「${input.name}」`)
    await load(true)
    return items.value.find((x) => x.name === input.name) ?? null
  }

  /** 编辑模板（价格直接生效，本批无调价审批）。 */
  async function update(
    id: string,
    patch: Partial<Pick<CatalogProduct,
      'name' | 'category' | 'sessions' | 'validityDays' | 'price' | 'originalPrice' |
      'transferable' | 'includes' | 'description'
    >>,
  ): Promise<boolean> {
    const it = items.value.find((x) => x.id === id)
    if (!it || !auth.can('catalog:edit')) {
      console.warn('[catalog] 无 catalog:edit 权限或商品不存在')
      return false
    }
    await apiUpdate(Number(id), {
      storeCode: '',
      name: (patch.name ?? it.name).trim(),
      category: patch.category ?? it.category ?? null,
      sessions: Number(patch.sessions ?? it.sessions) || 1,
      validityDays: Number(patch.validityDays ?? it.validityDays) || 365,
      priceFen: yuan2fen(patch.price ?? it.price),
      originalPriceFen: yuan2fen(patch.originalPrice ?? it.originalPrice),
      transferable: patch.transferable ?? it.transferable,
      includes: patch.includes ?? it.includes,
      description: patch.description ?? it.description ?? null,
    })
    activity.log(auth.user.name, `编辑${TYPE_LABEL[it.type]}「${it.name}」`, it.id)
    await load(true)
    return true
  }

  /** 上架/下架切换（ON_SHELF ↔ OFF_SHELF）。 */
  async function toggleStatus(id: string): Promise<boolean> {
    const it = items.value.find((x) => x.id === id)
    if (!it || !auth.can('catalog:edit')) {
      console.warn('[catalog] 无 catalog:edit 权限或商品不存在')
      return false
    }
    const willOn = it.status === 'OFF_SHELF'
    await apiToggle(Number(id), { storeCode: '' })
    activity.log(auth.user.name, `${willOn ? '上架' : '下架'}${TYPE_LABEL[it.type]}「${it.name}」`, it.id)
    await load(true)
    return true
  }

  function loadDemo() {
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
    items.value = data.map((d) => ({ id: nextId('ct'), ...d }))
  }

  return {
    items, filterType, filterStatus, keyword, loaded, demo,
    onShelf, offShelf, cards, courses, filtered,
    get, load, seed, create, update, toggleStatus,
    TYPE_LABEL, STATUS_LABEL, STATUS_PILL,
  }
})
