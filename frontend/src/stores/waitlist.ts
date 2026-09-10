// ============================================================
// 排队智能候补 store（P5-B29 卡①）—— 已接真实 txn-service（/api/txn/waitlist）
// 候补登记 / 取消 / 到场转正式到店；号源释放后后端自动递补首位（NOTIFIED）。
// 适配层（铁律：模板/样式零改动，只换数据源）：
//  - id=wlNo、客户名/掩码手机/项目/期望日期/状态/时间均取后端读模型
//  - 写动作经网关，400/403/409 中文错误经 errMsg() 外露 toast，失败返回 null/false
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { useToast } from '@/composables/useToast'
import { errMsg } from './m5Coupon'
import {
  listWaitlist, registerWaitlist, cancelWaitlist, fulfillWaitlist,
  type WaitlistViewDTO, type WaitlistStatus,
} from '@/api/waitlist'

export interface WaitlistItem {
  id: string
  wlNo: string
  storeCode: string
  storeName: string
  customerId?: string
  customerName: string
  phone: string
  project?: string
  expectDate?: string
  status: WaitlistStatus
  notifiedAt?: string
  ahNo?: string
  operator: string
  note?: string
  createdAt: string
}

const STATUS_LABEL: Record<WaitlistStatus, string> = {
  WAITING: '候补中',
  NOTIFIED: '已通知',
  FULFILLED: '已到场',
  CANCELLED: '已取消',
}

function adapt(d: WaitlistViewDTO): WaitlistItem {
  return {
    id: d.wlNo || d.id,
    wlNo: d.wlNo || d.id,
    storeCode: d.storeCode,
    storeName: d.storeName,
    customerId: d.customerId ?? undefined,
    customerName: d.customerName,
    phone: d.phone,
    project: d.project ?? undefined,
    expectDate: d.expectDate ?? undefined,
    status: d.status as WaitlistStatus,
    notifiedAt: d.notifiedAt ?? undefined,
    ahNo: d.ahNo ?? undefined,
    operator: d.operator,
    note: d.note ?? undefined,
    createdAt: d.createdAt,
  }
}

export const useWaitlistStore = defineStore('waitlist', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()
  const toast = useToast()

  const items = ref<WaitlistItem[]>([])

  /** 看板仅展示活跃候补（WAITING/NOTIFIED），登记时间正序即 FIFO 顺位 */
  const active = computed(() =>
    items.value
      .filter((w) => w.status === 'WAITING' || w.status === 'NOTIFIED')
      .slice()
      .sort((a, b) => new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime()),
  )
  const waitingCount = computed(() => active.value.filter((w) => w.status === 'WAITING').length)
  const notifiedCount = computed(() => active.value.filter((w) => w.status === 'NOTIFIED').length)

  function get(id: string) {
    return items.value.find((w) => w.id === id)
  }

  function upsert(w: WaitlistItem) {
    const idx = items.value.findIndex((x) => x.id === w.id)
    if (idx >= 0) items.value.splice(idx, 1, w)
    else items.value.unshift(w)
  }

  /** 拉取候补队列（默认活跃态、锁当前门店）。页面 onMounted 调用。 */
  async function load(storeCode?: string): Promise<boolean> {
    try {
      const [waiting, notified] = await Promise.all([
        listWaitlist({ storeCode, status: 'WAITING' }),
        listWaitlist({ storeCode, status: 'NOTIFIED' }),
      ])
      items.value = [...(waiting.data ?? []), ...(notified.data ?? [])].map(adapt)
      return true
    } catch (e) {
      items.value = []
      toast.error(errMsg(e, '候补队列加载失败'))
      return false
    }
  }

  async function register(input: {
    customerName: string
    phone: string
    project: string
    expectDate?: string
    note?: string
  }): Promise<WaitlistItem | null> {
    try {
      const res = await registerWaitlist({
        customerName: input.customerName.trim(),
        phone: input.phone.trim(),
        project: input.project.trim(),
        expectDate: input.expectDate || null,
        note: input.note?.trim() || undefined,
      })
      const w = adapt(res.data)
      upsert(w)
      activity.log(auth.user.name, `候补登记 ${w.customerName}：${w.project ?? ''}`, w.id)
      return w
    } catch (e) {
      toast.error(errMsg(e, '候补登记失败'))
      return null
    }
  }

  async function cancel(id: string): Promise<boolean> {
    try {
      const res = await cancelWaitlist(id)
      upsert(adapt(res.data))
      activity.log(auth.user.name, `取消候补 ${res.data.wlNo}`, id)
      await load(auth.user.storeId)
      return true
    } catch (e) {
      toast.error(errMsg(e, '取消候补失败'))
      return false
    }
  }

  /** 客户到场：FULFILLED 并由后端同事务生成正式到店登记（AH）。 */
  async function fulfill(id: string): Promise<WaitlistItem | null> {
    try {
      const res = await fulfillWaitlist(id)
      const w = adapt(res.data)
      upsert(w)
      activity.log(auth.user.name, `候补到场转正式到店 ${w.wlNo}${w.ahNo ? `（${w.ahNo}）` : ''}`, id)
      await load(auth.user.storeId)
      return w
    } catch (e) {
      toast.error(errMsg(e, '到场确认失败'))
      return null
    }
  }

  return {
    items, active, waitingCount, notifiedCount,
    get, load, register, cancel, fulfill, STATUS_LABEL,
  }
})
