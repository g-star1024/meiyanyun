// ============================================================
// Followup 聚合 store（术后回访与满意度）
// 状态机：PENDING（待回访）→ DONE（已回访）/ SKIPPED（无需回访）。
// - planDate 早于当前且仍 PENDING 的视为"超期未回访"，页面高亮预警。
// - 满意度 1-5 星；标记不良反应 (adverseReaction) 自动提示转投诉/医疗风险。
// 回访计划可由核销/治疗完成时自动生成（complete()），也可手动新建。
// 权限：followup:create 建计划 / followup:edit 回访登记。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

export type FollowupMethod = 'PHONE' | 'WECHAT' | 'IN_STORE'
export type FollowupStatus = 'PENDING' | 'DONE' | 'SKIPPED'
export type RecoveryStatus = 'GOOD' | 'NORMAL' | 'POOR'

/** 术后 SOP 节点阶段（多节点随访自动化） */
export type SopStage =
  | 'CARE_24H'    // 术后 24h 关怀
  | 'FOLLOWUP_3D' // 第 3 天回访
  | 'RECOVERY_7D' // 第 7 天恢复评估
  | 'REVISIT_30D' // 第 30 天复诊提醒
  | 'MANUAL'      // 手动/核销生成的普通随访

export interface Followup {
  id: string
  followupNo: string
  customerId: string
  customerName: string
  project: string
  relatedOrderNo?: string
  serviceDate: string
  planDate: string
  method: FollowupMethod
  status: FollowupStatus
  /** 术后 SOP 节点阶段（普通随访为 MANUAL；自定义编排节点也为 MANUAL，以 sopBatchId 区分） */
  sopStage?: SopStage
  /** SOP 节点名称（取自编排模板，自定义节点名称各异，故冗余存储） */
  sopLabel?: string
  /** 所属术后 SOP 批次（同一次治疗生成的多节点共享一个 batchId；有值即 SOP 节点） */
  sopBatchId?: string
  /** SOP 节点是否已超时升级（超期未完成自动升级提醒主管） */
  escalated?: boolean
  satisfaction?: number // 1-5，回访后填写
  recovery?: RecoveryStatus
  adverseReaction: boolean
  adverseNote?: string
  needRevisit: boolean
  note?: string
  followupByName?: string
  doneAt?: string
  createdAt: string
}

/** 术后 SOP 模板：节点 = 术后第 N 天 + 方式 + 阶段 */
export interface SopNodeDef {
  stage: SopStage
  label: string
  dayOffset: number
  method: FollowupMethod
  /** 是否启用（编排页可停用某节点，停用后不再自动生成） */
  enabled?: boolean
}

/** SOP 批次执行汇总（同一次治疗生成的多节点） */
export interface SopBatch {
  batchId: string
  customerId: string
  customerName: string
  project: string
  serviceDate: string
  nodes: Followup[]
  total: number
  done: number
  overdue: number
  /** 是否全部完成/跳过 */
  finished: boolean
}

/** 默认术后随访 SOP（注射/光电类通用，可按项目扩展） */
export const DEFAULT_POST_OP_SOP: SopNodeDef[] = [
  { stage: 'CARE_24H', label: '术后 24h 关怀', dayOffset: 1, method: 'WECHAT' },
  { stage: 'FOLLOWUP_3D', label: '第 3 天回访', dayOffset: 3, method: 'PHONE' },
  { stage: 'RECOVERY_7D', label: '第 7 天恢复评估', dayOffset: 7, method: 'WECHAT' },
  { stage: 'REVISIT_30D', label: '第 30 天复诊提醒', dayOffset: 30, method: 'PHONE' },
]

export const SOP_STAGE_LABEL: Record<SopStage, string> = {
  CARE_24H: '术后 24h 关怀',
  FOLLOWUP_3D: '第 3 天回访',
  RECOVERY_7D: '第 7 天恢复评估',
  REVISIT_30D: '第 30 天复诊提醒',
  MANUAL: '普通随访',
}

