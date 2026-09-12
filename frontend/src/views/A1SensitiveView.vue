<script setup lang="ts">
/* ============================================================
 * A1-04 敏感词检测（红线页）
 * 路由：/ai/sensitive
 * 真实链路：AI 中心六功能出站前敏感词校验，命中即落 ai_sensitive_hit 留痕并 400 拦截；
 * 误报标注回流词库治理（B46 卡1 去 mock）。
 * ============================================================ */
import { computed, onMounted, reactive, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CKpi from '@/components/CKpi.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CTable from '@/components/CTable.vue'
import CSegmented from '@/components/CSegmented.vue'
import CButton from '@/components/CButton.vue'
import CIcon from '@/components/CIcon.vue'
import CSelect from '@/components/CSelect.vue'
import CInput from '@/components/CInput.vue'
import CDrawer from '@/components/CDrawer.vue'
import CPagination from '@/components/CPagination.vue'
import { useAuthStore } from '@/stores/auth'
import { useToast } from '@/composables/useToast'
import { errMsg } from '@/stores/m5Coupon'
import { fmtDateTimeSec, fmtDateTime } from '@/utils/datetime'
import {
  listSensitiveWords, createSensitiveWord, updateSensitiveWord,
  listSensitiveHits, getSensitiveHitStats, markSensitiveHitFalsePositive,
  type SensitiveHitView, type SensitiveWordView, type SensitiveHitStats,
} from '@/api/ai'

const auth = useAuthStore()
const toast = useToast()
const canEdit = computed(() => auth.can('aiAdmin:edit'))

const tab = ref<'hit' | 'dict'>('hit')

const tabOptions = [
  { label: '命中记录', value: 'hit' },
  { label: '词库管理', value: 'dict' },
]

const stats = ref<SensitiveHitStats>({ todayHits: 0, totalHits: 0, falsePositiveHits: 0, totalWords: 0, enabledWords: 0 })

const kpis = computed(() => [
  { label: '今日命中', icon: 'calendar', value: String(stats.value.todayHits), tone: 'danger' as const },
  { label: '累计命中', icon: 'alert', value: String(stats.value.totalHits), tone: 'blue' as const },
  { label: '误报标注', icon: 'alert', value: String(stats.value.falsePositiveHits), tone: 'teal' as const },
  { label: '词库规模', icon: 'settings', value: `${stats.value.totalWords} 词（启用 ${stats.value.enabledWords}）`, tone: 'purple' as const },
])

// 命中记录
const hitRows = ref<SensitiveHitView[]>([])
const hitLoading = ref(false)
const hitPage = ref(1)
const hitPageSize = ref(20)
const hitTotal = ref(0)
const catFilter = ref('')

const catFilterOptions = [
  { label: '全部词类', value: '' },
  { label: '违禁内容（BANNED）', value: 'BANNED' },
  { label: '越权提示词（INJECTION）', value: 'INJECTION' },
]

const hitColumns = [
  { key: 'hitAt', label: '时间', width: '150' },
  { key: 'featureName', label: '功能来源', width: '110' },
  { key: 'word', label: '命中词', width: '140' },
  { key: 'contextSnippet', label: '上下文摘要' },
  { key: 'action', label: '处置', width: '100' },
  { key: 'op', label: '操作', width: '110', align: 'center' as const },
]

function catName(c: string) {
  return c === 'INJECTION' ? '越权提示词' : '违禁内容'
}

function catPill(c: string) {
  return c === 'INJECTION' ? 'warning' as const : 'danger' as const
}

function fmtHitTime(s: string | null) {
  return fmtDateTimeSec(s)
}

async function loadHits() {
  hitLoading.value = true
  try {
    const res = await listSensitiveHits({
      category: catFilter.value || undefined,
      page: hitPage.value - 1,
      size: hitPageSize.value,
    })
    hitRows.value = res.content
    hitTotal.value = res.totalElements
  } catch (e) {
    toast.error('命中记录加载失败：' + errMsg(e))
  } finally {
    hitLoading.value = false
  }
}

async function loadStats() {
  try {
    stats.value = await getSensitiveHitStats()
  } catch (e) {
    toast.error('统计加载失败：' + errMsg(e))
  }
}

function changeCatFilter(v: string) {
  catFilter.value = v
  hitPage.value = 1
  loadHits()
}

function changeHitPage(n: number) {
  hitPage.value = n
  loadHits()
}

async function markFalsePositive(row: Record<string, any>) {
  const r = row as SensitiveHitView
  if (r.falsePositive) return
  try {
    await markSensitiveHitFalsePositive(r.hitId)
    toast.success(`已将「${r.word}」标注为误报`)
    await Promise.all([loadHits(), loadStats()])
  } catch (e) {
    toast.error('误报标注失败：' + errMsg(e))
  }
}

// 词库管理
const dictRows = ref<SensitiveWordView[]>([])
const dictLoaded = ref(false)
const dictLoading = ref(false)

const dictColumns = [
  { key: 'word', label: '敏感词', width: '160' },
  { key: 'category', label: '词类', width: '120' },
  { key: 'hits', label: '命中次数', width: '110', align: 'right' as const },
  { key: 'enabled', label: '状态', width: '100' },
  { key: 'updatedAt', label: '最近更新', width: '160' },
  { key: 'op', label: '操作', width: '100', align: 'center' as const },
]

async function loadWords() {
  dictLoading.value = true
  try {
    dictRows.value = await listSensitiveWords()
    dictLoaded.value = true
  } catch (e) {
    toast.error('词库加载失败：' + errMsg(e))
  } finally {
    dictLoading.value = false
  }
}

function switchTab(v: string) {
  const t = v as 'hit' | 'dict'
  tab.value = t
  if (t === 'dict' && !dictLoaded.value) loadWords()
}

// 新增 / 编辑抽屉
const drawerShow = ref(false)
const drawerSaving = ref(false)
const editingId = ref<number | null>(null)
const form = reactive({ word: '', category: 'BANNED', enabled: 'true' })

const categoryOptions = [
  { label: '违禁内容（BANNED）', value: 'BANNED' },
  { label: '越权提示词（INJECTION）', value: 'INJECTION' },
]
const enabledOptions = [
  { label: '启用', value: 'true' },
  { label: '停用', value: 'false' },
]

const drawerTitle = computed(() => (editingId.value == null ? '新增敏感词' : '编辑敏感词'))
const wordError = computed(() => form.word.trim().length === 0 || form.word.trim().length > 128)

function addWord() {
  editingId.value = null
  form.word = ''
  form.category = 'BANNED'
  form.enabled = 'true'
  drawerShow.value = true
}

function editWord(row: Record<string, any>) {
  const r = row as SensitiveWordView
  editingId.value = r.wordId
  form.word = r.word
  form.category = r.category
  form.enabled = r.enabled ? 'true' : 'false'
  drawerShow.value = true
}

async function submitWord() {
  const word = form.word.trim()
  if (!word) {
    toast.warning('请填写敏感词内容')
    return
  }
  if (word.length > 128) {
    toast.warning('敏感词内容不能超过 128 字')
    return
  }
  const cmd = { word, category: form.category, enabled: form.enabled === 'true' }
  drawerSaving.value = true
  try {
    const res = editingId.value == null
      ? await createSensitiveWord(cmd)
      : await updateSensitiveWord(editingId.value, cmd)
    if (!res.changed) {
      toast.warning('内容无变化，未保存')
    } else {
      toast.success(editingId.value == null ? '敏感词已新增' : '敏感词已更新')
    }
    drawerShow.value = false
    await Promise.all([loadWords(), loadStats()])
  } catch (e) {
    toast.error('保存失败：' + errMsg(e))
  } finally {
    drawerSaving.value = false
  }
}

onMounted(() => {
  loadStats()
  loadHits()
})
</script>

<template>
  <div class="a1-sensitive">
    <div class="a1-sensitive__kpis">
      <CKpi v-for="k in kpis" :key="k.label" v-bind="k" />
    </div>

    <CCard padding="none">
      <template #header>
        <div class="card-head">
          <div class="card-head__title">
            <CIcon name="alert" :size="18" />
            <h3>敏感词实时检测</h3>
          </div>
          <div class="card-head__right">
            <CSelect
              v-if="tab === 'hit'"
              :model-value="catFilter"
              :options="catFilterOptions"
              width="190px"
              @update:model-value="changeCatFilter"
            />
            <CSegmented :model-value="tab" :options="tabOptions" size="sm" @update:model-value="switchTab" />
            <CButton v-if="tab === 'dict' && canEdit" size="sm" variant="primary" @click="addWord">
              <CIcon name="plus" :size="14" />
              新增词
            </CButton>
          </div>
        </div>
      </template>

      <!-- 命中记录 -->
      <div v-if="tab === 'hit'" class="tab-pane">
        <CTable
          :columns="hitColumns"
          :rows="hitRows"
          row-key="hitId"
          :empty-text="hitLoading ? '加载中…' : '暂无命中记录'"
        >
          <template #col-hitAt="{ value }">
            {{ fmtHitTime(value) }}
          </template>
          <template #col-featureName="{ row }">
            <CStatusPill status="info" dot>{{ row.featureName || '—' }}</CStatusPill>
          </template>
          <template #col-word="{ row }">
            <span class="word-hit">{{ row.word }}</span>
            <span class="word-cat">{{ catName(row.category) }}</span>
          </template>
          <template #col-contextSnippet="{ value }">
            <span class="ctx">{{ value || '—' }}</span>
          </template>
          <template #col-action>
            <CStatusPill status="danger" dot>拦截</CStatusPill>
          </template>
          <template #col-op="{ row }">
            <CButton
              v-if="canEdit && !row.falsePositive"
              size="sm"
              variant="text"
              @click="markFalsePositive(row)"
            >
              误报标注
            </CButton>
            <span v-else-if="row.falsePositive" class="fp-done">已标误报</span>
            <span v-else class="fp-done">—</span>
          </template>
        </CTable>
        <CPagination :page="hitPage" :page-size="hitPageSize" :total="hitTotal" @update:page="changeHitPage" />
      </div>

      <!-- 词库管理 -->
      <div v-else class="tab-pane">
        <CTable
          :columns="dictColumns"
          :rows="dictRows"
          row-key="wordId"
          :empty-text="dictLoading ? '加载中…' : '词库为空，点击右上角新增'"
        >
          <template #col-word="{ value }">
            <span class="word-dict">{{ value }}</span>
          </template>
          <template #col-category="{ value }">
            <CStatusPill :status="catPill(value)" dot>{{ catName(value) }}</CStatusPill>
          </template>
          <template #col-hits="{ value }">
            <span class="hits-num">{{ value.toLocaleString() }}</span>
          </template>
          <template #col-enabled="{ value }">
            <CStatusPill :status="value ? 'success' : 'disabled'" dot>
              {{ value ? '启用' : '停用' }}
            </CStatusPill>
          </template>
          <template #col-updatedAt="{ value }">{{ fmtDateTime(value, true) }}</template>
          <template #col-op="{ row }">
            <CButton v-if="canEdit" size="sm" variant="text" @click="editWord(row)">
              <CIcon name="edit" :size="13" />
              编辑
            </CButton>
            <span v-else>—</span>
          </template>
        </CTable>
      </div>
    </CCard>

    <!-- 新增/编辑抽屉 -->
    <CDrawer :show="drawerShow" size="md" :title="drawerTitle" @update:show="drawerShow = $event">
      <div class="word-form">
        <CInput
          v-model="form.word"
          label="敏感词内容"
          placeholder="请输入需拦截的词或短语（128 字内）"
          :error="wordError && form.word.trim().length > 0"
        />
        <div class="wf-row">
          <label class="fld-label">词类</label>
          <CSelect v-model="form.category" :options="categoryOptions" width="100%" />
        </div>
        <div class="wf-row">
          <label class="fld-label">状态</label>
          <CSelect v-model="form.enabled" :options="enabledOptions" width="100%" />
        </div>
      </div>
      <template #footer>
        <CButton variant="secondary" @click="drawerShow = false">取消</CButton>
        <CButton variant="primary" :disabled="drawerSaving || wordError" @click="submitWord">
          {{ drawerSaving ? '保存中…' : '保存' }}
        </CButton>
      </template>
    </CDrawer>

    <!-- 红线提示条 -->
    <div class="redline-bar">
      <CIcon name="shield" :size="16" />
      <span class="redline-bar__text">
        敏感词实时拦截已接入 AI 中心六大功能出站链路（客户画像 / 流失预警 / 智能话术 / 智能排班 / 内容生成 / 审批评估），命中即在 LLM 调用前拦截并留痕，误报标注可回流词库治理
      </span>
    </div>
  </div>
</template>

<style scoped>
.a1-sensitive {
  display: flex;
  flex-direction: column;
  gap: var(--s-lg);
}
.a1-sensitive__kpis {
  display: grid;
  grid-auto-flow: column;
  grid-auto-columns: 1fr;
  gap: var(--s-md);
}
@media (max-width: 1024px) {
  .a1-sensitive__kpis { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); }
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  gap: var(--s-sm);
  flex-wrap: wrap;
}
.card-head__right { display: flex; align-items: center; gap: var(--s-sm); flex-wrap: wrap; }
.card-head__title {
  display: flex;
  align-items: center;
  gap: var(--s-sm);
  color: var(--c-danger-fg);
}
.card-head__title h3 {
  margin: 0;
  font-size: var(--t-md);
  font-weight: 600;
  color: var(--c-text);
}
.card-head__right {
  display: flex;
  align-items: center;
  gap: var(--s-md);
}

