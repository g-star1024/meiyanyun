import client from './client'

export interface PerfStaffDto {
  id: number
  period: string
  storeCode: string
  name: string
  role: string
  title: string
  avatarLetter: string
  target: number
  actual: number
  orders: number
  commissionRate: number
  status: string
  joinedAt: string
  trend: number[]
}

export function listPerfStaff(params: { period?: string; role?: string; storeCode?: string }) {
  return client.get<PerfStaffDto[]>('/stores/perf-staff', { params })
}

export function getPerfStaff(id: string | number) {
  return client.get<PerfStaffDto>(`/stores/perf-staff/${id}`)
}

export function updatePerfTarget(id: string | number, target: number) {
  return client.post<PerfStaffDto>(`/stores/perf-staff/${id}/target`, { target })
}
