// ============================================================
// T3-04 集成中心 store（红线：单向镜像 + Outbox + T+1 对账，绝不碰资金池）
// 连接器（支付/医保/企微/税控/广告/金蝶/用友）
// 凭证加密存储（前端演示用 mask）+ 单向镜像 + transaction_id 幂等
// 调用日志 + Outbox 出站消息 + T+1 三方对账
// 对齐 T-G-中台与通用.md T3-04 详设
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

// ---- 类型 ----
export type ConnectorType =
  | 'PAYMENT'    // 支付（微信/支付宝/银联）
  | 'INSURANCE'  // 医保
  | 'WECOM'      // 企业微信
  | 'TAX'        // 税控/发票
  | 'ADS'        // 广告投放
  | 'KINGDEE'    // 金蝶 ERP
  | 'YONYOU'     // 用友 ERP

export type ConnectorStatus = 'CONNECTED' | 'DISCONNECTED' | 'ERROR' | 'SYNCING'

export interface Connector {
  id: string
  type: ConnectorType
  name: string
  endpoint: string
  /** 凭证（演示 mask，真实环境加密存储） */
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
  outboxId: string
  connectorId: string
  connectorName: string
  bizType: 'ORDER_PAY' | 'REFUND' | 'INVOICE' | 'VOUCHER' | 'CONTACT' | 'AD_CLICK'
  txnNo: string
  amount?: number
  /** 本地已记录 / 三方已确认 / 对账完成 */
  localSent: boolean
  remoteAck: boolean
  reconciled: boolean
  status: 'MATCHED' | 'PENDING' | 'LONG' | 'SHORT' | 'FAILED'
  occurredAt: string
  reconciledAt?: string
}

/** T+1 对账批次 */
export interface ReconcileBatch {
  id: string
  connectorId: string
  connectorName: string
  date: string // yyyy-MM-dd
  totalCount: number
  matchedCount: number
  pendingCount: number
  longCount: number
  shortCount: number
  failedCount: number
  totalAmount: number
  diffAmount: number
  status: 'RUNNING' | 'DONE' | 'FAILED'
  startedAt: string
  finishedAt?: string
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

  const connectors = ref<Connector[]>([])
  const callLogs = ref<CallLog[]>([])
  const outbox = ref<OutboxMessage[]>([])
  const batches = ref<ReconcileBatch[]>([])
  const loaded = ref(false)

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

  // ---- 命令 ----
  function createConnector(input: Omit<Connector, 'id' | 'status' | 'lastSyncAt' | 'lastError' | 'callCount24h' | 'errorCount24h' | 'createdAt'>): Connector {
    if (!auth.can('integration:create')) throw new Error('无连接器创建权限')
    const c: Connector = {
      ...input,
      id: nextId('conn'),
      status: 'DISCONNECTED',
      lastSyncAt: null,
      lastError: null,
      callCount24h: 0,
      errorCount24h: 0,
      createdAt: new Date().toISOString(),
    }
    connectors.value.push(c)
    activity.log(auth.user.name, `创建连接器「${c.name}」（${CONNECTOR_TYPE_LABEL[c.type]}）`, c.id)
    return c
  }

  function updateConnector(id: string, patch: Partial<Pick<Connector, 'name' | 'endpoint' | 'credentialKey'>>) {
    if (!auth.can('integration:edit')) throw new Error('无连接器编辑权限')
    const c = getConnector(id)
    if (!c) return
    Object.assign(c, patch)
    activity.log(auth.user.name, `更新连接器「${c.name}」配置`, id)
  }

  /** 测试连接（不改数据，只改状态） */
  function testConnection(id: string): boolean {
    if (!auth.can('integration:edit')) throw new Error('无连接器编辑权限')
    const c = getConnector(id)
    if (!c) return false
    c.status = 'SYNCING'
    // 模拟：80% 成功
    const ok = Math.random() > 0.2
    setTimeout(() => {
      c.status = ok ? 'CONNECTED' : 'ERROR'
      c.lastSyncAt = new Date().toISOString()
      c.lastError = ok ? null : '连接超时：三方接口无响应（模拟）'
    }, 500)
    return ok
  }

