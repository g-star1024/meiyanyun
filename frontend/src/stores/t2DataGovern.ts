// ============================================================
// T2-02 数据治理 store
// 质量规则 / 数据问题 / 数据血缘
// 对齐 T-G-中台与通用.md T2-02 详设
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

// ---- 类型 ----
export type RuleType = 'NOT_NULL' | 'UNIQUE' | 'RANGE' | 'REGEX' | 'CUSTOM'
export type RuleSeverity = 'HIGH' | 'MEDIUM' | 'LOW'
export type IssueStatus = 'OPEN' | 'RESOLVED' | 'IGNORED'
export type LineageNodeType = 'SOURCE' | 'TABLE' | 'TAG' | 'API' | 'REPORT'

export interface QualityRule {
  id: string
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
  id: string
  ruleId: string
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

export const useT2DataGovernStore = defineStore('t2DataGovern', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const rules = ref<QualityRule[]>([])
  const issues = ref<DataIssue[]>([])
  const lineageNodes = ref<LineageNode[]>([])
  const lineageEdges = ref<LineageEdge[]>([])
  const loaded = ref(false)

  // ---- 查询 ----
  const enabledRules = computed(() => rules.value.filter((r) => r.enabled))
  const openIssues = computed(() => issues.value.filter((i) => i.status === 'OPEN'))
  const passRate = computed(() => {
    const en = enabledRules.value
    if (!en.length) return 0
    return Math.round(en.reduce((s, r) => s + r.passRate, 0) / en.length)
  })

  function getRule(id: string) { return rules.value.find((r) => r.id === id) }

  function canCreate() { return auth.can('govern:rule:create') }
  function canEdit() { return auth.can('govern:rule:edit') }

  // ---- 命令 ----
  function createRule(input: {
    name: string; table: string; column: string; type: RuleType
    severity: RuleSeverity; expression: string; enabled?: boolean
  }): QualityRule {
    if (!canCreate()) throw new Error('无规则创建权限')
    const now = new Date().toISOString()
    const r: QualityRule = {
      id: nextId('rule'),
      name: input.name,
      table: input.table,
      column: input.column,
      type: input.type,
      severity: input.severity,
      expression: input.expression,
      enabled: input.enabled ?? true,
      lastCheckAt: null,
      passRate: 100,
      errorCount: 0,
      owner: auth.user.name,
      createdAt: now,
    }
    rules.value.unshift(r)
    activity.log(auth.user.name, `创建质量规则「${r.name}」（${RULE_TYPE_LABEL[r.type]}）`, r.id)
    return r
  }

  function updateRule(id: string, patch: Partial<Pick<QualityRule, 'name' | 'table' | 'column' | 'type' | 'severity' | 'expression' | 'enabled'>>) {
    if (!canEdit()) throw new Error('无规则编辑权限')
    const r = getRule(id)
    if (!r) return
    Object.assign(r, patch)
    activity.log(auth.user.name, `编辑质量规则「${r.name}」`, id)
  }

  function toggleRule(id: string) {
    if (!canEdit()) throw new Error('无规则编辑权限')
    const r = getRule(id)
    if (!r) return
    r.enabled = !r.enabled
    activity.log(auth.user.name, `${r.enabled ? '启用' : '停用'}质量规则「${r.name}」`, id)
  }

  function resolveIssue(id: string) {
    if (!canEdit()) throw new Error('无问题处理权限')
    const i = issues.value.find((x) => x.id === id)
    if (!i || i.status !== 'OPEN') return
    i.status = 'RESOLVED'
    i.resolvedAt = new Date().toISOString()
    // 回填到规则：错误数减少
    const r = getRule(i.ruleId)
    if (r) r.errorCount = Math.max(0, r.errorCount - i.count)
    activity.log(auth.user.name, `标记数据问题已解决：${i.ruleName}（${i.table}.${i.column}）`, id)
  }

  function ignoreIssue(id: string) {
    if (!canEdit()) throw new Error('无问题处理权限')
    const i = issues.value.find((x) => x.id === id)
    if (!i || i.status !== 'OPEN') return
    i.status = 'IGNORED'
    activity.log(auth.user.name, `忽略数据问题：${i.ruleName}`, id)
  }

  // ---- 种子 ----
  function seed() {
    if (loaded.value) return
    loaded.value = true
    const now = Date.now()
    const hoursAgo = (h: number) => new Date(now - h * 3600_000).toISOString()
    const daysAgo = (d: number) => new Date(now - d * 86400_000).toISOString()

    type RSeed = Partial<QualityRule> & Pick<QualityRule, 'name' | 'table' | 'column' | 'type' | 'severity' | 'expression' | 'enabled' | 'passRate' | 'errorCount' | 'owner'>
    const seedRules: RSeed[] = [
      { name: '订单号非空', table: 'orders', column: 'order_no', type: 'NOT_NULL', severity: 'HIGH', expression: 'order_no IS NOT NULL', enabled: true, passRate: 100, errorCount: 0, owner: '张数' },
      { name: '客户手机号唯一', table: 'customers', column: 'phone', type: 'UNIQUE', severity: 'HIGH', expression: 'COUNT(DISTINCT phone) = COUNT(*)', enabled: true, passRate: 98.4, errorCount: 124, owner: '张数' },
      { name: '订单金额合理范围', table: 'orders', column: 'amount', type: 'RANGE', severity: 'HIGH', expression: 'amount BETWEEN 0.01 AND 500000', enabled: true, passRate: 99.6, errorCount: 18, owner: '李析' },
      { name: '手机号格式', table: 'customers', column: 'phone', type: 'REGEX', severity: 'MEDIUM', expression: "phone ~ '^1[3-9]\\d{9}$'", enabled: true, passRate: 96.2, errorCount: 42, owner: '李析' },
      { name: '预约时间非过去', table: 'appointments', column: 'appoint_at', type: 'CUSTOM', severity: 'MEDIUM', expression: 'appoint_at >= created_at', enabled: true, passRate: 99.1, errorCount: 8, owner: '王治' },
      { name: '退款金额不超订单', table: 'refunds', column: 'amount', type: 'CUSTOM', severity: 'HIGH', expression: 'refunds.amount <= orders.amount', enabled: true, passRate: 100, errorCount: 0, owner: '王治' },
      { name: '身份证号格式', table: 'customers', column: 'id_card', type: 'REGEX', severity: 'HIGH', expression: "id_card ~ '^\\d{17}[\\dXx]$'", enabled: false, passRate: 94.8, errorCount: 6, owner: '王治' },
      { name: '员工工号非空', table: 'staff', column: 'staff_no', type: 'NOT_NULL', severity: 'MEDIUM', expression: 'staff_no IS NOT NULL', enabled: true, passRate: 100, errorCount: 0, owner: '张数' },
      { name: '会员等级枚举', table: 'customers', column: 'level', type: 'CUSTOM', severity: 'LOW', expression: "level IN ('NORMAL','SILVER','GOLD','BLACK')", enabled: true, passRate: 99.9, errorCount: 2, owner: '张数' },
      { name: '消费记录时间合法', table: 'orders', column: 'paid_at', type: 'RANGE', severity: 'MEDIUM', expression: "paid_at >= '2020-01-01' AND paid_at <= NOW()", enabled: true, passRate: 100, errorCount: 0, owner: '李析' },
    ]
    seedRules.forEach((s, i) => {
      rules.value.push({
        id: nextId('rule'),
        name: s.name, table: s.table, column: s.column, type: s.type, severity: s.severity,
        expression: s.expression, enabled: s.enabled,
        lastCheckAt: s.enabled ? hoursAgo(i + 1) : null,
        passRate: s.passRate, errorCount: s.errorCount,
        owner: s.owner,
        createdAt: daysAgo(60 - i * 2),
      })
    })

    // 问题清单：根据 passRate < 100 的规则生成
    rules.value.forEach((r) => {
      if (r.errorCount === 0) return
      const issueCount = Math.min(3, Math.ceil(r.errorCount / 50))
      for (let k = 0; k < issueCount; k++) {
        issues.value.push({
          id: nextId('iss'),
          ruleId: r.id,
          ruleName: r.name,
          table: r.table,
          column: r.column,
          sample: pickSample(r),
          count: Math.ceil(r.errorCount / issueCount),
          status: k === 0 ? 'OPEN' : (Math.random() > 0.5 ? 'OPEN' : 'RESOLVED'),
          detectedAt: hoursAgo((k + 1) * 6),
          resolvedAt: k > 0 && Math.random() > 0.5 ? hoursAgo(k * 4) : null,
        })
      }
    })

    // 血缘图：SOURCE → TABLE → TAG → API/REPORT（DAG）
    lineageNodes.value = [
      // 左列：数据源
      { id: 'src-mysql', name: 'MySQL 交易库', type: 'SOURCE', x: 40, y: 80 },
      { id: 'src-pg', name: 'PG 客户库', type: 'SOURCE', x: 40, y: 220 },
      { id: 'src-kafka', name: 'Kafka 埋点', type: 'SOURCE', x: 40, y: 360 },
      // 中左：明细表
      { id: 'tab-orders', name: 'dwd.orders', type: 'TABLE', x: 260, y: 60 },
      { id: 'tab-cust', name: 'dwd.customers', type: 'TABLE', x: 260, y: 200 },
      { id: 'tab-events', name: 'dwd.user_events', type: 'TABLE', x: 260, y: 340 },
      // 中：汇总宽表
      { id: 'tab-dws', name: 'dws.customer_360', type: 'TABLE', x: 480, y: 200 },
      // 右：标签
      { id: 'tag-value', name: '高价值客户', type: 'TAG', x: 700, y: 100 },
      { id: 'tag-churn', name: '流失风险', type: 'TAG', x: 700, y: 240 },
      { id: 'tag-pref', name: '项目偏好', type: 'TAG', x: 700, y: 380 },
      // 最右：API/REPORT
      { id: 'api-cust', name: '/api/v1/customer/profile', type: 'API', x: 920, y: 80 },
      { id: 'api-seg', name: '/api/v1/marketing/segment', type: 'API', x: 920, y: 220 },
      { id: 'rpt-dash', name: '经营驾驶舱', type: 'REPORT', x: 920, y: 360 },
    ]
    lineageEdges.value = [
      { from: 'src-mysql', to: 'tab-orders' },
      { from: 'src-pg', to: 'tab-cust' },
      { from: 'src-kafka', to: 'tab-events' },
      { from: 'tab-orders', to: 'tab-dws' },
      { from: 'tab-cust', to: 'tab-dws' },
      { from: 'tab-events', to: 'tab-dws' },
      { from: 'tab-dws', to: 'tag-value' },
      { from: 'tab-dws', to: 'tag-churn' },
      { from: 'tab-dws', to: 'tag-pref' },
      { from: 'tag-value', to: 'api-cust' },
      { from: 'tag-churn', to: 'api-seg' },
      { from: 'tag-pref', to: 'rpt-dash' },
      { from: 'tag-value', to: 'rpt-dash' },
    ]
  }

  function pickSample(r: QualityRule): string {
    if (r.type === 'REGEX' && r.column === 'phone') return '1380013800 (10位)'
    if (r.type === 'UNIQUE') return `phone='138****8000' 出现 3 次`
    if (r.type === 'RANGE' && r.column === 'amount') return 'order_no=SO202608250091 amount=-200'
    if (r.type === 'CUSTOM' && r.table === 'appointments') return 'appoint_at < created_at'
    return '异常样本示例数据'
  }

  return {
    rules, issues, lineageNodes, lineageEdges,
    enabledRules, openIssues, passRate,
    RULE_TYPE_LABEL, RULE_SEVERITY_LABEL, ISSUE_STATUS_LABEL, LINEAGE_NODE_LABEL,
    getRule, canCreate, canEdit,
    createRule, updateRule, toggleRule, resolveIssue, ignoreIssue, seed,
  }
})
