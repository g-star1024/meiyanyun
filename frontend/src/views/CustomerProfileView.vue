<script setup lang="ts">
/* ============================================================
 * 客户 360（合并页）/customers/:id
 * 数据源：真实 customer-service（经国密网关 /api/customer）。
 *   已接真实：头部信息条、4 KPI、标签、会员卡、积分流水、档案、智能提醒（真实字段派生）。
 *   显式占位（后端暂无字段 / 跨服务，严禁造假数据）：
 *     - 价值画像（RFM 五维雷达 / 生命周期）：客户域暂无 RFM 评分与生命周期字段；
 *     - 消费订单：交易服务 txn-service 订单接口（/api/txn）下一阶段接入；
 *     - 病历随访 / 对比照面诊：EMR / 面诊域 + 图床未接入。
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import { useAuthStore } from '@/stores/auth'
import { CUSTOMER_SOURCE } from '@/config/dictionary'
import {
  getCustomer,
  listCustomerCards,
  listPointsLog,
  listAllTags,
  listCustomerTagRels,
  type CustomerDTO,
  type MemberCardDTO,
  type PointsLedgerDTO,
} from '@/api/customer'
import {
  listCustomerOrders,
  listCustomerConsultations,
  listCustomerAppointments,
  getCustomerRfm,
  type CustomerOrderView,
  type CustomerConsultView,
  type CustomerApptView,
  type CustomerRfmView,
} from '@/api/customerView'

type PillStatus = 'default' | 'primary' | 'success' | 'warning' | 'danger' | 'info' | 'disabled' | 'draft'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const customerId = computed(() => (route.params.id as string) || '')

// ---- 真实数据状态 ----
const loading = ref(true)
const loadError = ref('')
const notFound = ref(false)
const customer = ref<CustomerDTO | null>(null)
const cards = ref<MemberCardDTO[]>([])
const ledgers = ref<PointsLedgerDTO[]>([])
const tagNames = ref<string[]>([])
const orders = ref<CustomerOrderView[]>([])
const consults = ref<CustomerConsultView[]>([])
const appts = ref<CustomerApptView[]>([])
const rfm = ref<CustomerRfmView | null>(null)

const canSeePhone = computed(() => auth.can('customer:phone:decrypt'))

type ProfileTab = 'profile' | 'overview' | 'order' | 'medical' | 'photo'
const tab = ref<ProfileTab>(route.query.t === '360' ? 'overview' : 'profile')

const profileTabs: { k: ProfileTab; label: string; icon: string }[] = [
  { k: 'profile', label: '价值画像', icon: 'customer' },
  { k: 'overview', label: '档案', icon: 'profile' },
  { k: 'order', label: '消费订单', icon: 'order' },
  { k: 'medical', label: '病历随访', icon: 'box' },
  { k: 'photo', label: '对比照/面诊', icon: 'beauty' },
]

// 会员等级（中文）→ 胶囊色（与客户列表页一致：等级越高色越重）
const LEVEL_PILL: Record<string, { status: PillStatus; text: string }> = {
  '普通': { status: 'default', text: '普通会员' },
  '银卡': { status: 'info', text: '银卡会员' },
  '金卡': { status: 'warning', text: '金卡会员' },
  '钻石': { status: 'primary', text: '钻石会员' },
  '黑卡': { status: 'danger', text: '黑卡会员' },
}
const levelPill = computed(() =>
  LEVEL_PILL[customer.value?.level ?? ''] ?? { status: 'default' as PillStatus, text: customer.value?.level ?? '—' },
)
const statusPill = computed<{ status: PillStatus; text: string }>(() => {
  const s = customer.value?.status
  if (s === '沉睡') return { status: 'warning', text: '沉睡' }
  if (s === '流失') return { status: 'danger', text: '流失' }
  return { status: 'success', text: s ?? '活跃' }
})

// ---- 展示派生（全部来自真实字段） ----
function maskPhone(p?: string | null): string {
  if (!p) return '—'
  return p.length === 11 ? p.replace(/(\d{3})\d{4}(\d{4})/, '$1****$2') : p
}
const phoneText = computed(() =>
  canSeePhone.value ? (customer.value?.phone ?? '—') : maskPhone(customer.value?.phone),
)
const channelText = computed(() => {
  const ch = customer.value?.channel
  if (!ch) return '未登记'
  return CUSTOMER_SOURCE[ch as keyof typeof CUSTOMER_SOURCE]?.label ?? ch
})
const registerDate = computed(() => (customer.value?.createdAt ? fmtDate(customer.value.createdAt) : '—'))
const avatarLetter = computed(() => customer.value?.name?.charAt(0) ?? '客')
const ownerText = computed(() => customer.value?.ownerStaffName || '未分配')
const storeText = computed(() => customer.value?.storeName || customer.value?.storeCode || '—')

function fmtDate(s?: string) {
  if (!s) return '—'
  const d = new Date(s)
  if (isNaN(d.getTime())) return '—'
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
function fmtMoneyYuan(cent: number) {
  return `¥${(cent / 100).toLocaleString('zh-CN', { minimumFractionDigits: 0, maximumFractionDigits: 2 })}`
}

// 订单状态（中文）→ 胶囊色
function orderStatusPill(s: string): PillStatus {
  if (s === '已收款') return 'success'
  if (s === '待收款') return 'warning'
  if (s === '已取消') return 'disabled'
  return 'draft' // 待签核
}
// 预约状态（中文）→ 胶囊色
function apptStatusPill(s: string): PillStatus {
  if (s === '已到店') return 'success'
  if (s === '未到诊') return 'danger'
  if (s === '已取消') return 'disabled'
  return 'info' // 已预约
}
function fmtDateTime(s?: string) {
  if (!s) return '—'
  const d = new Date(s)
  if (isNaN(d.getTime())) return '—'
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
// 近期预约（未来/最近 3 条，档案概览用）
const recentAppts = computed(() => appts.value.slice(0, 3))

const cardBalanceTotal = computed(() => cards.value.reduce((s, c) => s + (c.balance ?? 0), 0))

// 4 KPI（全部真实字段，无 LTV/沉睡天数等臆造指标）
const kpis = computed(() => [
  { label: '累计消费', value: `¥${(customer.value?.totalSpend ?? 0).toLocaleString('zh-CN')}`, sub: `客户状态：${statusPill.value.text}`, tone: 'teal' as const, bg: 'var(--c-success-bg)' },
  { label: '到店频次', value: `${customer.value?.visitCount ?? 0} 次`, sub: `归属 ${ownerText.value}`, tone: 'orange' as const, bg: 'var(--c-draft-bg)' },
  { label: '积分余额', value: `${(customer.value?.points ?? 0).toLocaleString('zh-CN')}`, sub: `${ledgers.value.length} 笔积分流水`, tone: 'brand' as const, bg: 'var(--c-info-bg)' },
  { label: '卡余额', value: fmtMoneyYuan(cardBalanceTotal.value), sub: `${cards.value.length} 张会员卡`, tone: 'warning' as const, bg: 'var(--c-warning-bg)' },
])

// 智能提醒：由真实字段派生（沉睡/流失唤回、卡剩余次数不足），非编造
const reminders = computed<{ level: 'danger' | 'warning' | 'success'; text: string }[]>(() => {
  const r: { level: 'danger' | 'warning' | 'success'; text: string }[] = []
  const st = customer.value?.status
  if (st === '流失') r.push({ level: 'danger', text: '客户已流失，建议由归属咨询师发起专项挽回回访' })
  else if (st === '沉睡') r.push({ level: 'warning', text: '客户进入沉睡期，建议推送唤回权益 / 优惠券' })
  for (const c of cards.value) {
    if (c.remainTimes != null && c.remainTimes <= 2 && c.status === '在用') {
      r.push({ level: 'warning', text: `「${c.cardItem}」剩余 ${c.remainTimes} 次，可提示续卡` })
    }
  }
  if (!r.length) r.push({ level: 'success', text: '客户状态正常，暂无待办提醒' })
  return r
})

async function load() {
  loading.value = true
  loadError.value = ''
  notFound.value = false
  try {
    const id = customerId.value
    const [custRes, cardRes, ledgerRes, tagRelRes, tagAllRes] = await Promise.all([
      getCustomer(id),
      listCustomerCards(id),
      listPointsLog(id),
      listCustomerTagRels(id),
      listAllTags(),
    ])
    customer.value = custRes.data
    cards.value = cardRes.data ?? []
    ledgers.value = (ledgerRes.data ?? []).slice().sort((a, b) => b.ledgerId - a.ledgerId)
    // tagId（关联）→ tagName（全量字典）join
    const nameById = new Map(tagAllRes.data.map((t) => [t.tagId, t.tagName]))
    tagNames.value = (tagRelRes.data ?? []).map((r) => nameById.get(r.tagId)).filter((x): x is string => !!x)
  } catch (e: any) {
    const status = e?.response?.status
    if (status === 404 || status === 400) notFound.value = true
    else loadError.value = '客户详情加载失败，请稍后重试'
    console.error('[CustomerProfile] 加载客户 360 失败', e)
  } finally {
    loading.value = false
  }

  // txn 域（订单/面诊/预约）独立容错：服务未就绪时对应 tab 显空态，不影响客户域
  try {
    const id = customerId.value
    const [o, cn, ap, rf] = await Promise.allSettled([
      listCustomerOrders(id),
      listCustomerConsultations(id),
      listCustomerAppointments(id),
      getCustomerRfm(id),
    ])
    orders.value = o.status === 'fulfilled' ? (o.value.data ?? []) : []
    consults.value = cn.status === 'fulfilled' ? (cn.value.data ?? []) : []
    appts.value = ap.status === 'fulfilled' ? (ap.value.data ?? []) : []
    rfm.value = rf.status === 'fulfilled' ? (rf.value.data ?? null) : null
  } catch (e) {
    console.error('[CustomerProfile] 加载交易域数据失败', e)
  }
}

onMounted(load)

function goBack() {
  router.push('/customers')
}
function call() {
  window.alert(`正在呼叫 ${phoneText.value}`)
}

// 平台合规能力声明（系统级控制项，非客户数据，可常驻展示）
const compliance = [
  { text: '脱敏展示', tone: 'success' as const, dot: 'var(--c-teal)' },
  { text: '敏感词拦截', tone: 'danger' as const, dot: 'var(--c-danger-fg)' },
  { text: 'EMR 只读·双签', tone: 'info' as const, dot: 'var(--c-blue)' },
  { text: '数据出域受限', tone: 'draft' as const, dot: 'var(--c-purple)' },
]
</script>

<template>
  <div class="cp">
    <!-- 顶部面包屑 + 操作 -->
    <div class="cp__topbar">
      <div class="cp__crumbs">
        <button class="cp__back" @click="goBack">
          <CIcon name="chevron-left" :size="18" />
        </button>
        <span class="cp__crumb">客户</span>
        <span class="cp__sep">/</span>
        <span class="cp__crumb-active">客户 360</span>
      </div>
      <div class="cp__top-actions">
        <CButton variant="primary" size="sm" :disabled="!customer" @click="call">
          <CIcon name="phone" :size="14" />一键呼叫
        </CButton>
      </div>
    </div>

    <!-- 加载 / 错误 / 不存在 -->
    <CCard v-if="loading" padding="lg">
      <div class="cp__state">加载客户详情中…</div>
    </CCard>
    <CCard v-else-if="notFound" padding="lg">
      <div class="cp__state">
        <CIcon name="alert" :size="18" />
        <span>未找到客户「{{ customerId }}」，可能已被合并或删除。</span>
        <CButton variant="ghost" size="sm" @click="goBack">返回客户列表</CButton>
      </div>
    </CCard>
    <CCard v-else-if="loadError || !customer" padding="lg">
      <div class="cp__state">
        <CIcon name="alert" :size="18" />
        <span>{{ loadError || '客户数据不可用' }}</span>
        <CButton variant="ghost" size="sm" @click="load">重试</CButton>
      </div>
    </CCard>

    <template v-else>
      <!-- 客户信息条 -->
      <CCard class="cp__hero" :header-border="false" padding="lg">
        <div class="hero">
          <div class="hero__avatar">{{ avatarLetter }}</div>
          <div class="hero__main">
            <div class="hero__name-row">
              <h2 class="hero__name">{{ customer.name }}</h2>
              <span class="hero__anon">会员号 {{ customer.customerId }}</span>
              <CStatusPill :status="levelPill.status" dot>{{ levelPill.text }}</CStatusPill>
              <CStatusPill :status="statusPill.status" dot>{{ statusPill.text }}</CStatusPill>
            </div>
            <div class="hero__meta">
              手机 {{ phoneText }} · 注册于 {{ registerDate }} · 来源 {{ channelText }}
            </div>
            <div class="hero__tags">
              <span v-for="(t, i) in tagNames" :key="t" class="hero__tag" :class="{ 'hero__tag--primary': i === 0 }">{{ t }}</span>
              <span v-if="!tagNames.length" class="hero__tag hero__tag--empty">暂无标签</span>
            </div>
          </div>
          <div class="hero__points">
            <strong>{{ (customer.points ?? 0).toLocaleString('zh-CN') }}</strong>
            <span>积分余额</span>
          </div>
        </div>
      </CCard>

      <!-- 4 KPI -->
      <div class="cp__kpis">
        <div v-for="k in kpis" :key="k.label" class="soft-kpi" :style="{ background: k.bg }">
          <div class="soft-kpi__label">{{ k.label }}</div>
          <div class="soft-kpi__value" :style="{ color: k.tone === 'teal' ? 'var(--c-teal-dark)' : k.tone === 'brand' ? 'var(--c-brand)' : k.tone === 'orange' ? 'var(--c-purple)' : 'var(--c-warning-fg)' }">{{ k.value }}</div>
          <div class="soft-kpi__sub" :style="{ color: k.tone === 'teal' ? 'var(--c-teal-fg)' : k.tone === 'brand' ? 'var(--c-blue)' : k.tone === 'orange' ? 'var(--c-purple)' : 'var(--c-warning-fg)' }">{{ k.sub }}</div>
        </div>
      </div>

      <!-- Tab 切换 -->
      <div class="cp__tabs">
        <button
          v-for="t in profileTabs"
          :key="t.k"
          class="cp__tab"
          :class="{ 'is-active': tab === t.k }"
          @click="tab = t.k"
        >
          <CIcon :name="t.icon as any" :size="15" />{{ t.label }}
        </button>
      </div>

      <!-- 主体：左主区 + 右侧栏 -->
      <div class="cp__body">
        <div class="cp__main">
          <!-- ===== Tab ① 价值画像（RFM + 忠诚 + 活跃，读时实时计算，不建表） ===== -->
          <CCard v-if="tab === 'profile'" padding="lg">
            <template #header><h3 class="cp__card-title">五维价值模型 · RFM + 忠诚 + 活跃</h3></template>

            <!-- 未成交：不展示评分 -->
            <div v-if="rfm && !rfm.transacted" class="ph">
              <CIcon name="customer" :size="22" class="ph__ic" />
              <div class="ph__title">暂无成交，无法评估价值</div>
              <div class="ph__desc">该客户尚无已收款订单，RFM 评分需至少一笔成交后由系统实时计算。</div>
            </div>

            <template v-else-if="rfm">
              <!-- 价值分层横幅 -->
              <div class="rfm-hero">
                <div class="rfm-hero__seg">{{ rfm.segment }}</div>
                <div class="rfm-hero__chips">
                  <span class="rfm-chip">生命周期 · {{ rfm.lifecycle }}</span>
                  <span class="rfm-chip">活跃度 · {{ rfm.activityLevel }}</span>
                  <span v-if="rfm.loyaltyLevel" class="rfm-chip">忠诚度 · {{ rfm.loyaltyLevel }}</span>
                </div>
              </div>

              <!-- R/F/M 三维评分条（5 分制） -->
              <div class="rfm-scores">
                <div class="rfm-score">
                  <div class="rfm-score__head"><span>最近消费 R</span><strong>{{ rfm.rScore }}<i>/5</i></strong></div>
                  <div class="rfm-bar"><div class="rfm-bar__fill" :style="{ width: (rfm.rScore! / 5) * 100 + '%' }"></div></div>
                  <div class="rfm-score__sub">最近成交距今 {{ rfm.recencyDays ?? '—' }} 天</div>
                </div>
                <div class="rfm-score">
                  <div class="rfm-score__head"><span>消费频次 F</span><strong>{{ rfm.fScore }}<i>/5</i></strong></div>
                  <div class="rfm-bar"><div class="rfm-bar__fill" :style="{ width: (rfm.fScore! / 5) * 100 + '%' }"></div></div>
                  <div class="rfm-score__sub">近一年成交 {{ rfm.freq365 }} 单</div>
                </div>
                <div class="rfm-score">
                  <div class="rfm-score__head"><span>消费金额 M</span><strong>{{ rfm.mScore }}<i>/5</i></strong></div>
                  <div class="rfm-bar"><div class="rfm-bar__fill" :style="{ width: (rfm.mScore! / 5) * 100 + '%' }"></div></div>
                  <div class="rfm-score__sub">近一年消费 ¥{{ rfm.monetary365.toLocaleString('zh-CN') }}</div>
                </div>
              </div>

              <!-- 关键指标 -->
              <div class="rfm-metrics">
                <div class="rfm-metric"><span>近 90 天成交</span><strong>{{ rfm.orders90 }} 单</strong></div>
                <div class="rfm-metric"><span>累计成交</span><strong>{{ rfm.totalOrders }} 单</strong></div>
                <div class="rfm-metric"><span>近一年消费</span><strong>¥{{ rfm.monetary365.toLocaleString('zh-CN') }}</strong></div>
                <div class="rfm-metric"><span>最近成交</span><strong>{{ rfm.recencyDays ?? '—' }} 天前</strong></div>
              </div>
              <div class="rfm-note">评分由系统按已收款订单实时计算（近 365 天窗口），规则见 RFM 价值模型文档；分层与运营策略每月可依真实数据复核调参。</div>
            </template>

            <div v-else class="ph">
              <div class="ph__desc">价值画像加载中…</div>
            </div>
          </CCard>

          <!-- ===== Tab ② 档案（真实：基础信息 / 会员卡 / 积分流水） ===== -->
          <CCard v-else-if="tab === 'overview'" padding="lg">
            <template #header><h3 class="cp__card-title">客户档案</h3></template>

            <div class="kv-grid">
              <div class="kv"><span>会员号</span><strong>{{ customer.customerId }}</strong></div>
              <div class="kv"><span>姓名</span><strong>{{ customer.name }}</strong></div>
              <div class="kv"><span>性别</span><strong>{{ customer.gender || '—' }}</strong></div>
              <div class="kv"><span>出生日期</span><strong>{{ customer.birthDate || '—' }}</strong></div>
              <div class="kv"><span>会员等级</span><strong>{{ levelPill.text }}</strong></div>
              <div class="kv"><span>客户状态</span><strong>{{ statusPill.text }}</strong></div>
              <div class="kv"><span>获客来源</span><strong>{{ channelText }}</strong></div>
              <div class="kv"><span>归属咨询师</span><strong>{{ ownerText }}</strong></div>
              <div class="kv"><span>所属门店</span><strong>{{ storeText }}</strong></div>
              <div class="kv"><span>注册时间</span><strong>{{ registerDate }}</strong></div>
            </div>

            <div class="cp__sub-title">会员卡（{{ cards.length }}）</div>
            <div v-if="!cards.length" class="cp__empty">暂无会员卡</div>
            <div v-for="c in cards" :key="c.cardNo" class="card-row">
              <div class="card-row__line">
                <CIcon name="order" :size="15" class="card-row__ic" />
                <span class="card-row__name">{{ c.cardItem }}</span>
                <CStatusPill :status="c.status === '在用' ? 'success' : 'default'">{{ c.status }}</CStatusPill>
                <strong class="card-row__amt">{{ fmtMoneyYuan(c.balance) }}</strong>
              </div>
              <div class="card-row__sub">
                {{ c.cardNo }} · 剩余 {{ c.remainTimes ?? '—' }}/{{ c.totalTimes ?? '—' }} 次
              </div>
            </div>

            <div class="cp__sub-title">积分流水（{{ ledgers.length }}）</div>
            <div v-if="!ledgers.length" class="cp__empty">暂无积分流水</div>
            <div v-for="l in ledgers" :key="l.ledgerId" class="ledger-row">
              <span class="ledger-row__reason">{{ l.reason }}</span>
              <span class="ledger-row__delta" :class="{ 'is-pos': l.changeAmt >= 0 }">{{ l.changeAmt >= 0 ? '+' : '' }}{{ l.changeAmt.toLocaleString('zh-CN') }}</span>
              <span class="ledger-row__bal">余额 {{ l.balanceAfter.toLocaleString('zh-CN') }}</span>
              <span class="ledger-row__date">{{ fmtDate(l.createdAt) }}</span>
            </div>

            <div class="cp__sub-title">近期预约（{{ recentAppts.length }}）</div>
            <div v-if="!recentAppts.length" class="cp__empty">暂无预约记录</div>
            <div v-for="a in recentAppts" :key="a.apptNo" class="card-row">
              <div class="card-row__line">
                <CIcon name="calendar" :size="15" class="card-row__ic" />
                <span class="card-row__name">{{ a.project }} · {{ a.apptDate }} {{ a.apptTime }}</span>
                <CStatusPill :status="apptStatusPill(a.status)">{{ a.status }}</CStatusPill>
              </div>
            </div>
          </CCard>

          <!-- ===== Tab ③ 消费订单（txn-service 真实订单） ===== -->
          <CCard v-else-if="tab === 'order'" padding="lg">
            <template #header><h3 class="cp__card-title">消费订单（{{ orders.length }}）</h3></template>
            <div v-if="!orders.length" class="cp__empty">暂无消费订单</div>
            <div v-for="o in orders" :key="o.orderNo" class="card-row">
              <div class="card-row__line">
                <CIcon name="order" :size="15" class="card-row__ic" />
                <span class="card-row__name">{{ o.project }}<span v-if="o.items && o.items.length > 1" class="card-row__count">等 {{ o.items.length }} 项</span></span>
                <CStatusPill :status="orderStatusPill(o.status)">{{ o.status }}</CStatusPill>
                <strong class="card-row__amt">{{ fmtMoneyYuan(o.amount) }}</strong>
              </div>
              <!-- 收费子项明细（一笔订单可含多个收费项目） -->
              <div v-if="o.items && o.items.length" class="order-items">
                <div v-for="(it, idx) in o.items" :key="idx" class="order-item">
                  <span class="order-item__name">{{ it.itemName }}</span>
                  <span class="order-item__qty">×{{ it.qty }}</span>
                  <span class="order-item__amt">{{ fmtMoneyYuan(it.amount) }}</span>
                </div>
              </div>
              <div class="card-row__sub">
                {{ o.orderNo }} · {{ o.consultantName ? `咨询师 ${o.consultantName} · ` : '' }}{{ fmtDateTime(o.createdAt) }}
              </div>
            </div>
          </CCard>

          <!-- ===== Tab ④ 病历随访（面诊咨询 + 预约真实；EMR 病历/随访待合规接入） ===== -->
          <CCard v-else-if="tab === 'medical'" padding="lg">
            <template #header><h3 class="cp__card-title">面诊 / 咨询（{{ consults.length }}）</h3></template>
            <div v-if="!consults.length" class="cp__empty">暂无面诊 / 咨询记录</div>
            <div v-for="c in consults" :key="c.consultId" class="card-row">
              <div class="card-row__line">
                <CIcon name="chat" :size="15" class="card-row__ic" />
                <span class="card-row__name">{{ c.skinStatus || '面诊咨询' }}</span>
                <CStatusPill :status="c.privacyMasked ? 'info' : 'default'">{{ c.privacyMasked ? '已脱敏' : '未脱敏' }}</CStatusPill>
              </div>
              <div class="card-row__sub">
                {{ c.consultId }}<template v-if="c.consultantName"> · 咨询师 {{ c.consultantName }}</template> · {{ fmtDateTime(c.createdAt) }}
              </div>
              <div v-if="c.needs" class="card-row__sub">需求：{{ c.needs }}</div>
            </div>

            <div class="cp__sub-title" style="margin-top: var(--s-lg)">预约 / 到店记录（{{ appts.length }}）</div>
            <div v-if="!appts.length" class="cp__empty">暂无预约记录</div>
            <div v-for="a in appts" :key="a.apptNo" class="card-row">
              <div class="card-row__line">
                <CIcon name="calendar" :size="15" class="card-row__ic" />
                <span class="card-row__name">{{ a.project }} · {{ a.apptDate }} {{ a.apptTime }}</span>
                <CStatusPill :status="apptStatusPill(a.status)">{{ a.status }}</CStatusPill>
              </div>
              <div class="card-row__sub">
                {{ a.apptNo }} · 来源 {{ a.source }}<template v-if="a.doctorName"> · 医生 {{ a.doctorName }}</template>
              </div>
            </div>

            <div class="ph ph--inline">
              <div class="ph__desc">EMR 电子病历与随访记录属医疗文书，需 EMR 只读·双签接口与字段级 RBAC 就绪后接入，内容不可篡改、访问全程审计。</div>
            </div>
          </CCard>

          <!-- ===== Tab ⑤ 对比照/面诊（图床未接入，占位） ===== -->
          <CCard v-else class="cp__placeholder-card" padding="lg">
            <template #header><h3 class="cp__card-title">对比照 / 面诊报告</h3></template>
            <div class="ph">
              <CIcon name="beauty" :size="22" class="ph__ic" />
              <div class="ph__title">对比照与面诊报告待接入</div>
              <div class="ph__desc">术前/术后对比照依赖对象存储（MinIO/S3）与面诊结构化报告数据，当前图床与面诊报告接口尚未接入，暂不展示占位图片。</div>
            </div>
          </CCard>
        </div>

        <!-- 右侧栏 -->
        <aside class="cp__side">
          <CCard class="side-card" padding="md">
            <template #header><h3 class="cp__card-title-sm">会员权益</h3></template>
            <div class="side-empty">依会员等级（{{ levelPill.text }}）的折扣 / 专享权益规则待配置接入。</div>
          </CCard>

          <CCard class="side-card" padding="md">
            <template #header><h3 class="cp__card-title-sm">风险与合规</h3></template>
            <div class="risks">
              <div v-for="c in compliance" :key="c.text" class="comp-cell" :class="`comp-cell--${c.tone}`">
                <span class="comp-cell__dot" :style="{ background: c.dot }" />
                <span>{{ c.text }}</span>
              </div>
            </div>
          </CCard>

          <CCard class="side-card" padding="md">
            <template #header><h3 class="cp__card-title-sm">智能提醒 / 待办</h3></template>
            <ul class="reminders">
              <li v-for="(r, i) in reminders" :key="i" class="reminder">
                <span class="reminder__dot" :class="`reminder__dot--${r.level}`" />
                <span class="reminder__text">{{ r.text }}</span>
              </li>
            </ul>
          </CCard>
        </aside>
      </div>
    </template>
  </div>
</template>

<style scoped>
.cp { display: flex; flex-direction: column; gap: var(--s-md); }

/* 顶部 */
.cp__topbar { display: flex; align-items: center; justify-content: space-between; gap: var(--s-md); }
.cp__crumbs { display: flex; align-items: center; gap: var(--s-xs); font-size: var(--t-lg); font-weight: 700; color: var(--c-text); }
.cp__back { display: inline-flex; align-items: center; justify-content: center; width: 32px; height: 32px; border: none; background: var(--c-surface); border-radius: var(--r-md); cursor: pointer; color: var(--c-text-2); margin-right: var(--s-xs); }
.cp__back:hover { background: var(--c-brand-soft); color: var(--c-brand); }
.cp__crumb { color: var(--c-text-3); font-weight: 400; font-size: var(--t-base); }
.cp__sep { color: var(--c-text-4); font-weight: 400; }
.cp__crumb-active { font-size: var(--t-lg); font-weight: 700; }
.cp__top-actions { display: flex; align-items: center; gap: var(--s-md); }

