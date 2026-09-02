// 开发期种子数据：用独立领域 store 重建闭环样板初始状态。
// 真实环境由后端 API 提供，本文件仅用于 /closed-loop 演示与本地开发。
import { useCustomerStore } from '@/stores/customer'
import { useArrivalStore } from '@/stores/arrival'

let seeded = false

export function seedClinicData() {
  if (seeded) return
  seeded = true

  const customer = useCustomerStore()
  const arrival = useArrivalStore()

  // customer store 已内置 3 个种子客户（C-201/202/203）
  const c201 = customer.get('C-201')!
  const c202 = customer.get('C-202')!

  arrival.checkIn({ customerId: c201.id, channel: 'WALK_IN' })
  arrival.checkIn({ customerId: c202.id, channel: 'REFERRAL' })
}
