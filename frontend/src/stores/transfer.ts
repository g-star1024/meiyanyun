// ============================================================
// AssetTransfer 资产转移聚合 store（客户间卡余额 / 疗程次数转移）
// 状态：待审批 → 待财务执行 → 已转移 / 已驳回。
// 对齐 docs/business-flows.md、permission-matrix.md（transfer:*）。
// 签署层级由 settings.tierFor(金额) 推导：L1 直达财务；L2/L3 需店长审批。
// 财务执行时调用 asset.applyTransfer 真实扣减/增加资产。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { useSettingsStore } from './settings'
import { useAssetStore } from './asset'

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
  /** 现金型：转移金额 */
  amount?: number
  /** 次数型：转移次数 */
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

export const useTransferStore = defineStore('transfer', () => {
  const auth = useAuthStore()
  const settings = useSettingsStore()
  const activity = useActivityStore()
  const asset = useAssetStore()

  const transfers = ref<Transfer[]>([])
  let seq = 0

  const pendingReview = computed(() => transfers.value.filter((t) => t.status === 'PENDING_REVIEW'))
  const pendingFinance = computed(() => transfers.value.filter((t) => t.status === 'PENDING_FINANCE'))
  const transferred = computed(() => transfers.value.filter((t) => t.status === 'TRANSFERRED'))
  const rejected = computed(() => transfers.value.filter((t) => t.status === 'REJECTED'))

  function get(id: string) {
    return transfers.value.find((t) => t.id === id)
  }

  /** 发起资产转移 */
  function create(input: {
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
  }): Transfer | null {
    if (!auth.can('transfer:create')) {
      console.warn('[transfer] 无 transfer:create 权限')
      return null
    }
    if (input.fromCustomerId === input.toCustomerId) {
      console.warn('[transfer] 转出与转入客户不能相同')
      return null
    }
    if (input.assetType === 'CASH') {
      if (!input.amount || input.amount <= 0) return null
    } else {
      if (!input.times || input.times <= 0) return null
    }
    // 校验转出方资产充足
    const acct = asset.account(input.fromCustomerId)
    if (input.assetType === 'CASH') {
      if (acct.totalBalance < (input.amount || 0)) {
        console.warn('[transfer] 转出方余额不足')
        return null
      }
    } else {
      const hit = acct.timesAssets.find((a) => a.itemSku === input.itemSku)
      if (!hit || hit.remainingTimes < (input.times || 0)) {
        console.warn('[transfer] 转出方疗程次数不足')
        return null
      }
    }
    seq += 1
    const tier = settings.tierFor(input.assetType === 'CASH' ? input.amount! : input.times! * 1000)
    const t: Transfer = {
      id: nextId('tr'),
      transferNo: `AT${Date.now().toString().slice(-8)}${seq}`,
      fromCustomerId: input.fromCustomerId,
      fromCustomerName: input.fromCustomerName,
      toCustomerId: input.toCustomerId,
      toCustomerName: input.toCustomerName,
      assetType: input.assetType,
      amount: input.amount,
      times: input.times,
      itemSku: input.itemSku,
      itemName: input.itemName,
      reason: input.reason,
      applicantName: auth.user.name,
      signTier: tier,
      status: tier === 'L1' ? 'PENDING_FINANCE' : 'PENDING_REVIEW',
      createdAt: new Date().toISOString(),
    }
    transfers.value.unshift(t)
    activity.log(
      auth.user.name,
      `发起资产转移 ${t.transferNo}：${input.fromCustomerName} → ${input.toCustomerName}，${
        input.assetType === 'CASH' ? `¥${input.amount}` : `${input.itemName} × ${input.times} 次`
      }（${tier}）`,
      t.id,
    )
    return t
  }

  /** 店长/区域审批通过，进入财务执行 */
  function approve(id: string): boolean {
    const t = transfers.value.find((x) => x.id === id)
    if (!t || t.status !== 'PENDING_REVIEW') return false
    if (!auth.can('transfer:approve')) {
      console.warn('[transfer] 无 transfer:approve 权限')
      return false
    }
    t.status = 'PENDING_FINANCE'
    t.reviewedByName = auth.user.name
    t.reviewedAt = new Date().toISOString()
    activity.log(auth.user.name, `资产转移 ${t.transferNo} 审批通过，进入财务执行`, t.id)
    return true
  }

  /** 驳回 */
  function reject(id: string, reason: string): boolean {
    const t = transfers.value.find((x) => x.id === id)
    if (!t || (t.status !== 'PENDING_REVIEW' && t.status !== 'PENDING_FINANCE')) return false
    if (!auth.can('transfer:approve')) {
      console.warn('[transfer] 无 transfer:approve 权限')
      return false
    }
    t.status = 'REJECTED'
    t.rejectionReason = reason
    t.rejectionByName = auth.user.name
    activity.log(auth.user.name, `资产转移 ${t.transferNo} 已驳回：${reason}`, t.id)
    return true
  }

  /** 财务执行转移：真实扣减/增加资产 */
  function execute(id: string): boolean {
    const t = transfers.value.find((x) => x.id === id)
    if (!t || t.status !== 'PENDING_FINANCE') return false
    if (!auth.can('transfer:edit')) {
      console.warn('[transfer] 无 transfer:edit 权限')
      return false
    }
    const ok = asset.applyTransfer({
      fromCustomerId: t.fromCustomerId,
      toCustomerId: t.toCustomerId,
      assetType: t.assetType,
      amount: t.amount,
      times: t.times,
      itemSku: t.itemSku,
      itemName: t.itemName,
      operatorName: auth.user.name,
      remark: t.transferNo,
    })
    if (!ok) {
      console.warn('[transfer] 资产执行失败（可能余额/次数已变动）')
      return false
    }
    t.status = 'TRANSFERRED'
    t.financeByName = auth.user.name
    t.transferredAt = new Date().toISOString()
    activity.log(
      auth.user.name,
      `资产转移 ${t.transferNo} 执行完成：${t.fromCustomerName} → ${t.toCustomerName}，${
        t.assetType === 'CASH' ? `¥${t.amount}` : `${t.itemName} × ${t.times} 次`
      }`,
      t.id,
    )
    return true
  }

  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    const now = Date.now()
    const seed: Array<Partial<Transfer> & {
      fromCustomerName: string; toCustomerName: string; reason: string;
      assetType: TransferAssetType; status: TransferStatus; signTier: 'L1' | 'L2' | 'L3'
    }> = [
      {
        fromCustomerId: 'C-201', fromCustomerName: '王小姐', toCustomerId: 'C-202', toCustomerName: '李静',
        assetType: 'CASH', amount: 2000, reason: '客户要求将部分储值转至家人账户',
        status: 'PENDING_REVIEW', signTier: 'L2',
      },
      {
        fromCustomerId: 'C-201', fromCustomerName: '王小姐', toCustomerId: 'C-202', toCustomerName: '李静',
        assetType: 'TIMES', itemSku: 'SKU-OPT-01', itemName: '光子嫩肤疗程', times: 2,
        reason: '剩余疗程转给同行好友', status: 'PENDING_FINANCE', signTier: 'L1',
      },
      {
        fromCustomerId: 'C-202', fromCustomerName: '李静', toCustomerId: 'C-201', toCustomerName: '王小姐',
        assetType: 'CASH', amount: 500, reason: '代付分摊转回',
        status: 'TRANSFERRED', signTier: 'L1',
      },
      {
        fromCustomerId: 'C-201', fromCustomerName: '王小姐', toCustomerId: 'C-202', toCustomerName: '李静',
        assetType: 'CASH', amount: 9800, reason: '大额储值转移（信息待核实）',
        status: 'REJECTED', signTier: 'L3',
      },
    ]
    seed.forEach((s, i) => {
      seq += 1
      transfers.value.push({
        id: nextId('tr'),
        transferNo: `AT2026082${4 - i}0${i + 1}`,
        fromCustomerId: s.fromCustomerId!,
        fromCustomerName: s.fromCustomerName,
        toCustomerId: s.toCustomerId!,
        toCustomerName: s.toCustomerName,
        assetType: s.assetType,
        amount: s.amount,
        times: s.times,
        itemSku: s.itemSku,
        itemName: s.itemName,
        reason: s.reason,
        applicantName: ['苏晴（店长）', '林微（咨询师）', '夏沫（前台）', '陈野（区域）'][i],
        signTier: s.signTier,
        status: s.status,
        createdAt: new Date(now - i * 7200_000).toISOString(),
        reviewedByName: s.status === 'PENDING_FINANCE' || s.status === 'TRANSFERRED' ? '陈野（区域）' : undefined,
        reviewedAt: s.status === 'PENDING_FINANCE' || s.status === 'TRANSFERRED' ? new Date(now - i * 7200_000 + 1800_000).toISOString() : undefined,
        financeByName: s.status === 'TRANSFERRED' ? '钱进（财务）' : undefined,
        transferredAt: s.status === 'TRANSFERRED' ? new Date(now - i * 7200_000 + 3600_000).toISOString() : undefined,
        rejectionReason: s.status === 'REJECTED' ? '单笔金额过大且双方关系无法核实，需客户本人到院签署确认' : undefined,
        rejectionByName: s.status === 'REJECTED' ? '陈野（区域）' : undefined,
      })
    })
  }

  return {
    transfers, pendingReview, pendingFinance, transferred, rejected,
    get, create, approve, reject, execute, seed,
  }
})
