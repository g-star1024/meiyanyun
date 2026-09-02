/**
 * 价目表/项目 store（C 端只读）
 * 数据与 B 端 M2-14 价目表对齐：在售项目名称/分类/原价/会员价/促销价/时长。
 * 后端就绪后 seed() 替换为 GET /c/pricelist?status=ACTIVE。
 */
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

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

let seq = 0
function nextId() {
  seq += 1
  return `pl-${seq}`
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
  function seed() {
    if (seeded) return
    seeded = true
    const data: Array<Omit<PriceItem, 'id'>> = [
      { code: 'IJ-001', name: '玻尿酸填充（瑞蓝2号）', category: 'INJECTION', originalPrice: 6800, memberPrice: 5800, promoPrice: 5280, unit: '支', duration: 30 },
      { code: 'IJ-002', name: '肉毒素（保妥适）', category: 'INJECTION', originalPrice: 4800, memberPrice: 4200, promoPrice: null, unit: '次', duration: 20 },
      { code: 'LS-001', name: '热玛吉FLX 面部', category: 'LASER', originalPrice: 28800, memberPrice: 25800, promoPrice: 23800, unit: '次', duration: 90 },
      { code: 'LS-002', name: '超声炮（半岛）', category: 'LASER', originalPrice: 19800, memberPrice: 17800, promoPrice: 16800, unit: '次', duration: 70 },
      { code: 'LS-003', name: '光子嫩肤（M22）', category: 'LASER', originalPrice: 1980, memberPrice: 1680, promoPrice: 1280, unit: '次', duration: 30 },
      { code: 'SK-001', name: '水光针（基础）', category: 'SKINCARE', originalPrice: 980, memberPrice: 780, promoPrice: 580, unit: '次', duration: 30 },
      { code: 'BD-001', name: '冷冻溶脂（单部位）', category: 'BODY', originalPrice: 8800, memberPrice: 7800, promoPrice: null, unit: '部位', duration: 60 },
      { code: 'BD-002', name: 'BTL 美体塑形', category: 'BODY', originalPrice: 1280, memberPrice: 980, promoPrice: 780, unit: '次', duration: 40 },
      { code: 'EX-001', name: 'VISIA 皮肤检测', category: 'EXAM', originalPrice: 200, memberPrice: 0, promoPrice: null, unit: '次', duration: 15 },
    ]
    data.forEach((d) => items.value.push({ id: nextId(), ...d }))
  }

  return { items, active, get, priceOf, seed, CATEGORY_LABEL }
})
