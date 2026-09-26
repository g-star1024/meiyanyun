// ============================================================
// FollowTask 跟进任务 store（M3-08，M3-B2 适配层切真）
// 数据源：/api/marketing/follow-tasks（marketing-service / follow_task，V55）。
// 适配层铁律：导出签名全保留（tasks/filterTab/pending/overdue/done/dueToday/
// doneThisMonth/filtered/get/create/complete/reassign/addLog/TYPE_LABEL/TYPE_ICON/
// STATUS_LABEL/PRIORITY_LABEL），FollowTasksView template/style 零改动。
// mock seed() 由 load() 替代（onMounted 挂载点同步改）；KPI 四键继续由 tasks
// 本地聚合（与后端 kpi 同库同口径；OVERDUE 由后端查询侧推导随列表下发）；
// create/complete/reassign/addLog 改 async：调 api 成功后才本地回写（响应行
// 整行替换），失败 console.warn 返 null/false 不落地。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import {
  appendFollowTaskLog,
  completeFollowTask,
  createFollowTask,
  fetchFollowTasks,
  reassignFollowTask,
} from '@/api/followtask'
import type { FollowTaskRow } from '@/api/followtask'
import { useActivityStore } from './activity'
import { useAuthStore } from './auth'

export type FollowTaskType = 'PHONE' | 'WECHAT' | 'IN_STORE' | 'BIRTHDAY' | 'POST_OP' | 'CONTENT'
export type FollowTaskStatus = 'PENDING' | 'DONE' | 'OVERDUE'
export type FollowTaskPriority = 'HIGH' | 'MEDIUM' | 'LOW'

export interface FollowTaskLog {
  by: string
  text: string
  at: string
}

export interface FollowTask {
  id: string
  customerName: string
  customerLevel: string
  type: FollowTaskType
  content: string
  deadline: string
  status: FollowTaskStatus
  priority: FollowTaskPriority
  assignee: string
  createdAt: string
  completedAt?: string
  logs: FollowTaskLog[]
}

const TYPE_LABEL: Record<FollowTaskType, string> = {
  PHONE: '电话回访',
  WECHAT: '企微跟进',
  IN_STORE: '到店提醒',
  BIRTHDAY: '生日关怀',
  POST_OP: '术后回访',
  CONTENT: '内容触达',
}
const TYPE_ICON: Record<FollowTaskType, string> = {
  PHONE: 'phone',
  WECHAT: 'chat',
  IN_STORE: 'store',
  BIRTHDAY: 'sun',
  POST_OP: 'check-square',
  CONTENT: 'volume',
}
const STATUS_LABEL: Record<FollowTaskStatus, string> = {
  PENDING: '待跟进',
  DONE: '已完成',
  OVERDUE: '已逾期',
}
const PRIORITY_LABEL: Record<FollowTaskPriority, string> = {
  HIGH: '高',
  MEDIUM: '中',
  LOW: '低',
}

function isToday(iso: string) {
  const d = new Date(iso)
  const n = new Date()
  return d.getFullYear() === n.getFullYear() && d.getMonth() === n.getMonth() && d.getDate() === n.getDate()
}
function isThisMonth(iso: string) {
  const d = new Date(iso)
  const n = new Date()
  return d.getFullYear() === n.getFullYear() && d.getMonth() === n.getMonth()
}

function mapRow(row: FollowTaskRow): FollowTask {
  return {
    id: row.id,
    customerName: row.customerName,
    customerLevel: row.customerLevel ?? '普通',
    type: row.type,
    content: row.content ?? '',
    deadline: row.deadline ?? '',
    status: row.status,
    priority: row.priority,
    assignee: row.assignee ?? '系统派发',
    createdAt: row.createdAt,
    completedAt: row.completedAt ?? undefined,
    logs: row.logs ?? [],
  }
}

