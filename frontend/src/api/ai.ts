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
  bindingId: number
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

export interface ApplyCmd {
  approvalType: string
  targetId?: number | null
  content: string
}

export function applyApproval(cmd: ApplyCmd): Promise<ApprovalView> {
  return client.post('/ai/approvals', cmd).then((r) => r.data)
}

// -------------------- 调用配额（B44） --------------------

export interface QuotaCmd {
  dailyLimit: number | null
  monthlyLimit: number | null
  enabled: boolean
}

export interface QuotaView {
  quotaId: number | null
  quotaScope: string
  targetCode: string
  targetName: string
  dailyLimit: number | null
  monthlyLimit: number | null
  dailyUsed: number
  monthlyUsed: number
  enabled: boolean
  updatedBy: string | null
  updatedAt: string | null
}

export interface QuotaSaveResult {
  changed: boolean
  quotaId: number | null
  quotaScope: string
  targetCode: string
}

export function listQuotas(): Promise<QuotaView[]> {
  return client.get('/ai/quotas').then((r) => r.data)
}

// target 走 query（GLOBAL 目标为 *，含斜杠/星号不宜放 path）
export function saveQuota(scope: string, target: string, cmd: QuotaCmd): Promise<QuotaSaveResult> {
  return client
    .post(`/ai/quotas/${scope}/save`, cmd, { params: { target } })
    .then((r) => r.data)
}

// -------------------- 监控告警（B44） --------------------

export interface AlertView {
  ruleId: number
  ruleCode: string
  ruleName: string
  metric: string
  compareOp: string
  thresholdNum: number
  windowMinutes: number
  notifyChannel: string
  enabled: boolean
  active: boolean
  currentValue: number
  status: string
}

export function listAlerts(): Promise<AlertView[]> {
  return client.get('/ai/alerts').then((r) => r.data)
}

// -------------------- 效果评估与 A/B 实验（B45） --------------------

export interface EvalMetrics {
  calls: number
  successCalls: number
  successRate: number
  p99LatencyMs: number | null
  tokens: number
  costFen: number
}

export interface EvalView {
  taskId: number
  taskName: string
  evalScope: string
  targetCode: string
  targetName: string
  windowDays: number
  periodStart: string
  periodEnd: string
  metrics: EvalMetrics
  status: string
  conclusion: string | null
  createdBy: string | null
  createdAt: string | null
  updatedAt: string | null
}

export interface EvalCreateCmd {
  taskName: string
  evalScope: string
  targetCode: string
  windowDays: number
}

export interface ExperimentView {
  experimentId: number
  experimentName: string
  controlModel: string
  experimentModel: string
  windowDays: number
  periodStart: string
  periodEnd: string
  controlMetrics: EvalMetrics
  experimentMetrics: EvalMetrics
  liftPp: number | null
  status: string
  conclusion: string | null
  createdBy: string | null
  createdAt: string | null
  updatedAt: string | null
}

export interface ExperimentCreateCmd {
  experimentName: string
  controlModel: string
  experimentModel: string
  windowDays: number
}

export interface EvalStats {
  runningExperiments: number
  avgLiftPp: number | ''
}

export function listEvals(): Promise<EvalView[]> {
  return client.get('/ai/evals').then((r) => r.data)
}

export function createEval(cmd: EvalCreateCmd): Promise<EvalView> {
  return client.post('/ai/evals', cmd).then((r) => r.data)
}

export function refreshEval(id: number): Promise<EvalView> {
  return client.post(`/ai/evals/${id}/refresh`).then((r) => r.data)
}

export function concludeEval(id: number, conclusion: string): Promise<EvalView> {
  return client.post(`/ai/evals/${id}/conclude`, { conclusion }).then((r) => r.data)
}

export function listExperiments(): Promise<ExperimentView[]> {
  return client.get('/ai/experiments').then((r) => r.data)
}

export function createExperiment(cmd: ExperimentCreateCmd): Promise<ExperimentView> {
  return client.post('/ai/experiments', cmd).then((r) => r.data)
}

export function refreshExperiment(id: number): Promise<ExperimentView> {
  return client.post(`/ai/experiments/${id}/refresh`).then((r) => r.data)
}

export function concludeExperiment(id: number, conclusion: string): Promise<ExperimentView> {
  return client.post(`/ai/experiments/${id}/conclude`, { conclusion }).then((r) => r.data)
}

export function getEvalStats(): Promise<EvalStats> {
  return client.get('/ai/eval-stats').then((r) => r.data)
}

// -------------------- 敏感词治理（B46 卡1） --------------------

export interface SensitiveWordCmd {
  word: string
  category: string
  enabled: boolean
}

export interface SensitiveWordView {
  wordId: number
  word: string
  category: string
  enabled: boolean
  hits: number
  createdAt: string | null
  updatedAt: string | null
}

