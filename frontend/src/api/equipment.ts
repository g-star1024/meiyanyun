// ============================================================
// Equipment 设备仪器 API（B13）
// 网关 /api/stores → store-service:8085，类路径 /api/stores。
// 读接口金额单位「元」（purchaseAmount/depreciated/cost，后端已换算）；
// 写接口金额单位「分」（purchaseAmountFen/costFen），前端由「元」换算。
// 日期均为 yyyy-MM-dd（后端 LocalDate）；前端传 ISO 字符串，后端截前 10 位解析。
// ============================================================
import client from './client'

/** 校准/维保/维修记录行（嵌在设备内）。 */
export interface MaintenanceRecordDTO {
  id: number
  type: 'CALIBRATION' | 'MAINTENANCE' | 'REPAIR'
  at: string | null
  by: string
  vendor: string | null
  summary: string
  nextAt: string | null
  cost: number // 元
}

/** 设备台账行（GET /stores/equipments）；金额单位「元」。 */
export interface EquipmentDTO {
  id: number
  storeCode: string
  assetNo: string
  name: string
  brand: string | null
  model: string | null
  category: 'LASER' | 'RF' | 'ULTRASOUND' | 'INJECTION' | 'MONITOR' | 'OTHER'
  location: string
  status: 'NORMAL' | 'CALIBRATING' | 'REPAIRING' | 'DISABLED'
  purchasedAt: string | null
  purchaseAmount: number // 元
  lifespanYears: number
  depreciated: number // 元
  nextCalibrationAt: string | null
  nextMaintenanceAt: string | null
  note: string | null
  records: MaintenanceRecordDTO[]
}

/** 设备建档入参；金额 purchaseAmountFen 单位「分」，日期为 ISO 字符串。 */
export interface CreateEquipmentCmd {
  storeCode: string
  assetNo: string
  name: string
  brand?: string
  model?: string
  category: string
  location?: string
  status?: string
  purchasedAt: string
  purchaseAmountFen: number
  lifespanYears: number
  nextCalibrationAt?: string
  nextMaintenanceAt?: string
  note?: string
}

/** 状态变更入参。 */
export interface EquipmentStatusCmd {
  storeCode: string
  status: string
  note?: string
}

/** 校准/维保/维修记录入参；费用 costFen 单位「分」，at/nextAt 为 ISO 字符串。 */
export interface EquipmentRecordCmd {
  storeCode: string
  type: string
  summary: string
  vendor?: string
  at?: string
  nextAt?: string
  costFen?: number
}

/** 设备台账（含记录嵌套）；门店/自助角色后端强制本店，集团/品牌可参 storeCode。 */
export const listEquipments = (params?: {
  storeCode?: string
  category?: string
  status?: string
  keyword?: string
}) => client.get<EquipmentDTO[]>('/stores/equipments', { params })

/** 单台设备详情（含记录）。 */
export const getEquipment = (id: number, params?: { storeCode?: string }) =>
  client.get<EquipmentDTO>(`/stores/equipments/${id}`, { params })

/** 设备建档；返回 {id, assetNo, storeCode}。 */
export const createEquipment = (cmd: CreateEquipmentCmd) =>
  client.post<{ id: number; assetNo: string; storeCode: string }>('/stores/equipments', cmd)

/** 设备状态变更；返回 {ok:true}。 */
export const setEquipmentStatus = (id: number, cmd: EquipmentStatusCmd) =>
  client.post<{ ok: boolean }>(`/stores/equipments/${id}/status`, cmd)

/** 登记校准/维保/维修记录（回写下次日期/状态/折旧）；返回 {ok:true}。 */
export const addEquipmentRecord = (id: number, cmd: EquipmentRecordCmd) =>
  client.post<{ ok: boolean }>(`/stores/equipments/${id}/records`, cmd)
