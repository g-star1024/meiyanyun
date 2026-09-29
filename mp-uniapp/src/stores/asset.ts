/**
 * 会员资产 store（C 端只读）
 * C-B6 切真（DESIGN-C §四端点 #9）：卡包 GET /c/packages（member_card 投影，
 * 次卡发次数/储值卡余额元），消费记录 GET /c/records（card_ledger 充值/扣次/退款
 * ∪ txn_order 现付单四路归并，ADJUST/TRANSFER 无对应类如实不投；
 * 金额支出负/收入正/扣次 0，按日分组直投页面形状）。
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { http } from '@/utils/request'

export interface MyPackage {
  name: string
  total: number
  used: number
  expire: string
  type: string
  balance?: string
}

export interface RecordLine {
  name: string
  store: string
  amount: number
  note: string
  time: string
  type: string
}

export interface RecordGroup {
  date: string
  list: RecordLine[]
}

export const useAssetStore = defineStore('mp-asset', () => {
  const packages = ref<MyPackage[]>([])
  const records = ref<RecordGroup[]>([])

  let seeded = false
  async function seed() {
    if (seeded) return
    seeded = true
    try {
      const [pk, rec] = await Promise.all([
        http.get<MyPackage[]>('/c/packages', { silent: true }),
        http.get<RecordGroup[]>('/c/records', { silent: true }),
      ])
      if (Array.isArray(pk)) packages.value = pk.map((p) => ({ ...p, expire: (p.expire ?? '').slice(0, 10) }))
      if (Array.isArray(rec)) records.value = rec
    } catch {
      /* 未登录/后端未就绪：回滚 seeded 允许登录后 onShow 重拉（如实空列表） */
      seeded = false
    }
  }

  return { packages, records, seed }
})
