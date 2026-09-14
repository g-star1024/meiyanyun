import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import {
  getAuditFacets,
  pageAuditLogs,
  verifyAuditChain,
  type AuditChainVerifyResult,
  type AuditFacets,
  type AuditLogRow,
} from '@/api/audit'
import { listStaff } from '@/api/org'
import { useAuthStore } from '@/stores/auth'

// ============================================================
// 审计日志 store（M1 集团管控 / 审计日志）—— 接真实 audit-service（B49 卡2）
// - 数据源：GET /api/audit/page（分页检索）+ /facets（统计）+ /verify（链巡检）
// - audit_log 为 append-only SHA-256 哈希链，库内无 ip/risk/result 字段：
//   「敏感操作」为展示层派生口径（动作命中敏感词表），并非库字段
// - 操作人为工号（或 system / 少量历史中文名），经员工列表解析为姓名显示
// ============================================================

export type { AuditLogRow }

/** bizType → 中文模块标签（覆盖库内真实分布；未映射新值回落显示原值）。 */
export const BIZTYPE_LABEL: Record<string, string> = {
  STAFF: '员工管理', ROLE: '角色权限', ORG: '组织机构',
  CUSTOMER: '客户档案', TAG: '客户标签', LEVEL: '客户等级', CARD: '客户卡项', CARD_CANCEL: '卡项退卡',
  POINTS: '积分账户', CONSULT: '咨询记录', PLAN: '咨询方案',
  APPT: '预约管理', ARRIVAL: '到诊接待', CHECKIN: '报到', WAITLIST: '等位队列',
  EMR: '电子病历', ORDER: '订单', REFUND: '退款', DUAL_SIGN: '双签审批',
  FUND_ENTRY: '资金流水', FUND_RECONCILE: '资金对账', FUND_ADJUST: '资金调整', FIN_CARRY: '成本结转',
  COUPON: '优惠券', COUPON_WRITEOFF: '券核销', WRITEOFF: '核销', WDESK: '核销台',
  GRANT_ISSUE: '赠予发放', GRANT_DEDUCT: '赠予划扣', GRANT_RULE: '赠予规则', GRANT_REFUND: '赠予退回',
  CAMPAIGN: '营销活动', MALL: '积分商城', MARKETING_CFG: '营销设置', PUSH: '消息推送',
  CONSUMABLE: '耗材库存', REQUISITION: '耗材领用', BOM: '项目配方',
  DICT: '数据字典', APPROVAL: '审批流', CONTRA_EXEMPT: '禁忌豁免',
  REPURCHASE: '复购管理', CUSTOMER_SEARCH_EVENT: '客户搜索',
  AI_KNOWLEDGE: 'AI 知识库', AI_MODEL: 'AI 模型', AI_FEATURE: 'AI 功能', AI_APPROVAL: 'AI 审批',
  AI_SCRIPT: 'AI 话术', AI_DAILY_REPORT: 'AI 日报', AI_SENSITIVE_WORD: 'AI 敏感词',
  AI_EVAL: 'AI 评测', AI_PRIVACY: 'AI 隐私', AI_EXPERIMENT: 'AI 实验',
  AI_REPURCHASE_PREDICTION: 'AI 复购预测', AI_QUOTA: 'AI 配额', AI_CHURN_PREDICTION: 'AI 流失预测',
  AI_CHATBOT: 'AI 客服', AI_SCHEDULING: 'AI 排班', AI_CONTENT_RECORD: 'AI 内容',
  AI_PROVIDER: 'AI 供应商', AI_CUSTOMER_PROFILE: 'AI 客户画像', AI_SENSITIVE_HIT: 'AI 敏感命中',
  AI_CFG: 'AI 配置', AUDIT_OUTBOX: '审计补偿',
}

/**
 * 敏感操作派生口径（展示层，非库字段）：动作命中删除/停用/驳回/撤销/转移/退款类语义
 * 的视为敏感操作标红，辅助审计员快速定位高风险行。
 */
