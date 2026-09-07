<script setup lang="ts">
/* ============================================================
 * 卡项疗程（/card-course）
 * 真实数据（B16 去 mock）：
 *  - 客户：customer-service listCustomers（分页/关键词搜索）
 *  - 卡资产：listCustomerCards（member_card，金额单位分）
 *  - 卡流水：listCardLedger（card_ledger，RECHARGE/CONSUME/REFUND/ADJUST）
 *  - 售卡开卡：createCardOrder（/txn/card-order）→ payOrder 收款（禁 balance）
 *    收齐后 txn 同事务调 customer 开卡并落首笔 RECHARGE 流水
 *  - 充值：rechargeCard（/customer/cards/{cardNo}/recharge）
 *  - 去划扣（B17）：跳划扣执行台 /m2-writeoff-desk，query 带出客户/卡预填「直接到店建单」，
 *    复用划扣台双签流程，不绕开合规双签；核销项目由现场操作员填写。
 * 权限：course:view 查看；写操作 course:edit。
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CKpi from '@/components/CKpi.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CProgressBar from '@/components/CProgressBar.vue'
import CSelect from '@/components/CSelect.vue'
import CIcon from '@/components/CIcon.vue'
import { useAuthStore } from '@/stores/auth'
import { useStoreContext } from '@/stores/storeContext'
import { useToast } from '@/composables/useToast'
import { listCustomers, searchCustomers, listCustomerCards, listCardLedger, rechargeCard,
  type CustomerDTO, type MemberCardDTO, type CardLedgerDTO } from '@/api/customer'
import { listCatalog, type CatalogProductDTO } from '@/api/catalog'
import { createCardOrder, payOrder, type OrderViewDTO, type PayResultDTO } from '@/api/order'

const auth = useAuthStore()
const storeCtx = useStoreContext()
const toast = useToast()
const router = useRouter()

// ---------------- 数据加载 ----------------
const loading = ref(false)
const customers = ref<CustomerDTO[]>([])
/** customerId → 卡列表（懒加载缓存） */
const cardsCache = ref<Record<string, MemberCardDTO[]>>({})

async function loadCardsOf(customerId: string, force = false): Promise<MemberCardDTO[]> {
  if (!force && cardsCache.value[customerId]) return cardsCache.value[customerId]
  try {
    const res = await listCustomerCards(customerId)
    cardsCache.value = { ...cardsCache.value, [customerId]: res.data ?? [] }
    return res.data ?? []
  } catch {
    // 单客户卡失败不阻断列表：按无卡处理
    cardsCache.value = { ...cardsCache.value, [customerId]: [] }
    return []
  }
}

async function load() {
  loading.value = true
  try {
    const res = await listCustomers({ storeCode: storeCtx.currentStoreCode, size: 200 })
    customers.value = res.data.content ?? []
    // 并行预热卡缓存（KPI/列表余额角标用；失败按无卡容错）
    await Promise.all(customers.value.map((c) => loadCardsOf(c.customerId)))
  } catch (e: any) {
    toast.error('客户/卡资产加载失败：' + (e?.response?.data?.message || e?.message || '网络异常'))
  } finally {
    loading.value = false
  }
}

onMounted(load)

// ---------------- 左列客户 ----------------
const keyword = ref('')
const selectedId = ref('')
const searchResults = ref<CustomerDTO[] | null>(null)
let searchTimer: ReturnType<typeof setTimeout> | undefined

function onKeywordInput() {
  if (searchTimer) clearTimeout(searchTimer)
  const q = keyword.value.trim()
  if (!q) { searchResults.value = null; return }
  searchTimer = setTimeout(async () => {
    try {
      // /customer/search 按姓名/手机号模糊（真实端点）；失败回落分页关键词搜索
      const res = await searchCustomers(q)
      const list = res.data ?? []
      searchResults.value = list.length ? list.slice(0, 20) : null
      if (!list.length) {
        const page = await listCustomers({ keyword: q, size: 20 })
        searchResults.value = page.data.content ?? []
      }
      void Promise.all((searchResults.value ?? []).map((c) => loadCardsOf(c.customerId)))
    } catch {
      try {
        const page = await listCustomers({ keyword: q, size: 20 })
        searchResults.value = page.data.content ?? []
        void Promise.all((searchResults.value ?? []).map((c) => loadCardsOf(c.customerId)))
      } catch {
        searchResults.value = []
      }
    }
  }, 300)
}

const custList = computed<CustomerDTO[]>(() => searchResults.value ?? customers.value)

function cardsOf(id: string): MemberCardDTO[] {
  return cardsCache.value[id] ?? []
}
function balanceOf(id: string): number {
  return cardsOf(id)
    .filter((c) => (c.cardType || 'CARD') !== 'COURSE' && c.status === '在用')
    .reduce((s, c) => s + c.balance + (c.giftBalance ?? 0), 0)
}
function timesOf(id: string): number {
  return cardsOf(id)
    .filter((c) => c.cardType === 'COURSE' && c.status === '在用')
    .reduce((s, c) => s + (c.remainTimes ?? 0), 0)
}

async function select(c: CustomerDTO) {
  selectedId.value = c.customerId
  await loadCardsOf(c.customerId, true)
}
const selected = computed(() => customers.value.find((c) => c.customerId === selectedId.value)
  ?? (searchResults.value ?? []).find((c) => c.customerId === selectedId.value))
