<script setup lang="ts">
/* ============================================================
 * M6 经营月报 /m6-monthly
 * 4 KPI（月度营收 netRevenue/营业成本 totalCost/毛利 grossProfit/毛利率 grossRate）
 * 近6月营收-成本-毛利折线趋势 + 门店维度月度对比表
 * 红线：仅镜像聚合确认收入与成本，不直接动账。
 * ============================================================ */
import { computed, onMounted } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CLineChart from '@/components/CLineChart.vue'
import { useFinReportsStore } from '@/stores/finReports'
import { useFinanceCoreStore } from '@/stores/financeCore'

const store = useFinReportsStore()
const core = useFinanceCoreStore()
onMounted(() => store.seed())

const rateWarn = (rate: number) => rate < 0 || rate > 100
const pct = (rate: number) => `${rate.toFixed(1)}%`

const kpis = computed(() => [
  { label: '月度净营收', icon: 'finance', value: money(core.netRevenue), tone: core.netRevenue >= 0 ? ('brand' as const) : ('danger' as const) },
  { label: '营业成本', icon: 'finance', value: money(core.totalCost), tone: 'text' as const },
  { label: '毛利', icon: 'finance', value: money(core.grossProfit), tone: core.grossProfit >= 0 ? ('text' as const) : ('danger' as const) },
  { label: '毛利率', icon: 'finance', value: pct(core.grossRate), tone: rateWarn(core.grossRate) ? ('danger' as const) : ('text' as const) },
])

function money(n: number) {
  return `¥${(n / 10000).toFixed(1)}万`
}

const categories = computed(() => store.monthlyTrend.map((m) => m.month))
const series = computed(() => [
  { name: '营收', values: store.monthlyTrend.map((m) => m.revenue) },
  { name: '成本', values: store.monthlyTrend.map((m) => m.cost) },
  { name: '毛利', values: store.monthlyTrend.map((m) => m.grossProfit) },
])

const totalRevenue = computed(() => store.storeMonthly.reduce((s, x) => s + x.revenue, 0))
const totalCost = computed(() => store.storeMonthly.reduce((s, x) => s + x.cost, 0))
const totalGross = computed(() => totalRevenue.value - totalCost.value)
const totalRate = computed(() => (totalRevenue.value ? Math.round((totalGross.value / totalRevenue.value) * 1000) / 10 : 0))
</script>

<template>
  <div class="ml">
    <div class="ml__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <CCard title="月度营收-成本-毛利趋势（台账确认收入 + /finance/cost 成本口径，元）" padding="lg">
      <CLineChart :categories="categories" :series="series" :height="280" unit="元" />
      <p class="na-line">成本来自 finance 成本聚合（耗材双签终审落账 + 折旧/人工财务录入）；毛利 = 营收 - 成本；横轴按台账/成本实际发生月份生成。</p>
    </CCard>

    <div class="ml__cols">
      <CCard padding="none">
        <template #header>
          <div class="card-head">
            <h3 class="card-head__title">门店营收对比（台账确认收入累计）</h3>
            <CButton variant="secondary" size="sm" v-perm.disable="'finance:export'">
              <CIcon name="export" :size="14" />导出报表
            </CButton>
          </div>
        </template>
        <table class="mtable">
          <thead>
            <tr>
              <th>门店</th>
              <th class="tar">营收（元）</th>
              <th class="tar">成本（元）</th>
              <th class="tar">毛利（元）</th>
              <th class="tar">毛利率</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="s in store.storeMonthly" :key="s.store">
              <td>{{ s.store }}</td>
              <td class="tar">{{ s.revenue.toLocaleString('zh-CN') }}</td>
              <td class="tar">{{ s.cost.toLocaleString('zh-CN') }}</td>
              <td class="tar" :class="{ 'rate-bad': s.grossProfit < 0 }">{{ s.grossProfit.toLocaleString('zh-CN') }}</td>
              <td class="tar" :class="{ na: true, 'rate-bad': rateWarn(s.grossRate) }">{{ pct(s.grossRate) }}</td>
            </tr>
            <tr class="mtable__sum">
              <td>合计</td>
              <td class="tar">{{ totalRevenue.toLocaleString('zh-CN') }}</td>
              <td class="tar">{{ totalCost.toLocaleString('zh-CN') }}</td>
              <td class="tar" :class="{ 'rate-bad': totalGross < 0 }">{{ totalGross.toLocaleString('zh-CN') }}</td>
              <td class="tar" :class="{ 'rate-bad': rateWarn(totalRate) }">{{ pct(totalRate) }}</td>
            </tr>
          </tbody>
        </table>
        <p class="na-line">成本取 /finance/cost 月×店聚合（耗材出库 / 设备折旧 / 报损 / 人工分摊）；毛利率为负或超过 100% 时以红色提示异常；评级列在无真实评级模型前不展示。</p>
      </CCard>

      <CCard title="经营要点" padding="lg" class="ml__note">
        <div class="note">
          <div class="note__item">
            <span class="note__dot note__dot--brand" />
            <span>营收口径：确认收入（直接收银 + 已双签划扣）扣退款净额，月度净营收 <b>{{ money(core.netRevenue) }}</b>，不含未确认预收；本期含一笔未对账退款 ¥1.5 万，净营收暂为负，以银行到账对账后为准。</span>
          </div>
          <div class="note__item">
            <span class="note__dot note__dot--orange" />
            <span>成本口径：耗材出库（双签终审自动落账）+ 设备折旧 + 报损 + 人工分摊，本期营业成本 <b>{{ money(core.totalCost) }}</b>、毛利 <b>{{ money(core.grossProfit) }}</b>、毛利率 <b>{{ pct(core.grossRate) }}</b>；毛利率为负或超 100% 红色提示异常。</span>
          </div>
          <div class="note__item">
            <span class="note__dot note__dot--teal" />
            <span>预收账款余额 <b>{{ money(core.depositBalance) }}</b>，与卡余额相互印证，属负债非收入。</span>
          </div>
          <div class="note__item">
            <span class="note__dot note__dot--warn" />
            <span>Outbox 长短款：长款 <b>¥{{ core.outboxLong }}</b> / 短款 <b>¥{{ core.outboxShort }}</b>，待处置 <b>{{ core.outboxPending }}</b> 笔。</span>
          </div>
        </div>
        <p class="redline">
          <CIcon name="shield" :size="14" />
          经营月报仅镜像聚合确认收入与成本，不直接动账；所有金额以业财一体恒等式校验为准。
        </p>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.ml { display: flex; flex-direction: column; gap: var(--s-lg); }
