<script setup lang="ts">
/* ============================================================
 * 标签体系 /m3-tags（M3-06，P5-B23 卡3 真实化）
 * Desktop：4 KPI + 标签分类树（左）+ 标签详情/打标统计/自动化规则（右）。
 * 数据：customer-service /customer/tags(/overview)；五分类中文枚举库内即中文。
 * 颜色无入库列，前端按五分类固定映射；自动化规则依赖事件流，列 Backlog 占位。
 * 打标主路径在客户 360 页；本页"查看命中客户"跳客户列表按标签过滤。
 * ============================================================ */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import { useToast } from '@/composables/useToast'
import {
  listAllTags,
  getTagOverview,
  createTag,
  updateTag,
  deleteTag,
  type CustomerTagDTO,
  type TagOverviewDTO,
  type TagUpsertReq,
} from '@/api/customer'

const router = useRouter()
const toast = useToast()

// 五分类为库内 CHECK 约束权威取值；颜色不入库，按分类固定映射
const CATEGORIES = ['消费', '肤质', '行为', '价值', '医疗'] as const
type Category = (typeof CATEGORIES)[number]
const CATEGORY_COLOR: Record<Category, string> = {
  消费: '#10B981',
  肤质: '#8B5CF6',
  行为: '#6366F1',
  价值: '#F59E0B',
  医疗: '#EC4899',
}
const CATEGORY_PILL: Record<Category, 'primary' | 'success' | 'warning' | 'danger' | 'info'> = {
  消费: 'success',
  肤质: 'primary',
  行为: 'info',
  价值: 'warning',
  医疗: 'danger',
}

const tags = ref<CustomerTagDTO[]>([])
const overview = ref<TagOverviewDTO>({ totalTags: 0, coveredCustomers: 0, totalAssignments: 0, avgTagsPerCustomer: 0 })
const loading = ref(false)
const selectedId = ref<string>('')

async function refresh(keepSelected = true) {
  loading.value = true
  try {
    const [tagRes, ovRes] = await Promise.all([listAllTags(), getTagOverview()])
    tags.value = tagRes.data ?? []
    overview.value = ovRes.data
    if (!keepSelected || !tags.value.some((t) => t.tagId === selectedId.value)) {
      selectedId.value = tags.value[0]?.tagId ?? ''
    }
  } catch (e: any) {
    toast.error('标签数据加载失败：' + (e?.response?.data?.message || e?.message || '网络异常'))
  } finally {
    loading.value = false
  }
}
onMounted(() => refresh(false))

const kpis = computed(() => [
  { label: '标签总数', icon: 'customer', value: String(overview.value.totalTags), tone: 'text' as const },
  { label: '覆盖客户数', icon: 'customer', value: overview.value.coveredCustomers.toLocaleString(), tone: 'brand' as const },
  { label: '累计打标人次', icon: 'customer', value: overview.value.totalAssignments.toLocaleString(), tone: 'warning' as const },
  { label: '人均标签数', icon: 'customer', value: String(overview.value.avgTagsPerCustomer), tone: 'success' as const },
])

// 分类筛选（Tablet 设计）：全部 + 五分类
type Source = 'ALL' | Category
const source = ref<Source>('ALL')
const keyword = ref('')

const groups = computed(() => {
  const kw = keyword.value.trim()
  return CATEGORIES
    .map((cat) => ({
      key: cat,
      label: cat + '标签',
      color: CATEGORY_COLOR[cat],
      tags: tags.value.filter(
        (t) => t.category === cat && (!kw || t.tagName.includes(kw)),
      ),
    }))
    .filter((g) => (source.value === 'ALL' || g.key === source.value) && g.tags.length > 0)
})

const selected = computed(() => tags.value.find((t) => t.tagId === selectedId.value) || null)

// 新建 / 编辑标签弹层（颜色按分类派生，不入库；命中规则/自动化归事件流 Backlog）
const showForm = ref(false)
const editingId = ref<string | null>(null)
const form = reactive<{ tagName: string; category: Category }>({ tagName: '', category: '价值' })
const categoryOptions = CATEGORIES.map((c) => ({ value: c, label: c + '标签' }))
const saving = ref(false)

