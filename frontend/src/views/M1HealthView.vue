<template>
  <div class="hl">
    <!-- KPI -->
    <div class="hl__kpis">
      <CKpi :value="String(h.overallScore)" label="集团健康均分" tone="brand" icon="trend-up" />
      <CKpi :value="String(h.healthyCount)" label="健康门店" tone="success" icon="store" />
      <CKpi :value="String(h.warningCount)" label="预警门店" tone="warning" icon="alert" />
      <CKpi :value="String(h.criticalCount)" label="严重门店" tone="danger" icon="alert" />
      <CKpi :value="String(h.openIssues.length)" label="待整改问题" tone="danger" icon="alert" />
    </div>

    <div class="hl__body">
      <!-- 左：门店列表 -->
      <CCard title="门店健康度" padding="none" class="hl__list">
        <div class="tenant" v-for="t in h.tenants" :key="t.tenantId"
             :class="{ 'is-active': selId === t.tenantId }" @click="selId = t.tenantId">
          <div class="tenant__top">
            <span class="tenant__name">{{ t.tenantName }}</span>
            <CStatusPill :status="statusTone(h.statusOf(t))">{{ STATUS_LABEL[h.statusOf(t)] }}</CStatusPill>
          </div>
          <div class="tenant__meta">
            <span><CIcon name="store" :size="12" /> {{ t.region }}</span>
            <span><CIcon name="calendar" :size="12" /> 下次 {{ t.nextCheckAt }}</span>
          </div>
          <div class="tenant__score">
            <span class="score-num" :class="'score--' + h.statusOf(t)">{{ h.scoreOf(t) }}</span>
            <CProgressBar :value="h.scoreOf(t)" :color="barColor(h.statusOf(t))" :show-label="false" />
          </div>
        </div>
      </CCard>

      <!-- 右：详情 -->
      <CCard class="hl__detail" padding="lg">
        <template v-if="sel">
          <div class="detail__head">
            <div>
              <h3>{{ sel.tenantName }}</h3>
              <div class="detail__sub">
                <span>{{ sel.region }}</span>
                <span>上次巡检 {{ sel.lastCheckedAt }} · {{ sel.inspector }}</span>
                <span>下次 {{ sel.nextCheckAt }}</span>
              </div>
            </div>
            <CButton v-if="canEdit" size="sm" @click="rerun"><CIcon name="check" :size="14" /> 重新巡检</CButton>
          </div>

          <!-- 六维雷达（用条形代替） -->
          <div class="radar">
            <div class="radar__row" v-for="s in sel.scores" :key="s.dimension">
              <span class="radar__label"><CIcon :name="(DIM_ICON[s.dimension] as any)" :size="16" /> {{ DIM_LABEL[s.dimension] }}</span>
              <CProgressBar :value="s.score" :height="10" :color="barColor(scoreStatus(s.score))" :show-label="false" />
              <span class="radar__val" :class="'score--' + scoreStatus(s.score)">{{ s.score }}</span>
            </div>
          </div>

          <!-- 整改任务 -->
          <div class="issues">
            <div class="issues__h">整改任务 <span class="cnt">{{ tenantIssues.length }}</span></div>
            <div class="issue" v-for="it in tenantIssues" :key="it.id">
              <div class="issue__left">
                <div class="issue__tags">
                  <span class="tag tag--dim">{{ DIM_LABEL[it.dimension] }}</span>
                  <span class="tag" :class="'tag--' + it.severity.toLowerCase()">{{ sevLabel(it.severity) }}</span>
                  <span class="tag" :class="'tag--st-' + it.status.toLowerCase()">{{ ISSUE_STATUS_LABEL[it.status] }}</span>
                </div>
                <div class="issue__title">{{ it.title }}</div>
                <div class="issue__detail">{{ it.detail }}</div>
                <div class="issue__meta">
                  <span>负责人 {{ it.assignee || '—' }}</span>
                  <span v-if="it.dueAt">截止 {{ it.dueAt }}</span>
                  <span v-if="it.resolvedAt">解决于 {{ it.resolvedAt }}</span>
                </div>
                <div class="issue__res" v-if="it.resolution">整改结果：{{ it.resolution }}</div>
              </div>
              <div class="issue__ops" v-if="canEdit && (it.status==='OPEN'||it.status==='PROCESSING')">
                <CButton v-if="it.status==='OPEN'" size="sm" @click="h.startIssue(it.id)">开始处理</CButton>
                <CButton v-if="it.status==='PROCESSING'" size="sm" variant="primary" @click="openResolve(it)">解决</CButton>
                <CButton size="sm" variant="ghost" @click="h.ignoreIssue(it.id)">忽略</CButton>
              </div>
            </div>
            <div v-if="!tenantIssues.length" class="empty">暂无整改任务</div>
          </div>
        </template>
      </CCard>
    </div>

    <!-- 解决弹层 -->
    <CDrawer :show="!!resolving" title="标记问题已解决" size="sm" @update:show="(v: boolean) => { if (!v) resolving = null }">
      <div class="form-row">
        <label>整改说明</label>
        <textarea v-model="resolution" rows="4" placeholder="描述整改措施与结果"></textarea>
      </div>
      <template #footer>
        <CButton variant="ghost" @click="resolving = null">取消</CButton>
        <CButton variant="primary" :disabled="!resolution.trim()" @click="confirmResolve">确认解决</CButton>
      </template>
    </CDrawer>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CProgressBar from '@/components/CProgressBar.vue'
