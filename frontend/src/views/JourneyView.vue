<script setup lang="ts">
/* ============================================================
 * M3-07 客户旅程 /m3-journey
 * 4 KPI + 左客户列表 + 右侧全生命周期旅程（横向时间轴）+ 消费明细 + 快捷操作。
 * ============================================================ */
import { computed, onMounted } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import { useJourneyStore } from '@/stores/journey'
import { RISK_LEVEL, dictPill } from '@/config/dictionary'

const store = useJourneyStore()
onMounted(() => store.seed())

const kpis = computed(() => [
  { label: '在途客户', icon: 'customer', value: String(store.inProgress.length), tone: 'brand' as const },
  { label: '本周转化', icon: 'trend-up', value: String(store.convertedThisWeek.length), tone: 'success' as const },
  { label: '平均旅程天数', icon: 'clock', value: String(store.avgDays), tone: 'teal' as const },
  { label: '流失风险', icon: 'alert', value: String(store.churnRisk.length), tone: 'danger' as const },
])


const selected = computed(() => store.selected)

function pick(id: string) { store.select(id) }
</script>

<template>
  <div class="jv">
    <!-- 页头 -->
    <div class="jv__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <div class="jv__body">
      <!-- 左：客户列表 -->
      <CCard class="jv__list" padding="none">
        <div class="list-head">
          <h3 class="list-title">在途客户<span class="list-count">{{ store.customers.length }}</span></h3>
        </div>
        <div class="list">
          <button v-for="c in store.customers" :key="c.id"
                  class="row" :class="{ 'row--active': selected?.id === c.id }"
                  @click="pick(c.id)">
            <div class="row__avatar">{{ c.avatarLetter }}</div>
            <div class="row__main">
              <div class="row__top">
                <span class="row__name">{{ c.name }}</span>
                <CStatusPill :status="dictPill(RISK_LEVEL[c.risk]).status" dot>{{ dictPill(RISK_LEVEL[c.risk]).text }}</CStatusPill>
              </div>
              <div class="row__sub">{{ c.phoneMask }} · {{ c.level }}</div>
              <div class="row__stage">
                <CIcon name="clock" :size="12" />
                当前阶段：{{ store.STAGE_LABEL[c.currentStage] }}
              </div>
            </div>
          </button>
        </div>
      </CCard>

      <!-- 右：旅程 -->
      <div class="jv__detail" v-if="selected">
        <CCard class="journey-card" padding="lg">
          <template #header>
            <div class="jv__journey-head">
              <h3 class="jv__card-title">全生命周期旅程</h3>
              <div class="jv__journey-ops">
                <div class="jv__head-chip">
                  <CIcon name="profile" :size="14" />
                  {{ selected.name }} · {{ selected.level }}
                </div>
                <CButton variant="secondary" size="sm">
                  <CIcon name="calendar" :size="14" />近 180 天
                </CButton>
              </div>
            </div>
          </template>
          <div class="tl">
            <div v-for="(n, i) in selected.nodes" :key="i" class="tl__node">
              <div class="tl__dot" :style="{ background: store.STAGE_COLOR[n.stage], opacity: n.done ? 1 : 0.4 }" />
              <div class="tl__line" v-if="i < selected.nodes.length - 1" />
              <div class="tl__body">
                <div class="tl__title">{{ store.STAGE_LABEL[n.stage] }}</div>
                <div class="tl__date">{{ n.date }}</div>
                <div class="tl__desc">{{ n.title }}</div>
                <div v-if="n.amount" class="tl__amount">¥{{ n.amount.toLocaleString() }}</div>
                <div v-if="n.operator" class="tl__op">
                  <CIcon name="check" :size="11" />{{ n.operator }}
                </div>
                <div v-else-if="!n.done" class="tl__pending">待处理</div>
              </div>
            </div>
          </div>
        </CCard>

        <div class="jv__lower">
          <CCard class="detail-card" padding="lg">
            <template #header>
              <h3 class="jv__card-title">
                消费明细 · {{ store.lastPayment?.date }} {{ store.lastPayment?.title }}
              </h3>
            </template>
            <div v-if="store.lastPayment" class="pay">
              <div class="pay__row">
                <span class="pay__label">服务项目</span>
                <span class="pay__val">{{ store.lastPayment.desc }} × 1 次</span>
              </div>
              <div class="pay__row">
                <span class="pay__label">实收金额</span>
                <span class="pay__val pay__val--strong">¥{{ store.lastPayment.amount?.toLocaleString() }}.00</span>
              </div>
              <div class="pay__row">
                <span class="pay__label">服务顾问</span>
                <span class="pay__val">{{ store.lastPayment.operator }} · 高级咨询师</span>
              </div>
              <div class="pay__row">
                <span class="pay__label">支付方式</span>
                <span class="pay__val">{{ store.lastPayment.desc }}</span>
              </div>
            </div>
            <div v-else class="pay-empty">暂无消费明细</div>
          </CCard>

          <CCard class="action-card" padding="md">
            <template #header><h3 class="jv__card-title">快捷操作</h3></template>
            <div class="actions">
              <CButton variant="primary" block v-perm.disable="'followup:create'">
                <CIcon name="plus" :size="14" />新建跟进任务
              </CButton>
              <CButton variant="secondary" block>
                <CIcon name="order" :size="14" />查看完整订单
              </CButton>
              <CButton variant="secondary" block>
                <CIcon name="edit" :size="14" />添加旅程备注
              </CButton>
            </div>
          </CCard>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.jv { display: flex; flex-direction: column; gap: var(--s-lg); }

