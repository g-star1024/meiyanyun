<script setup lang="ts">
/* A1-14/15/16 审批与效果评估 /ai/govern — 红线：AI动作受控，模型发布走审批
   B42 接真：待审批/本月审批 KPI ← /ai/logs/kpi；审批列表与决策 ← /ai/approvals
   （权限：查看 aiGovern:view，决策 aiGovern:approve）。
   效果评估 / A-B 实验为后续批次能力，本页保留示意结构。 */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CKpi from '@/components/CKpi.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CTable from '@/components/CTable.vue'
import CSegmented from '@/components/CSegmented.vue'
import CSelect from '@/components/CSelect.vue'
import CPagination from '@/components/CPagination.vue'
import { useAuthStore } from '@/stores/auth'
import { useToast } from '@/composables/useToast'
import { errMsg } from '@/stores/m5Coupon'
import { logKpi, listApprovals, decideApproval, type AiKpi, type ApprovalView } from '@/api/ai'

const auth = useAuthStore()
const toast = useToast()
const canDecide = computed(() => auth.can('aiGovern:approve'))

const tab = ref('approval')
const tabOptions = [
  { label: '审批待办', value: 'approval' },
  { label: '效果评估', value: 'effect' },
  { label: 'A/B 实验', value: 'ab' },
]

const kpis = ref<ReturnType<typeof toKpis> | null>(null)
function toKpis(k: AiKpi) {
  return [
    { label: '待审批', icon: 'check-square', value: String(k.pendingApprovals), tone: 'warning' as const },
    { label: '本月审批', icon: 'check-square', value: String(k.monthApproved), tone: 'brand' as const },
    { label: '平均效果提升', icon: 'trend-up', value: '—', tone: 'success' as const },
    { label: '运行中实验', icon: 'dashboard', value: '—', tone: 'purple' as const },
  ]
}

const approvalCols = [
  { key: 'approvalType', label: 'AI动作', width: '110' }, { key: 'content', label: '申请内容' },
  { key: 'applicant', label: '申请人', width: '100' }, { key: 'appliedAt', label: '申请时间', width: '150' },
  { key: 'status', label: '状态', width: '100' }, { key: 'ops', label: '操作', width: '150' },
]
const approvals = ref<ApprovalView[]>([])
const loading = ref(false)
const deciding = ref<Record<number, boolean>>({})
const page = ref(1)
const pageSize = 20
const total = ref(0)
const statusFilter = ref('PENDING')
const statusOptions = [
  { label: '待审批', value: 'PENDING' },
  { label: '已通过', value: 'APPROVED' },
  { label: '已驳回', value: 'REJECTED' },
  { label: '全部', value: '' },
]

const effectCols = [
  { key: 'cap', label: 'AI能力' }, { key: 'exposure', label: '曝光量', align: 'right' as const },
  { key: 'convert', label: '转化量', align: 'right' as const },
  { key: 'rate', label: '转化率', align: 'right' as const },
  { key: 'roi', label: 'ROI', align: 'right' as const },
  { key: 'confidence', label: '置信度', align: 'right' as const },
]
const effects = [
  { id: 1, cap: '智能话术-升单', exposure: '—', convert: '—', rate: '—', roi: '—', confidence: '—' },
  { id: 2, cap: '复购预测-时机推荐', exposure: '—', convert: '—', rate: '—', roi: '—', confidence: '—' },
  { id: 3, cap: '流失预警-干预', exposure: '—', convert: '—', rate: '—', roi: '—', confidence: '—' },
  { id: 4, cap: '内容生成-营销文案', exposure: '—', convert: '—', rate: '—', roi: '—', confidence: '—' },
]

const abCols = [
  { key: 'name', label: '实验名' }, { key: 'control', label: '对照组转化率', align: 'right' as const },
  { key: 'exp', label: '实验组转化率', align: 'right' as const },
  { key: 'lift', label: '提升', align: 'right' as const },
  { key: 'status', label: '状态', width: '100' },
]
const abs: Array<{ id: number; name: string; control: string; exp: string; lift: string; status: string }> = []

const TYPE_NAMES: Record<string, string> = {
  PROVIDER: '供应商接入',
  MODEL: '模型发布',
  BINDING: '功能绑定',
}

async function loadKpi() {
  try {
    kpis.value = toKpis(await logKpi())
  } catch (e) {
    toast.error('审批指标加载失败：' + errMsg(e))
  }
}
async function loadApprovals() {
  loading.value = true
  try {
    const r = await listApprovals({ status: statusFilter.value, page: page.value - 1, size: pageSize })
    approvals.value = r.content
    total.value = r.totalElements
  } catch (e) {
    toast.error('审批列表加载失败：' + errMsg(e))
  } finally {
    loading.value = false
  }
}
function changeFilter() {
  page.value = 1
  loadApprovals()
}
function changePage(n: number) {
  page.value = n
  loadApprovals()
}
async function decide(row: ApprovalView, approved: boolean) {
  let opinion = ''
  if (approved) {
    if (!window.confirm(`确认通过「${TYPE_NAMES[row.approvalType] || row.approvalType}：${row.content}」？通过后将联动启用对应模型/绑定。`)) return
  } else {
    const v = window.prompt('请输入驳回意见（将通知申请人并写入审计日志）：')
    if (v === null) return
    opinion = v.trim()
    if (!opinion) {
      toast.warning('驳回意见不能为空')
      return
    }
  }
  deciding.value[row.approvalId] = true
  try {
    await decideApproval(row.approvalId, { approved, opinion })
    toast.success(approved ? '已通过，联动配置已生效并记录审计' : '已驳回，意见已记录审计')
    await Promise.all([loadApprovals(), loadKpi()])
  } catch (e) {
    toast.error('审批决策失败：' + errMsg(e))
  } finally {
    deciding.value[row.approvalId] = false
  }
}

