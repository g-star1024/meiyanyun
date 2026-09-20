import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import * as wsApi from '@/api/wastage'
import { useToast } from '@/composables/useToast'
import { useAuthStore } from '@/stores/auth'
import { errMsg } from '@/stores/m5Coupon'
import { useStoreContext } from '@/stores/storeContext'

export type WastageStatus = 'DRAFT' | 'SUBMITTING' | 'APPROVED' | 'REJECTED'
export type WastageReason = 'BROKEN' | 'EXPIRED' | 'INVENTORY_LOSS' | 'OTHER'

export interface WastageNote {
  by: string
  text: string
  at: string
}

export interface Wastage {
  id: string
  wsNo: string
  itemName: string
  spec?: string
  qty: number
  unit: string
  amount: number
  reason: WastageReason
  description?: string
  location?: string
  reporter: string
  status: WastageStatus
  approver?: string
  approvedAt?: string
  rejectReason?: string
  occurredAt: string
  createdAt: string
  notes: WastageNote[]
}

const STATUS_LABEL: Record<WastageStatus, string> = {
  DRAFT: '草稿',
  SUBMITTING: '待审批',
  APPROVED: '已通过',
  REJECTED: '已驳回',
}
const STATUS_PILL: Record<WastageStatus, 'draft' | 'primary' | 'success' | 'danger'> = {
  DRAFT: 'draft',
  SUBMITTING: 'primary',
  APPROVED: 'success',
  REJECTED: 'danger',
}
const REASON_LABEL: Record<WastageReason, string> = {
  BROKEN: '破损',
  EXPIRED: '过期',
  INVENTORY_LOSS: '盘亏',
  OTHER: '其他',
}
const HIGH_VALUE_THRESHOLD = 500

function shortWsNo(wsNo: string): string {
  const parts = wsNo.split('-')
  const tail = parts[parts.length - 1]
  const seq = parseInt(tail, 10)
  if (!Number.isFinite(seq) || seq > 999) return wsNo
  return `${parts[0]}-${parts[1]}-${String(seq).padStart(3, '0')}`
}

function adapt(d: wsApi.WastageDto): Wastage {
  return {
    id: String(d.id),
    wsNo: shortWsNo(d.wsNo),
    itemName: d.itemName,
    spec: d.spec ?? undefined,
    qty: d.qty,
    unit: d.unit,
    amount: d.amountYuan,
    reason: d.reason as WastageReason,
    description: d.description ?? undefined,
    location: d.location ?? undefined,
    reporter: d.reporter,
    status: d.status as WastageStatus,
    approver: d.approver ?? undefined,
    approvedAt: d.approvedAt ?? undefined,
    rejectReason: d.rejectReason ?? undefined,
    occurredAt: d.occurredAt,
    createdAt: d.createdAt,
    notes: d.notes.map((n) => ({ by: n.by, text: n.text, at: n.at })),
  }
}

export const useWastageStore = defineStore('wastage', () => {
  const auth = useAuthStore()
  const ctx = useStoreContext()
  const toast = useToast()

  const list = ref<Wastage[]>([])
  const filterStatus = ref<WastageStatus | 'ALL'>('ALL')
  const filterReason = ref<WastageReason | 'ALL'>('ALL')
  const loaded = ref(false)

  async function load() {
    try {
      const params: { storeCode?: string; status?: string; reason?: string } = {}
      const sc = ctx.currentStoreCode
      if (sc) params.storeCode = sc
      const { data } = await wsApi.listWastages(params)
      list.value = data.map(adapt)
      loaded.value = true
    } catch (e) {
      list.value = []
      toast.error(errMsg(e, '报损单加载失败，请稍后重试'))
    }
  }

  async function seed() {
    await ctx.loadStores()
    await load()
  }

  const drafts = computed(() => list.value.filter((x) => x.status === 'DRAFT'))
  const submitting = computed(() => list.value.filter((x) => x.status === 'SUBMITTING'))
  const approved = computed(() => list.value.filter((x) => x.status === 'APPROVED'))
  const rejected = computed(() => list.value.filter((x) => x.status === 'REJECTED'))

  const monthCount = computed(() => {
    const now = new Date()
    return approved.value.filter((x) => {
      const d = new Date(x.approvedAt || x.createdAt)
      return d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth()
    }).length
  })

  const monthAmount = computed(() => {
    const now = new Date()
    return approved.value
      .filter((x) => {
        const d = new Date(x.approvedAt || x.createdAt)
        return d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth()
      })
      .reduce((s, x) => s + x.amount, 0)
  })

  const highValue = computed(() => list.value.filter((x) => x.amount >= HIGH_VALUE_THRESHOLD))

  const filtered = computed(() => {
    let arr = list.value
    if (filterStatus.value !== 'ALL') arr = arr.filter((x) => x.status === filterStatus.value)
    if (filterReason.value !== 'ALL') arr = arr.filter((x) => x.reason === filterReason.value)
    return [...arr].sort(
      (a, b) => new Date(b.occurredAt).getTime() - new Date(a.occurredAt).getTime(),
    )
  })

  function get(id: string) {
    return list.value.find((x) => x.id === id)
  }

  async function create(input: {
    itemName: string
    spec?: string
    qty: number
    unit: string
    amount: number
    reason: WastageReason
    description?: string
    location?: string
    occurredAt?: string
  }): Promise<Wastage | null> {
    if (!auth.can('wastage:create')) {
      toast.error('无报损权限，请联系管理员')
      return null
    }
    try {
      const sc = ctx.currentStoreCode
      if (!sc) {
        toast.error('未获取到当前门店，请稍后重试')
        return null
      }
      const { data } = await wsApi.createWastage({
        storeCode: sc,
        itemName: input.itemName,
        spec: input.spec ?? null,
        qty: Number(input.qty),
        unit: input.unit,
        amountFen: Math.round(Number(input.amount) * 100),
        reason: input.reason,
        reporter: auth.user.name,
        location: input.location ?? null,
        description: input.description ?? null,
        occurredAt: input.occurredAt ?? null,
      })
      await load()
      return adapt(data)
    } catch (e) {
      toast.error(errMsg(e, '创建报损单失败，请稍后重试'))
      return null
    }
  }

  async function submit(id: string): Promise<boolean> {
    try {
      await wsApi.submitWastage(id)
      await load()
      return true
    } catch (e) {
      toast.error(errMsg(e, '提交审批失败，请稍后重试'))
      return false
    }
  }

  async function approve(id: string, note?: string): Promise<boolean> {
    try {
      await wsApi.approveWastage(id, note)
      await load()
      return true
    } catch (e) {
      toast.error(errMsg(e, '审批操作失败，请稍后重试'))
      return false
    }
  }

  async function reject(id: string, reason: string): Promise<boolean> {
    try {
      await wsApi.rejectWastage(id, reason)
      await load()
      return true
    } catch (e) {
      toast.error(errMsg(e, '驳回操作失败，请稍后重试'))
      return false
    }
  }

  async function addNote(id: string, text: string): Promise<boolean> {
    try {
      await wsApi.addWastageNote(id, text)
      await load()
      return true
    } catch (e) {
      toast.error(errMsg(e, '备注添加失败，请稍后重试'))
      return false
    }
  }

  return {
    list,
    filterStatus,
    filterReason,
    loaded,
    drafts,
    submitting,
    approved,
    rejected,
    monthCount,
    monthAmount,
    highValue,
    filtered,
    get,
    create,
    submit,
    approve,
    reject,
    addNote,
    seed,
    STATUS_LABEL,
    STATUS_PILL,
    REASON_LABEL,
    HIGH_VALUE_THRESHOLD,
  }
})
