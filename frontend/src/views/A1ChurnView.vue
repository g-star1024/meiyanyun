<script setup lang="ts">
/* ============================================================
 * A1-09 流失预警模型 /ai/churn-model
 * KPI + 风险榜 / 因子分析 / 干预联动
 * 真实成交 RFM/到店间隔/消费下降信号 + churn invoke 全治理链（B47 卡3 去 mock）
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CTable from '@/components/CTable.vue'
import CSegmented from '@/components/CSegmented.vue'
import CProgressBar from '@/components/CProgressBar.vue'
import { useToast } from '@/composables/useToast'
import { errMsg } from '@/stores/m5Coupon'
import {
  runChurn, listChurn, getChurnStats, getChurnFactors,
  registerChurnIntervene, batchChurnIntervene,
  type ChurnRow, type ChurnStats, type ChurnFactorModel,
} from '@/api/ai'

const toast = useToast()

function fmtNum(n: number): string {
  return n.toLocaleString('zh-CN')
}

// ---------- KPI（全部真实统计；AUC/召回率无真实流失样本回流，不造数） ----------
const stats = ref<ChurnStats>({
  scoredCustomers: 0, highCount: 0, midCount: 0, interveneTotal: 0,
  weekInvokes: 0, modelVersion: 'v1-2026-09', modelNote: '', ran: false,
})
const kpis = computed(() => [
  {
    label: '高风险客户', icon: 'alert', value: fmtNum(stats.value.highCount),
    tone: 'danger' as const, trend: '需立即干预', trendUp: false, trendGood: false,
  },
  {
    label: '中风险客户', icon: 'alert', value: fmtNum(stats.value.midCount),
    tone: 'warning' as const, trend: '7 天内关注', trendUp: false, trendGood: true,
  },
  {
    label: '已登记干预', icon: 'check-square', value: fmtNum(stats.value.interveneTotal),
    tone: 'success' as const, trend: `本周评分调用 ${fmtNum(stats.value.weekInvokes)} 次`, trendUp: true, trendGood: true,
  },
  {
    label: '模型版本', icon: 'settings', value: stats.value.modelVersion || 'v1 基线',
    tone: 'purple' as const, trend: 'AUC/召回率待真实流失样本回流后评估', trendUp: true, trendGood: true,
  },
])

const tab = ref('risk')
const tabOpts = [
  { label: '风险榜', value: 'risk' },
  { label: '因子分析', value: 'factor' },
  { label: '干预联动', value: 'link' },
]

// ---------- 风险榜 ----------
const rows = ref<ChurnRow[]>([])
const listLoading = ref(false)
const hasBatch = ref(false)
const running = ref(false)
const batchBusy = ref(false)
const actionId = ref<number | null>(null)

const riskRows = computed(() => rows.value.map((r) => ({ id: r.predictionId, ...r })))

const riskCols = [
  { key: 'name', label: '客户', width: '140px' },
  { key: 'level', label: '风险等级', width: '100px' },
  { key: 'score', label: '风险分', width: '200px' },
  { key: 'factor', label: '关键因子' },
  { key: 'lastVisit', label: '最近到店', width: '120px' },
  { key: 'action', label: '建议干预' },
  { key: 'ops', label: '操作', width: '120px', align: 'right' as const },
]

function riskLabel(l: string) {
  if (l === 'high') return '高风险'
  if (l === 'mid') return '中风险'
  return '低风险'
}
function levelStatus(l: string) {
  if (l === '高风险') return 'danger' as const
  if (l === '中风险') return 'warning' as const
  return 'success' as const
}
function scoreColor(s: number) {
  if (s >= 85) return 'var(--c-danger-fg)'
  if (s >= 60) return 'var(--c-warning-fg)'
  return 'var(--c-success-fg)'
}

async function loadStats() {
  try {
    stats.value = await getChurnStats()
  } catch (e) {
    toast.error('流失统计加载失败：' + errMsg(e))
  }
}

async function loadList() {
  listLoading.value = true
  try {
    rows.value = await listChurn()
    hasBatch.value = true
  } catch (e) {
    if (String(errMsg(e)).includes('尚未运行')) {
      rows.value = []
      hasBatch.value = false
    } else {
      toast.error('风险榜加载失败：' + errMsg(e))
    }
  } finally {
    listLoading.value = false
  }
}

async function doRun() {
  running.value = true
  try {
    const batch = await runChurn({})
    toast.success(`批次 ${batch.batchNo} 已生成，覆盖 ${batch.size} 位客户（高风险 ${batch.highCount} / 中风险 ${batch.midCount}）`)
    await Promise.all([loadStats(), loadList()])
  } catch (e) {
    toast.error('流失评分运行失败：' + errMsg(e))
  } finally {
    running.value = false
  }
}

async function doIntervene(row: ChurnRow) {
  if (row.interveneRegistered) {
    toast.info('该客户已登记干预，无需重复操作')
    return
  }
  actionId.value = row.predictionId
  try {
    const res = await registerChurnIntervene(row.predictionId)
    if (res.changed) {
      row.interveneRegistered = true
      stats.value.interveneTotal += 1
      toast.success('已登记干预（跨域下发 M3-10 流失管理见远期规划）')
    } else {
      row.interveneRegistered = true
      toast.info('该客户已登记干预，无需重复操作')
    }
  } catch (e) {
    toast.error('干预登记失败：' + errMsg(e))
  } finally {
    actionId.value = null
  }
}

async function batchIntervene() {
  if (!hasBatch.value) {
    toast.warning('尚未运行流失评分，请先点击「运行评分」')
    return
  }
  batchBusy.value = true
  try {
    const res = await batchChurnIntervene()
    if (res.changed) {
      toast.success(`批次 ${res.batchNo} 已批量登记 ${res.affected} 条干预`)
      await Promise.all([loadStats(), loadList()])
    } else {
      toast.info('本批次客户均已登记干预，无需重复操作')
    }
  } catch (e) {
    toast.error('批量登记失败：' + errMsg(e))
  } finally {
    batchBusy.value = false
  }
}

// ---------- 因子分析（真实信号可用 / 客诉差评·行为埋点不可得诚实置灰） ----------
const factorModel = ref<ChurnFactorModel | null>(null)

const factorRows = computed(() =>
  (factorModel.value?.rows ?? []).map((f) => ({
    id: f.rank,
    name: f.title,
    weight: f.weight,
    direction: f.rank <= 3 ? '负向' : '正向',
    desc: f.desc,
    available: f.available,
    unavailableNote: f.unavailableNote,
  })))
const factorCols = [
  { key: 'name', label: '因子' },
  { key: 'weight', label: '重要性权重', width: '260px' },
  { key: 'direction', label: '方向', width: '100px' },
  { key: 'desc', label: '说明' },
]

async function loadFactors() {
  try {
    factorModel.value = await getChurnFactors()
  } catch (e) {
    toast.error('因子模型加载失败：' + errMsg(e))
  }
}

// ---------- 干预联动（站内登记已留痕；M3-10/M2-17/M5-03 真实下发为远期 Backlog） ----------
const linkTargets = [
  {
    code: 'M3-10',
    name: '流失管理',
    status: '规划中',
    statusType: 'default' as const,
    desc: '高风险客户的站内干预登记已在本页留痕；自动同步流失管理工作台、一键创建干预任务待 M3-10 建设后打通。',
    last: '—',
  },
  {
    code: 'M2-17',
    name: '唤醒活动',
    status: '规划中',
    statusType: 'default' as const,
    desc: '中风险客户纳入月度唤醒活动人群包、活动效果回流为远期规划，当前暂无人群包下发通道。',
    last: '—',
  },
  {
    code: 'M5-03',
    name: '智能推送',
    status: '规划中',
    statusType: 'default' as const,
    desc: '高风险客户专属推送通道（短信/企微/公众号）待营销推送系统建设后配置，当前仅支持站内登记。',
    last: '—',
  },
]

onMounted(() => {
  loadStats()
  loadList()
  loadFactors()
})
</script>

<template>
  <div class="a1-churn">
    <div class="a1-churn__kpis">
      <CKpi
        v-for="k in kpis"
        :key="k.label"
        :label="k.label"
        :value="k.value"
        :tone="k.tone"
        :trend="k.trend"
        :trend-up="k.trendUp"
        :trend-good="k.trendGood" :icon="k.icon" />
    </div>

    <CCard>
      <template #header>
        <h3 class="card-title"><CIcon name="alert" :size="16" /> 流失预警模型</h3>
        <div class="filters">
          <CSegmented v-model="tab" :options="tabOpts" size="sm" />
          <CButton v-if="tab === 'risk'" variant="primary" size="sm" :disabled="running" @click="doRun">
            <CIcon name="refresh" :size="14" />{{ running ? '评分中…' : '运行评分' }}
          </CButton>
        </div>
      </template>

      <!-- 风险榜 -->
      <div v-if="tab === 'risk'">
        <div v-if="running" class="churn-state">
          <CIcon name="loading" :size="28" />
          <p>正在逐客户调用大模型生成流失风险分，通常需数十秒，请勿离开本页…</p>
        </div>
        <div v-else-if="listLoading" class="churn-state">
          <CIcon name="loading" :size="28" />
          <p>正在加载最近一批流失评分…</p>
        </div>
        <div v-else-if="!hasBatch" class="churn-state">
          <CIcon name="alert" :size="28" />
          <p>尚未运行流失评分，点击右上角「运行评分」生成首批风险榜。</p>
        </div>
        <div v-else-if="!riskRows.length" class="churn-state">
          <CIcon name="alert" :size="28" />
          <p>当前批次暂无评分客户，请重新运行评分。</p>
        </div>
        <CTable v-else :columns="riskCols" :rows="riskRows" row-key="id">
          <template #col-name="{ row }">
            <div class="cell-customer">
              <div class="avatar">{{ (row.customerName || '客').charAt(0) }}</div>
              <div>
                <div class="cname">{{ row.customerName }}</div>
                <div class="cphone">{{ row.phone || '—' }}</div>
              </div>
            </div>
          </template>
          <template #col-level="{ row }">
            <CStatusPill :status="levelStatus(riskLabel(row.riskLevel))" dot>{{ riskLabel(row.riskLevel) }}</CStatusPill>
          </template>
          <template #col-score="{ value }">
            <CProgressBar :value="value" :color="scoreColor(value)" :height="8" :label="`${value}`" />
          </template>
          <template #col-factor="{ row }">
            <span>{{ row.keyFactor || '—' }}</span>
          </template>
          <template #col-lastVisit="{ row }">
            <span>{{ row.lastVisitDate || '—' }}</span>
          </template>
          <template #col-action="{ row }">
            <span>{{ row.suggestedAction || '—' }}</span>
          </template>
          <template #col-ops="{ row }">
            <CButton
              variant="text" size="sm"
              :disabled="actionId === row.predictionId || row.interveneRegistered"
              @click="doIntervene(row as ChurnRow)"
            >{{ row.interveneRegistered ? '已干预' : '登记干预' }}</CButton>
          </template>
        </CTable>
      </div>

      <!-- 因子分析 -->
      <div v-else-if="tab === 'factor'">
        <p class="hint">{{ factorModel?.modelVersion }} · {{ factorModel?.note || 'v1 基线先验权重（非实时训练产物），暂无数据源的因子不参与评分。' }}</p>
        <CTable :columns="factorCols" :rows="factorRows" row-key="id">
          <template #col-name="{ row }">
            <span :class="{ 'factor-off-name': !row.available }">{{ row.name }}</span>
          </template>
          <template #col-weight="{ row }">
            <div class="weight-bar">
              <div class="weight-bar__track">
                <div
                  class="weight-bar__fill"
                  :class="{ 'is-off': !row.available }"
                  :style="{ width: row.available ? (row.weight / 0.40 * 100) + '%' : '0%' }"
                />
              </div>
              <span class="weight-val">{{ row.available ? (row.weight * 100).toFixed(0) + '%' : '暂无数据' }}</span>
            </div>
          </template>
          <template #col-direction="{ row }">
            <CStatusPill v-if="row.available" :status="row.direction === '正向' ? 'success' : 'danger'" dot>{{ row.direction }}</CStatusPill>
            <CStatusPill v-else status="default" dot>不纳入</CStatusPill>
          </template>
          <template #col-desc="{ row }">
            <div>
              <div>{{ row.desc }}</div>
              <div v-if="!row.available && row.unavailableNote" class="factor-off-note">
                <CIcon name="shield" :size="12" />{{ row.unavailableNote }}
              </div>
            </div>
          </template>
        </CTable>
      </div>

      <!-- 干预联动 -->
      <div v-else class="link">
        <div v-for="t in linkTargets" :key="t.code" class="link-item">
          <div class="link-item__icon">
            <CIcon name="handover" :size="20" />
          </div>
          <div class="link-item__body">
            <div class="link-item__head">
              <span class="link-code">{{ t.code }}</span>
              <span class="link-name">{{ t.name }}</span>
              <CStatusPill :status="t.statusType" dot>{{ t.status }}</CStatusPill>
            </div>
            <div class="link-item__desc">{{ t.desc }}</div>
            <div class="link-item__last">最近同步：{{ t.last }}</div>
          </div>
          <CButton variant="secondary" size="sm" disabled>规划中</CButton>
        </div>

        <div class="link-note">
          <CIcon name="shield" :size="14" />
          <span>所有干预登记记录至 T1-04 审计日志，客户触达符合 A1-17 隐私合规要求；真实跨系统下发待对应模块建设后打通。</span>
        </div>
      </div>

      <template #footer>
        <CButton variant="primary" :disabled="running || batchBusy || !hasBatch" @click="batchIntervene">
          <CIcon name="handover" :size="16" />{{ batchBusy ? '登记中…' : '批量登记干预' }}
        </CButton>
      </template>
    </CCard>
  </div>
</template>

<style scoped>
.a1-churn {
  display: flex;
  flex-direction: column;
  gap: var(--s-md);
}
.a1-churn__kpis { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .a1-churn__kpis { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
.card-title {
  display: inline-flex;
  align-items: center;
  gap: var(--s-xs);
  font-size: var(--t-md);
  font-weight: 700;
}
.filters { display: flex; align-items: center; gap: var(--s-sm); }
.hint { margin: 0 0 var(--s-md); font-size: var(--t-sm); color: var(--c-text-3); }

.churn-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--s-sm);
  padding: var(--s-lg) 0;
  color: var(--c-text-3);
  font-size: var(--t-sm);
  text-align: center;
}
.churn-state p { margin: 0; max-width: 380px; }

.cell-customer { display: flex; align-items: center; gap: var(--s-sm); }
.avatar {
  width: 32px; height: 32px;
  border-radius: 50%;
  background: var(--c-purple-soft);
  color: var(--c-purple);
  display: flex; align-items: center; justify-content: center;
  font-size: var(--t-sm); font-weight: 700;
}
.cname { font-size: var(--t-sm); color: var(--c-text); font-weight: 600; }
.cphone { font-size: var(--t-xs); color: var(--c-text-3); }

.weight-bar { display: flex; align-items: center; gap: var(--s-sm); }
.weight-bar__track {
  flex: 1; height: 8px;
  background: var(--c-chart-track);
  border-radius: 999px; overflow: hidden;
}
.weight-bar__fill {
  height: 100%;
  background: linear-gradient(90deg, var(--c-purple), var(--c-brand));
  border-radius: 999px;
}
.weight-bar__fill.is-off { background: var(--c-border); }
.weight-val { font-size: var(--t-xs); color: var(--c-text-2); min-width: 48px; text-align: right; font-variant-numeric: tabular-nums; }
.factor-off-name { color: var(--c-text-3); }
.factor-off-note {
  display: flex;
  align-items: center;
  gap: var(--s-xxs);
  font-size: var(--t-xs);
  color: var(--c-text-4);
  line-height: var(--lh-sm);
}

.link { display: flex; flex-direction: column; gap: var(--s-md); }
.link-item {
  display: flex;
  align-items: center;
  gap: var(--s-md);
  padding: var(--s-md);
  border: 1px solid var(--c-border-light);
  border-radius: var(--r-lg);
  background: var(--c-bg-page);
}
.link-item__icon {
  width: 44px; height: 44px;
  border-radius: var(--r-md);
  background: var(--c-brand-soft);
  color: var(--c-brand);
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
}
.link-item__body { flex: 1; display: flex; flex-direction: column; gap: var(--s-xxs); }
.link-item__head { display: flex; align-items: center; gap: var(--s-sm); }
.link-code {
  font-size: var(--t-xs);
  font-weight: 700;
  color: var(--c-purple);
  background: var(--c-purple-soft);
  padding: 2px 8px;
  border-radius: var(--r-sm);
}
.link-name { font-size: var(--t-base); font-weight: 600; color: var(--c-text); }
.link-item__desc { font-size: var(--t-sm); color: var(--c-text-2); }
.link-item__last { font-size: var(--t-xs); color: var(--c-text-3); }
.link-note {
  display: flex;
  align-items: center;
  gap: var(--s-xs);
  padding: var(--s-sm) var(--s-md);
  background: var(--c-info-bg);
  color: var(--c-info-fg);
  font-size: var(--t-xs);
  border-radius: var(--r-md);
}
</style>
