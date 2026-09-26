// ============================================================
// 客户旅程 API（对接 customer-service，经网关 /api/customer 前缀）
// M3-B4 / DESIGN-M3 §3 D2/D5/D6：六阶段只读聚合时间轴（预约→到店→
// 咨询→支付→回访→复购）＋触达风险分级；零新表、journey:view 已预埋。
// 契约：金额已后端分→元、手机已脱敏、KPI 四数为候选全集口径（不随 limit 截断）。
// ============================================================
import client from './client'

export interface JourneyNodeDto {
  stage: string
  date: string
  title: string
  desc: string
  amount?: number | null
  operator?: string | null
  done: boolean
}

export interface JourneyCustomerDto {
  id: string | number
  name: string
  avatarLetter: string
  phoneMask: string
  level: string
  currentStage: string
  risk: string
  nodes: JourneyNodeDto[]
}

export interface JourneyKpiDto {
  inProgress: number
  convertedThisWeek: number
  avgDays: number
  churnRisk: number
}

export interface JourneyViewDto {
  days: number
  kpi: JourneyKpiDto
  customers: JourneyCustomerDto[]
}

export function getJourney(days = 90, limit = 50): Promise<JourneyViewDto> {
  return client.get('/customer/m3/journey', { params: { days, limit } }).then((r) => r.data)
}
