<script setup lang="ts">
/* ============================================================
 * 划扣核销 /writeoff（Desktop 优先 · 平板堆叠）
 * 待核销 = txn-service「已收款」订单中尚未核销者；确认核销走 POST /txn/order-writeoff，
 * 已核销/异常记录取 GET /txn/order-writeoff?status=。金额后端存「分」，适配层转「元」。
 * 样式/模板沿用原版，仅替换数据源（mock order/writeoff store → 真实 API），未改布局与交互。
 * ============================================================ */
import { computed, onMounted, ref, watch } from 'vue'
import CWorkbenchShell from '@/components/CWorkbenchShell.vue'
import CKpi from '@/components/CKpi.vue'
import CButton from '@/components/CButton.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import { useStoreContext } from '@/stores/storeContext'
import { useCustomerStore } from '@/stores/customer'
import { useAuthStore } from '@/stores/auth'
import { useToast } from '@/composables/useToast'
import { listOrders, type OrderViewDTO } from '@/api/order'
import { orderWriteoff, listOrderWriteoffs, type OrderWriteoffDTO } from '@/api/writeoff'
import { WRITEOFF_RECORD_STATUS, dictPill } from '@/config/dictionary'

interface UiItem {
  name: string
  spec?: string
  qty: number
  price: number // 元
}
interface UiOrder {
  id: string
  orderNo: string
  customerId: string
  customerName: string
  items: UiItem[]
  amount: number // 元
  paidAt?: string
}
interface UiRecord {
  id: string
  writeoffNo: string
  orderNo: string
  customerId: string
  customerName: string
  project: string
  timesUsed: number
  amount: number // 元
  operatorName: string
  status: 'PENDING' | 'DONE' | 'ABNORMAL' | 'VOID'
  createdAt?: string
  doneAt?: string
  abnormalReason?: string
}

const customer = useCustomerStore()
const auth = useAuthStore()
const toast = useToast()
const storeCtx = useStoreContext()

const fen2yuan = (f: number | null | undefined) => (f == null ? 0 : f / 100)

function adaptOrder(d: OrderViewDTO): UiOrder {
  return {
    id: d.orderNo,
    orderNo: d.orderNo,
    customerId: d.customerId,
    customerName: d.customerName || d.customerId,
    items: (d.items || []).map((it) => ({ name: it.itemName, qty: it.qty, price: fen2yuan(it.unitPrice) })),
    amount: fen2yuan(d.amount),
    paidAt: d.createdAt || undefined,
  }
}

function adaptRecord(d: OrderWriteoffDTO): UiRecord {
  return {
    id: d.writeoffNo,
    writeoffNo: d.writeoffNo,
    orderNo: d.orderNo,
    customerId: d.customerId,
    customerName: d.customerName || d.customerId,
    project: d.project || '—',
    timesUsed: d.timesUsed ?? 1,
    amount: fen2yuan(d.amount),
    operatorName: d.operatorName || d.operator || '—',
    status: d.status as UiRecord['status'],
    createdAt: d.createdAt || undefined,
    abnormalReason: d.abnormalReason || undefined,
  }
}

const orders = ref<UiOrder[]>([])
const records = ref<UiRecord[]>([])

async function load() {
  try {
    if (!storeCtx.loaded) await storeCtx.loadStores()
    const [paidPage, doneRes, abnRes] = await Promise.all([
      listOrders({ size: 100, status: '已收款', storeCode: storeCtx.currentStoreCode }),
      listOrderWriteoffs('DONE'),
      listOrderWriteoffs('ABNORMAL'),
    ])
    const written = new Set([...doneRes.data, ...abnRes.data].map((w) => w.orderNo))
    orders.value = paidPage.data.content
      .filter((d) => !written.has(d.orderNo))
      .map(adaptOrder)
    records.value = [...doneRes.data.map(adaptRecord), ...abnRes.data.map(adaptRecord)]
  } catch (e: any) {
    toast.error('核销数据加载失败：' + (e?.response?.data?.message || e?.message || '网络异常'))
  }
}

onMounted(load)
watch(() => storeCtx.currentStoreCode, () => load())

