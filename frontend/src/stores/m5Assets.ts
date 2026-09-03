// ============================================================
// M5-13 素材库 store
// - 素材：类型（图/文案/视频）、标签、授权范围、有效期、被引用数
// - 标签筛选；授权门店；分发到店
// - 素材可被 M5-01 活动/直播/落地页引用（演示引用计数）
// 权限：asset:view / asset:upload
// 数据：真实 /api/marketing/assets（marketing-service）
// 门店：真实门店主数据（storeContext），scope=ALL 按营业中门店展开
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import * as api from '@/api/marketing'
import type { MarketingAssetDTO } from '@/api/marketing'
import { useActivityStore } from '@/stores/activity'
import { useAuthStore } from '@/stores/auth'
import { useStoreContext } from '@/stores/storeContext'

export type AssetType = 'IMAGE' | 'VIDEO' | 'COPY' | 'LOGO'
export type AssetScope = 'ALL' | 'SPECIFIED'
export type AssetAccent = 'brand' | 'teal' | 'orange' | 'purple' | 'blue' | 'gold'

export interface Asset {
  id: string
  name: string
  type: AssetType
  tags: string[]
  scope: AssetScope
  storeNames: string[]
  expireAt: string
  refCount: number
  /** 色块主题（仅引用 token 变量名，view 映射） */
  accent: AssetAccent
  content?: string // 文案素材内容
  createdAt: string
}

export const TYPE_LABEL: Record<AssetType, string> = {
  IMAGE: '图片', VIDEO: '视频', COPY: '文案', LOGO: 'Logo',
}
export const TYPE_ICON: Record<AssetType, 'marketing' | 'volume' | 'edit' | 'sign'> = {
  IMAGE: 'marketing', VIDEO: 'volume', COPY: 'edit', LOGO: 'sign',
}
export const SCOPE_LABEL: Record<AssetScope, string> = {
  ALL: '全部门店', SPECIFIED: '指定门店',
}

const ACCENTS: AssetAccent[] = ['brand', 'teal', 'orange', 'purple', 'blue', 'gold']

function dayOf(s: string | null | undefined): string {
  return s ? s.slice(0, 10) : ''
}
function jsonArr(s: string | null | undefined): string[] {
  if (!s) return []
  try {
    const v = JSON.parse(s)
    return Array.isArray(v) ? v.map((x) => String(x)) : []
  } catch {
    return []
  }
}

