import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

// ============================================================
// 合规中心 store（M1 集团管控 / 合规中心）
// - ComplianceItem 合规检查项：资质证照 / 知情同意书 / 药品溯源 / 隐私合规 / 医疗广告 / 院感
//   状态：PASS 通过 / WARN 预警 / FAIL 不合规 / PENDING 待检
// - 按门店维度产生检查记录；FAIL 项必须整改并复检
// - ImpersonationAudit 超管代操作（impersonate）审计：超管以他人身份操作时强制留痕
//   任何 impersonate 会话的开始/结束/关键操作都写一条不可删除的审计记录
// ============================================================

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
  id: string
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

export type AuditAction = 'IMPERSONATE_START' | 'IMPERSONATE_END' | 'EXPORT_DATA' | 'PERMISSION_CHANGE' | 'SENSITIVE_VIEW' | 'CONFIG_CHANGE'
export const AUDIT_ACTION_LABEL: Record<AuditAction, string> = {
  IMPERSONATE_START: '开始代操作', IMPERSONATE_END: '结束代操作',
  EXPORT_DATA: '数据导出', PERMISSION_CHANGE: '权限变更',
  SENSITIVE_VIEW: '敏感信息查看', CONFIG_CHANGE: '配置变更',
}

export interface AuditLog {
  id: string
  at: string
  actor: string // 实际操作人（超管）
  action: AuditAction
  target?: string // 被 impersonate 的人 / 操作对象
  ip: string
  detail: string
  risk: 'LOW' | 'MEDIUM' | 'HIGH'
}

let _cid = 0
function cid(p: string) { _cid += 1; return `${p}-${Date.now().toString(36)}-${_cid}` }
function now() { return new Date().toISOString() }
function hoursAgo(h: number) { return new Date(Date.now() - h * 3600000).toISOString() }

