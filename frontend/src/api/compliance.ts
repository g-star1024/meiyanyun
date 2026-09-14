// ============================================================
// 合规中心 API（对接 audit-service 合规域 · B49 卡9）
// 读：GET /api/audit/compliance/checks（category/status 可选过滤，按 id 升序）
// 写：POST /api/audit/compliance/checks/{id}/recheck（{pass 必填, remark?}，
//   同事务写 COMPLIANCE/RECHECK 审计）
// 审计：POST /api/audit（impersonate 开始/结束直调 append 留痕，bizType=COMPLIANCE，
//   ip/risk/target 落 payload jsonb 四键；ip 浏览器取不到真实值，如实写 "web"）
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

/** 合规审计追加（impersonate 留痕专用）：payload 四键 target/ip/detail/risk。 */
export const appendComplianceAudit = (
  actor: string, action: string, target: string, detail: string, risk: string,
) =>
  client.post('/audit', {
    bizType: 'COMPLIANCE',
    txnNo: null,
    actor,
    action,
    payload: JSON.stringify({ target, ip: 'web', detail, risk }),
  })
