import client from './client'

export interface DailyTodoDto {
  id: number
  content: string
  kind: string
  done: boolean
  urgent: boolean
}

export interface DailyTimelineEntryDto {
  action: string
  by: string
  at: string
}

export interface DailyReportDto {
  id: number
  dailyNo: string
  storeCode: string
  date: string
  status: string
  footfall: number
  orders: number
  services: number
  inventoryAlerts: number
  hourly: number[]
  todos: DailyTodoDto[]
  exceptions: string
  note: string
  submittedBy: string | null
  submittedAt: string | null
  timeline: DailyTimelineEntryDto[]
}

export interface DailyFieldsPayload {
  footfall?: number
  orders?: number
  services?: number
  inventoryAlerts?: number
  exceptions?: string
  note?: string
}

export function listDailyReports(params: { storeCode?: string; status?: string }) {
  return client.get<DailyReportDto[]>('/stores/daily-reports', { params })
}

export function getTodayDailyReport(storeCode?: string) {
  return client.get<DailyReportDto>('/stores/daily-reports/today', { params: { storeCode } })
}

export function getDailyReport(id: string | number) {
  return client.get<DailyReportDto>(`/stores/daily-reports/${id}`)
}

export function saveDailyFields(id: string | number, body: DailyFieldsPayload) {
  return client.post<DailyReportDto>(`/stores/daily-reports/${id}/fields`, body)
}

export function saveDailyHourly(id: string | number, hourly: number[]) {
  return client.post<DailyReportDto>(`/stores/daily-reports/${id}/hourly`, { hourly })
}

export function addDailyTodo(id: string | number, body: { content: string; kind: string; urgent?: boolean }) {
  return client.post<DailyReportDto>(`/stores/daily-reports/${id}/todos`, body)
}

export function toggleDailyTodo(id: string | number, todoId: string | number) {
  return client.post<DailyReportDto>(`/stores/daily-reports/${id}/todos/${todoId}/toggle`, {})
}

export function removeDailyTodo(id: string | number, todoId: string | number) {
  return client.delete<DailyReportDto>(`/stores/daily-reports/${id}/todos/${todoId}`)
}

export function submitDailyReport(id: string | number) {
  return client.post<DailyReportDto>(`/stores/daily-reports/${id}/submit`, {})
}
