<script setup lang="ts">
/* ============================================================
 * 客户洞察报告 /m3-insight（M3-19）
 * 客户结构/复购/流失多维报告，纯 CSS 图表。
 * ============================================================ */
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import { useInsightStore } from '@/stores/insight'

const store = useInsightStore()
const maxTrend = Math.max(...store.trend.map(t => t.activeCustomers))

const kpis = [
  { label: '总会员数', icon: 'customer', value: store.summary.totalCustomers.toLocaleString(), tone: 'brand' as const },
  { label: '期内新增', icon: 'customer', value: `+${store.summary.newThisPeriod.toLocaleString()}`, tone: 'success' as const },
  { label: '复购率', icon: 'trend-up', value: `${store.summary.repurchaseRate}%`, tone: 'teal' as const },
  { label: '流失率', icon: 'trend-down', value: `${store.summary.churnRate}%`, tone: 'warning' as const },
]

function pct(n: number, max: number) { return Math.round((n / max) * 100) }
</script>

<template>
  <div class="ins">
    <div class="ins__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <!-- AI 洞察 -->
    <CCard class="ins__ai" padding="lg">
      <template #header>
        <h3 class="card-title"><CIcon name="marketing" :size="16" /> 智能洞察建议</h3>
        <CButton variant="primary" size="sm" v-perm.disable="'insight:export'">
          <CIcon name="export" :size="14" />导出报告
        </CButton>
      </template>
      <div class="ai-grid">
        <div v-for="(t, i) in store.topInsights" :key="i" class="ai-card" :class="`ai-card--${t.tone}`">
          <CIcon :name="(t.icon as any)" :size="18" class="ai-icon" />
          <div>
            <div class="ai-title">{{ t.title }}</div>
            <div class="ai-desc">{{ t.desc }}</div>
          </div>
        </div>
      </div>
    </CCard>

    <div class="ins__grid">
      <!-- 活跃/新增趋势 -->
      <CCard class="ins__trend" padding="lg">
        <template #header>
          <h3 class="card-title">客户活跃趋势（近 6 个月）</h3>
          <span class="card-hint">活跃 / 新增</span>
        </template>
        <div class="trend">
          <div v-for="t in store.trend" :key="t.month" class="trend__col">
            <div class="trend__bars">
              <div class="bar bar--active" :style="{ height: pct(t.activeCustomers, maxTrend) + '%' }" :title="`活跃 ${t.activeCustomers}`" />
              <div class="bar bar--new" :style="{ height: pct(t.newCustomers, maxTrend) + '%' }" :title="`新增 ${t.newCustomers}`" />
            </div>
            <div class="trend__month">{{ t.month }}</div>
          </div>
        </div>
        <div class="legend">
          <span><i class="dot dot--active" />活跃客户</span>
          <span><i class="dot dot--new" />新增客户</span>
        </div>
      </CCard>

      <!-- 等级分布 -->
      <CCard class="ins__level" padding="lg">
        <template #header><h3 class="card-title">会员等级分布</h3><span class="card-hint">共 {{ store.summary.totalCustomers.toLocaleString() }} 人</span></template>
        <div class="levels">
          <div v-for="l in store.levelDist" :key="l.level" class="level-row">
            <span class="level-name"><i class="level-dot" :style="{ background: l.color }" />{{ l.level }}</span>
            <div class="level-bar"><div class="level-fill" :style="{ width: l.percent + '%', background: l.color }" /></div>
            <span class="level-count">{{ l.count.toLocaleString() }}</span>
            <span class="level-pct">{{ l.percent }}%</span>
          </div>
        </div>
      </CCard>
    </div>

    <div class="ins__grid ins__grid--btm">
      <!-- 渠道分布 -->
      <CCard class="ins__channel" padding="lg">
        <template #header><h3 class="card-title">获客渠道分布</h3></template>
        <div class="channels">
          <div v-for="c in store.channelDist" :key="c.channel" class="ch-row">
            <span class="ch-name">{{ c.channel }}</span>
            <div class="ch-bar"><div class="ch-fill" :style="{ width: c.percent + '%' }" /></div>
            <span class="ch-count">{{ c.count.toLocaleString() }}</span>
            <span class="ch-pct">{{ c.percent }}%</span>
          </div>
        </div>
      </CCard>

      <!-- 高复购项目 -->
      <CCard class="ins__repur" padding="lg">
        <template #header><h3 class="card-title">高复购项目 TOP5</h3><span class="card-hint">复购率 / 贡献营收</span></template>
        <div class="repur-list">
          <div v-for="(r, i) in store.repurchaseItems" :key="r.name" class="repur-row">
            <span class="repur-rank">{{ i + 1 }}</span>
            <div class="repur-main">
              <div class="repur-name">{{ r.name }}</div>
              <div class="repur-bar"><div class="repur-fill" :style="{ width: r.rate + '%' }" /></div>
            </div>
            <div class="repur-meta">
              <span class="repur-rate">{{ r.rate }}%</span>
              <span class="repur-amt">¥{{ (r.amount / 10000).toFixed(1) }}万</span>
            </div>
          </div>
        </div>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.ins { display: flex; flex-direction: column; gap: var(--s-lg); }
