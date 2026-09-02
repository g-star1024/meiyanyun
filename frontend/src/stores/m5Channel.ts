import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useM5CoreStore, type ChannelKey, type ChannelPerf } from '@/stores/m5Core'
import { useActivityStore } from '@/stores/activity'
import { useAuthStore } from '@/stores/auth'

// ============================================================
// M5-07 渠道管理 store
// - 业绩数据全部消费 m5Core.channels，不重复维护
// - 本 store 仅补：渠道配置元数据（类型/对账日/密钥/对账状态）
// - 编辑：调 m5Core.updateChannel
// ============================================================

export type ChannelType = 'PLATFORM' | 'SOCIAL' | 'PRIVATE' | 'REFERRAL'
export type ReconcileStatus = 'SYNCED' | 'PENDING' | 'DISCONNECTED'

export const CHANNEL_TYPE_LABEL: Record<ChannelType, string> = {
  PLATFORM: '本地生活平台',
  SOCIAL: '内容社媒',
  PRIVATE: '私域',
  REFERRAL: '转介绍',
}
export const CHANNEL_TYPE_PILL: Record<ChannelType, 'primary' | 'info' | 'success' | 'warning'> = {
  PLATFORM: 'primary',
  SOCIAL: 'info',
  PRIVATE: 'success',
  REFERRAL: 'warning',
}
export const RECONCILE_LABEL: Record<ReconcileStatus, string> = {
  SYNCED: '已同步',
  PENDING: '待对账',
  DISCONNECTED: '未接入',
}
export const RECONCILE_PILL: Record<ReconcileStatus, 'success' | 'warning' | 'disabled'> = {
  SYNCED: 'success',
  PENDING: 'warning',
  DISCONNECTED: 'disabled',
}

export interface ChannelMeta {
  key: ChannelKey
  type: ChannelType
  settlementDay: number // 每月对账日
  apiKey: string
  reconcile: ReconcileStatus
}

const META_SEED: Omit<ChannelMeta, 'key'>[] = [
  { type: 'PLATFORM', settlementDay: 5, apiKey: 'mt_sk_live_8f3a9c2b1e', reconcile: 'SYNCED' },
  { type: 'SOCIAL', settlementDay: 8, apiKey: 'dy_sk_live_a71d4e09c2', reconcile: 'PENDING' },
  { type: 'SOCIAL', settlementDay: 10, apiKey: 'xhs_sk_live_52be0f77a1', reconcile: 'SYNCED' },
  { type: 'PLATFORM', settlementDay: 6, apiKey: 'dp_sk_live_c4e91ab20d', reconcile: 'PENDING' },
  { type: 'PLATFORM', settlementDay: 12, apiKey: '', reconcile: 'DISCONNECTED' },
  { type: 'REFERRAL', settlementDay: 1, apiKey: '—', reconcile: 'SYNCED' },
  { type: 'PRIVATE', settlementDay: 1, apiKey: '—', reconcile: 'SYNCED' },
]

let _id = 0
function nextId(p: string) { _id += 1; return `${p}-${Date.now().toString(36)}-${_id}` }

export interface ChannelRow extends ChannelPerf {
  type: ChannelType
  typeLabel: string
  settlementDay: number
  apiKey: string
  reconcile: ReconcileStatus
  reconcileLabel: string
  conversion: number // 线索→成交 转化率 %
  commission: number
  cpl: number // 单线索成本
}

