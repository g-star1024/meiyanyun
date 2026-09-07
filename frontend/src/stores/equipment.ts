// ============================================================
// Equipment 设备资产 store（M2-05，B13 接真实 API）
// 覆盖医美门店设备台账：激光/射频/超声/注射等仪器，
// 状态 NORMAL 正常 / CALIBRATING 校准中 / REPAIRING 维修中 / DISABLED 停用。
// 权威源：store-service /stores/equipments（台账 + 校准/维保记录嵌套）。
// 读金额单位「元」（purchaseAmount/depreciated/cost，后端已由分换算）；
// 写金额单位「分」（purchaseAmountFen/costFen），前端由「元」换算。
// 登记记录的回写规则（下次日期/状态恢复/折旧累加）由服务端统一执行，前端刷新即可。
// API 不可用或空库时回落本地演示数据（demo 标记）。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { useStoreContext } from './storeContext'
import {
  listEquipments, createEquipment, setEquipmentStatus, addEquipmentRecord,
  type EquipmentDTO, type MaintenanceRecordDTO,
} from '@/api/equipment'

export type EquipmentStatus = 'NORMAL' | 'CALIBRATING' | 'REPAIRING' | 'DISABLED'
export type EquipmentCategory = 'LASER' | 'RF' | 'ULTRASOUND' | 'INJECTION' | 'MONITOR' | 'OTHER'
export type MaintenanceType = 'CALIBRATION' | 'MAINTENANCE' | 'REPAIR'

export interface MaintenanceRecord {
  id: string
  type: MaintenanceType
  at: string
  by: string
  /** 服务商 / 工程师 */
  vendor?: string
  /** 内容/结果 */
  summary: string
  /** 本次之后的下次日期 */
  nextAt?: string
  /** 费用（元） */
  cost?: number
}

export interface Equipment {
  id: string
  /** 资产编号 */
  assetNo: string
  name: string
  brand?: string
  model?: string
  category: EquipmentCategory
  /** 所在位置 */
  location: string
  status: EquipmentStatus
  /** 购置日期 */
  purchasedAt: string
  /** 购置金额（元） */
  purchaseAmount: number
  /** 预计使用年限 */
  lifespanYears: number
  /** 已累计折旧（元） */
  depreciated: number
  nextCalibrationAt?: string
  nextMaintenanceAt?: string
  records: MaintenanceRecord[]
  note?: string
}

const STATUS_LABEL: Record<EquipmentStatus, string> = {
  NORMAL: '正常',
  CALIBRATING: '校准中',
  REPAIRING: '维修中',
  DISABLED: '停用',
}
const STATUS_PILL: Record<EquipmentStatus, 'success' | 'warning' | 'danger' | 'disabled'> = {
  NORMAL: 'success',
  CALIBRATING: 'warning',
  REPAIRING: 'danger',
  DISABLED: 'disabled',
}
const CATEGORY_LABEL: Record<EquipmentCategory, string> = {
  LASER: '激光仪器',
  RF: '射频仪器',
  ULTRASOUND: '超声仪器',
  INJECTION: '注射设备',
  MONITOR: '监护设备',
  OTHER: '其他',
}
const MAINT_TYPE_LABEL: Record<MaintenanceType, string> = {
  CALIBRATION: '校准',
  MAINTENANCE: '维保',
  REPAIR: '维修',
}
/** 临期提醒阈值（天） */
const DUE_SOON_DAYS = 14

const yuan2fen = (yuan: number) => Math.round((Number(yuan) || 0) * 100)

function daysUntil(iso?: string): number | null {
  if (!iso) return null
  const ms = new Date(iso).getTime() - Date.now()
  return Math.ceil(ms / 86_400_000)
}

