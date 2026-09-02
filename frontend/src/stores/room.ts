// ============================================================
// Room 床位/房间管理 store（M2-04）
// 覆盖治疗室/咨询室/观察室的房间+床位状态：
// FREE 空闲 / IN_USE 使用中 / SANITIZING 消毒中 / MAINTENANCE 维护中。
// 操作：入住、退房、清洁确认、设维护/恢复。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

export type BedStatus = 'FREE' | 'IN_USE' | 'SANITIZING' | 'MAINTENANCE'
export type RoomType = 'TREATMENT' | 'CONSULT' | 'OBSERVE' | 'RECOVERY'

export interface Bed {
  id: string
  /** 床位编号，如 A03-1 */
  code: string
  status: BedStatus
  /** 当前客户 / 治疗项目 */
  customerName?: string
  project?: string
  /** 入住时间 */
  occupiedAt?: string
  /** 备注（维护原因等） */
  note?: string
}

export interface Room {
  id: string
  code: string
  name: string
  type: RoomType
  beds: Bed[]
}

export interface RoomLog {
  id: string
  at: string
  by: string
  roomCode: string
  bedCode?: string
  text: string
}

const ROOM_TYPE_LABEL: Record<RoomType, string> = {
  TREATMENT: '治疗室',
  CONSULT: '咨询室',
  OBSERVE: '观察室',
  RECOVERY: '恢复室',
}

const BED_STATUS_LABEL: Record<BedStatus, string> = {
  FREE: '空闲',
  IN_USE: '使用中',
  SANITIZING: '消毒中',
  MAINTENANCE: '维护中',
}

const BED_STATUS_PILL: Record<BedStatus, 'success' | 'primary' | 'warning' | 'danger'> = {
  FREE: 'success',
  IN_USE: 'primary',
  SANITIZING: 'warning',
  MAINTENANCE: 'danger',
}

