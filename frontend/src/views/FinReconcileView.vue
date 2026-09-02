<script setup lang="ts">
/* ============================================================
 * M6-03 对账中心 /m6-reconcile（红线核心）
 * T+1 三方对账：收银 / 支付渠道 / 银行，Outbox 幂等 transaction_id
 * 长款/短款/冲正差异；一键轧平；人工调平需双签复核（不反向动账）
 * 4 KPI + 三方视图 + 差异清单 + 8 大恒等式面板
 * ============================================================ */
import { computed, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CInput from '@/components/CInput.vue'
import CTextarea from '@/components/CTextarea.vue'
import { useFinanceCoreStore, type OutboxItem } from '@/stores/financeCore'
import { useAuthStore } from '@/stores/auth'

const fin = useFinanceCoreStore()
const auth = useAuthStore()
const canReconcile = computed(() => auth.can('finance:reconcile'))
const canApprove = computed(() => auth.can('finance:reconcile:approve'))
const canExport = computed(() => auth.can('finance:export'))

const selectedId = ref<string | null>(null)
const selected = computed<OutboxItem | null>(() => {
  if (selectedId.value) return fin.outbox.find((o) => o.outboxId === selectedId.value) ?? null
  return fin.outbox.find((o) => o.status !== 'MATCHED') ?? fin.outbox[0] ?? null
})

const totalMatched = computed(() => fin.outbox.filter((o) => o.status === 'MATCHED').length)
const totalRecords = computed(() => fin.outbox.length)
const matchRate = computed(() => totalRecords.value ? Math.round((totalMatched.value / totalRecords.value) * 100) : 0)
const diffCount = computed(() => fin.outbox.filter((o) => o.status === 'LONG' || o.status === 'SHORT').length)

const kpis = computed(() => [
  { label: '对账总笔数', icon: 'finance', value: `${totalRecords.value} 笔`, tone: 'brand' as const, sub: 'Outbox 镜像' },
  { label: '已轧平', icon: 'finance', value: `${totalMatched.value} 笔`, tone: 'success' as const, sub: `匹配率 ${matchRate.value}%` },
  { label: '长款 / 短款', icon: 'alert', value: `¥${fin.outboxLong.toLocaleString('zh-CN')} / ¥${fin.outboxShort.toLocaleString('zh-CN')}`, tone: diffCount.value ? ('danger' as const) : ('text' as const), sub: `${diffCount.value} 笔差异待处理` },
  { label: '待对账 / 冲正', icon: 'refund', value: `${fin.outboxPending} 笔`, tone: fin.outboxPending ? ('warning' as const) : ('text' as const), sub: 'T+1 三方核对' },
])

const BIZ_LABEL: Record<string, string> = {
  ORDER_PAY: '收银支付', REFUND: '退款', RECHARGE: '充值', WRITEOFF: '划扣', SETTLE: '结算',
}
const STATUS_PILL: Record<OutboxItem['status'], 'success' | 'warning' | 'danger' | 'info' | 'primary'> = {
  MATCHED: 'success', PENDING: 'warning', LONG: 'info', SHORT: 'danger', REVERSED: 'primary',
}
const STATUS_LABEL: Record<OutboxItem['status'], string> = {
  MATCHED: '已平', PENDING: '待对账', LONG: '长款', SHORT: '短款', REVERSED: '冲正',
}

// 三方金额（用于详情对比；演示：长款银行多、短款银行少）
function triad(o: OutboxItem) {
  const cashier = o.amount
  let channel = o.amount
  let bank = o.amount
  if (o.status === 'LONG') { bank = o.amount + 100 }
  if (o.status === 'SHORT') { channel = o.amount - 6; bank = o.amount - 6 }
  if (o.status === 'PENDING') { bank = 0 }
  if (o.status === 'REVERSED') { bank = 0; channel = 0 }
  return { cashier, channel, bank }
}

function runAuto() {
  const n = fin.runReconcile()
  flash.value = `一键对账完成：本次轧平 ${n} 笔`
  setTimeout(() => (flash.value = ''), 3000)
}
const flash = ref('')

// 人工调平双签弹层
const showAdjust = ref(false)
const adjustForm = ref({ reviewer: '', remark: '' })
const canSubmitAdjust = computed(() => adjustForm.value.reviewer.trim().length > 1 && adjustForm.value.remark.trim().length > 1)
function openAdjust() {
  if (!selected.value) return
  adjustForm.value = { reviewer: '', remark: '' }
  showAdjust.value = true
}
function submitAdjust() {
  if (!selected.value || !canSubmitAdjust.value) return
  fin.adjustOutbox(selected.value.outboxId, `${adjustForm.value.remark}（复核：${adjustForm.value.reviewer}）`)
  showAdjust.value = false
  flash.value = '差异已人工调平（仅记调平记录，未反向动账）'
  setTimeout(() => (flash.value = ''), 3500)
}

function exportReport() {
  if (!canExport.value) return
  const head = 'Outbox号,业务类型,交易号,金额,渠道,收银,渠道回单,银行到账,状态\n'
  const rows = fin.outbox.map((o) => {
    const t = triad(o)
    return [o.outboxId, BIZ_LABEL[o.bizType], o.txnNo, o.amount, o.channel, t.cashier, t.channel, t.bank, STATUS_LABEL[o.status]].join(',')
  }).join('\n')
  const blob = new Blob(['\uFEFF' + head + rows], { type: 'text/csv;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `对账报告-${new Date().toISOString().slice(0, 10)}.csv`
  a.click()
  URL.revokeObjectURL(a.href)
}
</script>

<template>
  <div class="rc">
    <div class="rc__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <CCard v-if="flash" class="rc__flash" padding="md">
      <div class="flash-line"><CIcon name="check" :size="16" />{{ flash }}</div>
    </CCard>

    <div class="rc__body">
      <!-- 左：Outbox 对账清单 -->
      <CCard class="rc__list" padding="none">
        <div class="list-head">
          <div class="list-head__left">
            <span class="list-head__title">Outbox 对账流水</span>
            <span class="list-head__hint">收银 → 渠道 → 银行 三方核对</span>
          </div>
        </div>
        <div class="ob-list">
          <button
            v-for="o in fin.outbox" :key="o.outboxId"
            class="ob-row" :class="{ 'ob-row--active': selected?.outboxId === o.outboxId, [`ob-row--${o.status.toLowerCase()}`]: true }"
            @click="selectedId = o.outboxId"
          >
            <div class="ob-row__top">
              <span class="ob-row__no">{{ o.outboxId }}</span>
              <CStatusPill :status="STATUS_PILL[o.status]" dot>{{ STATUS_LABEL[o.status] }}</CStatusPill>
            </div>
            <div class="ob-row__mid">
              <span class="ob-row__biz">{{ BIZ_LABEL[o.bizType] }}</span>
              <span class="ob-row__amount">¥{{ o.amount.toLocaleString('zh-CN') }}</span>
            </div>
            <div class="ob-row__sub">{{ o.txnNo }} · {{ o.channel }} · {{ o.occurredAt }}</div>
            <div class="triad-mini">
              <span class="t" :class="{ on: o.cashier }">收银</span>
              <span class="arr">→</span>
              <span class="t" :class="{ on: o.channelAck }">渠道</span>
              <span class="arr">→</span>
              <span class="t" :class="{ on: o.bankAck }">银行</span>
            </div>
          </button>
        </div>
      </CCard>

      <!-- 右：三方对账详情 -->
      <CCard v-if="selected" class="rc__detail" padding="lg">
        <div class="det-head">
          <div>
            <h3 class="det-head__no">{{ selected.outboxId }} · {{ BIZ_LABEL[selected.bizType] }}</h3>
            <div class="det-head__sub">{{ selected.txnNo }} · {{ selected.occurredAt }}</div>
          </div>
          <div class="det-head__right">
            <CStatusPill :status="STATUS_PILL[selected.status]" dot>{{ STATUS_LABEL[selected.status] }}</CStatusPill>
            <CButton variant="secondary" size="sm" :disabled="!canExport" @click="exportReport">
              <CIcon name="export" :size="14" />导出
            </CButton>
            <CButton variant="primary" size="sm" :disabled="!canReconcile" @click="runAuto">
              <CIcon name="check-square" :size="14" />对账
            </CButton>
          </div>
        </div>

        <!-- 三方金额对比 -->
        <div class="triad">
          <template v-for="(col, i) in [
            { label: '收银记账', key: 'cashier', icon: 'pos', ack: selected.cashier },
            { label: '支付渠道', key: 'channel', icon: 'marketing', ack: selected.channelAck },
            { label: '银行到账', key: 'bank', icon: 'finance', ack: selected.bankAck },
          ]" :key="col.key">
            <div class="triad__col" :class="{ 'triad__col--miss': !col.ack }">
              <div class="triad__label">
                <CIcon :name="col.icon as 'pos'|'marketing'|'finance'" :size="15" />{{ col.label }}
              </div>
              <div class="triad__amount">¥{{ triad(selected)[col.key as 'cashier'|'channel'|'bank'].toLocaleString('zh-CN') }}</div>
              <div class="triad__ack" :class="col.ack ? 'is-ok' : 'is-miss'">
                <CIcon :name="col.ack ? 'check' : 'clock'" :size="12" />{{ col.ack ? '已确认' : '未到账' }}
              </div>
            </div>
            <div v-if="i < 2" class="triad__arrow">
              <CIcon name="chevron-right" :size="18" />
            </div>
          </template>
        </div>

        <!-- 差异说明 -->
        <div v-if="selected.status === 'LONG' || selected.status === 'SHORT' || selected.status === 'REVERSED'" class="diff-box" :class="`diff-box--${selected.status.toLowerCase()}`">
          <CIcon name="alert" :size="16" />
          <div>
            <div v-if="selected.status === 'LONG'" class="diff-box__title">长款 ¥{{ (triad(selected).bank - selected.amount).toLocaleString('zh-CN') }}：银行实际到账多于收银记录</div>
            <div v-else-if="selected.status === 'SHORT'" class="diff-box__title">短款 ¥{{ (selected.amount - triad(selected).bank).toLocaleString('zh-CN') }}：渠道/银行到账少于收银金额（疑手续费误扣）</div>
            <div v-else class="diff-box__title">冲正交易：退款已发起，等待渠道/银行回单</div>
            <div class="diff-box__hint">差异需人工复核后调平；<strong>调平只记调平记录，不反向修改资金系统数据</strong>。</div>
          </div>
        </div>
        <div v-else-if="selected.status === 'PENDING'" class="diff-box diff-box--pending">
          <CIcon name="clock" :size="16" />
          <div>
            <div class="diff-box__title">等待银行到账回单（T+1）</div>
            <div class="diff-box__hint">银行回单到达后，「一键对账」将自动轧平。</div>
          </div>
        </div>
        <div v-else class="diff-box diff-box--ok">
          <CIcon name="check" :size="16" />
          <div>
            <div class="diff-box__title">三方金额一致，账实相符</div>
            <div class="diff-box__hint">Outbox 以 transaction_id 幂等，该笔已完成对账闭环。</div>
          </div>
        </div>

        <div class="det-ops">
          <CButton variant="secondary" size="sm" :disabled="!canReconcile" @click="runAuto">
            <CIcon name="check-square" :size="14" />重新对账
          </CButton>
          <CButton v-if="selected.status !== 'MATCHED'" variant="primary" size="sm" :disabled="!canApprove" @click="openAdjust">
            <CIcon name="shield" :size="14" />人工调平（双签）
          </CButton>
        </div>

        <!-- 8 大恒等式（财务勾稽） -->
        <div class="ident">
          <div class="ident__title"><CIcon name="finance" :size="14" />财务恒等式校验</div>
          <div class="ident__grid">
            <div v-for="iden in fin.identities" :key="iden.no" class="iden-row" :class="{ 'is-pass': iden.passed, 'is-fail': !iden.passed }">
              <span class="iden-row__no">{{ iden.no }}</span>
              <span class="iden-row__label">{{ iden.label }}</span>
              <CIcon :name="iden.passed ? 'check' : 'alert'" :size="13" class="iden-row__icon" />
            </div>
          </div>
        </div>
      </CCard>
    </div>

    <!-- 人工调平双签弹层 -->
    <div v-if="showAdjust" class="modal-mask" @click.self="showAdjust = false">
      <CCard class="modal" title="人工调平（双签复核）" padding="lg">
        <div class="sign-box">
          <div class="sign-box__title"><CIcon name="shield" :size="16" /> 红线提示</div>
          <div class="sign-box__text">调平仅记录复核结论与差异原因，<strong>不会反向修改支付/银行系统的任何金额</strong>。重大差异需财务双人复核。</div>
        </div>
        <label class="form__label">差异原因 / 调平说明</label>
        <CTextarea v-model="adjustForm.remark" :rows="3" placeholder="如：手续费误扣，计入财务费用；长款计入营业外收入" />
        <label class="form__label">复核人姓名（二次确认）</label>
        <CInput v-model="adjustForm.reviewer" placeholder="请输入复核人姓名，与操作人不同" />
        <template #footer>
          <CButton variant="ghost" @click="showAdjust = false">取消</CButton>
          <CButton variant="primary" :disabled="!canSubmitAdjust || !canApprove" @click="submitAdjust">确认调平</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.rc { display: flex; flex-direction: column; gap: var(--s-lg); }
.rc__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .rc__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }

.rc__flash { background: var(--c-success-soft, rgba(22,163,110,.1)); border: 1px solid var(--c-success-fg); }
.flash-line { display: flex; align-items: center; gap: var(--s-sm); color: var(--c-success-fg); font-size: var(--t-sm); font-weight: 600; }

.rc__body { display: grid; grid-template-columns: 380px 1fr; gap: var(--s-lg); align-items: start; }
.rc__list { min-width: 0; }
.list-head { display: flex; justify-content: space-between; align-items: center; gap: var(--s-sm); padding: var(--s-md) var(--s-lg); border-bottom: 1px solid var(--c-border-light); flex-wrap: wrap; }
.list-head__left { display: flex; align-items: center; gap: var(--s-sm); }
.list-head__right { display: flex; align-items: center; gap: var(--s-sm); }
.list-head__title { font-size: var(--t-sm); font-weight: 700; color: var(--c-text); }
.list-head__hint { font-size: var(--t-xs); color: var(--c-text-3); }
.ob-list { max-height: 640px; overflow-y: auto; }
.ob-row { display: block; width: 100%; text-align: left; padding: var(--s-md) var(--s-lg); background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer; border-left: 3px solid transparent; }
.ob-row:hover { background: var(--c-brand-soft); }
.ob-row--active { background: var(--c-brand-soft); border-left-color: var(--c-brand); }
.ob-row__top { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; }
.ob-row__no { font-size: var(--t-xs); color: var(--c-text-3); font-variant-numeric: tabular-nums; }
.ob-row__mid { display: flex; justify-content: space-between; align-items: baseline; }
.ob-row__biz { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.ob-row__amount { font-size: var(--t-md); font-weight: 700; color: var(--c-text); font-variant-numeric: tabular-nums; }
.ob-row__sub { font-size: var(--t-xs); color: var(--c-text-3); margin: 2px 0 6px; }
.ob-row--long { border-left-color: var(--c-info-fg, #2f80ed); }
.ob-row--short { border-left-color: var(--c-danger-fg); }
.ob-row--reversed { border-left-color: var(--c-primary); }
.ob-row--pending { border-left-color: var(--c-warning-fg); }
.triad-mini { display: inline-flex; align-items: center; gap: 4px; font-size: var(--t-xs); }
.triad-mini .t { padding: 1px 6px; border-radius: var(--r-sm); background: var(--c-disabled-bg); color: var(--c-text-3); }
.triad-mini .t.on { background: var(--c-success-soft, rgba(22,163,110,.12)); color: var(--c-success-fg); }
.triad-mini .arr { color: var(--c-text-4); }

.rc__detail :deep(.card__body) { display: flex; flex-direction: column; gap: var(--s-md); }
.det-head { display: flex; justify-content: space-between; align-items: flex-start; gap: var(--s-sm); }
.det-head__right { display: flex; align-items: center; gap: var(--s-sm); flex-shrink: 0; flex-wrap: wrap; justify-content: flex-end; }
.det-head__right :deep(.cbtn) { white-space: nowrap; }
.det-head__no { margin: 0; font-size: var(--t-lg); font-weight: 700; }
.det-head__sub { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }

.triad { display: flex; align-items: stretch; gap: var(--s-sm); }
.triad__col { flex: 1; min-width: 0; background: var(--c-bg-right); border-radius: var(--r-md); padding: var(--s-md); text-align: center; display: flex; flex-direction: column; gap: 6px; align-items: center; justify-content: center; }
.triad__col--miss { background: rgba(245,158,11,.08); }
.triad__label { display: inline-flex; align-items: center; gap: 4px; font-size: var(--t-xs); color: var(--c-text-3); }
.triad__amount { font-size: var(--t-lg); font-weight: 700; color: var(--c-text); font-variant-numeric: tabular-nums; }
.triad__ack { font-size: var(--t-xs); display: inline-flex; align-items: center; gap: 2px; }
.triad__ack.is-ok { color: var(--c-success-fg); }
.triad__ack.is-miss { color: var(--c-warning-fg); }
.triad__arrow { display: flex; align-items: center; color: var(--c-text-4); }

.diff-box { display: flex; gap: var(--s-sm); padding: var(--s-md); border-radius: var(--r-md); font-size: var(--t-xs); line-height: 1.6; }
.diff-box__title { font-weight: 700; margin-bottom: 2px; }
.diff-box__hint { color: var(--c-text-3); }
.diff-box--ok { background: var(--c-success-soft, rgba(22,163,110,.1)); color: var(--c-success-fg); }
.diff-box--pending { background: rgba(245,158,11,.1); color: var(--c-warning-fg); }
.diff-box--long { background: rgba(47,128,237,.1); color: var(--c-info-fg, #2f80ed); }
.diff-box--short { background: rgba(229,57,53,.1); color: var(--c-danger-fg); }
.diff-box--reversed { background: rgba(94,114,228,.1); color: var(--c-primary); }

.det-ops { display: flex; gap: var(--s-sm); }

.ident { background: var(--c-bg-right); border-radius: var(--r-md); padding: var(--s-md); }
.ident__title { display: flex; align-items: center; gap: 6px; font-size: var(--t-xs); font-weight: 700; color: var(--c-text-2); margin-bottom: var(--s-sm); }
.ident__grid { display: grid; grid-template-columns: 1fr 1fr; gap: 4px var(--s-md); }
.iden-row { display: flex; align-items: flex-start; gap: 6px; font-size: var(--t-xs); color: var(--c-text-3); padding: 3px 0; min-width: 0; }
.iden-row__no { width: 16px; height: 16px; border-radius: 50%; background: var(--c-disabled-bg); color: var(--c-text-3); font-size: 10px; font-weight: 700; display: flex; align-items: center; justify-content: center; flex-shrink: 0; margin-top: 1px; }
.iden-row__label { flex: 1; min-width: 0; line-height: 1.4; word-break: break-word; }
.iden-row__icon { flex-shrink: 0; margin-top: 2px; }
.iden-row.is-pass { color: var(--c-success-fg); }
.iden-row.is-pass .iden-row__no { background: var(--c-success-fg); color: #fff; }
.iden-row.is-fail { color: var(--c-danger-fg); }
.iden-row.is-fail .iden-row__no { background: var(--c-danger-fg); color: #fff; }

.modal-mask { position: fixed; inset: 0; background: rgba(20,21,43,.45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 480px; max-width: 100%; box-shadow: var(--shadow-pop); }
.sign-box { background: rgba(229,57,53,.08); border: 1px solid rgba(229,57,53,.3); border-radius: var(--r-md); padding: var(--s-md); margin-bottom: var(--s-md); }
.sign-box__title { display: flex; align-items: center; gap: 6px; font-size: var(--t-sm); font-weight: 700; color: var(--c-danger-fg); margin-bottom: 4px; }
.sign-box__text { font-size: var(--t-xs); color: var(--c-text-2); line-height: 1.6; }
.form__label { display: block; font-size: var(--t-xs); color: var(--c-text-3); margin: var(--s-sm) 0 var(--s-xs); }

@media (max-width: 1200px) {
  .rc__body { grid-template-columns: 1fr; }
}
@media (max-width: 1024px) {
  .rc__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .rc__body { grid-template-columns: 1fr; }
  .triad { flex-direction: column; }
  .triad__arrow { transform: rotate(90deg); justify-content: center; padding: 2px 0; }
  .ident__grid { grid-template-columns: 1fr; }
  .ob-list { max-height: 360px; }
}
</style>
