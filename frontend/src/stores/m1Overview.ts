import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

// 集团经营概览（演示聚合数据，跨域只读看板）
export interface KpiTrend { value: number; label: string }
export interface StoreRank { id: string; name: string; region: string; revenue: number; growth: number; customers: number; satisfaction: number }

const MONTHS = ['1月', '2月', '3月', '4月', '5月', '6月', '7月', '8月']

export const useM1OverviewStore = defineStore('m1Overview', () => {
  const seeded = ref(false)
  function seed() { seeded.value = true }

  const kpis = computed(() => ({
    revenue: { value: 23800, unit: '万元', delta: 12.6, trend: [1820, 1960, 2100, 2280, 2410, 2580, 2660, 2790] },
    newCustomers: { value: 1980, unit: '人', delta: 8.3, trend: [] },
    repurchase: { value: 41.2, unit: '%', delta: 2.1, trend: [] },
    satisfaction: { value: 93.5, unit: '%', delta: -0.8, trend: [] },
    activeCustomers: { value: 12640, unit: '人', delta: 5.4 },
    procedureCount: { value: 8420, unit: '人次', delta: 9.7 },
  }))

  // 月度营收趋势（用于 CBarChart）
  const revenueChart = computed(() => ({
    labels: MONTHS,
    items: [{ values: [1820, 1960, 2100, 2280, 2410, 2580, 2660, 2790] }],
  }))
  // 各区域营收对比
  const regionChart = computed(() => ({
    labels: ['华东区', '华北区', '华南区', '西南区'],
    items: [{ values: [11900, 5900, 6000, 3200] }],
  }))
  // 新客 vs 复购 双序列
  const customerChart = computed(() => ({
    labels: MONTHS.slice(-6),
    items: [
      { values: [320, 350, 380, 360, 410, 460] },
      { values: [880, 920, 960, 940, 1020, 1100] },
    ],
  }))

  const storeRanks = computed<StoreRank[]>(() => [
    { id: 'T01', name: '杭州西湖旗舰院', region: '华东区', revenue: 6800, growth: 14.2, customers: 3240, satisfaction: 96 },
    { id: 'T04', name: '广州天河分院', region: '华南区', revenue: 6000, growth: 18.5, customers: 2680, satisfaction: 95 },
    { id: 'T02', name: '上海静安分院', region: '华东区', revenue: 5100, growth: 9.1, customers: 2410, satisfaction: 92 },
    { id: 'T03', name: '北京朝阳分院', region: '华北区', revenue: 4200, growth: -3.4, customers: 2080, satisfaction: 86 },
    { id: 'T05', name: '成都高新分院', region: '西南区', revenue: 3800, growth: 7.8, customers: 1680, satisfaction: 91 },
  ])

  const alerts = computed(() => [
    { level: 'HIGH', text: '北京朝阳分院 应收账款周转 62 天，超红线 45 天', time: '2 小时前' },
    { level: 'HIGH', text: '热玛吉设备 RMJ-003 超期未校准 107 天', time: '5 小时前' },
    { level: 'MED', text: '上海静安分院 主诊医师配比不足，招聘中', time: '今天 09:20' },
    { level: 'MED', text: '3 条线上推广素材未完成广告审查备案', time: '昨天' },
    { level: 'LOW', text: '成都高新分院 满意度环比下降 2 个百分点', time: '昨天' },
  ])

  return { seeded, seed, kpis, revenueChart, regionChart, customerChart, storeRanks, alerts }
})
