import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

// ============================================================
// 调度中心 store（M1 集团管控 / 调度中心）
// - Resource 调度资源：医生 / 治疗室 / 设备，有班次时段
// - Job 待派单：已预约但未分配医生/时段/房间的工单
// - Assignment 排班/派单：resource × 时段上的占用块（关联预约）
// - 派单 dispatch：把 pending job 派给某资源的某时段；释放 release
// - 资源利用率 = 已占用时长 / 可用时长
// ============================================================

export type ResourceType = 'DOCTOR' | 'ROOM' | 'DEVICE'
export const RES_TYPE_LABEL: Record<ResourceType, string> = { DOCTOR: '医生', ROOM: '治疗室', DEVICE: '设备' }

export interface Resource {
  id: string
  type: ResourceType
  name: string
  title?: string
  room?: string
  workStart: string // "09:00"
  workEnd: string   // "20:00"
  status: 'ON' | 'OFF'
}

export interface Job {
  id: string
  jobNo: string
  customerName: string
  itemName: string
  durationMin: number
  preferredDoctor?: string
  priority: 'NORMAL' | 'URGENT'
  status: 'PENDING' | 'ASSIGNED'
  createdAt: string
}

export interface Assignment {
  id: string
  resourceId: string
  jobId: string
  customerName: string
  itemName: string
  start: string // "10:00"
  end: string
  status: 'SCHEDULED' | 'IN_PROGRESS' | 'DONE'
}

let _cid = 0
function cid(p: string) { _cid += 1; return `${p}-${Date.now().toString(36)}-${_cid}` }

// 半小时时段（9:00-20:00，共22格）
export const SLOTS: string[] = (() => {
  const arr: string[] = []
  for (let h = 9; h < 20; h++) {
    arr.push(`${String(h).padStart(2, '0')}:00`)
    arr.push(`${String(h).padStart(2, '0')}:30`)
  }
  arr.push('20:00')
  return arr
})()

function toMin(t: string) { const [h, m] = t.split(':').map(Number); return h * 60 + m }

