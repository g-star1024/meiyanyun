import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import * as dailyApi from '@/api/daily'
import { useToast } from '@/composables/useToast'
import { useAuthStore } from '@/stores/auth'
import { errMsg } from '@/stores/m5Coupon'
import { useStoreContext } from '@/stores/storeContext'
import { shDateStr } from '@/utils/datetime'

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
  date: string
  status: DailyStatus
  footfall: number
  orders: number
  services: number
  inventoryAlerts: number
  hourly: number[]
  todos: DailyTodo[]
  exceptions: string
  note: string
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

function adapt(d: dailyApi.DailyReportDto): DailyReport {
  return {
    id: String(d.id),
    dailyNo: d.dailyNo,
    date: d.date,
    status: d.status as DailyStatus,
    footfall: d.footfall,
    orders: d.orders,
    services: d.services,
    inventoryAlerts: d.inventoryAlerts,
    hourly: d.hourly.slice(0, HOURS.length),
    todos: d.todos.map((t) => ({
      id: String(t.id),
      content: t.content,
      kind: t.kind as DailyTodoKind,
      done: t.done,
      urgent: t.urgent,
    })),
    exceptions: d.exceptions ?? '',
    note: d.note ?? '',
    submittedBy: d.submittedBy ?? undefined,
    submittedAt: d.submittedAt ?? undefined,
    timeline: d.timeline ?? [],
  }
}

export const useDailyStore = defineStore('daily', () => {
  const auth = useAuthStore()
  const ctx = useStoreContext()
  const toast = useToast()

  const reports = ref<DailyReport[]>([])

  const drafts = computed(() => reports.value.filter((r) => r.status === 'DRAFT'))
  const submitted = computed(() => reports.value.filter((r) => r.status === 'SUBMITTED'))
  const todayDate = () => shDateStr()

  const todayReport = computed<DailyReport | null>(() => {
    return reports.value.find((r) => r.date === todayDate()) ?? null
  })
  const openTodos = computed(() => {
    const t = todayReport.value
    if (!t) return 0
    return t.todos.filter((x) => !x.done).length
  })

  function transientShell(): DailyReport {
    const d = todayDate()
    return {
      id: '',
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
      timeline: [],
    }
  }

  function ensureToday(): DailyReport {
    return todayReport.value ?? transientShell()
  }

  function get(id: string) {
    return reports.value.find((r) => r.id === id)
  }

  async function load() {
    try {
      const params: { storeCode?: string; status?: string } = {}
      const sc = ctx.currentStoreCode
      if (sc) params.storeCode = sc
      const { data } = await dailyApi.listDailyReports(params)
      reports.value = data.map(adapt)
    } catch (e) {
      reports.value = []
      toast.error(errMsg(e, '日报加载失败，请稍后重试'))
    }
  }

  async function seed() {
    await ctx.loadStores()
    try {
      const sc = ctx.currentStoreCode
      const { data } = await dailyApi.getTodayDailyReport(sc || undefined)
      const today = adapt(data)
      const rest = reports.value.filter((r) => r.date !== today.date)
      reports.value = [today, ...rest]
      await load()
    } catch (e) {
      toast.error(errMsg(e, '日报加载失败，请稍后重试'))
      await load()
    }
  }

  async function save(input: {
    footfall?: number
    orders?: number
    services?: number
    inventoryAlerts?: number
    exceptions?: string
    note?: string
  }): Promise<boolean> {
    if (!auth.can('daily:edit')) {
      toast.error('无保存权限，请联系管理员')
      return false
    }
    const r = ensureToday()
    if (!r.id || r.status !== 'DRAFT') return false
    try {
      const { data } = await dailyApi.saveDailyFields(r.id, input)
      replaceReport(adapt(data))
      return true
    } catch (e) {
      toast.error(errMsg(e, '保存失败，请稍后重试'))
      return false
    }
  }

  async function setHourly(hourly: number[]): Promise<boolean> {
    if (!auth.can('daily:edit')) return false
    const r = ensureToday()
    if (!r.id || r.status !== 'DRAFT') return false
    try {
      const { data } = await dailyApi.saveDailyHourly(r.id, hourly)
      replaceReport(adapt(data))
      return true
    } catch (e) {
      toast.error(errMsg(e, '分时客流保存失败，请稍后重试'))
      return false
    }
  }

  async function addTodo(input: {
    content: string
    kind: DailyTodoKind
    urgent?: boolean
  }): Promise<boolean> {
    if (!auth.can('daily:edit')) return false
    const r = ensureToday()
    if (!r.id || r.status !== 'DRAFT' || !input.content.trim()) return false
    try {
      const { data } = await dailyApi.addDailyTodo(r.id, {
        content: input.content.trim(),
        kind: input.kind,
        urgent: !!input.urgent,
      })
      replaceReport(adapt(data))
      return true
    } catch (e) {
      toast.error(errMsg(e, '新增待办失败，请稍后重试'))
      return false
    }
  }

  async function toggleTodo(todoId: string): Promise<boolean> {
    const r = ensureToday()
    if (!r.id || r.status !== 'DRAFT') return false
    try {
      const { data } = await dailyApi.toggleDailyTodo(r.id, todoId)
      replaceReport(adapt(data))
      return true
    } catch (e) {
      toast.error(errMsg(e, '待办状态更新失败，请稍后重试'))
      return false
    }
  }

  async function removeTodo(todoId: string): Promise<boolean> {
    if (!auth.can('daily:edit')) return false
    const r = ensureToday()
    if (!r.id || r.status !== 'DRAFT') return false
    try {
      const { data } = await dailyApi.removeDailyTodo(r.id, todoId)
      replaceReport(adapt(data))
      return true
    } catch (e) {
      toast.error(errMsg(e, '删除待办失败，请稍后重试'))
      return false
    }
  }

  async function submit(): Promise<boolean> {
    if (!auth.can('daily:submit')) {
      toast.error('无提交权限，请联系管理员')
      return false
    }
    const r = ensureToday()
    if (!r.id) return false
    if (r.status === 'SUBMITTED') return false
    try {
      const { data } = await dailyApi.submitDailyReport(r.id)
      replaceReport(adapt(data))
      toast.success('日报已提交')
      return true
    } catch (e) {
      toast.error(errMsg(e, '提交日报失败，请稍后重试'))
      return false
    }
  }

  function replaceReport(next: DailyReport) {
    const idx = reports.value.findIndex((r) => r.id === next.id)
    if (idx >= 0) reports.value.splice(idx, 1, next)
    else reports.value.unshift(next)
  }

  return {
    reports, drafts, submitted, todayReport, openTodos,
    ensureToday, get, save, setHourly, addTodo, toggleTodo, removeTodo, submit, seed,
    HOURS, TODO_KIND_LABEL,
  }
})
