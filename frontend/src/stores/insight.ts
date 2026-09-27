// ============================================================
// Insight 客户洞察报告 store（M3-19）
// 客户结构/复购/流失多维报告。
// M3-B7 卡1 切真：数据源 mock → GET /api/customer/m3/insight（DESIGN-M3
// §3 M3-19 只读聚合，七组全部由服务端统算）；导出签名全保留（铁律-1-B）。
// ============================================================
import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import {
  getInsight,
  type InsightPeriod,
  type InsightSummaryDto,
  type RepurchaseItemDto,
  type TopInsightDto,
} from '@/api/insight'

export interface InsightTrend { month: string; newCustomers: number; activeCustomers: number; repurchaseRate: number; churnRate: number }
export interface LevelDist { level: string; count: number; percent: number; color: string }
export interface ChannelDist { channel: string; count: number; percent: number }

const EMPTY_SUMMARY: InsightSummaryDto = {
  totalCustomers: 0,
  newThisPeriod: 0,
  activeRate: 0,
  repurchaseRate: 0,
  churnRate: 0,
  avgLtv: 0,
  nps: 0,
}

export const useInsightStore = defineStore('insight', () => {
  const period = ref<InsightPeriod>('90d')

  const summary = ref<InsightSummaryDto>({ ...EMPTY_SUMMARY })
  const trend = ref<InsightTrend[]>([])
  const levelDist = ref<LevelDist[]>([])
  const channelDist = ref<ChannelDist[]>([])
  const topInsights = ref<TopInsightDto[]>([])
  const repurchaseItems = ref<RepurchaseItemDto[]>([])
  const loading = ref(false)

  async function load() {
    loading.value = true
    try {
      const dto = await getInsight(period.value)
      summary.value = dto.summary ?? { ...EMPTY_SUMMARY }
      trend.value = dto.trend ?? []
      levelDist.value = dto.levelDist ?? []
      channelDist.value = dto.channelDist ?? []
      topInsights.value = dto.topInsights ?? []
      repurchaseItems.value = dto.repurchaseItems ?? []
    } finally {
      loading.value = false
    }
  }

  watch(period, () => {
    void load()
  })

  return { period, summary, trend, levelDist, channelDist, topInsights, repurchaseItems, loading, load }
})
