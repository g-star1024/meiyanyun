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
import { useAuthStore } from '@/stores/auth'
import { useToast } from '@/composables/useToast'
import { errMsg } from '@/stores/m5Coupon'
import {
  logKpi, monthlyBill, listFeatures, saveFeatureRoles,
  getCfg, saveCfg,
  type AiKpi, type FeatureBill, type BindingView, type CfgView,
} from '@/api/ai'

const auth = useAuthStore()
const toast = useToast()
const canEdit = computed(() => auth.can('aiAdmin:edit'))

const tab = ref('perm')
const tabOptions = [
  { label: '灰度权限', value: 'perm' },
  { label: '用量计费', value: 'bill' },
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
  const [k, b] = await Promise.allSettled([logKpi(), monthlyBill()])
  if (k.status === 'fulfilled') kpis.value = toKpis(k.value)
  else toast.error('平台指标加载失败：' + errMsg(k.reason))
  if (b.status === 'fulfilled') bills.value = b.value
  else toast.error('用量账单加载失败：' + errMsg(b.reason))
  await Promise.all([loadMatrix(), loadCfg()])
}
onMounted(loadAll)
</script>

<template>
  <div class="a1-admin">
    <div class="kpis"><CKpi v-for="k in (kpis ?? [])" :key="k.label" v-bind="k" /></div>
    <CCard padding="lg">
      <CSegmented v-model="tab" :options="tabOptions" />

      <!-- 灰度权限 -->
      <div v-if="tab === 'perm'" class="mt">
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
          保存动作逐功能写入审计日志。当前绑定模型与启用状态请在各功能页查看。
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
</style>
