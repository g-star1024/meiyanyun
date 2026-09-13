<script setup lang="ts">
/* ============================================================
 * A1-06 智能话术（验收页）
 * 路由：/ai/scripts
 * 验收：可插入 M4-09 咨询工作台；所有话术经 A1-04 敏感词过滤
 * B46 卡4 去 mock：话术库/统计真实落库，抽屉内 AI 生成走 scripts invoke 全治理链
 * ============================================================ */
import { ref, computed, onMounted } from 'vue'
import CCard from '@/components/CCard.vue'
import CKpi from '@/components/CKpi.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CSegmented from '@/components/CSegmented.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CIcon from '@/components/CIcon.vue'
import CDrawer from '@/components/CDrawer.vue'
import CSelect from '@/components/CSelect.vue'
import CTextarea from '@/components/CTextarea.vue'
import { useToast } from '@/composables/useToast'
import { errMsg } from '@/stores/m5Coupon'
import {
  listScripts, getScriptStats, generateScript, createScript, updateScript,
  adoptScript, feedbackScript,
  type ScriptView, type ScriptStats, type ScriptScene,
} from '@/api/ai'

const toast = useToast()

const scene = ref('all')
const keyword = ref('')

const sceneOptions = [
  { label: '全部', value: 'all' },
  { label: '破冰', value: 'icebreak' },
  { label: '升单', value: 'upsell' },
  { label: '异议处理', value: 'objection' },
]

const stats = ref<ScriptStats>({ totalScripts: 0, todayCalls: 0, adoptRatePct: 0, goodRatePct: 0 })

const kpis = computed(() => [
  { label: '话术总数', icon: 'chat', value: String(stats.value.totalScripts), tone: 'purple' as const },
  { label: '今日调用', icon: 'settings', value: stats.value.todayCalls.toLocaleString(), tone: 'brand' as const },
  { label: '采纳率', icon: 'trend-up', value: `${stats.value.adoptRatePct}%`, tone: 'teal' as const },
  { label: '好评率', icon: 'trend-up', value: `${stats.value.goodRatePct}%`, tone: 'success' as const },
])

const scripts = ref<ScriptView[]>([])
const loading = ref(false)

const sceneLabel: Record<ScriptScene, { text: string; status: 'primary' | 'success' | 'warning' }> = {
  icebreak: { text: '破冰', status: 'primary' },
  upsell: { text: '升单', status: 'success' },
  objection: { text: '异议处理', status: 'warning' },
}

function stars(rating: number) {
  return '★'.repeat(rating) + '☆'.repeat(5 - rating)
}

async function loadStats() {
  try {
    stats.value = await getScriptStats()
  } catch (e) {
    toast.error('统计加载失败：' + errMsg(e))
  }
}

let kwTimer: ReturnType<typeof setTimeout> | null = null
async function loadList() {
  loading.value = true
  try {
    const res = await listScripts({
      scene: scene.value === 'all' ? undefined : scene.value,
      keyword: keyword.value.trim() || undefined,
      page: 0,
      size: 200,
    })
    scripts.value = res.content
  } catch (e) {
    toast.error('话术库加载失败：' + errMsg(e))
  } finally {
    loading.value = false
  }
}

function switchScene() {
  loadList()
}
function onKeyword() {
  if (kwTimer) clearTimeout(kwTimer)
  kwTimer = setTimeout(loadList, 300)
}

async function insertScript(card: ScriptView) {
  try {
    const res = await adoptScript(card.scriptId)
    card.adoptedCount = res.adoptedCount
    toast.success(`已插入咨询工作台：${card.title}`)
    loadStats()
  } catch (e) {
    toast.error('插入咨询工作台失败：' + errMsg(e))
  }
}

async function feedback(card: ScriptView) {
  try {
    const res = await feedbackScript(card.scriptId)
    card.feedbackCount = res.feedbackCount
    toast.info(`已反馈「${card.title}」，我们将持续优化该话术`)
  } catch (e) {
    toast.error('反馈失败：' + errMsg(e))
  }
}

/* ---------- 新增 / 编辑话术抽屉 ---------- */
const showForm = ref(false)
const editingId = ref<number | null>(null)
const saving = ref(false)
const generating = ref(false)
const genTopic = ref('')
const genLogId = ref<number | null>(null)
const genModelCode = ref<string | null>(null)
const SCENE_OPTIONS = [
  { value: 'icebreak', label: '破冰' },
  { value: 'upsell', label: '升单' },
  { value: 'objection', label: '异议处理' },
]
function emptyForm() {
  return { title: '', scene: 'icebreak' as ScriptScene, content: '' }
}
const form = ref(emptyForm())
const canSave = computed(() => form.value.title.trim() && form.value.content.trim())

