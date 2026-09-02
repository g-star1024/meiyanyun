<script setup lang="ts">
/* ============================================================
 * 资产转移 /asset-transfer（Desktop 优先 · 平板堆叠）
 * 客户间卡余额 / 疗程次数转移，双签状态机，层级取自设置中心。
 * 财务执行时调用 asset.applyTransfer 真实扣减/增加资产。
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
import { useTransferStore, type Transfer } from '@/stores/transfer'
import { TRANSFER_STATUS, TRANSFER_ASSET_TYPE, dictPill, type TransferAssetType } from '@/config/dictionary'
import { useAssetStore } from '@/stores/asset'
import { useCustomerStore } from '@/stores/customer'
import { useSettingsStore } from '@/stores/settings'
import { useAuthStore } from '@/stores/auth'

const transfer = useTransferStore()
const asset = useAssetStore()
const customer = useCustomerStore()
const settings = useSettingsStore()
const auth = useAuthStore()

onMounted(() => {
  asset.seed()
  transfer.seed()
})

type Tab = 'pending_review' | 'pending_finance' | 'transferred' | 'rejected'
const tab = ref<Tab>('pending_review')
const selectedId = ref<string | null>(transfer.pendingReview[0]?.id ?? null)

const tabs = computed(() => [
  { k: 'pending_review' as Tab, label: `待审批 (${transfer.pendingReview.length})` },
  { k: 'pending_finance' as Tab, label: `待执行 (${transfer.pendingFinance.length})` },
  { k: 'transferred' as Tab, label: `已转移 (${transfer.transferred.length})` },
  { k: 'rejected' as Tab, label: `已驳回 (${transfer.rejected.length})` },
])

const list = computed<Transfer[]>(() => {
  if (tab.value === 'pending_review') return transfer.pendingReview
  if (tab.value === 'pending_finance') return transfer.pendingFinance
  if (tab.value === 'transferred') return transfer.transferred
  return transfer.rejected
})

const selected = computed(() => {
  if (selectedId.value) return transfer.get(selectedId.value) ?? null
  return list.value[0] ?? null
})

// 切换 tab / 状态变化时，若当前选中项已不在列表，回退到列表首项
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

const transferredAmount = computed(() =>
  transfer.transferred.reduce((s, t) => s + (t.amount || 0), 0),
)

const kpis = computed(() => [
  { label: '待审批', value: transfer.pendingReview.length, tone: 'warning' as const, icon: 'alert' as const },
  { label: '待财务执行', value: transfer.pendingFinance.length, tone: 'warning' as const, icon: 'sign' as const },
  { label: '本月已转移', value: transfer.transferred.length, tone: 'default' as const, icon: 'check-square' as const },
  { label: '转移金额合计', value: '¥' + transferredAmount.value.toLocaleString('zh-CN'), tone: 'brand' as const, icon: 'finance' as const },
])


function fmtMoney(n?: number) { return n != null ? '¥' + n.toLocaleString('zh-CN') : '—' }
function fmtTime(iso?: string) {
  if (!iso) return '—'
  const d = new Date(iso)
  return `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}
function transferText(t: Transfer) {
  return t.assetType === 'CASH' ? fmtMoney(t.amount) : `${t.itemName} × ${t.times} 次`
}

// 审批 / 驳回 / 执行
const rejectReason = ref('')
const showReject = ref(false)

function doApprove() {
  if (selected.value) {
    const id = selected.value.id
    transfer.approve(id)
    selectedId.value = id
    tab.value = 'pending_finance'
  }
}
function doReject() {
  if (selected.value && rejectReason.value.trim()) {
    const id = selected.value.id
    transfer.reject(id, rejectReason.value.trim())
    showReject.value = false
    rejectReason.value = ''
    selectedId.value = id
    tab.value = 'rejected'
  }
}
function doExecute() {
  if (selected.value) {
    const id = selected.value.id
    transfer.execute(id)
    selectedId.value = id
    tab.value = 'transferred'
  }
}

// 发起转移表单
const showForm = ref(false)
const form = ref({
  fromCustomerId: '',
  toCustomerId: '',
  assetType: 'CASH' as TransferAssetType,
  itemSku: '',
  amount: '',
  times: '',
  reason: '',
})

const customerOptions = computed(() =>
  customer.customers
    .filter((c) => !c.masterId)
    .map((c) => ({ value: c.id, label: `${c.name}（${c.phoneMask}）` })),
)

// 转出方资产账户
const fromAccount = computed(() =>
  form.value.fromCustomerId ? asset.account(form.value.fromCustomerId) : null,
)
// 可选疗程（转出方 ACTIVE 次数型资产）
const fromTimesOptions = computed(() =>
  fromAccount.value
    ? fromAccount.value.timesAssets
        .filter((a) => a.remainingTimes > 0)
        .map((a) => ({ value: a.itemSku, label: `${a.itemName}（剩余 ${a.remainingTimes}/${a.totalTimes} 次）` }))
    : [],
)
// 选中疗程资产
const selectedTimesAsset = computed(() =>
  fromAccount.value?.timesAssets.find((a) => a.itemSku === form.value.itemSku) ?? null,
)

const amountNum = computed(() => Number(form.value.amount) || 0)
const timesNum = computed(() => Number(form.value.times) || 0)

// 签署层级：金额型按金额，次数型按次数×单价近似（这里用 1000/次估值，与 store 一致）
const formTier = computed(() =>
  settings.tierFor(form.value.assetType === 'CASH' ? amountNum.value : timesNum.value * 1000),
)

// 余额/次数校验
const balanceOk = computed(() => {
  if (form.value.assetType === 'CASH') {
    return !!fromAccount.value && fromAccount.value.totalBalance >= amountNum.value && amountNum.value > 0
  }
  return !!selectedTimesAsset.value && selectedTimesAsset.value.remainingTimes >= timesNum.value && timesNum.value > 0
})
const sameCustomer = computed(() =>
  !!form.value.fromCustomerId && form.value.fromCustomerId === form.value.toCustomerId,
)

const canSubmit = computed(
  () =>
    form.value.fromCustomerId &&
    form.value.toCustomerId &&
    !sameCustomer.value &&
    form.value.reason.trim() &&
    balanceOk.value &&
    (form.value.assetType === 'CASH' || !!form.value.itemSku),
)

function openForm() {
  form.value = {
    fromCustomerId: '', toCustomerId: '', assetType: 'CASH',
    itemSku: '', amount: '', times: '', reason: '',
  }
  showForm.value = true
}

function submitForm() {
  if (!canSubmit.value) return
  const fromCust = customer.get(form.value.fromCustomerId)
  const toCust = customer.get(form.value.toCustomerId)
  if (!fromCust || !toCust) return
  const t = transfer.create({
    fromCustomerId: fromCust.id,
    fromCustomerName: fromCust.name,
    toCustomerId: toCust.id,
    toCustomerName: toCust.name,
    assetType: form.value.assetType,
    amount: form.value.assetType === 'CASH' ? amountNum.value : undefined,
    times: form.value.assetType === 'TIMES' ? timesNum.value : undefined,
    itemSku: form.value.assetType === 'TIMES' ? form.value.itemSku : undefined,
    itemName: form.value.assetType === 'TIMES' ? selectedTimesAsset.value?.itemName : undefined,
    reason: form.value.reason.trim(),
  })
  if (t) {
    showForm.value = false
    selectedId.value = t.id
    tab.value = t.status === 'PENDING_REVIEW' ? 'pending_review' : 'pending_finance'
  }
}
</script>

<template>
  <div class="tr">
    <div class="tr__head">
      <div v-for="k in kpis" :key="k.label" class="kpi-item">
        <div class="kpi-item__icon" :class="`kpi-item__icon--${k.tone}`"><CIcon :name="k.icon" :size="20" /></div>
        <div class="kpi-item__body">
          <div class="kpi-item__label">{{ k.label }}</div>
          <div class="kpi-item__value">{{ k.value }}</div>
        </div>
      </div>
    </div>

    <div class="tr__body">
      <!-- 左列 -->
      <CCard class="tr__list" padding="none">
        <div class="tabs">
          <button
            v-for="t in tabs" :key="t.k"
            class="tab" :class="{ 'tab--active': tab === t.k }"
            @click="selectTab(t.k)"
          >{{ t.label }}</button>
        </div>
        <div class="list">
          <div v-if="list.length === 0" class="empty">
            <CIcon name="handover" :size="28" class="empty__icon" />
            <div>暂无记录</div>
          </div>
          <button
            v-for="r in list" :key="r.id"
            class="rec" :class="{ 'rec--active': selected?.id === r.id }"
            @click="selectedId = r.id"
          >
            <div class="rec__top">
              <span class="rec__no">{{ r.transferNo }}</span>
              <CStatusPill :status="dictPill(TRANSFER_STATUS[r.status]).status">{{ dictPill(TRANSFER_STATUS[r.status]).text }}</CStatusPill>
            </div>
            <div class="rec__flow">
              <span class="rec__person">{{ r.fromCustomerName }}</span>
              <CIcon name="chevron-right" :size="14" class="rec__arrow" />
              <span class="rec__person">{{ r.toCustomerName }}</span>
            </div>
            <div class="rec__meta">
              <span class="rec__amt">{{ transferText(r) }}</span>
              <span>{{ r.signTier }} · {{ fmtTime(r.createdAt) }}</span>
            </div>
          </button>
          <CFab
            :actions="[{ icon: 'plus', label: '发起资产转移', disabled: !auth.can('transfer:create'), onClick: openForm }]"
          />
        </div>
      </CCard>

      <!-- 右列详情 -->
      <CCard v-if="selected" class="tr__detail" :title="selected.transferNo">
        <template #header>
          <h3 class="tr__detail-title">{{ selected.transferNo }}</h3>
          <CStatusPill :status="dictPill(TRANSFER_STATUS[selected.status]).status">{{ dictPill(TRANSFER_STATUS[selected.status]).text }}</CStatusPill>
        </template>

        <div class="flow">
          <div class="flow__party">
            <div class="flow__avatar">{{ selected.fromCustomerName.charAt(0) }}</div>
            <div>
              <div class="flow__name">{{ selected.fromCustomerName }}</div>
              <div class="flow__role">转出方</div>
            </div>
          </div>
          <CIcon name="chevron-right" :size="20" class="flow__arrow" />
          <div class="flow__party">
            <div class="flow__avatar flow__avatar--to">{{ selected.toCustomerName.charAt(0) }}</div>
            <div>
              <div class="flow__name">{{ selected.toCustomerName }}</div>
              <div class="flow__role">转入方</div>
            </div>
          </div>
        </div>

        <div class="grid">
          <div class="field"><span class="field__label">资产类型</span><span class="field__val">{{ TRANSFER_ASSET_TYPE[selected.assetType]?.label }}</span></div>
          <div class="field"><span class="field__label">转移内容</span><span class="field__val field__val--amt">{{ transferText(selected) }}</span></div>
          <div class="field"><span class="field__label">申请人</span><span class="field__val">{{ selected.applicantName }}</span></div>
          <div class="field"><span class="field__label">签署层级</span><span class="field__val">{{ selected.signTier }}（阈值来自设置中心）</span></div>
          <div class="field field--full"><span class="field__label">转移原因</span><span class="field__val">{{ selected.reason }}</span></div>
          <div class="field"><span class="field__label">发起时间</span><span class="field__val">{{ fmtTime(selected.createdAt) }}</span></div>
        </div>

        <!-- 审批轨迹 -->
        <div class="trace">
          <div class="trace__step trace__step--done">
            <CIcon name="check" :size="14" /><span>发起申请 · {{ selected.applicantName }}</span>
          </div>
          <div class="trace__step" :class="{ 'trace__step--done': !!selected.reviewedByName, 'trace__step--active': selected.status === 'PENDING_REVIEW' }">
            <CIcon :name="selected.reviewedByName ? 'check' : 'sign'" :size="14" />
            <span>店长/区域审批{{ selected.reviewedByName ? ' · ' + selected.reviewedByName : '' }}</span>
          </div>
          <div class="trace__step" :class="{ 'trace__step--done': !!selected.transferredAt, 'trace__step--active': selected.status === 'PENDING_FINANCE' }">
            <CIcon :name="selected.transferredAt ? 'check' : 'finance'" :size="14" />
            <span>财务执行转移{{ selected.financeByName ? ' · ' + selected.financeByName : '' }}</span>
          </div>
        </div>

        <div v-if="selected.rejectionReason" class="reject-note">
          <strong>驳回原因（{{ selected.rejectionByName }}）：</strong>{{ selected.rejectionReason }}
        </div>

        <!-- 操作区 -->
        <div class="ops">
          <template v-if="selected.status === 'PENDING_REVIEW'">
            <CButton variant="ghost" v-perm.disable="'transfer:approve'" @click="showReject = true">驳回</CButton>
            <CButton variant="primary" v-perm.disable="'transfer:approve'" @click="doApprove">
              <CIcon name="check" :size="16" />审批通过
            </CButton>
          </template>
          <template v-else-if="selected.status === 'PENDING_FINANCE'">
            <CButton variant="ghost" v-perm.disable="'transfer:approve'" @click="showReject = true">驳回</CButton>
            <CButton variant="primary" v-perm.disable="'transfer:edit'" @click="doExecute">
              <CIcon name="handover" :size="16" />执行资产转移
            </CButton>
          </template>
          <div v-else-if="selected.status === 'TRANSFERRED'" class="ops__done">
            <CIcon name="check-square" :size="16" />资产已于 {{ fmtTime(selected.transferredAt) }} 完成转移
          </div>
        </div>

        <!-- 驳回输入 -->
        <div v-if="showReject" class="reject-box">
          <CInput v-model="rejectReason" placeholder="请输入驳回原因（必填）" />
          <div class="reject-box__btns">
            <CButton variant="ghost" @click="showReject = false; rejectReason = ''">取消</CButton>
            <CButton variant="primary" :disabled="!rejectReason.trim()" @click="doReject">确认驳回</CButton>
          </div>
        </div>
      </CCard>

      <CCard v-else class="tr__detail tr__detail--empty" title="转移详情">
        <div class="detail-empty">
          <CIcon name="handover" :size="40" class="detail-empty__icon" />
          <p>请从左侧选择一笔转移单</p>
        </div>
      </CCard>
    </div>

    <!-- 发起转移弹层 -->
    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <CCard class="modal" title="发起资产转移" padding="lg">
        <div class="form">
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">转出客户</label>
              <CSelect v-model="form.fromCustomerId" :options="customerOptions" placeholder="选择转出客户" />
            </div>
            <div>
              <label class="form__label">转入客户</label>
              <CSelect v-model="form.toCustomerId" :options="customerOptions" placeholder="选择转入客户" />
            </div>
          </div>
          <div v-if="sameCustomer" class="form__warn">转出与转入客户不能相同</div>

          <div class="form__row">
            <label class="form__label">资产类型</label>
            <div class="form__seg">
              <button :class="{ 'form__seg--on': form.assetType === 'CASH' }" @click="form.assetType = 'CASH'; form.itemSku = ''">储值余额</button>
              <button :class="{ 'form__seg--on': form.assetType === 'TIMES' }" @click="form.assetType = 'TIMES'; form.amount = ''">疗程次数</button>
            </div>
          </div>

          <!-- 转出方可用资产展示 -->
          <div v-if="fromAccount" class="avail">
            <template v-if="form.assetType === 'CASH'">
              <div class="avail__item">
                <span>可用储值余额</span>
                <strong :class="{ 'avail__val--low': amountNum > fromAccount.totalBalance }">
                  ¥{{ fromAccount.totalBalance.toLocaleString('zh-CN') }}
                </strong>
              </div>
              <div class="form__row">
                <label class="form__label">转移金额</label>
                <CInput v-model="form.amount" type="number" placeholder="请输入转移金额" />
              </div>
            </template>
            <template v-else>
              <div v-if="fromTimesOptions.length === 0" class="form__warn">该客户无可用疗程次数</div>
              <template v-else>
                <div class="form__row">
                  <label class="form__label">选择疗程</label>
                  <CSelect v-model="form.itemSku" :options="fromTimesOptions" placeholder="选择要转移的疗程" />
                </div>
                <div v-if="selectedTimesAsset" class="avail__item">
                  <span>可转移次数</span>
                  <strong :class="{ 'avail__val--low': timesNum > selectedTimesAsset.remainingTimes }">
                    {{ selectedTimesAsset.remainingTimes }} 次
                  </strong>
                </div>
                <div class="form__row">
                  <label class="form__label">转移次数</label>
                  <CInput v-model="form.times" type="number" placeholder="请输入转移次数" />
                </div>
              </template>
            </template>
          </div>

          <div class="form__row">
            <label class="form__label">转移原因</label>
            <CTextarea v-model="form.reason" placeholder="请说明转移原因（客户关系、授权凭证等）" />
          </div>

          <div v-if="!balanceOk && (amountNum > 0 || timesNum > 0)" class="form__warn">
            {{ form.assetType === 'CASH' ? '转移金额不能超过可用余额' : '转移次数不能超过剩余次数' }}
          </div>

          <div class="form__tier">
            签署层级：<strong>{{ formTier }}</strong>
            <span class="form__tier-hint">{{ formTier === 'L1' ? '直达财务执行' : '需店长/区域审批后再由财务执行' }}</span>
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showForm = false">取消</CButton>
          <CButton variant="primary" :disabled="!canSubmit" @click="submitForm">提交申请</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.tr { display: flex; flex-direction: column; gap: var(--s-lg); }
.tr__head { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); }
@media (max-width: 1024px) { .tr__head { grid-template-columns: repeat(2, 1fr); } }
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

.tr__body { display: grid; grid-template-columns: 380px 1fr; gap: var(--s-lg); align-items: start; }

.tr__detail-title { font-size: var(--t-md); line-height: var(--lh-md); font-weight: 700; color: var(--c-text); margin: 0; }

.tabs { display: flex; align-items: stretch; border-bottom: 1px solid var(--c-border); overflow-x: auto; padding: 0 var(--s-sm); gap: var(--s-xs); }
.tr__list { position: relative; display: flex; flex-direction: column; }
.tab {
  flex: 0 0 auto; padding: var(--s-md) var(--s-xs); font-size: var(--t-xs);
  color: var(--c-text-3); background: none; border: none; cursor: pointer;
  border-bottom: 2px solid transparent; white-space: nowrap;
}
.tab--active { color: var(--c-brand); border-bottom-color: var(--c-brand); font-weight: 600; }

.list { max-height: 560px; overflow-y: auto; display: flex; flex-direction: column; }
.empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl) var(--s-lg) 64px; color: var(--c-text-3); font-size: var(--t-sm); }
.empty__icon { color: var(--c-text-4); }

.rec {
  display: block; width: 100%; text-align: left; padding: var(--s-md) var(--s-lg);
  background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer;
}
.rec:hover { background: var(--c-brand-soft); }
.rec--active { background: var(--c-brand-soft); }
.rec__top { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--s-xs); }
.rec__no { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.rec__flow { display: flex; align-items: center; gap: var(--s-xs); font-size: var(--t-sm); color: var(--c-text); margin-bottom: var(--s-xs); }
.rec__person { font-weight: 500; }
.rec__arrow { color: var(--c-text-4); }
.rec__meta { display: flex; justify-content: space-between; font-size: var(--t-xs); color: var(--c-text-3); }
.rec__amt { font-weight: 600; color: var(--c-brand); }

/* 转移流向 */
.flow { display: flex; align-items: center; gap: var(--s-md); padding: var(--s-md) 0; border-bottom: 1px solid var(--c-border-light); }
.flow__party { display: flex; align-items: center; gap: var(--s-sm); }
.flow__avatar {
  width: 40px; height: 40px; border-radius: var(--r-capsule);
  background: var(--c-brand-soft); color: var(--c-brand);
  display: flex; align-items: center; justify-content: center;
  font-size: var(--t-md); font-weight: 700;
}
.flow__avatar--to { background: var(--c-success-bg, #f0fbf0); color: var(--c-success-fg); }
.flow__name { font-size: var(--t-md); font-weight: 600; color: var(--c-text); }
.flow__role { font-size: var(--t-xs); color: var(--c-text-3); }
.flow__arrow { color: var(--c-text-4); }

.grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md) var(--s-lg); margin: var(--s-lg) 0; }
.field { display: flex; flex-direction: column; gap: 2px; }
.field--full { grid-column: 1 / -1; }
.field__label { font-size: var(--t-xs); color: var(--c-text-3); }
.field__val { font-size: var(--t-sm); color: var(--c-text); }
.field__val--amt { font-size: var(--t-lg); font-weight: 700; color: var(--c-brand); }

