<script setup lang="ts">
/* A1-02 客户画像引擎 /ai/profile — 真实客户域上下文 + profile invoke 全治理链（B47 卡1 去 mock） */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CTable from '@/components/CTable.vue'
import CSegmented from '@/components/CSegmented.vue'
import CProgressBar from '@/components/CProgressBar.vue'
import CInput from '@/components/CInput.vue'
import { useToast } from '@/composables/useToast'
import { errMsg } from '@/stores/m5Coupon'
import { fmtDateTime } from '@/utils/datetime'
import {
  searchProfileCandidates, generateProfile, getLatestProfile, getProfileStats,
  getProfileWeights, getProfileReview, applyProfileToSegment,
  type ProfileView, type ProfileCandidate, type ProfileStats,
  type ProfileWeightModel, type ProfileReview,
} from '@/api/ai'

const toast = useToast()

function fmtNum(n: number): string {
  return n.toLocaleString('zh-CN')
}

// ---------- KPI（全部真实计数，趋势用今日/本周/已应用真实数诚实表达，不造环比） ----------
const stats = ref<ProfileStats>({
  coveredCustomers: 0, tagTotal: 0, totalInvokes: 0,
  weekInvokes: 0, appliedSegments: 0, todayInvokes: 0,
})
const kpis = computed(() => [
  { label: '覆盖客户', icon: 'customer', value: fmtNum(stats.value.coveredCustomers), tone: 'purple' as const, trend: '客户档案总数', trendUp: true },
  { label: '标签数', icon: 'customer', value: fmtNum(stats.value.tagTotal), tone: 'brand' as const, trend: `画像累计调用 ${fmtNum(stats.value.totalInvokes)} 次`, trendUp: true },
  { label: '画像调用', icon: 'customer', value: fmtNum(stats.value.totalInvokes), tone: 'teal' as const, trend: `本周 ${fmtNum(stats.value.weekInvokes)} 次 · 今日 ${fmtNum(stats.value.todayInvokes)} 次`, trendUp: true },
  { label: '应用分群', icon: 'customer', value: fmtNum(stats.value.appliedSegments), tone: 'orange' as const, trend: '已登记应用画像数', trendUp: true },
])

async function loadStats() {
  try {
    stats.value = await getProfileStats()
  } catch (e) {
    toast.error('画像统计加载失败：' + errMsg(e))
  }
}

// ---------- Tab（权重随页加载、回看懒加载，无调用周展示空态不造数） ----------
const tab = ref('output')
const tabOptions = [
  { label: '画像输出', value: 'output' },
  { label: '特征权重', value: 'weight' },
  { label: '效果回看', value: 'review' },
]

const weightModel = ref<ProfileWeightModel | null>(null)
const review = ref<ProfileReview | null>(null)
const reviewLoading = ref(false)

const weightCols = [
  { key: 'feature', label: '特征名' },
  { key: 'weight', label: '权重', width: '120px', align: 'right' as const },
  { key: 'direction', label: '贡献方向', width: '110px' },
  { key: 'shap', label: 'SHAP 值贡献', width: '280px' },
]
const weightRows = computed(() =>
  (weightModel.value?.rows ?? []).map((w, i) => ({ id: i + 1, ...w })))
const maxBar = 100

async function loadWeights() {
  if (weightModel.value) return
  try {
    weightModel.value = await getProfileWeights()
  } catch (e) {
    toast.error('特征权重加载失败：' + errMsg(e))
  }
}

async function loadReview() {
  if (reviewLoading.value) return
  reviewLoading.value = true
  try {
    review.value = await getProfileReview()
  } catch (e) {
    toast.error('效果回看加载失败：' + errMsg(e))
  } finally {
    reviewLoading.value = false
  }
}

function onTabChange(v: string) {
  tab.value = v
  if (v === 'weight') loadWeights()
  if (v === 'review') loadReview()
}

// ---------- 画像输出：搜索候选 → 回显最近画像 / 空态引导 → 真实生成 → 登记应用 ----------
const searchKey = ref('')
const searching = ref(false)
const candidates = ref<ProfileCandidate[]>([])
const selectedId = ref('')
const profile = ref<ProfileView | null>(null)
const profileLoading = ref(false)
const generating = ref(false)
const applying = ref(false)

async function doSearch() {
  const kw = searchKey.value.trim()
  if (!kw) {
    toast.warning('请输入客户姓名 / 手机号 / 客户编号')
    return
  }
  searching.value = true
  try {
    candidates.value = await searchProfileCandidates(kw)
    if (candidates.value.length === 0) {
      toast.info('未搜索到匹配客户，请调整关键词后重试')
      selectedId.value = ''
      profile.value = null
    }
  } catch (e) {
    toast.error('客户搜索失败：' + errMsg(e))
  } finally {
    searching.value = false
  }
}

