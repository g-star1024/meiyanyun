// ============================================================
// 经营周报 store（M2-20）
// 一周一份，草稿可编辑（客流/成交/营收/复盘），提交后锁定留痕。
// 对齐 list-detail 范式：seed ≥6、computed、action、nextId、activity.log。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

export type WeeklyStatus = 'DRAFT' | 'SUBMITTED'

export interface WeeklyReport {
  id: string
  weekNo: string         // 如 2026-W34
  startDate: string      // YYYY-MM-DD
  endDate: string
  revenue: number        // 营收（元）
  prevRevenue: number    // 上周营收（用于环比）
  footfall: number       // 客流
  orders: number         // 成交单数
  newCustomers: number   // 新客
  repurchaseRate: number // 复购率 %
  highlights: string     // 本周亮点
  issues: string         // 问题与风险
  nextWeekPlan: string   // 下周计划
  status: WeeklyStatus
  submittedBy?: string
  submittedAt?: string
}

const STATUS_LABEL: Record<WeeklyStatus, string> = { DRAFT: '草稿', SUBMITTED: '已提交' }

export const useWeeklyStore = defineStore('weekly', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const reports = ref<WeeklyReport[]>([])

  const drafts = computed(() => reports.value.filter((r) => r.status === 'DRAFT'))
  const submitted = computed(() => reports.value.filter((r) => r.status === 'SUBMITTED'))
  const sorted = computed(() =>
    [...reports.value].sort((a, b) => b.startDate.localeCompare(a.startDate)),
  )
  const current = computed<WeeklyReport | null>(() => sorted.value[0] ?? null)
  const latest = computed<WeeklyReport | null>(() =>
    [...reports.value].sort((a, b) => b.endDate.localeCompare(a.endDate))[0] ?? null,
  )

  // 环比（以当前最新一周为基准）
  const wowRevenue = computed(() => {
    if (!latest.value || latest.value.prevRevenue <= 0) return 0
    return Math.round(
      ((latest.value.revenue - latest.value.prevRevenue) / latest.value.prevRevenue) * 100,
    )
  })

  function get(id: string) {
    return reports.value.find((r) => r.id === id)
  }

  function save(id: string, patch: Partial<Pick<WeeklyReport,
    'revenue' | 'footfall' | 'orders' | 'newCustomers' | 'repurchaseRate' |
    'highlights' | 'issues' | 'nextWeekPlan'
  >>): boolean {
    const r = reports.value.find((x) => x.id === id)
    if (!r || r.status !== 'DRAFT' || !auth.can('weekly:submit')) return false
    Object.assign(r, patch)
    return true
  }

  function submit(id: string): boolean {
    const r = reports.value.find((x) => x.id === id)
    if (!r || r.status !== 'DRAFT' || !auth.can('weekly:submit')) return false
    r.status = 'SUBMITTED'
    r.submittedBy = auth.user.name
    r.submittedAt = new Date().toISOString()
    activity.log(auth.user.name, `提交经营周报 ${r.weekNo}（营收 ¥${r.revenue.toLocaleString()}）`, r.id)
    return true
  }

  function fmtRange(r: WeeklyReport) {
    return `${r.startDate.slice(5)} ~ ${r.endDate.slice(5)}`
  }

  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    const base: Array<Omit<WeeklyReport, 'id'>> = [
      {
        weekNo: '2026-W34', startDate: '2026-08-17', endDate: '2026-08-23',
        revenue: 486300, prevRevenue: 452800, footfall: 318, orders: 96,
        newCustomers: 42, repurchaseRate: 58,
        highlights: '超声炮项目周销冠，林微咨询师个人业绩突破 24 万；新客转化率 42%，环比提升 6 个百分点。',
        issues: '周三 A03 空调故障影响 2 单体验；玻尿酸库存预警，需补货。',
        nextWeekPlan: '启动七夕会员专属活动；安排 A03 空调检修；完成 8 月存量客户回访。',
        status: 'DRAFT',
      },
      {
        weekNo: '2026-W33', startDate: '2026-08-10', endDate: '2026-08-16',
        revenue: 452800, prevRevenue: 431500, footfall: 296, orders: 88,
        newCustomers: 35, repurchaseRate: 55,
        highlights: '热玛吉套餐连带销售 12 单；老客复购集中在水光和皮肤管理。',
        issues: '客流集中在周末，工作日预约不饱和；陈珂医生休 2 天。',
        nextWeekPlan: '工作日 14:00-17:00 推出限时体验价；优化咨询师排班。',
        status: 'SUBMITTED', submittedBy: '苏晴', submittedAt: '2026-08-17T10:12:00',
      },
      {
        weekNo: '2026-W32', startDate: '2026-08-03', endDate: '2026-08-09',
        revenue: 431500, prevRevenue: 398600, footfall: 284, orders: 82,
        newCustomers: 31, repurchaseRate: 53,
        highlights: '会员日活动带来 28 万营收；私域社群新增 120 人。',
        issues: '新客首单转化率偏低（28%），需加强咨询师话术培训。',
        nextWeekPlan: '组织一次咨询话术内训；准备七夕活动物料。',
        status: 'SUBMITTED', submittedBy: '苏晴', submittedAt: '2026-08-10T09:30:00',
      },
      {
        weekNo: '2026-W31', startDate: '2026-07-27', endDate: '2026-08-02',
        revenue: 398600, prevRevenue: 412000, footfall: 268, orders: 76,
        newCustomers: 28, repurchaseRate: 51,
        highlights: '皮肤管理次卡销售良好；疗程卡续卡率 62%。',
        issues: '营收环比下滑 3.3%，主要因高温天气客流减少。',
        nextWeekPlan: '推夏季补水套餐；线上投放增加。',
        status: 'SUBMITTED', submittedBy: '苏晴', submittedAt: '2026-08-03T11:05:00',
      },
      {
        weekNo: '2026-W30', startDate: '2026-07-20', endDate: '2026-07-26',
        revenue: 412000, prevRevenue: 389400, footfall: 275, orders: 80,
        newCustomers: 33, repurchaseRate: 54,
        highlights: '抗衰类项目占比提升至 45%；客单价同比提升 8%。',
        issues: '前台交接班出现一次预约信息遗漏。',
        nextWeekPlan: '强化交接班 checklist；推抗衰组合套餐。',
        status: 'SUBMITTED', submittedBy: '苏晴', submittedAt: '2026-07-27T09:50:00',
      },
      {
        weekNo: '2026-W29', startDate: '2026-07-13', endDate: '2026-07-19',
        revenue: 389400, prevRevenue: 372000, footfall: 260, orders: 74,
        newCustomers: 26, repurchaseRate: 52,
        highlights: '店庆预热活动启动；老客带新客 12 人。',
        issues: '员工排班与客流高峰不匹配。',
        nextWeekPlan: '调整周末排班；上线店庆主视觉。',
        status: 'SUBMITTED', submittedBy: '苏晴', submittedAt: '2026-07-20T10:00:00',
      },
    ]
    base.forEach((b) => reports.value.push({ id: nextId('wk'), ...b }))
  }

  /** 新建周报（在当前最新周基础上 +1 周） */
  function createWeekly(): boolean {
    if (!auth.can('weekly:submit')) return false
    const sortedReports = [...reports.value].sort((a, b) => b.startDate.localeCompare(a.startDate))
    const last = sortedReports[0]
    const lastStart = last ? new Date(last.startDate) : new Date()
    const nextStart = new Date(lastStart)
    nextStart.setDate(nextStart.getDate() + 7)
    const nextEnd = new Date(nextStart)
    nextEnd.setDate(nextEnd.getDate() + 6)
    const weekNo = `W${nextStart.getFullYear()}${String(nextStart.getMonth() + 1).padStart(2, '0')}-${String(Math.ceil(nextStart.getDate() / 7))}`
    const report: WeeklyReport = {
      id: nextId('wk'),
      weekNo,
      startDate: nextStart.toISOString().slice(0, 10),
      endDate: nextEnd.toISOString().slice(0, 10),
      revenue: 0,
      prevRevenue: last?.revenue ?? 0,
      footfall: 0,
      orders: 0,
      newCustomers: 0,
      repurchaseRate: 0,
      highlights: '',
      issues: '',
      nextWeekPlan: '',
      status: 'DRAFT',
      submittedBy: '',
      submittedAt: '',
    }
    reports.value.unshift(report)
    activity.log(auth.user.name, `新建周报 ${weekNo}`, report.id)
    return true
  }

  return {
    reports, drafts, submitted, sorted, current, latest, wowRevenue,
    get, save, submit, createWeekly, fmtRange, seed, STATUS_LABEL,
  }
})
