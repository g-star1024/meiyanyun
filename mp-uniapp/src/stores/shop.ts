/**
 * 门店 store（C 端只读）
 * C-B6 切真（DESIGN-C §四端点 #9）：列表 GET /c/stores（营业中过滤投影；
 * addr/hours/phone/distance/rating 系无源字段，如实空值回落 §七），
 * 详情 fetchDetail(id) GET /c/stores/{id}（hotProjects=价目全局 ACTIVE 前 5——
 * store_price 无 store_code 列随拍订正；sold/intro/facilities 无源回落；
 * 非营业中/不存在 404 中文原话由 http 层弹出）。
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { http } from '@/utils/request'

export interface ShopItem {
  id: string
  name: string
  addr: string
  distance: string
  tags: string[]
  hours: string
  phone: string
  rating: number | null
}

export interface ShopDetail extends ShopItem {
  sold: string
  intro: string
  facilities: string[]
  hotProjects: string[]
}

interface ShopRow {
  code: string
  name: string
  tags?: string[]
  addr?: string
  hours?: string
  phone?: string
  distance?: string
  rating?: number | null
  sold?: string | null
  intro?: string
  facilities?: string[]
  hotProjects?: string[]
}

function toItem(x: ShopRow): ShopItem {
  return {
    id: x.code,
    name: x.name,
    addr: x.addr ?? '',
    distance: x.distance ?? '',
    tags: x.tags ?? [],
    hours: x.hours ?? '',
    phone: x.phone ?? '',
    rating: x.rating ?? null,
  }
}

export const useShopStore = defineStore('mp-shop', () => {
  const stores = ref<ShopItem[]>([])

  let seeded = false
  async function seed() {
    if (seeded) return
    seeded = true
    try {
      const list = await http.get<ShopRow[]>('/c/stores', { silent: true })
      if (Array.isArray(list)) stores.value = list.filter((x) => x && x.code).map(toItem)
    } catch {
      /* 未登录/后端未就绪：回滚 seeded 允许登录后 onShow 重拉（如实空列表） */
      seeded = false
    }
  }

  /** 详情（非静默：404「门店不存在或已关店」由 http 层弹出中文原话） */
  async function fetchDetail(id: string): Promise<ShopDetail | null> {
    try {
      const d = await http.get<ShopRow>(`/c/stores/${encodeURIComponent(id)}`)
      if (!d || !d.code) return null
      return {
        ...toItem(d),
        sold: d.sold ?? '',
        intro: d.intro ?? '',
        facilities: d.facilities ?? [],
        hotProjects: d.hotProjects ?? [],
      }
    } catch {
      return null
    }
  }

  return { stores, seed, fetchDetail }
})
