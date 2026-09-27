// ============================================================
// Complaint 聚合 store（投诉与医疗风险处理，M3-18 / M3-B8 切真）
// 数据源：customer-service /api/customer/m3/complaint（complaint 状态机
// 待受理 → 处理中 → 待结案审批 → 已结案 / 已驳回，受理或审批环节均可驳回/退回）。
// - 赔付金额 → 签署层级服务端统算（tierFor 5000/20000），页面展示后端返回值。
// - medicalRisk=true 的医疗风险单在列表高亮，并强制留痕处理方案。
// 权限：complaint:view / complaint:create 登记 / complaint:edit 受理与处理 /
// complaint:approve 结案审批；actor 服务端取。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { useToast } from '@/composables/useToast'
import { errMsg } from './m5Coupon'
import {
  listComplaints, createComplaint, acceptComplaint, submitComplaintResolution,
  approveComplaintClose, sendBackComplaint, rejectComplaint,
  type ComplaintView,
} from '@/api/complaint'

export type ComplaintSource = 'STORE' | 'PHONE' | 'ONLINE' | 'THIRD_PARTY'
export type ComplaintSeverity = 'LOW' | 'MEDIUM' | 'HIGH'
export type ComplaintCategory = 'SERVICE' | 'MEDICAL' | 'BILLING' | 'OUTCOME' | 'OTHER'
export type ComplaintStatus =
  | 'PENDING_ACCEPT'
  | 'PROCESSING'
  | 'PENDING_REVIEW'
  | 'CLOSED'
  | 'REJECTED'

export interface ComplaintTimelineEntry {
  at: string
  by: string
  action: string
  note?: string
}

export interface Complaint {
  id: number
  complaintNo: string
  customerId: string
  customerName: string
  source: ComplaintSource
  severity: ComplaintSeverity
  category: ComplaintCategory
  medicalRisk: boolean
  description: string
  relatedOrderNo?: string
  storeId: string
  storeName: string
  status: ComplaintStatus
  compensationAmount: number
  signTier: 'L1' | 'L2' | 'L3'
  resolution?: string
  createdAt: string
  acceptedByName?: string
  acceptedAt?: string
  submittedByName?: string
  submittedAt?: string
  closedByName?: string
  closedAt?: string
  rejectionReason?: string
  timeline: ComplaintTimelineEntry[]
}

const TRANSITIONS: Record<ComplaintStatus, ComplaintStatus[]> = {
  PENDING_ACCEPT: ['PROCESSING', 'REJECTED'],
  PROCESSING: ['PENDING_REVIEW', 'REJECTED'],
  PENDING_REVIEW: ['CLOSED', 'PROCESSING', 'REJECTED'],
  CLOSED: [],
  REJECTED: [],
}

function mapComplaint(v: ComplaintView): Complaint {
  return {
    id: v.id,
    complaintNo: v.complaintNo,
    customerId: v.customerId,
    customerName: v.customerName,
    source: v.source as ComplaintSource,
    severity: v.severity as ComplaintSeverity,
    category: v.category as ComplaintCategory,
    medicalRisk: v.medicalRisk,
    description: v.description,
    relatedOrderNo: v.relatedOrderNo ?? undefined,
    storeId: v.storeId ?? '',
    storeName: v.storeName ?? '',
    status: v.status as ComplaintStatus,
    compensationAmount: v.compensationAmount ?? 0,
    signTier: (v.signTier ?? 'L1') as Complaint['signTier'],
    resolution: v.resolution ?? undefined,
    createdAt: v.createdAt,
    acceptedByName: v.acceptedByName ?? undefined,
    acceptedAt: v.acceptedAt ?? undefined,
    submittedByName: v.submittedByName ?? undefined,
    submittedAt: v.submittedAt ?? undefined,
    closedByName: v.closedByName ?? undefined,
    closedAt: v.closedAt ?? undefined,
    rejectionReason: v.rejectionReason ?? undefined,
    timeline: (v.timeline ?? []).map((t) => ({
      at: t.at,
      by: t.by,
      action: t.action,
      note: t.note ?? undefined,
    })),
  }
}

