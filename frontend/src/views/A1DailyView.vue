<script setup lang="ts">
/* ============================================================
 * A1-05 AI 经营日报
 * 路由 /ai/daily-report
 * 数据来源：T2-04 指标字典 + M1-01 经营看板 + M2-06 门店日报，AI 摘要模型由 T4 提供
 * ============================================================ */
import { ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CKpi from '@/components/CKpi.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CTable from '@/components/CTable.vue'
import CIcon from '@/components/CIcon.vue'

const reportDate = ref('2026-08-26')
const subscribed = ref(true)

interface Suggestion {
  id: string
  type: 'core' | 'anomaly' | 'action'
  title: string
  detail: string
  adopted: boolean
}

const suggestions = ref<Suggestion[]>([
  { id: 'S1', type: 'core', title: '核心指标：营收与到店双增长', detail: '今日营收 ¥28.6 万，环比 +12.4%；到店 186 人，环比 +8.1%。新客占比 22.6%，高于上周均值 19.3%。老客复购率 64.2%，保持稳定。', adopted: false },
  { id: 'S2', type: 'anomaly', title: '异常点：光电项目退单率上升', detail: '今日光电类项目退单 3 单，退单率 4.8%，高于近 7 日均值 2.1%。集中在"光子嫩肤"项目，疑似与今日咨询师李婷的话术调整有关，建议复核。', adopted: false },
  { id: 'S3', type: 'action', title: '行动建议：跟进高意向未到店客户', detail: '有 17 位 7 日内咨询但未预约客户，意向评分 > 80，建议今日 18:00 前由对应咨询师跟进，预计可转化 5-7 单。', adopted: false },
  { id: 'S4', type: 'action', title: '行动建议：补货热门 SKU', detail: '"玻尿酸 1ml"库存剩余 12 支，按近 7 日消耗速度预计 2 日内售罄，建议提交补货申请（参考 M5 库存）。', adopted: false },
  { id: 'S5', type: 'core', title: '员工表现：TOP3 咨询师', detail: '今日业绩 TOP3：王芳（¥4.2 万）、张敏（¥3.8 万）、李婷（¥3.1 万）。王芳连带率 2.3，为全店最高。', adopted: false },
])

const pushColumns = [
  { key: 'channel', label: '推送渠道' },
  { key: 'subscribers', label: '订阅人数', align: 'right' as const },
  { key: 'status', label: '推送状态' },
  { key: 'time', label: '推送时间' },
]

const pushRows = ref([
  { id: 'P1', channel: 'T3-03 站内消息', subscribers: 248, status: 'success', time: '08:00' },
  { id: 'P2', channel: '企业微信', subscribers: 186, status: 'success', time: '08:05' },
  { id: 'P3', channel: '短信', subscribers: 312, status: 'warning', time: '发送中（82%）' },
  { id: 'P4', channel: '邮件', subscribers: 54, status: 'disabled', time: '未订阅' },
])

const historyColumns = [
  { key: 'date', label: '日期' },
  { key: 'summary', label: '核心指标摘要' },
  { key: 'generatedAt', label: '生成时间' },
  { key: 'actions', label: '操作', align: 'right' as const, width: 140 },
]

const historyRows = ref([
  { id: 'H1', date: '2026-08-25', summary: '营收 ¥25.4 万 · 到店 172 · 新客 38 · 异常 1', generatedAt: '08:02:14' },
  { id: 'H2', date: '2026-08-24', summary: '营收 ¥22.8 万 · 到店 158 · 新客 35 · 异常 0', generatedAt: '08:01:45' },
  { id: 'H3', date: '2026-08-23', summary: '营收 ¥30.1 万 · 到店 201 · 新客 48 · 异常 2', generatedAt: '08:03:02' },
  { id: 'H4', date: '2026-08-22', summary: '营收 ¥19.6 万 · 到店 142 · 新客 28 · 异常 1', generatedAt: '08:01:58' },
  { id: 'H5', date: '2026-08-21', summary: '营收 ¥27.3 万 · 到店 189 · 新客 44 · 异常 0', generatedAt: '08:02:30' },
])

function statusPill(s: string) {
  const map: Record<string, { status: 'success' | 'warning' | 'disabled' | 'info'; label: string }> = {
    success: { status: 'success', label: '已送达' },
    warning: { status: 'warning', label: '发送中' },
    disabled: { status: 'disabled', label: '未发送' },
    info: { status: 'info', label: '排队中' },
  }
  return map[s] || { status: 'disabled', label: s }
}

function adoptSuggestion(id: string) {
  const s = suggestions.value.find((x) => x.id === id)
  if (s) s.adopted = true
}

function generateReport() {
  alert('AI 正在基于 T2-04 指标字典 + M1-01 经营看板 + M2-06 门店日报 生成 ' + reportDate.value + ' 日报...')
}

function toggleSubscribe() {
  subscribed.value = !subscribed.value
}
</script>

<template>
  <div class="a1-daily">
    <div class="a1-daily__kpis">
      <CKpi label="今日营收" value="¥28.6万" tone="brand" trend="+12.4%" trend-up trend-good icon="finance" />
      <CKpi label="到店" value="186" tone="teal" trend="+8.1%" trend-up trend-good icon="user-check" />
      <CKpi label="新客" value="42" tone="purple" trend="+10.5%" trend-up trend-good icon="customer" />
      <CKpi label="异常项" value="3" tone="warning" trend="需关注" trend-up :trend-good="false" icon="alert" />
    </div>

    <!-- 顶栏 -->
    <CCard padding="md">
      <div class="toolbar">
        <div class="toolbar__left">
          <label class="field-label">日报日期</label>
          <input v-model="reportDate" type="date" class="date-input" />
          <CButton variant="primary" @click="generateReport">
            <CIcon name="dashboard" :size="14" />
            生成日报
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
            <CStatusPill status="draft" dot>由 T4 模型生成</CStatusPill>
          </div>
          <span class="card-sub">数据来源：T2-04 指标字典 · M1-01 经营看板 · M2-06 门店日报</span>
        </div>
      </template>

      <div class="suggestion-list">
        <div
          v-for="s in suggestions"
          :key="s.id"
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
            :disabled="s.adopted"
            @click="adoptSuggestion(s.id)"
          >
            <CIcon :name="s.adopted ? 'check' : 'plus'" :size="14" />
            {{ s.adopted ? '已采纳' : '采纳为任务' }}
          </CButton>
        </div>
      </div>
    </CCard>

    <!-- 推送状态 -->
    <CCard padding="none">
      <template #header>
        <div class="card-head">
          <h3>推送状态</h3>
          <span class="card-sub">T3-03 通知通道</span>
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
          <CButton size="sm" variant="text">查看全部</CButton>
        </div>
      </template>
      <CTable :columns="historyColumns" :rows="historyRows" row-key="id">
        <template #col-actions>
          <div class="row-actions">
            <CButton size="sm" variant="text">查看</CButton>
            <CButton size="sm" variant="text">对比</CButton>
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
