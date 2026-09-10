// ============================================================
// M4-18 复购回访 store（复购 / 资产转移单据 + 知情同意 + 三方双签）
// 已接真实 txn-service 域 /api/txn/repurchase（原为零接线后端域，本卡收口 🔧→✅）。
// 适配层（铁律：模板/样式零改动，只换数据源）：
//  - id=repurchaseNo；金额后端单位「分」，视图层用 yuan()/fen() 换算，禁止浮点累计
//  - 后端实体不冗余客户名：load 后按 customerId 逐路 GET /customer/{id} 富化并 hydrate 客户缓存
//  - 权限/状态机/账实校验全部后端兜底，400/403/404 中文经 errMsg 外露
//  - 写动作失败 toast 中文并返回 null/false；终态（已完成/已拒绝）不可再签
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { useCustomerStore } from './customer'
import { useToast } from '@/composables/useToast'
import { errMsg } from './m5Coupon'
import { getCustomer } from '@/api/customer'
import {
  listRepurchase, createRepurchase, signRepurchase,
  type RepurchaseDTO, type CreateRepurchaseCmd, type TripleSignCmd,
} from '@/api/repurchase'

export interface RepurchaseRecord {
  id: string
  repurchaseNo: string
  customerId: string
  storeCode: string
  bizType: string
  targetProject: string
  fromCardNo: string
  toCardNo: string
  transferTimes: number
  transferAmount: number
  consentAck: boolean
  consentText: string
  status: string
  sign1: string
  sign1Role: string
  sign2: string
  sign2Role: string
  sign3: string
  sign3Role: string
  signedAt1: string
  signedAt2: string
  signedAt3: string
  note: string
  createdAt: string
}

const text = (v: string | null) => v ?? ''
const num = (v: number | null) => v ?? 0

export const useRepurchaseStore = defineStore('repurchase', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()
  const customer = useCustomerStore()
  const toast = useToast()

  const records = ref<RepurchaseRecord[]>([])
  const loading = ref(false)

  /** 兼容旧演示入口：真实数据由页面 onMounted 调 load 拉取 */
  function seed() {}

  const pending = computed(() => records.value.filter((r) => r.status === '待签核'))
  const completed = computed(() => records.value.filter((r) => r.status === '已完成'))
  const rejected = computed(() => records.value.filter((r) => r.status === '已拒绝'))

  function get(no: string) {
    return records.value.find((r) => r.repurchaseNo === no) ?? null
  }

  function customerName(customerId: string) {
    return customer.nameOf(customerId)
  }

  /** 分 → 元（展示用） */
  function yuan(fen: number) {
    return (fen / 100).toFixed(2)
  }

  function adapt(d: RepurchaseDTO): RepurchaseRecord {
    return {
      id: d.repurchaseNo,
      repurchaseNo: d.repurchaseNo,
      customerId: d.customerId,
      storeCode: d.storeCode,
      bizType: d.bizType,
      targetProject: text(d.targetProject),
      fromCardNo: text(d.fromCardNo),
      toCardNo: text(d.toCardNo),
      transferTimes: num(d.transferTimes),
      transferAmount: num(d.transferAmount),
      consentAck: d.consentAck,
      consentText: text(d.consentText),
      status: d.status,
      sign1: text(d.sign1),
      sign1Role: text(d.sign1Role),
      sign2: text(d.sign2),
      sign2Role: text(d.sign2Role),
      sign3: text(d.sign3),
      sign3Role: text(d.sign3Role),
      signedAt1: text(d.signedAt1),
      signedAt2: text(d.signedAt2),
      signedAt3: text(d.signedAt3),
      note: text(d.note),
      createdAt: d.createdAt,
    }
  }

  /** 后端实体不冗余客户名：对未入缓存的 customerId 逐路拉详情富化（列表量小，去重并发） */
  async function enrichCustomerNames(list: RepurchaseDTO[]) {
    const missing = [...new Set(
      list.map((r) => r.customerId).filter((id) => !customer.get(id)),
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

  /** 拉取单据列表（后端按 JWT 锁本店数据域） */
  async function load(): Promise<boolean> {
    loading.value = true
    try {
      const res = await listRepurchase()
      const list = res.data ?? []
      await enrichCustomerNames(list)
      records.value = list.map(adapt)
      return true
    } catch (e) {
      records.value = []
      toast.error(errMsg(e, '复购单据列表加载失败'))
      return false
    } finally {
      loading.value = false
    }
  }

  async function create(input: {
    customerId: string
    bizType: string
    targetProject?: string
    fromCardNo?: string
    toCardNo?: string
    transferTimes?: number
    /** 金额，单位「元」；入栈前转分 */
    transferAmountYuan?: number
    consentText?: string
    note?: string
  }): Promise<RepurchaseRecord | null> {
    const cmd: CreateRepurchaseCmd = {
      customerId: input.customerId,
      storeCode: auth.user.storeId,
      bizType: input.bizType,
      targetProject: input.targetProject?.trim() || undefined,
      fromCardNo: input.fromCardNo?.trim() || undefined,
      toCardNo: input.toCardNo?.trim() || undefined,
      transferTimes: input.transferTimes || undefined,
      transferAmount: input.transferAmountYuan != null
        ? Math.round(input.transferAmountYuan * 100)
        : undefined,
      consentAck: true,
      consentText: input.consentText?.trim() || undefined,
      note: input.note?.trim() || undefined,
    }
    try {
      const res = await createRepurchase(cmd)
      const r = adapt(res.data)
      activity.log(
        auth.user.name,
        `新建${r.bizType}单 ${r.repurchaseNo}（${customer.nameOf(r.customerId)}）`,
        r.id,
      )
      await load()
      return r
    } catch (e) {
      toast.error(errMsg(e, '新建复购/资产转移单失败'))
      return null
    }
  }

  /**
   * 三方双签：签核通过或拒签。
   * 后端契约：即使拒签也要求三签姓名齐全且互异（校验先于 reject 分支），故表单同口径。
   */
  async function sign(
    no: string,
    cmd: TripleSignCmd,
  ): Promise<boolean> {
    const isReject = !!cmd.reject
    try {
      await signRepurchase(no, cmd)
      activity.log(
        auth.user.name,
        isReject
          ? `复购单 ${no} 三方拒签，单据置「已拒绝」`
          : `复购单 ${no} 三方双签完成（客户/经办/店长）`,
        no,
      )
      await load()
      return true
    } catch (e) {
      toast.error(errMsg(e, isReject ? '拒签失败' : '三方签核失败'))
      return false
    }
  }

  return {
    records, loading, pending, completed, rejected,
    get, customerName, yuan, seed,
    load, create, sign,
  }
})
