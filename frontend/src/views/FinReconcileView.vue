<script setup lang="ts">
/* ============================================================
 * M6-03 对账中心 /m6-reconcile（红线核心）
 * T+1 三方对账：收银 / 支付渠道 / 银行，Outbox 幂等 transaction_id
 * 长款/短款/冲正差异；一键轧平；人工调平需双签复核（不反向动账）
 * 4 KPI + 三方视图 + 差异清单 + 8 大恒等式面板
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CInput from '@/components/CInput.vue'
import CTextarea from '@/components/CTextarea.vue'
import CSelect from '@/components/CSelect.vue'
import { useFinanceCoreStore, type OutboxItem } from '@/stores/financeCore'
import { useAuthStore } from '@/stores/auth'
import { useStoreContext } from '@/stores/storeContext'
import { getTripartite, type TripartiteResult } from '@/api/finance'

const fin = useFinanceCoreStore()
const auth = useAuthStore()
const storeCtx = useStoreContext()
const canReconcile = computed(() => auth.can('finance:reconcile'))
const canApprove = computed(() => auth.can('finance:reconcile:approve'))
const canExport = computed(() => auth.can('finance:export'))

const selectedId = ref<string | null>(null)
const selected = computed<OutboxItem | null>(() => {
  if (selectedId.value) return fin.outbox.find((o) => o.outboxId === selectedId.value) ?? null
  return fin.outbox.find((o) => o.status !== 'MATCHED' && o.status !== 'ADJUSTED') ?? fin.outbox[0] ?? null
})

const totalMatched = computed(() => fin.outboxMatched)
const totalRecords = computed(() => fin.outbox.length)
const matchRate = computed(() => totalRecords.value ? Math.round((totalMatched.value / totalRecords.value) * 100) : 0)
const diffCount = computed(() => fin.outbox.filter((o) => o.status === 'LONG' || o.status === 'SHORT').length + fin.outboxDiffCount)
// 真实台账（finance-service outbox，writable=true，支持人工标记一致/差异/调平写，后端全审计）；
// demoMode = 后端整体不可用降级的演示 seed（writable=false，仅本地演示三方回单轧平，不发起后端写）
const liveMode = computed(() => fin.outboxLiveCount > 0)
const demoMode = computed(() => totalRecords.value > 0 && !liveMode.value)
const busy = ref(false)

const kpis = computed(() => [
  { label: '对账总笔数', icon: 'finance', value: `${totalRecords.value} 笔`, tone: 'brand' as const, sub: liveMode.value ? `真实对账台账 ${fin.outboxLiveCount} 笔` : '演示数据（离线降级）' },
  { label: '已轧平', icon: 'finance', value: `${totalMatched.value} 笔`, tone: 'success' as const, sub: liveMode.value ? '含已调平留痕' : `匹配率 ${matchRate.value}%` },
  { label: '长短款 / 差异', icon: 'alert', value: liveMode.value ? `${fin.outboxDiffCount} 笔差异` : `¥${fin.outboxLong.toLocaleString('zh-CN')} / ¥${fin.outboxShort.toLocaleString('zh-CN')}`, tone: diffCount.value ? ('danger' as const) : ('text' as const), sub: liveMode.value ? '人工标记差异待调平' : `${diffCount.value} 笔演示长短款` },
  { label: '待对账 / 冲正', icon: 'refund', value: `${fin.outboxPending} 笔`, tone: fin.outboxPending ? ('warning' as const) : ('text' as const), sub: liveMode.value ? '待人工标记一致/差异' : 'T+1 三方核对（演示）' },
])

const BIZ_LABEL: Record<string, string> = {
  ORDER_PAY: '收银支付', REFUND: '退款', RECHARGE: '充值', WRITEOFF: '划扣', SETTLE: '结算',
}
const STATUS_PILL: Record<OutboxItem['status'], 'success' | 'warning' | 'danger' | 'info' | 'primary'> = {
  MATCHED: 'success', PENDING: 'warning', LONG: 'info', SHORT: 'danger', REVERSED: 'primary', DIFF: 'danger', ADJUSTED: 'primary',
}
const STATUS_LABEL: Record<OutboxItem['status'], string> = {
  MATCHED: '已平', PENDING: '待对账', LONG: '长款', SHORT: '短款', REVERSED: '冲正', DIFF: '差异', ADJUSTED: '已调平',
}

// 三方金额：演示 seed 为三方回单演示态（长款银行多、短款渠道/银行少）；
// 真实台账（writable）渠道/银行回单 B6 才接入，不伪造金额——回单列返回 null，页面诚实展示「回单未接入」
function triad(o: OutboxItem): { cashier: number; channel: number | null; bank: number | null } {
  const cashier = o.amount
  if (o.writable) return { cashier, channel: null, bank: null }
  let channel: number | null = o.amount
  let bank: number | null = o.amount
  if (o.status === 'LONG') { bank = o.amount + 100 }
  if (o.status === 'SHORT') { channel = o.amount - 6; bank = o.amount - 6 }
  if (o.status === 'PENDING') { bank = 0 }
  if (o.status === 'REVERSED') { bank = 0; channel = 0 }
  return { cashier, channel, bank }
}
function triadText(v: number | null) {
  return v == null ? '回单未接入' : `¥${v.toLocaleString('zh-CN')}`
}
// 演示长短款差额（真实台账不伪造回单金额，该值仅 LONG/SHORT 演示项可达）
function demoDiffAmount(o: OutboxItem): number | null {
  if (o.writable) return null
  const t = triad(o)
  if (o.status === 'LONG' && t.bank != null) return t.bank - o.amount
  if (o.status === 'SHORT' && t.bank != null) return o.amount - t.bank
  return null
}

const flash = ref('')
function flashMsg(msg: string, ms = 3500) {
  flash.value = msg
  setTimeout(() => (flash.value = ''), ms)
}

// 一键对账：真实台账三方回单未接入（B6），真实 PENDING 笔走人工标记；本按钮仅演示态本地轧平
async function runAuto() {
  if (busy.value) return
  busy.value = true
  try {
    const n = fin.runReconcile()
    flashMsg(`一键对账完成：演示轧平 ${n} 笔`)
  } catch (e) {
    flashMsg(e instanceof Error ? e.message : '对账失败')
  } finally {
    busy.value = false
  }
}

// 人工标记弹层（PENDING → 标记一致 RECONCILED / 标记差异 DIFF；二级确认，全审计）
const showMark = ref(false)
const markForm = ref({ remark: '' })
function openMark() {
  if (!selected.value || !selected.value.writable || selected.value.status !== 'PENDING') return
  markForm.value = { remark: '' }
  showMark.value = true
}
async function submitMark(kind: 'ok' | 'diff') {
  if (!selected.value || busy.value) return
  busy.value = true
  try {
    const remark = markForm.value.remark.trim() || undefined
    if (kind === 'ok') {
      await fin.markReconciled(selected.value.outboxId, remark)
      flashMsg('已人工标记「三方一致」：状态置为已对账（全审计留痕）')
    } else {
      await fin.markDiff(selected.value.outboxId, remark)
      flashMsg('已人工标记「存在差异」：请在差异处置中调平（全审计留痕）')
    }
    showMark.value = false
  } catch (e) {
    flashMsg(e instanceof Error ? e.message : '操作失败')
  } finally {
    busy.value = false
  }
}

// 人工调平双签弹层
const showAdjust = ref(false)
const SUBJECT_OPTIONS = [
  { label: '主营业务收入 RF-REVENUE', value: 'RF-REVENUE' },
  { label: '退款（收入抵减）RF-REFUND', value: 'RF-REFUND' },
  { label: '预收账款 RF-DEPOSIT', value: 'RF-DEPOSIT' },
]
const CHANNEL_OPTIONS = [
  { label: '现金', value: 'cash' },
  { label: '刷卡', value: 'card' },
  { label: '微信支付', value: 'wxpay' },
  { label: '支付宝', value: 'alipay' },
  { label: '储值余额', value: 'balance' },
  { label: '银行转账', value: 'transfer' },
]
const adjustForm = ref({ direction: 'IN', amount: '', subject: 'RF-REVENUE', channel: 'cash', reviewer: '', remark: '' })
const canSubmitAdjust = computed(() => {
  const amt = parseFloat(adjustForm.value.amount)
  return adjustForm.value.reviewer.trim().length > 1
    && adjustForm.value.remark.trim().length > 1
    && Number.isFinite(amt) && amt > 0
})
function openAdjust() {
  if (!selected.value) return
  const live = selected.value.writable
  adjustForm.value = {
    direction: 'IN',
    amount: live ? '' : String(selected.value.amount),
    subject: 'RF-REVENUE',
    channel: 'cash',
    reviewer: '',
    remark: '',
  }
  showAdjust.value = true
}
async function submitAdjust() {
  if (!selected.value || !canSubmitAdjust.value || busy.value) return
  busy.value = true
  try {
    const { direction, amount, subject, channel, reviewer, remark } = adjustForm.value
    if (selected.value.writable) {
      // 真实台账：调 finance-service 补 ADJUST 分录（DIFF → ADJUSTED），金额元转分，全审计
      await fin.adjustOutbox(
        selected.value.outboxId,
        { direction: direction as 'IN' | 'OUT', amountYuan: parseFloat(amount), subject, channel, memo: remark.trim() },
        reviewer.trim(),
      )
    } else {
      // 演示/离线降级：仅本地置已平，不发起后端写
      await fin.adjustOutbox(selected.value.outboxId, `${remark.trim()}（复核：${reviewer.trim()}）`)
    }
    showAdjust.value = false
    flashMsg('差异已人工调平（补调平分录并留痕，未反向动业务账）')
  } catch (e) {
    flashMsg(e instanceof Error ? e.message : '调平失败')
  } finally {
    busy.value = false
  }
}

// ============================================================
// 三方对账标签页（B7 §9.1）：经营域（txn 四流）× 资金域（fund_entry 落账）× 现金日结（双签工单）
// 外部渠道回单（微信/支付宝/银行）本期无自动导入，账实仅覆盖现金；现金方不可用诚实降级
// ============================================================
const activeTab = ref<'outbox' | 'tripartite'>('outbox')

function todayShanghai(): string {
  // 与后端一致：Asia/Shanghai 自然日，避免 toISOString 的 UTC 偏移
  const now = new Date()
  const utc = now.getTime() + now.getTimezoneOffset() * 60000
  return new Date(utc + 8 * 3600000).toISOString().slice(0, 10)
}
function yuan(v: number | null | undefined): string {
  if (v == null) return '—'
  return `¥${v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

const triDate = ref(todayShanghai())
const triStore = ref('') // 空 = 全部可见门店
const triLoading = ref(false)
const triError = ref('')
const tri = ref<TripartiteResult | null>(null)

const triStoreOptions = computed(() => [
  { label: '全部门店（有权限）', value: '' },
  ...storeCtx.stores.map((s) => ({ label: s.storeName || s.storeCode, value: s.storeCode })),
])

async function loadTripartite() {
  if (triLoading.value) return
  if (!storeCtx.loaded) {
    try { await storeCtx.loadStores() } catch { /* 门店名回落编码，不阻塞 */ }
  }
  triLoading.value = true
  triError.value = ''
  try {
    const params: { date?: string; storeCode?: string } = {}
    if (triDate.value) params.date = triDate.value
    if (triStore.value) params.storeCode = triStore.value
    const { data } = await getTripartite(params)
    tri.value = data
  } catch (e) {
    tri.value = null
    triError.value = e instanceof Error ? e.message : '三方对账查询失败'
  } finally {
    triLoading.value = false
  }
}

