<script setup lang="ts">
/* ============================================================
 * 通用异常中心 /m2-exception（M2-18 / P5-B63 卡2 L83）
 * 三源只读归集：耗材扣料 / 划扣核销 / 异常账务。
 * 中心零写操作：不升级、不闭环；点击「前往处置」按来源跳源处置视图。
 * ============================================================ */
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CSelect from '@/components/CSelect.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CFab from '@/components/CFab.vue'
import { useExceptionStore, type ExceptionEvent, type ExLevel, type ExSource } from '@/stores/exception'
import { EXCEPTION_STATUS, RISK_LEVEL, dictPill } from '@/config/dictionary'

const router = useRouter()
const store = useExceptionStore()
onMounted(() => store.load())

const selectedId = ref<string | null>(null)
const selected = computed<ExceptionEvent | null>(() => {
  if (selectedId.value) return store.get(selectedId.value) ?? null
  return store.filtered[0] ?? null
})

watch(() => store.filtered, (list) => {
  if (selectedId.value && !list.some((e) => e.id === selectedId.value)) selectedId.value = null
})

const kpis = computed(() => [
  { label: '待处理', icon: 'check-square', value: String(store.pending.length), tone: 'danger' as const },
  { label: '处理中', icon: 'dashboard', value: String(store.processing.length), tone: 'warning' as const },
  { label: '高级别', icon: 'alert', value: String(store.highLevel.length), tone: 'orange' as const },
  { label: '今日闭环', icon: 'calendar', value: String(store.todayClosed.length), tone: 'success' as const },
])

const sourceOptions = [
  { value: 'ALL', label: '全部来源' },
  { value: 'BOM_DEDUCT', label: '耗材扣料' },
  { value: 'WRITEOFF', label: '划扣核销' },
  { value: 'FIN_ABNORMAL', label: '异常账务' },
]
const statusOptions = [
  { value: 'ALL', label: '全部状态' },
  { value: 'PENDING', label: '待处理' },
  { value: 'PROCESSING', label: '处理中' },
  { value: 'CLOSED', label: '已闭环' },
]
const levelMap: Record<ExLevel, { text: string; cls: string }> = {
  HIGH: { text: '高', cls: 'lv--high' },
  MEDIUM: { text: '中', cls: 'lv--mid' },
  LOW: { text: '低', cls: 'lv--low' },
}

function sourceLabel(s: ExSource) {
  return store.SOURCE_LABEL[s]
}

