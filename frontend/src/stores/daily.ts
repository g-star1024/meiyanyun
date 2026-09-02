// ============================================================
// Daily 门店日报 store（M2-06）
// 设计稿 SCREEN-M2-06 Desktop/Tablet 均空白，按业务域自建：
// 当日客流/成交/服务/库存汇总 KPI、分时客流趋势、待办清单、异常与说明、日报提交。
// 状态机：DRAFT（草稿，可编辑）→ SUBMITTED（已提交区域，锁定留痕）。
// 一天一份，今日日报不存在时按日期自动建草稿。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

export type DailyStatus = 'DRAFT' | 'SUBMITTED'
export type DailyTodoKind = 'TASK' | 'CUSTOMER' | 'ISSUE'

export interface DailyTodo {
  id: string
  content: string
  kind: DailyTodoKind
  done: boolean
  urgent: boolean
}

export interface DailyTimelineEntry {
  action: string
  by: string
  at: string
}

export interface DailyReport {
  id: string
  dailyNo: string
  date: string // YYYY-MM-DD
  status: DailyStatus
  footfall: number // 今日客流
  orders: number // 今日成交（订单数）
  services: number // 今日服务工单完成数
  inventoryAlerts: number // 库存预警数
  hourly: number[] // 营业时段 10:00~21:00 共 12 个时点的客流
  todos: DailyTodo[]
  exceptions: string // 异常与说明
  note: string // 日报备注
  submittedBy?: string
  submittedAt?: string
  timeline: DailyTimelineEntry[]
}

const HOURS = [10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21]

const TODO_KIND_LABEL: Record<DailyTodoKind, string> = {
  TASK: '待办事务',
  CUSTOMER: '客户跟进',
  ISSUE: '异常处理',
}

