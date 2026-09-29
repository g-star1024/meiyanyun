import client from './client'

export interface FeatureView {
  code: string
  name: string
  group: string
  type: string
  valueType: string
  description: string
  source: string
  status: string
  owner: string
  onlineServing: boolean
  ttl: string | null
  callCount30d: number
  freshness: string
  version: string
  createdAt: string
  updatedAt: string
}

export interface LineageNodeView {
  id: string
  name: string
  type: string
}

export interface LineageEdgeView {
  from: string
  to: string
}

export interface FeatureOverview {
  features: FeatureView[]
  lineage: {
    nodes: LineageNodeView[]
    edges: LineageEdgeView[]
  }
}

export interface RegisterCmd {
  name: string
  group: string
  type: string
  valueType: string
  description: string
  source: string
  owner: string
  onlineServing?: boolean
  ttl?: string
  freshness: string
}

const BASE = '/ai/t4/features'

export function overview(): Promise<FeatureOverview> {
  return client.get(BASE).then((r) => r.data)
}

export function register(cmd: RegisterCmd): Promise<FeatureView> {
  return client.post(`${BASE}/register`, cmd).then((r) => r.data)
}

export function publish(code: string): Promise<FeatureView> {
  return client.post(`${BASE}/${code}/publish`).then((r) => r.data)
}

export function deprecate(code: string): Promise<FeatureView> {
  return client.post(`${BASE}/${code}/deprecate`).then((r) => r.data)
}

export function updateServing(code: string, online: boolean): Promise<FeatureView> {
  return client.post(`${BASE}/${code}/serving`, { online }).then((r) => r.data)
}
