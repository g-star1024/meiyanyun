// ============================================================
// T2-01 数据采集 store
// 数据源（MySQL/PG/Kafka/API/LOG/三方）+ 同步任务
// 对齐 T-G-中台与通用.md T2-01 详设
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

// ---- 类型 ----
export type SourceType = 'MYSQL' | 'POSTGRES' | 'KAFKA' | 'API' | 'LOG' | 'THIRD_PARTY'
export type SourceStatus = 'CONNECTED' | 'SYNCING' | 'ERROR' | 'IDLE'
export type SyncMode = 'BATCH' | 'REALTIME'

export interface DataSource {
  id: string
  name: string
  type: SourceType
  host: string
  port?: number
  database?: string
  endpoint?: string
  username?: string
  status: SourceStatus
  syncMode: SyncMode
  lastSyncAt: string | null
  lastRows: number
  totalRows: number
  errorMsg?: string | null
  tables: string[]
  owner: string
  createdAt: string
}

export type SyncJobType = 'FULL' | 'INCREMENTAL'
export type SyncJobStatus = 'RUNNING' | 'SUCCESS' | 'FAILED'

export interface SyncJob {
  id: string
  sourceId: string
  sourceName: string
  type: SyncJobType
  status: SyncJobStatus
  rowsSynced: number
  startedAt: string
  finishedAt?: string | null
  errorMsg?: string | null
}

export const SOURCE_TYPE_LABEL: Record<SourceType, string> = {
  MYSQL: 'MySQL',
  POSTGRES: 'PostgreSQL',
  KAFKA: 'Kafka',
  API: 'REST API',
  LOG: '日志采集',
  THIRD_PARTY: '三方平台',
}

export const SOURCE_STATUS_LABEL: Record<SourceStatus, string> = {
  CONNECTED: '已连接',
  SYNCING: '同步中',
  ERROR: '异常',
  IDLE: '空闲',
}

export const SYNC_MODE_LABEL: Record<SyncMode, string> = {
  BATCH: '批量',
  REALTIME: '实时',
}

