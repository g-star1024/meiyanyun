import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useActivityStore } from '@/stores/activity'
import { useAuthStore } from '@/stores/auth'
import {
  getMarketingConfig,
  saveMarketingConfig,
  type MarketingCfgDTO,
} from '@/api/marketing'
import { listStaff } from '@/api/org'
import client from '@/api/client'

// ============================================================
// M5-15 营销设置 store（接真实 API：GET/POST /api/marketing/config）
// - 触达频率（周频上限≤3 硬约束 / 免打扰时段 / 节日豁免）
// - 合规词库（违禁词维护在视图内直接调 /marketing/forbidden-words）
// - 审批流（大额券阈值 / 推送审批 / 审批层级）
// - 默认渠道（默认推送/投放）
// - 变更审计日志（真实 audit 链：GET /api/audit 全量拉取后前端过滤 MARKETING_CFG）
// 金额口径：后端 largeCouponThresholdFen bigint 存「分」，本 store/页面用「元」。
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

// -------------------- 适配层（后端 DTO ↔ 页面活规格） --------------------

const fen2yuan = (f: number | null | undefined): number => (f == null ? 0 : f / 100)
const yuan2fen = (y: number): number => Math.round(y * 100)

function parseChannels(json: string | null | undefined, fallback: string[]): string[] {
  if (!json) return [...fallback]
  try {
    const arr = JSON.parse(json)
    return Array.isArray(arr) ? arr.map(String) : [...fallback]
  } catch {
    return [...fallback]
  }
}

/** DTO → 页面 M5Settings；后端空值回落前端默认（与 Initializer 默认口径一致）。 */
function adapt(dto: MarketingCfgDTO | null | undefined): M5Settings {
  if (!dto) return { ...DEFAULT_SETTINGS, defaultPushChannels: [...DEFAULT_SETTINGS.defaultPushChannels], defaultAdChannels: [...DEFAULT_SETTINGS.defaultAdChannels] }
  return {
    weeklyLimit: dto.weeklyPushLimit ?? DEFAULT_SETTINGS.weeklyLimit,
    quietHoursEnabled: dto.quietHoursEnabled ?? DEFAULT_SETTINGS.quietHoursEnabled,
    quietStart: dto.quietStart ?? DEFAULT_SETTINGS.quietStart,
    quietEnd: dto.quietEnd ?? DEFAULT_SETTINGS.quietEnd,
    holidayExempt: dto.holidayExempt ?? DEFAULT_SETTINGS.holidayExempt,
    largeCouponThreshold: dto.largeCouponThresholdFen != null
      ? fen2yuan(dto.largeCouponThresholdFen)
      : DEFAULT_SETTINGS.largeCouponThreshold,
    pushRequiresApproval: dto.pushRequiresApproval ?? DEFAULT_SETTINGS.pushRequiresApproval,
    approvalLevel: (dto.approvalLevel === 1 ? 1 : 2) as ApprovalLevel,
    defaultPushChannels: parseChannels(dto.defaultPushChannels, DEFAULT_SETTINGS.defaultPushChannels) as PushChannel[],
    defaultAdChannels: parseChannels(dto.defaultAdChannels, DEFAULT_SETTINGS.defaultAdChannels),
  }
}

interface AuditChainRow {
  id: number
  bizType: string
  txnNo: string | null
  actor: string
  action: string
  payload: string
  createdAt: string
}

