// ============================================================
// 划扣执行台 store（M2-01）—— 真实 API 驱动
// 门店端"待划扣队列 + 双签执行"：与 /writeoff 交易核销视图独立。
// 数据来自 txn-service /api/txn/writeoff-desk：
//  - 预约签到自动生成 APPOINTMENT 任务（AppointmentController.checkIn 同事务建单）；
//  - 老客未预约直接到店由门店手工建 WALKIN 任务（createWalkin）。
// 金额口径：后端 amount bigint 存「分」，本页活规格用「元」，换算在适配层 fen2yuan。
// M6-09 财务划扣明细页只读镜像同一 store，故 items/标签常量/seed 形状保持不变。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useActivityStore } from './activity'
import { useAuthStore } from './auth'
import {
  listWdTasks, createWdWalkin, executeWdTask, markWdException, resetWdTask, listWdCustomerCards,
  type WdTaskDTO, type WdCardOptionDTO,
} from '@/api/writeoffDesk'

export type WdSource = 'APPOINTMENT' | 'WALKIN'
export type WdStatus = 'PENDING' | 'DONE' | 'EXCEPTION'
export type WdExceptionReason = 'NONE' | 'CUSTOMER_ABSENT' | 'COUNT_MISMATCH' | 'EQUIPMENT_FAULT' | 'OTHER'

export interface WdTimeline {
  by: string
  text: string
  at: string
}

export interface WriteoffDeskItem {
  id: string
  no: string
  /** 内部字段：供卡选择器/建单反查，页面不渲染 */
  customerId?: string
  storeCode?: string
  cardNo?: string | null
  customerName: string
  phone: string
  project: string
  cardName: string
  totalCount: number
  remainingCount: number
  /** 本次划扣金额（元） */
  amount: number
  operator: string
  reviewer?: string
  source: WdSource
  status: WdStatus
  exceptionReason: WdExceptionReason
  appointmentTime: string
  executedAt?: string
  timeline: WdTimeline[]
}

/** 卡选择器选项（金额单位：元）。 */
export interface WdCardOption {
  cardNo: string
  cardName: string
  storeCode: string
  totalTimes: number
  remainTimes: number
  balance: number
  unitAmount: number
}

const SOURCE_LABEL: Record<WdSource, string> = {
  APPOINTMENT: '预约到店',
  WALKIN: '直接到店',
}
const STATUS_LABEL: Record<WdStatus, string> = {
  PENDING: '待执行',
  DONE: '已划扣',
  EXCEPTION: '异常',
}
const EXCEPTION_LABEL: Record<WdExceptionReason, string> = {
  NONE: '—',
  CUSTOMER_ABSENT: '客户未到',
  COUNT_MISMATCH: '次数不符',
  EQUIPMENT_FAULT: '设备故障',
  OTHER: '其他',
}

// -------------------- 适配层（后端 DTO ↔ 页面活规格） --------------------

const fen2yuan = (f: number | null | undefined): number => (f == null ? 0 : f / 100)

function adapt(dto: WdTaskDTO): WriteoffDeskItem {
  return {
    id: dto.id,
    no: dto.no,
    customerId: dto.customerId,
    storeCode: dto.storeCode,
    cardNo: dto.cardNo ?? null,
    customerName: dto.customerName || dto.customerId || '未知客户',
    phone: dto.phone || '',
    project: dto.project,
    cardName: dto.cardName || '—',
    totalCount: dto.totalCount ?? 0,
    remainingCount: dto.remainingCount ?? 0,
    amount: fen2yuan(dto.amount),
    operator: dto.operator || '—',
    reviewer: dto.reviewer ?? undefined,
    source: (dto.source || 'APPOINTMENT') as WdSource,
    status: (dto.status || 'PENDING') as WdStatus,
    exceptionReason: (dto.exceptionReason || 'NONE') as WdExceptionReason,
    appointmentTime: dto.appointmentTime,
    executedAt: dto.executedAt ?? undefined,
    timeline: (dto.timeline || []).map((t) => ({ by: t.by || '系统', text: t.text || '', at: t.at || '' })),
  }
}

