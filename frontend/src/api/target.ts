// ============================================================
// Target API（对接 finance-service 经营目标域 · B49 卡8）
// 读：GET /api/finance/targets（ownerType/metric/period/approval 四过滤）
// 写：新建（idemKey 幂等）/ 更新进度（仅 APPROVED）/ 提交 / 批准 / 驳回（reason 必填，全审计）
// 口径：目标值/当前值为管理指标（万元/人/%/人次），非资金流水，不走「分」红线。
// ============================================================
import client from './client'

/** 目标行（id=可读业务编号，如 G1/G1-E/T01-R；children=下级目标 id 数组） */
export interface TargetLineDTO {
  id: string
  ownerId: string
  ownerName: string
  /** GROUP 集团 / REGION 区域 / STORE 门店 */
  ownerType: string
  /** REVENUE 营收 / NEW_CUSTOMER 新客 / REPURCHASE_RATE 复购率 / PROCEDURE_COUNT 治疗人次 / SATISFACTION 满意度 */
  metric: string
  /** YEAR 年度 / QUARTER 季度 / MONTH 月度 */
  period: string
  periodLabel: string // 如 2026年度 / 2026-Q3 / 2026-08
  targetValue: number
  currentValue: number
  unit: string // 万元 / 人 / % / 人次
  weight: number // 权重 0~100（集团加权达成用）
  /** DRAFT 草稿 / PENDING 待审批 / APPROVED 已批准 / REJECTED 已驳回 */
  approval: string
  children?: string[]
  submittedBy?: string | null
  approvedBy?: string | null
  rejectReason?: string | null
  aggregatedRevenue?: number | null
}

/** 新建目标入参（targetId 缺省后端生成 T-XXXXXXXX；idemKey 幂等键，重放返回 duplicated=true） */
export interface CreateTargetCmd {
  targetId?: string
  ownerId: string
  ownerName: string
  ownerType: string
  metric: string
  period: string
  periodLabel: string
  targetValue: number
  currentValue?: number
  unit: string
  weight?: number
  childrenIds?: string // 逗号串
  idemKey?: string
}

export interface TargetFilter {
  ownerType?: string
  metric?: string
  period?: string
  approval?: string
}

export const listTargets = (f: TargetFilter = {}) =>
  client.get<TargetLineDTO[]>('/finance/targets', { params: f })

export const createTarget = (cmd: CreateTargetCmd) =>
  client.post<TargetLineDTO & { duplicated?: boolean }>('/finance/targets', cmd)

export const updateTargetProgress = (targetId: string, value: number) =>
  client.put<TargetLineDTO>(`/finance/targets/${encodeURIComponent(targetId)}/progress`, { value })

export const submitTarget = (targetId: string) =>
  client.post<TargetLineDTO>(`/finance/targets/${encodeURIComponent(targetId)}/submit`)

export const approveTarget = (targetId: string) =>
  client.post<TargetLineDTO>(`/finance/targets/${encodeURIComponent(targetId)}/approve`)

export const rejectTarget = (targetId: string, reason: string) =>
  client.post<TargetLineDTO>(`/finance/targets/${encodeURIComponent(targetId)}/reject`, { reason })
