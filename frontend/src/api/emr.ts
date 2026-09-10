// ============================================================
// EMR 电子病历 API（对接 txn-service 病历独立域：/api/txn/emr）
//
// 状态机：DRAFT（草稿）→ SIGNED（电子签名/锁定）→ ARCHIVED（已归档）。
// 已签名/归档不可改，更正只能「新建修订」（复制为新草稿，version+1，parentId 溯源）。
// 权限：读 emr:view / 建 emr:create / 草稿与签名 emr:edit；门店由后端按 JWT 锁本店。
// ============================================================
import client from './client'

export type EmrStatus = 'DRAFT' | 'SIGNED' | 'ARCHIVED'
export type EmrType = 'FIRST_VISIT' | 'FOLLOW_UP' | 'TREATMENT' | 'PROCEDURE'

/** 病历读模型（id 与 emrNo 同值，对齐前端 id 主键契约）。 */
export interface EmrViewDTO {
  id: string
  emrNo: string
  customerId: string | null
  customerName: string
  storeCode: string
  type: EmrType | string
  visitDate: string
  status: EmrStatus | string
  chiefComplaint: string | null
  presentIllness: string | null
  pastHistory: string | null
  allergy: string | null
  diagnosis: string | null
  treatment: string | null
  prescription: string | null
  doctorId: string | null
  doctorName: string | null
  relatedAppointmentNo: string | null
  relatedOrderNo: string | null
  consultId: string | null
  version: number
  parentId: string | null
  signedBy: string | null
  signedByName: string | null
  signedAt: string | null
  createdBy: string | null
  createdAt: string
  updatedAt: string
}

export interface CreateEmrCmd {
  customerId: string
  customerName?: string
  type?: string
  visitDate?: string
  chiefComplaint?: string
  presentIllness?: string
  pastHistory?: string
  allergy?: string
  diagnosis?: string
  treatment?: string
  prescription?: string
  relatedOrderNo?: string
  consultId?: string
}

export interface DraftEmrCmd {
  chiefComplaint?: string
  presentIllness?: string
  pastHistory?: string
  allergy?: string
  diagnosis?: string
  treatment?: string
  prescription?: string
}

/** 病历列表（本店全量，不分页；三 tab 由前端按 status 分组）。 */
export const listEmr = (params?: { storeCode?: string; status?: string; customerId?: string; consultId?: string }) =>
  client.get<EmrViewDTO[]>('/txn/emr', { params })

/** 病历详情（越权跨店统一 404）。 */
export const getEmr = (emrNo: string) =>
  client.get<EmrViewDTO>(`/txn/emr/${emrNo}`)

/** 新建病历（customerId 必填；无建档客户由后端 400 中文引导先建档）。 */
export const createEmr = (cmd: CreateEmrCmd) =>
  client.post<EmrViewDTO>('/txn/emr', cmd)

/** 保存草稿（仅 DRAFT 态，七文本字段宽松更新）。 */
export const saveDraftEmr = (emrNo: string, cmd: DraftEmrCmd) =>
  client.post<EmrViewDTO>(`/txn/emr/${emrNo}/draft`, cmd)

/** 电子签名：DRAFT → SIGNED，后端盖 JWT 当前人员（diagnosis/treatment 必填校验在后端）。 */
export const signEmr = (emrNo: string) =>
  client.post<EmrViewDTO>(`/txn/emr/${emrNo}/sign`, {})

/** 归档：SIGNED → ARCHIVED。 */
export const archiveEmr = (emrNo: string) =>
  client.post<EmrViewDTO>(`/txn/emr/${emrNo}/archive`, {})

/** 新建修订：源单须非 DRAFT；复制为新草稿，version+1，emrNo 存「源号-R{n}」，parentId 溯源。 */
export const reviseEmr = (emrNo: string) =>
  client.post<EmrViewDTO>(`/txn/emr/${emrNo}/revise`, {})