.ml__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .ml__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }
.card-head { display: flex; align-items: center; justify-content: space-between; gap: var(--s-sm); width: 100%; }
.card-head__title { font-size: var(--t-md); font-weight: 700; margin: 0; }

.ml__cols { display: grid; grid-template-columns: 1fr 320px; gap: var(--s-lg); align-items: start; }

.mtable { width: 100%; border-collapse: collapse; font-size: var(--t-sm); }
.mtable thead th { padding: 12px var(--s-lg); background: var(--c-bg-page); color: var(--c-text); font-weight: 600; font-size: var(--t-xs); text-align: left; border-bottom: 1px solid var(--c-border); white-space: nowrap; }
.mtable tbody td { padding: 14px var(--s-lg); color: var(--c-text-2); border-bottom: 1px solid var(--c-border); vertical-align: middle; }
.mtable tbody tr:last-child td { border-bottom: none; }
.mtable tbody tr:hover { background: var(--c-brand-soft); }
.mtable b { color: var(--c-text); }
.mtable__sum td { background: var(--c-brand-soft); font-weight: 700; color: var(--c-text); }
.tar { text-align: right; font-variant-numeric: tabular-nums; }
.na { color: var(--c-text-4); font-size: var(--t-xs); }
.na-line { margin: var(--s-sm) 0 0; font-size: var(--t-xs); color: var(--c-text-4); line-height: 1.8; }
:deep(.card--pad-none) > .card__body > .na-line { padding: 0 var(--s-lg); }

.note { display: flex; flex-direction: column; gap: var(--s-sm); }
.note__item { display: flex; align-items: flex-start; gap: var(--s-sm); font-size: var(--t-sm); color: var(--c-text-2); line-height: var(--lh-sm); }
.note__item b { color: var(--c-text); font-variant-numeric: tabular-nums; }
.note__dot { width: 8px; height: 8px; border-radius: 50%; margin-top: 6px; flex-shrink: 0; }
.note__dot--brand { background: var(--c-brand); }
.note__dot--orange { background: var(--c-warning-fg); }
.note__dot--teal { background: var(--c-teal); }
.note__dot--warn { background: var(--c-danger-fg); }

.redline { display: flex; align-items: center; gap: 6px; font-size: var(--t-xs); color: var(--c-warning-fg); background: var(--c-warn-soft-bg); padding: var(--s-xs) var(--s-sm); border-radius: var(--r-sm); margin: var(--s-md) 0 0; }

@media (max-width: 1024px) {
  .ml__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .ml__cols { grid-template-columns: 1fr; }
}
</style>
