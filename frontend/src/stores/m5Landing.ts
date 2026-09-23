// ============================================================
// M5-08 落地页搭建 store（已接真实 marketing-service，P5-B88）
// - 落地页：模板/状态/访问/留资/转化率；可视化组件块（头图/标题/项目卡/表单/按钮）
// - A/B 测试：为某页配置 A/B 两版，流量 50/50，对比转化
// - 提交前由 view 调 checkSensitive 拦截违禁词；服务端创建时双道复检（D6）
// 权限：landing:view / landing:edit
//
// 适配层（铁律：模板/样式零改动，只换数据源）：
//  - 字段名 pageId→id、pageName→name；formFields/blocks/variants 后端 TEXT 存
//    JSON 串在此解析；createdAt ISO 串截前 10 位
//  - 写动作经网关，后端 400 中文错误经 errMsg() 外露到视图 formError
//  - publish/offline 成功后改本地状态；moveBlock/toggleAb 本地先算保即时交互，
//    后台同步失败回滚
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useActivityStore } from '@/stores/activity'
import { useAuthStore } from '@/stores/auth'
import * as api from '@/api/landing'
import type { LandingPageRow } from '@/api/landing'

export type LandingStatus = 'DRAFT' | 'PUBLISHED' | 'OFFLINE'
export type LandingTemplate = 'NEWBIE' | 'PROJECT' | 'FESTIVAL' | 'MEMBER' | 'BRAND'
export type BlockType = 'HERO' | 'TITLE' | 'PROJECT' | 'FORM' | 'BUTTON'

export interface LandingBlock {
  id: string
  type: BlockType
  label: string
}

export interface AbVariant {
  name: string
  visits: number
  leads: number
}

export interface LandingPage {
  id: string
  name: string
  template: LandingTemplate
  status: LandingStatus
  headline: string
  subtitle: string
  project: string
  formFields: string[]
  blocks: LandingBlock[]
  visits: number
  leads: number
  abEnabled: boolean
  variants: AbVariant[]
  createdAt: string
}

export const TEMPLATE_LABEL: Record<LandingTemplate, string> = {
  NEWBIE: '新客体验', PROJECT: '项目种草', FESTIVAL: '节日促销', MEMBER: '会员日', BRAND: '品牌宣传',
}
export const STATUS_LABEL: Record<LandingStatus, string> = {
  DRAFT: '草稿', PUBLISHED: '已发布', OFFLINE: '已下线',
}
export const STATUS_PILL: Record<LandingStatus, 'draft' | 'success' | 'disabled'> = {
  DRAFT: 'draft', PUBLISHED: 'success', OFFLINE: 'disabled',
}
export const BLOCK_LABEL: Record<BlockType, string> = {
  HERO: '头图', TITLE: '标题', PROJECT: '项目卡', FORM: '表单', BUTTON: '按钮',
}
export const BLOCK_ICONS: Record<BlockType, 'marketing' | 'profile' | 'package' | 'edit' | 'check-square'> = {
  HERO: 'marketing', TITLE: 'profile', PROJECT: 'package', FORM: 'edit', BUTTON: 'check-square',
}

const DEFAULT_BLOCKS: LandingBlock[] = [
  { id: 'blk-1', type: 'HERO', label: '头图' },
  { id: 'blk-2', type: 'TITLE', label: '标题' },
  { id: 'blk-3', type: 'PROJECT', label: '项目卡' },
  { id: 'blk-4', type: 'FORM', label: '表单' },
  { id: 'blk-5', type: 'BUTTON', label: '按钮' },
]

/** 从 axios 错误中取后端中文 message（与全平台视图错误范式一致） */
function errMsg(e: unknown, fallback = '网络异常，请稍后重试'): string {
  const anyE = e as { response?: { data?: { message?: string } }; message?: string }
  return anyE?.response?.data?.message || anyE?.message || fallback
}

/** ISO 时间串 → yyyy-MM-dd */
function dayOf(s?: string | null): string {
  return s ? s.slice(0, 10) : ''
}

function parseJsonArray<T>(json: string | null | undefined, fallback: T[]): T[] {
  if (!json) return fallback
  try {
    const v: unknown = JSON.parse(json)
    return Array.isArray(v) ? (v as T[]) : fallback
  } catch {
    return fallback
  }
}

function parseBlocks(json: string | null): LandingBlock[] {
  const raw = parseJsonArray<{ id?: unknown; type?: unknown; label?: unknown }>(json, [])
  const blocks = raw
    .map((b) => ({
      id: String(b.id ?? ''),
      type: String(b.type ?? 'TITLE') as BlockType,
      label: String(b.label ?? ''),
    }))
    .filter((b) => b.id)
  return blocks.length ? blocks : DEFAULT_BLOCKS.map((b) => ({ ...b }))
}

function adaptPage(row: LandingPageRow): LandingPage {
  return {
    id: row.pageId,
    name: row.pageName,
    template: (row.template || 'NEWBIE') as LandingTemplate,
    status: (row.status || 'DRAFT') as LandingStatus,
    headline: row.headline ?? '',
    subtitle: row.subtitle ?? '',
    project: row.project ?? '',
    formFields: parseJsonArray<string>(row.formFields, ['姓名', '手机']),
    blocks: parseBlocks(row.blocks),
    visits: row.visits ?? 0,
    leads: row.leads ?? 0,
    abEnabled: !!row.abEnabled,
    variants: parseJsonArray<AbVariant>(row.variants, []),
    createdAt: dayOf(row.createdAt),
  }
}

