<script setup lang="ts">
/* M5-02 优惠券管理 /m5-coupons — 券模板 + 发放记录 + 防超发；联动 M4 收银 / M5 核销 / M6 对账 */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CSelect from '@/components/CSelect.vue'
import CInput from '@/components/CInput.vue'
import CProgressBar from '@/components/CProgressBar.vue'
import {
  useM5CouponStore, type CouponTemplate, type CouponType, type GrantScope,
  COUPON_TYPE_LABEL, COUPON_STATUS_LABEL, COUPON_STATUS_PILL, GRANT_STATUS_PILL,
} from '@/stores/m5Coupon'
import { useSensitiveWords } from '@/composables/useSensitiveWords'
import { useAuthStore } from '@/stores/auth'

const store = useM5CouponStore()
const auth = useAuthStore()
const sw = useSensitiveWords()

const filterStatus = ref('ALL')
const selectedId = ref<string | null>(null)
const showCreate = ref(false)
const showGrant = ref(false)
const formError = ref('')

const canCreate = computed(() => auth.can('coupon:create'))
const canGrant = computed(() => auth.can('coupon:edit'))

const filtered = computed(() => {
  if (filterStatus.value === 'ALL') return store.coupons
  return store.coupons.filter((c) => c.status === filterStatus.value)
})
const selected = computed(() => store.coupons.find((c) => c.id === selectedId.value) ?? null)

const kpis = computed(() => [
  { label: '进行中券', icon: 'marketing', value: String(store.stats.active), tone: 'success' as const },
  { label: '已领取 / 库存', icon: 'package', value: `${store.stats.totalReceived.toLocaleString()} / ${store.stats.totalIssued.toLocaleString()}`, tone: 'brand' as const },
  { label: '已核销', icon: 'user-check', value: String(store.stats.totalUsed), tone: 'teal' as const },
  { label: '核销率', icon: 'user-check', value: store.stats.usageRate + '%', tone: 'orange' as const },
])

// 创建表单
const blankForm = () => ({
  name: '', type: 'AMOUNT' as CouponType, value: 100, threshold: 0, total: 100,
  startDate: '', endDate: '', scope: 'ALL' as GrantScope, scopeName: '全部客户',
})
const form = ref(blankForm())

function openCreate() {
  form.value = blankForm()
  formError.value = ''
  showCreate.value = true
}
function submitCreate() {
  formError.value = ''
  if (!form.value.name.trim()) { formError.value = '请输入券名称'; return }
  const hit = sw.check(form.value.name)
  if (hit.hit) { formError.value = hit.message; return }
  if (form.value.total < 1) { formError.value = '库存必须大于 0'; return }
  if (!form.value.startDate || !form.value.endDate) { formError.value = '请选择有效期'; return }
  try {
    const c = store.createCoupon({ ...form.value })
    showCreate.value = false
    selectedId.value = c.id
  } catch (e) { formError.value = (e as Error).message }
}

// 发放弹层
const grantForm = ref({ scope: 'ALL' as GrantScope, targetName: '全部客户', count: 10 })
function openGrant() {
  if (!selected.value) return
  grantForm.value = { scope: selected.value.scope, targetName: selected.value.scopeName, count: Math.min(10, store.stockLeft(selected.value)) }
  formError.value = ''
  showGrant.value = true
}
function submitGrant() {
  if (!selected.value) return
  formError.value = ''
  if (grantForm.value.count < 1) { formError.value = '发放数量必须大于 0'; return }
  const rec = store.grant(selected.value.id, grantForm.value.scope, grantForm.value.targetName, grantForm.value.count)
  if (rec.status === 'FAILED') formError.value = '库存不足，发放失败'
  else showGrant.value = false
}

