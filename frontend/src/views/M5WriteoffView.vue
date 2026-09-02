<script setup lang="ts">
/* M5-12 优惠券核销 /m5-writeoff — 扫码核销 + 重复/伪造拦截；联动 M5-02 券、M6-03 对账 */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CSelect from '@/components/CSelect.vue'
import CInput from '@/components/CInput.vue'
import {
  useM5CoreStore, WRITEOFF_STATUS_LABEL, WRITEOFF_STATUS_PILL, type WriteoffStatus,
} from '@/stores/m5Core'
import { useAuthStore } from '@/stores/auth'

const core = useM5CoreStore()
const auth = useAuthStore()

const filterStatus = ref('ALL')
const verifyCode = ref('')
const customerName = ref('')
const customerPhone = ref('')
const amount = ref<number | null>(null)
const verifyResult = ref<{ ok: boolean; message: string; record: { couponName: string; discount: number; status: WriteoffStatus } } | null>(null)

const canVerify = computed(() => auth.can('couponWriteoff:verify'))

const filtered = computed(() => {
  if (filterStatus.value === 'ALL') return core.writeoffs
  return core.writeoffs.filter((w) => w.status === filterStatus.value)
})

const kpis = computed(() => [
  { label: '今日核销', icon: 'user-check', value: String(core.writeoffStats.total), tone: 'brand' as const },
  { label: '正常核销', icon: 'user-check', value: String(core.writeoffStats.ok), tone: 'success' as const },
  { label: '异常拦截', icon: 'alert', value: String(core.writeoffStats.abnormal), tone: 'danger' as const },
  { label: '优惠抵扣额', icon: 'marketing', value: '¥' + core.writeoffStats.discount.toLocaleString(), tone: 'orange' as const },
])

function doVerify() {
  verifyResult.value = null
  if (!verifyCode.value.trim()) { verifyResult.value = { ok: false, message: '请输入或扫描券码', record: { couponName: '', discount: 0, status: 'FORGED' } }; return }
  if (!customerName.value.trim() || !customerPhone.value.trim()) { verifyResult.value = { ok: false, message: '请填写客户姓名和手机号', record: { couponName: '', discount: 0, status: 'FORGED' } }; return }
  if (!amount.value || amount.value <= 0) { verifyResult.value = { ok: false, message: '请输入核销订单金额', record: { couponName: '', discount: 0, status: 'FORGED' } }; return }
  const r = core.verifyCoupon(verifyCode.value.trim(), customerName.value.trim(), customerPhone.value.trim(), amount.value)
  verifyResult.value = {
    ok: r.ok,
    message: r.ok ? `核销成功，优惠抵扣 ¥${r.record.discount.toLocaleString()}` : `核销拦截：${r.reason}`,
    record: { couponName: r.record.couponName, discount: r.record.discount, status: r.record.status },
  }
  if (r.ok) { verifyCode.value = ''; customerName.value = ''; customerPhone.value = ''; amount.value = null }
}

function statusTone(s: WriteoffStatus): 'success' | 'warning' | 'danger' { return WRITEOFF_STATUS_PILL[s] }

onMounted(() => { core.seed() })
</script>

<template>
  <div class="vw">
    <div class="vw__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <div class="vw__body">
      <CCard class="vw__scan" padding="lg">
        <div class="scan-title"><CIcon name="scan" :size="16" />扫码 / 输入券码核销</div>
        <div class="scan-field">
          <CIcon name="scan" :size="18" class="scan-icon" />
          <CInput v-model="verifyCode" placeholder="请输入券码（如 WATER500）" />
        </div>
        <div class="scan-grid">
          <CInput v-model="customerName" placeholder="客户姓名" />
          <CInput v-model="customerPhone" placeholder="手机号" />
        </div>
        <div class="scan-field">
          <span class="yen">¥</span>
          <input v-model.number="amount" type="number" class="native-input native-input--yen" placeholder="订单金额" />
        </div>
        <CButton variant="primary" block :disabled="!canVerify" @click="doVerify">
          <CIcon name="check-square" :size="14" />确认核销
        </CButton>
        <div v-if="verifyResult" class="verify-result" :class="verifyResult.ok ? 'is-ok' : 'is-fail'">
          <CIcon :name="verifyResult.ok ? 'check' : 'alert'" :size="15" />
          <span>{{ verifyResult.message }}</span>
        </div>
        <div class="scan-tip">
          <CIcon name="shield" :size="12" />系统自动校验：券码有效性、是否已核销（防重复）、是否过期；异常立即拦截并告警。
        </div>
      </CCard>

      <CCard class="vw__list" padding="none">
        <div class="list-head">
          <div class="list-head__left">
            <span class="list-head__title">核销流水</span>
            <span class="list-head__hint">{{ filtered.length }} 笔</span>
          </div>
          <CSelect v-model="filterStatus" :options="[
            { value: 'ALL', label: '全部状态' },
            { value: 'OK', label: '正常核销' },
            { value: 'DUPLICATE', label: '重复核销' },
            { value: 'FORGED', label: '伪造券码' },
            { value: 'EXPIRED', label: '已过期' },
          ]" />
        </div>
        <div class="v-list">
          <div v-for="w in filtered" :key="w.id" class="v-row" :class="`v-row--${w.status.toLowerCase()}`">
            <div class="v-row__top">
              <span class="v-row__code">{{ w.couponCode }}</span>
              <CStatusPill :status="statusTone(w.status)" dot>{{ WRITEOFF_STATUS_LABEL[w.status] }}</CStatusPill>
            </div>
            <div class="v-row__name">{{ w.couponName }}</div>
            <div class="v-row__sub">{{ w.customerName }} · {{ w.customerPhone }} · {{ w.operator }}</div>
            <div class="v-row__bottom">
              <span class="v-row__time">{{ w.verifiedAt }}</span>
              <span class="v-row__amount">订单 ¥{{ w.amount.toLocaleString() }} <em v-if="w.discount > 0">减 ¥{{ w.discount }}</em></span>
            </div>
          </div>
        </div>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.vw { display: flex; flex-direction: column; gap: var(--s-lg); }
