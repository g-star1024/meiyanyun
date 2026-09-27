// ============================================================
// Risk 黑名单/风控 store（M3-17 / M3-B6 切真）
// 数据源：customer-service /api/customer/m3/risk（risk_record 状态机
// 提交→待审核→拉黑/观察→解除 + risk_rule 开关）。
// 权限：risk:view / risk:edit / risk:approve；actor 服务端取。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { useToast } from '@/composables/useToast'
import { errMsg } from './m5Coupon'
import {
  listRiskRecords, listRiskRules, submitRiskRecord, approveRiskRecord,
  rejectRiskRecord, releaseRiskRecord, toggleRiskRule,
  type RiskRecordView, type RiskRuleView,
} from '@/api/risk'

export type RiskLevel = 'HIGH' | 'MEDIUM' | 'LOW'
export type RiskStatus = 'BLACKLISTED' | 'WATCHING' | 'RELEASED' | 'PENDING_REVIEW'
export type RiskReason = 'FRAUD' | 'CHARGEBACK' | 'MALICIOUS_COMPLAINT' | 'ILLEGAL_PRACTICE' | 'OTHER'

export interface RiskRecord {
  id: number
  riskNo: string
  customerId: string
  customerName: string
  phoneMask: string
  level: RiskLevel
  reason: RiskReason
  reasonDetail: string
  status: RiskStatus
  hitCount: number
  blockTransactions: boolean
  operator: string
  createdAt: string
  resolvedAt?: string
  resolvedBy?: string
  timeline: { action: string; by: string; at: string; comment?: string }[]
}

export interface RiskRule {
  id: number
  ruleNo: string
  name: string
  description: string
  enabled: boolean
  action: 'BLOCK' | 'WARN' | 'REVIEW'
  hitCount: number
}

const REASON_LABEL: Record<RiskReason, string> = {
  FRAUD: '疑似欺诈', CHARGEBACK: '恶意退单', MALICIOUS_COMPLAINT: '恶意投诉',
  ILLEGAL_PRACTICE: '违规医托', OTHER: '其他',
}
const LEVEL_PILL: Record<RiskLevel, 'danger' | 'warning' | 'info'> = { HIGH: 'danger', MEDIUM: 'warning', LOW: 'info' }
const LEVEL_LABEL: Record<RiskLevel, string> = { HIGH: '高风险', MEDIUM: '中风险', LOW: '低风险' }
const STATUS_LABEL: Record<RiskStatus, string> = {
  BLACKLISTED: '已拉黑', WATCHING: '观察中', RELEASED: '已解除', PENDING_REVIEW: '待审核',
}
const STATUS_PILL: Record<RiskStatus, 'danger' | 'warning' | 'success' | 'info'> = {
  BLACKLISTED: 'danger', WATCHING: 'warning', RELEASED: 'success', PENDING_REVIEW: 'info',
}

function mapRecord(v: RiskRecordView): RiskRecord {
  return {
    id: v.id,
    riskNo: v.riskNo ?? '',
    customerId: v.customerId ?? '',
    customerName: v.customerName ?? '',
    phoneMask: v.phoneMask ?? '',
    level: (v.level as RiskLevel) || 'LOW',
    reason: (v.reason as RiskReason) || 'OTHER',
    reasonDetail: v.reasonDetail ?? '',
    status: (v.status as RiskStatus) || 'PENDING_REVIEW',
    hitCount: v.hitCount ?? 0,
    blockTransactions: !!v.blockTransactions,
    operator: v.operator ?? '',
    createdAt: v.createdAt ?? '',
    resolvedAt: v.resolvedAt ?? undefined,
    resolvedBy: v.resolvedBy ?? undefined,
    timeline: (v.timeline ?? []).map((t) => ({
      action: t.action ?? '', by: t.by ?? '', at: t.at ?? '', comment: t.comment ?? undefined,
    })),
  }
}

function mapRule(v: RiskRuleView): RiskRule {
  return {
    id: v.id,
    ruleNo: v.ruleNo ?? '',
    name: v.name ?? '',
    description: v.description ?? '',
    enabled: !!v.enabled,
    action: (v.action as RiskRule['action']) || 'WARN',
    hitCount: v.hitCount ?? 0,
  }
}

