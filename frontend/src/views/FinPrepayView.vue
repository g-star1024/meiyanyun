<script setup lang="ts">
/* ============================================================
 * M6-10 预收款监管 /m6-prepay（红线增强）
 * 会员预收款池「只读镜像」监控：总额/已耗/沉淀/可退；
 * 不碰资金池，仅监控 + 临期/超额/异常预警 + 合规报告
 * 4 KPI + 环形消耗进度 + 趋势 + 预警清单 + 恒等式
 * ============================================================ */
import { computed, ref } from 'vue'
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
const total = computed(() => fin.depositRecharge) // 累计充值
const consumed = computed(() => fin.depositConsume) // 已划扣消耗
const balance = computed(() => fin.depositBalance) // 池余额
const consumeRate = computed(() => total.value ? Math.round((consumed.value / total.value) * 100) : 0)
// 沉淀：余额中超过 180 天无消耗的部分（演示口径）
const sediment = computed(() => Math.round(balance.value * 0.18))
const refundable = computed(() => balance.value - sediment.value)

const kpis = computed(() => [
  { label: '预收款池总额', icon: 'pos' as const, value: `¥${total.value.toLocaleString('zh-CN')}`, sub: '累计充值（镜像）' },
  { label: '已消耗（确认收入）', icon: 'check-square' as const, value: `¥${consumed.value.toLocaleString('zh-CN')}`, sub: `消耗率 ${consumeRate.value}%` },
  { label: '池余额（待履约）', icon: 'finance' as const, value: `¥${balance.value.toLocaleString('zh-CN')}`, sub: '负债类，不得挪用' },
  { label: '沉淀资金', icon: 'finance' as const, value: `¥${sediment.value.toLocaleString('zh-CN')}`, sub: '>180天无消耗' },
])

const donutData = computed(() => [
  { label: '已消耗', value: consumed.value, color: 'var(--c-success-fg)' },
  { label: '可退余额', value: refundable.value, color: 'var(--c-brand)' },
  { label: '沉淀资金', value: sediment.value, color: 'var(--c-warning-fg)' },
])

// 近 6 月预收款趋势（纯 CSS 柱，镜像聚合）
const trend = [
  { m: '3月', recharge: 62000, consume: 48000 },
  { m: '4月', recharge: 58000, consume: 52000 },
  { m: '5月', recharge: 71000, consume: 55000 },
  { m: '6月', recharge: 66000, consume: 61000 },
  { m: '7月', recharge: 79000, consume: 64000 },
  { m: '8月', recharge: total.value, consume: consumed.value },
]
const trendMax = computed(() => Math.max(...trend.flatMap((t) => [t.recharge, t.consume])))

// 监管预警
type AlertLevel = 'HIGH' | 'MEDIUM' | 'LOW'
interface PrepayAlert { id: string; level: AlertLevel; type: string; desc: string; amount?: number; at: string }
const ALERT_PILL: Record<AlertLevel, 'danger' | 'warning' | 'info'> = { HIGH: 'danger', MEDIUM: 'warning', LOW: 'info' }
const ALERT_LABEL: Record<AlertLevel, string> = { HIGH: '高风险', MEDIUM: '预警', LOW: '提示' }
const alerts = ref<PrepayAlert[]>([
  { id: 'PA-1', level: 'HIGH', type: '超额充值', desc: '单笔储值 ¥50,000 超监管阈值，需复核', amount: 50000, at: '2026-08-16 16:18' },
  { id: 'PA-2', level: 'HIGH', type: '大额退款', desc: '原路退款 ¥2,800 等待银行回单（冲正）', amount: 2800, at: '2026-08-16 17:50' },
  { id: 'PA-3', level: 'MEDIUM', type: '临期消耗', desc: '林微疗程卡剩余 2 次即将到期（30天内）', at: '2026-08-15 09:00' },
  { id: 'PA-4', level: 'MEDIUM', type: '沉睡沉淀', desc: '12 位客户余额 >¥3,000 且 180 天无消耗', amount: 48600, at: '2026-08-14 08:00' },
  { id: 'PA-5', level: 'LOW', type: '对账差异', desc: 'T-1 预收账款与卡余额勾稽差 ¥0（已平）', amount: 0, at: '2026-08-17 07:00' },
])

