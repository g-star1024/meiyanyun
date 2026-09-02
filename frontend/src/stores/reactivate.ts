// ============================================================
// Reactivate 沉睡客户唤醒 store（M2-17）
// 覆盖沉睡名单分层（30/60/90+ 天）、指派唤醒任务、回访记录。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

export type SleepTier = 'T30' | 'T60' | 'T90'
export type ReactivateStatus = 'PENDING' | 'ASSIGNED' | 'VISITED' | 'RECOVERED'
export type Channel = 'PHONE' | 'WECHAT' | 'SMS'

export interface ReactivateLog {
  id: string
  by: string
  at: string
  action: string
  channel?: Channel
  result?: string
}

export interface ReactivateCustomer {
  id: string
  name: string
  level: string
  phone: string
  lastVisitDays: number
  cardBalance: number
  tier: SleepTier
  status: ReactivateStatus
  assignee?: string
  channel?: Channel
  nextFollowAt?: string
  logs: ReactivateLog[]
}

const TIER_LABEL: Record<SleepTier, string> = {
  T30: '30 天沉睡',
  T60: '60 天沉睡',
  T90: '90 天+ 深度沉睡',
}
const STATUS_LABEL: Record<ReactivateStatus, string> = {
  PENDING: '待唤醒',
  ASSIGNED: '已指派',
  VISITED: '已回访',
  RECOVERED: '已挽回',
}
const STATUS_RANK: Record<ReactivateStatus, number> = {
  PENDING: 0, ASSIGNED: 1, VISITED: 2, RECOVERED: 3,
}
const CHANNEL_LABEL: Record<Channel, string> = {
  PHONE: '电话',
  WECHAT: '企业微信',
  SMS: '短信',
}

function tierOf(days: number): SleepTier {
  if (days >= 90) return 'T90'
  if (days >= 60) return 'T60'
  return 'T30'
}