export const useComplaintStore = defineStore('complaint', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()
  const toast = useToast()

  const complaints = ref<Complaint[]>([])

  const pendingAccept = computed(() => complaints.value.filter((c) => c.status === 'PENDING_ACCEPT'))
  const processing = computed(() => complaints.value.filter((c) => c.status === 'PROCESSING'))
  const pendingReview = computed(() => complaints.value.filter((c) => c.status === 'PENDING_REVIEW'))
  const closed = computed(() => complaints.value.filter((c) => c.status === 'CLOSED'))
  const rejected = computed(() => complaints.value.filter((c) => c.status === 'REJECTED'))

  /** 医疗风险待处理（未结案/未驳回）数，用于风险预警 */
  const medicalRiskOpen = computed(() =>
    complaints.value.filter((c) => c.medicalRisk && c.status !== 'CLOSED' && c.status !== 'REJECTED'),
  )

  function replaceComplaint(next: Complaint) {
    const idx = complaints.value.findIndex((c) => c.id === next.id)
    if (idx >= 0) complaints.value.splice(idx, 1, next)
    else complaints.value.unshift(next)
  }

  function get(id: number) {
    return complaints.value.find((c) => c.id === id)
  }

  /** 状态迁移查表（仅用于按钮显隐预显；迁移合法性由后端裁定） */
  function canTransit(from: ComplaintStatus, to: ComplaintStatus) {
    return TRANSITIONS[from]?.includes(to) ?? false
  }

  /** 登记投诉 */
  async function create(input: {
    customerId: string
    customerName: string
    source: ComplaintSource
    severity: ComplaintSeverity
    category: ComplaintCategory
    medicalRisk: boolean
    description: string
    relatedOrderNo?: string
    compensationAmount?: number
  }): Promise<Complaint | null> {
    if (!auth.can('complaint:create')) {
      toast.error('无登记投诉权限')
      return null
    }
    if (!input.description.trim()) {
      toast.error('投诉描述必填')
      return null
    }
    try {
      const c = mapComplaint(await createComplaint({
        ...input,
        description: input.description.trim(),
        relatedOrderNo: input.relatedOrderNo?.trim() || undefined,
      }))
      replaceComplaint(c)
      activity.log(
        auth.user.name,
        `登记投诉 ${c.complaintNo}（${input.medicalRisk ? '医疗风险·' : ''}${input.severity}）`,
        c.complaintNo,
      )
      toast.success(`投诉 ${c.complaintNo} 已登记`)
      return c
    } catch (e) {
      toast.error(errMsg(e, '登记失败'))
      return null
    }
  }

  /** 受理：待受理 → 处理中 */
  async function accept(id: number): Promise<boolean> {
    if (!auth.can('complaint:edit')) {
      toast.error('无受理权限')
      return false
    }
    try {
      const c = mapComplaint(await acceptComplaint(id))
      replaceComplaint(c)
      activity.log(auth.user.name, `受理投诉 ${c.complaintNo}`, c.complaintNo)
      toast.success(`投诉 ${c.complaintNo} 已受理`)
      return true
    } catch (e) {
      toast.error(errMsg(e, '受理失败'))
      return false
    }
  }

  /** 提交处理方案：处理中 → 待结案审批；赔付金额变更时服务端重算签署层级 */
  async function submitResolution(id: number, resolution: string, compensation?: number): Promise<boolean> {
    if (!auth.can('complaint:edit')) {
      toast.error('无处理权限')
      return false
    }
    if (!resolution.trim()) {
      toast.error('处理方案必填')
      return false
    }
    try {
      const c = mapComplaint(await submitComplaintResolution(id, resolution.trim(), compensation))
      replaceComplaint(c)
      activity.log(auth.user.name, `投诉 ${c.complaintNo} 提交处理方案，待结案审批`, c.complaintNo)
      toast.success('处理方案已提交，待结案审批')
      return true
    } catch (e) {
      toast.error(errMsg(e, '提交失败'))
      return false
    }
  }

  /** 结案审批通过：待审批 → 已结案 */
  async function approveClose(id: number): Promise<boolean> {
    if (!auth.can('complaint:approve')) {
      toast.error('无结案审批权限')
      return false
    }
    try {
      const c = mapComplaint(await approveComplaintClose(id))
      replaceComplaint(c)
      activity.log(auth.user.name, `投诉 ${c.complaintNo} 已结案`, c.complaintNo)
      toast.success(`投诉 ${c.complaintNo} 已结案`)
      return true
    } catch (e) {
      toast.error(errMsg(e, '结案失败'))
      return false
    }
  }

  /** 退回补充处理：待审批 → 处理中 */
  async function sendBack(id: number, note: string): Promise<boolean> {
    if (!auth.can('complaint:approve')) {
      toast.error('无审批权限')
      return false
    }
    if (!note.trim()) {
      toast.error('退回说明必填')
      return false
    }
    try {
      const c = mapComplaint(await sendBackComplaint(id, note.trim()))
      replaceComplaint(c)
      activity.log(auth.user.name, `投诉 ${c.complaintNo} 退回补充：${note.trim()}`, c.complaintNo)
      toast.success('已退回补充处理')
      return true
    } catch (e) {
      toast.error(errMsg(e, '退回失败'))
      return false
    }
  }

  /** 驳回（受理/审批环节判为无效投诉） */
  async function reject(id: number, reason: string): Promise<boolean> {
    if (!auth.can('complaint:approve')) {
      toast.error('无审批权限')
      return false
    }
    if (!reason.trim()) {
      toast.error('驳回原因必填')
      return false
    }
    try {
      const c = mapComplaint(await rejectComplaint(id, reason.trim()))
      replaceComplaint(c)
      activity.log(auth.user.name, `投诉 ${c.complaintNo} 已驳回：${reason.trim()}`, c.complaintNo)
      toast.success(`投诉 ${c.complaintNo} 已驳回`)
      return true
    } catch (e) {
      toast.error(errMsg(e, '驳回失败'))
      return false
    }
  }

  /** 首屏加载（名保留 seed，内部幂等门闩） */
  let seeded = false
  async function seed() {
    if (seeded) return
    seeded = true
    try {
      complaints.value = (await listComplaints()).map(mapComplaint)
    } catch (e) {
      seeded = false
      toast.error(errMsg(e, '投诉数据加载失败'))
    }
  }

  return {
    complaints,
    pendingAccept, processing, pendingReview, closed, rejected, medicalRiskOpen,
    get, canTransit, create, accept, submitResolution, approveClose, sendBack, reject, seed,
  }
})
