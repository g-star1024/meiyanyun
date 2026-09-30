// ============================================================
// 客户导入导出 API（对接 customer-service，经网关 /api/customer 前缀）
// M3-B5 / DESIGN-M3 §4 B5：io_task 任务历史 + 导入同步校验流
// （行级 errors·仅校验不写 customer 表·重复 hash 409 携原 task_no）
// + 导出四 scope（ALL/TAG/LEVEL/SEGMENT）强制脱敏 EasyExcel 附件流。
// ============================================================
import client from './client'

// -------------------- 类型 --------------------

export interface IoImportView {
  id: number
  taskNo: string
  fileName: string
  total: number
  success: number
  failed: number
  status: string
  operator: string
  createdAt: string
  errors: string[] | null
}

export interface IoExportView {
  id: number
  taskNo: string
  filter: string
  count: number
  maskPhone: boolean
  maskId: boolean
  operator: string
  createdAt: string
}

export interface IoStats {
  monthImportTotal: number
  monthExportTotal: number
  pending: number
  importSuccessRate: number
}

export interface IoExportCmd {
  scope: string
  segmentNo?: string | null
  maskId?: boolean
}

export interface IoExportFile {
  blob: Blob
  fileName: string
}

// -------------------- 端点 --------------------

export function listImports(): Promise<IoImportView[]> {
  return client.get('/customer/m3/io/imports').then((r) => r.data)
}

export function listExports(): Promise<IoExportView[]> {
  return client.get('/customer/m3/io/exports').then((r) => r.data)
}

export function getIoStats(): Promise<IoStats> {
  return client.get('/customer/m3/io/stats').then((r) => r.data)
}

export function uploadImport(file: File): Promise<IoImportView> {
  const fd = new FormData()
  fd.append('file', file)
  return client.post('/customer/m3/io/import', fd, { timeout: 60000 }).then((r) => r.data)
}

export async function downloadImportTemplate(): Promise<IoExportFile> {
  try {
    const r = await client.get('/customer/m3/io/import-template', { responseType: 'blob', timeout: 60000 })
    const dispo = String(r.headers['content-disposition'] || '')
    const m = dispo.match(/filename\*=UTF-8''([^;]+)/)
    const fileName = m?.[1] ? decodeURIComponent(m[1]) : '客户导入模板.xlsx'
    return { blob: r.data as Blob, fileName }
  } catch (e) {
    // responseType=blob 时错误体也是 Blob，还原为 JSON 供 errMsg 统一取 message
    const anyE = e as { response?: { data?: unknown } }
    if (anyE?.response?.data instanceof Blob) {
      try {
        anyE.response.data = JSON.parse(await (anyE.response.data as Blob).text())
      } catch { /* 保留原始错误 */ }
    }
    throw e
  }
}

export async function exportCustomers(cmd: IoExportCmd): Promise<IoExportFile> {
  try {
    const r = await client.post('/customer/m3/io/export', cmd, { responseType: 'blob', timeout: 60000 })
    const dispo = String(r.headers['content-disposition'] || '')
    const m = dispo.match(/filename\*=UTF-8''([^;]+)/)
    const fileName = m?.[1] ? decodeURIComponent(m[1]) : `客户导出-${Date.now()}.xlsx`
    return { blob: r.data as Blob, fileName }
  } catch (e) {
    // responseType=blob 时错误体也是 Blob，还原为 JSON 供 errMsg 统一取 message
    const anyE = e as { response?: { data?: unknown } }
    if (anyE?.response?.data instanceof Blob) {
      try {
        anyE.response.data = JSON.parse(await (anyE.response.data as Blob).text())
      } catch { /* 保留原始错误 */ }
    }
    throw e
  }
}
