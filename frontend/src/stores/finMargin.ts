/* ============================================================
 * M6-07 毛利报表 store（只读镜像）
 * 毛利 = 已双签划扣确认收入 − 营业成本（耗材+折旧+报损+人工）
 * 汇总口径锚定 useFinanceCoreStore（/finance/ledger + /finance/cost）
 * 项目/大类明细：P5-B99 起切真 /finance/margin/project（成交侧 × BOM 直接耗材分摊，金额「元」）
 * ============================================================ */
import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import { useFinanceCoreStore } from './financeCore'
import { getProjectMargin } from '@/api/finance'
import { shDateStr } from '@/utils/datetime'

export interface MarginRow {
  id: string
  category: string // 项目大类
  itemName: string
  store: string
  revenue: number // 确认收入（成交侧口径，元）
  materialCost: number
  laborCost: number
  orderCount: number
}

const r2 = (v: number) => Math.round(v * 100) / 100

let _id = 0
const nextId = (p: string) => `${p}-${++_id}`

/** 全量宽区间（页面无日期控件）：[2020-01-01, 明日) 上海日界半闭 */
function defaultRange(): { from: string; to: string } {
  return { from: '2020-01-01', to: shDateStr(new Date(Date.now() + 86400000)) }
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
        // P5-B99 切真：项目级毛利 /finance/margin/project（成交侧 × BOM 直接耗材门店级分摊）；KPI 汇总仍锚 financeCore
        const resp = await getProjectMargin(defaultRange())
        rows.value = (resp.data ?? []).map((r) => ({
          id: nextId('MG'),
          category: r.category,
          itemName: r.itemName,
          store: r.store,
          revenue: r2(r.revenue),
          materialCost: r2(r.materialCost),
          laborCost: r2(r.laborCost),
          orderCount: r.orderCount,
        }))
        demo.value = false
        loaded.value = true
      } catch (e) {
        console.error('[finMargin] 项目毛利明细加载失败', e)
        rows.value = []
        demo.value = false
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

  // 大类图表/明细行：项目毛利真实行按大类聚合（收入/成本/毛利）
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