export const useM5ChannelStore = defineStore('m5Channel', () => {
  const core = useM5CoreStore()
  const activity = useActivityStore()
  const auth = useAuthStore()

  const metas = ref<ChannelMeta[]>([])
  const seeded = ref(false)

  const rows = computed<ChannelRow[]>(() =>
    core.channels.map((c, i) => {
      const m = metas.value[i]
      const type = m?.type ?? 'PLATFORM'
      const connected = c.connected
      const reconcile: ReconcileStatus = !connected
        ? 'DISCONNECTED'
        : (m?.reconcile ?? 'PENDING')
      return {
        ...c,
        type,
        typeLabel: CHANNEL_TYPE_LABEL[type],
        settlementDay: m?.settlementDay ?? 1,
        apiKey: m?.apiKey ?? '',
        reconcile,
        reconcileLabel: RECONCILE_LABEL[reconcile],
        conversion: c.leads > 0 ? Number(((c.deals / c.leads) * 100).toFixed(1)) : 0,
        commission: Math.round(c.revenue * c.commissionRate),
        cpl: c.adCost > 0 && c.leads > 0 ? Math.round(c.adCost / c.leads) : 0,
      }
    }),
  )

  const connectedCount = computed(() => rows.value.filter((r) => r.connected).length)
  const monthlyLeads = computed(() => rows.value.reduce((s, r) => s + r.leads, 0))
  const monthlyDeals = computed(() => rows.value.reduce((s, r) => s + r.deals, 0))
  const commissionTotal = computed(() => rows.value.reduce((s, r) => s + r.commission, 0))

  const pendingCount = computed(() => rows.value.filter((r) => r.reconcile === 'PENDING').length)
  const syncedCount = computed(() => rows.value.filter((r) => r.reconcile === 'SYNCED').length)

  // 业绩归集：按渠道汇总
  const collectionRows = computed(() =>
    rows.value
      .filter((r) => r.connected)
      .map((r) => ({
        key: r.key,
        name: r.name,
        leads: r.leads,
        deals: r.deals,
        conversion: r.conversion,
        revenue: r.revenue,
      })),
  )

  function findMeta(key: ChannelKey) {
    const idx = core.channels.findIndex((c) => c.key === key)
    return idx >= 0 ? metas.value[idx] : undefined
  }

  function updateChannelConfig(
    key: ChannelKey,
    patch: { name?: string; commissionRate?: number; settlementDay?: number; apiKey?: string },
  ) {
    if (!auth.can('channel:edit')) throw new Error('无渠道编辑权限')
    const idx = core.channels.findIndex((c) => c.key === key)
    if (idx < 0) return
    if (patch.name !== undefined || patch.commissionRate !== undefined) {
      core.updateChannel(key, {
        ...(patch.name !== undefined ? { name: patch.name } : {}),
        ...(patch.commissionRate !== undefined ? { commissionRate: patch.commissionRate } : {}),
      })
    }
    const m = metas.value[idx]
    if (m) {
      if (patch.settlementDay !== undefined) m.settlementDay = patch.settlementDay
      if (patch.apiKey !== undefined) m.apiKey = patch.apiKey
      if (!m.apiKey && core.channels[idx].connected) m.reconcile = 'PENDING'
    }
    const who = auth.user?.name ?? '系统'
    activity.log(who, `更新渠道「${core.channels[idx].name}」配置`, nextId('chlog'))
  }

  function connectChannel(key: ChannelKey) {
    if (!auth.can('channel:edit')) throw new Error('无渠道编辑权限')
    const idx = core.channels.findIndex((c) => c.key === key)
    if (idx < 0) return
    core.updateChannel(key, { connected: true })
    if (metas.value[idx]) {
      metas.value[idx].reconcile = 'PENDING'
      if (!metas.value[idx].apiKey) {
        metas.value[idx].apiKey = `${key}_sk_live_demo_${Date.now().toString(36)}`
      }
    }
    activity.log(auth.user?.name ?? '系统', `接入渠道「${core.channels[idx].name}」`, nextId('chlog'))
  }

  function seed() {
    if (seeded.value) return
    core.seed()
    metas.value = core.channels.map((c, i) => ({
      key: c.key,
      ...META_SEED[i],
    }))
    seeded.value = true
  }

  return {
    rows,
    connectedCount,
    monthlyLeads,
    monthlyDeals,
    commissionTotal,
    pendingCount,
    syncedCount,
    collectionRows,
    CHANNEL_TYPE_LABEL,
    CHANNEL_TYPE_PILL,
    RECONCILE_LABEL,
    RECONCILE_PILL,
    findMeta,
    updateChannelConfig,
    connectChannel,
    seed,
  }
})
