// ============================================================
// M5-04 裂变海报 store
// - 海报模板（节日促销/新客礼/项目种草/会员日/转介绍/直播预约）
// - 已生成海报：绑定推荐人、漏斗（分享→扫码→留资→到店→成交）、佣金试算
// - 提交前由 view 调 useSensitiveWords.checkSensitive 拦截违禁词
// 权限：poster:view / poster:edit
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useActivityStore } from '@/stores/activity'
import { useAuthStore } from '@/stores/auth'
import { useReferralStore } from '@/stores/referral'

export type PosterStyle = 'FESTIVAL' | 'NEWBIE' | 'PROJECT' | 'MEMBER' | 'REFERRAL' | 'LIVE'
export type PosterStatus = 'ENABLED' | 'DISABLED'
export type PosterStage = 'DRAFT' | 'PUBLISHED'

let _id = 0
function nextId(p: string) { _id += 1; return `${p}-${Date.now().toString(36)}-${_id}` }
function dayOffset(n: number) {
  const d = new Date(); d.setDate(d.getDate() + n)
  return d.toISOString().slice(0, 10)
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

export const useM5PosterStore = defineStore('m5Poster', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()
  const referral = useReferralStore()

  const templates = ref<PosterTemplate[]>([])
  const posters = ref<Poster[]>([])
  const filterStatus = ref<'ALL' | PosterStatus>('ALL')
  const seeded = ref(false)

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

  function toggleTemplateStatus(id: string) {
    if (!auth.can('poster:edit')) return
    const t = templates.value.find((x) => x.id === id)
    if (!t) return
    t.status = t.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
    activity.log(auth.user.name, `${t.status === 'ENABLED' ? '启用' : '停用'}海报模板「${t.name}」`, t.id)
  }

  function createPoster(input: {
    templateId: string
    title: string
    subtitle: string
    project: string
    referrerName: string
    commissionRate?: number
  }): Poster | null {
    if (!auth.can('poster:edit')) return null
    const tpl = templates.value.find((t) => t.id === input.templateId)
    if (!tpl) return null
    const rate = typeof input.commissionRate === 'number' ? input.commissionRate : DEFAULT_COMMISSION_RATE
    const poster: Poster = {
      id: nextId('mp'),
      templateId: tpl.id,
      templateName: tpl.name,
      style: tpl.style,
      accent: tpl.accent,
      title: input.title,
      subtitle: input.subtitle,
      project: input.project,
      referrerName: input.referrerName,
      status: 'PUBLISHED',
      funnel: { share: 0, scan: 0, lead: 0, visit: 0, deal: 0 },
      dealAmount: 0,
      commissionRate: rate,
      createdAt: dayOffset(0),
    }
    posters.value.unshift(poster)
    tpl.uses += 1
    activity.log(auth.user.name, `生成裂变海报「${poster.title}」（模板：${tpl.name}，推荐人：${poster.referrerName}）`, poster.id)
    return poster
  }

  /** 佣金试算：成交金额 × 比例 */
  function simulateCommission(amount: number, rate = DEFAULT_COMMISSION_RATE) {
    return Math.round(Math.max(0, amount) * rate)
  }

  function seed() {
    if (seeded.value) return
    referral.seed()
    templates.value = [
      { id: nextId('tpl'), name: '双11 狂欢大促', style: 'FESTIVAL', status: 'ENABLED', uses: 128, accent: 'brand', defaultTitle: '双11 狂欢季 礼遇焕新', defaultSubtitle: '爆款项目限时直降，会员再享折上折' },
      { id: nextId('tpl'), name: '新客 88 元体验礼', style: 'NEWBIE', status: 'ENABLED', uses: 96, accent: 'teal', defaultTitle: '新客专享 88 元体验', defaultSubtitle: '到店即赠皮肤检测一次，无隐形消费' },
      { id: nextId('tpl'), name: '热玛吉抗衰种草', style: 'PROJECT', status: 'ENABLED', uses: 74, accent: 'purple', defaultTitle: '热玛吉 FLX 紧致提拉', defaultSubtitle: '正版仪器可验真，医师一对一定制方案' },
      { id: nextId('tpl'), name: '周三会员日', style: 'MEMBER', status: 'ENABLED', uses: 210, accent: 'gold', defaultTitle: '会员日 双倍积分', defaultSubtitle: '每周三会员到店，积分翻倍兑好礼' },
      { id: nextId('tpl'), name: '老带新双赢礼', style: 'REFERRAL', status: 'ENABLED', uses: 58, accent: 'orange', defaultTitle: '邀请好友 各得 200 元', defaultSubtitle: '好友到店成交，奖励自动到账' },
      { id: nextId('tpl'), name: '医美直播预约', style: 'LIVE', status: 'DISABLED', uses: 32, accent: 'blue', defaultTitle: '院长直播 在线答疑', defaultSubtitle: '预约直播抽免单，限时福袋抢不停' },
    ]

    // 已生成海报（带漏斗数据）
    const tpl = (i: number) => templates.value[i]
    const mk = (i: number, title: string, subtitle: string, project: string, referrer: string,
      f: PosterFunnel, dealAmount: number, day: number): Poster => ({
      id: nextId('mp'), templateId: tpl(i).id, templateName: tpl(i).name, style: tpl(i).style, accent: tpl(i).accent,
      title, subtitle, project, referrerName: referrer, status: 'PUBLISHED',
      funnel: f, dealAmount, commissionRate: DEFAULT_COMMISSION_RATE, createdAt: dayOffset(day),
    })
    posters.value = [
      mk(0, '双11 狂欢季 礼遇焕新', '爆款项目限时直降，会员再享折上折', '水光嫩肤年卡', '林晚',
        { share: 420, scan: 286, lead: 124, visit: 58, deal: 22 }, 68000, -12),
      mk(1, '新客专享 88 元体验', '到店即赠皮肤检测一次，无隐形消费', '皮肤检测 + 小气泡', '王蕊',
        { share: 360, scan: 248, lead: 96, visit: 64, deal: 18 }, 12600, -8),
      mk(4, '邀请好友 各得 200 元', '好友到店成交，奖励自动到账', '通用项目券', '陈思',
        { share: 210, scan: 156, lead: 72, visit: 40, deal: 14 }, 38400, -6),
      mk(2, '热玛吉 FLX 紧致提拉', '正版仪器可验真，医师一对一定制方案', '热玛吉面部', '张敏',
        { share: 180, scan: 96, lead: 38, visit: 18, deal: 6 }, 58800, -3),
      mk(3, '会员日 双倍积分', '每周三会员到店，积分翻倍兑好礼', '会员日到店礼', '李娜',
        { share: 520, scan: 312, lead: 88, visit: 52, deal: 12 }, 9600, -2),
      mk(5, '院长直播 在线答疑', '预约直播抽免单，限时福袋抢不停', '直播预约', '王芳',
        { share: 90, scan: 64, lead: 20, visit: 4, deal: 0 }, 0, -1),
    ]
    seeded.value = true
  }

  return {
    templates, posters, filterStatus, seeded,
    referrerOptions, filteredTemplates,
    get, getPoster,
    totalShares, totalScans, totalDeals, totalCommission,
    toggleTemplateStatus, createPoster, simulateCommission,
    STYLE_LABEL, TEMPLATE_STATUS_LABEL, TEMPLATE_STATUS_PILL, STAGE_LABEL, STAGE_PILL,
    DEFAULT_COMMISSION_RATE,
    seed,
  }
})
