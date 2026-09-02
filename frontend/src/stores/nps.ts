// ============================================================
// NPS 满意度/NPS store（M3-12）
// 客户 NPS 评分记录（推荐者 9-10 / 被动者 7-8 / 贬损者 0-6），
// 周期 NPS 趋势、回收率、跟进状态。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

export type NpsCategory = 'PROMOTER' | 'PASSIVE' | 'DETRACTOR'
export type NpsFollowStatus = 'PENDING' | 'FOLLOWED'

export interface NpsRecord {
  id: string
  customer: string
  score: number // 0-10
  category: NpsCategory
  service: string // 服务项目
  tags: string[] // 评价标签
  comment: string
  createdAt: string
  followStatus: NpsFollowStatus
  followNote?: string
}

export interface NpsTrendPoint {
  period: string // 如 "2026-W30"
  nps: number
  promoters: number
  passives: number
  detractors: number
  total: number
}

export function categoryOf(score: number): NpsCategory {
  if (score >= 9) return 'PROMOTER'
  if (score >= 7) return 'PASSIVE'
  return 'DETRACTOR'
}

const CATEGORY_LABEL: Record<NpsCategory, string> = {
  PROMOTER: '推荐者',
  PASSIVE: '被动者',
  DETRACTOR: '贬损者',
}

const CATEGORY_COLOR: Record<NpsCategory, string> = {
  PROMOTER: 'var(--c-success-fg)',
  PASSIVE: 'var(--c-warning-fg)',
  DETRACTOR: 'var(--c-danger-fg)',
}