export interface SensitiveHitView {
  hitId: number
  hitAt: string | null
  wordId: number | null
  word: string
  category: string
  featureCode: string | null
  featureName: string
  staffName: string | null
  storeCode: string | null
  contextSnippet: string | null
  falsePositive: boolean
  markedBy: string | null
  markedAt: string | null
}

export interface SensitiveHitStats {
  todayHits: number
  totalHits: number
  falsePositiveHits: number
  totalWords: number
  enabledWords: number
}

export interface SensitiveSaveResult {
  changed: boolean
  wordId: number
}

export function listSensitiveWords(): Promise<SensitiveWordView[]> {
  return client.get('/ai/sensitive/words').then((r) => r.data)
}

export function createSensitiveWord(cmd: SensitiveWordCmd): Promise<SensitiveSaveResult> {
  return client.post('/ai/sensitive/words', cmd).then((r) => r.data)
}

export function updateSensitiveWord(id: number, cmd: SensitiveWordCmd): Promise<SensitiveSaveResult> {
  return client.post(`/ai/sensitive/words/${id}`, cmd).then((r) => r.data)
}

export function listSensitiveHits(params: { category?: string; page: number; size: number }): Promise<PageResult<SensitiveHitView>> {
  return client.get('/ai/sensitive/hits', { params }).then((r) => r.data)
}

export function getSensitiveHitStats(): Promise<SensitiveHitStats> {
  return client.get('/ai/sensitive/hits/stats').then((r) => r.data)
}

export function markSensitiveHitFalsePositive(id: number): Promise<SensitiveSaveResult> {
  return client.post(`/ai/sensitive/hits/${id}/mark-fp`).then((r) => r.data)
}

// -------------------- 渠道内容生成（B46 卡3） --------------------

export interface ContentCmd {
  channel: string
  topic: string
  storeCode?: string | null
}

export interface ContentView {
  recordId: number
  channel: string
  topic: string
  title: string
  content: string
  invokeLogId: number | null
  modelCode: string | null
  totalTokens: number | null
  costFen: number
  status: string
  deployedAt: string | null
  deployedBy: string | null
  staffId: string | null
  staffName: string | null
  storeCode: string | null
  createdAt: string | null
}

export interface ContentStats {
  todayGenerated: number
  totalGenerated: number
  todayDeployed: number
  totalDeployed: number
  todayBlocked: number
  adoptRatePct: number
}

export interface ContentDeployResult {
  changed: boolean
  recordId: number
  status: string
}

// 完整推文/海报 completion 可达 2000+ tokens，实测 40~140s，超时放宽到 180s（对齐后端出站读超时）
export function generateContent(cmd: ContentCmd): Promise<ContentView> {
  return client.post('/ai/content/generate', cmd, { timeout: 180000 }).then((r) => r.data)
}

export function listContentRecords(params: { channel?: string; page: number; size: number }): Promise<PageResult<ContentView>> {
  return client.get('/ai/content/records', { params }).then((r) => r.data)
}

export function getContentStats(): Promise<ContentStats> {
  return client.get('/ai/content/stats').then((r) => r.data)
}

export function deployContent(id: number): Promise<ContentDeployResult> {
  return client.post(`/ai/content/records/${id}/deploy`).then((r) => r.data)
}

// -------------------- 智能话术库（B46 卡4） --------------------

export type ScriptScene = 'icebreak' | 'upsell' | 'objection'

export interface ScriptView {
  scriptId: number
  scene: ScriptScene
  title: string
  content: string
  source: string
  invokeLogId: number | null
  modelCode: string | null
  rating: number
  adoptedCount: number
  feedbackCount: number
  staffId: string | null
  staffName: string | null
  storeCode: string | null
  createdAt: string | null
}

export interface ScriptStats {
  totalScripts: number
  todayCalls: number
  adoptRatePct: number
  goodRatePct: number
}

export interface ScriptGenView {
  content: string
  invokeLogId: number | null
  modelCode: string | null
  totalTokens: number | null
  costFen: number
}

export interface ScriptActionResult {
  scriptId: number
  adoptedCount: number
  feedbackCount: number
}

export interface ScriptSaveCmd {
  scene: ScriptScene
  title: string
  content: string
  invokeLogId?: number | null
  modelCode?: string | null
}

export function listScripts(params: { scene?: string; keyword?: string; page: number; size: number }): Promise<PageResult<ScriptView>> {
  return client.get('/ai/scripts', { params }).then((r) => r.data)
}

export function getScriptStats(): Promise<ScriptStats> {
  return client.get('/ai/scripts/stats').then((r) => r.data)
}

