// ============================================================
// 会员到店核销 store（M2-09）—— 已接真实 txn-service（/api/txn/checkin）
// 扫码核销 / 预约到店 / 直接到店，异常标记（非本人、已核销）。
// 适配层（铁律：模板/样式零改动，只换数据源）：
//  - id/no、客户名/掩码手机/项目、方式/状态/异常原因、时间均取后端读模型
//  - 后端 timeline 时间正序，模板按 mock 口径最新在前展示，适配层统一 reverse
//  - 写动作经网关，400/403 中文错误经 errMsg() 外露 toast，失败返回 null/false
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { useToast } from '@/composables/useToast'
import { errMsg } from './m5Coupon'
import {
  listCheckins, registerCheckin, confirmCheckin,
  markCheckinException, resetCheckin,
  type CheckinRecordDTO,
} from '@/api/checkin'

export type CheckinMethod = 'SCAN' | 'APPOINTMENT' | 'WALKIN'
export type CheckinStatus = 'DONE' | 'EXCEPTION' | 'PENDING'
export type CheckinExceptionReason = 'NONE' | 'NOT_SELF' | 'ALREADY_DONE' | 'NO_APPOINTMENT' | 'INFO_MISMATCH'

export interface CheckinTimeline {
  by: string
  text: string
  at: string
}

export interface CheckinRecord {
  id: string
  no: string
  customerName: string
  phone: string
  project: string
  method: CheckinMethod
  status: CheckinStatus
  exceptionReason: CheckinExceptionReason
  arrivedAt: string
  checkedAt?: string
  operator: string
  note?: string
  /** 自动勾连的预约单号 / 待划扣任务号（散客或未命中预约时为空） */
  apptNo?: string
  wdNo?: string
  timeline: CheckinTimeline[]
}

const METHOD_LABEL: Record<CheckinMethod, string> = {
  SCAN: '扫码核销',
  APPOINTMENT: '预约到店',
  WALKIN: '直接到店',
}
const STATUS_LABEL: Record<CheckinStatus, string> = {
  DONE: '已核销',
  EXCEPTION: '异常',
  PENDING: '待确认',
}
const EXCEPTION_LABEL: Record<CheckinExceptionReason, string> = {
  NONE: '—',
  NOT_SELF: '非本人',
  ALREADY_DONE: '已核销',
  NO_APPOINTMENT: '无预约',
  INFO_MISMATCH: '信息不符',
}

/** 后端读模型 → 模板既有 CheckinRecord 形状；timeline 反转为最新在前（对齐 mock 展示口径）。 */
function adapt(d: CheckinRecordDTO): CheckinRecord {
  return {
    id: d.no || d.id,
    no: d.no || d.id,
    customerName: d.customerName,
    phone: d.phone,
    project: d.project,
    method: d.method as CheckinMethod,
    status: d.status as CheckinStatus,
    exceptionReason: d.exceptionReason as CheckinExceptionReason,
    arrivedAt: d.arrivedAt,
    checkedAt: d.checkedAt ?? undefined,
    operator: d.operator,
    note: d.note ?? undefined,
    apptNo: d.apptNo ?? undefined,
    wdNo: d.wdNo ?? undefined,
    timeline: (d.timeline ?? []).slice().reverse(),
  }
}

export const useCheckinStore = defineStore('checkin', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()
  const toast = useToast()

  const records = ref<CheckinRecord[]>([])
  const filterMethod = ref<CheckinMethod | 'ALL'>('ALL')
  const filterStatus = ref<CheckinStatus | 'ALL'>('ALL')

  const today = computed(() => records.value)
  const done = computed(() => records.value.filter((r) => r.status === 'DONE'))
  const pending = computed(() => records.value.filter((r) => r.status === 'PENDING'))
  const exception = computed(() => records.value.filter((r) => r.status === 'EXCEPTION'))

  const filtered = computed(() => {
    let list = records.value
    if (filterMethod.value !== 'ALL') list = list.filter((r) => r.method === filterMethod.value)
    if (filterStatus.value !== 'ALL') list = list.filter((r) => r.status === filterStatus.value)
    return list.slice().sort((a, b) => new Date(b.arrivedAt).getTime() - new Date(a.arrivedAt).getTime())
  })

  function get(id: string) {
    return records.value.find((r) => r.id === id)
  }

  /** 写动作返回的最新单 upsert 入本地列表（按 no 去重替换）。 */
  function upsert(r: CheckinRecord) {
    const idx = records.value.findIndex((x) => x.id === r.id)
    if (idx >= 0) records.value.splice(idx, 1, r)
    else records.value.unshift(r)
  }

  /** 拉取今日到店核销队列（后端默认今日 + 数据域强制当前门店）。页面 onMounted 调用。 */
  async function seed(): Promise<boolean> {
    try {
      const res = await listCheckins()
      records.value = (res.data ?? []).map(adapt)
      return true
    } catch (e) {
      records.value = []
      toast.error(errMsg(e, '到店核销队列加载失败'))
      return false
    }
  }

  async function register(input: { customerName: string; phone: string; project: string; method: CheckinMethod }): Promise<CheckinRecord | null> {
    try {
      const res = await registerCheckin({
        customerName: input.customerName.trim(),
        phone: input.phone.trim(),
        project: input.project.trim(),
        method: input.method,
      })
      const r = adapt(res.data)
      upsert(r)
      activity.log(auth.user.name, `登记到店 ${r.customerName}：${r.project}`, r.id)
      return r
    } catch (e) {
      toast.error(errMsg(e, '登记到店失败'))
      return null
    }
  }

  async function confirm(id: string): Promise<boolean> {
    try {
      const res = await confirmCheckin(id)
      upsert(adapt(res.data))
      activity.log(auth.user.name, `到店核销确认 ${res.data.no}：${res.data.customerName}`, id)
      return true
    } catch (e) {
      toast.error(errMsg(e, '核销确认失败'))
      return false
    }
  }

  async function markException(id: string, reason: CheckinExceptionReason, note?: string): Promise<boolean> {
    try {
      const res = await markCheckinException(id, { reason, note })
      upsert(adapt(res.data))
      activity.log(auth.user.name, `到店异常 ${res.data.no}：${EXCEPTION_LABEL[reason]}`, id)
      return true
    } catch (e) {
      toast.error(errMsg(e, '标记异常失败'))
      return false
    }
  }

  async function resetToPending(id: string): Promise<boolean> {
    try {
      const res = await resetCheckin(id)
      upsert(adapt(res.data))
      return true
    } catch (e) {
      toast.error(errMsg(e, '解除异常失败'))
      return false
    }
  }

  return {
    records, filterMethod, filterStatus,
    today, done, pending, exception, filtered,
    get, register, confirm, markException, resetToPending, seed,
    METHOD_LABEL, STATUS_LABEL, EXCEPTION_LABEL,
  }
})
