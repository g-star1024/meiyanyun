<script setup lang="ts">
/* ============================================================
 * 智能排队候补 /queue（Desktop 优先 · 平板堆叠）
 * 候诊队列实时等候时长，超时阈值/自动释放号源取自设置中心。
 * 数据源 arrival store。权限 queue:view / create / edit。
 * ============================================================ */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useArrivalStore } from '@/stores/arrival'
import { useWaitlistStore } from '@/stores/waitlist'
import { useCustomerStore } from '@/stores/customer'
import { useSettingsStore } from '@/stores/settings'
import { useAuthStore } from '@/stores/auth'
import { useToast } from '@/composables/useToast'
import CKpi from '@/components/CKpi.vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import type { Arrival } from '@/types/domain'
import type { WaitlistItem } from '@/stores/waitlist'

const arrival = useArrivalStore()
const waitlist = useWaitlistStore()
const customer = useCustomerStore()
const settings = useSettingsStore()
const auth = useAuthStore()
const toast = useToast()

onMounted(() => {
  arrival.load(auth.user.storeId)
  waitlist.load(auth.user.storeId)
})

// 每 30 秒刷新当前时间，驱动等候时长重算；同时静默轮询到店/候补（释放与递补近实时可见）
const now = ref(Date.now())
let timer: number | undefined
onMounted(() => {
  timer = window.setInterval(() => {
    now.value = Date.now()
    arrival.load(auth.user.storeId)
    waitlist.load(auth.user.storeId)
  }, 30_000)
})
onUnmounted(() => { if (timer) clearInterval(timer) })

const timeoutMin = computed(() => settings.system.queue.waitingTimeoutMin)
const autoRelease = computed(() => settings.system.queue.autoReleaseSlot)
const graceMin = computed(() => settings.system.queue.releaseGraceMin)

/** 等候分钟数（arrivedAt 为 HH:MM） */
function waitedMin(a: Arrival): number {
  const [h, m] = a.arrivedAt.split(':').map(Number)
  const arr = new Date(now.value)
  arr.setHours(h, m, 0, 0)
  return Math.max(0, Math.round((now.value - arr.getTime()) / 60000))
}
function fmtWait(mins: number): string {
  if (mins < 1) return '刚到'
  if (mins < 60) return `${mins} 分钟`
  return `${Math.floor(mins / 60)} 小时 ${mins % 60} 分`
}

interface QueueRow {
  arrival: Arrival
  custName: string
  phoneMask: string
  waited: number
  overdue: boolean
  releasing: boolean // 已超时进入释放宽限
}

function toRow(a: Arrival): QueueRow {
  const c = customer.get(a.customerId)
  const waited = waitedMin(a)
  const overdue = a.status === 'WAITING' && waited >= timeoutMin.value
  const releasing = overdue && autoRelease.value && waited >= timeoutMin.value + graceMin.value
  return { arrival: a, custName: c?.name || a.customerId, phoneMask: c?.phoneMask || '', waited, overdue, releasing }
}

const waitingRows = computed(() =>
  arrival.waiting.map(toRow).sort((a, b) => a.arrival.queueNo - b.arrival.queueNo),
)
const triagedRows = computed(() =>
  arrival.triaged.map(toRow).sort((a, b) => b.arrival.queueNo - a.arrival.queueNo),
)
const overdueRows = computed(() => waitingRows.value.filter((r) => r.overdue))

const kpis = computed(() => [
  { label: '候诊中', icon: 'calendar', value: String(waitingRows.value.length), tone: 'brand' as const },
  { label: '候诊超时', icon: 'alert', value: String(overdueRows.value.length), tone: (overdueRows.value.length ? 'danger' : 'text') as 'danger' | 'text' },
  { label: '已分诊/叫号', icon: 'user-check', value: String(triagedRows.value.length), tone: 'teal' as const },
  { label: '今日已完成', icon: 'calendar', value: String(arrival.done.length), tone: 'success' as const },
  { label: '今日已释放', icon: 'clock', value: String(arrival.left.length), tone: (arrival.left.length ? 'danger' : 'text') as 'danger' | 'text' },
  { label: '智能候补', icon: 'user-check', value: String(waitlist.active.length), tone: (waitlist.notifiedCount ? 'warning' : 'text') as 'warning' | 'text' },
])

async function callRow(a: Arrival) {
  if (a.status === 'TRIAGED') await arrival.call(a.id)
}
async function markDone(a: Arrival) {
  await arrival.markDone(a.id)
}
async function releaseRow(a: Arrival) {
  if (!window.confirm(`确认释放 ${String(a.queueNo).padStart(2, '0')} 号号源？释放后将自动递补本店候补首位。`)) return
  if (await arrival.release(a.id)) {
    toast.success('号源已释放，已触发候补递补')
    waitlist.load(auth.user.storeId)
  }
}

