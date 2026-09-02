// ============================================================
// Insight 客户洞察报告 store（M3-19）
// 客户结构/复购/流失多维报告。
// ============================================================
import { defineStore } from 'pinia'
import { ref } from 'vue'

export interface InsightTrend { month: string; newCustomers: number; activeCustomers: number; repurchaseRate: number; churnRate: number }
export interface LevelDist { level: string; count: number; percent: number; color: string }
export interface ChannelDist { channel: string; count: number; percent: number }

export const useInsightStore = defineStore('insight', () => {
  const period = ref<'30d' | '90d' | '12m'>('90d')

  const summary = ref({
    totalCustomers: 19847,
    newThisPeriod: 1284,
    activeRate: 62.4,
    repurchaseRate: 38.7,
    churnRate: 8.2,
    avgLtv: 12680,
    nps: 64,
  })

  const trend = ref<InsightTrend[]>([
    { month: '3月', newCustomers: 320, activeCustomers: 9800, repurchaseRate: 35.2, churnRate: 9.1 },
    { month: '4月', newCustomers: 386, activeCustomers: 10240, repurchaseRate: 36.8, churnRate: 8.7 },
    { month: '5月', newCustomers: 412, activeCustomers: 10860, repurchaseRate: 37.5, churnRate: 8.3 },
    { month: '6月', newCustomers: 298, activeCustomers: 11200, repurchaseRate: 38.1, churnRate: 8.0 },
    { month: '7月', newCustomers: 445, activeCustomers: 11860, repurchaseRate: 38.9, churnRate: 7.6 },
    { month: '8月', newCustomers: 423, activeCustomers: 12380, repurchaseRate: 38.7, churnRate: 8.2 },
  ])

  const levelDist = ref<LevelDist[]>([
    { level: '普通会员', count: 11908, percent: 60, color: 'var(--c-text-3)' },
    { level: '银卡', count: 4366, percent: 22, color: '#9ca3af' },
    { level: '金卡', count: 2183, percent: 11, color: '#f59e0b' },
    { level: '钻石', count: 992, percent: 5, color: '#6366f1' },
    { level: '黑钻', count: 398, percent: 2, color: '#111827' },
  ])

  const channelDist = ref<ChannelDist[]>([
    { channel: '自然到店', count: 7940, percent: 40 },
    { channel: '线上预约', count: 4960, percent: 25 },
    { channel: '转介绍', count: 3572, percent: 18 },
    { channel: '营销活动', count: 2382, percent: 12 },
    { channel: '私域企微', count: 993, percent: 5 },
  ])

  const topInsights = ref([
    { icon: 'trend-up', tone: 'success', title: '复购率连续 6 个月上升', desc: '从 35.2% 提升至 38.7%，主要由胶原水光复购套餐带动。' },
    { icon: 'alert', tone: 'warning', title: '90 天沉睡客户 1,284 人', desc: '其中高价值客户 86 人，建议立即触发沉睡唤醒任务。' },
    { icon: 'customer', tone: 'brand', title: '转介绍渠道增长 23%', desc: '本月转介绍贡献新客 445 人，转介绍奖励 ROI 达 1:4.2。' },
    { icon: 'bell', tone: 'danger', title: '差评 NPS 贬损者 12 人待跟进', desc: '近 30 天 0-6 分评价 12 条，已自动创建跟进任务。' },
  ])

  const repurchaseItems = ref([
    { name: '胶原水光复购', count: 386, rate: 62, amount: 1_860_000 },
    { name: '超声炮年卡', count: 142, rate: 48, amount: 4_260_000 },
    { name: '热玛吉维护', count: 98, rate: 35, amount: 3_920_000 },
    { name: '光子嫩肤疗程', count: 524, rate: 71, amount: 2_096_000 },
    { name: '玻尿酸补打', count: 268, rate: 44, amount: 1_608_000 },
  ])

  return { period, summary, trend, levelDist, channelDist, topInsights, repurchaseItems }
})
