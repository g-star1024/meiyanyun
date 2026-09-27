import client from './client'

export interface RuleView {
  id: number
  name: string
  table: string
  column: string
  type: string
  severity: string
  expression: string
  enabled: boolean
  lastCheckAt: string | null
  passRate: number
  errorCount: number
  owner: string
  createdAt: string
}

export interface IssueView {
  id: number
  ruleId: number
  ruleName: string
  table: string
  column: string
  sample: string
  count: number
  status: string
  detectedAt: string
  resolvedAt: string | null
}

export interface LineageNodeView {
  id: string
  name: string
  type: string
  x: number
  y: number
}

export interface LineageEdgeView {
  from: string
  to: string
}

export interface LineageView {
  nodes: LineageNodeView[]
  edges: LineageEdgeView[]
}

/** 规则新建/编辑请求体（编辑为 patch 语义全可空，对齐后端 RuleReq）。 */
export interface RuleReq {
  name?: string
  table?: string
  column?: string
  type?: string
  severity?: string
  expression?: string
  enabled?: boolean
}

export function fetchRules(): Promise<RuleView[]> {
  return client.get('/customer/t2/govern/rules').then((r) => r.data)
}

export function createRule(cmd: RuleReq): Promise<RuleView> {
  return client.post('/customer/t2/govern/rules', cmd).then((r) => r.data)
}

export function updateRule(id: number, patch: RuleReq): Promise<RuleView> {
  return client.put(`/customer/t2/govern/rules/${id}`, patch).then((r) => r.data)
}

export function toggleRule(id: number): Promise<RuleView> {
  return client.post(`/customer/t2/govern/rules/${id}/toggle`).then((r) => r.data)
}

export function fetchIssues(): Promise<IssueView[]> {
  return client.get('/customer/t2/govern/issues').then((r) => r.data)
}

export function resolveIssue(id: number): Promise<IssueView> {
  return client.post(`/customer/t2/govern/issues/${id}/resolve`).then((r) => r.data)
}

export function ignoreIssue(id: number): Promise<IssueView> {
  return client.post(`/customer/t2/govern/issues/${id}/ignore`).then((r) => r.data)
}

export function fetchLineage(): Promise<LineageView> {
  return client.get('/customer/t2/govern/lineage').then((r) => r.data)
}
