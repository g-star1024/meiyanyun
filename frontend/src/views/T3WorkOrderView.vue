<script setup lang="ts">
/* ============================================================
 * T3 工单中心 /workorders
 * 跨模块统一路由层：M2-08 服务工单 / M2-10 物料申领 / M2-18 异常 / M4 设备故障。
 * 4 KPI + 筛选 + 工单表格 + 详情抽屉 + 新建/派单规则抽屉 + 智能派单。
 * ============================================================ */
import { computed, onMounted, ref, watch } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CTextarea from '@/components/CTextarea.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CSegmented from '@/components/CSegmented.vue'
import CDrawer from '@/components/CDrawer.vue'
import { useT3WorkorderStore } from '@/stores/t3Workorder'
import { useAuthStore } from '@/stores/auth'
import type {
  Ticket, TicketSource, TicketPriority, TicketStatus, SlaStatus, DispatchRule,
} from '@/stores/t3Workorder'

const store = useT3WorkorderStore()
const auth = useAuthStore()
onMounted(() => store.seed())

// ---------- KPI ----------
const kpis = computed(() => [
  { label: '待分配', icon: 'check-square', value: String(store.newCount), tone: 'warning' as const },
  { label: '处理中', icon: 'dashboard', value: String(store.processingCount), tone: 'brand' as const },
  { label: '已超时', icon: 'alert', value: String(store.overdueCount), tone: 'danger' as const },
  { label: '今日已解决', icon: 'calendar', value: String(store.resolvedToday), tone: 'success' as const },
])

// ---------- 筛选 ----------
const keyword = ref('')
const sourceFilter = ref('ALL')
const priorityFilter = ref('ALL')
const statusTab = ref<'ALL' | 'NEW' | 'PROCESSING' | 'RESOLVED'>('ALL')

const sourceOptions = [
  { value: 'ALL', label: '全部来源' },
  { value: 'M2-08', label: 'M2-08 服务工单' },
  { value: 'M2-10', label: 'M2-10 物料申领' },
  { value: 'M2-18', label: 'M2-18 异常处理' },
  { value: 'M4', label: 'M4 设备故障' },
  { value: 'MANUAL', label: '手工建单' },
  { value: 'SYSTEM', label: '系统生成' },
]
const priorityOptions = [
  { value: 'ALL', label: '全部优先级' },
  { value: 'URGENT', label: '紧急' },
  { value: 'HIGH', label: '高' },
  { value: 'NORMAL', label: '普通' },
  { value: 'LOW', label: '低' },
]
const statusTabOptions = [
  { value: 'ALL', label: '全部' },
  { value: 'NEW', label: '待分配' },
  { value: 'PROCESSING', label: '处理中' },
  { value: 'RESOLVED', label: '已解决' },
]

const categoryOptionsBySource: Record<string, { label: string; value: string }[]> = {
  'M2-08': [
    { value: '客诉', label: '客诉' },
    { value: '设备报修', label: '设备报修' },
    { value: '环境', label: '环境' },
    { value: '其他', label: '其他' },
  ],
  'M2-10': [
    { value: '药品申领', label: '药品申领' },
    { value: '耗材申领', label: '耗材申领' },
    { value: '其他', label: '其他' },
  ],
  'M2-18': [
    { value: '财务异常', label: '财务异常' },
    { value: '系统异常', label: '系统异常' },
    { value: '运营异常', label: '运营异常' },
  ],
  'M4': [
    { value: '设备报修', label: '设备报修' },
    { value: '设备保养', label: '设备保养' },
  ],
  MANUAL: [
    { value: '其他', label: '其他' },
    { value: '客诉', label: '客诉' },
  ],
  SYSTEM: [{ value: '系统异常', label: '系统异常' }],
}

