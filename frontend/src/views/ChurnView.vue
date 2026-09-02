<script setup lang="ts">
/* ============================================================
 * M3-10 流失预警 /m3-churn
 * 左风险榜 + 右客户详情（消费概览/原因/建议/干预时间线）+ 干预弹层。
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CTextarea from '@/components/CTextarea.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import {
  useChurnStore,
  type ChurnCustomer,
  type ChurnRisk,
  type ChurnReason,
} from '@/stores/churn'
import { CHURN_STATUS, dictPill } from '@/config/dictionary'

const store = useChurnStore()
onMounted(() => store.seed())

const selectedId = ref<string | null>(null)
const selected = computed<ChurnCustomer | null>(() => {
  if (selectedId.value) return store.get(selectedId.value) ?? null
  return store.filtered[0] ?? null
})

const kpis = computed(() => [
  { label: '高风险', icon: 'alert', value: String(store.high.length), tone: 'danger' as const },
  { label: '中风险', icon: 'alert', value: String(store.medium.length), tone: 'warning' as const },
  { label: '本月挽回', icon: 'customer', value: String(store.recoveredThisMonth.length), tone: 'success' as const },
  { label: '流失率', icon: 'trend-down', value: `${store.churnRate}%`, tone: 'text' as const },
])

const riskFilter = [
  { value: 'ALL', label: '全部风险' },
  { value: 'HIGH', label: '高风险' },
  { value: 'MEDIUM', label: '中风险' },
  { value: 'LOW', label: '低风险' },
]

const riskMap: Record<ChurnRisk, { text: string; cls: string; status: 'danger' | 'warning' | 'success' }> = {
  HIGH: { text: '高风险', cls: 'risk--high', status: 'danger' },
  MEDIUM: { text: '中风险', cls: 'risk--med', status: 'warning' },
  LOW: { text: '低风险', cls: 'risk--low', status: 'success' },
}
const reasonLabel: Record<ChurnReason, string> = store.REASON_LABEL

function fmtDate(iso: string) {
  const d = new Date(iso)
  return `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}
function fmtMoney(n: number) {
  return `¥${n.toLocaleString()}`
}

// 干预弹层
const showForm = ref(false)
const form = ref({ action: '', note: '' })
const actionOptions = [
  '下发专属优惠券 + 顾问电话回访',
  '企微发送新品体验邀约',
  '邀请到店免费皮肤检测',
  '店长亲自致歉并提供补偿方案',
  '发送月度活动海报',
]
function openForm() {
  form.value = { action: selected.value?.suggestedAction || actionOptions[0], note: '' }
  showForm.value = true
}
function submitForm() {
  if (!selected.value || !form.value.action.trim()) return
  store.intervene(selected.value.id, form.value.action.trim(), form.value.note.trim() || undefined)
  showForm.value = false
}

function doRecover() {
  if (selected.value) store.markRecovered(selected.value.id)
}
function doLost() {
  if (selected.value) store.markLost(selected.value.id)
}
</script>

<template>
  <div class="ch">
    <div class="ch__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <div class="ch__body">
      <CCard class="ch__list" padding="none">
        <div class="filters">
          <CSelect v-model="store.filterRisk" :options="riskFilter" />
        </div>
        <div class="list">
          <div v-if="store.filtered.length === 0" class="empty">
            <CIcon :name="('alert' as any)" :size="28" class="empty__icon" />
            <div>暂无风险客户</div>
          </div>
          <button
            v-for="c in store.filtered" :key="c.id"
            class="row" :class="[`row--${c.risk.toLowerCase()}`, { 'row--active': selected?.id === c.id }]"
            @click="selectedId = c.id"
          >
            <div class="row__top">
              <span class="row__name">{{ c.name }} · {{ c.level }}</span>
              <span class="risk-pill" :class="riskMap[c.risk].cls">{{ riskMap[c.risk].text }} {{ c.riskScore }}</span>
            </div>
            <div class="row__score">
              <div class="score-bar"><div class="score-bar__fill" :class="riskMap[c.risk].cls" :style="{ width: c.riskScore + '%' }" /></div>
            </div>
            <div class="row__meta">
              <CStatusPill :status="dictPill(CHURN_STATUS[c.status]).status">{{ dictPill(CHURN_STATUS[c.status]).text }}</CStatusPill>
              <span><CIcon name="clock" :size="12" /> {{ c.lastVisitDays }} 天未到店</span>
            </div>
            <div class="row__reasons">
              <span v-for="r in c.reasons" :key="r" class="reason-tag">{{ reasonLabel[r] }}</span>
            </div>
          </button>
        </div>
      </CCard>

      <CCard v-if="selected" class="ch__detail" :title="`${selected.name} 的风险详情`">
        <template #header>
          <h3 class="ch__detail-title">{{ selected.name }} · {{ selected.level }}</h3>
          <div class="detail__head-pills">
            <span class="risk-pill" :class="riskMap[selected.risk].cls">{{ riskMap[selected.risk].text }} · {{ selected.riskScore }}</span>
            <CStatusPill :status="dictPill(CHURN_STATUS[selected.status]).status">{{ dictPill(CHURN_STATUS[selected.status]).text }}</CStatusPill>
          </div>
        </template>

        <div class="detail__spend">
          <div class="spend-item">
            <div class="spend-item__label">累计消费</div>
            <div class="spend-item__value">{{ fmtMoney(selected.totalSpent) }}</div>
          </div>
          <div class="spend-item">
            <div class="spend-item__label">最近消费</div>
            <div class="spend-item__value">{{ fmtMoney(selected.lastSpent) }}</div>
          </div>
          <div class="spend-item">
            <div class="spend-item__label">到店次数</div>
            <div class="spend-item__value">{{ selected.visitCount }} 次</div>
          </div>
          <div class="spend-item">
            <div class="spend-item__label">最后到店</div>
            <div class="spend-item__value spend-item__value--warn">{{ selected.lastVisitDays }} 天前</div>
          </div>
        </div>

        <div class="detail__section">
          <div class="detail__sec-title">流失原因标签</div>
          <div class="reasons">
            <span v-for="r in selected.reasons" :key="r" class="reason-tag reason-tag--lg">{{ reasonLabel[r] }}</span>
          </div>
        </div>

        <div class="detail__section">
          <div class="detail__sec-title">AI 建议干预动作</div>
          <div class="suggest">
            <CIcon name="alert" :size="16" />
            <span>{{ selected.suggestedAction }}</span>
          </div>
        </div>

        <div class="detail__section">
          <div class="detail__sec-title">干预记录</div>
          <div class="timeline">
            <div v-for="l in selected.logs" :key="l.id" class="tl-item">
              <div class="tl-item__dot" />
              <div class="tl-item__body">
                <div class="tl-item__head">
                  <span class="tl-item__who">{{ l.by }}</span>
                  <span class="tl-item__action">{{ l.action }}</span>
                  <span class="tl-item__time">{{ fmtDate(l.at) }}</span>
                </div>
                <div v-if="l.note" class="tl-item__note">{{ l.note }}</div>
              </div>
            </div>
          </div>
        </div>

        <div class="detail__ops">
          <template v-if="selected.status !== 'LOST' && selected.status !== 'RECOVERED'">
            <CButton variant="ghost" v-perm.disable="'churn:edit'" @click="doLost">
              <CIcon name="close" :size="16" />标记流失
            </CButton>
            <CButton variant="ghost" v-perm.disable="'churn:edit'" @click="openForm">
              <CIcon name="upload" :size="16" />下发干预
            </CButton>
            <CButton variant="primary" v-perm.disable="'churn:edit'" @click="doRecover">
              <CIcon name="check" :size="16" />标记挽回
            </CButton>
          </template>
          <div v-else-if="selected.status === 'RECOVERED'" class="ops__done ops__done--success">
            <CIcon name="check" :size="16" />客户已挽回
          </div>
          <div v-else class="ops__done ops__done--lost">
            <CIcon name="close" :size="16" />客户已流失
          </div>
        </div>
      </CCard>

      <CCard v-else class="ch__detail ch__detail--empty" title="客户详情">
        <div class="detail-empty">
          <CIcon :name="('alert' as any)" :size="40" class="detail-empty__icon" />
          <p>请选择一位风险客户</p>
        </div>
      </CCard>
    </div>

    <!-- 干预弹层 -->
    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <CCard class="modal" title="下发干预任务" padding="lg">
        <div class="form">
          <div class="form__row">
            <label class="form__label">干预动作</label>
            <CSelect v-model="form.action" :options="actionOptions.map(a => ({ value: a, label: a }))" />
          </div>
          <div class="form__row">
            <label class="form__label">备注（可选）</label>
            <CTextarea v-model="form.note" placeholder="干预细节或客户特殊情况" />
          </div>
          <div class="form__row">
            <label class="form__label">归属人</label>
            <CInput :model-value="selected?.assignee" disabled />
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showForm = false">取消</CButton>
          <CButton variant="primary" @click="submitForm">下发</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.ch { display: flex; flex-direction: column; gap: var(--s-lg); }
.ch__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .ch__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }

.ch__body { display: grid; grid-template-columns: 380px 1fr; gap: var(--s-lg); align-items: start; }
.ch__list { min-width: 0; }

.filters { display: flex; gap: var(--s-sm); padding: var(--s-md); border-bottom: 1px solid var(--c-border-light); }
.list { max-height: 620px; overflow-y: auto; }
.empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); font-size: var(--t-sm); }
.empty__icon { color: var(--c-text-4); }

.row {
  display: block; width: 100%; text-align: left; padding: var(--s-md) var(--s-lg);
  background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer;
}
.row:hover { background: var(--c-brand-soft); }
.row--active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.row--high.row--active { box-shadow: inset 3px 0 0 var(--c-danger-fg); }
.row--med.row--active { box-shadow: inset 3px 0 0 var(--c-warning-fg); }
.row__top { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--s-xs); }
.row__name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.risk-pill { font-size: var(--t-xs); padding: 2px 10px; border-radius: var(--r-sm); font-weight: 600; }
.risk-pill.risk--high { background: var(--c-danger-bg); color: var(--c-danger-fg); }
.risk-pill.risk--med { background: var(--c-warning-bg); color: var(--c-warning-fg); }
.risk-pill.risk--low { background: var(--c-success-bg, #e6f9ed); color: var(--c-success-fg); }

.row__score { margin-bottom: var(--s-xs); }
.score-bar { height: 6px; background: var(--c-surface-muted); border-radius: var(--r-sm); overflow: hidden; }
.score-bar__fill { height: 100%; border-radius: var(--r-sm); transition: width .3s; }
.score-bar__fill.risk--high { background: var(--c-danger-fg); }
.score-bar__fill.risk--med { background: var(--c-warning-fg); }
.score-bar__fill.risk--low { background: var(--c-success-fg); }

.row__meta { display: flex; flex-wrap: wrap; gap: var(--s-xs); font-size: var(--t-xs); color: var(--c-text-3); align-items: center; margin-bottom: var(--s-xs); }
.row__meta > span { display: inline-flex; align-items: center; gap: 3px; }
.row__reasons { display: flex; flex-wrap: wrap; gap: 4px; }
.reason-tag { font-size: var(--t-xs); padding: 1px 8px; border-radius: var(--r-sm); background: var(--c-surface-muted); color: var(--c-text-2); }

.ch__detail-title { font-size: var(--t-md); font-weight: 700; margin: 0; }
.detail__head-pills { display: flex; gap: var(--s-xs); align-items: center; }

.detail__spend { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); padding: var(--s-md) 0; border-bottom: 1px solid var(--c-border-light); }
.spend-item { display: flex; flex-direction: column; gap: 4px; }
.spend-item__label { font-size: var(--t-xs); color: var(--c-text-3); }
.spend-item__value { font-size: var(--t-lg); font-weight: 700; color: var(--c-text); font-variant-numeric: tabular-nums; }
.spend-item__value--warn { color: var(--c-danger-fg); }

.detail__section { padding: var(--s-md) 0; border-bottom: 1px solid var(--c-border-light); }
.detail__section:last-of-type { border-bottom: none; }
.detail__sec-title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); margin-bottom: var(--s-sm); }

.reasons { display: flex; flex-wrap: wrap; gap: var(--s-xs); }
.reason-tag--lg { font-size: var(--t-sm); padding: 4px 12px; background: var(--c-brand-soft); color: var(--c-brand); }

.suggest { display: flex; gap: var(--s-sm); padding: var(--s-md); background: var(--c-warning-bg); color: var(--c-warning-fg); border-radius: var(--r-md); font-size: var(--t-sm); align-items: flex-start; }

.timeline { display: flex; flex-direction: column; gap: var(--s-sm); }
.tl-item { display: flex; gap: var(--s-sm); position: relative; padding-left: var(--s-sm); }
.tl-item__dot { width: 8px; height: 8px; border-radius: 50%; background: var(--c-brand); margin-top: 6px; flex-shrink: 0; }
.tl-item__body { flex: 1; }
.tl-item__head { display: flex; flex-wrap: wrap; gap: var(--s-xs); align-items: baseline; font-size: var(--t-sm); }
.tl-item__who { font-weight: 600; color: var(--c-text); }
.tl-item__action { color: var(--c-text-2); }
.tl-item__time { font-size: var(--t-xs); color: var(--c-text-3); margin-left: auto; }
.tl-item__note { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }

.detail__ops { display: flex; justify-content: flex-end; gap: var(--s-sm); margin-top: var(--s-lg); padding-top: var(--s-lg); border-top: 1px solid var(--c-border-light); }
.ops__done { display: flex; align-items: center; gap: var(--s-sm); font-size: var(--t-sm); font-weight: 600; margin-left: auto; }
.ops__done--success { color: var(--c-success-fg); }
.ops__done--lost { color: var(--c-text-3); }

.detail-empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-md); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); }
.detail-empty__icon { color: var(--c-text-4); }

.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 520px; max-width: 100%; max-height: 90vh; overflow-y: auto; box-shadow: var(--shadow-pop); }
.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.form__label { font-size: var(--t-xs); color: var(--c-text-3); }

@media (max-width: 1024px) {
  .ch__body { grid-template-columns: 1fr; }
  .ch__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .detail__spend { grid-template-columns: repeat(2, 1fr); }
  .list { max-height: 320px; }
}
</style>
