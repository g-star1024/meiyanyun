<script setup lang="ts">
/* ============================================================
 * 标签体系 /m3-tags（M3-06）
 * Desktop：4 KPI + 标签分类树（左）+ 打标统计/自动化规则（右）+ 标签详情弹层。
 * Tablet：KPI 2x2、分类树全宽、规则卡片堆叠、底部「批量打标」。
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
import { useTagStore, type TagCategory, type CustomerTag } from '@/stores/tag'

const store = useTagStore()
onMounted(() => store.seed())

const kpis = computed(() => [
  { label: '标签总数', icon: 'customer', value: String(store.totalTags), tone: 'text' as const },
  { label: '系统标签', icon: 'customer', value: String(store.systemTags.length), tone: 'brand' as const },
  { label: '人工标签', icon: 'customer', value: String(store.manualTags.length), tone: 'warning' as const },
  { label: '覆盖客户数', icon: 'customer', value: store.totalCovered.toLocaleString(), tone: 'success' as const },
])

// 来源筛选（Tablet 设计）
type Source = 'ALL' | TagCategory
const source = ref<Source>('ALL')
const keyword = ref('')

const groups = computed(() => {
  const all: Array<{ key: TagCategory; label: string; tags: CustomerTag[] }> = [
    { key: 'SYSTEM', label: '系统标签', tags: store.systemTags },
    { key: 'MANUAL', label: '人工标签', tags: store.manualTags },
    { key: 'BEHAVIOR', label: '行为标签', tags: store.behaviorTags },
  ]
  return all
    .filter((g) => source.value === 'ALL' || g.key === source.value)
    .map((g) => ({
      ...g,
      tags: g.tags.filter((t) => !keyword.value.trim() || t.name.includes(keyword.value.trim())),
    }))
    .filter((g) => g.tags.length > 0)
})

function fmtDateTime(iso?: string) {
  if (!iso) return '—'
  const d = new Date(iso)
  return `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

// 新建标签弹层
const showForm = ref(false)
const form = reactive({
  name: '',
  category: 'MANUAL' as TagCategory,
  color: '#F59E0B',
  rule: '',
})
const categoryOptions = [
  { value: 'SYSTEM', label: '系统标签' },
  { value: 'MANUAL', label: '人工标签' },
  { value: 'BEHAVIOR', label: '行为标签' },
]
const colorPresets = [
  { value: '#8B5CF6', label: '紫' },
  { value: '#F59E0B', label: '橙' },
  { value: '#6366F1', label: '蓝' },
  { value: '#10B981', label: '绿' },
  { value: '#EC4899', label: '粉' },
  { value: '#EF4444', label: '红' },
]
function openForm() {
  form.name = ''
  form.category = 'MANUAL'
  form.color = '#F59E0B'
  form.rule = ''
  showForm.value = true
}
function submitForm() {
  if (!form.name.trim()) return
  const t = store.createTag({
    name: form.name.trim(),
    category: form.category,
    color: form.color,
    rule: form.rule.trim() || '人工标记',
  })
  if (t) {
    store.select(t.id)
    showForm.value = false
  }
}

// 批量打标确认
const showBatch = ref(false)
const batchForm = reactive({ tagName: '', customers: '' })
function openBatch() {
  batchForm.tagName = store.selected?.name ?? ''
  batchForm.customers = ''
  showBatch.value = true
}
function submitBatch() {
  const names = batchForm.customers.split(/[\n,，]/).map((s) => s.trim()).filter(Boolean)
  if (!batchForm.tagName || names.length === 0) return
  if (store.batchTag(names, batchForm.tagName)) {
    showBatch.value = false
    toast.value = `已为 ${names.length} 位客户打标`
    setTimeout(() => (toast.value = ''), 2000)
  }
}

const toast = ref('')
</script>

<template>
  <div class="tg">
    <div class="tg__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <!-- Tablet: 搜索+来源筛选 -->
    <CCard class="tg__search" padding="md">
      <CInput v-model="keyword" placeholder="搜索标签名称" />
      <div class="src-tabs">
        <button class="src" :class="{ 'src--active': source === 'ALL' }" @click="source = 'ALL'">全部来源</button>
        <button class="src" :class="{ 'src--active': source === 'SYSTEM' }" @click="source = 'SYSTEM'">系统</button>
        <button class="src" :class="{ 'src--active': source === 'MANUAL' }" @click="source = 'MANUAL'">人工</button>
        <button class="src" :class="{ 'src--active': source === 'BEHAVIOR' }" @click="source = 'BEHAVIOR'">行为</button>
      </div>
    </CCard>

    <div class="tg__body">
      <!-- 左：标签分类树 -->
      <CCard class="tg__tree" title="标签分类树" padding="lg">
        <template #header>
          <h3 class="tg__card-title">标签分类树</h3>
          <div class="tg__tree-actions">
            <span class="tg__total">共 {{ store.totalTags }} 个标签</span>
            <CButton variant="primary" size="sm" v-perm.disable="'tag:edit'" @click="openForm">
              <CIcon name="plus" :size="14" />新建标签
            </CButton>
          </div>
        </template>

        <div v-for="g in groups" :key="g.key" class="tcat">
          <div class="tcat__head">
            <span class="tcat__dot" :style="{ background: store.CATEGORY_COLOR[g.key] }" />
            <span class="tcat__name">{{ g.label }}</span>
            <span class="tcat__count">{{ g.tags.length }} 个</span>
          </div>
          <div class="tcat__chips">
            <button
              v-for="t in g.tags" :key="t.id"
              class="chip"
              :class="{ 'chip--active': store.selectedId === t.id }"
              :style="{
                '--chip-color': t.color,
                background: store.selectedId === t.id ? t.color : `color-mix(in srgb, ${t.color} 12%, var(--c-surface))`,
                color: store.selectedId === t.id ? '#fff' : t.color,
              }"
              @click="store.select(t.id)"
            >
              {{ t.name }}
            </button>
          </div>
        </div>
      </CCard>

      <!-- 右：统计 + 详情 + 规则 -->
      <div class="tg__side">
        <!-- 选中标签详情 -->
        <CCard v-if="store.selected" class="tg__detail" :title="store.selected.name" padding="lg">
          <template #header>
            <h3 class="tg__card-title">
              <span class="dot" :style="{ background: store.selected.color }" />
              {{ store.selected.name }}
            </h3>
            <CStatusPill :status="store.selected.category === 'SYSTEM' ? 'primary' : store.selected.category === 'MANUAL' ? 'warning' : 'info'">
              {{ store.CATEGORY_LABEL[store.selected.category] }}
            </CStatusPill>
          </template>

          <div class="detail-stats">
            <div class="detail-stats__item">
              <div class="detail-stats__label">命中客户数</div>
              <div class="detail-stats__value" :style="{ color: store.selected.color }">{{ store.selected.customerCount.toLocaleString() }}</div>
            </div>
          </div>

          <div class="detail-sec">
            <div class="detail-sec__label">命中规则</div>
            <div class="detail-sec__text">{{ store.selected.rule }}</div>
          </div>

          <div v-if="store.selected.recentHitCustomers?.length" class="detail-sec">
            <div class="detail-sec__label">最近命中客户</div>
            <div class="detail-sec__customers">
              <span v-for="(c, i) in store.selected.recentHitCustomers" :key="i" class="cust-chip">
                <CIcon name="user" :size="12" />{{ c }}
              </span>
            </div>
          </div>

          <div class="detail-actions">
            <CButton variant="primary" size="sm" v-perm.disable="'tag:edit'" @click="openBatch">
              <CIcon name="plus" :size="14" />批量打标
            </CButton>
          </div>
        </CCard>

        <!-- 打标统计 -->
        <CCard class="tg__stats" title="打标统计" padding="lg">
          <div class="stat-row">
            <span class="stat-row__label">总覆盖人数</span>
            <span class="stat-row__value">{{ store.totalCovered.toLocaleString() }} 人</span>
          </div>
          <div class="stat-row">
            <span class="stat-row__label">今日新增打标</span>
            <span class="stat-row__value stat-row__value--teal">+{{ store.todayTagged }} 人</span>
          </div>
          <div class="stat-row">
            <span class="stat-row__label">人均标签数</span>
            <span class="stat-row__value">{{ store.avgTagsPerCustomer }} 个</span>
          </div>
        </CCard>

        <!-- 自动化规则 -->
        <CCard class="tg__rules" title="自动化规则" padding="lg">
          <template #header>
            <h3 class="tg__card-title">自动化规则</h3>
            <CButton variant="text" size="sm" v-perm.disable="'tag:edit'">
              <CIcon name="plus" :size="14" />添加规则
            </CButton>
          </template>
          <div class="rule-list">
            <div v-for="r in store.rules" :key="r.id" class="rule-item">
              <div class="rule-item__head">
                <span class="rule-item__name">{{ r.name }}</span>
                <label class="mini-switch" @click.stop>
                  <input type="checkbox" :checked="r.enabled" @change="store.toggleRule(r.id)" />
                  <span class="mini-switch__track" />
                </label>
              </div>
              <div class="rule-item__flow">
                <span class="rule-item__trigger">{{ r.trigger }}</span>
                <CIcon name="chevron-right" :size="12" />
                <span class="rule-item__action">{{ r.action }}</span>
              </div>
              <div class="rule-item__meta">
                <span>条件：{{ r.condition }}</span>
                <span v-if="r.lastFiredAt">最近触发：{{ fmtDateTime(r.lastFiredAt) }}</span>
              </div>
            </div>
          </div>
        </CCard>
      </div>
    </div>

    <!-- 底部批量打标（平板） -->
    <div class="tg__fab">
      <CButton variant="primary" block size="lg" v-perm.disable="'tag:edit'" @click="openBatch">
        <CIcon name="plus" :size="16" />批量打标
      </CButton>
    </div>

    <!-- 新建标签弹层 -->
    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <CCard class="modal" title="新建标签" padding="lg">
        <div class="form">
          <CInput label="标签名称" v-model="form.name" placeholder="如：高净值客户" />
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">所属分类</label>
              <CSelect v-model="form.category" width="100%" :options="categoryOptions" />
            </div>
            <div>
              <label class="form__label">标签颜色</label>
              <div class="color-row">
                <button
                  v-for="c in colorPresets" :key="c.value"
                  type="button"
                  class="color-dot"
                  :class="{ 'color-dot--active': form.color === c.value }"
                  :style="{ background: c.value }"
                  :title="c.label"
                  @click="form.color = c.value"
                >
                  <CIcon v-if="form.color === c.value" name="check" :size="12" />
                </button>
              </div>
            </div>
          </div>
          <CTextarea label="命中规则描述" v-model="form.rule" placeholder="如：近 90 天累计消费 ≥ 10,000 元" :rows="3" />
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showForm = false">取消</CButton>
          <CButton variant="primary" :disabled="!form.name.trim()" @click="submitForm">创建</CButton>
        </template>
      </CCard>
    </div>

    <!-- 批量打标弹层 -->
    <div v-if="showBatch" class="modal-mask" @click.self="showBatch = false">
      <CCard class="modal" title="批量打标" padding="lg">
        <div class="form">
          <CInput label="标签名称" v-model="batchForm.tagName" placeholder="选择或输入标签" />
          <CTextarea
            label="客户名单（每行一个，或用逗号分隔）"
            v-model="batchForm.customers"
            placeholder="陈美玲&#10;赵雨晴&#10;孙佳宁"
            :rows="5"
          />
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showBatch = false">取消</CButton>
          <CButton variant="primary" :disabled="!batchForm.tagName || !batchForm.customers.trim()" @click="submitBatch">确认打标</CButton>
        </template>
      </CCard>
    </div>

    <transition name="toast">
      <div v-if="toast" class="toast"><CIcon name="check" :size="16" />{{ toast }}</div>
    </transition>
  </div>
</template>

<style scoped>
.tg { display: flex; flex-direction: column; gap: var(--s-lg); }
.tg__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .tg__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }
.tg__tree-actions { display: flex; align-items: center; gap: var(--s-sm); flex-shrink: 0; }

.tg__search { display: none; flex-direction: column; gap: var(--s-sm); }
.src-tabs { display: flex; gap: var(--s-xs); flex-wrap: wrap; }
.src {
  padding: var(--s-xs) var(--s-md);
  border: 1px solid var(--c-border);
  background: var(--c-surface);
  border-radius: var(--r-capsule);
  font-size: var(--t-sm); color: var(--c-text-2);
  cursor: pointer;
}
.src--active { background: var(--c-brand); border-color: var(--c-brand); color: #fff; }

.tg__body { display: grid; grid-template-columns: 1.4fr 1fr; gap: var(--s-lg); align-items: start; }
.tg__side { display: flex; flex-direction: column; gap: var(--s-lg); }

.tg__card-title {
  font-size: var(--t-md); font-weight: 700; margin: 0;
  display: inline-flex; align-items: center; gap: var(--s-xs);
}
.tg__total { font-size: var(--t-sm); color: var(--c-text-3); }

.tcat { padding: var(--s-md) 0; border-bottom: 1px solid var(--c-border-light); }
.tcat:last-child { border-bottom: none; }
.tcat__head { display: flex; align-items: center; gap: var(--s-xs); margin-bottom: var(--s-sm); }
.tcat__dot { width: 8px; height: 8px; border-radius: 50%; }
.tcat__name { font-size: var(--t-sm); font-weight: 700; color: var(--c-text); }
.tcat__count { margin-left: auto; font-size: var(--t-xs); color: var(--c-text-3); }
.tcat__chips { display: flex; flex-wrap: wrap; gap: var(--s-xs); }
.chip {
  --chip-color: var(--c-brand);
  padding: 6px 14px;
  border: 1px solid transparent;
  border-radius: var(--r-capsule);
  font-size: var(--t-sm); font-weight: 600;
  cursor: pointer;
  transition: transform 0.1s, box-shadow 0.15s;
}
.chip:hover { transform: translateY(-1px); box-shadow: var(--shadow-card); }
.chip--active { box-shadow: 0 0 0 2px var(--chip-color) inset; }

/* 详情卡 */
.tg__detail .dot {
  display: inline-block; width: 10px; height: 10px; border-radius: 50%;
}
.detail-stats {
  display: flex; gap: var(--s-md);
  padding: var(--s-md);
  background: var(--c-surface-muted, #f5f6fa);
  border-radius: var(--r-md);
  margin-bottom: var(--s-md);
}
.detail-stats__item { flex: 1; text-align: center; }
.detail-stats__label { font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: 4px; }
.detail-stats__value { font-size: 22px; font-weight: 700; font-variant-numeric: tabular-nums; }

.detail-sec { margin-bottom: var(--s-md); }
.detail-sec__label { font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: 4px; }
.detail-sec__text { font-size: var(--t-sm); color: var(--c-text); line-height: var(--lh-md); }
.detail-sec__customers { display: flex; flex-wrap: wrap; gap: var(--s-xs); }
.cust-chip {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 4px 10px;
  background: var(--c-brand-soft); color: var(--c-brand);
  border-radius: var(--r-capsule);
  font-size: var(--t-xs);
}
.detail-actions { display: flex; justify-content: flex-end; padding-top: var(--s-sm); border-top: 1px solid var(--c-border-light); }

/* 统计卡 */
.stat-row { display: flex; justify-content: space-between; align-items: center; padding: var(--s-sm) 0; border-bottom: 1px solid var(--c-border-light); }
.stat-row:last-child { border-bottom: none; }
.stat-row__label { font-size: var(--t-sm); color: var(--c-text-2); }
.stat-row__value { font-size: var(--t-lg); font-weight: 700; color: var(--c-text); font-variant-numeric: tabular-nums; }
.stat-row__value--teal { color: var(--c-teal-dark); }

/* 规则卡 */
.rule-list { display: flex; flex-direction: column; gap: var(--s-sm); }
.rule-item {
  padding: var(--s-md);
  background: var(--c-surface-muted, #f5f6fa);
  border-radius: var(--r-md);
}
.rule-item__head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px; }
.rule-item__name { font-size: var(--t-sm); font-weight: 700; color: var(--c-text); }
.rule-item__flow {
  display: flex; align-items: center; gap: var(--s-xs);
  font-size: var(--t-sm); color: var(--c-text-2);
  margin-bottom: 4px;
}
.rule-item__trigger { color: var(--c-text-3); }
.rule-item__action { color: var(--c-brand); font-weight: 600; }
.rule-item__meta { display: flex; justify-content: space-between; font-size: var(--t-xs); color: var(--c-text-3); gap: var(--s-md); flex-wrap: wrap; }

.mini-switch { display: inline-flex; position: relative; width: 36px; height: 20px; cursor: pointer; }
.mini-switch input { position: absolute; opacity: 0; inset: 0; cursor: pointer; }
.mini-switch__track {
  position: absolute; inset: 0;
  background: var(--c-border); border-radius: var(--r-capsule);
  transition: background 0.15s;
}
.mini-switch__track::after {
  content: ''; position: absolute; top: 2px; left: 2px; width: 16px; height: 16px;
  background: var(--c-surface); border-radius: 50%;
  box-shadow: 0 1px 2px rgba(0,0,0,.2);
  transition: transform 0.15s;
}
.mini-switch input:checked + .mini-switch__track { background: var(--c-brand); }
.mini-switch input:checked + .mini-switch__track::after { transform: translateX(16px); }

.tg__fab { display: none; }

.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 520px; max-width: 100%; max-height: 90vh; overflow-y: auto; box-shadow: var(--shadow-pop); }
.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.form__row--2 { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.form__label { font-size: var(--t-xs); color: var(--c-text-3); }
.color-row { display: flex; gap: var(--s-xs); height: 36px; align-items: center; }
.color-dot {
  width: 28px; height: 28px; border-radius: 50%; border: 2px solid transparent;
  display: inline-flex; align-items: center; justify-content: center;
  color: #fff; cursor: pointer;
}
.color-dot--active { border-color: var(--c-text); }

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
  .tg__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .tg__body { grid-template-columns: 1fr; }
  .tg__search { display: flex; }
  .tg__fab { display: block; position: sticky; bottom: var(--s-md); z-index: 10; }
  .rule-item__meta { flex-direction: column; gap: 2px; }
}
</style>
