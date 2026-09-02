<script setup lang="ts">
/* ============================================================
 * M4-15 收款收银（/order）
 * 列出待收款订单 → 选支付方式（支持组合支付/找零）→ 收齐置 PAID。
 * 数据源：txn-service 真实 API（listOrders / payOrder 组合支付+现金找零）。
 * 订单两个合规来源：医生签病历自动生成（诊疗）/ 零售页 /prescription 直开；
 * 本页「只收款、不开单」（现场开单统一走零售页）。
 * 金额：后端存「分」，本页适配层转「元」供模板展示；权限 cashier:view/create/sign。
 * 样式/模板沿用原版（backup-views-0830-0001），仅替换数据源，未改布局与交互。
 * ============================================================ */
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useStoreContext } from '@/stores/storeContext'
import { useToast } from '@/composables/useToast'
import { listOrders, payOrder, type OrderViewDTO, type OrderPaymentDTO } from '@/api/order'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'

type PayMethod = 'cash' | 'card' | 'wxpay' | 'alipay' | 'balance'
type UiStatus = 'PENDING_SIGN' | 'PENDING_PAY' | 'PAID' | 'CANCELLED'

interface UiPayment {
  method: PayMethod
  amount: number // 元（入账额）
}
interface UiItem {
  name: string
  spec?: string
  qty: number
  price: number // 元（单价）
}
interface UiOrder {
  id: string
  orderNo: string
  customerId: string
  customerName: string
  consultantId?: string
  consultId?: string
  items: UiItem[]
  amount: number // 元（应收）
  signTier: 'L1' | 'L2' | 'L3'
  status: UiStatus
  createdAt: string
  payments: UiPayment[]
  cashierName?: string
}

const router = useRouter()
const auth = useAuthStore()
const storeCtx = useStoreContext()
const toast = useToast()

const orders = ref<UiOrder[]>([])
const paying = ref(false)

// ---- 单位 / 状态适配（后端「分」+ 中文状态 → 模板「元」+ mock 状态码）----
const fen2yuan = (f: number | null | undefined) => (f == null ? 0 : f / 100)
function mapStatus(s: string): UiStatus {
  if (s === '待收款') return 'PENDING_PAY'
  if (s === '待签核') return 'PENDING_SIGN'
  if (s === '已收款') return 'PAID'
  return 'CANCELLED'
}
function adapt(d: OrderViewDTO): UiOrder {
  const pays: UiPayment[] = (d.payments || []).map((p: OrderPaymentDTO) => ({
    method: (p.payMethod as PayMethod) || 'wxpay',
    amount: fen2yuan(p.postedAmount),
  }))
  return {
    id: d.orderNo,
    orderNo: d.orderNo,
    customerId: d.customerId,
    customerName: d.customerName || d.customerId,
    items: (d.items || []).map((it) => ({
      name: it.itemName,
      qty: it.qty,
      price: fen2yuan(it.unitPrice),
    })),
    amount: fen2yuan(d.amount),
    signTier: fen2yuan(d.amount) >= 50000 ? 'L2' : 'L1',
    status: mapStatus(d.status),
    createdAt: d.createdAt || '',
    payments: pays,
  }
}

async function load() {
  try {
    if (!storeCtx.loaded) await storeCtx.loadStores()
    // 收银台：当前门店，取待收款 + 近期已收款（KPI 今日已收/已完成用）
    const [pend, paid] = await Promise.all([
      listOrders({ size: 100, status: '待收款', storeCode: storeCtx.currentStoreCode }),
      listOrders({ size: 50, status: '已收款', storeCode: storeCtx.currentStoreCode }),
    ])
    orders.value = [...pend.data.content, ...paid.data.content].map(adapt)
  } catch (e: any) {
    toast.error('收银台数据加载失败：' + (e?.response?.data?.message || e?.message || '网络异常'))
  }
}

onMounted(load)
watch(() => storeCtx.currentStoreCode, () => load())

