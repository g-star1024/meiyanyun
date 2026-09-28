/**
 * 预约 store（C 端）
 * 会员查看自己的预约、新建预约（提交后同步 B 端预约看板）。
 * C-B3 切真：列表 GET /c/appointments、新建 POST /c/appointments（经 c-service → txn 域
 * internal 端点落 B 端 appointment 表）；seed/create async 化照 member store C-B2 先例
 * （导出签名不变，铁律-1-B）。
 */
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { http } from '@/utils/request'

export type ApptStatus = 'NEW' | 'CONFIRMED' | 'ARRIVED' | 'COMPLETED' | 'CANCELLED' | 'NO_SHOW'
export interface Appointment {
  id: string
  customerId: string
  timeSlot: string
  status: ApptStatus
  source: string
  project?: string
  note?: string
  /** C-B3：后端联查 store 表投影，list 页门店行展示用 */
  storeCode?: string
  storeName?: string
}

export const useAppointmentStore = defineStore('mp-appointment', () => {
  const appointments = ref<Appointment[]>([])

  function byCustomer(customerId: string) {
    return appointments.value.filter((a) => a.customerId === customerId)
  }

  /**
   * 新建预约：POST /c/appointments。customerId 由后端按登录态取（入参保留仅为签名兼容）；
   * note 因 appointment 表无对应列如实不落库；幂等 409/校验 400 中文错误由 http 层 toast 原话弹出。
   */
  async function create(input: {
    customerId: string
    timeSlot: string
    project?: string
    storeName?: string
    source?: string
    note?: string
  }): Promise<Appointment | null> {
    if (!input.timeSlot || !input.storeName || !input.project) return null
    try {
      const a = await http.post<Appointment>('/c/appointments', {
        storeName: input.storeName,
        project: input.project,
        timeSlot: input.timeSlot,
      })
      if (a) appointments.value.unshift(a)
      return a || null
    } catch {
      return null
    }
  }

  let seeded = false
  async function seed() {
    if (seeded) return
    seeded = true
    try {
      const list = await http.get<Appointment[]>('/c/appointments', { silent: true })
      if (Array.isArray(list)) appointments.value = list
    } catch {
      /* 未登录/后端未就绪：回滚 seeded 允许登录后 onShow 重拉（本地无假数据兜底） */
      seeded = false
    }
  }

  const mine = computed(() => appointments.value)
  return { appointments, mine, byCustomer, create, seed }
})