export const useM1DispatchStore = defineStore('m1Dispatch', () => {
  const resources = ref<Resource[]>([])
  const jobs = ref<Job[]>([])
  const assignments = ref<Assignment[]>([])
  const seeded = ref(false)

  const doctors = computed(() => resources.value.filter((r) => r.type === 'DOCTOR'))
  const rooms = computed(() => resources.value.filter((r) => r.type === 'ROOM'))
  const pendingJobs = computed(() => jobs.value.filter((j) => j.status === 'PENDING'))
  const urgentJobs = computed(() => pendingJobs.value.filter((j) => j.priority === 'URGENT'))

  function assignmentsOf(resourceId: string) {
    return assignments.value.filter((a) => a.resourceId === resourceId)
  }
  function resource(id: string) { return resources.value.find((r) => r.id === id) }

  function isSlotBusy(resourceId: string, slot: string): Assignment | undefined {
    const s = toMin(slot)
    return assignments.value.find((a) => a.resourceId === resourceId && toMin(a.start) <= s && toMin(a.end) > s)
  }

  // 利用率：已占用时段 / 工作时段（按30分钟格）
  function utilization(r: Resource): number {
    const total = (toMin(r.workEnd) - toMin(r.workStart)) / 30
    const occ = new Set<number>()
    for (const a of assignmentsOf(r.id)) {
      for (let m = toMin(a.start); m < toMin(a.end); m += 30) {
        if (m >= toMin(r.workStart) && m < toMin(r.workEnd)) occ.add(m)
      }
    }
    return total ? Math.round((occ.size / total) * 100) : 0
  }

  const stats = computed(() => {
    const onDocs = doctors.value.filter((d) => d.status === 'ON').length
    const utils = doctors.value.filter((d) => d.status === 'ON').map((d) => utilization(d))
    const avgUtil = utils.length ? Math.round(utils.reduce((s, x) => s + x, 0) / utils.length) : 0
    return {
      onDoctors: onDocs,
      rooms: rooms.value.length,
      pending: pendingJobs.value.length,
      urgent: urgentJobs.value.length,
      avgUtil,
    }
  })

  // 派单
  function canDispatch(job: Job, resourceId: string, start: string): boolean {
    if (job.status !== 'PENDING') return false
    const r = resource(resourceId)
    if (!r || r.status !== 'ON') return false
    const endMin = toMin(start) + job.durationMin
    if (toMin(start) < toMin(r.workStart) || endMin > toMin(r.workEnd)) return false
    // 检查冲突
    for (let m = toMin(start); m < endMin; m += 30) {
      const hh = String(Math.floor(m / 60)).padStart(2, '0')
      const mm = String(m % 60).padStart(2, '0')
      if (isSlotBusy(resourceId, `${hh}:${mm}`)) return false
    }
    return true
  }

  function dispatch(jobId: string, resourceId: string, start: string): boolean {
    const job = jobs.value.find((j) => j.id === jobId)
    if (!job || !canDispatch(job, resourceId, start)) return false
    const endMin = toMin(start) + job.durationMin
    const end = `${String(Math.floor(endMin / 60)).padStart(2, '0')}:${String(endMin % 60).padStart(2, '0')}`
    assignments.value.push({
      id: cid('asg'), resourceId, jobId, customerName: job.customerName,
      itemName: job.itemName, start, end, status: 'SCHEDULED',
    })
    job.status = 'ASSIGNED'
    return true
  }

  function release(assignmentId: string) {
    const a = assignments.value.find((x) => x.id === assignmentId)
    if (!a || a.status === 'DONE') return
    assignments.value = assignments.value.filter((x) => x.id !== assignmentId)
    const job = jobs.value.find((j) => j.id === a.jobId)
    if (job) job.status = 'PENDING'
  }

  function seed() {
    if (seeded.value) return
    resources.value = [
      { id: cid('res'), type: 'DOCTOR', name: '顾屿', title: '主治医师', room: 'A治疗室', workStart: '09:00', workEnd: '18:00', status: 'ON' },
      { id: cid('res'), type: 'DOCTOR', name: '沈知意', title: '皮肤主诊', room: 'B治疗室', workStart: '10:00', workEnd: '20:00', status: 'ON' },
      { id: cid('res'), type: 'DOCTOR', name: '陆沉', title: '注射医师', room: 'C治疗室', workStart: '09:30', workEnd: '18:30', status: 'ON' },
      { id: cid('res'), type: 'DOCTOR', name: '江晚', title: '皮肤科医生', room: 'A治疗室', workStart: '09:00', workEnd: '18:00', status: 'OFF' },
      { id: cid('res'), type: 'ROOM', name: 'A治疗室', workStart: '09:00', workEnd: '20:00', status: 'ON' },
      { id: cid('res'), type: 'ROOM', name: 'B治疗室', workStart: '09:00', workEnd: '20:00', status: 'ON' },
      { id: cid('res'), type: 'ROOM', name: 'C治疗室', workStart: '09:00', workEnd: '20:00', status: 'ON' },
      { id: cid('res'), type: 'DEVICE', name: '热玛吉FLX', room: 'C治疗室', workStart: '09:00', workEnd: '20:00', status: 'ON' },
      { id: cid('res'), type: 'DEVICE', name: '超皮秒', room: 'B治疗室', workStart: '09:00', workEnd: '20:00', status: 'ON' },
    ]
    const doc1 = resources.value[0].id, doc2 = resources.value[1].id

    // 已有排班块
    const mkAsg = (resourceId: string, customerName: string, itemName: string, start: string, durMin: number, status: Assignment['status'] = 'SCHEDULED') => {
      const endMin = toMin(start) + durMin
      assignments.value.push({
        id: cid('asg'), resourceId, jobId: cid('job'), customerName, itemName, start,
        end: `${String(Math.floor(endMin / 60)).padStart(2, '0')}:${String(endMin % 60).padStart(2, '0')}`, status,
      })
    }
    mkAsg(doc1, '周童', '保妥适瘦脸', '10:00', 30, 'DONE')
    mkAsg(doc1, '吴念', '乔雅登填充', '11:00', 60, 'IN_PROGRESS')
    mkAsg(doc1, '郑好', '水光补水', '14:00', 40)
    mkAsg(doc2, '许棠', '光子嫩肤', '13:00', 40, 'DONE')
    mkAsg(doc2, '何蔓', '热玛吉面部', '15:00', 90)

    // 待派单
    jobs.value = [
      { id: cid('job'), jobNo: 'JOB2026082501', customerName: '林一', itemName: '保妥适瘦脸', durationMin: 30, preferredDoctor: '顾屿', priority: 'NORMAL', status: 'PENDING', createdAt: new Date().toISOString() },
      { id: cid('job'), jobNo: 'JOB2026082502', customerName: '苏晚', itemName: '水光补水', durationMin: 40, priority: 'NORMAL', status: 'PENDING', createdAt: new Date().toISOString() },
      { id: cid('job'), jobNo: 'JOB2026082503', customerName: '高阳', itemName: '超皮秒祛斑', durationMin: 40, preferredDoctor: '沈知意', priority: 'URGENT', status: 'PENDING', createdAt: new Date().toISOString() },
      { id: cid('job'), jobNo: 'JOB2026082504', customerName: '郑重', itemName: '热玛吉面部', durationMin: 90, priority: 'NORMAL', status: 'PENDING', createdAt: new Date().toISOString() },
    ]
    seeded.value = true
  }

  return {
    resources, jobs, assignments, SLOTS, RES_TYPE_LABEL,
    doctors, rooms, pendingJobs, urgentJobs, stats,
    resource, assignmentsOf, isSlotBusy, utilization, canDispatch, dispatch, release, seed,
  }
})