// ---- 模板所需的 store 形状适配（保持原版模板零改动）----
const customer = {
  nameOf: (customerId: string) =>
    orders.value.find((o) => o.customerId === customerId)?.customerName || customerId,
}
function get(id: string) {
  return orders.value.find((o) => o.id === id)
}
function paidAmount(o: UiOrder) {
  return o.payments.reduce((s, p) => s + p.amount, 0)
}
// 列表行项目摘要：首项名，多项目带「等N项」（对齐核销台 ord__proj 项目行，不丢项目信息）
function itemSummary(o: UiOrder) {
  if (!o.items.length) return '无项目'
  const first = o.items[0].name
  return o.items.length > 1 ? `${first} 等 ${o.items.length} 项` : first
}
const order = {
  orders,
  get,
  paidAmount,
  approveSign: (_id: string) => {
    toast.error('高金额订单双签确认请走订单确认流程（本期诊疗/零售单直开待收款，无待签核单）')
    return false
  },
}

const canCashier = computed(() => auth.can('cashier:create'))
const canSign = computed(() => auth.can('cashier:sign'))

const PAY_METHODS: { key: PayMethod; label: string; icon: 'pos' | 'card' | 'phone' | 'finance'; desc: string }[] = [
  { key: 'wxpay', label: '微信支付', icon: 'phone', desc: '扫码 / 付款码' },
  { key: 'alipay', label: '支付宝', icon: 'phone', desc: '扫码 / 付款码' },
  { key: 'card', label: '银行卡', icon: 'card', desc: 'POS 刷卡' },
  { key: 'balance', label: '会员储值', icon: 'finance', desc: '余额扣款' },
  { key: 'cash', label: '现金', icon: 'pos', desc: '需双人复核' },
]
const METHOD_LABEL: Record<PayMethod, string> = {
  wxpay: '微信支付', alipay: '支付宝', card: '银行卡', balance: '会员储值', cash: '现金',
}

// ---- 选中订单 ----
const pending = computed(() => orders.value.filter((o) => o.status === 'PENDING_PAY'))
const signPending = computed(() => orders.value.filter((o) => o.status === 'PENDING_SIGN'))
const doneOrders = computed(() => orders.value.filter((o) => o.status === 'PAID'))

// 左侧列表 tab：待收款 / 已完成（分段控件与咨询/医师工作台统一；收款完成后进「已完成」归档）
const listTab = ref<'pending' | 'done'>('pending')

const selectedId = ref('')
const selected = computed(() => get(selectedId.value) || pending.value[0] || signPending.value[0])
function selectOrder(id: string) {
  selectedId.value = id
  inputAmt.value = ''
  activeMethod.value = 'wxpay'
}

// ---- 支付录入 ----
const activeMethod = ref<PayMethod>('wxpay')
const inputAmt = ref('')
const received = computed(() => (selected.value ? paidAmount(selected.value) : 0))
const rest = computed(() => (selected.value ? selected.value.amount - received.value : 0))
const done = computed(() => !!selected.value && rest.value <= 0)

// 现金找零：现金支付时录入"客户实付"，超出应收即找零
const cashReceived = computed(() => {
  if (activeMethod.value !== 'cash') return 0
  const n = Number(inputAmt.value) || 0
  return n > rest.value ? n : 0
})
const change = computed(() => Math.max(0, cashReceived.value - rest.value))

function effectiveAmount(): number {
  const n = Number(inputAmt.value) || 0
  if (n <= 0) return 0
  // 现金录入的是"客户给的钱"，入账按待收封顶（找零另算）
  if (activeMethod.value === 'cash') return Math.min(n, Math.ceil(rest.value))
  return Math.min(n, Math.round(rest.value * 100) / 100)
}

async function addPayment() {
  if (!selected.value || !canCashier.value || paying.value) return
  const amt = effectiveAmount()
  if (amt <= 0) return
  const target = selected.value
  // 现金传"客户实付"（后端按待收封顶入账并计算找零）；非现金传实际扣款额
  const tenderedYuan = activeMethod.value === 'cash' ? (Number(inputAmt.value) || 0) : amt
  paying.value = true
  try {
    const res = await payOrder(
      target.orderNo,
      activeMethod.value,
      Math.round(tenderedYuan * 100),
      auth.user.staffId || 'cashier',
    )
    if (res.data.changeAmount > 0) {
      toast.success(`收款成功，现金找零 ¥${(res.data.changeAmount / 100).toLocaleString('zh-CN')}`)
    } else if (res.data.completed) {
      toast.success(`订单 ${target.orderNo} 收款完成`)
    } else {
      toast.success(`部分收款成功，待收 ¥${Math.max(0, (res.data.orderAmount - res.data.paidAmount) / 100).toLocaleString('zh-CN')}`)
    }
    inputAmt.value = ''
    await load()
  } catch (e: any) {
    toast.error('收款失败：' + (e?.response?.data?.message || e?.message || '网络异常'))
  } finally {
    paying.value = false
  }
}

