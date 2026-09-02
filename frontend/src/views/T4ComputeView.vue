<template>
  <div class="cp">
    <!-- 头部 KPI + 主按钮 -->
    <div class="cp__head">
      <CKpi v-for="k in kpis" :key="k.label" :value="k.value" :label="k.label" :tone="k.tone" :icon="k.icon" />
    </div>

    <div class="cp__row">
      <!-- 左：GPU 节点网格 + 配额表 -->
      <div class="cp__main">
        <CCard padding="lg">
          <template #header>
            <div class="card-head">
              <h3>GPU 节点</h3>
              <CButton variant="primary" :disabled="!store.can('compute:alloc')" @click="openAlloc">
                <CIcon name="plus" :size="14" />分配配额
              </CButton>
            </div>
          </template>
          <div class="gpugrid">
            <div v-for="g in store.gpus" :key="g.id" class="gpu" :class="`gpu--${g.status.toLowerCase()}`">
              <div class="gpu__head">
                <div class="gpu__name">
                  <span class="gpu__dot" />
                  <strong>{{ g.name }}</strong>
                </div>
                <CStatusPill :status="gpuStatus(g.status)" dot>{{ store.GPU_STATUS_LABEL[g.status] }}</CStatusPill>
              </div>
              <div class="gpu__model">{{ g.model }} · {{ g.region }}</div>
              <div class="gpu__vram">
                <span>显存 {{ g.vramUsed }}/{{ g.vramTotal }} GB</span>
                <CProgressBar
                  :value="g.vramUsed"
                  :max="g.vramTotal"
                  :color="g.vramUsed / g.vramTotal > 0.9 ? 'var(--c-danger-fg)' : 'var(--c-brand)'"
                  :height="6"
                  :show-label="false"
                />
              </div>
              <div class="gpu__stats">
                <div class="stat">
                  <label>利用率</label>
                  <strong>{{ g.utilization }}%</strong>
                  <CProgressBar
                    :value="g.utilization"
                    :color="g.utilization > 85 ? 'var(--c-warning-fg)' : 'var(--c-teal-dark)'"
                    :height="4"
                    :show-label="false"
                  />
                </div>
                <div class="stat">
                  <label>温度</label>
                  <strong :class="{ 'is-hot': g.temperature > 80 }">{{ g.temperature }}℃</strong>
                </div>
                <div class="stat">
                  <label>单价</label>
                  <strong>¥{{ g.costPerHour }}/h</strong>
                </div>
              </div>
              <div v-if="g.currentTask" class="gpu__task">
                <CIcon name="loading" :size="12" />
                <span>{{ g.currentTask }}</span>
              </div>
            </div>
          </div>
        </CCard>

        <CCard title="配额管理" padding="none" class="cp__quota">
          <CTable :columns="quotaCols" :rows="quotaRows" row-key="id">
            <template #col-status="{ value }">
              <CStatusPill :status="quotaStatus(value)">{{ quotaStatusLabel(value) }}</CStatusPill>
            </template>
            <template #col-gpuHours="{ row }">
              <span>{{ row.gpuHoursUsed }} / {{ row.gpuHours }} h</span>
              <CProgressBar
                :value="row.gpuHoursUsed"
                :max="row.gpuHours"
                :height="4"
                :show-label="false"
                :color="row.gpuHoursUsed > row.gpuHours ? 'var(--c-danger-fg)' : 'var(--c-brand)'"
              />
            </template>
            <template #col-budget="{ row }">
              <span>¥{{ row.spent.toLocaleString() }} / ¥{{ row.budget.toLocaleString() }}</span>
              <CProgressBar
                :value="row.spent"
                :max="row.budget"
                :height="4"
                :show-label="false"
                :color="row.spent > row.budget ? 'var(--c-danger-fg)' : 'var(--c-success-fg)'"
              />
            </template>
            <template #col-actions>
              <CButton size="sm" variant="text" :disabled="!store.can('compute:edit')">调整</CButton>
            </template>
          </CTable>
        </CCard>
      </div>

      <!-- 右：成本模拟器 -->
      <CCard title="成本模拟器" padding="lg" class="cp__side">
        <div class="sim">
          <div class="sim__field">
            <label>模型类型</label>
            <CSelect v-model="sim.modelType" :options="modelTypeOptions" width="100%" />
          </div>
          <div class="sim__field">
            <label>GPU 型号</label>
            <CSelect v-model="sim.gpuModel" :options="gpuModelOptions" width="100%" />
          </div>
          <div class="sim__field">
            <label>训练时长（小时）</label>
            <input class="native-input" type="number" min="0" v-model.number="sim.hours" />
          </div>
          <div class="sim__field">
            <label>并行卡数</label>
            <input class="native-input" type="number" min="1" v-model.number="sim.parallel" />
          </div>

          <div class="sim__result">
            <div class="sim__line">
              <span>GPU 工时</span>
              <strong>{{ simResult.gpuHours }} h</strong>
            </div>
            <div class="sim__line">
              <span>单价</span>
              <strong>¥{{ simResult.unitPrice }}/h</strong>
            </div>
            <div class="sim__line sim__line--total">
              <span>预估费用</span>
              <strong>¥{{ simResult.totalCost.toLocaleString() }}</strong>
            </div>
          </div>
          <p class="sim__tip">
            <CIcon name="alert" :size="13" />
            模拟值仅供预算评估，实际费用以账单为准。
          </p>
        </div>
      </CCard>
    </div>

    <!-- 配额分配抽屉 -->
    <CDrawer :show="allocOpen" title="分配算力配额" size="md" @update:show="allocOpen = $event">
      <div class="form">
        <CInput v-model="alloc.department" label="部门" placeholder="如：数据智能部" />
        <CInput v-model="alloc.project" label="项目" placeholder="如：客户流失预测 v4" />
        <div class="form__row">
          <div class="form__field">
            <label class="flabel">GPU 工时（h）</label>
            <input class="native-input" type="number" min="0" v-model.number="alloc.gpuHours" />
          </div>
          <div class="form__field">
            <label class="flabel">预算（元）</label>
            <input class="native-input" type="number" min="0" v-model.number="alloc.budget" />
          </div>
        </div>
        <div class="form__field">
          <label class="flabel">周期</label>
          <CSelect v-model="alloc.period" :options="periodOptions" width="100%" />
        </div>
      </div>
      <template #footer>
        <CButton variant="ghost" @click="allocOpen = false">取消</CButton>
        <CButton variant="primary" :disabled="!canSubmitAlloc" @click="submitAlloc">确认分配</CButton>
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
import CProgressBar from '@/components/CProgressBar.vue'
import CDrawer from '@/components/CDrawer.vue'
import CTable from '@/components/CTable.vue'
import { useT4ComputeStore, type GpuStatus, type QuotaAllocation } from '@/stores/t4Compute'