const filteredTickets = computed(() => {
  let list = store.tickets
  if (keyword.value.trim()) {
    const k = keyword.value.trim().toLowerCase()
    list = list.filter(
      (t) => t.title.toLowerCase().includes(k) || t.ticketNo.toLowerCase().includes(k) || (t.assignee ?? '').includes(k),
    )
  }
  if (sourceFilter.value !== 'ALL') list = list.filter((t) => t.source === sourceFilter.value)
  if (priorityFilter.value !== 'ALL') list = list.filter((t) => t.priority === priorityFilter.value)
  if (statusTab.value === 'NEW') list = list.filter((t) => t.status === 'NEW')
  else if (statusTab.value === 'PROCESSING') list = list.filter((t) => ['ASSIGNED', 'PROCESSING', 'PENDING'].includes(t.status))
  else if (statusTab.value === 'RESOLVED') list = list.filter((t) => ['RESOLVED', 'CLOSED'].includes(t.status))
  return [...list].sort((a, b) => {
    // 按优先级 + 创建时间排序
    const pr = { URGENT: 0, HIGH: 1, NORMAL: 2, LOW: 3 } as const
    if (pr[a.priority] !== pr[b.priority]) return pr[a.priority] - pr[b.priority]
    return new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()
  })
})

// ---------- 映射 ----------
const PRIORITY_PILL: Record<TicketPriority, { text: string; status: 'danger' | 'info' | 'disabled' }> = {
  URGENT: { text: '紧急', status: 'danger' },
  HIGH: { text: '高', status: 'danger' },
  NORMAL: { text: '普通', status: 'info' },
  LOW: { text: '低', status: 'disabled' },
}
const STATUS_PILL: Record<TicketStatus, { text: string; status: 'warning' | 'primary' | 'info' | 'success' | 'disabled' | 'danger' }> = {
  NEW: { text: '待分配', status: 'warning' },
  ASSIGNED: { text: '已派单', status: 'info' },
  PROCESSING: { text: '处理中', status: 'primary' },
  PENDING: { text: '待反馈', status: 'warning' },
  RESOLVED: { text: '已解决', status: 'success' },
  CLOSED: { text: '已关闭', status: 'disabled' },
}
const SLA_PILL: Record<SlaStatus, { text: string; status: 'success' | 'warning' | 'danger' }> = {
  ON_TRACK: { text: '正常', status: 'success' },
  AT_RISK: { text: '即将超时', status: 'warning' },
  OVERDUE: { text: '已超时', status: 'danger' },
}

type PillStatus = 'default' | 'primary' | 'success' | 'warning' | 'danger' | 'info' | 'disabled' | 'draft'
const SOURCE_TAG_PILL: Record<TicketSource, { text: string; status: PillStatus }> = {
  'M2-08': { text: 'M2-08', status: 'primary' },
  'M2-10': { text: 'M2-10', status: 'info' },
  'M2-18': { text: 'M2-18', status: 'danger' },
  'M4':    { text: 'M4',    status: 'draft' },
  MANUAL:  { text: '手工',  status: 'default' },
  SYSTEM:  { text: '系统',  status: 'success' },
}

function fmtDateTime(iso?: string) {
  if (!iso) return '—'
  const d = new Date(iso)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getMonth() + 1}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function slaRemain(dueAt: string, closed = false) {
  if (closed) return '已完结'
  const diff = new Date(dueAt).getTime() - Date.now()
  const absM = Math.abs(Math.round(diff / 60000))
  const h = Math.floor(absM / 60)
  const m = absM % 60
  if (diff <= 0) return `已超 ${h}h${m ? m + 'm' : ''}`
  if (h < 1) return `剩余 ${m} 分钟`
  return `剩余 ${h}h${m ? m + 'm' : ''}`
}

// ---------- 详情抽屉 ----------
const detailOpen = ref(false)
const currentId = ref<string | null>(null)
const current = computed<Ticket | null>(() => (currentId.value ? store.getTicket(currentId.value) ?? null : null))

function openDetail(t: Ticket) {
  currentId.value = t.id
  detailOpen.value = true
  commentDraft.value = ''
  resolutionDraft.value = ''
  assignTo.value = t.assignee ?? ''
  assignDept.value = t.assigneeDept ?? ''
}

// ---------- 详情内操作 ----------
const assignTo = ref('')
const assignDept = ref('')
const commentDraft = ref('')
const resolutionDraft = ref('')
const flashMsg = ref('')

function setFlash(msg: string) {
  flashMsg.value = msg
  window.setTimeout(() => (flashMsg.value = ''), 3000)
}