function stockPct(c: CouponTemplate) {
  return c.total ? Math.round((c.received / c.total) * 100) : 0
}
function usedPct(c: CouponTemplate) {
  return c.received ? Math.round((c.used / c.received) * 100) : 0
}
function valueText(c: CouponTemplate) {
  if (c.type === 'AMOUNT') return `¥${c.value}`
  if (c.type === 'RATE') return `${c.value}折`
  return `券包 ¥${c.value}`
}

onMounted(() => { store.seed(); if (store.coupons.length) selectedId.value = store.coupons[0].id })
</script>

<template>
  <div class="cp">
    <div class="cp__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <CCard class="cp__toolbar" padding="none">
      <div class="cp__tools">
        <CSelect v-model="filterStatus" :options="[
          { value: 'ALL', label: '全部状态' },
          { value: 'ACTIVE', label: '进行中' },
          { value: 'DRAFT', label: '草稿' },
          { value: 'EXPIRED', label: '已过期' },
          { value: 'DISABLED', label: '已停用' },
        ]" />
        <CButton v-if="canCreate" size="sm" variant="primary" class="cp__tools-btn" @click="openCreate">
          <CIcon name="plus" :size="14" />创建券
        </CButton>
      </div>
    </CCard>

    <div class="cp__body">
      <CCard class="cp__list" padding="none">
        <div class="list-head">
          <div class="list-head__left">
            <span class="list-head__title">优惠券列表</span>
            <span class="list-head__hint">{{ filtered.length }} 张</span>
          </div>
        </div>
        <div class="c-list">
          <button v-for="c in filtered" :key="c.id" class="c-row"
            :class="{ 'c-row--active': c.id === selectedId }"
            @click="selectedId = c.id">
            <div class="c-row__top">
              <span class="c-row__name">{{ c.name }}</span>
              <CStatusPill :status="COUPON_STATUS_PILL[c.status]" dot>{{ COUPON_STATUS_LABEL[c.status] }}</CStatusPill>
            </div>
            <div class="c-row__mid">
              <span class="c-row__value">{{ valueText(c) }}</span>
              <span class="c-row__thr" v-if="c.threshold > 0">满{{ c.threshold }}可用</span>
            </div>
            <div class="c-row__sub">{{ COUPON_TYPE_LABEL[c.type] }} · 领{{ c.received }}/{{ c.total }} · 核{{ c.used }}</div>
            <CProgressBar :value="stockPct(c)" :height="4"
              :color="stockPct(c) >= 90 ? 'var(--c-danger-fg)' : 'var(--c-brand)'" />
          </button>
        </div>
      </CCard>

      <CCard v-if="selected" class="cp__detail" padding="lg">
        <div class="det-head">
          <div>
            <h3 class="det-head__name">{{ selected.name }}</h3>
            <div class="det-head__sub">{{ COUPON_TYPE_LABEL[selected.type] }} · {{ selected.scopeName }} · {{ selected.startDate }} 至 {{ selected.endDate }}</div>
          </div>
          <CStatusPill :status="COUPON_STATUS_PILL[selected.status]" dot>{{ COUPON_STATUS_LABEL[selected.status] }}</CStatusPill>
        </div>

        <div class="det-value">
          <span class="det-value__num">{{ valueText(selected) }}</span>
          <span class="det-value__thr" v-if="selected.threshold > 0">满 ¥{{ selected.threshold.toLocaleString() }} 可用</span>
          <span class="det-value__thr" v-else>无门槛</span>
        </div>

        <div class="det-stats">
          <div class="det-stat"><span class="det-stat__n">{{ selected.total }}</span><span class="det-stat__l">总库存</span></div>
          <div class="det-stat"><span class="det-stat__n">{{ store.stockLeft(selected) }}</span><span class="det-stat__l">剩余</span></div>
          <div class="det-stat"><span class="det-stat__n">{{ selected.received }}</span><span class="det-stat__l">已领取</span></div>
          <div class="det-stat"><span class="det-stat__n">{{ selected.used }}</span><span class="det-stat__l">已核销</span></div>
        </div>

        <div class="det-bar">
          <div class="det-bar__row"><span>领取进度</span><span>{{ stockPct(selected) }}%</span></div>
          <CProgressBar :value="stockPct(selected)" :height="8"
            :color="stockPct(selected) >= 90 ? 'var(--c-danger-fg)' : 'var(--c-brand)'" />
          <div class="det-bar__row" style="margin-top:8px"><span>核销率（占已领）</span><span>{{ usedPct(selected) }}%</span></div>
          <CProgressBar :value="usedPct(selected)" :height="8" color="var(--c-success-fg)" />
        </div>

        <div v-if="store.isExpiringSoon(selected)" class="warn-box">
          <CIcon name="alert" :size="14" />该券即将在 7 天内过期，请注意续期或停用。
        </div>

        <div class="det-ops">
          <CButton v-if="canGrant && (selected.status === 'ACTIVE')" variant="primary" size="sm" @click="openGrant">
            <CIcon name="customer" :size="14" />发放
          </CButton>
          <CButton v-if="selected.status === 'DRAFT' || selected.status === 'DISABLED'" variant="secondary" size="sm"
            :disabled="!canGrant" @click="store.enableCoupon(selected.id)">
            <CIcon name="check" :size="14" />启用
          </CButton>
          <CButton v-if="selected.status === 'ACTIVE'" variant="secondary" size="sm"
            :disabled="!canGrant" @click="store.disableCoupon(selected.id)">
            <CIcon name="close" :size="14" />停用
          </CButton>
        </div>

        <div class="grant-records">
          <div class="grant-records__title">发放记录</div>
          <div v-if="store.grants.filter(g => g.couponId === selected?.id).length === 0" class="empty">暂无发放记录</div>
          <div v-for="g in store.grants.filter(gr => gr.couponId === selected?.id)" :key="g.id" class="gr-row">
            <div>
              <span class="gr-row__target">{{ g.targetName }}</span>
              <span class="gr-row__meta">{{ g.count }} 张 · {{ g.operator }} · {{ g.grantedAt }}</span>
            </div>
            <CStatusPill :status="GRANT_STATUS_PILL[g.status]">{{ g.status === 'GRANTED' ? '已发放' : g.status === 'PENDING' ? '发放中' : '失败' }}</CStatusPill>
          </div>
        </div>
      </CCard>
    </div>

    <!-- 创建券弹层 -->
    <div v-if="showCreate" class="modal-mask" @click.self="showCreate = false">
      <CCard class="modal" title="创建优惠券" padding="lg">
        <div class="form-row">
          <label class="form-label">券名称</label>
          <CInput v-model="form.name" placeholder="如：水光满3000减500" />
        </div>
        <div class="form-grid">
          <div class="form-row">
            <label class="form-label">券类型</label>
            <CSelect v-model="form.type" :options="[
              { value: 'AMOUNT', label: '满减券' },
              { value: 'RATE', label: '折扣券' },
              { value: 'PACKAGE', label: '券包' },
            ]" />
          </div>
          <div class="form-row">
            <label class="form-label">{{ form.type === 'RATE' ? '折扣（如8.5）' : '面额（元）' }}</label>
            <input v-model.number="form.value" type="number" class="native-input" />
          </div>
        </div>
        <div class="form-grid">
          <div class="form-row">
            <label class="form-label">使用门槛（元，0=无门槛）</label>
            <input v-model.number="form.threshold" type="number" class="native-input" />
          </div>
          <div class="form-row">
            <label class="form-label">发放库存</label>
            <input v-model.number="form.total" type="number" class="native-input" />
          </div>
        </div>
        <div class="form-grid">
          <div class="form-row">
            <label class="form-label">生效日期</label>
            <input v-model="form.startDate" type="date" class="native-input" />
          </div>
          <div class="form-row">
            <label class="form-label">失效日期</label>
            <input v-model="form.endDate" type="date" class="native-input" />
          </div>
        </div>
        <div class="form-row">
          <label class="form-label">发放范围</label>
          <CSelect v-model="form.scope" :options="[
            { value: 'ALL', label: '全部客户' },
            { value: 'NEW', label: '新客专享' },
            { value: 'SEGMENT', label: '指定人群' },
            { value: 'DESIGNATED', label: '指定客户' },
          ]" @update:modelValue="(v) => form.scopeName = ({ALL:'全部客户',NEW:'新客专享',SEGMENT:'指定人群',DESIGNATED:'指定客户'} as Record<string,string>)[v] ?? v" />
        </div>
        <div v-if="formError" class="form-error"><CIcon name="alert" :size="13" />{{ formError }}</div>
        <div class="modal-foot">
          <CButton variant="ghost" size="sm" @click="showCreate = false">取消</CButton>
          <CButton variant="primary" size="sm" @click="submitCreate">创建（保存为草稿）</CButton>
        </div>
      </CCard>
    </div>

    <!-- 发放弹层 -->
    <div v-if="showGrant" class="modal-mask" @click.self="showGrant = false">
      <CCard class="modal" title="发放优惠券" padding="lg">
        <div class="form-row">
          <label class="form-label">发放范围</label>
          <CSelect v-model="grantForm.scope" :options="[
            { value: 'ALL', label: '全部客户' },
            { value: 'NEW', label: '新客专享' },
            { value: 'SEGMENT', label: '指定人群' },
            { value: 'DESIGNATED', label: '指定客户' },
          ]" />
        </div>
        <div class="form-row">
          <label class="form-label">发放数量（剩余可发 {{ selected ? store.stockLeft(selected) : 0 }} 张）</label>
          <input v-model.number="grantForm.count" type="number" class="native-input" />
        </div>
        <div v-if="formError" class="form-error"><CIcon name="alert" :size="13" />{{ formError }}</div>
        <div class="modal-foot">
          <CButton variant="ghost" size="sm" @click="showGrant = false">取消</CButton>
          <CButton variant="primary" size="sm" @click="submitGrant">确认发放</CButton>
        </div>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.cp { display: flex; flex-direction: column; gap: var(--s-lg); }
