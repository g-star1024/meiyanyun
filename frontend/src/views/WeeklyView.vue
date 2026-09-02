<script setup lang="ts">
/* ============================================================
 * 经营周报 /m2-weekly（M2-20）
 * 4 KPI（本周营收/环比/客流/成交单数）
 * 左：周报列表；右：详情可编辑/提交，提交后锁定
 * ============================================================ */
import { computed, onMounted, ref, watch } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CTextarea from '@/components/CTextarea.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import { useWeeklyStore, type WeeklyReport } from '@/stores/weekly'
import { useToast } from '@/composables/useToast'

const store = useWeeklyStore()
const toast = useToast()
onMounted(() => {
  store.seed()
  if (store.current) selectedId.value = store.current.id
  syncForm()
})

const selectedId = ref<string | null>(null)
const selected = computed<WeeklyReport | null>(() => {
  if (selectedId.value) return store.get(selectedId.value) ?? null
  return store.current
})

const isDraft = computed(() => selected.value?.status === 'DRAFT')

const kpis = computed(() => [
  {
    label: '本周营收', icon: 'finance',
    value: store.latest ? `¥${(store.latest.revenue / 10000).toFixed(1)}万` : '—',
    tone: 'brand' as const,
    trend: store.latest ? (store.wowRevenue > 0 ? `+${store.wowRevenue}%` : `${store.wowRevenue}%`) : '',
    trendUp: store.wowRevenue >= 0,
    trendGood: store.wowRevenue >= 0,
  },
  { label: '环比上周', icon: 'trend-up', value: store.wowRevenue > 0 ? `+${store.wowRevenue}%` : `${store.wowRevenue}%`, tone: store.wowRevenue >= 0 ? ('success' as const) : ('warning' as const) },
  { label: '本周客流', icon: 'customer', value: store.latest ? String(store.latest.footfall) : '—', tone: 'teal' as const },
  { label: '成交单数', icon: 'order', value: store.latest ? String(store.latest.orders) : '—', tone: 'orange' as const },
])

const form = ref({
  revenue: 0, footfall: 0, orders: 0, newCustomers: 0, repurchaseRate: 0,
  highlights: '', issues: '', nextWeekPlan: '',
})
function syncForm() {
  if (!selected.value) return
  const r = selected.value
  form.value = {
    revenue: r.revenue, footfall: r.footfall, orders: r.orders,
    newCustomers: r.newCustomers, repurchaseRate: r.repurchaseRate,
    highlights: r.highlights, issues: r.issues, nextWeekPlan: r.nextWeekPlan,
  }
}
watch(selected, syncForm)

function applyForm() {
  if (!selected.value || !isDraft.value) return
  store.save(selected.value.id, { ...form.value })
}

const confirm = ref<{ show: boolean } | null>(null)
function askSubmit() {
  if (!isDraft.value) return
  applyForm()
  confirm.value = { show: true }
}
function doSubmit() {
  if (selected.value) store.submit(selected.value.id)
  confirm.value = null
}

function doCreateWeekly() {
  const ok = store.createWeekly()
  if (ok) {
    toast.success('已创建新一周经营周报')
    if (store.sorted.length > 0) {
      selectedId.value = store.sorted[0].id
    }
  }
}

function money(n: number) { return `¥${n.toLocaleString('zh-CN')}` }
function fmtDateTime(iso?: string) {
  if (!iso) return '—'
  return iso.replace('T', ' ').slice(0, 16)
}
</script>