const staffOptions = [
  { value: '王工', label: '王工（工程部）' },
  { value: '李仓管', label: '李仓管（仓储）' },
  { value: '陈雅琳', label: '陈雅琳（门店运营）' },
  { value: '苏晴', label: '苏晴（店长）' },
  { value: 'IT 支持', label: 'IT 支持（信息部）' },
  { value: '物业', label: '物业（后勤）' },
]

function doAssign() {
  if (!current.value || !assignTo.value) return
  const dept = staffOptions.find((s) => s.value === assignTo.value)?.label.split('（')[1]?.replace('）', '') ?? assignDept.value
  store.assignTicket(current.value.id, assignTo.value, dept || assignDept.value)
  setFlash(`已派单给 ${assignTo.value}`)
}

function doStart() {
  if (!current.value) return
  store.startProcessing(current.value.id)
  setFlash('已开始处理')
}

function doResolve() {
  if (!current.value || !resolutionDraft.value.trim()) return
  store.resolveTicket(current.value.id, resolutionDraft.value.trim())
  resolutionDraft.value = ''
  setFlash('工单已标记解决')
}

function doClose() {
  if (!current.value) return
  store.closeTicket(current.value.id)
  setFlash('工单已关闭')
}

function doAddComment() {
  if (!current.value || !commentDraft.value.trim()) return
  store.addComment(current.value.id, commentDraft.value.trim())
  commentDraft.value = ''
}

// ---------- 新建抽屉 ----------
const createOpen = ref(false)
const form = ref({
  title: '',
  source: 'MANUAL' as TicketSource,
  category: '',
  priority: 'NORMAL' as TicketPriority,
  description: '',
  storeName: '静安旗舰店',
})
const formCategoryOptions = computed(() => categoryOptionsBySource[form.value.source] ?? [])

watch(() => form.value.source, () => {
  form.value.category = ''
})

function openCreate() {
  form.value = { title: '', source: 'MANUAL', category: '', priority: 'NORMAL', description: '', storeName: '静安旗舰店' }
  createOpen.value = true
}

function submitCreate() {
  if (!form.value.title.trim() || !form.value.category || !form.value.description.trim()) return
  const t = store.createTicket({
    title: form.value.title.trim(),
    source: form.value.source,
    category: form.value.category,
    priority: form.value.priority,
    description: form.value.description.trim(),
    storeName: form.value.storeName,
  })
  createOpen.value = false
  setFlash(t.assignee ? `已创建并自动派单给 ${t.assignee}` : '已创建，待分配')
  openDetail(t)
}

// ---------- 派单规则抽屉 ----------
const rulesOpen = ref(false)
const editingRule = ref<(DispatchRule & { isNew?: boolean }) | null>(null)

function startNewRule() {
  editingRule.value = {
    id: '',
    name: '',
    source: 'M2-08',
    category: '*',
    assignTo: '',
    assignDept: '',
    priority: 'NORMAL',
    enabled: true,
    isNew: true,
  }
}
function editRule(r: DispatchRule) {
  editingRule.value = { ...r }
}
function saveRule() {
  if (!editingRule.value) return
  if (!editingRule.value.name || !editingRule.value.assignTo) return
  const { isNew, id, ...data } = editingRule.value
  store.upsertRule(isNew ? data : { ...data, id })
  editingRule.value = null
}

const ruleSourceOptions = sourceOptions.filter((o) => o.value !== 'ALL')
const rulePriorityOptions = priorityOptions.filter((o) => o.value !== 'ALL')
</script>

