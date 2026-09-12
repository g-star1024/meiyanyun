import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useM1MarketingStore } from '@/stores/m1Marketing'
import { useActivityStore } from '@/stores/activity'
import { useAuthStore } from '@/stores/auth'

// ============================================================
// M5 营销核心 store
// - 周频配额：同一客户/人群 7 天内推送 ≤3 条（合规硬约束）
// - 推送批次：PushBatch（人群/通道/模板/状态/到达点击）
// - 渠道业绩：ChannelPerf（美团/抖音/新氧等线索/成交/ROI）
// - ROI 汇总：跨活动 + 渠道的投放 ROI 看板
// 活动/券数据复用 m1Marketing store，不重复造。
// 券核销流水的真实链路在 m5Writeoff store（走 /marketing 真实端点），
// 本 store 不再保留任何核销 mock。
// ============================================================

export type PushChannel = 'SMS' | 'WECOM' | 'WECHAT_MP'
export type PushStatus = 'DRAFT' | 'SCHEDULED' | 'SENDING' | 'SENT' | 'BLOCKED'
export type ChannelKey = 'meituan' | 'douyin' | 'xiaohongshu' | 'dianping' | 'xinyang' | 'referral' | 'wecom'

let _id = 0
function nextId(p: string) { _id += 1; return `${p}-${Date.now().toString(36)}-${_id}` }
function dayOffset(n: number) {
  const d = new Date(); d.setDate(d.getDate() + n)
  return d.toISOString().slice(0, 10)
}

export interface PushBatch {
  id: string
  name: string
  channel: PushChannel
  segmentId: string
  segmentName: string
  templateTitle: string
  templateBody: string
  reach: number
  status: PushStatus
  scheduledAt: string
  sentAt?: string
  delivered: number
  clicked: number
  converted: number
  blockedReason?: string
}

export interface ChannelPerf {
  key: ChannelKey
  name: string
  adCost: number
  leads: number
  deals: number
  revenue: number
  commissionRate: number // 渠道佣金率
  connected: boolean
}

const WEEKLY_LIMIT = 3

export const PUSH_CHANNEL_LABEL: Record<PushChannel, string> = {
  SMS: '短信', WECOM: '企业微信', WECHAT_MP: '微信公众号',
}
export const PUSH_STATUS_LABEL: Record<PushStatus, string> = {
  DRAFT: '草稿', SCHEDULED: '待发送', SENDING: '发送中', SENT: '已发送', BLOCKED: '已拦截',
}
export const PUSH_STATUS_PILL: Record<PushStatus, 'default' | 'primary' | 'info' | 'success' | 'danger'> = {
  DRAFT: 'default', SCHEDULED: 'primary', SENDING: 'info', SENT: 'success', BLOCKED: 'danger',
}

