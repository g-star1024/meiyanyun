<script setup lang="ts">
/* ============================================================
 * 客户导入导出 /m3-io（M3-16）
 * 4 KPI + 导入/导出两大卡 + 导入历史/导出历史 tab。
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CSelect from '@/components/CSelect.vue'
import CCheckbox from '@/components/CCheckbox.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import { useCustomerIoStore, type ImportStatus, type ExportScope } from '@/stores/customerio'

const store = useCustomerIoStore()
onMounted(() => store.seed())

const kpis = computed(() => [
  { label: '本月导入', icon: 'upload', value: String(store.monthImportTotal), tone: 'brand' as const },
  { label: '本月导出', icon: 'export', value: String(store.monthExportTotal), tone: 'teal' as const },
  { label: '待校验', icon: 'alert', value: String(store.pending.length), tone: 'warning' as const },
  { label: '导入成功率', icon: 'upload', value: `${store.importSuccessRate}%`, tone: 'success' as const },
])

type HistTab = 'IMPORT' | 'EXPORT'
const tab = ref<HistTab>('IMPORT')

const importStatusMap: Record<ImportStatus, { text: string; status: 'warning' | 'primary' | 'success' | 'danger' }> = {
  PENDING: { text: '待校验', status: 'warning' },
  VALIDATING: { text: '校验中', status: 'primary' },
  DONE: { text: '已完成', status: 'success' },
  FAILED: { text: '失败', status: 'danger' },
}

function fmtTime(iso: string) {
  const d = new Date(iso)
  return `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

// 模拟上传
const uploadName = ref('')
const uploadTotal = ref(0)
function handleUpload() {
  // 模拟用户选了一个文件
  const total = 100 + Math.floor(Math.random() * 200)
  const failed = Math.random() < 0.3 ? Math.floor(Math.random() * 8) + 1 : 0
  store.createImport(`客户名单_${new Date().toISOString().slice(0, 10).replace(/-/g, '')}.xlsx`, total, failed)
  uploadName.value = `客户名单_${new Date().toISOString().slice(0, 10).replace(/-/g, '')}.xlsx`
  uploadTotal.value = total
  setTimeout(() => { uploadName.value = ''; uploadTotal.value = 0 }, 2500)
}
function downloadTpl() {
  // 占位
}

// 导出
const exportScope = ref<ExportScope>('ALL')
const maskPhone = ref(true)
const maskId = ref(true)
const scopeOptions = [
  { value: 'ALL', label: '全部客户', count: 1860 },
  { value: 'TAG', label: '按标签（高意向）', count: 248 },
  { value: 'LEVEL', label: '按等级（金卡以上）', count: 186 },
  { value: 'SEGMENT', label: '按分群（高价值）', count: 42 },
]
const selectedScope = computed(() => scopeOptions.find((o) => o.value === exportScope.value)!)
function doExport() {
  store.createExport({
    filter: selectedScope.value.label,
    scope: exportScope.value,
    count: selectedScope.value.count,
    maskPhone: maskPhone.value,
    maskId: maskId.value,
  })
}
</script>

<template>
  <div class="io">
    <div class="io__head">
      <div class="io__kpis">
        <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
      </div>
    </div>

    <!-- 导入 / 导出两大卡 -->
    <div class="io__actions">
      <CCard class="action-card action-card--import" padding="lg">
        <template #header>
          <h3 class="card-title"><CIcon name="upload" :size="18" /> 客户导入</h3>
        </template>
        <div class="dropzone" @click="handleUpload">
          <CIcon name="upload" :size="36" class="dropzone__icon" />
          <div class="dropzone__title">点击上传 Excel/CSV 文件</div>
          <div class="dropzone__hint">支持 .xlsx / .xls / .csv，单次最多 5000 条</div>
        </div>
        <div class="field-mapping">
          <div class="fm-title">字段映射预览</div>
          <div class="fm-row"><span>姓名</span><span class="arrow">→</span><span class="fm-tgt">customer.name</span></div>
          <div class="fm-row"><span>手机号</span><span class="arrow">→</span><span class="fm-tgt">customer.phone</span></div>
          <div class="fm-row"><span>性别</span><span class="arrow">→</span><span class="fm-tgt">customer.gender</span></div>
          <div class="fm-row"><span>等级</span><span class="arrow">→</span><span class="fm-tgt">customer.level</span></div>
        </div>
        <div class="action-card__foot">
          <CButton variant="ghost" size="sm" @click="downloadTpl">
            <CIcon name="export" :size="14" />下载模板
          </CButton>
          <span v-if="uploadName" class="upload-tip">
            <CIcon name="loading" :size="14" /> {{ uploadName }}（{{ uploadTotal }} 条）校验中…
          </span>
        </div>
      </CCard>

      <CCard class="action-card action-card--export" padding="lg">
        <template #header>
          <h3 class="card-title"><CIcon name="export" :size="18" /> 客户导出</h3>
        </template>
        <div class="export-form">
          <div class="form__row">
            <label class="form__label">筛选范围</label>
            <CSelect v-model="exportScope" :options="scopeOptions" width="100%" />
          </div>
          <div class="form__row">
            <label class="form__label">数据脱敏</label>
            <CCheckbox v-model="maskPhone">手机号脱敏（138****1234）</CCheckbox>
            <CCheckbox v-model="maskId">身份证号脱敏</CCheckbox>
          </div>
          <div class="export-summary">
            将导出 <strong>{{ selectedScope.count }}</strong> 条客户数据
            <span v-if="maskPhone || maskId" class="muted">（已脱敏{{ maskPhone ? '手机' : '' }}{{ maskPhone && maskId ? '、' : '' }}{{ maskId ? '身份证' : '' }}）</span>
          </div>
        </div>
        <div class="action-card__foot">
          <CButton variant="primary" v-perm.disable="'io:export'" @click="doExport">
            <CIcon name="export" :size="16" />立即导出 Excel
          </CButton>
        </div>
      </CCard>
    </div>

    <!-- 历史 -->
    <CCard padding="none">
      <div class="tabs">
        <button class="tab" :class="{ 'tab--active': tab === 'IMPORT' }" @click="tab = 'IMPORT'">
          导入历史（{{ store.imports.length }}）
        </button>
        <button class="tab" :class="{ 'tab--active': tab === 'EXPORT' }" @click="tab = 'EXPORT'">
          导出历史（{{ store.exports.length }}）
        </button>
      </div>

      <!-- 导入历史 -->
      <div v-if="tab === 'IMPORT'" class="table-wrap">
        <table class="dt">
          <thead>
            <tr>
              <th>文件名</th>
              <th class="num">总数</th>
              <th class="num">成功</th>
              <th class="num">失败</th>
              <th>状态</th>
              <th>错误</th>
              <th>操作人</th>
              <th>时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="t in store.imports" :key="t.id">
              <td><span class="fname"><CIcon name="order" :size="14" />{{ t.fileName }}</span></td>
              <td class="num">{{ t.total }}</td>
              <td class="num is-ok">{{ t.success }}</td>
              <td class="num" :class="{ 'is-err': t.failed > 0 }">{{ t.failed }}</td>
              <td><CStatusPill :status="importStatusMap[t.status].status">{{ importStatusMap[t.status].text }}</CStatusPill></td>
              <td>
                <span v-if="t.errors && t.errors.length" class="err-cell" :title="t.errors.join('\n')">
                  {{ t.errors[0] }}<em v-if="t.errors.length > 1"> 等 {{ t.errors.length }} 条</em>
                </span>
                <span v-else class="muted">—</span>
              </td>
              <td>{{ t.operator }}</td>
              <td class="muted">{{ fmtTime(t.createdAt) }}</td>
              <td>
                <button v-if="t.failed > 0" class="link-btn">
                  <CIcon name="export" :size="12" />错误报告
                </button>
                <span v-else class="muted">—</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 导出历史 -->
      <div v-else class="table-wrap">
        <table class="dt">
          <thead>
            <tr>
              <th>筛选条件</th>
              <th class="num">条数</th>
              <th>脱敏</th>
              <th>操作人</th>
              <th>时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="t in store.exports" :key="t.id">
              <td>{{ t.filter }}</td>
              <td class="num">{{ t.count }}</td>
              <td>
                <span class="mask-tags">
                  <i v-if="t.maskPhone" class="mask">手机</i>
                  <i v-if="t.maskId" class="mask">身份证</i>
                  <i v-if="!t.maskPhone && !t.maskId" class="nomask">未脱敏</i>
                </span>
              </td>
              <td>{{ t.operator }}</td>
              <td class="muted">{{ fmtTime(t.createdAt) }}</td>
              <td>
                <button class="link-btn">
                  <CIcon name="export" :size="12" />下载
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </CCard>
  </div>
</template>

<style scoped>
.io { display: flex; flex-direction: column; gap: var(--s-lg); }
.io__head { display: flex; justify-content: space-between; align-items: center; gap: var(--s-md); flex-wrap: wrap; }
.io__kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); flex: 1; min-width: 480px; }

.card-title { font-size: var(--t-md); font-weight: 700; margin: 0; display: inline-flex; align-items: center; gap: var(--s-xs); }

/* 操作两列 */
.io__actions { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-lg); }
.action-card { display: flex; flex-direction: column; min-height: 360px; }

