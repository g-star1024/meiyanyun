// 集团经营概览（M1 集团屏 /m1）——B49 卡4 接真（铁律 -1-D 跨店例外域，只读）。
// 数据源：GET /api/finance/group-overview（finance-service · P5-B97 起读 monthly_store_metrics 月度事实表，金额「分」）
//   + GET /api/stores（store-service 门店名录）；数据范围随登录人数据域（DataScope）逐行收敛。
// 口径：hero=最新月合计营收（分→万元）；营收趋势=各月合计；区域对比/门店排行=月报全期累计；
//   新客/复购/活跃客户/治疗人次已有源（monthTotals 合计）本屏 KPI 暂未接入；满意度仍无源 → null 显「—」（已入 Backlog）；
//   环比 delta：月报月份不相邻（2026-07 种子 / 2026-09 真实）不可比 → null 不显示；
//   客群双序列图（新客 vs 复购）无数据源 → 空序列（CBarChart 空安全已实证）。
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { listGroupOverview, type GroupOverviewView } from '@/api/finance'
import { listStores, type Store } from '@/api/org'

export interface KpiTrend { value: number; label: string }
export interface StoreRank {
  id: string
  name: string
  region: string
  revenue: number // 万元（月报全期累计）
  growth: number | null // 营收环比 %：月份不相邻不可比 → null 显「—」
  customers: number | null // 客户数：无数据源 → null 显「—」
  satisfaction: number | null // 满意度：无数据源 → null 显「—」
}
export interface OverviewAlert { level: 'HIGH' | 'MED' | 'LOW'; text: string; time: string }

const fen2wan = (fen: number) => Math.round((fen / 1e6) * 10) / 10 // 分 → 万元（1 位小数）

export const useM1OverviewStore = defineStore('m1Overview', () => {
  const ov = ref<GroupOverviewView | null>(null)
  const storeList = ref<Store[]>([])
  const loaded = ref(false)
  const loading = ref(false)
  const error = ref('')

  const storeName = (code: string) =>
    storeList.value.find((s) => s.storeCode === code)?.storeName ?? code
  const storeRegion = (code: string) =>
    storeList.value.find((s) => s.storeCode === code)?.region ?? '—'

  // 月合计（后端已按 periodMonth 升序）
  const monthTotals = computed(() => ov.value?.monthTotals ?? [])
  const latest = computed(() => monthTotals.value[monthTotals.value.length - 1] ?? null)

  const kpis = computed(() => ({
    revenue: {
      value: (latest.value ? fen2wan(latest.value.revenue) : null) as number | null,
      unit: '万元',
      delta: null as number | null,
      trend: monthTotals.value.map((t) => fen2wan(t.revenue)),
    },
    newCustomers: { value: null as number | null, unit: '人', delta: null as number | null, trend: [] as number[] },
    repurchase: { value: null as number | null, unit: '%', delta: null as number | null, trend: [] as number[] },
    satisfaction: { value: null as number | null, unit: '%', delta: null as number | null, trend: [] as number[] },
    activeCustomers: { value: null as number | null, unit: '人', delta: null as number | null },
    procedureCount: { value: null as number | null, unit: '人次', delta: null as number | null },
  }))

  // 月度营收趋势（用于 CBarChart，各月合计）
  const revenueChart = computed(() => ({
    labels: monthTotals.value.map((t) => `${Number(t.periodMonth.slice(5, 7))}月`),
    items: [{ values: monthTotals.value.map((t) => fen2wan(t.revenue)) }],
  }))
  // 各区域营收对比（月报全期累计，按累计降序）
  const regionChart = computed(() => {
    const sum: Record<string, number> = {}
    for (const r of ov.value?.rows ?? []) {
      const rg = storeRegion(r.storeCode)
      sum[rg] = (sum[rg] ?? 0) + (r.revenue ?? 0)
    }
    const entries = Object.entries(sum).sort((a, b) => b[1] - a[1])
    return {
      labels: entries.map(([k]) => (k === '—' ? k : `${k}区`)),
      items: [{ values: entries.map(([, v]) => fen2wan(v)) }],
    }
  })
  // 新客 vs 复购 双序列（无数据源 → 空序列，CBarChart 空安全）
  const customerChart = computed(() => ({
    labels: [] as string[],
    items: [{ values: [] as number[] }, { values: [] as number[] }],
  }))

  // 门店营收排行（月报全期累计 Top5）
  const storeRanks = computed<StoreRank[]>(() => {
    const sum = new Map<string, number>()
    for (const r of ov.value?.rows ?? []) sum.set(r.storeCode, (sum.get(r.storeCode) ?? 0) + (r.revenue ?? 0))
    return [...sum.entries()]
      .map(([code, rev]) => ({
        id: code,
        name: storeName(code),
        region: storeRegion(code),
        revenue: fen2wan(rev),
        growth: null,
        customers: null,
        satisfaction: null,
      }))
      .sort((a, b) => b.revenue - a.revenue)
      .slice(0, 5)
  })

  // 预警：全部由真实月报派生（负毛利 / 月报覆盖缺口）
  const alerts = computed<OverviewAlert[]>(() => {
    const out: OverviewAlert[] = []
    for (const r of ov.value?.rows ?? []) {
      if (r.grossRate != null && Number(r.grossRate) < 0) {
        out.push({
          level: 'HIGH',
          text: `${storeName(r.storeCode)} ${r.periodMonth.slice(0, 7)} 月毛利率 ${(Number(r.grossRate) * 100).toFixed(1)}%，当月成本高于营收`,
          time: r.periodMonth.slice(0, 7),
        })
      }
    }
    const lt = latest.value
    if (lt && storeList.value.length > 0 && lt.storeCount < storeList.value.length) {
      out.push({
        level: 'MED',
        text: `${lt.periodMonth.slice(0, 7)} 月报覆盖不足：${storeList.value.length} 家门店中仅 ${lt.storeCount} 家已出月报`,
        time: lt.periodMonth.slice(0, 7),
      })
    }
    return out
  })

  async function load(force = false) {
    if (loaded.value && !force) return
    loading.value = true
    error.value = ''
    try {
      const [g, s] = await Promise.all([listGroupOverview(), listStores()])
      ov.value = g.data
      storeList.value = s.data || []
      loaded.value = true
    } catch (e) {
      error.value = e instanceof Error ? e.message : '集团经营概览加载失败'
      loaded.value = false
    } finally {
      loading.value = false
    }
  }

  return {
    loaded, loading, error, monthTotals, latest,
    kpis, revenueChart, regionChart, customerChart, storeRanks, alerts, load,
  }
})
