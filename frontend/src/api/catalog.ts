// ============================================================
// 卡项 / 疗程目录 API（B15）
// 卡项/疗程模板定义：次数、有效期、售价/划线价、转赠、上下架、包含项目。
// 网关 /api/stores → store-service:8085，类路径 /api/stores。
// store_code 空串 = 集团通用模板（全门店可见可售）。
// 读接口金额单位「元」（priceYuan/originalPriceYuan，后端已由分换算，includes 数组化）；
// 写接口金额单位「分」（*Fen），前端由「元」换算；productCode 后端生成（CD-/CS-）。
// 查询走 catalog:view；新建/编辑/上下架走 catalog:edit。
// ============================================================
import client from './client'

/** 目录模板行（GET /stores/catalog）；金额单位「元」。 */
export interface CatalogProductDTO {
  id: number
  storeCode: string
  productCode: string
  code: string // = productCode，摊平供前端编码列
  name: string
  productType: 'CARD' | 'COURSE'
  type: 'CARD' | 'COURSE'
  category: string | null
  sessions: number
  validityDays: number
  priceYuan: number // 元
  originalPriceYuan: number // 元
  transferable: boolean
  status: 'ON_SHELF' | 'OFF_SHELF'
  includes: string[]
  description: string | null
  updatedBy: string
  updatedAt: string | null
}

/** 新建模板入参；金额单位「分」；storeCode 空串/不传 = 集团通用模板。 */
export interface CreateCatalogCmd {
  storeCode?: string
  productType: 'CARD' | 'COURSE'
  name: string
  category?: string | null
  sessions: number
  validityDays: number
  priceFen: number
  originalPriceFen: number
  transferable: boolean
  status?: 'ON_SHELF' | 'OFF_SHELF'
  includes: string[]
  description?: string | null
}

/** 编辑模板入参（类型/编码不可改）；金额单位「分」。 */
export interface UpdateCatalogCmd {
  storeCode?: string
  name: string
  category?: string | null
  sessions: number
  validityDays: number
  priceFen: number
  originalPriceFen: number
  transferable: boolean
  includes: string[]
  description?: string | null
}

/** 目录列表（集团模板 + 本店模板；门店角色后端强制本店）。 */
export const listCatalog = (params?: {
  storeCode?: string
  type?: string
  status?: string
  keyword?: string
}) => client.get<CatalogProductDTO[]>('/stores/catalog', { params })

/** 新建模板；返回 {id, storeCode, productCode}。 */
export const createCatalog = (cmd: CreateCatalogCmd) =>
  client.post<{ id: number; storeCode: string; productCode: string }>('/stores/catalog', cmd)

/** 编辑模板（价格直接生效，本批无调价审批）；返回 {ok:true}。 */
export const updateCatalog = (id: number, cmd: UpdateCatalogCmd) =>
  client.post<{ ok: boolean }>(`/stores/catalog/${id}`, cmd)

/** 上架/下架切换（ON_SHELF ↔ OFF_SHELF）；返回 {ok:true}。 */
export const toggleCatalog = (id: number, cmd: { storeCode?: string }) =>
  client.post<{ ok: boolean }>(`/stores/catalog/${id}/toggle`, cmd)
