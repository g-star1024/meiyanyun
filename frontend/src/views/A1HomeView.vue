<script setup lang="ts">
/* ============================================================
 * A1-01 AI 智能中心首页
 * 定位：AI 能力总入口 — 能力矩阵卡片 + 全局效果 KPI + 待办
 * 红线：A1-17 隐私合规、A1-04 敏感词、模型发布走 T3-01 审批
 * ============================================================ */
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import CCard from '@/components/CCard.vue'
import CKpi from '@/components/CKpi.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import { AI_CAPABILITY_STATUS, dictPill } from '@/config/dictionary'
import { useAuthStore } from '@/stores/auth'
import {
  logKpi, monthlyBill, listApprovals, listAlerts, getSensitiveHitStats,
  type AiKpi, type AlertView, type ApprovalView, type SensitiveHitStats,
} from '@/api/ai'
import { fmtAgo } from '@/utils/datetime'

const router = useRouter()
const auth = useAuthStore()

interface AiCapability {
  key: string
  name: string
  desc: string
  icon: 'customer' | 'marketing' | 'alert' | 'sign' | 'box' | 'dashboard' | 'shield'
  to: string
  status: 'online' | 'beta' | 'coming'
  featureCode?: string
}

// 能力目录为产品元数据（名称/图标/跳转/生命周期），保留静态；交易口径（调用量/状态指标）一律接真。
const capabilities = ref<AiCapability[]>([
  { key: 'profile', name: '客户画像', desc: '标签/分群/价值分', icon: 'customer', to: '/ai/profile', status: 'online', featureCode: 'profile' },
  { key: 'repurchase', name: '复购预测', desc: '时机/项目/概率榜', icon: 'marketing', to: '/ai/repurchase', status: 'online' },
  { key: 'churn', name: '流失预警', desc: '因子/风险分/干预', icon: 'alert', to: '/ai/churn-model', status: 'online', featureCode: 'churn' },
  { key: 'scripts', name: '智能话术', desc: '破冰/升单/异议', icon: 'sign', to: '/ai/scripts', status: 'online', featureCode: 'scripts' },
  { key: 'chatbot', name: 'AI 客服', desc: '意图识别/转人工', icon: 'customer', to: '/ai/chatbot', status: 'beta' },
  { key: 'content', name: '内容生成', desc: '文案/海报/合规过滤', icon: 'marketing', to: '/ai/content', status: 'beta', featureCode: 'content' },
  { key: 'knowledge', name: '知识库', desc: '检索/向量化/溯源', icon: 'box', to: '/ai/knowledge', status: 'online' },
  { key: 'scheduling', name: '智能排班', desc: '预测/成本/回填', icon: 'dashboard', to: '/ai/scheduling', status: 'online', featureCode: 'scheduling' },
  { key: 'daily', name: '经营日报', desc: '摘要/异常/建议', icon: 'dashboard', to: '/ai/daily-report', status: 'online' },
  { key: 'sensitive', name: '敏感词检测', desc: '实时拦截/词库', icon: 'alert', to: '/ai/sensitive', status: 'online' },
  { key: 'govern', name: '审批与评估', desc: 'AI动作受控/AB', icon: 'shield', to: '/ai/govern', status: 'online', featureCode: 'govern' },
  { key: 'privacy', name: '隐私合规', desc: '脱敏/等保/审计', icon: 'shield', to: '/ai/privacy', status: 'online' },
])

const canGateway = computed(() => auth.can('aiGateway:view'))
const canAdmin = computed(() => auth.can('aiAdmin:view'))
const canGovern = computed(() => auth.can('aiGovern:view'))

const kpiRaw = ref<AiKpi | null>(null)
const hitStats = ref<SensitiveHitStats | null>(null)
const billMap = ref<Record<string, number>>({})
const pendingCount = ref(0)

const ALERT_METRIC_LABEL: Record<string, string> = {
  LATENCY_P99: 'P99 延迟',
  ERROR_RATE: '错误率',
  CALL_COUNT: '调用量',
  SUCCESS_RATE: '成功率',
  QUOTA_WATERMARK: '配额水位',
}
function fmtAlertMetric(metric: string, v: number) {
  if (metric === 'LATENCY_P99') return `${Math.round(v)}ms`
  if (metric === 'CALL_COUNT') return `${v} 次`
  return `${v}%`
}

const APPROVAL_TYPE_LABEL: Record<string, string> = {
  PROVIDER: '供应商接入',
  MODEL: '模型发布',
  BINDING: '功能绑定',
}

const kpis = computed(() => [
  {
    label: '今日 AI 调用', icon: 'settings',
    value: canGateway.value && kpiRaw.value ? kpiRaw.value.todayCalls.toLocaleString() : '—',
    tone: 'purple' as const,
  },
  {
    label: '敏感词拦截（今日）', icon: 'alert',
    value: canAdmin.value && hitStats.value ? hitStats.value.todayHits.toLocaleString() : '—',
    tone: 'danger' as const,
  },
  {
    label: '今日调用成功率', icon: 'trend-up',
    value: canGateway.value && kpiRaw.value ? `${kpiRaw.value.successRate}%` : '—',
    tone: 'teal' as const,
  },
  {
    label: '待审批', icon: 'shield',
    value: canGovern.value ? String(pendingCount.value) : '—',
    tone: 'warning' as const,
    trend: canGovern.value && pendingCount.value > 0 ? '待处理' : '',
    trendUp: false, trendGood: false,
  },
])

