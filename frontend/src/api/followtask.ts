import client from './client'

/**
 * 跟进任务 API（M3-B2 / M3-08 切真）。
 * 后端：marketing-service FollowTaskController（/api/marketing/follow-tasks）。
 * client 响应拦截器不拆包，消费侧取 resp.data。
 */

export type FollowTaskType = 'PHONE' | 'WECHAT' | 'IN_STORE' | 'BIRTHDAY' | 'POST_OP' | 'CONTENT'
export type FollowTaskStatus = 'PENDING' | 'DONE' | 'OVERDUE'
export type FollowTaskPriority = 'HIGH' | 'MEDIUM' | 'LOW'
export type FollowTaskSource = 'MANUAL' | 'CHURN' | 'REPURCHASE'

export interface FollowTaskLogRow {
  by: string
  text: string
  at: string
}

export interface FollowTaskRow {
  id: string
  customerId: string | null
  customerName: string
  customerLevel: string | null
  type: FollowTaskType
  content: string | null
  deadline: string | null
  status: FollowTaskStatus
  priority: FollowTaskPriority
  assignee: string | null
  createdAt: string
  completedAt: string | null
  logs: FollowTaskLogRow[]
  source: FollowTaskSource
  sourceId: string | null
}

export interface FollowTaskKpiPayload {
  pending: number
  dueToday: number
  overdue: number
  doneThisMonth: number
}

export interface FollowTaskListResp {
  tasks: FollowTaskRow[]
  kpi: FollowTaskKpiPayload
}

export interface FollowTaskCreateBody {
  customerId?: string
  customerName: string
  customerLevel?: string
  type: FollowTaskType
  content: string
  deadline: string
  priority?: FollowTaskPriority
  assignee?: string
  storeCode?: string
}

export function fetchFollowTasks(status?: string, storeCode?: string) {
  return client.get<FollowTaskListResp>('/marketing/follow-tasks', {
    params: {
      ...(status && status !== 'ALL' ? { status } : {}),
      ...(storeCode ? { storeCode } : {}),
    },
  })
}

export function createFollowTask(body: FollowTaskCreateBody) {
  return client.post<FollowTaskRow>('/marketing/follow-tasks', body)
}

export function completeFollowTask(followNo: string) {
  return client.post<FollowTaskRow>(`/marketing/follow-tasks/${encodeURIComponent(followNo)}/complete`)
}

export function reassignFollowTask(followNo: string, assignee: string) {
  return client.post<FollowTaskRow>(`/marketing/follow-tasks/${encodeURIComponent(followNo)}/reassign`, { assignee })
}

export function appendFollowTaskLog(followNo: string, text: string) {
  return client.post<FollowTaskRow>(`/marketing/follow-tasks/${encodeURIComponent(followNo)}/logs`, { text })
}
