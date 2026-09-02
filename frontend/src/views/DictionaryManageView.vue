<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getManageDictionaries, createDictionary, updateDictionary, getCategories } from '@/api/dictionary'
import type { DictionaryDTO } from '@/api/dictionary'
import { useToast } from '@/composables/useToast'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'

const router = useRouter()
const toast = useToast()

/** 管理端暂无登录体系，审计操作人统一记 admin（后续接入 RBAC 后替换为当前登录人） */
const OPERATOR = 'admin'

function errMsg(e: any, fallback: string) {
  return e?.response?.data?.message || e?.message || fallback
}

// 状态
const loading = ref(false)
const categories = ref<string[]>([])
const dictionaries = ref<Record<string, DictionaryDTO[]>>({})
const selectedCategory = ref<string>('')
const showForm = ref(false)
const editingItem = ref<DictionaryDTO | null>(null)

// 表单
const form = ref<DictionaryDTO>({
  category: '',
  dictCode: '',
  dictValue: '',
  dictLabel: '',
  dictColor: 'default',
  dictIcon: '',
  sortOrder: 0,
  enabled: true,
  description: '',
})

// 颜色选项
const colorOptions = [
  { value: 'default', label: '默认' },
  { value: 'success', label: '成功' },
  { value: 'warning', label: '警告' },
  { value: 'danger', label: '危险' },
  { value: 'info', label: '信息' },
  { value: 'primary', label: '主要' },
]

// 计算属性
const filteredDictionaries = computed(() => {
  if (!selectedCategory.value) {
    return Object.values(dictionaries.value).flat()
  }
  return dictionaries.value[selectedCategory.value] || []
})

const categoryOptions = computed(() => [
  { value: '', label: '全部分类' },
  ...categories.value.map(c => ({ value: c, label: c }))
])

// 方法
async function loadData() {
  loading.value = true
  try {
    const [catsRes, dictsRes] = await Promise.all([
      getCategories(),
      getManageDictionaries()
    ])
    categories.value = catsRes.data
    dictionaries.value = dictsRes.data
  } catch (error) {
    toast.error('字典加载失败：' + errMsg(error, '网络异常'))
  } finally {
    loading.value = false
  }
}

/** 当前分类下启用/停用数量统计 */
const stats = computed(() => {
  const items = filteredDictionaries.value
  return {
    total: items.length,
    enabled: items.filter(i => i.enabled !== false).length,
    disabled: items.filter(i => i.enabled === false).length,
  }
})

function openCreateForm() {
  editingItem.value = null
  form.value = {
    category: selectedCategory.value || categories.value[0] || '',
    dictCode: '',
    dictValue: '',
    dictLabel: '',
    dictColor: 'default',
    dictIcon: '',
    sortOrder: 0,
    enabled: true,
    description: '',
  }
  showForm.value = true
}

function openEditForm(item: DictionaryDTO) {
  editingItem.value = item
  form.value = { ...item }
  showForm.value = true
}

const saving = ref(false)

async function saveForm() {
  const f = form.value
  if (!f.category?.trim()) { toast.error('请选择分类'); return }
  if (!f.dictCode?.trim()) { toast.error('请填写字典编码'); return }
  if (!f.dictValue?.trim()) { toast.error('请填写字典值'); return }
  if (!f.dictLabel?.trim()) { toast.error('请填写显示标签'); return }
  saving.value = true
  try {
    const payload = { ...f, operator: OPERATOR }
    if (editingItem.value?.id) {
      // 业务键（分类/编码/值）不可改；仅提交展示属性
      await updateDictionary(editingItem.value.id, {
        ...editingItem.value,
        dictLabel: f.dictLabel,
        dictColor: f.dictColor,
        dictIcon: f.dictIcon,
        sortOrder: f.sortOrder,
        enabled: f.enabled,
        description: f.description,
        operator: OPERATOR,
      })
      toast.success('字典项已更新')
    } else {
      await createDictionary(payload)
      toast.success('字典项已新增')
    }
    showForm.value = false
    await loadData()
  } catch (error) {
    toast.error('保存失败：' + errMsg(error, '网络异常'))
  } finally {
    saving.value = false
  }
}

