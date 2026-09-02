/**
 * 订单 store（C 端）
 * 会员查看自己的订单、订单详情（含核销码）。
 * 后端就绪后：列表 GET /c/orders，详情 GET /c/orders/:id；
 * 下单/支付走 src/api/pay.ts（统一下单由服务端签名）。
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'

export type OrderStatus = 'PENDING_PAY' | 'PENDING_WRITE' | 'PAID' | 'COMPLETED'
export interface OrderItem {
  name: string
  spec?: string
  qty: number
  price: number
}
export interface COrder {
  id: string
  orderNo: string
  customerId: string
  items: OrderItem[]
  amount: number
  status: OrderStatus
  payMethod?: string
  createdAt: string
  store: string
}

let seq = 0
function nextId() {
  seq += 1
  return `ord-${seq}`
}

export const useOrderStore = defineStore('mp-order', () => {
  const orders = ref<COrder[]>([])

  function get(id: string) {
    return orders.value.find((o) => o.id === id || o.orderNo === id)
  }
  function byCustomer(customerId: string) {
    return orders.value.filter((o) => o.customerId === customerId)
  }

  /** 创建一笔订单（支付成功后调用；真实链路在 api/pay.ts） */
  function create(input: { customerId: string; items: OrderItem[]; payMethod?: string }): COrder {
    const amount = input.items.reduce((s, it) => s + it.qty * it.price, 0)
    const o: COrder = {
      id: nextId(),
      orderNo: `SO${Date.now().toString().slice(-8)}${seq}`,
      customerId: input.customerId,
      items: input.items,
      amount,
      status: 'PENDING_WRITE',
      payMethod: input.payMethod || '微信支付',
      createdAt: new Date().toISOString(),
      store: '上海静安旗舰店',
    }
    orders.value.unshift(o)
    return o
  }

  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    orders.value.push(
      {
        id: nextId(),
        orderNo: 'SO20260818002',
        customerId: 'C-201',
        items: [{ name: '闺蜜分享次卡', spec: '10 次水光', qty: 1, price: 3980 }],
        amount: 3980,
        status: 'COMPLETED',
        payMethod: '微信支付',
        createdAt: '2026-08-18T14:02:00',
        store: '上海静安旗舰店',
      },
      {
        id: nextId(),
        orderNo: 'SO20260810008',
        customerId: 'C-201',
        items: [{ name: '玻尿酸填充（瑞蓝2号）', spec: '瑞蓝2号 1支', qty: 1, price: 5280 }],
        amount: 5280,
        status: 'COMPLETED',
        payMethod: '会员卡支付',
        createdAt: '2026-08-10T10:20:00',
        store: '上海静安旗舰店',
      },
      {
        id: nextId(),
        orderNo: 'SO20260825006',
        customerId: 'C-201',
        items: [{ name: '光子嫩肤（M22）', spec: '到店服务', qty: 1, price: 1280 }],
        amount: 1280,
        status: 'PENDING_WRITE',
        payMethod: '微信支付',
        createdAt: new Date().toISOString(),
        store: '上海静安旗舰店',
      },
    )
  }

  return { orders, get, byCustomer, create, seed }
})
