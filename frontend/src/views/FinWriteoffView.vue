<script setup lang="ts">
/* M6-09 划扣明细 /m6-writeoff — 财务只读镜像：已双签划扣=确认收入，未双签不计收入 */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CSelect from '@/components/CSelect.vue'
import { useWriteoffDeskStore, type WdStatus } from '@/stores/writeoffDesk'
import { useFinanceCoreStore } from '@/stores/financeCore'
import { useAuthStore } from '@/stores/auth'

const wd = useWriteoffDeskStore()
const fin = useFinanceCoreStore()
const auth = useAuthStore()
const canExport = computed(() => auth.can('finance:export'))

onMounted(() => wd.seed())

const filterStatus = ref<WdStatus | 'ALL'>('ALL')
const filterSource = ref<string>('ALL')

const list = computed(() =>
  wd.items.filter((i) =>
    (filterStatus.value === 'ALL' || i.status === filterStatus.value) &&
    (filterSource.value === 'ALL' || i.source === filterSource.value),
  ),
)

const STATUS_PILL: Record<WdStatus, 'success' | 'warning' | 'danger'> = {
  DONE: 'success', PENDING: 'warning', EXCEPTION: 'danger',
}

const selectedId = ref<string | null>(null)
const selected = computed(() => list.value.find((i) => i.id === selectedId.value) ?? list.value.find((i) => i.status === 'DONE') ?? list.value[0] ?? null)

const confirmedAmount = computed(() => wd.done.reduce((s, i) => s + i.amount, 0))
const pendingAmount = computed(() => wd.pending.reduce((s, i) => s + i.amount, 0))

const kpis = computed(() => [
  { label: '已双签确认收入', icon: 'check-square', value: `¥${confirmedAmount.value.toLocaleString('zh-CN')}`, tone: 'success' as const, sub: `${wd.done.length} 笔，计入营收` },
  { label: '待双签划扣', icon: 'check-square', value: `¥${pendingAmount.value.toLocaleString('zh-CN')}`, tone: 'warning' as const, sub: `${wd.pending.length} 笔，不计收入` },
  { label: '异常单', icon: 'alert', value: `${wd.exception.length} 笔`, tone: wd.exception.length ? 'danger' as const : 'text' as const, sub: '需门店核实' },
  { label: '镜像确认收入', icon: 'check-square', value: `¥${fin.writeoffConfirmed.toLocaleString('zh-CN')}`, tone: 'brand' as const, sub: '财务总账口径' },
])