<template>
  <div class="t3wo">
    <!-- 头部：纯 KPI -->
    <div class="t3wo__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <CCard class="t3wo__filters" padding="md">
      <div class="t3wo__filterbar">
        <CSegmented v-model="statusTab" :options="statusTabOptions" />
        <CSelect v-model="sourceFilter" :options="sourceOptions" width="180px" />
        <CSelect v-model="priorityFilter" :options="priorityOptions" width="140px" />
        <CInput v-model="keyword" placeholder="搜索工单号 / 标题 / 负责人" />
        <div class="t3wo__filterbar-right">
          <CButton variant="secondary" size="sm" @click="rulesOpen = true">
            <CIcon name="settings" :size="16" />派单规则
          </CButton>
          <CButton
            variant="primary"
            size="sm"
            :disabled="!auth.can('ticket:create')"
            @click="openCreate"
          >
            <CIcon name="plus" :size="16" />新建工单
          </CButton>
        </div>
      </div>
    </CCard>

    <CCard class="t3wo__tablecard" padding="none">
      <div class="t3wo__tablewrap">
        <table class="t3wo__table">
          <thead>
            <tr>
              <th>工单号</th>
              <th>标题</th>
              <th>来源</th>
              <th>分类</th>
              <th>优先级</th>
              <th>状态</th>
              <th>SLA</th>
              <th>负责人</th>
              <th>门店</th>
              <th>创建时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="t in filteredTickets"
              :key="t.id"
              :class="{ 'row-overdue': t.slaStatus === 'OVERDUE' && t.status !== 'CLOSED' && t.status !== 'RESOLVED' }"
              @click="openDetail(t)"
            >
              <td class="cell-mono">{{ t.ticketNo }}</td>
              <td class="cell-title">{{ t.title }}</td>
              <td>
                <CStatusPill :status="SOURCE_TAG_PILL[t.source].status as any">{{ SOURCE_TAG_PILL[t.source].text }}</CStatusPill>
              </td>
              <td>{{ t.category }}</td>
              <td>
                <CStatusPill :status="PRIORITY_PILL[t.priority].status as any">{{ PRIORITY_PILL[t.priority].text }}</CStatusPill>
              </td>
              <td>
                <CStatusPill :status="STATUS_PILL[t.status].status as any" dot>{{ STATUS_PILL[t.status].text }}</CStatusPill>
              </td>
              <td>
                <CStatusPill :status="SLA_PILL[t.slaStatus].status as any">{{ SLA_PILL[t.slaStatus].text }}</CStatusPill>
              </td>
              <td>{{ t.assignee ?? '—' }}</td>
              <td>{{ t.storeName }}</td>
              <td class="cell-mono">{{ fmtDateTime(t.createdAt) }}</td>
              <td>
                <CButton variant="text" size="sm" @click.stop="openDetail(t)">详情</CButton>
              </td>
            </tr>
            <tr v-if="!filteredTickets.length">
              <td colspan="11" class="cell-empty">暂无符合条件的工单</td>
            </tr>
          </tbody>
        </table>
      </div>
    </CCard>

    <!-- 详情抽屉 -->
    <CDrawer :show="detailOpen" title="工单详情" size="lg" @update:show="detailOpen = $event">
      <div v-if="current" class="detail">
        <div v-if="flashMsg" class="flash flash--ok">
          <CIcon name="check" :size="16" />{{ flashMsg }}
        </div>

        <div class="detail__head">
          <div class="detail__no">{{ current.ticketNo }}</div>
          <h3 class="detail__title">{{ current.title }}</h3>
          <div class="detail__pills">
            <CStatusPill :status="SOURCE_TAG_PILL[current.source].status as any">{{ SOURCE_TAG_PILL[current.source].text }}</CStatusPill>
            <CStatusPill :status="PRIORITY_PILL[current.priority].status as any">{{ PRIORITY_PILL[current.priority].text }}</CStatusPill>
            <CStatusPill :status="STATUS_PILL[current.status].status as any" dot>{{ STATUS_PILL[current.status].text }}</CStatusPill>
            <CStatusPill :status="SLA_PILL[current.slaStatus].status as any">{{ SLA_PILL[current.slaStatus].text }}</CStatusPill>
          </div>
        </div>

        <div class="detail__sla" :class="`sla--${current.slaStatus.toLowerCase()}`">
          <CIcon name="clock" :size="16" />
          <div>
            <div class="detail__sla-due">截止：{{ fmtDateTime(current.dueAt) }}</div>
            <div class="detail__sla-remain">{{ slaRemain(current.dueAt, current.status === 'CLOSED') }}</div>
          </div>
        </div>

        <CCard class="detail__block" padding="md" header-border>
          <template #header><span class="block-title">基本信息</span></template>
          <div class="kv-grid">
            <div><span class="kv-k">分类</span><span class="kv-v">{{ current.category }}</span></div>
            <div><span class="kv-k">门店</span><span class="kv-v">{{ current.storeName }}</span></div>
            <div><span class="kv-k">报告人</span><span class="kv-v">{{ current.reporter }}</span></div>
            <div><span class="kv-k">报告部门</span><span class="kv-v">{{ current.reporterDept }}</span></div>
            <div><span class="kv-k">创建时间</span><span class="kv-v">{{ fmtDateTime(current.createdAt) }}</span></div>
            <div><span class="kv-k">负责人</span><span class="kv-v">{{ current.assignee ? `${current.assignee}（${current.assigneeDept ?? ''}）` : '未分配' }}</span></div>
          </div>
          <div class="desc-block">
            <div class="kv-k">问题描述</div>
            <p class="desc-text">{{ current.description }}</p>
          </div>
          <div v-if="current.resolution" class="desc-block">
            <div class="kv-k">解决方案</div>
            <p class="desc-text desc-text--ok">{{ current.resolution }}</p>
          </div>
        </CCard>

        <!-- 派单/操作区 -->
        <CCard class="detail__block" padding="md" header-border>
          <template #header><span class="block-title">处理操作</span></template>
          <div v-if="auth.can('ticket:dispatch') && current.status === 'NEW'" class="op-row">
            <CSelect v-model="assignTo" :options="staffOptions" width="220px" />
            <CButton variant="primary" size="sm" :disabled="!assignTo" @click="doAssign">
              <CIcon name="user-check" :size="16" />确认派单
            </CButton>
          </div>
          <div class="op-row">
            <CButton
              v-if="current.status === 'ASSIGNED' || current.status === 'PENDING'"
              variant="primary" size="sm"
              :disabled="!auth.can('ticket:dispatch')"
              @click="doStart"
            >
              <CIcon name="check-square" :size="16" />开始处理
            </CButton>
            <template v-if="current.status === 'PROCESSING' || current.status === 'ASSIGNED'">
              <CTextarea
                v-model="resolutionDraft"
                placeholder="请填写处理结果（必填）"
                :rows="3"
              />
              <CButton
                variant="secondary" size="sm"
                :disabled="!resolutionDraft.trim() || !auth.can('ticket:close')"
                @click="doResolve"
              >
                <CIcon name="check" :size="16" />标记解决
              </CButton>
            </template>
            <CButton
              v-if="current.status === 'RESOLVED'"
              variant="primary" size="sm"
              :disabled="!auth.can('ticket:close')"
              @click="doClose"
            >
              <CIcon name="check-square" :size="16" />关闭工单
            </CButton>
          </div>

          <div class="comment-row">
            <CTextarea v-model="commentDraft" placeholder="添加处理备注..." :rows="2" />
            <CButton variant="ghost" size="sm" :disabled="!commentDraft.trim()" @click="doAddComment">
              <CIcon name="chat" :size="16" />添加备注
            </CButton>
          </div>
        </CCard>

        <!-- 时间线 -->
        <CCard class="detail__block" padding="md" header-border>
          <template #header><span class="block-title">处理时间线</span></template>
          <ol class="timeline">
            <li v-for="(e, i) in current.timeline" :key="i" class="tl-item">
              <div class="tl-dot" />
              <div class="tl-body">
                <div class="tl-head">
                  <span class="tl-actor">{{ e.actor }}</span>
                  <span class="tl-action">{{ e.action }}</span>
                  <span class="tl-time">{{ fmtDateTime(e.at) }}</span>
                </div>
                <div v-if="e.comment" class="tl-comment">{{ e.comment }}</div>
              </div>
            </li>
          </ol>
        </CCard>
      </div>
    </CDrawer>

    <!-- 新建工单抽屉 -->
    <CDrawer :show="createOpen" title="新建工单" size="md" @update:show="createOpen = $event">
      <div class="form">
        <CInput v-model="form.title" label="工单标题" placeholder="一句话概述问题" />
        <div class="form__field">
          <label class="form__label">来源</label>
          <CSelect v-model="form.source" :options="ruleSourceOptions" width="100%" />
        </div>
        <div class="form__row">
          <div class="form__field">
            <label class="form__label">分类</label>
            <CSelect v-model="form.category" :options="formCategoryOptions" width="100%" />
          </div>
          <div class="form__field">
            <label class="form__label">优先级</label>
            <CSelect v-model="form.priority" :options="rulePriorityOptions" width="100%" />
          </div>
        </div>
        <CInput v-model="form.storeName" label="门店" placeholder="如：静安旗舰店" />
        <CTextarea v-model="form.description" label="问题描述" :rows="5" placeholder="请详细描述问题、影响范围、已尝试处理..." />
        <div class="form__hint">
          <CIcon name="shield" :size="14" />
          保存后将根据"派单规则"自动匹配处理人；若无匹配规则，工单状态为待分配。
        </div>
      </div>
      <template #footer>
        <CButton variant="ghost" size="sm" @click="createOpen = false">取消</CButton>
        <CButton
          variant="primary" size="sm"
          :disabled="!form.title.trim() || !form.category || !form.description.trim()"
          @click="submitCreate"
        >创建工单</CButton>
      </template>
    </CDrawer>

    <!-- 派单规则抽屉 -->
    <CDrawer :show="rulesOpen" title="智能派单规则" size="lg" @update:show="rulesOpen = $event">
      <div class="rules">
        <div class="rules__head">
          <span class="rules-tip">规则按来源 + 分类匹配，分类 "*" 表示通配。新建工单时自动按规则分配处理人。</span>
          <CButton v-if="!editingRule" variant="primary" size="sm" @click="startNewRule">
            <CIcon name="plus" :size="16" />新增规则
          </CButton>
        </div>

        <div v-if="!editingRule" class="rule-list">
          <div v-for="r in store.rules" :key="r.id" class="rule-item">
            <div class="rule-item__main">
              <div class="rule-item__title">
                <CIcon name="settings" :size="14" />{{ r.name }}
                <CStatusPill :status="r.enabled ? 'success' : 'disabled'">{{ r.enabled ? '已启用' : '已停用' }}</CStatusPill>
              </div>
              <div class="rule-item__meta">
                <span>来源：{{ store.SOURCE_LABEL[r.source] }}</span>
                <span>分类：{{ r.category === '*' ? '全部' : r.category }}</span>
                <span>派给：{{ r.assignTo }}（{{ r.assignDept }}）</span>
                <span>优先级：{{ store.PRIORITY_LABEL[r.priority] }}</span>
              </div>
            </div>
            <div class="rule-item__ops">
              <CButton variant="text" size="sm" @click="store.toggleRule(r.id, !r.enabled)">
                {{ r.enabled ? '停用' : '启用' }}
              </CButton>
              <CButton variant="text" size="sm" @click="editRule(r)">
                <CIcon name="edit" :size="14" />编辑
              </CButton>
            </div>
          </div>
        </div>

        <div v-else class="rule-form">
          <CInput v-model="editingRule.name" label="规则名称" placeholder="如：设备故障→工程" />
          <div class="form__row">
            <div class="form__field">
              <label class="form__label">来源</label>
              <CSelect v-model="editingRule.source" :options="ruleSourceOptions" width="100%" />
            </div>
            <CInput v-model="editingRule.category" label="分类（* 为全部）" placeholder="如：客诉" />
          </div>
          <div class="form__row">
            <CInput v-model="editingRule.assignTo" label="派单人" placeholder="如：王工" />
            <CInput v-model="editingRule.assignDept" label="派单部门" placeholder="如：工程部" />
          </div>
          <div class="form__field">
            <label class="form__label">默认优先级</label>
            <CSelect v-model="editingRule.priority" :options="rulePriorityOptions" width="100%" />
          </div>
          <div class="rule-form__ops">
            <CButton variant="ghost" size="sm" @click="editingRule = null">取消</CButton>
            <CButton variant="primary" size="sm" @click="saveRule">保存规则</CButton>
          </div>
        </div>
      </div>
    </CDrawer>
  </div>