const selectedCards = computed(() => (selectedId.value ? cardsOf(selectedId.value) : []))
const cashCards = computed(() => selectedCards.value.filter((c) => (c.cardType || 'CARD') !== 'COURSE'))
const courseCards = computed(() => selectedCards.value.filter((c) => c.cardType === 'COURSE'))
const totalBalance = computed(() => cashCards.value
  .filter((c) => c.status === '在用')
  .reduce((s, c) => s + c.balance + (c.giftBalance ?? 0), 0))
const totalRemainingTimes = computed(() => courseCards.value
  .filter((c) => c.status === '在用')
  .reduce((s, c) => s + (c.remainTimes ?? 0), 0))

// KPI（基于已加载客户的卡缓存；门店视角近似值）
const kpi = computed(() => {
  let balance = 0
  let courses = 0
  let finished = 0
  const withCards = new Set<string>()
  Object.entries(cardsCache.value).forEach(([cid, cards]) => {
    cards.forEach((c) => {
      if (c.status !== '在用') {
        if (c.cardType === 'COURSE') finished += 1
        return
      }
      withCards.add(cid)
      if (c.cardType === 'COURSE') courses += 1
      else balance += c.balance + (c.giftBalance ?? 0)
    })
  })
  return { customers: withCards.size, balance, courses, finished }
})

// ---------------- 卡流水 ----------------
const ledgerRows = ref<CardLedgerDTO[]>([])
const ledgerCardNo = ref('')
const ledgerCardItem = ref('')

async function openLedger(c: MemberCardDTO) {
  panel.value = 'ledger'
  ledgerCardNo.value = c.cardNo
  ledgerCardItem.value = c.cardItem
  ledgerRows.value = []
  try {
    const res = await listCardLedger(c.cardNo)
    // 后端账龄正序，倒序展示（最近一笔在最上）
    ledgerRows.value = (res.data ?? []).slice().sort((a, b) => b.ledgerId - a.ledgerId)
  } catch (e: any) {
    toast.error('卡流水加载失败：' + (e?.response?.data?.message || e?.message || '网络异常'))
  }
}

const LEDGER_TYPE_TEXT: Record<string, string> = {
  RECHARGE: '充值/开卡', CONSUME: '消费扣款', REFUND: '退卡退款', ADJUST: '人工调整',
}
function ledgerTypeText(t: string) {
  return LEDGER_TYPE_TEXT[t] ?? t
}
function ledgerColor(t: string) {
  if (t === 'RECHARGE') return 'var(--c-success-fg)'
  if (t === 'CONSUME' || t === 'REFUND') return 'var(--c-danger-fg)'
  return 'var(--c-text-2)'
}
function ledgerSign(amt: number) {
  if (amt > 0) return '+'
  return ''
}

// ---------------- 操作弹层 ----------------
type Panel = null | 'purchase' | 'recharge' | 'ledger'
type BuyStep = 'form' | 'pay'
const panel = ref<Panel>(null)
const busy = ref(false)

function close() { panel.value = null }

// ---- 售卡开卡 ----
const buyCustomerKeyword = ref('')
const buyResults = ref<CustomerDTO[]>([])
const buyCustomer = ref<CustomerDTO | null>(null)
let buySearchTimer: ReturnType<typeof setTimeout> | undefined
const templates = ref<CatalogProductDTO[]>([])
const tplKeyword = ref('')
const pickedTpl = ref<CatalogProductDTO | null>(null)
const buyStep = ref<BuyStep>('form')
const pendingOrder = ref<OrderViewDTO | null>(null)
const payResult = ref<PayResultDTO | null>(null)
const payMethod = ref('wxpay')
const payAmt = ref('')

const PAY_METHODS = [
  { key: 'wxpay', label: '微信支付', icon: 'phone' },
  { key: 'alipay', label: '支付宝', icon: 'phone' },
  { key: 'card', label: '银行卡', icon: 'card' },
  { key: 'cash', label: '现金', icon: 'pos' },
]
// 售卡禁 balance（售卡本身是预收负债，余额买卡绕开资金闭环）；后端同样 400 拦截

const filteredTemplates = computed(() => {
  const q = tplKeyword.value.trim()
  const list = templates.value
  if (!q) return list
  return list.filter((t) => t.name.includes(q) || t.productCode.includes(q))
})

async function openPurchase() {
  if (!canSell.value) {
    toast.error('无售卡开卡权限（需处方/开单权限）')
    return
  }
  panel.value = 'purchase'
  buyStep.value = 'form'
  buyCustomerKeyword.value = ''
  buyResults.value = []
  buyCustomer.value = null
  tplKeyword.value = ''
  pickedTpl.value = null
  pendingOrder.value = null
  payResult.value = null
  payMethod.value = 'wxpay'
  payAmt.value = ''
  try {
    const res = await listCatalog({ storeCode: storeCtx.currentStoreCode, status: 'ON_SHELF' })
    templates.value = (res.data ?? []).filter((t) => t.productType === 'CARD' || t.productType === 'COURSE')
  } catch (e: any) {
    templates.value = []
    toast.error('在售卡项加载失败：' + (e?.response?.data?.message || e?.message || '网络异常'))
  }
}

function onBuySearchInput() {
  if (buySearchTimer) clearTimeout(buySearchTimer)
  const q = buyCustomerKeyword.value.trim()
  if (!q) { buyResults.value = []; return }
  buySearchTimer = setTimeout(async () => {
    try {
      const res = await searchCustomers(q)
      buyResults.value = (res.data ?? []).slice(0, 8)
      if (!buyResults.value.length) {
        const page = await listCustomers({ keyword: q, size: 8 })
        buyResults.value = page.data.content ?? []
      }
    } catch {
      buyResults.value = []
    }
  }, 300)
}

