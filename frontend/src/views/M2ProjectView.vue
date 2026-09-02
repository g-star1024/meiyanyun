<script setup lang="ts">
/* M2-15b 医美项目库 /m2-projects
 * 门店运营视角维护可售卖 SKU，数据与 M1-02 品牌品类互通。
 */
import { computed, ref, watch } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CKpi from '@/components/CKpi.vue'
import CIcon from '@/components/CIcon.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CTable from '@/components/CTable.vue'
import CTextarea from '@/components/CTextarea.vue'
import { useToast } from '@/composables/useToast'
import { useAuthStore } from '@/stores/auth'
import { useM1BrandStore, type Product, type CommonStatus } from '@/stores/m1Brand'

const auth = useAuthStore()
const mb = useM1BrandStore()
const toast = useToast()

onMounted(() => mb.seed())

const keyword = ref('')
const filterBrandId = ref('')
const filterStatus = ref('')

const statusOptions = [
  { value: '', label: '全部状态' },
  { value: 'ACTIVE', label: '启用' },
  { value: 'INACTIVE', label: '停用' },
]
const brandOptions = computed(() => [
  { value: '', label: '全部品牌' },
  ...mb.activeBrands.map((b) => ({ value: b.id, label: b.name })),
])

const filtered = computed(() => {
  let list = mb.products.slice()
  if (filterBrandId.value) list = list.filter((p) => p.brandId === filterBrandId.value)
  if (filterStatus.value) list = list.filter((p) => p.status === filterStatus.value)
  const q = keyword.value.trim()
  if (q) list = list.filter((p) => p.name.includes(q) || p.sku.includes(q) || categoryPath(p.categoryId).includes(q))
  return list.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
})

function categoryPath(categoryId: string) {
  const c = mb.category(categoryId)
  if (!c) return '-'
  if (c.parentId) {
    const parent = mb.category(c.parentId)
    return parent ? `${parent.name} / ${c.name}` : c.name
  }
  return c.name
}
function brandName(brandId: string) { return mb.brand(brandId)?.name || '-' }
function formatPrice(n: number) { return `¥${n.toLocaleString()}` }

const kpis = computed(() => {
  const active = mb.products.filter((p) => p.status === 'ACTIVE').length
  const total = mb.products.length
  const brands = new Set(mb.products.map((p) => p.brandId)).size
  const avg = active ? Math.round(mb.products.filter((p) => p.status === 'ACTIVE').reduce((s, p) => s + p.listPrice, 0) / active) : 0
  return [
    { label: '在售项目', value: String(active), tone: 'success' as const, icon: 'check' },
    { label: '项目总数', value: String(total), tone: 'brand' as const, icon: 'package' },
    { label: '覆盖品牌', value: String(brands), tone: 'blue' as const, icon: 'org' },
    { label: '平均挂牌价', value: formatPrice(avg), tone: 'warning' as const, icon: 'order' },
  ]
})

const cols = [
  { key: 'sku', label: 'SKU', width: '130' },
  { key: 'name', label: '项目名称' },
  { key: 'category', label: '所属品类', width: '120' },
  { key: 'brand', label: '品牌', width: '100' },
  { key: 'unit', label: '单位', width: '70' },
  { key: 'listPrice', label: '挂牌价', width: '90', align: 'right' as const },
  { key: 'costPrice', label: '成本价', width: '90', align: 'right' as const },
  { key: 'durationMin', label: '时长', width: '70' },
  { key: 'status', label: '状态', width: '80' },
  { key: 'ops', label: '操作', width: '120' },
]

const STORE_TYPES = [
  { value: 'FLAGSHIP', label: '旗舰店' },
  { value: 'COMMUNITY', label: '社区店' },
  { value: 'CLINIC', label: '诊所' },
]
const UNIT_OPTIONS = ['次', '支', '盒', '部位', '疗程', '小时'].map((u) => ({ value: u, label: u }))
const canEdit = computed(() => auth.can('brand:edit'))

/* ---------- 新建 / 编辑弹窗 ---------- */
const showForm = ref(false)
const editingId = ref<string | null>(null)
function emptyForm() {
  return {
    brandId: '',
    categoryId: '',
    sku: '',
    name: '',
    unit: '次',
    listPrice: '',
    costPrice: '',
    durationMin: '',
    storeTypes: [] as string[],
    status: 'ACTIVE' as CommonStatus,
    remark: '',
  }
}
const form = ref(emptyForm())

const categoryOptions = computed(() => {
  if (!form.value.brandId) return []
  const cats = mb.categories.filter((c) => c.brandId === form.value.brandId && c.status === 'ACTIVE')
  const roots = cats.filter((c) => !c.parentId)
  const opts: { value: string; label: string }[] = []
  for (const r of roots) {
    const children = cats.filter((c) => c.parentId === r.id)
    if (children.length) {
      children.forEach((c) => opts.push({ value: c.id, label: `${r.name} / ${c.name}` }))
    } else {
      opts.push({ value: r.id, label: r.name })
    }
  }
  return opts
})

