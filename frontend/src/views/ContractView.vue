<script setup lang="ts">
/* ============================================================
 * 合同管理 /contract（Desktop 优先 · 平板堆叠）
 * 一个合同可对应多订单/多资产，承载退款条款（冷静期、违约金）。
 * 状态：草稿 → 生效中 → 已履行 / 已终止。
 * ============================================================ */
import { computed, onMounted, ref, watch } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CTextarea from '@/components/CTextarea.vue'
import CSelect from '@/components/CSelect.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CFab from '@/components/CFab.vue'
import {
  useContractStore,
  CONTRACT_TYPE_LABEL,
  type Contract,
  type ContractType,
} from '@/stores/contract'
import { CONTRACT_STATUS, dictPill } from '@/config/dictionary'
import { useCustomerStore } from '@/stores/customer'
import { useAuthStore } from '@/stores/auth'

const contract = useContractStore()
const customer = useCustomerStore()
const auth = useAuthStore()

onMounted(() => contract.seed())

type Tab = 'draft' | 'effective' | 'completed' | 'terminated'
const tab = ref<Tab>('effective')
const selectedId = ref<string | null>(contract.effective[0]?.id ?? null)

const tabs = computed(() => [
  { k: 'draft' as Tab, label: `草稿 (${contract.drafts.length})` },
  { k: 'effective' as Tab, label: `生效中 (${contract.effective.length})` },
  { k: 'completed' as Tab, label: `已履行 (${contract.completed.length})` },
  { k: 'terminated' as Tab, label: `已终止 (${contract.terminated.length})` },
])

const list = computed<Contract[]>(() => {
  if (tab.value === 'draft') return contract.drafts
  if (tab.value === 'effective') return contract.effective
  if (tab.value === 'completed') return contract.completed
  return contract.terminated
})

const selected = computed(() => {
  if (selectedId.value) return contract.get(selectedId.value) ?? null
  return list.value[0] ?? null
})

watch(list, (l) => {
  if (selectedId.value && !l.some((x) => x.id === selectedId.value)) {
    selectedId.value = l[0]?.id ?? null
  } else if (!selectedId.value && l[0]) {
    selectedId.value = l[0].id
  }
})

function selectTab(t: Tab) {
  tab.value = t
  selectedId.value = null
}

const monthAmount = computed(() =>
  contract.contracts
    .filter((c) => c.status === 'EFFECTIVE' || c.status === 'COMPLETED')
    .reduce((s, c) => s + c.totalAmount, 0),
)

const kpis = computed(() => [
  { label: '生效中', value: contract.effective.length, tone: 'success' as const, icon: 'check-square' as const },
  { label: '草稿', value: contract.drafts.length, tone: 'default' as const, icon: 'edit' as const },
  { label: '已履行', value: contract.completed.length, tone: 'brand' as const, icon: 'box' as const },
  { label: '合同金额合计', value: '¥' + monthAmount.value.toLocaleString('zh-CN'), tone: 'default' as const, icon: 'finance' as const },
])


