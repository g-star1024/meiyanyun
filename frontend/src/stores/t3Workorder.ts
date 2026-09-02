// ============================================================
// T3-03 工单中心 store（跨模块统一路由层）
// 汇聚 M2-08 服务工单 / M2-10 物料申领 / M2-18 异常处理 / M4 设备故障 等来源，
// 统一在 T3 流程中台进行：智能派单 → SLA 跟踪 → 处理时间线 → 关单。
// 对齐 Wave 5 T3-03 详设。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

// ---- 类型 ----
export type TicketSource = 'M2-08' | 'M2-10' | 'M2-18' | 'M4' | 'MANUAL' | 'SYSTEM'
export type TicketPriority = 'URGENT' | 'HIGH' | 'NORMAL' | 'LOW'
export type TicketStatus = 'NEW' | 'ASSIGNED' | 'PROCESSING' | 'PENDING' | 'RESOLVED' | 'CLOSED'
export type SlaStatus = 'ON_TRACK' | 'AT_RISK' | 'OVERDUE'

export interface TicketTimelineEntry {
  at: string
  actor: string
  action: string
  comment?: string
}

export interface Ticket {
  id: string
  ticketNo: string
  title: string
  source: TicketSource
  sourceRef?: string
  category: string
  priority: TicketPriority
  status: TicketStatus
  description: string
  reporter: string
  reporterDept: string
  assignee?: string
  assigneeDept?: string
  storeName: string
  createdAt: string
  assignedAt?: string
  resolvedAt?: string
  closedAt?: string
  dueAt: string
  slaStatus: SlaStatus
  resolution?: string
  tags: string[]
  timeline: TicketTimelineEntry[]
}

export interface DispatchRule {
  id: string
  name: string
  source: TicketSource
  category: string
  assignTo: string
  assignDept: string
  priority: TicketPriority
  enabled: boolean
}

const SOURCE_LABEL: Record<TicketSource, string> = {
  'M2-08': 'M2-08 服务工单',
  'M2-10': 'M2-10 物料申领',
  'M2-18': 'M2-18 异常处理',
  'M4':    'M4 设备故障',
  'MANUAL': '手工建单',
  'SYSTEM': '系统生成',
}

const PRIORITY_LABEL: Record<TicketPriority, string> = {
  URGENT: '紧急',
  HIGH: '高',
  NORMAL: '普通',
  LOW: '低',
}

const STATUS_LABEL: Record<TicketStatus, string> = {
  NEW: '待分配',
  ASSIGNED: '已派单',
  PROCESSING: '处理中',
  PENDING: '待反馈',
  RESOLVED: '已解决',
  CLOSED: '已关闭',
}

const SLA_LABEL: Record<SlaStatus, string> = {
  ON_TRACK: '正常',
  AT_RISK: '即将超时',
  OVERDUE: '已超时',
}

function calcSlaStatus(dueAt: string, closed = false): SlaStatus {
  if (closed) return 'ON_TRACK'
  const remain = new Date(dueAt).getTime() - Date.now()
  if (remain <= 0) return 'OVERDUE'
  if (remain <= 2 * 3600_000) return 'AT_RISK'
  return 'ON_TRACK'
}

function genTicketNo(seq: number) {
  const d = new Date()
  const ymd = `${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, '0')}${String(d.getDate()).padStart(2, '0')}`
  return `TK-${ymd}-${String(seq).padStart(4, '0')}`
}

