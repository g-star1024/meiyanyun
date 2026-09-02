<script setup lang="ts">
/* ============================================================
 * 积分商城 /m3-points-mall（M3-05/20）
 * Desktop：4 KPI + Tab（商品管理 / 兑换审核 / 积分规则）
 * Tablet：3 KPI + 商品卡片列表 + 底部「新建商品」FAB。
 * ============================================================ */
import { computed, onMounted, reactive, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CTextarea from '@/components/CTextarea.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import {
  usePointsStore,
  type ProductCategory,
} from '@/stores/points'
import { PRODUCT_STATUS, REDEMPTION_STATUS, dictPill } from '@/config/dictionary'

const store = usePointsStore()
onMounted(() => store.seed())

type Tab = 'PRODUCTS' | 'AUDIT' | 'RULES'
const tab = ref<Tab>('PRODUCTS')

const kpis = computed(() => [
  { label: '在售商品', icon: 'package', value: String(store.onSaleCount), tone: 'text' as const, sub: `共 ${store.products.length} 件` },
  { label: '本月兑换', icon: 'mall', value: `${(store.monthRedeemed / 10000).toFixed(1)} 万`, tone: 'brand' as const, sub: `${store.redemptions.length} 笔` },
  { label: '积分池余额', icon: 'mall', value: `${(store.totalPool / 10000).toFixed(1)} 万`, tone: 'teal' as const, sub: '分' },
  { label: '待审核兑换', icon: 'mall', value: String(store.pendingCount), tone: 'danger' as const, sub: '需 24h 内处理' },
])

// 商品列表筛选
const categoryOptions = [
  { value: 'ALL', label: '全部类型' },
  { value: 'PROJECT', label: '项目' },
  { value: 'PHYSICAL', label: '实物' },
  { value: 'COUPON', label: '优惠券' },
  { value: 'SERVICE', label: '服务' },
]
const statusOptions = [
  { value: 'ALL', label: '全部状态' },
  { value: 'ON_SALE', label: '在售' },
  { value: 'LOW_STOCK', label: '低库存' },
  { value: 'OFF_SHELF', label: '已下架' },
  { value: 'PENDING', label: '审核中' },
]


