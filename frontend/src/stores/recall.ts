// ============================================================
// Recall 聚合 store（复诊提醒管理）
// 状态机：PENDING（待提醒）→ NOTIFIED（已提醒/待客户确认）
//          → CONFIRMED（确认复诊）/ BOOKED（已预约）/ SKIPPED（已跳过）
// - 复诊建议通常由医生在病历经疗后发起（schedule），前台/运营执行提醒（notify）。
// - 客户确认复诊后可登记确认结果；实际预约落地后置 BOOKED。
// - dueDate 早于今天且仍 PENDING 的视为"超期未提醒"，页面高亮预警。
// 权限：recall:create 建提醒 / recall:edit 执行提醒与确认。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

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
}

/** 状态机转移表 */
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

export const useRecallStore = defineStore('recall', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const recalls = ref<Recall[]>([])
  let seq = 0

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
    const today = new Date().toISOString().slice(0, 10)
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

  function pushTimeline(r: Recall, action: string, detail?: string) {
    r.timeline.push({ at: new Date().toISOString(), by: auth.user.name, action, detail })
  }

  /** 新建复诊提醒（医生建议/手动） */
  function schedule(input: {
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
  }): Recall | null {
    if (!auth.can('recall:create')) {
      console.warn('[recall] 无 recall:create 权限')
      return null
    }
    seq += 1
    const now = new Date().toISOString()
    const r: Recall = {
      id: nextId('rc'),
      recallNo: `RC${Date.now().toString().slice(-8)}${seq}`,
      customerId: input.customerId,
      customerName: input.customerName,
      source: input.source,
      reason: input.reason,
      relatedEmrNo: input.relatedEmrNo,
      relatedOrderNo: input.relatedOrderNo,
      lastVisitDate: input.lastVisitDate,
      dueDate: input.dueDate,
      method: input.method ?? 'PHONE',
      status: 'PENDING',
      note: input.note?.trim() || undefined,
      timeline: [{ at: now, by: auth.user.name, action: '创建复诊提醒', detail: input.reason }],
      createdAt: now,
    }
    recalls.value.unshift(r)
    activity.log(auth.user.name, `创建复诊提醒 ${r.recallNo}（${r.customerName}·${r.reason}）`, r.id)
    return r
  }

  /** 执行提醒：PENDING → NOTIFIED */
  function notify(id: string, method?: RecallMethod): boolean {
    const r = recalls.value.find((x) => x.id === id)
    if (!r || !canTransit(r.status, 'NOTIFIED')) return false
    if (!auth.can('recall:edit')) {
      console.warn('[recall] 无 recall:edit 权限')
      return false
    }
    const now = new Date().toISOString()
    r.status = 'NOTIFIED'
    if (method) r.method = method
    r.notifiedByName = auth.user.name
    r.notifiedAt = now
    pushTimeline(r, '已发送提醒', `方式：${method ?? r.method}`)
    activity.log(auth.user.name, `复诊提醒 ${r.recallNo} 已通过${method ?? r.method}触达客户`, r.id)
    return true
  }

  /** 登记客户确认复诊：NOTIFIED → CONFIRMED */
  function confirm(id: string, reply?: string, confirmedDate?: string): boolean {
    const r = recalls.value.find((x) => x.id === id)
    if (!r || !canTransit(r.status, 'CONFIRMED')) return false
    if (!auth.can('recall:edit')) {
      console.warn('[recall] 无 recall:edit 权限')
      return false
    }
    r.status = 'CONFIRMED'
    r.customerReply = reply?.trim() || undefined
    r.confirmedDate = confirmedDate
    pushTimeline(r, '客户确认复诊', reply)
    activity.log(auth.user.name, `复诊提醒 ${r.recallNo} 客户确认复诊${confirmedDate ? `（拟 ${confirmedDate}）` : ''}`, r.id)
    return true
  }

  /** 已预约落地：CONFIRMED/NOTIFIED → BOOKED */
  function markBooked(id: string, detail?: string): boolean {
    const r = recalls.value.find((x) => x.id === id)
    if (!r || !canTransit(r.status, 'BOOKED')) return false
    if (!auth.can('recall:edit')) {
      console.warn('[recall] 无 recall:edit 权限')
      return false
    }
    r.status = 'BOOKED'
    pushTimeline(r, '已生成预约', detail)
    activity.log(auth.user.name, `复诊提醒 ${r.recallNo} 已转为预约`, r.id)
    return true
  }

  /** 跳过：PENDING/NOTIFIED/CONFIRMED → SKIPPED */
  function skip(id: string, reason: string): boolean {
    const r = recalls.value.find((x) => x.id === id)
    if (!r || !canTransit(r.status, 'SKIPPED')) return false
    if (!auth.can('recall:edit')) {
      console.warn('[recall] 无 recall:edit 权限')
      return false
    }
    r.status = 'SKIPPED'
    r.skipReason = reason.trim()
    pushTimeline(r, '已跳过', reason)
    activity.log(auth.user.name, `复诊提醒 ${r.recallNo} 跳过：${reason}`, r.id)
    return true
  }

  /** 改期：调整建议复诊日期并回到待提醒（NOTIFIED → PENDING） */
  function reschedule(id: string, dueDate: string, note?: string): boolean {
    const r = recalls.value.find((x) => x.id === id)
    if (!r) return false
    if (!auth.can('recall:edit')) {
      console.warn('[recall] 无 recall:edit 权限')
      return false
    }
    r.dueDate = dueDate
    if (note) r.note = note.trim()
    if (r.status === 'NOTIFIED') {
      r.status = 'PENDING'
      r.notifiedByName = undefined
      r.notifiedAt = undefined
    }
    pushTimeline(r, '改期重新提醒', `复诊日期调整为 ${dueDate.slice(0, 10)}${note ? `；${note}` : ''}`)
    activity.log(auth.user.name, `复诊提醒 ${r.recallNo} 改期至 ${dueDate.slice(0, 10)}`, r.id)
    return true
  }

  /** 开发期种子 */
  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    const today = new Date()
    const iso = (d: Date) => d.toISOString().slice(0, 10)
    const dayShift = (n: number) => { const d = new Date(today); d.setDate(d.getDate() + n); return iso(d) }

    type Seed = Partial<Recall> & {
      customerName: string; reason: string; source: RecallChannel
      lastVisitDate: string; dueDate: string; status: RecallStatus
    }
    const seedData: Seed[] = [
      // 超期 3 天未提醒（医生建议光子嫩肤复治）
      { customerName: '王美丽', reason: '光子嫩肤二次治疗', source: 'DOCTOR_ADVICE', lastVisitDate: dayShift(-30), dueDate: dayShift(-3), status: 'PENDING', relatedEmrNo: 'EMR2026072501', method: 'PHONE' },
      // 今日待提醒（疗程跟进）
      { customerName: '陈思', reason: '热玛吉疗程第 2 次', source: 'COURSE_FOLLOW', lastVisitDate: dayShift(-45), dueDate: dayShift(0), status: 'PENDING', relatedOrderNo: 'SO20260710002', method: 'WECHAT' },
      // 今日待提醒
      { customerName: '赵敏', reason: '水光针补水复购', source: 'MANUAL', lastVisitDate: dayShift(-20), dueDate: dayShift(0), status: 'PENDING', method: 'PHONE' },
      // 2 天后到期
      { customerName: '林晚', reason: '瘦脸针补量', source: 'DOCTOR_ADVICE', lastVisitDate: dayShift(-90), dueDate: dayShift(2), status: 'PENDING', relatedEmrNo: 'EMR2026052003', method: 'WECHAT' },
      // 已提醒待确认
      { customerName: '周婷', reason: '果酸焕肤复诊', source: 'DOCTOR_ADVICE', lastVisitDate: dayShift(-15), dueDate: dayShift(-1), status: 'NOTIFIED', method: 'PHONE', customerReply: undefined },
      // 已提醒 - 客户确认
      { customerName: '吴桐', reason: '光子嫩肤术后复查', source: 'DOCTOR_ADVICE', lastVisitDate: dayShift(-10), dueDate: dayShift(-2), status: 'CONFIRMED', method: 'PHONE', customerReply: '周三下午可以到院', confirmedDate: dayShift(2) },
      // 已预约
      { customerName: '孙莉', reason: '热玛吉疗程第 3 次', source: 'COURSE_FOLLOW', lastVisitDate: dayShift(-60), dueDate: dayShift(-5), status: 'BOOKED', method: 'WECHAT', customerReply: '已约本周六上午', relatedOrderNo: 'SO20260625007' },
      // 已跳过
      { customerName: '李娜', reason: '皮肤检测复查', source: 'SYSTEM_AUTO', lastVisitDate: dayShift(-25), dueDate: dayShift(-8), status: 'SKIPPED', method: 'SMS', skipReason: '客户为外地游客，近期无法到院，下月再联系。' },
    ]

    seedData.forEach((s, i) => {
      seq += 1
      const createdIso = new Date(s.lastVisitDate).toISOString()
      const r: Recall = {
        id: nextId('rc'),
        recallNo: `RC202608${String(18 + i).padStart(2, '0')}0${i + 1}`,
        customerId: `C-60${i}`,
        customerName: s.customerName!,
        source: s.source,
        reason: s.reason!,
        relatedEmrNo: s.relatedEmrNo,
        relatedOrderNo: s.relatedOrderNo,
        lastVisitDate: s.lastVisitDate,
        dueDate: s.dueDate,
        method: s.method ?? 'PHONE',
        status: s.status,
        note: s.note,
        timeline: [{ at: createdIso, by: '顾屿（医生）', action: '创建复诊提醒', detail: s.reason }],
        createdAt: createdIso,
      }
      if (s.status !== 'PENDING') {
        const notifiedIso = new Date(s.dueDate).toISOString()
        r.notifiedByName = '夏沫（前台）'
        r.notifiedAt = notifiedIso
        r.timeline.push({ at: notifiedIso, by: '夏沫（前台）', action: '已发送提醒', detail: `方式：${r.method}` })
      }
      if (s.status === 'CONFIRMED' || s.status === 'BOOKED') {
        r.customerReply = s.customerReply
        r.confirmedDate = s.confirmedDate
        r.timeline.push({
          at: new Date(s.confirmedDate ?? s.dueDate).toISOString(),
          by: '夏沫（前台）', action: '客户确认复诊', detail: s.customerReply,
        })
      }
      if (s.status === 'BOOKED') {
        r.timeline.push({ at: new Date().toISOString(), by: '夏沫（前台）', action: '已生成预约', detail: s.customerReply })
      }
      if (s.status === 'SKIPPED') {
        r.skipReason = s.skipReason
        r.timeline.push({ at: new Date(s.dueDate).toISOString(), by: '夏沫（前台）', action: '已跳过', detail: s.skipReason })
      }
      recalls.value.push(r)
    })
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
