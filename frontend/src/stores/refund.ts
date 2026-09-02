// ============================================================
// Refund 聚合 store（逆向交易：退款 + 退卡共用双签状态机）
// kind = ORDER(退款) | CARD(退卡)；状态：待审核 → 待财务复核 → 已退款 / 已驳回。
// 对齐 docs/business-flows.md §2.7、permission-matrix.md。
// 签署层级取自设置中心：L1 直达财务复核；L2/L3 需店长审批后再财务。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { useSettingsStore } from './settings'

export type RefundKind = 'ORDER' | 'CARD'
export type RefundChannel = 'ORIGINAL' | 'CASH' | 'TRANSFER'
export type RefundStatus = 'PENDING_REVIEW' | 'PENDING_FINANCE' | 'REFUNDED' | 'REJECTED'

export interface Refund {
  id: string
  refundNo: string
  kind: RefundKind
  customerId: string
  customerName: string
  project: string
  paidAmount: number
  refundAmount: number
  channel: RefundChannel
  reason: string
  applicantName: string
  signTier: 'L1' | 'L2' | 'L3'
  status: RefundStatus
  createdAt: string
  /** 退卡关联的资产 ID（kind=CARD） */
  assetId?: string
  /** 违约金（退卡扣减，展示用） */
  penaltyAmount?: number
  reviewedByName?: string
  reviewedAt?: string
  financeByName?: string
  refundedAt?: string
  rejectionReason?: string
  rejectionByName?: string
}

