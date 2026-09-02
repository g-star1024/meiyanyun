import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import * as api from '@/api/marketing'
import type { CampaignDTO } from '@/api/marketing'
import { useM5CouponStore } from '@/stores/m5Coupon'

// ============================================================
// 营销中心 store（M1 集团管控 / 营销中心，已接真实 marketing-service）
//
// 适配层（铁律：模板/样式零改动，只换数据源）：
//  - 后端金额 bigint 存「分」，前端活规格用「元」：fen2yuan / yuan2fen
//  - channels 后端存 JSON 数组文本（渠道中文名），前端用数组
//  - 字段名 campaignId→id、campaignName→name、campaignType→type
//  - 关联券复用 m5Coupon store 的真实券（按 campaignId 过滤）
//  - 写动作经网关，非法流转后端 400 中文错误经 errMsg() 外露
// ============================================================

export type CampaignStatus = 'DRAFT' | 'SCHEDULED' | 'RUNNING' | 'ENDED' | 'CANCELLED'
export type CampaignType = 'FULL_REDUCE' | 'DISCOUNT' | 'COUPON_PACK' | 'GIFT' | 'NEWBIE' | 'VIP_DAY'

export const CAMPAIGN_STATUS_LABEL: Record<CampaignStatus, string> = {
  DRAFT: '草稿', SCHEDULED: '待开始', RUNNING: '进行中', ENDED: '已结束', CANCELLED: '已取消',
}
export const CAMPAIGN_TYPE_LABEL: Record<CampaignType, string> = {
  FULL_REDUCE: '满减', DISCOUNT: '折扣', COUPON_PACK: '券包', GIFT: '赠品', NEWBIE: '新客礼', VIP_DAY: '会员日',
}
export const CHANNELS = ['抖音', '小红书', '美团', '大众点评', '微信私域', '视频号']

/** 合法状态流转（与后端 CampaignService TRANSITIONS 一致，用于按钮置灰；最终校验在后端） */
const TRANSITIONS: Record<CampaignStatus, CampaignStatus[]> = {
  DRAFT: ['SCHEDULED', 'CANCELLED'],
  SCHEDULED: ['RUNNING', 'DRAFT', 'CANCELLED'],
  RUNNING: ['ENDED', 'CANCELLED'],
  ENDED: [],
  CANCELLED: [],
}

/** 分 → 元 */
const fen2yuan = (fen?: number | null): number => (fen == null ? 0 : fen / 100)
/** 元 → 分 */
const yuan2fen = (yuan: number): number => Math.round((yuan || 0) * 100)

export interface Coupon {
  id: string
  campaignId: string
  code: string
  name: string
  type: 'AMOUNT' | 'RATE'
  value: number // 元 或 折扣(如 8.5)
  threshold: number // 使用门槛
  total: number
  received: number
  used: number
}

export interface Campaign {
  id: string
  name: string
  type: CampaignType
  status: CampaignStatus
  channels: string[]
  startDate: string
  endDate: string
  budget: number
  spent: number
  targetAmount: number
  actualAmount: number
  newCustomers: number
  storeScope: string
  owner: string
  remark?: string
}

/** yyyy-MM-dd（后端 LocalDate 直接可用） */
function dayOf(s?: string | null): string {
  return s ? s.slice(0, 10) : ''
}

/** 后端活动 → 前端活规格（分→元、channels JSON 串→数组、字段名映射） */
function adaptCampaign(d: CampaignDTO): Campaign {
  let channels: string[] = []
  if (d.channels) {
    try {
      const parsed = JSON.parse(d.channels)
      if (Array.isArray(parsed)) channels = parsed.filter((x): x is string => typeof x === 'string')
    } catch {
      channels = []
    }
  }
  return {
    id: d.campaignId,
    name: d.campaignName,
    type: d.campaignType as CampaignType,
    status: d.status as CampaignStatus,
    channels,
    startDate: dayOf(d.startDate),
    endDate: dayOf(d.endDate),
    budget: fen2yuan(d.budget),
    spent: fen2yuan(d.spent),
    targetAmount: fen2yuan(d.targetAmount),
    actualAmount: fen2yuan(d.actualAmount),
    newCustomers: d.newCustomers ?? 0,
    storeScope: d.storeScope || '全部门店',
    owner: d.owner || '系统',
    remark: d.remark || undefined,
  }
}

