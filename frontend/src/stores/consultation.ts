// ============================================================
// Consultation 聚合 store（咨询 / 医师双工作台闭环）
//
// 状态机（合规顺序：病历先于收费、收费先于治疗）：
//   【咨询工作台】PENDING 待咨询 → ACTIVE 咨询中（咨询师快捷开单）
//     → PENDING_REVIEW 待医生审核
//   【医师工作台】PENDING_REVIEW → APPROVED 审核通过·待写病历
//     → 医生快捷写病历并签名 → READY_PAY 病历已签·缴费单待支付（自动生成缴费单）
//     → 收银台收款 → PAID 已支付·待治疗
//     → 术前核对四项 → TREATING 治疗中 → 写治疗记录 → DONE 完成归档（自动生成术后随访）
//   打回：PENDING_REVIEW → REJECTED（咨询师改单重提）；前态可 ABANDONED（作废需医生/主管）。
//
// 审核 / 改单 / 病历 / 支付 / 治疗全程 append-only 留痕（revisions）。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type {
  Consultation,
  ConsultStatus,
  PlanItem,
  PlanRevision,
  ConsultContraindication,
  PreOpChecklist,
} from '@/types/domain'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { useOrderStore } from './order'
import { useEmrStore } from './emr'
import { useFollowupStore } from './followup'

const TRANSITIONS: Record<ConsultStatus, ConsultStatus[]> = {
  PENDING: ['ACTIVE', 'ABANDONED'],
  ACTIVE: ['PENDING_REVIEW', 'ABANDONED'],
  PENDING_REVIEW: ['APPROVED', 'REJECTED'],
  REJECTED: ['PENDING_REVIEW', 'ACTIVE', 'ABANDONED'],
  APPROVED: ['READY_PAY', 'ABANDONED'],
  READY_PAY: ['PAID', 'ABANDONED'],
  PAID: ['TREATING'],
  TREATING: ['DONE'],
  DONE: [],
  ABANDONED: [],
}
/** 审核通过后的全部履约态（病历/缴费单/治疗合法阶段） */
const POST_APPROVE: ConsultStatus[] = ['APPROVED', 'READY_PAY', 'PAID', 'TREATING', 'DONE']
function canTransit(from: ConsultStatus, to: ConsultStatus) {
  return TRANSITIONS[from]?.includes(to) ?? false
}

export interface PlanSubmitPayload {
  conclusion: string
  planItems: PlanItem[]
  doctorId: string
  contraindications: ConsultContraindication
  consentConsultant: boolean
  consentCustomer: boolean
  customerName: string
  /** 客户手写电子签名（dataURL）+ 签署人姓名 + 同意书版本 */
  consentSignatureDataUrl?: string
  consentSignerName?: string
  consentDocVersion?: string
  /** 面诊 / 皮肤检测报告 id */
  skinReportId?: string
}

/** 医生「审核通过 + 快捷写病历」一步完成时的病历字段（均可空，空则由方案单自动带入） */
export interface QuickEmrPayload {
  customerName: string
  chiefComplaint?: string
  presentIllness?: string
  pastHistory?: string
  /** 诊断/皮肤评估（留空则取咨询结论 conclusion） */
  diagnosis?: string
  /** 治疗方案/操作记录（留空则取方案项目明细） */
  treatment?: string
  prescription?: string
  note?: string
}

