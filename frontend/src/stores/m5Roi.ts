import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useM5CoreStore, type ChannelKey } from '@/stores/m5Core'
import {
  CAMPAIGN_TYPE_LABEL,
  CAMPAIGN_STATUS_LABEL,
  type CampaignStatus,
  type CampaignType,
} from '@/stores/m1Marketing'
import {
  COUPON_TYPE_LABEL,
  COUPON_STATUS_LABEL,
  fen2yuan,
  type CouponType,
  type CouponStatus,
} from '@/stores/m5Coupon'
import * as api from '@/api/marketing'
import type { MarketingStatsDTO } from '@/api/marketing'

// ============================================================
// M5-06 投放 ROI store
// - 渠道业绩：m5Core（外部广告平台 mock，未接入）
// - 活动维度 ROI / 发券核销统计：真实 marketing-service（GET /stats/overview）
// - 归因模型：首触点 / 末触点 / 线性（纯前端演示，权重不同）
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

// -------------------- M5-06 真实统计（GET /stats/overview，金额分→元） --------------------

export interface CouponRoiRow {
  id: string
  name: string
  type: string
  status: string
  total: number
  issued: number
  used: number
  writeoffRate: number
}

export interface CampaignRoiRow {
  id: string
  name: string
  type: string
  spent: number
  actualAmount: number
  targetAmount: number
  roi: number
  achieveRate: number
  newCustomers: number
  status: string
}

export interface CouponStatsView {
  couponKinds: number
  totalStock: number
  totalIssued: number
  totalUsed: number
  writeoffRate: number
  grantBatches: number
  grantedPcs: number
  rows: CouponRoiRow[]
}

export interface CampaignStatsView {
  campaignCount: number
  runningCount: number
  totalSpent: number
  totalActualAmount: number
  totalTargetAmount: number
  totalNewCustomers: number
  overallRoi: number
  achieveRate: number
  rows: CampaignRoiRow[]
}

export interface MarketingStatsView {
  coupon: CouponStatsView
  campaign: CampaignStatsView
}

export function emptyStats(): MarketingStatsView {
  return {
    coupon: { couponKinds: 0, totalStock: 0, totalIssued: 0, totalUsed: 0, writeoffRate: 0, grantBatches: 0, grantedPcs: 0, rows: [] },
    campaign: { campaignCount: 0, runningCount: 0, totalSpent: 0, totalActualAmount: 0, totalTargetAmount: 0, totalNewCustomers: 0, overallRoi: 0, achieveRate: 0, rows: [] },
  }
}

/** 后端统计 → 前端活规格（金额分→元，类型/状态码→中文；比率保持 0~1） */
export function adaptStats(d: MarketingStatsDTO): MarketingStatsView {
  return {
    coupon: {
      couponKinds: d.coupon.couponKinds,
      totalStock: d.coupon.totalStock,
      totalIssued: d.coupon.totalIssued,
      totalUsed: d.coupon.totalUsed,
      writeoffRate: d.coupon.writeoffRate,
      grantBatches: d.coupon.grantBatches,
      grantedPcs: d.coupon.grantedPcs,
      rows: d.coupon.rows.map((r) => ({
        id: r.couponId,
        name: r.couponName,
        type: COUPON_TYPE_LABEL[r.couponType as CouponType] ?? r.couponType,
        status: COUPON_STATUS_LABEL[r.status as CouponStatus] ?? r.status,
        total: r.totalQty,
        issued: r.issuedQty,
        used: r.usedQty,
        writeoffRate: r.writeoffRate,
      })),
    },
    campaign: {
      campaignCount: d.campaign.campaignCount,
      runningCount: d.campaign.runningCount,
      totalSpent: fen2yuan(d.campaign.totalSpent),
      totalActualAmount: fen2yuan(d.campaign.totalActualAmount),
      totalTargetAmount: fen2yuan(d.campaign.totalTargetAmount),
      totalNewCustomers: d.campaign.totalNewCustomers,
      overallRoi: d.campaign.overallRoi,
      achieveRate: d.campaign.achieveRate,
      rows: d.campaign.rows.map((r) => ({
        id: r.campaignId,
        name: r.campaignName,
        type: CAMPAIGN_TYPE_LABEL[r.campaignType as CampaignType] ?? r.campaignType,
        spent: fen2yuan(r.spent),
        actualAmount: fen2yuan(r.actualAmount),
        targetAmount: fen2yuan(r.targetAmount),
        roi: r.roi,
        achieveRate: r.targetAmount > 0 ? Math.round((r.actualAmount / r.targetAmount) * 10000) / 10000 : 0,
        newCustomers: r.newCustomers,
        status: CAMPAIGN_STATUS_LABEL[r.status as CampaignStatus] ?? r.status,
      })),
    },
  }
}

export const useM5RoiStore = defineStore('m5Roi', () => {
  const core = useM5CoreStore()

  const model = ref<AttributionModel>('LAST')

  const stats = ref<MarketingStatsView>(emptyStats())

  const couponStats = computed(() => stats.value.coupon)
  const campaignStats = computed(() => stats.value.campaign)

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

  /** 活动维度 ROI：真实统计（GET /stats/overview），仅展示已投放（spent>0）的活动，金额已转元 */
  const campaignRows = computed<CampaignRoiRow[]>(() =>
    campaignStats.value.rows.filter((c) => c.spent > 0),
  )

  function setModel(m: AttributionModel) {
    model.value = m
  }

  /** 渠道业绩 mock 同步播种 + 真实发券/核销/活动转化统计（后端空表时回落全 0 空明细） */
  async function seed() {
    core.seed()
    const res = await api.getMarketingStats()
    stats.value = adaptStats(res.data)
  }

  return {
    model,
    kpis,
    channelRows,
    roiBarItems,
    costDonutData,
    attributionRows,
    campaignRows,
    couponStats,
    campaignStats,
    setModel,
    seed,
  }
})
