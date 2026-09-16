// ============================================================
// Schedule 排班考勤 store（M2-03，B54 卡3/卡5 接真）
// 周视图排班已接真实 org-service（/api/org/schedule）：周视图读 + 单日改班 + 周例铺底 + 复制上周；
// 请假域（B54 卡5）已接真：登记 / 列表 / 批准（联动写 LEAVE OVERRIDE）/ 驳回。
//
// 诚实空态（铁律：不造后端没有的字段/功能）：
//  - 考勤打卡后端尚无端点，attendance 恒空，KPI 迟到/缺勤恒 0，「今日考勤」卡片维持空态。
//  - 未排班日期后端不返回行，shiftOf 返 NONE（区别于 OFF 休息），不替用户造班。
//  - 请假类型只收 年假/事假/病假（换班历史占位不接收新登记）；LEAVE 只能由真实请假单批准产生。
//
// 班次五态唯一事实源在后端 ScheduleController.SHIFT_CODES（字典）与 internal resolve 钟点窗；
// 下方 SHIFTS 常量仅做镜像（文案/颜色/钟点），卡4 M1 时间轴改走 resolve 真窗。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'
import { useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { useToast } from '@/composables/useToast'
import { errMsg } from './m5Coupon'
import {
  getScheduleWeek, setStaffShift, generateWeek as apiGenerateWeek, copyWeek as apiCopyWeek,
  listLeaves, applyLeave as apiApplyLeave, approveLeave as apiApproveLeave,
  rejectLeave as apiRejectLeave,
  type ShiftCode as ApiShiftCode,
  type ScheduleStaffDTO,
  type LeaveRequestDTO,
  type LeaveApplyCmd,
} from '@/api/schedule'

/** 五态 + NONE 空态（NONE 仅前端表示「未排班」，不提交后端、不参与循环切换）。 */
export type ShiftCode = ApiShiftCode | 'NONE'
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
  leaveNo: string
  staffId: string
  staffName: string
  type: '年假' | '事假' | '病假'
  startDate: string
  endDate: string
  reason: string
  status: LeaveStatus
  appliedAt: string
  reviewer?: string
  rejectReason?: string
}

/** 镜像后端 ScheduleController：文案/可派钟点窗以后端为事实源，颜色为前端展示层唯一增量。 */
export const SHIFTS: Record<ShiftCode, { label: string; time: string; color: string }> = {
  MORNING: { label: '上午', time: '09:00-14:00', color: 'var(--c-brand)' },
  MID: { label: '下午', time: '14:00-20:00', color: 'var(--c-brand-secondary)' },
  FULL: { label: '全天', time: '09:00-20:00', color: 'var(--c-warning-fg)' },
  OFF: { label: '休', time: '休息', color: 'var(--c-text-3)' },
  LEAVE: { label: '假', time: '请假', color: 'var(--c-danger-fg)' },
  NONE: { label: '未排', time: '', color: 'var(--c-text-3)' },
}

/** 内置 8 角色码 → 中文名（与 PermissionMatrix/RoleDef 同源；自定义角色兜底显码）。 */
const ROLE_LABELS: Record<string, string> = {
  SUPER_ADMIN: '超管',
  REGION_MGR: '区域经理',
  STORE_MGR: '店长',
  CONSULTANT: '咨询师',
  DOCTOR: '医生',
  FRONT_DESK: '前台',
  OPERATOR: '运营',
  FINANCE: '财务',
}

const AVATAR_COLORS = ['#ff6b9e', '#6b8aff', '#52c41a', '#fa8c16', '#8c5cf5', '#13c2c2']

function avatarColorOf(id: string): string {
  let h = 0
  for (let i = 0; i < id.length; i++) h = (h * 31 + id.charCodeAt(i)) >>> 0
  return AVATAR_COLORS[h % AVATAR_COLORS.length]
}

