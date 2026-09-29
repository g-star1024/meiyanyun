import client from './client'

export interface MetricView {
  modelId: string
  modelName: string
  timestamp: string
  qps: number
  latencyP99: number
  errorRate: number
  driftScore: number
  accuracy: number
}

export interface RuleView {
  id: string
  name: string
  modelId: string
  modelName: string
  metric: string
  threshold: number
  operator: string
  severity: string
  enabled: boolean
  notifyChannels: string[]
  createdAt: string
}

export interface EventView {
  id: string
  ruleId: string
  ruleName: string
  modelId: string
  modelName: string
  severity: string
  status: string
  message: string
  value: number
  threshold: number
  triggeredAt: string
  acknowledgedAt: string | null
  resolvedAt: string | null
  acknowledgedBy: string | null
}

export interface MonitorOverview {
  metrics: MetricView[]
  rules: RuleView[]
  events: EventView[]
}

export interface CreateRuleCmd {
  name: string
  modelId: string
  modelName?: string
  metric: string
  threshold: number
  operator: string
  severity: string
  enabled?: boolean
  notifyChannels?: string[]
}

export interface UpdateRuleCmd {
  name?: string
  metric?: string
  threshold?: number
  operator?: string
  severity?: string
  notifyChannels?: string[]
}

const BASE = '/ai/t4/monitor'

export function overview(): Promise<MonitorOverview> {
  return client.get(BASE).then((r) => r.data)
}

export function createRule(cmd: CreateRuleCmd): Promise<RuleView> {
  return client.post(`${BASE}/rules`, cmd).then((r) => r.data)
}

export function updateRule(code: string, cmd: UpdateRuleCmd): Promise<RuleView> {
  return client.put(`${BASE}/rules/${code}`, cmd).then((r) => r.data)
}

export function setEnabled(code: string, enabled: boolean): Promise<RuleView> {
  return client.post(`${BASE}/rules/${code}/enabled`, { enabled }).then((r) => r.data)
}

export function acknowledge(code: string): Promise<EventView> {
  return client.post(`${BASE}/events/${code}/acknowledge`).then((r) => r.data)
}

export function resolve(code: string): Promise<EventView> {
  return client.post(`${BASE}/events/${code}/resolve`).then((r) => r.data)
}
