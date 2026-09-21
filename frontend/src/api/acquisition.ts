import client from './client'

export interface AcquisitionDto {
  id: number
  no: string
  storeCode: string
  name: string
  type: string
  exposure: number
  arrival: number
  deal: number
  budget: number
  spent: number
  status: string
  startDate: string
  endDate: string
  owner: string
  channel: string
}

export interface AcquisitionCreateBody {
  name: string
  type: string
  budget: number
  channel: string
  startDate?: string
  endDate?: string
  owner?: string
}

export function listAcquisitions(params: { storeCode?: string }) {
  return client.get<AcquisitionDto[]>('/stores/acquisitions', { params })
}

export function getAcquisition(id: string | number) {
  return client.get<AcquisitionDto>(`/stores/acquisitions/${id}`)
}

export function createAcquisition(params: { storeCode?: string }, body: AcquisitionCreateBody) {
  return client.post<AcquisitionDto>('/stores/acquisitions', body, { params })
}

export function launchAcquisition(id: string | number) {
  return client.post<AcquisitionDto>(`/stores/acquisitions/${id}/launch`)
}

export function endAcquisition(id: string | number) {
  return client.post<AcquisitionDto>(`/stores/acquisitions/${id}/end`)
}
