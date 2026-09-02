<script setup lang="ts">
/* ============================================================
 * 审批中心 /approval（T3-01 统一双签引擎）
 * 数据源：txn-service 真实 API（/txn/approval 列表 +
 *   /{todoNo}/approve|reject|transfer|add-signer 动作）。
 * 后端实体：金额单位「分」，history 为 JSON 串、coSigners 为逗号串，
 *   applicant/assignee/history.actor 为员工工号（适配层解析中文姓名）。
 * 「我的待办」按当前登录人权限 + 指派人前端判定（后端只按 tab 过滤）。
 * 样式/模板沿用原版，仅替换数据源（mock approval store → 真实 API）。
 * ============================================================ */
import { computed, ref, onMounted } from 'vue'
import type { ApprovalTask, ApprovalAction, ApprovalBizType, ApprovalStage, SignTier } from '@/stores/approval'
import { useAuthStore } from '@/stores/auth'
import { useToast } from '@/composables/useToast'
import {
  listApprovals, approveTodo, rejectTodo, transferTodo, addSignerTodo,
  type ApprovalTodoDTO,
} from '@/api/approval'
import { staffName } from '@/config/staff'
import CKpi from '@/components/CKpi.vue'
import CCard from '@/components/CCard.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CButton from '@/components/CButton.vue'
import CTextarea from '@/components/CTextarea.vue'
import CDrawer from '@/components/CDrawer.vue'
import CSelect from '@/components/CSelect.vue'
import CIcon from '@/components/CIcon.vue'

const auth = useAuthStore()
const toast = useToast()

// ---- 业务类型中文标签 / 当前阶段权限码（与 stores/approval.ts 保持一致） ----
const BIZ_LABEL: Record<ApprovalBizType, string> = {
  REFUND: '订单退款',
  CARD_CANCEL: '退卡',
  TRANSFER: '资产转移',
  LEAVE: '请假审批',
  PROCUREMENT: '采购申请',
  PRICE_CHANGE: '价格变更',
  LOSS_REPORT: '损耗报损',
  REQUISITION: '物料申领',
}
const BIZ_PERM: Record<ApprovalBizType, string> = {
  REFUND: 'refund:approve',
  CARD_CANCEL: 'cardcancel:approve',
  TRANSFER: 'transfer:approve',
  LEAVE: 'schedule:approve',
  PROCUREMENT: 'inventory:approve',
  PRICE_CHANGE: 'brand:approve',
  LOSS_REPORT: 'inventory:approve',
  REQUISITION: 'inventory:approve',
}

// 工号 → 中文姓名补全：E/SE 真实工号已由 config/staff.ts ROSTER 覆盖；
// 此处仅保留离线 mock 旧 ID 与系统占位。
const STAFF_NAME_EXTRA: Record<string, string> = {
  'staff-zhou': '周岚', 'staff-chen': '陈野', 'staff-xia': '夏沫',
  'staff-bai': '白桥', 'staff-qian': '钱进', cashier: '前台收银', system: '系统',
}
function nameOf(id?: string | null): string {
  if (!id) return '—'
  if (STAFF_NAME_EXTRA[id]) return STAFF_NAME_EXTRA[id]
  const n = staffName(id)
  return n === id ? id : n
}

const fen2yuan = (f: number | null | undefined) => (f == null ? 0 : f / 100)

function stagePerm(t: ApprovalTask): string {
  if (t.stage === 'FINANCE') {
    if (t.bizType === 'REFUND') return 'refund:sign'
    if (t.bizType === 'CARD_CANCEL') return 'cardcancel:sign'
    if (t.bizType === 'TRANSFER') return 'transfer:edit'
  }
  return BIZ_PERM[t.bizType as ApprovalBizType] || 'approval:view'
}

