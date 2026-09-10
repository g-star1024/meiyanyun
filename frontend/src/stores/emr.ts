// ============================================================
// EMR 聚合 store（电子病历）—— 已接真实 txn-service 病历独立域 /api/txn/emr
// 状态机：DRAFT（草稿）→ SIGNED（医生签名/锁定）→ ARCHIVED（已归档）。
// 合规要求：SIGNED/ARCHIVED 病历内容不可修改，需更正只能"新建修订"（基于原单复制为新草稿）。
// 每次签名留痕 signedByName/signedAt；修订记录 parentId 溯源。
// 适配层（铁律：模板/样式零改动，只换数据源）：
//  - id=emrNo、visitDate 直传后端 LocalDate（yyyy-MM-dd，禁止 toISOString 时区错位）
//  - 七文本/doctorName null → ''；权限与状态机校验由后端兜底，400/403 中文经 errMsg 外露
//  - 写动作失败 toast 中文 message 并返回 null/false；seed 保留空函数兼容旧演示入口
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { useCustomerStore } from './customer'
import { useToast } from '@/composables/useToast'
import { errMsg } from './m5Coupon'
import {
  listEmr, createEmr, saveDraftEmr, signEmr, archiveEmr, reviseEmr,
  type EmrViewDTO,
} from '@/api/emr'

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

const SEVEN_FIELDS = [
  'chiefComplaint', 'presentIllness', 'pastHistory', 'allergy',
  'diagnosis', 'treatment', 'prescription',
] as const

export const useEmrStore = defineStore('emr', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()
  const customer = useCustomerStore()
  const toast = useToast()

  const records = ref<EmrRecord[]>([])

  /** 兼容旧演示入口：真实数据由页面 onMounted 调 load 拉取 */
  function seed() {}

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

  function adaptEmr(d: EmrViewDTO): EmrRecord {
    const text = (v: string | null) => v ?? ''
    return {
      id: d.emrNo || d.id,
      emrNo: d.emrNo || d.id,
      customerId: d.customerId ?? '',
      customerName: d.customerName,
      type: d.type as EmrType,
      visitDate: d.visitDate,
      doctorName: text(d.doctorName),
      chiefComplaint: text(d.chiefComplaint),
      presentIllness: text(d.presentIllness),
      pastHistory: text(d.pastHistory),
      allergy: text(d.allergy),
      diagnosis: text(d.diagnosis),
      treatment: text(d.treatment),
      prescription: text(d.prescription),
      relatedAppointmentNo: d.relatedAppointmentNo ?? undefined,
      relatedOrderNo: d.relatedOrderNo ?? undefined,
      consultId: d.consultId ?? undefined,
      status: d.status as EmrStatus,
      version: d.version,
      parentId: d.parentId ?? undefined,
      signedByName: d.signedByName ?? undefined,
      signedAt: d.signedAt ?? undefined,
      createdAt: d.createdAt,
      updatedAt: d.updatedAt,
    }
  }

  /** 拉取病历列表（锁当前门店；可按 status/customerId/consultId 过滤） */
  async function load(
    storeCode?: string,
    filters?: { status?: string; customerId?: string; consultId?: string },
  ): Promise<boolean> {
    try {
      const res = await listEmr({
        storeCode,
        status: filters?.status,
        customerId: filters?.customerId,
        consultId: filters?.consultId,
      })
      const list = res.data ?? []
      records.value = list.map(adaptEmr)
      customer.hydrate(list
        .filter((d) => !!d.customerId)
        .map((d) => ({ customerId: d.customerId as string, customerName: d.customerName })))
      return true
    } catch (e) {
      records.value = []
      toast.error(errMsg(e, '病历列表加载失败'))
      return false
    }
  }

  async function create(input: {
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
    /** 关联咨询方案单：预填由视图层经 getPlan 完成，后端校验方案存在且客户一致 */
    consultId?: string
  }): Promise<EmrRecord | null> {
    try {
      const res = await createEmr({
        customerId: input.customerId,
        customerName: input.customerName,
        type: input.type,
        visitDate: input.visitDate,
        chiefComplaint: input.chiefComplaint?.trim() || undefined,
        presentIllness: input.presentIllness?.trim() || undefined,
        pastHistory: input.pastHistory?.trim() || undefined,
        allergy: input.allergy?.trim() || undefined,
        diagnosis: input.diagnosis?.trim() || undefined,
        treatment: input.treatment?.trim() || undefined,
        prescription: input.prescription?.trim() || undefined,
        relatedOrderNo: input.relatedOrderNo?.trim() || undefined,
        consultId: input.consultId,
      })
      const r = adaptEmr(res.data)
      activity.log(
        auth.user.name,
        `新建${TYPE_LABEL[input.type] ?? ''}病历 ${r.emrNo}（${r.customerName}）${input.consultId ? `，据方案单 ${input.consultId}` : ''}`,
        r.id,
      )
      await load(auth.user.storeId)
      return r
    } catch (e) {
      toast.error(errMsg(e, '新建病历失败'))
      return null
    }
  }

  /** 更新草稿（已签名/归档病历禁止编辑；仅七文本落后端，其余 patch 字段忽略） */
  async function updateDraft(
    id: string,
    patch: Partial<Pick<EmrRecord, typeof SEVEN_FIELDS[number]>>,
  ): Promise<boolean> {
    const cmd: Record<string, string> = {}
    for (const k of SEVEN_FIELDS) {
      if (patch[k] !== undefined) cmd[k] = patch[k]
    }
    try {
      await saveDraftEmr(id, cmd)
      await load(auth.user.storeId)
      return true
    } catch (e) {
      toast.error(errMsg(e, '病历草稿保存失败'))
      return false
    }
  }

  /** 医生签名：草稿 → 已签名（锁定内容；diagnosis/treatment 必填校验在后端） */
  async function sign(id: string): Promise<boolean> {
    try {
      await signEmr(id)
      activity.log(auth.user.name, `病历 ${id} 已电子签名并锁定`, id)
      await load(auth.user.storeId)
      return true
    } catch (e) {
      toast.error(errMsg(e, '病历签名失败'))
      return false
    }
  }

  /** 归档：已签名 → 已归档（通常由归档/对账流程触发） */
  async function archive(id: string): Promise<boolean> {
    try {
      await archiveEmr(id)
      activity.log(auth.user.name, `病历 ${id} 已归档`, id)
      await load(auth.user.storeId)
      return true
    } catch (e) {
      toast.error(errMsg(e, '病历归档失败'))
      return false
    }
  }

  /**
   * 新建修订：已签名/归档病历不可改，基于原单复制为新草稿（version+1，parentId 指向原单）。
   * 合规留痕：原单保留，修订单溯源。
   */
  async function revise(id: string): Promise<EmrRecord | null> {
    try {
      const res = await reviseEmr(id)
      const r = adaptEmr(res.data)
      activity.log(auth.user.name, `基于病历 ${id} 新建修订版本 ${r.emrNo}`, r.id)
      await load(auth.user.storeId)
      return r
    } catch (e) {
      toast.error(errMsg(e, '新建修订失败'))
      return null
    }
  }

  return {
    records, drafts, signed, archived, locked,
    get, canTransit, byCustomer, byConsult,
    load, create, updateDraft, sign, archive, revise, seed,
  }
})
