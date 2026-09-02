// ============================================================
// T2-03 标签工厂 store
// SQL/规则加工 → 发布 → 供 M3-06（tag store）/ A1 消费
// 敏感标签需 T3-01 审批；发布后同步到 M3-06 tag store
// 对齐 T-G-中台与通用.md T2-03 详设
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

// ---- 类型 ----
export type TagFactoryType = 'SQL' | 'RULE' | 'ML'
export type TagFactoryStatus = 'DRAFT' | 'PROCESSING' | 'PUBLISHED' | 'OFFLINE' | 'PENDING_APPROVAL'
export type TagSensitivity = 'PUBLIC' | 'INTERNAL' | 'SENSITIVE'
export type ValueType = 'ENUM' | 'NUMBER' | 'BOOLEAN' | 'DATE'

export interface TagVersion {
  version: string
  sql: string
  publishedAt: string | null
  publishedBy: string | null
  coverCount: number
}

export interface TagConsumer {
  module: string
  scene: string
  usedAt: string
}

export interface FactoryTag {
  id: string
  code: string
  name: string
  category: string
  type: TagFactoryType
  sensitivity: TagSensitivity
  valueType: ValueType
  description: string
  sql: string
  status: TagFactoryStatus
  coverCount: number
  /** 刷新频率 */
  refreshCron: string
  lastComputeAt: string | null
  versions: TagVersion[]
  consumers: TagConsumer[]
  owner: string
  tags: string[]
  createdAt: string
  updatedAt: string
}

const TYPE_LABEL: Record<TagFactoryType, string> = {
  SQL: 'SQL 加工',
  RULE: '规则加工',
  ML: 'ML 模型',
}

const STATUS_LABEL: Record<TagFactoryStatus, string> = {
  DRAFT: '草稿',
  PROCESSING: '计算中',
  PUBLISHED: '已发布',
  OFFLINE: '已下线',
  PENDING_APPROVAL: '待审批',
}

const SENSITIVITY_LABEL: Record<TagSensitivity, string> = {
  PUBLIC: '公开',
  INTERNAL: '内部',
  SENSITIVE: '敏感',
}

