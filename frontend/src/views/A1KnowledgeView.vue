<script setup lang="ts">
/* ============================================================
 * A1-09 AI 知识库 /ai/knowledge — 词法检索 / 引用溯源
 * B47 卡7 去 mock：知识条目/统计/热搜/检索/引用反馈全部真实落库
 * 浏览（列表/筛选）不产生引用；显式检索（回车/按钮/点热搜）才写 citation
 * 语义向量（EMBEDDING）为远期能力，当前为 PG ILIKE 加权词法检索
 * ============================================================ */
import { computed, ref, watch, onMounted } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CKpi from '@/components/CKpi.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CTable from '@/components/CTable.vue'
import CSegmented from '@/components/CSegmented.vue'
import CDrawer from '@/components/CDrawer.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CTextarea from '@/components/CTextarea.vue'
import { useToast } from '@/composables/useToast'
import { errMsg } from '@/stores/m5Coupon'
import { fmtDateTimeSec } from '@/utils/datetime'
import {
  listKnowledgeDocs,
  getKnowledgeStats,
  getKnowledgeDoc,
  getKnowledgeHot,
  searchKnowledge,
  getKnowledgeCitations,
  createKnowledge,
  updateKnowledge,
  reindexKnowledge,
  feedbackCitation,
  type KnowledgeDoc,
  type KnowledgeStats,
  type KnowledgeCitation,
} from '@/api/ai'

const toast = useToast()

type Category = 'project' | 'script' | 'compliance'
type PillStatus = 'default' | 'primary' | 'success' | 'warning' | 'danger' | 'info' | 'disabled' | 'draft'

const tab = ref('all')
const tabOptions = [
  { label: '全部', value: 'all' }, { label: '项目知识', value: 'project' },
  { label: '方案话术', value: 'script' }, { label: '法规合规', value: 'compliance' },
]
const cols = [
  { key: 'title', label: '标题' }, { key: 'category', label: '分类', width: '100' },
  { key: 'indexStatus', label: '索引', width: '100' }, { key: 'refsCount', label: '引用次数', width: '90', align: 'right' as const },
  { key: 'updated', label: '更新时间', width: '170' }, { key: 'ops', label: '操作', width: '180' },
]

const stats = ref<KnowledgeStats>({
  totalDocs: 0, indexedCount: 0, pendingCount: 0, failedCount: 0, indexedPct: 0,
  totalRefs: 0, todaySearches: 0, feedbackTotal: 0, usefulRatePct: null,
})
const docs = ref<KnowledgeDoc[]>([])
const loading = ref(false)
const hotWords = ref<string[]>([])
const searchMode = ref(false)
const lastQuery = ref('')
const keyword = ref('')

const kpis = computed(() => [
  { label: '知识条目', icon: 'dashboard', value: String(stats.value.totalDocs), tone: 'purple' as const },
  { label: '索引完成', icon: 'settings', value: `${stats.value.indexedPct ?? 0}%`, tone: 'success' as const },
  { label: '累计引用', icon: 'trend-up', value: String(stats.value.totalRefs), tone: 'brand' as const },
  { label: '今日检索', icon: 'search', value: String(stats.value.todaySearches), tone: 'teal' as const },
])

async function loadStats() {
  try {
    stats.value = await getKnowledgeStats()
  } catch (e) {
    toast.error('统计加载失败：' + errMsg(e))
  }
}

async function loadList() {
  loading.value = true
  searchMode.value = false
  try {
    const res = await listKnowledgeDocs({ category: tab.value === 'all' ? undefined : tab.value, page: 0, size: 200 })
    docs.value = res.content
  } catch (e) {
    toast.error('知识库加载失败：' + errMsg(e))
  } finally {
    loading.value = false
  }
}

watch(tab, () => {
  if (!searchMode.value) void loadList()
})

async function runSearch(word?: string) {
  const q = (word ?? keyword.value).trim()
  if (!q) {
    toast.info('请输入检索关键词')
    return
  }
  keyword.value = q
  loading.value = true
  try {
    const hits = await searchKnowledge(q, 10)
    docs.value = hits.map((h): KnowledgeDoc => ({
      docId: h.docId,
      title: h.title,
      category: h.category,
      content: h.snippet,
      tags: h.tags,
      source: null,
      indexStatus: 'INDEXED',
      indexNote: null,
      refsCount: h.refsCount,
      staffId: null,
      staffName: null,
      storeCode: null,
      createdAt: null,
      updatedAt: null,
    }))
    searchMode.value = true
    lastQuery.value = q
    toast.success(`检索完成，命中 ${hits.length} 条；本次引用已真实记录`)
    void loadStats()
  } catch (e) {
    toast.error('检索失败：' + errMsg(e))
  } finally {
    loading.value = false
  }
}

