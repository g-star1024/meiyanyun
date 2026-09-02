<script setup lang="ts">
/* ============================================================
 * M5-06 投放 ROI /m5-roi
 * 4 KPI（总投放成本/总线索/总成交/综合 ROI）
 * 渠道投放对比表 + ROI 条形图 + 成本占比环形图
 * 归因模型切换 + 活动维度 ROI
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CKpi from '@/components/CKpi.vue'
import CIcon from '@/components/CIcon.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CBarChart from '@/components/CBarChart.vue'
import CDonutChart from '@/components/CDonutChart.vue'
import CSegmented from '@/components/CSegmented.vue'
import { useM5RoiStore, ATTR_MODEL_LABEL, ATTR_MODEL_DESC, type AttributionModel } from '@/stores/m5Roi'
import { useActivityStore } from '@/stores/activity'
import { useAuthStore } from '@/stores/auth'

const store = useM5RoiStore()
const activity = useActivityStore()
const auth = useAuthStore()
onMounted(() => store.seed())

const kpis = computed(() => [
  { label: '总投放成本', icon: 'marketing', value: `¥${(store.kpis.totalAdCost / 10000).toFixed(1)}万`, tone: 'brand' as const },
  { label: '总线索', icon: 'customer', value: store.kpis.totalLeads.toLocaleString('zh-CN'), tone: 'text' as const },
  { label: '总成交', icon: 'finance', value: store.kpis.totalDeals.toLocaleString('zh-CN'), tone: 'teal' as const },
  {
    label: '综合 ROI', icon: 'trend-up',
    value: store.kpis.overallRoi.toFixed(1),
    tone: store.kpis.overallRoi >= 3 ? ('success' as const) : store.kpis.overallRoi >= 1 ? ('warning' as const) : ('danger' as const),
  },
])

function money(n: number) {
  return `¥${n.toLocaleString('zh-CN')}`
}

function roiTone(r: number): 'success' | 'warning' | 'danger' {
  if (r >= 3) return 'success'
  if (r >= 1) return 'warning'
  return 'danger'
}
function roiPill(r: number): 'success' | 'warning' | 'danger' {
  return roiTone(r)
}

const modelOptions = (Object.keys(ATTR_MODEL_LABEL) as AttributionModel[]).map((v) => ({
  value: v,
  label: ATTR_MODEL_LABEL[v],
}))

const exported = ref(false)
function onExport() {
  if (!auth.can('marketing:export')) return
  activity.log(auth.user?.name ?? '系统', '导出投放 ROI 报表', 'm5-roi')
  exported.value = true
  setTimeout(() => (exported.value = false), 1800)
}
</script>

<template>
  <div class="ri">
    <div class="ri__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <!-- 渠道投放对比表 -->
    <CCard class="ri__table" padding="none">
      <template #header>
        <div class="ri__card-head">
          <span>各渠道投放对比</span>
          <CButton variant="secondary" size="sm" v-perm.disable="'marketing:export'" @click="onExport">
            <CIcon name="export" :size="14" />{{ exported ? '已导出' : '导出报表' }}
          </CButton>
        </div>
      </template>
      <div class="ri-table">
        <div class="ri-table__head">
          <div class="ri-table__cell ri-table__cell--name">渠道</div>
          <div class="ri-table__cell ri-table__cell--num">广告成本</div>
          <div class="ri-table__cell ri-table__cell--num">线索</div>
          <div class="ri-table__cell ri-table__cell--num">成交</div>
          <div class="ri-table__cell ri-table__cell--num">营收</div>
          <div class="ri-table__cell ri-table__cell--num">ROI</div>
          <div class="ri-table__cell ri-table__cell--num">佣金</div>
        </div>
        <div
          v-for="r in store.channelRows"
          :key="r.key"
          class="ri-table__row"
        >
          <div class="ri-table__cell ri-table__cell--name">
            <span class="ri-table__dot" :class="`ri-table__dot--${r.key}`" />
            <span class="ri-table__name">{{ r.name }}</span>
            <CStatusPill v-if="!r.connected" status="disabled">未接入</CStatusPill>
          </div>
          <div class="ri-table__cell ri-table__cell--num">{{ r.adCost ? money(r.adCost) : '—' }}</div>
          <div class="ri-table__cell ri-table__cell--num">{{ r.leads }}</div>
          <div class="ri-table__cell ri-table__cell--num">{{ r.deals }}</div>
          <div class="ri-table__cell ri-table__cell--num">{{ money(r.revenue) }}</div>
          <div class="ri-table__cell ri-table__cell--num">
            <div class="ri-roi">
              <span class="ri-roi__num" :class="`ri-roi__num--${roiTone(r.roi)}`">{{ r.roi ? r.roi.toFixed(1) : '—' }}</span>
              <CStatusPill v-if="r.roi > 0" :status="roiPill(r.roi)" dot />
            </div>
          </div>
          <div class="ri-table__cell ri-table__cell--num">{{ r.commission ? money(r.commission) : '—' }}</div>
        </div>
      </div>
    </CCard>

    <!-- 图表区 -->
    <div class="ri__charts">
      <CCard class="ri__chart" title="各渠道 ROI 对比">
        <CBarChart
          :items="store.roiBarItems"
          :series="['ROI']"
          orientation="horizontal"
          :height="260"
          :show-value="true"
        />
      </CCard>
      <CCard class="ri__chart" title="渠道广告成本占比">
        <CDonutChart
          :data="store.costDonutData"
          :size="180"
          :center-value="`¥${(store.kpis.totalAdCost / 10000).toFixed(1)}万`"
          center-label="总成本"
        />
      </CCard>
    </div>

    <!-- 归因模型 + 活动维度 ROI -->
    <div class="ri__bottom">
      <CCard class="ri__attr" title="归因模型">
        <template #header>
          <div class="ri__card-head">
            <h3 class="ri__card-title">归因模型</h3>
            <CSegmented v-model="store.model" :options="modelOptions" size="sm" />
          </div>
        </template>
        <div class="ri-attr">
          <div class="ri-attr__desc">
            <CIcon name="alert" :size="14" class="ri-attr__icon" />
            <span>{{ ATTR_MODEL_DESC[store.model] }}</span>
          </div>
          <div class="ri-attr__list">
            <div v-for="r in store.attributionRows" :key="r.key" class="ri-attr__row">
              <span class="ri-attr__name">{{ r.name }}</span>
              <div class="ri-attr__bar">
                <div class="ri-attr__fill" :style="{ width: (r.weight * 100) + '%' }" />
              </div>
              <span class="ri-attr__pct">{{ (r.weight * 100).toFixed(0) }}%</span>
              <span class="ri-attr__amt">{{ money(r.attributedRevenue) }}</span>
            </div>
          </div>
        </div>
      </CCard>

      <CCard class="ri__camp" title="活动维度 ROI" padding="none">
        <div class="ri-camp">
          <div v-if="store.campaignRows.length === 0" class="ri-camp__empty">暂无活动投放数据</div>
          <div v-for="c in store.campaignRows" :key="c.id" class="ri-camp__row">
            <div class="ri-camp__main">
              <div class="ri-camp__name">{{ c.name }}</div>
              <div class="ri-camp__meta">{{ c.type }} · 新客 {{ c.newCustomers }} · {{ c.status }}</div>
            </div>
            <div class="ri-camp__nums">
              <div class="ri-camp__num">
                <span class="ri-camp__label">投放</span>
                <span class="ri-camp__val">{{ money(c.spent) }}</span>
              </div>
              <div class="ri-camp__num">
                <span class="ri-camp__label">成交</span>
                <span class="ri-camp__val">{{ money(c.actualAmount) }}</span>
              </div>
              <div class="ri-camp__num">
                <span class="ri-camp__label">ROI</span>
                <span class="ri-camp__val" :class="`ri-roi__num--${roiTone(c.roi)}`">{{ c.roi.toFixed(1) }}</span>
              </div>
            </div>
          </div>
        </div>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.ri { display: flex; flex-direction: column; gap: var(--s-lg); }
.ri__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .ri__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
.ri__card-head { display: flex; align-items: center; justify-content: space-between; width: 100%; gap: var(--s-sm); font-size: var(--t-md); font-weight: 700; flex-wrap: wrap; }
:deep(.ckpi) { min-width: 0; }

/* 表格 */
.ri-table { width: 100%; }
.ri-table__head, .ri-table__row {
  display: grid;
  grid-template-columns: 1.4fr 1fr 0.8fr 0.8fr 1.1fr 1fr 1fr;
  align-items: center;
}
.ri-table__head {
  padding: var(--s-sm) var(--s-lg);
  background: var(--c-bg-page);
  font-size: var(--t-xs);
  color: var(--c-text-3);
  font-weight: 600;
  border-bottom: 1px solid var(--c-border);
}
.ri-table__row {
  padding: var(--s-md) var(--s-lg);
  border-bottom: 1px solid var(--c-border-light);
  font-size: var(--t-sm);
  color: var(--c-text-2);
}
.ri-table__row:last-child { border-bottom: none; }
.ri-table__cell { min-width: 0; }
.ri-table__cell--name { display: flex; align-items: center; gap: var(--s-xs); color: var(--c-text); font-weight: 600; }
.ri-table__cell--num { text-align: right; font-variant-numeric: tabular-nums; }
.ri-table__dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.ri-table__dot--meituan { background: var(--c-series-4); }
.ri-table__dot--douyin { background: var(--c-series-1); }
.ri-table__dot--xiaohongshu { background: var(--c-series-6); }
.ri-table__dot--dianping { background: var(--c-series-3); }
.ri-table__dot--xinyang { background: var(--c-series-5); }
.ri-table__dot--referral { background: var(--c-series-7); }
.ri-table__dot--wecom { background: var(--c-series-8); }

