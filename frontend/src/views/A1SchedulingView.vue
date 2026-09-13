<script setup lang="ts">
/* ============================================================
 * A1-08 AI 智能排班 /ai/scheduling
 * 真实员工池（org internal 按 6 类排班角色枚举在职员工）
 * + 最近 14 天真实到店登记按星期均值预测客流
 * → 规则计算 3 班×7 天需求矩阵与公平轮转（每人每周 ≤5 班、每天 ≤1 班，缺口如实）
 * → scheduling invoke 全治理链仅生成解读摘要（B47 卡6 去 mock）
 * 诚实口径：成本为岗位参考班薪规则估算（无工资数据源）；M2-03 无后端，不造现排对比，
 * 采纳仅站内幂等登记。
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CKpi from '@/components/CKpi.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CTable from '@/components/CTable.vue'
import CSegmented from '@/components/CSegmented.vue'
import CIcon from '@/components/CIcon.vue'
import { useToast } from '@/composables/useToast'
import { errMsg } from '@/stores/m5Coupon'
import {
  generateScheduling, getSchedulingPlan, adoptSchedulingPlan,
  getSchedulingStats, listSchedulingHistory,
  type SchedulingPlan, type SchedulingStats as Stats, type SchedulingHistoryItem,
} from '@/api/ai'

const toast = useToast()

const tab = ref('gantt')
const tabOptions = [
  { label: '排班建议', value: 'gantt' },
  { label: '成本模拟', value: 'cost' },
  { label: '方案自检', value: 'compare' },
]

function fmtNum(n: number | null | undefined): string {
  return (n ?? 0).toLocaleString('zh-CN')
}

/** 分 → 元/万元。 */
function fmtMoney(fen: number): string {
  const yuan = fen / 100
  if (yuan >= 10000) {
    return `¥${(yuan / 10000).toLocaleString('zh-CN', { maximumFractionDigits: 2 })}万`
  }
  return `¥${yuan.toLocaleString('zh-CN', { maximumFractionDigits: 0 })}`
}

function mondayStr(d: Date): string {
  const m = new Date(d.getFullYear(), d.getMonth(), d.getDate())
  const offset = (m.getDay() + 6) % 7
  m.setDate(m.getDate() - offset)
  const mm = String(m.getMonth() + 1).padStart(2, '0')
  const dd = String(m.getDate()).padStart(2, '0')
  return `${m.getFullYear()}-${mm}-${dd}`
}

function shiftWeek(delta: number): string {
  const d = new Date(weekStart.value + 'T00:00:00')
  d.setDate(d.getDate() + delta * 7)
  return mondayStr(d)
}

const weekStart = ref(mondayStr(new Date()))
const plan = ref<SchedulingPlan | null>(null)
const stats = ref<Stats | null>(null)
const history = ref<SchedulingHistoryItem[]>([])

const loading = ref(false)
const generating = ref(false)
const adopting = ref(false)

const hasPlan = computed(() => !!plan.value)
const adopted = computed(() => plan.value?.status === 'ADOPTED')
const days = computed(() => plan.value?.forecast.map((f) => f.weekday)
  ?? ['周一', '周二', '周三', '周四', '周五', '周六', '周日'])

const shiftColors: Record<string, string> = {
  MORNING: 'var(--c-brand-soft)',
  MID: 'var(--c-info-bg)',
  EVENING: 'var(--c-purple-soft)',
}

const maxCount = computed(() => {
  let m = 1
  for (const row of plan.value?.matrix ?? []) {
    for (const c of row.counts) m = Math.max(m, c)
  }
  return m
})

function barHeight(c: number): string {
  return (8 + (c / maxCount.value) * 88) + 'px'
}

