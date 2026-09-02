// ============================================================
// Handover 聚合 store（交接班 / 双签交接）
// 状态机：DRAFT（交班人填写中）→ SUBMITTED（已提交待接班确认）→ CONFIRMED（接班人已确认）。
// - 一笔交接单承载本班营收汇总、待跟进客户、未完成事项、重要提醒、钱款/设备交接。
// - 交班人提交即锁定内容，接班人确认后形成双签留痕（submittedAt / confirmedAt + 操作人）。
// - 接班人可在确认时填写备注；CONFIRMED 后不可再改。
// 权限：handover:create 建单/提交 / handover:edit 接班确认。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

export type HandoverShift = 'MORNING' | 'EVENING' | 'FULL'
export type HandoverStatus = 'DRAFT' | 'SUBMITTED' | 'CONFIRMED'

export interface HandoverTodo {
  id: string
  /** 待跟进事项类型：客户跟进 / 待办事务 / 异常处理 */
  kind: 'CUSTOMER' | 'TASK' | 'ISSUE'
  content: string
  /** 紧急程度 */
  urgent: boolean
  done?: boolean
}

export interface HandoverTimelineEntry {
  at: string
  by: string
  action: string
  detail?: string
}

export interface Handover {
  id: string
  handoverNo: string
  shift: HandoverShift
  /** 交班营业日 */
  date: string
  status: HandoverStatus
  /** 交班人 / 接班人 */
  fromName: string
  toName: string
  // —— 本班经营摘要 ——
  revenueAmount: number
  orderCount: number
  arrivalCount: number
  // —— 交接内容 ——
  /** 待跟进客户/事项 */
  todos: HandoverTodo[]
  /** 重要提醒（口头重点） */
  importantNote?: string
  /** 钱款交接说明（现金、备用金等） */
  cashNote?: string
  /** 设备/物料交接说明 */
  equipmentNote?: string
  /** 接班人确认备注 */
  confirmNote?: string
  // —— 双签留痕 ——
  submittedAt?: string
  confirmedAt?: string
  timeline: HandoverTimelineEntry[]
  createdAt: string
}

const SHIFT_LABEL: Record<HandoverShift, string> = {
  MORNING: '早班', EVENING: '晚班', FULL: '全天',
}
const STATUS_LABEL: Record<HandoverStatus, string> = {
  DRAFT: '草稿', SUBMITTED: '待确认', CONFIRMED: '已交接',
}
const TODO_KIND_LABEL: Record<HandoverTodo['kind'], string> = {
  CUSTOMER: '客户跟进', TASK: '待办事务', ISSUE: '异常处理',
}

const TRANSITIONS: Record<HandoverStatus, HandoverStatus[]> = {
  DRAFT: ['SUBMITTED'],
  SUBMITTED: ['CONFIRMED', 'DRAFT'], // 接班人可退回让交班人补充
  CONFIRMED: [],
}
function canTransit(from: HandoverStatus, to: HandoverStatus): boolean {
  return TRANSITIONS[from]?.includes(to) ?? false
}

