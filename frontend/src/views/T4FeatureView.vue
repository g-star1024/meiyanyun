<template>
  <div class="ft">
    <!-- 头部 KPI + 主按钮 -->
    <div class="ft__head">
      <CKpi v-for="k in kpis" :key="k.label" :value="k.value" :label="k.label" :tone="k.tone" :icon="k.icon" />
    </div>

    <CCard padding="lg">
      <div class="ft__bar">
        <CSegmented v-model="tab" :options="tabOptions" />
        <div class="ft__bar-right">
          <CInput v-model="keyword" placeholder="搜索特征名/分组" />
          <CButton variant="primary" :disabled="!store.can('feature:register')" @click="openRegister">
            <CIcon name="plus" :size="16" />注册特征
          </CButton>
        </div>
      </div>

      <!-- 表格视图 -->
      <div v-if="tab !== 'lineage'" class="ft__table">
        <CTable :columns="columns" :rows="filteredRows" row-key="id">
          <template #col-type="{ value }">
            <CStatusPill :status="value === 'ONLINE' ? 'success' : 'info'">
              {{ store.FEATURE_TYPE_LABEL[value as 'ONLINE'|'OFFLINE'] }}
            </CStatusPill>
          </template>
          <template #col-status="{ value }">
            <CStatusPill :status="featStatus(value as FeatureStatus)" dot>{{ store.FEATURE_STATUS_LABEL[value as FeatureStatus] }}</CStatusPill>
          </template>
          <template #col-onlineServing="{ row }">
            <label class="switch" :class="{ 'is-on': row.onlineServing }">
              <input type="checkbox" :checked="row.onlineServing" @change="toggleServing(row)" :disabled="!store.can('feature:edit')" />
              <span class="switch__track" />
            </label>
          </template>
          <template #col-callCount30d="{ value }">
            <span class="num">{{ Number(value).toLocaleString() }}</span>
          </template>
          <template #col-actions="{ row }">
            <div class="row-ops">
              <CButton v-if="row._status === 'REGISTERED'" size="sm" variant="text" :disabled="!store.can('feature:publish')" @click="publish(row)">发布</CButton>
              <CButton v-if="row._status === 'PUBLISHED'" size="sm" variant="text" :disabled="!store.can('feature:publish')" @click="deprecate(row)">下线</CButton>
            </div>
          </template>
        </CTable>
      </div>

      <!-- 血缘视图（SVG DAG） -->
      <div v-else class="lineage">
        <svg class="dag" :viewBox="`0 0 ${dag.w} ${dag.h}`" preserveAspectRatio="xMidYMid meet">
          <defs>
            <marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse">
              <path d="M0,0 L10,5 L0,10 z" fill="var(--c-text-4)" />
            </marker>
          </defs>
          <!-- 连线 -->
          <g class="dag__edges">
            <line
              v-for="(e, i) in dag.edges" :key="i"
              :x1="e.x1" :y1="e.y1" :x2="e.x2" :y2="e.y2"
              stroke="var(--c-border-strong, var(--c-text-4))" stroke-width="1.5"
              marker-end="url(#arrow)"
            />
          </g>
          <!-- 节点 -->
          <g class="dag__nodes">
            <g
              v-for="n in dag.nodes" :key="n.id"
              :transform="`translate(${n.x}, ${n.y})`"
              class="dag__node"
            >
              <rect
                :width="n.w" :height="n.h" rx="8" ry="8"
                :fill="nodeColor(n.type).fill"
                :stroke="nodeColor(n.type).stroke"
                stroke-width="1.5"
              />
              <text :x="n.w / 2" :y="20" text-anchor="middle" class="dag__type">{{ nodeTypeLabel(n.type) }}</text>
              <text :x="n.w / 2" :y="42" text-anchor="middle" class="dag__name">
                {{ truncate(n.name, 18) }}
              </text>
            </g>
          </g>
        </svg>
        <div class="legend">
          <span><i class="lg lg--src" />数据源</span>
          <span><i class="lg lg--feat" />特征</span>
          <span><i class="lg lg--mdl" />模型</span>
          <span><i class="lg lg--svc" />在线服务</span>
        </div>
      </div>
    </CCard>

    <!-- 注册特征抽屉 -->
    <CDrawer :show="registerOpen" title="注册特征" size="md" @update:show="registerOpen = $event">
      <div class="form">
        <CInput v-model="form.name" label="特征名称" placeholder="如：cust_recency_days" />
        <div class="form__row">
          <CInput v-model="form.group" label="分组" placeholder="客户RFM" />
          <div class="form__field">
            <label class="flabel">值类型</label>
            <CSelect v-model="form.valueType" :options="valueTypeOptions" width="100%" />
          </div>
        </div>
        <div class="form__row">
          <div class="form__field">
            <label class="flabel">类型</label>
            <CSelect v-model="form.type" :options="typeOptions" width="100%" />
          </div>
          <div class="form__field">
            <label class="flabel">新鲜度</label>
            <CSelect v-model="form.freshness" :options="freshnessOptions" width="100%" />
          </div>
        </div>
        <CInput v-model="form.source" label="来源表/字段" placeholder="如：dwd_customer_visit" />
        <CInput v-model="form.owner" label="负责人" placeholder="姓名" />
        <CTextarea v-model="form.description" label="描述" :rows="3" placeholder="特征业务含义、使用场景" />
        <div class="form__row">
          <CInput v-model="form.ttl" label="TTL（可选）" placeholder="如：30天" />
          <label class="checkline">
            <input type="checkbox" v-model="form.onlineServing" />
            <span>启用在线服务（ONLINE 实时）</span>
          </label>
        </div>
      </div>
      <template #footer>
        <CButton variant="ghost" @click="registerOpen = false">取消</CButton>
        <CButton variant="primary" :disabled="!canSubmit" @click="submit">注册</CButton>
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
import CTextarea from '@/components/CTextarea.vue'
import CDrawer from '@/components/CDrawer.vue'
import CSegmented from '@/components/CSegmented.vue'
import CTable from '@/components/CTable.vue'
import {
  useT4FeatureStore,
  type FeatureStatus,
  type FeatureType,
  type FeatureValueType,
  type LineageNode,
} from '@/stores/t4Feature'

