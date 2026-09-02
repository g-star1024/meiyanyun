// ============================================================
// 客户 360 跨域只读聚合 API（txn 域：订单 / 面诊咨询 / 预约）
// 对接 txn-service CustomerViewController（网关前缀 /api/txn）。
// 这些是客户视图专用的精简读模型，工号已在后端解析为中文名，金额单位「分」。
// 完整开单/签核流程见 order.ts；预约看板见 appointment.ts。
// ============================================================
import client from './client'

/** 订单收费子项（医美一笔订单可含多个收费项目） */
export interface CustomerOrderItemView {
  /** 收费项目名称（中文） */
  itemName: string
  /** 数量 */
  qty: number
  /** 单价：单位「分」 */
  unitPrice: number
  /** 小计 = 单价 × 数量，单位「分」 */
  amount: number
}

/** 客户消费订单行（读模型） */
export interface CustomerOrderView {
  orderNo: string
  project: string
  /** 金额：bigint，单位「分」（展示 /100 转元）；等于各子项小计之和 */
  amount: number
  /** 状态中文：待签核/待收款/已收款/已取消 */
  status: string
  /** 咨询师中文名（后端解析，可能为空） */
  consultantName: string | null
  createdAt: string
  /** 收费子项明细（一笔订单可含多个项目） */
  items: CustomerOrderItemView[]
}

/** 客户面诊/咨询记录（读模型，概要，不含过敏史明细） */
export interface CustomerConsultView {
  consultId: string
  /** 皮肤状况（中文） */
  skinStatus: string | null
  /** 需求描述 */
  needs: string | null
  consultantName: string | null
  privacyMasked: boolean
  createdAt: string
}

/** 客户预约记录（读模型） */
export interface CustomerApptView {
  apptNo: string
  project: string
  /** 预约日期 yyyy-MM-dd */
  apptDate: string
  /** 预约时间 HH:mm */
  apptTime: string
  /** 操作医生中文名（后端解析） */
  doctorName: string | null
  /** 来源中文：B端登记/C端小程序/C端App */
  source: string
  /** 状态中文：已预约/已到店/未到诊/已取消 */
  status: string
}

/** 客户价值模型 RFM（读时实时计算，不落库；规则见 docs/RFM-RULES.md） */
export interface CustomerRfmView {
  /** R/F/M 评分 1-5（无成交客户为 null） */
  rScore: number | null
  fScore: number | null
  mScore: number | null
  /** 最近一次成交距今天数 */
  recencyDays: number | null
  /** 近 365 天成交订单数 */
  freq365: number
  /** 近 365 天消费金额（元） */
  monetary365: number
  /** 近 90 天成交订单数 */
  orders90: number
  /** 历史累计成交订单数 */
  totalOrders: number
  /** 会员存续月数（客户域，当前留空） */
  tenureMonths: number | null
  /** 忠诚度中文：高忠诚/中忠诚/低忠诚 */
  loyaltyLevel: string | null
  /** 活跃度中文：活跃/沉默/沉睡/流失 */
  activityLevel: string
  /** 生命周期中文：新客/活跃/沉默/沉睡/流失/未成交 */
  lifecycle: string
  /** 八象限分层中文：重要价值客户/重要发展客户/…/一般挽留客户/未成交客户 */
  segment: string
  /** 是否有成交（false 时不展示评分雷达） */
  transacted: boolean
}

export const listCustomerOrders = (customerId: string) =>
  client.get<CustomerOrderView[]>(`/txn/customer/${customerId}/orders`)

export const getCustomerRfm = (customerId: string) =>
  client.get<CustomerRfmView>(`/txn/customer/${customerId}/rfm`)

export const listCustomerConsultations = (customerId: string) =>
  client.get<CustomerConsultView[]>(`/txn/customer/${customerId}/consultations`)

export const listCustomerAppointments = (customerId: string) =>
  client.get<CustomerApptView[]>(`/txn/customer/${customerId}/appointments`)
