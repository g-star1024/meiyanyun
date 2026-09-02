<template>
  <div class="mr">
    <!-- 头部：4 KPI + 主按钮 -->
    <div class="mr__head">
      <CKpi v-for="k in kpis" :key="k.label" :value="k.value" :label="k.label" :tone="k.tone" :icon="k.icon" />
    </div>

    <!-- Tab 切换 + 主操作（按钮随 tab 作用于当前列表） -->
    <div class="mr__bar">
      <CSegmented v-model="tab" :options="tabOptions" />
      <CButton v-if="tab === 'models'" variant="primary" :disabled="!store.can('model:register')" @click="openRegister">
        <CIcon name="box" :size="16" />注册模型
      </CButton>
      <CButton v-else variant="primary" @click="openDatasetRegister">
        <CIcon name="upload" :size="16" />录入训练集
      </CButton>
    </div>

    <!-- 模型列表 -->
    <template v-if="tab === 'models'">
    <!-- 模型卡片网格 -->
    <div class="mr__grid">
      <CCard
        v-for="m in store.models"
        :key="m.id"
        padding="lg"
        class="mcard"
        :class="{ 'mcard--pub': m.status === 'PUBLISHED' }"
      >
        <div class="mcard__head">
          <div class="mcard__title">
            <span class="mcard__name">{{ m.name }}</span>
            <CStatusPill :status="statusPill(m.status)" dot>{{ store.MODEL_STATUS_LABEL[m.status] }}</CStatusPill>
          </div>
          <CStatusPill status="info">{{ store.MODEL_TYPE_LABEL[m.type] }}</CStatusPill>
        </div>
        <div class="mcard__meta">
          <span><CIcon name="user" :size="13" />{{ m.owner }}</span>
          <span><CIcon name="org" :size="13" />{{ m.department }}</span>
          <span v-if="m.currentVersion"><CIcon name="package" :size="13" />v{{ m.currentVersion }}</span>
        </div>
        <p class="mcard__desc">{{ m.description }}</p>
        <div class="mcard__tags" v-if="m.tags.length">
          <span class="tag" v-for="t in m.tags" :key="t">{{ t }}</span>
        </div>
        <div class="mcard__metrics">
          <div><label>30天调用</label><strong>{{ m.callCount30d.toLocaleString() }}</strong></div>
          <div><label>P99延迟</label><strong>{{ m.avgLatencyMs }}ms</strong></div>
          <div><label>错误率</label><strong :class="{ 'is-danger': m.errorRate > 1 }">{{ m.errorRate }}%</strong></div>
        </div>
        <div class="mcard__actions">
          <CButton size="sm" variant="secondary" @click="openDetail(m)">详情/版本</CButton>
          <CButton
            v-if="m.status === 'READY' && store.can('model:release')"
            size="sm" variant="primary"
            @click="askRelease(m)"
          >发布</CButton>
          <CButton
            v-if="m.status === 'PUBLISHED' && store.can('model:rollback')"
            size="sm" variant="ghost"
            @click="rollback(m)"
          >回滚</CButton>
          <CButton
            v-if="m.status !== 'DEPRECATED'"
            size="sm" variant="text"
            @click="deprecate(m)"
          >废弃</CButton>
        </div>
      </CCard>
    </div>
    </template>

    <!-- A1-13 训练数据管理（红线：授权来源/去标识/保留期三要素） -->
    <template v-else>
      <CCard padding="lg">
        <template #header>
          <div class="card-head">
            <h3>训练数据集</h3>
            <span class="card-sub">红线：训练集须具备「授权来源 / 去标识策略 / 保留期」三要素，缺一不得提交审批</span>
          </div>
        </template>
        <CTable :columns="datasetCols" :rows="datasets" row-key="id" stripe>
          <template #col-compliance="{ row }">
            <div class="comp-cell">
              <span v-for="c in row.compliance" :key="c.label" class="comp-chip" :class="`comp-chip--${c.ok ? 'ok' : 'warn'}`">
                <CIcon :name="c.ok ? 'check' : 'alert'" :size="11" />{{ c.label }}
              </span>
            </div>
          </template>
          <template #col-count="{ row }">
            <span v-if="row.authorized" class="num">{{ row.count.toLocaleString() }}</span>
            <span v-else class="num-pending">待授权核验</span>
          </template>
          <template #col-quality="{ value }">
            <div class="with-bar">
              <span>{{ value }}%</span>
              <CProgressBar :value="value" :max="100" color="var(--c-teal)" :height="4" :show-label="false" />
            </div>
          </template>
          <template #col-status="{ value }">
            <CStatusPill :status="value === '可用' ? 'success' : value === '待核验' ? 'warning' : 'info'">{{ value }}</CStatusPill>
          </template>
          <template #col-actions="{ row }">
            <CButton size="sm" variant="secondary" @click="viewDataset(row)">查看</CButton>
            <CButton
              v-if="!row.authorized"
              size="sm" variant="primary"
              @click="authorizeDataset(row)"
            >提交授权核验</CButton>
          </template>
        </CTable>
      </CCard>

      <div class="redline-bar">
        <span class="redline-bar__title">A1-13 训练数据合规</span>
        <span class="redline-bar__text">语料条数在授权核验通过前标注「待授权核验」，不按已可用统计；训练集须同时具备授权来源、去标识策略、保留期三项，缺一不得提交 T3-01 发布审批；误报/反馈数据（A1-04/A1-06）回流须经 A1-17 脱敏。</span>
      </div>
    </template>

    <!-- 详情抽屉：版本列表 + schema -->
    <CDrawer :show="detailOpen" title="模型详情与版本" size="lg" @update:show="detailOpen = $event">
      <template v-if="current">
        <div class="detail">
          <div class="detail__head">
            <div>
              <div class="detail__name">{{ current.name }}</div>
              <div class="detail__sub">{{ current.department }} · {{ current.owner }} · {{ store.MODEL_TYPE_LABEL[current.type] }}</div>
            </div>
            <CStatusPill :status="statusPill(current.status)" dot>{{ store.MODEL_STATUS_LABEL[current.status] }}</CStatusPill>
          </div>
          <p class="detail__desc">{{ current.description }}</p>

          <CSectionCard title="版本列表" class="detail__sec">
            <div class="ver-list">
              <div v-for="v in [...current.versions].reverse()" :key="v.version" class="ver-row">
                <div class="ver-row__main">
                  <div class="ver-row__top">
                    <span class="ver-row__ver">v{{ v.version }}</span>
                    <CStatusPill :status="versionPill(v.status)" dot>{{ store.MODEL_STATUS_LABEL[v.status] }}</CStatusPill>
                    <span v-if="v.approvedBy" class="ver-row__app">审批人：{{ v.approvedBy }}</span>
                  </div>
                  <div class="ver-row__metrics">
                    <span v-for="(val, key) in v.metrics" :key="key" class="metric-chip">
                      {{ key }}: {{ val }}
                    </span>
                  </div>
                  <div class="ver-row__time">
                    <span>训练：{{ formatTime(v.trainedAt) }}</span>
                    <span v-if="v.publishedAt">发布：{{ formatTime(v.publishedAt) }}</span>
                    <span v-if="v.remark" class="ver-row__remark">{{ v.remark }}</span>
                  </div>
                </div>
                <div class="ver-row__ops">
                  <CButton
                    size="sm" variant="primary"
                    :disabled="v.status !== 'READY' || !store.can('model:release')"
                    @click="askRelease(current, v.version)"
                  >发布</CButton>
                  <CButton
                    size="sm" variant="secondary"
                    :disabled="v.status !== 'PUBLISHED' || !store.can('model:rollback')"
                    @click="rollback(current, v.version)"
                  >回滚</CButton>
                </div>
              </div>
              <div v-if="!current.versions.length" class="empty">暂无版本</div>
            </div>
          </CSectionCard>

          <div class="detail__grid">
            <CSectionCard title="输入 Schema">
              <pre class="schema">{{ current.inputSchema }}</pre>
            </CSectionCard>
            <CSectionCard title="输出 Schema">
              <pre class="schema">{{ current.outputSchema }}</pre>
            </CSectionCard>
          </div>
        </div>
      </template>
    </CDrawer>

    <!-- 注册模型抽屉 -->
    <CDrawer :show="registerOpen" title="注册模型" size="md" @update:show="registerOpen = $event">
      <div class="form">
        <CInput v-model="form.name" label="模型名称" placeholder="如：客户复购预测" />
        <div class="form__row">
          <div class="form__field">
            <label class="flabel">类型</label>
            <CSelect v-model="form.type" :options="typeOptions" width="100%" />
          </div>
          <CInput v-model="form.owner" label="负责人" placeholder="姓名" />
        </div>
        <CInput v-model="form.department" label="所属部门" placeholder="如：数据智能部" />
        <CTextarea v-model="form.description" label="模型描述" :rows="3" placeholder="模型业务目标、使用场景、输入输出说明" />
        <CTextarea v-model="form.inputSchema" label="输入 Schema (JSON)" :rows="5" placeholder='{ "customer_id": "string" }' />
        <CTextarea v-model="form.outputSchema" label="输出 Schema (JSON)" :rows="5" placeholder='{ "score": "float" }' />
        <CInput v-model="form.tagsStr" label="标签（逗号分隔）" placeholder="客户运营,流失预警" />
      </div>
      <template #footer>
        <CButton variant="ghost" @click="registerOpen = false">取消</CButton>
        <CButton variant="primary" :disabled="!canSubmitRegister" @click="submitRegister">提交注册</CButton>
      </template>
    </CDrawer>

    <!-- 发布红线确认弹层（CCard 确认框） -->
    <div v-if="releaseAsk" class="modal-mask" @click.self="releaseAsk = null">
      <CCard class="modal" padding="lg">
        <div class="modal__head">
          <CIcon name="alert" :size="22" class="modal__icon" />
          <span class="modal__title">模型发布审批确认</span>
        </div>
        <p class="modal__text">
          模型发布需经 <strong>T3-01 审批流程</strong>，确认提交？
        </p>
        <p class="modal__sub" v-if="releaseAsk">
          模型：{{ releaseAsk.model.name }} · 版本 v{{ releaseAsk.version }}
        </p>
        <div class="modal__ops">
          <CButton variant="ghost" @click="releaseAsk = null">取消</CButton>
          <CButton variant="primary" @click="confirmRelease">确认提交审批</CButton>
        </div>
      </CCard>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CKpi from '@/components/CKpi.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CTextarea from '@/components/CTextarea.vue'
