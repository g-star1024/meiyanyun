<template>
  <div class="mx">
    <!-- 工具栏 -->
    <div class="mx__bar">
      <div class="bar__left">
        <span class="bar__label">指标域</span>
        <CSegmented v-model="mx.activeGroup" :options="groupOptions" size="sm" />
      </div>
      <div class="bar__right">
        <span class="bar__label">周期</span>
        <CSegmented v-model="mx.period" :options="periodOptions" size="sm" />
      </div>
    </div>

    <div class="mx__body">
      <!-- 热力矩阵 -->
      <CCard :title="'指标矩阵 · ' + GROUP_RANGE" padding="none" class="mx__grid-card">
        <div class="heat">
          <table class="heat__table">
            <thead>
              <tr>
                <th class="heat__corner">指标</th>
                <th v-for="s in mx.stores" :key="s.id" class="heat__col">{{ s.name }}<span class="heat__region">{{ s.region }}</span></th>
              </tr>
            </thead>
            <tbody>
              <template v-for="g in groupKeys" :key="g">
                <tr class="heat__group">
                  <td :colspan="mx.stores.length + 1">{{ GROUP_LABEL[g] }}</td>
                </tr>
                <tr v-for="m in metricsOf(g)" :key="m.key">
                  <th class="heat__row-label">{{ m.label }}<span class="u">{{ m.unit }}</span></th>
                  <td v-for="s in mx.stores" :key="s.id"
                      class="heat__cell"
                      :class="{ 'is-selected': isSel(m.key, s.id), 'is-strong': textDark(m.key, s.id) }"
                      :style="{ background: bg(m.key, s.id) }"
                      @click="mx.select(m.key, s.id)">
                    <span class="cell__v">{{ mx.cell(m.key, s.id)?.value }}</span>
                    <span class="cell__mom" :class="momCls(m.key, s.id)">{{ momText(m.key, s.id) }}</span>
                  </td>
                </tr>
              </template>
            </tbody>
          </table>
        </div>
        <div class="legend-bar">
          <span>达成率低</span>
          <span class="legend-scale"><i v-for="i in 6" :key="i" :style="{ background: heatColor((i-1)/5) }" /></span>
          <span>达成率高</span>
        </div>
      </CCard>

      <!-- 下钻详情 -->
      <CCard class="mx__detail" padding="lg">
        <template v-if="mx.selectedDetail">
          <div class="det">
            <div class="det__head">
              <div>
                <h3>{{ mx.selectedDetail.metric.label }}</h3>
                <div class="det__sub">{{ mx.selectedDetail.store.name }} · {{ mx.selectedDetail.store.region }}区 · {{ mx.period }}</div>
              </div>
              <span class="det__unit">{{ mx.selectedDetail.metric.unit }}</span>
            </div>
            <div class="det__value">{{ mx.selectedDetail.cell.value }}</div>
            <div class="det__grid">
              <div class="dblk">
                <div class="dblk__l">目标值</div>
                <div class="dblk__v">{{ mx.selectedDetail.metric.target }}</div>
              </div>
              <div class="dblk">
                <div class="dblk__l">达成率</div>
                <div class="dblk__v" :class="achievementCls">{{ achievementRate }}%</div>
              </div>
              <div class="dblk">
                <div class="dblk__l">环比</div>
                <div class="dblk__v" :class="mx.selectedDetail.cell.mom >= 0 ? 'up' : 'down'">
                  {{ mx.selectedDetail.cell.mom >= 0 ? '+' : '' }}{{ mx.selectedDetail.cell.mom }}%
                </div>
              </div>
              <div class="dblk">
                <div class="dblk__l">同比</div>
                <div class="dblk__v" :class="mx.selectedDetail.cell.yoy >= 0 ? 'up' : 'down'">
                  {{ mx.selectedDetail.cell.yoy >= 0 ? '+' : '' }}{{ mx.selectedDetail.cell.yoy }}%
                </div>
              </div>
            </div>
            <div class="det__bar">
              <CProgressBar :value="Math.min(100, achievementRate)" :color="achievementColor" :height="10" :show-label="false" />
              <div class="det__bar-marks"><span>0</span><span>目标</span><span>超额</span></div>
            </div>
            <div class="det__hint">{{ hint }}</div>
          </div>
        </template>
        <div v-else class="empty">
          <CIcon name="dashboard" :size="32" />
          <p>点击矩阵中任意单元格查看指标下钻详情</p>
        </div>
      </CCard>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import CCard from '@/components/CCard.vue'
