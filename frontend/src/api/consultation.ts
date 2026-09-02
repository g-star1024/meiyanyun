// ============================================================
// Consultation 聚合 API（对接 txn-service 客情/咨询域）
// ============================================================
import client from './client'

export interface ConsultationDTO {
  consultId: string
  customerId: string
  storeCode: string | null
  allergyHistory: string | null
  drugAllergy: string | null
  scarConstitution: string
  pregnancy: string
  coagulationAbn: string
  skinStatus: string | null
  needs: string | null
  consultant: string | null
}

export const createConsultation = (cmd: Partial<ConsultationDTO>) =>
  client.post<ConsultationDTO>('/txn/consultation', cmd)

export const listConsultations = (customerId: string) =>
  client.get<ConsultationDTO[]>(`/txn/consultation/${customerId}`)
