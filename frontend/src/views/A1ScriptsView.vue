<script setup lang="ts">
/* ============================================================
 * A1-06 智能话术（验收页）
 * 路由：/ai/scripts
 * 验收：可插入 M4-09 咨询工作台；所有话术经 A1-04 敏感词过滤
 * ============================================================ */
import { ref, computed } from 'vue'
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

const scene = ref('all')
const keyword = ref('')

const sceneOptions = [
  { label: '全部', value: 'all' },
  { label: '破冰', value: 'icebreak' },
  { label: '升单', value: 'upsell' },
  { label: '异议处理', value: 'objection' },
]

const kpis = computed(() => [
  { label: '话术总数', icon: 'chat', value: '286', tone: 'purple' as const, trend: '+12', trendUp: true, trendGood: true },
  { label: '今日调用', icon: 'settings', value: '1,240', tone: 'brand' as const, trend: '+8.4%', trendUp: true, trendGood: true },
  { label: '采纳率', icon: 'trend-up', value: '68.4%', tone: 'teal' as const, trend: '+2.1pp', trendUp: true, trendGood: true },
  { label: '好评率', icon: 'trend-up', value: '82%', tone: 'success' as const, trend: '+1.6pp', trendUp: true, trendGood: true },
])

interface ScriptCard {
  id: number
  scene: 'icebreak' | 'upsell' | 'objection'
  title: string
  content: string
  rating: number
  adopted: number
}

const scripts = ref<ScriptCard[]>([
  { id: 1, scene: 'icebreak', title: '新客到店欢迎话术', content: '您好，欢迎光临！我是您今天的专属顾问小晴。之前有没有了解过我们门店？我先带您参观一下环境，再根据您的需求做一个免费的皮肤检测，您看可以吗？', rating: 5, adopted: 326 },
  { id: 2, scene: 'icebreak', title: '老客回访破冰', content: '张姐，好久不见呀！上次您做的水光护理已经过了一个月了，最近皮肤状态怎么样？这周我们新到了一款修复面膜，想邀请您回来体验一下，顺便帮您做个免费复查。', rating: 4, adopted: 218 },
  { id: 3, scene: 'upsell', title: '疗程升单推荐', content: '李姐，从您这次的检测结果看，单次护理虽然能改善表层问题，但配合 3 次一个小疗程效果会更稳定。我们这个月有疗程 8 折活动，算下来单次比原价省 280 元，而且效果能持续 3 个月以上。', rating: 5, adopted: 412 },
  { id: 4, scene: 'upsell', title: '会员卡升单', content: '王姐，看您这半年来店里已经消费了 6 次，如果办理我们的钻石卡，今天这次就能直接打 7 折，全年还能享受 4 次免费项目和生日专属礼遇，非常适合您这种高频到店的客户。', rating: 4, adopted: 186 },
  { id: 5, scene: 'objection', title: '价格异议处理', content: '我特别理解您对价格的考虑。其实我们的项目用的都是进口仪器和院线产品，单次折合下来比自己在家用护肤品更划算，而且有专业老师全程跟踪。您要不先体验一次小疗程，感受一下效果再决定？', rating: 5, adopted: 284 },
  { id: 6, scene: 'objection', title: '效果质疑应对', content: '您有这样的担心很正常。我们这个项目已经有 2000+ 位客户体验过，满意度 96%，而且签约承诺 28 天无明显改善可退款。我可以给您看一些同肤质客户的前后对比，您参考一下。', rating: 4, adopted: 172 },
  { id: 7, scene: 'upsell', title: '项目搭配推荐', content: '陈姐，您这次做的补水项目效果很好，但如果搭配一次深层清洁，后续营养吸收会提升 40%。两个项目一起做还能享受组合价，比分开做省 380 元，建议您今天一起体验。', rating: 5, adopted: 251 },
  { id: 8, scene: 'objection', title: '时间冲突异议', content: '理解您平时比较忙。我们门店营业到晚上 9 点，周末也正常开放，而且这个疗程每次只要 40 分钟。我可以帮您把三次预约都排在您方便的时段，提前一天会再提醒您，不会耽误您太多时间。', rating: 4, adopted: 138 },
  { id: 9, scene: 'icebreak', title: '电话预约破冰', content: '您好，是刘女士吗？我是 XX 美业的顾问小晴。看到您在小程序上预约了明天下午 3 点的补水护理，提前跟您确认一下时间，并提醒您到店前可以先不要化妆，方便我们做皮肤检测哦。', rating: 5, adopted: 198 },
])