.tab-pane {
  padding: 0;
}

.word-hit {
  font-weight: 600;
  color: var(--c-danger-fg);
  background: var(--c-danger-bg);
  padding: 2px 8px;
  border-radius: var(--r-sm);
  font-size: var(--t-xs);
}
.word-cat {
  display: block;
  margin-top: 4px;
  font-size: var(--t-xs);
  color: var(--c-text-3);
}
.word-dict {
  font-weight: 600;
  color: var(--c-text);
}
.ctx {
  color: var(--c-text-2);
  line-height: 1.5;
}
.hits-num {
  font-variant-numeric: tabular-nums;
  color: var(--c-text);
  font-weight: 500;
}
.fp-done {
  font-size: var(--t-xs);
  color: var(--c-text-3);
}
.word-form {
  display: flex;
  flex-direction: column;
  gap: var(--s-md);
}
.wf-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.fld-label {
  font-size: 13px;
  color: var(--c-text);
  line-height: 18px;
}

.redline-bar {
  display: flex;
  align-items: center;
  gap: var(--s-sm);
  padding: var(--s-sm) var(--s-md);
  background: var(--c-danger-bg);
  border: 1px solid var(--c-danger-fg);
  border-radius: var(--r-md);
  color: var(--c-danger-fg);
}
.redline-bar__text {
  font-size: var(--t-xs);
  line-height: 1.5;
  font-weight: 500;
}
</style>
