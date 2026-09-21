// ============================================================
// Handover 聚合 store（交接班 / 双签交接）—— B83 卡2 L118 切真
// 数据源：store-service /api/stores/handovers（HandoverController）。
// 状态机：DRAFT（交班人填写中）→ SUBMITTED（已提交待接班确认）→ CONFIRMED（接班人已确认）。
// - 一笔交接单承载本班营收汇总、待跟进客户、未完成事项、重要提醒、钱款/设备交接。
// - 交班人提交即锁定内容，接班人确认后形成双签留痕（submittedAt / confirmedAt + 操作人）。
// - 接班人可在确认时填写备注；CONFIRMED 后不可再改，仅可勾选跟进事项。
// 权限：handover:create 建单/提交 / handover:edit 接班确认。
// 适配层：后端 id(number)/金额(分)/UTC 时间 → 页面契约 id(string)/金额(元)/上海朴素 ISO。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useAuthStore } from './auth'
import { errMsg } from './m5Coupon'
import { useToast } from '@/composables/useToast'
import { shDateStr } from '@/utils/datetime'
import {
  listHandovers, createHandover, updateHandoverDraft,
  addHandoverTodo, removeHandoverTodo, toggleHandoverTodo,
  submitHandover, confirmHandover, sendBackHandover,
  type HandoverDTO,
} from '@/api/handover'

export type HandoverShift = 'MORNING' | 'EVENING' | 'FULL'
export type HandoverStatus = 'DRAFT' | 'SUBMITTED' | 'CONFIRMED'

export interface HandoverTodo {
  id: string
  /** 待跟进事项类型：客户跟进 / 待办事务 / 异常处理 */
  kind: 'CUSTOMER' | 'TASK' | 'ISSUE'
  content: string
  /** 紧急程度 */
  urgent: boolean
  done?: boolean
}

export interface HandoverTimelineEntry {
  at: string
  by: string
  action: string
  detail?: string
}

export interface Handover {
  id: string
  handoverNo: string
  shift: HandoverShift
  /** 交班营业日 */
  date: string
  status: HandoverStatus
  /** 交班人 / 接班人 */
  fromName: string
  toName: string
  // —— 本班经营摘要 ——
  revenueAmount: number
  orderCount: number
  arrivalCount: number
  // —— 交接内容 ——
  /** 待跟进客户/事项 */
  todos: HandoverTodo[]
  /** 重要提醒（口头重点） */
  importantNote?: string
  /** 钱款交接说明（现金、备用金等） */
  cashNote?: string
  /** 设备/物料交接说明 */
  equipmentNote?: string
  /** 接班人确认备注 */
  confirmNote?: string
  // —— 双签留痕 ——
  submittedAt?: string
  confirmedAt?: string
  timeline: HandoverTimelineEntry[]
  createdAt: string
}

const SHIFT_LABEL: Record<HandoverShift, string> = {
  MORNING: '早班', EVENING: '晚班', FULL: '全天',
}
const STATUS_LABEL: Record<HandoverStatus, string> = {
  DRAFT: '草稿', SUBMITTED: '待确认', CONFIRMED: '已交接',
}
const TODO_KIND_LABEL: Record<HandoverTodo['kind'], string> = {
  CUSTOMER: '客户跟进', TASK: '待办事务', ISSUE: '异常处理',
}

/** 后端时间（UTC ISO 或 jsonb epoch 秒）→ 上海时区朴素 ISO（供页面切片格式化） */
function toShLocalIso(v: string | number | null | undefined): string | undefined {
  if (v == null || v === '') return undefined
  const d = typeof v === 'number' ? new Date(v * 1000) : new Date(v)
  if (Number.isNaN(d.getTime())) return undefined
  return d.toLocaleString('sv-SE', { timeZone: 'Asia/Shanghai', hour12: false }).replace(' ', 'T')
}

function mapDto(d: HandoverDTO): Handover {
  return {
    id: String(d.id),
    handoverNo: d.handoverNo,
    shift: d.shift,
    date: d.date,
    status: d.status,
    fromName: d.fromName,
    toName: d.toName,
    revenueAmount: (d.revenueAmount ?? 0) / 100,
    orderCount: d.orderCount ?? 0,
    arrivalCount: d.arrivalCount ?? 0,
    todos: (d.todos ?? []).map((t) => ({
      id: String(t.id), kind: t.kind, content: t.content, urgent: t.urgent, done: t.done,
    })),
    importantNote: d.importantNote || undefined,
    cashNote: d.cashNote || undefined,
    equipmentNote: d.equipmentNote || undefined,
    confirmNote: d.confirmNote || undefined,
    submittedAt: toShLocalIso(d.submittedAt),
    confirmedAt: toShLocalIso(d.confirmedAt),
    createdAt: toShLocalIso(d.createdAt) ?? '',
    timeline: (d.timeline ?? []).map((t) => ({
      at: toShLocalIso(t.at) ?? '', by: t.by, action: t.action, detail: t.detail,
    })),
  }
}

