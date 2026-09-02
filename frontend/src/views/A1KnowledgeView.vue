<script setup lang="ts">
/* A1-11 知识库 /ai/knowledge — 检索/向量化/溯源 */
import { computed, ref } from 'vue'
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

type Category = 'project' | 'script' | 'compliance'
interface KbRow {
  id: number
  title: string
  category: Category
  vector: 'done' | 'processing' | 'failed'
  refs: number
  updated: string
  content: string
  tags: string[]
  source: string
}

const tab = ref('all')
const tabOptions = [
  { label: '全部', value: 'all' }, { label: '项目知识', value: 'project' },
  { label: '方案话术', value: 'script' }, { label: '法规合规', value: 'compliance' },
]
const hotWords = ['水光针', '热玛吉', '光子嫩肤', '过敏处理', '退卡政策', '术后护理', '会员卡', '投诉处理']
const cols = [
  { key: 'title', label: '标题' }, { key: 'category', label: '分类', width: '100' },
  { key: 'vector', label: '向量化', width: '100' }, { key: 'refs', label: '引用次数', width: '90', align: 'right' as const },
  { key: 'updated', label: '更新时间', width: '140' }, { key: 'ops', label: '操作', width: '140' },
]
const rows = ref<KbRow[]>([
  { id: 1, title: '水光针治疗适应人群与禁忌', category: 'project', vector: 'done', refs: 286, updated: '2026-08-20', content: '水光针适用于皮肤干燥、细纹、肤色暗沉人群；禁忌包括孕期、哺乳期、活动性皮肤病、凝血功能障碍、对透明质酸过敏者。', tags: ['水光针', '禁忌'], source: '院内 SOP-2026-03' },
  { id: 2, title: '光子嫩肤术后护理指南', category: 'project', vector: 'done', refs: 234, updated: '2026-08-18', content: '术后 24 小时避免热水洗脸，72 小时内禁用含酸类护肤品，严格防晒 SPF30+，补水修复面膜每日一次连用 5 天。', tags: ['光子嫩肤', '术后护理'], source: '护理部规范 v2.1' },
  { id: 3, title: '客户异议处理话术集', category: 'script', vector: 'done', refs: 412, updated: '2026-08-22', content: '价格异议：先认同感受，再拆解单次成本与疗效周期，最后给出可选方案；效果异议：引用同类案例与临床数据。', tags: ['异议处理', '价格'], source: '咨询部培训材料' },
  { id: 4, title: '升单推荐标准话术', category: 'script', vector: 'done', refs: 368, updated: '2026-08-21', content: '基于客户诉求点引入联合治疗方案，强调疗程化效果与单次差异，避免强推，给出二选一而非是否购买。', tags: ['升单', '联合治疗'], source: '咨询部培训材料' },
  { id: 5, title: '医疗美容服务管理办法', category: 'compliance', vector: 'done', refs: 89, updated: '2026-07-30', content: '医疗美容服务实行主诊医师负责制，医疗美容项目必须由具有相应资质的卫生技术人员实施，禁止超范围执业。', tags: ['法规', '执业资质'], source: '国家卫健委令' },
  { id: 6, title: '消费者权益保护法（医美章节）', category: 'compliance', vector: 'processing', refs: 0, updated: '2026-08-25', content: '经营者应当向消费者提供真实、全面的服务信息，不得作虚假或者引人误解的宣传；预付式消费需明确退费规则。', tags: ['法规', '消费者权益'], source: '市场监管总局' },
  { id: 7, title: '热玛吉治疗参数与效果说明', category: 'project', vector: 'done', refs: 178, updated: '2026-08-15', content: '面部治疗采用 4.0 探头，能量等级依据耐受度 3-5 级，单次 900 发；效果在 3-6 个月逐步显现，维持 1-2 年。', tags: ['热玛吉', '参数'], source: '厂家操作手册' },
  { id: 8, title: '退款纠纷处理流程', category: 'compliance', vector: 'failed', refs: 45, updated: '2026-08-10', content: '客户提出退款后 24 小时内由原咨询师首访，48 小时内店长介入，协商不成转客诉工单并同步法务。', tags: ['退款', '纠纷'], source: '运营手册 5.2' },
])

const keyword = ref('')
const filtered = computed(() => {
  const list = tab.value === 'all' ? rows.value : rows.value.filter((r) => r.category === tab.value)
  const q = keyword.value.trim()
  if (!q) return list
  return list.filter((r) => r.title.includes(q) || r.tags.some((t) => t.includes(q)))
})