function onSearchEnter() {
  void runSearch()
}
function pickHot(w: string) {
  void runSearch(w)
}
function clearSearch() {
  keyword.value = ''
  lastQuery.value = ''
  void loadList()
}

function idxPill(v: string | null): { s: PillStatus; t: string } {
  if (v === 'INDEXED') return { s: 'success', t: '已索引' }
  if (v === 'PENDING') return { s: 'primary', t: '待索引' }
  return { s: 'danger', t: '索引失败' }
}
function catLabel(c: string | null): string {
  return ({ project: '项目', script: '话术', compliance: '法规' } as Record<string, string>)[c ?? ''] || (c ?? '—')
}
function tagList(tags: string | null): string[] {
  return (tags ?? '').split(/[、,，\s]+/).map((s) => s.trim()).filter(Boolean)
}

/* ---------- 录入 / 编辑抽屉 ---------- */
const showForm = ref(false)
const editingId = ref<number | null>(null)
const saving = ref(false)
const CAT_OPTIONS = [
  { value: 'project', label: '项目知识' },
  { value: 'script', label: '方案话术' },
  { value: 'compliance', label: '法规合规' },
]
function emptyForm() {
  return { title: '', category: 'project' as Category, tags: '', content: '', source: '' }
}
const form = ref(emptyForm())
const canSave = computed(() => !!form.value.title.trim() && !!form.value.content.trim() && !saving.value)

function openCreate() {
  editingId.value = null
  form.value = emptyForm()
  showForm.value = true
}
// CTable 行插槽类型为 Record<string, unknown>，此处做收窄
function openEditSlot(row: Record<string, unknown>) { openEdit(row as unknown as KnowledgeDoc) }
function openTraceSlot(row: Record<string, unknown>) { openTrace(row as unknown as KnowledgeDoc) }
function reindexSlot(row: Record<string, unknown>) { void retryIndex(row as unknown as KnowledgeDoc) }
function openEdit(row: KnowledgeDoc) {
  editingId.value = row.docId
  form.value = {
    title: row.title,
    category: ((row.category as Category) || 'project'),
    tags: row.tags ?? '',
    content: row.content,
    source: row.source ?? '',
  }
  showForm.value = true
}
async function saveForm() {
  if (!canSave.value) return
  const cmd = {
    title: form.value.title.trim(),
    category: form.value.category,
    content: form.value.content.trim(),
    tags: form.value.tags.trim() || null,
    source: form.value.source.trim() || null,
  }
  saving.value = true
  try {
    if (editingId.value === null) {
      await createKnowledge(cmd)
      toast.success('知识已录入并建立词法检索索引')
    } else {
      await updateKnowledge(editingId.value, cmd)
      toast.success('知识已更新，索引已同步')
    }
    showForm.value = false
    await loadList()
    await loadStats()
  } catch (e) {
    toast.error('保存失败：' + errMsg(e))
  } finally {
    saving.value = false
  }
}
async function retryIndex(row: KnowledgeDoc) {
  try {
    await reindexKnowledge(row.docId)
    toast.success(`已重试索引：${row.title}`)
    await loadList()
    await loadStats()
  } catch (e) {
    toast.error('重试索引失败：' + errMsg(e))
  }
}

/* ---------- 溯源抽屉 ---------- */
const showTrace = ref(false)
const traceDoc = ref<KnowledgeDoc | null>(null)
const traceLoading = ref(false)
const citations = ref<KnowledgeCitation[]>([])
const feedbackBusy = ref<number | null>(null)

const SOURCE_FEATURE_LABEL: Record<string, string> = {
  manual_search: '人工检索',
  scripts: '智能话术',
  chatbot: '智能客服',
  content: '内容生成',
}
function sourceLabel(f: string | null): string {
  return SOURCE_FEATURE_LABEL[f ?? ''] || (f ?? '—')
}
function usefulText(u: boolean | null): string {
  if (u === true) return '有用'
  if (u === false) return '无用'
  return '未反馈'
}