/* 状态 */
.cp__state { display: flex; align-items: center; gap: var(--s-sm); color: var(--c-text-3); font-size: var(--t-base); padding: var(--s-lg) 0; justify-content: center; flex-wrap: wrap; }

/* Hero */
.cp__hero { padding: var(--s-lg); }
.hero { display: flex; align-items: center; gap: var(--s-lg); }
.hero__avatar { width: 72px; height: 72px; border-radius: 50%; background: var(--c-teal); color: #fff; display: flex; align-items: center; justify-content: center; font-size: 32px; font-weight: 700; flex-shrink: 0; }
.hero__main { flex: 1; min-width: 0; }
.hero__name-row { display: flex; align-items: center; gap: var(--s-sm); flex-wrap: wrap; }
.hero__name { font-size: var(--t-xl); font-weight: 700; }
.hero__anon { font-size: var(--t-sm); color: var(--c-text-3); background: var(--c-disabled-bg); padding: 2px 10px; border-radius: var(--r-sm); }
.hero__meta { font-size: var(--t-sm); color: var(--c-text-3); margin-top: 6px; }
.hero__tags { display: flex; flex-wrap: wrap; gap: var(--s-xs); margin-top: var(--s-sm); }
.hero__tag { font-size: var(--t-xs); padding: 4px 12px; border-radius: var(--r-pill); background: var(--c-info-bg); color: var(--c-blue); }
.hero__tag--primary { background: var(--c-brand-soft); color: var(--c-brand); }
.hero__tag--empty { background: var(--c-disabled-bg); color: var(--c-text-4); }
.hero__points { flex-shrink: 0; text-align: center; padding: var(--s-md); border-radius: var(--r-xl); background: var(--c-brand-soft); min-width: 96px; }
.hero__points strong { display: block; font-size: var(--t-xl); font-weight: 700; color: var(--c-brand); line-height: 1.1; font-variant-numeric: tabular-nums; }
.hero__points span { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 4px; display: block; }

/* Tabs */
.cp__tabs { display: flex; gap: var(--s-xs); border-bottom: 1px solid var(--c-border-light); }
.cp__tab { display: inline-flex; align-items: center; gap: 6px; background: none; border: none; padding: var(--s-sm) var(--s-md); font-size: var(--t-base); color: var(--c-text-3); cursor: pointer; font-weight: 600; position: relative; }
.cp__tab.is-active { color: var(--c-brand); }
.cp__tab.is-active::after { content: ''; position: absolute; left: var(--s-sm); right: var(--s-sm); bottom: -1px; height: 3px; background: var(--c-brand); border-radius: var(--r-capsule); }

/* KPI */
.cp__kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); }
.soft-kpi { padding: var(--s-md) var(--s-lg); border-radius: var(--r-xl); }
.soft-kpi__label { font-size: var(--t-sm); color: var(--c-text-2); }
.soft-kpi__value { font-size: var(--t-2xl); font-weight: 700; line-height: 1.3; margin-top: 4px; font-variant-numeric: tabular-nums; }
.soft-kpi__sub { font-size: var(--t-xs); margin-top: 4px; font-weight: 600; }

