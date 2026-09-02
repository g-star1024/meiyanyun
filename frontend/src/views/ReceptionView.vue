<script setup lang="ts">
/* ============================================================
 * M4-05 接待台（/reception）
 * 到店登记 → 候诊队列（叫号）→ 分诊。数据源 arrival + customer store。
 * 候诊超时时长、跨店改派开关均取自 settings（不在页面写死）。
 * ============================================================ */
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useArrivalStore } from '@/stores/arrival'
import { useCustomerStore } from '@/stores/customer'
import { useActivityStore } from '@/stores/activity'
import { useSettingsStore } from '@/stores/settings'
import { ADVISORS, DOCTORS, staffName } from '@/config/staff'
import CKpi from '@/components/CKpi.vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CSelect from '@/components/CSelect.vue'
import CInput from '@/components/CInput.vue'
import CIcon from '@/components/CIcon.vue'
import type { Arrival, TriageType } from '@/types/domain'

const arrival = useArrivalStore()
const customer = useCustomerStore()
const activity = useActivityStore()
const settings = useSettingsStore()
const route = useRoute()

onMounted(() => arrival.seed())

// 客情登记「完成建档」跳转带回的新客户 id，候诊卡高亮定位
const newCustomerId = ref(typeof route.query.newId === 'string' ? route.query.newId : '')

const waiting = computed(() => arrival.waiting)
const triaged = computed(() => arrival.triaged)

// 候诊超时（分钟）取自设置中心
const timeoutMin = computed(() => settings.system.queue.waitingTimeoutMin)
const overdueIds = computed(() => new Set(arrival.overdue().map((a) => a.id)))

// 实时等待时长：30s 刷新一次 tick
const nowTick = ref(Date.now())
let timer: number | undefined
onMounted(() => {
  timer = window.setInterval(() => { nowTick.value = Date.now() }, 30000)
})
onUnmounted(() => { if (timer) window.clearInterval(timer) })

function waitMin(a: Arrival): number {
  const [h, m] = a.arrivedAt.split(':').map(Number)
  const d = new Date(nowTick.value)
  d.setHours(h, m, 0, 0)
  return Math.max(0, Math.round((nowTick.value - d.getTime()) / 60000))
}

// 队首 = 排队号最小的候诊客户（下一位接诊）
const headId = computed(() => {
  if (!waiting.value.length) return ''
  return [...waiting.value].sort((x, y) => x.queueNo - y.queueNo)[0].id
})

// 渠道色标
function channelMeta(ch: string): { label: string; cls: string } {
  switch (ch) {
    case 'WALK_IN': return { label: '自然到店', cls: 'walk' }
    case 'REFERRAL': return { label: '转介绍', cls: 'referral' }
    case 'MARKETING': return { label: '营销活动', cls: 'mkt' }
    default: return { label: '线上预约', cls: 'appt' }
  }
}
// 分诊类型色标
function triageTypeMeta(t?: string): { label: string; cls: string } {
  if (t === 'MEDICAL') return { label: '医生面诊', cls: 'medical' }
  if (t === 'SERVICE') return { label: '直接服务', cls: 'service' }
  return { label: '顾问咨询', cls: 'consult' }
}

// 已分诊时间线（带分诊单）
const triagedList = computed(() =>
  triaged.value.map((a) => ({ a, t: arrival.triageOf(a.id) })),
)

// ---- 到店登记 ----
const checkInQuery = ref('')
const customerOptions = computed(() =>
  customer.search(checkInQuery.value).map((c) => ({ label: `${c.name} ${c.phoneMask}`, value: c.id })),
)
const selectedCustomer = ref('')
function pickCustomer(c: { label: string; value: string }) {
  selectedCustomer.value = c.value
  checkInQuery.value = c.label
}
// 选中后再改搜索词，视为重新搜索，清掉旧选中
watch(checkInQuery, (q) => {
  if (!selectedCustomer.value) return
  const sel = customerOptions.value.find((c) => c.value === selectedCustomer.value)
  if (sel && q !== sel.label) selectedCustomer.value = ''
})
function doCheckIn() {
  if (!selectedCustomer.value) return
  arrival.checkIn({ customerId: selectedCustomer.value, channel: 'WALK_IN' })
  selectedCustomer.value = ''
  checkInQuery.value = ''
}