async function openTrace(row: KnowledgeDoc) {
  showTrace.value = true
  traceDoc.value = { ...row }
  traceLoading.value = true
  citations.value = []
  try {
    const [doc, citePage] = await Promise.all([
      getKnowledgeDoc(row.docId),
      getKnowledgeCitations(row.docId, 0, 8),
    ])
    traceDoc.value = doc
    citations.value = citePage.content
  } catch (e) {
    toast.error('溯源信息加载失败：' + errMsg(e))
  } finally {
    traceLoading.value = false
  }
}
async function giveFeedback(c: KnowledgeCitation, useful: boolean) {
  if (c.useful === useful || feedbackBusy.value !== null) return
  feedbackBusy.value = c.citationId
  try {
    const updated = await feedbackCitation(c.citationId, useful)
    const idx = citations.value.findIndex((x) => x.citationId === c.citationId)
    if (idx >= 0) citations.value[idx] = updated
    toast.success(useful ? '已标记为有用' : '已标记为无用')
    void loadStats()
  } catch (e) {
    toast.error('反馈失败：' + errMsg(e))
  } finally {
    feedbackBusy.value = null
  }
}

onMounted(() => {
  void loadStats()
  void loadList()
  getKnowledgeHot()
    .then((w) => { hotWords.value = w })
    .catch(() => { hotWords.value = [] })
})
</script>

