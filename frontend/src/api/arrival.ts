// ============================================================
// 接待台 / 候诊分诊 API（对接 txn-service 到店域：/api/txn/arrivals）
//
// 到店登记 → 候诊（WAITING）→ 分诊入位（TRIAGED，同事务建方案草稿）
//   → 叫号（CALLED）→ 完成（DONE）；分诊单可改派（reassign）。
// 状态/类型码对齐后端英文枚举，中文化由页面模板现有 chip/字典完成。
// ============================================================
import client from './client'

export type ArrivalStatus = 'WAITING' | 'TRIAGED' | 'CALLED' | 'DONE' | 'LEFT'
export type TriageType = 'CONSULT' | 'MEDICAL' | 'SERVICE'
export type ArrivalChannel = 'WALK_IN' | 'REFERRAL' | 'MARKETING' | 'APPOINTMENT'

/** 内联分诊单读模型：assignedToName 为首诊负责人名，ownerName 为改派后当前负责人名。 */
export interface TriageViewDTO {
  id: string
  arrivalId: string
  customerId: string
  type: TriageType | string
  assignedTo: string
  assignedToName: string | null
  forwardedTo: string | null
  forwardedToName: string | null
  ownerName: string | null
  note: string | null
  planId: string | null
  editedBy: string | null
  editedAt: string | null
}

/** 到店记录读模型（后端富化客户名/掩码手机号 + 内联当前分诊单）。 */
export interface ArrivalViewDTO {
  id: string
  ahNo: string
  customerId: string
  storeCode: string
  customerName: string
  phoneMask: string
  channel: ArrivalChannel | string
  queueNo: number
  status: ArrivalStatus | string
  note: string | null
  apptNo: string | null
  arrivedAt: string
  calledAt: string | null
  doneAt: string | null
  triage: TriageViewDTO | null
}

export interface CheckInCmd {
  customerId: string
  channel?: string
  note?: string
}

export interface TriageCmd {
  type: string
  assignedTo: string
  note?: string
}

export interface ReassignCmd {
  newAssignedTo: string
}

/** 今日队列：默认今日，支持日期/门店/状态过滤；接待台与候诊看板共用一次拉全。 */
export const listArrivals = (params?: { date?: string; storeCode?: string; status?: string }) =>
  client.get<ArrivalViewDTO[]>('/txn/arrivals', { params })

/** 前台手工到店登记（门店由后端按 JWT 当前本店落，body 不传 storeCode）。 */
export const checkInArrival = (cmd: CheckInCmd) =>
  client.post<ArrivalViewDTO>('/txn/arrivals', cmd)

/** 分诊入位：WAITING → TRIAGED，同事务建 consult_plan 空草稿（planId 在 triage.planId 回查）。 */
export const triageArrival = (ahNo: string, cmd: TriageCmd) =>
  client.post<ArrivalViewDTO>(`/txn/arrivals/${ahNo}/triage`, cmd)

/** 改派：仅更新当前分诊单 forwardedTo，不动方案草稿归属。 */
export const reassignArrival = (ahNo: string, cmd: ReassignCmd) =>
  client.post<ArrivalViewDTO>(`/txn/arrivals/${ahNo}/reassign`, cmd)

/** 叫号：TRIAGED → CALLED（幂等）。 */
export const callArrival = (ahNo: string) =>
  client.post<ArrivalViewDTO>(`/txn/arrivals/${ahNo}/call`, {})

/** 完成接诊：TRIAGED/CALLED → DONE（幂等）。 */
export const doneArrival = (ahNo: string) =>
  client.post<ArrivalViewDTO>(`/txn/arrivals/${ahNo}/done`, {})
