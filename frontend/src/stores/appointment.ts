// ============================================================
// Appointment 聚合 store
// 职责：预约的创建、状态机（NEW→CONFIRMED→ARRIVED→COMPLETED / NO_SHOW / CANCELLED）。
// 对齐 docs/business-flows.md §2.1。到店动作委托 arrival store（避免跨 store 直接改对方 state）。
// ============================================================
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { Appointment, ApptStatus } from '@/types/domain'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

const STORE_ID = 'store-jingan'

// 合法状态迁移表（business-flows §2.1）
const TRANSITIONS: Record<ApptStatus, ApptStatus[]> = {
  NEW: ['CONFIRMED', 'CANCELLED'],
  CONFIRMED: ['ARRIVED', 'NO_SHOW', 'CANCELLED'],
  ARRIVED: ['COMPLETED', 'NO_SHOW'],
  COMPLETED: [],
  NO_SHOW: [],
  CANCELLED: [],
}

function canMove(from: ApptStatus, to: ApptStatus) {
  return TRANSITIONS[from]?.includes(to) ?? false
}

export const useAppointmentStore = defineStore('appointment', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const appointments = ref<Appointment[]>([])
  let seeded = false

  /** 开发期种子：今日若干预约覆盖各状态。真实环境由 API 拉取。 */
  function seed() {
    if (seeded) return
    seeded = true
    const today = new Date().toISOString().slice(0, 10)
    const seeds: Array<Partial<Appointment> & { timeSlot: string; status: ApptStatus }> = [
      { customerId: 'C-201', timeSlot: '09:30', status: 'ARRIVED', source: '小程序', project: '光子嫩肤', consultantId: 'staff-lin' },
      { customerId: 'C-202', timeSlot: '10:00', status: 'CONFIRMED', source: '电话', project: '热玛吉面诊', consultantId: 'staff-lin', doctorId: 'staff-gu' },
      { customerId: 'C-203', timeSlot: '10:30', status: 'CONFIRMED', source: '线上预约', project: '果酸焕肤', consultantId: 'staff-lin' },
      { customerId: 'C-201', timeSlot: '11:00', status: 'COMPLETED', source: '复诊', project: '光子嫩肤(第2次)', consultantId: 'staff-lin' },
      { customerId: 'C-202', timeSlot: '14:00', status: 'NEW', source: '咨询师代约', project: '水光针', consultantId: 'staff-lin' },
      { customerId: 'C-203', timeSlot: '15:30', status: 'NO_SHOW', source: '小程序', project: '痤疮护理', consultantId: 'staff-lin' },
      { customerId: 'C-201', timeSlot: '16:00', status: 'CANCELLED', source: '电话', project: '皮肤检测', consultantId: 'staff-lin' },
    ]
    seeds.forEach((s) => {
      appointments.value.push({
        id: nextId('ap'),
        customerId: s.customerId!,
        storeId: STORE_ID,
        consultantId: s.consultantId,
        doctorId: s.doctorId,
        timeSlot: `${today} ${s.timeSlot}`,
        status: s.status,
        source: s.source || '人工预约',
        project: s.project,
      } as Appointment)
    })
  }

  const today = computed(() => appointments.value)
  const stats = computed(() => {
    const list = appointments.value
    const total = list.length
    const confirmed = list.filter((a) => a.status === 'CONFIRMED' || a.status === 'NEW').length
    const arrived = list.filter((a) => a.status === 'ARRIVED').length
    const completed = list.filter((a) => a.status === 'COMPLETED').length
    const noShow = list.filter((a) => a.status === 'NO_SHOW').length
    const cancelled = list.filter((a) => a.status === 'CANCELLED').length
    const fulfilled = arrived + completed
    const arrivalRate = total ? Math.round((fulfilled / (total - cancelled || 1)) * 100) : 0
    return { total, confirmed, arrived, completed, noShow, cancelled, arrivalRate }
  })
  function byCustomer(customerId: string) {
    return appointments.value.filter((a) => a.customerId === customerId)
  }
  function get(id: string) {
    return appointments.value.find((a) => a.id === id)
  }

  function create(input: { customerId: string; timeSlot: string; project?: string; consultantId?: string; doctorId?: string; source?: string; note?: string }) {
    if (!auth.can('appointment:create')) {
      console.warn('[appointment] 无 appointment:create 权限')
      return null
    }
    const appt: Appointment = {
      id: nextId('ap'),
      customerId: input.customerId,
      storeId: STORE_ID,
      consultantId: input.consultantId,
      doctorId: input.doctorId,
      timeSlot: input.timeSlot,
      status: 'NEW',
      source: input.source || '人工预约',
      project: input.project,
    }
    appointments.value.unshift(appt)
    activity.log(auth.user.name, `新建预约 ${appt.timeSlot}`, appt.id)
    return appt
  }

  function transition(id: string, to: ApptStatus, note?: string): boolean {
    const a = appointments.value.find((x) => x.id === id)
    if (!a) return false
    if (!canMove(a.status, to)) {
      console.warn(`[appointment] 非法迁移 ${a.status} → ${to}`)
      return false
    }
    a.status = to
    const labels: Record<ApptStatus, string> = {
      NEW: '新建', CONFIRMED: '已确认', ARRIVED: '已到店', COMPLETED: '已完成',
      NO_SHOW: '爽约', CANCELLED: '已取消',
    }
    activity.log(auth.user.name, `预约 ${labels[to]}${note ? '（' + note + '）' : ''}`, a.id)
    return true
  }

  const confirm = (id: string) => transition(id, 'CONFIRMED')
  const cancel = (id: string, note?: string) => transition(id, 'CANCELLED', note)
  const markNoShow = (id: string) => transition(id, 'NO_SHOW')
  const complete = (id: string) => transition(id, 'COMPLETED')

  return {
    appointments, today, stats,
    seed, get, byCustomer, create, confirm, cancel, markNoShow, complete, transition,
  }
})
