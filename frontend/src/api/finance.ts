// ============================================================
// Finance API（对接 finance-service，全只读镜像）
// ============================================================
import client from './client'

export interface PrepayPool {
  total: number
  pendingConsume: number
  refundable: number
  earnedPending: number
}

export interface Tax {
  cat: string
  base: number
  rate: number
  amount: number
}

export interface AccountMirror {
  acctId: string
  acctName: string
  balance: number
  acctType: string
}

export interface OutboxRecord {
  outboxId: number
  bizType: string
  txnNo: string
  amount: number
  channel: string
  status: string
}

export interface RevenueMonthly {
  storeCode: string
  periodMonth: string // yyyy-MM-01
  revenue: number // 分
  cost: number
  grossProfit: number
  costRate: number
  grossRate: number
}

export const getPrepayPool = () => client.get<PrepayPool>('/finance/prepay-pool')
export const getTax = () => client.get<Tax[]>('/finance/tax')
export const getAccounts = () => client.get<AccountMirror[]>('/finance/accounts')
export const getOutbox = () => client.get<OutboxRecord[]>('/finance/outbox')
export const getReconcile = () =>
  client.get<{ totalRecords: number; byStatus: Record<string, number>; netAmount: number }>('/finance/outbox/reconcile')
export const getRevenue = (storeCode?: string, month?: string) =>
  client.get<RevenueMonthly[]>('/finance/revenue', { params: { storeCode, month } })
