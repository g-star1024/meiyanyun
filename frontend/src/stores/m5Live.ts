// ============================================================
// M5-05 直播/短视频 store
// - 直播场次：平台（抖音/视频号）、状态（未开始/直播中/已结束）、观看/点击/成交漏斗
// - 短视频库：平台、播放量、点赞、挂链成交
// - 创建直播时从 m1.coupons 选挂载团购/券；简介由 view 调 checkSensitive
// 权限：live:view / live:edit
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useM1MarketingStore } from '@/stores/m1Marketing'
import { useActivityStore } from '@/stores/activity'
import { useAuthStore } from '@/stores/auth'

export type LivePlatform = 'DOUYIN' | 'WECHAT_CHANNEL'
export type LiveStatus = 'NOT_STARTED' | 'LIVE' | 'ENDED'
export type VideoPlatform = 'DOUYIN' | 'WECHAT_CHANNEL' | 'XIAOHONGSHU'

let _id = 0
function nextId(p: string) { _id += 1; return `${p}-${Date.now().toString(36)}-${_id}` }
function dayOffset(n: number) {
  const d = new Date(); d.setDate(d.getDate() + n)
  return d.toISOString().slice(0, 10)
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

export const useM5LiveStore = defineStore('m5Live', () => {
  const m1 = useM1MarketingStore()
  const activity = useActivityStore()
  const auth = useAuthStore()

  const sessions = ref<LiveSession[]>([])
  const videos = ref<ShortVideo[]>([])
  const filterStatus = ref<'ALL' | LiveStatus>('ALL')
  const seeded = ref(false)

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

  function createSession(input: {
    title: string
    platform: LivePlatform
    startTime: string
    mountedCouponIds: string[]
    intro: string
  }): LiveSession {
    const s: LiveSession = {
      id: nextId('lv'),
      title: input.title,
      platform: input.platform,
      status: 'NOT_STARTED',
      startTime: input.startTime,
      viewers: 0,
      linkClicks: 0,
      dealCount: 0,
      dealAmount: 0,
      mountedCouponIds: input.mountedCouponIds,
      intro: input.intro,
      host: auth.user?.name ?? '运营',
    }
    sessions.value.unshift(s)
    activity.log(auth.user?.name ?? '运营', `创建直播「${s.title}」（${PLATFORM_LABEL[s.platform]}）`, s.id)
    return s
  }

  function startLive(id: string) {
    const s = sessions.value.find((x) => x.id === id)
    if (s && s.status === 'NOT_STARTED') {
      s.status = 'LIVE'
      activity.log(auth.user?.name ?? '运营', `开播「${s.title}」`, s.id)
    }
  }
  function endLive(id: string) {
    const s = sessions.value.find((x) => x.id === id)
    if (s && s.status === 'LIVE') {
      s.status = 'ENDED'
      activity.log(auth.user?.name ?? '运营', `结束直播「${s.title}」，成交 ${s.dealCount} 单`, s.id)
    }
  }

  function seed() {
    if (seeded.value) return
    m1.seed()
    const couponIds = m1.coupons.map((c) => c.id)

    const mk = (i: Partial<LiveSession> & { title: string; platform: LivePlatform; status: LiveStatus; startTime: string }): LiveSession => ({
      id: nextId('lv'),
      viewers: 0, linkClicks: 0, dealCount: 0, dealAmount: 0,
      mountedCouponIds: couponIds.slice(0, 1), intro: '', host: '白桥',
      ...i,
    })

    sessions.value = [
      mk({ title: '暑期水光自由卡专场', platform: 'DOUYIN', status: 'LIVE', startTime: dayOffset(0) + ' 19:30', viewers: 8620, linkClicks: 1840, dealCount: 86, dealAmount: 128000, intro: '润致娃娃针次卡，直播间专属加赠面膜一盒', host: '林微' }),
      mk({ title: '新客体验日·皮肤检测', platform: 'WECHAT_CHANNEL', status: 'LIVE', startTime: dayOffset(0) + ' 14:00', viewers: 3240, linkClicks: 680, dealCount: 32, dealAmount: 18600, intro: '新客 88 元体验，到店即赠皮肤检测', host: '苏晴' }),
      mk({ title: '热玛吉抗衰院长答疑', platform: 'DOUYIN', status: 'NOT_STARTED', startTime: dayOffset(2) + ' 20:00', viewers: 0, linkClicks: 0, dealCount: 0, dealAmount: 0, intro: '正版仪器可验真，院长一对一定制方案', host: '白桥' }),
      mk({ title: '会员日双倍积分直播', platform: 'WECHAT_CHANNEL', status: 'NOT_STARTED', startTime: dayOffset(5) + ' 19:00', viewers: 0, linkClicks: 0, dealCount: 0, dealAmount: 0, intro: '会员日积分翻倍，直播间专属福袋', host: '陈雅琳' }),
      mk({ title: '光子嫩肤买3送1', platform: 'DOUYIN', status: 'ENDED', startTime: dayOffset(-7) + ' 19:30', viewers: 12400, linkClicks: 2680, dealCount: 124, dealAmount: 186000, intro: '光子嫩肤年卡限时买3送1', host: '林微' }),
      mk({ title: '保妥适拼团夜', platform: 'DOUYIN', status: 'ENDED', startTime: dayOffset(-14) + ' 20:00', viewers: 6800, linkClicks: 1240, dealCount: 58, dealAmount: 86400, intro: '保妥适拼团 8.5 折', host: '白桥' }),
      mk({ title: '乔雅登品鉴会', platform: 'WECHAT_CHANNEL', status: 'ENDED', startTime: dayOffset(-21) + ' 15:00', viewers: 2180, linkClicks: 420, dealCount: 18, dealAmount: 52400, intro: '乔雅登全系品鉴，私享优惠', host: '苏晴' }),
    ]

    videos.value = [
      { id: nextId('vd'), title: '水光针全过程vlog', platform: 'DOUYIN', plays: 186000, likes: 12400, dealCount: 42, dealAmount: 38600, tags: ['水光', '种草'], publishedAt: dayOffset(-5) },
      { id: nextId('vd'), title: '热玛吉避坑指南', platform: 'XIAOHONGSHU', plays: 92000, likes: 8600, dealCount: 28, dealAmount: 52400, tags: ['热玛吉', '科普'], publishedAt: dayOffset(-8) },
      { id: nextId('vd'), title: '新客88元体验实拍', platform: 'DOUYIN', plays: 248000, likes: 18600, dealCount: 86, dealAmount: 24800, tags: ['新客', '体验'], publishedAt: dayOffset(-12) },
      { id: nextId('vd'), title: '光子嫩肤效果对比', platform: 'WECHAT_CHANNEL', plays: 34000, likes: 2100, dealCount: 12, dealAmount: 18600, tags: ['光子', '效果'], publishedAt: dayOffset(-18) },
      { id: nextId('vd'), title: '门店环境探店', platform: 'XIAOHONGSHU', plays: 56000, likes: 4200, dealCount: 8, dealAmount: 6800, tags: ['探店', '环境'], publishedAt: dayOffset(-25) },
    ]
    seeded.value = true
  }

  return {
    sessions, videos, filterStatus, seeded,
    filteredSessions, get,
    liveCount, monthSessions, totalViews, totalDealAmount, dealChartItems,
    createSession, startLive, endLive,
    PLATFORM_LABEL, VIDEO_PLATFORM_LABEL, LIVE_STATUS_LABEL, LIVE_STATUS_PILL,
    seed,
  }
})
