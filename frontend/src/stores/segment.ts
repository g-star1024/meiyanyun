// ============================================================
// Segment AI 客户分群 store（M3-14）
// 分群（高潜/沉睡/价格敏感/高价值/流失风险/...），规则条件、客户数、AI 建议。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

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

export const useSegmentStore = defineStore('segment', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

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
    s.customerCount = s.customerCount + Math.floor(Math.random() * 8 - 3)
    if (s.customerCount < 0) s.customerCount = 0
    s.updatedAt = new Date().toISOString()
    activity.log(auth.user.name, `刷新分群计算：${s.name}（${s.customerCount} 人）`, s.id)
    return true
  }

  function createFollowTask(id: string): boolean {
    const s = segments.value.find((x) => x.id === id)
    if (!s || !auth.can('segment:edit')) return false
    activity.log(auth.user.name, `为分群「${s.name}」一键创建跟进任务（${s.customerCount} 人）`, s.id)
    return true
  }

  function exportMembers(id: string): boolean {
    const s = segments.value.find((x) => x.id === id)
    if (!s || !auth.can('segment:edit')) return false
    activity.log(auth.user.name, `导出分群名单：${s.name}（${s.customerCount} 人）`, s.id)
    return true
  }

  function createSegment(input: {
    name: string
    type: SegmentType
    conditions: string[]
  }): Segment | null {
    if (!auth.can('segment:edit')) return null
    const seg: Segment = {
      id: nextId('seg'),
      name: input.name,
      type: input.type,
      aiStatus: 'RULE',
      ruleSummary: input.conditions.join('、'),
      conditions: input.conditions,
      customerCount: Math.floor(Math.random() * 60) + 20,
      sharePct: 0,
      updatedAt: new Date().toISOString(),
      members: [],
    }
    const total = totalCovered.value + seg.customerCount
    segments.value.forEach((x) => (x.sharePct = Math.round((x.customerCount / total) * 100)))
    seg.sharePct = Math.round((seg.customerCount / total) * 100)
    segments.value.unshift(seg)
    activity.log(auth.user.name, `创建规则分群：${seg.name}`, seg.id)
    return seg
  }

  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    const now = Date.now()
    const ago = (d: number) => new Date(now - d * 86400_000).toISOString()

    const seed: Array<Omit<Segment, 'id' | 'sharePct' | 'members'> & { members: Array<Omit<SegmentMember, 'id'>> }> = [
      {
        name: '高潜转化客户', type: 'HIGH_POTENTIAL', aiStatus: 'AI',
        ruleSummary: '近 30 天到店 ≥ 2 次 · 咨询未购 · 消费 500-3000',
        conditions: ['近 30 天到店 ≥ 2 次', '有咨询记录但未购卡', '累计消费 500-3000 元'],
        customerCount: 86, updatedAt: ago(0.3),
        aiSuggestion: '建议推送体验价项目 + 顾问 1v1 跟进，预计可转化 23%。',
        members: [
          { name: '林婉清', level: '银卡', lastVisit: ago(3), matched: ['到店 2 次', '咨询未购'] },
          { name: '何思雨', level: '银卡', lastVisit: ago(5), matched: ['到店 3 次', '咨询未购'] },
          { name: '罗梓萱', level: '普通', lastVisit: ago(7), matched: ['到店 2 次', '消费 880'] },
          { name: '高雨桐', level: '银卡', lastVisit: ago(9), matched: ['到店 2 次', '咨询未购'] },
        ],
      },
      {
        name: '沉睡 60 天客户', type: 'DORMANT', aiStatus: 'AI',
        ruleSummary: '60 天未到店 · 历史消费 > 2000 · 未在 SOP 中',
        conditions: ['60 天未到店', '历史消费 > 2000 元', '未加入唤醒 SOP'],
        customerCount: 124, updatedAt: ago(0.5),
        aiSuggestion: '建议推送专属唤醒券 + 老客回归礼，券核销率约 18%。',
        members: [
          { name: '孙佳宁', level: '金卡', lastVisit: ago(65), matched: ['65 天未到店', '历史消费 3200'] },
          { name: '许梓涵', level: '银卡', lastVisit: ago(72), matched: ['72 天未到店', '历史消费 2400'] },
          { name: '沈雨薇', level: '金卡', lastVisit: ago(80), matched: ['80 天未到店', '历史消费 5800'] },
        ],
      },
      {
        name: '价格敏感型', type: 'PRICE_SENSITIVE', aiStatus: 'AI',
        ruleSummary: '近 90 天仅在促销下单 · 客单价 < 500 · 多次领券',
        conditions: ['近 90 天仅促销下单', '客单价 < 500', '领券 ≥ 3 张'],
        customerCount: 156, updatedAt: ago(1),
        aiSuggestion: '建议组合促销 + 限时秒杀推送，避免推正价项目。',
        members: [
          { name: '周雅琴', level: '普通', lastVisit: ago(12), matched: ['仅促销下单', '客单 280'] },
          { name: '吴佳怡', level: '普通', lastVisit: ago(18), matched: ['仅促销下单', '领券 5 张'] },
        ],
      },
      {
        name: '高价值 VIP', type: 'HIGH_VALUE', aiStatus: 'AI',
        ruleSummary: '累计消费 > 30000 · 近 180 天到店 ≥ 4 次 · 金卡以上',
        conditions: ['累计消费 > 30000', '近 180 天到店 ≥ 4 次', '等级 ≥ 金卡'],
        customerCount: 42, updatedAt: ago(0.2),
        aiSuggestion: '建议店长 1v1 维护，邀请线下私享会，推荐抗衰年卡。',
        members: [
          { name: '陈美玲', level: '钻石', lastVisit: ago(2), matched: ['累计 68000', '到店 8 次'] },
          { name: '吴思涵', level: '钻石', lastVisit: ago(1), matched: ['累计 52000', '到店 6 次'] },
          { name: '郑晓彤', level: '金卡', lastVisit: ago(5), matched: ['累计 38000', '到店 5 次'] },
        ],
      },
      {
        name: '流失风险客户', type: 'CHURN_RISK', aiStatus: 'AI',
        ruleSummary: '近 30 天差评 · 投诉未闭环 · 90 天未复购',
        conditions: ['近 30 天 NPS ≤ 6', '有未跟进投诉', '90 天未复购'],
        customerCount: 28, updatedAt: ago(0.4),
        aiSuggestion: '建议 24 小时内店长电话回访，提供补救方案，防止流失。',
        members: [
          { name: '吴思涵', level: '钻石', lastVisit: ago(12), matched: ['NPS 3 分', '投诉未跟进'] },
          { name: '黄丽萍', level: '银卡', lastVisit: ago(15), matched: ['NPS 5 分', '反黑投诉'] },
        ],
      },
      {
        name: '本月新客', type: 'NEW', aiStatus: 'RULE',
        ruleSummary: '本月首次到店 · 未办卡',
        conditions: ['本月首次到店', '未办卡'],
        customerCount: 64, updatedAt: ago(2),
        members: [
          { name: '林婉清', level: '普通', lastVisit: ago(4), matched: ['本月首到'] },
          { name: '马欣怡', level: '普通', lastVisit: ago(6), matched: ['本月首到'] },
        ],
      },
    ]

    const total = seed.reduce((s, x) => s + x.customerCount, 0)
    seed.forEach((s) => {
      segments.value.push({
        ...s,
        id: nextId('seg'),
        sharePct: Math.round((s.customerCount / total) * 100),
        members: s.members.map((m) => ({ ...m, id: nextId('sm') })),
      })
    })
  }

  return {
    segments, filterType,
    totalSegments, totalCovered, largest, aiCount,
    filtered,
    get, refresh, createFollowTask, exportMembers, createSegment, seed,
    TYPE_LABEL, TYPE_COLOR, TYPE_ICON,
  }
})
