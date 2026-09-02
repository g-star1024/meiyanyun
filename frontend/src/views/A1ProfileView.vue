<script setup lang="ts">
/* ============================================================
 * A1-02 客户画像引擎 /ai/profile
 * KPI + 画像输出 / 特征权重 / 效果回看 三视图
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
import CInput from '@/components/CInput.vue'

// ---------- KPI ----------
const kpis = [
  { label: '覆盖客户', icon: 'customer', value: '12,480', tone: 'purple' as const, trend: '较上月 +8.2%', trendUp: true },
  { label: '标签数', icon: 'customer', value: '86', tone: 'brand' as const, trend: '新增 6 个', trendUp: true },
  { label: '画像调用', icon: 'customer', value: '3,240', tone: 'teal' as const, trend: '本周 +12.4%', trendUp: true },
  { label: '应用分群', icon: 'customer', value: '18', tone: 'orange' as const, trend: '活跃 14 个', trendUp: true },
]

// ---------- Tab ----------
const tab = ref('output')
const tabOptions = [
  { label: '画像输出', value: 'output' },
  { label: '特征权重', value: 'weight' },
  { label: '效果回看', value: 'review' },
]

// ---------- 画像输出 ----------
const searchKey = ref('张敏')
const profile = ref({
  name: '张敏',
  phone: '138****1234',
  valueScore: 85,
  level: '钻石会员',
  groups: ['高价值沉睡', '医美意向', '抗衰项目偏好'],
  tags: [
    { label: '高消费', status: 'primary' as const },
    { label: '医美偏好', status: 'success' as const },
    { label: '价格敏感', status: 'warning' as const },
    { label: '夜间活跃', status: 'info' as const },
    { label: '老带新潜力', status: 'default' as const },
  ],
  updated: '2026-08-26 09:12',
})

// ---------- 特征权重 ----------
const weightRows = [
  { id: 1, feature: '近 90 天消费金额', weight: 0.28, direction: '正向', shap: 0.92 },
  { id: 2, feature: '到店频次', weight: 0.21, direction: '正向', shap: 0.78 },
  { id: 3, feature: '最近一次到店间隔', weight: 0.16, direction: '负向', shap: -0.65 },
  { id: 4, feature: '项目品类宽度', weight: 0.12, direction: '正向', shap: 0.55 },
  { id: 5, feature: '客诉次数', weight: 0.08, direction: '负向', shap: -0.42 },
  { id: 6, feature: '会员卡余额', weight: 0.07, direction: '正向', shap: 0.38 },
  { id: 7, feature: '优惠券核销率', weight: 0.05, direction: '正向', shap: 0.26 },
  { id: 8, feature: '转介绍次数', weight: 0.03, direction: '正向', shap: 0.18 },
]
const weightCols = [
  { key: 'feature', label: '特征名' },
  { key: 'weight', label: '权重', width: '120px', align: 'right' as const },
  { key: 'direction', label: '贡献方向', width: '110px' },
  { key: 'shap', label: 'SHAP 值贡献', width: '280px' },
]

// ---------- 效果回看（近 4 周画像准确率） ----------
const accuracy = [
  { week: 'W1', rate: 0.82 },
  { week: 'W2', rate: 0.85 },
  { week: 'W3', rate: 0.83 },
  { week: 'W4', rate: 0.88 },
]
const maxBar = 100

function applyToSegment() {
  alert('画像标签已同步至 M3-06 标签工厂 / M3-14 分群')
}
</script>

<template>
  <div class="a1-profile">
    <!-- KPI 行 -->
    <div class="a1-profile__kpis">
      <CKpi
        v-for="k in kpis"
        :key="k.label"
        :label="k.label"
        :value="k.value"
        :tone="k.tone"
        :trend="k.trend"
        :trend-up="k.trendUp" :icon="k.icon" />
    </div>

    <CCard>
      <template #header>
        <h3 class="card-title"><CIcon name="profile" :size="16" /> 客户画像引擎</h3>
        <div class="profile-tools">
          <CSegmented v-model="tab" :options="tabOptions" size="sm" />
          <div v-if="tab === 'output'" class="profile-search">
            <CInput v-model="searchKey" placeholder="搜索客户姓名 / 手机号" />
            <CButton variant="secondary" size="sm"><CIcon name="search" :size="14" />搜索</CButton>
          </div>
        </div>
      </template>

      <!-- 画像输出 -->
      <div v-if="tab === 'output'" class="output">
        <div class="profile-card">
          <div class="profile-card__head">
            <div class="avatar">{{ profile.name.charAt(0) }}</div>
            <div class="meta">
              <div class="name">{{ profile.name }}<CStatusPill status="primary" dot>{{ profile.level }}</CStatusPill></div>
              <div class="phone">{{ profile.phone }}</div>
            </div>
            <div class="updated">最近更新：{{ profile.updated }}</div>
          </div>

          <div class="profile-card__score">
            <div class="score-label">客户价值分</div>
            <CProgressBar :value="profile.valueScore" color="var(--c-purple)" :height="10" :label="`${profile.valueScore} / 100`" />
          </div>

          <div class="profile-card__section">
            <div class="section-title">所属分群</div>
            <div class="group-list">
              <CStatusPill v-for="g in profile.groups" :key="g" status="info">{{ g }}</CStatusPill>
            </div>
          </div>

          <div class="profile-card__section">
            <div class="section-title">画像标签</div>
            <div class="tag-list">
              <CStatusPill v-for="t in profile.tags" :key="t.label" :status="t.status">{{ t.label }}</CStatusPill>
            </div>
          </div>
        </div>

        <div class="privacy-tip">
          <CIcon name="shield" :size="14" />
          <span>隐私提示：画像数据经 A1-17 脱敏处理，仅输出群体级标签，不暴露个体敏感信息。</span>
        </div>
      </div>

      <!-- 特征权重 -->
      <div v-else-if="tab === 'weight'" class="weight">
        <p class="hint">下表展示模型对客户价值分贡献最大的 Top 8 特征，条形长度为 |SHAP| 值。</p>
        <CTable :columns="weightCols" :rows="weightRows" row-key="id">
          <template #col-weight="{ value }">
            <span class="weight-num">{{ (value * 100).toFixed(0) }}%</span>
          </template>
          <template #col-direction="{ value }">
            <CStatusPill :status="value === '正向' ? 'success' : 'danger'" dot>{{ value }}</CStatusPill>
          </template>
          <template #col-shap="{ row }">
            <div class="shap-bar">
              <div class="shap-bar__track">
                <div
                  class="shap-bar__fill"
                  :class="row.shap >= 0 ? 'is-pos' : 'is-neg'"
                  :style="{ width: Math.abs(row.shap) * 100 + '%' }"
                />
              </div>
              <span class="shap-val" :class="row.shap >= 0 ? 'is-pos' : 'is-neg'">{{ row.shap >= 0 ? '+' : '' }}{{ row.shap.toFixed(2) }}</span>
            </div>
          </template>
        </CTable>
      </div>

      <!-- 效果回看 -->
      <div v-else class="review">
        <p class="hint">近 4 周画像标签在分群/推荐场景的准确率回流。</p>
        <div class="trend-chart">
          <div v-for="w in accuracy" :key="w.week" class="trend-col">
            <div class="trend-col__bars">
              <div class="trend-bar" :style="{ height: (w.rate * 100 / maxBar * 100) + '%' }" />
            </div>
            <div class="trend-col__label">{{ w.week }}</div>
            <div class="trend-col__val">{{ (w.rate * 100).toFixed(0) }}%</div>
          </div>
        </div>
        <div class="review-summary">
          <span>4 周平均准确率 <b>84.5%</b></span>
          <CStatusPill status="success" dot>模型稳定</CStatusPill>
        </div>
      </div>

      <template #footer>
        <CButton variant="primary" @click="applyToSegment">
          <CIcon name="check-square" :size="16" />应用到分群
        </CButton>
      </template>
    </CCard>
  </div>
</template>

<style scoped>
.a1-profile {
  display: flex;
  flex-direction: column;
  gap: var(--s-md);
}
.a1-profile__kpis {
  display: flex;
  gap: var(--s-md);
}
.card-title {
  display: inline-flex;
  align-items: center;
  gap: var(--s-xs);
  font-size: var(--t-md);
  font-weight: 700;
}
.profile-tools { display: flex; align-items: center; gap: var(--s-sm); margin-left: auto; flex-wrap: nowrap; }
.profile-search { display: flex; align-items: center; gap: var(--s-sm); flex-shrink: 0; }
.profile-search :deep(.cinput) { width: 220px; }
.profile-search .cbtn { flex-shrink: 0; white-space: nowrap; }
.profile-card {
  border: 1px solid var(--c-border-light);
  border-radius: var(--r-lg);
  padding: var(--s-lg);
  background: var(--c-bg-page);
  display: flex;
  flex-direction: column;
  gap: var(--s-md);
}
.profile-card__head {
  display: flex;
  align-items: center;
  gap: var(--s-md);
}
.avatar {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  background: var(--c-purple);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: var(--t-lg);
  font-weight: 700;
}
.meta { flex: 1; display: flex; flex-direction: column; gap: var(--s-xxs); }
.name {
  display: flex;
  align-items: center;
  gap: var(--s-sm);
  font-size: var(--t-md);
  font-weight: 700;
  color: var(--c-text);
}
.phone { font-size: var(--t-sm); color: var(--c-text-3); }
.updated { font-size: var(--t-xs); color: var(--c-text-3); }

.profile-card__score { display: flex; flex-direction: column; gap: var(--s-xs); }
.score-label { font-size: var(--t-sm); color: var(--c-text-2); }

.section-title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text-2); margin-bottom: var(--s-xs); }
.group-list, .tag-list { display: flex; flex-wrap: wrap; gap: var(--s-xs); }

.privacy-tip {
  margin-top: var(--s-md);
  display: flex;
  align-items: center;
  gap: var(--s-xs);
  padding: var(--s-sm) var(--s-md);
  background: var(--c-info-bg);
  border-radius: var(--r-md);
  color: var(--c-info-fg);
  font-size: var(--t-xs);
}

/* 特征权重 */
.hint { margin: 0 0 var(--s-md); font-size: var(--t-sm); color: var(--c-text-3); }
.weight-num { font-weight: 600; color: var(--c-purple); font-variant-numeric: tabular-nums; }
.shap-bar { display: flex; align-items: center; gap: var(--s-sm); }
.shap-bar__track {
  flex: 1;
  height: 8px;
  background: var(--c-chart-track);
  border-radius: 999px;
  overflow: hidden;
  position: relative;
}
.shap-bar__fill { height: 100%; border-radius: 999px; transition: width .3s; }
.shap-bar__fill.is-pos { background: var(--c-teal); }
.shap-bar__fill.is-neg { background: var(--c-danger-fg); }
.shap-val { font-size: var(--t-xs); font-variant-numeric: tabular-nums; min-width: 40px; text-align: right; }
.shap-val.is-pos { color: var(--c-teal-dark); }
.shap-val.is-neg { color: var(--c-danger-fg); }

/* 效果回看 */
.trend-chart {
  display: flex;
  align-items: flex-end;
  justify-content: space-around;
  height: 240px;
  padding: var(--s-md) var(--s-lg) 0;
  border-bottom: 1px dashed var(--c-border);
  gap: var(--s-md);
}
.trend-col {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--s-xs);
  height: 100%;
}
.trend-col__bars {
  flex: 1;
  width: 48px;
  display: flex;
  align-items: flex-end;
  justify-content: center;
}
.trend-bar {
  width: 100%;
  background: linear-gradient(180deg, var(--c-purple) 0%, var(--c-brand-secondary) 100%);
  border-radius: var(--r-sm) var(--r-sm) 0 0;
  min-height: 20px;
}
.trend-col__label { font-size: var(--t-xs); color: var(--c-text-3); }
.trend-col__val { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.review-summary {
  margin-top: var(--s-md);
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: var(--t-sm);
  color: var(--c-text-2);
}
.review-summary b { color: var(--c-purple); font-size: var(--t-md); margin: 0 var(--s-xxs); }
</style>