const kpis = computed(() => {
  const total = rows.value.length
  const done = rows.value.filter((r) => r.vector === 'done').length
  const rate = total ? Math.round((done / total) * 100) : 0
  const refs = rows.value.reduce((s, r) => s + r.refs, 0)
  return [
    { label: '知识条目', icon: 'dashboard', value: String(total), tone: 'purple' as const },
    { label: '向量化完成', icon: 'settings', value: `${rate}%`, tone: 'success' as const },
    { label: '累计引用', icon: 'trend-up', value: String(refs), tone: 'brand' as const },
    { label: '引用准确率', icon: 'check', value: '96%', tone: 'teal' as const },
  ]
})

function vecPill(v: string) {
  if (v === 'done') return { s: 'success' as const, t: '已完成' }
  if (v === 'processing') return { s: 'primary' as const, t: '处理中' }
  return { s: 'danger' as const, t: '失败' }
}
function catLabel(c: string) { return ({ project: '项目', script: '话术', compliance: '法规' } as Record<string, string>)[c] || c }

/* ---------- 录入 / 编辑抽屉 ---------- */
const showForm = ref(false)
const editingId = ref<number | null>(null)
const CAT_OPTIONS = [
  { value: 'project', label: '项目知识' },
  { value: 'script', label: '方案话术' },
  { value: 'compliance', label: '法规合规' },
]
function emptyForm() {
  return { title: '', category: 'project' as Category, tags: '', content: '', source: '' }
}
const form = ref(emptyForm())
const canSave = computed(() => form.value.title.trim() && form.value.content.trim())

function openCreate() {
  editingId.value = null
  form.value = emptyForm()
  showForm.value = true
}
// CTable 行插槽类型为 Record<string, any>，此处做收窄
function openEditSlot(row: Record<string, unknown>) { openEdit(row as unknown as KbRow) }
function openTraceSlot(row: Record<string, unknown>) { openTrace(row as unknown as KbRow) }
function openEdit(row: KbRow) {
  editingId.value = row.id
  form.value = {
    title: row.title,
    category: row.category,
    tags: row.tags.join('、'),
    content: row.content,
    source: row.source,
  }
  showForm.value = true
}
function today() { return new Date().toISOString().slice(0, 10) }
function saveForm() {
  if (!canSave.value) return
  const tags = form.value.tags.split(/[、,，\s]+/).map((s) => s.trim()).filter(Boolean)
  if (editingId.value === null) {
    const id = Math.max(0, ...rows.value.map((r) => r.id)) + 1
    rows.value.unshift({
      id,
      title: form.value.title.trim(),
      category: form.value.category,
      vector: 'processing',
      refs: 0,
      updated: today(),
      content: form.value.content.trim(),
      tags,
      source: form.value.source.trim() || '人工录入',
    })
  } else {
    const row = rows.value.find((r) => r.id === editingId.value)
    if (row) {
      row.title = form.value.title.trim()
      row.category = form.value.category
      row.tags = tags
      row.content = form.value.content.trim()
      row.source = form.value.source.trim() || row.source
      row.updated = today()
      row.vector = 'processing'
    }
  }
  showForm.value = false
}

