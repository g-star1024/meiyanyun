import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

// 指标矩阵：多维指标 × 门店，含同环比与热力
export type MetricGroup = 'FINANCE' | 'CUSTOMER' | 'OPERATION' | 'STAFF'
export interface MatrixMetric {
  key: string
  label: string
  group: MetricGroup
  unit: string
  higherBetter: boolean
  /** 目标值（用于达成率热力） */
  target: number
}

export interface MetricCell {
  metricKey: string
  storeId: string
  value: number
  mom: number // 环比 %
  yoy: number // 同比 %
}

export const GROUP_LABEL: Record<MetricGroup, string> = {
  FINANCE: '财务', CUSTOMER: '客户', OPERATION: '运营', STAFF: '人效',
}

export const METRICS: MatrixMetric[] = [
  { key: 'revenue', label: '营收', group: 'FINANCE', unit: '万元', higherBetter: true, target: 600 },
  { key: 'grossMargin', label: '毛利率', group: 'FINANCE', unit: '%', higherBetter: true, target: 55 },
  { key: 'arDays', label: '应收周转', group: 'FINANCE', unit: '天', higherBetter: false, target: 30 },
  { key: 'newCust', label: '新客数', group: 'CUSTOMER', unit: '人', higherBetter: true, target: 200 },
  { key: 'repurchase', label: '复购率', group: 'CUSTOMER', unit: '%', higherBetter: true, target: 45 },
  { key: 'satisfaction', label: '满意度', group: 'CUSTOMER', unit: '%', higherBetter: true, target: 94 },
  { key: 'procedure', label: '治疗人次', group: 'OPERATION', unit: '人次', higherBetter: true, target: 900 },
  { key: 'utilization', label: '资源利用率', group: 'OPERATION', unit: '%', higherBetter: true, target: 75 },
  { key: 'writeoff', label: '核销率', group: 'OPERATION', unit: '%', higherBetter: true, target: 80 },
  { key: 'perCapita', label: '人效', group: 'STAFF', unit: '万/人', higherBetter: true, target: 12 },
  { key: 'staffSat', label: '员工满意度', group: 'STAFF', unit: '%', higherBetter: true, target: 85 },
]

const STORES = [
  { id: 'T01', name: '杭州西湖', region: '华东' },
  { id: 'T02', name: '上海静安', region: '华东' },
  { id: 'T03', name: '北京朝阳', region: '华北' },
  { id: 'T04', name: '广州天河', region: '华南' },
  { id: 'T05', name: '成都高新', region: '西南' },
]

// 模拟数据
function rand(seed: number, min: number, max: number) {
  const x = Math.sin(seed) * 10000
  return min + (x - Math.floor(x)) * (max - min)
}
function buildCells(): MetricCell[] {
  const cells: MetricCell[] = []
  METRICS.forEach((m, mi) => {
    STORES.forEach((s, si) => {
      const base = m.target * rand(mi * 7 + si * 13 + 1, 0.7, 1.2)
      cells.push({
        metricKey: m.key, storeId: s.id, value: Math.round(base * 10) / 10,
        mom: Math.round(rand(mi * 3 + si * 5, -8, 20) * 10) / 10,
        yoy: Math.round(rand(mi * 5 + si * 9, -5, 30) * 10) / 10,
      })
    })
  })
  return cells
}

export const useM1MatrixStore = defineStore('m1Matrix', () => {
  const seeded = ref(false)
  const stores = ref(STORES)
  const cells = ref<MetricCell[]>([])
  const activeGroup = ref<MetricGroup | 'ALL'>('ALL')
  const period = ref('2026-08')
  const selectedCell = ref<{ metricKey: string; storeId: string } | null>(null)

  function seed() { if (!seeded.value) { cells.value = buildCells(); seeded.value = true } }

  const visibleMetrics = computed(() =>
    activeGroup.value === 'ALL' ? METRICS : METRICS.filter((m) => m.group === activeGroup.value))

  function cell(metricKey: string, storeId: string) {
    return cells.value.find((c) => c.metricKey === metricKey && c.storeId === storeId)
  }
  function metric(key: string) { return METRICS.find((m) => m.key === key)! }
  function store(id: string) { return stores.value.find((s) => s.id === id)! }

  // 热力：基于达成率（考虑方向）返回 0-1 强度
  function heat(metricKey: string, storeId: string): number {
    const m = metric(metricKey)
    const c = cell(metricKey, storeId)
    if (!c || !m.target) return 0
    const ratio = c.value / m.target
    const score = m.higherBetter ? ratio : 2 - ratio // 越低越好：value/target 越小越好
    return Math.max(0, Math.min(1, (score - 0.65) / 0.6)) // 0.65~1.25 映射 0~1，拉开层次
  }

  const periods = ['2026-06', '2026-07', '2026-08']
  const selectedDetail = computed(() => {
    if (!selectedCell.value) return null
    const c = cell(selectedCell.value.metricKey, selectedCell.value.storeId)
    if (!c) return null
    return { cell: c, metric: metric(c.metricKey), store: store(c.storeId) }
  })

  function select(metricKey: string, storeId: string) {
    if (selectedCell.value?.metricKey === metricKey && selectedCell.value?.storeId === storeId)
      selectedCell.value = null
    else selectedCell.value = { metricKey, storeId }
  }

  return {
    stores, cells, activeGroup, period, selectedCell, periods,
    seed, visibleMetrics, cell, metric, store, heat, selectedDetail, select,
  }
})
