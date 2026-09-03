// ============================================================
// M5-04 裂变海报 store（已接真实 marketing-service）
// - 海报模板（节日促销/新客礼/项目种草/会员日/转介绍/直播预约）
// - 已生成海报：绑定推荐人、漏斗（分享→扫码→留资→到店→成交）、佣金试算
// - 提交前由 view 调 useSensitiveWords.checkSensitive 拦截违禁词
// 权限：poster:view / poster:edit
//
// 适配层（铁律：模板/样式零改动，只换数据源）：
//  - 后端 dealAmount bigint 存「分」，前端活规格用「元」：fen2yuan
//  - 后端 commissionRate = 百分比×10（5% = 50），前端用 0~1：rate50↔view（×1000 / ÷1000）
//  - 字段名 templateId/posterId → id、templateName → name
//  - 推荐人选项仍从转介绍关系链（referral mock）派生
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useActivityStore } from '@/stores/activity'
import { useAuthStore } from '@/stores/auth'
import { useReferralStore } from '@/stores/referral'
import * as api from '@/api/marketing'
import type { PosterTemplateDTO, PosterRecordDTO } from '@/api/marketing'
import { fen2yuan } from '@/stores/m5Coupon'

export type PosterStyle = 'FESTIVAL' | 'NEWBIE' | 'PROJECT' | 'MEMBER' | 'REFERRAL' | 'LIVE'
export type PosterStatus = 'ENABLED' | 'DISABLED'
export type PosterStage = 'DRAFT' | 'PUBLISHED'

/** yyyy-MM-dd（OffsetDateTime / LocalDate 均可直接截取） */
function dayOf(s?: string | null): string {
  return s ? s.slice(0, 10) : ''
}
/** 后端佣金（百分比×10，如 50 = 5%）→ 前端 0~1（0.05） */
function rateView(v?: number | null): number {
  return v ? v / 1000 : 0
}
/** 前端佣金 0~1（0.05）→ 后端（50） */
function rate50(v: number): number {
  return Math.round((v || 0) * 1000)
}

export interface PosterTemplate {
  id: string
  name: string
  style: PosterStyle
  status: PosterStatus
  uses: number
  /** 色块主题（仅引用 token 变量名，view 映射） */
  accent: 'brand' | 'teal' | 'orange' | 'purple' | 'blue' | 'gold'
  defaultTitle: string
  defaultSubtitle: string
}

export interface PosterFunnel {
  share: number
  scan: number
  lead: number
  visit: number
  deal: number
}

export interface Poster {
  id: string
  templateId: string
  templateName: string
  style: PosterStyle
  accent: PosterTemplate['accent']
  title: string
  subtitle: string
  project: string
  referrerName: string
  status: PosterStage
  funnel: PosterFunnel
  /** 成交金额（元） */
  dealAmount: number
  /** 奖励比例（0~1） */
  commissionRate: number
  createdAt: string
}

export const STYLE_LABEL: Record<PosterStyle, string> = {
  FESTIVAL: '节日促销', NEWBIE: '新客礼', PROJECT: '项目种草',
  MEMBER: '会员日', REFERRAL: '转介绍', LIVE: '直播预约',
}
export const TEMPLATE_STATUS_LABEL: Record<PosterStatus, string> = {
  ENABLED: '启用中', DISABLED: '已停用',
}
export const TEMPLATE_STATUS_PILL: Record<PosterStatus, 'success' | 'disabled'> = {
  ENABLED: 'success', DISABLED: 'disabled',
}
export const STAGE_LABEL: Record<PosterStage, string> = { DRAFT: '草稿', PUBLISHED: '已发布' }
export const STAGE_PILL: Record<PosterStage, 'draft' | 'primary'> = { DRAFT: 'draft', PUBLISHED: 'primary' }

const DEFAULT_COMMISSION_RATE = 0.05

/** 后端海报模板 → 前端活规格 */
export function adaptTemplate(d: PosterTemplateDTO): PosterTemplate {
  return {
    id: d.templateId,
    name: d.templateName,
    style: d.style as PosterStyle,
    status: (d.status === 'DISABLED' ? 'DISABLED' : 'ENABLED') as PosterStatus,
    uses: d.uses ?? 0,
    accent: (d.accent || 'brand') as PosterTemplate['accent'],
    defaultTitle: d.defaultTitle || '',
    defaultSubtitle: d.defaultSubtitle || '',
  }
}

/** 后端海报记录 → 前端活规格（分→元、佣金 50→0.05） */
export function adaptPoster(d: PosterRecordDTO): Poster {
  return {
    id: d.posterId,
    templateId: d.templateId,
    templateName: d.templateName || '',
    style: d.style as PosterStyle,
    accent: (d.accent || 'brand') as PosterTemplate['accent'],
    title: d.title || '',
    subtitle: d.subtitle || '',
    project: d.project || '',
    referrerName: d.referrerName || '',
    status: (d.status === 'DRAFT' ? 'DRAFT' : 'PUBLISHED') as PosterStage,
    funnel: {
      share: d.share ?? 0,
      scan: d.scan ?? 0,
      lead: d.lead ?? 0,
      visit: d.visit ?? 0,
      deal: d.deal ?? 0,
    },
    dealAmount: fen2yuan(d.dealAmount),
    commissionRate: rateView(d.commissionRate),
    createdAt: dayOf(d.createdAt),
  }
}

