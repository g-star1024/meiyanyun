// ============================================================
// T3 数据中台 集成中心 API 薄封装（T3-B3 前端四 tab 切真）
// org-service IntegrationConnectorController（/api/org/integration 单数路径，
// 与 B57 接入配置窗口 /api/org/integrations 复数不同路径，零冲突）
// 红线：单向镜像绝不反向写资金池；探测/同步/对账全部如实返回，禁造假
// ============================================================
import client from './client'

/** 连接器视图（对齐后端 ConnectorView） */
export interface ConnectorView {
  id: number
  code: string
  type: string
  name: string
  endpoint: string
  credentialKey: string | null
  status: string
  syncMode: string
  lastSyncAt: string | null
  lastError: string | null
  callCount24h: number
  errorCount24h: number
  createdAt: string
}

/** 新建连接器请求体（code 必填由适配层自生成；对齐后端 CreateRequest） */
export interface CreateConnectorReq {
  code: string
  type: string
  name: string
  endpoint: string
  credentialKey?: string
  forceInsecure?: boolean
}

/** 更新连接器请求体（对齐后端 UpdateRequest；空字段不传） */
export interface UpdateConnectorReq {
  name?: string
  endpoint?: string
  credentialKey?: string
  forceInsecure?: boolean
}

/** 测试连接结果（真实探测如实返回；对齐后端 TestConnectorResult） */
export interface TestConnectorResult {
  reachable: boolean
  status: string
  statusCode: number
  latencyMs: number
  message: string
}

/** 调用日志视图（对齐后端 CallLogView） */
export interface CallLogView {
  id: number
  connectorId: number
  connectorName: string
  transactionId: string
  direction: string
  method: string | null
  endpoint: string | null
  statusCode: number | null
  latencyMs: number | null
  status: string
  requestAt: string
  errorMsg: string | null
}

/** 单向镜像同步结果（如实七字段；对齐后端 SyncResult） */
export interface SyncResult {
  pulled: number
  created: number
  skipped: number
  ack: number
  failed: number
  truncated: boolean
  message: string
}

/** Outbox 出站消息视图（对齐后端 OutboxView；金额单位元） */
export interface OutboxView {
  id: number
  outboxNo: string
  connectorId: number
  connectorName: string
  bizType: string
  txnNo: string
  amount: number | null
  status: string
  localSent: boolean
  remoteAck: boolean
  reconciled: boolean
  errorMsg: string | null
  occurredAt: string
  reconciledAt: string | null
}

/** T+1 对账批次视图（对齐后端 BatchView；alreadyExisted=uk 幂等重放命中） */
export interface BatchView {
  id: number
  batchNo: string
  connectorId: number
  connectorName: string
  bizDate: string
  totalCount: number
  matchedCount: number
  pendingCount: number
  longCount: number
  shortCount: number
  failedCount: number
  totalAmount: number
  diffAmount: number
  status: string
  startedAt: string | null
  finishedAt: string | null
  alreadyExisted: boolean
}

const BASE = '/org/integration'

export function listConnectors(): Promise<ConnectorView[]> {
  return client.get(`${BASE}/connectors`).then((r) => r.data)
}

export function createConnector(cmd: CreateConnectorReq): Promise<ConnectorView> {
  return client.post(`${BASE}/connectors`, cmd).then((r) => r.data)
}

export function updateConnector(id: number | string, cmd: UpdateConnectorReq): Promise<ConnectorView> {
  return client.put(`${BASE}/connectors/${id}`, cmd).then((r) => r.data)
}

/** 测试连接（真实探测 3s/3s：2xx-4xx→CONNECTED，5xx/异常→ERROR，如实返回） */
export function testConnector(id: number | string): Promise<TestConnectorResult> {
  return client.post(`${BASE}/connectors/${id}/test`).then((r) => r.data)
}

export function listCallLogs(params?: { connectorId?: number | string; limit?: number }): Promise<CallLogView[]> {
  return client.get(`${BASE}/call-logs`, { params }).then((r) => r.data)
}

/** 触发单向镜像同步（txn 拉已支付单→uk 幂等落 outbox→真实外呼；拉取失败 502 如实不软降级） */
export function syncConnector(id: number | string): Promise<SyncResult> {
  return client.post(`${BASE}/connectors/${id}/sync`).then((r) => r.data)
}

export function listOutbox(params?: {
  status?: string
  connectorId?: number | string
  limit?: number
}): Promise<OutboxView[]> {
  return client.get(`${BASE}/outbox`, { params }).then((r) => r.data)
}

/** 重发失败消息（仅 FAILED 否则 409 中文透出当前态；幂等复用原 txn_no） */
export function retryOutbox(id: number): Promise<OutboxView> {
  return client.post(`${BASE}/outbox/${id}/retry`).then((r) => r.data)
}

/** T+1 对账（本地口径 matched=remote_ack 置 MATCHED；uk(connector_id,biz_date) 幂等重放返 alreadyExisted） */
export function runReconcile(params?: { connectorId?: number | string; bizDate?: string }): Promise<BatchView> {
  return client.post(`${BASE}/reconcile`, null, { params }).then((r) => r.data)
}

export function listReconcileBatches(limit = 50): Promise<BatchView[]> {
  return client.get(`${BASE}/reconcile-batches`, { params: { limit } }).then((r) => r.data)
}
