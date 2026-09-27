// ============================================================
// T2-02 数据治理 store（T2-B1 切真 customer-service）
// 质量规则 / 数据问题 / 数据血缘，对齐 T-G-中台与通用.md T2-02 详设。
// 数据源：/api/customer/t2/govern（V63-V66 落库；查询=govern:view，
// 创建=govern:rule:create，编辑/启停＋问题解决/忽略=govern:rule:edit）。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { useToast } from '@/composables/useToast'
import { errMsg } from './m5Coupon'
import * as api from '@/api/t2DataGovern'
import type { RuleView, IssueView, LineageNodeView } from '@/api/t2DataGovern'

// ---- 类型 ----
export type RuleType = 'NOT_NULL' | 'UNIQUE' | 'RANGE' | 'REGEX' | 'CUSTOM'
export type RuleSeverity = 'HIGH' | 'MEDIUM' | 'LOW'
export type IssueStatus = 'OPEN' | 'RESOLVED' | 'IGNORED'
export type LineageNodeType = 'SOURCE' | 'TABLE' | 'TAG' | 'API' | 'REPORT'

export interface QualityRule {
  id: number
  name: string
  table: string
  column: string
  type: RuleType
  severity: RuleSeverity
  expression: string
  enabled: boolean
  lastCheckAt: string | null
  passRate: number
  errorCount: number
  owner: string
  createdAt: string
}

export interface DataIssue {
  id: number
  ruleId: number
  ruleName: string
  table: string
  column: string
  sample: string
  count: number
  status: IssueStatus
  detectedAt: string
  resolvedAt?: string | null
}

export interface LineageNode {
  id: string
  name: string
  type: LineageNodeType
  x: number
  y: number
}

export interface LineageEdge {
  from: string
  to: string
}

export const RULE_TYPE_LABEL: Record<RuleType, string> = {
  NOT_NULL: '非空',
  UNIQUE: '唯一',
  RANGE: '范围',
  REGEX: '正则',
  CUSTOM: '自定义 SQL',
}

export const RULE_SEVERITY_LABEL: Record<RuleSeverity, string> = {
  HIGH: '高',
  MEDIUM: '中',
  LOW: '低',
}

export const ISSUE_STATUS_LABEL: Record<IssueStatus, string> = {
  OPEN: '待处理',
  RESOLVED: '已解决',
  IGNORED: '已忽略',
}

export const LINEAGE_NODE_LABEL: Record<LineageNodeType, string> = {
  SOURCE: '数据源',
  TABLE: '数据表',
  TAG: '标签',
  API: '数据服务',
  REPORT: '报表',
}

function mapRule(v: RuleView): QualityRule {
  return { ...v, type: v.type as RuleType, severity: v.severity as RuleSeverity }
}

function mapIssue(v: IssueView): DataIssue {
  return { ...v, status: v.status as IssueStatus }
}

function mapNode(v: LineageNodeView): LineageNode {
  return { ...v, type: v.type as LineageNodeType }
}

