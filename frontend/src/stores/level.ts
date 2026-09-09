// ============================================================
// 会员等级体系 store（M3-04，接真实 customer-service /customer/member-levels、/level-rule）
// 5 级会员（普通/银卡/金卡/钻石/黑卡）+ 升降级规则 + LEVEL 审计链。
// - 人数/百分比：后端读时实时 count(customer) group by level，不读历史聚合 cnt 假数据。
// - 审计：GET /audit 全链拉取后前端按 bizType=LEVEL 过滤（照 m5Settings 先例，无 audit:view 静默为空）。
// - KPI 本月升级/降级：从本月 LEVEL/ADJUST 方向 + AUTO_UPGRADE 汇总人数派生（自动降级引擎在 Backlog）。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import client from '@/api/client'
import { listStaff } from '@/api/org'
import {
  getMemberLevels,
  getLevelRule,
  updateMemberLevel,
  saveLevelRule as apiSaveLevelRule,
  type MemberLevelDTO,
  type LevelRuleDTO,
} from '@/api/customer'
import { useAuthStore } from './auth'

export type LevelTier = 'NORMAL' | 'SILVER' | 'GOLD' | 'DIAMOND' | 'BLACK'

export interface MemberLevel {
  /** 等级中文短名（member_level 主键） */
  id: string
  tier: LevelTier
  name: string
  /** 等级主色，用于卡片左边框 / 圆点 / 升级条件底色 */
  color: string
  /** 累计消费阈值（元）；普通为 0（注册即享） */
  upgradeThreshold: number
  /** 升级条件文案（后端权威派生） */
  upgradeCondition: string
  benefits: string[]
  memberCount: number
  memberPercent: number
  isTop?: boolean
}

export interface LevelRule {
  /** 等级计算周期文案 */
  calcPeriod: string
  /** 降级保护期（月） */
  downgradeProtectMonths: number
  /** 达标后自动升级 */
  autoUpgrade: boolean
  /** 消费积分倍率 */
  pointsMultiplier: number
}

