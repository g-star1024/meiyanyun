// ============================================================
// T4 AI 中台底座 - 模型仓库 store
// 模型登记 / 版本管理 / 发布审批红线（非 READY 禁发，需走 T3-01 审批）
// 对齐 T4-01 详设
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

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

  const models = ref<ModelRecord[]>([])
  const loaded = ref(false)

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

  // ---- 命令 ----
  function registerModel(input: {
    name: string
    type: ModelType
    description: string
    owner: string
    department: string
    inputSchema: string
    outputSchema: string
    tags: string[]
  }): ModelRecord {
    if (!auth.can('model:register')) throw new Error('无模型注册权限')
    const now = new Date().toISOString()
    const m: ModelRecord = {
      id: nextId('mdl'),
      name: input.name,
      type: input.type,
      description: input.description,
      owner: input.owner,
      department: input.department,
      tags: input.tags,
      versions: [],
      status: 'DRAFT',
      inputSchema: input.inputSchema,
      outputSchema: input.outputSchema,
      callCount30d: 0,
      avgLatencyMs: 0,
      errorRate: 0,
      createdAt: now,
      updatedAt: now,
    }
    models.value.unshift(m)
    activity.log(auth.user.name, `注册模型「${m.name}」（${MODEL_TYPE_LABEL[m.type]}）`, m.id)
    return m
  }

  function addVersion(modelId: string, v: Omit<ModelVersion, 'trainedAt'> & { trainedAt?: string }): ModelVersion | null {
    if (!auth.can('model:version')) throw new Error('无版本上传权限')
    const m = getModel(modelId)
    if (!m) return null
    const version: ModelVersion = {
      version: v.version,
      metrics: v.metrics,
      status: v.status,
      trainedAt: v.trainedAt ?? new Date().toISOString(),
      publishedAt: v.publishedAt,
      approvedBy: v.approvedBy,
      remark: v.remark,
    }
    m.versions.push(version)
    if (version.status === 'READY' && m.status === 'DRAFT') m.status = 'READY'
    if (version.status === 'TRAINING') m.status = 'TRAINING'
    m.updatedAt = new Date().toISOString()
    activity.log(auth.user.name, `模型「${m.name}」新增版本 ${version.version}`, m.id)
    return version
  }

  /**
   * 发布红线：
   * 1) 非 READY 状态不能发布
   * 2) 必须走 T3-01 审批流程（前端演示：弹确认框提示，确认后写入 pendingApproval）
   */
  function requestRelease(modelId: string, version: string): { ok: boolean; reason?: string } {
    if (!auth.can('model:release')) return { ok: false, reason: '无模型发布权限' }
    const m = getModel(modelId)
    if (!m) return { ok: false, reason: '模型不存在' }
    const ver = m.versions.find((x) => x.version === version)
    if (!ver) return { ok: false, reason: '版本不存在' }
    if (ver.status !== 'READY') {
      return { ok: false, reason: '仅 READY 状态的版本可发布（红线：非 DRAFT 不能发布）' }
    }
    // 红线提示：必须经 T3-01 审批流程
    activity.log(auth.user.name, `提交模型「${m.name}」v${version} 发布审批（T3-01）`, m.id)
    return { ok: true, reason: '模型发布需经 T3-01 审批流程' }
  }

  /** 审批通过后真正发布（演示用：审批通过回调） */
  function releaseModel(modelId: string, version: string, approver = 'T3-01 审批人') {
    const m = getModel(modelId)
    if (!m) return
    const ver = m.versions.find((x) => x.version === version)
    if (!ver || ver.status !== 'READY') return
    // 其他已发布版本回退
    m.versions.forEach((x) => {
      if (x.status === 'PUBLISHED') {
        x.status = 'READY'
        x.publishedAt = undefined
        x.approvedBy = undefined
      }
    })
    ver.status = 'PUBLISHED'
    ver.publishedAt = new Date().toISOString()
    ver.approvedBy = approver
    m.status = 'PUBLISHED'
    m.currentVersion = version
    m.updatedAt = new Date().toISOString()
    activity.log(auth.user.name, `模型「${m.name}」v${version} 审批通过并发布（审批人：${approver}）`, m.id)
  }

  function rollbackModel(modelId: string, version: string) {
    if (!auth.can('model:rollback')) throw new Error('无回滚权限')
    const m = getModel(modelId)
    if (!m) return
    const ver = m.versions.find((x) => x.version === version)
    if (!ver) return
    m.versions.forEach((x) => {
      if (x.status === 'PUBLISHED') {
        x.status = 'READY'
        x.publishedAt = undefined
        x.approvedBy = undefined
      }
    })
    ver.status = 'PUBLISHED'
    ver.publishedAt = new Date().toISOString()
    ver.approvedBy = '回滚操作'
    m.status = 'PUBLISHED'
    m.currentVersion = version
    m.updatedAt = new Date().toISOString()
    activity.log(auth.user.name, `模型「${m.name}」回滚至 v${version}`, m.id)
  }

  function deprecateModel(modelId: string) {
    const m = getModel(modelId)
    if (!m) return
    m.status = 'DEPRECATED'
    m.versions.forEach((x) => {
      if (x.status === 'PUBLISHED') x.status = 'DEPRECATED'
    })
    m.updatedAt = new Date().toISOString()
    activity.log(auth.user.name, `模型「${m.name}」已废弃`, m.id)
  }

  // ---- 种子 ----
  function seed() {
    if (loaded.value) return
    loaded.value = true
    const now = Date.now()
    const daysAgo = (d: number) => new Date(now - d * 86400_000).toISOString()

    const seed: Array<Partial<ModelRecord> & Pick<ModelRecord, 'name' | 'type' | 'owner' | 'department' | 'status'>> = [
      {
        name: '客户流失预测 v3', type: 'CLASSIFICATION', owner: '李明', department: '数据智能部',
        status: 'PUBLISHED', currentVersion: '3.2.0',
        description: '基于近 90 天到店/消费/互动行为预测 30 天内流失概率，输出高/中/低三档。',
        tags: ['客户运营', '流失预警', 'XGBoost'],
        callCount30d: 186420, avgLatencyMs: 42, errorRate: 0.18,
        inputSchema: '{\n  "customer_id": "string",\n  "features": { "recency_days": "int", "frequency_90d": "int", "monetary_90d": "float" }\n}',
        outputSchema: '{\n  "churn_prob": "float 0-1",\n  "level": "HIGH|MEDIUM|LOW",\n  "top_factors": "string[]"\n}',
        versions: [
          { version: '1.0.0', metrics: { auc: 0.78, accuracy: 0.82 }, status: 'DEPRECATED', trainedAt: daysAgo(180), publishedAt: daysAgo(170), approvedBy: '王审批', remark: '初版' },
          { version: '2.0.0', metrics: { auc: 0.83, accuracy: 0.85 }, status: 'DEPRECATED', trainedAt: daysAgo(120), publishedAt: daysAgo(110), approvedBy: '王审批' },
          { version: '3.2.0', metrics: { auc: 0.89, accuracy: 0.91, f1: 0.87 }, status: 'PUBLISHED', trainedAt: daysAgo(20), publishedAt: daysAgo(18), approvedBy: 'T3-01 审批人' },
        ],
        createdAt: daysAgo(200), updatedAt: daysAgo(18),
      },
      {
        name: '皮肤问题影像分类', type: 'CV', owner: '张医生', department: '皮肤科',
        status: 'PUBLISHED', currentVersion: '1.5.0',
        description: '对面诊皮肤影像自动归类（痤疮/色斑/敏感/老化/正常），辅助医师初筛。',
        tags: ['CV', '医疗影像', 'ResNet'],
        callCount30d: 42180, avgLatencyMs: 186, errorRate: 0.62,
        inputSchema: '{\n  "image_base64": "string",\n  "resolution": "string"\n}',
        outputSchema: '{\n  "label": "string",\n  "confidence": "float",\n  "candidates": "{label,confidence}[]"\n}',
        versions: [
          { version: '1.0.0', metrics: { top1: 0.81, top3: 0.94 }, status: 'DEPRECATED', trainedAt: daysAgo(90), publishedAt: daysAgo(80), approvedBy: '医务部' },
          { version: '1.5.0', metrics: { top1: 0.88, top3: 0.97 }, status: 'PUBLISHED', trainedAt: daysAgo(12), publishedAt: daysAgo(10), approvedBy: 'T3-01 审批人' },
        ],
        createdAt: daysAgo(100), updatedAt: daysAgo(10),
      },
      {
        name: '智能客服意图识别', type: 'NLP', owner: '陈晓', department: '客户体验部',
        status: 'READY',
        description: '识别企微/小程序客户咨询意图（预约/退款/投诉/咨询/复购），路由到对应坐席组。',
        tags: ['NLP', 'BERT', '客服'],
        callCount30d: 0, avgLatencyMs: 0, errorRate: 0,
        inputSchema: '{\n  "text": "string",\n  "channel": "WECHAT|APP|WEB"\n}',
        outputSchema: '{\n  "intent": "string",\n  "confidence": "float",\n  "slots": "Record<string,string>"\n}',
        versions: [
          { version: '0.9.0', metrics: { f1: 0.91, accuracy: 0.93 }, status: 'READY', trainedAt: daysAgo(3), remark: '待 T3-01 审批发布' },
        ],
        createdAt: daysAgo(30), updatedAt: daysAgo(3),
      },
      {
        name: '项目疗程推荐', type: 'RECOMMEND', owner: '王悦', department: '营销中心',
        status: 'TRAINING',
        description: '基于客户画像 + 历史消费，推荐 Top3 高转化项目疗程，支撑导购侧栏。',
        tags: ['推荐', 'DeepFM', '营销'],
        callCount30d: 0, avgLatencyMs: 0, errorRate: 0,
        inputSchema: '{\n  "customer_id": "string",\n  "context": { "channel": "string", "store_id": "string" }\n}',
        outputSchema: '{\n  "items": "{item_id,score,reason}[]"\n}',
        versions: [
          { version: '2.1.0', metrics: {}, status: 'TRAINING', trainedAt: daysAgo(1), remark: '训练中，预计 4h 后完成' },
        ],
        createdAt: daysAgo(45), updatedAt: daysAgo(1),
      },
      {
        name: '门店销量预测', type: 'REGRESSION', owner: '赵磊', department: '数据智能部',
        status: 'PUBLISHED', currentVersion: '1.2.0',
        description: '按门店/SKU 预测未来 7/14/30 天销量，驱动采购与排班。',
        tags: ['时序', 'Prophet', '供应链'],
        callCount30d: 9820, avgLatencyMs: 68, errorRate: 0.05,
        inputSchema: '{\n  "store_id": "string",\n  "sku_id": "string",\n  "horizon_days": "int"\n}',
        outputSchema: '{\n  "dates": "string[]",\n  "yhat": "float[]",\n  "yhat_lower": "float[]",\n  "yhat_upper": "float[]"\n}',
        versions: [
          { version: '1.2.0', metrics: { mape: 0.084, rmse: 12.4 }, status: 'PUBLISHED', trainedAt: daysAgo(14), publishedAt: daysAgo(12), approvedBy: 'T3-01 审批人' },
        ],
        createdAt: daysAgo(60), updatedAt: daysAgo(12),
      },
      {
        name: '营销文案生成', type: 'GENERATIVE', owner: '孙琳', department: '营销中心',
        status: 'DRAFT',
        description: '基于活动主题/客户分群生成朋友圈/企微/短信多版本文案（合规词过滤）。',
        tags: ['LLM', 'AIGC', '营销'],
        callCount30d: 0, avgLatencyMs: 0, errorRate: 0,
        inputSchema: '{\n  "topic": "string",\n  "segment": "string",\n  "channel": "WECHAT|MOMENTS|SMS"\n}',
        outputSchema: '{\n  "variants": "string[]",\n  "compliance_passed": "bool"\n}',
        versions: [],
        createdAt: daysAgo(5), updatedAt: daysAgo(5),
      },
    ]

    seed.forEach((s) => {
      models.value.push({
        id: nextId('mdl'),
        name: s.name!,
        type: s.type!,
        description: s.description ?? '',
        owner: s.owner!,
        department: s.department!,
        tags: s.tags ?? [],
        versions: s.versions ?? [],
        currentVersion: s.currentVersion,
        status: s.status!,
        inputSchema: s.inputSchema ?? '{}',
        outputSchema: s.outputSchema ?? '{}',
        callCount30d: s.callCount30d ?? 0,
        avgLatencyMs: s.avgLatencyMs ?? 0,
        errorRate: s.errorRate ?? 0,
        createdAt: s.createdAt ?? daysAgo(30),
        updatedAt: s.updatedAt ?? new Date().toISOString(),
      })
    })
  }

  return {
    models, publishedModels, kpi,
    MODEL_TYPE_LABEL, MODEL_STATUS_LABEL,
    getModel, can,
    registerModel, addVersion, requestRelease, releaseModel, rollbackModel, deprecateModel,
    seed,
  }
})
