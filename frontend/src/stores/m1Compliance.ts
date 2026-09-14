// 合规中心（M1 集团屏 /m1-compliance · B49 卡9 接真）
// 数据源：audit-service 合规域——检查项 GET /api/audit/compliance/checks
//   （compliance_check 表，种子栈 12 行六类×四态）；审计时间线
//   GET /api/audit/page?bizType=COMPLIANCE（audit_log 链式哈希，种子 6 条）。
// 写路径：复检 POST /checks/{id}/recheck（pass → PASS/FAIL，后端同事务写 RECHECK 审计）；
//   impersonate 开始/结束直调 POST /api/audit append（仅留痕真实化，
//   真实身份切换登记 backlog；ip 浏览器取不到，如实 "web"）。
// 写成功后局部替换检查项 + 重拉审计时间线。
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import {
  listChecks, recheckCheck, appendComplianceAudit, type ComplianceCheckDTO,
} from '@/api/compliance'
import { pageAuditLogs, type AuditLogRow } from '@/api/audit'
import { useToast } from '@/composables/useToast'
import { errMsg } from '@/stores/m5Coupon'

export type ComplianceStatus = 'PASS' | 'WARN' | 'FAIL' | 'PENDING'
export type CheckCategory = 'QUALIFICATION' | 'CONSENT' | 'DRUG_TRACE' | 'PRIVACY' | 'AD' | 'INFECTION'

export const STATUS_LABEL: Record<ComplianceStatus, string> = {
  PASS: '合规', WARN: '预警', FAIL: '不合规', PENDING: '待检',
}
export const CATEGORY_LABEL: Record<CheckCategory, string> = {
  QUALIFICATION: '资质证照', CONSENT: '知情同意', DRUG_TRACE: '药品溯源',
  PRIVACY: '隐私合规', AD: '医疗广告', INFECTION: '院感管理',
}

export interface CheckItem {
  id: number
  category: CheckCategory
  title: string
  requirement: string
  storeName: string
  status: ComplianceStatus
  lastCheckAt: string
  checker: string
  evidence?: string
  dueDate?: string
  remark?: string
}

export type AuditAction = 'IMPERSONATE_START' | 'IMPERSONATE_END' | 'EXPORT_DATA' | 'PERMISSION_CHANGE' | 'SENSITIVE_VIEW' | 'CONFIG_CHANGE' | 'RECHECK'
export const AUDIT_ACTION_LABEL: Record<AuditAction, string> = {
  IMPERSONATE_START: '开始代操作', IMPERSONATE_END: '结束代操作',
  EXPORT_DATA: '数据导出', PERMISSION_CHANGE: '权限变更',
  SENSITIVE_VIEW: '敏感信息查看', CONFIG_CHANGE: '配置变更',
  RECHECK: '合规复检',
}

export interface AuditLog {
  id: number
  at: string
  actor: string // 实际操作人（中文姓名，与后端 checker/actor 口径一致）
  action: AuditAction
  target?: string // 被 impersonate 的人 / 操作对象（payload.target）
  ip: string // payload.ip（种子为内网 IP；impersonate 直调如实 "web"）
  detail: string
  risk: 'LOW' | 'MEDIUM' | 'HIGH'
}

function toItem(d: ComplianceCheckDTO): CheckItem {
  return {
    id: d.id,
    category: d.category as CheckCategory,
    title: d.title,
    requirement: d.requirement,
    storeName: d.storeName,
    status: d.status as ComplianceStatus,
    lastCheckAt: d.lastCheckAt,
    checker: d.checker,
    evidence: d.evidence ?? undefined,
    dueDate: d.dueDate ?? undefined,
    remark: d.remark ?? undefined,
  }
}

/** audit_log 行 → 时间线条目：payload jsonb 解析四键（target/ip/detail/risk），缺省兜底。 */
function toAuditLog(row: AuditLogRow): AuditLog {
  let p: { target?: string; ip?: string; detail?: string; risk?: string } = {}
  try {
    p = JSON.parse(row.payload || '{}')
  } catch {
    p = {}
  }
  const risk: AuditLog['risk'] = p.risk === 'HIGH' || p.risk === 'MEDIUM' ? p.risk : 'LOW'
  return {
    id: row.id,
    at: row.createdAt,
    actor: row.actor,
    action: row.action as AuditAction,
    target: p.target,
    ip: p.ip || '—',
    detail: p.detail ?? '',
    risk,
  }
}