function quickFull() {
  if (!selected.value) return
  inputAmt.value = String(Math.ceil(rest.value))
}

function settle() {
  if (!done.value) return
  router.push('/writeoff')
}

const money = (n: number) => `¥${(n || 0).toLocaleString('zh-CN')}`

// ---- KPI 汇总 ----
const totalReceivable = computed(() =>
  orders.value.filter((o) => o.status !== 'CANCELLED').reduce((s, o) => s + o.amount, 0),
)
const totalPaid = computed(() =>
  orders.value.filter((o) => o.status === 'PAID').reduce((s, o) => s + o.amount, 0),
)
const paidCount = computed(() => orders.value.filter((o) => o.status === 'PAID').length)
</script>

<template>
  <div class="ca">
    <div class="ca__kpis">
      <CKpi :value="money(totalReceivable)" label="今日应收" tone="brand" icon="pos" />
      <CKpi :value="money(totalPaid)" label="今日已收" tone="teal" icon="pos" />
      <CKpi :value="String(pending.length)" label="待收款订单" tone="warning" icon="pos" />
      <CKpi :value="String(paidCount)" label="已完成订单" tone="text" icon="order" />
    </div>

    <!-- 收款金额条 -->
    <section class="banner" :class="{ 'banner--done': done }">
      <div class="banner__block">
        <span class="banner__label">应收金额</span>
        <strong class="banner__value">{{ selected ? money(selected.amount) : '—' }}</strong>
      </div>
      <div class="banner__sep" />
      <div class="banner__block">
        <span class="banner__label">已收</span>
        <strong class="banner__value banner__value--ok">{{ money(received) }}</strong>
      </div>
      <div class="banner__sep" />
      <div class="banner__block">
        <span class="banner__label">待收</span>
        <strong class="banner__value banner__value--rest">{{ money(Math.max(rest, 0)) }}</strong>
      </div>
      <CStatusPill :status="done ? 'success' : selected?.status === 'PENDING_SIGN' ? 'default' : 'warning'">
        {{ done ? '已收齐，可结算' : selected?.status === 'PENDING_SIGN' ? '待签核' : '待收齐' }}
      </CStatusPill>
    </section>

    <div class="ca__grid">
      <!-- 左：订单列表（待收款 / 已完成 tab，样式与核销台 WriteoffView / 退款页 RefundView 统一） -->
      <CCard class="ca__orders" padding="none">
        <div class="tabs">
          <button class="tab" :class="{ 'tab--active': listTab === 'pending' }" @click="listTab = 'pending'">
            待收款 ({{ pending.length }})
          </button>
          <button class="tab" :class="{ 'tab--active': listTab === 'done' }" @click="listTab = 'done'">
            已完成 ({{ doneOrders.length }})
          </button>
        </div>

        <div class="list">
          <!-- 待收款 -->
          <template v-if="listTab === 'pending'">
            <div v-if="signPending.length" class="sign-group">
              <div class="sign-group__title">待签核（高金额）</div>
              <button
                v-for="o in signPending"
                :key="o.id"
                class="ord"
                :class="{ 'ord--active': selected?.id === o.id }"
                @click="selectOrder(o.id)"
              >
                <div class="ord__top">
                  <span class="ord__no">{{ o.orderNo }}</span>
                  <CStatusPill status="warning">{{ o.signTier }}</CStatusPill>
                </div>
                <div class="ord__cust">{{ customer.nameOf(o.customerId) }} · {{ o.items.length }} 项</div>
                <div class="ord__proj">{{ itemSummary(o) }}</div>
                <div class="ord__amt">{{ money(o.amount) }}</div>
              </button>
            </div>

            <div v-if="!pending.length && !signPending.length" class="empty">
              <CIcon name="pos" :size="28" class="empty__icon" />
              <div>暂无待收款订单</div>
            </div>
            <button
              v-for="o in pending"
              :key="o.id"
              class="ord"
              :class="{ 'ord--active': selected?.id === o.id }"
              @click="selectOrder(o.id)"
            >
              <div class="ord__top">
                <span class="ord__no">{{ o.orderNo }}</span>
                <CStatusPill status="warning">待收</CStatusPill>
              </div>
              <div class="ord__cust">{{ customer.nameOf(o.customerId) }} · {{ o.signTier }}</div>
              <div class="ord__proj">{{ itemSummary(o) }}</div>
              <div class="ord__amt">{{ money(o.amount) }}</div>
            </button>
          </template>

          <!-- 已完成 -->
          <template v-else>
            <div v-if="!doneOrders.length" class="empty">
              <CIcon name="check-square" :size="28" class="empty__icon" />
              <div>暂无已完成订单</div>
            </div>
            <button
              v-for="o in doneOrders"
              :key="o.id"
              class="ord"
              :class="{ 'ord--active': selected?.id === o.id }"
              @click="selectOrder(o.id)"
            >
              <div class="ord__top">
                <span class="ord__no">{{ o.orderNo }}</span>
                <CStatusPill status="success">已收</CStatusPill>
              </div>
              <div class="ord__cust">{{ customer.nameOf(o.customerId) }} · {{ o.items.length }} 项</div>
              <div class="ord__proj">{{ itemSummary(o) }}</div>
              <div class="ord__amt">{{ money(o.amount) }}</div>
            </button>
          </template>
        </div>
      </CCard>

      <!-- 中：支付方式 + 明细 -->
      <CCard title="收款明细" class="ca__pay">
        <template v-if="selected">
          <div class="cust">
            <span class="cust__avatar">{{ customer.nameOf(selected.customerId)[0] }}</span>
            <div class="cust__meta">
              <div class="cust__name">{{ customer.nameOf(selected.customerId) }}
                <span class="cust__id">{{ selected.customerId }}</span>
              </div>
              <div class="cust__sub">订单 {{ selected.orderNo }}</div>
            </div>
          </div>

          <div class="lines">
            <div v-for="(it, i) in selected.items" :key="i" class="line">
              <span class="line__name">{{ it.name }}<em v-if="it.spec"> · {{ it.spec }}</em></span>
              <span class="line__qty">×{{ it.qty }}</span>
              <span class="line__amt">{{ money(it.qty * it.price) }}</span>
            </div>
          </div>

          <!-- 已支付记录 -->
          <div v-if="selected.payments.length" class="paid-list">
            <div v-for="(p, i) in selected.payments" :key="i" class="paid-row">
              <CIcon name="check" :size="14" class="paid-row__ok" />
              <span class="paid-row__m">{{ METHOD_LABEL[p.method] }}</span>
              <span class="paid-row__a">{{ money(p.amount) }}</span>
            </div>
          </div>

          <!-- 待签核：签核按钮 -->
          <div v-if="selected.status === 'PENDING_SIGN'" class="signbox">
            <p class="signbox__hint">该订单为 {{ selected.signTier }} 层级，需签核通过后方可收款。</p>
            <CButton
              v-perm.disable="'cashier:sign'"
              variant="primary"
              block
              @click="order.approveSign(selected.id)"
            >{{ canSign ? `${selected.signTier} 签核通过` : '无签核权限' }}</CButton>
          </div>

          <!-- 收款录入 -->
          <div v-else-if="!done" class="entry">
            <div class="methods">
              <button
                v-for="m in PAY_METHODS"
                :key="m.key"
                class="method"
                :class="{ 'is-on': activeMethod === m.key }"
                :disabled="!canCashier"
                @click="activeMethod = m.key"
              >
                <CIcon :name="m.icon" :size="18" class="method__ic" />
                <span class="method__label">{{ m.label }}</span>
              </button>
            </div>
            <div class="entry__row">
              <CInput v-model="inputAmt" type="number" :placeholder="`收款金额，待收 ${money(Math.max(rest, 0))}`" />
              <CButton variant="secondary" @click="quickFull">全额</CButton>
            </div>
            <div v-if="activeMethod === 'cash' && cashReceived > 0" class="change">
              客户实付 {{ money(cashReceived) }} · 找零 <strong>{{ money(change) }}</strong>
            </div>
            <CButton
              v-perm.disable="'cashier:create'"
              variant="primary"
              block
              :disabled="effectiveAmount() <= 0 || paying"
              @click="addPayment"
            >确认收款 {{ money(effectiveAmount()) }}</CButton>
            <p v-if="!canCashier" class="no-perm">当前角色无收银权限。</p>
          </div>

          <div v-else class="donebox">
            <CIcon name="check-square" :size="28" class="donebox__ic" />
            <div class="donebox__text">
              <strong>收款完成</strong>
              <span>实收 {{ money(selected.amount) }} · 收银员 {{ selected.cashierName || auth.user.name }}</span>
            </div>
            <!-- 医美诊疗单：收款即解锁治疗，下一步回医师台术前核对；零售/药妆单走核销 -->
            <CButton v-if="selected.consultId" variant="primary" size="sm" @click="router.push(`/doctor?fromConsult=${selected.consultId}`)">
              已解锁治疗 · 前往治疗
            </CButton>
            <CButton v-else variant="primary" size="sm" @click="settle">前往核销</CButton>
          </div>
        </template>
        <div v-else class="empty">请选择左侧订单</div>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.ca { display: flex; flex-direction: column; gap: var(--s-md); }