const sceneLabel: Record<ScriptCard['scene'], { text: string; status: 'primary' | 'success' | 'warning' }> = {
  icebreak: { text: '破冰', status: 'primary' },
  upsell: { text: '升单', status: 'success' },
  objection: { text: '异议处理', status: 'warning' },
}

const filtered = computed(() => {
  return scripts.value.filter((s) => {
    const matchScene = scene.value === 'all' || s.scene === scene.value
    const kw = keyword.value.trim()
    const matchKw = !kw || s.title.includes(kw) || s.content.includes(kw)
    return matchScene && matchKw
  })
})

function stars(rating: number) {
  return '★'.repeat(rating) + '☆'.repeat(5 - rating)
}

const toast = useToast()

function insertScript(card: ScriptCard) {
  card.adopted += 1
  toast.success(`已插入咨询工作台：${card.title}`)
}

function feedback(card: ScriptCard) {
  toast.info(`已反馈「${card.title}」，我们将持续优化该话术`)
}

/* ---------- 新增 / 编辑话术抽屉 ---------- */
const showForm = ref(false)
const editingId = ref<number | null>(null)
const SCENE_OPTIONS = [
  { value: 'icebreak', label: '破冰' },
  { value: 'upsell', label: '升单' },
  { value: 'objection', label: '异议处理' },
]
function emptyForm() {
  return { title: '', scene: 'icebreak' as ScriptCard['scene'], content: '' }
}
const form = ref(emptyForm())
const canSave = computed(() => form.value.title.trim() && form.value.content.trim())

function openCreate() {
  editingId.value = null
  form.value = emptyForm()
  showForm.value = true
}
function openEdit(card: ScriptCard) {
  editingId.value = card.id
  form.value = { title: card.title, scene: card.scene, content: card.content }
  showForm.value = true
}
function saveForm() {
  if (!canSave.value) return
  if (editingId.value === null) {
    const id = Math.max(0, ...scripts.value.map((s) => s.id)) + 1
    scripts.value.unshift({
      id,
      scene: form.value.scene,
      title: form.value.title.trim(),
      content: form.value.content.trim(),
      rating: 5,
      adopted: 0,
    })
    toast.success('话术已新增，经敏感词过滤后即可使用')
  } else {
    const card = scripts.value.find((s) => s.id === editingId.value)
    if (card) {
      card.title = form.value.title.trim()
      card.scene = form.value.scene
      card.content = form.value.content.trim()
    }
    toast.success('话术已更新')
  }
  showForm.value = false
}
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
            <CSegmented v-model="scene" :options="sceneOptions" size="sm" />
            <div class="head-search">
              <CIcon name="search" :size="14" />
              <CInput v-model="keyword" placeholder="搜索话术标题或内容" />
            </div>
            <CButton variant="primary" @click="openCreate">
              <CIcon name="plus" :size="14" />新增话术
            </CButton>
          </div>
        </div>
      </template>

      <div class="script-grid">
        <article v-for="card in filtered" :key="card.id" class="script-card">
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
              采纳 {{ card.adopted }}
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

      <div v-if="!filtered.length" class="empty">未找到匹配的话术</div>
    </CCard>

    <div class="compliance-bar">
      <CIcon name="shield" :size="16" />
      <span>所有话术均已经过 A1-04 敏感词过滤，合规可直接使用</span>
    </div>

    <!-- 新增 / 编辑话术抽屉 -->
    <CDrawer v-model:show="showForm" :title="editingId === null ? '新增话术' : '编辑话术'" size="md">
      <div class="form">
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
          <CButton variant="primary" :disabled="!canSave" @click="saveForm">
            {{ editingId === null ? '新增话术' : '保存修改' }}
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
