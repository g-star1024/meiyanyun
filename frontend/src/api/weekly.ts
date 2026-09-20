import client from './client'

export interface WeeklyReportDto {
  id: number
  weekNo: string
  storeCode: string
  startDate: string
  endDate: string
  revenue: number
  prevRevenue: number
  footfall: number
  orders: number
  newCustomers: number
  repurchaseRate: number
  highlights: string
  issues: string
  nextWeekPlan: string
  status: string
  submittedBy?: string | null
  submittedAt?: string | null
}

export interface WeeklySaveBody {
  revenue: number
  footfall: number
  orders: number
  newCustomers: number
  repurchaseRate: number
  highlights: string
  issues: string
  nextWeekPlan: string
}

export function listWeeklyReports(params: { storeCode?: string }) {
  return client.get<WeeklyReportDto[]>('/stores/weekly-reports', { params })
}

export function getWeeklyReport(id: string | number) {
  return client.get<WeeklyReportDto>(`/stores/weekly-reports/${id}`)
}

export function createWeeklyReport(params: { storeCode?: string }) {
  return client.post<WeeklyReportDto>('/stores/weekly-reports', null, { params })
}

export function saveWeeklyReport(id: string | number, body: WeeklySaveBody) {
  return client.put<WeeklyReportDto>(`/stores/weekly-reports/${id}`, body)
}

export function submitWeeklyReport(id: string | number) {
  return client.post<WeeklyReportDto>(`/stores/weekly-reports/${id}/submit`)
}
