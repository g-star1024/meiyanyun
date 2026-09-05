/* ============================================================
 * M6-06 成本分析 store（只读镜像，TK 成本类）
 * 耗材 / 设备折旧 / 报损 / 人工分摊 四大成本，按门店与科目归集
 * B5：权威数据源为 finance-service GET /finance/cost（月×店聚合，Long 分）
 * 耗材/报损由双签工单终审自动落账，折旧/人工由财务手工录入；
 * 后端仅有月×店科目聚合、无逐笔明细，明细行按聚合结果合成展示（诚实降级），
 * 接口不可用且无数据时回落内置演示数据。
 * ============================================================ */
import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import { useFinanceCoreStore } from './financeCore'
import { useStoreContext } from './storeContext'
import { getCosts, type CostAggregate } from '@/api/finance'

export type CostSubject = 'MATERIAL' | 'DEPRECIATION' | 'LOSS' | 'LABOR'

export interface CostRow {
  id: string
  subject: CostSubject
  itemName: string
  store: string
  amount: number
  occurredAt: string
  source: string // 来源镜像（库存/设备/报损/薪酬）
  memo?: string
}

export interface CostByStore {
  store: string
  material: number
  depreciation: number
  loss: number
  labor: number
  total: number
}

const SUBJECT_LABEL: Record<CostSubject, string> = {
  MATERIAL: '耗材成本',
  DEPRECIATION: '设备折旧',
  LOSS: '报损成本',
  LABOR: '人工分摊',
}

const SUBJECT_ORDER: CostSubject[] = ['MATERIAL', 'DEPRECIATION', 'LOSS', 'LABOR']

let _id = 0
const nextId = (p: string) => `${p}-${++_id}`

/** 接口不可用时的离线演示数据（金额「元」） */
function mockRows(): CostRow[] {
  const stores = ['旗舰店', '万象城店', '科技园店']
  const data: Array<[CostSubject, string, number, string, string]> = [
    ['MATERIAL', '玻尿酸原液 10ml', 3200, '库存出库', '治疗室领用'],
    ['MATERIAL', '一次性无菌探头', 1800, '库存出库', '8 月治疗消耗'],
    ['MATERIAL', '医用冷敷贴', 1200, '库存出库', '术后护理领用'],
    ['MATERIAL', '光子冷凝胶', 980, '库存出库', '光电项目消耗'],
    ['MATERIAL', '消毒耗材包', 650, '库存出库', '院感规范'],
    ['DEPRECIATION', '皮秒激光治疗仪', 4200, '设备资产', '原值 50.4 万 / 10 年'],
    ['DEPRECIATION', '热玛吉 FLX', 3800, '设备资产', '原值 45.6 万 / 10 年'],
    ['DEPRECIATION', '光子嫩肤仪', 1600, '设备资产', '原值 19.2 万 / 10 年'],
    ['LOSS', '过期精华液 5 瓶', 1500, '报损单', '效期 2026-07 未先用'],
    ['LOSS', '破损冷冻探头', 2200, '报损单', '操作事故，已追责'],
    ['LABOR', '咨询师人工分摊', 8600, '薪酬镜像', '按业绩 12% 分摊'],
    ['LABOR', '治疗师人工分摊', 6400, '薪酬镜像', '按工时分摊'],
  ]
  return data.map(([subject, itemName, amount, source, memo], i) => ({
    id: nextId('COST'),
    subject,
    itemName,
    store: stores[i % stores.length],
    amount,
    occurredAt: `2026-08-${String(20 - (i % 18)).padStart(2, '0')}`,
    source,
    memo,
  }))
}

/** 分 → 元（保留两位小数） */
const fen2yuan = (fen: number) => Math.round((fen || 0) / 1) / 100

/**
 * 月×店科目聚合 → 合成明细行：
 * 后端 /finance/cost 不提供逐笔明细，每个「月份×门店×科目」聚合为一条展示行，
 * itemName 取科目中文名、source 标注「成本归集」，金额与 KPI/甜甜圈/门店对比完全同源。
 */
