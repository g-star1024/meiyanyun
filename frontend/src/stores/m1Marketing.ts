import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

// ============================================================
// 营销中心 store（M1 集团管控 / 营销中心）
// - Campaign 营销活动：DRAFT 草稿 → SCHEDULED 待开始 → RUNNING 进行中 → ENDED 已结束；CANCELLED 旁路
// - 类型：满减/折扣/券包/赠品/新客礼/会员日
// - 多渠道投放（抖音/小红书/美团/微信私域/大众点评）
// - 关联 Coupon 优惠券（领/用/核销）
// - KPI：进行中数、累计成交额、引流新客、ROI
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

const TRANSITIONS: Record<CampaignStatus, CampaignStatus[]> = {
  DRAFT: ['SCHEDULED', 'CANCELLED'],
  SCHEDULED: ['RUNNING', 'DRAFT', 'CANCELLED'],
  RUNNING: ['ENDED', 'CANCELLED'],
  ENDED: [],
  CANCELLED: [],
}

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

let _cid = 0
function cid(p: string) { _cid += 1; return `${p}-${Date.now().toString(36)}-${_cid}` }
function dateOffset(n: number) {
  const d = new Date(); d.setDate(d.getDate() + n)
  return d.toISOString().slice(0, 10)
}

export const useM1MarketingStore = defineStore('m1Marketing', () => {
  const campaigns = ref<Campaign[]>([])
  const coupons = ref<Coupon[]>([])
  const seeded = ref(false)

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
  function couponsOf(campaignId: string) { return coupons.value.filter((c) => c.campaignId === campaignId) }
  function canTransit(c: Campaign, to: CampaignStatus) { return TRANSITIONS[c.status].includes(to) }
  function achieveRate(c: Campaign) { return c.targetAmount ? Math.min(999, Math.round((c.actualAmount / c.targetAmount) * 100)) : 0 }
  function campaignRoi(c: Campaign) { return c.spent ? Number((c.actualAmount / c.spent).toFixed(1)) : 0 }

  function transit(id: string, to: CampaignStatus) {
    const c = campaign(id)
    if (c && canTransit(c, to)) c.status = to
  }

  function createCampaign(payload: Omit<Campaign, 'id' | 'status' | 'spent' | 'actualAmount' | 'newCustomers'>): Campaign {
    const c: Campaign = { ...payload, id: cid('cmp'), status: 'DRAFT', spent: 0, actualAmount: 0, newCustomers: 0 }
    campaigns.value.unshift(c)
    return c
  }

  function seed() {
    if (seeded.value) return
    const mk = (c: Partial<Campaign> & { name: string; type: CampaignType; status: CampaignStatus; channels: string[] }): Campaign => ({
      id: cid('cmp'), name: c.name, type: c.type, status: c.status, channels: c.channels,
      startDate: c.startDate!, endDate: c.endDate!, budget: c.budget ?? 0, spent: c.spent ?? 0,
      targetAmount: c.targetAmount ?? 0, actualAmount: c.actualAmount ?? 0, newCustomers: c.newCustomers ?? 0,
      storeScope: c.storeScope ?? '全部门店', owner: c.owner ?? '白桥', remark: c.remark,
    })
    campaigns.value = [
      mk({ name: '暑期水光自由卡', type: 'COUPON_PACK', status: 'RUNNING', channels: ['抖音', '小红书', '美团'], startDate: dateOffset(-20), endDate: dateOffset(20), budget: 80000, spent: 52000, targetAmount: 400000, actualAmount: 386000, newCustomers: 142, owner: '白桥', remark: '主推润致娃娃针次卡，抖音直播核销率 68%' }),
      mk({ name: '新客专享·首次体验礼', type: 'NEWBIE', status: 'RUNNING', channels: ['大众点评', '微信私域'], startDate: dateOffset(-30), endDate: dateOffset(30), budget: 30000, spent: 18000, targetAmount: 150000, actualAmount: 96000, newCustomers: 218, owner: '林微' }),
      mk({ name: '会员日·乔雅登满减', type: 'FULL_REDUCE', status: 'SCHEDULED', channels: ['微信私域', '视频号'], startDate: dateOffset(3), endDate: dateOffset(5), budget: 20000, spent: 0, targetAmount: 200000, actualAmount: 0, newCustomers: 0, owner: '白桥' }),
      mk({ name: '热玛吉抗衰专场', type: 'VIP_DAY', status: 'DRAFT', channels: ['微信私域'], startDate: dateOffset(15), endDate: dateOffset(17), budget: 50000, targetAmount: 600000, owner: '苏晴' }),
      mk({ name: '光子嫩肤买3送1', type: 'GIFT', status: 'ENDED', channels: ['美团', '大众点评'], startDate: dateOffset(-60), endDate: dateOffset(-30), budget: 25000, spent: 24800, targetAmount: 120000, actualAmount: 138000, newCustomers: 76, owner: '白桥' }),
      mk({ name: '保妥适拼团8.5折', type: 'DISCOUNT', status: 'CANCELLED', channels: ['抖音'], startDate: dateOffset(-10), endDate: dateOffset(5), budget: 15000, spent: 1200, targetAmount: 80000, actualAmount: 0, newCustomers: 0, owner: '白桥', remark: '因供应商限价政策中止' }),
    ]
    const running = campaigns.value[0]
    coupons.value = [
      { id: cid('cp'), campaignId: running.id, code: 'WATER500', name: '水光满3000减500', type: 'AMOUNT', value: 500, threshold: 3000, total: 200, received: 156, used: 89 },
      { id: cid('cp'), campaignId: running.id, code: 'NEWBIE88', name: '新客88元体验券', type: 'AMOUNT', value: 200, threshold: 500, total: 500, received: 218, used: 134 },
    ]
    seeded.value = true
  }

  return {
    campaigns, coupons, CHANNELS, CAMPAIGN_STATUS_LABEL, CAMPAIGN_TYPE_LABEL,
    stats: computed(() => ({ ...stats.value, roi: roi.value })),
    campaign, couponsOf, canTransit, achieveRate, roi: campaignRoi, transit, createCampaign, seed,
  }
})
