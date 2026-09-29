// ============================================================
// T4 AI 中台底座 - 模型仓库 store（T4-卡1 切真 ai-service）
// 模型登记 / 版本管理 / 发布审批红线（非 READY 禁发，需走 T3-01 审批）
// 对齐 T4-01 详设
// 数据源：/api/ai/t4/models（V78 落库；类级 model:view，
// 注册=model:register，版本=model:version，发布申请/废弃=model:release，
// 回滚=model:rollback）。
// 红线：①非 READY 禁发（后端 400 逐字透出）②发布仅走审批中心
// T4_MODEL 单，审批通过由后端 ApprovalService 联动发布，前端不直改状态
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { useToast } from '@/composables/useToast'
import { errMsg } from './m5Coupon'
import * as api from '@/api/t4Model'
import type { ModelView, VersionView } from '@/api/t4Model'

export type ModelStatus = 'DRAFT' | 'TRAINING' | 'READY' | 'PUBLISHED' | 'DEPRECATED'
export type ModelType = 'CLASSIFICATION' | 'REGRESSION' | 'NLP' | 'CV' | 'RECOMMEND' | 'GENERATIVE'

export interface ModelVersion {
  version: string
  metrics: Record<string, number>
  status: ModelStatus
  trainedAt: string
  publishedAt?: string
  approvedBy?: string
  remark?: string
}

export interface ModelRecord {
  id: string
  name: string
  type: ModelType
  description: string
  owner: string
  department: string
  tags: string[]
  versions: ModelVersion[]
  currentVersion?: string
  status: ModelStatus
  inputSchema: string
  outputSchema: string
  callCount30d: number
  avgLatencyMs: number
  errorRate: number
  createdAt: string
  updatedAt: string
}

export const MODEL_TYPE_LABEL: Record<ModelType, string> = {
  CLASSIFICATION: '分类',
  REGRESSION: '回归',
  NLP: '自然语言',
  CV: '计算机视觉',
  RECOMMEND: '推荐',
  GENERATIVE: '生成式',
}

export const MODEL_STATUS_LABEL: Record<ModelStatus, string> = {
  DRAFT: '草稿',
  TRAINING: '训练中',
  READY: '待发布',
  PUBLISHED: '已发布',
  DEPRECATED: '已废弃',
}

