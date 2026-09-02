// ============================================================
// Arrival 聚合 store（到店 / 候诊 / 分诊）
// 职责：到店登记、候诊队列、分诊分流、候诊超时（参数取自 settings）。
// 对齐 docs/business-flows.md §2.2/§2.3。分诊后产生 Consultation（委托 consultation store）。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { Arrival, ArrivalStatus, Triage, TriageType } from '@/types/domain'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { useSettingsStore } from './settings'
import { useConsultationStore } from './consultation'
import { staffName } from '@/config/staff'

const STORE_ID = 'store-jingan'

const TRANSITIONS: Record<ArrivalStatus, ArrivalStatus[]> = {
  WAITING: ['TRIAGED', 'LEFT'],
  TRIAGED: ['CALLED', 'DONE'],
  CALLED: ['TRIAGED', 'DONE'],
  DONE: [],
  LEFT: [],
}
function canTransit(from: ArrivalStatus, to: ArrivalStatus) {
  return TRANSITIONS[from]?.includes(to) ?? false
}

export const useArrivalStore = defineStore('arrival', () => {
  const auth = useAuthStore()
  const settings = useSettingsStore()
  const activity = useActivityStore()

  const arrivals = ref<Arrival[]>([])
  const triages = ref<Triage[]>([])
  let queueCounter = 0
  let seeded = false

  /** 开发期种子：2 条候诊到店记录 */
  function seed() {
    if (seeded) return
    seeded = true
    checkIn({ customerId: 'C-201', channel: 'WALK_IN' })
    checkIn({ customerId: 'C-202', channel: 'REFERRAL' })
  }

  const waiting = computed(() => arrivals.value.filter((a) => a.status === 'WAITING'))
  const triaged = computed(() => arrivals.value.filter((a) => a.status === 'TRIAGED' || a.status === 'CALLED'))
  const done = computed(() => arrivals.value.filter((a) => a.status === 'DONE'))

  function get(id: string) {
    return arrivals.value.find((a) => a.id === id)
  }
  function triageOf(arrivalId: string) {
    return triages.value.find((t) => t.arrivalId === arrivalId)
  }

  /** 到店登记 */
  function checkIn(input: { customerId: string; channel: Arrival['channel']; consultantId?: string; doctorId?: string; note?: string }): Arrival {
    const queueNo = ++queueCounter
    const now = new Date().toTimeString().slice(0, 5)
    const a: Arrival = {
      id: nextId('A'),
      customerId: input.customerId,
      storeId: STORE_ID,
      arrivedAt: now,
      channel: input.channel,
      queueNo,
      status: 'WAITING',
    }
    arrivals.value.push(a)
    activity.log(auth.user.name, `到店登记（${input.channel === 'WALK_IN' ? '自然到店' : input.channel === 'REFERRAL' ? '转介绍' : '线上预约'}），进入候诊队列`, a.id)
    return a
  }

  /** 分诊：WAITING → TRIAGED，产生分诊单 + 咨询单 */
  function triage(arrivalId: string, payload: { type: TriageType; assignedTo: string; note: string }) {
    if (!auth.can('reception:edit')) {
      console.warn('[arrival] 无 reception:edit 权限')
      return null
    }
    const a = arrivals.value.find((x) => x.id === arrivalId)
    if (!a || !canTransit(a.status, 'TRIAGED')) return null
    a.status = 'TRIAGED'
    const t: Triage = {
      id: nextId('t'), arrivalId, customerId: a.customerId,
      type: payload.type, assignedTo: payload.assignedTo, note: payload.note,
    }
    triages.value.push(t)
    // 分诊即产生咨询单（业务事实），doctorId 仅 MEDICAL 类型带上
    const consultation = useConsultationStore()
    consultation.open({
      customerId: a.customerId,
      consultantId: payload.assignedTo,
      arrivalId: a.id,
      doctorId: payload.type === 'MEDICAL' ? payload.assignedTo : undefined,
    })
    activity.log(auth.user.name, `分诊 → ${payload.type === 'MEDICAL' ? '医生' : payload.type === 'SERVICE' ? '服务' : '顾问'} ${staffName(payload.assignedTo)}${payload.note ? '（' + payload.note + '）' : ''}`, a.id)
    return t
  }

  /** 改分诊（跨门店受 settings 控制） */
  function reassign(triageId: string, newAssignedTo: string, crossStore = false) {
    if (crossStore && !settings.store.allowCrossStoreTriage) {
      console.warn('[arrival] 跨门店改派未启用（设置中心）')
      return false
    }
    const t = triages.value.find((x) => x.id === triageId)
    if (!t) return false
    t.forwardedTo = newAssignedTo
    t.editedBy = auth.user.name
    t.editedAt = new Date().toISOString()
    activity.log(auth.user.name, `改派分诊 → ${staffName(newAssignedTo)}`, t.arrivalId)
    return true
  }

  function call(arrivalId: string) {
    const a = arrivals.value.find((x) => x.id === arrivalId)
    if (a && canTransit(a.status, 'CALLED')) {
      a.status = 'CALLED'
      activity.log(auth.user.name, '呼叫入位', a.id)
    }
  }

  function markDone(arrivalId: string) {
    const a = arrivals.value.find((x) => x.id === arrivalId)
    if (a && canTransit(a.status, 'DONE')) {
      a.status = 'DONE'
      activity.log(auth.user.name, '离店，到店流程结束', a.id)
    }
  }

  /** 候诊超时检查（按设置中心 waitingTimeoutMin；返回超时到店记录） */
  function overdue() {
    const limit = settings.system.queue.waitingTimeoutMin
    const now = new Date()
    return arrivals.value.filter((a) => {
      if (a.status !== 'WAITING') return false
      const [h, m] = a.arrivedAt.split(':').map(Number)
      const arr = new Date(now)
      arr.setHours(h, m, 0, 0)
      return (now.getTime() - arr.getTime()) / 60000 >= limit
    })
  }

  return {
    arrivals, triages, waiting, triaged, done,
    get, triageOf, checkIn, triage, reassign, call, markDone, overdue, seed,
  }
})
