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
  getNotificationPreferences, updateNotificationPreference, notificationStreamUrl,
  type NotificationDTO, type NotifyPreferenceDTO,
} from '@/api/notification'
import { getToken } from '@/api/client'

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
  /** 个人免打扰开关（B60：仅非 INBOX 渠道延后，URGENT 恒豁免） */
  quietEnabled: boolean
  /** 免打扰开始 HH:mm（可跨午夜） */
  quietStart: string
  /** 免打扰结束 HH:mm（可跨午夜） */
  quietEnd: string
}

/** 个人免打扰后端默认时段（无落库行回落值，与 NotificationController 常量同构） */
const DEFAULT_QUIET_START = '22:00'
const DEFAULT_QUIET_END = '08:00'

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
      quietEnabled: false,
      quietStart: DEFAULT_QUIET_START,
      quietEnd: DEFAULT_QUIET_END,
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
    // B50 卡5（L146）：无 token 不发请求（/txn/notifications 网关实测需鉴权，未登录硬拉必 401）
    if (!getToken()) return
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
    return {
      category,
      enabled: !!d.enabled,
      channels,
      quietEnabled: !!d.quietEnabled,
      quietStart: d.quietStart || DEFAULT_QUIET_START,
      quietEnd: d.quietEnd || DEFAULT_QUIET_END,
    }
  }

  /** 拉取服务端持久化偏好（失败静默保留本地默认态——偏好为旁路能力，不阻断页面） */
  async function fetchPreferences() {
    // B50 卡5（L146）：无 token 不发请求，偏好保留本地默认态
    if (!getToken()) return
    try {
      const res = await getNotificationPreferences()
      const rows = (res.data.items || []).map(adaptPreference)
      preferences.value = CATEGORIES.map(
        (c) => rows.find((r) => r.category === c) || {
          category: c, enabled: true,
          channels: c === 'SYSTEM' ? ['INBOX', 'SMS'] : (['INBOX'] as NotifyChannel[]),
          quietEnabled: false,
          quietStart: DEFAULT_QUIET_START,
          quietEnd: DEFAULT_QUIET_END,
        },
      )
    } catch (e) {
      console.error('[notification] 通知偏好加载失败', e)
    }
  }

  /** 类别订阅开关：乐观更新 + 服务端持久化（失败回滚）；关闭订阅时渠道清空由后端归一；免打扰设置原样透传保留 */
  async function togglePreference(category: NotifyCategory, enabled: boolean) {
    const p = preferences.value.find((x) => x.category === category)
    if (!p || p.enabled === enabled) return
    const snapshot = { enabled: p.enabled, channels: [...p.channels] }
    p.enabled = enabled
    const channels = enabled ? (snapshot.channels.length ? snapshot.channels : ['INBOX'] as NotifyChannel[]) : []
    try {
      const res = await updateNotificationPreference({
        category, enabled, channels,
        quietEnabled: p.quietEnabled, quietStart: p.quietStart, quietEnd: p.quietEnd,
      })
      preferences.value = (res.data.items || []).map(adaptPreference)
    } catch (e) {
      p.enabled = snapshot.enabled
      p.channels = snapshot.channels
      console.error('[notification] 偏好订阅开关保存失败', e)
    }
  }

  /** 渠道勾选：乐观更新 + 服务端持久化（失败回滚）；后端约束订阅开启须含 INBOX；免打扰设置原样透传保留 */
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
        quietEnabled: p.quietEnabled, quietStart: p.quietStart, quietEnd: p.quietEnd,
      })
      preferences.value = (res.data.items || []).map(adaptPreference)
    } catch (e) {
      p.enabled = snapshot.enabled
      p.channels = snapshot.channels
      console.error('[notification] 偏好渠道保存失败', e)
    }
  }

  /**
   * 个人免打扰保存（B60 L65）：整类别 quiet 三字段持久化，返回 boolean 供页面 toast。
   * 非乐观——后端有 HH:mm 格式/起止相等 400 校验，失败时本地时段不动、由调用方提示中文原因。
   */
  async function saveQuiet(
    category: NotifyCategory,
    quiet: { quietEnabled: boolean; quietStart: string; quietEnd: string },
  ): Promise<boolean> {
    const p = preferences.value.find((x) => x.category === category)
    if (!p) return false
    try {
      const res = await updateNotificationPreference({
        category,
        enabled: p.enabled,
        channels: [...p.channels],
        quietEnabled: quiet.quietEnabled,
        quietStart: quiet.quietStart,
        quietEnd: quiet.quietEnd,
      })
      preferences.value = (res.data.items || []).map(adaptPreference)
      return true
    } catch (e) {
      console.error('[notification] 免打扰设置保存失败', e)
      throw e
    }
  }

  // ============================================================
  // B60 卡3：铃铛 SSE 实时推送（GET /api/txn/notifications/stream）
  // 服务端扇出 Job 对 INBOX 置 SENT 后按工号单人推 "notification" 事件，
  // 载荷即 notification 实体（与列表 DTO 同构，多个 idemKey 字段无害）。
  // 离线期间不补推，靠 onMounted 的 fetch() 拉全量；此处仅做增量头插。
  // ============================================================
  let es: EventSource | null = null
  // ready 首帧到达前握手是否成功未知；401 等致命错误浏览器会先 error 后 CLOSED，需主动摘除防死循环重连
  let streamReady = false

  function connectStream() {
    // 守卫防重：桌面壳 keep-alive 重复 onMounted 或多组件挂载时不另开连接
    if (es) return
    // 无 token（未登录/已退出）不握手，EventSource 不能带 Authorization 头
    if (!getToken()) return
    streamReady = false
    es = new EventSource(notificationStreamUrl())
    // ready 首帧：{"staffId":"..."}，仅用于标记握手成功，无数据语义
    es.addEventListener('ready', () => {
      streamReady = true
    })
    es.addEventListener('notification', (ev: MessageEvent) => {
      try {
        const d = JSON.parse(ev.data as string) as NotificationDTO
        // 崩溃自愈路径服务端可能重复 push 同一条（NotificationFanoutJob.retryOne），按主键去重
        if (items.value.some((n) => n.id === d.id)) return
        items.value.unshift(adapt(d))
      } catch (e) {
        // 单条负载异常丢弃，不影响整条流
        console.error('[notification] SSE 消息解析失败', e)
      }
    })
    es.onerror = () => {
      // EventSource 在 CONNECTING 态会由浏览器自动重连（服务端 30min 超时/网络抖动），无需手工干预；
      // 已握过手（收过 ready）后掉线也交给原生重连。仅当从未收到 ready 首帧且连接 CLOSED，
      // 多为 401 凭证失效（登出/token 过期），原生自动重连会以坏 token 死循环刷 401，
      // 主动关闭等下次登录后再连。
      if (es && !streamReady && es.readyState === EventSource.CLOSED) {
        es.close()
        es = null
        streamReady = false
      }
    }
  }

  function disconnectStream() {
    es?.close()
    es = null
    streamReady = false
  }

  return {
    items, activeCategory, readFilter, preferences,
    unreadCount, unreadByCategory, filtered,
    categoryLabel, CATEGORY_LABEL,
    fetch, fetchPreferences, markRead, markAllRead, togglePreference, toggleChannel, saveQuiet,
    connectStream, disconnectStream,
  }
})