</template>

<style scoped>
.t3wo {
  display: flex;
  flex-direction: column;
  gap: var(--s-md);
}
.t3wo :deep(.card__body) {
  display: flex;
  flex-direction: column;
  gap: var(--s-md);
}

.t3wo__head {
  display: grid;
  grid-auto-flow: column;
  grid-auto-columns: 1fr;
  gap: var(--s-md);
}
.t3wo__head :deep(.ckpi) { min-width: 0; }
@media (max-width: 1024px) {
  .t3wo__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); }
}

.t3wo__filterbar {
  display: flex;
  flex-wrap: nowrap;
  gap: var(--s-sm);
  align-items: center;
  overflow-x: auto;
}
.t3wo__filterbar > :deep(.csel),
.t3wo__filterbar > :deep(.seg) {
  flex-shrink: 0;
}
.t3wo__filterbar > :deep(.cinput) {
  flex: 1 1 200px;
  min-width: 200px;
}
.t3wo__filterbar-right {
  display: flex;
  align-items: center;
  gap: var(--s-sm);
  margin-left: auto;
  flex-shrink: 0;
  position: sticky;
  right: 0;
  background: var(--c-surface);
  z-index: 2;
  padding-left: var(--s-sm);
  box-shadow: -8px 0 8px -6px rgba(0,0,0,.08);
}

