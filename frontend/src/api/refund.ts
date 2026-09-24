// ============================================================
// Refund 聚合 API（逆向交易：退款 + 退卡，共用双签状态机）
// 对齐 business-flows §2.7：kind = ORDER(退款) | CARD(退卡)，共用 /txn 端点。
// 后端实体直接序列化（Jackson camelCase）；金额单位「分」，时间为 ISO 字符串。
// B95：双 Cmd 尾增 contractNo（可空，空＝旧链手动口径）；挂合同后 RF 侧 refundAmt
//   必须等于后端合同口径（已付−违约金，不符 400），CC 侧 fee 由后端自动计算覆写；
//   双 DTO 增 contractNo/contractSnapshot（penaltyAmt 仅 RF），旧单为空回落「—」。
// ============================================================
import client from './client'

export interface SignerDto {
  personId: string
  displayName: string
  role: string
  roleSequence: string
  storeId: string
  medicalLicensed: boolean
}

export interface RefundCmd {
  actor: string
  orderNo?: string
  customer: string
  customerName?: string
  project: string
  channel?: string // ORIGINAL/CASH/TRANSFER
  paidAmt?: number // 分（原实付）
  refundAmt: number // 分
  reason?: string
  feeManualOverride?: boolean
  feeCents?: number
  feeOverrideReason?: string
  /** B95：关联生效中合同号；挂合同后 refundAmt 须等于后端口径（已付−违约金） */
  contractNo?: string
}

export interface CardCancelCmd {
  actor: string
  cardNo: string
  customer: string
  customerName?: string
  cardItem: string
  channel?: string
  balance: number // 分（卡内可退余额基数）
  remainTimes?: number
  medical?: boolean
  feeManualOverride?: boolean
  feeCents?: number
  feeOverrideReason?: string
  /** B95：关联生效中合同号；挂合同后违约金（fee）由后端按合同口径自动计算 */
  contractNo?: string
}

export interface SignCmd {
  actor: string
  signer1: SignerDto
  signer2?: SignerDto
  signer3?: SignerDto
}

/** 退款单实体 DTO（txn_refund 表行）。金额单位「分」。 */
export interface RefundDTO {
  txnNo: string
  orderNo: string | null
  customer: string
  customerName: string | null
  project: string
  channel: string | null
  paidAmt: number | null
  refundAmt: number
  reason: string | null
  status: string // PENDING_REVIEW/PENDING_FINANCE/REFUNDED/REJECTED
  applicant: string | null
  reviewedBy: string | null
  reviewedAt: string | null
  financeBy: string | null
  refundedAt: string | null
  rejectionReason: string | null
  rejectedBy: string | null
  fee: number | null
  feeManualOverride: boolean | null
  feeOverrideReason: string | null
  signTier: string | null
  sign1: string | null
  sign2: string | null
  signedAt1: string | null
  signedAt2: string | null
  createdAt: string | null
  /** B95：关联合同号（未挂合同为 null） */
  contractNo: string | null
  /** B95：合同口径违约金（分；未挂合同为 null，CC 侧违约金即 fee 无此列） */
  penaltyAmt: number | null
  /** B95：判定时点合同快照 JSON（contractNo/title/effectiveAt/coolingDays/penaltyRate/inCooling/baseCents/penaltyAmt/judgedAt） */
  contractSnapshot: string | null
}

/** 退卡单实体 DTO（txn_card_cancel 表行）。金额单位「分」。 */
export interface CardCancelDTO {
  txnNo: string
  cardNo: string
  customer: string
  customerName: string | null
  cardItem: string
  channel: string | null
  balance: number
  refundAmt: number
  fee: number | null
  feeRate: number | null
  feeManualOverride: boolean | null
  feeOverrideReason: string | null
  medicalContraindication: boolean | null
  remainTimes: number | null
  status: string
  applicant: string | null
  reviewedBy: string | null
  reviewedAt: string | null
  financeBy: string | null
  refundedAt: string | null
  rejectionReason: string | null
  rejectedBy: string | null
  signTier: string | null
  sign1: string | null
  sign2: string | null
  signedAt1: string | null
  signedAt2: string | null
  createdAt: string | null
  /** B95：关联合同号（未挂合同为 null） */
  contractNo: string | null
  /** B95：判定时点合同快照 JSON（字段同 RefundDTO.contractSnapshot；违约金即 fee 列） */
  contractSnapshot: string | null
}

export const createRefund = (cmd: RefundCmd) =>
  client.post<RefundDTO>('/txn/refund', cmd)

export const createCardCancel = (cmd: CardCancelCmd) =>
  client.post<CardCancelDTO>('/txn/card-cancel', cmd)

export const signTxn = (txnNo: string, cmd: SignCmd) =>
  client.post(`/txn/${txnNo}/sign`, cmd)

/** 退款单列表（status 可选：PENDING_REVIEW/PENDING_FINANCE/REFUNDED/REJECTED）。 */
export const listRefunds = (status?: string) =>
  client.get<RefundDTO[]>('/txn/refund', { params: status ? { status } : {} })

/** 退卡单列表（status 可选）。 */
export const listCardCancels = (status?: string) =>
  client.get<CardCancelDTO[]>('/txn/card-cancel', { params: status ? { status } : {} })

export const getRefund = (no: string) =>
  client.get<RefundDTO>(`/txn/refund/${no}`)

export const getCardCancel = (no: string) =>
  client.get<CardCancelDTO>(`/txn/card-cancel/${no}`)

/** 店长/运营一审通过：PENDING_REVIEW → PENDING_FINANCE。 */
export const approveTxn = (txnNo: string, actor: string) =>
  client.post(`/txn/${txnNo}/approve`, { actor })

/** 驳回：comment 必填，PENDING_REVIEW/PENDING_FINANCE → REJECTED。 */
export const rejectTxn = (txnNo: string, actor: string, comment: string) =>
  client.post(`/txn/${txnNo}/reject`, { actor, comment })

/** 财务终审确认：PENDING_FINANCE → REFUNDED（资金出入账 M6 补）。 */
export const confirmTxn = (txnNo: string, actor: string) =>
  client.post(`/txn/${txnNo}/confirm`, { actor })
