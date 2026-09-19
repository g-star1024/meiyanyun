// ============================================================
// 通用异常中心 API（P5-B63 卡2 / L83）
// 三源只读归集：BOM 自动扣料失败 / 划扣核销异常 / 财务异常账单，
// 统一挂 txn-service（/txn/exceptions），中心零写操作，处置跳源视图。
// 权限码：exception:view（异常工作台菜单可见即只读）。
// ============================================================
import client from './client'

/** 异常来源：BOM_DEDUCT=自动扣料失败；WRITEOFF=划扣核销异常；FIN_ABNORMAL=财务异常账单。 */
export type ExceptionSource = 'BOM_DEDUCT' | 'WRITEOFF' | 'FIN_ABNORMAL'

/** 统一三态：PENDING=待处理；PROCESSING=处理中；CLOSED=已闭环。 */
export type ExceptionStatus = 'PENDING' | 'PROCESSING' | 'CLOSED'

/** 统一级别：HIGH/MEDIUM/LOW。 */
export type ExceptionLevel = 'HIGH' | 'MEDIUM' | 'LOW'

export interface ExceptionTimelineDTO {
  by: string
  text: string
  at: string | null
}

/** 统一异常单据（GET /txn/exceptions，后端 ExceptionCenterItem 14 字段逐一对齐）。 */
export interface ExceptionCenterItemDTO {
  /** 带源前缀统一 ID：BOM:BEX... / WO:WO... / FIN:AB...，详情接口据此分发 */
  id: string
  /** 源单据号，页面标题展示 */
  no: string
  source: ExceptionSource
  storeCode: string
  /** 后端经门店名映射富化；解析失败回落门店码 */
  storeName: string
  /** 统一业务类型，当前恒 BUSINESS */
  type: string
  level: ExceptionLevel
  title: string
  description: string
  status: ExceptionStatus
  /** 责任人（员工工号或工号+姓名，可能为空串） */
  assignee: string
  occurredAt: string
  closedAt: string | null
  timeline: ExceptionTimelineDTO[]
  /** 源处置视图路由：/m2-inventory、/writeoff、/m6-abnormal */
  disposeRoute: string
}

/** 异常列表：source/status/storeCode 均可选；门店角色后端强制本店数据域。 */
export const listExceptions = (params?: { source?: string; status?: string; storeCode?: string }) =>
  client.get<ExceptionCenterItemDTO[]>('/txn/exceptions', { params })

/** 异常详情：id 为带源前缀统一 ID（BOM:/WO:/FIN:），后端按前缀分发三源。 */
export const getException = (id: string) =>
  client.get<ExceptionCenterItemDTO>(`/txn/exceptions/${encodeURIComponent(id)}`)
