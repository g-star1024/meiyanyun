<script setup lang="ts">
/* A1-03 复购预测 /ai/repurchase — 真实交易 RFM 信号 + repurchase invoke 全治理链（B47 卡2 去 mock） */
import { computed, onMounted, ref, watch } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CTable from '@/components/CTable.vue'
import CSegmented from '@/components/CSegmented.vue'
import CProgressBar from '@/components/CProgressBar.vue'
import CSelect from '@/components/CSelect.vue'
import { useToast } from '@/composables/useToast'
import { errMsg } from '@/stores/m5Coupon'
import {
  runRepurchase, listRepurchase, getRepurchaseStats, getRepurchaseFactors,
  registerRepurchaseFollowup, registerRepurchasePush, batchRepurchaseFollowup,
  type RepurchaseRow, type RepurchaseStats, type RepurchaseFactorModel,
} from '@/api/ai'

const toast = useToast()

function fmtNum(n: number): string {
  return n.toLocaleString('zh-CN')
}

function yuan(fen: number): string {
  return fmtNum(Math.round(fen / 100))
}

function fmtAmount(fen: number): string {
  if (fen >= 10000000) return `¥${(fen / 10000000).toFixed(1)}千万`
  if (fen >= 1000000) return `¥${(fen / 1000000).toFixed(1)}百万`
  if (fen >= 100000) return `¥${(fen / 100000).toFixed(1)}万`
  return `¥${yuan(fen)}`
}

// ---------- KPI（全部真实统计，趋势文案诚实表达，无历史不造环比） ----------
const stats = ref<RepurchaseStats>({
  predictedCustomers: 0, avgProb: 0, expectedTotalFen: 0, expectedNote: '',
  followupTotal: 0, weekInvokes: 0, trendNote: '', modelVersion: '', ran: false,
})
const kpis = computed(() => [
  {
    label: '预测复购客户', icon: 'customer', value: fmtNum(stats.value.predictedCustomers),
    tone: 'purple' as const, trend: stats.value.trendNote || '本批次真实人数', trendUp: true,
  },
  {
    label: '平均概率', icon: 'trend-up', value: `${stats.value.avgProb}%`,
    tone: 'brand' as const, trend: stats.value.modelVersion ? `模型 ${stats.value.modelVersion}` : '模型基线', trendUp: true,
  },
  {
    label: '预计转化', icon: 'trend-up', value: stats.value.expectedTotalFen > 0 ? fmtAmount(stats.value.expectedTotalFen) : '¥0',
    tone: 'orange' as const, trend: stats.value.expectedNote || '模型估算，非成交承诺', trendUp: true,
  },
  {
    label: '已建任务', icon: 'check-square', value: fmtNum(stats.value.followupTotal),
    tone: 'teal' as const, trend: `本周预测调用 ${fmtNum(stats.value.weekInvokes)} 次`, trendUp: true,
  },
])

// ---------- 筛选 ----------
const period = ref('week')
const periodOpts = [
  { label: '本周', value: 'week' },
  { label: '本月', value: 'month' },
  { label: '本季', value: 'quarter' },
]
const projectId = ref('all')
const projectOpts = [
  { label: '全部项目', value: 'all' },
  { label: '皮肤管理', value: 'skin' },
  { label: '医美注射', value: 'inject' },
  { label: '抗衰疗程', value: 'anti' },
  { label: '身体护理', value: 'body' },
]

// ---------- 榜单 ----------
const rows = ref<RepurchaseRow[]>([])
const listLoading = ref(false)
const hasBatch = ref(false)
const running = ref(false)
const batchBusy = ref(false)
const actionId = ref<number | null>(null)

const tableRows = computed(() =>
  rows.value.map((r) => ({ id: r.predictionId, ...r })))

const cols = [
  { key: 'name', label: '客户名', width: '110px' },
  { key: 'phone', label: '手机号', width: '140px' },
  { key: 'project', label: '推荐项目' },
  { key: 'timing', label: '推荐时机', width: '110px' },
  { key: 'prob', label: '复购概率', width: '220px' },
  { key: 'ops', label: '操作', width: '160px', align: 'right' as const },
]

