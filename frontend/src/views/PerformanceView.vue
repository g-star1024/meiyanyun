<script setup lang="ts">
/* ============================================================
 * 员工绩效看板 /m2-performance（M2-07）
 * 4 KPI（门店总业绩/目标达成率/Top1 业绩/在岗人数）
 * 左：员工业绩排行榜；右：个人详情含目标进度、提成试算
 * ============================================================ */
import { computed, onMounted, ref, watch } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CProgressBar from '@/components/CProgressBar.vue'
import { usePerformanceStore } from '@/stores/performance'

const store = usePerformanceStore()
onMounted(() => store.seed())

const selectedId = ref<string | null>(null)
const selected = computed(() => {
  if (selectedId.value) return store.get(selectedId.value) ?? null
  return store.filtered[0] ?? null
})

const kpis = computed(() => [
  { label: '门店总业绩', icon: 'store', value: `¥${(store.totalActual / 10000).toFixed(1)}万`, tone: 'brand' as const },
  { label: '目标达成率', icon: 'trend-up', value: `${store.achievement}%`, tone: store.achievement >= 100 ? ('success' as const) : ('warning' as const) },
  { label: 'Top1 业绩', icon: 'finance', value: store.topStaff ? `¥${(store.topStaff.actual / 10000).toFixed(1)}万` : '—', tone: 'orange' as const },
  { label: '在岗人数', icon: 'customer', value: String(store.onDuty.length), tone: 'text' as const },
])

const roleOptions = [
  { value: 'ALL', label: '全部岗位' },
  { value: 'CONSULTANT', label: '咨询师' },
  { value: 'DOCTOR', label: '医生' },
  { value: 'BEAUTICIAN', label: '美容师' },
]
const periodOptions = [
  { value: 'THIS_MONTH', label: '本月' },
  { value: 'LAST_MONTH', label: '上月' },
]

function money(n: number) {
  return `¥${n.toLocaleString('zh-CN')}`
}

// 目标调整
const showTarget = ref(false)
const targetInput = ref(0)
function openTarget() {
  if (!selected.value) return
  targetInput.value = selected.value.target
  showTarget.value = true
}
function saveTarget() {
  if (!selected.value) return
  store.updateTarget(selected.value.id, Number(targetInput.value) || 0)
  showTarget.value = false
}

// 提成试算
const simulateAmount = ref(0)
const simulateResult = computed(() => {
  if (!selected.value) return null
  return store.simulateCommission(selected.value.id, Number(simulateAmount.value) || 0)
})
function resetSimulate() {
  if (!selected.value) return
  simulateAmount.value = selected.value.actual
}
onMounted(() => {
  // 初始化试算金额
  setTimeout(() => {
    if (selected.value) simulateAmount.value = selected.value.actual
  }, 0)
})
watch(selected, (s) => { if (s) simulateAmount.value = s.actual })
</script>

