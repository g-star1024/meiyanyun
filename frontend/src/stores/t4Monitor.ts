// ============================================================
// T4 AI 中台底座 - 监控告警 store
// 模型实时指标 + 告警规则 + 告警事件（确认/解决闭环）
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { errMsg } from './m5Coupon'
import { useToast } from '@/composables/useToast'
import * as api from '@/api/t4Monitor'

export type AlertSeverity = 'CRITICAL' | 'WARNING' | 'INFO'
export type AlertStatus = 'FIRING' | 'ACKNOWLEDGED' | 'RESOLVED'

export interface ModelMetric {
  modelId: string
  modelName: string
  timestamp: string
  qps: number
  latencyP99: number
  errorRate: number
  driftScore: number
  accuracy: number
}

export interface AlertRule {
  id: string
  name: string
  modelId: string
  modelName: string
  metric: 'DRIFT' | 'LATENCY' | 'ERROR_RATE' | 'ACCURACY' | 'QPS_DROP'
  threshold: number
  operator: '>' | '<' | '>=' | '<='
  severity: AlertSeverity
  enabled: boolean
  notifyChannels: string[]
  createdAt: string
}

export interface AlertEvent {
  id: string
  ruleId: string
  ruleName: string
  modelId: string
  modelName: string
  severity: AlertSeverity
  status: AlertStatus
  message: string
  value: number
  threshold: number
  triggeredAt: string
  acknowledgedAt?: string
  resolvedAt?: string
  acknowledgedBy?: string
}

export const ALERT_SEVERITY_LABEL: Record<AlertSeverity, string> = {
  CRITICAL: '严重',
  WARNING: '警告',
  INFO: '提示',
}
export const ALERT_STATUS_LABEL: Record<AlertStatus, string> = {
  FIRING: '告警中',
  ACKNOWLEDGED: '已确认',
  RESOLVED: '已解决',
}
export const ALERT_METRIC_LABEL: Record<AlertRule['metric'], string> = {
  DRIFT: '漂移分数',
  LATENCY: 'P99 延迟(ms)',
  ERROR_RATE: '错误率(%)',
  ACCURACY: '准确率',
  QPS_DROP: 'QPS 下跌(%)',
}

// —— 铁律 -1-B：前后端差异只在此消化；id = 后端 code；null→undefined ——
function mapMetric(v: api.MetricView): ModelMetric {
  return {
    modelId: v.modelId,
    modelName: v.modelName,
    timestamp: v.timestamp,
    qps: Number(v.qps),
    latencyP99: Number(v.latencyP99),
    errorRate: Number(v.errorRate),
    driftScore: Number(v.driftScore),
    accuracy: Number(v.accuracy),
  }
}

function mapRule(v: api.RuleView): AlertRule {
  return {
    id: v.id,
    name: v.name,
    modelId: v.modelId,
    modelName: v.modelName,
    metric: v.metric as AlertRule['metric'],
    threshold: Number(v.threshold),
    operator: v.operator as AlertRule['operator'],
    severity: v.severity as AlertSeverity,
    enabled: v.enabled,
    notifyChannels: v.notifyChannels ?? [],
    createdAt: v.createdAt,
  }
}

function mapEvent(v: api.EventView): AlertEvent {
  return {
    id: v.id,
    ruleId: v.ruleId,
    ruleName: v.ruleName,
    modelId: v.modelId,
    modelName: v.modelName,
    severity: v.severity as AlertSeverity,
    status: v.status as AlertStatus,
    message: v.message,
    value: Number(v.value),
    threshold: Number(v.threshold),
    triggeredAt: v.triggeredAt,
    acknowledgedAt: v.acknowledgedAt ?? undefined,
    resolvedAt: v.resolvedAt ?? undefined,
    acknowledgedBy: v.acknowledgedBy ?? undefined,
  }
}

