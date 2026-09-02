// ============================================================
// 拓客活动 store（M2-16）
// 体验价 / 拼团 / 老带新活动，引流转化漏斗。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

export type AcqType = 'TRIAL' | 'GROUP' | 'REFERRAL'
export type AcqStatus = 'ONGOING' | 'ENDED' | 'DRAFT'

export interface AcquisitionCampaign {
  id: string
  no: string
  name: string
  type: AcqType
  exposure: number      // 曝光人数
  arrival: number       // 到店人数
  deal: number          // 成交人数
  budget: number
  spent: number
  status: AcqStatus
  startDate: string
  endDate: string
  owner: string
  channel: string
}

const TYPE_LABEL: Record<AcqType, string> = {
  TRIAL: '体验价',
  GROUP: '拼团',
  REFERRAL: '老带新',
}
const STATUS_LABEL: Record<AcqStatus, string> = {
  ONGOING: '进行中',
  ENDED: '已结束',
  DRAFT: '草稿',
}
const TYPE_ICON: Record<AcqType, string> = {
  TRIAL: 'marketing',
  GROUP: 'customer',
  REFERRAL: 'user-check',
}

export const useAcquisitionStore = defineStore('acquisition', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const campaigns = ref<AcquisitionCampaign[]>([])
  const filterType = ref<AcqType | 'ALL'>('ALL')
  const filterStatus = ref<AcqStatus | 'ALL'>('ALL')

  const ongoing = computed(() => campaigns.value.filter((c) => c.status === 'ONGOING'))
  const ended = computed(() => campaigns.value.filter((c) => c.status === 'ENDED'))
  const monthLeads = computed(() =>
    campaigns.value.reduce((sum, c) => sum + c.arrival, 0),
  )
  const monthDeals = computed(() =>
    campaigns.value.reduce((sum, c) => sum + c.deal, 0),
  )
  const avgConversion = computed(() => {
    const arrivalTotal = campaigns.value.reduce((s, c) => s + c.arrival, 0)
    if (!arrivalTotal) return 0
    return Math.round((monthDeals.value / arrivalTotal) * 1000) / 10
  })

  const filtered = computed(() => {
    let list = campaigns.value
    if (filterType.value !== 'ALL') list = list.filter((c) => c.type === filterType.value)
    if (filterStatus.value !== 'ALL') list = list.filter((c) => c.status === filterStatus.value)
    const rank: Record<AcqStatus, number> = { ONGOING: 0, DRAFT: 1, ENDED: 2 }
    return list.sort((a, b) => rank[a.status] - rank[b.status] || new Date(b.startDate).getTime() - new Date(a.startDate).getTime())
  })

  function get(id: string) {
    return campaigns.value.find((c) => c.id === id)
  }

  function conversionRate(c: AcquisitionCampaign): number {
    if (!c.arrival) return 0
    return Math.round((c.deal / c.arrival) * 1000) / 10
  }

  function create(input: {
    name: string
    type: AcqType
    budget: number
    channel: string
    startDate?: string
    endDate?: string
    owner?: string
  }): AcquisitionCampaign | null {
    if (!auth.can('acquisition:edit')) {
      console.warn('[acquisition] 无 acquisition:edit 权限')
      return null
    }
    const now = new Date()
    const start = input.startDate ? new Date(input.startDate) : now
    const end = input.endDate
      ? new Date(input.endDate)
      : new Date(start.getTime() + 30 * 86400_000)
    const c: AcquisitionCampaign = {
      id: nextId('aq'),
      no: `AQ-${now.toISOString().slice(0, 10).replace(/-/g, '')}-${String(campaigns.value.length + 1).padStart(3, '0')}`,
      name: input.name.trim(),
      type: input.type,
      exposure: 0,
      arrival: 0,
      deal: 0,
      budget: input.budget,
      spent: 0,
      status: 'DRAFT',
      startDate: start.toISOString(),
      endDate: end.toISOString(),
      owner: input.owner?.trim() || auth.user.name,
      channel: input.channel.trim() || '私域社群',
    }
    campaigns.value.unshift(c)
    activity.log(auth.user.name, `新建拓客活动 ${c.name}（${TYPE_LABEL[c.type]}）`, c.id)
    return c
  }

  function launch(id: string): boolean {
    const c = campaigns.value.find((x) => x.id === id)
    if (!c || c.status !== 'DRAFT' || !auth.can('acquisition:edit')) return false
    c.status = 'ONGOING'
    activity.log(auth.user.name, `启用拓客活动 ${c.name}`, c.id)
    return true
  }

  function end(id: string): boolean {
    const c = campaigns.value.find((x) => x.id === id)
    if (!c || c.status !== 'ONGOING' || !auth.can('acquisition:edit')) return false
    c.status = 'ENDED'
    c.endDate = new Date().toISOString()
    activity.log(auth.user.name, `结束拓客活动 ${c.name}`, c.id)
    return true
  }

  // ===== 种子数据 =====
  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    const now = new Date()
    const daysAgo = (d: number) => {
      const x = new Date(now)
      x.setDate(x.getDate() - d)
      return x.toISOString()
    }
    const daysLater = (d: number) => {
      const x = new Date(now)
      x.setDate(x.getDate() + d)
      return x.toISOString()
    }
    const base: Array<{
      name: string; type: AcqType; exposure: number; arrival: number; deal: number
      budget: number; spent: number; status: AcqStatus; startAgo: number; endIn?: number
      channel: string
    }> = [
      { name: '99元水光体验日', type: 'TRIAL', exposure: 12800, arrival: 186, deal: 72, budget: 30000, spent: 18600, status: 'ONGOING', startAgo: 6, endIn: 24, channel: '小红书+私域' },
      { name: '闺蜜拼团·热玛吉双人8折', type: 'GROUP', exposure: 8600, arrival: 124, deal: 58, budget: 50000, spent: 32000, status: 'ONGOING', startAgo: 12, endIn: 18, channel: '微信社群' },
      { name: '老带新·赠光子嫩肤1次', type: 'REFERRAL', exposure: 5200, arrival: 98, deal: 61, budget: 20000, spent: 15200, status: 'ONGOING', startAgo: 20, endIn: 10, channel: '老客企微' },
      { name: '19.9元皮肤检测体验', type: 'TRIAL', exposure: 22000, arrival: 342, deal: 88, budget: 25000, spent: 25000, status: 'ENDED', startAgo: 45, channel: '抖音本地推' },
      { name: '三人拼团·童颜针体验', type: 'GROUP', exposure: 6800, arrival: 76, deal: 30, budget: 40000, spent: 38500, status: 'ENDED', startAgo: 60, channel: '美团点评' },
      { name: '双11老客回馈拼团', type: 'REFERRAL', exposure: 9400, arrival: 156, deal: 92, budget: 35000, spent: 34800, status: 'ENDED', startAgo: 80, channel: '全渠道' },
      { name: '新年焕颜体验价（策划中）', type: 'TRIAL', exposure: 0, arrival: 0, deal: 0, budget: 45000, spent: 0, status: 'DRAFT', startAgo: 0, channel: '待定' },
    ]
    base.forEach((s, i) => {
      const id = nextId('aq')
      campaigns.value.push({
        id,
        no: `AQ-${daysAgo(s.startAgo).slice(0, 10).replace(/-/g, '')}-${String(i + 1).padStart(3, '0')}`,
        name: s.name,
        type: s.type,
        exposure: s.exposure,
        arrival: s.arrival,
        deal: s.deal,
        budget: s.budget,
        spent: s.spent,
        status: s.status,
        startDate: daysAgo(s.startAgo),
        endDate: s.status === 'ENDED' ? daysAgo(s.startAgo - 30) : daysLater(s.endIn || 30),
        owner: ['白桥（运营）', '吴桐（运营）', '陈雅琳（店长）'][i % 3],
        channel: s.channel,
      })
    })
  }

  return {
    campaigns, filterType, filterStatus,
    ongoing, ended, monthLeads, monthDeals, avgConversion, filtered,
    get, create, launch, end, conversionRate, seed,
    TYPE_LABEL, STATUS_LABEL, TYPE_ICON,
  }
})
