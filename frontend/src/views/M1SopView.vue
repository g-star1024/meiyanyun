<template>
  <div class="sop">
    <div class="sop__kpis">
      <CKpi :value="String(sop.published.length)" label="已发布 SOP" tone="brand" icon="check-square" />
      <CKpi :value="String(sop.taskStats.inProgress)" label="执行中任务" tone="warning" icon="check-square" />
      <CKpi :value="String(sop.taskStats.done)" label="已完成任务" tone="success" icon="check-square" />
      <CKpi :value="String(sop.taskStats.overdue)" label="逾期任务" tone="danger" icon="alert" />
      <CKpi :value="sop.completionRate + '%'" label="完成率" tone="blue" icon="trend-up" />
    </div>

    <div class="sop__tabs">
      <button v-for="t in tabs" :key="t.key" :class="{ 'is-active': tab === t.key }" @click="tab = t.key">{{ t.label }}</button>
    </div>

    <div class="sop__body">
      <!-- 模板库 -->
      <template v-if="tab === 'template'">
        <CCard padding="none" class="sop__list">
          <template #header>
            <div class="card-head">
              <h3>SOP 流程库</h3>
              <CButton v-if="canEdit" size="sm" variant="primary" @click="openCreate">
                <CIcon name="plus" :size="14" />新建 SOP
              </CButton>
            </div>
          </template>
          <div class="row" v-for="t in sop.templates" :key="t.id"
               :class="{ 'is-active': selId === t.id }" @click="selId = t.id">
            <div class="row__top">
              <span class="row__code">{{ t.code }}</span>
              <CStatusPill :status="tplStatus(t.status)">{{ STATUS_LABEL[t.status] }}</CStatusPill>
            </div>
            <div class="row__title">{{ t.title }}</div>
            <div class="row__meta">
              <span class="tag tag--dim">{{ CAT_LABEL[t.category] }}</span>
              <span>{{ t.version }}</span>
              <span>{{ t.steps.length }} 步骤</span>
              <span>{{ t.owner }}</span>
            </div>
          </div>
        </CCard>
        <CCard class="sop__detail" padding="lg">
          <template v-if="selTpl">
            <div class="detail__head">
              <div>
                <h3>{{ selTpl.title }}</h3>
                <div class="detail__sub">
                  <span>{{ selTpl.code }} · {{ selTpl.version }}</span>
                  <span>{{ CAT_LABEL[selTpl.category] }}</span>
                  <span>负责人 {{ selTpl.owner }}</span>
                  <span>更新 {{ selTpl.updatedAt }}</span>
                </div>
              </div>
              <CButton v-if="canApprove && selTpl.status === 'DRAFT'" size="sm" variant="primary" @click="sop.publishTemplate(selTpl.id)">发布</CButton>
            </div>
            <div class="steps">
              <div class="step" v-for="(s, i) in selTpl.steps" :key="s.id">
                <div class="step__no">{{ i + 1 }}</div>
                <div class="step__body">
                  <div class="step__title">{{ s.title }}<span v-if="s.requirePhoto" class="tag tag--photo">需拍照</span></div>
                  <div class="step__desc">{{ s.desc }}</div>
                </div>
              </div>
            </div>
          </template>
        </CCard>
      </template>

      <!-- 执行任务 -->
      <template v-else>
        <CCard title="待执行/进行中" padding="none" class="sop__list">
          <div class="row" v-for="t in taskList" :key="t.id"
               :class="{ 'is-active': taskId === t.id }" @click="taskId = t.id">
            <div class="row__top">
              <span class="tag" :class="'tag--p-' + t.priority.toLowerCase()">{{ prioLabel(t.priority) }}</span>
              <CStatusPill :status="taskStatus(t.status)">{{ TASK_STATUS_LABEL[t.status] }}</CStatusPill>
            </div>
            <div class="row__title">{{ t.templateTitle }}</div>
            <div class="row__meta">
              <span class="tag tag--dim">{{ CAT_LABEL[t.category] }}</span>
              <span>{{ t.tenantName }}</span>
              <span>{{ t.assignee }}</span>
              <span>截止 {{ t.dueAt }}</span>
            </div>
            <CProgressBar :value="progress(t)" :show-label="false" :color="progressColor(t.status)" :height="5" />
          </div>
          <div v-if="!taskList.length" class="empty">暂无任务</div>
        </CCard>
        <CCard class="sop__detail" padding="lg">
          <template v-if="selTask">
            <div class="detail__head">
              <div>
                <h3>{{ selTask.templateTitle }}</h3>
                <div class="detail__sub">
                  <span>{{ selTask.tenantName }}</span>
                  <span>负责人 {{ selTask.assignee }}</span>
                  <span>截止 {{ selTask.dueAt }}</span>
                </div>
              </div>
              <CButton v-if="canEdit && selTask.status === 'PENDING'" size="sm" variant="primary" @click="sop.startTask(selTask.id)">开始执行</CButton>
            </div>
            <div class="steps">
              <label class="step step--check" v-for="(s, i) in selTaskSteps" :key="s.id">
                <input type="checkbox" :checked="selTask.completedSteps.includes(s.id)"
                       :disabled="!canEdit || selTask.status === 'DONE'"
                       @change="sop.toggleStep(selTask.id, s.id)" />
                <div class="step__no">{{ i + 1 }}</div>
                <div class="step__body">
                  <div class="step__title" :class="{ 'is-done': selTask.completedSteps.includes(s.id) }">
                    {{ s.title }}<span v-if="s.requirePhoto" class="tag tag--photo">需拍照</span>
                  </div>
                  <div class="step__desc">{{ s.desc }}</div>
                </div>
              </label>
            </div>
            <div v-if="canEdit && selTask.status !== 'DONE'" class="complete">
              <CButton variant="primary" :disabled="!canComplete" @click="openComplete">完成全部步骤</CButton>
            </div>
            <div v-if="selTask.note" class="note">完成备注：{{ selTask.note }}</div>
          </template>
        </CCard>
      </template>
    </div>

    <CDrawer :show="!!completing" title="完成 SOP 任务" size="sm" @update:show="(v: boolean) => { if (!v) completing = false }">
      <div class="form-row"><label>执行备注</label><textarea v-model="note" rows="4" placeholder="记录执行情况"></textarea></div>
      <template #footer>
        <CButton variant="ghost" @click="completing = false">取消</CButton>
        <CButton variant="primary" :disabled="!note.trim()" @click="confirmComplete">确认完成</CButton>
      </template>
    </CDrawer>

    <!-- 新建 SOP 抽屉 -->
    <CDrawer v-model:show="showForm" title="新建 SOP 流程" size="md">
      <div class="form">
        <div class="form__row">
          <label class="form__label">SOP 名称 <span class="req">*</span></label>
          <CInput v-model="form.title" placeholder="如：术后 24 小时随访规范" />
        </div>
        <div class="form__row form__row--2">
          <div class="form__col">
            <label class="form__label">分类 <span class="req">*</span></label>
            <CSelect v-model="form.category" :options="catOptions" width="100%" />
          </div>
          <div class="form__col">
            <label class="form__label">负责人 <span class="req">*</span></label>
            <CInput v-model="form.owner" placeholder="如：护理部" />
          </div>
        </div>
        <div class="form__row">
          <label class="form__label">执行步骤 <span class="req">*</span></label>
          <div class="steps-editor">
            <div v-for="(s, i) in form.steps" :key="i" class="step-line">
              <div class="step-line__no">{{ i + 1 }}</div>
              <div class="step-line__body">
                <CInput v-model="s.title" placeholder="步骤标题" />
                <CTextarea v-model="s.desc" placeholder="步骤说明（可选）" />
              </div>
              <div class="step-line__opts">
                <label class="opt"><CCheckbox v-model="s.requirePhoto" />拍照</label>
                <CButton v-if="form.steps.length > 1" size="sm" variant="text" @click="removeStep(i)">删除</CButton>
              </div>
            </div>
            <CButton variant="ghost" size="sm" @click="addStep"><CIcon name="plus" :size="14" />添加步骤</CButton>
          </div>
        </div>
      </div>
      <template #footer>
        <div class="drawer__foot">
          <CButton variant="ghost" @click="showForm = false">取消</CButton>
          <CButton variant="primary" :disabled="!canSave" @click="saveForm">创建流程</CButton>
        </div>
      </template>
    </CDrawer>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CProgressBar from '@/components/CProgressBar.vue'