const store = useT4FeatureStore()
onMounted(() => store.seed())

const kpis = computed(() => [
  { label: '特征总数', icon: 'settings', value: String(store.kpi.total), tone: 'text' as const },
  { label: '已发布', icon: 'check-square', value: String(store.kpi.published), tone: 'success' as const },
  { label: '在线服务', icon: 'settings', value: String(store.kpi.online), tone: 'brand' as const },
  { label: '近30天调用', icon: 'settings', value: store.kpi.totalCalls.toLocaleString(), tone: 'teal' as const },
])

const tab = ref('all')
const tabOptions = [
  { label: '全部特征', value: 'all' },
  { label: '在线特征', value: 'online' },
  { label: '离线特征', value: 'offline' },
  { label: '血缘视图', value: 'lineage' },
]
const keyword = ref('')

function featStatus(s: FeatureStatus) {
  return ({ DRAFT: 'disabled', REGISTERED: 'warning', PUBLISHED: 'success', DEPRECATED: 'info' } as const)[s]
}

const columns = [
  { key: 'name', label: '特征名' },
  { key: 'group', label: '分组' },
  { key: 'type', label: '类型', width: 90 },
  { key: 'valueType', label: '值类型', width: 90 },
  { key: 'status', label: '状态', width: 100 },
  { key: 'source', label: '来源' },
  { key: 'freshness', label: '新鲜度' },
  { key: 'onlineServing', label: '在线', width: 70, align: 'center' as const },
  { key: 'callCount30d', label: '30天调用', align: 'right' as const },
  { key: 'actions', label: '操作', width: 120, align: 'right' as const },
]