interface TodoItem {
  id: string
  type: 'approval' | 'alert'
  title: string
  desc: string
  time: string
  to: string
}

const pendingApprovals = ref<ApprovalView[]>([])
const activeAlerts = ref<AlertView[]>([])

const todos = computed<TodoItem[]>(() => {
  const list: TodoItem[] = []
  if (canGovern.value) {
    pendingApprovals.value.slice(0, 5).forEach((a) => {
      list.push({
        id: `apr-${a.approvalId}`,
        type: 'approval',
        title: `${APPROVAL_TYPE_LABEL[a.approvalType] || a.approvalType}审批`,
        desc: a.content,
        time: fmtAgo(a.appliedAt),
        to: '/ai/govern',
      })
    })
  }
  if (canAdmin.value && hitStats.value && hitStats.value.todayHits > 0) {
    list.push({
      id: 'sensitive-today',
      type: 'alert',
      title: '敏感词命中',
      desc: `今日已拦截敏感词 ${hitStats.value.todayHits} 次，累计 ${hitStats.value.totalHits} 次`,
      time: '实时',
      to: '/ai/sensitive',
    })
  }
  if (canGateway.value) {
    activeAlerts.value.slice(0, 5).forEach((a) => {
      list.push({
        id: `alert-${a.ruleId}`,
        type: 'alert',
        title: a.ruleName,
        desc: `${ALERT_METRIC_LABEL[a.metric] || a.metric}当前 ${fmtAlertMetric(a.metric, a.currentValue)}，规则 ${a.compareOp} ${fmtAlertMetric(a.metric, a.thresholdNum)}`,
        time: '实时',
        to: '/ai/gateway',
      })
    })
  }
  return list.slice(0, 6)
})

const capCalls = computed(() => {
  const m: Record<string, string> = {}
  capabilities.value.forEach((c) => {
    if (c.featureCode && canGateway.value) {
      const calls = billMap.value[c.featureCode]
      if (calls != null) m[c.key] = `本月 ${calls.toLocaleString()} 次调用`
    }
    if (c.key === 'sensitive' && canAdmin.value && hitStats.value && hitStats.value.todayHits > 0) {
      m[c.key] = `今日拦截 ${hitStats.value.todayHits.toLocaleString()} 次`
    }
  })
  return m
})

onMounted(async () => {
  // 按权限分组拉取：网关口径（调用/账单/告警）、治理口径（审批）、安全口径（敏感词统计），无权限直接降级，不发请求避免 403。
  if (canGateway.value) {
    const [k, b, al] = await Promise.allSettled([logKpi(), monthlyBill(), listAlerts()])
    if (k.status === 'fulfilled') kpiRaw.value = k.value
    if (b.status === 'fulfilled') {
      billMap.value = Object.fromEntries(b.value.map((x) => [x.featureCode ?? '', x.calls]))
    }
    if (al.status === 'fulfilled') activeAlerts.value = al.value.filter((x) => x.active)
  }
  if (canGovern.value) {
    const ap = await listApprovals({ status: 'PENDING', page: 0, size: 5 }).catch(() => null)
    if (ap) {
      pendingApprovals.value = ap.content
      pendingCount.value = ap.totalElements
    }
  }
  if (canAdmin.value) {
    const st = await getSensitiveHitStats().catch(() => null)
    if (st) hitStats.value = st
  }
})

const aiPill = (s: AiCapability['status']) => dictPill(AI_CAPABILITY_STATUS[s.toUpperCase() as 'ONLINE' | 'BETA' | 'COMING'])

const todoPill = (t: TodoItem['type']) => (
  t === 'approval'
    ? { status: 'warning' as const, label: '审批' }
    : { status: 'danger' as const, label: '告警' }
)

function go(to: string) {
  router.push(to)
}
</script>

