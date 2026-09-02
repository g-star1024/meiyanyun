<script setup lang="ts">
/* ============================================================
 * 满意度/NPS /m3-nps（M3-12）
 * 4 KPI + NPS 大数字环形 + 三色分布条 + 评价列表（tab 筛选）+ 详情。
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CDonutChart from '@/components/CDonutChart.vue'
import { useNpsStore, type NpsCategory, type NpsRecord } from '@/stores/nps'

const store = useNpsStore()
onMounted(() => store.seed())

const selectedId = ref<string | null>(null)
const selected = computed<NpsRecord | null>(() => {
  if (selectedId.value) return store.get(selectedId.value) ?? null
  return store.filtered[0] ?? null
})

const kpis = computed(() => [
  { label: '本期 NPS', icon: 'trend-up', value: String(store.npsScore), tone: (store.npsScore >= 50 ? 'success' : store.npsScore >= 30 ? 'brand' : 'danger') as 'success' | 'brand' | 'danger' },
  { label: '推荐者占比', icon: 'marketing', value: `${store.promoterPct}%`, tone: 'success' as const },
  { label: '贬损者占比', icon: 'trend-down', value: `${store.detractorPct}%`, tone: 'danger' as const },
  { label: '回收率', icon: 'trend-up', value: `${store.responseRate}%`, tone: 'teal' as const },
])

const tabs: Array<{ key: NpsCategory | 'ALL' | 'PENDING'; label: string }> = [
  { key: 'ALL', label: `全部 ${store.total}` },
  { key: 'PROMOTER', label: `推荐者 ${store.promoters.length}` },
  { key: 'PASSIVE', label: `被动者 ${store.passives.length}` },
  { key: 'DETRACTOR', label: `贬损者 ${store.detractors.length}` },
  { key: 'PENDING', label: `待跟进 ${store.pending.length}` },
]

const donutData = computed(() => [
  { label: '推荐者', value: store.promoters.length, color: 'var(--c-success-fg)' },
  { label: '被动者', value: store.passives.length, color: 'var(--c-warning-fg)' },
  { label: '贬损者', value: store.detractors.length, color: 'var(--c-danger-fg)' },
])

function scoreTone(score: number) {
  if (score >= 9) return 'var(--c-success-fg)'
  if (score >= 7) return 'var(--c-warning-fg)'
  return 'var(--c-danger-fg)'
}
function fmtDate(iso: string) {
  const d = new Date(iso)
  return `${d.getMonth() + 1}-${String(d.getDate()).padStart(2, '0')}`
}

// 跟进弹层
const showFollow = ref(false)
const followNote = ref('')
function openFollow() {
  followNote.value = selected.value?.followNote || ''
  showFollow.value = true
}
function submitFollow() {
  if (!selected.value) return
  store.markFollowed(selected.value.id, followNote.value || '已跟进')
  showFollow.value = false
}
function doCreateTask() {
  if (selected.value) store.createFollowTask(selected.value.id)
}

// 趋势柱最大
const maxTrend = computed(() => Math.max(...store.trends.map((t) => t.total), 1))
</script>

<template>
  <div class="nps">
    <div class="nps__head">
      <div class="nps__kpis">
        <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
      </div>
    </div>

    <!-- NPS 总览 -->
    <div class="nps-overview">
      <CCard class="ov-score" padding="lg">
        <div class="ov-score__left">
          <CDonutChart
            :data="donutData"
            :size="160"
            :thickness="18"
            center-label="NPS"
            :center-value="String(store.npsScore)"
            :show-legend="false"
          />
        </div>
        <div class="ov-score__right">
          <div class="ov-title">本期净推荐值</div>
          <div class="ov-nps" :style="{ color: scoreTone(store.npsScore > 0 ? 9 : store.npsScore < 0 ? 3 : 7) }">
            {{ store.npsScore > 0 ? '+' : '' }}{{ store.npsScore }}
          </div>
          <div class="ov-sub">推荐者 {{ store.promoterPct }}% − 贬损者 {{ store.detractorPct }}%</div>
          <div class="ov-dist">
            <div v-for="d in store.distribution" :key="d.key" class="ov-dist__row">
              <span class="ov-dist__label">
                <span class="dot" :style="{ background: d.color }"></span>{{ d.label }}
              </span>
              <div class="ov-dist__track">
                <div class="ov-dist__fill" :style="{ width: d.pct + '%', background: d.color }"></div>
              </div>
              <span class="ov-dist__val">{{ d.count }}人 · {{ d.pct }}%</span>
            </div>
          </div>
        </div>
      </CCard>

      <CCard class="ov-trend" padding="lg">
        <template #header>
          <h3 class="card-title">近 6 周 NPS 趋势</h3>
        </template>
        <div class="trend">
          <div v-for="t in store.trends" :key="t.period" class="trend__col">
            <div class="trend__bar-wrap">
              <div class="trend__bar trend__bar--d" :style="{ height: (t.detractors / maxTrend) * 100 + '%' }"></div>
              <div class="trend__bar trend__bar--p" :style="{ height: (t.passives / maxTrend) * 100 + '%' }"></div>
              <div class="trend__bar trend__bar--pr" :style="{ height: (t.promoters / maxTrend) * 100 + '%' }"></div>
            </div>
            <div class="trend__nps" :class="{ 'is-neg': t.nps < 0 }">{{ t.nps }}</div>
            <div class="trend__lbl">{{ t.period }}</div>
          </div>
        </div>
        <div class="trend-legend">
          <span><i class="lg-dot lg-dot--pr"></i>推荐者</span>
          <span><i class="lg-dot lg-dot--p"></i>被动者</span>
          <span><i class="lg-dot lg-dot--d"></i>贬损者</span>
        </div>
      </CCard>
    </div>

    <!-- 列表 + 详情 -->
    <div class="nps__body">
      <CCard class="nps__list" padding="none">
        <div class="tabs">
          <button
            v-for="t in tabs" :key="t.key"
            class="tab" :class="{ 'tab--active': store.filterCategory === t.key }"
            @click="store.filterCategory = t.key"
          >{{ t.label }}</button>
        </div>
        <div class="list">
          <div v-if="store.filtered.length === 0" class="empty">
            <CIcon :name="('message' as any)" :size="28" class="empty__icon" />
            <div>暂无评价</div>
          </div>
          <button
            v-for="r in store.filtered" :key="r.id"
            class="row"
            :class="{
              'row--active': selected?.id === r.id,
              'row--bad': r.category === 'DETRACTOR',
            }"
            @click="selectedId = r.id"
          >
            <div class="row__score" :style="{ color: scoreTone(r.score) }">{{ r.score }}</div>
            <div class="row__main">
              <div class="row__top">
                <span class="row__name">{{ r.customer }}</span>
                <CStatusPill v-if="r.followStatus === 'PENDING'" status="warning">待跟进</CStatusPill>
                <CStatusPill v-else status="success">已跟进</CStatusPill>
              </div>
              <div class="row__svc">{{ r.service }}</div>
              <div class="row__comment">{{ r.comment }}</div>
              <div class="row__tags">
                <span v-for="tag in r.tags.slice(0, 3)" :key="tag" class="row__tag">{{ tag }}</span>
              </div>
            </div>
          </button>
        </div>
      </CCard>

      <CCard v-if="selected" class="nps__detail" padding="lg">
        <template #header>
          <h3 class="card-title">{{ selected.customer }} 的评价</h3>
          <CStatusPill v-if="selected.followStatus === 'PENDING'" status="warning">待跟进</CStatusPill>
          <CStatusPill v-else status="success">已跟进</CStatusPill>
        </template>

        <div class="detail__hero">
          <div class="detail__score" :style="{ color: scoreTone(selected.score) }">{{ selected.score }}</div>
          <div class="detail__score-info">
            <div class="detail__cat">
              <span class="dot" :style="{ background: scoreTone(selected.score) }"></span>
              {{ store.CATEGORY_LABEL[selected.category] }}
            </div>
            <div class="detail__svc">{{ selected.service }}</div>
            <div class="detail__date">{{ fmtDate(selected.createdAt) }}</div>
          </div>
        </div>

        <div class="detail__tags">
          <span v-for="tag in selected.tags" :key="tag" class="tag">{{ tag }}</span>
        </div>

        <div class="detail__comment">
          <div class="sec-title">评价内容</div>
          <p>{{ selected.comment }}</p>
        </div>

        <div v-if="selected.followNote" class="detail__note">
          <div class="sec-title">跟进记录</div>
          <p>{{ selected.followNote }}</p>
        </div>

        <div class="detail__ops">
          <CButton variant="ghost" v-perm.disable="'nps:edit'" @click="doCreateTask">
            <CIcon name="plus" :size="16" />创建跟进任务
          </CButton>
          <CButton
            v-if="selected.followStatus === 'PENDING'"
            variant="primary"
            v-perm.disable="'nps:edit'"
            @click="openFollow"
          >
            <CIcon name="check" :size="16" />标记已跟进
          </CButton>
          <span v-else class="ops__done">
            <CIcon name="check" :size="16" />已跟进
          </span>
        </div>
      </CCard>

      <CCard v-else class="nps__detail" padding="lg">
        <div class="detail-empty">
          <CIcon :name="('message' as any)" :size="40" class="detail-empty__icon" />
          <p>请选择一条评价</p>
        </div>
      </CCard>
    </div>

    <!-- 跟进弹层 -->
    <div v-if="showFollow" class="modal-mask" @click.self="showFollow = false">
      <CCard class="modal" title="标记已跟进" padding="lg">
        <div class="form__row">
          <label class="form__label">跟进备注</label>
          <textarea v-model="followNote" class="textarea" rows="3" placeholder="如：已电话回访，客户接受重做安排"></textarea>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showFollow = false">取消</CButton>
          <CButton variant="primary" @click="submitFollow">确认</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.nps { display: flex; flex-direction: column; gap: var(--s-lg); }
.nps__head { display: flex; justify-content: space-between; align-items: center; gap: var(--s-md); flex-wrap: wrap; }
.nps__kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); flex: 1; min-width: 480px; }

.card-title { font-size: var(--t-md); font-weight: 700; margin: 0; }

/* 总览 */
.nps-overview { display: grid; grid-template-columns: 420px 1fr; gap: var(--s-lg); }
.ov-score { display: flex; gap: var(--s-lg); align-items: center; }
.ov-score__left { flex-shrink: 0; }
.ov-title { font-size: var(--t-sm); color: var(--c-text-3); margin-bottom: var(--s-xs); }
.ov-nps { font-size: 48px; font-weight: 800; line-height: 1; font-variant-numeric: tabular-nums; }
.ov-sub { font-size: var(--t-xs); color: var(--c-text-3); margin: var(--s-xs) 0 var(--s-md); }
.ov-dist { display: flex; flex-direction: column; gap: var(--s-sm); min-width: 200px; }
.ov-dist__row { display: grid; grid-template-columns: 56px 1fr 88px; align-items: center; gap: var(--s-sm); font-size: var(--t-xs); }
.ov-dist__label { display: inline-flex; align-items: center; gap: 6px; color: var(--c-text-2); }
.dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; flex-shrink: 0; }
.ov-dist__track { height: 8px; background: var(--c-chart-track); border-radius: 999px; overflow: hidden; }
.ov-dist__fill { height: 100%; border-radius: 999px; }
.ov-dist__val { color: var(--c-text-2); text-align: right; font-variant-numeric: tabular-nums; }

