// 资产转移 —— 真后端接入（复购 bizType=资产转移 镜像看板适配层）
// 来源（DESIGN-P5-B85 L169 权威定案）：资产转移不落新端点，复用 RepurchaseService（bizType='资产转移'）。
//   create → POST /txn/repurchase（from/toCardNo 由本 store 依客户持卡自动解析，须同品项/归属/在用/余额校验）
//   列表 → GET /txn/repurchase 过滤 bizType='资产转移'，派生 Transfer 读模型
//   approve/reject/execute → 由审批中心真链路承担（RepurchaseRecord.sign × 3 三方签核），本 store 只留读投影＋发起
// 状态映射：待签核→PENDING_REVIEW / 审批中→PENDING_FINANCE / 已完成→TRANSFERRED / 已拒绝→REJECTED
// 单位口径：后端分 → 前端元（amount）；签核时间戳取 sign3（终签）。
import { defineStore } from 'pinia'
import { useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { useCustomerStore } from './customer'
import { useSettingsStore } from './settings'
import { useToast } from '../composables/useToast'
import { errMsg } from './m5Coupon'
import { createRepurchase, listRepurchase } from '../api/repurchase'
import type { RepurchaseDTO } from '../api/repurchase'
import { getCustomer, listCustomerCards } from '../api/customer'
import type { MemberCardDTO } from '../api/customer'

export type TransferAssetType = 'CASH' | 'TIMES'
export type TransferStatus = 'PENDING_REVIEW' | 'PENDING_FINANCE' | 'TRANSFERRED' | 'REJECTED'

export interface Transfer {
  id: string
  transferNo: string
  fromCustomerId: string
  fromCustomerName: string
  toCustomerId: string
  toCustomerName: string
  assetType: TransferAssetType
  amount?: number
  times?: number
  itemSku?: string
  itemName?: string
  reason: string
  applicantName: string
  signTier: 'L1' | 'L2' | 'L3'
  status: TransferStatus
  createdAt: string
  reviewedByName?: string
  reviewedAt?: string
  financeByName?: string
  transferredAt?: string
  rejectionReason?: string
  rejectionByName?: string
}

// 中文状态 → TransferStatus 四段映射（复购 sign1/2/3 三方签核 → REVIEW/FINANCE 两段投影）
const STATUS_MAP: Record<string, TransferStatus> = {
  待签核: 'PENDING_REVIEW',
  审批中: 'PENDING_FINANCE',
  已完成: 'TRANSFERRED',
  已拒绝: 'REJECTED',
}

function text(v: unknown): string {
  return v == null ? '' : String(v)
}

function num(v: unknown): number {
  const n = Number(v)
  return Number.isFinite(n) ? n : 0
}

// RepurchaseDTO（bizType=资产转移）→ Transfer 读模型
function adapt(d: RepurchaseDTO): Transfer {
  const times = num(d.transferTimes)
  const amount = num(d.transferAmount) / 100
  // 后端 Repurchase 无 signTier 列 —— 读投影按 settings 阈值本地推导（TIMES 按 times*1000 元估算，与历史口径一致）
  const tier = useSettingsStore().tierFor(times > 0 ? times * 1000 : amount)
  return {
    id: text(d.repurchaseNo),
    transferNo: text(d.repurchaseNo),
    fromCustomerId: text(d.customerId),
    fromCustomerName: useCustomerStore().nameOf(text(d.customerId)),
    toCustomerId: text(d.toCustomerId),
    toCustomerName: useCustomerStore().nameOf(text(d.toCustomerId)),
    assetType: times > 0 ? 'TIMES' : 'CASH',
    amount: times > 0 ? undefined : amount,
    times: times > 0 ? times : undefined,
    reason: text(d.note),
    applicantName: text(d.sign2),
    signTier: tier,
    status: STATUS_MAP[text(d.status)] ?? 'PENDING_REVIEW',
    createdAt: text(d.createdAt),
    reviewedByName: text(d.sign3) || undefined,
    reviewedAt: text(d.signedAt3) || undefined,
    transferredAt: text(d.status) === '已完成' ? text(d.signedAt3) || undefined : undefined,
  }
}

export const useTransferStore = defineStore('transfer', {
  state: () => ({
    transfers: [] as Transfer[],
    loading: false as boolean,
    seeded: false as boolean,
  }),
  getters: {
    byStatus:
      s =>
      (st: TransferStatus): Transfer[] =>
        s.transfers.filter(t => t.status === st),
    ofCustomer:
      s =>
      (custId: string): Transfer[] =>
        s.transfers.filter(t => t.fromCustomerId === custId || t.toCustomerId === custId),
    pendingReview: s => s.transfers.filter(t => t.status === 'PENDING_REVIEW'),
    pendingFinance: s => s.transfers.filter(t => t.status === 'PENDING_FINANCE'),
    transferred: s => s.transfers.filter(t => t.status === 'TRANSFERRED'),
    rejected: s => s.transfers.filter(t => t.status === 'REJECTED'),
    get:
      s =>
      (id: string): Transfer | undefined =>
        s.transfers.find(t => t.id === id),
  },
  actions: {
    // 后端实体不冗余客户名：对未入缓存的 customerId/toCustomerId 逐路拉详情富化（同 repurchase 样板）
    async enrichCustomerNames(list: RepurchaseDTO[]) {
      const customer = useCustomerStore()
      const ids = new Set<string>()
      for (const d of list) {
        if (d.customerId) ids.add(text(d.customerId))
        if (d.toCustomerId) ids.add(text(d.toCustomerId))
      }
      const missing = [...ids].filter(id => id && !customer.get(id))
      if (!missing.length) return
      const results = await Promise.allSettled(missing.map(id => getCustomer(id)))
      const found = results
        .filter((r): r is PromiseFulfilledResult<Awaited<ReturnType<typeof getCustomer>>> => r.status === 'fulfilled')
        .map(r => r.value.data)
      customer.hydrate(found.map(c => ({
        customerId: c.customerId,
        customerName: c.name,
        phone: c.phone,
        storeCode: c.storeCode,
      })))
    },

    async load() {
      this.loading = true
      try {
        const list = ((await listRepurchase()).data ?? []).filter(d => text(d.bizType) === '资产转移')
        await this.enrichCustomerNames(list)
        this.transfers = list.map(adapt)
      } catch (e) {
        useToast().error(errMsg(e, '资产转移列表加载失败'))
      } finally {
        this.loading = false
      }
    },

    // 选卡：from 卡须属 fromCustomerId、在用、余额/次数足；to 卡须属 toCustomerId、在用、同品项（次数）。
    pickCards(
      fromCards: MemberCardDTO[],
      toCards: MemberCardDTO[],
      input: { assetType: TransferAssetType; amount?: number; times?: number; itemSku?: string },
    ): { fromCardNo: string; toCardNo: string } | null {
      const toast = useToast()
      const alive = (c: MemberCardDTO) => text(c.status) === '在用'
      if (input.assetType === 'TIMES') {
        const sku = text(input.itemSku)
        const from = fromCards.find(c => alive(c) && text(c.productCode) === sku && num(c.remainTimes) >= num(input.times))
        if (!from) {
          toast.error(`转出客户无可用品项 ${sku} 且剩余次数充足的疗程卡`)
          return null
        }
        const to = toCards.find(c => alive(c) && text(c.productCode) === sku)
        if (!to) {
          toast.error(`接收客户无同品项 ${sku} 的在用疗程卡（次数转移要求同品项）`)
          return null
        }
        return { fromCardNo: text(from.cardNo), toCardNo: text(to.cardNo) }
      }
      const amountFen = Math.round(num(input.amount) * 100)
      // 储值口径与 CardCourseView 一致：cardType 非 'COURSE' 即余额卡（真数据含 '储值卡'/'' 等快照值）
      const isCashCard = (c: MemberCardDTO) => text(c.cardType) !== 'COURSE'
      const from = fromCards.find(c => alive(c) && isCashCard(c) && num(c.balance) >= amountFen)
      if (!from) {
        toast.error('转出客户无余额充足的在用储值卡')
        return null
      }
      const to = toCards.find(c => alive(c) && isCashCard(c))
      if (!to) {
        toast.error('接收客户无在用储值卡')
        return null
      }
      return { fromCardNo: text(from.cardNo), toCardNo: text(to.cardNo) }
    },

    async create(input: {
      fromCustomerId: string
      fromCustomerName: string
      toCustomerId: string
      toCustomerName: string
      assetType: TransferAssetType
      amount?: number
      times?: number
      itemSku?: string
      itemName?: string
      reason: string
    }): Promise<Transfer | null> {
      const auth = useAuthStore()
      const toast = useToast()
      try {
        const [fromRes, toRes] = await Promise.all([
          listCustomerCards(input.fromCustomerId),
          listCustomerCards(input.toCustomerId),
        ])
        const picked = this.pickCards(fromRes.data ?? [], toRes.data ?? [], input)
        if (!picked) return null
        const cmd = {
          customerId: input.fromCustomerId,
          storeCode: auth.user.storeId,
          bizType: '资产转移',
          fromCardNo: picked.fromCardNo,
          toCardNo: picked.toCardNo,
          toCustomerId: input.toCustomerId,
          transferTimes: input.assetType === 'TIMES' ? input.times : undefined,
          transferAmount: input.assetType === 'CASH' ? Math.round(num(input.amount) * 100) : undefined,
          consentAck: true,
          consentText: '资产转移知情同意（双方）',
          note: input.reason,
        }
        await createRepurchase(cmd)
        await this.load()
        const t = this.transfers[0]
        useActivityStore().log(
          auth.user.name,
          `资产转移申请 ${input.fromCustomerName} → ${input.toCustomerName}（${input.assetType === 'CASH' ? `¥${input.amount}` : `${input.times} 次`}），已提交三方签核`,
          t?.transferNo ?? '',
        )
        return t ?? null
      } catch (e) {
        toast.error(errMsg(e, '转移申请提交失败'))
        return null
      }
    },

    // approve/reject/execute 由审批中心真链路承担（RepurchaseRecord.sign 三方签核）—— DESIGN L169
    approve(_no: string, _byName?: string): boolean {
      useToast().info('资产转移签核请至「复购/审批中心」完成三方签核')
      return false
    },
    reject(_no: string, _reason?: string): boolean {
      useToast().info('资产转移拒绝请至「复购/审批中心」操作')
      return false
    },
    execute(_no: string, _byName?: string): boolean {
      useToast().info('资产转移执行在签核完成后由台账自动搬账，见「复购/审批中心」')
      return false
    },

    // 视图 onMounted 兼容钩子 —— 改调真加载
    async seed() {
      await this.load()
      this.seeded = true
    },
  },
})
