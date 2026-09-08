// ============================================================
// Notification 消息通知 store（T3-03 + G-02 统一触达中心，B20 接真实 API）
// 数据源：txn-service /txn/notifications（登录人按工号隔离）。
// 当前生产源：审批 SLA 超时催办（ApprovalSlaJob：category=APPROVAL/level=URGENT）。
// 已读/全部已读为服务端持久化；通知偏好（类别开关/渠道）暂为本地 UI 状态，
// 后端偏好持久化属 Backlog（见 docs/DEVELOPMENT-ROADMAP.md）。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import {
  listNotifications, markNotificationRead, markAllNotificationsRead,
  type NotificationDTO,
} from '@/api/notification'

export type NotifyCategory = 'APPROVAL' | 'CUSTOMER' | 'INVENTORY' | 'MARKETING' | 'SYSTEM'
export type NotifyChannel = 'INBOX' | 'SMS' | 'WECHAT' | 'EMAIL'
export type NotifyLevel = 'INFO' | 'WARNING' | 'URGENT'

export interface AppNotification {
  id: number
  category: NotifyCategory
  level: NotifyLevel
  title: string
  content: string
  read: boolean
  createdAt: string
  /** 关联业务，可点击跳转 */
  link?: string
  sender?: string
  /** 关联业务单号（审批待办号等） */
  bizRef?: string
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

const CATEGORIES: NotifyCategory[] = ['APPROVAL', 'CUSTOMER', 'INVENTORY', 'MARKETING', 'SYSTEM']

function adapt(d: NotificationDTO): AppNotification {
  const category = CATEGORIES.includes(d.category as NotifyCategory) ? (d.category as NotifyCategory) : 'SYSTEM'
  const level: NotifyLevel = d.level === 'URGENT' || d.level === 'WARNING' ? d.level : 'INFO'
  return {
    id: d.id,
    category,
    level,
    title: d.title,
    content: d.content,
    read: !!d.read,
    createdAt: d.createdAt,
    link: d.link || undefined,
    sender: !d.sender || d.sender === 'system' ? '系统' : d.sender,
    bizRef: d.bizRef || undefined,
  }
}

export const useNotificationStore = defineStore('notification', () => {
  const items = ref<AppNotification[]>([])
  const activeCategory = ref<NotifyCategory | 'ALL'>('ALL')
  const readFilter = ref<'ALL' | 'UNREAD'>('UNREAD')
  // 偏好暂为本地默认态（后端持久化属 Backlog）
  const preferences = ref<NotifyPreference[]>(
    CATEGORIES.map((c) => ({
      category: c,
      enabled: true,
      channels: c === 'SYSTEM' ? ['INBOX', 'SMS'] : ['INBOX'],
    })),
  )

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

  /** 拉取当前登录人的通知（最新在前）；失败静默——通知为旁路能力，不阻断页面 */
  async function fetch() {
    try {
      const res = await listNotifications()
      items.value = (res.data.items || []).map(adapt)
    } catch (e) {
      console.error('[notification] 通知加载失败', e)
    }
  }

  /** 单条已读：乐观更新 + 服务端持久化（失败回滚） */
  async function markRead(id: number) {
    const n = items.value.find((x) => x.id === id)
    if (!n || n.read) return
    n.read = true
    try {
      await markNotificationRead(id)
    } catch (e) {
      n.read = false
      console.error('[notification] 标记已读失败', e)
    }
  }

  /** 全部已读（当前分类下未读）：乐观更新 + 服务端持久化（失败回滚） */
  async function markAllRead() {
    const target = items.value.filter((n) => {
      if (n.read) return false
      return activeCategory.value === 'ALL' || n.category === activeCategory.value
    })
    if (target.length === 0) return
    target.forEach((n) => (n.read = true))
    try {
      await markAllNotificationsRead()
    } catch (e) {
      target.forEach((n) => (n.read = false))
      console.error('[notification] 全部已读失败', e)
    }
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

  return {
    items, activeCategory, readFilter, preferences,
    unreadCount, unreadByCategory, filtered,
    categoryLabel, CATEGORY_LABEL,
    fetch, markRead, markAllRead, togglePreference, toggleChannel,
  }
})
