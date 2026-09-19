// ============================================================
// 通用异常中心 store（M2-18 / P5-B63 卡2 L83）—— 真实 API 驱动
// 三源只读归集：BOM 自动扣料失败 / 划扣核销异常 / 财务异常账单。
// 数据来自 txn-service /api/txn/exceptions（聚合层，权限码 exception:view）。
// 中心零写操作：不升级/不闭环/不加备注，处置一律按 disposeRoute 跳源处置视图。
// 源差异由 source 承载（来源筛选 + SOURCE_LABEL）；type 后端恒 BUSINESS，
// 前端四枚举字典（SYSTEM/BUSINESS/DEVICE/COMPLAINT）保留不动，TYPE 标签恒显业务异常。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import {
  listExceptions,
  type ExceptionCenterItemDTO, type ExceptionSource, type ExceptionStatus, type ExceptionLevel,
} from '@/api/exception'

export type ExType = 'SYSTEM' | 'BUSINESS' | 'DEVICE' | 'COMPLAINT'
export type ExLevel = ExceptionLevel
export type ExStatus = ExceptionStatus
export type ExSource = ExceptionSource

export interface ExTimeline {
  by: string
  text: string
  at: string
}

export interface ExceptionEvent {
  id: string
  no: string
  /** 统一业务类型，后端恒 BUSINESS（四枚举保留以兼容字典/模板） */
  type: ExType
  level: ExLevel
  title: string
  description: string
  status: ExStatus
  assignee: string
  /** 异常来源（BOM_DEDUCT/WRITEOFF/FIN_ABNORMAL），列表标签与筛选维度 */
  source: ExSource
  storeCode: string
  storeName: string
  occurredAt: string
  closedAt?: string
  timeline: ExTimeline[]
  /** 源处置视图路由，中心仅跳转 */
  disposeRoute: string
}

const TYPE_LABEL: Record<ExType, string> = {
  SYSTEM: '系统异常',
  BUSINESS: '业务异常',
  DEVICE: '设备异常',
  COMPLAINT: '客诉',
}
const LEVEL_LABEL: Record<ExLevel, string> = {
  HIGH: '高',
  MEDIUM: '中',
  LOW: '低',
}
const STATUS_LABEL: Record<ExStatus, string> = {
  PENDING: '待处理',
  PROCESSING: '处理中',
  CLOSED: '已闭环',
}
const TYPE_ICON: Record<ExType, string> = {
  SYSTEM: 'settings',
  BUSINESS: 'order',
  DEVICE: 'alert',
  COMPLAINT: 'chat',
}
const SOURCE_LABEL: Record<ExSource, string> = {
  BOM_DEDUCT: '耗材扣料',
  WRITEOFF: '划扣核销',
  FIN_ABNORMAL: '异常账务',
}

function adapt(dto: ExceptionCenterItemDTO): ExceptionEvent {
  return {
    id: dto.id,
    no: dto.no,
    type: 'BUSINESS',
    level: dto.level,
    title: dto.title,
    description: dto.description || '',
    status: dto.status,
    assignee: dto.assignee || '—',
    source: dto.source,
    storeCode: dto.storeCode,
    storeName: dto.storeName || dto.storeCode,
    occurredAt: dto.occurredAt,
    closedAt: dto.closedAt ?? undefined,
    timeline: (dto.timeline || []).map((t) => ({ by: t.by || '系统', text: t.text || '', at: t.at || '' })),
    disposeRoute: dto.disposeRoute,
  }
}

export const useExceptionStore = defineStore('exception', () => {
  const events = ref<ExceptionEvent[]>([])
  const filterSource = ref<ExSource | 'ALL'>('ALL')
  const filterStatus = ref<ExStatus | 'ALL'>('ALL')
  const loading = ref(false)
  const error = ref('')

  const pending = computed(() => events.value.filter((e) => e.status === 'PENDING'))
  const processing = computed(() => events.value.filter((e) => e.status === 'PROCESSING'))
  const closed = computed(() => events.value.filter((e) => e.status === 'CLOSED'))
  const highLevel = computed(() => events.value.filter((e) => e.level === 'HIGH' && e.status !== 'CLOSED'))
  const todayClosed = computed(() => {
    const today = new Date().toDateString()
    return events.value.filter((e) => e.closedAt && new Date(e.closedAt).toDateString() === today)
  })

  const filtered = computed(() => {
    let list = events.value
    if (filterSource.value !== 'ALL') list = list.filter((e) => e.source === filterSource.value)
    if (filterStatus.value !== 'ALL') list = list.filter((e) => e.status === filterStatus.value)
    return [...list].sort((a, b) => {
      const levelRank: Record<ExLevel, number> = { HIGH: 0, MEDIUM: 1, LOW: 2 }
      if (a.level !== b.level) return levelRank[a.level] - levelRank[b.level]
      return new Date(b.occurredAt).getTime() - new Date(a.occurredAt).getTime()
    })
  })

  function get(id: string) {
    return events.value.find((e) => e.id === id)
  }

  /** 拉取三源归集列表；后端已做数据域收敛（门店角色仅本店）。 */
  async function load() {
    loading.value = true
    error.value = ''
    try {
      const { data } = await listExceptions()
      events.value = (data || []).map(adapt)
    } catch (e) {
      console.error('[exception] 异常中心列表加载失败', e)
      error.value = '异常数据加载失败，请稍后重试'
      events.value = []
    } finally {
      loading.value = false
    }
  }

  return {
    events, filterSource, filterStatus, loading, error,
    pending, processing, closed, highLevel, todayClosed, filtered,
    get, load,
    TYPE_LABEL, LEVEL_LABEL, STATUS_LABEL, TYPE_ICON, SOURCE_LABEL,
  }
})
