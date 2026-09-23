// ============================================================
// Recall 聚合 store（复诊提醒管理）—— B90 卡2 去 mock 切真
// 状态机：PENDING（待提醒）→ NOTIFIED（已提醒/待客户确认）
//          → CONFIRMED（确认复诊）/ BOOKED（已预约）/ SKIPPED（已跳过）
// - 复诊建议通常由医生在病历经疗后发起（schedule），前台/运营执行提醒（notify）。
// - 客户确认复诊后可登记确认结果；实际预约落地后置 BOOKED。
// - dueDate 早于今天且仍 PENDING 的视为"超期未提醒"，页面高亮预警。
// 数据：对接 marketing-service /api/marketing/recall（@/api/recall 薄封装）。
// 状态机服务端权威：TRANSITIONS 仅客户端预检，非法转移 409 经 errMsg 透传。
// KPI 保留本地 computed（与后端 kpi 同口径；View .length 语义不可破）。
// 权限：recall:create 建提醒 / recall:edit 执行提醒与确认。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { useToast } from '@/composables/useToast'
import { errMsg } from './m5Coupon'
import { shDateStr } from '@/utils/datetime'
import {
  fetchRecalls,
  scheduleRecall,
  notifyRecall,
  confirmRecall,
  bookRecall,
  rescheduleRecall,
  skipRecall,
  type RecallRow,
} from '@/api/recall'

export type RecallMethod = 'PHONE' | 'WECHAT' | 'SMS' | 'IN_STORE'
export type RecallStatus = 'PENDING' | 'NOTIFIED' | 'CONFIRMED' | 'BOOKED' | 'SKIPPED'
export type RecallChannel = 'DOCTOR_ADVICE' | 'COURSE_FOLLOW' | 'SYSTEM_AUTO' | 'MANUAL'

export interface RecallTimelineEntry {
  at: string
  by: string
  action: string
  detail?: string
}

export interface Recall {
  id: string
  recallNo: string
  customerId: string
  customerName: string
  /** 复诊建议来源：医生建议 / 疗程跟进 / 系统自动 / 手动新建 */
  source: RecallChannel
  /** 建议复诊的项目/原因 */
  reason: string
  /** 关联病历号 / 订单号 */
  relatedEmrNo?: string
  relatedOrderNo?: string
  /** 上次就诊日期 */
  lastVisitDate: string
  /** 建议复诊日期 */
  dueDate: string
  method: RecallMethod
  status: RecallStatus
  /** 提醒人 / 提醒时间 */
  notifiedByName?: string
  notifiedAt?: string
  /** 客户回复/确认结果 */
  customerReply?: string
  confirmedDate?: string
  /** 跳过原因 */
  skipReason?: string
  note?: string
  timeline: RecallTimelineEntry[]
  createdAt: string
  /** 来源规则编号：非空 = Flow 引擎自动创建（页面展示「自动」徽标） */
  ruleNo?: string | null
}

/** 状态机转移表（客户端预检；服务端为权威，非法转移 409） */
const TRANSITIONS: Record<RecallStatus, RecallStatus[]> = {
  PENDING: ['NOTIFIED', 'SKIPPED'],
  NOTIFIED: ['CONFIRMED', 'BOOKED', 'SKIPPED', 'PENDING'], // NOTIFIED→PENDING 用于改期重提醒
  CONFIRMED: ['BOOKED', 'SKIPPED'],
  BOOKED: [],
  SKIPPED: [],
}

function canTransit(from: RecallStatus, to: RecallStatus): boolean {
  return TRANSITIONS[from]?.includes(to) ?? false
}

/** 后端行 → 前端提醒（timeline JSON 串解析；null → undefined） */
function rowToRecall(row: RecallRow): Recall {
  let timeline: RecallTimelineEntry[] = []
  try {
    const arr = JSON.parse(row.timeline || '[]')
    if (Array.isArray(arr)) timeline = arr
  } catch {
    timeline = []
  }
  return {
    id: row.id,
    recallNo: row.recallNo,
    customerId: row.customerId,
    customerName: row.customerName,
    source: row.source as RecallChannel,
    reason: row.reason,
    relatedEmrNo: row.relatedEmrNo ?? undefined,
    relatedOrderNo: row.relatedOrderNo ?? undefined,
    lastVisitDate: row.lastVisitDate ?? '',
    dueDate: row.dueDate,
    method: row.method as RecallMethod,
    status: row.status as RecallStatus,
    notifiedByName: row.notifiedByName ?? undefined,
    notifiedAt: row.notifiedAt ?? undefined,
    customerReply: row.customerReply ?? undefined,
    confirmedDate: row.confirmedDate ?? undefined,
    skipReason: row.skipReason ?? undefined,
    note: row.note ?? undefined,
    timeline,
    createdAt: row.createdAt,
    ruleNo: row.ruleNo,
  }
}

