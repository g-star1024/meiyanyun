// ============================================================
// 风控黑名单 API（对接 customer-service，经网关 /api/customer 前缀）
// M3-B6 / DESIGN-M3 §3 M3-17：risk_record 状态机（提交→待审核→
// 拉黑/观察→解除，非法迁移 400 中文消息）+ risk_rule 开关。
// actor 一律服务端 DataScope.currentActor() 取，请求体不传。
// ============================================================
import client from './client'

// -------------------- 类型 --------------------

export interface RiskTimelineItem {
  action: string
  by: string
  at: string
  comment?: string | null
}

export interface RiskRecordView {
  id: number
  riskNo: string
  customerId: string
  customerName: string
  phoneMask: string
  level: string
  reason: string
  reasonDetail: string
  status: string
  hitCount: number
  blockTransactions: boolean
  operator: string
  createdAt: string
  resolvedAt: string | null
  resolvedBy: string | null
  timeline: RiskTimelineItem[]
}

export interface RiskRuleView {
  id: number
  ruleNo: string
  name: string
  description: string
  enabled: boolean
  action: string
  hitCount: number
}

export interface RiskSubmitCmd {
  customerName: string
  phoneMask: string
  level: string
  reason: string
  detail: string
}

// -------------------- 端点 --------------------

export function listRiskRecords(): Promise<RiskRecordView[]> {
  return client.get('/customer/m3/risk/records').then((r) => r.data)
}

export function listRiskRules(): Promise<RiskRuleView[]> {
  return client.get('/customer/m3/risk/rules').then((r) => r.data)
}

export function submitRiskRecord(cmd: RiskSubmitCmd): Promise<RiskRecordView> {
  return client.post('/customer/m3/risk/records', cmd).then((r) => r.data)
}

export function approveRiskRecord(id: number): Promise<RiskRecordView> {
  return client.post(`/customer/m3/risk/records/${id}/approve`).then((r) => r.data)
}

export function rejectRiskRecord(id: number, reason: string): Promise<RiskRecordView> {
  return client.post(`/customer/m3/risk/records/${id}/reject`, { reason }).then((r) => r.data)
}

export function releaseRiskRecord(id: number, reason: string): Promise<RiskRecordView> {
  return client.post(`/customer/m3/risk/records/${id}/release`, { reason }).then((r) => r.data)
}

export function toggleRiskRule(id: number): Promise<RiskRuleView> {
  return client.post(`/customer/m3/risk/rules/${id}/toggle`).then((r) => r.data)
}
