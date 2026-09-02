<script setup lang="ts">
/* ============================================================
 * M6-08 咨询师提成 /m6-commission
 * 4 KPI：应发提成合计 / 已审批待发放 / 待审批 / 已发放
 * 左：提成单列表（按期间筛选）；右：详情（业绩基数 + 阶梯明细 + 提交/审批/驳回/发放）
 * 红线：业绩基数固定 WRITEOFF 口径（已双签划扣确认收入）；
 *       提成发放仅镜像外部薪酬系统回传，本系统不直接动账。
 * 严格消费 src/stores/finCommission.ts 现有 API，不改 store。
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CTextarea from '@/components/CTextarea.vue'
import CSelect from '@/components/CSelect.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CBarChart from '@/components/CBarChart.vue'
import { useFinCommissionStore } from '@/stores/finCommission'

const store = useFinCommissionStore()
onMounted(() => store.seed())

const selectedId = ref<string | null>(null)
const selected = computed(() => {
  if (selectedId.value) return store.get(selectedId.value) ?? null
  return store.filtered[0] ?? null
})

const kpis = computed(() => [
  { label: '应发提成合计', icon: 'profile', value: money(store.totalCommission), tone: 'brand' as const },
  { label: '已审批待发放', icon: 'check-square', value: money(store.approvedCommission), tone: 'orange' as const },
  { label: '待审批', icon: 'check-square', value: money(store.pendingCommission), tone: 'warning' as const },
  { label: '已发放', icon: 'finance', value: money(store.paidCommission), tone: 'success' as const },
])

const statusOptions = [
  { value: 'ALL', label: '全部状态' },
  { value: 'DRAFT', label: '待提交' },
  { value: 'SUBMITTED', label: '待审批' },
  { value: 'APPROVED', label: '已审批待发放' },
  { value: 'PAID', label: '已发放' },
  { value: 'REJECTED', label: '已驳回' },
]
const periodOptions = computed(() => [
  { value: 'ALL', label: '全部期间' },
  ...store.periods.map((p) => ({ value: p, label: p })),
])

function money(n: number) {
  return `¥${Math.round(n).toLocaleString('zh-CN')}`
}
function fmtTime(iso?: string) {
  return iso ? iso.slice(0, 16).replace('T', ' ') : '—'
}
function effectiveRate(item: { commission: number; baseAmount: number }) {
  return item.baseAmount > 0 ? (item.commission / item.baseAmount) * 100 : 0
}

/** 当前筛选期间内的提成排行（横向条形图） */
const ranking = computed(() => {
  return store.filtered
    .slice()
    .sort((a, b) => b.commission - a.commission)
    .slice(0, 6)
    .map((i) => ({ label: i.consultantName, values: [i.commission] }))
})

// 操作
function doSubmit() {
  if (selected.value) store.submit(selected.value.id)
}
function doApprove() {
  if (selected.value) store.approve(selected.value.id)
}
function doPay() {
  if (selected.value) store.markPaid(selected.value.id)
}

// 驳回弹层
const showReject = ref(false)
const rejectReason = ref('')
function openReject() {
  rejectReason.value = ''
  showReject.value = true
}
function confirmReject() {
  if (!selected.value || !rejectReason.value.trim()) return
  store.reject(selected.value.id, rejectReason.value.trim())
  showReject.value = false
}
</script>

