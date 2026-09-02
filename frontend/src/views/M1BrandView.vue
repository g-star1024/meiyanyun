<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CTextarea from '@/components/CTextarea.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import { useM1BrandStore, type Brand, type CommonStatus, type Product } from '@/stores/m1Brand'
import { useAuthStore } from '@/stores/auth'

const mb = useM1BrandStore()
const auth = useAuthStore()
onMounted(() => mb.seed())

const canEdit = computed(() => auth.can('brand:edit'))

// 选中品牌
const selectedId = ref('')
const selected = computed<Brand | undefined>(() => {
  if (selectedId.value) {
    const b = mb.brand(selectedId.value)
    if (b) return b
  }
  return mb.brands[0]
})
watch(() => mb.brands.length, () => {
  if (!selectedId.value && mb.brands[0]) selectedId.value = mb.brands[0].id
}, { immediate: true })

const tab = ref<'overview' | 'category' | 'product'>('overview')

const keyword = ref('')
const filteredBrands = computed(() => {
  const kw = keyword.value.trim()
  const list = mb.brands
  if (!kw) return list
  return list.filter((b) => `${b.code} ${b.name} ${b.shortName ?? ''} ${b.supplier}`.includes(kw))
})

function statusTone(s: CommonStatus) { return s === 'ACTIVE' ? 'success' : 'disabled' }
function fmtMoney(n: number) { return '¥' + n.toLocaleString('zh-CN') }
function marginRate(p: Product) { return p.listPrice ? Math.round(((p.listPrice - p.costPrice) / p.listPrice) * 100) : 0 }

const STORE_TYPE_LABEL: Record<string, string> = { FLAGSHIP: '旗舰', COMMUNITY: '社区', CLINIC: '诊所' }

// ---- 品牌新建/编辑 ----
const showBrandModal = ref(false)
const editingBrand = ref<Brand | null>(null)
const brandForm = reactive({ code: '', name: '', shortName: '', origin: '', supplier: '', remark: '' })
const brandErr = ref('')
function openBrandCreate() {
  editingBrand.value = null
  Object.assign(brandForm, { code: '', name: '', shortName: '', origin: '', supplier: '', remark: '' })
  brandErr.value = ''; showBrandModal.value = true
}
function openBrandEdit(b: Brand) {
  editingBrand.value = b
  Object.assign(brandForm, { code: b.code, name: b.name, shortName: b.shortName ?? '', origin: b.origin ?? '', supplier: b.supplier, remark: b.remark ?? '' })
  brandErr.value = ''; showBrandModal.value = true
}
function submitBrand() {
  if (!brandForm.name.trim()) { brandErr.value = '请填写品牌名称'; return }
  if (!brandForm.code.trim()) { brandErr.value = '请填写品牌编码'; return }
  if (mb.brands.some((b) => b.code === brandForm.code.trim() && b.id !== editingBrand.value?.id)) { brandErr.value = '品牌编码已存在'; return }
  const payload = {
    code: brandForm.code.trim(), name: brandForm.name.trim(), shortName: brandForm.shortName.trim() || undefined,
    origin: brandForm.origin.trim() || undefined, supplier: brandForm.supplier.trim(), remark: brandForm.remark.trim() || undefined,
  }
  if (editingBrand.value) mb.updateBrand(editingBrand.value.id, payload)
  else { const b = mb.createBrand(payload); selectedId.value = b.id }
  showBrandModal.value = false
}