async function loadStats() {
  try {
    stats.value = await getRepurchaseStats(period.value)
  } catch (e) {
    toast.error('复购统计加载失败：' + errMsg(e))
  }
}

async function loadList() {
  listLoading.value = true
  try {
    rows.value = await listRepurchase(period.value, projectId.value)
    hasBatch.value = true
  } catch (e) {
    if (String(errMsg(e)).includes('尚未运行')) {
      rows.value = []
      hasBatch.value = false
    } else {
      toast.error('复购榜单加载失败：' + errMsg(e))
    }
  } finally {
    listLoading.value = false
  }
}

async function doRun() {
  running.value = true
  try {
    const batch = await runRepurchase({ period: period.value })
    toast.success(`批次 ${batch.batchNo} 已生成，覆盖 ${batch.size} 位客户`)
    await Promise.all([loadStats(), loadList()])
  } catch (e) {
    toast.error('复购预测运行失败：' + errMsg(e))
  } finally {
    running.value = false
  }
}

async function doFollowup(row: RepurchaseRow) {
  if (row.followupRegistered) {
    toast.info('该客户已登记跟进，无需重复操作')
    return
  }
  actionId.value = row.predictionId
  try {
    const res = await registerRepurchaseFollowup(row.predictionId)
    if (res.changed) {
      row.followupRegistered = true
      stats.value.followupTotal += 1
      toast.success('已登记跟进任务（跨域下发 M3-08 见远期规划）')
    } else {
      row.followupRegistered = true
      toast.info('该客户已登记跟进，无需重复操作')
    }
  } catch (e) {
    toast.error('跟进登记失败：' + errMsg(e))
  } finally {
    actionId.value = null
  }
}

async function doPush(row: RepurchaseRow) {
  if (row.pushRegistered) {
    toast.info('该客户已登记推送，无需重复操作')
    return
  }
  actionId.value = row.predictionId
  try {
    const res = await registerRepurchasePush(row.predictionId)
    if (res.changed) {
      row.pushRegistered = true
      toast.success('已登记推送任务（真实触达 M5-03 见远期规划）')
    } else {
      row.pushRegistered = true
      toast.info('该客户已登记推送，无需重复操作')
    }
  } catch (e) {
    toast.error('推送登记失败：' + errMsg(e))
  } finally {
    actionId.value = null
  }
}

async function batchCreate() {
  if (!hasBatch.value) {
    toast.warning('当前周期尚未运行复购预测，请先点击「运行预测」')
    return
  }
  batchBusy.value = true
  try {
    const res = await batchRepurchaseFollowup(period.value)
    if (res.changed) {
      toast.success(`批次 ${res.batchNo} 已批量登记 ${res.affected} 条跟进任务`)
      await Promise.all([loadStats(), loadList()])
    } else {
      toast.info('本批次客户均已登记跟进，无需重复操作')
    }
  } catch (e) {
    toast.error('批量登记失败：' + errMsg(e))
  } finally {
    batchBusy.value = false
  }
}

// ---------- 推荐依据（真实信号可用 / 行为埋点不可得诚实置灰） ----------
const factorModel = ref<RepurchaseFactorModel | null>(null)

async function loadFactors() {
  try {
    factorModel.value = await getRepurchaseFactors()
  } catch (e) {
    toast.error('推荐依据加载失败：' + errMsg(e))
  }
}

function probColor(p: number) {
  if (p >= 85) return 'var(--c-danger-fg)'
  if (p >= 70) return 'var(--c-warning-fg)'
  return 'var(--c-info-fg)'
}

watch(period, () => {
  loadStats()
  loadList()
})
watch(projectId, () => loadList())

onMounted(() => {
  loadStats()
  loadList()
  loadFactors()
})
</script>