export const useHandoverStore = defineStore('handover', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const handovers = ref<Handover[]>([])
  let seq = 0

  const drafts = computed(() => handovers.value.filter((h) => h.status === 'DRAFT'))
  const submitted = computed(() => handovers.value.filter((h) => h.status === 'SUBMITTED'))
  const confirmed = computed(() => handovers.value.filter((h) => h.status === 'CONFIRMED'))

  const pendingCount = computed(() => submitted.value.length)
  /** 今日已交接笔数 */
  const todayConfirmed = computed(() => {
    const today = new Date().toISOString().slice(0, 10)
    return confirmed.value.filter((h) => h.date.slice(0, 10) === today).length
  })
  /** 未完成待跟进事项总数（已交接单中的未勾选项） */
  const openTodos = computed(() =>
    confirmed.value.reduce((n, h) => n + h.todos.filter((t) => !t.done).length, 0),
  )

  function get(id: string) {
    return handovers.value.find((h) => h.id === id)
  }

  function pushTimeline(h: Handover, action: string, detail?: string) {
    h.timeline.push({ at: new Date().toISOString(), by: auth.user.name, action, detail })
  }

  /** 创建交接班草稿 */
  function create(input: {
    shift: HandoverShift
    date: string
    toName: string
    revenueAmount?: number
    orderCount?: number
    arrivalCount?: number
  }): Handover | null {
    if (!auth.can('handover:create')) {
      console.warn('[handover] 无 handover:create 权限')
      return null
    }
    seq += 1
    const now = new Date().toISOString()
    const h: Handover = {
      id: nextId('ho'),
      handoverNo: `HJ${input.date.replace(/-/g, '').slice(2)}${seq}`,
      shift: input.shift,
      date: input.date,
      status: 'DRAFT',
      fromName: auth.user.name,
      toName: input.toName,
      revenueAmount: input.revenueAmount ?? 0,
      orderCount: input.orderCount ?? 0,
      arrivalCount: input.arrivalCount ?? 0,
      todos: [],
      timeline: [{ at: now, by: auth.user.name, action: '创建交接单' }],
      createdAt: now,
    }
    handovers.value.unshift(h)
    activity.log(auth.user.name, `创建交接班单 ${h.handoverNo}（${SHIFT_LABEL[h.shift]}）`, h.id)
    return h
  }

  /** 更新草稿内容（仅 DRAFT 可编辑） */
  function updateDraft(
    id: string,
    patch: Partial<Pick<Handover, 'revenueAmount' | 'orderCount' | 'arrivalCount' | 'importantNote' | 'cashNote' | 'equipmentNote' | 'toName'>>,
  ): boolean {
    const h = handovers.value.find((x) => x.id === id)
    if (!h || h.status !== 'DRAFT') return false
    if (!auth.can('handover:create')) return false
    Object.assign(h, patch)
    return true
  }

  /** 添加待跟进事项（仅 DRAFT） */
  function addTodo(id: string, todo: Omit<HandoverTodo, 'id' | 'done'>): boolean {
    const h = handovers.value.find((x) => x.id === id)
    if (!h || h.status !== 'DRAFT') return false
    if (!auth.can('handover:create')) return false
    h.todos.push({ ...todo, id: nextId('todo'), done: false })
    return true
  }
  function removeTodo(id: string, todoId: string): boolean {
    const h = handovers.value.find((x) => x.id === id)
    if (!h || h.status !== 'DRAFT') return false
    if (!auth.can('handover:create')) return false
    h.todos = h.todos.filter((t) => t.id !== todoId)
    return true
  }
  /** 接班人勾选已跟进事项（SUBMITTED/CONFIRMED 可操作） */
  function toggleTodo(id: string, todoId: string): boolean {
    const h = handovers.value.find((x) => x.id === id)
    if (!h || h.status === 'DRAFT') return false
    const t = h.todos.find((x) => x.id === todoId)
    if (!t) return false
    t.done = !t.done
    return true
  }

  /** 交班人提交：DRAFT → SUBMITTED（必须有接班人） */
  function submit(id: string): boolean {
    const h = handovers.value.find((x) => x.id === id)
    if (!h || !canTransit(h.status, 'SUBMITTED')) return false
    if (!auth.can('handover:create')) return false
    if (!h.toName.trim()) return false
    const now = new Date().toISOString()
    h.status = 'SUBMITTED'
    h.submittedAt = now
    pushTimeline(h, '交班人提交', `${auth.user.name} → ${h.toName}`)
    activity.log(auth.user.name, `交接班单 ${h.handoverNo} 已提交，待 ${h.toName} 确认`, h.id)
    return true
  }

  /** 接班人确认：SUBMITTED → CONFIRMED（双签完成） */
  function confirm(id: string, note?: string): boolean {
    const h = handovers.value.find((x) => x.id === id)
    if (!h || !canTransit(h.status, 'CONFIRMED')) return false
    if (!auth.can('handover:edit')) {
      console.warn('[handover] 无 handover:edit 权限')
      return false
    }
    const now = new Date().toISOString()
    h.status = 'CONFIRMED'
    h.confirmedAt = now
    h.confirmNote = note?.trim() || undefined
    pushTimeline(h, '接班人确认接收', note)
    activity.log(auth.user.name, `交接班单 ${h.handoverNo} 已由 ${auth.user.name} 确认，双签完成`, h.id)
    return true
  }

  /** 退回补充：SUBMITTED → DRAFT（接班人退回交班人修改） */
  function sendBack(id: string, reason: string): boolean {
    const h = handovers.value.find((x) => x.id === id)
    if (!h || !canTransit(h.status, 'DRAFT')) return false
    if (!auth.can('handover:edit')) return false
    h.status = 'DRAFT'
    h.submittedAt = undefined
    pushTimeline(h, '退回补充', reason)
    activity.log(auth.user.name, `交接班单 ${h.handoverNo} 退回补充：${reason}`, h.id)
    return true
  }

  /** 开发期种子 */
  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    const today = new Date().toISOString().slice(0, 10)
    const yesterday = new Date(Date.now() - 86400000).toISOString().slice(0, 10)

    const seedDefs: Array<{
      shift: HandoverShift; date: string; status: HandoverStatus
      fromName: string; toName: string
      revenue: number; orders: number; arrivals: number
      todos: Array<Omit<HandoverTodo, 'id'>>
      importantNote?: string; cashNote?: string; equipmentNote?: string
      confirmNote?: string
    }> = [
      {
        shift: 'MORNING', date: today, status: 'SUBMITTED',
        fromName: '夏沫（前台）', toName: '苏晴（店长）',
        revenue: 12680, orders: 9, arrivals: 14,
        importantNote: '王美丽光子嫩肤后轻微泛红，已安抚并约周三复诊；VIP 陈思到店需优先安排顾医生。',
        cashNote: '收银现金 ¥2,300 已投保险柜，备用金 ¥500 留存。',
        equipmentNote: '2 号治疗室冰敷仪故障，已贴停用标签并报工程。',
        todos: [
          { kind: 'CUSTOMER', content: '赵敏热玛吉疗程第 2 次，确认明日到店时间', urgent: true, done: false },
          { kind: 'ISSUE', content: '2 号冰敷仪维修跟进', urgent: false, done: false },
          { kind: 'TASK', content: '光子嫩肤耗材补货申请', urgent: false, done: false },
        ],
      },
      {
        shift: 'EVENING', date: yesterday, status: 'CONFIRMED',
        fromName: '夏沫（前台）', toName: '林微（咨询）',
        revenue: 8420, orders: 6, arrivals: 9,
        importantNote: '晚班无异常；储值卡客户周婷已预约周六热玛吉。',
        cashNote: '无现金交易，备用金账实相符。',
        equipmentNote: '设备正常。',
        confirmNote: '已核对营收与设备，待跟进事项已接收。',
        todos: [
          { kind: 'CUSTOMER', content: '周婷周六热玛吉到店提醒', urgent: false, done: true },
          { kind: 'TASK', content: '整理本周到店未成交客户名单', urgent: false, done: false },
        ],
      },
      {
        shift: 'FULL', date: yesterday, status: 'CONFIRMED',
        fromName: '苏晴（店长）', toName: '陈野（区域）',
        revenue: 21100, orders: 15, arrivals: 23,
        importantNote: '全天营业正常；新客 4 人，成交 2 人。',
        cashNote: '当日现金全部缴存，账实相符。',
        equipmentNote: '设备正常。',
        confirmNote: '已确认。',
        todos: [
          { kind: 'TASK', content: '周一区域例会数据准备', urgent: false, done: true },
        ],
      },
    ]

    seedDefs.forEach((s, i) => {
      seq += 1
      const createdIso = new Date(s.date + 'T08:00:00').toISOString()
      const h: Handover = {
        id: nextId('ho'),
        handoverNo: `HJ${s.date.replace(/-/g, '').slice(2)}${i + 1}`,
        shift: s.shift,
        date: s.date,
        status: s.status,
        fromName: s.fromName,
        toName: s.toName,
        revenueAmount: s.revenue,
        orderCount: s.orders,
        arrivalCount: s.arrivals,
        todos: s.todos.map((t) => ({ ...t, id: nextId('todo') })),
        importantNote: s.importantNote,
        cashNote: s.cashNote,
        equipmentNote: s.equipmentNote,
        confirmNote: s.confirmNote,
        timeline: [{ at: createdIso, by: s.fromName, action: '创建交接单' }],
        createdAt: createdIso,
      }
      if (s.status !== 'DRAFT') {
        h.submittedAt = new Date(s.date + 'T15:00:00').toISOString()
        h.timeline.push({ at: h.submittedAt, by: s.fromName, action: '交班人提交', detail: `${s.fromName} → ${s.toName}` })
      }
      if (s.status === 'CONFIRMED') {
        h.confirmedAt = new Date(s.date + 'T15:30:00').toISOString()
        h.timeline.push({ at: h.confirmedAt, by: s.toName, action: '接班人确认接收', detail: s.confirmNote })
      }
      handovers.value.push(h)
    })
  }

  return {
    handovers, drafts, submitted, confirmed, pendingCount, todayConfirmed, openTodos,
    get, create, updateDraft, addTodo, removeTodo, toggleTodo, submit, confirm, sendBack, seed,
    SHIFT_LABEL, STATUS_LABEL, TODO_KIND_LABEL,
  }
})

export { SHIFT_LABEL as HANDOVER_SHIFT_LABEL, STATUS_LABEL as HANDOVER_STATUS_LABEL, TODO_KIND_LABEL as HANDOVER_TODO_KIND_LABEL }
