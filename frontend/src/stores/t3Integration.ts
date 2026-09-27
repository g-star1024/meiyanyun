// ============================================================
// T3-04 集成中心 store（T3-B3 切真 org-service）
// 连接器（支付/医保/企微/税控/广告/金蝶/用友）
// 凭证后端加密存储（仅回显掩码）+ 单向镜像 + transaction_id 幂等
// 调用日志 + Outbox 出站消息 + T+1 三方对账
// 对齐 T-G-中台与通用.md T3-04 详设
// 数据源：/api/org/integration（V70~V73 落库；类级 integration:view，
// 创建=integration:create，编辑/测试=integration:edit，同步/重发=integration:sync，
// 对账=integration:reconcile）。
// 红线：①单向镜像绝不反向写资金池 ②绝不假装已连通（状态仅真实探测驱动）
// ③只建链路本体不伪造三方对接
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { useToast } from '@/composables/useToast'
import { errMsg } from './m5Coupon'
import * as api from '@/api/t3Integration'
import type {
  BatchView,
  CallLogView,
  ConnectorView,
  OutboxView,
  SyncResult,
  TestConnectorResult,
  UpdateConnectorReq,
} from '@/api/t3Integration'

// ---- 类型 ----
export type ConnectorType =
  | 'PAYMENT'    // 支付（微信/支付宝/银联）
  | 'INSURANCE'  // 医保
  | 'WECOM'      // 企业微信
  | 'TAX'        // 税控/发票
  | 'ADS'        // 广告投放
  | 'KINGDEE'    // 金蝶 ERP
  | 'YONYOU'     // 用友 ERP

/** SYNCING 为运行瞬时态（不落库），仅本地在测试/同步进行中呈现 */
export type ConnectorStatus = 'CONNECTED' | 'DISCONNECTED' | 'ERROR' | 'SYNCING'

export interface Connector {
  /** String(后端 Long id)；回传后端时直接作路径/参数段，零精度损失 */
  id: string
  type: ConnectorType
  name: string
  endpoint: string
  /** 凭证掩码（后端加密存储，仅回显；空串=未配置） */
  credentialKey: string
  status: ConnectorStatus
  /** 同步方向：UNIDIRECTIONAL = 单向镜像（红线） */
  syncMode: 'UNIDIRECTIONAL'
  lastSyncAt: string | null
  lastError: string | null
  /** 调用次数统计 */
  callCount24h: number
  errorCount24h: number
  createdAt: string
}

export interface CallLog {
  id: string
  connectorId: string
  connectorName: string
  /** 幂等键 */
  transactionId: string
  direction: 'OUT' | 'IN'
  method: string
  endpoint: string
  statusCode: number
  latencyMs: number
  /** OUT=已发送 / ACK=三方已确认 / FAIL=失败 */
  status: 'SENT' | 'ACK' | 'FAIL'
  requestAt: string
  errorMsg?: string
}

/** Outbox 出站消息（复用 financeCore 模式） */
export interface OutboxMessage {
  /** 显示锚 = 后端 outbox_no（OB-yyyyMMdd-seq，UK）；后端主键 id 由适配层映射表保管 */
  outboxId: string
  connectorId: string
  connectorName: string
  bizType: 'ORDER_PAY' | 'REFUND' | 'INVOICE' | 'VOUCHER' | 'CONTACT' | 'AD_CLICK'
  txnNo: string
  /** 金额（元；后端 txn 侧分换算 movePointLeft(2) 后即元，前端零换算） */
  amount?: number
  /** 本地已记录 / 三方已确认 / 对账完成 */
  localSent: boolean
  remoteAck: boolean
  reconciled: boolean
  /** 后端 chk 六值：MATCHED/PENDING/LONG/SHORT/FAILED + ACK（三方已确认待对账） */
  status: 'MATCHED' | 'PENDING' | 'LONG' | 'SHORT' | 'FAILED' | 'ACK'
  occurredAt: string
  reconciledAt?: string
}

/** T+1 对账批次 */
export interface ReconcileBatch {
  /** 显示锚 = 后端 batch_no（REC-yyyyMMdd-seq，UK） */
  id: string
  connectorId: string
  connectorName: string
  date: string // yyyy-MM-dd（后端 biz_date）
  totalCount: number
  matchedCount: number
  pendingCount: number
  longCount: number
  shortCount: number
  failedCount: number
  /** 金额（元，后端即元，零换算） */
  totalAmount: number
  diffAmount: number
  status: 'RUNNING' | 'DONE' | 'FAILED'
  startedAt: string
  finishedAt?: string
  /** uk(connector_id,biz_date) 幂等重放命中（后端如实返回，未重复记账） */
  alreadyExisted: boolean
}