<template>
  <div class="a1-kb">
    <div class="kpis">
      <CKpi v-for="k in kpis" :key="k.label" :value="k.value" :label="k.label" :tone="k.tone" :icon="k.icon" />
    </div>

    <CCard padding="lg">
      <div class="kb-tools">
        <CSegmented v-model="tab" :options="tabOptions" />
        <div class="kb-tools__right">
          <div class="search">
            <CIcon name="search" :size="16" />
            <input v-model="keyword" placeholder="检索标题/正文/标签，回车显式检索..." @keyup.enter="onSearchEnter" />
          </div>
          <CButton variant="primary" :disabled="loading" @click="onSearchEnter">
            <CIcon name="search" :size="14" />检索
          </CButton>
          <CButton variant="primary" @click="openCreate">
            <CIcon name="plus" :size="14" />录入知识
          </CButton>
        </div>
      </div>
      <div v-if="hotWords.length && !searchMode" class="hot">
        热搜：
        <span v-for="w in hotWords" :key="w" class="hot__tag" @click="pickHot(w)">{{ w }}</span>
      </div>
      <div v-if="searchMode" class="search-banner">
        <CIcon name="search" :size="14" />
        <span>检索结果：<b>{{ lastQuery }}</b>（共 {{ docs.length }} 条命中，每次命中均写入真实引用流水）</span>
        <CButton size="sm" variant="text" @click="clearSearch">返回浏览</CButton>
      </div>
      <CTable :columns="cols" :rows="docs" row-key="docId" stripe :empty-text="loading ? '加载中…' : '暂无知识条目，点击右上角「录入知识」补充'">
        <template #col-category="{ value }">{{ catLabel(value as string | null) }}</template>
        <template #col-indexStatus="{ value }">
          <CStatusPill :status="idxPill(value as string | null).s" dot>{{ idxPill(value as string | null).t }}</CStatusPill>
        </template>
        <template #col-refsCount="{ value }">{{ value as number }}</template>
        <template #col-updated="{ row }">{{ fmtDateTimeSec((row as unknown as KnowledgeDoc).updatedAt) }}</template>
        <template #col-ops="{ row }">
          <CButton size="sm" variant="text" @click="openTraceSlot(row)">溯源</CButton>
          <CButton size="sm" variant="text" @click="openEditSlot(row)">编辑</CButton>
          <CButton
            v-if="(row as unknown as KnowledgeDoc).indexStatus === 'FAILED'"
            size="sm"
            variant="text"
            @click="reindexSlot(row)"
          >重试索引</CButton>
        </template>
      </CTable>
    </CCard>

    <p class="hint">当前为 PG 词法加权检索（INDEXED 即可被检索）；语义向量（EMBEDDING）为远期能力。引用流水仅来自本页真实检索，可溯源到审计日志。</p>

    <!-- 录入/编辑抽屉 -->
    <CDrawer v-model:show="showForm" :title="editingId === null ? '录入知识' : '编辑知识'" size="md">
      <div class="form">
        <div class="form__row">
          <label class="form__label">知识标题 <span class="req">*</span></label>
          <CInput v-model="form.title" placeholder="如：水光针治疗适应人群与禁忌" />
        </div>
        <div class="form__row">
          <label class="form__label">归属分类</label>
          <CSelect v-model="form.category" :options="CAT_OPTIONS" width="100%" />
        </div>
        <div class="form__row">
          <label class="form__label">正文内容 <span class="req">*</span></label>
          <CTextarea v-model="form.content" placeholder="填写知识正文，用于 AI 词法检索与话术生成引用" />
        </div>
        <div class="form__row">
          <label class="form__label">检索标签</label>
          <CInput v-model="form.tags" placeholder="多个标签用「、」分隔，如：水光针、禁忌" />
        </div>
        <div class="form__row">
          <label class="form__label">来源出处</label>
          <CInput v-model="form.source" placeholder="如：院内 SOP-2026-03 / 厂家操作手册" />
        </div>
        <p class="form__tip">保存后立即建立词法检索索引（INDEXED 即可被检索）；语义向量 EMBEDDING 为远期能力。</p>
      </div>
      <template #footer>
        <div class="drawer__foot">
          <CButton variant="ghost" @click="showForm = false">取消</CButton>
          <CButton variant="primary" :disabled="!canSave" @click="saveForm">
            {{ editingId === null ? '录入知识' : '保存修改' }}
          </CButton>
        </div>
      </template>
    </CDrawer>

    <!-- 溯源抽屉 -->
    <CDrawer v-model:show="showTrace" title="引用溯源" size="sm">
      <div v-if="traceDoc" class="trace">
        <div class="trace__title">{{ traceDoc.title }}</div>
        <div class="trace__meta">
          <CStatusPill :status="idxPill(traceDoc.indexStatus).s" dot>{{ idxPill(traceDoc.indexStatus).t }}</CStatusPill>
          <span>累计引用 {{ traceDoc.refsCount }} 次</span>
          <span>来源：{{ traceDoc.source || '—' }}</span>
        </div>
        <div v-if="traceDoc.staffName" class="trace__meta">录入人：{{ traceDoc.staffName }} · 更新于 {{ fmtDateTimeSec(traceDoc.updatedAt) }}</div>
        <div v-if="traceDoc.indexNote" class="trace__note">索引备注：{{ traceDoc.indexNote }}</div>
        <div class="trace__tags">
          <span v-for="t in tagList(traceDoc.tags)" :key="t" class="trace__tag">{{ t }}</span>
        </div>
        <p class="trace__content">{{ traceDoc.content }}</p>
        <div class="trace__list">
          <div class="trace__sub">最近引用记录（真实检索流水）</div>
          <p v-if="traceLoading" class="trace__empty">加载中…</p>
          <template v-else>
            <div v-for="c in citations" :key="c.citationId" class="trace__item">
              <span class="trace__dot" />
              <div class="trace__body">
                <div class="trace__by">
                  {{ sourceLabel(c.sourceFeature) }} · 检索词「{{ c.query }}」
                </div>
                <div class="trace__scene">
                  {{ c.staffName || '未知操作人' }} · {{ fmtDateTimeSec(c.createdAt) }}
                  <CStatusPill
                    :status="c.useful === null ? 'draft' : (c.useful ? 'success' : 'default')"
                    dot
                  >{{ usefulText(c.useful) }}</CStatusPill>
                </div>
                <div class="trace__fb">
                  <CButton
                    size="sm"
                    :variant="c.useful === true ? 'primary' : 'ghost'"
                    :disabled="feedbackBusy === c.citationId"
                    @click="giveFeedback(c, true)"
                  >有用</CButton>
                  <CButton
                    size="sm"
                    :variant="c.useful === false ? 'primary' : 'ghost'"
                    :disabled="feedbackBusy === c.citationId"
                    @click="giveFeedback(c, false)"
                  >无用</CButton>
                </div>
              </div>
            </div>
            <p v-if="!citations.length" class="trace__empty">该条目暂无真实引用（浏览不产生引用，显式检索才记录）。</p>
          </template>
        </div>
      </div>
      <template #footer>
        <div class="drawer__foot">
          <CButton variant="primary" @click="showTrace = false">关闭</CButton>
        </div>
      </template>
    </CDrawer>
  </div>
