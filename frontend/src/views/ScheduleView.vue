<script setup lang="ts">
// M2-03 排班与考勤：周视图排班表（可点击换班）+ 今日考勤记录 + 请假/换班审批。
import { computed, onMounted } from 'vue'
import { useScheduleStore, type ShiftCode, type AttendanceStatus } from '@/stores/schedule'
import { useAuthStore } from '@/stores/auth'
import CKpi from '@/components/CKpi.vue'
import CCard from '@/components/CCard.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CButton from '@/components/CButton.vue'
import CIcon from '@/components/CIcon.vue'

const sc = useScheduleStore()
const auth = useAuthStore()
onMounted(() => sc.seed())

const canEdit = computed(() => auth.can('schedule:edit'))
const canApprove = computed(() => auth.can('schedule:approve'))

const kpi = computed(() => ({
  onDuty: sc.todayOnDuty,
  late: sc.lateToday,
  absent: sc.absentToday,
  pending: sc.pendingLeaves.length,
}))

const shiftCycle: ShiftCode[] = ['MORNING', 'MID', 'FULL', 'OFF', 'LEAVE']

function cycleShift(staffId: string, dateKey: string) {
  if (!canEdit.value) return
  const cur = sc.shiftOf(staffId, dateKey)
  const idx = shiftCycle.indexOf(cur)
  sc.setShift(staffId, dateKey, shiftCycle[(idx + 1) % shiftCycle.length])
}

function statusInfo(s: AttendanceStatus) {
  const map = {
    NORMAL: { status: 'success' as const, text: '正常' },
    LATE: { status: 'warning' as const, text: '迟到' },
    EARLY: { status: 'warning' as const, text: '早退' },
    ABSENT: { status: 'danger' as const, text: '缺勤' },
    LEAVE: { status: 'info' as const, text: '请假' },
  }
  return map[s]
}

function fmtWeekRange() {
  const s = sc.weekStart
  const e = new Date(s)
  e.setDate(s.getDate() + 6)
  return `${s.getMonth() + 1}/${s.getDate()} - ${e.getMonth() + 1}/${e.getDate()}`
}

function leaveTypePill(t: string) {
  if (t === '年假') return { status: 'primary' as const }
  if (t === '病假') return { status: 'danger' as const }
  if (t === '换班') return { status: 'info' as const }
  return { status: 'default' as const }
}
</script>

