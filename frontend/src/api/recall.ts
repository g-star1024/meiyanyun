// ============================================================
// Recall API（对接 marketing-service /api/marketing/recall）
// P5-B90 随访 Flow：复诊提醒列表/新建/提醒/确认/预约/改期/跳过。
// axios client 拦截器不透传 .data，消费侧须 res.data（B86 教训）。
// 词表与 V48 chk 一致：source=DOCTOR_ADVICE/COURSE_FOLLOW/SYSTEM_AUTO/
// MANUAL，method=PHONE/WECHAT/SMS/IN_STORE，status 五态；
// 状态机服务端权威，非法转移 409（errMsg 透传 message 中文文案）。
// ============================================================
import client from './client'

/**
 * 复诊提醒行（GET /marketing/recall 真实字段）。
 * timeline 为 JSON 数组字符串（元素 {at, by, action, detail?}），前端 store 适配层 JSON.parse。
 * 日期字段 yyyy-MM-dd；notifiedAt/createdAt 为 +8 业务时区 ISO 时间串。
 */
export interface RecallRow {
  id: string
  recallNo: string
  customerId: string
  customerName: string
  source: string
  reason: string
  relatedEmrNo: string | null
  relatedOrderNo: string | null
  lastVisitDate: string | null
  dueDate: string
  method: string
  status: string
  notifiedByName: string | null
  notifiedAt: string | null
  customerReply: string | null
  confirmedDate: string | null
  skipReason: string | null
  note: string | null
  timeline: string
  createdAt: string
  /** 来源规则编号：非空 = Flow 引擎自动创建（页面展示「自动」徽标） */
  ruleNo: string | null
  storeCode: string | null
}

/** KPI 四键（随列表响应返回；前端 store 按本地 computed 口径自算，不消费此对象） */
export interface RecallKpi {
  overdue: number
  todayPending: number
  upcoming: number
  conversionRate: number
}

export interface RecallListResp {
  recalls: RecallRow[]
  kpi: RecallKpi
}

/** 新建命令（source 四来源；dueDate=yyyy-MM-dd 必填；method 默认 PHONE；customerName 后端按 customerId 解析） */
export interface ScheduleRecallCmd {
  customerId: string
  source: string
  reason: string
  relatedEmrNo?: string
  relatedOrderNo?: string
  lastVisitDate?: string
  dueDate: string
  method?: string
  note?: string
}

/** 列表（status 精确五态；kw 模糊姓名/事由/单号；storeCode 精确）＋全量 KPI */
export const fetchRecalls = (params?: { status?: string; kw?: string; storeCode?: string }) =>
  client.get<RecallListResp>('/marketing/recall', { params })

/** 新建复诊提醒（须 recall:create；落 PENDING；客户硬校验） */
export const scheduleRecall = (body: ScheduleRecallCmd) =>
  client.post<RecallRow>('/marketing/recall', body)

/** 执行提醒 PENDING → NOTIFIED（须 recall:edit） */
export const notifyRecall = (recallNo: string, method?: string) =>
  client.post<RecallRow>(`/marketing/recall/${recallNo}/notify`, { method })

/** 登记客户确认 NOTIFIED → CONFIRMED（confirmedDate=yyyy-MM-dd） */
export const confirmRecall = (recallNo: string, body: { reply?: string; confirmedDate?: string }) =>
  client.post<RecallRow>(`/marketing/recall/${recallNo}/confirm`, body)

/** 标记已预约 NOTIFIED/CONFIRMED → BOOKED */
export const bookRecall = (recallNo: string, detail?: string) =>
  client.post<RecallRow>(`/marketing/recall/${recallNo}/book`, { detail })

/** 改期（仅 PENDING/NOTIFIED；NOTIFIED → PENDING 清通知信息；dueDate=yyyy-MM-dd） */
export const rescheduleRecall = (recallNo: string, body: { dueDate: string; note?: string }) =>
  client.post<RecallRow>(`/marketing/recall/${recallNo}/reschedule`, body)

/** 跳过（reason 必填 ≤200 字；PENDING/NOTIFIED/CONFIRMED → SKIPPED） */
export const skipRecall = (recallNo: string, reason: string) =>
  client.post<RecallRow>(`/marketing/recall/${recallNo}/skip`, { reason })