import CDrawer from '@/components/CDrawer.vue'
import CKpi from '@/components/CKpi.vue'
import { useM1HealthStore, DIM_LABEL, DIM_ICON, STATUS_LABEL, ISSUE_STATUS_LABEL, scoreStatus,
  type CheckStatus, type HealthIssue, type Severity } from '@/stores/m1Health'
import { useAuthStore } from '@/stores/auth'

const h = useM1HealthStore()
const auth = useAuthStore()
onMounted(() => h.seed())

const canEdit = computed(() => auth.can('health:edit') || auth.isSuper)
const selId = ref('T01')
const sel = computed(() => h.tenants.find((t) => t.tenantId === selId.value))
const tenantIssues = computed(() => h.issues.filter((i) => i.tenantId === selId.value))

const resolving = ref<HealthIssue | null>(null)
const resolution = ref('')
function openResolve(it: HealthIssue) { resolving.value = it; resolution.value = '' }
function confirmResolve() {
  if (resolving.value && resolution.value.trim()) h.resolveIssue(resolving.value.id, resolution.value.trim())
  resolving.value = null
}
function rerun() { if (sel.value) h.rerun(sel.value.tenantId, auth.user?.name || '系统') }

function statusTone(s: CheckStatus): 'success' | 'warning' | 'danger' | 'info' {
  return s === 'HEALTHY' ? 'success' : s === 'WARNING' ? 'warning' : s === 'CRITICAL' ? 'danger' : 'info'
}
function barColor(s: CheckStatus): string {
  return s === 'HEALTHY' ? 'var(--c-success-fg)' : s === 'WARNING' ? 'var(--c-warning-fg)' : 'var(--c-danger-fg)'
}
function sevLabel(s: Severity) { return s === 'HIGH' ? '高' : s === 'MEDIUM' ? '中' : '低' }
</script>

<style scoped>
.hl { display: flex; flex-direction: column; gap: var(--s-lg); }
.hl__kpis { display: flex; gap: var(--s-md); flex-wrap: wrap; }
.hl__kpis :deep(.ckpi) { flex: 1 1 0; min-width: 168px; padding: var(--s-lg); }

.hl__body { display: grid; grid-template-columns: 360px 1fr; gap: var(--s-lg); align-items: start; }

/* 左：门店列表 */
.tenant { padding: var(--s-md) var(--s-lg); cursor: pointer; border-bottom: 1px solid var(--c-border); transition: background .15s; }
.tenant:last-child { border-bottom: none; }
.tenant:hover { background: var(--c-surface-muted); }
.tenant.is-active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.tenant__top { display: flex; justify-content: space-between; align-items: center; gap: var(--s-sm); }
.tenant__name { font-weight: 600; font-size: var(--t-sm); color: var(--c-text); }
.tenant__meta { display: flex; gap: var(--s-md); font-size: var(--t-xs); color: var(--c-text-3); margin: var(--s-xs) 0 var(--s-sm); flex-wrap: wrap; }
.tenant__meta span { display: inline-flex; align-items: center; gap: 4px; }
.tenant__score { display: flex; align-items: center; gap: var(--s-sm); }
.score-num { font-weight: 700; font-size: var(--t-xl); min-width: 36px; font-variant-numeric: tabular-nums; line-height: 1; }
.score--HEALTHY { color: var(--c-success-fg); }
.score--WARNING { color: var(--c-warning-fg); }
.score--CRITICAL { color: var(--c-danger-fg); }
.tenant__score :deep(.pbar) { flex: 1; }