<template>
  <div class="a1-repurchase">
    <div class="a1-repurchase__kpis">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :trend="k.trend" :trend-up="k.trendUp" :icon="k.icon" />
    </div>

    <CCard>
      <template #header>
        <h3 class="card-title"><CIcon name="trend-up" :size="16" /> 复购预测</h3>
        <div class="filters">
          <CSegmented v-model="period" :options="periodOpts" size="sm" />
          <CSelect v-model="projectId" :options="projectOpts" width="150px" />
          <CButton variant="primary" size="sm" :disabled="running" @click="doRun">
            <CIcon name="refresh" :size="14" />{{ running ? '预测中…' : '运行预测' }}
          </CButton>
        </div>
      </template>

      <div class="layout">
        <div class="layout__main">
          <div v-if="running" class="repurchase-state">
            <CIcon name="loading" :size="28" />
            <p>正在逐客户调用大模型生成复购概率，通常需数十秒，请勿离开本页…</p>
          </div>
          <div v-else-if="listLoading" class="repurchase-state">
            <CIcon name="loading" :size="28" />
            <p>正在加载最近一批复购预测…</p>
          </div>
          <div v-else-if="!hasBatch" class="repurchase-state">
            <CIcon name="trend-up" :size="28" />
            <p>{{ period === 'week' ? '本周' : period === 'month' ? '本月' : '本季' }}尚未运行复购预测，点击右上角「运行预测」生成首批榜单。</p>
          </div>
          <div v-else-if="!rows.length" class="repurchase-state">
            <CIcon name="trend-up" :size="28" />
            <p>当前项目筛选下暂无预测客户，请切换项目分类查看。</p>
          </div>
          <CTable v-else :columns="cols" :rows="tableRows" row-key="id">
            <template #col-name="{ row }">
              <div class="cell-name">
                <span class="cell-name__text">{{ row.customerName }}</span>
                <CStatusPill v-if="row.level" status="default" dot>{{ row.level }}</CStatusPill>
              </div>
            </template>
            <template #col-project="{ row }">
              <div class="cell-project">
                <span class="cell-project__name">{{ row.projectName }}</span>
                <span class="cell-project__amount">预计客单 ¥{{ yuan(row.expectedAmountFen) }}</span>
              </div>
            </template>
            <template #col-timing="{ value }">
              <CStatusPill status="primary" dot>{{ value }}</CStatusPill>
            </template>
            <template #col-prob="{ value }">
              <CProgressBar :value="value" :color="probColor(value)" :height="8" :label="`${value}%`" />
            </template>
            <template #col-ops="{ row }">
              <CButton
                variant="text" size="sm"
                :disabled="actionId === row.predictionId || row.followupRegistered"
                @click="doFollowup(row as RepurchaseRow)"
              >{{ row.followupRegistered ? '已跟进' : '建跟进' }}</CButton>
              <CButton
                variant="text" size="sm"
                :disabled="actionId === row.predictionId || row.pushRegistered"
                @click="doPush(row as RepurchaseRow)"
              >{{ row.pushRegistered ? '已推送' : '推送' }}</CButton>
            </template>
          </CTable>
        </div>

        <aside class="layout__side">
          <div class="factor-card">
            <div class="factor-card__head">
              <CIcon name="marketing" :size="16" />
              <h4>Top 3 推荐依据</h4>
            </div>
            <ol class="factor-list">
              <li
                v-for="f in (factorModel?.rows ?? [])"
                :key="f.rank"
                class="factor-item"
                :class="{ 'is-unavailable': !f.available }"
              >
                <div class="factor-rank">{{ f.rank }}</div>
                <div class="factor-body">
                  <div class="factor-title">{{ f.title }}</div>
                  <div class="factor-desc">{{ f.desc }}</div>
                  <CProgressBar
                    :value="Math.round(f.weight * 100)"
                    :color="f.available ? 'var(--c-purple)' : 'var(--c-border)'"
                    :height="4"
                    :label="f.available ? `权重 ${(f.weight * 100).toFixed(0)}%` : '暂无数据'"
                  />
                  <div v-if="!f.available && f.unavailableNote" class="factor-unavailable">
                    <CIcon name="shield" :size="12" />{{ f.unavailableNote }}
                  </div>
                </div>
              </li>
            </ol>
            <div v-if="factorModel?.note" class="factor-model-note">{{ factorModel.modelVersion }} · {{ factorModel.note }}</div>
            <div class="factor-note">
              <CIcon name="shield" :size="12" />
              <span>预测结果仅供参考，实际触达需符合 A1-17 隐私规范。</span>
            </div>
          </div>
        </aside>
      </div>

      <template #footer>
        <CButton variant="primary" :disabled="running || batchBusy || !hasBatch" @click="batchCreate">
          <CIcon name="plus" :size="16" />{{ batchBusy ? '登记中…' : '批量建跟进任务' }}
        </CButton>
      </template>
    </CCard>
  </div>
