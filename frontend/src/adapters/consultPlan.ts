// ============================================================
// 方案单适配层（真实 API DTO ↔ 前端 mock 活规格 Consultation 同形状）
//
// 铁律 SOP：接后端只换数据源、模板/样式零改动。后端 PlanViewDTO 金额单位「分」、
// 字段名与前端 mock Consultation（单位「元」）不同，这里集中做：
//   1) adaptPlan：PlanViewDTO → Consultation（分→元、itemName→name、riskTags 串→数组、revId→字符串）
//   2) injectPlanShadows：把真实方案单/客户/员工以「影子记录」注入既有 pinia mock store，
//      使工作台模板里写死的 consultation.get / customer.nameOf / customer.get / staffName
//      无需任何改动即可解析到真实数据（右侧详情 v-if="selCustomer" 门控也因此正常渲染）。
// 状态码后端英文枚举（PENDING_REVIEW/APPROVED/REJECTED/READY_PAY/PAID）与前端 ConsultStatus 一致，无需映射。
// ============================================================
import { ALL_STAFF } from '@/config/staff'
import type { PlanViewDTO, PlanItemCmd } from '@/api/consultPlan'
import type {
  Consultation,
  ConsultContraindication,
  PlanItem,
  PlanRevision,
  Customer,
} from '@/types/domain'

/** 分 → 元（后端 bigint 分 → 前端 mock 元） */
export const fen2yuan = (fen?: number | null): number => (fen == null ? 0 : fen / 100)
/** 元 → 分（提交写动作） */
export const yuan2fen = (yuan: number): number => Math.round((yuan || 0) * 100)

/**
 * 影子单标记：真实 API 注入的 Consultation 带此标记，与本地 mock seed 单区分。
 * 用于工作台隔离——接真实的 tab 只取真实单，后端能力缺口的 tab（如治疗执行）保持 mock，
 * 避免真实单落入暂无后端端点的动作（开始/完成治疗）造成「本地改状态、刷新即失」的假交互。
 */
export const REAL_FLAG = '__realPlan'
export function isRealPlan(c: { status?: string } & Record<string, unknown>): boolean {
  return c?.[REAL_FLAG] === true
}

const noContra = (): ConsultContraindication => ({
  pregnant: false, allergy: false, scarConstitution: false,
  skinLesion: false, coagulationAbn: false, seriousIllness: false, note: '',
})

/** riskTags 后端逗号串 → 前端数组（空/空串 → undefined） */
function toRiskTags(s?: string | null): string[] | undefined {
  if (!s) return undefined
  const arr = s.split(/[,，]/).map((t) => t.trim()).filter(Boolean)
  return arr.length ? arr : undefined
}

/** PlanViewDTO → Consultation（mock 活规格同形状，金额转元） */
export function adaptPlan(dto: PlanViewDTO): Consultation {
  const contra = (dto.contraindications as Partial<ConsultContraindication> | null) || null
  const planItems: PlanItem[] = (dto.items || []).map((it) => ({
    itemId: it.itemCode || undefined,
    name: it.itemName,
    spec: it.spec || undefined,
    qty: it.qty,
    price: fen2yuan(it.unitPrice),
    riskTags: toRiskTags(it.riskTags),
  }))
  const revisions: PlanRevision[] = (dto.revisions || []).map((r) => ({
    id: String(r.revId),
    at: r.at || '',
    actorId: r.actorId || '',
    actorName: r.actorName || '',
    kind: (r.kind as PlanRevision['kind']) || 'SUBMIT',
    reason: r.reason || undefined,
  }))
  return {
    id: dto.planId,
    customerId: dto.customerId,
    consultantId: dto.consultantId || '',
    doctorId: dto.doctorId || undefined,
    // 后端英文状态枚举与前端 ConsultStatus 一致，直接用
    status: dto.status as Consultation['status'],
    conclusion: dto.conclusion || '',
    planAmount: fen2yuan(dto.planAmount),
    planCost: fen2yuan(dto.planCost),
    planItems,
    contraindications: contra ? { ...noContra(), ...contra } : noContra(),
    consentConsultant: dto.consentConsultant ?? false,
    consentCustomer: dto.consentCustomer ?? false,
    consentSignerName: dto.consentSignerName || undefined,
    consentDocVersion: dto.consentDocVersion || undefined,
    consentAt: dto.consentAt || undefined,
    skinReportId: dto.skinReportId || undefined,
    submittedAt: dto.submittedAt || undefined,
    reviewedByName: dto.reviewedByName || undefined,
    reviewedAt: dto.reviewedAt || undefined,
    rejectReason: dto.rejectReason || undefined,
    revisions,
    emrId: dto.emrId || undefined,
    emrSignedAt: dto.emrSignedAt || undefined,
    orderId: dto.orderNo || undefined,
    paidAt: dto.paidAt || undefined,
  }
}

/** 前端 PlanItem（元）→ 后端 PlanItemCmd（分），用于医生改单提交 */
export function toPlanItemCmd(items: PlanItem[]): PlanItemCmd[] {
  return items.map((it) => ({
    itemCode: it.itemId,
    itemName: it.name,
    spec: it.spec,
    qty: it.qty,
    unitPrice: yuan2fen(it.price),
    riskTags: it.riskTags?.length ? it.riskTags.join(',') : undefined,
  }))
}

/** 员工影子：真实 staffId 不在静态 ALL_STAFF 时补进，使 staffName(id) 解析到真实中文名 */
function ensureStaff(id?: string | null, name?: string | null) {
  if (!id || !name) return
  if (!ALL_STAFF.some((s) => s.id === id)) {
    ALL_STAFF.push({ id, name, title: '' })
  }
}

interface ShadowStores {
  consultation: { consultations: Consultation[] }
  customer: { customers: Customer[] }
}

/**
 * 把一条真实方案单以影子记录注入 mock store（咨询单 + 客户 + 员工名），
 * 返回适配后的 Consultation（已在 store 中，consultation.get(id) 可取）。
 * 幂等：同 planId 已注入则就地替换为最新数据。
 */
export function injectPlanShadows(dto: PlanViewDTO, stores: ShadowStores): Consultation {
  const c = adaptPlan(dto)

  // 咨询单影子（同 id 就地替换，保证动作后重载拿到最新状态）
  const list = stores.consultation.consultations
  const idx = list.findIndex((x) => x.id === c.id)
  if (idx >= 0) list[idx] = c
  else list.push(c)

  // 客户影子（右侧详情 v-if="selCustomer" 门控 + customer.nameOf 解析）
  if (dto.customerId && !stores.customer.customers.some((x) => x.id === dto.customerId)) {
    const name = dto.customerName || dto.customerId
    const shadow: Customer = {
      id: dto.customerId,
      name,
      avatarLetter: name[0] || '?',
      phoneMask: '',
      channel: 'WALK_IN',
      level: 'NEW',
      tags: [],
      storeId: dto.storeCode || '',
    }
    stores.customer.customers.push(shadow)
  }

  // 员工名影子
  ensureStaff(dto.consultantId, dto.consultantName)
  ensureStaff(dto.doctorId, dto.doctorName)

  return c
}

/** 从 mock store 移除一批影子咨询单（重载前清理，避免残留旧状态） */
export function removeShadowPlans(ids: Set<string>, stores: ShadowStores) {
  if (!ids.size) return
  stores.consultation.consultations = stores.consultation.consultations.filter((c) => !ids.has(c.id))
}