function pickBuyCustomer(c: CustomerDTO) {
  buyCustomer.value = c
  buyCustomerKeyword.value = `${c.name}（${maskPhone(c.phone)}）`
  buyResults.value = []
}

async function submitOrder() {
  if (!buyCustomer.value) { toast.error('请先搜索并选择购卡客户（须建档客户）'); return }
  if (!pickedTpl.value) { toast.error('请选择在售卡项模板'); return }
  busy.value = true
  try {
    const res = await createCardOrder({
      customerId: buyCustomer.value.customerId,
      storeCode: storeCtx.currentStoreCode,
      productCode: pickedTpl.value.productCode,
      consultant: auth.user.staffId || undefined,
      operator: auth.user.staffId || 'cashier',
    })
    pendingOrder.value = res.data
    payResult.value = null
    buyStep.value = 'pay'
    toast.success(`已开售卡单 ${res.data.orderNo}，待收款 ¥${(res.data.amount / 100).toLocaleString('zh-CN')}`)
  } catch (e: any) {
    toast.error('售卡开单失败：' + (e?.response?.data?.message || e?.message || '网络异常'))
  } finally {
    busy.value = false
  }
}

const paidAmount = computed(() => payResult.value?.paidAmount ?? 0)
const restAmount = computed(() => pendingOrder.value ? pendingOrder.value.amount - paidAmount.value : 0)
const cashChange = computed(() => {
  if (payMethod.value !== 'cash') return 0
  const n = Number(payAmt.value) || 0
  const restYuan = restAmount.value / 100
  return n > restYuan ? Math.round((n - restYuan) * 100) / 100 : 0
})
function effectivePayYuan(): number {
  const n = Number(payAmt.value) || 0
  if (n <= 0) return 0
  const restYuan = restAmount.value / 100
  if (payMethod.value === 'cash') return Math.min(n, Math.ceil(restYuan))
  return Math.min(n, Math.round(restYuan * 100) / 100)
}

async function submitPay() {
  if (!pendingOrder.value || busy.value) return
  const amt = effectivePayYuan()
  if (amt <= 0) { toast.error('请输入正确的收款金额'); return }
  busy.value = true
  try {
    const tendered = payMethod.value === 'cash' ? Math.round((Number(payAmt.value) || 0) * 100) : Math.round(amt * 100)
    const res = await payOrder(
      pendingOrder.value.orderNo,
      payMethod.value,
      tendered,
      auth.user.staffId || 'cashier',
    )
    payResult.value = res.data
    if (res.data.changeAmount > 0) {
      toast.success(`收款成功，现金找零 ¥${(res.data.changeAmount / 100).toLocaleString('zh-CN')}`)
    } else if (res.data.completed) {
      toast.success(`订单 ${pendingOrder.value.orderNo} 收款完成，卡已开通`)
    } else {
      toast.success(`部分收款成功，待收 ¥${Math.max(0, (res.data.orderAmount - res.data.paidAmount) / 100).toLocaleString('zh-CN')}`)
    }
    payAmt.value = ''
    if (res.data.completed) {
      const custId = buyCustomer.value?.customerId
      close()
      await load()
      if (custId) {
        await loadCardsOf(custId, true)
        selectedId.value = custId
      }
    }
  } catch (e: any) {
    toast.error('收款失败：' + (e?.response?.data?.message || e?.message || '网络异常'))
  } finally {
    busy.value = false
  }
}

// ---- 充值（已有储值卡） ----
const rechargeCardNo = ref('')
const rechargeCardItem = ref('')
const rechargeAmountYuan = ref('')
const rechargeMethod = ref('cash')
const RECHARGE_METHODS = [
  { value: 'cash', label: '现金' },
  { value: 'wxpay', label: '微信支付' },
  { value: 'alipay', label: '支付宝' },
  { value: 'card', label: '银行卡' },
]
function openRecharge(c: MemberCardDTO) {
  panel.value = 'recharge'
  rechargeCardNo.value = c.cardNo
  rechargeCardItem.value = c.cardItem
  rechargeAmountYuan.value = ''
  rechargeMethod.value = 'cash'
}
async function submitRecharge() {
  const fen = Math.round((Number(rechargeAmountYuan.value) || 0) * 100)
  if (fen <= 0) { toast.error('请输入正确的充值金额'); return }
  busy.value = true
  try {
    const res = await rechargeCard(rechargeCardNo.value, fen, rechargeMethod.value)
    toast.success(`充值成功，单号 ${res.data.bizRef}，卡余额 ¥${(res.data.balanceAfter / 100).toLocaleString('zh-CN')}`)
    close()
    await load()
    if (selectedId.value) await loadCardsOf(selectedId.value, true)
  } catch (e: any) {
    toast.error('充值失败：' + (e?.response?.data?.message || e?.message || '网络异常'))
  } finally {
    busy.value = false
  }
}

// ---------------- 去划扣（跳划扣台，复用双签） ----------------
// 售卡/疗程卡履约＝逐次划扣确认收入；此处只做动线入口：跳划扣台并预填客户/卡，
// 建单与双签划扣仍走 /m2-writeoff-desk 原流程（不绕开 writeoff:create 权限与复核双签）。
function goWriteoff(card: MemberCardDTO) {
  if (!auth.can('writeoff:create') || !auth.can('writeoffdesk:view')) {
    toast.error('无划扣执行台权限（需划扣建单/执行权限）')
    return
  }
  if (card.status !== '在用') {
    toast.error('该卡非「在用」状态，无法划扣')
    return
  }
  void router.push({
    path: '/m2-writeoff-desk',
    query: {
      walkin: '1',
      customerId: card.customerId,
      ...(selected.value?.name ? { customerName: selected.value.name } : {}),
      ...(selected.value?.phone ? { phone: selected.value.phone } : {}),
      cardNo: card.cardNo,
      cardName: card.cardItem,
    },
  })
}

