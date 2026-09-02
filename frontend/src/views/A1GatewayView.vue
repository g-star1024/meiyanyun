<script setup lang="ts">
/* A1-18/19/20 API网关与日志 /ai/gateway */
import { ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CKpi from '@/components/CKpi.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CTable from '@/components/CTable.vue'
import CSegmented from '@/components/CSegmented.vue'

const tab = ref('api')
const tabOptions = [
  { label: 'API 目录', value: 'api' },
  { label: '调用日志', value: 'logs' },
  { label: '告警规则', value: 'alerts' },
]
const kpis = [
  { label: '今日调用', icon: 'settings', value: '112,050', tone: 'purple' as const },
  { label: '成功率', icon: 'trend-up', value: '99.7%', tone: 'success' as const },
  { label: 'P99 延迟', icon: 'clock', value: '85ms', tone: 'teal' as const },
  { label: '活跃告警', icon: 'alert', value: '2', tone: 'danger' as const },
]
const apiCols = [
  { key: 'path', label: 'API 路径' }, { key: 'method', label: '方法', width: '80' },
  { key: 'qps', label: '限流QPS', width: '90', align: 'right' as const },
  { key: 'auth', label: '鉴权', width: '100' }, { key: 'calls', label: '今日调用', width: '100', align: 'right' as const },
  { key: 'status', label: '状态', width: '90' },
]
const apis = [
  { id: 1, path: '/api/v1/profile/query', method: 'POST', qps: 100, auth: 'Token', calls: 18640, status: '正常' },
  { id: 2, path: '/api/v1/churn/predict', method: 'POST', qps: 50, auth: 'Token', calls: 8420, status: '正常' },
  { id: 3, path: '/api/v1/scripts/recommend', method: 'POST', qps: 200, auth: 'Token', calls: 21300, status: '正常' },
  { id: 4, path: '/api/v1/chatbot/chat', method: 'POST', qps: 50, auth: 'OAuth', calls: 3420, status: '正常' },
  { id: 5, path: '/api/v1/content/generate', method: 'POST', qps: 20, auth: 'Token', calls: 1980, status: '限流' },
  { id: 6, path: '/api/v1/sensitive/check', method: 'POST', qps: 500, auth: 'Token', calls: 45200, status: '正常' },
  { id: 7, path: '/api/v1/scheduling/suggest', method: 'POST', qps: 10, auth: 'Token', calls: 420, status: '正常' },
  { id: 8, path: '/api/v1/models/list', method: 'GET', qps: 100, auth: 'Token', calls: 670, status: '正常' },
]
const logCols = [
  { key: 'time', label: '时间', width: '150' }, { key: 'api', label: 'API' },
  { key: 'caller', label: '调用方', width: '120' }, { key: 'code', label: '状态码', width: '80' },
  { key: 'latency', label: '延迟', width: '80', align: 'right' as const },
]
const logs = [
  { id: 1, time: '14:20:03', api: '/api/v1/churn/predict', caller: 'M3-10', code: 200, latency: '42ms' },
  { id: 2, time: '14:20:01', api: '/api/v1/scripts/recommend', caller: 'M4-09', code: 200, latency: '128ms' },
  { id: 3, time: '14:19:58', api: '/api/v1/sensitive/check', caller: 'M5-03', code: 200, latency: '18ms' },
  { id: 4, time: '14:19:55', api: '/api/v1/content/generate', caller: 'M5-01', code: 429, latency: '5ms' },
  { id: 5, time: '14:19:50', api: '/api/v1/profile/query', caller: 'M3-02', code: 200, latency: '86ms' },
  { id: 6, time: '14:19:45', api: '/api/v1/chatbot/chat', caller: '小程序', code: 200, latency: '210ms' },
  { id: 7, time: '14:19:40', api: '/api/v1/sensitive/check', caller: 'M4-09', code: 200, latency: '15ms' },
  { id: 8, time: '14:19:35', api: '/api/v1/churn/predict', caller: 'M3-10', code: 500, latency: '3020ms' },
]
const alertCols = [
  { key: 'name', label: '规则名' }, { key: 'metric', label: '指标' },
  { key: 'threshold', label: '阈值' }, { key: 'channel', label: '通知渠道' },
  { key: 'status', label: '状态', width: '90' }, { key: 'ops', label: '操作', width: '100' },
]
const alerts = [
  { id: 1, name: 'P99 延迟告警', metric: 'latency_p99', threshold: '> 200ms', channel: '企微/邮件', status: '启用' },
  { id: 2, name: '错误率告警', metric: 'error_rate', threshold: '> 1%', channel: '企微/短信', status: '启用' },
  { id: 3, name: '调用量突增', metric: 'qps', threshold: '> 500', channel: '企微', status: '启用' },
  { id: 4, name: '敏感词服务不可用', metric: 'availability', threshold: '< 99.9%', channel: '电话/企微', status: '停用' },
]
function codePill(c: number) {
  if (c < 300) return 'success' as const
  if (c < 500) return 'warning' as const
  return 'danger' as const
}
function statusPill(s: string) {
  return s === '正常' || s === '启用' ? 'success' as const : s === '限流' ? 'warning' as const : 'danger' as const
}
</script>

<template>
  <div class="a1-gw">
    <div class="kpis"><CKpi v-for="k in kpis" :key="k.label" v-bind="k" /></div>
    <CCard padding="lg">
      <CSegmented v-model="tab" :options="tabOptions" />
      <div class="mt">
        <CTable v-if="tab === 'api'" :columns="apiCols" :rows="apis" row-key="id" stripe>
          <template #col-method="{ value }"><span class="method" :class="value.toLowerCase()">{{ value }}</span></template>
          <template #col-status="{ value }"><CStatusPill :status="statusPill(value)" dot>{{ value }}</CStatusPill></template>
        </CTable>
        <CTable v-else-if="tab === 'logs'" :columns="logCols" :rows="logs" row-key="id" stripe>
          <template #col-code="{ value }"><CStatusPill :status="codePill(value)">{{ value }}</CStatusPill></template>
        </CTable>
        <template v-else>
          <div class="bar"><span></span><CButton size="sm" variant="primary">新建规则</CButton></div>
          <CTable :columns="alertCols" :rows="alerts" row-key="id" stripe>
            <template #col-status="{ value }"><CStatusPill :status="statusPill(value)">{{ value }}</CStatusPill></template>
            <template #col-ops><CButton size="sm" variant="text">编辑</CButton></template>
          </CTable>
        </template>
      </div>
    </CCard>
    <p class="hint">所有 AI 调用经 T4 网关鉴权，日志写入 T1-04 审计（append-only），告警经 T3-03 推送。</p>
  </div>
</template>

<style scoped>
.a1-gw { display: flex; flex-direction: column; gap: var(--s-lg); }
.kpis { display: flex; gap: var(--s-md); }
.mt { margin-top: var(--s-md); }
.method { font-family: ui-monospace, monospace; font-size: 11px; padding: 2px 6px; border-radius: 3px; font-weight: 600; }
.method.post { background: var(--c-info-bg); color: var(--c-info-fg); }
.bar { display: flex; justify-content: flex-end; margin-bottom: var(--s-sm); }
.hint { font-size: var(--t-xs); color: var(--c-text-3); margin: 0; }
</style>
