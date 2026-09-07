// ============================================================
// 项目目录（集团主数据）API（B14）
// 品牌 Brand -> 品类 Category -> SKU 三级，集团统一定义，不做门店数据域裁决。
// 网关 /api/stores → store-service:8085，类路径 /api/stores。
// 读接口金额单位「元」（listPriceYuan/costPriceYuan/avgListPriceYuan，后端已换算）；
// 写接口金额单位「分」（listPriceFen/costPriceFen），前端由「元」换算。
// 读 brand:view；建档/更新/启停/删除走 brand:edit。
// ============================================================
import client from './client'

// ---- DTO（GET 出参；id 为 number，storeTypes/riskTags 为数组） ----

/** 品牌行（GET /stores/brands）；stats 为后端聚合统计。 */
export interface BrandDTO {
  id: number
  code: string
  name: string
  shortName: string | null
  origin: string | null
  supplier: string
  status: 'ACTIVE' | 'INACTIVE'
  logoColor: string | null
  remark: string | null
  createdAt: string | null
  stats?: {
    categoryCount: number
    productCount: number
    activeProductCount: number
    avgListPriceYuan: number // 元
  }
}

/** 品类行（GET /stores/categories）。 */
export interface CategoryDTO {
  id: number
  code: string
  name: string
  brandId: number
  parentId: number | null
  status: 'ACTIVE' | 'INACTIVE'
  sort: number
  remark: string | null
  createdAt: string | null
}

/** SKU 行（GET /stores/skus）；金额单位「元」。 */
export interface SkuDTO {
  id: number
  sku: string
  name: string
  brandId: number
  categoryId: number
  unit: string
  listPriceYuan: number // 元
  costPriceYuan: number // 元
  status: 'ACTIVE' | 'INACTIVE'
  storeTypes: string[]
  durationMin: number
  serviceCategory: string | null
  riskTags: string[]
  remark: string | null
  createdAt: string | null
}

// ---- Cmd（POST 入参；金额单位「分」） ----

export interface BrandCmd {
  code: string
  name: string
  shortName?: string
  origin?: string
  supplier: string
  logoColor?: string
  remark?: string
  status?: string
}

export interface BrandUpdateCmd {
  name?: string
  shortName?: string
  origin?: string
  supplier?: string
  remark?: string
}

export interface CategoryCmd {
  code: string
  name: string
  brandId: number
  parentId?: number
  sort?: number
  remark?: string
}

export interface CategoryUpdateCmd {
  name?: string
  parentId?: number
  sort?: number
  remark?: string
}

export interface SkuCmd {
  sku: string
  name: string
  brandId: number
  categoryId: number
  unit: string
  listPriceFen: number
  costPriceFen: number
  storeTypes: string[]
  durationMin: number
  serviceCategory?: string
  remark?: string
}

export interface SkuUpdateCmd {
  name?: string
  categoryId?: number
  unit?: string
  listPriceFen?: number
  costPriceFen?: number
  storeTypes?: string[]
  durationMin?: number
  remark?: string
}

export interface StatusCmd {
  status: string
}

// ---- 品牌 ----

export const listBrands = () => client.get<BrandDTO[]>('/stores/brands')

export const createBrand = (cmd: BrandCmd) =>
  client.post<{ id: number; code: string; name: string }>('/stores/brands', cmd)

export const updateBrand = (id: number, cmd: BrandUpdateCmd) =>
  client.post<{ ok: boolean }>(`/stores/brands/${id}`, cmd)

export const setBrandStatus = (id: number, cmd: StatusCmd) =>
  client.post<{ ok: boolean }>(`/stores/brands/${id}/status`, cmd)

// ---- 品类 ----

export const listCategories = (params?: { brandId?: number }) =>
  client.get<CategoryDTO[]>('/stores/categories', { params })

export const createCategory = (cmd: CategoryCmd) =>
  client.post<{ id: number; code: string; name: string }>('/stores/categories', cmd)

export const updateCategory = (id: number, cmd: CategoryUpdateCmd) =>
  client.post<{ ok: boolean }>(`/stores/categories/${id}`, cmd)

export const setCategoryStatus = (id: number, cmd: StatusCmd) =>
  client.post<{ ok: boolean }>(`/stores/categories/${id}/status`, cmd)

export const deleteCategory = (id: number) =>
  client.delete<{ ok: boolean }>(`/stores/categories/${id}`)

// ---- SKU ----

export const listSkus = (params?: {
  brandId?: number
  categoryId?: number
  keyword?: string
  status?: string
}) => client.get<SkuDTO[]>('/stores/skus', { params })

export const createSku = (cmd: SkuCmd) =>
  client.post<{ id: number; sku: string; name: string }>('/stores/skus', cmd)

export const updateSku = (id: number, cmd: SkuUpdateCmd) =>
  client.post<{ ok: boolean }>(`/stores/skus/${id}`, cmd)

export const setSkuStatus = (id: number, cmd: StatusCmd) =>
  client.post<{ ok: boolean }>(`/stores/skus/${id}/status`, cmd)
