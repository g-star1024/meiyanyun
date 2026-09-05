// ============================================================
// Writeoff（划扣核销）API
// 两条链路：
//  ① POST /txn/writeoff —— M2 会员卡按次/按额划扣（治疗台 m2-writeoff-desk）；
//  ② POST /txn/order-writeoff —— M4 订单整单核销（/writeoff 页，已收款订单履约完结）。
// ============================================================
import client from './client'

export const writeoff = (cmd: {
  orderNo?: string
  cardNo: string
  storeCode: string
  project: string
  timesUsed: number
  amount: number
  operator?: string
  sign1?: string
  sign2?: string
}) => client.post('/txn/writeoff', cmd)

/** 订单核销读模型（M4FlowController.WriteoffView）。金额单位「分」。 */
export interface OrderWriteoffDTO {
  writeoffNo: string
  orderNo: string
  customerId: string
  customerName: string | null
  project: string | null
  timesUsed: number | null
  amount: number | null
  operator: string | null
  operatorName: string | null
  status: string // PENDING/DONE/ABNORMAL/VOID
  abnormalReason: string | null
  createdAt: string | null
}

/** 订单整单核销（一单一次，幂等）：operator 为执行人工号。 */
export const orderWriteoff = (orderNo: string, operator: string) =>
  client.post<OrderWriteoffDTO>('/txn/order-writeoff', { orderNo, operator })

/** 订单核销记录列表：status 传 DONE/ABNORMAL 过滤，不传全量（card_no 为空的订单维度记录）。 */
export const listOrderWriteoffs = (status?: string) =>
  client.get<OrderWriteoffDTO[]>('/txn/order-writeoff', { params: status ? { status } : {} })

/** 划扣记录读模型（M4FlowController WriteoffRecord 裸投影）。金额单位「分」。 */
export interface WriteoffRecordDTO {
  writeoffId: string
  orderNo: string | null
  cardNo: string | null // 卡划扣非空；订单整单核销为 null
  customerId: string | null
  storeCode: string
  project: string | null
  timesUsed: number | null
  amount: number | null
  operator: string | null
  status: string // DONE/ABNORMAL/VOID
  abnormalReason: string | null
  sign1: string | null
  sign2: string | null
  createdAt: string | null
}

/**
 * 划扣记录列表：cardNo（卡 360）/ customerId（客户 360）精确过滤，
 * from/to（yyyy-MM-dd）按 created_at 闭区间；均为可选，不传走数据域全量。
 */
export const listWriteoffs = (params: {
  cardNo?: string
  customerId?: string
  from?: string
  to?: string
}) => client.get<WriteoffRecordDTO[]>('/txn/writeoff', { params })
