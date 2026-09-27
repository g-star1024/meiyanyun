// ============================================================
// 客户洞察报告 API（对接 customer-service，经网关 /api/customer 前缀）
// M3-B7 / DESIGN-M3 §3 M3-19：只读聚合（总会员/新增/活跃/复购/流失/LTV/NPS
// ＋近 6 月趋势＋等级分布＋渠道分布＋智能洞察＋高复购 TOP5）；零新表、
// insight:view 已预埋。契约：金额已后端分→元、比率服务端统算保留 1 位小数。
// ============================================================
import client from './client'

export type InsightPeriod = '30d' | '90d' | '12m'

export interface InsightSummaryDto {
  totalCustomers: number
  newThisPeriod: number
  activeRate: number
  repurchaseRate: number
  churnRate: number
  avgLtv: number
  nps: number
}

export interface InsightTrendDto {
  month: string
  newCustomers: number
  activeCustomers: number
  repurchaseRate: number
  churnRate: number
}

export interface LevelDistDto {
  level: string
  count: number
  percent: number
  color: string
}

export interface ChannelDistDto {
  channel: string
  count: number
  percent: number
}

export interface TopInsightDto {
  icon: string
  tone: string
  title: string
  desc: string
}

export interface RepurchaseItemDto {
  name: string
  count: number
  rate: number
  amount: number
}

export interface InsightViewDto {
  period: string
  summary: InsightSummaryDto
  trend: InsightTrendDto[]
  levelDist: LevelDistDto[]
  channelDist: ChannelDistDto[]
  topInsights: TopInsightDto[]
  repurchaseItems: RepurchaseItemDto[]
}

export function getInsight(period: InsightPeriod = '90d'): Promise<InsightViewDto> {
  return client.get('/customer/m3/insight', { params: { period } }).then((r) => r.data)
}
