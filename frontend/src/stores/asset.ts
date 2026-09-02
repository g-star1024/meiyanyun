// ============================================================
// Asset 资产聚合 store
// 双聚合：CashAsset（余额/储值）+ TimesAsset（次数/疗程）；AssetAccount 只读汇总。
// 对齐 docs/domain-model.md §2.4（2026-08-24 决议）。
// 后端就绪前以内存 + activity 流水兜底。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { CashAsset, TimesAsset, AssetAccount } from '@/types/domain'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { useCustomerStore } from './customer'

const STORE_ID = 'store-jingan'

/** 资产流水类型 */
export type AssetTxnKind =
  | 'RECHARGE' // 充值
  | 'PURCHASE' // 购买次卡/疗程（入账次数）
  | 'CONSUME' // 消费扣减（核销扣次/扣款）
  | 'REFUND' // 退款回退
  | 'FREEZE' // 冻结
  | 'UNFREEZE' // 解冻
  | 'ADJUST' // 人工调整

export interface AssetTxn {
  id: string
  assetId: string
  assetType: 'CASH' | 'TIMES'
  kind: AssetTxnKind
  /** 金额变动（现金型，正入账/负扣减） */
  amount?: number
  /** 次数变动（次数型，正入账/负扣减） */
  times?: number
  operatorName: string
  remark?: string
  at: string
}

