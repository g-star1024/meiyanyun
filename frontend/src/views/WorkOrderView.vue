<script setup lang="ts">
/* ============================================================
 * 服务工单 /m2-workorder（M2-08）
 * Tablet 真值：标题+日期、4 KPI、待处理工单列表、超时预警、底部新建 FAB。
 * Desktop 设计稿内容区空白，按项目 list-detail 范式实现：左列表+右详情。
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CWorkbenchShell from '@/components/CWorkbenchShell.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CTextarea from '@/components/CTextarea.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CFab from '@/components/CFab.vue'
import { useAuthStore } from '@/stores/auth'
import { useWorkOrderStore, type WorkOrder, type WorkOrderType } from '@/stores/workorder'
import { WORK_ORDER_STATUS, WORK_ORDER_PRIORITY, dictPill, type WorkOrderPriority } from '@/config/dictionary'
import { ALL_STAFF } from '@/config/staff'
import { useToast } from '@/composables/useToast'

const auth = useAuthStore()
const store = useWorkOrderStore()
const toast = useToast()
onMounted(() => store.seed())

const selectedId = ref<string | null>(null)
const selected = computed<WorkOrder | null>(() => {
  if (selectedId.value) return store.get(selectedId.value) ?? null
  return store.filtered[0] ?? null
})

const kpis = computed(() => [
  { label: '待服务', icon: 'order', value: String(store.pending.length), tone: 'warning' as const },
  { label: '进行中', icon: 'dashboard', value: String(store.inProgress.length), tone: 'brand' as const },
  { label: '已完成', icon: 'dashboard', value: String(store.done.length), tone: 'success' as const },
  { label: '超时预警', icon: 'alert', value: String(store.overdue.length), tone: 'danger' as const },
])

const typeOptions = [
  { value: 'ALL', label: '全部类型' },
  { value: 'REPAIR', label: '报修' },
  { value: 'INSPECTION', label: '巡检' },
  { value: 'CUSTOMER', label: '客诉' },
  { value: 'CONSULT', label: '咨询' },
]
const statusOptions = [
  { value: 'ALL', label: '全部状态' },
  { value: 'PENDING', label: '待服务' },
  { value: 'IN_PROGRESS', label: '进行中' },
  { value: 'DONE', label: '已完成' },
  { value: 'ESCALATED', label: '已升级' },
]


function fmtTime(iso?: string) {
  if (!iso) return '—'
  const d = new Date(iso)
  return `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}
function overdueText(deadline: string) {
  const min = Math.round((Date.now() - new Date(deadline).getTime()) / 60000)
  if (min < 60) return `超 ${min} 分钟`
  return `超 ${Math.floor(min / 60)} 小时${min % 60 ? min % 60 + ' 分钟' : ''}`
}

// 新建工单
const showForm = ref(false)
// 默认截止时间：当前 +4 小时（datetime-local 本地时区格式）
function defaultDeadline() {
  const d = new Date(Date.now() + 4 * 3600_000)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`
}
const emptyForm = () => ({
  type: 'REPAIR' as WorkOrderType,
  title: '',
  description: '',
  customerName: '',
  project: '',
  room: '',
  priority: 'MEDIUM' as WorkOrderPriority,
  deadline: defaultDeadline(),
  assigneeId: '',
})
const form = ref(emptyForm())
const assigneeOptions = [
  { value: '', label: '暂不指派（待分配）' },
  ...ALL_STAFF.map((s) => ({ value: s.name, label: `${s.name}（${s.title}）` })),
]
const canSubmit = computed(() => form.value.title.trim() && form.value.description.trim())
function submitForm() {
  if (!canSubmit.value) return
  const f = form.value
  const o = store.create({
    type: f.type,
    title: f.title,
    description: f.description,
    customerName: f.customerName.trim() || undefined,
    project: f.project.trim() || undefined,
    room: f.room.trim() || undefined,
    priority: f.priority,
    deadline: f.deadline || undefined,
    assignee: f.assigneeId || undefined,
  })
  if (o) {
    showForm.value = false
    form.value = emptyForm()
    selectedId.value = o.id
    toast.success('工单已创建')
  }
}

// 操作
function doStart() { if (selected.value) { store.start(selected.value.id); toast.success('工单已开始处理') } }
function doComplete() { if (selected.value) { store.complete(selected.value.id, '已按要求处理完毕'); toast.success('工单已完成并关闭') } }
function doEscalate() { if (selected.value) { store.escalate(selected.value.id, '问题复杂，需店长介入'); toast.warning('工单已升级，请店长协调') } }

// 确认弹层
const confirm = ref<{show:boolean; title:string; action:()=>void} | null>(null)
function ask(title: string, action: () => void) {
  confirm.value = { show: true, title, action }
}
function runConfirm() {
  confirm.value?.action()
  confirm.value = null
}
</script>

<template>
  <div class="wo">
    <CWorkbenchShell
      :has-selection="!!selected"
      empty-icon="tool"
      empty-title="请选择一条工单"
      empty-desc="待服务工单可开始处理或升级；完成后关闭工单，处理记录留痕"
      list-width="400px"
    >
      <template #kpis>
        <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
      </template>

      <!-- 左：列表 + 筛选 -->
      <template #list>
        <div class="filters">
          <CSelect v-model="store.filterType" :options="typeOptions" />
          <CSelect v-model="store.filterStatus" :options="statusOptions" />
        </div>
        <div class="list">
          <div v-if="store.filtered.length === 0" class="empty">
            <CIcon :name="('tool' as any)" :size="28" class="empty__icon" />
            <div>暂无工单</div>
          </div>
          <button
            v-for="o in store.filtered" :key="o.id"
            class="row" :class="{ 'row--active': selected?.id === o.id }"
            @click="selectedId = o.id"
          >
            <div class="row__top">
              <span class="row__no">{{ o.woNo }}</span>
              <CStatusPill :status="dictPill(WORK_ORDER_STATUS[o.status]).status">{{ dictPill(WORK_ORDER_STATUS[o.status]).text }}</CStatusPill>
            </div>
            <div class="row__title">{{ o.title }}</div>
            <div class="row__meta">
              <span><CIcon :name="(store.TYPE_ICON[o.type] as any)" :size="12" /> {{ store.TYPE_LABEL[o.type] }}</span>
              <span v-if="o.customerName">{{ o.customerName }}</span>
              <span v-if="o.project">{{ o.project }}</span>
            </div>
            <div v-if="store.overdue.some(x => x.id === o.id)" class="row__alert">
              <CIcon name="alert" :size="12" /> {{ overdueText(o.deadline) }}
            </div>
          </button>
          <CFab
            :actions="[{ icon: 'plus', label: '新建工单', disabled: !auth.can('workorder:create'), onClick: () => { showForm = true } }]"
          />
        </div>
      </template>

      <!-- 右：详情 -->
      <template #head>
        <div v-if="selected" class="wb-head">
          <h3 class="wo__detail-title">{{ selected.woNo }}</h3>
          <CStatusPill :status="dictPill(WORK_ORDER_STATUS[selected.status]).status">{{ dictPill(WORK_ORDER_STATUS[selected.status]).text }}</CStatusPill>
        </div>
      </template>

      <template v-if="selected">
        <div class="detail__head">
          <div>
            <div class="detail__title">{{ selected.title }}</div>
            <div class="detail__sub">
              <span class="tag tag--type"><CIcon :name="(store.TYPE_ICON[selected.type] as any)" :size="12" /> {{ store.TYPE_LABEL[selected.type] }}</span>
              <span class="tag tag--priority">优先级 {{ WORK_ORDER_PRIORITY[selected.priority]?.label }}</span>
              <span v-if="selected.room" class="tag">{{ selected.room }}</span>
            </div>
          </div>
          <div class="detail__assign">
            <div class="detail__assign-label">负责人</div>
            <div class="detail__assign-name">{{ selected.assignee }}</div>
          </div>
        </div>

        <div class="detail__grid">
          <div class="field"><span class="field__label">客户</span><span class="field__val">{{ selected.customerName || '—' }}</span></div>
          <div class="field"><span class="field__label">项目/设备</span><span class="field__val">{{ selected.project || '—' }}</span></div>
          <div class="field"><span class="field__label">创建时间</span><span class="field__val">{{ fmtTime(selected.createdAt) }}</span></div>
          <div class="field"><span class="field__label">截止时间</span><span class="field__val" :class="{ 'is-overdue': store.overdue.some(x => x.id === selected?.id) }">{{ fmtTime(selected.deadline) }}</span></div>
        </div>

        <div class="detail__desc">
          <div class="detail__sec-title">问题描述</div>
          <p>{{ selected.description }}</p>
        </div>

        <div class="detail__notes">
          <div class="detail__sec-title">处理记录</div>
          <div v-for="(n, i) in selected.notes" :key="i" class="note">
            <span class="note__who">{{ n.by }}</span>
            <span class="note__text">{{ n.text }}</span>
            <span class="note__time">{{ fmtTime(n.at) }}</span>
          </div>
        </div>
      </template>

      <template #foot>
        <template v-if="selected">
          <template v-if="selected.status === 'PENDING'">
            <CButton variant="ghost" v-perm.disable="'workorder:edit'" @click="doEscalate">升级</CButton>
            <CButton variant="primary" v-perm.disable="'workorder:edit'" @click="ask('确认开始处理？', doStart)">
              <CIcon name="check" :size="16" />开始处理
            </CButton>
          </template>
          <template v-else-if="selected.status === 'IN_PROGRESS'">
            <CButton variant="ghost" v-perm.disable="'workorder:edit'" @click="doEscalate">升级</CButton>
            <CButton variant="primary" v-perm.disable="'workorder:close'" @click="ask('确认完成并关闭工单？', doComplete)">
              <CIcon name="check" :size="16" />完成工单
            </CButton>
          </template>
          <template v-else-if="selected.status === 'ESCALATED'">
            <CButton variant="ghost" v-perm.disable="'workorder:close'" @click="doComplete">标记完成</CButton>
            <span class="ops__hint">已升级，请店长协调处理</span>
          </template>
          <template v-else>
            <span class="wbs-foot-done">
              <CIcon name="check" :size="15" />已于 {{ fmtTime(selected.completedAt) }} 完成
            </span>
          </template>
        </template>
      </template>
    </CWorkbenchShell>

    <!-- 新建工单弹层 -->
    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <CCard class="modal" title="新建工单" padding="lg">
        <div class="form">
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">工单类型</label>
              <CSelect v-model="form.type" :options="[
                {value:'REPAIR',label:'报修'},{value:'INSPECTION',label:'巡检'},{value:'CUSTOMER',label:'客诉'},{value:'CONSULT',label:'咨询'}
              ]" />
            </div>
            <div>
              <label class="form__label">优先级</label>
              <CSelect v-model="form.priority" :options="[
                {value:'HIGH',label:'高'},{value:'MEDIUM',label:'中'},{value:'LOW',label:'低'}
              ]" />
            </div>
          </div>
          <div class="form__row">
            <label class="form__label">标题</label>
            <CInput v-model="form.title" placeholder="如：超声刀治疗仪报修" />
          </div>
          <div class="form__row">
            <label class="form__label">问题描述</label>
            <CTextarea v-model="form.description" placeholder="请描述具体问题" />
          </div>
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">客户姓名（可选）</label>
              <CInput v-model="form.customerName" placeholder="如：陈美玲" />
            </div>
            <div>
              <label class="form__label">项目/设备（可选）</label>
              <CInput v-model="form.project" placeholder="如：超声刀治疗仪" />
            </div>
          </div>
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">位置/房间（可选）</label>
              <CInput v-model="form.room" placeholder="如：A03" />
            </div>
            <div>
              <label class="form__label">截止时间</label>
              <input type="datetime-local" v-model="form.deadline" class="date-input" />
            </div>
          </div>
          <div class="form__row">
            <label class="form__label">负责人</label>
            <CSelect v-model="form.assigneeId" width="100%" :options="assigneeOptions" />
          </div>
          <p class="form__tip">截止时间用于超时预警：到点未完成的工单将在 KPI「超时预警」标红提醒。</p>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showForm = false">取消</CButton>
          <CButton variant="primary" :disabled="!canSubmit" @click="submitForm">提交</CButton>
        </template>
      </CCard>
    </div>

    <!-- 确认弹层 -->
    <div v-if="confirm?.show" class="modal-mask" @click.self="confirm = null">
      <CCard class="modal modal--sm" title="确认操作" padding="lg">
        <p class="confirm__text">{{ confirm.title }}</p>
        <template #footer>
          <CButton variant="ghost" @click="confirm = null">取消</CButton>
          <CButton variant="primary" @click="runConfirm">确认</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.wo { display: flex; flex-direction: column; gap: var(--s-lg); }

.filters { display: flex; gap: var(--s-sm); align-items: center; padding: var(--s-md); border-bottom: 1px solid var(--c-border-light); flex-shrink: 0; }
.list { flex: 1; min-height: 0; overflow-y: auto; display: flex; flex-direction: column; }
.empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); font-size: var(--t-sm); }
.empty__icon { color: var(--c-text-4); }

.row {
  display: block; width: 100%; text-align: left; padding: var(--s-md) var(--s-lg);
  background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer;
}
.row:hover { background: var(--c-brand-soft); }
.row--active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.row__top { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--s-xs); }
.row__no { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.row__title { font-size: var(--t-sm); color: var(--c-text); margin-bottom: var(--s-xs); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.row__meta { display: flex; flex-wrap: wrap; gap: var(--s-xs); font-size: var(--t-xs); color: var(--c-text-3); align-items: center; }
.row__meta span { display: inline-flex; align-items: center; gap: 3px; }
.row__alert { display: inline-flex; align-items: center; gap: 4px; font-size: var(--t-xs); color: var(--c-danger-fg); margin-top: var(--s-xs); }

.wo__detail-title { font-size: var(--t-md); font-weight: 700; margin: 0; }
.wb-head { display: flex; justify-content: space-between; align-items: center; gap: var(--s-sm); }
.detail__head { display: flex; justify-content: space-between; gap: var(--s-md); padding-bottom: var(--s-md); border-bottom: 1px solid var(--c-border-light); }
.detail__title { font-size: var(--t-lg); font-weight: 700; color: var(--c-text); }
.detail__sub { display: flex; flex-wrap: wrap; gap: var(--s-xs); margin-top: var(--s-xs); }
.tag { font-size: var(--t-xs); padding: 2px 8px; border-radius: var(--r-sm); background: var(--c-surface-muted, #f0f2f5); color: var(--c-text-2); }
.tag--type { background: var(--c-brand-soft); color: var(--c-brand); }
.tag--priority { background: var(--c-warning-bg); color: var(--c-warning-fg); }
.detail__assign { text-align: right; }
.detail__assign-label { font-size: var(--t-xs); color: var(--c-text-3); }
.detail__assign-name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }

.detail__grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md) var(--s-lg); margin: var(--s-lg) 0; }
.field { display: flex; flex-direction: column; gap: 2px; }
.field__label { font-size: var(--t-xs); color: var(--c-text-3); }
.field__val { font-size: var(--t-sm); color: var(--c-text); }
.is-overdue { color: var(--c-danger-fg); font-weight: 600; }

.detail__desc, .detail__notes { margin-bottom: var(--s-lg); }
.detail__sec-title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); margin-bottom: var(--s-sm); }
.detail__desc p { font-size: var(--t-sm); color: var(--c-text-2); line-height: var(--lh-md); margin: 0; }

.note { display: flex; gap: var(--s-sm); align-items: baseline; padding: var(--s-xs) 0; border-bottom: 1px solid var(--c-border-light); font-size: var(--t-sm); }
.note:last-child { border-bottom: none; }
.note__who { font-weight: 600; color: var(--c-text); flex-shrink: 0; }
.note__text { color: var(--c-text-2); flex: 1; }
.note__time { font-size: var(--t-xs); color: var(--c-text-3); flex-shrink: 0; }

.ops__hint { font-size: var(--t-sm); color: var(--c-text-3); margin-right: auto; }
.wbs-foot-done { display: inline-flex; align-items: center; gap: var(--s-xs); color: var(--c-success-fg); font-size: var(--t-sm); font-weight: 600; margin-right: auto; }

.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 560px; max-width: 100%; max-height: 90vh; overflow-y: auto; box-shadow: var(--shadow-pop); }
.modal--sm { width: 360px; }
.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.form__row--2 { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.form__label { font-size: var(--t-xs); color: var(--c-text-3); }
.date-input { width: 100%; box-sizing: border-box; padding: 9px var(--s-sm); border: 1px solid var(--c-border); border-radius: var(--r-md); font-size: var(--t-sm); color: var(--c-text); background: var(--c-surface); font-family: inherit; }
.date-input:focus { outline: none; border-color: var(--c-brand); }
.form__tip { margin: 0; font-size: var(--t-xs); color: var(--c-text-2); line-height: 1.5; background: var(--c-brand-soft); border-radius: var(--r-md); padding: var(--s-sm) var(--s-md); }
.confirm__text { font-size: var(--t-sm); color: var(--c-text); text-align: center; margin: var(--s-md) 0; }

@media (max-width: 1024px) {
  .detail__head { flex-direction: column; gap: var(--s-sm); }
  .detail__assign { text-align: left; }
}
</style>
