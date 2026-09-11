// ============================================================
// 术后随访 SOP API（对接 txn-service 随访独立域：/api/txn/followup）
//
// SOP 节点由「完成治疗」AFTER_COMMIT 自动排程（1/3/7/30 天共享批次号）；
// 超期未完成由后端定时巡检置 escalated 并升级提醒店长。
// 权限：读 followup:view / 核销 followup:edit；门店由后端按 JWT 数据域过滤。
// ============================================================
import client from './client'

/** 工作台术后随访计数：sopPending=待回访 SOP 节点；sopOverdue=其中计划日期早于今日。 */
export interface FollowupStats {
  sopPending: number
  sopOverdue: number
}

/** 本店术后 SOP 待回访/超期计数（工作台两卡，替代前端种子数据 .length）。 */
export const statsFollowup = (storeCode?: string) =>
  client.get<FollowupStats>('/txn/followup/stats', { params: { storeCode } })
