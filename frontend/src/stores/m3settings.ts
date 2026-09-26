// ============================================================
// M3Settings 客户域设置 store（M3-18，适配层切真）
// 数据源：GET/PUT /api/customer/m3/settings（customer-service / m3_settings + m3_settings_change_log）。
// 适配层铁律：导出签名全保留（settings/logs/dirty/canEdit/markDirty/save/resetDefault），
// M3SettingsView template/style 零改动；新增 seed() 由视图 onMounted 调用。
// save() 保持同步 boolean 签名（视图 doSave 依赖）：本地落日志 + fire-and-forget PUT 落库。
// resetDefault() 保持本地语义（复位 12 键 + dirty，由保存落库，与视图流程一致）；
// 后端 /reset-defaults 端点供 API/curl 消费。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { fetchM3Settings, putM3Settings } from '@/api/m3settings'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

export interface ChangeLog { id: string; action: string; by: string; at: string }

const DEFAULTS = {
  // 脱敏规则
  maskPhone: true,
  maskIdCard: true,
  maskPhoneInExport: true,
  decryptRequiresApproval: true,
  decryptRetentionHours: 4,
  // 等级来源
  levelSource: 'AUTO' as 'AUTO' | 'MANUAL' | 'HYBRID',
  levelCalcCycle: 'MONTHLY',
  downgradeProtectionMonths: 3,
  pointsMultiplier: 1.0,
  // 标签自动化
  autoTagDormant: true,
  dormantDays: 90,
  autoTagHighValue: true,
  highValueThreshold: 50000,
  autoTagChurnRisk: true,
  // 隐私合规
  dataRetentionMonths: 36,
  allowCrossStoreShare: false,
  enableWatermark: true,
  emrLockDays: 30,
  // 跟进规则
  autoCreateFollowTask: true,
  complaintAutoTask: true,
  npsDetractorAutoTask: true,
}

export type M3SettingsState = typeof DEFAULTS

const BOOL_KEYS = [
  'maskPhone', 'maskIdCard', 'maskPhoneInExport', 'decryptRequiresApproval',
  'autoTagDormant', 'autoTagHighValue', 'autoTagChurnRisk',
  'allowCrossStoreShare', 'enableWatermark',
  'autoCreateFollowTask', 'complaintAutoTask', 'npsDetractorAutoTask',
] as const
const NUM_KEYS = [
  'decryptRetentionHours', 'downgradeProtectionMonths', 'dormantDays',
  'highValueThreshold', 'dataRetentionMonths', 'emrLockDays', 'pointsMultiplier',
] as const

function coerceSettings(raw: Record<string, unknown>): M3SettingsState {
  const out: M3SettingsState = { ...DEFAULTS }
  for (const k of BOOL_KEYS) {
    const v = raw[k]
    if (typeof v === 'boolean') (out as Record<string, unknown>)[k] = v
  }
  for (const k of NUM_KEYS) {
    const v = raw[k]
    if (typeof v === 'number' && Number.isFinite(v)) (out as Record<string, unknown>)[k] = v
  }
  if (raw.levelSource === 'AUTO' || raw.levelSource === 'MANUAL' || raw.levelSource === 'HYBRID') out.levelSource = raw.levelSource
  if (raw.levelCalcCycle === 'MONTHLY' || raw.levelCalcCycle === 'QUARTERLY') out.levelCalcCycle = raw.levelCalcCycle
  return out
}

export const useM3SettingsStore = defineStore('m3settings', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const settings = ref<M3SettingsState>({ ...DEFAULTS })
  const logs = ref<ChangeLog[]>([])
  const dirty = ref(false)

  function markDirty() { dirty.value = true }

  async function seed() {
    try {
      const resp = await fetchM3Settings()
      settings.value = coerceSettings(resp.data.settings)
      logs.value = resp.data.logs.map((l) => ({ id: l.id, action: l.action, by: l.by, at: l.at }))
      dirty.value = false
    } catch (e) {
      console.warn('[m3settings] seed failed', e)
    }
  }

  function save() {
    if (!auth.can('m3settings:edit')) { console.warn('[m3settings] 无 m3settings:edit'); return false }
    logs.value.unshift({ id: nextId('mlog'), action: '保存客户域设置', by: auth.user.name, at: new Date().toISOString() })
    dirty.value = false
    activity.log(auth.user.name, '更新客户域设置（脱敏/等级/隐私）')
    void pushToBackend()
    return true
  }

  async function pushToBackend() {
    try {
      await putM3Settings({ ...settings.value })
    } catch (e) {
      console.warn('[m3settings] save failed', e)
    }
  }

  function resetDefault() {
    settings.value.maskPhone = true
    settings.value.maskIdCard = true
    settings.value.maskPhoneInExport = true
    settings.value.decryptRequiresApproval = true
    settings.value.decryptRetentionHours = 4
    settings.value.levelSource = 'AUTO'
    settings.value.downgradeProtectionMonths = 3
    settings.value.pointsMultiplier = 1.0
    settings.value.autoTagDormant = true
    settings.value.dormantDays = 90
    settings.value.dataRetentionMonths = 36
    settings.value.enableWatermark = true
    dirty.value = true
  }

  const canEdit = computed(() => auth.can('m3settings:edit'))

  return { settings, logs, dirty, canEdit, markDirty, save, resetDefault, seed }
})
