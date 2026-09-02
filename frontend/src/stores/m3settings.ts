// ============================================================
// M3Settings 客户域设置 store（M3-18）
// 客户域参数：脱敏规则、等级来源、标签自动化、隐私合规等。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

export interface ChangeLog { id: string; action: string; by: string; at: string }

export const useM3SettingsStore = defineStore('m3settings', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const settings = ref({
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
  })

  const logs = ref<ChangeLog[]>([
    { id: nextId('mlog'), action: '初始化客户域设置', by: '系统初始化', at: '2026-03-01T09:00:00.000Z' },
    { id: nextId('mlog'), action: '沉睡阈值调整为 90 天', by: '陈野（区域经理）', at: '2026-06-15T14:20:00.000Z' },
    { id: nextId('mlog'), action: '开启手机号导出脱敏', by: '陈野（区域经理）', at: '2026-07-02T10:05:00.000Z' },
  ])

  const dirty = ref(false)
  function markDirty() { dirty.value = true }

  function save() {
    if (!auth.can('m3settings:edit')) { console.warn('[m3settings] 无 m3settings:edit'); return false }
    logs.value.unshift({ id: nextId('mlog'), action: '保存客户域设置', by: auth.user.name, at: new Date().toISOString() })
    dirty.value = false
    activity.log(auth.user.name, '更新客户域设置（脱敏/等级/隐私）')
    return true
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

  return { settings, logs, dirty, canEdit, markDirty, save, resetDefault }
})
