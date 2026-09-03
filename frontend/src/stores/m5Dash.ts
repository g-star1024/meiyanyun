import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import * as api from '@/api/marketing'
import { CAMPAIGN_TYPE_LABEL, type CampaignType } from '@/stores/m1Marketing'

// ============================================================
// M5-14 营销数据看板 store（已接真实 GET /marketing/stats/overview）
//
// 适配层（铁律：模板/样式零改动，只换数据源；金额后端存「分」→ 前端用「元」）：
//  - 推送效果 push_record 无到达/点击/转化回执字段，后端用推送发送+内容曝光≈触达、
//    内容互动（海报扫码/直播商品点击/短视频点赞）≈点击、内容成交≈转化，真实表近似不造数；
//  - 漏斗为「到店核销」O2O 链路严格递减（直播/短视频线上团购成交不经过店，不计入漏斗成交）；
//  - 渠道排行营收/成交/线索取内容表（海报=微信私域，DOUYIN→抖音 等平台映射），
//    投放额取活动 spent 按渠道数均摊，按营收降序；
//  - KPI 累计触达/转化率为全域口径（推送+海报+直播+短视频），活动营收/ROI 取 campaign 块。
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

/** 分 → 元 */
const fen2yuan = (fen?: number | null): number => (fen == null ? 0 : fen / 100)

/** 后端月份 yyyy-MM（2026-08）→ 图表标签「8月」 */
function monthLabel(ym: string): string {
  const m = Number(ym.slice(5, 7))
  return Number.isNaN(m) ? ym : `${m}月`
}

export const useM5DashStore = defineStore('m5Dash', () => {
  const stats = ref<api.MarketingStatsDTO | null>(null)
  const seeded = ref(false)

  // 累计触达：全域触达（推送发送 + 海报分享 + 直播观看 + 短视频播放，后端聚合）
  const totalReach = computed(() => stats.value?.push.delivered ?? 0)

  // 全域成交单量：海报成交 + 直播成交 + 短视频成交（线上团购 + 私域裂变，含不经漏斗的线上单）
  const totalDeals = computed(() => stats.value?.push.converted ?? 0)

  // 转化率：全域成交 / 全域触达（百分比 1 位小数；营销曝光基数大，全域转化率天然低于到店环节）
  const conversionRate = computed(() => {
    if (!totalReach.value) return 0
    return Number(((totalDeals.value / totalReach.value) * 100).toFixed(1))
  })

  const kpis = computed(() => ({
    totalReach: totalReach.value,
    conversionRate: conversionRate.value,
    overallRoi: stats.value?.campaign.overallRoi ?? 0,
    campaignRevenue: fen2yuan(stats.value?.campaign.totalActualAmount),
  }))

  // 近 6 月趋势（后端按 push.sentAt/海报 createdAt/直播 startTime/短视频 publishedAt 分桶）
  const trends = computed<TrendPoint[]>(() =>
    (stats.value?.trend.points ?? []).map((p) => ({
      month: monthLabel(p.month),
      reach: p.reach,
      converted: p.converted,
    })),
  )
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

  // 各渠道成交对比（后端按营收降序，条形图按成交单量再排一次）
  const channelDealItems = computed(() =>
    (stats.value?.channel.rows ?? [])
      .slice()
      .sort((a, b) => b.deals - a.deals)
      .map((c) => ({ label: c.name, values: [c.deals] })),
  )

  // 活动类型分布（按实际成交额聚合，分→元；排除已取消，0 额不展示）
  const typeDistribution = computed(() => {
    const map = new Map<string, number>()
    ;(stats.value?.campaign.rows ?? []).forEach((r) => {
      if (r.status === 'CANCELLED') return
      map.set(r.campaignType, (map.get(r.campaignType) ?? 0) + fen2yuan(r.actualAmount))
    })
    return Array.from(map.entries())
      .filter(([, v]) => v > 0)
      .map(([type, value]) => ({
        label: CAMPAIGN_TYPE_LABEL[type as CampaignType] ?? type,
        value,
      }))
  })

  // 渠道排行榜（金额分→元；后端已按营收降序，rank 顺序重排兜底）
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
    (stats.value?.channel.rows ?? [])
      .map((r) => ({
        key: r.key,
        name: r.name,
        leads: r.leads,
        deals: r.deals,
        revenue: fen2yuan(r.revenue),
        roi: r.roi,
        rank: 0,
      }))
      .sort((a, b) => b.revenue - a.revenue)
      .map((r, i) => ({ ...r, rank: i + 1 })),
  )

  // 转化漏斗：曝光→领券→到店→成交（后端 O2O 链路聚合，ratio 直接透传）
  const funnel = computed<FunnelStage[]>(() => stats.value?.funnel.stages ?? [])

  // 推送效果（全域触达近似，后端聚合；ctr/cvr 为百分比）
  const pushEffectiveness = computed(() => {
    const p = stats.value?.push
    return {
      sent: p?.sent ?? 0,
      delivered: p?.delivered ?? 0,
      clicked: p?.clicked ?? 0,
      converted: p?.converted ?? 0,
      ctr: p?.ctr ?? 0,
      cvr: p?.cvr ?? 0,
    }
  })

  /** 拉取看板汇总（幂等：已加载默认不重复，force 用于写后重拉；失败不白屏） */
  async function seed(force = false) {
    if (seeded.value && !force) return
    try {
      const res = await api.getMarketingStats()
      stats.value = res.data
      seeded.value = true
    } catch (e) {
      console.error('营销看板汇总加载失败', e)
    }
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
