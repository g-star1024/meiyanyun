// ============================================================
// 价目表管理 store（M2-14）
// 门店项目价目：名称、分类、原价/会员价/活动价、状态（启用/停用/待审批）。
// 调价走审批：状态从启用 → 待审批，审批通过后生效。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

export type PriceCategory = 'INJECTION' | 'LASER' | 'SKINCARE' | 'BODY' | 'EXAM'
export type PriceStatus = 'ACTIVE' | 'DISABLED' | 'PENDING'

/**
 * 项目医疗风险标签（面诊禁忌初筛用，配合 useCompliance）：
 * INJECTION 注射有创 / LASER 光电 / HIGH_ENERGY 高能量抗衰 /
 * ANESTHESIA 需表面麻醉 / PREGNANCY_RISK 孕期禁忌。
 */
export type RiskTag = 'INJECTION' | 'LASER' | 'HIGH_ENERGY' | 'ANESTHESIA' | 'PREGNANCY_RISK'

export interface PriceItem {
  id: string
  code: string           // 项目编码
  name: string
  category: PriceCategory
  originalPrice: number
  memberPrice: number
  promoPrice: number | null
  unit: string           // 次/部位/支
  duration: number       // 服务时长（分钟）
  status: PriceStatus
  riskTags?: RiskTag[]
  updatedAt: string
  updatedBy: string
  pendingPrice?: { memberPrice: number; promoPrice: number | null; reason: string; requestedAt: string; requestedBy: string }
}

const CATEGORY_LABEL: Record<PriceCategory, string> = {
  INJECTION: '注射美容',
  LASER: '光电仪器',
  SKINCARE: '皮肤管理',
  BODY: '形体管理',
  EXAM: '检测咨询',
}
const STATUS_LABEL: Record<PriceStatus, string> = {
  ACTIVE: '启用',
  DISABLED: '停用',
  PENDING: '待审批',
}
const STATUS_PILL: Record<PriceStatus, 'success' | 'disabled' | 'warning'> = {
  ACTIVE: 'success',
  DISABLED: 'disabled',
  PENDING: 'warning',
}

