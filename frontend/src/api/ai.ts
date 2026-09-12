// ============================================================
// AI 中心 API（对接 ai-service，经网关 /api/ai 前缀）
// B42：供应商/模型接入、功能绑定灰度、调用日志/KPI、全局配置、审批。
// Key 安全：API 密钥仅写入，回显只有掩码（apiKeyMask）；更新时留空或含 * 表示不改。
// 金额口径：inputPrice/outputPrice 为「元/百万 token」；costFen 为「分」。
// ============================================================
import client from './client'

// -------------------- 供应商 --------------------

export interface ProviderCmd {
  providerCode: string
  providerName: string
  baseUrl: string
  apiKey: string
  protocol: string
  enabled: boolean
}

export interface ProviderView {
  providerId: number
  providerCode: string
  providerName: string
  baseUrl: string
  apiKeyMask: string | null
  hasApiKey: boolean
  protocol: string
  enabled: boolean
  modelCount: number
  updatedAt: string | null
}

export interface ProviderSaveResult {
  changed: boolean
  providerId: number
}

export interface ChangedResult {
  changed: boolean
}

export function listProviders(): Promise<ProviderView[]> {
  return client.get('/ai/providers').then((r) => r.data)
}

export function createProvider(cmd: ProviderCmd): Promise<ProviderSaveResult> {
  return client.post('/ai/providers', cmd).then((r) => r.data)
}

export function updateProvider(id: number, cmd: ProviderCmd): Promise<ProviderSaveResult> {
  return client.post(`/ai/providers/${id}`, cmd).then((r) => r.data)
}

export function deleteProvider(id: number): Promise<ChangedResult> {
  return client.post(`/ai/providers/${id}/delete`).then((r) => r.data)
}

// -------------------- 模型 --------------------

export interface ModelCmd {
  providerId: number
  modelCode: string
  displayName: string
  capabilities: string
  contextWindow: number | null
  temperature: number | null
  topP: number | null
  maxTokens: number | null
  priority: number | null
  enabled: boolean
  inputPrice: number | null
  outputPrice: number | null
}

export interface ModelView {
  modelId: number
  providerId: number
  providerCode: string | null
  providerName: string | null
  modelCode: string
  displayName: string
  capabilities: string
  contextWindow: number | null
  temperature: number | null
  topP: number | null
  maxTokens: number | null
  priority: number | null
  enabled: boolean
  connStatus: string
  connMessage: string | null
  connCheckedAt: string | null
  inputPrice: number | null
  outputPrice: number | null
  updatedAt: string | null
}

export interface ModelSaveResult {
  changed: boolean
  modelId: number
}

export interface ModelTestResult {
  success: boolean
  connStatus: string
  message: string
  replySnippet: string | null
  latencyMs: number
  totalTokens: number
  checkedAt: string | null
}

export function listModels(providerId?: number | null): Promise<ModelView[]> {
  return client.get('/ai/models', { params: providerId ? { providerId } : {} }).then((r) => r.data)
}

export function createModel(cmd: ModelCmd): Promise<ModelSaveResult> {
  return client.post('/ai/models', cmd).then((r) => r.data)
}

export function updateModel(id: number, cmd: ModelCmd): Promise<ModelSaveResult> {
  return client.post(`/ai/models/${id}`, cmd).then((r) => r.data)
}

export function deleteModel(id: number): Promise<ChangedResult> {
  return client.post(`/ai/models/${id}/delete`).then((r) => r.data)
}

// 真实出网调用供应商，超时放宽到 60s（默认 client 为 10s）
export function testModel(id: number): Promise<ModelTestResult> {
  return client.post(`/ai/models/${id}/test`, {}, { timeout: 60000 }).then((r) => r.data)
}

// -------------------- 功能绑定 × 角色灰度 --------------------

export interface BindingCmd {
  modelId: number | null
  storeScope: string
  storeCodes: string | null
  promptTemplate: string | null
  paramOverrides: string | null
  enabled: boolean
  requireApproval: boolean
}

export interface BindingView {
  featureCode: string
  featureName: string
  modelId: number | null
  modelCode: string | null
  modelDisplayName: string | null
  storeScope: string | null
  storeCodes: string | null
  promptTemplate: string | null
  paramOverrides: string | null
  enabled: boolean
  requireApproval: boolean
  updatedBy: string | null
  updatedAt: string | null
  roles: Record<string, boolean>
}

export interface FeatureSaveResult {
  changed: boolean
  featureCode: string
}

export function listFeatures(): Promise<BindingView[]> {
  return client.get('/ai/features').then((r) => r.data)
}

