import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useActivityStore } from '@/stores/activity'
import { useAuthStore } from '@/stores/auth'

// ============================================================
// M5-15 营销设置 store
// - 触达频率（周频上限≤3 硬约束 / 免打扰时段 / 节日豁免）
// - 合规词库（内置词不可删，自定义词可增删，复用 useSensitiveWords）
// - 审批流（大额券阈值 / 推送审批 / 审批层级）
// - 默认渠道（默认推送/投放）
// - 变更审计日志
// ============================================================

export type ApprovalLevel = 1 | 2
export type PushChannel = 'SMS' | 'WECOM' | 'WECHAT_MP'

export interface M5Settings {
  // 触达频率
  weeklyLimit: number          // 同一客户每周推送上限（硬约束，最大 3）
  quietHoursEnabled: boolean   // 营销免打扰时段开关
  quietStart: string           // HH:mm
  quietEnd: string
  holidayExempt: boolean       // 节日豁免
  // 审批流
  largeCouponThreshold: number // 大额券审批阈值（元）
  pushRequiresApproval: boolean
  approvalLevel: ApprovalLevel
  // 默认渠道
  defaultPushChannels: PushChannel[]
  defaultAdChannels: string[]
}

export interface AuditLog {
  id: string
  field: string
  oldValue: string
  newValue: string
  by: string
  at: string
}

let _id = 0
function nextId(p: string) {
  _id += 1
  return `${p}-${Date.now().toString(36)}-${_id}`
}
function nowIsoMin() {
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}
function offsetIsoMin(days: number) {
  const d = new Date()
  d.setDate(d.getDate() + days)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

export const PUSH_CHANNEL_LABEL: Record<PushChannel, string> = {
  SMS: '短信',
  WECOM: '企业微信',
  WECHAT_MP: '公众号',
}
export const AD_CHANNELS = ['抖音', '小红书', '美团', '大众点评', '微信私域', '视频号']

const DEFAULT_SETTINGS: M5Settings = {
  weeklyLimit: 3,
  quietHoursEnabled: true,
  quietStart: '21:00',
  quietEnd: '09:00',
  holidayExempt: true,
  largeCouponThreshold: 1000,
  pushRequiresApproval: true,
  approvalLevel: 2,
  defaultPushChannels: ['WECOM'],
  defaultAdChannels: ['抖音', '微信私域'],
}

export const useM5SettingsStore = defineStore('m5Settings', () => {
  const activity = useActivityStore()
  const auth = useAuthStore()

  const settings = ref<M5Settings>({ ...DEFAULT_SETTINGS })
  const logs = ref<AuditLog[]>([])
  const seeded = ref(false)

  const canEdit = computed(() => auth.can('m5settings:edit'))

  // 周频硬约束：上限锁定 3，不得通过设置放宽
  const WEEKLY_HARD_LIMIT = 3
  function clampWeeklyLimit(n: number) {
    if (!Number.isFinite(n) || n < 1) return 1
    if (n > WEEKLY_HARD_LIMIT) return WEEKLY_HARD_LIMIT
    return Math.floor(n)
  }

  const defaultChannelCount = computed(
    () => settings.value.defaultPushChannels.length + settings.value.defaultAdChannels.length,
  )

  // ---------- 差异提取（仅记录变化字段） ----------
  function diffSettings(prev: M5Settings, next: M5Settings): { field: string; oldValue: string; newValue: string }[] {
    const changes: { field: string; oldValue: string; newValue: string }[] = []
    const labels: Record<keyof M5Settings, string> = {
      weeklyLimit: '周频上限',
      quietHoursEnabled: '免打扰时段',
      quietStart: '免打扰开始',
      quietEnd: '免打扰结束',
      holidayExempt: '节日豁免',
      largeCouponThreshold: '大额券阈值',
      pushRequiresApproval: '推送需审批',
      approvalLevel: '审批层级',
      defaultPushChannels: '默认推送渠道',
      defaultAdChannels: '默认投放渠道',
    }
    const fmt = (v: unknown): string => {
      if (typeof v === 'boolean') return v ? '开' : '关'
      if (Array.isArray(v)) return v.length ? v.join('、') : '无'
      return String(v)
    }
    ;(Object.keys(labels) as (keyof M5Settings)[]).forEach((k) => {
      const a = prev[k]
      const b = next[k]
      const same = Array.isArray(a) && Array.isArray(b)
        ? a.length === b.length && a.every((x, i) => x === b[i])
        : a === b
      if (!same) {
        changes.push({ field: labels[k], oldValue: fmt(a), newValue: fmt(b) })
      }
    })
    return changes
  }

  function save(next: M5Settings): { ok: boolean; reason?: string } {
    if (!canEdit.value) return { ok: false, reason: '无保存权限' }

    const clamped: M5Settings = {
      ...next,
      weeklyLimit: clampWeeklyLimit(next.weeklyLimit),
      approvalLevel: (next.approvalLevel === 1 ? 1 : 2) as ApprovalLevel,
    }

    const changes = diffSettings(settings.value, clamped)
    if (!changes.length) return { ok: true }

    const by = auth.user?.name ?? '系统'
    changes.forEach((c) => {
      logs.value.unshift({
        id: nextId('log'),
        ...c,
        by,
        at: nowIsoMin(),
      })
    })

    settings.value = clamped
    activity.log(by, `更新营销设置：${changes.map((c) => c.field).join('、')}`)
    return { ok: true }
  }

  function resetDefault() {
    settings.value = { ...DEFAULT_SETTINGS }
  }

  function seed() {
    if (seeded.value) return
    settings.value = {
      weeklyLimit: 3,
      quietHoursEnabled: true,
      quietStart: '21:00',
      quietEnd: '09:00',
      holidayExempt: true,
      largeCouponThreshold: 1000,
      pushRequiresApproval: true,
      approvalLevel: 2,
      defaultPushChannels: ['WECOM'],
      defaultAdChannels: ['抖音', '微信私域'],
    }
    logs.value = [
      {
        id: nextId('log'),
        field: '大额券阈值', oldValue: '500', newValue: '1000',
        by: '陈野', at: offsetIsoMin(-2),
      },
      {
        id: nextId('log'),
        field: '审批层级', oldValue: '1级', newValue: '2级',
        by: '陈野', at: offsetIsoMin(-2),
      },
      {
        id: nextId('log'),
        field: '默认推送渠道', oldValue: '短信', newValue: '企业微信',
        by: '白桥', at: offsetIsoMin(-7),
      },
      {
        id: nextId('log'),
        field: '免打扰时段', oldValue: '关', newValue: '开（21:00-09:00）',
        by: '苏晴', at: offsetIsoMin(-15),
      },
    ]
    seeded.value = true
  }

  return {
    settings, logs, WEEKLY_HARD_LIMIT,
    PUSH_CHANNEL_LABEL, AD_CHANNELS,
    canEdit, defaultChannelCount,
    clampWeeklyLimit, save, resetDefault, seed,
  }
})