// ---- 智能候补 ----
function wlPill(w: WaitlistItem): { status: 'warning' | 'info'; text: string } {
  return w.status === 'NOTIFIED'
    ? { status: 'warning', text: '已通知待到场' }
    : { status: 'info', text: waitlist.STATUS_LABEL.WAITING }
}
async function wlFulfill(w: WaitlistItem) {
  if (!window.confirm(`确认候补客户「${w.customerName}」已到场？将转为正式到店登记。`)) return
  const r = await waitlist.fulfill(w.id)
  if (r) {
    toast.success(`已转正式到店${r.ahNo ? `（${r.ahNo}）` : ''}`)
    arrival.load(auth.user.storeId)
  }
}
async function wlCancel(w: WaitlistItem) {
  if (!window.confirm(`确认取消「${w.customerName}」的候补登记？`)) return
  await waitlist.cancel(w.id)
}

const showWlForm = ref(false)
const wlForm = ref({ customerName: '', phone: '', project: '', expectDate: '' })
const wlCanSubmit = computed(() =>
  wlForm.value.customerName.trim() && wlForm.value.phone.trim() && wlForm.value.project.trim(),
)
function openWlForm() {
  wlForm.value = { customerName: '', phone: '', project: '', expectDate: '' }
  showWlForm.value = true
}
async function submitWl() {
  if (!wlCanSubmit.value) return
  const w = await waitlist.register({
    customerName: wlForm.value.customerName,
    phone: wlForm.value.phone,
    project: wlForm.value.project,
    expectDate: wlForm.value.expectDate || undefined,
  })
  if (w) {
    showWlForm.value = false
    toast.success('候补登记成功，号源释放时将自动递补通知')
  }
}
</script>

