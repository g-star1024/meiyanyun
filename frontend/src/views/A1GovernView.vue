<script setup lang="ts">
/* A1-14/15/16 审批与效果评估 /ai/govern — 红线：AI动作受控，模型发布走T3-01 */
import { ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CKpi from '@/components/CKpi.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CTable from '@/components/CTable.vue'
import CSegmented from '@/components/CSegmented.vue'

const tab = ref('approval')
const tabOptions = [
  { label: '审批待办', value: 'approval' },
  { label: '效果评估', value: 'effect' },
  { label: 'A/B 实验', value: 'ab' },
]
const kpis = [
  { label: '待审批', icon: 'check-square', value: '5', tone: 'warning' as const },
  { label: '本月审批', icon: 'check-square', value: '28', tone: 'brand' as const },
  { label: '平均效果提升', icon: 'trend-up', value: '12.3%', tone: 'success' as const },
  { label: '运行中实验', icon: 'dashboard', value: '3', tone: 'purple' as const },
]

const approvalCols = [
  { key: 'type', label: 'AI动作', width: '120' }, { key: 'content', label: '申请内容' },
  { key: 'applicant', label: '申请人', width: '90' }, { key: 'time', label: '申请时间', width: '140' },
  { key: 'status', label: '状态', width: '100' }, { key: 'ops', label: '操作', width: '180' },
]
const approvals = ref([
  { id: 1, type: '模型发布', content: 'churn-v2.3 流失预警模型发布', applicant: '李明', time: '08-26 10:15', status: 'pending', t3link: true },
  { id: 2, type: '话术上线', content: 'S-032 异议处理话术（价格贵）', applicant: '陈晓', time: '08-26 09:30', status: 'pending', t3link: false },
  { id: 3, type: '灰度发布', content: '推荐模型 v2.1 灰度 20%', applicant: '王悦', time: '08-25 16:00', status: 'pending', t3link: true },
  { id: 4, type: '模型发布', content: 'repurchase-v1.8 复购预测发布', applicant: '赵磊', time: '08-25 14:20', status: 'approved', t3link: true },
  { id: 5, type: '知识库更新', content: '水光针知识库 v3 导入', applicant: '张医生', time: '08-24 11:00', status: 'approved', t3link: false },
  { id: 6, type: '话术下线', content: 'S-018 旧版破冰话术下线', applicant: '陈晓', time: '08-23 15:30', status: 'rejected', t3link: false },
])

const effectCols = [
  { key: 'cap', label: 'AI能力' }, { key: 'exposure', label: '曝光量', align: 'right' as const },
  { key: 'convert', label: '转化量', align: 'right' as const },
  { key: 'rate', label: '转化率', align: 'right' as const },
  { key: 'roi', label: 'ROI', align: 'right' as const },
  { key: 'confidence', label: '置信度', align: 'right' as const },
]
const effects = [
  { id: 1, cap: '智能话术-升单', exposure: 12800, convert: 1842, rate: '14.4%', roi: '3.2x', confidence: '94%' },
  { id: 2, cap: '复购预测-时机推荐', exposure: 8400, convert: 1260, rate: '15.0%', roi: '4.1x', confidence: '91%' },
  { id: 3, cap: '流失预警-干预', exposure: 2100, convert: 378, rate: '18.0%', roi: '5.6x', confidence: '88%' },
  { id: 4, cap: '内容生成-营销文案', exposure: 4600, convert: 414, rate: '9.0%', roi: '2.1x', confidence: '85%' },
  { id: 5, cap: 'AI客服-意图路由', exposure: 3280, convert: 0, rate: '—', roi: '成本节省42%', confidence: '96%' },
]

const abCols = [
  { key: 'name', label: '实验名' }, { key: 'control', label: '对照组转化率', align: 'right' as const },
  { key: 'exp', label: '实验组转化率', align: 'right' as const },
  { key: 'lift', label: '提升', align: 'right' as const },
  { key: 'status', label: '状态', width: '100' },
]
const abs = [
  { id: 1, name: '话术S-032 A/B（破冰）', control: '10.2%', exp: '12.8%', lift: '+25.5%', status: '运行中' },
  { id: 2, name: '推荐模型 v2 vs v1', control: '8.4%', exp: '11.1%', lift: '+32.1%', status: '已结束' },
  { id: 3, name: '客服转人工阈值 0.7 vs 0.8', control: '68%', exp: '72%', lift: '+5.9%', status: '运行中' },
]

function pill(s: string) {
  if (s === 'pending' || s === '运行中') return 'warning' as const
  if (s === 'approved' || s === '已结束') return 'success' as const
  return 'danger' as const
}
function label(s: string) {
  if (s === 'pending') return '待审批'
  if (s === 'approved') return '已通过'
  if (s === 'rejected') return '已驳回'
  return s
}
function approve(row: Record<string, any>) { row.status = 'approved'; window.alert('审批通过，操作已记录审计日志。') }
function reject(row: Record<string, any>) { row.status = 'rejected'; window.alert('已驳回。') }
function goT3() { window.location.hash = '#/approval' }
</script>

<template>
  <div class="a1-gov">
    <div class="kpis"><CKpi v-for="k in kpis" :key="k.label" v-bind="k" /></div>
    <CCard padding="lg">
      <CSegmented v-model="tab" :options="tabOptions" />

      <div v-if="tab === 'approval'" class="mt">
        <CTable :columns="approvalCols" :rows="approvals" row-key="id" stripe>
          <template #col-status="{ value }"><CStatusPill :status="pill(value)" dot>{{ label(value) }}</CStatusPill></template>
          <template #col-ops="{ row }">
            <template v-if="row.status === 'pending'">
              <CButton size="sm" variant="primary" @click="approve(row)">通过</CButton>
              <CButton size="sm" variant="secondary" @click="reject(row)">驳回</CButton>
              <CButton v-if="row.t3link" size="sm" variant="text" @click="goT3">T3-01审批</CButton>
            </template>
            <span v-else class="muted">—</span>
          </template>
        </CTable>
      </div>

      <div v-else-if="tab === 'effect'" class="mt">
        <CTable :columns="effectCols" :rows="effects" row-key="id" stripe />
      </div>

      <div v-else class="mt">
        <CTable :columns="abCols" :rows="abs" row-key="id" stripe>
          <template #col-status="{ value }"><CStatusPill :status="pill(value)" dot>{{ value }}</CStatusPill></template>
          <template #col-lift="{ value }"><span class="lift">{{ value }}</span></template>
        </CTable>
      </div>
    </CCard>

    <div class="redline">
      <span class="redline__title">合规红线</span>
      <span class="redline__text">所有 AI 动作必须经审批，模型发布走 T3-01 审批流，禁止自动上线；灰度发布须配置回滚阈值。</span>
    </div>
  </div>
</template>

<style scoped>
.a1-gov { display: flex; flex-direction: column; gap: var(--s-lg); }
.kpis { display: flex; gap: var(--s-md); }
.mt { margin-top: var(--s-md); }
.muted { color: var(--c-text-3); }
.lift { color: var(--c-success-fg); font-weight: 600; }
.redline { margin-top: var(--s-md); padding: var(--s-sm) var(--s-md); background: var(--c-danger-bg); border-radius: var(--r-md); display: flex; align-items: center; gap: var(--s-sm); }
.redline__title { font-size: var(--t-xs); font-weight: 600; color: var(--c-danger-fg); flex-shrink: 0; }
.redline__text { font-size: 11px; color: var(--c-danger-fg); line-height: 1.4; }
</style>