export const useT3WorkorderStore = defineStore('t3Workorder', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const tickets = ref<Ticket[]>([])
  const rules = ref<DispatchRule[]>([])
  const loaded = ref(false)

  // ---- 查询 ----
  function getTicket(id: string) {
    return tickets.value.find((t) => t.id === id)
  }

  const myTickets = computed(() => {
    const me = auth.user.name
    return tickets.value
      .filter((t) => t.assignee === me && t.status !== 'CLOSED')
      .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
  })

  const newCount = computed(() => tickets.value.filter((t) => t.status === 'NEW').length)
  const processingCount = computed(
    () => tickets.value.filter((t) => t.status === 'ASSIGNED' || t.status === 'PROCESSING' || t.status === 'PENDING').length,
  )
  const overdueCount = computed(
    () => tickets.value.filter((t) => t.status !== 'CLOSED' && t.status !== 'RESOLVED' && calcSlaStatus(t.dueAt) === 'OVERDUE').length,
  )
  const resolvedToday = computed(() => {
    const today = new Date().toISOString().slice(0, 10)
    return tickets.value.filter(
      (t) => t.resolvedAt && t.resolvedAt.slice(0, 10) === today,
    ).length
  })

  const stats = computed(() => ({
    newCount: newCount.value,
    processingCount: processingCount.value,
    overdueCount: overdueCount.value,
    resolvedToday: resolvedToday.value,
  }))

  function getRule(source: TicketSource, category: string): DispatchRule | undefined {
    return rules.value.find((r) => r.enabled && r.source === source && (r.category === category || r.category === '*'))
  }

  // ---- 命令 ----
  function createTicket(input: {
    title: string
    source: TicketSource
    category: string
    priority?: TicketPriority
    description: string
    storeName?: string
    reporter?: string
    reporterDept?: string
    dueInHours?: number
    sourceRef?: string
    tags?: string[]
  }): Ticket {
    if (!auth.can('ticket:create')) throw new Error('无工单创建权限')
    const now = new Date()
    const seq = tickets.value.length + 1
    const rule = getRule(input.source, input.category)
    const priority = input.priority ?? rule?.priority ?? 'NORMAL'
    const dueHours = input.dueInHours
      ?? (priority === 'URGENT' ? 2 : priority === 'HIGH' ? 4 : priority === 'NORMAL' ? 24 : 48)
    const dueAt = new Date(now.getTime() + dueHours * 3600_000).toISOString()

    const t: Ticket = {
      id: nextId('tk'),
      ticketNo: genTicketNo(seq),
      title: input.title,
      source: input.source,
      sourceRef: input.sourceRef,
      category: input.category,
      priority,
      status: rule ? 'ASSIGNED' : 'NEW',
      description: input.description,
      reporter: input.reporter ?? auth.user.name,
      reporterDept: input.reporterDept ?? auth.user.jobTitle,
      assignee: rule?.assignTo,
      assigneeDept: rule?.assignDept,
      storeName: input.storeName ?? '静安旗舰店',
      createdAt: now.toISOString(),
      assignedAt: rule ? now.toISOString() : undefined,
      dueAt,
      slaStatus: calcSlaStatus(dueAt),
      tags: input.tags ?? [],
      timeline: [
        { at: now.toISOString(), actor: auth.user.name, action: '创建工单' },
        ...(rule
          ? [{ at: now.toISOString(), actor: '系统', action: `智能派单：${rule.assignTo}（${rule.assignDept}）` }]
          : []),
      ],
    }
    tickets.value.unshift(t)
    activity.log(auth.user.name, `创建工单 ${t.ticketNo}：${t.title}${rule ? `（自动派单至 ${rule.assignTo}）` : '（待分配）'}`, t.id)
    return t
  }

  function assignTicket(id: string, assignee: string, assigneeDept?: string) {
    if (!auth.can('ticket:dispatch')) throw new Error('无派单权限')
    const t = getTicket(id)
    if (!t) return
    t.assignee = assignee
    if (assigneeDept) t.assigneeDept = assigneeDept
    t.assignedAt = new Date().toISOString()
    if (t.status === 'NEW') t.status = 'ASSIGNED'
    t.timeline.unshift({ at: new Date().toISOString(), actor: auth.user.name, action: `派单给 ${assignee}${assigneeDept ? `（${assigneeDept}）` : ''}` })
    activity.log(auth.user.name, `工单 ${t.ticketNo} 派单给 ${assignee}`, t.id)
  }

  function updateStatus(id: string, status: TicketStatus, comment?: string) {
    const t = getTicket(id)
    if (!t) return
    t.status = status
    const actionMap: Record<TicketStatus, string> = {
      NEW: '重置为待分配',
      ASSIGNED: '已派单',
      PROCESSING: '开始处理',
      PENDING: '挂起待反馈',
      RESOLVED: '标记解决',
      CLOSED: '关闭工单',
    }
    t.timeline.unshift({ at: new Date().toISOString(), actor: auth.user.name, action: actionMap[status], comment })
    if (status === 'PROCESSING' && !t.assignedAt) t.assignedAt = new Date().toISOString()
    activity.log(auth.user.name, `工单 ${t.ticketNo} → ${STATUS_LABEL[status]}`, t.id)
  }

  function startProcessing(id: string) {
    const t = getTicket(id)
    if (!t) return
    updateStatus(id, 'PROCESSING')
  }

  function resolveTicket(id: string, resolution: string) {
    if (!auth.can('ticket:close')) throw new Error('无解决/关单权限')
    const t = getTicket(id)
    if (!t) return
    t.resolution = resolution
    t.resolvedAt = new Date().toISOString()
    t.status = 'RESOLVED'
    t.slaStatus = calcSlaStatus(t.dueAt, true)
    t.timeline.unshift({ at: new Date().toISOString(), actor: auth.user.name, action: '标记解决', comment: resolution })
    activity.log(auth.user.name, `工单 ${t.ticketNo} 已解决：${resolution.slice(0, 30)}`, t.id)
  }

  function closeTicket(id: string) {
    if (!auth.can('ticket:close')) throw new Error('无关闭工单权限')
    const t = getTicket(id)
    if (!t) return
    t.closedAt = new Date().toISOString()
    t.status = 'CLOSED'
    t.slaStatus = calcSlaStatus(t.dueAt, true)
    t.timeline.unshift({ at: new Date().toISOString(), actor: auth.user.name, action: '关闭工单' })
    activity.log(auth.user.name, `工单 ${t.ticketNo} 已关闭`, t.id)
  }

  function addComment(id: string, comment: string) {
    const t = getTicket(id)
    if (!t || !comment.trim()) return
    t.timeline.unshift({ at: new Date().toISOString(), actor: auth.user.name, action: '添加备注', comment })
  }

  // 规则维护
  function upsertRule(rule: Omit<DispatchRule, 'id'> & { id?: string }) {
    if (rule.id) {
      const existing = rules.value.find((r) => r.id === rule.id)
      if (existing) Object.assign(existing, rule)
    } else {
      rules.value.push({ ...rule, id: nextId('rule') })
    }
  }

  function toggleRule(id: string, enabled: boolean) {
    const r = rules.value.find((x) => x.id === id)
    if (r) r.enabled = enabled
  }

  // ---- 种子 ----
  function seed() {
    if (loaded.value) return
    loaded.value = true
    const now = Date.now()
    const hoursAgo = (h: number) => new Date(now - h * 3600_000).toISOString()
    const hoursLater = (h: number) => new Date(now + h * 3600_000).toISOString()

    // 默认派单规则
    rules.value = [
      { id: nextId('rule'), name: '服务工单→客服主管', source: 'M2-08', category: '客诉', assignTo: '陈雅琳', assignDept: '门店运营', priority: 'HIGH', enabled: true },
      { id: nextId('rule'), name: '服务工单→工程', source: 'M2-08', category: '设备报修', assignTo: '王工', assignDept: '工程部', priority: 'HIGH', enabled: true },
      { id: nextId('rule'), name: '物料申领→库管', source: 'M2-10', category: '*', assignTo: '李仓管', assignDept: '仓储', priority: 'NORMAL', enabled: true },
      { id: nextId('rule'), name: '异常处理→运营经理', source: 'M2-18', category: '*', assignTo: '苏晴', assignDept: '门店运营', priority: 'URGENT', enabled: true },
      { id: nextId('rule'), name: '设备故障→工程', source: 'M4', category: '*', assignTo: '王工', assignDept: '工程部', priority: 'URGENT', enabled: true },
    ]

    const seedTickets: Array<Partial<Ticket> & Pick<Ticket, 'title' | 'source' | 'category' | 'priority' | 'status' | 'description' | 'reporter' | 'reporterDept' | 'storeName' | 'createdAt' | 'dueAt'>> = [
      {
        title: '超声刀治疗仪 E07 报错', source: 'M4', category: '设备报修', priority: 'URGENT',
        status: 'PROCESSING', description: '设备开机后显示 E07 报错，无法进入治疗模式，下午 3 点有预约客户。',
        reporter: '林微', reporterDept: '皮肤科', storeName: '静安旗舰店',
        createdAt: hoursAgo(2), dueAt: hoursLater(1), assignee: '王工', assigneeDept: '工程部', assignedAt: hoursAgo(1.8),
        tags: ['设备', '紧急'],
      },
      {
        title: '射频紧肤客诉 - 效果不明显', source: 'M2-08', category: '客诉', priority: 'HIGH',
        status: 'PROCESSING', description: '客户赵雨晴反馈做完一次后无明显改善，要求重做或退款。',
        reporter: '前台小夏', reporterDept: '前台', storeName: '静安旗舰店',
        createdAt: hoursAgo(5), dueAt: hoursAgo(1), assignee: '陈雅琳', assigneeDept: '门店运营', assignedAt: hoursAgo(4),
        tags: ['客诉', '退款风险'],
      },
      {
        title: '申领玻尿酸注射液 20 支', source: 'M2-10', category: '药品申领', priority: 'NORMAL',
        status: 'ASSIGNED', description: '本周预计客户 18 人，现有库存仅 5 支，需要补货。',
        reporter: '顾屿', reporterDept: '皮肤科', storeName: '静安旗舰店',
        createdAt: hoursAgo(8), dueAt: hoursLater(20), assignee: '李仓管', assigneeDept: '仓储', assignedAt: hoursAgo(7),
        tags: ['物料'],
      },
      {
        title: '日结对账异常：长款 94 元', source: 'M2-18', category: '财务异常', priority: 'URGENT',
        status: 'PROCESSING', description: '8 月 25 日收银日结与微信支付流水比对，长款 94 元，需排查。',
        reporter: '系统', reporterDept: '系统', storeName: '静安旗舰店',
        createdAt: hoursAgo(12), dueAt: hoursAgo(2), assignee: '苏晴', assigneeDept: '门店运营', assignedAt: hoursAgo(11),
        tags: ['异常', '对账'],
      },
      {
        title: '激光仪器冷却液补充', source: 'M2-08', category: '设备报修', priority: 'NORMAL',
        status: 'NEW', description: 'A01 激光仪器冷却液水位低于 MIN 线，需补充。',
        reporter: '吴桐', reporterDept: '美容部', storeName: '静安旗舰店',
        createdAt: hoursAgo(1), dueAt: hoursLater(48), tags: ['设备'],
      },
      {
        title: '客户档案手机号加密失败', source: 'M2-18', category: '系统异常', priority: 'HIGH',
        status: 'RESOLVED', description: '客户王晓明的档案打开时手机号显示为乱码，疑似加密字段异常。',
        reporter: '林微', reporterDept: '咨询', storeName: '静安旗舰店',
        createdAt: hoursAgo(30), dueAt: hoursAgo(20), assignee: 'IT 支持', assigneeDept: 'IT', assignedAt: hoursAgo(29),
        resolvedAt: hoursAgo(24), resolution: '已重新加密字段，数据恢复正常，已验证 3 个客户档案。',
        tags: ['系统'],
      },
      {
        title: '空调出风异味', source: 'M2-08', category: '环境', priority: 'LOW',
        status: 'CLOSED', description: '候诊区空调出风有异味，需清洁滤网。',
        reporter: '前台小夏', reporterDept: '前台', storeName: '静安旗舰店',
        createdAt: hoursAgo(72), dueAt: hoursAgo(48), assignee: '物业', assigneeDept: '物业', assignedAt: hoursAgo(70),
        resolvedAt: hoursAgo(60), closedAt: hoursAgo(55), resolution: '已更换滤网并完成风道清洁。',
        tags: ['环境'],
      },
    ]

    seedTickets.forEach((s, i) => {
      const isClosed = s.status === 'CLOSED'
      const isResolved = s.status === 'RESOLVED'
      const timeline: TicketTimelineEntry[] = [
        { at: s.createdAt!, actor: s.reporter!, action: '创建工单' },
      ]
      if (s.assignee) {
        timeline.push({ at: s.assignedAt ?? s.createdAt!, actor: '系统', action: `派单给 ${s.assignee}` })
      }
      if (isResolved || isClosed) {
        timeline.unshift({ at: s.resolvedAt ?? s.closedAt!, actor: s.assignee ?? '处理人', action: '标记解决', comment: s.resolution })
      }
      if (isClosed) {
        timeline.unshift({ at: s.closedAt!, actor: '苏晴', action: '关闭工单' })
      }
      tickets.value.push({
        id: nextId('tk'),
        ticketNo: genTicketNo(i + 1),
        title: s.title!,
        source: s.source!,
        sourceRef: s.sourceRef,
        category: s.category!,
        priority: s.priority!,
        status: s.status!,
        description: s.description!,
        reporter: s.reporter!,
        reporterDept: s.reporterDept!,
        assignee: s.assignee,
        assigneeDept: s.assigneeDept,
        storeName: s.storeName!,
        createdAt: s.createdAt!,
        assignedAt: s.assignedAt,
        resolvedAt: s.resolvedAt,
        closedAt: s.closedAt,
        dueAt: s.dueAt!,
        slaStatus: calcSlaStatus(s.dueAt!, isClosed || isResolved),
        resolution: s.resolution,
        tags: s.tags ?? [],
        timeline,
      })
    })
  }

  return {
    tickets, rules,
    SOURCE_LABEL, PRIORITY_LABEL, STATUS_LABEL, SLA_LABEL,
    stats, myTickets,
    newCount, processingCount, overdueCount, resolvedToday,
    getTicket, getRule,
    createTicket, assignTicket, updateStatus, startProcessing,
    resolveTicket, closeTicket, addComment,
    upsertRule, toggleRule,
    seed,
  }
})
