// ============================================================
// 异常处理 store（M2-18）
// 系统/业务/设备/客诉异常事件流，分级告警，升级闭环。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

export type ExType = 'SYSTEM' | 'BUSINESS' | 'DEVICE' | 'COMPLAINT'
export type ExLevel = 'HIGH' | 'MEDIUM' | 'LOW'
export type ExStatus = 'PENDING' | 'PROCESSING' | 'CLOSED'

export interface ExTimeline {
  by: string
  text: string
  at: string
}

export interface ExceptionEvent {
  id: string
  no: string
  type: ExType
  level: ExLevel
  title: string
  description: string
  status: ExStatus
  assignee: string
  source: string
  occurredAt: string
  closedAt?: string
  timeline: ExTimeline[]
}

const TYPE_LABEL: Record<ExType, string> = {
  SYSTEM: '系统异常',
  BUSINESS: '业务异常',
  DEVICE: '设备异常',
  COMPLAINT: '客诉',
}
const LEVEL_LABEL: Record<ExLevel, string> = {
  HIGH: '高',
  MEDIUM: '中',
  LOW: '低',
}
const STATUS_LABEL: Record<ExStatus, string> = {
  PENDING: '待处理',
  PROCESSING: '处理中',
  CLOSED: '已闭环',
}
const TYPE_ICON: Record<ExType, string> = {
  SYSTEM: 'settings',
  BUSINESS: 'order',
  DEVICE: 'alert',
  COMPLAINT: 'chat',
}