/* ---------- 溯源抽屉 ---------- */
const showTrace = ref(false)
const traceRow = ref<KbRow | null>(null)
function openTrace(row: KbRow) {
  traceRow.value = row
  showTrace.value = true
}
const traceLogs = computed(() => {
  const r = traceRow.value
  if (!r) return []
  return [
    { by: 'A1-06 销售话术', scene: '咨询师推荐话术生成', time: '2026-08-26 10:24' },
    { by: 'A1-07 智能客服', scene: '客户在线咨询应答', time: '2026-08-26 09:41' },
    { by: 'A1-10 内容生成', scene: '小红书种草文案', time: '2026-08-25 16:08' },
    { by: 'T1-04 审计日志', scene: '知识条目调阅记录', time: '2026-08-25 11:30' },
  ].slice(0, r.refs > 0 ? 4 : 2)
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
            <input v-model="keyword" placeholder="搜索标题或标签..." />
          </div>
          <CButton variant="primary" @click="openCreate">
            <CIcon name="plus" :size="14" />录入知识
          </CButton>
        </div>
      </div>
      <div class="hot">
        热搜：
        <span v-for="w in hotWords" :key="w" class="hot__tag" @click="keyword = w">{{ w }}</span>
      </div>
      <CTable :columns="cols" :rows="filtered" row-key="id" stripe>
        <template #col-category="{ value }">{{ catLabel(value) }}</template>
        <template #col-vector="{ value }"><CStatusPill :status="vecPill(value).s" dot>{{ vecPill(value).t }}</CStatusPill></template>
        <template #col-ops="{ row }">
          <CButton size="sm" variant="text" @click="openTraceSlot(row)">溯源</CButton>
          <CButton size="sm" variant="text" @click="openEditSlot(row)">编辑</CButton>
        </template>
      </CTable>
      <p v-if="!filtered.length" class="empty">暂无匹配的知识条目，换个关键词或点击右上角「录入知识」补充。</p>
    </CCard>

    <p class="hint">知识库为 A1-06 话术 / A1-07 客服 / A1-10 内容生成提供检索支撑，引用可溯源至 T1-04 审计。</p>

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
          <CTextarea v-model="form.content" placeholder="填写知识正文，用于 AI 检索与话术生成引用" />
        </div>
        <div class="form__row">
          <label class="form__label">检索标签</label>
          <CInput v-model="form.tags" placeholder="多个标签用「、」分隔，如：水光针、禁忌" />
        </div>
        <div class="form__row">
          <label class="form__label">来源出处</label>
          <CInput v-model="form.source" placeholder="如：院内 SOP-2026-03 / 厂家操作手册" />
        </div>
        <p class="form__tip">保存后自动进入向量化处理，处理完成前不可被 AI 检索引用。</p>
      </div>
      <template #footer>
        <div class="drawer__foot">
          <CButton variant="ghost" @click="showForm = false">取消</CButton>
          <CButton variant="primary" :disabled="!canSave" @click="saveForm">
            {{ editingId === null ? '录入并向量化' : '保存修改' }}
          </CButton>
        </div>
      </template>
    </CDrawer>

    <!-- 溯源抽屉 -->
    <CDrawer v-model:show="showTrace" title="引用溯源" size="sm">
      <div v-if="traceRow" class="trace">
        <div class="trace__title">{{ traceRow.title }}</div>
        <div class="trace__meta">
          <CStatusPill :status="vecPill(traceRow.vector).s" dot>{{ vecPill(traceRow.vector).t }}</CStatusPill>
          <span>累计引用 {{ traceRow.refs }} 次</span>
          <span>来源：{{ traceRow.source }}</span>
        </div>
        <div class="trace__tags">
          <span v-for="t in traceRow.tags" :key="t" class="trace__tag">{{ t }}</span>
        </div>
        <p class="trace__content">{{ traceRow.content }}</p>
        <div class="trace__list">
          <div class="trace__sub">最近引用记录</div>
          <div v-for="l in traceLogs" :key="l.by + l.time" class="trace__item">
            <span class="trace__dot" />
            <div>
              <div class="trace__by">{{ l.by }}</div>
              <div class="trace__scene">{{ l.scene }} · {{ l.time }}</div>
            </div>
          </div>
          <p v-if="!traceRow.refs" class="trace__empty">该条目尚未被引用（向量化未完成时不可检索）。</p>
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
.search { width: 220px; flex-shrink: 0; display: flex; align-items: center; gap: var(--s-sm); border: 1px solid var(--c-border); border-radius: var(--r-md); padding: 0 var(--s-sm); color: var(--c-text-3); }
.search input { flex: 1; min-width: 0; border: none; outline: none; font-size: var(--t-sm); padding: var(--s-sm) 0; background: transparent; color: var(--c-text); }
.kb-tools__right .cbtn { flex-shrink: 0; white-space: nowrap; }
.hot { margin: var(--s-md) 0; display: flex; flex-wrap: wrap; gap: var(--s-xs); align-items: center; font-size: var(--t-xs); color: var(--c-text-3); }
.hot__tag { padding: 2px 10px; background: var(--c-purple-soft); color: var(--c-purple); border-radius: var(--r-pill); cursor: pointer; }
.hot__tag:hover { background: var(--c-purple); color: #fff; }
.empty { margin: var(--s-lg) 0 0; text-align: center; font-size: var(--t-sm); color: var(--c-text-3); }
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
.trace__tags { display: flex; gap: var(--s-xs); flex-wrap: wrap; }
.trace__tag { padding: 2px 10px; background: var(--c-purple-soft); color: var(--c-purple); border-radius: var(--r-pill); font-size: var(--t-xs); }
.trace__content { margin: 0; font-size: var(--t-sm); color: var(--c-text-2); line-height: var(--lh-md); background: var(--c-bg-page); border-radius: var(--r-md); padding: var(--s-md); }
.trace__list { border-top: 1px dashed var(--c-border); padding-top: var(--s-md); display: flex; flex-direction: column; gap: var(--s-sm); }
.trace__sub { font-size: var(--t-xs); color: var(--c-text-3); }
.trace__item { display: flex; gap: var(--s-sm); align-items: flex-start; }
.trace__dot { width: 8px; height: 8px; border-radius: var(--r-pill); background: var(--c-brand); margin-top: 6px; flex-shrink: 0; }
.trace__by { font-size: var(--t-sm); color: var(--c-text); }
.trace__scene { font-size: var(--t-xs); color: var(--c-text-3); }
.trace__empty { margin: 0; font-size: var(--t-xs); color: var(--c-text-3); }

@media (max-width: 1024px) {
  .kpis { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); }
}
</style>
