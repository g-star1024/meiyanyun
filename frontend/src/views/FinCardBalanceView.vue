<script setup lang="ts">
/* ============================================================
 * M6 会员卡余额 /m6-card-balance
 * 4 KPI（卡余额总额[financeCore.depositBalance]/活跃/沉睡/卡张数）
 * 左：会员卡余额列表；右：选中卡余额变动时间线；上：余额构成环形图
 * 红线：余额只读镜像，不落地资金；冻结仅镜像状态。
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CDonutChart from '@/components/CDonutChart.vue'
import { useFinCardBalanceStore, type CardTxn } from '@/stores/finReports'

const store = useFinCardBalanceStore()
onMounted(() => store.seed())

const selectedId = ref<string | null>(null)
const selected = computed(() => {
  if (selectedId.value) return store.get(selectedId.value) ?? null
  return store.filtered[0] ?? null
})

const kpis = computed(() => [
  { label: '卡余额总额', icon: 'finance', value: money(store.totalBalance), tone: 'brand' as const },
  { label: '活跃卡余额', icon: 'customer', value: money(store.activeBalance), tone: 'success' as const },
  { label: '沉睡卡余额', icon: 'customer', value: money(store.dormantBalance), tone: 'warning' as const },
  { label: '卡张数', icon: 'card', value: String(store.cardCount), tone: 'text' as const },
])

const statusOptions = [
  { value: 'ALL', label: '全部状态' },
  { value: 'NORMAL', label: '正常' },
  { value: 'DORMANT', label: '沉睡' },
  { value: 'FROZEN', label: '冻结' },
]

function money(n: number) {
  return `¥${n.toLocaleString('zh-CN', { maximumFractionDigits: 2 })}`
}
function timesLabel(c: { type: string; timesTotal: number; timesRemain: number }) {
  if (c.type !== 'TIMES') return '—'
  return `${c.timesRemain}/${c.timesTotal} 次`
}

const txnIcon = {
  RECHARGE: 'plus', CONSUME: 'scissors', REFUND: 'refund', FREEZE: 'shield', ADJUST: 'edit',
} as const
const txnCls: Record<CardTxn['type'], string> = {
  RECHARGE: 'in', CONSUME: 'out', REFUND: 'in', FREEZE: 'freeze', ADJUST: 'adj',
}
</script>

<template>
  <div class="cb">
    <div class="cb__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <div class="cb__body">
      <!-- 左：卡列表 -->
      <CCard class="cb__list" padding="none">
        <div class="filters">
          <CSelect v-model="store.filterStatus" width="120px" :options="statusOptions" />
          <div class="filters__search">
            <CIcon name="search" :size="14" class="filters__search-icon" />
            <CInput v-model="store.keyword" placeholder="卡号/客户" />
          </div>
        </div>
        <div class="list">
          <div v-if="store.filtered.length === 0" class="empty">
            <CIcon name="card" :size="28" class="empty__icon" />
            <div>暂无会员卡</div>
          </div>
          <button
            v-for="c in store.filtered" :key="c.id"
            class="row" :class="{ 'row--active': selected?.id === c.id }"
            @click="selectedId = c.id"
          >
            <div class="row__top">
              <span class="row__no">{{ c.cardNo }}</span>
              <CStatusPill :status="store.CARD_STATUS_PILL[c.status]">{{ store.CARD_STATUS_LABEL[c.status] }}</CStatusPill>
            </div>
            <div class="row__name">{{ c.customerName }} · {{ store.CARD_TYPE_LABEL[c.type] }}</div>
            <div class="row__bottom">
              <span v-if="c.type !== 'TIMES'" class="row__amt">{{ money(c.balance + c.giftBalance) }}</span>
              <span v-else class="row__amt">{{ timesLabel(c) }}</span>
              <span class="row__date">{{ c.lastConsumeAt }}</span>
            </div>
          </button>
        </div>
      </CCard>

      <!-- 右：详情 -->
      <CCard v-if="selected" class="cb__detail" padding="none">
        <template #header>
          <div class="cb__detail-head">
            <div class="cb__who">
              <div class="cb__avatar">{{ selected.customerName.slice(0, 1) }}</div>
              <div>
                <h3 class="cb__name">{{ selected.customerName }}</h3>
                <div class="cb__sub">{{ selected.cardNo }} · {{ store.CARD_TYPE_LABEL[selected.type] }}</div>
              </div>
            </div>
            <div class="cb__detail-ops">
              <CStatusPill :status="store.CARD_STATUS_PILL[selected.status]" dot>{{ store.CARD_STATUS_LABEL[selected.status] }}</CStatusPill>
              <CButton variant="secondary" size="sm" v-perm.disable="'finance:export'">
                <CIcon name="export" :size="14" />导出余额
              </CButton>
            </div>
          </div>
        </template>

        <div class="detail-body">
          <!-- 余额构成 -->
          <div class="compose">
            <CDonutChart
              :data="store.composition"
              :size="140" :thickness="16"
              center-value="余额构成" center-label=""
            />
            <div class="compose__vals">
              <div class="compose__row" v-for="d in store.composition" :key="d.label">
                <span class="compose__dot" :style="{ background: d.color }" />
                <span class="compose__label">{{ d.label }}</span>
                <span class="compose__num">{{ money(d.value) }}</span>
              </div>
            </div>
          </div>

          <!-- 指标 -->
          <div class="stat-grid">
            <div class="stat"><span>储值余额</span><b>{{ money(selected.balance) }}</b></div>
            <div class="stat"><span>赠送金</span><b>{{ money(selected.giftBalance) }}</b></div>
            <div class="stat"><span>剩余次数</span><b>{{ timesLabel(selected) }}</b></div>
            <div class="stat"><span>最近消费</span><b>{{ selected.lastConsumeAt }}</b></div>
          </div>

          <!-- 时间线 -->
          <div class="block">
            <div class="block__title"><span>余额变动记录</span></div>
            <div class="tl">
              <div v-for="t in selected.txns" :key="t.id" class="tl__item">
                <div class="tl__dot" :class="`tl__dot--${txnCls[t.type]}`">
                  <CIcon :name="txnIcon[t.type]" :size="12" />
                </div>
                <div class="tl__body">
                  <div class="tl__top">
                    <span class="tl__type">{{ store.CARD_TXN_LABEL[t.type] }}</span>
                    <span class="tl__amt" :class="`tl__amt--${txnCls[t.type]}`">
                      {{ t.type === 'CONSUME' || t.type === 'FREEZE' ? '−' : t.type === 'ADJUST' ? '' : '+' }}{{ money(Math.abs(t.amount)) }}
                    </span>
                  </div>
                  <div class="tl__memo">{{ t.memo }}</div>
                  <div class="tl__date">{{ t.date }}</div>
                </div>
              </div>
            </div>
          </div>

          <p class="redline">
            <CIcon name="shield" :size="14" />
            会员卡余额为只读镜像，与预收账款相互印证，不落地资金；冻结操作仅镜像状态。
          </p>
        </div>
      </CCard>

      <CCard v-else class="cb__detail cb__detail--empty" title="会员卡详情" padding="lg">
        <div class="detail-empty">
          <CIcon name="card" :size="40" class="detail-empty__icon" />
          <p>请选择一张会员卡</p>
        </div>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.cb { display: flex; flex-direction: column; gap: var(--s-lg); }
.cb__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .cb__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }

.cb__body { display: grid; grid-template-columns: 380px 1fr; gap: var(--s-lg); align-items: start; }
.cb__list { min-width: 0; }
.filters { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-md); border-bottom: 1px solid var(--c-border-light); flex-wrap: nowrap; overflow-x: auto; }
.filters__search { position: relative; flex: 1; min-width: 140px; }
.filters__search-icon { position: absolute; left: 10px; top: 50%; transform: translateY(-50%); color: var(--c-text-3); pointer-events: none; z-index: 1; }
.filters__search :deep(.cinput) { padding-left: 30px; }
.filters__right { display: flex; align-items: center; gap: var(--s-sm); flex-shrink: 0; position: sticky; right: 0; background: var(--c-surface); z-index: 2; padding-left: var(--s-sm); margin-left: var(--s-xs); box-shadow: -8px 0 8px -6px rgba(0,0,0,.08); }
.list { max-height: 640px; overflow-y: auto; }
.empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); font-size: var(--t-sm); }
.empty__icon { color: var(--c-text-4); }

.row { display: block; width: 100%; text-align: left; padding: var(--s-md) var(--s-lg); background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer; }
.row:hover { background: var(--c-brand-soft); }
.row--active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.row__top { display: flex; align-items: center; justify-content: space-between; gap: var(--s-sm); margin-bottom: 4px; }
.row__no { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); font-variant-numeric: tabular-nums; }
.row__name { font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: 6px; }
.row__bottom { display: flex; align-items: baseline; justify-content: space-between; }
.row__amt { font-size: var(--t-md); font-weight: 700; color: var(--c-text); font-variant-numeric: tabular-nums; }
.row__date { font-size: var(--t-xs); color: var(--c-text-3); }

.cb__detail-head { display: flex; align-items: center; justify-content: space-between; gap: var(--s-md); width: 100%; }
.cb__who { display: flex; align-items: center; gap: var(--s-md); }
.cb__detail-ops { display: flex; align-items: center; gap: var(--s-sm); flex-shrink: 0; }
.cb__avatar { width: 48px; height: 48px; border-radius: 50%; background: var(--c-brand-soft); color: var(--c-brand); display: flex; align-items: center; justify-content: center; font-size: var(--t-lg); font-weight: 700; }
.cb__name { font-size: var(--t-lg); font-weight: 700; margin: 0; }
.cb__sub { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }

.detail-body { padding: var(--s-lg); display: flex; flex-direction: column; gap: var(--s-lg); }
.compose { display: flex; align-items: center; gap: var(--s-xl); background: var(--c-bg-right); border-radius: var(--r-md); padding: var(--s-lg); }
.compose__vals { flex: 1; display: flex; flex-direction: column; gap: var(--s-sm); }
.compose__row { display: flex; align-items: center; gap: var(--s-sm); }
.compose__dot { width: 10px; height: 10px; border-radius: 3px; flex-shrink: 0; }
.compose__label { flex: 1; font-size: var(--t-sm); color: var(--c-text-2); }
.compose__num { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); font-variant-numeric: tabular-nums; }

.stat-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); }
.stat { background: var(--c-bg-right); border-radius: var(--r-md); padding: var(--s-md); display: flex; flex-direction: column; gap: 4px; }
.stat span { font-size: var(--t-xs); color: var(--c-text-3); }
.stat b { font-size: var(--t-sm); font-weight: 700; color: var(--c-text); font-variant-numeric: tabular-nums; }

.block { display: flex; flex-direction: column; gap: var(--s-sm); }
.block__title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.tl { display: flex; flex-direction: column; }
.tl__item { display: flex; gap: var(--s-sm); padding: var(--s-sm) 0; border-bottom: 1px solid var(--c-border-light); }
.tl__item:last-child { border-bottom: none; }
.tl__dot { width: 28px; height: 28px; border-radius: 50%; display: flex; align-items: center; justify-content: center; flex-shrink: 0; color: #fff; }
.tl__dot--in { background: var(--c-success-fg); }
.tl__dot--out { background: var(--c-brand); }
.tl__dot--freeze { background: var(--c-text-3); }
.tl__dot--adj { background: var(--c-warning-fg); }
.tl__body { flex: 1; min-width: 0; }
.tl__top { display: flex; justify-content: space-between; align-items: center; }
.tl__type { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.tl__amt { font-size: var(--t-sm); font-weight: 700; font-variant-numeric: tabular-nums; }
.tl__amt--in { color: var(--c-success-fg); }
.tl__amt--out { color: var(--c-text-2); }
.tl__amt--freeze { color: var(--c-text-3); }
.tl__amt--adj { color: var(--c-warning-fg); }
.tl__memo { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }
.tl__date { font-size: var(--t-xs); color: var(--c-text-4); margin-top: 2px; }

.redline { display: flex; align-items: center; gap: 6px; font-size: var(--t-xs); color: var(--c-warning-fg); background: var(--c-warn-soft-bg); padding: var(--s-xs) var(--s-sm); border-radius: var(--r-sm); margin: 0; }
.detail-empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-md); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); }
.detail-empty__icon { color: var(--c-text-4); }

@media (max-width: 1024px) {
  .cb__body { grid-template-columns: 1fr; }
  .stat-grid { grid-template-columns: repeat(2, 1fr); }
  .compose { flex-direction: column; align-items: flex-start; }
  .list { max-height: 360px; }
}
</style>
