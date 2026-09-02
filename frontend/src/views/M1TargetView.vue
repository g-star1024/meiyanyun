<template>
  <div class="tg">
    <div class="tg__kpis">
      <CKpi :value="tg.overallProgress + '%'" label="集团加权达成" tone="brand" icon="trend-up" />
      <CKpi :value="String(tg.pendingApprovals.length)" label="待审批目标" tone="warning" icon="check-square" />
      <CKpi :value="String(groupTargets.length)" label="集团级指标" icon="trend-up" />
      <CKpi :value="String(achievedCount)" label="已达成指标" tone="success" icon="trend-up" />
    </div>

    <div class="tg__body">
      <CCard title="目标分解（集团 → 区域 → 门店）" padding="none" class="tg__tree">
        <div class="tree">
          <div v-for="g in tg.groupLines" :key="g.id" class="tree-node tree-node--group">
            <div class="trow" :class="{ 'is-active': selId === g.id }" @click="selId = g.id">
              <span class="trow__name"><CIcon name="org" :size="14" /> {{ g.ownerName }}</span>
              <span class="trow__metric">{{ METRIC_LABEL[g.metric] }} · {{ g.periodLabel }}</span>
              <CProgressBar :value="tg.progress(g)" :show-label="false" :color="progressColor(tg.progress(g))" :height="6" class="trow__bar" />
              <span class="trow__pct" :class="'pct--' + statusOf(tg.progress(g))">{{ tg.progress(g) }}%</span>
              <CStatusPill :status="approvalTone(g.approval)">{{ APPROVAL_LABEL[g.approval] }}</CStatusPill>
            </div>
            <div class="tree-children">
              <div v-for="rid in g.children || []" :key="rid" class="tree-node tree-node--region">
                <div class="trow trow--child" :class="{ 'is-active': selId === rid }" @click="selId = rid">
                  <span class="trow__name">{{ line(rid)?.ownerName }}</span>
                  <span class="trow__metric">{{ line(rid) ? METRIC_LABEL[line(rid)!.metric] : '' }}</span>
                  <CProgressBar v-if="line(rid)" :value="tg.progress(line(rid)!)" :show-label="false" :color="progressColor(tg.progress(line(rid)!))" :height="5" class="trow__bar" />
                  <span class="trow__pct">{{ line(rid) ? tg.progress(line(rid)!) : 0 }}%</span>
                </div>
                <div class="tree-children">
                  <div v-for="sid in line(rid)?.children || []" :key="sid" class="tree-node tree-node--store">
                    <div class="trow trow--child trow--store" :class="{ 'is-active': selId === sid }" @click="selId = sid">
                      <span class="trow__name">{{ line(sid)?.ownerName }}</span>
                      <CProgressBar v-if="line(sid)" :value="tg.progress(line(sid)!)" :show-label="false" :color="progressColor(tg.progress(line(sid)!))" :height="5" class="trow__bar" />
                      <span class="trow__pct">{{ line(sid) ? tg.progress(line(sid)!) : 0 }}%</span>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>
          <!-- 非营收的独立集团指标 -->
          <div v-for="g in standaloneMetrics" :key="g.id" class="tree-node tree-node--group">
            <div class="trow" :class="{ 'is-active': selId === g.id }" @click="selId = g.id">
              <span class="trow__name"><CIcon name="dashboard" :size="14" /> {{ g.ownerName }} · {{ METRIC_LABEL[g.metric] }}</span>
              <span class="trow__metric">{{ g.periodLabel }}</span>
              <CProgressBar :value="tg.progress(g)" :show-label="false" :color="progressColor(tg.progress(g))" :height="6" class="trow__bar" />
              <span class="trow__pct" :class="'pct--' + statusOf(tg.progress(g))">{{ tg.progress(g) }}%</span>
              <CStatusPill :status="approvalTone(g.approval)">{{ APPROVAL_LABEL[g.approval] }}</CStatusPill>
            </div>
          </div>
        </div>
      </CCard>

      <CCard class="tg__detail" padding="lg">
        <template v-if="sel">
          <div class="detail__head">
            <div>
              <h3>{{ sel.ownerName }} · {{ METRIC_LABEL[sel.metric] }}</h3>
              <div class="detail__sub">
                <span>{{ PERIOD_LABEL[sel.period] }} · {{ sel.periodLabel }}</span>
                <span>{{ ownerTypeLabel(sel.ownerType) }}</span>
                <span>权重 {{ sel.weight }}%</span>
                <CStatusPill :status="approvalTone(sel.approval)">{{ APPROVAL_LABEL[sel.approval] }}</CStatusPill>
              </div>
            </div>
          </div>

          <div class="nums">
            <div class="num"><div class="num__v">{{ sel.targetValue.toLocaleString() }}</div><div class="num__l">目标值 ({{ sel.unit }})</div></div>
            <div class="num"><div class="num__v">{{ sel.currentValue.toLocaleString() }}</div><div class="num__l">当前值 ({{ sel.unit }})</div></div>
            <div class="num" :class="'num--' + statusOf(tg.progress(sel))">
              <div class="num__v">{{ (sel.targetValue - sel.currentValue).toLocaleString() }}</div>
              <div class="num__l">缺口 ({{ sel.unit }})</div>
            </div>
            <div class="num" :class="'num--' + statusOf(tg.progress(sel))">
              <div class="num__v">{{ tg.progress(sel) }}%</div><div class="num__l">达成率</div>
            </div>
          </div>

          <div class="prog-wrap">
            <CProgressBar :value="tg.progress(sel)" :show-label="false" :color="progressColor(tg.progress(sel))" :height="12" />
            <div class="prog-marks">
              <span>0</span><span>60% 落后线</span><span>85% 风险线</span><span>100%</span>
            </div>
          </div>

          <!-- 更新进度（有编辑权限） -->
          <div v-if="canEdit && sel.approval === 'APPROVED'" class="update">
            <label>更新当前值</label>
            <div class="update__row">
              <input type="number" v-model.number="editVal" />
              <span class="unit">{{ sel.unit }}</span>
              <CButton size="sm" variant="primary" @click="applyVal">更新</CButton>
            </div>
          </div>

          <!-- 审批操作 -->
          <div v-if="sel.approval === 'PENDING' && canApprove" class="ops">
            <CButton variant="primary" @click="tg.approve(sel.id)"><CIcon name="check" :size="14" /> 批准</CButton>
            <CButton variant="danger" @click="tg.reject(sel.id)">驳回</CButton>
          </div>
          <div v-else-if="sel.approval === 'DRAFT' && canApprove" class="ops">
            <CButton variant="primary" @click="tg.submit(sel.id)">提交审批</CButton>
          </div>

          <div class="status-hint">
            状态：<b :class="'pct--' + statusOf(tg.progress(sel))">{{ STATUS_LABEL[statusOf(tg.progress(sel))] }}</b>
            <span v-if="tg.progress(sel) >= 100">— 已超额完成目标</span>
            <span v-else-if="tg.progress(sel) >= 85">— 进度正常，保持节奏</span>
            <span v-else-if="tg.progress(sel) >= 60">— 存在风险，需关注</span>
            <span v-else>— 进度落后，需立即干预</span>
          </div>
        </template>
      </CCard>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CProgressBar from '@/components/CProgressBar.vue'
