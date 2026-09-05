// ============================================================
// finSettings —— M6-17 财务设置
// 业财一体红线：仅配置会计科目展示、税率、结算周期、对账与镜像参数；
// 镜像源（金蝶/用友）单向同步，仅做读取/对账，绝不反向写资金池。
// B5：持久化到 finance-service /finance/settings（单例配置 + 科目启用表 +
// 变更日志，保存逐字段 diff 落库，全审计）。localStorage 仅作本地同步缓存，
// 让视图 draft 在 setup 阶段即可取到上次加载值，真实数据以服务端为准。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { SUBJECT_LABEL, type SubjectCode } from './financeCore'
import { getFinSettings, saveFinSettings as apiSaveFinSettings } from '@/api/finance'

export type FinGroupKey = 'SUBJECT' | 'TAX' | 'SETTLE' | 'RECONCILE'

export interface SubjectEnable {
  code: SubjectCode
  enabled: boolean
}

export interface FinSettings {
  // 税率配置
  vatRate: number // 增值税率（6% 服务），0~1
  surtaxRate: number // 附加税率，0~1
  incomeTaxRate: number // 所得税率，0~1
  // 结算周期
  settleDay: number // 门店结算日（每月几号）
  commissionPayDay: number // 专家提成发放日
  reconcileTn: number // 对账 T+N
  // 对账与镜像
  diffThreshold: number // 对账差异阈值（元，超过需双签）
  mirrorKingdee: boolean // 金蝶镜像源
  mirrorYonyou: boolean // 用友镜像源
  outboxRetry: number // Outbox 重试次数
}

export interface FinChangeLog {
  id: string
  by: string
  at: string
  field: string
  oldValue: string
  newValue: string
}

const FIELD_LABEL: Record<keyof FinSettings, string> = {
  vatRate: '增值税率',
  surtaxRate: '附加税率',
  incomeTaxRate: '所得税率',
  settleDay: '门店结算日',
  commissionPayDay: '专家提成发放日',
  reconcileTn: '对账周期 T+N',
  diffThreshold: '对账差异阈值',
  mirrorKingdee: '金蝶镜像源',
  mirrorYonyou: '用友镜像源',
  outboxRetry: 'Outbox 重试次数',
}

const DEFAULT_SETTINGS: FinSettings = {
  vatRate: 0.06,
  surtaxRate: 0.12,
  incomeTaxRate: 0.25,
  settleDay: 5,
  commissionPayDay: 10,
  reconcileTn: 1,
  diffThreshold: 100,
  mirrorKingdee: true,
  mirrorYonyou: false,
  outboxRetry: 3,
}

const CACHE_KEY = 'meiyun:fin-settings'

function restoreCache(): FinSettings {
  try {
    const raw = localStorage.getItem(CACHE_KEY)
    if (raw) return { ...DEFAULT_SETTINGS, ...(JSON.parse(raw) as Partial<FinSettings>) }
  } catch {
    // 缓存损坏回落默认
  }
  return { ...DEFAULT_SETTINGS }
}

