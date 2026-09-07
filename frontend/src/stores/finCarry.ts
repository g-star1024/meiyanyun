// ============================================================
// finCarry —— M6-06 期末结转（B11 月结成本结转自动化）
// 资金红线：结转只写成本镜像（fund_entry TK-DEPRECIATION/TK-LABOR OUT、
// source=SYSTEM、channel=null），绝不生成实付渠道分录；
// 重算先删当月 SYSTEM 结转行再跑，已封账月后端 422 拒绝（封账不可逆）。
// 数据全部来自 finance-service（/finance/carry-rules、/finance/carry*、
// /finance/assets）；适配层负责 分↔元、yyyy-MM-01 月份契约换算。
// 写操作权限/校验/审计由后端四件套兜底，失败抛错由调用方 toast。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import {
  listCarryRules, createCarryRule, updateCarryRule,
  previewCarry as apiPreview, runCarry as apiRun, getCarryPending as apiPending,
  listAssets, createAsset, disposeAsset,
  type CostCarryRuleDTO, type FinAssetDTO,
  type CarryPreviewDTO, type CarryRunDTO, type CarryPendingDTO,
  type CarryCostTypeDTO, type CarryCalcModeDTO, type AssetStatusDTO,
  type CreateCarryRuleCmd, type UpdateCarryRuleCmd, type CreateAssetCmd,
} from '@/api/carry'

export type CarryCostType = CarryCostTypeDTO
export type CarryCalcMode = CarryCalcModeDTO
export type AssetStatus = AssetStatusDTO

/** 结转规则（页面模型；固定额单位「元」） */
export interface CarryRule {
  id: string
  name: string
  costType: CarryCostType
  calcMode: CarryCalcMode
  fixedAmount: number | null
  storeCode: string | null
  enabled: boolean
  runOnClose: boolean
  remark: string
}

/** 设备资产（页面模型；金额「元」；月折旧「元」前端按后端同公式直算展示） */
export interface FinAsset {
  id: string
  name: string
  storeCode: string
  originalValue: number
  salvageRate: number
  usefulMonths: number
  startMonth: string
  status: AssetStatus
  /** 月折旧（元）= round(原值 ×(100-残值率)/100 / 月限)，与后端 FinAsset.monthlyDepreciation() 同式 */
  monthlyDep: number
}

/** 测算明细行（页面模型；金额「元」） */
export interface CarryPreviewLine {
  ruleId: string
  ruleName: string
  costType: CarryCostType
  calcMode: CarryCalcMode
  storeCode: string
  storeName: string
  amount: number
  basis: string
  executed: boolean
}

/** 执行结转逐行结果（页面模型；金额「元」） */
export interface CarryRunLine {
  ruleName?: string
  storeCode?: string
  costType?: CarryCostType
  amount?: number
  reason?: string
  duplicated?: boolean
  closed?: boolean
}

export interface CarryRunResult {
  month: string
  recalc: boolean
  posted: CarryRunLine[]
  skipped: CarryRunLine[]
  postedCount: number
  skippedCount: number
  postedAmount: number
  message: string
}

export const COST_TYPE_LABEL: Record<CarryCostType, string> = {
  DEPRECIATION: '设备折旧',
  LABOR: '人工成本',
}
export const CALC_MODE_LABEL: Record<CarryCalcMode, string> = {
  ASSET: '资产折旧（台账直线法）',
  BASE_SALARY: '底薪合计（薪酬配置）',
  COMMISSION: '已审批提成（薪酬系统镜像）',
  FIXED: '固定额',
}
export const ASSET_STATUS_LABEL: Record<AssetStatus, string> = {
  IN_USE: '在用',
  DISPOSED: '已处置',
}

/** 分 → 元（两位小数） */
function fenToYuan(fen: number | null | undefined): number {
  return Math.round((fen ?? 0) / 100 * 100) / 100
}
/** 元 → 分（四舍五入） */
export function yuanToFen(yuan: number): number {
  return Math.round(yuan * 100)
}

/** 月折旧（元）：与后端 FinAsset.monthlyDepreciation() 同一公式，仅用于列表展示 */
function monthlyDepYuan(originalValueFen: number, salvageRate: number, usefulMonths: number): number {
  const depFen = Math.round(Math.round(originalValueFen * (100 - salvageRate) / 100.0) / (usefulMonths || 1))
  return fenToYuan(depFen)
}