export const useDailyStore = defineStore('daily', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const reports = ref<DailyReport[]>([])

  const drafts = computed(() => reports.value.filter((r) => r.status === 'DRAFT'))
  const submitted = computed(() => reports.value.filter((r) => r.status === 'SUBMITTED'))
  const todayDate = () => new Date().toISOString().slice(0, 10)

  const todayReport = computed<DailyReport | null>(() => {
    return reports.value.find((r) => r.date === todayDate()) ?? null
  })
  const openTodos = computed(() => {
    const t = todayReport.value
    if (!t) return 0
    return t.todos.filter((x) => !x.done).length
  })

  function ensureToday(): DailyReport {
    const existing = todayReport.value
    if (existing) return existing
    const d = todayDate()
    const r: DailyReport = {
      id: nextId('daily'),
      dailyNo: `DR-${d.replace(/-/g, '')}`,
      date: d,
      status: 'DRAFT',
      footfall: 0,
      orders: 0,
      services: 0,
      inventoryAlerts: 0,
      hourly: HOURS.map(() => 0),
      todos: [],
      exceptions: '',
      note: '',
      timeline: [{ action: '创建今日日报草稿', by: auth.user.name, at: new Date().toISOString() }],
    }
    reports.value.unshift(r)
    return r
  }

  function get(id: string) {
    return reports.value.find((r) => r.id === id)
  }

  function save(input: { footfall?: number; orders?: number; services?: number; inventoryAlerts?: number; exceptions?: string; note?: string }) {
    if (!auth.can('daily:edit')) {
      console.warn('[daily] 无 daily:edit 权限')
      return false
    }
    const r = ensureToday()
    if (r.status !== 'DRAFT') return false
    let changed = false
    if (input.footfall !== undefined) { r.footfall = input.footfall; changed = true }
    if (input.orders !== undefined) { r.orders = input.orders; changed = true }
    if (input.services !== undefined) { r.services = input.services; changed = true }
    if (input.inventoryAlerts !== undefined) { r.inventoryAlerts = input.inventoryAlerts; changed = true }
    if (input.exceptions !== undefined) { r.exceptions = input.exceptions; changed = true }
    if (input.note !== undefined) { r.note = input.note; changed = true }
    if (changed) {
      r.hourly = HOURS.map((_, i) => r.hourly[i] ?? 0)
      activity.log(auth.user.name, `更新门店日报 ${r.dailyNo}`, r.id)
    }
    return true
  }

  function setHourly(hourly: number[]) {
    if (!auth.can('daily:edit')) return false
    const r = ensureToday()
    if (r.status !== 'DRAFT') return false
    r.hourly = hourly.slice(0, HOURS.length)
    r.footfall = r.hourly.reduce((a, b) => a + (b || 0), 0)
    return true
  }

  function addTodo(input: { content: string; kind: DailyTodoKind; urgent?: boolean }) {
    if (!auth.can('daily:edit')) return false
    const r = ensureToday()
    if (r.status !== 'DRAFT' || !input.content.trim()) return false
    r.todos.push({
      id: nextId('dtodo'),
      content: input.content.trim(),
      kind: input.kind,
      done: false,
      urgent: !!input.urgent,
    })
    activity.log(auth.user.name, `日报新增待办：${input.content.trim()}`, r.id)
    return true
  }

  function toggleTodo(todoId: string) {
    const r = ensureToday()
    const t = r.todos.find((x) => x.id === todoId)
    if (!t) return false
    t.done = !t.done
    return true
  }

  function removeTodo(todoId: string) {
    if (!auth.can('daily:edit')) return false
    const r = ensureToday()
    if (r.status !== 'DRAFT') return false
    r.todos = r.todos.filter((x) => x.id !== todoId)
    return true
  }

  function submit() {
    if (!auth.can('daily:submit')) {
      console.warn('[daily] 无 daily:submit 权限')
      return false
    }
    const r = ensureToday()
    if (r.status === 'SUBMITTED') return false
    r.status = 'SUBMITTED'
    r.submittedBy = auth.user.name
    r.submittedAt = new Date().toISOString()
    r.timeline.push({ action: '提交门店日报', by: auth.user.name, at: r.submittedAt })
    activity.log(auth.user.name, `提交门店日报 ${r.dailyNo}`, r.id)
    return true
  }

  // ===== 种子数据 =====
  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    const today = todayDate()
    const yest = new Date(Date.now() - 86400_000).toISOString().slice(0, 10)

    // 昨日已提交日报
    const yHourly = [6, 4, 9, 7, 11, 13, 10, 14, 18, 15, 9, 5]
    reports.value.push({
      id: nextId('daily'),
      dailyNo: `DR-${yest.replace(/-/g, '')}`,
      date: yest,
      status: 'SUBMITTED',
      footfall: yHourly.reduce((a, b) => a + b, 0),
      orders: 23,
      services: 18,
      inventoryAlerts: 2,
      hourly: yHourly,
      todos: [
        { id: nextId('dtodo'), content: '补货：玻尿酸精华液 5 盒', kind: 'TASK', done: true, urgent: false },
        { id: nextId('dtodo'), content: '回访客户赵雨晴射频紧肤效果', kind: 'CUSTOMER', done: true, urgent: false },
        { id: nextId('dtodo'), content: '报修超声刀治疗仪 E07 故障已转工单', kind: 'ISSUE', done: true, urgent: true },
      ],
      exceptions: '超声刀治疗仪上午出现 E07 报错，已转服务工单并暂停使用。',
      note: '昨日整体平稳，晚高峰客流偏高，建议增加晚班咨询师排班。',
      submittedBy: '陈雅琳（店长）',
      submittedAt: `${yest}T21:30:00.000Z`,
      timeline: [
        { action: '创建门店日报草稿', by: '陈雅琳（店长）', at: `${yest}T21:00:00.000Z` },
        { action: '提交门店日报', by: '陈雅琳（店长）', at: `${yest}T21:30:00.000Z` },
      ],
    })

    // 今日草稿（含部分预填数据，由店长补充）
    const tHourly = [4, 3, 6, 5, 8, 7, 9, 11, 10, 12, 8, 0]
    // 若组件 setup 已提前 ensureToday() 建了空草稿，则复用并填充，避免同日两份
    const existingToday = reports.value.find((r) => r.date === today)
    const todayDraft: DailyReport = existingToday ?? {
      id: nextId('daily'),
      dailyNo: `DR-${today.replace(/-/g, '')}`,
      date: today,
      status: 'DRAFT',
      footfall: 0,
      orders: 0,
      services: 0,
      inventoryAlerts: 0,
      hourly: HOURS.map(() => 0),
      todos: [],
      exceptions: '',
      note: '',
      timeline: [],
    }
    Object.assign(todayDraft, {
      footfall: tHourly.reduce((a, b) => a + b, 0),
      orders: 14,
      services: 11,
      inventoryAlerts: 1,
      hourly: tHourly,
      todos: [
        { id: nextId('dtodo'), content: '跟进客户孙佳宁玻尿酸术后护理咨询', kind: 'CUSTOMER', done: false, urgent: false },
        { id: nextId('dtodo'), content: '盘点 A 区耗材并补货', kind: 'TASK', done: false, urgent: false },
        { id: nextId('dtodo'), content: '空调出风异味处理进度确认', kind: 'ISSUE', done: false, urgent: true },
      ],
      exceptions: '',
      note: '',
    })
    if (!existingToday) {
      todayDraft.timeline = [{ action: '创建今日日报草稿', by: auth.user.name, at: new Date().toISOString() }]
      reports.value.unshift(todayDraft)
    }
  }

  return {
    reports, drafts, submitted, todayReport, openTodos,
    ensureToday, get, save, setHourly, addTodo, toggleTodo, removeTodo, submit, seed,
    HOURS, TODO_KIND_LABEL,
  }
})