export const useFinSettingsStore = defineStore('finSettings', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const settings = ref<FinSettings>(restoreCache())

  /** 会计科目启用表（从 financeCore SUBJECT_LABEL 镜像，只读展示 + 启用开关） */
  const subjectEnable = ref<SubjectEnable[]>(
    (Object.keys(SUBJECT_LABEL) as SubjectCode[]).map((code) => ({
      code,
      // RF-RECEIVABLE（应收账款）默认停用，其余启用
      enabled: code !== 'RF-RECEIVABLE',
    })),
  )

  const logs = ref<FinChangeLog[]>([])
  const loaded = ref(false)

  function fmtValue(key: keyof FinSettings, v: string | number | boolean): string {
    if (key === 'vatRate' || key === 'surtaxRate' || key === 'incomeTaxRate') {
      return `${Math.round(Number(v) * 1000) / 10}%`
    }
    if (key === 'settleDay' || key === 'commissionPayDay') return `每月 ${v} 号`
    if (key === 'reconcileTn') return `T+${v}`
    if (key === 'diffThreshold') return `¥${Number(v).toLocaleString('zh-CN')}`
    if (key === 'mirrorKingdee' || key === 'mirrorYonyou') return v ? '开启' : '关闭'
    return String(v)
  }

  function updateSetting<K extends keyof FinSettings>(key: K, value: FinSettings[K]) {
    const old = settings.value[key]
    if (old === value) return
    settings.value[key] = value
    logs.value.unshift({
      id: nextId('flog'),
      by: auth.user.name,
      at: new Date().toISOString(),
      field: FIELD_LABEL[key],
      oldValue: fmtValue(key, old),
      newValue: fmtValue(key, value),
    })
  }

  function toggleSubject(code: SubjectCode) {
    if (!auth.can('finance:settings:edit')) return
    const row = subjectEnable.value.find((s) => s.code === code)
    if (row) row.enabled = !row.enabled
  }

  function applyBundle(data: {
    settings: {
      vatRate: number; surtaxRate: number; incomeTaxRate: number
      settleDay: number; commissionPayDay: number; reconcileTn: number
      diffThresholdYuan: number; mirrorKingdee: boolean; mirrorYonyou: boolean; outboxRetry: number
    }
    subjects: Array<{ code: string; enabled: boolean }>
    logs: Array<{ id: number; by: string; at: string; field: string; oldValue: string | null; newValue: string | null }>
  }) {
    const s = data.settings
    settings.value = {
      vatRate: Number(s.vatRate) || 0,
      surtaxRate: Number(s.surtaxRate) || 0,
      incomeTaxRate: Number(s.incomeTaxRate) || 0,
      settleDay: Number(s.settleDay) || 5,
      commissionPayDay: Number(s.commissionPayDay) || 10,
      reconcileTn: Number(s.reconcileTn) || 0,
      diffThreshold: Math.max(0, Math.round(Number(s.diffThresholdYuan) || 0)),
      mirrorKingdee: !!s.mirrorKingdee,
      mirrorYonyou: !!s.mirrorYonyou,
      outboxRetry: Number(s.outboxRetry) || 0,
    }
    try {
      localStorage.setItem(CACHE_KEY, JSON.stringify(settings.value))
    } catch {
      // 缓存写入失败忽略
    }
    for (const row of subjectEnable.value) {
      const hit = data.subjects?.find((x) => x.code === row.code)
      if (hit) row.enabled = !!hit.enabled
    }
    logs.value = (data.logs ?? []).map((l) => ({
      id: String(l.id),
      by: l.by,
      at: l.at,
      field: l.field,
      oldValue: l.oldValue ?? '',
      newValue: l.newValue ?? '',
    }))
  }

  /** 批量保存（弹层二次确认后调用），需 finance:settings:edit；乐观更新后持久化，失败回拉。 */
  async function save(next: FinSettings, subjects: SubjectEnable[]): Promise<boolean> {
    if (!auth.can('finance:settings:edit')) {
      console.warn('[finSettings] 无 finance:settings:edit 权限')
      return false
    }
    const prevSettings = { ...settings.value }
    const prevSubjects = subjectEnable.value.map((s) => ({ ...s }))

    let changed = 0
    const cur = settings.value as unknown as Record<string, string | number | boolean>
    const nxt = next as unknown as Record<string, string | number | boolean>
    const localLogs: FinChangeLog[] = []
    ;(Object.keys(next) as Array<keyof FinSettings>).forEach((k) => {
      if (cur[k] !== nxt[k]) {
        const old = cur[k]
        cur[k] = nxt[k]
        localLogs.push({
          id: nextId('flog'),
          by: auth.user.name,
          at: new Date().toISOString(),
          field: FIELD_LABEL[k],
          oldValue: fmtValue(k, old),
          newValue: fmtValue(k, next[k]),
        })
        changed += 1
      }
    })
    for (const s of subjects) {
      const curRow = subjectEnable.value.find((x) => x.code === s.code)
      if (curRow && curRow.enabled !== s.enabled) {
        curRow.enabled = s.enabled
        localLogs.push({
          id: nextId('flog'),
          by: auth.user.name,
          at: new Date().toISOString(),
          field: `科目「${SUBJECT_LABEL[s.code]}」`,
          oldValue: s.enabled ? '停用' : '启用',
          newValue: s.enabled ? '启用' : '停用',
        })
        changed += 1
      }
    }
    logs.value = [...localLogs, ...logs.value]
    try {
      localStorage.setItem(CACHE_KEY, JSON.stringify(settings.value))
    } catch {
      // 缓存写入失败忽略
    }
    try {
      await apiSaveFinSettings({
        vatRate: next.vatRate,
        surtaxRate: next.surtaxRate,
        incomeTaxRate: next.incomeTaxRate,
        settleDay: next.settleDay,
        commissionPayDay: next.commissionPayDay,
        reconcileTn: next.reconcileTn,
        diffThreshold: next.diffThreshold,
        mirrorKingdee: next.mirrorKingdee,
        mirrorYonyou: next.mirrorYonyou,
        outboxRetry: next.outboxRetry,
        subjects: subjects.map((s) => ({ code: s.code, enabled: s.enabled })),
      })
      if (changed > 0) {
        activity.log(auth.user.name, `保存财务设置，共 ${changed} 项变更（作用于全 M6 财务页）`)
      }
      // 以服务端返回的权威日志为准回拉一次
      seed(true).catch(() => {})
      return true
    } catch (e) {
      console.error('[finSettings] 财务设置保存失败，回滚本地值', e)
      settings.value = prevSettings
      subjectEnable.value = prevSubjects.map((s) => ({ ...s }))
      logs.value = logs.value.slice(localLogs.length)
      return false
    }
  }

  function resetDefault() {
    Object.assign(settings.value, DEFAULT_SETTINGS)
  }

  const canEdit = computed(() => auth.can('finance:settings:edit'))
  const enabledSubjectCount = computed(() => subjectEnable.value.filter((s) => s.enabled).length)

  // ===== 种子：优先拉真实数据，失败回落默认值 + 内置审计记录 =====
  let seeding: Promise<void> | null = null
  function seedMockLogs() {
    const hoursAgo = (h: number) => new Date(Date.now() - h * 3600_000).toISOString()
    logs.value = [
      { id: nextId('flog'), by: '钱进（财务审核）', at: hoursAgo(26), field: FIELD_LABEL.diffThreshold, oldValue: '¥50', newValue: '¥100' },
      { id: nextId('flog'), by: '钱进（财务审核）', at: hoursAgo(72), field: FIELD_LABEL.reconcileTn, oldValue: 'T+0', newValue: 'T+1' },
      { id: nextId('flog'), by: '陈野（区域经理）', at: hoursAgo(24 * 9), field: FIELD_LABEL.mirrorKingdee, oldValue: '关闭', newValue: '开启' },
    ]
  }

  function seed(force = false): Promise<void> {
    if (seeding && !force) return seeding
    if (loaded && !force) return Promise.resolve()
    seeding = (async () => {
      try {
        const { data } = await getFinSettings()
        applyBundle(data)
        loaded.value = true
      } catch (e) {
        console.error('[finSettings] 加载财务设置失败，回落默认值', e)
        if (logs.value.length === 0) seedMockLogs()
      }
    })()
    return seeding
  }
  void seed()

  return {
    settings, subjectEnable, logs, canEdit, enabledSubjectCount, loaded,
    updateSetting, toggleSubject, save, resetDefault, seed,
    SUBJECT_LABEL, FIELD_LABEL,
  }
})