import CDrawer from '@/components/CDrawer.vue'
import CSectionCard from '@/components/CSectionCard.vue'
import CSegmented from '@/components/CSegmented.vue'
import CTable from '@/components/CTable.vue'
import CProgressBar from '@/components/CProgressBar.vue'
import {
  useT4ModelStore,
  type ModelRecord,
  type ModelStatus,
  type ModelType,
} from '@/stores/t4Model'

const store = useT4ModelStore()
onMounted(() => store.seed())

const kpis = computed(() => [
  { label: '模型总数', icon: 'settings', value: String(store.kpi.total), tone: 'text' as const },
  { label: '训练中', icon: 'settings', value: String(store.kpi.training), tone: 'brand' as const },
  { label: '已发布', icon: 'check-square', value: String(store.kpi.published), tone: 'success' as const },
  { label: '近30天调用', icon: 'settings', value: store.kpi.calls.toLocaleString(), tone: 'teal' as const },
])

// ---- Tab：模型 / 训练数据（A1-13）----
const tab = ref('models')
const tabOptions = [
  { label: '模型管理', value: 'models' },
  { label: '训练数据（A1-13）', value: 'datasets' },
]

interface DatasetRow {
  id: string
  name: string
  sourceModel: string
  count: number
  authorized: boolean
  quality: number
  status: string
  retention: string
  compliance: { label: string; ok: boolean }[]
  createdAt: string
}