.vw__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .vw__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }
.vw__body { display: grid; grid-template-columns: 360px 1fr; gap: var(--s-lg); align-items: start; }

.vw__scan :deep(.card__body) { display: flex; flex-direction: column; gap: var(--s-md); }
.scan-title { display: flex; align-items: center; gap: var(--s-sm); font-size: var(--t-sm); font-weight: 700; }
.scan-field { position: relative; display: flex; align-items: center; }
.scan-field :deep(.cinput) { width: 100%; padding-left: 36px; }
.scan-icon { position: absolute; left: 10px; color: var(--c-text-3); z-index: 1; }
.yen { position: absolute; left: 12px; color: var(--c-text-3); font-weight: 700; z-index: 1; }
.native-input { width: 100%; padding: 10px; border: 1px solid #D1D1D9; border-radius: var(--r-sm); background: var(--c-surface); font-size: 13px; color: var(--c-text); font-family: inherit; }
.native-input:focus { outline: none; border-color: #4D5AD9; box-shadow: 0 0 0 2px rgba(77,90,217,.12); }
.native-input--yen { padding-left: 28px; }
.scan-field .yen + :deep(.cinput) { padding-left: 28px; }
.scan-grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-sm); }
.verify-result { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-sm) var(--s-md); border-radius: var(--r-sm); font-size: var(--t-sm); }
.verify-result.is-ok { background: rgba(22,163,110,.1); color: var(--c-success-fg); }
.verify-result.is-fail { background: rgba(229,57,53,.08); color: var(--c-danger-fg); }
.scan-tip { display: flex; align-items: center; gap: 4px; font-size: var(--t-xs); color: var(--c-text-3); line-height: 1.5; }

.vw__list { min-width: 0; }
.list-head { display: flex; justify-content: space-between; align-items: center; gap: var(--s-sm); padding: var(--s-md) var(--s-lg); border-bottom: 1px solid var(--c-border-light); flex-wrap: wrap; }
.list-head__left { display: flex; align-items: center; gap: var(--s-sm); }
.list-head__title { font-size: var(--t-sm); font-weight: 700; }
.list-head__hint { font-size: var(--t-xs); color: var(--c-text-3); }
.v-list { max-height: 640px; overflow-y: auto; }
.v-row { padding: var(--s-md) var(--s-lg); border-bottom: 1px solid var(--c-border-light); border-left: 3px solid transparent; }
.v-row--ok { border-left-color: var(--c-success-fg); }
.v-row--duplicate { border-left-color: var(--c-danger-fg); background: rgba(229,57,53,.03); }
.v-row--forged { border-left-color: var(--c-danger-fg); background: rgba(229,57,53,.03); }
.v-row--expired { border-left-color: var(--c-warning-fg); }
.v-row__top { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; }
.v-row__code { font-size: var(--t-xs); color: var(--c-text-3); font-family: monospace; }
.v-row__name { font-size: var(--t-sm); font-weight: 600; margin-bottom: 2px; }
.v-row__sub { font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: 6px; }
.v-row__bottom { display: flex; justify-content: space-between; align-items: center; font-size: var(--t-xs); }
.v-row__time { color: var(--c-text-3); }
.v-row__amount { color: var(--c-text-2); font-variant-numeric: tabular-nums; }
.v-row__amount em { color: var(--c-danger-fg); font-style: normal; font-weight: 700; }

@media (max-width: 1200px) { .vw__body { grid-template-columns: 1fr; } }
@media (max-width: 1024px) { .vw__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; } }
</style>
