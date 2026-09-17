<script setup lang="ts">
/* ============================================================
 * T3 集成中心 /integrations
 * 红线：单向镜像 + Outbox + T+1 对账，绝不触碰资金池。
 * 4 KPI + 红线提示 + 5 tab：接入配置（真实）/ 连接器 / Outbox / 对账批次 / 调用日志（后 4 个为 T3-04 mock 演示）。
 * ============================================================ */
import { computed, onMounted, ref, watch } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CSegmented from '@/components/CSegmented.vue'
import CDrawer from '@/components/CDrawer.vue'
import { useT3IntegrationStore } from '@/stores/t3Integration'
import { useAuthStore } from '@/stores/auth'
import type { Connector, ConnectorType, OutboxMessage, ReconcileBatch } from '@/stores/t3Integration'
import { listIntegrations, upsertIntegration, testIntegration, type IntegrationDTO } from '@/api/integration'

const store = useT3IntegrationStore()
const auth = useAuthStore()
onMounted(() => {
  store.seed()
  loadIntegrations()
})

type Tab = 'access' | 'connectors' | 'outbox' | 'batches' | 'logs'
const tab = ref<Tab>('access')
const tabOptions = [
  { value: 'access', label: '接入配置' },
  { value: 'connectors', label: '连接器' },
  { value: 'outbox', label: 'Outbox 消息' },
  { value: 'batches', label: '对账批次' },
  { value: 'logs', label: '调用日志' },
]

const kpis = computed(() => [
  { label: '连接器总数', icon: 'settings', value: String(store.connectors.length), tone: 'text' as const, sub: '' },
  { label: '已连接', icon: 'settings', value: String(store.connectedCount), tone: 'success' as const, sub: '' },
  { label: '异常', icon: 'alert', value: String(store.errorCount), tone: 'danger' as const, sub: '' },
  {
    label: '24h 调用量', icon: 'settings',
    value: String(store.totalCalls24h),
    tone: 'brand' as const,
    sub: `错误率 ${store.errorRate}%`,
  },
])

// ---------- flash ----------
const flash = ref<{ type: 'ok' | 'warn' | 'err'; text: string } | null>(null)
function setFlash(type: 'ok' | 'warn' | 'err', text: string) {
  flash.value = { type, text }
  window.setTimeout(() => (flash.value = null), 4000)
}

// ---------- 连接器 ----------
type EditState = {
  mode: 'create' | 'edit'
  id?: string
  name: string
  type: ConnectorType
  endpoint: string
  credentialKey: string
}
const editOpen = ref(false)
const editing = ref<EditState | null>(null)

const typeOptions = [
  { value: 'PAYMENT', label: '支付渠道' },
  { value: 'INSURANCE', label: '医保接口' },
  { value: 'WECOM', label: '企业微信' },
  { value: 'TAX', label: '税控/发票' },
  { value: 'ADS', label: '广告投放' },
  { value: 'KINGDEE', label: '金蝶 ERP' },
  { value: 'YONYOU', label: '用友 ERP' },
]

const CONNECTOR_STATUS_PILL: Record<Connector['status'], { text: string; status: 'success' | 'disabled' | 'danger' | 'warning' }> = {
  CONNECTED: { text: '已连接', status: 'success' },
  DISCONNECTED: { text: '未连接', status: 'disabled' },
  ERROR: { text: '异常', status: 'danger' },
  SYNCING: { text: '同步中', status: 'warning' },
}

function fmtDateTime(iso?: string | null) {
  if (!iso) return '—'
  const d = new Date(iso)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getMonth() + 1}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function openCreate() {
  editing.value = { mode: 'create', name: '', type: 'PAYMENT', endpoint: '', credentialKey: '' }
  editOpen.value = true
}
function openEdit(c: Connector) {
  editing.value = {
    mode: 'edit', id: c.id, name: c.name, type: c.type,
    endpoint: c.endpoint, credentialKey: c.credentialKey,
  }
  editOpen.value = true
}
function saveConnector() {
  if (!editing.value) return
  const e = editing.value
  if (!e.name.trim() || !e.endpoint.trim()) return
  if (e.mode === 'create') {
    store.createConnector({
      name: e.name.trim(), type: e.type, endpoint: e.endpoint.trim(),
      credentialKey: e.credentialKey.trim() || '****', syncMode: 'UNIDIRECTIONAL',
    })
    setFlash('ok', '连接器已创建')
  } else if (e.id) {
    store.updateConnector(e.id, {
      name: e.name.trim(), endpoint: e.endpoint.trim(),
      credentialKey: e.credentialKey.trim(),
    })
    setFlash('ok', '连接器配置已更新')
  }
  editOpen.value = false
  editing.value = null
}

function doTest(c: Connector) {
  store.testConnection(c.id)
  setFlash('ok', `正在测试「${c.name}」连接…`)
}
function doSync(c: Connector) {
  const n = store.triggerSync(c.id)
  setFlash('ok', `已触发单向镜像同步，生成 ${n} 条 Outbox 消息`)
}
function doReconcileAll() {
  const b = store.runReconcile()
  setFlash('ok', `T+1 对账完成：轧平 ${b.matchedCount} 笔，长款 ${b.longCount}，短款 ${b.shortCount}，失败 ${b.failedCount}`)
}