const filteredRows = computed(() => {
  let list = store.features
  if (tab.value === 'online') list = list.filter((f) => f.type === 'ONLINE')
  else if (tab.value === 'offline') list = list.filter((f) => f.type === 'OFFLINE')
  if (keyword.value.trim()) {
    const k = keyword.value.trim().toLowerCase()
    list = list.filter((f) => f.name.toLowerCase().includes(k) || f.group.toLowerCase().includes(k))
  }
  return list.map((f) => ({
    ...f,
    valueType: store.FEATURE_VALUE_LABEL[f.valueType],
    _status: f.status,
  }))
})

function toggleServing(row: any) {
  store.toggleOnline(row.id, !row.onlineServing)
}
function publish(row: any) { store.publishFeature(row.id) }
function deprecate(row: any) {
  if (!window.confirm(`确认下线特征「${row.name}」？`)) return
  store.deprecateFeature(row.id)
}

// ---- 血缘 DAG 布局（4 列：SOURCE → FEATURE → MODEL → SERVICE） ----
const dag = computed(() => {
  const nodes = store.lineage.nodes
  const edges = store.lineage.edges
  const colMap: Record<LineageNode['type'], number> = { SOURCE: 0, FEATURE: 1, MODEL: 2, SERVICE: 3 }
  const colW = 240
  const nodeW = 200
  const nodeH = 64
  const gapY = 16
  const padX = 20
  const padY = 20

  // 按列分组
  const cols: LineageNode[][] = [[], [], [], []]
  nodes.forEach((n) => cols[colMap[n.type]].push(n))
  const colCount = cols.length
  const maxRows = Math.max(...cols.map((c) => c.length))
  const totalH = padY * 2 + maxRows * (nodeH + gapY) - gapY
  const totalW = padX * 2 + colCount * colW - (colW - nodeW)

  const positioned: Array<LineageNode & { x: number; y: number; w: number; h: number }> = []
  const posMap = new Map<string, { x: number; y: number; w: number; h: number }>()

  cols.forEach((col, ci) => {
    const colH = col.length * (nodeH + gapY) - gapY
    const startY = padY + (totalH - padY * 2 - colH) / 2
    col.forEach((n, ri) => {
      const x = padX + ci * colW
      const y = startY + ri * (nodeH + gapY)
      const p = { x, y, w: nodeW, h: nodeH }
      positioned.push({ ...n, ...p })
      posMap.set(n.id, p)
    })
  })

  const edgePos = edges
    .map((e) => {
      const a = posMap.get(e.from)
      const b = posMap.get(e.to)
      if (!a || !b) return null
      return {
        x1: a.x + a.w,
        y1: a.y + a.h / 2,
        x2: b.x,
        y2: b.y + b.h / 2,
      }
    })
    .filter(Boolean) as Array<{ x1: number; y1: number; x2: number; y2: number }>

  return { w: totalW, h: totalH, nodes: positioned, edges: edgePos }
})

function nodeColor(t: LineageNode['type']) {
  switch (t) {
    case 'SOURCE': return { fill: 'var(--c-info-bg)', stroke: 'var(--c-info-fg)' }
    case 'FEATURE': return { fill: 'var(--c-brand-soft)', stroke: 'var(--c-brand)' }
    case 'MODEL': return { fill: 'var(--c-warning-bg)', stroke: 'var(--c-warning-fg)' }
    case 'SERVICE': return { fill: 'var(--c-success-bg)', stroke: 'var(--c-success-fg)' }
  }
}
function nodeTypeLabel(t: LineageNode['type']) {
  return ({ SOURCE: '数据源', FEATURE: '特征', MODEL: '模型', SERVICE: '在线服务' } as const)[t]
}
function truncate(s: string, n: number) {
  return s.length > n ? s.slice(0, n - 1) + '…' : s
}

