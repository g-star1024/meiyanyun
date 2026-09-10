// ============================================================
// EMR 聚合 store（电子病历）—— 已接真实 txn-service 病历独立域 /api/txn/emr
// 状态机：DRAFT（草稿）→ SIGNED（医生签名/锁定）→ ARCHIVED（已归档）。
// 合规要求：SIGNED/ARCHIVED 病历内容不可修改，需更正只能"新建修订"（基于原单复制为新草稿）。
// 每次签名留痕 signedByName/signedAt；修订记录 parentId 溯源。
// P5-B30：列表真分页（后端固定排序）+ stats 计数聚合（tab 角标/KPI）+ emr_template 模板套用。
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
  listEmr, createEmr, saveDraftEmr, signEmr, archiveEmr, reviseEmr, getEmr,
  statsEmr, listEmrTemplates, createEmrTemplate, disableEmrTemplate,
  type EmrViewDTO, type EmrStats, type EmrTemplateDTO,
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

export interface EmrTemplate {
  templateNo: string
  name: string
  type: EmrType | null
  chiefComplaint: string
  presentIllness: string
  pastHistory: string
  allergy: string
  diagnosis: string
  treatment: string
  prescription: string
  storeCode: string | null
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

const EMPTY_STATS: EmrStats = { draft: 0, signed: 0, archived: 0, signedThisMonth: 0 }

export const useEmrStore = defineStore('emr', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()
  const customer = useCustomerStore()
  const toast = useToast()

  /** 当前 tab 当前页记录（分页后不再全量驻留）。 */
  const records = ref<EmrRecord[]>([])
  const page = ref(1)
  const pageSize = ref(20)
  const total = ref(0)
  const totalPages = ref(1)
  const loading = ref(false)
  /** 本店病历计数（tab 角标 / KPI 取后端聚合）。 */
  const stats = ref<EmrStats>({ ...EMPTY_STATS })
  /** 详情缓存：跨页选中、byConsult 回查命中，避免当前页换页后详情丢失。 */
  const detailCache = ref<Record<string, EmrRecord>>({})

  /** 兼容旧演示入口：真实数据由页面 onMounted 调 load 拉取 */
  function seed() {}

  const drafts = computed(() => stats.value.draft)
  const signed = computed(() => stats.value.signed)
  const archived = computed(() => stats.value.archived)
  /** 已锁定（不可编辑）= 已签名 + 已归档 */
  const locked = computed(() => stats.value.signed + stats.value.archived)

  function cacheRecord(r: EmrRecord) {
    detailCache.value[r.id] = r
  }

  function get(id: string) {
    return records.value.find((r) => r.id === id) ?? detailCache.value[id]
  }

  function canTransit(from: EmrStatus, to: EmrStatus) {
    return TRANSITIONS[from]?.includes(to) ?? false
  }

  /** 客户的全部病历（按就诊日期倒序）：仅当前页+缓存覆盖，跨页精确请走列表 customerId 过滤。 */
  function byCustomer(customerId: string) {
    const seen = new Map<string, EmrRecord>()
    for (const r of records.value) if (r.customerId === customerId) seen.set(r.id, r)
    for (const r of Object.values(detailCache.value)) {
      if (r.customerId === customerId) seen.set(r.id, r)
    }
    return [...seen.values()].sort((a, b) => b.visitDate.localeCompare(a.visitDate))
  }

  /** 某咨询方案单关联病历：先查缓存，未命中回查后端第一页（consultId 精确过滤）。 */
  async function byConsult(consultId: string): Promise<EmrRecord[]> {
    const hit = records.value.find((r) => r.consultId === consultId)
      ?? Object.values(detailCache.value).find((r) => r.consultId === consultId)
    if (hit) return [hit]
    try {
      const res = await listEmr({ consultId, page: 0, size: 50 })
      const list = (res.data.content ?? []).map(adaptEmr)
      list.forEach(cacheRecord)
      return list.sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
    } catch {
      return []
    }
  }

  /** 拉取单条病历详情并入缓存（跨页选中/直接链接）。 */
  async function fetchDetail(emrNo: string): Promise<EmrRecord | null> {
    const cached = detailCache.value[emrNo]
    if (cached && records.value.some((r) => r.id === emrNo)) return cached
    try {
      const res = await getEmr(emrNo)
      const r = adaptEmr(res.data)
      cacheRecord(r)
      return r
    } catch (e) {
      toast.error(errMsg(e, '病历详情加载失败'))
      return null
    }
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

  /**
   * 拉取病历分页（锁当前门店；后端固定 visitDate,createdAt 倒序，不信入参 sort）。
   * @param storeCode 门店码（省略取 JWT 本店）
   * @param filters status/customerId/consultId 精确 + q 四字段模糊
   * @param opts.page 1 起页码
   */
  async function load(
    storeCode?: string,
    filters?: { status?: string; customerId?: string; consultId?: string; q?: string },
    opts?: { page?: number; size?: number; silent?: boolean },
  ): Promise<boolean> {
    const targetPage = Math.max(1, opts?.page ?? page.value)
    const size = opts?.size ?? pageSize.value
    loading.value = true
    try {
      const res = await listEmr({
        storeCode,
        status: filters?.status,
        customerId: filters?.customerId,
        consultId: filters?.consultId,
        q: filters?.q?.trim() || undefined,
        page: targetPage - 1,
        size,
      })
      const data = res.data
      const list = (data.content ?? []).map(adaptEmr)
      records.value = list
      list.forEach(cacheRecord)
      total.value = data.totalElements ?? 0
      totalPages.value = Math.max(1, data.totalPages ?? 1)
      page.value = targetPage
      pageSize.value = size
      customer.hydrate(list
        .filter((d) => !!d.customerId)
        .map((d) => ({ customerId: d.customerId as string, customerName: d.customerName })))
      return true
    } catch (e) {
      records.value = []
      total.value = 0
      if (!opts?.silent) toast.error(errMsg(e, '病历列表加载失败'))
      return false
    } finally {
      loading.value = false
    }
  }

  /** 拉取本店计数（tab 角标/KPI；写动作后静默刷新）。 */
  async function loadStats(storeCode?: string): Promise<void> {
    try {
      stats.value = (await statsEmr(storeCode)).data
    } catch {
      // 计数失败不阻断主流程，保留上次计数
    }
  }

  /** 列表 + 计数一并刷新（页面初始化/写动作后）。 */
  async function refresh(
    storeCode?: string,
    filters?: { status?: string; customerId?: string; consultId?: string; q?: string },
    opts?: { page?: number; size?: number; silent?: boolean },
  ): Promise<boolean> {
    const ok = await load(storeCode, filters, opts)
    await loadStats(storeCode)
    return ok
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
      cacheRecord(r)
      activity.log(
        auth.user.name,
        `新建${TYPE_LABEL[input.type] ?? ''}病历 ${r.emrNo}（${r.customerName}）${input.consultId ? `，据方案单 ${input.consultId}` : ''}`,
        r.id,
      )
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
      const res = await saveDraftEmr(id, cmd)
      cacheRecord(adaptEmr(res.data))
      return true
    } catch (e) {
      toast.error(errMsg(e, '病历草稿保存失败'))
      return false
    }
  }

  /** 医生签名：草稿 → 已签名（锁定内容；diagnosis/treatment 必填校验在后端） */
  async function sign(id: string): Promise<EmrRecord | null> {
    try {
      const res = await signEmr(id)
      const r = adaptEmr(res.data)
      cacheRecord(r)
      activity.log(auth.user.name, `病历 ${id} 已电子签名并锁定`, id)
      return r
    } catch (e) {
      toast.error(errMsg(e, '病历签名失败'))
      return null
    }
  }

  /** 归档：已签名 → 已归档（通常由归档/对账流程触发） */
  async function archive(id: string): Promise<EmrRecord | null> {
    try {
      const res = await archiveEmr(id)
      const r = adaptEmr(res.data)
      cacheRecord(r)
      activity.log(auth.user.name, `病历 ${id} 已归档`, id)
      return r
    } catch (e) {
      toast.error(errMsg(e, '病历归档失败'))
      return null
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
      cacheRecord(r)
      activity.log(auth.user.name, `基于病历 ${id} 新建修订版本 ${r.emrNo}`, r.id)
      return r
    } catch (e) {
      toast.error(errMsg(e, '新建修订失败'))
      return null
    }
  }

  // ==================== 病历模板库（P5-B30） ====================

  function adaptTemplate(d: EmrTemplateDTO): EmrTemplate {
    const text = (v: string | null) => v ?? ''
    return {
      templateNo: d.templateNo,
      name: d.name,
      type: d.type ? (d.type as EmrType) : null,
      chiefComplaint: text(d.chiefComplaint),
      presentIllness: text(d.presentIllness),
      pastHistory: text(d.pastHistory),
      allergy: text(d.allergy),
      diagnosis: text(d.diagnosis),
      treatment: text(d.treatment),
      prescription: text(d.prescription),
      storeCode: d.storeCode,
    }
  }

  /** 套用候选：集团通用 + 本店自建；type 非空时下推「类型通用+指定类型」。 */
  async function loadTemplates(type?: string): Promise<EmrTemplate[]> {
    try {
      const res = await listEmrTemplates({ type: type || undefined, page: 0, size: 50 })
      return (res.data.content ?? []).map(adaptTemplate)
    } catch (e) {
      toast.error(errMsg(e, '病历模板加载失败'))
      return []
    }
  }

  /** 门店自建模板（复用 emr:create 权限；自动盖本店码）。 */
  async function createTemplate(input: {
    name: string
    type?: string | null
    chiefComplaint?: string
    presentIllness?: string
    pastHistory?: string
    allergy?: string
    diagnosis?: string
    treatment?: string
    prescription?: string
  }): Promise<EmrTemplate | null> {
    try {
      const res = await createEmrTemplate({
        name: input.name.trim(),
        type: input.type || null,
        chiefComplaint: input.chiefComplaint?.trim() || undefined,
        presentIllness: input.presentIllness?.trim() || undefined,
        pastHistory: input.pastHistory?.trim() || undefined,
        allergy: input.allergy?.trim() || undefined,
        diagnosis: input.diagnosis?.trim() || undefined,
        treatment: input.treatment?.trim() || undefined,
        prescription: input.prescription?.trim() || undefined,
      })
      const t = adaptTemplate(res.data)
      activity.log(auth.user.name, `新建病历模板 ${t.templateNo}（${t.name}）`, t.templateNo)
      return t
    } catch (e) {
      toast.error(errMsg(e, '新建病历模板失败'))
      return null
    }
  }

  /** 停用本店自建模板（集团模板只读，后端 404 中文）。 */
  async function disableTemplate(templateNo: string): Promise<boolean> {
    try {
      await disableEmrTemplate(templateNo)
      activity.log(auth.user.name, `停用病历模板 ${templateNo}`, templateNo)
      return true
    } catch (e) {
      toast.error(errMsg(e, '停用病历模板失败'))
      return false
    }
  }

  return {
    records, page, pageSize, total, totalPages, loading, stats,
    drafts, signed, archived, locked,
    get, canTransit, byCustomer, byConsult, fetchDetail,
    load, loadStats, refresh,
    create, updateDraft, sign, archive, revise, seed,
    loadTemplates, createTemplate, disableTemplate,
  }
})
