// ============================================================
// Inspection 巡店检查 store（M2-10）
// 覆盖环境 / 服务 / 合规三类巡店单，含检查项打分明细与整改跟踪。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

export type InspectionType = 'ENV' | 'SERVICE' | 'COMPLIANCE'
export type InspectionStatus = 'PENDING' | 'IN_PROGRESS' | 'DONE'
export type RectifyStatus = 'OPEN' | 'DOING' | 'DONE'

export interface InspectionItem {
  name: string
  score: number // 0-10
  note?: string
}

export interface RectifyIssue {
  id: string
  desc: string
  owner: string
  status: RectifyStatus
  dueAt: string
  hasPhoto: boolean
}

export interface Inspection {
  id: string
  no: string
  store: string
  inspectedAt: string
  type: InspectionType
  totalScore: number
  issueCount: number
  status: InspectionStatus
  inspector: string
  items: InspectionItem[]
  issues: RectifyIssue[]
  createdAt: string
  completedAt?: string
}

const TYPE_LABEL: Record<InspectionType, string> = {
  ENV: '环境',
  SERVICE: '服务',
  COMPLIANCE: '合规',
}
const TYPE_ICON: Record<InspectionType, string> = {
  ENV: 'sun',
  SERVICE: 'customer',
  COMPLIANCE: 'shield',
}
const STATUS_LABEL: Record<InspectionStatus, string> = {
  PENDING: '待整改',
  IN_PROGRESS: '整改中',
  DONE: '已完成',
}
const RECTIFY_LABEL: Record<RectifyStatus, string> = {
  OPEN: '待整改',
  DOING: '整改中',
  DONE: '已完成',
}