// ---- 注册抽屉 ----
const registerOpen = ref(false)
const form = reactive({
  name: '', group: '', type: 'ONLINE' as FeatureType,
  valueType: 'FLOAT' as FeatureValueType, description: '',
  source: '', owner: '', onlineServing: true, ttl: '', freshness: 'T+0 实时',
})
const typeOptions = [
  { label: '在线 (ONLINE)', value: 'ONLINE' },
  { label: '离线 (OFFLINE)', value: 'OFFLINE' },
]
const valueTypeOptions = [
  { label: '整数 INT', value: 'INT' },
  { label: '浮点 FLOAT', value: 'FLOAT' },
  { label: '字符串 STRING', value: 'STRING' },
  { label: '向量 VECTOR', value: 'VECTOR' },
  { label: '布尔 BOOL', value: 'BOOL' },
]
const freshnessOptions = [
  { label: 'T+0 实时', value: 'T+0 实时' },
  { label: 'T+0 小时级', value: 'T+0 小时级' },
  { label: 'T+1 天级', value: 'T+1 天级' },
  { label: 'T+7 周级', value: 'T+7 周级' },
]
const canSubmit = computed(() => form.name.trim() && form.group.trim() && form.source.trim() && form.owner.trim())
function openRegister() {
  if (!store.can('feature:register')) return
  Object.assign(form, { name: '', group: '', type: 'ONLINE', valueType: 'FLOAT', description: '', source: '', owner: '', onlineServing: true, ttl: '', freshness: 'T+0 实时' })
  registerOpen.value = true
}
function submit() {
  store.registerFeature({
    name: form.name.trim(),
    group: form.group.trim(),
    type: form.type,
    valueType: form.valueType,
    description: form.description.trim(),
    source: form.source.trim(),
    owner: form.owner.trim(),
    onlineServing: form.onlineServing,
    ttl: form.ttl.trim() || undefined,
    freshness: form.freshness,
  })
  registerOpen.value = false
}
</script>

<style scoped>
.ft { display: flex; flex-direction: column; gap: var(--s-lg); }
.ft__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
.ft__bar { display: flex; justify-content: space-between; align-items: center; gap: var(--s-md); margin-bottom: var(--s-md); flex-wrap: wrap; }
.ft__bar-right { display: flex; gap: var(--s-sm); align-items: center; }
.ft__bar-right :deep(.cinput) { width: 240px; }
.ft__table { width: 100%; }

.num { font-variant-numeric: tabular-nums; }
.row-ops { display: inline-flex; gap: var(--s-xxs); justify-content: flex-end; }

/* 在线服务开关 */
.switch { position: relative; display: inline-block; width: 38px; height: 20px; cursor: pointer; }
.switch input { opacity: 0; width: 0; height: 0; }
.switch__track {
  position: absolute; inset: 0;
  background: var(--c-border);
  border-radius: 999px;
  transition: background 0.15s;
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

/* 血缘 */
.lineage {
  background: var(--c-bg-page);
  border-radius: var(--r-md);
  padding: var(--s-md);
  overflow: auto;
}
.dag { width: 100%; min-width: 960px; height: auto; display: block; }
.dag__node { cursor: default; }
.dag__type { font-size: 10px; fill: var(--c-text-3); font-weight: 600; }
.dag__name { font-size: 12px; fill: var(--c-text); font-weight: 600; }
.legend { display: flex; gap: var(--s-lg); justify-content: center; margin-top: var(--s-md); font-size: var(--t-xs); color: var(--c-text-2); }
.legend span { display: inline-flex; align-items: center; gap: 6px; }
.lg { width: 12px; height: 12px; border-radius: 3px; display: inline-block; }
.lg--src { background: var(--c-info-bg); border: 1.5px solid var(--c-info-fg); }
.lg--feat { background: var(--c-brand-soft); border: 1.5px solid var(--c-brand); }
.lg--mdl { background: var(--c-warning-bg); border: 1.5px solid var(--c-warning-fg); }
.lg--svc { background: var(--c-success-bg); border: 1.5px solid var(--c-success-fg); }

/* 表单 */
.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); align-items: end; }
.form__field { display: flex; flex-direction: column; gap: 6px; }
.flabel { font-size: 13px; color: var(--c-text); line-height: 18px; }
.checkline { display: inline-flex; align-items: center; gap: 8px; font-size: var(--t-sm); color: var(--c-text-2); height: 36px; }
.checkline input { width: 16px; height: 16px; accent-color: var(--c-brand); }

@media (max-width: 720px) {
  .form__row { grid-template-columns: 1fr; }
}
</style>
