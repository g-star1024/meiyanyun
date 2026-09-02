<script setup lang="ts">
/* ============================================================
 * 复诊提醒管理 /recall（Desktop 优先 · 平板堆叠）
 * 状态机：待提醒 → 已提醒 → 已确认 / 已预约 / 已跳过。
 * 医生/咨询师发起复诊建议，前台/运营执行提醒与确认。
 * 超期未提醒高亮预警；统计转化率。
 * ============================================================ */
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import CCard from '@/components/CCard.vue'
import CWorkbenchShell from '@/components/CWorkbenchShell.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CTextarea from '@/components/CTextarea.vue'
import CSelect from '@/components/CSelect.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CKpi from '@/components/CKpi.vue'
import CIcon from '@/components/CIcon.vue'
import {
  useRecallStore,
  RECALL_METHOD_LABEL, RECALL_SOURCE_LABEL,
  type Recall, type RecallMethod, type RecallChannel,
} from '@/stores/recall'
import { RECALL_STATUS, dictPill } from '@/config/dictionary'
import { useToast } from '@/composables/useToast'

const recall = useRecallStore()
const router = useRouter()
const toast = useToast()
onMounted(() => recall.seed())

type Tab = 'PENDING' | 'NOTIFIED' | 'CONFIRMED' | 'BOOKED' | 'SKIPPED'
const tab = ref<Tab>('PENDING')
const selectedId = ref<string | null>(null)
const keyword = ref('')

const tabList = computed(() => [
  { k: 'PENDING' as Tab, label: `待提醒 (${recall.pending.length})` },
  { k: 'NOTIFIED' as Tab, label: `已提醒 (${recall.notified.length})` },
  { k: 'CONFIRMED' as Tab, label: `已确认 (${recall.confirmed.length})` },
  { k: 'BOOKED' as Tab, label: `已预约 (${recall.booked.length})` },
  { k: 'SKIPPED' as Tab, label: `已跳过 (${recall.skipped.length})` },
])

const baseList = computed<Recall[]>(() => {
  const map: Record<Tab, Recall[]> = {
    PENDING: [...recall.pending].sort((a, b) => a.dueDate.localeCompare(b.dueDate)),
    NOTIFIED: recall.notified,
    CONFIRMED: recall.confirmed,
    BOOKED: recall.booked,
    SKIPPED: recall.skipped,
  }
  return map[tab.value]
})
const list = computed<Recall[]>(() => {
  const kw = keyword.value.trim()
  if (!kw) return baseList.value
  return baseList.value.filter(
    (r) => r.customerName.includes(kw) || r.reason.includes(kw)
      || (r.relatedOrderNo ?? '').includes(kw) || (r.relatedEmrNo ?? '').includes(kw),
  )
})

const selected = computed(() => {
  if (selectedId.value) return recall.get(selectedId.value) ?? null
  return list.value[0] ?? null
})
function selectTab(t: Tab) { tab.value = t; selectedId.value = null }

const kpis = computed(() => [
  { label: '待提醒', value: String(recall.pending.length), tone: 'warning' as const, icon: 'bell' },
  { label: '今日待提醒', value: String(recall.todayPending.length), tone: 'brand' as const, icon: 'clock' },
  { label: '复诊转化率', value: recall.conversionRate + '%', tone: 'success' as const, icon: 'trend-up' },
  { label: '即将到期(3天)', value: String(recall.upcoming.length), tone: 'brand' as const, icon: 'calendar' },
])

const sourceTone: Record<RecallChannel, string> = {
  DOCTOR_ADVICE: 'src src--doctor',
  COURSE_FOLLOW: 'src src--course',
  SYSTEM_AUTO: 'src src--system',
  MANUAL: 'src src--manual',
}

function isOverdue(r: Recall) {
  if (r.status !== 'PENDING') return false
  const today = new Date(); today.setHours(0, 0, 0, 0)
  return new Date(r.dueDate) < today
}
function daysFromNow(iso: string) {
  const today = new Date(); today.setHours(0, 0, 0, 0)
  const target = new Date(iso); target.setHours(0, 0, 0, 0)
  return Math.round((target.getTime() - today.getTime()) / 86400000)
}
function dueLabel(r: Recall) {
  if (r.status !== 'PENDING') return fmtDate(r.dueDate)
  const d = daysFromNow(r.dueDate)
  if (d === 0) return '今日提醒'
  if (d < 0) return `超期 ${-d} 天`
  if (d === 1) return '明日提醒'
  return `${d} 天后提醒`
}
function fmtDate(iso: string) { return iso.slice(0, 10) }
function fmtDateTime(iso: string) { return iso.replace('T', ' ').slice(0, 16) }

