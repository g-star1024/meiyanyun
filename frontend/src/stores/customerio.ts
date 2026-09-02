// ============================================================
// CustomerIO 客户导入导出 store（M3-16）
// 导入任务历史 / 导出任务历史，模拟上传→校验→完成流程。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

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

export const useCustomerIoStore = defineStore('customerio', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const imports = ref<ImportTask[]>([])
  const exports = ref<ExportTask[]>([])

  const monthStart = computed(() => {
    const d = new Date()
    return new Date(d.getFullYear(), d.getMonth(), 1).getTime()
  })
  const monthImports = computed(() => imports.value.filter((t) => new Date(t.createdAt).getTime() >= monthStart.value))
  const monthExports = computed(() => exports.value.filter((t) => new Date(t.createdAt).getTime() >= monthStart.value))
  const monthImportTotal = computed(() => monthImports.value.reduce((s, t) => s + t.total, 0))
  const monthExportTotal = computed(() => monthExports.value.reduce((s, t) => s + t.count, 0))
  const pending = computed(() => imports.value.filter((t) => t.status === 'PENDING' || t.status === 'VALIDATING'))
  const importSuccessRate = computed(() => {
    const total = imports.value.reduce((s, t) => s + t.total, 0)
    const succ = imports.value.reduce((s, t) => s + t.success, 0)
    if (!total) return 0
    return Math.round((succ / total) * 1000) / 10
  })

  function createImport(fileName: string, total: number, failed: number): ImportTask | null {
    if (!auth.can('io:import')) return null
    const task: ImportTask = {
      id: nextId('imp'),
      fileName,
      total,
      success: total - failed,
      failed,
      status: 'VALIDATING',
      operator: auth.user.name,
      createdAt: new Date().toISOString(),
      errors: failed > 0 ? [
        `第 12 行：手机号格式错误`,
        `第 48 行：身份证号校验位错误`,
        `第 103 行：姓名为空`,
      ].slice(0, Math.min(failed, 3)) : [],
    }
    imports.value.unshift(task)
    activity.log(auth.user.name, `上传客户数据：${fileName}（${total} 条）`, task.id)
    // 模拟校验→完成
    setTimeout(() => {
      task.status = failed > 5 ? 'FAILED' : 'DONE'
      activity.log(auth.user.name, `导入任务${task.status === 'DONE' ? '完成' : '失败'}：${fileName}`, task.id)
    }, 1500)
    return task
  }

  function createExport(input: { filter: string; scope: ExportScope; count: number; maskPhone: boolean; maskId: boolean }): ExportTask | null {
    if (!auth.can('io:export')) return null
    const task: ExportTask = {
      id: nextId('exp'),
      filter: input.filter,
      count: input.count,
      maskPhone: input.maskPhone,
      maskId: input.maskId,
      operator: auth.user.name,
      createdAt: new Date().toISOString(),
    }
    exports.value.unshift(task)
    activity.log(auth.user.name, `导出客户数据：${input.filter}（${input.count} 条）`, task.id)
    return task
  }

  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    const now = Date.now()
    const ago = (d: number, h = 0) => new Date(now - d * 86400_000 - h * 3600_000).toISOString()

    const importData: Array<Omit<ImportTask, 'id'>> = [
      { fileName: '新客名单_20260820.xlsx', total: 240, success: 238, failed: 2, status: 'DONE', operator: '吴桐', createdAt: ago(5) },
      { fileName: '沉睡客户唤醒导入.csv', total: 186, success: 180, failed: 6, status: 'FAILED', operator: '李娜', createdAt: ago(8), errors: ['第 23 行：手机号重复', '第 67 行：等级字段非法'] },
      { fileName: '老带新推荐名单.xlsx', total: 92, success: 92, failed: 0, status: 'DONE', operator: '周敏', createdAt: ago(12) },
      { fileName: '美团点评客户迁移.xlsx', total: 420, success: 405, failed: 15, status: 'DONE', operator: '吴桐', createdAt: ago(18) },
      { fileName: '异业合作客户名单.csv', total: 68, success: 68, failed: 0, status: 'PENDING', operator: '李娜', createdAt: ago(0, 2) },
      { fileName: '门店活动签到表_0815.xlsx', total: 156, success: 152, failed: 4, status: 'DONE', operator: '周敏', createdAt: ago(10) },
    ]
    importData.forEach((t) => imports.value.push({ ...t, id: nextId('imp') }))

    const exportData: Array<Omit<ExportTask, 'id'>> = [
      { filter: '全部 VIP 客户', count: 326, maskPhone: true, maskId: true, operator: '陈雅琳', createdAt: ago(2) },
      { filter: '高价值分群', count: 42, maskPhone: false, maskId: false, operator: '吴桐', createdAt: ago(4) },
      { filter: '沉睡 60 天客户', count: 124, maskPhone: true, maskId: true, operator: '李娜', createdAt: ago(7) },
      { filter: '近 30 天到店客户', count: 486, maskPhone: true, maskId: false, operator: '周敏', createdAt: ago(11) },
      { filter: '流失风险客户', count: 28, maskPhone: false, maskId: false, operator: '陈雅琳', createdAt: ago(14) },
    ]
    exportData.forEach((t) => exports.value.push({ ...t, id: nextId('exp') }))
  }

  return {
    imports, exports,
    monthImportTotal, monthExportTotal, pending, importSuccessRate,
    createImport, createExport, seed,
    IMPORT_STATUS_LABEL,
  }
})
