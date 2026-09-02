// ============================================================
// Order 聚合 store（正向交易：开单 → 双签 → 收款）
// 承接 prescription 开方开单产出；逆向见 refund 聚合。
// 对齐 docs/business-flows.md §2.5、permission-matrix.md。
// 后端就绪前以内存 + activity 流水兜底，金额签署层级取自设置中心。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { useSettingsStore } from './settings'
import { useConsultationStore } from './consultation'

export type OrderStatus = 'DRAFT' | 'PENDING_SIGN' | 'PENDING_PAY' | 'PAID' | 'CANCELLED'

export interface OrderItem {
  name: string
  spec?: string
  qty: number
  price: number
}

export type PayMethod = 'cash' | 'card' | 'wxpay' | 'alipay' | 'balance'

export interface Payment {
  method: PayMethod
  amount: number
}

export interface Order {
  id: string
  orderNo: string
  customerId: string
  consultantId?: string
  doctorId?: string
  items: OrderItem[]
  amount: number
  /** 所需签署层级（由设置中心双签阈值推导） */
  signTier: 'L1' | 'L2' | 'L3'
  status: OrderStatus
  createdAt: string
  remark?: string
  payments: Payment[]
  paidAt?: string
  cashierName?: string
  /** 关联咨询方案单（医美项目须医生审核通过后方可开单收款） */
  consultId?: string
}

