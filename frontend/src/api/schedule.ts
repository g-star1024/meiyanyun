// ============================================================
// 员工周排班 API（对接 org-service：/api/org/schedule、/api/org/leaves）
//
// 周视图读（员工清单+数据域内班次行）/ 单日改班（周网格点选循环）/ 周例铺底 / 复制上周；
// 请假域：登记 / 列表 / 批准（联动写 LEAVE OVERRIDE）/ 驳回。
// 班次五态以后端字典为唯一事实源：MORNING 上午 / MID 下午 / FULL 全天 /
// OFF 休息 / LEAVE 请假；未排日期后端不返回行，前端按空态处理，不替用户造班。
// 400/403/404/409 中文错误由调用方经 errMsg() 外露 toast。
// ============================================================
import client from './client'

export type ShiftCode = 'MORNING' | 'MID' | 'FULL' | 'OFF' | 'LEAVE'

/** 班次字典项（对齐后端 ScheduleController.ShiftCodeDef）。 */
export interface ShiftCodeDef {
  code: ShiftCode
  label: string
  assignable: boolean
}

/** 周视图内员工节点（数据域内在职员工）。 */
export interface ScheduleStaffDTO {
  staffId: string
  staffName: string
  storeCode: string | null
  roleCode: string
}

/** 班次行节点（对齐后端 shiftNode）。 */
export interface StaffShiftDTO {
  id: number
  staffId: string
  shiftDate: string
  shiftCode: ShiftCode | string
  source: 'TEMPLATE' | 'OVERRIDE' | string
}

/** 周排班视图（GET /org/schedule）。 */
export interface ScheduleWeekDTO {
  weekStart: string
  weekEnd: string
  staff: ScheduleStaffDTO[]
  shifts: StaffShiftDTO[]
}

/** 单日改班请求体（PUT /org/schedule/shift）。 */
export interface ShiftSetCmd {
  staffId: string
  shiftDate: string
  shiftCode: ShiftCode
}

/** 周视图：weekStart 任意日期均可（后端归一化到所在周周一），可省默认本周。 */
export const getScheduleWeek = (weekStart?: string) =>
  client.get<ScheduleWeekDTO>('/org/schedule', { params: weekStart ? { weekStart } : undefined })

/** 班次五态字典：内部码 + 中文文案 + 是否可派单。 */
export const listShiftCodes = () =>
  client.get<ShiftCodeDef[]>('/org/shift-codes')

/** 单日改班：手工改动一律落 source=OVERRIDE；同日同码提交幂等。 */
export const setStaffShift = (cmd: ShiftSetCmd) =>
  client.put<StaffShiftDTO>('/org/schedule/shift', cmd)

/** 周动作结果：铺底 / 复制上周共用。 */
export interface WeekActionDTO {
  weekStart: string
  weekEnd: string
  staffCount: number
  created: number
  skipped: number
}

/** 周例铺底：周一至周六 FULL、周日 OFF，已存在行跳过不覆盖。 */
export const generateWeek = (weekStart?: string) =>
  client.post<WeekActionDTO>('/org/schedule/generate-week', weekStart ? { weekStart } : {})

/** 复制上周：上一周真实班次复制到目标周，目标日存在跳过，LEAVE 不复制。 */
export const copyWeek = (weekStart?: string) =>
  client.post<WeekActionDTO>('/org/schedule/copy-week', weekStart ? { weekStart } : {})

/** 请假类型（后端白名单：年假/事假/病假；换班不接收新登记）。 */
export type LeaveType = '年假' | '事假' | '病假'

/** 请假单节点（对齐后端 LeaveController.leaveNode）。 */
export interface LeaveRequestDTO {
  id: number
  leaveNo: string
  staffId: string
  staffName: string
  type: LeaveType | string
  startDate: string
  endDate: string
  reason: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | string
  appliedBy: string
  appliedAt: string
  reviewerId: string | null
  reviewerName: string | null
  reviewedAt: string | null
  rejectReason: string | null
  affectedDays?: number
}

/** 请假登记请求体（POST /org/leaves）。 */
export interface LeaveApplyCmd {
  staffId: string
  type: LeaveType
  startDate: string
  endDate: string
  reason: string
}

/** 请假列表：数据域内在职员工的全部单据（登记时间倒序）。 */
export const listLeaves = () => client.get<LeaveRequestDTO[]>('/org/leaves')

/** 请假登记：成功返回新建 PENDING 单据。 */
export const applyLeave = (cmd: LeaveApplyCmd) => client.post<LeaveRequestDTO>('/org/leaves', cmd)

/** 批准：联动区间覆盖 LEAVE + OVERRIDE（affectedDays 为实际覆盖天数）。 */
export const approveLeave = (id: number | string) =>
  client.post<LeaveRequestDTO>(`/org/leaves/${id}/approve`)

/** 驳回：不动排班；rejectReason 可空。 */
export const rejectLeave = (id: number | string, rejectReason?: string) =>
  client.post<LeaveRequestDTO>(`/org/leaves/${id}/reject`, rejectReason ? { rejectReason } : {})
