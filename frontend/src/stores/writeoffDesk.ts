// ============================================================
// 划扣执行台 store（M2-01）
// 门店端"待划扣队列 + 双签执行"：与 /writeoff 交易核销视图独立。
// 覆盖今日待划扣、已划扣、异常单，支持操作人+复核人双签划扣。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

export type WdSource = 'APPOINTMENT' | 'WALKIN'
export type WdStatus = 'PENDING' | 'DONE' | 'EXCEPTION'
export type WdExceptionReason = 'NONE' | 'CUSTOMER_ABSENT' | 'COUNT_MISMATCH' | 'EQUIPMENT_FAULT' | 'OTHER'

export interface WdTimeline {
  by: string
  text: string
  at: string
}

export interface WriteoffDeskItem {
  id: string
  no: string
  customerName: string
  phone: string
  project: string
  cardName: string
  totalCount: number
  remainingCount: number
  amount: number
  operator: string
  reviewer?: string
  source: WdSource
  status: WdStatus
  exceptionReason: WdExceptionReason
  appointmentTime: string
  executedAt?: string
  timeline: WdTimeline[]
}

const SOURCE_LABEL: Record<WdSource, string> = {
  APPOINTMENT: '预约到店',
  WALKIN: '直接到店',
}
const STATUS_LABEL: Record<WdStatus, string> = {
  PENDING: '待执行',
  DONE: '已划扣',
  EXCEPTION: '异常',
}
const EXCEPTION_LABEL: Record<WdExceptionReason, string> = {
  NONE: '—',
  CUSTOMER_ABSENT: '客户未到',
  COUNT_MISMATCH: '次数不符',
  EQUIPMENT_FAULT: '设备故障',
  OTHER: '其他',
}

