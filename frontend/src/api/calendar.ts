import client from './client'

// ============================================================
// M5-09/10 会员日/节日营销 API 薄封装（P5-B88 接真 marketing-service）
// 后端 couponIds/channels 为 TEXT 存 JSON 串；estimatedRevenueCents 单位「分」，
// 元↔分换算在 stores/m5Calendar 适配层完成。
// ============================================================

export interface CalendarNodeRow {
  nodeId: string
  nodeDate: string
  title: string
  nodeType: string
  nodeDesc: string | null
  createdAt: string
}

export interface CalendarScheduleRow {
  scheduleId: string
  nodeId: string
  nodeDate: string
  scheduleName: string
  benefitDesc: string | null
  couponIds: string | null
  channels: string | null
  pointsReward: number
  startDate: string
  endDate: string
  copyText: string | null
  status: string
  estimatedRevenueCents: number
  storeCode: string | null
  createdBy: string | null
  clientToken: string | null
  createdAt: string
  updatedAt: string
}

export interface CalendarScheduleCreateReq {
  nodeId: string
  name: string
  benefitDesc: string
  couponIds: string[]
  pointsReward: number
  startDate: string
  endDate: string
  channels: string[]
  copyText: string
  estimatedRevenueCents: number
  storeCode?: string | null
  clientToken: string
}

export function fetchCalendarNodes() {
  return client.get<CalendarNodeRow[]>('/marketing/calendar/nodes')
}

export function fetchCalendarSchedules() {
  return client.get<CalendarScheduleRow[]>('/marketing/calendar/schedules')
}

export function createCalendarSchedule(body: CalendarScheduleCreateReq) {
  return client.post<CalendarScheduleRow>('/marketing/calendar/schedules', body)
}
