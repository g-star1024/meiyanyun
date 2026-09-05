<script setup lang="ts">
/* ============================================================
 * M6-10 预收款监管 /m6-prepay（红线增强）
 * 会员预收款池「只读镜像」监控：总额/已耗/沉淀/可退；
 * 不碰资金池，仅监控 + 临期/超额/异常预警 + 合规报告
 * 4 KPI + 环形消耗进度 + 趋势 + 预警清单 + 恒等式
 * ============================================================ */
import { computed } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CDonutChart from '@/components/CDonutChart.vue'
import CProgressBar from '@/components/CProgressBar.vue'
import { useFinanceCoreStore } from '@/stores/financeCore'
import { useAuthStore } from '@/stores/auth'

const fin = useFinanceCoreStore()
const auth = useAuthStore()
const canExport = computed(() => auth.can('finance:export'))

// 预收款池（只读镜像，数据来自 financeCore 的预收账款）
// 说明：充值流水台账后端暂无数据源（member_card 仅有余额终值），故「累计充值」本期发生额为 0；
// 池余额直接锚定会员卡储值合计（会计真值，恒等式 8），不做流水滚存推算，不伪造历史充值。
const total = computed(() => fin.depositRecharge) // 累计充值（本期发生额，无充值流水台账 → 0）
const consumed = computed(() => fin.depositConsume) // 已划扣消耗（台账 RF-DEPOSIT OUT 真实）
const balance = computed(() => fin.depositBalance) // 池余额（锚定会员卡储值合计真值）
const rechargeOnline = computed(() => total.value > 0)
const consumeRate = computed(() => (rechargeOnline.value && total.value ? Math.round((consumed.value / total.value) * 100) : null))
// 沉淀资金：后端据末次消费判定的休眠卡（DORMANT）储值余额合计——真实沉睡沉淀
const sediment = computed(() => fin.dormantAmount)
const dormantCount = computed(() => fin.dormantCount)
const refundable = computed(() => Math.max(balance.value - sediment.value, 0))
// 未对账大额退款（台账 RF-REFUND 中 reconciled=false，真实镜像；银行回单未接入，列为待核销）
const pendingRefunds = computed(() =>
  fin.entries.filter((e) => e.subject === 'RF-REFUND' && e.direction === 'OUT' && !e.reconciled))

const kpis = computed(() => [
  { label: '预收款池总额', icon: 'pos' as const, value: rechargeOnline.value ? `¥${total.value.toLocaleString('zh-CN')}` : '充值流水未接入', sub: rechargeOnline.value ? '累计充值（镜像）' : '池余额见卡储值合计' },
  { label: '已消耗（确认收入）', icon: 'check-square' as const, value: `¥${consumed.value.toLocaleString('zh-CN')}`, sub: consumeRate.value == null ? '已双签划扣转出' : `消耗率 ${consumeRate.value}%` },
  { label: '池余额（待履约）', icon: 'finance' as const, value: `¥${balance.value.toLocaleString('zh-CN')}`, sub: '会员卡储值合计 · 负债类，不得挪用' },
  { label: '沉淀资金', icon: 'finance' as const, value: `¥${sediment.value.toLocaleString('zh-CN')}`, sub: `休眠卡 ${dormantCount.value} 张 · 无近期消耗` },
])

const donutData = computed(() => [
  { label: '已消耗', value: consumed.value, color: 'var(--c-success-fg)' },
  { label: '可退余额', value: refundable.value, color: 'var(--c-brand)' },
  { label: '沉淀资金', value: sediment.value, color: 'var(--c-warning-fg)' },
])

// 监管预警：全部由真实台账 / 会员卡镜像派生，不编造客户、金额与日期；
// 无数据源的预警类型（超额充值/临期疗程）后端暂无投影，不伪造。
type AlertLevel = 'HIGH' | 'MEDIUM' | 'LOW'
interface PrepayAlert { id: string; level: AlertLevel; type: string; desc: string; amount?: number; at: string }
const ALERT_PILL: Record<AlertLevel, 'danger' | 'warning' | 'info'> = { HIGH: 'danger', MEDIUM: 'warning', LOW: 'info' }
const ALERT_LABEL: Record<AlertLevel, string> = { HIGH: '高风险', MEDIUM: '预警', LOW: '提示' }
const alerts = computed<PrepayAlert[]>(() => {
  const list: PrepayAlert[] = []
  pendingRefunds.value.forEach((r, i) => {
    list.push({
      id: `PR-${i + 1}`, level: 'HIGH', type: '大额退款待核销',
      desc: `退款 ${r.refNo} 已记账 ¥${r.amount.toLocaleString('zh-CN')}，银行/渠道回单未接入，待对账核销`,
      amount: r.amount, at: r.date,
    })
  })
  if (dormantCount.value > 0) {
    list.push({
      id: 'PD-1', level: 'MEDIUM', type: '沉睡沉淀',
      desc: `${dormantCount.value} 张休眠卡仍有储值余额（末次消费后无近期消耗）`,
      amount: sediment.value, at: '据卡状态实时镜像',
    })
  }
  if (fin.identities[7]?.passed) {
    list.push({
      id: 'PC-1', level: 'LOW', type: '勾稽一致',
      desc: `池余额 ¥${balance.value.toLocaleString('zh-CN')} 与会员卡储值合计相互印证（恒等式 8 已平）`,
      amount: 0, at: '实时校验',
    })
  }
  return list
})