.cp__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .cp__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }
.cp__body { display: grid; grid-template-columns: 380px 1fr; gap: var(--s-lg); align-items: start; }
.cp__list { min-width: 0; }

.list-head { display: flex; justify-content: space-between; align-items: center; gap: var(--s-sm); padding: var(--s-md) var(--s-lg); border-bottom: 1px solid var(--c-border-light); flex-wrap: wrap; }
.list-head__left { display: flex; align-items: center; gap: var(--s-sm); }
.list-head__right { display: flex; align-items: center; gap: var(--s-sm); }
.list-head__title { font-size: var(--t-sm); font-weight: 700; }
.list-head__hint { font-size: var(--t-xs); color: var(--c-text-3); }
.cp__toolbar { flex-shrink: 0; }
.cp__tools { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-md); flex-wrap: nowrap; overflow-x: auto; }
.cp__tools > * { flex-shrink: 0; }
.cp__tools-btn { margin-left: auto; white-space: nowrap; }
.c-list { max-height: 640px; overflow-y: auto; }
.c-row { display: block; width: 100%; text-align: left; padding: var(--s-md) var(--s-lg); background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer; border-left: 3px solid transparent; }
.c-row:hover { background: var(--c-brand-soft); }
.c-row--active { background: var(--c-brand-soft); border-left-color: var(--c-brand); }
.c-row__top { display: flex; justify-content: space-between; align-items: center; gap: var(--s-sm); margin-bottom: 6px; }
.c-row__name { font-size: var(--t-sm); font-weight: 600; }
.c-row__mid { display: flex; align-items: baseline; gap: var(--s-sm); margin-bottom: 4px; }
.c-row__value { font-size: var(--t-md); font-weight: 800; color: var(--c-danger-fg); font-variant-numeric: tabular-nums; }
.c-row__thr { font-size: var(--t-xs); color: var(--c-text-3); }
.c-row__sub { font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: 6px; }