// 话术生成可达 300 字、实测数十秒，超时对齐后端出站读超时 180s
export function generateScript(cmd: { scene: ScriptScene; topic: string }): Promise<ScriptGenView> {
  return client.post('/ai/scripts/generate', cmd, { timeout: 180000 }).then((r) => r.data)
}

export function createScript(cmd: ScriptSaveCmd): Promise<ScriptView> {
  return client.post('/ai/scripts', cmd).then((r) => r.data)
}

export function updateScript(id: number, cmd: ScriptSaveCmd): Promise<ScriptView> {
  return client.post(`/ai/scripts/${id}`, cmd).then((r) => r.data)
}

export function adoptScript(id: number): Promise<ScriptActionResult> {
  return client.post(`/ai/scripts/${id}/adopt`).then((r) => r.data)
}

export function feedbackScript(id: number): Promise<ScriptActionResult> {
  return client.post(`/ai/scripts/${id}/feedback`).then((r) => r.data)
}

// -------------------- 客户画像引擎（B47 卡1） --------------------

export interface ProfileCmd {
  keyword?: string | null
  customerId?: string | null
  storeCode?: string | null
}

export interface ProfileTag {
  label: string
  status: string
}

export interface ProfileView {
  profileId: number
  customerId: string
  customerName: string
  phone: string
  level: string
  valueScore: number
  groups: string[]
  tags: ProfileTag[]
  invokeLogId: number | null
  modelCode: string | null
  totalTokens: number | null
  costFen: number
  appliedToSegment: boolean
  staffId: string | null
  staffName: string | null
  storeCode: string | null
  createdAt: string | null
}

export interface ProfileCandidate {
  customerId: string
  name: string
  phone: string
  level: string
  hasProfile: boolean
  profileId: number | null
  profileCreatedAt: string | null
}

export interface ProfileStats {
  coveredCustomers: number
  tagTotal: number
  totalInvokes: number
  weekInvokes: number
  appliedSegments: number
  todayInvokes: number
}

export interface ProfileWeight {
  feature: string
  weight: number
  direction: string
  shap: number
}

export interface ProfileWeightModel {
  modelVersion: string
  note: string
  rows: ProfileWeight[]
}

export interface ProfileWeekAccuracy {
  week: string
  weekStart: string | null
  weekEnd: string | null
  calls: number | null
  successRate: number | null
}

export interface ProfileReview {
  weeks: ProfileWeekAccuracy[]
  calls: number | null
  avgSuccessRate: number | null
  statusNote: string
}

export interface ProfileApplyResult {
  changed: boolean
  profileId: number
  appliedToSegment: boolean
}

export function searchProfileCandidates(keyword: string): Promise<ProfileCandidate[]> {
  return client.get('/ai/profile/search', { params: { keyword } }).then((r) => r.data)
}

// 画像生成走真实大模型出站，completion 耗时较长，超时对齐后端出站读超时 180s
export function generateProfile(cmd: ProfileCmd): Promise<ProfileView> {
  return client.post('/ai/profile/generate', cmd, { timeout: 180000 }).then((r) => r.data)
}

export function getLatestProfile(customerId: string): Promise<ProfileView> {
  return client.get('/ai/profile/latest', { params: { customerId } }).then((r) => r.data)
}

export function getProfileStats(): Promise<ProfileStats> {
  return client.get('/ai/profile/stats').then((r) => r.data)
}

export function getProfileWeights(): Promise<ProfileWeightModel> {
  return client.get('/ai/profile/weights').then((r) => r.data)
}

export function getProfileReview(): Promise<ProfileReview> {
  return client.get('/ai/profile/review').then((r) => r.data)
}

export function applyProfileToSegment(id: number): Promise<ProfileApplyResult> {
  return client.post(`/ai/profile/${id}/apply`).then((r) => r.data)
}

// -------------------- 复购预测引擎（B47 卡2） --------------------

export interface RepurchaseCmd {
  period?: string | null
  storeCode?: string | null
  limit?: number | null
}

export interface RepurchaseRow {
  predictionId: number
  customerId: string
  customerName: string
  phone: string
  level: string
  projectCode: string
  projectName: string
  timing: string
  prob: number
  expectedAmountFen: number
  avgTicketFen: number
  recencyDays: number | null
  avgIntervalDays: number | null
  cardBalanceFen: number | null
  followupRegistered: boolean
  pushRegistered: boolean
  invokeLogId: number | null
  modelCode: string | null
  createdAt: string | null
}

export interface RepurchaseBatch {
  batchNo: string
  period: string
  horizonDays: number
  size: number
  avgProb: number
  expectedTotalFen: number
  followupRegistered: number
  storeCode: string | null
  createdAt: string | null
}

export interface RepurchaseStats {
  predictedCustomers: number
  avgProb: number
  expectedTotalFen: number
  expectedNote: string
  followupTotal: number
  weekInvokes: number
  trendNote: string
  modelVersion: string
  ran: boolean
}