</template>

<style scoped>
.a1-repurchase {
  display: flex;
  flex-direction: column;
  gap: var(--s-md);
}
.a1-repurchase__kpis {
  display: grid;
  grid-auto-flow: column;
  grid-auto-columns: 1fr;
  gap: var(--s-md);
}
@media (max-width: 1024px) {
  .a1-repurchase__kpis { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); }
}
.card-title {
  display: inline-flex;
  align-items: center;
  gap: var(--s-xs);
  font-size: var(--t-md);
  font-weight: 700;
}
.filters { display: flex; align-items: center; gap: var(--s-sm); }

.layout {
  display: grid;
  grid-template-columns: 1fr 320px;
  gap: var(--s-lg);
}
.layout__side { display: flex; }
.factor-card {
  flex: 1;
  border: 1px solid var(--c-border-light);
  border-radius: var(--r-lg);
  padding: var(--s-md);
  background: var(--c-purple-soft);
  display: flex;
  flex-direction: column;
  gap: var(--s-md);
}
.factor-card__head {
  display: flex;
  align-items: center;
  gap: var(--s-xs);
  color: var(--c-purple);
}
.factor-card__head h4 {
  font-size: var(--t-base);
  color: var(--c-text);
}
.factor-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: var(--s-md); }
.factor-item { display: flex; gap: var(--s-sm); }
.factor-rank {
  width: 22px; height: 22px;
  border-radius: 50%;
  background: var(--c-purple);
  color: #fff;
  font-size: var(--t-xs);
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.factor-body { flex: 1; display: flex; flex-direction: column; gap: var(--s-xxs); }
.factor-title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.factor-desc { font-size: var(--t-xs); color: var(--c-text-2); line-height: var(--lh-sm); }
.factor-note {
  display: flex;
  align-items: center;
  gap: var(--s-xxs);
  font-size: var(--t-xs);
  color: var(--c-text-3);
  padding-top: var(--s-sm);
  border-top: 1px dashed var(--c-border);
}

/* 加载 / 空态（B47 卡2 新增） */
.repurchase-state {
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
.repurchase-state p { margin: 0; max-width: 380px; }

/* 榜单行内信息（B47 卡2 新增） */
.cell-name { display: flex; align-items: center; gap: var(--s-xs); }
.cell-name__text { font-weight: 600; color: var(--c-text); }
.cell-project { display: flex; flex-direction: column; gap: 2px; }
.cell-project__amount { font-size: var(--t-xs); color: var(--c-text-3); font-variant-numeric: tabular-nums; }

/* 不可得因子置灰（B47 卡2 新增） */
.factor-item.is-unavailable .factor-rank { background: var(--c-border); }
.factor-item.is-unavailable .factor-title { color: var(--c-text-3); }
.factor-unavailable {
  display: flex;
  align-items: center;
  gap: var(--s-xxs);
  font-size: var(--t-xs);
  color: var(--c-text-4);
  line-height: var(--lh-sm);
}
.factor-model-note {
  font-size: var(--t-xs);
  color: var(--c-text-3);
  line-height: var(--lh-sm);
}
</style>