  /** 触发单向镜像同步（红线：只从三方拉取/推送，不反向写资金池） */
  function triggerSync(id: string): number {
    if (!auth.can('integration:sync')) throw new Error('无同步权限')
    const c = getConnector(id)
    if (!c) return 0
    c.status = 'SYNCING'
    // 模拟生成 3~8 条 Outbox 消息
    const count = 3 + Math.floor(Math.random() * 6)
    for (let i = 0; i < count; i++) {
      const isFail = Math.random() < 0.1
      const ob: OutboxMessage = {
        outboxId: nextId('ob'),
        connectorId: c.id,
        connectorName: c.name,
        bizType: c.type === 'KINGDEE' || c.type === 'YONYOU' ? 'VOUCHER'
          : c.type === 'TAX' ? 'INVOICE'
          : c.type === 'PAYMENT' ? (Math.random() > 0.3 ? 'ORDER_PAY' : 'REFUND')
          : c.type === 'WECOM' ? 'CONTACT'
          : c.type === 'ADS' ? 'AD_CLICK'
          : 'ORDER_PAY',
        txnNo: `TX${Date.now()}${Math.floor(Math.random() * 1000).toString().padStart(3, '0')}`,
        amount: Math.round(Math.random() * 50000) / 100,
        localSent: true,
        remoteAck: !isFail,
        reconciled: false,
        status: isFail ? 'FAILED' : 'PENDING',
        occurredAt: new Date().toISOString(),
      }
      outbox.value.unshift(ob)
      // 记录调用日志
      callLogs.value.unshift({
        id: nextId('log'),
        connectorId: c.id,
        connectorName: c.name,
        transactionId: ob.txnNo,
        direction: 'OUT',
        method: 'POST',
        endpoint: c.endpoint,
        statusCode: isFail ? 504 : 200,
        latencyMs: 50 + Math.floor(Math.random() * 300),
        status: isFail ? 'FAIL' : 'SENT',
        requestAt: new Date().toISOString(),
        errorMsg: isFail ? '连接超时' : undefined,
      })
    }
    setTimeout(() => {
      c.status = 'CONNECTED'
      c.lastSyncAt = new Date().toISOString()
      c.callCount24h += count
      c.errorCount24h += outbox.value.filter((o) => o.connectorId === c.id && o.status === 'FAILED').length
    }, 800)
    activity.log(auth.user.name, `触发连接器「${c.name}」单向镜像同步，生成 ${count} 条消息`, id)
    return count
  }

  /** T+1 对账：把 PENDING 且 localSent+remoteAck 的标记为 MATCHED；模拟长短款 */
  function runReconcile(connectorId?: string): ReconcileBatch {
    if (!auth.can('integration:reconcile')) throw new Error('无对账权限')
    const targetConnectors = connectorId
      ? connectors.value.filter((c) => c.id === connectorId)
      : connectors.value.filter((c) => c.status === 'CONNECTED')

    let total = 0, matched = 0, pending = 0, long = 0, short = 0, failed = 0
    let totalAmt = 0, diffAmt = 0

    for (const c of targetConnectors) {
      for (const o of outbox.value) {
        if (o.connectorId !== c.id || o.reconciled) continue
        total++
        totalAmt += o.amount ?? 0
        if (o.status === 'FAILED') { failed++; continue }
        if (!o.localSent || !o.remoteAck) { pending++; continue }
        // 模拟 5% 长款、5% 短款
        const r = Math.random()
        if (r < 0.05) {
          o.status = 'LONG'
          long++
          diffAmt += 100 // 固定长款 100
        } else if (r < 0.10) {
          o.status = 'SHORT'
          short++
          diffAmt -= 6  // 手续费差异
        } else {
          o.status = 'MATCHED'
          matched++
        }
        o.reconciled = true
        o.reconciledAt = new Date().toISOString()
      }
    }

    const batch: ReconcileBatch = {
      id: nextId('rec'),
      connectorId: connectorId || 'ALL',
      connectorName: connectorId ? (getConnector(connectorId)?.name ?? '未知') : '全部连接器',
      date: new Date().toISOString().slice(0, 10),
      totalCount: total,
      matchedCount: matched,
      pendingCount: pending,
      longCount: long,
      shortCount: short,
      failedCount: failed,
      totalAmount: Math.round(totalAmt * 100) / 100,
      diffAmount: Math.round(diffAmt * 100) / 100,
      status: 'DONE',
      startedAt: new Date().toISOString(),
      finishedAt: new Date().toISOString(),
    }
    batches.value.unshift(batch)
    activity.log(auth.user.name, `T+1 对账完成：${batch.connectorName}，轧平 ${matched} 笔，长款 ${long}，短款 ${short}，失败 ${failed}`)
    return batch
  }

