// ============================================================
// M5-13 素材库 store
// - 素材：类型（图/文案/视频）、标签、授权范围、有效期、被引用数
// - 标签筛选；授权门店；分发到店
// - 素材可被 M5-01 活动/直播/落地页引用（演示引用计数）
// 权限：asset:view / asset:upload
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useActivityStore } from '@/stores/activity'
import { useAuthStore } from '@/stores/auth'

export type AssetType = 'IMAGE' | 'VIDEO' | 'COPY' | 'LOGO'
export type AssetScope = 'ALL' | 'SPECIFIED'

let _id = 0
function nextId(p: string) { _id += 1; return `${p}-${Date.now().toString(36)}-${_id}` }
function dayOffset(n: number) {
  const d = new Date(); d.setDate(d.getDate() + n)
  return d.toISOString().slice(0, 10)
}

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
  accent: 'brand' | 'teal' | 'orange' | 'purple' | 'blue' | 'gold'
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

const ALL_STORES = ['上海静安旗舰店', '上海徐汇店', '北京国贸店', '杭州西湖店', '深圳南山店']

export const useM5AssetsStore = defineStore('m5Assets', () => {
  const activity = useActivityStore()
  const auth = useAuthStore()

  const assets = ref<Asset[]>([])
  const filterTag = ref<string>('ALL')
  const filterType = ref<'ALL' | AssetType>('ALL')
  const selectedId = ref<string | null>(null)
  const seeded = ref(false)

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
      if (a.scope === 'ALL') ALL_STORES.forEach((s) => set.add(s))
      else a.storeNames.forEach((s) => set.add(s))
    })
    return set.size
  })

  function get(id: string) { return assets.value.find((a) => a.id === id) ?? null }

  function upload(input: {
    name: string
    type: AssetType
    tags: string[]
    scope: AssetScope
    storeNames: string[]
    expireAt: string
    content?: string
  }): Asset {
    const accents: Asset['accent'][] = ['brand', 'teal', 'orange', 'purple', 'blue', 'gold']
    const a: Asset = {
      id: nextId('ast'),
      name: input.name,
      type: input.type,
      tags: input.tags,
      scope: input.scope,
      storeNames: input.scope === 'ALL' ? [...ALL_STORES] : input.storeNames,
      expireAt: input.expireAt,
      refCount: 0,
      accent: accents[Math.floor(Math.random() * accents.length)],
      content: input.content,
      createdAt: dayOffset(0),
    }
    assets.value.unshift(a)
    selectedId.value = a.id
    activity.log(auth.user?.name ?? '运营', `上传素材「${a.name}」（${TYPE_LABEL[a.type]}）`, a.id)
    return a
  }

  function addTag(id: string, tag: string) {
    const a = assets.value.find((x) => x.id === id)
    if (a && tag.trim() && !a.tags.includes(tag.trim())) {
      a.tags.push(tag.trim())
    }
  }
  function removeTag(id: string, tag: string) {
    const a = assets.value.find((x) => x.id === id)
    if (a) {
      const i = a.tags.indexOf(tag)
      if (i >= 0) a.tags.splice(i, 1)
    }
  }

  /** 分发到店：追加授权门店 */
  function distribute(id: string, storeNames: string[]) {
    const a = assets.value.find((x) => x.id === id)
    if (!a) return
    if (a.scope === 'ALL') return
    storeNames.forEach((s) => { if (!a.storeNames.includes(s)) a.storeNames.push(s) })
    activity.log(auth.user?.name ?? '运营', `素材「${a.name}」分发到 ${storeNames.length} 家门店`, a.id)
  }

  const storeOptions = ALL_STORES.map((s) => ({ value: s, label: s }))

  function seed() {
    if (seeded.value) return
    const mk = (a: Partial<Asset> & { name: string; type: AssetType; tags: string[] }): Asset => ({
      id: nextId('ast'), scope: 'ALL', storeNames: [...ALL_STORES], expireAt: dayOffset(90),
      refCount: 0, accent: 'brand', createdAt: dayOffset(-Math.floor(Math.random() * 30)),
      ...a,
    })

    assets.value = [
      mk({ name: '暑期水光主海报', type: 'IMAGE', tags: ['暑期', '水光', '促销'], accent: 'brand', refCount: 12, expireAt: dayOffset(30) }),
      mk({ name: '新客88元体验海报', type: 'IMAGE', tags: ['新客', '体验'], accent: 'teal', refCount: 8, scope: 'SPECIFIED', storeNames: ['上海静安旗舰店', '上海徐汇店'] }),
      mk({ name: '热玛吉种草短视频', type: 'VIDEO', tags: ['热玛吉', '种草', '抗衰'], accent: 'purple', refCount: 6, expireAt: dayOffset(60) }),
      mk({ name: '光子嫩肤对比视频', type: 'VIDEO', tags: ['光子', '效果'], accent: 'orange', refCount: 4 }),
      mk({ name: '双11狂欢文案', type: 'COPY', tags: ['双11', '促销', '文案'], content: '双11 礼遇焕新，爆款项目限时直降，会员再享折上折！', accent: 'gold', refCount: 15, expireAt: dayOffset(20) }),
      mk({ name: '新客体验邀约话术', type: 'COPY', tags: ['新客', '邀约', '文案'], content: '亲爱的，新客专享88元体验套餐已为您准备好，到店即赠皮肤检测一次。', accent: 'blue', refCount: 9 }),
      mk({ name: '品牌Logo-横版', type: 'LOGO', tags: ['Logo', '品牌'], accent: 'brand', refCount: 24, expireAt: dayOffset(365) }),
      mk({ name: '品牌Logo-竖版', type: 'LOGO', tags: ['Logo', '品牌'], accent: 'teal', refCount: 18, expireAt: dayOffset(365) }),
      mk({ name: '会员日活动海报', type: 'IMAGE', tags: ['会员日', '促销'], accent: 'gold', refCount: 7, scope: 'SPECIFIED', storeNames: ['北京国贸店', '杭州西湖店'], expireAt: dayOffset(10) }),
      mk({ name: '门店环境探店视频', type: 'VIDEO', tags: ['探店', '环境'], accent: 'teal', refCount: 3 }),
    ]
    seeded.value = true
  }

  return {
    assets, filterTag, filterType, selectedId, seeded,
    allTags, tagOptions, typeOptions, filtered, selected,
    totalCount, imageCount, videoCount, authorizedStores, storeOptions,
    select, get, upload, addTag, removeTag, distribute,
    TYPE_LABEL, TYPE_ICON, SCOPE_LABEL,
    seed,
  }
})
