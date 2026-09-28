/**
 * 订单 store（C 端）
 * 会员查看自己的订单、订单详情（含核销凭证）。
 * C-B4 切真：列表 GET /c/orders（txn_order 行级隔离只读投影，B 五态→C 六态，金额元）、
 * 创建 POST /c/orders（c-service 侧定价自查「用户所见价=落库价」→ txn internal 落库）；
 * 支付发起走 src/api/pay.ts（商户入网未完成时后端如实 503＋审计 PAY_FAILED）。
 * seed/create async 化照 appointment store C-B3 先例（导出签名保留，铁律-1-B）。
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { http } from '@/utils/request'

export type OrderStatus = 'PENDING_SIGN' | 'PENDING_PAY' | 'PAID' | 'COMPLETED' | 'CANCELLED'
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
  /** C-B4：后端联查 store 表投影，详情页门店行展示用 */
  storeCode?: string
  storeName?: string
  project?: string
}

export const useOrderStore = defineStore('mp-order', () => {
  const orders = ref<COrder[]>([])

  function get(id: string) {
    return orders.value.find((o) => o.id === id || o.orderNo === id)
  }
  function byCustomer(customerId: string) {
    return orders.value.filter((o) => o.customerId === customerId)
  }

  /**
   * 创建订单：POST /c/orders。customerId 由后端按登录态取（入参不含，行级隔离）；
   * 校验 400/404 中文错误由 http 层 toast 原话弹出，此处如实落空返回 null。
   */
  async function create(input: {
    itemId: string
    qty?: number
    storeCode?: string
    storeName?: string
  }): Promise<COrder | null> {
    if (!input.itemId) return null
    try {
      const o = await http.post<COrder>('/c/orders', {
        itemId: input.itemId,
        qty: input.qty ?? 1,
        storeCode: input.storeCode,
        storeName: input.storeName,
      })
      if (o) orders.value.unshift(o)
      return o || null
    } catch {
      return null
    }
  }

  let seeded = false
  async function seed() {
    if (seeded) return
    seeded = true
    try {
      const list = await http.get<COrder[]>('/c/orders', { silent: true })
      if (Array.isArray(list)) orders.value = list
    } catch {
      /* 未登录/后端未就绪：回滚 seeded 允许登录后 onShow 重拉（本地无假数据兜底） */
      seeded = false
    }
  }

  return { orders, get, byCustomer, create, seed }
})