function fmtTime(iso?: string) {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '—'
  return `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

function goDispose() {
  if (!selected.value) return
  router.push(selected.value.disposeRoute)
}
</script>

<template>
  <div class="ex">
    <div class="ex__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <div class="ex__body">
      <!-- 左：事件流 -->
      <CCard class="ex__list" padding="none">
        <div class="filters">
          <CSelect v-model="store.filterSource" :options="sourceOptions" width="130px" />
          <CSelect v-model="store.filterStatus" :options="statusOptions" width="120px" />
        </div>
        <div class="list">
          <div v-if="store.loading" class="empty">
            <CIcon name="dashboard" :size="28" class="empty__icon" />
            <div>异常数据加载中…</div>
          </div>
          <div v-else-if="store.error" class="empty">
            <CIcon name="shield" :size="28" class="empty__icon" />
            <div>{{ store.error }}</div>
          </div>
          <div v-else-if="store.filtered.length === 0" class="empty">
            <CIcon name="shield" :size="28" class="empty__icon" />
            <div>暂无异常事件</div>
          </div>
          <button
            v-for="e in store.filtered" :key="e.id"
            class="row" :class="{ 'row--active': selected?.id === e.id, 'row--high': e.level === 'HIGH' && e.status !== 'CLOSED' }"
            @click="selectedId = e.id"
          >
            <div class="row__top">
              <span class="row__type">
                <CIcon :name="(store.TYPE_ICON[e.type]) as any" :size="13" /> {{ sourceLabel(e.source) }}
              </span>
              <span class="lv" :class="levelMap[e.level].cls">{{ levelMap[e.level].text }}</span>
            </div>
            <div class="row__title">{{ e.title }}</div>
            <div class="row__meta">
              <CStatusPill :status="dictPill(EXCEPTION_STATUS[e.status]).status">{{ dictPill(EXCEPTION_STATUS[e.status]).text }}</CStatusPill>
              <span><CIcon name="clock" :size="12" /> {{ fmtTime(e.occurredAt) }}</span>
            </div>
          </button>
          <CFab
            :actions="[{ icon: 'tool', label: '前往处置', disabled: !selected, onClick: goDispose }]"
          />
        </div>
      </CCard>

      <!-- 右：详情 -->
      <CCard v-if="selected" class="ex__detail" :title="selected.no">
        <template #header>
          <h3 class="ex__detail-title">{{ selected.no }}</h3>
          <div class="header__right">
            <span class="lv lv--pill" :class="`lv--${RISK_LEVEL[selected.level as keyof typeof RISK_LEVEL].color}`">{{ dictPill(RISK_LEVEL[selected.level as keyof typeof RISK_LEVEL]).text }}级</span>
            <CStatusPill :status="dictPill(EXCEPTION_STATUS[selected.status]).status">{{ dictPill(EXCEPTION_STATUS[selected.status]).text }}</CStatusPill>
          </div>
        </template>

        <div class="detail__head">
          <div>
            <div class="detail__title">
              <CIcon :name="(store.TYPE_ICON[selected.type]) as any" :size="18" />
              {{ selected.title }}
            </div>
            <div class="detail__sub">
              <span class="tag tag--type">{{ sourceLabel(selected.source) }}</span>
              <span class="tag"><CIcon name="bell" :size="12" /> {{ selected.storeName }}</span>
            </div>
          </div>
          <div class="detail__assign">
            <div class="detail__assign-label">责任人</div>
            <div class="detail__assign-name">{{ selected.assignee }}</div>
          </div>
        </div>

        <div class="detail__grid">
          <div class="field"><span class="field__label">发生时间</span><span class="field__val">{{ fmtTime(selected.occurredAt) }}</span></div>
          <div class="field"><span class="field__label">闭环时间</span><span class="field__val">{{ fmtTime(selected.closedAt) }}</span></div>
        </div>

        <div class="detail__desc">
          <div class="detail__sec-title">异常描述</div>
          <p>{{ selected.description }}</p>
        </div>

        <div class="detail__notes">
          <div class="detail__sec-title">处理时间线</div>
          <div v-for="(t, i) in selected.timeline" :key="i" class="note">
            <span class="note__who">{{ t.by }}</span>
            <span class="note__text">{{ t.text }}</span>
            <span class="note__time">{{ fmtTime(t.at) }}</span>
          </div>
        </div>

        <div class="detail__ops">
          <template v-if="selected.status !== 'CLOSED'">
            <CButton variant="primary" @click="goDispose">
              <CIcon name="tool" :size="16" />前往处置
            </CButton>
          </template>
          <div v-else class="ops__done">
            <CIcon name="check" :size="16" />已于 {{ fmtTime(selected.closedAt) }} 闭环
          </div>
        </div>
      </CCard>

      <CCard v-else class="ex__detail ex__detail--empty" title="异常详情">
        <div class="detail-empty">
          <CIcon name="shield" :size="40" class="detail-empty__icon" />
          <p>请选择一条异常事件</p>
        </div>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.ex { display: flex; flex-direction: column; gap: var(--s-lg); }
.ex__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .ex__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }

.ex__body { display: grid; grid-template-columns: 380px 1fr; gap: var(--s-lg); align-items: start; }
.ex__list { min-width: 0; display: flex; flex-direction: column; }
.filters { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-md); border-bottom: 1px solid var(--c-border-light); flex-wrap: nowrap; overflow-x: auto; }
.filters > * { flex-shrink: 0; }
.list { max-height: 560px; overflow-y: auto; display: flex; flex-direction: column; }
.empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); font-size: var(--t-sm); }
.empty__icon { color: var(--c-text-4); }

.row {
  display: block; width: 100%; text-align: left; padding: var(--s-md) var(--s-lg);
  background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer;
}
.row:hover { background: var(--c-brand-soft); }
.row--active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.row--high { box-shadow: inset 3px 0 0 var(--c-danger-fg); }
.row--active.row--high { box-shadow: inset 3px 0 0 var(--c-danger-fg), inset 3px 0 0 var(--c-brand); }
.row__top { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--s-xs); }
.row__type { display: inline-flex; align-items: center; gap: 4px; font-size: var(--t-xs); color: var(--c-text-3); }
.row__title { font-size: var(--t-sm); color: var(--c-text); margin-bottom: var(--s-xs); font-weight: 600; line-height: 1.4; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.row__meta { display: flex; flex-wrap: wrap; align-items: center; gap: var(--s-sm); font-size: var(--t-xs); color: var(--c-text-3); }
.row__meta span { display: inline-flex; align-items: center; gap: 3px; }

.lv { font-size: var(--t-xs); font-weight: 600; padding: 1px 7px; border-radius: var(--r-sm); }
.lv--high { color: var(--c-danger-fg); background: var(--c-danger-bg); }
.lv--mid { color: var(--c-warning-fg); background: var(--c-warning-bg); }
.lv--low { color: var(--c-text-3); background: var(--c-disabled-bg); }
.lv--pill { border-radius: var(--r-capsule); }

.header__right { display: flex; align-items: center; gap: var(--s-xs); }
.ex__detail-title { font-size: var(--t-md); font-weight: 700; margin: 0; }

.detail__head { display: flex; justify-content: space-between; gap: var(--s-md); padding-bottom: var(--s-md); border-bottom: 1px solid var(--c-border-light); }
.detail__title { display: flex; align-items: center; gap: var(--s-xs); font-size: var(--t-lg); font-weight: 700; color: var(--c-text); }
.detail__sub { display: flex; flex-wrap: wrap; gap: var(--s-xs); margin-top: var(--s-xs); }
.tag { font-size: var(--t-xs); padding: 2px 8px; border-radius: var(--r-sm); background: var(--c-disabled-bg); color: var(--c-text-2); display: inline-flex; align-items: center; gap: 3px; }
.tag--type { background: var(--c-danger-bg); color: var(--c-danger-fg); }
.detail__assign { text-align: right; }
.detail__assign-label { font-size: var(--t-xs); color: var(--c-text-3); }
.detail__assign-name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }

.detail__grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md) var(--s-lg); margin: var(--s-lg) 0; }
.field { display: flex; flex-direction: column; gap: 2px; }
.field__label { font-size: var(--t-xs); color: var(--c-text-3); }
.field__val { font-size: var(--t-sm); color: var(--c-text); }

.detail__desc { margin-bottom: var(--s-lg); }
.detail__sec-title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); margin-bottom: var(--s-sm); }
.detail__desc p { font-size: var(--t-sm); color: var(--c-text-2); line-height: var(--lh-md); margin: 0; }

.detail__notes { margin-bottom: var(--s-lg); }
.note { display: flex; gap: var(--s-sm); align-items: baseline; padding: var(--s-xs) 0; border-bottom: 1px solid var(--c-border-light); font-size: var(--t-sm); }
.note:last-child { border-bottom: none; }
.note__who { font-weight: 600; color: var(--c-text); flex-shrink: 0; }
.note__text { color: var(--c-text-2); flex: 1; }
.note__time { font-size: var(--t-xs); color: var(--c-text-3); flex-shrink: 0; }

.detail__ops { display: flex; justify-content: flex-end; align-items: center; gap: var(--s-sm); margin-top: var(--s-lg); padding-top: var(--s-lg); border-top: 1px solid var(--c-border-light); }
.ops__done { display: flex; align-items: center; gap: var(--s-sm); font-size: var(--t-sm); color: var(--c-success-fg); font-weight: 600; margin-left: auto; }

.detail-empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-md); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); }
.detail-empty__icon { color: var(--c-text-4); }

@media (max-width: 1024px) {
  .ex__body { grid-template-columns: 1fr; }
  .ex__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .detail__head { flex-direction: column; gap: var(--s-sm); }
  .detail__assign { text-align: left; }
  .list { max-height: 320px; }
}
</style>
