// ============================================================
// Care 生日节日关怀 store（M3-09）
// 类型：生日/节日/复购窗口/沉睡唤醒；渠道：短信/企微/电话；
// 状态：待发送/已发送/已触达。KPI：本月待关怀/已发送/触达率/带来预约。
// 权限：care:view / care:edit / care:send。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

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

export const useCareStore = defineStore('care', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

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

  function create(input: {
    customerName: string
    customerLevel?: string
    type: CareType
    channel: CareChannel
    templateId?: string
    scheduledAt: string
    assignee?: string
  }): CareTask | null {
    if (!auth.can('care:edit')) {
      console.warn('[care] 无 care:edit 权限')
      return null
    }
    const tpl = templates.value.find((t) => t.id === input.templateId)
      || templates.value.find((t) => t.channel === input.channel)
    const t: CareTask = {
      id: nextId('care'),
      customerName: input.customerName,
      customerLevel: input.customerLevel || '普通',
      type: input.type,
      channel: input.channel,
      templateName: tpl?.name || '自定义内容',
      templateContent: tpl?.content || '尊敬的客户，美研云为您送上专属关怀，欢迎到店体验。',
      scheduledAt: new Date(input.scheduledAt).toISOString(),
      status: 'PENDING',
      reached: false,
      replied: false,
      convertedBooking: false,
      assignee: input.assignee || auth.user.name,
    }
    tasks.value.unshift(t)
    activity.log(auth.user.name, `创建关怀任务：${t.customerName} - ${TYPE_LABEL[t.type]}`, t.id)
    return t
  }

  function send(id: string): boolean {
    const t = tasks.value.find((x) => x.id === id)
    if (!t || t.status !== 'PENDING' || !auth.can('care:send')) return false
    t.status = 'SENT'
    t.sentAt = new Date().toISOString()
    activity.log(auth.user.name, `发送关怀：${t.customerName}（${CHANNEL_LABEL[t.channel]}）`, t.id)
    return true
  }

  function markReached(id: string, reached: boolean): boolean {
    const t = tasks.value.find((x) => x.id === id)
    if (!t || t.status === 'PENDING' || !auth.can('care:edit')) return false
    t.reached = reached
    if (reached) t.status = 'REACHED'
    activity.log(auth.user.name, `${reached ? '标记已触达' : '取消触达'}：${t.customerName}`, t.id)
    return true
  }

  function markConverted(id: string, converted: boolean): boolean {
    const t = tasks.value.find((x) => x.id === id)
    if (!t || !auth.can('care:edit')) return false
    t.convertedBooking = converted
    if (converted) t.replied = true
    activity.log(auth.user.name, `${converted ? '登记转化预约' : '取消转化'}：${t.customerName}`, t.id)
    return true
  }

  // ===== 种子 =====
  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    const now = new Date()
    const daysAgo = (d: number) => new Date(now.getTime() - d * 86400_000).toISOString()
    const daysLater = (d: number) => new Date(now.getTime() + d * 86400_000).toISOString()

    templates.value = [
      { id: 'tpl-s03', name: '短信模板 S-03', channel: 'SMS', content: '亲爱的{昵称}，生日快乐！专属礼遇已备好，回复1领取生日礼券，到店还可享双倍积分。' },
      { id: 'tpl-t12', name: '企微图文 T-12', channel: 'WECHAT', content: '生日海报 + 到店券，企微一键推送。含本月专属项目优惠与免费皮肤检测名额。' },
      { id: 'tpl-p01', name: '电话话术 P-01', channel: 'PHONE', content: '您好，这里是美研云。{昵称}女士，本月是您的生日月，我们为您准备了专属礼遇，是否方便为您预约到店时间？' },
      { id: 'tpl-r02', name: '复购提醒 R-02', channel: 'WECHAT', content: '{昵称}女士，距离您上次护理已有 60 天，第二疗程效果更佳，本周到店享老客 8 折。' },
      { id: 'tpl-w01', name: '唤醒券 W-01', channel: 'SMS', content: '好久不见！我们为您准备了 200 元回归券，7 天内到店即可使用，期待您的光临。' },
    ]

    type Seed = Omit<CareTask, 'id' | 'templateName' | 'templateContent'> & { templateId?: string }
    const base: Seed[] = [
      { customerName: '王芳', customerLevel: '金卡', type: 'BIRTHDAY', channel: 'SMS', scheduledAt: daysLater(0), status: 'PENDING', reached: false, replied: false, convertedBooking: false, assignee: '林微' },
      { customerName: '李娜', customerLevel: 'L3', type: 'BIRTHDAY', channel: 'WECHAT', scheduledAt: daysLater(3), status: 'PENDING', reached: false, replied: false, convertedBooking: false, assignee: '苏晴' },
      { customerName: '林晚', customerLevel: '钻石', type: 'BIRTHDAY', channel: 'WECHAT', scheduledAt: daysAgo(2), status: 'REACHED', sentAt: daysAgo(2), reached: true, replied: true, convertedBooking: true, assignee: '林微' },
      { customerName: '周岚', customerLevel: '白金', type: 'REPURCHASE', channel: 'WECHAT', scheduledAt: daysAgo(5), status: 'REACHED', sentAt: daysAgo(5), reached: true, replied: true, convertedBooking: true, assignee: '苏晴' },
      { customerName: '陈思', customerLevel: '金卡', type: 'REACTIVATE', channel: 'SMS', scheduledAt: daysAgo(8), status: 'SENT', sentAt: daysAgo(8), reached: true, replied: false, convertedBooking: false, assignee: '林微' },
      { customerName: '张敏', customerLevel: '银卡', type: 'HOLIDAY', channel: 'PHONE', scheduledAt: daysLater(7), status: 'PENDING', reached: false, replied: false, convertedBooking: false, assignee: '苏晴' },
      { customerName: '王蕊', customerLevel: '黄金', type: 'BIRTHDAY', channel: 'SMS', scheduledAt: daysLater(1), status: 'PENDING', reached: false, replied: false, convertedBooking: false, assignee: '林微' },
      { customerName: '赵雨晴', customerLevel: '钻石', type: 'REPURCHASE', channel: 'PHONE', scheduledAt: daysAgo(12), status: 'REACHED', sentAt: daysAgo(12), reached: true, replied: false, convertedBooking: false, assignee: '林微' },
    ]
    base.forEach((s) => {
      const tpl = templates.value.find((t) => t.channel === s.channel)
      tasks.value.push({
        id: nextId('care'),
        ...s,
        templateName: tpl?.name || '自定义',
        templateContent: tpl?.content || '',
      })
    })
  }

  return {
    tasks, templates, filterTab,
    pending, sent, reached, converted, pendingThisMonth, reachRate, filtered,
    get, create, send, markReached, markConverted, seed,
    TYPE_LABEL, TYPE_ICON, CHANNEL_LABEL, STATUS_LABEL,
  }
})
