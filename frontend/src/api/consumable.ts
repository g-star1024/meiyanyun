// ============================================================
// Consumable 耗材库存 API（B5）
// 网关 /api/stores → store-service:8085，类路径 /api/stores/consumables。
// 读接口金额单位「元」（avgCostYuan/stockValueYuan/unitCostYuan/amountYuan，后端已换算）；
// 写接口金额单位「分」（costPriceFen/unitCostFen），前端由「元」换算。
// 领用/报损出库无公开端点：必须走 txn 审批中心双签，终审后由服务端内部扣库。
// ============================================================
import client from './client'

/** 耗材台账行（GET /stores/consumables）；金额单位「元」。 */
export interface ConsumableDTO {
  id: number
  storeCode: string
  skuCode: string
  name: string
  category: 'CONSUMABLE' | 'PRODUCT' | 'DRUG' | 'DEVICE'
  spec: string | null
  unit: string
  qty: number
  safetyStock: number
  lowStock: boolean
  supplier: string | null
  location: string | null
  avgCostYuan: number
  stockValueYuan: number
  lastInAt: string | null
}

/** 出入库流水行（GET /stores/consumables/movements）；金额单位「元」。 */
export interface ConsumableMovementDTO {
  id: number
  storeCode: string
  consumableId: number
  skuCode: string | null
  name: string
  unit: string | null
  qtyChange: number // 正=入库，负=出库/报损
  moveType: 'PURCHASE' | 'USE' | 'SCRAP' | 'ADJUST'
  unitCostYuan: number
  amountYuan: number
  bizRef: string // 出库=审批号 AP...，入库=批次号
  operator: string
  remark: string | null
  createdAt: string
}

/** 耗材建档入参；金额 costPriceFen 单位「分」，initialQty>0 自动写 PURCHASE 流水。 */
export interface CreateSkuCmd {
  storeCode: string
  skuCode: string
  name: string
  category: string
  spec?: string
  unit: string
  costPriceFen: number
  safetyStock?: number
  initialQty?: number
  supplier?: string
  location?: string
}

/** 入库入参；金额 unitCostFen 单位「分」，batchNo 必填且为幂等键（重复提交 409）。 */
export interface StockInCmd {
  storeCode: string
  skuCode: string
  qty: number
  unitCostFen: number
  batchNo: string
  remark?: string
}

/** 耗材台账；门店/自助角色后端强制本店，REGION/GROUP 可参 storeCode。 */
export const listConsumables = (params?: { storeCode?: string; category?: string; keyword?: string }) =>
  client.get<ConsumableDTO[]>('/stores/consumables', { params })

/** 出入库流水；types 缺省 PURCHASE/USE/SCRAP。 */
export const listMovements = (params?: { storeCode?: string; types?: string[] }) =>
  client.get<ConsumableMovementDTO[]>('/stores/consumables/movements', { params })

/** 耗材建档（含初始库存）；返回 {id, skuCode, storeCode}。 */
export const createConsumable = (cmd: CreateSkuCmd) =>
  client.post<{ id: number; skuCode: string; storeCode: string }>('/stores/consumables', cmd)

/** 采购入库（移动平均重算）；batchNo 幂等，返回 {ok:true}。 */
export const stockInConsumable = (cmd: StockInCmd) =>
  client.post<{ ok: boolean }>('/stores/consumables/stock-in', cmd)