function adapt(d: ApprovalTodoDTO): ApprovalTask {
  let history: ApprovalAction[] = []
  try {
    const parsed = d.history ? JSON.parse(d.history) : []
    if (Array.isArray(parsed)) {
      history = parsed.map((h: any) => ({
        actor: nameOf(h?.actor),
        action: (h?.action || 'SUBMIT') as ApprovalAction['action'],
        comment: h?.comment || '',
        at: h?.at || d.submittedAt || '',
      }))
    }
  } catch {
    history = []
  }
  return {
    id: d.todoNo,
    bizType: d.bizType as ApprovalBizType,
    bizNo: d.bizNo,
    title: d.title,
    summary: d.summary,
    amount: d.amount != null ? fen2yuan(d.amount) : undefined,
    applicant: nameOf(d.applicant),
    applicantRole: d.applicantRole || 'OPERATOR',
    signTier: (d.signTier as SignTier) || 'L1',
    status: d.status as ApprovalTask['status'],
    stage: (d.stage as ApprovalStage) || 'REVIEW',
    priority: (d.priority as ApprovalTask['priority']) || 'MEDIUM',
    storeName: d.storeName || '默认门店',
    submittedAt: d.submittedAt || '',
    dueAt: d.dueAt || undefined,
    assignee: d.assignee || undefined,
    coSigners: d.coSigners ? d.coSigners.split(',').filter(Boolean) : [],
    history,
  }
}

const tasks = ref<ApprovalTask[]>([])
const tab = ref<'todo' | 'done' | 'all'>('todo')
const filterType = ref<ApprovalBizType | 'ALL'>('ALL')

async function load() {
  try {
    const res = await listApprovals({ tab: 'all' })
    tasks.value = res.data.map(adapt)
  } catch (e: any) {
    toast.error('审批数据加载失败：' + (e?.response?.data?.message || e?.message || '网络异常'))
  }
}

onMounted(load)

const todo = computed(() => tasks.value.filter((t) => t.status === 'PENDING'))
const done = computed(() => tasks.value.filter((t) => t.status !== 'PENDING'))
const overdue = computed(() => {
  const now = Date.now()
  return todo.value.filter((t) => t.dueAt && new Date(t.dueAt).getTime() < now)
})
/** 当前用户可处理的待办（有当前阶段对应权限，且未指派给他人） */
const myTodo = computed(() =>
  todo.value.filter((t) => auth.can(stagePerm(t)) && (!t.assignee || t.assignee === auth.user.name)),
)
const filtered = computed(() => {
  const base = tab.value === 'todo' ? myTodo.value : tab.value === 'done' ? done.value : tasks.value
  if (filterType.value === 'ALL') return base
  return base.filter((t) => t.bizType === filterType.value)
})

function get(id: string) {
  return tasks.value.find((t) => t.id === id)
}
function bizLabel(t: ApprovalBizType | string) {
  return BIZ_LABEL[t as ApprovalBizType] || t
}
function permFor(t: ApprovalTask) {
  return stagePerm(t)
}

const actor = () => auth.user.staffId || 'cashier'

async function approve(id: string, commentValue = '同意'): Promise<boolean> {
  try {
    await approveTodo(id, { actor: actor(), comment: commentValue })
    await load()
    toast.success('审批通过')
    return true
  } catch (e: any) {
    toast.error('审批失败：' + (e?.response?.data?.message || e?.message || '网络异常'))
    return false
  }
}
async function reject(id: string, reason: string): Promise<boolean> {
  try {
    await rejectTodo(id, { actor: actor(), comment: reason })
    await load()
    toast.success('已驳回')
    return true
  } catch (e: any) {
    toast.error('驳回失败：' + (e?.response?.data?.message || e?.message || '网络异常'))
    return false
  }
}
async function transfer(id: string, to: string, commentValue = ''): Promise<boolean> {
  try {
    await transferTodo(id, { actor: actor(), to, comment: commentValue || undefined })
    await load()
    toast.success(`已转交给 ${to}`)
    return true
  } catch (e: any) {
    toast.error('转交失败：' + (e?.response?.data?.message || e?.message || '网络异常'))
    return false
  }
}
async function addSigner(id: string, who: string): Promise<boolean> {
  try {
    await addSignerTodo(id, { actor: actor(), who })
    await load()
    toast.success(`已加签 ${who}`)
    return true
  } catch (e: any) {
    toast.error('加签失败：' + (e?.response?.data?.message || e?.message || '网络异常'))
    return false
  }
}