// ---- 操作状态 ----
// 发送提醒（PENDING → NOTIFIED）
const notifyMethod = ref<RecallMethod>('PHONE')
// 客户确认（NOTIFIED → CONFIRMED）
const confirmReply = ref('')
const confirmDate = ref('')
// 改期
const showReschedule = ref(false)
const rescheduleDate = ref('')
const rescheduleNote = ref('')
// 跳过
const showSkip = ref(false)
const skipReason = ref('')

watch(
  selected,
  (r) => {
    showReschedule.value = false; showSkip.value = false
    confirmReply.value = ''; confirmDate.value = ''
    rescheduleDate.value = ''; rescheduleNote.value = ''; skipReason.value = ''
    if (r) {
      notifyMethod.value = r.method
      confirmDate.value = r.dueDate.slice(0, 10)
    }
  },
  { immediate: true },
)

function doNotify() {
  if (!selected.value) return
  selectedId.value = selected.value.id
  recall.notify(selected.value.id, notifyMethod.value)
  toast.success('复诊提醒已发送')
}
function doConfirm() {
  if (!selected.value || !confirmDate.value) return
  selectedId.value = selected.value.id
  recall.confirm(selected.value.id, confirmReply.value, new Date(confirmDate.value).toISOString())
  showReschedule.value = false
  toast.success('已登记客户确认复诊日期')
}
function doBooked() {
  if (!selected.value) return
  selectedId.value = selected.value.id
  recall.markBooked(selected.value.id, selected.value.customerReply)
  toast.success('已标记生成预约')
}
function openReschedule() {
  if (!selected.value) return
  rescheduleDate.value = selected.value.dueDate.slice(0, 10)
  showReschedule.value = true
}
function doReschedule() {
  if (!selected.value || !rescheduleDate.value) return
  selectedId.value = selected.value.id
  recall.reschedule(selected.value.id, new Date(rescheduleDate.value).toISOString(), rescheduleNote.value)
  showReschedule.value = false; rescheduleNote.value = ''
  toast.info('已改期并重新提醒')
}
function doSkip() {
  if (!selected.value || !skipReason.value.trim()) return
  selectedId.value = selected.value.id
  recall.skip(selected.value.id, skipReason.value.trim())
  showSkip.value = false; skipReason.value = ''
  toast.info('已跳过该提醒')
}

function goBooking() { router.push('/appointment') }

// ---- 新建提醒 ----
const showForm = ref(false)
const newRecall = ref({
  customerName: '', reason: '', source: 'MANUAL' as RecallChannel,
  relatedEmrNo: '', relatedOrderNo: '',
  lastVisitDate: '', dueDate: '', method: 'PHONE' as RecallMethod, note: '',
})
const canSubmit = computed(
  () => newRecall.value.customerName.trim() && newRecall.value.reason.trim()
    && newRecall.value.lastVisitDate && newRecall.value.dueDate,
)
function submitRecall() {
  if (!canSubmit.value) return
  const r = recall.schedule({
    customerId: 'C-NEW',
    customerName: newRecall.value.customerName.trim(),
    source: newRecall.value.source,
    reason: newRecall.value.reason.trim(),
    relatedEmrNo: newRecall.value.relatedEmrNo.trim() || undefined,
    relatedOrderNo: newRecall.value.relatedOrderNo.trim() || undefined,
    lastVisitDate: new Date(newRecall.value.lastVisitDate).toISOString(),
    dueDate: new Date(newRecall.value.dueDate).toISOString(),
    method: newRecall.value.method,
    note: newRecall.value.note,
  })
  if (r) {
    showForm.value = false
    newRecall.value = {
      customerName: '', reason: '', source: 'MANUAL', relatedEmrNo: '', relatedOrderNo: '',
      lastVisitDate: '', dueDate: '', method: 'PHONE', note: '',
    }
    selectedId.value = r.id
    tab.value = 'PENDING'
  }
}
</script>

