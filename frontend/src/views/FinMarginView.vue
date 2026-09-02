<script setup lang="ts">
/* M6-07 毛利报表 /m6-margin — 毛利=已双签划扣确认收入−成本，按项目/门店拆分 */
import { computed, onMounted } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CSelect from '@/components/CSelect.vue'
import CBarChart from '@/components/CBarChart.vue'
import { useFinMarginStore } from '@/stores/finMargin'
import { useAuthStore } from '@/stores/auth'

const store = useFinMarginStore()
const auth = useAuthStore()
const canExport = computed(() => auth.can('finance:export'))

onMounted(() => store.init())

const kpis = computed(() => [
  { label: '确认收入', icon: 'check-square', value: `¥${store.totalRevenue.toLocaleString('zh-CN')}`, tone: 'brand' as const, sub: '已双签划扣口径' },
  { label: '营业成本', icon: 'finance', value: `¥${store.totalCost.toLocaleString('zh-CN')}`, tone: 'text' as const, sub: `耗材+人工` },
  { label: '毛利', icon: 'finance', value: `¥${store.totalGross.toLocaleString('zh-CN')}`, tone: store.totalGross >= 0 ? 'success' as const : 'danger' as const, sub: '收入−成本' },
  { label: '毛利率', icon: 'finance', value: `${store.grossRate}%`, tone: store.grossRate >= 40 ? 'success' as const : store.grossRate >= 20 ? 'warning' as const : 'danger' as const, sub: `镜像口径 ${store.mirrorRate}%` },
])

const chartItems = computed(() =>
  store.byCategory.map((c) => ({ label: c.category, values: [c.revenue, c.cost, Math.max(c.gross, 0)] })),
)

function rateTone(rate: number) {
  if (rate >= 50) return 'success'
  if (rate >= 30) return 'warning'
  return 'danger'
}

