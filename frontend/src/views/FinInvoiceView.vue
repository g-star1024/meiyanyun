<script setup lang="ts">
/* ============================================================
 * M6-04 发票管理 /m6-invoice
 * 4 KPI（本月开票额/待开笔数/红冲笔数/可开票金额）
 * 左：发票列表；右：发票详情 + 开具/红冲双签
 * 红线：发票仅作凭证登记，开票由外部开票系统完成，本页不触达资金
 * 适配已有 store: useFinInvoiceStore
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import { useFinInvoiceStore } from '@/stores/finInvoice'
import { useAuthStore } from '@/stores/auth'

const store = useFinInvoiceStore()
const auth = useAuthStore()
onMounted(() => store.seed())

const selectedId = ref<string | null>(null)
const selected = computed(() => {
  if (selectedId.value) return store.get(selectedId.value) ?? null
  return store.filtered[0] ?? null
})

const canEdit = computed(() => auth.can('finance:invoice:edit'))

const kpis = computed(() => [
  { label: '本月开票额', icon: 'finance', value: `¥${(store.monthIssuedAmount / 10000).toFixed(1)}万`, tone: 'brand' as const },
  { label: '待开笔数', icon: 'pos', value: String(store.drafts.length), tone: 'warning' as const },
  { label: '红冲/作废', icon: 'refund', value: String(store.voided.length), tone: 'danger' as const },
  { label: '可开票金额', icon: 'finance', value: `¥${(store.drafts.reduce((s, i) => s + i.amount, 0) / 10000).toFixed(1)}万`, tone: 'text' as const },
])

const statusOptions = [
  { value: 'ALL', label: '全部状态' },
  { value: 'DRAFT', label: '待开票' },
  { value: 'ISSUED', label: '已开票' },
  { value: 'VOIDED', label: '已作废' },
  { value: 'RED_FLUSHED', label: '已红冲' },
]
const typeOptions = [
  { value: 'ALL', label: '全部票种' },
  { value: 'NORMAL', label: '增值税普通发票' },
  { value: 'SPECIAL', label: '增值税专用发票' },
  { value: 'ELECTRONIC', label: '增值税电子普通发票' },
]
const rateOptions = store.RATES.map((r) => ({ value: String(r), label: r === 0 ? '免税 0%' : `${(r * 100).toFixed(0)}%` }))

function money(n: number) {
  return `¥${n.toLocaleString('zh-CN')}`
}
function fmtDate(iso: string) {
  return iso ? iso.slice(0, 10) : '—'
}

// 申请开票（创建草稿）弹层
const showCreate = ref(false)
const form = ref({
  type: 'ELECTRONIC' as 'NORMAL' | 'SPECIAL' | 'ELECTRONIC',
  category: 'SERVICE' as 'SERVICE' | 'PRODUCT' | 'MEMBERSHIP',
  title: '', taxNo: '', buyerName: '', orderRefs: '',
  amount: 0, taxRate: 0.06, store: '静安旗舰店',
})
const formTax = computed(() => {
  const r = form.value.taxRate
  return r > 0 ? Math.round(form.value.amount - form.value.amount / (1 + r)) : 0
})
function openCreate() {
  form.value = { type: 'ELECTRONIC', category: 'SERVICE', title: '', taxNo: '', buyerName: '', orderRefs: '', amount: 0, taxRate: 0.06, store: '静安旗舰店' }
  showCreate.value = true
}
function submitCreate() {
  if (!form.value.title || !form.value.amount) return
  store.create({
    type: form.value.type, category: form.value.category,
    title: form.value.title, taxNo: form.value.taxNo, buyerName: form.value.buyerName,
    orderRefs: form.value.orderRefs ? form.value.orderRefs.split(/[,，\s]+/).filter(Boolean) : [],
    amount: Math.round(form.value.amount), taxAmount: formTax.value, taxRate: form.value.taxRate,
    store: form.value.store,
  })
  showCreate.value = false
}

// 开具双签
const showIssue = ref(false)
const issueReviewer = ref('')
function openIssue() { issueReviewer.value = ''; showIssue.value = true }
function submitIssue() {
  if (!selected.value || issueReviewer.value.trim().length < 2) return
  store.markIssued(selected.value.id, issueReviewer.value.trim())
  showIssue.value = false
}
const canIssue = computed(() => issueReviewer.value.trim().length >= 2)

// 红冲双签
const showFlush = ref(false)
const flushForm = ref({ reviewer: '', reason: '' })
function openFlush() { flushForm.value = { reviewer: '', reason: '' }; showFlush.value = true }
function submitFlush() {
  if (!selected.value || flushForm.value.reviewer.trim().length < 2 || !flushForm.value.reason.trim()) return
  store.redFlush(selected.value.id, flushForm.value.reason.trim())
  showFlush.value = false
}
const canFlush = computed(() => flushForm.value.reviewer.trim().length >= 2 && flushForm.value.reason.trim().length > 0)

// 作废
const showVoid = ref(false)
const voidReason = ref('')
function openVoid() { voidReason.value = ''; showVoid.value = true }
function submitVoid() {
  if (!selected.value || !voidReason.value.trim()) return
  store.voidInvoice(selected.value.id, voidReason.value.trim())
  showVoid.value = false
}
</script>

<template>
  <div class="inv">
    <div class="inv__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <div class="inv__body">
      <!-- 左：发票列表 -->
      <CCard class="inv__list" padding="none">
        <div class="filters">
          <CSelect v-model="store.filterType" :options="typeOptions" />
          <CSelect v-model="store.filterStatus" width="120px" :options="statusOptions" />
          <CButton variant="secondary" size="sm" v-perm.disable="'finance:export'">
            <CIcon name="export" :size="14" />导出
          </CButton>
          <CButton v-if="canEdit" variant="primary" size="sm" @click="openCreate">
            <CIcon name="plus" :size="14" />申请开票
          </CButton>
        </div>
        <div class="list">
          <div v-if="store.filtered.length === 0" class="empty">
            <CIcon name="order" :size="28" class="empty__icon" />
            <div>暂无发票数据</div>
          </div>
          <button
            v-for="inv in store.filtered" :key="inv.id"
            class="row" :class="{ 'row--active': selected?.id === inv.id }"
            @click="selectedId = inv.id"
          >
            <div class="row__top">
              <span class="row__no">{{ inv.invoiceNo }}</span>
              <CStatusPill :status="store.STATUS_PILL[inv.status]" dot>{{ store.STATUS_LABEL[inv.status] }}</CStatusPill>
            </div>
            <div class="row__title">{{ inv.title }}</div>
            <div class="row__sub">{{ store.TYPE_LABEL[inv.type] }} · {{ inv.buyerName }}</div>
            <div class="row__bottom">
              <span class="row__amt">{{ money(inv.amount) }}</span>
              <span class="row__date">{{ fmtDate(inv.issuedAt) }}</span>
            </div>
          </button>
        </div>
      </CCard>

      <!-- 右：详情 -->
      <CCard v-if="selected" class="inv__detail" padding="none">
        <template #header>
          <div class="inv__detail-head">
            <div class="inv__who">
              <h3 class="inv__no">{{ selected.invoiceNo }}</h3>
              <div class="inv__sub">{{ selected.title }}</div>
            </div>
            <CStatusPill :status="store.STATUS_PILL[selected.status]" dot>{{ store.STATUS_LABEL[selected.status] }}</CStatusPill>
          </div>
        </template>

        <div class="detail-body">
          <div class="stat-grid">
            <div class="stat">
              <div class="stat__label">价税合计</div>
              <div class="stat__value stat__value--brand">{{ money(selected.amount) }}</div>
            </div>
            <div class="stat">
              <div class="stat__label">税额</div>
              <div class="stat__value">{{ money(selected.taxAmount) }}</div>
            </div>
            <div class="stat">
              <div class="stat__label">税率</div>
              <div class="stat__value">{{ selected.taxRate === 0 ? '免税' : (selected.taxRate * 100).toFixed(0) + '%' }}</div>
            </div>
            <div class="stat">
              <div class="stat__label">开票日期</div>
              <div class="stat__value">{{ fmtDate(selected.issuedAt) }}</div>
            </div>
          </div>

          <div class="kv">
            <div class="kv__row"><span class="kv__k">票种</span><span class="kv__v">{{ store.TYPE_LABEL[selected.type] }}</span></div>
            <div class="kv__row"><span class="kv__k">项目类别</span><span class="kv__v">{{ store.CATEGORY_LABEL[selected.category] }}</span></div>
            <div class="kv__row"><span class="kv__k">购方客户</span><span class="kv__v">{{ selected.buyerName }}</span></div>
            <div class="kv__row"><span class="kv__k">税号</span><span class="kv__v">{{ selected.taxNo || '—' }}</span></div>
            <div class="kv__row"><span class="kv__k">关联订单</span><span class="kv__v">{{ selected.orderRefs.join('、') || '—' }}</span></div>
            <div class="kv__row"><span class="kv__k">门店</span><span class="kv__v">{{ selected.store }}</span></div>
            <div class="kv__row"><span class="kv__k">开票人 / 复核</span><span class="kv__v">{{ selected.operator }}<template v-if="selected.reviewer"> / {{ selected.reviewer }}</template></span></div>
            <div v-if="selected.remark" class="kv__row"><span class="kv__k">备注</span><span class="kv__v kv__v--danger">{{ selected.remark }}</span></div>
          </div>

          <!-- 税额分税率统计 -->
          <div class="block">
            <div class="block__title"><span>税额分税率统计</span></div>
            <div class="taxrows">
              <div v-for="t in store.taxBreakdown" :key="t.rate" class="taxrow">
                <span class="taxrow__rate">{{ t.rate === 0 ? '免税' : (t.rate * 100).toFixed(0) + '%' }}</span>
                <span class="taxrow__amt">{{ money(t.amount) }}</span>
                <span class="taxrow__tax">税额 {{ money(t.tax) }}</span>
              </div>
              <div v-if="store.taxBreakdown.length === 0" class="taxrow taxrow--empty">暂无已开票数据</div>
            </div>
          </div>

          <div class="ops">
            <CButton v-if="selected.status === 'DRAFT'" variant="primary" size="sm" v-perm.disable="'finance:invoice:edit'" @click="openIssue">
              <CIcon name="check" :size="14" />开具发票（双签）
            </CButton>
            <template v-if="selected.status === 'ISSUED'">
              <CButton variant="secondary" size="sm" v-perm.disable="'finance:invoice:edit'" @click="openVoid">
                <CIcon name="close" :size="14" />作废
              </CButton>
              <CButton variant="danger" size="sm" v-perm.disable="'finance:invoice:approve'" @click="openFlush">
                <CIcon name="alert" :size="14" />红冲审批（双签）
              </CButton>
            </template>
            <span v-if="selected.status === 'VOIDED'" class="ops__hint ops__hint--danger">该发票已作废</span>
            <span v-if="selected.status === 'RED_FLUSHED'" class="ops__hint ops__hint--danger">该发票已红冲</span>
          </div>
        </div>
      </CCard>

      <CCard v-else class="inv__detail inv__detail--empty" title="发票详情" padding="lg">
        <div class="detail-empty">
          <CIcon name="order" :size="40" class="detail-empty__icon" />
          <p>请选择一张发票</p>
        </div>
      </CCard>
    </div>

    <!-- 申请开票弹层 -->
    <div v-if="showCreate" class="modal-mask" @click.self="showCreate = false">
      <CCard class="modal" title="申请开票（创建草稿）" padding="lg">
        <div class="form">
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">票种 <span class="req">*</span></label>
              <CSelect v-model="form.type" width="100%" :options="typeOptions.filter(o => o.value !== 'ALL').map(o => ({ value: o.value, label: o.label }))" />
            </div>
            <div>
              <label class="form__label">项目类别</label>
              <CSelect v-model="form.category" width="100%" :options="[
                { value: 'SERVICE', label: '医疗服务' },
                { value: 'PRODUCT', label: '产品销售' },
                { value: 'MEMBERSHIP', label: '会员卡/疗程' },
              ]" />
            </div>
          </div>
          <div class="form__row">
            <label class="form__label">发票抬头 <span class="req">*</span></label>
            <CInput v-model="form.title" placeholder="企业全称或个人姓名" />
          </div>
          <div class="form__row">
            <label class="form__label">税号</label>
            <CInput v-model="form.taxNo" placeholder="企业税号，个人可留空" />
          </div>
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">购方客户</label>
              <CInput v-model="form.buyerName" placeholder="客户姓名" />
            </div>
            <div>
              <label class="form__label">关联订单号</label>
              <CInput v-model="form.orderRefs" placeholder="如 ORD-20260820-01" />
            </div>
          </div>
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">价税合计（元）<span class="req">*</span></label>
              <CInput :model-value="String(form.amount)" @update:model-value="form.amount = Number($event) || 0" placeholder="0" />
            </div>
            <div>
              <label class="form__label">税率</label>
              <CSelect :model-value="String(form.taxRate)" @update:model-value="form.taxRate = Number($event)" width="100%" :options="rateOptions" />
            </div>
          </div>
          <div class="form__calc">预计税额：<b>{{ money(formTax) }}</b>　不含税：<b>{{ money(form.amount - formTax) }}</b></div>
          <p class="form__tip">提交后仅生成发票草稿，实际开具由外部开票系统回传状态，不涉及资金动账。</p>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showCreate = false">取消</CButton>
          <CButton variant="primary" @click="submitCreate">提交草稿</CButton>
        </template>
      </CCard>
    </div>

    <!-- 开具双签弹层 -->
    <div v-if="showIssue" class="modal-mask" @click.self="showIssue = false">
      <CCard class="modal modal--sm" title="开具发票（双签）" padding="lg">
        <div class="form">
          <div class="sign-box">
            <div class="sign-box__title"><CIcon name="shield" :size="16" /> 双签确认</div>
            <div class="sign-box__text">发票：{{ selected?.invoiceNo }}　|　{{ selected?.title }}</div>
            <div class="sign-box__text">价税合计：<b>{{ selected ? money(selected.amount) : '' }}</b></div>
          </div>
          <div class="form__row">
            <label class="form__label">复核人 <span class="req">*</span></label>
            <CInput v-model="issueReviewer" placeholder="请输入复核人姓名，如：苏晴（店长）" />
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showIssue = false">取消</CButton>
          <CButton variant="primary" :disabled="!canIssue" @click="submitIssue">确认开具</CButton>
        </template>
      </CCard>
    </div>

    <!-- 作废弹层 -->
    <div v-if="showVoid" class="modal-mask" @click.self="showVoid = false">
      <CCard class="modal modal--sm" title="作废发票" padding="lg">
        <div class="form">
          <div class="form__row">
            <label class="form__label">作废原因 <span class="req">*</span></label>
            <CInput v-model="voidReason" placeholder="仅当月未抄税可作废" />
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showVoid = false">取消</CButton>
          <CButton variant="danger" :disabled="!voidReason.trim()" @click="submitVoid">确认作废</CButton>
        </template>
      </CCard>
    </div>

    <!-- 红冲双签弹层 -->
    <div v-if="showFlush" class="modal-mask" @click.self="showFlush = false">
      <CCard class="modal" title="红冲审批（双签）" padding="lg">
        <div class="form">
          <div class="sign-box">
            <div class="sign-box__title"><CIcon name="shield" :size="16" /> 双签确认</div>
            <div class="sign-box__text">发票：{{ selected?.invoiceNo }}　|　{{ selected?.title }}</div>
            <div class="sign-box__text sign-box__text--danger">红冲金额：{{ selected ? money(selected.amount) : '' }}（跨月/已抄税需开红字信息表）</div>
          </div>
          <div class="form__row">
            <label class="form__label">复核人 <span class="req">*</span></label>
            <CInput v-model="flushForm.reviewer" placeholder="请输入复核人姓名，如：陈雅琳（财务主管）" />
          </div>
          <div class="form__row">
            <label class="form__label">红冲原因 <span class="req">*</span></label>
            <CInput v-model="flushForm.reason" placeholder="如：发生退款、开票信息有误" />
          </div>
          <p class="form__tip">红冲仅登记红字发票状态，不做真实资金动账；需复核人确认后生效。</p>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showFlush = false">取消</CButton>
          <CButton variant="danger" :disabled="!canFlush" @click="submitFlush">确认红冲</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.inv { display: flex; flex-direction: column; gap: var(--s-lg); }
.inv__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .inv__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }

.inv__body { display: grid; grid-template-columns: 380px 1fr; gap: var(--s-lg); align-items: start; }
.inv__list { min-width: 0; }
.filters { display: flex; gap: var(--s-sm); padding: var(--s-md); border-bottom: 1px solid var(--c-border-light); }
.list { max-height: 640px; overflow-y: auto; }
.empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); font-size: var(--t-sm); }
.empty__icon { color: var(--c-text-4); }

.row {
  display: block; width: 100%; text-align: left; padding: var(--s-md) var(--s-lg);
  background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer;
}
.row:hover { background: var(--c-brand-soft); }
.row--active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.row__top { display: flex; align-items: center; justify-content: space-between; gap: var(--s-sm); margin-bottom: 4px; }
.row__no { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.row__title { font-size: var(--t-sm); color: var(--c-text-2); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.row__sub { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }
.row__bottom { display: flex; align-items: center; justify-content: space-between; margin-top: var(--s-xs); }
.row__amt { font-size: var(--t-sm); font-weight: 700; color: var(--c-text); font-variant-numeric: tabular-nums; }
.row__date { font-size: var(--t-xs); color: var(--c-text-3); }

.inv__detail-head { display: flex; align-items: center; gap: var(--s-md); width: 100%; }
.inv__who { flex: 1; min-width: 0; }
.inv__no { font-size: var(--t-lg); font-weight: 700; margin: 0; }
.inv__sub { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }

.detail-body { padding: var(--s-lg); display: flex; flex-direction: column; gap: var(--s-lg); }
.stat-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); }
.stat { background: var(--c-bg-right); border-radius: var(--r-md); padding: var(--s-md); }
.stat__label { font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: 4px; }
.stat__value { font-size: var(--t-md); font-weight: 700; color: var(--c-text); font-variant-numeric: tabular-nums; }
.stat__value--brand { color: var(--c-brand); }

.kv { display: flex; flex-direction: column; gap: var(--s-sm); background: var(--c-bg-right); border-radius: var(--r-md); padding: var(--s-md); }
.kv__row { display: flex; justify-content: space-between; gap: var(--s-md); font-size: var(--t-sm); }
.kv__k { color: var(--c-text-3); flex-shrink: 0; }
.kv__v { color: var(--c-text-2); font-variant-numeric: tabular-nums; text-align: right; word-break: break-all; }
.kv__v--danger { color: var(--c-danger-fg); }

.block { display: flex; flex-direction: column; gap: var(--s-sm); }
.block__title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.taxrows { display: flex; flex-direction: column; gap: var(--s-xs); }
.taxrow { display: flex; align-items: center; gap: var(--s-md); padding: var(--s-sm) var(--s-md); background: var(--c-bg-right); border-radius: var(--r-sm); font-size: var(--t-sm); }
.taxrow__rate { width: 56px; color: var(--c-brand); font-weight: 600; flex-shrink: 0; }
.taxrow__amt { flex: 1; color: var(--c-text); font-weight: 600; font-variant-numeric: tabular-nums; }
.taxrow__tax { font-size: var(--t-xs); color: var(--c-text-3); font-variant-numeric: tabular-nums; }
.taxrow--empty { color: var(--c-text-3); justify-content: center; }

.ops { display: flex; align-items: center; gap: var(--s-sm); flex-wrap: wrap; }
.ops__hint { font-size: var(--t-xs); color: var(--c-text-3); }
.ops__hint--danger { color: var(--c-danger-fg); font-weight: 600; }

.detail-empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-md); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); }
.detail-empty__icon { color: var(--c-text-4); }

.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 520px; max-width: 100%; box-shadow: var(--shadow-pop); }
.modal--sm { width: 400px; }
.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.form__row--2 { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.form__label { font-size: var(--t-xs); color: var(--c-text-3); }
.req { color: var(--c-danger-fg); }
.form__calc { font-size: var(--t-sm); color: var(--c-text-2); background: var(--c-bg-right); border-radius: var(--r-sm); padding: var(--s-sm) var(--s-md); }
.form__tip { font-size: var(--t-xs); color: var(--c-text-3); background: var(--c-bg-right); border-radius: var(--r-sm); padding: var(--s-sm); margin: 0; }
.sign-box { background: var(--c-warning-bg); border: 1px solid var(--c-border-light); border-radius: var(--r-md); padding: var(--s-md); display: flex; flex-direction: column; gap: 4px; }
.sign-box__title { display: flex; align-items: center; gap: var(--s-xs); font-size: var(--t-sm); font-weight: 600; color: var(--c-warning-fg); }
.sign-box__text { font-size: var(--t-sm); color: var(--c-text-2); }
.sign-box__text--danger { color: var(--c-danger-fg); font-weight: 600; }

@media (max-width: 1024px) {
  .inv__body { grid-template-columns: 1fr; }
  .inv__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .stat-grid { grid-template-columns: repeat(2, 1fr); }
  .form__row--2 { grid-template-columns: 1fr; }
  .list { max-height: 360px; }
}
</style>
