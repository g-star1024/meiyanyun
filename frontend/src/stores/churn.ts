// ============================================================
// Churn 流失预警 store（M3-10 / M3-B3 切真）
// 数据源：ai-service /api/ai/churn（引擎评分结果直读，零新表）。
// 映射口径（DESIGN-M3 §4 B3 + 04 登记）：
//  - predictionId→id(String)；riskLevel high/mid/low→HIGH/MEDIUM/LOW；score→riskScore；
//  - keyFactor→reasons 四值关键词映射；recencyDays→lastVisitDays；
//  - interveneRegistered→status=INTERVENING；详情四格（totalSpent/lastSpent/visitCount）引擎无字段→0 兜底；
//  - churnRate=highCount/scoredCustomers（ChurnStats 无流失率字段）；
//  - 「本月挽回」KPI 映射 interveneTotal（引擎无挽回追踪字段）。
// 写动作：intervene 乐观置位 + registerChurnIntervene 后台登记（失败回滚+toast）；
// markRecovered/markLost 引擎无端点，本地状态标记保留。
// 权限：churn:view / churn:edit。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { useToast } from '@/composables/useToast'
import { errMsg } from './m5Coupon'
import {
  listChurn, getChurnStats, registerChurnIntervene,
  type ChurnRow, type ChurnStats,
} from '@/api/ai'

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

function mapRisk(riskLevel: string): ChurnRisk {
  const r = (riskLevel || '').toLowerCase()
  if (r === 'high') return 'HIGH'
  if (r === 'mid' || r === 'medium') return 'MEDIUM'
  return 'LOW'
}

function mapReasons(keyFactor: string): ChurnReason[] {
  const k = keyFactor || ''
  const out: ChurnReason[] = []
  if (/价|券|折|费|贵/.test(k)) out.push('PRICE')
  if (/服务|投诉|体验|态度/.test(k)) out.push('SERVICE')
  if (/竞品|分流|同行|别家/.test(k)) out.push('COMPETITION')
  if (/自然|到店|间隔|沉睡|久|活跃/.test(k)) out.push('NATURAL')
  if (!out.length) out.push('NATURAL')
  return out
}

function mapRow(row: ChurnRow): ChurnCustomer {
  const risk = mapRisk(row.riskLevel)
  const logs: ChurnLog[] = [
    {
      id: nextId('clog'),
      by: '系统',
      at: row.createdAt ?? new Date().toISOString(),
      action: '模型识别为' + RISK_LABEL[risk],
      note: row.keyFactor || undefined,
    },
  ]
  const status: ChurnStatus = row.interveneRegistered ? 'INTERVENING' : 'PENDING'
  if (row.interveneRegistered) {
    logs.unshift({
      id: nextId('clog'),
      by: '系统',
      at: row.createdAt ?? new Date().toISOString(),
      action: '下发干预任务',
      note: 'ai 引擎已登记干预',
    })
  }
  return {
    id: String(row.predictionId),
    name: row.customerName,
    level: row.level || '普通',
    lastVisitDays: row.recencyDays ?? 0,
    totalSpent: 0,
    lastSpent: 0,
    visitCount: 0,
    risk,
    riskScore: Math.round(row.score ?? 0),
    reasons: mapReasons(row.keyFactor),
    suggestedAction: row.suggestedAction || '',
    status,
    assignee: '—',
    logs,
  }
}

export const useChurnStore = defineStore('churn', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()
  const toast = useToast()

  const customers = ref<ChurnCustomer[]>([])
  const filterRisk = ref<ChurnRisk | 'ALL'>('ALL')
  const stats = ref<ChurnStats | null>(null)

  const high = computed(() => customers.value.filter((c) => c.risk === 'HIGH' && c.status !== 'RECOVERED' && c.status !== 'LOST'))
  const medium = computed(() => customers.value.filter((c) => c.risk === 'MEDIUM' && c.status !== 'RECOVERED' && c.status !== 'LOST'))
  const recoveredThisMonth = computed(() => customers.value.filter((c) => {
    if (c.status !== 'RECOVERED') return false
    const last = c.logs.find((l) => l.action === '标记挽回')
    return last && isThisMonth(last.at)
  }))
  // 「本月挽回」KPI 口径：引擎无挽回追踪字段，映射 ChurnStats.interveneTotal（04 登记）
  const interveneTotal = computed(() => stats.value?.interveneTotal ?? recoveredThisMonth.value.length)
  const churnRate = computed(() => {
    const s = stats.value
    if (s && s.scoredCustomers > 0) return Math.round((s.highCount / s.scoredCustomers) * 100)
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
    const prevStatus = c.status
    c.status = 'INTERVENING'
    c.logs.unshift({
      id: nextId('clog'),
      by: auth.user.name,
      at: new Date().toISOString(),
      action: action || '下发干预任务',
      note,
    })
    activity.log(auth.user.name, `下发干预：${c.name} - ${action}`, c.id)
    // 乐观置位后后台登记 ai 引擎（B2 已联动建跟进任务）；失败回滚本地态
    registerChurnIntervene(Number(id))
      .then(() => {
        if (stats.value) stats.value = { ...stats.value, interveneTotal: stats.value.interveneTotal + 1 }
      })
      .catch((e) => {
        c.status = prevStatus
        toast.error(errMsg(e, '干预登记失败'))
      })
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

  // ===== 加载（切真：ai /api/ai/churn 直读；保留 seed 名兼容视图入口） =====
  let seeded = false
  async function seed() {
    if (seeded) return
    seeded = true
    try {
      const [rows, s] = await Promise.all([listChurn(), getChurnStats()])
      customers.value = rows.map(mapRow)
      stats.value = s
    } catch (e) {
      seeded = false
      toast.error(errMsg(e, '流失预警加载失败'))
    }
  }

  return {
    customers, filterRisk,
    high, medium, recoveredThisMonth, interveneTotal, churnRate, filtered,
    get, intervene, markRecovered, markLost, seed,
    RISK_LABEL, REASON_LABEL, STATUS_LABEL,
  }
})
