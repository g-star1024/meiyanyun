// ============================================================
// M1 报表中心 store（B49 卡11 · 去 mock 接真）
// 数据源：/api/finance/report 七端点（模板/历史/订阅/预览/生成/重试/下载）
// 收窄：首卡仅 R01/R02 两模板真实生成（SUPPORTED_IDS 与后端对齐），仅 CSV
// 生成是异步任务：触发后轮询 GET /jobs（1s × 10 上限）直至脱离 GENERATING
// ============================================================
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import * as api from '@/api/report'
import { useToast } from '@/composables/useToast'
import { errMsg } from '@/stores/m5Coupon'
import type { ReportCategory, ReportStatus, ExportFormat, ReportTemplateView, ReportJobView, ReportPreview, ReportVerifyResult } from '@/api/report'

export type { ReportCategory, ReportStatus, ExportFormat, ReportPeriod, ReportTemplateView, ReportJobView, ReportPreview, ReportVerifyResult, ReportVerifyReason } from '@/api/report'
export type ReportTemplate = ReportTemplateView
export type ReportJob = ReportJobView

export const CAT_LABEL: Record<ReportCategory, string> = {
  REVENUE: '营收分析', CUSTOMER: '客户分析', OPERATION: '运营分析',
  FINANCE: '财务报表', COMPLIANCE: '合规报表', STAFF: '人效报表',
}
export const STATUS_LABEL: Record<ReportStatus, string> = { READY: '已生成', GENERATING: '生成中', FAILED: '失败' }
export const FORMAT_LABEL: Record<ExportFormat, string> = { XLSX: 'Excel', PDF: 'PDF', CSV: 'CSV' }

export const useM1ReportStore = defineStore('m1Report', () => {
  const toast = useToast()
  const templates = ref<ReportTemplateView[]>([])
  const jobs = ref<ReportJobView[]>([])
  const loaded = ref(false)
  // B56：验真结果按 jobId 留存（轮询整包替换 jobs 时不丢），verifying 承载按钮 loading
  const verifyResults = ref<Record<string, ReportVerifyResult>>({})
  const verifyingIds = ref<Set<string>>(new Set())

  async function seed() {
    if (loaded.value) return
    loaded.value = true
    try {
      const [t, j] = await Promise.all([api.listReportTemplates(), api.listReportJobs()])
      templates.value = t.data
      jobs.value = j.data
    } catch (e) {
      loaded.value = false
      toast.error(errMsg(e, '报表数据加载失败'))
    }
  }

  const catFilter = ref<ReportCategory | 'ALL'>('ALL')
  const filtered = computed(() => catFilter.value === 'ALL' ? templates.value : templates.value.filter((t) => t.category === catFilter.value))
  const subscribedCount = computed(() => templates.value.filter((t) => t.subscribed).length)

  // 首卡仅 R01 门店营收日报 / R02 月度经营分析真实生成（其余模板后端 422「数据源待建」，已登记 backlog）
  const SUPPORTED_IDS = new Set(['R01', 'R02', 'R03', 'R04', 'R08'])
  function isSupported(id: string) { return SUPPORTED_IDS.has(id) }

  async function toggleSubscribe(id: string) {
    const t = templates.value.find((x) => x.id === id)
    if (!t) return
    try {
      const r = await api.subscribeTemplate(id, !t.subscribed)
      Object.assign(t, r.data)
    } catch (e) {
      toast.error(errMsg(e, '订阅操作失败'))
    }
  }

  async function generate(templateId: string, period: string, format: ExportFormat) {
    try {
      const r = await api.generateReport({ templateId, period, format })
      upsertJob(r.data)
      void pollJob(r.data.id)
    } catch (e) {
      toast.error(errMsg(e, '报表生成失败'))
    }
  }

  async function retry(jobId: string) {
    try {
      const r = await api.retryReportJob(jobId)
      upsertJob(r.data)
      void pollJob(r.data.id)
    } catch (e) {
      toast.error(errMsg(e, '重试失败'))
    }
  }

  // 下载真实 CSV：返回 blob+文件名由视图负责另存；历史种子行 content=NULL 时后端 404 文案经 errMsg 外露
  async function download(jobId: string) {
    try {
      return await api.downloadReportJob(jobId)
    } catch (e) {
      toast.error(errMsg(e, '下载失败'))
      return null
    }
  }

  // 数据预览：不支持的模板 / 期段非法时静默置 null（视图不渲染预览区）
  async function preview(templateId: string, period?: string): Promise<ReportPreview | null> {
    try {
      const r = await api.previewTemplate(templateId, period)
      return r.data
    } catch {
      return null
    }
  }

  // B56 哈希验真：结果留 verifyResults（成功/业务空态均留），网络/权限异常 toast 且不留结果
  async function verify(jobId: string) {
    if (verifyingIds.value.has(jobId)) return
    verifyingIds.value.add(jobId)
    verifyingIds.value = new Set(verifyingIds.value)
    try {
      const r = await api.verifyReportJob(jobId)
      verifyResults.value[jobId] = r.data
      verifyResults.value = { ...verifyResults.value }
    } catch (e) {
      toast.error(errMsg(e, '验真失败'))
    } finally {
      verifyingIds.value.delete(jobId)
      verifyingIds.value = new Set(verifyingIds.value)
    }
  }

  function upsertJob(job: ReportJobView) {
    const i = jobs.value.findIndex((x) => x.id === job.id)
    if (i >= 0) jobs.value[i] = job
    else jobs.value.unshift(job)
  }

  // 异步生成轮询：1s × 10 上限，任务脱离 GENERATING 即停；READY 后回拉模板刷 lastRunAt
  async function pollJob(jobId: string) {
    for (let i = 0; i < 10; i++) {
      await new Promise((r) => setTimeout(r, 1000))
      try {
        const r = await api.listReportJobs()
        jobs.value = r.data
      } catch {
        return
      }
      const j = jobs.value.find((x) => x.id === jobId)
      if (!j || j.status !== 'GENERATING') {
        if (j?.status === 'READY') {
          try {
            const t = await api.listReportTemplates()
            templates.value = t.data
          } catch { /* lastRunAt 回显刷新失败不阻断主流程 */ }
        }
        return
      }
    }
  }

  return {
    templates, jobs, loaded, seed, catFilter, filtered, subscribedCount,
    isSupported, toggleSubscribe, generate, retry, download, preview, verify,
    verifyResults, verifyingIds,
  }
})
