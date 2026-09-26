// ============================================================
// Segment AI 客户分群 store（M3-14 / M3-B3 切真）
// 数据源：customer-service /api/customer/m3/segments（segment_def + RULE 实时计算）。
// 契约（DESIGN-M3 §4 B3）：六 kind 结构化条件（DORMANT_DAYS / VISIT_IN_DAYS /
// SPEND_RANGE / LEVEL_GTE / TAG_ANY / CREATED_IN_DAYS），label 为人读文案直展；
// 导出名单 = 命中成员一键回推标签（apply-tags，D3-3）；
// 跟进任务 = 命中批量下发 marketing（软降级，idemKey 当日防重）。
// 权限：segment:view / segment:edit。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { useToast } from '@/composables/useToast'
import { errMsg } from './m5Coupon'
import {
  listSegments, createSegment as apiCreateSegment, refreshSegment,
  followTasksBySegment, applySegmentTags,
  type SegmentView, type SegmentCond,
} from '@/api/segment'

export type SegmentType = 'HIGH_POTENTIAL' | 'DORMANT' | 'PRICE_SENSITIVE' | 'HIGH_VALUE' | 'CHURN_RISK' | 'NEW'
export type AiStatus = 'AI' | 'RULE'

export interface SegmentMember {
  id: string
  name: string
  level: string
  lastVisit: string
  matched: string[] // 命中条件点
}

export interface Segment {
  id: string
  name: string
  type: SegmentType
  aiStatus: AiStatus
  ruleSummary: string
  conditions: string[]
  customerCount: number
  sharePct: number // 占比 0-100
  updatedAt: string
  aiSuggestion?: string
  members: SegmentMember[]
}

const TYPE_LABEL: Record<SegmentType, string> = {
  HIGH_POTENTIAL: '高潜客户',
  DORMANT: '沉睡客户',
  PRICE_SENSITIVE: '价格敏感',
  HIGH_VALUE: '高价值',
  CHURN_RISK: '流失风险',
  NEW: '新客',
}

const TYPE_COLOR: Record<SegmentType, { bg: string; fg: string }> = {
  HIGH_POTENTIAL: { bg: 'var(--c-brand-soft)', fg: 'var(--c-brand)' },
  DORMANT: { bg: 'var(--c-warning-bg)', fg: 'var(--c-warning-fg)' },
  PRICE_SENSITIVE: { bg: 'var(--c-orange-soft, rgba(234, 88, 12, 0.12))', fg: 'var(--c-orange-dark)' },
  HIGH_VALUE: { bg: 'var(--c-success-bg)', fg: 'var(--c-success-fg)' },
  CHURN_RISK: { bg: 'var(--c-danger-bg)', fg: 'var(--c-danger-fg)' },
  NEW: { bg: 'var(--c-teal-soft, rgba(14, 165, 164, 0.12))', fg: 'var(--c-teal-dark)' },
}

const TYPE_ICON: Record<SegmentType, string> = {
  HIGH_POTENTIAL: 'trend-up',
  DORMANT: 'clock',
  PRICE_SENSITIVE: 'marketing',
  HIGH_VALUE: 'customer',
  CHURN_RISK: 'alert',
  NEW: 'user-check',
}

const SEG_TYPES = new Set<string>(['HIGH_POTENTIAL', 'DORMANT', 'PRICE_SENSITIVE', 'HIGH_VALUE', 'CHURN_RISK', 'NEW'])

function mapView(v: SegmentView): Segment {
  return {
    id: String(v.id),
    name: v.name,
    type: (SEG_TYPES.has(v.type) ? v.type : 'HIGH_POTENTIAL') as SegmentType,
    aiStatus: v.aiStatus === 'AI' ? 'AI' : 'RULE',
    ruleSummary: v.ruleSummary || '',
    conditions: v.conditions ?? [],
    customerCount: v.customerCount ?? 0,
    sharePct: Math.round(Number(v.sharePct ?? 0)),
    updatedAt: v.updatedAt ?? new Date().toISOString(),
    aiSuggestion: v.aiSuggestion ?? undefined,
    members: (v.members ?? []).map((m) => ({
      id: m.id,
      name: m.name,
      level: m.level,
      lastVisit: m.lastVisit,
      matched: m.matched ?? [],
    })),
  }
}