<template>
  <div class="pf">
    <div class="pf__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <div class="pf__body">
      <!-- 左：员工排行榜 -->
      <CCard class="pf__list" padding="none">
        <div class="filters">
          <CSelect v-model="store.filterRole" :options="roleOptions" />
          <CSelect v-model="store.period" width="110px" :options="periodOptions" />
        </div>
        <div class="list">
          <div v-if="store.filtered.length === 0" class="empty">
            <CIcon name="profile" :size="28" class="empty__icon" />
            <div>暂无员工数据</div>
          </div>
          <button
            v-for="(s, i) in store.filtered" :key="s.id"
            class="row" :class="{ 'row--active': selected?.id === s.id, 'row--top': i === 0 }"
            @click="selectedId = s.id"
          >
            <div class="row__rank" :class="`row__rank--${i + 1}`">{{ i + 1 }}</div>
            <div class="row__avatar">{{ s.avatarLetter }}</div>
            <div class="row__main">
              <div class="row__top">
                <span class="row__name">{{ s.name }}</span>
                <CStatusPill :status="store.STATUS_PILL[s.status]" dot>{{ store.STATUS_LABEL[s.status] }}</CStatusPill>
              </div>
              <div class="row__sub">{{ s.title }} · {{ store.ROLE_LABEL[s.role] }}</div>
              <div class="row__bar">
                <CProgressBar :value="s.actual" :max="Math.max(s.target, s.actual)" :height="6" :show-label="false" />
              </div>
            </div>
            <div class="row__right">
              <div class="row__amount">{{ money(s.actual) }}</div>
              <div class="row__pct" :class="{ 'is-over': store.completion(s) >= 100 }">
                {{ store.completion(s) }}%
              </div>
            </div>
          </button>
        </div>
      </CCard>

      <!-- 右：个人详情 -->
      <CCard v-if="selected" class="pf__detail" padding="none">
        <template #header>
          <div class="pf__detail-head">
            <div class="pf__avatar">{{ selected.avatarLetter }}</div>
            <div class="pf__who">
              <h3 class="pf__name">{{ selected.name }}</h3>
              <div class="pf__sub">{{ selected.title }} · {{ store.ROLE_LABEL[selected.role] }} · 入职 {{ selected.joinedAt }}</div>
            </div>
          </div>
          <div class="pf__detail-head-right">
            <CStatusPill :status="store.STATUS_PILL[selected.status]" dot>{{ store.STATUS_LABEL[selected.status] }}</CStatusPill>
            <CButton variant="secondary" size="sm" v-perm.disable="'performance:edit'">
              <CIcon name="export" :size="14" />导出绩效
            </CButton>
          </div>
        </template>

        <div class="detail-body">
          <!-- 核心指标 -->
          <div class="stat-grid">
            <div class="stat">
              <div class="stat__label">本月业绩</div>
              <div class="stat__value stat__value--brand">{{ money(selected.actual) }}</div>
            </div>
            <div class="stat">
              <div class="stat__label">业绩目标</div>
              <div class="stat__value">{{ money(selected.target) }}</div>
            </div>
            <div class="stat">
              <div class="stat__label">成交单数</div>
              <div class="stat__value">{{ selected.orders }} 单</div>
            </div>
            <div class="stat">
              <div class="stat__label">提成比例</div>
              <div class="stat__value">{{ (selected.commissionRate * 100).toFixed(0) }}%</div>
            </div>
          </div>

          <!-- 目标进度 -->
          <div class="block">
            <div class="block__title">
              <span>目标完成进度</span>
              <CButton variant="text" size="sm" v-perm.disable="'performance:edit'" @click="openTarget">
                <CIcon name="edit" :size="14" />调整目标
              </CButton>
            </div>
            <div class="progress-wrap">
              <CProgressBar
                :value="selected.actual" :max="Math.max(selected.target, selected.actual)"
                :color="store.completion(selected) >= 100 ? 'var(--c-success-fg)' : 'var(--c-brand)'"
                :height="12" :label="`${store.completion(selected)}%`"
              />
              <div class="progress-meta">
                <span>已完成 {{ money(selected.actual) }}</span>
                <span v-if="selected.actual < selected.target" class="progress-meta__remain">
                  距目标还差 {{ money(selected.target - selected.actual) }}
                </span>
                <span v-else class="progress-meta__done">已超额完成 {{ money(selected.actual - selected.target) }}</span>
              </div>
            </div>
          </div>

          <!-- 提成试算 -->
          <div class="block">
            <div class="block__title">
              <span>提成试算</span>
              <span class="block__hint">当前档位：{{ store.tierFor(selected.actual).label }}</span>
            </div>
            <div class="commission">
              <div class="commission__result">
                <div class="commission__num">{{ money(store.commission(selected)) }}</div>
                <div class="commission__label">按当前业绩（{{ (selected.commissionRate * 100).toFixed(0) }}%）</div>
              </div>
              <div class="commission__sim">
                <label class="sim__label">模拟业绩金额</label>
                <div class="sim__row">
                  <CInput :model-value="String(simulateAmount)" @update:model-value="simulateAmount = Number($event) || 0" placeholder="输入金额" />
                  <CButton variant="ghost" size="sm" @click="resetSimulate">当前值</CButton>
                </div>
                <div v-if="simulateResult" class="sim__out">
                  <div>
                    <span class="sim__out-label">{{ simulateResult.label }}</span>
                    <span class="sim__out-value">{{ money(simulateResult.commission) }}</span>
                  </div>
                  <div v-if="simulateResult.delta !== 0" class="sim__delta" :class="{ 'is-up': simulateResult.delta > 0 }">
                    <CIcon :name="simulateResult.delta > 0 ? 'trend-up' : 'trend-down'" :size="12" />
                    较当前 {{ simulateResult.delta > 0 ? '+' : '' }}{{ money(simulateResult.delta) }}
                  </div>
                </div>
              </div>
            </div>
          </div>

          <!-- 近 6 个月业绩迷你走势 -->
          <div class="block">
            <div class="block__title"><span>近 6 个月业绩走势</span></div>
            <div class="trend">
              <div v-for="(v, i) in selected.trend" :key="i" class="trend__col">
                <div class="trend__bar-wrap">
                  <div class="trend__bar" :style="{ height: (v / Math.max(...selected.trend, 1) * 100) + '%' }" />
                </div>
                <div class="trend__month">{{ ['3月','4月','5月','6月','7月','8月'][i] }}</div>
              </div>
            </div>
          </div>
        </div>
      </CCard>

      <CCard v-else class="pf__detail pf__detail--empty" title="员工详情" padding="lg">
        <div class="detail-empty">
          <CIcon name="profile" :size="40" class="detail-empty__icon" />
          <p>请选择一位员工</p>
        </div>
      </CCard>
    </div>

    <!-- 调整目标弹层 -->
    <div v-if="showTarget" class="modal-mask" @click.self="showTarget = false">
      <CCard class="modal modal--sm" title="调整业绩目标" padding="lg">
        <label class="form__label">新目标金额（元）</label>
        <CInput :model-value="String(targetInput)"
          @update:model-value="targetInput = Number($event) || 0" placeholder="如 200000" />
        <template #footer>
          <CButton variant="ghost" @click="showTarget = false">取消</CButton>
          <CButton variant="primary" @click="saveTarget">保存</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.pf { display: flex; flex-direction: column; gap: var(--s-lg); }