watch(() => form.value.brandId, () => { form.value.categoryId = '' })

const canSave = computed(() =>
  form.value.brandId &&
  form.value.categoryId &&
  form.value.name.trim() &&
  form.value.sku.trim() &&
  Number(form.value.listPrice) >= 0 &&
  form.value.storeTypes.length > 0,
)

function openCreate() {
  editingId.value = null
  form.value = emptyForm()
  showForm.value = true
}
function openEditSlot(row: Record<string, unknown>) { openEdit(row as unknown as Product) }
function toggleStatusSlot(row: Record<string, unknown>) { toggleStatus(row as unknown as Product) }
function openEdit(row: Product) {
  editingId.value = row.id
  form.value = {
    brandId: row.brandId,
    categoryId: row.categoryId,
    sku: row.sku,
    name: row.name,
    unit: row.unit,
    listPrice: String(row.listPrice),
    costPrice: String(row.costPrice),
    durationMin: String(row.durationMin),
    storeTypes: [...row.storeTypes],
    status: row.status,
    remark: row.remark || '',
  }
  showForm.value = true
}
function toggleType(v: string) {
  const set = new Set(form.value.storeTypes)
  if (set.has(v)) set.delete(v)
  else set.add(v)
  form.value.storeTypes = [...set]
}
function saveForm() {
  if (!canSave.value) return
  const payload = {
    brandId: form.value.brandId,
    categoryId: form.value.categoryId,
    sku: form.value.sku.trim(),
    name: form.value.name.trim(),
    unit: form.value.unit,
    listPrice: Number(form.value.listPrice),
    costPrice: Number(form.value.costPrice),
    durationMin: Number(form.value.durationMin) || 0,
    storeTypes: form.value.storeTypes,
    status: form.value.status,
    remark: form.value.remark.trim(),
  }
  if (editingId.value === null) {
    mb.createProduct(payload)
    toast.success('项目已创建，同步至品牌品类库')
  } else {
    mb.updateProduct(editingId.value, payload)
    toast.success('项目已更新，/m1-brand 同步生效')
  }
  showForm.value = false
}
function toggleStatus(row: Product) {
  const next: CommonStatus = row.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
  mb.setProductStatus(row.id, next)
  toast.success(`项目已${next === 'ACTIVE' ? '启用' : '停用'}`)
}

import { onMounted } from 'vue'
</script>

