// ============================================================
// 品牌品类 store（M1 集团管控 / 品牌品类，B14 接真实 API）
// 三级主数据：品牌 Brand -> 品类 Category -> 项目/产品 Product(SKU)
// - 品牌：上游厂商/供应商品牌（如 艾尔建、华熙生物），可启停
// - 品类：项目分类（注射类/光电类/护肤类/手术类），归属品牌
// - 项目：可售卖 SKU，挂牌价/成本/单位/状态/适用门店类型/服务大类/风险标签
// 权威源：store-service /stores/brands|categories|skus（集团级，无 storeCode）。
// 读金额单位「元」（listPrice/costPrice，后端已由分换算）；
// 写金额单位「分」（listPriceFen/costPriceFen），前端由「元」换算。
// API 不可用或空库时回落本地演示数据（demo 标记，仅控制台告警）。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import {
  listBrands, listCategories, listSkus,
  createBrand as apiCreateBrand, updateBrand as apiUpdateBrand, setBrandStatus as apiSetBrandStatus,
  createCategory as apiCreateCategory, updateCategory as apiUpdateCategory,
  setCategoryStatus as apiSetCategoryStatus, deleteCategory as apiDeleteCategory,
  createSku as apiCreateSku, updateSku as apiUpdateSku, setSkuStatus as apiSetSkuStatus,
  type BrandDTO, type CategoryDTO, type SkuDTO,
} from '@/api/projectMaster'
import { useAuthStore } from './auth'

export type CommonStatus = 'ACTIVE' | 'INACTIVE'
export const STATUS_LABEL: Record<CommonStatus, string> = { ACTIVE: '启用', INACTIVE: '停用' }

export interface Brand {
  id: string
  code: string
  name: string
  shortName?: string
  origin?: string // 产地
  supplier: string // 供应商
  status: CommonStatus
  logoColor: string // 头像色（后端为空时前端轮色兜底）
  remark?: string
  createdAt: string
  /** 后端聚合统计（品类数/SKU 数/启用 SKU 数/平均挂牌价，金额元） */
  stats?: { categoryCount: number; productCount: number; activeProductCount: number; avgListPrice: number }
}

export interface Category {
  id: string
  code: string
  name: string
  brandId: string
  parentId?: string // 支持二级品类
  status: CommonStatus
  sort: number
  remark?: string
}

export interface Product {
  id: string
  sku: string
  name: string
  brandId: string
  categoryId: string
  unit: string // 单位：次/支/盒/部位
  listPrice: number // 挂牌价（元）
  costPrice: number // 成本价（元）
  status: CommonStatus
  storeTypes: string[] // 适用门店类型 FLAGSHIP/COMMUNITY/CLINIC
  durationMin: number // 预计时长（分钟）
  serviceCategory?: string // 服务大类 INJECTION/LASER/SKINCARE/BODY/EXAM（E2，后端富化价目用）
  riskTags?: string[] // 医疗风险标签（面诊禁忌初筛用）
  remark?: string
  createdAt: string
}

let _cid = 0
function cid(p: string) { _cid += 1; return `${p}-${Date.now().toString(36)}-${_cid}` }
function now() { return new Date().toISOString() }

const yuan2fen = (yuan: number) => Math.round((Number(yuan) || 0) * 100)
const BRAND_COLORS = ['#FF6B9D', '#5B8DEF', '#22C55E', '#F59E0B', '#8B5CF6', '#06B6D4']