export const useHandoverStore = defineStore('handover', () => {
  const auth = useAuthStore()
  const toast = useToast()

  const handovers = ref<Handover[]>([])
  const loading = ref(false)
  let fetched = false

  const drafts = computed(() => handovers.value.filter((h) => h.status === 'DRAFT'))
  const submitted = computed(() => handovers.value.filter((h) => h.status === 'SUBMITTED'))
  const confirmed = computed(() => handovers.value.filter((h) => h.status === 'CONFIRMED'))

  const pendingCount = computed(() => submitted.value.length)
  /** 今日已交接笔数 */
  const todayConfirmed = computed(() => {
    const today = shDateStr()
    return confirmed.value.filter((h) => h.date.slice(0, 10) === today).length
  })
  /** 未完成待跟进事项总数（已交接单中的未勾选项） */
  const openTodos = computed(() =>
    confirmed.value.reduce((n, h) => n + h.todos.filter((t) => !t.done).length, 0),
  )

  function get(id: string) {
    return handovers.value.find((h) => h.id === id)
  }

  function replace(h: Handover) {
    const i = handovers.value.findIndex((x) => x.id === h.id)
    if (i >= 0) handovers.value.splice(i, 1, h)
    else handovers.value.unshift(h)
  }

  /** 拉取本店交接班列表（幂等，重复进入页面强制刷新） */
  async function fetch(force = true) {
    if (fetched && !force) return
    loading.value = true
    try {
      const { data } = await listHandovers()
      handovers.value = data.map(mapDto)
      fetched = true
    } catch (e) {
      toast.error(errMsg(e, '交接班列表加载失败'))
    } finally {
      loading.value = false
    }
  }

  /** 创建交接班草稿 */
  async function create(input: {
    shift: HandoverShift
    date: string
    toName: string
  }): Promise<Handover | null> {
    if (!auth.can('handover:create')) {
      console.warn('[handover] 无 handover:create 权限')
      return null
    }
    try {
      const { data } = await createHandover({
        shift: input.shift,
        date: input.date.slice(0, 10),
        toName: input.toName,
      })
      const h = mapDto(data)
      replace(h)
      return h
    } catch (e) {
      toast.error(errMsg(e, '创建交接班单失败'))
      return null
    }
  }

  /** 更新草稿内容（仅 DRAFT 可编辑；金额元→分） */
  async function updateDraft(
    id: string,
    patch: Partial<Pick<Handover, 'revenueAmount' | 'orderCount' | 'arrivalCount' | 'importantNote' | 'cashNote' | 'equipmentNote' | 'toName'>>,
  ): Promise<boolean> {
    if (!auth.can('handover:create')) return false
    try {
      const { data } = await updateHandoverDraft(Number(id), {
        toName: patch.toName,
        revenueAmount: patch.revenueAmount == null ? undefined : Math.round(patch.revenueAmount * 100),
        orderCount: patch.orderCount,
        arrivalCount: patch.arrivalCount,
        importantNote: patch.importantNote,
        cashNote: patch.cashNote,
        equipmentNote: patch.equipmentNote,
      })
      replace(mapDto(data))
      return true
    } catch (e) {
      toast.error(errMsg(e, '保存草稿失败'))
      return false
    }
  }

  /** 添加待跟进事项（仅 DRAFT） */
  async function addTodo(id: string, todo: Omit<HandoverTodo, 'id' | 'done'>): Promise<boolean> {
    if (!auth.can('handover:create')) return false
    try {
      const { data } = await addHandoverTodo(Number(id), todo)
      replace(mapDto(data))
      return true
    } catch (e) {
      toast.error(errMsg(e, '添加待办失败'))
      return false
    }
  }

  async function removeTodo(id: string, todoId: string): Promise<boolean> {
    if (!auth.can('handover:create')) return false
    try {
      const { data } = await removeHandoverTodo(Number(id), Number(todoId))
      replace(mapDto(data))
      return true
    } catch (e) {
      toast.error(errMsg(e, '移除待办失败'))
      return false
    }
  }

  /** 接班人勾选已跟进事项（仅 CONFIRMED） */
  async function toggleTodo(id: string, todoId: string): Promise<boolean> {
    if (!auth.can('handover:edit')) return false
    try {
      const { data } = await toggleHandoverTodo(Number(id), Number(todoId))
      replace(mapDto(data))
      return true
    } catch (e) {
      toast.error(errMsg(e, '勾选失败'))
      return false
    }
  }

  /** 交班人提交：DRAFT → SUBMITTED */
  async function submit(id: string): Promise<boolean> {
    if (!auth.can('handover:create')) return false
    try {
      const { data } = await submitHandover(Number(id))
      replace(mapDto(data))
      return true
    } catch (e) {
      toast.error(errMsg(e, '提交失败'))
      return false
    }
  }

  /** 接班人确认：SUBMITTED → CONFIRMED（双签完成） */
  async function confirm(id: string, note?: string): Promise<boolean> {
    if (!auth.can('handover:edit')) {
      console.warn('[handover] 无 handover:edit 权限')
      return false
    }
    try {
      const { data } = await confirmHandover(Number(id), note?.trim() || undefined)
      replace(mapDto(data))
      return true
    } catch (e) {
      toast.error(errMsg(e, '确认接收失败'))
      return false
    }
  }

  /** 退回补充：SUBMITTED → DRAFT（接班人退回交班人修改） */
  async function sendBack(id: string, reason: string): Promise<boolean> {
    if (!auth.can('handover:edit')) return false
    try {
      const { data } = await sendBackHandover(Number(id), reason)
      replace(mapDto(data))
      return true
    } catch (e) {
      toast.error(errMsg(e, '退回失败'))
      return false
    }
  }

  return {
    handovers, loading, drafts, submitted, confirmed, pendingCount, todayConfirmed, openTodos,
    get, fetch, create, updateDraft, addTodo, removeTodo, toggleTodo, submit, confirm, sendBack,
    SHIFT_LABEL, STATUS_LABEL, TODO_KIND_LABEL,
  }
})

export { SHIFT_LABEL as HANDOVER_SHIFT_LABEL, STATUS_LABEL as HANDOVER_STATUS_LABEL, TODO_KIND_LABEL as HANDOVER_TODO_KIND_LABEL }
