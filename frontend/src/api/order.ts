// ============================================================
// Order 聚合 API（对接 txn-service 订单/收款域）
// 正向交易：开单（可含多收费子项）→ 双签确认 → 收款。逆向见 refund.ts。
// 库内状态中文四态：待签核/待收款/已收款/已取消；金额单位「分」。
// 读模型冗余客户名/门店名/咨询师中文名 + 收费子项。
// ============================================================
import client from './client'

/** 订单收费子项（读模型）。金额单位「分」。 */
export interface OrderItemViewDTO {
  itemName: string
  qty: number
  unitPrice: number // 单价（分）
  amount: number // 小计（分）
}

export interface OrderViewDTO {
  orderNo: string
  customerId: string
  customerName: string | null
  storeCode: string | null
  storeName: string | null
  project: string
  /** 订单总额（分） */
  amount: number
  status: string // 待签核/待收款/已收款/已取消
  consultantName: string | null
  contraCheck: string // GREEN/YELLOW/RED
  createdAt: string | null
  items: OrderItemViewDTO[]
  /** 累计已入账（分）；待收款单为部分收款累计，已收款单等于 amount。 */
  paidAmount?: number
  /** 支付明细流水（组合支付/找零）。 */
  payments?: OrderPaymentDTO[]
}

/** 单笔支付明细流水（order_payment）。金额单位「分」。 */
export interface OrderPaymentDTO {
  paymentId: string
  orderNo: string
  payMethod: string // cash/card/wxpay/alipay/balance
  /** 客户实付（现金为递交现金，非现金等于入账）。 */
  cashTendered: number
  /** 实际入账（现金按待收封顶）。 */
  postedAmount: number
  /** 现金找零。 */
  changeAmount: number
  /** 本笔后累计已入账。 */
  paidAfter: number
  operator: string | null
}

/** 收款结果：本笔流水 + 入账后累计/找零 + 是否收齐。 */
export interface PayResultDTO {
  payment: OrderPaymentDTO | null
  orderAmount: number
  paidAmount: number
  changeAmount: number
  completed: boolean
  orderStatus: string
}

export interface OrderPage {
  content: OrderViewDTO[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

export interface OrderItemCmd {
  itemName: string
  qty: number
  unitPrice: number // 单价（分）
}

export interface CreateOrderCmd {
  customerId: string
  storeCode?: string
  project: string
  /** 订单总额（分）；传 items 时可省略，以后端子项合计为准 */
  amount?: number
  consultant?: string
  items?: OrderItemCmd[]
  // RED 禁忌豁免（仅禁忌硬阻断时需要）
  exemptionSign1?: string
  exemptionSign2?: string
  exemptionSign2Licensed?: boolean
  exemptionNote?: string
}

/** 零售/现场直开命令（/prescription，不走医生审核，直接生成「待收款」订单）。 */
export interface RetailOrderCmd {
  customerId: string
  storeCode: string
  /** 咨询师/开单员工 ID（可选）。 */
  consultant?: string
  /** 单名（如「药妆零售」）；省略取首个商品名。 */
  project?: string
  items: OrderItemCmd[]
  operator?: string
}

/** 订单分页列表（status/storeCode 可选过滤）。 */
export const listOrders = (params: { page?: number; size?: number; status?: string; storeCode?: string }) =>
  client.get<OrderPage>('/txn/order', { params })

export const createOrder = (cmd: CreateOrderCmd) =>
  client.post<OrderViewDTO>('/txn/order', cmd)

/** 零售/现场直开缴费单（/prescription）：不走医生审核，直接待收款；散客也须建档客户。 */
export const createRetailOrder = (cmd: RetailOrderCmd) =>
  client.post<OrderViewDTO>('/txn/retail-order', cmd)

export const confirmOrder = (no: string, sign1: string, sign2: string) =>
  client.post<OrderViewDTO>(`/txn/order/${no}/confirm`, { sign1, sign2 })

/**
 * 登记一笔收款（组合支付 / 现金找零 / 部分收款），收齐即 PAID。
 * @param method   cash/card/wxpay/alipay/balance
 * @param tendered 客户实付（分）；现金为递交现金（后端按待收封顶入账+找零），非现金等于实际扣款
 */
export const payOrder = (no: string, method: string, tendered: number, operator: string) =>
  client.post<PayResultDTO>(`/txn/order/${no}/pay`, { method, tendered, operator })

/** 某订单的支付明细流水。 */
export const listOrderPayments = (no: string) =>
  client.get<OrderPaymentDTO[]>(`/txn/order/${no}/payments`)

export const cancelOrder = (no: string, operator: string) =>
  client.post<OrderViewDTO>(`/txn/order/${no}/cancel`, { operator })

export const getOrder = (no: string) =>
  client.get<OrderViewDTO>(`/txn/order/${no}`)