<template>
  <div class="wk">
    <div class="wk__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone"
        :trend="k.trend" :trend-up="k.trendUp" :trend-good="k.trendGood" :icon="k.icon" />
    </div>

    <div class="wk__body">
      <!-- 左：周报列表 -->
      <CCard class="wk__list" padding="none">
        <template #header>
          <div class="wk__card-head">
            <span>周报列表</span>
            <div style="display: flex; gap: var(--s-xs);">
              <CButton variant="primary" size="sm" v-perm.disable="'weekly:submit'" @click="doCreateWeekly">
                <CIcon name="plus" :size="14" />新建周报
              </CButton>
              <CButton variant="secondary" size="sm" v-perm.disable="'weekly:submit'">
                <CIcon name="export" :size="14" />导出
              </CButton>
            </div>
          </div>
        </template>
        <div class="list">
          <div v-if="store.sorted.length === 0" class="empty">
            <CIcon name="calendar" :size="28" class="empty__icon" />
            <div>暂无周报</div>
          </div>
          <button
            v-for="r in store.sorted" :key="r.id"
            class="row" :class="{ 'row--active': selected?.id === r.id }"
            @click="selectedId = r.id"
          >
            <div class="row__top">
              <span class="row__no">{{ r.weekNo }}</span>
              <CStatusPill :status="r.status === 'DRAFT' ? 'draft' : 'success'" dot>
                {{ store.STATUS_LABEL[r.status] }}
              </CStatusPill>
            </div>
            <div class="row__range">
              <CIcon name="calendar" :size="12" /> {{ store.fmtRange(r) }}
            </div>
            <div class="row__rev">{{ money(r.revenue) }}</div>
            <div class="row__meta">{{ r.footfall }} 客流 · {{ r.orders }} 单</div>
          </button>
        </div>
      </CCard>

      <!-- 右：详情 -->
      <CCard v-if="selected" class="wk__detail" padding="none">
        <template #header>
          <div class="wk__detail-head">
            <div>
              <h3 class="wk__title">{{ selected.weekNo }} 经营周报</h3>
              <div class="wk__range">
                <CIcon name="calendar" :size="12" /> {{ store.fmtRange(selected) }}
              </div>
            </div>
            <CStatusPill :status="selected.status === 'DRAFT' ? 'draft' : 'success'" dot>
              {{ store.STATUS_LABEL[selected.status] }}
            </CStatusPill>
          </div>
        </template>

        <div class="detail-body">
          <!-- 核心数据 -->
          <div class="stat-grid">
            <div class="stat">
              <div class="stat__label">营业收入</div>
              <CInput v-if="isDraft" :model-value="String(form.revenue)"
                @update:model-value="form.revenue = Number($event) || 0" />
              <div v-else class="stat__value stat__value--brand">{{ money(selected.revenue) }}</div>
            </div>
            <div class="stat">
              <div class="stat__label">客流（人）</div>
              <CInput v-if="isDraft" :model-value="String(form.footfall)"
                @update:model-value="form.footfall = Number($event) || 0" />
              <div v-else class="stat__value">{{ selected.footfall }}</div>
            </div>
            <div class="stat">
              <div class="stat__label">成交单数</div>
              <CInput v-if="isDraft" :model-value="String(form.orders)"
                @update:model-value="form.orders = Number($event) || 0" />
              <div v-else class="stat__value">{{ selected.orders }}</div>
            </div>
            <div class="stat">
              <div class="stat__label">新客（人）</div>
              <CInput v-if="isDraft" :model-value="String(form.newCustomers)"
                @update:model-value="form.newCustomers = Number($event) || 0" />
              <div v-else class="stat__value">{{ selected.newCustomers }}</div>
            </div>
          </div>
          <div class="stat-grid stat-grid--3">
            <div class="stat">
              <div class="stat__label">复购率（%）</div>
              <CInput v-if="isDraft" :model-value="String(form.repurchaseRate)"
                @update:model-value="form.repurchaseRate = Number($event) || 0" />
              <div v-else class="stat__value">{{ selected.repurchaseRate }}%</div>
            </div>
            <div class="stat">
              <div class="stat__label">上周营收</div>
              <div class="stat__value">{{ money(selected.prevRevenue) }}</div>
            </div>
            <div class="stat">
              <div class="stat__label">环比</div>
              <div class="stat__value" :class="selected.revenue >= selected.prevRevenue ? 'is-up' : 'is-down'">
                {{ selected.revenue >= selected.prevRevenue ? '+' : '' }}{{ store.wowRevenue }}%
              </div>
            </div>
          </div>

          <!-- 文本复盘 -->
          <div class="block">
            <label class="field-label">本周亮点</label>
            <CTextarea v-if="isDraft" v-model="form.highlights" placeholder="记录本周业绩亮点、优秀项目、突出员工等" />
            <p v-else class="text-block">{{ selected.highlights }}</p>
          </div>
          <div class="block">
            <label class="field-label">问题与风险</label>
            <CTextarea v-if="isDraft" v-model="form.issues" placeholder="记录本周问题、风险、需协调事项等" />
            <p v-else class="text-block">{{ selected.issues }}</p>
          </div>
          <div class="block">
            <label class="field-label">下周工作计划</label>
            <CTextarea v-if="isDraft" v-model="form.nextWeekPlan" placeholder="列出下周重点工作、活动安排等" />
            <p v-else class="text-block">{{ selected.nextWeekPlan }}</p>
          </div>

          <!-- 操作区 -->
          <div v-if="isDraft" class="ops">
            <CButton variant="ghost" v-perm.disable="'weekly:submit'" @click="applyForm">
              <CIcon name="check" :size="16" />保存草稿
            </CButton>
            <CButton variant="primary" v-perm.disable="'weekly:submit'" @click="askSubmit">
              <CIcon name="check-square" :size="16" />提交周报
            </CButton>
          </div>

          <!-- 提交锁定提示 -->
          <div v-else class="done-bar">
            <CIcon name="check-square" :size="16" />
            <span>周报已提交至区域，内容已锁定留痕。提交人：{{ selected.submittedBy }} · {{ fmtDateTime(selected.submittedAt) }}</span>
          </div>
        </div>
      </CCard>

      <CCard v-else class="wk__detail wk__detail--empty" title="周报详情" padding="lg">
        <div class="detail-empty">
          <CIcon name="calendar" :size="40" class="detail-empty__icon" />
          <p>请选择一份周报</p>
        </div>
      </CCard>
    </div>

    <!-- 提交确认弹层 -->
    <div v-if="confirm?.show" class="modal-mask" @click.self="confirm = null">
      <CCard class="modal modal--sm" title="提交经营周报" padding="lg">
        <p class="confirm__text">确认提交 {{ selected?.weekNo }} 经营周报？提交后将锁定，仅可查看。</p>
        <template #footer>
          <CButton variant="ghost" @click="confirm = null">取消</CButton>
          <CButton variant="primary" @click="doSubmit">确认提交</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.wk { display: flex; flex-direction: column; gap: var(--s-lg); }