export const useFollowTaskStore = defineStore('followtask', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const tasks = ref<FollowTask[]>([])
  const filterTab = ref<'ALL' | 'PENDING' | 'OVERDUE' | 'DONE'>('ALL')

  const pending = computed(() => tasks.value.filter((t) => t.status === 'PENDING'))
  const overdue = computed(() => tasks.value.filter((t) => t.status === 'OVERDUE'))
  const done = computed(() => tasks.value.filter((t) => t.status === 'DONE'))
  const dueToday = computed(() => tasks.value.filter((t) => t.status !== 'DONE' && isToday(t.deadline)))
  const doneThisMonth = computed(() => tasks.value.filter((t) => t.status === 'DONE' && t.completedAt && isThisMonth(t.completedAt)))

  const filtered = computed(() => {
    let list = tasks.value
    if (filterTab.value !== 'ALL') list = list.filter((t) => t.status === filterTab.value)
    return list.sort((a, b) => {
      const order: Record<FollowTaskStatus, number> = { OVERDUE: 0, PENDING: 1, DONE: 2 }
      if (order[a.status] !== order[b.status]) return order[a.status] - order[b.status]
      return new Date(a.deadline).getTime() - new Date(b.deadline).getTime()
    })
  })

  function get(id: string) {
    return tasks.value.find((t) => t.id === id)
  }

  function replaceRow(row: FollowTask) {
    const i = tasks.value.findIndex((x) => x.id === row.id)
    if (i >= 0) tasks.value[i] = row
  }

  async function create(input: {
    customerName: string
    customerLevel?: string
    type: FollowTaskType
    content: string
    deadline: string
    priority?: FollowTaskPriority
    assignee?: string
  }): Promise<FollowTask | null> {
    if (!auth.can('followuptask:edit')) {
      console.warn('[followtask] 无 followuptask:edit 权限')
      return null
    }
    try {
      const resp = await createFollowTask({
        customerName: input.customerName,
        customerLevel: input.customerLevel || '普通',
        type: input.type,
        content: input.content,
        deadline: new Date(input.deadline).toISOString(),
        priority: input.priority || 'MEDIUM',
        ...(input.assignee ? { assignee: input.assignee } : {}),
      })
      const t = mapRow(resp.data)
      tasks.value.unshift(t)
      activity.log(auth.user.name, `创建跟进任务：${t.customerName} - ${TYPE_LABEL[t.type]}`, t.id)
      return t
    } catch (e) {
      console.warn('[followtask] create failed', e)
      return null
    }
  }

  async function complete(id: string, note?: string): Promise<boolean> {
    const t = tasks.value.find((x) => x.id === id)
    if (!t || t.status === 'DONE' || !auth.can('followuptask:edit')) return false
    try {
      const resp = await completeFollowTask(id)
      let row = mapRow(resp.data)
      if (note) {
        const logResp = await appendFollowTaskLog(id, `完成：${note}`)
        row = mapRow(logResp.data)
      }
      replaceRow(row)
      activity.log(auth.user.name, `完成跟进任务：${row.customerName} - ${TYPE_LABEL[row.type]}`, row.id)
      return true
    } catch (e) {
      console.warn('[followtask] complete failed', e)
      return false
    }
  }

  async function reassign(id: string, assignee: string): Promise<boolean> {
    const t = tasks.value.find((x) => x.id === id)
    if (!t || t.status === 'DONE' || !auth.can('followuptask:edit')) return false
    try {
      const resp = await reassignFollowTask(id, assignee)
      replaceRow(mapRow(resp.data))
      activity.log(auth.user.name, `转派跟进任务 ${t.customerName} → ${assignee}`, t.id)
      return true
    } catch (e) {
      console.warn('[followtask] reassign failed', e)
      return false
    }
  }

  async function addLog(id: string, text: string): Promise<boolean> {
    const t = tasks.value.find((x) => x.id === id)
    if (!t || !auth.can('followuptask:edit')) return false
    try {
      const resp = await appendFollowTaskLog(id, text)
      replaceRow(mapRow(resp.data))
      return true
    } catch (e) {
      console.warn('[followtask] addLog failed', e)
      return false
    }
  }

  let loaded = false
  async function load() {
    if (loaded) return
    loaded = true
    try {
      const resp = await fetchFollowTasks()
      tasks.value = resp.data.tasks.map(mapRow)
    } catch (e) {
      loaded = false
      console.warn('[followtask] load failed', e)
    }
  }

  return {
    tasks, filterTab,
    pending, overdue, done, dueToday, doneThisMonth, filtered,
    get, create, complete, reassign, addLog, load,
    TYPE_LABEL, TYPE_ICON, STATUS_LABEL, PRIORITY_LABEL,
  }
})