.cp__detail :deep(.card__body) { display: flex; flex-direction: column; gap: var(--s-md); min-width: 0; }
.det-head { display: flex; justify-content: space-between; align-items: flex-start; gap: var(--s-md); }
.det-head > div { min-width: 0; flex: 1; }
.det-head__name { margin: 0; font-size: var(--t-lg); font-weight: 700; word-break: break-word; }
.det-head__sub { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }
.det-value { display: flex; align-items: baseline; gap: var(--s-sm); }
.det-value__num { font-size: 32px; font-weight: 800; color: var(--c-danger-fg); font-variant-numeric: tabular-nums; }
.det-value__thr { font-size: var(--t-sm); color: var(--c-text-3); }
.det-stats { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-sm); background: var(--c-bg-right); border-radius: var(--r-md); padding: var(--s-md); }
.det-stat { display: flex; flex-direction: column; align-items: center; gap: 2px; }
.det-stat__n { font-size: var(--t-lg); font-weight: 700; color: var(--c-text); font-variant-numeric: tabular-nums; }
.det-stat__l { font-size: var(--t-xs); color: var(--c-text-3); }
.det-bar { background: var(--c-bg-right); border-radius: var(--r-md); padding: var(--s-md); }
.det-bar__row { display: flex; justify-content: space-between; font-size: var(--t-xs); color: var(--c-text-2); margin-bottom: 4px; }
.warn-box { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-sm) var(--s-md); background: rgba(245,158,11,.1); color: var(--c-warning-fg); border-radius: var(--r-sm); font-size: var(--t-xs); }
.det-ops { display: flex; gap: var(--s-sm); }
.grant-records { border-top: 1px solid var(--c-border-light); padding-top: var(--s-md); }
.grant-records__title { font-size: var(--t-sm); font-weight: 700; margin-bottom: var(--s-sm); }
.empty { font-size: var(--t-xs); color: var(--c-text-3); padding: var(--s-md) 0; text-align: center; }
.gr-row { display: flex; justify-content: space-between; align-items: center; padding: var(--s-sm) 0; border-bottom: 1px solid var(--c-border-light); font-size: var(--t-xs); }
.gr-row__target { font-weight: 600; display: block; }
.gr-row__meta { color: var(--c-text-3); }