export const useM1MarketingStore = defineStore('m1Marketing', () => {
  const campaigns = ref<Campaign[]>([])
  const loaded = ref(false)

  const stats = computed(() => {
    const list = campaigns.value.filter((c) => c.status !== 'CANCELLED')
    return {
      running: list.filter((c) => c.status === 'RUNNING').length,
      amount: list.reduce((s, c) => s + c.actualAmount, 0),
      newCustomers: list.reduce((s, c) => s + c.newCustomers, 0),
      spent: list.reduce((s, c) => s + c.spent, 0),
      roi: 0,
    }
  })
  // roi 单独算（依赖 spent）
  const roi = computed(() => {
    const s = stats.value
    return s.spent ? Number((s.amount / s.spent).toFixed(1)) : 0
  })

  function campaign(id: string) { return campaigns.value.find((c) => c.id === id) }

  /**
   * 全量券兼容层：旧 mock 期 m1.coupons 的消费方（核销 m5Core / 看板漏斗 m5Dash /
   * 直播挂券 m5Live / 会员日 m5Calendar）数据源切到真实 m5Coupon store，形状与旧 Coupon 一致。
   * PACKAGE 券包归入 AMOUNT 展示；store 在 computed 内取用，无初始化顺序依赖。
   */
  const coupons = computed<Coupon[]>(() => {
    const m5 = useM5CouponStore()
    return m5.coupons.map((c) => ({
      id: c.id,
      campaignId: c.campaignId ?? '',
      code: c.code || c.id,
      name: c.name,
      type: c.type === 'RATE' ? 'RATE' as const : 'AMOUNT' as const,
      value: c.value,
      threshold: c.threshold,
      total: c.total,
      received: c.received,
      used: c.used,
    }))
  })

  /** 活动关联券：复用 m5Coupon store 的真实券（按 campaignId 过滤）；store 在函数内取用，无初始化顺序依赖 */
  function couponsOf(campaignId: string): Coupon[] {
    return coupons.value.filter((c) => c.campaignId === campaignId)
  }

  function canTransit(c: Campaign, to: CampaignStatus) { return TRANSITIONS[c.status].includes(to) }
  function achieveRate(c: Campaign) { return c.targetAmount ? Math.min(999, Math.round((c.actualAmount / c.targetAmount) * 100)) : 0 }
  function campaignRoi(c: Campaign) { return c.spent ? Number((c.actualAmount / c.spent).toFixed(1)) : 0 }

  /** 拉取真实活动列表（幂等：已加载默认不重复，force 用于写后重拉）；顺带确保关联券 store 已加载（详情抽屉 couponsOf 依赖） */
  async function seed(force = false) {
    const m5 = useM5CouponStore()
    const [res] = await Promise.all([
      api.listCampaigns(),
      m5.seed(force),
    ])
    campaigns.value = res.data.map(adaptCampaign)
    loaded.value = true
  }

  /** 状态流转：非法流转/终态由后端 400 拦截，错误向上抛给视图中文外露 */
  async function transit(id: string, to: CampaignStatus) {
    await api.transitCampaign(id, to)
    await seed(true)
  }

  async function createCampaign(
    payload: Omit<Campaign, 'id' | 'status' | 'spent' | 'actualAmount' | 'newCustomers'>,
  ): Promise<Campaign> {
    const res = await api.createCampaign({
      name: payload.name,
      type: payload.type,
      channels: payload.channels,
      startDate: payload.startDate,
      endDate: payload.endDate,
      budget: yuan2fen(payload.budget),
      targetAmount: yuan2fen(payload.targetAmount),
      storeScope: payload.storeScope,
      owner: payload.owner,
      remark: payload.remark,
    })
    await seed(true)
    return campaign(res.data.campaignId) ?? adaptCampaign(res.data)
  }

  return {
    campaigns, coupons, CHANNELS, CAMPAIGN_STATUS_LABEL, CAMPAIGN_TYPE_LABEL,
    stats: computed(() => ({ ...stats.value, roi: roi.value })),
    campaign, couponsOf, canTransit, achieveRate, roi: campaignRoi, transit, createCampaign, seed,
  }
})
