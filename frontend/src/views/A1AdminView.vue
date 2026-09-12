<script setup lang="ts">
/* A1-21/22/23/24 平台管理 /ai/admin — 灰度/计费/模板/配置
   B42 接真：KPI ← /ai/logs/kpi；功能×角色灰度矩阵 ← /ai/features（保存整表 roles）；
   用量账单 ← /ai/logs/bill（金额单位：分）；全局配置 ← /ai/cfg。
   注意：ai_feature_role 是 AI 灰度矩阵（谁能用到 AI 功能），不是 T1 RBAC 菜单权限。 */
import { computed, onMounted, reactive, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CKpi from '@/components/CKpi.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CTable from '@/components/CTable.vue'
import CSegmented from '@/components/CSegmented.vue'
import CSelect from '@/components/CSelect.vue'
import CInput from '@/components/CInput.vue'
import CDrawer from '@/components/CDrawer.vue'
import AiApplyDrawer from '@/components/AiApplyDrawer.vue'
import { useAuthStore } from '@/stores/auth'
import { useToast } from '@/composables/useToast'
import { errMsg } from '@/stores/m5Coupon'
import {
  logKpi, monthlyBill, listFeatures, saveFeatureRoles,
  getCfg, saveCfg, listModels, saveFeatureBinding, invokeFeature,
  listQuotas, saveQuota,
  type AiKpi, type FeatureBill, type BindingView, type CfgView,
  type ModelView, type InvokeView, type QuotaView,
} from '@/api/ai'

const auth = useAuthStore()
const toast = useToast()
const canEdit = computed(() => auth.can('aiAdmin:edit'))

const tab = ref('bind')
const tabOptions = [
  { label: '功能绑定', value: 'bind' },
  { label: '灰度权限', value: 'perm' },
  { label: '用量计费', value: 'bill' },
  { label: '配额管理', value: 'quota' },
  { label: '模板市场', value: 'tpl' },
  { label: '全局配置', value: 'cfg' },
]

const kpis = ref<ReturnType<typeof toKpis> | null>(null)
function toKpis(k: AiKpi) {
  return [
    { label: 'AI 功能数', icon: 'dashboard', value: `${k.enabledFeatureCount}/${k.featureCount}`, tone: 'purple' as const },
    { label: '本月用量', icon: 'dashboard', value: compactNum(k.monthCalls), tone: 'brand' as const },
    { label: '本月费用', icon: 'finance', value: `¥${fmtYuan(k.totalCostFen)}`, tone: 'orange' as const },
    { label: '接入模型', icon: 'settings', value: String(k.modelCount), tone: 'teal' as const },
  ]
}
function compactNum(n: number) {
  if (n >= 10000) return `${(n / 10000).toFixed(1)}万`
  return n.toLocaleString()
}
function fmtYuan(fen: number) {
  return (fen / 100).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

// 灰度矩阵：后端 roles 以角色码为 key
const roleCodes = ['SUPER_ADMIN', 'REGION_MGR', 'STORE_MGR', 'CONSULTANT', 'OPERATOR']
const roleNames: Record<string, string> = {
  SUPER_ADMIN: '超级管理员', REGION_MGR: '区域经理', STORE_MGR: '店长',
  CONSULTANT: '咨询师', OPERATOR: '市场专员',
}
const features = ref<BindingView[]>([])
const matrix = reactive<Record<string, Record<string, boolean>>>({})
const matrixLoading = ref(false)
const matrixSaving = ref(false)
const dirty = ref(false)
roleCodes.forEach((r) => { matrix[r] = {} })

async function loadMatrix() {
  matrixLoading.value = true
  try {
    features.value = await listFeatures()
    features.value.forEach((f) => {
      roleCodes.forEach((r) => { matrix[r][f.featureCode] = !!f.roles?.[r] })
    })
    dirty.value = false
  } catch (e) {
    toast.error('灰度矩阵加载失败：' + errMsg(e))
  } finally {
    matrixLoading.value = false
  }
}
function touch() { dirty.value = true }
async function saveMatrix() {
  matrixSaving.value = true
  try {
    for (const f of features.value) {
      const roles: Record<string, boolean> = {}
      roleCodes.forEach((r) => { roles[r] = !!matrix[r][f.featureCode] })
      await saveFeatureRoles(f.featureCode, roles)
    }
    dirty.value = false
    toast.success('灰度矩阵已保存，变更已写入审计日志')
  } catch (e) {
    toast.error('灰度矩阵保存失败：' + errMsg(e))
  } finally {
    matrixSaving.value = false
  }
}

// 功能绑定：模型 / 灰度范围 / 提示词模板 / 参数覆盖 / 启用，抽屉内支持真实试运行
const models = ref<ModelView[]>([])
const bindCols = [
  { key: 'featureName', label: 'AI 功能' },
  { key: 'model', label: '绑定模型' },
  { key: 'storeScopeText', label: '门店灰度' },
  { key: 'enabled', label: '状态', width: '90', align: 'center' as const },
  { key: 'roles', label: '已放开角色' },
  { key: 'updatedAt', label: '最近更新', width: '160' },
  { key: 'ops', label: '操作', width: '150', align: 'right' as const },
]
const bindRows = computed(() =>
  features.value.map((f) => ({
    ...f,
    model: f.modelDisplayName ? `${f.modelDisplayName}（${f.modelCode}）` : '—',
    storeScopeText: f.storeScope === 'SPECIFIED' ? `指定门店：${f.storeCodes || '—'}` : '全部门店',
    roles: roleCodes.filter((r) => f.roles?.[r]).map((r) => roleNames[r]).join('、') || '—',
    updatedAt: f.updatedAt ? f.updatedAt.replace('T', ' ').slice(0, 16) : '—',
  })),
)
const bindModelOptions = computed(() =>
  models.value.map((m) => ({
    label: `${m.displayName}（${m.modelCode}）${m.enabled ? '' : '·已停用'}`,
    value: String(m.modelId),
  })),
)

const bDrawer = ref(false)
const bEditing = ref<BindingView | null>(null)
const bSaving = ref(false)
const bForm = reactive({
  modelId: '', storeScope: 'ALL', storeCodes: '', promptTemplate: '',
  paramOverrides: '', enabled: false, requireApproval: false,
})
function openBind(row: BindingView) {
  bEditing.value = row
  Object.assign(bForm, {
    modelId: row.modelId == null ? '' : String(row.modelId),
    storeScope: row.storeScope || 'ALL',
    storeCodes: row.storeCodes || '',
    promptTemplate: row.promptTemplate || '',
    paramOverrides: row.paramOverrides || '',
    enabled: !!row.enabled,
    requireApproval: !!row.requireApproval,
  })
  invokeResult.value = null
  invokeInput.value = ''
  invokeError.value = ''
  bDrawer.value = true
}
async function saveBinding() {
  if (!bEditing.value) return
  let overrides: string | null = bForm.paramOverrides.trim()
  if (overrides) {
    try {
      JSON.parse(overrides)
    } catch {
      toast.error('参数覆盖不是合法 JSON，示例：{"temperature":0.2,"maxTokens":2048}')
      return
    }
  } else {
    overrides = null
  }
  bSaving.value = true
  try {
    const r = await saveFeatureBinding(bEditing.value.featureCode, {
      modelId: bForm.modelId === '' ? null : Number(bForm.modelId),
      storeScope: bForm.storeScope,
      storeCodes: bForm.storeScope === 'SPECIFIED' ? bForm.storeCodes.trim() : null,
      promptTemplate: bForm.promptTemplate.trim() || null,
      paramOverrides: overrides,
      enabled: bForm.enabled,
      requireApproval: bForm.requireApproval,
    })
    toast.success(r.changed ? '功能绑定已保存并写入审计日志' : '配置无变化，未产生更新')
    await loadMatrix()
    bDrawer.value = false
  } catch (e) {
    toast.error('功能绑定保存失败：' + errMsg(e))
  } finally {
    bSaving.value = false
  }
}

// B45：停用功能提交上架（绑定启用）审批，治理岗在 AI 治理页裁决
const applyShow = ref(false)
const applyTargetId = ref<number | null>(null)
const applyLabel = ref('')
function openBindingApply() {
  if (!bEditing.value) return
  applyTargetId.value = bEditing.value.bindingId
  applyLabel.value = `${bEditing.value.featureName}（${bEditing.value.featureCode}）`
  applyShow.value = true
}

// 试运行：走真实 POST /api/ai/features/{code}/invoke，结果与费用同步沉淀 ai_invoke_log
const invokeInput = ref('')
const invoking = ref(false)
const invokeResult = ref<InvokeView | null>(null)
const invokeError = ref('')
async function runInvoke() {
  if (!bEditing.value) return
  const text = invokeInput.value.trim()
  if (!text) {
    toast.warning('请先输入试运行内容')
    return
  }
  invoking.value = true
  invokeResult.value = null
  invokeError.value = ''
  try {
    invokeResult.value = await invokeFeature(bEditing.value.featureCode, { input: text })
    const [k, b] = await Promise.allSettled([logKpi(), monthlyBill()])
    if (k.status === 'fulfilled') kpis.value = toKpis(k.value)
    if (b.status === 'fulfilled') bills.value = b.value
  } catch (e) {
    invokeError.value = errMsg(e)
  } finally {
    invoking.value = false
  }
}

// 计费
const billCols = [
  { key: 'featureName', label: '功能' },
  { key: 'calls', label: '本月调用', align: 'right' as const },
  { key: 'tokens', label: 'Token 消耗', align: 'right' as const },
  { key: 'costFen', label: '费用（元）', align: 'right' as const },
  { key: 'trend', label: '环比' },
]
const bills = ref<FeatureBill[]>([])
const billRows = computed(() =>
  bills.value.map((b) => ({ ...b, trend: '—' })),
)

// 配额管理：维度 FEATURE/MODEL/GLOBAL，用量按 ai_invoke_log 实时聚合，空限额 = 不限
const scopeNames: Record<string, string> = {
  FEATURE: '功能', MODEL: '模型', GLOBAL: '全局',
}
const quotas = ref<QuotaView[]>([])
const quotaCols = [
  { key: 'quotaScope', label: '维度', width: '80' },
  { key: 'targetName', label: '目标' },
  { key: 'daily', label: '今日用量 / 日额度', align: 'center' as const },
  { key: 'monthly', label: '本月用量 / 月额度', align: 'center' as const },
  { key: 'watermark', label: '最高水位', width: '110', align: 'center' as const },
  { key: 'enabled', label: '状态', width: '90', align: 'center' as const },
  { key: 'updatedAt', label: '最近更新', width: '160' },
  { key: 'ops', label: '操作', width: '90', align: 'right' as const },
]
function fmtLimit(v: number | null) {
  return v == null ? '不限' : v.toLocaleString()
}
function watermarkOf(q: QuotaView): number | null {
  const d = q.dailyLimit && q.dailyLimit > 0 ? (q.dailyUsed / q.dailyLimit) * 100 : null
  const m = q.monthlyLimit && q.monthlyLimit > 0 ? (q.monthlyUsed / q.monthlyLimit) * 100 : null
  if (d == null && m == null) return null
  return Math.max(d ?? 0, m ?? 0)
}
const quotaRows = computed(() =>
  quotas.value.map((q) => ({
    ...q,
    daily: `${q.dailyUsed.toLocaleString()} / ${fmtLimit(q.dailyLimit)}`,
    monthly: `${q.monthlyUsed.toLocaleString()} / ${fmtLimit(q.monthlyLimit)}`,
    updatedAt: q.updatedAt ? q.updatedAt.replace('T', ' ').slice(0, 16) : '—',
  })),
)
async function loadQuotas() {
  try {
    quotas.value = await listQuotas()
  } catch (e) {
    toast.error('配额列表加载失败：' + errMsg(e))
  }
}
const qDrawer = ref(false)
const qEditing = ref<QuotaView | null>(null)
const qSaving = ref(false)
const qForm = reactive({ dailyLimit: '', monthlyLimit: '', enabled: true })
function openQuota(row: QuotaView) {
  qEditing.value = row
  Object.assign(qForm, {
    dailyLimit: row.dailyLimit == null ? '' : String(row.dailyLimit),
    monthlyLimit: row.monthlyLimit == null ? '' : String(row.monthlyLimit),
    enabled: !!row.enabled,
  })
  qDrawer.value = true
}
async function saveQuotaRow() {
  if (!qEditing.value) return
  const dailyRaw = qForm.dailyLimit.trim()
  const monthlyRaw = qForm.monthlyLimit.trim()
  if (dailyRaw && (!/^\d+$/.test(dailyRaw) || Number(dailyRaw) < 0)) {
    toast.error('日额度必须是非负整数，留空表示不限')
    return
  }
  if (monthlyRaw && (!/^\d+$/.test(monthlyRaw) || Number(monthlyRaw) < 0)) {
    toast.error('月额度必须是非负整数，留空表示不限')
    return
  }
  qSaving.value = true
  try {
    const r = await saveQuota(qEditing.value.quotaScope, qEditing.value.targetCode, {
      dailyLimit: dailyRaw ? Number(dailyRaw) : null,
      monthlyLimit: monthlyRaw ? Number(monthlyRaw) : null,
      enabled: qForm.enabled,
    })
    toast.success(r.changed ? '配额已保存并写入审计日志' : '配置无变化，未产生更新')
    await loadQuotas()
    qDrawer.value = false
  } catch (e) {
    toast.error('配额保存失败：' + errMsg(e))
  } finally {
    qSaving.value = false
  }
}

// 模板（规划能力，本期占位）
const templates = [
  { id: 1, name: '新客破冰话术包', desc: '20 条标准破冰话术，适配首次到店客户', uses: 0, tag: '话术' },
  { id: 2, name: '秋季营销文案模板', desc: '朋友圈/企微/短信三端文案，含合规词过滤', uses: 0, tag: '营销' },
  { id: 3, name: '流失召回 SOP', desc: '高/中/低风险客户分层召回策略与话术', uses: 0, tag: '运营' },
  { id: 4, name: '排班优化模型配置', desc: '基于客流预测的人力排班推荐参数模板', uses: 0, tag: '排班' },
]

// 全局配置
const config = reactive({
  defaultModelId: '',
  grayScale: '100',
  retention: '12',
  autoAudit: true,
  sensitiveCheck: true,
  explainability: false,
})
const modelOptions = ref<{ label: string; value: string }[]>([])
const retentionOptions = [
  { label: '6 个月', value: '6' }, { label: '12 个月', value: '12' },
  { label: '24 个月', value: '24' }, { label: '36 个月', value: '36' },
]
const cfgSaving = ref(false)
const cfgUpdated = ref<string | null>(null)

async function loadCfg() {
  try {
    const c: CfgView = await getCfg()
    config.defaultModelId = c.defaultModelId == null ? '' : String(c.defaultModelId)
    config.grayScale = String(c.grayScale)
    config.retention = String(c.retentionMonths)
    config.autoAudit = c.autoAudit
    config.sensitiveCheck = c.sensitiveCheck
    config.explainability = c.explainability
    modelOptions.value = c.modelOptions.map((m) => ({ label: m.label, value: String(m.modelId) }))
    cfgUpdated.value = c.updatedAt ? c.updatedAt.replace('T', ' ').slice(0, 16) : null
  } catch (e) {
    toast.error('全局配置加载失败：' + errMsg(e))
  }
}
async function saveGlobalCfg() {
  cfgSaving.value = true
  try {
    const r = await saveCfg({
      defaultModelId: config.defaultModelId === '' ? null : Number(config.defaultModelId),
      grayScale: Number(config.grayScale) || 0,
      retentionMonths: Number(config.retention),
      sensitiveCheck: config.sensitiveCheck,
      explainability: config.explainability,
      autoAudit: config.autoAudit,
    })
    toast.success(r.changed ? '全局配置已保存' : '配置无变化，未产生更新')
    await loadCfg()
  } catch (e) {
    toast.error('配置保存失败：' + errMsg(e))
  } finally {
    cfgSaving.value = false
  }
}

async function loadAll() {
  const [k, b, m] = await Promise.allSettled([logKpi(), monthlyBill(), listModels()])
  if (k.status === 'fulfilled') kpis.value = toKpis(k.value)
  else toast.error('平台指标加载失败：' + errMsg(k.reason))
  if (b.status === 'fulfilled') bills.value = b.value
  else toast.error('用量账单加载失败：' + errMsg(b.reason))
  if (m.status === 'fulfilled') models.value = m.value
  else toast.error('模型列表加载失败：' + errMsg(m.reason))
  await Promise.all([loadMatrix(), loadCfg(), loadQuotas()])
}
onMounted(loadAll)
</script>

<template>
  <div class="a1-admin">
    <div class="kpis"><CKpi v-for="k in (kpis ?? [])" :key="k.label" v-bind="k" /></div>
    <CCard padding="lg">
      <CSegmented v-model="tab" :options="tabOptions" />

      <!-- 功能绑定 -->
      <div v-if="tab === 'bind'" class="mt">
        <CTable :columns="bindCols" :rows="bindRows" row-key="featureCode" stripe
          :empty-text="features.length ? '暂无数据' : '功能目录加载中…'">
          <template #col-model="{ value }">
            <span v-if="value !== '—'" class="mono">{{ value }}</span>
            <span v-else class="muted">未绑定</span>
          </template>
          <template #col-enabled="{ value }">
            <CStatusPill :status="value ? 'success' : 'disabled'" dot>{{ value ? '已启用' : '未启用' }}</CStatusPill>
          </template>
          <template #col-roles="{ value }"><span class="muted">{{ value }}</span></template>
          <template #col-ops="{ row }">
            <CButton v-if="canEdit" size="sm" variant="text" @click="openBind(row as BindingView)">绑定 / 试运行</CButton>
            <span v-else class="muted">—</span>
          </template>
        </CTable>
        <p class="hint">
          功能绑定决定每个 AI 能力实际走哪个已接入模型、提示词模板与参数覆盖；启用并在「灰度权限」放开角色后，
          六功能页与试运行才会产生真实调用，调用量与费用实时沉淀到网关监控与用量计费。
        </p>
      </div>

      <!-- 灰度权限 -->
      <div v-else-if="tab === 'perm'" class="mt">
        <div class="perm-bar">
          <span class="perm-bar__tip">勾选表示该角色在灰度范围内可使用对应 AI 功能（不影响 T1 菜单 RBAC）</span>
          <CButton v-if="canEdit" size="sm" variant="primary" :disabled="matrixSaving || !dirty" @click="saveMatrix">
            {{ matrixSaving ? '保存中…' : '保存灰度' }}
          </CButton>
        </div>
        <div class="perm-table">
          <div class="perm-row perm-head">
            <div class="perm-cell perm-cell--role">角色</div>
            <div v-for="f in features" :key="f.featureCode" class="perm-cell">{{ f.featureName }}</div>
          </div>
          <div v-for="r in roleCodes" :key="r" class="perm-row">
            <div class="perm-cell perm-cell--role">{{ roleNames[r] }}</div>
            <div v-for="f in features" :key="f.featureCode" class="perm-cell perm-cell--check">
              <input type="checkbox" :disabled="!canEdit || matrixLoading" v-model="matrix[r][f.featureCode]" @change="touch" />
            </div>
          </div>
        </div>
        <p class="hint">
          AI 功能的后台菜单权限受 T1 RBAC 统一管控；本矩阵控制功能内的 AI 能力灰度放量，
          保存动作逐功能写入审计日志。功能绑定模型与启用状态请在「功能绑定」页配置。
        </p>
      </div>

      <!-- 用量计费 -->
      <div v-else-if="tab === 'bill'" class="mt">
        <CTable :columns="billCols" :rows="billRows" row-key="featureCode" stripe empty-text="本月暂无调用，产生真实 AI 调用后出账">
          <template #col-calls="{ value }">{{ value.toLocaleString() }}</template>
          <template #col-tokens="{ value }">{{ value.toLocaleString() }}</template>
          <template #col-costFen="{ value }"><strong>¥{{ fmtYuan(value) }}</strong></template>
          <template #col-trend="{ value }"><span class="muted">{{ value }}</span></template>
        </CTable>
        <p class="hint">账单口径：按 ai_invoke_log 当月成功调用汇总，金额按模型定价折算（分 → 元）；环比为后续批次能力。</p>
      </div>

      <!-- 配额管理 -->
      <div v-else-if="tab === 'quota'" class="mt">
        <CTable :columns="quotaCols" :rows="quotaRows" row-key="targetCode" stripe
          :empty-text="quotas.length ? '暂无数据' : '配额数据加载中…'">
          <template #col-quotaScope="{ value }">
            <CStatusPill :status="value === 'GLOBAL' ? 'primary' : value === 'MODEL' ? 'info' : 'default'">
              {{ scopeNames[value] || value }}
            </CStatusPill>
          </template>
          <template #col-targetName="{ row }">
            <strong>{{ row.targetName }}</strong>
            <span v-if="row.quotaScope !== 'GLOBAL'" class="mono" style="margin-left:6px">{{ row.targetCode }}</span>
          </template>
          <template #col-watermark="{ row }">
            <span v-if="watermarkOf(row as QuotaView) == null" class="muted">—</span>
            <CStatusPill v-else
              :status="(watermarkOf(row as QuotaView) as number) >= 100 ? 'danger' : (watermarkOf(row as QuotaView) as number) >= 80 ? 'warning' : 'success'"
              dot>{{ (watermarkOf(row as QuotaView) as number).toFixed(0) }}%</CStatusPill>
          </template>
          <template #col-enabled="{ value }">
            <CStatusPill :status="value ? 'success' : 'disabled'" dot>{{ value ? '启用' : '停用' }}</CStatusPill>
          </template>
          <template #col-ops="{ row }">
            <CButton v-if="canEdit" size="sm" variant="text" @click="openQuota(row as QuotaView)">调整配额</CButton>
            <span v-else class="muted">—</span>
          </template>
        </CTable>
        <p class="hint">
          配额按「功能 → 模型 → 全局」三级在真实调用出站前依次校验，任一限额用尽即中文拒绝（HTTP 429）；
          用量以 ai_invoke_log 为准按北京时间当日/当月实时统计，被拦截请求不落日志、不占额度；留空限额表示不限。
        </p>
      </div>

      <!-- 模板市场 -->
      <div v-else-if="tab === 'tpl'" class="mt">
        <div class="tpl-grid">
          <div v-for="t in templates" :key="t.id" class="tpl-card">
            <div class="tpl-card__tag"><CStatusPill status="info">{{ t.tag }}</CStatusPill></div>
            <h4>{{ t.name }}</h4>
            <p>{{ t.desc }}</p>
            <div class="tpl-card__foot"><span>{{ t.uses }} 次使用</span><CButton size="sm" variant="primary" disabled>规划中</CButton></div>
          </div>
        </div>
      </div>

      <!-- 全局配置 -->
      <div v-else class="mt cfg">
        <div class="cfg__row">
          <label>默认模型</label>
          <CSelect v-model="config.defaultModelId" :options="modelOptions" width="280px" placeholder="未配置，请先在模型接入页添加" />
        </div>
        <div class="cfg__row">
          <label>灰度发布比例</label>
          <div class="cfg__inline"><CInput v-model="config.grayScale" width="100px" type="number" /><span>%（0~100）</span></div>
        </div>
        <div class="cfg__row">
          <label>调用数据保留期</label>
          <CSelect v-model="config.retention" :options="retentionOptions" width="240px" />
        </div>
        <div class="cfg__row cfg__row--toggle">
          <label>敏感词实时拦截（A1-04）</label>
          <button class="toggle" :class="{ on: config.sensitiveCheck, disabled: !canEdit }" :disabled="!canEdit" @click="config.sensitiveCheck = !config.sensitiveCheck"><span /></button>
        </div>
        <div class="cfg__row cfg__row--toggle">
          <label>AI 决策可解释性输出</label>
          <button class="toggle" :class="{ on: config.explainability, disabled: !canEdit }" :disabled="!canEdit" @click="config.explainability = !config.explainability"><span /></button>
        </div>
        <div class="cfg__row cfg__row--toggle">
          <label>操作审计自动记录（T1-04）</label>
          <button class="toggle" :class="{ on: config.autoAudit, disabled: !canEdit }" :disabled="!canEdit" @click="config.autoAudit = !config.autoAudit"><span /></button>
        </div>
        <div class="cfg__foot">
          <CButton variant="primary" :disabled="cfgSaving || !canEdit" @click="saveGlobalCfg">{{ cfgSaving ? '保存中…' : '保存配置' }}</CButton>
          <span v-if="cfgUpdated" class="cfg__upd">最近更新：{{ cfgUpdated }}</span>
        </div>
        <p class="hint">红线：数据保留期配置须满足训练集「授权来源/去标识/保留期」三要素要求；配置变更全动作审计。</p>
      </div>
    </CCard>

    <!-- 功能绑定 + 试运行抽屉 -->
    <CDrawer v-model:show="bDrawer" :title="bEditing ? `功能绑定 · ${bEditing.featureName}` : '功能绑定'" size="md">
      <div v-if="bEditing" class="bind-form">
        <div class="bf-row">
          <label class="fld-label">绑定模型</label>
          <CSelect v-model="bForm.modelId" :options="bindModelOptions" width="100%"
            placeholder="未绑定，请选择已接入且具备对话能力的模型" />
        </div>
        <div class="bf-grid">
          <div class="bf-row">
            <label class="fld-label">门店灰度范围</label>
            <CSelect v-model="bForm.storeScope" width="100%"
              :options="[{ label: '全部门店', value: 'ALL' }, { label: '指定门店', value: 'SPECIFIED' }]" />
          </div>
          <div class="bf-row">
            <label class="fld-label">功能状态</label>
            <CSelect :model-value="bForm.enabled ? '1' : '0'" width="100%"
              :options="[{ label: '启用', value: '1' }, { label: '停用', value: '0' }]"
              @update:model-value="bForm.enabled = $event === '1'" />
          </div>
        </div>
        <div v-if="bForm.storeScope === 'SPECIFIED'" class="bf-row">
          <CInput v-model="bForm.storeCodes" label="灰度门店编码（逗号分隔）" placeholder="如 S001,S002" />
        </div>
        <div class="bf-row">
          <label class="fld-label">提示词模板（{input} 为用户输入占位符；留空则直接透传输入）</label>
          <textarea v-model="bForm.promptTemplate" class="bf-textarea" rows="4"
            placeholder="如：你是医美连锁的专业话术助手，请基于以下客户情况生成到店沟通话术：&#10;{input}"></textarea>
        </div>
        <div class="bf-row">
          <label class="fld-label">参数覆盖（可选，JSON）</label>
          <textarea v-model="bForm.paramOverrides" class="bf-textarea" rows="2"
            placeholder='如 {"temperature":0.2,"maxTokens":2048}'></textarea>
        </div>

        <div class="bf-divider">
          <span>真实试运行</span>
          <span class="bf-divider__sub">走 POST /api/ai/features/{{ bEditing.featureCode }}/invoke，结果沉淀调用日志</span>
        </div>
        <div class="bf-row">
          <label class="fld-label">试运行输入</label>
          <textarea v-model="invokeInput" class="bf-textarea" rows="3"
            placeholder="输入一段真实业务内容，如：客户 32 岁，做过水光针，担心恢复期，想推荐新品项"></textarea>
        </div>
        <div class="bf-invoke">
          <CButton variant="primary" size="sm" :disabled="invoking || !canEdit" @click="runInvoke">
            {{ invoking ? '调用中…' : '发起真实调用' }}
          </CButton>
          <span class="muted">需当前账号已在灰度矩阵放开、功能已启用并绑定可用模型</span>
        </div>
        <div v-if="invokeResult" class="bf-result">
          <div class="bf-result__meta">
            <CStatusPill status="success" dot>调用成功</CStatusPill>
            <span class="mono">{{ invokeResult.modelCode }}</span>
            <span>耗时 {{ invokeResult.latencyMs }}ms</span>
            <span>提示 {{ invokeResult.promptTokens }} / 补全 {{ invokeResult.completionTokens }} / 合计 {{ invokeResult.totalTokens }} tokens</span>
            <span>费用 ¥{{ fmtYuan(invokeResult.costFen ?? 0) }}</span>
          </div>
          <pre class="bf-result__content">{{ invokeResult.content }}</pre>
        </div>
        <div v-if="invokeError" class="bf-result bf-result--fail">
          <CStatusPill status="danger" dot>调用失败（失败也已写入调用日志）</CStatusPill>
          <p class="bf-result__err">{{ invokeError }}</p>
        </div>
      </div>
      <template #footer>
        <CButton variant="secondary" @click="bDrawer = false">关闭</CButton>
        <CButton v-if="canEdit && bEditing && !bEditing.enabled" variant="secondary" @click="openBindingApply">提交上架申请</CButton>
        <CButton variant="primary" :disabled="bSaving || !canEdit" @click="saveBinding">{{ bSaving ? '保存中…' : '保存绑定' }}</CButton>
      </template>
    </CDrawer>

    <!-- B45 功能绑定上架申请抽屉 -->
    <AiApplyDrawer v-model:show="applyShow" approval-type="BINDING"
      :target-id="applyTargetId" :target-label="applyLabel" @applied="loadMatrix" />

    <!-- 配额调整抽屉 -->
    <CDrawer v-model:show="qDrawer" :title="qEditing ? `配额调整 · ${qEditing.targetName}` : '配额调整'" size="md">
      <div v-if="qEditing" class="bind-form">
        <div class="bf-grid">
          <div class="bf-row">
            <label class="fld-label">配额维度</label>
            <CInput :model-value="scopeNames[qEditing.quotaScope] || qEditing.quotaScope" disabled />
          </div>
          <div class="bf-row">
            <label class="fld-label">目标编码</label>
            <CInput :model-value="qEditing.targetCode" disabled />
          </div>
        </div>
        <div class="bf-grid">
          <div class="bf-row">
            <label class="fld-label">今日已用（次）</label>
            <CInput :model-value="String(qEditing.dailyUsed)" disabled />
          </div>
          <div class="bf-row">
            <label class="fld-label">本月已用（次）</label>
            <CInput :model-value="String(qEditing.monthlyUsed)" disabled />
          </div>
        </div>
        <div class="bf-grid">
          <div class="bf-row">
            <label class="fld-label">日调用额度（留空 = 不限）</label>
            <CInput v-model="qForm.dailyLimit" type="number" placeholder="如 200" />
          </div>
          <div class="bf-row">
            <label class="fld-label">月调用额度（留空 = 不限）</label>
            <CInput v-model="qForm.monthlyLimit" type="number" placeholder="如 3000" />
          </div>
        </div>
        <div class="bf-row">
          <label class="fld-label">配额状态</label>
          <CSelect :model-value="qForm.enabled ? '1' : '0'" width="100%"
            :options="[{ label: '启用（出站前校验）', value: '1' }, { label: '停用（不限制该维度）', value: '0' }]"
            @update:model-value="qForm.enabled = $event === '1'" />
        </div>
        <p class="hint">
          调整后立即生效；用量按北京时间当日 00:00 / 当月 1 日 00:00 起从 ai_invoke_log 实时统计。
          保存动作写入审计日志（AI_QUOTA）。
        </p>
      </div>
      <template #footer>
        <CButton variant="secondary" @click="qDrawer = false">关闭</CButton>
        <CButton variant="primary" :disabled="qSaving || !canEdit" @click="saveQuotaRow">{{ qSaving ? '保存中…' : '保存配额' }}</CButton>
      </template>
    </CDrawer>
  </div>
</template>

<style scoped>
.a1-admin { display: flex; flex-direction: column; gap: var(--s-lg); }
.kpis { display: flex; gap: var(--s-md); }
.mt { margin-top: var(--s-md); }
.hint { font-size: var(--t-xs); color: var(--c-text-3); margin-top: var(--s-md); }
.muted { color: var(--c-text-3); }

.perm-bar { display: flex; align-items: center; justify-content: space-between; gap: var(--s-md); margin-bottom: var(--s-sm); }
.perm-bar__tip { font-size: var(--t-xs); color: var(--c-text-3); }
.perm-table { border: 1px solid var(--c-border-light); border-radius: var(--r-lg); overflow: hidden; }
.perm-row { display: grid; grid-template-columns: 140px repeat(6, 1fr); }
.perm-row + .perm-row { border-top: 1px solid var(--c-border-light); }
.perm-head { background: var(--c-bg-page); font-weight: 600; font-size: var(--t-sm); }
.perm-cell { padding: var(--s-sm) var(--s-md); display: flex; align-items: center; font-size: var(--t-sm); color: var(--c-text-2); justify-content: center; }
.perm-cell--role { justify-content: flex-start; color: var(--c-text); font-weight: 500; }
.perm-cell--check input { width: 16px; height: 16px; accent-color: var(--c-brand); cursor: pointer; }
.perm-cell--check input:disabled { cursor: not-allowed; }

.tpl-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); }
.tpl-card { padding: var(--s-md); border: 1px solid var(--c-border-light); border-radius: var(--r-lg); display: flex; flex-direction: column; gap: var(--s-sm); }
.tpl-card h4 { margin: 0; font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.tpl-card p { margin: 0; font-size: var(--t-xs); color: var(--c-text-2); line-height: 1.5; flex: 1; }
.tpl-card__foot { display: flex; justify-content: space-between; align-items: center; font-size: 11px; color: var(--c-text-3); }

.cfg { max-width: 560px; }
.cfg__row { display: flex; align-items: center; justify-content: space-between; padding: var(--s-sm) 0; border-bottom: 1px solid var(--c-border-light); }
.cfg__row label { font-size: var(--t-sm); color: var(--c-text); }
.cfg__inline { display: flex; align-items: center; gap: 6px; font-size: var(--t-sm); color: var(--c-text-2); }
.cfg__foot { margin-top: var(--s-lg); display: flex; align-items: center; justify-content: flex-end; gap: var(--s-md); }
.cfg__upd { font-size: var(--t-xs); color: var(--c-text-3); }
.toggle { width: 40px; height: 22px; border-radius: 11px; background: var(--c-border); border: none; position: relative; cursor: pointer; transition: background .2s; padding: 0; }
.toggle span { position: absolute; top: 2px; left: 2px; width: 18px; height: 18px; border-radius: 50%; background: #fff; transition: left .2s; }
.toggle.on { background: var(--c-brand); }
.toggle.on span { left: 20px; }
.toggle.disabled { opacity: .5; cursor: not-allowed; }

.mono { font-family: ui-monospace, monospace; font-size: var(--t-xs); color: var(--c-text-2); }
.bind-form { display: flex; flex-direction: column; gap: var(--s-md); }
.bf-row { display: flex; flex-direction: column; gap: 6px; }
.bf-grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.fld-label { font-size: 13px; color: var(--c-text); line-height: 18px; }
.bf-textarea {
  width: 100%; border: 1px solid var(--c-border); border-radius: var(--r-md);
  padding: 8px 10px; font-size: var(--t-sm); color: var(--c-text); resize: vertical;
  font-family: inherit; line-height: 1.5; background: var(--c-surface);
}
.bf-textarea:focus { outline: none; border-color: var(--c-brand); }
.bf-divider {
  display: flex; align-items: baseline; gap: var(--s-sm); margin-top: var(--s-xs);
  padding-top: var(--s-md); border-top: 1px solid var(--c-border-light);
  font-size: var(--t-sm); font-weight: 600; color: var(--c-text);
}
.bf-divider__sub { font-size: 11px; font-weight: 400; color: var(--c-text-3); }
.bf-invoke { display: flex; align-items: center; gap: var(--s-sm); }
.bf-result {
  border: 1px solid var(--c-success, #16a34a); border-radius: var(--r-lg);
  padding: var(--s-md); display: flex; flex-direction: column; gap: var(--s-sm); background: var(--c-bg-page);
}
.bf-result__meta { display: flex; flex-wrap: wrap; align-items: center; gap: var(--s-sm); font-size: var(--t-xs); color: var(--c-text-2); }
.bf-result__content {
  margin: 0; white-space: pre-wrap; word-break: break-word;
  font-size: var(--t-sm); line-height: 1.6; color: var(--c-text); font-family: inherit;
  max-height: 240px; overflow-y: auto;
}
.bf-result--fail { border-color: var(--c-danger, #dc2626); }
.bf-result__err { margin: 0; font-size: var(--t-xs); color: var(--c-danger, #dc2626); line-height: 1.5; }
</style>