// ---- 品类弹层 ----
const showCatModal = ref(false)
const catForm = reactive({ name: '', code: '', parentId: '', remark: '' })
const catErr = ref('')
function openCatCreate() {
  Object.assign(catForm, { name: '', code: '', parentId: '', remark: '' })
  catErr.value = ''; showCatModal.value = true
}
function submitCat() {
  if (!selected.value) return
  if (!catForm.name.trim()) { catErr.value = '请填写品类名称'; return }
  mb.createCategory({
    code: catForm.code.trim() || `CT-${Date.now().toString(36).toUpperCase()}`,
    name: catForm.name.trim(), brandId: selected.value.id,
    parentId: catForm.parentId || undefined, remark: catForm.remark.trim() || undefined,
  })
  showCatModal.value = false
}
function toggleCat(id: string, s: CommonStatus) {
  if (!canEdit.value) return
  mb.setCategoryStatus(id, s === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE')
}
function removeCat(id: string) {
  try { mb.deleteCategory(id) } catch (e) { catErr.value = (e as Error).message }
}

// ---- 项目弹层 ----
const showProdModal = ref(false)
const editingProd = ref<Product | null>(null)
const prodErr = ref('')
const prodForm = reactive({
  sku: '', name: '', categoryId: '', unit: '次', listPrice: '', costPrice: '',
  durationMin: '30', storeTypes: [] as string[], remark: '',
})
function openProdCreate() {
  editingProd.value = null
  Object.assign(prodForm, { sku: '', name: '', categoryId: '', unit: '次', listPrice: '', costPrice: '', durationMin: '30', storeTypes: ['FLAGSHIP'], remark: '' })
  prodErr.value = ''; showProdModal.value = true
}
function openProdEdit(p: Product) {
  editingProd.value = p
  Object.assign(prodForm, {
    sku: p.sku, name: p.name, categoryId: p.categoryId, unit: p.unit,
    listPrice: String(p.listPrice), costPrice: String(p.costPrice),
    durationMin: String(p.durationMin), storeTypes: [...p.storeTypes], remark: p.remark ?? '',
  })
  prodErr.value = ''; showProdModal.value = true
}
function toggleStoreType(t: string) {
  const i = prodForm.storeTypes.indexOf(t)
  if (i >= 0) prodForm.storeTypes.splice(i, 1)
  else prodForm.storeTypes.push(t)
}
function submitProd() {
  if (!selected.value) return
  if (!prodForm.name.trim()) { prodErr.value = '请填写项目名称'; return }
  if (!prodForm.sku.trim()) { prodErr.value = '请填写 SKU 编码'; return }
  if (!prodForm.categoryId) { prodErr.value = '请选择品类'; return }
  if (prodForm.storeTypes.length === 0) { prodErr.value = '至少选择一种适用门店类型'; return }
  const payload = {
    sku: prodForm.sku.trim(), name: prodForm.name.trim(), brandId: selected.value.id,
    categoryId: prodForm.categoryId, unit: prodForm.unit,
    listPrice: Number(prodForm.listPrice) || 0, costPrice: Number(prodForm.costPrice) || 0,
    durationMin: Number(prodForm.durationMin) || 0, storeTypes: [...prodForm.storeTypes],
    remark: prodForm.remark.trim() || undefined,
  }
  if (editingProd.value) mb.updateProduct(editingProd.value.id, payload)
  else mb.createProduct(payload)
  showProdModal.value = false
}
function toggleProd(id: string, s: CommonStatus) {
  if (!canEdit.value) return
  mb.setProductStatus(id, s === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE')
}

const rootCategories = computed(() => selected.value ? mb.categoriesOf(selected.value.id).filter((c) => !c.parentId) : [])
function childCats(parentId: string) {
  return selected.value ? mb.categoriesOf(selected.value.id).filter((c) => c.parentId === parentId) : []
}
const brandProducts = computed(() => selected.value ? mb.productsOf(selected.value.id) : [])
function catName(id: string) { return mb.category(id)?.name ?? '—' }
function categoryPath(catId: string): string {
  const c = mb.category(catId)
  if (!c) return '—'
  if (c.parentId) return `${catName(c.parentId)} / ${c.name}`
  return c.name
}
</script>

<template>
  <div class="mb-page">
    <!-- KPI -->
    <div class="mb-kpis">
      <div class="kpi kpi--brand"><div class="kpi__icon"><CIcon name="mall" :size="20" /></div><div class="kpi__body"><div class="kpi__label">合作品牌</div><div class="kpi__value">{{ mb.stats.brandCount }}<span class="kpi__sub">/启用 {{ mb.stats.activeBrand }}</span></div></div></div>
      <div class="kpi kpi--info"><div class="kpi__icon"><CIcon name="box" :size="20" /></div><div class="kpi__body"><div class="kpi__label">品类总数</div><div class="kpi__value">{{ mb.stats.categoryCount }}</div></div></div>
      <div class="kpi kpi--success"><div class="kpi__icon"><CIcon name="package" :size="20" /></div><div class="kpi__body"><div class="kpi__label">在售项目</div><div class="kpi__value">{{ mb.stats.activeProduct }}<span class="kpi__sub">/共 {{ mb.stats.productCount }}</span></div></div></div>
      <div class="kpi kpi--warning"><div class="kpi__icon"><CIcon name="pos" :size="20" /></div><div class="kpi__body"><div class="kpi__label">平均挂牌价</div><div class="kpi__value">{{ fmtMoney(mb.stats.avgListPrice) }}</div></div></div>
    </div>

    <div class="mb-layout">
      <!-- 左：品牌列表 -->
      <CCard class="mb-list" padding="none">
        <div class="mb-list__head">
          <CInput v-model="keyword" placeholder="搜索品牌/编码/供应商" />
          <CButton variant="primary" size="sm" :disabled="!canEdit" v-perm="'brand:edit'" @click="openBrandCreate"><CIcon name="plus" :size="14" /> 品牌</CButton>
        </div>
        <div class="mb-list__body">
          <div
            v-for="b in filteredBrands" :key="b.id"
            class="brand-item" :class="{ 'brand-item--active': selected?.id === b.id, 'brand-item--inactive': b.status === 'INACTIVE' }"
            @click="selectedId = b.id"
          >
            <div class="brand-logo" :style="{ background: b.logoColor }">{{ (b.shortName || b.name).slice(0, 1) }}</div>
            <div class="brand-item__main">
              <div class="brand-item__name">{{ b.name }} <span v-if="b.shortName" class="brand-item__en">{{ b.shortName }}</span></div>
              <div class="brand-item__meta">{{ b.code }} · {{ mb.brandStats(b.id).productCount }} 个项目</div>
            </div>
            <CStatusPill :status="statusTone(b.status)" dot />
          </div>
          <div v-if="filteredBrands.length === 0" class="mb-empty">无匹配品牌</div>
        </div>
      </CCard>

      <!-- 右：品牌详情 -->
      <CCard v-if="selected" class="mb-detail" padding="none">
        <div class="mb-detail__head">
          <div class="mb-detail__title">
            <div class="brand-logo brand-logo--lg" :style="{ background: selected.logoColor }">{{ (selected.shortName || selected.name).slice(0, 1) }}</div>
            <div>
              <div class="mb-detail__name-row">
                <h3>{{ selected.name }}</h3>
                <CStatusPill :status="statusTone(selected.status)" dot>{{ mb.STATUS_LABEL[selected.status] }}</CStatusPill>
              </div>
              <div class="mb-detail__meta">
                <span class="code">{{ selected.code }}</span>
                <span v-if="selected.origin">产地：{{ selected.origin }}</span>
                <span>供应商：{{ selected.supplier }}</span>
              </div>
            </div>
          </div>
          <div class="mb-detail__ops">
            <CButton variant="secondary" size="sm" :disabled="!canEdit" v-perm="'brand:edit'" @click="openBrandEdit(selected)">编辑品牌</CButton>
            <CButton v-if="selected.status === 'ACTIVE'" variant="secondary" size="sm" :disabled="!canEdit" v-perm="'brand:edit'" @click="mb.setBrandStatus(selected.id, 'INACTIVE')">停用</CButton>
            <CButton v-else variant="primary" size="sm" :disabled="!canEdit" v-perm="'brand:edit'" @click="mb.setBrandStatus(selected.id, 'ACTIVE')">启用</CButton>
          </div>
        </div>

        <p v-if="selected.remark" class="mb-detail__remark">{{ selected.remark }}</p>

        <!-- 概览统计 -->
        <div class="mb-stats">
          <div class="ms"><div class="ms__label">品类数</div><div class="ms__value">{{ mb.brandStats(selected.id).categoryCount }}</div></div>
          <div class="ms"><div class="ms__label">在售项目</div><div class="ms__value">{{ mb.brandStats(selected.id).activeProductCount }}</div></div>
          <div class="ms"><div class="ms__label">项目总数</div><div class="ms__value">{{ mb.brandStats(selected.id).productCount }}</div></div>
          <div class="ms"><div class="ms__label">平均挂牌价</div><div class="ms__value ms__value--brand">{{ fmtMoney(mb.brandStats(selected.id).avgListPrice) }}</div></div>
        </div>

        <!-- Tabs -->
        <div class="mb-tabs">
          <button class="mb-tab" :class="{ 'is-active': tab === 'category' }" @click="tab = 'category'">品类目录（{{ mb.brandStats(selected.id).categoryCount }}）</button>
          <button class="mb-tab" :class="{ 'is-active': tab === 'product' }" @click="tab = 'product'">项目/SKU（{{ brandProducts.length }}）</button>
        </div>

        <!-- 品类 -->
        <div v-if="tab === 'category'" class="mb-pane">
          <div class="pane-toolbar">
            <span class="pane-tip">品类用于项目归类，支持二级分类；删除品类前需先移出其下项目。</span>
            <CButton variant="primary" size="sm" :disabled="!canEdit" v-perm="'brand:edit'" @click="openCatCreate"><CIcon name="plus" :size="14" /> 新建品类</CButton>
          </div>
          <div v-if="catErr" class="inline-err">{{ catErr }}</div>
          <div class="cat-tree">
            <div v-for="c in rootCategories" :key="c.id" class="cat-node">
              <div class="cat-row" :class="{ 'cat-row--off': c.status === 'INACTIVE' }">
                <CIcon name="box" :size="16" class="cat-ic" />
                <span class="cat-name">{{ c.name }}</span>
                <span class="code code--sm">{{ c.code }}</span>
                <CStatusPill :status="statusTone(c.status)" dot />
                <div class="cat-ops">
                  <button class="lk" :disabled="!canEdit" @click="toggleCat(c.id, c.status)">{{ c.status === 'ACTIVE' ? '停用' : '启用' }}</button>
                  <button class="lk lk--danger" :disabled="!canEdit" @click="removeCat(c.id)">删除</button>
                </div>
              </div>
              <div v-for="sub in childCats(c.id)" :key="sub.id" class="cat-row cat-row--sub" :class="{ 'cat-row--off': sub.status === 'INACTIVE' }">
                <CIcon name="package" :size="14" class="cat-ic" />
                <span class="cat-name">{{ sub.name }}</span>
                <span class="code code--sm">{{ sub.code }}</span>
                <CStatusPill :status="statusTone(sub.status)" dot />
                <div class="cat-ops">
                  <button class="lk" :disabled="!canEdit" @click="toggleCat(sub.id, sub.status)">{{ sub.status === 'ACTIVE' ? '停用' : '启用' }}</button>
                  <button class="lk lk--danger" :disabled="!canEdit" @click="removeCat(sub.id)">删除</button>
                </div>
              </div>
            </div>
            <div v-if="rootCategories.length === 0" class="mb-empty">暂无品类，点击右上角新建</div>
          </div>
        </div>

        <!-- 项目 -->
        <div v-if="tab === 'product'" class="mb-pane">
          <div class="pane-toolbar">
            <span class="pane-tip">项目即门店可售卖 SKU；挂牌价面向客户，成本价仅财务可见（finance:margin:view）。</span>
            <CButton variant="primary" size="sm" :disabled="!canEdit" v-perm="'brand:edit'" @click="openProdCreate"><CIcon name="plus" :size="14" /> 新建项目</CButton>
          </div>
          <div class="table-wrap">
            <table class="dt">
              <thead><tr><th>SKU</th><th>项目名称</th><th>所属品类</th><th>单位</th><th class="num">挂牌价</th><th class="num" v-if="auth.can('finance:margin:view')">成本/毛利</th><th>时长</th><th>适用门店</th><th>状态</th><th>操作</th></tr></thead>
              <tbody>
                <tr v-for="p in brandProducts" :key="p.id" :class="{ 'row--off': p.status === 'INACTIVE' }">
                  <td class="mono">{{ p.sku }}</td>
                  <td class="cell-name">{{ p.name }}</td>
                  <td>{{ categoryPath(p.categoryId) }}</td>
                  <td>{{ p.unit }}</td>
                  <td class="num price">{{ fmtMoney(p.listPrice) }}</td>
                  <td v-if="auth.can('finance:margin:view')" class="num">
                    <div class="cost">{{ fmtMoney(p.costPrice) }}</div>
                    <div class="margin" :class="marginRate(p) >= 60 ? 'margin--hi' : 'margin--mid'">{{ marginRate(p) }}%</div>
                  </td>
                  <td>{{ p.durationMin ? p.durationMin + '分' : '—' }}</td>
                  <td><span v-for="t in p.storeTypes" :key="t" class="st-chip">{{ STORE_TYPE_LABEL[t] || t }}</span></td>
                  <td><CStatusPill :status="statusTone(p.status)" dot>{{ mb.STATUS_LABEL[p.status] }}</CStatusPill></td>
                  <td class="ops-cell">
                    <button class="lk" :disabled="!canEdit" @click="openProdEdit(p)">编辑</button>
                    <button class="lk" :disabled="!canEdit" @click="toggleProd(p.id, p.status)">{{ p.status === 'ACTIVE' ? '下架' : '上架' }}</button>
                  </td>
                </tr>
                <tr v-if="brandProducts.length === 0"><td colspan="10" class="empty-cell">暂无项目</td></tr>
              </tbody>
            </table>
          </div>
        </div>
      </CCard>
    </div>

    <!-- 品牌弹层 -->
    <div v-if="showBrandModal" class="modal-mask" @click.self="showBrandModal = false">
      <div class="modal">
        <div class="modal__head"><h3>{{ editingBrand ? '编辑品牌' : '新建品牌' }}</h3><button class="modal__close" @click="showBrandModal = false"><CIcon name="close" :size="18" /></button></div>
        <div class="modal__body">
          <div class="form-grid">
            <label class="field"><span class="field__label">品牌编码 <i>*</i></span><CInput v-model="brandForm.code" placeholder="如 BR-ALLERGAN" /></label>
            <label class="field"><span class="field__label">品牌名称 <i>*</i></span><CInput v-model="brandForm.name" placeholder="如 艾尔建" /></label>
            <label class="field"><span class="field__label">简称(英文)</span><CInput v-model="brandForm.shortName" placeholder="如 Allergan" /></label>
            <label class="field"><span class="field__label">产地</span><CInput v-model="brandForm.origin" placeholder="如 美国/爱尔兰" /></label>
            <label class="field field--full"><span class="field__label">供应商</span><CInput v-model="brandForm.supplier" placeholder="供应商全称" /></label>
            <label class="field field--full"><span class="field__label">品牌说明</span><CTextarea v-model="brandForm.remark" :rows="2" placeholder="品牌背景/授权范围等" /></label>
          </div>
          <div v-if="brandErr" class="form-err">{{ brandErr }}</div>
        </div>
        <div class="modal__foot"><CButton variant="secondary" @click="showBrandModal = false">取消</CButton><CButton variant="primary" @click="submitBrand">{{ editingBrand ? '保存' : '创建' }}</CButton></div>
      </div>
    </div>

    <!-- 品类弹层 -->
    <div v-if="showCatModal" class="modal-mask" @click.self="showCatModal = false">
      <div class="modal modal--sm">
        <div class="modal__head"><h3>新建品类</h3><button class="modal__close" @click="showCatModal = false"><CIcon name="close" :size="18" /></button></div>
        <div class="modal__body">
          <div class="form-grid form-grid--1">
            <label class="field"><span class="field__label">品类名称 <i>*</i></span><CInput v-model="catForm.name" placeholder="如 注射美容" /></label>
            <label class="field"><span class="field__label">品类编码</span><CInput v-model="catForm.code" placeholder="留空自动生成" /></label>
            <label class="field field--full">
              <span class="field__label">上级品类</span>
              <select v-model="catForm.parentId" class="sel"><option value="">— 顶级品类 —</option><option v-for="c in rootCategories" :key="c.id" :value="c.id">{{ c.name }}</option></select>
            </label>
          </div>
          <div v-if="catErr" class="form-err">{{ catErr }}</div>
        </div>
        <div class="modal__foot"><CButton variant="secondary" @click="showCatModal = false">取消</CButton><CButton variant="primary" @click="submitCat">创建</CButton></div>
      </div>
    </div>

    <!-- 项目弹层 -->
    <div v-if="showProdModal" class="modal-mask" @click.self="showProdModal = false">
      <div class="modal modal--lg">
        <div class="modal__head"><h3>{{ editingProd ? '编辑项目' : '新建项目' }}</h3><button class="modal__close" @click="showProdModal = false"><CIcon name="close" :size="18" /></button></div>
        <div class="modal__body">
          <div class="form-grid">
            <label class="field"><span class="field__label">SKU 编码 <i>*</i></span><CInput v-model="prodForm.sku" placeholder="如 AGN-BTX-100" /></label>
            <label class="field"><span class="field__label">项目名称 <i>*</i></span><CInput v-model="prodForm.name" placeholder="如 保妥适100U瘦脸针" /></label>
            <label class="field">
              <span class="field__label">所属品类 <i>*</i></span>
              <select v-model="prodForm.categoryId" class="sel">
                <option value="">请选择</option>
                <optgroup v-for="c in rootCategories" :key="c.id" :label="c.name">
                  <option :value="c.id">{{ c.name }}</option>
                  <option v-for="sub in childCats(c.id)" :key="sub.id" :value="sub.id">　└ {{ sub.name }}</option>
                </optgroup>
              </select>
            </label>
            <label class="field"><span class="field__label">单位</span>
              <select v-model="prodForm.unit" class="sel"><option>次</option><option>支</option><option>盒</option><option>部位</option><option>疗程</option></select>
            </label>
            <label class="field"><span class="field__label">挂牌价（元）</span><CInput v-model="prodForm.listPrice" placeholder="3800" /></label>
            <label class="field"><span class="field__label">成本价（元）</span><CInput v-model="prodForm.costPrice" placeholder="1650" /></label>
            <label class="field"><span class="field__label">预计时长(分钟)</span><CInput v-model="prodForm.durationMin" placeholder="30" /></label>
            <label class="field field--full">
              <span class="field__label">适用门店类型 <i>*</i></span>
              <div class="st-pick">
                <label v-for="t in ['FLAGSHIP','COMMUNITY','CLINIC']" :key="t" class="st-opt" :class="{ 'is-on': prodForm.storeTypes.includes(t) }">
                  <input type="checkbox" :checked="prodForm.storeTypes.includes(t)" @change="toggleStoreType(t)" /> {{ STORE_TYPE_LABEL[t] }}店
                </label>
              </div>
            </label>
            <label class="field field--full"><span class="field__label">备注</span><CTextarea v-model="prodForm.remark" :rows="2" /></label>
          </div>
          <div v-if="prodErr" class="form-err">{{ prodErr }}</div>
        </div>
        <div class="modal__foot"><CButton variant="secondary" @click="showProdModal = false">取消</CButton><CButton variant="primary" @click="submitProd">{{ editingProd ? '保存' : '创建' }}</CButton></div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.mb-page { display: flex; flex-direction: column; gap: var(--s-md); }
.mb-kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); }
.kpi { display: flex; align-items: center; gap: var(--s-md); padding: var(--s-md); border-radius: var(--r-xl); background: var(--c-surface); border: 1px solid var(--c-border-light); }
.kpi__icon { width: 44px; height: 44px; border-radius: var(--r-lg); display: flex; align-items: center; justify-content: center; flex: none; }
.kpi--brand .kpi__icon { background: var(--c-brand-soft); color: var(--c-brand); }
.kpi--info .kpi__icon { background: var(--c-info-bg, #EAF2FF); color: var(--c-info-fg); }
.kpi--success .kpi__icon { background: var(--c-success-bg, #f0fbf0); color: var(--c-success-fg); }
.kpi--warning .kpi__icon { background: var(--c-warning-bg, #FFF5E6); color: var(--c-warning-fg); }
.kpi__label { font-size: var(--t-xs); color: var(--c-text-3); }
.kpi__value { font-size: var(--t-xl); font-weight: 700; color: var(--c-text); line-height: 1.2; display: flex; align-items: baseline; gap: 6px; }
.kpi__sub { font-size: var(--t-xs); font-weight: 400; color: var(--c-text-3); }

.mb-layout { display: grid; grid-template-columns: 300px 1fr; gap: var(--s-md); align-items: start; }
.mb-list { max-height: calc(100vh - 220px); display: flex; flex-direction: column; }
.mb-list__head { display: flex; gap: var(--s-sm); padding: var(--s-md); border-bottom: 1px solid var(--c-border-light); }
.mb-list__head > :deep(.cinput) { flex: 1; }
.mb-list__body { overflow-y: auto; padding: var(--s-xs); }
.brand-item { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-sm) var(--s-md); border-radius: var(--r-md); cursor: pointer; transition: background .12s; }
.brand-item:hover { background: var(--c-surface, #f7f8fa); }
.brand-item--active { background: var(--c-brand-soft); }
.brand-item--inactive { opacity: .6; }
.brand-logo { width: 36px; height: 36px; border-radius: var(--r-md); color: #fff; font-weight: 700; display: flex; align-items: center; justify-content: center; flex: none; font-size: var(--t-md); }
.brand-logo--lg { width: 48px; height: 48px; border-radius: var(--r-lg); font-size: var(--t-xl); }
.brand-item__main { flex: 1; min-width: 0; }
.brand-item__name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); display: flex; align-items: center; gap: 4px; }
.brand-item__en { font-size: var(--t-xs); font-weight: 400; color: var(--c-text-3); }
.brand-item__meta { font-size: var(--t-xs); color: var(--c-text-3); }
.mb-empty { padding: var(--s-lg); text-align: center; color: var(--c-text-3); font-size: var(--t-sm); }

.mb-detail { display: flex; flex-direction: column; }
.mb-detail__head { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--s-md); padding: var(--s-lg); border-bottom: 1px solid var(--c-border-light); }
.mb-detail__title { display: flex; gap: var(--s-md); align-items: center; }
.mb-detail__name-row { display: flex; align-items: center; gap: var(--s-sm); }
.mb-detail__name-row h3 { margin: 0; font-size: var(--t-lg); font-weight: 700; }
.mb-detail__meta { display: flex; flex-wrap: wrap; gap: var(--s-md); margin-top: 6px; font-size: var(--t-xs); color: var(--c-text-3); }
.code { font-family: var(--t-number, monospace); background: var(--c-surface, #f7f8fa); padding: 1px 8px; border-radius: var(--r-sm); color: var(--c-text-2); }
.code--sm { font-size: 11px; padding: 0 6px; }
.mb-detail__ops { display: flex; gap: var(--s-sm); }
.mb-detail__remark { margin: 0; padding: var(--s-sm) var(--s-lg); font-size: var(--t-xs); color: var(--c-text-2); background: var(--c-surface, #f7f8fa); border-bottom: 1px solid var(--c-border-light); }

.mb-stats { display: grid; grid-template-columns: repeat(4, 1fr); gap: 1px; background: var(--c-border-light); border-bottom: 1px solid var(--c-border-light); }
.ms { background: var(--c-surface); padding: var(--s-md) var(--s-lg); }
.ms__label { font-size: var(--t-xs); color: var(--c-text-3); }
.ms__value { font-size: var(--t-lg); font-weight: 700; color: var(--c-text); margin-top: 4px; }
.ms__value--brand { color: var(--c-brand); }

.mb-tabs { display: flex; gap: var(--s-xs); padding: var(--s-sm) var(--s-lg) 0; }
.mb-tab { border: none; background: none; padding: var(--s-sm) var(--s-md); font-size: var(--t-sm); font-weight: 600; color: var(--c-text-3); cursor: pointer; border-bottom: 2px solid transparent; border-radius: var(--r-sm) var(--r-sm) 0 0; }
.mb-tab:hover { color: var(--c-text); }
.mb-tab.is-active { color: var(--c-brand); border-bottom-color: var(--c-brand); }

.mb-pane { padding: var(--s-md) var(--s-lg) var(--s-lg); }
.pane-toolbar { display: flex; align-items: center; justify-content: space-between; gap: var(--s-md); margin-bottom: var(--s-md); }
.pane-tip { font-size: var(--t-xs); color: var(--c-text-3); }
.inline-err { color: var(--c-danger-fg); font-size: var(--t-xs); margin-bottom: var(--s-sm); }

.cat-tree { display: flex; flex-direction: column; gap: 2px; border: 1px solid var(--c-border-light); border-radius: var(--r-lg); padding: var(--s-xs); }
.cat-node { display: flex; flex-direction: column; }
.cat-row { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-sm) var(--s-md); border-radius: var(--r-md); }
.cat-row:hover { background: var(--c-surface, #f7f8fa); }
.cat-row--sub { padding-left: var(--s-xl); }
.cat-row--off { opacity: .55; }
.cat-ic { color: var(--c-text-3); flex: none; }
.cat-name { font-size: var(--t-sm); font-weight: 500; color: var(--c-text); }
.cat-ops { margin-left: auto; display: flex; gap: var(--s-sm); }
.lk { border: none; background: none; cursor: pointer; font-size: var(--t-xs); color: var(--c-brand); padding: 2px 4px; }
.lk:disabled { color: var(--c-text-3); cursor: not-allowed; }
.lk--danger { color: var(--c-danger-fg); }

.table-wrap { border: 1px solid var(--c-border-light); border-radius: var(--r-lg); overflow: auto; }
.dt { width: 100%; border-collapse: collapse; font-size: var(--t-sm); }
.dt thead th { background: var(--c-surface, #f7f8fa); color: var(--c-text-3); font-weight: 600; text-align: left; padding: 10px var(--s-md); font-size: var(--t-xs); white-space: nowrap; border-bottom: 1px solid var(--c-border-light); }
.dt tbody td { padding: 10px var(--s-md); border-bottom: 1px solid var(--c-border-light); vertical-align: middle; }
.dt tbody tr:last-child td { border-bottom: none; }
.dt tbody tr:hover { background: var(--c-surface, #f7f8fa); }
.row--off { opacity: .55; }
.num { text-align: right; font-family: var(--t-number, monospace); white-space: nowrap; }
.mono { font-family: var(--t-number, monospace); font-size: var(--t-xs); color: var(--c-text-2); }
.cell-name { font-weight: 600; color: var(--c-text); }
.price { color: var(--c-brand); font-weight: 700; }
.cost { font-size: var(--t-xs); color: var(--c-text-3); }
.margin { font-size: var(--t-xs); font-weight: 600; }
.margin--hi { color: var(--c-success-fg); }
.margin--mid { color: var(--c-warning-fg); }
.st-chip { display: inline-block; font-size: 11px; padding: 1px 8px; background: var(--c-brand-soft); color: var(--c-brand); border-radius: var(--r-capsule); margin-right: 4px; }
.ops-cell { white-space: nowrap; }
.empty-cell { text-align: center; color: var(--c-text-3); padding: var(--s-xl); }

.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,.45); display: flex; align-items: center; justify-content: center; z-index: 100; }
.modal { background: var(--c-surface); border-radius: var(--r-xl); width: 560px; max-width: calc(100vw - 48px); max-height: 86vh; display: flex; flex-direction: column; box-shadow: var(--shadow-pop, 0 12px 40px rgba(0,0,0,.18)); }
.modal--sm { width: 420px; }
.modal--lg { width: 640px; }
.modal__head { display: flex; align-items: center; justify-content: space-between; padding: var(--s-md) var(--s-lg); border-bottom: 1px solid var(--c-border-light); }
.modal__head h3 { margin: 0; font-size: var(--t-lg); font-weight: 700; }
.modal__close { border: none; background: none; cursor: pointer; color: var(--c-text-3); padding: 4px; display: flex; border-radius: var(--r-sm); }
.modal__close:hover { background: var(--c-surface, #f7f8fa); color: var(--c-text); }
.modal__body { padding: var(--s-lg); overflow-y: auto; }
.modal__foot { display: flex; justify-content: flex-end; gap: var(--s-sm); padding: var(--s-md) var(--s-lg); border-top: 1px solid var(--c-border-light); }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.form-grid--1 { grid-template-columns: 1fr; }
.field { display: flex; flex-direction: column; gap: 6px; }
.field--full { grid-column: 1 / -1; }
.field__label { font-size: var(--t-xs); color: var(--c-text-2); font-weight: 500; }
.field__label i { color: var(--c-danger-fg); font-style: normal; }
.sel { height: 36px; padding: 0 12px; border: 1px solid var(--c-border); border-radius: var(--r-md); font-size: var(--t-sm); color: var(--c-text); background: var(--c-surface); width: 100%; }
.form-err { margin-top: var(--s-sm); color: var(--c-danger-fg); font-size: var(--t-xs); }
.st-pick { display: flex; gap: var(--s-sm); }
.st-opt { display: flex; align-items: center; gap: 6px; font-size: var(--t-sm); padding: 6px 14px; border: 1px solid var(--c-border); border-radius: var(--r-capsule); cursor: pointer; color: var(--c-text-2); }
.st-opt input { accent-color: var(--c-brand); }
.st-opt.is-on { border-color: var(--c-brand); background: var(--c-brand-soft); color: var(--c-brand); font-weight: 600; }

@media (max-width: 1024px) {
  .mb-kpis { grid-template-columns: repeat(2, 1fr); }
  .mb-layout { grid-template-columns: 1fr; }
  .mb-list { max-height: none; }
  .mb-stats { grid-template-columns: repeat(2, 1fr); }
}
</style>
