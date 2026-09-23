import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useM1MarketingStore } from '@/stores/m1Marketing'
import { useActivityStore } from '@/stores/activity'
import { useAuthStore } from '@/stores/auth'
import { checkSensitive } from '@/composables/useSensitiveWords'
import * as api from '@/api/calendar'
import type { CalendarNodeRow, CalendarScheduleRow } from '@/api/calendar'

// ============================================================
// M5-09/10 会员日/节日营销 store（已接真实 marketing-service，P5-B88）
// - CalendarNode：营销节点（会员日/节日/活动）
// - ScheduledActivity：为节点排期的活动（关联券 + 积分 + 渠道）
// - 月历网格纯前端计算（new Date）
//
// 适配层（铁律：模板/样式零改动，只换数据源）：
//  - 字段名 nodeId→id、scheduleId→id、scheduleName→name、nodeDesc→desc、nodeType→type
//  - couponIds/channels 后端 TEXT 存 JSON 串在此解析；日期串截前 10 位
//  - estimatedRevenueCents 后端单位「分」÷100 → 前端 estimatedRevenue「元」；创建时 ×100
//  - 校验链顺序/中文文案与后端逐字一致（前端预检 + 服务端敏感词双道兜底）
//  - 写动作经网关，后端 400 中文错误经 errMsg() 回填 reason 外露到视图 formError
// ============================================================

export type NodeType = 'member' | 'festival' | 'campaign'
export type ScheduleStatus = 'DRAFT' | 'SCHEDULED' | 'RUNNING' | 'ENDED'
export type PushChannel = 'SMS' | 'WECOM' | 'WECHAT_MP'

export interface CalendarNode {
  id: string
  /** YYYY-MM-DD */
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

/** 从 axios 错误中取后端中文 message（与全平台视图错误范式一致） */
function errMsg(e: unknown, fallback = '网络异常，请稍后重试'): string {
  const anyE = e as { response?: { data?: { message?: string } }; message?: string }
  return anyE?.response?.data?.message || anyE?.message || fallback
}

/** yyyy-MM-dd（后端 LocalDate 直接可用；ISO 时间串兜底截取） */
function dayOf(s?: string | null): string {
  return s ? s.slice(0, 10) : ''
}

function parseJsonArray<T>(json: string | null | undefined, fallback: T[]): T[] {
  if (!json) return fallback
  try {
    const v: unknown = JSON.parse(json)
    return Array.isArray(v) ? (v as T[]) : fallback
  } catch {
    return fallback
  }
}

function adaptNode(row: CalendarNodeRow): CalendarNode {
  return {
    id: row.nodeId,
    date: dayOf(row.nodeDate),
    title: row.title,
    type: (row.nodeType || 'campaign') as NodeType,
    desc: row.nodeDesc ?? undefined,
  }
}

function adaptSchedule(row: CalendarScheduleRow): ScheduledActivity {
  return {
    id: row.scheduleId,
    nodeId: row.nodeId,
    nodeDate: dayOf(row.nodeDate),
    name: row.scheduleName,
    benefitDesc: row.benefitDesc ?? '',
    couponIds: parseJsonArray<string>(row.couponIds, []),
    pointsReward: row.pointsReward ?? 0,
    startDate: dayOf(row.startDate),
    endDate: dayOf(row.endDate),
    channels: parseJsonArray<PushChannel>(row.channels, []),
    copyText: row.copyText ?? '',
    status: (row.status || 'DRAFT') as ScheduleStatus,
    estimatedRevenue: Math.round((row.estimatedRevenueCents ?? 0) / 100),
    createdAt: dayOf(row.createdAt),
    createdBy: row.createdBy ?? '运营',
  }
}

/** 创建幂等令牌（crypto.randomUUID 不可用时回退随机串） */
function newToken(): string {
  return typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `tok-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
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
  async function createSchedule(payload: {
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
  }): Promise<{ ok: boolean; reason?: string; schedule?: ScheduledActivity }> {
    if (!auth.can('calendar:edit')) return { ok: false, reason: '无排期权限' }

    const hit = checkSensitive([payload.name, payload.benefitDesc, payload.copyText].join(' '))
    if (hit.hit) return { ok: false, reason: hit.message }

    const node = nodes.value.find((n) => n.id === payload.nodeId)
    if (!node) return { ok: false, reason: '节点不存在' }
    if (!payload.name.trim()) return { ok: false, reason: '请填写活动名称' }
    if (!payload.channels.length) return { ok: false, reason: '至少选择一个推送渠道' }
    if (payload.startDate > payload.endDate) return { ok: false, reason: '开始时间不能晚于结束时间' }

    try {
      const resp = await api.createCalendarSchedule({
        nodeId: payload.nodeId,
        name: payload.name.trim(),
        benefitDesc: payload.benefitDesc.trim(),
        couponIds: [...payload.couponIds],
        pointsReward: payload.pointsReward,
        startDate: payload.startDate,
        endDate: payload.endDate,
        channels: [...payload.channels],
        copyText: payload.copyText.trim(),
        estimatedRevenueCents: Math.round((payload.estimatedRevenue || 0) * 100),
        clientToken: newToken(),
      })
      const s = adaptSchedule(resp.data)
      const i = schedules.value.findIndex((x) => x.id === s.id)
      if (i >= 0) schedules.value.splice(i, 1, s)
      else schedules.value.unshift(s)
      activity.log(
        s.createdBy,
        `为「${node.title}」排期活动「${s.name}」，预计带动营收 ¥${s.estimatedRevenue.toLocaleString('zh-CN')}`,
        s.id,
      )
      return { ok: true, schedule: s }
    } catch (e) {
      return { ok: false, reason: errMsg(e, '提交失败，请稍后重试') }
    }
  }

  // ------------- seed（每次进页重拉真实数据，B86 适配层范式） -------------
  async function seed() {
    try {
      await m1.seed()
      const [nodesResp, schedulesResp] = await Promise.all([
        api.fetchCalendarNodes(),
        api.fetchCalendarSchedules(),
      ])
      nodes.value = nodesResp.data.map(adaptNode)
      schedules.value = schedulesResp.data.map(adaptSchedule)
      seeded.value = true
    } catch (e) {
      console.warn('[m5Calendar] 日历数据加载失败', e)
    }
  }

  return {
    nodes, schedules,
    NODE_TYPE_LABEL, NODE_TYPE_PILL, SCHEDULE_STATUS_LABEL, SCHEDULE_STATUS_PILL, PUSH_CHANNEL_LABEL,
    monthNodes, runningCount, pendingCount, estimatedRevenue,
    nodesOfMonth, nodesOfDay, schedulesOfNode, weeklyScheduleOfMonth,
    createSchedule, seed,
  }
})
