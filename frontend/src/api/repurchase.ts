// ============================================================
// M4-18 复购回访 API（对接 txn-service 复购/资产转移单据域：/api/txn/repurchase）
//
// 红线（后端强制，前端同契约）：
// ① 知情同意书 consentAck=false 不得创建（后端 400 中文）；
// ② 三方双签 = 客户确认 + 经办 + 店长，三方不得同一人；
// ③ 资产转移完成时后端同事务搬移来源卡余额(分)/次数到目标卡（账实校验）；
// ④ 全部动作落 append-only 审计链（REPURCHASE/CREATE|TRIPLE_SIGN|REJECT）。
// 状态机：待签核 → 已完成 / 已拒绝（终态）。
// 权限：读 followup:view / 建 followup:create / 签核 followup:edit；门店由后端 JWT 锁定。
// ============================================================
import client from './client'

export type RepurchaseStatus = '待签核' | '已完成' | '已拒绝'
export type RepurchaseBizType = '复购' | '资产转移'

/** 复购/资产转移单据读模型（字段与后端 Repurchase 实体同名驼峰）。 */
export interface RepurchaseDTO {
  repurchaseNo: string
  customerId: string
  storeCode: string
  /** 业务类型：复购 | 资产转移 */
  bizType: string
  targetProject: string | null
  fromCardNo: string | null
  toCardNo: string | null
  transferTimes: number | null
  /** 转移金额：bigint，单位「分」（展示需 /100 转元） */
  transferAmount: number | null
  consentAck: boolean
  consentText: string | null
  status: RepurchaseStatus | string
  sign1: string | null
  sign1Role: string | null
  signedAt1: string | null
  sign2: string | null
  sign2Role: string | null
  signedAt2: string | null
  sign3: string | null
  sign3Role: string | null
  signedAt3: string | null
  note: string | null
  createdAt: string
}

export interface CreateRepurchaseCmd {
  customerId: string
  storeCode: string
  /** 复购 | 资产转移 */
  bizType: string
  targetProject?: string
  /** 资产转移必填：来源卡/目标卡卡号 */
  fromCardNo?: string
  toCardNo?: string
  transferTimes?: number
  /** 转移金额，单位「分」 */
  transferAmount?: number
  /** 知情同意硬前置：必须 true */
  consentAck: boolean
  consentText?: string
  note?: string
}

export interface TripleSignCmd {
  /** 客户确认签名（姓名） */
  sign1: string
  sign1Role?: string
  /** 经办签名（咨询师/前台） */
  sign2: string
  sign2Role?: string
  /** 店长签名 */
  sign3: string
  sign3Role?: string
  /** true=拒签（仅记客户一签，单据置「已拒绝」） */
  reject?: boolean
}

/** 单据列表（本店数据域，按创建时间倒序）。 */
export const listRepurchase = () =>
  client.get<RepurchaseDTO[]>('/txn/repurchase')

/** 单据详情（跨店/不存在统一 404，不泄露存在性）。 */
export const getRepurchase = (no: string) =>
  client.get<RepurchaseDTO>(`/txn/repurchase/${no}`)

/** 新建复购/资产转移单（consentAck 必须为 true；资产转移须带 from/toCardNo）。 */
export const createRepurchase = (cmd: CreateRepurchaseCmd) =>
  client.post<RepurchaseDTO>('/txn/repurchase', cmd)

/** 三方双签：齐全且互异 → 已完成（资产转移同事务搬卡）；reject=true → 已拒绝。 */
export const signRepurchase = (no: string, cmd: TripleSignCmd) =>
  client.post<RepurchaseDTO>(`/txn/repurchase/${no}/sign`, cmd)
