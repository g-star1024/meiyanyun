/**
 * 价目表/项目 store（C 端只读）
 * 数据与 B 端 M2-14 价目表对齐：在售项目名称/分类/原价/会员价/促销价/时长。
 * C-B2 切真：seed() 拉取 GET /c/pricelist（store_price ACTIVE × product_sku 只读投影，
 * id=code=sku，金额元）；fetchOne(id) 拉取 GET /c/projects/{id} 供直进详情页（导出签名保留，铁律-1-B）。
 */
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { http } from '@/utils/request'

export type PriceCategory = 'INJECTION' | 'LASER' | 'SKINCARE' | 'BODY' | 'EXAM'
export interface PriceItem {
  id: string
  code: string
  name: string
  category: PriceCategory
  originalPrice: number
  memberPrice: number
  promoPrice: number | null
  unit: string
  duration: number
}

export const CATEGORY_LABEL: Record<PriceCategory | 'ALL', string> = {
  ALL: '全部',
  INJECTION: '注射美容',
  LASER: '光电仪器',
  SKINCARE: '皮肤管理',
  BODY: '形体管理',
  EXAM: '检测咨询',
}

export const usePricelistStore = defineStore('mp-pricelist', () => {
  const items = ref<PriceItem[]>([])
  const active = computed(() => items.value)

  function get(id: string) {
    return items.value.find((x) => x.id === id)
  }
  function priceOf(p: PriceItem) {
    return p.promoPrice ?? p.memberPrice
  }

  let seeded = false
  async function seed() {
    if (seeded) return
    seeded = true
    try {
      const list = await http.get<PriceItem[]>('/c/pricelist', { silent: true })
      if (Array.isArray(list)) {
        items.value = list.filter((x) => x && x.id && x.category)
      }
    } catch {
      /* 未登录/后端未就绪：如实空列表，页面以空态呈现 */
    }
  }

  /** 直进详情页兜底：store 未命中时按 id(sku) 拉取单项并入库 */
  async function fetchOne(id: string): Promise<PriceItem | null> {
    const hit = get(id)
    if (hit) return hit
    try {
      const d = await http.get<PriceItem>(`/c/projects/${encodeURIComponent(id)}`, { silent: true })
      if (d && d.id) {
        items.value.push(d)
        return d
      }
    } catch {
      /* 404/未登录：如实落空态，页面自显「项目不存在或已下架」 */
    }
    return null
  }

  return { items, active, get, priceOf, seed, fetchOne, CATEGORY_LABEL }
})