.trace { display: flex; flex-direction: column; gap: var(--s-sm); padding: var(--s-md); background: var(--c-bg-page); border-radius: var(--r-md); }
.trace__step { display: flex; align-items: center; gap: var(--s-sm); font-size: var(--t-sm); color: var(--c-text-3); }
.trace__step--done { color: var(--c-success-fg); }
.trace__step--active { color: var(--c-warning-fg); font-weight: 600; }

.reject-note { margin-top: var(--s-md); padding: var(--s-sm) var(--s-md); background: var(--c-danger-bg); color: var(--c-danger-fg); border-radius: var(--r-md); font-size: var(--t-sm); line-height: 1.6; }

.ops { display: flex; justify-content: flex-end; gap: var(--s-sm); margin-top: var(--s-lg); padding-top: var(--s-lg); border-top: 1px solid var(--c-border-light); }
.ops__done { display: flex; align-items: center; gap: var(--s-sm); font-size: var(--t-sm); color: var(--c-success-fg); font-weight: 600; margin-left: auto; }

.reject-box { margin-top: var(--s-md); display: flex; flex-direction: column; gap: var(--s-sm); }
.reject-box__btns { display: flex; justify-content: flex-end; gap: var(--s-sm); }

.detail-empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-md); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); }
.detail-empty__icon { color: var(--c-text-4); }

