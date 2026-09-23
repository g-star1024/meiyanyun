import client from './client'

// ============================================================
// M5-08 落地页 API 薄封装（P5-B88 接真 marketing-service）
// 后端 formFields/blocks/variants 为 TEXT 存 JSON 串，原样透传，
// 解析在 stores/m5Landing 适配层完成。
// ============================================================

export interface LandingPageRow {
  pageId: string
  pageName: string
  template: string
  status: string
  headline: string | null
  subtitle: string | null
  project: string | null
  formFields: string | null
  blocks: string | null
  variants: string | null
  visits: number
  leads: number
  abEnabled: boolean
  clientToken: string | null
  createdAt: string
  updatedAt: string
}

export interface LandingCreateReq {
  name: string
  template: string
  headline: string
  subtitle: string
  project: string
  formFields: string[]
  clientToken: string
}

export function fetchLandingPages() {
  return client.get<LandingPageRow[]>('/marketing/landing-pages')
}

export function createLandingPage(body: LandingCreateReq) {
  return client.post<LandingPageRow>('/marketing/landing-pages', body)
}

export function publishLandingPage(id: string) {
  return client.post<{ changed: boolean }>(`/marketing/landing-pages/${id}/publish`)
}

export function offlineLandingPage(id: string) {
  return client.post<{ changed: boolean }>(`/marketing/landing-pages/${id}/offline`)
}

export function moveLandingBlock(id: string, body: { blockId: string; direction: number }) {
  return client.post<{ changed: boolean }>(`/marketing/landing-pages/${id}/blocks/move`, body)
}

export function toggleLandingAb(id: string) {
  return client.post<{ changed: boolean }>(`/marketing/landing-pages/${id}/ab/toggle`)
}