export const useM5SettingsStore = defineStore('m5Settings', () => {
  const activity = useActivityStore()
  const auth = useAuthStore()

  const settings = ref<M5Settings>({ ...DEFAULT_SETTINGS, defaultPushChannels: [...DEFAULT_SETTINGS.defaultPushChannels], defaultAdChannels: [...DEFAULT_SETTINGS.defaultAdChannels] })
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
  const FIELD_LABELS: Record<keyof M5Settings, string> = {
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

  const fmtVal = (v: unknown): string => {
    if (typeof v === 'boolean') return v ? '开' : '关'
    if (Array.isArray(v)) {
      if (!v.length) return '无'
      // 推送渠道码转中文；投放渠道本身即中文
      return v.map((x) => PUSH_CHANNEL_LABEL[x as PushChannel] ?? String(x)).join('、')
    }
    return String(v)
  }

  function diffSettings(prev: M5Settings, next: M5Settings): { field: string; oldValue: string; newValue: string }[] {
    const changes: { field: string; oldValue: string; newValue: string }[] = []
    ;(Object.keys(FIELD_LABELS) as (keyof M5Settings)[]).forEach((k) => {
      const a = prev[k]
      const b = next[k]
      const same = Array.isArray(a) && Array.isArray(b)
        ? a.length === b.length && a.every((x, i) => x === b[i])
        : a === b
      if (!same) {
        changes.push({ field: FIELD_LABELS[k], oldValue: fmtVal(a), newValue: fmtVal(b) })
      }
    })
    return changes
  }

  /** 保存：提交后端 POST /config（四件套在后端），成功后重拉配置 + 刷新审计卡。 */
  async function save(next: M5Settings): Promise<{ ok: boolean; reason?: string }> {
    if (!canEdit.value) return { ok: false, reason: '无保存权限' }

    const clamped: M5Settings = {
      ...next,
      weeklyLimit: clampWeeklyLimit(next.weeklyLimit),
      approvalLevel: (next.approvalLevel === 1 ? 1 : 2) as ApprovalLevel,
    }

    try {
      await saveMarketingConfig({
        weeklyLimit: clamped.weeklyLimit,
        quietHoursEnabled: clamped.quietHoursEnabled,
        quietStart: clamped.quietStart,
        quietEnd: clamped.quietEnd,
        holidayExempt: clamped.holidayExempt,
        largeCouponThresholdFen: yuan2fen(clamped.largeCouponThreshold),
        pushRequiresApproval: clamped.pushRequiresApproval,
        approvalLevel: clamped.approvalLevel,
        defaultPushChannels: clamped.defaultPushChannels,
        defaultAdChannels: clamped.defaultAdChannels,
      })
    } catch (e) {
      return { ok: false, reason: errText(e) }
    }

    // 成功后重拉真实配置与审计链（审计以服务端链为准）
    await seed(true)
    const changes = diffSettings(settings.value, clamped)
    activity.log(auth.user?.name ?? '系统', `更新营销设置：${changes.map((c) => c.field).join('、') || '配置已保存'}`)
    return { ok: true }
  }

  function resetDefault() {
    settings.value = { ...DEFAULT_SETTINGS, defaultPushChannels: [...DEFAULT_SETTINGS.defaultPushChannels], defaultAdChannels: [...DEFAULT_SETTINGS.defaultAdChannels] }
  }

  /** 拉取真实配置 + 营销配置审计链；force=true 时强制重拉（保存后刷新用）。 */
  async function seed(force = false) {
    if (seeded.value && !force) return
    try {
      const { data } = await getMarketingConfig()
      settings.value = adapt(data)
    } catch (e) {
      console.error('营销设置加载失败', e)
    }
    await loadAuditLogs()
    seeded.value = true
  }

  /** 审计卡：拉 audit 全链，按 bizType=MARKETING_CFG 过滤，按时间倒序；工号解析为姓名。 */
  async function loadAuditLogs() {
    try {
      const { data } = await client.get<AuditChainRow[]>('/audit')
      const rows = data
        .filter((r) => r.bizType === 'MARKETING_CFG')
        .sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1))
      const nameMap = await resolveActorNames(rows.map((r) => r.actor))
      logs.value = rows.map((r, i) => {
        // 倒序链：rows[i+1] 即本条的上一条（更早）快照，用于 old→new 字段级 diff
        const changes = payloadToChanges(r.payload, rows[i + 1]?.payload)
        return {
          id: `audit-${r.id}`,
          field: changes.length ? changes.map((c) => c.field).join('、') : '营销设置',
          oldValue: changes.map((c) => c.oldValue).join('；') || '—',
          newValue: changes.map((c) => c.newValue).join('；') || '已保存',
          by: nameMap(r.actor),
          at: fmtTime(r.createdAt),
        }
      })
    } catch (e) {
      // 无 audit:view 权限或 audit 服务不可用：审计卡静默为空，不阻断设置页
      console.warn('营销设置审计链加载失败（可能无 audit:view 权限）', e)
      logs.value = []
    }
  }

  /**
   * 把审计 payload（全字段快照）展开成字段级中文变更：
   * 与上一条（更早）快照逐字段 diff，只列实际发生变化的字段；
   * 无更早快照（链首/首次保存）时 old 统一记 '—'、new 列全字段。
   */
  function payloadToChanges(payload: string, prevPayload?: string): { field: string; oldValue: string; newValue: string }[] {
    let snap: Record<string, unknown>
    let prev: Record<string, unknown> | null = null
    try {
      snap = JSON.parse(payload)
    } catch {
      return []
    }
    if (prevPayload) {
      try {
        prev = JSON.parse(prevPayload)
      } catch {
        prev = null
      }
    }
    const norm = (jsonKey: string, v: unknown): unknown =>
      jsonKey === 'largeCouponThresholdFen' ? fen2yuan(Number(v)) : v
    const out: { field: string; oldValue: string; newValue: string }[] = []
    const map: Record<string, keyof M5Settings> = {
      weeklyLimit: 'weeklyLimit',
      quietHoursEnabled: 'quietHoursEnabled',
      quietStart: 'quietStart',
      quietEnd: 'quietEnd',
      holidayExempt: 'holidayExempt',
      largeCouponThresholdFen: 'largeCouponThreshold',
      pushRequiresApproval: 'pushRequiresApproval',
      approvalLevel: 'approvalLevel',
      defaultPushChannels: 'defaultPushChannels',
      defaultAdChannels: 'defaultAdChannels',
    }
    Object.entries(map).forEach(([jsonKey, settingKey]) => {
      if (!(jsonKey in snap)) return
      const newV = norm(jsonKey, snap[jsonKey])
      const oldV = prev && jsonKey in prev ? norm(jsonKey, prev[jsonKey]) : undefined
      // 有上一条快照：仅列变化字段；无上一条：全字段列示、old 记 '—'
      if (prev && oldV !== undefined && fmtVal(oldV) === fmtVal(newV)) return
      out.push({
        field: FIELD_LABELS[settingKey],
        oldValue: oldV === undefined ? '—' : fmtVal(oldV),
        newValue: fmtVal(newV),
      })
    })
    return out
  }

  async function resolveActorNames(actors: string[]): Promise<(id: string) => string> {
    const map = new Map<string, string>()
    // 当前登录人先用 auth 里的姓名
    if (auth.user?.staffId && auth.user?.name) map.set(auth.user.staffId, auth.user.name)
    const need = actors.filter((a) => a && a !== 'system' && !map.has(a))
    if (need.length) {
      try {
        const { data } = await listStaff()
        data.forEach((s) => {
          if (need.includes(s.staffId)) map.set(s.staffId, s.staffName)
        })
      } catch {
        // 姓名解析失败回落工号
      }
    }
    return (id: string) => {
      if (id === 'system') return '系统'
      return map.get(id) ?? id
    }
  }

  function fmtTime(iso: string): string {
    const d = new Date(iso)
    if (Number.isNaN(d.getTime())) return iso
    const pad = (n: number) => String(n).padStart(2, '0')
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
  }

  function errText(e: unknown): string {
    const anyE = e as { response?: { data?: { message?: string; error?: string } }; message?: string }
    return anyE?.response?.data?.message || anyE?.message || '保存失败'
  }

  return {
    settings, logs, WEEKLY_HARD_LIMIT,
    PUSH_CHANNEL_LABEL, AD_CHANNELS,
    canEdit, defaultChannelCount,
    clampWeeklyLimit, save, resetDefault, seed,
  }
})