function adaptCard(dto: WdCardOptionDTO): WdCardOption {
  return {
    cardNo: dto.cardNo,
    cardName: dto.cardName,
    storeCode: dto.storeCode,
    totalTimes: dto.totalTimes ?? 0,
    remainTimes: dto.remainTimes ?? 0,
    balance: fen2yuan(dto.balance),
    unitAmount: fen2yuan(dto.unitAmount),
  }
}

/** 提取后端中文错误消息。 */
function errText(e: unknown): string {
  const anyE = e as { response?: { data?: { message?: string; error?: string } }; message?: string }
  return anyE?.response?.data?.message || anyE?.response?.data?.error || anyE?.message || '操作失败，请重试'
}

export const useWriteoffDeskStore = defineStore('writeoffDesk', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const items = ref<WriteoffDeskItem[]>([])
  const filterSource = ref<WdSource | 'ALL'>('ALL')
  const filterStatus = ref<WdStatus | 'ALL'>('ALL')
  const loading = ref(false)

  const pending = computed(() => items.value.filter((i) => i.status === 'PENDING'))
  const done = computed(() => items.value.filter((i) => i.status === 'DONE'))
  const exception = computed(() => items.value.filter((i) => i.status === 'EXCEPTION'))
  const todayAmount = computed(() =>
    done.value.reduce((sum, i) => sum + i.amount, 0),
  )

  const filtered = computed(() => {
    let list = items.value
    if (filterSource.value !== 'ALL') list = list.filter((i) => i.source === filterSource.value)
    if (filterStatus.value !== 'ALL') list = list.filter((i) => i.status === filterStatus.value)
    return [...list].sort((a, b) => new Date(b.appointmentTime).getTime() - new Date(a.appointmentTime).getTime())
  })

  function get(id: string) {
    return items.value.find((i) => i.id === id)
  }

  function upsert(it: WriteoffDeskItem) {
    const idx = items.value.findIndex((x) => x.id === it.id)
    if (idx >= 0) items.value[idx] = it
    else items.value.unshift(it)
  }

  /** 拉取今日队列；seed 为 M2/M6 两页 onMounted 兼容别名。 */
  async function load() {
    loading.value = true
    try {
      const { data } = await listWdTasks()
      items.value = (data || []).map(adapt)
    } catch (e) {
      console.error('[writeoffDesk] 队列加载失败', e)
    } finally {
      loading.value = false
    }
  }
  async function seed() {
    await load()
  }

  /** 客户本店在用卡（双签弹窗/手工建单选卡）。失败返回空数组由调用方提示。 */
  async function customerCards(customerId: string, storeCode?: string): Promise<WdCardOption[]> {
    try {
      const { data } = await listWdCustomerCards(customerId, storeCode)
      return (data || []).map(adaptCard)
    } catch (e) {
      console.error('[writeoffDesk] 客户卡列表加载失败', e)
      return []
    }
  }

  /**
   * 双签划扣：reviewer 必填，cardNo 可选（缺省用任务绑定卡）。
   * 后端同事务扣卡余次/余额、写 writeoff_record（sign1 操作人 / sign2 复核人）、任务置 DONE。
   * 返回 { ok, reason }，不抛出，由页面 toast 中文原因。
   */
  async function execute(
    id: string,
    reviewer: string,
    cardNo?: string,
    remark?: string,
  ): Promise<{ ok: boolean; reason?: string }> {
    const it = items.value.find((i) => i.id === id)
    if (!it) return { ok: false, reason: '任务不存在' }
    if (it.status === 'DONE') return { ok: false, reason: '该任务已划扣，请勿重复操作' }
    if (it.status === 'EXCEPTION') return { ok: false, reason: '异常单请先解除异常后再划扣' }
    if (!auth.can('writeoff:create')) return { ok: false, reason: '无划扣执行权限' }
    if (!reviewer.trim()) return { ok: false, reason: '请填写复核人' }
    try {
      const { data } = await executeWdTask(it.no, {
        reviewer: reviewer.trim(),
        ...(cardNo ? { cardNo } : {}),
        ...(remark && remark.trim() ? { remark: remark.trim() } : {}),
      })
      upsert(adapt(data))
      activity.log(auth.user?.name ?? '前台', `双签划扣 ${it.no}：${it.customerName} - ${it.project}（复核 ${reviewer.trim()}）`, it.id)
      return { ok: true }
    } catch (e) {
      return { ok: false, reason: errText(e) }
    }
  }

  /** 标记异常（DONE 不可标）。 */
  async function markException(
    id: string,
    reason: WdExceptionReason,
    note?: string,
  ): Promise<{ ok: boolean; reason?: string }> {
    const it = items.value.find((i) => i.id === id)
    if (!it) return { ok: false, reason: '任务不存在' }
    if (it.status === 'DONE') return { ok: false, reason: '已划扣任务不可标记异常' }
    if (!auth.can('writeoff:edit')) return { ok: false, reason: '无异常处理权限' }
    try {
      const { data } = await markWdException(it.no, {
        reason,
        ...(note && note.trim() ? { note: note.trim() } : {}),
      })
      upsert(adapt(data))
      activity.log(auth.user?.name ?? '前台', `划扣异常 ${it.no}：${EXCEPTION_LABEL[reason]}`, it.id)
      return { ok: true }
    } catch (e) {
      return { ok: false, reason: errText(e) }
    }
  }

  /** 解除异常：恢复待执行。 */
  async function resetToPending(id: string): Promise<{ ok: boolean; reason?: string }> {
    const it = items.value.find((i) => i.id === id)
    if (!it) return { ok: false, reason: '任务不存在' }
    if (it.status !== 'EXCEPTION') return { ok: false, reason: '仅异常单可解除' }
    if (!auth.can('writeoff:edit')) return { ok: false, reason: '无异常处理权限' }
    try {
      const { data } = await resetWdTask(it.no)
      upsert(adapt(data))
      activity.log(auth.user?.name ?? '前台', `解除异常 ${it.no}，恢复待执行`, it.id)
      return { ok: true }
    } catch (e) {
      return { ok: false, reason: errText(e) }
    }
  }

  /**
   * 老客未预约直接到店手工建单（WALKIN）。
   * 门店取当前登录人 storeCode；cardNo 不传由后端绑本店在用最新卡。
   */
  async function createWalkin(cmd: {
    customerId: string
    project: string
    cardNo?: string
  }): Promise<{ ok: boolean; reason?: string; item?: WriteoffDeskItem }> {
    if (!auth.can('writeoff:create')) return { ok: false, reason: '无建单权限' }
    const storeCode = auth.user?.storeId || ''
    if (!cmd.customerId.trim()) return { ok: false, reason: '请选择到店客户' }
    if (!cmd.project.trim()) return { ok: false, reason: '请填写核销项目' }
    if (!storeCode) return { ok: false, reason: '当前登录人无归属门店，无法建单' }
    try {
      const { data } = await createWdWalkin({
        customerId: cmd.customerId.trim(),
        storeCode,
        project: cmd.project.trim(),
        ...(cmd.cardNo ? { cardNo: cmd.cardNo } : {}),
      })
      const it = adapt(data)
      upsert(it)
      activity.log(auth.user?.name ?? '前台', `手工建单 ${it.no}：${it.customerName} - ${it.project}（直接到店）`, it.id)
      return { ok: true, item: it }
    } catch (e) {
      return { ok: false, reason: errText(e) }
    }
  }

  return {
    items, filterSource, filterStatus, loading,
    pending, done, exception, todayAmount, filtered,
    get, load, seed, customerCards,
    execute, markException, resetToPending, createWalkin,
    SOURCE_LABEL, STATUS_LABEL, EXCEPTION_LABEL,
  }
})