<template>
  <div class="sch">
    <!-- KPI -->
    <div class="sch__kpis">
      <CKpi :value="String(kpi.onDuty)" label="今日在岗" tone="success" icon="profile" />
      <CKpi :value="String(kpi.late)" label="今日迟到" tone="warning" icon="profile" />
      <CKpi :value="String(kpi.absent)" label="今日缺勤" tone="danger" icon="profile" />
      <CKpi :value="String(kpi.pending)" label="待批请假/换班" tone="brand" icon="profile" />
    </div>

    <div class="sch__body">
      <!-- 左：周排班表 -->
      <CCard padding="none" class="sch__grid-card">
        <div class="sch__head">
          <h3 class="sch__title">
            <CIcon name="calendar" :size="16" /> 本周排班
            <span class="sch__range">{{ fmtWeekRange() }}</span>
          </h3>
          <div class="sch__nav">
            <CButton variant="ghost" size="sm" @click="sc.weekOffset--"><CIcon name="chevron-left" :size="14" /></CButton>
            <CButton variant="ghost" size="sm" @click="sc.weekOffset = 0">本周</CButton>
            <CButton variant="ghost" size="sm" @click="sc.weekOffset++"><CIcon name="chevron-right" :size="14" /></CButton>
          </div>
        </div>
        <div class="sch__legend">
          <span v-for="(sh, code) in sc.SHIFTS" :key="code" class="lg">
            <i class="lg__dot" :style="{ background: sh.color }"></i>{{ sh.label }} {{ sh.time }}
          </span>
          <span v-if="canEdit" class="lg lg--hint">点击格子可循环切换班次</span>
        </div>
        <div class="sch__grid-wrap">
          <table class="grid">
            <thead>
              <tr>
                <th class="grid__staff">员工</th>
                <th v-for="d in sc.days" :key="d.key" :class="{ 'is-today': d.isToday }">
                  {{ d.label }}<span class="grid__date">{{ d.date }}</span>
                </th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="s in sc.staff" :key="s.id">
                <td class="grid__staff">
                  <span class="avatar" :style="{ background: s.avatarColor }">{{ s.name[0] }}</span>
                  <div>
                    <div class="staff__name">{{ s.name }}</div>
                    <div class="staff__role">{{ s.role }}</div>
                  </div>
                </td>
                <td v-for="d in sc.days" :key="d.key"
                    class="cell" :class="{ 'is-today': d.isToday, 'cell--off': sc.shiftOf(s.id, d.key) === 'OFF', 'cell--clickable': canEdit }"
                    @click="cycleShift(s.id, d.key)">
                  <span class="cell__shift" :style="{ color: sc.SHIFTS[sc.shiftOf(s.id, d.key)].color }">
                    {{ sc.SHIFTS[sc.shiftOf(s.id, d.key)].label }}
                  </span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </CCard>

      <!-- 右：考勤 + 请假审批 -->
      <div class="sch__side">
        <CCard title="今日考勤" padding="none" class="sch__att">
          <div class="att__rows">
            <div v-for="a in sc.attendanceOf(sc.days.find(d => d.isToday)?.key || '')" :key="a.id" class="att">
              <div class="att__left">
                <span class="att__name">{{ a.staffName }}</span>
                <span class="att__time">{{ a.checkIn }} - {{ a.checkOut }} · {{ a.workHours }}h</span>
              </div>
              <CStatusPill :status="statusInfo(a.status).status" dot>{{ statusInfo(a.status).text }}</CStatusPill>
            </div>
            <div v-if="sc.attendance.length === 0" class="att__empty">暂无考勤记录</div>
          </div>
        </CCard>

        <CCard title="请假 / 换班审批" padding="none" class="sch__leave">
          <div class="lv__rows">
            <div v-for="l in sc.leaves" :key="l.id" class="lv">
              <div class="lv__top">
                <span class="lv__name">{{ l.staffName }}</span>
                <CStatusPill v-if="l.status === 'PENDING'" status="warning">待审批</CStatusPill>
                <CStatusPill v-else-if="l.status === 'APPROVED'" status="success">已批准</CStatusPill>
                <CStatusPill v-else status="danger">已驳回</CStatusPill>
              </div>
              <div class="lv__meta">
                <span class="lv__type" :class="'lv__type--' + leaveTypePill(l.type).status">{{ l.type }}</span>
                <span>{{ l.startDate }} 至 {{ l.endDate }}</span>
              </div>
              <p class="lv__reason">{{ l.reason }}</p>
              <div v-if="l.status === 'PENDING' && canApprove" class="lv__ops">
                <CButton variant="danger" size="sm" @click="sc.approveLeave(l.id, false)">驳回</CButton>
                <CButton variant="primary" size="sm" @click="sc.approveLeave(l.id, true)">批准</CButton>
              </div>
              <div v-else-if="l.reviewer" class="lv__reviewer">审批人：{{ l.reviewer }}</div>
            </div>
          </div>
        </CCard>
      </div>
    </div>
  </div>
</template>

<style scoped>
.sch { display: flex; flex-direction: column; gap: var(--s-lg); }
.sch__kpis { display: flex; gap: var(--s-md); flex-wrap: wrap; }
.sch__kpis :deep(.ckpi) { flex: 1 1 0; min-width: 168px; }

.sch__body { display: grid; grid-template-columns: 1fr 340px; gap: var(--s-lg); align-items: start; }

