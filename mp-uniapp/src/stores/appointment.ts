/**
 * 预约 store（C 端）
 * 会员查看自己的预约、新建预约（提交后同步 B 端预约看板）。
 * 后端就绪后：列表 GET /c/appointments，新建 POST /c/appointments。
 */
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

export type ApptStatus = 'NEW' | 'CONFIRMED' | 'ARRIVED' | 'COMPLETED' | 'CANCELLED' | 'NO_SHOW'
export interface Appointment {
  id: string
  customerId: string
  timeSlot: string
  status: ApptStatus
  source: string
  project?: string
  note?: string
}

let seq = 0
function nextId() {
  seq += 1
  return `APPT-${seq}`
}

export const useAppointmentStore = defineStore('mp-appointment', () => {
  const appointments = ref<Appointment[]>([])

  function byCustomer(customerId: string) {
    return appointments.value.filter((a) => a.customerId === customerId)
  }

  function create(input: {
    customerId: string
    timeSlot: string
    project?: string
    source?: string
    note?: string
  }): Appointment | null {
    if (!input.timeSlot) return null
    const a: Appointment = {
      id: nextId(),
      customerId: input.customerId,
      timeSlot: input.timeSlot,
      status: 'NEW',
      source: input.source || 'C_MINIAPP',
      project: input.project,
      note: input.note,
    }
    appointments.value.unshift(a)
    return a
  }

  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    const today = new Date().toISOString().slice(0, 10)
    const seeds: Array<Partial<Appointment> & { timeSlot: string; status: ApptStatus }> = [
      { customerId: 'C-201', timeSlot: '09:30', status: 'ARRIVED', source: '小程序', project: '光子嫩肤' },
      { customerId: 'C-201', timeSlot: '11:00', status: 'COMPLETED', source: '复诊', project: '光子嫩肤(第2次)' },
      { customerId: 'C-201', timeSlot: '16:00', status: 'CANCELLED', source: '电话', project: '皮肤检测' },
      { customerId: 'C-201', timeSlot: '14:00', status: 'CONFIRMED', source: '小程序', project: '热玛吉面诊' },
    ]
    seeds.forEach((s) => {
      appointments.value.push({
        id: nextId(),
        customerId: s.customerId!,
        timeSlot: `${today}T${s.timeSlot}:00`,
        status: s.status,
        source: s.source || '小程序',
        project: s.project,
      })
    })
  }

  const mine = computed(() => appointments.value)
  return { appointments, mine, byCustomer, create, seed }
})
