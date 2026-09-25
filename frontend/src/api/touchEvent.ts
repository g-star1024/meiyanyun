import client from './client'

// ============================================================
// T2-01 触点采集监控 API 薄封装（P5-B98 接真 marketing-service）
// 数据源＝touch_event 五通道聚合（summary）；同步任务＝触点时间线（recent）。
// 两端点 @RequirePerm("collect:view")（PermissionMatrix 预埋零新码）。
// ============================================================

/** 触点类型五值（与后端 TOUCH_TYPES 固定序一致）。 */
export type TouchType = 'LANDING_VISIT' | 'LANDING_LEAD' | 'POSTER_SCAN' | 'PUSH_SEND' | 'RETURNBACK'

/** 通道聚合行：count 累计触点；todayCount 今日触点（Asia/Shanghai 业务日）；EMPTY=尚无触点不伪造。 */
export interface TouchChannelRow {
  touchType: TouchType
  count: number
  todayCount: number
  latestAt: string | null
  status: 'EMPTY' | 'ACTIVE'
}

export interface TouchSummaryResp {
  channels: TouchChannelRow[]
  total: number
}

/** 触点时间线行（touch_event 表直出，at 为 ISO 串）。 */
export interface TouchEventRow {
  id: number
  customerId: string | null
  channel: string
  touchType: TouchType
  refType: string | null
  refId: string | null
  clientToken: string | null
  payload: string | null
  at: string
}

/** 五通道聚合（touch_type 固定序，零计数通道 status=EMPTY）。 */
export const fetchTouchSummary = () =>
  client.get<TouchSummaryResp>('/marketing/touch-events/summary')

/** 触点时间线倒序（limit 服务端封顶 200）。 */
export const fetchTouchRecent = (limit = 100) =>
  client.get<TouchEventRow[]>('/marketing/touch-events/recent', { params: { limit } })
