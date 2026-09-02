// ============================================================
// WorkOrder 服务工单 store（M2-08）
// 覆盖报修、巡检、客诉、咨询四类门店服务工单。
// 对齐设计稿 SCREEN-M2-08 Tablet 真值：4 KPI + 待处理列表 + 超时预警 + 新建工单 FAB。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

export type WorkOrderType = 'REPAIR' | 'INSPECTION' | 'CUSTOMER' | 'CONSULT'
export type WorkOrderStatus = 'PENDING' | 'IN_PROGRESS' | 'DONE' | 'ESCALATED'
export type WorkOrderPriority = 'HIGH' | 'MEDIUM' | 'LOW'

export interface WorkOrderNote {
  by: string
  text: string
  at: string
}

export interface WorkOrder {
  id: string
  woNo: string
  type: WorkOrderType
  title: string
  description: string
  customerName?: string
  project?: string
  room?: string
  assignee: string
  status: WorkOrderStatus
  priority: WorkOrderPriority
  deadline: string
  createdAt: string
  startedAt?: string
  completedAt?: string
  notes: WorkOrderNote[]
}

const TYPE_LABEL: Record<WorkOrderType, string> = {
  REPAIR: '报修',
  INSPECTION: '巡检',
  CUSTOMER: '客诉',
  CONSULT: '咨询',
}

const STATUS_LABEL: Record<WorkOrderStatus, string> = {
  PENDING: '待服务',
  IN_PROGRESS: '进行中',
  DONE: '已完成',
  ESCALATED: '已升级',
}

const TYPE_ICON: Record<WorkOrderType, string> = {
  REPAIR: 'tool',
  INSPECTION: 'scan',
  CUSTOMER: 'customer',
  CONSULT: 'message',
}

