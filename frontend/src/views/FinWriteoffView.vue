<script setup lang="ts">
/* M6-09 划扣明细 /m6-writeoff — 财务只读镜像：已双签（台账已对账）划扣=确认收入，未对账不计收入。
 * 数据源：financeCore.entries 中 refType=WRITEOFF 的台账分录（finance-service 读时聚合）。
 * 门店划扣台「当日队列」(writeoff-desk) 仅反映当日待执行/已执行任务，不含历史划扣，
 * 故历史双签划扣以财务台账为准；台账不含客户/卡项/双签人/次数明细，诚实标注待接入，不编造。 */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CSelect from '@/components/CSelect.vue'
import { useFinanceCoreStore } from '@/stores/financeCore'
import { useAuthStore } from '@/stores/auth'

const fin = useFinanceCoreStore()
const auth = useAuthStore()
const canExport = computed(() => auth.can('finance:export'))

onMounted(() => void fin.seed())

type WoStatus = 'DONE' | 'PENDING'
const STATUS_LABEL: Record<WoStatus, string> = { DONE: '已双签', PENDING: '待对账' }
const STATUS_PILL: Record<WoStatus, 'success' | 'warning'> = { DONE: 'success', PENDING: 'warning' }

/** 台账 memo「划扣确认收入 · 项目名」→ 项目名（项目名本身可能含「·」，如 黑卡·抗衰，故只剥第一段） */
function projectOf(memo: string) {
  const idx = memo.indexOf('·')
  return idx >= 0 ? memo.slice(idx + 1).trim() : memo
}

interface WoRow {
  id: string
  no: string
  date: string
  amount: number
  project: string
  store: string
  reconciled: boolean
  status: WoStatus
  /** 配对的 RF-DEPOSIT OUT（预收转出）金额，用于勾稽展示；无配对分录为 null */
  depositOut: number | null
}

/** 划扣列表：取 WRITEOFF 的 RF-REVENUE IN 侧（每笔划扣一行，金额=确认收入，避免与预收转出重复计） */
const writeoffs = computed<WoRow[]>(() =>
  fin.entries
    .filter((e) => e.refType === 'WRITEOFF' && e.subject === 'RF-REVENUE')
    .map((e) => {
      const dep = fin.entries.find(
        (x) => x.refType === 'WRITEOFF' && x.subject === 'RF-DEPOSIT' && x.refNo === e.refNo,
      )
      return {
        id: e.id,
        no: e.refNo,
        date: e.date,
        amount: e.amount,
        project: projectOf(e.memo),
        store: e.store,
        reconciled: e.reconciled,
        status: (e.reconciled ? 'DONE' : 'PENDING') as WoStatus,
        depositOut: dep ? dep.amount : null,
      }
    })
    .sort((a, b) => (a.date < b.date ? 1 : a.date > b.date ? -1 : a.no < b.no ? 1 : -1)),
)

const filterStatus = ref<WoStatus | 'ALL'>('ALL')
const filterStore = ref<string>('ALL')
const storeOptions = computed(() => [
  { value: 'ALL', label: '全部门店' },
  ...[...new Set(writeoffs.value.map((w) => w.store))].map((s) => ({ value: s, label: s })),
])

const list = computed(() =>
  writeoffs.value.filter(
    (w) =>
      (filterStatus.value === 'ALL' || w.status === filterStatus.value) &&
      (filterStore.value === 'ALL' || w.store === filterStore.value),
  ),
)

const doneCount = computed(() => writeoffs.value.filter((w) => w.reconciled).length)
const pendingCount = computed(() => writeoffs.value.filter((w) => !w.reconciled).length)

const selectedId = ref<string | null>(null)
const selected = computed(
  () => list.value.find((w) => w.id === selectedId.value) ?? list.value[0] ?? null,
)

const kpis = computed(() => [
  { label: '已双签确认收入', icon: 'check-square', value: `¥${fin.writeoffConfirmed.toLocaleString('zh-CN')}`, tone: 'success' as const, sub: `${doneCount.value} 笔，台账已对账，计入营收` },
  { label: '待双签划扣', icon: 'check-square', value: `¥${fin.writeoffPending.toLocaleString('zh-CN')}`, tone: 'warning' as const, sub: `${pendingCount.value} 笔未对账，不计收入` },
  { label: '异常单', icon: 'alert', value: '0 笔', tone: 'text' as const, sub: '划扣异常数据源待接入' },
  { label: '预收转出（划扣消耗）', icon: 'shield', value: `¥${fin.depositConsume.toLocaleString('zh-CN')}`, tone: 'brand' as const, sub: 'RF-DEPOSIT 流出，与确认收入勾稽' },
])

