// ============================================================
// Commission / Comp API（对接 finance-service，B9 薪酬提成域）
// 金额一律 Long「分」（后端契约），rate 为万分位（600 = 6%），月份契约 yyyy-MM-01。
// 红线：提成只写 commission_record 成本镜像，绝不在 fund_entry 动账；
//       PAID 仅镜像外部薪酬系统回传状态。
// 权限：读 finance:commission:view；写 finance:commission:edit；
//       审批/驳回/发放登记 finance:commission:approve；
//       estimate 额外放行咨询业务人员（consult:create/edit/view，方法级 OR）。
// ============================================================
import client from './client'

export type CommissionStatusDTO = 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'PAID' | 'REJECTED'
export type CommissionBaseDTO = 'ORDER' | 'WRITEOFF' | 'RECHARGE'
export type CommRoleDTO = 'CONSULTANT' | 'DOCTOR' | 'BEAUTICIAN' | 'ALL'

/** 规则阶梯（入参/实体 tiersJson 反序列化均为此结构；min=分，rate=万分位） */
export interface RuleTierDTO {
  min: number
  rate: number
  label?: string | null
}

/** 提成规则（实体字段直序列化） */
export interface CommissionRuleDTO {
  ruleId: string
  ruleName: string
  base: CommissionBaseDTO
  role: string
  /** 阶梯 JSON 字符串（[{"min":0,"rate":600,"label":"…"}]），使用前 JSON.parse */
  tiersJson: string
  active: boolean
  createdBy?: string | null
  createdAt?: string | null
  updatedBy?: string | null
  updatedAt?: string | null
}

/** 员工薪酬配置（底薪 + 适用提成规则；一人一 ACTIVE，调薪=旧 INACTIVE+新 ACTIVE） */
export interface StaffCompConfigDTO {
  compId: string
  staffId: string
  staffName: string
  storeCode: string | null
  /** 月底薪（分） */
  baseSalary: number
  /** 适用提成规则（可空：无提成仅底薪） */
  commissionRuleId: string | null
  /** 生效月份 yyyy-MM-01（不追溯历史期间） */
  effectiveMonth: string
  status: 'ACTIVE' | 'INACTIVE'
  createdBy?: string | null
  createdAt?: string | null
  updatedBy?: string | null
  updatedAt?: string | null
}

/** 月度提成单（实体字段直序列化；金额均为「分」） */
export interface CommissionRecordDTO {
  recordId: string
  /** 归属月份 yyyy-MM-01 */
  period: string
  staffId: string
  staffName: string
  storeCode: string | null
  ruleId: string | null
  /** 规则名快照（生成时规则名） */
  ruleName: string | null
  /** 业绩基数（分，口径由规则 base 决定） */
  baseAmount: number
  orderCount: number
  /** 阶梯试算快照 JSON 字符串，使用前 JSON.parse */
  tiersJson: string | null
  /** 提成合计（分） */
  commission: number
  status: CommissionStatusDTO
  remark?: string | null
  approver?: string | null
  approvedAt?: string | null
  paidAt?: string | null
  createdAt?: string | null
}

/** 本单提成预估试算结果（不落库；segments 金额分、rate 万分位） */
export interface CommissionEstimateDTO {
  staffId: string
  amount: number
  /** 是否已配置生效薪酬+规则；false 时不展示预估 */
  configured: boolean
  staffName?: string | null
  ruleId?: string | null
  ruleName?: string | null
  commission: number
  segments: Array<{ label: string; min: number; amount: number; rate: number; commission: number }>
}

/** 保存员工薪酬配置入参（baseSalary=分，effectiveMonth=yyyy-MM-01） */
export interface SaveCompConfigCmd {
  staffId: string
  staffName: string
  storeCode?: string | null
  baseSalary: number
  commissionRuleId?: string | null
  effectiveMonth: string
}

/** 新建提成规则入参 */
export interface CreateRuleCmd {
  name: string
  base: CommissionBaseDTO
  role: string
  tiers: RuleTierDTO[]
}

/** 更新规则入参（字段均可缺省，缺省不改） */
export interface UpdateRuleCmd {
  name?: string
  active?: boolean
  tiers?: RuleTierDTO[]
}

// -------------------- 员工薪酬配置 --------------------

export const listCompConfigs = (storeCode?: string) =>
  client.get<StaffCompConfigDTO[]>('/finance/comp-configs', { params: { storeCode } })

export const saveCompConfig = (cmd: SaveCompConfigCmd) =>
  client.post<StaffCompConfigDTO>('/finance/comp-configs', cmd)

// -------------------- 提成规则 --------------------

export const listCommissionRules = () =>
  client.get<CommissionRuleDTO[]>('/finance/commission-rules')

export const createCommissionRule = (cmd: CreateRuleCmd) =>
  client.post<CommissionRuleDTO>('/finance/commission-rules', cmd)

export const updateCommissionRule = (ruleId: string, cmd: UpdateRuleCmd) =>
  client.post<CommissionRuleDTO>(`/finance/commission-rules/${ruleId}`, cmd)

// -------------------- 月度提成单 --------------------

/** 按月生成试算单（幂等；同月同人重算回 DRAFT，PAID 已发放单锁定不重算）。period=yyyy-MM-01 */
export const generateCommission = (period: string) =>
  client.post<CommissionRecordDTO[]>('/finance/commission/generate', null, { params: { period } })

export const listCommission = (period: string, storeCode?: string) =>
  client.get<CommissionRecordDTO[]>('/finance/commission', { params: { period, storeCode } })

export const submitCommission = (id: string) =>
  client.post<CommissionRecordDTO>(`/finance/commission/${id}/submit`)

export const approveCommission = (id: string) =>
  client.post<CommissionRecordDTO>(`/finance/commission/${id}/approve`)

export const rejectCommission = (id: string, reason?: string) =>
  client.post<CommissionRecordDTO>(`/finance/commission/${id}/reject`, { reason: reason || null })

export const markCommissionPaid = (id: string) =>
  client.post<CommissionRecordDTO>(`/finance/commission/${id}/mark-paid`)

// -------------------- 本单预估（咨询页） --------------------

/** staffId=接诊咨询员工号；amountFen=本单金额（分）；未配置 configured=false 时调用方不展示 */
export const estimateCommission = (staffId: string, amountFen: number) =>
  client.post<CommissionEstimateDTO>('/finance/commission/estimate', null, {
    params: { staffId, amount: amountFen },
  })

// -------------------- 薪酬表导出（CSV） --------------------

/** 从 Content-Disposition 解析后端文件名（filename*=UTF-8'' 优先），取不到用兜底名 */
function downloadCsv(resp: { data?: BlobPart; headers?: unknown }, fallback: string) {
  const h = (resp?.headers ?? {}) as { get?(k: string): unknown } & Record<string, unknown>
  const raw = typeof h.get === 'function' ? h.get('content-disposition') : h['content-disposition']
  const disposition = String(raw ?? '')
  let filename = fallback
  const star = /filename\*=UTF-8''([^;]+)/i.exec(disposition)
  if (star && star[1]) {
    try {
      filename = decodeURIComponent(star[1])
    } catch {
      filename = star[1]
    }
  }
  const blob = new Blob([resp.data as BlobPart], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

/** 薪酬表导出（UTF-8 BOM + 中文表头 + 金额「元」，浏览器直接触发下载） */
export const exportCommissionCsv = async (params: { period: string; storeCode?: string }) => {
  const resp = await client.get('/finance/export/commission.csv', { params, responseType: 'blob' })
  downloadCsv(resp, `薪酬表-${params.period.slice(0, 7)}.csv`)
}