export const useReactivateStore = defineStore('reactivate', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const customers = ref<ReactivateCustomer[]>([])
  const filterTier = ref<SleepTier | 'ALL'>('ALL')
  const filterStatus = ref<ReactivateStatus | 'ALL'>('ALL')

  const total = computed(() => customers.value.length)
  const t30 = computed(() => customers.value.filter((c) => c.tier === 'T30').length)
  const t90 = computed(() => customers.value.filter((c) => c.tier === 'T90').length)

  const monthRecovered = computed(() => {
    const now = new Date()
    return customers.value.filter((c) => {
      if (c.status !== 'RECOVERED') return false
      const last = c.logs.find((l) => l.action === '客户已挽回')
      if (!last) return false
      const d = new Date(last.at)
      return d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth()
    }).length
  })

  const pending = computed(() => customers.value.filter((c) => c.status === 'PENDING'))
  const assigned = computed(() => customers.value.filter((c) => c.status === 'ASSIGNED'))

  const filtered = computed(() => {
    let list = customers.value
    if (filterTier.value !== 'ALL') list = list.filter((c) => c.tier === filterTier.value)
    if (filterStatus.value !== 'ALL') list = list.filter((c) => c.status === filterStatus.value)
    return list.sort((a, b) => b.lastVisitDays - a.lastVisitDays)
  })

  function get(id: string) {
    return customers.value.find((c) => c.id === id)
  }

  function assign(id: string, assignee: string, channel: Channel): boolean {
    const c = customers.value.find((x) => x.id === id)
    if (!c || !auth.can('reactivate:edit')) return false
    c.assignee = assignee
    c.channel = channel
    c.status = STATUS_RANK[c.status] < STATUS_RANK.ASSIGNED ? 'ASSIGNED' : c.status
    c.nextFollowAt = new Date(Date.now() + 2 * 86400_000).toISOString()
    c.logs.unshift({
      id: nextId('rlog'),
      by: auth.user.name,
      at: new Date().toISOString(),
      action: `指派给 ${assignee}（${CHANNEL_LABEL[channel]}）`,
      channel,
    })
    activity.log(auth.user.name, `指派唤醒任务：${c.name} → ${assignee}`, c.id)
    return true
  }

  function logVisit(id: string, result: string, recovered: boolean): boolean {
    const c = customers.value.find((x) => x.id === id)
    if (!c || !auth.can('reactivate:edit')) return false
    c.logs.unshift({
      id: nextId('rlog'),
      by: c.assignee || auth.user.name,
      at: new Date().toISOString(),
      action: recovered ? '客户已挽回' : '回访记录',
      result,
    })
    if (recovered) {
      c.status = 'RECOVERED'
      c.lastVisitDays = 0
    } else if (c.status !== 'RECOVERED') {
      c.status = 'VISITED'
    }
    activity.log(
      auth.user.name,
      recovered ? `已挽回沉睡客户 ${c.name}` : `记录回访：${c.name} - ${result}`,
      c.id,
    )
    return true
  }

  // ===== 种子 =====
  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    const now = new Date()
    const hoursAgo = (h: number) => new Date(now.getTime() - h * 3600_000).toISOString()
    const daysAgo = (d: number) => new Date(now.getTime() - d * 86400_000).toISOString()
    type Seed = {
      name: string; level: string; phone: string; lastVisitDays: number;
      cardBalance: number; status: ReactivateStatus; assignee?: string; channel?: Channel;
    }
    const base: Seed[] = [
      { name: '赵雨晴', level: '钻石', phone: '138****2041', lastVisitDays: 112, cardBalance: 18600, status: 'RECOVERED', assignee: '林微', channel: 'PHONE' },
      { name: '孙佳宁', level: '金卡', phone: '139****6612', lastVisitDays: 95, cardBalance: 8200, status: 'ASSIGNED', assignee: '林微', channel: 'WECHAT' },
      { name: '王晓明', level: '银卡', phone: '136****3018', lastVisitDays: 78, cardBalance: 3600, status: 'PENDING' },
      { name: '陈美玲', level: '金卡', phone: '135****7788', lastVisitDays: 62, cardBalance: 6400, status: 'VISITED', assignee: '白桥', channel: 'WECHAT' },
      { name: '李思琪', level: '钻石', phone: '137****9150', lastVisitDays: 45, cardBalance: 24300, status: 'ASSIGNED', assignee: '林微', channel: 'PHONE' },
      { name: '周心怡', level: '银卡', phone: '131****2204', lastVisitDays: 38, cardBalance: 1200, status: 'PENDING' },
      { name: '吴雅琴', level: '普通', phone: '186****5509', lastVisitDays: 35, cardBalance: 0, status: 'VISITED', assignee: '白桥', channel: 'SMS' },
      { name: '郑雪', level: '金卡', phone: '133****8817', lastVisitDays: 41, cardBalance: 5200, status: 'PENDING' },
    ]
    base.forEach((s, i) => {
      const logs: ReactivateLog[] = []
      if (s.assignee) {
        logs.push({
          id: nextId('rlog'), by: '苏晴', at: daysAgo(s.lastVisitDays - 2),
          action: `指派给 ${s.assignee}（${s.channel ? CHANNEL_LABEL[s.channel] : ''}）`,
          channel: s.channel,
        })
      }
      if (s.status === 'VISITED' || s.status === 'RECOVERED') {
        logs.unshift({
          id: nextId('rlog'), by: s.assignee || '系统', at: hoursAgo(i * 6 + 4),
          action: s.status === 'RECOVERED' ? '客户已挽回' : '回访记录',
          result: s.status === 'RECOVERED'
            ? '客户已到店做热玛吉，充值 10000'
            : '客户反馈近期出差，预计月底回店',
        })
      }
      customers.value.push({
        id: nextId('rc'),
        name: s.name,
        level: s.level,
        phone: s.phone,
        lastVisitDays: s.status === 'RECOVERED' ? 0 : s.lastVisitDays,
        cardBalance: s.cardBalance,
        tier: s.status === 'RECOVERED' ? 'T30' : tierOf(s.lastVisitDays),
        status: s.status,
        assignee: s.assignee,
        channel: s.channel,
        nextFollowAt: s.status === 'ASSIGNED' ? new Date(now.getTime() + (i + 1) * 86400_000).toISOString() : undefined,
        logs,
      })
    })
  }

  return {
    customers, filterTier, filterStatus,
    total, t30, t90, monthRecovered, pending, assigned, filtered,
    get, assign, logVisit, seed,
    TIER_LABEL, STATUS_LABEL, CHANNEL_LABEL,
  }
})