export const useM5AssetsStore = defineStore('m5Assets', () => {
  const activity = useActivityStore()
  const auth = useAuthStore()
  const storeCtx = useStoreContext()

  const assets = ref<Asset[]>([])
  const filterTag = ref<string>('ALL')
  const filterType = ref<'ALL' | AssetType>('ALL')
  const selectedId = ref<string | null>(null)
  const loaded = ref(false)

  /** 营业中门店（素材可授权/分发的范围；筹建中门店不下发素材） */
  const activeStores = computed(() => storeCtx.stores.filter((s) => s.status === '营业中'))
  const activeStoreNames = computed(() => activeStores.value.map((s) => s.storeName))
  function nameOf(code: string): string {
    return storeCtx.stores.find((s) => s.storeCode === code)?.storeName ?? code
  }

  function adaptAsset(d: MarketingAssetDTO): Asset {
    const codes = jsonArr(d.storeCodes)
    return {
      id: d.assetId,
      name: d.assetName,
      type: (d.type || 'IMAGE') as AssetType,
      tags: jsonArr(d.tags),
      scope: d.scope === 'SPECIFIED' ? 'SPECIFIED' : 'ALL',
      storeNames: d.scope === 'SPECIFIED' ? codes.map(nameOf) : [...activeStoreNames.value],
      expireAt: dayOf(d.expireAt),
      refCount: d.refCount ?? 0,
      accent: (d.accent as AssetAccent) || 'brand',
      content: d.content || undefined,
      createdAt: dayOf(d.createdAt),
    }
  }

  const allTags = computed(() => {
    const set = new Set<string>()
    assets.value.forEach((a) => a.tags.forEach((t) => set.add(t)))
    return [...set]
  })

  const tagOptions = computed(() => [
    { value: 'ALL', label: '全部标签' },
    ...allTags.value.map((t) => ({ value: t, label: t })),
  ])
  const typeOptions = [
    { value: 'ALL', label: '全部类型' },
    { value: 'IMAGE', label: '海报' },
    { value: 'VIDEO', label: '视频' },
    { value: 'COPY', label: '文案' },
    { value: 'LOGO', label: 'Logo' },
  ]

  const filtered = computed(() => {
    return assets.value.filter((a) => {
      if (filterType.value !== 'ALL' && a.type !== filterType.value) return false
      if (filterTag.value !== 'ALL' && !a.tags.includes(filterTag.value)) return false
      return true
    })
  })

  const selected = computed(() => {
    if (selectedId.value) return assets.value.find((a) => a.id === selectedId.value) ?? null
    return assets.value[0] ?? null
  })
  function select(id: string) { selectedId.value = id }

  // KPI
  const totalCount = computed(() => assets.value.length)
  const imageCount = computed(() => assets.value.filter((a) => a.type === 'IMAGE').length)
  const videoCount = computed(() => assets.value.filter((a) => a.type === 'VIDEO').length)
  const authorizedStores = computed(() => {
    const set = new Set<string>()
    assets.value.forEach((a) => {
      if (a.scope === 'ALL') activeStoreNames.value.forEach((s) => set.add(s))
      else a.storeNames.forEach((s) => set.add(s))
    })
    return set.size
  })

  function get(id: string) { return assets.value.find((a) => a.id === id) ?? null }

  async function upload(input: {
    name: string
    type: AssetType
    tags: string[]
    scope: AssetScope
    storeNames: string[]
    expireAt: string
    content?: string
  }): Promise<Asset> {
    if (!auth.can('asset:upload')) throw new Error('无素材上传权限')
    const codes = input.scope === 'ALL'
      ? []
      : input.storeNames
          .map((n) => storeCtx.stores.find((s) => s.storeName === n)?.storeCode)
          .filter((c): c is string => !!c)
    const res = await api.createAsset({
      name: input.name,
      type: input.type,
      tags: input.tags,
      scope: input.scope,
      storeCodes: codes,
      expireAt: input.expireAt || null,
      accent: ACCENTS[Math.floor(Math.random() * ACCENTS.length)],
      content: input.content || null,
    })
    activity.log(auth.user?.name ?? '运营', `上传素材「${input.name}」（${TYPE_LABEL[input.type]}）`, res.data.assetId)
    await seed(true)
    return assets.value.find((a) => a.id === res.data.assetId) ?? adaptAsset(res.data)
  }

  async function addTag(id: string, tag: string) {
    const t = tag.trim()
    const a = assets.value.find((x) => x.id === id)
    if (!a || !t || a.tags.includes(t)) return
    const res = await api.addAssetTag(id, t)
    await seed(true)
    if (res.data.changed) {
      activity.log(auth.user?.name ?? '运营', `素材「${a.name}」添加标签「${t}」`, id)
    }
  }
  async function removeTag(id: string, tag: string) {
    const a = assets.value.find((x) => x.id === id)
    if (!a) return
    const res = await api.removeAssetTag(id, tag)
    await seed(true)
    if (res.data.changed) {
      activity.log(auth.user?.name ?? '运营', `素材「${a.name}」移除标签「${tag}」`, id)
    }
  }

  /** 分发到店：追加授权门店（入参为门店名，view 复选框绑定口径） */
  async function distribute(id: string, storeNames: string[]) {
    if (!auth.can('asset:upload')) throw new Error('无素材分发权限')
    const a = assets.value.find((x) => x.id === id)
    if (!a) return
    if (a.scope === 'ALL') return
    const codes = storeNames
      .map((n) => storeCtx.stores.find((s) => s.storeName === n)?.storeCode)
      .filter((c): c is string => !!c)
    if (!codes.length) return
    const res = await api.distributeAsset(id, codes)
    await seed(true)
    if (res.data.changed) {
      const names = codes.map(nameOf).join('、')
      activity.log(auth.user?.name ?? '运营', `素材「${a.name}」分发到 ${codes.length} 家门店（${names}）`, id)
    }
  }

  const storeOptions = computed(() => activeStores.value.map((s) => ({ value: s.storeName, label: s.storeName })))

  async function seed(force = false) {
    if (loaded.value && !force) return
    await storeCtx.loadStores()
    const res = await api.listAssets()
    assets.value = (res.data || []).map(adaptAsset)
    loaded.value = true
  }

  return {
    assets, filterTag, filterType, selectedId, loaded,
    allTags, tagOptions, typeOptions, filtered, selected,
    totalCount, imageCount, videoCount, authorizedStores, storeOptions,
    select, get, upload, addTag, removeTag, distribute,
    TYPE_LABEL, TYPE_ICON, SCOPE_LABEL,
    seed,
  }
})
