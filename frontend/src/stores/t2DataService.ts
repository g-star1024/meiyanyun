// ============================================================
// T2-04 数据服务目录 store（T2-B3 切真 customer-service）
// 对内对外数据服务（API/数据集）+ 权限申请审批
// 对齐 T-G-中台与通用.md T2-04 详设
// 数据源：/api/customer/t2/dataservice（V68/V69 落库；查询=dataService:view，
// 创建/发布/下线/审批=dataService:publish，申请=dataService:apply）。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { useToast } from '@/composables/useToast'
import { errMsg } from './m5Coupon'
import * as api from '@/api/t2DataService'
import type { ServiceView, PermissionView } from '@/api/t2DataService'

// ---- 类型 ----
export type ServiceType = 'API' | 'DATASET'
export type ServiceStatus = 'PUBLISHED' | 'DRAFT' | 'DEPRECATED'
export type PermissionStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

export interface DataService {
  id: number
  name: string
  type: ServiceType
  endpoint?: string | null
  method?: 'GET' | 'POST' | null
  description: string
  owner: string
  status: ServiceStatus
  callCount24h: number
  avgLatency: number
  errorRate: number
  fields: string[]
  tags: string[]
  version: string
  createdAt: string
}

export interface ServicePermission {
  id: number
  serviceId: number
  serviceName: string
  applicant: string
  reason: string
  status: PermissionStatus
  appliedAt: string
  decidedAt?: string | null
  decidedBy?: string | null
}

export const SERVICE_TYPE_LABEL: Record<ServiceType, string> = {
  API: 'API 接口',
  DATASET: '数据集',
}

export const SERVICE_STATUS_LABEL: Record<ServiceStatus, string> = {
  PUBLISHED: '已发布',
  DRAFT: '草稿',
  DEPRECATED: '已下线',
}

export const PERMISSION_STATUS_LABEL: Record<PermissionStatus, string> = {
  PENDING: '待审批',
  APPROVED: '已通过',
  REJECTED: '已拒绝',
}

function mapService(v: ServiceView): DataService {
  return {
    ...v,
    type: v.type as ServiceType,
    status: v.status as ServiceStatus,
    method: (v.method as 'GET' | 'POST' | null) ?? null,
  }
}

function mapPerm(v: PermissionView): ServicePermission {
  return {
    ...v,
    status: v.status as PermissionStatus,
  }
}

