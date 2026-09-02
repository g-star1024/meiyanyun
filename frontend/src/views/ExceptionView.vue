<script setup lang="ts">
/* ============================================================
 * 异常处理 /m2-exception（M2-18）
 * 系统/业务/设备/客诉异常事件流，分级告警，升级闭环。
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CSelect from '@/components/CSelect.vue'
import CTextarea from '@/components/CTextarea.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CFab from '@/components/CFab.vue'
import { useAuthStore } from '@/stores/auth'
import { useExceptionStore, type ExceptionEvent, type ExLevel } from '@/stores/exception'
import { EXCEPTION_STATUS, RISK_LEVEL, dictPill } from '@/config/dictionary'
import { useToast } from '@/composables/useToast'

const auth = useAuthStore()
const store = useExceptionStore()
const toast = useToast()
onMounted(() => store.seed())

const selectedId = ref<string | null>(null)
const selected = computed<ExceptionEvent | null>(() => {
  if (selectedId.value) return store.get(selectedId.value) ?? null
  return store.filtered[0] ?? null
})

const kpis = computed(() => [
  { label: '待处理', icon: 'check-square', value: String(store.pending.length), tone: 'danger' as const },
  { label: '处理中', icon: 'dashboard', value: String(store.processing.length), tone: 'warning' as const },
  { label: '高级别', icon: 'alert', value: String(store.highLevel.length), tone: 'orange' as const },
  { label: '今日闭环', icon: 'calendar', value: String(store.todayClosed.length), tone: 'success' as const },
])

const typeOptions = [
  { value: 'ALL', label: '全部类型' },
  { value: 'SYSTEM', label: '系统异常' },
  { value: 'BUSINESS', label: '业务异常' },
  { value: 'DEVICE', label: '设备异常' },
  { value: 'COMPLAINT', label: '客诉' },
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

function fmtTime(iso?: string) {
  if (!iso) return '—'
  const d = new Date(iso)
  return `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

// 处理弹层（升级/闭环/备注）
const showHandle = ref(false)
const handleMode = ref<'ESCALATE' | 'CLOSE'>('CLOSE')
const handleText = ref('')
const canHandle = computed(() => handleText.value.trim().length > 0)
function openHandle(mode: 'ESCALATE' | 'CLOSE') {
  if (!selected.value || selected.value.status === 'CLOSED') return
  handleMode.value = mode
  handleText.value = ''
  showHandle.value = true
}
function submitHandle() {
  if (!selected.value || !canHandle.value) return
  if (handleMode.value === 'ESCALATE') {
    store.escalate(selected.value.id, handleText.value.trim())
  } else {
    store.close(selected.value.id, handleText.value.trim())
  }
  showHandle.value = false
}

function doStart() {
  if (!selected.value) {
    toast.warning('请先选择一条异常事件')
    return
  }
  if (selected.value.status === 'CLOSED') {
    toast.info('该事件已闭环，无需重复处理')
    return
  }
  if (selected.value.status === 'PENDING') {
    store.start(selected.value.id)
    toast.success(`已开始处理告警「${selected.value.title}」`)
  } else if (selected.value.status === 'PROCESSING') {
    openHandle('CLOSE')
  }
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
          <CSelect v-model="store.filterType" :options="typeOptions" width="130px" />
          <CSelect v-model="store.filterStatus" :options="statusOptions" width="120px" />
        </div>
        <div class="list">
          <div v-if="store.filtered.length === 0" class="empty">
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
                <CIcon :name="(store.TYPE_ICON[e.type]) as any" :size="13" /> {{ store.TYPE_LABEL[e.type] }}
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
            :actions="[{ icon: 'bell', label: '处理告警', disabled: !auth.can('exception:edit') || !selected, onClick: doStart }]"
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
              <span class="tag tag--type">{{ store.TYPE_LABEL[selected.type] }}</span>
              <span class="tag"><CIcon name="bell" :size="12" /> {{ selected.source }}</span>
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
          <template v-if="selected.status === 'PENDING'">
            <CButton variant="ghost" v-perm.disable="'exception:edit'" @click="openHandle('ESCALATE')">
              <CIcon name="trend-up" :size="16" />升级
            </CButton>
            <CButton variant="primary" v-perm.disable="'exception:edit'" @click="doStart">
              <CIcon name="check" :size="16" />开始处理
            </CButton>
          </template>
          <template v-else-if="selected.status === 'PROCESSING'">
            <CButton variant="ghost" v-perm.disable="'exception:edit'" @click="openHandle('ESCALATE')">
              <CIcon name="trend-up" :size="16" />升级
            </CButton>
            <CButton variant="primary" v-perm.disable="'exception:edit'" @click="openHandle('CLOSE')">
              <CIcon name="check-square" :size="16" />闭环
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

    <!-- 处理弹层（升级/闭环） -->
    <div v-if="showHandle" class="modal-mask" @click.self="showHandle = false">
      <CCard class="modal modal--sm" :title="handleMode === 'ESCALATE' ? '升级处理' : '异常闭环'" padding="lg">
        <div class="form">
          <div class="form__row">
            <label class="form__label">{{ handleMode === 'ESCALATE' ? '升级原因' : '闭环说明' }} <span class="req">*</span></label>
            <CTextarea v-model="handleText" :placeholder="handleMode === 'ESCALATE' ? '说明升级原因，将通知店长介入' : '说明处理结果与验证情况'" />
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showHandle = false">取消</CButton>
          <CButton :variant="handleMode === 'ESCALATE' ? 'danger' : 'primary'" :disabled="!canHandle" @click="submitHandle">
            {{ handleMode === 'ESCALATE' ? '确认升级' : '确认闭环' }}
          </CButton>
        </template>
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

.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 440px; max-width: 100%; max-height: 90vh; overflow-y: auto; box-shadow: var(--shadow-pop); }
.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.form__label { font-size: var(--t-xs); color: var(--c-text-3); }
.req { color: var(--c-danger-fg); }

@media (max-width: 1024px) {
  .ex__body { grid-template-columns: 1fr; }
  .ex__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .detail__head { flex-direction: column; gap: var(--s-sm); }
  .detail__assign { text-align: left; }
  .list { max-height: 320px; }
}
</style>