<template>
  <div class="fcm">
    <div class="fcm__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <div class="fcm__body">
      <!-- 左：提成单列表 -->
      <CCard class="fcm__list" padding="none">
        <div class="filters">
          <CSelect v-model="store.filterPeriod" :options="periodOptions" width="130px" />
          <CSelect v-model="store.filterStatus" :options="statusOptions" width="140px" />
          <div class="filters__right">
            <CButton variant="secondary" size="sm" v-perm.disable="'finance:commission:edit'">
              <CIcon name="export" :size="14" />导出薪酬表
            </CButton>
          </div>
        </div>
        <div class="list">
          <div v-if="store.filtered.length === 0" class="empty">
            <CIcon name="sign" :size="28" class="empty__icon" />
            <div>暂无提成单数据</div>
          </div>
          <button
            v-for="it in store.filtered" :key="it.id"
            class="row" :class="{ 'row--active': selected?.id === it.id }"
            @click="selectedId = it.id"
          >
            <div class="row__avatar">{{ it.consultantName.slice(0, 1) }}</div>
            <div class="row__main">
              <div class="row__top">
                <span class="row__name">{{ it.consultantName }}</span>
                <CStatusPill :status="store.STATUS_PILL[it.status]" dot>
                  {{ store.STATUS_LABEL[it.status] }}
                </CStatusPill>
              </div>
              <div class="row__sub">{{ it.title }} · {{ it.period }} · {{ it.orderCount }} 单</div>
              <div class="row__sub">{{ it.ruleName }}</div>
            </div>
            <div class="row__right">
              <div class="row__amount">{{ money(it.commission) }}</div>
              <div class="row__base">基数 {{ money(it.baseAmount) }}</div>
            </div>
          </button>
        </div>
      </CCard>

      <!-- 右：详情 -->
      <CCard v-if="selected" class="fcm__detail" padding="none">
        <template #header>
          <div class="fcm__detail-head">
            <div class="fcm__avatar">{{ selected.consultantName.slice(0, 1) }}</div>
            <div class="fcm__who">
              <div class="fcm__name">{{ selected.consultantName }} · {{ selected.title }}</div>
              <div class="fcm__sub">{{ selected.period }} · 业绩口径：划扣确认收入 · {{ selected.orderCount }} 单</div>
            </div>
            <CStatusPill :status="store.STATUS_PILL[selected.status]" dot>
              {{ store.STATUS_LABEL[selected.status] }}
            </CStatusPill>
          </div>
        </template>

        <div class="detail-body">
          <!-- 核心指标 -->
          <div class="stat-grid">
            <div class="stat">
              <div class="stat__label">业绩基数（已双签口径）</div>
              <div class="stat__value stat__value--brand">{{ money(selected.baseAmount) }}</div>
            </div>
            <div class="stat">
              <div class="stat__label">提成合计</div>
              <div class="stat__value stat__value--orange">{{ money(selected.commission) }}</div>
            </div>
            <div class="stat">
              <div class="stat__label">综合提成率</div>
              <div class="stat__value">{{ effectiveRate(selected).toFixed(2) }}%</div>
            </div>
            <div class="stat">
              <div class="stat__label">阶梯档位数</div>
              <div class="stat__value">{{ selected.tiers.length }}</div>
            </div>
          </div>

          <!-- 阶梯明细 -->
          <div class="block">
            <div class="block__title">
              <span>阶梯提成明细</span>
              <span class="block__hint">{{ selected.ruleName }}</span>
            </div>
            <div class="tier-table">
              <div class="tier-head">
                <span>档位</span>
                <span>分段金额</span>
                <span>比例</span>
                <span class="t-col-r">提成</span>
              </div>
              <div v-for="(t, i) in selected.tiers" :key="i" class="tier-row">
                <span>{{ t.label }}</span>
                <span>{{ money(t.amount) }}</span>
                <span>{{ (t.rate * 100).toFixed(0) }}%</span>
                <span class="t-col-r">{{ money(t.commission) }}</span>
              </div>
              <div class="tier-row tier-row--total">
                <span>合计</span>
                <span>{{ money(selected.baseAmount) }}</span>
                <span>—</span>
                <span class="t-col-r">{{ money(selected.commission) }}</span>
              </div>
            </div>
          </div>

          <!-- 提成排行 -->
          <div class="block">
            <div class="block__title"><span>当前筛选 Top{{ ranking.length }}</span></div>
            <CBarChart v-if="ranking.length" :items="ranking" orientation="horizontal" :height="0" :show-value="true" />
            <div v-else class="empty-inline">暂无数据</div>
          </div>

          <!-- 备注/审批信息 -->
          <div v-if="selected.remark || selected.approver || selected.paidAt" class="kv">
            <div v-if="selected.approver" class="kv__row">
              <span class="kv__k">审批人</span><span class="kv__v">{{ selected.approver }} · {{ fmtTime(selected.approvedAt) }}</span>
            </div>
            <div v-if="selected.paidAt" class="kv__row">
              <span class="kv__k">发放时间</span><span class="kv__v kv__v--success">{{ fmtTime(selected.paidAt) }}</span>
            </div>
            <div v-if="selected.remark" class="kv__row">
              <span class="kv__k">备注</span><span class="kv__v">{{ selected.remark }}</span>
            </div>
          </div>

          <!-- 操作流 -->
          <div v-if="selected.status === 'DRAFT' || selected.status === 'REJECTED'" class="actions">
            <CButton variant="primary" v-perm.disable="'finance:commission:edit'" @click="doSubmit">
              <CIcon name="check-square" :size="14" />提交审批
            </CButton>
            <span v-if="selected.status === 'REJECTED' && selected.remark" class="actions__hint">
              <CIcon name="alert" :size="12" />上次驳回原因：{{ selected.remark }}
            </span>
          </div>
          <div v-else-if="selected.status === 'SUBMITTED'" class="actions">
            <CButton variant="primary" v-perm.disable="'finance:commission:approve'" @click="doApprove">
              <CIcon name="check" :size="14" />审批通过
            </CButton>
            <CButton variant="ghost" size="sm" v-perm.disable="'finance:commission:approve'" @click="openReject">驳回</CButton>
          </div>
          <div v-else-if="selected.status === 'APPROVED'" class="actions">
            <CButton variant="primary" v-perm.disable="'finance:commission:approve'" @click="doPay">
              <CIcon name="check" :size="14" />确认发放（薪酬系统回传）
            </CButton>
            <span class="actions__hint"><CIcon name="shield" :size="12" />仅镜像状态，真实薪酬由外部通道划付</span>
          </div>
          <div v-else-if="selected.status === 'PAID'" class="paid-meta">
            <CIcon name="check" :size="12" />
            已发放{{ selected.approver ? `（审批：${selected.approver}）` : '' }}，发放时间 {{ fmtTime(selected.paidAt) }}
          </div>
        </div>
      </CCard>

      <CCard v-else class="fcm__detail fcm__detail--empty" title="提成详情" padding="lg">
        <div class="detail-empty">
          <CIcon name="sign" :size="40" class="detail-empty__icon" />
          <p>请选择一张提成单</p>
        </div>
      </CCard>
    </div>

    <!-- 驳回弹层 -->
    <div v-if="showReject" class="modal-mask" @click.self="showReject = false">
      <CCard class="modal" title="驳回提成单" padding="lg">
        <p class="modal__hint">驳回后提成单回到草稿状态，可调整后重新提交。</p>
        <label class="form__label">驳回原因</label>
        <CTextarea v-model="rejectReason" :rows="3" placeholder="请填写驳回原因" />
        <template #footer>
          <CButton variant="ghost" @click="showReject = false">取消</CButton>
          <CButton variant="primary" @click="confirmReject">确认驳回</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.fcm { display: flex; flex-direction: column; gap: var(--s-lg); }