// ---- 分诊 ----
const triageTarget = ref('')
const triageType = ref<TriageType>('CONSULT')
const triageAssign = ref('')
const triageNote = ref('')
const assignOptions = computed(() =>
  (triageType.value === 'MEDICAL' ? DOCTORS : ADVISORS).map((s) => ({
    label: `${s.name}（${s.title}）`,
    value: s.id,
  })),
)
function openTriage(id: string) {
  triageTarget.value = id
  triageType.value = 'CONSULT'
  triageAssign.value = ADVISORS[0].id
  triageNote.value = ''
}
function onTypeChange() {
  triageAssign.value = triageType.value === 'MEDICAL' ? DOCTORS[0].id : ADVISORS[0].id
}
function confirmTriage() {
  if (!triageTarget.value || !triageAssign.value) return
  arrival.triage(triageTarget.value, {
    type: triageType.value,
    assignedTo: triageAssign.value,
    note: triageNote.value,
  })
  triageTarget.value = ''
}
</script>

<template>
  <div class="rec">
    <div class="rec__kpis">
      <CKpi :value="String(waiting.length)" label="候诊中" tone="warning" icon="calendar" />
      <CKpi :value="String(triaged.length)" label="已分诊/入位" tone="brand" icon="user-check" />
      <CKpi :value="String(overdueIds.size)" label="超时候诊" tone="danger" icon="alert" />
      <CKpi :value="timeoutMin + ' 分'" label="候诊超时时长" tone="text" icon="alert" />
    </div>

    <div class="rec__main">
      <!-- 左：到店登记 + 候诊队列 -->
      <CCard class="rec__col">
        <template #header>
          <h3 class="card__title">
            到店登记 / 候诊队列
            <span class="rec__count" :class="{ 'is-zero': !waiting.length }">{{ waiting.length }}</span>
          </h3>
          <div class="rec__head-tools">
            <div class="rec__search">
              <CInput v-model="checkInQuery" placeholder="搜索客户姓名 / 手机号登记到店" />
              <ul v-if="checkInQuery && !selectedCustomer && customerOptions.length" class="rec__search-pop">
                <li v-for="c in customerOptions" :key="c.value" @click="pickCustomer(c)">
                  {{ c.label }}
                </li>
              </ul>
              <div v-if="checkInQuery && !selectedCustomer && !customerOptions.length" class="rec__search-pop rec__search-pop--hint">
                未找到客户，先到「客情登记」建档
              </div>
            </div>
            <CButton
              v-perm="'reception:edit'"
              variant="primary"
              size="sm"
              class="rec__checkin-btn"
              :disabled="!selectedCustomer"
              @click="doCheckIn"
            >
              <CIcon name="plus" :size="14" />到店登记
            </CButton>
          </div>
        </template>

        <!-- 候诊队列（叫号） -->
        <div class="queue-list">
          <div
            v-for="a in waiting"
            :key="a.id"
            class="qcard"
            :class="{
              'qcard--head': a.id === headId,
              'qcard--overdue': overdueIds.has(a.id),
              'qcard--new': a.customerId === newCustomerId,
            }"
          >
            <!-- 大号排队号牌 -->
            <div class="qcard__no">
              <span class="qcard__no-num">{{ String(a.queueNo).padStart(2, '0') }}</span>
              <span v-if="a.id === headId" class="qcard__no-tag">下一位</span>
            </div>

            <div class="qcard__body">
              <div class="qcard__row1">
                <span class="qcard__name">{{ customer.nameOf(a.customerId) }}</span>
                <span v-if="a.customerId === newCustomerId" class="qcard__newtag">新建档</span>
                <span class="qcard__time">{{ a.arrivedAt }} 到店</span>
                <CStatusPill :status="overdueIds.has(a.id) ? 'danger' : 'warning'" class="qcard__pill">
                  {{ overdueIds.has(a.id) ? '候诊超时' : '候诊中' }}
                </CStatusPill>
              </div>
              <div class="qcard__row2">
                <span class="chan-chip" :class="'chan-chip--' + channelMeta(a.channel).cls">
                  {{ channelMeta(a.channel).label }}
                </span>
                <span
                  class="qcard__wait"
                  :class="{ 'is-overdue': overdueIds.has(a.id) }"
                >
                  <CIcon name="clock" :size="13" />
                  已等待 {{ waitMin(a) }} 分钟<span v-if="overdueIds.has(a.id)"> · 请优先接待</span>
                </span>
              </div>

              <!-- 分诊表单 / 按钮 -->
              <div v-if="triageTarget === a.id" class="triage-form">
                <div class="triage-form__row">
                  <span class="triage-form__lbl">类型</span>
                  <CSelect v-model="triageType" :options="[
                    { label: '顾问咨询', value: 'CONSULT' },
                    { label: '医生面诊', value: 'MEDICAL' },
                    { label: '直接服务', value: 'SERVICE' },
                  ]" width="120px" @update:modelValue="onTypeChange" />
                  <span class="triage-form__lbl">分配</span>
                  <CSelect v-model="triageAssign" :options="assignOptions" width="150px" />
                </div>
                <CInput v-model="triageNote" placeholder="分诊备注（可选）" />
                <div class="triage-form__actions">
                  <CButton variant="ghost" size="sm" @click="triageTarget = ''">取消</CButton>
                  <CButton variant="primary" size="sm" @click="confirmTriage">确认分诊</CButton>
                </div>
              </div>
              <CButton
                v-else
                v-perm.disable="'reception:edit'"
                :variant="a.id === headId ? 'primary' : 'secondary'"
                size="sm"
                block
                @click="openTriage(a.id)"
              >
                {{ a.id === headId ? '分诊接待' : '分诊' }}
              </CButton>
            </div>
          </div>

          <div v-if="!waiting.length" class="rec__empty">
            <CIcon name="user-check" :size="28" class="rec__empty-icon" />
            <span>暂无候诊客户，到店登记后将进入叫号队列</span>
          </div>
        </div>
      </CCard>

      <!-- 右：已分诊时间线 + 活动流水 -->
      <CCard title="已分诊 / 实时动态" class="rec__col rec__col--side">
        <div v-if="triagedList.length" class="tl">
          <div v-for="({ a, t }) in triagedList" :key="a.id" class="tl__item">
            <span class="tl__dot" :class="'tl__dot--' + triageTypeMeta(t?.type).cls" />
            <div class="tl__body">
              <div class="tl__row1">
                <span class="tl__name">{{ customer.nameOf(a.customerId) }}</span>
                <span class="type-chip" :class="'type-chip--' + triageTypeMeta(t?.type).cls">
                  {{ triageTypeMeta(t?.type).label }}
                </span>
              </div>
              <div class="tl__row2">
                <CIcon name="user" :size="12" />
                {{ staffName(t?.assignedTo) }}
                <span class="tl__time">· {{ a.arrivedAt }}</span>
              </div>
            </div>
          </div>
        </div>
        <div v-else class="rec__empty rec__empty--sm">暂无已分诊记录</div>

        <div class="rec__feed">
          <div class="rec__feed-title">实时动态</div>
          <div v-for="act in activity.items.slice(0, 12)" :key="act.id" class="feed__item">
            <span class="feed__time">{{ act.at }}</span>
            <span class="feed__text"><b>{{ act.actor }}</b> {{ act.text }}</span>
          </div>
        </div>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.rec { display: flex; flex-direction: column; gap: var(--s-md); }
