<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CTextarea from '@/components/CTextarea.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import {
  useM1ProcurementStore, type PurchaseOrder, type PoStatus,
} from '@/stores/m1Procurement'
import { useAuthStore } from '@/stores/auth'

const pc = useM1ProcurementStore()
const auth = useAuthStore()
onMounted(() => pc.seed())

const canEdit = computed(() => auth.can('inventory:edit'))
const canApprove = computed(() => auth.can('inventory:approve') || auth.isSuper)

const tab = ref<'po' | 'supplier' | 'inventory'>('po')
const statusFilter = ref<PoStatus | ''>('')

const filteredOrders = computed(() => {
  return pc.orders.filter((o) => !statusFilter.value || o.status === statusFilter.value)
})

// 选中采购单
const selectedId = ref('')
const selected = computed<PurchaseOrder | undefined>(() => {
  if (selectedId.value) {
    const o = pc.order(selectedId.value)
    if (o) return o
  }
  return filteredOrders.value[0]
})

function select(o: PurchaseOrder) { selectedId.value = o.id }

function statusTone(s: PoStatus) {
  return {
    DRAFT: 'disabled', SUBMITTED: 'warning', APPROVED: 'info',
    PARTIAL: 'primary', RECEIVED: 'success', CANCELLED: 'danger',
  }[s] as 'disabled' | 'warning' | 'info' | 'primary' | 'success' | 'danger'
}
function tierTone(t: string) {
  return t === 'GROUP' ? 'danger' : t === 'REGION' ? 'warning' : 'info'
}
function fmtMoney(n: number) { return '¥' + n.toLocaleString('zh-CN') }
function fmtDate(iso: string) { return iso.slice(0, 10) }
function pct(o: PurchaseOrder) {
  const q = o.items.reduce((s, it) => s + it.qty, 0)
  const r = o.items.reduce((s, it) => s + it.receivedQty, 0)
  return q ? Math.round((r / q) * 100) : 0
}

const statusTabs: { key: PoStatus | ''; label: string }[] = [
  { key: '', label: '全部' },
  { key: 'SUBMITTED', label: '待审批' },
  { key: 'APPROVED', label: '待入库' },
  { key: 'PARTIAL', label: '部分入库' },
  { key: 'RECEIVED', label: '已入库' },
  { key: 'DRAFT', label: '草稿' },
  { key: 'CANCELLED', label: '已取消' },
]

// ---- 审批确认 ----
const showApprove = ref(false)
const approveNote = ref('')
function openApprove() { approveNote.value = ''; showApprove.value = true }
function confirmApprove(ok: boolean) {
  if (!selected.value) return
  if (ok) pc.approve(selected.value.id, auth.user.name)
  else pc.reject(selected.value.id)
  showApprove.value = false
}

// ---- 入库弹层 ----
const showReceive = ref(false)
const receiveForm = reactive<Record<string, number>>({})
const receiveNote = ref('')
const receiveErr = ref('')
function openReceive() {
  if (!selected.value) return
  receiveErr.value = ''
  receiveNote.value = ''
  Object.keys(receiveForm).forEach((k) => delete receiveForm[k])
  selected.value.items.forEach((it) => {
    if (it.receivedQty < it.qty) receiveForm[it.sku] = it.qty - it.receivedQty
  })
  showReceive.value = true
}
function confirmReceive() {
  if (!selected.value) return
  const items = Object.entries(receiveForm)
    .map(([sku, q]) => ({ sku, qty: Number(q) || 0 }))
    .filter((x) => x.qty > 0)
  if (items.length === 0) { receiveErr.value = '请填写本次入库数量'; return }
  const over = items.some((x) => {
    const it = selected.value!.items.find((i) => i.sku === x.sku)!
    return x.qty > it.qty - it.receivedQty
  })
  if (over) { receiveErr.value = '入库数量不能超过未交数量'; return }
  const targetId = selected.value.id
  pc.receive(targetId, items, auth.user.name, receiveNote.value)
  showReceive.value = false
  selectedId.value = targetId
}

function doSubmit() { if (selected.value) pc.submit(selected.value.id) }
function doCancel() { if (selected.value) pc.cancel(selected.value.id) }

