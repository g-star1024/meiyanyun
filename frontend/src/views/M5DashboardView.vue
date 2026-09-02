<script setup lang="ts">
/* ============================================================
 * M5-14 营销数据看板 /m5-dashboard
 * 4 KPI（累计触达/转化率/综合 ROI/活动营收）
 * 近 6 月趋势 + 渠道成交对比 + 活动类型分布
 * 渠道排行榜 + 转化漏斗
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CKpi from '@/components/CKpi.vue'
import CIcon from '@/components/CIcon.vue'
import CLineChart from '@/components/CLineChart.vue'
import CBarChart from '@/components/CBarChart.vue'
import CDonutChart from '@/components/CDonutChart.vue'
import { useM5DashStore } from '@/stores/m5Dash'
import { useActivityStore } from '@/stores/activity'
import { useAuthStore } from '@/stores/auth'

const store = useM5DashStore()
const activity = useActivityStore()
const auth = useAuthStore()
onMounted(() => store.seed())

const kpis = computed(() => [
  {
    label: '累计触达', icon: 'marketing',
    value: store.kpis.totalReach.toLocaleString('zh-CN'),
    tone: 'brand' as const,
    trend: `推送 ${store.pushEffectiveness.sent} 批次`,
  },
  {
    label: '转化率', icon: 'trend-up',
    value: `${store.kpis.conversionRate}%`,
    tone: store.kpis.conversionRate >= 3 ? ('success' as const) : ('warning' as const),
  },
  {
    label: '综合 ROI', icon: 'trend-up',
    value: store.kpis.overallRoi.toFixed(1),
    tone: store.kpis.overallRoi >= 3 ? ('success' as const) : store.kpis.overallRoi >= 1 ? ('warning' as const) : ('danger' as const),
  },
  {
    label: '活动带来营收', icon: 'marketing',
    value: `¥${(store.kpis.campaignRevenue / 10000).toFixed(1)}万`,
    tone: 'teal' as const,
  },
])

function money(n: number) {
  return `¥${n.toLocaleString('zh-CN')}`
}
function big(n: number) {
  return n >= 10000 ? `${(n / 10000).toFixed(1)}万` : n.toLocaleString('zh-CN')
}

const exported = ref(false)
const subscribed = ref(false)
function onExport() {
  if (!auth.can('marketing:export')) return
  activity.log(auth.user?.name ?? '系统', '导出营销数据看板报告', 'm5-dashboard')
  exported.value = true
  setTimeout(() => (exported.value = false), 1800)
}
function onSubscribe() {
  activity.log(auth.user?.name ?? '系统', subscribed.value ? '取消订阅营销看板周报' : '订阅营销看板周报', 'm5-dashboard')
  subscribed.value = !subscribed.value
}

// 漏斗最大宽度用于收窄
const funnelMax = computed(() => Math.max(1, ...store.funnel.map((f) => f.value)))
</script>

<template>
  <div class="db">
    <div class="db__head">
      <CKpi
        v-for="k in kpis"
        :key="k.label"
        :label="k.label"
        :value="k.value"
        :tone="k.tone"
        :trend="k.trend" :icon="k.icon" />
    </div>

    <!-- 趋势 + 渠道成交 -->
    <div class="db__row">
      <CCard class="db__trend">
        <template #header>
          <div class="db__card-head">
            <span>近 6 月触达 / 转化趋势</span>
            <div class="db__card-head-right">
              <CButton variant="ghost" size="sm" @click="onSubscribe">
                <CIcon :name="subscribed ? 'check' : 'bell'" :size="14" />
                {{ subscribed ? '已订阅' : '订阅周报' }}
              </CButton>
              <CButton variant="secondary" size="sm" v-perm.disable="'marketing:export'" @click="onExport">
                <CIcon name="export" :size="14" />{{ exported ? '已导出' : '导出报告' }}
              </CButton>
            </div>
          </div>
        </template>
        <CLineChart
          :categories="store.trendCategories"
          :series="store.trendSeries"
          :height="260"
          area
        />
      </CCard>
      <CCard class="db__deals" title="各渠道成交对比">
        <CBarChart
          :items="store.channelDealItems"
          :series="['成交单']"
          orientation="horizontal"
          :height="260"
        />
      </CCard>
    </div>

    <!-- 类型分布 + 漏斗 -->
    <div class="db__row">
      <CCard class="db__type" title="活动类型分布（按成交额）">
        <CDonutChart
          :data="store.typeDistribution"
          :size="180"
          :center-value="big(store.kpis.campaignRevenue)"
          center-label="活动总营收"
        />
      </CCard>

      <CCard class="db__funnel" title="转化漏斗">
        <div class="db-funnel">
          <div v-for="(s, i) in store.funnel" :key="s.key" class="db-funnel__stage">
            <div
              class="db-funnel__bar"
              :class="`db-funnel__bar--${s.key}`"
              :style="{ width: (s.value / funnelMax * 100) + '%' }"
            >
              <span class="db-funnel__label">{{ s.label }}</span>
              <span class="db-funnel__value">{{ big(s.value) }}</span>
            </div>
            <div v-if="i > 0" class="db-funnel__ratio">
              <CIcon name="trend-down" :size="12" />转化率 {{ s.ratio }}%
            </div>
          </div>
        </div>
      </CCard>
    </div>

    <!-- 排行榜 + 推送效果 -->
    <div class="db__row db__row--last">
      <CCard class="db__rank" title="渠道排行榜（按营收）" padding="none">
        <div class="db-table">
          <div class="db-table__head">
            <div class="db-table__c db-table__c--center" style="width:60px">排名</div>
            <div class="db-table__c">渠道</div>
            <div class="db-table__c db-table__c--num">线索</div>
            <div class="db-table__c db-table__c--num">成交</div>
            <div class="db-table__c db-table__c--num">营收</div>
            <div class="db-table__c db-table__c--num">ROI</div>
          </div>
          <div v-for="r in store.channelRank" :key="r.key" class="db-table__row">
            <div class="db-table__c db-table__c--center">
              <span class="db-rank" :class="`db-rank--${r.rank}`">{{ r.rank }}</span>
            </div>
            <div class="db-table__c db-table__c--name">{{ r.name }}</div>
            <div class="db-table__c db-table__c--num">{{ r.leads }}</div>
            <div class="db-table__c db-table__c--num">{{ r.deals }}</div>
            <div class="db-table__c db-table__c--num">{{ money(r.revenue) }}</div>
            <div class="db-table__c db-table__c--num">
              <span
                class="db-roi"
                :class="{
                  'db-roi--ok': r.roi >= 3,
                  'db-roi--warn': r.roi > 0 && r.roi < 3,
                  'db-roi--zero': r.roi === 0,
                }"
              >{{ r.roi ? r.roi.toFixed(1) : '—' }}</span>
            </div>
          </div>
        </div>
      </CCard>

      <CCard class="db__push" title="推送效果">
        <div class="db-push">
          <div class="db-push__grid">
            <div class="db-push__item">
              <div class="db-push__num">{{ store.pushEffectiveness.sent }}</div>
              <div class="db-push__label">已发送批次</div>
            </div>
            <div class="db-push__item">
              <div class="db-push__num">{{ big(store.pushEffectiveness.delivered) }}</div>
              <div class="db-push__label">成功到达</div>
            </div>
            <div class="db-push__item">
              <div class="db-push__num">{{ big(store.pushEffectiveness.clicked) }}</div>
              <div class="db-push__label">点击</div>
            </div>
            <div class="db-push__item">
              <div class="db-push__num">{{ store.pushEffectiveness.converted }}</div>
              <div class="db-push__label">转化</div>
            </div>
          </div>
          <div class="db-push__rates">
            <div class="db-push__rate">
              <span class="db-push__rate-label">点击率 CTR</span>
              <span class="db-push__rate-value">{{ store.pushEffectiveness.ctr }}%</span>
            </div>
            <div class="db-push__rate">
              <span class="db-push__rate-label">点击转化率 CVR</span>
              <span class="db-push__rate-value">{{ store.pushEffectiveness.cvr }}%</span>
            </div>
          </div>
          <div class="db-push__hint">
            <CIcon name="alert" :size="14" />
            <span>周频配额：同一人群 7 天内推送 ≤3 条，超限自动拦截。</span>
          </div>
        </div>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.db { display: flex; flex-direction: column; gap: var(--s-lg); }
.db__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .db__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
.db__card-head { display: flex; align-items: center; justify-content: space-between; width: 100%; gap: var(--s-sm); font-size: var(--t-md); font-weight: 700; flex-wrap: wrap; }
.db__card-head-right { display: flex; align-items: center; gap: var(--s-sm); }
:deep(.ckpi) { min-width: 0; }

.db__row { display: grid; grid-template-columns: 1.4fr 1fr; gap: var(--s-lg); align-items: stretch; }
.db__row--last { grid-template-columns: 1.4fr 1fr; }
.db__trend, .db__deals, .db__type, .db__funnel, .db__rank, .db__push { min-width: 0; }
.db__trend :deep(.card__body),
.db__deals :deep(.card__body),
.db__type :deep(.card__body),
.db__funnel :deep(.card__body),
.db__push :deep(.card__body) { padding: var(--s-lg); }

/* 活动类型分布居左对齐 */
.db__type :deep(.dc) { justify-content: flex-start; }

