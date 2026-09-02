<template>
  <div class="ov">
    <!-- KPI 行 -->
    <div class="ov__kpis">
      <div class="kpi kpi--hero">
        <div class="kpi__l">本月集团营收</div>
        <div class="kpi__v">{{ ov.kpis.revenue.value.toLocaleString() }}<span class="u">{{ ov.kpis.revenue.unit }}</span></div>
        <div class="kpi__trend" :class="ov.kpis.revenue.delta >= 0 ? 'up' : 'down'">
          <CIcon :name="ov.kpis.revenue.delta >= 0 ? 'trend-up' : 'trend-down'" :size="14" />
          环比 {{ Math.abs(ov.kpis.revenue.delta) }}%
        </div>
      </div>
      <div v-for="k in subKpis" :key="k.label" class="kpi">
        <div class="kpi__l">{{ k.label }}</div>
        <div class="kpi__v">{{ k.value }}</div>
        <div class="kpi__trend" :class="k.delta >= 0 ? 'up' : 'down'">
          <CIcon :name="k.delta >= 0 ? 'trend-up' : 'trend-down'" :size="14" />
          {{ Math.abs(k.delta) }}%
        </div>
      </div>
    </div>

    <div class="ov__row">
      <!-- 营收趋势 -->
      <CCard title="月度营收趋势（万元）" padding="lg" class="ov__chart ov__chart--wide">
        <CBarChart :items="revItems" :height="240" :show-value="false" />
      </CCard>
      <!-- 预警 -->
      <CCard title="经营预警" padding="none" class="ov__alerts">
        <div class="alert" v-for="(a, i) in ov.alerts" :key="i">
          <span class="alert__dot" :class="'alert--' + a.level.toLowerCase()" />
          <div class="alert__body">
            <div class="alert__text">{{ a.text }}</div>
            <div class="alert__time">{{ a.time }}</div>
          </div>
        </div>
      </CCard>
    </div>

    <div class="ov__row">
      <!-- 区域对比 -->
      <CCard title="各区域营收（万元）" padding="lg" class="ov__chart">
        <CBarChart :items="regionItems" :height="220" orientation="horizontal" />
      </CCard>
      <!-- 新客/复购 -->
      <CCard title="新客与复购趋势（近6月）" padding="lg" class="ov__chart">
        <div class="legend">
          <span><i class="lg lg--1" />新客</span>
          <span><i class="lg lg--2" />复购客</span>
        </div>
        <CBarChart :items="custItems" :series="['新客','复购客']" :height="220" :show-value="false" />
      </CCard>
    </div>

    <!-- 门店排行 -->
    <CCard title="门店营收排行" padding="none">
      <CTable :columns="rankCols" :rows="rankRows" row-key="id" />
    </CCard>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import CCard from '@/components/CCard.vue'
import CIcon from '@/components/CIcon.vue'
import CBarChart from '@/components/CBarChart.vue'
import CTable from '@/components/CTable.vue'
import { useM1OverviewStore } from '@/stores/m1Overview'

const ov = useM1OverviewStore()
onMounted(() => ov.seed())

const subKpis = computed(() => [
  { label: '本月新客', icon: 'customer', value: ov.kpis.newCustomers.value.toLocaleString() + ov.kpis.newCustomers.unit, delta: ov.kpis.newCustomers.delta },
  { label: '复购率', icon: 'trend-up', value: ov.kpis.repurchase.value + '%', delta: ov.kpis.repurchase.delta },
  { label: '客户满意度', icon: 'customer', value: ov.kpis.satisfaction.value + '%', delta: ov.kpis.satisfaction.delta },
  { label: '治疗人次', icon: 'customer', value: ov.kpis.procedureCount.value.toLocaleString() + ov.kpis.procedureCount.unit, delta: ov.kpis.procedureCount.delta },
  { label: '活跃客户', icon: 'customer', value: ov.kpis.activeCustomers.value.toLocaleString() + ov.kpis.activeCustomers.unit, delta: ov.kpis.activeCustomers.delta },
])

