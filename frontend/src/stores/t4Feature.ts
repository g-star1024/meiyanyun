// ============================================================
// T4 AI 中台底座 - 特征平台 store
// 特征注册 / 在线离线 / 血缘 DAG / 发布下线
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

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

export const useT4FeatureStore = defineStore('t4Feature', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const features = ref<Feature[]>([])
  const lineage = ref<FeatureLineage>({ nodes: [], edges: [] })
  const loaded = ref(false)

  // ---- 查询 ----
  const kpi = computed(() => ({
    total: features.value.length,
    published: features.value.filter((f) => f.status === 'PUBLISHED').length,
    online: features.value.filter((f) => f.onlineServing).length,
    totalCalls: features.value.reduce((s, f) => s + f.callCount30d, 0),
  }))

  function getFeature(id: string) {
    return features.value.find((f) => f.id === id)
  }

  function can(perm: string) {
    return auth.can(perm)
  }

  // ---- 命令 ----
  function registerFeature(input: {
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
  }): Feature {
    if (!auth.can('feature:register')) throw new Error('无特征注册权限')
    const now = new Date().toISOString()
    const f: Feature = {
      id: nextId('feat'),
      name: input.name,
      group: input.group,
      type: input.type,
      valueType: input.valueType,
      description: input.description,
      source: input.source,
      status: 'REGISTERED',
      owner: input.owner,
      onlineServing: input.onlineServing,
      ttl: input.ttl,
      callCount30d: 0,
      freshness: input.freshness,
      version: '1.0.0',
      createdAt: now,
      updatedAt: now,
    }
    features.value.unshift(f)
    // 同步血缘
    lineage.value.nodes.push({ id: f.id, name: f.name, type: 'FEATURE' })
    const srcId = `src-${input.source}`
    if (!lineage.value.nodes.find((n) => n.id === srcId)) {
      lineage.value.nodes.unshift({ id: srcId, name: input.source, type: 'SOURCE' })
    }
    lineage.value.edges.push({ from: srcId, to: f.id })
    activity.log(auth.user.name, `注册特征「${f.name}」（${input.group} / ${FEATURE_VALUE_LABEL[input.valueType]}）`, f.id)
    return f
  }

  function publishFeature(id: string) {
    if (!auth.can('feature:publish')) throw new Error('无特征发布权限')
    const f = getFeature(id)
    if (!f || f.status !== 'REGISTERED') return
    f.status = 'PUBLISHED'
    f.updatedAt = new Date().toISOString()
    activity.log(auth.user.name, `发布特征「${f.name}」至线上`, id)
  }

  function deprecateFeature(id: string) {
    if (!auth.can('feature:publish')) throw new Error('无特征下线权限')
    const f = getFeature(id)
    if (!f) return
    f.status = 'DEPRECATED'
    f.onlineServing = false
    f.updatedAt = new Date().toISOString()
    activity.log(auth.user.name, `下线特征「${f.name}」`, id)
  }

  function toggleOnline(id: string, online: boolean) {
    if (!auth.can('feature:edit')) throw new Error('无特征编辑权限')
    const f = getFeature(id)
    if (!f) return
    f.onlineServing = online
    f.updatedAt = new Date().toISOString()
    activity.log(auth.user.name, `特征「${f.name}」在线服务${online ? '启用' : '停用'}`, id)
  }

  // ---- 种子 ----
  function seed() {
    if (loaded.value) return
    loaded.value = true
    const now = Date.now()
    const daysAgo = (d: number) => new Date(now - d * 86400_000).toISOString()

    const seed: Array<Partial<Feature> & Pick<Feature, 'name' | 'group' | 'type' | 'valueType' | 'status' | 'source' | 'owner'>> = [
      { name: 'cust_recency_days', group: '客户RFM', type: 'ONLINE', valueType: 'INT', status: 'PUBLISHED', source: 'dwd_customer_visit', owner: '李明', description: '客户最近一次到店距今天数', onlineServing: true, ttl: '30天', callCount30d: 186420, freshness: 'T+0 小时级', version: '2.1.0' },
      { name: 'cust_frequency_90d', group: '客户RFM', type: 'ONLINE', valueType: 'INT', status: 'PUBLISHED', source: 'dwd_customer_order', owner: '李明', description: '近 90 天消费/到店次数', onlineServing: true, ttl: '90天', callCount30d: 186420, freshness: 'T+0 小时级', version: '2.1.0' },
      { name: 'cust_monetary_90d', group: '客户RFM', type: 'ONLINE', valueType: 'FLOAT', status: 'PUBLISHED', source: 'dwd_customer_order', owner: '李明', description: '近 90 天累计消费金额', onlineServing: true, ttl: '90天', callCount30d: 182300, freshness: 'T+0 小时级', version: '2.1.0' },
      { name: 'skin_concern_embedding', group: '皮肤影像', type: 'ONLINE', valueType: 'VECTOR', status: 'PUBLISHED', source: 'dwd_skin_image', owner: '张医生', description: '面诊皮肤问题向量（512 维）', onlineServing: true, ttl: '180天', callCount30d: 42180, freshness: 'T+0 实时', version: '1.5.0' },
      { name: 'skin_acne_score', group: '皮肤影像', type: 'ONLINE', valueType: 'FLOAT', status: 'PUBLISHED', source: 'dwd_skin_image', owner: '张医生', description: '痤疮严重度评分 0-1', onlineServing: true, callCount30d: 40120, freshness: 'T+0 实时', version: '1.5.0' },
      { name: 'intent_embedding', group: 'NLP特征', type: 'ONLINE', valueType: 'VECTOR', status: 'REGISTERED', source: 'dwd_chat_message', owner: '陈晓', description: '客户对话意图向量（768 维）', onlineServing: false, callCount30d: 0, freshness: 'T+1 天级', version: '0.9.0' },
      { name: 'intent_topic', group: 'NLP特征', type: 'OFFLINE', valueType: 'STRING', status: 'PUBLISHED', source: 'dwd_chat_message', owner: '陈晓', description: '咨询主题分类（预约/退款/投诉/咨询/复购）', onlineServing: false, callCount30d: 8200, freshness: 'T+1 天级', version: '1.2.0' },
      { name: 'item_popularity_score', group: '商品推荐', type: 'ONLINE', valueType: 'FLOAT', status: 'PUBLISHED', source: 'dws_item_stats', owner: '王悦', description: '项目热度分（近 30 天销量+点击+收藏加权）', onlineServing: true, ttl: '7天', callCount30d: 96500, freshness: 'T+0 小时级', version: '3.0.1' },
      { name: 'cust_segment_label', group: '客户画像', type: 'OFFLINE', valueType: 'STRING', status: 'PUBLISHED', source: 'ads_customer_segment', owner: '赵磊', description: '客户分群标签（高价值/潜力/沉睡/流失）', onlineServing: false, callCount30d: 124000, freshness: 'T+1 天级', version: '4.2.0' },
      { name: 'cust_ltv_pred', group: '客户画像', type: 'OFFLINE', valueType: 'FLOAT', status: 'REGISTERED', source: 'ads_customer_ltv', owner: '赵磊', description: '未来 12 个月预测 LTV', onlineServing: false, callCount30d: 0, freshness: 'T+7 周级', version: '0.3.0' },
      { name: 'store_daily_sales', group: '门店运营', type: 'OFFLINE', valueType: 'FLOAT', status: 'PUBLISHED', source: 'dws_store_daily', owner: '赵磊', description: '门店日销售额', onlineServing: false, callCount30d: 9820, freshness: 'T+1 天级', version: '1.2.0' },
      { name: 'coupon_sensitivity', group: '营销偏好', type: 'ONLINE', valueType: 'FLOAT', status: 'DRAFT', source: 'dwd_coupon_usage', owner: '孙琳', description: '客户对优惠券的敏感度 0-1', onlineServing: false, callCount30d: 0, freshness: 'T+1 天级', version: '0.1.0' },
      { name: 'is_vip_customer', group: '客户画像', type: 'ONLINE', valueType: 'BOOL', status: 'PUBLISHED', source: 'dim_customer', owner: '李明', description: '是否 VIP 客户', onlineServing: true, callCount30d: 210000, freshness: 'T+0 实时', version: '1.0.0' },
      { name: 'visit_channel_prefer', group: '营销偏好', type: 'OFFLINE', valueType: 'STRING', status: 'DEPRECATED', source: 'dws_channel_stats', owner: '孙琳', description: '到访渠道偏好（已被新标签替代）', onlineServing: false, callCount30d: 0, freshness: 'T+7', version: '1.0.0' },
    ]

    seed.forEach((s) => {
      features.value.push({
        id: nextId('feat'),
        name: s.name!,
        group: s.group!,
        type: s.type!,
        valueType: s.valueType!,
        description: s.description ?? '',
        source: s.source!,
        status: s.status!,
        owner: s.owner!,
        onlineServing: s.onlineServing ?? false,
        ttl: s.ttl,
        callCount30d: s.callCount30d ?? 0,
        freshness: s.freshness ?? 'T+1',
        version: s.version ?? '1.0.0',
        createdAt: s.createdAt ?? daysAgo(60),
        updatedAt: s.updatedAt ?? daysAgo(2),
      })
    })

    // 血缘：数据源 → 特征 → 模型/服务
    const nodes: LineageNode[] = [
      { id: 'src-visit', name: 'dwd_customer_visit', type: 'SOURCE' },
      { id: 'src-order', name: 'dwd_customer_order', type: 'SOURCE' },
      { id: 'src-image', name: 'dwd_skin_image', type: 'SOURCE' },
      { id: 'src-chat', name: 'dwd_chat_message', type: 'SOURCE' },
      { id: 'src-item', name: 'dws_item_stats', type: 'SOURCE' },
      { id: 'src-seg', name: 'ads_customer_segment', type: 'SOURCE' },
    ]
    const fmap = new Map<string, string>()
    features.value.forEach((f) => fmap.set(f.name, f.id))
    features.value.forEach((f) => {
      nodes.push({ id: f.id, name: f.name, type: 'FEATURE' })
    })
    // 模型 & 服务节点
    nodes.push(
      { id: 'mdl-churn', name: '客户流失预测 v3', type: 'MODEL' },
      { id: 'mdl-skin', name: '皮肤影像分类', type: 'MODEL' },
      { id: 'mdl-rec', name: '疗程推荐 DeepFM', type: 'MODEL' },
      { id: 'svc-guide', name: '导购侧栏推荐服务', type: 'SERVICE' },
      { id: 'svc-alert', name: '流失预警推送服务', type: 'SERVICE' },
    )
    const edges: LineageEdge[] = [
      { from: 'src-visit', to: fmap.get('cust_recency_days')! },
      { from: 'src-order', to: fmap.get('cust_frequency_90d')! },
      { from: 'src-order', to: fmap.get('cust_monetary_90d')! },
      { from: 'src-image', to: fmap.get('skin_concern_embedding')! },
      { from: 'src-image', to: fmap.get('skin_acne_score')! },
      { from: 'src-chat', to: fmap.get('intent_embedding')! },
      { from: 'src-chat', to: fmap.get('intent_topic')! },
      { from: 'src-item', to: fmap.get('item_popularity_score')! },
      { from: 'src-seg', to: fmap.get('cust_segment_label')! },
      { from: fmap.get('cust_recency_days')!, to: 'mdl-churn' },
      { from: fmap.get('cust_frequency_90d')!, to: 'mdl-churn' },
      { from: fmap.get('cust_monetary_90d')!, to: 'mdl-churn' },
      { from: fmap.get('cust_segment_label')!, to: 'mdl-churn' },
      { from: fmap.get('skin_concern_embedding')!, to: 'mdl-skin' },
      { from: fmap.get('skin_acne_score')!, to: 'mdl-skin' },
      { from: fmap.get('item_popularity_score')!, to: 'mdl-rec' },
      { from: fmap.get('cust_monetary_90d')!, to: 'mdl-rec' },
      { from: 'mdl-churn', to: 'svc-alert' },
      { from: 'mdl-rec', to: 'svc-guide' },
      { from: 'mdl-skin', to: 'svc-guide' },
    ]
    lineage.value = { nodes, edges }
  }

  return {
    features, lineage, kpi,
    FEATURE_TYPE_LABEL, FEATURE_VALUE_LABEL, FEATURE_STATUS_LABEL,
    getFeature, can,
    registerFeature, publishFeature, deprecateFeature, toggleOnline,
    seed,
  }
})
