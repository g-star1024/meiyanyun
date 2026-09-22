// ============================================================
// Contract 合同聚合 store（P5-B85 卡4 已接真实 txn-service 域 /api/txn/contracts）
// 一个合同可对应多订单/多资产，承载退款条款（冷静期、违约金比例）。
// 状态机（后端兜底）：草稿 → 生效中 → 已履行；草稿|生效中 → 已终止（须原因）。
// 适配层（铁律：模板/样式零改动，只换数据源；公开 API 签名不变）：
//  - id=contractNo；状态中文四值 ↔ 英文枚举 DRAFT/EFFECTIVE/COMPLETED/TERMINATED
//  - totalAmount 后端「分」→ 前端「元」；penaltyRate 后端基点万分比 → 前端 0-1 小数
//  - ordersJson/assetsJson 为后端不透明 TEXT 快照：前端 JSON.parse 容错还原
//    （orders.amount 单位「元」由前端自约定，仅作展示快照，不进资金台账）
//  - 后端实体不冗余客户名：load 后按 customerId 逐路 GET /customer/{id} 富化并 hydrate 客户缓存
//  - 权限/状态机/校验全部后端兜底，400/403/404/409 中文经 errMsg 外露 toast
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { useCustomerStore } from './customer'
import { useToast } from '@/composables/useToast'
import { errMsg } from './m5Coupon'
import { shDateStr } from '@/utils/datetime'
import { getCustomer } from '@/api/customer'
import {
  listContracts, createContract, activateContract, completeContract, terminateContract,
  type ContractDTO, type CreateContractCmd,
} from '@/api/contract'

export type ContractType = 'COURSE' | 'STORED_VALUE' | 'PACKAGE' | 'SERVICE'
export type ContractStatus = 'DRAFT' | 'EFFECTIVE' | 'COMPLETED' | 'TERMINATED'

export interface ContractOrder {
  orderNo: string
  amount: number
  itemName: string
}

export interface Contract {
  id: string
  contractNo: string
  customerId: string
  customerName: string
  type: ContractType
  title: string
  storeId: string
  signDate: string
  totalAmount: number
  /** 关联订单 */
  orders: ContractOrder[]
  /** 关联资产 ID（疗程/储值卡） */
  assetIds: string[]
  /** 冷静期天数（期内可无责退） */
  coolingDays: number
  /** 违约金比例（0-1，冷静期后退卡按此倒扣） */
  penaltyRate: number
  /** 退款/终止条款说明 */
  refundTerms: string
  remarks?: string
  attachments?: string[]
  signedByName: string
  status: ContractStatus
  createdAt: string
  effectiveAt?: string
  completedAt?: string
  terminatedAt?: string
  terminateReason?: string
}

export const CONTRACT_TYPE_LABEL: Record<ContractType, string> = {
  COURSE: '疗程合同',
  STORED_VALUE: '储值合同',
  PACKAGE: '套餐合同',
  SERVICE: '服务合同',
}

/** 后端中文状态 → 前端英文枚举（视图字典 CONTRACT_STATUS 以英文为 key） */
const STATUS_MAP: Record<string, ContractStatus> = {
  草稿: 'DRAFT',
  生效中: 'EFFECTIVE',
  已履行: 'COMPLETED',
  已终止: 'TERMINATED',
}

/** 快照 JSON 容错解析：非法/空一律回退 []（后端不透明 TEXT，绝不让解析异常炸掉列表） */
function parseOrders(json: string | null): ContractOrder[] {
  if (!json) return []
  try {
    const arr = JSON.parse(json)
    if (!Array.isArray(arr)) return []
    return arr
      .filter((o) => o && typeof o === 'object')
      .map((o) => ({
        orderNo: String(o.orderNo ?? ''),
        amount: Number(o.amount) || 0,
        itemName: String(o.itemName ?? ''),
      }))
  } catch {
    return []
  }
}

