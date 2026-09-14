// 门店对标（M1 集团屏 /m1-compare）——B49 卡4 接真（铁律 -1-D 跨店例外域，只读）。
// 数据源：GET /api/finance/group-overview（revenue_monthly）+ GET /api/stores（门店名录）。
// 口径：统一取「已出月报门店数最多的月份」（并列取最新，随数据域而定）；对标门店=当月已出月报门店；
//   6 项指标仅「营收/毛利率」有月报数据源，其余 4 项 value null 显「—」（雷达贴地，已入 Backlog）；
//   综合得分仅基于有源指标、权重归一（营收 25 + 毛利率 15 → 62.5% / 37.5%）；benchmark 为管理基准非 mock。
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { listGroupOverview, type GroupOverviewView } from '@/api/finance'
import { listStores, type Store } from '@/api/org'

export interface CompareMetric {
  key: string
  label: string
  unit: string
  higherBetter: boolean
  weight: number
  /** 行业基准值（管理基准，非 mock） */
  benchmark: number
}
export interface StoreMetricValue {
  storeId: string
  metricKey: string
  value: number | null // 无数据源 → null 显「—」
}

export const COMPARE_METRICS: CompareMetric[] = [
  { key: 'revenue', label: '营收', unit: '万元', higherBetter: true, weight: 25, benchmark: 500 },
  { key: 'grossMargin', label: '毛利率', unit: '%', higherBetter: true, weight: 15, benchmark: 55 },
  { key: 'newCust', label: '新客数', unit: '人', higherBetter: true, weight: 15, benchmark: 180 },
  { key: 'repurchase', label: '复购率', unit: '%', higherBetter: true, weight: 15, benchmark: 42 },
  { key: 'satisfaction', label: '满意度', unit: '%', higherBetter: true, weight: 15, benchmark: 92 },
  { key: 'utilization', label: '资源利用率', unit: '%', higherBetter: true, weight: 15, benchmark: 70 },
]

interface CompareStore { id: string; name: string; region: string }

export const useM1CompareStore = defineStore('m1Compare', () => {
  const ov = ref<GroupOverviewView | null>(null)
  const storeList = ref<Store[]>([])
  const loaded = ref(false)
  const loading = ref(false)
  const error = ref('')
  const selectedIds = ref<string[]>([])

  // 统一口径月份：已出月报门店数最多的月份（并列取最新）
  const period = computed(() => {
    let best = ''
    let bestN = -1
    for (const t of ov.value?.monthTotals ?? []) {
      if (t.storeCount >= bestN) { bestN = t.storeCount; best = t.periodMonth.slice(0, 7) }
    }
    return best
  })

  const periodRows = computed(() =>
    (ov.value?.rows ?? []).filter((r) => r.periodMonth.slice(0, 7) === period.value))

  // 对标门店 = 当月已出月报门店（真实编码/店名/大区）
  const stores = computed<CompareStore[]>(() =>
    periodRows.value.map((r) => {
      const st = storeList.value.find((x) => x.storeCode === r.storeCode)
      return { id: r.storeCode, name: st?.storeName ?? r.storeCode, region: st?.region ?? '—' }
    }))

  // 指标值：营收(万元)/毛利率(%) 填真，其余 null
  const data = computed<StoreMetricValue[]>(() => {
    const out: StoreMetricValue[] = []
    for (const r of periodRows.value) {
      for (const m of COMPARE_METRICS) {
        let v: number | null = null
        if (m.key === 'revenue') v = Math.round((r.revenue / 1e6) * 10) / 10 // 分 → 万元
        else if (m.key === 'grossMargin') v = Math.round(Number(r.grossRate) * 1000) / 10 // 小数 → %
        out.push({ storeId: r.storeCode, metricKey: m.key, value: v })
      }
    }
    return out
  })

  function toggle(id: string) {
    const i = selectedIds.value.indexOf(id)
    if (i >= 0) { if (selectedIds.value.length > 1) selectedIds.value.splice(i, 1) }
    else if (selectedIds.value.length < 5) selectedIds.value.push(id)
  }

  function value(storeId: string, metricKey: string): number | null {
    return data.value.find((d) => d.storeId === storeId && d.metricKey === metricKey)?.value ?? null
  }

  // 单店综合得分：仅累有源指标，权重归一到 0-100（无源指标不参与；负比率截 0）
  function score(storeId: string): number {
    let total = 0
    let totalW = 0
    COMPARE_METRICS.forEach((m) => {
      const v = value(storeId, m.key)
      if (v == null) return
      const ratio = Math.max(0, m.higherBetter ? v / m.benchmark : m.benchmark / v)
      total += Math.min(1.3, ratio) * m.weight
      totalW += m.weight
    })
    if (!totalW) return 0
    return Math.round((total / totalW) * 100)
  }

  const selectedStores = computed(() => stores.value.filter((s) => selectedIds.value.includes(s.id)))
  const ranked = computed(() =>
    [...stores.value]
      .map((s) => ({ ...s, score: score(s.id) }))
      .sort((a, b) => b.score - a.score))

  // 每个指标的最大最小值（仅统计有源值），用于条形归一；全无源 → {0,0}
  function metricRange(metricKey: string) {
    const vals = stores.value
      .map((s) => value(s.id, metricKey))
      .filter((v): v is number => v != null)
    if (!vals.length) return { min: 0, max: 0 }
    return { min: Math.min(...vals), max: Math.max(...vals) }
  }

  // 雷达坐标：无源指标贴地（ratio=0），负比率截 0
  function radarPoints(storeId: string, radius: number, cx: number, cy: number) {
    return COMPARE_METRICS.map((m, i) => {
      const angle = (Math.PI * 2 * i) / COMPARE_METRICS.length - Math.PI / 2
      const v = value(storeId, m.key)
      const ratio = v == null ? 0 : Math.min(1.3, Math.max(0, v / m.benchmark))
      const r = Math.min(1, ratio) * radius
      return { x: cx + Math.cos(angle) * r, y: cy + Math.sin(angle) * r, label: m.label }
    })
  }

  async function load(force = false) {
    if (loaded.value && !force) return
    loading.value = true
    error.value = ''
    try {
      const [g, s] = await Promise.all([listGroupOverview(), listStores()])
      ov.value = g.data
      storeList.value = s.data || []
      const ids = stores.value.map((x) => x.id)
      selectedIds.value = selectedIds.value.filter((id) => ids.includes(id))
      if (!selectedIds.value.length) selectedIds.value = ids.slice(0, 3)
      loaded.value = true
    } catch (e) {
      error.value = e instanceof Error ? e.message : '门店对标加载失败'
      loaded.value = false
    } finally {
      loading.value = false
    }
  }

  return {
    stores, data, selectedIds, COMPARE_METRICS, loaded, loading, error, period,
    toggle, value, score, selectedStores, ranked, metricRange, radarPoints, load,
  }
})
