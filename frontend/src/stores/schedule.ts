// ============================================================
// Schedule 排班考勤 store（M2-03）
// 周视图排班、考勤记录、请假/换班审批，为绩效/提成/智能排班提供基础数据。
// 对齐 docs/business-flows.md、permission-matrix.md。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

export type ShiftCode = 'OFF' | 'MORNING' | 'MID' | 'FULL' | 'LEAVE'
export type AttendanceStatus = 'NORMAL' | 'LATE' | 'EARLY' | 'ABSENT' | 'LEAVE'
export type LeaveStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

export interface Staff {
  id: string
  name: string
  role: string
  avatarColor: string
}

export interface AttendanceRecord {
  id: string
  staffId: string
  staffName: string
  date: string
  checkIn: string
  checkOut: string
  status: AttendanceStatus
  workHours: number
}

export interface LeaveRequest {
  id: string
  staffId: string
  staffName: string
  type: '年假' | '事假' | '病假' | '换班'
  startDate: string
  endDate: string
  reason: string
  status: LeaveStatus
  appliedAt: string
  reviewer?: string
}

export const SHIFTS: Record<ShiftCode, { label: string; time: string; color: string }> = {
  OFF: { label: '休', time: '休息', color: 'var(--c-text-3)' },
  MORNING: { label: '早', time: '09:00-18:00', color: 'var(--c-brand)' },
  MID: { label: '中', time: '12:00-21:00', color: 'var(--c-brand-secondary)' },
  FULL: { label: '全', time: '09:00-21:00', color: 'var(--c-warning-fg)' },
  LEAVE: { label: '假', time: '请假', color: 'var(--c-danger-fg)' },
}

