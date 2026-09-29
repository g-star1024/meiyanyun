// ============================================================
// T4 AI 中台底座 - 特征平台 store
// 特征注册 / 在线离线 / 血缘 DAG / 发布下线
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { errMsg } from './m5Coupon'
import { useToast } from '@/composables/useToast'
import * as api from '@/api/t4Feature'

export type FeatureType = 'ONLINE' | 'OFFLINE'
export type FeatureValueType = 'INT' | 'FLOAT' | 'STRING' | 'VECTOR' | 'BOOL'
export type FeatureStatus = 'DRAFT' | 'REGISTERED' | 'PUBLISHED' | 'DEPRECATED'

export interface Feature {
  id: string
  name: string
  group: string
  type: FeatureType
  valueType: FeatureValueType
  description: string
  source: string
  status: FeatureStatus
  owner: string
  onlineServing: boolean
  ttl?: string
  callCount30d: number
  freshness: string
  version: string
  createdAt: string
  updatedAt: string
}

export interface LineageNode {
  id: string
  name: string
  type: 'SOURCE' | 'FEATURE' | 'MODEL' | 'SERVICE'
}
export interface LineageEdge {
  from: string
  to: string
}
export interface FeatureLineage {
  nodes: LineageNode[]
  edges: LineageEdge[]
}

export const FEATURE_TYPE_LABEL: Record<FeatureType, string> = {
  ONLINE: '在线',
  OFFLINE: '离线',
}
export const FEATURE_VALUE_LABEL: Record<FeatureValueType, string> = {
  INT: '整数',
  FLOAT: '浮点',
  STRING: '字符串',
  VECTOR: '向量',
  BOOL: '布尔',
}
export const FEATURE_STATUS_LABEL: Record<FeatureStatus, string> = {
  DRAFT: '草稿',
  REGISTERED: '已注册',
  PUBLISHED: '已发布',
  DEPRECATED: '已下线',
}

// —— 铁律 -1-B：前后端差异只在此消化；id = 后端 code；ttl null→undefined ——
function mapFeature(v: api.FeatureView): Feature {
  return {
    id: v.code,
    name: v.name,
    group: v.group,
    type: v.type as FeatureType,
    valueType: v.valueType as FeatureValueType,
    description: v.description,
    source: v.source,
    status: v.status as FeatureStatus,
    owner: v.owner,
    onlineServing: v.onlineServing,
    ttl: v.ttl ?? undefined,
    callCount30d: Number(v.callCount30d),
    freshness: v.freshness,
    version: v.version,
    createdAt: v.createdAt,
    updatedAt: v.updatedAt,
  }
}

export const useT4FeatureStore = defineStore('t4Feature', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()
  const toast = useToast()

  const features = ref<Feature[]>([])
  const lineage = ref<FeatureLineage>({ nodes: [], edges: [] })
  const loaded = ref(false)
  const loading = ref(false)
  const loadError = ref('')

  // ---- 查询 ----
  const kpi = computed(() => ({
    total: features.value.length,
    published: features.value.filter((f) => f.status === 'PUBLISHED').length,
    online: features.value.filter((f) => f.onlineServing).length,
    totalCalls: features.value.reduce((s, f) => s + f.callCount30d, 0),
  }))

  async function load() {
    if (loading.value) return
    loading.value = true
    loadError.value = ''
    try {
      const data = await api.overview()
      features.value = data.features.map(mapFeature)
      lineage.value = {
        nodes: data.lineage.nodes.map((n) => ({ id: n.id, name: n.name, type: n.type as LineageNode['type'] })),
        edges: data.lineage.edges.map((e) => ({ from: e.from, to: e.to })),
      }
      loaded.value = true
    } catch (e) {
      loadError.value = errMsg(e, '特征数据加载失败')
    } finally {
      loading.value = false
    }
  }

  function getFeature(id: string) {
    return features.value.find((f) => f.id === id)
  }

  function can(perm: string) {
    return auth.can(perm)
  }

  // ---- 命令 ----
  async function registerFeature(input: {
    name: string
    group: string
    type: FeatureType
    valueType: FeatureValueType
    description: string
    source: string
    owner: string
    onlineServing: boolean
    ttl?: string
    freshness: string
  }): Promise<Feature | null> {
    if (!can('feature:register')) {
      toast.error('无操作权限：需要 feature:register')
      return null
    }
    try {
      const created = await api.register({
        name: input.name,
        group: input.group,
        type: input.type,
        valueType: input.valueType,
        description: input.description,
        source: input.source,
        owner: input.owner,
        onlineServing: input.onlineServing,
        ttl: input.ttl,
        freshness: input.freshness,
      })
      const f = mapFeature(created)
      features.value.unshift(f)
      // 血缘本地同步（与后端一致）：特征节点 + 来源节点（幂等）+ 边
      if (!lineage.value.nodes.some((n) => n.id === f.id)) {
        lineage.value.nodes.push({ id: f.id, name: f.name, type: 'FEATURE' })
        const srcId = `src-${f.source}`
        if (!lineage.value.nodes.some((n) => n.id === srcId)) {
          lineage.value.nodes.unshift({ id: srcId, name: f.source, type: 'SOURCE' })
        }
        lineage.value.edges.push({ from: srcId, to: f.id })
      }
      activity.log(auth.user.name, `注册特征「${f.name}」（${f.group} / ${FEATURE_VALUE_LABEL[f.valueType]}）`, f.id)
      toast.success(`特征「${f.name}」已注册（${f.id}）`)
      return f
    } catch (e) {
      toast.error(errMsg(e, '特征注册失败'))
      return null
    }
  }

  async function publishFeature(id: string) {
    if (!can('feature:publish')) {
      toast.error('无操作权限：需要 feature:publish')
      return
    }
    const f = getFeature(id)
    if (!f) return
    try {
      const next = await api.publish(id)
      Object.assign(f, mapFeature(next))
      activity.log(auth.user.name, `发布特征「${f.name}」至线上`, id)
      toast.success(`特征「${f.name}」已发布`)
    } catch (e) {
      toast.error(errMsg(e, '发布失败'))
    }
  }

  async function deprecateFeature(id: string) {
    if (!can('feature:publish')) {
      toast.error('无操作权限：需要 feature:publish')
      return
    }
    const f = getFeature(id)
    if (!f) return
    try {
      const next = await api.deprecate(id)
      Object.assign(f, mapFeature(next))
      activity.log(auth.user.name, `下线特征「${f.name}」`, id)
      toast.success(`特征「${f.name}」已下线`)
    } catch (e) {
      toast.error(errMsg(e, '下线失败'))
    }
  }

  async function toggleOnline(id: string, online: boolean) {
    if (!can('feature:edit')) {
      toast.error('无操作权限：需要 feature:edit')
      return
    }
    const f = getFeature(id)
    if (!f) return
    try {
      const next = await api.updateServing(id, online)
      Object.assign(f, mapFeature(next))
      activity.log(auth.user.name, `特征「${f.name}」在线服务${online ? '启用' : '停用'}`, id)
      toast.success(`特征「${f.name}」在线服务已${online ? '启用' : '停用'}`)
    } catch (e) {
      toast.error(errMsg(e, '操作失败'))
    }
  }

  // ---- 种子（切真：拉取后端概览） ----
  async function seed() {
    await load()
  }

  return {
    features, lineage, kpi,
    FEATURE_TYPE_LABEL, FEATURE_VALUE_LABEL, FEATURE_STATUS_LABEL,
    getFeature, can,
    registerFeature, publishFeature, deprecateFeature, toggleOnline,
    seed,
  }
})
