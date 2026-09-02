<script setup lang="ts">
/* ============================================================
 * M4-10 零售开方开单（/prescription）—— 独立入口，不承接诊疗主线
 * 用于零售 / 药妆 / 产品现场开单：选客户（建档客户或散客）→ 选品 →
 * 金额汇总 → 双签层级预览 → 提交生成零售订单（不关联咨询方案单）。
 * 医美诊疗项目的开单收款走「咨询 → 医师」主线，审核通过时自动生成缴费单，不在本页。
 * 数据源 customer / order / settings store；权限 prescription:create/edit。
 * 双签阈值由设置中心下发，页面不硬编码。
 * ============================================================ */
import { computed, ref, watch, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { type OrderItem } from '@/stores/order'
import { useAuthStore } from '@/stores/auth'
import { useSettingsStore } from '@/stores/settings'
import { useStoreContext } from '@/stores/storeContext'
import { useToast } from '@/composables/useToast'
import { listCustomers, type CustomerDTO } from '@/api/customer'
import { createRetailOrder } from '@/api/order'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'

const router = useRouter()
const auth = useAuthStore()
const settings = useSettingsStore()
const storeCtx = useStoreContext()
const toast = useToast()

const canEdit = computed(() => auth.can('prescription:create') || auth.can('prescription:edit'))

// ---- ① 客户选择：建档客户（姓名/手机号真实搜索）。散客须先建档（后端零售单强制建档客户）----
/** 模板消费的客户形状（与原 mock 客户卡同形状，模板零改动） */
interface RxClient {
  id: string
  name: string
  level: string
  phoneMask: string
  avatarLetter: string
  memberNo?: string
}
function maskPhone(p?: string | null): string {
  if (!p) return '未留手机号'
  const s = String(p).replace(/\s/g, '')
  return /^1\d{10}$/.test(s) ? `${s.slice(0, 3)}****${s.slice(7)}` : s
}
function adaptCustomer(d: CustomerDTO): RxClient {
  return {
    id: d.customerId,
    name: d.name,
    level: d.level || '普通',
    phoneMask: maskPhone(d.phone),
    avatarLetter: (d.name || '?')[0],
  }
}

const keyword = ref('')
const matchedCustomers = ref<RxClient[]>([])
let searchTimer: ReturnType<typeof setTimeout> | undefined
watch(keyword, (kw) => {
  if (searchTimer) clearTimeout(searchTimer)
  const q = kw.trim()
  if (!q) {
    matchedCustomers.value = []
    return
  }
  searchTimer = setTimeout(async () => {
    try {
      // GET /customer?keyword= 分页搜索（真实可用；/customer/search 端点暂不返回结果）
      const res = await listCustomers({ keyword: q, size: 8 })
      matchedCustomers.value = (res.data.content || []).map(adaptCustomer)
    } catch {
      matchedCustomers.value = []
    }
  }, 250)
})

const selectedCustomerId = ref('')
// 散客不再可直接开单（后端未建档 400），保留常量供模板分支兼容，恒为 false
const isWalkin = computed(() => false)
const selectedCustomer = ref<RxClient | null>(null)

function selectCustomer(id: string) {
  selectedCustomerId.value = id
  selectedCustomer.value = matchedCustomers.value.find((c) => c.id === id) || null
}

/** 散客卡：引导先去客情登记建档（不做匿名假单） */
function onWalkin() {
  toast.warning('零售 / 药妆开单要求客户为建档客户，散客请先到「客情登记」建档后再开单')
  router.push('/guest-reg')
}

onMounted(() => {
  if (!storeCtx.loaded) storeCtx.loadStores()
})

// ---- 可选项目目录（演示期静态；后续接 catalog API / 卡项聚合）----
interface CatalogItem {
  id: string
  name: string
  spec: string
  price: number
  category: '项目' | '疗程' | '产品'
}
const CATALOG: CatalogItem[] = [
  { id: 'cat-1', name: '医学修护面膜', spec: '术后修护 5 片/盒', price: 380, category: '产品' },
  { id: 'cat-2', name: '医用保湿防晒乳', spec: 'SPF50+ 50ml', price: 268, category: '产品' },
  { id: 'cat-3', name: '舒缓修护精华', spec: '30ml 药妆', price: 520, category: '产品' },
  { id: 'cat-4', name: '光子嫩肤', spec: '全脸 1 次（体验）', price: 1280, category: '项目' },
  { id: 'cat-5', name: '水光针', spec: '基础 1 次', price: 980, category: '项目' },
  { id: 'cat-6', name: '祛痘护理疗程', spec: '5 次卡', price: 4800, category: '疗程' },
]
const categoryFilter = ref<'全部' | CatalogItem['category']>('全部')
const catalog = computed(() =>
  categoryFilter.value === '全部' ? CATALOG : CATALOG.filter((c) => c.category === categoryFilter.value),
)

// ---- 已选明细 ----
const items = ref<(OrderItem & { uid: string; category: string })[]>([])
function addItem(c: CatalogItem) {
  const exist = items.value.find((i) => i.name === c.name && i.spec === c.spec)
  if (exist) {
    exist.qty += 1
  } else {
    items.value.push({ uid: `li-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`, name: c.name, spec: c.spec, price: c.price, qty: 1, category: c.category })
  }
}
function changeQty(uid: string, delta: number) {
  const it = items.value.find((i) => i.uid === uid)
  if (!it) return
  it.qty = Math.max(1, it.qty + delta)
}
function removeItem(uid: string) {
  items.value = items.value.filter((i) => i.uid !== uid)
}

const total = computed(() => items.value.reduce((s, i) => s + i.qty * i.price, 0))
const totalQty = computed(() => items.value.reduce((s, i) => s + i.qty, 0))

// ---- 双签层级（取自设置中心，禁止硬编码）----
const tier = computed(() => settings.tierFor(total.value))
const tierText = computed(() =>
  tier.value === 'L3' ? 'L3 三签/高审' : tier.value === 'L2' ? 'L2 双签' : 'L1 基础签署',
)
const tierTone = computed<'danger' | 'warning' | 'success'>(() =>
  tier.value === 'L3' ? 'danger' : tier.value === 'L2' ? 'warning' : 'success',
)
const canSubmit = computed(() => !!selectedCustomerId.value && items.value.length > 0 && total.value > 0 && canEdit.value)

// ---- 提交：真实零售订单（createRetailOrder），不关联咨询方案单 / 不走医生审核 ----
const justCreatedNo = ref('')
const submitting = ref(false)
async function submit() {
  if (!canSubmit.value || !selectedCustomerId.value) return
  if (submitting.value) return
  submitting.value = true
  try {
    const res = await createRetailOrder({
      customerId: selectedCustomerId.value,
      storeCode: storeCtx.currentStoreCode,
      consultant: auth.user.staffId,
      project: items.value.map((i) => i.name).slice(0, 3).join('、') || '零售开单',
      items: items.value.map((i) => ({
        itemName: i.name,
        qty: i.qty,
        unitPrice: Math.round(i.price * 100), // 元 → 分
      })),
      operator: auth.user.staffId,
    })
    justCreatedNo.value = res.data.orderNo
    toast.success(`零售订单 ${res.data.orderNo} 已生成，进入待收款流程`)
    items.value = []
  } catch (e: any) {
    toast.error('开单失败：' + (e?.response?.data?.message || e?.message || '网络异常'))
  } finally {
    submitting.value = false
  }
}
function goCashier() {
  router.push('/order')
}

const money = (n: number) => `¥${n.toLocaleString('zh-CN')}`
</script>

<template>
  <div class="rx">
    <!-- 客户选择条 -->
    <CCard title="① 选择客户" class="rx__cust">
      <div class="cust-search">
        <CIcon name="search" :size="15" class="cust-search__icon" />
        <input
          v-model="keyword"
          class="cust-search__input"
          placeholder="搜索姓名 / 手机号选择建档客户，或直接选「散客」开单"
        />
      </div>
      <div class="custs">
        <button
          class="cust"
          :class="{ 'is-active': false }"
          @click="onWalkin"
        >
          <span class="cust__avatar cust__avatar--walk"><CIcon name="user" :size="16" /></span>
          <span class="cust__meta">
            <span class="cust__name">散客（未建档）</span>
            <span class="cust__sub">点击先建档，再开零售单</span>
          </span>
        </button>
        <button
          v-for="c in matchedCustomers"
          :key="c.id"
          class="cust"
          :class="{ 'is-active': selectedCustomerId === c.id }"
          @click="selectCustomer(c.id)"
        >
          <span class="cust__avatar">{{ c.avatarLetter || c.name[0] }}</span>
          <span class="cust__meta">
            <span class="cust__name">{{ c.name }}
              <span class="cust__lvl">{{ c.level }}</span>
            </span>
            <span class="cust__sub">{{ c.phoneMask }} · {{ c.memberNo || '非会员' }}</span>
          </span>
          <CIcon v-if="selectedCustomerId === c.id" name="check" :size="16" class="cust__ok" />
        </button>
      </div>
      <div v-if="keyword && !matchedCustomers.length" class="rx__empty">
        未找到建档客户，可直接选「散客」开单，或先到
        <CButton variant="ghost" size="sm" @click="router.push('/guest-reg')">客情登记</CButton>
        建档后再开单。
      </div>
    </CCard>

    <div class="rx__grid">
      <!-- 左：项目目录 -->
      <CCard title="② 选择项目 / 疗程 / 产品" class="rx__catalog">
        <div class="cat-filter">
          <button
            v-for="f in (['全部','产品','项目','疗程'] as const)"
            :key="f"
            class="chip"
            :class="{ 'is-active': categoryFilter === f }"
            @click="categoryFilter = f"
          >{{ f }}</button>
        </div>
        <div class="catalog">
          <button v-for="c in catalog" :key="c.id" class="prod" :disabled="!canEdit" @click="addItem(c)">
            <span class="prod__cat">{{ c.category }}</span>
            <span class="prod__name">{{ c.name }}</span>
            <span class="prod__spec">{{ c.spec }}</span>
            <span class="prod__price">{{ money(c.price) }}</span>
            <CIcon name="plus" :size="16" class="prod__add" />
          </button>
        </div>
      </CCard>

      <!-- 右：订单明细 + 结算 -->
      <CCard title="③ 开单明细与结算" class="rx__cart">
        <div v-if="selectedCustomer" class="patient">
          <span class="patient__avatar">{{ selectedCustomer.avatarLetter }}</span>
          <div class="patient__meta">
            <div class="patient__name">{{ selectedCustomer.name }}
              <span class="patient__id">{{ selectedCustomer.id }}</span>
            </div>
            <div class="patient__sub">{{ selectedCustomer.phoneMask }} · {{ selectedCustomer.level }} 级客户</div>
          </div>
        </div>
        <div v-else-if="isWalkin" class="patient">
          <span class="patient__avatar patient__avatar--walk"><CIcon name="user" :size="18" /></span>
          <div class="patient__meta">
            <div class="patient__name">散客（未建档）</div>
            <div class="patient__sub">零售单 · 不关联诊疗档案</div>
          </div>
        </div>
        <div v-else class="patient patient--empty">请先在上方选择客户（建档客户或散客）</div>

        <div class="lines">
          <div v-for="i in items" :key="i.uid" class="line">
            <div class="line__info">
              <span class="line__name">{{ i.name }}</span>
              <span class="line__spec">{{ i.spec }} · {{ money(i.price) }}</span>
            </div>
            <div class="line__ctrl">
              <button class="qty" :disabled="!canEdit" @click="changeQty(i.uid, -1)">−</button>
              <span class="qty__num">{{ i.qty }}</span>
              <button class="qty" :disabled="!canEdit" @click="changeQty(i.uid, 1)">+</button>
              <span class="line__sum">{{ money(i.qty * i.price) }}</span>
              <button class="line__del" :disabled="!canEdit" @click="removeItem(i.uid)">
                <CIcon name="delete" :size="14" />
              </button>
            </div>
          </div>
          <div v-if="!items.length" class="lines__empty">尚未添加项目，点击左侧项目加入明细</div>
        </div>

        <div class="settle">
          <div class="tier">
            <span class="tier__label">签署层级</span>
            <CStatusPill :status="tierTone">{{ tierText }}</CStatusPill>
            <span class="tier__hint">阈值由设置中心下发 · L1 ¥{{ settings.system.dualSign.l1 }} / L2 ¥{{ settings.system.dualSign.l2 }} / L3 ¥{{ settings.system.dualSign.l3 }}</span>
          </div>
          <div class="total">
            <span class="total__label">合计 <em>{{ totalQty }}</em> 项</span>
            <span class="total__num">{{ money(total) }}</span>
          </div>
          <div class="actions">
            <CButton variant="ghost" @click="items = []">清空</CButton>
            <CButton
              v-perm.disable="['prescription:create','prescription:edit']"
              variant="primary"
              :disabled="!canSubmit"
              @click="submit"
            >提交开单</CButton>
          </div>
          <p v-if="!canEdit" class="no-perm">当前角色无开方/开单权限。</p>
        </div>

        <div v-if="justCreatedNo" class="done">
          <CIcon name="check-square" :size="18" class="done__icon" />
          <div class="done__text">
            <strong>零售订单 {{ justCreatedNo }} 已生成</strong>
            <span>已进入{{ tier === 'L1' ? '待收款' : '签核' }}流程，请到收银台收款。</span>
          </div>
          <CButton variant="primary" size="sm" @click="goCashier">前往收银</CButton>
        </div>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.rx { display: flex; flex-direction: column; gap: var(--s-md); }
.rx__empty { color: var(--c-text-3); font-size: var(--t-sm); display: flex; align-items: center; gap: var(--s-xs); margin-top: var(--s-sm); }

.cust-search { position: relative; display: flex; align-items: center; margin-bottom: var(--s-sm); }
.cust-search__icon { position: absolute; left: var(--s-sm); color: var(--c-text-3); pointer-events: none; }
.cust-search__input { width: 100%; padding: 8px var(--s-md) 8px 34px; border: 1px solid var(--c-border); border-radius: var(--r-md); background: var(--c-surface); font-size: var(--t-sm); color: var(--c-text); }
.cust-search__input:focus { outline: none; border-color: var(--c-brand); box-shadow: 0 0 0 2px var(--c-brand-soft); }

.custs { display: flex; gap: var(--s-sm); flex-wrap: wrap; }
.cust { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-sm) var(--s-md); border: 1px solid var(--c-border); border-radius: var(--r-lg); background: var(--c-surface); cursor: pointer; min-width: 220px; text-align: left; transition: all .15s; }
.cust:hover { border-color: var(--c-brand); }
.cust.is-active { border-color: var(--c-brand); background: var(--c-brand-soft); box-shadow: 0 0 0 2px var(--c-brand-soft); }
.cust__avatar { width: 34px; height: 34px; border-radius: var(--r-avatar); background: var(--c-brand-soft); color: var(--c-brand); font-weight: 600; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.cust__avatar--walk { background: var(--c-bg-page); color: var(--c-text-3); }
.cust__meta { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.cust__name { font-weight: 600; font-size: var(--t-sm); display: flex; align-items: center; gap: 4px; }
.cust__lvl { font-size: var(--t-xs); font-weight: 600; color: var(--c-brand); background: var(--c-brand-soft); border-radius: var(--r-sm); padding: 1px 6px; }
.cust__sub { font-size: var(--t-xs); color: var(--c-text-3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cust__ok { color: var(--c-brand); }

.rx__grid { display: grid; grid-template-columns: 1.15fr 1fr; gap: var(--s-md); align-items: start; }

.cat-filter { display: flex; gap: var(--s-xs); margin-bottom: var(--s-sm); }
.chip { padding: 4px 12px; border-radius: 999px; border: 1px solid var(--c-border); background: var(--c-surface); color: var(--c-text-2); font-size: var(--t-sm); cursor: pointer; }
.chip.is-active { background: var(--c-brand); color: #fff; border-color: var(--c-brand); }
.catalog { display: grid; grid-template-columns: repeat(2, 1fr); gap: var(--s-sm); }
.prod { position: relative; display: flex; flex-direction: column; gap: 2px; padding: var(--s-sm) var(--s-md); border: 1px solid var(--c-border-light); border-radius: var(--r-lg); background: var(--c-surface); cursor: pointer; text-align: left; transition: all .15s; }
.prod:hover:not(:disabled) { border-color: var(--c-brand); background: var(--c-brand-soft); }
.prod:disabled { opacity: .5; cursor: not-allowed; }
.prod__cat { font-size: var(--t-xs); color: var(--c-text-3); }
.prod__name { font-weight: 600; font-size: var(--t-sm); }
.prod__spec { font-size: var(--t-xs); color: var(--c-text-3); }
.prod__price { font-size: var(--t-base); color: var(--c-brand); font-weight: 600; margin-top: 2px; }
.prod__add { position: absolute; top: var(--s-sm); right: var(--s-sm); color: var(--c-brand); }

.patient { display: flex; align-items: center; gap: var(--s-sm); padding-bottom: var(--s-sm); border-bottom: 1px solid var(--c-border-light); }
.patient--empty { color: var(--c-text-3); font-size: var(--t-sm); }
.patient__avatar { width: 38px; height: 38px; border-radius: var(--r-avatar); background: var(--c-brand-soft); color: var(--c-brand); font-weight: 600; display: flex; align-items: center; justify-content: center; font-size: var(--t-lg); }
.patient__avatar--walk { background: var(--c-bg-page); color: var(--c-text-3); }
.patient__name { font-weight: 600; display: flex; align-items: center; gap: var(--s-xs); }
.patient__id { font-size: var(--t-xs); color: var(--c-text-3); font-weight: 400; }
.patient__sub { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }

.lines { margin: var(--s-sm) 0; display: flex; flex-direction: column; gap: var(--s-xs); min-height: 80px; }
.lines__empty { color: var(--c-text-3); font-size: var(--t-sm); text-align: center; padding: var(--s-lg) 0; }
.line { display: flex; align-items: center; justify-content: space-between; gap: var(--s-sm); padding: var(--s-xs) 0; border-bottom: 1px dashed var(--c-border-light); }
.line__info { display: flex; flex-direction: column; }
.line__name { font-size: var(--t-sm); font-weight: 500; }
.line__spec { font-size: var(--t-xs); color: var(--c-text-3); }
.line__ctrl { display: flex; align-items: center; gap: var(--s-xs); }
.qty { width: 26px; height: 26px; border-radius: var(--r-md); border: 1px solid var(--c-border); background: var(--c-surface); cursor: pointer; font-size: var(--t-base); line-height: 1; display: flex; align-items: center; justify-content: center; color: var(--c-text-2); }
.qty:disabled { opacity: .4; cursor: not-allowed; }
.qty__num { min-width: 20px; text-align: center; font-size: var(--t-sm); }
.line__sum { min-width: 72px; text-align: right; font-size: var(--t-sm); font-weight: 600; }
.line__del { border: none; background: transparent; color: var(--c-text-4); cursor: pointer; padding: 4px; }
.line__del:hover { color: var(--c-danger); }

.settle { border-top: 1px solid var(--c-border); padding-top: var(--s-sm); }
.tier { display: flex; align-items: center; gap: var(--s-xs); font-size: var(--t-sm); flex-wrap: wrap; }
.tier__label { color: var(--c-text-2); }
.tier__hint { color: var(--c-text-3); font-size: var(--t-xs); width: 100%; }
.total { display: flex; align-items: baseline; justify-content: space-between; margin: var(--s-sm) 0; }
.total__label { color: var(--c-text-2); font-size: var(--t-sm); }
.total__label em { font-style: normal; color: var(--c-brand); font-weight: 600; padding: 0 2px; }
.total__num { font-size: var(--t-xxl); font-weight: 700; color: var(--c-brand); }
.actions { display: flex; gap: var(--s-sm); justify-content: flex-end; }
.no-perm { color: var(--c-danger); font-size: var(--t-xs); text-align: right; margin-top: var(--s-xs); }

.done { display: flex; align-items: center; gap: var(--s-sm); margin-top: var(--s-md); padding: var(--s-sm) var(--s-md); background: var(--c-success-soft, #eaf8ef); border: 1px solid var(--c-success, #22a06b); border-radius: var(--r-lg); }
.done__icon { color: var(--c-success, #22a06b); flex-shrink: 0; }
.done__text { flex: 1; display: flex; flex-direction: column; font-size: var(--t-sm); }
.done__text span { color: var(--c-text-3); font-size: var(--t-xs); }

@media (max-width: 1024px) {
  .rx__grid { grid-template-columns: 1fr; }
  .catalog { grid-template-columns: 1fr; }
}
</style>
