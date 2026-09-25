// 指标矩阵（M1 集团屏 /m1-matrix）——B49 卡4 接真（铁律 -1-D 跨店例外域，只读）。
// 数据源：GET /api/finance/group-overview（P5-B97 起读 monthly_store_metrics 月度事实表）+ GET /api/stores（门店名录）。
// 口径：11 项指标全保留（target 为管理基准值，非 mock）；有源 5 项：营收/毛利率/新客数/复购率(=当月复购人数/活跃客户)/治疗人次，
//   其余 6 项及全部 mom/yoy 暂无月度聚合数据源 → null 显「—」；
//   periods=有月报的真实月份，默认选中「已出月报门店数最多的月份」（并列取最新，随数据域而定）。
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { listGroupOverview, type GroupOverviewView } from '@/api/finance'
import { listStores, type Store } from '@/api/org'

export type MetricGroup = 'FINANCE' | 'CUSTOMER' | 'OPERATION' | 'STAFF'
export interface MatrixMetric {
  key: string
  label: string
  group: MetricGroup
  unit: string
  higherBetter: boolean
  /** 目标值（用于达成率热力，管理基准非 mock） */
  target: number
}

export interface MetricCell {
  metricKey: string
  storeId: string
  value: number | null // 无数据源 → null 显「—」
  mom: number | null // 环比 %：月报月份不相邻不可比 → null
  yoy: number | null // 同比 %：无去年同期数据 → null
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

interface MatrixStore { id: string; name: string; region: string }

export const useM1MatrixStore = defineStore('m1Matrix', () => {
  const ov = ref<GroupOverviewView | null>(null)
  const storeList = ref<Store[]>([])
  const loaded = ref(false)
  const loading = ref(false)
  const error = ref('')
  const activeGroup = ref<MetricGroup | 'ALL'>('ALL')
  const period = ref('')
  const selectedCell = ref<{ metricKey: string; storeId: string } | null>(null)

  const stores = computed<MatrixStore[]>(() =>
    storeList.value.map((s) => ({ id: s.storeCode, name: s.storeName, region: s.region ?? '—' })))

  // 有月报的真实月份（yyyy-MM 升序）
  const periods = computed(() => (ov.value?.months ?? []).map((m) => m.slice(0, 7)))

  // 默认期：已出月报门店数最多的月份（并列取最新）
  const defaultPeriod = computed(() => {
    let best = ''
    let bestN = -1
    for (const t of ov.value?.monthTotals ?? []) {
      if (t.storeCount >= bestN) { bestN = t.storeCount; best = t.periodMonth.slice(0, 7) }
    }
    return best
  })

  // 当前期单元格：有源 5 项填真（营收/毛利率/新客数/复购率/治疗人次），其余指标 null；mom/yoy 均 null
  const cells = computed<MetricCell[]>(() => {
    const rows = ov.value?.rows ?? []
    const p = period.value
    const out: MetricCell[] = []
    for (const m of METRICS) {
      for (const s of stores.value) {
        const r = rows.find((x) => x.storeCode === s.id && x.periodMonth.slice(0, 7) === p)
        let v: number | null = null
        if (r) {
          if (m.key === 'revenue' && r.revenue != null) v = Math.round((r.revenue / 1e6) * 10) / 10 // 分 → 万元
          else if (m.key === 'grossMargin' && r.grossRate != null) v = Math.round(Number(r.grossRate) * 1000) / 10 // 小数 → %
          else if (m.key === 'newCust') v = r.newCustomers
          else if (m.key === 'repurchase' && r.repurchaseCount != null && r.activeCustomers != null && r.activeCustomers > 0) {
            v = Math.round((r.repurchaseCount / r.activeCustomers) * 1000) / 10 // 复购人数/活跃客户 → %
          } else if (m.key === 'procedure') v = r.treatmentCount
        }
        out.push({ metricKey: m.key, storeId: s.id, value: v, mom: null, yoy: null })
      }
    }
    return out
  })

  const visibleMetrics = computed(() =>
    activeGroup.value === 'ALL' ? METRICS : METRICS.filter((m) => m.group === activeGroup.value))

  function cell(metricKey: string, storeId: string) {
    return cells.value.find((c) => c.metricKey === metricKey && c.storeId === storeId)
  }
  function metric(key: string) { return METRICS.find((m) => m.key === key)! }
  function store(id: string) { return stores.value.find((s) => s.id === id) }

  // 热力：基于达成率（考虑方向）返回 0-1 强度；无数据（null）→ 0（淡底色不误导）
  function heat(metricKey: string, storeId: string): number {
    const m = metric(metricKey)
    const c = cell(metricKey, storeId)
    if (!c || c.value == null || !m.target) return 0
    const ratio = c.value / m.target
    const score = m.higherBetter ? ratio : 2 - ratio // 越低越好：value/target 越小越好
    return Math.max(0, Math.min(1, (score - 0.65) / 0.6)) // 0.65~1.25 映射 0~1，拉开层次
  }

  const selectedDetail = computed(() => {
    if (!selectedCell.value) return null
    const c = cell(selectedCell.value.metricKey, selectedCell.value.storeId)
    const st = c ? store(c.storeId) : undefined
    if (!c || !st) return null
    return { cell: c, metric: metric(c.metricKey), store: st }
  })

  function select(metricKey: string, storeId: string) {
    if (selectedCell.value?.metricKey === metricKey && selectedCell.value?.storeId === storeId)
      selectedCell.value = null
    else selectedCell.value = { metricKey, storeId }
  }

  async function load(force = false) {
    if (loaded.value && !force) return
    loading.value = true
    error.value = ''
    try {
      const [g, s] = await Promise.all([listGroupOverview(), listStores()])
      ov.value = g.data
      storeList.value = s.data || []
      if (!period.value || !periods.value.includes(period.value)) period.value = defaultPeriod.value
      loaded.value = true
    } catch (e) {
      error.value = e instanceof Error ? e.message : '指标矩阵加载失败'
      loaded.value = false
    } finally {
      loading.value = false
    }
  }

  return {
    stores, cells, activeGroup, period, selectedCell, periods, loaded, loading, error,
    load, visibleMetrics, cell, metric, store, heat, selectedDetail, select,
  }
})