export const useM1BrandStore = defineStore('m1Brand', () => {
  const auth = useAuthStore()

  const brands = ref<Brand[]>([])
  const categories = ref<Category[]>([])
  const products = ref<Product[]>([])
  const loaded = ref(false)
  const demo = ref(false)

  // ---- 派生 ----
  const activeBrands = computed(() => brands.value.filter((b) => b.status === 'ACTIVE'))

  function categoriesOf(brandId: string) {
    return categories.value.filter((c) => c.brandId === brandId)
  }
  function productsOf(brandId: string) {
    return products.value.filter((p) => p.brandId === brandId)
  }
  function productsOfCategory(categoryId: string) {
    return products.value.filter((p) => p.categoryId === categoryId)
  }
  function brand(id: string) { return brands.value.find((b) => b.id === id) }
  function category(id: string) { return categories.value.find((c) => c.id === id) }

  // 品牌统计：优先用后端聚合 stats，演示/缺失时本地计算
  function brandStats(brandId: string) {
    const b = brand(brandId)
    if (b?.stats) return b.stats
    const cats = categoriesOf(brandId)
    const prods = productsOf(brandId)
    const activeProds = prods.filter((p) => p.status === 'ACTIVE')
    const avg = activeProds.length ? Math.round(activeProds.reduce((s, p) => s + p.listPrice, 0) / activeProds.length) : 0
    return {
      categoryCount: cats.length,
      productCount: prods.length,
      activeProductCount: activeProds.length,
      avgListPrice: avg,
    }
  }

  const stats = computed(() => {
    const activeProd = products.value.filter((p) => p.status === 'ACTIVE').length
    const totalValue = products.value.filter((p) => p.status === 'ACTIVE').reduce((s, p) => s + p.listPrice, 0)
    return {
      brandCount: brands.value.length,
      activeBrand: activeBrands.value.length,
      categoryCount: categories.value.length,
      productCount: products.value.length,
      activeProduct: activeProd,
      avgListPrice: activeProd ? Math.round(totalValue / activeProd) : 0,
    }
  })

  // ---- DTO 映射（金额元直接取用，id number→string） ----
  function mapBrand(d: BrandDTO): Brand {
    return {
      id: String(d.id),
      code: d.code,
      name: d.name,
      shortName: d.shortName || undefined,
      origin: d.origin || undefined,
      supplier: d.supplier || '',
      status: d.status,
      logoColor: d.logoColor || BRAND_COLORS[brands.value.length % BRAND_COLORS.length],
      remark: d.remark || undefined,
      createdAt: d.createdAt || now(),
      stats: d.stats
        ? {
            categoryCount: Number(d.stats.categoryCount) || 0,
            productCount: Number(d.stats.productCount) || 0,
            activeProductCount: Number(d.stats.activeProductCount) || 0,
            avgListPrice: Number(d.stats.avgListPriceYuan) || 0,
          }
        : undefined,
    }
  }

  function mapCategory(d: CategoryDTO): Category {
    return {
      id: String(d.id),
      code: d.code,
      name: d.name,
      brandId: String(d.brandId),
      parentId: d.parentId == null ? undefined : String(d.parentId),
      status: d.status,
      sort: d.sort ?? 0,
      remark: d.remark || undefined,
    }
  }

  function mapProduct(d: SkuDTO): Product {
    return {
      id: String(d.id),
      sku: d.sku,
      name: d.name,
      brandId: String(d.brandId),
      categoryId: String(d.categoryId),
      unit: d.unit || '次',
      listPrice: Number(d.listPriceYuan) || 0,
      costPrice: Number(d.costPriceYuan) || 0,
      status: d.status,
      storeTypes: d.storeTypes ?? [],
      durationMin: d.durationMin ?? 0,
      serviceCategory: d.serviceCategory || undefined,
      riskTags: d.riskTags ?? [],
      remark: d.remark || undefined,
      createdAt: d.createdAt || now(),
    }
  }

  // ---- 拉取（集团级，不带 storeCode；失败回落演示数据） ----
  let loading: Promise<void> | null = null
  function load(force = false): Promise<void> {
    if (loading && !force) return loading
    if (loaded.value && !force) return Promise.resolve()
    loading = (async () => {
      try {
        const [brandResp, catResp, skuResp] = await Promise.all([
          listBrands(), listCategories(), listSkus(),
        ])
        const brandList = brandResp.data ?? []
        if (brandList.length > 0) {
          brands.value = brandList.map(mapBrand)
          categories.value = (catResp.data ?? []).map(mapCategory)
          products.value = (skuResp.data ?? []).map(mapProduct)
          demo.value = false
        } else {
          loadDemo()
          demo.value = true
        }
        loaded.value = true
      } catch (e) {
        console.error('[m1Brand] 加载项目目录失败，回落本地演示数据', e)
        loadDemo()
        demo.value = true
        loaded.value = true
      }
    })()
    return loading
  }
  /** 兼容旧视图调用名（内部即 load）。 */
  function seed() { return load() }
  void load()

  // ---- 品牌 CRUD（真实持久化；写后强制刷新） ----
  async function createBrand(b: {
    code: string; name: string; shortName?: string; origin?: string
    supplier: string; remark?: string; status?: CommonStatus
  }): Promise<Brand> {
    if (!auth.can('brand:edit')) { console.warn('[m1Brand] 无 brand:edit 权限'); throw new Error('无品牌建档权限') }
    const logoColor = BRAND_COLORS[brands.value.length % BRAND_COLORS.length]
    const resp = await apiCreateBrand({
      code: b.code, name: b.name,
      shortName: b.shortName || undefined, origin: b.origin || undefined,
      supplier: b.supplier, logoColor, remark: b.remark || undefined, status: b.status,
    })
    await load(true)
    const created = brands.value.find((x) => x.id === String(resp.data.id))
    return created ?? {
      id: String(resp.data.id), code: resp.data.code, name: resp.data.name,
      supplier: b.supplier, status: b.status ?? 'ACTIVE', logoColor, createdAt: now(),
    }
  }

  async function updateBrand(id: string, patch: Partial<Brand>): Promise<void> {
    if (!auth.can('brand:edit')) { console.warn('[m1Brand] 无 brand:edit 权限'); return }
    await apiUpdateBrand(Number(id), {
      name: patch.name, shortName: patch.shortName, origin: patch.origin,
      supplier: patch.supplier, remark: patch.remark,
    })
    await load(true)
  }

  async function setBrandStatus(id: string, status: CommonStatus): Promise<void> {
    if (!auth.can('brand:edit')) { console.warn('[m1Brand] 无 brand:edit 权限'); return }
    await apiSetBrandStatus(Number(id), { status })
    await load(true)
  }

  // ---- 品类 CRUD ----
  async function createCategory(c: {
    code: string; name: string; brandId: string; parentId?: string; sort?: number; remark?: string
  }): Promise<void> {
    if (!auth.can('brand:edit')) { console.warn('[m1Brand] 无 brand:edit 权限'); return }
    await apiCreateCategory({
      code: c.code, name: c.name, brandId: Number(c.brandId),
      parentId: c.parentId ? Number(c.parentId) : undefined, sort: c.sort, remark: c.remark || undefined,
    })
    await load(true)
  }

  async function updateCategory(id: string, patch: Partial<Category>): Promise<void> {
    if (!auth.can('brand:edit')) { console.warn('[m1Brand] 无 brand:edit 权限'); return }
    await apiUpdateCategory(Number(id), {
      name: patch.name, parentId: patch.parentId ? Number(patch.parentId) : undefined,
      sort: patch.sort, remark: patch.remark,
    })
    await load(true)
  }

  async function setCategoryStatus(id: string, status: CommonStatus): Promise<void> {
    if (!auth.can('brand:edit')) { console.warn('[m1Brand] 无 brand:edit 权限'); return }
    await apiSetCategoryStatus(Number(id), { status })
    await load(true)
  }

  /** 删除品类；后端校验有子品类/挂载项目时 422（中文 message 由调用方 toast 透出）。 */
  async function deleteCategory(id: string): Promise<void> {
    if (!auth.can('brand:edit')) { console.warn('[m1Brand] 无 brand:edit 权限'); return }
    await apiDeleteCategory(Number(id))
    await load(true)
  }

  // ---- 项目 SKU CRUD（更新不覆盖 serviceCategory/riskTags；金额元→分） ----
  async function createProduct(p: {
    sku: string; name: string; brandId: string; categoryId: string; unit: string
    listPrice: number; costPrice: number; storeTypes: string[]
    durationMin: number; serviceCategory?: string; remark?: string; status?: CommonStatus
  }): Promise<void> {
    if (!auth.can('brand:edit')) { console.warn('[m1Brand] 无 brand:edit 权限'); return }
    await apiCreateSku({
      sku: p.sku, name: p.name, brandId: Number(p.brandId), categoryId: Number(p.categoryId),
      unit: p.unit, listPriceFen: yuan2fen(p.listPrice), costPriceFen: yuan2fen(p.costPrice),
      storeTypes: p.storeTypes, durationMin: p.durationMin || 0,
      serviceCategory: p.serviceCategory || undefined, remark: p.remark || undefined,
    })
    await load(true)
  }

  async function updateProduct(id: string, patch: Partial<Product>): Promise<void> {
    if (!auth.can('brand:edit')) { console.warn('[m1Brand] 无 brand:edit 权限'); return }
    await apiUpdateSku(Number(id), {
      name: patch.name, categoryId: patch.categoryId ? Number(patch.categoryId) : undefined,
      unit: patch.unit,
      listPriceFen: patch.listPrice != null ? yuan2fen(patch.listPrice) : undefined,
      costPriceFen: patch.costPrice != null ? yuan2fen(patch.costPrice) : undefined,
      storeTypes: patch.storeTypes, durationMin: patch.durationMin, remark: patch.remark,
    })
    await load(true)
  }

  async function setProductStatus(id: string, status: CommonStatus): Promise<void> {
    if (!auth.can('brand:edit')) { console.warn('[m1Brand] 无 brand:edit 权限'); return }
    await apiSetSkuStatus(Number(id), { status })
    await load(true)
  }

  // ---- 离线演示数据（API 不可用/空库回落） ----
  function loadDemo() {
    const mkBrand = (b: { code: string; name: string; shortName?: string; origin?: string; supplier: string; remark?: string }): Brand => ({
      ...b, status: 'ACTIVE', logoColor: BRAND_COLORS[brands.value.length % BRAND_COLORS.length],
      id: cid('brand'), createdAt: now(),
    })
    const b1 = mkBrand({ code: 'BR-ALLERGAN', name: '艾尔建', shortName: 'Allergan', origin: '美国/爱尔兰', supplier: '艾尔建信息咨询(上海)有限公司', remark: '全球医美制药龙头，肉毒素/玻尿酸头部品牌' })
    const b2 = mkBrand({ code: 'BR-BLOOMAGE', name: '华熙生物', shortName: 'Bloomage', origin: '中国山东', supplier: '华熙生物科技股份有限公司', remark: '透明质酸全产业链' })
    const b3 = mkBrand({ code: 'BR-SINOGEN', name: '中韩光电', shortName: 'Sinogen', origin: '中国北京', supplier: '北京中韩光电科技有限公司', remark: '光电仪器设备与耗材' })
    const b4 = mkBrand({ code: 'BR-LUMENIS', name: '科医人', shortName: 'Lumenis', origin: '以色列', supplier: '科医人医疗激光设备有限公司', remark: '医美能量源设备' })
    b4.status = 'INACTIVE'
    brands.value = [b1, b2, b3, b4]

    const mkCat = (c: { code: string; name: string; brandId: string; parentId?: string; remark?: string }): Category => ({
      ...c, status: 'ACTIVE', sort: categories.value.length, id: cid('cat'),
    })
    const c1 = mkCat({ code: 'CT-INJECT', name: '注射美容', brandId: b1.id, remark: '肉毒素、玻尿酸注射类' })
    const c2 = mkCat({ code: 'CT-BTX', name: '肉毒素', brandId: b1.id, parentId: c1.id })
    const c3 = mkCat({ code: 'CT-FILLER', name: '玻尿酸填充', brandId: b1.id, parentId: c1.id })
    const c4 = mkCat({ code: 'CT-HA', name: '水光补水', brandId: b2.id })
    const c5 = mkCat({ code: 'CT-SKINCARE', name: '功能性护肤', brandId: b2.id })
    const c6 = mkCat({ code: 'CT-LASER', name: '激光治疗', brandId: b3.id })
    const c7 = mkCat({ code: 'CT-THERMO', name: '射频紧致', brandId: b3.id })
    const c8 = mkCat({ code: 'CT-IPL', name: '光子嫩肤', brandId: b4.id })
    categories.value = [c1, c2, c3, c4, c5, c6, c7, c8]

    const S = ['FLAGSHIP', 'COMMUNITY', 'CLINIC']
    const mkProd = (p: {
      sku: string; name: string; brandId: string; categoryId: string; unit: string
      listPrice: number; costPrice: number; storeTypes: string[]; durationMin: number
      serviceCategory?: string; riskTags?: string[]; status?: CommonStatus
    }): Product => ({ ...p, status: p.status ?? 'ACTIVE', id: cid('prod'), createdAt: now() })
    products.value = [
      mkProd({ sku: 'AGN-BTX-100', name: '保妥适 100U 瘦脸针', brandId: b1.id, categoryId: c1.id, unit: '次', listPrice: 3800, costPrice: 1650, storeTypes: S, durationMin: 30, serviceCategory: 'INJECTION', riskTags: ['INJECTION'] }),
      mkProd({ sku: 'AGN-JUV-1ML', name: '乔雅登极致 1ml 玻尿酸', brandId: b1.id, categoryId: c1.id, unit: '支', listPrice: 6800, costPrice: 3200, storeTypes: ['FLAGSHIP', 'CLINIC'], durationMin: 45, serviceCategory: 'INJECTION', riskTags: ['INJECTION'] }),
      mkProd({ sku: 'HX-RST-2.5ML', name: '润致娃娃针 2.5ml', brandId: b2.id, categoryId: c4.id, unit: '支', listPrice: 1980, costPrice: 680, storeTypes: S, durationMin: 40, serviceCategory: 'SKINCARE', riskTags: ['INJECTION', 'ANESTHESIA'] }),
      mkProd({ sku: 'HX-QUADHA', name: '润百颜次抛精华(疗程)', brandId: b2.id, categoryId: c5.id, unit: '盒', listPrice: 880, costPrice: 220, storeTypes: S, durationMin: 0, serviceCategory: 'SKINCARE' }),
      mkProd({ sku: 'ZH-THERMAGE-FL', name: '热玛吉FLX 面部900发', brandId: b3.id, categoryId: c7.id, unit: '部位', listPrice: 19800, costPrice: 7200, storeTypes: ['FLAGSHIP'], durationMin: 90, serviceCategory: 'LASER', riskTags: ['HIGH_ENERGY', 'PREGNANCY_RISK'] }),
      mkProd({ sku: 'ZH-PICOWAY', name: '超皮秒全模式', brandId: b3.id, categoryId: c6.id, unit: '次', listPrice: 2980, costPrice: 980, storeTypes: S, durationMin: 40, serviceCategory: 'LASER', riskTags: ['LASER'] }),
      mkProd({ sku: 'LUM-M22', name: 'M22王者之冠 光子嫩肤', brandId: b4.id, categoryId: c8.id, unit: '次', listPrice: 1280, costPrice: 420, storeTypes: S, durationMin: 30, status: 'INACTIVE', serviceCategory: 'LASER', riskTags: ['LASER'] }),
    ]
  }

  return {
    brands, categories, products, loaded, demo, STATUS_LABEL,
    activeBrands, stats,
    brand, category, categoriesOf, productsOf, productsOfCategory, brandStats,
    createBrand, updateBrand, setBrandStatus,
    createCategory, updateCategory, setCategoryStatus, deleteCategory,
    createProduct, updateProduct, setProductStatus,
    load, seed,
  }
})
