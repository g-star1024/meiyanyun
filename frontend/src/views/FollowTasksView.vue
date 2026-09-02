<script setup lang="ts">
/* ============================================================
 * M3-08 跟进任务 /m3-tasks
 * 左列表（tab 筛选）+ 右详情（任务内容/客户/记录时间线）+ 新建弹层。
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CTextarea from '@/components/CTextarea.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CFab from '@/components/CFab.vue'
import {
  useFollowTaskStore,
  type FollowTask,
  type FollowTaskType,
  type FollowTaskPriority,
} from '@/stores/followtask'
import { FOLLOW_TASK_STATUS, dictPill } from '@/config/dictionary'
import { useAuthStore } from '@/stores/auth'

const store = useFollowTaskStore()
const auth = useAuthStore()
onMounted(() => store.seed())

const selectedId = ref<string | null>(null)
const selected = computed<FollowTask | null>(() => {
  if (selectedId.value) return store.get(selectedId.value) ?? null
  return store.filtered[0] ?? null
})

const kpis = computed(() => [
  { label: '我的待跟进', icon: 'bell', value: String(store.pending.length), tone: 'brand' as const },
  { label: '今日到期', icon: 'calendar', value: String(store.dueToday.length), tone: 'warning' as const },
  { label: '已逾期', icon: 'alert', value: String(store.overdue.length), tone: 'danger' as const },
  { label: '本月完成', icon: 'check-square', value: String(store.doneThisMonth.length), tone: 'success' as const },
])

const tabs = computed(() => [
  { key: 'ALL' as const, label: `全部 (${store.tasks.length})` },
  { key: 'PENDING' as const, label: `待跟进 (${store.pending.length})` },
  { key: 'OVERDUE' as const, label: `已逾期 (${store.overdue.length})` },
  { key: 'DONE' as const, label: `已完成 (${store.done.length})` },
])

const priorityMap: Record<FollowTaskPriority, { text: string; cls: string }> = {
  HIGH: { text: '高', cls: 'prio--high' },
  MEDIUM: { text: '中', cls: 'prio--med' },
  LOW: { text: '低', cls: 'prio--low' },
}

function fmtTime(iso?: string) {
  if (!iso) return '—'
  const d = new Date(iso)
  return `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}
function isOverdue(t: FollowTask) {
  return t.status === 'OVERDUE' || (t.status !== 'DONE' && new Date(t.deadline).getTime() < Date.now())
}

// 新建弹层
const showForm = ref(false)
const form = ref({
  customerName: '',
  customerLevel: '普通',
  type: 'PHONE' as FollowTaskType,
  content: '',
  deadline: '',
  priority: 'MEDIUM' as FollowTaskPriority,
})
const canSubmit = computed(() => form.value.customerName.trim() && form.value.content.trim() && form.value.deadline)
function openForm() {
  const d = new Date(Date.now() + 24 * 3600_000)
  const local = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}T${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  form.value = { customerName: '', customerLevel: '普通', type: 'PHONE', content: '', deadline: local, priority: 'MEDIUM' }
  showForm.value = true
}
function submitForm() {
  if (!canSubmit.value) return
  const t = store.create({ ...form.value })
  if (t) {
    showForm.value = false
    selectedId.value = t.id
  }
}

// 添加记录弹层
const showNote = ref(false)
const noteText = ref('')
function openNote() {
  noteText.value = ''
  showNote.value = true
}
function submitNote() {
  if (!selected.value || !noteText.value.trim()) return
  store.addLog(selected.value.id, noteText.value.trim())
  showNote.value = false
}

// 转派弹层
const showAssign = ref(false)
const assignName = ref('')
function openAssign() {
  assignName.value = selected.value?.assignee ?? ''
  showAssign.value = true
}
function submitAssign() {
  if (!selected.value || !assignName.value.trim()) return
  store.reassign(selected.value.id, assignName.value.trim())
  showAssign.value = false
}

function doComplete() {
  if (selected.value) store.complete(selected.value.id)
}
</script>

<template>
  <div class="ft">
    <div class="ft__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <div class="ft__body">
      <CCard class="ft__list" padding="none">
        <div class="tabs">
          <button
            v-for="t in tabs" :key="t.key"
            class="tab" :class="{ 'tab--active': store.filterTab === t.key }"
            @click="store.filterTab = t.key"
          >{{ t.label }}</button>
        </div>
        <div class="list">
          <div v-if="store.filtered.length === 0" class="empty">
            <CIcon :name="('check-square' as any)" :size="28" class="empty__icon" />
            <div>暂无跟进任务</div>
          </div>
          <button
            v-for="t in store.filtered" :key="t.id"
            class="row" :class="{ 'row--active': selected?.id === t.id, 'row--overdue': isOverdue(t) }"
            @click="selectedId = t.id"
          >
            <div class="row__top">
              <span class="row__name">
                <CIcon :name="(store.TYPE_ICON[t.type] as any)" :size="14" />
                {{ t.customerName }} · {{ t.customerLevel }}
              </span>
              <span class="prio" :class="priorityMap[t.priority].cls">{{ priorityMap[t.priority].text }}</span>
            </div>
            <div class="row__title">{{ t.content }}</div>
            <div class="row__meta">
              <span class="row__type">{{ store.TYPE_LABEL[t.type] }}</span>
              <span :class="{ 'is-overdue': isOverdue(t) }">
                <CIcon name="clock" :size="12" /> {{ fmtTime(t.deadline) }}
              </span>
              <CStatusPill :status="dictPill(FOLLOW_TASK_STATUS[t.status]).status">{{ dictPill(FOLLOW_TASK_STATUS[t.status]).text }}</CStatusPill>
            </div>
          </button>
          <CFab
            :actions="[{ icon: 'plus', label: '新建任务', disabled: !auth.can('followuptask:edit'), onClick: openForm }]"
          />
        </div>
      </CCard>

      <CCard v-if="selected" class="ft__detail" :title="`${selected.customerName} 的跟进任务`">
        <template #header>
          <h3 class="ft__detail-title">{{ selected.customerName }} · {{ selected.customerLevel }}</h3>
          <CStatusPill :status="dictPill(FOLLOW_TASK_STATUS[selected.status]).status">{{ dictPill(FOLLOW_TASK_STATUS[selected.status]).text }}</CStatusPill>
        </template>

        <div class="detail__head">
          <div>
            <div class="detail__content">{{ selected.content }}</div>
            <div class="detail__sub">
              <span class="tag tag--type"><CIcon :name="(store.TYPE_ICON[selected.type] as any)" :size="12" /> {{ store.TYPE_LABEL[selected.type] }}</span>
              <span class="tag tag--prio" :class="priorityMap[selected.priority].cls">优先级 {{ priorityMap[selected.priority].text }}</span>
            </div>
          </div>
          <div class="detail__assign">
            <div class="detail__assign-label">归属人</div>
            <div class="detail__assign-name">{{ selected.assignee }}</div>
          </div>
        </div>

        <div class="detail__grid">
          <div class="field"><span class="field__label">客户</span><span class="field__val">{{ selected.customerName }}（{{ selected.customerLevel }}）</span></div>
          <div class="field"><span class="field__label">任务类型</span><span class="field__val">{{ store.TYPE_LABEL[selected.type] }}</span></div>
          <div class="field"><span class="field__label">创建时间</span><span class="field__val">{{ fmtTime(selected.createdAt) }}</span></div>
          <div class="field"><span class="field__label">截止时间</span><span class="field__val" :class="{ 'is-overdue': isOverdue(selected) }">{{ fmtTime(selected.deadline) }}</span></div>
        </div>

        <div class="detail__logs">
          <div class="detail__sec-title">跟进记录</div>
          <div v-for="(l, i) in selected.logs" :key="i" class="note">
            <span class="note__who">{{ l.by }}</span>
            <span class="note__text">{{ l.text }}</span>
            <span class="note__time">{{ fmtTime(l.at) }}</span>
          </div>
        </div>

        <div class="detail__ops">
          <template v-if="selected.status !== 'DONE'">
            <CButton variant="ghost" v-perm.disable="'followuptask:edit'" @click="openAssign">
              <CIcon name="handover" :size="16" />转派
            </CButton>
            <CButton variant="ghost" v-perm.disable="'followuptask:edit'" @click="openNote">
              <CIcon name="edit" :size="16" />添加记录
            </CButton>
            <CButton variant="primary" v-perm.disable="'followuptask:edit'" @click="doComplete">
              <CIcon name="check" :size="16" />标记完成
            </CButton>
          </template>
          <div v-else class="ops__done">
            <CIcon name="check" :size="16" />已于 {{ fmtTime(selected.completedAt) }} 完成
          </div>
        </div>
      </CCard>

      <CCard v-else class="ft__detail ft__detail--empty" title="任务详情">
        <div class="detail-empty">
          <CIcon :name="('check-square' as any)" :size="40" class="detail-empty__icon" />
          <p>请选择一条跟进任务</p>
        </div>
      </CCard>
    </div>

    <!-- 新建任务弹层 -->
    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <CCard class="modal" title="新建跟进任务" padding="lg">
        <div class="form">
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">客户姓名</label>
              <CInput v-model="form.customerName" placeholder="如：林晚" />
            </div>
            <div>
              <label class="form__label">客户等级</label>
              <CSelect v-model="form.customerLevel" :options="[
                { value: '普通', label: '普通' },
                { value: '银卡', label: '银卡' },
                { value: '黄金', label: '黄金' },
                { value: '金卡', label: '金卡' },
                { value: '白金', label: '白金' },
                { value: '钻石', label: '钻石' },
              ]" />
            </div>
          </div>
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">跟进类型</label>
              <CSelect v-model="form.type" :options="[
                { value: 'PHONE', label: '电话回访' },
                { value: 'WECHAT', label: '企微跟进' },
                { value: 'IN_STORE', label: '到店提醒' },
                { value: 'BIRTHDAY', label: '生日关怀' },
                { value: 'POST_OP', label: '术后回访' },
                { value: 'CONTENT', label: '内容触达' },
              ]" />
            </div>
            <div>
              <label class="form__label">优先级</label>
              <CSelect v-model="form.priority" :options="[
                { value: 'HIGH', label: '高' },
                { value: 'MEDIUM', label: '中' },
                { value: 'LOW', label: '低' },
              ]" />
            </div>
          </div>
          <div class="form__row">
            <label class="form__label">截止时间</label>
            <input v-model="form.deadline" type="datetime-local" class="native-input" />
          </div>
          <div class="form__row">
            <label class="form__label">跟进内容</label>
            <CTextarea v-model="form.content" placeholder="如：术后第3天回访，确认恢复情况" />
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showForm = false">取消</CButton>
          <CButton variant="primary" :disabled="!canSubmit" @click="submitForm">提交</CButton>
        </template>
      </CCard>
    </div>

    <!-- 添加记录弹层 -->
    <div v-if="showNote" class="modal-mask" @click.self="showNote = false">
      <CCard class="modal modal--sm" title="添加跟进记录" padding="lg">
        <CTextarea v-model="noteText" placeholder="请输入跟进内容" />
        <template #footer>
          <CButton variant="ghost" @click="showNote = false">取消</CButton>
          <CButton variant="primary" :disabled="!noteText.trim()" @click="submitNote">保存</CButton>
        </template>
      </CCard>
    </div>

    <!-- 转派弹层 -->
    <div v-if="showAssign" class="modal-mask" @click.self="showAssign = false">
      <CCard class="modal modal--sm" title="转派任务" padding="lg">
        <label class="form__label">转派给</label>
        <CInput v-model="assignName" placeholder="归属人姓名" />
        <template #footer>
          <CButton variant="ghost" @click="showAssign = false">取消</CButton>
          <CButton variant="primary" :disabled="!assignName.trim()" @click="submitAssign">确认</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.ft { display: flex; flex-direction: column; gap: var(--s-lg); }
.ft__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .ft__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }

.ft__body { display: grid; grid-template-columns: 380px 1fr; gap: var(--s-lg); align-items: start; }
.ft__list { min-width: 0; position: relative; display: flex; flex-direction: column; }

.tabs { display: flex; gap: var(--s-xs); padding: var(--s-md); border-bottom: 1px solid var(--c-border-light); flex-wrap: nowrap; overflow-x: auto; align-items: center; }
.tab { padding: 6px 14px; border-radius: var(--r-md); border: 1px solid var(--c-border); background: var(--c-surface); color: var(--c-text-2); font-size: var(--t-sm); cursor: pointer; white-space: nowrap; flex-shrink: 0; }
.tab:hover { color: var(--c-brand); border-color: var(--c-brand); }
.tab--active { background: var(--c-brand-soft); border-color: var(--c-brand); color: var(--c-brand); font-weight: 600; }

.list { max-height: 560px; overflow-y: auto; display: flex; flex-direction: column; }
.empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl) var(--s-lg) 64px; color: var(--c-text-3); font-size: var(--t-sm); }
.empty__icon { color: var(--c-text-4); }

.row {
  display: block; width: 100%; text-align: left; padding: var(--s-md) var(--s-lg);
  background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer;
}
.row:hover { background: var(--c-brand-soft); }
.row--active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.row--overdue { background: var(--c-danger-bg); }
.row--overdue.row--active { box-shadow: inset 3px 0 0 var(--c-danger-fg); }
.row__top { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--s-xs); }
.row__name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); display: inline-flex; align-items: center; gap: 6px; }
.row__title { font-size: var(--t-sm); color: var(--c-text-2); margin-bottom: var(--s-xs); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.row__meta { display: flex; flex-wrap: wrap; gap: var(--s-xs); font-size: var(--t-xs); color: var(--c-text-3); align-items: center; }
.row__meta > span { display: inline-flex; align-items: center; gap: 3px; }
.is-overdue { color: var(--c-danger-fg) !important; font-weight: 600; }

.prio { font-size: var(--t-xs); padding: 2px 10px; border-radius: var(--r-sm); font-weight: 600; }
.prio--high { background: var(--c-danger-bg); color: var(--c-danger-fg); }
.prio--med { background: var(--c-warning-bg); color: var(--c-warning-fg); }
.prio--low { background: var(--c-brand-soft); color: var(--c-brand); }

.ft__detail-title { font-size: var(--t-md); font-weight: 700; margin: 0; }
.detail__head { display: flex; justify-content: space-between; gap: var(--s-md); padding-bottom: var(--s-md); border-bottom: 1px solid var(--c-border-light); }
.detail__content { font-size: var(--t-lg); font-weight: 700; color: var(--c-text); line-height: var(--lh-md); }
.detail__sub { display: flex; flex-wrap: wrap; gap: var(--s-xs); margin-top: var(--s-xs); }
.tag { font-size: var(--t-xs); padding: 2px 8px; border-radius: var(--r-sm); background: var(--c-surface-muted); color: var(--c-text-2); display: inline-flex; align-items: center; gap: 4px; }
.tag--type { background: var(--c-brand-soft); color: var(--c-brand); }
.tag--prio.prio--high { background: var(--c-danger-bg); color: var(--c-danger-fg); }
.tag--prio.prio--med { background: var(--c-warning-bg); color: var(--c-warning-fg); }
.tag--prio.prio--low { background: var(--c-brand-soft); color: var(--c-brand); }
.detail__assign { text-align: right; }
.detail__assign-label { font-size: var(--t-xs); color: var(--c-text-3); }
.detail__assign-name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }

.detail__grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md) var(--s-lg); margin: var(--s-lg) 0; }
.field { display: flex; flex-direction: column; gap: 2px; }
.field__label { font-size: var(--t-xs); color: var(--c-text-3); }
.field__val { font-size: var(--t-sm); color: var(--c-text); }

.detail__logs { margin-bottom: var(--s-lg); }
.detail__sec-title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); margin-bottom: var(--s-sm); }
.note { display: flex; gap: var(--s-sm); align-items: baseline; padding: var(--s-xs) 0; border-bottom: 1px solid var(--c-border-light); font-size: var(--t-sm); }
.note:last-child { border-bottom: none; }
.note__who { font-weight: 600; color: var(--c-text); flex-shrink: 0; }
.note__text { color: var(--c-text-2); flex: 1; }
.note__time { font-size: var(--t-xs); color: var(--c-text-3); flex-shrink: 0; }

.detail__ops { display: flex; justify-content: flex-end; gap: var(--s-sm); margin-top: var(--s-lg); padding-top: var(--s-lg); border-top: 1px solid var(--c-border-light); }
.ops__done { display: flex; align-items: center; gap: var(--s-sm); font-size: var(--t-sm); color: var(--c-success-fg); font-weight: 600; margin-left: auto; }

.detail-empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-md); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); }
.detail-empty__icon { color: var(--c-text-4); }

.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 560px; max-width: 100%; max-height: 90vh; overflow-y: auto; box-shadow: var(--shadow-pop); }
.modal--sm { width: 400px; }
.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.form__row--2 { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.form__label { font-size: var(--t-xs); color: var(--c-text-3); }
.native-input {
  width: 100%;
  padding: var(--s-sm) var(--s-md);
  border: 1px solid var(--c-border);
  border-radius: var(--r-sm);
  background: var(--c-surface);
  font-size: var(--t-sm);
  color: var(--c-text);
}
.native-input:focus { outline: none; border-color: var(--c-brand); }

@media (max-width: 1024px) {
  .ft__body { grid-template-columns: 1fr; }
  .ft__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .detail__head { flex-direction: column; gap: var(--s-sm); }
  .detail__assign { text-align: left; }
  .list { max-height: 320px; }
}
</style>