const kpis = computed(() => {
  const p = plan.value
  const s = stats.value
  return [
    {
      label: '下周预测客流', icon: 'customer',
      value: p ? fmtNum(p.forecastTotal) : '—', tone: 'purple' as const,
      trend: p ? '近 14 天真实到店按星期均值' : '当周尚未生成方案',
      trendUp: true, trendGood: true,
    },
    {
      label: '建议排班人次', icon: 'profile',
      value: p ? `${fmtNum(p.slotTotal)}人次` : '—', tone: 'brand' as const,
      trend: p ? `员工池 ${p.staffPoolCount} 人 · 每人每周≤5班` : '生成后由规则矩阵计算',
      trendUp: true, trendGood: true,
    },
    {
      label: '预估周人力成本', icon: 'finance',
      value: p ? fmtMoney(p.costFen) : '—', tone: 'orange' as const,
      trend: '岗位参考班薪规则估算',
      trendUp: true, trendGood: true,
    },
    {
      label: '未覆盖缺口', icon: 'alert',
      value: p ? fmtNum(p.gapSlots) : '—',
      tone: (p?.gapSlots ?? 0) > 0 ? ('warning' as const) : ('teal' as const),
      trend: p
        ? (p.gapSlots > 0 ? '存在容量缺口，请协调兼岗' : '三班需求全部覆盖')
        : (s ? `本周 AI 调用 ${s.weekInvokes} 次` : '加载中…'),
      trendUp: true, trendGood: (p?.gapSlots ?? 0) === 0,
    },
  ]
})

const llmMeta = computed(() => {
  const p = plan.value
  if (!p || !p.modelCode) return ''
  const yuan = ((p.llmCostFen ?? 0) / 100).toFixed(2)
  return `${p.modelCode} · ${p.totalTokens ?? 0} tokens · 解读成本 ¥${yuan} · ai_invoke_log#${p.invokeLogId ?? '-'}`
})

function notePill(type: string) {
  if (type === 'gap') return { status: 'warning' as const, label: '缺口' }
  if (type === 'fairness') return { status: 'info' as const, label: '公平性' }
  return { status: 'success' as const, label: '高峰' }
}

// ---------- 成本明细 ----------
const costCols = [
  { key: 'dayLabel', label: '日期' },
  { key: 'shiftName', label: '班次' },
  { key: 'staffName', label: '员工' },
  { key: 'roleName', label: '岗位' },
  { key: 'hours', label: '工时(h)', align: 'right' as const },
  { key: 'costYuan', label: '预估成本', align: 'right' as const },
]

const costRows = computed(() =>
  (plan.value?.slots ?? []).map((s) => ({
    id: s.slotId,
    dayLabel: s.dayLabel,
    shiftName: s.shiftName,
    staffName: s.gap ? '缺口未分配' : s.staffName,
    roleName: s.gap ? '—' : s.roleName,
    hours: s.hours,
    costYuan: s.gap ? 0 : s.costFen / 100,
    gap: s.gap,
  })),
)

const historyCols = [
  { key: 'weekStart', label: '周起始（周一）' },
  { key: 'summary', label: '排班解读' },
  { key: 'metric', label: '槽位/缺口' },
  { key: 'cost', label: '预估周成本', align: 'right' as const },
  { key: 'status', label: '状态' },
  { key: 'createdAt', label: '生成时间' },
]

const historyRows = computed(() =>
  history.value.map((h, i) => ({
    id: h.weekStart + '-' + i,
    weekStart: h.weekStart,
    summary: h.summary,
    metric: `${h.slotTotal} 人次 / 缺口 ${h.gapSlots}`,
    cost: fmtMoney(h.costFen),
    status: h.status === 'ADOPTED' ? '已采纳' : '草稿',
    createdAt: h.createdAt ?? '-',
  })),
)

async function loadPlan(silent = false) {
  loading.value = true
  try {
    plan.value = await getSchedulingPlan({ weekStart: weekStart.value })
  } catch (e) {
    const msg = String(errMsg(e))
    if (msg.includes('尚未生成')) {
      plan.value = null
    } else if (!silent) {
      toast.error('排班方案加载失败：' + msg)
    }
  } finally {
    loading.value = false
  }
}

async function loadStats() {
  try {
    stats.value = await getSchedulingStats()
  } catch (e) {
    toast.error('排班统计加载失败：' + errMsg(e))
  }
}

async function loadHistory() {
  try {
    history.value = await listSchedulingHistory()
  } catch (e) {
    toast.error('历史方案加载失败：' + errMsg(e))
  }
}