// 模板原绑定 ap.xxx：同名对象保持形状一致（tab/filterType 需可写，故提供 setter）
const ap = {
  BIZ_LABEL,
  get tasks() { return tasks.value },
  get tab() { return tab.value },
  set tab(v: 'todo' | 'done' | 'all') { tab.value = v },
  get filterType() { return filterType.value },
  set filterType(v: ApprovalBizType | 'ALL') { filterType.value = v },
  get myTodo() { return myTodo.value },
  get overdue() { return overdue.value },
  get done() { return done.value },
  get filtered() { return filtered.value },
  get, bizLabel, permFor, approve, reject, transfer, addSigner,
}

const tabs = computed<{ label: string; value: 'todo' | 'done' | 'all' }[]>(() => [
  { label: `待我审批 (${ap.myTodo.length})`, value: 'todo' },
  { label: '已办结', value: 'done' },
  { label: '全部', value: 'all' },
])

const typeOptions = [
  { label: '全部类型', value: 'ALL' },
  ...(Object.keys(ap.BIZ_LABEL) as ApprovalBizType[]).map((k) => ({ label: ap.BIZ_LABEL[k], value: k })),
]

const selectedId = ref<string | null>(null)
const selected = computed<ApprovalTask | undefined>(() =>
  ap.tasks.find((t) => t.id === selectedId.value),
)

function selectTask(t: ApprovalTask) {
  selectedId.value = t.id
}

const kpi = computed(() => ({
  todo: ap.myTodo.length,
  overdue: ap.overdue.length,
  high: ap.myTodo.filter((t) => t.priority === 'HIGH').length,
  done30: ap.done.length,
}))

// 审批操作
const rejectOpen = ref(false)
const transferOpen = ref(false)
const addSignOpen = ref(false)
const comment = ref('')
const transferTo = ref('')
const addSignWho = ref('')

const staffOptions = [
  { label: '陈雅琳（店长）', value: '陈雅琳（店长）' },
  { label: '王财务', value: '王财务' },
  { label: '张磊（区域经理）', value: '张磊（区域经理）' },
  { label: '林主任（医务）', value: '林主任（医务）' },
]

function canHandle(t: ApprovalTask) {
  return t.status === 'PENDING' && auth.can(ap.permFor(t))
}

async function doApprove() {
  if (selected.value) {
    const ok = await ap.approve(selected.value.id, comment.value || '同意')
    if (ok) comment.value = ''
  }
}
function openReject() {
  comment.value = ''
  rejectOpen.value = true
}
async function doReject() {
  if (selected.value && comment.value.trim()) {
    const ok = await ap.reject(selected.value.id, comment.value)
    if (ok) {
      rejectOpen.value = false
      comment.value = ''
    }
  }
}
async function doTransfer() {
  if (selected.value && transferTo.value) {
    const ok = await ap.transfer(selected.value.id, transferTo.value, comment.value)
    if (ok) {
      transferOpen.value = false
      transferTo.value = ''
      comment.value = ''
    }
  }
}
async function doAddSign() {
  if (selected.value && addSignWho.value) {
    const ok = await ap.addSigner(selected.value.id, addSignWho.value)
    if (ok) {
      addSignOpen.value = false
      addSignWho.value = ''
    }
  }
}

function statusPill(t: ApprovalTask) {
  if (t.status === 'APPROVED') return { status: 'success' as const, text: '已通过' }
  if (t.status === 'REJECTED') return { status: 'danger' as const, text: '已驳回' }
  if (t.status === 'TRANSFERRED') return { status: 'info' as const, text: '已转交' }
  if (t.stage === 'FINANCE') return { status: 'warning' as const, text: '待财务复核' }
  return { status: 'primary' as const, text: '待审批' }
}

