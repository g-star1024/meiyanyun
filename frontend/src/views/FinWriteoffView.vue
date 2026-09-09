<script setup lang="ts">
/* M6-09 核销双签明细 /m6-writeoff — 财务只读镜像（B24 卡2 切真）。
 * 数据源：txn writeoff_record 经 finance 聚合代理（/finance/writeoff-details），
 * 含客户/卡号/项目/扣次/金额/状态/操作人/操作双签/复核双签/异常原因。
 * 状态：DONE 已核销（双签完成）/ ABNORMAL 异常 / VOID 已作废；纯扣次金额为 0。
 * 财务域只读，不反向写划扣；收入确认口径仍以财务台账（financeCore）为准。 */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CSelect from '@/components/CSelect.vue'
import CInput from '@/components/CInput.vue'
import { useFinWriteoffStore } from '@/stores/finReports'
import { useAuthStore } from '@/stores/auth'
import { exportWriteoffCsv } from '@/api/finance'

const store = useFinWriteoffStore()
const auth = useAuthStore()
const canExport = computed(() => auth.can('finance:export'))

onMounted(() => void store.seed())

const selectedId = ref<string | null>(null)
const selected = computed(
  () => store.filtered.find((w) => w.writeoffId === selectedId.value) ?? store.filtered[0] ?? null,
)

const statusOf = (s: string) =>
  (store.WRITEOFF_STATUS_LABEL as Record<string, string>)[s] ?? s
const pillOf = (s: string) =>
  (store.WRITEOFF_STATUS_PILL as Record<string, 'success' | 'danger' | 'info'>)[s] ?? 'info'

function money(n: number | null | undefined) {
  return `¥${(n ?? 0).toLocaleString('zh-CN', { maximumFractionDigits: 2 })}`
}

const kpis = computed(() => [
  { label: '已核销金额', icon: 'check-square', value: money(store.doneAmount), tone: 'success' as const, sub: `${store.doneRows.length} 笔，双签完成` },
  { label: '异常核销', icon: 'alert', value: `${store.abnormalRows.length} 笔`, tone: 'danger' as const, sub: `合计 ${money(store.abnormalAmount)}，需复核处置` },
  { label: '已作废', icon: 'shield', value: `${store.voidRows.length} 笔`, tone: 'blue' as const, sub: '作废记录不计收入' },
  { label: '核销总笔数', icon: 'card', value: `${store.rows.length} 笔`, tone: 'text' as const, sub: '划扣双签明细只读镜像' },
])

async function exportCsv() {
  if (!canExport.value) return
  try {
    const params: Record<string, string> = {}
    if (store.filterStatus !== 'ALL') params.status = store.filterStatus
    if (store.filterStore !== 'ALL') params.storeCode = store.filterStore
    const kw = store.keyword.trim()
    if (kw) params.keyword = kw
    await exportWriteoffCsv(Object.keys(params).length ? params : undefined)
  } catch (e) {
    console.error('[finWriteoff] 导出核销明细 CSV 失败', e)
  }
}
</script>

