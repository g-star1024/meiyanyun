// ============================================================
// Arrival 聚合 store（到店 / 候诊 / 分诊）—— 已接真实 txn-service
// 职责：到店登记、候诊队列、分诊分流、候诊超时（参数取自 settings）。
// 适配层（铁律：模板/样式零改动，只换数据源）：
//  - id=ahNo、storeId=storeCode、arrivedAt 由 OffsetDateTime 适配为上海时区 HH:MM
//  - 后端 channel 含 APPOINTMENT，前端活规格为 ONLINE_APPT/WALK_IN/REFERRAL/MARKETING
//  - 分诊单由读模型内联 triage 适配（分诊同事务在后端建咨询/方案草稿，前端不再 open）
//  - 写动作经网关，400/403 中文错误经 errMsg() 外露 toast，失败返回 null/false
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { Arrival, ArrivalStatus, Triage, TriageType } from '@/types/domain'
import { useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { useSettingsStore } from './settings'
import { useCustomerStore } from './customer'
import { useToast } from '@/composables/useToast'
import { errMsg } from './m5Coupon'
import {
  listArrivals, checkInArrival, triageArrival, reassignArrival,
  callArrival, doneArrival, releaseArrival,
  type ArrivalViewDTO,
} from '@/api/arrival'

const CHANNEL_TO_DOMAIN: Record<string, Arrival['channel']> = {
  WALK_IN: 'WALK_IN',
  REFERRAL: 'REFERRAL',
  MARKETING: 'MARKETING',
  APPOINTMENT: 'ONLINE_APPT',
  ONLINE_APPT: 'ONLINE_APPT',
}
const CHANNEL_FROM_DOMAIN: Record<string, string> = {
  WALK_IN: 'WALK_IN',
  REFERRAL: 'REFERRAL',
  MARKETING: 'MARKETING',
  ONLINE_APPT: 'APPOINTMENT',
}
const CHANNEL_LABEL: Record<string, string> = {
  WALK_IN: '自然到店',
  REFERRAL: '转介绍',
  MARKETING: '营销获客',
  APPOINTMENT: '线上预约',
  ONLINE_APPT: '线上预约',
}
const TYPE_LABEL: Record<string, string> = {
  CONSULT: '顾问',
  MEDICAL: '医生',
  SERVICE: '服务',
}

function today(): string {
  const d = new Date()
  const m = `${d.getMonth() + 1}`.padStart(2, '0')
  const day = `${d.getDate()}`.padStart(2, '0')
  return `${d.getFullYear()}-${m}-${day}`
}

/** OffsetDateTime/ISO → 上海时区 HH:MM（非法输入回退本地时间/空串） */
function hhmm(s?: string | null): string {
  if (!s) return new Date().toTimeString().slice(0, 5)
  const d = new Date(s)
  if (Number.isNaN(d.getTime())) return s.slice(11, 16)
  try {
    return new Intl.DateTimeFormat('en-GB', {
      timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit', hour12: false,
    }).format(d)
  } catch {
    return s.slice(11, 16)
  }
}

export const useArrivalStore = defineStore('arrival', () => {
  const auth = useAuthStore()
  const settings = useSettingsStore()
  const activity = useActivityStore()
  const customer = useCustomerStore()
  const toast = useToast()

  const arrivals = ref<Arrival[]>([])
  const triages = ref<Triage[]>([])

  /** 兼容旧演示入口：真实数据由页面 onMounted 调 load 拉取 */
  function seed() {}

  const waiting = computed(() => arrivals.value.filter((a) => a.status === 'WAITING'))
  const triaged = computed(() => arrivals.value.filter((a) => a.status === 'TRIAGED' || a.status === 'CALLED'))
  const done = computed(() => arrivals.value.filter((a) => a.status === 'DONE'))
  /** 已释放（手工/超时自动）：WAITING → LEFT，号源已交回并触发候补递补 */
  const left = computed(() => arrivals.value.filter((a) => a.status === 'LEFT'))

  function get(id: string) {
    return arrivals.value.find((a) => a.id === id)
  }
  function triageOf(arrivalId: string) {
    return triages.value.find((t) => t.arrivalId === arrivalId)
  }

  function adaptArrival(d: ArrivalViewDTO): Arrival {
    return {
      id: d.ahNo || d.id,
      customerId: d.customerId,
      storeId: d.storeCode,
      arrivedAt: hhmm(d.arrivedAt),
      channel: CHANNEL_TO_DOMAIN[d.channel] ?? 'ONLINE_APPT',
      queueNo: d.queueNo,
      status: d.status as ArrivalStatus,
      leftAt: d.leftAt ? hhmm(d.leftAt) : undefined,
    }
  }
  function adaptTriage(d: ArrivalViewDTO): Triage | null {
    const t = d.triage
    if (!t) return null
    return {
      id: t.id,
      arrivalId: d.ahNo || d.id,
      customerId: t.customerId || d.customerId,
      type: t.type as TriageType,
      assignedTo: t.assignedTo,
      forwardedTo: t.forwardedTo ?? undefined,
      note: t.note ?? '',
      editedBy: t.editedBy ?? undefined,
      editedAt: t.editedAt ?? undefined,
      reassignHistory: (t.reassignHistory ?? []).map((h) => ({
        fromStaff: h.fromStaff,
        fromStaffName: h.fromStaffName,
        toStaff: h.toStaff,
        toStaffName: h.toStaffName,
        operator: h.operator,
        operatorName: h.operatorName,
        createdAt: h.createdAt,
      })),
    }
  }

  /** 拉取当日到店/分诊列表（锁当前门店；管理员可空 storeCode 看全量） */
  async function load(storeCode?: string, date?: string): Promise<boolean> {
    try {
      const res = await listArrivals({ date: date || today(), storeCode })
      const list = res.data ?? []
      arrivals.value = list.map(adaptArrival)
      triages.value = list.map(adaptTriage).filter((t): t is Triage => !!t)
      customer.hydrate(list.map((d) => ({
        customerId: d.customerId,
        customerName: d.customerName,
        phoneMask: d.phoneMask,
      })))
      return true
    } catch (e) {
      arrivals.value = []
      triages.value = []
      toast.error(errMsg(e, '候诊队列加载失败'))
      return false
    }
  }

  /** 到店登记 */
  async function checkIn(input: {
    customerId: string
    channel: Arrival['channel']
    consultantId?: string
    doctorId?: string
    note?: string
  }): Promise<Arrival | null> {
    try {
      const res = await checkInArrival({
        customerId: input.customerId,
        channel: CHANNEL_FROM_DOMAIN[input.channel] ?? input.channel,
        note: input.note,
      })
      activity.log(
        auth.user.name,
        `到店登记（${CHANNEL_LABEL[res.data.channel] ?? '到店'}），进入候诊队列`,
        res.data.ahNo,
      )
      await load(auth.user.storeId)
      return adaptArrival(res.data)
    } catch (e) {
      toast.error(errMsg(e, '到店登记失败'))
      return null
    }
  }

  /** 分诊：WAITING → TRIAGED（后端同事务建方案草稿，planId 在再次 load 后可查） */
  async function triage(
    arrivalId: string,
    payload: { type: TriageType; assignedTo: string; note: string },
  ): Promise<Triage | null> {
    try {
      const res = await triageArrival(arrivalId, {
        type: payload.type,
        assignedTo: payload.assignedTo,
        note: payload.note,
      })
      activity.log(
        auth.user.name,
        `分诊 → ${TYPE_LABEL[payload.type] ?? '分诊'} ${res.data.triage?.assignedToName || payload.assignedTo}${payload.note ? '（' + payload.note + '）' : ''}`,
        arrivalId,
      )
      await load(auth.user.storeId)
      return triageOf(arrivalId) ?? null
    } catch (e) {
      toast.error(errMsg(e, '分诊失败'))
      return null
    }
  }

  /** 改分诊（跨门店受 settings 控制；入参保持 triageId 契约，内部转 ahNo） */
  async function reassign(triageId: string, newAssignedTo: string, crossStore = false): Promise<boolean> {
    if (crossStore && !settings.store.allowCrossStoreTriage) {
      toast.error('跨门店改派未启用（设置中心）')
      return false
    }
    const t = triages.value.find((x) => x.id === triageId)
    if (!t) {
      toast.error('未找到分诊单，改派失败')
      return false
    }
    try {
      await reassignArrival(t.arrivalId, { newAssignedTo })
      activity.log(auth.user.name, `改派分诊 → ${newAssignedTo}`, t.arrivalId)
      await load(auth.user.storeId)
      return true
    } catch (e) {
      toast.error(errMsg(e, '改派失败'))
      return false
    }
  }

  async function call(arrivalId: string): Promise<boolean> {
    try {
      await callArrival(arrivalId)
      activity.log(auth.user.name, '呼叫入位', arrivalId)
      await load(auth.user.storeId)
      return true
    } catch (e) {
      toast.error(errMsg(e, '叫号失败'))
      return false
    }
  }

  async function markDone(arrivalId: string): Promise<boolean> {
    try {
      await doneArrival(arrivalId)
      activity.log(auth.user.name, '离店，到店流程结束', arrivalId)
      await load(auth.user.storeId)
      return true
    } catch (e) {
      toast.error(errMsg(e, '完成处理失败'))
      return false
    }
  }

  /** 手工释放号源：WAITING → LEFT，后端同事务递补本店候补首位；页面已二次确认。 */
  async function release(arrivalId: string): Promise<boolean> {
    try {
      await releaseArrival(arrivalId)
      activity.log(auth.user.name, '手工释放号源，已触发候补递补', arrivalId)
      await load(auth.user.storeId)
      return true
    } catch (e) {
      toast.error(errMsg(e, '释放号源失败'))
      return false
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
    arrivals, triages, waiting, triaged, done, left,
    get, triageOf, load, checkIn, triage, reassign, call, markDone, release, overdue, seed,
  }
})