export const useOrderStore = defineStore('order', () => {
  const auth = useAuthStore()
  const settings = useSettingsStore()
  const activity = useActivityStore()

  const orders = ref<Order[]>([])
  let seq = 0

  const pendingPay = computed(() => orders.value.filter((o) => o.status === 'PENDING_PAY'))

  function get(id: string) {
    return orders.value.find((o) => o.id === id)
  }
  function byCustomer(customerId: string) {
    return orders.value.filter((o) => o.customerId === customerId)
  }

  /**
   * 开方提交 → 生成订单，按金额进入对应签署层级。
   * 医美项目携带 consultId 时，方案单必须为 APPROVED（医生二次审核通过），否则拒绝开单。
   */
  function create(input: {
    customerId: string
    consultantId?: string
    doctorId?: string
    items: OrderItem[]
    remark?: string
    consultId?: string
  }): Order | null {
    if (!auth.can('prescription:create') && !auth.can('prescription:edit')) {
      console.warn('[order] 无开单权限')
      return null
    }
    const amount = input.items.reduce((s, it) => s + it.qty * it.price, 0)
    if (amount <= 0 || input.items.length === 0) {
      console.warn('[order] 订单明细为空或金额非法')
      return null
    }
    if (input.consultId) {
      const consult = useConsultationStore()
      const c = consult.get(input.consultId)
      if (!c) {
        console.warn('[order] 关联咨询单不存在')
        return null
      }
      const postApprove = ['APPROVED', 'READY_PAY', 'PAID', 'TREATING', 'DONE']
      if (!postApprove.includes(c.status)) {
        console.warn(`[order] 方案单 ${c.id} 未经医生审核通过（当前 ${c.status}），不可开单收款`)
        return null
      }
      // 合规闸口：医美方案单缴费单必须在首程病历签署后生成（病历先于收费）。
      // approveAndSignEmr 在生成缴费单前已写 emrSignedAt；零售/无咨询单不受此限。
      if (!c.emrSignedAt) {
        console.warn(`[order] 方案单 ${c.id} 首程病历尚未签署，缴费单将在医生写病历签名后自动生成，不可提前开单收款`)
        return null
      }
      // 幂等：一张方案单仅生成一张缴费单（病历签署时自动生成），禁止重复开单
      if (c.orderId) {
        console.warn(`[order] 方案单 ${c.id} 已生成缴费单，不可重复开单`)
        return null
      }
    }
    seq += 1
    const order: Order = {
      id: nextId('o'),
      orderNo: `SO${Date.now().toString().slice(-8)}${seq}`,
      customerId: input.customerId,
      consultantId: input.consultantId,
      doctorId: input.doctorId,
      items: input.items,
      amount,
      signTier: settings.tierFor(amount),
      // L1 基础签署后即待收款；L2/L3 需更高签批（演示期统一落待签核）
      status: settings.tierFor(amount) === 'L1' ? 'PENDING_PAY' : 'PENDING_SIGN',
      createdAt: new Date().toISOString(),
      remark: input.remark,
      payments: [],
      consultId: input.consultId,
    }
    orders.value.unshift(order)
    if (input.consultId) {
      useConsultationStore().linkOrder(input.consultId, order.id)
    }
    activity.log(
      auth.user.name,
      `开单 ${order.orderNo}，金额 ¥${amount}（${order.signTier} 签署）${input.consultId ? `，关联方案单 ${input.consultId}` : ''}`,
      order.id,
    )
    return order
  }

  /** 高签批层级订单通过签核后进入待收款（需 cashier:sign 及以上） */
  function approveSign(id: string): boolean {
    if (!auth.can('cashier:sign')) {
      console.warn('[order] 无 cashier:sign 权限，无法签核')
      return false
    }
    const o = orders.value.find((x) => x.id === id)
    if (!o || o.status !== 'PENDING_SIGN') return false
    o.status = 'PENDING_PAY'
    activity.log(auth.user.name, `订单 ${o.orderNo} 签核通过，进入待收款`, o.id)
    return true
  }

  /** 登记一笔收款（支持组合支付，收齐即 PAID） */
  function pay(id: string, method: PayMethod, amount: number): boolean {
    if (!auth.can('cashier:create')) {
      console.warn('[order] 无 cashier:create 权限，无法收款')
      return false
    }
    const o = orders.value.find((x) => x.id === id)
    if (!o || o.status !== 'PENDING_PAY') return false
    if (amount <= 0) return false
    o.payments.push({ method, amount })
    const paid = o.payments.reduce((s, p) => s + p.amount, 0)
    if (paid >= o.amount) {
      o.status = 'PAID'
      o.paidAt = new Date().toISOString()
      o.cashierName = auth.user.name
      activity.log(auth.user.name, `订单 ${o.orderNo} 收款完成 ¥${o.amount}`, o.id)
      // 医美方案单：收款完成 → 方案解锁治疗（READY_PAY → PAID）
      if (o.consultId) {
        useConsultationStore().markPaid(o.consultId)
      }
    } else {
      activity.log(auth.user.name, `订单 ${o.orderNo} 部分收款 ¥${amount}（${method}）`, o.id)
    }
    return true
  }

  function paidAmount(o: Order) {
    return o.payments.reduce((s, p) => s + p.amount, 0)
  }

  /** 开发期种子：零售/药妆待收款订单（无 consultId，不进诊疗动线流水牌，由收银台/我的工作台兜住） */
  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    // 仅按本零售种子单自身特征判重（C-201 且无 consultId），
    // 不能因「已存在任意 PENDING_PAY」就跳过——consultation 种子会先生成诊疗缴费单。
    if (orders.value.some((o) => o.customerId === 'C-201' && !o.consultId)) return
    seq += 1
    orders.value.unshift({
      id: 'o-seed-retail',
      orderNo: `SO2026082500${seq}`,
      customerId: 'C-201',
      consultantId: 'staff-lin',
      doctorId: 'staff-gu',
      items: [
        { name: '光子嫩肤', spec: '全脸 1 次', qty: 3, price: 1280 },
        { name: '水光针', spec: '基础 1 次', qty: 1, price: 980 },
      ],
      amount: 4820,
      signTier: 'L1',
      status: 'PENDING_PAY',
      createdAt: new Date().toISOString(),
      payments: [],
    })
  }

  return {
    orders, pendingPay, get, byCustomer, create, approveSign, pay, paidAmount, seed,
  }
})