export const useT2TagFactoryStore = defineStore('t2TagFactory', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const tags = ref<FactoryTag[]>([])
  const loaded = ref(false)
  const filterStatus = ref<TagFactoryStatus | 'ALL'>('ALL')
  const filterType = ref<TagFactoryType | 'ALL'>('ALL')
  const keyword = ref('')

  // ---- 查询 ----
  const publishedTags = computed(() => tags.value.filter((t) => t.status === 'PUBLISHED'))

  const filtered = computed(() => {
    let list = tags.value
    if (filterStatus.value !== 'ALL') list = list.filter((t) => t.status === filterStatus.value)
    if (filterType.value !== 'ALL') list = list.filter((t) => t.type === filterType.value)
    if (keyword.value.trim()) {
      const kw = keyword.value.toLowerCase()
      list = list.filter((t) => t.name.toLowerCase().includes(kw) || t.code.toLowerCase().includes(kw))
    }
    return list
  })

  const kpi = computed(() => ({
    total: tags.value.length,
    published: publishedTags.value.length,
    draft: tags.value.filter((t) => t.status === 'DRAFT').length,
    pending: tags.value.filter((t) => t.status === 'PENDING_APPROVAL').length,
    totalCover: publishedTags.value.reduce((s, t) => s + t.coverCount, 0),
    totalConsumers: tags.value.reduce((s, t) => s + t.consumers.length, 0),
  }))

  function get(id: string) {
    return tags.value.find((t) => t.id === id)
  }

  function canEdit() { return auth.can('tagFactory:edit') }
  function canPublish() { return auth.can('tagFactory:publish') }
  function canApprove() { return auth.can('tagFactory:approve') }

  // ---- 命令 ----
  function createTag(input: {
    code: string; name: string; category: string; type: TagFactoryType
    sensitivity: TagSensitivity; valueType: ValueType; description: string
    sql: string; refreshCron: string; tags?: string[]
  }): FactoryTag {
    if (!auth.can('tagFactory:create')) throw new Error('无标签创建权限')
    const now = new Date().toISOString()
    const t: FactoryTag = {
      id: nextId('ftag'),
      code: input.code,
      name: input.name,
      category: input.category,
      type: input.type,
      sensitivity: input.sensitivity,
      valueType: input.valueType,
      description: input.description,
      sql: input.sql,
      status: 'DRAFT',
      coverCount: 0,
      refreshCron: input.refreshCron,
      lastComputeAt: null,
      versions: [],
      consumers: [],
      owner: auth.user.name,
      tags: input.tags || [],
      createdAt: now,
      updatedAt: now,
    }
    tags.value.unshift(t)
    activity.log(auth.user.name, `创建标签「${t.name}」（${TYPE_LABEL[t.type]}）`, t.id)
    return t
  }

  function updateTag(id: string, patch: Partial<Pick<FactoryTag, 'name' | 'description' | 'sql' | 'sensitivity' | 'refreshCron' | 'category' | 'tags'>>) {
    if (!auth.can('tagFactory:edit')) throw new Error('无标签编辑权限')
    const t = get(id)
    if (!t) return
    Object.assign(t, patch, { updatedAt: new Date().toISOString() })
    activity.log(auth.user.name, `编辑标签「${t.name}」`, id)
  }

  /** 试算（模拟运行 SQL/规则，返回覆盖人数） */
  function previewCompute(id: string): number {
    const t = get(id)
    if (!t) return 0
    // 模拟：根据 SQL 长度/类型生成一个合理的覆盖人数
    const base = t.type === 'SQL' ? 2000 : t.type === 'RULE' ? 1500 : 3000
    const count = base + Math.floor(Math.random() * base * 0.5)
    activity.log(auth.user.name, `试算标签「${t.name}」，预估覆盖 ${count} 人`, id)
    return count
  }

  /**
   * 发布标签：
   * - 敏感标签 → PENDING_APPROVAL（需 T3-01 审批）
   * - 非敏感标签 → 直接 PUBLISHED
   * 发布后生成新版本，并同步到 M3-06 tag store
   */
  function publishTag(id: string): boolean {
    if (!auth.can('tagFactory:publish')) throw new Error('无标签发布权限')
    const t = get(id)
    if (!t || (t.status !== 'DRAFT' && t.status !== 'OFFLINE')) return false

    if (t.sensitivity === 'SENSITIVE') {
      t.status = 'PENDING_APPROVAL'
      t.updatedAt = new Date().toISOString()
      activity.log(auth.user.name, `敏感标签「${t.name}」提交审批`, id)
      return true
    }

    doPublish(t)
    return true
  }

  /** 审批通过后发布（敏感标签） */
  function approvePublish(id: string) {
    if (!auth.can('tagFactory:approve')) throw new Error('无标签审批权限')
    const t = get(id)
    if (!t || t.status !== 'PENDING_APPROVAL') return
    doPublish(t)
    activity.log(auth.user.name, `审批通过标签「${t.name}」，已发布`, id)
  }

  function doPublish(t: FactoryTag) {
    const now = new Date().toISOString()
    const version = `v${(t.versions.length + 1).toFixed(1)}`
    const coverCount = t.type === 'SQL' ? 2000 + Math.floor(Math.random() * 1000)
      : t.type === 'RULE' ? 1500 + Math.floor(Math.random() * 800)
      : 3000 + Math.floor(Math.random() * 1500)

    t.versions.push({
      version,
      sql: t.sql,
      publishedAt: now,
      publishedBy: auth.user.name,
      coverCount,
    })
    t.status = 'PUBLISHED'
    t.coverCount = coverCount
    t.lastComputeAt = now
    t.updatedAt = now

    // 同步到 M3-06 tag store（动态导入避免循环依赖）
    syncToM3(t)
  }

  /** 同步标签到 M3-06 标签体系（tag store） */
  function syncToM3(t: FactoryTag) {
    try {
      // 动态导入避免循环依赖
      import('./tag').then(({ useTagStore }) => {
        const tagStore = useTagStore()
        const existing = tagStore.tags.find((x) => x.name === t.name)
        if (!existing) {
          tagStore.createTag({
            name: t.name,
            category: t.type === 'RULE' ? 'BEHAVIOR' : 'SYSTEM',
            color: t.sensitivity === 'SENSITIVE' ? '#EF4444' : '#8B5CF6',
            rule: t.sql.slice(0, 60) + (t.sql.length > 60 ? '...' : ''),
          })
        }
      })
    } catch {
      // tag store 未挂载时忽略
    }
  }

  function offlineTag(id: string) {
    if (!auth.can('tagFactory:edit')) throw new Error('无标签编辑权限')
    const t = get(id)
    if (!t || t.status !== 'PUBLISHED') return
    t.status = 'OFFLINE'
    t.updatedAt = new Date().toISOString()
    activity.log(auth.user.name, `下线标签「${t.name}」`, id)
  }

  function deleteTag(id: string) {
    if (!auth.can('tagFactory:edit')) throw new Error('无标签编辑权限')
    const t = get(id)
    if (!t || t.status === 'PUBLISHED') throw new Error('已发布标签不可删除，请先下线')
    tags.value = tags.value.filter((x) => x.id !== id)
    activity.log(auth.user.name, `删除标签「${t.name}」`, id)
  }

  // ---- 种子 ----
  function seed() {
    if (loaded.value) return
    loaded.value = true
    const now = Date.now()
    const daysAgo = (d: number) => new Date(now - d * 86400000).toISOString()

    const seedTags: Array<Partial<FactoryTag> & Pick<FactoryTag, 'code' | 'name' | 'category' | 'type' | 'sensitivity' | 'valueType' | 'description' | 'sql' | 'status' | 'refreshCron' | 'owner'>> = [
      {
        code: 'TAG_HIGH_VALUE', name: '高价值客户', category: '客户价值', type: 'SQL', sensitivity: 'PUBLIC',
        valueType: 'ENUM', description: '累计消费 ≥ 50,000 元的客户', refreshCron: '每日 02:00', owner: '张数',
        sql: "SELECT customer_id FROM orders WHERE status='PAID' GROUP BY customer_id HAVING SUM(amount) >= 50000",
        status: 'PUBLISHED', coverCount: 1842,
      },
      {
        code: 'TAG_CHURN_RISK', name: '流失风险客户', category: '风险预警', type: 'RULE', sensitivity: 'INTERNAL',
        valueType: 'BOOLEAN', description: '180天未到店且历史消费 > 10,000', refreshCron: '每日 03:00', owner: '张数',
        sql: "last_visit_days > 180 AND total_paid > 10000",
        status: 'PUBLISHED', coverCount: 412,
      },
      {
        code: 'TAG_PRICE_SENSITIVE', name: '价格敏感型', category: '消费偏好', type: 'RULE', sensitivity: 'PUBLIC',
        valueType: 'ENUM', description: '只在折扣期下单 ≥ 3 次', refreshCron: '每周一 02:00', owner: '李析',
        sql: "discount_order_count >= 3 AND discount_order_ratio > 0.6",
        status: 'PUBLISHED', coverCount: 2134,
      },
      {
        code: 'TAG_PROJECT_PREFER_LASER', name: '项目偏好-光电类', category: '消费偏好', type: 'SQL', sensitivity: 'PUBLIC',
        valueType: 'NUMBER', description: '光电类项目消费占比 > 60%', refreshCron: '每日 02:30', owner: '张数',
        sql: "SELECT customer_id, laser_spend/total_spend AS ratio FROM ...",
        status: 'PUBLISHED', coverCount: 1320,
      },
      {
        code: 'TAG_SLEEP_CUSTOMER', name: '沉睡客户', category: '活跃度', type: 'RULE', sensitivity: 'PUBLIC',
        valueType: 'BOOLEAN', description: '90 天未到店', refreshCron: '每日 01:00', owner: '张数',
        sql: "last_visit_days > 90",
        status: 'PUBLISHED', coverCount: 986,
      },
      {
        code: 'TAG_MEDICAL_AESTHETICS', name: '医美消费倾向', category: 'AI 预测', type: 'ML', sensitivity: 'INTERNAL',
        valueType: 'NUMBER', description: '基于浏览/咨询/消费行为预测医美消费倾向（0-100）', refreshCron: '每周一 04:00', owner: 'AI 模型组',
        sql: "PREDICT(aesthetic_propensity) USING model 'aesthetic_v2'",
        status: 'PUBLISHED', coverCount: 3200,
      },
      {
        code: 'TAG_NPS_PREDICT', name: 'NPS 预测低分', category: 'AI 预测', type: 'ML', sensitivity: 'SENSITIVE',
        valueType: 'NUMBER', description: '预测 NPS ≤ 6 的客户（敏感，需审批）', refreshCron: '每周一 05:00', owner: 'AI 模型组',
        sql: "PREDICT(nps_score) USING model 'nps_v1' WHERE nps_score <= 6",
        status: 'PENDING_APPROVAL', coverCount: 0,
      },
      {
        code: 'TAG_REPURCHASE_CYCLE', name: '复购周期标签', category: '消费行为', type: 'SQL', sensitivity: 'PUBLIC',
        valueType: 'NUMBER', description: '客户平均复购间隔天数', refreshCron: '每日 03:30', owner: '李析',
        sql: "SELECT customer_id, AVG(days_between_orders) FROM ...",
        status: 'DRAFT', coverCount: 0,
      },
      {
        code: 'TAG_REFERRAL_HIGH', name: '高转介绍价值', category: '客户价值', type: 'RULE', sensitivity: 'PUBLIC',
        valueType: 'BOOLEAN', description: '推荐 ≥ 3 人且推荐成交率 > 50%', refreshCron: '每周一 02:00', owner: '张数',
        sql: "referral_count >= 3 AND referral_conversion > 0.5",
        status: 'PUBLISHED', coverCount: 215,
      },
      {
        code: 'TAG_INCOME_ESTIMATE', name: '收入水平估计', category: 'AI 预测', type: 'ML', sensitivity: 'SENSITIVE',
        valueType: 'ENUM', description: '基于消费行为预估客户收入区间（敏感数据）', refreshCron: '每月 1 日', owner: 'AI 模型组',
        sql: "PREDICT(income_range) USING model 'income_v1'",
        status: 'OFFLINE', coverCount: 0,
      },
    ]

    seedTags.forEach((s, i) => {
      const id = nextId('ftag')
      const t: FactoryTag = {
        id,
        code: s.code!,
        name: s.name!,
        category: s.category!,
        type: s.type!,
        sensitivity: s.sensitivity!,
        valueType: s.valueType!,
        description: s.description!,
        sql: s.sql!,
        status: s.status!,
        coverCount: s.coverCount ?? 0,
        refreshCron: s.refreshCron!,
        lastComputeAt: s.status === 'PUBLISHED' ? daysAgo(i % 3) : null,
        versions: s.status === 'PUBLISHED' ? [
          { version: 'v1.0', sql: s.sql!, publishedAt: daysAgo(30 - i), publishedBy: s.owner!, coverCount: s.coverCount ?? 0 },
        ] : [],
        consumers: [],
        owner: s.owner!,
        tags: [],
        createdAt: daysAgo(30 - i),
        updatedAt: daysAgo(i % 5),
      }
      tags.value.push(t)
    })

    // 消费方关联（M3-06 / A1 等）
    const consumerMap: Record<string, TagConsumer[]> = {
      [tags.value[0].id]: [
        { module: 'M3-06 标签体系', scene: '客户分群', usedAt: daysAgo(1) },
        { module: 'M3-12 流失预警', scene: '高价值流失拦截', usedAt: daysAgo(2) },
        { module: 'A1-08 智能营销', scene: '高价值客户专属方案', usedAt: daysAgo(1) },
      ],
      [tags.value[1].id]: [
        { module: 'M3-12 流失预警', scene: '流失预警看板', usedAt: daysAgo(1) },
        { module: 'M3-09 生日节日关怀', scene: '流失客户挽回推送', usedAt: daysAgo(3) },
      ],
      [tags.value[2].id]: [
        { module: 'M5-01 优惠券管理', scene: '折扣券定向发放', usedAt: daysAgo(1) },
        { module: 'M5-02 短信企微推送', scene: '促销活动触达', usedAt: daysAgo(2) },
      ],
      [tags.value[5].id]: [
        { module: 'A1-08 智能营销', scene: '医美倾向客户推荐', usedAt: daysAgo(1) },
        { module: 'A1-05 客户画像', scene: 'AI 画像标签', usedAt: daysAgo(1) },
      ],
    }
    Object.entries(consumerMap).forEach(([id, consumers]) => {
      const t = tags.value.find((x) => x.id === id)
      if (t) t.consumers = consumers
    })
  }

  return {
    tags, filterStatus, filterType, keyword,
    publishedTags, filtered, kpi,
    TYPE_LABEL, STATUS_LABEL, SENSITIVITY_LABEL,
    get, canEdit, canPublish, canApprove,
    createTag, updateTag, previewCompute, publishTag, approvePublish,
    offlineTag, deleteTag, seed,
  }
})