const store = useT4ComputeStore()
onMounted(() => store.seed())

const kpis = computed(() => [
  { label: 'GPU 节点数', icon: 'settings', value: String(store.kpi.totalGpus), tone: 'text' as const },
  { label: '繁忙节点', icon: 'settings', value: String(store.kpi.busyGpus), tone: 'warning' as const },
  { label: '总显存(GB)', icon: 'settings', value: store.kpi.totalVram.toLocaleString(), tone: 'teal' as const },
  { label: '今日成本', icon: 'finance', value: '¥' + store.kpi.costToday.toLocaleString(), tone: 'danger' as const },
])

function gpuStatus(s: GpuStatus) {
  return ({ IDLE: 'success', BUSY: 'warning', OFFLINE: 'danger', RESERVED: 'info' } as const)[s]
}

const quotaCols = [
  { key: 'department', label: '部门' },
  { key: 'project', label: '项目' },
  { key: 'gpuHours', label: 'GPU 工时', width: 180 },
  { key: 'budget', label: '预算', width: 200 },
  { key: 'period', label: '周期' },
  { key: 'status', label: '状态', width: 100 },
  { key: 'actions', label: '操作', width: 80, align: 'right' as const },
]
const quotaRows = computed(() => store.quotas.map((q) => ({ ...q })))
function quotaStatus(s: QuotaAllocation['status']) {
  return ({ ACTIVE: 'success', EXCEEDED: 'danger', EXPIRED: 'disabled' } as const)[s]
}
function quotaStatusLabel(s: QuotaAllocation['status']) {
  return ({ ACTIVE: '生效中', EXCEEDED: '超额', EXPIRED: '已过期' } as const)[s]
}

