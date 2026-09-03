// ============================================================
// M5-05 直播/短视频 store（已接真实 marketing-service）
// - 直播场次：平台（抖音/视频号）、状态（未开始/直播中/已结束）、观看/点击/成交漏斗
// - 短视频库：平台、播放量、点赞、挂链成交
// - 创建直播时从 m1.coupons 选挂载团购/券；简介由 view 调 checkSensitive
// 权限：live:view / live:edit
//
// 适配层（铁律：模板/样式零改动，只换数据源）：
//  - 后端 dealAmount bigint 存「分」，前端活规格用「元」：fen2yuan
//  - startTime 为 LocalDateTime（ISO 'yyyy-MM-ddTHH:mm'），前端展示 'yyyy-MM-dd HH:mm'
//  - mountedCouponIds / tags 为 JSON 数组字符串（parse 失败回落 []）
//  - 字段名 sessionId/videoId → id；券名解析仍走 m1.coupons
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useM1MarketingStore } from '@/stores/m1Marketing'
import { useActivityStore } from '@/stores/activity'
import { useAuthStore } from '@/stores/auth'
import * as api from '@/api/marketing'
import type { LiveSessionDTO, ShortVideoDTO } from '@/api/marketing'
import { fen2yuan } from '@/stores/m5Coupon'

export type LivePlatform = 'DOUYIN' | 'WECHAT_CHANNEL'
export type LiveStatus = 'NOT_STARTED' | 'LIVE' | 'ENDED'
export type VideoPlatform = 'DOUYIN' | 'WECHAT_CHANNEL' | 'XIAOHONGSHU'

/** yyyy-MM-dd（LocalDate / OffsetDateTime 均可直接截取） */
function dayOf(s?: string | null): string {
  return s ? s.slice(0, 10) : ''
}
/** LocalDateTime ISO（'yyyy-MM-ddTHH:mm:ss'）→ 前端 'yyyy-MM-dd HH:mm' */
function minuteOf(s?: string | null): string {
  return s ? s.slice(0, 16).replace('T', ' ') : ''
}
/** JSON 数组字符串 → string[]（失败回落 []） */
function jsonArr(s?: string | null): string[] {
  if (!s) return []
  try {
    const v = JSON.parse(s)
    return Array.isArray(v) ? v.map((x) => String(x)) : []
  } catch {
    return []
  }
}

export interface LiveFunnel {
  views: number
  clicks: number
  deals: number
}

export interface LiveSession {
  id: string
  title: string
  platform: LivePlatform
  status: LiveStatus
  startTime: string
  viewers: number
  linkClicks: number
  dealCount: number
  dealAmount: number
  mountedCouponIds: string[]
  intro: string
  host: string
}

export interface ShortVideo {
  id: string
  title: string
  platform: VideoPlatform
  plays: number
  likes: number
  dealCount: number
  dealAmount: number
  tags: string[]
  publishedAt: string
}

export const PLATFORM_LABEL: Record<LivePlatform, string> = {
  DOUYIN: '抖音', WECHAT_CHANNEL: '视频号',
}
export const VIDEO_PLATFORM_LABEL: Record<VideoPlatform, string> = {
  DOUYIN: '抖音', WECHAT_CHANNEL: '视频号', XIAOHONGSHU: '小红书',
}
export const LIVE_STATUS_LABEL: Record<LiveStatus, string> = {
  NOT_STARTED: '未开始', LIVE: '直播中', ENDED: '已结束',
}
export const LIVE_STATUS_PILL: Record<LiveStatus, 'default' | 'success' | 'disabled'> = {
  NOT_STARTED: 'default', LIVE: 'success', ENDED: 'disabled',
}

/** 后端直播场次 → 前端活规格（分→元、时间格式化、挂载券 JSON.parse） */
export function adaptSession(d: LiveSessionDTO): LiveSession {
  return {
    id: d.sessionId,
    title: d.title || '',
    platform: d.platform as LivePlatform,
    status: (d.status || 'NOT_STARTED') as LiveStatus,
    startTime: minuteOf(d.startTime),
    viewers: d.viewers ?? 0,
    linkClicks: d.linkClicks ?? 0,
    dealCount: d.dealCount ?? 0,
    dealAmount: fen2yuan(d.dealAmount),
    mountedCouponIds: jsonArr(d.mountedCouponIds),
    intro: d.intro || '',
    host: d.host || '运营',
  }
}