export interface RepurchaseFactor {
  rank: number
  title: string
  desc: string
  weight: number
  available: boolean
  unavailableNote: string | null
}

export interface RepurchaseFactorModel {
  modelVersion: string
  note: string
  rows: RepurchaseFactor[]
}

export interface RepurchaseActionResult {
  changed: boolean
  predictionId: number
  action: string
}

export interface RepurchaseBatchResult {
  batchNo: string
  affected: number
  changed: boolean
}

// 运行预测按候选客户逐人真实模型 invoke，耗时较长，超时对齐后端出站读超时 180s
export function runRepurchase(cmd: RepurchaseCmd): Promise<RepurchaseBatch> {
  return client.post('/ai/repurchase/run', cmd, { timeout: 180000 }).then((r) => r.data)
}

export function listRepurchase(period: string, projectCode?: string): Promise<RepurchaseRow[]> {
  return client.get('/ai/repurchase/list', { params: { period, projectCode } }).then((r) => r.data)
}

export function getRepurchaseBatch(period: string): Promise<RepurchaseBatch> {
  return client.get('/ai/repurchase/batch', { params: { period } }).then((r) => r.data)
}

export function getRepurchaseStats(period: string): Promise<RepurchaseStats> {
  return client.get('/ai/repurchase/stats', { params: { period } }).then((r) => r.data)
}

export function getRepurchaseFactors(): Promise<RepurchaseFactorModel> {
  return client.get('/ai/repurchase/factors').then((r) => r.data)
}

export function registerRepurchaseFollowup(id: number): Promise<RepurchaseActionResult> {
  return client.post(`/ai/repurchase/${id}/followup`).then((r) => r.data)
}

export function registerRepurchasePush(id: number): Promise<RepurchaseActionResult> {
  return client.post(`/ai/repurchase/${id}/push`).then((r) => r.data)
}

export function batchRepurchaseFollowup(period: string): Promise<RepurchaseBatchResult> {
  return client.post('/ai/repurchase/batch-followup', null, { params: { period } }).then((r) => r.data)
}

// -------------------- 流失预警引擎（B47 卡3） --------------------

export interface ChurnCmd {
  storeCode?: string | null
  limit?: number | null
}

export interface ChurnRow {
  predictionId: number
  customerId: string
  customerName: string
  phone: string
  level: string
  riskLevel: string
  score: number
  keyFactor: string
  suggestedAction: string
  lastVisitDate: string
  recencyDays: number | null
  spendDeclinePct: number | null
  cardBalanceFen: number | null
  interveneRegistered: boolean
  invokeLogId: number | null
  modelCode: string | null
  createdAt: string | null
}

export interface ChurnBatch {
  batchNo: string
  size: number
  highCount: number
  midCount: number
  avgScore: number
  storeCode: string | null
  createdAt: string | null
}

export interface ChurnStats {
  scoredCustomers: number
  highCount: number
  midCount: number
  interveneTotal: number
  weekInvokes: number
  modelVersion: string
  modelNote: string
  ran: boolean
}

export interface ChurnFactor {
  rank: number
  title: string
  desc: string
  weight: number
  available: boolean
  unavailableNote: string | null
}

export interface ChurnFactorModel {
  modelVersion: string
  note: string
  rows: ChurnFactor[]
}

export interface ChurnActionResult {
  changed: boolean
  predictionId: number
  action: string
}

export interface ChurnBatchResult {
  batchNo: string
  affected: number
  changed: boolean
}

// 运行评分按候选客户逐人真实模型 invoke，耗时较长，超时对齐后端出站读超时 180s
export function runChurn(cmd: ChurnCmd): Promise<ChurnBatch> {
  return client.post('/ai/churn/run', cmd, { timeout: 180000 }).then((r) => r.data)
}

export function listChurn(riskLevel?: string): Promise<ChurnRow[]> {
  return client.get('/ai/churn/list', { params: { riskLevel } }).then((r) => r.data)
}

export function getChurnBatch(): Promise<ChurnBatch> {
  return client.get('/ai/churn/batch').then((r) => r.data)
}

export function getChurnStats(): Promise<ChurnStats> {
  return client.get('/ai/churn/stats').then((r) => r.data)
}

export function getChurnFactors(): Promise<ChurnFactorModel> {
  return client.get('/ai/churn/factors').then((r) => r.data)
}

export function registerChurnIntervene(id: number): Promise<ChurnActionResult> {
  return client.post(`/ai/churn/${id}/intervene`).then((r) => r.data)
}

export function batchChurnIntervene(): Promise<ChurnBatchResult> {
  return client.post('/ai/churn/batch-intervene').then((r) => r.data)
}
