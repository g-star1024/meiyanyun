// ============================================================
// 术后随访 SOP API（对接 txn-service 随访独立域：/api/txn/followup）
//
// SOP 节点由「完成治疗」AFTER_COMMIT 自动排程（1/3/7/30 天共享批次号）；
// 超期未完成由后端定时巡检置 escalated 并升级提醒店长。
// 权限：读 followup:view / 核销 followup:edit / 手工建 followup:create；门店由后端按 JWT 数据域过滤。
// ============================================================
import client from './client'

export type FollowupStatusDTO = 'PENDING' | 'DONE' | 'SKIPPED'

/**
 * 随访读模型（24 字段，1:1 对齐后端 FollowupController.FollowupView）。
 * 注意：id 为数据库主键 String.valueOf(Long)，路径端点（get/complete/skip）均吃它；
 * followupNo 仅展示业务单号。serviceDate/planDate 为 LocalDate（yyyy-MM-dd），doneAt/createdAt 为 ISO offset 串。
 */
export interface FollowupViewDTO {
  id: string
  followupNo: string
  customerId: string
  customerName: string
  storeCode: string
  project: string
  relatedOrderNo: string | null
  serviceDate: string
  planDate: string
  method: string
  status: FollowupStatusDTO | string
  sopStage: string | null
  sopLabel: string | null
  sopBatchId: string | null
  escalated: boolean
  satisfaction: number | null
  recovery: string | null
  adverseReaction: boolean
  adverseNote: string | null
  adverseStatus: string | null
  adverseHandleNote: string | null
  adverseHandleBy: string | null
  adverseHandleAt: string | null
  needRevisit: boolean
  note: string | null
  followupByName: string | null
  doneAt: string | null
  createdAt: string
}

