// ============================================================
// M5-11 老带新（邀请机制配置）store
// P5-B89 卡2 起接真：数据源 = customer-service /api/customer/referral
// （campaigns / campaign-config / top-referrers 三端点）。
// - 邀请活动（进行中/已结束），支撑 KPI「进行中邀请活动」
// - 邀请机制配置：奖励形式、阶梯门槛（1/3/5 人）、有效期、邀请话术
// - 层级奖励：1 级 5% / 2 级 2%（可编辑），用于奖励规则卡
// - 关系链/奖励发放复用 referral store（referrals / rules / payReward）
// 权限：referralCampaign:view / referralCampaign:edit；奖励审核走 referral:approve
// 适配层铁律（铁律 -1-B）：导出签名全保留、M5ReferralView template/style 零改动。
// D3 定案：KPI「累计邀请人数」= referral.total 纯真实（与列表「共 N 条」自洽）；
// D4 定案：活动 invited/converted 统计列 v1 恒 0（聚合留 v2，防误导）。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useActivityStore } from '@/stores/activity'
import { useAuthStore } from '@/stores/auth'
import { useReferralStore, type RewardType } from '@/stores/referral'
import {
  createReferralCampaign, fetchReferralCampaigns, fetchReferralCampaignConfig,
  fetchTopReferrers, putReferralCampaignConfig, putReferralCampaignStatus,
  updateReferralCampaign, type ReferralCampaignConfigRow, type ReferralCampaignRow,
} from '@/api/referral'

export type CampaignStatus = 'ONGOING' | 'ENDED' | 'DRAFT'

