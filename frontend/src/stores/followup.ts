// ============================================================
// Followup 聚合 store（术后回访与满意度）
// 状态机：PENDING（待回访）→ DONE（已回访）/ SKIPPED（无需回访）。
// - planDate 早于当前且仍 PENDING 的视为"超期未回访"，页面高亮预警。
// - 满意度 1-5 星；标记不良反应 (adverseReaction) 自动提示转投诉/医疗风险。
// P5-B31：随访工作台接真——列表真分页（后端固定 planDate,id 升序）+ stats 九键聚合 +
// 手工建/登记/无需回访走 txn-service；BoardView 待回访列由 sopTodos 真 SOP 待办驱动。
// 适配层（铁律：模板/样式零改动，只换数据源）：
//  - id 取数据库主键字符串（路径端点吃 Long id，与 emr 取 emrNo 相反）；followupNo 仅展示
//  - serviceDate/planDate 直传后端 LocalDate（yyyy-MM-dd，禁止 toISOString 时区错位）
//  - 可空文本 null → undefined；权限与状态机校验由后端兜底，400/403 中文经 errMsg 外露
// 过渡保留（卡B 接真前不白屏）：SOP 模板/批次编排 mock 服务 /sop；followups 演示种子服务 C 端 /m/followup。
//  SOP 节点已由后端 FollowupScheduler 在治疗完成 AFTER_COMMIT 自动排程，schedulePostOpSop 降为空 shim 防重复。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { useCustomerStore } from './customer'
import { useToast } from '@/composables/useToast'
import { errMsg } from './m5Coupon'
import {
  listFollowup, statsFollowup, getFollowup, createFollowup,
  completeFollowup, skipFollowup,
  type FollowupViewDTO, type FollowupStats,
} from '@/api/followup'

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

const EMPTY_STATS: FollowupStats = {
  sopPending: 0, sopOverdue: 0, pending: 0, todayPending: 0, overdue: 0,
  done: 0, skipped: 0, avgSatisfaction: 0, adverseCount: 0,
}