/* 漏斗 */
.db-funnel { display: flex; flex-direction: column; gap: var(--s-xs); padding: var(--s-sm) 0; }
.db-funnel__stage { display: flex; flex-direction: column; align-items: center; gap: 2px; }
.db-funnel__bar {
  display: flex; align-items: center; justify-content: space-between;
  gap: var(--s-md);
  min-height: 40px;
  padding: 0 var(--s-lg);
  border-radius: var(--r-md);
  color: #fff;
  font-size: var(--t-sm);
  font-weight: 600;
  transition: width .4s ease;
}
.db-funnel__bar--exposure { background: var(--c-series-7); }
.db-funnel__bar--coupon  { background: var(--c-series-2); }
.db-funnel__bar--arrival { background: var(--c-series-3); }
.db-funnel__bar--deal    { background: var(--c-series-1); }
.db-funnel__value { font-variant-numeric: tabular-nums; }
.db-funnel__ratio {
  display: inline-flex; align-items: center; gap: 2px;
  font-size: var(--t-xs); color: var(--c-warning-fg);
}

/* 排行榜表 */
.db-table { width: 100%; }
.db-table__head, .db-table__row {
  display: grid;
  grid-template-columns: 60px 1.4fr 0.8fr 0.8fr 1.2fr 0.8fr;
  align-items: center;
}
.db-table__head {
  padding: var(--s-sm) var(--s-lg);
  background: var(--c-bg-page);
  font-size: var(--t-xs);
  color: var(--c-text-3);
  font-weight: 600;
  border-bottom: 1px solid var(--c-border);
}
.db-table__row {
  padding: var(--s-md) var(--s-lg);
  border-bottom: 1px solid var(--c-border-light);
  font-size: var(--t-sm);
  color: var(--c-text-2);
}
.db-table__row:last-child { border-bottom: none; }
.db-table__c { min-width: 0; }
.db-table__c--num { text-align: right; font-variant-numeric: tabular-nums; }
.db-table__c--center { text-align: center; }
.db-table__c--name { color: var(--c-text); font-weight: 600; }
.db-rank {
  display: inline-flex; align-items: center; justify-content: center;
  width: 22px; height: 22px; border-radius: 50%;
  background: var(--c-disabled-bg); color: var(--c-text-3);
  font-size: var(--t-xs); font-weight: 700;
}
.db-rank--1 { background: var(--c-warning-fg); color: #fff; }
.db-rank--2 { background: var(--c-text-3); color: #fff; }
.db-rank--3 { background: var(--c-orange-dark); color: #fff; }
.db-roi { font-weight: 700; font-variant-numeric: tabular-nums; }
.db-roi--ok { color: var(--c-success-fg); }
.db-roi--warn { color: var(--c-warning-fg); }
.db-roi--zero { color: var(--c-text-4); font-weight: 400; }

/* 推送效果 */
.db-push { display: flex; flex-direction: column; gap: var(--s-md); }
.db-push__grid {
  display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-sm);
}
.db-push__item {
  background: var(--c-bg-right); border-radius: var(--r-md);
  padding: var(--s-md); text-align: center;
}
.db-push__num { font-size: var(--t-lg); font-weight: 700; color: var(--c-text); font-variant-numeric: tabular-nums; }
.db-push__label { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }
.db-push__rates {
  display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-sm);
}
.db-push__rate {
  display: flex; align-items: center; justify-content: space-between;
  background: var(--c-brand-soft); border-radius: var(--r-md);
  padding: var(--s-sm) var(--s-md);
}
.db-push__rate-label { font-size: var(--t-xs); color: var(--c-text-2); }
.db-push__rate-value { font-size: var(--t-md); font-weight: 700; color: var(--c-brand); font-variant-numeric: tabular-nums; }
.db-push__hint {
  display: flex; gap: var(--s-xs); align-items: flex-start;
  background: var(--c-warn-soft-bg); border-radius: var(--r-md);
  padding: var(--s-sm) var(--s-md); font-size: var(--t-xs); color: var(--c-text-2); line-height: var(--lh-sm);
}
.db-push__hint :deep(svg) { color: var(--c-warning-fg); margin-top: 2px; flex-shrink: 0; }

@media (max-width: 1280px) {
  .db__row, .db__row--last { grid-template-columns: 1fr; }
}
@media (max-width: 1024px) {
  .db__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .db-push__grid { grid-template-columns: repeat(2, 1fr); }
}
</style>
