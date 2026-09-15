// ============================================================
// Report API（对接 finance-service 报表中心域 · B49 卡11）
// 读：GET /templates（九模板）/ GET /jobs（闭投影不含 content，供 1s 轮询）/ GET /templates/{id}/preview（截 50 行）
// 写：订阅（回写模板视图）/ 触发生成（异步 GENERATING）/ 失败重试（report:export）
// 下载：GET /jobs/{id}/download 真实 CSV 文件流（BOM+CRLF；历史种子行 content=NULL 走 404 提示路径）
// 收窄：首卡仅 R01 门店营收日报 / R02 月度经营分析真实生成（其余 422 待建），仅 CSV（XLSX/PDF 已登记 backlog）
// ============================================================
import client from './client'

export type ReportCategory = 'REVENUE' | 'CUSTOMER' | 'OPERATION' | 'FINANCE' | 'COMPLIANCE' | 'STAFF'
export type ReportStatus = 'READY' | 'GENERATING' | 'FAILED'
export type ExportFormat = 'XLSX' | 'PDF' | 'CSV'
export type ReportPeriod = 'DAY' | 'WEEK' | 'MONTH' | 'QUARTER' | 'YEAR' | 'RANGE'

/** 报表模板行（desc=口径描述；dimensions/metrics 已拆数组；lastRunAt 仅真实生成过才带） */
export interface ReportTemplateView {
  id: string
  name: string
  category: ReportCategory
  desc: string
  period: ReportPeriod
  dimensions: string[]
  metrics: string[]
  lastRunAt?: string
  subscribed: boolean
}

/** 生成历史行（fileSize 已格式化为 '248 KB' 串；rowCount/fileSize/error 条件携带） */
export interface ReportJobView {
  id: string
  templateId: string
  templateName: string
  category: ReportCategory
  period: string
  status: ReportStatus
  format: ExportFormat
  createdAt: string
  createdBy: string
  rowCount?: number
  fileSize?: string
  error?: string
}

/** 数据预览（rows 为字符串矩阵，最多 50 行） */
export interface ReportPreview {
  headers: string[]
  rows: string[][]
}

export const listReportTemplates = () =>
  client.get<ReportTemplateView[]>('/finance/report/templates')

export const listReportJobs = () =>
  client.get<ReportJobView[]>('/finance/report/jobs')

export const subscribeTemplate = (id: string, subscribed: boolean) =>
  client.post<ReportTemplateView>(`/finance/report/templates/${encodeURIComponent(id)}/subscribe`, { subscribed })

export const previewTemplate = (id: string, period?: string) =>
  client.get<ReportPreview>(`/finance/report/templates/${encodeURIComponent(id)}/preview`,
    { params: period ? { period } : {} })

export const generateReport = (body: { templateId: string; period?: string; format?: string }) =>
  client.post<ReportJobView>('/finance/report/generate', body)

export const retryReportJob = (jobId: string) =>
  client.post<ReportJobView>(`/finance/report/jobs/${encodeURIComponent(jobId)}/retry`)

/** 下载真实 CSV（blob）。responseType=blob 时错误体同为 Blob，需先还原 JSON 取中文 message 再抛。 */
export const downloadReportJob = async (jobId: string): Promise<{ blob: Blob; filename: string }> => {
  const r = await client
    .get(`/finance/report/jobs/${encodeURIComponent(jobId)}/download`, { responseType: 'blob' })
    .catch(async (e) => {
      const data = e?.response?.data
      if (data instanceof Blob) {
        let msg = '下载失败'
        try { msg = JSON.parse(await data.text())?.message || msg } catch { /* 非 JSON 错误体走缺省文案 */ }
        throw new Error(msg)
      }
      throw e
    })
  const dispo: string = r.headers['content-disposition'] || ''
  const m = dispo.match(/filename\*=UTF-8''([^;]+)/i)
  const filename = m ? decodeURIComponent(m[1]) : `report-${jobId}.csv`
  return { blob: r.data as Blob, filename }
}
