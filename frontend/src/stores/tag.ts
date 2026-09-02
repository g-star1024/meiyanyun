// ============================================================
// 标签体系 store（M3-06）
// 系统/人工/行为三大类标签 + 自动化规则。
// 对齐设计稿 210:55 / 227:194：分类树 + 打标统计 + 自动化规则。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

export type TagCategory = 'SYSTEM' | 'MANUAL' | 'BEHAVIOR'

export interface CustomerTag {
  id: string
  name: string
  category: TagCategory
  color: string
  customerCount: number
  rule: string
  recentHitCustomers?: string[]
}

export interface AutomationRule {
  id: string
  name: string
  trigger: string
  condition: string
  action: string
  enabled: boolean
  lastFiredAt?: string
}

const CATEGORY_LABEL: Record<TagCategory, string> = {
  SYSTEM: '系统标签',
  MANUAL: '人工标签',
  BEHAVIOR: '行为标签',
}

const CATEGORY_COLOR: Record<TagCategory, string> = {
  SYSTEM: '#8B5CF6',
  MANUAL: '#F59E0B',
  BEHAVIOR: '#6366F1',
}

export const useTagStore = defineStore('tag', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const tags = ref<CustomerTag[]>([])
  const rules = ref<AutomationRule[]>([])

  const selectedId = ref<string | null>(null)

  const systemTags = computed(() => tags.value.filter((t) => t.category === 'SYSTEM'))
  const manualTags = computed(() => tags.value.filter((t) => t.category === 'MANUAL'))
  const behaviorTags = computed(() => tags.value.filter((t) => t.category === 'BEHAVIOR'))

  const totalTags = computed(() => tags.value.length)
  const totalCovered = computed(() => {
    // 去重估算：客户可能多标签，用最大覆盖近似（演示数据）
    return 12846
  })
  const todayTagged = ref(236)
  const avgTagsPerCustomer = ref(3.2)

  const selected = computed(() => tags.value.find((t) => t.id === selectedId.value) ?? tags.value[0] ?? null)

  function select(id: string) {
    selectedId.value = id
  }

  function createTag(input: Omit<CustomerTag, 'id' | 'customerCount' | 'recentHitCustomers'>): CustomerTag | null {
    if (!auth.can('tag:edit')) {
      console.warn('[tag] 无 tag:edit 权限')
      return null
    }
    const t: CustomerTag = {
      ...input,
      id: nextId('tag'),
      customerCount: 0,
      recentHitCustomers: [],
    }
    tags.value.push(t)
    activity.log(auth.user.name, `新建标签「${t.name}」`, t.id)
    return t
  }

  function updateTag(id: string, patch: Partial<Pick<CustomerTag, 'name' | 'color' | 'rule'>>): boolean {
    const t = tags.value.find((x) => x.id === id)
    if (!t || !auth.can('tag:edit')) return false
    Object.assign(t, patch)
    activity.log(auth.user.name, `更新标签「${t.name}」`, t.id)
    return true
  }

  function batchTag(customerNames: string[], tagName: string): boolean {
    if (!auth.can('tag:edit')) return false
    const t = tags.value.find((x) => x.name === tagName)
    if (!t) return false
    t.customerCount += customerNames.length
    activity.log(auth.user.name, `批量打标「${tagName}」× ${customerNames.length}`)
    return true
  }

  function toggleRule(id: string): boolean {
    const r = rules.value.find((x) => x.id === id)
    if (!r || !auth.can('tag:edit')) return false
    r.enabled = !r.enabled
    activity.log(auth.user.name, `${r.enabled ? '启用' : '禁用'}规则「${r.name}」`, r.id)
    return true
  }

  function createRule(input: Omit<AutomationRule, 'id' | 'enabled'>): AutomationRule | null {
    if (!auth.can('tag:edit')) return null
    const r: AutomationRule = { ...input, id: nextId('rule'), enabled: true }
    rules.value.push(r)
    activity.log(auth.user.name, `新建自动化规则「${r.name}」`, r.id)
    return r
  }

  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true

    const system: Array<Omit<CustomerTag, 'id' | 'category' | 'color'>> = [
      { name: '高价值', customerCount: 1842, rule: '累计消费 ≥ 50,000 元', recentHitCustomers: ['陈美玲', '赵雨晴', '孙佳宁'] },
      { name: '沉睡客户', customerCount: 986, rule: '90 天未到店', recentHitCustomers: ['周岚', '李娜', '吴桐'] },
      { name: '价格敏感', customerCount: 2134, rule: '只在折扣期下单 ≥ 3 次', recentHitCustomers: ['王晓明', '张倩', '刘洋'] },
      { name: '流失风险', customerCount: 412, rule: '180 天未到店且历史消费 > 10,000', recentHitCustomers: ['黄磊', '徐静', '马超'] },
      { name: '新客', customerCount: 568, rule: '首次到店 ≤ 30 天', recentHitCustomers: ['林可', '郑洁', '韩梅'] },
      { name: '高频到店', customerCount: 721, rule: '近 90 天到店 ≥ 6 次', recentHitCustomers: ['苏晴', '冯刚', '邓丽'] },
      { name: '项目偏好-光电', customerCount: 1320, rule: '光电类项目消费占比 > 60%', recentHitCustomers: ['曹颖', '彭磊', '邱实'] },
      { name: '转化率高', customerCount: 486, rule: '到店成交率 > 70%', recentHitCustomers: ['宋玉', '潘婷', '蔡明'] },
    ]
    const manual: Array<Omit<CustomerTag, 'id' | 'category' | 'color'>> = [
      { name: 'VIP-A', customerCount: 168, rule: '店长手动标记', recentHitCustomers: ['陈美玲', '赵雨晴', '孙佳宁'] },
      { name: '医美达人', customerCount: 342, rule: '咨询师标记', recentHitCustomers: ['周岚', '李娜', '吴桐'] },
      { name: '抗衰专注', customerCount: 824, rule: '咨询师标记', recentHitCustomers: ['王晓明', '张倩', '刘洋'] },
      { name: '价格敏感型', customerCount: 1024, rule: '咨询师标记', recentHitCustomers: ['黄磊', '徐静', '马超'] },
      { name: '活动偏好', customerCount: 682, rule: '运营标记', recentHitCustomers: ['林可', '郑洁', '韩梅'] },
      { name: '口碑客户', customerCount: 215, rule: '推荐 ≥ 3 人', recentHitCustomers: ['苏晴', '冯刚', '邓丽'] },
      { name: '需重点跟进', customerCount: 94, rule: '店长标记', recentHitCustomers: ['曹颖', '彭磊', '邱实'] },
      { name: '投诉记录', customerCount: 38, rule: '6 个月内有投诉', recentHitCustomers: ['宋玉', '潘婷', '蔡明'] },
      { name: '生日月', customerCount: 156, rule: '本月生日', recentHitCustomers: ['陈美玲', '赵雨晴', '孙佳宁'] },
      { name: '孕期/产后', customerCount: 78, rule: '咨询师标记', recentHitCustomers: ['周岚', '李娜', '吴桐'] },
    ]
    const behavior: Array<Omit<CustomerTag, 'id' | 'category' | 'color'>> = [
      { name: '大额消费', customerCount: 412, rule: '单笔订单 ≥ 10,000 元', recentHitCustomers: ['陈美玲', '赵雨晴', '孙佳宁'] },
      { name: '预约未到', customerCount: 286, rule: '近 90 天爽约 ≥ 2 次', recentHitCustomers: ['黄磊', '徐静', '马超'] },
      { name: '高频到店', customerCount: 721, rule: '近 90 天到店 ≥ 6 次', recentHitCustomers: ['苏晴', '冯刚', '邓丽'] },
      { name: '卡项余额高', customerCount: 1056, rule: '卡项余额 ≥ 20,000', recentHitCustomers: ['曹颖', '彭磊', '邱实'] },
      { name: '疗程未完成', customerCount: 348, rule: '购疗程但剩余 ≥ 50%', recentHitCustomers: ['宋玉', '潘婷', '蔡明'] },
      { name: '浏览未购', customerCount: 624, rule: '小程序浏览但未下单', recentHitCustomers: ['林可', '郑洁', '韩梅'] },
    ]

    system.forEach((t) => tags.value.push({ ...t, id: nextId('tag'), category: 'SYSTEM', color: CATEGORY_COLOR.SYSTEM }))
    manual.forEach((t) => tags.value.push({ ...t, id: nextId('tag'), category: 'MANUAL', color: CATEGORY_COLOR.MANUAL }))
    behavior.forEach((t) => tags.value.push({ ...t, id: nextId('tag'), category: 'BEHAVIOR', color: CATEGORY_COLOR.BEHAVIOR }))

    rules.value = [
      { id: nextId('rule'), name: '新客自动打标', trigger: '首次到店', condition: '新客户建档', action: '自动打「新客」+「高价值」', enabled: true, lastFiredAt: new Date(Date.now() - 2 * 3600_000).toISOString() },
      { id: nextId('rule'), name: '沉睡预警打标', trigger: '60 天未到店', condition: '历史消费 > 5,000', action: '自动打「流失风险」', enabled: true, lastFiredAt: new Date(Date.now() - 86400_000).toISOString() },
      { id: nextId('rule'), name: '消费达标打标', trigger: '单月消费 ≥ 5,000', condition: '客户等级 ≤ 银卡', action: '自动打「VIP-A」', enabled: true, lastFiredAt: new Date(Date.now() - 3 * 86400_000).toISOString() },
      { id: nextId('rule'), name: '生日月自动营销', trigger: '客户生日所在自然月', condition: '客户等级 ≥ 银卡', action: '打「生日月」并推送双倍积分', enabled: false },
    ]
  }

  return {
    tags, rules, selectedId, selected,
    systemTags, manualTags, behaviorTags,
    totalTags, totalCovered, todayTagged, avgTagsPerCustomer,
    select, createTag, updateTag, batchTag, toggleRule, createRule,
    seed, CATEGORY_LABEL, CATEGORY_COLOR,
  }
})