function openCreate() {
  editingId.value = null
  form.value = emptyForm()
  genTopic.value = ''
  genLogId.value = null
  genModelCode.value = null
  showForm.value = true
}
function openEdit(card: ScriptView) {
  editingId.value = card.scriptId
  form.value = { title: card.title, scene: card.scene, content: card.content }
  genTopic.value = ''
  genLogId.value = null
  genModelCode.value = null
  showForm.value = true
}

async function aiGenerate() {
  const topic = genTopic.value.trim()
  if (!topic) {
    toast.warning('请先填写想生成的话术主题，如「新客到店欢迎」')
    return
  }
  generating.value = true
  try {
    const v = await generateScript({ scene: form.value.scene, topic })
    form.value.content = v.content
    if (!form.value.title.trim()) form.value.title = topic.slice(0, 40)
    genLogId.value = v.invokeLogId
    genModelCode.value = v.modelCode
    toast.success('AI 话术已生成，可编辑后保存入库')
  } catch (e) {
    toast.error('AI 生成失败：' + errMsg(e))
  } finally {
    generating.value = false
  }
}

async function saveForm() {
  if (!canSave.value || saving.value) return
  saving.value = true
  const cmd = {
    scene: form.value.scene,
    title: form.value.title.trim(),
    content: form.value.content.trim(),
    invokeLogId: editingId.value === null ? genLogId.value : null,
    modelCode: editingId.value === null ? genModelCode.value : null,
  }
  try {
    if (editingId.value === null) {
      await createScript(cmd)
      toast.success('话术已新增，经敏感词过滤后即可使用')
    } else {
      await updateScript(editingId.value, cmd)
      toast.success('话术已更新')
    }
    showForm.value = false
    await Promise.all([loadList(), loadStats()])
  } catch (e) {
    toast.error('保存失败：' + errMsg(e))
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  loadStats()
  loadList()
})
</script>

<template>
  <div class="a1-scripts">
    <div class="a1-scripts__kpis">
      <CKpi v-for="k in kpis" :key="k.label" v-bind="k" />
    </div>

    <CCard padding="lg">
      <template #header>
        <div class="card-head">
          <div class="card-head__title">
            <CIcon name="chat" :size="18" />
            <h3>智能话术库</h3>
          </div>
          <div class="card-head__right">
            <CSegmented v-model="scene" :options="sceneOptions" size="sm" @update:model-value="switchScene" />
            <div class="head-search">
              <CIcon name="search" :size="14" />
              <CInput v-model="keyword" placeholder="搜索话术标题或内容" @update:model-value="onKeyword" />
            </div>
            <CButton variant="primary" @click="openCreate">
              <CIcon name="plus" :size="14" />新增话术
            </CButton>
          </div>
        </div>
      </template>

      <div class="script-grid">
        <article v-for="card in scripts" :key="card.scriptId" class="script-card">
          <div class="script-card__head">
            <CStatusPill :status="sceneLabel[card.scene].status" dot>
              {{ sceneLabel[card.scene].text }}
            </CStatusPill>
            <span class="script-card__rating" :title="`${card.rating} 星`">{{ stars(card.rating) }}</span>
          </div>
          <h4 class="script-card__title">{{ card.title }}</h4>
          <p class="script-card__content">{{ card.content }}</p>
          <div class="script-card__meta">
            <span class="meta-item">
              <CIcon name="check-square" :size="13" />
              采纳 {{ card.adoptedCount }}
            </span>
          </div>
          <div class="script-card__foot">
            <CButton size="sm" variant="primary" @click="insertScript(card)">
              <CIcon name="upload" :size="13" />
              插入咨询工作台
            </CButton>
            <CButton size="sm" variant="text" @click="feedback(card)">反馈</CButton>
            <CButton size="sm" variant="text" @click="openEdit(card)">编辑</CButton>
          </div>
        </article>
      </div>

      <div v-if="!scripts.length" class="empty">{{ loading ? '加载中…' : '未找到匹配的话术' }}</div>
    </CCard>

    <div class="compliance-bar">
      <CIcon name="shield" :size="16" />
      <span>所有话术均已经过 A1-04 敏感词过滤，合规可直接使用</span>
    </div>

    <!-- 新增 / 编辑话术抽屉 -->
    <CDrawer v-model:show="showForm" :title="editingId === null ? '新增话术' : '编辑话术'" size="md">
      <div class="form">
        <div v-if="editingId === null" class="form__row">
          <label class="form__label">AI 生成主题</label>
          <div class="card-head__right">
            <CInput v-model="genTopic" placeholder="如：新客到店欢迎、疗程升单推荐" @keyup.enter="aiGenerate" />
            <CButton variant="ghost" :disabled="generating" @click="aiGenerate">
              <CIcon name="refresh" :size="14" />{{ generating ? 'AI 生成中…' : 'AI 生成话术' }}
            </CButton>
          </div>
        </div>
        <div class="form__row">
          <label class="form__label">话术标题 <span class="req">*</span></label>
          <CInput v-model="form.title" placeholder="如：新客到店欢迎话术" />
        </div>
        <div class="form__row">
          <label class="form__label">适用场景</label>
          <CSelect v-model="form.scene" :options="SCENE_OPTIONS" width="100%" />
        </div>
        <div class="form__row">
          <label class="form__label">话术内容 <span class="req">*</span></label>
          <CTextarea v-model="form.content" placeholder="填写完整话术内容，插入工作台后可直接使用" />
        </div>
        <p class="form__tip">新增话术将自动经过 A1-04 敏感词过滤，存在违规表述时会提示修改后再保存。</p>
      </div>
      <template #footer>
        <div class="drawer__foot">
          <CButton variant="ghost" @click="showForm = false">取消</CButton>
          <CButton variant="primary" :disabled="!canSave || saving" @click="saveForm">
            {{ editingId === null ? (saving ? '新增中…' : '新增话术') : (saving ? '保存中…' : '保存修改') }}
          </CButton>
        </div>
      </template>
    </CDrawer>
  </div>
