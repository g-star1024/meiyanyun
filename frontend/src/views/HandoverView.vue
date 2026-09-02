<script setup lang="ts">
/* ============================================================
 * 交接班 / 双签交接 /handover（Desktop 优先 · 平板堆叠）
 * 状态机：草稿 → 待确认 → 已交接（双签）；接班人可退回补充。
 * 交班人提交后锁定，接班人确认完成双签留痕。
 * ============================================================ */
import { computed, onMounted, ref, watch } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CTextarea from '@/components/CTextarea.vue'
import CSelect from '@/components/CSelect.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import {
  useHandoverStore,
  type Handover, type HandoverShift, type HandoverTodo,
} from '@/stores/handover'
import { HANDOVER_STATUS, dictPill } from '@/config/dictionary'

const handover = useHandoverStore()
onMounted(() => handover.seed())

type Tab = 'SUBMITTED' | 'DRAFT' | 'CONFIRMED'
const tab = ref<Tab>('SUBMITTED')
const selectedId = ref<string | null>(null)
const keyword = ref('')

const tabList = computed(() => [
  { k: 'SUBMITTED' as Tab, label: `待我确认 (${handover.submitted.length})` },
  { k: 'DRAFT' as Tab, label: `草稿 (${handover.drafts.length})` },
  { k: 'CONFIRMED' as Tab, label: `已交接 (${handover.confirmed.length})` },
])

const baseList = computed<Handover[]>(() => {
  if (tab.value === 'SUBMITTED') return [...handover.submitted].sort((a, b) => b.date.localeCompare(a.date))
  if (tab.value === 'DRAFT') return handover.drafts
  return [...handover.confirmed].sort((a, b) => (b.confirmedAt ?? '').localeCompare(a.confirmedAt ?? ''))
})
const list = computed<Handover[]>(() => {
  const kw = keyword.value.trim()
  if (!kw) return baseList.value
  return baseList.value.filter(
    (h) => h.fromName.includes(kw) || h.toName.includes(kw) || h.handoverNo.includes(kw),
  )
})

const selected = computed(() => {
  if (selectedId.value) return handover.get(selectedId.value) ?? null
  return list.value[0] ?? null
})
function selectTab(t: Tab) { tab.value = t; selectedId.value = null }

const kpis = computed(() => [
  { label: '待我确认', value: handover.pendingCount, tone: 'warning' as const, icon: 'bell' as const },
  { label: '今日已交接', value: handover.todayConfirmed, tone: 'success' as const, icon: 'check-square' as const },
  { label: '未结待办', value: handover.openTodos, tone: 'danger' as const, icon: 'alert' as const },
  { label: '草稿', value: handover.drafts.length, tone: 'default' as const, icon: 'edit' as const },
])

const shiftLabel: Record<HandoverShift, string> = { MORNING: '早班', EVENING: '晚班', FULL: '全天' }
const todoKindLabel: Record<HandoverTodo['kind'], string> = { CUSTOMER: '客户跟进', TASK: '待办事务', ISSUE: '异常处理' }
const todoKindClass: Record<HandoverTodo['kind'], string> = {
  CUSTOMER: 'todo__kind--customer', TASK: 'todo__kind--task', ISSUE: 'todo__kind--issue',
}

function fmtMoney(n: number) { return '¥' + n.toLocaleString('zh-CN') }
function fmtDate(iso: string) { return iso.slice(0, 10) }
function fmtDateTime(iso?: string) { return iso ? iso.replace('T', ' ').slice(0, 16) : '—' }

const isDraft = computed(() => selected.value?.status === 'DRAFT')
const isSubmitted = computed(() => selected.value?.status === 'SUBMITTED')
const isConfirmed = computed(() => selected.value?.status === 'CONFIRMED')

// ---- 草稿编辑表单 ----
const form = ref({
  revenue: '', orders: '', arrivals: '',
  important: '', cash: '', equipment: '', toName: '',
})
const newTodo = ref({ kind: 'CUSTOMER' as HandoverTodo['kind'], content: '', urgent: false })