.t3wo__tablewrap {
  overflow-x: auto;
}
.t3wo__table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--t-sm);
}
.t3wo__table th,
.t3wo__table td {
  padding: var(--s-sm) var(--s-md);
  text-align: left;
  border-bottom: 1px solid var(--c-border-light);
  vertical-align: middle;
}
.t3wo__table th {
  background: var(--c-bg-page);
  color: var(--c-text-3);
  font-weight: 600;
  font-size: var(--t-xs);
  white-space: nowrap;
}
.t3wo__table tbody tr {
  cursor: pointer;
  transition: background 0.15s;
}
.t3wo__table tbody tr:hover {
  background: var(--c-brand-soft);
}
.t3wo__table tr.row-overdue {
  background: var(--c-danger-bg);
}
.t3wo__table tr.row-overdue:hover {
  background: #ffe5e5;
}
.cell-mono {
  font-variant-numeric: tabular-nums;
  color: var(--c-text-2);
  white-space: nowrap;
}
.cell-title {
  color: var(--c-text);
  font-weight: 600;
  min-width: 180px;
}
.cell-empty {
  text-align: center;
  color: var(--c-text-3);
  padding: var(--s-xl) 0;
}

/* 详情 */
.detail {
  display: flex;
  flex-direction: column;
  gap: var(--s-md);
}
.detail__head {
  display: flex;
  flex-direction: column;
  gap: var(--s-xs);
}
.detail__no {
  font-size: var(--t-xs);
  color: var(--c-text-3);
  font-variant-numeric: tabular-nums;
}
.detail__title {
  font-size: var(--t-lg);
  font-weight: 700;
  color: var(--c-text);
}
.detail__pills {
  display: flex;
  gap: var(--s-xs);
  flex-wrap: wrap;
}
.detail__sla {
  display: flex;
  align-items: center;
  gap: var(--s-sm);
  padding: var(--s-sm) var(--s-md);
  border-radius: var(--r-md);
  background: var(--c-success-bg);
  color: var(--c-success-fg);
}
.detail__sla.sla--at_risk {
  background: var(--c-warning-bg);
  color: var(--c-warning-fg);
}
.detail__sla.sla--overdue {
  background: var(--c-danger-bg);
  color: var(--c-danger-fg);
}
.detail__sla-due {
  font-size: var(--t-xs);
  opacity: 0.9;
}
.detail__sla-remain {
  font-size: var(--t-md);
  font-weight: 700;
}

