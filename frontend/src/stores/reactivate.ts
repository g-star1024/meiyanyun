// ============================================================
// Reactivate 沉睡客户唤醒 store（M2-17）
// 覆盖沉睡名单分层（30/60/90+ 天）、指派唤醒任务、回访记录。
// 切真：列表/指派/回访走后端，分层与状态派生由后端计算。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import * as reactivateApi from '@/api/reactivate'
import { useToast } from '@/composables/useToast'
import { useAuthStore } from '@/stores/auth'
import { errMsg } from '@/stores/m5Coupon'
import { useStoreContext } from '@/stores/storeContext'

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

function adapt(d: reactivateApi.ReactivateCustomerDto): ReactivateCustomer {
  return {
    id: String(d.id),
    name: d.name,
    level: d.level,
    phone: d.phone,
    lastVisitDays: d.lastVisitDays,
    cardBalance: d.cardBalance,
    tier: d.tier as SleepTier,
    status: d.status as ReactivateStatus,
    assignee: d.assignee,
    channel: d.channel as Channel | undefined,
    nextFollowAt: d.nextFollowAt,
    logs: d.logs.map((l) => ({
      id: String(l.id),
      by: l.by,
      at: l.at,
      action: l.action,
      channel: l.channel as Channel | undefined,
      result: l.result,
    })),
  }
}

export const useReactivateStore = defineStore('reactivate', () => {
  const auth = useAuthStore()
  const ctx = useStoreContext()
  const toast = useToast()

  const customers = ref<ReactivateCustomer[]>([])
  const filterTier = ref<SleepTier | 'ALL'>('ALL')
  const filterStatus = ref<ReactivateStatus | 'ALL'>('ALL')

  async function load() {
    try {
      const sc = ctx.currentStoreCode
      const { data } = await reactivateApi.listReactivates({ storeCode: sc || undefined })
      customers.value = data
        .map(adapt)
        .sort((a, b) => b.lastVisitDays - a.lastVisitDays)
    } catch (e) {
      customers.value = []
      toast.error(errMsg(e, '沉睡客户加载失败，请稍后重试'))
    }
  }

  async function seed() {
    await ctx.loadStores()
    await load()
  }

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
    return [...list].sort((a, b) => b.lastVisitDays - a.lastVisitDays)
  })

  function get(id: string) {
    return customers.value.find((c) => c.id === id)
  }

  async function assign(id: string, assignee: string, channel: Channel): Promise<boolean> {
    if (!auth.can('reactivate:edit')) {
      toast.error('无操作权限，请联系管理员')
      return false
    }
    try {
      await reactivateApi.assignReactivate(id, { assignee, channel })
      await load()
      return true
    } catch (e) {
      toast.error(errMsg(e, '指派唤醒任务失败，请稍后重试'))
      return false
    }
  }

  async function logVisit(id: string, result: string, recovered: boolean): Promise<boolean> {
    if (!auth.can('reactivate:edit')) {
      toast.error('无操作权限，请联系管理员')
      return false
    }
    try {
      await reactivateApi.visitReactivate(id, { result, recovered })
      await load()
      return true
    } catch (e) {
      toast.error(errMsg(e, '记录回访失败，请稍后重试'))
      return false
    }
  }

  return {
    customers, filterTier, filterStatus,
    total, t30, t90, monthRecovered, pending, assigned, filtered,
    get, assign, logVisit, seed,
    TIER_LABEL, STATUS_LABEL, STATUS_RANK, CHANNEL_LABEL,
  }
})