.fcm__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .fcm__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }

.fcm__body { display: grid; grid-template-columns: 420px 1fr; gap: var(--s-lg); align-items: start; }
.fcm__list { min-width: 0; }
.filters { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-md); border-bottom: 1px solid var(--c-border-light); flex-wrap: nowrap; overflow-x: auto; }
.filters__right { display: flex; align-items: center; gap: var(--s-sm); margin-left: auto; flex-shrink: 0; }
.list { max-height: 700px; overflow-y: auto; }
.empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); font-size: var(--t-sm); }
.empty__icon { color: var(--c-text-4); }

.row {
  display: flex; align-items: center; gap: var(--s-sm); width: 100%; text-align: left;
  padding: var(--s-md) var(--s-lg); background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer;
}
.row:hover { background: var(--c-brand-soft); }
.row--active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.row__avatar {
  width: 36px; height: 36px; border-radius: 50%; background: var(--c-brand-soft); color: var(--c-brand);
  display: flex; align-items: center; justify-content: center; font-weight: 700; font-size: var(--t-sm); flex-shrink: 0;
}
.row__main { flex: 1; min-width: 0; }
.row__top { display: flex; align-items: center; gap: var(--s-xs); margin-bottom: 2px; }
.row__name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.row__sub { font-size: var(--t-xs); color: var(--c-text-3); line-height: 1.5; }
.row__right { text-align: right; flex-shrink: 0; }
.row__amount { font-size: var(--t-md); font-weight: 700; color: var(--c-text); font-variant-numeric: tabular-nums; }
.row__base { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }

