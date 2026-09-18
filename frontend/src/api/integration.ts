// ============================================================
// ExternalIntegration API（对接 org-service，P5-B57 卡2 外部依赖配置窗口）
// 平台属主 org-service 单点持钥：通知网关 URL（3）/ 广告回传 HMAC 密钥（3）/ 免签开关（1）
// / 全局免打扰时段（1，QUIET_WINDOW，窗口存 configJson）。
// 密钥红线：secret 写后不可读回——列表仅 hasSecret + secretMask，不回显明文/密文；
//          保存时 secret 留空或含掩码（****）= 不修改。审计不记明文与 URL。
// 权限：读 integration:view；写 integration:edit（不新增权限码，目录固定 8 项）。
// ============================================================
import client from './client'

export type IntegrationKind = 'URL' | 'SECRET' | 'SWITCH' | 'QUIET_WINDOW'

/** 外部集成读模型（对齐 org IntegrationService.IntegrationView） */
export interface IntegrationDTO {
  code: string
  category: string
  name: string
  valueKind: IntegrationKind | string
  baseUrl: string | null
  /** 是否已设置密钥（明文绝不下发） */
  hasSecret: boolean
  secretMask: string | null
  boolValue: boolean | null
  enabled: boolean
  remark: string | null
  lastTestAt: string | null
  lastTestOk: boolean | null
  lastTestMsg: string | null
  /** QUIET_WINDOW 类：{"start":"HH:mm","end":"HH:mm"}；其余类型为 null */
  configJson: string | null
}

/** upsert 入参（对齐 IntegrationService.UpsertRequest；secret 空串/含 **** = 不改） */
export interface UpsertIntegrationCmd {
  baseUrl?: string | null
  secret?: string | null
  boolValue?: boolean | null
  enabled?: boolean | null
  /** 明文 HTTP 网关二次确认（仅限内网联调） */
  insecureHttpConfirmed?: boolean
  /** QUIET_WINDOW 类：免打扰起止时间 HH:mm */
  quietStart?: string | null
  quietEnd?: string | null
}

/** 测试连接结果（对齐 IntegrationService.TestResult） */
export interface IntegrationTestResult {
  ok: boolean
  message: string
}

/** 目录全量视图（固定 8 行，无密文无明文） */
export const listIntegrations = () =>
  client.get<IntegrationDTO[]>('/org/integrations')

/** 新建/更新某项配置（upsert by code；密钥留空不改） */
export const upsertIntegration = (code: string, cmd: UpsertIntegrationCmd) =>
  client.post<IntegrationDTO>(`/org/integrations/${encodeURIComponent(code)}`, cmd)

/** 测试连接：URL→真实探测；SECRET→格式校验+联调指引；SWITCH→回显当前状态 */
export const testIntegration = (code: string) =>
  client.post<IntegrationTestResult>(`/org/integrations/${encodeURIComponent(code)}/test`)