async function toggleEnabled(item: DictionaryDTO) {
  const target = item.enabled === false
  try {
    await updateDictionary(item.id!, { ...item, enabled: target, operator: OPERATOR })
    toast.success(target ? `已启用「${item.dictLabel}」` : `已停用「${item.dictLabel}」`)
    await loadData()
  } catch (error) {
    toast.error('切换状态失败：' + errMsg(error, '网络异常'))
  }
}

function getColorStatus(color?: string) {
  const map: Record<string, string> = {
    default: 'default',
    success: 'success',
    warning: 'warning',
    danger: 'danger',
    info: 'info',
    primary: 'primary',
  }
  return (map[color || 'default'] || 'default') as any
}

// 生命周期
onMounted(loadData)
</script>

<template>
  <div class="dict-manage">
    <!-- 工具栏 -->
    <CCard>
      <div class="toolbar">
        <div class="toolbar__left">
          <CSelect
            v-model="selectedCategory"
            :options="categoryOptions"
            placeholder="选择分类"
            width="200px"
          />
        </div>
        <div class="toolbar__right">
          <CButton variant="ghost" @click="router.push('/admin/dictionary')">
            <CIcon name="layers" :size="16" />
            字典库查阅
          </CButton>
          <CButton variant="primary" @click="openCreateForm">
            <CIcon name="plus" :size="16" />
            新增字典项
          </CButton>
        </div>
      </div>
    </CCard>

    <!-- 字典列表 -->
    <CCard padding="none">
      <template #header>
        <div class="list-title">
          <h3 class="card__title">字典项列表</h3>
          <span class="list-stats">
            共 {{ stats.total }} 项 ·
            <em class="st-on">启用 {{ stats.enabled }}</em> ·
            <em class="st-off">停用 {{ stats.disabled }}</em>
          </span>
        </div>
      </template>
      <div v-if="loading" class="loading">加载中...</div>
      <div v-else-if="filteredDictionaries.length === 0" class="empty">
        <CIcon name="box" :size="48" />
        <p>暂无字典项</p>
      </div>
      <div v-else class="dict-list">
        <div
          v-for="item in filteredDictionaries"
          :key="item.id"
          class="dict-item"
          :class="{ 'is-disabled': item.enabled === false }"
        >
          <div class="dict-item__main">
            <div class="dict-item__header">
              <span class="dict-item__category">{{ item.category }}</span>
              <span class="dict-item__code">{{ item.dictCode }}</span>
              <CStatusPill :status="getColorStatus(item.dictColor)" dot>
                {{ item.dictLabel }}
              </CStatusPill>
            </div>
            <div class="dict-item__meta">
              <span>值: {{ item.dictValue }}</span>
              <span v-if="item.dictIcon">图标: {{ item.dictIcon }}</span>
              <span>排序: {{ item.sortOrder }}</span>
              <CStatusPill :status="item.enabled ? 'success' : 'default'" size="sm">
                {{ item.enabled ? '已启用' : '已禁用' }}
              </CStatusPill>
            </div>
            <div v-if="item.description" class="dict-item__desc">
              {{ item.description }}
            </div>
          </div>
          <div class="dict-item__actions">
            <CButton variant="text" size="sm" @click="openEditForm(item)">
              <CIcon name="edit" :size="14" />
              编辑
            </CButton>
            <CButton
              variant="text"
              size="sm"
              :class="{ danger: item.enabled !== false }"
              @click="toggleEnabled(item)"
            >
              <CIcon :name="(item.enabled !== false ? 'eye-off' : 'eye') as any" :size="14" />
              {{ item.enabled !== false ? '停用' : '启用' }}
            </CButton>
          </div>
        </div>
      </div>
    </CCard>

    <!-- 表单弹窗 -->
    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <CCard :title="editingItem ? '编辑字典项' : '新增字典项'" class="modal">
        <div class="form">
          <div class="form-row">
            <CInput
              v-model="form.category"
              label="分类"
              :disabled="!!editingItem"
              placeholder="如 CUSTOMER_SOURCE（大写英文下划线，可新建分类）"
            />
            <span v-if="editingItem" class="form-hint">分类为业务键，创建后不可修改</span>
          </div>
          <div class="form-row">
            <CInput v-model="form.dictCode" label="字典编码" placeholder="如：SOURCE" />
          </div>
          <div class="form-row">
            <CInput v-model="form.dictValue" label="字典值" placeholder="如：WALK_IN" />
          </div>
          <div class="form-row">
            <CInput v-model="form.dictLabel" label="显示标签" placeholder="如：到店" />
          </div>
          <div class="form-row">
            <CSelect
              :model-value="form.dictColor ?? 'default'"
              @update:model-value="form.dictColor = $event"
              :options="colorOptions"
              label="颜色"
            />
          </div>
          <div class="form-row">
            <CInput :model-value="form.dictIcon ?? ''" @update:model-value="form.dictIcon = $event" label="图标" placeholder="可选" />
          </div>
          <div class="form-row">
            <CInput
              :model-value="String(form.sortOrder ?? 0)"
              @update:model-value="form.sortOrder = Number($event)"
              type="number"
              label="排序"
              placeholder="数字越小越靠前"
            />
          </div>
          <div class="form-row">
            <label class="checkbox">
              <input type="checkbox" v-model="form.enabled" />
              <span>启用</span>
            </label>
          </div>
          <div class="form-row">
            <CInput
              v-model="form.description"
              label="描述"
              placeholder="可选"
            />
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showForm = false">取消</CButton>
          <CButton variant="primary" :disabled="saving" @click="saveForm">
            {{ saving ? '保存中…' : '保存' }}
          </CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.dict-manage {
  display: flex;
  flex-direction: column;
  gap: var(--s-md);
}