const revItems = computed(() => ov.revenueChart.labels.map((l, i) => ({ label: l, values: [ov.revenueChart.items[0].values[i]] })))
const regionItems = computed(() => ov.regionChart.labels.map((l, i) => ({ label: l, values: [ov.regionChart.items[0].values[i]] })))
const custItems = computed(() => ov.customerChart.labels.map((l, i) => ({ label: l, values: [ov.customerChart.items[0].values[i], ov.customerChart.items[1].values[i]] })))

const rankCols = [
  { key: 'name', label: '门店' },
  { key: 'region', label: '区域' },
  { key: 'revenue', label: '营收(万元)', align: 'right' as const },
  { key: 'growth', label: '环比', align: 'right' as const },
  { key: 'customers', label: '客户数', align: 'right' as const },
  { key: 'satisfaction', label: '满意度', align: 'right' as const },
]
const rankRows = computed(() => ov.storeRanks.map((s) => ({
  id: s.id, name: s.name, region: s.region,
  revenue: s.revenue.toLocaleString(),
  growth: (s.growth >= 0 ? '+' : '') + s.growth + '%',
  customers: s.customers.toLocaleString(),
  satisfaction: s.satisfaction + '%',
})))
</script>

<style scoped>
.ov { display: flex; flex-direction: column; gap: var(--s-lg); }
.ov__kpis { display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: var(--s-md); }
.kpi { background: var(--c-surface); border: 1px solid var(--c-border-light); border-radius: var(--r-xl); padding: var(--s-lg); display: flex; flex-direction: column; align-items: flex-start; text-align: left; min-width: 0; }
.kpi--hero { background: linear-gradient(135deg, var(--c-brand), var(--c-brand-secondary)); border: none; color: #fff; grid-column: span 1; }
.kpi--hero .kpi__l, .kpi--hero .u { color: rgba(255,255,255,.85); }
.kpi--hero .kpi__trend { color: #fff; }
.kpi__l { font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: var(--s-sm); }
.kpi__v { font-size: 28px; font-weight: 700; margin: var(--s-xs) 0; line-height: 1.1; font-variant-numeric: tabular-nums; }
.u { font-size: var(--t-sm); font-weight: 400; margin-left: 4px; color: var(--c-text-3); }
.kpi__trend { display: inline-flex; align-items: center; gap: 4px; font-size: var(--t-xs); font-weight: 600; margin-top: var(--s-sm); }
.kpi__trend.up { color: var(--c-success-fg); }
.kpi__trend.down { color: var(--c-danger-fg); }
.ov__row { display: grid; grid-template-columns: 1fr 360px; gap: var(--s-lg); align-items: start; }
.ov__row:last-of-type { grid-template-columns: 1fr 1fr; }
.ov__chart--wide { min-width: 0; }
.legend { display: flex; gap: var(--s-md); font-size: var(--t-xs); color: var(--c-text-2); margin-bottom: var(--s-sm); }
.legend span { display: inline-flex; align-items: center; gap: var(--s-xs); }
.lg { width: 10px; height: 10px; border-radius: 2px; display: inline-block; }
.lg--1 { background: var(--c-series-1); }
.lg--2 { background: var(--c-series-2); }
.alert { display: flex; gap: var(--s-sm); padding: var(--s-md) var(--s-lg); border-bottom: 1px solid var(--c-border); align-items: flex-start; }
.alert:last-child { border-bottom: none; }
.alert__dot { width: 8px; height: 8px; border-radius: 50%; margin-top: 6px; flex-shrink: 0; }
.alert--high { background: var(--c-danger-fg); }
.alert--med { background: var(--c-warning-fg); }
.alert--low { background: var(--c-brand-secondary); }
.alert__text { font-size: var(--t-sm); line-height: 1.6; }
.alert__time { font-size: 10px; color: var(--c-text-3); margin-top: var(--s-xs); }
@media (max-width: 900px) {
  .ov__row, .ov__row:last-of-type { grid-template-columns: 1fr; }
}
</style>
