<script setup lang="ts">
/* ============================================================
 * 门店日报 /m2-daily（M2-06）
 * 设计稿 SCREEN-M2-06 Desktop/Tablet 均空白，按业务域自建：
 * 顶部 4 KPI（客流/成交/服务/库存预警） + 分时客流趋势 + 当日待办 + 异常与说明 + 提交日报。
 * 一天一份，今日草稿可编辑，提交后锁定留痕。
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CTextarea from '@/components/CTextarea.vue'
import CSelect from '@/components/CSelect.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import { useDailyStore, type DailyTodoKind } from '@/stores/daily'
import { useAuthStore } from '@/stores/auth'

const store = useDailyStore()
const auth = useAuthStore()
const canEdit = computed(() => auth.can('daily:edit'))
onMounted(() => {
  store.seed()
  // seed 会把今日空草稿填充为带数据的草稿，此时再把已保存值同步到本地编辑态
  syncForm()
  syncNotes()
})

const report = computed(() => store.ensureToday())
const isDraft = computed(() => report.value.status === 'DRAFT')
const isSubmitted = computed(() => report.value.status === 'SUBMITTED')

const kpis = computed(() => [
  { label: '今日客流', icon: 'customer', value: String(report.value.footfall), tone: 'brand' as const },
  { label: '今日成交', icon: 'finance', value: String(report.value.orders), tone: 'teal' as const },
  { label: '服务工单完成', icon: 'tool', value: String(report.value.services), tone: 'success' as const },
  { label: '库存预警', icon: 'alert', value: String(report.value.inventoryAlerts), tone: report.value.inventoryAlerts > 0 ? ('danger' as const) : ('text' as const) },
])

// 经营概览编辑
const form = ref({ footfall: 0, orders: 0, services: 0, inventoryAlerts: 0 })
function syncForm() {
  form.value = {
    footfall: report.value.footfall,
    orders: report.value.orders,
    services: report.value.services,
    inventoryAlerts: report.value.inventoryAlerts,
  }
}
function applyForm() {
  if (!isDraft.value) return
  store.save({
    footfall: Number(form.value.footfall) || 0,
    orders: Number(form.value.orders) || 0,
    services: Number(form.value.services) || 0,
    inventoryAlerts: Number(form.value.inventoryAlerts) || 0,
  })
}
function fmtTimeNoon(iso?: string) { return iso ? iso.replace('T', ' ').slice(0, 16) : '—' }

// 分时客流趋势
const maxHourly = computed(() => Math.max(1, ...report.value.hourly))
const peakHour = computed(() => {
  const h = report.value.hourly
  const idx = h.indexOf(Math.max(...h))
  return store.HOURS[idx]
})

// 待办
const newTodo = ref({ kind: 'TASK' as DailyTodoKind, content: '', urgent: false })
function addTodo() {
  if (!newTodo.value.content.trim()) return
  store.addTodo({ content: newTodo.value.content, kind: newTodo.value.kind, urgent: newTodo.value.urgent })
  newTodo.value = { kind: 'TASK', content: '', urgent: false }
}
function removeTodo(id: string) { store.removeTodo(id) }
function toggleTodo(id: string) { store.toggleTodo(id) }
const todoKindLabel = (k: DailyTodoKind) => store.TODO_KIND_LABEL[k]
const todoKindClass: Record<DailyTodoKind, string> = {
  TASK: 'todo__kind--task', CUSTOMER: 'todo__kind--customer', ISSUE: 'todo__kind--issue',
}

// 异常与说明（草稿可编辑）
const exceptions = ref('')
const note = ref('')
function syncNotes() {
  exceptions.value = report.value.exceptions
  note.value = report.value.note
}
function applyNotes() {
  if (!isDraft.value) return
  store.save({ exceptions: exceptions.value, note: note.value })
}

// 提交
const confirm = ref<{ show: boolean } | null>(null)
function askSubmit() {
  if (!isDraft.value) return
  confirm.value = { show: true }
}
function doSubmit() {
  applyForm(); applyNotes()
  store.submit()
  confirm.value = null
}

syncForm(); syncNotes()
</script>

<template>
  <div class="dr">
    <div class="dr__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <div class="dr__grid">
      <!-- 左：分时客流趋势 -->
      <CCard class="dr__trend" title="分时客流趋势">
        <template #header>
          <div class="dr__card-head">
            <div class="dr__card-head-left">
              <h3 class="dr__card-title">分时客流趋势</h3>
              <span class="dr__peak">峰值 {{ peakHour }}:00 · 共 {{ report.footfall }} 人</span>
            </div>
            <div class="dr__card-head-right">
              <CStatusPill v-if="isDraft" status="draft">草稿</CStatusPill>
              <CStatusPill v-else status="success">已提交</CStatusPill>
              <CButton variant="primary" size="sm" v-perm.disable="'daily:submit'" :disabled="!isDraft" @click="askSubmit">
                <CIcon name="check-square" :size="14" />提交日报
              </CButton>
            </div>
          </div>
        </template>
        <div class="trend">
          <div v-for="(v, i) in report.hourly" :key="i" class="trend__col">
            <div class="trend__bar-wrap">
              <div class="trend__bar" :style="{ height: (v / maxHourly * 100) + '%' }">
                <span class="trend__val">{{ v }}</span>
              </div>
            </div>
            <div class="trend__hour">{{ store.HOURS[i] }}</div>
          </div>
        </div>
      </CCard>

      <!-- 右：当日待办清单 -->
      <CCard class="dr__todo" title="当日待办清单">
        <template #header>
          <h3 class="dr__card-title">当日待办清单</h3>
          <span class="dr__count">{{ report.todos.filter(t => !t.done).length }} 项未结</span>
        </template>

        <div class="todos">
          <div v-if="report.todos.length === 0" class="todos__empty">暂无待办事项</div>
          <div v-for="t in report.todos" :key="t.id" class="todo">
            <button
              class="todo__check" :class="{ 'todo__check--on': t.done, 'todo__check--disabled': !isDraft || !canEdit }"
              :disabled="!isDraft || !canEdit" @click="toggleTodo(t.id)"
            >
              <CIcon v-if="t.done" name="check" :size="12" />
            </button>
            <div class="todo__body">
              <span class="todo__kind" :class="todoKindClass[t.kind]">{{ todoKindLabel(t.kind) }}</span>
              <span v-if="t.urgent" class="todo__urgent">紧急</span>
              <span class="todo__text" :class="{ 'todo__text--done': t.done }">{{ t.content }}</span>
            </div>
            <button v-if="isDraft && canEdit" class="todo__del" @click="removeTodo(t.id)">
              <CIcon name="delete" :size="14" />
            </button>
          </div>
        </div>

        <div v-if="isDraft && canEdit" class="todo-add">
          <CSelect v-model="newTodo.kind" width="110px" :options="[
            { value: 'TASK', label: '待办事务' },
            { value: 'CUSTOMER', label: '客户跟进' },
            { value: 'ISSUE', label: '异常处理' },
          ]" />
          <CInput v-model="newTodo.content" placeholder="添加待办事项…" @keyup.enter="addTodo" />
          <label class="todo-add__urgent"><input type="checkbox" v-model="newTodo.urgent" /><span>紧急</span></label>
          <CButton variant="secondary" :disabled="!newTodo.content.trim()" @click="addTodo">添加</CButton>
        </div>
      </CCard>
    </div>

    <!-- 经营概览 + 异常与说明 -->
    <div class="dr__grid dr__grid--btm">
      <CCard class="dr__overview" title="经营概览">
        <template #header>
          <h3 class="dr__card-title">经营概览</h3>
          <CButton v-if="isDraft && canEdit" variant="ghost" size="sm" @click="applyForm">保存</CButton>
        </template>
        <div class="ov">
          <div class="ov__item">
            <label class="ov__label">今日客流（人）</label>
            <CInput :model-value="String(form.footfall)" :disabled="!isDraft || !canEdit" @update:model-value="form.footfall = Number($event) || 0" placeholder="0" />
          </div>
          <div class="ov__item">
            <label class="ov__label">今日成交（单）</label>
            <CInput :model-value="String(form.orders)" :disabled="!isDraft || !canEdit" @update:model-value="form.orders = Number($event) || 0" placeholder="0" />
          </div>
          <div class="ov__item">
            <label class="ov__label">服务工单完成（单）</label>
            <CInput :model-value="String(form.services)" :disabled="!isDraft || !canEdit" @update:model-value="form.services = Number($event) || 0" placeholder="0" />
          </div>
          <div class="ov__item">
            <label class="ov__label">库存预警（项）</label>
            <CInput :model-value="String(form.inventoryAlerts)" :disabled="!isDraft || !canEdit" @update:model-value="form.inventoryAlerts = Number($event) || 0" placeholder="0" />
          </div>
        </div>
        <p class="ov__hint">分时客流趋势中的客流合计数将自动同步至「今日客流」。</p>
      </CCard>

      <CCard class="dr__note" title="异常与日报说明">
        <template #header>
          <h3 class="dr__card-title">异常与日报说明</h3>
          <CButton v-if="isDraft && canEdit" variant="ghost" size="sm" @click="applyNotes">保存</CButton>
        </template>
        <label class="field-label">异常记录</label>
        <CTextarea v-model="exceptions" :disabled="!isDraft || !canEdit" placeholder="记录当日异常事件（设备故障、客诉、安全等）" />
        <label class="field-label">日报说明</label>
        <CTextarea v-model="note" :disabled="!isDraft || !canEdit" placeholder="补充当日经营说明、次日重点事项等" />
      </CCard>
    </div>

    <div v-if="isSubmitted" class="dr__done-bar">
      <CIcon name="check-square" :size="16" />
      <span>日报已提交至区域，内容已锁定留痕。提交人：{{ report.submittedBy }} · {{ fmtTimeNoon(report.submittedAt) }}</span>
    </div>

    <!-- 提交确认弹层 -->
    <div v-if="confirm?.show" class="modal-mask" @click.self="confirm = null">
      <CCard class="modal modal--sm" title="提交门店日报" padding="lg">
        <p class="confirm__text">确认提交 {{ report.dailyNo }} 门店日报？提交后将锁定，仅可查看。</p>
        <template #footer>
          <CButton variant="ghost" @click="confirm = null">取消</CButton>
          <CButton variant="primary" @click="doSubmit">确认提交</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.dr { display: flex; flex-direction: column; gap: var(--s-lg); }
.dr__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .dr__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }

.dr__grid { display: grid; grid-template-columns: 1.1fr 1fr; gap: var(--s-lg); align-items: start; }
.dr__card-head { display: flex; align-items: center; justify-content: space-between; width: 100%; gap: var(--s-sm); flex-wrap: wrap; }
.dr__card-head-left { display: flex; align-items: center; gap: var(--s-sm); }
.dr__card-head-right { display: flex; align-items: center; gap: var(--s-sm); }
.dr__card-title { font-size: var(--t-md); font-weight: 700; margin: 0; }

.dr__peak, .dr__count { font-size: var(--t-xs); color: var(--c-text-3); }
.dr__peak { color: var(--c-brand); font-weight: 600; }

/* 分时客流趋势 */
.trend { display: flex; align-items: flex-end; gap: var(--s-xs); height: 200px; padding-top: var(--s-md); }
.trend__col { flex: 1; display: flex; flex-direction: column; align-items: center; height: 100%; }
.trend__bar-wrap { flex: 1; width: 100%; display: flex; align-items: flex-end; justify-content: center; }
.trend__bar {
  width: 70%; max-width: 28px; background: linear-gradient(180deg, var(--c-brand), var(--c-brand-2, #6d8bff));
  border-radius: var(--r-sm) var(--r-sm) 0 0; position: relative; min-height: 4px; transition: height .3s ease;
}
.trend__val { position: absolute; top: -18px; left: 50%; transform: translateX(-50%); font-size: var(--t-xs); color: var(--c-text-2); white-space: nowrap; }
.trend__hour { font-size: var(--t-xs); color: var(--c-text-3); margin-top: var(--s-xs); }

/* 待办 */
.todos { display: flex; flex-direction: column; gap: var(--s-xs); }
.todo { display: flex; align-items: flex-start; gap: var(--s-sm); padding: var(--s-sm) 0; border-bottom: 1px solid var(--c-border-light); }
.todo:last-child { border-bottom: none; }
.todo__check {
  width: 18px; height: 18px; border-radius: var(--r-sm); border: 1.5px solid var(--c-border);
  background: #fff; display: flex; align-items: center; justify-content: center; cursor: pointer; flex-shrink: 0; margin-top: 1px; color: #fff;
}
.todo__check--on { background: var(--c-success-fg); border-color: var(--c-success-fg); }
.todo__check--disabled { cursor: not-allowed; opacity: 0.6; }
.todo__body { flex: 1; font-size: var(--t-sm); color: var(--c-text); display: flex; align-items: center; gap: var(--s-xs); flex-wrap: wrap; }
.todo__text--done { text-decoration: line-through; color: var(--c-text-4); }
.todo__kind { font-size: var(--t-xs); padding: 1px 6px; border-radius: var(--r-capsule); }
.todo__kind--customer { background: var(--c-brand-soft); color: var(--c-brand); }
.todo__kind--task { background: var(--c-surface-muted, #f3f4f8); color: var(--c-text-3); }
.todo__kind--issue { background: var(--c-danger-bg); color: var(--c-danger-fg); }
.todo__urgent { font-size: var(--t-xs); color: var(--c-danger-fg); background: var(--c-danger-bg); padding: 0 6px; border-radius: var(--r-capsule); }
.todo__del { background: none; border: none; cursor: pointer; color: var(--c-text-4); padding: 2px; }
.todo__del:hover { color: var(--c-danger-fg); }
.todos__empty { font-size: var(--t-sm); color: var(--c-text-4); padding: var(--s-md) 0; }
.todo-add { display: flex; gap: var(--s-sm); align-items: center; margin-top: var(--s-md); flex-wrap: wrap; }
.todo-add__urgent { display: flex; align-items: center; gap: 4px; font-size: var(--t-xs); color: var(--c-text-2); cursor: pointer; white-space: nowrap; }

/* 经营概览 */
.ov { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.ov__item label { display: block; font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: 4px; }
.ov__hint { font-size: var(--t-xs); color: var(--c-text-3); margin: var(--s-md) 0 0; line-height: 1.6; }

.field-label { display: block; font-size: var(--t-xs); color: var(--c-text-3); margin: var(--s-md) 0 var(--s-xs); }
.field-label:first-child { margin-top: 0; }

.dr__done-bar { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-md); background: var(--c-success-bg); color: var(--c-success-fg); border-radius: var(--r-md); font-size: var(--t-sm); }

.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 380px; max-width: 100%; box-shadow: var(--shadow-pop); }
.modal--sm { width: 360px; }
.confirm__text { font-size: var(--t-sm); color: var(--c-text); text-align: center; margin: var(--s-md) 0; }

@media (max-width: 1024px) {
  .dr__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .dr__grid { grid-template-columns: 1fr; }
  .ov { grid-template-columns: 1fr 1fr; }
  .trend { height: 170px; }
}
</style>