export interface InviteCampaign {
  id: string
  name: string
  status: CampaignStatus
  startAt: string
  endAt: string
  invited: number
  converted: number
  /** P5-B91 卡2：编辑弹层回填用（可空=全部门店） */
  storeCode: string | null
  remark: string | null
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

function mapRewardType(t: string | null | undefined): RewardType {
  return t === 'POINTS' || t === 'COUPON' ? t : 'CASH'
}

export const useM5ReferralCampaignStore = defineStore('m5ReferralCampaign', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()
  const referral = useReferralStore()

  const campaigns = ref<InviteCampaign[]>([])
  const rewardType = ref<RewardType>('CASH')
  const validDays = ref(30)
  const script = ref('')
  const ladders = ref<LadderTier[]>([])
  const levels = ref<LevelReward[]>([])
  const topReferrers = ref<{ name: string; total: number; deal: number }[]>([])
  const seeded = ref(false)

  // KPI
  const ongoingCount = computed(() => campaigns.value.filter((c) => c.status === 'ONGOING').length)
  const totalInvited = computed(() => referral.total)
  const convertedCount = computed(() => referral.dealt.length)
  const pendingRewardCount = computed(() => referral.pendingReward.length)
  const pendingRewardAmount = computed(() =>
    referral.referrals
      .filter((r) => r.rewardStatus === 'PENDING' && r.status === 'DEAL')
      .reduce((s, r) => s + r.rewardAmount, 0))

  const levelReward = (lvl: 1 | 2) => levels.value.find((x) => x.level === lvl)

  function applyConfig(row: ReferralCampaignConfigRow) {
    rewardType.value = mapRewardType(row.rewardType)
    validDays.value = Math.max(1, Math.round(Number(row.validDays) || 30))
    script.value = row.script ?? ''
    ladders.value = (row.ladders ?? []).map((l) => ({
      threshold: Math.max(1, Number(l.threshold) || 1),
      type: mapRewardType(l.type),
      amount: Number(l.amount) || 0,
      desc: l.desc ?? '',
    }))
    levels.value = (row.levels ?? [])
      .filter((l) => l.level === 1 || l.level === 2)
      .map((l) => ({
        level: l.level as 1 | 2,
        rate: Math.max(0, Math.min(1, Number(l.rate) || 0)),
        desc: l.desc ?? '',
      }))
  }

  async function refreshConfig() {
    const resp = await fetchReferralCampaignConfig()
    applyConfig(resp.data)
  }

  // P5-B91 D8：invited/converted 统计列接真（后端聚合下发，去 v1 恒 0）
  function mapCampaignRow(c: ReferralCampaignRow): InviteCampaign {
    return {
      id: c.campaignId,
      name: c.name,
      status: (c.status === 'ONGOING' || c.status === 'ENDED' ? c.status : 'DRAFT') as CampaignStatus,
      startAt: c.startAt ?? '',
      endAt: c.endAt ?? '',
      invited: Number(c.invited ?? 0),
      converted: Number(c.converted ?? 0),
      storeCode: c.storeCode ?? null,
      remark: c.remark ?? null,
    }
  }

  async function refreshCampaigns() {
    const resp = await fetchReferralCampaigns()
    campaigns.value = resp.data.map(mapCampaignRow)
  }

  async function refreshTopReferrers() {
    const resp = await fetchTopReferrers(5)
    topReferrers.value = resp.data.map((r) => ({
      name: r.name ?? r.referrerCustomerId,
      total: r.total,
      deal: r.deal,
    }))
  }

  /** 全量 PUT 配置（乐观更新失败回滚）：saveConfig / updateLevelRate 共用。 */
  async function pushConfig(snapshot: {
    rewardType: RewardType
    validDays: number
    script: string
    ladders: LadderTier[]
    levels: LevelReward[]
  }): Promise<boolean> {
    try {
      await putReferralCampaignConfig({
        rewardType: rewardType.value,
        validDays: validDays.value,
        script: script.value,
        ladders: ladders.value.map((l) => ({ ...l })),
        levels: levels.value.map((l) => ({ ...l })),
      })
      return true
    } catch (e) {
      console.warn('[m5ReferralCampaign] 保存邀请机制配置失败，已回滚', e)
      rewardType.value = snapshot.rewardType
      validDays.value = snapshot.validDays
      script.value = snapshot.script
      ladders.value = snapshot.ladders
      levels.value = snapshot.levels
      return false
    }
  }

  function snapshotConfig() {
    return {
      rewardType: rewardType.value,
      validDays: validDays.value,
      script: script.value,
      ladders: ladders.value.map((l) => ({ ...l })),
      levels: levels.value.map((l) => ({ ...l })),
    }
  }

  async function updateLevelRate(lvl: 1 | 2, rate: number) {
    if (!auth.can('referralCampaign:edit')) return
    const snap = snapshotConfig()
    const item = levels.value.find((x) => x.level === lvl)
    if (item) {
      item.rate = Math.max(0, Math.min(1, rate))
      item.desc = `${lvl} 级推荐${lvl === 1 ? '（直接邀请）' : '（好友再邀）'}成交返 ${(item.rate * 100).toFixed(0)}%`
    }
    const ok = await pushConfig(snap)
    if (ok) activity.log(auth.user.name, `调整层级奖励：${lvl} 级返 ${(Math.max(0, Math.min(1, rate)) * 100).toFixed(0)}%`)
  }

  async function updateLadder(index: number, patch: Partial<LadderTier>) {
    if (!auth.can('referralCampaign:edit')) return
    const snap = snapshotConfig()
    const t = ladders.value[index]
    if (!t) return
    Object.assign(t, patch)
    await pushConfig(snap)
  }

  async function saveConfig(input: {
    rewardType: RewardType
    validDays: number
    script: string
    ladders: LadderTier[]
  }) {
    if (!auth.can('referralCampaign:edit')) return
    const snap = snapshotConfig()
    rewardType.value = input.rewardType
    validDays.value = Math.max(1, Math.round(input.validDays || 30))
    script.value = input.script
    ladders.value = input.ladders
    const ok = await pushConfig(snap)
    if (ok) activity.log(auth.user.name, `更新老带新邀请机制：奖励形式=${input.rewardType}，有效期=${validDays.value}天`)
  }

  /** 审核通过并发放奖励（委托 referral.payReward，需要 referral:approve） */
  async function approveReward(id: string): Promise<boolean> {
    return referral.payReward(id)
  }

  // -------------------- P5-B91 D10：活动 CRUD（referralCampaign:edit） --------------------
  // 乐观更新+失败回滚同 pushConfig 范式；create 无本地行可改，成功后插入返回行。

  function snapshotCampaigns() {
    return campaigns.value.map((c) => ({ ...c }))
  }

  function rollbackCampaigns(snap: InviteCampaign[], e: unknown) {
    console.warn('[m5ReferralCampaign] 活动操作失败，已回滚', e)
    campaigns.value = snap
    return false
  }

  async function createCampaign(input: {
    name: string
    startAt: string
    endAt: string
    storeCode?: string
    remark?: string
  }): Promise<boolean> {
    if (!auth.can('referralCampaign:edit')) return false
    try {
      const resp = await createReferralCampaign({
        name: input.name,
        startAt: input.startAt,
        endAt: input.endAt,
        storeCode: input.storeCode || undefined,
        remark: input.remark || undefined,
      })
      campaigns.value.unshift(mapCampaignRow(resp.data))
      activity.log(auth.user.name, `新建邀请活动：${input.name}`)
      return true
    } catch (e) {
      console.warn('[m5ReferralCampaign] 新建邀请活动失败', e)
      return false
    }
  }

  async function updateCampaign(id: string, input: {
    name: string
    startAt: string
    endAt: string
    storeCode?: string
    remark?: string
  }): Promise<boolean> {
    if (!auth.can('referralCampaign:edit')) return false
    const snap = snapshotCampaigns()
    const c = campaigns.value.find((x) => x.id === id)
    if (!c || c.status === 'ENDED') return false
    c.name = input.name
    c.startAt = input.startAt
    c.endAt = input.endAt
    c.storeCode = input.storeCode || null
    c.remark = input.remark || null
    try {
      await updateReferralCampaign(id, {
        name: input.name,
        startAt: input.startAt,
        endAt: input.endAt,
        storeCode: input.storeCode || undefined,
        remark: input.remark || undefined,
      })
      activity.log(auth.user.name, `编辑邀请活动：${input.name}`)
      return true
    } catch (e) {
      return rollbackCampaigns(snap, e)
    }
  }

  /** 状态逐级流转（DRAFT→ONGOING 开始／ONGOING→ENDED 结束；跳态/回退后端 409） */
  async function transitionCampaignStatus(id: string, target: CampaignStatus): Promise<boolean> {
    if (!auth.can('referralCampaign:edit')) return false
    const snap = snapshotCampaigns()
    const c = campaigns.value.find((x) => x.id === id)
    if (!c) return false
    const from = c.status
    c.status = target
    try {
      await putReferralCampaignStatus(id, target)
      activity.log(auth.user.name, `邀请活动状态流转：${c.name} ${CAMPAIGN_STATUS_LABEL[from]}→${CAMPAIGN_STATUS_LABEL[target]}`)
      return true
    } catch (e) {
      return rollbackCampaigns(snap, e)
    }
  }

  async function seed() {
    referral.seed()
    if (seeded.value) return
    seeded.value = true
    try {
      await Promise.all([refreshCampaigns(), refreshConfig(), refreshTopReferrers()])
    } catch (e) {
      console.warn('[m5ReferralCampaign] 加载转介绍活动配置失败', e)
    }
  }

  return {
    campaigns, rewardType, validDays, script, ladders, levels,
    ongoingCount, totalInvited, convertedCount, pendingRewardCount, pendingRewardAmount,
    topReferrers, levelReward,
    updateLevelRate, updateLadder, saveConfig, approveReward,
    createCampaign, updateCampaign, transitionCampaignStatus,
    CAMPAIGN_STATUS_LABEL, CAMPAIGN_STATUS_PILL, REWARD_TYPE_OPTIONS,
    seed,
  }
})
