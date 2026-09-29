// ============================================================
// T4 AI 中台底座 - 算力管理 API 薄封装（T4-卡2 切真）
// ai-service T4ComputeController（/api/ai/t4/compute）
// 类级 compute:view；配额分配=compute:alloc，GPU 状态=compute:edit（零新码）
// 成本模拟 simulateCost / GPU_MODEL_PRICE 为纯前端逻辑，不落库
// ============================================================
import client from './client'

/** GPU 节点视图（对齐后端 GpuView；code 为业务主键，前端适配层作 id） */
export interface GpuView {
  code: string
  name: string
  model: string
  vramTotal: number
  vramUsed: number
  utilization: number
  temperature: number
  status: string
  currentTask: string | null
  podName: string | null
  costPerHour: number
  region: string
  createdAt: string
  updatedAt: string
}

/** 配额视图（对齐后端 QuotaView；code 为业务主键，前端适配层作 id） */
export interface QuotaView {
  code: string
  department: string
  project: string
  gpuHours: number
  gpuHoursUsed: number
  budget: number
  spent: number
  period: string
  status: string
  createdAt: string
  updatedAt: string
}

/** 总览响应（对齐后端 ComputeView：GPU 节点 + 部门配额 双数组） */
export interface ComputeView {
  gpus: GpuView[]
  quotas: QuotaView[]
}

/** 配额分配请求体（对齐后端 AllocateCmd；code 服务端自动生成 quota- 前缀） */
export interface AllocateCmd {
  department: string
  project: string
  gpuHours: number
  budget: number
  period: string
}

const BASE = '/ai/t4/compute'

export function overview(): Promise<ComputeView> {
  return client.get(BASE).then((r) => r.data)
}

export function allocateQuota(cmd: AllocateCmd): Promise<QuotaView> {
  return client.post(`${BASE}/quotas`, cmd).then((r) => r.data)
}

export function updateGpuStatus(code: string, status: string): Promise<GpuView> {
  return client.post(`${BASE}/gpus/${code}/status`, { status }).then((r) => r.data)
}