export const useWriteoffDeskStore = defineStore('writeoffDesk', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const items = ref<WriteoffDeskItem[]>([])
  const filterSource = ref<WdSource | 'ALL'>('ALL')
  const filterStatus = ref<WdStatus | 'ALL'>('ALL')

  const pending = computed(() => items.value.filter((i) => i.status === 'PENDING'))
  const done = computed(() => items.value.filter((i) => i.status === 'DONE'))
  const exception = computed(() => items.value.filter((i) => i.status === 'EXCEPTION'))
  const todayAmount = computed(() =>
    done.value.reduce((sum, i) => sum + i.amount, 0),
  )

  const filtered = computed(() => {
    let list = items.value
    if (filterSource.value !== 'ALL') list = list.filter((i) => i.source === filterSource.value)
    if (filterStatus.value !== 'ALL') list = list.filter((i) => i.status === filterStatus.value)
    return list.sort((a, b) => new Date(b.appointmentTime).getTime() - new Date(a.appointmentTime).getTime())
  })

  function get(id: string) {
    return items.value.find((i) => i.id === id)
  }

  function execute(id: string, reviewer: string): boolean {
    const it = items.value.find((i) => i.id === id)
    if (!it || it.status !== 'PENDING') return false
    if (!auth.can('writeoff:create')) {
      console.warn('[writeoffDesk] 无 writeoff:create 权限')
      return false
    }
    if (!reviewer.trim()) return false
    const now = new Date().toISOString()
    it.status = 'DONE'
    it.reviewer = reviewer.trim()
    it.executedAt = now
    it.remainingCount = Math.max(0, it.remainingCount - 1)
    it.timeline.unshift({ by: auth.user.name, text: `双签划扣完成，复核人：${reviewer}`, at: now })
    activity.log(auth.user.name, `划扣执行 ${it.no}：${it.customerName} - ${it.project}`, it.id)
    return true
  }

  function markException(id: string, reason: WdExceptionReason, note?: string): boolean {
    const it = items.value.find((i) => i.id === id)
    if (!it || it.status === 'DONE') return false
    if (!auth.can('writeoff:edit')) {
      console.warn('[writeoffDesk] 无 writeoff:edit 权限')
      return false
    }
    const now = new Date().toISOString()
    it.status = 'EXCEPTION'
    it.exceptionReason = reason
    it.timeline.unshift({ by: auth.user.name, text: `标记异常：${EXCEPTION_LABEL[reason]}${note ? `（${note}）` : ''}`, at: now })
    activity.log(auth.user.name, `划扣异常 ${it.no}：${EXCEPTION_LABEL[reason]}`, it.id)
    return true
  }

  function resetToPending(id: string): boolean {
    const it = items.value.find((i) => i.id === id)
    if (!it || it.status !== 'EXCEPTION') return false
    if (!auth.can('writeoff:edit')) return false
    const now = new Date().toISOString()
    it.status = 'PENDING'
    it.exceptionReason = 'NONE'
    it.timeline.unshift({ by: auth.user.name, text: '异常已解除，重新进入待执行队列', at: now })
    return true
  }

  // ===== 种子数据 =====
  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    const now = new Date()
    const today = (h: number, m = 0) => {
      const d = new Date(now)
      d.setHours(h, m, 0, 0)
      return d.toISOString()
    }
    const ago = (h: number) => new Date(now.getTime() - h * 3600_000).toISOString()
    const staff = ['顾屿（医师）', '周敏（美容师）', '李娜（护士）', '陈雅琳（店长）']
    const base: Array<Partial<WriteoffDeskItem> & {
      customerName: string; phone: string; project: string; cardName: string
      totalCount: number; remainingCount: number; amount: number; source: WdSource
      status: WdStatus; appointmentTime: string
    }> = [
      { customerName: '陈美玲', phone: '138****2046', project: '超声炮全脸提拉', cardName: '超声炮次卡（10次）', totalCount: 10, remainingCount: 6, amount: 3980, source: 'APPOINTMENT', status: 'PENDING', appointmentTime: today(10, 30) },
      { customerName: '赵雨晴', phone: '139****8821', project: '热玛吉四代面部', cardName: '热玛吉单次体验', totalCount: 1, remainingCount: 1, amount: 12800, source: 'APPOINTMENT', status: 'PENDING', appointmentTime: today(11, 0) },
      { customerName: '孙佳宁', phone: '137****5512', project: '光子嫩肤全模式', cardName: '光子嫩肤年卡（12次）', totalCount: 12, remainingCount: 8, amount: 680, source: 'WALKIN', status: 'PENDING', appointmentTime: today(11, 15) },
      { customerName: '林晓彤', phone: '135****3390', project: '水光针基础款', cardName: '水光针疗程（5次）', totalCount: 5, remainingCount: 3, amount: 1280, source: 'APPOINTMENT', status: 'DONE', appointmentTime: today(9, 30) },
      { customerName: '王诗涵', phone: '136****7766', project: '射频紧肤下颌缘', cardName: '射频紧肤次卡（6次）', totalCount: 6, remainingCount: 2, amount: 980, source: 'APPOINTMENT', status: 'DONE', appointmentTime: today(9, 0) },
      { customerName: '周慧敏', phone: '133****1188', project: '皮秒祛斑全脸', cardName: '皮秒疗程（3次）', totalCount: 3, remainingCount: 1, amount: 2680, source: 'WALKIN', status: 'EXCEPTION', appointmentTime: today(10, 0) },
      { customerName: '吴思琪', phone: '188****4409', project: '玻尿酸填充（太阳穴）', cardName: '玻尿酸单次', totalCount: 1, remainingCount: 1, amount: 3680, source: 'APPOINTMENT', status: 'PENDING', appointmentTime: today(14, 0) },
    ]
    base.forEach((s, i) => {
      const isDone = s.status === 'DONE'
      const isEx = s.status === 'EXCEPTION'
      const id = nextId('wd')
      items.value.push({
        id,
        no: `WD-${s.appointmentTime.slice(0, 10).replace(/-/g, '')}-${String(i + 1).padStart(3, '0')}`,
        customerName: s.customerName,
        phone: s.phone,
        project: s.project,
        cardName: s.cardName,
        totalCount: s.totalCount,
        remainingCount: s.remainingCount,
        amount: s.amount,
        operator: staff[i % staff.length],
        reviewer: isDone ? '陈雅琳（店长）' : undefined,
        source: s.source,
        status: s.status,
        exceptionReason: isEx ? 'EQUIPMENT_FAULT' : 'NONE',
        appointmentTime: s.appointmentTime,
        executedAt: isDone ? ago(2 - i * 0.3) : undefined,
        timeline: [
          { by: '系统', text: '生成待划扣任务', at: s.appointmentTime },
          ...(isDone ? [{ by: staff[i % staff.length], text: '双签划扣完成，复核人：陈雅琳（店长）', at: ago(2 - i * 0.3) }] : []),
          ...(isEx ? [{ by: staff[i % staff.length], text: '标记异常：设备故障（皮秒仪器需重启）', at: ago(1) }] : []),
        ],
      })
    })
  }

  return {
    items, filterSource, filterStatus,
    pending, done, exception, todayAmount, filtered,
    get, execute, markException, resetToPending, seed,
    SOURCE_LABEL, STATUS_LABEL, EXCEPTION_LABEL,
  }
})
