<script setup lang="ts">
/* ============================================================
 * M6-02 收支流水 /m6-ledger
 * 红线：全量收支明细的「只读镜像」，每一笔钱可追溯；
 *       仅可切换对账标记（不碰金额/资金），transaction_id 幂等。
 * 4 KPI（本期收入/支出/净额/待对账）+ 筛选 + 明细表 + 侧栏科目归类
 * ============================================================ */
import { computed, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CSelect from '@/components/CSelect.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import { useFinanceCoreStore, SUBJECT_LABEL, type LedgerEntry } from '@/stores/financeCore'
import { useAuthStore } from '@/stores/auth'

const fin = useFinanceCoreStore()
const auth = useAuthStore()
const canReconcile = computed(() => auth.can('finance:reconcile'))
const canExport = computed(() => auth.can('finance:export'))
const canSeeMargin = computed(() => auth.can('finance:margin:view'))

const dirFilter = ref<'ALL' | 'IN' | 'OUT'>('ALL')
const typeFilter = ref<'ALL' | 'RF' | 'TK'>('ALL')
const channelFilter = ref('ALL')
const kw = ref('')

const dirOptions = [
  { value: 'ALL', label: '全部方向' },
  { value: 'IN', label: '收入' },
  { value: 'OUT', label: '支出' },
]
const typeOptions = [
  { value: 'ALL', label: '全部科目' },
  { value: 'RF', label: '资金类 RF' },
  { value: 'TK', label: '成本库存类 TK' },
]
const channelOptions = [
  { value: 'ALL', label: '全部渠道' },
  { value: 'cash', label: '现金' },
  { value: 'wxpay', label: '微信支付' },
  { value: 'alipay', label: '支付宝' },
  { value: 'card', label: '刷卡' },
  { value: 'bank', label: '银行转账' },
  { value: 'balance', label: '余额' },
]
const CHANNEL_LABEL: Record<string, string> = {
  cash: '现金', wxpay: '微信', alipay: '支付宝', card: '刷卡', bank: '银行', balance: '余额', transfer: '转账',
}
const REF_LABEL: Record<string, string> = {
  ORDER: '收银单', REFUND: '退款单', RECHARGE: '充值', WRITEOFF: '划扣',
  LOSS: '报损', DEP: '折旧', PURCHASE: '耗材出库', SETTLE: '结算', ADJUST: '调整',
}

const filtered = computed<LedgerEntry[]>(() => {
  return fin.entries
    .filter((e) => dirFilter.value === 'ALL' || e.direction === dirFilter.value)
    .filter((e) => typeFilter.value === 'ALL' || e.subject.startsWith(typeFilter.value))
    .filter((e) => channelFilter.value === 'ALL' || e.channel === channelFilter.value)
    .filter((e) => !kw.value || e.memo.includes(kw.value) || e.refNo.toLowerCase().includes(kw.value.toLowerCase()) || e.txnId.toLowerCase().includes(kw.value.toLowerCase()))
    .sort((a, b) => (a.date < b.date ? 1 : -1))
})

const income = computed(() => fin.entries.filter((e) => e.direction === 'IN').reduce((s, e) => s + e.amount, 0))
const expense = computed(() => fin.entries.filter((e) => e.direction === 'OUT').reduce((s, e) => s + e.amount, 0))
const net = computed(() => income.value - expense.value)
const unreconciled = computed(() => fin.entries.filter((e) => !e.reconciled).length)

const kpis = computed(() => [
  { label: '本期收入（镜像）', icon: 'finance', value: `¥${income.value.toLocaleString('zh-CN')}`, tone: 'success' as const, sub: '资金类 RF' },
  { label: '本期支出', icon: 'finance', value: canSeeMargin.value ? `¥${expense.value.toLocaleString('zh-CN')}` : '¥****', tone: 'danger' as const, sub: '含成本类 TK' },
  { label: '收支净额', icon: 'finance', value: canSeeMargin.value ? `¥${net.value.toLocaleString('zh-CN')}` : '¥****', tone: net.value >= 0 ? ('brand' as const) : ('danger' as const), sub: '收入 − 支出' },
  { label: '待对账笔数', icon: 'finance', value: `${unreconciled.value} 笔`, tone: unreconciled.value ? ('warning' as const) : ('text' as const), sub: 'Outbox 轧平中' },
])

// 科目归类统计（侧栏）
const subjectGroups = computed(() => {
  const map = new Map<string, number>()
  for (const e of fin.entries) {
    const key = e.subject
    const sign = e.direction === 'IN' ? 1 : -1
    map.set(key, (map.get(key) ?? 0) + sign * e.amount)
  }
  return [...map.entries()].map(([code, amount]) => ({
    code, name: SUBJECT_LABEL[code as keyof typeof SUBJECT_LABEL], amount,
    kind: code.startsWith('RF') ? 'RF' : 'TK',
  })).sort((a, b) => Math.abs(b.amount) - Math.abs(a.amount))
})

function money(n: number) {
  return `¥${Math.abs(n).toLocaleString('zh-CN')}`
}

function exportCsv() {
  if (!canExport.value) return
  const head = '日期,交易号,科目,方向,金额,渠道,来源,关联单,门店,摘要,对账\n'
  const rows = filtered.value.map((e) =>
    [e.date, e.txnId, `${e.subject} ${SUBJECT_LABEL[e.subject]}`, e.direction === 'IN' ? '收入' : '支出',
      e.amount, e.channel ? CHANNEL_LABEL[e.channel] : '', e.source, `${e.refType}:${e.refNo}`, e.store, e.memo, e.reconciled ? '已对' : '待对'].join(',')).join('\n')
  const blob = new Blob(['\uFEFF' + head + rows], { type: 'text/csv;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `收支流水-${new Date().toISOString().slice(0, 10)}.csv`
  a.click()
  URL.revokeObjectURL(a.href)
}
</script>

<template>
  <div class="lg">
    <div class="lg__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <CCard class="lg__tip" padding="md">
      <div class="tip-line">
        <CIcon name="finance" :size="16" />
        <span>所有流水均为支付系统 <strong>单向镜像</strong>，以 <code>transaction_id</code> 幂等；财务仅可标记对账状态，不可修改金额。</span>
      </div>
    </CCard>

    <div class="lg__body">
      <CCard class="lg__list" padding="none">
        <div class="filters">
          <CSelect v-model="dirFilter" width="110px" :options="dirOptions" />
          <CSelect v-model="typeFilter" width="130px" :options="typeOptions" />
          <CSelect v-model="channelFilter" width="120px" :options="channelOptions" />
          <input v-model="kw" class="search" placeholder="搜索摘要 / 单号 / 交易号" />
          <CButton variant="secondary" size="sm" class="filters__btn" :disabled="!canExport" @click="exportCsv">
            <CIcon name="export" :size="14" />导出流水
          </CButton>
        </div>
        <div class="table-wrap">
          <table class="tbl">
            <thead>
              <tr>
                <th>日期</th><th>交易号 / 摘要</th><th>科目</th><th>渠道</th>
                <th class="num">金额</th><th>关联单</th><th>对账</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="e in filtered" :key="e.id" :class="{ 'row--out': e.direction === 'OUT', 'row--pending': !e.reconciled }">
                <td class="dim">{{ e.date }}</td>
                <td>
                  <div class="txn">
                    <span class="txn__id">{{ e.txnId }}</span>
                    <span class="txn__memo">{{ e.memo }}</span>
                  </div>
                </td>
                <td>
                  <span class="subj" :class="`subj--${e.subject.slice(0, 2).toLowerCase()}`">{{ e.subject }}</span>
                  <span class="dim subj-name">{{ SUBJECT_LABEL[e.subject] }}</span>
                </td>
                <td class="dim">{{ e.channel ? CHANNEL_LABEL[e.channel] : '—' }}</td>
                <td class="num amt" :class="{ 'amt--in': e.direction === 'IN', 'amt--out': e.direction === 'OUT' }">
                  {{ e.direction === 'IN' ? '+' : '−' }}{{ money(e.amount) }}
                </td>
                <td>
                  <CStatusPill status="info" dot>{{ REF_LABEL[e.refType] }}</CStatusPill>
                  <span class="dim refno">{{ e.refNo }}</span>
                </td>
                <td>
                  <button
                    class="recon-btn" :class="{ 'is-on': e.reconciled }"
                    :disabled="!canReconcile" :title="canReconcile ? '切换对账标记' : '无对账权限'"
                    @click="fin.toggleReconciled(e.id)"
                  >
                    <CIcon :name="e.reconciled ? 'check' : 'clock'" :size="13" />
                    {{ e.reconciled ? '已对' : '待对' }}
                  </button>
                </td>
              </tr>
              <tr v-if="filtered.length === 0">
                <td colspan="7" class="empty">无匹配流水</td>
              </tr>
            </tbody>
          </table>
        </div>
      </CCard>

      <CCard class="lg__side" title="科目归类（RF / TK）" padding="md">
        <p class="side-hint">RF 资金类与 TK 成本库存类 <strong>科目分离</strong>，互不混记。</p>
        <div class="subj-list">
          <div v-for="g in subjectGroups" :key="g.code" class="subj-row">
            <div class="subj-row__top">
              <span class="subj" :class="`subj--${g.kind.toLowerCase()}`">{{ g.code }}</span>
              <span class="subj-row__name">{{ g.name }}</span>
            </div>
            <div class="subj-row__amt" :class="{ 'is-neg': g.amount < 0 }">
              {{ g.amount < 0 ? '−' : '' }}{{ money(g.amount) }}
            </div>
          </div>
        </div>
        <div class="identity-hint">
          <CIcon name="check-square" :size="14" />
          <span>RF 资金链与 TK 成本链在报表层通过 8 大恒等式勾稽。</span>
        </div>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.lg { display: flex; flex-direction: column; gap: var(--s-lg); }
.lg__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .lg__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }

.lg__tip { background: var(--c-brand-soft); border: 1px solid var(--c-border-light); }
.tip-line { display: flex; align-items: center; gap: var(--s-sm); font-size: var(--t-xs); color: var(--c-text-2); }
.tip-line code { background: var(--c-bg-right); padding: 1px 6px; border-radius: 4px; font-size: var(--t-xs); color: var(--c-brand); }

.lg__body { display: grid; grid-template-columns: 1fr 300px; gap: var(--s-lg); align-items: start; }
.lg__list { min-width: 0; }
.filters { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-md); border-bottom: 1px solid var(--c-border-light); flex-wrap: wrap; }
.filters__btn { flex-shrink: 0; }
.search { flex: 1; min-width: 160px; height: 32px; padding: 0 var(--s-sm); border: 1px solid var(--c-border); border-radius: var(--r-sm); font-size: var(--t-sm); background: var(--c-bg); color: var(--c-text); }
.search:focus { outline: none; border-color: var(--c-brand); }
.table-wrap { overflow-x: auto; }
.tbl { width: 100%; border-collapse: collapse; font-size: var(--t-sm); }
.tbl th { text-align: left; padding: var(--s-sm) var(--s-md); font-size: var(--t-xs); color: var(--c-text-3); font-weight: 600; background: var(--c-bg-right); white-space: nowrap; }
.tbl td { padding: var(--s-md); border-bottom: 1px solid var(--c-border-light); vertical-align: middle; }
.tbl tbody tr:hover { background: var(--c-brand-soft); }
.tbl .num { text-align: right; font-variant-numeric: tabular-nums; white-space: nowrap; }
.dim { color: var(--c-text-3); font-size: var(--t-xs); }
.row--pending { background: rgba(245,158,11,.06); }
.txn { display: flex; flex-direction: column; gap: 2px; }
.txn__id { font-size: var(--t-xs); color: var(--c-text-3); font-variant-numeric: tabular-nums; }
.txn__memo { font-size: var(--t-sm); color: var(--c-text); }
.subj { display: inline-block; font-size: var(--t-xs); font-weight: 700; padding: 2px 7px; border-radius: var(--r-sm); margin-right: 6px; letter-spacing: .3px; }
.subj--rf { background: rgba(94,114,228,.12); color: var(--c-brand); }
.subj--tk { background: rgba(245,158,11,.14); color: var(--c-warning-fg); }
.subj-name { vertical-align: middle; }
.amt { font-weight: 700; }
.amt--in { color: var(--c-success-fg); }
.amt--out { color: var(--c-danger-fg); }
.refno { margin-left: 6px; }
.recon-btn { display: inline-flex; align-items: center; gap: 4px; border: 1px solid var(--c-border); background: var(--c-bg); border-radius: var(--r-sm); padding: 3px 10px; font-size: var(--t-xs); color: var(--c-text-3); cursor: pointer; }
.recon-btn:disabled { opacity: .5; cursor: not-allowed; }
.recon-btn.is-on { background: var(--c-success-soft, rgba(22,163,110,.1)); border-color: transparent; color: var(--c-success-fg); }
.empty { text-align: center; color: var(--c-text-3); padding: var(--s-xxl); }

.lg__side { position: sticky; top: 0; }
.side-hint { font-size: var(--t-xs); color: var(--c-text-3); margin: 0 0 var(--s-md); }
.subj-list { display: flex; flex-direction: column; gap: var(--s-sm); }
.subj-row { display: flex; flex-direction: column; gap: 4px; padding: var(--s-sm) 0; border-bottom: 1px dashed var(--c-border-light); }
.subj-row__top { display: flex; align-items: center; gap: var(--s-sm); }
.subj-row__name { font-size: var(--t-xs); color: var(--c-text-2); }
.subj-row__amt { font-size: var(--t-md); font-weight: 700; text-align: right; font-variant-numeric: tabular-nums; color: var(--c-text); }
.subj-row__amt.is-neg { color: var(--c-danger-fg); }
.identity-hint { display: flex; gap: 6px; align-items: flex-start; margin-top: var(--s-md); padding: var(--s-sm); background: var(--c-bg-right); border-radius: var(--r-sm); font-size: var(--t-xs); color: var(--c-text-3); }

@media (max-width: 1024px) {
  .lg__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .lg__body { grid-template-columns: 1fr; }
  .lg__side { position: static; }
}
</style>
