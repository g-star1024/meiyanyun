// ============================================================
// Approval 审批中心 store（T3-01 双签真引擎）
// 统一聚合全平台待审批任务，提供 同意/拒绝/转交/加签/审批意见 全链路留痕。
// 业务模块（退款/退卡/资产转移/请假/采购/价格变更/损耗/申领…）通过 submitTask 接入；
// 对已落地的 refund / transfer 业务，审批动作会回写对应 store 形成闭环。
// 对齐 docs/business-flows.md §2.5/§2.7、permission-matrix.md。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { useRefundStore } from './refund'
import { useTransferStore } from './transfer'
import { useAssetStore } from './asset'

/** 审批业务类型（新增 M2 五类双签在此扩展） */
export type ApprovalBizType =
  | 'REFUND'        // 订单退款
  | 'CARD_CANCEL'   // 退卡
  | 'TRANSFER'      // 客户资产转移
  | 'LEAVE'         // 请假（M2 排班考勤）
  | 'PROCUREMENT'   // 采购申请（M2 供应链）
  | 'PRICE_CHANGE'  // 价格变更
  | 'LOSS_REPORT'   // 损耗报损（M2 库存）
  | 'REQUISITION'   // 物料申领（M2 库存）

export type ApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'TRANSFERRED'
/** 审批阶段：REVIEW=店长/运营一审；FINANCE=财务复核（L1 直达） */
export type ApprovalStage = 'REVIEW' | 'FINANCE'
export type SignTier = 'L1' | 'L2' | 'L3'

export interface ApprovalAction {
  actor: string
  action: 'SUBMIT' | 'APPROVE' | 'REJECT' | 'TRANSFER' | 'ADD_SIGN'
  comment: string
  at: string
}

export interface ApprovalTask {
  id: string
  bizType: ApprovalBizType
  bizNo: string
  title: string
  summary: string
  amount?: number
  applicant: string
  applicantRole: string
  signTier: SignTier
  status: ApprovalStatus
  stage: ApprovalStage
  priority: 'HIGH' | 'MEDIUM' | 'LOW'
  storeName: string
  submittedAt: string
  dueAt?: string
  /** 当前审批人（转交/加签后变化）；空表示按角色自动路由 */
  assignee?: string
  /** 加签人列表 */
  coSigners: string[]
  history: ApprovalAction[]
  /** 关联业务 store 的记录 id（用于回写闭环） */
  bizRefId?: string
}

const BIZ_LABEL: Record<ApprovalBizType, string> = {
  REFUND: '订单退款',
  CARD_CANCEL: '退卡',
  TRANSFER: '资产转移',
  LEAVE: '请假审批',
  PROCUREMENT: '采购申请',
  PRICE_CHANGE: '价格变更',
  LOSS_REPORT: '损耗报损',
  REQUISITION: '物料申领',
}

const BIZ_PERM: Record<ApprovalBizType, string> = {
  REFUND: 'refund:approve',
  CARD_CANCEL: 'cardcancel:approve',
  TRANSFER: 'transfer:approve',
  LEAVE: 'schedule:approve',
  PROCUREMENT: 'inventory:approve',
  PRICE_CHANGE: 'brand:approve',
  LOSS_REPORT: 'inventory:approve',
  REQUISITION: 'inventory:approve',
}

/**
 * 某任务在「当前阶段」所需的权限码：
 * - REVIEW（店长/运营一审）用业务审批权限（*:approve）；
 * - FINANCE（财务复核/执行）对退款/退卡用 :sign、对资产转移用 :edit（执行权）；
 *   其余暂无落地业务 store 的类型沿用 BIZ_PERM。
 * 这样保证"谁能看到待办/点同意"与"业务 store 回写所需权限"一致。
 */
function stagePerm(t: ApprovalTask): string {
  if (t.stage === 'FINANCE') {
    if (t.bizType === 'REFUND') return 'refund:sign'
    if (t.bizType === 'CARD_CANCEL') return 'cardcancel:sign'
    if (t.bizType === 'TRANSFER') return 'transfer:edit'
  }
  return BIZ_PERM[t.bizType]
}