  /** 重发失败消息（幂等：用 transaction_id 去重） */
  function retryMessage(outboxId: string) {
    if (!auth.can('integration:sync')) throw new Error('无同步权限')
    const o = outbox.value.find((x) => x.outboxId === outboxId)
    if (!o || o.status !== 'FAILED') return
    o.status = 'PENDING'
    o.remoteAck = true
    o.reconciled = false
    activity.log(auth.user.name, `重发消息 ${o.txnNo}（幂等 transaction_id）`, o.outboxId)
  }

  // ---- 种子 ----
  function seed() {
    if (loaded.value) return
    loaded.value = true
    const now = Date.now()
    const hoursAgo = (h: number) => new Date(now - h * 3600_000).toISOString()

    const seedConnectors: Array<Partial<Connector> & Pick<Connector, 'type' | 'name' | 'endpoint' | 'credentialKey'>> = [
      { type: 'PAYMENT', name: '微信支付', endpoint: 'https://api.mch.weixin.qq.com/v3', credentialKey: 'wx_mch_****a3f2', status: 'CONNECTED', callCount24h: 1286, errorCount24h: 3 },
      { type: 'PAYMENT', name: '支付宝', endpoint: 'https://openapi.alipay.com/gateway', credentialKey: 'ali_pid_****b8c1', status: 'CONNECTED', callCount24h: 842, errorCount24h: 1 },
      { type: 'PAYMENT', name: '银联刷卡', endpoint: 'https://api.chinaums.com', credentialKey: 'ums_mer_****d4e5', status: 'ERROR', lastError: '证书过期，请更新商户证书', callCount24h: 156, errorCount24h: 12 },
      { type: 'INSURANCE', name: '上海医保接口', endpoint: 'https://ybj.sh.gov.cn/api', credentialKey: 'sh_yb_****f6a7', status: 'CONNECTED', callCount24h: 320, errorCount24h: 0 },
      { type: 'WECOM', name: '企业微信', endpoint: 'https://qyapi.weixin.qq.com/cgi-bin', credentialKey: 'ww_corp_****g9b3', status: 'CONNECTED', callCount24h: 2150, errorCount24h: 5 },
      { type: 'TAX', name: '百望税控', endpoint: 'https://api.baiwang.com/invoice', credentialKey: 'bw_tax_****h2c4', status: 'CONNECTED', callCount24h: 186, errorCount24h: 2 },
      { type: 'ADS', name: '巨量引擎', endpoint: 'https://ad.oceanengine.com/open_api', credentialKey: 'ocean_****j5d6', status: 'DISCONNECTED', callCount24h: 0, errorCount24h: 0 },
      { type: 'KINGDEE', name: '金蝶云星空 ERP', endpoint: 'https://api.kingdee.com/koas', credentialKey: 'kd_app_****k7e8', status: 'CONNECTED', callCount24h: 45, errorCount24h: 0 },
      { type: 'YONYOU', name: '用友 U8 ERP', endpoint: 'https://api.yonyoucloud.com/u8', credentialKey: 'yy_app_****l9f0', status: 'CONNECTED', callCount24h: 38, errorCount24h: 1 },
    ]

    seedConnectors.forEach((c, i) => {
      const id = nextId('conn')
      connectors.value.push({
        id,
        type: c.type!,
        name: c.name!,
        endpoint: c.endpoint!,
        credentialKey: c.credentialKey!,
        syncMode: 'UNIDIRECTIONAL',
        status: c.status ?? 'DISCONNECTED',
        lastSyncAt: c.status === 'CONNECTED' ? hoursAgo(i + 1) : null,
        lastError: c.lastError ?? null,
        callCount24h: c.callCount24h ?? 0,
        errorCount24h: c.errorCount24h ?? 0,
        createdAt: hoursAgo(24 * 30 - i * 24),
      })
    })

    // 种子 Outbox（金蝶/用友/支付 单向镜像）
    const kd = connectors.value.find((c) => c.type === 'KINGDEE')!
    const yy = connectors.value.find((c) => c.type === 'YONYOU')!
    const wx = connectors.value.find((c) => c.name === '微信支付')!
    const ali = connectors.value.find((c) => c.name === '支付宝')!

    const seedOutbox: Array<Partial<OutboxMessage> & Pick<OutboxMessage, 'connectorId' | 'connectorName' | 'bizType' | 'txnNo' | 'status' | 'occurredAt'>> = [
      { connectorId: kd.id, connectorName: kd.name, bizType: 'VOUCHER', txnNo: 'KD-V-20260825-001', amount: 12800, status: 'MATCHED', localSent: true, remoteAck: true, reconciled: true, reconciledAt: hoursAgo(20), occurredAt: hoursAgo(21) },
      { connectorId: kd.id, connectorName: kd.name, bizType: 'VOUCHER', txnNo: 'KD-V-20260825-002', amount: 6800, status: 'MATCHED', localSent: true, remoteAck: true, reconciled: true, reconciledAt: hoursAgo(19), occurredAt: hoursAgo(20) },
      { connectorId: kd.id, connectorName: kd.name, bizType: 'VOUCHER', txnNo: 'KD-V-20260825-003', amount: 3600, status: 'PENDING', localSent: true, remoteAck: false, reconciled: false, occurredAt: hoursAgo(18) },
      { connectorId: yy.id, connectorName: yy.name, bizType: 'VOUCHER', txnNo: 'YY-V-20260825-001', amount: 29800, status: 'MATCHED', localSent: true, remoteAck: true, reconciled: true, reconciledAt: hoursAgo(18), occurredAt: hoursAgo(19) },
      { connectorId: yy.id, connectorName: yy.name, bizType: 'VOUCHER', txnNo: 'YY-V-20260825-002', amount: 5900, status: 'SHORT', localSent: true, remoteAck: true, reconciled: true, reconciledAt: hoursAgo(17), occurredAt: hoursAgo(18) },
      { connectorId: wx.id, connectorName: wx.name, bizType: 'ORDER_PAY', txnNo: 'TX20260825001', amount: 12800, status: 'MATCHED', localSent: true, remoteAck: true, reconciled: true, reconciledAt: hoursAgo(16), occurredAt: hoursAgo(17) },
      { connectorId: wx.id, connectorName: wx.name, bizType: 'ORDER_PAY', txnNo: 'TX20260825002', amount: 6800, status: 'MATCHED', localSent: true, remoteAck: true, reconciled: true, reconciledAt: hoursAgo(15), occurredAt: hoursAgo(16) },
      { connectorId: wx.id, connectorName: wx.name, bizType: 'REFUND', txnNo: 'TX20260825003', amount: 2800, status: 'PENDING', localSent: true, remoteAck: false, reconciled: false, occurredAt: hoursAgo(14) },
      { connectorId: wx.id, connectorName: wx.name, bizType: 'ORDER_PAY', txnNo: 'TX20260825004', amount: 5400, status: 'LONG', localSent: true, remoteAck: true, reconciled: true, reconciledAt: hoursAgo(14), occurredAt: hoursAgo(15) },
      { connectorId: ali.id, connectorName: ali.name, bizType: 'ORDER_PAY', txnNo: 'TX20260825005', amount: 3000, status: 'SHORT', localSent: true, remoteAck: true, reconciled: true, reconciledAt: hoursAgo(13), occurredAt: hoursAgo(14) },
      { connectorId: ali.id, connectorName: ali.name, bizType: 'ORDER_PAY', txnNo: 'TX20260825006', amount: 8900, status: 'FAILED', localSent: true, remoteAck: false, reconciled: false, occurredAt: hoursAgo(12) },
    ]
    seedOutbox.forEach((o) => {
      outbox.value.push({
        outboxId: nextId('ob'),
        connectorId: o.connectorId!,
        connectorName: o.connectorName!,
        bizType: o.bizType!,
        txnNo: o.txnNo!,
        amount: o.amount,
        localSent: o.localSent ?? true,
        remoteAck: o.remoteAck ?? false,
        reconciled: o.reconciled ?? false,
        status: o.status!,
        occurredAt: o.occurredAt!,
        reconciledAt: o.reconciledAt,
      })
    })

    // 种子调用日志（最近 20 条）
    const logTemplates = [
      { conn: wx, method: 'POST', endpoint: '/v3/pay/transactions/jsapi', code: 200, latency: 120, status: 'ACK' as const },
      { conn: wx, method: 'POST', endpoint: '/v3/refund/domestic/refunds', code: 202, latency: 280, status: 'SENT' as const },
      { conn: ali, method: 'POST', endpoint: '/gateway.do?service=alipay.trade.page.pay', code: 200, latency: 95, status: 'ACK' as const },
      { conn: kd, method: 'POST', endpoint: '/koas/voucher/save', code: 200, latency: 340, status: 'ACK' as const },
      { conn: yy, method: 'POST', endpoint: '/u8/voucher/import', code: 200, latency: 420, status: 'ACK' as const },
      { conn: connectors.value[2], method: 'POST', endpoint: '/api/pay', code: 504, latency: 5000, status: 'FAIL' as const, error: '证书过期' },
    ]
    for (let i = 0; i < 20; i++) {
      const t = logTemplates[i % logTemplates.length]
      callLogs.value.push({
        id: nextId('log'),
        connectorId: t.conn.id,
        connectorName: t.conn.name,
        transactionId: `TX20260825${String(1000 + i).padStart(4, '0')}`,
        direction: 'OUT',
        method: t.method,
        endpoint: t.endpoint,
        statusCode: t.code,
        latencyMs: t.latency + Math.floor(Math.random() * 100),
        status: t.status,
        requestAt: hoursAgo(i * 0.5),
        errorMsg: t.error,
      })
    }

    // 种子对账批次
    batches.value = [
      {
        id: nextId('rec'), connectorId: 'ALL', connectorName: '全部连接器',
        date: '2026-08-24', totalCount: 48, matchedCount: 45, pendingCount: 1, longCount: 1, shortCount: 1, failedCount: 0,
        totalAmount: 286400, diffAmount: 94, status: 'DONE',
        startedAt: hoursAgo(24), finishedAt: hoursAgo(23.8),
      },
      {
        id: nextId('rec'), connectorId: 'ALL', connectorName: '全部连接器',
        date: '2026-08-23', totalCount: 52, matchedCount: 52, pendingCount: 0, longCount: 0, shortCount: 0, failedCount: 0,
        totalAmount: 312800, diffAmount: 0, status: 'DONE',
        startedAt: hoursAgo(48), finishedAt: hoursAgo(47.8),
      },
    ]
  }

  return {
    connectors, callLogs, outbox, batches,
    CONNECTOR_TYPE_LABEL, CONNECTOR_STATUS_LABEL,
    connectedCount, errorCount, pendingOutbox, totalCalls24h, totalErrors24h, errorRate,
    outboxMatched, outboxLong, outboxShort, recentLogs,
    getConnector, canEdit,
    createConnector, updateConnector, testConnection, triggerSync,
    runReconcile, retryMessage, seed,
  }
})