/* 排班表 */
.sch__head { display: flex; justify-content: space-between; align-items: center; padding: var(--s-md) var(--s-lg); border-bottom: 1px solid var(--c-border); }
.sch__title { display: flex; align-items: center; gap: var(--s-xs); margin: 0; font-size: var(--t-md); font-weight: 600; }
.sch__range { font-size: var(--t-xs); color: var(--c-text-3); font-weight: 400; }
.sch__nav { display: flex; gap: var(--s-xxs); align-items: center; }
.sch__legend { display: flex; flex-wrap: wrap; gap: var(--s-md); padding: var(--s-sm) var(--s-lg); border-bottom: 1px solid var(--c-border); font-size: var(--t-xs); color: var(--c-text-2); }
.lg { display: inline-flex; align-items: center; gap: 4px; }
.lg__dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }
.lg--hint { color: var(--c-text-3); margin-left: auto; }
.sch__grid-wrap { overflow-x: auto; }
.grid { width: 100%; border-collapse: collapse; table-layout: fixed; }
.grid th { padding: var(--s-sm) var(--s-xs); font-size: var(--t-xs); font-weight: 600; color: var(--c-text-2); text-align: center; background: var(--c-surface-muted, #f7f8fa); border-bottom: 1px solid var(--c-border); }
.grid th.is-today { color: var(--c-brand); background: var(--c-brand-soft); }
.grid__date { display: block; font-weight: 400; color: var(--c-text-3); margin-top: 2px; }
.grid__staff { text-align: left !important; width: 140px; min-width: 140px; padding: var(--s-sm) var(--s-lg) !important; background: var(--c-surface-muted, #f7f8fa); position: sticky; left: 0; z-index: 1; }
.grid td { padding: 0; border-bottom: 1px solid var(--c-border-light); border-right: 1px solid var(--c-border-light); text-align: center; vertical-align: middle; height: 52px; }
.cell--clickable { cursor: pointer; transition: background .12s; }
.cell--clickable:hover { background: var(--c-brand-soft); }
.cell.is-today { background: var(--c-brand-soft); }
.cell--off .cell__shift { opacity: .35; }
.cell__shift { font-size: var(--t-md); font-weight: 700; font-variant-numeric: tabular-nums; }
.avatar { width: 28px; height: 28px; border-radius: 50%; color: #fff; display: inline-flex; align-items: center; justify-content: center; font-size: var(--t-xs); font-weight: 600; flex-shrink: 0; }
.staff__name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.staff__role { font-size: 10px; color: var(--c-text-3); }
td.grid__staff { display: flex; align-items: center; gap: var(--s-sm); text-align: left; }

/* 右侧 */
.sch__side { display: flex; flex-direction: column; gap: var(--s-lg); }
.att__rows, .lv__rows { padding: var(--s-xs) 0; max-height: 280px; overflow-y: auto; }
.att { display: flex; justify-content: space-between; align-items: center; padding: var(--s-sm) var(--s-lg); border-bottom: 1px solid var(--c-border-light); }
.att__name { font-size: var(--t-sm); font-weight: 600; display: block; }
.att__time { font-size: var(--t-xs); color: var(--c-text-3); }
.att__empty { text-align: center; color: var(--c-text-3); font-size: var(--t-sm); padding: var(--s-lg) 0; }

.lv { padding: var(--s-md) var(--s-lg); border-bottom: 1px solid var(--c-border); }
.lv:last-child { border-bottom: none; }
.lv__top { display: flex; justify-content: space-between; align-items: center; }
.lv__name { font-weight: 600; font-size: var(--t-sm); }
.lv__meta { display: flex; gap: var(--s-sm); align-items: center; margin: var(--s-xxs) 0; font-size: var(--t-xs); color: var(--c-text-3); }
.lv__type { padding: 1px 7px; border-radius: var(--r-sm); font-size: var(--t-xs); }
.lv__type--primary { background: var(--c-brand-soft); color: var(--c-brand); }
.lv__type--danger { background: var(--c-danger-bg); color: var(--c-danger-fg); }
.lv__type--info { background: rgba(107,138,255,.12); color: var(--c-brand-secondary); }
.lv__type--default { background: var(--c-surface-muted, #f0f2f5); color: var(--c-text-2); }
.lv__reason { margin: 0 0 var(--s-sm); font-size: var(--t-xs); color: var(--c-text-2); line-height: 1.5; }
.lv__ops { display: flex; gap: var(--s-xs); justify-content: flex-end; }
.lv__reviewer { font-size: 10px; color: var(--c-text-3); text-align: right; }

@media (max-width: 900px) {
  .sch__body { grid-template-columns: 1fr; }
}
</style>