function fmtMoney(n: number) { return '¥' + n.toLocaleString('zh-CN') }
function fmtDate(iso?: string) {
  if (!iso) return '—'
  return iso.slice(0, 10)
}
function fmtDateTime(iso?: string) {
  if (!iso) return '—'
  const d = new Date(iso)
  return `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

// 操作
function doActivate() {
  if (selected.value) {
    const id = selected.value.id
    contract.activate(id)
    selectedId.value = id
    tab.value = 'effective'
  }
}
function doComplete() {
  if (selected.value) {
    const id = selected.value.id
    contract.complete(id)
    selectedId.value = id
    tab.value = 'completed'
  }
}
const showTerminate = ref(false)
const terminateReason = ref('')
function doTerminate() {
  if (selected.value && terminateReason.value.trim()) {
    const id = selected.value.id
    contract.terminate(id, terminateReason.value.trim())
    showTerminate.value = false
    terminateReason.value = ''
    selectedId.value = id
    tab.value = 'terminated'
  }
}

// 冷静期 + 退款估算
const inCooling = computed(() => selected.value ? contract.inCoolingPeriod(selected.value) : false)
const refundPaidInput = ref('')
const refundPaidNum = computed(() => Number(refundPaidInput.value) || 0)
const estimate = computed(() => {
  if (!selected.value || refundPaidNum.value <= 0) return null
  return contract.refundEstimate(selected.value, refundPaidNum.value)
})

watch(selected, (c) => {
  refundPaidInput.value = c ? String(c.totalAmount) : ''
})

// 新建合同弹层
const showForm = ref(false)
const form = ref({
  customerId: '',
  title: '',
  type: 'COURSE' as ContractType,
  totalAmount: '',
  coolingDays: '7',
  penaltyRate: '20',
  refundTerms: '',
  remarks: '',
})
const customerOptions = computed(() =>
  customer.customers
    .filter((c) => !c.masterId)
    .map((c) => ({ value: c.id, label: `${c.name}（${c.phoneMask}）` })),
)
const typeOptions = [
  { value: 'COURSE', label: '疗程合同' },
  { value: 'STORED_VALUE', label: '储值合同' },
  { value: 'PACKAGE', label: '套餐合同' },
  { value: 'SERVICE', label: '服务合同' },
]
const amountNum = computed(() => Number(form.value.totalAmount) || 0)
const canSubmit = computed(
  () =>
    form.value.customerId &&
    form.value.title.trim() &&
    amountNum.value > 0 &&
    form.value.refundTerms.trim(),
)
function openForm() {
  form.value = {
    customerId: '', title: '', type: 'COURSE', totalAmount: '',
    coolingDays: '7', penaltyRate: '20', refundTerms: '', remarks: '',
  }
  showForm.value = true
}
function submitForm() {
  if (!canSubmit.value) return
  const c = customer.get(form.value.customerId)
  if (!c) return
  const ct = contract.saveDraft({
    customerId: c.id,
    customerName: c.name,
    title: form.value.title.trim(),
    type: form.value.type,
    totalAmount: amountNum.value,
    coolingDays: Number(form.value.coolingDays) || 7,
    penaltyRate: (Number(form.value.penaltyRate) || 20) / 100,
    refundTerms: form.value.refundTerms.trim(),
    remarks: form.value.remarks.trim() || undefined,
  })
  if (ct) {
    showForm.value = false
    selectedId.value = ct.id
    tab.value = 'draft'
  }
}
</script>

<template>
  <div class="ct">
    <div class="ct__head">
      <div v-for="k in kpis" :key="k.label" class="kpi-item">
        <div class="kpi-item__icon" :class="`kpi-item__icon--${k.tone}`">
          <CIcon :name="k.icon" :size="20" />
        </div>
        <div class="kpi-item__body">
          <div class="kpi-item__label">{{ k.label }}</div>
          <div class="kpi-item__value">{{ k.value }}</div>
        </div>
      </div>
    </div>

    <div class="ct__body">
      <!-- 左列 -->
      <CCard class="ct__list" padding="none">
        <div class="tabs">
          <button
            v-for="t in tabs" :key="t.k"
            class="tab" :class="{ 'tab--active': tab === t.k }"
            @click="selectTab(t.k)"
          >{{ t.label }}</button>
        </div>
        <div class="list">
          <div v-if="list.length === 0" class="empty">
            <CIcon name="profile" :size="28" class="empty__icon" />
            <div>暂无合同</div>
          </div>
          <button
            v-for="c in list" :key="c.id"
            class="rec" :class="{ 'rec--active': selected?.id === c.id }"
            @click="selectedId = c.id"
          >
            <div class="rec__top">
              <span class="rec__type">{{ CONTRACT_TYPE_LABEL[c.type] }}</span>
              <CStatusPill :status="dictPill(CONTRACT_STATUS[c.status as keyof typeof CONTRACT_STATUS]).status">{{ dictPill(CONTRACT_STATUS[c.status as keyof typeof CONTRACT_STATUS]).text }}</CStatusPill>
            </div>
            <div class="rec__title">{{ c.title }}</div>
            <div class="rec__meta">
              <span>{{ c.customerName }}</span>
              <span class="rec__amt">{{ fmtMoney(c.totalAmount) }}</span>
            </div>
          </button>
          <CFab
            :actions="[{ icon: 'plus', label: '新建合同', disabled: !auth.can('contract:edit'), onClick: openForm }]"
          />
        </div>
      </CCard>

      <!-- 右列详情 -->
      <CCard v-if="selected" class="ct__detail">
        <template #header>
          <div class="ct__detail-head">
            <div>
              <h3 class="ct__detail-title">{{ selected.title }}</h3>
              <div class="ct__detail-sub">{{ selected.contractNo }} · {{ CONTRACT_TYPE_LABEL[selected.type] }}</div>
            </div>
            <CStatusPill :status="dictPill(CONTRACT_STATUS[selected.status as keyof typeof CONTRACT_STATUS]).status">{{ dictPill(CONTRACT_STATUS[selected.status as keyof typeof CONTRACT_STATUS]).text }}</CStatusPill>
          </div>
        </template>

        <!-- 客户信息 -->
        <div class="cust">
          <div class="cust__avatar">{{ selected.customerName.charAt(0) }}</div>
          <div>
            <div class="cust__name">{{ selected.customerName }}</div>
            <div class="cust__sub">签约日期 {{ fmtDate(selected.signDate) }} · {{ selected.signedByName }}</div>
          </div>
        </div>

        <!-- 合同金额 -->
        <div class="amount-bar">
          <span class="amount-bar__label">合同金额</span>
          <span class="amount-bar__value">{{ fmtMoney(selected.totalAmount) }}</span>
        </div>

        <!-- 字段网格 -->
        <div class="grid">
          <div class="field"><span class="field__label">合同编号</span><span class="field__val">{{ selected.contractNo }}</span></div>
          <div class="field"><span class="field__label">合同类型</span><span class="field__val">{{ CONTRACT_TYPE_LABEL[selected.type] }}</span></div>
          <div class="field"><span class="field__label">签约日期</span><span class="field__val">{{ fmtDate(selected.signDate) }}</span></div>
          <div class="field"><span class="field__label">签署人</span><span class="field__val">{{ selected.signedByName }}</span></div>
        </div>

        <!-- 关联订单 -->
        <div v-if="selected.orders.length > 0" class="section">
          <div class="section__title">关联订单</div>
          <div class="orders">
            <div v-for="o in selected.orders" :key="o.orderNo" class="order-row">
              <span class="order-row__no">{{ o.orderNo }}</span>
              <span class="order-row__item">{{ o.itemName }}</span>
              <span class="order-row__amt">{{ fmtMoney(o.amount) }}</span>
            </div>
          </div>
        </div>

        <!-- 退款条款 -->
        <div class="section">
          <div class="section__title">退款条款</div>
          <div class="terms">
            <div class="terms__badges">
              <span class="badge" :class="{ 'badge--active': inCooling }">
                <CIcon name="shield" :size="14" />
                冷静期 {{ selected.coolingDays }} 天{{ inCooling ? '（期内）' : '' }}
              </span>
              <span class="badge badge--warn">违约金 {{ Math.round(selected.penaltyRate * 100) }}%</span>
            </div>
            <p class="terms__text">{{ selected.refundTerms }}</p>
          </div>

          <!-- 退款估算器 -->
          <div v-if="selected.status === 'EFFECTIVE'" class="estimator">
            <div class="estimator__title">退款金额估算</div>
            <div class="estimator__row">
              <CInput v-model="refundPaidInput" type="number" placeholder="实付金额" />
              <span class="estimator__hint">输入已付金额试算</span>
            </div>
            <div v-if="estimate" class="estimator__result">
              <template v-if="estimate.inCooling">
                <div class="estimator__line"><span>冷静期内无责退款</span><span class="estimator__refund">可退 {{ fmtMoney(estimate.refund) }}</span></div>
              </template>
              <template v-else>
                <div class="estimator__line"><span>实付金额</span><span>{{ fmtMoney(refundPaidNum) }}</span></div>
                <div class="estimator__line"><span>违约金（{{ Math.round(selected.penaltyRate * 100) }}%）</span><span class="estimator__penalty">-{{ fmtMoney(estimate.penalty) }}</span></div>
                <div class="estimator__line estimator__line--total"><span>应退金额</span><span class="estimator__refund">{{ fmtMoney(estimate.refund) }}</span></div>
              </template>
            </div>
          </div>
        </div>

        <!-- 终止原因 -->
        <div v-if="selected.terminateReason" class="reject-note">
          <strong>终止原因：</strong>{{ selected.terminateReason }}
        </div>

        <!-- 操作区 -->
        <div class="ops">
          <template v-if="selected.status === 'DRAFT'">
            <CButton variant="primary" v-perm.disable="'contract:edit'" @click="doActivate">
              <CIcon name="check" :size="16" />合同生效
            </CButton>
          </template>
          <template v-else-if="selected.status === 'EFFECTIVE'">
            <CButton variant="ghost" v-perm.disable="'contract:edit'" @click="showTerminate = true">终止合同</CButton>
            <CButton variant="primary" v-perm.disable="'contract:edit'" @click="doComplete">
              <CIcon name="check-square" :size="16" />履行完成
            </CButton>
          </template>
          <div v-else-if="selected.status === 'COMPLETED'" class="ops__done">
            <CIcon name="check-square" :size="16" />合同已于 {{ fmtDateTime(selected.completedAt) }} 履行完成
          </div>
          <div v-else-if="selected.status === 'TERMINATED'" class="ops__done ops__done--danger">
            <CIcon name="alert" :size="16" />合同已于 {{ fmtDateTime(selected.terminatedAt) }} 终止
          </div>
        </div>

        <!-- 终止输入 -->
        <div v-if="showTerminate" class="reject-box">
          <CInput v-model="terminateReason" placeholder="请输入终止原因（必填）" />
          <div class="reject-box__btns">
            <CButton variant="ghost" @click="showTerminate = false; terminateReason = ''">取消</CButton>
            <CButton variant="primary" :disabled="!terminateReason.trim()" @click="doTerminate">确认终止</CButton>
          </div>
        </div>
      </CCard>

      <CCard v-else class="ct__detail ct__detail--empty" title="合同详情">
        <div class="detail-empty">
          <CIcon name="profile" :size="40" class="detail-empty__icon" />
          <p>请从左侧选择一份合同</p>
        </div>
      </CCard>
    </div>

    <!-- 新建合同弹层 -->
    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <CCard class="modal" title="新建合同" padding="lg">
        <div class="form">
          <div class="form__row">
            <label class="form__label">客户</label>
            <CSelect v-model="form.customerId" :options="customerOptions" placeholder="选择签约客户" />
          </div>
          <div class="form__row">
            <label class="form__label">合同标题</label>
            <CInput v-model="form.title" placeholder="如：光子嫩肤 6 次疗程合同" />
          </div>
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">合同类型</label>
              <CSelect v-model="form.type" :options="typeOptions" />
            </div>
            <div>
              <label class="form__label">合同金额</label>
              <CInput v-model="form.totalAmount" type="number" placeholder="0" />
            </div>
          </div>
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">冷静期（天）</label>
              <CInput v-model="form.coolingDays" type="number" placeholder="7" />
            </div>
            <div>
              <label class="form__label">违约金比例（%）</label>
              <CInput v-model="form.penaltyRate" type="number" placeholder="20" />
            </div>
          </div>
          <div class="form__row">
            <label class="form__label">退款条款</label>
            <CTextarea v-model="form.refundTerms" placeholder="请填写退款/终止条款说明" />
          </div>
          <div class="form__row">
            <label class="form__label">备注</label>
            <CTextarea v-model="form.remarks" placeholder="可选" />
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showForm = false">取消</CButton>
          <CButton variant="primary" :disabled="!canSubmit" @click="submitForm">保存为草稿</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.ct { display: flex; flex-direction: column; gap: var(--s-lg); }
.ct__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .ct__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
.kpi-item {
  display: flex; align-items: center; gap: var(--s-md);
  background: var(--c-surface); border: 1px solid var(--c-border-light);
  border-radius: var(--r-xl); padding: var(--s-md);
}
.kpi-item__icon {
  width: 40px; height: 40px; border-radius: var(--r-lg);
  background: var(--c-brand-soft); color: var(--c-brand);
  display: flex; align-items: center; justify-content: center; flex-shrink: 0;
}
.kpi-item__icon--success { background: var(--c-success-bg, #f0fbf0); color: var(--c-success-fg); }
.kpi-item__icon--warning { background: var(--c-warning-bg, #fff7e6); color: var(--c-warning-fg); }
.kpi-item__icon--danger { background: var(--c-danger-bg); color: var(--c-danger-fg); }
.kpi-item__body { min-width: 0; flex: 1; }
.kpi-item__label { font-size: var(--t-xs); color: var(--c-text-3); }
.kpi-item__value { font-size: var(--t-lg); font-weight: 700; font-variant-numeric: tabular-nums; }

.ct__body { display: grid; grid-template-columns: 380px 1fr; gap: var(--s-lg); align-items: start; }

.ct__detail-head { display: flex; justify-content: space-between; align-items: flex-start; gap: var(--s-md); width: 100%; }
.ct__detail-title { font-size: var(--t-md); line-height: var(--lh-md); font-weight: 700; color: var(--c-text); margin: 0; }
.ct__detail-sub { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }

.tabs { display: flex; align-items: stretch; border-bottom: 1px solid var(--c-border); overflow-x: auto; padding: 0 var(--s-sm); gap: var(--s-xs); }
.ct__list { position: relative; display: flex; flex-direction: column; }
.tab {
  flex: 0 0 auto; padding: var(--s-md) var(--s-xs); font-size: var(--t-xs);
  color: var(--c-text-3); background: none; border: none; cursor: pointer;
  border-bottom: 2px solid transparent; white-space: nowrap;
}
.tab--active { color: var(--c-brand); border-bottom-color: var(--c-brand); font-weight: 600; }

.list { max-height: 560px; overflow-y: auto; display: flex; flex-direction: column; }
.empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); font-size: var(--t-sm); }
.empty__icon { color: var(--c-text-4); }

.rec {
  display: block; width: 100%; text-align: left; padding: var(--s-md) var(--s-lg);
  background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer;
}
.rec:hover { background: var(--c-brand-soft); }
.rec--active { background: var(--c-brand-soft); }
.rec__top { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--s-xs); }
.rec__type { font-size: var(--t-xs); color: var(--c-brand); background: var(--c-brand-soft); padding: 2px var(--s-sm); border-radius: var(--r-sm); font-weight: 500; }
.rec__title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); margin-bottom: var(--s-xs); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.rec__meta { display: flex; justify-content: space-between; font-size: var(--t-xs); color: var(--c-text-3); }
.rec__amt { font-weight: 600; color: var(--c-text); }

.cust { display: flex; align-items: center; gap: var(--s-md); padding: var(--s-md) 0; border-bottom: 1px solid var(--c-border-light); }
.cust__avatar {
  width: 44px; height: 44px; border-radius: var(--r-capsule);
  background: var(--c-brand-soft); color: var(--c-brand);
  display: flex; align-items: center; justify-content: center;
  font-size: var(--t-lg); font-weight: 700;
}
.cust__name { font-size: var(--t-lg); font-weight: 700; color: var(--c-text); }
.cust__sub { font-size: var(--t-sm); color: var(--c-text-3); margin-top: 2px; }

.amount-bar { display: flex; justify-content: space-between; align-items: center; padding: var(--s-md); background: var(--c-brand-soft); border-radius: var(--r-md); margin: var(--s-md) 0; }
.amount-bar__label { font-size: var(--t-sm); color: var(--c-text-2); }
.amount-bar__value { font-size: var(--t-xl); font-weight: 700; color: var(--c-brand); }

.grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md) var(--s-lg); margin-bottom: var(--s-lg); }
.field { display: flex; flex-direction: column; gap: 2px; }
.field__label { font-size: var(--t-xs); color: var(--c-text-3); }
.field__val { font-size: var(--t-sm); color: var(--c-text); }

.section { margin-bottom: var(--s-lg); }
.section__title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); margin-bottom: var(--s-sm); padding-bottom: var(--s-xs); border-bottom: 1px solid var(--c-border-light); }

.orders { display: flex; flex-direction: column; gap: var(--s-xs); }
.order-row { display: flex; align-items: center; gap: var(--s-md); padding: var(--s-xs) var(--s-md); background: var(--c-bg-page); border-radius: var(--r-sm); font-size: var(--t-sm); }
.order-row__no { font-weight: 600; color: var(--c-text); min-width: 140px; }
.order-row__item { flex: 1; color: var(--c-text-2); }
.order-row__amt { font-weight: 600; color: var(--c-text); }

.terms { background: var(--c-bg-page); border-radius: var(--r-md); padding: var(--s-md); }
.terms__badges { display: flex; gap: var(--s-sm); margin-bottom: var(--s-sm); flex-wrap: wrap; }
.badge { display: inline-flex; align-items: center; gap: 4px; font-size: var(--t-xs); padding: var(--s-xs) var(--s-sm); border-radius: var(--r-capsule); background: var(--c-surface); border: 1px solid var(--c-border); color: var(--c-text-2); }
.badge--active { background: var(--c-success-bg, #f0fbf0); color: var(--c-success-fg); border-color: var(--c-success-fg); }
.badge--warn { background: var(--c-warning-bg, #fff7e6); color: var(--c-warning-fg); border-color: var(--c-warning-fg); }
.terms__text { font-size: var(--t-sm); color: var(--c-text-2); line-height: 1.6; margin: 0; }

.estimator { margin-top: var(--s-md); padding: var(--s-md); border: 1px solid var(--c-border); border-radius: var(--r-md); }
.estimator__title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); margin-bottom: var(--s-sm); }
.estimator__row { display: flex; align-items: center; gap: var(--s-md); }
.estimator__row :deep(.cinput) { max-width: 180px; }
.estimator__hint { font-size: var(--t-xs); color: var(--c-text-3); }
.estimator__result { margin-top: var(--s-md); display: flex; flex-direction: column; gap: var(--s-xs); }
.estimator__line { display: flex; justify-content: space-between; font-size: var(--t-sm); color: var(--c-text-2); }
.estimator__line--total { padding-top: var(--s-xs); border-top: 1px dashed var(--c-border); font-weight: 600; color: var(--c-text); }
.estimator__refund { color: var(--c-success-fg); font-weight: 700; font-size: var(--t-md); }
.estimator__penalty { color: var(--c-danger-fg); }

.reject-note { margin-top: var(--s-md); padding: var(--s-sm) var(--s-md); background: var(--c-danger-bg); color: var(--c-danger-fg); border-radius: var(--r-md); font-size: var(--t-sm); line-height: 1.6; }

.ops { display: flex; justify-content: flex-end; gap: var(--s-sm); margin-top: var(--s-lg); padding-top: var(--s-lg); border-top: 1px solid var(--c-border-light); }
.ops__done { display: flex; align-items: center; gap: var(--s-sm); font-size: var(--t-sm); color: var(--c-success-fg); font-weight: 600; margin-left: auto; }
.ops__done--danger { color: var(--c-danger-fg); }

.reject-box { margin-top: var(--s-md); display: flex; flex-direction: column; gap: var(--s-sm); }
.reject-box__btns { display: flex; justify-content: flex-end; gap: var(--s-sm); }

.detail-empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-md); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); }
.detail-empty__icon { color: var(--c-text-4); }

.modal-mask { position: fixed; inset: 0; background: rgba(20,21,43,.45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 560px; max-width: 100%; max-height: 90vh; overflow-y: auto; box-shadow: var(--shadow-pop); }
.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.form__row--2 { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.form__label { font-size: var(--t-xs); color: var(--c-text-3); }

@media (max-width: 1024px) {
  .ct__body { grid-template-columns: 1fr; }
  .list { max-height: 320px; }
}
</style>