/* dropzone */
.dropzone {
  border: 2px dashed var(--c-border);
  border-radius: var(--r-lg);
  padding: var(--s-xl) var(--s-lg);
  text-align: center;
  cursor: pointer;
  transition: border-color 0.2s, background 0.2s;
  display: flex; flex-direction: column; align-items: center; gap: var(--s-xs);
}
.dropzone:hover { border-color: var(--c-brand); background: var(--c-brand-soft); }
.dropzone__icon { color: var(--c-brand); }
.dropzone__title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.dropzone__hint { font-size: var(--t-xs); color: var(--c-text-3); }

/* 字段映射 */
.field-mapping { margin-top: var(--s-md); padding: var(--s-md); background: var(--c-disabled-bg); border-radius: var(--r-md); }
.fm-title { font-size: var(--t-xs); font-weight: 600; color: var(--c-text-3); margin-bottom: var(--s-sm); }
.fm-row { display: grid; grid-template-columns: 80px 20px 1fr; gap: var(--s-sm); align-items: center; font-size: var(--t-xs); padding: 3px 0; color: var(--c-text-2); }
.fm-row .arrow { color: var(--c-text-4); text-align: center; }
.fm-tgt { color: var(--c-brand); font-family: var(--t-font-mono, ui-monospace, monospace); }