.wk__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .wk__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
.wk__card-head { display: flex; align-items: center; justify-content: space-between; width: 100%; gap: var(--s-sm); font-size: var(--t-md); font-weight: 700; flex-wrap: wrap; }
:deep(.ckpi) { min-width: 0; }

.wk__body { display: grid; grid-template-columns: 380px 1fr; gap: var(--s-lg); align-items: start; }
.wk__list { min-width: 0; }
.list { max-height: 640px; overflow-y: auto; }
.empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); font-size: var(--t-sm); }
.empty__icon { color: var(--c-text-4); }

.row {
  display: block; width: 100%; text-align: left; padding: var(--s-md) var(--s-lg);
  background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer;
}
.row:hover { background: var(--c-brand-soft); }
.row--active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.row__top { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--s-xs); }
.row__no { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.row__range { display: inline-flex; align-items: center; gap: 4px; font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: var(--s-xs); }
.row__rev { font-size: var(--t-base); font-weight: 700; color: var(--c-brand); font-variant-numeric: tabular-nums; }
.row__meta { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }

.wk__detail-head { display: flex; justify-content: space-between; align-items: flex-start; gap: var(--s-md); width: 100%; }
.wk__title { font-size: var(--t-lg); font-weight: 700; margin: 0; }
.wk__range { display: inline-flex; align-items: center; gap: 4px; font-size: var(--t-xs); color: var(--c-text-3); margin-top: 4px; }

.detail-body { padding: var(--s-lg); display: flex; flex-direction: column; gap: var(--s-md); }

.stat-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); }
.stat-grid--3 { grid-template-columns: repeat(3, 1fr); }
.stat { background: var(--c-bg-right); border-radius: var(--r-md); padding: var(--s-md); }
.stat__label { font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: 4px; }
.stat__value { font-size: var(--t-md); font-weight: 700; color: var(--c-text); font-variant-numeric: tabular-nums; }
.stat__value--brand { color: var(--c-brand); }
.stat__value.is-up { color: var(--c-success-fg); }
.stat__value.is-down { color: var(--c-warning-fg); }

.block { display: flex; flex-direction: column; gap: var(--s-xs); }
.field-label { font-size: var(--t-xs); color: var(--c-text-3); }
.text-block { font-size: var(--t-sm); color: var(--c-text-2); line-height: var(--lh-md); margin: 0; white-space: pre-wrap; }

.ops { display: flex; justify-content: flex-end; gap: var(--s-sm); margin-top: var(--s-sm); padding-top: var(--s-lg); border-top: 1px solid var(--c-border-light); }

.done-bar { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-md); background: var(--c-success-bg); color: var(--c-success-fg); border-radius: var(--r-md); font-size: var(--t-sm); margin-top: var(--s-sm); }

.detail-empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-md); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); }
.detail-empty__icon { color: var(--c-text-4); }

.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 420px; max-width: 100%; box-shadow: var(--shadow-pop); }
.modal--sm { width: 380px; }
.confirm__text { font-size: var(--t-sm); color: var(--c-text); text-align: center; margin: var(--s-md) 0; line-height: var(--lh-md); }

@media (max-width: 1024px) {
  .wk__body { grid-template-columns: 1fr; }
  .wk__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .stat-grid, .stat-grid--3 { grid-template-columns: repeat(2, 1fr); }
  .list { max-height: 320px; }
}
</style>