function exportCsv() {
  if (!canExport.value) return
  const head = '划扣号,划扣日期,门店,项目,确认收入金额,预收转出金额,对账状态\n'
  const rows = list.value
    .map((w) =>
      [w.no, w.date, w.store, w.project, w.amount, w.depositOut ?? '', w.reconciled ? '已对账' : '待对账'].join(','),
    )
    .join('\n')
  const blob = new Blob(['﻿' + head + rows], { type: 'text/csv;charset=utf-8' })
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
        <span><strong>收入确认口径：</strong>仅「操作人 + 复核人」双签完成（台账已对账）的划扣计入确认收入；待对账划扣不计入营收。本页为财务总账台账的只读镜像（按划扣号取确认收入侧分录），不可修改。</span>
      </div>
    </CCard>

    <div class="wo__body">
      <CCard class="wo__list" padding="none">
        <div class="list-head">
          <span class="list-head__title">划扣明细<span class="list-head__hint">{{ list.length }} 笔</span></span>
          <div class="list-head__right">
            <CSelect v-model="filterStatus" :options="[{ value: 'ALL', label: '全部状态' }, { value: 'DONE', label: '已双签' }, { value: 'PENDING', label: '待对账' }]" />
            <CSelect v-model="filterStore" :options="storeOptions" />
          </div>
        </div>
        <div class="w-list">
          <button
            v-for="w in list" :key="w.id"
            class="w-row" :class="{ 'w-row--active': selected?.id === w.id, [`w-row--${w.status.toLowerCase()}`]: true }"
            @click="selectedId = w.id"
          >
            <div class="w-row__top">
              <span class="w-row__no">{{ w.no }}</span>
              <CStatusPill :status="STATUS_PILL[w.status]" dot>{{ STATUS_LABEL[w.status] }}</CStatusPill>
            </div>
            <div class="w-row__mid">
              <span class="w-row__proj">{{ w.project }}</span>
              <span class="w-row__amount">¥{{ w.amount.toLocaleString('zh-CN') }}</span>
            </div>
            <div class="w-row__sub">{{ w.store }} · {{ w.date }}</div>
            <div class="w-row__sign">
              <span class="sign" :class="{ on: w.reconciled }">{{ w.reconciled ? '双签完成 · 台账已对账' : '待对账' }}</span>
            </div>
          </button>
          <div v-if="!list.length" class="empty">
            <CIcon name="check-square" :size="28" class="empty__icon" />
            <p>台账暂无划扣分录</p>
            <p class="empty__hint">不生成演示划扣数据；划扣台历史队列接入后此处展示全部双签划扣</p>
          </div>
        </div>
      </CCard>

      <CCard v-if="selected" class="wo__detail" padding="lg">
        <div class="det-head">
          <div>
            <h3 class="det-head__no">{{ selected.no }} · {{ selected.project }}</h3>
            <div class="det-head__sub">{{ selected.store }} · 划扣日期 {{ selected.date }}</div>
          </div>
          <div class="det-head__ops">
            <CStatusPill :status="STATUS_PILL[selected.status]" dot>{{ STATUS_LABEL[selected.status] }}</CStatusPill>
            <CButton variant="secondary" size="sm" :disabled="!canExport" @click="exportCsv">
              <CIcon name="export" :size="14" />导出
            </CButton>
          </div>
        </div>

        <div class="det-amount">¥{{ selected.amount.toLocaleString('zh-CN') }}</div>
        <dl class="det-meta">
          <div><dt>划扣单号</dt><dd>{{ selected.no }}</dd></div>
          <div><dt>划扣日期</dt><dd>{{ selected.date }}</dd></div>
          <div><dt>所属门店</dt><dd>{{ selected.store }}</dd></div>
          <div><dt>服务项目</dt><dd>{{ selected.project }}</dd></div>
          <div><dt>台账对账状态</dt><dd>{{ selected.reconciled ? '已对账（双签完成）' : '待对账' }}</dd></div>
          <div><dt>预收转出额</dt><dd>{{ selected.depositOut != null ? `¥${selected.depositOut.toLocaleString('zh-CN')}` : '—' }}</dd></div>
        </dl>

        <div class="na-box">
          <CIcon name="alert" :size="15" />
          <span>台账镜像仅含划扣号、金额、项目、门店与对账标记；<strong>客户姓名、会员卡项、剩余次数、操作人/复核人双签明细</strong>待划扣台历史查询端点接入后展示，本页不编造。</span>
        </div>

        <div class="tl">
          <div class="tl__title"><CIcon name="clock" :size="13" />台账勾稽分录（同号配对）</div>
          <div class="tl__row">
            <span class="tl__dot"></span>
            <div class="tl__body">
              <div class="tl__text">主营业务收入（RF-REVENUE）<strong class="tl-in">+¥{{ selected.amount.toLocaleString('zh-CN') }}</strong> · 划扣确认收入</div>
              <div class="tl__by">财务总账 · {{ selected.date }}</div>
            </div>
          </div>
          <div class="tl__row">
            <span class="tl__dot tl__dot--out"></span>
            <div class="tl__body">
              <div class="tl__text">预收账款（RF-DEPOSIT）<strong class="tl-out">-¥{{ (selected.depositOut ?? selected.amount).toLocaleString('zh-CN') }}</strong> · 卡划扣预收转出</div>
              <div class="tl__by">财务总账 · {{ selected.date }} · 与确认收入同号配对，金额相等即勾稽一致</div>
            </div>
          </div>
        </div>

        <div class="mirror-note"><CIcon name="shield" :size="13" />划扣数据单向镜像自财务总账台账（finance-service 读时聚合），财务域只读。</div>
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
.na-box { display: flex; align-items: flex-start; gap: var(--s-sm); padding: var(--s-sm) var(--s-md); background: var(--c-brand-soft); color: var(--c-text-2); border-radius: var(--r-sm); font-size: var(--t-xs); line-height: 1.7; }
.na-box strong { color: var(--c-brand); }
.tl { border-top: 1px solid var(--c-border-light); padding-top: var(--s-md); }
.tl__title { display: flex; align-items: center; gap: 4px; font-size: var(--t-xs); font-weight: 700; color: var(--c-text-2); margin-bottom: var(--s-sm); }
.tl__row { display: flex; gap: var(--s-sm); padding: 4px 0; }
.tl__dot { width: 8px; height: 8px; border-radius: 50%; background: var(--c-success-fg); margin-top: 5px; flex-shrink: 0; }
.tl__dot--out { background: var(--c-warning-fg); }
.tl__text { font-size: var(--t-xs); color: var(--c-text); }
.tl-in { color: var(--c-success-fg); font-variant-numeric: tabular-nums; }
.tl-out { color: var(--c-warning-fg); font-variant-numeric: tabular-nums; }
.tl__by { font-size: 10px; color: var(--c-text-3); margin-top: 2px; }
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