async function changeWeek(delta: number) {
  weekStart.value = shiftWeek(delta)
  await loadPlan()
}

async function onWeekInput(e: Event) {
  const v = (e.target as HTMLInputElement).value
  if (!v) return
  const d = new Date(v + 'T00:00:00')
  if (Number.isNaN(d.getTime())) return
  weekStart.value = mondayStr(d)
  await loadPlan()
}

async function generate() {
  if (generating.value) return
  generating.value = true
  try {
    plan.value = await generateScheduling({ weekStart: weekStart.value })
    toast.success('排班方案已生成：客流预测与员工池均为真实数据，解读经 scheduling 治理链真实出站')
    await Promise.all([loadStats(), loadHistory()])
  } catch (e) {
    toast.error('排班方案生成失败：' + errMsg(e))
  } finally {
    generating.value = false
  }
}

async function adopt() {
  if (adopting.value || !plan.value) return
  adopting.value = true
  try {
    const res = await adoptSchedulingPlan(plan.value.planId)
    if (res.changed) {
      toast.success('已在 AI 侧登记采纳（M2-03 排班后端落地后支持真实回填）')
    } else {
      toast.info('该方案已是采纳状态，无需重复操作')
    }
    await Promise.all([loadPlan(true), loadStats(), loadHistory()])
  } catch (e) {
    toast.error('采纳失败：' + errMsg(e))
  } finally {
    adopting.value = false
  }
}

function openHistoryWeek(w: string) {
  weekStart.value = w
  loadPlan()
}

onMounted(() => {
  loadPlan()
  loadStats()
  loadHistory()
})
</script>