export interface LevelAuditEntry {
  id: string
  date: string
  text: string
  operator: string
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

/** 等级序（KPI 升降级方向判定；与后端 LEVEL_ORDER 同序） */
const LEVEL_ORDER: Record<string, number> = {
  普通: 1,
  银卡: 2,
  金卡: 3,
  钻石: 4,
  黑卡: 5,
}

/** 重置默认口径（与后端种子/常量兜底一致；同态提交后端短路，不产生重复审计） */
const DEFAULT_RULE: LevelRule = {
  calcPeriod: '自然月（每月1号）',
  downgradeProtectMonths: 3,
  autoUpgrade: true,
  pointsMultiplier: 1.0,
}
const DEFAULT_LEVELS: Array<{ id: string; upgradeThreshold: number; benefits: string[] }> = [
  { id: '普通', upgradeThreshold: 0, benefits: ['项目基础价'] },
  { id: '银卡', upgradeThreshold: 5000, benefits: ['项目折扣 9.5 折', '生日当月 1.2 倍积分'] },
  { id: '金卡', upgradeThreshold: 20000, benefits: ['项目折扣 9 折', '生日当月 1.5 倍积分', '专属咨询师'] },
  { id: '钻石', upgradeThreshold: 50000, benefits: ['项目折扣 8.5 折', '生日当月 2 倍积分', '专属咨询师 + 免排队', '每月 1 次免费护理'] },
  { id: '黑卡', upgradeThreshold: 100000, benefits: ['项目折扣 8 折', '生日当月 3 倍积分', '专属咨询师 + 免排队', '每月 2 次免费护理'] },
]

export const useLevelStore = defineStore('level', () => {
  const auth = useAuthStore()

  const levels = ref<MemberLevel[]>([])
  const rule = ref<LevelRule>({ ...DEFAULT_RULE })
  const audits = ref<LevelAuditEntry[]>([])
  /** LEVEL 原始审计行（KPI 本月升/降级统计用） */
  const rows = ref<AuditChainRow[]>([])
  const seeded = ref(false)

  const totalMembers = computed(() => levels.value.reduce((s, l) => s + l.memberCount, 0))
  const topLevel = computed(() => levels.value.find((l) => l.isTop) ?? levels.value[levels.value.length - 1])

  function inCurrentMonth(iso: string): boolean {
    const d = new Date(iso)
    const now = new Date()
    return !Number.isNaN(d.getTime()) && d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth()
  }

  function safeJson(s: string): Record<string, any> | null {
    try {
      return JSON.parse(s)
    } catch {
      return null
    }
  }

  /** 本月升级：本月手工调级（方向向上）人次 + 本月自动升级汇总人数 */
  const monthUpgraded = computed(() => {
    let n = 0
    for (const r of rows.value) {
      if (!inCurrentMonth(r.createdAt)) continue
      const p = safeJson(r.payload)
      if (!p) continue
      if (r.action === 'AUTO_UPGRADE' && typeof p.upgraded === 'number') {
        n += p.upgraded
      } else if (r.action === 'ADJUST'
        && (LEVEL_ORDER[p.toLevel] ?? 0) > (LEVEL_ORDER[p.fromLevel] ?? 0)) {
        n += 1
      }
    }
    return n
  })

  /** 本月降级：仅手工向下调级可统计；自动降级引擎未建（Backlog），故无 AUTO_DOWNGRADE 来源 */
  const monthDowngraded = computed(() =>
    rows.value.reduce((n, r) => {
      if (r.action !== 'ADJUST' || !inCurrentMonth(r.createdAt)) return n
      const p = safeJson(r.payload)
      if (p && (LEVEL_ORDER[p.toLevel] ?? 99) < (LEVEL_ORDER[p.fromLevel] ?? 0)) return n + 1
      return n
    }, 0),
  )

  function get(id: string) {
    return levels.value.find((l) => l.id === id)
  }

  function adaptLevel(d: MemberLevelDTO): MemberLevel {
    return {
      id: d.id,
      tier: d.tier as LevelTier,
      name: d.name,
      color: d.color,
      upgradeThreshold: Number(d.upgradeThreshold),
      upgradeCondition: d.upgradeCondition,
      benefits: d.benefits ?? [],
      memberCount: d.memberCount,
      memberPercent: d.memberPercent,
      isTop: d.isTop,
    }
  }

  function adaptRule(d: LevelRuleDTO): LevelRule {
    return {
      calcPeriod: d.calcPeriod,
      downgradeProtectMonths: d.downgradeProtectMonths,
      autoUpgrade: d.autoUpgrade,
      pointsMultiplier: Number(d.pointsMultiplier),
    }
  }

  /**
   * 更新等级阈值（页面只改阈值；benefits 原值回传防误清，后端同态短路不审计）。
   * 成功后用返回 DTO 就地替换并刷新审计链/KPI。
   */
  async function updateLevel(
    id: string,
    patch: Partial<Pick<MemberLevel, 'upgradeThreshold' | 'benefits' | 'name'>>,
  ): Promise<{ ok: boolean; reason?: string }> {
    const cur = levels.value.find((x) => x.id === id)
    if (!cur) return { ok: false, reason: '等级不存在' }
    const threshold = patch.upgradeThreshold ?? cur.upgradeThreshold
    try {
      const { data } = await updateMemberLevel(id, {
        upgradeThreshold: threshold,
        benefits: cur.benefits,
      })
      const idx = levels.value.findIndex((x) => x.id === id)
      if (idx >= 0) levels.value[idx] = adaptLevel(data)
      await loadAuditLogs()
      return { ok: true }
    } catch (e) {
      return { ok: false, reason: errText(e) }
    }
  }

  /** 保存升降级规则（全字段覆盖；后端校验中文报错，同态短路不审计）。 */
  async function saveRule(patch: Partial<LevelRule>): Promise<{ ok: boolean; reason?: string }> {
    try {
      const { data } = await apiSaveLevelRule(patch)
      rule.value = adaptRule(data)
      await loadAuditLogs()
      return { ok: true }
    } catch (e) {
      return { ok: false, reason: errText(e) }
    }
  }

  /**
   * 重置为系统默认：五级阈值/权益逐档同态提交 + 规则默认值提交（现值即默认时后端短路不审计），
   * 全部成功后强制重拉读模型与审计链。
   */
  async function resetDefault(): Promise<{ ok: boolean; reason?: string }> {
    try {
      await Promise.all(
        DEFAULT_LEVELS.map((d) =>
          updateMemberLevel(d.id, { upgradeThreshold: d.upgradeThreshold, benefits: d.benefits }),
        ),
      )
      await apiSaveLevelRule({ ...DEFAULT_RULE })
      await seed(true)
      return { ok: true }
    } catch (e) {
      return { ok: false, reason: errText(e) }
    }
  }

  /** 拉取五级读模型 + 升降级规则 + LEVEL 审计链；force=true 保存/重置后强制刷新。 */
  async function seed(force = false) {
    if (seeded.value && !force) return
    try {
      const [lv, ru] = await Promise.all([getMemberLevels(), getLevelRule()])
      levels.value = lv.data.map(adaptLevel)
      rule.value = adaptRule(ru.data)
    } catch (e) {
      console.error('会员等级加载失败', e)
    }
    await loadAuditLogs()
    seeded.value = true
  }

  /** 审计卡：拉 audit 全链按 bizType=LEVEL 过滤、时间倒序；工号解析为姓名，失败静默为空不阻断页面。 */
  async function loadAuditLogs() {
    try {
      const { data } = await client.get<AuditChainRow[]>('/audit')
      const sorted = data
        .filter((r) => r.bizType === 'LEVEL')
        .sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1))
      rows.value = sorted
      const nameMap = await resolveActorNames(sorted.map((r) => r.actor))
      audits.value = sorted.map((r) => ({
        id: `audit-${r.id}`,
        date: fmtTime(r.createdAt),
        text: auditText(r),
        operator: nameMap(r.actor),
      }))
    } catch (e) {
      console.warn('会员等级审计链加载失败（可能无 audit:view 权限）', e)
      rows.value = []
      audits.value = []
    }
  }