function dayKey(date: Date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

function adaptStaff(s: ScheduleStaffDTO): Staff {
  return {
    id: s.staffId,
    name: s.staffName,
    role: ROLE_LABELS[s.roleCode] ?? s.roleCode,
    avatarColor: avatarColorOf(s.staffId),
  }
}

function adaptLeave(l: LeaveRequestDTO): LeaveRequest {
  return {
    id: String(l.id),
    leaveNo: l.leaveNo,
    staffId: l.staffId,
    staffName: l.staffName,
    type: l.type as LeaveRequest['type'],
    startDate: l.startDate,
    endDate: l.endDate,
    reason: l.reason,
    status: l.status as LeaveStatus,
    appliedAt: l.appliedAt,
    reviewer: l.reviewerName ?? undefined,
    rejectReason: l.rejectReason ?? undefined,
  }
}

export const useScheduleStore = defineStore('schedule', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()
  const toast = useToast()

  const staff = ref<Staff[]>([])
  const shifts = ref<Record<string, ApiShiftCode>>({})
  const sources = ref<Record<string, string>>({})
  const attendance = ref<AttendanceRecord[]>([])
  const leaves = ref<LeaveRequest[]>([])
  const weekOffset = ref(0)
  const loading = ref(false)
  const loadedWeekKey = ref('')

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
      return code === 'MORNING' || code === 'MID' || code === 'FULL'
    }).length
  })
  const lateToday = computed(() => attendance.value.filter((a) => a.date === dayKey(new Date()) && a.status === 'LATE').length)
  const absentToday = computed(() => attendance.value.filter((a) => a.date === dayKey(new Date()) && a.status === 'ABSENT').length)

  function shiftOf(staffId: string, dateKey: string): ShiftCode {
    return shifts.value[`${staffId}-${dateKey}`] || 'NONE'
  }

  /** 周网格点选循环：乐观更新本地，失败回退并 toast 后端中文错误。NONE 空态不入后端。 */
  async function setShift(staffId: string, dateKey: string, code: ShiftCode): Promise<boolean> {
    if (!auth.can('schedule:edit') || code === 'NONE') return false
    const key = `${staffId}-${dateKey}`
    const prev = shifts.value[key]
    shifts.value[key] = code
    sources.value[key] = 'OVERRIDE'
    try {
      const res = await setStaffShift({ staffId, shiftDate: dateKey, shiftCode: code })
      sources.value[key] = res.data.source
      const s = staff.value.find((x) => x.id === staffId)
      activity.log(auth.user.name, `排班调整：${s?.name ?? staffId} ${dateKey} → ${SHIFTS[code].label}`, staffId)
      return true
    } catch (e) {
      if (prev) shifts.value[key] = prev
      else delete shifts.value[key]
      toast.error(errMsg(e, '排班调整失败'))
      return false
    }
  }

  /**
   * 请假审批：批准调 approve（后端区间联动写 LEAVE OVERRIDE，随后重载周网格显假）；
   * 驳回调 reject（不动排班）。成功后刷新请假列表，错误透传后端中文文案。
   */
  async function decideLeave(id: string, approved: boolean): Promise<boolean> {
    if (!auth.can('schedule:approve')) return false
    try {
      if (approved) {
        const res = await apiApproveLeave(id)
        toast.success(`已批准 ${res.data.leaveNo}，请假期间班次已置为请假`)
        const s = staff.value.find((x) => x.id === res.data.staffId)
        activity.log(auth.user.name, `请假批准：${s?.name ?? res.data.staffName} ${res.data.startDate} 至 ${res.data.endDate}`, res.data.leaveNo)
        await load()
      } else {
        const res = await apiRejectLeave(id)
        toast.success(`已驳回 ${res.data.leaveNo}`)
        const s = staff.value.find((x) => x.id === res.data.staffId)
        activity.log(auth.user.name, `请假驳回：${s?.name ?? res.data.staffName} ${res.data.leaveNo}`, res.data.leaveNo)
      }
      await loadLeaves()
      return true
    } catch (e) {
      toast.error(errMsg(e, approved ? '批准失败' : '驳回失败'))
      return false
    }
  }

  /** 兼容视图既有签名：approveLeave(id, approved) 委托 decideLeave。 */
  function approveLeave(id: string, approved: boolean) {
    void decideLeave(id, approved)
  }

  /** 请假登记（schedule:edit）：成功刷新待批列表并关弹层（由调用方据返回值处理）。 */
  async function submitLeave(cmd: LeaveApplyCmd): Promise<boolean> {
    if (!auth.can('schedule:edit')) return false
    try {
      const res = await apiApplyLeave(cmd)
      toast.success(`请假单 ${res.data.leaveNo} 已提交，待审批`)
      const s = staff.value.find((x) => x.id === cmd.staffId)
      activity.log(auth.user.name, `请假登记：${s?.name ?? res.data.staffName} ${cmd.type} ${cmd.startDate} 至 ${cmd.endDate}`, res.data.leaveNo)
      await loadLeaves()
      return true
    } catch (e) {
      toast.error(errMsg(e, '请假登记失败'))
      return false
    }
  }

  /** 周动作：铺底（generate）/ 复制上周（copy），成功后按当前周真源重载。 */
  async function runWeekAction(action: 'generate' | 'copy'): Promise<boolean> {
    if (!auth.can('schedule:edit')) return false
    const monday = dayKey(weekStart.value)
    const verb = action === 'generate' ? '铺底' : '复制'
    try {
      const res = action === 'generate'
        ? await apiGenerateWeek(monday)
        : await apiCopyWeek(monday)
      toast.success(`已${verb} ${res.data.created} 个班次，跳过既有 ${res.data.skipped} 个`)
      activity.log(auth.user.name, `排班${verb}：${res.data.weekStart} 周，新建 ${res.data.created} / 跳过 ${res.data.skipped}`, `week@${res.data.weekStart}`)
      await load()
      return true
    } catch (e) {
      toast.error(errMsg(e, `排班${verb}失败`))
      return false
    }
  }

  function attendanceOf(_dateKey: string): AttendanceRecord[] {
    return attendance.value
  }

  /** 拉取当前 weekOffset 对应周的真排班（员工清单+班次行）。 */
  async function load(): Promise<boolean> {
    loading.value = true
    const monday = dayKey(weekStart.value)
    try {
      const res = await getScheduleWeek(monday)
      staff.value = (res.data.staff ?? []).map(adaptStaff)
      const next: Record<string, ApiShiftCode> = {}
      const nextSrc: Record<string, string> = {}
      for (const sh of res.data.shifts ?? []) {
        next[`${sh.staffId}-${sh.shiftDate}`] = sh.shiftCode as ApiShiftCode
        nextSrc[`${sh.staffId}-${sh.shiftDate}`] = sh.source
      }
      shifts.value = next
      sources.value = nextSrc
      loadedWeekKey.value = monday
      return true
    } catch (e) {
      staff.value = []
      shifts.value = {}
      sources.value = {}
      toast.error(errMsg(e, '周排班加载失败'))
      return false
    } finally {
      loading.value = false
    }
  }

  /** 拉取真实请假单列表（数据域内在职员工，登记时间倒序）。 */
  async function loadLeaves(): Promise<void> {
    try {
      const res = await listLeaves()
      leaves.value = (res.data ?? []).map(adaptLeave)
    } catch (e) {
      leaves.value = []
      toast.error(errMsg(e, '请假单加载失败'))
    }
  }

  /** 视图 onMounted 入口（沿用旧名 seed，内部即真实加载）。 */
  function seed() {
    void load()
    void loadLeaves()
  }

  // 上周/下周导航：周偏移变化后自动按新周真源重载
  watch(weekOffset, () => {
    if (loadedWeekKey.value !== dayKey(weekStart.value)) void load()
  })

  return {
    staff, shifts, attendance, leaves, weekOffset, weekStart, days, loading,
    pendingLeaves, todayOnDuty, lateToday, absentToday,
    shiftOf, setShift, approveLeave, decideLeave, submitLeave, runWeekAction,
    attendanceOf, SHIFTS, seed, load, loadLeaves,
  }
})
