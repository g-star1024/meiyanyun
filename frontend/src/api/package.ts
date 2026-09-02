// ============================================================
// Package（套餐/疗程）API
// C 端我的套餐：会员已购卡项/疗程
// ============================================================
import client from './client'

export interface PackageDTO {
  id: string
  name: string
  type: string
  total: number
  used: number
  expire: string
  balance?: string
}

export const listMyPackages = (memberId: string) =>
  client.get<PackageDTO[]>(`/customer/${memberId}/packages`)
