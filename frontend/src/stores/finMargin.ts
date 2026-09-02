/* ============================================================
 * M6-07 毛利报表 store（只读镜像）
 * 毛利 = 已双签划扣确认收入 − 营业成本（TK），按项目/门店拆分
 * 汇总与 useFinanceCoreStore 对齐
 * ============================================================ */
import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import { useFinanceCoreStore } from './financeCore'

export interface MarginRow {
  id: string
  category: string // 项目大类
  itemName: string
  store: string
  revenue: number // 确认收入（已双签划扣口径）
  materialCost: number
  laborCost: number
  orderCount: number
}

let _id = 0
const nextId = (p: string) => `${p}-${++_id}`

function seed(): MarginRow[] {
  const stores = ['旗舰店', '万象城店', '科技园店']
  const data: Array<[string, string, number, number, number, number]> = [
    ['光电美容', '皮秒祛斑', 48000, 4200, 8600, 32],
    ['光电美容', '热玛吉紧致', 62000, 3800, 9400, 21],
    ['光电美容', '光子嫩肤', 28000, 1800, 5200, 45],
    ['注射美容', '玻尿酸填充', 56000, 18600, 7200, 38],
    ['注射美容', '肉毒素除皱', 34000, 9800, 4800, 41],
    ['皮肤管理', '补水修护疗程', 18000, 3200, 3600, 52],
    ['皮肤管理', '果酸焕肤', 12000, 1500, 2400, 30],
    ['身体护理', '瘦身塑形', 22000, 2600, 4200, 18],
  ]
  return data.map(([category, itemName, revenue, materialCost, laborCost, orderCount], i) => ({
    id: nextId('MG'),
    category,
    itemName,
    store: stores[i % stores.length],
    revenue,
    materialCost,
    laborCost,
    orderCount,
  }))
}

export const useFinMarginStore = defineStore('finMargin', () => {
  const fin = useFinanceCoreStore()
  const rows = ref<MarginRow[]>([])
  const filterStore = ref<string>('ALL')
  const filterCategory = ref<string>('ALL')
  const _seeded = ref(false)

  function init() {
    if (_seeded.value) return
    rows.value = seed()
    _seeded.value = true
  }

  const stores = computed(() => Array.from(new Set(rows.value.map((r) => r.store))))
  const categories = computed(() => Array.from(new Set(rows.value.map((r) => r.category))))

  const filtered = computed(() =>
    rows.value.filter((r) =>
      (filterStore.value === 'ALL' || r.store === filterStore.value) &&
      (filterCategory.value === 'ALL' || r.category === filterCategory.value),
    ),
  )

  const totalRevenue = computed(() => filtered.value.reduce((s, r) => s + r.revenue, 0))
  const totalMaterial = computed(() => filtered.value.reduce((s, r) => s + r.materialCost, 0))
  const totalLabor = computed(() => filtered.value.reduce((s, r) => s + r.laborCost, 0))
  const totalCost = computed(() => totalMaterial.value + totalLabor.value)
  const totalGross = computed(() => totalRevenue.value - totalCost.value)
  const grossRate = computed(() => totalRevenue.value ? Math.round((totalGross.value / totalRevenue.value) * 1000) / 10 : 0)

  // 核心镜像口径（含折旧+报损的完整营业成本）
  const mirrorGross = computed(() => fin.grossProfit)
  const mirrorRate = computed(() => fin.grossRate)

  const byCategory = computed(() => {
    const map = new Map<string, { category: string; revenue: number; cost: number; gross: number }>()
    for (const r of filtered.value) {
      const cost = r.materialCost + r.laborCost
      const cur = map.get(r.category) ?? { category: r.category, revenue: 0, cost: 0, gross: 0 }
      cur.revenue += r.revenue
      cur.cost += cost
      cur.gross += r.revenue - cost
      map.set(r.category, cur)
    }
    return Array.from(map.values()).map((x) => ({ ...x, rate: x.revenue ? Math.round((x.gross / x.revenue) * 1000) / 10 : 0 }))
  })

  return {
    rows, filtered, stores, categories, byCategory,
    filterStore, filterCategory,
    totalRevenue, totalMaterial, totalLabor, totalCost, totalGross, grossRate,
    mirrorGross, mirrorRate, init,
  }
})
