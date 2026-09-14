import client, { getToken } from './client'

// M1 集团经营数据大屏（B49 卡7）：overview 聚合快照（30s 重取，口径单一来源）
// + SSE 实时成交流（仅驱动成交流顶插，不累加 KPI）。
// 约定：金额字段带 fen 标记/后缀时单位为「分」，由 store 层统一换算为元/万元。

export interface ScreenKpiDto {
  key: string
  label: string
  value: number
  /** true 时 value 单位为分（前端 /100 换算元） */
  fen: boolean
  unit: string
  /** 较昨日同口径百分比；昨日为 0 时 null（前端显「—」，不伪造箭头） */
  deltaPct: number | null
}

export interface ScreenHourlyDto { hour: string; amountFen: number }
export interface ScreenCategoryDto { name: string; pct: number }
export interface ScreenStoreRankDto { storeCode: string; amountFen: number }

export interface ScreenOverviewDto {
  kpis: ScreenKpiDto[]
  hourly: ScreenHourlyDto[]
  categoryShare: ScreenCategoryDto[]
  storeRanks: ScreenStoreRankDto[]
  notes: string[]
}

/** SSE order-paid 事件负载（与 ScreenOrderFanoutJob 八键一致） */
export interface ScreenOrderPaidDto {
  paymentId: string
  orderNo: string
  storeCode: string
  customerName: string
  item: string
  amountFen: number
  payMethod: string
  /** ISO-8601（OffsetDateTime） */
  paidAt: string
}

export const getScreenOverview = () => client.get<ScreenOverviewDto>('/txn/screen/overview')

/** SSE 握手地址（EventSource 无法设 Authorization 头，走 ?access_token= 查询参） */
export const screenStreamUrl = () =>
  `/api/txn/screen/stream?access_token=${encodeURIComponent(getToken())}`