.ca__kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); }

.banner { display: flex; align-items: center; gap: var(--s-lg); padding: var(--s-md) var(--s-lg); background: var(--c-warning-bg); border: 1px solid var(--c-warning-fg); border-radius: var(--r-lg); }
.banner--done { background: var(--c-success-bg); border-color: var(--c-success-fg); }
.banner__block { display: flex; flex-direction: column; gap: 2px; }
.banner__label { font-size: var(--t-xs); color: var(--c-text-3); }
.banner__value { font-size: var(--t-xxl); font-weight: 700; color: var(--c-text); line-height: 1.1; }
.banner__value--ok { color: var(--c-success-fg); }
.banner__value--rest { color: var(--c-warning-fg); }
.banner--done .banner__value--rest { color: var(--c-success-fg); }
.banner__sep { width: 1px; align-self: stretch; background: var(--c-border); }

.ca__grid { display: grid; grid-template-columns: 320px 1fr; gap: var(--s-md); align-items: start; }

/* 左侧订单卡：padding="none" 让 tab 贴顶横贯，与核销台/退款页列表卡一致 */
.ca__orders { display: flex; flex-direction: column; }

/* 列表状态 tab —— 照抄核销台 WriteoffView / 退款页 RefundView 的 .tabs/.tab/.tab--active */
.tabs { display: flex; border-bottom: 1px solid var(--c-border); flex-shrink: 0; }
.tab {
  flex: 1; padding: var(--s-md) var(--s-sm); font-size: var(--t-sm);
  color: var(--c-text-3); background: none; border: none; cursor: pointer;
  border-bottom: 2px solid transparent; transition: all .15s;
}
.tab--active { color: var(--c-brand); border-bottom-color: var(--c-brand); font-weight: 600; }

