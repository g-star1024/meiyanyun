// ============================================================
// finCommission —— M6-08 薪酬提成（B9 后端化）
// 业财一体红线：提成仅基于「已双签划扣确认收入」镜像试算与审批，
// 提成发放走外部薪酬系统，本 store 只登记提成单、勾稽 finance 侧业绩基数，
// 绝不直接动账。
// 数据全部来自 finance-service（/finance/commission*、/finance/comp-configs、
// /finance/commission-rules）；适配层负责 分↔元、万分位↔小数、yyyy-MM-01↔yyyy-MM 换算。
// 写操作的权限/校验/审计由后端四件套兜底，失败抛错由调用方 toast。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import {
  listCommission, listCompConfigs, listCommissionRules,
  generateCommission as apiGenerate, submitCommission as apiSubmit,
  approveCommission as apiApprove, rejectCommission as apiReject,
  markCommissionPaid as apiMarkPaid,
  type CommissionRuleDTO, type CommissionRecordDTO, type StaffCompConfigDTO, type RuleTierDTO,
} from '@/api/commission'

export type CommissionStatus = 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'PAID' | 'REJECTED'
export type CommissionBase = 'ORDER' | 'WRITEOFF' | 'RECHARGE'
export type CommRole = 'CONSULTANT' | 'DOCTOR' | 'BEAUTICIAN'

export interface CommissionRule {
  id: string
  name: string
  base: CommissionBase
  /** 阶梯：业绩下限（元）~ 比例（0~1 小数） */
  tiers: { min: number; rate: number; label: string }[]
  /** 适用岗位 */
  role: CommRole | 'ALL'
  active: boolean
}

export interface CommissionItem {
  id: string
  period: string            // yyyy-MM 归属期间
  consultantId: string
  consultantName: string
  title: string
  ruleId: string | null
  ruleName: string
  /** 业绩口径（取自规则 base；无规则回退 WRITEOFF） */
  base: CommissionBase
  /** 业绩基数（元，来自镜像口径） */
  baseAmount: number
  /** 阶梯明细（金额元、rate 小数） */
  tiers: { label: string; amount: number; rate: number; commission: number }[]
  commission: number        // 提成合计（元）
  status: CommissionStatus
  orderCount: number
  approver?: string
  approvedAt?: string | null
  paidAt?: string | null
  remark?: string | null
}

/** 员工薪酬配置（底薪+适用规则），金额「元」、月份 yyyy-MM */
export interface StaffCompConfig {
  compId: string
  staffId: string
  staffName: string
  storeCode: string | null
  baseSalary: number
  commissionRuleId: string | null
  effectiveMonth: string
  status: 'ACTIVE' | 'INACTIVE'
}

export const STATUS_LABEL: Record<CommissionStatus, string> = {
  DRAFT: '待提交',
  SUBMITTED: '待审批',
  APPROVED: '已审批待发放',
  PAID: '已发放',
  REJECTED: '已驳回',
}
export const STATUS_PILL: Record<CommissionStatus, 'warning' | 'primary' | 'info' | 'success' | 'danger'> = {
  DRAFT: 'warning',
  SUBMITTED: 'primary',
  APPROVED: 'info',
  PAID: 'success',
  REJECTED: 'danger',
}
export const BASE_LABEL: Record<CommissionBase, string> = {
  ORDER: '成交额',
  WRITEOFF: '划扣确认收入',
  RECHARGE: '充值额',
}

const ROLE_TITLE: Record<string, string> = {
  CONSULTANT: '咨询师',
  DOCTOR: '主诊医生',
  BEAUTICIAN: '美疗师',
  ALL: '通用岗位',
}

/** 分 → 元（两位小数） */
function fenToYuan(fen: number | null | undefined): number {
  return Math.round((fen ?? 0) / 100 * 100) / 100
}
/** 元 → 分（四舍五入） */
export function yuanToFen(yuan: number): number {
  return Math.round(yuan * 100)
}
/** 万分位 → 小数（600 → 0.06） */
function bpToRate(bp: number | null | undefined): number {
  return (bp ?? 0) / 10000
}
/** 小数 → 万分位（0.06 → 600） */
export function rateToBp(rate: number): number {
  return Math.round(rate * 10000)
}

