import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import * as rqApi from '@/api/requisition'
import { useToast } from '@/composables/useToast'
import { useAuthStore } from '@/stores/auth'
import { errMsg } from '@/stores/m5Coupon'
import { useStoreContext } from '@/stores/storeContext'

export type RequisitionStatus = 'DRAFT' | 'SUBMITTING' | 'APPROVED' | 'RECEIVED' | 'REJECTED'

export interface RequisitionItem {
  name: string
  spec?: string
  qty: number
  unit: string
  skuCode?: string
}

export interface RequisitionNote {
  by: string
  text: string
  at: string
}

export interface Requisition {
  id: string
  rqNo: string
  storeCode: string
  applicant: string
  purpose: string
  remark?: string
  items: RequisitionItem[]
  status: RequisitionStatus
  approver?: string
  approvedAt?: string
  receiver?: string
  receivedAt?: string
  rejectReason?: string
  createdAt: string
  notes: RequisitionNote[]
}

const STATUS_LABEL: Record<RequisitionStatus, string> = {
  DRAFT: '草稿',
  SUBMITTING: '审批中',
  APPROVED: '待签收',
  RECEIVED: '已签收',
  REJECTED: '已驳回'
}

const STATUS_PILL: Record<RequisitionStatus, 'draft' | 'primary' | 'warning' | 'success' | 'danger'> = {
  DRAFT: 'draft',
  SUBMITTING: 'primary',
  APPROVED: 'warning',
  RECEIVED: 'success',
  REJECTED: 'danger'
}

function shortRqNo(rqNo: string): string {
  const parts = rqNo.split('-')
  const tail = parts[parts.length - 1]
  const seq = parseInt(tail, 10)
  if (!Number.isFinite(seq) || seq > 999) return rqNo
  return `${parts[0]}-${parts[1]}-${String(seq).padStart(3, '0')}`
}

function adapt(d: rqApi.RequisitionDto): Requisition {
  return {
    id: String(d.id),
    rqNo: shortRqNo(d.rqNo),
    storeCode: d.storeCode,
    applicant: d.applicant,
    purpose: d.purpose,
    remark: d.remark ?? undefined,
    items: d.items.map((i) => ({
      name: i.name,
      spec: i.spec ?? undefined,
      qty: i.qty,
      unit: i.unit,
      skuCode: i.skuCode ?? undefined
    })),
    status: d.status as RequisitionStatus,
    approver: d.approver ?? undefined,
    approvedAt: d.approvedAt ?? undefined,
    receiver: d.receiver ?? undefined,
    receivedAt: d.receivedAt ?? undefined,
    rejectReason: d.rejectReason ?? undefined,
    createdAt: d.createdAt,
    notes: d.notes.map((n) => ({ by: n.by, text: n.text, at: n.at }))
  }
}

export const useRequisitionStore = defineStore('requisition', () => {
  const auth = useAuthStore()
  const ctx = useStoreContext()
  const toast = useToast()

  const list = ref<Requisition[]>([])
  const filterStatus = ref<'ALL' | RequisitionStatus>('ALL')
  const keyword = ref('')
  const loaded = ref(false)

  async function load() {
    try {
      const params: { storeCode?: string; status?: string } = {}
      const sc = ctx.currentStoreCode
      if (sc) params.storeCode = sc
      const { data } = await rqApi.listRequisitions(params)
      list.value = data.map(adapt)
      loaded.value = true
    } catch (e) {
      list.value = []
      toast.error(errMsg(e, '申领单加载失败，请稍后重试'))
    }
  }

  async function seed() {
    await ctx.loadStores()
    await load()
  }

  const drafts = computed(() => list.value.filter((r) => r.status === 'DRAFT'))
  const submitting = computed(() => list.value.filter((r) => r.status === 'SUBMITTING'))
  const approved = computed(() => list.value.filter((r) => r.status === 'APPROVED'))
  const received = computed(() => list.value.filter((r) => r.status === 'RECEIVED'))
  const rejected = computed(() => list.value.filter((r) => r.status === 'REJECTED'))

  const filtered = computed(() => {
    const kw = keyword.value.trim().toLowerCase()
    return list.value
      .filter((r) => (filterStatus.value === 'ALL' ? true : r.status === filterStatus.value))
      .filter((r) => {
        if (!kw) return true
        return (
          r.rqNo.toLowerCase().includes(kw) ||
          r.applicant.toLowerCase().includes(kw) ||
          r.purpose.toLowerCase().includes(kw) ||
          r.items.some((i) => i.name.toLowerCase().includes(kw))
        )
      })
      .sort((a, b) => (a.createdAt < b.createdAt ? 1 : a.createdAt > b.createdAt ? -1 : 0))
  })

  function get(id: string) {
    return list.value.find((r) => r.id === id)
  }

  async function create(input: {
    storeCode?: string
    applicant?: string
    purpose: string
    remark?: string
    sourceType?: string
    sourceRef?: string
    items: RequisitionItem[]
  }): Promise<Requisition | null> {
    if (!auth.can('requisition:create')) {
      toast.error('无申领权限，请联系管理员')
      return null
    }
    try {
      const sc = input.storeCode ?? ctx.currentStoreCode
      if (!sc) {
        toast.error('未获取到当前门店，请稍后重试')
        return null
      }
      const { data } = await rqApi.createRequisition({
        storeCode: sc,
        applicant: input.applicant ?? auth.user.name,
        purpose: input.purpose,
        remark: input.remark ?? null,
        sourceType: input.sourceType,
        sourceRef: input.sourceRef,
        items: input.items.map((i) => ({
          name: i.name,
          spec: i.spec ?? null,
          qty: i.qty,
          unit: i.unit,
          skuCode: i.skuCode ?? null
        }))
      })
      await load()
      return adapt(data)
    } catch (e) {
      toast.error(errMsg(e, '创建申领单失败，请稍后重试'))
      return null
    }
  }

  async function submit(id: string): Promise<boolean> {
    try {
      await rqApi.submitRequisition(id)
      await load()
      return true
    } catch (e) {
      toast.error(errMsg(e, '提交审批失败，请稍后重试'))
      return false
    }
  }

  async function approve(id: string, note?: string): Promise<boolean> {
    try {
      await rqApi.approveRequisition(id, note)
      await load()
      return true
    } catch (e) {
      toast.error(errMsg(e, '审批操作失败，请稍后重试'))
      return false
    }
  }

  async function reject(id: string, reason: string): Promise<boolean> {
    try {
      await rqApi.rejectRequisition(id, reason)
      await load()
      return true
    } catch (e) {
      toast.error(errMsg(e, '驳回操作失败，请稍后重试'))
      return false
    }
  }

  async function receive(id: string): Promise<boolean> {
    try {
      await rqApi.receiveRequisition(id)
      await load()
      return true
    } catch (e) {
      toast.error(errMsg(e, '签收操作失败，请稍后重试'))
      return false
    }
  }

  async function addNote(id: string, text: string): Promise<boolean> {
    try {
      await rqApi.addRequisitionNote(id, text)
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
    keyword,
    loaded,
    drafts,
    submitting,
    approved,
    received,
    rejected,
    filtered,
    get,
    create,
    submit,
    approve,
    reject,
    receive,
    addNote,
    seed,
    STATUS_LABEL,
    STATUS_PILL
  }
})