const CONNECTOR_TYPE_LABEL: Record<ConnectorType, string> = {
  PAYMENT: '支付渠道',
  INSURANCE: '医保接口',
  WECOM: '企业微信',
  TAX: '税控/发票',
  ADS: '广告投放',
  KINGDEE: '金蝶 ERP',
  YONYOU: '用友 ERP',
}

const CONNECTOR_STATUS_LABEL: Record<ConnectorStatus, string> = {
  CONNECTED: '已连接',
  DISCONNECTED: '未连接',
  ERROR: '异常',
  SYNCING: '同步中',
}

export const useT3IntegrationStore = defineStore('t3Integration', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()
  const toast = useToast()

  const connectors = ref<Connector[]>([])
  const callLogs = ref<CallLog[]>([])
  const outbox = ref<OutboxMessage[]>([])
  const batches = ref<ReconcileBatch[]>([])
  const loaded = ref(false)
  const loading = ref(false)
  const loadError = ref('')

  /** outboxNo（前端显示锚）→ 后端主键 id（重发写操作寻址用） */
  const outboxNumId = new Map<string, number>()

  // ---- 适配层：后端 View → 前端类型（铁律 -1-B：前后端差异只在此消化） ----
  function mapConnector(v: ConnectorView): Connector {
    return {
      id: String(v.id),
      type: v.type as ConnectorType,
      name: v.name,
      endpoint: v.endpoint,
      credentialKey: v.credentialKey ?? '',
      status: v.status as ConnectorStatus,
      syncMode: 'UNIDIRECTIONAL',
      lastSyncAt: v.lastSyncAt,
      lastError: v.lastError,
      callCount24h: v.callCount24h,
      errorCount24h: v.errorCount24h,
      createdAt: v.createdAt,
    }
  }

  function mapLog(v: CallLogView): CallLog {
    return {
      id: String(v.id),
      connectorId: String(v.connectorId),
      connectorName: v.connectorName,
      transactionId: v.transactionId,
      direction: v.direction === 'IN' ? 'IN' : 'OUT',
      method: v.method ?? '—',
      endpoint: v.endpoint ?? '—',
      statusCode: v.statusCode ?? 0,
      latencyMs: v.latencyMs ?? 0,
      status: v.status as CallLog['status'],
      requestAt: v.requestAt,
      errorMsg: v.errorMsg ?? undefined,
    }
  }

  function mapOutbox(v: OutboxView): OutboxMessage {
    outboxNumId.set(v.outboxNo, v.id)
    return {
      outboxId: v.outboxNo,
      connectorId: String(v.connectorId),
      connectorName: v.connectorName,
      bizType: v.bizType as OutboxMessage['bizType'],
      txnNo: v.txnNo,
      amount: v.amount ?? undefined,
      localSent: v.localSent,
      remoteAck: v.remoteAck,
      reconciled: v.reconciled,
      status: v.status as OutboxMessage['status'],
      occurredAt: v.occurredAt,
      reconciledAt: v.reconciledAt ?? undefined,
    }
  }

  function mapBatch(v: BatchView): ReconcileBatch {
    return {
      id: v.batchNo,
      connectorId: v.connectorId === 0 ? 'ALL' : String(v.connectorId),
      connectorName: v.connectorName,
      date: v.bizDate,
      totalCount: v.totalCount,
      matchedCount: v.matchedCount,
      pendingCount: v.pendingCount,
      longCount: v.longCount,
      shortCount: v.shortCount,
      failedCount: v.failedCount,
      totalAmount: v.totalAmount,
      diffAmount: v.diffAmount,
      status: v.status as ReconcileBatch['status'],
      startedAt: v.startedAt ?? '',
      finishedAt: v.finishedAt ?? undefined,
      alreadyExisted: v.alreadyExisted,
    }
  }

  // ---- 查询 ----
  function getConnector(id: string) {
    return connectors.value.find((c) => c.id === id)
  }

  const connectedCount = computed(() => connectors.value.filter((c) => c.status === 'CONNECTED').length)
  const errorCount = computed(() => connectors.value.filter((c) => c.status === 'ERROR').length)
  const pendingOutbox = computed(() => outbox.value.filter((o) => o.status === 'PENDING' || o.status === 'FAILED').length)

  const outboxMatched = computed(() => outbox.value.filter((o) => o.status === 'MATCHED').length)
  const outboxLong = computed(() => outbox.value.filter((o) => o.status === 'LONG').length)
  const outboxShort = computed(() => outbox.value.filter((o) => o.status === 'SHORT').length)

  const totalCalls24h = computed(() => connectors.value.reduce((s, c) => s + c.callCount24h, 0))
  const totalErrors24h = computed(() => connectors.value.reduce((s, c) => s + c.errorCount24h, 0))
  const errorRate = computed(() => totalCalls24h.value ? ((totalErrors24h.value / totalCalls24h.value) * 100).toFixed(2) : '0.00')

  const recentLogs = computed(() =>
    [...callLogs.value].sort((a, b) => b.requestAt.localeCompare(a.requestAt)).slice(0, 100))

  function canEdit() { return auth.can('integration:edit') }

  function replaceConnector(next: Connector) {
    const idx = connectors.value.findIndex((c) => c.id === next.id)
    if (idx >= 0) connectors.value.splice(idx, 1, next)
    else connectors.value.unshift(next)
  }

  function replaceOutbox(next: OutboxMessage) {
    const idx = outbox.value.findIndex((o) => o.outboxId === next.outboxId)
    if (idx >= 0) outbox.value.splice(idx, 1, next)
    else outbox.value.unshift(next)
  }

  // ---- 装载 ----
  async function refreshConnectors() {
    connectors.value = (await api.listConnectors()).map(mapConnector)
  }

  async function refreshCallLogs() {
    callLogs.value = (await api.listCallLogs({ limit: 200 })).map(mapLog)
  }

  async function refreshOutbox() {
    outbox.value = (await api.listOutbox({ limit: 200 })).map(mapOutbox)
  }

  async function refreshBatches() {
    batches.value = (await api.listReconcileBatches(50)).map(mapBatch)
  }

  async function load() {
    loading.value = true
    loadError.value = ''
    try {
      const [cs, ls, os, bs] = await Promise.all([
        api.listConnectors(),
        api.listCallLogs({ limit: 200 }),
        api.listOutbox({ limit: 200 }),
        api.listReconcileBatches(50),
      ])
      connectors.value = cs.map(mapConnector)
      callLogs.value = ls.map(mapLog)
      outbox.value = os.map(mapOutbox)
      batches.value = bs.map(mapBatch)
      loaded.value = true
    } catch (e) {
      loadError.value = errMsg(e)
      console.warn('[t3Integration] 集成中心数据加载失败', e)
    } finally {
      loading.value = false
    }
  }

  /** 进页装载（B86 范式：每次进页重拉真实数据） */
  async function seed() {
    await load()
  }

  // ---- 命令 ----
  async function createConnector(input: {
    name: string
    type: ConnectorType
    endpoint: string
    credentialKey: string
    syncMode: 'UNIDIRECTIONAL'
  }): Promise<Connector | null> {
    if (!auth.can('integration:create')) {
      toast.error('无连接器创建权限')
      return null
    }
    try {
      // 抽屉无 code 输入框：适配层自生成业务编码（后端 code UK）
      const v = await api.createConnector({
        code: `CONN-CUSTOM-${Date.now()}`,
        type: input.type,
        name: input.name,
        endpoint: input.endpoint,
        credentialKey: input.credentialKey || undefined,
      })
      const c = mapConnector(v)
      connectors.value.push(c)
      activity.log(auth.user.name, `创建连接器「${c.name}」（${CONNECTOR_TYPE_LABEL[c.type]}）`, c.id)
      toast.success(`连接器「${c.name}」已创建`)
      return c
    } catch (e) {
      toast.error(errMsg(e, '创建连接器失败'))
      return null
    }
  }

  async function updateConnector(id: string, patch: Partial<Pick<Connector, 'name' | 'endpoint' | 'credentialKey'>>): Promise<boolean> {
    if (!canEdit()) {
      toast.error('无连接器编辑权限')
      return false
    }
    const c = getConnector(id)
    if (!c) return false
    try {
      const req: UpdateConnectorReq = {}
      if (patch.name) req.name = patch.name
      if (patch.endpoint) req.endpoint = patch.endpoint
      // 凭证保护：空值/掩码回填（含 * 或与当前掩码一致）视为未修改，绝不明文覆盖真实凭证
      if (patch.credentialKey && patch.credentialKey !== c.credentialKey && !patch.credentialKey.includes('*')) {
        req.credentialKey = patch.credentialKey
      }
      const v = await api.updateConnector(id, req)
      replaceConnector(mapConnector(v))
      activity.log(auth.user.name, `更新连接器「${v.name}」配置`, id)
      toast.success(`连接器「${v.name}」配置已更新`)
      return true
    } catch (e) {
      toast.error(errMsg(e, '更新连接器失败'))
      return false
    }
  }

  /** 测试连接（真实探测 3s/3s 如实返回；SYNCING 仅本地瞬时呈现） */
  async function testConnection(id: string): Promise<TestConnectorResult | null> {
    if (!canEdit()) {
      toast.error('无连接器编辑权限')
      return null
    }
    const c = getConnector(id)
    if (!c) return null
    c.status = 'SYNCING'
    try {
      const r = await api.testConnector(id)
      await refreshConnectors()
      activity.log(auth.user.name, `测试连接器「${c.name}」连接：${r.message}`, id)
      if (r.reachable) toast.success(`「${c.name}」${r.message}（${r.latencyMs}ms）`)
      else toast.error(`「${c.name}」${r.message}`)
      return r
    } catch (e) {
      await refreshConnectors().catch(() => undefined)
      toast.error(errMsg(e, '测试连接失败'))
      return null
    }
  }

  /** 触发单向镜像同步（红线：只从业务侧拉取已支付单，不反向写资金池；结果七字段如实） */
  async function triggerSync(id: string): Promise<SyncResult | null> {
    if (!auth.can('integration:sync')) {
      toast.error('无同步权限')
      return null
    }
    const c = getConnector(id)
    if (!c) return null
    c.status = 'SYNCING'
    try {
      const r = await api.syncConnector(id)
      // 同步真实写入 outbox / call_log / 连接器统计：三路全部重拉
      await Promise.all([refreshConnectors(), refreshOutbox(), refreshCallLogs()])
      activity.log(
        auth.user.name,
        `触发连接器「${c.name}」单向镜像同步：拉取 ${r.pulled}，新建 ${r.created}，幂等跳过 ${r.skipped}，ACK ${r.ack}，失败 ${r.failed}`,
        id,
      )
      if (r.failed > 0) toast.error(`「${c.name}」同步完成：新建 ${r.created}，失败 ${r.failed}（如实）`)
      else toast.success(`「${c.name}」同步完成：新建 ${r.created}，幂等跳过 ${r.skipped}，ACK ${r.ack}`)
      return r
    } catch (e) {
      await Promise.all([refreshConnectors().catch(() => undefined), refreshCallLogs().catch(() => undefined)])
      toast.error(errMsg(e, '同步失败'))
      return null
    }
  }

  /** T+1 对账（本地口径：remote_ack 置 MATCHED；uk(connector_id,biz_date) 幂等重放返 alreadyExisted） */
  async function runReconcile(connectorId?: string): Promise<ReconcileBatch | null> {
    if (!auth.can('integration:reconcile')) {
      toast.error('无对账权限')
      return null
    }
    try {
      const v = await api.runReconcile(connectorId ? { connectorId } : undefined)
      const b = mapBatch(v)
      // 对账回写 outbox 状态并落批次：两路重拉
      await Promise.all([refreshOutbox(), refreshBatches()])
      activity.log(
        auth.user.name,
        `T+1 对账${b.alreadyExisted ? '（幂等重放）' : ''}完成：${b.connectorName}（${b.date}），轧平 ${b.matchedCount} 笔，长款 ${b.longCount}，短款 ${b.shortCount}，失败 ${b.failedCount}`,
      )
      toast.success(
        `T+1 对账完成：${b.connectorName} 轧平 ${b.matchedCount} 笔`
        + `${b.alreadyExisted ? '（幂等重放，未重复记账）' : ''}`,
      )
      return b
    } catch (e) {
      toast.error(errMsg(e, '对账失败'))
      return null
    }
  }

  /** 重发失败消息（幂等复用原 transaction_id；仅 FAILED 可重发，后端 409 中文透出当前态） */
  async function retryMessage(outboxId: string): Promise<boolean> {
    if (!auth.can('integration:sync')) {
      toast.error('无同步权限')
      return false
    }
    const numId = outboxNumId.get(outboxId)
    if (numId == null) {
      toast.error('消息未加载，请刷新后重试')
      return false
    }
    try {
      const v = await api.retryOutbox(numId)
      replaceOutbox(mapOutbox(v))
      activity.log(auth.user.name, `重发消息 ${v.txnNo}（幂等复用原 transaction_id）`, outboxId)
      toast.success(`已重发消息 ${v.txnNo}，等待三方确认`)
      return true
    } catch (e) {
      toast.error(errMsg(e, '重发失败'))
      return false
    }
  }

  return {
    connectors, callLogs, outbox, batches,
    loaded, loading, loadError,
    CONNECTOR_TYPE_LABEL, CONNECTOR_STATUS_LABEL,
    connectedCount, errorCount, pendingOutbox, totalCalls24h, totalErrors24h, errorRate,
    outboxMatched, outboxLong, outboxShort, recentLogs,
    getConnector, canEdit,
    createConnector, updateConnector, testConnection, triggerSync,
    runReconcile, retryMessage, seed,
  }
})
