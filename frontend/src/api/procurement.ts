// ============================================================
// 采购供应链 API（B49 卡5）：供应商档案 + 采购订单六态 + 收货联动耗材库存。
// 网关 /api/stores → store-service:8085，类路径 /api/stores/suppliers、/api/stores/purchase-orders。
// 读接口金额单位「元」（totalYuan/unitPriceYuan/amountYuan，后端已从分换算）；
// 供应商为集团级全局实体（无 storeCode）；PO 按数据域收敛（不传 storeCode 返可见门店全集）。
// ============================================================
import client from './client'

export type PoStatusDTO = 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'PARTIAL' | 'RECEIVED' | 'CANCELLED'
export type SignTierDTO = 'STORE' | 'REGION' | 'GROUP'

/** 供应商行（GET /stores/suppliers）。 */
export interface SupplierDTO {
  id: number
  code: string
  name: string
  contact: string | null
  phone: string | null
  paymentTerms: number
  qualified: boolean
  status: 'ACTIVE' | 'INACTIVE'
  remark: string | null
}

/** 采购单明细行；金额单位「元」。 */
export interface PoItemDTO {
  lineNo: number
  skuCode: string
  name: string
  brand: string | null
  unit: string
  unitPriceYuan: number
  qty: number
  receivedQty: number
  amountYuan: number
}

/** 收货批次行（详情接口 receipts[]）；金额单位「元」。 */
export interface GoodsReceiptDTO {
  id: number
  receiptNo: string
  receivedAt: string
  receiver: string
  qty: number
  amountYuan: number
  note: string | null
}

/** 采购单行（GET /stores/purchase-orders、/{id}）；金额单位「元」。 */
export interface PurchaseOrderDTO {
  id: number
  poNo: string
  supplierId: number
  supplierName: string | null
  supplierCode: string | null
  storeCode: string
  storeName: string
  status: PoStatusDTO
  totalYuan: number
  signTier: SignTierDTO
  expectDate: string | null
  remark: string | null
  createdBy: string
  submittedAt: string | null
  approver: string | null
  approvedAt: string | null
  rejectNote: string | null
  cancelledAt: string | null
  createdAt: string
  items: PoItemDTO[]
  receipts?: GoodsReceiptDTO[]
}

/** 建单明细入参；金额 unitPriceFen 单位「分」。 */
export interface CreatePoLineCmd {
  skuCode: string
  name: string
  brand?: string
  unit: string
  unitPriceFen: number
  qty: number
}

/** 建采购草稿入参。 */
export interface CreatePoCmd {
  storeCode: string
  supplierId: number
  expectDate?: string
  remark?: string
  lines: CreatePoLineCmd[]
}

/** 收货行入参（数量单位 = 明细单位）。 */
export interface ReceiveLineCmd {
  skuCode: string
  qty: number
}

/** 供应商列表（集团全局，可选关键词）。 */
export const listSuppliers = (params?: { keyword?: string }) =>
  client.get<SupplierDTO[]>('/stores/suppliers', { params })

/** 供应商建档；返回 {id, code}。 */
export const createSupplier = (cmd: {
  code: string
  name: string
  contact?: string
  phone?: string
  paymentTerms?: number
  qualified?: boolean
  status?: 'ACTIVE' | 'INACTIVE'
  remark?: string
}) => client.post<{ id: number; code: string }>('/stores/suppliers', cmd)

/** 采购单列表（数据域按登录态收敛）。 */
export const listPurchaseOrders = (params?: { storeCode?: string; status?: PoStatusDTO }) =>
  client.get<PurchaseOrderDTO[]>('/stores/purchase-orders', { params })

/** 采购单详情（含收货批次）。 */
export const getPurchaseOrder = (id: number | string) =>
  client.get<PurchaseOrderDTO>(`/stores/purchase-orders/${id}`)

/** 建采购草稿；返回新建主体。 */
export const createPurchaseOrder = (cmd: CreatePoCmd) =>
  client.post<PurchaseOrderDTO>('/stores/purchase-orders', cmd)

export const submitPurchaseOrder = (id: number | string) =>
  client.post<{ ok: boolean }>(`/stores/purchase-orders/${id}/submit`, {})

export const approvePurchaseOrder = (id: number | string, note?: string) =>
  client.post<{ ok: boolean }>(`/stores/purchase-orders/${id}/approve`, { note: note ?? null })

export const rejectPurchaseOrder = (id: number | string, note?: string) =>
  client.post<{ ok: boolean }>(`/stores/purchase-orders/${id}/reject`, { note: note ?? null })

export const cancelPurchaseOrder = (id: number | string, note?: string) =>
  client.post<{ ok: boolean }>(`/stores/purchase-orders/${id}/cancel`, { note: note ?? null })

/** 收货入库；返回 {ok, receiptNo}。 */
export const receivePurchaseOrder = (id: number | string, lines: ReceiveLineCmd[], note?: string) =>
  client.post<{ ok: boolean; receiptNo: string }>(`/stores/purchase-orders/${id}/receive`, { lines, note: note ?? null })
