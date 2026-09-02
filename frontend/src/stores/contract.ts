// ============================================================
// Contract 合同聚合 store
// 一个合同可对应多订单/多资产，承载退款条款（冷静期、违约金比例）。
// 状态：草稿 → 生效中 → 已履行 / 已终止。
// 对齐 docs/business-flows.md：合同是交易与退款条款的法律载体。
// 后端就绪前以内存 + activity 流水兜底。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

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

export const useContractStore = defineStore('contract', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const contracts = ref<Contract[]>([])
  let seq = 0

  const drafts = computed(() => contracts.value.filter((c) => c.status === 'DRAFT'))
  const effective = computed(() => contracts.value.filter((c) => c.status === 'EFFECTIVE'))
  const completed = computed(() => contracts.value.filter((c) => c.status === 'COMPLETED'))
  const terminated = computed(() => contracts.value.filter((c) => c.status === 'TERMINATED'))

  function get(id: string) {
    return contracts.value.find((c) => c.id === id)
  }

  /** 新建/保存草稿 */
  function saveDraft(input: Partial<Contract> & { customerId: string; customerName: string; title: string; totalAmount: number }): Contract | null {
    if (!auth.can('contract:edit')) {
      console.warn('[contract] 无 contract:edit 权限')
      return null
    }
    if (input.id) {
      const c = contracts.value.find((x) => x.id === input.id)
      if (c && c.status === 'DRAFT') {
        Object.assign(c, input)
        return c
      }
      return null
    }
    seq += 1
    const c: Contract = {
      id: nextId('ct'),
      contractNo: `HT${Date.now().toString().slice(-8)}${seq}`,
      customerId: input.customerId,
      customerName: input.customerName,
      type: input.type || 'COURSE',
      title: input.title,
      storeId: auth.user.storeId,
      signDate: input.signDate || new Date().toISOString().slice(0, 10),
      totalAmount: input.totalAmount,
      orders: input.orders || [],
      assetIds: input.assetIds || [],
      coolingDays: input.coolingDays ?? 7,
      penaltyRate: input.penaltyRate ?? 0.2,
      refundTerms: input.refundTerms || '',
      remarks: input.remarks,
      attachments: input.attachments,
      signedByName: auth.user.name,
      status: 'DRAFT',
      createdAt: new Date().toISOString(),
    }
    contracts.value.unshift(c)
    activity.log(auth.user.name, `创建合同草稿 ${c.contractNo}：${c.title}`, c.id)
    return c
  }

  /** 生效（草稿 → 生效中） */
  function activate(id: string): boolean {
    const c = contracts.value.find((x) => x.id === id)
    if (!c || c.status !== 'DRAFT') return false
    if (!auth.can('contract:edit')) {
      console.warn('[contract] 无 contract:edit 权限')
      return false
    }
    c.status = 'EFFECTIVE'
    c.effectiveAt = new Date().toISOString()
    activity.log(auth.user.name, `合同 ${c.contractNo} 生效`, c.id)
    return true
  }

  /** 履行完成（生效中 → 已履行） */
  function complete(id: string): boolean {
    const c = contracts.value.find((x) => x.id === id)
    if (!c || c.status !== 'EFFECTIVE') return false
    if (!auth.can('contract:edit')) {
      console.warn('[contract] 无 contract:edit 权限')
      return false
    }
    c.status = 'COMPLETED'
    c.completedAt = new Date().toISOString()
    activity.log(auth.user.name, `合同 ${c.contractNo} 已履行完成`, c.id)
    return true
  }

  /** 终止（生效中 → 已终止，记录原因） */
  function terminate(id: string, reason: string): boolean {
    const c = contracts.value.find((x) => x.id === id)
    if (!c || c.status !== 'EFFECTIVE') return false
    if (!auth.can('contract:edit')) {
      console.warn('[contract] 无 contract:edit 权限')
      return false
    }
    c.status = 'TERMINATED'
    c.terminatedAt = new Date().toISOString()
    c.terminateReason = reason
    activity.log(auth.user.name, `合同 ${c.contractNo} 已终止：${reason}`, c.id)
    return true
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

  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    const now = Date.now()
    const seed: Array<Partial<Contract> & {
      customerName: string; title: string; type: ContractType; status: ContractStatus; totalAmount: number;
      effectiveDaysAgo?: number;
    }> = [
      {
        customerId: 'C-201', customerName: '王小姐', type: 'COURSE', title: '光子嫩肤 6 次疗程合同',
        totalAmount: 9800, status: 'EFFECTIVE', coolingDays: 7, penaltyRate: 0.2, effectiveDaysAgo: 12,
        orders: [{ orderNo: 'SO2026082001', amount: 9800, itemName: '光子嫩肤 6 次卡' }],
        assetIds: ['times-seed-1'], refundTerms: '冷静期 7 天内无责退款；超期按已消费次数原价扣减后，另收 20% 违约金。',
      },
      {
        customerId: 'C-202', customerName: '李女士', type: 'STORED_VALUE', title: '储值卡充值合同（¥10000 送 ¥1200）',
        totalAmount: 10000, status: 'EFFECTIVE', coolingDays: 7, penaltyRate: 0.15,
        orders: [{ orderNo: 'SO2026081801', amount: 10000, itemName: '储值充值 ¥10000' }],
        refundTerms: '冷静期 7 天内全额退款；赠送金额不退还；超期退款扣除 15% 违约金。',
      },
      {
        customerId: 'C-202', customerName: '李女士', type: 'PACKAGE', title: '热玛吉四代套餐合同',
        totalAmount: 26800, status: 'COMPLETED', coolingDays: 7, penaltyRate: 0.3,
        orders: [{ orderNo: 'SO2026071501', amount: 26800, itemName: '热玛吉四代 3 次套餐' }],
        assetIds: ['times-seed-2'], refundTerms: '套餐一经开启不退款；未消费部分可按原价折算转疗程。',
      },
      {
        customerId: 'C-203', customerName: '张同学', type: 'SERVICE', title: '痤疮治疗季度服务合同',
        totalAmount: 5980, status: 'DRAFT', coolingDays: 7, penaltyRate: 0.2,
        orders: [], refundTerms: '季度服务含 6 次复诊；冷静期内可全额退款。',
      },
    ]
    seed.forEach((s, i) => {
      seq += 1
      const status = s.status
      const daysAgo = (s.effectiveDaysAgo ?? 0) + i * 5
      contracts.value.push({
        id: nextId('ct'),
        contractNo: `HT2026082${4 - i}0${i + 1}`,
        customerId: s.customerId!,
        customerName: s.customerName,
        type: s.type,
        title: s.title,
        storeId: 'store-jingan',
        signDate: new Date(now - daysAgo * 86400000).toISOString().slice(0, 10),
        totalAmount: s.totalAmount,
        orders: s.orders || [],
        assetIds: s.assetIds || [],
        coolingDays: s.coolingDays ?? 7,
        penaltyRate: s.penaltyRate ?? 0.2,
        refundTerms: s.refundTerms || '',
        signedByName: ['苏晴（店长）', '林微（咨询师）', '苏晴（店长）', '林微（咨询师）'][i],
        status,
        createdAt: new Date(now - daysAgo * 86400000).toISOString(),
        effectiveAt: status === 'EFFECTIVE' || status === 'COMPLETED' || status === 'TERMINATED'
          ? new Date(now - daysAgo * 86400000 + 3600_000).toISOString() : undefined,
        completedAt: status === 'COMPLETED' ? new Date(now - 2 * 86400000).toISOString() : undefined,
      })
    })
  }

  return {
    contracts, drafts, effective, completed, terminated,
    get, saveDraft, activate, complete, terminate, inCoolingPeriod, refundEstimate, seed,
  }
})