const datasetCols = [
  { key: 'name', label: '数据集名称', width: '200' },
  { key: 'sourceModel', label: '关联模型', width: '160' },
  { key: 'compliance', label: '合规三要素', width: '280' },
  { key: 'count', label: '语料条数', width: '120', align: 'right' as const },
  { key: 'quality', label: '标注质量', width: '140' },
  { key: 'retention', label: '保留期', width: '100' },
  { key: 'status', label: '状态', width: '100' },
  { key: 'actions', label: '操作', width: '160' },
]

const datasets = ref<DatasetRow[]>([
  {
    id: 'ds-1', name: '流失预测训练集 v3', sourceModel: '客户流失预测 v3',
    count: 48200, authorized: true, quality: 94, status: '可用', retention: '24个月',
    compliance: [
      { label: '授权来源', ok: true }, { label: '去标识', ok: true }, { label: '保留期', ok: true },
    ],
    createdAt: '2026-08-06',
  },
  {
    id: 'ds-2', name: '皮肤影像标注集', sourceModel: '皮肤问题影像分类',
    count: 12800, authorized: true, quality: 91, status: '可用', retention: '36个月',
    compliance: [
      { label: '授权来源', ok: true }, { label: '去标识', ok: true }, { label: '保留期', ok: true },
    ],
    createdAt: '2026-08-10',
  },
  {
    id: 'ds-3', name: '客服对话语料-0815', sourceModel: '智能客服意图识别',
    count: 0, authorized: false, quality: 0, status: '待核验', retention: '待设定',
    compliance: [
      { label: '授权来源', ok: false }, { label: '去标识', ok: true }, { label: '保留期', ok: false },
    ],
    createdAt: '2026-08-15',
  },
  {
    id: 'ds-4', name: '营销文案反馈集', sourceModel: '营销文案生成',
    count: 3640, authorized: true, quality: 87, status: '可用', retention: '12个月',
    compliance: [
      { label: '授权来源', ok: true }, { label: '去标识', ok: true }, { label: '保留期', ok: true },
    ],
    createdAt: '2026-08-12',
  },
  {
    id: 'ds-5', name: '敏感词误报回流集', sourceModel: '敏感词检测（A1-04）',
    count: 0, authorized: false, quality: 0, status: '待核验', retention: '待设定',
    compliance: [
      { label: '授权来源', ok: false }, { label: '去标识', ok: false }, { label: '保留期', ok: false },
    ],
    createdAt: '2026-08-25',
  },
])

