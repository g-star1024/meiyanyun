// ============================================================
// 咨询方案单 API（对接 txn-service 方案单域：咨询 → 医生二次审核 → 签病历生成缴费单）
//
// 诊疗主线：submit(待审核) → approve/reject/doctorEdit → signEmr(自动开「待收款」缴费单) → 收银。
// 零售支线见 order.ts 的 createRetailOrder（/prescription 直开，不走医生审核）。
// 金额单位「分」；状态码对齐后端状态机（活规格：stores/consultation.ts mock）。
// ============================================================
import client from './client'
import type { OrderViewDTO } from './order'

/** 方案单状态码（英文枚举，前端映射中文 pill）。 */
export type PlanStatus =
  | 'PENDING' // 待咨询
  | 'ACTIVE' // 咨询中
  | 'PENDING_REVIEW' // 待医生审核
  | 'APPROVED' // 审核通过·待写病历
  | 'REJECTED' // 已驳回（改单重提）
  | 'READY_PAY' // 待支付（签病历已生成缴费单）
  | 'PAID' // 已支付·待治疗
  | 'TREATING' // 治疗中
  | 'DONE' // 完成
  | 'ABANDONED' // 已作废

export interface PlanItemCmd {
  itemCode?: string
  itemName: string
  spec?: string
  qty: number
  unitPrice: number // 单价（分）
  riskTags?: string
}

export interface ContraCmd {
  pregnant?: boolean
  allergy?: boolean
  scarConstitution?: boolean
  skinLesion?: boolean
  coagulationAbn?: boolean
  seriousIllness?: boolean
  note?: string
}

export interface PlanItemViewDTO {
  itemCode: string | null
  itemName: string
  spec: string | null
  qty: number
  unitPrice: number
  amount: number
  riskTags: string | null
}

export interface PlanRevisionViewDTO {
  revId: number
  kind: string
  actorId: string | null
  actorName: string | null
  reason: string | null
  at: string | null
}

export interface PlanViewDTO {
  planId: string
  customerId: string
  customerName: string | null
  storeCode: string | null
  storeName: string | null
  consultantId: string | null
  consultantName: string | null
  doctorId: string | null
  doctorName: string | null
  status: PlanStatus | string
  conclusion: string | null
  planAmount: number // 方案应收（分）
  planCost: number // 成本（分）
  contraindications: ContraCmd | Record<string, unknown> | null
  consentConsultant: boolean | null
  consentCustomer: boolean | null
  consentSignerName: string | null
  consentDocVersion: string | null
  consentAt: string | null
  skinReportId: string | null
  submittedAt: string | null
  reviewedAt: string | null
  reviewedByName: string | null
  rejectReason: string | null
  emrId: string | null
  emrSignedAt: string | null
  orderNo: string | null
  orderStatus: string | null
  paidAt: string | null
  createdAt: string | null
  items: PlanItemViewDTO[]
  revisions: PlanRevisionViewDTO[]
}

export interface PlanPage {
  content: PlanViewDTO[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

export interface SubmitPlanCmd {
  customerId: string
  storeCode: string
  consultantId?: string
  doctorId: string
  conclusion: string
  items: PlanItemCmd[]
  contraindications?: ContraCmd
  consentConsultant?: boolean
  consentCustomer?: boolean
  consentSignatureDataUrl?: string
  consentSignerName?: string
  consentDocVersion?: string
  skinReportId?: string
  operator?: string
}

export interface SignEmrCmd {
  operator?: string
  customerName?: string
  chiefComplaint?: string
  presentIllness?: string
  pastHistory?: string
  diagnosis?: string
  treatment?: string
  prescription?: string
  note?: string
}

export interface DoctorEditCmd {
  operator?: string
  conclusion?: string
  items: PlanItemCmd[]
  reason: string
}

/** 咨询师：提交方案 → 待医生审核。 */
export const submitPlan = (cmd: SubmitPlanCmd) =>
  client.post<PlanViewDTO>('/txn/consult-plan', cmd)

/** 医生：审核通过（→ 待写病历）。 */
export const approvePlan = (planId: string, operator: string, note?: string) =>
  client.post<PlanViewDTO>(`/txn/consult-plan/${planId}/approve`, { operator, note })

/** 医生：驳回（须填原因，咨询师据此改单重提）。 */
export const rejectPlan = (planId: string, operator: string, note: string) =>
  client.post<PlanViewDTO>(`/txn/consult-plan/${planId}/reject`, { operator, note })

/** 医生：改单并通过（改单说明留痕）。 */
export const doctorEditPlan = (planId: string, cmd: DoctorEditCmd) =>
  client.post<PlanViewDTO>(`/txn/consult-plan/${planId}/doctor-edit`, cmd)

/** 医生：签首程病历 → 系统自动生成「待收款」缴费单，返回订单读模型。幂等：已开单返回原单。 */
export const signPlanEmr = (planId: string, cmd: SignEmrCmd = {}) =>
  client.post<OrderViewDTO>(`/txn/consult-plan/${planId}/sign-emr`, cmd)

/** 方案单队列（咨询师/医生工作台）：按状态、门店过滤，分页。 */
export const listPlans = (params: { page?: number; size?: number; status?: string; storeCode?: string }) =>
  client.get<PlanPage>('/txn/consult-plan', { params })

/** 方案单详情（含子项、留痕、关联缴费单状态）。 */
export const getPlan = (planId: string) =>
  client.get<PlanViewDTO>(`/txn/consult-plan/${planId}`)
