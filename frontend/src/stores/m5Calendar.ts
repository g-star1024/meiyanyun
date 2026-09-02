import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useM1MarketingStore } from '@/stores/m1Marketing'
import { useActivityStore } from '@/stores/activity'
import { useAuthStore } from '@/stores/auth'
import { checkSensitive } from '@/composables/useSensitiveWords'

// ============================================================
// M5-09/10 会员日/节日营销 store
// - CalendarNode：营销节点（会员日/节日/活动），按月日固定
// - ScheduledActivity：为节点排期的活动（关联 m1.coupons + 积分 + 渠道）
// - 月历网格纯前端计算（new Date）
// ============================================================

export type NodeType = 'member' | 'festival' | 'campaign'
export type ScheduleStatus = 'DRAFT' | 'SCHEDULED' | 'RUNNING' | 'ENDED'
export type PushChannel = 'SMS' | 'WECOM' | 'WECHAT_MP'

export interface CalendarNode {
  id: string
  /** YYYY-MM-DD，跨年固定月日（这里演示用完整日期） */
  date: string
  title: string
  type: NodeType
  desc?: string
}

export interface ScheduledActivity {
  id: string
  nodeId: string
  nodeDate: string
  name: string
  benefitDesc: string
  couponIds: string[]
  pointsReward: number
  startDate: string
  endDate: string
  channels: PushChannel[]
  copyText: string
  status: ScheduleStatus
  estimatedRevenue: number
  createdAt: string
  createdBy: string
}

let _id = 0
function nextId(p: string) {
  _id += 1
  return `${p}-${Date.now().toString(36)}-${_id}`
}
function todayStr() {
  return new Date().toISOString().slice(0, 10)
}
function isoFromOffset(day: number) {
  const d = new Date()
  d.setDate(d.getDate() + day)
  return d.toISOString().slice(0, 10)
}

export const NODE_TYPE_LABEL: Record<NodeType, string> = {
  member: '会员日',
  festival: '节日',
  campaign: '活动',
}
export const NODE_TYPE_PILL: Record<NodeType, 'primary' | 'warning' | 'info'> = {
  member: 'primary',
  festival: 'warning',
  campaign: 'info',
}
export const SCHEDULE_STATUS_LABEL: Record<ScheduleStatus, string> = {
  DRAFT: '草稿',
  SCHEDULED: '待开始',
  RUNNING: '进行中',
  ENDED: '已结束',
}
export const SCHEDULE_STATUS_PILL: Record<ScheduleStatus, 'draft' | 'primary' | 'success' | 'default'> = {
  DRAFT: 'draft',
  SCHEDULED: 'primary',
  RUNNING: 'success',
  ENDED: 'default',
}
export const PUSH_CHANNEL_LABEL: Record<PushChannel, string> = {
  SMS: '短信',
  WECOM: '企业微信',
  WECHAT_MP: '公众号',
}