function openCreate() {
  editingId.value = null
  form.tagName = ''
  form.category = '价值'
  showForm.value = true
}
function openEdit() {
  if (!selected.value) return
  editingId.value = selected.value.tagId
  form.tagName = selected.value.tagName
  form.category = (CATEGORIES.includes(selected.value.category as Category)
    ? selected.value.category
    : '价值') as Category
  showForm.value = true
}
async function submitForm() {
  const name = form.tagName.trim()
  if (!name) return
  if (name.length > 32) {
    toast.error('标签名称最长 32 字')
    return
  }
  const payload: TagUpsertReq = { tagName: name, category: form.category }
  saving.value = true
  try {
    if (editingId.value) {
      await updateTag(editingId.value, payload)
      toast.success('标签已更新')
    } else {
      const res = await createTag(payload)
      selectedId.value = res.data.tagId
      toast.success('标签已创建')
    }
    showForm.value = false
    await refresh()
  } catch (e: any) {
    toast.error((editingId.value ? '更新失败：' : '创建失败：') + (e?.response?.data?.message || e?.message || '网络异常'))
  } finally {
    saving.value = false
  }
}

async function removeSelected() {
  const t = selected.value
  if (!t) return
  const hit = t.customerCount ?? 0
  const tip = hit > 0 ? `，将同时解绑 ${hit} 位客户的该标签` : ''
  if (!window.confirm(`确认删除标签「${t.tagName}」？${tip}删除后不可恢复。`)) return
  try {
    await deleteTag(t.tagId)
    toast.success(hit > 0 ? `标签已删除，已解绑 ${hit} 位客户` : '标签已删除')
    await refresh(false)
  } catch (e: any) {
    toast.error('删除失败：' + (e?.response?.data?.message || e?.message || '网络异常'))
  }
}

// 查看命中客户：跳客户列表并按该标签过滤（打标/删标在客户 360 页操作）
function viewCustomers() {
  if (!selected.value) return
  router.push({ path: '/customers', query: { tagId: selected.value.tagId } })
}
</script>