export const useWorkOrderStore = defineStore('workorder', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const orders = ref<WorkOrder[]>([])
  const filterType = ref<WorkOrderType | 'ALL'>('ALL')
  const filterStatus = ref<WorkOrderStatus | 'ALL'>('ALL')

  const pending = computed(() => orders.value.filter((o) => o.status === 'PENDING'))
  const inProgress = computed(() => orders.value.filter((o) => o.status === 'IN_PROGRESS'))
  const done = computed(() => orders.value.filter((o) => o.status === 'DONE'))
  const escalated = computed(() => orders.value.filter((o) => o.status === 'ESCALATED'))
  const overdue = computed(() => {
    const now = Date.now()
    return orders.value.filter((o) => o.status !== 'DONE' && new Date(o.deadline).getTime() < now)
  })

  const filtered = computed(() => {
    let list = orders.value
    if (filterType.value !== 'ALL') list = list.filter((o) => o.type === filterType.value)
    if (filterStatus.value !== 'ALL') list = list.filter((o) => o.status === filterStatus.value)
    return list.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
  })

  function get(id: string) {
    return orders.value.find((o) => o.id === id)
  }

  function create(input: {
    type: WorkOrderType
    title: string
    description: string
    customerName?: string
    project?: string
    room?: string
    priority?: WorkOrderPriority
    deadline?: string
    assignee?: string
  }): WorkOrder | null {
    if (!auth.can('workorder:create')) {
      console.warn('[workorder] 无 workorder:create 权限')
      return null
    }
    const now = new Date()
    const deadline = input.deadline
      ? new Date(input.deadline).toISOString()
      : new Date(now.getTime() + 4 * 3600_000).toISOString()
    const seq = orders.value.length + 1
    const o: WorkOrder = {
      id: nextId('wo'),
      woNo: `WO-${now.toISOString().slice(0, 10).replace(/-/g, '')}-${String(seq).padStart(3, '0')}`,
      type: input.type,
      title: input.title,
      description: input.description,
      customerName: input.customerName,
      project: input.project,
      room: input.room,
      assignee: input.assignee?.trim() || '待分配',
      status: 'PENDING',
      priority: input.priority || 'MEDIUM',
      deadline,
      createdAt: now.toISOString(),
      notes: [{ by: auth.user.name, text: '创建工单', at: now.toISOString() }],
    }
    orders.value.unshift(o)
    activity.log(auth.user.name, `创建服务工单 ${o.woNo}：${o.title}`, o.id)
    return o
  }

  function assign(id: string, assignee: string): boolean {
    const o = orders.value.find((x) => x.id === id)
    if (!o || !auth.can('workorder:edit')) return false
    o.assignee = assignee
    addNote(id, `指派给 ${assignee}`)
    activity.log(auth.user.name, `指派工单 ${o.woNo} 给 ${assignee}`, o.id)
    return true
  }

  function start(id: string): boolean {
    const o = orders.value.find((x) => x.id === id)
    if (!o || o.status !== 'PENDING' || !auth.can('workorder:edit')) return false
    o.status = 'IN_PROGRESS'
    o.startedAt = new Date().toISOString()
    addNote(id, '开始处理')
    activity.log(auth.user.name, `开始处理工单 ${o.woNo}`, o.id)
    return true
  }

  function complete(id: string, note?: string): boolean {
    const o = orders.value.find((x) => x.id === id)
    if (!o || o.status !== 'IN_PROGRESS' || !auth.can('workorder:close')) return false
    o.status = 'DONE'
    o.completedAt = new Date().toISOString()
    if (note) addNote(id, `完成：${note}`)
    else addNote(id, '已完成')
    activity.log(auth.user.name, `关闭工单 ${o.woNo}`, o.id)
    return true
  }

  function escalate(id: string, reason: string): boolean {
    const o = orders.value.find((x) => x.id === id)
    if (!o || o.status === 'DONE' || !auth.can('workorder:edit')) return false
    o.status = 'ESCALATED'
    addNote(id, `升级：${reason}`)
    activity.log(auth.user.name, `升级工单 ${o.woNo}：${reason}`, o.id)
    return true
  }

  function addNote(id: string, text: string): boolean {
    const o = orders.value.find((x) => x.id === id)
    if (!o) return false
    o.notes.unshift({ by: auth.user.name, text, at: new Date().toISOString() })
    return true
  }

  // ===== 种子数据 =====
  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    const now = new Date()
    const hoursAgo = (h: number) => new Date(now.getTime() - h * 3600_000).toISOString()
    const hoursLater = (h: number) => new Date(now.getTime() + h * 3600_000).toISOString()
    const base: Array<Partial<WorkOrder> & { type: WorkOrderType; title: string; description: string; status: WorkOrderStatus; priority: WorkOrderPriority; deadline: string; createdAt: string }> = [
      { type: 'REPAIR', title: '超声刀治疗仪报修', description: '设备开机后显示 E07 报错，无法进入治疗模式', customerName: '陈美玲', project: '超声刀治疗仪', room: 'A03', status: 'PENDING', priority: 'HIGH', deadline: hoursLater(2), createdAt: hoursAgo(1) },
      { type: 'CUSTOMER', title: '射频紧肤效果投诉', description: '客户赵雨晴反馈做完一次后无明显改善，要求重做', customerName: '赵雨晴', project: '射频紧肤', room: 'B02', status: 'IN_PROGRESS', priority: 'HIGH', deadline: hoursAgo(1), createdAt: hoursAgo(3) },
      { type: 'INSPECTION', title: '每日设备巡检', description: '检查激光仪器冷却液水位及手柄消毒记录', project: '激光仪器', room: 'A01', status: 'DONE', priority: 'MEDIUM', deadline: hoursAgo(4), createdAt: hoursAgo(5) },
      { type: 'CONSULT', title: '玻尿酸术后护理咨询', description: '客户孙佳宁咨询术后 24h 内能否化妆', customerName: '孙佳宁', project: '玻尿酸', room: 'C01', status: 'PENDING', priority: 'LOW', deadline: hoursLater(4), createdAt: hoursAgo(2) },
      { type: 'REPAIR', title: '空调出风异味处理', description: '候诊区空调出风有异味，需清洁滤网', project: '中央空调', room: '大厅', status: 'IN_PROGRESS', priority: 'MEDIUM', deadline: hoursLater(1), createdAt: hoursAgo(4) },
      { type: 'CUSTOMER', title: '预约时间冲突协调', description: '客户王晓明预约的咨询师临时请假，需改约', customerName: '王晓明', project: '咨询预约', room: '前台', status: 'DONE', priority: 'LOW', deadline: hoursAgo(6), createdAt: hoursAgo(7) },
    ]
    base.forEach((s, i) => {
      const isDone = s.status === 'DONE'
      orders.value.push({
        id: nextId('wo'),
        woNo: `WO-${s.createdAt.slice(0, 10).replace(/-/g, '')}-${String(i + 1).padStart(3, '0')}`,
        type: s.type,
        title: s.title,
        description: s.description,
        customerName: s.customerName,
        project: s.project,
        room: s.room,
        assignee: ['周敏（美容师）', '李娜（前台）', '吴桐（运营）', '陈雅琳（店长）'][i % 4],
        status: s.status,
        priority: s.priority,
        deadline: s.deadline,
        createdAt: s.createdAt,
        startedAt: s.status !== 'PENDING' ? hoursAgo(i + 1) : undefined,
        completedAt: isDone ? hoursAgo(i) : undefined,
        notes: [
          { by: '系统', text: '自动创建', at: s.createdAt },
          ...(isDone ? [{ by: s.assignee || '系统', text: '已完成', at: hoursAgo(i) }] : []),
        ],
      })
    })
  }

  return {
    orders, filterType, filterStatus,
    pending, inProgress, done, escalated, overdue, filtered,
    get, create, assign, start, complete, escalate, addNote, seed,
    TYPE_LABEL, STATUS_LABEL, TYPE_ICON,
  }
})