.detail__block.block-title {
  font-weight: 600;
}
.block-title {
  font-weight: 600;
}
.kv-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--s-sm) var(--s-md);
}
.kv-k {
  color: var(--c-text-3);
  font-size: var(--t-xs);
  margin-right: var(--s-xs);
}
.kv-v {
  color: var(--c-text);
  font-size: var(--t-sm);
}
.desc-block {
  margin-top: var(--s-md);
}
.desc-text {
  margin: var(--s-xs) 0 0;
  padding: var(--s-sm);
  background: var(--c-bg-page);
  border-radius: var(--r-sm);
  color: var(--c-text-2);
  font-size: var(--t-sm);
  line-height: var(--lh-base);
}
.desc-text--ok {
  background: var(--c-success-bg);
  color: var(--c-success-fg);
}

.op-row {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-sm);
  align-items: flex-end;
}
.op-row > :deep(.ctextarea) {
  flex: 1 1 100%;
}
.comment-row {
  display: flex;
  gap: var(--s-sm);
  align-items: flex-end;
  margin-top: var(--s-md);
  padding-top: var(--s-md);
  border-top: 1px dashed var(--c-border);
}
.comment-row > :deep(.ctextarea) {
  flex: 1;
}

.flash {
  display: flex;
  align-items: center;
  gap: var(--s-xs);
  padding: var(--s-sm) var(--s-md);
  border-radius: var(--r-md);
  font-size: var(--t-sm);
}
.flash--ok {
  background: var(--c-success-bg);
  color: var(--c-success-fg);
}