</template>

<style scoped>
.a1-kb { display: flex; flex-direction: column; gap: var(--s-lg); }
.kpis { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
.kb-tools { display: flex; align-items: center; gap: var(--s-sm); flex-wrap: nowrap; overflow-x: auto; }
.kb-tools__right { display: flex; align-items: center; gap: var(--s-sm); margin-left: auto; flex-shrink: 0; }
.search { width: 240px; flex-shrink: 0; display: flex; align-items: center; gap: var(--s-sm); border: 1px solid var(--c-border); border-radius: var(--r-md); padding: 0 var(--s-sm); color: var(--c-text-3); }
.search input { flex: 1; min-width: 0; border: none; outline: none; font-size: var(--t-sm); padding: var(--s-sm) 0; background: transparent; color: var(--c-text); }
.kb-tools__right .cbtn { flex-shrink: 0; white-space: nowrap; }
.hot { margin: var(--s-md) 0 0; display: flex; flex-wrap: wrap; gap: var(--s-xs); align-items: center; font-size: var(--t-xs); color: var(--c-text-3); }
.hot__tag { padding: 2px 10px; background: var(--c-purple-soft); color: var(--c-purple); border-radius: var(--r-pill); cursor: pointer; }
.hot__tag:hover { background: var(--c-purple); color: #fff; }
.search-banner { margin: var(--s-md) 0 0; display: flex; align-items: center; gap: var(--s-sm); font-size: var(--t-sm); color: var(--c-text-2); background: var(--c-purple-soft); border-radius: var(--r-md); padding: var(--s-sm) var(--s-md); }
.search-banner b { color: var(--c-purple); }
.search-banner .cbtn { margin-left: auto; }
.hint { font-size: var(--t-xs); color: var(--c-text-3); margin: 0; }

.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.form__label { font-size: var(--t-xs); color: var(--c-text-3); }
.req { color: var(--c-danger-fg); }
.form__tip { margin: 0; font-size: var(--t-xs); color: var(--c-text-3); line-height: var(--lh-sm); background: var(--c-bg-page); border-radius: var(--r-md); padding: var(--s-sm) var(--s-md); }
.drawer__foot { display: flex; justify-content: flex-end; gap: var(--s-sm); }

.trace { display: flex; flex-direction: column; gap: var(--s-md); }
.trace__title { font-size: var(--t-md); font-weight: 700; color: var(--c-text); }
.trace__meta { display: flex; align-items: center; gap: var(--s-sm); flex-wrap: wrap; font-size: var(--t-xs); color: var(--c-text-3); }
.trace__note { margin: 0; font-size: var(--t-xs); color: var(--c-warning-fg, var(--c-danger-fg)); }
.trace__tags { display: flex; gap: var(--s-xs); flex-wrap: wrap; }
.trace__tag { padding: 2px 10px; background: var(--c-purple-soft); color: var(--c-purple); border-radius: var(--r-pill); font-size: var(--t-xs); }
.trace__content { margin: 0; font-size: var(--t-sm); color: var(--c-text-2); line-height: var(--lh-md); background: var(--c-bg-page); border-radius: var(--r-md); padding: var(--s-md); }
.trace__list { border-top: 1px dashed var(--c-border); padding-top: var(--s-md); display: flex; flex-direction: column; gap: var(--s-sm); }
.trace__sub { font-size: var(--t-xs); color: var(--c-text-3); }
.trace__item { display: flex; gap: var(--s-sm); align-items: flex-start; }
.trace__dot { width: 8px; height: 8px; border-radius: var(--r-pill); background: var(--c-brand); margin-top: 6px; flex-shrink: 0; }
.trace__body { display: flex; flex-direction: column; gap: 2px; }
.trace__by { font-size: var(--t-sm); color: var(--c-text); }
.trace__scene { display: flex; align-items: center; gap: var(--s-xs); font-size: var(--t-xs); color: var(--c-text-3); }
.trace__fb { display: flex; gap: var(--s-xs); margin-top: 2px; }
.trace__empty { margin: 0; font-size: var(--t-xs); color: var(--c-text-3); }

@media (max-width: 1024px) {
  .kpis { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); }
}
</style>
