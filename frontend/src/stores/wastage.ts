// ============================================================
// Wastage 损耗报损 store（M2-12）
// 覆盖医美门店耗材/产品报损：破损 / 过期 / 盘亏 / 其他。
// 草稿 → 审批中 → 已通过/已驳回，双签：提交人 + 审批人。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

export type WastageStatus = 'DRAFT' | 'SUBMITTING' | 'APPROVED' | 'REJECTED'
export type WastageReason = 'BROKEN' | 'EXPIRED' | 'INVENTORY_LOSS' | 'OTHER'

export interface Wastage {
  id: string
  wsNo: string
  /** 物料名 */
  itemName: string
  spec?: string
  /** 数量 */
  qty: number
  unit: string
  /** 损失金额（元） */
  amount: number
  reason: WastageReason
  /** 详细说明 */
  description?: string
  /** 发生位置 */
  location?: string
  /** 报损人 */
  reporter: string
  status: WastageStatus
  approver?: string
  approvedAt?: string
  rejectReason?: string
  occurredAt: string
  createdAt: string
  notes: { by: string; text: string; at: string }[]
}

const STATUS_LABEL: Record<WastageStatus, string> = {
  DRAFT: '草稿',
  SUBMITTING: '待审批',
  APPROVED: '已通过',
  REJECTED: '已驳回',
}
const STATUS_PILL: Record<WastageStatus, 'draft' | 'primary' | 'success' | 'danger'> = {
  DRAFT: 'draft',
  SUBMITTING: 'primary',
  APPROVED: 'success',
  REJECTED: 'danger',
}
const REASON_LABEL: Record<WastageReason, string> = {
  BROKEN: '破损',
  EXPIRED: '过期',
  INVENTORY_LOSS: '盘亏',
  OTHER: '其他',
}
/** 单笔损失金额阈值（元），超过视为高值报损 */
const HIGH_VALUE_THRESHOLD = 500