function fmtDate(iso: string) {
  const d = new Date(iso)
  return `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

// 新建商品弹层
const showForm = ref(false)
// 商品卡图标占位字：按分类自动派生（项目/实物/券/服务）
const CATEGORY_IMG: Record<ProductCategory, string> = {
  PROJECT: '项目', PHYSICAL: '实物', COUPON: '券', SERVICE: '服务',
}
const form = reactive({
  name: '',
  category: 'PHYSICAL' as ProductCategory,
  pointsCost: '1000',
  stock: '100',
  description: '',
})
function openForm() {
  form.name = ''
  form.category = 'PHYSICAL'
  form.pointsCost = '1000'
  form.stock = '100'
  form.description = ''
  showForm.value = true
}
function submitForm() {
  if (!form.name.trim()) return
  const p = store.createProduct({
    name: form.name.trim(),
    category: form.category,
    pointsCost: Number(form.pointsCost) || 0,
    stock: form.category === 'COUPON' ? -1 : (Number(form.stock) || 0),
    imageText: CATEGORY_IMG[form.category],
    description: form.description.trim() || undefined,
  })
  if (p) showForm.value = false
}

// 审核操作
function approve(id: string) { store.approveRedemption(id) }
function reject(id: string) { store.rejectRedemption(id, '信息不全') }

// 规则表单
const ruleDraft = reactive({ ...store.rule })
const ruleDirty = ref(false)
function syncRule<K extends keyof typeof ruleDraft>(key: K, v: (typeof ruleDraft)[K]) {
  ruleDraft[key] = v
  ruleDirty.value = true
}
function saveRule() {
  if (store.saveRule({ ...ruleDraft })) {
    ruleDirty.value = false
    toast.value = '积分规则已保存'
    setTimeout(() => (toast.value = ''), 2000)
  }
}

const toast = ref('')
</script>

<template>
  <div class="pm">
    <div class="pm__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <!-- Tabs -->
    <CCard class="pm__tabs" padding="none">
      <div class="tabs">
        <button class="tab" :class="{ 'tab--active': tab === 'PRODUCTS' }" @click="tab = 'PRODUCTS'">商品管理</button>
        <button class="tab" :class="{ 'tab--active': tab === 'AUDIT' }" @click="tab = 'AUDIT'">
          兑换审核<span v-if="store.pendingCount" class="tab__badge">{{ store.pendingCount }}</span>
        </button>
        <button class="tab" :class="{ 'tab--active': tab === 'RULES' }" @click="tab = 'RULES'">积分规则</button>
      </div>
    </CCard>

    <!-- 商品管理 -->
    <template v-if="tab === 'PRODUCTS'">
      <CCard class="pm__filters" padding="none">
        <div class="filters">
          <CSelect v-model="store.filterCategory" :options="categoryOptions" />
          <CSelect v-model="store.filterStatus" :options="statusOptions" />
          <div class="filters__search">
            <CIcon name="search" :size="14" />
            <input v-model="store.keyword" placeholder="搜索商品名称 / SKU" />
          </div>
          <CButton variant="secondary" size="sm" v-perm.disable="'points:edit'" class="filters__batch">批量上架</CButton>
          <CButton variant="primary" size="sm" v-perm.disable="'points:edit'" class="filters__create" @click="openForm">
            <CIcon name="plus" :size="14" />新建商品
          </CButton>
        </div>
      </CCard>

      <!-- Desktop: 表格 -->
      <CCard class="pm__table-card" padding="none">        <div class="table-scroll">
          <table class="ptable">
            <thead>
              <tr>
                <th>商品</th><th>类型</th><th>积分价</th><th>库存</th><th>已兑</th><th>状态</th><th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="p in store.filteredProducts" :key="p.id">
                <td>
                  <div class="prod">
                    <div class="prod__img"><span>{{ p.imageText }}</span></div>
                    <div>
                      <div class="prod__name">{{ p.name }}</div>
                      <div v-if="p.description" class="prod__desc">{{ p.description }}</div>
                      <div class="prod__sku">SKU: {{ p.sku }}</div>
                    </div>
                  </div>
                </td>
                <td>{{ store.CATEGORY_LABEL[p.category] }}</td>
                <td class="num num--brand">{{ p.pointsCost.toLocaleString() }} 分</td>
                <td class="num" :class="{ 'num--danger': p.stock === 0 }">{{ p.stock < 0 ? '∞' : p.stock }}</td>
                <td class="num">{{ p.redeemedCount }}</td>
                <td><CStatusPill :status="dictPill(PRODUCT_STATUS[p.status]).status">{{ dictPill(PRODUCT_STATUS[p.status]).text }}</CStatusPill></td>
                <td>
                  <div class="ops">
                    <button class="ops__btn" v-perm.disable="'points:edit'">编辑</button>
                    <button class="ops__btn" v-perm.disable="'points:edit'" @click="store.toggleShelf(p.id)">
                      {{ p.status === 'OFF_SHELF' ? '上架' : '下架' }}
                    </button>
                  </div>
                </td>
              </tr>
              <tr v-if="store.filteredProducts.length === 0">
                <td colspan="7" class="empty-row">暂无商品</td>
              </tr>
            </tbody>
          </table>
        </div>
      </CCard>

      <!-- Mobile: 卡片列表 -->
      <CCard class="pm__mobile-list" padding="none">
        <div v-for="p in store.filteredProducts" :key="p.id" class="mprod">
          <div class="mprod__img"><span>{{ p.imageText }}</span></div>
          <div class="mprod__main">
            <div class="mprod__name">{{ p.name }}</div>
            <div v-if="p.description" class="mprod__desc">{{ p.description }}</div>
            <div class="mprod__meta">
              <span class="mprod__points">{{ p.pointsCost.toLocaleString() }} 分</span>
              <span>库存 {{ p.stock < 0 ? '∞' : p.stock }}</span>
              <span>已兑 {{ p.redeemedCount }}</span>
            </div>
          </div>
          <CStatusPill :status="dictPill(PRODUCT_STATUS[p.status]).status">{{ dictPill(PRODUCT_STATUS[p.status]).text }}</CStatusPill>
        </div>
        <div v-if="store.filteredProducts.length === 0" class="empty-row">暂无商品</div>
      </CCard>
    </template>

    <!-- 兑换审核 -->
    <template v-else-if="tab === 'AUDIT'">
      <CCard title="待审核兑换" padding="none">
        <div class="audit-list">
          <div v-for="r in store.redemptions" :key="r.id" class="audit-row">
            <div class="audit-row__main">
              <div class="audit-row__top">
                <span class="audit-row__no">{{ r.orderNo }}</span>
                <CStatusPill :status="dictPill(REDEMPTION_STATUS[r.status]).status">{{ dictPill(REDEMPTION_STATUS[r.status]).text }}</CStatusPill>
              </div>
              <div class="audit-row__body">
                <div class="audit-row__cust">
                  <CIcon name="user" :size="14" />{{ r.customerName }}
                </div>
                <div class="audit-row__prod">{{ r.productName }} × {{ r.qty }}</div>
                <div class="audit-row__points">{{ (r.pointsCost * r.qty).toLocaleString() }} 分</div>
                <div class="audit-row__time">{{ fmtDate(r.createdAt) }}</div>
              </div>
              <div v-if="r.address" class="audit-row__addr">
                <CIcon name="phone" :size="12" />{{ r.phone }} · {{ r.address }}
              </div>
              <div v-if="r.note" class="audit-row__note">驳回原因：{{ r.note }}</div>
            </div>
            <div v-if="r.status === 'PENDING'" class="audit-row__ops">
              <CButton variant="ghost" size="sm" v-perm.disable="'points:approve'" @click="reject(r.id)">驳回</CButton>
              <CButton variant="primary" size="sm" v-perm.disable="'points:approve'" @click="approve(r.id)">通过</CButton>
            </div>
          </div>
        </div>
      </CCard>
    </template>

    <!-- 积分规则 -->
    <template v-else>
      <CCard title="积分获取与过期规则" padding="lg">
        <div class="rule-grid">
          <CInput label="消费 1 元 = 多少分" type="number" :model-value="String(ruleDraft.earnPerYuan)" @update:model-value="(v) => syncRule('earnPerYuan', Number(v) || 0)" />
          <CInput label="积分有效期（月）" type="number" :model-value="String(ruleDraft.expireMonths)" @update:model-value="(v) => syncRule('expireMonths', Number(v) || 0)" />
          <CInput label="每日签到奖励（分）" type="number" :model-value="String(ruleDraft.signInReward)" @update:model-value="(v) => syncRule('signInReward', Number(v) || 0)" />
          <CInput label="生日月积分倍率" type="number" :model-value="String(ruleDraft.birthdayMultiplier)" @update:model-value="(v) => syncRule('birthdayMultiplier', Number(v) || 1)" />
          <CInput label="推荐新客奖励（分）" type="number" :model-value="String(ruleDraft.referralReward)" @update:model-value="(v) => syncRule('referralReward', Number(v) || 0)" />
        </div>
        <label class="rule-switch">
          <span class="rule-switch__text">
            <span class="rule-switch__title">允许手动发放/扣减积分</span>
            <span class="rule-switch__desc">店长可在客户详情页手动调整积分（写入审计）</span>
          </span>
          <input type="checkbox" :checked="ruleDraft.manualGrantEnabled" @change="syncRule('manualGrantEnabled', ($event.target as HTMLInputElement).checked)" />
        </label>
        <div class="rule-actions">
          <CButton variant="primary" v-perm.disable="'points:edit'" :disabled="!ruleDirty" @click="saveRule">
            <CIcon name="check" :size="16" />保存规则
          </CButton>
        </div>
      </CCard>
    </template>

    <!-- 新建商品弹层 -->
    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <CCard class="modal" title="新建积分商品" padding="lg">
        <div class="form">
          <CInput label="商品名称" v-model="form.name" placeholder="如：医用面膜 1 片装" />
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">分类</label>
              <CSelect v-model="form.category" width="100%" :options="[
                { value: 'PROJECT', label: '项目' },
                { value: 'PHYSICAL', label: '实物' },
                { value: 'COUPON', label: '优惠券' },
                { value: 'SERVICE', label: '服务' },
              ]" />
            </div>
            <CInput label="积分价格" type="number" v-model="form.pointsCost" />
          </div>
          <CInput v-if="form.category !== 'COUPON'" label="库存数量（优惠券不限库存）" type="number" v-model="form.stock" />
          <CTextarea label="商品说明（可选）" v-model="form.description" placeholder="用于商品详情展示，如规格、使用规则、有效期等" />
          <p class="form__tip">SKU 编号由系统自动生成；实物商品兑换时会员需填写收货信息，进入审核队列。</p>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showForm = false">取消</CButton>
          <CButton variant="primary" :disabled="!form.name.trim()" @click="submitForm">创建</CButton>
        </template>
      </CCard>
    </div>

    <transition name="toast">
      <div v-if="toast" class="toast"><CIcon name="check" :size="16" />{{ toast }}</div>
    </transition>
  </div>
</template>

<style scoped>
.pm { display: flex; flex-direction: column; gap: var(--s-lg); }
.pm__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .pm__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }

.tabs { display: flex; padding: var(--s-xs); gap: var(--s-xs); }
.tab {
  flex: 1; padding: var(--s-sm) var(--s-md);
  background: transparent; border: none; border-radius: var(--r-md);
  font-size: var(--t-sm); color: var(--c-text-2); font-weight: 600;
  cursor: pointer; display: inline-flex; align-items: center; justify-content: center; gap: var(--s-xs);
  transition: background 0.15s, color 0.15s;
}
.tab:hover { background: var(--c-brand-soft); }
.tab--active { background: var(--c-brand-soft); color: var(--c-brand); }
.tab__badge {
  display: inline-flex; align-items: center; justify-content: center;
  min-width: 18px; height: 18px; padding: 0 6px;
  background: var(--c-danger-fg); color: #fff;
  border-radius: var(--r-capsule);
  font-size: var(--t-xs);
}

.filters { display: flex; gap: var(--s-sm); padding: var(--s-md); align-items: center; flex-wrap: nowrap; overflow-x: auto; }
.filters__search {
  flex: 1; min-width: 200px; height: 36px;
  display: flex; align-items: center; gap: var(--s-xs);
  padding: 0 var(--s-sm);
  border: 1px solid var(--c-border);
  border-radius: var(--r-sm);
  background: var(--c-surface);
}
.filters__search input {
  flex: 1; border: none; outline: none; background: transparent;
  font-size: var(--t-sm); color: var(--c-text);
}
.filters__search .cicon { color: var(--c-text-3); }
.filters__batch { margin-left: auto; }

.table-scroll { overflow-x: auto; }
.ptable { width: 100%; border-collapse: collapse; font-size: var(--t-sm); }
.ptable thead th {
  text-align: left; padding: var(--s-sm) var(--s-md);
  font-size: var(--t-xs); color: var(--c-text-3); font-weight: 600;
  background: var(--c-surface-muted, #f5f6fa);
  border-bottom: 1px solid var(--c-border-light);
  white-space: nowrap;
}
.ptable tbody td {
  padding: var(--s-md);
  border-bottom: 1px solid var(--c-border-light);
  vertical-align: middle;
}
.num { text-align: right; font-variant-numeric: tabular-nums; color: var(--c-text-2); white-space: nowrap; }
.num--brand { color: var(--c-brand); font-weight: 700; font-size: var(--t-md); }
.num--danger { color: var(--c-danger-fg); font-weight: 700; }
.empty-row { text-align: center; color: var(--c-text-3); padding: var(--s-xxl); }

.pm__mobile-list { display: none; }
.mprod {
  display: flex; align-items: center; gap: var(--s-sm);
  padding: var(--s-md);
  border-bottom: 1px solid var(--c-border-light);
}
.mprod:last-child { border-bottom: none; }
.mprod__img {
  width: 44px; height: 44px; border-radius: var(--r-md);
  background: var(--c-disabled-bg); color: var(--c-text-3);
  display: flex; align-items: center; justify-content: center;
  font-size: var(--t-xs); flex-shrink: 0;
}
.mprod__main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 4px; }
.mprod__name { font-size: var(--t-sm); color: var(--c-text); font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.mprod__meta { display: flex; gap: var(--s-sm); font-size: var(--t-xs); color: var(--c-text-3); flex-wrap: wrap; }
.mprod__desc { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; line-height: 1.4; }
.mprod__points { color: var(--c-brand); font-weight: 700; }

.prod { display: flex; gap: var(--s-sm); align-items: center; min-width: 220px; }
.prod__img {
  width: 40px; height: 40px; border-radius: var(--r-md);
  background: var(--c-disabled-bg); color: var(--c-text-3);
  display: flex; align-items: center; justify-content: center;
  font-size: var(--t-xs); flex-shrink: 0;
}
.prod__name { color: var(--c-text); font-weight: 600; }
.prod__desc { color: var(--c-text-3); font-size: var(--t-xs); margin-top: 2px; max-width: 280px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.prod__sku { color: var(--c-text-3); font-size: var(--t-xs); }

.ops { display: flex; gap: var(--s-sm); }
.ops__btn {
  background: none; border: none; padding: 0;
  color: var(--c-brand); font-size: var(--t-sm); cursor: pointer;
}
.ops__btn:hover { text-decoration: underline; }

.audit-list { display: flex; flex-direction: column; }
.audit-row {
  display: flex; align-items: flex-start; justify-content: space-between; gap: var(--s-md);
  padding: var(--s-md) var(--s-lg);
  border-bottom: 1px solid var(--c-border-light);
}
.audit-row:last-child { border-bottom: none; }
.audit-row__main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 6px; }
.audit-row__top { display: flex; justify-content: space-between; align-items: center; }
.audit-row__no { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.audit-row__body { display: flex; flex-wrap: wrap; gap: var(--s-md); font-size: var(--t-sm); color: var(--c-text-2); align-items: center; }
.audit-row__cust { display: inline-flex; align-items: center; gap: 4px; color: var(--c-text); font-weight: 600; }
.audit-row__prod { color: var(--c-text-2); }
.audit-row__points { color: var(--c-brand); font-weight: 700; margin-left: auto; }
.audit-row__time { color: var(--c-text-3); font-size: var(--t-xs); }
.audit-row__addr, .audit-row__note { font-size: var(--t-xs); color: var(--c-text-3); display: flex; align-items: center; gap: 4px; }
.audit-row__note { color: var(--c-danger-fg); }
.audit-row__ops { display: flex; gap: var(--s-xs); flex-shrink: 0; }

.rule-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--s-md); margin-bottom: var(--s-lg); }
.rule-switch {
  display: flex; align-items: center; justify-content: space-between; gap: var(--s-md);
  padding: var(--s-md) var(--s-lg);
  border: 1px solid var(--c-border-light);
  border-radius: var(--r-md);
  cursor: pointer;
}
.rule-switch__text { display: flex; flex-direction: column; gap: 2px; }
.rule-switch__title { font-size: var(--t-sm); color: var(--c-text); font-weight: 600; }
.rule-switch__desc { font-size: var(--t-xs); color: var(--c-text-3); }
.rule-switch input {
  appearance: none; width: 40px; height: 22px; border-radius: var(--r-capsule);
  background: var(--c-border); position: relative; cursor: pointer; transition: background 0.15s;
}
.rule-switch input::after {
  content: ''; position: absolute; top: 2px; left: 2px; width: 18px; height: 18px;
  border-radius: 50%; background: var(--c-surface); box-shadow: 0 1px 2px rgba(0,0,0,.2);
  transition: transform 0.15s;
}
.rule-switch input:checked { background: var(--c-brand); }
.rule-switch input:checked::after { transform: translateX(18px); }
.rule-actions { display: flex; justify-content: flex-end; margin-top: var(--s-lg); }

.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 520px; max-width: 100%; max-height: 90vh; overflow-y: auto; box-shadow: var(--shadow-pop); }
.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.form__row--2 { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.form__tip { margin: 0; font-size: var(--t-xs); color: var(--c-text-2); line-height: 1.5; background: var(--c-brand-soft); border-radius: var(--r-md); padding: var(--s-sm) var(--s-md); }
.form__label { font-size: var(--t-xs); color: var(--c-text-3); }

.toast {
  position: fixed; bottom: var(--s-xl); left: 50%; transform: translateX(-50%);
  display: inline-flex; align-items: center; gap: var(--s-xs);
  padding: var(--s-sm) var(--s-lg);
  background: var(--c-success-fg); color: #fff;
  border-radius: var(--r-capsule);
  font-size: var(--t-sm); font-weight: 600;
  box-shadow: var(--shadow-pop);
  z-index: 300;
}
.toast-enter-active, .toast-leave-active { transition: opacity 0.2s, transform 0.2s; }
.toast-enter-from, .toast-leave-to { opacity: 0; transform: translate(-50%, 10px); }

@media (max-width: 1024px) {
  .pm__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .pm__table-card { display: none; }
  .pm__mobile-list { display: block; }
  .pm__filters { display: none; }
  .rule-grid { grid-template-columns: 1fr; }
  .audit-row { flex-direction: column; }
  .audit-row__ops { width: 100%; justify-content: flex-end; }
  .audit-row__points { margin-left: 0; }
}
</style>