.pf__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .pf__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }

.pf__body { display: grid; grid-template-columns: 380px 1fr; gap: var(--s-lg); align-items: start; }
.pf__list { min-width: 0; }
.filters { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-md); border-bottom: 1px solid var(--c-border-light); flex-wrap: nowrap; overflow-x: auto; }
.filters > * { flex-shrink: 0; }
.filters__btn { margin-left: auto; flex-shrink: 0; position: sticky; right: 0; z-index: 2; box-shadow: -12px 0 10px -8px rgba(0,0,0,.12); white-space: nowrap; }
.list { max-height: 640px; overflow-y: auto; }
.empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); font-size: var(--t-sm); }
.empty__icon { color: var(--c-text-4); }

.row {
  display: flex; align-items: center; gap: var(--s-sm); width: 100%; text-align: left;
  padding: var(--s-md) var(--s-lg); background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer;
}
.row:hover { background: var(--c-brand-soft); }
.row--active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.row__rank {
  width: 22px; height: 22px; border-radius: 50%; display: flex; align-items: center; justify-content: center;
  font-size: var(--t-xs); font-weight: 700; background: var(--c-disabled-bg); color: var(--c-text-3); flex-shrink: 0;
}
.row__rank--1 { background: var(--c-warning-fg); color: #fff; }
.row__rank--2 { background: var(--c-text-3); color: #fff; }
.row__rank--3 { background: var(--c-orange-dark); color: #fff; }
.row__avatar {
  width: 36px; height: 36px; border-radius: 50%; background: var(--c-brand-soft); color: var(--c-brand);
  display: flex; align-items: center; justify-content: center; font-weight: 700; font-size: var(--t-sm); flex-shrink: 0;
}
.row__main { flex: 1; min-width: 0; }
.row__top { display: flex; align-items: center; gap: var(--s-xs); margin-bottom: 2px; }
.row__name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.row__sub { font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: var(--s-xs); }
.row__bar { width: 100%; }
.row__right { text-align: right; flex-shrink: 0; }
.row__amount { font-size: var(--t-sm); font-weight: 700; color: var(--c-text); font-variant-numeric: tabular-nums; }
.row__pct { font-size: var(--t-xs); color: var(--c-text-3); }
.row__pct.is-over { color: var(--c-success-fg); font-weight: 600; }

.pf__detail-head { display: flex; align-items: center; gap: var(--s-md); width: 100%; }
.pf__detail-head-right { display: flex; align-items: center; gap: var(--s-sm); flex-shrink: 0; }
.pf__detail-head-right :deep(.cbtn) { white-space: nowrap; }
.pf__avatar {
  width: 48px; height: 48px; border-radius: 50%; background: var(--c-brand-soft); color: var(--c-brand);
  display: flex; align-items: center; justify-content: center; font-size: var(--t-lg); font-weight: 700;
}
.pf__who { flex: 1; min-width: 0; }
.pf__name { font-size: var(--t-lg); font-weight: 700; margin: 0; }
.pf__sub { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }

.detail-body { padding: var(--s-lg); display: flex; flex-direction: column; gap: var(--s-lg); }

.stat-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); }
.stat { background: var(--c-bg-right); border-radius: var(--r-md); padding: var(--s-md); }
.stat__label { font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: 4px; }
.stat__value { font-size: var(--t-md); font-weight: 700; color: var(--c-text); font-variant-numeric: tabular-nums; }
.stat__value--brand { color: var(--c-brand); }

.block { display: flex; flex-direction: column; gap: var(--s-sm); }
.block__title {
  display: flex; justify-content: space-between; align-items: center;
  font-size: var(--t-sm); font-weight: 600; color: var(--c-text);
}
.block__hint { font-size: var(--t-xs); color: var(--c-brand); font-weight: 400; }

.progress-meta { display: flex; justify-content: space-between; font-size: var(--t-xs); color: var(--c-text-3); margin-top: var(--s-xs); }
.progress-meta__remain { color: var(--c-warning-fg); }
.progress-meta__done { color: var(--c-success-fg); font-weight: 600; }

.commission { display: grid; grid-template-columns: 200px 1fr; gap: var(--s-lg); align-items: center; background: var(--c-bg-right); border-radius: var(--r-md); padding: var(--s-md); }
.commission__result { text-align: center; }
.commission__num { font-size: var(--t-xl); font-weight: 700; color: var(--c-brand); font-variant-numeric: tabular-nums; }
.commission__label { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }
.commission__sim { display: flex; flex-direction: column; gap: var(--s-xs); }
.sim__label { font-size: var(--t-xs); color: var(--c-text-3); }
.sim__row { display: flex; gap: var(--s-xs); }
.sim__out { display: flex; justify-content: space-between; align-items: center; padding-top: var(--s-xs); border-top: 1px solid var(--c-border-light); }
.sim__out-label { font-size: var(--t-xs); color: var(--c-text-3); margin-right: var(--s-sm); }
.sim__out-value { font-size: var(--t-md); font-weight: 700; color: var(--c-text); }
.sim__delta { display: inline-flex; align-items: center; gap: 2px; font-size: var(--t-xs); color: var(--c-danger-fg); }
.sim__delta.is-up { color: var(--c-success-fg); }

.trend { display: flex; align-items: flex-end; gap: var(--s-sm); height: 120px; }
.trend__col { flex: 1; display: flex; flex-direction: column; align-items: center; height: 100%; }
.trend__bar-wrap { flex: 1; width: 100%; display: flex; align-items: flex-end; justify-content: center; }
.trend__bar { width: 60%; max-width: 24px; background: var(--c-brand); border-radius: var(--r-sm) var(--r-sm) 0 0; min-height: 4px; transition: height .3s ease; }
.trend__month { font-size: var(--t-xs); color: var(--c-text-3); margin-top: var(--s-xs); }

.detail-empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-md); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); }
.detail-empty__icon { color: var(--c-text-4); }

.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 380px; max-width: 100%; box-shadow: var(--shadow-pop); }
.modal--sm { width: 360px; }
.form__label { display: block; font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: var(--s-xs); }

@media (max-width: 1024px) {
  .pf__body { grid-template-columns: 1fr; }
  .pf__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .stat-grid { grid-template-columns: repeat(2, 1fr); }
  .commission { grid-template-columns: 1fr; gap: var(--s-md); }
  .list { max-height: 360px; }
}
</style>
