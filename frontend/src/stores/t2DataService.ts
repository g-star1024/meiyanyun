// ============================================================
// T2-04 数据服务目录 store
// 对内对外数据服务（API/数据集）+ 权限申请审批
// 对齐 T-G-中台与通用.md T2-04 详设
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

// ---- 类型 ----
export type ServiceType = 'API' | 'DATASET'
export type ServiceStatus = 'PUBLISHED' | 'DRAFT' | 'DEPRECATED'
export type PermissionStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

export interface DataService {
  id: string
  name: string
  type: ServiceType
  endpoint?: string
  method?: 'GET' | 'POST'
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
  id: string
  serviceId: string
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

export const useT2DataServiceStore = defineStore('t2DataService', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const services = ref<DataService[]>([])
  const permissions = ref<ServicePermission[]>([])
  const loaded = ref(false)

  // ---- 查询 ----
  const publishedCount = computed(() => services.value.filter((s) => s.status === 'PUBLISHED').length)
  const totalCalls24h = computed(() => services.value.reduce((s, x) => s + x.callCount24h, 0))
  const pendingPerms = computed(() => permissions.value.filter((p) => p.status === 'PENDING'))

  function get(id: string) { return services.value.find((s) => s.id === id) }

  function canPublish() { return auth.can('dataService:publish') }
  function canApply() { return auth.can('dataService:apply') }

  // ---- 命令 ----
  function createService(input: {
    name: string; type: ServiceType; endpoint?: string; method?: 'GET' | 'POST'
    description: string; fields: string[]; tags: string[]
  }): DataService {
    if (!canPublish()) throw new Error('无数据服务创建权限')
    const now = new Date().toISOString()
    const s: DataService = {
      id: nextId('svc'),
      name: input.name,
      type: input.type,
      endpoint: input.endpoint,
      method: input.method,
      description: input.description,
      owner: auth.user.name,
      status: 'DRAFT',
      callCount24h: 0,
      avgLatency: 0,
      errorRate: 0,
      fields: input.fields,
      tags: input.tags,
      version: 'v0.1',
      createdAt: now,
    }
    services.value.unshift(s)
    activity.log(auth.user.name, `创建数据服务「${s.name}」（${SERVICE_TYPE_LABEL[s.type]}）`, s.id)
    return s
  }

  function publishService(id: string) {
    if (!canPublish()) throw new Error('无发布权限')
    const s = get(id)
    if (!s || s.status === 'PUBLISHED') return
    s.status = 'PUBLISHED'
    s.version = s.version.startsWith('v0') ? 'v1.0' : s.version
    activity.log(auth.user.name, `发布数据服务「${s.name}」`, id)
  }

  function deprecateService(id: string) {
    if (!canPublish()) throw new Error('无发布权限')
    const s = get(id)
    if (!s || s.status !== 'PUBLISHED') return
    s.status = 'DEPRECATED'
    activity.log(auth.user.name, `下线数据服务「${s.name}」`, id)
  }

  function applyPermission(serviceId: string, reason: string): ServicePermission {
    if (!canApply()) throw new Error('无申请权限')
    const s = get(serviceId)
    if (!s) throw new Error('服务不存在')
    const p: ServicePermission = {
      id: nextId('perm'),
      serviceId,
      serviceName: s.name,
      applicant: auth.user.name,
      reason,
      status: 'PENDING',
      appliedAt: new Date().toISOString(),
      decidedAt: null,
      decidedBy: null,
    }
    permissions.value.unshift(p)
    activity.log(auth.user.name, `申请数据服务「${s.name}」权限`, p.id)
    return p
  }

  function approvePermission(id: string) {
    if (!canPublish()) throw new Error('无审批权限')
    const p = permissions.value.find((x) => x.id === id)
    if (!p || p.status !== 'PENDING') return
    p.status = 'APPROVED'
    p.decidedAt = new Date().toISOString()
    p.decidedBy = auth.user.name
    activity.log(auth.user.name, `批准数据服务权限：${p.serviceName}（${p.applicant}）`, id)
  }

  function rejectPermission(id: string) {
    if (!canPublish()) throw new Error('无审批权限')
    const p = permissions.value.find((x) => x.id === id)
    if (!p || p.status !== 'PENDING') return
    p.status = 'REJECTED'
    p.decidedAt = new Date().toISOString()
    p.decidedBy = auth.user.name
    activity.log(auth.user.name, `拒绝数据服务权限：${p.serviceName}（${p.applicant}）`, id)
  }

  // ---- 种子 ----
  function seed() {
    if (loaded.value) return
    loaded.value = true
    const now = Date.now()
    const hoursAgo = (h: number) => new Date(now - h * 3600_000).toISOString()
    const daysAgo = (d: number) => new Date(now - d * 86400_000).toISOString()

    type SSeed = Partial<DataService> & Pick<DataService, 'name' | 'type' | 'description' | 'owner' | 'status' | 'version' | 'fields' | 'tags'>
    const seedServices: SSeed[] = [
      {
        name: '客户 360 画像', type: 'API', endpoint: '/api/v1/customer/profile', method: 'GET',
        description: '根据 customer_id 返回客户完整画像，含基础信息、消费汇总、标签列表、最近到店',
        owner: '张数', status: 'PUBLISHED', version: 'v2.3',
        callCount24h: 4820, avgLatency: 86, errorRate: 0.12,
        fields: ['customer_id', 'name_mask', 'phone_mask', 'level', 'total_paid', 'last_visit_at', 'tags[]'],
        tags: ['客户', '画像', '高频'],
      },
      {
        name: '客户分群人群包', type: 'API', endpoint: '/api/v1/marketing/segment', method: 'POST',
        description: '传入标签组合条件，返回符合条件的 customer_id 列表（分页），支持 M5 营销圈选',
        owner: '李析', status: 'PUBLISHED', version: 'v1.5',
        callCount24h: 1240, avgLatency: 420, errorRate: 0.45,
        fields: ['segment_id', 'customer_ids[]', 'total_count', 'expire_at'],
        tags: ['营销', '分群', '标签'],
      },
      {
        name: '经营日报数据集', type: 'DATASET',
        description: '按门店、日期聚合的经营数据宽表（营业额/客流/转化/客单价），供 BI 工具直连',
        owner: '王治', status: 'PUBLISHED', version: 'v1.0',
        callCount24h: 86, avgLatency: 2400, errorRate: 0,
        fields: ['store_id', 'date', 'revenue', 'customer_count', 'conversion_rate', 'avg_ticket'],
        tags: ['经营', '报表', 'BI'],
      },
      {
        name: '高价值客户名单', type: 'DATASET',
        description: '累计消费 ≥ 50,000 元的客户名单，T+1 更新',
        owner: '张数', status: 'PUBLISHED', version: 'v1.2',
        callCount24h: 32, avgLatency: 1800, errorRate: 0,
        fields: ['customer_id', 'total_paid', 'first_paid_at', 'last_paid_at'],
        tags: ['客户', '高价值'],
      },
      {
        name: '流失预警推送', type: 'API', endpoint: '/api/v1/risk/churn/predict', method: 'POST',
        description: '输入 customer_id 列表，返回每位客户的流失风险评分（0-100）和建议动作',
        owner: 'AI 模型组', status: 'PUBLISHED', version: 'v1.1',
        callCount24h: 2180, avgLatency: 156, errorRate: 0.28,
        fields: ['customer_id', 'churn_score', 'risk_level', 'suggest_action'],
        tags: ['AI', '流失预警', '风控'],
      },
      {
        name: '项目消费明细', type: 'API', endpoint: '/api/v1/order/items', method: 'GET',
        description: '订单项目明细查询，支持按门店、项目、时间范围筛选（草稿，待联调）',
        owner: '李析', status: 'DRAFT', version: 'v0.1',
        callCount24h: 0, avgLatency: 0, errorRate: 0,
        fields: ['order_no', 'item_id', 'item_name', 'quantity', 'price', 'paid_amount'],
        tags: ['订单', '明细'],
      },
      {
        name: '老版客户标签接口', type: 'API', endpoint: '/api/v0/customer/tags', method: 'GET',
        description: '【已废弃】旧版客户标签查询接口，请使用 /api/v1/customer/profile 中的 tags 字段',
        owner: '张数', status: 'DEPRECATED', version: 'v0.9',
        callCount24h: 42, avgLatency: 220, errorRate: 1.2,
        fields: ['customer_id', 'tag_codes[]'],
        tags: ['废弃', '标签'],
      },
      {
        name: '门店业绩排行榜', type: 'DATASET',
        description: '门店日/周/月业绩排名宽表，供经营驾驶舱和大屏使用',
        owner: '王治', status: 'PUBLISHED', version: 'v1.0',
        callCount24h: 128, avgLatency: 980, errorRate: 0,
        fields: ['store_id', 'period', 'rank', 'revenue', 'target', 'completion_rate'],
        tags: ['门店', '业绩', '大屏'],
      },
    ]

    seedServices.forEach((s, i) => {
      services.value.push({
        id: nextId('svc'),
        name: s.name, type: s.type, endpoint: s.endpoint, method: s.method,
        description: s.description, owner: s.owner, status: s.status, version: s.version,
        callCount24h: s.callCount24h ?? 0, avgLatency: s.avgLatency ?? 0, errorRate: s.errorRate ?? 0,
        fields: s.fields, tags: s.tags,
        createdAt: daysAgo(80 - i * 5),
      })
    })

    // 权限申请记录
    const custSvc = services.value[0]
    const segSvc = services.value[1]
    const churnSvc = services.value[4]
    const perms: Array<Partial<ServicePermission> & Pick<ServicePermission, 'serviceId' | 'serviceName' | 'applicant' | 'reason' | 'status'>> = [
      { serviceId: segSvc.id, serviceName: segSvc.name, applicant: '营销-小赵', reason: '618 大促人群圈选，需要调用分群接口筛选高价值客户', status: 'PENDING' },
      { serviceId: churnSvc.id, serviceName: churnSvc.name, applicant: '客服-小钱', reason: '客服中心日常流失预警外呼，需要批量查询客户流失评分', status: 'PENDING' },
      { serviceId: custSvc.id, serviceName: custSvc.name, applicant: 'BI-小孙', reason: 'BI 看板客户明细 drill-down', status: 'APPROVED' },
      { serviceId: segSvc.id, serviceName: segSvc.name, applicant: '短信运营-小李', reason: '生日月客户触达活动', status: 'APPROVED' },
      { serviceId: custSvc.id, serviceName: custSvc.name, applicant: '外部合作方', reason: '需要全量客户数据做联合建模', status: 'REJECTED' },
    ]
    perms.forEach((p, i) => {
      permissions.value.push({
        id: nextId('perm'),
        serviceId: p.serviceId, serviceName: p.serviceName, applicant: p.applicant, reason: p.reason, status: p.status,
        appliedAt: hoursAgo((i + 2) * 8),
        decidedAt: p.status === 'PENDING' ? null : hoursAgo((i + 1) * 6),
        decidedBy: p.status === 'PENDING' ? null : '张数',
      })
    })
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
