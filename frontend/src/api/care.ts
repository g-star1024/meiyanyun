// ============================================================
// Care API（对接 marketing-service /api/marketing/care）
// P5-B90 随访 Flow：关怀任务列表/创建/发送/触达/转化＋模板目录。
// axios client 拦截器不透传 .data，消费侧须 res.data（B86 教训）。
// 词表与 V48 chk 一致：type=BIRTHDAY/HOLIDAY/REPURCHASE/REACTIVATE，
// channel=SMS/WECHAT/PHONE（无 IN_STORE），status=PENDING/SENT/REACHED。
// ============================================================
import client from './client'

/** 关怀任务行（GET /marketing/care/tasks 真实字段；id 即 careNo） */
export interface CareTaskRow {
  id: string
  customerId: string
  customerName: string
  customerLevel: string | null
  type: string
  channel: string
  templateName: string | null
  templateContent: string | null
  scheduledAt: string
  status: string
  sentAt: string | null
  reached: boolean
  replied: boolean
  convertedBooking: boolean
  assignee: string | null
  /** 来源规则编号：非空 = Flow 引擎自动创建（追溯用） */
  ruleNo: string | null
  storeCode: string | null
}

/** KPI 四键（随列表响应返回；前端 store 按本地 computed 口径自算，不消费此对象） */
export interface CareKpi {
  pendingThisMonth: number
  sent: number
  reachRate: number
  converted: number
}

export interface CareListResp {
  tasks: CareTaskRow[]
  kpi: CareKpi
}

/** 创建命令（content 可空 = 后端默认文案；planDate=yyyy-MM-dd） */
export interface CreateCareCmd {
  customerId: string
  type: string
  channel: string
  content?: string
  planDate: string
}

/** send 结果：skipped=true 表示合规/故障拦截未发送（reason 中文原因，任务保持 PENDING） */
export interface CareSendResult {
  skipped: boolean
  reason: string | null
  task: CareTaskRow
}

/** 关怀模板（GET /marketing/care/templates；按渠道过滤在调用侧做） */
export interface CareTemplateRow {
  id: string
  name: string
  channel: string
  content: string
}

/** 任务列表（status=PENDING/SENT 语义过滤，month=yyyy-MM 过滤 planDate，storeCode 精确）＋全量 KPI */
export const fetchCareTasks = (params?: { status?: string; month?: string; storeCode?: string }) =>
  client.get<CareListResp>('/marketing/care/tasks', { params })

/** 创建关怀任务（须 care:edit；客户硬校验，不存在 400/404） */
export const createCareTask = (body: CreateCareCmd) =>
  client.post<CareTaskRow>('/marketing/care/tasks', body)

/** 立即发送（须 care:send；合规词/频控拦截返回 skipped=true 不抛错，任务保持 PENDING） */
export const sendCareTask = (careNo: string) =>
  client.post<CareSendResult>(`/marketing/care/tasks/${careNo}/send`)

/** 标记/取消触达（须 care:edit；仅已发送可标记） */
export const reachCareTask = (careNo: string, reached: boolean) =>
  client.post<CareTaskRow>(`/marketing/care/tasks/${careNo}/reach`, { reached })

/** 登记/取消转化预约（须 care:edit） */
export const convertCareTask = (careNo: string, converted: boolean) =>
  client.post<CareTaskRow>(`/marketing/care/tasks/${careNo}/convert`, { converted })

/** 模板目录 */
export const fetchCareTemplates = () =>
  client.get<CareTemplateRow[]>('/marketing/care/templates')