/** 卡资产快照容错解析：元素为对象取 cardNo，为字符串原样保留 */
function parseAssetIds(json: string | null): string[] {
  if (!json) return []
  try {
    const arr = JSON.parse(json)
    if (!Array.isArray(arr)) return []
    return arr
      .map((a) => (a && typeof a === 'object' ? String(a.cardNo ?? '') : String(a ?? '')))
      .filter((s) => s)
  } catch {
    return []
  }
}

export const useContractStore = defineStore('contract', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()
  const customer = useCustomerStore()
  const toast = useToast()

  const contracts = ref<Contract[]>([])
  const loading = ref(false)

  /** 兼容旧演示入口：真实数据由页面 onMounted 调 load 拉取 */
  function seed() {}

  const drafts = computed(() => contracts.value.filter((c) => c.status === 'DRAFT'))
  const effective = computed(() => contracts.value.filter((c) => c.status === 'EFFECTIVE'))
  const completed = computed(() => contracts.value.filter((c) => c.status === 'COMPLETED'))
  const terminated = computed(() => contracts.value.filter((c) => c.status === 'TERMINATED'))

  function get(id: string) {
    return contracts.value.find((c) => c.id === id)
  }

  function adapt(d: ContractDTO): Contract {
    return {
      id: d.contractNo,
      contractNo: d.contractNo,
      customerId: d.customerId,
      customerName: customer.nameOf(d.customerId),
      type: (d.contractType || 'COURSE') as ContractType,
      title: d.title,
      storeId: d.storeCode,
      signDate: d.signDate,
      totalAmount: d.totalAmount / 100,
      orders: parseOrders(d.ordersJson),
      assetIds: parseAssetIds(d.assetsJson),
      coolingDays: d.coolingDays ?? 7,
      penaltyRate: (d.penaltyRate ?? 2000) / 10000,
      refundTerms: d.refundTerms ?? '',
      remarks: d.remarks ?? undefined,
      signedByName: d.signedBy ?? '',
      status: STATUS_MAP[d.status] ?? 'DRAFT',
      createdAt: d.createdAt,
      effectiveAt: d.effectiveAt ?? undefined,
      completedAt: d.completedAt ?? undefined,
      terminatedAt: d.terminatedAt ?? undefined,
      terminateReason: d.terminateReason ?? undefined,
    }
  }

  /** 后端实体不冗余客户名：对未入缓存的 customerId 逐路拉详情富化（列表量小，去重并发） */
  async function enrichCustomerNames(list: ContractDTO[]) {
    const missing = [...new Set(
      list.map((c) => c.customerId).filter((id) => !customer.get(id)),
    )]
    if (!missing.length) return
    const results = await Promise.allSettled(missing.map((id) => getCustomer(id)))
    const found = results
      .filter((r): r is PromiseFulfilledResult<Awaited<ReturnType<typeof getCustomer>>> => r.status === 'fulfilled')
      .map((r) => r.value.data)
    customer.hydrate(found.map((c) => ({
      customerId: c.customerId,
      customerName: c.name,
      phone: c.phone,
      storeCode: c.storeCode,
    })))
  }

  /** 拉取合同列表（后端按 JWT 锁本店数据域，创建时间倒序） */
  async function load(): Promise<boolean> {
    loading.value = true
    try {
      const res = await listContracts()
      const list = res.data ?? []
      await enrichCustomerNames(list)
      contracts.value = list.map(adapt)
      return true
    } catch (e) {
      contracts.value = []
      toast.error(errMsg(e, '合同列表加载失败'))
      return false
    } finally {
      loading.value = false
    }
  }

  /** 新建草稿（后端无更新端点，本批仅创建；input.id 分支保留签名兼容但不可用） */
  async function saveDraft(input: Partial<Contract> & { customerId: string; customerName: string; title: string; totalAmount: number }): Promise<Contract | null> {
    if (!auth.can('contract:edit')) {
      console.warn('[contract] 无 contract:edit 权限')
      return null
    }
    if (input.id) {
      console.warn('[contract] 后端无合同更新端点，仅支持新建草稿')
      return null
    }
    const cmd: CreateContractCmd = {
      customerId: input.customerId,
      storeCode: auth.user.storeId,
      contractType: input.type || 'COURSE',
      title: input.title.trim(),
      signDate: input.signDate || shDateStr(),
      totalAmount: Math.round(input.totalAmount * 100),
      ordersJson: input.orders?.length ? JSON.stringify(input.orders) : undefined,
      assetsJson: input.assetIds?.length
        ? JSON.stringify(input.assetIds.map((no) => ({ cardNo: no })))
        : undefined,
      coolingDays: input.coolingDays ?? 7,
      penaltyRate: Math.round((input.penaltyRate ?? 0.2) * 10000),
      refundTerms: input.refundTerms || '',
      remarks: input.remarks,
      signedBy: auth.user.name,
    }
    try {
      const res = await createContract(cmd)
      const c = adapt(res.data)
      activity.log(auth.user.name, `创建合同草稿 ${c.contractNo}：${c.title}`, c.id)
      await load()
      return contracts.value.find((x) => x.id === c.id) ?? c
    } catch (e) {
      toast.error(errMsg(e, '新建合同草稿失败'))
      return null
    }
  }

  /** 生效（草稿 → 生效中；仅生效中可被复购/资产转移单 contractNo 引用） */
  async function activate(id: string): Promise<boolean> {
    if (!auth.can('contract:edit')) {
      console.warn('[contract] 无 contract:edit 权限')
      return false
    }
    try {
      await activateContract(id)
      activity.log(auth.user.name, `合同 ${id} 生效`, id)
      await load()
      return true
    } catch (e) {
      toast.error(errMsg(e, '合同生效失败'))
      return false
    }
  }

  /** 履行完成（生效中 → 已履行） */
  async function complete(id: string): Promise<boolean> {
    if (!auth.can('contract:edit')) {
      console.warn('[contract] 无 contract:edit 权限')
      return false
    }
    try {
      await completeContract(id)
      activity.log(auth.user.name, `合同 ${id} 已履行完成`, id)
      await load()
      return true
    } catch (e) {
      toast.error(errMsg(e, '合同履行完成失败'))
      return false
    }
  }

  /** 终止（草稿|生效中 → 已终止，后端强制原因非空） */
  async function terminate(id: string, reason: string): Promise<boolean> {
    if (!auth.can('contract:edit')) {
      console.warn('[contract] 无 contract:edit 权限')
      return false
    }
    try {
      await terminateContract(id, reason)
      activity.log(auth.user.name, `合同 ${id} 已终止：${reason}`, id)
      await load()
      return true
    } catch (e) {
      toast.error(errMsg(e, '合同终止失败'))
      return false
    }
  }

  /** 判断合同是否在冷静期内 */
  function inCoolingPeriod(c: Contract): boolean {
    if (c.status !== 'EFFECTIVE' || !c.effectiveAt) return false
    const eff = new Date(c.effectiveAt).getTime()
    return Date.now() - eff < c.coolingDays * 86400_000
  }

  /** 计算退卡应退金额（冷静期内全额；期内按违约金倒扣） */
  function refundEstimate(c: Contract, paidAmount: number): { refund: number; penalty: number; inCooling: boolean } {
    const inCooling = inCoolingPeriod(c)
    if (inCooling) return { refund: paidAmount, penalty: 0, inCooling: true }
    const penalty = Math.round(paidAmount * c.penaltyRate)
    return { refund: paidAmount - penalty, penalty, inCooling: false }
  }

  return {
    contracts, loading, drafts, effective, completed, terminated,
    get, saveDraft, activate, complete, terminate, inCoolingPeriod, refundEstimate,
    seed, load,
  }
})
