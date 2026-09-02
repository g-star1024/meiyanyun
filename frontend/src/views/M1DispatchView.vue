<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import { useM1DispatchStore, type Assignment, type Job } from '@/stores/m1Dispatch'
import { useAuthStore } from '@/stores/auth'

const dp = useM1DispatchStore()
const auth = useAuthStore()
onMounted(() => dp.seed())

const canEdit = computed(() => auth.can('dispatch:edit'))

const resTab = ref<'DOCTOR' | 'ROOM' | 'DEVICE'>('DOCTOR')
const list = computed(() => dp.resources.filter((r) => r.type === resTab.value))

// 选中待派单（点 job 后再点空时段完成派单）
const activeJobId = ref('')
const activeJob = computed<Job | undefined>(() => dp.jobs.find((j) => j.id === activeJobId.value))
const dispatchMsg = ref('')

function pickJob(j: Job) {
  if (j.status !== 'PENDING') return
  activeJobId.value = activeJobId.value === j.id ? '' : j.id
  dispatchMsg.value = activeJobId.value ? `已选「${j.customerName} · ${j.itemName}」，点击时间格派单` : ''
}

function slotClick(resourceId: string, slot: string) {
  if (!activeJob.value || !canEdit.value) return
  const ok = dp.dispatch(activeJob.value.id, resourceId, slot)
  if (ok) {
    dispatchMsg.value = `✓ 已派单：${activeJob.value.customerName} → ${dp.resource(resourceId)?.name} ${slot}`
    activeJobId.value = ''
  } else {
    dispatchMsg.value = '✗ 该时段不可用（冲突/不在班次/时长超出）'
  }
}

function release(a: Assignment) {
  if (!canEdit.value) return
  dp.release(a.id)
  dispatchMsg.value = `已释放 ${a.customerName} 的排班，工单回到待派单`
}

// 时间格占用定位：计算一个排班块在时间轴上的 left/width（按分钟比例）
function blockStyle(a: Assignment) {
  const startMin = toMin(a.start) - 9 * 60 // 轴从 9:00
  const endMin = toMin(a.end) - 9 * 60
  const total = (20 - 9) * 60
  const leftPct = (Math.max(0, startMin) / total) * 100
  const widthPct = (Math.min(total, endMin) - Math.max(0, startMin)) / total * 100
  return { left: leftPct + '%', width: widthPct + '%' }
}
function toMin(t: string) { const [h, m] = t.split(':').map(Number); return h * 60 + m }

function asgTone(status: Assignment['status']) {
  return status === 'DONE' ? 'success' : status === 'IN_PROGRESS' ? 'warning' : 'info'
}

function utilTone(u: number) {
  if (u >= 85) return 'danger'
  if (u >= 60) return 'warning'
  if (u >= 30) return 'success'
  return 'disabled'
}
</script>

