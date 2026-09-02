import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useM5CoreStore } from '@/stores/m5Core'
import { useM1MarketingStore, type CampaignType } from '@/stores/m1Marketing'

// ============================================================
// M5-14 营销数据看板 store
// - 全量汇总 m5Core（推送/渠道）+ m1Marketing（活动）
// - 自己只补：近 6 月触达/转化趋势 seed
// ============================================================

export interface TrendPoint {
  month: string
  reach: number      // 触达
  converted: number  // 转化成交人数
}
export interface FunnelStage {
  key: string
  label: string
  value: number
  ratio: number // 相对上一级的转化率
}

export const useM5DashStore = defineStore('m5Dash', () => {
  const core = useM5CoreStore()
  const m1 = useM1MarketingStore()

  const trends = ref<TrendPoint[]>([])
  const seeded = ref(false)

  // 累计触达：所有已发送批次 delivered 之和
  const totalReach = computed(() =>
    core.batches
      .filter((b) => b.status === 'SENT')
      .reduce((s, b) => s + b.delivered, 0),
  )

  // 累计成交（与核心口径一致）
  const totalDeals = computed(() => core.totalDeals)

  // 转化率：成交 / 触达
  const conversionRate = computed(() => {
    if (!totalReach.value) return 0
    return Number(((totalDeals.value / totalReach.value) * 100).toFixed(1))
  })

  const kpis = computed(() => ({
    totalReach: totalReach.value,
    conversionRate: conversionRate.value,
    overallRoi: core.overallRoi,
    campaignRevenue: m1.stats.amount,
  }))

  // 近 6 月趋势：用 seed 数据 + 当前累计作为最后一点
  const trendCategories = computed(() => trends.value.map((t) => t.month))
  const reachSeries = computed(() => [{
    name: '触达人数',
    values: trends.value.map((t) => t.reach),
  }])
  const convertedSeries = computed(() => [{
    name: '转化成交',
    values: trends.value.map((t) => t.converted),
  }])
  const trendSeries = computed(() => [
    { name: '触达人数', values: trends.value.map((t) => t.reach) },
    { name: '转化成交', values: trends.value.map((t) => t.converted) },
  ])

  // 各渠道成交对比
  const channelDealItems = computed(() =>
    core.channels
      .slice()
      .sort((a, b) => b.deals - a.deals)
      .map((c) => ({ label: c.name, values: [c.deals] })),
  )

  // 活动类型分布（按实际成交额聚合）
  const typeDistribution = computed(() => {
    const map = new Map<CampaignType, number>()
    m1.campaigns.forEach((c) => {
      if (c.status === 'CANCELLED') return
      map.set(c.type, (map.get(c.type) ?? 0) + Math.max(c.actualAmount, 0))
    })
    return Array.from(map.entries())
      .filter(([, v]) => v > 0)
      .map(([type, value]) => ({
        label: m1.CAMPAIGN_TYPE_LABEL[type],
        value,
      }))
  })

  // 渠道排行榜
  interface RankRow {
    key: string
    name: string
    leads: number
    deals: number
    roi: number
    revenue: number
    rank: number
  }
  const channelRank = computed<RankRow[]>(() =>
    core.channels
      .slice()
      .map((c, i) => ({
        key: c.key,
        name: c.name,
        leads: c.leads,
        deals: c.deals,
        revenue: c.revenue,
        roi: c.adCost > 0 ? Number((c.revenue / c.adCost).toFixed(1)) : 0,
        rank: i + 1,
      }))
      .sort((a, b) => b.revenue - a.revenue)
      .map((r, i) => ({ ...r, rank: i + 1 })),
  )

  // 转化漏斗：曝光→领券→到店→成交
  const funnel = computed<FunnelStage[]>(() => {
    const exposure = totalReach.value * 4 // 演示：曝光≈4 倍触达（含多渠道展示）
    const couponReceived = m1.coupons.reduce((s, c) => s + c.received, 0)
    const arrival = Math.round(couponReceived * 0.62)
    const deals = totalDeals.value
    const safe = (n: number) => Math.max(0, n)
    const stages = [
      { key: 'exposure', label: '曝光', value: safe(exposure) },
      { key: 'coupon', label: '领券', value: safe(couponReceived) },
      { key: 'arrival', label: '到店', value: safe(arrival) },
      { key: 'deal', label: '成交', value: safe(deals) },
    ]
    return stages.map((s, i) => ({
      ...s,
      ratio: i === 0 ? 100 : Number(((s.value / Math.max(1, stages[i - 1].value)) * 100).toFixed(1)),
    }))
  })

  // 推送效果（最近批次）
  const pushEffectiveness = computed(() => {
    const sent = core.batches.filter((b) => b.status === 'SENT')
    const delivered = sent.reduce((s, b) => s + b.delivered, 0)
    const clicked = sent.reduce((s, b) => s + b.clicked, 0)
    const converted = sent.reduce((s, b) => s + b.converted, 0)
    return {
      sent: sent.length,
      delivered,
      clicked,
      converted,
      ctr: delivered ? Number(((clicked / delivered) * 100).toFixed(1)) : 0,
      cvr: clicked ? Number(((converted / clicked) * 100).toFixed(1)) : 0,
    }
  })

  function seed() {
    if (seeded.value) return
    core.seed()
    const months: string[] = []
    const now = new Date()
    for (let i = 5; i >= 0; i--) {
      const d = new Date(now.getFullYear(), now.getMonth() - i, 1)
      months.push(`${d.getMonth() + 1}月`)
    }
    const reachBase = [8200, 9600, 11200, 12800, 14500, totalReach.value || 15600]
    const convBase = [180, 224, 286, 342, 408, totalDeals.value || 510]
    trends.value = months.map((m, i) => ({
      month: m,
      reach: reachBase[i],
      converted: convBase[i],
    }))
    seeded.value = true
  }

  return {
    trends,
    kpis,
    totalReach,
    conversionRate,
    trendCategories,
    reachSeries,
    convertedSeries,
    trendSeries,
    channelDealItems,
    typeDistribution,
    channelRank,
    funnel,
    pushEffectiveness,
    seed,
  }
})