<template>
  <div class="rc">
    <!-- 超期预警条 -->
    <div v-if="recall.overdue.length > 0" class="warnbar">
      <CIcon name="alert" :size="16" />
      <span>有 <strong>{{ recall.overdue.length }}</strong> 条复诊提醒已超期未触达，请优先联系客户（最早 {{ dueLabel(recall.overdue[0]) }}）。</span>
    </div>

    <CWorkbenchShell
      :has-selection="!!selected"
      empty-icon="bell"
      empty-title="请从左侧选择一条复诊提醒"
      empty-desc="待提醒客户可发送提醒、登记确认；已确认可标记生成预约"
      list-width="380px"
    >
      <template #kpis>
        <CKpi v-for="k in kpis" :key="k.label" :value="String(k.value)" :label="k.label" :tone="k.tone" :icon="k.icon" />
      </template>

      <template #toolbar>
        <CInput v-model="keyword" placeholder="搜索客户 / 原因 / 病历 / 订单" />
        <CButton variant="primary" v-perm.disable="'recall:create'" @click="showForm = true">
          <CIcon name="plus" :size="16" />新建提醒
        </CButton>
      </template>

      <template #list>
        <div class="tabs">
          <button
            v-for="t in tabList" :key="t.k"
            class="tab" :class="{ 'tab--active': tab === t.k }"
            @click="selectTab(t.k)"
          >{{ t.label }}</button>
        </div>
        <div class="list">
          <div v-if="list.length === 0" class="empty">
            <CIcon name="bell" :size="28" class="empty__icon" />
            <div>暂无复诊提醒</div>
          </div>
          <button
            v-for="r in list" :key="r.id"
            class="rec" :class="{ 'rec--active': selected?.id === r.id, 'rec--overdue': isOverdue(r) }"
            @click="selectedId = r.id"
          >
            <div class="rec__top">
              <span class="rec__name">{{ r.customerName }}</span>
              <CStatusPill :status="dictPill(RECALL_STATUS[r.status]).status">{{ dictPill(RECALL_STATUS[r.status]).text }}</CStatusPill>
            </div>
            <div class="rec__reason">{{ r.reason }}</div>
            <div class="rec__meta">
              <span class="rec__src" :class="sourceTone[r.source]">{{ RECALL_SOURCE_LABEL[r.source] }}</span>
              <span class="rec__due" :class="{ 'rec__due--overdue': isOverdue(r) }">
                <CIcon name="clock" :size="12" />{{ dueLabel(r) }}
              </span>
            </div>
          </button>
        </div>
      </template>

      <!-- 右列详情 -->
      <template #head>
        <div v-if="selected" class="wb-head">
          <h3 class="rc__detail-title">{{ selected.customerName }}</h3>
          <div class="rc__detail-tags">
            <span class="src" :class="sourceTone[selected.source]">{{ RECALL_SOURCE_LABEL[selected.source] }}</span>
            <CStatusPill :status="dictPill(RECALL_STATUS[selected.status]).status">{{ dictPill(RECALL_STATUS[selected.status]).text }}</CStatusPill>
          </div>
        </div>
      </template>

      <template v-if="selected">
        <div class="cust">
          <div class="cust__name">{{ selected.reason }}</div>
          <div class="cust__sub">
            {{ RECALL_METHOD_LABEL[selected.method] }}提醒
            <template v-if="selected.relatedEmrNo"> · 病历 {{ selected.relatedEmrNo }}</template>
            <template v-if="selected.relatedOrderNo"> · 订单 {{ selected.relatedOrderNo }}</template>
          </div>
        </div>

        <div class="grid">
          <div class="field"><span class="field__label">上次就诊</span><span class="field__val">{{ fmtDate(selected.lastVisitDate) }}</span></div>
          <div class="field"><span class="field__label">建议复诊</span><span class="field__val" :class="{ 'field__val--overdue': isOverdue(selected) }">{{ fmtDate(selected.dueDate) }}（{{ dueLabel(selected) }}）</span></div>
          <div v-if="selected.notifiedByName" class="field"><span class="field__label">提醒人</span><span class="field__val">{{ selected.notifiedByName }}</span></div>
          <div v-if="selected.notifiedAt" class="field"><span class="field__label">提醒时间</span><span class="field__val">{{ fmtDateTime(selected.notifiedAt) }}</span></div>
          <div v-if="selected.confirmedDate" class="field"><span class="field__label">确认复诊</span><span class="field__val">{{ fmtDate(selected.confirmedDate) }}</span></div>
        </div>

        <div v-if="selected.customerReply" class="reply">
          <span class="reply__label">客户回复</span>
          <p>{{ selected.customerReply }}</p>
        </div>
        <div v-if="selected.status === 'SKIPPED' && selected.skipReason" class="reply">
          <span class="reply__label">跳过原因</span>
          <p>{{ selected.skipReason }}</p>
        </div>
        <div v-if="selected.note" class="reply">
          <span class="reply__label">备注</span>
          <p>{{ selected.note }}</p>
        </div>

        <!-- 操作表单区（按状态机显示） -->
        <template v-if="selected.status === 'PENDING'">
          <div class="form">
            <div class="form__row">
              <label class="form__label">提醒方式</label>
              <CSelect v-model="notifyMethod" width="200px" :options="[
                { value: 'PHONE', label: '电话' },
                { value: 'WECHAT', label: '微信' },
                { value: 'SMS', label: '短信' },
                { value: 'IN_STORE', label: '到店面诊' },
              ]" />
            </div>
          </div>
        </template>

        <template v-else-if="selected.status === 'NOTIFIED'">
          <div class="form">
            <div class="form__row">
              <label class="form__label">客户确认复诊日期</label>
              <input type="date" v-model="confirmDate" class="date-input" />
            </div>
            <div class="form__row">
              <label class="form__label">客户回复（选填）</label>
              <CTextarea v-model="confirmReply" placeholder="如：周三下午可以到院" />
            </div>
          </div>
        </template>

        <!-- 改期弹层（内嵌） -->
        <div v-if="showReschedule" class="inline-box">
          <div class="form__row">
            <label class="form__label">新的复诊日期</label>
            <input type="date" v-model="rescheduleDate" class="date-input" />
          </div>
          <div class="form__row">
            <label class="form__label">改期说明（选填）</label>
            <CInput v-model="rescheduleNote" placeholder="如：客户出差，改至下周" />
          </div>
          <div class="inline-box__btns">
            <CButton variant="ghost" @click="showReschedule = false">取消</CButton>
            <CButton variant="primary" :disabled="!rescheduleDate" @click="doReschedule">确认改期</CButton>
          </div>
        </div>

        <!-- 跳过弹层（内嵌） -->
        <div v-if="showSkip" class="inline-box">
          <div class="form__row">
            <label class="form__label">跳过原因（必填）</label>
            <CInput v-model="skipReason" placeholder="如：客户外地，近期无法到院" />
          </div>
          <div class="inline-box__btns">
            <CButton variant="ghost" @click="showSkip = false; skipReason = ''">取消</CButton>
            <CButton variant="primary" :disabled="!skipReason.trim()" @click="doSkip">确认跳过</CButton>
          </div>
        </div>

        <!-- 时间线 -->
        <div class="tl">
          <div class="tl__title">处理轨迹</div>
          <div class="tl__list">
            <div v-for="(e, i) in selected.timeline" :key="i" class="tl__item">
              <div class="tl__dot" />
              <div class="tl__body">
                <div class="tl__action">{{ e.action }}</div>
                <div v-if="e.detail" class="tl__detail">{{ e.detail }}</div>
                <div class="tl__meta">{{ e.by }} · {{ fmtDateTime(e.at) }}</div>
              </div>
            </div>
          </div>
        </div>
      </template>

      <template #foot>
        <template v-if="selected">
          <!-- PENDING：发送提醒 / 改期 / 跳过 -->
          <template v-if="selected.status === 'PENDING'">
            <CButton variant="ghost" v-perm.disable="'recall:edit'" @click="openReschedule">改期</CButton>
            <CButton variant="ghost" v-perm.disable="'recall:edit'" @click="showSkip = true">跳过</CButton>
            <CButton variant="primary" v-perm.disable="'recall:edit'" @click="doNotify">
              <CIcon name="bell" :size="16" />发送提醒
            </CButton>
          </template>

          <!-- NOTIFIED：登记确认 / 标记已预约 / 改期重提醒 / 跳过 -->
          <template v-else-if="selected.status === 'NOTIFIED'">
            <CButton variant="ghost" v-perm.disable="'recall:edit'" @click="openReschedule">改期重提醒</CButton>
            <CButton variant="ghost" v-perm.disable="'recall:edit'" @click="showSkip = true">跳过</CButton>
            <CButton variant="secondary" v-perm.disable="'recall:edit'" @click="doBooked">
              <CIcon name="check" :size="16" />直接标记已预约
            </CButton>
            <CButton variant="primary" :disabled="!confirmDate" v-perm.disable="'recall:edit'" @click="doConfirm">
              <CIcon name="check" :size="16" />登记客户确认
            </CButton>
          </template>

          <!-- CONFIRMED：标记已预约 / 跳过 -->
          <template v-else-if="selected.status === 'CONFIRMED'">
            <CButton variant="ghost" v-perm.disable="'recall:edit'" @click="showSkip = true">跳过</CButton>
            <CButton variant="primary" v-perm.disable="'recall:edit'" @click="doBooked">
              <CIcon name="check" :size="16" />标记已生成预约
            </CButton>
          </template>

          <!-- BOOKED：终态出口 -->
          <template v-else-if="selected.status === 'BOOKED'">
            <span class="wbs-foot-done">
              <CIcon name="check-square" :size="15" />已生成预约，客户将按预约到院
            </span>
            <CButton variant="primary" @click="goBooking">
              <CIcon name="calendar" :size="14" />前往预约看板
            </CButton>
          </template>
        </template>
      </template>
    </CWorkbenchShell>

    <!-- 新建提醒弹层 -->
    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <CCard class="modal" title="新建复诊提醒" padding="lg">
        <div class="form">
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">客户姓名</label>
              <CInput v-model="newRecall.customerName" placeholder="如：王美丽" />
            </div>
            <div>
              <label class="form__label">提醒来源</label>
              <CSelect v-model="newRecall.source" width="100%" :options="[
                { value: 'MANUAL', label: '手动新建' },
                { value: 'DOCTOR_ADVICE', label: '医生建议' },
                { value: 'COURSE_FOLLOW', label: '疗程跟进' },
                { value: 'SYSTEM_AUTO', label: '系统自动' },
              ]" />
            </div>
          </div>
          <div class="form__row">
            <label class="form__label">复诊原因 / 项目</label>
            <CInput v-model="newRecall.reason" placeholder="如：光子嫩肤二次治疗" />
          </div>
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">关联病历号（选填）</label>
              <CInput v-model="newRecall.relatedEmrNo" placeholder="如：EMR2026072501" />
            </div>
            <div>
              <label class="form__label">关联订单号（选填）</label>
              <CInput v-model="newRecall.relatedOrderNo" placeholder="如：SO20260824001" />
            </div>
          </div>
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">上次就诊日期</label>
              <input type="date" v-model="newRecall.lastVisitDate" class="date-input" />
            </div>
            <div>
              <label class="form__label">建议复诊日期</label>
              <input type="date" v-model="newRecall.dueDate" class="date-input" />
            </div>
          </div>
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">默认提醒方式</label>
              <CSelect v-model="newRecall.method" width="100%" :options="[
                { value: 'PHONE', label: '电话' },
                { value: 'WECHAT', label: '微信' },
                { value: 'SMS', label: '短信' },
                { value: 'IN_STORE', label: '到店面诊' },
              ]" />
            </div>
            <div>
              <label class="form__label">备注（选填）</label>
              <CInput v-model="newRecall.note" placeholder="补充说明" />
            </div>
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showForm = false">取消</CButton>
          <CButton variant="primary" :disabled="!canSubmit" @click="submitRecall">创建提醒</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.rc { display: flex; flex-direction: column; gap: var(--s-lg); }