<template>
  <div class="a1-sched">
    <div class="kpis"><CKpi v-for="k in kpis" :key="k.label" v-bind="k" /></div>

    <div class="bar">
      <CSegmented v-model="tab" :options="tabOptions" />
      <div class="bar__ops">
        <div class="week-picker">
          <CButton size="sm" variant="ghost" :disabled="loading" @click="changeWeek(-1)">上一周</CButton>
          <input
            type="date"
            class="week-input"
            :value="weekStart"
            :disabled="generating"
            @change="onWeekInput" />
          <CButton size="sm" variant="ghost" :disabled="loading" @click="changeWeek(1)">下一周</CButton>
        </div>
        <CButton size="sm" variant="secondary" :disabled="generating" @click="generate">
          <CIcon name="trend-up" :size="14" />
          {{ generating ? '生成中（规则矩阵+AI 解读，可能耗时数十秒）…' : hasPlan ? '重新生成' : '生成排班方案' }}
        </CButton>
        <CButton
          size="sm"
          variant="primary"
          :disabled="!hasPlan || adopted || generating || adopting"
          @click="adopt">
          {{ adopting ? '采纳中…' : adopted ? '已采纳（站内登记）' : '采纳方案（站内登记）' }}
        </CButton>
      </div>
    </div>

    <!-- 空态：当周无方案 -->
    <CCard v-if="!hasPlan" padding="lg">
      <div class="empty">
        <CIcon name="calendar" :size="32" />
        <div class="empty__title">{{ loading ? '排班方案加载中…' : `${weekStart} 当周尚未生成 AI 排班方案` }}</div>
        <div class="empty__tip">
          生成将拉取组织服务真实在职员工（店长/咨询师/医生含治疗师/前台含收银）与交易服务最近 14 天真实到店登记，
          由规则算出 3 班×7 天需求矩阵与公平轮转结果，AI 仅经 scheduling 治理链生成解读摘要。
        </div>
        <CButton size="sm" variant="primary" :disabled="generating" @click="generate">
          {{ generating ? '生成中…' : '生成排班方案' }}
        </CButton>
      </div>
    </CCard>

    <template v-else>
      <!-- Tab1：排班建议甘特 + 客流预测 + AI 解读 -->
      <CCard v-if="tab === 'gantt'" padding="lg">
        <template #header>
          <div class="card-head">
            <h3>AI 排班建议甘特（{{ weekStart }} 起一周）</h3>
            <CStatusPill :status="adopted ? 'success' : 'info'" dot>
              {{ adopted ? '已采纳' : '草稿' }}
            </CStatusPill>
          </div>
        </template>

        <div class="gantt">
          <div class="gantt__label gantt__corner">班次</div>
          <div v-for="d in days" :key="d" class="gantt__head">{{ d }}</div>
          <template v-for="row in plan!.matrix" :key="row.shiftCode">
            <div class="gantt__label">
              <span class="shift-dot" :style="{ background: shiftColors[row.shiftCode] }" />
              {{ row.shiftName }} {{ row.shiftTime }}
            </div>
            <div v-for="(c, i) in row.counts" :key="row.shiftCode + i" class="gantt__cell">
              <div
                class="gantt__bar"
                :class="{ 'is-gap-cell': c === 0 }"
                :style="{ background: shiftColors[row.shiftCode], height: barHeight(c) }">
                {{ c }}
              </div>
            </div>
          </template>
        </div>

        <div class="forecast">
          <div class="forecast__title">下周客流预测（近 14 天真实到店按星期均值，周五~周日 ×1.15）</div>
          <div class="forecast__grid">
            <div v-for="f in plan!.forecast" :key="f.date" class="forecast__item">
              <div class="forecast__dow">{{ f.weekday }}</div>
              <div class="forecast__num">{{ f.forecast }}</div>
              <div class="forecast__sample">样本 {{ f.samples }} 天</div>
            </div>
          </div>
        </div>

        <div class="summary">
          <div class="summary__head">
            <CIcon name="trend-up" :size="14" />
            <span>AI 排班解读</span>
            <CStatusPill status="info" dot>scheduling 真实出站</CStatusPill>
          </div>
          <p class="summary__text">{{ plan!.summary }}</p>
          <div class="notes">
            <div v-for="(n, i) in plan!.notes" :key="i" class="note">
              <div class="note__head">
                <CStatusPill :status="notePill(n.type).status" dot>{{ notePill(n.type).label }}</CStatusPill>
                <span class="note__title">{{ n.title }}</span>
              </div>
              <div class="note__detail">{{ n.detail }}</div>
            </div>
          </div>
          <div v-if="llmMeta" class="llm-meta">{{ llmMeta }}</div>
        </div>
      </CCard>

      <!-- Tab2：成本模拟（真实槽位明细 + 规则估算班薪） -->
      <CCard v-else-if="tab === 'cost'" padding="lg">
        <template #header><h3>人力成本模拟（按排班槽位 · 岗位参考班薪）</h3></template>
        <CTable :columns="costCols" :rows="costRows" row-key="id" stripe>
          <template #col-staffName="{ row }">
            <span :class="{ 'gap-text': row.gap }">{{ row.staffName }}</span>
          </template>
          <template #col-costYuan="{ value }">¥{{ Number(value).toLocaleString('zh-CN') }}</template>
        </CTable>
        <div class="total">
          合计（周）：{{ fmtMoney(plan!.costFen) }}
          <span v-if="plan!.gapSlots > 0" class="gap-text">· 含 {{ plan!.gapSlots }} 个缺口槽位未计成本</span>
        </div>
        <p class="hint">{{ plan!.costBasisNote }}</p>
      </CCard>

      <!-- Tab3：方案自检（替代假现排对比，诚实口径） -->
      <CCard v-else padding="lg">
        <template #header><h3>方案自检与口径说明</h3></template>
        <div class="selfcheck">
          <div class="selfcheck__col">
            <h4>本周方案真实指标</h4>
            <ul>
              <li>预测到店总量：{{ fmtNum(plan!.forecastTotal) }} 人次</li>
              <li>规则建议排班：{{ plan!.slotTotal }} 人次（3 班 × 7 天）</li>
              <li>在职排班员工池：{{ plan!.staffPoolCount }} 人</li>
              <li>未覆盖缺口：<span :class="{ 'gap-text': plan!.gapSlots > 0 }">{{ plan!.gapSlots }} 个</span></li>
              <li>预估周人力成本：{{ fmtMoney(plan!.costFen) }}（规则估算）</li>
              <li>方案状态：{{ adopted ? '已采纳（站内登记）' : '草稿' }}</li>
            </ul>
          </div>
          <div class="selfcheck__col selfcheck__col--note">
            <h4>为什么没有「现排对比」</h4>
            <p class="selfcheck__p">{{ plan!.baselineNote }}</p>
            <h4>成本口径</h4>
            <p class="selfcheck__p">{{ plan!.costBasisNote }}</p>
            <h4>模型边界</h4>
            <p class="selfcheck__p">
              客流预测、班次需求、员工轮转与成本全部由规则基于真实数据计算，LLM 仅生成排班解读与建议；
              AI 不参与数字计算，不展示伪造的现排方案、节省额与人效对比。
            </p>
          </div>
        </div>

        <div class="history">
          <div class="history__title">历史方案（按周归并最新版，最多 14 条）</div>
          <CTable
            v-if="historyRows.length > 0"
            :columns="historyCols"
            :rows="historyRows"
            row-key="id"
            stripe>
            <template #col-weekStart="{ value }">
              <button class="link-btn" @click="openHistoryWeek(value)">{{ value }}</button>
            </template>
            <template #col-status="{ value }">
              <CStatusPill :status="value === '已采纳' ? 'success' : 'info'" dot>{{ value }}</CStatusPill>
            </template>
          </CTable>
          <div v-else class="history__empty">暂无历史方案</div>
        </div>
      </CCard>
    </template>

    <div class="compliance-bar">
      <CIcon name="shield" :size="14" />
      <span>
        员工池来自组织服务在职名单、客流来自交易服务真实到店登记；解读经 scheduling 功能角色/门店灰度、
        敏感词、配额计费链治理（ai_invoke_log 留痕），生成/采纳写 T1-04 审计；成本为规则估算、采纳为站内登记，
        M2-03 真实回填与薪酬数据接入为远期能力。
      </span>
    </div>
  </div>