// ---- 模板所需的 store 形状适配（保持原版模板零改动）----
const order = {
  get(id: string) {
    return orders.value.find((o) => o.id === id) ?? null
  },
}
const writeoff = {
  get(id: string) {
    return records.value.find((r) => r.id === id) ?? null
  },
  get pendingOrders() {
    return orders.value
  },
  get doneRecords() {
    return records.value.filter((r) => r.status === 'DONE')
  },
  get abnormalRecords() {
    return records.value.filter((r) => r.status === 'ABNORMAL')
  },
  get records() {
    return records.value
  },
}

type Tab = 'pending' | 'done' | 'abnormal'
const tab = ref<Tab>('pending')

const tabs = computed(() => [
  { k: 'pending' as Tab, label: `待核销 (${writeoff.pendingOrders.length})` },
  { k: 'done' as Tab, label: `已核销 (${writeoff.doneRecords.length})` },
  { k: 'abnormal' as Tab, label: `异常 (${writeoff.abnormalRecords.length})` },
])

const selectedId = ref<string | null>(null)
const selectedRecordId = ref<string | null>(null)

const pendingList = computed<UiOrder[]>(() => writeoff.pendingOrders)
const selectedOrder = computed<UiOrder | null>(() => {
  if (tab.value !== 'pending') return null
  if (selectedId.value) return order.get(selectedId.value)
  return pendingList.value[0] ?? null
})
const selectedRecord = computed<UiRecord | null>(() => {
  if (tab.value === 'pending') return null
  if (selectedRecordId.value) return writeoff.get(selectedRecordId.value)
  return history.value[0] ?? null
})

function selectOrder(id: string) {
  selectedId.value = id
  selectedRecordId.value = null
}
function selectRecord(id: string) {
  selectedRecordId.value = id
  selectedId.value = null
}

// 核销核验清单
const checks = ref([
  { key: 'service', label: '服务已实际完成（履约记录可查）', done: true },
  { key: 'sign', label: '客户现场签字 / 电子确认', done: true },
  { key: 'amount', label: '划扣次数与金额与订单一致', done: false },
  { key: 'balance', label: '卡余额度充足，未透支', done: true },
])
const allChecked = computed(() => checks.value.every((c) => c.done))
function toggleCheck(c: { done: boolean }) {
  c.done = !c.done
}

async function confirmWriteoff() {
  if (!selectedOrder.value || !allChecked.value) return
  try {
    const res = await orderWriteoff(selectedOrder.value.orderNo, auth.user.staffId || 'cashier')
    await load()
    checks.value = checks.value.map((c) => ({ ...c, done: false }))
    checks.value[0].done = true
    checks.value[1].done = true
    checks.value[3].done = true
    selectedId.value = null
    selectedRecordId.value = null
    toast.success(`核销成功，单号 ${res.data.writeoffNo}`)
  } catch (e: any) {
    toast.error('核销失败：' + (e?.response?.data?.message || e?.message || '网络异常'))
  }
}

