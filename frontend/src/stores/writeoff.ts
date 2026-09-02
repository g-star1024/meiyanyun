// ============================================================
// Writeoff 聚合 store（划扣核销）
// 消费 order store 的 PAID 订单，生成核销/扣次记录。
// 对齐 docs/business-flows.md §2.6、permission-matrix.md。
// 后端就绪前以内存 + activity 流水兜底。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { useOrderStore, type Order } from './order'

export type WriteoffStatus = 'PENDING' | 'DONE' | 'ABNORMAL' | 'VOID'

export interface WriteoffRecord {
  id: string
  writeoffNo: string
  orderId: string
  orderNo: string
  customerId: string
  /** 本次核销的项目名（订单明细首项，演示期一单核销一次） */
  project: string
  /** 扣次次数 */
  timesUsed: number
  /** 核销金额 */
  amount: number
  operatorName: string
  status: WriteoffStatus
  createdAt: string
  doneAt?: string
  abnormalReason?: string
}

export const useWriteoffStore = defineStore('writeoff', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()
  const order = useOrderStore()

  const records = ref<WriteoffRecord[]>([])
  let seq = 0

  /** 待核销 = 已支付且尚未生成核销记录的订单 */
  const pendingOrders = computed<Order[]>(() => {
    const written = new Set(records.value.map((r) => r.orderId))
    return order.orders.filter((o) => o.status === 'PAID' && !written.has(o.id))
  })

  const doneRecords = computed(() => records.value.filter((r) => r.status === 'DONE'))
  const abnormalRecords = computed(() => records.value.filter((r) => r.status === 'ABNORMAL'))

  function get(id: string) {
    return records.value.find((r) => r.id === id)
  }

  /** 对一笔 PAID 订单执行核销（演示期一单核销一次，取明细首项） */
  function writeoffOrder(orderId: string): WriteoffRecord | null {
    if (!auth.can('writeoff:create')) {
      console.warn('[writeoff] 无 writeoff:create 权限')
      return null
    }
    const o = order.get(orderId)
    if (!o || o.status !== 'PAID') {
      console.warn('[writeoff] 订单不存在或未支付')
      return null
    }
    if (records.value.some((r) => r.orderId === orderId)) {
      console.warn('[writeoff] 该订单已核销')
      return null
    }
    seq += 1
    const item = o.items[0]
    const rec: WriteoffRecord = {
      id: nextId('w'),
      writeoffNo: `WO${Date.now().toString().slice(-8)}${seq}`,
      orderId: o.id,
      orderNo: o.orderNo,
      customerId: o.customerId,
      project: item.name + (item.spec ? ` ${item.spec}` : ''),
      timesUsed: item.qty,
      amount: o.amount,
      operatorName: auth.user.name,
      status: 'DONE',
      createdAt: new Date().toISOString(),
      doneAt: new Date().toISOString(),
    }
    records.value.unshift(rec)
    activity.log(
      auth.user.name,
      `核销 ${rec.writeoffNo}：${rec.project}，扣 ${rec.timesUsed} 次 / ¥${rec.amount}`,
      rec.id,
    )
    return rec
  }

  /** 标记异常（如划扣与订单不符、额度不足） */
  function markAbnormal(id: string, reason: string): boolean {
    if (!auth.can('writeoff:edit')) {
      console.warn('[writeoff] 无 writeoff:edit 权限')
      return false
    }
    const r = records.value.find((x) => x.id === id)
    if (!r || r.status !== 'PENDING') return false
    r.status = 'ABNORMAL'
    r.abnormalReason = reason
    activity.log(auth.user.name, `核销 ${r.writeoffNo} 标记异常：${reason}`, r.id)
    return true
  }

  /** 开发期种子：种一笔已支付订单并直接核销，另留一笔待核销 */
  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    order.seed()
    // 种子订单（¥4,820）若仍待收款则直接置为已支付（seed 绕过权限，构造待核销数据）
    const seedOrder = order.orders.find((o) => o.orderNo.startsWith('SO20260825'))
    if (seedOrder && seedOrder.status === 'PENDING_PAY') {
      seedOrder.status = 'PAID'
      seedOrder.paidAt = new Date().toISOString()
      seedOrder.cashierName = '系统（种子）'
      seedOrder.payments = [{ method: 'wxpay', amount: seedOrder.amount }]
    }
    // 种一笔历史已核销记录（基于已支付订单）
    if (seedOrder && seedOrder.status === 'PAID') {
      // 不重复核销种子单，保留为待核销；另造一笔已核销
      seq += 1
      records.value.unshift({
        id: nextId('w'),
        writeoffNo: `WO2026082400${seq}`,
        orderId: 'seed-done',
        orderNo: 'SO20260824009',
        customerId: 'C-201',
        project: '水光针 基础 1 次',
        timesUsed: 1,
        amount: 980,
        operatorName: '周敏（美容师）',
        status: 'DONE',
        createdAt: new Date(Date.now() - 86400000).toISOString(),
        doneAt: new Date(Date.now() - 86400000).toISOString(),
      })
      // 种一笔异常
      seq += 1
      records.value.unshift({
        id: nextId('w'),
        writeoffNo: `WO2026082300${seq}`,
        orderId: 'seed-abn',
        orderNo: 'SO20260823015',
        customerId: 'C-305',
        project: '热玛吉 4 代 1 次',
        timesUsed: 1,
        amount: 16800,
        operatorName: '苏晴（咨询师）',
        status: 'ABNORMAL',
        createdAt: new Date(Date.now() - 2 * 86400000).toISOString(),
        abnormalReason: '划扣金额与订单实付不符，差 ¥800，疑似漏记优惠',
      })
    }
  }

  return {
    records, pendingOrders, doneRecords, abnormalRecords,
    get, writeoffOrder, markAbnormal, seed,
  }
})