import CKpi from '@/components/CKpi.vue'
import { useM1TargetStore, METRIC_LABEL, PERIOD_LABEL, APPROVAL_LABEL, STATUS_LABEL, statusOf,
  type TargetLine, type ApprovalStatus } from '@/stores/m1Target'
import { useAuthStore } from '@/stores/auth'

const tg = useM1TargetStore()
const auth = useAuthStore()
onMounted(() => tg.seed())

const canEdit = computed(() => auth.can('target:edit') || auth.isSuper)
const canApprove = computed(() => auth.can('target:approve') || auth.isSuper)

const selId = ref('G1')
const sel = computed(() => tg.lines.find((l) => l.id === selId.value))
const editVal = ref(0)
watch(sel, (l) => { if (l) editVal.value = l.currentValue }, { immediate: true })

function line(id: string) { return tg.lines.find((l) => l.id === id) }
const groupTargets = computed(() => tg.groupLines)
const standaloneMetrics = computed(() => tg.groupLines.filter((g) => g.metric !== 'REVENUE' || g.ownerType !== 'GROUP' || !g.children))
const achievedCount = computed(() => tg.lines.filter((l) => tg.progress(l) >= 100 && l.approval === 'APPROVED').length)

function applyVal() { if (sel.value) tg.updateProgress(sel.value.id, editVal.value) }

