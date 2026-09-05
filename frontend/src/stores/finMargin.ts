/* ============================================================
 * M6-07 毛利报表 store（只读镜像）
 * 毛利 = 已双签划扣确认收入 − 营业成本（耗材+折旧+报损+人工）
 * 汇总口径锚定 useFinanceCoreStore（/finance/ledger + /finance/cost）
 * 项目/大类明细后端暂无拆分端点，离线演示数据降级（demo 标记）
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

const r2 = (v: number) => Math.round(v * 100) / 100

let _id = 0
const nextId = (p: string) => `${p}-${++_id}`

function mockRows(): MarginRow[] {
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
  const loaded = ref(false)
  const demo = ref(false)

  let seeding: Promise<void> | null = null
  function seed(force = false): Promise<void> {
    if (seeding && !force) return seeding
    if (loaded.value && !force) return Promise.resolve()
    seeding = (async () => {
      try {
        // 后端暂无项目级收入/成本拆分端点：明细行离线演示降级；KPI 汇总锚 financeCore 真实口径
        rows.value = mockRows()
        demo.value = true
        loaded.value = true
      } catch (e) {
        console.error('[finMargin] 毛利明细加载失败，回落离线演示', e)
        if (rows.value.length === 0) rows.value = mockRows()
        demo.value = true
        loaded.value = true
      }
    })()
    return seeding
  }
  void seed()

  function init() {
    return seed()
  }

  const stores = computed(() => Array.from(new Set(rows.value.map((r) => r.store))))
  const categories = computed(() => Array.from(new Set(rows.value.map((r) => r.category))))

  const filtered = computed(() =>
    rows.value.filter((r) =>
      (filterStore.value === 'ALL' || r.store === filterStore.value) &&
      (filterCategory.value === 'ALL' || r.category === filterCategory.value),
    ),
  )

  // KPI 汇总：锚定 financeCore 真实口径（确认收入=已双签划扣；成本=耗材+折旧+报损+人工，来自 /finance/cost）
  const totalRevenue = computed(() => r2(fin.writeoffConfirmed))
  const totalMaterial = computed(() => r2(fin.materialCost))
  const totalLabor = computed(() => r2(fin.laborCost))
  const totalCost = computed(() => r2(fin.totalCost))
  const totalGross = computed(() => r2(fin.grossProfit))
  const grossRate = computed(() => fin.grossRate)

  // 核心镜像口径（与上方 KPI 同源，保留供视图「镜像口径」副标题展示）
  const mirrorGross = computed(() => fin.grossProfit)
  const mirrorRate = computed(() => fin.grossRate)

  // 大类图表/明细行来自离线演示数据（后端无项目级拆分端点），仅作结构演示
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
    mirrorGross, mirrorRate,
    seed, init, loaded, demo,
  }
})