export const useRecallStore = defineStore('recall', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()
  const toast = useToast()

  const recalls = ref<Recall[]>([])

  const pending = computed(() => recalls.value.filter((r) => r.status === 'PENDING'))
  const notified = computed(() => recalls.value.filter((r) => r.status === 'NOTIFIED'))
  const confirmed = computed(() => recalls.value.filter((r) => r.status === 'CONFIRMED'))
  const booked = computed(() => recalls.value.filter((r) => r.status === 'BOOKED'))
  const skipped = computed(() => recalls.value.filter((r) => r.status === 'SKIPPED'))

  /** 超期未提醒：建议复诊日期早于今天且仍待提醒 */
  const overdue = computed(() => {
    const today = new Date()
    today.setHours(0, 0, 0, 0)
    return pending.value.filter((r) => new Date(r.dueDate) < today)
  })
  /** 今日待提醒 */
  const todayPending = computed(() => {
    const today = shDateStr()
    return pending.value.filter((r) => r.dueDate.slice(0, 10) === today)
  })
  /** 即将到期（未来 3 天内待提醒） */
  const upcoming = computed(() => {
    const today = new Date()
    today.setHours(0, 0, 0, 0)
    const in3 = new Date(today)
    in3.setDate(in3.getDate() + 3)
    return pending.value.filter((r) => {
      const d = new Date(r.dueDate)
      return d >= today && d <= in3
    })
  })
  /** 转化率：已确认+已预约 / 已提醒总数 */
  const conversionRate = computed(() => {
    const reached = notified.value.length + confirmed.value.length + booked.value.length
    if (reached === 0) return 0
    return Math.round(((confirmed.value.length + booked.value.length) / reached) * 100)
  })

  function get(id: string) {
    return recalls.value.find((r) => r.id === id)
  }

  function upsert(r: Recall) {
    const idx = recalls.value.findIndex((x) => x.id === r.id)
    if (idx >= 0) recalls.value.splice(idx, 1, r)
    else recalls.value.unshift(r)
  }

  /** 加载真实复诊提醒列表（幂等；函数名保留 seed，View onMounted 零改） */
  let seeded = false
  async function seed(): Promise<void> {
    if (seeded) return
    seeded = true
    try {
      const res = await fetchRecalls()
      recalls.value = (res.data.recalls ?? []).map(rowToRecall)
    } catch (e) {
      seeded = false
      toast.error(errMsg(e, '复诊提醒加载失败'))
    }
  }

  /** 新建复诊提醒（医生建议/手动；customerName 不上送，后端按 customerId 解析回填） */
  async function schedule(input: {
    customerId: string
    customerName: string
    source: RecallChannel
    reason: string
    relatedEmrNo?: string
    relatedOrderNo?: string
    lastVisitDate: string
    dueDate: string
    method?: RecallMethod
    note?: string
  }): Promise<Recall | null> {
    if (!auth.can('recall:create')) {
      toast.error('无新建复诊提醒权限')
      return null
    }
    try {
      const res = await scheduleRecall({
        customerId: input.customerId,
        source: input.source,
        reason: input.reason,
        relatedEmrNo: input.relatedEmrNo?.trim() || undefined,
        relatedOrderNo: input.relatedOrderNo?.trim() || undefined,
        lastVisitDate: input.lastVisitDate ? input.lastVisitDate.slice(0, 10) : undefined,
        dueDate: input.dueDate.slice(0, 10),
        method: input.method,
        note: input.note?.trim() || undefined,
      })
      const r = rowToRecall(res.data)
      recalls.value.unshift(r)
      activity.log(auth.user.name, `创建复诊提醒 ${r.recallNo}（${r.customerName}·${r.reason}）`, r.id)
      return r
    } catch (e) {
      toast.error(errMsg(e, '新建复诊提醒失败'))
      return null
    }
  }

  /** 执行提醒：PENDING → NOTIFIED */
  async function notify(id: string, method?: RecallMethod): Promise<boolean> {
    const r = recalls.value.find((x) => x.id === id)
    if (!r || !canTransit(r.status, 'NOTIFIED')) return false
    if (!auth.can('recall:edit')) {
      toast.error('无复诊提醒执行权限')
      return false
    }
    try {
      const res = await notifyRecall(r.recallNo, method)
      upsert(rowToRecall(res.data))
      activity.log(auth.user.name, `复诊提醒 ${r.recallNo} 已通过${method ?? r.method}触达客户`, r.id)
      return true
    } catch (e) {
      toast.error(errMsg(e, '执行提醒失败'))
      return false
    }
  }

  /** 登记客户确认复诊：NOTIFIED → CONFIRMED */
  async function confirm(id: string, reply?: string, confirmedDate?: string): Promise<boolean> {
    const r = recalls.value.find((x) => x.id === id)
    if (!r || !canTransit(r.status, 'CONFIRMED')) return false
    if (!auth.can('recall:edit')) {
      toast.error('无复诊提醒执行权限')
      return false
    }
    try {
      const res = await confirmRecall(r.recallNo, {
        reply: reply?.trim() || undefined,
        confirmedDate: confirmedDate ? confirmedDate.slice(0, 10) : undefined,
      })
      upsert(rowToRecall(res.data))
      activity.log(auth.user.name, `复诊提醒 ${r.recallNo} 客户确认复诊${confirmedDate ? `（拟 ${confirmedDate.slice(0, 10)}）` : ''}`, r.id)
      return true
    } catch (e) {
      toast.error(errMsg(e, '登记确认失败'))
      return false
    }
  }

  /** 已预约落地：CONFIRMED/NOTIFIED → BOOKED */
  async function markBooked(id: string, detail?: string): Promise<boolean> {
    const r = recalls.value.find((x) => x.id === id)
    if (!r || !canTransit(r.status, 'BOOKED')) return false
    if (!auth.can('recall:edit')) {
      toast.error('无复诊提醒执行权限')
      return false
    }
    try {
      const res = await bookRecall(r.recallNo, detail?.trim() || undefined)
      upsert(rowToRecall(res.data))
      activity.log(auth.user.name, `复诊提醒 ${r.recallNo} 已转为预约`, r.id)
      return true
    } catch (e) {
      toast.error(errMsg(e, '登记预约失败'))
      return false
    }
  }

  /** 跳过：PENDING/NOTIFIED/CONFIRMED → SKIPPED */
  async function skip(id: string, reason: string): Promise<boolean> {
    const r = recalls.value.find((x) => x.id === id)
    if (!r || !canTransit(r.status, 'SKIPPED')) return false
    if (!auth.can('recall:edit')) {
      toast.error('无复诊提醒执行权限')
      return false
    }
    try {
      const res = await skipRecall(r.recallNo, reason.trim())
      upsert(rowToRecall(res.data))
      activity.log(auth.user.name, `复诊提醒 ${r.recallNo} 跳过：${reason}`, r.id)
      return true
    } catch (e) {
      toast.error(errMsg(e, '跳过失败'))
      return false
    }
  }

  /** 改期：调整建议复诊日期（仅 PENDING/NOTIFIED；NOTIFIED → PENDING 清通知信息） */
  async function reschedule(id: string, dueDate: string, note?: string): Promise<boolean> {
    const r = recalls.value.find((x) => x.id === id)
    if (!r) return false
    if (r.status !== 'PENDING' && r.status !== 'NOTIFIED') return false
    if (!auth.can('recall:edit')) {
      toast.error('无复诊提醒执行权限')
      return false
    }
    try {
      const res = await rescheduleRecall(r.recallNo, {
        dueDate: dueDate.slice(0, 10),
        note: note?.trim() || undefined,
      })
      upsert(rowToRecall(res.data))
      activity.log(auth.user.name, `复诊提醒 ${r.recallNo} 改期至 ${dueDate.slice(0, 10)}`, r.id)
      return true
    } catch (e) {
      toast.error(errMsg(e, '改期失败'))
      return false
    }
  }

  return {
    recalls, pending, notified, confirmed, booked, skipped,
    overdue, todayPending, upcoming, conversionRate,
    get, schedule, notify, confirm, markBooked, skip, reschedule, seed,
  }
})

export const RECALL_STATUS_LABEL: Record<RecallStatus, string> = {
  PENDING: '待提醒',
  NOTIFIED: '已提醒',
  CONFIRMED: '已确认',
  BOOKED: '已预约',
  SKIPPED: '已跳过',
}

export const RECALL_METHOD_LABEL: Record<RecallMethod, string> = {
  PHONE: '电话', WECHAT: '微信', SMS: '短信', IN_STORE: '到店面诊',
}

export const RECALL_SOURCE_LABEL: Record<RecallChannel, string> = {
  DOCTOR_ADVICE: '医生建议',
  COURSE_FOLLOW: '疗程跟进',
  SYSTEM_AUTO: '系统自动',
  MANUAL: '手动新建',
}
