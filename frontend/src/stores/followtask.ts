// ============================================================
// FollowTask 跟进任务 store（M3-08）
// 类型：电话/企微/到店/生日/术后/内容；状态：待跟进/已完成/已逾期。
// KPI：我的待跟进 / 今日到期 / 已逾期 / 本月完成。
// 权限：followuptask:view / followuptask:edit。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
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

  function create(input: {
    customerName: string
    customerLevel?: string
    type: FollowTaskType
    content: string
    deadline: string
    priority?: FollowTaskPriority
    assignee?: string
  }): FollowTask | null {
    if (!auth.can('followuptask:edit')) {
      console.warn('[followtask] 无 followuptask:edit 权限')
      return null
    }
    const now = new Date()
    const t: FollowTask = {
      id: nextId('ft'),
      customerName: input.customerName,
      customerLevel: input.customerLevel || '普通',
      type: input.type,
      content: input.content,
      deadline: new Date(input.deadline).toISOString(),
      status: 'PENDING',
      priority: input.priority || 'MEDIUM',
      assignee: input.assignee || auth.user.name,
      createdAt: now.toISOString(),
      logs: [{ by: auth.user.name, text: '创建跟进任务', at: now.toISOString() }],
    }
    tasks.value.unshift(t)
    activity.log(auth.user.name, `创建跟进任务：${t.customerName} - ${TYPE_LABEL[t.type]}`, t.id)
    return t
  }

  function complete(id: string, note?: string): boolean {
    const t = tasks.value.find((x) => x.id === id)
    if (!t || t.status === 'DONE' || !auth.can('followuptask:edit')) return false
    t.status = 'DONE'
    t.completedAt = new Date().toISOString()
    t.logs.unshift({ by: auth.user.name, text: note ? `完成：${note}` : '标记完成', at: t.completedAt })
    activity.log(auth.user.name, `完成跟进任务：${t.customerName} - ${TYPE_LABEL[t.type]}`, t.id)
    return true
  }

  function reassign(id: string, assignee: string): boolean {
    const t = tasks.value.find((x) => x.id === id)
    if (!t || t.status === 'DONE' || !auth.can('followuptask:edit')) return false
    t.assignee = assignee
    t.logs.unshift({ by: auth.user.name, text: `转派给 ${assignee}`, at: new Date().toISOString() })
    activity.log(auth.user.name, `转派跟进任务 ${t.customerName} → ${assignee}`, t.id)
    return true
  }

  function addLog(id: string, text: string): boolean {
    const t = tasks.value.find((x) => x.id === id)
    if (!t || !auth.can('followuptask:edit')) return false
    t.logs.unshift({ by: auth.user.name, text, at: new Date().toISOString() })
    return true
  }

  // ===== 种子 =====
  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    const now = new Date()
    const daysAgo = (d: number) => new Date(now.getTime() - d * 86400_000).toISOString()
    const hoursLater = (h: number) => new Date(now.getTime() + h * 3600_000).toISOString()
    const hoursAgo = (h: number) => new Date(now.getTime() - h * 3600_000).toISOString()
    type Seed = Omit<FollowTask, 'id' | 'logs' | 'createdAt'> & { createdAt: string }
    const base: Seed[] = [
      { customerName: '林晚', customerLevel: '钻石', type: 'POST_OP', content: '术后第3天回访，确认红肿消退情况，附护理提示', deadline: hoursLater(6), status: 'PENDING', priority: 'HIGH', assignee: '林微', createdAt: hoursAgo(4) },
      { customerName: '苏晴', customerLevel: '黄金', type: 'BIRTHDAY', content: '生日关怀，发送祝福短信并推送生日礼遇券', deadline: hoursLater(28), status: 'PENDING', priority: 'MEDIUM', assignee: '林微', createdAt: hoursAgo(2) },
      { customerName: '王蕊', customerLevel: '黄金', type: 'CONTENT', content: '沉睡唤醒，推送限时优惠到企微，引导回店', deadline: daysAgo(2), status: 'OVERDUE', priority: 'HIGH', assignee: '苏晴', createdAt: daysAgo(4) },
      { customerName: '陈思', customerLevel: '白金', type: 'IN_STORE', content: '复诊提醒，热玛吉第2疗程，预约本周六到店', deadline: hoursLater(50), status: 'DONE', priority: 'LOW', assignee: '林微', completedAt: hoursAgo(20), createdAt: daysAgo(3) },
      { customerName: '张敏', customerLevel: '金卡', type: 'PHONE', content: '术后回访，确认水光针后皮肤状态，提醒补水', deadline: hoursLater(30), status: 'PENDING', priority: 'MEDIUM', assignee: '苏晴', createdAt: hoursAgo(6) },
      { customerName: '王芳', customerLevel: '银卡', type: 'WECHAT', content: '复购意向高，同步推送季度焕肤套餐', deadline: hoursAgo(3), status: 'OVERDUE', priority: 'HIGH', assignee: '林微', createdAt: daysAgo(5) },
      { customerName: '周岚', customerLevel: '钻石', type: 'POST_OP', content: '术后7天回访，确认结痂脱落，预约复查', deadline: hoursLater(72), status: 'PENDING', priority: 'MEDIUM', assignee: '苏晴', createdAt: hoursAgo(12) },
      { customerName: '李娜', customerLevel: '普通', type: 'BIRTHDAY', content: '生日祝福 + 到店礼券发放', deadline: hoursAgo(72), status: 'DONE', priority: 'LOW', assignee: '林微', completedAt: hoursAgo(80), createdAt: daysAgo(8) },
    ]
    base.forEach((s) => {
      const logs: FollowTaskLog[] = [{ by: '系统', text: '自动创建', at: s.createdAt }]
      if (s.status === 'DONE' && s.completedAt) {
        logs.unshift({ by: s.assignee, text: '已按计划完成跟进', at: s.completedAt })
      }
      if (s.status === 'OVERDUE') {
        logs.unshift({ by: '系统', text: '任务已逾期，请尽快处理', at: hoursAgo(2) })
      }
      tasks.value.push({ id: nextId('ft'), ...s, logs })
    })
  }

  return {
    tasks, filterTab,
    pending, overdue, done, dueToday, doneThisMonth, filtered,
    get, create, complete, reassign, addLog, seed,
    TYPE_LABEL, TYPE_ICON, STATUS_LABEL, PRIORITY_LABEL,
  }
})
