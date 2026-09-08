// ============================================================
// Notification（消息通知中心 B20）API
// 数据源：txn-service 真实端点 /txn/notifications（登录人按工号隔离）。
// 当前生产源：审批 SLA 超时催办（category=APPROVAL/level=URGENT，sender=system）。
// 后端实体直接序列化（Jackson camelCase）；id 为数字（主键自增），read 为布尔。
// ============================================================
import client from './client'

/** 通知实体 DTO（notification 表行）。 */
export interface NotificationDTO {
  id: number
  /** 收件人工号 */
  recipient: string
  category: string // APPROVAL/CUSTOMER/INVENTORY/MARKETING/SYSTEM
  level: string // INFO/WARNING/URGENT
  title: string
  content: string
  link?: string | null
  /** 关联业务单号（如审批待办号 todoNo） */
  bizRef?: string | null
  sender?: string | null
  read: boolean
  createdAt: string
}

/** 我的通知（最新在前）+ 服务端未读数。 */
export const listNotifications = () =>
  client.get<{ items: NotificationDTO[]; unread: number }>('/txn/notifications')

/** 未读数（铃铛角标用）。 */
export const unreadCount = () =>
  client.get<{ unread: number }>('/txn/notifications/unread-count')

/** 单条标记已读（仅本人通知，越权 404）。 */
export const markNotificationRead = (id: number) =>
  client.post<{ id: number; read: boolean }>(`/txn/notifications/${id}/read`)

/** 全部标记已读（当前登录人），返回更新条数。 */
export const markAllNotificationsRead = () =>
  client.post<{ updated: number }>('/txn/notifications/read-all')

/** 通知偏好行 DTO（notify_preference 表；无落库行类别由后端回落默认值）。 */
export interface NotifyPreferenceDTO {
  category: string
  enabled: boolean
  channels: string[]
}

/** 我的通知偏好（五大类别全量；未设置类别后端回落系统默认）。 */
export const getNotificationPreferences = () =>
  client.get<{ items: NotifyPreferenceDTO[] }>('/txn/notifications/preferences')

/** 更新单个类别偏好（upsert），返回更新后的全量偏好。 */
export const updateNotificationPreference = (body: {
  category: string
  enabled: boolean
  channels: string[]
}) => client.put<{ items: NotifyPreferenceDTO[] }>('/txn/notifications/preferences', body)