/** Spring Data 分页响应（page 0 起）。 */
export interface FollowupPage {
  content: FollowupViewDTO[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

/** 本店随访计数九键（LinkedHashMap 固定顺序；avgSatisfaction 为一位小数 number，无记录 0）。 */
export interface FollowupStats {
  sopPending: number
  sopOverdue: number
  pending: number
  todayPending: number
  overdue: number
  done: number
  skipped: number
  avgSatisfaction: number
  adverseCount: number
}

/** 手工建普通随访请求（工作台「新建回访计划」；日期传 yyyy-MM-dd，禁止 toISOString）。 */
export interface CreateFollowupCmd {
  customerId: string
  project: string
  relatedOrderNo?: string
  serviceDate: string
  planDate: string
  method?: string
}

/** 登记回访结果请求（满意度 1-5、恢复情况 GOOD/NORMAL/POOR 必填；勾选不良反应时说明必填，校验在后端）。 */
export interface CompleteFollowupCmd {
  satisfaction: number
  recovery: string
  adverseReaction: boolean
  adverseNote?: string
  needRevisit: boolean
  note?: string
  method?: string
}

export interface ListFollowupParams {
  storeCode?: string
  status?: string
  customerId?: string
  sopBatchId?: string
  sopOnly?: boolean
  adverseOnly?: boolean
  adverseStatus?: string
  keyword?: string
  page?: number
  size?: number
}

/** 随访分页列表：keyword 模糊客户名/项目/关联订单号；排序后端固定（planDate,id 升序）。 */
export const listFollowup = (params?: ListFollowupParams) =>
  client.get<FollowupPage>('/txn/followup', { params })

/** 本店随访计数聚合（工作台两卡 + 台账 KPI/角标九键，替代前端全量 .length）。 */
export const statsFollowup = (storeCode?: string) =>
  client.get<FollowupStats>('/txn/followup/stats', { params: { storeCode } })

/** 随访详情（越权跨店统一 404；id 为数据库主键字符串）。 */
export const getFollowup = (id: string) =>
  client.get<FollowupViewDTO>(`/txn/followup/${id}`)

/** 手工建普通随访（sopStage=MANUAL；客户须已建档，否则后端 400 中文引导先建档）。 */
export const createFollowup = (cmd: CreateFollowupCmd) =>
  client.post<FollowupViewDTO>('/txn/followup', cmd)

/** 登记回访结果（PENDING → DONE）。 */
export const completeFollowup = (id: string, cmd: CompleteFollowupCmd) =>
  client.post<FollowupViewDTO>(`/txn/followup/${id}/complete`, cmd)

/** 标记无需回访（PENDING → SKIPPED），原因必填。 */
export const skipFollowup = (id: string, reason: string) =>
  client.post<FollowupViewDTO>(`/txn/followup/${id}/skip`, { reason })

// ============================================================
// 术后随访 SOP 编排（/api/txn/followup/sop；P5-B31 卡B）
// 模板为集团通用单模板（节点含停用，行号升序）；写动作返回最新全量模板，前端整体替换。
// ============================================================

/** SOP 模板节点读模型（id 为数据库主键字符串，增改删/启停路径均吃它；内置四阶段 stage 非 MANUAL）。 */
export interface SopNodeDTO {
  id: string
  templateNo: string
  lineNo: number
  stage: string
  label: string
  dayOffset: number
  method: string
  enabled: boolean
}

/** SOP 模板读模型（模板号 + 名称 + 全量节点）。 */
export interface SopTemplateDTO {
  templateNo: string
  name: string
  nodes: SopNodeDTO[]
}

/** 批次看板行：批次头 + 完成/超期计数 + 完结标记 + 内嵌全部节点（24 字段随访读模型）。 */
export interface SopBatchDTO {
  batchId: string
  customerId: string
  customerName: string
  project: string
  relatedOrderNo: string | null
  serviceDate: string
  total: number
  done: number
  overdue: number
  finished: boolean
  nodes: FollowupViewDTO[]
}

/** 看板五键（activeBatches/finishedBatches/sopPending/sopOverdue/needEscalation）。 */
export interface SopSummaryDTO {
  activeBatches: number
  finishedBatches: number
  sopPending: number
  sopOverdue: number
  needEscalation: number
}

/** 节点新增/局部更新请求：新增三字段必填，更新时仅非空字段生效（后端中文校验）。templateNo 指定目标模板（缺省默认模板）。 */
export interface SopNodeCmd {
  label?: string
  dayOffset?: number
  method?: string
  templateNo?: string
}

/** SOP 模板行（P6-B101 多模板列表）：门店模板仅本店可见，集团模板全店可见。 */
export interface SopTemplateRowDTO {
  templateNo: string
  name: string
  storeCode: string | null
  enabled: boolean
  nodeCount: number
}

export interface SopBatchParams {
  storeCode?: string
  keyword?: string
  page?: number
  size?: number
}

/** SOP 批次 Spring Data 分页响应（page 0 起）。 */
export interface SopBatchPage {
  content: SopBatchDTO[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

/** 读取 SOP 模板（含停用节点，行号升序；未播种环境后端幂等懒初始化）。templateNo 缺省为集团默认模板。 */
export const getSopTemplate = (templateNo?: string) =>
  client.get<SopTemplateDTO>('/txn/followup/sop/template', { params: { templateNo } })

/** 新增自定义节点（stage=MANUAL），返回最新全量模板。 */
export const addSopNode = (cmd: SopNodeCmd) =>
  client.post<SopTemplateDTO>('/txn/followup/sop/template/nodes', cmd)

/** 修改节点（名称/天数/方式，仅非空字段生效；内置与自定义均可改），返回最新全量模板。 */
export const updateSopNode = (id: string, cmd: SopNodeCmd) =>
  client.put<SopTemplateDTO>(`/txn/followup/sop/template/nodes/${id}`, cmd)

/** 启停节点（停用后不参与新批次排程，历史批次不变），空体默认启用；返回最新全量模板。 */
export const toggleSopNode = (id: string, enabled: boolean) =>
  client.post<SopTemplateDTO>(`/txn/followup/sop/template/nodes/${id}/toggle`, { enabled })

/** 删除节点（仅 MANUAL 自定义节点可删，内置 400 中文引导停用），返回最新全量模板。 */
export const deleteSopNode = (id: string) =>
  client.delete<SopTemplateDTO>(`/txn/followup/sop/template/nodes/${id}`)

/** 恢复默认模板（清空含自定义的全部节点，重建内置四节点并全启用），返回最新全量模板。 */
export const resetSopTemplate = () =>
  client.post<SopTemplateDTO>('/txn/followup/sop/template/reset')

/** 批次分页聚合（后端排序：未完结在前、服务日期倒序；keyword 模糊客户名/项目/批次号）。 */
export const listSopBatches = (params?: SopBatchParams) =>
  client.get<SopBatchPage>('/txn/followup/sop/batches', { params })

/** 看板五键汇总（KPI / 预警条 / Tab 角标）。 */
export const getSopSummary = (storeCode?: string) =>
  client.get<SopSummaryDTO>('/txn/followup/sop/summary', { params: { storeCode } })

/** 一键升级本店超期未升级 SOP 节点（FIFO 50，与 60s 巡检共享通知幂等），返回实际升级条数。 */
export const escalateSopOverdue = (storeCode?: string) =>
  client.post<{ escalated: number }>('/txn/followup/sop/escalate', null, { params: { storeCode } })

/** P6-B101 多模板：当前门店可见模板列表（集团模板＋本店模板，他店模板不可见）。 */
export const listSopTemplates = () =>
  client.get<SopTemplateRowDTO[]>('/txn/followup/sop/templates')

/** P6-B101 多模板：新建本店专属模板（店码取登录态，名称必填），返回模板号。 */
export const createStoreSopTemplate = (name: string) =>
  client.post<{ templateNo: string }>('/txn/followup/sop/templates', { name })

/** P6-B101 节点排序：按数组顺序重排行号（1..N），返回最新全量模板。 */
export const reorderSopNodes = (templateNo: string, nodeIds: string[]) =>
  client.post<SopTemplateDTO>('/txn/followup/sop/template/nodes/reorder', { templateNo, nodeIds })

/** 满意度趋势点（P6-B101 结构化分析）：bucket 为日/周首（周一），无数据桶补零。 */
export interface FollowupTrendPointDTO {
  bucket: string
  doneCount: number
  avgSatisfaction: number
  adverseCount: number
}

/** 满意度趋势（按核销时间分桶，默认近 30 天，区间上限 366 天；granularity=day|week）。 */
export const trendFollowup = (params?: { storeCode?: string; from?: string; to?: string; granularity?: string }) =>
  client.get<FollowupTrendPointDTO[]>('/txn/followup/stats/trend', { params })

/** 不良反应处置（P6-B101 处置台）：仅已登记不良反应的随访可处置，RESOLVED 必填处置说明。 */
export const adverseHandleFollowup = (id: string, body: { status: string; note?: string }) =>
  client.post<FollowupViewDTO>(`/txn/followup/${id}/adverse-handle`, body)
