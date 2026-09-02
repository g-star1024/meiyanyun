// ============================================================
// Notification 消息通知 store（T3-03 + G-02 统一触达中心）
// 统一接收各业务模块的站内通知：审批待办、客户提醒、库存预警、营销任务、系统公告。
// 支持已读/未读、分类筛选、全部已读、通知偏好（按类别开关 + 渠道）。
// 对齐 docs/business-flows.md、permission-matrix.md。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId } from './activity'

export type NotifyCategory = 'APPROVAL' | 'CUSTOMER' | 'INVENTORY' | 'MARKETING' | 'SYSTEM'
export type NotifyChannel = 'INBOX' | 'SMS' | 'WECHAT' | 'EMAIL'
export type NotifyLevel = 'INFO' | 'WARNING' | 'URGENT'

export interface AppNotification {
  id: string
  category: NotifyCategory
  level: NotifyLevel
  title: string
  content: string
  read: boolean
  createdAt: string
  /** 关联业务，可点击跳转 */
  link?: string
  sender?: string
}

export interface NotifyPreference {
  category: NotifyCategory
  enabled: boolean
  channels: NotifyChannel[]
}

const CATEGORY_LABEL: Record<NotifyCategory, string> = {
  APPROVAL: '审批待办',
  CUSTOMER: '客户提醒',
  INVENTORY: '库存预警',
  MARKETING: '营销任务',
  SYSTEM: '系统公告',
}

export const useNotificationStore = defineStore('notification', () => {
  const items = ref<AppNotification[]>([])
  const activeCategory = ref<NotifyCategory | 'ALL'>('ALL')
  const readFilter = ref<'ALL' | 'UNREAD'>('UNREAD')
  const preferences = ref<NotifyPreference[]>([])

  const unreadCount = computed(() => items.value.filter((n) => !n.read).length)
  const unreadByCategory = computed(() => {
    const map: Record<string, number> = {}
    for (const n of items.value) {
      if (!n.read) map[n.category] = (map[n.category] || 0) + 1
    }
    return map
  })

  const filtered = computed(() => {
    return items.value.filter((n) => {
      if (activeCategory.value !== 'ALL' && n.category !== activeCategory.value) return false
      if (readFilter.value === 'UNREAD' && n.read) return false
      return true
    })
  })

  function categoryLabel(c: NotifyCategory) {
    return CATEGORY_LABEL[c]
  }

  /** 业务模块推送通知（统一入口） */
  function push(input: {
    category: NotifyCategory
    title: string
    content: string
    level?: NotifyLevel
    link?: string
    sender?: string
  }): AppNotification {
    const n: AppNotification = {
      id: nextId('nt'),
      category: input.category,
      level: input.level || 'INFO',
      title: input.title,
      content: input.content,
      read: false,
      createdAt: new Date().toISOString(),
      link: input.link,
      sender: input.sender,
    }
    items.value.unshift(n)
    return n
  }

  function markRead(id: string) {
    const n = items.value.find((x) => x.id === id)
    if (n) n.read = true
  }

  function markAllRead() {
    const target = activeCategory.value === 'ALL' ? items.value : items.value.filter((n) => n.category === activeCategory.value)
    target.forEach((n) => (n.read = true))
  }

  function togglePreference(category: NotifyCategory, enabled: boolean) {
    const p = preferences.value.find((x) => x.category === category)
    if (p) p.enabled = enabled
  }

  function toggleChannel(category: NotifyCategory, channel: NotifyChannel) {
    const p = preferences.value.find((x) => x.category === category)
    if (!p) return
    const idx = p.channels.indexOf(channel)
    if (idx >= 0) p.channels.splice(idx, 1)
    else p.channels.push(channel)
  }

  // ===== 种子 =====
  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    const now = Date.now()
    const hoursAgo = (h: number) => new Date(now - h * 3600_000).toISOString()
    const seed: Array<Partial<AppNotification> & Pick<AppNotification, 'category' | 'title' | 'content' | 'level'>> = [
      { category: 'APPROVAL', level: 'URGENT', title: '退款审批待处理', content: '苏晴提交退款 RF20260825001（¥5,880），等待您审批', link: '/approval', sender: '审批中心' },
      { category: 'APPROVAL', level: 'WARNING', title: '采购申请进入财务复核', content: '玻尿酸采购 ¥18,500 已通过店长审批，待财务复核', link: '/approval', sender: '审批中心' },
      { category: 'CUSTOMER', level: 'WARNING', title: '高价值客户到店', content: '会员王美丽（累计消费 ¥98,000）已到店，请安排资深顾问接待', link: '/customer-graph', sender: '客户洞察' },
      { category: 'CUSTOMER', level: 'INFO', title: '今日 5 位客户生日', content: '李娜、张敏等 5 位会员今日生日，建议发送祝福与生日券', sender: '客户关怀' },
      { category: 'CUSTOMER', level: 'INFO', title: '复诊提醒到期', content: '陈思（光子嫩肤）已到复诊时间，请安排回访', link: '/recall', sender: '复诊管理' },
      { category: 'INVENTORY', level: 'URGENT', title: '玻尿酸库存预警', content: '润百颜玻尿酸剩余 8 支，低于安全库存 20 支，请及时补货', link: '/m2-inventory', sender: '库存管理' },
      { category: 'INVENTORY', level: 'WARNING', title: '耗材即将过期', content: '12 片补水面膜将于 3 天后过期，请优先使用或报损', link: '/m2-inventory', sender: '库存管理' },
      { category: 'MARKETING', level: 'INFO', title: '新营销活动待审批', content: '市场部提交"七夕闺蜜同行"活动方案，等待您审批', sender: '营销中心' },
      { category: 'MARKETING', level: 'INFO', title: '本周触达限额提醒', content: '本周已发送营销短信 2/3 次，达到周频上限前请合理规划', sender: '营销合规' },
      { category: 'SYSTEM', level: 'INFO', title: '系统将于今晚维护', content: '今晚 23:00-次日 01:00 系统例行维护，期间可能短暂不可用', sender: '系统管理员' },
      { category: 'SYSTEM', level: 'WARNING', title: '双签阈值已调整', content: '集团财务调整了退款双签金额阈值，L2 阈值由 ¥5,000 调整为 ¥3,000', sender: '系统管理员' },
      { category: 'APPROVAL', level: 'INFO', title: '请假已批准', content: '您的年假申请（8/27-8/28）已批准', sender: '审批中心' },
    ]
    seed.forEach((s, i) => {
      items.value.push({
        id: nextId('nt'),
        category: s.category!,
        level: s.level!,
        title: s.title!,
        content: s.content!,
        read: i >= 8, // 前 9 条未读，后 3 条已读
        createdAt: hoursAgo(i * 2 + 1),
        link: s.link,
        sender: s.sender,
      })
    })

    // 默认偏好
    const allCats: NotifyCategory[] = ['APPROVAL', 'CUSTOMER', 'INVENTORY', 'MARKETING', 'SYSTEM']
    preferences.value = allCats.map((c) => ({
      category: c,
      enabled: true,
      channels: c === 'SYSTEM' ? ['INBOX', 'SMS'] : ['INBOX'],
    }))
  }

  return {
    items, activeCategory, readFilter, preferences,
    unreadCount, unreadByCategory, filtered,
    categoryLabel, CATEGORY_LABEL,
    push, markRead, markAllRead, togglePreference, toggleChannel, seed,
  }
})
