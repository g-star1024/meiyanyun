// ============================================================
// Referral 转介绍管理 store（M3-11）
// B86 卡3 起接真：数据源 = customer-service /api/customer/referral。
// 介绍人 → 被介绍人关系链；状态：待确认/已确认/已到店/已成交/已过期/已拒绝；
// 奖励状态：待发放/已发放/已驳回；奖励类型：积分/券/现金。
// KPI：转介绍总数 / 本月新增 / 已成交 / 待发奖励。
// 权限：referral:view / referral:edit / referral:approve。
// 适配层铁律（铁律 -1-B）：Referral 接口与导出形状保持 mock 活规格原样，
// M5ReferralView / ReferralView 两个视图模板零改动。
// 后端差异全部在本层消化：金额分→元、奖励类型 POINT/GRANT/COUPON/COMMISSION
// →POINTS/COUPON/CASH、奖励状态 GRANTED→PAID、时间线由行字段合成。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'
import {
  confirmReferral, createReferral, createReferralReward, dealReferral,
  fetchReferralPage, grantReferralReward, visitReferral, type ReferralRow,
} from '@/api/referral'

export type ReferralStatus = 'PENDING' | 'CONFIRMED' | 'VISITED' | 'DEAL' | 'EXPIRED' | 'REJECTED'
export type RewardStatus = 'PENDING' | 'PAID' | 'REJECTED'
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
  EXPIRED: '已过期',
  REJECTED: '已拒绝',
}
const STATUS_ORDER: Record<ReferralStatus, number> = { PENDING: 0, CONFIRMED: 1, VISITED: 2, DEAL: 3, EXPIRED: 4, REJECTED: 4 }
const REWARD_LABEL: Record<RewardStatus, string> = {
  PENDING: '待发放',
  PAID: '已发放',
  REJECTED: '已驳回',
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

// -------------------- 后端 → 活规格映射（铁律 -1-B 适配层） --------------------

function fen2yuan(cents: number | null | undefined): number {
  return (cents ?? 0) / 100
}

function mapStatus(s: string): ReferralStatus {
  return (s in STATUS_LABEL ? s : 'PENDING') as ReferralStatus
}

function mapRewardType(t: string | null | undefined): RewardType {
  if (t === 'POINT') return 'POINTS'
  if (t === 'COUPON') return 'COUPON'
  return 'CASH'
}

function mapRewardStatus(s: string | null | undefined): RewardStatus {
  if (s === 'GRANTED') return 'PAID'
  if (s === 'REJECTED') return 'REJECTED'
  return 'PENDING'
}

// 与 mock 活规格同形状（138****6621）；后端返回明文，列表层统一脱敏
function maskPhone(p: string | null | undefined): string {
  if (!p) return ''
  if (p.includes('*')) return p
  return p.length >= 7 ? p.slice(0, 3) + '****' + p.slice(-4) : p
}

function adapt(row: ReferralRow): Referral {
  const rewardType = mapRewardType(row.rewardType)
  const rewardAmount = rewardType === 'POINTS' ? (row.rewardPoints ?? 0) : fen2yuan(row.rewardAmountCents)
  const dealAmount = row.dealAmountCents != null ? fen2yuan(row.dealAmountCents) : undefined
  const timeline: ReferralTimeline[] = []
  if (row.boundAt) timeline.push({ at: row.boundAt, action: '绑定转介绍关系', by: row.createdBy || '系统' })
  if (row.confirmedAt) timeline.push({ at: row.confirmedAt, action: '确认归属关系', by: '门店' })
  if (row.visitedAt) timeline.push({ at: row.visitedAt, action: '被介绍人首次到店', by: '前台' })
  if (row.dealAt) timeline.push({ at: row.dealAt, action: `成交 ¥${dealAmount ?? 0}${row.rewardId ? '，奖励已生成' : ''}`, by: '顾问' })
  if (row.rejectedAt) timeline.push({ at: row.rejectedAt, action: `拒绝转介绍${row.rejectReason ? '：' + row.rejectReason : ''}`, by: '门店' })
  if (row.expiredAt) timeline.push({ at: row.expiredAt, action: '到期失效，释放绑定', by: '系统' })
  if (row.rewardPaidAt) {
    timeline.push({
      at: row.rewardPaidAt,
      action: `发放奖励 ${REWARD_TYPE_LABEL[rewardType]}${rewardType === 'POINTS' ? ` ${rewardAmount} 积分` : ` ¥${rewardAmount}`}`,
      by: '门店',
    })
  }
  timeline.reverse()
  return {
    id: row.referralId,
    referrerName: row.referrerName ?? row.referrerCustomerId,
    referrerLevel: row.referrerLevel ?? '普通',
    referrerPhone: maskPhone(row.referrerPhone),
    referrerTotal: Number(row.referrerTotal ?? 0),
    introducedName: row.refereeName ?? row.refereeCustomerId,
    introducedPhone: maskPhone(row.refereePhone),
    status: mapStatus(row.status),
    rewardStatus: mapRewardStatus(row.rewardStatus),
    rewardAmount,
    rewardType,
    boundAt: row.boundAt ?? row.createdAt,
    visitAt: row.visitedAt ?? undefined,
    dealAt: row.dealAt ?? undefined,
    paidAt: row.rewardPaidAt ?? undefined,
    dealAmount,
    timeline,
  }
}

export const useReferralStore = defineStore('referral', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const referrals = ref<Referral[]>([])
  const rules = ref<RewardRule[]>([])
  const filterTab = ref<'ALL' | 'PENDING' | 'VISITED' | 'DEAL' | 'REWARD'>('ALL')
  const serverTotal = ref(0)
  // referralId → 最新奖励单号（发放走 rewards/{rewardId}/grant，不导出、不进模板）
  const rewardIdMap = new Map<string, string>()

  const total = computed(() => serverTotal.value || referrals.value.length)
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
    return [...list].sort((a, b) => new Date(b.boundAt).getTime() - new Date(a.boundAt).getTime())
  })

  function get(id: string) {
    return referrals.value.find((r) => r.id === id)
  }

  async function load() {
    const resp = await fetchReferralPage({ size: 500 })
    const page = resp.data
    rewardIdMap.clear()
    serverTotal.value = page.totalElements
    referrals.value = page.content.map((row) => {
      if (row.rewardId) rewardIdMap.set(row.referralId, row.rewardId)
      return adapt(row)
    })
  }

  async function confirm(id: string): Promise<boolean> {
    const r = referrals.value.find((x) => x.id === id)
    if (!r || r.status !== 'PENDING' || !auth.can('referral:edit')) return false
    try {
      await confirmReferral(id)
      activity.log(auth.user.name, `确认转介绍归属：${r.referrerName} → ${r.introducedName}`, r.id)
      await load()
      return true
    } catch {
      return false
    }
  }

  async function markVisited(id: string): Promise<boolean> {
    const r = referrals.value.find((x) => x.id === id)
    if (!r || r.status === 'PENDING' || r.status === 'VISITED' || r.status === 'DEAL' || !auth.can('referral:edit')) return false
    try {
      await visitReferral(id)
      activity.log(auth.user.name, `转介绍到店：${r.introducedName}`, r.id)
      await load()
      return true
    } catch {
      return false
    }
  }

  // rewardType/rewardAmount 形参保留（旧页签名兼容）；奖励不再本地生成，
  // 登记走 payReward 两分支（D4-A 手动登记幂等）或后续批次登记 UI。
  async function markDeal(id: string, amount: number, _rewardType?: RewardType, _rewardAmount?: number): Promise<boolean> {
    const r = referrals.value.find((x) => x.id === id)
    if (!r || r.status !== 'VISITED' || !auth.can('referral:edit')) return false
    try {
      await dealReferral(id, Math.round(amount * 100))
      activity.log(auth.user.name, `转介绍成交：${r.introducedName} - ¥${amount}`, r.id)
      await load()
      return true
    } catch {
      return false
    }
  }

  async function payReward(id: string): Promise<boolean> {
    const r = referrals.value.find((x) => x.id === id)
    if (!r || r.rewardStatus !== 'PENDING' || r.status !== 'DEAL' || !auth.can('referral:approve')) return false
    try {
      let rewardId = rewardIdMap.get(id)
      if (!rewardId) {
        // 无已登记奖励：按行上奖励形式先登记（idem_key 幂等不重复落库）再发放
        if (r.rewardAmount <= 0) return false
        const wResp = await createReferralReward(id, r.rewardType === 'POINTS'
          ? { rewardType: 'POINT', triggerEvent: 'DEAL', points: r.rewardAmount }
          : { rewardType: r.rewardType === 'COUPON' ? 'COUPON' : 'GRANT', triggerEvent: 'DEAL', amountCents: Math.round(r.rewardAmount * 100) })
        rewardId = wResp.data.rewardId
      }
      await grantReferralReward(rewardId)
      activity.log(auth.user.name, `发放转介绍奖励：${r.referrerName} - ${r.rewardType === 'POINTS' ? r.rewardAmount + ' 积分' : '¥' + r.rewardAmount}`, r.id)
      await load()
      return true
    } catch {
      return false
    }
  }

  // P5-B91 卡2：新建绑定接真（M5 视图「新建绑定」弹层入口）。
  // clientToken=crypto.randomUUID() 幂等（重复提交/重放返回同一绑定单）；
  // campaignId 可空（空=不挂活动），validDays 可空（空=后端 GLOBAL 配置回退）；
  // campaignId 非空须存在且 ONGOING（后端 422），成功后全量 refresh。
  async function createBinding(input: {
    referrerCustomerId: string
    refereeCustomerId: string
    campaignId?: string
    validDays?: number
    remark?: string
  }): Promise<boolean> {
    if (!auth.can('referral:edit')) return false
    try {
      const resp = await createReferral({
        referrerCustomerId: input.referrerCustomerId,
        refereeCustomerId: input.refereeCustomerId,
        campaignId: input.campaignId || undefined,
        validDays: input.validDays,
        remark: input.remark || undefined,
        clientToken: crypto.randomUUID(),
      })
      const row = resp.data
      activity.log(
        auth.user.name,
        `新建转介绍绑定：${row.referrerName ?? row.referrerCustomerId} → ${row.refereeName ?? row.refereeCustomerId}`,
        row.referralId,
      )
      await load()
      return true
    } catch (e) {
      console.warn('[referral] 新建转介绍绑定失败', e)
      return false
    }
  }

  // 创建保留内存行为：B86 不接真——旧页 /m3-referral 无 customerId 选择入口
  // （后端创建需 referrerCustomerId/refereeCustomerId）。M5 视图创建入口已走 createBinding 接真。
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

  // 每次进页重拉真实数据；奖励规则为本地配置（后端暂无 rules 表，本批不接真）
  async function seed() {
    if (!rules.value.length) {
      rules.value = [
        { type: 'POINTS', name: '积分奖励', threshold: '被介绍人首次到店', amount: 500, desc: '到店即奖励介绍人 500 积分' },
        { type: 'COUPON', name: '项目券', threshold: '被介绍人成交', amount: 300, desc: '成交后发放 300 元项目券，限本人使用' },
        { type: 'CASH', name: '现金回馈', threshold: '被介绍人成交满 ¥2000', amount: 200, desc: '成交满 2000 元返介绍人 200 元现金' },
      ]
    }
    try {
      await load()
    } catch (e) {
      console.warn('[referral] 加载转介绍列表失败', e)
    }
  }

  return {
    referrals, rules, filterTab,
    total, newThisMonth, dealt, pendingReward, totalRewardPaid, filtered,
    get, create, createBinding, confirm, markVisited, markDeal, payReward, updateRule, seed, load,
    STATUS_LABEL, REWARD_LABEL, REWARD_TYPE_LABEL, STATUS_ORDER,
  }
})
