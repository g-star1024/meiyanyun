// ============================================================
// 员工周排班 API（对接 org-service：/api/org/schedule）
//
// 周视图读（员工清单+数据域内班次行）/ 单日改班（周网格点选循环）/ 周例铺底。
// 班次五态以后端字典为唯一事实源：MORNING 上午 / MID 下午 / FULL 全天 /
// OFF 休息 / LEAVE 请假；未排日期后端不返回行，前端按空态处理，不替用户造班。
// 400/403/404 中文错误由调用方经 errMsg() 外露 toast。
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

/** 周例铺底：周一至周六 FULL、周日 OFF，已存在行跳过不覆盖。 */
export const generateWeek = (weekStart?: string) =>
  client.post<{ weekStart: string; weekEnd: string; staffCount: number; created: number; skipped: number }>(
    '/org/schedule/generate-week',
    weekStart ? { weekStart } : {},
  )