.warnbar {
  display: flex; align-items: center; gap: var(--s-sm);
  padding: var(--s-sm) var(--s-md); border-radius: var(--r-md);
  background: var(--c-warning-bg); color: var(--c-warning-fg); font-size: var(--t-sm);
  border: 1px solid var(--c-warning-fg);
}
.warnbar strong { margin: 0 2px; }

.rc__detail-title { font-size: var(--t-md); line-height: var(--lh-md); font-weight: 700; color: var(--c-text); margin: 0; }
.rc__detail-tags { display: flex; gap: var(--s-xs); align-items: center; }
.wb-head { display: flex; justify-content: space-between; align-items: center; gap: var(--s-sm); }

.tabs { display: flex; border-bottom: 1px solid var(--c-border); overflow-x: auto; flex-shrink: 0; }
.tab {
  flex: 1; padding: var(--s-md) var(--s-xs); font-size: var(--t-xs); white-space: nowrap;
  color: var(--c-text-3); background: none; border: none; cursor: pointer;
  border-bottom: 2px solid transparent;
}
.tab--active { color: var(--c-brand); border-bottom-color: var(--c-brand); font-weight: 600; }

.list { flex: 1; min-height: 0; overflow-y: auto; }
.empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); font-size: var(--t-sm); }
.empty__icon { color: var(--c-text-4); }