export const useFollowupStore = defineStore('followup', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()
  const customer = useCustomerStore()
  const toast = useToast()

  // ==================== 真实数据层（随访工作台 / BoardView） ====================

  /** 当前 tab 当前页记录（分页后不再全量驻留）。 */
  const records = ref<Followup[]>([])
  const page = ref(1)
  const pageSize = ref(20)
  const total = ref(0)
  const totalPages = ref(1)
  const loading = ref(false)
  /** 本店随访计数九键（tab 角标 / KPI 取后端聚合）。 */
  const stats = ref<FollowupStats>({ ...EMPTY_STATS })
  /** 详情缓存：跨页选中后详情不丢。 */
  const detailCache = ref<Record<string, Followup>>({})
  /** 工作台待回访列真 SOP 待办（sopOnly+PENDING，loadSopTodos 填充）。 */
  const sopTodos = ref<Followup[]>([])

  // ==================== 过渡演示层（/sop 编排 mock + C 端 /m/followup 演示种子） ====================

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

  // ---- /sop 批次看板与角标（mock，卡B 批次端点接真前保留） ----
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

  function cacheRecord(r: Followup) {
    detailCache.value[r.id] = r
  }

  /** 工作台/跨页选中：当前页 → 详情缓存 → 演示种子（C 端兜底） */
  function get(id: string) {
    return records.value.find((f) => f.id === id)
      ?? detailCache.value[id]
      ?? followups.value.find((f) => f.id === id)
  }

  /** 拉取单条随访详情并入缓存（跨页选中/直接链接）。 */
  async function fetchDetail(id: string): Promise<Followup | null> {
    const cached = detailCache.value[id]
    if (cached && records.value.some((r) => r.id === id)) return cached
    try {
      const res = await getFollowup(id)
      const r = adaptFollowup(res.data)
      cacheRecord(r)
      return r
    } catch (e) {
      toast.error(errMsg(e, '随访详情加载失败'))
      return null
    }
  }

  function adaptFollowup(d: FollowupViewDTO): Followup {
    return {
      id: d.id,
      followupNo: d.followupNo,
      customerId: d.customerId,
      customerName: d.customerName,
      project: d.project,
      relatedOrderNo: d.relatedOrderNo ?? undefined,
      serviceDate: d.serviceDate,
      planDate: d.planDate,
      method: (d.method as FollowupMethod) || 'PHONE',
      status: d.status as FollowupStatus,
      sopStage: (d.sopStage as SopStage | null) ?? undefined,
      sopLabel: d.sopLabel ?? undefined,
      sopBatchId: d.sopBatchId ?? undefined,
      escalated: d.escalated,
      satisfaction: d.satisfaction ?? undefined,
      recovery: (d.recovery as RecoveryStatus | null) ?? undefined,
      adverseReaction: d.adverseReaction,
      adverseNote: d.adverseNote ?? undefined,
      needRevisit: d.needRevisit,
      note: d.note ?? undefined,
      followupByName: d.followupByName ?? undefined,
      doneAt: d.doneAt ?? undefined,
      createdAt: d.createdAt,
    }
  }

  /**
   * 拉取随访分页（锁当前门店；后端固定 planDate,id 升序，不信入参 sort）。
   * @param storeCode 门店码（省略取 JWT 本店）
   * @param filters status/customerId/sopBatchId/sopOnly 精确 + keyword 三字段模糊
   * @param opts.page 1 起页码
   */
  async function load(
    storeCode?: string,
    filters?: { status?: string; customerId?: string; sopBatchId?: string; sopOnly?: boolean; keyword?: string },
    opts?: { page?: number; size?: number; silent?: boolean },
  ): Promise<boolean> {
    const targetPage = Math.max(1, opts?.page ?? page.value)
    const size = opts?.size ?? pageSize.value
    loading.value = true
    try {
      const res = await listFollowup({
        storeCode,
        status: filters?.status,
        customerId: filters?.customerId,
        sopBatchId: filters?.sopBatchId,
        sopOnly: filters?.sopOnly,
        keyword: filters?.keyword?.trim() || undefined,
        page: targetPage - 1,
        size,
      })
      const data = res.data
      const list = (data.content ?? []).map(adaptFollowup)
      records.value = list
      list.forEach(cacheRecord)
      total.value = data.totalElements ?? 0
      totalPages.value = Math.max(1, data.totalPages ?? 1)
      page.value = targetPage
      pageSize.value = size
      customer.hydrate(list.map((d) => ({ customerId: d.customerId, customerName: d.customerName })))
      return true
    } catch (e) {
      records.value = []
      total.value = 0
      if (!opts?.silent) toast.error(errMsg(e, '随访列表加载失败'))
      return false
    } finally {
      loading.value = false
    }
  }

  /** 拉取本店计数九键（tab 角标/KPI；写动作后静默刷新）。 */
  async function loadStats(storeCode?: string): Promise<void> {
    try {
      stats.value = (await statsFollowup(storeCode)).data
    } catch {
      // 计数失败不阻断主流程，保留上次计数
    }
  }

  /** 列表 + 计数一并刷新（页面初始化/写动作后）。 */
  async function refresh(
    storeCode?: string,
    filters?: { status?: string; customerId?: string; sopBatchId?: string; sopOnly?: boolean; keyword?: string },
    opts?: { page?: number; size?: number; silent?: boolean },
  ): Promise<boolean> {
    const ok = await load(storeCode, filters, opts)
    await loadStats(storeCode)
    return ok
  }

  /** 工作台待回访列：本店 PENDING 的术后 SOP 节点（最多 100 条）。 */
  async function loadSopTodos(storeCode?: string): Promise<void> {
    try {
      const res = await listFollowup({
        storeCode, status: 'PENDING', sopOnly: true, page: 0, size: 100,
      })
      sopTodos.value = (res.data.content ?? []).map(adaptFollowup)
    } catch {
      sopTodos.value = []
    }
  }

  /** 手工建普通随访（MANUAL；日期传 yyyy-MM-dd；客户须已建档，否则后端 400 中文引导）。 */
  async function create(input: {
    customerId: string
    project: string
    relatedOrderNo?: string
    serviceDate: string
    planDate: string
    method?: FollowupMethod
  }): Promise<Followup | null> {
    try {
      const res = await createFollowup({
        customerId: input.customerId,
        project: input.project.trim(),
        relatedOrderNo: input.relatedOrderNo?.trim() || undefined,
        serviceDate: input.serviceDate,
        planDate: input.planDate,
        method: input.method,
      })
      const r = adaptFollowup(res.data)
      cacheRecord(r)
      customer.hydrate([{ customerId: r.customerId, customerName: r.customerName }])
      activity.log(auth.user.name, `生成回访计划 ${r.followupNo}（${r.customerName}·${r.project}）`, r.id)
      return r
    } catch (e) {
      toast.error(errMsg(e, '新建回访计划失败'))
      return null
    }
  }

  /** 登记回访结果（PENDING → DONE；满意度/恢复情况/不良反应说明校验由后端兜底）。 */
  async function complete(
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
  ): Promise<boolean> {
    try {
      const res = await completeFollowup(id, {
        satisfaction: result.satisfaction,
        recovery: result.recovery,
        adverseReaction: result.adverseReaction,
        adverseNote: result.adverseReaction ? result.adverseNote?.trim() || undefined : undefined,
        needRevisit: result.needRevisit,
        note: result.note?.trim() || undefined,
        method: result.method,
      })
      cacheRecord(adaptFollowup(res.data))
      activity.log(
        auth.user.name,
        `完成回访 ${res.data.followupNo}，满意度 ${result.satisfaction} 星${result.adverseReaction ? '（有不良反应，建议转投诉跟进）' : ''}`,
        id,
      )
      return true
    } catch (e) {
      toast.error(errMsg(e, '回访结果提交失败'))
      return false
    }
  }

  /** 标记无需回访（如客户明确拒绝、失联等；原因必填留痕）。 */
  async function skip(id: string, reason: string): Promise<boolean> {
    try {
      const res = await skipFollowup(id, reason.trim())
      cacheRecord(adaptFollowup(res.data))
      activity.log(auth.user.name, `回访 ${res.data.followupNo} 标记无需回访：${reason.trim()}`, id)
      return true
    } catch (e) {
      toast.error(errMsg(e, '标记无需回访失败'))
      return false
    }
  }

  /**
   * 术后 SOP 自动排程 shim：节点已由后端 FollowupScheduler 在治疗完成 AFTER_COMMIT 排程，
   * 前端不再造假节点（防重复）。保留签名形状供咨询动线调用，恒返空数组。
   */
  async function schedulePostOpSop(_input?: {
    customerId: string
    customerName: string
    project: string
    relatedOrderNo?: string
    serviceDate?: string
    nodes?: SopNodeDef[]
  }): Promise<Followup[]> {
    return []
  }

  /** 某客户/某批次的 SOP 节点（按计划时间升序；mock 演示数据，卡B 接真后替换） */
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

  /** SOP 待办（术后节点，未完成）——以 sopBatchId 判定，含自定义节点（mock 角标，/sop 卡B 接真前保留） */
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

  /** 重新安排回访日期（改期；mock 演示动作，后端端点卡B 后补） */
  function reschedule(id: string, planDate: string): boolean {
    const f = followups.value.find((x) => x.id === id)
    if (!f || f.status !== 'PENDING') return false
    f.planDate = planDate
    activity.log(auth.user.name, `回访 ${f.followupNo} 改期至 ${planDate.slice(0, 10)}`, f.id)
    return true
  }

  /** 开发期演示种子（/sop 批次看板 + C 端 /m/followup；真实工作台走 load/refresh） */
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

  /** C 端消费者自助提交回访（联动 5：C 端 → M4-11，不需要 followup:edit 权限；演示种子动作） */
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
    // 真实数据层
    records, page, pageSize, total, totalPages, loading, stats, sopTodos,
    get, fetchDetail, load, loadStats, refresh, loadSopTodos, create, complete, skip,
    // 过渡演示层（/sop + C 端）
    followups, pending, done, skipped, overdue, todayPending, avgSatisfaction, adverseCount,
    sopPending, sopOverdue, sopOverdueNeedEscalation, sopBatches,
    sopTemplate, enabledSopNodes,
    schedulePostOpSop, sopOfBatch, sopOfCustomer, escalate, escalateAllOverdue,
    toggleSopNode, updateSopNode, addSopNode, removeSopNode, resetSopTemplate,
    reschedule, submitByCustomer, seed,
  }
})
