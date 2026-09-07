// ============================================================
// 门店价目 API（B14）
// 门店级价目：挂牌价/会员价/活动价 + 调价申请审批状态机。
// 网关 /api/stores → store-service:8085，类路径 /api/stores。
// 读接口金额单位「元」（originalPriceYuan/memberPriceYuan/promoPriceYuan，后端已由分换算，
//   富化 SKU 的项目名 name、服务大类 category、单位 unit、时长 durationMin、风险标签 riskTags）；
// 写接口金额单位「分」（*Fen），前端由「元」换算。
// 查询/建档/调价申请/停用启用走 pricelist:*；审批通过/驳回走 brand:approve（E1）。
// ============================================================
import client from './client'

/** 待审调价嵌套体（PENDING 态返回）；金额单位「元」。 */
export interface PendingPriceDTO {
  memberPriceYuan: number // 元
  promoPriceYuan: number | null // 元
  reason: string
  requestedAt: string | null
  requestedBy: string
}

/** 价目行（GET /stores/prices）；富化自 SKU，金额单位「元」。 */
export interface PriceDTO {
  id: number
  storeCode: string
  sku: string
  code: string // = sku，摊平供前端项目编码列
  name: string // SKU 项目名
  category: string | null // SKU 服务大类 INJECTION/LASER/SKINCARE/BODY/EXAM
  unit: string | null
  durationMin: number
  riskTags: string[]
  originalPriceYuan: number // 元
  memberPriceYuan: number // 元
  promoPriceYuan: number | null // 元
  status: 'ACTIVE' | 'DISABLED' | 'PENDING'
  updatedBy: string
  updatedAt: string | null
  pendingPrice?: PendingPriceDTO | null
}

/** 门店建档定价入参；金额单位「分」。 */
export interface CreatePriceCmd {
  storeCode: string
  sku: string
  originalPriceFen: number
  memberPriceFen: number
  promoPriceFen?: number | null
}

/** 调价申请入参；reason 必填，memberPriceFen 必填，promoPriceFen 可空；金额单位「分」。 */
export interface ChangeRequestCmd {
  storeCode: string
  memberPriceFen: number
  promoPriceFen?: number | null
  reason: string
}

/** 仅携带门店数据域的入参（toggle）。 */
export interface StoreScopeCmd {
  storeCode: string
}

/** 价目列表（富化 SKU；门店/自助角色后端强制本店，集团/品牌可参 storeCode）。 */
export const listPrices = (params?: {
  storeCode?: string
  category?: string
  status?: string
  keyword?: string
}) => client.get<PriceDTO[]>('/stores/prices', { params })

/** 门店建档定价；返回 {id, storeCode, sku}。 */
export const createPrice = (cmd: CreatePriceCmd) =>
  client.post<{ id: number; storeCode: string; sku: string }>('/stores/prices', cmd)

/** 提交调价申请（店长 pricelist:edit；落 PENDING）；返回 {ok:true}。 */
export const requestPriceChange = (id: number, cmd: ChangeRequestCmd) =>
  client.post<{ ok: boolean }>(`/stores/prices/${id}/change-request`, cmd)

/** 审批通过（区域/超管 brand:approve；无 body；pending 覆盖正式价）。 */
export const approvePriceChange = (id: number) =>
  client.post<{ ok: boolean }>(`/stores/prices/${id}/approve`)

/** 审批驳回（区域/超管 brand:approve；无 body；清 pending 回原价）。 */
export const rejectPriceChange = (id: number) =>
  client.post<{ ok: boolean }>(`/stores/prices/${id}/reject`)

/** 停用/启用切换（店长 pricelist:edit；PENDING 态后端 409）。 */
export const togglePrice = (id: number, cmd: StoreScopeCmd) =>
  client.post<{ ok: boolean }>(`/stores/prices/${id}/toggle`, cmd)
