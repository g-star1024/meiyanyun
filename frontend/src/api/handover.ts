// ============================================================
// Handover（交接班 / 双签交接）API —— B83 卡2 L118
// store-service /api/stores/handovers；金额单位「分」（bigint）。
// 状态机：DRAFT → SUBMITTED → CONFIRMED；SUBMITTED 可退回 DRAFT。
// ============================================================
import client from './client'

export interface HandoverTodoDTO {
  id: number
  kind: 'CUSTOMER' | 'TASK' | 'ISSUE'
  content: string
  urgent: boolean
  done: boolean
}

/** 时间线条目：at 经 jsonb 往返后为 epoch 秒（number）或 ISO 字符串，适配层统一归一化 */
export interface HandoverTimelineDTO {
  at: string | number
  by: string
  action: string
  detail?: string
}

export interface HandoverDTO {
  id: number
  handoverNo: string
  storeCode: string
  shift: 'MORNING' | 'EVENING' | 'FULL'
  /** 交班营业日 yyyy-MM-dd */
  date: string
  status: 'DRAFT' | 'SUBMITTED' | 'CONFIRMED'
  fromName: string
  toName: string
  /** 本班业绩（分） */
  revenueAmount: number
  orderCount: number
  arrivalCount: number
  todos: HandoverTodoDTO[]
  importantNote: string
  cashNote: string
  equipmentNote: string
  confirmNote: string
  submittedAt: string | null
  confirmedAt: string | null
  createdAt: string | null
  timeline: HandoverTimelineDTO[]
}

export const listHandovers = (status?: string) =>
  client.get<HandoverDTO[]>('/stores/handovers', { params: status ? { status } : {} })

export const createHandover = (cmd: { shift: string; date: string; toName: string }) =>
  client.post<HandoverDTO>('/stores/handovers', cmd)

/** 草稿保存（仅 DRAFT）：金额单位「分」 */
export const updateHandoverDraft = (id: number, cmd: {
  toName?: string
  revenueAmount?: number
  orderCount?: number
  arrivalCount?: number
  importantNote?: string
  cashNote?: string
  equipmentNote?: string
}) => client.post<HandoverDTO>(`/stores/handovers/${id}/draft`, cmd)

export const addHandoverTodo = (id: number, cmd: {
  content: string
  kind: string
  urgent?: boolean
}) => client.post<HandoverDTO>(`/stores/handovers/${id}/todos`, cmd)

export const removeHandoverTodo = (id: number, todoId: number) =>
  client.delete<HandoverDTO>(`/stores/handovers/${id}/todos/${todoId}`)

/** 已交接单勾选跟进事项（仅 CONFIRMED） */
export const toggleHandoverTodo = (id: number, todoId: number) =>
  client.post<HandoverDTO>(`/stores/handovers/${id}/todos/${todoId}/toggle`)

export const submitHandover = (id: number) =>
  client.post<HandoverDTO>(`/stores/handovers/${id}/submit`)

export const confirmHandover = (id: number, confirmNote?: string) =>
  client.post<HandoverDTO>(`/stores/handovers/${id}/confirm`, { confirmNote })

export const sendBackHandover = (id: number, confirmNote: string) =>
  client.post<HandoverDTO>(`/stores/handovers/${id}/send-back`, { confirmNote })