.fcm__detail-head { display: flex; align-items: center; gap: var(--s-md); width: 100%; }
.fcm__avatar {
  width: 48px; height: 48px; border-radius: 50%; background: var(--c-brand-soft); color: var(--c-brand);
  display: flex; align-items: center; justify-content: center; font-size: var(--t-lg); font-weight: 700;
}
.fcm__who { flex: 1; min-width: 0; }
.fcm__name { font-size: var(--t-lg); font-weight: 700; color: var(--c-text); }
.fcm__sub { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }

.detail-body { padding: var(--s-lg); display: flex; flex-direction: column; gap: var(--s-lg); }

.stat-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); }
.stat { background: var(--c-bg-right); border-radius: var(--r-md); padding: var(--s-md); }
.stat__label { font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: 4px; }
.stat__value { font-size: var(--t-md); font-weight: 700; color: var(--c-text); font-variant-numeric: tabular-nums; }
.stat__value--brand { color: var(--c-brand); }
.stat__value--orange { color: var(--c-orange-dark); }

.block { display: flex; flex-direction: column; gap: var(--s-sm); }
.block__title { display: flex; justify-content: space-between; align-items: center; font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.block__hint { font-size: var(--t-xs); color: var(--c-text-3); font-weight: 400; }

.tier-table { border: 1px solid var(--c-border-light); border-radius: var(--r-md); overflow: hidden; }
.tier-head, .tier-row {
  display: grid; grid-template-columns: 1.4fr 1fr 0.6fr 1fr;
  padding: var(--s-sm) var(--s-md); font-size: var(--t-sm); align-items: center;
}
.tier-head { background: var(--c-bg-right); color: var(--c-text-3); font-size: var(--t-xs); font-weight: 600; }
.tier-row { border-top: 1px solid var(--c-border-light); color: var(--c-text-2); }
.t-col-r { text-align: right; font-variant-numeric: tabular-nums; font-weight: 600; color: var(--c-text); }
.tier-row--total { background: var(--c-brand-soft); font-weight: 700; color: var(--c-text); }
.tier-row--total .t-col-r { color: var(--c-brand); font-size: var(--t-md); }

.empty-inline { font-size: var(--t-sm); color: var(--c-text-3); padding: var(--s-md) 0; }

.kv { display: flex; flex-direction: column; gap: var(--s-sm); background: var(--c-bg-right); border-radius: var(--r-md); padding: var(--s-md); }
.kv__row { display: flex; justify-content: space-between; gap: var(--s-md); font-size: var(--t-sm); }
.kv__k { color: var(--c-text-3); flex-shrink: 0; }
.kv__v { color: var(--c-text-2); text-align: right; word-break: break-all; }
.kv__v--success { color: var(--c-success-fg); font-weight: 600; }

.actions { display: flex; gap: var(--s-sm); align-items: center; flex-wrap: wrap; }
.actions__hint { display: inline-flex; align-items: center; gap: 4px; font-size: var(--t-xs); color: var(--c-text-3); }
.paid-meta { display: flex; align-items: center; gap: 4px; font-size: var(--t-xs); color: var(--c-success-fg); }

.detail-empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-md); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); }
.detail-empty__icon { color: var(--c-text-4); }

.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 440px; max-width: 100%; box-shadow: var(--shadow-pop); }
.modal__hint { font-size: var(--t-xs); color: var(--c-text-3); margin: 0 0 var(--s-md); line-height: 1.6; }
.form__label { display: block; font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: var(--s-xs); }

@media (max-width: 1024px) {
  .fcm__body { grid-template-columns: 1fr; }
  .stat-grid { grid-template-columns: repeat(2, 1fr); }
  .list { max-height: 360px; }
}
</style>