export const useEquipmentStore = defineStore('equipment', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()
  const ctx = useStoreContext()

  const list = ref<Equipment[]>([])
  const filterStatus = ref<EquipmentStatus | 'ALL'>('ALL')
  const filterCategory = ref<EquipmentCategory | 'ALL'>('ALL')
  const keyword = ref('')
  const loaded = ref(false)
  const demo = ref(false)

  const normal = computed(() => list.value.filter((x) => x.status === 'NORMAL'))
  const calibrating = computed(() => list.value.filter((x) => x.status === 'CALIBRATING'))
  const repairing = computed(() => list.value.filter((x) => x.status === 'REPAIRING'))
  const disabled = computed(() => list.value.filter((x) => x.status === 'DISABLED'))

  /** 待校准：状态为校准中 或 下次校准日期 14 天内/已过期 */
  const dueCalibration = computed(() =>
    list.value.filter((x) => {
      if (x.status === 'DISABLED') return false
      const d = daysUntil(x.nextCalibrationAt)
      return x.status === 'CALIBRATING' || (d !== null && d <= DUE_SOON_DAYS)
    }),
  )

  const filtered = computed(() => {
    let arr = list.value
    if (filterStatus.value !== 'ALL') arr = arr.filter((x) => x.status === filterStatus.value)
    if (filterCategory.value !== 'ALL') arr = arr.filter((x) => x.category === filterCategory.value)
    const kw = keyword.value.trim().toLowerCase()
    if (kw) {
      arr = arr.filter(
        (x) =>
          x.assetNo.toLowerCase().includes(kw) ||
          x.name.toLowerCase().includes(kw) ||
          x.brand?.toLowerCase().includes(kw) ||
          x.model?.toLowerCase().includes(kw) ||
          x.location.toLowerCase().includes(kw),
      )
    }
    return arr
  })

  function get(id: string) {
    return list.value.find((x) => x.id === id)
  }

  function netValue(e: Equipment) {
    return Math.max(0, e.purchaseAmount - e.depreciated)
  }

  function dueStatus(e: Equipment): { calibration: number | null; maintenance: number | null } {
    return {
      calibration: daysUntil(e.nextCalibrationAt),
      maintenance: daysUntil(e.nextMaintenanceAt),
    }
  }

  // ---- DTO 映射（读金额「元」直接取用，日期为 yyyy-MM-dd 字符串） ----
  function mapRecord(r: MaintenanceRecordDTO): MaintenanceRecord {
    return {
      id: String(r.id),
      type: r.type,
      at: r.at || '',
      by: r.by || '系统',
      vendor: r.vendor || undefined,
      summary: r.summary,
      nextAt: r.nextAt || undefined,
      cost: Number(r.cost) || 0,
    }
  }

  function mapEquipment(e: EquipmentDTO): Equipment {
    return {
      id: String(e.id),
      assetNo: e.assetNo,
      name: e.name,
      brand: e.brand || undefined,
      model: e.model || undefined,
      category: e.category,
      location: e.location || '未设置',
      status: e.status,
      purchasedAt: e.purchasedAt || '',
      purchaseAmount: Number(e.purchaseAmount) || 0,
      lifespanYears: e.lifespanYears || 8,
      depreciated: Number(e.depreciated) || 0,
      nextCalibrationAt: e.nextCalibrationAt || undefined,
      nextMaintenanceAt: e.nextMaintenanceAt || undefined,
      records: (e.records ?? []).map(mapRecord),
      note: e.note || undefined,
    }
  }

  // ---- 拉取 ----
  let seeding: Promise<void> | null = null
  function seed(force = false): Promise<void> {
    if (seeding && !force) return seeding
    if (loaded.value && !force) return Promise.resolve()
    seeding = (async () => {
      try {
        await ctx.loadStores()
        const storeCode = ctx.currentStoreCode
        const resp = await listEquipments({ storeCode })
        const eqList = resp.data ?? []
        if (eqList.length > 0) {
          list.value = eqList.map(mapEquipment)
          demo.value = false
        } else {
          loadDemo()
          demo.value = true
        }
        loaded.value = true
      } catch (e) {
        console.error('[equipment] 加载设备台账失败，回落本地演示数据', e)
        loadDemo()
        demo.value = true
        loaded.value = true
      }
    })()
    return seeding
  }
  void seed()

  /** 状态变更（真实持久化 POST /equipments/{id}/status） */
  async function setStatus(id: string, status: EquipmentStatus, note?: string): Promise<boolean> {
    const e = list.value.find((x) => x.id === id)
    if (!e) return false
    if (!auth.can('equipment:edit')) {
      console.warn('[equipment] 无 equipment:edit 权限')
      return false
    }
    await setEquipmentStatus(Number(id), { storeCode: ctx.currentStoreCode, status, note })
    activity.log(auth.user.name, `设备 ${e.assetNo} 状态变更为 ${STATUS_LABEL[status]}`, e.id)
    await seed(true)
    return true
  }

  /**
   * 登记校准/维保/维修记录（真实持久化 POST /equipments/{id}/records）。
   * 回写规则（下次日期/状态恢复/折旧累加）由服务端统一执行，强制刷新后呈现。
   */
  async function addRecord(
    id: string,
    rec: Omit<MaintenanceRecord, 'id' | 'at' | 'by'> & { at?: string },
  ): Promise<boolean> {
    const e = list.value.find((x) => x.id === id)
    if (!e) return false
    if (!auth.can('equipment:edit')) {
      console.warn('[equipment] 无 equipment:edit 权限')
      return false
    }
    await addEquipmentRecord(Number(id), {
      storeCode: ctx.currentStoreCode,
      type: rec.type,
      summary: rec.summary,
      vendor: rec.vendor || undefined,
      at: rec.at ? new Date(rec.at).toISOString() : undefined,
      nextAt: rec.nextAt ? new Date(rec.nextAt).toISOString() : undefined,
      costFen: rec.cost ? yuan2fen(rec.cost) : undefined,
    })
    activity.log(auth.user.name, `设备 ${e.assetNo} 新增${MAINT_TYPE_LABEL[rec.type]}记录`, e.id)
    await seed(true)
    return true
  }

  /** 新建设备建档（真实持久化 POST /equipments；金额元→分，折旧恒 0） */
  async function addEquipment(data: Omit<Equipment, 'id' | 'records'>): Promise<boolean> {
    if (!auth.can('equipment:edit')) {
      console.warn('[equipment] 无 equipment:edit 权限')
      return false
    }
    await createEquipment({
      storeCode: ctx.currentStoreCode,
      assetNo: data.assetNo,
      name: data.name,
      brand: data.brand || undefined,
      model: data.model || undefined,
      category: data.category,
      location: data.location || undefined,
      status: data.status || undefined,
      purchasedAt: data.purchasedAt ? new Date(data.purchasedAt).toISOString() : new Date().toISOString(),
      purchaseAmountFen: yuan2fen(data.purchaseAmount),
      lifespanYears: data.lifespanYears || 8,
      nextCalibrationAt: data.nextCalibrationAt ? new Date(data.nextCalibrationAt).toISOString() : undefined,
      nextMaintenanceAt: data.nextMaintenanceAt ? new Date(data.nextMaintenanceAt).toISOString() : undefined,
      note: data.note || undefined,
    })
    activity.log(auth.user.name, `新建设备 ${data.name}（${data.assetNo}）`, data.assetNo)
    await seed(true)
    return true
  }

  // ---- 离线演示数据（API 不可用/空库回落） ----
  function loadDemo() {
    const now = new Date()
    const daysAgo = (d: number) => new Date(now.getTime() - d * 86_400_000).toISOString()
    const daysLater = (d: number) => new Date(now.getTime() + d * 86_400_000).toISOString()
    const yearsAgo = (y: number) => new Date(now.getTime() - y * 365 * 86_400_000).toISOString()

    const data: Array<Omit<Equipment, 'id' | 'records'> & { records: MaintenanceRecord[] }> = [
      {
        assetNo: 'EQ-L001', name: '皮秒激光治疗仪', brand: '赛诺秀', model: 'PicoSure', category: 'LASER',
        location: 'A01 激光治疗室', status: 'NORMAL',
        purchasedAt: yearsAgo(2), purchaseAmount: 680000, lifespanYears: 8, depreciated: 168000,
        nextCalibrationAt: daysLater(60), nextMaintenanceAt: daysLater(20),
        records: [
          { id: nextId('mrec'), type: 'CALIBRATION', at: daysAgo(120), by: '苏晴（店长）', vendor: '赛诺秀原厂', summary: '年度能量校准，输出稳定', nextAt: daysLater(60), cost: 4800 },
          { id: nextId('mrec'), type: 'MAINTENANCE', at: daysAgo(70), by: '吴桐（运营）', vendor: '华东医械', summary: '季度光路除尘与手柄检测', nextAt: daysLater(20), cost: 1200 },
        ],
      },
      {
        assetNo: 'EQ-R002', name: '热玛吉射频治疗仪', brand: 'Solta', model: 'Thermage FLX', category: 'RF',
        location: 'A02 射频治疗室', status: 'CALIBRATING',
        purchasedAt: yearsAgo(1.5), purchaseAmount: 520000, lifespanYears: 8, depreciated: 96000,
        nextCalibrationAt: daysLater(2), nextMaintenanceAt: daysLater(90),
        records: [
          { id: nextId('mrec'), type: 'MAINTENANCE', at: daysAgo(60), by: '吴桐（运营）', vendor: 'Solta 中国', summary: '半年度常规维保，手柄接触面更换', nextAt: daysLater(90), cost: 3600 },
          { id: nextId('mrec'), type: 'CALIBRATION', at: daysAgo(360), by: '苏晴（店长）', vendor: 'Solta 中国', summary: '年度能量校准', nextAt: daysLater(2), cost: 5200 },
        ],
      },
      {
        assetNo: 'EQ-U003', name: '超声刀治疗仪', brand: 'Merz', model: 'Ulthera', category: 'ULTRASOUND',
        location: 'A03 超声刀室', status: 'REPAIRING',
        purchasedAt: yearsAgo(3), purchaseAmount: 420000, lifespanYears: 8, depreciated: 196000,
        nextCalibrationAt: daysLater(30), nextMaintenanceAt: daysLater(120),
        note: 'E07 报错，手柄无法出能，等待配件',
        records: [
          { id: nextId('mrec'), type: 'REPAIR', at: daysAgo(3), by: '吴桐（运营）', vendor: 'Merz 售后', summary: '报修 E07，初步判定手柄主板故障，配件订购中', nextAt: daysLater(120), cost: 0 },
          { id: nextId('mrec'), type: 'CALIBRATION', at: daysAgo(330), by: '苏晴（店长）', vendor: 'Merz 售后', summary: '年度校准通过', nextAt: daysLater(30), cost: 4200 },
        ],
      },
      {
        assetNo: 'EQ-I004', name: '水光注射仪', brand: 'CUSM', model: 'Vital Injector 2', category: 'INJECTION',
        location: 'B01 注射室', status: 'NORMAL',
        purchasedAt: yearsAgo(1), purchaseAmount: 38000, lifespanYears: 5, depreciated: 6800,
        nextCalibrationAt: daysLater(120), nextMaintenanceAt: daysLater(40),
        records: [
          { id: nextId('mrec'), type: 'MAINTENANCE', at: daysAgo(50), by: '顾屿（主治医师）', vendor: '代理工程师', summary: '注射压力校准，密封圈更换', nextAt: daysLater(40), cost: 480 },
        ],
      },
      {
        assetNo: 'EQ-M005', name: '多参数监护仪', brand: '迈瑞', model: 'uMEC12', category: 'MONITOR',
        location: 'D01 术后观察室', status: 'NORMAL',
        purchasedAt: yearsAgo(4), purchaseAmount: 26000, lifespanYears: 8, depreciated: 13200,
        nextCalibrationAt: daysLater(-5), nextMaintenanceAt: daysLater(60),
        note: '校准已过期 5 天，需尽快安排',
        records: [
          { id: nextId('mrec'), type: 'CALIBRATION', at: daysAgo(370), by: '苏晴（店长）', vendor: '迈瑞医疗', summary: '年度计量校准', nextAt: daysLater(-5), cost: 800 },
        ],
      },
      {
        assetNo: 'EQ-L006', name: '二氧化碳激光治疗仪', brand: '科英', model: 'KL-R', category: 'LASER',
        location: 'A01 激光治疗室', status: 'DISABLED',
        purchasedAt: yearsAgo(7), purchaseAmount: 128000, lifespanYears: 8, depreciated: 112000,
        note: '导光臂老化，维修成本过高，计划资产报废',
        records: [
          { id: nextId('mrec'), type: 'REPAIR', at: daysAgo(30), by: '苏晴（店长）', vendor: '科英售后', summary: '导光臂损坏，维修费报价 2.8 万，建议停用', cost: 0 },
        ],
      },
      {
        assetNo: 'EQ-O007', name: '冷喷补水仪', brand: '日韩', model: 'A-One', category: 'OTHER',
        location: 'D02 恢复室', status: 'NORMAL',
        purchasedAt: yearsAgo(0.5), purchaseAmount: 4800, lifespanYears: 5, depreciated: 480,
        nextCalibrationAt: undefined, nextMaintenanceAt: daysLater(180),
        records: [
          { id: nextId('mrec'), type: 'MAINTENANCE', at: daysAgo(5), by: '周敏（美容师）', summary: '内部水垢清洁', nextAt: daysLater(180), cost: 0 },
        ],
      },
      {
        assetNo: 'EQ-R008', name: '黄金射频微针', brand: 'Jeisys', model: 'Genius', category: 'RF',
        location: 'A02 射频治疗室', status: 'NORMAL',
        purchasedAt: yearsAgo(2), purchaseAmount: 260000, lifespanYears: 8, depreciated: 56000,
        nextCalibrationAt: daysLater(10), nextMaintenanceAt: daysLater(15),
        records: [
          { id: nextId('mrec'), type: 'MAINTENANCE', at: daysAgo(75), by: '吴桐（运营）', vendor: 'Jeisys 代理', summary: '微针头更换与频率校准', nextAt: daysLater(15), cost: 2800 },
        ],
      },
    ]
    list.value = data.map((e) => ({ ...e, id: nextId('eq') }))
  }

  return {
    list, filterStatus, filterCategory, keyword,
    normal, calibrating, repairing, disabled, dueCalibration, filtered,
    get, netValue, dueStatus, setStatus, addRecord, addEquipment, seed, loaded, demo,
    STATUS_LABEL, STATUS_PILL, CATEGORY_LABEL, MAINT_TYPE_LABEL, DUE_SOON_DAYS,
  }
})