.ins__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .ins__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }
.card-title { font-size: var(--t-md); font-weight: 700; margin: 0; display: flex; align-items: center; gap: var(--s-xs); color: var(--c-text); }
.card-hint { font-size: var(--t-xs); color: var(--c-text-3); }

.ai-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: var(--s-md); }
.ai-card { display: flex; gap: var(--s-sm); padding: var(--s-md); border-radius: var(--r-md); border: 1px solid var(--c-border-light); }
.ai-card--success { background: var(--c-success-bg); }
.ai-card--warning { background: var(--c-warning-bg); }
.ai-card--brand { background: var(--c-brand-soft); }
.ai-card--danger { background: var(--c-danger-bg); }
.ai-icon { flex-shrink: 0; margin-top: 2px; color: var(--c-text-2); }
.ai-title { font-weight: 600; font-size: var(--t-sm); color: var(--c-text); margin-bottom: 2px; }
.ai-desc { font-size: var(--t-xs); color: var(--c-text-2); line-height: var(--lh-md); }

.ins__grid { display: grid; grid-template-columns: 1.4fr 1fr; gap: var(--s-lg); align-items: start; }
.ins__grid--btm { grid-template-columns: 1fr 1fr; }

.trend { display: flex; align-items: flex-end; gap: var(--s-md); height: 220px; padding: var(--s-md) 0; }
.trend__col { flex: 1; display: flex; flex-direction: column; align-items: center; height: 100%; }
.trend__bars { flex: 1; display: flex; align-items: flex-end; justify-content: center; gap: 4px; width: 100%; }
.bar { width: 40%; max-width: 24px; border-radius: var(--r-sm) var(--r-sm) 0 0; min-height: 4px; transition: height .3s; }
.bar--active { background: linear-gradient(180deg, var(--c-brand), var(--c-brand-2, #6d8bff)); }
.bar--new { background: var(--c-teal, #14b8a6); opacity: .8; }
.trend__month { font-size: var(--t-xs); color: var(--c-text-3); margin-top: var(--s-xs); }
.legend { display: flex; gap: var(--s-md); font-size: var(--t-xs); color: var(--c-text-3); justify-content: center; }
.dot { display: inline-block; width: 10px; height: 10px; border-radius: 2px; margin-right: 4px; vertical-align: middle; }
.dot--active { background: var(--c-brand); }
.dot--new { background: var(--c-teal, #14b8a6); }

.levels, .channels { display: flex; flex-direction: column; gap: var(--s-md); }
.level-row, .ch-row { display: grid; grid-template-columns: 90px 1fr 70px 40px; align-items: center; gap: var(--s-sm); font-size: var(--t-sm); }
.level-name { display: flex; align-items: center; gap: 6px; color: var(--c-text-2); }
.level-dot { width: 10px; height: 10px; border-radius: 50%; display: inline-block; }
.level-bar, .ch-bar { height: 8px; background: var(--c-surface-muted, #f0f2f5); border-radius: var(--r-capsule); overflow: hidden; }
.level-fill, .ch-fill { height: 100%; border-radius: var(--r-capsule); transition: width .3s; }
.ch-fill { background: linear-gradient(90deg, var(--c-brand), var(--c-teal, #14b8a6)); }
.level-count, .ch-count { text-align: right; color: var(--c-text); font-weight: 600; font-variant-numeric: tabular-nums; }
.level-pct, .ch-pct { text-align: right; color: var(--c-text-3); font-size: var(--t-xs); }
.ch-name { color: var(--c-text-2); }

.repur-list { display: flex; flex-direction: column; gap: var(--s-md); }
.repur-row { display: flex; align-items: center; gap: var(--s-sm); }
.repur-rank { width: 22px; height: 22px; border-radius: 50%; background: var(--c-brand-soft); color: var(--c-brand); font-weight: 700; font-size: var(--t-xs); display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.repur-main { flex: 1; }
.repur-name { font-size: var(--t-sm); color: var(--c-text); margin-bottom: 4px; }
.repur-bar { height: 6px; background: var(--c-surface-muted, #f0f2f5); border-radius: var(--r-capsule); overflow: hidden; }
.repur-fill { height: 100%; background: var(--c-success-fg); border-radius: var(--r-capsule); }
.repur-meta { display: flex; flex-direction: column; align-items: flex-end; gap: 2px; min-width: 70px; }
.repur-rate { font-size: var(--t-sm); font-weight: 700; color: var(--c-success-fg); }
.repur-amt { font-size: var(--t-xs); color: var(--c-text-3); }

@media (max-width: 1024px) {
  .ins__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .ai-grid { grid-template-columns: 1fr; }
  .ins__grid, .ins__grid--btm { grid-template-columns: 1fr; }
}
</style>