function openDatasetRegister() {
  window.alert('训练集录入：须填写授权来源/去标识策略/保留期三要素')
}

function viewDataset(row: Record<string, any>) {
  const r = row as DatasetRow
  const info = [
    `数据集：${r.name}`,
    `关联模型：${r.sourceModel}`,
    `语料条数：${r.authorized ? r.count.toLocaleString() : '待授权核验'}`,
    `标注质量：${r.quality}%`,
    `保留期：${r.retention}`,
    `合规三要素：${r.compliance.map((c: { label: string; ok: boolean }) => c.label + (c.ok ? '✓' : '✗')).join(' / ')}`,
  ].join('\n')
  window.alert(info)
}

function authorizeDataset(row: Record<string, any>) {
  const r = row as DatasetRow
  if (!window.confirm(`确认提交「${r.name}」的授权核验申请？核验通过后语料条数才计入可用统计。`)) return
  r.authorized = true
  r.count = 12400
  r.quality = 85
  r.status = '可用'
  r.retention = '12个月'
  r.compliance = [
    { label: '授权来源', ok: true }, { label: '去标识', ok: true }, { label: '保留期', ok: true },
  ]
  window.alert('授权核验已提交，审批通过后数据可用于训练（T3-01 审批流）。')
}

function statusPill(s: ModelStatus) {
  return ({ DRAFT: 'disabled', TRAINING: 'primary', READY: 'warning', PUBLISHED: 'success', DEPRECATED: 'info' } as const)[s]
}
function versionPill(s: ModelStatus) { return statusPill(s) }

