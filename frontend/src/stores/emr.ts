// ============================================================
// EMR 聚合 store（电子病历）
// 状态机：DRAFT（草稿）→ SIGNED（医生签名/锁定）→ ARCHIVED（已归档）。
// 合规要求：SIGNED/ARCHIVED 病历内容不可修改，需更正只能"新建修订"（基于原单复制为新草稿）。
// 每次签名留痕 signedByName/signedAt；修订记录 parentId 溯源。
// 权限：emr:create 建病历 / emr:edit 编辑草稿与签名 / emr:view 查看。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { useConsultationStore } from './consultation'

export type EmrStatus = 'DRAFT' | 'SIGNED' | 'ARCHIVED'
export type EmrType = 'FIRST_VISIT' | 'FOLLOW_UP' | 'TREATMENT' | 'PROCEDURE'

export interface EmrRecord {
  id: string
  emrNo: string
  customerId: string
  customerName: string
  type: EmrType
  visitDate: string
  doctorName: string
  chiefComplaint: string // 主诉
  presentIllness: string // 现病史
  pastHistory: string // 既往史
  allergy: string // 过敏史
  diagnosis: string // 诊断/皮肤评估
  treatment: string // 治疗方案/操作记录
  prescription: string // 医嘱/术后注意事项
  relatedAppointmentNo?: string
  relatedOrderNo?: string
  /** 关联咨询方案单（医生据审核通过的方案写病历/治疗记录） */
  consultId?: string
  status: EmrStatus
  version: number
  parentId?: string // 修订溯源
  signedByName?: string
  signedAt?: string
  createdAt: string
  updatedAt: string
}

const TYPE_LABEL: Record<EmrType, string> = {
  FIRST_VISIT: '初诊',
  FOLLOW_UP: '复诊',
  TREATMENT: '治疗记录',
  PROCEDURE: '操作记录',
}
export { TYPE_LABEL as EMR_TYPE_LABEL }

const TRANSITIONS: Record<EmrStatus, EmrStatus[]> = {
  DRAFT: ['SIGNED'],
  SIGNED: ['ARCHIVED'],
  ARCHIVED: [],
}