// 库存搜索
const invKw = ref('')
const filteredInv = computed(() => {
  const kw = invKw.value.trim()
  const list = pc.inventory
  if (!kw) return list
  return list.filter((i) => `${i.sku} ${i.name} ${i.brand} ${i.storeName}`.includes(kw))
})
function stockLevel(i: { onHand: number; safety: number }) {
  if (i.onHand === 0) return 'out'
  if (i.onHand <= i.safety) return 'low'
  return 'ok'
}
</script>

<template>
  <div class="mp-page">
    <!-- KPI -->
    <div class="mp-kpis">
      <div class="kpi kpi--brand"><div class="kpi__icon"><CIcon name="package" :size="20" /></div><div class="kpi__body"><div class="kpi__label">合作供应商</div><div class="kpi__value">{{ pc.stats.supplierCount }}</div></div></div>
      <div class="kpi kpi--warning"><div class="kpi__icon"><CIcon name="clock" :size="20" /></div><div class="kpi__body"><div class="kpi__label">待审批采购单</div><div class="kpi__value">{{ pc.stats.pendingApprove }}</div></div></div>
      <div class="kpi kpi--info"><div class="kpi__icon"><CIcon name="box" :size="20" /></div><div class="kpi__body"><div class="kpi__label">待入库</div><div class="kpi__value">{{ pc.stats.pendingReceive }}</div></div></div>
      <div class="kpi kpi--danger"><div class="kpi__icon"><CIcon name="alert" :size="20" /></div><div class="kpi__body"><div class="kpi__label">库存预警</div><div class="kpi__value">{{ pc.stats.lowStock }}</div></div></div>
    </div>

    <CCard padding="none">
      <div class="mp-tabs">
        <button class="mp-tab" :class="{ 'is-active': tab === 'po' }" @click="tab = 'po'"><CIcon name="order" :size="15" /> 采购订单</button>
        <button class="mp-tab" :class="{ 'is-active': tab === 'supplier' }" @click="tab = 'supplier'"><CIcon name="mall" :size="15" /> 供应商</button>
        <button class="mp-tab" :class="{ 'is-active': tab === 'inventory' }" @click="tab = 'inventory'"><CIcon name="box" :size="15" /> 库存<span v-if="pc.stats.lowStock" class="badge">{{ pc.stats.lowStock }}</span></button>
      </div>
    </CCard>

    <!-- 采购单 -->
    <div v-if="tab === 'po'" class="mp-po">
      <CCard padding="none" class="po-list">
        <div class="po-filters">
          <button v-for="t in statusTabs" :key="t.key" class="chip" :class="{ 'chip--on': statusFilter === t.key }" @click="statusFilter = t.key">{{ t.label }}</button>
        </div>
        <div class="po-items">
          <div v-for="o in filteredOrders" :key="o.id" class="po-item" :class="{ 'po-item--active': selected?.id === o.id }" @click="select(o)">
            <div class="po-item__top">
              <span class="po-no">{{ o.poNo }}</span>
              <CStatusPill :status="statusTone(o.status)" dot>{{ pc.PO_STATUS_LABEL[o.status] }}</CStatusPill>
            </div>
            <div class="po-item__name">{{ pc.supplier(o.supplierId)?.name ?? '—' }}</div>
            <div class="po-item__meta">
              <span>{{ o.storeName }}</span>
              <span class="po-amount">{{ fmtMoney(o.totalAmount) }}</span>
            </div>
            <div class="po-item__foot">
              <span class="tier" :class="'tier--' + tierTone(o.signTier)">{{ o.signTier === 'GROUP' ? '集团审' : o.signTier === 'REGION' ? '区域审' : '门店审' }}</span>
              <span class="muted">{{ o.items.length }} 项 · 收货 {{ pct(o) }}%</span>
            </div>
          </div>
          <div v-if="filteredOrders.length === 0" class="mp-empty">暂无采购单</div>
        </div>
      </CCard>

      <CCard v-if="selected" padding="none" class="po-detail">
        <div class="pd-head">
          <div>
            <div class="pd-head__row">
              <h3>{{ selected.poNo }}</h3>
              <CStatusPill :status="statusTone(selected.status)" dot>{{ pc.PO_STATUS_LABEL[selected.status] }}</CStatusPill>
            </div>
            <div class="pd-meta">
              <span>供应商：{{ pc.supplier(selected.supplierId)?.name }}</span>
              <span>收货门店：{{ selected.storeName }}</span>
              <span>预计到货：{{ selected.expectDate }}</span>
            </div>
          </div>
          <div class="pd-amount">
            <div class="pd-amount__label">采购总额</div>
            <div class="pd-amount__value">{{ fmtMoney(selected.totalAmount) }}</div>
            <div class="tier tier--block" :class="'tier--' + tierTone(selected.signTier)">
              需{{ selected.signTier === 'GROUP' ? '集团' : selected.signTier === 'REGION' ? '区域' : '门店' }}审批
            </div>
          </div>
        </div>

        <div v-if="selected.remark" class="pd-remark">{{ selected.remark }}</div>

        <div class="pd-section">
          <div class="pd-section__title">采购明细</div>
          <div class="table-wrap">
            <table class="dt">
              <thead><tr><th>SKU</th><th>项目</th><th>品牌</th><th class="num">单价</th><th class="num">采购数</th><th class="num">已收</th><th class="num">小计</th></tr></thead>
              <tbody>
                <tr v-for="it in selected.items" :key="it.sku">
                  <td class="mono">{{ it.sku }}</td>
                  <td class="cell-name">{{ it.name }}</td>
                  <td>{{ it.brand }}</td>
                  <td class="num">{{ fmtMoney(it.unitPrice) }}</td>
                  <td class="num">{{ it.qty }} {{ it.unit }}</td>
                  <td class="num" :class="{ 'cell-warn': it.receivedQty < it.qty }">{{ it.receivedQty }} {{ it.unit }}</td>
                  <td class="num price">{{ fmtMoney(it.qty * it.unitPrice) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div v-if="selected.approver" class="pd-approve">
          <CIcon name="check" :size="15" /> 由 <b>{{ selected.approver }}</b> 于 {{ fmtDate(selected.approvedAt!) }} 审批通过
        </div>

        <div class="pd-ops">
          <template v-if="selected.status === 'DRAFT'">
            <CButton variant="secondary" :disabled="!canEdit" v-perm="'inventory:edit'" @click="doCancel">作废</CButton>
            <CButton variant="primary" :disabled="!canEdit" v-perm="'inventory:edit'" @click="doSubmit">提交审批</CButton>
          </template>
          <template v-else-if="selected.status === 'SUBMITTED'">
            <CButton variant="secondary" :disabled="!canApprove" v-perm="'inventory:approve'" @click="confirmApprove(false)">驳回</CButton>
            <CButton variant="primary" :disabled="!canApprove" v-perm="'inventory:approve'" @click="openApprove">审批通过</CButton>
          </template>
          <template v-else-if="selected.status === 'APPROVED' || selected.status === 'PARTIAL'">
            <CButton variant="secondary" :disabled="!canEdit" v-perm="'inventory:edit'" @click="doCancel">作废</CButton>
            <CButton variant="primary" :disabled="!canEdit" v-perm="'inventory:edit'" @click="openReceive"><CIcon name="check" :size="15" /> 入库登记</CButton>
          </template>
          <CButton v-else variant="secondary" disabled>已完结</CButton>
        </div>
      </CCard>
    </div>

    <!-- 供应商 -->
    <div v-if="tab === 'supplier'" class="mp-sup">
      <div v-for="s in pc.suppliers" :key="s.id" class="sup-card" :class="{ 'sup-card--off': s.status === 'INACTIVE' }">
        <div class="sup-card__head">
          <div class="sup-logo">{{ s.name.slice(0, 1) }}</div>
          <div class="sup-card__title">
            <div class="sup-name">{{ s.name }}</div>
            <div class="sup-code">{{ s.code }}</div>
          </div>
          <CStatusPill :status="s.qualified ? 'success' : 'danger'" dot>{{ s.qualified ? '资质有效' : '资质到期' }}</CStatusPill>
        </div>
        <div class="sup-card__body">
          <div class="sup-info"><span class="lbl">联系人</span><span>{{ s.contact }}</span></div>
          <div class="sup-info"><span class="lbl">电话</span><span>{{ s.phone }}</span></div>
          <div class="sup-info"><span class="lbl">账期</span><span>{{ s.paymentTerms }} 天</span></div>
        </div>
        <p v-if="s.remark" class="sup-remark">{{ s.remark }}</p>
      </div>
    </div>

    <!-- 库存 -->
    <div v-if="tab === 'inventory'" class="mp-inv">
      <CCard class="inv-toolbar" padding="md">
        <CInput v-model="invKw" placeholder="搜索 SKU/名称/品牌/门店" />
        <span class="inv-legend"><i class="dot dot--ok"></i> 充足 <i class="dot dot--low"></i> 预警 <i class="dot dot--out"></i> 缺货</span>
      </CCard>
      <CCard padding="none">
        <div class="table-wrap">
          <table class="dt">
            <thead><tr><th>SKU</th><th>品名</th><th>品牌</th><th>门店</th><th class="num">现存量</th><th class="num">安全库存</th><th>状态</th><th>批次/效期</th></tr></thead>
            <tbody>
              <tr v-for="i in filteredInv" :key="i.id" :class="'row--' + stockLevel(i)">
                <td class="mono">{{ i.sku }}</td>
                <td class="cell-name">{{ i.name }}</td>
                <td>{{ i.brand }}</td>
                <td>{{ i.storeName }}</td>
                <td class="num"><b :class="'stock--' + stockLevel(i)">{{ i.onHand }}</b> {{ i.unit }}</td>
                <td class="num">{{ i.safety }} {{ i.unit }}</td>
                <td>
                  <CStatusPill v-if="stockLevel(i) === 'out'" status="danger" dot>缺货</CStatusPill>
                  <CStatusPill v-else-if="stockLevel(i) === 'low'" status="warning" dot>预警</CStatusPill>
                  <CStatusPill v-else status="success" dot>充足</CStatusPill>
                </td>
                <td class="mono muted">{{ i.batchNo ?? '—' }}<span v-if="i.expireDate"> · 效期 {{ i.expireDate }}</span></td>
              </tr>
              <tr v-if="filteredInv.length === 0"><td colspan="8" class="empty-cell">暂无库存</td></tr>
            </tbody>
          </table>
        </div>
      </CCard>
    </div>

    <!-- 审批确认 -->
    <div v-if="showApprove" class="modal-mask" @click.self="showApprove = false">
      <div class="modal modal--sm">
        <div class="modal__head"><h3>采购审批</h3><button class="modal__close" @click="showApprove = false"><CIcon name="close" :size="18" /></button></div>
        <div class="modal__body">
          <p class="confirm-txt">确认审批通过采购单「<b>{{ selected?.poNo }}</b>」（{{ fmtMoney(selected?.totalAmount ?? 0) }}）？审批后供应商可发货、门店可入库。</p>
          <label class="field field--full"><span class="field__label">审批意见</span><CTextarea v-model="approveNote" :rows="2" placeholder="可选" /></label>
        </div>
        <div class="modal__foot">
          <CButton variant="secondary" @click="showApprove = false">取消</CButton>
          <CButton variant="secondary" @click="confirmApprove(false)">驳回</CButton>
          <CButton variant="primary" @click="confirmApprove(true)">审批通过</CButton>
        </div>
      </div>
    </div>

    <!-- 入库登记 -->
    <div v-if="showReceive" class="modal-mask" @click.self="showReceive = false">
      <div class="modal modal--lg">
        <div class="modal__head"><h3>入库登记 · {{ selected?.poNo }}</h3><button class="modal__close" @click="showReceive = false"><CIcon name="close" :size="18" /></button></div>
        <div class="modal__body">
          <div class="table-wrap">
            <table class="dt">
              <thead><tr><th>项目</th><th class="num">采购数</th><th class="num">已收</th><th class="num">本次入库</th></tr></thead>
              <tbody>
                <tr v-for="it in selected!.items.filter((x) => x.receivedQty < x.qty)" :key="it.sku">
                  <td class="cell-name">{{ it.name }}<span class="mono">{{ it.sku }}</span></td>
                  <td class="num">{{ it.qty }} {{ it.unit }}</td>
                  <td class="num">{{ it.receivedQty }} {{ it.unit }}</td>
                  <td class="num"><input v-model.number="receiveForm[it.sku]" type="number" min="0" class="num-input" /></td>
                </tr>
              </tbody>
            </table>
          </div>
          <label class="field field--full" style="margin-top:12px"><span class="field__label">备注</span><CTextarea v-model="receiveNote" :rows="2" placeholder="批次/质检情况（可选）" /></label>
          <div v-if="receiveErr" class="form-err">{{ receiveErr }}</div>
        </div>
        <div class="modal__foot"><CButton variant="secondary" @click="showReceive = false">取消</CButton><CButton variant="primary" @click="confirmReceive">确认入库</CButton></div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.mp-page { display: flex; flex-direction: column; gap: var(--s-md); }
.mp-kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); }
.kpi { display: flex; align-items: center; gap: var(--s-md); padding: var(--s-md); border-radius: var(--r-xl); background: var(--c-surface); border: 1px solid var(--c-border-light); }
.kpi__icon { width: 44px; height: 44px; border-radius: var(--r-lg); display: flex; align-items: center; justify-content: center; flex: none; }
.kpi--brand .kpi__icon { background: var(--c-brand-soft); color: var(--c-brand); }
.kpi--info .kpi__icon { background: var(--c-info-bg, #EAF2FF); color: var(--c-info-fg); }
.kpi--warning .kpi__icon { background: var(--c-warning-bg, #FFF5E6); color: var(--c-warning-fg); }
.kpi--danger .kpi__icon { background: var(--c-danger-bg, #FFF0F0); color: var(--c-danger-fg); }
.kpi__label { font-size: var(--t-xs); color: var(--c-text-3); }
.kpi__value { font-size: var(--t-xl); font-weight: 700; color: var(--c-text); line-height: 1.2; }

.mp-tabs { display: flex; gap: var(--s-xs); padding: 4px; }
.mp-tab { display: inline-flex; align-items: center; gap: 6px; border: none; background: none; padding: var(--s-sm) var(--s-md); font-size: var(--t-sm); font-weight: 600; color: var(--c-text-3); cursor: pointer; border-radius: var(--r-md); }
.mp-tab:hover { color: var(--c-text); background: var(--c-surface, #f7f8fa); }
.mp-tab.is-active { color: var(--c-brand); background: var(--c-brand-soft); }
.badge { display: inline-flex; align-items: center; justify-content: center; min-width: 18px; height: 18px; padding: 0 5px; border-radius: 9px; background: var(--c-danger-fg); color: #fff; font-size: 11px; }

.mp-po { display: grid; grid-template-columns: 360px 1fr; gap: var(--s-md); align-items: start; }
.po-list { max-height: calc(100vh - 280px); display: flex; flex-direction: column; }
.po-filters { display: flex; flex-wrap: wrap; gap: 4px; padding: var(--s-sm) var(--s-md); border-bottom: 1px solid var(--c-border-light); }
.chip { border: 1px solid var(--c-border-light); background: var(--c-surface); padding: 4px 12px; border-radius: var(--r-capsule); font-size: var(--t-xs); color: var(--c-text-2); cursor: pointer; }
.chip:hover { border-color: var(--c-brand); color: var(--c-brand); }
.chip--on { background: var(--c-brand); color: #fff; border-color: var(--c-brand); }
.po-items { overflow-y: auto; padding: var(--s-xs); }
.po-item { padding: var(--s-sm) var(--s-md); border-radius: var(--r-md); cursor: pointer; border: 1px solid transparent; }
.po-item:hover { background: var(--c-surface, #f7f8fa); }
.po-item--active { background: var(--c-brand-soft); border-color: var(--c-brand); }
.po-item__top { display: flex; align-items: center; justify-content: space-between; }
.po-no { font-family: var(--t-number, monospace); font-size: var(--t-xs); color: var(--c-text-2); font-weight: 600; }
.po-item__name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); margin: 4px 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.po-item__meta { display: flex; justify-content: space-between; font-size: var(--t-xs); color: var(--c-text-3); }
.po-amount { font-family: var(--t-number, monospace); font-weight: 600; color: var(--c-text); }
.po-item__foot { display: flex; justify-content: space-between; align-items: center; margin-top: 6px; font-size: 11px; color: var(--c-text-3); }
.tier { font-size: 11px; padding: 1px 8px; border-radius: var(--r-capsule); font-weight: 500; }
.tier--info { background: var(--c-info-bg, #EAF2FF); color: var(--c-info-fg); }
.tier--warning { background: var(--c-warning-bg, #FFF5E6); color: var(--c-warning-fg); }
.tier--danger { background: var(--c-danger-bg, #FFF0F0); color: var(--c-danger-fg); }
.tier--block { display: inline-block; margin-top: 6px; }
.muted { color: var(--c-text-3); }
.mp-empty { padding: var(--s-xl); text-align: center; color: var(--c-text-3); font-size: var(--t-sm); }

.po-detail { display: flex; flex-direction: column; }
.pd-head { display: flex; justify-content: space-between; gap: var(--s-md); padding: var(--s-lg); border-bottom: 1px solid var(--c-border-light); }
.pd-head__row { display: flex; align-items: center; gap: var(--s-sm); }
.pd-head__row h3 { margin: 0; font-size: var(--t-lg); font-weight: 700; }
.pd-meta { display: flex; flex-wrap: wrap; gap: var(--s-md); margin-top: 6px; font-size: var(--t-xs); color: var(--c-text-3); }
.pd-amount { text-align: right; }
.pd-amount__label { font-size: var(--t-xs); color: var(--c-text-3); }
.pd-amount__value { font-size: var(--t-xl); font-weight: 700; color: var(--c-brand); }
.pd-remark { margin: 0; padding: var(--s-sm) var(--s-lg); font-size: var(--t-xs); color: var(--c-text-2); background: var(--c-surface, #f7f8fa); border-bottom: 1px solid var(--c-border-light); }
.pd-section { padding: var(--s-lg); }
.pd-section__title { font-size: var(--t-sm); font-weight: 700; color: var(--c-text); margin-bottom: var(--s-sm); }
.pd-approve { display: flex; align-items: center; gap: 6px; margin: 0 var(--s-lg) var(--s-md); padding: var(--s-sm) var(--s-md); background: var(--c-success-bg, #f0fbf0); color: var(--c-success-fg); border-radius: var(--r-md); font-size: var(--t-xs); }
.pd-ops { display: flex; justify-content: flex-end; gap: var(--s-sm); padding: var(--s-md) var(--s-lg); border-top: 1px solid var(--c-border-light); }

.table-wrap { border: 1px solid var(--c-border-light); border-radius: var(--r-lg); overflow: auto; }
.dt { width: 100%; border-collapse: collapse; font-size: var(--t-sm); }
.dt thead th { background: var(--c-surface, #f7f8fa); color: var(--c-text-3); font-weight: 600; text-align: left; padding: 10px var(--s-md); font-size: var(--t-xs); white-space: nowrap; border-bottom: 1px solid var(--c-border-light); }
.dt tbody td { padding: 10px var(--s-md); border-bottom: 1px solid var(--c-border-light); vertical-align: middle; }
.dt tbody tr:last-child td { border-bottom: none; }
.dt tbody tr:hover { background: var(--c-surface, #f7f8fa); }
.num { text-align: right; font-family: var(--t-number, monospace); white-space: nowrap; }
.mono { font-family: var(--t-number, monospace); font-size: var(--t-xs); color: var(--c-text-2); display: block; }
.cell-name { font-weight: 600; color: var(--c-text); }
.price { color: var(--c-brand); font-weight: 700; }
.cell-warn { color: var(--c-warning-fg); }

.mp-sup { display: grid; grid-template-columns: repeat(2, 1fr); gap: var(--s-md); }
.sup-card { padding: var(--s-lg); border-radius: var(--r-xl); background: var(--c-surface); border: 1px solid var(--c-border-light); }
.sup-card--off { opacity: .65; }
.sup-card__head { display: flex; align-items: center; gap: var(--s-md); }
.sup-logo { width: 44px; height: 44px; border-radius: var(--r-lg); background: var(--c-brand-soft); color: var(--c-brand); font-weight: 700; font-size: var(--t-lg); display: flex; align-items: center; justify-content: center; }
.sup-card__title { flex: 1; }
.sup-name { font-size: var(--t-md); font-weight: 700; color: var(--c-text); }
.sup-code { font-family: var(--t-number, monospace); font-size: var(--t-xs); color: var(--c-text-3); }
.sup-card__body { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--s-md); margin-top: var(--s-md); }
.sup-info { display: flex; flex-direction: column; gap: 2px; }
.sup-info .lbl { font-size: var(--t-xs); color: var(--c-text-3); }
.sup-remark { margin: var(--s-md) 0 0; padding: var(--s-sm) var(--s-md); background: var(--c-surface, #f7f8fa); border-radius: var(--r-sm); border-left: 3px solid var(--c-warning-fg); font-size: var(--t-xs); color: var(--c-text-2); }

.inv-toolbar { display: flex; align-items: center; justify-content: space-between; gap: var(--s-md); }
.inv-toolbar > :deep(.cinput) { max-width: 320px; }
.inv-legend { font-size: var(--t-xs); color: var(--c-text-3); display: flex; align-items: center; gap: 6px; }
.dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; margin: 0 2px; }
.dot--ok { background: var(--c-success-fg); }
.dot--low { background: var(--c-warning-fg); }
.dot--out { background: var(--c-danger-fg); }
.row--low { background: var(--c-warning-bg, #FFF5E633); }
.row--out { background: var(--c-danger-bg, #FFF0F055); }
.stock--low { color: var(--c-warning-fg); }
.stock--out { color: var(--c-danger-fg); }
.empty-cell { text-align: center; color: var(--c-text-3); padding: var(--s-xl); }

.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,.45); display: flex; align-items: center; justify-content: center; z-index: 100; }
.modal { background: var(--c-surface); border-radius: var(--r-xl); width: 520px; max-width: calc(100vw - 48px); max-height: 86vh; display: flex; flex-direction: column; box-shadow: var(--shadow-pop, 0 12px 40px rgba(0,0,0,.18)); }
.modal--sm { width: 420px; }
.modal--lg { width: 680px; }
.modal__head { display: flex; align-items: center; justify-content: space-between; padding: var(--s-md) var(--s-lg); border-bottom: 1px solid var(--c-border-light); }
.modal__head h3 { margin: 0; font-size: var(--t-lg); font-weight: 700; }
.modal__close { border: none; background: none; cursor: pointer; color: var(--c-text-3); padding: 4px; display: flex; border-radius: var(--r-sm); }
.modal__close:hover { background: var(--c-surface, #f7f8fa); color: var(--c-text); }
.modal__body { padding: var(--s-lg); overflow-y: auto; }
.modal__foot { display: flex; justify-content: flex-end; gap: var(--s-sm); padding: var(--s-md) var(--s-lg); border-top: 1px solid var(--c-border-light); }
.field { display: flex; flex-direction: column; gap: 6px; }
.field--full { width: 100%; }
.field__label { font-size: var(--t-xs); color: var(--c-text-2); font-weight: 500; }
.form-err { margin-top: var(--s-sm); color: var(--c-danger-fg); font-size: var(--t-xs); }
.confirm-txt { margin: 0 0 var(--s-md); font-size: var(--t-sm); color: var(--c-text-2); }
.num-input { width: 90px; height: 32px; padding: 0 8px; border: 1px solid var(--c-border); border-radius: var(--r-md); font-family: var(--t-number, monospace); text-align: right; }

@media (max-width: 1024px) {
  .mp-kpis { grid-template-columns: repeat(2, 1fr); }
  .mp-po { grid-template-columns: 1fr; }
  .po-list { max-height: none; }
  .mp-sup { grid-template-columns: 1fr; }
}
</style>