/* 趋势 */
.trend { display: flex; justify-content: space-between; gap: var(--s-sm); height: 160px; padding: var(--s-sm) 0; align-items: flex-end; }
.trend__col { flex: 1; display: flex; flex-direction: column; align-items: center; gap: var(--s-xs); height: 100%; justify-content: flex-end; }
.trend__bar-wrap { width: 36px; display: flex; flex-direction: column; justify-content: flex-end; height: 120px; gap: 1px; }
.trend__bar { width: 100%; border-radius: 2px 2px 0 0; min-height: 2px; }
.trend__bar--pr { background: var(--c-success-fg); }
.trend__bar--p { background: var(--c-warning-fg); }
.trend__bar--d { background: var(--c-danger-fg); }
.trend__nps { font-size: var(--t-xs); font-weight: 700; color: var(--c-text); font-variant-numeric: tabular-nums; }
.trend__nps.is-neg { color: var(--c-danger-fg); }
.trend__lbl { font-size: var(--t-xs); color: var(--c-text-3); }
.trend-legend { display: flex; gap: var(--s-md); justify-content: center; padding-top: var(--s-md); border-top: 1px solid var(--c-border-light); font-size: var(--t-xs); color: var(--c-text-3); }
.trend-legend span { display: inline-flex; align-items: center; gap: 6px; }
.lg-dot { width: 8px; height: 8px; border-radius: 2px; display: inline-block; }
.lg-dot--pr { background: var(--c-success-fg); }
.lg-dot--p { background: var(--c-warning-fg); }
.lg-dot--d { background: var(--c-danger-fg); }

