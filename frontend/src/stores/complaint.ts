// ============================================================
// Complaint 聚合 store（投诉与医疗风险处理）
// 状态机：待受理 → 处理中 → 待结案审批 → 已结案 / 已驳回（受理或审批环节均可驳回/退回）。
// - 赔付金额 → 签署层级由 settings.tierFor() 推导，页面不硬编码。
// - medicalRisk=true 的医疗风险单在列表高亮，并强制留痕处理方案。
// 权限：complaint:create 登记 / complaint:edit 受理与处理 / complaint:approve 结案审批。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { useSettingsStore } from './settings'

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
  id: string
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

export const useComplaintStore = defineStore('complaint', () => {
  const auth = useAuthStore()
  const settings = useSettingsStore()
  const activity = useActivityStore()

  const complaints = ref<Complaint[]>([])
  let seq = 0

  const pendingAccept = computed(() => complaints.value.filter((c) => c.status === 'PENDING_ACCEPT'))
  const processing = computed(() => complaints.value.filter((c) => c.status === 'PROCESSING'))
  const pendingReview = computed(() => complaints.value.filter((c) => c.status === 'PENDING_REVIEW'))
  const closed = computed(() => complaints.value.filter((c) => c.status === 'CLOSED'))
  const rejected = computed(() => complaints.value.filter((c) => c.status === 'REJECTED'))

  /** 医疗风险待处理（未结案/未驳回）数，用于风险预警 */
  const medicalRiskOpen = computed(() =>
    complaints.value.filter((c) => c.medicalRisk && c.status !== 'CLOSED' && c.status !== 'REJECTED'),
  )

  function get(id: string) {
    return complaints.value.find((c) => c.id === id)
  }

  function canTransit(from: ComplaintStatus, to: ComplaintStatus) {
    return TRANSITIONS[from]?.includes(to) ?? false
  }

  /** 登记投诉 */
  function create(input: {
    customerId: string
    customerName: string
    source: ComplaintSource
    severity: ComplaintSeverity
    category: ComplaintCategory
    medicalRisk: boolean
    description: string
    relatedOrderNo?: string
    compensationAmount?: number
  }): Complaint | null {
    if (!auth.can('complaint:create')) {
      console.warn('[complaint] 无 complaint:create 权限')
      return null
    }
    if (!input.description.trim()) {
      console.warn('[complaint] 投诉描述必填')
      return null
    }
    seq += 1
    const compensation = input.compensationAmount ?? 0
    const tier = settings.tierFor(compensation)
    const now = new Date().toISOString()
    const c: Complaint = {
      id: nextId('cp'),
      complaintNo: `TS${Date.now().toString().slice(-8)}${seq}`,
      customerId: input.customerId,
      customerName: input.customerName,
      source: input.source,
      severity: input.severity,
      category: input.category,
      medicalRisk: input.medicalRisk,
      description: input.description.trim(),
      relatedOrderNo: input.relatedOrderNo?.trim() || undefined,
      storeId: auth.storeId,
      storeName: auth.user.name,
      status: 'PENDING_ACCEPT',
      compensationAmount: compensation,
      signTier: tier,
      createdAt: now,
      timeline: [
        { at: now, by: auth.user.name, action: '登记投诉', note: input.medicalRisk ? '标记为医疗风险' : undefined },
      ],
    }
    complaints.value.unshift(c)
    activity.log(
      auth.user.name,
      `登记投诉 ${c.complaintNo}（${input.medicalRisk ? '医疗风险·' : ''}${input.severity}）`,
      c.id,
    )
    return c
  }

  /** 受理：待受理 → 处理中 */
  function accept(id: string): boolean {
    const c = complaints.value.find((x) => x.id === id)
    if (!c || !canTransit(c.status, 'PROCESSING')) return false
    if (!auth.can('complaint:edit')) {
      console.warn('[complaint] 无 complaint:edit 权限')
      return false
    }
    const now = new Date().toISOString()
    c.status = 'PROCESSING'
    c.acceptedByName = auth.user.name
    c.acceptedAt = now
    c.timeline.push({ at: now, by: auth.user.name, action: '受理投诉' })
    activity.log(auth.user.name, `受理投诉 ${c.complaintNo}`, c.id)
    return true
  }

  /** 提交处理方案：处理中 → 待结案审批；赔付金额变更时重算签署层级 */
  function submitResolution(id: string, resolution: string, compensation?: number): boolean {
    const c = complaints.value.find((x) => x.id === id)
    if (!c || !canTransit(c.status, 'PENDING_REVIEW')) return false
    if (!auth.can('complaint:edit')) {
      console.warn('[complaint] 无 complaint:edit 权限')
      return false
    }
    if (!resolution.trim()) {
      console.warn('[complaint] 处理方案必填')
      return false
    }
    if (typeof compensation === 'number' && compensation >= 0) {
      c.compensationAmount = compensation
      c.signTier = settings.tierFor(compensation)
    }
    const now = new Date().toISOString()
    c.status = 'PENDING_REVIEW'
    c.resolution = resolution.trim()
    c.submittedByName = auth.user.name
    c.submittedAt = now
    c.timeline.push({
      at: now,
      by: auth.user.name,
      action: '提交处理方案',
      note: `赔付 ¥${c.compensationAmount}（${c.signTier}）`,
    })
    activity.log(auth.user.name, `投诉 ${c.complaintNo} 提交处理方案，待结案审批`, c.id)
    return true
  }

  /** 结案审批通过：待审批 → 已结案 */
  function approveClose(id: string): boolean {
    const c = complaints.value.find((x) => x.id === id)
    if (!c || !canTransit(c.status, 'CLOSED')) return false
    if (!auth.can('complaint:approve')) {
      console.warn('[complaint] 无 complaint:approve 权限')
      return false
    }
    const now = new Date().toISOString()
    c.status = 'CLOSED'
    c.closedByName = auth.user.name
    c.closedAt = now
    c.timeline.push({ at: now, by: auth.user.name, action: '审批结案' })
    activity.log(auth.user.name, `投诉 ${c.complaintNo} 已结案`, c.id)
    return true
  }

  /** 退回补充处理：待审批 → 处理中 */
  function sendBack(id: string, note: string): boolean {
    const c = complaints.value.find((x) => x.id === id)
    if (!c || !canTransit(c.status, 'PROCESSING')) return false
    if (!auth.can('complaint:approve')) {
      console.warn('[complaint] 无 complaint:approve 权限')
      return false
    }
    const now = new Date().toISOString()
    c.status = 'PROCESSING'
    c.timeline.push({ at: now, by: auth.user.name, action: '退回补充处理', note })
    activity.log(auth.user.name, `投诉 ${c.complaintNo} 退回补充：${note}`, c.id)
    return true
  }

  /** 驳回（受理/审批环节判为无效投诉） */
  function reject(id: string, reason: string): boolean {
    const c = complaints.value.find((x) => x.id === id)
    if (!c || !canTransit(c.status, 'REJECTED')) return false
    if (!auth.can('complaint:approve')) {
      console.warn('[complaint] 无 complaint:approve 权限')
      return false
    }
    const now = new Date().toISOString()
    c.status = 'REJECTED'
    c.rejectionReason = reason.trim()
    c.timeline.push({ at: now, by: auth.user.name, action: '驳回投诉', note: reason.trim() })
    activity.log(auth.user.name, `投诉 ${c.complaintNo} 已驳回：${reason}`, c.id)
    return true
  }

  /** 开发期种子 */
  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    const now = Date.now()
    const seedData: Array<Partial<Complaint> & {
      customerName: string
      status: ComplaintStatus
      severity: ComplaintSeverity
      category: ComplaintCategory
      source: ComplaintSource
      description: string
    }> = [
      {
        customerName: '王美丽', status: 'PENDING_ACCEPT', severity: 'HIGH', category: 'MEDICAL', source: 'STORE',
        medicalRisk: true, compensationAmount: 3000, signTier: 'L1', relatedOrderNo: 'SO20260824001',
        description: '光子嫩肤术后面部出现明显红肿，客户质疑能量参数设置过高，要求复诊与赔付。',
      },
      {
        customerName: '陈思', status: 'PROCESSING', severity: 'MEDIUM', category: 'SERVICE', source: 'PHONE',
        medicalRisk: false, compensationAmount: 0, signTier: 'L1',
        description: '预约到店等候超 40 分钟，前台未主动告知进度，客户体验不满。',
      },
      {
        customerName: '赵敏', status: 'PENDING_REVIEW', severity: 'MEDIUM', category: 'BILLING', source: 'ONLINE',
        medicalRisk: false, compensationAmount: 1280, signTier: 'L1', relatedOrderNo: 'SO20260820007',
        description: '结算时被加收未告知的耗材费 ¥380，要求退还争议费用并补偿一次护理。',
        resolution: '核实为前台未提前说明耗材费，退还 ¥380 耗材费并补偿一次价值 ¥1280 水光护理，已电话致歉。',
      },
      {
        customerName: '林晚', status: 'PENDING_REVIEW', severity: 'HIGH', category: 'OUTCOME', source: 'THIRD_PARTY',
        medicalRisk: true, compensationAmount: 8600, signTier: 'L2', relatedOrderNo: 'SO20260815003',
        description: '热玛吉治疗后效果未达预期，客户通过平台投诉要求退一赔三，持续在社交平台发声。',
        resolution: '经主诊医生复评效果在合理范围内，出于客情维护退还疗程余款 ¥8600，安排院长面谈，签署和解协议。',
      },
      {
        customerName: '周婷', status: 'CLOSED', severity: 'LOW', category: 'OTHER', source: 'STORE',
        medicalRisk: false, compensationAmount: 200, signTier: 'L1',
        description: '会员积分未及时到账，客户来电反映。',
        resolution: '系统延迟导致，手动补录积分并赠送 ¥200 护理券。',
      },
      {
        customerName: '吴桐', status: 'REJECTED', severity: 'LOW', category: 'BILLING', source: 'PHONE',
        medicalRisk: false, compensationAmount: 0, signTier: 'L1',
        description: '客户声称重复扣费，经查为两笔不同项目消费。',
        rejectionReason: '调取签购单与消费记录核实为两个独立项目，非重复扣费，已向客户解释并提供凭证。',
      },
    ]

    seedData.forEach((s, i) => {
      seq += 1
      const createdIso = new Date(now - i * 7200_000).toISOString()
      const c: Complaint = {
        id: nextId('cp'),
        complaintNo: `TS2026082${5 - i}00${i + 1}`,
        customerId: `C-40${i}`,
        customerName: s.customerName!,
        source: s.source!,
        severity: s.severity!,
        category: s.category!,
        medicalRisk: !!s.medicalRisk,
        description: s.description!,
        relatedOrderNo: s.relatedOrderNo,
        storeId: 'store-jingan',
        storeName: '静安旗舰店',
        status: s.status,
        compensationAmount: s.compensationAmount ?? 0,
        signTier: s.signTier ?? 'L1',
        resolution: s.resolution,
        rejectionReason: s.rejectionReason,
        createdAt: createdIso,
        timeline: [{ at: createdIso, by: '夏沫（前台）', action: '登记投诉' }],
      }
      if (s.status !== 'PENDING_ACCEPT') {
        c.acceptedByName = '苏晴（店长）'
        c.acceptedAt = createdIso
        c.timeline.push({ at: createdIso, by: '苏晴（店长）', action: '受理投诉' })
      }
      if (s.status === 'PENDING_REVIEW' || s.status === 'CLOSED' || s.status === 'REJECTED') {
        if (c.resolution) {
          c.submittedByName = '苏晴（店长）'
          c.submittedAt = createdIso
          c.timeline.push({
            at: createdIso,
            by: '苏晴（店长）',
            action: '提交处理方案',
            note: `赔付 ¥${c.compensationAmount}（${c.signTier}）`,
          })
        }
      }
      if (s.status === 'CLOSED') {
        c.closedByName = '陈野（区域经理）'
        c.closedAt = createdIso
        c.timeline.push({ at: createdIso, by: '陈野（区域经理）', action: '审批结案' })
      }
      if (s.status === 'REJECTED') {
        c.closedByName = '苏晴（店长）'
        c.closedAt = createdIso
        c.timeline.push({ at: createdIso, by: '苏晴（店长）', action: '驳回投诉', note: c.rejectionReason })
      }
      complaints.value.push(c)
    })
  }

  return {
    complaints,
    pendingAccept, processing, pendingReview, closed, rejected, medicalRiskOpen,
    get, canTransit, create, accept, submitResolution, approveClose, sendBack, reject, seed,
  }
})