/* 订单列表：扁平分隔行，照抄核销台 WriteoffView 的 .list/.ord/.empty（无方框、无卡片间距） */
.list { flex: 1; min-height: 0; overflow-y: auto; max-height: calc(100vh - 330px); }
.empty {
  display: flex; flex-direction: column; align-items: center; gap: var(--s-sm);
  padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); font-size: var(--t-sm);
}
.empty__icon { color: var(--c-text-4); }

.sign-group__title { font-size: var(--t-xs); color: var(--c-text-3); padding: var(--s-md) var(--s-lg) var(--s-xs); }

.ord {
  display: block; width: 100%; text-align: left; padding: var(--s-md) var(--s-lg);
  background: none; border: none; border-bottom: 1px solid var(--c-border-light);
  cursor: pointer; transition: background .15s;
}
.ord:hover { background: var(--c-brand-soft); }
.ord--active { background: var(--c-brand-soft); }
.ord__top { display: flex; align-items: center; justify-content: space-between; margin-bottom: var(--s-xs); }
.ord__no { font-weight: 600; font-size: var(--t-sm); color: var(--c-text); }
.ord__cust { font-size: var(--t-sm); color: var(--c-text); margin-bottom: 2px; }
.ord__proj { font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: var(--s-xs); }
.ord__amt { font-size: var(--t-lg); font-weight: 700; color: var(--c-brand); margin-top: 4px; }