<template>
  <div class="wo">
    <div class="wo__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :sub="k.sub" :icon="k.icon" />
    </div>

    <CCard class="wo__rule" padding="md">
      <div class="rule-line">
        <CIcon name="shield" :size="15" />
        <span><strong>双签口径：</strong>每笔核销需「操作双签 + 复核双签」完成方为已核销；异常核销须登记异常原因并复核处置，已作废记录不计收入。本页为 txn 核销记录经财务聚合的只读镜像，财务域不可修改。</span>
      </div>
    </CCard>

    <div class="wo__body">
      <CCard class="wo__list" padding="none">
        <div class="list-head">
          <span class="list-head__title">核销明细<span class="list-head__hint">{{ store.filtered.length }} 笔</span></span>
          <div class="list-head__right">
            <CSelect
              v-model="store.filterStatus"
              :options="[{ value: 'ALL', label: '全部状态' }, { value: 'DONE', label: '已核销' }, { value: 'ABNORMAL', label: '异常' }, { value: 'VOID', label: '已作废' }]"
            />
            <CSelect v-model="store.filterStore" :options="store.storeOptions" />
          </div>
          <div class="list-head__search">
            <CIcon name="search" :size="14" class="list-head__search-icon" />
            <CInput v-model="store.keyword" placeholder="核销号/订单/卡号/项目（页面内可按客户/异常原因再筛）" />
          </div>
        </div>
        <div class="w-list">
          <button
            v-for="w in store.filtered" :key="w.writeoffId"
            class="w-row" :class="{ 'w-row--active': selected?.writeoffId === w.writeoffId, [`w-row--${w.status.toLowerCase()}`]: true }"
            @click="selectedId = w.writeoffId"
          >
            <div class="w-row__top">
              <span class="w-row__no">{{ w.writeoffId }}</span>
              <CStatusPill :status="pillOf(w.status)" dot>{{ statusOf(w.status) }}</CStatusPill>
            </div>
            <div class="w-row__mid">
              <span class="w-row__proj">{{ w.project || '—' }}</span>
              <span class="w-row__amount">{{ w.amount ? money(w.amount) : `扣 ${w.timesUsed || 1} 次` }}</span>
            </div>
            <div class="w-row__sub">{{ w.store }} · {{ w.customerName || '—' }} · {{ w.date }}</div>
            <div class="w-row__sign">
              <span class="sign" :class="{ on: w.status === 'DONE' }">
                {{ w.status === 'DONE' ? '双签完成' : w.status === 'ABNORMAL' ? '异常待处置' : '已作废' }}
              </span>
            </div>
          </button>
          <div v-if="store.loading" class="empty">
            <CIcon name="clock" :size="28" class="empty__icon" />
            <p>核销明细加载中…</p>
          </div>
          <div v-else-if="store.error" class="empty">
            <CIcon name="alert" :size="28" class="empty__icon" />
            <p>{{ store.error }}</p>
            <p class="empty__hint">不生成演示核销数据，请稍后重试</p>
          </div>
          <div v-else-if="!store.filtered.length" class="empty">
            <CIcon name="check-square" :size="28" class="empty__icon" />
            <p>暂无核销双签明细</p>
            <p class="empty__hint">不生成演示核销数据；无记录可能是筛选条件不匹配或当前数据范围确无核销</p>
          </div>
        </div>
      </CCard>

      <CCard v-if="selected" class="wo__detail" padding="lg">
        <div class="det-head">
          <div>
            <h3 class="det-head__no">{{ selected.writeoffId }} · {{ selected.project || '—' }}</h3>
            <div class="det-head__sub">{{ selected.store }} · 核销日期 {{ selected.date }}</div>
          </div>
          <div class="det-head__ops">
            <CStatusPill :status="pillOf(selected.status)" dot>{{ statusOf(selected.status) }}</CStatusPill>
            <CButton variant="secondary" size="sm" :disabled="!canExport" @click="exportCsv">
              <CIcon name="export" :size="14" />导出
            </CButton>
          </div>
        </div>

        <div class="det-amount">{{ selected.amount ? money(selected.amount) : `纯扣次 ${selected.timesUsed || 1} 次` }}</div>
        <dl class="det-meta">
          <div><dt>核销号</dt><dd>{{ selected.writeoffId }}</dd></div>
          <div><dt>订单号</dt><dd>{{ selected.orderNo || '—' }}</dd></div>
          <div><dt>客户姓名</dt><dd>{{ selected.customerName || '—' }}</dd></div>
          <div><dt>会员卡号</dt><dd>{{ selected.cardNo || '整单核销（无卡号）' }}</dd></div>
          <div><dt>所属门店</dt><dd>{{ selected.store }}（{{ selected.storeCode }}）</dd></div>
          <div><dt>本次扣次</dt><dd>{{ selected.timesUsed || 1 }} 次</dd></div>
          <div><dt>操作人</dt><dd>{{ selected.operator || '—' }}</dd></div>
          <div><dt>异常原因</dt><dd :class="{ 'det-abn': selected.abnormalReason }">{{ selected.abnormalReason || '—' }}</dd></div>
        </dl>

        <div class="sign-card" :class="`sign-card--${selected.status.toLowerCase()}`">
          <div class="sign-card__title"><CIcon name="shield" :size="13" />双签记录</div>
          <div class="sign-card__row">
            <span class="sign-card__dot"></span>
            <div class="sign-card__body">
              <div class="sign-card__text">操作双签：<strong>{{ selected.sign1 || '—' }}</strong></div>
              <div class="sign-card__by">核销操作人现场双签</div>
            </div>
          </div>
          <div class="sign-card__row">
            <span class="sign-card__dot" :class="{ 'sign-card__dot--off': !selected.sign2 }"></span>
            <div class="sign-card__body">
              <div class="sign-card__text">复核双签：<strong>{{ selected.sign2 || '待复核' }}</strong></div>
              <div class="sign-card__by">第二人复核确认</div>
            </div>
          </div>
        </div>

        <div class="mirror-note"><CIcon name="shield" :size="13" />核销数据单向镜像自 txn 核销记录（finance-service 聚合代理，按登录人数据权限过滤），财务域只读。</div>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.wo { display: flex; flex-direction: column; gap: var(--s-lg); }
.wo__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .wo__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }

.wo__rule { background: var(--c-brand-soft); border: 1px solid var(--c-brand-light, rgba(94,114,228,.25)); }
.rule-line { display: flex; align-items: flex-start; gap: var(--s-sm); font-size: var(--t-xs); color: var(--c-text-2); line-height: 1.6; }
.rule-line strong { color: var(--c-brand); }