// 合规校验项：仅反映能被真实数据验证的项；流程制度类（专户管理/退款原路/双人复核）
// 后端暂无合规事件源，标记为「待接入」不伪造通过。
const checks = computed(() => [
  { label: '池余额与卡余额相互印证（恒等式 8）', passed: fin.identities[7]?.passed === true, supported: true },
  { label: '消耗 = 已双签划扣，确认收入有据', passed: fin.identities[3]?.passed === true, supported: true },
  { label: '大额退款待核销（银行回单未接入）', passed: pendingRefunds.value.length === 0, supported: true },
  { label: '预收款专户管理，不与营业资金混同', passed: false, supported: false },
  { label: '大额充值/退款双人复核（合规事件源未接入）', passed: false, supported: false },
])
const supportedChecks = computed(() => checks.value.filter((c) => c.supported))
const passedCount = computed(() => supportedChecks.value.filter((c) => c.passed).length)
const complianceRate = computed(() =>
  supportedChecks.value.length ? Math.round((passedCount.value / supportedChecks.value.length) * 100) : 0)

function exportReport() {
  if (!canExport.value) return
  const lines = [
    '预收款监管报告（只读镜像，不碰资金池）',
    `生成时间,${new Date().toLocaleString('zh-CN')}`,
    `已消耗,${consumed.value}`, `池余额(卡储值合计),${balance.value}`, `沉淀资金(休眠卡),${sediment.value}`,
    `充值流水台账,未接入`,
    '', '预警清单:', ...alerts.value.map((a) => `${ALERT_LABEL[a.level]},${a.type},${a.desc},${a.amount ?? ''}`),
  ]
  const blob = new Blob(['\uFEFF' + lines.join('\n')], { type: 'text/csv;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `预收款监管报告-${new Date().toISOString().slice(0, 10)}.csv`
  a.click()
  URL.revokeObjectURL(a.href)
}
</script>

<template>
  <div class="pp">
    <div class="pp__head">
      <div v-for="k in kpis" :key="k.label" class="kpi-item">
        <div class="kpi-item__icon">
          <CIcon :name="k.icon" :size="24" />
        </div>
        <div class="kpi-item__body">
          <div class="kpi-item__label">{{ k.label }}</div>
          <div class="kpi-item__value">{{ k.value }}</div>
          <div class="kpi-item__sub">{{ k.sub }}</div>
        </div>
      </div>
    </div>

    <div class="pp__body">
      <div class="pp__main">
        <!-- 池子消耗进度 -->
        <CCard class="block" padding="lg">
          <template #header>
            <div class="card-head">
              <span class="card-head__title">预收款池消耗进度</span>
              <div class="card-head__right">
                <span class="pp__badge"><CIcon name="shield" :size="14" />只读镜像 · 资金池受监管</span>
                <CButton variant="secondary" size="sm" :disabled="!canExport" @click="exportReport">
                  <CIcon name="export" :size="14" />导出监管报告
                </CButton>
              </div>
            </div>
          </template>
          <div class="pool">
            <CDonutChart :data="donutData" :size="170" :thickness="22" center-label="池余额" :center-value="`¥${balance.toLocaleString('zh-CN')}`" />
            <div class="pool__legend">
              <div v-for="d in donutData" :key="d.label" class="legend-row">
                <span class="legend-row__dot" :style="{ background: d.color }" />
                <span class="legend-row__label">{{ d.label }}</span>
                <span class="legend-row__val">¥{{ d.value.toLocaleString('zh-CN') }}</span>
              </div>
              <div class="pool__rate">
                <template v-if="consumeRate != null">
                  <div class="pool__rate-label">累计消耗率 <strong>{{ consumeRate }}%</strong></div>
                  <CProgressBar :value="consumed" :max="total" :height="10" color="var(--c-success-fg)" />
                </template>
                <div v-else class="pool__rate-na">
                  <CIcon name="alert" :size="14" />累计消耗率待充值流水台账接入后计算（分母为累计充值，当前无数据源）
                </div>
              </div>
            </div>
          </div>
        </CCard>

        <!-- 本期预收镜像：仅展示台账可验证的真实发生额；历史月度充值趋势无数据源，不伪造 -->
        <CCard class="block" title="本期预收 / 消耗（镜像）" padding="lg">
          <div class="cur">
            <div class="cur__item">
              <span class="cur__label">本期划扣消耗（确认收入转出）</span>
              <span class="cur__val cur__val--c">¥{{ consumed.toLocaleString('zh-CN') }}</span>
            </div>
            <div class="cur__item">
              <span class="cur__label">本期充值流水</span>
              <span v-if="rechargeOnline" class="cur__val cur__val--r">¥{{ total.toLocaleString('zh-CN') }}</span>
              <span v-else class="cur__na">充值流水台账未接入</span>
            </div>
            <div class="cur__item">
              <span class="cur__label">池余额（会员卡储值合计真值）</span>
              <span class="cur__val">¥{{ balance.toLocaleString('zh-CN') }}</span>
            </div>
          </div>
          <div class="cur__note">
            <CIcon name="alert" :size="14" />
            充值流水台账后端暂无数据源（会员卡仅有余额终值），历史月度充值 / 消耗趋势暂不展示；池余额以会员卡储值合计为会计真值，不做流水滚存推算。
          </div>
        </CCard>
      </div>

      <!-- 右侧：合规 + 预警 -->
      <div class="pp__side">
        <CCard class="block" title="合规红线校验" padding="md">
          <div class="comp">
            <div class="comp__ring" :class="{ 'is-full': complianceRate === 100 }">
              <span class="comp__pct">{{ complianceRate }}%</span>
              <span class="comp__lbl">合规率</span>
            </div>
            <ul class="comp__list">
              <li v-for="(c, i) in checks" :key="i" :class="{ fail: c.supported && !c.passed }">
                <CIcon :name="!c.supported ? 'shield' : c.passed ? 'check' : 'alert'" :size="14" />
                <span>{{ c.label }}<em v-if="!c.supported" class="comp__na">（数据源待接入）</em></span>
              </li>
            </ul>
          </div>
        </CCard>

        <CCard class="block" title="监管预警（真实镜像派生）" padding="none">
          <div class="alerts">
            <div v-for="a in alerts" :key="a.id" class="alert-row" :class="`alert-row--${a.level.toLowerCase()}`">
              <div class="alert-row__top">
                <span class="alert-row__type">{{ a.type }}</span>
                <CStatusPill :status="ALERT_PILL[a.level]" dot>{{ ALERT_LABEL[a.level] }}</CStatusPill>
              </div>
              <div class="alert-row__desc">{{ a.desc }}</div>
              <div class="alert-row__meta">
                <span v-if="a.amount != null && a.amount > 0" class="alert-row__amt">¥{{ a.amount.toLocaleString('zh-CN') }}</span>
                <span class="alert-row__time">{{ a.at }}</span>
              </div>
            </div>
            <div v-if="alerts.length === 0" class="alerts__empty">暂无真实预警事件；超额充值 / 临期疗程等预警类型后端数据源待接入，不生成演示告警。</div>
          </div>
        </CCard>
      </div>
    </div>
  </div>
</template>

<style scoped>
.pp { display: flex; flex-direction: column; gap: var(--s-lg); }
.pp__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .pp__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
.kpi-item {
  display: flex; align-items: center; gap: var(--s-md);
  background: var(--c-surface); border: 1px solid var(--c-border-light);
  border-radius: var(--r-xl); padding: var(--s-md);
}
.kpi-item__icon {
  width: 40px; height: 40px; border-radius: var(--r-lg);
  background: var(--c-brand-soft); color: var(--c-brand);
  display: flex; align-items: center; justify-content: center; flex-shrink: 0;
}
.kpi-item__body { min-width: 0; flex: 1; }
.kpi-item__label { font-size: var(--t-xs); color: var(--c-text-3); line-height: var(--lh-xs); }
.kpi-item__value { font-size: var(--t-lg); font-weight: 700; line-height: 1.3; font-variant-numeric: tabular-nums; }
.kpi-item__sub { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }
.pp__badge { display: inline-flex; align-items: center; gap: 4px; font-size: var(--t-xs); color: var(--c-success-fg); background: var(--c-success-soft, rgba(22,163,110,.1)); padding: 4px 10px; border-radius: var(--r-sm); font-weight: 600; }
.card-head { display: flex; align-items: center; justify-content: space-between; gap: var(--s-sm); width: 100%; flex-wrap: wrap; }
.card-head__title { font-size: var(--t-md); font-weight: 700; color: var(--c-text); }
.card-head__right { display: flex; align-items: center; gap: var(--s-sm); margin-left: auto; flex-shrink: 0; }

.pp__body { display: grid; grid-template-columns: 1fr 360px; gap: var(--s-lg); align-items: start; }
.pp__main { display: flex; flex-direction: column; gap: var(--s-lg); min-width: 0; }
.pp__side { display: flex; flex-direction: column; gap: var(--s-lg); }
.block { min-width: 0; }

.pool { display: flex; gap: var(--s-lg); align-items: center; flex-wrap: wrap; }
.pool__legend { flex: 1; min-width: 220px; display: flex; flex-direction: column; gap: var(--s-sm); }
.legend-row { display: flex; align-items: center; gap: var(--s-sm); font-size: var(--t-sm); }
.legend-row__dot { width: 10px; height: 10px; border-radius: 50%; flex-shrink: 0; }
.legend-row__label { flex: 1; color: var(--c-text-2); }
.legend-row__val { font-weight: 700; font-variant-numeric: tabular-nums; }
.pool__rate { margin-top: var(--s-sm); }
.pool__rate-label { font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: 6px; }
.pool__rate-label strong { color: var(--c-success-fg); }
.pool__rate-na { display: flex; align-items: center; gap: 6px; font-size: var(--t-xs); color: var(--c-text-3); line-height: 1.6; }

.cur { display: flex; flex-direction: column; gap: var(--s-sm); }
.cur__item { display: flex; align-items: center; justify-content: space-between; gap: var(--s-md); padding: var(--s-sm) var(--s-md); background: var(--c-bg); border-radius: var(--r-md); }
.cur__label { font-size: var(--t-sm); color: var(--c-text-2); }
.cur__val { font-size: var(--t-md); font-weight: 700; font-variant-numeric: tabular-nums; color: var(--c-text); }
.cur__val--c { color: var(--c-success-fg); }
.cur__val--r { color: var(--c-brand); }
.cur__na { font-size: var(--t-sm); color: var(--c-text-3); }
.cur__note { display: flex; align-items: flex-start; gap: 6px; margin-top: var(--s-md); padding: var(--s-sm) var(--s-md); background: var(--c-warning-soft, rgba(240,173,78,.1)); border-radius: var(--r-md); font-size: var(--t-xs); color: var(--c-text-2); line-height: 1.6; }
.cur__note svg { color: var(--c-warning-fg); flex-shrink: 0; margin-top: 2px; }
.comp__na { font-style: normal; color: var(--c-text-3); font-size: 11px; }
.alerts__empty { padding: var(--s-lg); text-align: center; font-size: var(--t-xs); color: var(--c-text-3); line-height: 1.6; }

.comp { display: flex; gap: var(--s-md); align-items: center; }
.comp__ring { width: 84px; height: 84px; border-radius: 50%; flex-shrink: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; background: conic-gradient(var(--c-success-fg) 0%, var(--c-success-fg) 80%, var(--c-border-light) 80%); position: relative; }
.comp__ring::before { content: ''; position: absolute; inset: 8px; background: var(--c-bg); border-radius: 50%; }
.comp__ring.is-full { background: conic-gradient(var(--c-success-fg) 100%, var(--c-border-light) 0); }
.comp__pct { position: relative; font-size: var(--t-lg); font-weight: 700; color: var(--c-success-fg); }
.comp__lbl { position: relative; font-size: 10px; color: var(--c-text-3); }
.comp__list { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 6px; flex: 1; }
.comp__list li { display: flex; align-items: flex-start; gap: 6px; font-size: var(--t-xs); color: var(--c-text-2); line-height: 1.4; }
.comp__list svg { color: var(--c-success-fg); flex-shrink: 0; margin-top: 1px; }
.comp__list li.fail { color: var(--c-danger-fg); }
.comp__list li.fail svg { color: var(--c-danger-fg); }

.alerts { max-height: 420px; overflow-y: auto; }
.alert-row { padding: var(--s-md) var(--s-lg); border-bottom: 1px solid var(--c-border-light); border-left: 3px solid transparent; }
.alert-row--high { border-left-color: var(--c-danger-fg); background: rgba(229,57,53,.04); }
.alert-row--medium { border-left-color: var(--c-warning-fg); }
.alert-row--low { border-left-color: var(--c-info-fg, #2f80ed); }
.alert-row__top { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; }
.alert-row__type { font-size: var(--t-sm); font-weight: 700; color: var(--c-text); }
.alert-row__desc { font-size: var(--t-xs); color: var(--c-text-2); line-height: 1.5; }
.alert-row__meta { display: flex; justify-content: space-between; margin-top: 6px; font-size: var(--t-xs); color: var(--c-text-3); }
.alert-row__amt { font-weight: 700; color: var(--c-danger-fg); font-variant-numeric: tabular-nums; }

@media (max-width: 1024px) {
  .pp__body { grid-template-columns: 1fr; }
  .trend__bar { width: 10px; }
}
</style>