export const useApprovalStore = defineStore('approval', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const tasks = ref<ApprovalTask[]>([])
  const tab = ref<'todo' | 'done' | 'all'>('todo')
  const filterType = ref<ApprovalBizType | 'ALL'>('ALL')

  const todo = computed(() => tasks.value.filter((t) => t.status === 'PENDING'))
  const done = computed(() => tasks.value.filter((t) => t.status !== 'PENDING'))
  const overdue = computed(() => {
    const now = Date.now()
    return todo.value.filter((t) => t.dueAt && new Date(t.dueAt).getTime() < now)
  })

  /** 当前用户可处理的待办（有当前阶段对应权限，且未指派给他人） */
  const myTodo = computed(() =>
    todo.value.filter((t) => auth.can(stagePerm(t)) && (!t.assignee || t.assignee === auth.user.name)),
  )

  const filtered = computed(() => {
    const base = tab.value === 'todo' ? myTodo.value : tab.value === 'done' ? done.value : tasks.value
    if (filterType.value === 'ALL') return base
    return base.filter((t) => t.bizType === filterType.value)
  })

  function get(id: string) {
    return tasks.value.find((t) => t.id === id)
  }

  function bizLabel(t: ApprovalBizType) {
    return BIZ_LABEL[t]
  }

  /** 当前阶段所需权限码（供视图判断按钮可用性） */
  function permFor(t: ApprovalTask) {
    return stagePerm(t)
  }

  /** 业务模块提交审批任务（M2 五类双签直接调用此方法接入） */
  function submitTask(input: {
    bizType: ApprovalBizType
    title: string
    summary: string
    amount?: number
    signTier: SignTier
    priority?: 'HIGH' | 'MEDIUM' | 'LOW'
    storeName?: string
    dueAt?: string
    bizNo?: string
    bizRefId?: string
    applicantName?: string
  }): ApprovalTask {
    const now = new Date().toISOString()
    const t: ApprovalTask = {
      id: nextId('ap'),
      bizType: input.bizType,
      bizNo: input.bizNo || `${input.bizType.slice(0, 3)}${Date.now().toString().slice(-8)}`,
      title: input.title,
      summary: input.summary,
      amount: input.amount,
      applicant: input.applicantName || auth.user.name,
      applicantRole: auth.primaryRole,
      signTier: input.signTier,
      status: 'PENDING',
      stage: input.signTier === 'L1' ? 'FINANCE' : 'REVIEW',
      priority: input.priority || (input.signTier === 'L3' ? 'HIGH' : 'MEDIUM'),
      storeName: input.storeName || auth.storeId || '默认门店',
      submittedAt: now,
      dueAt: input.dueAt,
      coSigners: [],
      history: [{ actor: input.applicantName || auth.user.name, action: 'SUBMIT', comment: '提交审批', at: now }],
      bizRefId: input.bizRefId,
    }
    tasks.value.unshift(t)
    activity.log(auth.user.name, `提交${BIZ_LABEL[input.bizType]}审批 ${t.bizNo}`, t.id)
    return t
  }

  /** 审批通过：REVIEW 阶段通过后，L2/L3 进入 FINANCE；FINANCE 通过则终审 */
  function approve(id: string, comment = '同意'): boolean {
    const t = tasks.value.find((x) => x.id === id)
    if (!t || t.status !== 'PENDING') return false
    const perm = stagePerm(t)
    if (!auth.can(perm)) {
      console.warn(`[approval] 无 ${perm} 权限`)
      return false
    }
    const now = new Date().toISOString()
    t.history.push({ actor: auth.user.name, action: 'APPROVE', comment, at: now })

    if (t.stage === 'REVIEW') {
      // 一审通过：推进到财务复核阶段，并回写业务单状态（退款/转移 PENDING_REVIEW→PENDING_FINANCE）
      t.stage = 'FINANCE'
      t.assignee = undefined
      writeback(t, true, false)
      activity.log(auth.user.name, `${BIZ_LABEL[t.bizType]} ${t.bizNo} 一审通过，进入财务复核`, t.id)
    } else {
      // 财务终审通过：审批办结，回写业务单完成（确认退款/执行转移）
      t.status = 'APPROVED'
      writeback(t, true, true)
      activity.log(auth.user.name, `${BIZ_LABEL[t.bizType]} ${t.bizNo} 审批终审通过`, t.id)
    }
    return true
  }

  /** 驳回（可在任一阶段） */
  function reject(id: string, reason: string): boolean {
    const t = tasks.value.find((x) => x.id === id)
    if (!t || t.status !== 'PENDING') return false
    const perm = stagePerm(t)
    if (!auth.can(perm)) {
      console.warn(`[approval] 无 ${perm} 权限`)
      return false
    }
    if (!reason.trim()) return false
    t.status = 'REJECTED'
    t.history.push({ actor: auth.user.name, action: 'REJECT', comment: reason, at: new Date().toISOString() })
    activity.log(auth.user.name, `${BIZ_LABEL[t.bizType]} ${t.bizNo} 已驳回：${reason}`, t.id)
    writeback(t, false, true, reason)
    return true
  }

  /** 转交他人审批 */
  function transfer(id: string, to: string, comment = ''): boolean {
    const t = tasks.value.find((x) => x.id === id)
    if (!t || t.status !== 'PENDING') return false
    t.assignee = to
    t.history.push({
      actor: auth.user.name,
      action: 'TRANSFER',
      comment: `转交给 ${to}${comment ? '：' + comment : ''}`,
      at: new Date().toISOString(),
    })
    activity.log(auth.user.name, `${BIZ_LABEL[t.bizType]} ${t.bizNo} 转交 ${to}`, t.id)
    return true
  }

  /** 加签（增加会签人，不改变当前审批人） */
  function addSigner(id: string, who: string): boolean {
    const t = tasks.value.find((x) => x.id === id)
    if (!t || t.status !== 'PENDING') return false
    if (!t.coSigners.includes(who)) t.coSigners.push(who)
    t.history.push({ actor: auth.user.name, action: 'ADD_SIGN', comment: `加签 ${who}`, at: new Date().toISOString() })
    activity.log(auth.user.name, `${BIZ_LABEL[t.bizType]} ${t.bizNo} 加签 ${who}`, t.id)
    return true
  }

  /**
   * 回写业务 store 形成真实闭环。
   * @param approved 通过/驳回
   * @param final    是否终审（FINANCE 阶段通过 = 业务单完成；REVIEW 阶段通过 = 推进到财务复核）
   * @param reason   驳回时透传用户填写的具体原因
   */
  function writeback(t: ApprovalTask, approved: boolean, final: boolean, reason?: string) {
    if (!t.bizRefId) return
    try {
      if (t.bizType === 'REFUND' || t.bizType === 'CARD_CANCEL') {
        const refund = useRefundStore()
        if (!approved) {
          refund.reject(t.bizRefId, reason || '审批中心驳回')
        } else if (final) {
          refund.confirmRefund(t.bizRefId)
        } else {
          refund.approve(t.bizRefId)
        }
      } else if (t.bizType === 'TRANSFER') {
        const transfer = useTransferStore()
        if (!approved) {
          transfer.reject(t.bizRefId, reason || '审批中心驳回')
        } else if (final) {
          transfer.execute(t.bizRefId)
        } else {
          transfer.approve(t.bizRefId)
        }
      }
    } catch (e) {
      console.warn('[approval] writeback skipped:', (e as Error).message)
    }
  }

  // ===== 种子数据（覆盖八类双签，含 M2 五类） =====
  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    const now = Date.now()
    const hoursAgo = (h: number) => new Date(now - h * 3600_000).toISOString()
    const hoursLater = (h: number) => new Date(now + h * 3600_000).toISOString()
    const seed: Array<Partial<ApprovalTask> & Pick<ApprovalTask, 'bizType' | 'title' | 'summary' | 'signTier' | 'status' | 'stage' | 'applicant' | 'storeName'>> = [
      { bizType: 'REFUND', title: '退款 · 光子嫩肤 5 次卡', summary: '客户王美丽申请剩余 3 次退款 ¥5,880', amount: 5880, signTier: 'L2', status: 'PENDING', stage: 'REVIEW', applicant: '苏晴（咨询师）', applicantRole: 'CONSULTANT', storeName: '上海静安店', priority: 'MEDIUM', bizNo: 'RF20260824001' },
      { bizType: 'CARD_CANCEL', title: '退卡 · 王小姐疗程卡', summary: '客户搬离本地，退卡扣违约金 ¥1,024，实退 ¥5,120', amount: 5120, signTier: 'L3', status: 'PENDING', stage: 'REVIEW', applicant: '林微（咨询师）', applicantRole: 'CONSULTANT', storeName: '上海静安店', priority: 'HIGH', bizNo: 'CC20260820005' },
      { bizType: 'TRANSFER', title: '资产转移 · 光子嫩肤剩余疗程', summary: '王小姐将光子嫩肤剩余 2 次转给李静（L1 直达财务执行）', signTier: 'L1', status: 'PENDING', stage: 'FINANCE', applicant: '周敏（美容师）', applicantRole: 'OPERATOR', storeName: '上海静安店', priority: 'MEDIUM', bizNo: 'AT2026082302' },
      { bizType: 'LEAVE', title: '请假 · 陈雅琳年假 2 天', summary: '店长陈雅琳申请 8/27-8/28 年假，需安排代班', signTier: 'L1', status: 'PENDING', stage: 'FINANCE', applicant: '陈雅琳（店长）', applicantRole: 'STORE_MGR', storeName: '上海静安店', priority: 'LOW', dueAt: hoursLater(20), bizNo: 'LV20260825004' },
      { bizType: 'PROCUREMENT', title: '采购 · 玻尿酸 50 支', summary: '库存低于安全线，采购 ¥18,500，供应商华东医药', amount: 18500, signTier: 'L3', status: 'PENDING', stage: 'REVIEW', applicant: '吴桐（库管）', applicantRole: 'OPERATOR', storeName: '上海静安店', priority: 'HIGH', dueAt: hoursLater(8), bizNo: 'PO20260825005' },
      { bizType: 'PRICE_CHANGE', title: '价格变更 · 水光针调价', summary: '水光针单次 ¥1,280 → ¥980（引流活动，限时 7 天）', amount: 980, signTier: 'L2', status: 'PENDING', stage: 'REVIEW', applicant: '市场部 · 张磊', applicantRole: 'REGION_MGR', storeName: '华东区域', priority: 'MEDIUM', bizNo: 'PC20260825006' },
      { bizType: 'LOSS_REPORT', title: '损耗报损 · 过期面膜 12 片', summary: '库存盘点发现 12 片补水面膜过期，报损 ¥360', amount: 360, signTier: 'L1', status: 'PENDING', stage: 'FINANCE', applicant: '吴桐（库管）', applicantRole: 'OPERATOR', storeName: '上海静安店', priority: 'LOW', bizNo: 'LS20260825007' },
      { bizType: 'REQUISITION', title: '物料申领 · 一次性床单 200 张', summary: '门店申领床单 200 张、消毒棉片 5 盒', signTier: 'L1', status: 'PENDING', stage: 'FINANCE', applicant: '李娜（前台）', applicantRole: 'FRONT_DESK', storeName: '上海静安店', priority: 'LOW', dueAt: hoursLater(48), bizNo: 'RQ20260825008' },
      // 已办结示例
      { bizType: 'REFUND', title: '退款 · 水光针单次', summary: '客户重复下单退款 ¥1,280', amount: 1280, signTier: 'L1', status: 'APPROVED', stage: 'FINANCE', applicant: '李娜（前台）', applicantRole: 'FRONT_DESK', storeName: '上海静安店', priority: 'LOW', bizNo: 'RF20260824009' },
      { bizType: 'PROCUREMENT', title: '采购 · 瘦脸针 20 支', summary: '常规补货 ¥26,000', amount: 26000, signTier: 'L3', status: 'REJECTED', stage: 'REVIEW', applicant: '吴桐（库管）', applicantRole: 'OPERATOR', storeName: '上海静安店', priority: 'HIGH', bizNo: 'PO20260823010' },
    ]
    seed.forEach((s, i) => {
      tasks.value.push({
        id: nextId('ap'),
        bizType: s.bizType!,
        bizNo: s.bizNo!,
        title: s.title!,
        summary: s.summary!,
        amount: s.amount,
        applicant: s.applicant!,
        applicantRole: s.applicantRole || 'OPERATOR',
        signTier: s.signTier!,
        status: s.status!,
        stage: s.stage!,
        priority: s.priority || 'MEDIUM',
        storeName: s.storeName!,
        submittedAt: hoursAgo(i * 5 + 1),
        dueAt: s.dueAt,
        coSigners: [],
        history: [
          { actor: s.applicant!, action: 'SUBMIT', comment: '提交审批', at: hoursAgo(i * 5 + 1) },
          ...(s.status === 'APPROVED'
            ? [{ actor: '王财务', action: 'APPROVE' as const, comment: '复核通过', at: hoursAgo(i * 5) }]
            : []),
          ...(s.status === 'REJECTED'
            ? [{ actor: '陈雅琳（店长）', action: 'REJECT' as const, comment: '库存尚足，本月暂不采购', at: hoursAgo(i * 5) }]
            : []),
        ],
      })
    })

    // 把退款/退卡审批任务与真实 refund store 记录挂钩，实现联动
    try {
      const refund = useRefundStore()
      refund.seed()
      tasks.value.forEach((t) => {
        if (t.bizType === 'REFUND' || t.bizType === 'CARD_CANCEL') {
          const r = refund.refunds.find((x) => x.refundNo === t.bizNo)
          if (r) t.bizRefId = r.id
        }
      })
    } catch {
      // 忽略：refund store 未挂载时保持当前无联动状态
    }
    // 把资产转移审批任务与真实 transfer store 记录挂钩
    try {
      const transfer = useTransferStore()
      const asset = useAssetStore()
      asset.seed()
      transfer.seed()
      tasks.value.forEach((t) => {
        if (t.bizType === 'TRANSFER') {
          const xfer = transfer.transfers.find((x) => x.transferNo === t.bizNo)
          if (xfer) t.bizRefId = xfer.id
        }
      })
    } catch {
      // 忽略：transfer store 未挂载
    }
  }

  return {
    tasks, tab, filterType,
    todo, done, overdue, myTodo, filtered,
    get, bizLabel, permFor, submitTask, approve, reject, transfer, addSigner, seed,
    BIZ_LABEL, BIZ_PERM,
  }
})