// ---- 接班确认状态（提前声明，供下方 watch immediate 使用）----
const confirmNote = ref('')
const showSendBack = ref(false)
const sendBackReason = ref('')

watch(
  selected,
  (h) => {
    confirmNote.value = ''; sendBackReason.value = ''; showSendBack.value = false
    newTodo.value = { kind: 'CUSTOMER', content: '', urgent: false }
    if (h) {
      form.value = {
        revenue: String(h.revenueAmount || ''),
        orders: String(h.orderCount || ''),
        arrivals: String(h.arrivalCount || ''),
        important: h.importantNote ?? '',
        cash: h.cashNote ?? '',
        equipment: h.equipmentNote ?? '',
        toName: h.toName,
      }
    }
  },
  { immediate: true },
)
function saveDraft() {
  if (!selected.value) return
  selectedId.value = selected.value.id
  handover.updateDraft(selected.value.id, {
    revenueAmount: Number(form.value.revenue) || 0,
    orderCount: Number(form.value.orders) || 0,
    arrivalCount: Number(form.value.arrivals) || 0,
    importantNote: form.value.important,
    cashNote: form.value.cash,
    equipmentNote: form.value.equipment,
    toName: form.value.toName.trim(),
  })
}
function addTodoItem() {
  if (!selected.value || !newTodo.value.content.trim()) return
  selectedId.value = selected.value.id
  handover.addTodo(selected.value.id, {
    kind: newTodo.value.kind,
    content: newTodo.value.content.trim(),
    urgent: newTodo.value.urgent,
  })
  newTodo.value = { kind: 'CUSTOMER', content: '', urgent: false }
}
function removeTodoItem(todoId: string) {
  if (!selected.value) return
  handover.removeTodo(selected.value.id, todoId)
}
function toggleTodoItem(todoId: string) {
  if (!selected.value) return
  handover.toggleTodo(selected.value.id, todoId)
}
function doSubmit() {
  if (!selected.value || !form.value.toName.trim()) return
  saveDraft()
  selectedId.value = selected.value.id
  handover.submit(selected.value.id)
}

// ---- 接班确认 ----
function doConfirm() {
  if (!selected.value) return
  selectedId.value = selected.value.id
  handover.confirm(selected.value.id, confirmNote.value)
  confirmNote.value = ''
}
function doSendBack() {
  if (!selected.value || !sendBackReason.value.trim()) return
  selectedId.value = selected.value.id
  handover.sendBack(selected.value.id, sendBackReason.value.trim())
  showSendBack.value = false; sendBackReason.value = ''
}

// ---- 新建交接单 ----
const showForm = ref(false)
const newHo = ref({
  shift: 'MORNING' as HandoverShift,
  date: new Date().toISOString().slice(0, 10),
  toName: '',
})
function createHo() {
  if (!newHo.value.toName.trim()) return
  const h = handover.create({
    shift: newHo.value.shift,
    date: new Date(newHo.value.date).toISOString(),
    toName: newHo.value.toName.trim(),
  })
  if (h) {
    showForm.value = false
    newHo.value = { shift: 'MORNING', date: new Date().toISOString().slice(0, 10), toName: '' }
    selectedId.value = h.id
    tab.value = 'DRAFT'
  }
}
</script>