export const useT2DataGovernStore = defineStore('t2DataGovern', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()
  const toast = useToast()

  const rules = ref<QualityRule[]>([])
  const issues = ref<DataIssue[]>([])
  const lineageNodes = ref<LineageNode[]>([])
  const lineageEdges = ref<LineageEdge[]>([])
  const loading = ref(false)
  const loaded = ref(false)
  const loadError = ref('')

  // ---- 查询 ----
  const enabledRules = computed(() => rules.value.filter((r) => r.enabled))
  const openIssues = computed(() => issues.value.filter((i) => i.status === 'OPEN'))
  const passRate = computed(() => {
    const en = enabledRules.value
    if (!en.length) return 0
    return Math.round(en.reduce((s, r) => s + r.passRate, 0) / en.length)
  })

  function getRule(id: number) { return rules.value.find((r) => r.id === id) }

  function canCreate() { return auth.can('govern:rule:create') }
  function canEdit() { return auth.can('govern:rule:edit') }

  function replaceRule(next: QualityRule) {
    const idx = rules.value.findIndex((r) => r.id === next.id)
    if (idx >= 0) rules.value.splice(idx, 1, next)
    else rules.value.unshift(next)
  }

  function replaceIssue(next: DataIssue) {
    const idx = issues.value.findIndex((i) => i.id === next.id)
    if (idx >= 0) issues.value.splice(idx, 1, next)
    else issues.value.unshift(next)
  }

  // ---- 装载 ----
  async function load() {
    loading.value = true
    loadError.value = ''
    try {
      const [rs, is, lg] = await Promise.all([
        api.fetchRules(),
        api.fetchIssues(),
        api.fetchLineage(),
      ])
      rules.value = rs.map(mapRule)
      issues.value = is.map(mapIssue)
      lineageNodes.value = lg.nodes.map(mapNode)
      lineageEdges.value = lg.edges
      loaded.value = true
    } catch (e) {
      loadError.value = errMsg(e)
      console.warn('[t2DataGovern] 数据治理加载失败', e)
    } finally {
      loading.value = false
    }
  }

  /** 进页装载（B86 范式：每次进页重拉真实数据） */
  async function seed() {
    await load()
  }

  // ---- 命令 ----
  async function createRule(input: {
    name: string; table: string; column: string; type: RuleType
    severity: RuleSeverity; expression: string; enabled?: boolean
  }): Promise<QualityRule | null> {
    if (!canCreate()) {
      toast.error('无规则创建权限')
      return null
    }
    try {
      const r = mapRule(await api.createRule({ ...input }))
      rules.value.unshift(r)
      activity.log(auth.user.name, `创建质量规则「${r.name}」（${RULE_TYPE_LABEL[r.type]}）`, String(r.id))
      toast.success(`质量规则「${r.name}」已创建`)
      return r
    } catch (e) {
      toast.error(errMsg(e, '创建失败'))
      return null
    }
  }

  async function updateRule(id: number, patch: Partial<Pick<QualityRule, 'name' | 'table' | 'column' | 'type' | 'severity' | 'expression' | 'enabled'>>): Promise<boolean> {
    if (!canEdit()) {
      toast.error('无规则编辑权限')
      return false
    }
    try {
      const r = mapRule(await api.updateRule(id, { ...patch }))
      replaceRule(r)
      activity.log(auth.user.name, `编辑质量规则「${r.name}」`, String(id))
      toast.success(`质量规则「${r.name}」已保存`)
      return true
    } catch (e) {
      toast.error(errMsg(e, '保存失败'))
      return false
    }
  }

  async function toggleRule(id: number): Promise<boolean> {
    if (!canEdit()) {
      toast.error('无规则编辑权限')
      return false
    }
    try {
      const r = mapRule(await api.toggleRule(id))
      replaceRule(r)
      activity.log(auth.user.name, `${r.enabled ? '启用' : '停用'}质量规则「${r.name}」`, String(id))
      return true
    } catch (e) {
      toast.error(errMsg(e, '操作失败'))
      return false
    }
  }

  async function resolveIssue(id: number): Promise<boolean> {
    if (!canEdit()) {
      toast.error('无问题处理权限')
      return false
    }
    try {
      const i = mapIssue(await api.resolveIssue(id))
      replaceIssue(i)
      // 回填到规则：错误数减少（后端同事务回填 errorCount，本地镜像同步免重拉）
      const r = getRule(i.ruleId)
      if (r) r.errorCount = Math.max(0, r.errorCount - i.count)
      activity.log(auth.user.name, `标记数据问题已解决：${i.ruleName}（${i.table}.${i.column}）`, String(id))
      toast.success('问题已标记解决')
      return true
    } catch (e) {
      toast.error(errMsg(e, '操作失败'))
      return false
    }
  }

  async function ignoreIssue(id: number): Promise<boolean> {
    if (!canEdit()) {
      toast.error('无问题处理权限')
      return false
    }
    try {
      const i = mapIssue(await api.ignoreIssue(id))
      replaceIssue(i)
      activity.log(auth.user.name, `忽略数据问题：${i.ruleName}`, String(id))
      toast.success('问题已忽略')
      return true
    } catch (e) {
      toast.error(errMsg(e, '操作失败'))
      return false
    }
  }

  return {
    rules, issues, lineageNodes, lineageEdges,
    loading, loaded, loadError,
    enabledRules, openIssues, passRate,
    RULE_TYPE_LABEL, RULE_SEVERITY_LABEL, ISSUE_STATUS_LABEL, LINEAGE_NODE_LABEL,
    getRule, canCreate, canEdit,
    load, seed,
    createRule, updateRule, toggleRule, resolveIssue, ignoreIssue,
  }
})
