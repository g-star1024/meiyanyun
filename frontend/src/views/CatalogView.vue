<script setup lang="ts">
/* ============================================================
 * 卡项 / 疗程定义 /m2-catalog（M2-15）
 * 商品定义侧：卡项 / 疗程模板（次数、有效期、价格、转赠、上下架）。
 * 4 KPI（商品总数/上架中/疗程类/卡项类）+ 列表 + 详情 + 新建/编辑弹层
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import { useCatalogStore, type CatalogProduct, type CatalogType, type CatalogStatus } from '@/stores/catalog'

const store = useCatalogStore()
onMounted(() => store.seed())

const selectedId = ref<string | null>(null)
const selected = computed<CatalogProduct | null>(() => {
  if (selectedId.value) return store.get(selectedId.value) ?? null
  return store.filtered[0] ?? null
})

const kpis = computed(() => [
  { label: '商品总数', icon: 'package', value: String(store.items.length), tone: 'text' as const },
  { label: '上架中', icon: 'package', value: String(store.onShelf.length), tone: 'success' as const },
  { label: '疗程类', icon: 'card', value: String(store.courses.length), tone: 'brand' as const },
  { label: '卡项类', icon: 'card', value: String(store.cards.length), tone: 'orange' as const },
])

const typeOptions = [
  { value: 'ALL', label: '全部类型' },
  { value: 'CARD', label: '卡项' },
  { value: 'COURSE', label: '疗程' },
]
const statusOptions = [
  { value: 'ALL', label: '全部状态' },
  { value: 'ON_SHELF', label: '上架中' },
  { value: 'OFF_SHELF', label: '已下架' },
]

function money(n: number) { return `¥${n.toLocaleString('zh-CN')}` }
function validityText(days: number) {
  if (days >= 365) return `${Math.round(days / 365)} 年`
  if (days >= 30) return `${Math.round(days / 30)} 个月`
  return `${days} 天`
}
function typeIcon(t: CatalogType) { return t === 'CARD' ? 'card' : 'box' }
function statusPill(s: CatalogStatus) { return store.STATUS_PILL[s] }

// 新建 / 编辑
const showForm = ref(false)
const editing = ref<CatalogProduct | null>(null)
const form = ref({
  name: '', type: 'CARD' as CatalogType, category: '',
  sessions: 1, validityDays: 365,
  price: 0, originalPrice: 0, transferable: true,
  status: 'ON_SHELF' as CatalogStatus,
  includes: '', description: '',
})
const canSubmit = computed(() => form.value.name.trim() && form.value.price > 0)

function openCreate() {
  editing.value = null
  form.value = {
    name: '', type: 'CARD', category: '', sessions: 1, validityDays: 365,
    price: 0, originalPrice: 0, transferable: true,
    status: 'ON_SHELF', includes: '', description: '',
  }
  showForm.value = true
}
function openEdit() {
  if (!selected.value) return
  editing.value = selected.value
  form.value = {
    name: selected.value.name,
    type: selected.value.type,
    category: selected.value.category,
    sessions: selected.value.sessions,
    validityDays: selected.value.validityDays,
    price: selected.value.price,
    originalPrice: selected.value.originalPrice,
    transferable: selected.value.transferable,
    status: selected.value.status,
    includes: selected.value.includes.join('\n'),
    description: selected.value.description,
  }
  showForm.value = true
}
function submitForm() {
  if (!canSubmit.value) return
  const payload = {
    name: form.value.name.trim(),
    type: form.value.type,
    category: form.value.category.trim() || (form.value.type === 'CARD' ? '储值卡' : '美肤疗程'),
    sessions: Number(form.value.sessions) || 1,
    validityDays: Number(form.value.validityDays) || 365,
    price: Number(form.value.price) || 0,
    originalPrice: Number(form.value.originalPrice) || 0,
    transferable: form.value.transferable,
    status: form.value.status,
    includes: form.value.includes.split('\n').map((s) => s.trim()).filter(Boolean),
    description: form.value.description.trim(),
  }
  if (editing.value) {
    store.update(editing.value.id, payload)
  } else {
    const p = store.create(payload)
    if (p) selectedId.value = p.id
  }
  showForm.value = false
}

// 上下架确认
const confirmBox = ref<{ show: boolean; title: string; text: string; action: () => void } | null>(null)
function ask(title: string, text: string, action: () => void) {
  confirmBox.value = { show: true, title, text, action }
}
function runConfirm() {
  confirmBox.value?.action()
  confirmBox.value = null
}
function doToggle() {
  if (!selected.value) return
  const will = selected.value.status === 'ON_SHELF' ? '下架' : '上架'
  ask(`确认${will}商品`, `${will}后该商品${will === '下架' ? '将不在前台销售，已售出客户资产不受影响' : '将恢复前台销售'}。`, () => {
    if (selected.value) store.toggleStatus(selected.value.id)
  })
}
</script>

<template>
  <div class="ct">
    <div class="ct__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <CCard class="ct__toolbar" padding="none">
      <div class="toolbar">
        <CInput v-model="store.keyword" placeholder="搜索商品名称 / 编码" />
        <CButton variant="primary" class="toolbar__btn" v-perm.disable="'catalog:edit'" @click="openCreate">
          <CIcon name="plus" :size="16" />新建商品
        </CButton>
      </div>
    </CCard>

    <div class="ct__body">
      <!-- 左：商品列表 -->
      <CCard class="ct__list" padding="none">
        <div class="filters">
          <CSelect v-model="store.filterType" :options="typeOptions" />
          <CSelect v-model="store.filterStatus" :options="statusOptions" />
        </div>
        <div class="list">
          <div v-if="store.filtered.length === 0" class="empty">
            <CIcon name="box" :size="28" class="empty__icon" />
            <div>暂无商品</div>
          </div>
          <button
            v-for="it in store.filtered" :key="it.id"
            class="row" :class="{ 'row--active': selected?.id === it.id }"
            @click="selectedId = it.id"
          >
            <div class="row__top">
              <span class="row__type">
                <CIcon :name="(typeIcon(it.type) as any)" :size="12" />
                {{ store.TYPE_LABEL[it.type] }}
              </span>
              <CStatusPill :status="statusPill(it.status)" dot>{{ store.STATUS_LABEL[it.status] }}</CStatusPill>
            </div>
            <div class="row__name">{{ it.name }}</div>
            <div class="row__meta">
              <span class="row__code">{{ it.code }}</span>
              <span class="row__price">{{ money(it.price) }}</span>
            </div>
            <div class="row__sub">{{ it.sessions }} 次 · {{ validityText(it.validityDays) }} · {{ it.transferable ? '可转赠' : '不可转赠' }}</div>
          </button>
        </div>
      </CCard>

      <!-- 右：详情 -->
      <CCard v-if="selected" class="ct__detail" padding="none">
        <template #header>
          <div class="ct__detail-head">
            <div>
              <h3 class="ct__name">{{ selected.name }}</h3>
              <div class="ct__sub">
                <span class="ct__code">{{ selected.code }}</span>
                <span class="ct__tag">
                  <CIcon :name="(typeIcon(selected.type) as any)" :size="12" />
                  {{ store.TYPE_LABEL[selected.type] }}
                </span>
                <span>{{ selected.category }}</span>
              </div>
            </div>
            <CStatusPill :status="statusPill(selected.status)" dot>{{ store.STATUS_LABEL[selected.status] }}</CStatusPill>
          </div>
        </template>

        <div class="detail-body">
          <!-- 价格区 -->
          <div class="price-row">
            <div class="price-now">{{ money(selected.price) }}</div>
            <div class="price-old">{{ money(selected.originalPrice) }}</div>
            <div class="price-save">立省 {{ money(selected.originalPrice - selected.price) }}</div>
          </div>

          <!-- 核心规格 -->
          <div class="spec-grid">
            <div class="spec">
              <div class="spec__label">可用次数</div>
              <div class="spec__value">{{ selected.sessions }} 次</div>
            </div>
            <div class="spec">
              <div class="spec__label">有效期</div>
              <div class="spec__value">{{ validityText(selected.validityDays) }}</div>
            </div>
            <div class="spec">
              <div class="spec__label">转赠</div>
              <div class="spec__value" :class="selected.transferable ? 'is-yes' : 'is-no'">
                {{ selected.transferable ? '允许' : '不允许' }}
              </div>
            </div>
            <div class="spec">
              <div class="spec__label">状态</div>
              <div class="spec__value">{{ store.STATUS_LABEL[selected.status] }}</div>
            </div>
          </div>

          <!-- 包含项目 -->
          <div class="block">
            <div class="block__title">包含项目</div>
            <ul class="includes">
              <li v-for="(inc, i) in selected.includes" :key="i" class="includes__item">
                <CIcon name="check" :size="14" class="includes__icon" />
                <span>{{ inc }}</span>
              </li>
            </ul>
          </div>

          <!-- 说明 -->
          <div class="block">
            <div class="block__title">商品说明</div>
            <p class="desc">{{ selected.description || '—' }}</p>
          </div>

          <!-- 操作区 -->
          <div class="ops">
            <CButton variant="ghost" v-perm.disable="'catalog:edit'" @click="doToggle">
              <CIcon :name="selected.status === 'ON_SHELF' ? 'close' : 'check'" :size="16" />
              {{ selected.status === 'ON_SHELF' ? '下架' : '上架' }}
            </CButton>
            <CButton variant="primary" v-perm.disable="'catalog:edit'" @click="openEdit">
              <CIcon name="edit" :size="16" />编辑商品
            </CButton>
          </div>
        </div>
      </CCard>

      <CCard v-else class="ct__detail ct__detail--empty" title="商品详情" padding="lg">
        <div class="detail-empty">
          <CIcon name="box" :size="40" class="detail-empty__icon" />
          <p>请选择一个商品</p>
        </div>
      </CCard>
    </div>

    <!-- 新建/编辑弹层 -->
    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <CCard class="modal" :title="editing ? '编辑商品' : '新建商品'" padding="lg">
        <div class="form">
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">商品名称 *</label>
              <CInput v-model="form.name" placeholder="如：热玛吉紧致疗程" />
            </div>
            <div>
              <label class="form__label">商品类型</label>
              <CSelect v-model="form.type" width="100%" :options="[
                { value: 'CARD', label: '卡项' },
                { value: 'COURSE', label: '疗程' },
              ]" />
            </div>
          </div>
          <div class="form__row form__row--3">
            <div>
              <label class="form__label">分类</label>
              <CInput v-model="form.category" placeholder="如：抗衰疗程" />
            </div>
            <div>
              <label class="form__label">可用次数</label>
              <CInput :model-value="String(form.sessions)"
                @update:model-value="form.sessions = Number($event) || 1" placeholder="1" />
            </div>
            <div>
              <label class="form__label">有效期（天）</label>
              <CInput :model-value="String(form.validityDays)"
                @update:model-value="form.validityDays = Number($event) || 365" placeholder="365" />
            </div>
          </div>
          <div class="form__row form__row--3">
            <div>
              <label class="form__label">售价（元）*</label>
              <CInput :model-value="String(form.price)"
                @update:model-value="form.price = Number($event) || 0" placeholder="0" />
            </div>
            <div>
              <label class="form__label">原价（元）</label>
              <CInput :model-value="String(form.originalPrice)"
                @update:model-value="form.originalPrice = Number($event) || 0" placeholder="0" />
            </div>
            <div>
              <label class="form__label">上架状态</label>
              <CSelect v-model="form.status" width="100%" :options="[
                { value: 'ON_SHELF', label: '上架中' },
                { value: 'OFF_SHELF', label: '已下架' },
              ]" />
            </div>
          </div>
          <div class="form__row">
            <label class="form__check">
              <input type="checkbox" v-model="form.transferable" />
              <span>允许客户转赠</span>
            </label>
          </div>
          <div class="form__row">
            <label class="form__label">包含项目（每行一项）</label>
            <textarea class="textarea" v-model="form.includes" rows="3" placeholder="热玛吉面部 3 次&#10;术后修复面膜 3 片"></textarea>
          </div>
          <div class="form__row">
            <label class="form__label">商品说明</label>
            <textarea class="textarea" v-model="form.description" rows="2" placeholder="商品详细说明、使用规则等"></textarea>
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showForm = false">取消</CButton>
          <CButton variant="primary" :disabled="!canSubmit" @click="submitForm">
            {{ editing ? '保存' : '创建' }}
          </CButton>
        </template>
      </CCard>
    </div>

    <!-- 确认弹层 -->
    <div v-if="confirmBox?.show" class="modal-mask" @click.self="confirmBox = null">
      <CCard class="modal modal--sm" :title="confirmBox.title" padding="lg">
        <p class="confirm__text">{{ confirmBox.text }}</p>
        <template #footer>
          <CButton variant="ghost" @click="confirmBox = null">取消</CButton>
          <CButton variant="primary" @click="runConfirm">确认</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.ct { display: flex; flex-direction: column; gap: var(--s-lg); }
.ct__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .ct__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }

.ct__body { display: grid; grid-template-columns: 380px 1fr; gap: var(--s-lg); align-items: start; }
.ct__list { min-width: 0; display: flex; flex-direction: column; }
.filters { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-md); border-bottom: 1px solid var(--c-border-light); flex-wrap: nowrap; overflow-x: auto; background: var(--c-surface); flex-shrink: 0; }
.filters > * { flex-shrink: 0; }
.ct__toolbar { flex-shrink: 0; }
.toolbar { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-md); flex-wrap: nowrap; }
.toolbar > .cinput { flex: 1; min-width: 0; }
.toolbar__btn { flex-shrink: 0; white-space: nowrap; }
.list { flex: 1; min-height: 0; max-height: 600px; overflow-y: auto; }
.empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); font-size: var(--t-sm); }
.empty__icon { color: var(--c-text-4); }

.row {
  display: block; width: 100%; text-align: left; padding: var(--s-md) var(--s-lg);
  background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer;
}
.row:hover { background: var(--c-brand-soft); }
.row--active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.row__top { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--s-xs); }
.row__type { display: inline-flex; align-items: center; gap: 4px; font-size: var(--t-xs); color: var(--c-brand); background: var(--c-brand-soft); padding: 1px 8px; border-radius: var(--r-capsule); }
.row__name { font-size: var(--t-sm); color: var(--c-text); font-weight: 600; margin-bottom: var(--s-xs); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.row__meta { display: flex; justify-content: space-between; align-items: center; margin-bottom: 2px; }
.row__code { font-size: var(--t-xs); color: var(--c-text-3); font-family: var(--f-latin); }
.row__price { font-size: var(--t-sm); font-weight: 700; color: var(--c-brand); font-variant-numeric: tabular-nums; }
.row__sub { font-size: var(--t-xs); color: var(--c-text-3); }

.ct__detail-head { display: flex; justify-content: space-between; align-items: flex-start; gap: var(--s-md); width: 100%; }
.ct__name { font-size: var(--t-lg); font-weight: 700; margin: 0; }
.ct__sub { display: flex; flex-wrap: wrap; gap: var(--s-sm); align-items: center; font-size: var(--t-xs); color: var(--c-text-3); margin-top: 4px; }
.ct__code { font-family: var(--f-latin); }
.ct__tag { display: inline-flex; align-items: center; gap: 4px; background: var(--c-brand-soft); color: var(--c-brand); padding: 1px 8px; border-radius: var(--r-capsule); }

.detail-body { padding: var(--s-lg); display: flex; flex-direction: column; gap: var(--s-lg); }

.price-row { display: flex; align-items: baseline; gap: var(--s-md); }
.price-now { font-size: var(--t-xl); font-weight: 700; color: var(--c-brand); font-variant-numeric: tabular-nums; }
.price-old { font-size: var(--t-md); color: var(--c-text-4); text-decoration: line-through; font-variant-numeric: tabular-nums; }
.price-save { font-size: var(--t-xs); color: var(--c-danger-fg); background: var(--c-danger-bg); padding: 2px 8px; border-radius: var(--r-capsule); }

.spec-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); }
.spec { background: var(--c-bg-right); border-radius: var(--r-md); padding: var(--s-md); }
.spec__label { font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: 4px; }
.spec__value { font-size: var(--t-md); font-weight: 700; color: var(--c-text); }
.spec__value.is-yes { color: var(--c-success-fg); }
.spec__value.is-no { color: var(--c-text-4); }

.block { display: flex; flex-direction: column; gap: var(--s-sm); }
.block__title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.includes { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: var(--s-xs); }
.includes__item { display: flex; align-items: center; gap: var(--s-xs); font-size: var(--t-sm); color: var(--c-text-2); }
.includes__icon { color: var(--c-success-fg); flex-shrink: 0; }
.desc { font-size: var(--t-sm); color: var(--c-text-2); line-height: var(--lh-md); margin: 0; }

.ops { display: flex; justify-content: flex-end; gap: var(--s-sm); padding-top: var(--s-lg); border-top: 1px solid var(--c-border-light); }

.detail-empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-md); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); }
.detail-empty__icon { color: var(--c-text-4); }

.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 560px; max-width: 100%; max-height: 90vh; overflow-y: auto; box-shadow: var(--shadow-pop); }
.modal--sm { width: 360px; }
.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.form__row--2 { display: grid; grid-template-columns: 2fr 1fr; gap: var(--s-md); }
.form__row--3 { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: var(--s-md); }
.form__label { font-size: var(--t-xs); color: var(--c-text-3); }
.form__check { display: flex; align-items: center; gap: var(--s-xs); font-size: var(--t-sm); color: var(--c-text-2); cursor: pointer; }
.textarea {
  width: 100%; padding: var(--s-sm); border: 1px solid var(--c-border); border-radius: var(--r-sm);
  background: var(--c-surface); font-size: var(--t-sm); color: var(--c-text); line-height: var(--lh-sm);
  resize: vertical; outline: none; font-family: inherit;
}
.textarea:focus { border-color: var(--c-brand); box-shadow: 0 0 0 2px rgba(255, 107, 158, 0.12); }
.confirm__text { font-size: var(--t-sm); color: var(--c-text); text-align: center; margin: var(--s-md) 0; line-height: var(--lh-md); }

@media (max-width: 1024px) {
  .ct__body { grid-template-columns: 1fr; }
  .ct__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .spec-grid { grid-template-columns: repeat(2, 1fr); }
  .form__row--2, .form__row--3 { grid-template-columns: 1fr; }
  .list { max-height: 320px; }
}
</style>