function exportCsv() {
  if (!canExport.value) return
  const head = '大类,项目,门店,确认收入,耗材,人工,成本,毛利,毛利率,单量\n'
  const rows = store.filtered.map((r) => {
    const cost = r.materialCost + r.laborCost
    const gross = r.revenue - cost
    const rate = r.revenue ? ((gross / r.revenue) * 100).toFixed(1) : '0'
    return [r.category, r.itemName, r.store, r.revenue, r.materialCost, r.laborCost, cost, gross, `${rate}%`, r.orderCount].join(',')
  }).join('\n')
  const blob = new Blob(['\uFEFF' + head + rows], { type: 'text/csv;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `毛利报表-${new Date().toISOString().slice(0, 10)}.csv`
  a.click()
  URL.revokeObjectURL(a.href)
}
</script>

<template>
  <div class="mg">
    <div class="mg__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :sub="k.sub" :icon="k.icon" />
    </div>

    <div class="mg__charts">
      <CCard padding="lg" class="mg__chart-card">
        <div class="chart-title"><CIcon name="dashboard" :size="14" />大类收入 / 成本 / 毛利</div>
        <CBarChart :items="chartItems" :series="['收入', '成本', '毛利']" orientation="horizontal" :show-value="false" :height="220" />
        <div class="legend">
          <span><i style="background:var(--c-series-1)"></i>收入</span>
          <span><i style="background:var(--c-series-2)"></i>成本</span>
          <span><i style="background:var(--c-series-3)"></i>毛利</span>
        </div>
      </CCard>
      <CCard padding="lg" class="mg__chart-card">
        <div class="chart-title"><CIcon name="finance" :size="14" />大类毛利率</div>
        <div class="rate-list">
          <div v-for="c in store.byCategory" :key="c.category" class="rate-row">
            <span class="rate-row__name">{{ c.category }}</span>
            <div class="rate-row__bar"><div class="rate-row__fill" :class="`is-${rateTone(c.rate)}`" :style="{ width: Math.min(c.rate, 100) + '%' }"></div></div>
            <span class="rate-row__val" :class="`is-${rateTone(c.rate)}`">{{ c.rate }}%</span>
          </div>
        </div>
      </CCard>
    </div>

    <CCard padding="none">
      <div class="list-head">
        <span class="list-head__title">项目毛利明细（只读镜像）</span>
        <div class="list-head__right">
          <CSelect v-model="store.filterCategory" :options="[{ value: 'ALL', label: '全部大类' }, ...store.categories.map((c) => ({ value: c, label: c }))]" />
          <CSelect v-model="store.filterStore" :options="[{ value: 'ALL', label: '全部门店' }, ...store.stores.map((s) => ({ value: s, label: s }))]" />
          <CButton variant="secondary" size="sm" :disabled="!canExport" @click="exportCsv">
            <CIcon name="export" :size="14" />导出
          </CButton>
        </div>
      </div>
      <div class="mg-table">
        <div class="mg-table__head">
          <span>大类 / 项目</span><span>门店</span><span>确认收入</span><span>耗材</span><span>人工</span><span>成本</span><span>毛利</span><span>毛利率</span><span>单量</span>
        </div>
        <div v-for="r in store.filtered" :key="r.id" class="mg-table__row">
          <span class="cell-name"><strong>{{ r.itemName }}</strong><em>{{ r.category }}</em></span>
          <span>{{ r.store }}</span>
          <span>¥{{ r.revenue.toLocaleString('zh-CN') }}</span>
          <span>¥{{ r.materialCost.toLocaleString('zh-CN') }}</span>
          <span>¥{{ r.laborCost.toLocaleString('zh-CN') }}</span>
          <span>¥{{ (r.materialCost + r.laborCost).toLocaleString('zh-CN') }}</span>
          <span class="cell-gross">¥{{ (r.revenue - r.materialCost - r.laborCost).toLocaleString('zh-CN') }}</span>
          <span :class="['cell-rate', `is-${rateTone(r.revenue ? (r.revenue - r.materialCost - r.laborCost) / r.revenue * 100 : 0)}`]">
            {{ r.revenue ? ((r.revenue - r.materialCost - r.laborCost) / r.revenue * 100).toFixed(1) : '0' }}%
          </span>
          <span>{{ r.orderCount }}</span>
        </div>
      </div>
    </CCard>
  </div>
</template>

<style scoped>
.mg { display: flex; flex-direction: column; gap: var(--s-lg); }
.mg__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .mg__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }

.mg__charts { display: grid; grid-template-columns: 1.4fr 1fr; gap: var(--s-lg); }
.mg__chart-card { min-width: 0; }
.chart-title { display: flex; align-items: center; gap: 6px; font-size: var(--t-sm); font-weight: 700; margin-bottom: var(--s-md); }
.legend { display: flex; gap: var(--s-md); justify-content: center; margin-top: var(--s-sm); font-size: var(--t-xs); color: var(--c-text-3); }
.legend i { display: inline-block; width: 10px; height: 10px; border-radius: 2px; margin-right: 4px; vertical-align: middle; }

.rate-list { display: flex; flex-direction: column; gap: var(--s-md); }
.rate-row { display: grid; grid-template-columns: 80px 1fr 52px; align-items: center; gap: var(--s-sm); font-size: var(--t-sm); }
.rate-row__name { color: var(--c-text-2); }
.rate-row__bar { height: 8px; background: var(--c-bg-right); border-radius: var(--r-full); overflow: hidden; }
.rate-row__fill { height: 100%; border-radius: var(--r-full); }
.rate-row__val { font-weight: 700; text-align: right; font-variant-numeric: tabular-nums; }
.is-success { background: var(--c-success-fg); color: var(--c-success-fg); }
.is-warning { background: var(--c-warning-fg); color: var(--c-warning-fg); }
.is-danger { background: var(--c-danger-fg); color: var(--c-danger-fg); }

.list-head { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-md) var(--s-lg); border-bottom: 1px solid var(--c-border-light); flex-wrap: wrap; }
.list-head__title { font-size: var(--t-sm); font-weight: 700; margin-right: auto; }
.list-head__right { display: flex; align-items: center; gap: var(--s-sm); flex-shrink: 0; flex-wrap: nowrap; margin-left: auto; }
.mg-table { font-size: var(--t-sm); }
.mg-table__head, .mg-table__row { display: grid; grid-template-columns: 1.6fr 1fr repeat(6, 1fr); gap: var(--s-sm); padding: var(--s-sm) var(--s-lg); align-items: center; }
.mg-table__head { font-size: var(--t-xs); color: var(--c-text-3); border-bottom: 1px solid var(--c-border-light); font-weight: 600; }
.mg-table__row { border-bottom: 1px solid var(--c-border-light); font-variant-numeric: tabular-nums; }
.cell-name { display: flex; flex-direction: column; }
.cell-name em { font-style: normal; font-size: var(--t-xs); color: var(--c-text-3); }
.cell-gross { font-weight: 700; color: var(--c-text); }
.cell-rate { font-weight: 700; }
.cell-rate.is-success { color: var(--c-success-fg); }
.cell-rate.is-warning { color: var(--c-warning-fg); }
.cell-rate.is-danger { color: var(--c-danger-fg); }

@media (max-width: 1024px) {
  .mg__charts { grid-template-columns: 1fr; }
  .list-head { flex-direction: column; align-items: stretch; }
  .list-head__right { margin-left: 0; overflow-x: auto; }
  .mg-table { overflow-x: auto; }
  .mg-table__head, .mg-table__row { min-width: 720px; }
}
</style>
