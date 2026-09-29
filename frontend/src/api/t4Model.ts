// ============================================================
// T4 AI 中台底座 - 模型仓库 API 薄封装（T4-卡1 切真）
// ai-service T4ModelController（/api/ai/t4/models）
// 类级 model:view；注册=model:register，版本=model:version，
// 发布申请/废弃=model:release，回滚=model:rollback（零新码）
// 红线：非 READY 禁发；发布走审批中心 T4_MODEL 单（服务端代建，
// 前端零权限绕行），审批通过由 ApprovalService 联动发布
// ============================================================
import client from './client'

/** 版本视图（对齐后端 VersionView；OffsetDateTime 序列化 ISO 串） */
export interface VersionView {
  version: string
  metrics: Record<string, number>
  status: string
  trainedAt: string
  publishedAt: string | null
  approvedBy: string | null
  remark: string | null
}

/** 模型视图（对齐后端 ModelView；code 为业务主键，前端适配层作 id） */
export interface ModelView {
  code: string
  name: string
  type: string
  description: string
  owner: string
  department: string
  tags: string[]
  versions: VersionView[]
  currentVersion: string | null
  status: string
  inputSchema: string
  outputSchema: string
  callCount30d: number
  avgLatencyMs: number
  errorRate: number
  createdAt: string
  updatedAt: string
}

/** 注册请求体（对齐后端 RegisterCmd；code 服务端自动生成） */
export interface RegisterCmd {
  name: string
  type: string
  description: string
  owner: string
  department: string
  tags: string[]
  inputSchema: string
  outputSchema: string
}

/** 版本上传请求体（对齐后端 VersionCmd；起始状态仅 DRAFT/TRAINING/READY） */
export interface VersionCmd {
  version: string
  metrics: Record<string, number>
  status: string
  remark?: string
}

/** 发布申请结果（对齐后端 ReleaseResult；message=「模型发布需经 T3-01 审批流程」） */
export interface ReleaseResult {
  approvalId: number
  message: string
}

const BASE = '/ai/t4/models'

export function listModels(): Promise<ModelView[]> {
  return client.get(BASE).then((r) => r.data)
}

export function registerModel(cmd: RegisterCmd): Promise<ModelView> {
  return client.post(BASE, cmd).then((r) => r.data)
}

export function addVersion(code: string, cmd: VersionCmd): Promise<ModelView> {
  return client.post(`${BASE}/${code}/versions`, cmd).then((r) => r.data)
}

/** 发布申请（仅 READY 可发；服务端代建审批中心 T4_MODEL 单） */
export function requestRelease(code: string, version: string): Promise<ReleaseResult> {
  return client.post(`${BASE}/${code}/release-request`, { version }).then((r) => r.data)
}

export function rollbackModel(code: string, version: string): Promise<ModelView> {
  return client.post(`${BASE}/${code}/rollback`, { version }).then((r) => r.data)
}

export function deprecateModel(code: string): Promise<ModelView> {
  return client.post(`${BASE}/${code}/deprecate`).then((r) => r.data)
}