import CDrawer from '@/components/CDrawer.vue'
import CKpi from '@/components/CKpi.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CTextarea from '@/components/CTextarea.vue'
import CCheckbox from '@/components/CCheckbox.vue'
import { useM1SopStore, CAT_LABEL, STATUS_LABEL, TASK_STATUS_LABEL,
  type SopStatus, type TaskStatus, type Priority, type SopCategory } from '@/stores/m1Sop'
import { useAuthStore } from '@/stores/auth'

const sop = useM1SopStore()
const auth = useAuthStore()
onMounted(() => sop.seed())

const canEdit = computed(() => auth.can('sop:edit') || auth.isSuper)
const canApprove = computed(() => auth.can('sop:approve') || auth.isSuper)

const tabs = [{ key: 'template', label: '流程库' }, { key: 'task', label: '执行任务' }] as const
const tab = ref<'template' | 'task'>('template')
const selId = ref('S01')
const taskId = ref('TK01')
const selTpl = computed(() => sop.template(selId.value))
const selTask = computed(() => sop.tasks.find((t) => t.id === taskId.value))
const selTaskSteps = computed(() => selTask.value ? sop.template(selTask.value.templateId)?.steps ?? [] : [])
const taskList = computed(() => sop.tasks.filter((t) => t.status !== 'DONE'))
const canComplete = computed(() => selTask.value ? selTask.value.completedSteps.length === selTaskSteps.value.length : false)

