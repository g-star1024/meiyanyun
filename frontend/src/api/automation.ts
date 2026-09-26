// ============================================================
// Automation API（对接 marketing-service /api/marketing/flow）
// P6-B100 B90 遗留收口：自动化规则管理前端页（规则 CRUD/启停＋执行日志）。
// axios client 拦截器不透传 .data，消费侧须 res.data（B86 教训）。
// 词表与 V48 chk 一致：triggerType=BIRTHDAY/DORMANT_DAYS/VISIT_GAP_DAYS，
// actionType=CREATE_CARE_TASK/CREATE_RECALL，log status=SUCCESS/SKIPPED/FAILED。
// ============================================================
import client from './client'

/** 规则行（GET /marketing/flow/rules；trigger/condition/actionConfig 为 JSON 字符串，jsonb 承载） */
export interface AutomationRuleRow {
  id: number
  ruleNo: string
  name: string
  triggerType: string
  triggerConfig: string
  conditionConfig: string | null
  actionType: string
  actionConfig: string
  enabled: boolean
  storeCode: string | null
  createdBy: string | null
  createdAt: string
  updatedAt: string
}

/** 创建/更新命令（enabled 仅创建时生效，后续启停走 toggle；storeCode 空=null=全连锁） */
export interface AutomationRuleCmd {
  name: string
  triggerType: string
  triggerConfig: string
  conditionConfig?: string | null
  actionType: string
  actionConfig: string
  storeCode?: string | null
  enabled?: boolean
}

/** 执行日志行（GET /marketing/flow/logs；status=SUCCESS/SKIPPED/FAILED） */
export interface AutomationLogRow {
  id: number
  ruleNo: string
  customerId: string
  triggerDate: string
  actionType: string
  actionRef: string
  status: string
  message: string | null
  idemKey: string
  createdAt: string
}

/** 规则列表（enabled 可空=全部；须 marketing:view） */
export const fetchAutomationRules = (params?: { enabled?: boolean }) =>
  client.get<AutomationRuleRow[]>('/marketing/flow/rules', { params })

/** 新建规则（须 marketing:edit；rule_no 后端生成 AR+yyyyMMdd+6位序号） */
export const createAutomationRule = (body: AutomationRuleCmd) =>
  client.post<AutomationRuleRow>('/marketing/flow/rules', body)

/** 更新规则（须 marketing:edit；enabled 字段此端点不生效，启停走 toggle） */
export const updateAutomationRule = (id: number, body: AutomationRuleCmd) =>
  client.put<AutomationRuleRow>(`/marketing/flow/rules/${id}`, body)

/** 启停翻转（须 marketing:edit；误配止血，DESIGN §7） */
export const toggleAutomationRule = (id: number) =>
  client.post<AutomationRuleRow>(`/marketing/flow/rules/${id}/toggle`)

/** 执行日志（ruleNo/date=yyyy-MM-dd 可空四组合，无过滤限 200 条；须 marketing:view） */
export const fetchAutomationLogs = (params?: { ruleNo?: string; date?: string }) =>
  client.get<AutomationLogRow[]>('/marketing/flow/logs', { params })