.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.loading, .empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: var(--s-xl);
  color: var(--c-text-tertiary);
  gap: var(--s-sm);
}

.dict-list {
  display: flex;
  flex-direction: column;
}

.dict-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: var(--s-md);
  border-bottom: 1px solid var(--c-border);
  transition: background 0.2s;
}

.dict-item:hover {
  background: var(--c-bg-secondary);
}

.dict-item:last-child {
  border-bottom: none;
}

.dict-item__main {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: var(--s-xs);
}

.dict-item__header {
  display: flex;
  align-items: center;
  gap: var(--s-sm);
}

.dict-item__category {
  font-size: var(--t-xs);
  color: var(--c-text-secondary);
  padding: 2px 8px;
  background: var(--c-bg-tertiary);
  border-radius: var(--r-sm);
}

.dict-item__code {
  font-size: var(--t-xs);
  color: var(--c-text-tertiary);
  font-family: var(--f-mono);
}

.dict-item__meta {
  display: flex;
  align-items: center;
  gap: var(--s-md);
  font-size: var(--t-xs);
  color: var(--c-text-secondary);
}

.dict-item__desc {
  font-size: var(--t-xs);
  color: var(--c-text-tertiary);
}

.dict-item__actions {
  display: flex;
  gap: var(--s-xs);
}

.dict-item__actions .danger {
  color: var(--c-danger);
}

.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2000;
}

.modal {
  width: 500px;
  max-width: 90vw;
  max-height: 90vh;
  overflow-y: auto;
}

.form {
  display: flex;
  flex-direction: column;
  gap: var(--s-md);
}

.form-row {
  display: flex;
  flex-direction: column;
  gap: var(--s-xs);
}

.checkbox {
  display: flex;
  align-items: center;
  gap: var(--s-xs);
  cursor: pointer;
}

.list-title {
  display: flex;
  align-items: baseline;
  gap: var(--s-md);
}

.list-stats {
  font-size: var(--t-xs);
  font-weight: 400;
  color: var(--c-text-tertiary);
}

.list-stats .st-on { color: var(--c-success, #16a34a); font-style: normal; }
.list-stats .st-off { color: var(--c-text-tertiary); font-style: normal; }

.dict-item.is-disabled {
  opacity: 0.55;
  background: var(--c-bg-secondary);
}

.form-hint {
  font-size: var(--t-xs);
  color: var(--c-text-tertiary);
}
</style>
