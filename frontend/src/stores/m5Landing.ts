// ============================================================
// M5-08 落地页搭建 store
// - 落地页：模板/状态/访问/留资/转化率；可视化组件块（头图/标题/项目卡/表单/按钮）
// - A/B 测试：为某页配置 A/B 两版，流量 50/50，对比转化
// - 提交前由 view 调 checkSensitive 拦截违禁词
// 权限：landing:view / landing:edit
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useActivityStore } from '@/stores/activity'
import { useAuthStore } from '@/stores/auth'

export type LandingStatus = 'DRAFT' | 'PUBLISHED' | 'OFFLINE'
export type LandingTemplate = 'NEWBIE' | 'PROJECT' | 'FESTIVAL' | 'MEMBER' | 'BRAND'
export type BlockType = 'HERO' | 'TITLE' | 'PROJECT' | 'FORM' | 'BUTTON'

let _id = 0
function nextId(p: string) { _id += 1; return `${p}-${Date.now().toString(36)}-${_id}` }
function dayOffset(n: number) {
  const d = new Date(); d.setDate(d.getDate() + n)
  return d.toISOString().slice(0, 10)
}

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
  { id: nextId('blk'), type: 'HERO', label: '头图' },
  { id: nextId('blk'), type: 'TITLE', label: '标题' },
  { id: nextId('blk'), type: 'PROJECT', label: '项目卡' },
  { id: nextId('blk'), type: 'FORM', label: '表单' },
  { id: nextId('blk'), type: 'BUTTON', label: '按钮' },
]

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

  function createPage(input: {
    name: string
    template: LandingTemplate
    headline: string
    subtitle: string
    project: string
    formFields: string[]
  }): LandingPage {
    const page: LandingPage = {
      id: nextId('lp'),
      name: input.name,
      template: input.template,
      status: 'DRAFT',
      headline: input.headline,
      subtitle: input.subtitle,
      project: input.project,
      formFields: input.formFields,
      blocks: DEFAULT_BLOCKS.map((b) => ({ ...b, id: nextId('blk') })),
      visits: 0,
      leads: 0,
      abEnabled: false,
      variants: [],
      createdAt: dayOffset(0),
    }
    pages.value.unshift(page)
    selectedId.value = page.id
    activity.log(auth.user?.name ?? '运营', `搭建落地页「${page.name}」`, page.id)
    return page
  }

  function publish(id: string) {
    const p = pages.value.find((x) => x.id === id)
    if (p && p.status !== 'PUBLISHED') {
      p.status = 'PUBLISHED'
      activity.log(auth.user?.name ?? '运营', `发布落地页「${p.name}」`, p.id)
    }
  }
  function offline(id: string) {
    const p = pages.value.find((x) => x.id === id)
    if (p && p.status === 'PUBLISHED') {
      p.status = 'OFFLINE'
      activity.log(auth.user?.name ?? '运营', `下线落地页「${p.name}」`, p.id)
    }
  }

  function moveBlock(pageId: string, blockId: string, dir: -1 | 1) {
    const p = pages.value.find((x) => x.id === pageId)
    if (!p) return
    const i = p.blocks.findIndex((b) => b.id === blockId)
    const j = i + dir
    if (i < 0 || j < 0 || j >= p.blocks.length) return
    const [b] = p.blocks.splice(i, 1)
    p.blocks.splice(j, 0, b)
  }

  function toggleAb(id: string) {
    const p = pages.value.find((x) => x.id === id)
    if (!p) return
    p.abEnabled = !p.abEnabled
    if (p.abEnabled && p.variants.length === 0) {
      // 演示 A/B 数据
      const base = Math.max(1, Math.round(p.visits / 2))
      p.variants = [
        { name: 'A 版（原版）', visits: base, leads: Math.round(base * (p.leads / Math.max(1, p.visits))) },
        { name: 'B 版（新文案）', visits: p.visits - base, leads: Math.round((p.visits - base) * ((p.leads / Math.max(1, p.visits)) * 1.35)) },
      ]
    }
    activity.log(auth.user?.name ?? '运营', `${p.abEnabled ? '开启' : '关闭'}「${p.name}」A/B 测试`, p.id)
  }

  function seed() {
    if (seeded.value) return
    const blocks = () => DEFAULT_BLOCKS.map((b) => ({ ...b, id: nextId('blk') }))
    pages.value = [
      { id: nextId('lp'), name: '新客88元体验页', template: 'NEWBIE', status: 'PUBLISHED', headline: '新客专享 88 元体验', subtitle: '到店即赠皮肤检测一次', project: '皮肤检测+小气泡', formFields: ['姓名', '手机', '意向项目'], blocks: blocks(), visits: 12800, leads: 860, abEnabled: false, variants: [], createdAt: dayOffset(-1) },
      { id: nextId('lp'), name: '热玛吉抗衰专场', template: 'PROJECT', status: 'PUBLISHED', headline: '热玛吉 FLX 紧致提拉', subtitle: '正版仪器 院长定制', project: '热玛吉面部', formFields: ['姓名', '手机'], blocks: blocks(), visits: 8600, leads: 420, abEnabled: true, variants: [
        { name: 'A 版（原版）', visits: 4300, leads: 180 },
        { name: 'B 版（新文案）', visits: 4300, leads: 286 },
      ], createdAt: dayOffset(-3) },
      { id: nextId('lp'), name: '双11狂欢主会场', template: 'FESTIVAL', status: 'PUBLISHED', headline: '双11 礼遇焕新', subtitle: '爆款项目限时直降', project: '水光年卡', formFields: ['姓名', '手机', '意向项目'], blocks: blocks(), visits: 24600, leads: 1240, abEnabled: false, variants: [], createdAt: dayOffset(-5) },
      { id: nextId('lp'), name: '周三会员日', template: 'MEMBER', status: 'DRAFT', headline: '会员日 双倍积分', subtitle: '每周三专属福利', project: '会员日到店礼', formFields: ['姓名', '手机'], blocks: blocks(), visits: 0, leads: 0, abEnabled: false, variants: [], createdAt: dayOffset(-10) },
      { id: nextId('lp'), name: '品牌故事页', template: 'BRAND', status: 'OFFLINE', headline: '匠心医美 十年品牌', subtitle: '正规机构 专业医师', project: '品牌宣传', formFields: ['姓名', '手机'], blocks: blocks(), visits: 3200, leads: 86, abEnabled: false, variants: [], createdAt: dayOffset(-20) },
    ]
    seeded.value = true
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