export const useExceptionStore = defineStore('exception', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const events = ref<ExceptionEvent[]>([])
  const filterType = ref<ExType | 'ALL'>('ALL')
  const filterStatus = ref<ExStatus | 'ALL'>('ALL')

  const pending = computed(() => events.value.filter((e) => e.status === 'PENDING'))
  const processing = computed(() => events.value.filter((e) => e.status === 'PROCESSING'))
  const closed = computed(() => events.value.filter((e) => e.status === 'CLOSED'))
  const highLevel = computed(() => events.value.filter((e) => e.level === 'HIGH' && e.status !== 'CLOSED'))
  const todayClosed = computed(() => {
    const today = new Date().toDateString()
    return events.value.filter((e) => e.closedAt && new Date(e.closedAt).toDateString() === today)
  })

  const filtered = computed(() => {
    let list = events.value
    if (filterType.value !== 'ALL') list = list.filter((e) => e.type === filterType.value)
    if (filterStatus.value !== 'ALL') list = list.filter((e) => e.status === filterStatus.value)
    return list.sort((a, b) => {
      const levelRank: Record<ExLevel, number> = { HIGH: 0, MEDIUM: 1, LOW: 2 }
      if (a.level !== b.level) return levelRank[a.level] - levelRank[b.level]
      return new Date(b.occurredAt).getTime() - new Date(a.occurredAt).getTime()
    })
  })

  function get(id: string) {
    return events.value.find((e) => e.id === id)
  }

  function start(id: string): boolean {
    const e = events.value.find((x) => x.id === id)
    if (!e || e.status !== 'PENDING' || !auth.can('exception:edit')) return false
    const now = new Date().toISOString()
    e.status = 'PROCESSING'
    e.timeline.unshift({ by: auth.user.name, text: '开始处理', at: now })
    activity.log(auth.user.name, `开始处理异常 ${e.no}：${e.title}`, e.id)
    return true
  }

  function escalate(id: string, reason: string): boolean {
    const e = events.value.find((x) => x.id === id)
    if (!e || e.status === 'CLOSED' || !auth.can('exception:edit')) return false
    const now = new Date().toISOString()
    if (e.level !== 'HIGH') e.level = 'HIGH'
    e.assignee = '陈雅琳（店长）'
    e.timeline.unshift({ by: auth.user.name, text: `升级处理：${reason}`, at: now })
    activity.log(auth.user.name, `升级异常 ${e.no}：${reason}`, e.id)
    return true
  }

  function close(id: string, note: string): boolean {
    const e = events.value.find((x) => x.id === id)
    if (!e || e.status === 'CLOSED' || !auth.can('exception:edit')) return false
    const now = new Date().toISOString()
    e.status = 'CLOSED'
    e.closedAt = now
    e.timeline.unshift({ by: auth.user.name, text: `闭环：${note}`, at: now })
    activity.log(auth.user.name, `异常闭环 ${e.no}：${note}`, e.id)
    return true
  }

  function addNote(id: string, text: string): boolean {
    const e = events.value.find((x) => x.id === id)
    if (!e || !auth.can('exception:edit')) return false
    e.timeline.unshift({ by: auth.user.name, text, at: new Date().toISOString() })
    return true
  }

  // ===== 种子数据 =====
  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    const now = new Date()
    const ago = (h: number) => new Date(now.getTime() - h * 3600_000).toISOString()
    const base: Array<{
      type: ExType; level: ExLevel; title: string; description: string
      status: ExStatus; assignee: string; source: string; occurredAt: string; closedAgo?: number
    }> = [
      { type: 'DEVICE', level: 'HIGH', title: '皮秒激光器水温超限报警', description: 'A03 治疗室皮秒仪器运行中水温达到 42℃，触发设备自保停机，需工程师检修冷却系统', status: 'PENDING', assignee: '周敏（美容师）', source: '设备 IoT 监控', occurredAt: ago(0.5) },
      { type: 'SYSTEM', level: 'HIGH', title: '支付回调超时，3 笔订单状态未同步', description: '10:12-10:18 期间微信支付回调延迟，导致 3 笔收银订单状态停留在"支付中"，客户已实际扣款', status: 'PROCESSING', assignee: '吴桐（运营）', source: '系统监控', occurredAt: ago(2) },
      { type: 'BUSINESS', level: 'MEDIUM', title: '卡项剩余次数与核销记录不一致', description: '客户赵雨晴（139****8821）热玛吉卡项系统显示剩余 1 次，但核销流水显示已用 2 次，需对账', status: 'PENDING', assignee: '李娜（护士）', source: '业务巡检', occurredAt: ago(3) },
      { type: 'COMPLAINT', level: 'MEDIUM', title: '客户投诉等候时间过长', description: '客户孙佳宁预约 14:00 光子嫩肤，实际等待 50 分钟才安排治疗，体验不佳要求致歉', status: 'PROCESSING', assignee: '陈雅琳（店长）', source: '前台登记', occurredAt: ago(5) },
      { type: 'DEVICE', level: 'LOW', title: 'B02 房间冷光灯闪烁', description: 'B02 咨询室冷光灯间歇性闪烁，不影响使用但需更换灯管', status: 'CLOSED', assignee: '周敏（美容师）', source: '员工报修', occurredAt: ago(28), closedAgo: 24 },
      { type: 'SYSTEM', level: 'LOW', title: '短信网关发送失败率升高', description: '上午短信发送失败率 3.2%（正常阈值 1%），多为省外号码，已联系服务商', status: 'CLOSED', assignee: '吴桐（运营）', source: '系统监控', occurredAt: ago(30), closedAgo: 26 },
      { type: 'BUSINESS', level: 'HIGH', title: '退单审批超时未处理', description: '客户王诗涵的疗程退单申请已提交 26 小时未审批，涉及金额 ¥9,800，触发超时升级', status: 'PENDING', assignee: '陈雅琳（店长）', source: '业务规则引擎', occurredAt: ago(26) },
    ]
    base.forEach((s, i) => {
      const id = nextId('ex')
      const isClosed = s.status === 'CLOSED'
      const isProc = s.status === 'PROCESSING'
      events.value.push({
        id,
        no: `EX-${s.occurredAt.slice(0, 10).replace(/-/g, '')}-${String(i + 1).padStart(3, '0')}`,
        type: s.type,
        level: s.level,
        title: s.title,
        description: s.description,
        status: s.status,
        assignee: s.assignee,
        source: s.source,
        occurredAt: s.occurredAt,
        closedAt: isClosed && s.closedAgo ? ago(s.closedAgo) : undefined,
        timeline: [
          { by: '系统', text: `检测到异常（来源：${s.source}）`, at: s.occurredAt },
          ...(isProc ? [{ by: s.assignee, text: '开始处理', at: ago(1) }] : []),
          ...(isClosed ? [{ by: s.assignee, text: '闭环：已处理完成并验证恢复', at: ago(s.closedAgo!) }] : []),
        ],
      })
    })
  }

  return {
    events, filterType, filterStatus,
    pending, processing, closed, highLevel, todayClosed, filtered,
    get, start, escalate, close, addNote, seed,
    TYPE_LABEL, LEVEL_LABEL, STATUS_LABEL, TYPE_ICON,
  }
})