export const useAssetStore = defineStore('asset', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()
  const customer = useCustomerStore()

  const cashAssets = ref<CashAsset[]>([])
  const timesAssets = ref<TimesAsset[]>([])
  const txns = ref<AssetTxn[]>([])

  /** 某客户的资产账户（只读汇总） */
  function account(customerId: string): AssetAccount {
    const cash = cashAssets.value.filter((a) => a.customerId === customerId && a.status === 'ACTIVE')
    const times = timesAssets.value.filter((a) => a.customerId === customerId && a.status === 'ACTIVE')
    return {
      customerId,
      cashAssets: cash,
      timesAssets: times,
      totalBalance: cash.reduce((s, a) => s + a.balance + a.giftBalance, 0),
      totalRemainingTimes: times.reduce((s, a) => s + a.remainingTimes, 0),
    }
  }

  /** 有任意资产的客户（用于列表） */
  const customersWithAssets = computed(() => {
    const ids = new Set<string>()
    cashAssets.value.forEach((a) => { if (a.status === 'ACTIVE') ids.add(a.customerId) })
    timesAssets.value.forEach((a) => { if (a.status === 'ACTIVE') ids.add(a.customerId) })
    return [...ids].map((id) => customer.get(id)).filter(Boolean)
  })

  function txnsOf(assetId: string) {
    return txns.value.filter((t) => t.assetId === assetId)
  }

  /** 充值（储值卡），返回资产 */
  function recharge(input: {
    customerId: string
    amount: number
    gift?: number
    payType?: CashAsset['payType']
  }): CashAsset | null {
    if (!auth.can('course:edit')) {
      console.warn('[asset] 无 course:edit 权限')
      return null
    }
    if (input.amount <= 0) return null
    // 同一客户优先累加已有 ACTIVE 储值卡；否则新开
    let asset = cashAssets.value.find(
      (a) => a.customerId === input.customerId && a.status === 'ACTIVE',
    )
    if (!asset) {
      asset = {
        id: nextId('cash'),
        customerId: input.customerId,
        storeId: STORE_ID,
        balance: 0,
        giftBalance: 0,
        payType: input.payType || 'CASH',
        status: 'ACTIVE',
      }
      cashAssets.value.unshift(asset)
    }
    asset.balance += input.amount
    if (input.gift) asset.giftBalance += input.gift
    txns.value.unshift({
      id: nextId('txn'),
      assetId: asset.id,
      assetType: 'CASH',
      kind: 'RECHARGE',
      amount: input.amount + (input.gift || 0),
      operatorName: auth.user.name,
      at: new Date().toISOString(),
    })
    activity.log(
      auth.user.name,
      `充值 ${customer.nameOf(input.customerId)}：¥${input.amount}${input.gift ? `（赠 ¥${input.gift}）` : ''}`,
      asset.id,
    )
    return asset
  }

  /** 购买次卡/疗程（入账次数） */
  function purchaseTimes(input: {
    customerId: string
    itemSku: string
    itemName: string
    totalTimes: number
  }): TimesAsset | null {
    if (!auth.can('course:edit')) {
      console.warn('[asset] 无 course:edit 权限')
      return null
    }
    if (input.totalTimes <= 0) return null
    const asset: TimesAsset = {
      id: nextId('times'),
      customerId: input.customerId,
      storeId: STORE_ID,
      itemSku: input.itemSku,
      itemName: input.itemName,
      totalTimes: input.totalTimes,
      remainingTimes: input.totalTimes,
      status: 'ACTIVE',
    }
    timesAssets.value.unshift(asset)
    txns.value.unshift({
      id: nextId('txn'),
      assetId: asset.id,
      assetType: 'TIMES',
      kind: 'PURCHASE',
      times: input.totalTimes,
      operatorName: auth.user.name,
      at: new Date().toISOString(),
    })
    activity.log(
      auth.user.name,
      `购卡 ${customer.nameOf(input.customerId)}：${input.itemName} × ${input.totalTimes} 次`,
      asset.id,
    )
    return asset
  }

  /** 扣次（核销调用），返回剩余次数；不足返回 null */
  function consumeTimes(assetId: string, times = 1, remark?: string): number | null {
    const asset = timesAssets.value.find((a) => a.id === assetId)
    if (!asset || asset.status !== 'ACTIVE') return null
    if (asset.remainingTimes < times) {
      console.warn('[asset] 剩余次数不足')
      return null
    }
    asset.remainingTimes -= times
    if (asset.remainingTimes === 0) asset.status = 'FINISHED'
    txns.value.unshift({
      id: nextId('txn'),
      assetId,
      assetType: 'TIMES',
      kind: 'CONSUME',
      times: -times,
      operatorName: auth.user.name,
      remark,
      at: new Date().toISOString(),
    })
    activity.log(auth.user.name, `扣次 ${asset.itemName} -${times}（剩 ${asset.remainingTimes}）`, assetId)
    return asset.remainingTimes
  }

  /** 扣款（储值消费），返回剩余余额；不足返回 null */
  function consumeCash(assetId: string, amount: number, remark?: string): number | null {
    const asset = cashAssets.value.find((a) => a.id === assetId)
    if (!asset || asset.status !== 'ACTIVE') return null
    const total = asset.balance + asset.giftBalance
    if (total < amount) {
      console.warn('[asset] 余额不足')
      return null
    }
    // 优先扣赠送金，再扣本金
    let left = amount
    const fromGift = Math.min(asset.giftBalance, left)
    asset.giftBalance -= fromGift
    left -= fromGift
    asset.balance -= left
    txns.value.unshift({
      id: nextId('txn'),
      assetId,
      assetType: 'CASH',
      kind: 'CONSUME',
      amount: -amount,
      operatorName: auth.user.name,
      remark,
      at: new Date().toISOString(),
    })
    activity.log(auth.user.name, `储值扣款 -¥${amount}（剩 ¥${asset.balance + asset.giftBalance}）`, assetId)
    return asset.balance + asset.giftBalance
  }

  /**
   * 执行资产转移（审批通过后调用，真实扣减转出方 / 增加转入方）。
   * - CASH：按金额从转出方储值卡扣减，转入转入方储值卡（优先本金，再赠送金）。
   * - TIMES：按次数从转出方疗程卡扣减，在转入方名下新建同 SKU 疗程卡（或累加）。
   * 返回是否成功。
   */
  function applyTransfer(input: {
    fromCustomerId: string
    toCustomerId: string
    assetType: 'CASH' | 'TIMES'
    amount?: number
    times?: number
    itemSku?: string
    itemName?: string
    operatorName: string
    remark?: string
  }): boolean {
    if (input.fromCustomerId === input.toCustomerId) return false
    if (input.assetType === 'CASH') {
      const amount = input.amount || 0
      if (amount <= 0) return false
      const from = cashAssets.value.find(
        (a) => a.customerId === input.fromCustomerId && a.status === 'ACTIVE',
      )
      if (!from || from.balance + from.giftBalance < amount) return false
      // 扣减：优先赠送金
      let left = amount
      const fromGift = Math.min(from.giftBalance, left)
      from.giftBalance -= fromGift
      left -= fromGift
      from.balance -= left
      // 转入方：累加已有储值卡；本金进 balance，赠送部分（若有）进 giftBalance
      let to = cashAssets.value.find(
        (a) => a.customerId === input.toCustomerId && a.status === 'ACTIVE',
      )
      if (!to) {
        to = {
          id: nextId('cash'), customerId: input.toCustomerId, storeId: STORE_ID,
          balance: 0, giftBalance: 0, payType: 'CASH', status: 'ACTIVE',
        }
        cashAssets.value.unshift(to)
      }
      to.balance += amount - fromGift
      to.giftBalance += fromGift
      txns.value.unshift(
        { id: nextId('txn'), assetId: from.id, assetType: 'CASH', kind: 'ADJUST', amount: -amount, operatorName: input.operatorName, remark: `转出至 ${customer.nameOf(input.toCustomerId)}${input.remark ? '：' + input.remark : ''}`, at: new Date().toISOString() },
        { id: nextId('txn'), assetId: to.id, assetType: 'CASH', kind: 'ADJUST', amount, operatorName: input.operatorName, remark: `由 ${customer.nameOf(input.fromCustomerId)} 转入${input.remark ? '：' + input.remark : ''}`, at: new Date().toISOString() },
      )
    } else {
      const times = input.times || 0
      if (times <= 0) return false
      const from = timesAssets.value.find(
        (a) => a.customerId === input.fromCustomerId && a.status === 'ACTIVE' && a.itemSku === input.itemSku,
      )
      if (!from || from.remainingTimes < times) return false
      from.remainingTimes -= times
      if (from.remainingTimes === 0) from.status = 'FINISHED'
      // 转入方：同 SKU 累加剩余次数；否则新建
      let to = timesAssets.value.find(
        (a) => a.customerId === input.toCustomerId && a.status === 'ACTIVE' && a.itemSku === input.itemSku,
      )
      if (to) {
        to.remainingTimes += times
        to.totalTimes += times
      } else {
        to = {
          id: nextId('times'), customerId: input.toCustomerId, storeId: STORE_ID,
          itemSku: input.itemSku || '', itemName: input.itemName || '转入疗程',
          totalTimes: times, remainingTimes: times, status: 'ACTIVE',
          expiresAt: from.expiresAt,
        }
        timesAssets.value.unshift(to)
      }
      txns.value.unshift(
        { id: nextId('txn'), assetId: from.id, assetType: 'TIMES', kind: 'ADJUST', times: -times, operatorName: input.operatorName, remark: `转出至 ${customer.nameOf(input.toCustomerId)}`, at: new Date().toISOString() },
        { id: nextId('txn'), assetId: to.id, assetType: 'TIMES', kind: 'ADJUST', times, operatorName: input.operatorName, remark: `由 ${customer.nameOf(input.fromCustomerId)} 转入`, at: new Date().toISOString() },
      )
    }
    return true
  }

  /** 冻结/解冻 */
  function setFrozen(assetId: string, frozen: boolean, remark?: string) {
    const asset =
      cashAssets.value.find((a) => a.id === assetId) ||
      timesAssets.value.find((a) => a.id === assetId)
    if (!asset) return
    asset.status = frozen ? 'FROZEN' : 'ACTIVE'
    txns.value.unshift({
      id: nextId('txn'),
      assetId,
      assetType: 'balance' in asset ? 'CASH' : 'TIMES',
      kind: frozen ? 'FREEZE' : 'UNFREEZE',
      operatorName: auth.user.name,
      remark,
      at: new Date().toISOString(),
    })
    activity.log(auth.user.name, `${frozen ? '冻结' : '解冻'}资产`, assetId)
  }

  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    // C-201 王小姐：储值卡余额 + 疗程
    const cash: CashAsset = {
      id: 'cash-seed-1', customerId: 'C-201', storeId: STORE_ID,
      balance: 5200, giftBalance: 600, payType: 'MIX', status: 'ACTIVE',
    }
    cashAssets.value.push(cash)
    const times1: TimesAsset = {
      id: 'times-seed-1', customerId: 'C-201', storeId: STORE_ID,
      itemSku: 'SKU-OPT-01', itemName: '光子嫩肤疗程', totalTimes: 6, remainingTimes: 4,
      status: 'ACTIVE', expiresAt: new Date(Date.now() + 12 * 86400000).toISOString(),
    }
    timesAssets.value.push(times1)
    const times2: TimesAsset = {
      id: 'times-seed-2', customerId: 'C-202', storeId: STORE_ID,
      itemSku: 'SKU-ANTI-03', itemName: '热玛吉四代', totalTimes: 3, remainingTimes: 3,
      status: 'ACTIVE', expiresAt: new Date(Date.now() + 90 * 86400000).toISOString(),
    }
    timesAssets.value.push(times2)
    // 已用完疗程
    timesAssets.value.push({
      id: 'times-seed-3', customerId: 'C-202', storeId: STORE_ID,
      itemSku: 'SKU-WATER-01', itemName: '水光针基础', totalTimes: 5, remainingTimes: 0,
      status: 'FINISHED',
    })
    // 历史流水
    const now = Date.now()
    txns.value.push(
      { id: nextId('txn'), assetId: cash.id, assetType: 'CASH', kind: 'RECHARGE', amount: 5800, operatorName: '夏沫（前台）', at: new Date(now - 6 * 86400000).toISOString() },
      { id: nextId('txn'), assetId: times1.id, assetType: 'TIMES', kind: 'PURCHASE', times: 6, operatorName: '林微（咨询师）', at: new Date(now - 6 * 86400000).toISOString() },
      { id: nextId('txn'), assetId: times1.id, assetType: 'TIMES', kind: 'CONSUME', times: -1, operatorName: '顾屿（医生）', remark: '首次核销', at: new Date(now - 5 * 86400000).toISOString() },
      { id: nextId('txn'), assetId: times1.id, assetType: 'TIMES', kind: 'CONSUME', times: -1, operatorName: '顾屿（医生）', remark: '第二次核销', at: new Date(now - 3 * 86400000).toISOString() },
      { id: nextId('txn'), assetId: cash.id, assetType: 'CASH', kind: 'CONSUME', amount: -600, operatorName: '夏沫（前台）', remark: '抵扣水光针', at: new Date(now - 5 * 86400000).toISOString() },
      { id: nextId('txn'), assetId: times2.id, assetType: 'TIMES', kind: 'PURCHASE', times: 3, operatorName: '林微（咨询师）', at: new Date(now - 2 * 86400000).toISOString() },
    )
  }

  return {
    cashAssets, timesAssets, txns, customersWithAssets,
    account, txnsOf, recharge, purchaseTimes, consumeTimes, consumeCash, applyTransfer, setFrozen, seed,
  }
})