export const useM5CalendarStore = defineStore('m5Calendar', () => {
  const m1 = useM1MarketingStore()
  const activity = useActivityStore()
  const auth = useAuthStore()

  const nodes = ref<CalendarNode[]>([])
  const schedules = ref<ScheduledActivity[]>([])
  const seeded = ref(false)

  // ------------- 查询 -------------
  function nodesOfMonth(year: number, month: number) {
    const prefix = `${year}-${String(month + 1).padStart(2, '0')}-`
    return nodes.value
      .filter((n) => n.date.startsWith(prefix))
      .sort((a, b) => a.date.localeCompare(b.date))
  }
  function nodesOfDay(dateStr: string) {
    return nodes.value.filter((n) => n.date === dateStr)
  }
  function schedulesOfNode(nodeId: string) {
    return schedules.value.filter((s) => s.nodeId === nodeId)
  }

  // ------------- KPI -------------
  const monthNodes = computed(() => {
    const now = new Date()
    return nodesOfMonth(now.getFullYear(), now.getMonth())
  })
  const runningCount = computed(
    () => schedules.value.filter((s) => s.status === 'RUNNING').length,
  )
  const pendingCount = computed(
    () => schedules.value.filter((s) => s.status === 'SCHEDULED' || s.status === 'DRAFT').length,
  )
  const estimatedRevenue = computed(() =>
    schedules.value
      .filter((s) => s.status === 'SCHEDULED' || s.status === 'RUNNING')
      .reduce((sum, s) => sum + s.estimatedRevenue, 0),
  )

  // ------------- 周节奏（当月各周排期数） -------------
  function weeklyScheduleOfMonth(year: number, month: number) {
    const daysInMonth = new Date(year, month + 1, 0).getDate()
    const weeks: { label: string; values: number[] }[] = []
    for (let w = 0; w < 5; w += 1) {
      const start = w * 7 + 1
      const end = Math.min(daysInMonth, start + 6)
      if (start > daysInMonth) break
      const prefix = `${year}-${String(month + 1).padStart(2, '0')}-`
      const count = schedules.value.filter((s) => {
        if (!s.nodeDate.startsWith(prefix)) return false
        const d = Number(s.nodeDate.slice(8, 10))
        return d >= start && d <= end
      }).length
      weeks.push({ label: `${start}-${end}日`, values: [count] })
    }
    return weeks
  }

  // ------------- 新建排期 -------------
  function createSchedule(payload: {
    nodeId: string
    name: string
    benefitDesc: string
    couponIds: string[]
    pointsReward: number
    startDate: string
    endDate: string
    channels: PushChannel[]
    copyText: string
    estimatedRevenue: number
  }): { ok: boolean; reason?: string; schedule?: ScheduledActivity } {
    if (!auth.can('calendar:edit')) return { ok: false, reason: '无排期权限' }

    const hit = checkSensitive([payload.name, payload.benefitDesc, payload.copyText].join(' '))
    if (hit.hit) return { ok: false, reason: hit.message }

    const node = nodes.value.find((n) => n.id === payload.nodeId)
    if (!node) return { ok: false, reason: '节点不存在' }
    if (!payload.name.trim()) return { ok: false, reason: '请填写活动名称' }
    if (!payload.channels.length) return { ok: false, reason: '至少选择一个推送渠道' }
    if (payload.startDate > payload.endDate) return { ok: false, reason: '开始时间不能晚于结束时间' }

    const s: ScheduledActivity = {
      id: nextId('sch'),
      nodeId: payload.nodeId,
      nodeDate: node.date,
      name: payload.name.trim(),
      benefitDesc: payload.benefitDesc.trim(),
      couponIds: [...payload.couponIds],
      pointsReward: payload.pointsReward,
      startDate: payload.startDate,
      endDate: payload.endDate,
      channels: [...payload.channels],
      copyText: payload.copyText.trim(),
      status: 'SCHEDULED',
      estimatedRevenue: payload.estimatedRevenue,
      createdAt: todayStr(),
      createdBy: auth.user?.name ?? '运营',
    }
    schedules.value.unshift(s)
    activity.log(
      s.createdBy,
      `为「${node.title}」排期活动「${s.name}」，预计带动营收 ¥${s.estimatedRevenue.toLocaleString('zh-CN')}`,
      s.id,
    )
    return { ok: true, schedule: s }
  }

  // ------------- seed -------------
  function seed() {
    if (seeded.value) return
    m1.seed()

    // 以 2026 年 8 月为当前月铺节点，同时给 9-12 月铺节日
    const y = new Date().getFullYear()
    const d = (month: number, day: number) =>
      `${y}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`

    nodes.value = [
      // 8 月
      { id: nextId('node'), date: d(8, 1), title: '建军节', type: 'festival', desc: '致敬军人，专属福利' },
      { id: nextId('node'), date: d(8, 8), title: '8 月会员日', type: 'member', desc: '每月 8 号会员日，积分双倍' },
      { id: nextId('node'), date: d(8, 12), title: '国际青年日', type: 'festival', desc: '青年顾客专属焕肤套餐' },
      { id: nextId('node'), date: d(8, 15), title: '暑期水光自由卡', type: 'campaign', desc: '主推润致娃娃针次卡' },
      { id: nextId('node'), date: d(8, 19), title: '七夕情人节', type: 'festival', desc: '情侣同行，双人套餐' },
      { id: nextId('node'), date: d(8, 22), title: '新客首享体验周', type: 'campaign', desc: '新客 88 元体验项目' },
      { id: nextId('node'), date: d(8, 25), title: '全国护肤日预热', type: 'campaign', desc: '皮肤检测免费预约' },
      { id: nextId('node'), date: d(8, 28), title: '月末宠粉日', type: 'campaign', desc: '老客到店赠精华小样' },
      // 9 月
      { id: nextId('node'), date: d(9, 8), title: '9 月会员日', type: 'member', desc: '会员日专享满减' },
      { id: nextId('node'), date: d(9, 10), title: '教师节', type: 'festival', desc: '凭教师资格证享 8.5 折' },
      { id: nextId('node'), date: d(9, 15), title: '秋季抗衰专场', type: 'campaign', desc: '热玛吉/超声炮套餐' },
      { id: nextId('node'), date: d(9, 25), title: '中秋节', type: 'festival', desc: '中秋团圆礼盒' },
      // 10 月
      { id: nextId('node'), date: d(10, 1), title: '国庆黄金周', type: 'festival', desc: '10.1-10.7 全场满赠' },
      { id: nextId('node'), date: d(10, 8), title: '10 月会员日', type: 'member' },
      { id: nextId('node'), date: d(10, 18), title: '重阳节', type: 'festival', desc: '孝心套餐，带父母同行' },
      { id: nextId('node'), date: d(10, 20), title: '双 11 预热', type: 'campaign', desc: '提前锁价，储值翻倍' },
      // 11 月
      { id: nextId('node'), date: d(11, 8), title: '11 月会员日', type: 'member' },
      { id: nextId('node'), date: d(11, 11), title: '双 11 狂欢', type: 'festival', desc: '全年最低价，限时 24 小时' },
      // 12 月
      { id: nextId('node'), date: d(12, 8), title: '12 月会员日', type: 'member' },
      { id: nextId('node'), date: d(12, 12), title: '双 12 年终庆', type: 'campaign' },
      { id: nextId('node'), date: d(12, 24), title: '平安夜', type: 'festival' },
      { id: nextId('node'), date: d(12, 25), title: '圣诞节', type: 'festival' },
      { id: nextId('node'), date: d(12, 31), title: '门店 5 周年店庆', type: 'campaign', desc: '周年庆，全年最大力度' },
    ]

    // 排期 seed
    const findNode = (date: string) => nodes.value.find((n) => n.date === date)!
    schedules.value = [
      {
        id: nextId('sch'),
        nodeId: findNode(d(8, 8)).id,
        nodeDate: d(8, 8),
        name: '8 月会员日·乔雅登满减',
        benefitDesc: '乔雅登满 5000 减 800，会员双倍积分',
        couponIds: [],
        pointsReward: 200,
        startDate: d(8, 8),
        endDate: d(8, 10),
        channels: ['WECOM', 'WECHAT_MP'],
        copyText: '8 月会员日专属福利，乔雅登满 5000 减 800，仅此 3 天',
        status: 'RUNNING',
        estimatedRevenue: 180000,
        createdAt: isoFromOffset(-10),
        createdBy: '白桥',
      },
      {
        id: nextId('sch'),
        nodeId: findNode(d(8, 15)).id,
        nodeDate: d(8, 15),
        name: '暑期水光自由卡',
        benefitDesc: '润致娃娃针 3 次卡，赠送修复面膜 1 盒',
        couponIds: m1.coupons.slice(0, 1).map((c) => c.id),
        pointsReward: 100,
        startDate: d(8, 15),
        endDate: d(8, 31),
        channels: ['WECOM', 'SMS'],
        copyText: '暑期水光自由卡，3 次超值套餐，抖音直播同步发售',
        status: 'RUNNING',
        estimatedRevenue: 386000,
        createdAt: isoFromOffset(-20),
        createdBy: '白桥',
      },
      {
        id: nextId('sch'),
        nodeId: findNode(d(8, 19)).id,
        nodeDate: d(8, 19),
        name: '七夕·双人同行',
        benefitDesc: '情侣双人到店，第二人半价',
        couponIds: [],
        pointsReward: 300,
        startDate: d(8, 19),
        endDate: d(8, 20),
        channels: ['WECHAT_MP', 'WECOM'],
        copyText: '七夕相约，双人同行第二人半价，赠鲜花礼盒',
        status: 'SCHEDULED',
        estimatedRevenue: 120000,
        createdAt: isoFromOffset(-5),
        createdBy: '林微',
      },
      {
        id: nextId('sch'),
        nodeId: findNode(d(9, 10)).id,
        nodeDate: d(9, 10),
        name: '教师节感恩专场',
        benefitDesc: '凭教师资格证 8.5 折，赠手部护理',
        couponIds: [],
        pointsReward: 150,
        startDate: d(9, 10),
        endDate: d(9, 12),
        channels: ['SMS'],
        copyText: '感恩教师节，凭资格证享 8.5 折优惠',
        status: 'DRAFT',
        estimatedRevenue: 60000,
        createdAt: isoFromOffset(-2),
        createdBy: '苏晴',
      },
    ]
    seeded.value = true
  }

  return {
    nodes, schedules,
    NODE_TYPE_LABEL, NODE_TYPE_PILL, SCHEDULE_STATUS_LABEL, SCHEDULE_STATUS_PILL, PUSH_CHANNEL_LABEL,
    monthNodes, runningCount, pendingCount, estimatedRevenue,
    nodesOfMonth, nodesOfDay, schedulesOfNode, weeklyScheduleOfMonth,
    createSchedule, seed,
  }
})
