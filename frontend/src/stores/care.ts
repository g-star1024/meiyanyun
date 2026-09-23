// ============================================================
// Care 生日节日关怀 store（M3-09）—— B90 卡2 去 mock 切真
// 类型：生日/节日/复购窗口/沉睡唤醒；渠道：短信/企微/电话；
// 状态：待发送/已发送/已触达。KPI：本月待关怀/已发送/触达率/带来预约。
// 数据：对接 marketing-service /api/marketing/care（@/api/care 薄封装）。
// KPI 保留本地 computed（与后端 kpi 同口径；View .length 语义不可破）。
// 权限：care:view / care:edit / care:send。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { useToast } from '@/composables/useToast'
import { errMsg } from './m5Coupon'
import { searchCustomers } from '@/api/customer'
import {
  fetchCareTasks,
  fetchCareTemplates,
  createCareTask,
  sendCareTask,
  reachCareTask,
  convertCareTask,
  type CareTaskRow,
} from '@/api/care'

export type CareType = 'BIRTHDAY' | 'HOLIDAY' | 'REPURCHASE' | 'REACTIVATE'
export type CareChannel = 'SMS' | 'WECHAT' | 'PHONE'
export type CareStatus = 'PENDING' | 'SENT' | 'REACHED'

export interface CareTemplate {
  id: string
  name: string
  channel: CareChannel
  content: string
}

export interface CareTask {
  id: string
  customerName: string
  customerLevel: string
  type: CareType
  channel: CareChannel
  templateName: string
  templateContent: string
  scheduledAt: string
  status: CareStatus
  sentAt?: string
  reached: boolean
  replied: boolean
  convertedBooking: boolean
  assignee: string
  /** 来源规则编号：非空 = Flow 引擎自动创建（追溯用） */
  ruleNo?: string | null
}

const TYPE_LABEL: Record<CareType, string> = {
  BIRTHDAY: '生日关怀',
  HOLIDAY: '节日问候',
  REPURCHASE: '复购窗口',
  REACTIVATE: '沉睡唤醒',
}
const TYPE_ICON: Record<CareType, string> = {
  BIRTHDAY: 'sun',
  HOLIDAY: 'bell',
  REPURCHASE: 'trend-up',
  REACTIVATE: 'volume',
}
const CHANNEL_LABEL: Record<CareChannel, string> = {
  SMS: '短信',
  WECHAT: '企微',
  PHONE: '电话',
}
const STATUS_LABEL: Record<CareStatus, string> = {
  PENDING: '待发送',
  SENT: '已发送',
  REACHED: '已触达',
}

function isThisMonth(iso: string) {
  const d = new Date(iso)
  const n = new Date()
  return d.getFullYear() === n.getFullYear() && d.getMonth() === n.getMonth()
}

/** 后端行 → 前端任务（后端 customerLevel/templateName 恒空串，|| 兜底默认展示） */
function rowToTask(row: CareTaskRow): CareTask {
  return {
    id: row.id,
    customerName: row.customerName,
    customerLevel: row.customerLevel || '普通',
    type: row.type as CareType,
    channel: row.channel as CareChannel,
    templateName: row.templateName || '自定义内容',
    templateContent: row.templateContent ?? '',
    scheduledAt: row.scheduledAt,
    status: row.status as CareStatus,
    sentAt: row.sentAt ?? undefined,
    reached: row.reached,
    replied: row.replied,
    convertedBooking: row.convertedBooking,
    assignee: row.assignee ?? '',
    ruleNo: row.ruleNo,
  }
}

