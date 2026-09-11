// ============================================================
// 术后随访 SOP API（对接 txn-service 随访独立域：/api/txn/followup）
//
// SOP 节点由「完成治疗」AFTER_COMMIT 自动排程（1/3/7/30 天共享批次号）；
// 超期未完成由后端定时巡检置 escalated 并升级提醒店长。
// 权限：读 followup:view / 核销 followup:edit / 手工建 followup:create；门店由后端按 JWT 数据域过滤。
// ============================================================
import client from './client'

export type FollowupStatusDTO = 'PENDING' | 'DONE' | 'SKIPPED'

/**
 * 随访读模型（24 字段，1:1 对齐后端 FollowupController.FollowupView）。
 * 注意：id 为数据库主键 String.valueOf(Long)，路径端点（get/complete/skip）均吃它；
 * followupNo 仅展示业务单号。serviceDate/planDate 为 LocalDate（yyyy-MM-dd），doneAt/createdAt 为 ISO offset 串。
 */
export interface FollowupViewDTO {
  id: string
  followupNo: string
  customerId: string
  customerName: string
  storeCode: string
  project: string
  relatedOrderNo: string | null
  serviceDate: string
  planDate: string
  method: string
  status: FollowupStatusDTO | string
  sopStage: string | null
  sopLabel: string | null
  sopBatchId: string | null
  escalated: boolean
  satisfaction: number | null
  recovery: string | null
  adverseReaction: boolean
  adverseNote: string | null
  needRevisit: boolean
  note: string | null
  followupByName: string | null
  doneAt: string | null
  createdAt: string
}

/** Spring Data 分页响应（page 0 起）。 */
export interface FollowupPage {
  content: FollowupViewDTO[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

/** 本店随访计数九键（LinkedHashMap 固定顺序；avgSatisfaction 为一位小数 number，无记录 0）。 */
export interface FollowupStats {
  sopPending: number
  sopOverdue: number
  pending: number
  todayPending: number
  overdue: number
  done: number
  skipped: number
  avgSatisfaction: number
  adverseCount: number
}

/** 手工建普通随访请求（工作台「新建回访计划」；日期传 yyyy-MM-dd，禁止 toISOString）。 */
export interface CreateFollowupCmd {
  customerId: string
  project: string
  relatedOrderNo?: string
  serviceDate: string
  planDate: string
  method?: string
}

/** 登记回访结果请求（满意度 1-5、恢复情况 GOOD/NORMAL/POOR 必填；勾选不良反应时说明必填，校验在后端）。 */
export interface CompleteFollowupCmd {
  satisfaction: number
  recovery: string
  adverseReaction: boolean
  adverseNote?: string
  needRevisit: boolean
  note?: string
  method?: string
}

export interface ListFollowupParams {
  storeCode?: string
  status?: string
  customerId?: string
  sopBatchId?: string
  sopOnly?: boolean
  keyword?: string
  page?: number
  size?: number
}

/** 随访分页列表：keyword 模糊客户名/项目/关联订单号；排序后端固定（planDate,id 升序）。 */
export const listFollowup = (params?: ListFollowupParams) =>
  client.get<FollowupPage>('/txn/followup', { params })

/** 本店随访计数聚合（工作台两卡 + 台账 KPI/角标九键，替代前端全量 .length）。 */
export const statsFollowup = (storeCode?: string) =>
  client.get<FollowupStats>('/txn/followup/stats', { params: { storeCode } })

/** 随访详情（越权跨店统一 404；id 为数据库主键字符串）。 */
export const getFollowup = (id: string) =>
  client.get<FollowupViewDTO>(`/txn/followup/${id}`)

/** 手工建普通随访（sopStage=MANUAL；客户须已建档，否则后端 400 中文引导先建档）。 */
export const createFollowup = (cmd: CreateFollowupCmd) =>
  client.post<FollowupViewDTO>('/txn/followup', cmd)

/** 登记回访结果（PENDING → DONE）。 */
export const completeFollowup = (id: string, cmd: CompleteFollowupCmd) =>
  client.post<FollowupViewDTO>(`/txn/followup/${id}/complete`, cmd)

/** 标记无需回访（PENDING → SKIPPED），原因必填。 */
export const skipFollowup = (id: string, reason: string) =>
  client.post<FollowupViewDTO>(`/txn/followup/${id}/skip`, { reason })