// ---------- Outbox ----------
const OUTBOX_PILL: Record<OutboxMessage['status'], { text: string; status: 'success' | 'warning' | 'info' | 'danger' }> = {
  MATCHED: { text: '已匹配', status: 'success' },
  PENDING: { text: '待确认', status: 'warning' },
  LONG:    { text: '长款', status: 'info' },
  SHORT:   { text: '短款', status: 'danger' },
  FAILED:  { text: '失败', status: 'danger' },
}
const BIZ_LABEL: Record<OutboxMessage['bizType'], string> = {
  ORDER_PAY: '订单支付',
  REFUND: '退款',
  INVOICE: '发票',
  VOUCHER: '凭证',
  CONTACT: '通讯录',
  AD_CLICK: '广告点击',
}
function retry(o: OutboxMessage) {
  store.retryMessage(o.outboxId)
  setFlash('ok', `已重发消息 ${o.txnNo}（幂等 transaction_id）`)
}
const outboxList = computed(() =>
  [...store.outbox].sort((a, b) => b.occurredAt.localeCompare(a.occurredAt)),
)

// ---------- 对账批次 ----------
const batchStatusPill = (s: ReconcileBatch['status']) =>
  s === 'DONE' ? { text: '完成', status: 'success' as const }
  : s === 'RUNNING' ? { text: '运行中', status: 'warning' as const }
  : { text: '失败', status: 'danger' as const }