function priorityPill(p: ApprovalTask['priority']) {
  if (p === 'HIGH') return { status: 'danger' as const, text: '高' }
  if (p === 'MEDIUM') return { status: 'warning' as const, text: '中' }
  return { status: 'default' as const, text: '低' }
}

function fmtDate(iso: string) {
  const d = new Date(iso)
  return `${d.getMonth() + 1}/${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

function actionLabel(a: string) {
  return { SUBMIT: '提交', APPROVE: '通过', REJECT: '驳回', TRANSFER: '转交', ADD_SIGN: '加签' }[a] || a
}
</script>

<template>
  <div class="ap">
    <!-- KPI -->
    <div class="ap__kpis">
      <CKpi :value="String(kpi.todo)" label="待我审批" tone="brand" icon="check-square" />
      <CKpi :value="String(kpi.overdue)" label="即将逾期/逾期" tone="danger" icon="alert" />
      <CKpi :value="String(kpi.high)" label="高优先级" tone="danger" icon="check-square" />
      <CKpi :value="String(kpi.done30)" label="已办结" tone="success" icon="check-square" />
    </div>

    <div class="ap__body">
      <!-- 左：任务列表 -->
      <CCard padding="none" class="ap__list">
        <div class="ap__tabs">
          <button v-for="t in tabs" :key="t.value"
                  :class="{ 'is-active': ap.tab === t.value }"
                  @click="ap.tab = t.value">{{ t.label }}</button>
        </div>
        <div class="ap__filter">
          <CSelect v-model="ap.filterType" :options="typeOptions" width="100%" />
        </div>
        <div class="ap__rows">
          <div v-for="t in ap.filtered" :key="t.id"
               class="task" :class="{ 'is-active': selectedId === t.id, 'is-done': t.status !== 'PENDING' }"
               @click="selectTask(t)">
            <div class="task__top">
              <span class="task__type">{{ ap.bizLabel(t.bizType) }}</span>
              <CStatusPill :status="statusPill(t).status" dot>{{ statusPill(t).text }}</CStatusPill>
            </div>
            <div class="task__title">{{ t.title }}</div>
            <div class="task__meta">
              <span><CIcon name="profile" :size="12" /> {{ t.applicant }}</span>
              <span v-if="t.amount"><CIcon name="finance" :size="12" /> ¥{{ t.amount.toLocaleString() }}</span>
            </div>
            <div class="task__foot">
              <span class="tier tier--{{ t.signTier.toLowerCase() }}">{{ t.signTier }}</span>
              <CStatusPill :status="priorityPill(t.priority).status">{{ priorityPill(t.priority).text }}优先</CStatusPill>
              <span class="task__time">{{ fmtDate(t.submittedAt) }}</span>
            </div>
          </div>
          <div v-if="ap.filtered.length === 0" class="empty">暂无审批任务</div>
        </div>
      </CCard>

      <!-- 右：详情 -->
      <CCard v-if="selected" padding="lg" class="ap__detail">
        <div class="det__head">
          <div>
            <div class="det__type">{{ ap.bizLabel(selected.bizType) }} · {{ selected.bizNo }}</div>
            <h3 class="det__title">{{ selected.title }}</h3>
            <div class="det__sub">
              <span><CIcon name="profile" :size="12" /> 申请人 {{ selected.applicant }}（{{ selected.applicantRole }}）</span>
              <span><CIcon name="store" :size="12" /> {{ selected.storeName }}</span>
              <span><CIcon name="clock" :size="12" /> {{ fmtDate(selected.submittedAt) }}</span>
            </div>
          </div>
          <div class="det__badges">
            <CStatusPill :status="statusPill(selected).status">{{ statusPill(selected).text }}</CStatusPill>
            <CStatusPill :status="priorityPill(selected.priority).status">{{ priorityPill(selected.priority).text }}优先级</CStatusPill>
          </div>
        </div>

        <div class="det__summary">{{ selected.summary }}</div>

        <div v-if="selected.amount" class="det__amount">
          <span class="det__amount-l">审批金额</span>
          <span class="det__amount-v">¥{{ selected.amount.toLocaleString() }}</span>
          <span class="det__amount-tier">签署层级 <b>{{ selected.signTier }}</b></span>
        </div>

        <!-- 审批操作区 -->
        <div v-if="canHandle(selected)" class="det__ops">
          <CTextarea v-model="comment" label="审批意见" placeholder="请输入审批意见（驳回时必填）" :rows="2" />
          <div class="det__ops-row">
            <CButton variant="secondary" size="sm" @click="addSignOpen = true">
              <CIcon name="user-check" :size="14" /> 加签
            </CButton>
            <CButton variant="ghost" size="sm" @click="transferOpen = true">
              <CIcon name="handover" :size="14" /> 转交
            </CButton>
            <CButton variant="danger" size="sm" @click="openReject">
              <CIcon name="close" :size="14" /> 驳回
            </CButton>
            <CButton variant="primary" size="sm" @click="doApprove">
              <CIcon name="check" :size="14" /> 同意
            </CButton>
          </div>
          <p class="det__ops-hint">
            <CIcon name="shield" :size="13" />
            {{ selected.stage === 'FINANCE' ? '当前为财务复核阶段，通过即终审；操作将写入审计日志。' : '一审通过后将流转至财务复核；L3 金额需双签留痕。' }}
          </p>
        </div>
        <div v-else-if="selected.status === 'PENDING'" class="det__readonly">
          <CStatusPill status="disabled">当前角色无该阶段审批权限（{{ ap.permFor(selected) }}）</CStatusPill>
        </div>

        <!-- 审批历史 -->
        <div class="det__history-h">
          <CIcon name="order" :size="14" /> 审批流转记录
        </div>
        <div class="timeline">
          <div v-for="(h, i) in selected.history" :key="i" class="tl">
            <div class="tl__dot" :class="'tl__dot--' + h.action.toLowerCase()"></div>
            <div class="tl__body">
              <div class="tl__top">
                <span class="tl__actor">{{ h.actor }}</span>
                <span class="tl__action" :class="'tl__action--' + h.action.toLowerCase()">{{ actionLabel(h.action) }}</span>
                <span class="tl__time">{{ fmtDate(h.at) }}</span>
              </div>
              <div v-if="h.comment" class="tl__comment">{{ h.comment }}</div>
            </div>
          </div>
        </div>
      </CCard>

      <CCard v-else padding="lg" class="ap__detail ap__detail--empty">
        <div class="empty-big">
          <CIcon name="check-square" :size="40" />
          <p>从左侧选择一条审批任务查看详情</p>
        </div>
      </CCard>
    </div>

    <!-- 驳回弹窗 -->
    <CDrawer v-model:show="rejectOpen" title="驳回审批" size="sm">
      <CTextarea v-model="comment" label="驳回原因（必填）" placeholder="请说明驳回原因，将通知申请人" :rows="4" />
      <div class="drawer__ops">
        <CButton variant="ghost" size="sm" @click="rejectOpen = false">取消</CButton>
        <CButton variant="danger" size="sm" :disabled="!comment.trim()" @click="doReject">确认驳回</CButton>
      </div>
    </CDrawer>

    <!-- 转交弹窗 -->
    <CDrawer v-model:show="transferOpen" title="转交审批" size="sm">
      <CSelect v-model="transferTo" :options="staffOptions" width="100%" />
      <CTextarea v-model="comment" label="转交说明" placeholder="可选" :rows="2" />
      <div class="drawer__ops">
        <CButton variant="ghost" size="sm" @click="transferOpen = false">取消</CButton>
        <CButton variant="primary" size="sm" :disabled="!transferTo" @click="doTransfer">确认转交</CButton>
      </div>
    </CDrawer>

    <!-- 加签弹窗 -->
    <CDrawer v-model:show="addSignOpen" title="加签会签人" size="sm">
      <CSelect v-model="addSignWho" :options="staffOptions" width="100%" />
      <p class="drawer__hint">加签人将共同参与审批，不改变当前审批流程。</p>
      <div class="drawer__ops">
        <CButton variant="ghost" size="sm" @click="addSignOpen = false">取消</CButton>
        <CButton variant="primary" size="sm" :disabled="!addSignWho" @click="doAddSign">确认加签</CButton>
      </div>
    </CDrawer>
  </div>
</template>

<style scoped>
.ap { display: flex; flex-direction: column; gap: var(--s-lg); }
.ap__kpis { display: flex; gap: var(--s-md); flex-wrap: wrap; }
.ap__kpis :deep(.ckpi) { flex: 1 1 0; min-width: 168px; }

.ap__body { display: grid; grid-template-columns: 380px 1fr; gap: var(--s-lg); align-items: start; }

/* 列表 */
.ap__tabs { display: flex; gap: var(--s-xs); padding: var(--s-sm) var(--s-sm) 0; }
.ap__tabs button { flex: 1; border: none; background: none; padding: var(--s-xs) var(--s-sm); border-radius: var(--r-sm); font-size: var(--t-sm); color: var(--c-text-2); cursor: pointer; transition: background .15s; }
.ap__tabs button:hover { background: var(--c-surface-muted, #f7f8fa); }
.ap__tabs button.is-active { background: var(--c-brand-soft); color: var(--c-brand); font-weight: 600; }
.ap__filter { padding: var(--s-sm); border-bottom: 1px solid var(--c-border); }
.ap__rows { max-height: 640px; overflow-y: auto; }
.task { padding: var(--s-md) var(--s-lg); cursor: pointer; border-bottom: 1px solid var(--c-border); transition: background .15s; }
.task:hover { background: var(--c-surface-muted, #f7f8fa); }
.task.is-active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.task.is-done { opacity: .68; }
.task__top { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--s-xs); }
.task__type { font-size: var(--t-xs); color: var(--c-brand); font-weight: 600; }
.task__title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); margin-bottom: var(--s-xs); line-height: 1.4; }
.task__meta { display: flex; flex-wrap: wrap; gap: var(--s-md); font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: var(--s-xs); }
.task__meta span { display: inline-flex; align-items: center; gap: 4px; }
.task__foot { display: flex; align-items: center; gap: var(--s-xs); }
.task__time { margin-left: auto; font-size: 10px; color: var(--c-text-3); }
.tier { font-size: 10px; font-weight: 700; padding: 1px 6px; border-radius: var(--r-sm); }
.tier--l1 { background: rgba(82,196,26,.12); color: var(--c-success-fg); }
.tier--l2 { background: rgba(250,140,22,.12); color: var(--c-warning-fg); }
.tier--l3 { background: rgba(255,77,79,.12); color: var(--c-danger-fg); }
.empty { text-align: center; color: var(--c-text-3); font-size: var(--t-sm); padding: var(--s-xl) 0; }

/* 详情 */
.det__head { display: flex; justify-content: space-between; align-items: flex-start; gap: var(--s-md); padding-bottom: var(--s-md); border-bottom: 1px solid var(--c-border); }
.det__type { font-size: var(--t-xs); color: var(--c-brand); font-weight: 600; margin-bottom: var(--s-xxs); }
.det__title { margin: 0 0 var(--s-xs); font-size: var(--t-lg); font-weight: 700; color: var(--c-text); }
.det__sub { display: flex; flex-wrap: wrap; gap: var(--s-md); font-size: var(--t-xs); color: var(--c-text-3); }
.det__sub span { display: inline-flex; align-items: center; gap: 4px; }
.det__badges { display: flex; flex-direction: column; gap: var(--s-xs); align-items: flex-end; flex-shrink: 0; }
.det__summary { margin: var(--s-md) 0; padding: var(--s-md); background: var(--c-surface-muted, #f7f8fa); border-radius: var(--r-md); font-size: var(--t-sm); color: var(--c-text-2); line-height: var(--lh-md); }
.det__amount { display: flex; align-items: baseline; gap: var(--s-sm); padding: var(--s-md); border: 1px solid var(--c-brand-border); border-radius: var(--r-md); background: var(--c-brand-soft); margin-bottom: var(--s-md); }
.det__amount-l { font-size: var(--t-xs); color: var(--c-text-3); }
.det__amount-v { font-size: var(--t-xl); font-weight: 700; color: var(--c-brand); font-variant-numeric: tabular-nums; }
.det__amount-tier { margin-left: auto; font-size: var(--t-xs); color: var(--c-text-2); }
.det__amount-tier b { color: var(--c-text); font-size: var(--t-md); }

.det__ops { margin-bottom: var(--s-lg); }
.det__ops-row { display: flex; gap: var(--s-xs); justify-content: flex-end; margin-top: var(--s-sm); flex-wrap: wrap; }
.det__ops-hint { display: flex; align-items: center; gap: 6px; margin: var(--s-sm) 0 0; font-size: var(--t-xs); color: var(--c-text-3); }
.det__readonly { padding: var(--s-md); background: var(--c-surface-muted, #f7f8fa); border-radius: var(--r-md); margin-bottom: var(--s-md); }

.det__history-h { display: flex; align-items: center; gap: 6px; font-size: var(--t-sm); font-weight: 600; color: var(--c-text); margin-bottom: var(--s-md); }
.timeline { position: relative; padding-left: var(--s-sm); }
.tl { position: relative; padding: 0 0 var(--s-md) var(--s-lg); border-left: 2px solid var(--c-border); }
.tl:last-child { border-left-color: transparent; padding-bottom: 0; }
.tl__dot { position: absolute; left: -7px; top: 2px; width: 12px; height: 12px; border-radius: 50%; background: var(--c-text-3); border: 2px solid var(--c-surface); }
.tl__dot--approve { background: var(--c-success-fg); }
.tl__dot--reject { background: var(--c-danger-fg); }
.tl__dot--submit { background: var(--c-brand); }
.tl__dot--transfer { background: var(--c-brand-secondary); }
.tl__dot--add_sign { background: var(--c-warning-fg); }
.tl__top { display: flex; align-items: center; gap: var(--s-xs); flex-wrap: wrap; }
.tl__actor { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.tl__action { font-size: var(--t-xs); padding: 1px 7px; border-radius: var(--r-sm); background: var(--c-surface-muted, #f0f2f5); color: var(--c-text-2); }
.tl__action--approve { background: var(--c-success-bg); color: var(--c-success-fg); }
.tl__action--reject { background: var(--c-danger-bg); color: var(--c-danger-fg); }
.tl__action--submit { background: var(--c-brand-soft); color: var(--c-brand); }
.tl__time { margin-left: auto; font-size: 10px; color: var(--c-text-3); }
.tl__comment { margin-top: 4px; font-size: var(--t-xs); color: var(--c-text-2); line-height: 1.5; }

.ap__detail--empty { display: flex; align-items: center; justify-content: center; min-height: 480px; }
.empty-big { text-align: center; color: var(--c-text-3); }
.empty-big p { margin-top: var(--s-md); font-size: var(--t-sm); }

.drawer__ops { display: flex; justify-content: flex-end; gap: var(--s-xs); margin-top: var(--s-md); }
.drawer__hint { margin: var(--s-sm) 0 0; font-size: var(--t-xs); color: var(--c-text-3); line-height: 1.5; }

@media (max-width: 900px) {
  .ap__body { grid-template-columns: 1fr; }
  .det__head { flex-direction: column; }
  .det__badges { align-items: flex-start; flex-direction: row; }
}
</style>