<template>
  <div class="m2-proj">
    <div class="kpis">
      <CKpi v-for="k in kpis" :key="k.label" :value="k.value" :label="k.label" :tone="k.tone" :icon="k.icon" />
    </div>

    <div class="toolbar">
      <div class="filters">
        <CSelect v-model="filterBrandId" :options="brandOptions" width="160px" />
        <CSelect v-model="filterStatus" :options="statusOptions" width="120px" />
        <div class="search">
          <CIcon name="search" :size="16" />
          <input v-model="keyword" placeholder="搜索 SKU / 项目名称 / 品类" />
        </div>
      </div>
      <CButton v-if="canEdit" variant="primary" @click="openCreate">
        <CIcon name="plus" :size="14" />新建项目
      </CButton>
    </div>

    <CCard padding="lg">
      <CTable :columns="cols" :rows="filtered" row-key="id" stripe>
        <template #col-category="{ row }">{{ categoryPath(row.categoryId) }}</template>
        <template #col-brand="{ row }">{{ brandName(row.brandId) }}</template>
        <template #col-listPrice="{ row }">{{ formatPrice(row.listPrice) }}</template>
        <template #col-costPrice="{ row }">{{ formatPrice(row.costPrice) }}</template>
        <template #col-durationMin="{ row }">{{ row.durationMin ? `${row.durationMin}min` : '-' }}</template>
        <template #col-status="{ row }">
          <CStatusPill :status="row.status === 'ACTIVE' ? 'success' : 'danger'" dot>{{ row.status === 'ACTIVE' ? '启用' : '停用' }}</CStatusPill>
        </template>
        <template #col-ops="{ row }">
          <CButton size="sm" variant="text" @click="openEditSlot(row)">编辑</CButton>
          <CButton size="sm" variant="text" @click="toggleStatusSlot(row)">{{ row.status === 'ACTIVE' ? '停用' : '启用' }}</CButton>
        </template>
      </CTable>
      <p v-if="!filtered.length" class="empty">暂无匹配项目，可前往右上角「新建项目」录入 SKU。</p>
    </CCard>

    <!-- 新建 / 编辑弹窗 -->
    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <CCard class="modal" :title="editingId === null ? '新建医美项目' : '编辑医美项目'" padding="lg">
        <div class="form">
          <div class="form__row form__row--2">
            <div class="form__col">
              <label class="form__label">所属品牌 <span class="req">*</span></label>
              <CSelect v-model="form.brandId" :options="brandOptions.filter(o => o.value)" width="100%" />
            </div>
            <div class="form__col">
              <label class="form__label">所属品类 <span class="req">*</span></label>
              <CSelect v-model="form.categoryId" :options="categoryOptions" width="100%" placeholder="请先选择品牌" />
            </div>
          </div>
          <div class="form__row form__row--2">
            <div class="form__col">
              <label class="form__label">SKU 编码 <span class="req">*</span></label>
              <CInput v-model="form.sku" placeholder="如：AGN-BTX-100" />
            </div>
            <div class="form__col">
              <label class="form__label">项目名称 <span class="req">*</span></label>
              <CInput v-model="form.name" placeholder="如：保妥适 100U 瘦脸针" />
            </div>
          </div>
          <div class="form__row form__row--3">
            <div class="form__col">
              <label class="form__label">单位</label>
              <CSelect v-model="form.unit" :options="UNIT_OPTIONS" width="100%" />
            </div>
            <div class="form__col">
              <label class="form__label">挂牌价（元）</label>
              <CInput v-model="form.listPrice" type="number" placeholder="0" />
            </div>
            <div class="form__col">
              <label class="form__label">成本价（元）</label>
              <CInput v-model="form.costPrice" type="number" placeholder="0" />
            </div>
          </div>
          <div class="form__row form__row--3">
            <div class="form__col">
              <label class="form__label">预计时长（分钟）</label>
              <CInput v-model="form.durationMin" type="number" placeholder="0" />
            </div>
            <div class="form__col">
              <label class="form__label">状态</label>
              <CSelect v-model="form.status" :options="statusOptions.filter(o => o.value)" width="100%" />
            </div>
          </div>
          <div class="form__row">
            <label class="form__label">适用门店类型 <span class="req">*</span></label>
            <div class="store-types">
              <label v-for="t in STORE_TYPES" :key="t.value" class="type-opt" :class="{ 'type-opt--checked': form.storeTypes.includes(t.value) }">
                <input type="checkbox" :checked="form.storeTypes.includes(t.value)" @change="toggleType(t.value)">
                <span>{{ t.label }}</span>
              </label>
            </div>
          </div>
          <div class="form__row">
            <label class="form__label">备注</label>
            <CTextarea v-model="form.remark" placeholder="如：需配合皮肤检测、特殊禁忌说明" />
          </div>
        </div>
        <template #footer>
          <div class="modal__foot">
            <CButton variant="ghost" @click="showForm = false">取消</CButton>
            <CButton variant="primary" :disabled="!canSave" @click="saveForm">
              {{ editingId === null ? '创建项目' : '保存修改' }}
            </CButton>
          </div>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.m2-proj { display: flex; flex-direction: column; gap: var(--s-lg); }
.kpis { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
.toolbar { display: flex; align-items: center; justify-content: space-between; gap: var(--s-md); flex-wrap: wrap; }
.filters { display: flex; align-items: center; gap: var(--s-sm); flex: 1; }
.search { flex: 1; max-width: 320px; display: flex; align-items: center; gap: var(--s-sm); border: 1px solid var(--c-border); border-radius: var(--r-md); padding: 0 var(--s-sm); color: var(--c-text-3); }
.search input { flex: 1; border: none; outline: none; font-size: var(--t-sm); padding: var(--s-sm) 0; background: transparent; color: var(--c-text); }
.empty { margin: var(--s-lg) 0 0; text-align: center; font-size: var(--t-sm); color: var(--c-text-3); }

.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,.45); display: flex; align-items: center; justify-content: center; z-index: 100; padding: var(--s-md); }
.modal { width: 640px; max-width: 100%; max-height: 90vh; overflow-y: auto; box-shadow: var(--shadow-pop); }
.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.form__row--2 { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.form__row--3 { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: var(--s-md); }
.form__label { font-size: var(--t-xs); color: var(--c-text-3); }
.req { color: var(--c-danger-fg); }
.store-types { display: flex; gap: var(--s-sm); flex-wrap: wrap; }
.type-opt { display: flex; align-items: center; gap: 6px; padding: var(--s-sm) var(--s-md); border: 1px solid var(--c-border); border-radius: var(--r-md); font-size: var(--t-sm); color: var(--c-text-2); cursor: pointer; }
.type-opt--checked { background: var(--c-brand-soft); border-color: var(--c-brand); color: var(--c-brand); }
.type-opt input { accent-color: var(--c-brand); }
.modal__foot { display: flex; justify-content: flex-end; gap: var(--s-sm); }

@media (max-width: 1024px) {
  .kpis { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); }
  .toolbar { flex-direction: column; align-items: stretch; }
  .filters { flex-wrap: wrap; }
  .search { max-width: none; }
  .form__row--2, .form__row--3 { grid-template-columns: 1fr; }
}
</style>
