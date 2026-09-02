<script setup lang="ts">
/* ============================================================
 * A1-09 流失预警模型 /ai/churn-model
 * KPI + 风险榜 / 因子分析 / 干预联动
 * ============================================================ */
import { ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CTable from '@/components/CTable.vue'
import CSegmented from '@/components/CSegmented.vue'
import CProgressBar from '@/components/CProgressBar.vue'

const kpis = [
  { label: '高风险客户', icon: 'alert', value: '156', tone: 'danger' as const, trend: '需立即干预', trendUp: false, trendGood: false },
  { label: '中风险客户', icon: 'alert', value: '420', tone: 'warning' as const, trend: '7 天内关注', trendUp: false, trendGood: true },
  { label: '本月召回', icon: 'customer', value: '89', tone: 'success' as const, trend: '召回率 24%', trendUp: true, trendGood: true },
  { label: '模型 AUC', icon: 'settings', value: '0.87', tone: 'purple' as const, threshold: '阈值 0.82', trendUp: true, trendGood: true },
]

const tab = ref('risk')
const tabOpts = [
  { label: '风险榜', value: 'risk' },
  { label: '因子分析', value: 'factor' },
  { label: '干预联动', value: 'link' },
]

// ---------- 风险榜 ----------
const riskRows = [
  { id: 1, name: '李晓雯', phone: '138****1234', level: '高风险', score: 92, factor: '60 天未到店', lastVisit: '2026-06-22', action: '专属顾问外呼 + 唤醒券' },
  { id: 2, name: '王佳琪', phone: '139****5678', level: '高风险', score: 88, factor: '客诉未闭环', lastVisit: '2026-07-08', action: '店长回访 + 补偿方案' },
  { id: 3, name: '陈雅婷', phone: '136****9012', level: '高风险', score: 85, factor: '消费金额骤降', lastVisit: '2026-07-15', action: '定向优惠 + 新品体验' },
  { id: 4, name: '张敏', phone: '137****3456', level: '中风险', score: 72, factor: '到店频次下降', lastVisit: '2026-07-20', action: 'M2-17 唤醒活动' },
  { id: 5, name: '刘思雨', phone: '135****7890', level: '中风险', score: 68, factor: '会员卡余额低', lastVisit: '2026-07-25', action: '充值有礼提醒' },
  { id: 6, name: '周婷婷', phone: '133****2345', level: '中风险', score: 65, factor: '疗程中断', lastVisit: '2026-07-28', action: '疗程跟进提醒' },
  { id: 7, name: '吴静怡', phone: '180****6789', level: '中风险', score: 61, factor: '差评倾向', lastVisit: '2026-08-01', action: '满意度调研' },
  { id: 8, name: '孙悦', phone: '186****0123', level: '低风险', score: 42, factor: '常规波动', lastVisit: '2026-08-10', action: '常规维系' },
]
const riskCols = [
  { key: 'name', label: '客户', width: '140px' },
  { key: 'level', label: '风险等级', width: '100px' },
  { key: 'score', label: '风险分', width: '200px' },
  { key: 'factor', label: '关键因子' },
  { key: 'lastVisit', label: '最近到店', width: '120px' },
  { key: 'action', label: '建议干预' },
  { key: 'ops', label: '操作', width: '120px', align: 'right' as const },
]

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

// ---------- 因子分析 ----------
const factorRows = [
  { id: 1, name: '最近一次到店间隔', weight: 0.32, direction: '负向', desc: '间隔越长流失概率越高' },
  { id: 2, name: '近 90 天消费下降率', weight: 0.24, direction: '负向', desc: '环比下降超 40% 风险显著' },
  { id: 3, name: '客诉/差评次数', weight: 0.18, direction: '负向', desc: '未闭环客诉影响最大' },
  { id: 4, name: '会员卡余额', weight: 0.12, direction: '正向', desc: '余额越低流失越高' },
  { id: 5, name: '互动行为（开券/点击）', weight: 0.09, direction: '正向', desc: '持续互动客户更稳定' },
  { id: 6, name: '会员等级', weight: 0.05, direction: '正向', desc: '高等级客户流失率更低' },
]
const factorCols = [
  { key: 'name', label: '因子' },
  { key: 'weight', label: '重要性权重', width: '260px' },
  { key: 'direction', label: '方向', width: '100px' },
  { key: 'desc', label: '说明' },
]

// ---------- 干预联动 ----------
const linkTargets = [
  {
    code: 'M3-10',
    name: '流失管理',
    status: '已联动',
    statusType: 'success' as const,
    desc: '高风险客户已自动同步至流失管理工作台，支持一键创建干预任务',
    last: '2026-08-26 08:00',
  },
  {
    code: 'M2-17',
    name: '唤醒活动',
    status: '已联动',
    statusType: 'success' as const,
    desc: '中风险客户纳入月度唤醒活动人群包，活动效果回流模型再训练',
    last: '2026-08-25 20:00',
  },
  {
    code: 'M5-03',
    name: '智能推送',
    status: '待配置',
    statusType: 'warning' as const,
    desc: '可配置高风险客户的专属推送通道（短信/企微/公众号）',
    last: '—',
  },
]

function triggerLink() {
  alert('高风险客户已同步至 M3-10 流失管理创建干预任务')
}
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
        <CSegmented v-model="tab" :options="tabOpts" size="sm" />
      </template>

      <!-- 风险榜 -->
      <div v-if="tab === 'risk'">
        <CTable :columns="riskCols" :rows="riskRows" row-key="id">
          <template #col-name="{ row }">
            <div class="cell-customer">
              <div class="avatar">{{ row.name.charAt(0) }}</div>
              <div>
                <div class="cname">{{ row.name }}</div>
                <div class="cphone">{{ row.phone }}</div>
              </div>
            </div>
          </template>
          <template #col-level="{ value }">
            <CStatusPill :status="levelStatus(value)" dot>{{ value }}</CStatusPill>
          </template>
          <template #col-score="{ value }">
            <CProgressBar :value="value" :color="scoreColor(value)" :height="8" :label="`${value}`" />
          </template>
          <template #col-ops>
            <CButton variant="text" size="sm">联动 M3-10</CButton>
          </template>
        </CTable>
      </div>

      <!-- 因子分析 -->
      <div v-else-if="tab === 'factor'">
        <p class="hint">下列因子重要性基于最近 90 天流失样本训练（XGBoost），总权重 100%。</p>
        <CTable :columns="factorCols" :rows="factorRows" row-key="id">
          <template #col-weight="{ value }">
            <div class="weight-bar">
              <div class="weight-bar__track">
                <div class="weight-bar__fill" :style="{ width: (value / 0.32 * 100) + '%' }" />
              </div>
              <span class="weight-val">{{ (value * 100).toFixed(0) }}%</span>
            </div>
          </template>
          <template #col-direction="{ value }">
            <CStatusPill :status="value === '正向' ? 'success' : 'danger'" dot>{{ value }}</CStatusPill>
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
          <CButton variant="secondary" size="sm">配置</CButton>
        </div>

        <div class="link-note">
          <CIcon name="shield" :size="14" />
          <span>所有干预动作记录至 T1-04 审计日志，客户触达符合 A1-17 隐私合规要求。</span>
        </div>
      </div>

      <template #footer>
        <CButton variant="primary" @click="triggerLink">
          <CIcon name="handover" :size="16" />一键联动 M3-10 干预
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
.hint { margin: 0 0 var(--s-md); font-size: var(--t-sm); color: var(--c-text-3); }

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
.weight-val { font-size: var(--t-xs); color: var(--c-text-2); min-width: 36px; text-align: right; font-variant-numeric: tabular-nums; }

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
