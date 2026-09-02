// ============================================================
// Requisition 物料申领 store（M2-11）
// 覆盖医美门店耗材/产品申领：草稿 → 审批中 → 已通过/已驳回 → 待签收。
// 对齐 workorder 范式：nextId、useActivityStore、seed、computed、action。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

export type RequisitionStatus = 'DRAFT' | 'SUBMITTING' | 'APPROVED' | 'RECEIVED' | 'REJECTED'

export interface RequisitionItem {
  /** 物料名 */
  name: string
  /** 规格 */
  spec?: string
  /** 申领数量 */
  qty: number
  /** 单位 */
  unit: string
}

export interface RequisitionNote {
  by: string
  text: string
  at: string
}

export interface Requisition {
  id: string
  rqNo: string
  /** 申领人 */
  applicant: string
  /** 所属部门/用途 */
  purpose: string
  /** 备注 */
  remark?: string
  items: RequisitionItem[]
  status: RequisitionStatus
  /** 双签：审批人 */
  approver?: string
  approvedAt?: string
  /** 双签：签收人 */
  receiver?: string
  receivedAt?: string
  rejectReason?: string
  createdAt: string
  notes: RequisitionNote[]
}

const STATUS_LABEL: Record<RequisitionStatus, string> = {
  DRAFT: '草稿',
  SUBMITTING: '审批中',
  APPROVED: '待签收',
  RECEIVED: '已签收',
  REJECTED: '已驳回',
}

const STATUS_PILL: Record<RequisitionStatus, 'draft' | 'primary' | 'warning' | 'success' | 'danger'> = {
  DRAFT: 'draft',
  SUBMITTING: 'primary',
  APPROVED: 'warning',
  RECEIVED: 'success',
  REJECTED: 'danger',
}