async function selectCandidate(c: ProfileCandidate) {
  selectedId.value = c.customerId
  searchKey.value = c.name
  candidates.value = []
  profile.value = null
  if (!c.hasProfile || c.profileId == null) return
  profileLoading.value = true
  try {
    profile.value = await getLatestProfile(c.customerId)
  } catch (e) {
    toast.error('最近画像加载失败：' + errMsg(e))
  } finally {
    profileLoading.value = false
  }
}

async function doGenerate() {
  const cid = selectedId.value
  const kw = searchKey.value.trim()
  if (!cid && !kw) {
    toast.warning('请先搜索并选择客户，或直接输入关键词后生成')
    return
  }
  generating.value = true
  try {
    profile.value = await generateProfile(cid ? { customerId: cid } : { keyword: kw })
    selectedId.value = profile.value.customerId
    searchKey.value = profile.value.customerName
    toast.success('客户画像已生成（' + (profile.value.modelCode ?? 'AI') + '）')
    loadStats()
  } catch (e) {
    toast.error('画像生成失败：' + errMsg(e))
  } finally {
    generating.value = false
  }
}

async function applyToSegment() {
  if (!profile.value) return
  if (profile.value.appliedToSegment) {
    toast.info('该画像已登记应用到分群，无需重复操作')
    return
  }
  applying.value = true
  try {
    const res = await applyProfileToSegment(profile.value.profileId)
    if (profile.value) profile.value = { ...profile.value, appliedToSegment: res.appliedToSegment }
    toast.success('画像已登记应用到分群（跨域标签工厂/分群推送见远期规划）')
    loadStats()
  } catch (e) {
    toast.error('应用到分群失败：' + errMsg(e))
  } finally {
    applying.value = false
  }
}

const profileInitial = computed(() => profile.value?.customerName.charAt(0) ?? '')
const profileUpdated = computed(() => fmtDateTime(profile.value?.createdAt))
const reviewWeeks = computed(() => review.value?.weeks ?? [])
const reviewAvgText = computed(() =>
  review.value?.avgSuccessRate == null ? '—' : `${review.value.avgSuccessRate}%`)

