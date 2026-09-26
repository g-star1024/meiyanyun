// ============================================================
// Journey 客户旅程 store（M3-07）
// 覆盖：预约→到店→咨询→消费→回访→复购 六阶段。
// 对齐设计稿 SCREEN-M3-07：4 KPI + 左客户列表 + 右旅程时间轴 + 消费明细。
// M3-B4 卡1 切真：数据源 seed mock → GET /api/customer/m3/journey（DESIGN-M3
// §3 D2/D5/D6 只读聚合，KPI 为候选全集口径由后端统算）；导出签名全保留。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'
import { getJourney, type JourneyCustomerDto } from '@/api/journey'
import { useActivityStore } from './activity'
import { useAuthStore } from './auth'

export type JourneyStage = 'APPT' | 'ARRIVE' | 'CONSULT' | 'PAY' | 'FOLLOW' | 'REBUY'

export interface JourneyNode {
  stage: JourneyStage
  date: string // MM-DD
  title: string
  desc: string
  amount?: number
  operator?: string
  done: boolean
}

export interface JourneyCustomer {
  id: string
  name: string
  avatarLetter: string
  phoneMask: string
  level: string
  currentStage: JourneyStage
  risk: 'HIGH' | 'MEDIUM' | 'LOW'
  /** 全部旅程节点（按顺序） */
  nodes: JourneyNode[]
}

export interface JourneyKpi {
  inProgress: number
  convertedThisWeek: number
  avgDays: number
  churnRisk: number
}

const STAGE_LABEL: Record<JourneyStage, string> = {
  APPT: '预约',
  ARRIVE: '到店',
  CONSULT: '咨询',
  PAY: '消费',
  FOLLOW: '回访',
  REBUY: '复购',
}

const STAGE_COLOR: Record<JourneyStage, string> = {
  APPT: 'var(--c-blue)',
  ARRIVE: 'var(--c-teal)',
  CONSULT: 'var(--c-brand)',
  PAY: 'var(--c-purple)',
  FOLLOW: 'var(--c-orange-dark)',
  REBUY: 'var(--c-teal)',
}

const STAGES: JourneyStage[] = ['APPT', 'ARRIVE', 'CONSULT', 'PAY', 'FOLLOW', 'REBUY']
const RANGE_DAYS = { '180d': 180, '90d': 90, '30d': 30 } as const

function mapCustomer(dto: JourneyCustomerDto): JourneyCustomer {
  return {
    id: String(dto.id),
    name: dto.name ?? '—',
    avatarLetter: dto.avatarLetter ?? (dto.name ? dto.name.charAt(0) : '—'),
    phoneMask: dto.phoneMask ?? '—',
    level: dto.level ?? '普通',
    currentStage: STAGES.includes(dto.currentStage as JourneyStage) ? (dto.currentStage as JourneyStage) : 'APPT',
    risk: dto.risk === 'HIGH' || dto.risk === 'MEDIUM' ? dto.risk : 'LOW',
    nodes: (dto.nodes ?? [])
      .filter((n) => STAGES.includes(n.stage as JourneyStage))
      .map((n) => ({
        stage: n.stage as JourneyStage,
        date: n.date ?? '',
        title: n.title ?? '',
        desc: n.desc ?? '',
        amount: n.amount == null ? undefined : Number(n.amount),
        operator: n.operator == null || n.operator === '' ? undefined : n.operator,
        done: !!n.done,
      })),
  }
}

export const useJourneyStore = defineStore('journey', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const customers = ref<JourneyCustomer[]>([])
  const kpi = ref<JourneyKpi>({ inProgress: 0, convertedThisWeek: 0, avgDays: 0, churnRisk: 0 })
  const loading = ref(false)
  const selectedId = ref<string | null>(null)
  const range = ref<'180d' | '90d' | '30d'>('180d')

  const selected = computed<JourneyCustomer | null>(() => {
    if (selectedId.value) return customers.value.find((c) => c.id === selectedId.value) ?? null
    return customers.value[0] ?? null
  })

  // —— 选中客户最近一笔消费明细 ——
  const lastPayment = computed(() => {
    if (!selected.value) return null
    const pay = [...selected.value.nodes].reverse().find((n) => n.stage === 'PAY' && n.done)
    return pay ?? null
  })

  async function load() {
    loading.value = true
    try {
      const dto = await getJourney(RANGE_DAYS[range.value], 50)
      customers.value = (dto.customers ?? []).map(mapCustomer)
      kpi.value = dto.kpi ?? { inProgress: 0, convertedThisWeek: 0, avgDays: 0, churnRisk: 0 }
      if (selectedId.value && !customers.value.some((c) => c.id === selectedId.value)) {
        selectedId.value = null
      }
    } finally {
      loading.value = false
    }
  }

  watch(range, () => {
    void load()
  })

  // —— 首屏入口（签名保留，内部走真实 API） ——
  let loaded = false
  function seed() {
    if (loaded) return
    loaded = true
    void load()
  }

  function select(id: string) {
    selectedId.value = id
  }

  function addFollowNote(text: string) {
    if (!selected.value || !auth.can('followup:edit')) return
    activity.log(auth.user.name, `为 ${selected.value.name} 添加旅程备注：${text}`, selected.value.id)
  }

  return {
    customers, kpi, loading, selectedId, selected, range,
    lastPayment,
    select, addFollowNote, seed, load,
    STAGE_LABEL, STAGE_COLOR,
  }
})
