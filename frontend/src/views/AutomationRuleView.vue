<script setup lang="ts">
/* ============================================================
 * 自动化规则管理 /m5-automation（P6-B100，B90 遗留收口）
 * 规则列表（enabled 三态筛选＋启停＋编辑抽屉）+ 执行日志 Tab。
 * 数据：对接 marketing-service /api/marketing/flow（@/api/automation 薄封装）。
 * 权限：marketing:view（查询）/ marketing:edit（增改/启停，D3 零新码）。
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CFab from '@/components/CFab.vue'
import {
  fetchAutomationRules,
  fetchAutomationLogs,
  createAutomationRule,
  updateAutomationRule,
  toggleAutomationRule,
  type AutomationRuleRow,
  type AutomationLogRow,
} from '@/api/automation'
import { useAuthStore } from '@/stores/auth'
import { useToast } from '@/composables/useToast'
import { errMsg } from '@/stores/m5Coupon'

const auth = useAuthStore()
const toast = useToast()

type TriggerType = 'BIRTHDAY' | 'DORMANT_DAYS' | 'VISIT_GAP_DAYS'
type ActionType = 'CREATE_CARE_TASK' | 'CREATE_RECALL'

const TRIGGER_LABEL: Record<string, string> = {
  BIRTHDAY: '生日触发',
  DORMANT_DAYS: '沉睡唤醒',
  VISIT_GAP_DAYS: '复购窗口',
}
const ACTION_LABEL: Record<string, string> = {
  CREATE_CARE_TASK: '创建关怀任务',
  CREATE_RECALL: '创建召回任务',
}
const LOG_STATUS_PILL: Record<string, { status: 'success' | 'warning' | 'danger'; text: string }> = {
  SUCCESS: { status: 'success', text: '成功' },
  SKIPPED: { status: 'warning', text: '跳过' },
  FAILED: { status: 'danger', text: '失败' },
}

const tab = ref<'rules' | 'logs'>('rules')

// ---------- 规则列表 ----------
const rules = ref<AutomationRuleRow[]>([])
const enabledFilter = ref<'ALL' | 'ON' | 'OFF'>('ALL')
const enabledOptions = [
  { value: 'ALL', label: '全部状态' },
  { value: 'ON', label: '仅启用' },
  { value: 'OFF', label: '仅停用' },
]
const filteredRules = computed(() => {
  if (enabledFilter.value === 'ON') return rules.value.filter((r) => r.enabled)
  if (enabledFilter.value === 'OFF') return rules.value.filter((r) => !r.enabled)
  return rules.value
})

const logs = ref<AutomationLogRow[]>([])

const kpis = computed(() => [
  { label: '规则总数', icon: 'settings', value: String(rules.value.length), tone: 'brand' as const },
  { label: '启用中', icon: 'check', value: String(rules.value.filter((r) => r.enabled).length), tone: 'success' as const },
  { label: '已停用', icon: 'bell', value: String(rules.value.filter((r) => !r.enabled).length), tone: 'warning' as const },
  { label: '执行日志', icon: 'calendar', value: String(logs.value.length), tone: 'teal' as const },
])

function parseJson(raw: string | null): Record<string, unknown> {
  if (!raw) return {}
  try {
    return JSON.parse(raw) as Record<string, unknown>
  } catch {
    return {}
  }
}

function triggerSummary(r: AutomationRuleRow): string {
  const c = parseJson(r.triggerConfig)
  if (r.triggerType === 'BIRTHDAY') return `提前 ${c.daysBefore ?? '—'} 天`
  if (r.triggerType === 'DORMANT_DAYS') return `沉睡 ${c.dormantDays ?? '—'} 天`
  if (r.triggerType === 'VISIT_GAP_DAYS') return `间隔 ${c.gapDays ?? '—'} 天`
  return '—'
}

function actionSummary(r: AutomationRuleRow): string {
  const c = parseJson(r.actionConfig)
  if (r.actionType === 'CREATE_CARE_TASK') return `${c.careType ?? '—'} / ${c.channel ?? '—'}`
  if (r.actionType === 'CREATE_RECALL') return `召回方式 ${c.method ?? '—'}`
  return '—'
}

async function loadRules() {
  try {
    const res = await fetchAutomationRules()
    rules.value = res.data ?? []
  } catch (e) {
    toast.error(errMsg(e, '规则列表加载失败'))
  }
}

async function doToggle(r: AutomationRuleRow) {
  if (!auth.can('marketing:edit')) {
    toast.error('无营销编辑权限')
    return
  }
  try {
    const res = await toggleAutomationRule(r.id)
    const idx = rules.value.findIndex((x) => x.id === r.id)
    if (idx >= 0) rules.value.splice(idx, 1, res.data)
    toast.success(res.data.enabled ? `规则「${res.data.name}」已启用` : `规则「${res.data.name}」已停用`)
  } catch (e) {
    toast.error(errMsg(e, '启停失败'))
  }
}

// ---------- 新建/编辑弹层 ----------
const showForm = ref(false)
const form = ref({
  id: null as number | null,
  name: '',
  triggerType: 'BIRTHDAY' as TriggerType,
  daysBefore: 3,
  dormantDays: 90,
  gapDays: 60,
  levels: '',
  tags: '',
  actionType: 'CREATE_CARE_TASK' as ActionType,
  careType: 'BIRTHDAY',
  channel: 'SMS',
  contentTemplate: '',
  method: 'PHONE',
  reason: '',
  storeCode: '',
  enabled: true,
})

const triggerTypeOptions = [
  { value: 'BIRTHDAY', label: '生日触发（提前 N 天）' },
  { value: 'DORMANT_DAYS', label: '沉睡唤醒（N 天未到店）' },
  { value: 'VISIT_GAP_DAYS', label: '复购窗口（距上次到店 N 天）' },
]
const actionTypeOptions = [
  { value: 'CREATE_CARE_TASK', label: '创建关怀任务' },
  { value: 'CREATE_RECALL', label: '创建召回任务' },
]
const careTypeOptions = [
  { value: 'BIRTHDAY', label: '生日关怀' },
  { value: 'HOLIDAY', label: '节日问候' },
  { value: 'REPURCHASE', label: '复购窗口' },
  { value: 'REACTIVATE', label: '沉睡唤醒' },
]
const channelOptions = [
  { value: 'SMS', label: '短信' },
  { value: 'WECHAT', label: '企微' },
  { value: 'PHONE', label: '电话' },
]
const methodOptions = [
  { value: 'PHONE', label: '电话' },
  { value: 'SMS', label: '短信' },
  { value: 'WECHAT', label: '企微' },
]

const canSubmit = computed(() => !!form.value.name.trim())

function openCreate() {
  form.value = {
    id: null, name: '', triggerType: 'BIRTHDAY', daysBefore: 3, dormantDays: 90, gapDays: 60,
    levels: '', tags: '', actionType: 'CREATE_CARE_TASK', careType: 'BIRTHDAY', channel: 'SMS',
    contentTemplate: '', method: 'PHONE', reason: '', storeCode: '', enabled: true,
  }
  showForm.value = true
}

function openEdit(r: AutomationRuleRow) {
  const tc = parseJson(r.triggerConfig)
  const cc = parseJson(r.conditionConfig)
  const ac = parseJson(r.actionConfig)
  form.value = {
    id: r.id,
    name: r.name,
    triggerType: (r.triggerType as TriggerType) || 'BIRTHDAY',
    daysBefore: Number(tc.daysBefore ?? 3),
    dormantDays: Number(tc.dormantDays ?? 90),
    gapDays: Number(tc.gapDays ?? 60),
    levels: Array.isArray(cc.levels) ? (cc.levels as string[]).join(',') : '',
    tags: Array.isArray(cc.tags) ? (cc.tags as string[]).join(',') : '',
    actionType: (r.actionType as ActionType) || 'CREATE_CARE_TASK',
    careType: String(ac.careType ?? 'BIRTHDAY'),
    channel: String(ac.channel ?? 'SMS'),
    contentTemplate: String(ac.contentTemplate ?? ''),
    method: String(ac.method ?? 'PHONE'),
    reason: String(ac.reason ?? ''),
    storeCode: r.storeCode ?? '',
    enabled: r.enabled,
  }
  showForm.value = true
}

function splitCsv(s: string): string[] {
  return s.split(/[,，]/).map((x) => x.trim()).filter(Boolean)
}

async function submitForm() {
  if (!auth.can('marketing:edit')) {
    toast.error('无营销编辑权限')
    return
  }
  const f = form.value
  const triggerConfig = f.triggerType === 'BIRTHDAY'
    ? JSON.stringify({ daysBefore: f.daysBefore })
    : f.triggerType === 'DORMANT_DAYS'
      ? JSON.stringify({ dormantDays: f.dormantDays })
      : JSON.stringify({ gapDays: f.gapDays })
  const levels = splitCsv(f.levels)
  const tags = splitCsv(f.tags)
  const conditionConfig = levels.length || tags.length ? JSON.stringify({ levels, tags }) : null
  const actionConfig = f.actionType === 'CREATE_CARE_TASK'
    ? JSON.stringify({ careType: f.careType, channel: f.channel, contentTemplate: f.contentTemplate })
    : JSON.stringify({ method: f.method, reason: f.reason })
  const body = {
    name: f.name.trim(),
    triggerType: f.triggerType,
    triggerConfig,
    conditionConfig,
    actionType: f.actionType,
    actionConfig,
    storeCode: f.storeCode.trim() || null,
    enabled: f.enabled,
  }
  try {
    if (f.id == null) {
      const res = await createAutomationRule(body)
      rules.value.unshift(res.data)
      toast.success(`规则「${res.data.name}」已创建（${res.data.ruleNo}）`)
    } else {
      const res = await updateAutomationRule(f.id, body)
      const idx = rules.value.findIndex((x) => x.id === f.id)
      if (idx >= 0) rules.value.splice(idx, 1, res.data)
      toast.success(`规则「${res.data.name}」已更新`)
    }
    showForm.value = false
  } catch (e) {
    toast.error(errMsg(e, '规则保存失败'))
  }
}

// ---------- 执行日志 ----------
const logRuleNo = ref('')
const logDate = ref('')

async function loadLogs() {
  try {
    const params: { ruleNo?: string; date?: string } = {}
    if (logRuleNo.value.trim()) params.ruleNo = logRuleNo.value.trim()
    if (logDate.value) params.date = logDate.value
    const res = await fetchAutomationLogs(params)
    logs.value = res.data ?? []
  } catch (e) {
    toast.error(errMsg(e, '执行日志加载失败'))
  }
}

function fmtTime(iso?: string | null) {
  if (!iso) return '—'
  const d = new Date(iso)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

onMounted(() => {
  loadRules()
  loadLogs()
})
</script>

<template>
  <div class="auto">
    <div class="auto__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <CCard padding="none">
      <div class="tabs">
        <button class="tab" :class="{ 'tab--active': tab === 'rules' }" @click="tab = 'rules'">
          规则管理 ({{ rules.length }})
        </button>
        <button class="tab" :class="{ 'tab--active': tab === 'logs' }" @click="tab = 'logs'; loadLogs()">
          执行日志 ({{ logs.length }})
        </button>
      </div>

      <!-- ========== 规则管理 ========== -->
      <div v-if="tab === 'rules'" class="pane">
        <div class="pane__filter">
          <CSelect v-model="enabledFilter" :options="enabledOptions" />
        </div>
        <div class="list">
          <div v-if="filteredRules.length === 0" class="empty">
            <CIcon :name="('settings' as any)" :size="28" class="empty__icon" />
            <div>暂无自动化规则</div>
          </div>
          <div v-for="r in filteredRules" :key="r.id" class="rule">
            <div class="rule__main">
              <div class="rule__top">
                <span class="rule__name">{{ r.name }}</span>
                <CStatusPill :status="r.enabled ? 'success' : 'disabled'" dot>{{ r.enabled ? '启用中' : '已停用' }}</CStatusPill>
              </div>
              <div class="rule__meta">
                <span class="rule__no">{{ r.ruleNo }}</span>
                <span><CIcon name="clock" :size="12" /> {{ TRIGGER_LABEL[r.triggerType] ?? r.triggerType }} · {{ triggerSummary(r) }}</span>
                <span><CIcon name="marketing" :size="12" /> {{ ACTION_LABEL[r.actionType] ?? r.actionType }} · {{ actionSummary(r) }}</span>
                <span><CIcon name="org" :size="12" /> {{ r.storeCode ?? '全连锁' }}</span>
              </div>
            </div>
            <div class="rule__ops">
              <CButton variant="ghost" size="sm" v-perm.disable="'marketing:edit'" @click="openEdit(r)">
                <CIcon name="edit" :size="14" />编辑
              </CButton>
              <CButton
                :variant="r.enabled ? 'ghost' : 'primary'" size="sm"
                v-perm.disable="'marketing:edit'" @click="doToggle(r)"
              >
                <CIcon :name="(r.enabled ? 'close' : 'check') as any" :size="14" />{{ r.enabled ? '停用' : '启用' }}
              </CButton>
            </div>
          </div>
        </div>
        <CFab
          :actions="[{ icon: 'plus', label: '新建规则', disabled: !auth.can('marketing:edit'), onClick: openCreate }]"
        />
      </div>

      <!-- ========== 执行日志 ========== -->
      <div v-else class="pane">
        <div class="pane__filter pane__filter--logs">
          <CInput v-model="logRuleNo" placeholder="规则编号（如 AR-SEED-001）" />
          <input v-model="logDate" type="date" class="native-input native-input--date" />
          <CButton variant="primary" size="sm" @click="loadLogs">
            <CIcon name="search" :size="14" />查询
          </CButton>
        </div>
        <div class="list">
          <div v-if="logs.length === 0" class="empty">
            <CIcon :name="('calendar' as any)" :size="28" class="empty__icon" />
            <div>暂无执行日志</div>
          </div>
          <div v-for="l in logs" :key="l.id" class="log">
            <div class="log__main">
              <div class="log__top">
                <span class="log__rule">{{ l.ruleNo }}</span>
                <CStatusPill :status="(LOG_STATUS_PILL[l.status] ?? { status: 'default' }).status">
                  {{ (LOG_STATUS_PILL[l.status] ?? { text: l.status }).text }}
                </CStatusPill>
              </div>
              <div class="log__meta">
                <span><CIcon name="customer" :size="12" /> {{ l.customerId }}</span>
                <span><CIcon name="marketing" :size="12" /> {{ ACTION_LABEL[l.actionType] ?? l.actionType }} → {{ l.actionRef }}</span>
                <span><CIcon name="clock" :size="12" /> 触发 {{ l.triggerDate }} · 落账 {{ fmtTime(l.createdAt) }}</span>
              </div>
              <div v-if="l.message" class="log__msg">{{ l.message }}</div>
            </div>
          </div>
        </div>
      </div>
    </CCard>

    <!-- 新建/编辑规则弹层 -->
    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <CCard class="modal" :title="form.id == null ? '新建自动化规则' : `编辑规则 ${form.name}`" padding="lg">
        <div class="form">
          <div class="form__row">
            <label class="form__label">规则名称</label>
            <CInput v-model="form.name" placeholder="如：生日提前3天关怀" />
          </div>
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">触发类型</label>
              <CSelect v-model="form.triggerType" :options="triggerTypeOptions" />
            </div>
            <div v-if="form.triggerType === 'BIRTHDAY'">
              <label class="form__label">提前天数</label>
              <input v-model.number="form.daysBefore" type="number" min="0" class="native-input" />
            </div>
            <div v-else-if="form.triggerType === 'DORMANT_DAYS'">
              <label class="form__label">沉睡天数</label>
              <input v-model.number="form.dormantDays" type="number" min="1" class="native-input" />
            </div>
            <div v-else>
              <label class="form__label">间隔天数</label>
              <input v-model.number="form.gapDays" type="number" min="1" class="native-input" />
            </div>
          </div>
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">限定等级（逗号分隔，可空）</label>
              <CInput v-model="form.levels" placeholder="如：金卡,白金" />
            </div>
            <div>
              <label class="form__label">限定标签（逗号分隔，可空）</label>
              <CInput v-model="form.tags" placeholder="如：高意向" />
            </div>
          </div>
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">动作类型</label>
              <CSelect v-model="form.actionType" :options="actionTypeOptions" />
            </div>
            <div>
              <label class="form__label">适用门店（留空=全连锁）</label>
              <CInput v-model="form.storeCode" placeholder="如：SH001" />
            </div>
          </div>
          <template v-if="form.actionType === 'CREATE_CARE_TASK'">
            <div class="form__row form__row--2">
              <div>
                <label class="form__label">关怀类型</label>
                <CSelect v-model="form.careType" :options="careTypeOptions" />
              </div>
              <div>
                <label class="form__label">发送渠道</label>
                <CSelect v-model="form.channel" :options="channelOptions" />
              </div>
            </div>
            <div class="form__row">
              <label class="form__label">内容模板</label>
              <CInput v-model="form.contentTemplate" placeholder="关怀文案模板" />
            </div>
          </template>
          <template v-else>
            <div class="form__row form__row--2">
              <div>
                <label class="form__label">召回方式</label>
                <CSelect v-model="form.method" :options="methodOptions" />
              </div>
              <div>
                <label class="form__label">召回原因</label>
                <CInput v-model="form.reason" placeholder="如：沉睡90天唤醒" />
              </div>
            </div>
          </template>
          <div v-if="form.id == null" class="form__row form__row--inline">
            <label class="form__check">
              <input v-model="form.enabled" type="checkbox" /> 创建后立即启用（后续启停走列表开关）
            </label>
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showForm = false">取消</CButton>
          <CButton variant="primary" :disabled="!canSubmit" @click="submitForm">提交</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.auto { display: flex; flex-direction: column; gap: var(--s-lg); }
.auto__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .auto__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }

.tabs { display: flex; gap: var(--s-xs); padding: var(--s-md); border-bottom: 1px solid var(--c-border-light); align-items: center; }
.tab { padding: 6px 14px; border-radius: var(--r-md); border: 1px solid var(--c-border); background: var(--c-surface); color: var(--c-text-2); font-size: var(--t-sm); cursor: pointer; white-space: nowrap; flex-shrink: 0; }
.tab:hover { color: var(--c-brand); border-color: var(--c-brand); }
.tab--active { background: var(--c-brand-soft); border-color: var(--c-brand); color: var(--c-brand); font-weight: 600; }

.pane { position: relative; display: flex; flex-direction: column; }
.pane__filter { display: flex; gap: var(--s-md); padding: var(--s-md) var(--s-lg); border-bottom: 1px solid var(--c-border-light); }
.pane__filter--logs { align-items: center; }
.pane__filter--logs > * { flex: 1; }
.pane__filter--logs .cbutton, .pane__filter--logs button { flex: 0 0 auto; }

.list { max-height: 560px; overflow-y: auto; display: flex; flex-direction: column; }
.empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl) var(--s-lg) 64px; color: var(--c-text-3); font-size: var(--t-sm); }
.empty__icon { color: var(--c-text-4); }

.rule { display: flex; justify-content: space-between; align-items: center; gap: var(--s-md); padding: var(--s-md) var(--s-lg); border-bottom: 1px solid var(--c-border-light); }
.rule:hover { background: var(--c-brand-soft); }
.rule__main { min-width: 0; }
.rule__top { display: flex; align-items: center; gap: var(--s-sm); margin-bottom: var(--s-xs); }
.rule__name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.rule__meta { display: flex; flex-wrap: wrap; gap: var(--s-md); font-size: var(--t-xs); color: var(--c-text-3); align-items: center; }
.rule__meta > span { display: inline-flex; align-items: center; gap: 3px; }
.rule__no { color: var(--c-brand); font-weight: 600; }
.rule__ops { display: flex; gap: var(--s-xs); flex-shrink: 0; }

.log { display: flex; justify-content: space-between; gap: var(--s-md); padding: var(--s-md) var(--s-lg); border-bottom: 1px solid var(--c-border-light); }
.log__main { min-width: 0; }
.log__top { display: flex; align-items: center; gap: var(--s-sm); margin-bottom: var(--s-xs); }
.log__rule { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.log__meta { display: flex; flex-wrap: wrap; gap: var(--s-md); font-size: var(--t-xs); color: var(--c-text-3); align-items: center; }
.log__meta > span { display: inline-flex; align-items: center; gap: 3px; }
.log__msg { margin-top: var(--s-xs); font-size: var(--t-xs); color: var(--c-warning-fg); background: var(--c-warning-bg); border-radius: var(--r-sm); padding: 4px 8px; }

.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 560px; max-width: 100%; max-height: 90vh; overflow-y: auto; box-shadow: var(--shadow-pop); }
.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.form__row--2 { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.form__row--inline { flex-direction: row; align-items: center; }
.form__label { font-size: var(--t-xs); color: var(--c-text-3); }
.form__check { display: inline-flex; align-items: center; gap: var(--s-xs); font-size: var(--t-sm); color: var(--c-text-2); }
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
.native-input--date { max-width: 180px; }

@media (max-width: 1024px) {
  .rule { flex-direction: column; align-items: flex-start; }
  .rule__ops { align-self: flex-end; }
  .list { max-height: 320px; }
}
</style>