<template>
  <div class="dp-page">
    <div class="dp-kpis">
      <div class="kpi kpi--success"><div class="kpi__icon"><CIcon name="user-check" :size="20" /></div><div class="kpi__body"><div class="kpi__label">在岗医生</div><div class="kpi__value">{{ dp.stats.onDoctors }}</div></div></div>
      <div class="kpi kpi--info"><div class="kpi__icon"><CIcon name="store" :size="20" /></div><div class="kpi__body"><div class="kpi__label">可用治疗室</div><div class="kpi__value">{{ dp.stats.rooms }}</div></div></div>
      <div class="kpi kpi--warning"><div class="kpi__icon"><CIcon name="clock" :size="20" /></div><div class="kpi__body"><div class="kpi__label">待派单</div><div class="kpi__value">{{ dp.stats.pending }}<span v-if="dp.stats.urgent" class="kpi__sub kpi__sub--danger">{{ dp.stats.urgent }} 急</span></div></div></div>
      <div class="kpi kpi--brand"><div class="kpi__icon"><CIcon name="trend-up" :size="20" /></div><div class="kpi__body"><div class="kpi__label">医生平均利用率</div><div class="kpi__value">{{ dp.stats.avgUtil }}%</div></div></div>
    </div>

    <div class="dp-main">
      <!-- 左：待派单队列 -->
      <CCard padding="none" class="dp-queue">
        <div class="queue-head">
          <h4>待派单（{{ dp.pendingJobs.length }}）</h4>
          <CStatusPill v-if="activeJob" status="info" dot>选单中</CStatusPill>
        </div>
        <div class="queue-body">
          <div
            v-for="j in dp.pendingJobs" :key="j.id"
            class="job" :class="{ 'job--active': activeJobId === j.id, 'job--urgent': j.priority === 'URGENT' }"
            @click="pickJob(j)"
          >
            <div class="job__top">
              <span class="job__no">{{ j.jobNo.slice(-6) }}</span>
              <CStatusPill v-if="j.priority === 'URGENT'" status="danger" dot>加急</CStatusPill>
            </div>
            <div class="job__name">{{ j.customerName }} · {{ j.itemName }}</div>
            <div class="job__meta">
              <span><CIcon name="clock" :size="12" /> {{ j.durationMin }}分钟</span>
              <span v-if="j.preferredDoctor"><CIcon name="user" :size="12" /> 偏好 {{ j.preferredDoctor }}</span>
            </div>
          </div>
          <div v-if="dp.pendingJobs.length === 0" class="queue-empty">
            <CIcon name="check" :size="28" /><p>全部工单已派单</p>
          </div>
        </div>
        <div v-if="dispatchMsg" class="queue-msg" :class="{ 'queue-msg--ok': dispatchMsg.startsWith('✓') }">{{ dispatchMsg }}</div>
      </CCard>

      <!-- 右：排班时间轴 -->
      <CCard padding="none" class="dp-board">
        <div class="board-tabs">
          <button class="bt" :class="{ 'is-on': resTab === 'DOCTOR' }" @click="resTab = 'DOCTOR'">医生</button>
          <button class="bt" :class="{ 'is-on': resTab === 'ROOM' }" @click="resTab = 'ROOM'">治疗室</button>
          <button class="bt" :class="{ 'is-on': resTab === 'DEVICE' }" @click="resTab = 'DEVICE'">设备</button>
          <span class="board-hint">{{ activeJob ? '点击空白时段派单' : '先从左侧选择待派单' }}</span>
        </div>

        <!-- 时间刻度 -->
        <div class="ruler">
          <div class="ruler__label">资源</div>
          <div class="ruler__track">
            <span v-for="s in dp.SLOTS" :key="s" class="tick" :class="{ 'tick--hour': s.endsWith(':00') }">{{ s.endsWith(':00') ? s : '' }}</span>
          </div>
          <div class="ruler__util">利用率</div>
        </div>

        <div class="rows">
          <div v-for="r in list" :key="r.id" class="row" :class="{ 'row--off': r.status === 'OFF' }">
            <div class="row__label">
              <div class="res-name">{{ r.name }}<span v-if="r.title" class="res-title">{{ r.title }}</span></div>
              <div class="res-sub">{{ r.workStart }}-{{ r.workEnd }}<span v-if="r.room"> · {{ r.room }}</span></div>
            </div>
            <div class="row__track">
              <!-- 班次遮罩（工作时段外灰） -->
              <div
                class="shift"
                :style="{ left: (((toMin(r.workStart) - 540) / 660) * 100) + '%', width: (((toMin(r.workEnd) - toMin(r.workStart)) / 660) * 100) + '%' }"
              ></div>
              <!-- 时间格（可点击派单） -->
              <div class="cells">
                <div
                  v-for="s in dp.SLOTS.slice(0, -1)" :key="s"
                  class="cell"
                  :class="{ 'cell--busy': !!dp.isSlotBusy(r.id, s), 'cell--offshift': toMin(s) < toMin(r.workStart) || toMin(s) >= toMin(r.workEnd), 'cell--pickable': activeJob && r.status === 'ON' }"
                  @click="slotClick(r.id, s)"
                ></div>
              </div>
              <!-- 排班块 -->
              <div
                v-for="a in dp.assignmentsOf(r.id)" :key="a.id"
                class="block" :class="'block--' + asgTone(a.status)" :style="blockStyle(a)"
                :title="`${a.customerName} ${a.start}-${a.end} ${a.itemName}`"
              >
                <span class="block__time">{{ a.start }}</span>
                <span class="block__name">{{ a.customerName }}</span>
                <span class="block__item">{{ a.itemName }}</span>
                <button v-if="canEdit && a.status !== 'DONE'" class="block__x" @click.stop="release(a)">×</button>
              </div>
            </div>
            <div class="row__util">
              <div class="util-bar"><div class="util-bar__fill" :class="'util--' + utilTone(dp.utilization(r))" :style="{ width: dp.utilization(r) + '%' }"></div></div>
              <span class="util-num">{{ dp.utilization(r) }}%</span>
            </div>
          </div>
        </div>
        <div class="legend">
          <span><i class="lg lg--info"></i>已排</span>
          <span><i class="lg lg--warning"></i>进行中</span>
          <span><i class="lg lg--success"></i>已完成</span>
          <span class="muted">点击排班块右上角 × 可释放回待派单</span>
        </div>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.dp-page { display: flex; flex-direction: column; gap: var(--s-md); }