export const useM1ComplianceStore = defineStore('m1Compliance', () => {
  const toast = useToast()
  const items = ref<CheckItem[]>([])
  const auditLogs = ref<AuditLog[]>([])
  const seeded = ref(false)
  const loading = ref(false)

  // 当前 impersonate 会话（前端内存单会话，真实身份切换属 backlog）
  const activeSession = ref<{ target: string; startedAt: string; reason: string } | null>(null)

  const stats = computed(() => {
    const total = items.value.length
    return {
      total,
      pass: items.value.filter((i) => i.status === 'PASS').length,
      warn: items.value.filter((i) => i.status === 'WARN').length,
      fail: items.value.filter((i) => i.status === 'FAIL').length,
      pending: items.value.filter((i) => i.status === 'PENDING').length,
      passRate: total ? Math.round((items.value.filter((i) => i.status === 'PASS').length / total) * 100) : 0,
    }
  })

  function itemsByCategory(cat: CheckCategory) { return items.value.filter((i) => i.category === cat) }
  function categoryScore(cat: CheckCategory) {
    const list = itemsByCategory(cat)
    if (!list.length) return { rate: 0, fail: 0, total: 0 }
    const pass = list.filter((i) => i.status === 'PASS').length
    return { rate: Math.round((pass / list.length) * 100), fail: list.filter((i) => i.status === 'FAIL').length, total: list.length }
  }

  /** 审计时间线重拉（bizType=COMPLIANCE，按 id 倒序最新在前）。 */
  async function refreshAudits() {
    try {
      const resp = await pageAuditLogs({ bizType: 'COMPLIANCE', size: 50 })
      auditLogs.value = (resp.data?.items ?? []).map(toAuditLog)
    } catch (e) {
      toast.error(errMsg(e, '合规审计加载失败'))
    }
  }

  async function seed(force = false) {
    if (seeded.value && !force) return
    loading.value = true
    try {
      const [checksResp, auditsResp] = await Promise.all([
        listChecks(),
        pageAuditLogs({ bizType: 'COMPLIANCE', size: 50 }),
      ])
      items.value = (checksResp.data ?? []).map(toItem)
      auditLogs.value = (auditsResp.data?.items ?? []).map(toAuditLog)
      seeded.value = true
    } catch (e) {
      toast.error(errMsg(e, '合规数据加载失败'))
    } finally {
      loading.value = false
    }
  }

  // 复检：pass → PASS/FAIL（checker 由后端从登录上下文取，前端传参仅兼容契约）；
  // 后端同事务写 COMPLIANCE/RECHECK 审计，成功后局部替换检查项并重拉时间线。
  async function recheck(id: number, ok: boolean, _checker: string, remark?: string) {
    try {
      const resp = await recheckCheck(id, ok, remark)
      const updated = toItem(resp.data)
      const i = items.value.findIndex((x) => x.id === updated.id)
      if (i >= 0) items.value.splice(i, 1, updated)
      toast.success('复检结果已记录')
      void refreshAudits()
    } catch (e) {
      toast.error(errMsg(e, '复检提交失败'))
    }
  }

  // 超管代操作：必须填理由，全程留痕（审计 append 异步写，失败不阻断会话但 toast 提示）
  function startImpersonate(target: string, reason: string, actor: string): boolean {
    if (activeSession.value) return false
    if (!reason.trim()) return false
    activeSession.value = { target, startedAt: new Date().toISOString(), reason: reason.trim() }
    appendComplianceAudit(actor, 'IMPERSONATE_START', target,
      `以「${target}」身份开始代操作，理由：${reason.trim()}`, 'HIGH')
      .then(() => refreshAudits())
      .catch((e) => toast.error(errMsg(e, '代操作审计写入失败')))
    return true
  }
  function endImpersonate(actor: string) {
    if (!activeSession.value) return
    const dur = Math.round((Date.now() - new Date(activeSession.value.startedAt).getTime()) / 60000)
    const target = activeSession.value.target
    activeSession.value = null
    appendComplianceAudit(actor, 'IMPERSONATE_END', target, `结束代操作，会话时长 ${dur} 分钟`, 'MEDIUM')
      .then(() => refreshAudits())
      .catch((e) => toast.error(errMsg(e, '代操作审计写入失败')))
  }

  return {
    items, auditLogs, activeSession, STATUS_LABEL, CATEGORY_LABEL, AUDIT_ACTION_LABEL,
    stats, itemsByCategory, categoryScore, recheck, startImpersonate, endImpersonate, seed,
  }
})
