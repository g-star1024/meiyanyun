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

/** 耗材明细行（领用/报损提交共用）；skuCode 必填，qty 正整数。 */
export interface ConsumableLineCmd {
  skuCode: string
  name?: string
  qty: number
  remark?: string
}

/** 耗材领用提交（B5）：固定双签（店长一审 → 财务终审）；操作人取 JWT，actor 忽略。 */
export interface RequisitionSubmitCmd {
  storeCode: string
  purpose?: string
  lines: ConsumableLineCmd[]
}

/** 耗材报损提交（B5）：amount 为损失金额（分，>0），<¥5000 财务单签 / ≥¥5000 双签；操作人取 JWT。 */
export interface LossReportSubmitCmd {
  storeCode: string
  reason?: string
  amount: number
  lines: ConsumableLineCmd[]
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

/** 耗材领用提交（perm requisition:edit）：固定双签，终审通过后由服务端扣库并落 TK-MATERIAL 成本。 */
export const submitRequisition = (cmd: RequisitionSubmitCmd) =>
  client.post<ApprovalTodoDTO>('/txn/approval/requisition', cmd)

/** 耗材报损提交（perm wastage:edit）：金额分，终审通过后由服务端扣 SCRAP 并落 TK-LOSS 成本。 */
export const submitLossReport = (cmd: LossReportSubmitCmd) =>
  client.post<ApprovalTodoDTO>('/txn/approval/loss-report', cmd)