.wo__body { display: grid; grid-template-columns: 380px 1fr; gap: var(--s-lg); align-items: start; }
.wo__list { min-width: 0; }
.list-head { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-md) var(--s-lg); border-bottom: 1px solid var(--c-border-light); flex-wrap: wrap; }
.list-head__title { font-size: var(--t-sm); font-weight: 700; display: flex; align-items: baseline; gap: var(--s-sm); margin-right: auto; }
.list-head__hint { font-size: var(--t-xs); color: var(--c-text-3); font-weight: 400; }
.list-head__right { display: flex; align-items: center; gap: var(--s-sm); flex-shrink: 0; flex-wrap: nowrap; }
.list-head__search { position: relative; flex: 1; min-width: 180px; }
.list-head__search-icon { position: absolute; left: 10px; top: 50%; transform: translateY(-50%); color: var(--c-text-3); pointer-events: none; z-index: 1; }
.list-head__search :deep(.cinput) { padding-left: 30px; }
.w-list { max-height: 620px; overflow-y: auto; }
.w-row { display: block; width: 100%; text-align: left; padding: var(--s-md) var(--s-lg); background: none; border: none; border-bottom: 1px solid var(--c-border-light); border-left: 3px solid transparent; cursor: pointer; }
.w-row:hover { background: var(--c-brand-soft); }
.w-row--active { background: var(--c-brand-soft); border-left-color: var(--c-brand); }
.w-row--abnormal { border-left-color: var(--c-danger-fg); }
.w-row--void { opacity: .7; }
.w-row__top { display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px; }
.w-row__no { font-size: var(--t-xs); color: var(--c-text-3); font-variant-numeric: tabular-nums; }
.w-row__mid { display: flex; justify-content: space-between; align-items: baseline; gap: var(--s-sm); }
.w-row__proj { font-size: var(--t-sm); font-weight: 600; min-width: 0; }
.w-row__amount { font-size: var(--t-md); font-weight: 700; font-variant-numeric: tabular-nums; flex-shrink: 0; }
.w-row__sub { font-size: var(--t-xs); color: var(--c-text-3); margin: 2px 0 6px; }
.w-row__sign { display: flex; gap: 6px; font-size: var(--t-xs); }
.sign { padding: 1px 6px; border-radius: var(--r-sm); background: var(--c-disabled-bg); color: var(--c-text-3); }
.sign.on { background: var(--c-success-soft, rgba(22,163,110,.12)); color: var(--c-success-fg); }

.empty { display: flex; flex-direction: column; align-items: center; gap: 6px; padding: var(--s-xl) var(--s-lg); color: var(--c-text-3); text-align: center; }
.empty__icon { color: var(--c-text-4); }
.empty p { margin: 0; font-size: var(--t-sm); }
.empty__hint { font-size: var(--t-xs) !important; color: var(--c-text-4); line-height: 1.7; }

.wo__detail :deep(.card__body) { display: flex; flex-direction: column; gap: var(--s-md); min-width: 0; }
.det-head { display: flex; justify-content: space-between; align-items: flex-start; gap: var(--s-md); }
.det-head > div { min-width: 0; flex: 1; }
.det-head__ops { flex: 0 0 auto !important; display: flex; align-items: center; gap: var(--s-sm); }
.det-head__no { margin: 0; font-size: var(--t-lg); font-weight: 700; word-break: break-word; }
.det-head__sub { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; word-break: break-word; }
.det-amount { font-size: 28px; font-weight: 800; color: var(--c-success-fg); font-variant-numeric: tabular-nums; margin: var(--s-xs) 0 var(--s-sm); }
.det-meta { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-sm) var(--s-lg); margin: 0; }
.det-meta div { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.det-meta dt { font-size: var(--t-xs); color: var(--c-text-3); }
.det-meta dd { margin: 0; font-size: var(--t-sm); font-weight: 600; word-break: break-word; }
.det-abn { color: var(--c-danger-fg); }

.sign-card { padding: var(--s-md); border-radius: var(--r-md); font-size: var(--t-xs); line-height: 1.7; }
.sign-card--done { background: var(--c-success-bg, rgba(22,163,110,.06)); }
.sign-card--abnormal { background: var(--c-danger-bg); }
.sign-card--void { background: var(--c-disabled-bg); }
.sign-card__title { display: flex; align-items: center; gap: 4px; font-weight: 700; color: var(--c-text-2); margin-bottom: var(--s-sm); }
.sign-card__row { display: flex; gap: var(--s-sm); padding: 4px 0; }
.sign-card__dot { width: 8px; height: 8px; border-radius: 50%; background: var(--c-success-fg); margin-top: 5px; flex-shrink: 0; }
.sign-card__dot--off { background: var(--c-text-4); }
.sign-card__text { color: var(--c-text); }
.sign-card__by { font-size: 10px; color: var(--c-text-3); margin-top: 2px; }

.mirror-note { display: flex; align-items: center; gap: 4px; font-size: var(--t-xs); color: var(--c-text-3); }

@media (max-width: 1200px) {
  .wo__body { grid-template-columns: 1fr; }
}
@media (max-width: 1024px) {
  .wo__body { grid-template-columns: 1fr; }
  .list-head { flex-direction: column; align-items: stretch; }
  .list-head__right { margin-left: 0; overflow-x: auto; }
  .det-meta { grid-template-columns: 1fr; }
}
</style>
