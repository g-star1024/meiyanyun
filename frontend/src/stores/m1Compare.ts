import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

// 门店对标：选择多家门店进行多维度对比
export interface CompareMetric {
  key: string
  label: string
  unit: string
  higherBetter: boolean
  weight: number
  /** 行业基准值 */
  benchmark: number
}
export interface StoreMetricValue {
  storeId: string
  metricKey: string
  value: number
}

export const COMPARE_METRICS: CompareMetric[] = [
  { key: 'revenue', label: '营收', unit: '万元', higherBetter: true, weight: 25, benchmark: 500 },
  { key: 'grossMargin', label: '毛利率', unit: '%', higherBetter: true, weight: 15, benchmark: 55 },
  { key: 'newCust', label: '新客数', unit: '人', higherBetter: true, weight: 15, benchmark: 180 },
  { key: 'repurchase', label: '复购率', unit: '%', higherBetter: true, weight: 15, benchmark: 42 },
  { key: 'satisfaction', label: '满意度', unit: '%', higherBetter: true, weight: 15, benchmark: 92 },
  { key: 'utilization', label: '资源利用率', unit: '%', higherBetter: true, weight: 15, benchmark: 70 },
]

interface Store { id: string; name: string; region: string }
const STORES: Store[] = [
  { id: 'T01', name: '杭州西湖旗舰院', region: '华东' },
  { id: 'T02', name: '上海静安分院', region: '华东' },
  { id: 'T03', name: '北京朝阳分院', region: '华北' },
  { id: 'T04', name: '广州天河分院', region: '华南' },
  { id: 'T05', name: '成都高新分院', region: '西南' },
]

// 模拟值
function rand(seed: number) { const x = Math.sin(seed) * 10000; return x - Math.floor(x) }
const DATA: StoreMetricValue[] = []
STORES.forEach((s, si) => {
  COMPARE_METRICS.forEach((m, mi) => {
    const factor = [1.15, 0.95, 0.78, 1.08, 0.88][si] // 各店水平
    DATA.push({ storeId: s.id, metricKey: m.key, value: Math.round(m.benchmark * factor * (0.9 + rand(si * 9 + mi * 3) * 0.25) * 10) / 10 })
  })
})

export const useM1CompareStore = defineStore('m1Compare', () => {
  const stores = ref(STORES)
  const data = ref(DATA)
  const selectedIds = ref<string[]>(['T01', 'T03', 'T04'])

  function toggle(id: string) {
    const i = selectedIds.value.indexOf(id)
    if (i >= 0) { if (selectedIds.value.length > 1) selectedIds.value.splice(i, 1) }
    else if (selectedIds.value.length < 5) selectedIds.value.push(id)
  }

  function value(storeId: string, metricKey: string) {
    return data.value.find((d) => d.storeId === storeId && d.metricKey === metricKey)?.value ?? 0
  }

  // 单店综合得分（对基准达成率的加权平均，归一到 0-100）
  function score(storeId: string): number {
    let total = 0
    let totalW = 0
    COMPARE_METRICS.forEach((m) => {
      const v = value(storeId, m.key)
      const ratio = m.higherBetter ? v / m.benchmark : m.benchmark / v
      total += Math.min(1.3, ratio) * m.weight
      totalW += m.weight
    })
    return Math.round((total / totalW) * 100)
  }

  const selectedStores = computed(() => stores.value.filter((s) => selectedIds.value.includes(s.id)))
  const ranked = computed(() =>
    [...stores.value]
      .map((s) => ({ ...s, score: score(s.id) }))
      .sort((a, b) => b.score - a.score))

  // 每个指标的最大最小值，用于条形归一
  function metricRange(metricKey: string) {
    const vals = stores.value.map((s) => value(s.id, metricKey))
    return { min: Math.min(...vals), max: Math.max(...vals) }
  }

  // 雷达坐标（综合得分）
  function radarPoints(storeId: string, radius: number, cx: number, cy: number) {
    return COMPARE_METRICS.map((m, i) => {
      const angle = (Math.PI * 2 * i) / COMPARE_METRICS.length - Math.PI / 2
      const ratio = Math.min(1.3, value(storeId, m.key) / m.benchmark)
      const r = (Math.min(1, ratio) / 1) * radius
      return { x: cx + Math.cos(angle) * r, y: cy + Math.sin(angle) * r, label: m.label }
    })
  }

  return {
    stores, data, selectedIds, COMPARE_METRICS,
    toggle, value, score, selectedStores, ranked, metricRange, radarPoints,
  }
})