export const useT2DataServiceStore = defineStore('t2DataService', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()
  const toast = useToast()

  const services = ref<DataService[]>([])
  const permissions = ref<ServicePermission[]>([])
  const loaded = ref(false)
  const loading = ref(false)
  const loadError = ref('')

  // ---- 查询 ----
  const publishedCount = computed(() => services.value.filter((s) => s.status === 'PUBLISHED').length)
  const totalCalls24h = computed(() => services.value.reduce((s, x) => s + x.callCount24h, 0))
  const pendingPerms = computed(() => permissions.value.filter((p) => p.status === 'PENDING'))

  function get(id: number) { return services.value.find((s) => s.id === id) }

  function canPublish() { return auth.can('dataService:publish') }
  function canApply() { return auth.can('dataService:apply') }

  function replaceService(next: DataService) {
    const idx = services.value.findIndex((s) => s.id === next.id)
    if (idx >= 0) services.value.splice(idx, 1, next)
    else services.value.unshift(next)
  }

  function replacePerm(next: ServicePermission) {
    const idx = permissions.value.findIndex((p) => p.id === next.id)
    if (idx >= 0) permissions.value.splice(idx, 1, next)
    else permissions.value.unshift(next)
  }

  // ---- 装载 ----
  async function load() {
    loading.value = true
    loadError.value = ''
    try {
      const [svcList, permList] = await Promise.all([api.fetchServices(), api.fetchPermissions()])
      services.value = svcList.map(mapService)
      permissions.value = permList.map(mapPerm)
      loaded.value = true
    } catch (e) {
      loadError.value = errMsg(e)
      console.warn('[t2DataService] 数据服务加载失败', e)
    } finally {
      loading.value = false
    }
  }

  /** 进页装载（B86 范式：每次进页重拉真实数据） */
  async function seed() {
    await load()
  }

  // ---- 命令 ----
  async function createService(input: {
    name: string; type: ServiceType; endpoint?: string; method?: 'GET' | 'POST'
    description: string; fields: string[]; tags: string[]
  }): Promise<DataService | null> {
    if (!canPublish()) {
      toast.error('无数据服务创建权限')
      return null
    }
    try {
      const s = mapService(await api.createService({ ...input }))
      services.value.unshift(s)
      activity.log(auth.user.name, `创建数据服务「${s.name}」（${SERVICE_TYPE_LABEL[s.type]}）`, String(s.id))
      toast.success(`数据服务「${s.name}」已创建（草稿）`)
      return s
    } catch (e) {
      toast.error(errMsg(e, '创建失败'))
      return null
    }
  }

  async function publishService(id: number): Promise<boolean> {
    if (!canPublish()) {
      toast.error('无发布权限')
      return false
    }
    try {
      const s = mapService(await api.publishService(id))
      replaceService(s)
      activity.log(auth.user.name, `发布数据服务「${s.name}」`, String(id))
      toast.success(`数据服务「${s.name}」已发布（${s.version}）`)
      return true
    } catch (e) {
      toast.error(errMsg(e, '发布失败'))
      return false
    }
  }

  async function deprecateService(id: number): Promise<boolean> {
    if (!canPublish()) {
      toast.error('无发布权限')
      return false
    }
    try {
      const s = mapService(await api.deprecateService(id))
      replaceService(s)
      activity.log(auth.user.name, `下线数据服务「${s.name}」`, String(id))
      toast.success(`数据服务「${s.name}」已下线`)
      return true
    } catch (e) {
      toast.error(errMsg(e, '下线失败'))
      return false
    }
  }

  async function applyPermission(serviceId: number, reason: string): Promise<ServicePermission | null> {
    if (!canApply()) {
      toast.error('无申请权限')
      return null
    }
    try {
      const p = mapPerm(await api.applyPermission(serviceId, { reason }))
      permissions.value.unshift(p)
      activity.log(auth.user.name, `申请数据服务「${p.serviceName}」权限`, String(p.id))
      toast.success(`已提交「${p.serviceName}」权限申请，待审批`)
      return p
    } catch (e) {
      toast.error(errMsg(e, '申请失败'))
      return null
    }
  }

  async function approvePermission(id: number): Promise<boolean> {
    if (!canPublish()) {
      toast.error('无审批权限')
      return false
    }
    try {
      const p = mapPerm(await api.approvePermission(id))
      replacePerm(p)
      activity.log(auth.user.name, `批准数据服务权限：${p.serviceName}（${p.applicant}）`, String(id))
      toast.success(`已批准「${p.serviceName}」权限申请`)
      return true
    } catch (e) {
      toast.error(errMsg(e, '批准失败'))
      return false
    }
  }

  async function rejectPermission(id: number): Promise<boolean> {
    if (!canPublish()) {
      toast.error('无审批权限')
      return false
    }
    try {
      const p = mapPerm(await api.rejectPermission(id))
      replacePerm(p)
      activity.log(auth.user.name, `拒绝数据服务权限：${p.serviceName}（${p.applicant}）`, String(id))
      toast.success(`已拒绝「${p.serviceName}」权限申请`)
      return true
    } catch (e) {
      toast.error(errMsg(e, '拒绝失败'))
      return false
    }
  }

  return {
    services, permissions,
    publishedCount, totalCalls24h, pendingPerms,
    SERVICE_TYPE_LABEL, SERVICE_STATUS_LABEL, PERMISSION_STATUS_LABEL,
    get, canPublish, canApply,
    createService, publishService, deprecateService,
    applyPermission, approvePermission, rejectPermission, seed,
  }
})