.cust { display: flex; align-items: center; gap: var(--s-sm); padding-bottom: var(--s-sm); border-bottom: 1px solid var(--c-border-light); }
.cust__avatar { width: 38px; height: 38px; border-radius: var(--r-avatar); background: var(--c-brand-soft); color: var(--c-brand); font-weight: 600; display: flex; align-items: center; justify-content: center; font-size: var(--t-lg); }
.cust__name { font-weight: 600; display: flex; align-items: center; gap: var(--s-xs); }
.cust__id { font-size: var(--t-xs); color: var(--c-text-3); font-weight: 400; }
.cust__sub { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }

.lines { margin: var(--s-sm) 0; display: flex; flex-direction: column; gap: var(--s-xs); }
.line { display: flex; align-items: center; gap: var(--s-sm); font-size: var(--t-sm); padding: 4px 0; }
.line__name { flex: 1; color: var(--c-text-2); }
.line__name em { font-style: normal; color: var(--c-text-3); font-size: var(--t-xs); }
.line__qty { color: var(--c-text-3); }
.line__amt { font-weight: 600; min-width: 80px; text-align: right; }

.paid-list { display: flex; flex-direction: column; gap: 4px; margin: var(--s-xs) 0; padding: var(--s-sm); background: var(--c-bg-page); border-radius: var(--r-md); }
.paid-row { display: flex; align-items: center; gap: var(--s-xs); font-size: var(--t-sm); }
.paid-row__ok { color: var(--c-success-fg); }
.paid-row__m { flex: 1; color: var(--c-text-2); }
.paid-row__a { font-weight: 600; color: var(--c-success-fg); }

.signbox { margin-top: var(--s-md); padding-top: var(--s-md); border-top: 1px solid var(--c-border); }
.signbox__hint { font-size: var(--t-sm); color: var(--c-warning-fg); margin-bottom: var(--s-sm); }

.entry { margin-top: var(--s-md); padding-top: var(--s-md); border-top: 1px solid var(--c-border); display: flex; flex-direction: column; gap: var(--s-sm); }
.methods { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--s-xs); }
.method { display: flex; flex-direction: column; align-items: center; gap: 4px; padding: var(--s-sm) var(--s-xs); border: 1px solid var(--c-border); border-radius: var(--r-md); background: var(--c-surface); cursor: pointer; color: var(--c-text-2); transition: all .15s; }
.method:disabled { opacity: .5; cursor: not-allowed; }
.method.is-on { border-color: var(--c-brand); background: var(--c-brand-soft); color: var(--c-brand); }
.method__ic { color: var(--c-brand); }
.method__label { font-size: var(--t-xs); }
.entry__row { display: flex; gap: var(--s-xs); }
.entry__row :deep(.c-input) { flex: 1; }
.change { font-size: var(--t-sm); color: var(--c-text-2); background: var(--c-bg-page); padding: var(--s-xs) var(--s-sm); border-radius: var(--r-md); }
.change strong { color: var(--c-brand); }
.no-perm { color: var(--c-danger-fg); font-size: var(--t-xs); text-align: center; margin: 0; }

.donebox { display: flex; align-items: center; gap: var(--s-sm); margin-top: var(--s-md); padding: var(--s-md); background: var(--c-success-bg); border: 1px solid var(--c-success-fg); border-radius: var(--r-lg); }
.donebox__ic { color: var(--c-success-fg); flex-shrink: 0; }
.donebox__text { flex: 1; display: flex; flex-direction: column; font-size: var(--t-sm); }
.donebox__text span { color: var(--c-text-3); font-size: var(--t-xs); }

@media (max-width: 1024px) {
  .ca__kpis { grid-template-columns: repeat(2, 1fr); }
  .ca__grid { grid-template-columns: 1fr; }
  .banner { flex-wrap: wrap; gap: var(--s-md); }
  .banner__sep { display: none; }
}
</style>
