// ============================================================
// 会员到店核销 store（M2-09）
// 扫码核销 / 预约到店 / 直接到店，异常标记（非本人、已核销）。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

export type CheckinMethod = 'SCAN' | 'APPOINTMENT' | 'WALKIN'
export type CheckinStatus = 'DONE' | 'EXCEPTION' | 'PENDING'
export type CheckinExceptionReason = 'NONE' | 'NOT_SELF' | 'ALREADY_DONE' | 'NO_APPOINTMENT' | 'INFO_MISMATCH'

export interface CheckinTimeline {
  by: string
  text: string
  at: string
}

export interface CheckinRecord {
  id: string
  no: string
  customerName: string
  phone: string
  project: string
  method: CheckinMethod
  status: CheckinStatus
  exceptionReason: CheckinExceptionReason
  arrivedAt: string
  checkedAt?: string
  operator: string
  note?: string
  timeline: CheckinTimeline[]
}

const METHOD_LABEL: Record<CheckinMethod, string> = {
  SCAN: '扫码核销',
  APPOINTMENT: '预约到店',
  WALKIN: '直接到店',
}
const STATUS_LABEL: Record<CheckinStatus, string> = {
  DONE: '已核销',
  EXCEPTION: '异常',
  PENDING: '待确认',
}
const EXCEPTION_LABEL: Record<CheckinExceptionReason, string> = {
  NONE: '—',
  NOT_SELF: '非本人',
  ALREADY_DONE: '已核销',
  NO_APPOINTMENT: '无预约',
  INFO_MISMATCH: '信息不符',
}