/* 右：详情 */
.detail__head { display: flex; justify-content: space-between; align-items: flex-start; gap: var(--s-md); margin-bottom: var(--s-lg); }
.detail__head h3 { margin: 0 0 var(--s-xs); font-size: var(--t-lg); font-weight: 700; }
.detail__sub { display: flex; gap: var(--s-md); font-size: var(--t-xs); color: var(--c-text-3); flex-wrap: wrap; }

/* 六维 */
.radar { display: flex; flex-direction: column; gap: var(--s-md); padding: var(--s-lg); background: var(--c-surface-muted); border-radius: var(--r-lg); margin-bottom: var(--s-lg); }
.radar__row { display: grid; grid-template-columns: 132px 1fr 44px; align-items: center; gap: var(--s-md); }
.radar__label { font-size: var(--t-sm); color: var(--c-text-2); display: inline-flex; align-items: center; gap: var(--s-xs); white-space: nowrap; }
.radar__val { text-align: right; font-weight: 700; font-size: var(--t-sm); font-variant-numeric: tabular-nums; }

/* 整改任务 */
.issues__h { font-weight: 600; font-size: var(--t-sm); margin-bottom: var(--s-sm); display: flex; align-items: center; gap: var(--s-xs); }
.cnt { background: var(--c-brand); color: #fff; border-radius: 10px; padding: 0 8px; font-size: var(--t-xs); line-height: 18px; min-width: 18px; text-align: center; }
.issue { display: flex; justify-content: space-between; gap: var(--s-md); padding: var(--s-md) 0; border-bottom: 1px solid var(--c-border); }
.issue:last-of-type { border-bottom: none; }
.issue__tags { display: flex; gap: var(--s-xs); margin-bottom: var(--s-xs); flex-wrap: wrap; }
.tag { font-size: var(--t-xs); padding: 2px 8px; border-radius: var(--r-sm); background: var(--c-surface-muted); color: var(--c-text-2); }
.tag--dim { background: var(--c-brand-soft); color: var(--c-brand); }
.tag--high { background: rgba(239, 68, 68, .12); color: var(--c-danger-fg); }
.tag--medium { background: rgba(245, 158, 11, .12); color: var(--c-warning-fg); }
.tag--low { background: rgba(100, 116, 139, .12); color: var(--c-text-3); }
.tag--st-processing { background: rgba(59, 130, 246, .12); color: #2563eb; }
.tag--st-resolved { background: rgba(34, 197, 94, .12); color: var(--c-success-fg); }
.tag--st-open { background: rgba(239, 68, 68, .1); color: var(--c-danger-fg); }
.tag--st-ignored { background: var(--c-surface-muted); color: var(--c-text-3); }
.issue__title { font-weight: 600; font-size: var(--t-sm); }
.issue__detail { font-size: var(--t-xs); color: var(--c-text-2); margin: var(--s-xs) 0; line-height: 1.6; }
.issue__meta { display: flex; gap: var(--s-md); font-size: var(--t-xs); color: var(--c-text-3); flex-wrap: wrap; }
.issue__res { font-size: var(--t-xs); color: var(--c-success-fg); margin-top: var(--s-xs); }
.issue__ops { display: flex; flex-direction: column; gap: var(--s-xs); flex-shrink: 0; }
.empty { text-align: center; color: var(--c-text-3); font-size: var(--t-sm); padding: var(--s-xl) 0; }

.form-row { display: flex; flex-direction: column; gap: var(--s-xs); margin-bottom: var(--s-md); }
.form-row label { font-size: var(--t-sm); font-weight: 600; }
.form-row textarea { border: 1px solid var(--c-border); border-radius: var(--r-md); padding: var(--s-sm); font-size: var(--t-sm); resize: vertical; font-family: inherit; }
.form-row textarea:focus { outline: none; border-color: var(--c-brand); }

@media (max-width: 900px) {
  .hl__body { grid-template-columns: 1fr; }
}
</style>
