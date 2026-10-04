<script setup lang="ts">
/* ============================================================
 * T2-01 数据采集 /data/collect
 * 采集通道（touch_event 五通道聚合）+ 触点时间线 + 数据源注册（棒⑥卡7 真源化），KPI×4
 * 数据源注册：CDC/Kafka/三方注册登记（V72 落库）；三方 API 真实连通探测，
 * CDC/Kafka 接入运行时归 v2 移交（DESIGN-T2 §6）如实不伪造。
 * ============================================================ */
import { computed, onMounted, reactive, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CTextarea from '@/components/CTextarea.vue'
import CDrawer from '@/components/CDrawer.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CSegmented from '@/components/CSegmented.vue'
import { useT2DataCollectStore, type ChannelCard } from '@/stores/t2DataCollect'
import { useT2DataSourceStore, type DataSourceType } from '@/stores/t2DataSource'
import { useAuthStore } from '@/stores/auth'
import type { TouchType } from '@/api/touchEvent'

const store = useT2DataCollectStore()
const dsStore = useT2DataSourceStore()
const auth = useAuthStore()
onMounted(() => { store.seed(); dsStore.seed() })

const tab = ref<'channels' | 'events' | 'sources'>('channels')
const tabOpts = [
  { value: 'channels', label: '采集通道' },
  { value: 'events', label: '触点时间线' },
  { value: 'sources', label: '数据源注册' },
]

const kpis = computed(() => [
  { label: '采集通道', icon: 'layers', value: String(store.channels.length), tone: 'brand' as const },
  { label: '活跃通道', icon: 'check-square', value: String(store.activeCount), tone: 'success' as const },
  { label: '今日触点', icon: 'clock', value: store.todayTouches.toLocaleString(), tone: 'teal' as const },
  { label: '待接入', icon: 'alert', value: String(store.emptyCount), tone: store.emptyCount > 0 ? 'danger' as const : 'text' as const },
])

const CHANNEL_ICON: Record<TouchType, 'navigate' | 'edit' | 'scan' | 'marketing' | 'handover'> = {
  LANDING_VISIT: 'navigate',
  LANDING_LEAD: 'edit',
  POSTER_SCAN: 'scan',
  PUSH_SEND: 'marketing',
  RETURNBACK: 'handover',
}

function statusPill(s: ChannelCard['status']) {
  return s === 'ACTIVE' ? 'success' : 'disabled'
}

/** 时间线触点类型 tag 色 */
function touchTagClass(t: TouchType) {
  switch (t) {
    case 'LANDING_VISIT': return 'tag--primary'
    case 'LANDING_LEAD': return 'tag--success'
    case 'POSTER_SCAN': return 'tag--info'
    case 'PUSH_SEND': return 'tag--warn'
    default: return 'tag--purple'
  }
}

function fmtTime(iso: string | null) {
  if (!iso) return '—'
  const d = new Date(iso)
  return `${d.getMonth() + 1}/${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

// ---- 数据源注册（棒⑥卡7：CDC/Kafka/三方注册登记真源化；接入运行时归 v2） ----
function dsStatusPill(s: 'REGISTERED' | 'CONNECTED' | 'DISABLED') {
  return s === 'CONNECTED' ? 'success' : s === 'REGISTERED' ? 'info' : 'disabled'
}
function dsTypeTagClass(t: DataSourceType) {
  return t === 'CDC' ? 'tag--primary' : t === 'KAFKA' ? 'tag--warn' : 'tag--success'
}

const showDsForm = ref(false)
const dsEditingId = ref<number | null>(null)
const dsSaving = ref(false)
const dsForm = reactive({
  code: '', name: '', type: 'THIRD_PARTY' as DataSourceType, endpoint: '', description: '',
})
const dsTypeOptions = [
  { value: 'CDC', label: 'CDC（数据库变更捕获）' },
  { value: 'KAFKA', label: 'Kafka（消息流）' },
  { value: 'THIRD_PARTY', label: '三方 API（支持连通探测）' },
]
const canDsSubmit = computed(() =>
  dsForm.name.trim() && (dsEditingId.value !== null || /^[A-Z][A-Z0-9_]{0,63}$/.test(dsForm.code.trim())))

function openDsCreate() {
  Object.assign(dsForm, { code: '', name: '', type: 'THIRD_PARTY', endpoint: '', description: '' })
  dsEditingId.value = null
  showDsForm.value = true
}
function openDsEdit(id: number) {
  const d = dsStore.getSource(id)
  if (!d) return
  Object.assign(dsForm, {
    code: d.code, name: d.name, type: d.type,
    endpoint: d.endpoint ?? '', description: d.description,
  })
  dsEditingId.value = id
  showDsForm.value = true
}
async function submitDs() {
  if (!canDsSubmit.value || dsSaving.value) return
  dsSaving.value = true
  try {
    const ok = dsEditingId.value
      ? await dsStore.updateSource(dsEditingId.value, {
          name: dsForm.name.trim(),
          endpoint: dsForm.endpoint.trim() || undefined,
          description: dsForm.description.trim(),
        })
      : !!(await dsStore.createSource({
          code: dsForm.code.trim(),
          name: dsForm.name.trim(),
          type: dsForm.type,
          endpoint: dsForm.endpoint.trim() || undefined,
          description: dsForm.description.trim(),
        }))
    if (ok) {
      showDsForm.value = false
      dsEditingId.value = null
    }
  } finally {
    dsSaving.value = false
  }
}
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
          <span v-if="tab === 'sources' ? dsStore.loadError : store.loadError" class="load-err"><CIcon name="alert" :size="14" />{{ tab === 'sources' ? dsStore.loadError : store.loadError }}</span>
          <CButton v-if="tab === 'sources' && auth.can('collect:create')" variant="primary" size="sm" @click="openDsCreate">
            <CIcon name="plus" :size="16" />新建数据源
          </CButton>
          <CButton variant="secondary" size="sm" :disabled="tab === 'sources' ? dsStore.loading : store.loading" @click="tab === 'sources' ? dsStore.load() : store.load()">
            <CIcon name="refresh" :size="16" />{{ (tab === 'sources' ? dsStore.loading : store.loading) ? '加载中…' : '刷新' }}
          </CButton>
        </div>
      </div>

      <!-- 采集通道（数据源列表＝五通道聚合） -->
      <div v-if="tab === 'channels'" class="src-grid">
        <div v-for="c in store.channels" :key="c.touchType" class="src-card">
          <div class="src-card__head">
            <div class="src-card__title">
              <span class="src-card__icon"><CIcon :name="CHANNEL_ICON[c.touchType]" :size="18" /></span>
              <div>
                <div class="src-card__name">{{ c.name }}</div>
                <div class="src-card__meta">{{ c.touchType }}</div>
              </div>
            </div>
            <CStatusPill :status="statusPill(c.status)" dot>{{ c.status === 'ACTIVE' ? '已接入' : '待接入' }}</CStatusPill>
          </div>
          <div class="src-card__body">
            <div class="src-line">
              <span class="src-line__label">接入方式</span>
              <span class="src-line__val">{{ c.desc }}</span>
            </div>
            <div class="src-line">
              <span class="src-line__label">累计触点</span>
              <span class="src-line__val num">{{ c.count.toLocaleString() }}</span>
            </div>
            <div class="src-line">
              <span class="src-line__label">今日触点</span>
              <span class="src-line__val num">{{ c.todayCount.toLocaleString() }}</span>
            </div>
            <div class="src-line">
              <span class="src-line__label">最近触点</span>
              <span class="src-line__val">{{ fmtTime(c.latestAt) }}</span>
            </div>
          </div>
        </div>
        <div v-if="store.loaded && store.channels.length === 0" class="empty-hint">暂无通道数据</div>
      </div>

      <!-- 触点时间线（同步任务＝触点倒序流） -->
      <table v-else-if="tab === 'events'" class="ctable">
        <thead>
          <tr>
            <th style="width:130px">触点类型</th>
            <th style="width:110px">渠道</th>
            <th style="width:120px">客户</th>
            <th>来源</th>
            <th style="width:150px">发生时刻</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="e in store.events" :key="e.id">
            <td><span class="tag" :class="touchTagClass(e.touchType)">{{ store.TOUCH_TYPE_LABEL[e.touchType] ?? e.touchType }}</span></td>
            <td>{{ store.channelLabel(e.channel) }}</td>
            <td class="muted">{{ e.customerId || '—' }}</td>
            <td class="muted">{{ store.refTypeLabel(e.refType) }}<template v-if="e.refId"> #{{ e.refId }}</template></td>
            <td>{{ fmtTime(e.at) }}</td>
          </tr>
          <tr v-if="store.loaded && store.events.length === 0">
            <td colspan="5" class="empty-cell">暂无触点数据</td>
          </tr>
        </tbody>
      </table>

      <!-- 数据源注册（棒⑥卡7：CDC/Kafka/三方注册登记；接入运行时归 v2） -->
      <table v-else class="ctable">
        <thead>
          <tr>
            <th style="width:150px">编码</th>
            <th>名称</th>
            <th style="width:100px">类型</th>
            <th>接入地址</th>
            <th style="width:90px">状态</th>
            <th style="width:80px">负责人</th>
            <th style="width:120px">最近同步</th>
            <th style="width:210px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="d in dsStore.sources" :key="d.id">
            <td><code class="tbl">{{ d.code }}</code></td>
            <td>
              <div class="rulename">{{ d.name }}</div>
              <div class="rulename__sub">{{ d.description }}</div>
            </td>
            <td><span class="tag" :class="dsTypeTagClass(d.type)">{{ dsStore.DS_TYPE_LABEL[d.type] }}</span></td>
            <td class="muted">{{ d.endpoint || '—' }}</td>
            <td><CStatusPill :status="dsStatusPill(d.status)" dot>{{ dsStore.DS_STATUS_LABEL[d.status] }}</CStatusPill></td>
            <td class="muted">{{ d.owner }}</td>
            <td>{{ fmtTime(d.lastSyncAt) }}</td>
            <td>
              <template v-if="d.status !== 'DISABLED'">
                <CButton v-if="auth.can('collect:sync') && d.type === 'THIRD_PARTY'" size="sm" variant="text" :disabled="dsStore.syncingId !== null" @click="dsStore.syncSource(d.id)">
                  <CIcon name="refresh" :size="14" />{{ dsStore.syncingId === d.id ? '探测中…' : '连通探测' }}
                </CButton>
                <CButton v-if="auth.can('collect:edit')" size="sm" variant="text" @click="openDsEdit(d.id)">
                  <CIcon name="edit" :size="14" />编辑
                </CButton>
                <CButton v-if="auth.can('collect:edit')" size="sm" variant="text" @click="dsStore.disableSource(d.id)">
                  <CIcon name="close" :size="14" />停用
                </CButton>
              </template>
              <span v-else class="muted">已停用</span>
            </td>
          </tr>
          <tr v-if="dsStore.loaded && dsStore.sources.length === 0">
            <td colspan="8" class="empty-cell">暂无数据源，点击右上角「新建数据源」注册</td>
          </tr>
        </tbody>
      </table>
    </CCard>

    <!-- 数据源新建/编辑 Drawer（code/type 建后不可变） -->
    <CDrawer :show="showDsForm" :title="dsEditingId ? '编辑数据源' : '新建数据源'" size="md" @update:show="showDsForm = $event">
      <div class="form">
        <div class="form__row">
          <CInput v-model="dsForm.code" label="编码（建后不可变）" placeholder="例如：TP_DOUYIN" :disabled="dsEditingId !== null" />
          <div class="form__field">
            <label class="fld-label">类型（建后不可变）</label>
            <CSelect v-model="dsForm.type" :options="dsTypeOptions" width="100%" :disabled="dsEditingId !== null" />
          </div>
        </div>
        <CInput v-model="dsForm.name" label="名称" placeholder="例如：抖音三方回传 API" />
        <CInput v-model="dsForm.endpoint" label="接入地址" placeholder="https://… 或 host:port（三方 API 探测用）" />
        <CTextarea v-model="dsForm.description" :rows="3" label="描述" placeholder="接入内容、归属域、备注" />
        <div v-if="dsForm.type !== 'THIRD_PARTY'" class="form-hint">CDC / Kafka 接入运行时归 v2 移交（DESIGN-T2 §6），当前仅注册登记，不支持连通探测。</div>
      </div>
      <template #footer>
        <CButton variant="ghost" @click="showDsForm = false">取消</CButton>
        <CButton variant="primary" :disabled="!canDsSubmit || dsSaving" @click="submitDs">{{ dsSaving ? '提交中…' : dsEditingId ? '保存' : '注册' }}</CButton>
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
.load-err {
  display: inline-flex; align-items: center; gap: 4px;
  font-size: var(--t-xs); color: var(--c-danger-fg);
}

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
.src-card__meta { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.src-card__body { display: flex; flex-direction: column; gap: 6px; padding: var(--s-xs) 0; border-top: 1px dashed var(--c-border); }
.src-line { display: flex; justify-content: space-between; gap: var(--s-sm); font-size: var(--t-xs); }
.src-line__label { color: var(--c-text-3); flex-shrink: 0; }
.src-line__val { color: var(--c-text-2); text-align: right; word-break: break-all; }
.src-line__val.num { font-variant-numeric: tabular-nums; color: var(--c-text); font-weight: 600; }
.empty-hint { grid-column: 1 / -1; text-align: center; color: var(--c-text-3); font-size: var(--t-sm); padding: var(--s-xl) 0; }

.ctable { width: 100%; border-collapse: collapse; font-size: var(--t-sm); }
.ctable thead th { padding: 12px var(--s-lg); background: var(--c-bg-page); color: var(--c-text); font-weight: 600; font-size: var(--t-xs); text-align: left; border-bottom: 1px solid var(--c-border); white-space: nowrap; }
.ctable tbody td { padding: 14px var(--s-lg); color: var(--c-text-2); border-bottom: 1px solid var(--c-border); vertical-align: middle; }
.ctable tbody tr:last-child td { border-bottom: none; }
.ctable tbody tr:hover { background: var(--c-brand-soft); }
.ctable .muted { color: var(--c-text-3); font-size: var(--t-xs); }
.empty-cell { text-align: center; color: var(--c-text-3); padding: var(--s-xl) var(--s-lg) !important; }

.tag { display: inline-flex; align-items: center; padding: 2px 8px; border-radius: var(--r-pill); font-size: var(--t-xs); font-weight: 500; white-space: nowrap; }
.tag--primary { color: var(--c-brand); background: var(--c-brand-soft); }
.tag--info { color: var(--c-info-fg); background: var(--c-info-bg); }
.tag--success { color: var(--c-success-fg); background: var(--c-success-bg); }
.tag--warn { color: var(--c-warning-fg); background: var(--c-warning-bg); }
.tag--purple { color: #7C3AED; background: #F3E8FF; }

.tbl { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: var(--t-xs); color: var(--c-text); background: var(--c-bg-page); padding: 2px 6px; border-radius: var(--r-sm); }
.rulename { font-weight: 600; color: var(--c-text); }
.rulename__sub { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; max-width: 420px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.form__field { display: flex; flex-direction: column; gap: 6px; }
.fld-label { font-size: var(--t-xs); color: var(--c-text-2); font-weight: 600; }
.form-hint { font-size: var(--t-xs); color: var(--c-warning-fg); background: var(--c-warning-bg); border-radius: var(--r-md); padding: var(--s-sm) var(--s-md); }
</style>