function exportCsv() {
  if (!canExport.value) return
  const head = '划扣号,客户,项目,卡项,金额,操作人,复核人,来源,状态,时间\n'
  const rows = list.value.map((i) =>
    [i.no, i.customerName, i.project, i.cardName, i.amount, i.operator, i.reviewer ?? '', wd.SOURCE_LABEL[i.source], wd.STATUS_LABEL[i.status], i.executedAt ?? i.appointmentTime].join(','),
  ).join('\n')
  const blob = new Blob(['\uFEFF' + head + rows], { type: 'text/csv;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `划扣明细-${new Date().toISOString().slice(0, 10)}.csv`
  a.click()
  URL.revokeObjectURL(a.href)
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
        <span><strong>收入确认口径：</strong>仅「操作人 + 复核人」双签完成的划扣计入确认收入；待执行/异常单不计入营收。本页为门店划扣台的财务只读镜像，不可修改。</span>
      </div>
    </CCard>

    <div class="wo__body">
      <CCard class="wo__list" padding="none">
        <div class="list-head">
          <span class="list-head__title">划扣明细<span class="list-head__hint">{{ list.length }} 笔</span></span>
          <div class="list-head__right">
            <CSelect v-model="filterStatus" :options="[{ value: 'ALL', label: '全部状态' }, { value: 'DONE', label: '已双签' }, { value: 'PENDING', label: '待双签' }, { value: 'EXCEPTION', label: '异常' }]" />
            <CSelect v-model="filterSource" :options="[{ value: 'ALL', label: '全部来源' }, { value: 'APPOINTMENT', label: '预约到店' }, { value: 'WALKIN', label: '直接到店' }]" />
          </div>
        </div>
        <div class="w-list">
          <button
            v-for="i in list" :key="i.id"
            class="w-row" :class="{ 'w-row--active': selected?.id === i.id, [`w-row--${i.status.toLowerCase()}`]: true }"
            @click="selectedId = i.id"
          >
            <div class="w-row__top">
              <span class="w-row__no">{{ i.no }}</span>
              <CStatusPill :status="STATUS_PILL[i.status]" dot>{{ wd.STATUS_LABEL[i.status] }}</CStatusPill>
            </div>
            <div class="w-row__mid">
              <span class="w-row__proj">{{ i.project }}</span>
              <span class="w-row__amount">¥{{ i.amount.toLocaleString('zh-CN') }}</span>
            </div>
            <div class="w-row__sub">{{ i.customerName }} · {{ i.cardName }} · 余 {{ i.remainingCount }}/{{ i.totalCount }} 次</div>
            <div class="w-row__sign">
              <span class="sign" :class="{ on: !!i.operator }">操作 {{ i.operator || '—' }}</span>
              <span class="sign" :class="{ on: !!i.reviewer }">复核 {{ i.reviewer || '待签' }}</span>
            </div>
          </button>
        </div>
      </CCard>

      <CCard v-if="selected" class="wo__detail" padding="lg">
        <div class="det-head">
          <div>
            <h3 class="det-head__no">{{ selected.no }} · {{ selected.project }}</h3>
            <div class="det-head__sub">{{ selected.customerName }} {{ selected.phone }} · {{ wd.SOURCE_LABEL[selected.source] }}</div>
          </div>
          <div class="det-head__ops">
            <CStatusPill :status="STATUS_PILL[selected.status]" dot>{{ wd.STATUS_LABEL[selected.status] }}</CStatusPill>
            <CButton variant="secondary" size="sm" :disabled="!canExport" @click="exportCsv">
              <CIcon name="export" :size="14" />导出
            </CButton>
          </div>
        </div>

        <div class="det-amount">¥{{ selected.amount.toLocaleString('zh-CN') }}</div>
        <dl class="det-meta">
          <div><dt>会员卡项</dt><dd>{{ selected.cardName }}</dd></div>
          <div><dt>剩余 / 总次数</dt><dd>{{ selected.remainingCount }} / {{ selected.totalCount }}</dd></div>
          <div><dt>操作人</dt><dd>{{ selected.operator }}</dd></div>
          <div><dt>复核人</dt><dd>{{ selected.reviewer ?? '待双签' }}</dd></div>
          <div><dt>预约时间</dt><dd>{{ selected.appointmentTime }}</dd></div>
          <div><dt>划扣时间</dt><dd>{{ selected.executedAt ?? '—' }}</dd></div>
        </dl>

        <div v-if="selected.status === 'EXCEPTION'" class="exc-box">
          <CIcon name="alert" :size="15" />
          <span>异常原因：{{ wd.EXCEPTION_LABEL[selected.exceptionReason] }}（不计入收入，需门店核实后重提）</span>
        </div>

        <div class="tl">
          <div class="tl__title"><CIcon name="clock" :size="13" />双签轨迹</div>
          <div v-for="(t, idx) in selected.timeline" :key="idx" class="tl__row">
            <span class="tl__dot"></span>
            <div class="tl__body">
              <div class="tl__text">{{ t.text }}</div>
              <div class="tl__by">{{ t.by }} · {{ t.at }}</div>
            </div>
          </div>
        </div>

        <div class="mirror-note"><CIcon name="shield" :size="13" />划扣数据单向镜像自门店划扣执行台，财务域只读。</div>
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
.list-head__right { display: flex; align-items: center; gap: var(--s-sm); flex-shrink: 0; flex-wrap: nowrap; margin-left: auto; }
.w-list { max-height: 620px; overflow-y: auto; }
.w-row { display: block; width: 100%; text-align: left; padding: var(--s-md) var(--s-lg); background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer; border-left: 3px solid transparent; }
.w-row:hover { background: var(--c-brand-soft); }
.w-row--active { background: var(--c-brand-soft); border-left-color: var(--c-brand); }
.w-row--done { border-left-color: transparent; }
.w-row--pending { border-left-color: var(--c-warning-fg); }
.w-row--exception { border-left-color: var(--c-danger-fg); }
.w-row__top { display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px; }
.w-row__no { font-size: var(--t-xs); color: var(--c-text-3); font-variant-numeric: tabular-nums; }
.w-row__mid { display: flex; justify-content: space-between; align-items: baseline; }
.w-row__proj { font-size: var(--t-sm); font-weight: 600; }
.w-row__amount { font-size: var(--t-md); font-weight: 700; font-variant-numeric: tabular-nums; }
.w-row__sub { font-size: var(--t-xs); color: var(--c-text-3); margin: 2px 0 6px; }
.w-row__sign { display: flex; gap: 6px; font-size: var(--t-xs); }
.sign { padding: 1px 6px; border-radius: var(--r-sm); background: var(--c-disabled-bg); color: var(--c-text-3); }
.sign.on { background: var(--c-success-soft, rgba(22,163,110,.12)); color: var(--c-success-fg); }

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
.exc-box { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-sm) var(--s-md); background: rgba(229,57,53,.08); color: var(--c-danger-fg); border-radius: var(--r-sm); font-size: var(--t-xs); }
.tl { border-top: 1px solid var(--c-border-light); padding-top: var(--s-md); }
.tl__title { display: flex; align-items: center; gap: 4px; font-size: var(--t-xs); font-weight: 700; color: var(--c-text-2); margin-bottom: var(--s-sm); }
.tl__row { display: flex; gap: var(--s-sm); padding: 4px 0; }
.tl__dot { width: 8px; height: 8px; border-radius: 50%; background: var(--c-brand); margin-top: 5px; flex-shrink: 0; }
.tl__text { font-size: var(--t-xs); color: var(--c-text); }
.tl__by { font-size: 10px; color: var(--c-text-3); }
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