/* ---------- 新建 SOP 抽屉 ---------- */
const showForm = ref(false)
const catOptions = (Object.keys(CAT_LABEL) as SopCategory[]).map((k) => ({ value: k, label: CAT_LABEL[k] }))
function emptyStep() { return { title: '', desc: '', requirePhoto: false } }
function emptyForm() {
  return { title: '', category: 'SERVICE' as SopCategory, owner: '', steps: [emptyStep()] }
}
const form = ref(emptyForm())
const canSave = computed(() => form.value.title.trim() && form.value.owner.trim() && form.value.steps.every((s) => s.title.trim()))
function addStep() { form.value.steps.push(emptyStep()) }
function removeStep(i: number) { form.value.steps.splice(i, 1) }
function openCreate() { form.value = emptyForm(); showForm.value = true }
function saveForm() {
  if (!canSave.value) return
  const t = sop.createTemplate({
    title: form.value.title.trim(),
    category: form.value.category,
    owner: form.value.owner.trim(),
    applicableStores: ['ALL'],
    steps: form.value.steps.map((s, i) => ({ id: `st-${i + 1}`, title: s.title.trim(), desc: s.desc.trim(), requirePhoto: s.requirePhoto })),
  })
  selId.value = t.id
  showForm.value = false
}

const completing = ref(false)
const note = ref('')
function openComplete() { completing.value = true; note.value = '' }
function confirmComplete() { if (selTask.value && note.value.trim()) sop.completeTask(selTask.value.id, note.value.trim()); completing.value = false }

function progress(t: { completedSteps: string[]; templateId: string }): number {
  const tmpl = sop.template(t.templateId)
  if (!tmpl || !tmpl.steps.length) return 0
  return Math.round((t.completedSteps.length / tmpl.steps.length) * 100)
}
function progressColor(t: TaskStatus): string {
  return t === 'OVERDUE' ? 'var(--c-danger-fg)' : t === 'DONE' ? 'var(--c-success-fg)' : 'var(--c-brand)'
}
function tplStatus(s: SopStatus): 'default' | 'success' | 'draft' | 'disabled' {
  return s === 'PUBLISHED' ? 'success' : s === 'DRAFT' ? 'draft' : 'disabled'
}
function taskStatus(s: TaskStatus): 'warning' | 'info' | 'success' | 'danger' {
  return s === 'IN_PROGRESS' ? 'warning' : s === 'DONE' ? 'success' : s === 'OVERDUE' ? 'danger' : 'info'
}
function prioLabel(p: Priority) { return p === 'HIGH' ? '高优先级' : p === 'MEDIUM' ? '中' : '低' }
void CAT_LABEL
</script>