export const useM1ComplianceStore = defineStore('m1Compliance', () => {
  const items = ref<CheckItem[]>([])
  const auditLogs = ref<AuditLog[]>([])
  const seeded = ref(false)

  // 当前 impersonate 会话
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

  // 整改：FAIL/WARN → PENDING（待复检）；复检通过 → PASS
  function recheck(id: string, ok: boolean, checker: string, remark?: string) {
    const it = items.value.find((i) => i.id === id)
    if (!it) return
    it.status = ok ? 'PASS' : 'FAIL'
    it.lastCheckAt = now()
    it.checker = checker
    if (remark) it.remark = remark
  }

  function addAudit(log: Omit<AuditLog, 'id' | 'at' | 'ip'> & { ip?: string }) {
    auditLogs.value.unshift({
      ...log, id: cid('aud'), at: now(),
      ip: log.ip ?? '10.12.' + (20 + Math.floor(Math.random() * 80)) + '.' + (Math.floor(Math.random() * 200) + 2),
    })
  }

  // 超管代操作：必须填理由，全程留痕
  function startImpersonate(target: string, reason: string, actor: string): boolean {
    if (activeSession.value) return false
    if (!reason.trim()) return false
    activeSession.value = { target, startedAt: now(), reason: reason.trim() }
    addAudit({ actor, action: 'IMPERSONATE_START', target, detail: `以「${target}」身份开始代操作，理由：${reason.trim()}`, risk: 'HIGH' })
    return true
  }
  function endImpersonate(actor: string) {
    if (!activeSession.value) return
    const dur = Math.round((Date.now() - new Date(activeSession.value.startedAt).getTime()) / 60000)
    addAudit({ actor, action: 'IMPERSONATE_END', target: activeSession.value.target, detail: `结束代操作，会话时长 ${dur} 分钟`, risk: 'MEDIUM' })
    activeSession.value = null
  }

  function seed() {
    if (seeded.value) return
    const mk = (i: Omit<CheckItem, 'id'>): CheckItem => ({ ...i, id: cid('chk') })
    items.value = [
      mk({ category: 'QUALIFICATION', title: '医疗机构执业许可证', requirement: '证照在有效期内且悬挂公示', storeName: '静安旗舰店', status: 'PASS', lastCheckAt: hoursAgo(72), checker: '陈野', evidence: '证照编号 PDY10034-...', dueDate: '2027-03-15' }),
      mk({ category: 'QUALIFICATION', title: '医师执业证书', requirement: '在岗医师均持有效执业证', storeName: '静安旗舰店', status: 'PASS', lastCheckAt: hoursAgo(48), checker: '陈野', evidence: '顾屿 110310...' }),
      mk({ category: 'QUALIFICATION', title: '医师执业证书', requirement: '在岗医师均持有效执业证', storeName: '浦东诊所', status: 'WARN', lastCheckAt: hoursAgo(96), checker: '陈野', remark: '新入职医师证件正在多点执业备案，限期7天', dueDate: '2026-09-01' }),
      mk({ category: 'CONSENT', title: '术前知情同意书签署', requirement: '所有侵入性项目100%签署纸质+电子同意书', storeName: '静安旗舰店', status: 'PASS', lastCheckAt: hoursAgo(24), checker: '苏晴', evidence: '本月签署率 100%（236/236）' }),
      mk({ category: 'CONSENT', title: '术前知情同意书签署', requirement: '所有侵入性项目100%签署纸质+电子同意书', storeName: '徐汇社区店', status: 'FAIL', lastCheckAt: hoursAgo(12), checker: '苏晴', remark: '发现 3 例热玛吉仅有电子签未留存纸质，要求3日内补签', dueDate: '2026-08-28' }),
      mk({ category: 'DRUG_TRACE', title: '注射类药品溯源', requirement: '肉毒/玻尿酸一物一码、全程可追溯', storeName: '静安旗舰店', status: 'PASS', lastCheckAt: hoursAgo(36), checker: '钱进', evidence: '本月扫码溯源 412 支，0 异常' }),
      mk({ category: 'DRUG_TRACE', title: '注射类药品溯源', requirement: '肉毒/玻尿酸一物一码、全程可追溯', storeName: '浦东诊所', status: 'WARN', lastCheckAt: hoursAgo(60), checker: '钱进', remark: '保妥适 1 批次温控记录缺失 2 小时，已上报供应商' }),
      mk({ category: 'PRIVACY', title: '客户敏感信息访问审计', requirement: '手机号/身份证脱敏，访问需授权与留痕', storeName: '全集团', status: 'PASS', lastCheckAt: hoursAgo(6), checker: '周岚', evidence: '本周敏感字段解密 18 次，均有授权' }),
      mk({ category: 'AD', title: '对外宣传素材合规', requirement: '医疗广告经审查、无绝对化用语/案例对比', storeName: '静安旗舰店', status: 'PASS', lastCheckAt: hoursAgo(120), checker: '白桥', evidence: '在投素材 24 份，均经法务审查' }),
      mk({ category: 'AD', title: '对外宣传素材合规', requirement: '医疗广告经审查、无绝对化用语/案例对比', storeName: '徐汇社区店', status: 'PENDING', lastCheckAt: hoursAgo(240), checker: '白桥', remark: '新增抖音投流素材 3 份待审' }),
      mk({ category: 'INFECTION', title: '院感消毒记录', requirement: '治疗室每4小时消毒并记录、医疗器械高压灭菌', storeName: '静安旗舰店', status: 'PASS', lastCheckAt: hoursAgo(8), checker: '苏晴', evidence: '消毒记录完整，物表抽检合格' }),
      mk({ category: 'INFECTION', title: '院感消毒记录', requirement: '治疗室每4小时消毒并记录、医疗器械高压灭菌', storeName: '浦东诊所', status: 'FAIL', lastCheckAt: hoursAgo(4), checker: '苏晴', remark: '8月24日下午消毒记录漏登，灭菌指示卡留存不全', dueDate: '2026-08-26' }),
    ]

    auditLogs.value = [
      { id: cid('aud'), at: hoursAgo(2), actor: '周岚', action: 'SENSITIVE_VIEW', target: '客户 138****6677 病历', ip: '10.12.21.45', detail: '导出客户病历用于医疗纠纷举证，已获客户书面授权', risk: 'MEDIUM' },
      { id: cid('aud'), at: hoursAgo(26), actor: '周岚', action: 'IMPERSONATE_START', target: '苏晴（静安店长）', ip: '10.12.21.45', detail: '以店长身份排查退款审批异常，理由：门店反馈审批按钮无响应（工单#T20260824）', risk: 'HIGH' },
      { id: cid('aud'), at: hoursAgo(25), actor: '周岚', action: 'PERMISSION_CHANGE', target: '角色「皮肤科护士」', ip: '10.12.21.45', detail: '新增字段权限：emr.treatment 只读', risk: 'MEDIUM' },
      { id: cid('aud'), at: hoursAgo(25), actor: '周岚', action: 'IMPERSONATE_END', target: '苏晴（静安店长）', ip: '10.12.21.45', detail: '结束代操作，会话时长 47 分钟', risk: 'MEDIUM' },
      { id: cid('aud'), at: hoursAgo(50), actor: '陈野', action: 'EXPORT_DATA', target: '华东大区经营数据', ip: '10.12.34.12', detail: '导出 7 月经营报表 Excel，含成本字段', risk: 'LOW' },
      { id: cid('aud'), at: hoursAgo(72), actor: '周岚', action: 'CONFIG_CHANGE', target: '系统设置·双签阈值', ip: '10.12.21.45', detail: 'L2 审批阈值由 50000 调整为 30000', risk: 'HIGH' },
    ]
    seeded.value = true
  }

  return {
    items, auditLogs, activeSession, STATUS_LABEL, CATEGORY_LABEL, AUDIT_ACTION_LABEL,
    stats, itemsByCategory, categoryScore, recheck, addAudit, startImpersonate, endImpersonate, seed,
  }
})
