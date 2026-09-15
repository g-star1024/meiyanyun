import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import * as api from '@/api/dispatch'
import type {
  DispatchResourceDTO, DispatchJobDTO, DispatchAssignmentDTO, DispatchResourceType,
} from '@/api/dispatch'
import { useToast } from '@/composables/useToast'
import { errMsg } from '@/stores/m5Coupon'
import { shDateStr } from '@/utils/datetime'

// ============================================================
// 调度中心 store（M1 集团管控 / 调度中心 · B49 卡12 切真）
// - 数据源：/api/txn/dispatch（txn 聚合 org 医生 + store 治疗室；DEVICE 无真实源诚实空态）
// - Job = 当日「已预约/已到店」且无活跃派单的预约（已到店排前），start 锚定 apptTime
// - Assignment 随 Resource 行内联返回（仅 SCHEDULED/IN_PROGRESS；RELEASED 保留行不回读）
// - durationMin 固定 60、priority 全 NORMAL、班次固定 09:00-20:00（无源，见 Backlog）
// ============================================================

export type ResourceType = DispatchResourceType
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
  apptTime: string
  preferredDoctorId?: string
  preferredDoctor?: string
  priority: 'NORMAL'
  status: 'PENDING' | 'ASSIGNED'
  arrived: boolean
  createdAt: string
}

export interface Assignment {
  id: string
  resourceType: ResourceType
  resourceId: string
  jobId: string
  customerName: string
  itemName: string
  start: string // "10:00"
  end: string
  status: 'SCHEDULED' | 'IN_PROGRESS'
}

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

function adaptResource(d: DispatchResourceDTO): Resource {
  return {
    id: d.id,
    type: d.type,
    name: d.name,
    title: d.title ?? undefined,
    room: d.room ?? undefined,
    workStart: d.workStart,
    workEnd: d.workEnd,
    status: d.status === 'ON' ? 'ON' : 'OFF',
  }
}

function adaptJob(d: DispatchJobDTO): Job {
  return {
    id: d.id,
    jobNo: d.jobNo,
    customerName: d.customerName,
    itemName: d.itemName,
    durationMin: d.durationMin,
    apptTime: d.apptTime,
    preferredDoctorId: d.preferredDoctorId ?? undefined,
    preferredDoctor: d.preferredDoctor ?? undefined,
    priority: 'NORMAL',
    status: 'PENDING',
    arrived: d.arrived,
    createdAt: d.createdAt,
  }
}

function adaptAssignment(d: DispatchAssignmentDTO): Assignment {
  return {
    id: d.id,
    resourceType: (d.resourceType === 'ROOM' ? 'ROOM' : 'DOCTOR') as ResourceType,
    resourceId: d.resourceId,
    jobId: d.jobId,
    customerName: d.customerName,
    itemName: d.itemName,
    start: d.start,
    end: d.end,
    status: d.status === 'IN_PROGRESS' ? 'IN_PROGRESS' : 'SCHEDULED',
  }
}

export const useM1DispatchStore = defineStore('m1Dispatch', () => {
  const toast = useToast()
  const resources = ref<Resource[]>([])
  const jobs = ref<Job[]>([])
  const assignments = ref<Assignment[]>([])
  const loaded = ref(false)
  const loading = ref(false)
  const bizDate = ref(shDateStr())
  const storeCode = ref('SST01')

  const doctors = computed(() => resources.value.filter((r) => r.type === 'DOCTOR'))
  const rooms = computed(() => resources.value.filter((r) => r.type === 'ROOM'))
  const pendingJobs = computed(() => jobs.value.filter((j) => j.status === 'PENDING'))

  function assignmentsOf(resourceId: string) {
    return assignments.value.filter((a) => a.resourceId === resourceId)
  }
  function resource(id: string) { return resources.value.find((r) => r.id === id) }
  function jobOf(jobId: string) { return jobs.value.find((j) => j.id === jobId) }

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
      avgUtil,
    }
  })

  // 门店切换后重载（视图调用；loaded 不做一次性门控）
  async function load(sc: string = storeCode.value, date: string = bizDate.value) {
    storeCode.value = sc
    bizDate.value = date
    if (loading.value) return
    loading.value = true
    try {
      const [rres, rjobs] = await Promise.all([
        api.listDispatchResources({ storeCode: sc, date }),
        api.listDispatchJobs({ storeCode: sc, date }),
      ])
      resources.value = rres.data.map(adaptResource)
      jobs.value = rjobs.data.map(adaptJob)
      assignments.value = rres.data.flatMap((r) => r.assignments.map(adaptAssignment))
      // 已被活跃派单占用的预约不在 jobs 返回；RELEASED 不回读，释放后重新进队列
      loaded.value = true
    } catch (e) {
      resources.value = []
      jobs.value = []
      assignments.value = []
      toast.error(errMsg(e, '调度数据加载失败'))
    } finally {
      loading.value = false
    }
  }

  // 兼容视图 onMounted 的 seed() 入口（切真后为真实加载）
  function seed(sc?: string) {
    return load(sc ?? storeCode.value)
  }

  // 派单前端预检（最终以后端 409/422 中文错误为准）
  function canDispatch(job: Job, resourceId: string, start: string): boolean {
    if (job.status !== 'PENDING') return false
    const r = resource(resourceId)
    if (!r || r.status !== 'ON') return false
    const endMin = toMin(start) + job.durationMin
    if (toMin(start) < toMin(r.workStart) || endMin > toMin(r.workEnd)) return false
    for (let m = toMin(start); m < endMin; m += 30) {
      const hh = String(Math.floor(m / 60)).padStart(2, '0')
      const mm = String(m % 60).padStart(2, '0')
      if (isSlotBusy(resourceId, `${hh}:${mm}`)) return false
    }
    return true
  }

  async function dispatch(jobId: string, resourceId: string, start: string): Promise<boolean> {
    const job = jobOf(jobId)
    const r = resource(resourceId)
    if (!job || !r || !canDispatch(job, resourceId, start)) return false
    try {
      const { data } = await api.dispatchJob(storeCode.value, {
        apptNo: job.id,
        resourceType: r.type,
        resourceId,
      })
      assignments.value.push(adaptAssignment(data))
      jobs.value = jobs.value.filter((j) => j.id !== jobId)
      return true
    } catch (e) {
      toast.error(errMsg(e, '派单失败'))
      return false
    }
  }

  async function release(assignmentId: string) {
    const a = assignments.value.find((x) => x.id === assignmentId)
    if (!a) return
    try {
      await api.releaseAssignment(storeCode.value, a.id)
      await load()
    } catch (e) {
      toast.error(errMsg(e, '释放失败'))
    }
  }

  return {
    resources, jobs, assignments, loaded, loading, bizDate, storeCode, SLOTS, RES_TYPE_LABEL,
    doctors, rooms, pendingJobs, stats,
    resource, assignmentsOf, isSlotBusy, utilization, canDispatch, dispatch, release, seed, load,
  }
})