// 合规校验项
const checks = computed(() => [
  { label: '预收款专户管理，不与营业资金混同', passed: true },
  { label: '消耗 = 已双签划扣，确认收入有据', passed: true },
  { label: '退款原路返回，不做现金坐支', passed: true },
  { label: '池余额与卡余额相互印证（恒等式 8）', passed: fin.identities[7]?.passed },
  { label: '大额充值/退款已触发双人复核', passed: alerts.value.filter((a) => a.level === 'HIGH').length === 0 ? true : false },
])
const passedCount = computed(() => checks.value.filter((c) => c.passed).length)
const complianceRate = computed(() => Math.round((passedCount.value / checks.value.length) * 100))

function exportReport() {
  if (!canExport.value) return
  const lines = [
    '预收款监管报告（只读镜像，不碰资金池）',
    `生成时间,${new Date().toLocaleString('zh-CN')}`,
    `池总额,${total.value}`, `已消耗,${consumed.value}`, `池余额,${balance.value}`, `沉淀资金,${sediment.value}`,
    '', '预警清单:', ...alerts.value.map((a) => `${a.level},${a.type},${a.desc},${a.amount ?? ''}`),
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
                <div class="pool__rate-label">累计消耗率 <strong>{{ consumeRate }}%</strong></div>
                <CProgressBar :value="consumed" :max="total" :height="10" color="var(--c-success-fg)" />
              </div>
            </div>
          </div>
        </CCard>

        <!-- 趋势 -->
        <CCard class="block" title="近 6 月预收 / 消耗趋势（镜像）" padding="lg">
          <div class="trend">
            <div v-for="t in trend" :key="t.m" class="trend__col">
              <div class="trend__bars">
                <div class="trend__bar trend__bar--r" :style="{ height: (t.recharge / trendMax * 100) + '%' }" :title="`充值 ¥${t.recharge}`" />
                <div class="trend__bar trend__bar--c" :style="{ height: (t.consume / trendMax * 100) + '%' }" :title="`消耗 ¥${t.consume}`" />
              </div>
              <div class="trend__m">{{ t.m }}</div>
            </div>
          </div>
          <div class="trend-legend">
            <span><i class="dot dot--r" />充值</span>
            <span><i class="dot dot--c" />消耗（确认收入）</span>
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
              <li v-for="(c, i) in checks" :key="i" :class="{ fail: !c.passed }">
                <CIcon :name="c.passed ? 'check' : 'alert'" :size="14" />
                <span>{{ c.label }}</span>
              </li>
            </ul>
          </div>
        </CCard>

        <CCard class="block" title="监管预警" padding="none">
          <div class="alerts">
            <div v-for="a in alerts" :key="a.id" class="alert-row" :class="`alert-row--${a.level.toLowerCase()}`">
              <div class="alert-row__top">
                <span class="alert-row__type">{{ a.type }}</span>
                <CStatusPill :status="ALERT_PILL[a.level]" dot>{{ ALERT_LABEL[a.level] }}</CStatusPill>
              </div>
              <div class="alert-row__desc">{{ a.desc }}</div>
              <div class="alert-row__meta">
                <span v-if="a.amount" class="alert-row__amt">¥{{ a.amount.toLocaleString('zh-CN') }}</span>
                <span class="alert-row__time">{{ a.at }}</span>
              </div>
            </div>
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

.trend { display: flex; align-items: flex-end; gap: var(--s-md); height: 180px; padding: 0 var(--s-sm); }
.trend__col { flex: 1; display: flex; flex-direction: column; align-items: center; height: 100%; }
.trend__bars { flex: 1; display: flex; align-items: flex-end; gap: 4px; width: 100%; justify-content: center; }
.trend__bar { width: 16px; max-width: 40%; border-radius: var(--r-sm) var(--r-sm) 0 0; min-height: 3px; }
.trend__bar--r { background: var(--c-brand); opacity: .55; }
.trend__bar--c { background: var(--c-success-fg); }
.trend__m { font-size: var(--t-xs); color: var(--c-text-3); margin-top: var(--s-xs); }
.trend-legend { display: flex; gap: var(--s-lg); justify-content: center; margin-top: var(--s-md); font-size: var(--t-xs); color: var(--c-text-3); }
.trend-legend .dot { display: inline-block; width: 10px; height: 10px; border-radius: 2px; margin-right: 5px; vertical-align: middle; }
.dot--r { background: var(--c-brand); opacity: .55; }
.dot--c { background: var(--c-success-fg); }

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