<template>
  <div class="q-page">
    <div class="q-kpis">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <div class="q-settings-hint">
      <CIcon name="clock" :size="14" />
      候诊超时阈值 <strong>{{ timeoutMin }} 分钟</strong>
      <span v-if="autoRelease">· 超时 {{ graceMin }} 分钟宽限后自动释放号源</span>
      <span v-else>· 未开启自动释放号源</span>
      <span class="q-settings-hint__sub">（参数可在设置中心调整）</span>
    </div>

    <div class="q-body">
      <!-- 候诊队列 -->
      <CCard class="q-col" padding="none">
        <template #header>
          <h3 class="q-col__title">候诊队列</h3>
          <span class="q-col__count">{{ waitingRows.length }} 人</span>
        </template>
        <div class="q-list">
          <div v-if="!waitingRows.length" class="q-empty">
            <CIcon name="check-square" :size="32" class="q-empty__icon" />
            <p>暂无候诊客户</p>
          </div>
          <div
            v-for="r in waitingRows" :key="r.arrival.id"
            class="qrow"
            :class="{ 'qrow--overdue': r.overdue, 'qrow--release': r.releasing }"
          >
            <div class="qrow__no">{{ String(r.arrival.queueNo).padStart(2, '0') }}</div>
            <div class="qrow__main">
              <div class="qrow__name">
                {{ r.custName }}
                <CStatusPill v-if="r.releasing" status="danger">释放中</CStatusPill>
                <CStatusPill v-else-if="r.overdue" status="warning">候诊超时</CStatusPill>
              </div>
              <div class="qrow__sub">{{ r.phoneMask }} · {{ r.arrival.channel === 'WALK_IN' ? '自然到店' : r.arrival.channel === 'REFERRAL' ? '转介绍' : '线上预约' }}</div>
            </div>
            <div class="qrow__wait">
              <div class="qrow__wait-time" :class="{ 'qrow__wait-time--overdue': r.overdue }">{{ fmtWait(r.waited) }}</div>
              <div class="qrow__wait-hint">等候时长</div>
              <CButton
                class="qrow__release"
                size="sm" variant="ghost"
                v-perm.disable="'queue:edit'"
                @click="releaseRow(r.arrival)"
              >释放</CButton>
            </div>
          </div>
        </div>
      </CCard>

      <!-- 已分诊/叫号 -->
      <CCard class="q-col" padding="none">
        <template #header>
          <h3 class="q-col__title">已分诊 / 叫号</h3>
          <span class="q-col__count">{{ triagedRows.length }} 人</span>
        </template>
        <div class="q-list">
          <div v-if="!triagedRows.length" class="q-empty">
            <CIcon name="home" :size="32" class="q-empty__icon" />
            <p>分诊后客户将显示在此处</p>
          </div>
          <div
            v-for="r in triagedRows" :key="r.arrival.id"
            class="qrow qrow--triaged"
            :class="{ 'qrow--called': r.arrival.status === 'CALLED' }"
          >
            <div class="qrow__no qrow__no--teal">{{ String(r.arrival.queueNo).padStart(2, '0') }}</div>
            <div class="qrow__main">
              <div class="qrow__name">
                {{ r.custName }}
                <CStatusPill v-if="r.arrival.status === 'CALLED'" status="primary">已叫号</CStatusPill>
                <CStatusPill v-else status="info">已分诊</CStatusPill>
              </div>
              <div class="qrow__sub">{{ r.phoneMask }} · 到店 {{ r.arrival.arrivedAt }}</div>
            </div>
            <div class="qrow__ops">
              <CButton
                v-if="r.arrival.status === 'TRIAGED'"
                size="sm" variant="primary"
                v-perm.disable="'queue:edit'"
                @click="callRow(r.arrival)"
              >叫号</CButton>
              <CButton
                size="sm" variant="ghost"
                v-perm.disable="'queue:edit'"
                @click="markDone(r.arrival)"
              >完成</CButton>
            </div>
          </div>
        </div>
      </CCard>

      <!-- 智能候补：号源释放后自动递补首位；客户到场转正式到店 -->
      <CCard class="q-col" padding="none">
        <template #header>
          <h3 class="q-col__title">智能候补</h3>
          <div class="q-col__head-ops">
            <span class="q-col__count">{{ waitlist.active.length }} 人</span>
            <CButton size="sm" variant="primary" v-perm.disable="'reception:edit'" @click="openWlForm">候补登记</CButton>
          </div>
        </template>
        <div class="q-list">
          <div v-if="!waitlist.active.length" class="q-empty">
            <CIcon name="user-check" :size="32" class="q-empty__icon" />
            <p>暂无候补客户</p>
          </div>
          <div
            v-for="w in waitlist.active" :key="w.id"
            class="qrow qrow--waitlist"
            :class="{ 'qrow--notified': w.status === 'NOTIFIED' }"
          >
            <div class="qrow__no qrow__no--waitlist">{{ w.wlNo.replace(/\D/g, '').slice(-2).padStart(2, '0') }}</div>
            <div class="qrow__main">
              <div class="qrow__name">
                {{ w.customerName }}
                <CStatusPill :status="wlPill(w).status">{{ wlPill(w).text }}</CStatusPill>
              </div>
              <div class="qrow__sub">
                {{ w.phone }} · {{ w.project || '未填项目' }}<template v-if="w.expectDate"> · 期望 {{ w.expectDate }}</template>
              </div>
            </div>
            <div class="qrow__ops qrow__ops--col">
              <CButton size="sm" variant="primary" v-perm.disable="'reception:edit'" @click="wlFulfill(w)">到场</CButton>
              <CButton size="sm" variant="ghost" v-perm.disable="'reception:edit'" @click="wlCancel(w)">取消</CButton>
            </div>
          </div>
        </div>
      </CCard>
    </div>

    <!-- 候补登记弹层 -->
    <div v-if="showWlForm" class="modal-mask" @click.self="showWlForm = false">
      <CCard class="wl-modal" title="候补登记" padding="lg">
        <div class="wl-form">
          <div class="wl-form__row wl-form__row--2">
            <div>
              <label class="wl-form__label">客户姓名 <span class="wl-req">*</span></label>
              <CInput v-model="wlForm.customerName" placeholder="如：陈美玲" />
            </div>
            <div>
              <label class="wl-form__label">手机号 <span class="wl-req">*</span></label>
              <CInput v-model="wlForm.phone" placeholder="如：13800002046" />
            </div>
          </div>
          <div class="wl-form__row">
            <label class="wl-form__label">意向项目 <span class="wl-req">*</span></label>
            <CInput v-model="wlForm.project" placeholder="如：超声炮全脸提拉" />
          </div>
          <div class="wl-form__row">
            <label class="wl-form__label">期望到店日期（可选）</label>
            <input v-model="wlForm.expectDate" type="date" class="wl-date" />
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showWlForm = false">取消</CButton>
          <CButton variant="primary" :disabled="!wlCanSubmit" @click="submitWl">登记候补</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.q-page { display: flex; flex-direction: column; gap: var(--s-lg); }
.q-kpis { display: grid; grid-template-columns: repeat(6, 1fr); gap: var(--s-md); }