/* 双栏 */
.nps__body { display: grid; grid-template-columns: 380px 1fr; gap: var(--s-lg); align-items: start; }
.nps__list { min-width: 0; }
.tabs { display: flex; gap: 0; padding: 0 var(--s-sm); border-bottom: 1px solid var(--c-border-light); overflow-x: auto; }
.tab { padding: var(--s-md) var(--s-sm); font-size: var(--t-sm); color: var(--c-text-2); background: none; border: none; border-bottom: 2px solid transparent; cursor: pointer; white-space: nowrap; }
.tab:hover { color: var(--c-text); }
.tab--active { color: var(--c-brand); border-bottom-color: var(--c-brand); font-weight: 600; }
.list { max-height: 560px; overflow-y: auto; }
.empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); font-size: var(--t-sm); }
.empty__icon { color: var(--c-text-4); }

.row {
  display: flex; gap: var(--s-md); width: 100%; text-align: left;
  padding: var(--s-md) var(--s-lg); background: none; border: none;
  border-bottom: 1px solid var(--c-border-light); cursor: pointer;
  border-left: 3px solid transparent;
}
.row:hover { background: var(--c-brand-soft); }
.row--active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.row--bad { border-left-color: var(--c-danger-fg); }
.row--bad.row--active { box-shadow: inset 3px 0 0 var(--c-danger-fg); }
.row__score { font-size: 28px; font-weight: 800; line-height: 1; font-variant-numeric: tabular-nums; width: 40px; flex-shrink: 0; }
.row__main { flex: 1; min-width: 0; }
.row__top { display: flex; justify-content: space-between; align-items: center; gap: var(--s-xs); }
.row__name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.row__svc { font-size: var(--t-xs); color: var(--c-text-3); margin: 2px 0; }
.row__comment { font-size: var(--t-xs); color: var(--c-text-2); line-height: var(--lh-md); display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.row__tags { display: flex; flex-wrap: wrap; gap: 4px; margin-top: var(--s-xs); }
.row__tag { font-size: 10px; padding: 1px 6px; border-radius: var(--r-sm); background: var(--c-disabled-bg); color: var(--c-text-2); }

/* 详情 */
.detail__hero { display: flex; gap: var(--s-lg); align-items: center; padding-bottom: var(--s-md); border-bottom: 1px solid var(--c-border-light); margin-bottom: var(--s-md); }
.detail__score { font-size: 56px; font-weight: 800; line-height: 1; font-variant-numeric: tabular-nums; }
.detail__cat { display: inline-flex; align-items: center; gap: 6px; font-size: var(--t-md); font-weight: 600; color: var(--c-text); }
.detail__svc { font-size: var(--t-sm); color: var(--c-text-2); margin-top: 4px; }
.detail__date { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }

.detail__tags { display: flex; flex-wrap: wrap; gap: var(--s-xs); margin-bottom: var(--s-md); }
.tag { font-size: var(--t-xs); padding: 3px 10px; border-radius: var(--r-sm); background: var(--c-brand-soft); color: var(--c-brand); }

.sec-title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); margin-bottom: var(--s-xs); }
.detail__comment p, .detail__note p { margin: 0; font-size: var(--t-sm); color: var(--c-text-2); line-height: var(--lh-lg); }
.detail__note { margin-top: var(--s-md); padding: var(--s-md); background: var(--c-disabled-bg); border-radius: var(--r-md); }

.detail__ops { display: flex; justify-content: flex-end; gap: var(--s-sm); margin-top: var(--s-lg); padding-top: var(--s-lg); border-top: 1px solid var(--c-border-light); }
.ops__done { display: inline-flex; align-items: center; gap: var(--s-xs); color: var(--c-success-fg); font-size: var(--t-sm); font-weight: 600; }

.detail-empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-md); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); }
.detail-empty__icon { color: var(--c-text-4); }

.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 420px; max-width: 100%; box-shadow: var(--shadow-pop); }
.form__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.form__label { font-size: var(--t-xs); color: var(--c-text-3); }
.textarea { width: 100%; padding: var(--s-sm) var(--s-md); border: 1px solid var(--c-border); border-radius: var(--r-md); font-size: var(--t-sm); font-family: inherit; resize: vertical; background: var(--c-surface); color: var(--c-text); }
.textarea:focus { outline: none; border-color: var(--c-brand); }

@media (max-width: 1024px) {
  .nps__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .nps-overview { grid-template-columns: 1fr; }
  .ov-score { flex-direction: column; text-align: center; }
  .ov-dist { min-width: 0; width: 100%; }
  .nps__body { grid-template-columns: 1fr; }
  .list { max-height: 320px; }
}
</style>
