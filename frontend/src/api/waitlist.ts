// ============================================================
// 排队智能候补 API（对接 txn-service：/api/txn/waitlist）
//
// 候补登记（手机号自动锚定客户，未命中落散客快照）→ 号源释放后系统递补（NOTIFIED）
//   → 客户到场转正式到店登记（FULFILLED，回传 ahNo）；可取消（CANCELLED）。
// 出参手机号一律掩码；timeline 后端按时间正序返回。
// ============================================================
import client from './client'

export type WaitlistStatus = 'WAITING' | 'NOTIFIED' | 'FULFILLED' | 'CANCELLED'

export interface WaitlistTimelineDTO {
  by: string
  text: string
  at: string
}

/** 候补读模型（对齐后端 WaitlistController.WaitlistView）。 */
export interface WaitlistViewDTO {
  id: string
  wlNo: string
  storeCode: string
  storeName: string
  customerId: string | null
  customerName: string
  phone: string
  project: string | null
  expectDate: string | null
  status: WaitlistStatus | string
  notifiedAt: string | null
  ahNo: string | null
  operator: string
  note: string | null
  timeline: WaitlistTimelineDTO[]
  createdAt: string
}

export interface WaitlistRegisterCmd {
  customerId?: string | null
  customerName: string
  phone: string
  project: string
  expectDate?: string | null
  note?: string
}

/** 候补队列：默认全部日期，支持期望日期/门店/状态过滤；登记时间正序。 */
export const listWaitlist = (params?: { expectDate?: string; storeCode?: string; status?: string }) =>
  client.get<WaitlistViewDTO[]>('/txn/waitlist', { params })

/** 候补登记：门店取 JWT 当前本店；同店活跃候补重复手机号后端 409 幂等拒绝。 */
export const registerWaitlist = (cmd: WaitlistRegisterCmd) =>
  client.post<WaitlistViewDTO>('/txn/waitlist', cmd)

/** 取消候补：WAITING/NOTIFIED → CANCELLED（终态拒重）。 */
export const cancelWaitlist = (wlNo: string) =>
  client.post<WaitlistViewDTO>(`/txn/waitlist/${wlNo}/cancel`, {})

/** 到场确认：→ FULFILLED，同事务生成正式到店登记（AH）；channel 默认 WALK_IN。 */
export const fulfillWaitlist = (wlNo: string, channel = 'WALK_IN') =>
  client.post<WaitlistViewDTO>(`/txn/waitlist/${wlNo}/fulfill`, { channel })
