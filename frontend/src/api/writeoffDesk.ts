// ============================================================
// M2-01 划扣核销台 API（对接 txn-service /api/txn/writeoff-desk）
//  ① GET  /tasks            今日（或指定日期）待划扣队列，富化客户名/掩码手机号/卡项
//  ② POST /tasks            老客未预约直接到店：手工建 WALKIN 任务
//  ③ POST /tasks/{wdNo}/execute   双签划扣（reviewer 必填，cardNo 可选缺省用绑定卡）
//  ④ POST /tasks/{wdNo}/exception 标记异常（DONE 不可标）
//  ⑤ POST /tasks/{wdNo}/reset     解除异常恢复待执行
//  ⑥ GET  /customer-cards   客户本店在用卡列表（双签弹窗/手工建单选卡）
// 金额口径：amount/balance/unitAmount 均为 bigint 存「分」，展示层 fen2yuan。
// ============================================================
import client from './client'

/** 核销台任务读模型（WdView）；金额单位「分」。 */
export interface WdTaskDTO {
  id: string
  no: string
  customerId?: string
  customerName: string
  phone: string
  project: string
  cardName: string | null
  cardNo?: string | null
  storeCode?: string
  totalCount: number
  remainingCount: number
  /** 本次划扣金额（分） */
  amount: number
  operator: string
  reviewer: string | null
  source: 'APPOINTMENT' | 'WALKIN'
  status: 'PENDING' | 'DONE' | 'EXCEPTION'
  exceptionReason: 'NONE' | 'CUSTOMER_ABSENT' | 'COUNT_MISMATCH' | 'EQUIPMENT_FAULT' | 'OTHER'
  appointmentTime: string
  executedAt: string | null
  timeline: Array<{ by: string; text: string; at: string }>
}

/** 客户在用卡选项（CardOption）；金额单位「分」。 */
export interface WdCardOptionDTO {
  cardNo: string
  cardName: string
  storeCode: string
  totalTimes: number
  remainTimes: number
  /** 卡余额（分） */
  balance: number
  /** 单次均价（分） */
  unitAmount: number
}

/** 待划扣队列：date 缺省今日（yyyy-MM-dd），storeCode/status 可选过滤。 */
export const listWdTasks = (params?: { date?: string; storeCode?: string; status?: string }) =>
  client.get<WdTaskDTO[]>('/txn/writeoff-desk/tasks', { params })

/** 老客未预约直接到店手工建单（WALKIN）；cardNo 不传由后端绑本店在用最新卡。 */
export const createWdWalkin = (cmd: {
  customerId: string
  storeCode: string
  project: string
  cardNo?: string
}) => client.post<WdTaskDTO>('/txn/writeoff-desk/tasks', cmd)

/** 双签划扣执行：reviewer 必填，cardNo 可选（卡选择器改卡），remark 可选。 */
export const executeWdTask = (wdNo: string, cmd: { reviewer: string; cardNo?: string; remark?: string }) =>
  client.post<WdTaskDTO>(`/txn/writeoff-desk/tasks/${wdNo}/execute`, cmd)

/** 标记异常。 */
export const markWdException = (wdNo: string, cmd: { reason: string; note?: string }) =>
  client.post<WdTaskDTO>(`/txn/writeoff-desk/tasks/${wdNo}/exception`, cmd)

/** 解除异常。 */
export const resetWdTask = (wdNo: string) =>
  client.post<WdTaskDTO>(`/txn/writeoff-desk/tasks/${wdNo}/reset`)

/** 客户本店在用卡（卡选择器）。 */
export const listWdCustomerCards = (customerId: string, storeCode?: string) =>
  client.get<WdCardOptionDTO[]>('/txn/writeoff-desk/customer-cards', {
    params: { customerId, ...(storeCode ? { storeCode } : {}) },
  })