/** 后端短视频 → 前端活规格（分→元、标签 JSON.parse、发布日直取） */
export function adaptVideo(d: ShortVideoDTO): ShortVideo {
  return {
    id: d.videoId,
    title: d.title || '',
    platform: d.platform as VideoPlatform,
    plays: d.plays ?? 0,
    likes: d.likes ?? 0,
    dealCount: d.dealCount ?? 0,
    dealAmount: fen2yuan(d.dealAmount),
    tags: jsonArr(d.tags),
    publishedAt: dayOf(d.publishedAt),
  }
}

export const useM5LiveStore = defineStore('m5Live', () => {
  const m1 = useM1MarketingStore()
  const activity = useActivityStore()
  const auth = useAuthStore()

  const sessions = ref<LiveSession[]>([])
  const videos = ref<ShortVideo[]>([])
  const filterStatus = ref<'ALL' | LiveStatus>('ALL')
  const loaded = ref(false)

  const filteredSessions = computed(() => {
    if (filterStatus.value === 'ALL') return sessions.value
    return sessions.value.filter((s) => s.status === filterStatus.value)
  })

  function get(id: string) {
    return sessions.value.find((s) => s.id === id) ?? null
  }

  // KPI
  const liveCount = computed(() => sessions.value.filter((s) => s.status === 'LIVE').length)
  const monthSessions = computed(() => sessions.value.length)
  const totalViews = computed(() => sessions.value.reduce((s, x) => s + x.viewers, 0))
  const totalDealAmount = computed(() => sessions.value.reduce((s, x) => s + x.dealAmount, 0))

  // 各场成交对比（CBarChart items）
  const dealChartItems = computed(() =>
    [...sessions.value]
      .sort((a, b) => b.dealAmount - a.dealAmount)
      .slice(0, 8)
      .map((s) => ({ label: s.title.length > 8 ? s.title.slice(0, 8) + '…' : s.title, values: [s.dealAmount] })),
  )

  async function createSession(input: {
    title: string
    platform: LivePlatform
    startTime: string
    mountedCouponIds: string[]
    intro: string
  }): Promise<LiveSession> {
    if (!auth.can('live:edit')) throw new Error('无直播编辑权限')
    const cmd: api.SessionCmd = {
      title: input.title,
      platform: input.platform,
      startTime: input.startTime,
      mountedCouponIds: input.mountedCouponIds,
      intro: input.intro,
    }
    const res = await api.createLiveSession(cmd)
    activity.log(
      auth.user?.name ?? '运营',
      `创建直播「${input.title}」（${PLATFORM_LABEL[input.platform]}）`,
      res.data.sessionId,
    )
    await seed(true)
    return sessions.value.find((s) => s.id === res.data.sessionId) ?? adaptSession(res.data)
  }

  /** 开播：NOT_STARTED → LIVE（后端幂等 changed；实际翻转才审计） */
  async function startLive(id: string) {
    if (!auth.can('live:edit')) throw new Error('无直播编辑权限')
    const s = sessions.value.find((x) => x.id === id)
    const res = await api.startLiveSession(id)
    await seed(true)
    if (res.data.changed && s) {
      activity.log(auth.user?.name ?? '运营', `开播「${s.title}」`, id)
    }
  }

  /** 结束直播：LIVE → ENDED（后端幂等 changed；实际翻转才审计，成交单数取重拉后数据） */
  async function endLive(id: string) {
    if (!auth.can('live:edit')) throw new Error('无直播编辑权限')
    const before = sessions.value.find((x) => x.id === id)
    const res = await api.endLiveSession(id)
    await seed(true)
    if (res.data.changed) {
      const after = sessions.value.find((x) => x.id === id)
      activity.log(
        auth.user?.name ?? '运营',
        `结束直播「${after?.title ?? before?.title ?? id}」，成交 ${after?.dealCount ?? 0} 单`,
        id,
      )
    }
  }

  /** 拉取真实场次 + 短视频（幂等：已加载默认不重复，force 用于写后重拉）；券名解析依赖 m1.coupons */
  async function seed(force = false) {
    if (loaded.value && !force) return
    await m1.seed()
    const [sessionRes, videoRes] = await Promise.all([api.listLiveSessions(), api.listShortVideos()])
    sessions.value = sessionRes.data.map(adaptSession)
    videos.value = videoRes.data.map(adaptVideo)
    loaded.value = true
  }

  return {
    sessions, videos, filterStatus, loaded,
    filteredSessions, get,
    liveCount, monthSessions, totalViews, totalDealAmount, dealChartItems,
    createSession, startLive, endLive,
    PLATFORM_LABEL, VIDEO_PLATFORM_LABEL, LIVE_STATUS_LABEL, LIVE_STATUS_PILL,
    seed,
  }
})
