/**
 * 术后回访域 API（C 端，C-B5）
 * 对齐 c-service CFollowupController 实测契约：
 * - GET  /c/followups → 本人回访列表（行级隔离，plan_date 倒序，status 与前端同词 PENDING/DONE/SKIPPED）
 * - POST /c/followups/{id}/submit → 客户自评提交（满意度 1-5 前置校验；归属双保险；
 *   非 PENDING 幂等直返；越权/不存在统一 400 不泄露存在性）
 * 列表拉取 silent；提交动作非 silent，txn 中文错误原码原话由 http 层 toast 弹出。
 */
import { http } from '@/utils/request'

/** 回访列表项（list 投影） */
export interface CFollowupItem {
  id: string
  followupNo: string
  customerName: string
  project: string
  planDate: string
  status: 'PENDING' | 'DONE' | 'SKIPPED'
  satisfaction: number | null
  note: string | null
  doneAt: string | null
}

export interface FollowupSubmitPayload {
  satisfaction: number
  note?: string
  adverseReaction?: boolean
  adverseNote?: string
}

/** 提交响应（txn internal 投影：DONE + 恢复情况 + 不良反应流转态） */
export interface FollowupSubmitResult {
  id: string
  followupNo: string
  customerId: string
  status: string
  satisfaction: number
  recovery: string
  adverseReaction: boolean
  adverseStatus: string | null
  doneAt: string | null
}

export async function fetchFollowups(): Promise<CFollowupItem[]> {
  const list = await http.get<CFollowupItem[]>('/c/followups', { silent: true })
  return Array.isArray(list) ? list : []
}

export async function submitFollowup(id: string, payload: FollowupSubmitPayload): Promise<FollowupSubmitResult> {
  return http.post<FollowupSubmitResult>(`/c/followups/${encodeURIComponent(id)}/submit`, payload)
}