.ri-roi { display: inline-flex; align-items: center; gap: var(--s-xs); justify-content: flex-end; }
.ri-roi__num { font-weight: 700; font-variant-numeric: tabular-nums; }
.ri-roi__num--success { color: var(--c-success-fg); }
.ri-roi__num--warning { color: var(--c-warning-fg); }
.ri-roi__num--danger { color: var(--c-danger-fg); }

/* 图表 */
.ri__charts { display: grid; grid-template-columns: 1.2fr 1fr; gap: var(--s-lg); align-items: stretch; }
.ri__chart { min-width: 0; }
.ri__chart :deep(.card__body) { padding: var(--s-lg); }

/* 底部 */
.ri__bottom { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-lg); align-items: start; }
.ri__card-head { display: flex; align-items: center; justify-content: space-between; width: 100%; gap: var(--s-md); }
.ri__card-title { font-size: var(--t-md); font-weight: 700; margin: 0; }

.ri-attr { display: flex; flex-direction: column; gap: var(--s-md); }
.ri-attr__desc {
  display: flex; gap: var(--s-xs); align-items: flex-start;
  background: var(--c-bg-right); border-radius: var(--r-md);
  padding: var(--s-sm) var(--s-md); font-size: var(--t-xs); color: var(--c-text-2); line-height: var(--lh-sm);
}
.ri-attr__icon { color: var(--c-brand); margin-top: 2px; flex-shrink: 0; }
.ri-attr__list { display: flex; flex-direction: column; gap: var(--s-sm); }
.ri-attr__row { display: grid; grid-template-columns: 80px 1fr 48px 100px; align-items: center; gap: var(--s-sm); font-size: var(--t-sm); }
.ri-attr__name { color: var(--c-text-2); }
.ri-attr__bar { height: 8px; background: var(--c-chart-track); border-radius: 999px; overflow: hidden; }
.ri-attr__fill { height: 100%; background: var(--c-brand); border-radius: 999px; transition: width .3s ease; }
.ri-attr__pct { text-align: right; color: var(--c-text-3); font-variant-numeric: tabular-nums; }
.ri-attr__amt { text-align: right; color: var(--c-text); font-weight: 600; font-variant-numeric: tabular-nums; }

