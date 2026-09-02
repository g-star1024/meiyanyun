<script setup lang="ts">
/* ============================================================
 * A1-01 AI 智能中心首页
 * 定位：AI 能力总入口 — 能力矩阵卡片 + 全局效果 KPI + 待办
 * 红线：A1-17 隐私合规、A1-04 敏感词、模型发布走 T3-01 审批
 * ============================================================ */
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import CCard from '@/components/CCard.vue'
import CKpi from '@/components/CKpi.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import { AI_CAPABILITY_STATUS, dictPill } from '@/config/dictionary'

const router = useRouter()

interface AiCapability {
  key: string
  name: string
  desc: string
  icon: 'customer' | 'marketing' | 'alert' | 'sign' | 'box' | 'dashboard' | 'shield'
  to: string
  status: 'online' | 'beta' | 'coming'
  calls: number
}

const capabilities = ref<AiCapability[]>([
  { key: 'profile', name: '客户画像', desc: '标签/分群/价值分', icon: 'customer', to: '/ai/profile', status: 'online', calls: 12840 },
  { key: 'repurchase', name: '复购预测', desc: '时机/项目/概率榜', icon: 'marketing', to: '/ai/repurchase', status: 'online', calls: 8320 },
  { key: 'churn', name: '流失预警', desc: '因子/风险分/干预', icon: 'alert', to: '/ai/churn-model', status: 'online', calls: 5640 },
  { key: 'scripts', name: '智能话术', desc: '破冰/升单/异议', icon: 'sign', to: '/ai/scripts', status: 'online', calls: 21300 },
  { key: 'chatbot', name: 'AI 客服', desc: '意图识别/转人工', icon: 'customer', to: '/ai/chatbot', status: 'beta', calls: 3420 },
  { key: 'content', name: '内容生成', desc: '文案/海报/合规过滤', icon: 'marketing', to: '/ai/content', status: 'beta', calls: 1980 },
  { key: 'knowledge', name: '知识库', desc: '检索/向量化/溯源', icon: 'box', to: '/ai/knowledge', status: 'online', calls: 9750 },
  { key: 'scheduling', name: '智能排班', desc: '预测/成本/回填', icon: 'dashboard', to: '/ai/scheduling', status: 'online', calls: 420 },
  { key: 'daily', name: '经营日报', desc: '摘要/异常/建议', icon: 'dashboard', to: '/ai/daily-report', status: 'online', calls: 1860 },
  { key: 'sensitive', name: '敏感词检测', desc: '实时拦截/词库', icon: 'alert', to: '/ai/sensitive', status: 'online', calls: 45200 },
  { key: 'govern', name: '审批与评估', desc: 'AI动作受控/AB', icon: 'shield', to: '/ai/govern', status: 'online', calls: 320 },
  { key: 'privacy', name: '隐私合规', desc: '脱敏/等保/审计', icon: 'shield', to: '/ai/privacy', status: 'online', calls: 0 },
])

const kpis = computed(() => [
  { label: '今日 AI 调用', icon: 'settings', value: '112,050', tone: 'purple' as const, trend: '+8.2%', trendUp: true, trendGood: true },
  { label: '敏感词拦截', icon: 'alert', value: '347', tone: 'danger' as const, trend: '-12%', trendUp: false, trendGood: true },
  { label: '话术采纳率', icon: 'chat', value: '68.4%', tone: 'teal' as const, trend: '+3.1%', trendUp: true, trendGood: true },
  { label: '待审批模型', icon: 'settings', value: '3', tone: 'warning' as const, trend: 'T3-01', trendUp: false, trendGood: false },
])

interface TodoItem {
  id: string
  type: 'approval' | 'alert' | 'review'
  title: string
  desc: string
  time: string
  to: string
}

const todos = ref<TodoItem[]>([
  { id: 'T1', type: 'approval', title: '模型发布审批', desc: 'churn-v2.3 提交发布申请，等待 T3-01 审批', time: '10 分钟前', to: '/ai/govern' },
  { id: 'T2', type: 'approval', title: '训练集授权核验', desc: 'profile-train-0815 语料待授权核验（12,400 条）', time: '1 小时前', to: '/ai/models' },
  { id: 'T3', type: 'alert', title: '敏感词命中激增', desc: 'M5 推送渠道近 1h 命中 23 次，超阈值', time: '32 分钟前', to: '/ai/sensitive' },
  { id: 'T4', type: 'review', title: '话术效果复核', desc: '升单话术 S-031 转化率下降 5.2%，建议复核', time: '2 小时前', to: '/ai/scripts' },
  { id: 'T5', type: 'alert', title: '模型 P99 延迟告警', desc: 'repurchase-v1.8 P99 = 285ms 超阈值 200ms', time: '3 小时前', to: '/ai/monitor' },
])

const aiPill = (s: AiCapability['status']) => dictPill(AI_CAPABILITY_STATUS[s.toUpperCase() as 'ONLINE' | 'BETA' | 'COMING'])

const todoPill = (t: TodoItem['type']) => {
  if (t === 'approval') return { status: 'warning' as const, label: '审批' }
  if (t === 'alert') return { status: 'danger' as const, label: '告警' }
  return { status: 'info' as const, label: '复核' }
}

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
              <div v-if="cap.calls > 0" class="cap-card__calls">今日 {{ cap.calls.toLocaleString() }} 次调用</div>
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

/* 红线提示 */
.redline-bar {
  margin-top: var(--s-md); padding: var(--s-sm) var(--s-md);
  background: var(--c-danger-bg); border-radius: var(--r-md);
  display: flex; align-items: center; gap: var(--s-sm);
}
.redline-bar__title { font-size: var(--t-xs); font-weight: 600; color: var(--c-danger-fg); flex-shrink: 0; }
.redline-bar__text { font-size: 11px; color: var(--c-danger-fg); line-height: 1.4; }
</style>