export const useM5PosterStore = defineStore('m5Poster', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()
  const referral = useReferralStore()

  const templates = ref<PosterTemplate[]>([])
  const posters = ref<Poster[]>([])
  const filterStatus = ref<'ALL' | PosterStatus>('ALL')
  const loaded = ref(false)

  // 推荐人选项（去重，从转介绍关系链派生）
  const referrerOptions = computed(() => {
    const map = new Map<string, { name: string; level: string; total: number }>()
    referral.referrals.forEach((r) => {
      if (!map.has(r.referrerName)) {
        map.set(r.referrerName, { name: r.referrerName, level: r.referrerLevel, total: r.referrerTotal })
      }
    })
    return [...map.values()].sort((a, b) => b.total - a.total)
  })

  const filteredTemplates = computed(() => {
    if (filterStatus.value === 'ALL') return templates.value
    return templates.value.filter((t) => t.status === filterStatus.value)
  })

  function get(id: string) {
    return templates.value.find((t) => t.id === id) ?? null
  }
  function getPoster(id: string) {
    return posters.value.find((p) => p.id === id) ?? null
  }

  const totalShares = computed(() => posters.value.reduce((s, p) => s + p.funnel.share, 0))
  const totalScans = computed(() => posters.value.reduce((s, p) => s + p.funnel.scan, 0))
  const totalDeals = computed(() => posters.value.reduce((s, p) => s + p.funnel.deal, 0))
  const totalCommission = computed(() =>
    posters.value.reduce((s, p) => s + Math.round(p.dealAmount * p.commissionRate), 0))

  /** 模板启用/停用翻转（后端幂等 changed；实际翻转才审计） */
  async function toggleTemplateStatus(id: string) {
    if (!auth.can('poster:edit')) throw new Error('无海报编辑权限')
    const t = templates.value.find((x) => x.id === id)
    const action = t?.status === 'ENABLED' ? '停用' : '启用'
    const res = await api.togglePosterTemplate(id)
    await seed(true)
    if (res.data.changed && t) {
      activity.log(auth.user?.name ?? '系统', `${action}海报模板「${t.name}」`, id)
    }
  }

  async function createPoster(input: {
    templateId: string
    title: string
    subtitle: string
    project: string
    referrerName: string
    commissionRate?: number
  }): Promise<Poster> {
    if (!auth.can('poster:edit')) throw new Error('无海报编辑权限')
    const tpl = templates.value.find((t) => t.id === input.templateId)
    if (!tpl) throw new Error('海报模板不存在')
    const rate = typeof input.commissionRate === 'number' ? input.commissionRate : DEFAULT_COMMISSION_RATE
    const cmd: api.PosterCmd = {
      templateId: input.templateId,
      title: input.title,
      subtitle: input.subtitle,
      project: input.project,
      referrerName: input.referrerName,
      commissionRate: rate50(rate),
    }
    const res = await api.createPoster(cmd)
    activity.log(
      auth.user?.name ?? '系统',
      `生成裂变海报「${input.title}」（模板：${tpl.name}，推荐人：${input.referrerName}）`,
      res.data.posterId,
    )
    await seed(true)
    return posters.value.find((p) => p.id === res.data.posterId) ?? adaptPoster(res.data)
  }

  /** 佣金试算：成交金额 × 比例 */
  function simulateCommission(amount: number, rate = DEFAULT_COMMISSION_RATE) {
    return Math.round(Math.max(0, amount) * rate)
  }

  /** 拉取真实模板 + 海报（幂等：已加载默认不重复，force 用于写后重拉）；推荐人选项仍走转介绍 mock */
  async function seed(force = false) {
    if (loaded.value && !force) return
    referral.seed()
    const [tplRes, posterRes] = await Promise.all([api.listPosterTemplates(), api.listPosters()])
    templates.value = tplRes.data.map(adaptTemplate)
    posters.value = posterRes.data.map(adaptPoster)
    loaded.value = true
  }

  return {
    templates, posters, filterStatus, loaded,
    referrerOptions, filteredTemplates,
    get, getPoster,
    totalShares, totalScans, totalDeals, totalCommission,
    toggleTemplateStatus, createPoster, simulateCommission,
    STYLE_LABEL, TEMPLATE_STATUS_LABEL, TEMPLATE_STATUS_PILL, STAGE_LABEL, STAGE_PILL,
    DEFAULT_COMMISSION_RATE,
    seed,
  }
})