import CIcon from '@/components/CIcon.vue'
import CSegmented from '@/components/CSegmented.vue'
import CProgressBar from '@/components/CProgressBar.vue'
import { useM1MatrixStore, GROUP_LABEL,
  type MetricGroup, type MatrixMetric } from '@/stores/m1Matrix'

const mx = useM1MatrixStore()
onMounted(() => mx.seed())

const groupOptions = [
  { label: '全部', value: 'ALL' },
  { label: '财务', value: 'FINANCE' },
  { label: '客户', value: 'CUSTOMER' },
  { label: '运营', value: 'OPERATION' },
  { label: '人效', value: 'STAFF' },
]
const periodOptions = mx.periods.map((p) => ({ label: p, value: p }))

const groupKeys = computed<MetricGroup[]>(() => {
  const all: MetricGroup[] = ['FINANCE', 'CUSTOMER', 'OPERATION', 'STAFF']
  return mx.activeGroup === 'ALL' ? all : [mx.activeGroup as MetricGroup]
})
function metricsOf(g: MetricGroup): MatrixMetric[] {
  return mx.visibleMetrics.filter((m) => m.group === g)
}
const GROUP_RANGE = computed(() => mx.activeGroup === 'ALL' ? '全维度' : GROUP_LABEL[mx.activeGroup as MetricGroup])

function isSel(metricKey: string, storeId: string) {
  return mx.selectedCell?.metricKey === metricKey && mx.selectedCell?.storeId === storeId
}

function heatColor(h: number): string {
  // 0: 近白（差） → 1: 品牌粉（好），低值贴近背景、高值饱和，层次分明
  const alpha = 0.04 + h * 0.86
  return `rgba(255, 107, 158, ${alpha.toFixed(2)})`
}
function textDark(metricKey: string, storeId: string): boolean {
  // 强度高时文字反白
  return mx.heat(metricKey, storeId) > 0.55
}
function bg(metricKey: string, storeId: string) {
  return heatColor(mx.heat(metricKey, storeId))
}
function momText(metricKey: string, storeId: string) {
  const c = mx.cell(metricKey, storeId)
  if (!c) return ''
  return (c.mom >= 0 ? '▲' : '▼') + Math.abs(c.mom) + '%'
}
function momCls(metricKey: string, storeId: string) {
  const m = mx.metric(metricKey)
  const c = mx.cell(metricKey, storeId)
  if (!c) return ''
  const positive = m.higherBetter ? c.mom >= 0 : c.mom <= 0
  return positive ? 'up' : 'down'
}

const achievementRate = computed(() => {
  if (!mx.selectedDetail) return 0
  const { cell: c, metric: m } = mx.selectedDetail
  const ratio = m.higherBetter ? c.value / m.target : m.target / c.value
  return Math.round(ratio * 100)
})
const achievementCls = computed(() => {
  const r = achievementRate.value
  if (r >= 100) return 'up'
  if (r >= 85) return ''
  return 'down'
})
const achievementColor = computed(() => {
  const r = achievementRate.value
  if (r >= 100) return 'var(--c-success-fg)'
  if (r >= 85) return 'var(--c-brand)'
  return 'var(--c-danger-fg)'
})
const hint = computed(() => {
  if (!mx.selectedDetail) return ''
  const r = achievementRate.value
  const m = mx.selectedDetail.metric
  if (r >= 100) return `${m.label}已达成目标，表现优异`
  if (r >= 85) return `${m.label}接近目标，继续保持`
  if (m.higherBetter) return `${m.label}落后目标，需重点关注`
  return `${m.label}超出目标控制线，需介入`
})
</script>