function typeName(t: string) {
  return TYPE_NAMES[t] || t
}
function fmtTime(s: string | null) {
  if (!s) return '—'
  return s.replace('T', ' ').slice(5, 16)
}
function pill(s: string) {
  if (s === 'PENDING') return 'warning' as const
  if (s === 'APPROVED') return 'success' as const
  return 'danger' as const
}
function statusLabel(s: string) {
  if (s === 'PENDING') return '待审批'
  if (s === 'APPROVED') return '已通过'
  if (s === 'REJECTED') return '已驳回'
  return s
}

onMounted(() => {
  loadKpi()
  loadApprovals()
})
</script>

<template>
  <div class="a1-gov">
    <div class="kpis"><CKpi v-for="k in (kpis ?? [])" :key="k.label" v-bind="k" /></div>
    <CCard padding="lg">
      <CSegmented v-model="tab" :options="tabOptions" />

      <div v-if="tab === 'approval'" class="mt">
        <div class="appr-bar">
          <CSelect v-model="statusFilter" :options="statusOptions" width="140px" @update:model-value="changeFilter" />
          <CButton size="sm" variant="secondary" :disabled="loading" @click="loadApprovals">刷新</CButton>
        </div>
        <CTable :columns="approvalCols" :rows="approvals" row-key="approvalId" stripe
          :empty-text="loading ? '加载中…' : '当前状态下没有审批单'">
          <template #col-approvalType="{ value }">
            <CStatusPill status="info">{{ typeName(value) }}</CStatusPill>
          </template>
          <template #col-content="{ row }">
            <div class="content">
              <span>{{ row.content }}</span>
              <span v-if="row.opinion" class="content__opinion">审批意见：{{ row.opinion }}（{{ row.decidedBy || '—' }}）</span>
            </div>
          </template>
          <template #col-applicant="{ value }">{{ value || '系统' }}</template>
          <template #col-appliedAt="{ value }">{{ fmtTime(value) }}</template>
          <template #col-status="{ value }"><CStatusPill :status="pill(value)" dot>{{ statusLabel(value) }}</CStatusPill></template>
          <template #col-ops="{ row }">
            <template v-if="row.status === 'PENDING' && canDecide">
              <CButton size="sm" variant="primary" :disabled="deciding[row.approvalId]" @click="decide(row as ApprovalView, true)">通过</CButton>
              <CButton size="sm" variant="danger" :disabled="deciding[row.approvalId]" @click="decide(row as ApprovalView, false)">驳回</CButton>
            </template>
            <span v-else class="muted">—</span>
          </template>
        </CTable>
        <CPagination :page="page" :page-size="pageSize" :total="total" @update:page="changePage" />
      </div>

      <div v-else-if="tab === 'effect'" class="mt">
        <CTable :columns="effectCols" :rows="effects" row-key="id" stripe empty-text="效果评估为后续批次能力，AI 调用量积累后自动出数" />
      </div>

      <div v-else class="mt">
        <CTable :columns="abCols" :rows="abs" row-key="id" stripe empty-text="A/B 实验为后续批次能力，模型绑定多版本后开放" />
      </div>
    </CCard>

    <div class="redline">
      <span class="redline__title">合规红线</span>
      <span class="redline__text">所有 AI 动作必须经审批，模型/供应商上线禁止自动生效；审批通过后由系统联动启用并全量记录审计，灰度发布须配置回滚阈值。</span>
    </div>
  </div>
</template>

<style scoped>
.a1-gov { display: flex; flex-direction: column; gap: var(--s-lg); }
.kpis { display: flex; gap: var(--s-md); }
.mt { margin-top: var(--s-md); }
.muted { color: var(--c-text-3); }
.appr-bar { display: flex; justify-content: flex-end; gap: var(--s-sm); margin-bottom: var(--s-sm); }
.content { display: flex; flex-direction: column; gap: 2px; }
.content__opinion { font-size: var(--t-xs); color: var(--c-text-3); }
.redline { margin-top: var(--s-md); padding: var(--s-sm) var(--s-md); background: var(--c-danger-bg); border-radius: var(--r-md); display: flex; align-items: center; gap: var(--s-sm); }
.redline__title { font-size: var(--t-xs); font-weight: 600; color: var(--c-danger-fg); flex-shrink: 0; }
.redline__text { font-size: 11px; color: var(--c-danger-fg); line-height: 1.4; }
</style>