.q-settings-hint {
  display: flex; align-items: center; gap: var(--s-xs);
  font-size: var(--t-sm); color: var(--c-text-2);
  padding: var(--s-sm) var(--s-md); background: var(--c-brand-soft);
  border-radius: var(--r-md);
}
.q-settings-hint strong { color: var(--c-brand); }
.q-settings-hint__sub { color: var(--c-text-3); margin-left: auto; font-size: var(--t-xs); }

.q-body { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: var(--s-lg); align-items: start; }
.q-col { display: flex; flex-direction: column; }
.q-col :deep(.card__header) { justify-content: space-between; }
.q-col__title { font-size: var(--t-base); font-weight: 700; color: var(--c-text); margin: 0; }
.q-col__count { font-size: var(--t-xs); color: var(--c-text-3); background: var(--c-bg-page); padding: 2px 10px; border-radius: 999px; }
.q-col__head-ops { display: flex; align-items: center; gap: var(--s-sm); }

.q-list { display: flex; flex-direction: column; max-height: 560px; overflow-y: auto; }
.q-empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl); color: var(--c-text-3); }
.q-empty__icon { color: var(--c-text-4); }
.q-empty p { margin: 0; font-size: var(--t-sm); }

.qrow {
  display: grid; grid-template-columns: 48px 1fr auto; gap: var(--s-md);
  align-items: center; padding: var(--s-md) var(--s-lg);
  border-bottom: 1px solid var(--c-border-light);
}
.qrow:last-child { border-bottom: none; }
.qrow__no {
  width: 40px; height: 40px; border-radius: var(--r-md);
  background: var(--c-brand-soft); color: var(--c-brand);
  display: flex; align-items: center; justify-content: center;
  font-size: var(--t-lg); font-weight: 700; font-variant-numeric: tabular-nums;
}
.qrow__no--teal { background: var(--c-teal-bg, var(--c-brand-soft)); color: var(--c-teal-dark, var(--c-brand)); }
.qrow__main { min-width: 0; }
.qrow__name { display: flex; align-items: center; gap: var(--s-xs); font-size: var(--t-base); font-weight: 600; color: var(--c-text); margin-bottom: 2px; }
.qrow__sub { font-size: var(--t-xs); color: var(--c-text-3); }
.qrow__wait { text-align: right; display: flex; flex-direction: column; align-items: flex-end; gap: 2px; }
.qrow__wait-time { font-size: var(--t-lg); font-weight: 700; color: var(--c-text-2); font-variant-numeric: tabular-nums; }
.qrow__wait-time--overdue { color: var(--c-danger-fg); }
.qrow__wait-hint { font-size: var(--t-xs); color: var(--c-text-3); }
.qrow__release { margin-top: 2px; }
.qrow__ops { display: flex; gap: var(--s-xs); }
.qrow__ops--col { flex-direction: column; align-items: stretch; }
.qrow__no--waitlist { background: var(--c-warning-bg, var(--c-brand-soft)); color: var(--c-warning-fg, var(--c-brand)); }
.qrow--notified { background: var(--c-warning-bg, var(--c-brand-soft)); }

/* 超时行高亮 */
.qrow--overdue { background: var(--c-warning-bg, var(--c-brand-soft)); }
.qrow--release { background: var(--c-danger-bg); }
.qrow--release .qrow__no { background: var(--c-danger-fg); color: #fff; }
.qrow--called { background: var(--c-brand-soft); }

/* 候补登记弹层（复用全局 modal-mask） */
.wl-modal { width: 520px; max-width: 100%; box-shadow: var(--shadow-pop); }
.wl-form { display: flex; flex-direction: column; gap: var(--s-md); }
.wl-form__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.wl-form__row--2 { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.wl-form__label { font-size: var(--t-xs); color: var(--c-text-3); }
.wl-req { color: var(--c-danger-fg); }
.wl-date {
  width: 100%; padding: 10px; border: 1px solid #D1D1D9; border-radius: var(--r-sm);
  background: var(--c-surface); font-size: 13px; color: var(--c-text);
}
.wl-date:focus { outline: none; border-color: #4D5AD9; box-shadow: 0 0 0 2px rgba(77, 90, 217, 0.12); }

@media (max-width: 1280px) {
  .q-kpis { grid-template-columns: repeat(3, 1fr); }
  .q-body { grid-template-columns: 1fr; }
}
@media (max-width: 834px) {
  .q-kpis { grid-template-columns: repeat(2, 1fr); }
  .q-body { grid-template-columns: 1fr; }
  .q-settings-hint { flex-wrap: wrap; }
  .q-settings-hint__sub { margin-left: 0; width: 100%; }
}
</style>
