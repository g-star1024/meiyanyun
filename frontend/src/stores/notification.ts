// ============================================================
// Notification 消息通知 store（T3-03 + G-02 统一触达中心，B20 接真实 API，B21 偏好持久化）
// 数据源：txn-service /txn/notifications（登录人按工号隔离）。
// 当前生产源：审批 SLA 超时催办（ApprovalSlaJob：category=APPROVAL/level=URGENT）。
// 已读/全部已读/通知偏好（类别开关/渠道）均为服务端持久化（B21 起 notify_preference 表）；
// SLA 催办落库前按 APPROVAL 类别偏好过滤（关闭订阅则免打扰）。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import {
  listNotifications, markNotificationRead, markAllNotificationsRead,
  getNotificationPreferences, updateNotificationPreference,
  type NotificationDTO, type NotifyPreferenceDTO,
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
  // 偏好：本地默认态兜底，onMounted 调 fetchPreferences 拉取服务端持久化结果覆盖（B21）
  const preferences = ref<NotifyPreference[]>(
    CATEGORIES.map((c) => ({
      category: c,
      enabled: true,
      channels: c === 'SYSTEM' ? ['INBOX', 'SMS'] : (['INBOX'] as NotifyChannel[]),
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

  const VALID_CHANNELS: NotifyChannel[] = ['INBOX', 'SMS', 'WECHAT', 'EMAIL']

  function adaptPreference(d: NotifyPreferenceDTO): NotifyPreference {
    const category = CATEGORIES.includes(d.category as NotifyCategory)
      ? (d.category as NotifyCategory) : 'SYSTEM'
    const channels = (d.channels || [])
      .map((c) => c as NotifyChannel)
      .filter((c) => VALID_CHANNELS.includes(c))
    return { category, enabled: !!d.enabled, channels }
  }

  /** 拉取服务端持久化偏好（失败静默保留本地默认态——偏好为旁路能力，不阻断页面） */
  async function fetchPreferences() {
    try {
      const res = await getNotificationPreferences()
      const rows = (res.data.items || []).map(adaptPreference)
      preferences.value = CATEGORIES.map(
        (c) => rows.find((r) => r.category === c) || {
          category: c, enabled: true,
          channels: c === 'SYSTEM' ? ['INBOX', 'SMS'] : (['INBOX'] as NotifyChannel[]),
        },
      )
    } catch (e) {
      console.error('[notification] 通知偏好加载失败', e)
    }
  }

  /** 类别订阅开关：乐观更新 + 服务端持久化（失败回滚）；关闭订阅时渠道清空由后端归一 */
  async function togglePreference(category: NotifyCategory, enabled: boolean) {
    const p = preferences.value.find((x) => x.category === category)
    if (!p || p.enabled === enabled) return
    const snapshot = { enabled: p.enabled, channels: [...p.channels] }
    p.enabled = enabled
    const channels = enabled ? (snapshot.channels.length ? snapshot.channels : ['INBOX'] as NotifyChannel[]) : []
    try {
      const res = await updateNotificationPreference({ category, enabled, channels })
      preferences.value = (res.data.items || []).map(adaptPreference)
    } catch (e) {
      p.enabled = snapshot.enabled
      p.channels = snapshot.channels
      console.error('[notification] 偏好订阅开关保存失败', e)
    }
  }

  /** 渠道勾选：乐观更新 + 服务端持久化（失败回滚）；后端约束订阅开启须含 INBOX */
  async function toggleChannel(category: NotifyCategory, channel: NotifyChannel) {
    const p = preferences.value.find((x) => x.category === category)
    if (!p) return
    const snapshot = { enabled: p.enabled, channels: [...p.channels] }
    const idx = p.channels.indexOf(channel)
    if (idx >= 0) p.channels.splice(idx, 1)
    else p.channels.push(channel)
    try {
      const res = await updateNotificationPreference({
        category, enabled: p.enabled, channels: [...p.channels],
      })
      preferences.value = (res.data.items || []).map(adaptPreference)
    } catch (e) {
      p.enabled = snapshot.enabled
      p.channels = snapshot.channels
      console.error('[notification] 偏好渠道保存失败', e)
    }
  }

  return {
    items, activeCategory, readFilter, preferences,
    unreadCount, unreadByCategory, filtered,
    categoryLabel, CATEGORY_LABEL,
    fetch, fetchPreferences, markRead, markAllRead, togglePreference, toggleChannel,
  }
})