function fmtAmt(n?: number) {
  if (n == null) return '—'
  return `¥${n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

// ---------- 调用日志 ----------
const logList = computed(() => store.recentLogs)

// ---------- 接入配置（真实 API：org-service external_integration，P5-B57 卡2） ----------
const CATEGORY_LABEL: Record<string, string> = {
  NOTIFY: '消息通知',
  AD: '广告回传',
}
const integrations = ref<IntegrationDTO[]>([])
const intLoading = ref(false)
const intLoadErr = ref('')
const canEditInt = computed(() => auth.can('integration:edit'))

async function loadIntegrations() {
  intLoading.value = true
  intLoadErr.value = ''
  try {
    const { data } = await listIntegrations()
    integrations.value = data
  } catch (e) {
    intLoadErr.value = intErrMsg(e, '接入配置加载失败')
  } finally {
    intLoading.value = false
  }
}
function intErrMsg(e: unknown, fallback: string): string {
  const anyE = e as { response?: { data?: { message?: string } }; message?: string }
  return anyE?.response?.data?.message || anyE?.message || fallback
}
function statusPillOf(i: IntegrationDTO): { text: string; status: 'success' | 'disabled' | 'warning' } {
  if (i.code === 'AD_DEV_NO_AUTH') {
    return i.enabled && i.boolValue === true
      ? { text: '免签开启', status: 'warning' }
      : { text: '验签中', status: 'success' }
  }
  if (!i.enabled) return { text: '未启用', status: 'disabled' }
  if (i.valueKind === 'URL') return i.baseUrl ? { text: '已启用', status: 'success' } : { text: '未配置', status: 'disabled' }
  if (i.valueKind === 'SECRET') return i.hasSecret ? { text: '已启用', status: 'success' } : { text: '未配置', status: 'disabled' }
  return { text: '已启用', status: 'success' }
}
function valueSummary(i: IntegrationDTO): string {
  if (i.valueKind === 'URL') return i.baseUrl || '—'
  if (i.valueKind === 'SECRET') return i.hasSecret ? `已保存密钥 ${i.secretMask ?? '****'}` : '未配置密钥'
  return i.enabled && i.boolValue === true ? '免签（仅联调）' : 'HMAC 验签'
}

interface IntForm {
  baseUrl: string
  secret: string
  boolValue: boolean
  enabled: boolean
  insecureHttpConfirmed: boolean
}
const intEditOpen = ref(false)
const intEditing = ref<IntegrationDTO | null>(null)
const intForm = ref<IntForm>({ baseUrl: '', secret: '', boolValue: false, enabled: false, insecureHttpConfirmed: false })
const intSaving = ref(false)
const intTesting = ref<string>('')
const intTestInline = ref<Record<string, { ok: boolean; message: string; at: string }>>({})

function openIntEdit(i: IntegrationDTO) {
  intEditing.value = i
  intForm.value = {
    baseUrl: i.baseUrl ?? '',
    secret: '',
    boolValue: i.boolValue === true,
    enabled: i.enabled,
    insecureHttpConfirmed: false,
  }
  intEditOpen.value = true
}
async function saveInt() {
  const i = intEditing.value
  if (!i) return
  const f = intForm.value
  if (i.valueKind === 'URL' && f.enabled && !f.baseUrl.trim()) {
    setFlash('err', '启用前请先填写网关 URL')
    return
  }
  if (i.valueKind === 'SECRET' && f.enabled && !i.hasSecret && !f.secret.trim()) {
    setFlash('err', '启用前请先填写渠道密钥')
    return
  }
  if (
    i.valueKind === 'URL'
    && f.baseUrl.trim()
    && f.baseUrl.trim().startsWith('http://')
    && !f.insecureHttpConfirmed
  ) {
    if (!window.confirm('当前为明文 HTTP 网关（仅限内网联调），确认保存？')) return
    f.insecureHttpConfirmed = true
  }
  intSaving.value = true
  try {
    await upsertIntegration(i.code, {
      baseUrl: i.valueKind === 'URL' ? (f.baseUrl.trim() || null) : null,
      secret: i.valueKind === 'SECRET' ? f.secret : null,
      boolValue: i.valueKind === 'SWITCH' ? f.boolValue : null,
      enabled: f.enabled,
      insecureHttpConfirmed: i.valueKind === 'URL' ? f.insecureHttpConfirmed : undefined,
    })
    setFlash('ok', `「${i.name}」配置已保存（约 60 秒内对各服务生效）`)
    intEditOpen.value = false
    intEditing.value = null
    await loadIntegrations()
  } catch (e) {
    setFlash('err', intErrMsg(e, '配置保存失败'))
  } finally {
    intSaving.value = false
  }
}
async function doIntTest(i: IntegrationDTO) {
  intTesting.value = i.code
  try {
    const { data } = await testIntegration(i.code)
    intTestInline.value[i.code] = { ok: data.ok, message: data.message, at: new Date().toISOString() }
    await loadIntegrations()
  } catch (e) {
    intTestInline.value[i.code] = { ok: false, message: intErrMsg(e, '测试失败'), at: new Date().toISOString() }
  } finally {
    intTesting.value = ''
  }
}

// tab 切换时清理 flash
watch(tab, () => (flash.value = null))
</script>

<template>
  <div class="t3int">
    <!-- 头部：纯 KPI -->
    <div class="t3int__head">
      <div v-for="k in kpis" :key="k.label" class="kpi-wrap">
        <CKpi :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
        <div v-if="k.sub" class="kpi-sub" :class="{ 'kpi-sub--err': k.label === '24h 调用量' && Number(store.errorRate) > 0 }">{{ k.sub }}</div>
      </div>
    </div>

    <!-- 红线提示条 -->
    <CCard class="t3int__redline" padding="md" :header-border="false">
      <div class="redline">
        <CIcon name="shield" :size="20" class="redline__icon" />
        <div class="redline__text">
          <strong>红线提示：</strong>集成中心采用单向镜像模式，仅同步凭证数据与业务单据，<em>绝不触碰资金池</em>。所有操作经 Outbox 幂等 <code>transaction_id</code> 保证一致性，每日 T+1 自动对账。
        </div>
      </div>
    </CCard>

    <!-- tab + 主操作 -->
    <div class="t3int__tabs">
      <CSegmented v-model="tab" :options="tabOptions" />
      <div class="t3int__tabs-right">
        <CButton
          v-if="tab === 'connectors'"
          variant="secondary" size="sm"
          :disabled="!auth.can('integration:reconcile')"
          @click="doReconcileAll"
        >
          <CIcon name="check-square" :size="16" />T+1 一键对账
        </CButton>
        <CButton
          v-if="tab === 'batches'"
          variant="primary" size="sm"
          :disabled="!auth.can('integration:reconcile')"
          @click="doReconcileAll"
        >
          <CIcon name="check-square" :size="16" />执行 T+1 对账
        </CButton>
        <CButton
          v-if="tab === 'connectors'"
          variant="primary" size="sm"
          :disabled="!auth.can('integration:create')"
          @click="openCreate"
        >
          <CIcon name="plus" :size="16" />新建连接器
        </CButton>
      </div>
    </div>

    <Transition name="flash">
      <div v-if="flash" class="flash" :class="`flash--${flash.type}`">
        <CIcon :name="flash.type === 'err' ? 'alert' : 'check'" :size="16" />
        {{ flash.text }}
      </div>
    </Transition>

    <!-- 接入配置 tab（真实配置：org-service external_integration 单点持钥，P5-B57 卡2） -->
    <CCard v-if="tab === 'access'" class="t3int__tablecard" padding="none">
      <template #header>
        <div class="access-head">
          <span>外部依赖接入配置</span>
          <CStatusPill status="success" dot>真实配置 · 保存后约 60 秒生效，无需重启</CStatusPill>
        </div>
      </template>
      <div v-if="intLoading" class="access-state">加载中…</div>
      <div v-else-if="intLoadErr" class="access-state access-state--err">
        <CIcon name="alert" :size="14" />接入配置加载失败：{{ intLoadErr }}
        <CButton variant="text" size="sm" @click="loadIntegrations">重试</CButton>
      </div>
      <div v-else class="tablewrap">
        <table class="grid">
          <thead>
            <tr>
              <th>分类</th>
              <th>接入点</th>
              <th>类型</th>
              <th>当前配置 / 影响</th>
              <th>状态</th>
              <th>最近测试</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="i in integrations" :key="i.code" :class="{ 'row-fail': i.code === 'AD_DEV_NO_AUTH' && i.enabled && i.boolValue === true }">
              <td>
                <CStatusPill status="info">{{ CATEGORY_LABEL[i.category] ?? i.category }}</CStatusPill>
              </td>
              <td>
                <div class="access-name">{{ i.name }}</div>
                <div class="mono access-code">{{ i.code }}</div>
              </td>
              <td class="text-weak">{{ i.valueKind === 'URL' ? '网关 URL' : i.valueKind === 'SECRET' ? '密钥' : '开关' }}</td>
              <td>
                <div class="mono access-val" :title="valueSummary(i)">{{ valueSummary(i) }}</div>
                <div v-if="i.remark" class="access-remark">{{ i.remark }}</div>
              </td>
              <td>
                <CStatusPill :status="statusPillOf(i).status" dot>{{ statusPillOf(i).text }}</CStatusPill>
              </td>
              <td>
                <div v-if="i.lastTestAt" class="access-test" :class="i.lastTestOk ? 'ic-ok' : 'ic-pending'">
                  {{ i.lastTestOk ? '成功' : '失败' }} · {{ fmtDateTime(i.lastTestAt) }}
                </div>
                <span v-else class="text-weak">—</span>
              </td>
              <td>
                <div class="access-ops">
                  <CButton
                    v-if="i.valueKind !== 'SWITCH'"
                    variant="ghost" size="sm"
                    :disabled="!canEditInt || intTesting === i.code"
                    @click="doIntTest(i)"
                  >
                    <CIcon name="loading" :size="14" />{{ intTesting === i.code ? '测试中' : '测试' }}
                  </CButton>
                  <CButton variant="text" size="sm" :disabled="!canEditInt" @click="openIntEdit(i)">
                    <CIcon name="edit" :size="14" />配置
                  </CButton>
                </div>
                <div
                  v-if="intTestInline[i.code]"
                  class="access-inline"
                  :class="intTestInline[i.code].ok ? 'access-inline--ok' : 'access-inline--err'"
                >
                  <CIcon :name="intTestInline[i.code].ok ? 'check' : 'alert'" :size="13" />
                  {{ intTestInline[i.code].message }}
                </div>
              </td>
            </tr>
            <tr v-if="!integrations.length">
              <td colspan="7" class="cell-empty">暂无接入配置（目录由系统迁移播种）</td>
            </tr>
          </tbody>
        </table>
      </div>
    </CCard>

    <!-- 连接器 tab（T3-04 mock 演示） -->
    <CCard v-else-if="tab === 'connectors'" class="t3int__conncard" padding="lg">
      <div class="conn-grid">
        <div
          v-for="c in store.connectors"
          :key="c.id"
          class="conn-card"
          :class="{ 'conn-card--err': c.status === 'ERROR', 'conn-card--syncing': c.status === 'SYNCING' }"
        >
          <div class="conn-card__head">
            <div class="conn-card__title">
              <CIcon name="box" :size="16" class="conn-card__type-icon" />
              <span>{{ c.name }}</span>
            </div>
            <CStatusPill :status="CONNECTOR_STATUS_PILL[c.status].status" dot>
              {{ CONNECTOR_STATUS_PILL[c.status].text }}
            </CStatusPill>
          </div>
          <div class="conn-card__type">
            <CStatusPill status="info">{{ store.CONNECTOR_TYPE_LABEL[c.type] }}</CStatusPill>
          </div>
          <div class="conn-card__row">
            <span class="kv-k">Endpoint</span>
            <span class="kv-v mono" :title="c.endpoint">{{ c.endpoint }}</span>
          </div>
          <div class="conn-card__row">
            <span class="kv-k">凭证</span>
            <span class="kv-v mono">{{ c.credentialKey }}</span>
          </div>
          <div class="conn-card__row">
            <span class="kv-k">同步模式</span>
            <span class="kv-v">
              <CStatusPill status="default">单向镜像</CStatusPill>
            </span>
          </div>
          <div class="conn-card__row">
            <span class="kv-k">最后同步</span>
            <span class="kv-v">{{ fmtDateTime(c.lastSyncAt) }}</span>
          </div>
          <div class="conn-card__stats">
            <div>
              <div class="stat-num">{{ c.callCount24h }}</div>
              <div class="stat-label">24h 调用</div>
            </div>
            <div>
              <div class="stat-num" :class="{ 'stat-num--err': c.errorCount24h > 0 }">{{ c.errorCount24h }}</div>
              <div class="stat-label">错误数</div>
            </div>
          </div>
          <div v-if="c.lastError" class="conn-card__err">
            <CIcon name="alert" :size="14" />{{ c.lastError }}
          </div>
          <div class="conn-card__ops">
            <CButton variant="ghost" size="sm" :disabled="!store.canEdit()" @click="doTest(c)">
              <CIcon name="loading" :size="14" />测试
            </CButton>
            <CButton variant="secondary" size="sm" :disabled="!auth.can('integration:sync')" @click="doSync(c)">
              <CIcon name="upload" :size="14" />同步
            </CButton>
            <CButton variant="text" size="sm" :disabled="!store.canEdit()" @click="openEdit(c)">
              <CIcon name="edit" :size="14" />编辑
            </CButton>
          </div>
        </div>
      </div>
    </CCard>

    <!-- Outbox tab -->
    <CCard v-else-if="tab === 'outbox'" class="t3int__tablecard" padding="none">
      <div class="tablewrap">
        <table class="grid">
          <thead>
            <tr>
              <th>Outbox ID</th>
              <th>连接器</th>
              <th>业务类型</th>
              <th>交易号</th>
              <th>金额</th>
              <th>本地发送</th>
              <th>三方确认</th>
              <th>对账</th>
              <th>状态</th>
              <th>时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="o in outboxList" :key="o.outboxId" :class="{ 'row-fail': o.status === 'FAILED' }">
              <td class="mono">{{ o.outboxId }}</td>
              <td>{{ o.connectorName }}</td>
              <td>{{ BIZ_LABEL[o.bizType] }}</td>
              <td class="mono">{{ o.txnNo }}</td>
              <td class="num">{{ o.amount != null ? fmtAmt(o.amount) : '—' }}</td>
              <td>
                <CIcon :name="o.localSent ? 'check' : 'clock'" :size="14" :class="o.localSent ? 'ic-ok' : 'ic-pending'" />
              </td>
              <td>
                <CIcon :name="o.remoteAck ? 'check' : 'clock'" :size="14" :class="o.remoteAck ? 'ic-ok' : 'ic-pending'" />
              </td>
              <td>
                <CIcon :name="o.reconciled ? 'check' : 'clock'" :size="14" :class="o.reconciled ? 'ic-ok' : 'ic-pending'" />
              </td>
              <td>
                <CStatusPill :status="OUTBOX_PILL[o.status].status">{{ OUTBOX_PILL[o.status].text }}</CStatusPill>
              </td>
              <td class="mono">{{ fmtDateTime(o.occurredAt) }}</td>
              <td>
                <CButton
                  v-if="o.status === 'FAILED'"
                  variant="text" size="sm"
                  :disabled="!auth.can('integration:sync')"
                  @click="retry(o)"
                >
                  <CIcon name="upload" :size="14" />重发
                </CButton>
                <span v-else class="text-weak">—</span>
              </td>
            </tr>
            <tr v-if="!outboxList.length">
              <td colspan="11" class="cell-empty">暂无 Outbox 消息</td>
            </tr>
          </tbody>
        </table>
      </div>
    </CCard>

    <!-- 对账批次 tab -->
    <CCard v-else-if="tab === 'batches'" class="t3int__tablecard" padding="none">
      <div class="tablewrap">
        <table class="grid">
          <thead>
            <tr>
              <th>批次号</th>
              <th>连接器</th>
              <th>日期</th>
              <th>总数</th>
              <th>匹配</th>
              <th>待处理</th>
              <th>长款</th>
              <th>短款</th>
              <th>失败</th>
              <th>总金额</th>
              <th>差异金额</th>
              <th>状态</th>
              <th>开始时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="b in store.batches" :key="b.id">
              <td class="mono">{{ b.id }}</td>
              <td>{{ b.connectorName }}</td>
              <td class="mono">{{ b.date }}</td>
              <td class="num">{{ b.totalCount }}</td>
              <td class="num ok">{{ b.matchedCount }}</td>
              <td class="num">{{ b.pendingCount }}</td>
              <td class="num warn">{{ b.longCount }}</td>
              <td class="num danger">{{ b.shortCount }}</td>
              <td class="num danger">{{ b.failedCount }}</td>
              <td class="num">{{ fmtAmt(b.totalAmount) }}</td>
              <td class="num" :class="{ danger: b.diffAmount !== 0 }">{{ fmtAmt(b.diffAmount) }}</td>
              <td>
                <CStatusPill :status="batchStatusPill(b.status).status">{{ batchStatusPill(b.status).text }}</CStatusPill>
              </td>
              <td class="mono">{{ fmtDateTime(b.startedAt) }}</td>
            </tr>
            <tr v-if="!store.batches.length">
              <td colspan="13" class="cell-empty">暂无对账批次</td>
            </tr>
          </tbody>
        </table>
      </div>
    </CCard>

    <!-- 调用日志 tab -->
    <CCard v-else-if="tab === 'logs'" class="t3int__tablecard" padding="none">
      <div class="tablewrap">
        <table class="grid">
          <thead>
            <tr>
              <th>时间</th>
              <th>连接器</th>
              <th>Transaction ID</th>
              <th>方法</th>
              <th>Endpoint</th>
              <th>状态码</th>
              <th>延迟</th>
              <th>状态</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="l in logList"
              :key="l.id"
              :class="{ 'row-fail': l.status === 'FAIL' }"
            >
              <td class="mono">{{ fmtDateTime(l.requestAt) }}</td>
              <td>{{ l.connectorName }}</td>
              <td class="mono">{{ l.transactionId }}</td>
              <td><span class="method-badge">{{ l.method }}</span></td>
              <td class="mono endpoint" :title="l.endpoint">{{ l.endpoint }}</td>
              <td class="num" :class="{ danger: l.statusCode >= 400 }">{{ l.statusCode }}</td>
              <td class="num">{{ l.latencyMs }} ms</td>
              <td>
                <CStatusPill :status="l.status === 'ACK' ? 'success' : l.status === 'SENT' ? 'info' : 'danger'">
                  {{ l.status === 'ACK' ? '已确认' : l.status === 'SENT' ? '已发送' : '失败' }}
                </CStatusPill>
              </td>
            </tr>
            <tr v-if="!logList.length">
              <td colspan="8" class="cell-empty">暂无调用日志</td>
            </tr>
          </tbody>
        </table>
      </div>
    </CCard>

    <!-- 新建/编辑连接器抽屉 -->
    <CDrawer :show="editOpen" :title="editing?.mode === 'edit' ? '编辑连接器' : '新建连接器'" size="md" @update:show="editOpen = $event">
      <div v-if="editing" class="form">
        <CInput v-model="editing.name" label="连接器名称" placeholder="如：微信支付" />
        <div class="form__field">
          <label class="form__label">类型</label>
          <CSelect
            v-model="editing.type"
            :options="typeOptions"
            width="100%"
            :disabled="editing.mode === 'edit'"
          />
        </div>
        <CInput v-model="editing.endpoint" label="Endpoint" placeholder="https://api.example.com/v1" />
        <CInput v-model="editing.credentialKey" label="凭证 Key（演示 mask）" placeholder="如：wx_mch_****a3f2" />
        <div class="form__readonly">
          <span class="kv-k">同步模式</span>
          <CStatusPill status="default">UNIDIRECTIONAL · 单向镜像（不可改）</CStatusPill>
        </div>
        <div class="form__hint">
          <CIcon name="shield" :size="14" />
          单向镜像仅允许从业务侧同步凭证与单据，<em>绝不反向写入资金池</em>；所有出站消息带 <code>transaction_id</code> 幂等键。
        </div>
      </div>
      <template #footer>
        <CButton variant="ghost" size="sm" @click="editOpen = false">取消</CButton>
        <CButton
          variant="primary" size="sm"
          :disabled="!editing?.name.trim() || !editing?.endpoint.trim()"
          @click="saveConnector"
        >保存</CButton>
      </template>
    </CDrawer>

    <!-- 接入配置编辑抽屉（真实配置：URL / SECRET / SWITCH 三类条件字段，P5-B57 卡2） -->
    <CDrawer
      :show="intEditOpen"
      :title="intEditing ? `配置 · ${intEditing.name}` : '配置接入点'"
      size="md"
      @update:show="(v: boolean) => { intEditOpen = v; if (!v) intEditing = null }"
    >
      <div v-if="intEditing" class="form">
        <div class="form__readonly">
          <span class="kv-k">{{ CATEGORY_LABEL[intEditing.category] ?? intEditing.category }}</span>
          <CStatusPill status="info">{{ intEditing.code }}</CStatusPill>
          <CStatusPill status="default">
            {{ intEditing.valueKind === 'URL' ? '网关 URL' : intEditing.valueKind === 'SECRET' ? '密钥' : '开关' }}
          </CStatusPill>
        </div>

        <template v-if="intEditing.valueKind === 'URL'">
          <CInput
            :model-value="intForm.baseUrl"
            label="网关 URL"
            placeholder="https://api.example.com/v1/send"
            @update:model-value="intForm.baseUrl = $event"
          />
          <label v-if="intForm.baseUrl.trim().startsWith('http://')" class="access-chk">
            <input type="checkbox" v-model="intForm.insecureHttpConfirmed" />
            <span>我已知晓明文 HTTP 仅限内网联调，公网环境存在凭证被窃听风险，确认保存</span>
          </label>
        </template>

        <template v-else-if="intEditing.valueKind === 'SECRET'">
          <div v-if="intEditing.hasSecret" class="masked">
            当前已保存密钥：<b class="mono">{{ intEditing.secretMask ?? '****' }}</b>，留空保存表示不修改原密钥
          </div>
          <CInput
            type="password"
            :model-value="intForm.secret"
            label="渠道密钥"
            :placeholder="intEditing.hasSecret ? intEditing.secretMask ?? '****（留空不修改）' : '未配置（保存后不可读回）'"
            @update:model-value="intForm.secret = $event"
          />
        </template>

        <template v-else>
          <label class="access-chk">
            <input type="checkbox" v-model="intForm.boolValue" />
            <span>
              <span class="access-chk__title">开启免签回传（跳过 HMAC 验签）</span>
              <span class="access-chk__desc">仅用于广告渠道内网联调；生产环境开启将接收未验签的回传请求</span>
            </span>
          </label>
          <div v-if="intForm.boolValue" class="form__hint">
            <CIcon name="alert" :size="14" />
            <span><em>安全红线：</em>免签模式下任何来源都可提交回传数据，仅限隔离联调环境短时开启，联调结束请立即关闭。</span>
          </div>
        </template>

        <div v-if="intEditing.remark" class="form__readonly access-remark-ro">
          <span class="kv-k">未配置影响</span>
          <span class="text-weak">{{ intEditing.remark }}</span>
        </div>

        <label class="access-chk">
          <input type="checkbox" v-model="intForm.enabled" />
          <span>
            <span class="access-chk__title">启用该接入点</span>
            <span class="access-chk__desc">停用后对应功能进入诚实空态 / 优雅降级，不影响其他模块</span>
          </span>
        </label>
      </div>
      <template #footer>
        <CButton variant="ghost" size="sm" @click="intEditOpen = false">取消</CButton>
        <CButton variant="primary" size="sm" :disabled="!canEditInt || intSaving" @click="saveInt">
          {{ intSaving ? '保存中…' : '保存' }}
        </CButton>
      </template>
    </CDrawer>
  </div>
</template>

<style scoped>
.t3int {
  display: flex;
  flex-direction: column;
  gap: var(--s-md);
}
.t3int :deep(.card__body) {
  display: flex;
  flex-direction: column;
  gap: var(--s-md);
}

.t3int__head {
  display: grid;
  grid-auto-flow: column;
  grid-auto-columns: 1fr;
  gap: var(--s-md);
}
@media (max-width: 1024px) {
  .t3int__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); }
}
.kpi-wrap {
  position: relative;
  display: flex;
  min-width: 0;
}
.kpi-wrap > :deep(.ckpi) {
  width: 100%;
  min-width: 0;
}
.kpi-sub {
  position: absolute;
  right: var(--s-md);
  bottom: var(--s-sm);
  font-size: var(--t-xs);
  color: var(--c-text-3);
  background: var(--c-bg-page);
  padding: 2px 8px;
  border-radius: var(--r-pill);
}
.kpi-sub--err {
  color: var(--c-danger-fg);
  background: var(--c-danger-bg);
}

/* 红线 */
.t3int__redline :deep(.card__body) {
  padding: var(--s-md) var(--s-lg);
}
.redline {
  display: flex;
  gap: var(--s-md);
  align-items: flex-start;
  background: var(--c-danger-bg);
  border: 1px solid #ffd0d0;
  border-radius: var(--r-md);
  padding: var(--s-md);
  color: var(--c-danger-fg);
}
.redline__icon {
  flex-shrink: 0;
  margin-top: 2px;
}
.redline__text {
  font-size: var(--t-sm);
  line-height: var(--lh-base);
  color: var(--c-text);
}
.redline__text strong {
  color: var(--c-danger-fg);
}
.redline__text em {
  font-style: normal;
  font-weight: 600;
  color: var(--c-danger-fg);
}
.redline__text code {
  background: rgba(255, 77, 79, 0.08);
  padding: 1px 6px;
  border-radius: 4px;
  font-size: var(--t-xs);
}

.t3int__tabs {
  display: flex;
  align-items: center;
  gap: var(--s-sm);
}
.t3int__tabs-right {
  display: flex;
  align-items: center;
  gap: var(--s-sm);
  margin-left: auto;
  flex-shrink: 0;
}

/* flash */
.flash {
  display: flex;
  align-items: center;
  gap: var(--s-xs);
  padding: var(--s-sm) var(--s-md);
  border-radius: var(--r-md);
  font-size: var(--t-sm);
  border: 1px solid transparent;
}
.flash--ok {
  background: var(--c-success-bg);
  color: var(--c-success-fg);
  border-color: #b7eb8f;
}
.flash--warn {
  background: var(--c-warning-bg);
  color: var(--c-warning-fg);
  border-color: #ffd591;
}
.flash--err {
  background: var(--c-danger-bg);
  color: var(--c-danger-fg);
  border-color: #ffccc7;
}
.flash-enter-active, .flash-leave-active { transition: opacity 0.2s, transform 0.2s; }
.flash-enter-from, .flash-leave-to { opacity: 0; transform: translateY(-4px); }

/* 连接器卡片网格 */
.conn-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--s-md);
}
@media (min-width: 1440px) {
  .conn-grid { grid-template-columns: repeat(4, minmax(0, 1fr)); }
}
.conn-card {
  display: flex;
  flex-direction: column;
  gap: var(--s-sm);
  padding: var(--s-md);
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: var(--r-xl);
  box-shadow: var(--shadow-card);
  transition: box-shadow 0.15s, border-color 0.15s;
}
.conn-card:hover {
  box-shadow: var(--shadow-pop);
  border-color: var(--c-brand-border);
}
.conn-card--err {
  border-color: var(--c-danger-fg);
  box-shadow: 0 0 0 2px rgba(255, 77, 79, 0.12);
}
.conn-card--syncing {
  opacity: 0.85;
}
.conn-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-xs);
}
.conn-card__title {
  display: flex;
  align-items: center;
  gap: var(--s-xs);
  font-weight: 700;
  font-size: var(--t-md);
  color: var(--c-text);
}
.conn-card__type-icon {
  color: var(--c-brand);
}
.conn-card__type {
  display: flex;
}
.conn-card__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-sm);
  font-size: var(--t-xs);
}
.kv-k {
  color: var(--c-text-3);
  flex-shrink: 0;
}
.kv-v {
  color: var(--c-text-2);
  text-align: right;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mono { font-variant-numeric: tabular-nums; }
.conn-card__stats {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--s-sm);
  padding: var(--s-sm);
  background: var(--c-bg-page);
  border-radius: var(--r-md);
  margin-top: var(--s-xxs);
}
.conn-card__stats > div {
  text-align: center;
}
.stat-num {
  font-size: var(--t-lg);
  font-weight: 700;
  color: var(--c-text);
  font-variant-numeric: tabular-nums;
}
.stat-num--err { color: var(--c-danger-fg); }
.stat-label {
  font-size: var(--t-xs);
  color: var(--c-text-3);
}
.conn-card__err {
  display: flex;
  gap: var(--s-xs);
  padding: var(--s-xs) var(--s-sm);
  background: var(--c-danger-bg);
  color: var(--c-danger-fg);
  border-radius: var(--r-sm);
  font-size: var(--t-xs);
  align-items: flex-start;
}
.conn-card__ops {
  display: flex;
  gap: var(--s-xs);
  justify-content: flex-end;
  margin-top: auto;
  padding-top: var(--s-xs);
}

/* 表格 */
.tablewrap {
  overflow-x: auto;
}
table.grid {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--t-sm);
}
table.grid th,
table.grid td {
  padding: var(--s-sm) var(--s-md);
  text-align: left;
  border-bottom: 1px solid var(--c-border-light);
  vertical-align: middle;
  white-space: nowrap;
}
table.grid th {
  background: var(--c-bg-page);
  color: var(--c-text-3);
  font-weight: 600;
  font-size: var(--t-xs);
}
table.grid tbody tr:hover {
  background: var(--c-brand-soft);
}
table.grid tr.row-fail {
  background: var(--c-danger-bg);
}
table.grid tr.row-fail:hover {
  background: #ffe5e5;
}
.num {
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.num.ok { color: var(--c-success-fg); }
.num.warn { color: var(--c-warning-fg); }
.num.danger { color: var(--c-danger-fg); }
.cell-empty {
  text-align: center !important;
  color: var(--c-text-3);
  padding: var(--s-xl) 0 !important;
}
.text-weak {
  color: var(--c-text-4);
}
.ic-ok { color: var(--c-success-fg); }
.ic-pending { color: var(--c-text-3); }
.method-badge {
  display: inline-block;
  padding: 2px 8px;
  background: var(--c-info-bg);
  color: var(--c-info-fg);
  border-radius: var(--r-sm);
  font-size: var(--t-xs);
  font-weight: 600;
}
.endpoint {
  max-width: 280px;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 表单 */
.form {
  display: flex;
  flex-direction: column;
  gap: var(--s-md);
}
.form__field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.form__label {
  font-size: 13px;
  color: var(--c-text);
  line-height: 18px;
}
.form__readonly {
  display: flex;
  align-items: center;
  gap: var(--s-sm);
  padding: var(--s-sm);
  background: var(--c-bg-page);
  border-radius: var(--r-sm);
}
.form__hint {
  display: flex;
  gap: var(--s-xs);
  align-items: flex-start;
  padding: var(--s-sm);
  background: var(--c-danger-bg);
  color: var(--c-text-2);
  border-radius: var(--r-sm);
  font-size: var(--t-xs);
}
.form__hint code {
  background: rgba(255, 77, 79, 0.08);
  padding: 1px 6px;
  border-radius: 4px;
}
.form__hint em {
  font-style: normal;
  color: var(--c-danger-fg);
  font-weight: 600;
}

/* 接入配置 tab（P5-B57 卡2，仅布局类，视觉令牌全部沿用既有） */
.access-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-sm);
  font-weight: 600;
}
.access-state {
  display: flex;
  align-items: center;
  gap: var(--s-xs);
  padding: var(--s-xl) var(--s-md);
  color: var(--c-text-3);
  font-size: var(--t-sm);
}
.access-state--err {
  color: var(--c-danger-fg);
}
.access-name {
  font-weight: 600;
}
.access-code {
  font-size: var(--t-xs);
  color: var(--c-text-4);
}
.access-val {
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
}
.access-remark {
  margin-top: 2px;
  font-size: var(--t-xs);
  color: var(--c-text-3);
  white-space: normal;
  max-width: 280px;
}
.access-test {
  font-size: var(--t-xs);
  white-space: normal;
}
.access-ops {
  display: flex;
  gap: 2px;
  justify-content: flex-end;
}
.access-inline {
  display: flex;
  align-items: flex-start;
  gap: 4px;
  margin-top: 4px;
  font-size: var(--t-xs);
  line-height: 1.5;
  white-space: normal;
  max-width: 200px;
}
.access-inline--ok {
  color: var(--c-success-fg);
}
.access-inline--err {
  color: var(--c-danger-fg);
}
.masked {
  font-size: var(--t-xs);
  color: var(--c-text-2);
  background: var(--c-bg-page);
  border-radius: var(--r-sm);
  padding: var(--s-sm);
}
.masked b {
  color: var(--c-text);
}
.access-remark-ro {
  align-items: flex-start;
  flex-direction: column;
  gap: 4px;
}
.access-chk {
  display: flex;
  align-items: flex-start;
  gap: var(--s-xs);
  font-size: var(--t-sm);
  color: var(--c-text-2);
  cursor: pointer;
  line-height: 1.6;
}
.access-chk input {
  margin-top: 3px;
}
.access-chk__title {
  display: block;
  color: var(--c-text);
  font-weight: 600;
}
.access-chk__desc {
  display: block;
  font-size: var(--t-xs);
  color: var(--c-text-3);
}
</style>