<template>
  <div class="a1-home">
    <!-- KPI 概览 -->
    <div class="a1-home__kpis">
      <CKpi v-for="k in kpis" :key="k.label" v-bind="k" />
    </div>

    <div class="a1-home__body">
      <!-- 能力矩阵 -->
      <CCard padding="lg" class="a1-home__cap">
        <template #header>
          <div class="card-head">
            <h3>AI 能力矩阵</h3>
            <span class="card-sub">12 项能力 · 模型发布须经 T3-01 审批</span>
          </div>
        </template>
        <div class="cap-grid">
          <div
            v-for="cap in capabilities"
            :key="cap.key"
            class="cap-card"
            :class="`cap-card--${cap.status}`"
            @click="cap.status !== 'coming' && go(cap.to)"
          >
            <div class="cap-card__icon">
              <CIcon :name="cap.icon" :size="22" />
            </div>
            <div class="cap-card__body">
              <div class="cap-card__title">
                {{ cap.name }}
                <CStatusPill :status="aiPill(cap.status).status" dot>
                  {{ aiPill(cap.status).text }}
                </CStatusPill>
              </div>
              <div class="cap-card__desc">{{ cap.desc }}</div>
              <div v-if="capCalls[cap.key]" class="cap-card__calls">{{ capCalls[cap.key] }}</div>
            </div>
          </div>
        </div>
      </CCard>

      <!-- 待办 -->
      <CCard padding="lg" class="a1-home__todo">
        <template #header>
          <div class="card-head">
            <h3>待办与告警</h3>
            <a class="card-head__link" @click="go('/approval')">查看全部 →</a>
          </div>
        </template>
        <div class="todo-list">
          <div v-for="t in todos" :key="t.id" class="todo-item" @click="go(t.to)">
            <CStatusPill :status="todoPill(t.type).status" size="sm">{{ todoPill(t.type).label }}</CStatusPill>
            <div class="todo-item__body">
              <div class="todo-item__title">{{ t.title }}</div>
              <div class="todo-item__desc">{{ t.desc }}</div>
            </div>
            <div class="todo-item__time">{{ t.time }}</div>
          </div>
          <div v-if="todos.length === 0" class="todo-empty">暂无待办，各项指标运行正常</div>
        </div>
        <!-- 红线提示条 -->
        <div class="redline-bar">
          <span class="redline-bar__title">合规红线</span>
          <span class="redline-bar__text">模型不得自动上线 · 训练集须授权/去标识/保留期 · 敏感词实时拦截 · AI 建议不覆盖禁忌硬阻断</span>
        </div>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.a1-home { display: flex; flex-direction: column; gap: var(--s-lg); }
.a1-home__kpis { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .a1-home__kpis { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }

.a1-home__body { display: grid; grid-template-columns: 1fr 380px; gap: var(--s-lg); }
@media (max-width: 1100px) { .a1-home__body { grid-template-columns: 1fr; } }

.card-head { display: flex; align-items: center; justify-content: space-between; width: 100%; }
.card-head h3 { margin: 0; font-size: var(--t-md); font-weight: 600; color: var(--c-text); }
.card-sub { font-size: var(--t-xs); color: var(--c-text-3); }
.card-head__link { font-size: var(--t-sm); color: var(--c-brand); cursor: pointer; }
.card-head__link:hover { text-decoration: underline; }

/* 能力卡片网格 */
.cap-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: var(--s-md); }
.cap-card {
  display: flex; gap: var(--s-md); padding: var(--s-md);
  background: var(--c-purple-soft); border: 1px solid transparent; border-radius: var(--r-lg);
  cursor: pointer; transition: all .15s;
}
.cap-card:hover { border-color: var(--c-purple); box-shadow: 0 2px 8px rgba(140,92,245,.12); }
.cap-card--coming { opacity: .5; cursor: not-allowed; }
.cap-card--coming:hover { border-color: transparent; box-shadow: none; }
.cap-card__icon {
  flex-shrink: 0; width: 44px; height: 44px; border-radius: var(--r-md);
  background: var(--c-purple); color: #fff;
  display: flex; align-items: center; justify-content: center;
}
.cap-card__body { min-width: 0; flex: 1; }
.cap-card__title { display: flex; align-items: center; gap: 6px; font-size: var(--t-sm); font-weight: 600; color: var(--c-text); margin-bottom: 2px; }
.cap-card__desc { font-size: var(--t-xs); color: var(--c-text-2); line-height: 1.4; }
.cap-card__calls { font-size: 11px; color: var(--c-purple); margin-top: 6px; font-variant-numeric: tabular-nums; }

/* 待办列表 */
.todo-list { display: flex; flex-direction: column; gap: var(--s-sm); }
.todo-item {
  display: flex; align-items: flex-start; gap: var(--s-sm); padding: var(--s-sm) 0;
  border-bottom: 1px solid var(--c-border-light); cursor: pointer;
}
.todo-item:last-child { border-bottom: none; }
.todo-item:hover .todo-item__title { color: var(--c-brand); }
.todo-item__body { flex: 1; min-width: 0; }
.todo-item__title { font-size: var(--t-sm); font-weight: 500; color: var(--c-text); }
.todo-item__desc { font-size: var(--t-xs); color: var(--c-text-3); line-height: 1.4; margin-top: 2px; }
.todo-item__time { font-size: 11px; color: var(--c-text-3); flex-shrink: 0; }
.todo-empty { padding: var(--s-lg) 0; text-align: center; font-size: var(--t-xs); color: var(--c-text-3); }

/* 红线提示 */
.redline-bar {
  margin-top: var(--s-md); padding: var(--s-sm) var(--s-md);
  background: var(--c-danger-bg); border-radius: var(--r-md);
  display: flex; align-items: center; gap: var(--s-sm);
}
.redline-bar__title { font-size: var(--t-xs); font-weight: 600; color: var(--c-danger-fg); flex-shrink: 0; }
.redline-bar__text { font-size: 11px; color: var(--c-danger-fg); line-height: 1.4; }
</style>
