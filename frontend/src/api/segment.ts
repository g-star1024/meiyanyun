// ============================================================
// 客群分群 API（对接 customer-service，经网关 /api/customer 前缀）
// M3-B3 / DESIGN-M3 §4 B3：segment_def CRUD + RULE 实时计算命中 +
// 命中批量跟进任务（软降级）+ 一键回推标签 + 画像 apply 回推分群（D3-3 第三条）。
// 条件契约：conditions 为六 kind 结构化数组（DORMANT_DAYS / VISIT_IN_DAYS /
// SPEND_RANGE / LEVEL_GTE / TAG_ANY / CREATED_IN_DAYS），label 为人读文案直展。
// ============================================================
import client from './client'

// -------------------- 类型 --------------------

export interface SegmentCond {
  kind: string
  days?: number | null
  times?: number | null
  min?: number | null
  max?: number | null
  level?: string | null
  tags?: string[] | null
  label: string
}

export interface SegmentMember {
  id: string
  name: string
  level: string
  lastVisit: string
  matched: string[]
}

export interface SegmentView {
  id: number
  segmentNo: string
  name: string
  type: string
  aiStatus: string
  conditions: string[]
  ruleSummary: string
  customerCount: number
  sharePct: number
  aiSuggestion: string | null
  updatedAt: string
  members: SegmentMember[]
}

export interface SegmentCreateCmd {
  name: string
  type: string
  conditions: SegmentCond[]
  storeCode?: string | null
  clientToken?: string | null
}

export interface SegmentUpdateCmd {
  name?: string
  type?: string
  conditions?: SegmentCond[]
}

export interface SegmentRefreshResult {
  id: number
  customerCount: number
  sharePct: number
  scanned: number
}

export interface SegmentFollowTaskResult {
  id: number
  matched: number
  created: number
}

export interface SegmentApplyTagsResult {
  id: number
  tagId: string
  matched: number
  assigned: number
}

export interface SyncProfileCmd {
  profileId: number
  customerId: string
  customerName: string
  groups: string[]
  tags: string[]
}

export interface SyncProfileResult {
  tagsAssigned: number
  groupsCreated: string[]
  groupsExisted: string[]
}

// -------------------- 端点 --------------------

export function listSegments(): Promise<SegmentView[]> {
  return client.get('/customer/m3/segments').then((r) => r.data)
}

export function getSegmentMembers(id: number): Promise<SegmentMember[]> {
  return client.get(`/customer/m3/segments/${id}/members`).then((r) => r.data)
}

export function createSegment(cmd: SegmentCreateCmd): Promise<SegmentView> {
  return client.post('/customer/m3/segments', cmd).then((r) => r.data)
}

export function updateSegment(id: number, cmd: SegmentUpdateCmd): Promise<SegmentView> {
  return client.put(`/customer/m3/segments/${id}`, cmd).then((r) => r.data)
}

export function deleteSegment(id: number): Promise<void> {
  return client.delete(`/customer/m3/segments/${id}`).then((r) => r.data)
}

export function refreshSegment(id: number): Promise<SegmentRefreshResult> {
  return client.post(`/customer/m3/segments/${id}/refresh`).then((r) => r.data)
}

export function followTasksBySegment(id: number): Promise<SegmentFollowTaskResult> {
  return client.post(`/customer/m3/segments/${id}/follow-tasks`).then((r) => r.data)
}

export function applySegmentTags(id: number): Promise<SegmentApplyTagsResult> {
  return client.post(`/customer/m3/segments/${id}/apply-tags`).then((r) => r.data)
}

export function syncProfileToSegment(cmd: SyncProfileCmd): Promise<SyncProfileResult> {
  return client.post('/customer/m3/segments/sync-profile', cmd).then((r) => r.data)
}