const SENSITIVE_RE =
  /DELETE|DISABLE|REVOKE|REJECT|CANCEL|RESET|TRANSFER|REFUND|OFF_SHELF|DEDUCT|关闭|停用|删除|撤销|驳回|退卡|退款|转移|划扣/

export interface AuditFilters {
  bizType: string
  actor: string
  keyword: string
  /** datetime-local 值（YYYY-MM-DDTHH:mm），空串=不限 */
  from: string
  to: string
}

export const useM1AuditStore = defineStore('m1Audit', () => {
  const auth = useAuthStore()

  const items = ref<AuditLogRow[]>([])
  const total = ref(0)
  const page = ref(0)
  const size = ref(20)
  const filters = ref<AuditFilters>({ bizType: '', actor: '', keyword: '', from: '', to: '' })

  const facets = ref<AuditFacets | null>(null)
  const verify = ref<AuditChainVerifyResult | null>(null)
  const loading = ref(false)
  const error = ref('')

  /** 工号 → 姓名缓存（listStaff 一次解析，当前登录人优先取 auth）。 */
  const nameCache = new Map<string, string>()
  const namesResolved = ref(false)

  const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size.value)))
  const stats = computed(() => ({
    total: facets.value?.total ?? 0,
    last24: facets.value?.last24 ?? 0,
    actors: facets.value?.actors ?? 0,
  }))

  function bizLabel(bizType: string): string {
    return BIZTYPE_LABEL[bizType] ?? bizType
  }

  function isSensitive(action: string): boolean {
    return SENSITIVE_RE.test(action)
  }

  function displayActor(actor: string): string {
    if (actor === 'system') return '系统'
    return nameCache.get(actor) ?? actor
  }

  /** datetime-local 值 → ISO 带偏移；空串返回 undefined。 */
  function toIso(v: string): string | undefined {
    if (!v) return undefined
    const d = new Date(v)
    return Number.isNaN(d.getTime()) ? undefined : d.toISOString()
  }

  async function resolveNames() {
    if (namesResolved.value) return
    if (auth.user?.staffId && auth.user?.name) nameCache.set(auth.user.staffId, auth.user.name)
    try {
      const { data } = await listStaff()
      data.forEach((s) => nameCache.set(s.staffId, s.staffName))
    } catch {
      // 姓名解析失败回落工号原值，不阻断页面
    }
    namesResolved.value = true
  }

  async function loadFacets() {
    try {
      const { data } = await getAuditFacets()
      facets.value = data
    } catch (e) {
      console.error('审计统计加载失败', e)
    }
  }

  async function checkChain() {
    try {
      const { data } = await verifyAuditChain()
      verify.value = data
    } catch (e) {
      console.error('链巡检失败', e)
      verify.value = null
    }
  }

  /** 检索：p 为目标页（0 起）；filters 变化时调用方应传 0。 */
  async function search(p = 0) {
    loading.value = true
    error.value = ''
    try {
      const { data } = await pageAuditLogs({
        bizType: filters.value.bizType || undefined,
        actor: filters.value.actor.trim() || undefined,
        keyword: filters.value.keyword.trim() || undefined,
        from: toIso(filters.value.from),
        to: toIso(filters.value.to),
        page: p,
        size: size.value,
      })
      items.value = data.items
      total.value = data.total
      page.value = data.page
    } catch (e) {
      error.value = '审计日志加载失败，请稍后重试'
      console.error('审计日志检索失败', e)
      items.value = []
      total.value = 0
    } finally {
      loading.value = false
    }
  }

  function resetFilters() {
    filters.value = { bizType: '', actor: '', keyword: '', from: '', to: '' }
    return search(0)
  }

  function setSize(n: number) {
    size.value = n
    return search(0)
  }

  /** 页面挂载：统计 + 首页 + 链巡检 + 姓名解析并行。 */
  async function init() {
    await Promise.all([loadFacets(), search(0), checkChain(), resolveNames()])
  }

  return {
    items, total, page, size, totalPages, filters, facets, verify, loading, error, stats,
    BIZTYPE_LABEL, bizLabel, isSensitive, displayActor,
    init, search, resetFilters, setSize, loadFacets, checkChain,
  }
})