export const useSegmentStore = defineStore('segment', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()
  const toast = useToast()

  const segments = ref<Segment[]>([])
  const filterType = ref<SegmentType | 'ALL'>('ALL')

  const totalSegments = computed(() => segments.value.length)
  const totalCovered = computed(() => segments.value.reduce((s, x) => s + x.customerCount, 0))
  const largest = computed(() => segments.value.reduce((a, b) => (a.customerCount >= b.customerCount ? a : b), segments.value[0]))
  const aiCount = computed(() => segments.value.filter((s) => s.aiStatus === 'AI' && s.aiSuggestion).length)

  const filtered = computed(() => {
    if (filterType.value === 'ALL') return segments.value
    return segments.value.filter((s) => s.type === filterType.value)
  })

  function get(id: string) { return segments.value.find((s) => s.id === id) }

  function refresh(id: string): boolean {
    const s = segments.value.find((x) => x.id === id)
    if (!s || !auth.can('segment:edit')) return false
    refreshSegment(Number(id))
      .then((r) => {
        s.customerCount = r.customerCount
        s.sharePct = Math.round(Number(r.sharePct ?? 0))
        s.updatedAt = new Date().toISOString()
        activity.log(auth.user.name, `刷新分群计算：${s.name}（${s.customerCount} 人）`, s.id)
        toast.success(`分群「${s.name}」已重算：命中 ${r.customerCount} 人（扫描 ${r.scanned}）`)
      })
      .catch((e) => toast.error(errMsg(e, '分群重算失败')))
    return true
  }

  function createFollowTask(id: string): boolean {
    const s = segments.value.find((x) => x.id === id)
    if (!s || !auth.can('segment:edit')) return false
    followTasksBySegment(Number(id))
      .then((r) => {
        activity.log(auth.user.name, `为分群「${s.name}」一键创建跟进任务（${r.created}/${r.matched} 人）`, s.id)
        toast.success(`跟进任务已下发 ${r.created} 个（命中 ${r.matched} 人，当日幂等防重）`)
      })
      .catch((e) => toast.error(errMsg(e, '跟进任务下发失败')))
    return true
  }

  // 导出名单 = 命中成员一键回推标签（apply-tags，D3-3：分群结果回写客户标签）
  function exportMembers(id: string): boolean {
    const s = segments.value.find((x) => x.id === id)
    if (!s || !auth.can('segment:edit')) return false
    applySegmentTags(Number(id))
      .then((r) => {
        activity.log(auth.user.name, `分群「${s.name}」回推标签 ${r.tagId}（${r.assigned}/${r.matched} 人）`, s.id)
        toast.success(`已回推标签并打标 ${r.assigned}/${r.matched} 人（标签 ${r.tagId}）`)
      })
      .catch((e) => toast.error(errMsg(e, '回推标签失败')))
    return true
  }

  async function createSegment(input: {
    name: string
    type: SegmentType
    conds: SegmentCond[]
  }): Promise<Segment | null> {
    if (!auth.can('segment:edit')) return null
    const clientToken = `seg-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
    try {
      const v = await apiCreateSegment({
        name: input.name,
        type: input.type,
        conditions: input.conds,
        clientToken,
      })
      const seg = mapView(v)
      segments.value.unshift(seg)
      activity.log(auth.user.name, `创建规则分群：${seg.name}（命中 ${seg.customerCount} 人）`, seg.id)
      toast.success(`分群「${seg.name}」已创建，实时命中 ${seg.customerCount} 人`)
      return seg
    } catch (e) {
      toast.error(errMsg(e, '分群创建失败'))
      return null
    }
  }

  // ===== 加载（切真：customer /api/customer/m3/segments；保留 seed 名兼容视图入口） =====
  let seeded = false
  async function seed() {
    if (seeded) return
    seeded = true
    try {
      const rows = await listSegments()
      segments.value = rows.map(mapView)
    } catch (e) {
      seeded = false
      toast.error(errMsg(e, '客群分群加载失败'))
    }
  }

  return {
    segments, filterType,
    totalSegments, totalCovered, largest, aiCount,
    filtered,
    get, refresh, createFollowTask, exportMembers, createSegment, seed,
    TYPE_LABEL, TYPE_COLOR, TYPE_ICON,
  }
})
