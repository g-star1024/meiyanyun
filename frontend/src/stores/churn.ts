// ============================================================
// Churn 流失预警 store（M3-10）
// 风险客户：等级/最后到店天数/风险等级/原因标签/建议动作/状态/归属人。
// KPI：高风险 / 中风险 / 本月挽回 / 流失率。
// 权限：churn:view / churn:edit。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

export type ChurnRisk = 'HIGH' | 'MEDIUM' | 'LOW'
export type ChurnReason = 'PRICE' | 'SERVICE' | 'COMPETITION' | 'NATURAL'
export type ChurnStatus = 'PENDING' | 'INTERVENING' | 'RECOVERED' | 'LOST'

export interface ChurnLog {
  id: string
  by: string
  at: string
  action: string
  note?: string
}

export interface ChurnCustomer {
  id: string
  name: string
  level: string
  lastVisitDays: number
  totalSpent: number
  lastSpent: number
  visitCount: number
  risk: ChurnRisk
  riskScore: number
  reasons: ChurnReason[]
  suggestedAction: string
  status: ChurnStatus
  assignee: string
  logs: ChurnLog[]
}

const RISK_LABEL: Record<ChurnRisk, string> = {
  HIGH: '高风险',
  MEDIUM: '中风险',
  LOW: '低风险',
}
const RISK_SCORE: Record<ChurnRisk, number> = { HIGH: 3, MEDIUM: 2, LOW: 1 }
const REASON_LABEL: Record<ChurnReason, string> = {
  PRICE: '价格敏感',
  SERVICE: '服务体验',
  COMPETITION: '竞品分流',
  NATURAL: '自然流失',
}
const STATUS_LABEL: Record<ChurnStatus, string> = {
  PENDING: '待干预',
  INTERVENING: '干预中',
  RECOVERED: '已挽回',
  LOST: '已流失',
}

function isThisMonth(iso: string) {
  const d = new Date(iso)
  const n = new Date()
  return d.getFullYear() === n.getFullYear() && d.getMonth() === n.getMonth()
}