.modal-mask { position: fixed; inset: 0; background: rgba(20,21,43,.45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 520px; max-width: 100%; box-shadow: var(--shadow-pop); }
:deep(.modal .card__body) { display: flex; flex-direction: column; gap: var(--s-md); }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.form-row { display: flex; flex-direction: column; gap: var(--s-xs); }
.form-label { font-size: var(--t-xs); color: var(--c-text-3); }
.native-input { width: 100%; padding: 10px; border: 1px solid #D1D1D9; border-radius: var(--r-sm); background: var(--c-surface); font-size: 13px; color: var(--c-text); font-family: inherit; }
.native-input:focus { outline: none; border-color: #4D5AD9; box-shadow: 0 0 0 2px rgba(77,90,217,.12); }
.form-error { display: flex; align-items: center; gap: 4px; color: var(--c-danger-fg); font-size: var(--t-xs); background: rgba(229,57,53,.08); padding: var(--s-sm) var(--s-md); border-radius: var(--r-sm); }
.modal-foot { display: flex; justify-content: flex-end; gap: var(--s-sm); margin-top: var(--s-xs); }

@media (max-width: 1200px) {
  .cp__body { grid-template-columns: 1fr; }
}
@media (max-width: 1024px) {
  .cp__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .form-grid { grid-template-columns: 1fr; }
}
</style>