/* 主体 grid */
.cp__body { display: grid; grid-template-columns: 1fr 360px; gap: var(--s-md); align-items: start; }
.cp__main { display: flex; flex-direction: column; gap: var(--s-md); min-width: 0; }
.cp__side { display: flex; flex-direction: column; gap: var(--s-md); }
.cp__card-title { font-size: var(--t-md); font-weight: 700; margin: 0; }
.cp__card-title-sm { font-size: var(--t-base); font-weight: 700; margin: 0; }
.cp__sub-title { font-size: var(--t-sm); font-weight: 700; color: var(--c-text-2); margin: var(--s-md) 0 var(--s-xs); }
.cp__empty { font-size: var(--t-xs); color: var(--c-text-3); text-align: center; padding: var(--s-md) 0; }

/* 档案 kv */
.kv-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 0 var(--s-lg); }
.kv { display: flex; justify-content: space-between; gap: var(--s-sm); font-size: var(--t-sm); padding: 8px 0; border-bottom: 1px dashed var(--c-border-light); }
.kv span { color: var(--c-text-3); flex-shrink: 0; }
.kv strong { color: var(--c-text-2); font-weight: 600; text-align: right; }

/* 会员卡 */
.card-row { padding: var(--s-sm) 0; border-bottom: 1px dashed var(--c-border-light); }
.card-row__line { display: flex; align-items: center; gap: var(--s-xs); }
.card-row__ic { color: var(--c-brand); flex-shrink: 0; }
.card-row__name { flex: 1; min-width: 0; font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.card-row__count { font-weight: 500; font-size: var(--t-xs); color: var(--c-text-3); margin-left: 6px; }
.card-row__amt { font-size: var(--t-sm); font-weight: 700; color: var(--c-brand); font-variant-numeric: tabular-nums; }
.card-row__sub { font-size: var(--t-xs); color: var(--c-text-3); padding-left: 23px; margin-top: 2px; }

/* 订单收费子项明细 */
.order-items { margin: 6px 0 2px 23px; padding: 8px 12px; background: var(--c-surface); border-radius: var(--r-lg); display: flex; flex-direction: column; gap: 4px; }
.order-item { display: flex; align-items: center; gap: var(--s-sm); font-size: var(--t-xs); color: var(--c-text-2); }
.order-item__name { flex: 1; min-width: 0; }
.order-item__qty { color: var(--c-text-3); font-variant-numeric: tabular-nums; }
.order-item__amt { font-weight: 600; color: var(--c-text); font-variant-numeric: tabular-nums; min-width: 72px; text-align: right; }

/* 价值画像 RFM */
.rfm-hero { display: flex; flex-wrap: wrap; align-items: center; gap: var(--s-md); padding: var(--s-md) var(--s-lg); border-radius: var(--r-lg); background: linear-gradient(135deg, var(--c-info-bg), var(--c-surface)); margin-bottom: var(--s-lg); }
.rfm-hero__seg { font-size: var(--t-lg, 18px); font-weight: 800; color: var(--c-brand); }
.rfm-hero__chips { display: flex; flex-wrap: wrap; gap: 8px; }
.rfm-chip { font-size: var(--t-xs); color: var(--c-text-2); background: var(--c-surface); border: 1px solid var(--c-border-light); border-radius: 999px; padding: 3px 10px; }
.rfm-scores { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--s-md); margin-bottom: var(--s-lg); }
.rfm-score__head { display: flex; align-items: baseline; justify-content: space-between; font-size: var(--t-sm); color: var(--c-text-2); margin-bottom: 6px; }
.rfm-score__head strong { font-size: 20px; font-weight: 800; color: var(--c-text); font-variant-numeric: tabular-nums; }
.rfm-score__head strong i { font-style: normal; font-size: var(--t-xs); color: var(--c-text-4); font-weight: 500; }
.rfm-bar { height: 8px; border-radius: 999px; background: var(--c-surface-hover, #eef0f6); overflow: hidden; }
.rfm-bar__fill { height: 100%; border-radius: 999px; background: linear-gradient(90deg, var(--c-brand), var(--c-blue, #5b8def)); transition: width .4s ease; }
.rfm-score__sub { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 6px; }
.rfm-metrics { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); padding: var(--s-md) 0; border-top: 1px solid var(--c-border-light); border-bottom: 1px solid var(--c-border-light); }
.rfm-metric { display: flex; flex-direction: column; gap: 4px; }
.rfm-metric span { font-size: var(--t-xs); color: var(--c-text-3); }
.rfm-metric strong { font-size: var(--t-md, 16px); font-weight: 700; color: var(--c-text); font-variant-numeric: tabular-nums; }
.rfm-note { font-size: var(--t-xs); color: var(--c-text-4); margin-top: var(--s-md); line-height: 1.6; }

/* 积分流水 */
.ledger-row { display: flex; align-items: center; gap: var(--s-sm); font-size: var(--t-xs); padding: 6px 0; border-bottom: 1px dashed var(--c-border-light); }
.ledger-row__reason { flex: 1; min-width: 0; color: var(--c-text-2); }
.ledger-row__delta { font-weight: 700; font-variant-numeric: tabular-nums; color: var(--c-text-3); }
.ledger-row__delta.is-pos { color: var(--c-teal-dark, var(--c-teal)); }
.ledger-row__bal { color: var(--c-text-3); font-variant-numeric: tabular-nums; }
.ledger-row__date { color: var(--c-text-4); margin-left: auto; }

/* 占位 */
.cp__placeholder-card { min-height: 240px; }
.ph { display: flex; flex-direction: column; align-items: center; text-align: center; gap: var(--s-sm); padding: var(--s-xl) var(--s-lg); color: var(--c-text-3); }
.ph__ic { color: var(--c-brand); opacity: .7; }
.ph__title { font-size: var(--t-md); font-weight: 700; color: var(--c-text-2); }
.ph__desc { font-size: var(--t-sm); line-height: var(--lh-base, 1.7); max-width: 520px; }
.ph__desc b { color: var(--c-text-2); }
.ph--inline { min-height: 0; padding: var(--s-md) 0 0; }
.ph--inline .ph__desc { font-size: var(--t-xs); max-width: none; text-align: left; }

/* 右侧 */
.side-card { background: var(--c-surface); }
.side-empty { font-size: var(--t-xs); color: var(--c-text-3); line-height: 1.6; }
.risks { display: flex; flex-direction: column; gap: var(--s-sm); }
.comp-cell { display: flex; align-items: center; gap: 6px; padding: 6px var(--s-md); border-radius: var(--r-lg); font-size: var(--t-sm); color: var(--c-text); }
.comp-cell--success { background: var(--c-success-bg); color: var(--c-teal-fg); }
.comp-cell--danger { background: var(--c-danger-bg); color: var(--c-danger-fg); }
.comp-cell--info { background: var(--c-info-bg); color: var(--c-blue); }
.comp-cell--draft { background: var(--c-draft-bg); color: var(--c-draft-fg); }
.comp-cell__dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }

.reminders { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: var(--s-sm); }
.reminder { display: flex; gap: var(--s-sm); align-items: flex-start; font-size: var(--t-sm); color: var(--c-text-2); line-height: var(--lh-sm, 1.5); }
.reminder__dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; margin-top: 6px; }
.reminder__dot--danger { background: var(--c-danger-fg); }
.reminder__dot--warning { background: var(--c-warning-fg); }
.reminder__dot--success { background: var(--c-teal); }

@media (max-width: 1024px) {
  .cp__body { grid-template-columns: 1fr; }
  .cp__kpis { grid-template-columns: repeat(2, 1fr); }
  .hero { flex-wrap: wrap; }
  .kv-grid { grid-template-columns: 1fr; }
}
</style>