.rec {
  display: block; width: 100%; text-align: left; padding: var(--s-md) var(--s-lg);
  background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer;
  border-left: 3px solid transparent;
}
.rec:hover { background: var(--c-brand-soft); }
.rec--active { background: var(--c-brand-soft); }
.rec--overdue { border-left-color: var(--c-danger-fg); }
.rec__top { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; }
.rec__name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.rec__reason { font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: var(--s-xs); }
.rec__meta { display: flex; justify-content: space-between; align-items: center; font-size: var(--t-xs); color: var(--c-text-3); }
.rec__due { display: inline-flex; align-items: center; gap: 3px; }
.rec__due--overdue { color: var(--c-danger-fg); font-weight: 600; }

.src {
  display: inline-block; padding: 1px 8px; border-radius: var(--r-capsule);
  font-size: var(--t-xs); line-height: 1.6;
}
.src--doctor { background: var(--c-brand-soft); color: var(--c-brand); }
.src--course { background: var(--c-success-bg); color: var(--c-success-fg); }
.src--system { background: var(--c-surface-muted, #f3f4f8); color: var(--c-text-3); }
.src--manual { background: var(--c-warning-bg); color: var(--c-warning-fg); }

.cust { padding-bottom: var(--s-md); border-bottom: 1px solid var(--c-border-light); }
.cust__name { font-size: var(--t-lg); font-weight: 700; color: var(--c-text); }
.cust__sub { font-size: var(--t-sm); color: var(--c-text-3); margin-top: 2px; }

.grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md) var(--s-lg); margin: var(--s-lg) 0; }
.field { display: flex; flex-direction: column; gap: 2px; }
.field__label { font-size: var(--t-xs); color: var(--c-text-3); }
.field__val { font-size: var(--t-sm); color: var(--c-text); }
.field__val--overdue { color: var(--c-danger-fg); font-weight: 600; }