/** 创建幂等令牌（crypto.randomUUID 不可用时回退随机串） */
function newToken(): string {
  return typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `tok-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

export const useM5LandingStore = defineStore('m5Landing', () => {
  const activity = useActivityStore()
  const auth = useAuthStore()

  const pages = ref<LandingPage[]>([])
  const selectedId = ref<string | null>(null)
  const filterStatus = ref<'ALL' | LandingStatus>('ALL')
  const seeded = ref(false)

  const filteredPages = computed(() => {
    if (filterStatus.value === 'ALL') return pages.value
    return pages.value.filter((p) => p.status === filterStatus.value)
  })

  const selected = computed(() => {
    if (selectedId.value) return pages.value.find((p) => p.id === selectedId.value) ?? null
    return pages.value[0] ?? null
  })

  function select(id: string) { selectedId.value = id }

  // KPI
  const publishedCount = computed(() => pages.value.filter((p) => p.status === 'PUBLISHED').length)
  const totalVisits = computed(() => pages.value.reduce((s, p) => s + p.visits, 0))
  const totalLeads = computed(() => pages.value.reduce((s, p) => s + p.leads, 0))
  const conversionRate = computed(() =>
    totalVisits.value ? Number(((totalLeads.value / totalVisits.value) * 100).toFixed(1)) : 0,
  )

  function get(id: string) { return pages.value.find((p) => p.id === id) ?? null }

  async function load() {
    const resp = await api.fetchLandingPages()
    pages.value = resp.data.map(adaptPage)
    if (selectedId.value && !pages.value.some((p) => p.id === selectedId.value)) {
      selectedId.value = null
    }
    seeded.value = true
  }

  async function createPage(input: {
    name: string
    template: LandingTemplate
    headline: string
    subtitle: string
    project: string
    formFields: string[]
  }): Promise<{ ok: boolean; reason?: string; page?: LandingPage }> {
    try {
      const resp = await api.createLandingPage({ ...input, clientToken: newToken() })
      const page = adaptPage(resp.data)
      const i = pages.value.findIndex((p) => p.id === page.id)
      if (i >= 0) pages.value.splice(i, 1, page)
      else pages.value.unshift(page)
      selectedId.value = page.id
      activity.log(auth.user?.name ?? '运营', `搭建落地页「${page.name}」`, page.id)
      return { ok: true, page }
    } catch (e) {
      return { ok: false, reason: errMsg(e, '创建失败，请稍后重试') }
    }
  }

  async function publish(id: string): Promise<boolean> {
    const p = pages.value.find((x) => x.id === id)
    if (!p || p.status === 'PUBLISHED') return false
    try {
      await api.publishLandingPage(id)
      p.status = 'PUBLISHED'
      activity.log(auth.user?.name ?? '运营', `发布落地页「${p.name}」`, p.id)
      return true
    } catch (e) {
      console.warn('[m5Landing] 发布失败', e)
      return false
    }
  }

  async function offline(id: string): Promise<boolean> {
    const p = pages.value.find((x) => x.id === id)
    if (!p || p.status !== 'PUBLISHED') return false
    try {
      await api.offlineLandingPage(id)
      p.status = 'OFFLINE'
      activity.log(auth.user?.name ?? '运营', `下线落地页「${p.name}」`, p.id)
      return true
    } catch (e) {
      console.warn('[m5Landing] 下线失败', e)
      return false
    }
  }

  async function moveBlock(pageId: string, blockId: string, dir: -1 | 1): Promise<boolean> {
    const p = pages.value.find((x) => x.id === pageId)
    if (!p) return false
    const i = p.blocks.findIndex((b) => b.id === blockId)
    const j = i + dir
    if (i < 0 || j < 0 || j >= p.blocks.length) return false
    const snapshot = [...p.blocks]
    const [b] = p.blocks.splice(i, 1)
    p.blocks.splice(j, 0, b)
    try {
      await api.moveLandingBlock(pageId, { blockId, direction: dir })
      return true
    } catch (e) {
      p.blocks = snapshot
      console.warn('[m5Landing] 组件块排序失败', e)
      return false
    }
  }

  async function toggleAb(id: string): Promise<boolean> {
    const p = pages.value.find((x) => x.id === id)
    if (!p) return false
    const prevAb = p.abEnabled
    const prevVariants = p.variants
    p.abEnabled = !p.abEnabled
    if (p.abEnabled && p.variants.length === 0) {
      // 演示 A/B 数据（与服务端 toggleAb 同公式）
      const base = Math.max(1, Math.round(p.visits / 2))
      p.variants = [
        { name: 'A 版（原版）', visits: base, leads: Math.round(base * (p.leads / Math.max(1, p.visits))) },
        { name: 'B 版（新文案）', visits: p.visits - base, leads: Math.round((p.visits - base) * ((p.leads / Math.max(1, p.visits)) * 1.35)) },
      ]
    }
    try {
      await api.toggleLandingAb(id)
      activity.log(auth.user?.name ?? '运营', `${p.abEnabled ? '开启' : '关闭'}「${p.name}」A/B 测试`, p.id)
      return true
    } catch (e) {
      p.abEnabled = prevAb
      p.variants = prevVariants
      console.warn('[m5Landing] A/B 开关失败', e)
      return false
    }
  }

  /** 每次进页重拉真实数据（B86 适配层范式） */
  async function seed() {
    try {
      await load()
    } catch (e) {
      console.warn('[m5Landing] 落地页加载失败', e)
    }
  }

  return {
    pages, selectedId, filterStatus, seeded,
    filteredPages, selected,
    publishedCount, totalVisits, totalLeads, conversionRate,
    select, get, createPage, publish, offline, moveBlock, toggleAb,
    TEMPLATE_LABEL, STATUS_LABEL, STATUS_PILL, BLOCK_LABEL, BLOCK_ICONS,
    seed,
  }
})
