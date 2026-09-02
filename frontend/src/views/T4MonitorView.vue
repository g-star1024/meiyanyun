<template>
  <div class="mon">
    <!-- 头部 KPI + 主按钮 -->
    <div class="mon__head">
      <CKpi v-for="k in kpis" :key="k.label" :value="k.value" :label="k.label" :tone="k.tone" :icon="k.icon" />
    </div>

    <CCard padding="lg">
      <div class="mon__bar">
        <CSegmented v-model="tab" :options="tabOptions" />
        <CButton variant="primary" :disabled="!store.can('monitor:rule:create')" @click="openCreate">
          <CIcon name="bell" :size="16" />新建告警规则
        </CButton>
      </div>

      <!-- 实时监控 -->
      <div v-if="tab === 'live'" class="mon__table">
        <CTable :columns="liveCols" :rows="liveRows" row-key="modelId">
          <template #col-latencyP99="{ row }">
            <div class="with-bar">
              <span>{{ row.latencyP99 }}ms</span>
              <CProgressBar
                :value="row.latencyP99"
                :max="300"
                :color="row.latencyP99 > 200 ? 'var(--c-warning-fg)' : 'var(--c-brand)'"
                :height="4"
                :show-label="false"
              />
            </div>
          </template>
          <template #col-errorRate="{ value }">
            <span :class="{ 'is-danger': Number(value) > 5 }">{{ value }}%</span>
          </template>
          <template #col-driftScore="{ value }">
            <span :class="{ 'is-danger': Number(value) > 0.7 }">{{ value }}</span>
          </template>
          <template #col-spark="{ value }">
            <span class="spark">
              <i v-for="(h, i) in value" :key="i" :style="{ height: h + '%' }" />
            </span>
          </template>
        </CTable>
      </div>

      <!-- 告警事件 -->
      <div v-else-if="tab === 'events'" class="mon__table">
        <CTable :columns="eventCols" :rows="eventRows" row-key="id">
          <template #col-severity="{ value }">
            <CStatusPill :status="sevPill(value as AlertSeverity)" dot>{{ store.ALERT_SEVERITY_LABEL[value as AlertSeverity] }}</CStatusPill>
          </template>
          <template #col-status="{ value }">
            <CStatusPill :status="evtPill(value as AlertStatus)">{{ store.ALERT_STATUS_LABEL[value as AlertStatus] }}</CStatusPill>
          </template>
          <template #col-value="{ row }">
            <span class="num">{{ row.value }} <span class="th">/ {{ row.threshold }}</span></span>
          </template>
          <template #col-triggeredAt="{ value }">
            {{ formatTime(value) }}
          </template>
          <template #col-actions="{ row }">
            <div class="row-ops">
              <CButton
                v-if="row._status === 'FIRING'"
                size="sm" variant="secondary"
                @click="ack(row as AlertEvent)"
              >确认</CButton>
              <CButton
                v-if="row._status !== 'RESOLVED'"
                size="sm" variant="primary"
                @click="resolve(row as AlertEvent)"
              >解决</CButton>
            </div>
          </template>
        </CTable>
      </div>

      <!-- 告警规则 -->
      <div v-else class="mon__table">
        <CTable :columns="ruleCols" :rows="ruleRows" row-key="id">
          <template #col-metric="{ value }">
            {{ store.ALERT_METRIC_LABEL[value as AlertRule['metric']] }}
          </template>
          <template #col-condition="{ row }">
            <code class="cond">{{ row.metric }} {{ row.operator }} {{ row.threshold }}</code>
          </template>
          <template #col-severity="{ value }">
            <CStatusPill :status="sevPill(value as AlertSeverity)">{{ store.ALERT_SEVERITY_LABEL[value as AlertSeverity] }}</CStatusPill>
          </template>
          <template #col-notifyChannels="{ value }">
            <span class="chips">
              <span v-for="c in value" :key="c" class="chip">{{ c }}</span>
            </span>
          </template>
          <template #col-enabled="{ row }">
            <label class="switch" :class="{ 'is-on': row.enabled }">
              <input type="checkbox" :checked="row.enabled" @change="toggleRule(row as AlertRule)" :disabled="!store.can('monitor:rule:edit')" />
              <span class="switch__track" />
            </label>
          </template>
          <template #col-actions>
            <CButton size="sm" variant="text" :disabled="!store.can('monitor:rule:edit')">编辑</CButton>
          </template>
        </CTable>
      </div>
    </CCard>

    <!-- 新建规则抽屉 -->
    <CDrawer :show="createOpen" title="新建告警规则" size="md" @update:show="createOpen = $event">
      <div class="form">
        <CInput v-model="ruleForm.name" label="规则名称" placeholder="如：皮肤影像-漂移过高" />
        <div class="form__row">
          <div class="form__field">
            <label class="flabel">模型</label>
            <CSelect v-model="ruleForm.modelId" :options="modelOptions" width="100%" />
          </div>
          <div class="form__field">
            <label class="flabel">指标</label>
            <CSelect v-model="ruleForm.metric" :options="metricOptions" width="100%" />
          </div>
        </div>
        <div class="form__row">
          <div class="form__field">
            <label class="flabel">比较操作符</label>
            <CSelect v-model="ruleForm.operator" :options="operatorOptions" width="100%" />
          </div>
          <div class="form__field">
            <label class="flabel">阈值</label>
            <input class="native-input" type="number" :step="ruleForm.metric === 'DRIFT' || ruleForm.metric === 'ACCURACY' ? 0.01 : 1" v-model.number="ruleForm.threshold" />
          </div>
        </div>
        <div class="form__field">
          <label class="flabel">严重度</label>
          <CSelect v-model="ruleForm.severity" :options="severityOptions" width="100%" />
        </div>
        <div class="form__field">
          <label class="flabel">通知渠道（多选）</label>
          <div class="chips-check">
            <label v-for="c in channelOptions" :key="c" class="check-chip" :class="{ 'is-on': ruleForm.notifyChannels.includes(c) }">
              <input type="checkbox" :value="c" :checked="ruleForm.notifyChannels.includes(c)" @change="toggleChannel(c)" />
              <span>{{ c }}</span>
            </label>
          </div>
        </div>
      </div>
      <template #footer>
        <CButton variant="ghost" @click="createOpen = false">取消</CButton>
        <CButton variant="primary" :disabled="!canSubmitRule" @click="submitRule">创建规则</CButton>
      </template>
    </CDrawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CKpi from '@/components/CKpi.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CDrawer from '@/components/CDrawer.vue'