// ---------------- 展示辅助 ----------------
// 售卡开卡走 txn /card-order（@RequirePerm prescription:create/edit/cashier:create 任一，对齐 permission-matrix §4）；
  // 充值走 customer /cards/{no}/recharge（@RequirePerm customer:card:recharge）。权限码前后端必须一致。
const canSell = computed(() => auth.can('prescription:create') || auth.can('prescription:edit') || auth.can('cashier:create'))
const canRecharge = computed(() => auth.can('customer:card:recharge'))
function avatarLetter(name: string | null | undefined) {
  return (name || '客').charAt(0)
}
function maskPhone(phone: string | null | undefined) {
  if (!phone) return '—'
  if (phone.length < 7) return phone
  return phone.slice(0, 3) + '****' + phone.slice(-4)
}
function fen2yuan(f: number | null | undefined) {
  return f == null ? 0 : f / 100
}
function money(fen: number) {
  return `¥${(fen / 100).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}
function yuanText(y: number) {
  return `¥${y.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}
function fmtTime(iso: string | null | undefined) {
  if (!iso) return '—'
  const d = new Date(iso)
  if (isNaN(d.getTime())) return '—'
  return `${d.getMonth() + 1}/${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}
function fmtDate(iso: string | null | undefined) {
  if (!iso) return '长期有效'
  const d = new Date(iso)
  if (isNaN(d.getTime())) return '长期有效'
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} 止`
}
function cardStatusPill(status: string): 'success' | 'default' | 'warning' {
  if (status === '在用') return 'success'
  if (status === '冻结') return 'warning'
  return 'default'
}
function cardStatusText(status: string) {
  return status === '在用' ? '正常' : status
}
function tplValidityText(t: CatalogProductDTO) {
  return t.validityDays > 0 ? `有效期 ${t.validityDays} 天` : '长期有效'
}
function tplSessionsText(t: CatalogProductDTO) {
  return t.productType === 'COURSE' ? `${t.sessions} 次` : '储值卡'
}
</script>

<template>
  <div class="cc-page">
    <div class="cc-kpis">
      <CKpi label="持卡客户" :value="String(kpi.customers)" tone="brand" icon="customer" />
      <CKpi label="储值总额" :value="'¥' + (kpi.balance / 100).toLocaleString('zh-CN')" tone="teal" icon="finance" />
      <CKpi label="有效疗程" :value="String(kpi.courses)" tone="orange" icon="card" />
      <CKpi label="已用完疗程" :value="String(kpi.finished)" tone="text" icon="card" />
    </div>

    <div class="cc-body">
      <!-- 左列：客户 -->
      <CCard title="持卡客户" class="cc-list">
        <div class="cc-search">
          <CInput v-model="keyword" placeholder="搜姓名 / 手机号" clearable @input="onKeywordInput" />
        </div>
        <div class="cc-clist">
          <button
            v-for="c in custList"
            :key="c.customerId"
            class="cc-cust"
            :class="{ 'is-on': selectedId === c.customerId }"
            @click="select(c)"
          >
            <div class="cc-cust__avatar">{{ avatarLetter(c.name) }}</div>
            <div class="cc-cust__main">
              <div class="cc-cust__name">{{ c.name }}</div>
              <div class="cc-cust__sub">{{ maskPhone(c.phone) }}</div>
            </div>
            <div class="cc-cust__bal">
              <span v-if="balanceOf(c.customerId)" class="cc-cust__amt">
                ¥{{ (balanceOf(c.customerId) / 100).toLocaleString('zh-CN') }}
              </span>
              <span v-if="timesOf(c.customerId)" class="cc-cust__times">
                {{ timesOf(c.customerId) }}次
              </span>
            </div>
          </button>
          <div v-if="!loading && !custList.length" class="cc-empty">暂无匹配客户</div>
        </div>
      </CCard>

      <!-- 右列：资产账户 -->
      <div class="cc-detail">
        <template v-if="selected">
          <CCard class="cc-acct-head">
            <div class="cc-acct-id">
              <div class="cc-acct__avatar">{{ avatarLetter(selected.name) }}</div>
              <div>
                <div class="cc-acct__name">{{ selected.name }}</div>
                <div class="cc-acct__sub">{{ maskPhone(selected.phone) }} · 等级 {{ selected.level }}</div>
              </div>
            </div>
            <div class="cc-acct-sum">
              <div class="cc-acct-sum__item">
                <span class="cc-acct-sum__label">账户余额</span>
                <span class="cc-acct-sum__val cc-acct-sum__val--teal">{{ money(totalBalance) }}</span>
              </div>
              <div class="cc-acct-sum__item">
                <span class="cc-acct-sum__label">剩余次数</span>
                <span class="cc-acct-sum__val cc-acct-sum__val--orange">{{ totalRemainingTimes }} 次</span>
              </div>
            </div>
          </CCard>

          <div class="cc-actions">
            <CButton variant="secondary" size="sm" v-perm.disable="['prescription:create','prescription:edit']" @click="openPurchase">
              <CIcon name="card" :size="14" /> 售卡开卡
            </CButton>
          </div>

          <!-- 储值卡 -->
          <CCard v-if="cashCards.length" title="储值卡 / 余额" class="cc-asset-card">
            <div v-for="a in cashCards" :key="a.cardNo" class="asset">
              <div class="asset__top">
                <div class="asset__title">
                  <CIcon name="pos" :size="16" class="asset__icon asset__icon--teal" />
                  {{ a.cardItem }}
                  <CStatusPill :status="cardStatusPill(a.status)">
                    {{ cardStatusText(a.status) }}
                  </CStatusPill>
                </div>
                <div class="asset__balance">{{ money(a.balance + (a.giftBalance ?? 0)) }}</div>
              </div>
              <div class="asset__sub">
                本金 ¥{{ fen2yuan(a.balance).toLocaleString('zh-CN') }}<span v-if="a.giftBalance"> · 赠送 ¥{{ fen2yuan(a.giftBalance).toLocaleString('zh-CN') }}</span>
                · {{ a.cardNo }} · {{ fmtDate(a.expiresAt) }}
              </div>
              <div class="asset__ops">
                <CButton
                  v-if="a.status === '在用'"
                  size="sm" variant="ghost"
                  @click="goWriteoff(a)"
                >去划扣</CButton>
                <CButton
                  v-if="a.status === '在用' && canRecharge"
                  size="sm" variant="ghost"
                  @click="openRecharge(a)"
                >充值</CButton>
                <CButton size="sm" variant="ghost" @click="openLedger(a)">流水</CButton>
              </div>
            </div>
          </CCard>

          <!-- 疗程卡 -->
          <CCard v-if="courseCards.length" title="疗程 / 次卡" class="cc-asset-card">
            <div v-for="a in courseCards" :key="a.cardNo" class="asset">
              <div class="asset__top">
                <div class="asset__title">
                  <CIcon name="card" :size="16" class="asset__icon asset__icon--orange" />
                  {{ a.cardItem }}
                  <CStatusPill :status="a.status === '在用' ? 'primary' : 'default'">
                    {{ a.status === '在用' ? '可用' : a.status }}
                  </CStatusPill>
                </div>
                <div class="asset__times">{{ a.remainTimes ?? 0 }} / {{ a.totalTimes ?? 0 }} 次</div>
              </div>
              <CProgressBar
                v-if="(a.totalTimes ?? 0) > 0"
                :value="(a.totalTimes ?? 0) - (a.remainTimes ?? 0)"
                :max="a.totalTimes ?? 1"
                :label="`已用 ${(a.totalTimes ?? 0) - (a.remainTimes ?? 0)}/${a.totalTimes}`"
                color="var(--c-brand)"
              />
              <div class="asset__sub">
                {{ a.cardNo }} · {{ fmtDate(a.expiresAt) }}<span v-if="a.saleNo"> · 售卡单 {{ a.saleNo }}</span>
              </div>
              <div class="asset__ops">
                <CButton
                  v-if="a.status === '在用'"
                  size="sm" variant="ghost"
                  @click="goWriteoff(a)"
                >去划扣</CButton>
                <CButton size="sm" variant="ghost" @click="openLedger(a)">流水</CButton>
              </div>
            </div>
          </CCard>

          <div v-if="!cashCards.length && !courseCards.length" class="cc-noasset">
            该客户暂无卡资产，可点击上方「售卡开卡」选购在售卡项。
          </div>

          <!-- 卡流水 -->
          <CCard v-if="selectedCards.length" title="卡流水（点卡片「流水」查看）" class="cc-txns">
            <div class="txn-head">
              <span>时间</span><span>类型</span><span class="txn-r">变动金额</span><span>业务单号</span><span>操作人</span>
            </div>
            <div class="txn-list">
              <div
                v-for="t in ledgerRows"
                :key="t.ledgerId"
                class="txn-row"
              >
                <span class="txn-time">{{ fmtTime(t.createdAt) }}</span>
                <span class="txn-kind">{{ ledgerTypeText(t.changeType) }}</span>
                <span class="txn-r" :style="{ color: ledgerColor(t.changeType) }">
                  {{ ledgerSign(t.amount) }}{{ money(Math.abs(t.amount)) }}
                </span>
                <span class="txn-ref">{{ t.bizRef || '—' }}</span>
                <span class="txn-op">{{ t.operator }}</span>
              </div>
              <div v-if="panel !== 'ledger' && !ledgerRows.length" class="txn-empty">点击任一卡的「流水」按钮查看该卡台账</div>
              <div v-else-if="panel === 'ledger' && !ledgerRows.length" class="txn-empty">该卡暂无流水</div>
            </div>
          </CCard>
        </template>

        <CCard v-else class="cc-placeholder">
          <div class="cc-placeholder__inner">
            <CIcon name="card" :size="40" class="cc-placeholder__icon" />
            <p>从左侧选择一位客户，查看其储值与疗程资产；或点击「售卡开卡」为新客户购卡。</p>
          </div>
        </CCard>
      </div>
    </div>

    <!-- 操作面板（轻量弹层） -->
    <div v-if="panel" class="mask" @click.self="close">
      <!-- 售卡开卡 -->
      <CCard v-if="panel === 'purchase'" class="dlg dlg--wide" :title="buyStep === 'form' ? '售卡开卡' : `售卡收款 · ${pendingOrder?.orderNo ?? ''}`">
        <div class="dlg__body">
          <!-- 第一步：选客户 + 选模板 -->
          <template v-if="buyStep === 'form'">
            <div class="dlg__row">
              <label>购卡客户（姓名 / 手机号搜索）</label>
              <CInput v-model="buyCustomerKeyword" placeholder="输入客户姓名 / 手机号" @input="onBuySearchInput" />
              <div v-if="buyResults.length" class="buy-drop">
                <button
                  v-for="c in buyResults"
                  :key="c.customerId"
                  type="button"
                  class="buy-drop__item"
                  :class="{ 'is-on': buyCustomer?.customerId === c.customerId }"
                  @click="pickBuyCustomer(c)"
                >
                  <span class="buy-drop__name">{{ c.name }}</span>
                  <span class="buy-drop__phone">{{ maskPhone(c.phone) }}</span>
                  <span class="buy-drop__level">{{ c.level }}</span>
                </button>
              </div>
            </div>
            <div class="dlg__row">
              <label>在售卡项模板</label>
              <CInput v-model="tplKeyword" placeholder="按卡项名称 / 编码筛选" clearable />
              <div class="buy-tpls">
                <button
                  v-for="t in filteredTemplates"
                  :key="t.productCode"
                  type="button"
                  class="buy-tpl"
                  :class="{ 'is-on': pickedTpl?.productCode === t.productCode }"
                  @click="pickedTpl = t"
                >
                  <div class="buy-tpl__top">
                    <span class="buy-tpl__name">{{ t.name }}</span>
                    <CStatusPill :status="t.productType === 'COURSE' ? 'primary' : 'success'">
                      {{ t.productType === 'COURSE' ? '疗程卡' : '储值卡' }}
                    </CStatusPill>
                  </div>
                  <div class="buy-tpl__meta">
                    <span>{{ tplSessionsText(t) }}</span>
                    <span>{{ tplValidityText(t) }}</span>
                    <span class="buy-tpl__code">{{ t.productCode }}</span>
                  </div>
                  <div class="buy-tpl__price">{{ yuanText(t.priceYuan) }}</div>
                </button>
                <div v-if="!filteredTemplates.length" class="buy-tpl-empty">暂无在售卡项模板</div>
              </div>
            </div>
          </template>

          <!-- 第二步：收款（售卡禁 balance） -->
          <template v-else>
            <div class="pay-sum">
              <div class="pay-sum__row">
                <span>卡项</span><strong>{{ pendingOrder?.project }}</strong>
              </div>
              <div class="pay-sum__row">
                <span>客户</span><strong>{{ buyCustomer?.name }}（{{ maskPhone(buyCustomer?.phone) }}）</strong>
              </div>
              <div class="pay-sum__row">
                <span>应收</span><strong class="pay-sum__amt">{{ money(pendingOrder?.amount ?? 0) }}</strong>
              </div>
              <div class="pay-sum__row" v-if="paidAmount > 0">
                <span>已收 / 待收</span><strong>{{ money(paidAmount) }} / {{ money(Math.max(0, restAmount)) }}</strong>
              </div>
            </div>
            <div class="dlg__row">
              <label>支付方式（售卡不支持储值余额）</label>
              <div class="pay-methods">
                <button
                  v-for="m in PAY_METHODS"
                  :key="m.key"
                  type="button"
                  class="pay-method"
                  :class="{ 'is-on': payMethod === m.key }"
                  @click="payMethod = m.key"
                >
                  <CIcon :name="m.icon as any" :size="16" />
                  {{ m.label }}
                </button>
              </div>
            </div>
            <div class="dlg__row">
              <label>{{ payMethod === 'cash' ? '客户实付（现金，自动找零）' : '收款金额' }}</label>
              <CInput v-model="payAmt" type="number" placeholder="0.00" />
              <div v-if="cashChange > 0" class="pay-change">客户实付 ¥{{ Number(payAmt).toLocaleString('zh-CN') }} · 找零 {{ yuanText(cashChange) }}</div>
              <CButton size="sm" variant="ghost" @click="payAmt = String(Math.round(restAmount) / 100)">全额收款</CButton>
            </div>
          </template>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="close">{{ buyStep === 'pay' ? '取消（待收款单可在收银台继续）' : '取消' }}</CButton>
          <CButton v-if="buyStep === 'form'" variant="primary" :disabled="busy" @click="submitOrder">
            {{ busy ? '提交中…' : '下单并收款' }}
          </CButton>
          <CButton v-else variant="primary" :disabled="busy" @click="submitPay">
            {{ busy ? '收款中…' : '确认收款' }}
          </CButton>
        </template>
      </CCard>

      <!-- 充值 -->
      <CCard v-else-if="panel === 'recharge'" class="dlg" :title="`卡充值 · ${rechargeCardItem}`">
        <div class="dlg__body">
          <div class="dlg__row">
            <label>卡号</label>
            <div class="dlg__static">{{ rechargeCardNo }}</div>
          </div>
          <div class="dlg__row">
            <label>支付方式（储值余额不可充储值卡）</label>
            <CSelect
              :model-value="rechargeMethod"
              :options="RECHARGE_METHODS"
              @update:model-value="rechargeMethod = $event"
            />
          </div>
          <div class="dlg__row">
            <label>充值金额（元）</label>
            <CInput v-model="rechargeAmountYuan" type="number" placeholder="0.00" />
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="close">取消</CButton>
          <CButton variant="primary" :disabled="busy" @click="submitRecharge">
            {{ busy ? '提交中…' : '确认充值' }}
          </CButton>
        </template>
      </CCard>

      <!-- 卡流水 -->
      <CCard v-else class="dlg dlg--wide" :title="`卡流水 · ${ledgerCardItem}`">
        <div class="dlg__body">
          <div class="ledger-table">
            <div class="txn-head">
              <span>时间</span><span>类型</span><span class="txn-r">变动金额</span><span>业务单号</span><span>操作人</span>
            </div>
            <div class="txn-list">
              <div v-for="t in ledgerRows" :key="t.ledgerId" class="txn-row">
                <span class="txn-time">{{ fmtTime(t.createdAt) }}</span>
                <span class="txn-kind">{{ ledgerTypeText(t.changeType) }}</span>
                <span class="txn-r" :style="{ color: ledgerColor(t.changeType) }">
                  {{ ledgerSign(t.amount) }}{{ money(Math.abs(t.amount)) }}
                </span>
                <span class="txn-ref">{{ t.bizRef || '—' }}</span>
                <span class="txn-op">{{ t.operator }}</span>
              </div>
              <div v-if="!ledgerRows.length" class="txn-empty">该卡暂无流水</div>
            </div>
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="close">关闭</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.cc-page { display: flex; flex-direction: column; gap: var(--s-lg); }
.cc-kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); }
.cc-body { display: grid; grid-template-columns: 340px 1fr; gap: var(--s-lg); align-items: start; }

