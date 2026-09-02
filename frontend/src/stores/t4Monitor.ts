// ============================================================
// T4 AI 中台底座 - 监控告警 store
// 模型实时指标 + 告警规则 + 告警事件（确认/解决闭环）
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

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

export const useT4MonitorStore = defineStore('t4Monitor', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const metrics = ref<ModelMetric[]>([])
  const rules = ref<AlertRule[]>([])
  const events = ref<AlertEvent[]>([])
  const loaded = ref(false)

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

  function can(perm: string) {
    return auth.can(perm)
  }

  // ---- 命令 ----
  function createRule(input: Omit<AlertRule, 'id' | 'createdAt' | 'enabled'> & { enabled?: boolean }): AlertRule {
    if (!auth.can('monitor:rule:create')) throw new Error('无告警规则创建权限')
    const r: AlertRule = {
      id: nextId('rule'),
      ...input,
      enabled: input.enabled ?? true,
      createdAt: new Date().toISOString(),
    }
    rules.value.unshift(r)
    activity.log(auth.user.name, `创建告警规则「${r.name}」（${ALERT_METRIC_LABEL[r.metric]} ${r.operator} ${r.threshold}）`, r.id)
    return r
  }

  function updateRule(id: string, patch: Partial<Omit<AlertRule, 'id' | 'createdAt'>>) {
    if (!auth.can('monitor:rule:edit')) throw new Error('无告警规则编辑权限')
    const r = rules.value.find((x) => x.id === id)
    if (!r) return
    Object.assign(r, patch)
    activity.log(auth.user.name, `更新告警规则「${r.name}」`, id)
  }

  function toggleRule(id: string, enabled: boolean) {
    if (!auth.can('monitor:rule:edit')) throw new Error('无告警规则编辑权限')
    const r = rules.value.find((x) => x.id === id)
    if (!r) return
    r.enabled = enabled
    activity.log(auth.user.name, `告警规则「${r.name}」${enabled ? '启用' : '停用'}`, id)
  }

  function acknowledgeAlert(id: string) {
    const e = events.value.find((x) => x.id === id)
    if (!e || e.status !== 'FIRING') return
    e.status = 'ACKNOWLEDGED'
    e.acknowledgedAt = new Date().toISOString()
    e.acknowledgedBy = auth.user.name
    activity.log(auth.user.name, `确认告警「${e.ruleName}」（模型：${e.modelName}）`, id)
  }

  function resolveAlert(id: string) {
    const e = events.value.find((x) => x.id === id)
    if (!e || e.status === 'RESOLVED') return
    e.status = 'RESOLVED'
    e.resolvedAt = new Date().toISOString()
    if (!e.acknowledgedAt) {
      e.acknowledgedAt = e.resolvedAt
      e.acknowledgedBy = auth.user.name
    }
    activity.log(auth.user.name, `解决告警「${e.ruleName}」（模型：${e.modelName}）`, id)
  }

  // ---- 种子 ----
  function seed() {
    if (loaded.value) return
    loaded.value = true
    const now = Date.now()
    const minsAgo = (m: number) => new Date(now - m * 60_000).toISOString()

    metrics.value = [
      { modelId: 'mdl-churn', modelName: '客户流失预测 v3', timestamp: minsAgo(1), qps: 142, latencyP99: 58, errorRate: 0.18, driftScore: 0.21, accuracy: 0.91 },
      { modelId: 'mdl-skin', modelName: '皮肤影像分类', timestamp: minsAgo(1), qps: 28, latencyP99: 218, errorRate: 0.62, driftScore: 0.78, accuracy: 0.86 },
      { modelId: 'mdl-sales', modelName: '门店销量预测', timestamp: minsAgo(2), qps: 12, latencyP99: 92, errorRate: 0.05, driftScore: 0.12, accuracy: 0.92 },
      { modelId: 'mdl-rec', modelName: '项目疗程推荐', timestamp: minsAgo(1), qps: 86, latencyP99: 76, errorRate: 0.02, driftScore: 0.08, accuracy: 0.88 },
      { modelId: 'mdl-nlp', modelName: '客服意图识别(待发布)', timestamp: minsAgo(3), qps: 0, latencyP99: 0, errorRate: 0, driftScore: 0, accuracy: 0.93 },
    ]

    rules.value = [
      { id: nextId('rule'), name: '皮肤影像-漂移过高', modelId: 'mdl-skin', modelName: '皮肤影像分类', metric: 'DRIFT', threshold: 0.7, operator: '>', severity: 'CRITICAL', enabled: true, notifyChannels: ['企微', '邮件'], createdAt: minsAgo(60 * 24 * 10) },
      { id: nextId('rule'), name: '流失预测-P99 延迟', modelId: 'mdl-churn', modelName: '客户流失预测 v3', metric: 'LATENCY', threshold: 200, operator: '>', severity: 'WARNING', enabled: true, notifyChannels: ['企微'], createdAt: minsAgo(60 * 24 * 8) },
      { id: nextId('rule'), name: '皮肤影像-错误率', modelId: 'mdl-skin', modelName: '皮肤影像分类', metric: 'ERROR_RATE', threshold: 5, operator: '>=', severity: 'WARNING', enabled: true, notifyChannels: ['企微', '邮件', '短信'], createdAt: minsAgo(60 * 24 * 8) },
      { id: nextId('rule'), name: '销量预测-准确率下跌', modelId: 'mdl-sales', modelName: '门店销量预测', metric: 'ACCURACY', threshold: 0.8, operator: '<', severity: 'WARNING', enabled: true, notifyChannels: ['邮件'], createdAt: minsAgo(60 * 24 * 6) },
      { id: nextId('rule'), name: '推荐服务-QPS 下跌', modelId: 'mdl-rec', modelName: '项目疗程推荐', metric: 'QPS_DROP', threshold: 30, operator: '>', severity: 'INFO', enabled: false, notifyChannels: ['企微'], createdAt: minsAgo(60 * 24 * 3) },
      { id: nextId('rule'), name: '流失预测-漂移', modelId: 'mdl-churn', modelName: '客户流失预测 v3', metric: 'DRIFT', threshold: 0.6, operator: '>', severity: 'INFO', enabled: true, notifyChannels: ['企微'], createdAt: minsAgo(60 * 24 * 2) },
    ]

    events.value = [
      {
        id: nextId('evt'), ruleId: rules.value[0].id, ruleName: rules.value[0].name,
        modelId: 'mdl-skin', modelName: '皮肤影像分类',
        severity: 'CRITICAL', status: 'FIRING',
        message: '漂移分数 0.78 超过阈值 0.70，建议立即复核模型效果或触发重训',
        value: 0.78, threshold: 0.7, triggeredAt: minsAgo(18),
      },
      {
        id: nextId('evt'), ruleId: rules.value[2].id, ruleName: rules.value[2].name,
        modelId: 'mdl-skin', modelName: '皮肤影像分类',
        severity: 'WARNING', status: 'FIRING',
        message: '近 5 分钟错误率 5.6% ≥ 阈值 5%，疑似推理服务异常',
        value: 5.6, threshold: 5, triggeredAt: minsAgo(26),
      },
      {
        id: nextId('evt'), ruleId: rules.value[1].id, ruleName: rules.value[1].name,
        modelId: 'mdl-churn', modelName: '客户流失预测 v3',
        severity: 'WARNING', status: 'ACKNOWLEDGED',
        message: 'P99 延迟 218ms 超过阈值 200ms，已通知值班同学',
        value: 218, threshold: 200, triggeredAt: minsAgo(120), acknowledgedAt: minsAgo(115), acknowledgedBy: '王运维',
      },
      {
        id: nextId('evt'), ruleId: rules.value[3].id, ruleName: rules.value[3].name,
        modelId: 'mdl-sales', modelName: '门店销量预测',
        severity: 'WARNING', status: 'RESOLVED',
        message: '准确率跌至 0.78，低于阈值 0.80',
        value: 0.78, threshold: 0.8, triggeredAt: minsAgo(60 * 8), acknowledgedAt: minsAgo(60 * 8 - 3), resolvedAt: minsAgo(60 * 6), acknowledgedBy: '赵磊',
      },
      {
        id: nextId('evt'), ruleId: rules.value[0].id, ruleName: rules.value[0].name,
        modelId: 'mdl-skin', modelName: '皮肤影像分类',
        severity: 'CRITICAL', status: 'RESOLVED',
        message: '历史漂移告警（0.72）已随 v1.4 重训解决',
        value: 0.72, threshold: 0.7, triggeredAt: minsAgo(60 * 24 * 2), acknowledgedAt: minsAgo(60 * 24 * 2 - 5), resolvedAt: minsAgo(60 * 24 * 1), acknowledgedBy: '张医生',
      },
    ]
  }

  return {
    metrics, rules, events, firingAlerts, kpi,
    ALERT_SEVERITY_LABEL, ALERT_STATUS_LABEL, ALERT_METRIC_LABEL,
    can,
    createRule, updateRule, toggleRule, acknowledgeAlert, resolveAlert,
    seed,
  }
})