export const useT2DataCollectStore = defineStore('t2DataCollect', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const sources = ref<DataSource[]>([])
  const jobs = ref<SyncJob[]>([])
  const loaded = ref(false)

  // ---- 查询 ----
  const connectedCount = computed(() => sources.value.filter((s) => s.status === 'CONNECTED' || s.status === 'SYNCING').length)
  const errorSources = computed(() => sources.value.filter((s) => s.status === 'ERROR').length)
  const totalRows = computed(() => sources.value.reduce((s, x) => s + x.totalRows, 0))
  const todayRows = computed(() => {
    const today = new Date().toISOString().slice(0, 10)
    return jobs.value
      .filter((j) => j.startedAt.slice(0, 10) === today)
      .reduce((s, j) => s + j.rowsSynced, 0)
  })

  function get(id: string) {
    return sources.value.find((s) => s.id === id)
  }

  function canCreate() { return auth.can('collect:create') }
  function canEdit() { return auth.can('collect:edit') }
  function canSync() { return auth.can('collect:sync') }

  // ---- 命令 ----
  function createSource(input: {
    name: string; type: SourceType; host: string; port?: number
    database?: string; endpoint?: string; username?: string; password?: string
    syncMode: SyncMode; tables?: string[]
  }): DataSource {
    if (!canCreate()) throw new Error('无数据源创建权限')
    const now = new Date().toISOString()
    const s: DataSource = {
      id: nextId('src'),
      name: input.name,
      type: input.type,
      host: input.host,
      port: input.port,
      database: input.database,
      endpoint: input.endpoint,
      username: input.username ? maskName(input.username) : undefined,
      status: 'IDLE',
      syncMode: input.syncMode,
      lastSyncAt: null,
      lastRows: 0,
      totalRows: 0,
      errorMsg: null,
      tables: input.tables ?? [],
      owner: auth.user.name,
      createdAt: now,
    }
    sources.value.unshift(s)
    activity.log(auth.user.name, `创建数据源「${s.name}」（${SOURCE_TYPE_LABEL[s.type]}）`, s.id)
    return s
  }

  function updateSource(id: string, patch: Partial<Pick<DataSource, 'name' | 'host' | 'port' | 'database' | 'endpoint' | 'syncMode' | 'tables'>>) {
    if (!canEdit()) throw new Error('无数据源编辑权限')
    const s = get(id)
    if (!s) return
    Object.assign(s, patch)
    activity.log(auth.user.name, `编辑数据源「${s.name}」`, id)
  }

  /** 测试连接：模拟 80% 成功率 */
  function testConnection(id: string): boolean {
    if (!canEdit()) throw new Error('无数据源编辑权限')
    const s = get(id)
    if (!s) return false
    s.status = 'SYNCING'
    const ok = Math.random() > 0.2
    setTimeout(() => {
      s.status = ok ? 'CONNECTED' : 'ERROR'
      s.errorMsg = ok ? null : '连接超时或凭据无效（模拟）'
    }, 500)
    activity.log(auth.user.name, `测试数据源「${s.name}」连接：${ok ? '成功' : '失败'}`, id)
    return ok
  }

  /** 触发同步：写入一条 RUNNING job，模拟成功后回填 */
  function triggerSync(id: string, type: SyncJobType = 'INCREMENTAL'): SyncJob | null {
    if (!canSync()) throw new Error('无同步权限')
    const s = get(id)
    if (!s) return null
    s.status = 'SYNCING'
    const job: SyncJob = {
      id: nextId('job'),
      sourceId: s.id,
      sourceName: s.name,
      type,
      status: 'RUNNING',
      rowsSynced: 0,
      startedAt: new Date().toISOString(),
      finishedAt: null,
    }
    jobs.value.unshift(job)
    // 模拟同步过程
    setTimeout(() => {
      const ok = Math.random() > 0.1
      const rows = ok ? 500 + Math.floor(Math.random() * 5000) : 0
      job.status = ok ? 'SUCCESS' : 'FAILED'
      job.rowsSynced = rows
      job.finishedAt = new Date().toISOString()
      if (ok) {
        s.status = 'CONNECTED'
        s.lastSyncAt = job.finishedAt
        s.lastRows = rows
        s.totalRows += rows
        s.errorMsg = null
      } else {
        s.status = 'ERROR'
        s.errorMsg = '同步失败：表结构变更或字段类型不匹配（模拟）'
        job.errorMsg = s.errorMsg
      }
    }, 800)
    activity.log(auth.user.name, `触发数据源「${s.name}」${type === 'FULL' ? '全量' : '增量'}同步`, id)
    return job
  }

  function maskName(u: string) {
    if (u.length <= 2) return u[0] + '***'
    return u[0] + '***' + u[u.length - 1]
  }

  // ---- 种子 ----
  function seed() {
    if (loaded.value) return
    loaded.value = true
    const now = Date.now()
    const hoursAgo = (h: number) => new Date(now - h * 3600_000).toISOString()
    const daysAgo = (d: number) => new Date(now - d * 86400_000).toISOString()

    type Seed = Partial<DataSource> & Pick<DataSource, 'name' | 'type' | 'host' | 'syncMode' | 'status'>
    const seedSources: Array<Seed & { owner?: string }> = [
      { name: '门店交易主库', type: 'MYSQL', host: 'mysql.master.meiyun.internal', port: 3306, database: 'meiyun_core', username: 'ro_reader', syncMode: 'REALTIME', status: 'CONNECTED', tables: ['orders', 'order_items', 'customers', 'appointments', 'refunds'], totalRows: 1_284_360, lastRows: 4820, owner: '张数' },
      { name: '客户档案库', type: 'POSTGRES', host: 'pg.customer.meiyun.internal', port: 5432, database: 'customer_360', username: 'etl_user', syncMode: 'BATCH', status: 'CONNECTED', tables: ['customers', 'customer_tags', 'customer_profiles', 'consent_logs'], totalRows: 862_140, lastRows: 1240, owner: '张数' },
      { name: '用户行为埋点', type: 'KAFKA', host: 'kafka.cluster.meiyun.internal:9092', endpoint: 'topic=user_events', syncMode: 'REALTIME', status: 'CONNECTED', tables: ['page_view', 'click', 'consult_start', 'consult_end'], totalRows: 24_680_000, lastRows: 128_400, owner: '李析' },
      { name: '企微 SCRM 接口', type: 'API', host: 'https://qyapi.weixin.qq.com', endpoint: '/cgi-bin/externalcontact/*', syncMode: 'REALTIME', status: 'CONNECTED', tables: ['external_contact', 'group_chat', 'msg_audit'], totalRows: 128_640, lastRows: 3200, owner: '李析' },
      { name: '应用日志采集', type: 'LOG', host: 'logstash.meiyun.internal:5044', syncMode: 'REALTIME', status: 'SYNCING', tables: ['frontend_access', 'api_gateway', 'error_log'], totalRows: 98_420_000, lastRows: 0, owner: '王治' },
      { name: '美团点评团购', type: 'THIRD_PARTY', host: 'https://openapi.meituan.com', endpoint: '/api/v1/order/*', syncMode: 'BATCH', status: 'ERROR', errorMsg: 'Token 已过期，请重新授权', tables: ['deal_orders', 'verify_logs'], totalRows: 42_800, lastRows: 0, owner: '王治' },
      { name: '新氧平台接口', type: 'THIRD_PARTY', host: 'https://open.soyoung.com', endpoint: '/v2/order/list', syncMode: 'BATCH', status: 'IDLE', tables: ['orders', 'customers'], totalRows: 12_400, lastRows: 0, owner: '张数' },
      { name: '财务结算库', type: 'MYSQL', host: 'mysql.finance.meiyun.internal', port: 3306, database: 'finance_settle', username: 'bi_reader', syncMode: 'BATCH', status: 'CONNECTED', tables: ['settlement', 'invoice', 'commission'], totalRows: 386_200, lastRows: 680, owner: '王治' },
    ]

    seedSources.forEach((s, i) => {
      const id = nextId('src')
      sources.value.push({
        id,
        name: s.name!,
        type: s.type!,
        host: s.host!,
        port: s.port,
        database: s.database,
        endpoint: s.endpoint,
        username: s.username,
        status: s.status!,
        syncMode: s.syncMode!,
        lastSyncAt: s.status === 'CONNECTED' ? hoursAgo(i + 1) : (s.status === 'SYNCING' ? new Date().toISOString() : null),
        lastRows: s.lastRows ?? 0,
        totalRows: s.totalRows ?? 0,
        errorMsg: s.errorMsg ?? null,
        tables: s.tables ?? [],
        owner: s.owner ?? '系统',
        createdAt: daysAgo(60 - i * 3),
      })
    })

    // 种子同步任务（最近 20 条）
    const recent = sources.value.slice(0, 6)
    for (let i = 0; i < 18; i++) {
      const s = recent[i % recent.length]
      const ok = Math.random() > 0.15
      const type: SyncJobType = i % 4 === 0 ? 'FULL' : 'INCREMENTAL'
      const started = hoursAgo(i * 1.2)
      jobs.value.push({
        id: nextId('job'),
        sourceId: s.id,
        sourceName: s.name,
        type,
        status: ok ? 'SUCCESS' : 'FAILED',
        rowsSynced: ok ? 200 + Math.floor(Math.random() * 5000) : 0,
        startedAt: started,
        finishedAt: ok ? new Date(new Date(started).getTime() + 60_000 * (1 + Math.random() * 4)).toISOString() : null,
        errorMsg: ok ? null : '字段类型不匹配',
      })
    }
  }

  return {
    sources, jobs,
    connectedCount, totalRows, todayRows, errorSources,
    SOURCE_TYPE_LABEL, SOURCE_STATUS_LABEL, SYNC_MODE_LABEL,
    get, canCreate, canEdit, canSync,
    createSource, updateSource, testConnection, triggerSync, seed,
  }
})