</template>

<style scoped>
.a1-sched { display: flex; flex-direction: column; gap: var(--s-lg); }
.kpis { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .kpis { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
.bar { display: flex; justify-content: space-between; align-items: center; gap: var(--s-sm); flex-wrap: wrap; }
.bar__ops { display: flex; align-items: center; gap: var(--s-sm); flex-wrap: wrap; }
.week-picker { display: flex; align-items: center; gap: 2px; }
.week-input {
  height: 30px; padding: 0 var(--s-sm);
  border: 1px solid var(--c-border); border-radius: var(--r-md);
  background: var(--c-bg-page); font-size: var(--t-xs); color: var(--c-text); outline: none;
}
.week-input:focus { border-color: var(--c-brand); }

.card-head { display: flex; align-items: center; justify-content: space-between; gap: var(--s-sm); width: 100%; }
.card-head h3 { margin: 0; font-size: var(--t-md); font-weight: 700; }

/* 甘特 */
.gantt {
  display: grid;
  grid-template-columns: 140px repeat(7, 1fr);
  gap: 2px;
}
.gantt__corner, .gantt__head {
  font-weight: 600; font-size: var(--t-xs); color: var(--c-text-2);
  display: flex; align-items: center; justify-content: center;
  padding: var(--s-xs);
}
.gantt__label {
  display: flex; align-items: center; gap: var(--s-xs);
  padding: var(--s-xs) var(--s-sm);
  font-size: var(--t-xs); color: var(--c-text); white-space: nowrap;
}
.shift-dot { width: 8px; height: 8px; border-radius: 2px; flex-shrink: 0; }
.gantt__cell {
  display: flex; align-items: flex-end; justify-content: center;
  padding: var(--s-xs); background: var(--c-bg-page);
  border-radius: var(--r-sm); min-height: 110px;
}
.gantt__bar {
  width: 100%; max-width: 46px;
  border-radius: var(--r-sm) var(--r-sm) 0 0;
  display: flex; align-items: flex-start; justify-content: center;
  font-size: 11px; color: var(--c-text); padding-top: 2px;
  min-width: 28px;
}
.gantt__bar.is-gap-cell { opacity: 0.45; }

/* 客流预测 */
.forecast { margin-top: var(--s-lg); }
.forecast__title { font-size: var(--t-xs); font-weight: 600; color: var(--c-text-2); margin-bottom: var(--s-sm); }
.forecast__grid { display: grid; grid-template-columns: repeat(7, 1fr); gap: var(--s-sm); }
.forecast__item {
  background: var(--c-bg-page); border-radius: var(--r-md);
  padding: var(--s-sm); text-align: center;
}
.forecast__dow { font-size: var(--t-xs); color: var(--c-text-3); }
.forecast__num { font-size: var(--t-lg); font-weight: 700; color: var(--c-brand); line-height: 1.4; }
.forecast__sample { font-size: 11px; color: var(--c-text-3); }

/* 解读 */
.summary {
  margin-top: var(--s-lg); padding: var(--s-md);
  background: var(--c-purple-soft); border: 1px solid var(--c-purple);
  border-radius: var(--r-md);
}
.summary__head {
  display: flex; align-items: center; gap: var(--s-xs);
  font-size: var(--t-sm); font-weight: 600; color: var(--c-purple);
}
.summary__text {
  margin: var(--s-sm) 0 0; font-size: var(--t-sm);
  line-height: var(--lh-md); color: var(--c-text);
}
.notes { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: var(--s-sm); margin-top: var(--s-md); }
.note {
  background: var(--c-surface); border: 1px solid var(--c-border-light);
  border-radius: var(--r-md); padding: var(--s-sm) var(--s-md);
}
.note__head { display: flex; align-items: center; gap: var(--s-xs); margin-bottom: 4px; }
.note__title { font-size: var(--t-xs); font-weight: 600; color: var(--c-text); }
.note__detail { font-size: 11px; color: var(--c-text-2); line-height: var(--lh-sm); }
.llm-meta { margin-top: var(--s-sm); font-size: 11px; color: var(--c-text-3); }

/* 成本 */
.total { text-align: right; font-size: var(--t-md); font-weight: 700; color: var(--c-text); margin-top: var(--s-md); }
.gap-text { color: var(--c-danger-fg, var(--c-warning, #c2410c)); font-weight: 600; }
.hint { font-size: var(--t-xs); color: var(--c-text-3); margin: var(--s-sm) 0 0; line-height: var(--lh-sm); }

/* 自检 */
.selfcheck { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-lg); }
.selfcheck__col {
  padding: var(--s-md); border: 1px solid var(--c-border-light);
  border-radius: var(--r-lg); background: var(--c-surface);
}
.selfcheck__col--note { border-color: var(--c-brand); background: var(--c-brand-soft); }
.selfcheck__col h4 { margin: 0 0 var(--s-sm); font-size: var(--t-sm); font-weight: 700; }
.selfcheck__col h4:not(:first-child) { margin-top: var(--s-md); }
.selfcheck__col ul { margin: 0; padding-left: var(--s-md); line-height: 2; font-size: var(--t-sm); color: var(--c-text-2); }
.selfcheck__p { margin: 0; font-size: var(--t-xs); line-height: var(--lh-md); color: var(--c-text-2); }

.history { margin-top: var(--s-lg); }
.history__title { font-size: var(--t-sm); font-weight: 600; margin-bottom: var(--s-sm); }
.history__empty { color: var(--c-text-3); font-size: var(--t-xs); text-align: center; padding: var(--s-lg); }
.link-btn {
  border: none; background: none; padding: 0; cursor: pointer;
  color: var(--c-brand); font-size: var(--t-sm);
}
.link-btn:hover { text-decoration: underline; }

/* 空态 */
.empty {
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: var(--s-sm); padding: var(--s-xl) var(--s-md);
  color: var(--c-text-3); text-align: center;
}
.empty__title { font-size: var(--t-md); font-weight: 600; color: var(--c-text-2); }
.empty__tip { max-width: 560px; font-size: var(--t-xs); line-height: var(--lh-md); }

.compliance-bar {
  display: flex; align-items: center; gap: var(--s-xs);
  padding: var(--s-sm) var(--s-md);
  background: var(--c-danger-bg); color: var(--c-danger-fg);
  font-size: 11px; line-height: var(--lh-sm);
  border-radius: var(--r-md);
}
</style>
