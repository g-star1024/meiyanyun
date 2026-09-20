// ============================================================
// Inspection 巡店检查 store（M2-10）
// 覆盖环境 / 服务 / 合规三类巡店单，含检查项打分明细与整改跟踪。
// 切真：列表/新建/整改指派/完成走后端，单号与整改派生由后端计算。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import * as inspectionApi from '@/api/inspection'
import { useToast } from '@/composables/useToast'
import { useAuthStore } from '@/stores/auth'
import { errMsg } from '@/stores/m5Coupon'
import { useStoreContext } from '@/stores/storeContext'

export type InspectionType = 'ENV' | 'SERVICE' | 'COMPLIANCE'
export type InspectionStatus = 'PENDING' | 'IN_PROGRESS' | 'DONE'
export type RectifyStatus = 'OPEN' | 'DOING' | 'DONE'

export interface InspectionItem {
  name: string
  score: number
  note?: string
}

export interface RectifyIssue {
  id: string
  desc: string
  owner: string
  status: RectifyStatus
  dueAt: string
  hasPhoto: boolean
}

export interface Inspection {
  id: string
  no: string
  store: string
  inspectedAt: string
  type: InspectionType
  totalScore: number
  issueCount: number
  status: InspectionStatus
  inspector: string
  items: InspectionItem[]
  issues: RectifyIssue[]
  createdAt: string
  completedAt?: string
}

const TYPE_LABEL: Record<InspectionType, string> = {
  ENV: '环境',
  SERVICE: '服务',
  COMPLIANCE: '合规',
}
const TYPE_ICON: Record<InspectionType, string> = {
  ENV: 'sun',
  SERVICE: 'customer',
  COMPLIANCE: 'shield',
}
const STATUS_LABEL: Record<InspectionStatus, string> = {
  PENDING: '待整改',
  IN_PROGRESS: '整改中',
  DONE: '已完成',
}
const RECTIFY_LABEL: Record<RectifyStatus, string> = {
  OPEN: '待整改',
  DOING: '整改中',
  DONE: '已完成',
}

const STORE_NAME: Record<string, string> = {
  SST01: '上海徐汇店',
  SST02: '上海浦东店',
  SST03: '北京国贸店',
}

function shortInsNo(no: string): string {
  const parts = no.split('-')
  const tail = parts[parts.length - 1]
  const seq = parseInt(tail, 10)
  if (!Number.isFinite(seq) || seq > 999) return no
  return `${parts[0]}-${parts[1]}-${String(seq).padStart(3, '0')}`
}

function adapt(d: inspectionApi.InspectionDto): Inspection {
  return {
    id: String(d.id),
    no: shortInsNo(d.no),
    store: STORE_NAME[d.storeCode] ?? d.storeCode,
    inspectedAt: d.inspectedAt,
    type: d.type as InspectionType,
    totalScore: d.totalScore,
    issueCount: d.issueCount,
    status: d.status as InspectionStatus,
    inspector: d.inspector,
    items: d.items.map((it) => ({ name: it.name, score: it.score, note: it.note ?? undefined })),
    issues: d.issues.map((iss) => ({
      id: String(iss.id),
      desc: iss.desc,
      owner: iss.owner,
      status: iss.status as RectifyStatus,
      dueAt: iss.dueAt,
      hasPhoto: iss.hasPhoto,
    })),
    createdAt: d.createdAt,
    completedAt: d.completedAt ?? undefined,
  }
}

export const useInspectionStore = defineStore('inspection', () => {
  const auth = useAuthStore()
  const ctx = useStoreContext()
  const toast = useToast()

  const orders = ref<Inspection[]>([])
  const filterType = ref<InspectionType | 'ALL'>('ALL')
  const filterStatus = ref<InspectionStatus | 'ALL'>('ALL')

  async function load() {
    try {
      const sc = ctx.currentStoreCode
      const { data } = await inspectionApi.listInspections({ storeCode: sc || undefined })
      orders.value = data.map(adapt)
    } catch (e) {
      orders.value = []
      toast.error(errMsg(e, '巡店检查加载失败，请稍后重试'))
    }
  }

  async function seed() {
    await ctx.loadStores()
    await load()
  }

  const pending = computed(() => orders.value.filter((o) => o.status === 'PENDING'))
  const inProgress = computed(() => orders.value.filter((o) => o.status === 'IN_PROGRESS'))
  const done = computed(() => orders.value.filter((o) => o.status === 'DONE'))

  const avgScore = computed(() => {
    if (!orders.value.length) return 0
    const sum = orders.value.reduce((s, o) => s + o.totalScore, 0)
    return Math.round((sum / orders.value.length) * 10) / 10
  })

  const monthCount = computed(() => {
    const now = new Date()
    return orders.value.filter((o) => {
      const d = new Date(o.inspectedAt)
      return d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth()
    }).length
  })

  const overdue = computed(() => {
    const now = Date.now()
    return orders.value.filter((o) => {
      if (o.status === 'DONE') return false
      return o.issues.some((iss) => iss.status !== 'DONE' && new Date(iss.dueAt).getTime() < now)
    })
  })

  const filtered = computed(() => {
    let list = orders.value
    if (filterType.value !== 'ALL') list = list.filter((o) => o.type === filterType.value)
    if (filterStatus.value !== 'ALL') list = list.filter((o) => o.status === filterStatus.value)
    return [...list].sort((a, b) => new Date(b.inspectedAt).getTime() - new Date(a.inspectedAt).getTime())
  })

  function get(id: string) {
    return orders.value.find((o) => o.id === id)
  }

  async function create(input: {
    store: string
    type: InspectionType
    inspector: string
    inspectedAt: string
    items: InspectionItem[]
  }): Promise<Inspection | null> {
    if (!auth.can('inspection:create')) {
      toast.error('无创建巡检单权限，请联系管理员')
      return null
    }
    try {
      const sc = ctx.currentStoreCode
      if (!sc) {
        toast.error('未获取到当前门店，请稍后重试')
        return null
      }
      const { data } = await inspectionApi.createInspection(
        { storeCode: sc },
        {
          type: input.type,
          inspector: input.inspector.trim(),
          inspectedAt: input.inspectedAt,
          items: input.items.map((it) => ({
            name: it.name,
            score: it.score,
            note: it.note?.trim() || null,
          })),
        },
      )
      await load()
      return adapt(data)
    } catch (e) {
      toast.error(errMsg(e, '创建巡检单失败，请稍后重试'))
      return null
    }
  }

  async function assignIssue(_inspectionId: string, issueId: string, owner: string): Promise<boolean> {
    if (!auth.can('inspection:edit')) {
      toast.error('无操作权限，请联系管理员')
      return false
    }
    try {
      await inspectionApi.assignIssue(issueId, owner)
      await load()
      return true
    } catch (e) {
      toast.error(errMsg(e, '整改指派失败，请稍后重试'))
      return false
    }
  }

  async function completeIssue(_inspectionId: string, issueId: string): Promise<boolean> {
    if (!auth.can('inspection:edit')) {
      toast.error('无操作权限，请联系管理员')
      return false
    }
    try {
      await inspectionApi.completeIssue(issueId)
      await load()
      return true
    } catch (e) {
      toast.error(errMsg(e, '完成整改失败，请稍后重试'))
      return false
    }
  }

  return {
    orders, filterType, filterStatus,
    pending, inProgress, done, overdue, avgScore, monthCount, filtered,
    get, create, assignIssue, completeIssue, seed,
    TYPE_LABEL, TYPE_ICON, STATUS_LABEL, RECTIFY_LABEL,
  }
})
