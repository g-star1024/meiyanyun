<script setup lang="ts">
/* ============================================================
 * A1-03 复购预测 /ai/repurchase
 * KPI + 周期筛选 + 复购概率榜 + 推荐依据
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
import CSelect from '@/components/CSelect.vue'

const kpis = [
  { label: '预测复购客户', icon: 'customer', value: '842', tone: 'purple' as const, trend: '较上周 +46', trendUp: true },
  { label: '平均概率', icon: 'trend-up', value: '72%', tone: 'brand' as const, trend: '模型 v2.3', trendUp: true },
  { label: '预计转化', icon: 'trend-up', value: '¥128万', tone: 'orange' as const, trend: '置信区间 ±8%', trendUp: true },
  { label: '已建任务', icon: 'check-square', value: '36', tone: 'teal' as const, trend: '本周新增 12', trendUp: true },
]

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

interface RepurchaseRow {
  id: number
  name: string
  phone: string
  project: string
  timing: string
  prob: number
}
const rows = ref<RepurchaseRow[]>([
  { id: 1, name: '李晓雯', phone: '138****1234', project: '热玛吉五代', timing: '3 天内', prob: 92 },
  { id: 2, name: '王佳琪', phone: '139****5678', project: '水光针疗程', timing: '本周', prob: 88 },
  { id: 3, name: '陈雅婷', phone: '136****9012', project: '光子嫩肤', timing: '本周', prob: 85 },
  { id: 4, name: '张敏', phone: '137****3456', project: '抗衰紧致套组', timing: '1 周内', prob: 82 },
  { id: 5, name: '刘思雨', phone: '135****7890', project: '皮秒祛斑', timing: '2 周内', prob: 78 },
  { id: 6, name: '周婷婷', phone: '133****2345', project: '玻尿酸填充', timing: '2 周内', prob: 75 },
  { id: 7, name: '吴静怡', phone: '180****6789', project: '身体塑形', timing: '本月', prob: 71 },
  { id: 8, name: '孙悦', phone: '186****0123', project: '眼部护理', timing: '本月', prob: 68 },
  { id: 9, name: '赵雨晴', phone: '188****4567', project: '脱毛年卡', timing: '本月', prob: 64 },
  { id: 10, name: '郑美玲', phone: '151****8901', project: '头皮养护', timing: '本月', prob: 60 },
])

const cols = [
  { key: 'name', label: '客户名', width: '110px' },
  { key: 'phone', label: '手机号', width: '140px' },
  { key: 'project', label: '推荐项目' },
  { key: 'timing', label: '推荐时机', width: '110px' },
  { key: 'prob', label: '复购概率', width: '220px' },
  { key: 'ops', label: '操作', width: '160px', align: 'right' as const },
]

const factors = [
  { rank: 1, title: '历史项目周期吻合', desc: '该客群距上次同类项目消费平均间隔 42 天，当前已达 40 天', weight: 0.38 },
  { rank: 2, title: '浏览/咨询行为活跃', desc: '近 7 天内查看项目详情 ≥3 次，客服会话提及项目名 2 次', weight: 0.27 },
  { rank: 3, title: '会员卡余额充足', desc: '会员卡余额 ≥ 推荐项目客单价的 1.2 倍', weight: 0.19 },
]

function probColor(p: number) {
  if (p >= 85) return 'var(--c-danger-fg)'
  if (p >= 70) return 'var(--c-warning-fg)'
  return 'var(--c-info-fg)'
}

function batchCreate() {
  alert('已批量创建跟进任务至 M3-08，推送任务至 M5-03')
}
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
        </div>
      </template>

      <div class="layout">
        <div class="layout__main">
          <CTable :columns="cols" :rows="rows" row-key="id">
            <template #col-timing="{ value }">
              <CStatusPill status="primary" dot>{{ value }}</CStatusPill>
            </template>
            <template #col-prob="{ value }">
              <CProgressBar :value="value" :color="probColor(value)" :height="8" :label="`${value}%`" />
            </template>
            <template #col-ops>
              <CButton variant="text" size="sm">建跟进</CButton>
              <CButton variant="text" size="sm">推送</CButton>
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
              <li v-for="f in factors" :key="f.rank" class="factor-item">
                <div class="factor-rank">{{ f.rank }}</div>
                <div class="factor-body">
                  <div class="factor-title">{{ f.title }}</div>
                  <div class="factor-desc">{{ f.desc }}</div>
                  <CProgressBar :value="Math.round(f.weight * 100)" color="var(--c-purple)" :height="4" :label="`权重 ${(f.weight * 100).toFixed(0)}%`" />
                </div>
              </li>
            </ol>
            <div class="factor-note">
              <CIcon name="shield" :size="12" />
              <span>预测结果仅供参考，实际触达需符合 A1-17 隐私规范。</span>
            </div>
          </div>
        </aside>
      </div>

      <template #footer>
        <CButton variant="primary" @click="batchCreate">
          <CIcon name="plus" :size="16" />批量建跟进任务
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
</style>