.timeline {
  list-style: none;
  padding: 0;
  margin: 0;
  position: relative;
}
.tl-item {
  position: relative;
  padding-left: var(--s-lg);
  padding-bottom: var(--s-md);
}
.tl-item:last-child {
  padding-bottom: 0;
}
.tl-item::before {
  content: '';
  position: absolute;
  left: 5px;
  top: 16px;
  bottom: 0;
  width: 1px;
  background: var(--c-border);
}
.tl-item:last-child::before {
  display: none;
}
.tl-dot {
  position: absolute;
  left: 0;
  top: 6px;
  width: 11px;
  height: 11px;
  border-radius: 50%;
  background: var(--c-brand);
  box-shadow: 0 0 0 3px var(--c-brand-soft);
}
.tl-head {
  display: flex;
  gap: var(--s-sm);
  align-items: baseline;
  flex-wrap: wrap;
}
.tl-actor {
  font-weight: 600;
  color: var(--c-text);
}
.tl-action {
  color: var(--c-brand);
}
.tl-time {
  color: var(--c-text-3);
  font-size: var(--t-xs);
  margin-left: auto;
}
.tl-comment {
  margin-top: var(--s-xxs);
  color: var(--c-text-2);
  font-size: var(--t-sm);
  padding: var(--s-xs) var(--s-sm);
  background: var(--c-bg-page);
  border-radius: var(--r-sm);
}

/* 表单 */
.form {
  display: flex;
  flex-direction: column;
  gap: var(--s-md);
}
.form__row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--s-md);
}
.form__field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.form__label {
  font-size: 13px;
  color: var(--c-text);
  line-height: 18px;
}
.form__hint {
  display: flex;
  gap: var(--s-xs);
  align-items: flex-start;
  padding: var(--s-sm);
  background: var(--c-info-bg);
  color: var(--c-info-fg);
  border-radius: var(--r-sm);
  font-size: var(--t-xs);
}

/* 规则 */
.rules {
  display: flex;
  flex-direction: column;
  gap: var(--s-md);
}
.rules__head {
  display: flex;
  gap: var(--s-md);
  align-items: center;
  justify-content: space-between;
}
.rules-tip {
  font-size: var(--t-xs);
  color: var(--c-text-3);
}
.rule-list {
  display: flex;
  flex-direction: column;
  gap: var(--s-sm);
}
.rule-item {
  padding: var(--s-md);
  border: 1px solid var(--c-border-light);
  border-radius: var(--r-md);
  display: flex;
  align-items: center;
  gap: var(--s-md);
  background: var(--c-surface);
}
.rule-item__main {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: var(--s-xxs);
}
.rule-item__title {
  display: flex;
  align-items: center;
  gap: var(--s-xs);
  font-weight: 600;
  color: var(--c-text);
}
.rule-item__meta {
  display: flex;
  gap: var(--s-md);
  flex-wrap: wrap;
  font-size: var(--t-xs);
  color: var(--c-text-3);
}
.rule-item__ops {
  display: flex;
  gap: var(--s-xs);
}
.rule-form {
  display: flex;
  flex-direction: column;
  gap: var(--s-md);
  padding: var(--s-md);
  background: var(--c-bg-page);
  border-radius: var(--r-md);
}
.rule-form__ops {
  display: flex;
  justify-content: flex-end;
  gap: var(--s-sm);
}
</style>