.rec__kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); }
.rec__main { display: flex; gap: var(--s-md); align-items: flex-start; }
.rec__col { flex: 1; min-width: 0; }
.rec__col--side { max-width: 360px; }

/* 卡片标题计数徽标 */
.rec__count { display: inline-flex; align-items: center; justify-content: center; min-width: 20px; height: 20px; padding: 0 6px; margin-left: var(--s-xs); border-radius: var(--r-pill); background: var(--c-brand); color: #fff; font-size: var(--t-xs); font-weight: 700; vertical-align: middle; }
.rec__count.is-zero { background: var(--c-border); color: var(--c-text-3); }

/* 卡片头部工具：搜索（autocomplete）+ 到店登记 */
.rec__head-tools { display: flex; align-items: center; gap: var(--s-sm); margin-left: auto; flex-shrink: 0; }
.rec__search { position: relative; width: 260px; }
.rec__search-pop { position: absolute; top: calc(100% + 4px); left: 0; right: 0; z-index: 60; margin: 0; padding: var(--s-xxs); list-style: none; background: var(--c-surface); border: 1px solid var(--c-border-light); border-radius: var(--r-md); box-shadow: var(--shadow-pop); max-height: 240px; overflow-y: auto; }
.rec__search-pop li { padding: var(--s-xs) var(--s-sm); border-radius: var(--r-sm); font-size: var(--t-sm); color: var(--c-text-2); cursor: pointer; }
.rec__search-pop li:hover { background: var(--c-brand-soft); color: var(--c-text); }
.rec__search-pop--hint { padding: var(--s-sm); font-size: var(--t-sm); color: var(--c-warning-fg); cursor: default; }
.rec__search-pop--hint:hover { background: var(--c-surface); }
.rec__checkin-btn { white-space: nowrap; }

/* 候诊队列 */
.queue-list { display: flex; flex-direction: column; gap: var(--s-sm); }
.qcard { display: flex; gap: var(--s-sm); padding: var(--s-sm) var(--s-md); border: 1px solid var(--c-border-light); border-radius: var(--r-lg); background: var(--c-surface); transition: box-shadow .15s, border-color .15s; }
.qcard:hover { box-shadow: var(--shadow-card); }

/* 大号排队号牌 */
.qcard__no { flex-shrink: 0; width: 52px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 3px; border-radius: var(--r-md); background: var(--c-brand-soft); color: var(--c-brand); align-self: stretch; }
.qcard__no-num { font-size: 24px; font-weight: 700; line-height: 1; font-variant-numeric: tabular-nums; }
.qcard__no-tag { font-size: 10px; font-weight: 600; line-height: 1; padding: 2px 6px; border-radius: var(--r-pill); background: var(--c-brand); color: #fff; }

/* 队首高亮 */
.qcard--head { border-color: var(--c-brand); box-shadow: 0 0 0 1px var(--c-brand-border); }
.qcard--head .qcard__no { background: var(--c-brand); color: #fff; }

/* 超时红条 + 脉冲 */
.qcard--overdue { border-color: var(--c-danger-fg); background: var(--c-danger-bg); border-left: 3px solid var(--c-danger-fg); }
.qcard--overdue .qcard__no { background: var(--c-danger-fg); color: #fff; }

/* 新建档高亮 */
.qcard--new { border-color: var(--c-brand-border); background: var(--c-brand-soft); }
.qcard__newtag { font-size: var(--t-xs); font-weight: 600; color: #fff; background: var(--c-brand); border-radius: var(--r-pill); padding: 1px 7px; line-height: 16px; }

.qcard__body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: var(--s-xs); }
.qcard__row1 { display: flex; align-items: center; gap: var(--s-xs); flex-wrap: wrap; }
.qcard__name { font-weight: 700; font-size: var(--t-base); }
.qcard__time { font-size: var(--t-xs); color: var(--c-text-3); font-variant-numeric: tabular-nums; }
.qcard__pill { margin-left: auto; }
.qcard__row2 { display: flex; align-items: center; gap: var(--s-sm); flex-wrap: wrap; }

/* 渠道色标 chip */
.chan-chip { display: inline-flex; align-items: center; font-size: var(--t-xs); font-weight: 600; padding: 2px 8px; border-radius: var(--r-pill); line-height: 16px; }
.chan-chip--walk { background: var(--c-info-bg); color: var(--c-info-fg); }
.chan-chip--appt { background: var(--c-warning-bg); color: var(--c-warning-fg); }
.chan-chip--referral { background: var(--c-teal-bg); color: var(--c-teal-fg); }
.chan-chip--mkt { background: var(--c-purple-soft); color: var(--c-purple); }

/* 等待时长 */
.qcard__wait { display: inline-flex; align-items: center; gap: 3px; font-size: var(--t-xs); color: var(--c-text-3); font-variant-numeric: tabular-nums; }
.qcard__wait.is-overdue { color: var(--c-danger-fg); font-weight: 700; animation: rec-pulse 1.4s ease-in-out infinite; }
@keyframes rec-pulse { 0%, 100% { opacity: 1; } 50% { opacity: .55; } }

.qcard :deep(.cbtn) { margin-top: 2px; }

/* 分诊表单 */
.triage-form { display: flex; flex-direction: column; gap: var(--s-xs); border-top: 1px dashed var(--c-border); padding-top: var(--s-sm); margin-top: 2px; }
.triage-form__row { display: flex; align-items: center; gap: var(--s-xs); flex-wrap: wrap; }
.triage-form__lbl { font-size: var(--t-xs); color: var(--c-text-2); }
.triage-form__actions { display: flex; gap: var(--s-xs); justify-content: flex-end; }

/* 空态 */
.rec__empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-xs); color: var(--c-text-3); font-size: var(--t-sm); text-align: center; padding: var(--s-xl) 0; }
.rec__empty--sm { padding: var(--s-md) 0; }
.rec__empty-icon { color: var(--c-border); }

/* 已分诊时间线 */
.tl { display: flex; flex-direction: column; }
.tl__item { position: relative; display: flex; gap: var(--s-sm); padding: 0 0 var(--s-md) 0; }
.tl__item:not(:last-child)::before { content: ''; position: absolute; left: 7px; top: 18px; bottom: 2px; width: 2px; background: var(--c-border-light); }
.tl__dot { position: relative; z-index: 1; flex-shrink: 0; width: 16px; height: 16px; margin-top: 2px; border-radius: 50%; border: 3px solid var(--c-surface); box-shadow: 0 0 0 1px var(--c-border); }
.tl__dot--consult { background: var(--c-brand); }
.tl__dot--medical { background: var(--c-purple); }
.tl__dot--service { background: var(--c-teal); }
.tl__body { flex: 1; min-width: 0; }
.tl__row1 { display: flex; align-items: center; gap: var(--s-xs); }
.tl__name { font-weight: 600; font-size: var(--t-sm); }
.type-chip { font-size: var(--t-xs); font-weight: 600; padding: 1px 7px; border-radius: var(--r-pill); line-height: 16px; }
.type-chip--consult { background: var(--c-brand-soft); color: var(--c-brand); }
.type-chip--medical { background: var(--c-purple-soft); color: var(--c-purple); }
.type-chip--service { background: var(--c-teal-bg); color: var(--c-teal-fg); }
.tl__row2 { display: flex; align-items: center; gap: 3px; font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }
.tl__time { font-variant-numeric: tabular-nums; }

/* 活动流水 */
.rec__feed { margin-top: var(--s-sm); border-top: 1px solid var(--c-border); padding-top: var(--s-sm); max-height: 300px; overflow-y: auto; }
.rec__feed-title { font-size: var(--t-xs); font-weight: 600; color: var(--c-text-3); margin-bottom: var(--s-xs); }
.feed__item { display: flex; gap: var(--s-xs); padding: 4px 0; font-size: var(--t-sm); line-height: var(--lh-sm); }
.feed__time { color: var(--c-text-3); flex-shrink: 0; font-variant-numeric: tabular-nums; }
.feed__text { color: var(--c-text-2); }
.feed__text b { color: var(--c-text); }

@media (max-width: 1024px) {
  .rec__kpis { grid-template-columns: repeat(2, 1fr); }
  .rec__main { flex-direction: column; align-items: stretch; }
  .rec__col, .rec__col--side { max-width: none; width: 100%; }
  .rec__head-tools { width: 100%; }
  .rec__search { flex: 1; width: auto; }
}
</style>
