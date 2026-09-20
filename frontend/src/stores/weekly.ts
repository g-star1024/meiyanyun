// ============================================================
// 经营周报 store（M2-20）
// 一周一份，草稿可编辑（客流/成交/营收/复盘），提交后锁定留痕。
// 切真：列表/新建/保存/提交走后端，周号 ISO 由后端计算。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import * as weeklyApi from '@/api/weekly'
import { useToast } from '@/composables/useToast'
import { errMsg } from '@/stores/m5Coupon'
import { useStoreContext } from '@/stores/storeContext'

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

function adapt(d: weeklyApi.WeeklyReportDto): WeeklyReport {
  return {
    id: String(d.id),
    weekNo: d.weekNo,
    startDate: d.startDate,
    endDate: d.endDate,
    revenue: d.revenue,
    prevRevenue: d.prevRevenue,
    footfall: d.footfall,
    orders: d.orders,
    newCustomers: d.newCustomers,
    repurchaseRate: d.repurchaseRate,
    highlights: d.highlights,
    issues: d.issues,
    nextWeekPlan: d.nextWeekPlan,
    status: d.status as WeeklyStatus,
    submittedBy: d.submittedBy ?? undefined,
    submittedAt: d.submittedAt ?? undefined,
  }
}

export const useWeeklyStore = defineStore('weekly', () => {
  const ctx = useStoreContext()
  const toast = useToast()

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

  function replaceReport(next: WeeklyReport) {
    const idx = reports.value.findIndex((r) => r.id === next.id)
    if (idx >= 0) reports.value.splice(idx, 1, next)
    else reports.value.push(next)
  }

  async function save(id: string, patch: Partial<Pick<WeeklyReport,
    'revenue' | 'footfall' | 'orders' | 'newCustomers' | 'repurchaseRate' |
    'highlights' | 'issues' | 'nextWeekPlan'
  >>): Promise<boolean> {
    const r = reports.value.find((x) => x.id === id)
    if (!r || r.status !== 'DRAFT') return false
    try {
      const { data } = await weeklyApi.saveWeeklyReport(id, {
        revenue: patch.revenue ?? r.revenue,
        footfall: patch.footfall ?? r.footfall,
        orders: patch.orders ?? r.orders,
        newCustomers: patch.newCustomers ?? r.newCustomers,
        repurchaseRate: patch.repurchaseRate ?? r.repurchaseRate,
        highlights: patch.highlights ?? r.highlights,
        issues: patch.issues ?? r.issues,
        nextWeekPlan: patch.nextWeekPlan ?? r.nextWeekPlan,
      })
      replaceReport(adapt(data))
      return true
    } catch (e) {
      toast.error(errMsg(e, '草稿保存失败，请稍后重试'))
      return false
    }
  }

  async function submit(id: string): Promise<boolean> {
    const r = reports.value.find((x) => x.id === id)
    if (!r || r.status !== 'DRAFT') return false
    try {
      const { data } = await weeklyApi.submitWeeklyReport(id)
      replaceReport(adapt(data))
      return true
    } catch (e) {
      toast.error(errMsg(e, '周报提交失败，请稍后重试'))
      return false
    }
  }

  function fmtRange(r: WeeklyReport) {
    return `${r.startDate.slice(5)} ~ ${r.endDate.slice(5)}`
  }

  async function load() {
    try {
      const sc = ctx.currentStoreCode
      const { data } = await weeklyApi.listWeeklyReports({ storeCode: sc || undefined })
      reports.value = data.map(adapt)
    } catch (e) {
      reports.value = []
      toast.error(errMsg(e, '经营周报加载失败，请稍后重试'))
    }
  }

  async function seed() {
    await ctx.loadStores()
    await load()
  }

  /** 新建周报（周号与区间由后端按 ISO 周计算） */
  async function createWeekly(): Promise<boolean> {
    try {
      const sc = ctx.currentStoreCode
      const { data } = await weeklyApi.createWeeklyReport({ storeCode: sc || undefined })
      replaceReport(adapt(data))
      return true
    } catch (e) {
      toast.error(errMsg(e, '新建周报失败，请稍后重试'))
      return false
    }
  }

  return {
    reports, drafts, submitted, sorted, current, latest, wowRevenue,
    get, save, submit, createWeekly, fmtRange, seed, STATUS_LABEL,
  }
})