// 成本模拟器
const sim = reactive({ modelType: 'CLASSIFICATION', gpuModel: 'A100', hours: 6, parallel: 2 })
const modelTypeOptions = [
  { label: '分类 / 回归', value: 'CLASSIFICATION' },
  { label: 'NLP 大模型微调', value: 'NLP' },
  { label: 'CV 视觉训练', value: 'CV' },
  { label: '推荐 / 排序', value: 'RECOMMEND' },
  { label: '生成式 LLM', value: 'GENERATIVE' },
]
const gpuModelOptions = [
  { label: 'NVIDIA A100 (80GB)', value: 'A100' },
  { label: 'NVIDIA H100 (80GB)', value: 'H100' },
  { label: 'NVIDIA V100 (32GB)', value: 'V100' },
  { label: 'NVIDIA T4 (16GB)', value: 'T4' },
]
const simResult = computed(() => store.simulateCost({
  modelType: sim.modelType,
  gpuModel: sim.gpuModel,
  hours: Math.max(0, Number(sim.hours) || 0),
  parallel: Math.max(1, Number(sim.parallel) || 1),
}))

// 配额分配
const allocOpen = ref(false)
const alloc = reactive({ department: '', project: '', gpuHours: 100, budget: 3000, period: '2026-08' })
const periodOptions = [
  { label: '月度（2026-08）', value: '2026-08' },
  { label: '月度（2026-09）', value: '2026-09' },
  { label: '季度（2026-Q3）', value: '2026-Q3' },
  { label: '季度（2026-Q4）', value: '2026-Q4' },
]
const canSubmitAlloc = computed(() => alloc.department.trim() && alloc.project.trim() && alloc.gpuHours > 0 && alloc.budget > 0)
function openAlloc() {
  if (!store.can('compute:alloc')) return
  Object.assign(alloc, { department: '', project: '', gpuHours: 100, budget: 3000, period: '2026-08' })
  allocOpen.value = true
}
function submitAlloc() {
  store.allocateQuota({
    department: alloc.department.trim(),
    project: alloc.project.trim(),
    gpuHours: Number(alloc.gpuHours),
    budget: Number(alloc.budget),
    period: alloc.period,
  })
  allocOpen.value = false
}
</script>

<style scoped>
.cp { display: flex; flex-direction: column; gap: var(--s-lg); }
.cp__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }

.cp__row { display: grid; grid-template-columns: 1fr 320px; gap: var(--s-lg); align-items: start; }
.card-head { display: flex; justify-content: space-between; align-items: center; width: 100%; }
.card-head h3 { margin: 0; font-size: var(--t-md); font-weight: 600; color: var(--c-text); }
.cp__main { display: flex; flex-direction: column; gap: var(--s-lg); min-width: 0; }

