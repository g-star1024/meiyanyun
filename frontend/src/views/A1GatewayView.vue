<script setup lang="ts">
/* A1-18/19/20 API网关与日志 /ai/gateway
   B42 接真：KPI 与调用日志来自 ai-service /api/ai/logs（权限 aiGateway:view）。
   API 目录与告警规则为规划能力，本期静态占位（Backlog）。 */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CKpi from '@/components/CKpi.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CTable from '@/components/CTable.vue'
import CSegmented from '@/components/CSegmented.vue'
import CSelect from '@/components/CSelect.vue'
import CPagination from '@/components/CPagination.vue'
import { useToast } from '@/composables/useToast'
import { errMsg } from '@/stores/m5Coupon'
import { logKpi, searchLogs, listAlerts, type AiKpi, type AiLogView, type AlertView } from '@/api/ai'

const toast = useToast()

const tab = ref('api')
const tabOptions = [
  { label: 'API 目录', value: 'api' },
  { label: '调用日志', value: 'logs' },
  { label: '告警规则', value: 'alerts' },
]

const kpiRaw = ref<AiKpi | null>(null)
const kpis = computed(() => (kpiRaw.value ? toKpis(kpiRaw.value) : null))
function toKpis(k: AiKpi) {
  return [
    { label: '今日调用', icon: 'settings', value: k.todayCalls.toLocaleString(), tone: 'purple' as const },
    { label: '成功率', icon: 'trend-up', value: `${k.successRate}%`, tone: 'success' as const },
    { label: 'P99 延迟', icon: 'clock', value: k.p99LatencyMs == null ? '—' : `${k.p99LatencyMs}ms`, tone: 'teal' as const },
    { label: '活跃告警', icon: 'alert', value: String(k.activeAlerts), tone: 'danger' as const },
  ]
}

const apiCols = [
  { key: 'path', label: 'API 路径' }, { key: 'method', label: '方法', width: '80' },
  { key: 'qps', label: '限流QPS', width: '90', align: 'right' as const },
  { key: 'auth', label: '鉴权', width: '100' }, { key: 'calls', label: '今日调用', width: '100', align: 'right' as const },
  { key: 'status', label: '状态', width: '90' },
]
const apiCatalog = [
  { id: 1, path: '/api/ai/features/{code}/invoke', method: 'POST', qps: 100, auth: 'Bearer', invoke: true, status: '正常' },
  { id: 2, path: '/api/ai/internal/llm/chat', method: 'POST', qps: 50, auth: 'Internal-Token', invoke: false, status: '规划中' },
  { id: 3, path: '/api/ai/providers', method: 'GET', qps: 100, auth: 'Bearer', invoke: false, status: '正常' },
  { id: 4, path: '/api/ai/models/{id}/test', method: 'POST', qps: 20, auth: 'Bearer', invoke: false, status: '正常' },
  { id: 5, path: '/api/ai/logs', method: 'GET', qps: 100, auth: 'Bearer', invoke: false, status: '正常' },
]
const apis = computed(() =>
  apiCatalog.map((a) => ({
    ...a,
    calls: a.invoke ? (kpiRaw.value?.todayCalls ?? 0) : 0,
  })),
)
const logCols = [
  { key: 'invokedAt', label: '时间', width: '150' },
  { key: 'feature', label: '功能 / 模型' },
  { key: 'caller', label: '调用方', width: '140' },
  { key: 'success', label: '结果', width: '100' },
  { key: 'totalTokens', label: 'Tokens', width: '90', align: 'right' as const },
  { key: 'latencyMs', label: '延迟', width: '90', align: 'right' as const },
]
const logs = ref<AiLogView[]>([])
const logLoading = ref(false)
const page = ref(1)
const pageSize = 20
const total = ref(0)
const successFilter = ref('')
const successOptions = [
  { label: '全部结果', value: '' },
  { label: '成功', value: '1' },
  { label: '失败', value: '0' },
]

const alertCols = [
  { key: 'ruleName', label: '规则名' }, { key: 'metricText', label: '指标' },
  { key: 'thresholdText', label: '阈值' }, { key: 'currentText', label: '当前值', width: '110' },
  { key: 'notifyChannel', label: '通知渠道', width: '100' },
  { key: 'status', label: '状态', width: '90' },
]
const alerts = ref<AlertView[]>([])
async function loadAlerts() {
  try {
    alerts.value = await listAlerts()
  } catch (e) {
    toast.error('告警规则加载失败：' + errMsg(e))
  }
}
const alertRows = computed(() =>
  alerts.value.map((a) => ({
    ...a,
    metricText: METRIC_LABEL[a.metric] || a.metric,
    thresholdText: `${a.compareOp} ${fmtMetric(a.metric, a.thresholdNum)}`,
    currentText: fmtMetric(a.metric, a.currentValue),
  })),
)
const METRIC_LABEL: Record<string, string> = {
  LATENCY_P99: 'P99 延迟',
  ERROR_RATE: '错误率',
  CALL_COUNT: '调用量（当日）',
  SUCCESS_RATE: '成功率',
  QUOTA_WATERMARK: '配额水位',
}
function fmtMetric(metric: string, v: number) {
  if (metric === 'LATENCY_P99') return `${Math.round(v)}ms`
  if (metric === 'CALL_COUNT') return `${v} 次`
  return `${v}%`
}
function alertPill(a: AlertView) {
  if (!a.enabled) return 'default' as const
  return a.active ? 'danger' as const : 'success' as const
}