<template>
  <div class="ho">
    <div class="ho__kpis">
      <div v-for="k in kpis" :key="k.label" class="kpi">
        <div class="kpi__icon" :class="`kpi__icon--${k.tone}`"><CIcon :name="k.icon" :size="18" /></div>
        <div>
          <div class="kpi__label">{{ k.label }}</div>
          <div class="kpi__value" :class="`kpi__value--${k.tone}`">{{ k.value }}</div>
        </div>
      </div>
    </div>
    <div class="ho__toolbar">
      <CInput v-model="keyword" placeholder="搜索单号 / 交班人 / 接班人" />
      <CButton variant="primary" v-perm.disable="'handover:create'" @click="showForm = true">
        <CIcon name="plus" :size="16" />新建交接单
      </CButton>
    </div>

    <div class="ho__body">
      <!-- 左列 -->
      <CCard class="ho__list" padding="none">
        <div class="tabs">
          <button
            v-for="t in tabList" :key="t.k"
            class="tab" :class="{ 'tab--active': tab === t.k }"
            @click="selectTab(t.k)"
          >{{ t.label }}</button>
        </div>
        <div class="list">
          <div v-if="list.length === 0" class="empty">
            <CIcon name="handover" :size="28" class="empty__icon" />
            <div>暂无交接单</div>
          </div>
          <button
            v-for="h in list" :key="h.id"
            class="rec" :class="{ 'rec--active': selected?.id === h.id, 'rec--pending': h.status === 'SUBMITTED' }"
            @click="selectedId = h.id"
          >
            <div class="rec__top">
              <span class="rec__shift">{{ shiftLabel[h.shift] }}</span>
              <CStatusPill :status="dictPill(HANDOVER_STATUS[h.status]).status">{{ dictPill(HANDOVER_STATUS[h.status]).text }}</CStatusPill>
            </div>
            <div class="rec__no">{{ h.handoverNo }}</div>
            <div class="rec__people">
              <span class="rec__from">{{ h.fromName }}</span>
              <CIcon name="chevron-right" :size="12" class="rec__arrow" />
              <span class="rec__to">{{ h.toName }}</span>
            </div>
            <div class="rec__meta">
              <span>{{ fmtDate(h.date) }}</span>
              <span class="rec__amt">{{ fmtMoney(h.revenueAmount) }}</span>
            </div>
          </button>
        </div>
      </CCard>

      <!-- 右列详情 -->
      <CCard v-if="selected" class="ho__detail" :title="selected.handoverNo">
        <template #header>
          <h3 class="ho__detail-title">{{ shiftLabel[selected.shift] }}交接 · {{ fmtDate(selected.date) }}</h3>
          <div class="ho__detail-tags">
            <CStatusPill :status="dictPill(HANDOVER_STATUS[selected.status]).status">{{ dictPill(HANDOVER_STATUS[selected.status]).text }}</CStatusPill>
          </div>
        </template>

        <!-- 双签栏 -->
        <div class="signbar">
          <div class="signbar__person">
            <CIcon name="user" :size="16" />
            <div>
              <div class="signbar__role">交班人</div>
              <div class="signbar__name">{{ selected.fromName }}</div>
              <div class="signbar__time">{{ fmtDateTime(selected.submittedAt) }}</div>
            </div>
            <CIcon v-if="selected.status !== 'DRAFT'" name="check" :size="16" class="signbar__check" />
          </div>
          <CIcon name="chevron-right" :size="20" class="signbar__sep" />
          <div class="signbar__person" :class="{ 'signbar__person--done': isConfirmed }">
            <CIcon name="user-check" :size="16" />
            <div>
              <div class="signbar__role">接班人</div>
              <div class="signbar__name">{{ selected.toName }}</div>
              <div class="signbar__time">{{ fmtDateTime(selected.confirmedAt) }}</div>
            </div>
            <CIcon v-if="isConfirmed" name="check" :size="16" class="signbar__check" />
          </div>
        </div>

        <!-- 经营摘要 -->
        <div class="section">
          <div class="section__title">本班经营摘要</div>
          <div v-if="isDraft" class="sum-edit">
            <div class="sum-edit__item">
              <label>营业额（元）</label>
              <CInput v-model="form.revenue" placeholder="0" />
            </div>
            <div class="sum-edit__item">
              <label>订单数</label>
              <CInput v-model="form.orders" placeholder="0" />
            </div>
            <div class="sum-edit__item">
              <label>到店人数</label>
              <CInput v-model="form.arrivals" placeholder="0" />
            </div>
          </div>
          <div v-else class="sum">
            <div class="sum__item"><div class="sum__num">{{ fmtMoney(selected.revenueAmount) }}</div><div class="sum__lbl">营业额</div></div>
            <div class="sum__item"><div class="sum__num">{{ selected.orderCount }}</div><div class="sum__lbl">订单数</div></div>
            <div class="sum__item"><div class="sum__num">{{ selected.arrivalCount }}</div><div class="sum__lbl">到店人数</div></div>
          </div>
        </div>

        <!-- 待跟进事项 -->
        <div class="section">
          <div class="section__title">
            待跟进事项
            <span class="section__count">{{ selected.todos.filter((t) => !t.done).length }} 项未结</span>
          </div>
          <div class="todos">
            <div v-for="t in selected.todos" :key="t.id" class="todo">
              <button
                class="todo__check" :class="{ 'todo__check--on': t.done, 'todo__check--disabled': isDraft }"
                :disabled="isDraft"
                @click="toggleTodoItem(t.id)"
              >
                <CIcon v-if="t.done" name="check" :size="12" />
              </button>
              <div class="todo__body">
                <span class="todo__kind" :class="todoKindClass[t.kind]">{{ todoKindLabel[t.kind] }}</span>
                <span v-if="t.urgent" class="todo__urgent">紧急</span>
                <span class="todo__text" :class="{ 'todo__text--done': t.done }">{{ t.content }}</span>
              </div>
              <button v-if="isDraft" class="todo__del" @click="removeTodoItem(t.id)">
                <CIcon name="delete" :size="14" />
              </button>
            </div>
            <div v-if="selected.todos.length === 0" class="todos__empty">暂无待跟进事项</div>
          </div>
          <!-- 草稿：添加待办 -->
          <div v-if="isDraft" class="todo-add">
            <CSelect v-model="newTodo.kind" width="110px" :options="[
              { value: 'CUSTOMER', label: '客户跟进' },
              { value: 'TASK', label: '待办事务' },
              { value: 'ISSUE', label: '异常处理' },
            ]" />
            <CInput v-model="newTodo.content" placeholder="添加待跟进事项…" @keyup.enter="addTodoItem" />
            <label class="todo-add__urgent">
              <input type="checkbox" v-model="newTodo.urgent" /><span>紧急</span>
            </label>
            <CButton variant="secondary" :disabled="!newTodo.content.trim()" @click="addTodoItem">添加</CButton>
          </div>
        </div>

        <!-- 交接说明 -->
        <div class="section">
          <div class="section__title">交接说明</div>
          <template v-if="isDraft">
            <label class="field-label">重要提醒</label>
            <CTextarea v-model="form.important" placeholder="需要接班人重点关注的客户或事项" />
            <label class="field-label">钱款交接</label>
            <CInput v-model="form.cash" placeholder="现金、备用金、投柜情况" />
            <label class="field-label">设备/物料</label>
            <CInput v-model="form.equipment" placeholder="设备状态、物料盘点情况" />
            <label class="field-label">接班人</label>
            <CInput v-model="form.toName" placeholder="接班人姓名" />
          </template>
          <template v-else>
            <div v-if="selected.importantNote" class="note note--important">
              <CIcon name="alert" :size="14" /><p>{{ selected.importantNote }}</p>
            </div>
            <div v-if="selected.cashNote" class="note">
              <span class="note__label">钱款交接</span><p>{{ selected.cashNote }}</p>
            </div>
            <div v-if="selected.equipmentNote" class="note">
              <span class="note__label">设备/物料</span><p>{{ selected.equipmentNote }}</p>
            </div>
            <div v-if="isConfirmed && selected.confirmNote" class="note note--confirm">
              <CIcon name="check-square" :size="14" /><p>接班备注：{{ selected.confirmNote }}</p>
            </div>
          </template>
        </div>

        <!-- 操作区 -->
        <!-- 草稿：保存 + 提交 -->
        <div v-if="isDraft" class="ops">
          <CButton variant="ghost" v-perm.disable="'handover:create'" @click="saveDraft">保存草稿</CButton>
          <CButton variant="primary" :disabled="!form.toName.trim()" v-perm.disable="'handover:create'" @click="doSubmit">
            <CIcon name="sign" :size="16" />提交交接
          </CButton>
        </div>

        <!-- 待确认：接班人确认 / 退回 -->
        <template v-else-if="isSubmitted">
          <div class="confirm-box">
            <label class="field-label">接班备注（选填）</label>
            <CTextarea v-model="confirmNote" placeholder="确认接收时可填写备注" />
          </div>
          <div v-if="showSendBack" class="inline-box">
            <CInput v-model="sendBackReason" placeholder="请填写退回补充的原因（必填）" />
            <div class="inline-box__btns">
              <CButton variant="ghost" @click="showSendBack = false; sendBackReason = ''">取消</CButton>
              <CButton variant="primary" :disabled="!sendBackReason.trim()" @click="doSendBack">确认退回</CButton>
            </div>
          </div>
          <div class="ops">
            <CButton variant="ghost" v-perm.disable="'handover:edit'" @click="showSendBack = true">退回补充</CButton>
            <CButton variant="primary" v-perm.disable="'handover:edit'" @click="doConfirm">
              <CIcon name="check-square" :size="16" />确认接收
            </CButton>
          </div>
        </template>

        <!-- 已交接：只读提示 -->
        <div v-else class="done-bar">
          <CIcon name="check-square" :size="16" />
          <span>双签交接已完成，内容已锁定留痕。接班人可继续勾选跟进中的待办事项。</span>
        </div>

        <!-- 时间线 -->
        <div class="tl">
          <div class="tl__title">交接轨迹</div>
          <div class="tl__list">
            <div v-for="(e, i) in selected.timeline" :key="i" class="tl__item">
              <div class="tl__dot" />
              <div class="tl__body">
                <div class="tl__action">{{ e.action }}</div>
                <div v-if="e.detail" class="tl__detail">{{ e.detail }}</div>
                <div class="tl__meta">{{ e.by }} · {{ fmtDateTime(e.at) }}</div>
              </div>
            </div>
          </div>
        </div>
      </CCard>

      <CCard v-else class="ho__detail ho__detail--empty" title="交接单详情">
        <div class="detail-empty">
          <CIcon name="handover" :size="40" class="detail-empty__icon" />
          <p>请从左侧选择一张交接单</p>
        </div>
      </CCard>
    </div>

    <!-- 新建交接单弹层 -->
    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <CCard class="modal" title="新建交接单" padding="lg">
        <div class="form">
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">班次</label>
              <CSelect v-model="newHo.shift" width="100%" :options="[
                { value: 'MORNING', label: '早班' },
                { value: 'EVENING', label: '晚班' },
                { value: 'FULL', label: '全天' },
              ]" />
            </div>
            <div>
              <label class="form__label">交班日期</label>
              <input type="date" v-model="newHo.date" class="date-input" />
            </div>
          </div>
          <div class="form__row">
            <label class="form__label">接班人</label>
            <CInput v-model="newHo.toName" placeholder="接班人姓名（必填）" />
          </div>
          <p class="form__hint">创建后进入草稿，填写经营摘要、待跟进事项与交接说明，再提交双签。</p>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showForm = false">取消</CButton>
          <CButton variant="primary" :disabled="!newHo.toName.trim()" @click="createHo">创建并填写</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.ho { display: flex; flex-direction: column; gap: var(--s-lg); }

