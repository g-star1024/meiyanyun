// ============================================================
// Approval（审批中心 T3-01）API
// 统一聚合八类双签业务待办：退款/退卡经审批动作回写 txn 状态机形成闭环。
// 后端实体字段直接序列化（Jackson camelCase）；金额单位「分」，history 为 JSON 字符串。
// ============================================================
import client from './client'

/** 审批待办实体 DTO（approval_todo 表行）。金额单位「分」。 */
export interface ApprovalTodoDTO {
  todoNo: string
  bizType: string // REFUND/CARD_CANCEL/TRANSFER/LEAVE/PROCUREMENT/PRICE_CHANGE/LOSS_REPORT/REQUISITION
  bizNo: string
  title: string
  summary: string
  amount: number | null // 分；无金额业务为空
  applicant: string
  applicantRole: string | null
  signTier: string // L1/L2/L3
  status: string // PENDING/APPROVED/REJECTED/TRANSFERRED
  stage: string // REVIEW/FINANCE
  priority: string // HIGH/MEDIUM/LOW
  storeName: string | null
  assignee: string | null
  coSigners: string | null // 逗号分隔串，适配层拆数组
  history: string // JSON 数组字符串 [{actor,action,comment,at},...]
  submittedAt: string
  dueAt: string | null
  createdAt: string | null
}

export interface ApprovalActionCmd {
  actor: string
  comment?: string
}

export interface ApprovalTransferCmd {
  actor: string
  to: string
  comment?: string
}

export interface ApprovalAddSignerCmd {
  actor: string
  who: string
}

/** 待办列表：tab=todo/done/all；bizType 可选过滤。 */
export const listApprovals = (params: { tab?: string; bizType?: string }) =>
  client.get<ApprovalTodoDTO[]>('/txn/approval', { params })

export const getApproval = (todoNo: string) =>
  client.get<ApprovalTodoDTO>(`/txn/approval/${todoNo}`)

export const approveTodo = (todoNo: string, cmd: ApprovalActionCmd) =>
  client.post<ApprovalTodoDTO>(`/txn/approval/${todoNo}/approve`, cmd)

export const rejectTodo = (todoNo: string, cmd: ApprovalActionCmd) =>
  client.post<ApprovalTodoDTO>(`/txn/approval/${todoNo}/reject`, cmd)

export const transferTodo = (todoNo: string, cmd: ApprovalTransferCmd) =>
  client.post<ApprovalTodoDTO>(`/txn/approval/${todoNo}/transfer`, cmd)

export const addSignerTodo = (todoNo: string, cmd: ApprovalAddSignerCmd) =>
  client.post<ApprovalTodoDTO>(`/txn/approval/${todoNo}/add-signer`, cmd)