export const useConsultationStore = defineStore('consultation', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const consultations = ref<Consultation[]>([])
  let seeded = false

  /** 开发期种子：覆盖双工作台各状态列。真实环境由分诊/审核动作产生。 */
  function seed() {
    if (seeded) return
    seeded = true
    const now = new Date().toISOString()
    const noContra = (): ConsultContraindication => ({
      pregnant: false, allergy: false, scarConstitution: false,
      skinLesion: false, coagulationAbn: false, seriousIllness: false,
    })

    // ① 待咨询（咨询工作台）
    open({ customerId: 'C-202', consultantId: 'staff-lin', arrivalId: 'A-seed-1' })

    // ② 咨询中（咨询师已面诊，方案草稿中）
    const c2 = open({ customerId: 'C-203', consultantId: 'staff-lin', arrivalId: 'A-seed-2' })
    if (c2) {
      c2.status = 'ACTIVE'
      c2.startedAt = now
    }

    // ③ 待医生审核（驱动全链路真实操作的主单）
    const c3 = open({ customerId: 'C-201', consultantId: 'staff-lin', arrivalId: 'A-seed-3', doctorId: 'staff-gu' })
    if (c3) {
      c3.status = 'PENDING_REVIEW'
      c3.conclusion = '光子嫩肤 3 次 + 水光针 1 次，改善肤色暗沉与干燥'
      c3.planItems = [
        { itemId: 'LS-003', name: '光子嫩肤（M22）', spec: '全脸 · 3 次套餐', qty: 3, price: 1680, riskTags: ['LASER'] },
        { itemId: 'SK-001', name: '水光针（基础补水）', spec: '2ml · 1 次', qty: 1, price: 780, riskTags: ['INJECTION', 'ANESTHESIA'] },
      ]
      c3.planAmount = 3 * 1680 + 780
      c3.planCost = Math.round(c3.planAmount * 0.35)
      c3.contraindications = { ...noContra(), allergy: true, note: '利多卡因过敏史，表麻改用非利多卡因方案' }
      c3.consentConsultant = true
      c3.consentCustomer = true
      c3.consentAt = now
      c3.submittedAt = now
      c3.revisions = [
        { id: nextId('rev'), at: now, actorId: 'staff-lin', actorName: '林微', kind: 'SUBMIT', reason: '方案已与客户沟通确认' },
      ]
    }

    // ④ 打回改单（咨询工作台）
    const c4 = open({ customerId: 'C-204', consultantId: 'staff-lin', doctorId: 'staff-gu' })
    if (c4) {
      c4.status = 'REJECTED'
      c4.conclusion = '热玛吉面部提升 1 次'
      c4.planItems = [{ itemId: 'LS-005', name: '热玛吉（面部 600 发）', spec: '面部 · 1 次', qty: 1, price: 19800, riskTags: ['LASER'] }]
      c4.planAmount = 19800
      c4.planCost = Math.round(19800 * 0.35)
      c4.contraindications = noContra()
      c4.consentConsultant = true
      c4.consentCustomer = true
      c4.submittedAt = new Date(Date.now() - 86400000).toISOString()
      c4.reviewedBy = 'staff-gu'
      c4.reviewedByName = '顾屿'
      c4.reviewedAt = new Date(Date.now() - 3600000).toISOString()
      c4.rejectReason = '客户面部有活动性痤疮，建议先抗炎治疗 2 周后再评估热玛吉；本次先开药妆护理方案。'
      c4.revisions = [
        { id: nextId('rev'), at: c4.submittedAt!, actorId: 'staff-lin', actorName: '林微', kind: 'SUBMIT' },
        { id: nextId('rev'), at: c4.reviewedAt!, actorId: 'staff-gu', actorName: '顾屿', kind: 'REJECT', reason: c4.rejectReason },
      ]
    }

    // ⑤ 审核通过·待写病历（医师工作台左列）
    const c5 = open({ customerId: 'C-205', consultantId: 'staff-lin', doctorId: 'staff-gu' })
    if (c5) {
      c5.status = 'APPROVED'
      c5.conclusion = '皮肤偏干、屏障轻度受损，建议基础水光补水 1 次'
      c5.planItems = [
        { itemId: 'SK-001', name: '水光针（基础补水）', spec: '2ml · 1 次', qty: 1, price: 780, riskTags: ['INJECTION', 'ANESTHESIA'] },
      ]
      c5.planAmount = 780
      c5.planCost = Math.round(780 * 0.35)
      c5.contraindications = noContra()
      c5.consentConsultant = true
      c5.consentCustomer = true
      c5.submittedAt = new Date(Date.now() - 2 * 3600000).toISOString()
      c5.reviewedBy = 'staff-gu'
      c5.reviewedByName = '顾屿'
      c5.reviewedAt = new Date(Date.now() - 3600000).toISOString()
      c5.revisions = [
        { id: nextId('rev'), at: c5.submittedAt!, actorId: 'staff-lin', actorName: '林微', kind: 'SUBMIT' },
        { id: nextId('rev'), at: c5.reviewedAt!, actorId: 'staff-gu', actorName: '顾屿', kind: 'APPROVE', reason: '审核通过，方案适宜，待面诊写首程病历' },
      ]
    }

    // ⑥ 病历已签·缴费单待支付（医师工作台右列；缴费单真实落 order，可在收银台收款联动）
    const c6 = open({ customerId: 'C-206', consultantId: 'staff-lin', doctorId: 'staff-gu' })
    if (c6) {
      c6.status = 'APPROVED' // 先落审核通过，供 order.create 校验
      c6.conclusion = 'VISIA 检测后建议果酸焕肤 1 次改善痤疮与毛孔'
      c6.planItems = [
        { itemId: 'SK-003', name: '果酸焕肤', spec: '全脸 · 1 次', qty: 1, price: 680, riskTags: ['LASER'] },
      ]
      c6.planAmount = 680
      c6.planCost = Math.round(680 * 0.35)
      c6.contraindications = noContra()
      c6.consentConsultant = true
      c6.consentCustomer = true
      c6.submittedAt = new Date(Date.now() - 5 * 3600000).toISOString()
      c6.reviewedBy = 'staff-gu'
      c6.reviewedByName = '顾屿'
      c6.reviewedAt = new Date(Date.now() - 4 * 3600000).toISOString()
      c6.emrId = 'emr-seed-rp'
      c6.emrSignedAt = new Date(Date.now() - 4 * 3600000).toISOString()
      // 真实缴费单（收银台可见、收款后联动 markPaid）
      const o6 = useOrderStore().create({
        customerId: c6.customerId,
        consultantId: c6.consultantId,
        doctorId: c6.doctorId,
        consultId: c6.id,
        items: c6.planItems.map((i) => ({ name: i.name, spec: i.spec, qty: i.qty, price: i.price })),
        remark: '方案单首程病历签署后自动生成（种子）',
      })
      c6.orderId = o6?.id
      c6.status = 'READY_PAY'
      c6.revisions = [
        { id: nextId('rev'), at: c6.submittedAt!, actorId: 'staff-lin', actorName: '林微', kind: 'SUBMIT' },
        { id: nextId('rev'), at: c6.reviewedAt!, actorId: 'staff-gu', actorName: '顾屿', kind: 'APPROVE' },
        { id: nextId('rev'), at: c6.emrSignedAt!, actorId: 'staff-gu', actorName: '顾屿', kind: 'EMR_SIGN', reason: '首程病历已签署，缴费单已生成' },
      ]
    }

    // ⑦ 已支付·待治疗（医师工作台右列）
    const c7 = open({ customerId: 'C-207', consultantId: 'staff-lin', doctorId: 'staff-gu' })
    if (c7) {
      c7.status = 'PAID'
      c7.conclusion = '面部光老化，光子嫩肤全脸 1 次'
      c7.planItems = [
        { itemId: 'LS-003', name: '光子嫩肤（M22）', spec: '全脸 · 1 次', qty: 1, price: 1280, riskTags: ['LASER'] },
      ]
      c7.planAmount = 1280
      c7.planCost = Math.round(1280 * 0.35)
      c7.contraindications = noContra()
      c7.consentConsultant = true
      c7.consentCustomer = true
      c7.submittedAt = new Date(Date.now() - 86400000).toISOString()
      c7.reviewedBy = 'staff-gu'
      c7.reviewedByName = '顾屿'
      c7.reviewedAt = new Date(Date.now() - 86400000).toISOString()
      c7.emrId = 'emr-seed-paid'
      c7.emrSignedAt = new Date(Date.now() - 86400000).toISOString()
      c7.orderId = 'o-seed-paid'
      c7.paidAt = new Date(Date.now() - 3 * 3600000).toISOString()
      c7.revisions = [
        { id: nextId('rev'), at: c7.submittedAt!, actorId: 'staff-lin', actorName: '林微', kind: 'SUBMIT' },
        { id: nextId('rev'), at: c7.reviewedAt!, actorId: 'staff-gu', actorName: '顾屿', kind: 'APPROVE' },
        { id: nextId('rev'), at: c7.emrSignedAt!, actorId: 'staff-gu', actorName: '顾屿', kind: 'EMR_SIGN' },
        { id: nextId('rev'), at: c7.paidAt!, actorId: 'staff-fd', actorName: '前台', kind: 'PAY', reason: '缴费单收款完成 ¥1280' },
      ]
    }

    // ⑧ 治疗中（医师工作台右列）
    const c8 = open({ customerId: 'C-208', consultantId: 'staff-lin', doctorId: 'staff-gu' })
    if (c8) {
      c8.status = 'TREATING'
      c8.conclusion = '皮肤干燥，水光治疗中（第 2/3 次）'
      c8.planItems = [
        { itemId: 'SK-001', name: '水光针（基础补水）', spec: '2ml · 3 次套餐', qty: 3, price: 780, riskTags: ['INJECTION', 'ANESTHESIA'] },
      ]
      c8.planAmount = 2340
      c8.planCost = Math.round(2340 * 0.35)
      c8.contraindications = noContra()
      c8.consentConsultant = true
      c8.consentCustomer = true
      c8.submittedAt = new Date(Date.now() - 7 * 86400000).toISOString()
      c8.reviewedBy = 'staff-gu'
      c8.reviewedByName = '顾屿'
      c8.reviewedAt = new Date(Date.now() - 7 * 86400000).toISOString()
      c8.emrId = 'emr-seed-tr'
      c8.emrSignedAt = new Date(Date.now() - 7 * 86400000).toISOString()
      c8.orderId = 'o-seed-tr'
      c8.paidAt = new Date(Date.now() - 6 * 86400000).toISOString()
      c8.treatingAt = new Date(Date.now() - 3600000).toISOString()
      c8.preOp = { consentChecked: true, contraChecked: true, drugChecked: true, siteChecked: true, room: '治疗室 2' }
      c8.revisions = [
        { id: nextId('rev'), at: c8.submittedAt!, actorId: 'staff-lin', actorName: '林微', kind: 'SUBMIT' },
        { id: nextId('rev'), at: c8.reviewedAt!, actorId: 'staff-gu', actorName: '顾屿', kind: 'APPROVE' },
        { id: nextId('rev'), at: c8.emrSignedAt!, actorId: 'staff-gu', actorName: '顾屿', kind: 'EMR_SIGN' },
        { id: nextId('rev'), at: c8.paidAt!, actorId: 'staff-fd', actorName: '前台', kind: 'PAY' },
        { id: nextId('rev'), at: c8.treatingAt!, actorId: 'staff-gu', actorName: '顾屿', kind: 'TREAT_START', reason: '术前四项核对通过，治疗室 2' },
      ]
    }
  }

  // —— 咨询工作台列 ——
  const pending = computed(() => consultations.value.filter((c) => c.status === 'PENDING'))
  const active = computed(() => consultations.value.filter((c) => c.status === 'ACTIVE' || c.status === 'REJECTED'))
  /** 咨询师工作队列：待咨询 + 咨询中草稿（开始咨询后不移出，提交审核后才移出） */
  const queue = computed(() => consultations.value.filter((c) => c.status === 'PENDING' || c.status === 'ACTIVE'))
  /** 已提交：今日已提交审核、进入审核/缴费/治疗履约链路（只读回看当日咨询记录） */
  const SUBMITTED_STATUSES: ConsultStatus[] = ['PENDING_REVIEW', 'APPROVED', 'READY_PAY', 'PAID', 'TREATING', 'DONE']
  const isToday = (iso?: string) => {
    if (!iso) return false
    const d = new Date(iso)
    const n = new Date()
    return d.getFullYear() === n.getFullYear() && d.getMonth() === n.getMonth() && d.getDate() === n.getDate()
  }
  const submitted = computed(() =>
    consultations.value.filter((c) => SUBMITTED_STATUSES.includes(c.status) && isToday(c.submittedAt)),
  )
  // —— 医师工作台列 ——
  const reviewing = computed(() => consultations.value.filter((c) => c.status === 'PENDING_REVIEW'))
  const approved = computed(() => consultations.value.filter((c) => c.status === 'APPROVED'))
  const readyPay = computed(() => consultations.value.filter((c) => c.status === 'READY_PAY'))
  const paid = computed(() => consultations.value.filter((c) => c.status === 'PAID'))
  const treating = computed(() => consultations.value.filter((c) => c.status === 'TREATING'))
  const done = computed(() => consultations.value.filter((c) => c.status === 'DONE'))
  /** 治疗执行右列聚合：待支付 + 已支付待治疗 + 治疗中 */
  const treatmentQueue = computed(() =>
    consultations.value.filter((c) => ['READY_PAY', 'PAID', 'TREATING'].includes(c.status)),
  )
  /** 兼容旧引用（PrescriptionView 等）：审核通过之后的单可开单 */
  const planned = computed(() => consultations.value.filter((c) => POST_APPROVE.includes(c.status)))

  function get(id: string) {
    return consultations.value.find((c) => c.id === id)
  }
  function byCustomer(customerId: string) {
    return consultations.value.filter((c) => c.customerId === customerId)
  }

  function pushRevision(c: Consultation, rev: Omit<PlanRevision, 'id' | 'at' | 'actorId' | 'actorName'>) {
    if (!c.revisions) c.revisions = []
    c.revisions.push({
      id: nextId('rev'),
      at: new Date().toISOString(),
      actorId: auth.user.staffId,
      actorName: auth.user.name,
      ...rev,
    })
  }

  /** 分诊后由 arrival store 间接触发创建咨询单 */
  function open(input: { customerId: string; consultantId: string; arrivalId?: string; doctorId?: string }) {
    const c: Consultation = {
      id: nextId('c'),
      customerId: input.customerId,
      arrivalId: input.arrivalId,
      consultantId: input.consultantId,
      doctorId: input.doctorId,
      status: 'PENDING',
      conclusion: '',
    }
    consultations.value.push(c)
    return c
  }

  function start(id: string) {
    if (!auth.can('consult:edit')) {
      console.warn('[consultation] 无 consult:edit 权限')
      return false
    }
    const c = consultations.value.find((x) => x.id === id)
    if (!c || !canTransit(c.status, 'ACTIVE')) return false
    const wasRejected = c.status === 'REJECTED'
    c.status = 'ACTIVE'
    c.startedAt = c.startedAt ?? new Date().toISOString()
    if (wasRejected) c.rejectReason = undefined
    activity.log(auth.user.name, wasRejected ? `打回后重新开单咨询` : `开始咨询`, c.id)
    return true
  }

  /**
   * 咨询师快捷开单 → 提交医生审核（不直接生成订单/预约）。
   * 合规前置校验由视图层合规引擎完成（敏感词/禁忌硬阻断），store 做最终防线。
   */
  function submitPlan(id: string, payload: PlanSubmitPayload): { ok: boolean; error?: string } {
    if (!auth.can('consult:edit')) return { ok: false, error: '无 consult:edit 权限' }
    const c = consultations.value.find((x) => x.id === id)
    if (!c) return { ok: false, error: '咨询单不存在' }
    if (c.status !== 'ACTIVE' && c.status !== 'REJECTED') {
      return { ok: false, error: '当前状态不可提交审核' }
    }
    if (!payload.planItems.length) return { ok: false, error: '方案明细为空，请先添加项目' }
    if (!payload.conclusion?.trim()) {
      return { ok: false, error: '咨询结论 / 诊断为必填项：请客观描述皮肤评估与建议项目（将作为病历诊断），为空无法提交' }
    }
    if (!payload.doctorId) return { ok: false, error: '请指定审核医生' }
    if (!payload.consentConsultant || !payload.consentCustomer) {
      return { ok: false, error: '知情同意双确认未完成' }
    }
    // 知情同意电子签：客户必须手写签名并归档
    if (!payload.consentSignatureDataUrl || !payload.consentSignerName?.trim()) {
      return { ok: false, error: '客户未完成《知情同意书》手写电子签名' }
    }
    const positive = Object.entries(payload.contraindications)
      .filter(([k, v]) => k !== 'note' && v === true)
      .map(([k]) => k)
    if (positive.length && !payload.contraindications.note?.trim()) {
      return { ok: false, error: '存在禁忌阳性项，必须填写医生备注/处置说明' }
    }

    const amount = payload.planItems.reduce((s, it) => s + it.qty * it.price, 0)
    const isResubmit = c.status === 'REJECTED'
    c.status = 'PENDING_REVIEW'
    c.conclusion = payload.conclusion
    c.planItems = payload.planItems
    c.doctorId = payload.doctorId
    c.contraindications = payload.contraindications
    c.consentConsultant = payload.consentConsultant
    c.consentCustomer = payload.consentCustomer
    c.consentAt = new Date().toISOString()
    c.consentSignatureDataUrl = payload.consentSignatureDataUrl
    c.consentSignerName = payload.consentSignerName?.trim()
    c.consentDocVersion = payload.consentDocVersion || 'MEIYUN-ICF-v2026.1'
    c.skinReportId = payload.skinReportId ?? c.skinReportId
    c.submittedAt = new Date().toISOString()
    c.planAmount = amount
    c.planCost = Math.round(amount * 0.35)
    c.rejectReason = undefined
    pushRevision(c, {
      kind: isResubmit ? 'RESUBMIT' : 'SUBMIT',
      reason: isResubmit ? '打回后修改重新提交' : '方案已与客户沟通确认',
    })

    activity.log(
      auth.user.name,
      `${isResubmit ? '重新提交' : '提交'}方案审核 ${payload.customerName}：${payload.planItems.length} 项 / ¥${amount}`,
      c.id,
    )
    return { ok: true }
  }

  /** 医生审核通过（仅通过，不写病历）→ APPROVED 待写病历 */
  function approve(id: string, note?: string): { ok: boolean; error?: string } {
    if (!auth.can('consult:review')) return { ok: false, error: '无 consult:review 权限' }
    const c = consultations.value.find((x) => x.id === id)
    if (!c || !canTransit(c.status, 'APPROVED')) return { ok: false, error: '当前状态不可审核通过' }
    c.status = 'APPROVED'
    c.reviewedBy = auth.user.staffId
    c.reviewedByName = auth.user.name
    c.reviewedAt = new Date().toISOString()
    pushRevision(c, { kind: 'APPROVE', reason: note?.trim() || '审核通过，适应症与禁忌核验无误' })
    activity.log(auth.user.name, `审核通过方案单 ${c.id}，待写首程病历`, c.id)
    return { ok: true }
  }

  /** 医生打回 → 咨询师修改重提 */
  function reject(id: string, reason: string): { ok: boolean; error?: string } {
    if (!auth.can('consult:review')) return { ok: false, error: '无 consult:review 权限' }
    const c = consultations.value.find((x) => x.id === id)
    if (!c || !canTransit(c.status, 'REJECTED')) return { ok: false, error: '当前状态不可打回' }
    if (!reason.trim()) return { ok: false, error: '打回必须填写原因' }
    c.status = 'REJECTED'
    c.reviewedBy = auth.user.staffId
    c.reviewedByName = auth.user.name
    c.reviewedAt = new Date().toISOString()
    c.rejectReason = reason.trim()
    pushRevision(c, { kind: 'REJECT', reason: reason.trim() })
    activity.log(auth.user.name, `打回方案单 ${c.id}：${reason.trim()}`, c.id)
    return { ok: true }
  }

  /**
   * 医生直接改单（审核环节微调）→ 视为医生确认通过（APPROVED 待写病历）。
   * 原值/改后值 append-only 留痕，咨询师可见。
   */
  function doctorEdit(
    id: string,
    payload: { conclusion?: string; planItems: PlanItem[]; reason: string },
  ): { ok: boolean; error?: string } {
    if (!auth.can('consult:review')) return { ok: false, error: '无 consult:review 权限' }
    const c = consultations.value.find((x) => x.id === id)
    if (!c || c.status !== 'PENDING_REVIEW') return { ok: false, error: '仅待审核单可由医生改单' }
    if (!payload.planItems.length) return { ok: false, error: '方案明细为空' }

    const changes: PlanRevision['changes'] = []
    if (payload.conclusion !== undefined && payload.conclusion !== c.conclusion) {
      changes.push({ field: 'conclusion', label: '咨询结论', from: c.conclusion || '（空）', to: payload.conclusion })
      c.conclusion = payload.conclusion
    }
    const oldAmount = c.planAmount ?? 0
    const newItems = payload.planItems
    const newAmount = newItems.reduce((s, it) => s + it.qty * it.price, 0)
    const oldNames = (c.planItems ?? []).map((i) => `${i.name}×${i.qty}`).join('、') || '（空）'
    const newNames = newItems.map((i) => `${i.name}×${i.qty}`).join('、')
    if (oldNames !== newNames || oldAmount !== newAmount) {
      changes.push({ field: 'planItems', label: '方案明细', from: `${oldNames}（¥${oldAmount}）`, to: `${newNames}（¥${newAmount}）` })
      c.planItems = newItems
      c.planAmount = newAmount
      c.planCost = Math.round(newAmount * 0.35)
    }

    c.status = 'APPROVED'
    c.reviewedBy = auth.user.staffId
    c.reviewedByName = auth.user.name
    c.reviewedAt = new Date().toISOString()
    pushRevision(c, {
      kind: 'DOCTOR_EDIT',
      reason: payload.reason.trim() || '医生审核时调整方案',
      changes: changes.length ? changes : undefined,
    })
    activity.log(
      auth.user.name,
      `医生改单并通过 ${c.id}：${changes.length ? changes.map((x) => x.label).join('、') : '无字段变更'}，¥${newAmount}`,
      c.id,
    )
    return { ok: true }
  }

  /**
   * 医师工作台核心动作：审核通过 + 快捷写病历 + 电子签名 → 自动生成缴费单（READY_PAY）。
   * 可从 PENDING_REVIEW（边审边写，一步到位）或 APPROVED（已通过、面诊后补病历）进入。
   * 病历字段留空时由方案单自动带入（诊断=咨询结论、治疗方案=项目明细、过敏=面诊禁忌）。
   */
  function approveAndSignEmr(id: string, payload: QuickEmrPayload): { ok: boolean; error?: string; orderNo?: string } {
    if (!auth.can('consult:review')) return { ok: false, error: '无 consult:review 权限（仅医生/店长）' }
    const c = consultations.value.find((x) => x.id === id)
    if (!c) return { ok: false, error: '咨询单不存在' }
    if (c.status !== 'PENDING_REVIEW' && c.status !== 'APPROVED') {
      return { ok: false, error: '仅待审核/已通过待写病历的方案单可写病历' }
    }
    if (!(c.planItems?.length)) return { ok: false, error: '方案明细为空，无法生成病历与缴费单' }
    // 诊断/治疗方案为病历签名必填：优先用医生在快捷病历中填写的内容，否则取咨询结论/项目明细
    const finalDiagnosis = (payload.diagnosis ?? '').trim() || (c.conclusion ?? '').trim()
    if (!finalDiagnosis) {
      return { ok: false, error: '诊断与治疗方案为必填项：请在下方「诊断 / 皮肤评估」中补填（咨询结论为空），无需退回咨询环节' }
    }

    const emr = useEmrStore()
    const order = useOrderStore()

    // 1. 待审核单先落 APPROVED（满足 emr/order store 的"审核通过"校验）
    if (c.status === 'PENDING_REVIEW') {
      c.status = 'APPROVED'
      c.reviewedBy = auth.user.staffId
      c.reviewedByName = auth.user.name
      c.reviewedAt = new Date().toISOString()
      pushRevision(c, { kind: 'APPROVE', reason: '审核通过（与首程病历一并签署）' })
    }

    // 2. 创建首程病历并电子签名（方案明细/禁忌自动带入）
    const rec = emr.create({
      customerId: c.customerId,
      customerName: payload.customerName,
      type: 'FIRST_VISIT',
      visitDate: new Date().toISOString(),
      consultId: c.id,
      chiefComplaint: payload.chiefComplaint,
      presentIllness: payload.presentIllness,
      pastHistory: payload.pastHistory,
      diagnosis: payload.diagnosis,
      treatment: payload.treatment,
      prescription: payload.prescription,
    })
    if (!rec) return { ok: false, error: '首程病历创建失败（检查 emr:create 权限或方案状态）' }
    if (!emr.sign(rec.id)) {
      return { ok: false, error: '病历电子签名失败：诊断与治疗方案为必填项' }
    }
    c.emrId = rec.id
    c.emrSignedAt = new Date().toISOString()

    // 3. 按方案明细自动生成缴费单（收银台核单收款）
    const o = order.create({
      customerId: c.customerId,
      consultantId: c.consultantId,
      doctorId: c.doctorId,
      consultId: c.id,
      items: c.planItems.map((i) => ({ name: i.name, spec: i.spec, qty: i.qty, price: i.price })),
      remark: `方案单 ${c.id} 首程病历签署后自动生成`,
    })
    if (!o) return { ok: false, error: '缴费单生成失败（订单明细为空或方案未审核通过）' }
    c.orderId = o.id

    // 4. 流转 READY_PAY
    c.status = 'READY_PAY'
    pushRevision(c, { kind: 'EMR_SIGN', reason: `首程病历 ${rec.emrNo} 已签署，缴费单 ${o.orderNo} 已生成待支付` })
    activity.log(
      auth.user.name,
      `写病历并生成缴费单 ${payload.customerName}：病历 ${rec.emrNo} 已签、缴费单 ${o.orderNo} ¥${o.amount} 待支付`,
      c.id,
    )
    return { ok: true, orderNo: o.orderNo }
  }

  /** 缴费单收齐后由 order.pay 联动调用：READY_PAY → PAID（解锁治疗） */
  function markPaid(id: string): boolean {
    const c = get(id)
    if (!c || c.status !== 'READY_PAY') return false
    c.status = 'PAID'
    c.paidAt = new Date().toISOString()
    pushRevision(c, { kind: 'PAY', reason: '缴费单收款完成，方案解锁治疗' })
    activity.log(auth.user.name, `方案单 ${c.id} 已支付，进入待治疗`, c.id)
    return true
  }

  /**
   * 术前核对 → 开始治疗：PAID → TREATING。
   * 知情同意/禁忌复核/药品批号/治疗部位四项必须全部确认。
   */
  function startTreatment(id: string, checklist: PreOpChecklist): { ok: boolean; error?: string } {
    if (!auth.can('consult:review') && !auth.can('emr:edit')) {
      return { ok: false, error: '无治疗执行权限' }
    }
    const c = get(id)
    if (!c) return { ok: false, error: '咨询单不存在' }
    if (c.status !== 'PAID') return { ok: false, error: '仅已支付方案可开始治疗（请先确认缴费单收款）' }
    const allChecked =
      checklist.consentChecked && checklist.contraChecked && checklist.drugChecked && checklist.siteChecked
    if (!allChecked) return { ok: false, error: '术前核对四项必须全部确认' }
    // 知情同意须有客户手写电子签名归档
    if (checklist.consentChecked && !c.consentSignatureDataUrl) {
      return { ok: false, error: '未查到客户《知情同意书》手写电子签名，请回到咨询环节补签后再开始治疗' }
    }

    c.status = 'TREATING'
    c.preOp = checklist
    c.treatingAt = new Date().toISOString()
    pushRevision(c, { kind: 'TREAT_START', reason: `术前四项核对通过${checklist.room ? `，${checklist.room}` : ''}` })
    activity.log(auth.user.name, `方案单 ${c.id} 术前核对通过，开始治疗`, c.id)
    return { ok: true }
  }

  /**
   * 完成治疗：TREATING → DONE。
   * 写治疗记录病历（TREATMENT，电子签名）+ 自动生成术后随访计划（术后第 3 天电话回访）。
   */
  function completeTreatment(
    id: string,
    payload: { customerName: string; treatmentNote?: string; prescription?: string },
  ): { ok: boolean; error?: string } {
    if (!auth.can('consult:review') && !auth.can('emr:edit')) {
      return { ok: false, error: '无治疗记录权限' }
    }
    const c = get(id)
    if (!c || c.status !== 'TREATING') return { ok: false, error: '仅治疗中方案可完成治疗' }

    const emr = useEmrStore()
    const followup = useFollowupStore()

    // 治疗记录病历
    const rec = emr.create({
      customerId: c.customerId,
      customerName: payload.customerName,
      type: 'TREATMENT',
      visitDate: new Date().toISOString(),
      consultId: c.id,
      treatment: payload.treatmentNote,
      prescription: payload.prescription,
    })
    if (!rec) return { ok: false, error: '治疗记录创建失败' }
    emr.sign(rec.id)
    c.treatmentEmrId = rec.id

    // 术后 SOP 自动化：按模板生成多节点随访（24h关怀/第3天回访/第7天恢复/第30天复诊）
    const serviceDate = new Date()
    const sopNodes = followup.schedulePostOpSop({
      customerId: c.customerId,
      customerName: payload.customerName,
      project: (c.planItems ?? []).map((i) => i.name).join('、') || '医美治疗',
      serviceDate: serviceDate.toISOString(),
    })
    if (sopNodes.length) c.followupId = sopNodes[0].id

    c.status = 'DONE'
    c.treatedAt = new Date().toISOString()
    pushRevision(c, { kind: 'TREAT_DONE', reason: `治疗记录 ${rec.emrNo} 已签署，术后 SOP 已生成 ${sopNodes.length} 个随访节点` })
    activity.log(
      auth.user.name,
      `方案单 ${c.id} 治疗完成归档：治疗记录 ${rec.emrNo}，术后随访 SOP ${sopNodes.length} 节点已排程`,
      c.id,
    )
    return { ok: true }
  }

  /** 作废：审核中/已通过/待支付单需医生/主管（consult:review），防咨询师绕过审核 */
  function abandon(id: string, reason: string): { ok: boolean; error?: string } {
    if (!auth.can('consult:review') && !auth.can('consult:edit')) {
      return { ok: false, error: '无操作权限' }
    }
    const c = consultations.value.find((x) => x.id === id)
    if (!c) return { ok: false, error: '咨询单不存在' }
    const locked = ['PENDING_REVIEW', 'APPROVED', 'READY_PAY'].includes(c.status)
    if (locked && !auth.can('consult:review')) {
      return { ok: false, error: '审核中/已通过/待支付的方案单作废需医生或主管权限' }
    }
    if (c.status === 'PAID' || c.status === 'TREATING' || c.status === 'DONE') {
      return { ok: false, error: '已支付/治疗中的方案不可作废，请走退款流程' }
    }
    if (!canTransit(c.status, 'ABANDONED') && c.status !== 'REJECTED') {
      return { ok: false, error: '当前状态不可作废' }
    }
    c.status = 'ABANDONED'
    pushRevision(c, { kind: 'REJECT', reason: `作废：${reason}` })
    activity.log(auth.user.name, `作废咨询单 ${c.id}（${reason}）`, c.id)
    return { ok: true }
  }

  /** 缴费单/病历/随访回写（由 order/emr store 联动调用） */
  function linkOrder(id: string, orderId: string) {
    const c = get(id)
    if (c) c.orderId = orderId
  }
  function linkEmr(id: string, emrId: string) {
    const c = get(id)
    if (c) c.emrId = emrId
  }

  return {
    consultations,
    pending, active, queue, submitted, reviewing, approved, readyPay, paid, treating, done, treatmentQueue, planned,
    seed, get, byCustomer, open, start,
    submitPlan, approve, reject, doctorEdit, approveAndSignEmr,
    markPaid, startTreatment, completeTreatment, abandon,
    linkOrder, linkEmr,
  }
})