.dp-kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); }
.kpi { display: flex; align-items: center; gap: var(--s-md); padding: var(--s-md); border-radius: var(--r-xl); background: var(--c-surface); border: 1px solid var(--c-border-light); }
.kpi__icon { width: 44px; height: 44px; border-radius: var(--r-lg); display: flex; align-items: center; justify-content: center; flex: none; }
.kpi--brand .kpi__icon { background: var(--c-brand-soft); color: var(--c-brand); }
.kpi--info .kpi__icon { background: var(--c-info-bg, #EAF2FF); color: var(--c-info-fg); }
.kpi--success .kpi__icon { background: var(--c-success-bg, #f0fbf0); color: var(--c-success-fg); }
.kpi--warning .kpi__icon { background: var(--c-warning-bg, #FFF5E6); color: var(--c-warning-fg); }
.kpi__label { font-size: var(--t-xs); color: var(--c-text-3); }
.kpi__value { font-size: var(--t-xl); font-weight: 700; color: var(--c-text); display: flex; align-items: baseline; gap: 6px; }
.kpi__sub { font-size: var(--t-xs); font-weight: 400; color: var(--c-text-3); }
.kpi__sub--danger { color: var(--c-danger-fg); font-weight: 600; }

.dp-main { display: grid; grid-template-columns: 280px 1fr; gap: var(--s-md); align-items: start; }

.queue-head { display: flex; align-items: center; justify-content: space-between; padding: var(--s-md); border-bottom: 1px solid var(--c-border-light); }
.queue-head h4 { margin: 0; font-size: var(--t-md); font-weight: 700; }
.queue-body { max-height: calc(100vh - 320px); overflow-y: auto; padding: var(--s-sm); display: flex; flex-direction: column; gap: var(--s-sm); }
.job { padding: var(--s-sm) var(--s-md); border-radius: var(--r-md); border: 1px solid var(--c-border-light); cursor: pointer; transition: all .12s; }
.job:hover { border-color: var(--c-brand); background: var(--c-surface, #f7f8fa); }
.job--active { border-color: var(--c-brand); background: var(--c-brand-soft); box-shadow: 0 0 0 2px var(--c-brand-soft); }
.job--urgent { border-left: 3px solid var(--c-danger-fg); }
.job__top { display: flex; align-items: center; justify-content: space-between; }
.job__no { font-family: var(--t-number, monospace); font-size: 11px; color: var(--c-text-3); }
.job__name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); margin: 4px 0; }
.job__meta { display: flex; gap: var(--s-md); font-size: 11px; color: var(--c-text-3); }
.job__meta span { display: inline-flex; align-items: center; gap: 3px; }
.queue-empty { text-align: center; padding: var(--s-xl) var(--s-md); color: var(--c-text-3); }
.queue-empty .ci { margin-bottom: var(--s-sm); }
.queue-msg { padding: var(--s-sm) var(--s-md); font-size: var(--t-xs); border-top: 1px solid var(--c-border-light); background: var(--c-info-bg, #EAF2FF); color: var(--c-info-fg); }
.queue-msg--ok { background: var(--c-success-bg, #f0fbf0); color: var(--c-success-fg); }

.dp-board { display: flex; flex-direction: column; }
.board-tabs { display: flex; align-items: center; gap: var(--s-xs); padding: var(--s-sm) var(--s-md); border-bottom: 1px solid var(--c-border-light); }
.bt { border: none; background: none; padding: 6px 16px; font-size: var(--t-sm); font-weight: 600; color: var(--c-text-3); cursor: pointer; border-radius: var(--r-md); }
.bt:hover { color: var(--c-text); background: var(--c-surface, #f7f8fa); }
.bt.is-on { color: var(--c-brand); background: var(--c-brand-soft); }
.board-hint { margin-left: auto; font-size: var(--t-xs); color: var(--c-text-3); }

.ruler { display: grid; grid-template-columns: 140px 1fr 90px; align-items: center; padding: var(--s-sm) 0; border-bottom: 1px solid var(--c-border-light); background: var(--c-surface, #f7f8fa); position: sticky; top: 0; z-index: 2; }
.ruler__label { font-size: var(--t-xs); color: var(--c-text-3); padding-left: var(--s-md); font-weight: 600; }
.ruler__track { display: grid; grid-template-columns: repeat(22, 1fr); position: relative; height: 18px; }
.tick { font-size: 10px; color: var(--c-text-3); border-left: 1px dotted var(--c-border); padding-left: 2px; }
.tick--hour { color: var(--c-text-2); border-left: 1px solid var(--c-border); font-weight: 600; }
.ruler__util { font-size: var(--t-xs); color: var(--c-text-3); text-align: center; }

.rows { max-height: calc(100vh - 360px); overflow-y: auto; }
.row { display: grid; grid-template-columns: 140px 1fr 90px; align-items: stretch; border-bottom: 1px solid var(--c-border-light); min-height: 56px; }
.row:hover { background: var(--c-surface, #f7f8fa); }
.row--off { opacity: .5; }
.row__label { padding: var(--s-sm) var(--s-md); display: flex; flex-direction: column; justify-content: center; gap: 2px; border-right: 1px solid var(--c-border-light); }
.res-name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); display: flex; align-items: center; gap: 6px; }
.res-title { font-size: 11px; color: var(--c-text-3); font-weight: 400; }
.res-sub { font-size: 11px; color: var(--c-text-3); }
.row__track { position: relative; }
.shift { position: absolute; top: 4px; bottom: 4px; background: var(--c-surface, #f7f8fa); border-radius: var(--r-sm); z-index: 0; }
.cells { position: absolute; inset: 0; display: grid; grid-template-columns: repeat(22, 1fr); z-index: 1; }
.cell { border-right: 1px dotted var(--c-border-light); cursor: default; }
.cell--offshift { background: repeating-linear-gradient(45deg, transparent, transparent 4px, rgba(0,0,0,.02) 4px, rgba(0,0,0,.02) 8px); }
.cell--pickable:not(.cell--busy):not(.cell--offshift) { cursor: pointer; }
.cell--pickable:not(.cell--busy):not(.cell--offshift):hover { background: var(--c-brand-soft); }
.cell--busy { cursor: not-allowed; }
.block { position: absolute; top: 4px; bottom: 4px; border-radius: var(--r-md); padding: 4px 8px; font-size: 11px; color: #fff; overflow: hidden; z-index: 2; display: flex; flex-direction: column; gap: 1px; box-shadow: 0 1px 4px rgba(0,0,0,.12); }
.block--info { background: linear-gradient(135deg, var(--c-info-fg), #7BA7F5); }
.block--warning { background: linear-gradient(135deg, var(--c-warning-fg), #FFBE5C); }
.block--success { background: linear-gradient(135deg, var(--c-success-fg), #5DD37D); }
.block__time { font-weight: 700; font-size: 10px; opacity: .9; }
.block__name { font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.block__item { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; opacity: .9; }
.block__x { position: absolute; top: 2px; right: 4px; border: none; background: rgba(255,255,255,.3); color: #fff; width: 16px; height: 16px; border-radius: 50%; line-height: 1; cursor: pointer; font-size: 12px; padding: 0; }
.block__x:hover { background: rgba(255,255,255,.5); }
.row__util { display: flex; align-items: center; gap: 6px; padding: 0 var(--s-sm); border-left: 1px solid var(--c-border-light); }
.util-bar { flex: 1; height: 6px; background: var(--c-surface, #f7f8fa); border-radius: 3px; overflow: hidden; }
.util-bar__fill { height: 100%; border-radius: 3px; }
.util--success { background: var(--c-success-fg); }
.util--warning { background: var(--c-warning-fg); }
.util--danger { background: var(--c-danger-fg); }
.util--disabled { background: var(--c-text-3); }
.util-num { font-size: 11px; font-family: var(--t-number, monospace); color: var(--c-text-2); width: 32px; text-align: right; }

.legend { display: flex; align-items: center; gap: var(--s-md); padding: var(--s-sm) var(--s-md); border-top: 1px solid var(--c-border-light); font-size: var(--t-xs); color: var(--c-text-2); }
.legend .muted { margin-left: auto; color: var(--c-text-3); }
.lg { display: inline-block; width: 10px; height: 10px; border-radius: 3px; margin-right: 4px; vertical-align: middle; }
.lg--info { background: var(--c-info-fg); }
.lg--warning { background: var(--c-warning-fg); }
.lg--success { background: var(--c-success-fg); }

@media (max-width: 1024px) {
  .dp-kpis { grid-template-columns: repeat(2, 1fr); }
  .dp-main { grid-template-columns: 1fr; }
  .queue-body { max-height: none; }
}
</style>
