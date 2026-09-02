import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

// ============================================================
// 品牌品类 store（M1 集团管控 / 品牌品类）
// 三级主数据：品牌 Brand -> 品类 Category -> 项目/产品 Product(SKU)
// - 品牌：上游厂商/供应商品牌（如 艾尔建、华熙生物），可启停
// - 品类：项目分类（注射类/光电类/护肤类/手术类），归属品牌
// - 项目：可售卖 SKU，挂牌价/成本/单位/状态/适用门店类型
// - 删除/停用均为受控操作（内置 seed 品牌不可删，只能停用）
// ============================================================

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
  logoColor: string // 头像色（演示用）
  remark?: string
  createdAt: string
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
  listPrice: number // 挂牌价
  costPrice: number // 成本价
  status: CommonStatus
  storeTypes: string[] // 适用门店类型 FLAGSHIP/COMMUNITY/CLINIC
  durationMin: number // 预计时长（分钟）
  remark?: string
  createdAt: string
}

let _cid = 0
function cid(p: string) { _cid += 1; return `${p}-${Date.now().toString(36)}-${_cid}` }
function now() { return new Date().toISOString() }
function daysAgo(n: number) { return new Date(Date.now() - n * 86400000).toISOString() }

export const useM1BrandStore = defineStore('m1Brand', () => {
  const brands = ref<Brand[]>([])
  const categories = ref<Category[]>([])
  const products = ref<Product[]>([])
  const seeded = ref(false)

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

  // 品牌统计：品类数、启用项目数、SKU 总数、平均挂牌价
  function brandStats(brandId: string) {
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

  // ---- 品牌 CRUD ----
  function createBrand(b: Omit<Brand, 'id' | 'createdAt' | 'status' | 'logoColor'> & { status?: CommonStatus }): Brand {
    const colors = ['#FF6B9D', '#5B8DEF', '#22C55E', '#F59E0B', '#8B5CF6', '#06B6D4']
    const br: Brand = {
      ...b, status: b.status ?? 'ACTIVE', logoColor: colors[brands.value.length % colors.length],
      id: cid('brand'), createdAt: now(),
    }
    brands.value.push(br)
    return br
  }
  function updateBrand(id: string, patch: Partial<Brand>) {
    const b = brand(id)
    if (b) Object.assign(b, patch)
  }
  function setBrandStatus(id: string, status: CommonStatus) {
    const b = brand(id)
    if (b) b.status = status
  }

  // ---- 品类 CRUD ----
  function createCategory(c: Omit<Category, 'id' | 'sort' | 'status'> & { status?: CommonStatus; sort?: number }): Category {
    const maxSort = Math.max(0, ...categories.value.filter((x) => x.brandId === c.brandId).map((x) => x.sort))
    const cat: Category = { ...c, status: c.status ?? 'ACTIVE', sort: c.sort ?? maxSort + 1, id: cid('cat') }
    categories.value.push(cat)
    return cat
  }
  function updateCategory(id: string, patch: Partial<Category>) {
    const c = category(id)
    if (c) Object.assign(c, patch)
  }
  function setCategoryStatus(id: string, status: CommonStatus) {
    const c = category(id)
    if (c) c.status = status
  }
  function deleteCategory(id: string) {
    if (products.value.some((p) => p.categoryId === id)) {
      throw new Error('该品类下仍有项目，无法删除')
    }
    categories.value = categories.value.filter((c) => c.id !== id && c.parentId !== id)
  }

  // ---- 项目 CRUD ----
  function createProduct(p: Omit<Product, 'id' | 'createdAt' | 'status'> & { status?: CommonStatus }): Product {
    const prod: Product = { ...p, status: p.status ?? 'ACTIVE', id: cid('prod'), createdAt: now() }
    products.value.push(prod)
    return prod
  }
  function updateProduct(id: string, patch: Partial<Product>) {
    const p = products.value.find((x) => x.id === id)
    if (p) Object.assign(p, patch)
  }
  function setProductStatus(id: string, status: CommonStatus) {
    const p = products.value.find((x) => x.id === id)
    if (p) p.status = status
  }

  // ---- seed ----
  function seed() {
    if (seeded.value) return
    const b1 = createBrand({ code: 'BR-ALLERGAN', name: '艾尔建', shortName: 'Allergan', origin: '美国/爱尔兰', supplier: '艾尔建信息咨询(上海)有限公司', remark: '全球医美制药龙头，肉毒素/玻尿酸头部品牌' })
    const b2 = createBrand({ code: 'BR-BLOOMAGE', name: '华熙生物', shortName: 'Bloomage', origin: '中国山东', supplier: '华熙生物科技股份有限公司', remark: '透明质酸全产业链' })
    const b3 = createBrand({ code: 'BR-SINOGEN', name: '中韩光电', shortName: 'Sinogen', origin: '中国北京', supplier: '北京中韩光电科技有限公司', remark: '光电仪器设备与耗材' })
    const b4 = createBrand({ code: 'BR-LUMENIS', name: '科医人', shortName: 'Lumenis', origin: '以色列', supplier: '科医人医疗激光设备有限公司', remark: '医美能量源设备' })
    // 一个停用品牌
    brands.value.find((b) => b.code === 'BR-LUMENIS')!.status = 'INACTIVE'

    // 品类
    const c1 = createCategory({ code: 'CT-INJECT', name: '注射美容', brandId: b1.id, remark: '肉毒素、玻尿酸注射类' })
    createCategory({ code: 'CT-BTX', name: '肉毒素', brandId: b1.id, parentId: c1.id })
    createCategory({ code: 'CT-FILLER', name: '玻尿酸填充', brandId: b1.id, parentId: c1.id })
    createCategory({ code: 'CT-HA', name: '水光补水', brandId: b2.id })
    createCategory({ code: 'CT-SKINCARE', name: '功能性护肤', brandId: b2.id })
    createCategory({ code: 'CT-LASER', name: '激光治疗', brandId: b3.id })
    createCategory({ code: 'CT-THERMO', name: '射频紧致', brandId: b3.id })
    createCategory({ code: 'CT-IPL', name: '光子嫩肤', brandId: b4.id })

    // 项目 SKU
    const S = ['FLAGSHIP', 'COMMUNITY', 'CLINIC']
    createProduct({ sku: 'AGN-BTX-100', name: '保妥适 100U 瘦脸针', brandId: b1.id, categoryId: c1.id, unit: '次', listPrice: 3800, costPrice: 1650, storeTypes: S, durationMin: 30 })
    createProduct({ sku: 'AGN-JUV-1ML', name: '乔雅登极致 1ml 玻尿酸', brandId: b1.id, categoryId: c1.id, unit: '支', listPrice: 6800, costPrice: 3200, storeTypes: ['FLAGSHIP', 'CLINIC'], durationMin: 45 })
    createProduct({ sku: 'HX-RST-2.5ML', name: '润致娃娃针 2.5ml', brandId: b2.id, categoryId: categories.value.find((c) => c.code === 'CT-HA')!.id, unit: '支', listPrice: 1980, costPrice: 680, storeTypes: S, durationMin: 40 })
    createProduct({ sku: 'HX-QUADHA', name: '润百颜次抛精华(疗程)', brandId: b2.id, categoryId: categories.value.find((c) => c.code === 'CT-SKINCARE')!.id, unit: '盒', listPrice: 880, costPrice: 220, storeTypes: S, durationMin: 0 })
    createProduct({ sku: 'ZH-THERMAGE-FL', name: '热玛吉FLX 面部900发', brandId: b3.id, categoryId: categories.value.find((c) => c.code === 'CT-THERMO')!.id, unit: '部位', listPrice: 19800, costPrice: 7200, storeTypes: ['FLAGSHIP'], durationMin: 90 })
    createProduct({ sku: 'ZH-PICOWAY', name: '超皮秒全模式', brandId: b3.id, categoryId: categories.value.find((c) => c.code === 'CT-LASER')!.id, unit: '次', listPrice: 2980, costPrice: 980, storeTypes: S, durationMin: 40 })
    createProduct({ sku: 'LUM-M22', name: 'M22王者之冠 光子嫩肤', brandId: b4.id, categoryId: categories.value.find((c) => c.code === 'CT-IPL')!.id, unit: '次', listPrice: 1280, costPrice: 420, storeTypes: S, durationMin: 30, status: 'INACTIVE' })

    seeded.value = true
    void daysAgo
  }

  return {
    brands, categories, products, seeded, STATUS_LABEL,
    activeBrands, stats,
    brand, category, categoriesOf, productsOf, productsOfCategory, brandStats,
    createBrand, updateBrand, setBrandStatus,
    createCategory, updateCategory, setCategoryStatus, deleteCategory,
    createProduct, updateProduct, setProductStatus,
    seed,
  }
})