<template>
  <div class="tg">
    <div class="tg__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <!-- Tablet: 搜索+分类筛选 -->
    <CCard class="tg__search" padding="md">
      <CInput v-model="keyword" placeholder="搜索标签名称" />
      <div class="src-tabs">
        <button class="src" :class="{ 'src--active': source === 'ALL' }" @click="source = 'ALL'">全部分类</button>
        <button
          v-for="c in CATEGORIES" :key="c"
          class="src" :class="{ 'src--active': source === c }"
          @click="source = c"
        >{{ c }}</button>
      </div>
    </CCard>

    <div class="tg__body">
      <!-- 左：标签分类树 -->
      <CCard class="tg__tree" title="标签分类树" padding="lg">
        <template #header>
          <h3 class="tg__card-title">标签分类树</h3>
          <div class="tg__tree-actions">
            <span class="tg__total">共 {{ tags.length }} 个标签</span>
            <CButton variant="primary" size="sm" v-perm.disable="'tag:edit'" @click="openCreate">
              <CIcon name="plus" :size="14" />新建标签
            </CButton>
          </div>
        </template>

        <div v-if="loading && !tags.length" class="tg__hint">标签加载中…</div>
        <div v-else-if="!groups.length" class="tg__hint">暂无符合条件的标签</div>
        <div v-for="g in groups" :key="g.key" class="tcat">
          <div class="tcat__head">
            <span class="tcat__dot" :style="{ background: g.color }" />
            <span class="tcat__name">{{ g.label }}</span>
            <span class="tcat__count">{{ g.tags.length }} 个</span>
          </div>
          <div class="tcat__chips">
            <button
              v-for="t in g.tags" :key="t.tagId"
              class="chip"
              :class="{ 'chip--active': selectedId === t.tagId }"
              :style="{
                '--chip-color': g.color,
                background: selectedId === t.tagId ? g.color : `color-mix(in srgb, ${g.color} 12%, var(--c-surface))`,
                color: selectedId === t.tagId ? '#fff' : g.color,
              }"
              @click="selectedId = t.tagId"
            >
              {{ t.tagName }}
            </button>
          </div>
        </div>
      </CCard>

      <!-- 右：详情 + 统计 + 规则 -->
      <div class="tg__side">
        <!-- 选中标签详情 -->
        <CCard v-if="selected" class="tg__detail" :title="selected.tagName" padding="lg">
          <template #header>
            <h3 class="tg__card-title">
              <span class="dot" :style="{ background: CATEGORY_COLOR[(selected.category as Category) in CATEGORY_COLOR ? (selected.category as Category) : '价值'] }" />
              {{ selected.tagName }}
            </h3>
            <CStatusPill :status="CATEGORY_PILL[(selected.category as Category) in CATEGORY_COLOR ? (selected.category as Category) : '价值']">
              {{ selected.category }}标签
            </CStatusPill>
          </template>

          <div class="detail-stats">
            <div class="detail-stats__item">
              <div class="detail-stats__label">命中客户数</div>
              <div class="detail-stats__value" :style="{ color: CATEGORY_COLOR[selected.category as Category] || CATEGORY_COLOR['价值'] }">
                {{ (selected.customerCount ?? 0).toLocaleString() }}
              </div>
            </div>
            <div class="detail-stats__item">
              <div class="detail-stats__label">标签编号</div>
              <div class="detail-stats__value detail-stats__value--code">{{ selected.tagId }}</div>
            </div>
          </div>

          <div class="detail-sec">
            <div class="detail-sec__label">打标方式</div>
            <div class="detail-sec__text">人工打标：在客户 360 页为单个客户打标/删标；本页可查看命中客户名单。</div>
          </div>

          <div class="detail-actions">
            <CButton variant="ghost" size="sm" @click="viewCustomers">
              <CIcon name="customer" :size="14" />查看命中客户
            </CButton>
            <CButton variant="ghost" size="sm" v-perm.disable="'tag:edit'" @click="openEdit">
              <CIcon name="edit" :size="14" />编辑
            </CButton>
            <CButton variant="danger" size="sm" v-perm.disable="'tag:edit'" @click="removeSelected">
              <CIcon name="delete" :size="14" />删除
            </CButton>
          </div>
        </CCard>
        <CCard v-else class="tg__detail" title="标签详情" padding="lg">
          <div class="tg__hint">请选择左侧标签查看详情</div>
        </CCard>

        <!-- 打标统计 -->
        <CCard class="tg__stats" title="打标统计" padding="lg">
          <div class="stat-row">
            <span class="stat-row__label">总覆盖人数</span>
            <span class="stat-row__value">{{ overview.coveredCustomers.toLocaleString() }} 人</span>
          </div>
          <div class="stat-row">
            <span class="stat-row__label">累计打标人次</span>
            <span class="stat-row__value stat-row__value--teal">{{ overview.totalAssignments.toLocaleString() }} 次</span>
          </div>
          <div class="stat-row">
            <span class="stat-row__label">人均标签数</span>
            <span class="stat-row__value">{{ overview.avgTagsPerCustomer }} 个</span>
          </div>
        </CCard>

        <!-- 自动化规则（依赖事件流，列 Backlog） -->
        <CCard class="tg__rules" title="自动化规则" padding="lg">
          <template #header>
            <h3 class="tg__card-title">自动化规则</h3>
            <CStatusPill status="disabled">规划中</CStatusPill>
          </template>
          <div class="rule-soon">
            <CIcon name="mall" :size="20" />
            <div class="rule-soon__body">
              <div class="rule-soon__title">事件流上线后开放</div>
              <div class="rule-soon__desc">
                支持「消费达标 / 到店频次 / 生日月 / 肤质检测」等触发条件自动打标与标签失效。
                当前请在客户 360 页人工打标，规则引擎随消费事件流（Backlog）一并交付。
              </div>
            </div>
          </div>
        </CCard>
      </div>
    </div>

    <!-- 底部新建标签（平板） -->
    <div class="tg__fab">
      <CButton variant="primary" block size="lg" v-perm.disable="'tag:edit'" @click="openCreate">
        <CIcon name="plus" :size="16" />新建标签
      </CButton>
    </div>

    <!-- 新建/编辑标签弹层 -->
    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <CCard class="modal" :title="editingId ? '编辑标签' : '新建标签'" padding="lg">
        <div class="form">
          <CInput label="标签名称" v-model="form.tagName" placeholder="如：高净值客户（最长 32 字）" />
          <div class="form__row">
            <label class="form__label">所属分类</label>
            <CSelect v-model="form.category" width="100%" :options="categoryOptions" />
          </div>
          <div class="form__tip">
            <span class="form__tip-dot" :style="{ background: CATEGORY_COLOR[form.category] }" />
            标签颜色按「{{ form.category }}」分类固定展示；标签名称全库唯一，删除标签将同时解绑全部客户。
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showForm = false">取消</CButton>
          <CButton variant="primary" :disabled="!form.tagName.trim() || saving" @click="submitForm">
            {{ editingId ? '保存' : '创建' }}
          </CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.tg { display: flex; flex-direction: column; gap: var(--s-lg); }
