<script setup lang="ts">
/* ============================================================
 * M6 资金日报 /m6-cash-daily
 * 4 KPI（本日收入/本日支出/本日净现金流/期末余额）
 * 近7日收支柱状图 + 当日按渠道收支明细表
 * 红线：仅镜像聚合收银/渠道流水，不直接动账。
 * ============================================================ */
import { computed, onMounted } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CBarChart from '@/components/CBarChart.vue'
import { useFinReportsStore } from '@/stores/finReports'

const store = useFinReportsStore()
onMounted(() => store.seed())

const kpis = computed(() => [
  { label: '本日收入', icon: 'finance', value: money(store.todayIncome), tone: 'brand' as const },
  { label: '本日支出', icon: 'finance', value: money(store.todayExpense), tone: 'orange' as const },
  { label: '本日净现金流', icon: 'finance', value: money(store.todayNet), tone: store.todayNet >= 0 ? ('success' as const) : ('danger' as const) },
  { label: '期末余额（预收）', icon: 'finance', value: money(store.endBalance), tone: 'teal' as const },
])

function money(n: number) {
  return `¥${n.toLocaleString('zh-CN', { maximumFractionDigits: 2 })}`
}

const chartItems = computed(() =>
  store.dailyFlows.map((d) => ({ label: d.date, values: [d.income, d.expense] })),
)
const totalIncome = computed(() => store.channelFlows.reduce((s, c) => s + c.income, 0))
const totalExpense = computed(() => store.channelFlows.reduce((s, c) => s + c.expense, 0))
</script>

<template>
  <div class="cd">
    <div class="cd__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <CCard title="近 7 日收支（元）" padding="lg">
      <CBarChart :items="chartItems" :series="['收入', '支出']" :height="260" unit="元" />
    </CCard>

    <CCard padding="none">
      <template #header>
        <div class="card-head">
          <h3 class="card-head__title">当日渠道收支明细（2026-08-17）</h3>
          <CButton variant="secondary" size="sm" v-perm.disable="'finance:export'">
            <CIcon name="export" :size="14" />导出报表
          </CButton>
        </div>
      </template>
      <table class="ctable">
        <thead>
          <tr>
            <th>渠道</th>
            <th class="tar">收入（元）</th>
            <th class="tar">支出（元）</th>
            <th class="tar">净额（元）</th>
            <th>状态</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="c in store.channelFlows" :key="c.channel">
            <td>{{ c.channel }}</td>
            <td class="tar">{{ c.income > 0 ? c.income.toLocaleString('zh-CN') : '—' }}</td>
            <td class="tar">{{ c.expense > 0 ? c.expense.toLocaleString('zh-CN') : '—' }}</td>
            <td class="tar" :class="c.income - c.expense >= 0 ? 'is-in' : 'is-out'">
              {{ c.income - c.expense >= 0 ? '+' : '' }}{{ (c.income - c.expense).toLocaleString('zh-CN') }}
            </td>
            <td><CStatusPill status="success">已对账</CStatusPill></td>
          </tr>
          <tr class="ctable__sum">
            <td>合计</td>
            <td class="tar">{{ totalIncome.toLocaleString('zh-CN') }}</td>
            <td class="tar">{{ totalExpense.toLocaleString('zh-CN') }}</td>
            <td class="tar">{{ (totalIncome - totalExpense).toLocaleString('zh-CN') }}</td>
            <td></td>
          </tr>
        </tbody>
      </table>
      <p class="redline">
        <CIcon name="shield" :size="14" />
        资金日报仅镜像聚合收银与支付渠道流水，真实资金以银行到账为准；本系统不直接划付。
      </p>
    </CCard>
  </div>
</template>

<style scoped>
.cd { display: flex; flex-direction: column; gap: var(--s-lg); }
.cd__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .cd__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }
.card-head { display: flex; align-items: center; justify-content: space-between; gap: var(--s-sm); width: 100%; }
.card-head__title { font-size: var(--t-md); font-weight: 700; margin: 0; }

.ctable { width: 100%; border-collapse: collapse; font-size: var(--t-sm); }
.ctable thead th { padding: 12px var(--s-lg); background: var(--c-bg-page); color: var(--c-text); font-weight: 600; font-size: var(--t-xs); text-align: left; border-bottom: 1px solid var(--c-border); white-space: nowrap; }
.ctable tbody td { padding: 14px var(--s-lg); color: var(--c-text-2); border-bottom: 1px solid var(--c-border); vertical-align: middle; }
.ctable tbody tr:last-child td { border-bottom: none; }
.ctable tbody tr:hover { background: var(--c-brand-soft); }
.ctable__sum td { background: var(--c-brand-soft); font-weight: 700; color: var(--c-text); }
.tar { text-align: right; font-variant-numeric: tabular-nums; }
td.is-in { color: var(--c-success-fg); font-weight: 600; }
td.is-out { color: var(--c-danger-fg); font-weight: 600; }

.redline { display: flex; align-items: center; gap: 6px; font-size: var(--t-xs); color: var(--c-warning-fg); background: var(--c-warn-soft-bg); padding: var(--s-sm) var(--s-lg); margin: 0; border-top: 1px solid var(--c-border-light); }
</style>