export const useFollowupStore = defineStore('followup', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const followups = ref<Followup[]>([])
  let seq = 0

  // ---- 术后 SOP 模板（可在编排页增删改 / 启停；停用节点不再自动生成） ----
  const sopTemplate = ref<SopNodeDef[]>(
    DEFAULT_POST_OP_SOP.map((n) => ({ ...n, enabled: true })),
  )
  /** 当前启用的节点（按术后天数升序），schedulePostOpSop 实际使用 */
  const enabledSopNodes = computed(() =>
    sopTemplate.value
      .filter((n) => n.enabled !== false)
      .slice()
      .sort((a, b) => a.dayOffset - b.dayOffset),
  )

  /** 启停节点（按模板下标定位，兼容多个自定义 MANUAL 节点） */
  function toggleSopNode(index: number, enabled: boolean) {
    const n = sopTemplate.value[index]
    if (!n) return
    n.enabled = enabled
    activity.log(auth.user.name, `术后 SOP 节点「${n.label}」已${enabled ? '启用' : '停用'}`, 'sop-template')
  }
  /** 修改节点（术后天数 / 回访方式 / 名称） */
  function updateSopNode(
    index: number,
    patch: Partial<Pick<SopNodeDef, 'dayOffset' | 'method' | 'label'>>,
  ) {
    const n = sopTemplate.value[index]
    if (!n) return
    if (typeof patch.dayOffset === 'number' && patch.dayOffset >= 0) n.dayOffset = Math.round(patch.dayOffset)
    if (patch.method) n.method = patch.method
    if (patch.label?.trim()) n.label = patch.label.trim()
  }
  /** 新增自定义节点（stage 固定 MANUAL 以外不可重复，自定义节点用 MANUAL + 唯一 label） */
  function addSopNode(input: { label: string; dayOffset: number; method: FollowupMethod }): boolean {
    if (!input.label.trim() || input.dayOffset < 0) return false
    sopTemplate.value.push({
      stage: 'MANUAL',
      label: input.label.trim(),
      dayOffset: Math.round(input.dayOffset),
      method: input.method,
      enabled: true,
    })
    sopTemplate.value.sort((a, b) => a.dayOffset - b.dayOffset)
    activity.log(auth.user.name, `术后 SOP 新增节点「${input.label.trim()}」（术后第 ${Math.round(input.dayOffset)} 天）`, 'sop-template')
    return true
  }
  /** 删除自定义节点（内置四个阶段节点不可删，只能停用） */
  function removeSopNode(index: number): boolean {
    const n = sopTemplate.value[index]
    if (!n || n.stage !== 'MANUAL') return false
    sopTemplate.value.splice(index, 1)
    activity.log(auth.user.name, `术后 SOP 节点「${n.label}」已删除`, 'sop-template')
    return true
  }
  /** 恢复默认模板 */
  function resetSopTemplate() {
    sopTemplate.value = DEFAULT_POST_OP_SOP.map((n) => ({ ...n, enabled: true }))
    activity.log(auth.user.name, '术后 SOP 模板已恢复默认（24h关怀/3天回访/7天评估/30天复诊）', 'sop-template')
  }

  const pending = computed(() => followups.value.filter((f) => f.status === 'PENDING'))
  const done = computed(() => followups.value.filter((f) => f.status === 'DONE'))
  const skipped = computed(() => followups.value.filter((f) => f.status === 'SKIPPED'))

  /** 超期未回访：计划日期早于今天且仍待回访 */
  const overdue = computed(() => {
    const today = new Date()
    today.setHours(0, 0, 0, 0)
    return pending.value.filter((f) => new Date(f.planDate) < today)
  })
  /** 今日待回访 */
  const todayPending = computed(() => {
    const today = new Date().toISOString().slice(0, 10)
    return pending.value.filter((f) => f.planDate.slice(0, 10) === today)
  })

  /** 平均满意度（已回访） */
  const avgSatisfaction = computed(() => {
    const rated = done.value.filter((f) => typeof f.satisfaction === 'number')
    if (rated.length === 0) return 0
    return rated.reduce((s, f) => s + (f.satisfaction ?? 0), 0) / rated.length
  })
  /** 不良反应数（已回访中） */
  const adverseCount = computed(() => done.value.filter((f) => f.adverseReaction).length)

  function get(id: string) {
    return followups.value.find((f) => f.id === id)
  }

  /** 生成回访计划（核销/治疗完成调用，或手动新建） */
  function schedule(input: {
    customerId: string
    customerName: string
    project: string
    relatedOrderNo?: string
    serviceDate: string
    planDate: string
    method?: FollowupMethod
  }): Followup | null {
    if (!auth.can('followup:create')) {
      console.warn('[followup] 无 followup:create 权限')
      return null
    }
    seq += 1
    const now = new Date().toISOString()
    const f: Followup = {
      id: nextId('fu'),
      followupNo: `HF${Date.now().toString().slice(-8)}${seq}`,
      customerId: input.customerId,
      customerName: input.customerName,
      project: input.project,
      relatedOrderNo: input.relatedOrderNo,
      serviceDate: input.serviceDate,
      planDate: input.planDate,
      method: input.method ?? 'PHONE',
      status: 'PENDING',
      adverseReaction: false,
      needRevisit: false,
      createdAt: now,
    }
    followups.value.unshift(f)
    activity.log(auth.user.name, `生成回访计划 ${f.followupNo}（${f.customerName}·${f.project}）`, f.id)
    return f
  }

  /**
   * 术后 SOP 自动化：治疗完成时按模板一次性生成多节点随访计划
   * （24h 关怀 / 第3天回访 / 第7天恢复 / 第30天复诊），共享一个 batchId。
   * 返回生成的随访列表。
   */
  function schedulePostOpSop(input: {
    customerId: string
    customerName: string
    project: string
    relatedOrderNo?: string
    serviceDate?: string
    nodes?: SopNodeDef[]
  }): Followup[] {
    if (!auth.can('followup:create')) {
      console.warn('[followup] 无 followup:create 权限')
      return []
    }
    const nodes = (input.nodes ?? enabledSopNodes.value).filter((n) => n.enabled !== false)
    if (nodes.length === 0) return []
    const serviceDate = input.serviceDate ? new Date(input.serviceDate) : new Date()
    const batchId = `SOP${Date.now().toString().slice(-8)}`
    const created: Followup[] = []
    nodes.forEach((n, idx) => {
      seq += 1
      const plan = new Date(serviceDate.getTime() + n.dayOffset * 86400000)
      const f: Followup = {
        id: nextId('fu'),
        followupNo: `HF${batchId.slice(-6)}${idx + 1}`,
        customerId: input.customerId,
        customerName: input.customerName,
        project: input.project,
        relatedOrderNo: input.relatedOrderNo,
        serviceDate: serviceDate.toISOString(),
        planDate: plan.toISOString(),
        method: n.method,
        status: 'PENDING',
        adverseReaction: false,
        needRevisit: false,
        sopStage: n.stage,
        sopLabel: n.label,
        sopBatchId: batchId,
        createdAt: new Date().toISOString(),
      }
      followups.value.unshift(f)
      created.push(f)
    })
    activity.log(
      auth.user.name,
      `术后 SOP 自动生成 ${created.length} 个随访节点（${input.customerName}·${input.project}，批次 ${batchId}）`,
      batchId,
    )
    return created
  }

  /** 某客户/某批次的 SOP 节点（按计划时间升序） */
  function sopOfBatch(batchId: string) {
    return followups.value
      .filter((f) => f.sopBatchId === batchId)
      .sort((a, b) => new Date(a.planDate).getTime() - new Date(b.planDate).getTime())
  }
  function sopOfCustomer(customerId: string) {
    return followups.value
      .filter((f) => f.customerId === customerId && f.sopBatchId)
      .sort((a, b) => new Date(a.planDate).getTime() - new Date(b.planDate).getTime())
  }

  /** SOP 待办（术后节点，未完成）——以 sopBatchId 判定，含自定义节点 */
  const sopPending = computed(() => pending.value.filter((f) => f.sopBatchId))
  /** SOP 超期未回访节点（含已升级，用于看板统计） */
  const sopOverdue = computed(() => overdue.value.filter((f) => f.sopBatchId))
  /** SOP 超期且尚未升级的节点（自动升级巡检目标） */
  const sopOverdueNeedEscalation = computed(
    () => sopOverdue.value.filter((f) => !f.escalated),
  )

  /**
   * SOP 批次聚合：同一次治疗生成的多节点聚合成一个批次看板行。
   * done = 已回访 + 无需回访；overdue = 超期待回访；finished = 全部节点完结。
   */
  const sopBatches = computed<SopBatch[]>(() => {
    const map = new Map<string, Followup[]>()
    for (const f of followups.value) {
      if (!f.sopBatchId) continue
      const arr = map.get(f.sopBatchId) ?? []
      arr.push(f)
      map.set(f.sopBatchId, arr)
    }
    const batches: SopBatch[] = []
    for (const [batchId, nodesRaw] of map) {
      const nodes = nodesRaw.sort(
        (a, b) => new Date(a.planDate).getTime() - new Date(b.planDate).getTime(),
      )
      const head = nodes[0]
      const closed = nodes.filter((f) => f.status !== 'PENDING').length
      const today = new Date(); today.setHours(0, 0, 0, 0)
      const od = nodes.filter(
        (f) => f.status === 'PENDING' && new Date(f.planDate) < today,
      ).length
      batches.push({
        batchId,
        customerId: head.customerId,
        customerName: head.customerName,
        project: head.project,
        serviceDate: head.serviceDate,
        nodes,
        total: nodes.length,
        done: closed,
        overdue: od,
        finished: closed === nodes.length,
      })
    }
    // 未完成在前；同状态按服务日期倒序（新批次在前）
    return batches.sort((a, b) => {
      if (a.finished !== b.finished) return a.finished ? 1 : -1
      return new Date(b.serviceDate).getTime() - new Date(a.serviceDate).getTime()
    })
  })

  /** 巡检：将所有超期未升级的 SOP 节点一键升级（编排页"超期升级"按钮 / 可扩展为定时任务） */
  function escalateAllOverdue(): number {
    let n = 0
    for (const f of sopOverdueNeedEscalation.value) {
      if (escalate(f.id)) n += 1
    }
    return n
  }

  /** 标记 SOP 节点超时升级（由页面/定时巡检触发） */
  function escalate(id: string): boolean {
    const f = followups.value.find((x) => x.id === id)
    if (!f || f.status !== 'PENDING' || f.escalated) return false
    f.escalated = true
    activity.log(auth.user.name, `术后随访 ${f.followupNo} 超期未完成，已升级提醒主管/医生`, f.id)
    return true
  }

  /** 登记回访结果 */
  function complete(
    id: string,
    result: {
      satisfaction: number
      recovery: RecoveryStatus
      adverseReaction: boolean
      adverseNote?: string
      needRevisit: boolean
      note?: string
      method?: FollowupMethod
    },
  ): boolean {
    const f = followups.value.find((x) => x.id === id)
    if (!f || f.status !== 'PENDING') return false
    if (!auth.can('followup:edit')) {
      console.warn('[followup] 无 followup:edit 权限')
      return false
    }
    const now = new Date().toISOString()
    f.status = 'DONE'
    f.satisfaction = result.satisfaction
    f.recovery = result.recovery
    f.adverseReaction = result.adverseReaction
    f.adverseNote = result.adverseReaction ? result.adverseNote?.trim() || undefined : undefined
    f.needRevisit = result.needRevisit
    f.note = result.note?.trim() || undefined
    if (result.method) f.method = result.method
    f.followupByName = auth.user.name
    f.doneAt = now
    activity.log(
      auth.user.name,
      `完成回访 ${f.followupNo}，满意度 ${result.satisfaction} 星${result.adverseReaction ? '（有不良反应，建议转投诉跟进）' : ''}`,
      f.id,
    )
    return true
  }

  /** 标记无需回访（如客户明确拒绝、失联等） */
  function skip(id: string, reason: string): boolean {
    const f = followups.value.find((x) => x.id === id)
    if (!f || f.status !== 'PENDING') return false
    if (!auth.can('followup:edit')) {
      console.warn('[followup] 无 followup:edit 权限')
      return false
    }
    const now = new Date().toISOString()
    f.status = 'SKIPPED'
    f.note = reason.trim()
    f.followupByName = auth.user.name
    f.doneAt = now
    activity.log(auth.user.name, `回访 ${f.followupNo} 标记无需回访：${reason}`, f.id)
    return true
  }

  /** 重新安排回访日期（改期） */
  function reschedule(id: string, planDate: string): boolean {
    const f = followups.value.find((x) => x.id === id)
    if (!f || f.status !== 'PENDING') return false
    if (!auth.can('followup:edit')) {
      console.warn('[followup] 无 followup:edit 权限')
      return false
    }
    f.planDate = planDate
    activity.log(auth.user.name, `回访 ${f.followupNo} 改期至 ${planDate.slice(0, 10)}`, f.id)
    return true
  }

  /** 开发期种子 */
  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    const today = new Date()
    const iso = (d: Date) => d.toISOString().slice(0, 10)
    const dayShift = (n: number) => { const d = new Date(today); d.setDate(d.getDate() + n); return iso(d) }

    const seedData: Array<Partial<Followup> & {
      customerName: string; project: string; serviceDate: string; planDate: string; status: FollowupStatus
    }> = [
      // 超期 2 天未回访
      { customerName: '王美丽', project: '光子嫩肤', serviceDate: dayShift(-5), planDate: dayShift(-2), status: 'PENDING', relatedOrderNo: 'SO20260820001', method: 'PHONE' },
      // 今日待回访
      { customerName: '陈思', project: '水光针', serviceDate: dayShift(-3), planDate: dayShift(0), status: 'PENDING', relatedOrderNo: 'SO20260822005', method: 'WECHAT' },
      // 今日待回访
      { customerName: '赵敏', project: '热玛吉 4 代', serviceDate: dayShift(-7), planDate: dayShift(0), status: 'PENDING', relatedOrderNo: 'SO20260818003', method: 'PHONE' },
      // 明天待回访
      { customerName: '林晚', project: '瘦脸针 100U', serviceDate: dayShift(-2), planDate: dayShift(1), status: 'PENDING', method: 'IN_STORE' },
      // 已回访 - 满意
      { customerName: '周婷', project: '果酸焕肤', serviceDate: dayShift(-10), planDate: dayShift(-7), status: 'DONE', relatedOrderNo: 'SO20260815009', method: 'PHONE', satisfaction: 5, recovery: 'GOOD', adverseReaction: false, needRevisit: false, note: '客户反馈皮肤状态很好，预约下月二次治疗。' },
      // 已回访 - 有不良反应（应提示转投诉）
      { customerName: '吴桐', project: '光子嫩肤', serviceDate: dayShift(-8), planDate: dayShift(-5), status: 'DONE', method: 'PHONE', satisfaction: 2, recovery: 'POOR', adverseReaction: true, adverseNote: '面部轻微红肿持续 3 天，已预约本周复诊所见医生。', needRevisit: true, note: '已安抚客户并安排复诊，持续跟进。' },
      // 已回访 - 一般
      { customerName: '孙莉', project: '水光针', serviceDate: dayShift(-12), planDate: dayShift(-9), status: 'DONE', method: 'WECHAT', satisfaction: 4, recovery: 'NORMAL', adverseReaction: false, needRevisit: false },
      // 无需回访
      { customerName: '李娜', project: '皮肤检测', serviceDate: dayShift(-4), planDate: dayShift(-1), status: 'SKIPPED', method: 'PHONE', note: '客户为外地游客，婉拒回访。' },
      // C 端会员「陈美玲」的回访（C 端 /m/followup 使用）
      { customerName: '陈美玲', project: '水光焕肤', serviceDate: dayShift(-3), planDate: dayShift(0), status: 'PENDING', relatedOrderNo: 'SO20260823008', method: 'WECHAT' },
      { customerName: '陈美玲', project: '光子嫩肤', serviceDate: dayShift(-14), planDate: dayShift(-7), status: 'DONE', relatedOrderNo: 'SO20260812003', method: 'WECHAT', satisfaction: 5, recovery: 'GOOD', adverseReaction: false, needRevisit: false, note: '皮肤状态良好，已预约下月护理。' },
    ]

    seedData.forEach((s, i) => {
      seq += 1
      const createdIso = new Date(s.serviceDate).toISOString()
      const f: Followup = {
        id: nextId('fu'),
        followupNo: `HF2026082${4 - (i % 5)}0${i + 1}`,
        customerId: `C-50${i}`,
        customerName: s.customerName!,
        project: s.project!,
        relatedOrderNo: s.relatedOrderNo,
        serviceDate: s.serviceDate,
        planDate: s.planDate,
        method: s.method ?? 'PHONE',
        status: s.status,
        adverseReaction: s.adverseReaction ?? false,
        adverseNote: s.adverseNote,
        needRevisit: s.needRevisit ?? false,
        recovery: s.recovery,
        satisfaction: s.satisfaction,
        note: s.note,
        createdAt: createdIso,
      }
      if (s.status === 'DONE' || s.status === 'SKIPPED') {
        f.followupByName = s.status === 'DONE' ? '白桥（运营）' : '夏沫（前台）'
        f.doneAt = new Date(s.planDate).toISOString()
      }
      followups.value.push(f)
    })

    // ---- SOP 批次种子：绑定真实客户（C-201~C-204），覆盖待办/超期/升级/完结全状态 ----
    const seedBatch = (
      batchNo: string,
      customerId: string,
      customerName: string,
      project: string,
      relatedOrderNo: string,
      serviceOffset: number,
      nodeStates: Array<{ def: SopNodeDef; state: 'DONE' | 'PENDING' | 'SKIPPED'; escalated?: boolean; satisfaction?: number; recovery?: RecoveryStatus }>,
    ) => {
      const batchId = `SOP-SEED-${batchNo}`
      const svcDate = new Date(today); svcDate.setDate(svcDate.getDate() + serviceOffset)
      nodeStates.forEach((ns, idx) => {
        seq += 1
        const plan = new Date(svcDate); plan.setDate(plan.getDate() + ns.def.dayOffset)
        const node: Followup = {
          id: nextId('fu'),
          followupNo: `HF${batchNo}${idx + 1}`,
          customerId,
          customerName,
          project,
          relatedOrderNo,
          serviceDate: svcDate.toISOString(),
          planDate: plan.toISOString(),
          method: ns.def.method,
          status: ns.state,
          adverseReaction: false,
          needRevisit: false,
          sopStage: ns.def.stage,
          sopLabel: ns.def.label,
          sopBatchId: batchId,
          escalated: ns.escalated,
          satisfaction: ns.satisfaction,
          recovery: ns.recovery,
          note: ns.state === 'DONE' ? '恢复情况良好，按 SOP 话术完成关怀。' : undefined,
          followupByName: ns.state === 'DONE' ? '白桥（运营）' : undefined,
          doneAt: ns.state === 'DONE' ? plan.toISOString() : undefined,
          createdAt: svcDate.toISOString(),
        }
        followups.value.push(node)
      })
    }

    const [n24h, n3d, n7d, n30d] = DEFAULT_POST_OP_SOP
    // 批次1：王小姐 光子嫩肤（术后 10 天）——24h/3d 已完成，7d 超期未回访未升级，30d 待办
    seedBatch('01', 'C-201', '王小姐', '光子嫩肤', 'SO20260818002', -10, [
      { def: n24h, state: 'DONE', satisfaction: 5, recovery: 'GOOD' },
      { def: n3d, state: 'DONE', satisfaction: 5, recovery: 'GOOD' },
      { def: n7d, state: 'PENDING' },
      { def: n30d, state: 'PENDING' },
    ])
    // 批次2：李女士 水光针（术后 4 天）——24h 已完成，3d 超期已升级主管，7d 待办
    seedBatch('02', 'C-202', '李女士', '水光针', 'SO20260824006', -4, [
      { def: n24h, state: 'DONE', satisfaction: 4, recovery: 'NORMAL' },
      { def: n3d, state: 'PENDING', escalated: true },
      { def: n7d, state: 'PENDING' },
      { def: n30d, state: 'PENDING' },
    ])
    // 批次3：赵女士 热玛吉（术后 35 天）——前三节点已完成，30d 复诊提醒超期已升级
    seedBatch('03', 'C-204', '赵女士', '热玛吉 4 代', 'SO20260724001', -35, [
      { def: n24h, state: 'DONE', satisfaction: 5, recovery: 'GOOD' },
      { def: n3d, state: 'DONE', satisfaction: 4, recovery: 'GOOD' },
      { def: n7d, state: 'SKIPPED' },
      { def: n30d, state: 'PENDING', escalated: true },
    ])
    // 批次4：张同学 果酸焕肤（术后 40 天）——全部完结
    seedBatch('04', 'C-203', '张同学', '果酸焕肤', 'SO20260719008', -40, [
      { def: n24h, state: 'DONE', satisfaction: 5, recovery: 'GOOD' },
      { def: n3d, state: 'DONE', satisfaction: 5, recovery: 'GOOD' },
      { def: n7d, state: 'DONE', satisfaction: 5, recovery: 'GOOD' },
      { def: n30d, state: 'DONE', satisfaction: 5, recovery: 'GOOD' },
    ])
  }

  /** C 端消费者自助提交回访（联动 5：C 端 → M4-11，不需要 followup:edit 权限） */
  function submitByCustomer(
    id: string,
    result: { satisfaction: number; note?: string; adverseReaction?: boolean; adverseNote?: string },
  ): boolean {
    const f = followups.value.find((x) => x.id === id)
    if (!f || f.status !== 'PENDING') return false
    const now = new Date().toISOString()
    f.status = 'DONE'
    f.satisfaction = result.satisfaction
    f.recovery = result.satisfaction >= 4 ? 'GOOD' : result.satisfaction >= 3 ? 'NORMAL' : 'POOR'
    f.adverseReaction = !!result.adverseReaction
    f.adverseNote = result.adverseReaction ? result.adverseNote?.trim() || undefined : undefined
    f.needRevisit = !!result.adverseReaction || result.satisfaction <= 2
    f.note = result.note?.trim() || undefined
    f.method = 'WECHAT'
    f.followupByName = '顾客自助提交（C 端）'
    f.doneAt = now
    activity.log(
      f.customerName,
      `C 端自助完成回访 ${f.followupNo}，满意度 ${result.satisfaction} 星${result.adverseReaction ? '（有不良反应，已转 M4-12 投诉跟进）' : ''}`,
      f.id,
    )
    return true
  }

  return {
    followups, pending, done, skipped, overdue, todayPending, avgSatisfaction, adverseCount,
    sopPending, sopOverdue, sopOverdueNeedEscalation, sopBatches,
    sopTemplate, enabledSopNodes,
    get, schedule, schedulePostOpSop, sopOfBatch, sopOfCustomer, escalate, escalateAllOverdue,
    toggleSopNode, updateSopNode, addSopNode, removeSopNode, resetSopTemplate,
    complete, skip, reschedule, submitByCustomer, seed,
  }
})
