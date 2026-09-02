// ============================================================
// Referral 转介绍管理 store（M3-11）
// 介绍人 → 被介绍人关系链；状态：待确认/已确认/已到店/已成交；
// 奖励状态：待发放/已发放；奖励类型：积分/券/现金。
// KPI：转介绍总数 / 本月新增 / 已成交 / 待发奖励。
// 权限：referral:view / referral:edit / referral:approve。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

export type ReferralStatus = 'PENDING' | 'CONFIRMED' | 'VISITED' | 'DEAL'
export type RewardStatus = 'PENDING' | 'PAID'
export type RewardType = 'POINTS' | 'COUPON' | 'CASH'

export interface ReferralTimeline {
  at: string
  action: string
  by: string
}

export interface Referral {
  id: string
  referrerName: string
  referrerLevel: string
  referrerPhone: string
  referrerTotal: number
  introducedName: string
  introducedPhone: string
  status: ReferralStatus
  rewardStatus: RewardStatus
  rewardAmount: number
  rewardType: RewardType
  boundAt: string
  visitAt?: string
  dealAt?: string
  paidAt?: string
  dealAmount?: number
  timeline: ReferralTimeline[]
}

export interface RewardRule {
  type: RewardType
  name: string
  threshold: string
  amount: number
  desc: string
}

const STATUS_LABEL: Record<ReferralStatus, string> = {
  PENDING: '待确认',
  CONFIRMED: '已确认',
  VISITED: '已到店',
  DEAL: '已成交',
}
const STATUS_ORDER: Record<ReferralStatus, number> = { PENDING: 0, CONFIRMED: 1, VISITED: 2, DEAL: 3 }
const REWARD_LABEL: Record<RewardStatus, string> = {
  PENDING: '待发放',
  PAID: '已发放',
}
const REWARD_TYPE_LABEL: Record<RewardType, string> = {
  POINTS: '积分',
  COUPON: '券',
  CASH: '现金',
}

function isThisMonth(iso: string) {
  const d = new Date(iso)
  const n = new Date()
  return d.getFullYear() === n.getFullYear() && d.getMonth() === n.getMonth()
}