export const useRoomStore = defineStore('room', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const rooms = ref<Room[]>([])
  const filterType = ref<RoomType | 'ALL'>('ALL')
  const filterStatus = ref<BedStatus | 'ALL'>('ALL')
  /** 操作记录（房间视角） */
  const logs = ref<RoomLog[]>([])

  const allBeds = computed(() => rooms.value.flatMap((r) => r.beds.map((b) => ({ room: r, bed: b }))))
  const total = computed(() => allBeds.value.length)
  const inUse = computed(() => allBeds.value.filter((x) => x.bed.status === 'IN_USE').length)
  const free = computed(() => allBeds.value.filter((x) => x.bed.status === 'FREE').length)
  const sanitizing = computed(() => allBeds.value.filter((x) => x.bed.status === 'SANITIZING').length)
  const maintenance = computed(() => allBeds.value.filter((x) => x.bed.status === 'MAINTENANCE').length)

  const filteredRooms = computed(() => {
    let arr = rooms.value
    if (filterType.value !== 'ALL') arr = arr.filter((r) => r.type === filterType.value)
    if (filterStatus.value !== 'ALL') {
      arr = arr.filter((r) => r.beds.some((b) => b.status === filterStatus.value))
    }
    return arr
  })

  function getRoom(id: string) {
    return rooms.value.find((r) => r.id === id)
  }
  function findBed(roomId: string, bedId: string) {
    return getRoom(roomId)?.beds.find((b) => b.id === bedId)
  }

  function addLog(roomCode: string, text: string, bedCode?: string) {
    logs.value.unshift({
      id: nextId('rlog'),
      at: new Date().toISOString(),
      by: auth.user.name,
      roomCode,
      bedCode,
      text,
    })
  }

  /** 入住：空闲 → 使用中 */
  function occupy(roomId: string, bedId: string, customerName: string, project?: string): boolean {
    const room = getRoom(roomId)
    const bed = room?.beds.find((b) => b.id === bedId)
    if (!room || !bed || bed.status !== 'FREE' || !auth.can('room:edit')) return false
    bed.status = 'IN_USE'
    bed.customerName = customerName
    bed.project = project
    bed.occupiedAt = new Date().toISOString()
    addLog(room.code, `${bed.code} 入住：${customerName}${project ? '（' + project + '）' : ''}`, bed.code)
    activity.log(auth.user.name, `${room.code} ${bed.code} 入住：${customerName}`, bed.id)
    return true
  }

  /** 退房：使用中 → 消毒中 */
  function release(roomId: string, bedId: string): boolean {
    const room = getRoom(roomId)
    const bed = room?.beds.find((b) => b.id === bedId)
    if (!room || !bed || bed.status !== 'IN_USE' || !auth.can('room:edit')) return false
    const customer = bed.customerName
    bed.status = 'SANITIZING'
    bed.customerName = undefined
    bed.project = undefined
    bed.occupiedAt = undefined
    addLog(room.code, `${bed.code} 退房：${customer || ''}，进入消毒`, bed.code)
    activity.log(auth.user.name, `${room.code} ${bed.code} 退房，进入消毒`, bed.id)
    return true
  }

  /** 清洁确认：消毒中 → 空闲 */
  function clean(roomId: string, bedId: string): boolean {
    const room = getRoom(roomId)
    const bed = room?.beds.find((b) => b.id === bedId)
    if (!room || !bed || bed.status !== 'SANITIZING' || !auth.can('room:edit')) return false
    bed.status = 'FREE'
    bed.note = undefined
    addLog(room.code, `${bed.code} 清洁确认，恢复空闲`, bed.code)
    activity.log(auth.user.name, `${room.code} ${bed.code} 清洁确认`, bed.id)
    return true
  }

  /** 设维护：任意 → 维护中 */
  function setMaintenance(roomId: string, bedId: string, reason: string): boolean {
    const room = getRoom(roomId)
    const bed = room?.beds.find((b) => b.id === bedId)
    if (!room || !bed || bed.status === 'MAINTENANCE' || !auth.can('room:edit')) return false
    bed.status = 'MAINTENANCE'
    bed.note = reason
    bed.customerName = undefined
    bed.project = undefined
    bed.occupiedAt = undefined
    addLog(room.code, `${bed.code} 设为维护：${reason}`, bed.code)
    activity.log(auth.user.name, `${room.code} ${bed.code} 设为维护：${reason}`, bed.id)
    return true
  }

  /** 维护恢复：维护中 → 消毒中（必须先消毒再恢复空闲） */
  function restore(roomId: string, bedId: string): boolean {
    const room = getRoom(roomId)
    const bed = room?.beds.find((b) => b.id === bedId)
    if (!room || !bed || bed.status !== 'MAINTENANCE' || !auth.can('room:edit')) return false
    bed.status = 'SANITIZING'
    bed.note = undefined
    addLog(room.code, `${bed.code} 维护完成，进入消毒`, bed.code)
    activity.log(auth.user.name, `${room.code} ${bed.code} 维护恢复，进入消毒`, bed.id)
    return true
  }

  // ===== 种子 =====
  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    const now = new Date()
    const minsAgo = (m: number) => new Date(now.getTime() - m * 60_000).toISOString()
    const roomSeed: Array<{ code: string; name: string; type: RoomType; beds: Array<Partial<Bed> & { code: string; status: BedStatus }> }> = [
      {
        code: 'A01', name: '激光治疗室', type: 'TREATMENT',
        beds: [
          { code: 'A01-1', status: 'IN_USE', customerName: '陈美玲', project: '皮秒激光', occupiedAt: minsAgo(25) },
          { code: 'A01-2', status: 'FREE' },
        ],
      },
      {
        code: 'A02', name: '射频治疗室', type: 'TREATMENT',
        beds: [
          { code: 'A02-1', status: 'IN_USE', customerName: '赵雨晴', project: '热玛吉', occupiedAt: minsAgo(50) },
          { code: 'A02-2', status: 'SANITIZING' },
        ],
      },
      {
        code: 'A03', name: '超声刀室', type: 'TREATMENT',
        beds: [
          { code: 'A03-1', status: 'FREE' },
          { code: 'A03-2', status: 'MAINTENANCE', note: '治疗床导轨异响，待维修' },
        ],
      },
      {
        code: 'B01', name: '注射室 1', type: 'TREATMENT',
        beds: [
          { code: 'B01-1', status: 'IN_USE', customerName: '孙佳宁', project: '玻尿酸填充', occupiedAt: minsAgo(15) },
        ],
      },
      {
        code: 'B02', name: '注射室 2', type: 'TREATMENT',
        beds: [
          { code: 'B02-1', status: 'FREE' },
          { code: 'B02-2', status: 'FREE' },
        ],
      },
      {
        code: 'C01', name: 'VIP 咨询室', type: 'CONSULT',
        beds: [{ code: 'C01-1', status: 'IN_USE', customerName: '王晓明', project: '方案咨询', occupiedAt: minsAgo(35) }],
      },
      {
        code: 'C02', name: '咨询室', type: 'CONSULT',
        beds: [{ code: 'C02-1', status: 'FREE' }],
      },
      {
        code: 'D01', name: '术后观察室', type: 'OBSERVE',
        beds: [
          { code: 'D01-1', status: 'IN_USE', customerName: '李雯', project: '术后观察', occupiedAt: minsAgo(70) },
          { code: 'D01-2', status: 'SANITIZING' },
          { code: 'D01-3', status: 'FREE' },
        ],
      },
      {
        code: 'D02', name: '恢复室', type: 'RECOVERY',
        beds: [
          { code: 'D02-1', status: 'FREE' },
          { code: 'D02-2', status: 'FREE' },
        ],
      },
    ]
    roomSeed.forEach((r) => {
      const room: Room = {
        id: nextId('room'),
        code: r.code,
        name: r.name,
        type: r.type,
        beds: r.beds.map((b) => ({
          id: nextId('bed'),
          code: b.code,
          status: b.status,
          customerName: b.customerName,
          project: b.project,
          occupiedAt: b.occupiedAt,
          note: b.note,
        })),
      }
      rooms.value.push(room)
    })
  }

  /** 新建房间 */
  function addRoom(data: { code: string; name: string; type: RoomType; bedCount?: number }): boolean {
    if (!auth.can('room:edit')) return false
    const bedCount = data.bedCount ?? 2
    const beds: Bed[] = Array.from({ length: bedCount }, (_, i) => ({
      id: nextId('bed'),
      code: `${data.code}-B${i + 1}`,
      status: 'FREE' as BedStatus,
      customerName: '',
      project: '',
      occupiedAt: '',
      note: '',
    }))
    const room: Room = {
      id: nextId('room'),
      code: data.code,
      name: data.name,
      type: data.type,
      beds,
    }
    rooms.value.push(room)
    addLog(room.code, `新建房间（${bedCount} 张床位）`)
    activity.log(auth.user.name, `新建房间 ${room.name}（${room.code}）`, room.id)
    return true
  }

  return {
    rooms, logs, filterType, filterStatus,
    total, inUse, free, sanitizing, maintenance,
    filteredRooms, allBeds,
    getRoom, findBed,
    occupy, release, clean, setMaintenance, restore, addRoom,
    seed,
    ROOM_TYPE_LABEL, BED_STATUS_LABEL, BED_STATUS_PILL,
  }
})
