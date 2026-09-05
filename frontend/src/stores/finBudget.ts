// ============================================================
// finBudget —— M6-16 预算管控
// 业财一体红线：预算仅做"额度控制 + 实际发生额对比 + 超支预警"，
// 实际发生额只读镜像自 financeCore，预算超额只拦截/提示，不反向触达资金池。
// B5：预算持久化到 finance-service /finance/budgets（年度+科目幂等 upsert，全审计）。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { useFinanceCoreStore } from './financeCore'
import { getBudgets, saveBudgets as apiSaveBudgets } from '@/api/finance'

export type BudgetSubjectId =
  | 'REVENUE' | 'COST' | 'MATERIAL' | 'LABOR' | 'DEPRECIATION' | 'LOSS' | 'MARKETING' | 'RENT'

export interface BudgetSubject {
  id: BudgetSubjectId
  name: string
  /** RF/TK 科目归属，用于与 financeCore 勾稽展示 */
  kind: 'RF' | 'TK' | 'EXP'
  /** 是否计入 KPI 预算/执行总额（明细成本科目由 COST 汇总，不重复计入） */
  rollup: boolean
  budget: number
}

/** 执行率区段：>=100 超支(danger) / 80~100 预警(warning) / <80 正常(success) */
export type ExecTone = 'danger' | 'warning' | 'success'
export const EXEC_PILL: Record<ExecTone, 'danger' | 'warning' | 'success'> = {
  danger: 'danger',
  warning: 'warning',
  success: 'success',
}
export const EXEC_LABEL: Record<ExecTone, string> = {
  danger: '已超支',
  warning: '接近预算',
  success: '执行正常',
}

function rateOf(actual: number, budget: number): number {
  if (budget <= 0) return actual > 0 ? 100 : 0
  return Math.round((actual / budget) * 1000) / 10
}
function toneOf(rate: number): ExecTone {
  if (rate >= 100) return 'danger'
  if (rate >= 80) return 'warning'
  return 'success'
}

/** 空库/服务不可用时回落的默认年度预算（元），与后端默认一致 */
const DEFAULT_BUDGET: Record<BudgetSubjectId, number> = {
  REVENUE: 60000,
  COST: 12000,
  MATERIAL: 2000,
  LABOR: 10000,
  DEPRECIATION: 1500,
  LOSS: 1000,
  MARKETING: 5000,
  RENT: 8000,
}