export const useM5CoreStore = defineStore('m5Core', () => {
  const m1 = useM1MarketingStore()
  const activity = useActivityStore()
  const auth = useAuthStore()

  const batches = ref<PushBatch[]>([])
  const channels = ref<ChannelPerf[]>([])
  const seeded = ref(false)

  // ---------- 周频配额 ----------
  // 近 7 天已发送批次触达人次（演示：按总发送量近似人均）
  const sentLast7Days = computed(() =>
    batches.value
      .filter((b) => b.status === 'SENT' && b.sentAt && daysAgo(b.sentAt) <= 7)
      .reduce((s, b) => s + b.delivered, 0))
  const weeklyLimit = WEEKLY_LIMIT
  const weeklyRemaining = computed(() => Math.max(0, WEEKLY_LIMIT * estimateAudience() - sentLast7Days.value))
  const quotaPct = computed(() => Math.min(100, Math.round((sentLast7Days.value / Math.max(1, WEEKLY_LIMIT * estimateAudience())) * 100)))
  function estimateAudience() {
    // 演示：估算可触达客户基数
    return 800
  }
  function daysAgo(iso: string) {
    return Math.floor((Date.now() - new Date(iso).getTime()) / 86400000)
  }

  /** 周频校验：本周该人群是否还能发 */
  function checkWeeklyQuota(segmentId: string): { ok: boolean; reason: string } {
    const recent = batches.value.filter(
      (b) => b.segmentId === segmentId && b.status === 'SENT' && b.sentAt && daysAgo(b.sentAt) <= 7,
    )
    if (recent.length >= WEEKLY_LIMIT) {
      return { ok: false, reason: `该人群近 7 天已推送 ${recent.length} 次，达周频上限 ${WEEKLY_LIMIT} 次` }
    }
    return { ok: true, reason: `本周已发 ${recent.length}/${WEEKLY_LIMIT} 次` }
  }

  // ---------- 推送批次 ----------
  function createBatch(payload: Omit<PushBatch, 'id' | 'status' | 'delivered' | 'clicked' | 'converted'>): PushBatch {
    const b: PushBatch = {
      ...payload, id: nextId('pb'), status: 'SCHEDULED',
      delivered: 0, clicked: 0, converted: 0,
    }
    batches.value.unshift(b)
    return b
  }

  function sendBatch(id: string) {
    if (!auth.can('push:send')) throw new Error('无发送权限')
    const b = batches.value.find((x) => x.id === id)
    if (!b) return
    if (b.status === 'BLOCKED') throw new Error('该批次已被合规拦截')
    const quota = checkWeeklyQuota(b.segmentId)
    if (!quota.ok) {
      b.status = 'BLOCKED'
      b.blockedReason = quota.reason
      activity.log(auth.user?.name ?? '系统', `推送「${b.name}」因周频超限拦截`, b.id)
      return
    }
    b.status = 'SENT'
    b.sentAt = new Date().toISOString().slice(0, 16).replace('T', ' ')
    // 模拟到达/点击/转化
    b.delivered = Math.round(b.reach * 0.96)
    b.clicked = Math.round(b.delivered * 0.18)
    b.converted = Math.round(b.clicked * 0.22)
    activity.log(auth.user?.name ?? '系统', `发送营销推送「${b.name}」触达 ${b.delivered} 人`, b.id)
  }

  // ---------- 渠道业绩 ----------
  function updateChannel(key: ChannelKey, patch: Partial<ChannelPerf>) {
    const c = channels.value.find((x) => x.key === key)
    if (c) Object.assign(c, patch)
  }

  // ---------- ROI 汇总 ----------
  const totalAdCost = computed(() =>
    m1.campaigns.reduce((s, c) => s + c.spent, 0) + channels.value.reduce((s, c) => s + c.adCost, 0))
  const totalRevenue = computed(() => m1.stats.amount)
  const totalLeads = computed(() => channels.value.reduce((s, c) => s + c.leads, 0))
  const totalDeals = computed(() => channels.value.reduce((s, c) => s + c.deals, 0))
  const overallRoi = computed(() => totalAdCost.value ? Number((totalRevenue.value / totalAdCost.value).toFixed(1)) : 0)

  function seed() {
    if (seeded.value) return
    m1.seed()
    channels.value = [
      { key: 'meituan', name: '美团', adCost: 28000, leads: 320, deals: 86, revenue: 256000, commissionRate: 0.08, connected: true },
      { key: 'douyin', name: '抖音', adCost: 52000, leads: 680, deals: 142, revenue: 386000, commissionRate: 0.10, connected: true },
      { key: 'xiaohongshu', name: '小红书', adCost: 18000, leads: 210, deals: 48, revenue: 128000, commissionRate: 0.05, connected: true },
      { key: 'dianping', name: '大众点评', adCost: 12000, leads: 156, deals: 52, revenue: 96000, commissionRate: 0.06, connected: true },
      { key: 'xinyang', name: '新氧', adCost: 8000, leads: 96, deals: 22, revenue: 58000, commissionRate: 0.12, connected: false },
      { key: 'referral', name: '转介绍', adCost: 0, leads: 128, deals: 64, revenue: 182000, commissionRate: 0, connected: true },
      { key: 'wecom', name: '企微私域', adCost: 0, leads: 240, deals: 96, revenue: 214000, commissionRate: 0, connected: true },
    ]
    // 推送批次 seed
    batches.value = [
      mkBatch('暑期水光活动-高潜客户', 'WECOM', '高潜唤醒', '您有一张水光满减券待领取', 420, 'SENT', -3, 405, 88, 20),
      mkBatch('新客首享礼-沉睡客户', 'SMS', '沉睡唤醒', '专属88元体验券，限时领取', 680, 'SENT', -5, 652, 104, 28),
      mkBatch('会员日乔雅登满减', 'WECHAT_MP', '高价值客户', '会员日专属满减，仅此3天', 260, 'SCHEDULED', 2, 0, 0, 0),
      mkBatch('热玛吉专场-VIP邀约', 'SMS', '高价值客户', '抗衰专场一对一咨询', 180, 'BLOCKED', -1, 0, 0, 0, '该人群近7天已推送3次，达周频上限'),
    ]
    seeded.value = true
  }

  function mkBatch(name: string, channel: PushChannel, segName: string, body: string, reach: number,
    status: PushStatus, day: number, delivered: number, clicked: number, converted: number, blockedReason?: string): PushBatch {
    return {
      id: nextId('pb'), name, channel, segmentId: 'seg-' + segName, segmentName: segName,
      templateTitle: name, templateBody: body, reach, status,
      scheduledAt: dayOffset(day), sentAt: status === 'SENT' ? dayOffset(day) + ' 10:00' : undefined,
      delivered, clicked, converted, blockedReason,
    }
  }

  return {
    batches, channels, WEEKLY_LIMIT,
    PUSH_CHANNEL_LABEL, PUSH_STATUS_LABEL, PUSH_STATUS_PILL,
    sentLast7Days, weeklyLimit, weeklyRemaining, quotaPct,
    checkWeeklyQuota, createBatch, sendBatch, updateChannel,
    totalAdCost, totalRevenue, totalLeads, totalDeals, overallRoi,
    seed,
  }
})