export const useRiskStore = defineStore('risk', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()
  const toast = useToast()

  const records = ref<RiskRecord[]>([])
  const rules = ref<RiskRule[]>([])

  const blacklisted = computed(() => records.value.filter((r) => r.status === 'BLACKLISTED'))
  const pending = computed(() => records.value.filter((r) => r.status === 'PENDING_REVIEW'))
  const watching = computed(() => records.value.filter((r) => r.status === 'WATCHING'))
  const highRisk = computed(() => records.value.filter((r) => r.level === 'HIGH' && r.status !== 'RELEASED'))
  const enabledRules = computed(() => rules.value.filter((r) => r.enabled))

  const filterStatus = ref<'ALL' | RiskStatus>('ALL')
  const filterLevel = ref<'ALL' | RiskLevel>('ALL')
  const filtered = computed(() => records.value
    .filter((r) => filterStatus.value === 'ALL' || r.status === filterStatus.value)
    .filter((r) => filterLevel.value === 'ALL' || r.level === filterLevel.value)
    .sort((a, b) => b.hitCount - a.hitCount))

  function get(id: number) { return records.value.find((r) => r.id === id) }

  function replaceRecord(next: RiskRecord) {
    const i = records.value.findIndex((r) => r.id === next.id)
    if (i >= 0) records.value.splice(i, 1, next)
    else records.value.unshift(next)
  }

  async function addToBlacklist(customerName: string, phoneMask: string, level: RiskLevel, reason: RiskReason, detail: string): Promise<RiskRecord | null> {
    if (!auth.can('risk:edit')) { console.warn('[risk] 无 risk:edit'); return null }
    try {
      const r = mapRecord(await submitRiskRecord({ customerName, phoneMask, level, reason, detail }))
      records.value.unshift(r)
      activity.log(auth.user.name, `提交黑名单审核：${customerName}`, r.riskNo)
      toast.success(`已提交审核：${r.riskNo}`)
      return r
    } catch (e) {
      toast.error(errMsg(e, '提交失败'))
      return null
    }
  }

  async function approve(id: number): Promise<boolean> {
    if (!auth.can('risk:approve')) { console.warn('[risk] 无 risk:approve'); return false }
    try {
      const r = mapRecord(await approveRiskRecord(id))
      replaceRecord(r)
      activity.log(auth.user.name, `黑名单审核通过：${r.customerName}，已拦截交易`, r.riskNo)
      toast.success(`审核通过：${r.customerName} 已拉黑`)
      return true
    } catch (e) {
      toast.error(errMsg(e, '审核失败'))
      return false
    }
  }

  async function reject(id: number, reason: string): Promise<boolean> {
    if (!auth.can('risk:approve')) return false
    try {
      const r = mapRecord(await rejectRiskRecord(id, reason))
      replaceRecord(r)
      toast.success(`已驳回转观察：${r.customerName}`)
      return true
    } catch (e) {
      toast.error(errMsg(e, '驳回失败'))
      return false
    }
  }

  async function release(id: number, reason: string): Promise<boolean> {
    if (!auth.can('risk:edit')) return false
    try {
      const r = mapRecord(await releaseRiskRecord(id, reason))
      replaceRecord(r)
      activity.log(auth.user.name, `解除黑名单：${r.customerName}`, r.riskNo)
      toast.success(`已解除风险：${r.customerName}`)
      return true
    } catch (e) {
      toast.error(errMsg(e, '解除失败'))
      return false
    }
  }

  async function toggleRule(id: number): Promise<void> {
    if (!auth.can('risk:edit')) return
    try {
      const next = mapRule(await toggleRiskRule(id))
      const i = rules.value.findIndex((x) => x.id === next.id)
      if (i >= 0) rules.value.splice(i, 1, next)
    } catch (e) {
      toast.error(errMsg(e, '规则切换失败'))
    }
  }

  // ===== 加载（切真：customer /api/customer/m3/risk；seeded 门闩防重入） =====
  let seeded = false
  async function load() {
    if (seeded) return
    seeded = true
    try {
      const [rec, rul] = await Promise.all([listRiskRecords(), listRiskRules()])
      records.value = rec.map(mapRecord)
      rules.value = rul.map(mapRule)
    } catch (e) {
      seeded = false
      toast.error(errMsg(e, '风控数据加载失败'))
    }
  }

  return {
    records, rules, filtered, blacklisted, pending, watching, highRisk, enabledRules,
    filterStatus, filterLevel, get, addToBlacklist, approve, reject, release, toggleRule, load,
    REASON_LABEL, LEVEL_PILL, LEVEL_LABEL, STATUS_LABEL, STATUS_PILL,
  }
})