/** 解析规则 tiersJson（分/万分位 → 元/小数） */
function parseRuleTiers(dto: CommissionRuleDTO): CommissionRule['tiers'] {
  try {
    const raw = JSON.parse(dto.tiersJson || '[]') as Partial<RuleTierDTO>[]
    return raw.map((t) => ({
      min: fenToYuan(t.min ?? 0),
      rate: bpToRate(t.rate ?? 0),
      label: t.label || `${fenToYuan(t.min ?? 0)} 元以上 ${(bpToRate(t.rate ?? 0) * 100).toFixed(1)}%`,
    }))
  } catch {
    return []
  }
}

/** 近 N 个月的 yyyy-MM-01 列表（当月在前） */
function recentMonths(count: number): string[] {
  const out: string[] = []
  const now = new Date()
  for (let i = 0; i < count; i++) {
    const d = new Date(now.getFullYear(), now.getMonth() - i, 1)
    out.push(`${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-01`)
  }
  return out
}

export const useFinCommissionStore = defineStore('finCommission', () => {
  const rules = ref<CommissionRule[]>([])
  const compConfigs = ref<StaffCompConfig[]>([])
  const items = ref<CommissionItem[]>([])
  const filterStatus = ref<CommissionStatus | 'ALL'>('ALL')
  const filterPeriod = ref<string>('ALL')
  const loading = ref(false)

  function ruleOf(id: string | null | undefined): CommissionRule | undefined {
    if (!id) return undefined
    return rules.value.find((r) => r.id === id)
  }

  /** DTO → 页面模型（规则须先装载以解析 title/base；未匹配规则时诚实回退） */
  function toItem(dto: CommissionRecordDTO): CommissionItem {
    const rule = ruleOf(dto.ruleId)
    let tiers: CommissionItem['tiers'] = []
    try {
      const raw = JSON.parse(dto.tiersJson || '[]') as Array<{
        label?: string; min?: number; amount?: number; rate?: number; commission?: number
      }>
      tiers = raw.map((t) => ({
        label: t.label || '提成档位',
        amount: fenToYuan(t.amount ?? 0),
        rate: bpToRate(t.rate ?? 0),
        commission: fenToYuan(t.commission ?? 0),
      }))
    } catch {
      tiers = []
    }
    return {
      id: dto.recordId,
      period: dto.period ? dto.period.slice(0, 7) : '',
      consultantId: dto.staffId,
      consultantName: dto.staffName,
      title: rule ? ROLE_TITLE[rule.role] ?? '提成人员' : '提成人员',
      ruleId: dto.ruleId,
      ruleName: dto.ruleName || rule?.name || '未绑定规则',
      base: rule?.base ?? 'WRITEOFF',
      baseAmount: fenToYuan(dto.baseAmount),
      tiers,
      commission: fenToYuan(dto.commission),
      status: dto.status,
      orderCount: dto.orderCount ?? 0,
      approver: dto.approver ?? undefined,
      approvedAt: dto.approvedAt,
      paidAt: dto.paidAt,
      remark: dto.remark,
    }
  }

  const totalCommission = computed(() => items.value.reduce((s, i) => s + i.commission, 0))
  const approvedCommission = computed(() => items.value.filter((i) => i.status === 'APPROVED' || i.status === 'PAID').reduce((s, i) => s + i.commission, 0))
  const pendingCommission = computed(() => items.value.filter((i) => i.status === 'SUBMITTED').reduce((s, i) => s + i.commission, 0))
  const paidCommission = computed(() => items.value.filter((i) => i.status === 'PAID').reduce((s, i) => s + i.commission, 0))

  const periods = computed(() => {
    const set = new Set(items.value.map((i) => i.period).filter(Boolean))
    return [...set].sort().reverse()
  })

  const filtered = computed(() => {
    let list = items.value
    if (filterStatus.value !== 'ALL') list = list.filter((i) => i.status === filterStatus.value)
    if (filterPeriod.value !== 'ALL') list = list.filter((i) => i.period === filterPeriod.value)
    return [...list].sort((a, b) => (a.period < b.period ? 1 : a.consultantName.localeCompare(b.consultantName)))
  })

  function get(id: string) {
    return items.value.find((i) => i.id === id)
  }

  function activeRule(role: CommRole) {
    return rules.value.find((r) => r.active && (r.role === role || r.role === 'ALL'))
  }

  /** 按阶梯试算（金额元、rate 小数；与后端超额累进语义一致：amount > 档下限 才入档） */
  function calcTiers(rule: CommissionRule, amount: number) {
    const tiers: { label: string; amount: number; rate: number; commission: number }[] = []
    for (let i = 0; i < rule.tiers.length; i++) {
      const cur = rule.tiers[i]
      const next = rule.tiers[i + 1]
      const upper = next ? next.min : Infinity
      if (amount > cur.min) {
        const seg = Math.min(amount, upper) - cur.min
        tiers.push({ label: cur.label, amount: seg, rate: cur.rate, commission: Math.round(seg * cur.rate * 100) / 100 })
      }
    }
    return tiers
  }

  /** 装载：规则 + 薪酬配置 + 近三个月提成单（演示单归属当月）；失败抛错由调用方 toast */
  async function seed() {
    loading.value = true
    try {
      const months = recentMonths(3)
      const [rulesRes, compsRes, ...recordsRes] = await Promise.all([
        listCommissionRules(),
        listCompConfigs(),
        ...months.map((m) => listCommission(m)),
      ])
      rules.value = rulesRes.data.map((dto: CommissionRuleDTO): CommissionRule => ({
        id: dto.ruleId,
        name: dto.ruleName,
        base: (dto.base as CommissionBase) || 'WRITEOFF',
        role: (dto.role as CommissionRule['role']) || 'ALL',
        tiers: parseRuleTiers(dto),
        active: dto.active,
      }))
      compConfigs.value = compsRes.data.map((dto: StaffCompConfigDTO): StaffCompConfig => ({
        compId: dto.compId,
        staffId: dto.staffId,
        staffName: dto.staffName,
        storeCode: dto.storeCode,
        baseSalary: fenToYuan(dto.baseSalary),
        commissionRuleId: dto.commissionRuleId,
        effectiveMonth: dto.effectiveMonth ? dto.effectiveMonth.slice(0, 7) : '',
        status: dto.status,
      }))
      const merged = new Map<string, CommissionRecordDTO>()
      recordsRes.forEach((res) => {
        res.data.forEach((r) => merged.set(r.recordId, r))
      })
      items.value = [...merged.values()].map(toItem)
      if (filterPeriod.value !== 'ALL' && !periods.value.includes(filterPeriod.value)) {
        filterPeriod.value = 'ALL'
      }
    } finally {
      loading.value = false
    }
  }

  /** 按月生成/重算试算单（period=yyyy-MM-01；PAID 锁定不重算，后端幂等） */
  async function generate(period: string) {
    await apiGenerate(period)
    await seed()
  }

  async function submit(id: string) {
    await apiSubmit(id)
    await seed()
  }

  async function approve(id: string) {
    await apiApprove(id)
    await seed()
  }

  async function reject(id: string, reason: string) {
    await apiReject(id, reason)
    await seed()
  }

  /** 发放登记（外部薪酬系统回传镜像，本系统不划款） */
  async function markPaid(id: string) {
    await apiMarkPaid(id)
    await seed()
  }

  /** 操作后局部刷新单条返回值（备用；当前统一 seed 全量重载保证口径一致） */
  function upsert(dto: CommissionRecordDTO) {
    const idx = items.value.findIndex((i) => i.id === dto.recordId)
    const item = toItem(dto)
    if (idx >= 0) items.value[idx] = item
    else items.value.unshift(item)
  }

  return {
    rules, compConfigs, items, filterStatus, filterPeriod, loading,
    totalCommission, approvedCommission, pendingCommission, paidCommission,
    periods, filtered, get, activeRule, calcTiers,
    seed, generate, submit, approve, reject, markPaid, upsert,
    STATUS_LABEL, STATUS_PILL, BASE_LABEL,
  }
})