export const useWastageStore = defineStore('wastage', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const list = ref<Wastage[]>([])
  const filterStatus = ref<WastageStatus | 'ALL'>('ALL')
  const filterReason = ref<WastageReason | 'ALL'>('ALL')

  const drafts = computed(() => list.value.filter((x) => x.status === 'DRAFT'))
  const submitting = computed(() => list.value.filter((x) => x.status === 'SUBMITTING'))
  const approved = computed(() => list.value.filter((x) => x.status === 'APPROVED'))
  const rejected = computed(() => list.value.filter((x) => x.status === 'REJECTED'))

  /** 本月已审批通过的笔数 */
  const monthCount = computed(() => {
    const now = new Date()
    return approved.value.filter((x) => {
      const d = new Date(x.approvedAt || x.createdAt)
      return d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth()
    }).length
  })
  /** 本月已审批通过的损失金额 */
  const monthAmount = computed(() => {
    const now = new Date()
    return approved.value
      .filter((x) => {
        const d = new Date(x.approvedAt || x.createdAt)
        return d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth()
      })
      .reduce((s, x) => s + x.amount, 0)
  })
  /** 高值报损（金额 >= 阈值，任意状态） */
  const highValue = computed(() => list.value.filter((x) => x.amount >= HIGH_VALUE_THRESHOLD))

  const filtered = computed(() => {
    let arr = list.value
    if (filterStatus.value !== 'ALL') arr = arr.filter((x) => x.status === filterStatus.value)
    if (filterReason.value !== 'ALL') arr = arr.filter((x) => x.reason === filterReason.value)
    return arr.sort((a, b) => new Date(b.occurredAt).getTime() - new Date(a.occurredAt).getTime())
  })

  function get(id: string) {
    return list.value.find((x) => x.id === id)
  }

  function create(input: {
    itemName: string
    spec?: string
    qty: number
    unit: string
    amount: number
    reason: WastageReason
    description?: string
    location?: string
    occurredAt?: string
  }): Wastage | null {
    if (!auth.can('wastage:create')) {
      console.warn('[wastage] 无 wastage:create 权限')
      return null
    }
    const now = new Date()
    const seq = list.value.length + 1
    const w: Wastage = {
      id: nextId('ws'),
      wsNo: `WS-${now.toISOString().slice(0, 10).replace(/-/g, '')}-${String(seq).padStart(3, '0')}`,
      itemName: input.itemName,
      spec: input.spec,
      qty: Number(input.qty),
      unit: input.unit,
      amount: Number(input.amount),
      reason: input.reason,
      description: input.description,
      location: input.location,
      reporter: auth.user.name,
      status: 'DRAFT',
      occurredAt: input.occurredAt ? new Date(input.occurredAt).toISOString() : now.toISOString(),
      createdAt: now.toISOString(),
      notes: [{ by: auth.user.name, text: '创建报损单（草稿）', at: now.toISOString() }],
    }
    list.value.unshift(w)
    activity.log(auth.user.name, `创建损耗报损 ${w.wsNo}：${w.itemName}`, w.id)
    return w
  }

  function submit(id: string): boolean {
    const w = list.value.find((x) => x.id === id)
    if (!w || w.status !== 'DRAFT' || !auth.can('wastage:edit')) return false
    w.status = 'SUBMITTING'
    addNote(id, '提交审批')
    activity.log(auth.user.name, `提交损耗报损 ${w.wsNo}`, w.id)
    return true
  }

  function approve(id: string, note?: string): boolean {
    const w = list.value.find((x) => x.id === id)
    if (!w || w.status !== 'SUBMITTING' || !auth.can('wastage:sign')) return false
    w.status = 'APPROVED'
    w.approver = auth.user.name
    w.approvedAt = new Date().toISOString()
    addNote(id, note ? `审批通过：${note}` : '审批通过')
    activity.log(auth.user.name, `审批通过损耗报损 ${w.wsNo}`, w.id)
    return true
  }

  function reject(id: string, reason: string): boolean {
    const w = list.value.find((x) => x.id === id)
    if (!w || w.status !== 'SUBMITTING' || !auth.can('wastage:sign')) return false
    w.status = 'REJECTED'
    w.approver = auth.user.name
    w.approvedAt = new Date().toISOString()
    w.rejectReason = reason
    addNote(id, `驳回：${reason}`)
    activity.log(auth.user.name, `驳回损耗报损 ${w.wsNo}：${reason}`, w.id)
    return true
  }

  function addNote(id: string, text: string): boolean {
    const w = list.value.find((x) => x.id === id)
    if (!w) return false
    w.notes.unshift({ by: auth.user.name, text, at: new Date().toISOString() })
    return true
  }

  // ===== 种子 =====
  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    const now = new Date()
    const hoursAgo = (h: number) => new Date(now.getTime() - h * 3600_000).toISOString()
    type Seed = Omit<Wastage, 'id' | 'wsNo' | 'reporter' | 'status' | 'createdAt' | 'notes' | 'approver' | 'approvedAt'> & {
      status: WastageStatus
      reporter: string
      createdAt: string
    }
    const data: Seed[] = [
      { itemName: '医用修复面膜', spec: '6 片/盒', qty: 2, unit: '盒', amount: 336, reason: 'EXPIRED', description: '库存盘点发现 2 盒已过保质期', location: '耗材仓 B 区', status: 'APPROVED', reporter: '吴桐（运营）', occurredAt: hoursAgo(20), createdAt: hoursAgo(20) },
      { itemName: '冷凝胶', spec: '500ml', qty: 1, unit: '瓶', amount: 180, reason: 'BROKEN', description: '取用过程中不慎跌落，瓶身破裂无法使用', location: 'B02 治疗室', status: 'SUBMITTING', reporter: '周敏（美容师）', occurredAt: hoursAgo(3), createdAt: hoursAgo(3) },
      { itemName: '玻尿酸原液', spec: '1ml/支', qty: 5, unit: '支', amount: 4250, reason: 'EXPIRED', description: '冷链断电 4 小时，整盒效期受影响报废', location: '冷藏柜 1', status: 'SUBMITTING', reporter: '顾屿（主治医师）', occurredAt: hoursAgo(6), createdAt: hoursAgo(6) },
      { itemName: '一次性注射器', spec: '1ml', qty: 20, unit: '支', amount: 40, reason: 'INVENTORY_LOSS', description: '月末盘点账实不符，差异 20 支', location: '耗材仓', status: 'APPROVED', reporter: '苏晴（店长）', occurredAt: hoursAgo(72), createdAt: hoursAgo(72) },
      { itemName: '射频治疗手柄', spec: '标配', qty: 1, unit: '个', amount: 3800, reason: 'BROKEN', description: '客户治疗过程中手柄异常发热，检修判定主板损坏', location: 'B02 治疗室', status: 'REJECTED', reporter: '李娜（前台）', occurredAt: hoursAgo(96), createdAt: hoursAgo(96) },
      { itemName: '瓶装饮用水', spec: '350ml', qty: 12, unit: '瓶', amount: 36, reason: 'OTHER', description: '包装破损污染，无法提供给客户', location: '候诊区', status: 'DRAFT', reporter: '夏沫（前台）', occurredAt: hoursAgo(1), createdAt: hoursAgo(1) },
      { itemName: '医用酒精棉片', spec: '独立包装', qty: 30, unit: '片', amount: 15, reason: 'EXPIRED', description: '独立包装上有效期已过', location: 'C01 注射室', status: 'APPROVED', reporter: '顾屿（主治医师）', occurredAt: hoursAgo(120), createdAt: hoursAgo(120) },
    ]
    data.forEach((s, i) => {
      list.value.push({
        id: nextId('ws'),
        wsNo: `WS-${s.createdAt.slice(0, 10).replace(/-/g, '')}-${String(i + 1).padStart(3, '0')}`,
        itemName: s.itemName,
        spec: s.spec,
        qty: s.qty,
        unit: s.unit,
        amount: s.amount,
        reason: s.reason,
        description: s.description,
        location: s.location,
        reporter: s.reporter,
        status: s.status,
        occurredAt: s.occurredAt,
        createdAt: s.createdAt,
        approver: s.status === 'APPROVED' || s.status === 'REJECTED' ? '苏晴（店长）' : undefined,
        approvedAt: s.status === 'APPROVED' || s.status === 'REJECTED' ? hoursAgo(Math.max(i, 1)) : undefined,
        rejectReason: s.status === 'REJECTED' ? '建议先走设备维修工单，再按维修结果处理资产报废' : undefined,
        notes: [
          { by: s.reporter, text: '创建报损单', at: s.createdAt },
          ...(s.status !== 'DRAFT' ? [{ by: s.reporter, text: '提交审批', at: hoursAgo(i + 2) }] : []),
          ...(s.status === 'APPROVED' ? [{ by: '苏晴（店长）', text: '审批通过', at: hoursAgo(Math.max(i, 1)) }] : []),
          ...(s.status === 'REJECTED' ? [{ by: '苏晴（店长）', text: '驳回：建议先走设备维修工单', at: hoursAgo(Math.max(i, 1)) }] : []),
        ],
      })
    })
  }

  return {
    list, filterStatus, filterReason,
    drafts, submitting, approved, rejected,
    monthCount, monthAmount, highValue, filtered,
    get, create, submit, approve, reject, addNote, seed,
    STATUS_LABEL, STATUS_PILL, REASON_LABEL, HIGH_VALUE_THRESHOLD,
  }
})
