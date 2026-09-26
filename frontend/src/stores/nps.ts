// ============================================================
// NPS 满意度/NPS store（M3-12，适配层切真）
// 数据源：/api/customer/m3/nps/records|summary|trends（customer-service / nps_record）。
// 适配层铁律：导出签名全保留（records/trends/filterCategory/reachCount/total/promoters/
// passives/detractors/pending/npsScore/promoterPct/passivePct/detractorPct/responseRate/
// distribution/filtered/get/markFollowed/createFollowTask/seed/CATEGORY_LABEL/CATEGORY_COLOR），
// NpsView template/style 零改动。
// KPI（total/npsScore/pct/pending）继续由 records 本地聚合（与 /summary 同库同口径）；
// reachCount 由 /summary 下发（m3_settings.npsReachCount）；趋势由 /trends 后端真实聚合。
// markFollowed 保持同步 boolean 签名：乐观更新 + fire-and-forget POST，失败回滚。
// createFollowTask 维持本地动态（M3-B2 任务 API 接线前移交项）。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { fetchNpsRecords, fetchNpsSummary, fetchNpsTrends, followNpsRecord } from '@/api/nps'
import type { NpsRecordRow } from '@/api/nps'
import { useActivityStore } from './activity'
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

function mapRow(row: NpsRecordRow): NpsRecord {
  return {
    id: row.recordNo,
    customer: row.customerName,
    score: row.score,
    category: row.category,
    service: row.service ?? '',
    tags: row.tags ?? [],
    comment: row.comment ?? '',
    createdAt: row.createdAt,
    followStatus: row.followStatus,
    followNote: row.followNote ?? undefined,
  }
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
  // 回收率 = 评价数 / 触达数（触达数由 m3_settings.npsReachCount 下发）
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
    void pushFollow(id, note)
    return true
  }

  async function pushFollow(id: string, note: string) {
    try {
      await followNpsRecord(id, note)
    } catch (e) {
      console.warn('[nps] follow failed', e)
      const r = records.value.find((x) => x.id === id)
      if (r) {
        r.followStatus = 'PENDING'
        delete r.followNote
      }
    }
  }

  function createFollowTask(id: string): boolean {
    const r = records.value.find((x) => x.id === id)
    if (!r || !auth.can('nps:edit')) return false
    activity.log(auth.user.name, `为 NPS 差评创建跟进任务：${r.customer}（${r.score}分）`, r.id)
    return true
  }

  let seeded = false
  async function seed() {
    if (seeded) return
    seeded = true
    try {
      const [recordsResp, summaryResp, trendsResp] = await Promise.all([
        fetchNpsRecords(),
        fetchNpsSummary(),
        fetchNpsTrends(),
      ])
      records.value = recordsResp.data.map(mapRow)
      reachCount.value = summaryResp.data.reachCount
      trends.value = trendsResp.data.map((t) => ({
        period: t.period,
        nps: t.nps,
        promoters: t.promoters,
        passives: t.passives,
        detractors: t.detractors,
        total: t.total,
      }))
    } catch (e) {
      seeded = false
      console.warn('[nps] seed failed', e)
    }
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
