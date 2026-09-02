// ============================================================
// Risk 黑名单/风控 store（M3-17）
// 黑/风险名单、风控规则、拉黑解黑审批、命中拦截交易。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

export type RiskLevel = 'HIGH' | 'MEDIUM' | 'LOW'
export type RiskStatus = 'BLACKLISTED' | 'WATCHING' | 'RELEASED' | 'PENDING_REVIEW'
export type RiskReason = 'FRAUD' | 'CHARGEBACK' | 'MALICIOUS_COMPLAINT' | 'ILLEGAL_PRACTICE' | 'OTHER'

export interface RiskRecord {
  id: string
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
  id: string
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

const hoursAgo = (h: number) => new Date(Date.now() - h * 3600_000).toISOString()
const daysAgo = (d: number) => new Date(Date.now() - d * 86400_000).toISOString()

export const useRiskStore = defineStore('risk', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const records = ref<RiskRecord[]>([
    {
      id: 'R-001', riskNo: 'RK20260820001', customerId: 'C-301', customerName: '赵某某', phoneMask: '135****0011',
      level: 'HIGH', reason: 'FRAUD', reasonDetail: '冒用他人会员卡到店核销，经核实身份不符',
      status: 'BLACKLISTED', hitCount: 3, blockTransactions: true, operator: '陈雅琳（店长）',
      createdAt: daysAgo(5),
      timeline: [
        { action: '命中风控规则', by: '系统', at: daysAgo(6), comment: '非本人核销累计 2 次' },
        { action: '加入黑名单', by: '陈雅琳（店长）', at: daysAgo(5), comment: '核实身份不符，拉黑拦截交易' },
      ],
    },
    {
      id: 'R-002', riskNo: 'RK20260822002', customerId: 'C-302', customerName: '钱某', phoneMask: '136****0022',
      level: 'HIGH', reason: 'CHARGEBACK', reasonDetail: '近 30 天恶意退单 4 笔，涉及金额 ¥6,800',
      status: 'PENDING_REVIEW', hitCount: 4, blockTransactions: false, operator: '系统自动',
      createdAt: hoursAgo(20),
      timeline: [{ action: '系统检测到异常退单', by: '系统', at: hoursAgo(20), comment: '30 天内退单率 80%' }],
    },
    {
      id: 'R-003', riskNo: 'RK20260818003', customerId: 'C-303', customerName: '孙某', phoneMask: '137****0033',
      level: 'MEDIUM', reason: 'MALICIOUS_COMPLAINT', reasonDetail: '多次无依据投诉并要求超额赔偿',
      status: 'WATCHING', hitCount: 2, blockTransactions: false, operator: '林微（咨询师）',
      createdAt: daysAgo(7),
      timeline: [{ action: '加入观察名单', by: '林微（咨询师）', at: daysAgo(7) }],
    },
    {
      id: 'R-004', riskNo: 'RK20260810004', customerId: 'C-304', customerName: '周某', phoneMask: '138****0044',
      level: 'LOW', reason: 'ILLEGAL_PRACTICE', reasonDetail: '疑似医托行为，引导客户至外院',
      status: 'RELEASED', hitCount: 1, blockTransactions: false, operator: '张磊（区域经理）',
      createdAt: daysAgo(15), resolvedAt: daysAgo(10), resolvedBy: '张磊（区域经理）',
      timeline: [
        { action: '加入观察', by: '张磊（区域经理）', at: daysAgo(15) },
        { action: '解除风险', by: '张磊（区域经理）', at: daysAgo(10), comment: '证据不足，解除观察' },
      ],
    },
    {
      id: 'R-005', riskNo: 'RK20260824005', customerId: 'C-305', customerName: '吴某', phoneMask: '139****0055',
      level: 'MEDIUM', reason: 'OTHER', reasonDetail: '短期内多次预约未到店，爽约率 90%',
      status: 'WATCHING', hitCount: 5, blockTransactions: false, operator: '系统自动',
      createdAt: daysAgo(1),
      timeline: [{ action: '爽约率超阈值', by: '系统', at: daysAgo(1) }],
    },
    {
      id: 'R-006', riskNo: 'RK20260805006', customerId: 'C-306', customerName: '郑某', phoneMask: '133****0066',
      level: 'HIGH', reason: 'FRAUD', reasonDetail: '使用伪造优惠券被识别',
      status: 'BLACKLISTED', hitCount: 1, blockTransactions: true, operator: '陈雅琳（店长）',
      createdAt: daysAgo(20),
      timeline: [{ action: '加入黑名单', by: '陈雅琳（店长）', at: daysAgo(20), comment: '伪造优惠券，拦截交易' }],
    },
  ])

  const rules = ref<RiskRule[]>([
    { id: 'RR-1', name: '非本人会员卡核销', description: '同一会员卡被不同身份人员核销达 2 次即预警', enabled: true, action: 'REVIEW', hitCount: 12 },
    { id: 'RR-2', name: '高频恶意退单', description: '30 天内退单 ≥3 笔且退单率 >50% 自动拉黑待审', enabled: true, action: 'BLOCK', hitCount: 4 },
    { id: 'RR-3', name: '伪造优惠券/兑换码', description: '核销时校验失败累计 1 次即拦截', enabled: true, action: 'BLOCK', hitCount: 2 },
    { id: 'RR-4', name: '高爽约率', description: '近 90 天爽约率 >70% 加入观察名单', enabled: true, action: 'WARN', hitCount: 8 },
    { id: 'RR-5', name: '医托导流识别', description: '同一联系方式关联多家外院导流行为', enabled: false, action: 'REVIEW', hitCount: 0 },
  ])

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

  function get(id: string) { return records.value.find((r) => r.id === id) }

  function addToBlacklist(customerName: string, phoneMask: string, level: RiskLevel, reason: RiskReason, detail: string) {
    if (!auth.can('risk:edit')) { console.warn('[risk] 无 risk:edit'); return null }
    const r: RiskRecord = {
      id: nextId('R'), riskNo: `RK${Date.now().toString().slice(-10)}`,
      customerId: nextId('C'), customerName, phoneMask, level, reason, reasonDetail: detail,
      status: 'PENDING_REVIEW', hitCount: 1, blockTransactions: level === 'HIGH',
      operator: auth.user.name, createdAt: new Date().toISOString(),
      timeline: [{ action: '提交拉黑审核', by: auth.user.name, at: new Date().toISOString(), comment: detail }],
    }
    records.value.unshift(r)
    activity.log(auth.user.name, `提交黑名单审核：${customerName}`, r.id)
    return r
  }

  function approve(id: string) {
    const r = get(id)
    if (!r || r.status !== 'PENDING_REVIEW') return false
    if (!auth.can('risk:approve')) { console.warn('[risk] 无 risk:approve'); return false }
    r.status = 'BLACKLISTED'
    r.blockTransactions = true
    r.resolvedAt = new Date().toISOString()
    r.resolvedBy = auth.user.name
    r.timeline.push({ action: '审核通过，加入黑名单', by: auth.user.name, at: new Date().toISOString() })
    activity.log(auth.user.name, `黑名单审核通过：${r.customerName}，已拦截交易`, r.id)
    return true
  }

  function reject(id: string, reason: string) {
    const r = get(id)
    if (!r || r.status !== 'PENDING_REVIEW') return false
    if (!auth.can('risk:approve')) return false
    r.status = 'WATCHING'
    r.blockTransactions = false
    r.timeline.push({ action: '审核驳回，转观察', by: auth.user.name, at: new Date().toISOString(), comment: reason })
    return true
  }

  function release(id: string, reason: string) {
    const r = get(id)
    if (!r || (r.status !== 'BLACKLISTED' && r.status !== 'WATCHING')) return false
    if (!auth.can('risk:edit')) return false
    r.status = 'RELEASED'
    r.blockTransactions = false
    r.resolvedAt = new Date().toISOString()
    r.resolvedBy = auth.user.name
    r.timeline.push({ action: '解除风险', by: auth.user.name, at: new Date().toISOString(), comment: reason })
    activity.log(auth.user.name, `解除黑名单：${r.customerName}`, r.id)
    return true
  }

  function toggleRule(id: string) {
    const r = rules.value.find((x) => x.id === id)
    if (r && auth.can('risk:edit')) r.enabled = !r.enabled
  }

  return {
    records, rules, filtered, blacklisted, pending, watching, highRisk, enabledRules,
    filterStatus, filterLevel, get, addToBlacklist, approve, reject, release, toggleRule,
    REASON_LABEL, LEVEL_PILL, LEVEL_LABEL, STATUS_LABEL, STATUS_PILL,
  }
})
