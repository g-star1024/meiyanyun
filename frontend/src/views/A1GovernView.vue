<script setup lang="ts">
/* A1-14/15/16 审批与效果评估 /ai/govern — 红线：AI动作受控，模型发布走审批
   B42 接真：待审批/本月审批 KPI ← /ai/logs/kpi；审批列表与决策 ← /ai/approvals
   B45 接真：效果评估 ← /ai/evals（ai_invoke_log 技术指标聚合快照）；
   A/B 实验 ← /ai/experiments（对照/实验双侧快照 + lift pp）；KPI ← /ai/eval-stats。
   （权限：查看 aiGovern:view，审批决策 aiGovern:approve，评估/实验写操作 aiAdmin:edit）。 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CKpi from '@/components/CKpi.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CTable from '@/components/CTable.vue'
import CSegmented from '@/components/CSegmented.vue'
import CSelect from '@/components/CSelect.vue'
import CInput from '@/components/CInput.vue'
import CDrawer from '@/components/CDrawer.vue'
import CPagination from '@/components/CPagination.vue'
import { useAuthStore } from '@/stores/auth'
import { useToast } from '@/composables/useToast'
import { errMsg } from '@/stores/m5Coupon'
import {
  logKpi, listApprovals, decideApproval, listFeatures, listModels,
  listEvals, createEval, refreshEval, concludeEval,
  listExperiments, createExperiment, refreshExperiment, concludeExperiment,
  getEvalStats,
  type AiKpi, type ApprovalView, type BindingView, type ModelView,
  type EvalView, type ExperimentView, type EvalMetrics,
} from '@/api/ai'
import { fmtDateTime } from '@/utils/datetime'

const auth = useAuthStore()
const toast = useToast()
const canDecide = computed(() => auth.can('aiGovern:approve'))
const canEdit = computed(() => auth.can('aiAdmin:edit'))

const tab = ref('approval')
const tabOptions = [
  { label: '审批待办', value: 'approval' },
  { label: '效果评估', value: 'effect' },
  { label: 'A/B 实验', value: 'ab' },
]

const evalStats = ref<{ runningExperiments: number; avgLiftPp: number | '' } | null>(null)
const baseKpi = ref<AiKpi | null>(null)
const kpis = computed(() => (baseKpi.value ? toKpis(baseKpi.value) : null))
function toKpis(k: AiKpi) {
  return [
    { label: '待审批', icon: 'check-square', value: String(k.pendingApprovals), tone: 'warning' as const },
    { label: '本月审批', icon: 'check-square', value: String(k.monthApproved), tone: 'brand' as const },
    {
      label: '平均效果提升', icon: 'trend-up',
      value: evalStats.value && evalStats.value.avgLiftPp !== '' ? `${evalStats.value.avgLiftPp} pp` : '—',
      tone: 'success' as const,
    },
    {
      label: '运行中实验', icon: 'dashboard',
      value: String(evalStats.value?.runningExperiments ?? '—'), tone: 'purple' as const,
    },
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

// -------------------- 效果评估 --------------------

const evals = ref<EvalView[]>([])
const evalLoading = ref(false)
const evalBusy = ref<Record<number, boolean>>({})
const evalCols = [
  { key: 'taskName', label: '评估任务', width: '170' },
  { key: 'scope', label: '评估维度', width: '90' },
  { key: 'targetName', label: '评估目标', width: '180' },
  { key: 'window', label: '统计窗口', width: '150' },
  { key: 'calls', label: '调用量', width: '80', align: 'right' as const },
  { key: 'successRate', label: '成功率', width: '80', align: 'right' as const },
  { key: 'p99', label: 'P99 时延', width: '100', align: 'right' as const },
  { key: 'tokens', label: 'Token 消耗', width: '110', align: 'right' as const },
  { key: 'costFen', label: '成本（元）', width: '100', align: 'right' as const },
  { key: 'status', label: '状态', width: '90', align: 'center' as const },
  { key: 'conclusion', label: '评估结论' },
  { key: 'ops', label: '操作', width: '150', align: 'right' as const },
]
const evalRows = computed(() =>
  evals.value.map((e) => ({
    ...e,
    scope: e.evalScope === 'FEATURE' ? 'AI 功能' : '模型',
    window: `近 ${e.windowDays} 天`,
    calls: e.metrics.calls.toLocaleString(),
    successRate: `${Number(e.metrics.successRate).toFixed(1)}%`,
    p99: e.metrics.p99LatencyMs == null ? '—' : `${(e.metrics.p99LatencyMs / 1000).toFixed(2)}s`,
    tokens: e.metrics.tokens.toLocaleString(),
    costFen: fmtYuan(e.metrics.costFen),
  })),
)
async function loadEvals() {
  evalLoading.value = true
  try {
    evals.value = await listEvals()
  } catch (e) {
    toast.error('效果评估列表加载失败：' + errMsg(e))
  } finally {
    evalLoading.value = false
  }
}
async function refreshEvalRow(row: EvalView) {
  evalBusy.value[row.taskId] = true
  try {
    await refreshEval(row.taskId)
    toast.success('指标快照已按当前窗口重新聚合')
    await loadEvals()
    await loadEvalStats()
  } catch (e) {
    toast.error('刷新失败：' + errMsg(e))
  } finally {
    evalBusy.value[row.taskId] = false
  }
}
async function concludeEvalRow(row: EvalView) {
  const v = window.prompt('请输入评估结论（结案后任务冻结，不可再刷新）：', row.conclusion ?? '')
  if (v === null) return
  const conclusion = v.trim()
  if (!conclusion) {
    toast.warning('评估结论不能为空')
    return
  }
  evalBusy.value[row.taskId] = true
  try {
    await concludeEval(row.taskId, conclusion)
    toast.success('评估任务已结案')
    await loadEvals()
  } catch (e) {
    toast.error('结案失败：' + errMsg(e))
  } finally {
    evalBusy.value[row.taskId] = false
  }
}

// 新建评估任务抽屉
const features = ref<BindingView[]>([])
const models = ref<ModelView[]>([])
const evalDrawer = ref(false)
const evalSaving = ref(false)
const evalForm = reactive({ taskName: '', evalScope: 'FEATURE', targetCode: '', windowDays: '7' })
const evalScopeOptions = [
  { label: '按 AI 功能', value: 'FEATURE' },
  { label: '按模型', value: 'MODEL' },
]
const evalTargetOptions = computed(() =>
  evalForm.evalScope === 'FEATURE'
    ? features.value.map((f) => ({ label: `${f.featureName}（${f.featureCode}）`, value: f.featureCode }))
    : models.value.map((m) => ({ label: `${m.displayName}（${m.modelCode}）`, value: m.modelCode })),
)
function openEvalCreate() {
  Object.assign(evalForm, {
    taskName: '', evalScope: 'FEATURE',
    targetCode: evalTargetOptions.value[0]?.value ?? '', windowDays: '7',
  })
  evalDrawer.value = true
}
async function saveEval() {
  const taskName = evalForm.taskName.trim()
  if (!taskName) {
    toast.warning('请填写任务名称')
    return
  }
  if (!evalForm.targetCode) {
    toast.warning('请选择评估目标')
    return
  }
  const days = Number(evalForm.windowDays)
  if (!Number.isInteger(days) || days < 1 || days > 90) {
    toast.warning('统计窗口须为 1~90 的整数天')
    return
  }
  evalSaving.value = true
  try {
    await createEval({ taskName, evalScope: evalForm.evalScope, targetCode: evalForm.targetCode, windowDays: days })
    toast.success('评估任务已创建，指标快照已聚合落库')
    evalDrawer.value = false
    await loadEvals()
    await loadEvalStats()
  } catch (e) {
    toast.error('评估任务创建失败：' + errMsg(e))
  } finally {
    evalSaving.value = false
  }
}

// -------------------- A/B 实验 --------------------

const experiments = ref<ExperimentView[]>([])
const expLoading = ref(false)
const expBusy = ref<Record<number, boolean>>({})
const abCols = [
  { key: 'experimentName', label: '实验名', width: '170' },
  { key: 'controlModel', label: '对照模型', width: '180' },
  { key: 'experimentModel', label: '实验模型', width: '180' },
  { key: 'window', label: '统计窗口', width: '120' },
  { key: 'controlRate', label: '对照成功率', width: '100', align: 'right' as const },
  { key: 'expRate', label: '实验成功率', width: '100', align: 'right' as const },
  { key: 'lift', label: '效果提升', width: '100', align: 'right' as const },
  { key: 'status', label: '状态', width: '90', align: 'center' as const },
  { key: 'conclusion', label: '实验结论' },
  { key: 'ops', label: '操作', width: '150', align: 'right' as const },
]
function rateOf(m: EvalMetrics | null | undefined) {
  if (!m) return '—'
  return `${Number(m.successRate).toFixed(1)}%`
}
const abRows = computed(() =>
  experiments.value.map((x) => ({
    ...x,
    window: `近 ${x.windowDays} 天`,
    controlRate: rateOf(x.controlMetrics),
    expRate: rateOf(x.experimentMetrics),
    lift: x.liftPp,
  })),
)
async function loadExperiments() {
  expLoading.value = true
  try {
    experiments.value = await listExperiments()
  } catch (e) {
    toast.error('A/B 实验列表加载失败：' + errMsg(e))
  } finally {
    expLoading.value = false
  }
}
async function refreshExpRow(row: ExperimentView) {
  expBusy.value[row.experimentId] = true
  try {
    await refreshExperiment(row.experimentId)
    toast.success('双侧指标快照已按当前窗口重新聚合')
    await loadExperiments()
    await loadEvalStats()
  } catch (e) {
    toast.error('刷新失败：' + errMsg(e))
  } finally {
    expBusy.value[row.experimentId] = false
  }
}
async function concludeExpRow(row: ExperimentView) {
  const v = window.prompt('请输入实验结论（结案后实验冻结，不可再刷新）：', row.conclusion ?? '')
  if (v === null) return
  const conclusion = v.trim()
  if (!conclusion) {
    toast.warning('实验结论不能为空')
    return
  }
  expBusy.value[row.experimentId] = true
  try {
    await concludeExperiment(row.experimentId, conclusion)
    toast.success('实验已结案')
    await loadExperiments()
    await loadEvalStats()
  } catch (e) {
    toast.error('结案失败：' + errMsg(e))
  } finally {
    expBusy.value[row.experimentId] = false
  }
}

// 新建实验抽屉
const expDrawer = ref(false)
const expSaving = ref(false)
const expForm = reactive({ experimentName: '', controlModel: '', experimentModel: '', windowDays: '7' })
const expModelOptions = computed(() =>
  models.value.map((m) => ({ label: `${m.displayName}（${m.modelCode}）`, value: m.modelCode })),
)
function openExpCreate() {
  if (models.value.length < 2) {
    toast.warning('至少需要接入 2 个模型才能配置对照/实验分组')
    return
  }
  Object.assign(expForm, {
    experimentName: '',
    controlModel: expModelOptions.value[0]?.value ?? '',
    experimentModel: expModelOptions.value[1]?.value ?? '',
    windowDays: '7',
  })
  expDrawer.value = true
}
async function saveExperiment() {
  const experimentName = expForm.experimentName.trim()
  if (!experimentName) {
    toast.warning('请填写实验名称')
    return
  }
  if (!expForm.controlModel || !expForm.experimentModel) {
    toast.warning('请选择对照组与实验组模型')
    return
  }
  if (expForm.controlModel === expForm.experimentModel) {
    toast.warning('对照组与实验组必须是不同模型')
    return
  }
  const days = Number(expForm.windowDays)
  if (!Number.isInteger(days) || days < 1 || days > 90) {
    toast.warning('统计窗口须为 1~90 的整数天')
    return
  }
  expSaving.value = true
  try {
    await createExperiment({
      experimentName,
      controlModel: expForm.controlModel,
      experimentModel: expForm.experimentModel,
      windowDays: days,
    })
    toast.success('A/B 实验已创建，双侧指标快照已聚合落库')
    expDrawer.value = false
    await loadExperiments()
    await loadEvalStats()
  } catch (e) {
    toast.error('A/B 实验创建失败：' + errMsg(e))
  } finally {
    expSaving.value = false
  }
}

// -------------------- KPI 汇总 --------------------

async function loadEvalStats() {
  try {
    evalStats.value = await getEvalStats()
  } catch (e) {
    toast.error('效果指标加载失败：' + errMsg(e))
  }
}

const TYPE_NAMES: Record<string, string> = {
  PROVIDER: '供应商接入',
  MODEL: '模型发布',
  BINDING: '功能绑定',
}

async function loadKpi() {
  try {
    const k = await logKpi()
    baseKpi.value = k
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
    await Promise.all([loadApprovals(), loadKpi(), loadFeaturesAndModels()])
  } catch (e) {
    toast.error('审批决策失败：' + errMsg(e))
  } finally {
    deciding.value[row.approvalId] = false
  }
}

async function loadFeaturesAndModels() {
  const [f, m] = await Promise.allSettled([listFeatures(), listModels()])
  if (f.status === 'fulfilled') features.value = f.value
  if (m.status === 'fulfilled') models.value = m.value
}

function typeName(t: string) {
  return TYPE_NAMES[t] || t
}
function fmtTime(s: string | null) {
  return fmtDateTime(s)
}
function fmtYuan(fen: number) {
  return (fen / 100).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function pill(s: string) {
  if (s === 'PENDING' || s === 'RUNNING') return 'warning' as const
  if (s === 'APPROVED' || s === 'DONE' || s === 'FINISHED') return 'success' as const
  return 'danger' as const
}
function statusLabel(s: string) {
  if (s === 'PENDING') return '待审批'
  if (s === 'APPROVED') return '已通过'
  if (s === 'REJECTED') return '已驳回'
  if (s === 'RUNNING') return '运行中'
  if (s === 'DONE') return '已结案'
  if (s === 'FINISHED') return '已结案'
  return s
}
function liftClass(v: number | null) {
  if (v == null) return ''
  return v >= 0 ? 'lift-up' : 'lift-down'
}
function liftText(v: number | null) {
  if (v == null) return '—'
  return `${v > 0 ? '+' : ''}${v} pp`
}

onMounted(async () => {
  await Promise.all([loadEvalStats(), loadKpi(), loadFeaturesAndModels(), loadApprovals()])
})

// 效果评估 / A·B 实验列表在首次切到对应 tab 时懒加载，避免进页即聚合拖慢首屏
const tabLoaded: Record<string, boolean> = {}
watch(tab, (v) => {
  if (tabLoaded[v]) return
  tabLoaded[v] = true
  if (v === 'effect') loadEvals()
  else if (v === 'ab') loadExperiments()
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
        <div class="appr-bar">
          <CButton v-if="canEdit" size="sm" variant="primary" @click="openEvalCreate">新建评估任务</CButton>
          <CButton size="sm" variant="secondary" :disabled="evalLoading" @click="loadEvals">刷新</CButton>
        </div>
        <CTable :columns="evalCols" :rows="evalRows" row-key="taskId" stripe
          :empty-text="evalLoading ? '加载中…' : '暂无评估任务，点击「新建评估任务」按 AI 功能或模型聚合调用日志指标'">
          <template #col-status="{ value }"><CStatusPill :status="pill(value)" dot>{{ statusLabel(value) }}</CStatusPill></template>
          <template #col-conclusion="{ value }"><span :class="value ? '' : 'muted'">{{ value || '运行中，待人工结案录入结论' }}</span></template>
          <template #col-ops="{ row }">
            <template v-if="canEdit && row.status === 'RUNNING'">
              <CButton size="sm" variant="text" :disabled="evalBusy[row.taskId]" @click="refreshEvalRow(row as EvalView)">刷新指标</CButton>
              <CButton size="sm" variant="text" :disabled="evalBusy[row.taskId]" @click="concludeEvalRow(row as EvalView)">结案</CButton>
            </template>
            <span v-else class="muted">已冻结</span>
          </template>
        </CTable>
        <p class="hint">指标来源于 ai_invoke_log 技术口径（调用量 / 成功率 / P99 / Token / 成本），按北京时间回看窗口在创建与刷新时聚合快照；结案结论人工录入，结案后冻结。</p>
      </div>

      <div v-else class="mt">
        <div class="appr-bar">
          <CButton v-if="canEdit" size="sm" variant="primary" @click="openExpCreate">新建实验</CButton>
          <CButton size="sm" variant="secondary" :disabled="expLoading" @click="loadExperiments">刷新</CButton>
        </div>
        <CTable :columns="abCols" :rows="abRows" row-key="experimentId" stripe
          :empty-text="expLoading ? '加载中…' : '暂无 A/B 实验，接入两个以上模型后可新建对照实验'">
          <template #col-lift="{ value }"><span :class="liftClass(value)">{{ liftText(value) }}</span></template>
          <template #col-status="{ value }"><CStatusPill :status="pill(value)" dot>{{ statusLabel(value) }}</CStatusPill></template>
          <template #col-conclusion="{ value }"><span :class="value ? '' : 'muted'">{{ value || '运行中，待人工结案录入结论' }}</span></template>
          <template #col-ops="{ row }">
            <template v-if="canEdit && row.status === 'RUNNING'">
              <CButton size="sm" variant="text" :disabled="expBusy[row.experimentId]" @click="refreshExpRow(row as ExperimentView)">刷新指标</CButton>
              <CButton size="sm" variant="text" :disabled="expBusy[row.experimentId]" @click="concludeExpRow(row as ExperimentView)">结案</CButton>
            </template>
            <span v-else class="muted">已冻结</span>
          </template>
        </CTable>
        <p class="hint">效果提升 = 实验组成功率 − 对照组成功率（百分点 pp）；KPI「平均效果提升」仅统计已结案实验。业务转化/ROI 暂无埋点数据源，不在本批伪造。</p>
      </div>
    </CCard>

    <!-- 新建评估任务抽屉 -->
    <CDrawer v-model:show="evalDrawer" title="新建效果评估任务" size="md">
      <div class="gov-form">
        <CInput v-model="evalForm.taskName" label="任务名称" placeholder="如：智能话术 9 月效果评估" />
        <div class="gov-row">
          <label class="fld-label">评估维度</label>
          <CSelect v-model="evalForm.evalScope" :options="evalScopeOptions" width="100%" />
        </div>
        <div class="gov-row">
          <label class="fld-label">评估目标</label>
          <CSelect v-model="evalForm.targetCode" :options="evalTargetOptions" width="100%" />
        </div>
        <CInput v-model="evalForm.windowDays" type="number" label="统计窗口（天，1~90）" placeholder="如 7" />
      </div>
      <template #footer>
        <CButton variant="secondary" @click="evalDrawer = false">取消</CButton>
        <CButton variant="primary" :disabled="evalSaving" @click="saveEval">{{ evalSaving ? '创建中…' : '创建任务' }}</CButton>
      </template>
    </CDrawer>

    <!-- 新建 A/B 实验抽屉 -->
    <CDrawer v-model:show="expDrawer" title="新建 A/B 实验" size="md">
      <div class="gov-form">
        <CInput v-model="expForm.experimentName" label="实验名称" placeholder="如：话术模型切换对照实验" />
        <div class="gov-row">
          <label class="fld-label">对照组模型</label>
          <CSelect v-model="expForm.controlModel" :options="expModelOptions" width="100%" />
        </div>
        <div class="gov-row">
          <label class="fld-label">实验组模型</label>
          <CSelect v-model="expForm.experimentModel" :options="expModelOptions" width="100%" />
        </div>
        <CInput v-model="expForm.windowDays" type="number" label="统计窗口（天，1~90）" placeholder="如 7" />
      </div>
      <template #footer>
        <CButton variant="secondary" @click="expDrawer = false">取消</CButton>
        <CButton variant="primary" :disabled="expSaving" @click="saveExperiment">{{ expSaving ? '创建中…' : '创建实验' }}</CButton>
      </template>
    </CDrawer>

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
.hint { font-size: var(--t-xs); color: var(--c-text-3); margin-top: var(--s-sm); }
.appr-bar { display: flex; justify-content: flex-end; gap: var(--s-sm); margin-bottom: var(--s-sm); }
.content { display: flex; flex-direction: column; gap: 2px; }
.content__opinion { font-size: var(--t-xs); color: var(--c-text-3); }
.gov-form { display: flex; flex-direction: column; gap: var(--s-md); }
.gov-row { display: flex; flex-direction: column; gap: 6px; }
.fld-label { font-size: 13px; color: var(--c-text); line-height: 18px; }
.lift-up { color: var(--c-success-fg); font-weight: 600; }
.lift-down { color: var(--c-danger-fg); font-weight: 600; }
.redline { margin-top: var(--s-md); padding: var(--s-sm) var(--s-md); background: var(--c-danger-bg); border-radius: var(--r-md); display: flex; align-items: center; gap: var(--s-sm); }
.redline__title { font-size: var(--t-xs); font-weight: 600; color: var(--c-danger-fg); flex-shrink: 0; }
.redline__text { font-size: 11px; color: var(--c-danger-fg); line-height: 1.4; }
</style>
