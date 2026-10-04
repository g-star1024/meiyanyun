// ============================================================
// T2-01 数据源注册 store（棒⑥卡7 接真 customer-service）
// CDC/Kafka/三方数据源注册登记真源化；棒⑧卡5 三类型探测放开（接入装配位归 DESIGN-T3 §7）。
// 数据源：/api/customer/t2/datasources（V72 落库；查询=collect:view，
// 新建=collect:create，编辑/停用=collect:edit，连通探测=collect:sync）。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { useToast } from '@/composables/useToast'
import { errMsg } from './m5Coupon'
import * as api from '@/api/t2DataSource'
import type { DataSourceView } from '@/api/t2DataSource'

export type DataSourceType = 'CDC' | 'KAFKA' | 'THIRD_PARTY'
export type DataSourceStatus = 'REGISTERED' | 'CONNECTED' | 'DISABLED'

export interface DataSource extends Omit<DataSourceView, 'type' | 'status'> {
  type: DataSourceType
  status: DataSourceStatus
}

export const DS_TYPE_LABEL: Record<DataSourceType, string> = {
  CDC: 'CDC',
  KAFKA: 'Kafka',
  THIRD_PARTY: '三方 API',
}

export const DS_STATUS_LABEL: Record<DataSourceStatus, string> = {
  REGISTERED: '已注册',
  CONNECTED: '已连通',
  DISABLED: '已停用',
}

function mapSource(v: DataSourceView): DataSource {
  return { ...v, type: v.type as DataSourceType, status: v.status as DataSourceStatus }
}

export const useT2DataSourceStore = defineStore('t2DataSource', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()
  const toast = useToast()

  const sources = ref<DataSource[]>([])
  const loading = ref(false)
  const loaded = ref(false)
  const loadError = ref('')
  const syncingId = ref<number | null>(null)

  const activeCount = computed(() => sources.value.filter((s) => s.status !== 'DISABLED').length)

  function getSource(id: number) { return sources.value.find((s) => s.id === id) }

  function canCreate() { return auth.can('collect:create') }
  function canEdit() { return auth.can('collect:edit') }
  function canSync() { return auth.can('collect:sync') }

  function replaceSource(next: DataSource) {
    const idx = sources.value.findIndex((s) => s.id === next.id)
    if (idx >= 0) sources.value.splice(idx, 1, next)
    else sources.value.unshift(next)
  }

  // ---- 装载 ----
  async function load(type?: string, keyword?: string) {
    loading.value = true
    loadError.value = ''
    try {
      sources.value = (await api.fetchDataSources(type, keyword)).map(mapSource)
      loaded.value = true
    } catch (e) {
      loadError.value = errMsg(e)
      console.warn('[t2DataSource] 数据源列表加载失败', e)
    } finally {
      loading.value = false
    }
  }

  async function seed() {
    await load()
  }

  // ---- 命令 ----
  async function createSource(input: api.DataSourceCreateReq): Promise<DataSource | null> {
    if (!canCreate()) {
      toast.error('无数据源新建权限')
      return null
    }
    try {
      const d = mapSource(await api.createDataSource(input))
      sources.value.push(d)
      activity.log(auth.user.name, `注册数据源「${d.name}」（${DS_TYPE_LABEL[d.type]}）`, d.code)
      toast.success(`数据源「${d.name}」已注册`)
      return d
    } catch (e) {
      toast.error(errMsg(e, '注册失败'))
      return null
    }
  }

  async function updateSource(id: number, patch: api.DataSourceUpdateReq): Promise<boolean> {
    if (!canEdit()) {
      toast.error('无数据源编辑权限')
      return false
    }
    try {
      const d = mapSource(await api.updateDataSource(id, patch))
      replaceSource(d)
      activity.log(auth.user.name, `编辑数据源「${d.name}」`, d.code)
      toast.success(`数据源「${d.name}」已保存`)
      return true
    } catch (e) {
      toast.error(errMsg(e, '保存失败'))
      return false
    }
  }

  async function disableSource(id: number): Promise<boolean> {
    if (!canEdit()) {
      toast.error('无数据源编辑权限')
      return false
    }
    try {
      const d = mapSource(await api.disableDataSource(id))
      replaceSource(d)
      activity.log(auth.user.name, `停用数据源「${d.name}」`, d.code)
      toast.success(`数据源「${d.name}」已停用`)
      return true
    } catch (e) {
      toast.error(errMsg(e, '停用失败'))
      return false
    }
  }

  /** 连通探测：三类型均真实探测（棒⑧卡5 放开 CDC/KAFKA）；失败 toast 透出后端原文。 */
  async function syncSource(id: number): Promise<boolean> {
    if (!canSync()) {
      toast.error('无连通探测权限')
      return false
    }
    if (syncingId.value !== null) return false
    syncingId.value = id
    try {
      const d = mapSource(await api.syncDataSource(id))
      replaceSource(d)
      activity.log(auth.user.name, `连通探测数据源「${d.name}」成功`, d.code)
      toast.success(`数据源「${d.name}」连通探测成功`)
      return true
    } catch (e) {
      toast.error(errMsg(e, '连通探测失败'))
      return false
    } finally {
      syncingId.value = null
    }
  }

  return {
    sources, loading, loaded, loadError, syncingId,
    activeCount,
    DS_TYPE_LABEL, DS_STATUS_LABEL,
    getSource, canCreate, canEdit, canSync,
    load, seed,
    createSource, updateSource, disableSource, syncSource,
  }
})