export const useT4ModelStore = defineStore('t4Model', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()
  const toast = useToast()

  const models = ref<ModelRecord[]>([])
  const loaded = ref(false)
  const loading = ref(false)
  const loadError = ref('')

  // ---- 适配层：后端 View → 前端类型（铁律 -1-B：前后端差异只在此消化；id=后端 code） ----
  function mapVersion(v: VersionView): ModelVersion {
    return {
      version: v.version,
      metrics: v.metrics ?? {},
      status: v.status as ModelStatus,
      trainedAt: v.trainedAt,
      publishedAt: v.publishedAt ?? undefined,
      approvedBy: v.approvedBy ?? undefined,
      remark: v.remark ?? undefined,
    }
  }

  function mapModel(v: ModelView): ModelRecord {
    return {
      id: v.code,
      name: v.name,
      type: v.type as ModelType,
      description: v.description,
      owner: v.owner,
      department: v.department,
      tags: v.tags ?? [],
      versions: (v.versions ?? []).map(mapVersion),
      currentVersion: v.currentVersion ?? undefined,
      status: v.status as ModelStatus,
      inputSchema: v.inputSchema,
      outputSchema: v.outputSchema,
      callCount30d: v.callCount30d,
      avgLatencyMs: v.avgLatencyMs,
      errorRate: v.errorRate,
      createdAt: v.createdAt,
      updatedAt: v.updatedAt,
    }
  }

  // ---- 查询 ----
  const publishedModels = computed(() => models.value.filter((m) => m.status === 'PUBLISHED'))

  const kpi = computed(() => ({
    total: models.value.length,
    training: models.value.filter((m) => m.status === 'TRAINING').length,
    published: publishedModels.value.length,
    calls: models.value.reduce((s, m) => s + m.callCount30d, 0),
  }))

  function getModel(id: string) {
    return models.value.find((m) => m.id === id)
  }

  function can(perm: string) {
    return auth.can(perm)
  }

  function replaceModel(next: ModelRecord) {
    const idx = models.value.findIndex((m) => m.id === next.id)
    if (idx >= 0) models.value.splice(idx, 1, next)
    else models.value.unshift(next)
  }

  // ---- 装载 ----
  async function load() {
    loading.value = true
    loadError.value = ''
    try {
      models.value = (await api.listModels()).map(mapModel)
      loaded.value = true
    } catch (e) {
      loadError.value = errMsg(e)
      console.warn('[t4Model] 模型仓库加载失败', e)
    } finally {
      loading.value = false
    }
  }

  /** 进页装载（每次进页重拉真实数据） */
  async function seed() {
    await load()
  }

  // ---- 命令 ----
  async function registerModel(input: {
    name: string
    type: ModelType
    description: string
    owner: string
    department: string
    inputSchema: string
    outputSchema: string
    tags: string[]
  }): Promise<ModelRecord | null> {
    if (!auth.can('model:register')) {
      toast.error('无模型注册权限')
      return null
    }
    try {
      const v = await api.registerModel({
        name: input.name,
        type: input.type,
        description: input.description,
        owner: input.owner,
        department: input.department,
        tags: input.tags,
        inputSchema: input.inputSchema,
        outputSchema: input.outputSchema,
      })
      const m = mapModel(v)
      models.value.unshift(m)
      activity.log(auth.user.name, `注册模型「${m.name}」（${MODEL_TYPE_LABEL[m.type]}）`, m.id)
      toast.success(`模型「${m.name}」已注册`)
      return m
    } catch (e) {
      toast.error(errMsg(e, '注册模型失败'))
      return null
    }
  }

  async function addVersion(
    modelId: string,
    v: Omit<ModelVersion, 'trainedAt'> & { trainedAt?: string },
  ): Promise<ModelVersion | null> {
    if (!auth.can('model:version')) {
      toast.error('无版本上传权限')
      return null
    }
    const m = getModel(modelId)
    if (!m) return null
    try {
      const view = await api.addVersion(modelId, {
        version: v.version,
        metrics: v.metrics,
        status: v.status,
        remark: v.remark,
      })
      replaceModel(mapModel(view))
      const version = getModel(modelId)?.versions.find((x) => x.version === v.version) ?? null
      activity.log(auth.user.name, `模型「${m.name}」新增版本 ${v.version}`, modelId)
      toast.success(`模型「${m.name}」版本 ${v.version} 已上传`)
      return version
    } catch (e) {
      toast.error(errMsg(e, '版本上传失败'))
      return null
    }
  }

  /**
   * 发布红线：
   * 1) 非 READY 状态不能发布（后端 400 逐字透出，前端仅预审提示）
   * 2) 必须走 T3-01 审批流程（服务端代建审批中心 T4_MODEL 单，
   *    审批通过由后端联动发布，前端不直改状态）
   */
  async function requestRelease(modelId: string, version: string): Promise<{ ok: boolean; reason?: string }> {
    if (!auth.can('model:release')) return { ok: false, reason: '无模型发布权限' }
    const m = getModel(modelId)
    if (!m) return { ok: false, reason: '模型不存在' }
    const ver = m.versions.find((x) => x.version === version)
    if (!ver) return { ok: false, reason: '版本不存在' }
    if (ver.status !== 'READY') {
      return { ok: false, reason: '仅 READY 状态的版本可发布（红线：非 DRAFT 不能发布）' }
    }
    try {
      const r = await api.requestRelease(modelId, version)
      activity.log(auth.user.name, `提交模型「${m.name}」v${version} 发布审批（T3-01）`, m.id)
      return { ok: true, reason: r.message }
    } catch (e) {
      return { ok: false, reason: errMsg(e, '提交发布审批失败') }
    }
  }

  /**
   * 审批通过后发布由后端 ApprovalService 联动完成；
   * 本函数保留导出契约（铁律 -1-B），语义改为从后端刷新该模型真实状态。
   */
  async function releaseModel(modelId: string, _version?: string, _approver?: string): Promise<void> {
    try {
      const list = (await api.listModels()).map(mapModel)
      models.value = list
      const m = list.find((x) => x.id === modelId)
      if (m) activity.log(auth.user.name, `刷新模型「${m.name}」发布状态（审批联动以服务端为准）`, m.id)
    } catch (e) {
      toast.error(errMsg(e, '刷新模型状态失败'))
    }
  }

  async function rollbackModel(modelId: string, version: string): Promise<boolean> {
    if (!auth.can('model:rollback')) {
      toast.error('无回滚权限')
      return false
    }
    const m = getModel(modelId)
    if (!m) return false
    try {
      const view = await api.rollbackModel(modelId, version)
      replaceModel(mapModel(view))
      activity.log(auth.user.name, `模型「${m.name}」回滚至 v${version}`, modelId)
      toast.success(`模型「${m.name}」已回滚至 v${version}`)
      return true
    } catch (e) {
      toast.error(errMsg(e, '回滚失败'))
      return false
    }
  }

  async function deprecateModel(modelId: string): Promise<boolean> {
    const m = getModel(modelId)
    if (!m) return false
    try {
      const view = await api.deprecateModel(modelId)
      replaceModel(mapModel(view))
      activity.log(auth.user.name, `模型「${m.name}」已废弃`, modelId)
      toast.success(`模型「${m.name}」已废弃`)
      return true
    } catch (e) {
      toast.error(errMsg(e, '废弃失败'))
      return false
    }
  }

  return {
    models, publishedModels, kpi,
    MODEL_TYPE_LABEL, MODEL_STATUS_LABEL,
    getModel, can,
    registerModel, addVersion, requestRelease, releaseModel, rollbackModel, deprecateModel,
    seed,
  }
})