export const useChurnStore = defineStore('churn', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const customers = ref<ChurnCustomer[]>([])
  const filterRisk = ref<ChurnRisk | 'ALL'>('ALL')

  const high = computed(() => customers.value.filter((c) => c.risk === 'HIGH' && c.status !== 'RECOVERED' && c.status !== 'LOST'))
  const medium = computed(() => customers.value.filter((c) => c.risk === 'MEDIUM' && c.status !== 'RECOVERED' && c.status !== 'LOST'))
  const recoveredThisMonth = computed(() => customers.value.filter((c) => {
    if (c.status !== 'RECOVERED') return false
    const last = c.logs.find((l) => l.action === '标记挽回')
    return last && isThisMonth(last.at)
  }))
  const churnRate = computed(() => {
    if (!customers.value.length) return 0
    const lost = customers.value.filter((c) => c.status === 'LOST').length
    return Math.round((lost / customers.value.length) * 100)
  })

  const filtered = computed(() => {
    let list = customers.value
    if (filterRisk.value !== 'ALL') list = list.filter((c) => c.risk === filterRisk.value)
    return list.sort((a, b) => {
      if (RISK_SCORE[a.risk] !== RISK_SCORE[b.risk]) return RISK_SCORE[b.risk] - RISK_SCORE[a.risk]
      return b.lastVisitDays - a.lastVisitDays
    })
  })

  function get(id: string) {
    return customers.value.find((c) => c.id === id)
  }

  function intervene(id: string, action: string, note?: string): boolean {
    const c = customers.value.find((x) => x.id === id)
    if (!c || !auth.can('churn:edit')) return false
    c.status = 'INTERVENING'
    c.logs.unshift({
      id: nextId('clog'),
      by: auth.user.name,
      at: new Date().toISOString(),
      action: action || '下发干预任务',
      note,
    })
    activity.log(auth.user.name, `下发干预：${c.name} - ${action}`, c.id)
    return true
  }

  function markRecovered(id: string, note?: string): boolean {
    const c = customers.value.find((x) => x.id === id)
    if (!c || !auth.can('churn:edit')) return false
    c.status = 'RECOVERED'
    c.lastVisitDays = 0
    c.logs.unshift({
      id: nextId('clog'),
      by: auth.user.name,
      at: new Date().toISOString(),
      action: '标记挽回',
      note,
    })
    activity.log(auth.user.name, `已挽回客户：${c.name}`, c.id)
    return true
  }

  function markLost(id: string, note?: string): boolean {
    const c = customers.value.find((x) => x.id === id)
    if (!c || !auth.can('churn:edit')) return false
    c.status = 'LOST'
    c.logs.unshift({
      id: nextId('clog'),
      by: auth.user.name,
      at: new Date().toISOString(),
      action: '标记流失',
      note,
    })
    activity.log(auth.user.name, `标记流失：${c.name}`, c.id)
    return true
  }

  // ===== 种子 =====
  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    const now = new Date()
    const daysAgo = (d: number) => new Date(now.getTime() - d * 86400_000).toISOString()

    type Seed = Omit<ChurnCustomer, 'id' | 'logs' | 'riskScore'>
    const base: Seed[] = [
      { name: '孙某某', level: '金卡', lastVisitDays: 60, totalSpent: 18600, lastSpent: 1200, visitCount: 8, risk: 'HIGH', reasons: ['COMPETITION'], suggestedAction: '发放限时8折优惠券 + 顾问电话回访', status: 'PENDING', assignee: '林微' },
      { name: '周某某', level: '银卡', lastVisitDays: 45, totalSpent: 6400, lastSpent: 800, visitCount: 5, risk: 'MEDIUM', reasons: ['NATURAL'], suggestedAction: '到店间隔过长，企微发送新品体验邀约', status: 'INTERVENING', assignee: '苏晴' },
      { name: '吴某某', level: '普通', lastVisitDays: 30, totalSpent: 2200, lastSpent: 500, visitCount: 3, risk: 'MEDIUM', reasons: ['SERVICE'], suggestedAction: '互动骤减，邀请到店免费皮肤检测', status: 'PENDING', assignee: '林微' },
      { name: '赵某某', level: '钻石', lastVisitDays: 90, totalSpent: 42000, lastSpent: 3800, visitCount: 12, risk: 'HIGH', reasons: ['PRICE', 'COMPETITION'], suggestedAction: '高价值客户，店长亲自回访并提供专属套餐', status: 'INTERVENING', assignee: '林微' },
      { name: '郑某某', level: '金卡', lastVisitDays: 25, totalSpent: 9800, lastSpent: 1500, visitCount: 6, risk: 'LOW', reasons: ['NATURAL'], suggestedAction: '发送月度活动海报，保持触达', status: 'RECOVERED', assignee: '苏晴' },
      { name: '冯某某', level: '银卡', lastVisitDays: 75, totalSpent: 5200, lastSpent: 900, visitCount: 4, risk: 'HIGH', reasons: ['SERVICE', 'PRICE'], suggestedAction: '上次服务投诉未闭环，店长致歉+补偿券', status: 'PENDING', assignee: '林微' },
      { name: '陈某某', level: '普通', lastVisitDays: 120, totalSpent: 1800, lastSpent: 600, visitCount: 2, risk: 'HIGH', reasons: ['NATURAL'], suggestedAction: '低价唤醒券 + 短信触达', status: 'LOST', assignee: '苏晴' },
    ]
    base.forEach((s, i) => {
      const logs: ChurnLog[] = [
        { id: nextId('clog'), by: '系统', at: daysAgo(s.lastVisitDays + 5), action: '模型识别为' + RISK_LABEL[s.risk] },
      ]
      if (s.status === 'INTERVENING') {
        logs.unshift({ id: nextId('clog'), by: s.assignee, at: daysAgo(2), action: '下发干预任务', note: s.suggestedAction })
      }
      if (s.status === 'RECOVERED') {
        logs.unshift({ id: nextId('clog'), by: s.assignee, at: daysAgo(3), action: '标记挽回', note: '客户已回店体验热玛吉，充值 6000' })
      }
      if (s.status === 'LOST') {
        logs.unshift({ id: nextId('clog'), by: s.assignee, at: daysAgo(i + 1), action: '标记流失', note: '多次触达无回应' })
      }
      const score = s.risk === 'HIGH' ? 85 + (i % 3) * 3 : s.risk === 'MEDIUM' ? 60 + (i % 3) * 5 : 35
      customers.value.push({ id: nextId('ch'), ...s, riskScore: score, logs })
    })
  }

  return {
    customers, filterRisk,
    high, medium, recoveredThisMonth, churnRate, filtered,
    get, intervene, markRecovered, markLost, seed,
    RISK_LABEL, REASON_LABEL, STATUS_LABEL,
  }
})
