/**
 * 消息与设置 store（C 端）
 * C-B6 切真（DESIGN-C §四端点 #9）：设置 GET /c/settings（V76 缺行回落默认
 * order/appt=true、promo=false，phone=登录档案掩码前三后四；实名/地址/缓存/关于/帮助
 * 留前端静态 §七），开关 PUT /c/settings（upsert，失败本地回滚，错误文案 http 层弹出）；
 * 通知 GET /c/notifications（V77 行级隔离 LIMIT 50 倒序），单条已读
 * PUT /c/notifications/{id}/read，全部已读 PUT /c/notifications/read-all
 * （写路径静默：本地即时生效，失败不扰动本地态）。
 */
import { defineStore } from 'pinia'
import { reactive, ref } from 'vue'
import { http } from '@/utils/request'

export interface NoticeSettings {
  order: boolean
  appt: boolean
  promo: boolean
  phone: string
}

export interface Notif {
  id: string
  type: 'appt' | 'promo' | 'system'
  title: string
  body: string
  time: string
  read: boolean
  to?: string
}

export const useNoticeStore = defineStore('mp-notice', () => {
  const settings = reactive<NoticeSettings>({ order: true, appt: true, promo: false, phone: '' })
  const notifs = ref<Notif[]>([])

  let seeded = false
  async function seed() {
    if (seeded) return
    seeded = true
    try {
      const [s, list] = await Promise.all([
        http.get<NoticeSettings | null>('/c/settings', { silent: true }),
        http.get<Notif[]>('/c/notifications', { silent: true }),
      ])
      if (s) {
        settings.order = s.order ?? true
        settings.appt = s.appt ?? true
        settings.promo = s.promo ?? false
        settings.phone = s.phone ?? ''
      }
      if (Array.isArray(list)) notifs.value = list
    } catch {
      /* 未登录/后端未就绪：回滚 seeded 允许登录后 onShow 重拉（本地默认值兜底） */
      seeded = false
    }
  }

  /** 开关持久化：先本地翻转，PUT 失败回滚（错误文案由 http 层弹出） */
  async function toggle(key: 'order' | 'appt' | 'promo') {
    const prev = settings[key]
    settings[key] = !prev
    try {
      await http.put('/c/settings', { order: settings.order, appt: settings.appt, promo: settings.promo })
    } catch {
      settings[key] = prev
    }
  }

  /** 单条已读（静默写路径：本地即时生效，失败不扰动） */
  function markRead(n: Notif) {
    if (n.read) return
    n.read = true
    http.put(`/c/notifications/${encodeURIComponent(n.id)}/read`, {}, { silent: true }).catch(() => {})
  }

  /** 全部已读（静默写路径：本地即时生效，失败不扰动） */
  function markAllRead() {
    notifs.value.forEach((n) => (n.read = true))
    http.put('/c/notifications/read-all', {}, { silent: true }).catch(() => {})
  }

  return { settings, notifs, seed, toggle, markRead, markAllRead }
})
