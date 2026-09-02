<script setup lang="ts">
/* ============================================================
 * 预算管控 /m6-budget（M6-16）
 * 4 KPI（年度预算/已执行/剩余/整体执行率）+ 预算编制表 + 超支预警 + 配置预算弹层
 * 实际发生额只读镜像自 financeCore；超额仅拦截提示，不碰资金池。
 * ============================================================ */
import { computed, reactive, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CProgressBar from '@/components/CProgressBar.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import { useFinBudgetStore, type BudgetSubjectId } from '@/stores/finBudget'

const store = useFinBudgetStore()

function money(n: number) {
  return `¥${n.toLocaleString('zh-CN')}`
}

const kpis = computed(() => [
  { label: '年度预算总额', icon: 'finance', value: money(store.totalBudget), tone: 'brand' as const },
  { label: '已执行', icon: 'finance', value: money(store.totalActual), tone: 'text' as const },
  {
    label: '剩余预算', icon: 'finance',
    value: money(store.remaining),
    tone: (store.remaining < 0 ? 'danger' : 'teal') as 'danger' | 'teal',
  },
  {
    label: '整体执行率', icon: 'trend-up',
    value: `${store.totalRate}%`,
    tone: (store.totalTone === 'danger'
      ? 'danger'
      : store.totalTone === 'warning'
        ? 'warning'
        : 'success') as 'danger' | 'warning' | 'success',
  },
])

function barColor(tone: string) {
  if (tone === 'danger') return 'var(--c-danger-fg)'
  if (tone === 'warning') return 'var(--c-warning-fg)'
  return 'var(--c-success-fg)'
}

// ---------- 配置预算弹层（编辑 + 二次确认） ----------
const showConfig = ref(false)
const showConfirm = ref(false)
const draft = reactive<Record<BudgetSubjectId, number>>({} as Record<BudgetSubjectId, number>)

function openConfig() {
  for (const s of store.subjects) draft[s.id] = s.budget
  showConfig.value = true
}
function closeConfig() {
  showConfig.value = false
  showConfirm.value = false
}
function requestConfirm() {
  showConfirm.value = true
}
function confirmSave() {
  store.saveBudgets({ ...draft })
  closeConfig()
}

const draftTotal = computed(() =>
  store.subjects.filter((s) => s.rollup).reduce((sum, s) => sum + (Number(draft[s.id]) || 0), 0))
</script>

<template>
  <div class="bg">
    <div class="bg__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <!-- 超支预警 -->
    <CCard v-if="store.overBudget.length > 0" class="bg__alert" padding="lg">
      <div class="alert">
        <div class="alert__icon"><CIcon name="alert" :size="20" /></div>
        <div class="alert__body">
          <div class="alert__title">超支预警：{{ store.overBudget.length }} 个科目已超出预算</div>
          <div class="alert__chips">
            <span v-for="r in store.overBudget" :key="r.id" class="chip">
              {{ r.name }} 执行 {{ r.rate }}%（超 {{ money(r.variance) }}）
            </span>
          </div>
          <div class="alert__hint">已触发预算拦截：该科目后续支出需预算调整或追加审批，系统不自动动账。</div>
        </div>
      </div>
    </CCard>

    <!-- 预算编制表 -->
    <CCard class="bg__table" padding="none">
      <template #header>
        <h3 class="card-title"><CIcon name="sign" :size="16" /> 预算编制与执行</h3>
        <div class="bg__head-actions">
          <CStatusPill v-if="store.warningCount > 0" status="warning" dot>
            {{ store.warningCount }} 个科目接近预算
          </CStatusPill>
          <CButton variant="primary" size="sm" v-perm.disable="'finance:budget:edit'" @click="openConfig">
            <CIcon name="settings" :size="14" />配置预算
          </CButton>
        </div>
      </template>
      <div class="table-scroll">
        <table class="btable">
          <thead>
            <tr>
              <th class="btable__subject">科目</th>
              <th class="btable__num">本期预算</th>
              <th class="btable__num">实际发生</th>
              <th class="btable__bar">执行率</th>
              <th class="btable__num">偏差</th>
              <th class="btable__status">状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in store.rows" :key="r.id" :class="{ 'is-over': r.tone === 'danger' }">
              <td class="btable__subject">
                <span class="subj">
                  <span class="subj__kind" :class="`subj__kind--${r.kind.toLowerCase()}`">{{ r.kind }}</span>
                  <span class="subj__name">{{ r.name }}</span>
                  <span v-if="!r.rollup" class="subj__tag">明细</span>
                </span>
              </td>
              <td class="btable__num">{{ money(r.budget) }}</td>
              <td class="btable__num">{{ money(r.actual) }}</td>
              <td class="btable__bar">
                <CProgressBar
                  :value="Math.min(r.rate, 100)"
                  :color="barColor(r.tone)"
                  :height="8"
                  :label="`${r.rate}%`"
                />
              </td>
              <td class="btable__num" :class="r.variance > 0 ? 'num--danger' : 'num--ok'">
                {{ r.variance > 0 ? '+' : '' }}{{ money(r.variance) }}
              </td>
              <td class="btable__status">
                <CStatusPill :status="store.EXEC_PILL[r.tone]" dot>{{ store.EXEC_LABEL[r.tone] }}</CStatusPill>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </CCard>

    <!-- 配置预算弹层 -->
    <div v-if="showConfig" class="modal-mask" @click.self="closeConfig">
      <CCard class="modal" title="配置年度预算" padding="lg">
        <div class="cfg">
          <div class="cfg__row cfg__row--head">
            <span>科目</span>
            <span>预算金额（元）</span>
          </div>
          <div v-for="s in store.subjects" :key="s.id" class="cfg__row">
            <span class="cfg__label">
              <span class="subj__kind" :class="`subj__kind--${s.kind.toLowerCase()}`">{{ s.kind }}</span>
              {{ s.name }}
              <span v-if="!s.rollup" class="subj__tag">明细</span>
            </span>
            <CInput
              type="number"
              :model-value="String(draft[s.id] ?? 0)"
              @update:model-value="draft[s.id] = Number($event) || 0"
            />
          </div>
          <div class="cfg__total">
            <span>汇总预算（计入 KPI）</span>
            <strong>{{ money(draftTotal) }}</strong>
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="closeConfig">取消</CButton>
          <CButton variant="primary" @click="requestConfirm">保存预算</CButton>
        </template>
      </CCard>
    </div>

    <!-- 二次确认 -->
    <div v-if="showConfirm" class="modal-mask" @click.self="showConfirm = false">
      <CCard class="modal modal--sm" title="确认保存预算" padding="lg">
        <div class="confirm">
          <div class="confirm__icon"><CIcon name="alert" :size="28" /></div>
          <p class="confirm__text">
            预算调整将作用于全 M6 数据财务页的执行率与超支预警，且会影响门店后续支出的预算拦截。
          </p>
          <p class="confirm__meta">本次汇总预算：<strong>{{ money(draftTotal) }}</strong></p>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showConfirm = false">再看看</CButton>
          <CButton variant="primary" @click="confirmSave">确认保存</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.bg { display: flex; flex-direction: column; gap: var(--s-lg); }
.bg__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .bg__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }

.card-title { font-size: var(--t-md); font-weight: 700; margin: 0; display: flex; align-items: center; gap: var(--s-xs); }
.bg__head-actions { display: flex; align-items: center; gap: var(--s-sm); flex-shrink: 0; }

.alert { display: flex; gap: var(--s-md); }
.alert__icon {
  width: 40px; height: 40px; border-radius: var(--r-md); flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  background: var(--c-danger-bg); color: var(--c-danger-fg);
}
.alert__body { display: flex; flex-direction: column; gap: var(--s-xs); }
.alert__title { font-size: var(--t-sm); font-weight: 700; color: var(--c-danger-fg); }
.alert__chips { display: flex; flex-wrap: wrap; gap: var(--s-xs); }
.chip {
  font-size: var(--t-xs); color: var(--c-danger-fg); background: var(--c-danger-bg);
  padding: 2px var(--s-sm); border-radius: var(--r-pill);
}
.alert__hint { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }

.table-scroll { overflow-x: auto; }
.btable { width: 100%; border-collapse: collapse; font-size: var(--t-sm); }
.btable th {
  text-align: left; font-weight: 600; color: var(--c-text-3); font-size: var(--t-xs);
  padding: var(--s-sm) var(--s-lg); border-bottom: 1px solid var(--c-border); white-space: nowrap;
}
.btable td { padding: var(--s-md) var(--s-lg); border-bottom: 1px solid var(--c-border-light); vertical-align: middle; }
.btable tbody tr:last-child td { border-bottom: none; }
.btable tbody tr.is-over { background: var(--c-danger-bg); }
.btable__num { text-align: right; font-variant-numeric: tabular-nums; white-space: nowrap; }
.btable__bar { min-width: 220px; }
.btable__status { text-align: right; white-space: nowrap; }

.subj { display: inline-flex; align-items: center; gap: var(--s-xs); }
.subj__kind {
  font-size: 10px; font-weight: 700; line-height: 1;
  padding: 2px 6px; border-radius: var(--r-sm); color: #fff;
}
.subj__kind--rf { background: var(--c-brand); }
.subj__kind--tk { background: var(--c-orange-dark); }
.subj__kind--exp { background: var(--c-teal-dark); }
.subj__name { color: var(--c-text); }
.subj__tag { font-size: 10px; color: var(--c-text-4); background: var(--c-disabled-bg); padding: 1px 6px; border-radius: var(--r-sm); }
.num--danger { color: var(--c-danger-fg); font-weight: 600; }
.num--ok { color: var(--c-success-fg); }

.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 520px; max-width: 100%; box-shadow: var(--shadow-pop); }
.modal--sm { width: 380px; }
.cfg { display: flex; flex-direction: column; gap: var(--s-sm); }
.cfg__row { display: grid; grid-template-columns: 1fr 160px; align-items: center; gap: var(--s-md); }
.cfg__row--head { font-size: var(--t-xs); color: var(--c-text-3); font-weight: 600; }
.cfg__label { display: inline-flex; align-items: center; gap: var(--s-xs); font-size: var(--t-sm); color: var(--c-text); }
.cfg__total { display: flex; justify-content: space-between; align-items: center; padding-top: var(--s-sm); border-top: 1px solid var(--c-border-light); font-size: var(--t-sm); color: var(--c-text-2); }
.cfg__total strong { font-size: var(--t-md); color: var(--c-brand); font-variant-numeric: tabular-nums; }

.confirm { display: flex; flex-direction: column; align-items: center; gap: var(--s-md); text-align: center; padding: var(--s-sm) 0; }
.confirm__icon { width: 52px; height: 52px; border-radius: 50%; background: var(--c-warning-bg); color: var(--c-warning-fg); display: flex; align-items: center; justify-content: center; }
.confirm__text { font-size: var(--t-sm); color: var(--c-text-2); margin: 0; line-height: 1.6; }
.confirm__meta { font-size: var(--t-sm); color: var(--c-text-3); margin: 0; }
.confirm__meta strong { color: var(--c-brand); font-variant-numeric: tabular-nums; }

@media (max-width: 1024px) {
  .bg__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .btable__bar { min-width: 140px; }
  .cfg__row { grid-template-columns: 1fr; gap: var(--s-xs); }
}
</style>