function dayKey(date: Date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

export const useScheduleStore = defineStore('schedule', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const staff = ref<Staff[]>([])
  const shifts = ref<Record<string, ShiftCode>>({})
  const attendance = ref<AttendanceRecord[]>([])
  const leaves = ref<LeaveRequest[]>([])
  const weekOffset = ref(0)

  const weekStart = computed(() => {
    const now = new Date()
    const day = now.getDay() || 7
    const monday = new Date(now)
    monday.setDate(now.getDate() - day + 1 + weekOffset.value * 7)
    monday.setHours(0, 0, 0, 0)
    return monday
  })

  const days = computed(() => {
    return Array.from({ length: 7 }, (_, i) => {
      const d = new Date(weekStart.value)
      d.setDate(weekStart.value.getDate() + i)
      return {
        key: dayKey(d),
        label: ['周一', '周二', '周三', '周四', '周五', '周六', '周日'][i],
        date: `${d.getMonth() + 1}/${d.getDate()}`,
        isToday: dayKey(d) === dayKey(new Date()),
      }
    })
  })

  const pendingLeaves = computed(() => leaves.value.filter((l) => l.status === 'PENDING'))
  const todayOnDuty = computed(() => {
    const today = dayKey(new Date())
    return staff.value.filter((s) => {
      const code = shifts.value[`${s.id}-${today}`]
      return code && code !== 'OFF' && code !== 'LEAVE'
    }).length
  })
  const lateToday = computed(() => attendance.value.filter((a) => a.date === dayKey(new Date()) && a.status === 'LATE').length)
  const absentToday = computed(() => attendance.value.filter((a) => a.date === dayKey(new Date()) && a.status === 'ABSENT').length)

  function shiftOf(staffId: string, dateKey: string): ShiftCode {
    return shifts.value[`${staffId}-${dateKey}`] || 'OFF'
  }

  function setShift(staffId: string, dateKey: string, code: ShiftCode) {
    if (!auth.can('schedule:edit')) return
    shifts.value[`${staffId}-${dateKey}`] = code
    const s = staff.value.find((x) => x.id === staffId)
    activity.log(auth.user.name, `排班调整：${s?.name} ${dateKey} → ${SHIFTS[code].label}班`, staffId)
  }

  function approveLeave(id: string, approved: boolean) {
    const l = leaves.value.find((x) => x.id === id)
    if (!l || l.status !== 'PENDING') return
    if (!auth.can('schedule:approve')) return
    l.status = approved ? 'APPROVED' : 'REJECTED'
    l.reviewer = auth.user.name
    if (approved) {
      // 简单地将请假日期班次标为 LEAVE
      const start = new Date(l.startDate)
      const end = new Date(l.endDate)
      for (let d = new Date(start); d <= end; d.setDate(d.getDate() + 1)) {
        shifts.value[`${l.staffId}-${dayKey(d)}`] = 'LEAVE'
      }
    }
    activity.log(auth.user.name, `${approved ? '批准' : '驳回'}${l.staffName}的${l.type}申请`, id)
  }

  function attendanceOf(dateKey: string) {
    return attendance.value.filter((a) => a.date === dateKey)
  }

  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    const staffSeed: Staff[] = [
      { id: 'st-1', name: '苏晴', role: '店长/咨询', avatarColor: '#ff6b9e' },
      { id: 'st-2', name: '陈雅琳', role: '美容师', avatarColor: '#6b8aff' },
      { id: 'st-3', name: '李娜', role: '前台', avatarColor: '#52c41a' },
      { id: 'st-4', name: '周敏', role: '美容师', avatarColor: '#fa8c16' },
      { id: 'st-5', name: '吴桐', role: '库管/运营', avatarColor: '#8c5cf5' },
      { id: 'st-6', name: '林溪', role: '护士', avatarColor: '#13c2c2' },
    ]
    staff.value = staffSeed

    const pattern: ShiftCode[] = ['MORNING', 'MID', 'FULL', 'MORNING', 'MID', 'FULL', 'OFF']
    staffSeed.forEach((s, si) => {
      days.value.forEach((d, di) => {
        const code = pattern[(di + si) % 7]
        shifts.value[`${s.id}-${d.key}`] = code
      })
    })

    const today = dayKey(new Date())
    const records: Array<[number, AttendanceStatus, string, string, number]> = [
      [0, 'NORMAL', '08:55', '18:05', 9.2],
      [1, 'LATE', '09:23', '21:05', 11.2],
      [2, 'NORMAL', '08:48', '18:00', 9.2],
      [3, 'EARLY', '11:55', '20:18', 8.4],
      [4, 'ABSENT', '—', '—', 0],
      [5, 'NORMAL', '08:50', '21:00', 12.2],
    ]
    records.forEach(([si, status, ci, co, hours]) => {
      const s = staffSeed[si]
      attendance.value.push({
        id: nextId('at'),
        staffId: s.id,
        staffName: s.name,
        date: today,
        checkIn: ci,
        checkOut: co,
        status: status as AttendanceStatus,
        workHours: hours,
      })
    })

    leaves.value = [
      {
        id: nextId('lv'),
        staffId: 'st-2',
        staffName: '陈雅琳',
        type: '年假',
        startDate: days.value[5].key,
        endDate: days.value[6].key,
        reason: '家中有事，申请周末两天年假',
        status: 'PENDING',
        appliedAt: new Date(Date.now() - 86400000).toISOString(),
      },
      {
        id: nextId('lv'),
        staffId: 'st-4',
        staffName: '周敏',
        type: '换班',
        startDate: days.value[2].key,
        endDate: days.value[2].key,
        reason: '周三晚班与李娜换班',
        status: 'APPROVED',
        appliedAt: new Date(Date.now() - 172800000).toISOString(),
        reviewer: '苏晴',
      },
    ]
  }

  return {
    staff, shifts, attendance, leaves, weekOffset, weekStart, days,
    pendingLeaves, todayOnDuty, lateToday, absentToday,
    shiftOf, setShift, approveLeave, attendanceOf, SHIFTS, seed,
  }
})