async function loadKpi() {
  try {
    kpiRaw.value = await logKpi()
  } catch (e) {
    toast.error('网关指标加载失败：' + errMsg(e))
  }
}
async function loadLogs() {
  logLoading.value = true
  try {
    const r = await searchLogs({
      success: successFilter.value === '' ? null : successFilter.value === '1',
      page: page.value - 1,
      size: pageSize,
    })
    logs.value = r.content
    total.value = r.totalElements
  } catch (e) {
    toast.error('调用日志加载失败：' + errMsg(e))
  } finally {
    logLoading.value = false
  }
}
function changeFilter() {
  page.value = 1
  loadLogs()
}
function changePage(n: number) {
  page.value = n
  loadLogs()
}
onMounted(() => {
  loadKpi()
  loadLogs()
  loadAlerts()
})

function fmtTime(s: string | null) {
  if (!s) return '—'
  return s.replace('T', ' ').slice(5, 19)
}
function fmtTokens(v: number | null) {
  return v == null ? '—' : v.toLocaleString()
}
function fmtLatency(v: number | null) {
  return v == null ? '—' : `${v}ms`
}
function statusPill(s: string) {
  return s === '正常' ? 'success' as const : 'warning' as const
}
</script>

<template>
  <div class="a1-gw">
    <div class="kpis">
      <CKpi v-for="k in (kpis ?? [])" :key="k.label" v-bind="k" />
    </div>
    <CCard padding="lg">
      <CSegmented v-model="tab" :options="tabOptions" />
      <div class="mt">
        <CTable v-if="tab === 'api'" :columns="apiCols" :rows="apis" row-key="id" stripe>
          <template #col-method="{ value }"><span class="method" :class="value.toLowerCase()">{{ value }}</span></template>
          <template #col-status="{ value }"><CStatusPill :status="statusPill(value)" dot>{{ value }}</CStatusPill></template>
        </CTable>
        <template v-else-if="tab === 'logs'">
          <div class="log-bar">
            <CSelect v-model="successFilter" :options="successOptions" width="140px" @update:model-value="changeFilter" />
            <CButton size="sm" variant="secondary" :disabled="logLoading" @click="loadLogs">刷新</CButton>
          </div>
          <CTable :columns="logCols" :rows="logs" row-key="logId" stripe
            :empty-text="logLoading ? '加载中…' : '暂无调用日志（功能调用经 /api/ai 网关后在此沉淀）'">
            <template #col-invokedAt="{ value }">{{ fmtTime(value) }}</template>
            <template #col-feature="{ row }">
              <div class="feat">
                <span class="feat__name">{{ row.featureName || row.featureCode || '—' }}</span>
                <span class="feat__model mono">{{ row.modelCode || '—' }}</span>
              </div>
            </template>
            <template #col-caller="{ row }">{{ row.staffName || row.storeCode || '系统' }}</template>
            <template #col-success="{ row }">
              <CStatusPill v-if="row.success" status="success" dot>成功</CStatusPill>
              <CStatusPill v-else status="danger" :title="row.errorCode || ''" dot>失败{{ row.errorCode ? `·${row.errorCode}` : '' }}</CStatusPill>
            </template>
            <template #col-totalTokens="{ value }">{{ fmtTokens(value) }}</template>
            <template #col-latencyMs="{ value }">{{ fmtLatency(value) }}</template>
          </CTable>
          <CPagination :page="page" :page-size="pageSize" :total="total" @update:page="changePage" />
        </template>
        <template v-else>
          <div class="bar"><CButton size="sm" variant="secondary" @click="loadAlerts">刷新状态</CButton></div>
          <CTable :columns="alertCols" :rows="alertRows" row-key="ruleId" stripe
            empty-text="暂无告警规则">
            <template #col-status="{ row }">
              <CStatusPill :status="alertPill(row as AlertView)" :title="row.active ? '当前值已越过阈值' : ''" dot>{{ row.status }}</CStatusPill>
            </template>
          </CTable>
        </template>
      </div>
    </CCard>
    <p class="hint">所有 AI 调用经网关鉴权后落 ai_invoke_log（append-only），连通性测试与配置变更同步写入审计日志；告警状态按当日调用数据实时评估，通知推送为后续批次能力。</p>
  </div>
</template>

<style scoped>
.a1-gw { display: flex; flex-direction: column; gap: var(--s-lg); }
.kpis { display: flex; gap: var(--s-md); }
.mt { margin-top: var(--s-md); }
.method { font-family: ui-monospace, monospace; font-size: 11px; padding: 2px 6px; border-radius: 3px; font-weight: 600; }
.method.post { background: var(--c-info-bg); color: var(--c-info-fg); }
.method.get { background: var(--c-success-bg, #e7f6ec); color: var(--c-success-fg, #1a7f37); }
.bar { display: flex; justify-content: flex-end; margin-bottom: var(--s-sm); }
.log-bar { display: flex; justify-content: flex-end; gap: var(--s-sm); margin-bottom: var(--s-sm); }
.feat { display: flex; flex-direction: column; gap: 2px; }
.feat__name { font-size: var(--t-sm); color: var(--c-text); }
.feat__model { font-size: var(--t-xs); color: var(--c-text-3); }
.mono { font-family: ui-monospace, monospace; }
.hint { font-size: var(--t-xs); color: var(--c-text-3); margin: 0; }
</style>