import CSegmented from '@/components/CSegmented.vue'
import CTable from '@/components/CTable.vue'
import CProgressBar from '@/components/CProgressBar.vue'
import {
  useT4MonitorStore,
  type AlertEvent,
  type AlertSeverity,
  type AlertStatus,
  type AlertRule,
} from '@/stores/t4Monitor'

const store = useT4MonitorStore()
onMounted(() => store.seed())

const kpis = computed(() => [
  { label: '告警中', icon: 'alert', value: String(store.kpi.firing), tone: 'danger' as const },
  { label: '严重告警', icon: 'alert', value: String(store.kpi.critical), tone: 'danger' as const },
  { label: '平均P99延迟', icon: 'clock', value: store.kpi.avgLatency + 'ms', tone: 'warning' as const },
  { label: '监控模型数', icon: 'settings', value: String(store.kpi.models), tone: 'text' as const },
])

const tab = ref('live')
const tabOptions = [
  { label: '实时监控', value: 'live' },
  { label: '告警事件', value: 'events' },
  { label: '告警规则', value: 'rules' },
]

function sevPill(s: AlertSeverity) {
  return ({ CRITICAL: 'danger', WARNING: 'warning', INFO: 'info' } as const)[s]
}
function evtPill(s: AlertStatus) {
  return ({ FIRING: 'danger', ACKNOWLEDGED: 'warning', RESOLVED: 'success' } as const)[s]
}
function formatTime(iso: string) {
  const d = new Date(iso)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

// ---- 实时监控表 ----
const liveCols = [
  { key: 'modelName', label: '模型' },
  { key: 'qps', label: 'QPS', align: 'right' as const },
  { key: 'latencyP99', label: 'P99 延迟' },
  { key: 'errorRate', label: '错误率', align: 'right' as const },
  { key: 'driftScore', label: '漂移分数', align: 'right' as const },
  { key: 'accuracy', label: '准确率', align: 'right' as const },
  { key: 'spark', label: '近12点趋势', width: 120 },
]
// 行高亮：漂移>0.7 或 错误率>5%
const liveRows = computed(() => store.metrics.map((m) => ({
  ...m,
  spark: Array.from({ length: 12 }, (_, i) => {
    const base = m.qps || 10
    return Math.max(8, Math.min(100, 30 + ((i * 17 + m.modelId.length * 13) % 60) + (base % 20)))
  }),
  _highlight: m.driftScore > 0.7 || m.errorRate > 5,
})))

// ---- 事件表 ----
const eventCols = [
  { key: 'ruleName', label: '规则' },
  { key: 'modelName', label: '模型' },
  { key: 'severity', label: '严重度', width: 90 },
  { key: 'status', label: '状态', width: 100 },
  { key: 'message', label: '消息' },
  { key: 'value', label: '当前/阈值', width: 120, align: 'right' as const },
  { key: 'triggeredAt', label: '触发时间', width: 110 },
  { key: 'actions', label: '操作', width: 150, align: 'right' as const },
]
const eventRows = computed(() => [...store.events]
  .sort((a, b) => b.triggeredAt.localeCompare(a.triggeredAt))
  .map((e) => ({ ...e, _status: e.status })))

function ack(row: AlertEvent) { store.acknowledgeAlert(row.id) }
function resolve(row: AlertEvent) { store.resolveAlert(row.id) }

// ---- 规则表 ----
const ruleCols = [
  { key: 'name', label: '规则名' },
  { key: 'modelName', label: '模型' },
  { key: 'metric', label: '指标', width: 130 },
  { key: 'condition', label: '触发条件', width: 180 },
  { key: 'severity', label: '严重度', width: 90 },
  { key: 'notifyChannels', label: '通知渠道' },
  { key: 'enabled', label: '启用', width: 70, align: 'center' as const },
  { key: 'actions', label: '操作', width: 80, align: 'right' as const },
]
const ruleRows = computed(() => store.rules.map((r) => ({ ...r })))
function toggleRule(row: AlertRule) {
  store.toggleRule(row.id, !row.enabled)
}

// ---- 新建规则 ----
const createOpen = ref(false)
const defaultForm = () => ({
  name: '',
  modelId: store.metrics[0]?.modelId ?? '',
  metric: 'DRIFT' as AlertRule['metric'],
  operator: '>' as AlertRule['operator'],
  threshold: 0.7,
  severity: 'WARNING' as AlertSeverity,
  notifyChannels: [] as string[],
})
const ruleForm = reactive(defaultForm())

const modelOptions = computed(() => store.metrics.map((m) => ({ label: m.modelName, value: m.modelId })))
const metricOptions = [
  { label: '漂移分数 (DRIFT)', value: 'DRIFT' },
  { label: 'P99 延迟 (ms)', value: 'LATENCY' },
  { label: '错误率 (%)', value: 'ERROR_RATE' },
  { label: '准确率', value: 'ACCURACY' },
  { label: 'QPS 下跌 (%)', value: 'QPS_DROP' },
]
const operatorOptions = [
  { label: '> 大于', value: '>' },
  { label: '< 小于', value: '<' },
  { label: '>= 大于等于', value: '>=' },
  { label: '<= 小于等于', value: '<=' },
]
const severityOptions = [
  { label: '严重 CRITICAL', value: 'CRITICAL' },
  { label: '警告 WARNING', value: 'WARNING' },
  { label: '提示 INFO', value: 'INFO' },
]
const channelOptions = ['企微', '邮件', '短信']

const canSubmitRule = computed(() =>
  ruleForm.name.trim() && ruleForm.modelId && ruleForm.notifyChannels.length > 0,
)

function openCreate() {
  if (!store.can('monitor:rule:create')) return
  Object.assign(ruleForm, defaultForm())
  createOpen.value = true
}
function toggleChannel(c: string) {
  const i = ruleForm.notifyChannels.indexOf(c)
  if (i >= 0) ruleForm.notifyChannels.splice(i, 1)
  else ruleForm.notifyChannels.push(c)
}
function submitRule() {
  const m = store.metrics.find((x) => x.modelId === ruleForm.modelId)
  store.createRule({
    name: ruleForm.name.trim(),
    modelId: ruleForm.modelId,
    modelName: m?.modelName ?? '',
    metric: ruleForm.metric,
    operator: ruleForm.operator,
    threshold: Number(ruleForm.threshold),
    severity: ruleForm.severity,
    notifyChannels: [...ruleForm.notifyChannels],
  })
  createOpen.value = false
}
</script>

<style scoped>
.mon { display: flex; flex-direction: column; gap: var(--s-lg); }
.mon__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
.mon__bar { display: flex; justify-content: space-between; align-items: center; gap: var(--s-md); margin-bottom: var(--s-md); flex-wrap: wrap; }

.mon__table { margin-top: var(--s-md); }
.mon__table :deep(tbody tr.is-hot) { background: var(--c-danger-bg); }

.with-bar { display: flex; flex-direction: column; gap: 4px; min-width: 120px; }
.with-bar > span { font-size: var(--t-sm); font-variant-numeric: tabular-nums; }
.is-danger { color: var(--c-danger-fg); font-weight: 600; }
.num { font-variant-numeric: tabular-nums; }
.th { color: var(--c-text-3); font-weight: 400; }

/* 迷你趋势 sparkline */
.spark { display: inline-flex; align-items: flex-end; gap: 2px; height: 24px; }
.spark i {
  display: block; width: 4px; min-height: 4px;
  background: var(--c-brand);
  border-radius: 1px;
  opacity: 0.75;
}

.row-ops { display: inline-flex; gap: var(--s-xxs); justify-content: flex-end; }

.cond {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: var(--t-xs);
  padding: 2px 6px;
  background: var(--c-bg-page);
  border-radius: var(--r-sm);
  color: var(--c-text-2);
}
.chips { display: inline-flex; gap: 4px; flex-wrap: wrap; }
.chip {
  padding: 2px 8px;
  border-radius: var(--r-pill);
  background: var(--c-brand-soft);
  color: var(--c-brand);
  font-size: var(--t-xs);
}

/* 启用开关 */
.switch { position: relative; display: inline-block; width: 38px; height: 20px; cursor: pointer; }
.switch input { opacity: 0; width: 0; height: 0; }
.switch__track {
  position: absolute; inset: 0;
  background: var(--c-border); border-radius: 999px; transition: background 0.15s;
}
.switch__track::before {
  content: ''; position: absolute;
  width: 16px; height: 16px; left: 2px; top: 2px;
  background: #fff; border-radius: 50%;
  transition: transform 0.15s;
  box-shadow: 0 1px 3px rgba(0,0,0,0.2);
}
.switch.is-on .switch__track { background: var(--c-success-fg); }
.switch.is-on .switch__track::before { transform: translateX(18px); }
.switch input:disabled + .switch__track { opacity: 0.5; cursor: not-allowed; }

/* 表单 */
.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.form__field { display: flex; flex-direction: column; gap: 6px; }
.flabel { font-size: 13px; color: var(--c-text); line-height: 18px; }
.native-input {
  width: 100%; height: 36px; padding: 0 var(--s-sm);
  border: 1px solid var(--c-border); border-radius: var(--r-sm);
  background: var(--c-surface); font-size: var(--t-sm); color: var(--c-text);
  font-family: inherit;
}
.native-input:focus { outline: none; border-color: var(--c-brand-border); }

.chips-check { display: flex; gap: var(--s-sm); flex-wrap: wrap; }
.check-chip {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 6px 12px;
  border: 1px solid var(--c-border);
  border-radius: var(--r-capsule);
  font-size: var(--t-sm);
  color: var(--c-text-2);
  cursor: pointer;
  background: var(--c-surface);
  transition: all 0.15s;
}
.check-chip input { display: none; }
.check-chip.is-on {
  background: var(--c-brand-soft);
  border-color: var(--c-brand);
  color: var(--c-brand);
  font-weight: 600;
}
</style>