export const useFinBudgetStore = defineStore('finBudget', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()
  const core = useFinanceCoreStore()

  const subjects = ref<BudgetSubject[]>([
    { id: 'REVENUE', name: '主营业务收入', kind: 'RF', rollup: true, budget: DEFAULT_BUDGET.REVENUE },
    { id: 'COST', name: '主营业务成本', kind: 'TK', rollup: true, budget: DEFAULT_BUDGET.COST },
    { id: 'MATERIAL', name: '耗材成本', kind: 'TK', rollup: false, budget: DEFAULT_BUDGET.MATERIAL },
    { id: 'LABOR', name: '人工分摊', kind: 'TK', rollup: false, budget: DEFAULT_BUDGET.LABOR },
    { id: 'DEPRECIATION', name: '设备折旧', kind: 'TK', rollup: false, budget: DEFAULT_BUDGET.DEPRECIATION },
    { id: 'LOSS', name: '报损', kind: 'TK', rollup: false, budget: DEFAULT_BUDGET.LOSS },
    { id: 'MARKETING', name: '营销费用', kind: 'EXP', rollup: true, budget: DEFAULT_BUDGET.MARKETING },
    { id: 'RENT', name: '房租分摊', kind: 'EXP', rollup: true, budget: DEFAULT_BUDGET.RENT },
  ])

  const budgetYear = ref<number>(new Date().getFullYear())
  const loaded = ref(false)

  /** 实际发生额：从只读镜像核心 store 取，无对应项为 0 */
  const actualMap = computed<Record<BudgetSubjectId, number>>(() => ({
    REVENUE: core.netRevenue,
    COST: core.totalCost,
    MATERIAL: core.materialCost,
    LABOR: core.laborCost,
    DEPRECIATION: core.depreciationCost,
    LOSS: core.lossCost,
    MARKETING: 0,
    RENT: 0,
  }))

  interface Row extends BudgetSubject {
    actual: number
    rate: number
    variance: number // 实际 - 预算（正为超支）
    tone: ExecTone
  }

  const rows = computed<Row[]>(() =>
    subjects.value.map((s) => {
      const actual = actualMap.value[s.id]
      const rate = rateOf(actual, s.budget)
      return {
        ...s,
        actual,
        rate,
        variance: Math.round((actual - s.budget) * 100) / 100,
        tone: toneOf(rate),
      }
    }),
  )

  /** KPI：仅汇总 rollup 科目（收入/成本/营销/房租），避免成本明细重复计入 */
  const totalBudget = computed(() =>
    subjects.value.filter((s) => s.rollup).reduce((sum, s) => sum + s.budget, 0))
  const totalActual = computed(() =>
    rows.value.filter((r) => r.rollup).reduce((sum, r) => sum + r.actual, 0))
  const remaining = computed(() => totalBudget.value - totalActual.value)
  const totalRate = computed(() => rateOf(totalActual.value, totalBudget.value))
  const totalTone = computed<ExecTone>(() => toneOf(totalRate.value))

  /** 超支科目（执行率 >=100%） */
  const overBudget = computed(() => rows.value.filter((r) => r.tone === 'danger'))
  const warningCount = computed(() => rows.value.filter((r) => r.tone === 'warning').length)

  function getBudget(id: BudgetSubjectId) {
    return subjects.value.find((s) => s.id === id)
  }

  function applyBudgets(map: Record<string, number>) {
    for (const s of subjects.value) {
      if (map[s.id] != null) s.budget = Math.max(0, Math.round(Number(map[s.id]) || 0))
    }
  }

  /** 拉取年度预算（finance-service）；失败/空回落内置默认，不阻断页面。 */
  let seeding: Promise<void> | null = null
  function seed(force = false): Promise<void> {
    if (seeding && !force) return seeding
    if (loaded && !force) return Promise.resolve()
    seeding = (async () => {
      try {
        const { data } = await getBudgets(budgetYear.value)
        budgetYear.value = data.year
        const map: Record<string, number> = {}
        for (const row of data.subjects ?? []) map[row.subjectCode] = row.budgetYuan
        applyBudgets(map)
        loaded.value = true
      } catch (e) {
        console.error('[finBudget] 加载年度预算失败，回落默认预算', e)
        applyBudgets(DEFAULT_BUDGET)
      }
    })()
    return seeding
  }
  void seed()

  /** 保存预算（弹层二次确认后调用），需 finance:budget:edit；先乐观更新再持久化，失败回拉。 */
  async function saveBudgets(next: Record<BudgetSubjectId, number>): Promise<boolean> {
    if (!auth.can('finance:budget:edit')) {
      console.warn('[finBudget] 无 finance:budget:edit 权限')
      return false
    }
    const normalized: Record<BudgetSubjectId, number> = {} as Record<BudgetSubjectId, number>
    let changed = 0
    for (const s of subjects.value) {
      const v = Math.max(0, Math.round(Number(next[s.id]) || 0))
      normalized[s.id] = v
      if (s.budget !== v) changed += 1
    }
    const prev = subjects.value.map((s) => ({ id: s.id, budget: s.budget }))
    applyBudgets(normalized)
    try {
      await apiSaveBudgets(normalized as unknown as Record<string, number>, budgetYear.value)
      if (changed > 0) {
        activity.log(auth.user.name, `调整 ${budgetYear.value} 年度预算：共 ${changed} 个科目变更`)
      }
      return true
    } catch (e) {
      console.error('[finBudget] 预算保存失败，回滚本地值', e)
      applyBudgets(Object.fromEntries(prev.map((p) => [p.id, p.budget])))
      return false
    }
  }

  return {
    subjects, rows, budgetYear, loaded,
    totalBudget, totalActual, remaining, totalRate, totalTone,
    overBudget, warningCount,
    getBudget, saveBudgets, seed,
    EXEC_PILL, EXEC_LABEL,
  }
})
