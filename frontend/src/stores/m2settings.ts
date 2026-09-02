// ============================================================
// M2Settings 门店级配置 store（M2-21）
// 分组：基础信息 / 预约规则 / 库存与采购 / 通知偏好。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

export interface M2Settings {
  // 基础信息
  storeName: string
  storeCode: string
  businessHoursWeekday: string
  businessHoursWeekend: string
  // 预约规则
  minAdvanceHours: number
  maxAdvanceDays: number
  lateCancelMinutes: number
  noShowFee: number
  // 库存与采购
  inventoryWarnThreshold: number
  purchaseLeadDays: number
  autoReorder: boolean
  // 收银 / 退款
  refundMaxAmount: number
  refundRequireApproval: boolean
  invoiceTitle: string
  // 通知
  notifySmsBooking: boolean
  notifySmsArrival: boolean
  notifyWxFollowup: boolean
  notifyEmailReport: boolean
}

export interface SettingChangeLog {
  id: string
  by: string
  at: string
  field: string
  oldValue: string
  newValue: string
}

const FIELD_LABEL: Record<keyof M2Settings, string> = {
  storeName: '门店名称',
  storeCode: '门店编码',
  businessHoursWeekday: '工作日营业时间',
  businessHoursWeekend: '周末营业时间',
  minAdvanceHours: '最早提前预约（小时）',
  maxAdvanceDays: '最晚提前预约（天）',
  lateCancelMinutes: '迟到/取消缓冲（分钟）',
  noShowFee: '爽约扣费（元）',
  inventoryWarnThreshold: '库存预警阈值（%）',
  purchaseLeadDays: '采购到货周期（天）',
  autoReorder: '启用自动补货建议',
  refundMaxAmount: '店长退款上限（元）',
  refundRequireApproval: '超额退款需审批',
  invoiceTitle: '发票抬头',
  notifySmsBooking: '预约成功短信',
  notifySmsArrival: '到店提醒短信',
  notifyWxFollowup: '企微回访提醒',
  notifyEmailReport: '周报邮件推送',
}

export const useM2SettingsStore = defineStore('m2settings', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const settings = ref<M2Settings>({
    storeName: '美研云·静安旗舰店',
    storeCode: 'MY-JA-001',
    businessHoursWeekday: '10:00 - 21:00',
    businessHoursWeekend: '09:30 - 21:30',
    minAdvanceHours: 2,
    maxAdvanceDays: 30,
    lateCancelMinutes: 15,
    noShowFee: 100,
    inventoryWarnThreshold: 20,
    purchaseLeadDays: 7,
    autoReorder: true,
    refundMaxAmount: 2000,
    refundRequireApproval: true,
    invoiceTitle: '上海美研医疗美容门诊部有限公司',
    notifySmsBooking: true,
    notifySmsArrival: true,
    notifyWxFollowup: true,
    notifyEmailReport: false,
  })

  const staffOnDuty = ref(18)
  const logs = ref<SettingChangeLog[]>([])

  const businessHoursLen = computed(() => {
    // 简单解析 "10:00 - 21:00"，返回小时数（用于 KPI 展示）
    const m = settings.value.businessHoursWeekday.match(/(\d{1,2}):(\d{2})\s*-\s*(\d{1,2}):(\d{2})/)
    if (!m) return 0
    const start = Number(m[1]) + Number(m[2]) / 60
    let end = Number(m[3]) + Number(m[4]) / 60
    if (end < start) end += 24
    return Math.round((end - start) * 10) / 10
  })

  const warnItemCount = ref(6)

  const monthModified = computed(() => {
    const now = new Date()
    return logs.value.filter((l) => {
      const d = new Date(l.at)
      return d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth()
    }).length
  })

  function update<K extends keyof M2Settings>(key: K, value: M2Settings[K]): boolean {
    if (!auth.can('m2settings:edit')) {
      console.warn('[m2settings] 无 m2settings:edit 权限')
      return false
    }
    const old = settings.value[key]
    if (old === value) return true
    settings.value[key] = value
    logs.value.unshift({
      id: nextId('slog'),
      by: auth.user.name,
      at: new Date().toISOString(),
      field: FIELD_LABEL[key],
      oldValue: String(old),
      newValue: String(value),
    })
    activity.log(auth.user.name, `修改门店设置：${FIELD_LABEL[key]}（${old} → ${value}）`)
    return true
  }

  function saveAll(next: M2Settings): boolean {
    if (!auth.can('m2settings:edit')) return false
    let changed = 0
    const cur = settings.value as unknown as Record<string, string | number | boolean>
    const nxt = next as unknown as Record<string, string | number | boolean>
    ;(Object.keys(next) as Array<keyof M2Settings>).forEach((k) => {
      if (cur[k] !== nxt[k]) {
        const old = cur[k]
        cur[k] = nxt[k]
        logs.value.unshift({
          id: nextId('slog'),
          by: auth.user.name,
          at: new Date().toISOString(),
          field: FIELD_LABEL[k],
          oldValue: String(old),
          newValue: String(nxt[k]),
        })
        changed += 1
      }
    })
    if (changed > 0) {
      activity.log(auth.user.name, `批量保存门店设置，共 ${changed} 项变更`)
    }
    return true
  }

  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    const now = new Date()
    const hoursAgo = (h: number) => new Date(now.getTime() - h * 3600_000).toISOString()
    const seedLogs: SettingChangeLog[] = [
      { id: nextId('slog'), by: '苏晴', at: hoursAgo(5), field: FIELD_LABEL.noShowFee, oldValue: '50', newValue: '100' },
      { id: nextId('slog'), by: '陈野', at: hoursAgo(52), field: FIELD_LABEL.inventoryWarnThreshold, oldValue: '15', newValue: '20' },
      { id: nextId('slog'), by: '苏晴', at: hoursAgo(120), field: FIELD_LABEL.notifyWxFollowup, oldValue: 'false', newValue: 'true' },
    ]
    logs.value.push(...seedLogs)
  }

  return {
    settings, staffOnDuty, warnItemCount, logs,
    businessHoursLen, monthModified,
    update, saveAll, seed,
    FIELD_LABEL,
  }
})