function progressColor(p: number): string {
  if (p >= 100) return 'var(--c-success-fg)'
  if (p >= 85) return 'var(--c-brand)'
  if (p >= 60) return 'var(--c-warning-fg)'
  return 'var(--c-danger-fg)'
}
function approvalTone(a: ApprovalStatus): 'success' | 'warning' | 'draft' | 'danger' {
  return a === 'APPROVED' ? 'success' : a === 'PENDING' ? 'warning' : a === 'REJECTED' ? 'danger' : 'draft'
}
function ownerTypeLabel(t: TargetLine['ownerType']) { return t === 'GROUP' ? '集团' : t === 'REGION' ? '区域' : '门店' }
void statusOf
</script>

<style scoped>
.tg { display: flex; flex-direction: column; gap: var(--s-lg); }
.tg__kpis { display: flex; gap: var(--s-md); flex-wrap: wrap; }
.tg__kpis :deep(.ckpi) { flex: 1 1 0; min-width: 168px; padding: var(--s-lg); }
.tg__body { display: grid; grid-template-columns: 1fr 420px; gap: var(--s-lg); align-items: start; }
.tree { padding: var(--s-xs) 0; }
.tree-node--group > .trow { font-weight: 600; }
.trow { display: grid; grid-template-columns: 1.4fr 1.2fr 1fr 56px 72px; align-items: center; gap: var(--s-md); padding: var(--s-md) var(--s-lg); cursor: pointer; border-bottom: 1px solid var(--c-border); font-size: var(--t-sm); transition: background .15s; }
.trow:last-child { border-bottom: none; }
.trow:hover { background: var(--c-surface-muted); }
.trow.is-active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.trow--child { padding-left: var(--s-xl); font-weight: 400; background: var(--c-surface-muted); }
.trow--store { padding-left: calc(var(--s-xl) + var(--s-lg)); }
.trow__name { display: inline-flex; align-items: center; gap: var(--s-xs); }
.trow__metric { font-size: var(--t-xs); color: var(--c-text-3); }
.trow__bar { min-width: 0; }
.trow__pct { text-align: right; font-weight: 700; font-size: var(--t-sm); font-variant-numeric: tabular-nums; }
.pct--ACHIEVED { color: var(--c-success-fg); }
.pct--ON_TRACK { color: var(--c-brand); }
.pct--AT_RISK { color: var(--c-warning-fg); }
.pct--BEHIND { color: var(--c-danger-fg); }
.detail__head { margin-bottom: var(--s-lg); }
.detail__head h3 { margin: 0 0 var(--s-xs); font-size: var(--t-lg); font-weight: 700; }
.detail__sub { display: flex; flex-wrap: wrap; gap: var(--s-md); font-size: var(--t-xs); color: var(--c-text-3); align-items: center; }
.nums { display: grid; grid-template-columns: repeat(2, 1fr); gap: var(--s-md); margin: var(--s-lg) 0; }
.num { background: var(--c-surface-muted); border-radius: var(--r-md); padding: var(--s-md); }
.num__v { font-size: var(--t-xl); font-weight: 700; font-variant-numeric: tabular-nums; line-height: 1.2; }
.num__l { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }
.num--ACHIEVED .num__v { color: var(--c-success-fg); }
.num--AT_RISK .num__v { color: var(--c-warning-fg); }
.num--BEHIND .num__v { color: var(--c-danger-fg); }
.prog-wrap { margin-bottom: var(--s-lg); }
.prog-marks { display: flex; justify-content: space-between; font-size: 10px; color: var(--c-text-3); margin-top: var(--s-xs); }
.update { background: var(--c-surface-muted); border-radius: var(--r-md); padding: var(--s-md); margin-bottom: var(--s-md); }
.update label { font-size: var(--t-sm); font-weight: 600; display: block; margin-bottom: var(--s-xs); }
.update__row { display: flex; gap: var(--s-xs); align-items: center; }
.update__row input { flex: 1; border: 1px solid var(--c-border); border-radius: var(--r-md); padding: var(--s-xs) var(--s-sm); font-size: var(--t-sm); }
.update__row input:focus { outline: none; border-color: var(--c-brand); }
.unit { font-size: var(--t-xs); color: var(--c-text-3); }
.ops { display: flex; gap: var(--s-xs); margin-bottom: var(--s-md); }
.status-hint { font-size: var(--t-sm); color: var(--c-text-2); padding-top: var(--s-md); border-top: 1px solid var(--c-border); }
@media (max-width: 900px) {
  .tg__body { grid-template-columns: 1fr; }
  .trow { grid-template-columns: 1fr 1fr; gap: var(--s-xs); }
  .trow__bar, .trow__metric { display: none; }
}
</style>