function formatTime(iso: string) {
  const d = new Date(iso)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

// ---- 详情抽屉 ----
const detailOpen = ref(false)
const current = ref<ModelRecord | null>(null)
function openDetail(m: ModelRecord) {
  current.value = m
  detailOpen.value = true
}

// ---- 注册抽屉 ----
const registerOpen = ref(false)
const form = reactive({
  name: '', type: 'CLASSIFICATION' as ModelType,
  description: '', owner: '', department: '',
  inputSchema: '{}', outputSchema: '{}', tagsStr: '',
})
const typeOptions = [
  { label: '分类', value: 'CLASSIFICATION' },
  { label: '回归', value: 'REGRESSION' },
  { label: '自然语言', value: 'NLP' },
  { label: '计算机视觉', value: 'CV' },
  { label: '推荐', value: 'RECOMMEND' },
  { label: '生成式', value: 'GENERATIVE' },
]
const canSubmitRegister = computed(() => form.name.trim() && form.owner.trim() && form.department.trim())

function openRegister() {
  if (!store.can('model:register')) return
  Object.assign(form, { name: '', type: 'CLASSIFICATION', description: '', owner: '', department: '', inputSchema: '{}', outputSchema: '{}', tagsStr: '' })
  registerOpen.value = true
}
function submitRegister() {
  store.registerModel({
    name: form.name.trim(),
    type: form.type,
    description: form.description.trim(),
    owner: form.owner.trim(),
    department: form.department.trim(),
    inputSchema: form.inputSchema,
    outputSchema: form.outputSchema,
    tags: form.tagsStr.split(',').map((s) => s.trim()).filter(Boolean),
  })
  registerOpen.value = false
}

// ---- 发布红线确认 ----
const releaseAsk = ref<{ model: ModelRecord; version: string } | null>(null)
function askRelease(m: ModelRecord, version?: string) {
  // 找到该模型当前可发布版本：优先指定 version；否则取最新 READY 版本
  let v = version
  if (!v) {
    const ready = [...m.versions].reverse().find((x) => x.status === 'READY')
    if (!ready) {
      window.alert('仅 READY 状态的版本可发布（红线：非 DRAFT 不能发布）')
      return
    }
    v = ready.version
  }
  releaseAsk.value = { model: m, version: v! }
}
function confirmRelease() {
  if (!releaseAsk.value) return
  const { model, version } = releaseAsk.value
  const r = store.requestRelease(model.id, version)
  if (!r.ok) {
    window.alert(r.reason || '提交失败')
  } else {
    // 演示：提交后立即模拟 T3-01 审批通过
    store.releaseModel(model.id, version)
    window.alert('已提交 T3-01 审批流程，审批通过后模型已发布。')
  }
  releaseAsk.value = null
}

function rollback(m: ModelRecord, version?: string) {
  if (!store.can('model:rollback')) return
  const v = version ?? m.currentVersion
  if (!v) return
  store.rollbackModel(m.id, v)
}
function deprecate(m: ModelRecord) {
  if (!window.confirm(`确认废弃模型「${m.name}」？`)) return
  store.deprecateModel(m.id)
}
</script>

<style scoped>
.mr { display: flex; flex-direction: column; gap: var(--s-lg); }
.mr__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
.mr__bar { display: flex; align-items: center; justify-content: space-between; gap: var(--s-md); flex-wrap: wrap; }

.mr__grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--s-md); }
.mr :deep(.mcard .card__body) { display: flex; flex-direction: column; gap: var(--s-md); }
.mcard--pub { border-color: var(--c-success-border, var(--c-brand-border)); }
.mcard__head { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--s-sm); }
.mcard__title { display: flex; flex-direction: column; gap: var(--s-xs); min-width: 0; }
.mcard__name { font-size: var(--t-md); font-weight: 700; color: var(--c-text); }
.mcard__meta { display: flex; gap: var(--s-md); font-size: var(--t-xs); color: var(--c-text-2); flex-wrap: wrap; }
.mcard__meta span { display: inline-flex; align-items: center; gap: 4px; }
.mcard__desc { font-size: var(--t-sm); color: var(--c-text-2); line-height: 1.6; margin: 0; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.mcard__tags { display: flex; flex-wrap: wrap; gap: 4px; }
.tag { padding: 2px 8px; border-radius: var(--r-pill); background: var(--c-bg-page); color: var(--c-text-2); font-size: var(--t-xs); }
.mcard__metrics { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--s-sm); padding: var(--s-sm); background: var(--c-bg-page); border-radius: var(--r-md); }
.mcard__metrics > div { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.mcard__metrics label { font-size: 10px; color: var(--c-text-3); }
.mcard__metrics strong { font-size: var(--t-base); color: var(--c-text); font-variant-numeric: tabular-nums; }
.mcard__metrics .is-danger { color: var(--c-danger-fg); }
.mcard__actions { display: flex; gap: var(--s-xs); flex-wrap: wrap; }

/* 详情抽屉 */
.detail { display: flex; flex-direction: column; gap: var(--s-md); }
.detail__head { display: flex; justify-content: space-between; align-items: flex-start; gap: var(--s-md); }
.detail__name { font-size: var(--t-lg); font-weight: 700; color: var(--c-text); }
.detail__sub { font-size: var(--t-xs); color: var(--c-text-2); margin-top: 4px; }
.detail__desc { font-size: var(--t-sm); color: var(--c-text-2); line-height: 1.7; margin: 0; }
.detail__sec { margin-top: var(--s-xs); }
.ver-list { display: flex; flex-direction: column; gap: var(--s-sm); }
.ver-row { display: flex; gap: var(--s-md); padding: var(--s-sm); border: 1px solid var(--c-border-light); border-radius: var(--r-md); align-items: flex-start; }
.ver-row__main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 4px; }
.ver-row__top { display: flex; align-items: center; gap: var(--s-sm); }
.ver-row__ver { font-weight: 700; font-size: var(--t-sm); color: var(--c-text); }
.ver-row__app { font-size: var(--t-xs); color: var(--c-text-3); margin-left: auto; }
.ver-row__metrics { display: flex; flex-wrap: wrap; gap: 4px; }
.metric-chip { padding: 2px 8px; border-radius: var(--r-pill); background: var(--c-brand-soft); color: var(--c-brand); font-size: var(--t-xs); font-variant-numeric: tabular-nums; }
.ver-row__time { display: flex; gap: var(--s-md); font-size: var(--t-xs); color: var(--c-text-3); flex-wrap: wrap; }
.ver-row__remark { color: var(--c-warning-fg); }
.ver-row__ops { display: flex; flex-direction: column; gap: 4px; flex-shrink: 0; }
.detail__grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.schema { margin: 0; padding: var(--s-sm); background: var(--c-bg-page); border-radius: var(--r-md); font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: var(--t-xs); line-height: 1.6; color: var(--c-text-2); white-space: pre-wrap; word-break: break-all; max-height: 240px; overflow: auto; }
.empty { padding: var(--s-lg); text-align: center; color: var(--c-text-3); font-size: var(--t-sm); }