/** 当前月份 yyyy-MM-01（后端契约，按本地时区） */
export function currentMonth(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-01`
}

function toRule(dto: CostCarryRuleDTO): CarryRule {
  return {
    id: dto.ruleId,
    name: dto.ruleName,
    costType: dto.costType,
    calcMode: dto.calcMode,
    fixedAmount: dto.fixedAmount == null ? null : fenToYuan(dto.fixedAmount),
    storeCode: dto.storeCode,
    enabled: dto.enabled,
    runOnClose: dto.runOnClose,
    remark: dto.remark ?? '',
  }
}

function toAsset(dto: FinAssetDTO): FinAsset {
  return {
    id: dto.assetId,
    name: dto.assetName,
    storeCode: dto.storeCode,
    originalValue: fenToYuan(dto.originalValue),
    salvageRate: dto.salvageRate,
    usefulMonths: dto.usefulMonths,
    startMonth: dto.startMonth ? dto.startMonth.slice(0, 7) : '',
    status: dto.status,
    monthlyDep: monthlyDepYuan(dto.originalValue, dto.salvageRate, dto.usefulMonths),
  }
}

export const useFinCarryStore = defineStore('finCarry', () => {
  const rules = ref<CarryRule[]>([])
  const assets = ref<FinAsset[]>([])
  const loading = ref(false)

  const enabledRules = computed(() => rules.value.filter((r) => r.enabled))

  /** 装载结转规则 + 资产台账（资产默认全门店，门店过滤由调用方在视图层收敛） */
  async function seed() {
    loading.value = true
    try {
      const [rulesRes, assetsRes] = await Promise.all([listCarryRules(), listAssets()])
      rules.value = rulesRes.data.map(toRule)
      assets.value = assetsRes.data.map(toAsset)
    } finally {
      loading.value = false
    }
  }

  async function reloadRules() {
    const res = await listCarryRules()
    rules.value = res.data.map(toRule)
  }

  async function reloadAssets(storeCode?: string) {
    const res = await listAssets(storeCode || undefined)
    assets.value = res.data.map(toAsset)
  }

  // -------------------- 规则维护（后台可配，随时调整） --------------------

  async function createRule(cmd: CreateCarryRuleCmd) {
    await createCarryRule(cmd)
    await reloadRules()
  }

  async function updateRule(ruleId: string, cmd: UpdateCarryRuleCmd) {
    await updateCarryRule(ruleId, cmd)
    await reloadRules()
  }

  /** 启停切换（停用不追溯已结转期间） */
  async function toggleRule(ruleId: string, enabled: boolean) {
    await updateCarryRule(ruleId, { enabled: !enabled })
    await reloadRules()
  }

  /** 封账自动结转开关 */
  async function toggleRunOnClose(ruleId: string, runOnClose: boolean) {
    await updateCarryRule(ruleId, { runOnClose: !runOnClose })
    await reloadRules()
  }

  // -------------------- 月度结转：测算 / 执行 / 重算 / 未结转提示 --------------------

  /** 测算本月结转（dry-run，不落库）；month=yyyy-MM-01 */
  async function preview(month: string): Promise<{
    month: string
    lines: CarryPreviewLine[]
    total: number
    executed: number
    pending: number
    pendingCount: number
    executedCount: number
  }> {
    const res = await apiPreview(month)
    const dto: CarryPreviewDTO = res.data
    return {
      month: dto.month,
      lines: dto.details.map((d) => ({
        ruleId: d.ruleId,
        ruleName: d.ruleName,
        costType: d.costType,
        calcMode: d.calcMode,
        storeCode: d.storeCode,
        storeName: d.storeName,
        amount: fenToYuan(d.amountFen),
        basis: d.basis,
        executed: d.executed,
      })),
      total: fenToYuan(dto.totalFen),
      executed: fenToYuan(dto.executedFen),
      pending: fenToYuan(dto.pendingFen),
      pendingCount: dto.pendingCount,
      executedCount: dto.executedCount,
    }
  }

  /** 执行结转 / 重算本月（recalc=true 先删 SYSTEM 行再跑，封账月后端 422） */
  async function run(month: string, recalc: boolean): Promise<CarryRunResult> {
    const res = await apiRun(month, recalc)
    const dto: CarryRunDTO = res.data
    const mapLine = (it: CarryRunDTO['posted'][number]): CarryRunLine => ({
      ruleName: it.ruleName,
      storeCode: it.storeCode,
      costType: it.costType,
      amount: it.amountFen == null ? undefined : fenToYuan(it.amountFen),
      reason: it.reason,
      duplicated: it.duplicated,
      closed: it.closed,
    })
    return {
      month: dto.month,
      recalc: dto.recalc,
      posted: dto.posted.map(mapLine),
      skipped: dto.skipped.map(mapLine),
      postedCount: dto.postedCount,
      skippedCount: dto.skippedCount,
      postedAmount: fenToYuan(dto.postedFen),
      message: dto.message,
    }
  }

  /** 未结转提示（封账页「本月 N 项结转未执行」） */
  async function pending(month: string, storeCode?: string): Promise<CarryPendingDTO> {
    const res = await apiPending(month, storeCode || undefined)
    return res.data
  }

  // -------------------- 设备资产台账 --------------------

  async function addAsset(cmd: CreateAssetCmd) {
    await createAsset(cmd)
    await reloadAssets()
  }

  async function dispose(assetId: string) {
    await disposeAsset(assetId)
    await reloadAssets()
  }

  return {
    rules, assets, loading, enabledRules,
    seed, reloadRules, reloadAssets,
    createRule, updateRule, toggleRule, toggleRunOnClose,
    preview, run, pending,
    addAsset, dispose,
    COST_TYPE_LABEL, CALC_MODE_LABEL, ASSET_STATUS_LABEL,
  }
})