/* 弹层 */
.modal-mask { position: fixed; inset: 0; background: rgba(20,21,43,.45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 560px; max-width: 100%; max-height: 90vh; overflow-y: auto; box-shadow: var(--shadow-pop); }
.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.form__row--2 { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.form__label { font-size: var(--t-xs); color: var(--c-text-3); }
.form__seg { display: inline-flex; border: 1px solid var(--c-border); border-radius: var(--r-capsule); overflow: hidden; align-self: flex-start; }
.form__seg button { padding: var(--s-xs) var(--s-md); font-size: var(--t-sm); background: none; border: none; cursor: pointer; color: var(--c-text-2); }
.form__seg button.form__seg--on { background: var(--c-brand); color: #fff; }
.form__tier { font-size: var(--t-sm); color: var(--c-text-2); padding: var(--s-sm) var(--s-md); background: var(--c-brand-soft); border-radius: var(--r-md); }
.form__tier strong { color: var(--c-brand); margin: 0 var(--s-xs); }
.form__tier-hint { color: var(--c-text-3); font-size: var(--t-xs); }
.form__warn { font-size: var(--t-sm); color: var(--c-danger-fg); background: var(--c-danger-bg); padding: var(--s-xs) var(--s-md); border-radius: var(--r-md); }

.avail { display: flex; flex-direction: column; gap: var(--s-sm); }
.avail__item { display: flex; justify-content: space-between; align-items: center; padding: var(--s-sm) var(--s-md); background: var(--c-bg-page); border-radius: var(--r-md); font-size: var(--t-sm); color: var(--c-text-2); }
.avail__item strong { color: var(--c-brand); font-size: var(--t-md); }
.avail__val--low { color: var(--c-danger-fg) !important; }

@media (max-width: 1024px) {
  .tr__body { grid-template-columns: 1fr; }
  .tr__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .list { max-height: 320px; }
}
</style>