.reply { margin-bottom: var(--s-md); padding: var(--s-md); background: var(--c-surface-muted, #f7f8fa); border-radius: var(--r-md); }
.reply__label { font-size: var(--t-xs); color: var(--c-text-3); display: block; margin-bottom: 4px; }
.reply p { margin: 0; font-size: var(--t-sm); color: var(--c-text-2); line-height: 1.6; }

.form { display: flex; flex-direction: column; gap: var(--s-md); margin-bottom: var(--s-md); }
.form__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.form__row--2 { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.form__label { font-size: var(--t-xs); color: var(--c-text-3); }

.inline-box { margin-top: var(--s-md); padding: var(--s-md); background: var(--c-surface-muted, #f7f8fa); border-radius: var(--r-md); display: flex; flex-direction: column; gap: var(--s-sm); }
.inline-box__btns { display: flex; justify-content: flex-end; gap: var(--s-sm); }

.wbs-foot-done { display: inline-flex; align-items: center; gap: var(--s-xs); color: var(--c-success-fg, #389e0d); font-size: var(--t-sm); font-weight: 600; margin-right: auto; }

.tl { margin-top: var(--s-lg); padding-top: var(--s-lg); border-top: 1px solid var(--c-border-light); }
.tl__title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); margin-bottom: var(--s-md); }
.tl__list { position: relative; padding-left: var(--s-lg); }
.tl__list::before { content: ''; position: absolute; left: 5px; top: 4px; bottom: 4px; width: 2px; background: var(--c-border-light); }
.tl__item { position: relative; padding-bottom: var(--s-md); }
.tl__item:last-child { padding-bottom: 0; }
.tl__dot { position: absolute; left: calc(-1 * var(--s-lg) + 1px); top: 4px; width: 10px; height: 10px; border-radius: 50%; background: var(--c-brand); border: 2px solid var(--c-surface); }
.tl__action { font-size: var(--t-sm); color: var(--c-text); font-weight: 500; }
.tl__detail { font-size: var(--t-xs); color: var(--c-text-2); margin-top: 2px; }
.tl__meta { font-size: var(--t-xs); color: var(--c-text-4); margin-top: 2px; }

.date-input {
  padding: 10px; border: 1px solid var(--c-border); border-radius: var(--r-md);
  font-size: var(--t-sm); color: var(--c-text); background: #fff; font-family: inherit;
}
.date-input:focus { outline: none; border-color: var(--c-brand); }

.modal-mask { position: fixed; inset: 0; background: rgba(20,21,43,.45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 600px; max-width: 100%; max-height: 90vh; overflow-y: auto; box-shadow: var(--shadow-pop); }
</style>