export const useRefundStore = defineStore('refund', () => {
  const auth = useAuthStore()
  const settings = useSettingsStore()
  const activity = useActivityStore()

  const refunds = ref<Refund[]>([])
  let seq = 0

  const pendingReview = computed(() => refunds.value.filter((r) => r.status === 'PENDING_REVIEW'))
  const pendingFinance = computed(() => refunds.value.filter((r) => r.status === 'PENDING_FINANCE'))
  const refunded = computed(() => refunds.value.filter((r) => r.status === 'REFUNDED'))
  const rejected = computed(() => refunds.value.filter((r) => r.status === 'REJECTED'))

  function get(id: string) {
    return refunds.value.find((r) => r.id === id)
  }

  /** 发起退款/退卡 */
  function create(input: {
    kind: RefundKind
    customerId: string
    customerName: string
    project: string
    paidAmount: number
    refundAmount: number
    channel: RefundChannel
    reason: string
    assetId?: string
    penaltyAmount?: number
  }): Refund | null {
    if (!auth.can(input.kind === 'CARD' ? 'cardcancel:create' : 'refund:create')) {
      console.warn(`[refund] 无 ${input.kind === 'CARD' ? 'cardcancel' : 'refund'}:create 权限`)
      return null
    }
    if (input.refundAmount <= 0 || input.refundAmount > input.paidAmount) {
      console.warn('[refund] 退款金额非法')
      return null
    }
    seq += 1
    const tier = settings.tierFor(input.refundAmount)
    const r: Refund = {
      id: nextId('rf'),
      refundNo: `${input.kind === 'CARD' ? 'CC' : 'RF'}${Date.now().toString().slice(-8)}${seq}`,
      kind: input.kind,
      customerId: input.customerId,
      customerName: input.customerName,
      project: input.project,
      paidAmount: input.paidAmount,
      refundAmount: input.refundAmount,
      channel: input.channel,
      reason: input.reason,
      applicantName: auth.user.name,
      signTier: tier,
      status: tier === 'L1' ? 'PENDING_FINANCE' : 'PENDING_REVIEW',
      createdAt: new Date().toISOString(),
      assetId: input.assetId,
      penaltyAmount: input.penaltyAmount,
    }
    refunds.value.unshift(r)
    activity.log(
      auth.user.name,
      `发起${input.kind === 'CARD' ? '退卡' : '退款'} ${r.refundNo}，¥${input.refundAmount}（${tier}）`,
      r.id,
    )
    return r
  }

  /** 店长/运营审批通过（L2/L3），进入财务复核 */
  function approve(id: string): boolean {
    const r = refunds.value.find((x) => x.id === id)
    if (!r || r.status !== 'PENDING_REVIEW') return false
    const perm = r.kind === 'CARD' ? 'cardcancel:approve' : 'refund:approve'
    if (!auth.can(perm)) {
      console.warn(`[refund] 无 ${perm} 权限`)
      return false
    }
    r.status = 'PENDING_FINANCE'
    r.reviewedByName = auth.user.name
    r.reviewedAt = new Date().toISOString()
    activity.log(auth.user.name, `${r.kind === 'CARD' ? '退卡' : '退款'} ${r.refundNo} 审批通过，进入财务复核`, r.id)
    return true
  }

  /** 驳回 */
  function reject(id: string, reason: string): boolean {
    const r = refunds.value.find((x) => x.id === id)
    if (!r || (r.status !== 'PENDING_REVIEW' && r.status !== 'PENDING_FINANCE')) return false
    const perm = r.kind === 'CARD' ? 'cardcancel:approve' : 'refund:approve'
    if (!auth.can(perm)) {
      console.warn(`[refund] 无 ${perm} 权限`)
      return false
    }
    r.status = 'REJECTED'
    r.rejectionReason = reason
    r.rejectionByName = auth.user.name
    activity.log(auth.user.name, `${r.kind === 'CARD' ? '退卡' : '退款'} ${r.refundNo} 已驳回：${reason}`, r.id)
    return true
  }

  /** 财务确认退款完成 */
  function confirmRefund(id: string): boolean {
    const r = refunds.value.find((x) => x.id === id)
    if (!r || r.status !== 'PENDING_FINANCE') return false
    const perm = r.kind === 'CARD' ? 'cardcancel:sign' : 'refund:sign'
    if (!auth.can(perm)) {
      console.warn(`[refund] 无 ${perm} 权限`)
      return false
    }
    r.status = 'REFUNDED'
    r.financeByName = auth.user.name
    r.refundedAt = new Date().toISOString()
    activity.log(auth.user.name, `${r.kind === 'CARD' ? '退卡' : '退款'} ${r.refundNo} 已完成 ¥${r.refundAmount}`, r.id)
    return true
  }

  /** 开发期种子 */
  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    const now = Date.now()
    const seed: Array<Partial<Refund> & { customerName: string; project: string; paidAmount: number; refundAmount: number; status: RefundStatus }> = [
      { customerName: '王美丽', project: '光子嫩肤 5 次卡（剩余 3 次）', paidAmount: 9800, refundAmount: 5880, status: 'PENDING_REVIEW', signTier: 'L2' },
      { customerName: '陈思', project: '水光针单次', paidAmount: 1280, refundAmount: 1280, status: 'PENDING_FINANCE', signTier: 'L1' },
      { customerName: '赵敏', project: '热玛吉 4 代 1 次', paidAmount: 16800, refundAmount: 8400, status: 'REFUNDED', signTier: 'L2' },
      { customerName: '林晚', project: '瘦脸针 100U', paidAmount: 3600, refundAmount: 3600, status: 'REJECTED', signTier: 'L1' },
      { customerName: '王小姐', project: '光子嫩肤疗程（剩余 4 次）', paidAmount: 7680, refundAmount: 5120, status: 'PENDING_REVIEW', signTier: 'L2', kind: 'CARD', penaltyAmount: 1024, assetId: 'times-seed-1' },
    ]
    seed.forEach((s, i) => {
      seq += 1
      const isCard = s.kind === 'CARD'
      refunds.value.push({
        id: nextId('rf'),
        refundNo: `${isCard ? 'CC' : 'RF'}2026082${4 - i}00${i + 1}`,
        kind: s.kind || 'ORDER',
        customerId: isCard ? 'C-201' : `C-30${i}`,
        customerName: s.customerName,
        project: s.project,
        paidAmount: s.paidAmount,
        refundAmount: s.refundAmount,
        channel: i % 2 === 0 ? 'ORIGINAL' : 'TRANSFER',
        reason: isCard ? '客户搬离本地，申请退卡' : '客户申请退款（种子数据）',
        applicantName: ['苏晴（咨询师）', '李娜（前台）', '周敏（美容师）', '吴桐（咨询师）', '林微（咨询师）'][i],
        signTier: s.signTier!,
        status: s.status,
        createdAt: new Date(now - i * 3600_000).toISOString(),
        assetId: s.assetId,
        penaltyAmount: s.penaltyAmount,
        reviewedByName: s.status === 'PENDING_FINANCE' || s.status === 'REFUNDED' ? '陈雅琳（店长）' : undefined,
        financeByName: s.status === 'REFUNDED' ? '王财务' : undefined,
        refundedAt: s.status === 'REFUNDED' ? new Date(now - i * 3600_000).toISOString() : undefined,
        rejectionReason: s.status === 'REJECTED' ? '重复下单经核实为系统误报，建议走补单流程' : undefined,
        rejectionByName: s.status === 'REJECTED' ? '陈雅琳（店长）' : undefined,
      })
    })
  }

  return {
    refunds, pendingReview, pendingFinance, refunded, rejected,
    get, create, approve, reject, confirmRefund, seed,
  }
})
