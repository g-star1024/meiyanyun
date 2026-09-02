/* ============================================================
 * M6-06 成本分析 store（只读镜像，TK 成本类）
 * 耗材 / 设备折旧 / 报损 / 人工分摊 四大成本，按门店与科目归集
 * 总额与 useFinanceCoreStore 对齐，明细来自 M2 库存/设备/报损镜像
 * ============================================================ */
import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import { useFinanceCoreStore } from './financeCore'

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

let _id = 0
const nextId = (p: string) => `${p}-${++_id}`

function seed(): CostRow[] {
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

export const useFinCostStore = defineStore('finCost', () => {
  const fin = useFinanceCoreStore()
  const rows = ref<CostRow[]>([])
  const filterSubject = ref<CostSubject | 'ALL'>('ALL')
  const filterStore = ref<string>('ALL')
  const _seeded = ref(false)

  function init() {
    if (_seeded.value) return
    rows.value = seed()
    _seeded.value = true
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
  const mirrorDiff = computed(() => totalCost.value - fin.totalCost)

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
    return Array.from(map.values()).sort((a, b) => b.total - a.total)
  })

  function by(list: CostRow[], s: CostSubject) {
    return list.filter((r) => r.subject === s).reduce((sum, r) => sum + r.amount, 0)
  }

  return {
    rows, filtered, stores, byStore,
    filterSubject, filterStore,
    totalMaterial, totalDepreciation, totalLoss, totalLabor, totalCost, mirrorDiff,
    SUBJECT_LABEL, init,
  }
})