/* 注册抽屉表单 */
.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.form__field { display: flex; flex-direction: column; gap: 6px; }
.flabel { font-size: 13px; color: var(--c-text); line-height: 18px; }

/* 发布红线确认弹层 */
.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, 0.45); z-index: 1100; display: flex; align-items: center; justify-content: center; padding: var(--s-xl); }
.modal { width: 440px; max-width: 100%; }
.modal__head { display: flex; align-items: center; gap: var(--s-sm); }
.modal__icon { color: var(--c-warning-fg); }
.modal__title { font-size: var(--t-lg); font-weight: 700; color: var(--c-text); }
.modal__text { font-size: var(--t-base); color: var(--c-text); line-height: 1.7; margin: var(--s-md) 0 var(--s-xs); }
.modal__sub { font-size: var(--t-sm); color: var(--c-text-2); margin: 0 0 var(--s-md); }
.modal__ops { display: flex; justify-content: flex-end; gap: var(--s-sm); }

@media (max-width: 1100px) {
  .mr__grid { grid-template-columns: repeat(2, 1fr); }
}
@media (max-width: 720px) {
  .mr__grid { grid-template-columns: 1fr; }
  .detail__grid { grid-template-columns: 1fr; }
  .form__row { grid-template-columns: 1fr; }
}
</style>