export const useEmrStore = defineStore('emr', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const records = ref<EmrRecord[]>([])
  let seq = 0

  const drafts = computed(() => records.value.filter((r) => r.status === 'DRAFT'))
  const signed = computed(() => records.value.filter((r) => r.status === 'SIGNED'))
  const archived = computed(() => records.value.filter((r) => r.status === 'ARCHIVED'))
  /** 已锁定（不可编辑） */
  const locked = computed(() => records.value.filter((r) => r.status !== 'DRAFT'))

  function get(id: string) {
    return records.value.find((r) => r.id === id)
  }

  function canTransit(from: EmrStatus, to: EmrStatus) {
    return TRANSITIONS[from]?.includes(to) ?? false
  }

  /** 客户的全部病历（按就诊日期倒序） */
  function byCustomer(customerId: string) {
    return records.value
      .filter((r) => r.customerId === customerId)
      .sort((a, b) => b.visitDate.localeCompare(a.visitDate))
  }

  /** 某咨询方案单关联的全部病历（最新在前），用于医师台/EMR 双向联动定位 */
  function byConsult(consultId: string) {
    return records.value
      .filter((r) => r.consultId === consultId)
      .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
  }

  function create(input: {
    customerId: string
    customerName: string
    type: EmrType
    visitDate: string
    chiefComplaint?: string
    presentIllness?: string
    pastHistory?: string
    allergy?: string
    diagnosis?: string
    treatment?: string
    prescription?: string
    relatedAppointmentNo?: string
    relatedOrderNo?: string
    /** 关联咨询方案单：须 APPROVED，且 customerId 一致；自动带入方案/禁忌内容 */
    consultId?: string
  }): EmrRecord | null {
    if (!auth.can('emr:create')) {
      console.warn('[emr] 无 emr:create 权限')
      return null
    }
    let consultPrefill: { treatment: string; allergy: string; diagnosis: string } | null = null
    if (input.consultId) {
      const consult = useConsultationStore()
      const c = consult.get(input.consultId)
      if (!c) {
        console.warn('[emr] 关联咨询单不存在')
        return null
      }
      // 审核通过之后的履约态（待写病历/待支付/待治疗/治疗中/完成）均可据单写病历或治疗记录
      const postApprove = ['APPROVED', 'READY_PAY', 'PAID', 'TREATING', 'DONE']
      if (!postApprove.includes(c.status)) {
        console.warn(`[emr] 方案单 ${c.id} 未经医生审核通过（当前 ${c.status}），不可据单写病历`)
        return null
      }
      if (c.customerId !== input.customerId) {
        console.warn('[emr] 病历客户与方案单客户不一致')
        return null
      }
      const itemNames = (c.planItems ?? []).map((i) => `${i.name}×${i.qty}`).join('、')
      const contraNote = c.contraindications?.note ? `；面诊备注：${c.contraindications.note}` : ''
      consultPrefill = {
        treatment: input.treatment?.trim() || itemNames,
        allergy:
          input.allergy?.trim() ||
          (c.contraindications?.allergy ? `过敏史阳性${contraNote}` : c.contraindications?.note ? c.contraindications.note : ''),
        diagnosis: input.diagnosis?.trim() || c.conclusion,
      }
    }
    seq += 1
    const now = new Date().toISOString()
    const r: EmrRecord = {
      id: nextId('emr'),
      emrNo: `EMR${Date.now().toString().slice(-8)}${seq}`,
      customerId: input.customerId,
      customerName: input.customerName,
      type: input.type,
      visitDate: input.visitDate,
      doctorName: auth.user.name,
      chiefComplaint: input.chiefComplaint?.trim() ?? '',
      presentIllness: input.presentIllness?.trim() ?? '',
      pastHistory: input.pastHistory?.trim() ?? '',
      allergy: consultPrefill?.allergy ?? input.allergy?.trim() ?? '',
      diagnosis: consultPrefill?.diagnosis ?? input.diagnosis?.trim() ?? '',
      treatment: consultPrefill?.treatment ?? input.treatment?.trim() ?? '',
      prescription: input.prescription?.trim() ?? '',
      relatedAppointmentNo: input.relatedAppointmentNo,
      relatedOrderNo: input.relatedOrderNo,
      consultId: input.consultId,
      status: 'DRAFT',
      version: 1,
      createdAt: now,
      updatedAt: now,
    }
    records.value.unshift(r)
    if (input.consultId) useConsultationStore().linkEmr(input.consultId, r.id)
    activity.log(
      auth.user.name,
      `新建${TYPE_LABEL[input.type]}病历 ${r.emrNo}（${r.customerName}）${input.consultId ? `，据方案单 ${input.consultId}` : ''}`,
      r.id,
    )
    return r
  }

  /** 更新草稿（已签名/归档病历禁止编辑） */
  function updateDraft(id: string, patch: Partial<Omit<EmrRecord, 'id' | 'emrNo' | 'status' | 'version' | 'parentId' | 'signedByName' | 'signedAt' | 'createdAt'>>): boolean {
    const r = records.value.find((x) => x.id === id)
    if (!r || r.status !== 'DRAFT') {
      console.warn('[emr] 仅草稿状态可编辑')
      return false
    }
    if (!auth.can('emr:edit')) {
      console.warn('[emr] 无 emr:edit 权限')
      return false
    }
    Object.assign(r, patch)
    r.updatedAt = new Date().toISOString()
    return true
  }

  /** 医生签名：草稿 → 已签名（锁定内容） */
  function sign(id: string): boolean {
    const r = records.value.find((x) => x.id === id)
    if (!r || !canTransit(r.status, 'SIGNED')) return false
    if (!auth.can('emr:edit')) {
      console.warn('[emr] 无 emr:edit 权限')
      return false
    }
    // 签名前关键字段校验
    if (!r.diagnosis.trim() || !r.treatment.trim()) {
      console.warn('[emr] 诊断与治疗方案为签名必填项')
      return false
    }
    const now = new Date().toISOString()
    r.status = 'SIGNED'
    r.signedByName = auth.user.name
    r.signedAt = now
    r.updatedAt = now
    activity.log(auth.user.name, `病历 ${r.emrNo} 已电子签名并锁定`, r.id)
    return true
  }

  /** 归档：已签名 → 已归档（通常由归档/对账流程触发） */
  function archive(id: string): boolean {
    const r = records.value.find((x) => x.id === id)
    if (!r || !canTransit(r.status, 'ARCHIVED')) return false
    if (!auth.can('emr:edit')) {
      console.warn('[emr] 无 emr:edit 权限')
      return false
    }
    r.status = 'ARCHIVED'
    r.updatedAt = new Date().toISOString()
    activity.log(auth.user.name, `病历 ${r.emrNo} 已归档`, r.id)
    return true
  }

  /**
   * 新建修订：已签名/归档病历不可改，基于原单复制为新草稿（version+1，parentId 指向原单）。
   * 合规留痕：原单保留，修订单溯源。
   */
  function revise(id: string): EmrRecord | null {
    const src = records.value.find((x) => x.id === id)
    if (!src || src.status === 'DRAFT') return null
    if (!auth.can('emr:create')) {
      console.warn('[emr] 无 emr:create 权限')
      return null
    }
    seq += 1
    const now = new Date().toISOString()
    const r: EmrRecord = {
      ...src,
      id: nextId('emr'),
      emrNo: `${src.emrNo}-R${src.version + 1}`,
      status: 'DRAFT',
      version: src.version + 1,
      parentId: src.id,
      signedByName: undefined,
      signedAt: undefined,
      createdAt: now,
      updatedAt: now,
    }
    records.value.unshift(r)
    activity.log(auth.user.name, `基于病历 ${src.emrNo} 新建修订版本 ${r.emrNo}`, r.id)
    return r
  }

  /** 开发期种子 */
  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    const today = new Date()
    const iso = (d: Date) => d.toISOString().slice(0, 10)
    const shift = (n: number) => { const d = new Date(today); d.setDate(d.getDate() + n); return iso(d) }

    const seedData: Array<Partial<EmrRecord> & {
      customerName: string; type: EmrType; status: EmrStatus; visitDate: string;
      chiefComplaint: string; diagnosis: string; treatment: string;
    }> = [
      {
        customerName: '王美丽', type: 'FIRST_VISIT', status: 'DRAFT', visitDate: shift(0),
        chiefComplaint: '面部肤色暗沉，毛孔粗大，咨询光子嫩肤改善。',
        presentIllness: '近半年自觉面部肤色不均，T 区出油明显，无既往光电治疗史。',
        pastHistory: '否认高血压、糖尿病及瘢痕体质。', allergy: '否认药物及化妆品过敏。',
        diagnosis: '面部光老化、毛孔粗大（Fitzpatrick III 型）',
        treatment: '建议行 IPL 光子嫩肤全脸治疗，能量 16-18J/cm²，术后即刻冷敷 20 分钟。',
        prescription: '严格防晒 SPF50+；术后 3 天每日补水面膜；如有持续红肿及时复诊。',
        relatedOrderNo: 'SO20260825001',
      },
      {
        customerName: '陈思', type: 'TREATMENT', status: 'SIGNED', visitDate: shift(-3),
        chiefComplaint: '按疗程行第三次水光针注射。',
        presentIllness: '前两次注射恢复良好，无不良反应。',
        pastHistory: '无特殊。', allergy: '否认过敏。',
        diagnosis: '皮肤干燥，水光治疗中（第 3/5 次）',
        treatment: '透明质酸 2ml 全面部微量注射，术中生命体征平稳，无渗血异常。',
        prescription: '6 小时内避免沾水；一周内避免高温瑜伽及辛辣；2 周后复诊。',
        relatedAppointmentNo: 'AP20260822007', relatedOrderNo: 'SO20260822005',
      },
      {
        customerName: '赵敏', type: 'PROCEDURE', status: 'SIGNED', visitDate: shift(-7),
        chiefComplaint: '行热玛吉 4 代面部紧致治疗。',
        presentIllness: '面部轻中度松弛，要求非手术紧致。',
        pastHistory: '体健，否认金属植入物及心脏起搏器。', allergy: '否认过敏。',
        diagnosis: '面部皮肤松弛（轻中度）',
        treatment: 'Thermage 4 代，面部 600 发，能量 3.0-4.5，表皮温度控制 ≤40℃，患者耐受可。',
        prescription: '术后红肿 1-3 天属正常；加强保湿防晒；如有水泡即刻联系医生。',
        relatedOrderNo: 'SO20260818003',
      },
      {
        customerName: '周婷', type: 'FOLLOW_UP', status: 'ARCHIVED', visitDate: shift(-14),
        chiefComplaint: '果酸焕肤术后两周复查。',
        presentIllness: '术后轻微脱屑 3 天后恢复，肤色提亮明显。',
        pastHistory: '无特殊。', allergy: '否认过敏。',
        diagnosis: '果酸焕肤术后恢复良好',
        treatment: '复查无异常，建议继续按疗程每月一次，共 4 次。',
        prescription: '日常防晒保湿；下月预约下次治疗。',
      },
    ]

    seedData.forEach((s, i) => {
      seq += 1
      const createdIso = new Date(s.visitDate).toISOString()
      const r: EmrRecord = {
        id: nextId('emr'),
        emrNo: `EMR202608${20 + i}0${i + 1}`,
        customerId: `C-60${i}`,
        customerName: s.customerName!,
        type: s.type,
        visitDate: s.visitDate,
        doctorName: '顾屿（主治医师）',
        chiefComplaint: s.chiefComplaint,
        presentIllness: s.presentIllness ?? '',
        pastHistory: s.pastHistory ?? '',
        allergy: s.allergy ?? '',
        diagnosis: s.diagnosis,
        treatment: s.treatment,
        prescription: s.prescription ?? '',
        relatedAppointmentNo: s.relatedAppointmentNo,
        relatedOrderNo: s.relatedOrderNo,
        status: s.status,
        version: 1,
        createdAt: createdIso,
        updatedAt: createdIso,
      }
      if (s.status !== 'DRAFT') {
        r.signedByName = '顾屿（主治医师）'
        r.signedAt = createdIso
      }
      records.value.push(r)
    })
  }

  return {
    records, drafts, signed, archived, locked,
    get, canTransit, byCustomer, byConsult, create, updateDraft, sign, archive, revise, seed,
  }
})
