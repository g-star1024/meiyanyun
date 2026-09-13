<script setup lang="ts">
/* ============================================================
 * A1-05 AI 经营日报 /ai/daily-report
 * 真实收款/到店/新客/退款/风控指标（txn 内部投影，Asia/Shanghai 自然日）
 * + daily invoke 全治理链（B47 卡4 去 mock）
 * ============================================================ */
import { computed, onMounted, ref, watch } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CKpi from '@/components/CKpi.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CTable from '@/components/CTable.vue'
import CIcon from '@/components/CIcon.vue'
import { useToast } from '@/composables/useToast'
import { errMsg } from '@/stores/m5Coupon'
import {
  getDailyMetrics, generateDaily, getDailyReport, listDailyHistory,
  getDailyStats, listDailyChannels, getDailySubscription, toggleDailySubscription,
  adoptDailySuggestion,
  type DailyMetrics, type DailyReport, type DailyHistoryItem,
  type DailyStats, type DailyChannel, type DailySuggestion,
} from '@/api/ai'

const toast = useToast()

function todayStr(): string {
  const d = new Date()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${m}-${day}`
}

function fmtNum(n: number | null | undefined): string {
  return (n ?? 0).toLocaleString('zh-CN')
}

/** 分 → 万元（>=1 万显示万元，否则显示元）。 */
function fmtRevenue(fen: number): string {
  const yuan = fen / 100
  if (yuan >= 10000) {
    return `¥${(yuan / 10000).toLocaleString('zh-CN', { maximumFractionDigits: 1 })}万`
  }
  return `¥${yuan.toLocaleString('zh-CN', { maximumFractionDigits: 0 })}`
}

function trendText(pct: number | null): string {
  if (pct === null || pct === undefined) return '环比不可算'
  return `${pct >= 0 ? '+' : ''}${pct}%`
}

const reportDate = ref(todayStr())
const subscribed = ref(false)

const metrics = ref<DailyMetrics | null>(null)
const report = ref<DailyReport | null>(null)
const stats = ref<DailyStats | null>(null)
const channels = ref<DailyChannel[]>([])
const history = ref<DailyHistoryItem[]>([])

const loadingMetrics = ref(false)
const generating = ref(false)
const loadingReport = ref(false)
const actionId = ref<number | null>(null)

// ---------- 4 KPI（真实当日聚合 + 上一自然日环比） ----------
const kpis = computed(() => {
  const m = metrics.value
  const unavailable = !m || !m.available
  return [
    {
      label: '今日营收', icon: 'finance', value: unavailable ? '—' : fmtRevenue(m!.revenueFen),
      tone: 'brand' as const,
      trend: unavailable ? '交易数据暂不可用' : trendText(m!.revenueDeltaPct),
      trendUp: (m?.revenueDeltaPct ?? 0) >= 0, trendGood: (m?.revenueDeltaPct ?? 0) >= 0,
    },
    {
      label: '到店', icon: 'user-check', value: unavailable ? '—' : fmtNum(m!.arrivalCount),
      tone: 'teal' as const,
      trend: unavailable ? '交易数据暂不可用' : trendText(m!.arrivalDeltaPct),
      trendUp: (m?.arrivalDeltaPct ?? 0) >= 0, trendGood: (m?.arrivalDeltaPct ?? 0) >= 0,
    },
    {
      label: '新客', icon: 'customer', value: unavailable ? '—' : fmtNum(m!.newCustomerCount),
      tone: 'purple' as const,
      trend: unavailable ? '交易数据暂不可用' : trendText(m!.newCustomerDeltaPct),
      trendUp: (m?.newCustomerDeltaPct ?? 0) >= 0, trendGood: (m?.newCustomerDeltaPct ?? 0) >= 0,
    },
    {
      label: '异常项', icon: 'alert', value: unavailable ? '—' : fmtNum(m!.anomalyCount),
      tone: 'warning' as const,
      trend: unavailable
        ? '交易数据暂不可用'
        : `退款 ${fmtNum(m!.refundCount)} · 红黄单 ${fmtNum(m!.contraYellowCount + m!.contraRedCount)}`,
      trendUp: true, trendGood: false,
    },
  ]
})

const suggestions = computed<DailySuggestion[]>(() => report.value?.suggestions ?? [])
const hasReport = computed(() => !!report.value)

const sourceNote = computed(() => {
  const m = metrics.value
  const scope = !m ? '全部门店' : m.store === 'ALL' ? '全部门店' : `门店 ${m.store}`
  return `数据来源：已收款订单 · 到店登记 · 退款流水 · 收款风控（${scope}，Asia/Shanghai 自然日）`
})

const modelPill = computed(() => {
  if (generating.value) return 'AI 生成中…'
  if (report.value?.modelCode) return `由 ${report.value.modelCode} 生成`
  return stats.value?.modelVersion ? `模型 ${stats.value.modelVersion}` : 'AI 日报'
})

const costText = computed(() => {
  const r = report.value
  if (!r || !r.totalTokens) return ''
  const yuan = (r.costFen ?? 0) / 100
  return `本次调用 ${fmtNum(r.totalTokens)} tokens · 成本 ¥${yuan.toFixed(2)}`
})

// ---------- 推送通道（四通道本期一律置灰，真实触达 M5-03 远期） ----------
const pushColumns = [
  { key: 'channelName', label: '推送渠道' },
  { key: 'status', label: '推送状态' },
  { key: 'note', label: '说明' },
]
const pushRows = computed(() =>
  channels.value.map((c, i) => ({ id: c.channel + i, ...c })),
)

function statusPill(s: string) {
  const map: Record<string, { status: 'success' | 'warning' | 'disabled' | 'info'; label: string }> = {
    success: { status: 'success', label: '已送达' },
    warning: { status: 'warning', label: '发送中' },
    disabled: { status: 'disabled', label: '未发送' },
    info: { status: 'info', label: '排队中' },
  }
  return map[s] || { status: 'disabled' as const, label: s }
}

// ---------- 历史日报（真实生成记录，按日归并最新版） ----------
const historyColumns = [
  { key: 'date', label: '日期' },
  { key: 'summary', label: '核心指标摘要' },
  { key: 'generatedAt', label: '生成时间' },
  { key: 'actions', label: '操作', align: 'right' as const, width: 140 },
]
const historyRows = computed(() =>
  history.value.map((h, i) => ({
    id: h.date + '-' + i,
    ...h,
    summary: `营收 ${fmtRevenue(h.revenueFen)} · 到店 ${fmtNum(h.arrivalCount)} · 新客 ${fmtNum(h.newCustomerCount)} · 异常 ${fmtNum(h.anomalyCount)}`,
  })),
)

async function loadMetrics() {
  loadingMetrics.value = true
  try {
    metrics.value = await getDailyMetrics({ date: reportDate.value })
  } catch (e) {
    metrics.value = null
    toast.error('经营指标加载失败：' + errMsg(e))
  } finally {
    loadingMetrics.value = false
  }
}

async function loadReport() {
  loadingReport.value = true
  try {
    report.value = await getDailyReport({ date: reportDate.value })
  } catch (e) {
    if (String(errMsg(e)).includes('尚未生成')) {
      report.value = null
    } else {
      toast.error('日报加载失败：' + errMsg(e))
    }
  } finally {
    loadingReport.value = false
  }
}

async function loadRefs() {
  try {
    const [s, c, sub, h] = await Promise.all([
      getDailyStats(),
      listDailyChannels(),
      getDailySubscription(),
      listDailyHistory(),
    ])
    stats.value = s
    channels.value = c
    subscribed.value = sub.subscribed
    history.value = h
  } catch (e) {
    toast.error('日报辅助信息加载失败：' + errMsg(e))
  }
}

async function generateReport() {
  if (generating.value) return
  generating.value = true
  try {
    const res = await generateDaily({ date: reportDate.value })
    report.value = res.report
    metrics.value = res.metrics
    const n = res.report.suggestions.length
    toast.success(`${reportDate.value} 经营日报已生成，摘要与 ${n} 条建议来自真实模型出站`)
    loadRefs()
  } catch (e) {
    toast.error('生成日报失败：' + errMsg(e))
  } finally {
    generating.value = false
  }
}

async function adoptSuggestion(s: DailySuggestion) {
  if (s.adopted) {
    toast.info('该建议已采纳，无需重复操作')
    return
  }
  actionId.value = s.suggestionId
  try {
    const res = await adoptDailySuggestion(s.suggestionId)
    if (res.changed) {
      s.adopted = true
      toast.success('已采纳并站内登记（真实任务下发待 M3-10/M5-03 建设）')
      if (stats.value) stats.value.adoptedCount += 1
    } else {
      s.adopted = true
      toast.info('该建议已采纳，无需重复操作')
    }
  } catch (e) {
    toast.error('采纳建议失败：' + errMsg(e))
  } finally {
    actionId.value = null
  }
}

async function toggleSubscribe() {
  const want = !subscribed.value
  try {
    const res = await toggleDailySubscription(want)
    subscribed.value = res.subscribed
    toast.success(res.subscribed ? '已开启每日日报订阅偏好（真实推送通道建设中）' : '已关闭日报订阅偏好')
  } catch (e) {
    toast.error('订阅设置失败：' + errMsg(e))
  }
}

function viewHistory(row: Record<string, any>) {
  reportDate.value = String(row.date)
}

watch(reportDate, () => {
  loadMetrics()
  loadReport()
})

onMounted(() => {
  loadMetrics()
  loadReport()
  loadRefs()
})
</script>

<template>
  <div class="a1-daily">
    <div class="a1-daily__kpis">
      <CKpi
        v-for="k in kpis"
        :key="k.label"
        :label="k.label"
        :value="k.value"
        :tone="k.tone"
        :trend="k.trend"
        :trend-up="k.trendUp"
        :trend-good="k.trendGood"
        :icon="k.icon" />
    </div>

    <!-- 顶栏 -->
    <CCard padding="md">
      <div class="toolbar">
        <div class="toolbar__left">
          <label class="field-label">日报日期</label>
          <input v-model="reportDate" type="date" class="date-input" />
          <CButton variant="primary" :disabled="generating" @click="generateReport">
            <CIcon name="dashboard" :size="14" />
            {{ generating ? '生成中…' : '生成日报' }}
          </CButton>
        </div>
        <div class="toolbar__right">
          <span class="field-label">每日自动推送</span>
          <button class="toggle" :class="{ 'is-on': subscribed }" @click="toggleSubscribe">
            <span class="toggle__dot" />
          </button>
        </div>
      </div>
    </CCard>

    <!-- AI 摘要 -->
    <CCard padding="lg">
      <template #header>
        <div class="card-head">
          <div class="card-head__left">
            <CIcon name="dashboard" :size="18" class="card-head__icon" />
            <h3>AI 经营摘要</h3>
            <CStatusPill status="draft" dot>{{ modelPill }}</CStatusPill>
          </div>
          <span class="card-sub">{{ sourceNote }}</span>
        </div>
      </template>

      <div v-if="generating" class="daily-state">
        <CIcon name="loading" :size="28" />
        <p>正在调用大模型基于当日真实收款/到店/退款指标生成经营日报，通常需数十秒，请勿离开本页…</p>
      </div>
      <div v-else-if="loadingReport" class="daily-state">
        <CIcon name="loading" :size="28" />
        <p>正在加载 {{ reportDate }} 的经营日报…</p>
      </div>
      <div v-else-if="!hasReport" class="daily-state">
        <CIcon name="dashboard" :size="28" />
        <p>{{ reportDate }} 尚未生成 AI 经营日报，请选择日期后点击「生成日报」；上方 KPI 为当日真实经营指标。</p>
      </div>
      <template v-else>
        <div class="report-summary">
          <p>{{ report!.summary }}</p>
          <div v-if="costText" class="report-meta">{{ costText }} · 生成时间 {{ report!.generatedAt }}</div>
        </div>

        <div class="suggestion-list">
          <div
            v-for="s in suggestions"
            :key="s.suggestionId"
            class="suggestion-item"
            :class="`suggestion-item--${s.type}`"
          >
            <div class="suggestion-item__body">
              <div class="suggestion-item__title">
                <span class="suggestion-tag" :class="`suggestion-tag--${s.type}`">
                  {{ s.type === 'core' ? '核心' : s.type === 'anomaly' ? '异常' : '建议' }}
                </span>
                {{ s.title }}
              </div>
              <div class="suggestion-item__detail">{{ s.detail }}</div>
            </div>
            <CButton
              size="sm"
              :variant="s.adopted ? 'ghost' : 'secondary'"
              :disabled="s.adopted || actionId === s.suggestionId"
              @click="adoptSuggestion(s)"
            >
              <CIcon :name="s.adopted ? 'check' : 'plus'" :size="14" />
              {{ s.adopted ? '已采纳' : '采纳为任务' }}
            </CButton>
          </div>
        </div>
      </template>
    </CCard>

    <!-- 推送状态 -->
    <CCard padding="none">
      <template #header>
        <div class="card-head">
          <h3>推送状态</h3>
          <span class="card-sub">本期仅登记订阅偏好，站内/企微/短信/邮件真实触达为 M5-03 远期规划</span>
        </div>
      </template>
      <CTable :columns="pushColumns" :rows="pushRows" row-key="id">
        <template #col-status="{ value }">
          <CStatusPill :status="statusPill(value).status" dot>
            {{ statusPill(value).label }}
          </CStatusPill>
        </template>
      </CTable>
    </CCard>

    <!-- 历史日报 -->
    <CCard padding="none">
      <template #header>
        <div class="card-head">
          <h3>历史日报</h3>
          <span class="card-sub">最近 {{ historyRows.length }} 期真实生成记录</span>
        </div>
      </template>
      <CTable :columns="historyColumns" :rows="historyRows" row-key="id">
        <template #col-actions="{ row }">
          <div class="row-actions">
            <CButton size="sm" variant="text" @click="viewHistory(row)">查看</CButton>
            <CButton size="sm" variant="text" disabled>对比</CButton>
          </div>
        </template>
      </CTable>
    </CCard>
  </div>
</template>

<style scoped>
.a1-daily { display: flex; flex-direction: column; gap: var(--s-lg); }
.a1-daily__kpis { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .a1-daily__kpis { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }

.toolbar { display: flex; align-items: center; justify-content: space-between; gap: var(--s-md); }
.toolbar__left { display: flex; align-items: center; gap: var(--s-sm); }
.toolbar__right { display: flex; align-items: center; gap: var(--s-sm); }
.field-label { font-size: var(--t-sm); color: var(--c-text-2); white-space: nowrap; }
.date-input {
  height: 36px; padding: 0 var(--s-sm);
  border: 1px solid var(--c-border); border-radius: var(--r-sm);
  background: var(--c-surface); font-size: var(--t-sm); color: var(--c-text);
  outline: none;
}
.date-input:focus { border-color: var(--c-brand); }

.toggle {
  width: 40px; height: 22px; border-radius: 11px;
  border: none; padding: 2px;
  background: var(--c-border); cursor: pointer;
  transition: background 0.2s;
}
.toggle.is-on { background: var(--c-brand); }
.toggle__dot {
  display: block; width: 18px; height: 18px;
  background: var(--c-surface); border-radius: 50%;
  transition: transform 0.2s;
}
.toggle.is-on .toggle__dot { transform: translateX(18px); }

.card-head { display: flex; align-items: center; justify-content: space-between; width: 100%; }
.card-head__left { display: flex; align-items: center; gap: var(--s-sm); }
.card-head__left h3 { margin: 0; font-size: var(--t-md); font-weight: 700; }
.card-head__icon { color: var(--c-purple); }
.card-sub { font-size: var(--t-xs); color: var(--c-text-3); }

.daily-state {
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: var(--s-sm); padding: var(--s-lg) 0;
  color: var(--c-text-3); font-size: var(--t-sm); text-align: center;
}
.daily-state p { margin: 0; max-width: 420px; }

.report-summary {
  padding: var(--s-md);
  background: var(--c-bg-page);
  border-radius: var(--r-md);
  margin-bottom: var(--s-md);
}
.report-summary p { margin: 0; font-size: var(--t-sm); color: var(--c-text); line-height: 1.7; }
.report-meta { margin-top: var(--s-xs); font-size: var(--t-xs); color: var(--c-text-3); }

.suggestion-list { display: flex; flex-direction: column; gap: var(--s-md); }
.suggestion-item {
  display: flex; gap: var(--s-md); align-items: flex-start;
  padding: var(--s-md);
  background: var(--c-bg-page);
  border-radius: var(--r-md);
  border-left: 3px solid var(--c-border);
}
.suggestion-item--core { border-left-color: var(--c-brand); }
.suggestion-item--anomaly { border-left-color: var(--c-warning-fg); background: var(--c-warning-bg); }
.suggestion-item--action { border-left-color: var(--c-teal); }
.suggestion-item__body { flex: 1; min-width: 0; }
.suggestion-item__title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); display: flex; align-items: center; gap: var(--s-xs); }
.suggestion-item__detail { font-size: var(--t-xs); color: var(--c-text-2); line-height: 1.6; margin-top: var(--s-xs); }

.suggestion-tag {
  display: inline-flex; align-items: center;
  padding: 1px 8px; border-radius: var(--r-pill);
  font-size: 11px; font-weight: 600;
}
.suggestion-tag--core { background: var(--c-brand-soft); color: var(--c-brand); }
.suggestion-tag--anomaly { background: var(--c-warning-bg); color: var(--c-warning-fg); }
.suggestion-tag--action { background: var(--c-success-bg); color: var(--c-success-fg); }

.row-actions { display: flex; gap: var(--s-xxs); justify-content: flex-end; }
</style>