.action-card__foot { display: flex; justify-content: space-between; align-items: center; margin-top: auto; padding-top: var(--s-md); border-top: 1px solid var(--c-border-light); }
.upload-tip { display: inline-flex; align-items: center; gap: 4px; font-size: var(--t-xs); color: var(--c-brand); }

/* 导出表单 */
.export-form { display: flex; flex-direction: column; gap: var(--s-md); padding: var(--s-sm) 0; flex: 1; }
.form__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.form__label { font-size: var(--t-xs); color: var(--c-text-3); }
.export-summary { padding: var(--s-md); background: var(--c-brand-soft); border-radius: var(--r-md); font-size: var(--t-sm); color: var(--c-text); }
.export-summary strong { color: var(--c-brand); font-size: var(--t-md); font-weight: 700; margin: 0 2px; }
.muted { color: var(--c-text-3); font-size: var(--t-xs); }

/* tabs */
.tabs { display: flex; border-bottom: 1px solid var(--c-border-light); padding: 0 var(--s-md); }
.tab { padding: var(--s-md) var(--s-lg); font-size: var(--t-sm); color: var(--c-text-2); background: none; border: none; border-bottom: 2px solid transparent; cursor: pointer; }
.tab:hover { color: var(--c-text); }
.tab--active { color: var(--c-brand); border-bottom-color: var(--c-brand); font-weight: 600; }

/* table */
.table-wrap { overflow-x: auto; }
.dt { width: 100%; border-collapse: collapse; font-size: var(--t-sm); }
.dt th { padding: var(--s-sm) var(--s-md); text-align: left; font-size: var(--t-xs); font-weight: 600; color: var(--c-text-3); background: var(--c-disabled-bg); border-bottom: 1px solid var(--c-border-light); white-space: nowrap; }
.dt td { padding: var(--s-sm) var(--s-md); border-bottom: 1px solid var(--c-border-light); color: var(--c-text-2); vertical-align: middle; }
.dt tr:hover td { background: var(--c-brand-soft); }
.dt .num { text-align: right; font-variant-numeric: tabular-nums; }
.dt .is-ok { color: var(--c-success-fg); font-weight: 600; }
.dt .is-err { color: var(--c-danger-fg); font-weight: 600; }
.fname { display: inline-flex; align-items: center; gap: 6px; color: var(--c-text); font-weight: 500; }
.err-cell { font-size: var(--t-xs); color: var(--c-danger-fg); }
.err-cell em { font-style: normal; color: var(--c-text-3); }
.link-btn { display: inline-flex; align-items: center; gap: 4px; font-size: var(--t-xs); color: var(--c-brand); background: none; border: none; cursor: pointer; padding: 2px 6px; border-radius: var(--r-sm); }
.link-btn:hover { background: var(--c-brand-soft); }

.mask-tags { display: inline-flex; gap: 4px; }
.mask-tags .mask { font-style: normal; font-size: 10px; padding: 1px 6px; background: var(--c-warning-bg); color: var(--c-warning-fg); border-radius: var(--r-sm); }
.mask-tags .nomask { font-style: normal; font-size: 10px; padding: 1px 6px; background: var(--c-disabled-bg); color: var(--c-text-3); border-radius: var(--r-sm); }

@media (max-width: 1024px) {
  .io__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .io__actions { grid-template-columns: 1fr; }
}
</style>
