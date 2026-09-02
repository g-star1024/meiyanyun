// ============================================================
// 会员等级体系 store（M3-04）
// 5 级会员（普通/银卡/金卡/钻石/黑钻）+ 升降级规则 + 审计追踪。
// 对齐设计稿 209:194 / 209:283：等级卡片横排 + 规则配置 + 最近变更。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

export type LevelTier = 'NORMAL' | 'SILVER' | 'GOLD' | 'DIAMOND' | 'BLACK'

export interface MemberLevel {
  id: string
  tier: LevelTier
  name: string
  /** 等级主色，用于卡片左边框 / 圆点 / 升级条件底色 */
  color: string
  /** 累计消费阈值（元）；普通为 0（注册即享） */
  upgradeThreshold: number
  /** 升级条件文案 */
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

const COLOR_BY_TIER: Record<LevelTier, string> = {
  NORMAL: '#8B5CF6',
  SILVER: '#8B5CF6',
  GOLD: '#F59E0B',
  DIAMOND: '#6366F1',
  BLACK: '#10B981',
}

export const useLevelStore = defineStore('level', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const levels = ref<MemberLevel[]>([])
  const rule = ref<LevelRule>({
    calcPeriod: '自然月（每月1号）',
    downgradeProtectMonths: 3,
    autoUpgrade: true,
    pointsMultiplier: 1.0,
  })
  const audits = ref<LevelAuditEntry[]>([])

  const totalMembers = computed(() => levels.value.reduce((s, l) => s + l.memberCount, 0))
  const topLevel = computed(() => levels.value.find((l) => l.isTop) ?? levels.value[levels.value.length - 1])

  /** 本月升级（种子：演示统计） */
  const monthUpgraded = ref(328)
  /** 本月降级 */
  const monthDowngraded = ref(64)

  function get(id: string) {
    return levels.value.find((l) => l.id === id)
  }

  function updateLevel(id: string, patch: Partial<Pick<MemberLevel, 'upgradeThreshold' | 'benefits' | 'name'>>): boolean {
    const l = levels.value.find((x) => x.id === id)
    if (!l) return false
    if (!auth.can('level:edit')) {
      console.warn('[level] 无 level:edit 权限')
      return false
    }
    const before = l.upgradeThreshold
    if (patch.name !== undefined) l.name = patch.name
    if (patch.upgradeThreshold !== undefined) {
      l.upgradeThreshold = patch.upgradeThreshold
      l.upgradeCondition = patch.upgradeThreshold > 0
        ? `累计消费 ≥ ¥${patch.upgradeThreshold.toLocaleString()}`
        : '注册即享（无门槛）'
    }
    if (patch.benefits) l.benefits = [...patch.benefits]
    activity.log(auth.user.name, `更新等级 ${l.name} 规则（阈值 ¥${before.toLocaleString()}→¥${l.upgradeThreshold.toLocaleString()}）`, l.id)
    audits.value.unshift({
      id: nextId('la'),
      date: new Date().toISOString().slice(0, 10),
      text: `调整「${l.name}」阈值 ¥${before.toLocaleString()}→¥${l.upgradeThreshold.toLocaleString()}`,
      operator: auth.user.name,
    })
    return true
  }

  function saveRule(patch: Partial<LevelRule>): boolean {
    if (!auth.can('level:edit')) {
      console.warn('[level] 无 level:edit 权限')
      return false
    }
    Object.assign(rule.value, patch)
    activity.log(auth.user.name, '保存升降级规则')
    audits.value.unshift({
      id: nextId('la'),
      date: new Date().toISOString().slice(0, 10),
      text: '保存升降级规则',
      operator: auth.user.name,
    })
    return true
  }

  function resetDefault(): boolean {
    if (!auth.can('level:edit')) return false
    rule.value = {
      calcPeriod: '自然月（每月1号）',
      downgradeProtectMonths: 3,
      autoUpgrade: true,
      pointsMultiplier: 1.0,
    }
    const defaults: Array<[LevelTier, number, string[]]> = [
      ['NORMAL', 0, ['项目基础价']],
      ['SILVER', 5000, ['项目折扣 9.5 折', '生日当月 1.2 倍积分']],
      ['GOLD', 20000, ['项目折扣 9 折', '生日当月 1.5 倍积分', '专属咨询师']],
      ['DIAMOND', 50000, ['项目折扣 8.5 折', '生日当月 2 倍积分', '专属咨询师 + 免排队', '每月 1 次免费护理']],
      ['BLACK', 100000, ['项目折扣 8 折', '生日当月 3 倍积分', '专属咨询师 + 免排队', '每月 2 次免费护理']],
    ]
    levels.value.forEach((l, i) => {
      const d = defaults[i]
      if (!d) return
      l.upgradeThreshold = d[1]
      l.upgradeCondition = d[1] > 0 ? `累计消费 ≥ ¥${d[1].toLocaleString()}` : '注册即享（无门槛）'
      l.benefits = d[2]
    })
    activity.log(auth.user.name, '重置等级规则为默认')
    return true
  }

  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    const data: Array<Omit<MemberLevel, 'id' | 'color'>> = [
      {
        tier: 'NORMAL', name: '普通会员', upgradeThreshold: 0,
        upgradeCondition: '注册即享（无门槛）',
        benefits: ['项目基础价'],
        memberCount: 29160, memberPercent: 60,
      },
      {
        tier: 'SILVER', name: '银卡会员', upgradeThreshold: 5000,
        upgradeCondition: '累计消费 ≥ ¥5,000',
        benefits: ['项目折扣 9.5 折', '生日当月 1.2 倍积分'],
        memberCount: 11664, memberPercent: 24,
      },
      {
        tier: 'GOLD', name: '金卡会员', upgradeThreshold: 20000,
        upgradeCondition: '累计消费 ≥ ¥20,000',
        benefits: ['项目折扣 9 折', '生日当月 1.5 倍积分', '专属咨询师'],
        memberCount: 5346, memberPercent: 11,
      },
      {
        tier: 'DIAMOND', name: '钻石会员', upgradeThreshold: 50000,
        upgradeCondition: '累计消费 ≥ ¥50,000',
        benefits: ['项目折扣 8.5 折', '生日当月 2 倍积分', '专属咨询师 + 免排队', '每月 1 次免费护理'],
        memberCount: 1944, memberPercent: 4,
      },
      {
        tier: 'BLACK', name: '黑卡会员', upgradeThreshold: 100000,
        upgradeCondition: '累计消费 ≥ ¥100,000 或邀请 5 位金卡',
        benefits: ['项目折扣 8 折', '生日当月 3 倍积分', '专属咨询师 + 免排队', '每月 2 次免费护理'],
        memberCount: 486, memberPercent: 1, isTop: true,
      },
    ]
    levels.value = data.map((d) => ({ ...d, id: nextId('lv'), color: COLOR_BY_TIER[d.tier] }))
    audits.value = [
      { id: nextId('la'), date: '2026-07-01', text: '调整钻石门槛 ¥80,000→¥100,000', operator: '集团管理员' },
      { id: nextId('la'), date: '2026-05-15', text: '新增「免费护理」权益至白金/钻石', operator: '运营总监' },
      { id: nextId('la'), date: '2026-03-01', text: '初始规则创建', operator: '系统初始化' },
    ]
  }

  return {
    levels, rule, audits,
    totalMembers, topLevel, monthUpgraded, monthDowngraded,
    get, updateLevel, saveRule, resetDefault, seed,
  }
})
