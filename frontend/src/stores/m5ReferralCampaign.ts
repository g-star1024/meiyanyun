// ============================================================
// M5-11 老带新（邀请机制配置）store
// - 邀请活动（进行中/已结束），支撑 KPI「进行中邀请活动」
// - 邀请机制配置：奖励形式、阶梯门槛（1/3/5 人）、有效期、邀请话术
// - 层级奖励：1 级 5% / 2 级 2%（可编辑），用于奖励规则卡
// - 关系链/奖励发放复用 referral store（referrals / rules / payReward）
// 权限：referralCampaign:view / referralCampaign:edit；奖励审核走 referral:approve
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useActivityStore } from '@/stores/activity'
import { useAuthStore } from '@/stores/auth'
import { useReferralStore, type RewardType } from '@/stores/referral'

export type CampaignStatus = 'ONGOING' | 'ENDED' | 'DRAFT'

let _id = 0
function nextId(p: string) { _id += 1; return `${p}-${Date.now().toString(36)}-${_id}` }
function dayOffset(n: number) {
  const d = new Date(); d.setDate(d.getDate() + n)
  return d.toISOString().slice(0, 10)
}

export interface InviteCampaign {
  id: string
  name: string
  status: CampaignStatus
  startAt: string
  endAt: string
  invited: number
  converted: number
}

export interface LadderTier {
  /** 邀请人数门槛 */
  threshold: number
  type: RewardType
  amount: number
  desc: string
}

export interface LevelReward {
  level: 1 | 2
  rate: number // 0~1
  desc: string
}

export const CAMPAIGN_STATUS_LABEL: Record<CampaignStatus, string> = {
  ONGOING: '进行中', ENDED: '已结束', DRAFT: '草稿',
}
export const CAMPAIGN_STATUS_PILL: Record<CampaignStatus, 'success' | 'disabled' | 'draft'> = {
  ONGOING: 'success', ENDED: 'disabled', DRAFT: 'draft',
}

export const REWARD_TYPE_OPTIONS: { value: RewardType; label: string }[] = [
  { value: 'POINTS', label: '积分' },
  { value: 'COUPON', label: '优惠券' },
  { value: 'CASH', label: '现金' },
]

export const useM5ReferralCampaignStore = defineStore('m5ReferralCampaign', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()
  const referral = useReferralStore()

  const campaigns = ref<InviteCampaign[]>([])
  const rewardType = ref<RewardType>('CASH')
  const validDays = ref(30)
  const script = ref('我在美研云体验很不错，推荐你也来看看，到店我们都能拿奖励～')
  const ladders = ref<LadderTier[]>([
    { threshold: 1, type: 'CASH', amount: 100, desc: '邀请 1 人到店，奖励 100 元现金' },
    { threshold: 3, type: 'COUPON', amount: 300, desc: '邀请 3 人成交，加赠 300 元项目券' },
    { threshold: 5, type: 'POINTS', amount: 5000, desc: '邀请 5 人成交，额外 5000 积分' },
  ])
  const levels = ref<LevelReward[]>([
    { level: 1, rate: 0.05, desc: '一级推荐（直接邀请）成交返 5%' },
    { level: 2, rate: 0.02, desc: '二级推荐（好友再邀）成交返 2%' },
  ])
  const seeded = ref(false)

  // KPI
  const ongoingCount = computed(() => campaigns.value.filter((c) => c.status === 'ONGOING').length)
  const totalInvited = computed(() => campaigns.value.reduce((s, c) => s + c.invited, 0)
    + referral.total)
  const convertedCount = computed(() => referral.dealt.length)
  const pendingRewardCount = computed(() => referral.pendingReward.length)
  const pendingRewardAmount = computed(() =>
    referral.referrals
      .filter((r) => r.rewardStatus === 'PENDING' && r.status === 'DEAL')
      .reduce((s, r) => s + r.rewardAmount, 0))

  // 邀请排行 Top5（按 referrerTotal 聚合）
  const topReferrers = computed(() => {
    const map = new Map<string, { name: string; total: number; deal: number }>()
    referral.referrals.forEach((r) => {
      const cur = map.get(r.referrerName) ?? { name: r.referrerName, total: 0, deal: 0 }
      cur.total += 1
      if (r.status === 'DEAL') cur.deal += 1
      map.set(r.referrerName, cur)
    })
    return [...map.values()].sort((a, b) => b.total - a.total).slice(0, 5)
  })

  const levelReward = (lvl: 1 | 2) => levels.value.find((x) => x.level === lvl)

  function updateLevelRate(lvl: 1 | 2, rate: number) {
    if (!auth.can('referralCampaign:edit')) return
    const item = levels.value.find((x) => x.level === lvl)
    if (item) {
      item.rate = Math.max(0, Math.min(1, rate))
      item.desc = `${lvl} 级推荐${lvl === 1 ? '（直接邀请）' : '（好友再邀）'}成交返 ${(item.rate * 100).toFixed(0)}%`
    }
  }

  function updateLadder(index: number, patch: Partial<LadderTier>) {
    if (!auth.can('referralCampaign:edit')) return
    const t = ladders.value[index]
    if (!t) return
    Object.assign(t, patch)
  }

  function saveConfig(input: {
    rewardType: RewardType
    validDays: number
    script: string
    ladders: LadderTier[]
  }) {
    if (!auth.can('referralCampaign:edit')) return
    rewardType.value = input.rewardType
    validDays.value = Math.max(1, Math.round(input.validDays || 30))
    script.value = input.script
    ladders.value = input.ladders
    activity.log(auth.user.name, `更新老带新邀请机制：奖励形式=${input.rewardType}，有效期=${validDays.value}天`)
  }

  /** 审核通过并发放奖励（委托 referral.payReward，需要 referral:approve） */
  function approveReward(id: string): boolean {
    return referral.payReward(id)
  }

  function seed() {
    if (seeded.value) return
    referral.seed()
    campaigns.value = [
      { id: nextId('ic'), name: '2026 春季老带新', status: 'ONGOING', startAt: dayOffset(-20), endAt: dayOffset(40), invited: 86, converted: 24 },
      { id: nextId('ic'), name: '会员日邀请有礼', status: 'ONGOING', startAt: dayOffset(-10), endAt: dayOffset(20), invited: 52, converted: 12 },
      { id: nextId('ic'), name: '新客体验官招募', status: 'ONGOING', startAt: dayOffset(-5), endAt: dayOffset(25), invited: 38, converted: 6 },
      { id: nextId('ic'), name: '双11 老带新专场', status: 'ENDED', startAt: dayOffset(-90), endAt: dayOffset(-30), invited: 142, converted: 48 },
      { id: nextId('ic'), name: '暑期抗衰推荐季', status: 'ENDED', startAt: dayOffset(-60), endAt: dayOffset(-10), invited: 96, converted: 32 },
      { id: nextId('ic'), name: '直播预约邀请', status: 'DRAFT', startAt: dayOffset(7), endAt: dayOffset(37), invited: 0, converted: 0 },
    ]
    seeded.value = true
  }

  return {
    campaigns, rewardType, validDays, script, ladders, levels,
    ongoingCount, totalInvited, convertedCount, pendingRewardCount, pendingRewardAmount,
    topReferrers, levelReward,
    updateLevelRate, updateLadder, saveConfig, approveReward,
    CAMPAIGN_STATUS_LABEL, CAMPAIGN_STATUS_PILL, REWARD_TYPE_OPTIONS,
    seed,
  }
})