.ho__kpis { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
.ho__toolbar { display: flex; align-items: center; gap: var(--s-sm); }
.ho__toolbar :deep(.cinput) { flex: 1; min-width: 160px; }
.kpi { display: flex; align-items: center; gap: var(--s-md); background: var(--c-surface); border: 1px solid var(--c-border); border-radius: var(--r-lg); padding: var(--s-md); }
.kpi__icon { width: 40px; height: 40px; border-radius: var(--r-md); display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.kpi__icon--warning { background: var(--c-warning-bg); color: var(--c-warning-fg); }
.kpi__icon--success { background: var(--c-success-bg); color: var(--c-success-fg); }
.kpi__icon--danger { background: var(--c-danger-bg); color: var(--c-danger-fg); }
.kpi__icon--default { background: var(--c-brand-soft); color: var(--c-brand); }
.kpi__label { font-size: var(--t-xs); color: var(--c-text-3); }
.kpi__value { font-size: var(--t-xl); font-weight: 700; color: var(--c-text); line-height: 1.2; }
.kpi__value--warning { color: var(--c-warning-fg); }
.kpi__value--success { color: var(--c-success-fg); }
.kpi__value--danger { color: var(--c-danger-fg); }
.kpi__value--default { color: var(--c-brand); }

.ho__body { display: grid; grid-template-columns: 360px 1fr; gap: var(--s-lg); align-items: start; }

.ho__detail-title { font-size: var(--t-md); line-height: var(--lh-md); font-weight: 700; color: var(--c-text); margin: 0; }
.ho__detail-tags { display: flex; gap: var(--s-xs); align-items: center; }

.tabs { display: flex; border-bottom: 1px solid var(--c-border); }
.tab {
  flex: 1; padding: var(--s-md) var(--s-xs); font-size: var(--t-xs); white-space: nowrap;
  color: var(--c-text-3); background: none; border: none; cursor: pointer;
  border-bottom: 2px solid transparent;
}
.tab--active { color: var(--c-brand); border-bottom-color: var(--c-brand); font-weight: 600; }

.list { max-height: 600px; overflow-y: auto; }
.empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); font-size: var(--t-sm); }
.empty__icon { color: var(--c-text-4); }