export function saveFeatureBinding(featureCode: string, cmd: BindingCmd): Promise<FeatureSaveResult> {
  return client.post(`/ai/features/${featureCode}/binding`, cmd).then((r) => r.data)
}

export function saveFeatureRoles(featureCode: string, roles: Record<string, boolean>): Promise<FeatureSaveResult> {
  return client.post(`/ai/features/${featureCode}/roles`, roles).then((r) => r.data)
}

// -------------------- 功能真实调用（B43） --------------------

export interface InvokeCmd {
  input: string
  storeCode?: string | null
}

export interface InvokeView {
  success: boolean
  featureCode: string
  featureName: string
  providerCode: string | null
  modelCode: string | null
  content: string | null
  promptTokens: number | null
  completionTokens: number | null
  totalTokens: number | null
  latencyMs: number | null
  costFen: number | null
  errorCode: string | null
}

// 真实出网调用大模型，超时放宽到 60s（默认 client 为 10s）
export function invokeFeature(featureCode: string, cmd: InvokeCmd): Promise<InvokeView> {
  return client.post(`/ai/features/${featureCode}/invoke`, cmd, { timeout: 60000 }).then((r) => r.data)
}

// -------------------- 调用日志 / KPI / 账单 --------------------

export interface AiLogView {
  logId: number
  invokedAt: string
  staffId: string | null
  staffName: string | null
  storeCode: string | null
  featureCode: string | null
  featureName: string | null
  providerCode: string | null
  modelCode: string | null
  promptSnippet: string | null
  outputSnippet: string | null
  promptTokens: number | null
  completionTokens: number | null
  totalTokens: number | null
  latencyMs: number | null
  success: boolean
  errorCode: string | null
  costFen: number
}

export interface FeatureBill {
  featureCode: string | null
  featureName: string | null
  calls: number
  tokens: number
  costFen: number
}

export interface AiKpi {
  todayCalls: number
  successRate: number
  p99LatencyMs: number | null
  activeAlerts: number
  featureCount: number
  enabledFeatureCount: number
  monthCalls: number
  totalCostFen: number
  modelCount: number
  pendingApprovals: number
  monthApproved: number
}

export interface PageResult<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

export interface LogQuery {
  featureCode?: string
  success?: boolean | null
  page?: number
  size?: number
}

export function searchLogs(query: LogQuery): Promise<PageResult<AiLogView>> {
  return client.get('/ai/logs', {
    params: {
      featureCode: query.featureCode || undefined,
      success: query.success === null || query.success === undefined ? undefined : query.success,
      page: query.page ?? 0,
      size: query.size ?? 20,
    },
  }).then((r) => r.data)
}

export function monthlyBill(): Promise<FeatureBill[]> {
  return client.get('/ai/logs/bill').then((r) => r.data)
}

export function logKpi(): Promise<AiKpi> {
  return client.get('/ai/logs/kpi').then((r) => r.data)
}

// -------------------- 全局配置 --------------------

export interface ModelOption {
  modelId: number
  label: string
}

export interface CfgView {
  defaultModelId: number | null
  grayScale: number
  retentionMonths: number
  sensitiveCheck: boolean
  explainability: boolean
  autoAudit: boolean
  updatedBy: string | null
  updatedAt: string | null
  modelOptions: ModelOption[]
}

export interface CfgCmd {
  defaultModelId: number | null
  grayScale: number
  retentionMonths: number
  sensitiveCheck: boolean
  explainability: boolean
  autoAudit: boolean
}

export function getCfg(): Promise<CfgView> {
  return client.get('/ai/cfg').then((r) => r.data)
}

export function saveCfg(cmd: CfgCmd): Promise<ChangedResult> {
  return client.post('/ai/cfg', cmd).then((r) => r.data)
}

// -------------------- 审批 --------------------

export interface ApprovalView {
  approvalId: number
  approvalType: string
  targetId: number | null
  content: string
  applicant: string | null
  appliedAt: string | null
  decidedBy: string | null
  decidedAt: string | null
  status: string
  opinion: string | null
}

export interface DecideCmd {
  approved: boolean
  opinion: string
}

export interface ApprovalQuery {
  status?: string
  page?: number
  size?: number
}

export function listApprovals(query: ApprovalQuery): Promise<PageResult<ApprovalView>> {
  return client.get('/ai/approvals', {
    params: {
      status: query.status || undefined,
      page: query.page ?? 0,
      size: query.size ?? 20,
    },
  }).then((r) => r.data)
}

export function decideApproval(id: number, cmd: DecideCmd): Promise<ApprovalView> {
  return client.post(`/ai/approvals/${id}/decide`, cmd).then((r) => r.data)
}