  /** LEVEL 四类动作（UPDATE/RULE_SAVE/ADJUST/AUTO_UPGRADE）payload 展开为中文变更文案。 */
  function auditText(r: AuditChainRow): string {
    const p = safeJson(r.payload)
    if (r.action === 'UPDATE' && p) {
      const label = p.name ? `「${p.name}」` : ''
      const parts: string[] = []
      const before = p.before ?? {}
      const after = p.after ?? {}
      if (Number(before.upgradeThreshold) !== Number(after.upgradeThreshold)) {
        parts.push(`阈值 ¥${fmtNum(before.upgradeThreshold)}→¥${fmtNum(after.upgradeThreshold)}`)
      }
      if (JSON.stringify(before.benefits ?? []) !== JSON.stringify(after.benefits ?? [])) {
        parts.push(`权益 ${before.benefits?.length ?? 0} 项→${after.benefits?.length ?? 0} 项`)
      }
      return `调整${label}${parts.join('，') || '等级配置'}`
    }
    if (r.action === 'RULE_SAVE' && p) {
      return `保存升降级规则（${p.calcPeriod} · 保护${p.downgradeProtectMonths}月`
        + ` · 自动升级${p.autoUpgrade ? '开' : '关'} · 倍率${p.pointsMultiplier}）`
    }
    if (r.action === 'ADJUST' && p) {
      return `手工调级「${p.name}」${p.fromLevel}→${p.toLevel}`
    }
    if (r.action === 'AUTO_UPGRADE' && p) {
      const items: Array<{ name: string; fromLevel: string; toLevel: string }> = p.items ?? []
      const head = items.slice(0, 3).map((i) => `${i.name}（${i.fromLevel}→${i.toLevel}）`).join('、')
      return `按累计消费自动升级 ${p.upgraded} 人${head ? `：${head}${items.length > 3 ? ' 等' : ''}` : ''}`
    }
    const fallback: Record<string, string> = {
      UPDATE: '更新等级配置',
      RULE_SAVE: '保存升降级规则',
      ADJUST: '手工调级',
      AUTO_UPGRADE: '批量自动升级',
    }
    return fallback[r.action] ?? r.action
  }

  async function resolveActorNames(actors: string[]): Promise<(id: string) => string> {
    const map = new Map<string, string>()
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

  function fmtNum(v: unknown): string {
    const n = Number(v)
    return Number.isFinite(n) ? n.toLocaleString() : String(v)
  }

  function errText(e: unknown): string {
    const anyE = e as { response?: { data?: { message?: string; error?: string } }; message?: string }
    return anyE?.response?.data?.message || anyE?.message || '操作失败'
  }

  return {
    levels, rule, audits, seeded,
    totalMembers, topLevel, monthUpgraded, monthDowngraded,
    get, updateLevel, saveRule, resetDefault, seed,
  }
})
