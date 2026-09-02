// ============================================================
// finBudget —— M6-16 预算管控
// 业财一体红线：预算仅做"额度控制 + 实际发生额对比 + 超支预警"，
// 实际发生额只读镜像自 financeCore，预算超额只拦截/提示，不反向触达资金池。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { useFinanceCoreStore } from './financeCore'

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

export const useFinBudgetStore = defineStore('finBudget', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()
  const core = useFinanceCoreStore()

  const subjects = ref<BudgetSubject[]>([
    { id: 'REVENUE', name: '主营业务收入', kind: 'RF', rollup: true, budget: 60000 },
    { id: 'COST', name: '主营业务成本', kind: 'TK', rollup: true, budget: 12000 },
    { id: 'MATERIAL', name: '耗材成本', kind: 'TK', rollup: false, budget: 2000 },
    { id: 'LABOR', name: '人工分摊', kind: 'TK', rollup: false, budget: 10000 },
    { id: 'DEPRECIATION', name: '设备折旧', kind: 'TK', rollup: false, budget: 1500 },
    { id: 'LOSS', name: '报损', kind: 'TK', rollup: false, budget: 1000 },
    { id: 'MARKETING', name: '营销费用', kind: 'EXP', rollup: true, budget: 5000 },
    { id: 'RENT', name: '房租分摊', kind: 'EXP', rollup: true, budget: 8000 },
  ])

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

  /** 保存预算（弹层二次确认后调用），需 finance:budget:edit */
  function saveBudgets(next: Record<BudgetSubjectId, number>): boolean {
    if (!auth.can('finance:budget:edit')) {
      console.warn('[finBudget] 无 finance:budget:edit 权限')
      return false
    }
    let changed = 0
    for (const s of subjects.value) {
      const v = Math.max(0, Math.round(Number(next[s.id]) || 0))
      if (s.budget !== v) {
        s.budget = v
        changed += 1
      }
    }
    if (changed > 0) {
      activity.log(auth.user.name, `调整年度预算：共 ${changed} 个科目变更`)
    }
    return true
  }

  return {
    subjects, rows,
    totalBudget, totalActual, remaining, totalRate, totalTone,
    overBudget, warningCount,
    getBudget, saveBudgets,
    EXEC_PILL, EXEC_LABEL,
  }
})