export const usePricelistStore = defineStore('pricelist', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const items = ref<PriceItem[]>([])
  const filterCategory = ref<PriceCategory | 'ALL'>('ALL')
  const filterStatus = ref<PriceStatus | 'ALL'>('ALL')
  const keyword = ref('')

  const active = computed(() => items.value.filter((x) => x.status === 'ACTIVE'))
  const pending = computed(() => items.value.filter((x) => x.status === 'PENDING'))
  const disabled = computed(() => items.value.filter((x) => x.status === 'DISABLED'))

  const filtered = computed(() => {
    let list = items.value
    if (filterCategory.value !== 'ALL') list = list.filter((x) => x.category === filterCategory.value)
    if (filterStatus.value !== 'ALL') list = list.filter((x) => x.status === filterStatus.value)
    const kw = keyword.value.trim().toLowerCase()
    if (kw) list = list.filter((x) => x.name.toLowerCase().includes(kw) || x.code.toLowerCase().includes(kw))
    return [...list].sort((a, b) => a.code.localeCompare(b.code))
  })

  function get(id: string) {
    return items.value.find((x) => x.id === id)
  }

  function requestPriceChange(
    id: string,
    patch: { memberPrice: number; promoPrice: number | null; reason: string },
  ): boolean {
    const it = items.value.find((x) => x.id === id)
    if (!it || it.status === 'PENDING' || !auth.can('pricelist:edit')) return false
    it.pendingPrice = {
      memberPrice: Math.max(0, Math.round(patch.memberPrice)),
      promoPrice: patch.promoPrice == null ? null : Math.max(0, Math.round(patch.promoPrice)),
      reason: patch.reason,
      requestedAt: new Date().toISOString(),
      requestedBy: auth.user.name,
    }
    it.status = 'PENDING'
    it.updatedAt = new Date().toISOString()
    it.updatedBy = auth.user.name
    activity.log(auth.user.name, `提交 ${it.name} 调价审批：会员价 ¥${it.pendingPrice!.memberPrice}`, it.id)
    return true
  }

  function approvePriceChange(id: string): boolean {
    const it = items.value.find((x) => x.id === id)
    if (!it || !it.pendingPrice || !auth.can('pricelist:edit')) return false
    it.memberPrice = it.pendingPrice.memberPrice
    it.promoPrice = it.pendingPrice.promoPrice
    it.pendingPrice = undefined
    it.status = 'ACTIVE'
    it.updatedAt = new Date().toISOString()
    it.updatedBy = auth.user.name
    activity.log(auth.user.name, `审批通过 ${it.name} 调价，新会员价已生效`, it.id)
    return true
  }

  function rejectPriceChange(id: string): boolean {
    const it = items.value.find((x) => x.id === id)
    if (!it || !it.pendingPrice || !auth.can('pricelist:edit')) return false
    it.pendingPrice = undefined
    it.status = 'ACTIVE'
    it.updatedAt = new Date().toISOString()
    it.updatedBy = auth.user.name
    activity.log(auth.user.name, `驳回 ${it.name} 调价申请`, it.id)
    return true
  }

  function toggleStatus(id: string): boolean {
    const it = items.value.find((x) => x.id === id)
    if (!it || it.status === 'PENDING' || !auth.can('pricelist:edit')) return false
    it.status = it.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
    it.updatedAt = new Date().toISOString()
    it.updatedBy = auth.user.name
    activity.log(auth.user.name, `${it.status === 'ACTIVE' ? '启用' : '停用'}项目 ${it.name}`, it.id)
    return true
  }

  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    const now = new Date().toISOString()
    const data: Array<Omit<PriceItem, 'id'>> = [
      { code: 'IJ-001', name: '玻尿酸填充（瑞蓝2号）', category: 'INJECTION', originalPrice: 6800, memberPrice: 5800, promoPrice: 5280, unit: '支', duration: 30, status: 'ACTIVE', riskTags: ['INJECTION'], updatedAt: now, updatedBy: '苏晴' },
      { code: 'IJ-002', name: '肉毒素（保妥适）', category: 'INJECTION', originalPrice: 4800, memberPrice: 4200, promoPrice: null, unit: '次', duration: 20, status: 'ACTIVE', riskTags: ['INJECTION'], updatedAt: now, updatedBy: '苏晴' },
      { code: 'IJ-003', name: '少女针（艾维岚）', category: 'INJECTION', originalPrice: 18800, memberPrice: 16800, promoPrice: null, unit: '支', duration: 40, status: 'PENDING', riskTags: ['INJECTION'], updatedAt: now, updatedBy: '苏晴',
        pendingPrice: { memberPrice: 15800, promoPrice: 14800, reason: '暑期抗衰活动，需配合整体促销方案下调', requestedAt: now, requestedBy: '苏晴' } },
      { code: 'LS-001', name: '热玛吉FLX 面部', category: 'LASER', originalPrice: 28800, memberPrice: 25800, promoPrice: 23800, unit: '次', duration: 90, status: 'ACTIVE', riskTags: ['LASER', 'HIGH_ENERGY', 'PREGNANCY_RISK'], updatedAt: now, updatedBy: '苏晴' },
      { code: 'LS-002', name: '超声炮（半岛）', category: 'LASER', originalPrice: 19800, memberPrice: 17800, promoPrice: 16800, unit: '次', duration: 70, status: 'ACTIVE', riskTags: ['LASER', 'HIGH_ENERGY', 'PREGNANCY_RISK'], updatedAt: now, updatedBy: '苏晴' },
      { code: 'LS-003', name: '光子嫩肤（M22）', category: 'LASER', originalPrice: 1980, memberPrice: 1680, promoPrice: 1280, unit: '次', duration: 30, status: 'ACTIVE', riskTags: ['LASER'], updatedAt: now, updatedBy: '苏晴' },
      { code: 'SK-001', name: '水光针（基础）', category: 'SKINCARE', originalPrice: 980, memberPrice: 780, promoPrice: 580, unit: '次', duration: 30, status: 'ACTIVE', riskTags: ['INJECTION', 'ANESTHESIA'], updatedAt: now, updatedBy: '苏晴' },
      { code: 'SK-002', name: '小气泡深层清洁', category: 'SKINCARE', originalPrice: 380, memberPrice: 280, promoPrice: 198, unit: '次', duration: 45, status: 'DISABLED', updatedAt: now, updatedBy: '苏晴' },
      { code: 'BD-001', name: '冷冻溶脂（单部位）', category: 'BODY', originalPrice: 8800, memberPrice: 7800, promoPrice: null, unit: '部位', duration: 60, status: 'ACTIVE', riskTags: ['HIGH_ENERGY', 'PREGNANCY_RISK'], updatedAt: now, updatedBy: '苏晴' },
      { code: 'BD-002', name: 'BTL 美体塑形', category: 'BODY', originalPrice: 1280, memberPrice: 980, promoPrice: 780, unit: '次', duration: 40, status: 'PENDING', riskTags: ['PREGNANCY_RISK'], updatedAt: now, updatedBy: '苏晴',
        pendingPrice: { memberPrice: 880, promoPrice: null, reason: '新客拓客，下调体验价', requestedAt: now, requestedBy: '苏晴' } },
      { code: 'EX-001', name: 'VISIA 皮肤检测', category: 'EXAM', originalPrice: 200, memberPrice: 0, promoPrice: null, unit: '次', duration: 15, status: 'ACTIVE', updatedAt: now, updatedBy: '苏晴' },
    ]
    data.forEach((d) => items.value.push({ id: nextId('pl'), ...d }))
  }

  return {
    items, filterCategory, filterStatus, keyword,
    active, pending, disabled, filtered,
    get, requestPriceChange, approvePriceChange, rejectPriceChange, toggleStatus, seed,
    CATEGORY_LABEL, STATUS_LABEL, STATUS_PILL,
  }
})
