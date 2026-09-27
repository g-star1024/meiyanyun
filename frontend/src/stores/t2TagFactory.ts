// ============================================================
// T2-03 标签工厂 store（T2-B2 切真 customer-service）
// SQL/规则加工 → 发布 → 供 M3-06（tag store）/ A1 消费
// 敏感标签需 T3-01 审批；发布后由后端同事务同步 customer_tag（窄表 TG###）
// 数据源：/api/customer/t2/tagfactory（V67 落库；查询=tagFactory:view，
// 创建=tagFactory:create，编辑/下线/删除=tagFactory:edit，
// 发布=tagFactory:publish，审批=tagFactory:approve）。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { useToast } from '@/composables/useToast'
import { errMsg } from './m5Coupon'
import * as api from '@/api/t2TagFactory'
import type { FactoryTagView } from '@/api/t2TagFactory'

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
  id: number
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

function mapTag(v: FactoryTagView): FactoryTag {
  return {
    ...v,
    type: v.type as TagFactoryType,
    sensitivity: v.sensitivity as TagSensitivity,
    valueType: v.valueType as ValueType,
    status: v.status as TagFactoryStatus,
  }
}

export const useT2TagFactoryStore = defineStore('t2TagFactory', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()
  const toast = useToast()

  const tags = ref<FactoryTag[]>([])
  const loaded = ref(false)
  const loading = ref(false)
  const loadError = ref('')
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

  function get(id: number) {
    return tags.value.find((t) => t.id === id)
  }

  function canEdit() { return auth.can('tagFactory:edit') }
  function canPublish() { return auth.can('tagFactory:publish') }
  function canApprove() { return auth.can('tagFactory:approve') }

  function replaceTag(next: FactoryTag) {
    const idx = tags.value.findIndex((t) => t.id === next.id)
    if (idx >= 0) tags.value.splice(idx, 1, next)
    else tags.value.unshift(next)
  }

  // ---- 装载 ----
  async function load() {
    loading.value = true
    loadError.value = ''
    try {
      tags.value = (await api.fetchTags()).map(mapTag)
      loaded.value = true
    } catch (e) {
      loadError.value = errMsg(e)
      console.warn('[t2TagFactory] 标签工厂加载失败', e)
    } finally {
      loading.value = false
    }
  }

  /** 进页装载（B86 范式：每次进页重拉真实数据） */
  async function seed() {
    await load()
  }

  // ---- 命令 ----
  async function createTag(input: {
    code: string; name: string; category: string; type: TagFactoryType
    sensitivity: TagSensitivity; valueType: ValueType; description: string
    sql: string; refreshCron: string; tags?: string[]
  }): Promise<FactoryTag | null> {
    if (!auth.can('tagFactory:create')) {
      toast.error('无标签创建权限')
      return null
    }
    try {
      const t = mapTag(await api.createTag({ ...input }))
      tags.value.unshift(t)
      activity.log(auth.user.name, `创建标签「${t.name}」（${TYPE_LABEL[t.type]}）`, String(t.id))
      toast.success(`标签「${t.name}」已创建`)
      return t
    } catch (e) {
      toast.error(errMsg(e, '创建失败'))
      return null
    }
  }

  async function updateTag(id: number, patch: Partial<Pick<FactoryTag, 'name' | 'description' | 'sql' | 'sensitivity' | 'refreshCron' | 'category' | 'tags'>>): Promise<boolean> {
    if (!canEdit()) {
      toast.error('无标签编辑权限')
      return false
    }
    try {
      const t = mapTag(await api.updateTag(id, { ...patch }))
      replaceTag(t)
      activity.log(auth.user.name, `编辑标签「${t.name}」`, String(id))
      toast.success(`标签「${t.name}」已保存`)
      return true
    } catch (e) {
      toast.error(errMsg(e, '保存失败'))
      return false
    }
  }

  /** 试算（后端确定性预估，返回覆盖人数；失败返回 -1） */
  async function previewCompute(id: number): Promise<number> {
    const t = get(id)
    try {
      const { coverCount } = await api.previewCompute(id)
      activity.log(auth.user.name, `试算标签「${t?.name ?? id}」，预估覆盖 ${coverCount} 人`, String(id))
      return coverCount
    } catch (e) {
      toast.error(errMsg(e, '试算失败'))
      return -1
    }
  }

  /**
   * 发布标签：
   * - 敏感标签 → PENDING_APPROVAL（需 T3-01 审批）
   * - 非敏感标签 → 直接 PUBLISHED
   * 发布后后端生成新版本，并同事务同步 customer_tag（M3-06）
   */
  async function publishTag(id: number): Promise<boolean> {
    if (!canPublish()) {
      toast.error('无标签发布权限')
      return false
    }
    try {
      const t = mapTag(await api.publishTag(id))
      replaceTag(t)
      if (t.status === 'PENDING_APPROVAL') {
        activity.log(auth.user.name, `敏感标签「${t.name}」提交审批`, String(id))
        toast.success(`敏感标签「${t.name}」已提交审批`)
      } else {
        activity.log(auth.user.name, `发布标签「${t.name}」`, String(id))
        toast.success(`标签「${t.name}」已发布，覆盖 ${t.coverCount.toLocaleString()} 人`)
      }
      return true
    } catch (e) {
      toast.error(errMsg(e, '发布失败'))
      return false
    }
  }

  /** 审批通过后发布（敏感标签） */
  async function approvePublish(id: number): Promise<boolean> {
    if (!canApprove()) {
      toast.error('无标签审批权限')
      return false
    }
    try {
      const t = mapTag(await api.approvePublish(id))
      replaceTag(t)
      activity.log(auth.user.name, `审批通过标签「${t.name}」，已发布`, String(id))
      toast.success(`标签「${t.name}」审批通过，已发布`)
      return true
    } catch (e) {
      toast.error(errMsg(e, '审批失败'))
      return false
    }
  }

  async function offlineTag(id: number): Promise<boolean> {
    if (!canEdit()) {
      toast.error('无标签编辑权限')
      return false
    }
    try {
      const t = mapTag(await api.offlineTag(id))
      replaceTag(t)
      activity.log(auth.user.name, `下线标签「${t.name}」`, String(id))
      toast.success(`标签「${t.name}」已下线`)
      return true
    } catch (e) {
      toast.error(errMsg(e, '下线失败'))
      return false
    }
  }

  async function deleteTag(id: number): Promise<boolean> {
    if (!canEdit()) {
      toast.error('无标签编辑权限')
      return false
    }
    const t = get(id)
    try {
      await api.deleteTag(id)
      tags.value = tags.value.filter((x) => x.id !== id)
      activity.log(auth.user.name, `删除标签「${t?.name ?? id}」`, String(id))
      toast.success(`标签「${t?.name ?? id}」已删除`)
      return true
    } catch (e) {
      toast.error(errMsg(e, '删除失败'))
      return false
    }
  }

  return {
    tags, filterStatus, filterType, keyword,
    loading, loaded, loadError,
    publishedTags, filtered, kpi,
    TYPE_LABEL, STATUS_LABEL, SENSITIVITY_LABEL,
    get, canEdit, canPublish, canApprove,
    load, seed,
    createTag, updateTag, previewCompute, publishTag, approvePublish,
    offlineTag, deleteTag,
  }
})