.rec {
  display: block; width: 100%; text-align: left; padding: var(--s-md) var(--s-lg);
  background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer;
  border-left: 3px solid transparent;
}
.rec:hover { background: var(--c-brand-soft); }
.rec--active { background: var(--c-brand-soft); }
.rec--pending { border-left-color: var(--c-warning-fg); }
.rec__top { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; }
.rec__shift { font-size: var(--t-xs); font-weight: 600; color: var(--c-brand); background: var(--c-brand-soft); padding: 1px 8px; border-radius: var(--r-capsule); }
.rec__no { font-size: var(--t-xs); color: var(--c-text-4); margin-bottom: 4px; }
.rec__people { display: flex; align-items: center; gap: 4px; font-size: var(--t-sm); color: var(--c-text); margin-bottom: 4px; }
.rec__from { font-weight: 600; }
.rec__arrow { color: var(--c-text-4); }
.rec__to { color: var(--c-text-2); }
.rec__meta { display: flex; justify-content: space-between; align-items: center; font-size: var(--t-xs); color: var(--c-text-3); }
.rec__amt { font-weight: 600; color: var(--c-text); }

/* 双签栏 */
.signbar { display: flex; align-items: center; gap: var(--s-md); padding: var(--s-md); background: var(--c-brand-soft); border-radius: var(--r-lg); margin-bottom: var(--s-lg); }
.signbar__person { display: flex; align-items: center; gap: var(--s-sm); flex: 1; color: var(--c-text-2); position: relative; }
.signbar__person--done { color: var(--c-success-fg); }
.signbar__role { font-size: var(--t-xs); color: var(--c-text-3); }
.signbar__name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.signbar__person--done .signbar__name { color: var(--c-success-fg); }
.signbar__time { font-size: var(--t-xs); color: var(--c-text-4); }
.signbar__check { color: var(--c-success-fg); margin-left: auto; }
.signbar__sep { color: var(--c-text-4); flex-shrink: 0; }

