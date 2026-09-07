// ============================================================
// Room 房间床位 API（B13）
// 网关 /api/stores → store-service:8085，类路径 /api/stores。
// 床位实时交易态（FREE/IN_USE/SANITIZING）一期不持久化（DESIGN-P4 D1），
// 后端仅落主数据 + 维护停用态（maintStatus: OK/MAINTENANCE）；
// 入住/退房/消毒为前端演示态，设维护/维护恢复为真实持久化动作。
// ============================================================
import client from './client'

/** 床位档案行（嵌在房间内）；maintStatus 为唯一持久化床位状态。 */
export interface BedDTO {
  id: number
  roomId: number
  bedCode: string
  maintStatus: 'OK' | 'MAINTENANCE'
  maintReason: string | null
}

/** 房间档案行（GET /stores/rooms）。 */
export interface RoomDTO {
  id: number
  storeCode: string
  roomCode: string
  name: string
  roomType: 'TREATMENT' | 'CONSULT' | 'OBSERVE' | 'RECOVERY'
  status: 'ACTIVE' | 'MAINTENANCE'
  remark: string | null
  beds: BedDTO[]
}

/** 房间/床位操作日志行（GET /stores/rooms/logs）。 */
export interface RoomLogDTO {
  id: number
  storeCode: string
  roomCode: string | null
  bedCode: string | null
  action: 'ADD_ROOM' | 'SET_MAINTENANCE' | 'RESTORE'
  text: string
  actor: string
  createdAt: string
}

/** 房间建档入参；bedCount 1~50，床位编号 {roomCode}-B{n}。 */
export interface CreateRoomCmd {
  storeCode: string
  roomCode: string
  name: string
  roomType: string
  bedCount: number
}

/** 床位设维护入参；reason 必填。 */
export interface BedMaintCmd {
  storeCode: string
  reason: string
}

/** 房间档案；门店/自助角色后端强制本店，集团/品牌可参 storeCode。 */
export const listRooms = (params?: { storeCode?: string; type?: string; status?: string }) =>
  client.get<RoomDTO[]>('/stores/rooms', { params })

/** 房间/床位操作日志（最近 limit 条，时间倒序）。 */
export const listRoomLogs = (params?: { storeCode?: string; limit?: number }) =>
  client.get<RoomLogDTO[]>('/stores/rooms/logs', { params })

/** 房间建档（批量生成床位）；返回 {id, roomCode, storeCode}。 */
export const createRoom = (cmd: CreateRoomCmd) =>
  client.post<{ id: number; roomCode: string; storeCode: string }>('/stores/rooms', cmd)

/** 床位设为维护；返回 {ok:true}。 */
export const setBedMaintenance = (bedId: number, cmd: BedMaintCmd) =>
  client.post<{ ok: boolean }>(`/stores/beds/${bedId}/maintenance`, cmd)

/** 床位维护恢复；返回 {ok:true}。 */
export const restoreBed = (bedId: number, cmd: { storeCode: string }) =>
  client.post<{ ok: boolean }>(`/stores/beds/${bedId}/restore`, cmd)
