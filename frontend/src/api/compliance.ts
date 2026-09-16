// ============================================================
// 合规中心 API（对接 audit-service 合规域 · B49 卡9；B55 impersonate 改走 org 端点）
// 读：GET /api/audit/compliance/checks（category/status 可选过滤，按 id 升序）
// 写：POST /api/audit/compliance/checks/{id}/recheck（{pass 必填, remark?}，
//   同事务写 COMPLIANCE/RECHECK 审计）
// 超管代操作 IMPERSONATE_START/END 由 org-service /auth/impersonate(/exit)
//   端点权威留痕，前端不再直调 POST /api/audit。
// ============================================================
import client from './client'

/** 合规检查项行（Jackson camelCase 直出）。 */
export interface ComplianceCheckDTO {
  id: number
  /** QUALIFICATION 资质证照 / CONSENT 知情同意 / DRUG_TRACE 药品溯源 /
   *  PRIVACY 隐私合规 / AD 医疗广告 / INFECTION 院感管理 */
  category: string
  title: string
  requirement: string
  storeName: string
  /** PASS 合规 / WARN 预警 / FAIL 不合规 / PENDING 待检 */
  status: string
  lastCheckAt: string
  checker: string
  evidence?: string | null
  dueDate?: string | null
  remark?: string | null
}

export interface ComplianceCheckFilter {
  category?: string
  status?: string
}

export const listChecks = (f: ComplianceCheckFilter = {}) =>
  client.get<ComplianceCheckDTO[]>('/audit/compliance/checks', { params: f })

/** 复检：pass=true → PASS，false → FAIL；remark 非空覆盖。 */
export const recheckCheck = (id: number, pass: boolean, remark?: string) =>
  client.post<ComplianceCheckDTO>(`/audit/compliance/checks/${id}/recheck`, { pass, remark })
