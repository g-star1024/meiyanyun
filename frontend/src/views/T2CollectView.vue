<script setup lang="ts">
/* ============================================================
 * T2-01 数据采集 /data/collect
 * 数据源 + 同步任务，KPI×4，新建数据源 Drawer
 * ============================================================ */
import { computed, onMounted, reactive, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CSegmented from '@/components/CSegmented.vue'
import CDrawer from '@/components/CDrawer.vue'
import { useT2DataCollectStore, type SourceType, type SyncMode, type DataSource } from '@/stores/t2DataCollect'
import { useAuthStore } from '@/stores/auth'

const store = useT2DataCollectStore()
const auth = useAuthStore()
onMounted(() => store.seed())

const tab = ref<'sources' | 'jobs'>('sources')
const tabOpts = [
  { value: 'sources', label: '数据源列表' },
  { value: 'jobs', label: '同步任务' },
]

const kpis = computed(() => [
  { label: '数据源总数', icon: 'settings', value: String(store.sources.length), tone: 'brand' as const },
  { label: '已连接', icon: 'settings', value: String(store.connectedCount), tone: 'success' as const },
  { label: '今日同步行数', icon: 'calendar', value: store.todayRows.toLocaleString(), tone: 'teal' as const },
  { label: '异常源', icon: 'alert', value: String(store.errorSources), tone: store.errorSources > 0 ? 'danger' as const : 'text' as const },
])

// 状态 pill 映射
function statusPill(s: DataSource['status']) {
  return s === 'CONNECTED' ? 'success' : s === 'SYNCING' ? 'primary' : s === 'ERROR' ? 'danger' : 'disabled'
}
function jobStatusPill(s: 'RUNNING' | 'SUCCESS' | 'FAILED') {
  return s === 'SUCCESS' ? 'success' : s === 'RUNNING' ? 'primary' : 'danger'
}
function sourceIcon(t: SourceType) {
  if (t === 'MYSQL' || t === 'POSTGRES') return 'box'
  if (t === 'KAFKA' || t === 'LOG') return 'volume'
  if (t === 'API') return 'settings'
  return 'package'
}

function fmtTime(iso: string | null) {
  if (!iso) return '—'
  const d = new Date(iso)
  return `${d.getMonth() + 1}/${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

// ---- 新建数据源 ----
const showCreate = ref(false)
const form = reactive({
  name: '', type: 'MYSQL' as SourceType, host: '', port: '', database: '',
  endpoint: '', username: '', password: '', syncMode: 'BATCH' as SyncMode,
})
const typeOptions = [
  { value: 'MYSQL', label: 'MySQL' },
  { value: 'POSTGRES', label: 'PostgreSQL' },
  { value: 'KAFKA', label: 'Kafka' },
  { value: 'API', label: 'REST API' },
  { value: 'LOG', label: '日志采集' },
  { value: 'THIRD_PARTY', label: '三方平台' },
]
const syncOpts = [
  { value: 'BATCH', label: '批量同步' },
  { value: 'REALTIME', label: '实时同步' },
]
const canSubmit = computed(() => form.name.trim() && form.host.trim())
function openCreate() {
  Object.assign(form, {
    name: '', type: 'MYSQL', host: '', port: '', database: '',
    endpoint: '', username: '', password: '', syncMode: 'BATCH',
  })
  showCreate.value = true
}
function submitCreate() {
  if (!canSubmit.value) return
  store.createSource({
    name: form.name,
    type: form.type,
    host: form.host,
    port: form.port ? Number(form.port) : undefined,
    database: form.database || undefined,
    endpoint: form.endpoint || undefined,
    username: form.username || undefined,
    syncMode: form.syncMode,
  })
  showCreate.value = false
}

// ---- 测试 / 同步 反馈 ----
const testing = ref<Set<string>>(new Set())
const syncing = ref<Set<string>>(new Set())
function doTest(s: DataSource) {
  testing.value.add(s.id)
  store.testConnection(s.id)
  setTimeout(() => testing.value.delete(s.id), 800)
}
function doSync(s: DataSource) {
  syncing.value.add(s.id)
  store.triggerSync(s.id, 'INCREMENTAL')
  setTimeout(() => syncing.value.delete(s.id), 1200)
}

// 编辑（简化：复用创建表单）
function doEdit(s: DataSource) {
  Object.assign(form, {
    name: s.name, type: s.type, host: s.host,
    port: s.port ? String(s.port) : '', database: s.database ?? '',
    endpoint: s.endpoint ?? '', username: s.username ?? '', password: '',
    syncMode: s.syncMode,
  })
  editingId.value = s.id
  showCreate.value = true
}
const editingId = ref<string | null>(null)
function submitEdit() {
  if (!editingId.value || !canSubmit.value) return
  store.updateSource(editingId.value, {
    name: form.name, host: form.host,
    port: form.port ? Number(form.port) : undefined,
    database: form.database || undefined,
    endpoint: form.endpoint || undefined,
    syncMode: form.syncMode,
  })
  showCreate.value = false
  editingId.value = null
}

function onSubmit() { editingId.value ? submitEdit() : submitCreate() }
</script>

<template>
  <div class="col">
    <div class="col__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <CCard class="col__main" padding="none">
      <div class="col__toolbar">
        <CSegmented v-model="tab" :options="tabOpts" size="sm" />
        <div class="col__toolbar-right">
          <CButton variant="secondary" size="sm" @click="store.seed()">
            <CIcon name="loading" :size="16" />刷新
          </CButton>
          <CButton v-if="tab === 'sources' && auth.can('collect:create')" variant="primary" size="sm" @click="openCreate">
            <CIcon name="plus" :size="16" />新建数据源
          </CButton>
        </div>
      </div>

      <!-- 数据源列表 -->
      <div v-if="tab === 'sources'" class="src-grid">
        <div v-for="s in store.sources" :key="s.id" class="src-card">
          <div class="src-card__head">
            <div class="src-card__title">
              <span class="src-card__icon"><CIcon :name="sourceIcon(s.type)" :size="18" /></span>
              <div>
                <div class="src-card__name">{{ s.name }}</div>
                <div class="src-card__meta">{{ store.SOURCE_TYPE_LABEL[s.type] }} · {{ store.SYNC_MODE_LABEL[s.syncMode] }}</div>
              </div>
            </div>
            <CStatusPill :status="statusPill(s.status)" dot>{{ store.SOURCE_STATUS_LABEL[s.status] }}</CStatusPill>
          </div>
          <div class="src-card__body">
            <div class="src-line">
              <span class="src-line__label">连接地址</span>
              <code class="src-line__val">{{ s.host }}<template v-if="s.port">:{{ s.port }}</template><template v-if="s.database">/{{ s.database }}</template><template v-if="s.endpoint">{{ s.endpoint }}</template></code>
            </div>
            <div class="src-line">
              <span class="src-line__label">负责人</span>
              <span class="src-line__val">{{ s.owner }}<template v-if="s.username"> · {{ s.username }}</template></span>
            </div>
            <div class="src-line">
              <span class="src-line__label">累计同步</span>
              <span class="src-line__val num">{{ s.totalRows.toLocaleString() }} 行</span>
            </div>
            <div class="src-line">
              <span class="src-line__label">最后同步</span>
              <span class="src-line__val">{{ fmtTime(s.lastSyncAt) }}<template v-if="s.lastRows"> · {{ s.lastRows.toLocaleString() }} 行</template></span>
            </div>
            <div v-if="s.errorMsg" class="src-err">
              <CIcon name="alert" :size="14" />{{ s.errorMsg }}
            </div>
          </div>
          <div class="src-card__foot">
            <CButton size="sm" variant="ghost" :disabled="!auth.can('collect:edit') || testing.has(s.id)" @click="doTest(s)">
              <CIcon name="shield" :size="14" />{{ testing.has(s.id) ? '测试中...' : '测试连接' }}
            </CButton>
            <CButton size="sm" variant="ghost" :disabled="!auth.can('collect:sync') || syncing.has(s.id) || s.status === 'SYNCING'" @click="doSync(s)">
              <CIcon name="loading" :size="14" />{{ syncing.has(s.id) || s.status === 'SYNCING' ? '同步中' : '立即同步' }}
            </CButton>
            <CButton size="sm" variant="ghost" :disabled="!auth.can('collect:edit')" @click="doEdit(s)">
              <CIcon name="edit" :size="14" />编辑
            </CButton>
          </div>
        </div>
      </div>

      <!-- 同步任务 -->
      <table v-else class="ctable">
        <thead>
          <tr>
            <th>数据源</th>
            <th style="width:120px">同步类型</th>
            <th style="width:110px">状态</th>
            <th style="width:140px" class="num">同步行数</th>
            <th style="width:180px">开始时间</th>
            <th>备注</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="j in store.jobs" :key="j.id">
            <td>{{ j.sourceName }}</td>
            <td>
              <span class="tag" :class="j.type === 'FULL' ? 'tag--primary' : 'tag--info'">
                {{ j.type === 'FULL' ? '全量' : '增量' }}
              </span>
            </td>
            <td><CStatusPill :status="jobStatusPill(j.status)" dot>{{ j.status === 'SUCCESS' ? '成功' : j.status === 'RUNNING' ? '运行中' : '失败' }}</CStatusPill></td>
            <td class="num">{{ j.rowsSynced.toLocaleString() }}</td>
            <td>{{ fmtTime(j.startedAt) }}</td>
            <td class="muted">{{ j.errorMsg || '—' }}</td>
          </tr>
        </tbody>
      </table>
    </CCard>

    <!-- 新建/编辑数据源抽屉 -->
    <CDrawer :show="showCreate" :title="editingId ? '编辑数据源' : '新建数据源'" size="md" @update:show="(v: boolean) => { showCreate = v; if (!v) editingId = null }">
      <div class="form">
        <CInput v-model="form.name" label="数据源名称" placeholder="例如：门店交易主库" />
        <CSelect v-model="form.type" :options="typeOptions" width="100%" />
        <CInput v-model="form.host" label="Host / Endpoint" placeholder="mysql.internal 或 https://api.xxx.com" />
        <div class="form__row">
          <div class="form__field">
            <label class="fld-label">端口</label>
            <input v-model="form.port" type="number" class="native-input" placeholder="3306" />
          </div>
          <div class="form__field">
            <CInput v-model="form.database" label="数据库 / Topic" placeholder="meiyun_core" />
          </div>
        </div>
        <CInput v-model="form.endpoint" label="API 路径（可选）" placeholder="/api/v1/orders" />
        <div class="form__row">
          <div class="form__field">
            <CInput v-model="form.username" label="用户名" placeholder="ro_reader" />
          </div>
          <div class="form__field">
            <CInput v-model="form.password" type="password" label="密码 / Token" placeholder="••••••••" />
          </div>
        </div>
        <div class="form__field">
          <label class="fld-label">同步模式</label>
          <CSelect v-model="form.syncMode" :options="syncOpts" width="100%" />
        </div>
      </div>
      <template #footer>
        <CButton variant="ghost" @click="showCreate = false">取消</CButton>
        <CButton variant="primary" :disabled="!canSubmit" @click="onSubmit">{{ editingId ? '保存' : '创建' }}</CButton>
      </template>
    </CDrawer>
  </div>
</template>

<style scoped>
.col { display: flex; flex-direction: column; gap: var(--s-md); }
.col__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
.col__head :deep(.ckpi) { min-width: 0; }
@media (max-width: 1024px) {
  .col__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); }
}

.col__main :deep(.card__body) { display: flex; flex-direction: column; gap: var(--s-md); padding: 0; }
.col__toolbar {
  display: flex; align-items: center; gap: var(--s-sm);
  padding: var(--s-md) var(--s-lg) 0;
}
.col__toolbar-right { display: flex; align-items: center; gap: var(--s-sm); margin-left: auto; flex-shrink: 0; }

.src-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: var(--s-md);
  padding: 0 var(--s-lg) var(--s-lg);
}
.src-card {
  display: flex;
  flex-direction: column;
  gap: var(--s-sm);
  padding: var(--s-md);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--r-lg);
  transition: border-color .15s, box-shadow .15s;
}
.src-card:hover { border-color: var(--c-brand-border); box-shadow: var(--shadow-card); }
.src-card__head { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--s-sm); }
.src-card__title { display: flex; gap: var(--s-sm); align-items: center; min-width: 0; }
.src-card__icon {
  width: 36px; height: 36px; flex-shrink: 0;
  display: inline-flex; align-items: center; justify-content: center;
  background: var(--c-brand-soft); color: var(--c-brand);
  border-radius: var(--r-md);
}
.src-card__name { font-size: var(--t-md); font-weight: 700; color: var(--c-text); line-height: 1.3; }
.src-card__meta { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }
.src-card__body { display: flex; flex-direction: column; gap: 6px; padding: var(--s-xs) 0; border-top: 1px dashed var(--c-border); border-bottom: 1px dashed var(--c-border); }
.src-line { display: flex; justify-content: space-between; gap: var(--s-sm); font-size: var(--t-xs); }
.src-line__label { color: var(--c-text-3); flex-shrink: 0; }
.src-line__val { color: var(--c-text-2); text-align: right; word-break: break-all; }
.src-line__val.num { font-variant-numeric: tabular-nums; color: var(--c-text); font-weight: 600; }
.src-line__val code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 11px; background: var(--c-bg-page); padding: 2px 6px; border-radius: var(--r-sm); }
.src-err {
  display: flex; align-items: center; gap: 4px;
  font-size: var(--t-xs); color: var(--c-danger-fg);
  background: var(--c-danger-bg); padding: 6px 8px; border-radius: var(--r-sm);
}
.src-card__foot { display: flex; gap: var(--s-xs); }
.src-card__foot :deep(.cbtn) { flex: 1; padding: 0 var(--s-xs); }

.ctable { width: 100%; border-collapse: collapse; font-size: var(--t-sm); }
.ctable thead th { padding: 12px var(--s-lg); background: var(--c-bg-page); color: var(--c-text); font-weight: 600; font-size: var(--t-xs); text-align: left; border-bottom: 1px solid var(--c-border); white-space: nowrap; }
.ctable tbody td { padding: 14px var(--s-lg); color: var(--c-text-2); border-bottom: 1px solid var(--c-border); vertical-align: middle; }
.ctable tbody tr:last-child td { border-bottom: none; }
.ctable tbody tr:hover { background: var(--c-brand-soft); }
.ctable .num { text-align: right; font-variant-numeric: tabular-nums; }
.ctable .muted { color: var(--c-text-3); font-size: var(--t-xs); }
.ctable th.num { text-align: right; }

.tag { display: inline-flex; align-items: center; padding: 2px 8px; border-radius: var(--r-pill); font-size: var(--t-xs); font-weight: 500; }
.tag--primary { color: var(--c-brand); background: var(--c-brand-soft); }
.tag--info { color: var(--c-info-fg); background: var(--c-info-bg); }

.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.form__field { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.fld-label { font-size: 13px; font-weight: 400; color: var(--c-text); line-height: 18px; }
.native-input {
  width: 100%; padding: 10px; border: 1px solid #D1D1D9; border-radius: var(--r-sm);
  background: var(--c-surface); font-size: 13px; color: var(--c-text); line-height: 20px;
  font-family: inherit; outline: none; transition: border-color .15s, box-shadow .15s;
}
.native-input:focus { border-color: #4D5AD9; box-shadow: 0 0 0 2px rgba(77, 90, 217, 0.12); }
</style>
