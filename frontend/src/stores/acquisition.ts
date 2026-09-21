// ============================================================
// 拓客活动 store（M2-16）
// 体验价 / 拼团 / 老带新活动，引流转化漏斗。
// 切真：列表/新建/启用/结束走后端，单号与状态派生由后端计算。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import * as acquisitionApi from '@/api/acquisition'
import { useToast } from '@/composables/useToast'
import { useAuthStore } from '@/stores/auth'
import { errMsg } from '@/stores/m5Coupon'
import { useStoreContext } from '@/stores/storeContext'

export type AcqType = 'TRIAL' | 'GROUP' | 'REFERRAL'
export type AcqStatus = 'ONGOING' | 'ENDED' | 'DRAFT'

export interface AcquisitionCampaign {
  id: string
  no: string
  name: string
  type: AcqType
  exposure: number      // 曝光人数
  arrival: number       // 到店人数
  deal: number          // 成交人数
  budget: number
  spent: number
  status: AcqStatus
  startDate: string
  endDate: string
  owner: string
  channel: string
}

const TYPE_LABEL: Record<AcqType, string> = {
  TRIAL: '体验价',
  GROUP: '拼团',
  REFERRAL: '老带新',
}
const STATUS_LABEL: Record<AcqStatus, string> = {
  ONGOING: '进行中',
  ENDED: '已结束',
  DRAFT: '草稿',
}
const TYPE_ICON: Record<AcqType, string> = {
  TRIAL: 'marketing',
  GROUP: 'customer',
  REFERRAL: 'user-check',
}

function shortAqNo(no: string): string {
  const parts = no.split('-')
  const tail = parts[parts.length - 1]
  const seq = parseInt(tail, 10)
  if (!Number.isFinite(seq) || seq > 999) return no
  return `${parts[0]}-${parts[1]}-${String(seq).padStart(3, '0')}`
}

function adapt(d: acquisitionApi.AcquisitionDto): AcquisitionCampaign {
  return {
    id: String(d.id),
    no: shortAqNo(d.no),
    name: d.name,
    type: d.type as AcqType,
    exposure: d.exposure,
    arrival: d.arrival,
    deal: d.deal,
    budget: d.budget,
    spent: d.spent,
    status: d.status as AcqStatus,
    startDate: d.startDate,
    endDate: d.endDate,
    owner: d.owner,
    channel: d.channel,
  }
}

export const useAcquisitionStore = defineStore('acquisition', () => {
  const auth = useAuthStore()
  const ctx = useStoreContext()
  const toast = useToast()

  const campaigns = ref<AcquisitionCampaign[]>([])
  const filterType = ref<AcqType | 'ALL'>('ALL')
  const filterStatus = ref<AcqStatus | 'ALL'>('ALL')

  async function load() {
    try {
      const sc = ctx.currentStoreCode
      const { data } = await acquisitionApi.listAcquisitions({ storeCode: sc || undefined })
      campaigns.value = data.map(adapt)
    } catch (e) {
      campaigns.value = []
      toast.error(errMsg(e, '拓客活动加载失败，请稍后重试'))
    }
  }

  async function seed() {
    await ctx.loadStores()
    await load()
  }

  const ongoing = computed(() => campaigns.value.filter((c) => c.status === 'ONGOING'))
  const ended = computed(() => campaigns.value.filter((c) => c.status === 'ENDED'))
  const monthLeads = computed(() =>
    campaigns.value.reduce((sum, c) => sum + c.arrival, 0),
  )
  const monthDeals = computed(() =>
    campaigns.value.reduce((sum, c) => sum + c.deal, 0),
  )
  const avgConversion = computed(() => {
    const arrivalTotal = campaigns.value.reduce((s, c) => s + c.arrival, 0)
    if (!arrivalTotal) return 0
    return Math.round((monthDeals.value / arrivalTotal) * 1000) / 10
  })

  const filtered = computed(() => {
    let list = campaigns.value
    if (filterType.value !== 'ALL') list = list.filter((c) => c.type === filterType.value)
    if (filterStatus.value !== 'ALL') list = list.filter((c) => c.status === filterStatus.value)
    const rank: Record<AcqStatus, number> = { ONGOING: 0, DRAFT: 1, ENDED: 2 }
    return [...list].sort((a, b) => rank[a.status] - rank[b.status] || new Date(b.startDate).getTime() - new Date(a.startDate).getTime())
  })

  function get(id: string) {
    return campaigns.value.find((c) => c.id === id)
  }

  function conversionRate(c: AcquisitionCampaign): number {
    if (!c.arrival) return 0
    return Math.round((c.deal / c.arrival) * 1000) / 10
  }

  async function create(input: {
    name: string
    type: AcqType
    budget: number
    channel: string
    startDate?: string
    endDate?: string
    owner?: string
  }): Promise<AcquisitionCampaign | null> {
    if (!auth.can('acquisition:edit')) {
      toast.error('无创建拓客活动权限，请联系管理员')
      return null
    }
    try {
      const sc = ctx.currentStoreCode
      if (!sc) {
        toast.error('未获取到当前门店，请稍后重试')
        return null
      }
      const { data } = await acquisitionApi.createAcquisition(
        { storeCode: sc },
        {
          name: input.name.trim(),
          type: input.type,
          budget: input.budget,
          channel: input.channel.trim(),
          startDate: input.startDate,
          endDate: input.endDate,
          owner: input.owner?.trim() || undefined,
        },
      )
      await load()
      return adapt(data)
    } catch (e) {
      toast.error(errMsg(e, '创建拓客活动失败，请稍后重试'))
      return null
    }
  }

  async function launch(id: string): Promise<boolean> {
    if (!auth.can('acquisition:edit')) {
      toast.error('无操作权限，请联系管理员')
      return false
    }
    try {
      await acquisitionApi.launchAcquisition(id)
      await load()
      return true
    } catch (e) {
      toast.error(errMsg(e, '启用拓客活动失败，请稍后重试'))
      return false
    }
  }

  async function end(id: string): Promise<boolean> {
    if (!auth.can('acquisition:edit')) {
      toast.error('无操作权限，请联系管理员')
      return false
    }
    try {
      await acquisitionApi.endAcquisition(id)
      await load()
      return true
    } catch (e) {
      toast.error(errMsg(e, '结束拓客活动失败，请稍后重试'))
      return false
    }
  }

  return {
    campaigns, filterType, filterStatus,
    ongoing, ended, monthLeads, monthDeals, avgConversion, filtered,
    get, create, launch, end, conversionRate, seed,
    TYPE_LABEL, STATUS_LABEL, TYPE_ICON,
  }
})