<style scoped>
.mx { display: flex; flex-direction: column; gap: var(--s-lg); }
.mx__bar { display: flex; justify-content: space-between; align-items: center; gap: var(--s-md); padding: var(--s-md) var(--s-lg); background: var(--c-surface); border: 1px solid var(--c-border); border-radius: var(--r-lg); }
.bar__left, .bar__right { display: flex; align-items: center; gap: var(--s-sm); flex-wrap: nowrap; }
.bar__label { font-size: var(--t-sm); color: var(--c-text-2); font-weight: 600; white-space: nowrap; }
@media (max-width: 1024px) {
  .mx__bar { flex-wrap: wrap; }
}
.mx__body { display: grid; grid-template-columns: 1fr 320px; gap: var(--s-lg); align-items: start; }
.heat { overflow-x: auto; }
.heat__table { width: 100%; border-collapse: collapse; font-size: var(--t-sm); }
.heat__corner, .heat__col, .heat__row-label { text-align: left; font-weight: 600; color: var(--c-text-2); padding: var(--s-sm) var(--s-md); background: var(--c-surface-muted); }
.heat__col { text-align: center; }
.heat__region { display: block; font-size: 10px; color: var(--c-text-3); font-weight: 400; margin-top: 2px; }
.heat__group td { padding: var(--s-xs) var(--s-md); font-size: var(--t-xs); color: var(--c-brand); font-weight: 700; background: var(--c-brand-soft); }
.heat__row-label { font-weight: 600; background: var(--c-surface); border-bottom: 1px solid var(--c-border); white-space: nowrap; }
.u { font-size: 10px; color: var(--c-text-3); font-weight: 400; margin-left: 3px; }
.heat__cell { text-align: center; padding: var(--s-sm) var(--s-xs); border: 2px solid var(--c-surface); cursor: pointer; transition: outline .1s, box-shadow .1s; min-width: 104px; }
.heat__cell:hover { box-shadow: inset 0 0 0 2px var(--c-brand); }
.heat__cell.is-selected { box-shadow: inset 0 0 0 2px var(--c-brand-press); }
.heat__cell.is-strong .cell__v { color: #fff; }
.heat__cell.is-strong .cell__mom.up { color: #d1fae5; }
.heat__cell.is-strong .cell__mom.down { color: #fee2e2; }
.cell__v { display: block; font-weight: 700; font-size: var(--t-sm); font-variant-numeric: tabular-nums; }
.cell__mom { display: block; font-size: 10px; margin-top: 2px; font-variant-numeric: tabular-nums; }
.cell__mom.up { color: var(--c-success-fg); }
.cell__mom.down { color: var(--c-danger-fg); }
.legend-bar { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-sm) var(--s-md); font-size: var(--t-xs); color: var(--c-text-3); border-top: 1px solid var(--c-border); }
.legend-scale { display: inline-flex; gap: 2px; }
.legend-scale i { width: 24px; height: 10px; border-radius: 2px; }
.det__head { display: flex; justify-content: space-between; align-items: flex-start; gap: var(--s-sm); margin-bottom: var(--s-sm); }
.det__head h3 { margin: 0; font-size: var(--t-lg); font-weight: 700; }
.det__sub { font-size: var(--t-xs); color: var(--c-text-3); margin-top: var(--s-xs); }
.det__unit { font-size: var(--t-xs); color: var(--c-text-3); background: var(--c-surface-muted); padding: 2px 8px; border-radius: var(--r-sm); white-space: nowrap; }
.det__value { font-size: 36px; font-weight: 700; color: var(--c-text); margin: var(--s-md) 0; font-variant-numeric: tabular-nums; line-height: 1; }
.det__grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-sm); margin-bottom: var(--s-lg); }
.dblk { background: var(--c-surface-muted); border-radius: var(--r-md); padding: var(--s-md); }
.dblk__l { font-size: var(--t-xs); color: var(--c-text-3); }
.dblk__v { font-size: var(--t-lg); font-weight: 700; margin-top: 4px; font-variant-numeric: tabular-nums; }
.dblk__v.up { color: var(--c-success-fg); }
.dblk__v.down { color: var(--c-danger-fg); }
.det__bar { margin-bottom: var(--s-md); }
.det__bar-marks { display: flex; justify-content: space-between; font-size: 10px; color: var(--c-text-3); margin-top: var(--s-xs); }
.det__hint { font-size: var(--t-sm); color: var(--c-text-2); padding-top: var(--s-md); border-top: 1px solid var(--c-border); line-height: 1.6; }
.empty { text-align: center; color: var(--c-text-3); padding: var(--s-xxl) var(--s-md); }
.empty p { margin-top: var(--s-md); font-size: var(--t-sm); }
@media (max-width: 900px) { .mx__body { grid-template-columns: 1fr; } }
</style>