onMounted(() => { loadTripartite() })

function exportReport() {
  if (!canExport.value) return
  const head = 'Outbox号,业务类型,交易号,金额,渠道,收银,渠道回单,银行到账,状态\n'
  const rows = fin.outbox.map((o) => {
    const t = triad(o)
    const c = t.channel == null ? '回单未接入' : t.channel
    const b = t.bank == null ? '回单未接入' : t.bank
    return [o.outboxId, BIZ_LABEL[o.bizType], o.txnNo, o.amount, o.channel, t.cashier, c, b, STATUS_LABEL[o.status]].join(',')
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

    <CCard v-if="liveMode" class="rc__mirror-note" padding="md">
      <div class="mirror-line">
        <CIcon name="finance" :size="16" />
        <span>
          当前为 <strong>finance-service 真实对账台账</strong>（共 {{ totalRecords }} 笔，业务事件随收退款/划扣实时落账）。
          支付渠道 / 银行 <strong>三方回单 B6 接入</strong>，在此之前长短款不自动判定；待对账笔可由财务
          <strong>人工「标记一致 / 标记差异」</strong>，差异笔经<strong>双签复核后调平</strong>（补调平分录，全审计留痕，不反向动业务账）。
        </span>
      </div>
    </CCard>
    <CCard v-else-if="demoMode" class="rc__mirror-note rc__mirror-note--demo" padding="md">
      <div class="mirror-line">
        <CIcon name="alert" :size="16" />
        <span>
          finance-service 暂不可达，当前为 <strong>演示数据（离线降级）</strong>：长短款/冲正与一键轧平均为本地演示，
          <strong>不会发起任何后端写操作</strong>；服务恢复后自动切换为真实对账台账。
        </span>
      </div>
    </CCard>

    <CCard v-if="flash" class="rc__flash" padding="md">
      <div class="flash-line"><CIcon name="check" :size="16" />{{ flash }}</div>
    </CCard>

    <!-- 标签切换：Outbox 逐笔台账 / 三方按日对账（B7 §9.1） -->
    <div class="rc__tabs">
      <button class="rc__tab" :class="{ 'is-active': activeTab === 'outbox' }" @click="activeTab = 'outbox'">
        <CIcon name="finance" :size="15" />Outbox 对账台账
      </button>
      <button class="rc__tab" :class="{ 'is-active': activeTab === 'tripartite' }" @click="activeTab = 'tripartite'">
        <CIcon name="shield" :size="15" />三方对账（按日）
      </button>
    </div>

    <div v-if="activeTab === 'outbox'" class="rc__body">
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
            <div v-if="o.writable" class="triad-mini">
              <span class="t live">收银已记账 · 三方回单未接入</span>
            </div>
            <div v-else class="triad-mini">
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
            <CButton v-if="!selected.writable" variant="primary" size="sm" :disabled="!canReconcile || busy" @click="runAuto">
              <CIcon name="check-square" :size="14" />一键对账（演示）
            </CButton>
          </div>
        </div>

        <!-- 三方金额对比：收银已落账；渠道/银行回单 B6 接入，真实台账回单列为空时诚实展示「回单未接入」 -->
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
              <div class="triad__amount">{{ triadText(triad(selected)[col.key as 'cashier'|'channel'|'bank']) }}</div>
              <div class="triad__ack" :class="col.ack ? 'is-ok' : 'is-miss'">
                <CIcon :name="col.ack ? 'check' : 'clock'" :size="12" />{{ col.ack ? '已确认' : '回单未接入' }}
              </div>
            </div>
            <div v-if="i < 2" class="triad__arrow">
              <CIcon name="chevron-right" :size="18" />
            </div>
          </template>
        </div>

        <!-- 差异说明：真实台账回单未接入，长短款不自动判定；PENDING 走人工标记、DIFF 走双签调平、ADJUSTED 留痕 -->
        <div v-if="selected.status === 'PENDING' && selected.writable" class="diff-box diff-box--pending">
          <CIcon name="clock" :size="16" />
          <div>
            <div class="diff-box__title">收银已记账，等待人工对账确认</div>
            <div class="diff-box__hint">支付渠道 / 银行回单 B6 自动接入；在回单到达前，可由财务人工<strong>「标记一致」或「标记差异」</strong>（全审计留痕）。</div>
          </div>
        </div>
        <div v-else-if="selected.status === 'DIFF'" class="diff-box diff-box--diff">
          <CIcon name="alert" :size="16" />
          <div>
            <div class="diff-box__title">人工标记「存在差异」，待双签复核调平</div>
            <div class="diff-box__hint">差异金额以回单核对为准；调平仅<strong>补调平分录并留痕，不反向动业务账</strong>，复核人双人确认。</div>
          </div>
        </div>
        <div v-else-if="selected.status === 'ADJUSTED'" class="diff-box diff-box--adjusted">
          <CIcon name="shield" :size="16" />
          <div>
            <div class="diff-box__title">该笔差异已人工调平（补调平分录，已留痕）</div>
            <div class="diff-box__hint">调平记录含差异原因与复核人，可在审计日志中追溯；Outbox 状态置为已调平。</div>
          </div>
        </div>
        <div v-else-if="selected.status === 'LONG' || selected.status === 'SHORT' || selected.status === 'REVERSED'" class="diff-box" :class="`diff-box--${selected.status.toLowerCase()}`">
          <CIcon name="alert" :size="16" />
          <div>
            <div v-if="selected.status === 'LONG'" class="diff-box__title">长款 ¥{{ (demoDiffAmount(selected) ?? 0).toLocaleString('zh-CN') }}：银行实际到账多于收银记录（演示）</div>
            <div v-else-if="selected.status === 'SHORT'" class="diff-box__title">短款 ¥{{ (demoDiffAmount(selected) ?? 0).toLocaleString('zh-CN') }}：渠道/银行到账少于收银金额（演示，疑手续费误扣）</div>
            <div v-else class="diff-box__title">冲正交易：退款已发起，等待渠道/银行回单（演示）</div>
            <div class="diff-box__hint">差异需人工复核后调平；<strong>调平只记调平记录，不反向修改资金系统数据</strong>。</div>
          </div>
        </div>
        <div v-else-if="selected.status === 'PENDING'" class="diff-box diff-box--pending">
          <CIcon name="clock" :size="16" />
          <div>
            <div class="diff-box__title">等待银行到账回单（T+1，演示）</div>
            <div class="diff-box__hint">银行回单到达后，「一键对账」将自动轧平。</div>
          </div>
        </div>
        <div v-else class="diff-box diff-box--ok">
          <CIcon name="check" :size="16" />
          <div>
            <div class="diff-box__title">{{ selected.writable ? '人工标记三方一致，账实相符' : '三方金额一致，账实相符' }}</div>
            <div class="diff-box__hint">Outbox 以 transaction_id 幂等，该笔已完成对账闭环。</div>
          </div>
        </div>

        <div class="det-ops">
          <CButton v-if="!selected.writable" variant="secondary" size="sm" :disabled="!canReconcile || busy" @click="runAuto">
            <CIcon name="check-square" :size="14" />重新对账（演示轧平）
          </CButton>
          <CButton v-if="selected.writable && selected.status === 'PENDING'" variant="primary" size="sm" :disabled="!canReconcile || busy" @click="openMark">
            <CIcon name="check-square" :size="14" />标记一致 / 差异
          </CButton>
          <CButton v-if="selected.writable && selected.status === 'DIFF'" variant="primary" size="sm" :disabled="!canApprove || busy" @click="openAdjust">
            <CIcon name="shield" :size="14" />人工调平（双签）
          </CButton>
          <CButton v-if="!selected.writable && selected.status !== 'MATCHED'" variant="primary" size="sm" :disabled="!canApprove || busy" @click="openAdjust">
            <CIcon name="shield" :size="14" />人工调平（双签·演示）
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

    <!-- 三方对账（按日）：经营域 × 资金域 × 现金日结；外部渠道回单未接入，账实仅覆盖现金 -->
    <div v-if="activeTab === 'tripartite'" class="tri">
      <CCard class="tri__filters" padding="md">
        <div class="tri-filters">
          <div class="tri-field">
            <span class="tri-field__label">对账日期</span>
            <input v-model="triDate" type="date" class="date-input" />
          </div>
          <div class="tri-field">
            <span class="tri-field__label">门店范围</span>
            <CSelect v-model="triStore" width="220px" :options="triStoreOptions" />
          </div>
          <CButton variant="primary" size="sm" :disabled="triLoading" @click="loadTripartite">
            <CIcon name="finance" :size="14" />{{ triLoading ? '对账中…' : '查询对账' }}
          </CButton>
        </div>
      </CCard>

      <CCard v-if="triError" class="tri__err" padding="md">
        <div class="flash-line" style="color: var(--c-danger-fg);">
          <CIcon name="alert" :size="16" />{{ triError }}
        </div>
      </CCard>

      <template v-if="tri">
        <!-- 结论横幅 -->
        <CCard class="tri__verdict" :class="tri.matched ? 'tri__verdict--ok' : 'tri__verdict--diff'" padding="md">
          <div class="verdict-line">
            <CIcon :name="tri.matched ? 'check' : 'alert'" :size="18" />
            <div>
              <div class="verdict-line__title">
                {{ tri.matched ? '三方核对通过' : `${tri.diffStoreCount} 家门店存在差异` }}
                <CStatusPill v-if="!tri.cashAvailable" status="warning" dot>现金日结不可用·仅账账</CStatusPill>
              </div>
              <div class="verdict-line__msg">{{ tri.message }}</div>
            </div>
          </div>
        </CCard>

        <!-- 三方净额总览 -->
        <div class="tri__kpis">
          <CKpi label="经营域净额（txn 四流）" :value="yuan(tri.bizNetYuan)" tone="brand" icon="finance"
            :sub="`订单 ${tri.flowCounts.orders ?? 0} · 退款 ${tri.flowCounts.refunds ?? 0} · 划扣对 ${tri.flowCounts.writeoffPairs ?? 0} · 退卡 ${tri.flowCounts.cardCancels ?? 0}`" />
          <CKpi label="资金域净额（fund_entry 落账）" :value="yuan(tri.postedNetYuan)" tone="brand" icon="finance"
            :sub="`落账分录 ${tri.flowCounts.postedEntries ?? 0} 笔 · 人工调平 ${tri.adjustCount} 笔单列`" />
          <CKpi label="账账差异（已扣期末成本）" :value="yuan(tri.unexplainedDiffFen / 100)" :tone="tri.unexplainedDiffFen === 0 ? 'success' : 'danger'" icon="alert"
            :sub="`账账原差 ${yuan(tri.netDiffYuan)} − 期末成本 ${yuan(tri.postedManualCostFen / 100)}（财务域独有单列）`" />
          <CKpi label="现金日结实点（双签工单）" :value="yuan(tri.cashHandoverYuan)" :tone="!tri.cashAvailable ? 'text' : (tri.cashDiffFen === 0 ? 'success' : 'danger')" icon="pos"
            :sub="tri.cashAvailable
              ? `工单 ${tri.cashTicketCount ?? 0} 张 · 现金账实差异 ${tri.cashDiffFen === 0 ? '0（相符）' : yuan((tri.cashDiffFen ?? 0) / 100)}`
              : 'txn 现金日结暂不可达，已降级仅出账账结果'" />
        </div>

        <!-- 渠道覆盖诚实提示 -->
        <CCard class="tri__note" padding="md">
          <div class="mirror-line">
            <CIcon name="clock" :size="16" />
            <span>
              账实核对本期<strong>仅覆盖现金渠道</strong>（CASHIER 现金净额 vs 现金交接双签工单实点现金）；
              微信 / 支付宝 / 银行回单流水本期无自动导入，<strong>不臆造金额</strong>，待渠道回单接入后补齐。
              账账两侧按 UTC 日界聚合，现金方按 Asia/Shanghai 自然日（交接班口径）。
              差异处置请回到「Outbox 对账台账」标记差异并双签调平（补 ADJUST 分录，不反向动业务账）。
            </span>
          </div>
        </CCard>

        <!-- 门店明细表 -->
        <CCard class="tri__table" padding="none">
          <div class="list-head">
            <div class="list-head__left">
              <span class="list-head__title">门店对账明细（{{ tri.date }}）</span>
              <span class="list-head__hint">{{ tri.stores.length }} 家门店 · 金额单位元</span>
            </div>
          </div>
          <div class="tri-table-wrap">
            <table class="tri-table">
              <thead>
                <tr>
                  <th>门店</th>
                  <th class="num">经营域净额</th>
                  <th class="num">资金域净额</th>
                  <th class="num">账账差异<small>（扣成本后）</small></th>
                  <th class="num">现金实点<small>（双签）</small></th>
                  <th class="num">现金差异</th>
                  <th class="num">笔数<small>（订/退/划/退卡/落账）</small></th>
                  <th>结论</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="s in tri.stores" :key="s.storeCode" :class="{ 'row--diff': !s.matched }">
                  <td>
                    <div class="tri-store">{{ s.storeName }}</div>
                    <div class="tri-store__code">{{ s.storeCode }}</div>
                  </td>
                  <td class="num">{{ yuan(s.bizNetYuan) }}</td>
                  <td class="num">{{ yuan(s.postedNetYuan) }}</td>
                  <td class="num" :class="{ 'cell--diff': s.unexplainedDiffFen !== 0 }">
                    {{ s.unexplainedDiffFen === 0 ? '0.00' : yuan(s.unexplainedDiffFen / 100) }}
                    <div v-if="s.manualCostFen !== 0" class="cell__sub">含期末成本 {{ yuan(s.manualCostFen / 100) }} 单列</div>
                  </td>
                  <td class="num">
                    <template v-if="tri.cashAvailable">{{ yuan(s.cashHandoverFen != null ? s.cashHandoverFen / 100 : null) }}<div v-if="s.cashTicketCount" class="cell__sub">{{ s.cashTicketCount }} 张工单</div></template>
                    <span v-else class="cell--na">不可用</span>
                  </td>
                  <td class="num" :class="{ 'cell--diff': s.cashDiffFen != null && s.cashDiffFen !== 0 }">
                    <template v-if="tri.cashAvailable && s.cashDiffFen != null">{{ s.cashDiffFen === 0 ? '0.00' : yuan(s.cashDiffFen / 100) }}</template>
                    <span v-else class="cell--na">—</span>
                  </td>
                  <td class="num cell__sub">{{ s.orderCount }}/{{ s.refundCount }}/{{ s.writeoffPairCount }}/{{ s.cardCancelCount }}/{{ s.postedEntryCount }}</td>
                  <td>
                    <CStatusPill v-if="s.matched" status="success" dot>相符</CStatusPill>
                    <CStatusPill v-else status="danger" dot>差异</CStatusPill>
                  </td>
                </tr>
                <tr v-if="tri.stores.length === 0">
                  <td colspan="8" class="tri-empty">当日无可见门店的经营/资金数据</td>
                </tr>
              </tbody>
            </table>
          </div>
        </CCard>
      </template>
    </div>
    <div v-if="showAdjust && selected" class="modal-mask" @click.self="showAdjust = false">
      <CCard class="modal" :class="{ 'modal--wide': selected.writable }" title="人工调平（双签复核）" padding="lg">
        <div class="sign-box">
          <div class="sign-box__title"><CIcon name="shield" :size="16" /> 红线提示</div>
          <div class="sign-box__text">
            调平仅在财务账补一笔调平分录，<strong>不会反向修改支付/银行系统的任何金额</strong>；
            {{ selected.writable ? '分录金额（分）、科目、渠道随调平请求落账并全审计。' : '当前为演示数据，调平仅本地置平，不发起后端写。' }}
          </div>
        </div>
        <div v-if="selected.writable" class="form-grid">
          <div>
            <label class="form__label">调平方向</label>
            <CSelect v-model="adjustForm.direction" width="100%" :options="[{ label: '入账 IN（补收钱方向）', value: 'IN' }, { label: '出账 OUT（补退款/扣款方向）', value: 'OUT' }]" />
          </div>
          <div>
            <label class="form__label">调平金额（元）</label>
            <CInput v-model="adjustForm.amount" type="number" placeholder="按回单核对的差异金额，单位元" />
          </div>
          <div>
            <label class="form__label">对方科目</label>
            <CSelect v-model="adjustForm.subject" width="100%" :options="SUBJECT_OPTIONS" />
          </div>
          <div>
            <label class="form__label">资金渠道</label>
            <CSelect v-model="adjustForm.channel" width="100%" :options="CHANNEL_OPTIONS" />
          </div>
        </div>
        <label class="form__label">差异原因 / 调平说明</label>
        <CTextarea v-model="adjustForm.remark" :rows="3" placeholder="如：手续费误扣，计入财务费用；长款计入营业外收入" />
        <label class="form__label">复核人姓名（二次确认）</label>
        <CInput v-model="adjustForm.reviewer" placeholder="请输入复核人姓名，与操作人不同" />
        <template #footer>
          <CButton variant="ghost" :disabled="busy" @click="showAdjust = false">取消</CButton>
          <CButton variant="primary" :disabled="!canSubmitAdjust || !canApprove || busy" @click="submitAdjust">确认调平</CButton>
        </template>
      </CCard>
    </div>

    <!-- 人工标记弹层（真实台账 PENDING：标记一致 RECONCILED / 标记差异 DIFF，二级确认） -->
    <div v-if="showMark && selected" class="modal-mask" @click.self="showMark = false">
      <CCard class="modal" title="人工对账标记（二次确认）" padding="lg">
        <div class="sign-box">
          <div class="sign-box__title" style="color: var(--c-primary, #5e72e4);"><CIcon name="check-square" :size="16" /> 标记留痕</div>
          <div class="sign-box__text">
            三方回单自动对账 B6 接入；本次人工结论将<strong>直接写入对账台账并全审计留痕</strong>：
            「标记一致」置为已对账；「标记差异」转入差异处置，待双签调平。
          </div>
        </div>
        <label class="form__label">对账依据 / 备注（可选）</label>
        <CTextarea v-model="markForm.remark" :rows="3" placeholder="如：已与微信商户后台账单逐笔核对一致；银行流水见 9/5 对账单" />
        <template #footer>
          <CButton variant="ghost" :disabled="busy" @click="showMark = false">取消</CButton>
          <CButton variant="secondary" :disabled="!canReconcile || busy" @click="submitMark('diff')">标记差异</CButton>
          <CButton variant="primary" :disabled="!canReconcile || busy" @click="submitMark('ok')">标记一致</CButton>
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

.rc__mirror-note { background: var(--c-brand-soft, rgba(94,114,228,.08)); border: 1px solid var(--c-primary, #5e72e4); }
.mirror-line { display: flex; align-items: flex-start; gap: var(--s-sm); color: var(--c-text-2); font-size: var(--t-xs); line-height: 1.7; }
.mirror-line .ci { flex-shrink: 0; margin-top: 2px; color: var(--c-primary, #5e72e4); }
.triad-mirror { display: flex; flex-direction: column; gap: 6px; padding: var(--s-md) var(--s-lg); background: rgba(245,158,11,.08); border-radius: var(--r-md); font-size: var(--t-xs); color: var(--c-warning-fg, #f59e0b); }
.triad-mirror__title { font-size: var(--t-md); font-weight: 700; color: var(--c-text); font-variant-numeric: tabular-nums; }
.triad-mirror__hint { color: var(--c-text-3); line-height: 1.7; }
.triad-mini .t.mirror { background: var(--c-brand-soft, rgba(94,114,228,.12)); color: var(--c-primary, #5e72e4); }

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

/* B3：真实台账 / 离线降级两态 + DIFF/ADJUSTED 状态 + 双签弹层栅格（仅追加） */
.rc__mirror-note--demo { background: rgba(245,158,11,.08); border-color: var(--c-warning-fg, #f59e0b); }
.rc__mirror-note--demo .mirror-line { color: var(--c-warning-fg, #f59e0b); }
.triad-mini .t.live { background: var(--c-brand-soft, rgba(94,114,228,.12)); color: var(--c-primary, #5e72e4); }
.ob-row--matched { border-left-color: transparent; }
.ob-row--diff { border-left-color: var(--c-danger-fg); }
.ob-row--adjusted { border-left-color: var(--c-primary); }
.diff-box--diff { background: rgba(229,57,53,.1); color: var(--c-danger-fg); }
.diff-box--adjusted { background: var(--c-brand-soft, rgba(94,114,228,.1)); color: var(--c-primary, #5e72e4); }
.modal--wide { width: 600px; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 var(--s-md); }
@media (max-width: 640px) { .form-grid { grid-template-columns: 1fr; } }

/* B7：标签切换 + 三方对账面板（仅追加） */
.rc__tabs { display: flex; gap: var(--s-xs); border-bottom: 1px solid var(--c-border-light); }
.rc__tab { display: inline-flex; align-items: center; gap: 6px; padding: var(--s-sm) var(--s-md); background: none; border: none; border-bottom: 2px solid transparent; margin-bottom: -1px; cursor: pointer; font-size: var(--t-sm); font-weight: 600; color: var(--c-text-3); }
.rc__tab:hover { color: var(--c-text); }
.rc__tab.is-active { color: var(--c-primary, #5e72e4); border-bottom-color: var(--c-primary, #5e72e4); }

.tri { display: flex; flex-direction: column; gap: var(--s-md); }
.tri-filters { display: flex; align-items: flex-end; gap: var(--s-md); flex-wrap: wrap; }
.tri-field { display: flex; flex-direction: column; gap: 4px; }
.tri-field__label { font-size: var(--t-xs); color: var(--c-text-3); }
.date-input { height: 32px; padding: 0 var(--s-sm); border: 1px solid var(--c-border, #d2d6de); border-radius: var(--r-sm, 6px); font-size: var(--t-sm); color: var(--c-text); background: var(--c-bg, #fff); }
.tri__err { background: rgba(229,57,53,.08); border: 1px solid var(--c-danger-fg); }

.tri__verdict { border-radius: var(--r-md); }
.tri__verdict--ok { background: var(--c-success-soft, rgba(22,163,110,.1)); border: 1px solid var(--c-success-fg); }
.tri__verdict--diff { background: rgba(229,57,53,.08); border: 1px solid var(--c-danger-fg); }
.verdict-line { display: flex; align-items: flex-start; gap: var(--s-sm); }
.tri__verdict--ok .verdict-line { color: var(--c-success-fg); }
.tri__verdict--diff .verdict-line { color: var(--c-danger-fg); }
.verdict-line__title { display: flex; align-items: center; gap: var(--s-sm); font-size: var(--t-md); font-weight: 700; }
.verdict-line__msg { margin-top: 4px; font-size: var(--t-xs); line-height: 1.7; color: var(--c-text-2); }

.tri__kpis { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .tri__kpis { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }

.tri__note { background: rgba(245,158,11,.06); border: 1px solid rgba(245,158,11,.35); }
.tri__note .mirror-line { color: var(--c-text-2); }

.tri-table-wrap { overflow-x: auto; }
.tri-table { width: 100%; border-collapse: collapse; font-size: var(--t-xs); }
.tri-table th { padding: var(--s-sm) var(--s-md); text-align: left; color: var(--c-text-3); font-weight: 600; white-space: nowrap; border-bottom: 1px solid var(--c-border-light); background: var(--c-bg-right); }
.tri-table th small { font-weight: 400; color: var(--c-text-4); }
.tri-table td { padding: var(--s-sm) var(--s-md); border-bottom: 1px solid var(--c-border-light); vertical-align: middle; }
.tri-table .num { text-align: right; font-variant-numeric: tabular-nums; white-space: nowrap; }
.tri-table tbody tr:hover { background: var(--c-brand-soft); }
.tri-table tr.row--diff { background: rgba(229,57,53,.04); }
.tri-table tr.row--diff:hover { background: rgba(229,57,53,.08); }
.tri-store { font-weight: 600; color: var(--c-text); }
.tri-store__code { font-size: 11px; color: var(--c-text-4); font-variant-numeric: tabular-nums; }
.cell--diff { color: var(--c-danger-fg); font-weight: 700; }
.cell__sub { font-size: 11px; color: var(--c-text-4); font-weight: 400; }
.cell--na { color: var(--c-text-4); }
.tri-empty { text-align: center; color: var(--c-text-3); padding: var(--s-lg) !important; }
</style>