/* 左列客户 */
.cc-list { max-height: calc(100vh - 200px); display: flex; flex-direction: column; }
.cc-search { margin-bottom: var(--s-sm); }
.cc-clist { overflow-y: auto; margin: 0 -8px; padding: 0 8px; }
.cc-cust {
  width: 100%; display: flex; align-items: center; gap: var(--s-sm);
  padding: var(--s-sm) var(--s-md); border: none; background: transparent;
  border-radius: var(--r-md); cursor: pointer; text-align: left;
}
.cc-cust:hover { background: var(--c-brand-soft); }
.cc-cust.is-on { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.cc-cust__avatar {
  width: 36px; height: 36px; border-radius: 50%;
  background: var(--c-brand-soft); color: var(--c-brand);
  display: flex; align-items: center; justify-content: center;
  font-weight: 600; flex-shrink: 0;
}
.cc-cust__main { flex: 1; min-width: 0; }
.cc-cust__name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.cc-cust__sub { font-size: var(--t-xs); color: var(--c-text-3); }
.cc-cust__bal { display: flex; flex-direction: column; align-items: flex-end; gap: 2px; }
.cc-cust__amt { font-size: var(--t-xs); font-weight: 600; color: var(--c-teal-dark); font-variant-numeric: tabular-nums; }
.cc-cust__times { font-size: var(--t-xs); color: var(--c-orange-dark); font-variant-numeric: tabular-nums; }
.cc-empty { text-align: center; color: var(--c-text-3); padding: var(--s-xl); font-size: var(--t-sm); }

/* 右列详情 */
.cc-detail { display: flex; flex-direction: column; gap: var(--s-md); }
.cc-acct-head { display: flex; align-items: center; justify-content: space-between; }
.cc-acct-id { display: flex; align-items: center; gap: var(--s-md); }
.cc-acct__avatar {
  width: 44px; height: 44px; border-radius: 50%;
  background: var(--c-brand-soft); color: var(--c-brand);
  display: flex; align-items: center; justify-content: center;
  font-size: 18px; font-weight: 600;
}
.cc-acct__name { font-size: var(--t-lg); font-weight: 700; color: var(--c-text); }
.cc-acct__sub { font-size: var(--t-xs); color: var(--c-text-3); }
.cc-acct-sum { display: flex; gap: var(--s-xl); }
.cc-acct-sum__item { display: flex; flex-direction: column; align-items: flex-end; }
.cc-acct-sum__label { font-size: var(--t-xs); color: var(--c-text-3); }
.cc-acct-sum__val { font-size: var(--t-xl); font-weight: 700; font-variant-numeric: tabular-nums; }
.cc-acct-sum__val--teal { color: var(--c-teal-dark); }
.cc-acct-sum__val--orange { color: var(--c-orange-dark); }

.cc-actions { display: flex; gap: var(--s-sm); }

.cc-asset-card { padding: var(--s-md); }
.asset { padding: var(--s-sm) 0; border-bottom: 1px solid var(--c-border-light); }
.asset:last-child { border-bottom: none; }
.asset__top { display: flex; align-items: center; justify-content: space-between; margin-bottom: var(--s-xs); }
.asset__title { display: flex; align-items: center; gap: var(--s-xs); font-size: var(--t-base); font-weight: 600; color: var(--c-text); }
.asset__icon { flex-shrink: 0; }
.asset__icon--teal { color: var(--c-teal-dark); }
.asset__icon--orange { color: var(--c-orange-dark); }
.asset__balance { font-size: var(--t-lg); font-weight: 700; color: var(--c-teal-dark); font-variant-numeric: tabular-nums; }
.asset__times { font-size: var(--t-base); font-weight: 600; color: var(--c-orange-dark); font-variant-numeric: tabular-nums; }
.asset__sub { font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: var(--s-sm); }
.asset__ops { display: flex; gap: var(--s-xs); margin-top: var(--s-sm); }

.cc-noasset { padding: var(--s-xl); text-align: center; color: var(--c-text-3); font-size: var(--t-sm); background: var(--c-surface); border-radius: var(--r-md); }

/* 流水 */
.cc-txns { padding: var(--s-md); }
.txn-head, .txn-row {
  display: grid; grid-template-columns: 90px 90px 1fr 140px 90px; gap: var(--s-sm);
  align-items: center; padding: var(--s-xs) var(--s-sm);
}
.txn-head { font-size: var(--t-xs); color: var(--c-text-3); border-bottom: 1px solid var(--c-border-light); }
.txn-row { font-size: var(--t-sm); color: var(--c-text-2); border-bottom: 1px solid var(--c-border-light); }
.txn-time { color: var(--c-text-3); font-variant-numeric: tabular-nums; }
.txn-r { text-align: right; font-weight: 600; font-variant-numeric: tabular-nums; }
.txn-ref { font-size: var(--t-xs); color: var(--c-text-3); font-variant-numeric: tabular-nums; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.txn-op { font-size: var(--t-xs); color: var(--c-text-3); }
.txn-empty { text-align: center; color: var(--c-text-3); padding: var(--s-lg); font-size: var(--t-sm); }

/* 占位 */
.cc-placeholder__inner { display: flex; flex-direction: column; align-items: center; justify-content: center; padding: var(--s-xxl); color: var(--c-text-3); gap: var(--s-sm); }
.cc-placeholder__icon { color: var(--c-text-4); }
.cc-placeholder p { margin: 0; font-size: var(--t-sm); }

/* 弹层 */
.mask { position: fixed; inset: 0; background: rgba(0,0,0,0.32); display: flex; align-items: center; justify-content: center; z-index: 100; }
.dlg { width: 420px; }
.dlg--wide { width: 560px; }
.dlg__body { display: flex; flex-direction: column; gap: var(--s-md); padding: var(--s-sm) 0; }
.dlg__row { display: flex; flex-direction: column; gap: var(--s-xs); position: relative; }
.dlg__row label { font-size: var(--t-sm); color: var(--c-text-2); font-weight: 500; }
.dlg__tip { margin: 0; font-size: var(--t-sm); color: var(--c-text-3); }
.dlg__static { font-size: var(--t-sm); color: var(--c-text); font-variant-numeric: tabular-nums; padding: var(--s-xs) 0; }

/* 售卡弹层：客户候选 + 模板卡片 */
.buy-drop {
  position: absolute; top: 100%; left: 0; right: 0; margin-top: 4px; z-index: 5;
  background: var(--c-surface); border: 1px solid var(--c-border); border-radius: var(--r-md);
  box-shadow: 0 8px 24px rgba(20,21,43,.12); max-height: 220px; overflow-y: auto;
}
.buy-drop__item {
  width: 100%; display: flex; align-items: center; gap: var(--s-sm);
  padding: var(--s-sm) var(--s-md); border: none; background: transparent;
  cursor: pointer; text-align: left; font-size: var(--t-sm);
}
.buy-drop__item:hover { background: var(--c-brand-soft); }
.buy-drop__item.is-on { background: var(--c-brand-soft); }
.buy-drop__name { font-weight: 600; color: var(--c-text); }
.buy-drop__phone { color: var(--c-text-3); font-size: var(--t-xs); }
.buy-drop__level { margin-left: auto; font-size: var(--t-xs); color: var(--c-brand); }

.buy-tpls { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-sm); max-height: 260px; overflow-y: auto; }
.buy-tpl {
  display: flex; flex-direction: column; gap: var(--s-xs); text-align: left;
  padding: var(--s-sm) var(--s-md); border: 1px solid var(--c-border);
  border-radius: var(--r-md); background: var(--c-surface); cursor: pointer;
}
.buy-tpl:hover { border-color: var(--c-brand); }
.buy-tpl.is-on { border-color: var(--c-brand); background: var(--c-brand-soft); }
.buy-tpl__top { display: flex; align-items: center; justify-content: space-between; gap: var(--s-xs); }
.buy-tpl__name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.buy-tpl__meta { display: flex; flex-wrap: wrap; gap: var(--s-xs); font-size: var(--t-xs); color: var(--c-text-3); }
.buy-tpl__code { font-variant-numeric: tabular-nums; }
.buy-tpl__price { font-size: var(--t-base); font-weight: 700; color: var(--c-teal-dark); font-variant-numeric: tabular-nums; }
.buy-tpl-empty { grid-column: 1 / -1; text-align: center; color: var(--c-text-3); padding: var(--s-lg); font-size: var(--t-sm); }

/* 售卡收款 */
.pay-sum { display: flex; flex-direction: column; gap: var(--s-xs); padding: var(--s-sm) var(--s-md); background: var(--c-surface); border-radius: var(--r-md); }
.pay-sum__row { display: flex; justify-content: space-between; font-size: var(--t-sm); color: var(--c-text-2); }
.pay-sum__amt { color: var(--c-teal-dark); font-size: var(--t-lg); font-variant-numeric: tabular-nums; }
.pay-methods { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-xs); }
.pay-method {
  display: flex; flex-direction: column; align-items: center; gap: 4px;
  padding: var(--s-sm); border: 1px solid var(--c-border); border-radius: var(--r-md);
  background: var(--c-surface); cursor: pointer; font-size: var(--t-xs); color: var(--c-text-2);
}
.pay-method:hover { border-color: var(--c-brand); }
.pay-method.is-on { border-color: var(--c-brand); background: var(--c-brand-soft); color: var(--c-brand); font-weight: 600; }
.pay-change { font-size: var(--t-xs); color: var(--c-orange-dark); }

.ledger-table { max-height: 50vh; overflow-y: auto; }

/* Pad 堆叠 */
@media (max-width: 834px) {
  .cc-kpis { grid-template-columns: repeat(2, 1fr); }
  .cc-body { grid-template-columns: 1fr; }
  .cc-list { max-height: 320px; }
  .cc-acct-head { flex-direction: column; align-items: flex-start; gap: var(--s-md); }
  .cc-acct-sum { width: 100%; justify-content: space-between; }
  .buy-tpls { grid-template-columns: 1fr; }
  .pay-methods { grid-template-columns: repeat(2, 1fr); }
  .txn-head, .txn-row { grid-template-columns: 70px 80px 1fr 70px; }
  .txn-ref { display: none; }
}
</style>