export const useInspectionStore = defineStore('inspection', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const orders = ref<Inspection[]>([])
  const filterType = ref<InspectionType | 'ALL'>('ALL')
  const filterStatus = ref<InspectionStatus | 'ALL'>('ALL')

  const pending = computed(() => orders.value.filter((o) => o.status === 'PENDING'))
  const inProgress = computed(() => orders.value.filter((o) => o.status === 'IN_PROGRESS'))
  const done = computed(() => orders.value.filter((o) => o.status === 'DONE'))

  const avgScore = computed(() => {
    if (!orders.value.length) return 0
    const sum = orders.value.reduce((s, o) => s + o.totalScore, 0)
    return Math.round((sum / orders.value.length) * 10) / 10
  })

  const monthCount = computed(() => {
    const now = new Date()
    return orders.value.filter((o) => {
      const d = new Date(o.inspectedAt)
      return d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth()
    }).length
  })

  const overdue = computed(() => {
    const now = Date.now()
    return orders.value.filter((o) => {
      if (o.status === 'DONE') return false
      return o.issues.some((iss) => iss.status !== 'DONE' && new Date(iss.dueAt).getTime() < now)
    })
  })

  const filtered = computed(() => {
    let list = orders.value
    if (filterType.value !== 'ALL') list = list.filter((o) => o.type === filterType.value)
    if (filterStatus.value !== 'ALL') list = list.filter((o) => o.status === filterStatus.value)
    return list.sort((a, b) => new Date(b.inspectedAt).getTime() - new Date(a.inspectedAt).getTime())
  })

  function get(id: string) {
    return orders.value.find((o) => o.id === id)
  }

  function create(input: {
    store: string
    type: InspectionType
    inspector: string
    inspectedAt: string
    items: InspectionItem[]
  }): Inspection | null {
    if (!auth.can('inspection:create')) {
      console.warn('[inspection] 无 inspection:create 权限')
      return null
    }
    const totalScore = Math.round(
      (input.items.reduce((s, it) => s + it.score, 0) / (input.items.length * 10)) * 100,
    )
    const issueCount = input.items.filter((it) => it.score < 7).length
    const now = new Date().toISOString()
    const seq = orders.value.length + 1
    const o: Inspection = {
      id: nextId('ins'),
      no: `INS-${input.inspectedAt.slice(0, 10).replace(/-/g, '')}-${String(seq).padStart(3, '0')}`,
      store: input.store,
      inspectedAt: input.inspectedAt,
      type: input.type,
      totalScore,
      issueCount,
      status: issueCount > 0 ? 'PENDING' : 'DONE',
      inspector: input.inspector,
      items: input.items,
      issues: input.items
        .filter((it) => it.score < 7)
        .map((it) => ({
          id: nextId('iss'),
          desc: `${it.name} 未达标（${it.score}/10）${it.note ? '：' + it.note : ''}`,
          owner: '待分配',
          status: 'OPEN' as RectifyStatus,
          dueAt: new Date(Date.now() + 7 * 86400_000).toISOString(),
          hasPhoto: false,
        })),
      createdAt: now,
      completedAt: issueCount === 0 ? now : undefined,
    }
    orders.value.unshift(o)
    activity.log(auth.user.name, `创建巡店检查 ${o.no}（${TYPE_LABEL[o.type]}，得分 ${o.totalScore}）`, o.id)
    return o
  }

  function assignIssue(inspectionId: string, issueId: string, owner: string): boolean {
    const o = orders.value.find((x) => x.id === inspectionId)
    const iss = o?.issues.find((x) => x.id === issueId)
    if (!o || !iss || !auth.can('inspection:edit')) return false
    iss.owner = owner
    if (iss.status === 'OPEN') iss.status = 'DOING'
    if (o.status === 'PENDING') o.status = 'IN_PROGRESS'
    activity.log(auth.user.name, `指派整改：${iss.desc} → ${owner}`, o.id)
    return true
  }

  function completeIssue(inspectionId: string, issueId: string, note?: string): boolean {
    const o = orders.value.find((x) => x.id === inspectionId)
    const iss = o?.issues.find((x) => x.id === issueId)
    if (!o || !iss || !auth.can('inspection:edit')) return false
    iss.status = 'DONE'
    iss.hasPhoto = true
    if (o.issues.every((x) => x.status === 'DONE')) {
      o.status = 'DONE'
      o.completedAt = new Date().toISOString()
    } else {
      o.status = 'IN_PROGRESS'
    }
    activity.log(auth.user.name, `完成整改 ${o.no}：${iss.desc}${note ? '（' + note + '）' : ''}`, o.id)
    return true
  }

  // ===== 种子 =====
  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    const today = new Date()
    const daysAgo = (d: number) => {
      const x = new Date(today)
      x.setDate(x.getDate() - d)
      return x.toISOString()
    }
    const daysLater = (d: number) => {
      const x = new Date(today)
      x.setDate(x.getDate() + d)
      return x.toISOString()
    }
    const base: Array<{
      type: InspectionType
      store: string
      inspector: string
      inspectedAt: string
      status: InspectionStatus
      items: InspectionItem[]
    }> = [
      {
        type: 'ENV', store: '静安旗舰店', inspector: '陈野', inspectedAt: daysAgo(2), status: 'PENDING',
        items: [
          { name: '大厅整洁度', score: 8 },
          { name: '治疗室消毒', score: 6, note: 'A03 台面有污渍' },
          { name: '卫生间物资', score: 5, note: '纸巾缺失' },
          { name: '灯光空调', score: 9 },
          { name: '香氛氛围', score: 8 },
        ],
      },
      {
        type: 'SERVICE', store: '静安旗舰店', inspector: '苏晴', inspectedAt: daysAgo(5), status: 'IN_PROGRESS',
        items: [
          { name: '接待话术', score: 9 },
          { name: '术前告知', score: 7 },
          { name: '术后回访', score: 6, note: '24h 内回访率 82%' },
          { name: '客诉响应', score: 8 },
          { name: '仪容仪表', score: 9 },
        ],
      },
      {
        type: 'COMPLIANCE', store: '静安旗舰店', inspector: '周岚', inspectedAt: daysAgo(12), status: 'DONE',
        items: [
          { name: '医师资质公示', score: 10 },
          { name: '器械消毒记录', score: 9 },
          { name: '麻醉药品台账', score: 9 },
          { name: '知情同意书', score: 10 },
          { name: '消防设施', score: 9 },
        ],
      },
      {
        type: 'ENV', store: '徐汇店', inspector: '陈野', inspectedAt: daysAgo(18), status: 'DONE',
        items: [
          { name: '大厅整洁度', score: 9 },
          { name: '治疗室消毒', score: 9 },
          { name: '卫生间物资', score: 8 },
          { name: '灯光空调', score: 9 },
          { name: '香氛氛围', score: 8 },
        ],
      },
      {
        type: 'SERVICE', store: '陆家嘴店', inspector: '苏晴', inspectedAt: daysAgo(22), status: 'IN_PROGRESS',
        items: [
          { name: '接待话术', score: 7 },
          { name: '术前告知', score: 6, note: '部分项目未逐条告知' },
          { name: '术后回访', score: 8 },
          { name: '客诉响应', score: 9 },
          { name: '仪容仪表', score: 8 },
        ],
      },
      {
        type: 'COMPLIANCE', store: '静安旗舰店', inspector: '周岚', inspectedAt: daysAgo(35), status: 'DONE',
        items: [
          { name: '医师资质公示', score: 10 },
          { name: '器械消毒记录', score: 10 },
          { name: '麻醉药品台账', score: 8, note: '一项记录签名缺失' },
          { name: '知情同意书', score: 10 },
          { name: '消防设施', score: 10 },
        ],
      },
    ]
    base.forEach((s, i) => {
      const totalScore = Math.round(
        (s.items.reduce((sum, it) => sum + it.score, 0) / (s.items.length * 10)) * 100,
      )
      const issueCount = s.items.filter((it) => it.score < 7).length
      const issues: RectifyIssue[] = s.items
        .filter((it) => it.score < 7)
        .map((it, idx) => ({
          id: nextId('iss'),
          desc: `${it.name} 未达标（${it.score}/10）${it.note ? '：' + it.note : ''}`,
          owner: idx % 2 === 0 ? '李娜（前台主管）' : '吴桐（运营）',
          status:
            s.status === 'DONE'
              ? 'DONE'
              : idx % 2 === 0
                ? 'DOING'
                : 'OPEN',
          dueAt: daysLater(s.status === 'DONE' ? -1 : 5 + i),
          hasPhoto: s.status === 'DONE' || idx === 0,
        }))
      orders.value.push({
        id: nextId('ins'),
        no: `INS-${s.inspectedAt.slice(0, 10).replace(/-/g, '')}-${String(i + 1).padStart(3, '0')}`,
        store: s.store,
        inspectedAt: s.inspectedAt,
        type: s.type,
        totalScore,
        issueCount,
        status: s.status,
        inspector: s.inspector,
        items: s.items,
        issues,
        createdAt: s.inspectedAt,
        completedAt: s.status === 'DONE' ? daysAgo(i + 1) : undefined,
      })
    })
  }

  return {
    orders, filterType, filterStatus,
    pending, inProgress, done, overdue, avgScore, monthCount, filtered,
    get, create, assignIssue, completeIssue, seed,
    TYPE_LABEL, TYPE_ICON, STATUS_LABEL, RECTIFY_LABEL,
  }
})