.section { margin-bottom: var(--s-lg); }
.section__title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); margin-bottom: var(--s-md); display: flex; align-items: center; gap: var(--s-sm); }
.section__count { font-size: var(--t-xs); font-weight: 400; color: var(--c-text-3); }

.sum { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--s-md); }
.sum__item { background: var(--c-surface-muted, #f7f8fa); border-radius: var(--r-md); padding: var(--s-md); text-align: center; }
.sum__num { font-size: var(--t-lg); font-weight: 700; color: var(--c-brand); }
.sum__lbl { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }
.sum-edit { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--s-md); }
.sum-edit__item label { display: block; font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: 4px; }

.todos { display: flex; flex-direction: column; gap: var(--s-xs); }
.todo { display: flex; align-items: flex-start; gap: var(--s-sm); padding: var(--s-sm) 0; border-bottom: 1px solid var(--c-border-light); }
.todo:last-child { border-bottom: none; }
.todo__check {
  width: 18px; height: 18px; border-radius: var(--r-sm); border: 1.5px solid var(--c-border);
  background: #fff; display: flex; align-items: center; justify-content: center; cursor: pointer;
  flex-shrink: 0; margin-top: 1px; color: #fff;
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

.field-label { display: block; font-size: var(--t-xs); color: var(--c-text-3); margin: var(--s-md) 0 var(--s-xs); }
.field-label:first-child { margin-top: 0; }

.note { display: flex; gap: var(--s-sm); padding: var(--s-md); border-radius: var(--r-md); background: var(--c-surface-muted, #f7f8fa); font-size: var(--t-sm); color: var(--c-text-2); line-height: 1.6; margin-bottom: var(--s-sm); align-items: flex-start; }
.note p { margin: 0; }
.note__label { font-size: var(--t-xs); color: var(--c-text-3); display: block; margin-bottom: 2px; flex-shrink: 0; }
.note--important { background: var(--c-warning-bg); color: var(--c-warning-fg); }
.note--confirm { background: var(--c-success-bg); color: var(--c-success-fg); }

.confirm-box { margin-bottom: var(--s-md); }

.ops { display: flex; justify-content: flex-end; gap: var(--s-sm); padding-top: var(--s-md); border-top: 1px solid var(--c-border-light); }
.inline-box { margin-bottom: var(--s-md); padding: var(--s-md); background: var(--c-surface-muted, #f7f8fa); border-radius: var(--r-md); display: flex; flex-direction: column; gap: var(--s-sm); }
.inline-box__btns { display: flex; justify-content: flex-end; gap: var(--s-sm); }

.done-bar { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-md); background: var(--c-success-bg); color: var(--c-success-fg); border-radius: var(--r-md); font-size: var(--t-sm); }

.tl { margin-top: var(--s-lg); padding-top: var(--s-lg); border-top: 1px solid var(--c-border-light); }
.tl__title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); margin-bottom: var(--s-md); }
.tl__list { position: relative; padding-left: var(--s-lg); }
.tl__list::before { content: ''; position: absolute; left: 5px; top: 4px; bottom: 4px; width: 2px; background: var(--c-border-light); }
.tl__item { position: relative; padding-bottom: var(--s-md); }
.tl__item:last-child { padding-bottom: 0; }
.tl__dot { position: absolute; left: calc(-1 * var(--s-lg) + 1px); top: 4px; width: 10px; height: 10px; border-radius: 50%; background: var(--c-brand); border: 2px solid var(--c-surface); }
.tl__action { font-size: var(--t-sm); color: var(--c-text); font-weight: 500; }
.tl__detail { font-size: var(--t-xs); color: var(--c-text-2); margin-top: 2px; }
.tl__meta { font-size: var(--t-xs); color: var(--c-text-4); margin-top: 2px; }

.date-input { padding: 10px; border: 1px solid var(--c-border); border-radius: var(--r-md); font-size: var(--t-sm); color: var(--c-text); background: #fff; font-family: inherit; }
.date-input:focus { outline: none; border-color: var(--c-brand); }

.detail-empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-md); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); }
.detail-empty__icon { color: var(--c-text-4); }

.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.form__row--2 { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.form__label { font-size: var(--t-xs); color: var(--c-text-3); }
.form__hint { font-size: var(--t-xs); color: var(--c-text-3); line-height: 1.6; margin: 0; }

.modal-mask { position: fixed; inset: 0; background: rgba(20,21,43,.45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 520px; max-width: 100%; box-shadow: var(--shadow-pop); }

@media (max-width: 1024px) {
  .ho__body { grid-template-columns: 1fr; }
  .ho__kpis { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); grid-auto-columns: auto; }
  .ho__toolbar { flex-wrap: wrap; }
  .ho__toolbar :deep(.cinput) { flex: 1 1 100%; }
  .list { max-height: 320px; }
}
</style>
