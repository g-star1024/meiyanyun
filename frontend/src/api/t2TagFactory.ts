import client from './client'

export interface TagVersionView {
  version: string
  sql: string
  publishedAt: string | null
  publishedBy: string | null
  coverCount: number
}

export interface TagConsumerView {
  module: string
  scene: string
  usedAt: string
}

export interface FactoryTagView {
  id: number
  code: string
  name: string
  category: string
  type: string
  sensitivity: string
  valueType: string
  description: string
  sql: string
  status: string
  coverCount: number
  refreshCron: string
  lastComputeAt: string | null
  versions: TagVersionView[]
  consumers: TagConsumerView[]
  owner: string
  tags: string[]
  createdAt: string
  updatedAt: string
}

export interface PreviewView {
  coverCount: number
}

/** 标签新建/编辑请求体（编辑为 patch 语义全可空，对齐后端 TagReq）。 */
export interface TagReq {
  code?: string
  name?: string
  category?: string
  type?: string
  sensitivity?: string
  valueType?: string
  description?: string
  sql?: string
  refreshCron?: string
  tags?: string[]
}

export function fetchTags(): Promise<FactoryTagView[]> {
  return client.get('/customer/t2/tagfactory/tags').then((r) => r.data)
}

export function createTag(cmd: TagReq): Promise<FactoryTagView> {
  return client.post('/customer/t2/tagfactory/tags', cmd).then((r) => r.data)
}

export function updateTag(id: number, patch: TagReq): Promise<FactoryTagView> {
  return client.put(`/customer/t2/tagfactory/tags/${id}`, patch).then((r) => r.data)
}

export function previewCompute(id: number): Promise<PreviewView> {
  return client.post(`/customer/t2/tagfactory/tags/${id}/preview`).then((r) => r.data)
}

export function publishTag(id: number): Promise<FactoryTagView> {
  return client.post(`/customer/t2/tagfactory/tags/${id}/publish`).then((r) => r.data)
}

export function approvePublish(id: number): Promise<FactoryTagView> {
  return client.post(`/customer/t2/tagfactory/tags/${id}/approve`).then((r) => r.data)
}

export function offlineTag(id: number): Promise<FactoryTagView> {
  return client.post(`/customer/t2/tagfactory/tags/${id}/offline`).then((r) => r.data)
}

export function deleteTag(id: number): Promise<void> {
  return client.delete(`/customer/t2/tagfactory/tags/${id}`).then((r) => r.data)
}