.ri-camp { display: flex; flex-direction: column; }
.ri-camp__empty { padding: var(--s-xl) var(--s-lg); text-align: center; color: var(--c-text-3); font-size: var(--t-sm); }
.ri-camp__row {
  display: flex; align-items: center; justify-content: space-between; gap: var(--s-md);
  padding: var(--s-md) var(--s-lg); border-bottom: 1px solid var(--c-border-light);
}
.ri-camp__row:last-child { border-bottom: none; }
.ri-camp__main { min-width: 0; flex: 1; }
.ri-camp__name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ri-camp__meta { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }
.ri-camp__nums { display: flex; gap: var(--s-lg); flex-shrink: 0; }
.ri-camp__num { display: flex; flex-direction: column; align-items: flex-end; gap: 2px; }
.ri-camp__label { font-size: var(--t-xs); color: var(--c-text-3); }
.ri-camp__val { font-size: var(--t-sm); font-weight: 700; color: var(--c-text); font-variant-numeric: tabular-nums; }

@media (max-width: 1024px) {
  .ri__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .ri__charts, .ri__bottom { grid-template-columns: 1fr; }
  .ri-table__head, .ri-table__row { grid-template-columns: 1.4fr 1fr 0.8fr 0.8fr 1.1fr 1fr 1fr; min-width: 720px; }
  .ri-table { overflow-x: auto; }
}
</style>