export const useCareStore = defineStore('care', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()
  const toast = useToast()

  const tasks = ref<CareTask[]>([])
  const templates = ref<CareTemplate[]>([])
  const filterTab = ref<'PENDING' | 'SENT' | 'ALL'>('PENDING')

  const pending = computed(() => tasks.value.filter((t) => t.status === 'PENDING'))
  const sent = computed(() => tasks.value.filter((t) => t.status !== 'PENDING'))
  const reached = computed(() => tasks.value.filter((t) => t.reached))
  const converted = computed(() => tasks.value.filter((t) => t.convertedBooking))
  const pendingThisMonth = computed(() => tasks.value.filter((t) => t.status === 'PENDING' && isThisMonth(t.scheduledAt)))
  const reachRate = computed(() => {
    const s = sent.value.length
    if (!s) return 0
    return Math.round((reached.value.length / s) * 100)
  })

  const filtered = computed(() => {
    let list = tasks.value
    if (filterTab.value === 'PENDING') list = list.filter((t) => t.status === 'PENDING')
    else if (filterTab.value === 'SENT') list = list.filter((t) => t.status !== 'PENDING')
    return list.sort((a, b) => new Date(a.scheduledAt).getTime() - new Date(b.scheduledAt).getTime())
  })

  function get(id: string) {
    return tasks.value.find((t) => t.id === id)
  }

  function upsert(t: CareTask) {
    const idx = tasks.value.findIndex((x) => x.id === t.id)
    if (idx >= 0) tasks.value.splice(idx, 1, t)
    else tasks.value.unshift(t)
  }

  /** 加载真实任务＋模板（幂等；函数名保留 seed，View onMounted 零改） */
  let seeded = false
  async function seed(): Promise<void> {
    if (seeded) return
    seeded = true
    try {
      const [taskRes, tplRes] = await Promise.all([fetchCareTasks(), fetchCareTemplates()])
      tasks.value = (taskRes.data.tasks ?? []).map(rowToTask)
      templates.value = (tplRes.data ?? []).map((t) => ({
        id: t.id,
        name: t.name,
        channel: t.channel as CareChannel,
        content: t.content,
      }))
    } catch (e) {
      seeded = false
      toast.error(errMsg(e, '关怀任务加载失败'))
    }
  }

  async function create(input: {
    customerName: string
    customerLevel?: string
    type: CareType
    channel: CareChannel
    templateId?: string
    scheduledAt: string
    assignee?: string
  }): Promise<CareTask | null> {
    if (!auth.can('care:edit')) {
      toast.error('无关怀编辑权限')
      return null
    }
    try {
      const kw = input.customerName.trim()
      const hits = (await searchCustomers(kw)).data ?? []
      const c = hits[0]
      if (!c) {
        toast.warning(`未检索到客户「${kw}」，请先建档`)
        return null
      }
      const tpl = input.templateId ? templates.value.find((t) => t.id === input.templateId) : undefined
      const res = await createCareTask({
        customerId: c.customerId,
        type: input.type,
        channel: input.channel,
        content: tpl?.content || undefined,
        planDate: input.scheduledAt.slice(0, 10),
      })
      const t = rowToTask(res.data)
      tasks.value.unshift(t)
      activity.log(auth.user.name, `创建关怀任务：${t.customerName} - ${TYPE_LABEL[t.type]}`, t.id)
      return t
    } catch (e) {
      toast.error(errMsg(e, '创建关怀任务失败'))
      return null
    }
  }

  async function send(id: string): Promise<boolean> {
    const t = tasks.value.find((x) => x.id === id)
    if (!t || t.status !== 'PENDING') return false
    if (!auth.can('care:send')) {
      toast.error('无关怀发送权限')
      return false
    }
    try {
      const res = await sendCareTask(id)
      if (res.data.skipped) {
        toast.warning(res.data.reason || '合规拦截，未发送')
        return false
      }
      upsert(rowToTask(res.data.task))
      activity.log(auth.user.name, `发送关怀：${t.customerName}（${CHANNEL_LABEL[t.channel]}）`, id)
      return true
    } catch (e) {
      toast.error(errMsg(e, '发送关怀失败'))
      return false
    }
  }

  async function markReached(id: string, reached: boolean): Promise<boolean> {
    const t = tasks.value.find((x) => x.id === id)
    if (!t || t.status === 'PENDING') return false
    if (!auth.can('care:edit')) {
      toast.error('无关怀编辑权限')
      return false
    }
    try {
      const res = await reachCareTask(id, reached)
      upsert(rowToTask(res.data))
      activity.log(auth.user.name, `${reached ? '标记已触达' : '取消触达'}：${t.customerName}`, id)
      return true
    } catch (e) {
      toast.error(errMsg(e, '触达标记失败'))
      return false
    }
  }

  async function markConverted(id: string, converted: boolean): Promise<boolean> {
    const t = tasks.value.find((x) => x.id === id)
    if (!t) return false
    if (!auth.can('care:edit')) {
      toast.error('无关怀编辑权限')
      return false
    }
    try {
      const res = await convertCareTask(id, converted)
      upsert(rowToTask(res.data))
      activity.log(auth.user.name, `${converted ? '登记转化预约' : '取消转化'}：${t.customerName}`, id)
      return true
    } catch (e) {
      toast.error(errMsg(e, '转化登记失败'))
      return false
    }
  }

  return {
    tasks, templates, filterTab,
    pending, sent, reached, converted, pendingThisMonth, reachRate, filtered,
    get, create, send, markReached, markConverted, seed,
    TYPE_LABEL, TYPE_ICON, CHANNEL_LABEL, STATUS_LABEL,
  }
})
