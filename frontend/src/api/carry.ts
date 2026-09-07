// ============================================================
// Carry API（对接 finance-service，B11 月结成本结转域）
// 金额一律 Long「分」（后端契约），月份契约 yyyy-MM-01。
// 资金红线：结转只写成本镜像（fund_entry TK-DEPRECIATION/TK-LABOR OUT、
//          source=SYSTEM、channel=null），绝不生成实付渠道分录。
// 权限：读走类级 finance:view；规则/资产维护与结转执行 finance:cost:edit。
// ============================================================
import client from './client'

export type CarryCostTypeDTO = 'DEPRECIATION' | 'LABOR'
export type CarryCalcModeDTO = 'ASSET' | 'BASE_SALARY' | 'COMMISSION' | 'FIXED'
export type AssetStatusDTO = 'IN_USE' | 'DISPOSED'

/** 结转规则（实体字段直序列化；storeCode 空 = 全门店通用；FIXED 须 fixedAmount 正数分） */
export interface CostCarryRuleDTO {
  ruleId: string
  ruleName: string
  costType: CarryCostTypeDTO
  calcMode: CarryCalcModeDTO
  /** 固定额（分）；仅 calcMode=FIXED 使用 */
  fixedAmount: number | null
  /** 适用门店；空 = 全门店通用 */
  storeCode: string | null
  enabled: boolean
  /** 月结封账前是否自动执行该规则 */
  runOnClose: boolean
  remark?: string | null
  createdBy?: string | null
  createdAt?: string | null
  updatedBy?: string | null
  updatedAt?: string | null
}

/** 设备资产台账（直线法月折旧；DISPOSED 后处置月起停折） */
export interface FinAssetDTO {
  assetId: string
  assetName: string
  storeCode: string
  /** 原值（分） */
  originalValue: number
  /** 残值率（百分比整数，5 = 5%） */
  salvageRate: number
  /** 折旧月限 */
  usefulMonths: number
  /** 起折月 yyyy-MM-01 */
  startMonth: string
  status: AssetStatusDTO
  createdBy?: string | null
  createdAt?: string | null
  updatedBy?: string | null
  updatedAt?: string | null
}

/** 测算明细行（dry-run，不落库；金额分） */
export interface CarryPreviewDetailDTO {
  ruleId: string
  ruleName: string
  costType: CarryCostTypeDTO
  calcMode: CarryCalcModeDTO
  storeCode: string
  storeName: string
  amountFen: number
  /** 取数说明（如「3 台设备月折旧合计」） */
  basis: string
  /** 本月是否已结转（幂等键命中） */
  executed: boolean
}

/** 测算本月结转结果 */
export interface CarryPreviewDTO {
  month: string
  details: CarryPreviewDetailDTO[]
  totalFen: number
  executedFen: number
  pendingFen: number
  pendingCount: number
  executedCount: number
}

/** 执行/重算结转的逐行结果（金额分；skipped 行带 reason） */
export interface CarryRunItemDTO {
  ruleId?: string
  ruleName?: string
  storeCode?: string
  costType?: CarryCostTypeDTO
  amountFen?: number
  reason?: string
  duplicated?: boolean
  closed?: boolean
  [key: string]: unknown
}

/** 执行结转结果 */
export interface CarryRunDTO {
  month: string
  recalc: boolean
  posted: CarryRunItemDTO[]
  skipped: CarryRunItemDTO[]
  postedCount: number
  skippedCount: number
  postedFen: number
  message: string
}

/** 未结转提示（封账页「本月 N 项结转未执行」数据源） */
export interface CarryPendingDTO {
  month: string
  storeCode: string | null
  pendingCount: number
  pendingFen: number
}

/** 新建结转规则入参（fixedAmount=分，storeCode 空=全门店） */
export interface CreateCarryRuleCmd {
  ruleName: string
  costType: CarryCostTypeDTO
  calcMode: CarryCalcModeDTO
  fixedAmount?: number | null
  storeCode?: string | null
  enabled?: boolean
  runOnClose?: boolean
  remark?: string | null
}

/** 更新规则入参（字段均可缺省，缺省不改；停用不追溯已结转期间） */
export interface UpdateCarryRuleCmd {
  ruleName?: string
  costType?: CarryCostTypeDTO
  calcMode?: CarryCalcModeDTO
  fixedAmount?: number | null
  storeCode?: string | null
  enabled?: boolean
  runOnClose?: boolean
  remark?: string | null
}

/** 新增设备资产入参（originalValue=分，startMonth=yyyy-MM-01） */
export interface CreateAssetCmd {
  assetName: string
  storeCode: string
  originalValue: number
  salvageRate: number
  usefulMonths: number
  startMonth: string
}

// -------------------- 结转规则 --------------------

export const listCarryRules = () =>
  client.get<CostCarryRuleDTO[]>('/finance/carry-rules')

export const createCarryRule = (cmd: CreateCarryRuleCmd) =>
  client.post<CostCarryRuleDTO>('/finance/carry-rules', cmd)

export const updateCarryRule = (ruleId: string, cmd: UpdateCarryRuleCmd) =>
  client.post<CostCarryRuleDTO>(`/finance/carry-rules/${ruleId}`, cmd)

// -------------------- 月度结转：测算 / 执行 / 未结转提示 --------------------

/** 测算本月结转（dry-run，不落库）；month=yyyy-MM-01 */
export const previewCarry = (month: string) =>
  client.post<CarryPreviewDTO>('/finance/carry/preview', null, { params: { month } })

/** 执行结转；recalc=true「重算本月」先删 SYSTEM 结转行再跑（已封账月 422） */
export const runCarry = (month: string, recalc = false) =>
  client.post<CarryRunDTO>('/finance/carry/run', null, { params: { month, recalc } })

/** 未结转提示：run_on_close 启用规则中本月未结转项数/金额 */
export const getCarryPending = (month: string, storeCode?: string) =>
  client.get<CarryPendingDTO>('/finance/carry/pending', { params: { month, storeCode } })

// -------------------- 设备资产台账 --------------------

export const listAssets = (storeCode?: string) =>
  client.get<FinAssetDTO[]>('/finance/assets', { params: { storeCode } })

export const createAsset = (cmd: CreateAssetCmd) =>
  client.post<FinAssetDTO>('/finance/assets', cmd)

/** 资产处置（IN_USE → DISPOSED，处置月起停折） */
export const disposeAsset = (assetId: string) =>
  client.post<FinAssetDTO>(`/finance/assets/${assetId}/dispose`)
