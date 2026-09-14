// ============================================================
// SOP 标准作业流程 API（B49 卡6）：流程模板库 + 门店执行任务 + 步骤勾选。
// 网关 /api/stores → store-service:8085，类路径 /api/stores/sop。
// 模板为集团级流程库（不分门店，DRAFT → PUBLISHED 发布版本末位 +1）；
// 任务按数据域收敛（不传 storeCode 返可见门店全集）；
// OVERDUE 由后端 DTO 组装派生（未完成且截止日早于今日），不落库；
// id 适配层：模板 S%02d / 任务 TK%02d / 步骤 's'+序号，入参反向 strip。
// ============================================================
import client from './client'

export type SopCategoryDTO = 'MEDICAL' | 'SERVICE' | 'SAFETY' | 'HYGIENE' | 'MANAGEMENT' | 'TRAINING'
export type SopStatusDTO = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'
export type SopTaskStatusDTO = 'PENDING' | 'IN_PROGRESS' | 'DONE' | 'OVERDUE'
export type SopPriorityDTO = 'HIGH' | 'MEDIUM' | 'LOW'

/** SOP 步骤行（模板 steps[]）。 */
export interface SopStepDTO {
  id: string
  title: string
  desc: string | null
  requirePhoto: boolean
}

/** SOP 模板行（GET /stores/sop/templates）。 */
export interface SopTemplateDTO {
  id: string
  code: string
  title: string
  category: SopCategoryDTO
  version: string
  status: SopStatusDTO
  owner: string
  updatedAt: string
  applicableStores: string[]
  steps: SopStepDTO[]
}

/** SOP 任务行（GET /stores/sop/tasks）；status 为派生态（含 OVERDUE）。 */
export interface SopTaskDTO {
  id: string
  templateId: string
  templateTitle: string
  category: SopCategoryDTO
  tenantId: string
  tenantName: string
  assignee: string
  priority: SopPriorityDTO
  dueAt: string
  status: SopTaskStatusDTO
  completedSteps: string[]
  note: string | null
  startedAt: string | null
  completedAt: string | null
}

/** 新建模板步骤入参。 */
export interface CreateSopStepCmd {
  title: string
  desc?: string
  requirePhoto?: boolean
}

/** 新建模板入参（固定 v1.0 DRAFT）。 */
export interface CreateSopTemplateCmd {
  title: string
  category: SopCategoryDTO
  owner: string
  applicableStores: string[]
  steps: CreateSopStepCmd[]
}

/** 派单入参。 */
export interface CreateSopTaskCmd {
  templateId: string
  storeCode: string
  assignee: string
  priority?: SopPriorityDTO
  dueDate: string
}

/** 模板列表（集团全局，可选状态过滤）。 */
export const listSopTemplates = (params?: { status?: SopStatusDTO }) =>
  client.get<SopTemplateDTO[]>('/stores/sop/templates', { params })

/** 新建模板；返回新建主体（含服务端 id/code）。 */
export const createSopTemplate = (cmd: CreateSopTemplateCmd) =>
  client.post<SopTemplateDTO>('/stores/sop/templates', cmd)

/** 发布模板（sop:approve）；版本末位 +1。 */
export const publishSopTemplate = (id: string) =>
  client.post<SopTemplateDTO>(`/stores/sop/templates/${id}/publish`, {})

/** 任务列表（数据域按登录态收敛；status 按派生态匹配）。 */
export const listSopTasks = (params?: { storeCode?: string; status?: SopTaskStatusDTO }) =>
  client.get<SopTaskDTO[]>('/stores/sop/tasks', { params })

/** 派单；返回新建任务主体。 */
export const createSopTask = (cmd: CreateSopTaskCmd) =>
  client.post<SopTaskDTO>('/stores/sop/tasks', cmd)

/** 开始执行（落库 PENDING → IN_PROGRESS）。 */
export const startSopTask = (id: string) =>
  client.post<SopTaskDTO>(`/stores/sop/tasks/${id}/start`, {})

/** 勾选/取消步骤（DONE 禁改）。 */
export const toggleSopStep = (id: string, stepRef: string) =>
  client.post<SopTaskDTO>(`/stores/sop/tasks/${id}/steps/${stepRef}/toggle`, {})

/** 完成任务（note 必填；须全步骤勾选）。 */
export const completeSopTask = (id: string, note: string) =>
  client.post<SopTaskDTO>(`/stores/sop/tasks/${id}/complete`, { note })