export const useRequisitionStore = defineStore('requisition', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const list = ref<Requisition[]>([])
  const filterStatus = ref<RequisitionStatus | 'ALL'>('ALL')
  const keyword = ref('')

  const drafts = computed(() => list.value.filter((x) => x.status === 'DRAFT'))
  const submitting = computed(() => list.value.filter((x) => x.status === 'SUBMITTING'))
  const approved = computed(() => list.value.filter((x) => x.status === 'APPROVED'))
  const received = computed(() => list.value.filter((x) => x.status === 'RECEIVED'))
  const rejected = computed(() => list.value.filter((x) => x.status === 'REJECTED'))

  const filtered = computed(() => {
    let arr = list.value
    if (filterStatus.value !== 'ALL') arr = arr.filter((x) => x.status === filterStatus.value)
    const kw = keyword.value.trim().toLowerCase()
    if (kw) {
      arr = arr.filter(
        (x) =>
          x.rqNo.toLowerCase().includes(kw) ||
          x.applicant.toLowerCase().includes(kw) ||
          x.purpose.toLowerCase().includes(kw) ||
          x.items.some((it) => it.name.toLowerCase().includes(kw)),
      )
    }
    return arr.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
  })

  function get(id: string) {
    return list.value.find((x) => x.id === id)
  }

  function create(input: {
    applicant?: string
    purpose: string
    remark?: string
    items: RequisitionItem[]
  }): Requisition | null {
    if (!auth.can('requisition:create')) {
      console.warn('[requisition] 无 requisition:create 权限')
      return null
    }
    const now = new Date()
    const seq = list.value.length + 1
    const r: Requisition = {
      id: nextId('rq'),
      rqNo: `RQ-${now.toISOString().slice(0, 10).replace(/-/g, '')}-${String(seq).padStart(3, '0')}`,
      applicant: input.applicant || auth.user.name,
      purpose: input.purpose,
      remark: input.remark,
      items: input.items.filter((it) => it.name && it.qty > 0),
      status: 'DRAFT',
      createdAt: now.toISOString(),
      notes: [{ by: auth.user.name, text: '创建申领单（草稿）', at: now.toISOString() }],
    }
    list.value.unshift(r)
    activity.log(auth.user.name, `创建物料申领 ${r.rqNo}`, r.id)
    return r
  }

  function submit(id: string): boolean {
    const r = list.value.find((x) => x.id === id)
    if (!r || r.status !== 'DRAFT' || !auth.can('requisition:edit')) return false
    r.status = 'SUBMITTING'
    addNote(id, '提交审批')
    activity.log(auth.user.name, `提交物料申领 ${r.rqNo}`, r.id)
    return true
  }

  function approve(id: string, note?: string): boolean {
    const r = list.value.find((x) => x.id === id)
    if (!r || r.status !== 'SUBMITTING' || !auth.can('requisition:sign')) return false
    r.status = 'APPROVED'
    r.approver = auth.user.name
    r.approvedAt = new Date().toISOString()
    addNote(id, note ? `审批通过：${note}` : '审批通过')
    activity.log(auth.user.name, `审批通过物料申领 ${r.rqNo}`, r.id)
    return true
  }

  function reject(id: string, reason: string): boolean {
    const r = list.value.find((x) => x.id === id)
    if (!r || r.status !== 'SUBMITTING' || !auth.can('requisition:sign')) return false
    r.status = 'REJECTED'
    r.approver = auth.user.name
    r.approvedAt = new Date().toISOString()
    r.rejectReason = reason
    addNote(id, `驳回：${reason}`)
    activity.log(auth.user.name, `驳回物料申领 ${r.rqNo}：${reason}`, r.id)
    return true
  }

  function receive(id: string): boolean {
    const r = list.value.find((x) => x.id === id)
    if (!r || r.status !== 'APPROVED' || !auth.can('requisition:edit')) return false
    r.status = 'RECEIVED'
    r.receiver = auth.user.name
    r.receivedAt = new Date().toISOString()
    addNote(id, '已签收物料')
    activity.log(auth.user.name, `签收物料申领 ${r.rqNo}`, r.id)
    return true
  }

  function addNote(id: string, text: string): boolean {
    const r = list.value.find((x) => x.id === id)
    if (!r) return false
    r.notes.unshift({ by: auth.user.name, text, at: new Date().toISOString() })
    return true
  }

  // ===== 种子数据 =====
  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    const now = new Date()
    const hoursAgo = (h: number) => new Date(now.getTime() - h * 3600_000).toISOString()
    type Seed = Partial<Requisition> & {
      purpose: string
      items: RequisitionItem[]
      status: RequisitionStatus
      applicant: string
      createdAt: string
    }
    const base: Seed[] = [
      {
        purpose: 'A03 治疗室日常耗材补充',
        applicant: '周敏（美容师）',
        status: 'DRAFT',
        createdAt: hoursAgo(2),
        items: [
          { name: '一次性治疗巾', spec: '40×50cm', qty: 50, unit: '包' },
          { name: '医用棉签', spec: '竹棒', qty: 20, unit: '盒' },
        ],
      },
      {
        purpose: 'B02 射频项目客户使用',
        applicant: '李娜（前台）',
        status: 'SUBMITTING',
        createdAt: hoursAgo(5),
        items: [
          { name: '冷凝胶', spec: '500ml', qty: 6, unit: '瓶' },
          { name: '一次性手套', spec: 'M 码', qty: 4, unit: '盒' },
        ],
      },
      {
        purpose: '激光术后护理备货',
        applicant: '吴桐（运营）',
        status: 'APPROVED',
        createdAt: hoursAgo(20),
        items: [
          { name: '医用修复面膜', spec: '6 片/盒', qty: 30, unit: '盒' },
          { name: '生理盐水', spec: '250ml', qty: 20, unit: '瓶' },
          { name: '纱布块', spec: '8 层', qty: 10, unit: '包' },
        ],
      },
      {
        purpose: 'C01 注射室一次性器械',
        applicant: '顾屿（主治医师）',
        status: 'RECEIVED',
        createdAt: hoursAgo(48),
        items: [
          { name: '一次性注射器', spec: '1ml', qty: 100, unit: '支' },
          { name: '医用酒精棉片', spec: '独立包装', qty: 200, unit: '片' },
        ],
      },
      {
        purpose: '候诊区日常物资',
        applicant: '夏沫（前台）',
        status: 'REJECTED',
        createdAt: hoursAgo(72),
        remark: '数量超出月度预算，请重新提交',
        items: [
          { name: '瓶装饮用水', spec: '350ml', qty: 500, unit: '瓶' },
          { name: '一次性纸杯', spec: '200ml', qty: 20, unit: '条' },
        ],
      },
      {
        purpose: '美容床品换洗',
        applicant: '周敏（美容师）',
        status: 'SUBMITTING',
        createdAt: hoursAgo(8),
        items: [
          { name: '美容床笠', spec: '粉色', qty: 15, unit: '条' },
          { name: '一次性枕套', spec: '无纺布', qty: 60, unit: '个' },
        ],
      },
    ]
    base.forEach((s, i) => {
      const id = nextId('rq')
      const r: Requisition = {
        id,
        rqNo: `RQ-${s.createdAt.slice(0, 10).replace(/-/g, '')}-${String(i + 1).padStart(3, '0')}`,
        applicant: s.applicant,
        purpose: s.purpose,
        remark: s.remark,
        items: s.items,
        status: s.status,
        createdAt: s.createdAt,
        approver: s.status === 'APPROVED' || s.status === 'RECEIVED' || s.status === 'REJECTED' ? '苏晴（店长）' : undefined,
        approvedAt: ['APPROVED', 'RECEIVED', 'REJECTED'].includes(s.status) ? hoursAgo(i + 3) : undefined,
        receiver: s.status === 'RECEIVED' ? s.applicant : undefined,
        receivedAt: s.status === 'RECEIVED' ? hoursAgo(i + 2) : undefined,
        rejectReason: s.status === 'REJECTED' ? s.remark : undefined,
        notes: [
          { by: s.applicant, text: '创建申领单', at: s.createdAt },
          ...(s.status !== 'DRAFT'
            ? [{ by: s.applicant, text: '提交审批', at: hoursAgo(i + 10) }]
            : []),
          ...(s.status === 'APPROVED' || s.status === 'RECEIVED'
            ? [{ by: '苏晴（店长）', text: '审批通过', at: hoursAgo(i + 3) }]
            : []),
          ...(s.status === 'REJECTED'
            ? [{ by: '苏晴（店长）', text: `驳回：${s.remark}`, at: hoursAgo(i + 3) }]
            : []),
          ...(s.status === 'RECEIVED'
            ? [{ by: s.applicant, text: '已签收物料', at: hoursAgo(i + 2) }]
            : []),
        ],
      }
      list.value.push(r)
    })
  }

  return {
    list, filterStatus, keyword,
    drafts, submitting, approved, received, rejected, filtered,
    get, create, submit, approve, reject, receive, addNote, seed,
    STATUS_LABEL, STATUS_PILL,
  }
})
