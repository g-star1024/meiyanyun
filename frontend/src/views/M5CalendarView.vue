<script setup lang="ts">
/* ============================================================
 * M5-09/10 会员日/节日营销 /m5-calendar
 * 自写月历网格 + 节点详情 + 排期弹层 + 周节奏柱状图
 * ============================================================ */
import { computed, onMounted, reactive, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CTextarea from '@/components/CTextarea.vue'
import CKpi from '@/components/CKpi.vue'
import CIcon from '@/components/CIcon.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CCheckbox from '@/components/CCheckbox.vue'
import CBarChart from '@/components/CBarChart.vue'
import {
  useM5CalendarStore,
  NODE_TYPE_LABEL,
  NODE_TYPE_PILL,
  SCHEDULE_STATUS_LABEL,
  SCHEDULE_STATUS_PILL,
  PUSH_CHANNEL_LABEL,
  type CalendarNode,
  type PushChannel,
} from '@/stores/m5Calendar'
import { useM1MarketingStore } from '@/stores/m1Marketing'
import { useAuthStore } from '@/stores/auth'

const store = useM5CalendarStore()
const m1 = useM1MarketingStore()
const auth = useAuthStore()

const canEdit = computed(() => auth.can('calendar:edit'))

onMounted(() => {
  store.seed()
  selectedNodes.value = store.nodesOfDay(todayStr)
})

// ---------- 月份切换 ----------
const today = new Date()
const cursor = ref(new Date(today.getFullYear(), today.getMonth(), 1))

const weekHeaders = ['一', '二', '三', '四', '五', '六', '日']
const monthLabel = computed(() => `${cursor.value.getFullYear()}年${cursor.value.getMonth() + 1}月`)

function prevMonth() {
  cursor.value = new Date(cursor.value.getFullYear(), cursor.value.getMonth() - 1, 1)
}
function nextMonth() {
  cursor.value = new Date(cursor.value.getFullYear(), cursor.value.getMonth() + 1, 1)
}

interface Cell {
  date: string
  day: number
  inMonth: boolean
  isToday: boolean
  nodes: CalendarNode[]
}

const todayStr = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`

const cells = computed<Cell[]>(() => {
  const y = cursor.value.getFullYear()
  const m = cursor.value.getMonth()
  const firstDay = new Date(y, m, 1)
  // JS: 周日=0，我们要周一=0
  const lead = (firstDay.getDay() + 6) % 7
  const daysInMonth = new Date(y, m + 1, 0).getDate()
  const list: Cell[] = []
  for (let i = 0; i < lead; i += 1) {
    const d = new Date(y, m, -lead + i + 1)
    list.push(buildCell(d, false))
  }
  for (let d = 1; d <= daysInMonth; d += 1) {
    list.push(buildCell(new Date(y, m, d), true))
  }
  // 补齐到 6 行（42 格）保持视觉稳定
  while (list.length % 7 !== 0 || list.length < 35) {
    const last = list[list.length - 1]
    const base = last ? new Date(last.date) : new Date(y, m, 0)
    base.setDate(base.getDate() + 1)
    list.push(buildCell(base, false))
  }
  return list
})

function buildCell(d: Date, inMonth: boolean): Cell {
  const y = d.getFullYear()
  const m = d.getMonth()
  const day = d.getDate()
  const date = `${y}-${String(m + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`
  return {
    date,
    day,
    inMonth,
    isToday: date === todayStr,
    nodes: store.nodesOfDay(date),
  }
}

const selectedDate = ref<string>(todayStr)
const selectedNodes = ref<CalendarNode[]>(store.nodesOfDay(todayStr))

function selectCell(cell: Cell) {
  selectedDate.value = cell.date
  selectedNodes.value = cell.nodes
}

const selectedNode = computed<CalendarNode | null>(() => selectedNodes.value[0] ?? null)
const selectedSchedules = computed(() =>
  selectedNode.value ? store.schedulesOfNode(selectedNode.value.id) : [],
)

const weeklyBars = computed(() => {
  const y = cursor.value.getFullYear()
  const m = cursor.value.getMonth()
  return store.weeklyScheduleOfMonth(y, m)
})

// ---------- KPI ----------
const kpis = computed(() => [
  { label: '本月营销节点', icon: 'settings', value: String(store.monthNodes.length), tone: 'brand' as const },
  { label: '进行中活动', icon: 'marketing', value: String(store.runningCount), tone: 'success' as const },
  { label: '待排期', icon: 'calendar', value: String(store.pendingCount), tone: 'warning' as const },
  { label: '预计带动营收', icon: 'finance', value: `¥${store.estimatedRevenue.toLocaleString('zh-CN')}`, tone: 'teal' as const },
])

// ---------- 排期弹层 ----------
const showSchedule = ref(false)
const scheduleTarget = ref<CalendarNode | null>(null)
const form = reactive({
  name: '',
  benefitDesc: '',
  couponIds: [] as string[],
  pointsReward: 100,
  startDate: '',
  endDate: '',
  channels: ['WECOM'] as PushChannel[],
  copyText: '',
  estimatedRevenue: 50000,
})
const formError = ref('')

function openSchedule(node: CalendarNode) {
  if (!canEdit.value) return
  scheduleTarget.value = node
  form.name = `${node.title}专属活动`
  form.benefitDesc = node.desc ?? ''
  form.couponIds = []
  form.pointsReward = 100
  form.startDate = node.date
  const end = new Date(node.date)
  end.setDate(end.getDate() + 2)
  form.endDate = end.toISOString().slice(0, 10)
  form.channels = ['WECOM']
  form.copyText = ''
  form.estimatedRevenue = 50000
  formError.value = ''
  showSchedule.value = true
}

function toggleCoupon(id: string) {
  const i = form.couponIds.indexOf(id)
  if (i >= 0) form.couponIds.splice(i, 1)
  else form.couponIds.push(id)
}
function toggleChannel(ch: PushChannel) {
  const i = form.channels.indexOf(ch)
  if (i >= 0) form.channels.splice(i, 1)
  else form.channels.push(ch)
}

function submitSchedule() {
  if (!scheduleTarget.value) return
  const r = store.createSchedule({
    nodeId: scheduleTarget.value.id,
    name: form.name,
    benefitDesc: form.benefitDesc,
    couponIds: form.couponIds,
    pointsReward: form.pointsReward,
    startDate: form.startDate,
    endDate: form.endDate,
    channels: form.channels,
    copyText: form.copyText,
    estimatedRevenue: form.estimatedRevenue,
  })
  if (!r.ok) {
    formError.value = r.reason ?? '提交失败'
    return
  }
  showSchedule.value = false
  selectedNodes.value = store.nodesOfDay(selectedDate.value)
  toast.value = '活动排期成功'
  setTimeout(() => (toast.value = ''), 2400)
}

const toast = ref('')

const allChannels = Object.entries(PUSH_CHANNEL_LABEL) as [PushChannel, string][]

function fmtMoney(n: number) {
  return `¥${n.toLocaleString('zh-CN')}`
}
</script>

<template>
  <div class="mc">
    <div class="mc__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <div class="mc__body">
      <!-- 左：月历 -->
      <CCard class="mc__cal-card" padding="lg">
        <template #header>
          <div class="cal-toolbar">
            <div class="cal-nav">
              <CButton variant="ghost" size="sm" @click="prevMonth"><CIcon name="chevron-left" :size="14" /></CButton>
              <span class="cal-title">{{ monthLabel }}</span>
              <CButton variant="ghost" size="sm" @click="nextMonth"><CIcon name="chevron-right" :size="14" /></CButton>
            </div>
            <div class="legend">
              <span class="legend-item"><span class="dot dot--member" />会员日</span>
              <span class="legend-item"><span class="dot dot--festival" />节日</span>
              <span class="legend-item"><span class="dot dot--campaign" />活动</span>
            </div>
            <CButton variant="primary" size="sm" class="cal-toolbar__btn" :disabled="!canEdit" @click="openSchedule(selectedNode ?? selectedNodes[0] ?? store.monthNodes[0])">
              <CIcon name="plus" :size="14" />配置活动
            </CButton>
          </div>
        </template>

        <div class="cal-grid cal-grid--head">
          <div v-for="w in weekHeaders" :key="w" class="cal-weekday">{{ w }}</div>
        </div>
        <div class="cal-grid cal-grid--days">
          <button
            v-for="(cell, i) in cells" :key="i"
            class="cal-cell"
            :class="{
              'is-out': !cell.inMonth,
              'is-today': cell.isToday,
              'is-selected': cell.date === selectedDate,
            }"
            @click="selectCell(cell)"
          >
            <span class="cal-cell__day">{{ cell.day }}</span>
            <span class="cal-cell__tags">
              <span
                v-for="n in cell.nodes" :key="n.id"
                class="node-tag"
                :class="`node-tag--${n.type}`"
              >
                <span class="node-tag__dot" />{{ n.title }}
              </span>
            </span>
          </button>
        </div>
      </CCard>

      <!-- 右：详情 -->
      <CCard class="mc__detail" padding="lg">
        <template #header>
          <h3 class="card-title"><CIcon name="calendar" :size="16" /> 节点详情</h3>
        </template>

        <div v-if="!selectedNode" class="empty">
          <CIcon name="calendar" :size="28" class="empty__icon" />
          <p>点击月历中有标记的日期，查看节点详情并排期活动。</p>
        </div>

        <div v-else class="detail">
          <div class="detail__head">
            <div>
              <div class="detail__date">{{ selectedNode.date }}</div>
              <div class="detail__title">{{ selectedNode.title }}</div>
            </div>
            <CStatusPill :status="NODE_TYPE_PILL[selectedNode.type]">
              {{ NODE_TYPE_LABEL[selectedNode.type] }}
            </CStatusPill>
          </div>
          <p v-if="selectedNode.desc" class="detail__desc">{{ selectedNode.desc }}</p>

          <div class="detail__section">
            <div class="detail__section-title">已排期活动（{{ selectedSchedules.length }}）</div>
            <div v-if="!selectedSchedules.length" class="detail__empty">该节点暂未排期活动</div>
            <div v-for="s in selectedSchedules" :key="s.id" class="sch-item">
              <div class="sch-item__top">
                <span class="sch-item__name">{{ s.name }}</span>
                <CStatusPill :status="SCHEDULE_STATUS_PILL[s.status]" :dot="true">
                  {{ SCHEDULE_STATUS_LABEL[s.status] }}
                </CStatusPill>
              </div>
              <div class="sch-item__meta">
                <span><CIcon name="clock" :size="12" />{{ s.startDate }} ~ {{ s.endDate }}</span>
                <span><CIcon name="volume" :size="12" />{{ s.channels.map(c => PUSH_CHANNEL_LABEL[c]).join(' / ') }}</span>
              </div>
              <div v-if="s.benefitDesc" class="sch-item__benefit">{{ s.benefitDesc }}</div>
              <div class="sch-item__foot">
                <span class="sch-item__by">{{ s.createdBy }} · {{ s.createdAt }}</span>
                <span class="sch-item__rev">预计 {{ fmtMoney(s.estimatedRevenue) }}</span>
              </div>
            </div>
          </div>

          <CButton block variant="primary" :disabled="!canEdit" @click="openSchedule(selectedNode)">
            <CIcon name="plus" :size="16" />为此节点排期活动
          </CButton>
        </div>
      </CCard>
    </div>

    <!-- 节奏看板 -->
    <CCard class="mc__rhythm" padding="lg">
      <template #header>
        <h3 class="card-title"><CIcon name="trend-up" :size="16" /> 当月各周活动排期节奏</h3>
      </template>
      <CBarChart :items="weeklyBars" :height="200" unit=" 场" />
    </CCard>

    <!-- 排期弹层 -->
    <div v-if="showSchedule" class="modal-mask" @click.self="showSchedule = false">
      <CCard class="modal" padding="lg" :title="`为「${scheduleTarget?.title}」排期活动`">
        <div class="form">
          <CInput v-model="form.name" label="活动名称" placeholder="如：会员日乔雅登满减" />
          <CTextarea v-model="form.benefitDesc" label="权益组合" placeholder="描述本次活动的权益组合，如：满 5000 减 800，双倍积分" :rows="2" />

          <div class="form-row">
            <CInput label="开始日期" type="text" :model-value="form.startDate" placeholder="YYYY-MM-DD"
              @update:model-value="form.startDate = $event" />
            <CInput label="结束日期" type="text" :model-value="form.endDate" placeholder="YYYY-MM-DD"
              @update:model-value="form.endDate = $event" />
          </div>

          <div class="form-row">
            <CInput label="积分奖励（分）" type="number" :model-value="String(form.pointsReward)"
              @update:model-value="form.pointsReward = Number($event) || 0" />
            <CInput label="预计营收（元）" type="number" :model-value="String(form.estimatedRevenue)"
              @update:model-value="form.estimatedRevenue = Number($event) || 0" />
          </div>

          <div class="fld">
            <label class="fld__label">关联优惠券（m1 活动券）</label>
            <div v-if="!m1.coupons.length" class="fld__hint">暂无可关联的券</div>
            <div v-else class="coupon-list">
              <label v-for="c in m1.coupons" :key="c.id" class="coupon-chip">
                <CCheckbox :model-value="form.couponIds.includes(c.id)" @update:model-value="toggleCoupon(c.id)" />
                <span>{{ c.name }}</span>
              </label>
            </div>
          </div>

          <div class="fld">
            <label class="fld__label">推送渠道</label>
            <div class="channel-list">
              <label v-for="[key, label] in allChannels" :key="key" class="channel-chip">
                <CCheckbox :model-value="form.channels.includes(key)" @update:model-value="toggleChannel(key)" />
                <span>{{ label }}</span>
              </label>
            </div>
          </div>

          <CTextarea v-model="form.copyText" label="排期推送文案（提交前自动校验违禁词）"
            placeholder="如：8 月会员日专属福利，仅此 3 天" :rows="3" />

          <div v-if="formError" class="form-error"><CIcon name="alert" :size="14" />{{ formError }}</div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showSchedule = false">取消</CButton>
          <CButton variant="primary" @click="submitSchedule"><CIcon name="check" :size="16" />确认排期</CButton>
        </template>
      </CCard>
    </div>

    <transition name="toast">
      <div v-if="toast" class="toast"><CIcon name="check" :size="16" />{{ toast }}</div>
    </transition>
  </div>
</template>

<style scoped>
.mc { display: flex; flex-direction: column; gap: var(--s-lg); }
.mc__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .mc__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }

.card-title { font-size: var(--t-md); font-weight: 700; margin: 0; display: flex; align-items: center; gap: var(--s-xs); }

.mc__body { display: grid; grid-template-columns: minmax(0, 1fr) 320px; gap: var(--s-lg); align-items: start; }

/* toolbar */
.cal-toolbar { display: flex; align-items: center; justify-content: space-between; gap: var(--s-md); width: 100%; flex-wrap: wrap; }
.cal-toolbar__btn { flex-shrink: 0; }
.cal-nav { display: inline-flex; align-items: center; gap: var(--s-sm); }
.cal-title { font-size: var(--t-md); font-weight: 700; min-width: 120px; text-align: center; }
.legend { display: inline-flex; align-items: center; gap: var(--s-md); }
.legend-item { display: inline-flex; align-items: center; gap: var(--s-xxs); font-size: var(--t-xs); color: var(--c-text-3); }
.dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }
.dot--member { background: var(--c-brand); }
.dot--festival { background: var(--c-orange-dark); }
.dot--campaign { background: var(--c-teal-dark); }

/* grid */
.cal-grid { display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); gap: 4px; }
.cal-grid--head { margin-bottom: var(--s-xs); }
.cal-weekday { text-align: center; font-size: var(--t-xs); color: var(--c-text-3); padding: var(--s-xs) 0; font-weight: 600; }
.cal-grid--days { grid-auto-rows: 92px; }
.cal-cell {
  display: flex; flex-direction: column; gap: 4px;
  padding: var(--s-xs);
  background: var(--c-bg-right, #f7f7fb);
  border: 1px solid transparent;
  border-radius: var(--r-md);
  text-align: left; cursor: pointer; min-width: 0;
  font-family: inherit;
  transition: border-color 0.15s, background 0.15s;
}
.cal-cell:hover { border-color: var(--c-brand-border); background: var(--c-surface); }
.cal-cell.is-out { background: transparent; opacity: 0.4; cursor: default; }
.cal-cell.is-out:hover { border-color: transparent; background: transparent; }
.cal-cell.is-today { border-color: var(--c-brand); }
.cal-cell.is-selected { background: var(--c-brand-soft); border-color: var(--c-brand); box-shadow: 0 0 0 1px var(--c-brand); }
.cal-cell__day { font-size: var(--t-xs); font-weight: 600; color: var(--c-text-2); line-height: 1; }
.cal-cell.is-today .cal-cell__day { color: var(--c-brand); }
.cal-cell__tags { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.node-tag {
  display: inline-flex; align-items: center; gap: 3px;
  font-size: 10px; line-height: 1.3;
  padding: 1px 4px; border-radius: var(--r-sm);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
  min-width: 0;
}
.node-tag__dot { width: 5px; height: 5px; border-radius: 50%; flex-shrink: 0; }
.node-tag--member { color: var(--c-brand); background: var(--c-brand-soft); }
.node-tag--member .node-tag__dot { background: var(--c-brand); }
.node-tag--festival { color: var(--c-orange-dark); background: var(--c-warning-bg); }
.node-tag--festival .node-tag__dot { background: var(--c-orange-dark); }
.node-tag--campaign { color: var(--c-teal-dark); background: var(--c-info-bg); }
.node-tag--campaign .node-tag__dot { background: var(--c-teal-dark); }

/* detail */
.detail { display: flex; flex-direction: column; gap: var(--s-md); }
.detail__head { display: flex; justify-content: space-between; align-items: flex-start; gap: var(--s-sm); }
.detail__date { font-size: var(--t-xs); color: var(--c-text-3); }
.detail__title { font-size: var(--t-lg); font-weight: 700; color: var(--c-text); margin-top: 2px; }
.detail__desc { margin: 0; font-size: var(--t-sm); color: var(--c-text-2); line-height: 1.6; }
.detail__section { display: flex; flex-direction: column; gap: var(--s-sm); border-top: 1px solid var(--c-border-light); padding-top: var(--s-md); }
.detail__section-title { font-size: var(--t-xs); color: var(--c-text-3); font-weight: 600; }
.detail__empty { font-size: var(--t-xs); color: var(--c-text-4); padding: var(--s-sm) 0; }
.sch-item { padding: var(--s-sm); background: var(--c-bg-right, #f7f7fb); border-radius: var(--r-md); display: flex; flex-direction: column; gap: 4px; }
.sch-item__top { display: flex; justify-content: space-between; align-items: center; gap: var(--s-xs); }
.sch-item__name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.sch-item__meta { display: flex; flex-wrap: wrap; gap: var(--s-md); font-size: var(--t-xs); color: var(--c-text-3); }
.sch-item__meta span { display: inline-flex; align-items: center; gap: 4px; }
.sch-item__benefit { font-size: var(--t-xs); color: var(--c-text-2); line-height: 1.5; }
.sch-item__foot { display: flex; justify-content: space-between; font-size: var(--t-xs); color: var(--c-text-3); }
.sch-item__rev { color: var(--c-brand); font-weight: 600; }
.empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xl) 0; color: var(--c-text-3); text-align: center; }
.empty__icon { color: var(--c-text-4); }
.empty p { margin: 0; font-size: var(--t-sm); }

.mc__rhythm :deep(.card__body) { padding-top: var(--s-md); }

/* form */
.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 560px; max-width: 100%; max-height: 90vh; overflow: auto; box-shadow: var(--shadow-pop); }
.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form-row { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.fld { display: flex; flex-direction: column; gap: var(--s-xs); }
.fld__label { font-size: var(--t-xs); color: var(--c-text-3); }
.fld__hint { font-size: var(--t-xs); color: var(--c-text-4); }
.coupon-list, .channel-list { display: flex; flex-wrap: wrap; gap: var(--s-sm); }
.coupon-chip, .channel-chip {
  display: inline-flex; align-items: center; gap: var(--s-xs);
  padding: var(--s-xs) var(--s-sm);
  background: var(--c-bg-right, #f7f7fb); border: 1px solid var(--c-border-light);
  border-radius: var(--r-capsule); font-size: var(--t-xs); color: var(--c-text-2); cursor: pointer;
}
.form-error {
  display: inline-flex; align-items: center; gap: var(--s-xs);
  padding: var(--s-xs) var(--s-sm); background: var(--c-danger-bg); color: var(--c-danger-fg);
  border-radius: var(--r-sm); font-size: var(--t-xs);
}

.toast {
  position: fixed; bottom: var(--s-xl); left: 50%; transform: translateX(-50%);
  display: inline-flex; align-items: center; gap: var(--s-xs);
  padding: var(--s-sm) var(--s-lg); background: var(--c-success-fg); color: #fff;
  border-radius: var(--r-capsule); font-size: var(--t-sm); font-weight: 600;
  box-shadow: var(--shadow-pop); z-index: 300;
}
.toast-enter-active, .toast-leave-active { transition: opacity 0.2s, transform 0.2s; }
.toast-enter-from, .toast-leave-to { opacity: 0; transform: translate(-50%, 10px); }
</style>
