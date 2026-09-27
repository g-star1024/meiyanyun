// ============================================================
// CustomerIO 客户导入导出 store（M3-16 / M3-B5 切真）
// 数据源：customer-service /api/customer/m3/io（io_task + 导入校验流
// + 导出四 scope 强制脱敏）。KPI 四卡改读服务端 stats 统算。
// 权限：io:import / io:export。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { useToast } from '@/composables/useToast'
import { errMsg } from './m5Coupon'
import {
  listImports, listExports, getIoStats, uploadImport, exportCustomers,
  type IoImportView, type IoExportView, type IoStats,
} from '@/api/customerio'

export type ImportStatus = 'PENDING' | 'VALIDATING' | 'DONE' | 'FAILED'
export type ExportScope = 'ALL' | 'TAG' | 'LEVEL' | 'SEGMENT'

export interface ImportTask {
  id: string
  fileName: string
  total: number
  success: number
  failed: number
  status: ImportStatus
  operator: string
  createdAt: string
  errors?: string[]
}

export interface ExportTask {
  id: string
  filter: string
  count: number
  maskPhone: boolean
  maskId: boolean
  operator: string
  createdAt: string
}

const IMPORT_STATUS_LABEL: Record<ImportStatus, string> = {
  PENDING: '待校验',
  VALIDATING: '校验中',
  DONE: '已完成',
  FAILED: '失败',
}

function mapImport(v: IoImportView): ImportTask {
  return {
    id: v.taskNo,
    fileName: v.fileName ?? '',
    total: v.total ?? 0,
    success: v.success ?? 0,
    failed: v.failed ?? 0,
    status: (v.status as ImportStatus) || 'PENDING',
    operator: v.operator ?? '',
    createdAt: v.createdAt ?? '',
    errors: v.errors ?? [],
  }
}

function mapExport(v: IoExportView): ExportTask {
  return {
    id: v.taskNo,
    filter: v.filter ?? '',
    count: v.count ?? 0,
    maskPhone: !!v.maskPhone,
    maskId: !!v.maskId,
    operator: v.operator ?? '',
    createdAt: v.createdAt ?? '',
  }
}

export const useCustomerIoStore = defineStore('customerio', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()
  const toast = useToast()

  const imports = ref<ImportTask[]>([])
  const exports = ref<ExportTask[]>([])
  const stats = ref<IoStats>({ monthImportTotal: 0, monthExportTotal: 0, pending: 0, importSuccessRate: 0 })

  const monthImportTotal = computed(() => stats.value.monthImportTotal)
  const monthExportTotal = computed(() => stats.value.monthExportTotal)
  const pending = computed(() => stats.value.pending)
  const importSuccessRate = computed(() => stats.value.importSuccessRate)

  async function refreshStatsQuiet() {
    try {
      stats.value = await getIoStats()
    } catch { /* 静默：不覆盖主操作结果 */ }
  }

  async function createImport(file: File): Promise<ImportTask | null> {
    if (!auth.can('io:import')) return null
    try {
      const t = mapImport(await uploadImport(file))
      imports.value.unshift(t)
      activity.log(auth.user.name, `上传客户数据：${t.fileName}（${t.total} 条）`, t.id)
      if (t.status === 'DONE') {
        activity.log(auth.user.name, `导入任务完成：${t.fileName}`, t.id)
        toast.success(`导入完成：成功 ${t.success} 条`)
      } else {
        activity.log(auth.user.name, `导入任务失败：${t.fileName}`, t.id)
        toast.warning(`校验未通过：${t.failed} 行失败，详见错误列`)
      }
      await refreshStatsQuiet()
      return t
    } catch (e) {
      toast.error(errMsg(e, '导入失败'))
      return null
    }
  }

  async function createExport(input: { filter: string; scope: ExportScope; count: number; maskPhone: boolean; maskId: boolean }): Promise<ExportTask | null> {
    if (!auth.can('io:export')) return null
    try {
      const f = await exportCustomers({ scope: input.scope, maskId: input.maskId })
      const url = URL.createObjectURL(f.blob)
      const a = document.createElement('a')
      a.href = url
      a.download = f.fileName
      a.click()
      URL.revokeObjectURL(url)
      exports.value = (await listExports()).map(mapExport)
      const t = exports.value[0] ?? null
      activity.log(auth.user.name, `导出客户数据：${input.filter}（${t?.count ?? 0} 条）`, t?.id)
      toast.success(`导出成功：${f.fileName}`)
      await refreshStatsQuiet()
      return t
    } catch (e) {
      toast.error(errMsg(e, '导出失败'))
      return null
    }
  }

  // ===== 加载（切真：customer /api/customer/m3/io；保留 seed 名兼容视图入口） =====
  let seeded = false
  async function seed() {
    if (seeded) return
    seeded = true
    try {
      const [imp, exp, st] = await Promise.all([listImports(), listExports(), getIoStats()])
      imports.value = imp.map(mapImport)
      exports.value = exp.map(mapExport)
      stats.value = st
    } catch (e) {
      seeded = false
      toast.error(errMsg(e, '导入导出数据加载失败'))
    }
  }

  return {
    imports, exports,
    monthImportTotal, monthExportTotal, pending, importSuccessRate,
    createImport, createExport, seed,
    IMPORT_STATUS_LABEL,
  }
})