<style scoped>
.sop { display: flex; flex-direction: column; gap: var(--s-lg); }
.sop__kpis { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .sop__kpis { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
.card-head { display: flex; justify-content: space-between; align-items: center; width: 100%; }
.card-head h3 { margin: 0; font-size: var(--t-md); font-weight: 600; color: var(--c-text); }
.sop__tabs { display: flex; gap: var(--s-xs); border-bottom: 1px solid var(--c-border); }
.sop__tabs button { padding: var(--s-xs) var(--s-md); border: none; background: none; cursor: pointer; font-size: var(--t-sm); color: var(--c-text-2); border-bottom: 2px solid transparent; margin-bottom: -1px; }
.sop__tabs button.is-active { color: var(--c-brand); border-bottom-color: var(--c-brand); font-weight: 600; }
.sop__body { display: grid; grid-template-columns: 360px 1fr; gap: var(--s-lg); align-items: start; }
.row { padding: var(--s-md) var(--s-lg); cursor: pointer; border-bottom: 1px solid var(--c-border); transition: background .15s; }
.row:last-child { border-bottom: none; }
.row:hover { background: var(--c-surface-muted); }
.row.is-active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.row__top { display: flex; justify-content: space-between; align-items: center; gap: var(--s-sm); }
.row__code { font-size: var(--t-xs); color: var(--c-text-3); font-family: monospace; }
.row__title { font-weight: 600; font-size: var(--t-sm); margin: var(--s-xs) 0; }
.row__meta { display: flex; flex-wrap: wrap; gap: var(--s-sm); font-size: var(--t-xs); color: var(--c-text-3); align-items: center; }
.row :deep(.pbar) { margin-top: var(--s-sm); }
.tag { font-size: var(--t-xs); padding: 2px 8px; border-radius: var(--r-sm); background: var(--c-surface-muted); color: var(--c-text-2); }
.tag--dim { background: var(--c-brand-soft); color: var(--c-brand); }
.tag--p-high { background: rgba(239,68,68,.12); color: var(--c-danger-fg); }
.tag--p-medium { background: rgba(245,158,11,.12); color: var(--c-warning-fg); }
.tag--p-low { background: rgba(100,116,139,.12); color: var(--c-text-3); }
.tag--photo { background: rgba(107,138,255,.12); color: var(--c-brand-secondary); }
.detail__head { display: flex; justify-content: space-between; align-items: flex-start; gap: var(--s-md); margin-bottom: var(--s-lg); }
.detail__head h3 { margin: 0 0 var(--s-xs); font-size: var(--t-lg); font-weight: 700; }
.detail__sub { display: flex; flex-wrap: wrap; gap: var(--s-md); font-size: var(--t-xs); color: var(--c-text-3); }
.steps { display: flex; flex-direction: column; gap: var(--s-sm); }
.step { display: flex; gap: var(--s-md); padding: var(--s-md); background: var(--c-surface-muted); border-radius: var(--r-md); }
.step--check { cursor: pointer; align-items: flex-start; }
.step--check input { margin-top: 4px; width: 16px; height: 16px; accent-color: var(--c-brand); }
.step__no { width: 26px; height: 26px; border-radius: 50%; background: var(--c-brand); color: #fff; display: flex; align-items: center; justify-content: center; font-size: var(--t-xs); font-weight: 700; flex-shrink: 0; }
.step__title { font-weight: 600; font-size: var(--t-sm); display: flex; align-items: center; gap: var(--s-xs); }
.step__title.is-done { color: var(--c-text-3); text-decoration: line-through; }
.step__desc { font-size: var(--t-xs); color: var(--c-text-2); margin-top: var(--s-xs); line-height: 1.6; }
.complete { margin-top: var(--s-lg); }
.note { margin-top: var(--s-md); padding: var(--s-sm) var(--s-md); background: var(--c-success-bg); border-radius: var(--r-md); font-size: var(--t-sm); color: var(--c-success-fg); }
.empty { text-align: center; color: var(--c-text-3); padding: var(--s-xl) 0; font-size: var(--t-sm); }
.form-row { display: flex; flex-direction: column; gap: var(--s-xs); }
.form-row label { font-size: var(--t-sm); font-weight: 600; }
.form-row textarea { border: 1px solid var(--c-border); border-radius: var(--r-md); padding: var(--s-sm); font-size: var(--t-sm); resize: vertical; font-family: inherit; }
.form-row textarea:focus { outline: none; border-color: var(--c-brand); }
@media (max-width: 900px) { .sop__body { grid-template-columns: 1fr; } }

.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.form__row--2 { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.form__label { font-size: var(--t-xs); color: var(--c-text-3); }
.req { color: var(--c-danger-fg); }
.steps-editor { display: flex; flex-direction: column; gap: var(--s-md); }
.step-line { display: flex; gap: var(--s-sm); align-items: flex-start; }
.step-line__no { width: 24px; height: 24px; border-radius: var(--r-pill); background: var(--c-brand-soft); color: var(--c-brand); font-size: var(--t-xs); font-weight: 700; display: flex; align-items: center; justify-content: center; flex-shrink: 0; margin-top: 6px; }
.step-line__body { flex: 1; display: flex; flex-direction: column; gap: var(--s-xs); }
.step-line__opts { display: flex; flex-direction: column; gap: var(--s-xs); align-items: flex-start; }
.opt { display: flex; align-items: center; gap: 4px; font-size: var(--t-xs); color: var(--c-text-3); white-space: nowrap; }
.drawer__foot { display: flex; justify-content: flex-end; gap: var(--s-sm); }
</style>