</template>

<style scoped>
.a1-scripts {
  display: flex;
  flex-direction: column;
  gap: var(--s-lg);
}
.a1-scripts__kpis {
  display: grid;
  grid-auto-flow: column;
  grid-auto-columns: 1fr;
  gap: var(--s-md);
}
.card-head { display: flex; justify-content: space-between; align-items: center; width: 100%; gap: var(--s-md); flex-wrap: wrap; }
.card-head__title { display: flex; align-items: center; gap: var(--s-sm); }
.card-head__right { display: flex; align-items: center; gap: var(--s-sm); flex-wrap: wrap; }
.head-search { width: 220px; display: flex; align-items: center; gap: var(--s-sm); color: var(--c-text-3); }
.head-search :deep(.cinput) { flex: 1; }
.head-search :deep(.cinput__field) { height: 32px; font-size: var(--t-sm); }
.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.form__label { font-size: var(--t-xs); color: var(--c-text-3); }
.req { color: var(--c-danger-fg); }
.form__tip { margin: 0; font-size: var(--t-xs); color: var(--c-text-3); line-height: var(--lh-sm); background: var(--c-bg-page); border-radius: var(--r-md); padding: var(--s-sm) var(--s-md); }
.drawer__foot { display: flex; justify-content: flex-end; gap: var(--s-sm); }

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  gap: var(--s-md);
}
.card-head__title {
  display: flex;
  align-items: center;
  gap: var(--s-sm);
  color: var(--c-purple);
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
/* 工具行搜索框：CInput 内部已带图标位，外层只负责撑满 */
.toolbar__search :deep(.cinput__field) {
  height: 32px;
  font-size: var(--t-sm);
}

.script-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--s-md);
}

.script-card {
  display: flex;
  flex-direction: column;
  gap: var(--s-sm);
  padding: var(--s-md);
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: var(--r-lg);
  transition: border-color 0.15s, box-shadow 0.15s;
}
.script-card:hover {
  border-color: var(--c-brand-border);
  box-shadow: 0 2px 8px rgba(255, 107, 157, 0.12);
}
.script-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.script-card__rating {
  color: var(--c-gold);
  font-size: var(--t-md);
  letter-spacing: 1px;
}
.script-card__title {
  margin: 0;
  font-size: var(--t-base);
  font-weight: 600;
  color: var(--c-text);
  line-height: 1.4;
}
.script-card__content {
  margin: 0;
  font-size: var(--t-xs);
  color: var(--c-text-2);
  line-height: 1.6;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  min-height: 48px;
}
.script-card__meta {
  display: flex;
  gap: var(--s-md);
  font-size: var(--t-xs);
  color: var(--c-text-3);
}
.meta-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.script-card__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: var(--s-sm);
  border-top: 1px solid var(--c-border-light);
}

.empty {
  text-align: center;
  padding: var(--s-xxl);
  color: var(--c-text-3);
  font-size: var(--t-sm);
}

.compliance-bar {
  display: flex;
  align-items: center;
  gap: var(--s-sm);
  padding: var(--s-sm) var(--s-md);
  background: var(--c-success-bg);
  border: 1px solid var(--c-success-fg);
  border-radius: var(--r-md);
  color: var(--c-success-fg);
  font-size: var(--t-xs);
  font-weight: 500;
}
</style>