function fmtMoney(n: number) {
  return '¥' + n.toLocaleString('zh-CN')
}
function fmtTime(iso?: string) {
  if (!iso) return ''
  const d = new Date(iso)
  return `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

const customerName = (id: string) =>
  orders.value.find((o) => o.customerId === id)?.customerName ||
  records.value.find((r) => r.customerId === id)?.customerName ||
  customer.get?.(id)?.name ||
  customer.customers.find((c) => c.id === id)?.name ||
  id
const phoneMask = (id: string) => customer.customers.find((c) => c.id === id)?.phoneMask || '—'

// 历史记录列表
const history = computed(() => {
  if (tab.value === 'done') return writeoff.doneRecords
  if (tab.value === 'abnormal') return writeoff.abnormalRecords
  return writeoff.records
})
</script>

<template>
  <div class="wo">
    <CWorkbenchShell
      :has-selection="!!selectedOrder || !!selectedRecord"
      empty-icon="check-square"
      empty-title="请从左侧选择一笔待核销订单"
      empty-desc="已支付订单在此核验清单确认后核销扣次；已核销/异常记录可在左侧切换查看"
      list-width="380px"
    >
      <template #kpis>
        <CKpi label="待核销笔数" :value="String(pendingList.length)" tone="warning" icon="check-square" />
        <CKpi label="本月已核销" :value="String(writeoff.doneRecords.length)" tone="brand" icon="check" />
        <CKpi label="异常笔数" :value="String(writeoff.abnormalRecords.length)" tone="danger" icon="alert" />
      </template>

      <!-- 左列 -->
      <template #list>
        <div class="tabs">
          <button
            v-for="t in tabs"
            :key="t.k"
            class="tab"
            :class="{ 'tab--active': tab === t.k }"
            @click="tab = t.k"
          >{{ t.label }}</button>
        </div>

        <!-- 待核销：来自已支付订单 -->
        <div v-if="tab === 'pending'" class="list">
          <div v-if="pendingList.length === 0" class="empty">
            <CIcon name="check-square" :size="28" class="empty__icon" />
            <div>暂无待核销订单</div>
          </div>
          <button
            v-for="o in pendingList"
            :key="o.id"
            class="ord"
            :class="{ 'ord--active': selectedOrder?.id === o.id }"
            @click="selectOrder(o.id)"
          >
            <div class="ord__top">
              <span class="ord__no">{{ o.orderNo }}</span>
              <CStatusPill status="warning">待核销</CStatusPill>
            </div>
            <div class="ord__cust">{{ customerName(o.customerId) }} · {{ phoneMask(o.customerId) }}</div>
            <div class="ord__proj">{{ o.items[0]?.name }}</div>
            <div class="ord__meta">
              <span>{{ fmtMoney(o.amount) }}</span>
              <span>{{ fmtTime(o.paidAt) }}</span>
            </div>
          </button>
        </div>

        <!-- 已核销 / 异常：核销记录 -->
        <div v-else class="list">
          <div v-if="history.length === 0" class="empty">
            <CIcon name="profile" :size="28" class="empty__icon" />
            <div>暂无记录</div>
          </div>
          <button
            v-for="r in history"
            :key="r.id"
            class="rec"
            :class="{ 'rec--active': selectedRecord?.id === r.id }"
            @click="selectRecord(r.id)"
          >
            <div class="rec__top">
              <span class="rec__no">{{ r.writeoffNo }}</span>
              <CStatusPill :status="dictPill(WRITEOFF_RECORD_STATUS[r.status]).status">{{ dictPill(WRITEOFF_RECORD_STATUS[r.status]).text }}</CStatusPill>
            </div>
            <div class="rec__proj">{{ r.project }}</div>
            <div class="rec__meta">
              <span>扣 {{ r.timesUsed }} 次 · {{ fmtMoney(r.amount) }}</span>
              <span>{{ r.operatorName }}</span>
            </div>
            <div v-if="r.abnormalReason" class="rec__abn">{{ r.abnormalReason }}</div>
          </button>
        </div>
      </template>

      <!-- 右列：核销执行 -->
      <template #head>
        <div v-if="selectedOrder" class="wb-head">
          <span class="wo-detail-label">核销执行</span>
          <CStatusPill status="warning">待核销</CStatusPill>
        </div>
        <div v-else-if="selectedRecord" class="wb-head">
          <span class="wo-detail-label">核销记录</span>
          <CStatusPill :status="dictPill(WRITEOFF_RECORD_STATUS[selectedRecord.status]).status">{{ dictPill(WRITEOFF_RECORD_STATUS[selectedRecord.status]).text }}</CStatusPill>
        </div>
      </template>

      <template v-if="selectedOrder">
        <div class="cust">
          <div class="cust__name">{{ customerName(selectedOrder.customerId) }}</div>
          <div class="cust__sub">{{ phoneMask(selectedOrder.customerId) }} · 订单 {{ selectedOrder.orderNo }}</div>
        </div>

        <div class="sec-title">订单明细</div>
        <div class="items">
          <div v-for="(it, i) in selectedOrder.items" :key="i" class="item">
            <div class="item__main">
              <span class="item__name">{{ it.name }}</span>
              <span v-if="it.spec" class="item__spec">{{ it.spec }}</span>
            </div>
            <div class="item__right">
              <span class="item__qty">×{{ it.qty }}</span>
              <span class="item__price">{{ fmtMoney(it.price * it.qty) }}</span>
            </div>
          </div>
        </div>
        <div class="total">
          <span>核销金额合计</span>
          <span class="total__amt">{{ fmtMoney(selectedOrder.amount) }}</span>
        </div>

        <div class="sec-title">核销核验清单</div>
        <div class="checks">
          <button
            v-for="c in checks"
            :key="c.key"
            class="check"
            :class="{ 'check--on': c.done }"
            @click="toggleCheck(c)"
          >
            <CIcon v-if="c.done" name="check-square" :size="18" class="check__box" />
            <span v-else class="check__square"></span>
            <span>{{ c.label }}</span>
          </button>
        </div>
      </template>

      <template v-else-if="selectedRecord">
        <div class="cust">
          <div class="cust__name">{{ customerName(selectedRecord.customerId) }}</div>
          <div class="cust__sub">{{ selectedRecord.orderNo }} · {{ selectedRecord.writeoffNo }}</div>
        </div>

        <div class="sec-title">核销明细</div>
        <div class="rec-detail">
          <div class="rec-field">
            <span class="rec-field__label">项目</span>
            <span class="rec-field__val">{{ selectedRecord.project }}</span>
          </div>
          <div class="rec-field">
            <span class="rec-field__label">扣次</span>
            <span class="rec-field__val">{{ selectedRecord.timesUsed }} 次</span>
          </div>
          <div class="rec-field">
            <span class="rec-field__label">金额</span>
            <span class="rec-field__val">{{ fmtMoney(selectedRecord.amount) }}</span>
          </div>
          <div class="rec-field">
            <span class="rec-field__label">操作人</span>
            <span class="rec-field__val">{{ selectedRecord.operatorName }}</span>
          </div>
          <div class="rec-field">
            <span class="rec-field__label">核销时间</span>
            <span class="rec-field__val">{{ fmtTime(selectedRecord.doneAt || selectedRecord.createdAt) }}</span>
          </div>
          <div v-if="selectedRecord.abnormalReason" class="rec-field rec-field--full">
            <span class="rec-field__label">异常原因</span>
            <span class="rec-field__val rec-field__val--danger">{{ selectedRecord.abnormalReason }}</span>
          </div>
        </div>
      </template>

      <template #foot>
        <template v-if="selectedOrder">
          <span class="op__operator">执行人：{{ auth.user.name }}</span>
          <CButton
            variant="primary"
            :disabled="!allChecked"
            v-perm.disable="'writeoff:create'"
            @click="confirmWriteoff"
          >
            <CIcon name="check-square" :size="16" />
            确认核销（扣 {{ selectedOrder.items[0]?.qty }} 次）
          </CButton>
        </template>
      </template>
    </CWorkbenchShell>
  </div>
</template>

<style scoped>
.wo { display: flex; flex-direction: column; gap: var(--s-lg); }
.wo-detail-label { font-size: var(--t-md); font-weight: 700; color: var(--c-text); }
.wb-head { display: flex; justify-content: space-between; align-items: center; gap: var(--s-sm); }

.tabs { display: flex; border-bottom: 1px solid var(--c-border); flex-shrink: 0; }
.tab {
  flex: 1; padding: var(--s-md) var(--s-sm); font-size: var(--t-sm);
  color: var(--c-text-3); background: none; border: none; cursor: pointer;
  border-bottom: 2px solid transparent; transition: all .15s;
}
.tab--active { color: var(--c-brand); border-bottom-color: var(--c-brand); font-weight: 600; }

.list { flex: 1; min-height: 0; overflow-y: auto; }
.empty {
  display: flex; flex-direction: column; align-items: center; gap: var(--s-sm);
  padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); font-size: var(--t-sm);
}
.empty__icon { color: var(--c-text-4); }

.ord {
  display: block; width: 100%; text-align: left; padding: var(--s-md) var(--s-lg);
  background: none; border: none; border-bottom: 1px solid var(--c-border-light);
  cursor: pointer; transition: background .15s;
}
.ord:hover { background: var(--c-brand-soft); }
.ord--active { background: var(--c-brand-soft); }
.ord__top { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--s-xs); }
.ord__no { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.ord__cust { font-size: var(--t-sm); color: var(--c-text); margin-bottom: 2px; }
.ord__proj { font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: var(--s-xs); }
.ord__meta { display: flex; justify-content: space-between; font-size: var(--t-xs); color: var(--c-text-3); }

.rec {
  display: block; width: 100%; text-align: left;
  padding: var(--s-md) var(--s-lg); border-bottom: 1px solid var(--c-border-light);
  background: none; border-left: none; border-right: none; border-top: none;
  cursor: pointer; transition: background .15s;
}
.rec:hover { background: var(--c-brand-soft); }
.rec--active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.rec__top { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--s-xs); }
.rec__no { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.rec__proj { font-size: var(--t-sm); color: var(--c-text); margin-bottom: 2px; }
.rec__meta { display: flex; justify-content: space-between; font-size: var(--t-xs); color: var(--c-text-3); }
.rec__abn {
  margin-top: var(--s-xs); padding: var(--s-xs) var(--s-sm);
  background: var(--c-danger-bg); color: var(--c-danger-fg);
  border-radius: var(--r-md); font-size: var(--t-xs); line-height: 1.5;
}

.rec-detail { display: flex; flex-direction: column; gap: var(--s-sm); margin-top: var(--s-md); }
.rec-field { display: flex; justify-content: space-between; align-items: center; padding: var(--s-sm) var(--s-md); background: var(--c-bg-page); border-radius: var(--r-md); }
.rec-field--full { flex-direction: column; align-items: flex-start; gap: 4px; }
.rec-field__label { font-size: var(--t-xs); color: var(--c-text-3); }
.rec-field__val { font-size: var(--t-sm); color: var(--c-text); font-weight: 500; }
.rec-field__val--danger { color: var(--c-danger-fg); }

.cust { padding-bottom: var(--s-md); border-bottom: 1px solid var(--c-border-light); }
.cust__name { font-size: var(--t-lg); font-weight: 700; color: var(--c-text); }
.cust__sub { font-size: var(--t-sm); color: var(--c-text-3); margin-top: 2px; }

.sec-title {
  font-size: var(--t-sm); font-weight: 600; color: var(--c-text);
  margin: var(--s-lg) 0 var(--s-sm);
}
.items { display: flex; flex-direction: column; gap: var(--s-sm); }
.item {
  display: flex; justify-content: space-between; align-items: center;
  padding: var(--s-sm) var(--s-md); background: var(--c-bg-page);
  border-radius: var(--r-md);
}
.item__main { display: flex; flex-direction: column; gap: 2px; }
.item__name { font-size: var(--t-sm); color: var(--c-text); }
.item__spec { font-size: var(--t-xs); color: var(--c-text-3); }
.item__right { display: flex; gap: var(--s-md); align-items: center; }
.item__qty { font-size: var(--t-sm); color: var(--c-text-3); }
.item__price { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.total {
  display: flex; justify-content: space-between; align-items: center;
  margin-top: var(--s-md); padding-top: var(--s-md); border-top: 1px solid var(--c-border-light);
}
.total__amt { font-size: var(--t-xl); font-weight: 700; color: var(--c-brand); }

.checks { display: flex; flex-direction: column; gap: var(--s-xs); }
.check {
  display: flex; align-items: center; gap: var(--s-sm); width: 100%;
  padding: var(--s-sm) var(--s-md); background: none; border: 1px solid var(--c-border);
  border-radius: var(--r-md); cursor: pointer; text-align: left;
  font-size: var(--t-sm); color: var(--c-text); transition: all .15s;
}
.check:hover { border-color: var(--c-brand); }
.check--on { background: var(--c-success-bg); border-color: var(--c-success-fg); }
.check__box { color: var(--c-text-3); flex-shrink: 0; }
.check__square {
  width: 18px; height: 18px; flex-shrink: 0;
  border: 1.5px solid var(--c-border); border-radius: 4px; box-sizing: border-box;
}
.check--on .check__box { color: var(--c-success-fg); }

.op__operator { font-size: var(--t-sm); color: var(--c-text-3); margin-right: auto; }
</style>