export const useCheckinStore = defineStore('checkin', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const records = ref<CheckinRecord[]>([])
  const filterMethod = ref<CheckinMethod | 'ALL'>('ALL')
  const filterStatus = ref<CheckinStatus | 'ALL'>('ALL')

  const today = computed(() => records.value)
  const done = computed(() => records.value.filter((r) => r.status === 'DONE'))
  const pending = computed(() => records.value.filter((r) => r.status === 'PENDING'))
  const exception = computed(() => records.value.filter((r) => r.status === 'EXCEPTION'))

  const filtered = computed(() => {
    let list = records.value
    if (filterMethod.value !== 'ALL') list = list.filter((r) => r.method === filterMethod.value)
    if (filterStatus.value !== 'ALL') list = list.filter((r) => r.status === filterStatus.value)
    return list.sort((a, b) => new Date(b.arrivedAt).getTime() - new Date(a.arrivedAt).getTime())
  })

  function get(id: string) {
    return records.value.find((r) => r.id === id)
  }

  function register(input: { customerName: string; phone: string; project: string; method: CheckinMethod }): CheckinRecord | null {
    if (!auth.can('checkin:create')) {
      console.warn('[checkin] 无 checkin:create 权限')
      return null
    }
    const now = new Date()
    const r: CheckinRecord = {
      id: nextId('ci'),
      no: `CI-${now.toISOString().slice(0, 10).replace(/-/g, '')}-${String(records.value.length + 1).padStart(3, '0')}`,
      customerName: input.customerName.trim(),
      phone: input.phone.trim(),
      project: input.project.trim(),
      method: input.method,
      status: 'PENDING',
      exceptionReason: 'NONE',
      arrivedAt: now.toISOString(),
      operator: auth.user.name,
      timeline: [{ by: auth.user.name, text: `${METHOD_LABEL[input.method]}登记到店，待确认`, at: now.toISOString() }],
    }
    records.value.unshift(r)
    activity.log(auth.user.name, `登记到店 ${r.customerName}：${r.project}`, r.id)
    return r
  }

  function confirm(id: string): boolean {
    const r = records.value.find((x) => x.id === id)
    if (!r || r.status !== 'PENDING' || !auth.can('checkin:create')) return false
    const now = new Date().toISOString()
    r.status = 'DONE'
    r.checkedAt = now
    r.timeline.unshift({ by: auth.user.name, text: '核销确认完成', at: now })
    activity.log(auth.user.name, `到店核销确认 ${r.no}：${r.customerName}`, r.id)
    return true
  }

  function markException(id: string, reason: CheckinExceptionReason, note?: string): boolean {
    const r = records.value.find((x) => x.id === id)
    if (!r || r.status === 'DONE' || !auth.can('checkin:create')) return false
    const now = new Date().toISOString()
    r.status = 'EXCEPTION'
    r.exceptionReason = reason
    if (note) r.note = note
    r.timeline.unshift({ by: auth.user.name, text: `标记异常：${EXCEPTION_LABEL[reason]}${note ? `（${note}）` : ''}`, at: now })
    activity.log(auth.user.name, `到店异常 ${r.no}：${EXCEPTION_LABEL[reason]}`, r.id)
    return true
  }

  function resetToPending(id: string): boolean {
    const r = records.value.find((x) => x.id === id)
    if (!r || r.status !== 'EXCEPTION' || !auth.can('checkin:create')) return false
    const now = new Date().toISOString()
    r.status = 'PENDING'
    r.exceptionReason = 'NONE'
    r.note = undefined
    r.timeline.unshift({ by: auth.user.name, text: '异常已解除，重新待确认', at: now })
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
    const base: Array<{ customerName: string; phone: string; project: string; method: CheckinMethod; status: CheckinStatus; arrivedAt: string; exReason?: CheckinExceptionReason }> = [
      { customerName: '林晓彤', phone: '138****2046', project: '水光针基础款', method: 'SCAN', status: 'DONE', arrivedAt: today(9, 15) },
      { customerName: '王诗涵', phone: '139****8821', project: '射频紧肤下颌缘', method: 'APPOINTMENT', status: 'DONE', arrivedAt: today(9, 40) },
      { customerName: '陈美玲', phone: '137****5512', project: '超声炮全脸提拉', method: 'APPOINTMENT', status: 'PENDING', arrivedAt: today(10, 20) },
      { customerName: '赵雨晴', phone: '135****3390', project: '热玛吉四代面部', method: 'SCAN', status: 'PENDING', arrivedAt: today(10, 45) },
      { customerName: '周慧敏', phone: '133****1188', project: '皮秒祛斑全脸', method: 'WALKIN', status: 'EXCEPTION', arrivedAt: today(10, 5), exReason: 'NOT_SELF' },
      { customerName: '吴思琪', phone: '188****4409', project: '玻尿酸填充（太阳穴）', method: 'APPOINTMENT', status: 'DONE', arrivedAt: today(11, 10) },
      { customerName: '孙佳宁', phone: '136****7766', project: '光子嫩肤全模式', method: 'SCAN', status: 'EXCEPTION', arrivedAt: today(11, 30), exReason: 'ALREADY_DONE' },
    ]
    base.forEach((s, i) => {
      const isDone = s.status === 'DONE'
      const isEx = s.status === 'EXCEPTION'
      const id = nextId('ci')
      records.value.push({
        id,
        no: `CI-${s.arrivedAt.slice(0, 10).replace(/-/g, '')}-${String(i + 1).padStart(3, '0')}`,
        customerName: s.customerName,
        phone: s.phone,
        project: s.project,
        method: s.method,
        status: s.status,
        exceptionReason: s.exReason || 'NONE',
        arrivedAt: s.arrivedAt,
        checkedAt: isDone ? ago(2 - i * 0.2) : undefined,
        operator: ['夏沫（前台）', '李娜（护士）', '陈雅琳（店长）'][i % 3],
        note: isEx && s.exReason === 'NOT_SELF' ? '到店人与预约信息不一致，已电话核实' : undefined,
        timeline: [
          { by: '系统', text: `${METHOD_LABEL[s.method]}登记到店`, at: s.arrivedAt },
          ...(isDone ? [{ by: '夏沫（前台）', text: '核销确认完成', at: ago(2 - i * 0.2) }] : []),
          ...(isEx ? [{ by: '夏沫（前台）', text: `标记异常：${EXCEPTION_LABEL[s.exReason!]}`, at: ago(1) }] : []),
        ],
      })
    })
  }

  return {
    records, filterMethod, filterStatus,
    today, done, pending, exception, filtered,
    get, register, confirm, markException, resetToPending, seed,
    METHOD_LABEL, STATUS_LABEL, EXCEPTION_LABEL,
  }
})
