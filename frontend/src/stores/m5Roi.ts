import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useM5CoreStore, type ChannelKey } from '@/stores/m5Core'
import { useM1MarketingStore } from '@/stores/m1Marketing'

// ============================================================
// M5-06 投放 ROI store
// - 消费 m5Core 渠道业绩 / m1Marketing 活动数据，不重复维护业绩
// - 归因模型：首触点 / 末触点 / 线性（纯前端演示，权重不同）
// - 活动维度 ROI：从 m1.campaigns 派生
// ============================================================

export type AttributionModel = 'FIRST' | 'LAST' | 'LINEAR'

export const ATTR_MODEL_LABEL: Record<AttributionModel, string> = {
  FIRST: '首触点',
  LAST: '末触点',
  LINEAR: '线性归因',
}
export const ATTR_MODEL_DESC: Record<AttributionModel, string> = {
  FIRST: '将 100% 转化功劳归给客户第一次接触的渠道，适合新客拉新评估。',
  LAST: '将 100% 转化功劳归给成交前最后一个触点，适合短链路促销评估。',
  LINEAR: '在客户旅程的每个触点平均分配功劳，适合多渠道协同评估。',
}

// 首触点 / 末触点 / 线性 下各渠道权重（演示用，合计 1）
const WEIGHTS: Record<AttributionModel, Record<ChannelKey, number>> = {
  FIRST: {
    meituan: 0.10,
    douyin: 0.34,
    xiaohongshu: 0.22,
    dianping: 0.12,
    xinyang: 0.06,
    referral: 0.08,
    wecom: 0.08,
  },
  LAST: {
    meituan: 0.18,
    douyin: 0.14,
    xiaohongshu: 0.08,
    dianping: 0.16,
    xinyang: 0.04,
    referral: 0.18,
    wecom: 0.22,
  },
  LINEAR: {
    meituan: 0.16,
    douyin: 0.20,
    xiaohongshu: 0.14,
    dianping: 0.14,
    xinyang: 0.06,
    referral: 0.14,
    wecom: 0.16,
  },
}

export const useM5RoiStore = defineStore('m5Roi', () => {
  const core = useM5CoreStore()
  const m1 = useM1MarketingStore()

  const model = ref<AttributionModel>('LAST')

  const kpis = computed(() => ({
    totalAdCost: core.totalAdCost,
    totalLeads: core.totalLeads,
    totalDeals: core.totalDeals,
    overallRoi: core.overallRoi,
  }))

  interface ChannelRoiRow {
    key: ChannelKey
    name: string
    adCost: number
    leads: number
    deals: number
    revenue: number
    roi: number
    commission: number
    connected: boolean
  }

  const channelRows = computed<ChannelRoiRow[]>(() =>
    core.channels.map((c) => {
      const roi = c.adCost > 0 ? Number((c.revenue / c.adCost).toFixed(2)) : 0
      return {
        key: c.key,
        name: c.name,
        adCost: c.adCost,
        leads: c.leads,
        deals: c.deals,
        revenue: c.revenue,
        roi,
        commission: Math.round(c.revenue * c.commissionRate),
        connected: c.connected,
      }
    }),
  )

  const roiBarItems = computed(() =>
    channelRows.value.map((r) => ({ label: r.name, values: [r.roi] })),
  )

  const costDonutData = computed(() =>
    channelRows.value
      .filter((r) => r.adCost > 0)
      .map((r) => ({ label: r.name, value: r.adCost })),
  )

  const attributionRows = computed(() => {
    const w = WEIGHTS[model.value]
    const totalRevenue = core.totalRevenue
    return core.channels.map((c) => {
      const weight = w[c.key] ?? 0
      return {
        key: c.key,
        name: c.name,
        weight,
        attributedRevenue: Math.round(totalRevenue * weight),
      }
    })
  })

  interface CampaignRoiRow {
    id: string
    name: string
    type: string
    spent: number
    actualAmount: number
    roi: number
    newCustomers: number
    status: string
  }

  const campaignRows = computed<CampaignRoiRow[]>(() =>
    m1.campaigns
      .filter((c) => c.spent > 0)
      .map((c) => ({
        id: c.id,
        name: c.name,
        type: m1.CAMPAIGN_TYPE_LABEL[c.type],
        spent: c.spent,
        actualAmount: c.actualAmount,
        roi: c.spent > 0 ? Number((c.actualAmount / c.spent).toFixed(2)) : 0,
        newCustomers: c.newCustomers,
        status: m1.CAMPAIGN_STATUS_LABEL[c.status],
      })),
  )

  function setModel(m: AttributionModel) {
    model.value = m
  }

  function seed() {
    core.seed()
  }

  return {
    model,
    kpis,
    channelRows,
    roiBarItems,
    costDonutData,
    attributionRows,
    campaignRows,
    setModel,
    seed,
  }
})
