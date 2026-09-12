<script setup lang="ts">
/* B42 模型与治理 /ai/providers — AI 供应商与模型接入（OpenAI 兼容）
   数据源 ai-service：/api/ai/providers、/api/ai/models（真实出网连通性测试）。
   Key 红线：只写不读，列表仅回显掩码；编辑时留空或保持掩码（含 *）= 不修改。 */
import { computed, onMounted, reactive, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CTable from '@/components/CTable.vue'
import CSelect from '@/components/CSelect.vue'
import CInput from '@/components/CInput.vue'
import CDrawer from '@/components/CDrawer.vue'
import { useAuthStore } from '@/stores/auth'
import { useToast } from '@/composables/useToast'
import { errMsg } from '@/stores/m5Coupon'
import {
  listProviders, createProvider, updateProvider, deleteProvider,
  listModels, createModel, updateModel, deleteModel, testModel,
  type ProviderView, type ModelView, type ModelTestResult,
} from '@/api/ai'

const auth = useAuthStore()
const toast = useToast()
const canEdit = computed(() => auth.can('aiAdmin:edit'))

const loading = ref(false)
const providers = ref<ProviderView[]>([])
const models = ref<ModelView[]>([])
const providerFilter = ref('')

const providerCols = [
  { key: 'providerCode', label: '供应商编码', width: '140' },
  { key: 'providerName', label: '供应商名称' },
  { key: 'baseUrl', label: '请求地址（base_url）' },
  { key: 'protocol', label: '协议', width: '90' },
  { key: 'apiKeyMask', label: 'API 密钥', width: '170' },
  { key: 'modelCount', label: '模型数', width: '80', align: 'center' as const },
  { key: 'enabled', label: '状态', width: '90' },
  { key: 'ops', label: '操作', width: '150' },
]
const modelCols = [
  { key: 'modelCode', label: '模型 ID', width: '170' },
  { key: 'displayName', label: '展示名称', width: '180' },
  { key: 'providerName', label: '供应商', width: '130' },
  { key: 'capabilities', label: '能力', width: '150' },
  { key: 'contextWindow', label: '上下文', width: '100', align: 'right' as const },
  { key: 'price', label: '价格(元/千tok)', width: '150', align: 'right' as const },
  { key: 'priority', label: '优先级', width: '80', align: 'center' as const },
  { key: 'enabled', label: '启用', width: '80' },
  { key: 'connStatus', label: '连通状态', width: '110' },
  { key: 'connCheckedAt', label: '最近测试', width: '150' },
  { key: 'ops', label: '操作', width: '230' },
]

const providerOptions = computed(() => [
  { label: '全部供应商', value: '' },
  ...providers.value.map((p) => ({ label: p.providerName, value: String(p.providerId) })),
])
const filteredModels = computed(() => {
  const pid = Number(providerFilter.value)
  return providerFilter.value ? models.value.filter((m) => m.providerId === pid) : models.value
})

// -------------------- 供应商抽屉 --------------------
const pDrawer = ref(false)
const pEditing = ref<ProviderView | null>(null)
const pSaving = ref(false)
const pForm = reactive({
  providerCode: '', providerName: '', baseUrl: '', apiKey: '', protocol: 'OPENAI', enabled: true,
})

function openProviderCreate() {
  pEditing.value = null
  Object.assign(pForm, { providerCode: '', providerName: '', baseUrl: '', apiKey: '', protocol: 'OPENAI', enabled: true })
  pDrawer.value = true
}
function openProviderEdit(row: ProviderView) {
  pEditing.value = row
  Object.assign(pForm, {
    providerCode: row.providerCode,
    providerName: row.providerName,
    baseUrl: row.baseUrl,
    apiKey: row.apiKeyMask ?? '',
    protocol: row.protocol || 'OPENAI',
    enabled: row.enabled,
  })
  pDrawer.value = true
}
async function saveProvider() {
  pSaving.value = true
  try {
    const cmd = {
      providerCode: pForm.providerCode.trim(),
      providerName: pForm.providerName.trim(),
      baseUrl: pForm.baseUrl.trim(),
      apiKey: pForm.apiKey.trim(),
      protocol: pForm.protocol,
      enabled: pForm.enabled,
    }
    if (pEditing.value) {
      const r = await updateProvider(pEditing.value.providerId, cmd)
      toast.success(r.changed ? '供应商已更新' : '配置无变化，未产生更新')
    } else {
      await createProvider(cmd)
      toast.success('供应商已接入，密钥已加密存储')
    }
    pDrawer.value = false
    await loadAll()
  } catch (e) {
    toast.error('供应商保存失败：' + errMsg(e))
  } finally {
    pSaving.value = false
  }
}
async function removeProvider(row: ProviderView) {
  if (!window.confirm(`确认删除供应商「${row.providerName}」？删除后不可恢复。`)) return
  try {
    await deleteProvider(row.providerId)
    toast.success('供应商已删除')
    await loadAll()
  } catch (e) {
    toast.error('删除失败：' + errMsg(e))
  }
}

// -------------------- 模型抽屉 --------------------
const mDrawer = ref(false)
const mEditing = ref<ModelView | null>(null)
const mSaving = ref(false)
const testing = ref<Record<number, boolean>>({})
const testResults = ref<Record<number, ModelTestResult>>({})
const mForm = reactive({
  providerId: '', modelCode: '', displayName: '', capabilities: 'CHAT',
  contextWindow: '', temperature: '', topP: '', maxTokens: '', priority: '100',
  enabled: true, inputPrice: '', outputPrice: '',
})
const modelProviderOptions = computed(() =>
  providers.value.map((p) => ({ label: `${p.providerName}（${p.providerCode}）`, value: String(p.providerId) })),
)
const capabilityOptions = ['CHAT', 'EMBEDDING', 'VISION']

function blankModel(providerId = '') {
  Object.assign(mForm, {
    providerId, modelCode: '', displayName: '', capabilities: 'CHAT',
    contextWindow: '', temperature: '', topP: '', maxTokens: '', priority: '100',
    enabled: true, inputPrice: '', outputPrice: '',
  })
}
function openModelCreate() {
  if (!providers.value.length) {
    toast.warning('请先接入供应商后再添加模型')
    return
  }
  mEditing.value = null
  blankModel(providerFilter.value || String(providers.value[0].providerId))
  mDrawer.value = true
}
function openModelEdit(row: ModelView) {
  mEditing.value = row
  Object.assign(mForm, {
    providerId: String(row.providerId),
    modelCode: row.modelCode,
    displayName: row.displayName,
    capabilities: row.capabilities || 'CHAT',
    contextWindow: row.contextWindow ?? '',
    temperature: row.temperature ?? '',
    topP: row.topP ?? '',
    maxTokens: row.maxTokens ?? '',
    priority: String(row.priority ?? 100),
    enabled: row.enabled,
    inputPrice: row.inputPrice ?? '',
    outputPrice: row.outputPrice ?? '',
  })
  mDrawer.value = true
}
function numOrNull(v: string): number | null {
  const t = v.trim()
  if (t === '') return null
  const n = Number(t)
  return Number.isFinite(n) ? n : null
}
function toggleCap(cap: string) {
  const set = new Set(mForm.capabilities.split(',').map((s) => s.trim()).filter(Boolean))
  if (set.has(cap)) set.delete(cap)
  else set.add(cap)
  mForm.capabilities = capabilityOptions.filter((c) => set.has(c)).join(',') || 'CHAT'
}
async function saveModel() {
  mSaving.value = true
  try {
    const cmd = {
      providerId: Number(mForm.providerId),
      modelCode: mForm.modelCode.trim(),
      displayName: mForm.displayName.trim(),
      capabilities: mForm.capabilities,
      contextWindow: numOrNull(mForm.contextWindow),
      temperature: numOrNull(mForm.temperature),
      topP: numOrNull(mForm.topP),
      maxTokens: numOrNull(mForm.maxTokens),
      priority: numOrNull(mForm.priority) ?? 100,
      enabled: mForm.enabled,
      inputPrice: numOrNull(mForm.inputPrice),
      outputPrice: numOrNull(mForm.outputPrice),
    }
    if (mEditing.value) {
      const r = await updateModel(mEditing.value.modelId, cmd)
      toast.success(r.changed ? '模型已更新' : '配置无变化，未产生更新')
    } else {
      await createModel(cmd)
      toast.success('模型已添加，可在操作列做真实连通性测试')
    }
    mDrawer.value = false
    await loadAll()
  } catch (e) {
    toast.error('模型保存失败：' + errMsg(e))
  } finally {
    mSaving.value = false
  }
}
async function removeModel(row: ModelView) {
  if (!window.confirm(`确认删除模型「${row.displayName}」？删除后不可恢复。`)) return
  try {
    await deleteModel(row.modelId)
    toast.success('模型已删除')
    await loadAll()
  } catch (e) {
    toast.error('删除失败：' + errMsg(e))
  }
}
async function runTest(row: ModelView) {
  testing.value[row.modelId] = true
  try {
    const r = await testModel(row.modelId)
    testResults.value[row.modelId] = r
    if (r.success) toast.success(`连通正常：${r.message}（${r.latencyMs}ms）`)
    else toast.error('连通失败：' + r.message)
    await loadAll()
  } catch (e) {
    toast.error('连通性测试失败：' + errMsg(e))
  } finally {
    testing.value[row.modelId] = false
  }
}

// -------------------- 加载与展示辅助 --------------------
async function loadAll() {
  loading.value = true
  try {
    const [ps, ms] = await Promise.all([listProviders(), listModels()])
    providers.value = ps
    models.value = ms
  } catch (e) {
    toast.error('AI 接入数据加载失败：' + errMsg(e))
  } finally {
    loading.value = false
  }
}
onMounted(loadAll)

function enabledPill(on: boolean) {
  return on ? 'success' : 'disabled'
}
function connPill(s: string | null) {
  if (s === 'SUCCESS') return 'success' as const
  if (s === 'FAIL') return 'danger' as const
  return 'default' as const
}
function connLabel(s: string | null) {
  if (s === 'SUCCESS') return '连通正常'
  if (s === 'FAIL') return '连通失败'
  return '未测试'
}
function fmtTime(s: string | null) {
  if (!s) return '—'
  return s.replace('T', ' ').slice(0, 16)
}
function fmtPrice(inP: number | null, outP: number | null) {
  if (inP == null && outP == null) return '—'
  return `入 ${inP ?? '—'} / 出 ${outP ?? '—'}`
}
function fmtCtx(v: number | null) {
  return v == null ? '—' : v.toLocaleString()
}
function capsLabel(raw: string) {
  const map: Record<string, string> = { CHAT: '对话', EMBEDDING: '向量', VISION: '视觉' }
  return raw.split(',').map((c) => map[c] ?? c).join('、')
}
</script>

<template>
  <div class="ai-providers">
    <CCard padding="lg">
      <template #header>
        <div class="card-head">
          <div>
            <h3 class="card-head__title">AI 供应商接入</h3>
            <p class="card-head__sub">支持 OpenAI Chat Completions 兼容协议；密钥 AES-GCM 加密落库，页面仅显示掩码。</p>
          </div>
          <CButton v-if="canEdit" size="sm" variant="primary" @click="openProviderCreate">接入供应商</CButton>
        </div>
      </template>
      <CTable :columns="providerCols" :rows="providers" row-key="providerId" stripe :empty-text="loading ? '加载中…' : '尚未接入供应商，点击右上角接入'">
        <template #col-baseUrl="{ value }"><span class="mono">{{ value }}</span></template>
        <template #col-apiKeyMask="{ row }">
          <span v-if="row.hasApiKey" class="mono">{{ row.apiKeyMask }}</span>
          <span v-else class="muted">未配置</span>
        </template>
        <template #col-enabled="{ value }">
          <CStatusPill :status="enabledPill(value)" dot>{{ value ? '启用' : '停用' }}</CStatusPill>
        </template>
        <template #col-ops="{ row }">
          <template v-if="canEdit">
            <CButton size="sm" variant="text" @click="openProviderEdit(row as ProviderView)">编辑</CButton>
            <CButton size="sm" variant="text" @click="removeProvider(row as ProviderView)">删除</CButton>
          </template>
          <span v-else class="muted">—</span>
        </template>
      </CTable>
    </CCard>

    <CCard padding="lg">
      <template #header>
        <div class="card-head">
          <div>
            <h3 class="card-head__title">模型仓库</h3>
            <p class="card-head__sub">模型 ID 需与供应商侧完全一致；保存后请执行真实连通性测试。</p>
          </div>
          <div class="card-head__ops">
            <CSelect v-model="providerFilter" :options="providerOptions" width="180px" />
            <CButton v-if="canEdit" size="sm" variant="primary" @click="openModelCreate">添加模型</CButton>
          </div>
        </div>
      </template>
      <CTable :columns="modelCols" :rows="filteredModels" row-key="modelId" stripe empty-text="暂无模型，请先添加">
        <template #col-modelCode="{ value }"><span class="mono">{{ value }}</span></template>
        <template #col-capabilities="{ value }">{{ capsLabel(value) }}</template>
        <template #col-contextWindow="{ value }">{{ fmtCtx(value) }}</template>
        <template #col-price="{ row }">{{ fmtPrice(row.inputPrice, row.outputPrice) }}</template>
        <template #col-enabled="{ value }">
          <CStatusPill :status="enabledPill(value)" dot>{{ value ? '启用' : '停用' }}</CStatusPill>
        </template>
        <template #col-connStatus="{ row }">
          <CStatusPill :status="connPill(row.connStatus)" dot>{{ connLabel(row.connStatus) }}</CStatusPill>
        </template>
        <template #col-connCheckedAt="{ value }">{{ fmtTime(value) }}</template>
        <template #col-ops="{ row }">
          <template v-if="canEdit">
            <CButton size="sm" variant="text" :disabled="testing[row.modelId]" @click="runTest(row as ModelView)">
              {{ testing[row.modelId] ? '测试中…' : '连通测试' }}
            </CButton>
            <CButton size="sm" variant="text" @click="openModelEdit(row as ModelView)">编辑</CButton>
            <CButton size="sm" variant="text" @click="removeModel(row as ModelView)">删除</CButton>
          </template>
          <span v-else class="muted">—</span>
        </template>
      </CTable>
      <p v-if="Object.keys(testResults).length" class="hint">
        最近一次测试结果以模型行内「连通状态 / 最近测试」为准，失败原因已回写并计入审计日志。
      </p>
    </CCard>

    <!-- 供应商编辑抽屉 -->
    <CDrawer v-model:show="pDrawer" :title="pEditing ? '编辑供应商' : '接入供应商'" size="md">
      <div class="form-grid">
        <div class="form-span2">
          <CInput v-model="pForm.providerCode" label="供应商编码" placeholder="如 volc-ark（2~64 位英文/数字/-/_）" :disabled="!!pEditing" />
        </div>
        <div class="form-span2">
          <CInput v-model="pForm.providerName" label="供应商名称" placeholder="如 火山方舟" />
        </div>
        <div class="form-span2">
          <CInput v-model="pForm.baseUrl" label="自定义请求地址（base_url）" placeholder="https://ark.cn-beijing.volces.com/api/coding/v3（不要以斜杠结尾）" />
        </div>
        <div>
          <label class="fld-label">API 协议</label>
          <CSelect v-model="pForm.protocol" :options="[{ label: 'OpenAI Chat Completions', value: 'OPENAI' }]" width="100%" />
        </div>
        <div>
          <label class="fld-label">状态</label>
          <CSelect :model-value="pForm.enabled ? '1' : '0'" width="100%"
            :options="[{ label: '启用', value: '1' }, { label: '停用', value: '0' }]"
            @update:model-value="pForm.enabled = $event === '1'" />
        </div>
        <div class="form-span2">
          <CInput v-model="pForm.apiKey" type="password"
            :label="pEditing ? 'API 密钥（留空或保持掩码不变表示不修改）' : 'API 密钥（新建必填）'"
            placeholder="粘贴供应商 API Key，保存后仅显示掩码" />
        </div>
      </div>
      <template #footer>
        <CButton variant="secondary" @click="pDrawer = false">取消</CButton>
        <CButton variant="primary" :disabled="pSaving" @click="saveProvider">{{ pSaving ? '保存中…' : '保存' }}</CButton>
      </template>
    </CDrawer>

    <!-- 模型编辑抽屉 -->
    <CDrawer v-model:show="mDrawer" :title="mEditing ? '编辑模型' : '添加模型'" size="md">
      <div class="form-grid">
        <div class="form-span2">
          <label class="fld-label">所属供应商</label>
          <CSelect v-model="mForm.providerId" :options="modelProviderOptions" width="100%" :disabled="!!mEditing" />
        </div>
        <div>
          <CInput v-model="mForm.modelCode" label="模型 ID" placeholder="如 ark-code-latest" />
        </div>
        <div>
          <CInput v-model="mForm.displayName" label="模型展示名称" placeholder="如 ark-code-latest-coding" />
        </div>
        <div class="form-span2">
          <label class="fld-label">能力（可多选）</label>
          <div class="cap-row">
            <button
              v-for="c in capabilityOptions" :key="c" type="button"
              class="cap-chip" :class="{ on: mForm.capabilities.split(',').includes(c) }"
              @click="toggleCap(c)"
            >{{ { CHAT: '对话', EMBEDDING: '向量', VISION: '视觉' }[c] }}</button>
          </div>
        </div>
        <div>
          <CInput v-model="mForm.contextWindow" type="number" label="上下文窗口（tokens）" placeholder="如 128000" />
        </div>
        <div>
          <CInput v-model="mForm.maxTokens" type="number" label="最大输出 tokens" placeholder="如 4096" />
        </div>
        <div>
          <CInput v-model="mForm.temperature" type="number" label="temperature（0~2）" placeholder="默认留空" />
        </div>
        <div>
          <CInput v-model="mForm.topP" type="number" label="top_p（0~1）" placeholder="默认留空" />
        </div>
        <div>
          <CInput v-model="mForm.priority" type="number" label="优先级（小者优先）" placeholder="100" />
        </div>
        <div>
          <CInput v-model="mForm.inputPrice" type="number" label="输入价（元/千tokens）" placeholder="可选" />
        </div>
        <div>
          <CInput v-model="mForm.outputPrice" type="number" label="输出价（元/千tokens）" placeholder="可选" />
        </div>
        <div>
          <label class="fld-label">状态</label>
          <CSelect :model-value="mForm.enabled ? '1' : '0'" width="100%"
            :options="[{ label: '启用', value: '1' }, { label: '停用', value: '0' }]"
            @update:model-value="mForm.enabled = $event === '1'" />
        </div>
      </div>
      <template #footer>
        <CButton variant="secondary" @click="mDrawer = false">取消</CButton>
        <CButton variant="primary" :disabled="mSaving" @click="saveModel">{{ mSaving ? '保存中…' : '保存' }}</CButton>
      </template>
    </CDrawer>
  </div>
</template>

<style scoped>
.ai-providers { display: flex; flex-direction: column; gap: var(--s-lg); }
.card-head { display: flex; align-items: center; justify-content: space-between; gap: var(--s-md); width: 100%; }
.card-head__title { margin: 0; font-size: var(--t-md); font-weight: 700; color: var(--c-text); }
.card-head__sub { margin: 4px 0 0; font-size: var(--t-xs); color: var(--c-text-3); }
.card-head__ops { display: flex; align-items: center; gap: var(--s-sm); }
.mono { font-family: ui-monospace, monospace; font-size: var(--t-xs); color: var(--c-text-2); }
.muted { color: var(--c-text-3); font-size: var(--t-xs); }
.hint { font-size: var(--t-xs); color: var(--c-text-3); margin: var(--s-sm) 0 0; }

.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.form-span2 { grid-column: span 2; display: flex; flex-direction: column; gap: 6px; }
.fld-label { font-size: 13px; color: var(--c-text); line-height: 18px; }
.cap-row { display: flex; gap: var(--s-xs); }
.cap-chip {
  height: 32px; padding: 0 var(--s-md); border-radius: var(--r-capsule);
  border: 1px solid var(--c-border); background: var(--c-surface);
  font-size: var(--t-sm); color: var(--c-text-2); cursor: pointer; transition: all .15s;
}
.cap-chip.on { border-color: var(--c-brand); background: var(--c-brand-soft); color: var(--c-brand); font-weight: 600; }
@media (max-width: 1024px) {
  .form-grid { grid-template-columns: 1fr; }
  .form-span2 { grid-column: span 1; }
}
</style>