.gpugrid { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--s-md); }
.gpu {
  border: 1px solid var(--c-border-light);
  border-radius: var(--r-lg);
  padding: var(--s-md);
  display: flex; flex-direction: column; gap: var(--s-sm);
  background: var(--c-surface);
  transition: border-color 0.15s, box-shadow 0.15s;
}
.gpu:hover { border-color: var(--c-brand-border); box-shadow: var(--shadow-card); }
.gpu--offline { opacity: 0.7; background: var(--c-bg-page); }
.gpu__head { display: flex; justify-content: space-between; align-items: center; gap: var(--s-sm); }
.gpu__name { display: flex; align-items: center; gap: 6px; font-size: var(--t-sm); color: var(--c-text); min-width: 0; }
.gpu__name strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.gpu__dot { width: 8px; height: 8px; border-radius: 50%; background: var(--c-success-fg); flex-shrink: 0; }
.gpu--busy .gpu__dot { background: var(--c-warning-fg); }
.gpu--offline .gpu__dot { background: var(--c-danger-fg); }
.gpu--reserved .gpu__dot { background: var(--c-info-fg); }
.gpu__model { font-size: var(--t-xs); color: var(--c-text-3); }
.gpu__vram { display: flex; flex-direction: column; gap: 4px; font-size: var(--t-xs); color: var(--c-text-2); }
.gpu__stats { display: grid; grid-template-columns: 1.4fr 1fr 1fr; gap: var(--s-sm); padding-top: var(--s-xs); border-top: 1px dashed var(--c-border-light); }
.stat { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
.stat label { font-size: 10px; color: var(--c-text-3); }
.stat strong { font-size: var(--t-sm); color: var(--c-text); font-variant-numeric: tabular-nums; }
.stat .is-hot { color: var(--c-danger-fg); }
.gpu__task { display: flex; align-items: center; gap: 6px; font-size: var(--t-xs); color: var(--c-text-2); padding: 4px 8px; background: var(--c-brand-soft); border-radius: var(--r-sm); }
.gpu__task .ci { animation: spin 1.4s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

.cp__side { position: sticky; top: var(--s-md); }
.sim { display: flex; flex-direction: column; gap: var(--s-md); }
.sim__field { display: flex; flex-direction: column; gap: 6px; }
.sim__field label { font-size: 13px; color: var(--c-text); line-height: 18px; }
.native-input {
  width: 100%; height: 36px; padding: 0 var(--s-sm);
  border: 1px solid var(--c-border); border-radius: var(--r-sm);
  background: var(--c-surface); font-size: var(--t-sm); color: var(--c-text);
  font-family: inherit;
}
.native-input:focus { outline: none; border-color: var(--c-brand-border); }
.sim__result {
  background: var(--c-bg-page); border-radius: var(--r-md); padding: var(--s-md);
  display: flex; flex-direction: column; gap: var(--s-xs);
}
.sim__line { display: flex; justify-content: space-between; font-size: var(--t-sm); color: var(--c-text-2); }
.sim__line strong { color: var(--c-text); font-variant-numeric: tabular-nums; }
.sim__line--total { border-top: 1px dashed var(--c-border-light); padding-top: var(--s-xs); margin-top: var(--s-xxs); }
.sim__line--total strong { color: var(--c-danger-fg); font-size: var(--t-lg); font-weight: 700; }
.sim__tip { display: flex; align-items: center; gap: 6px; font-size: var(--t-xs); color: var(--c-text-3); margin: 0; }

.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.form__field { display: flex; flex-direction: column; gap: 6px; }
.flabel { font-size: 13px; color: var(--c-text); line-height: 18px; }

.cp__quota :deep(td) { vertical-align: middle; }
.cp__quota :deep(td > span:first-child) { display: inline-block; margin-bottom: 4px; font-size: var(--t-sm); }

@media (max-width: 1100px) {
  .cp__row { grid-template-columns: 1fr; }
  .gpugrid { grid-template-columns: repeat(2, 1fr); }
}
@media (max-width: 720px) {
  .gpugrid { grid-template-columns: 1fr; }
  .form__row { grid-template-columns: 1fr; }
}
</style>
