// ============================================================
// 会员到店核销 API（对接 txn-service：/api/txn/checkin）
//
// 今日到店队列 + 登记（扫码/预约/直接到店）+ 确认核销 + 异常标记/解除。
// 手机号：登记收明文，出参一律掩码；timeline 后端按时间正序返回。
// ============================================================
import client from './client'

export type CheckinMethod = 'SCAN' | 'APPOINTMENT' | 'WALKIN'
export type CheckinStatus = 'DONE' | 'EXCEPTION' | 'PENDING'
export type CheckinExceptionReason = 'NONE' | 'NOT_SELF' | 'ALREADY_DONE' | 'NO_APPOINTMENT' | 'INFO_MISMATCH'

export interface CheckinTimelineDTO {
  by: string
  text: string
  at: string
}

/** 到店核销读模型（对齐后端 CheckinController.CiView；customerId/storeCode 页面不渲染）。 */
export interface CheckinRecordDTO {
  id: string
  no: string
  customerId: string | null
  storeCode: string
  customerName: string
  phone: string
  project: string
  method: CheckinMethod | string
  status: CheckinStatus | string
  exceptionReason: CheckinExceptionReason | string
  arrivedAt: string
  checkedAt: string | null
  operator: string
  note: string | null
  /** 预约到店自动勾连的预约单号（未命中为 null） */
  apptNo: string | null
  /** 勾连同事务生成的待划扣任务号（未生成/未命中为 null） */
  wdNo: string | null
  timeline: CheckinTimelineDTO[]
}

export interface RegisterCmd {
  customerName: string
  phone: string
  project: string
  method: string
}

export interface ExceptionCmd {
  reason: string
  note?: string
}

/** 今日队列：默认今日，支持日期/门店/方式/状态过滤。 */
export const listCheckins = (params?: { date?: string; storeCode?: string; method?: string; status?: string }) =>
  client.get<CheckinRecordDTO[]>('/txn/checkin/records', { params })

/** 登记到店：当日同号仍待确认时后端幂等返回既有单。 */
export const registerCheckin = (cmd: RegisterCmd) =>
  client.post<CheckinRecordDTO>('/txn/checkin/records', cmd)

/** 确认核销：PENDING → DONE（DONE 幂等）。 */
export const confirmCheckin = (ciNo: string) =>
  client.post<CheckinRecordDTO>(`/txn/checkin/records/${ciNo}/confirm`, {})

/** 标记异常（PENDING/EXCEPTION 可标，DONE 不可）。 */
export const markCheckinException = (ciNo: string, cmd: ExceptionCmd) =>
  client.post<CheckinRecordDTO>(`/txn/checkin/records/${ciNo}/exception`, cmd)

/** 解除异常：EXCEPTION → PENDING。 */
export const resetCheckin = (ciNo: string) =>
  client.post<CheckinRecordDTO>(`/txn/checkin/records/${ciNo}/reset`, {})
