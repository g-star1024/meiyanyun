// ============================================================
// Dispatch API（对接 txn-service 调度派单域 · B49 卡12；B51 卡4 DEVICE 接真）
// 读：GET /api/txn/dispatch/resources（DOCTOR/ROOM/DEVICE 资源+当日占用块内联：活跃+DONE 回显；DEVICE=本店 NORMAL 态设备）
//     GET /api/txn/dispatch/jobs（当日「已预约/已到店」且无活跃派单的预约，已到店排前）
// 写：POST /api/txn/dispatch/dispatch（start 锚定 apptTime，end=start+60min；三类资源均可派）
//     POST /api/txn/dispatch/assignments/{id}/release（RELEASED+released_at 保留行）
// 口径：storeCode 为必传过滤参数（单门店视角）；写操作方法级 dispatch:edit。
// ============================================================
import client from './client'

export type DispatchResourceType = 'DOCTOR' | 'ROOM' | 'DEVICE'

/** 排班/派单块（后端资源行内联） */
export interface DispatchAssignmentDTO {
  id: string
  resourceType: string
  resourceId: string
  resourceName: string
  jobId: string
  customerName: string
  itemName: string
  start: string // "HH:mm"
  end: string
  /** SCHEDULED 已排 / IN_PROGRESS 进行中 / DONE 已完成（RELEASED 不随读侧返回） */
  status: 'SCHEDULED' | 'IN_PROGRESS' | 'DONE' | string
}

/** 调度资源（医生/治疗室/设备；DEVICE 自 B51 卡4 接真=本店 NORMAL 态设备） */
export interface DispatchResourceDTO {
  id: string
  type: DispatchResourceType
  name: string
  title: string | null
  room: string | null
  workStart: string
  workEnd: string
  status: 'ON' | 'OFF' | string
  assignments: DispatchAssignmentDTO[]
}

/** 待派单工单（当日有效预约派生） */
export interface DispatchJobDTO {
  id: string
  jobNo: string
  customerName: string
  itemName: string
  /** 固定 60 分钟（真实时长无源，见 Backlog） */
  durationMin: number
  apptTime: string
  preferredDoctorId: string | null
  preferredDoctor: string | null
  /** 全 NORMAL（无加急源，见 Backlog） */
  priority: 'NORMAL' | string
  /** PENDING 待派单 */
  status: 'PENDING' | string
  arrived: boolean
  createdAt: string
}

export interface DispatchCmd {
  apptNo: string
  resourceType: DispatchResourceType
  resourceId: string
}

export interface DispatchQuery {
  storeCode: string
  date?: string
}

export const listDispatchResources = (params: DispatchQuery & { type?: DispatchResourceType }) =>
  client.get<DispatchResourceDTO[]>('/txn/dispatch/resources', { params })

export const listDispatchJobs = (params: DispatchQuery) =>
  client.get<DispatchJobDTO[]>('/txn/dispatch/jobs', { params })

export const dispatchJob = (storeCode: string, cmd: DispatchCmd) =>
  client.post<DispatchAssignmentDTO>('/txn/dispatch/dispatch', cmd, { params: { storeCode } })

export const releaseAssignment = (storeCode: string, id: string) =>
  client.post<{ id: number; status: string }>(`/txn/dispatch/assignments/${encodeURIComponent(id)}/release`, null, {
    params: { storeCode },
  })