.jv__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .jv__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }
.jv__head-chip { display: inline-flex; align-items: center; gap: 6px; padding: 6px 14px; background: var(--c-surface); border: 1px solid var(--c-border); border-radius: var(--r-md); font-size: var(--t-sm); color: var(--c-text-2); }

.jv__body { display: grid; grid-template-columns: 380px 1fr; gap: var(--s-lg); align-items: start; }

/* 左列表 */
.list-head { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-md) var(--s-lg); border-bottom: 1px solid var(--c-border-light); }
.list-title { font-size: var(--t-md); font-weight: 700; margin: 0; display: flex; align-items: center; gap: var(--s-sm); }
.list-count { font-size: var(--t-xs); color: var(--c-text-3); background: var(--c-disabled-bg); padding: 2px 8px; border-radius: var(--r-pill); font-weight: 400; }
.list { max-height: 640px; overflow-y: auto; }
.row { display: flex; gap: var(--s-sm); width: 100%; text-align: left; padding: var(--s-md) var(--s-lg); background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer; }
.row:hover { background: var(--c-brand-soft); }
.row--active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.row__avatar { width: 40px; height: 40px; border-radius: 50%; background: var(--c-brand); color: #fff; display: flex; align-items: center; justify-content: center; font-weight: 700; flex-shrink: 0; }
.row--active .row__avatar { background: var(--c-brand-press); }
.row__main { flex: 1; min-width: 0; }
.row__top { display: flex; align-items: center; justify-content: space-between; gap: var(--s-xs); }
.row__name { font-size: var(--t-sm); font-weight: 700; color: var(--c-text); }
.row__sub { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }
.row__stage { display: inline-flex; align-items: center; gap: 4px; font-size: var(--t-xs); color: var(--c-text-2); margin-top: 4px; }

/* 旅程时间轴（横向） */
.journey-card { background: var(--c-surface); }
.jv__card-title { font-size: var(--t-md); font-weight: 700; margin: 0; }
.jv__journey-head { display: flex; align-items: center; justify-content: space-between; gap: var(--s-md); width: 100%; flex-wrap: nowrap; }
.jv__journey-ops { display: flex; align-items: center; gap: var(--s-sm); flex-shrink: 0; }
.jv__journey-ops :deep(.cbtn) { white-space: nowrap; }
.tl { display: grid; grid-template-columns: repeat(6, 1fr); gap: var(--s-sm); padding: var(--s-sm) 0; position: relative; }
.tl__node { position: relative; padding-top: var(--s-lg); }
.tl__dot { position: absolute; top: 0; left: 0; width: 16px; height: 16px; border-radius: 50%; z-index: 1; }
.tl__line { position: absolute; top: 7px; left: 16px; right: -8px; height: 2px; background: var(--c-border); }
.tl__body { display: flex; flex-direction: column; gap: 2px; padding-right: var(--s-xs); }
.tl__title { font-size: var(--t-base); font-weight: 700; color: var(--c-text); }
.tl__date { font-size: var(--t-sm); color: var(--c-text-2); }
.tl__desc { font-size: var(--t-xs); color: var(--c-text-3); line-height: var(--lh-xs); }
.tl__amount { font-size: var(--t-sm); font-weight: 700; color: var(--c-text); margin-top: 2px; }
.tl__op { display: inline-flex; align-items: center; gap: 3px; font-size: var(--t-xs); color: var(--c-text-3); }
.tl__pending { font-size: var(--t-xs); color: var(--c-warning-fg); }

/* 下半 */
.jv__lower { display: grid; grid-template-columns: 1fr 320px; gap: var(--s-lg); margin-top: var(--s-lg); }
.pay { display: flex; flex-direction: column; gap: var(--s-md); }
.pay__row { display: flex; justify-content: space-between; align-items: baseline; gap: var(--s-md); padding: var(--s-xs) 0; border-bottom: 1px dashed var(--c-border-light); }
.pay__row:last-child { border-bottom: none; }
.pay__label { font-size: var(--t-sm); color: var(--c-text-3); }
.pay__val { font-size: var(--t-sm); color: var(--c-text); }
.pay__val--strong { font-size: var(--t-lg); font-weight: 700; color: var(--c-text); }
.pay-empty { padding: var(--s-lg); text-align: center; color: var(--c-text-3); font-size: var(--t-sm); }

.actions { display: flex; flex-direction: column; gap: var(--s-sm); }

@media (max-width: 1024px) {
  .jv__body { grid-template-columns: 1fr; }
  .jv__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .tl { grid-template-columns: repeat(2, 1fr); gap: var(--s-md); }
  .tl__line { display: none; }
  .jv__lower { grid-template-columns: 1fr; }
}
</style>
