// ============================================================
// 客户检索 outbox 事件处置台 store（B32）
// 权威源：customer-service /customer/search-events（区域/集团级全局运维视图，无门店维度）。
// 分页下推（后端固定 eventId 倒序）；四态 KPI 由 /stats 独立加载；写动作后刷新当前页与 KPI。
// ============================================================
import { defineStore } from 'pinia'
import { ref } from 'vue'
import {
  listSearchEvents, statsSearchEvents, retrySearchEvent,
  replaySearchEvent, discardSearchEvent, reindexCustomerSearch,
  type SearchEventDTO, type SearchEventStats, type SearchEventStatus,
} from '@/api/searchEvent'

const EMPTY_STATS: SearchEventStats = { pending: 0, sent: 0, dead: 0, discarded: 0 }

export const useSearchEventStore = defineStore('searchEvent', () => {
  const events = ref<SearchEventDTO[]>([])
  const stats = ref<SearchEventStats>({ ...EMPTY_STATS })
  const loading = ref(false)
  const acting = ref(false)
  const reindexing = ref(false)

  const page = ref(1)
  const pageSize = ref(20)
  const total = ref(0)
  const totalPages = ref(1)

  const fStatus = ref<SearchEventStatus | ''>('')
  const fCustomerId = ref('')
  const fEventId = ref<number | undefined>(undefined)

  /** 拉取分页（1 起页码 → 后端 0 起）；筛选变化时应回到第 1 页。 */
  async function load(targetPage?: number): Promise<void> {
    const target = Math.max(1, targetPage ?? page.value)
    loading.value = true
    try {
      const res = await listSearchEvents({
        status: fStatus.value || undefined,
        customerId: fCustomerId.value.trim() || undefined,
        eventId: fEventId.value,
        page: target - 1,
        size: pageSize.value,
      })
      const data = res.data
      events.value = data.content ?? []
      total.value = data.totalElements ?? 0
      totalPages.value = Math.max(1, data.totalPages ?? 1)
      page.value = target
    } catch {
      events.value = []
      total.value = 0
      totalPages.value = 1
    } finally {
      loading.value = false
    }
  }

  /** 静默拉 KPI（页面加载与写动作后调用）。 */
  async function loadStats(silent = false): Promise<void> {
    try {
      stats.value = (await statsSearchEvents()).data ?? { ...EMPTY_STATS }
    } catch (e) {
      if (!silent) throw e
    }
  }

  async function search(): Promise<void> {
    await load(1)
  }

  function resetFilter(): void {
    fStatus.value = ''
    fCustomerId.value = ''
    fEventId.value = undefined
  }

  /** 写动作统一包装：按钮态 + 动作后刷新当前页/KPI；失败时错误向上抛，由视图 errMsg 透传。 */
  async function runAction(fn: () => Promise<unknown>): Promise<void> {
    acting.value = true
    try {
      await fn()
      await Promise.all([load(), loadStats(true)])
    } finally {
      acting.value = false
    }
  }

  async function retry(id: number): Promise<void> {
    await runAction(() => retrySearchEvent(id))
  }

  async function replay(id: number): Promise<void> {
    await runAction(() => replaySearchEvent(id))
  }

  async function discard(id: number, note: string): Promise<void> {
    await runAction(() => discardSearchEvent(id, note))
  }

  async function reindex(): Promise<number> {
    reindexing.value = true
    try {
      const res = await reindexCustomerSearch()
      await loadStats(true)
      return res.data?.indexed ?? 0
    } finally {
      reindexing.value = false
    }
  }

  return {
    events, stats, loading, acting, reindexing,
    page, pageSize, total, totalPages,
    fStatus, fCustomerId, fEventId,
    load, loadStats, search, resetFilter,
    retry, replay, discard, reindex,
  }
})
