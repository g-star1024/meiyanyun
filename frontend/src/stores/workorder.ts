import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import * as woApi from '@/api/workorder'
import { useToast } from '@/composables/useToast'
import { useAuthStore } from '@/stores/auth'
import { errMsg } from '@/stores/m5Coupon'
import { useStoreContext } from '@/stores/storeContext'

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

function shortWoNo(woNo: string): string {
  const parts = woNo.split('-')
  const tail = parts[parts.length - 1]
  const seq = parseInt(tail, 10)
  if (!Number.isFinite(seq) || seq > 999) return woNo
  return `${parts[0]}-${parts[1]}-${String(seq).padStart(3, '0')}`
}

function adapt(d: woApi.WorkOrderDto): WorkOrder {
  return {
    id: String(d.id),
    woNo: shortWoNo(d.woNo),
    type: d.type as WorkOrderType,
    title: d.title,
    description: d.description,
    customerName: d.customerName ?? undefined,
    project: d.project ?? undefined,
    room: d.room ?? undefined,
    assignee: d.assignee,
    status: d.status as WorkOrderStatus,
    priority: d.priority as WorkOrderPriority,
    deadline: d.deadline,
    createdAt: d.createdAt,
    startedAt: d.startedAt ?? undefined,
    completedAt: d.completedAt ?? undefined,
    notes: d.notes.map((n) => ({ by: n.by, text: n.text, at: n.at })),
  }
}

export const useWorkOrderStore = defineStore('workorder', () => {
  const auth = useAuthStore()
  const ctx = useStoreContext()
  const toast = useToast()

  const orders = ref<WorkOrder[]>([])
  const filterType = ref<WorkOrderType | 'ALL'>('ALL')
  const filterStatus = ref<WorkOrderStatus | 'ALL'>('ALL')
  const loaded = ref(false)

  async function load() {
    try {
      const params: { storeCode?: string; type?: string; status?: string } = {}
      const sc = ctx.currentStoreCode
      if (sc) params.storeCode = sc
      const { data } = await woApi.listWorkOrders(params)
      orders.value = data.map(adapt)
      loaded.value = true
    } catch (e) {
      orders.value = []
      toast.error(errMsg(e, '工单加载失败，请稍后重试'))
    }
  }

  async function seed() {
    await ctx.loadStores()
    await load()
  }

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
    return [...list].sort(
      (a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()
    )
  })

  function get(id: string) {
    return orders.value.find((o) => o.id === id)
  }

  async function create(input: {
    type: WorkOrderType
    title: string
    description: string
    customerName?: string
    project?: string
    room?: string
    priority?: WorkOrderPriority
    deadline?: string
    assignee?: string
  }): Promise<WorkOrder | null> {
    if (!auth.can('workorder:create')) {
      toast.error('无创建工单权限，请联系管理员')
      return null
    }
    try {
      const sc = ctx.currentStoreCode
      if (!sc) {
        toast.error('未获取到当前门店，请稍后重试')
        return null
      }
      const { data } = await woApi.createWorkOrder({
        storeCode: sc,
        type: input.type,
        title: input.title.trim(),
        description: input.description.trim(),
        customerName: input.customerName?.trim() || null,
        project: input.project?.trim() || null,
        room: input.room?.trim() || null,
        assignee: input.assignee?.trim() || null,
        priority: input.priority || 'MEDIUM',
        deadline: input.deadline ? new Date(input.deadline).toISOString() : null,
      })
      await load()
      return adapt(data)
    } catch (e) {
      toast.error(errMsg(e, '创建工单失败，请稍后重试'))
      return null
    }
  }

  async function start(id: string): Promise<boolean> {
    if (!auth.can('workorder:edit')) {
      toast.error('无操作权限，请联系管理员')
      return false
    }
    try {
      await woApi.startWorkOrder(id)
      await load()
      return true
    } catch (e) {
      toast.error(errMsg(e, '开始处理失败，请稍后重试'))
      return false
    }
  }

  async function complete(id: string, note?: string): Promise<boolean> {
    if (!auth.can('workorder:close')) {
      toast.error('无关闭工单权限，请联系管理员')
      return false
    }
    try {
      await woApi.completeWorkOrder(id, note)
      await load()
      return true
    } catch (e) {
      toast.error(errMsg(e, '完成工单失败，请稍后重试'))
      return false
    }
  }

  async function escalate(id: string, reason: string): Promise<boolean> {
    if (!auth.can('workorder:edit')) {
      toast.error('无操作权限，请联系管理员')
      return false
    }
    try {
      await woApi.escalateWorkOrder(id, reason)
      await load()
      return true
    } catch (e) {
      toast.error(errMsg(e, '升级工单失败，请稍后重试'))
      return false
    }
  }

  return {
    orders, filterType, filterStatus, loaded,
    pending, inProgress, done, escalated, overdue, filtered,
    get, create, start, complete, escalate, seed,
    TYPE_LABEL, STATUS_LABEL, TYPE_ICON,
  }
})
