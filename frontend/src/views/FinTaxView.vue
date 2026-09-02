<script setup lang="ts">
/* ============================================================
 * M6 税务报表 /m6-tax
 * 4 KPI（应税收入/销项税额/进项抵扣/应纳税额）+ 税种明细表
 * 红线：税务申报辅助，单向镜像税控数据，不碰资金。
 * ============================================================ */
import { computed, onMounted } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CDonutChart from '@/components/CDonutChart.vue'
import { useFinReportsStore } from '@/stores/finReports'

const store = useFinReportsStore()
onMounted(() => store.seed())

const kpis = computed(() => [
  { label: '应税收入', icon: 'finance', value: money(store.taxableRevenue), tone: 'brand' as const },
  { label: '销项税额', icon: 'finance', value: money(store.outputTax), tone: 'orange' as const },
  { label: '进项抵扣', icon: 'finance', value: money(store.inputDeduct), tone: 'teal' as const },
  { label: '应纳税额', icon: 'finance', value: money(store.taxPayable), tone: 'danger' as const },
])

function money(n: number) {
  return `¥${n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

const donutData = computed(() =>
  store.taxRows.map((r, i) => ({ label: r.taxName, value: r.amount, color: `var(--c-series-${(i % 8) + 1})` })),
)

const totalTax = computed(() => store.taxRows.reduce((s, r) => s + r.amount, 0))
</script>

<template>
  <div class="tax">
    <div class="tax__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <div class="tax__body">
      <CCard class="tax__chart" title="税种构成" padding="lg">
        <CDonutChart :data="donutData" :size="180" :thickness="22" center-label="税额合计" :center-value="money(totalTax)" />
      </CCard>

      <CCard class="tax__table" padding="none">
        <template #header>
          <div class="card-head">
            <h3 class="card-head__title">税种明细（2026-08）</h3>
            <CButton variant="secondary" size="sm" v-perm.disable="'finance:export'">
              <CIcon name="export" :size="14" />导出报表
            </CButton>
          </div>
        </template>
        <table class="ttable">
          <thead>
            <tr>
              <th>税种</th>
              <th class="tar">税基（元）</th>
              <th class="tar">税率</th>
              <th class="tar">税额（元）</th>
              <th>申报状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in store.taxRows" :key="r.id">
              <td>{{ r.taxName }}</td>
              <td class="tar">{{ r.base.toLocaleString('zh-CN', { minimumFractionDigits: 2 }) }}</td>
              <td class="tar">{{ (r.rate * 100).toFixed(0) }}%</td>
              <td class="tar"><b>{{ r.amount.toLocaleString('zh-CN', { minimumFractionDigits: 2 }) }}</b></td>
              <td><CStatusPill status="info">待申报</CStatusPill></td>
            </tr>
            <tr class="ttable__sum">
              <td>合计</td>
              <td class="tar">{{ store.taxableRevenue.toLocaleString('zh-CN', { minimumFractionDigits: 2 }) }}</td>
              <td class="tar">—</td>
              <td class="tar"><b>{{ totalTax.toLocaleString('zh-CN', { minimumFractionDigits: 2 }) }}</b></td>
              <td></td>
            </tr>
          </tbody>
        </table>
      </CCard>

      <CCard class="tax__calc" title="应纳税额计算" padding="lg">
        <div class="calc-row"><span>销项税额合计</span><b>{{ money(store.outputTax) }}</b></div>
        <div class="calc-row"><span>减：进项税额抵扣</span><b class="is-teal">−{{ money(store.inputDeduct) }}</b></div>
        <div class="calc-row calc-row--sum"><span>本期应纳税额</span><b>{{ money(store.taxPayable) }}</b></div>
        <p class="redline">
          <CIcon name="shield" :size="14" />
          本页为税务申报辅助报表，数据单向镜像自税控/业务系统，申报与缴款以电子税务局为准，本系统不碰资金。
        </p>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.tax { display: flex; flex-direction: column; gap: var(--s-lg); }
.tax__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .tax__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }
.card-head { display: flex; align-items: center; justify-content: space-between; gap: var(--s-sm); width: 100%; }
.card-head__title { font-size: var(--t-md); font-weight: 700; margin: 0; }

.tax__body { display: grid; grid-template-columns: 320px 1fr; grid-template-areas: 'chart table' 'calc table'; gap: var(--s-lg); align-items: start; }
.tax__chart { grid-area: chart; }
.tax__table { grid-area: table; min-width: 0; }
.tax__calc { grid-area: calc; }

.ttable { width: 100%; border-collapse: collapse; font-size: var(--t-sm); }
.ttable thead th { padding: 12px var(--s-lg); background: var(--c-bg-page); color: var(--c-text); font-weight: 600; font-size: var(--t-xs); text-align: left; border-bottom: 1px solid var(--c-border); white-space: nowrap; }
.ttable tbody td { padding: 14px var(--s-lg); color: var(--c-text-2); border-bottom: 1px solid var(--c-border); vertical-align: middle; }
.ttable tbody tr:last-child td { border-bottom: none; }
.ttable tbody tr:hover { background: var(--c-brand-soft); }
.ttable b { color: var(--c-text); font-variant-numeric: tabular-nums; }
.ttable__sum td { background: var(--c-brand-soft); font-weight: 700; color: var(--c-text); }
.tar { text-align: right; font-variant-numeric: tabular-nums; }

.calc-row { display: flex; justify-content: space-between; align-items: center; padding: var(--s-sm) 0; border-bottom: 1px solid var(--c-border-light); font-size: var(--t-sm); color: var(--c-text-2); }
.calc-row b { color: var(--c-text); font-variant-numeric: tabular-nums; }
.calc-row b.is-teal { color: var(--c-teal-fg); }
.calc-row--sum { border-bottom: none; padding-top: var(--s-md); font-weight: 700; }
.calc-row--sum b { color: var(--c-danger-fg); font-size: var(--t-md); }

.redline { display: flex; align-items: center; gap: 6px; font-size: var(--t-xs); color: var(--c-warning-fg); background: var(--c-warn-soft-bg); padding: var(--s-xs) var(--s-sm); border-radius: var(--r-sm); margin: var(--s-md) 0 0; }

@media (max-width: 1024px) {
  .tax__body { grid-template-columns: 1fr; grid-template-areas: 'chart' 'table' 'calc'; }
}
</style>