export const useReferralStore = defineStore('referral', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const referrals = ref<Referral[]>([])
  const rules = ref<RewardRule[]>([])
  const filterTab = ref<'ALL' | 'PENDING' | 'VISITED' | 'DEAL' | 'REWARD'>('ALL')

  const total = computed(() => referrals.value.length)
  const newThisMonth = computed(() => referrals.value.filter((r) => isThisMonth(r.boundAt)).length)
  const dealt = computed(() => referrals.value.filter((r) => r.status === 'DEAL'))
  const pendingReward = computed(() => referrals.value.filter((r) => r.rewardStatus === 'PENDING'))
  const totalRewardPaid = computed(() =>
    referrals.value.filter((r) => r.rewardStatus === 'PAID').reduce((s, r) => s + r.rewardAmount, 0),
  )

  const filtered = computed(() => {
    let list = referrals.value
    if (filterTab.value === 'PENDING') list = list.filter((r) => r.status === 'PENDING')
    else if (filterTab.value === 'VISITED') list = list.filter((r) => r.status === 'VISITED' || r.status === 'DEAL')
    else if (filterTab.value === 'DEAL') list = list.filter((r) => r.status === 'DEAL')
    else if (filterTab.value === 'REWARD') list = list.filter((r) => r.rewardStatus === 'PENDING')
    return list.sort((a, b) => new Date(b.boundAt).getTime() - new Date(a.boundAt).getTime())
  })

  function get(id: string) {
    return referrals.value.find((r) => r.id === id)
  }

  function confirm(id: string): boolean {
    const r = referrals.value.find((x) => x.id === id)
    if (!r || r.status !== 'PENDING' || !auth.can('referral:edit')) return false
    r.status = 'CONFIRMED'
    r.timeline.unshift({ at: new Date().toISOString(), action: '确认归属关系', by: auth.user.name })
    activity.log(auth.user.name, `确认转介绍归属：${r.referrerName} → ${r.introducedName}`, r.id)
    return true
  }

  function markVisited(id: string): boolean {
    const r = referrals.value.find((x) => x.id === id)
    if (!r || r.status === 'PENDING' || r.status === 'VISITED' || r.status === 'DEAL' || !auth.can('referral:edit')) return false
    r.status = 'VISITED'
    r.visitAt = new Date().toISOString()
    r.timeline.unshift({ at: r.visitAt, action: '被介绍人首次到店', by: auth.user.name })
    activity.log(auth.user.name, `转介绍到店：${r.introducedName}`, r.id)
    return true
  }

  function markDeal(id: string, amount: number, rewardType?: RewardType, rewardAmount?: number): boolean {
    const r = referrals.value.find((x) => x.id === id)
    if (!r || r.status !== 'VISITED' || !auth.can('referral:edit')) return false
    r.status = 'DEAL'
    r.dealAt = new Date().toISOString()
    r.dealAmount = amount
    if (rewardType) r.rewardType = rewardType
    if (typeof rewardAmount === 'number') r.rewardAmount = rewardAmount
    r.timeline.unshift({ at: r.dealAt, action: `成交 ¥${amount}，奖励已生成`, by: auth.user.name })
    activity.log(auth.user.name, `转介绍成交：${r.introducedName} - ¥${amount}`, r.id)
    return true
  }

  function payReward(id: string): boolean {
    const r = referrals.value.find((x) => x.id === id)
    if (!r || r.rewardStatus !== 'PENDING' || r.status !== 'DEAL' || !auth.can('referral:approve')) return false
    r.rewardStatus = 'PAID'
    r.paidAt = new Date().toISOString()
    r.timeline.unshift({ at: r.paidAt, action: `发放奖励 ${REWARD_TYPE_LABEL[r.rewardType]} ¥${r.rewardAmount}`, by: auth.user.name })
    activity.log(auth.user.name, `发放转介绍奖励：${r.referrerName} - ¥${r.rewardAmount}`, r.id)
    return true
  }

  function create(input: {
    referrerName: string
    referrerLevel?: string
    referrerPhone?: string
    introducedName: string
    introducedPhone?: string
    rewardType?: RewardType
    rewardAmount?: number
  }): Referral | null {
    if (!auth.can('referral:edit')) return null
    const now = new Date().toISOString()
    const r: Referral = {
      id: nextId('ref'),
      referrerName: input.referrerName,
      referrerLevel: input.referrerLevel || '金卡',
      referrerPhone: input.referrerPhone || '138****0000',
      referrerTotal: 1,
      introducedName: input.introducedName,
      introducedPhone: input.introducedPhone || '139****0000',
      status: 'PENDING',
      rewardStatus: 'PENDING',
      rewardAmount: input.rewardAmount ?? 200,
      rewardType: input.rewardType || 'CASH',
      boundAt: now,
      timeline: [{ at: now, action: '绑定转介绍关系', by: auth.user.name }],
    }
    referrals.value.unshift(r)
    activity.log(auth.user.name, `新建转介绍：${r.referrerName} → ${r.introducedName}`, r.id)
    return r
  }

  function updateRule(type: RewardType, patch: Partial<RewardRule>): boolean {
    if (!auth.can('referral:edit')) return false
    const r = rules.value.find((x) => x.type === type)
    if (!r) return false
    Object.assign(r, patch)
    return true
  }

  // ===== 种子 =====
  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    rules.value = [
      { type: 'POINTS', name: '积分奖励', threshold: '被介绍人首次到店', amount: 500, desc: '到店即奖励介绍人 500 积分' },
      { type: 'COUPON', name: '项目券', threshold: '被介绍人成交', amount: 300, desc: '成交后发放 300 元项目券，限本人使用' },
      { type: 'CASH', name: '现金回馈', threshold: '被介绍人成交满 ¥2000', amount: 200, desc: '成交满 2000 元返介绍人 200 元现金' },
    ]

    const now = new Date()
    const daysAgo = (d: number) => new Date(now.getTime() - d * 86400_000).toISOString()
    type Seed = Omit<Referral, 'id' | 'timeline'>
    const base: Seed[] = [
      { referrerName: '林晚', referrerLevel: '黑卡', referrerPhone: '138****6621', referrerTotal: 12, introducedName: '苏晴', introducedPhone: '139****1188', status: 'DEAL', rewardStatus: 'PENDING', rewardAmount: 200, rewardType: 'CASH', boundAt: daysAgo(45), visitAt: daysAgo(38), dealAt: daysAgo(30), dealAmount: 6200 },
      { referrerName: '王蕊', referrerLevel: '金卡', referrerPhone: '137****2210', referrerTotal: 5, introducedName: '陈思', introducedPhone: '135****7766', status: 'CONFIRMED', rewardStatus: 'PENDING', rewardAmount: 500, rewardType: 'POINTS', boundAt: daysAgo(20) },
      { referrerName: '陈思', referrerLevel: '钻石', referrerPhone: '135****7766', referrerTotal: 8, introducedName: '周岚', introducedPhone: '136****3312', status: 'DEAL', rewardStatus: 'PENDING', rewardAmount: 300, rewardType: 'COUPON', boundAt: daysAgo(60), visitAt: daysAgo(50), dealAt: daysAgo(40), dealAmount: 3800 },
      { referrerName: '张敏', referrerLevel: '金卡', referrerPhone: '131****8821', referrerTotal: 3, introducedName: '刘芳', introducedPhone: '186****5599', status: 'DEAL', rewardStatus: 'PAID', rewardAmount: 200, rewardType: 'CASH', boundAt: daysAgo(90), visitAt: daysAgo(80), dealAt: daysAgo(70), paidAt: daysAgo(65), dealAmount: 5400 },
      { referrerName: '王芳', referrerLevel: '银卡', referrerPhone: '133****1120', referrerTotal: 2, introducedName: '赵雪', introducedPhone: '189****7788', status: 'VISITED', rewardStatus: 'PENDING', rewardAmount: 500, rewardType: 'POINTS', boundAt: daysAgo(15), visitAt: daysAgo(5) },
      { referrerName: '李娜', referrerLevel: '白金', referrerPhone: '132****0099', referrerTotal: 6, introducedName: '吴雅琴', introducedPhone: '188****6611', status: 'PENDING', rewardStatus: 'PENDING', rewardAmount: 500, rewardType: 'POINTS', boundAt: daysAgo(3) },
      { referrerName: '林晚', referrerLevel: '黑卡', referrerPhone: '138****6621', referrerTotal: 12, introducedName: '孙佳宁', introducedPhone: '139****6612', status: 'DEAL', rewardStatus: 'PAID', rewardAmount: 300, rewardType: 'COUPON', boundAt: daysAgo(120), visitAt: daysAgo(110), dealAt: daysAgo(100), paidAt: daysAgo(95), dealAmount: 2400 },
    ]
    base.forEach((s) => {
      const timeline: ReferralTimeline[] = [
        { at: s.boundAt, action: '绑定转介绍关系', by: '系统' },
      ]
      if (s.visitAt) timeline.push({ at: s.visitAt, action: '被介绍人首次到店', by: '前台' })
      if (s.dealAt) timeline.push({ at: s.dealAt, action: `成交 ¥${s.dealAmount}，奖励已生成`, by: '顾问' })
      if (s.paidAt) timeline.push({ at: s.paidAt, action: `发放奖励 ${REWARD_TYPE_LABEL[s.rewardType]} ¥${s.rewardAmount}`, by: auth.user.name })
      if (s.status === 'CONFIRMED') timeline.push({ at: s.boundAt, action: '确认归属关系', by: '林微' })
      referrals.value.push({ id: nextId('ref'), ...s, timeline })
    })
  }

  return {
    referrals, rules, filterTab,
    total, newThisMonth, dealt, pendingReward, totalRewardPaid, filtered,
    get, create, confirm, markVisited, markDeal, payReward, updateRule, seed,
    STATUS_LABEL, REWARD_LABEL, REWARD_TYPE_LABEL, STATUS_ORDER,
  }
})