.tg__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .tg__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }
.tg__tree-actions { display: flex; align-items: center; gap: var(--s-sm); flex-shrink: 0; }

.tg__hint { padding: var(--s-lg) 0; text-align: center; font-size: var(--t-sm); color: var(--c-text-3); }

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
.detail-stats__value--code { font-size: var(--t-md); color: var(--c-text-2); }

.detail-sec { margin-bottom: var(--s-md); }
.detail-sec__label { font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: 4px; }
.detail-sec__text { font-size: var(--t-sm); color: var(--c-text); line-height: var(--lh-md); }
.detail-actions { display: flex; justify-content: flex-end; gap: var(--s-xs); padding-top: var(--s-sm); border-top: 1px solid var(--c-border-light); }

/* 统计卡 */
.stat-row { display: flex; justify-content: space-between; align-items: center; padding: var(--s-sm) 0; border-bottom: 1px solid var(--c-border-light); }
.stat-row:last-child { border-bottom: none; }
.stat-row__label { font-size: var(--t-sm); color: var(--c-text-2); }
.stat-row__value { font-size: var(--t-lg); font-weight: 700; color: var(--c-text); font-variant-numeric: tabular-nums; }
.stat-row__value--teal { color: var(--c-teal-dark); }

/* 自动化规则 Backlog 占位 */
.rule-soon {
  display: flex; gap: var(--s-md); align-items: flex-start;
  padding: var(--s-md);
  background: var(--c-surface-muted, #f5f6fa);
  border: 1px dashed var(--c-border);
  border-radius: var(--r-md);
  color: var(--c-text-3);
}
.rule-soon__title { font-size: var(--t-sm); font-weight: 700; color: var(--c-text-2); margin-bottom: 4px; }
.rule-soon__desc { font-size: var(--t-xs); color: var(--c-text-3); line-height: var(--lh-md); }

.tg__fab { display: none; }

.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 520px; max-width: 100%; max-height: 90vh; overflow-y: auto; box-shadow: var(--shadow-pop); }
.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.form__label { font-size: var(--t-xs); color: var(--c-text-3); }
.form__tip {
  display: flex; align-items: flex-start; gap: var(--s-xs);
  font-size: var(--t-xs); color: var(--c-text-3); line-height: var(--lh-md);
}
.form__tip-dot { width: 8px; height: 8px; border-radius: 50%; margin-top: 5px; flex-shrink: 0; }

@media (max-width: 1024px) {
  .tg__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .tg__body { grid-template-columns: 1fr; }
  .tg__search { display: flex; }
  .tg__fab { display: block; position: sticky; bottom: var(--s-md); z-index: 10; }
}
</style>