export const useT4MonitorStore = defineStore('t4Monitor', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()
  const toast = useToast()

  const metrics = ref<ModelMetric[]>([])
  const rules = ref<AlertRule[]>([])
  const events = ref<AlertEvent[]>([])
  const loaded = ref(false)
  const loading = ref(false)
  const loadError = ref('')

  // ---- 查询 ----
  const firingAlerts = computed(() => events.value.filter((e) => e.status === 'FIRING'))

  const kpi = computed(() => {
    const firing = events.value.filter((e) => e.status === 'FIRING').length
    const critical = events.value.filter((e) => e.severity === 'CRITICAL' && e.status !== 'RESOLVED').length
    const avgLatency = metrics.value.length
      ? Math.round(metrics.value.reduce((s, m) => s + m.latencyP99, 0) / metrics.value.length)
      : 0
    return { firing, critical, avgLatency, models: metrics.value.length }
  })

  async function load() {
    if (loading.value) return
    loading.value = true
    loadError.value = ''
    try {
      const data = await api.overview()
      metrics.value = data.metrics.map(mapMetric)
      rules.value = data.rules.map(mapRule)
      events.value = data.events.map(mapEvent)
      loaded.value = true
    } catch (e) {
      loadError.value = errMsg(e, '监控数据加载失败')
    } finally {
      loading.value = false
    }
  }

  function can(perm: string) {
    return auth.can(perm)
  }

  // ---- 命令 ----
  async function createRule(input: Omit<AlertRule, 'id' | 'createdAt' | 'enabled'> & { enabled?: boolean }): Promise<AlertRule | null> {
    if (!can('monitor:rule:create')) {
      toast.error('无操作权限：需要 monitor:rule:create')
      return null
    }
    try {
      const created = await api.createRule({
        name: input.name,
        modelId: input.modelId,
        modelName: input.modelName,
        metric: input.metric,
        threshold: input.threshold,
        operator: input.operator,
        severity: input.severity,
        enabled: input.enabled,
        notifyChannels: input.notifyChannels,
      })
      const r = mapRule(created)
      rules.value.unshift(r)
      activity.log(auth.user.name, `创建告警规则「${r.name}」（${ALERT_METRIC_LABEL[r.metric]} ${r.operator} ${r.threshold}）`, r.id)
      toast.success(`告警规则「${r.name}」已创建（${r.id}）`)
      return r
    } catch (e) {
      toast.error(errMsg(e, '告警规则创建失败'))
      return null
    }
  }

  async function updateRule(id: string, patch: Partial<Omit<AlertRule, 'id' | 'createdAt'>>) {
    if (!can('monitor:rule:edit')) {
      toast.error('无操作权限：需要 monitor:rule:edit')
      return
    }
    const r = rules.value.find((x) => x.id === id)
    if (!r) return
    try {
      const next = await api.updateRule(id, {
        name: patch.name,
        metric: patch.metric,
        threshold: patch.threshold,
        operator: patch.operator,
        severity: patch.severity,
        notifyChannels: patch.notifyChannels,
      })
      Object.assign(r, mapRule(next))
      activity.log(auth.user.name, `更新告警规则「${r.name}」`, id)
      toast.success(`告警规则「${r.name}」已更新`)
    } catch (e) {
      toast.error(errMsg(e, '告警规则更新失败'))
    }
  }

  async function toggleRule(id: string, enabled: boolean) {
    if (!can('monitor:rule:edit')) {
      toast.error('无操作权限：需要 monitor:rule:edit')
      return
    }
    const r = rules.value.find((x) => x.id === id)
    if (!r) return
    try {
      const next = await api.setEnabled(id, enabled)
      Object.assign(r, mapRule(next))
      activity.log(auth.user.name, `告警规则「${r.name}」${enabled ? '启用' : '停用'}`, id)
      toast.success(`告警规则「${r.name}」已${enabled ? '启用' : '停用'}`)
    } catch (e) {
      toast.error(errMsg(e, '操作失败'))
    }
  }

  async function acknowledgeAlert(id: string) {
    const e = events.value.find((x) => x.id === id)
    if (!e || e.status !== 'FIRING') return
    try {
      const next = await api.acknowledge(id)
      Object.assign(e, mapEvent(next))
      activity.log(auth.user.name, `确认告警「${e.ruleName}」（模型：${e.modelName}）`, id)
      toast.success(`告警「${e.ruleName}」已确认`)
    } catch (err) {
      toast.error(errMsg(err, '确认失败'))
    }
  }

  async function resolveAlert(id: string) {
    const e = events.value.find((x) => x.id === id)
    if (!e || e.status === 'RESOLVED') return
    try {
      const next = await api.resolve(id)
      Object.assign(e, mapEvent(next))
      activity.log(auth.user.name, `解决告警「${e.ruleName}」（模型：${e.modelName}）`, id)
      toast.success(`告警「${e.ruleName}」已解决`)
    } catch (err) {
      toast.error(errMsg(err, '解决失败'))
    }
  }

  // ---- 种子（切真：拉取后端概览） ----
  async function seed() {
    if (loaded.value) return
    await load()
  }

  return {
    metrics, rules, events, firingAlerts, kpi,
    ALERT_SEVERITY_LABEL, ALERT_STATUS_LABEL, ALERT_METRIC_LABEL,
    can,
    createRule, updateRule, toggleRule, acknowledgeAlert, resolveAlert,
    seed,
  }
})
