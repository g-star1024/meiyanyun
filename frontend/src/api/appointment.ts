// ============================================================
// Appointment 聚合 API（对接 txn-service 预约域，真实读写）
// 库内状态/来源均为中文枚举：状态 已预约/已到店/未到诊/已取消；
// 来源 B端登记/C端小程序/C端App。后端读模型冗余客户/门店/医生中文名。
// ============================================================
import client from './client'

export interface AppointmentView {
  apptNo: string
  customerId: string | null
  customerName: string | null
  storeCode: string | null
  storeName: string | null
  project: string
  apptDate: string
  apptTime: string
  doctor: string | null
  doctorName: string | null
  source: string
  status: string
  arrivedAt: string | null
  createdAt: string | null
}

export interface BoardStats {
  total: number
  booked: number
  arrived: number
  noShow: number
  cancelled: number
  arrivalRate: number
}

export interface CreateApptCmd {
  customerId: string
  storeCode: string
  project: string
  apptDate: string
  apptTime: string
  doctor?: string | null
  source?: string
  operator?: string
}

export const createAppointment = (cmd: CreateApptCmd) =>
  client.post<AppointmentView>('/txn/appointment', cmd)

export const rescheduleAppointment = (no: string, apptDate: string, apptTime: string) =>
  client.post<AppointmentView>(`/txn/appointment/${no}/reschedule`, { apptDate, apptTime })

export const checkIn = (no: string) =>
  client.post<AppointmentView>(`/txn/appointment/${no}/check-in`)

export const cancelAppointment = (no: string) =>
  client.post<AppointmentView>(`/txn/appointment/${no}/cancel`)

export const noShow = (no: string) =>
  client.post<AppointmentView>(`/txn/appointment/${no}/no-show`)

export const listAppointments = (storeCode?: string, date?: string) =>
  client.get<AppointmentView[]>('/txn/appointment', { params: { storeCode, date } })

export const appointmentBoard = (storeCode?: string, date?: string) =>
  client.get<BoardStats>('/txn/appointment/board', { params: { storeCode, date } })

/** 客户跨店预约/到店交叉校验（撞单辅助） */
export const crossCheck = (customerId: string) =>
  client.get<AppointmentView[]>(`/txn/appointment/cross-check/${customerId}`)