onMounted(loadStats)
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
          <CSegmented :model-value="tab" :options="tabOptions" size="sm" @update:model-value="onTabChange" />
          <div v-if="tab === 'output'" class="profile-search">
            <div class="profile-search__box">
              <CInput
                v-model="searchKey"
                placeholder="搜索客户姓名 / 手机号 / 客户编号"
                @keyup.enter="doSearch"
              />
              <div v-if="candidates.length" class="candidate-pop">
                <button
                  v-for="c in candidates"
                  :key="c.customerId"
                  type="button"
                  class="candidate-item"
                  @click="selectCandidate(c)"
                >
                  <span class="candidate-item__name">{{ c.name }}</span>
                  <span class="candidate-item__phone">{{ c.phone }}</span>
                  <CStatusPill :status="c.hasProfile ? 'success' : 'default'" dot>
                    {{ c.level }}{{ c.hasProfile ? ' · 已有画像' : ' · 未生成' }}
                  </CStatusPill>
                </button>
              </div>
            </div>
            <CButton variant="secondary" size="sm" :disabled="searching" @click="doSearch">
              <CIcon name="search" :size="14" />{{ searching ? '搜索中' : '搜索' }}
            </CButton>
            <CButton variant="primary" size="sm" :disabled="generating" @click="doGenerate">
              <CIcon name="refresh" :size="14" />{{ generating ? '生成中…' : '生成画像' }}
            </CButton>
          </div>
        </div>
      </template>

      <!-- 画像输出 -->
      <div v-if="tab === 'output'" class="output">
        <div v-if="generating" class="profile-state">
          <CIcon name="loading" :size="28" />
          <p>大模型画像生成中，通常需数秒至数十秒，请勿离开本页…</p>
        </div>
        <div v-else-if="profileLoading" class="profile-state">
          <CIcon name="loading" :size="28" />
          <p>正在加载该客户最近一次画像…</p>
        </div>
        <template v-else-if="profile">
          <div class="profile-card">
            <div class="profile-card__head">
              <div class="avatar">{{ profileInitial }}</div>
              <div class="meta">
                <div class="name">
                  {{ profile.customerName }}
                  <CStatusPill status="primary" dot>{{ profile.level }}</CStatusPill>
                  <CStatusPill v-if="profile.modelCode" status="default">{{ profile.modelCode }}</CStatusPill>
                </div>
                <div class="phone">{{ profile.phone }}</div>
              </div>
              <div class="updated">最近更新：{{ profileUpdated }}</div>
            </div>

            <div class="profile-card__score">
              <div class="score-label">客户价值分</div>
              <CProgressBar :value="profile.valueScore" color="var(--c-purple)" :height="10" :label="`${profile.valueScore} / 100`" />
            </div>

            <div class="profile-card__section">
              <div class="section-title">所属分群</div>
              <div class="group-list">
                <CStatusPill v-for="g in profile.groups" :key="g" status="info">{{ g }}</CStatusPill>
                <span v-if="!profile.groups.length" class="empty-inline">暂无分群</span>
              </div>
            </div>

            <div class="profile-card__section">
              <div class="section-title">画像标签</div>
              <div class="tag-list">
                <CStatusPill v-for="t in profile.tags" :key="t.label" :status="(t.status as any)">{{ t.label }}</CStatusPill>
                <span v-if="!profile.tags.length" class="empty-inline">暂无标签</span>
              </div>
            </div>
          </div>

          <div class="privacy-tip">
            <CIcon name="shield" :size="14" />
            <span>隐私提示：画像数据经 A1-17 脱敏处理，仅输出群体级标签，不暴露个体敏感信息。</span>
          </div>
        </template>
        <div v-else class="profile-state">
          <CIcon name="profile" :size="28" />
          <p>在上方搜索并选择客户以查看最近画像，或直接输入关键词后点击「生成画像」。</p>
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
        <p class="hint">{{ review?.statusNote ?? '近 4 周画像调用成功率回流（业务标签准确率回流待效果评估体系建设）。' }}</p>
        <div v-if="reviewLoading" class="profile-state">
          <CIcon name="loading" :size="28" />
          <p>正在聚合近 4 周回看数据…</p>
        </div>
        <template v-else>
          <div class="trend-chart">
            <div v-for="w in reviewWeeks" :key="w.week" class="trend-col">
              <div class="trend-col__bars">
                <div
                  v-if="w.successRate != null"
                  class="trend-bar"
                  :style="{ height: (w.successRate / maxBar * 100) + '%' }"
                />
                <span v-else class="trend-empty">—</span>
              </div>
              <div class="trend-col__label">{{ w.week }}</div>
              <div class="trend-col__val">{{ w.successRate == null ? '无调用' : w.successRate.toFixed(1) + '%' }}</div>
            </div>
          </div>
          <div class="review-summary">
            <span>近 4 周平均成功率<b>{{ reviewAvgText }}</b></span>
            <CStatusPill :status="review && review.avgSuccessRate != null ? 'success' : 'default'" dot>
              {{ review && review.avgSuccessRate != null ? '调用正常' : '暂无调用' }}
            </CStatusPill>
          </div>
        </template>
      </div>

      <template #footer>
        <CButton
          variant="primary"
          :disabled="tab !== 'output' || !profile || applying || profile.appliedToSegment"
          @click="applyToSegment"
        >
          <CIcon name="check-square" :size="16" />
          {{ applying ? '登记中…' : profile?.appliedToSegment ? '已应用到分群' : '应用到分群' }}
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
.profile-search { display: flex; align-items: flex-start; gap: var(--s-sm); flex-shrink: 0; }
.profile-search__box { position: relative; }
.profile-search :deep(.cinput) { width: 240px; }
.profile-search .cbtn { flex-shrink: 0; white-space: nowrap; }

/* 候选下拉（B47 卡1 新增） */
.candidate-pop {
  position: absolute;
  top: calc(100% + 4px);
  left: 0;
  right: 0;
  z-index: 20;
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--r-md);
  box-shadow: var(--shadow-card);
  padding: var(--s-xxs);
  display: flex;
  flex-direction: column;
  max-height: 264px;
  overflow-y: auto;
}
.candidate-item {
  display: flex;
  align-items: center;
  gap: var(--s-sm);
  width: 100%;
  border: none;
  background: transparent;
  border-radius: var(--r-sm);
  padding: var(--s-xs) var(--s-sm);
  cursor: pointer;
  text-align: left;
}
.candidate-item:hover { background: var(--c-brand-soft); }
.candidate-item__name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); min-width: 56px; }
.candidate-item__phone { font-size: var(--t-xs); color: var(--c-text-3); flex: 1; }

/* 加载 / 空态（B47 卡1 新增） */
.profile-state {
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
.profile-state p { margin: 0; max-width: 360px; }
.empty-inline { font-size: var(--t-xs); color: var(--c-text-4); }
.trend-empty { font-size: var(--t-sm); color: var(--c-text-4); }
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