function aggregateToRows(list: CostAggregate[], nameOf: (code: string) => string): CostRow[] {
  const out: CostRow[] = []
  const sorted = [...list].sort((a, b) => (a.periodMonth < b.periodMonth ? 1 : -1))
  for (const agg of sorted) {
    const month = (agg.periodMonth || '').slice(0, 7)
    const store = nameOf(agg.storeCode) || agg.storeCode
    for (const subject of SUBJECT_ORDER) {
      const fen = Number(agg[subject.toLowerCase() as 'material' | 'depreciation' | 'loss' | 'labor']) || 0
      if (fen <= 0) continue
      out.push({
        id: `COST-${agg.periodMonth}-${agg.storeCode}-${subject}`,
        subject,
        itemName: SUBJECT_LABEL[subject],
        store,
        amount: fen2yuan(fen),
        occurredAt: month,
        source: '成本归集',
        memo: `${month} 月度${SUBJECT_LABEL[subject]}归集（${store}）`,
      })
    }
  }
  return out
}

export const useFinCostStore = defineStore('finCost', () => {
  const fin = useFinanceCoreStore()
  const ctx = useStoreContext()
  const rows = ref<CostRow[]>([])
  const filterSubject = ref<CostSubject | 'ALL'>('ALL')
  const filterStore = ref<string>('ALL')
  const loaded = ref(false)

  /** 拉取真实成本聚合（finance-service）；失败或空库回落演示数据，不阻断页面 */
  let seeding: Promise<void> | null = null
  function seed(force = false): Promise<void> {
    if (seeding && !force) return seeding
    if (loaded.value && !force) return Promise.resolve()
    seeding = (async () => {
      try {
        await ctx.loadStores()
        const { data } = await getCosts()
        const list = data || []
        const nameOf = (code: string) =>
          ctx.stores.find((s) => s.storeCode === code)?.storeName ?? code
        if (list.length > 0) {
          rows.value = aggregateToRows(list, nameOf)
        } else if (!loaded.value) {
          rows.value = mockRows()
        }
        loaded.value = true
      } catch (e) {
        console.error('[finCost] 加载成本聚合失败，回落演示数据', e)
        if (rows.value.length === 0) rows.value = mockRows()
      }
    })()
    return seeding
  }
  void seed()

  /** 视图 onMounted 兼容入口 */
  function init() {
    return seed()
  }

  const stores = computed(() => Array.from(new Set(rows.value.map((r) => r.store))))

  const filtered = computed(() =>
    rows.value.filter((r) =>
      (filterSubject.value === 'ALL' || r.subject === filterSubject.value) &&
      (filterStore.value === 'ALL' || r.store === filterStore.value),
    ),
  )

  const totalMaterial = computed(() => by(rows.value, 'MATERIAL'))
  const totalDepreciation = computed(() => by(rows.value, 'DEPRECIATION'))
  const totalLoss = computed(() => by(rows.value, 'LOSS'))
  const totalLabor = computed(() => by(rows.value, 'LABOR'))
  const totalCost = computed(() =>
    totalMaterial.value + totalDepreciation.value + totalLoss.value + totalLabor.value,
  )

  // 与 financeCore 镜像口径对齐校验（明细合计 ≈ 核心镜像）
  const mirrorDiff = computed(() => Math.round((totalCost.value - fin.totalCost) * 100) / 100)

  const byStore = computed<CostByStore[]>(() => {
    const map = new Map<string, CostByStore>()
    for (const r of rows.value) {
      if (!map.has(r.store)) {
        map.set(r.store, { store: r.store, material: 0, depreciation: 0, loss: 0, labor: 0, total: 0 })
      }
      const b = map.get(r.store)!
      b[r.subject === 'MATERIAL' ? 'material' : r.subject === 'DEPRECIATION' ? 'depreciation' : r.subject === 'LOSS' ? 'loss' : 'labor'] += r.amount
      b.total += r.amount
    }
    return Array.from(map.values())
      .map((b) => ({
        ...b,
        material: r2(b.material),
        depreciation: r2(b.depreciation),
        loss: r2(b.loss),
        labor: r2(b.labor),
        total: r2(b.total),
      }))
      .sort((a, b) => b.total - a.total)
  })

  function by(list: CostRow[], s: CostSubject) {
    return r2(list.filter((r) => r.subject === s).reduce((sum, r) => sum + r.amount, 0))
  }

  function r2(v: number) {
    return Math.round(v * 100) / 100
  }

  return {
    rows, filtered, stores, byStore,
    filterSubject, filterStore,
    totalMaterial, totalDepreciation, totalLoss, totalLabor, totalCost, mirrorDiff,
    SUBJECT_LABEL, init, seed, loaded,
  }
})