export const useNpsStore = defineStore('nps', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const records = ref<NpsRecord[]>([])
  const trends = ref<NpsTrendPoint[]>([])
  const filterCategory = ref<NpsCategory | 'ALL' | 'PENDING'>('ALL')

  const total = computed(() => records.value.length)
  const promoters = computed(() => records.value.filter((r) => r.category === 'PROMOTER'))
  const passives = computed(() => records.value.filter((r) => r.category === 'PASSIVE'))
  const detractors = computed(() => records.value.filter((r) => r.category === 'DETRACTOR'))
  const pending = computed(() => records.value.filter((r) => r.followStatus === 'PENDING'))

  // NPS = 推荐者% - 贬损者%
  const npsScore = computed(() => {
    if (!total.value) return 0
    const p = (promoters.value.length / total.value) * 100
    const d = (detractors.value.length / total.value) * 100
    return Math.round(p - d)
  })
  const promoterPct = computed(() => (total.value ? Math.round((promoters.value.length / total.value) * 100) : 0))
  const passivePct = computed(() => (total.value ? Math.round((passives.value.length / total.value) * 100) : 0))
  const detractorPct = computed(() => (total.value ? Math.round((detractors.value.length / total.value) * 100) : 0))
  // 回收率 = 评价数 / 假定触达数（种子里触达 = 评价数 * 系数）
  const reachCount = ref(0)
  const responseRate = computed(() => (reachCount.value ? Math.round((total.value / reachCount.value) * 100) : 0))

  const distribution = computed(() => [
    { key: 'PROMOTER' as const, label: '推荐者', count: promoters.value.length, pct: promoterPct.value, color: CATEGORY_COLOR.PROMOTER },
    { key: 'PASSIVE' as const, label: '被动者', count: passives.value.length, pct: passivePct.value, color: CATEGORY_COLOR.PASSIVE },
    { key: 'DETRACTOR' as const, label: '贬损者', count: detractors.value.length, pct: detractorPct.value, color: CATEGORY_COLOR.DETRACTOR },
  ])

  const filtered = computed(() => {
    if (filterCategory.value === 'ALL') return records.value
    if (filterCategory.value === 'PENDING') return pending.value
    return records.value.filter((r) => r.category === filterCategory.value)
  })

  function get(id: string) {
    return records.value.find((r) => r.id === id)
  }

  function markFollowed(id: string, note: string): boolean {
    const r = records.value.find((x) => x.id === id)
    if (!r || !auth.can('nps:edit')) return false
    r.followStatus = 'FOLLOWED'
    r.followNote = note
    activity.log(auth.user.name, `标记 NPS 评价已跟进：${r.customer} ${r.score}分`, r.id)
    return true
  }

  function createFollowTask(id: string): boolean {
    const r = records.value.find((x) => x.id === id)
    if (!r || !auth.can('nps:edit')) return false
    activity.log(auth.user.name, `为 NPS 差评创建跟进任务：${r.customer}（${r.score}分）`, r.id)
    return true
  }

  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    const now = Date.now()
    const daysAgo = (d: number) => new Date(now - d * 86400_000).toISOString()
    const base: Array<Omit<NpsRecord, 'id' | 'category'>> = [
      { customer: '陈美玲', score: 10, service: '热玛吉紧致', tags: ['效果显著', '服务贴心', '环境舒适'], comment: '王医生手法专业，做完半侧脸明显提升，下次还来！', createdAt: daysAgo(1), followStatus: 'FOLLOWED', followNote: '已电话回访' },
      { customer: '赵雨晴', score: 9, service: '水光补水', tags: ['皮肤变好', '不疼'], comment: '护士很温柔，补水效果不错，推荐闺蜜一起来。', createdAt: daysAgo(2), followStatus: 'FOLLOWED' },
      { customer: '孙佳宁', score: 8, service: '光子嫩肤', tags: ['流程顺畅'], comment: '整体还行，就是等了一会儿，希望下次能更快。', createdAt: daysAgo(2), followStatus: 'PENDING' },
      { customer: '林婉清', score: 7, service: '小气泡清洁', tags: ['一般'], comment: '清洁力度一般，和想象有差距，价格略贵。', createdAt: daysAgo(3), followStatus: 'PENDING' },
      { customer: '周雅琴', score: 6, service: '射频紧肤', tags: ['效果不明显', '等待久'], comment: '做完一次没感觉有变化，等了快 40 分钟才轮到。', createdAt: daysAgo(4), followStatus: 'PENDING' },
      { customer: '吴思涵', score: 3, service: '玻尿酸填充', tags: ['疼痛', '态度差', '退款'], comment: '注射时非常疼，咨询师一直推销加项目，体验很差，要求退款！', createdAt: daysAgo(5), followStatus: 'PENDING' },
      { customer: '郑晓彤', score: 10, service: '超声刀', tags: ['专业', '效果好'], comment: '李医生耐心讲解，做完轮廓清晰很多，值得。', createdAt: daysAgo(6), followStatus: 'FOLLOWED' },
      { customer: '黄丽萍', score: 5, service: '皮秒祛斑', tags: ['反黑', '恢复慢'], comment: '做完两周还有红印，担心反黑，希望尽快联系我。', createdAt: daysAgo(7), followStatus: 'PENDING' },
    ]
    base.forEach((r) => {
      records.value.push({
        ...r,
        id: nextId('nps'),
        category: categoryOf(r.score),
      })
    })
    reachCount.value = 42

    // 近 6 周趋势
    const trendRaw = [
      { nps: 42, p: 58, pa: 26, d: 16, t: 88 },
      { nps: 45, p: 60, pa: 25, d: 15, t: 92 },
      { nps: 40, p: 55, pa: 30, d: 15, t: 90 },
      { nps: 48, p: 62, pa: 24, d: 14, t: 95 },
      { nps: 52, p: 64, pa: 24, d: 12, t: 98 },
      { nps: 55, p: 66, pa: 23, d: 11, t: 100 },
    ]
    trends.value = trendRaw.map((t, i) => ({
      period: `W${26 + i}`,
      nps: t.nps,
      promoters: t.p,
      passives: t.pa,
      detractors: t.d,
      total: t.t,
    }))
  }

  return {
    records, trends, filterCategory, reachCount,
    total, promoters, passives, detractors, pending,
    npsScore, promoterPct, passivePct, detractorPct, responseRate,
    distribution, filtered,
    get, markFollowed, createFollowTask, seed,
    CATEGORY_LABEL, CATEGORY_COLOR,
  }
})
