<script setup lang="ts">
/* ============================================================
 * T2-04 数据服务目录 /data/service
 * API / 数据集 服务卡片 + 权限申请审批，KPI×4
 * ============================================================ */
import { computed, onMounted, reactive, ref } from 'vue'
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
import { useT2DataServiceStore, type DataService, type ServiceType, type ServiceStatus } from '@/stores/t2DataService'
import { useAuthStore } from '@/stores/auth'

const store = useT2DataServiceStore()
const auth = useAuthStore()
onMounted(() => store.seed())

const tab = ref<'services' | 'permissions'>('services')
const tabOpts = [
  { value: 'services', label: `服务目录（${store.services.length}）` },
  { value: 'permissions', label: `权限审批（${store.pendingPerms.length}）` },
]

const kpis = computed(() => [
  { label: '服务总数', icon: 'package', value: String(store.services.length), tone: 'brand' as const },
  { label: '已发布', icon: 'check-square', value: String(store.publishedCount), tone: 'success' as const },
  { label: '今日调用', icon: 'settings', value: store.totalCalls24h.toLocaleString(), tone: 'teal' as const },
  { label: '待审批权限', icon: 'org', value: String(store.pendingPerms.length), tone: store.pendingPerms.length > 0 ? 'warning' as const : 'text' as const },
])

function statusPill(s: ServiceStatus) {
  return s === 'PUBLISHED' ? 'success' : s === 'DRAFT' ? 'disabled' : 'info'
}
function typeBadge(t: ServiceType) {
  return t === 'API' ? 'primary' : 'warning'
}
function fmtDate(iso: string) {
  const d = new Date(iso)
  return `${d.getFullYear()}/${String(d.getMonth() + 1).padStart(2, '0')}/${String(d.getDate()).padStart(2, '0')}`
}

// ---- 筛选 ----
const filterType = ref<'ALL' | ServiceType>('ALL')
const keyword = ref('')
const typeOpts = [
  { value: 'ALL', label: '全部类型' },
  { value: 'API', label: 'API 接口' },
  { value: 'DATASET', label: '数据集' },
]
const filtered = computed(() => {
  return store.services.filter((s) => {
    if (filterType.value !== 'ALL' && s.type !== filterType.value) return false
    if (keyword.value.trim()) {
      const kw = keyword.value.toLowerCase()
      return s.name.toLowerCase().includes(kw) || (s.endpoint || '').toLowerCase().includes(kw) || s.description.toLowerCase().includes(kw)
    }
    return true
  })
})

// ---- 权限申请 ----
const applyOpen = ref(false)
const applyTarget = ref<DataService | null>(null)
const applyForm = reactive({ applicant: '', reason: '' })
function openApply(s: DataService) {
  applyTarget.value = s
  applyForm.applicant = auth.user.name
  applyForm.reason = ''
  applyOpen.value = true
}
function submitApply() {
  if (!applyTarget.value || !applyForm.reason.trim()) return
  store.applyPermission(applyTarget.value.id, applyForm.reason)
  applyOpen.value = false
}

// ---- 新建服务 ----
const createOpen = ref(false)
const form = reactive({
  name: '', type: 'API' as ServiceType, endpoint: '', method: 'GET' as 'GET' | 'POST',
  description: '', fieldsText: '', tagsText: '',
})
const typeFormOpts = [
  { value: 'API', label: 'API 接口' },
  { value: 'DATASET', label: '数据集' },
]
const methodOpts = [
  { value: 'GET', label: 'GET' },
  { value: 'POST', label: 'POST' },
]
const canSubmit = computed(() => form.name.trim() && form.description.trim())
function openCreate() {
  Object.assign(form, { name: '', type: 'API', endpoint: '', method: 'GET', description: '', fieldsText: '', tagsText: '' })
  createOpen.value = true
}
function submitCreate() {
  if (!canSubmit.value) return
  store.createService({
    name: form.name,
    type: form.type,
    endpoint: form.endpoint || undefined,
    method: form.type === 'API' ? form.method : undefined,
    description: form.description,
    fields: form.fieldsText.split('\n').map((s) => s.trim()).filter(Boolean),
    tags: form.tagsText.split(',').map((s) => s.trim()).filter(Boolean),
  })
  createOpen.value = false
}

// 发布/下线
function togglePublish(s: DataService) {
  if (s.status === 'PUBLISHED') store.deprecateService(s.id)
  else store.publishService(s.id)
}
function viewDoc(s: DataService) {
  window.alert(`【${s.name}】接口文档\n\n${s.description}\n\n版本：${s.version}\n负责人：${s.owner}\nEndpoint：${s.endpoint || '（数据集，无 HTTP 端点）'}`)
}
</script>

<template>
  <div class="svc">
    <div class="svc__head">
      <div class="svc__kpis">
        <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
      </div>
      <CButton v-if="auth.can('dataService:publish')" variant="primary" size="sm" @click="openCreate">
        <CIcon name="plus" :size="16" />新建服务
      </CButton>
    </div>

    <CCard class="svc__main" padding="none">
      <div class="svc__toolbar">
        <CSegmented v-model="tab" :options="tabOpts" size="sm" />
        <div v-if="tab === 'services'" class="svc__filters">
          <CInput v-model="keyword" placeholder="搜索服务名 / Endpoint / 描述" style="width: 260px" />
          <CSelect v-model="filterType" :options="typeOpts" width="140px" />
        </div>
      </div>

      <!-- 服务卡片网格 -->
      <div v-if="tab === 'services'" class="svc-grid">
        <div v-for="s in filtered" :key="s.id" class="svc-card">
          <div class="svc-card__head">
            <div class="svc-card__title">
              <div class="svc-card__name-row">
                <span class="svc-card__name">{{ s.name }}</span>
                <CStatusPill :status="statusPill(s.status)" dot>{{ store.SERVICE_STATUS_LABEL[s.status] }}</CStatusPill>
              </div>
              <div class="svc-card__tags">
                <span class="badge" :class="`badge--${typeBadge(s.type)}`">{{ store.SERVICE_TYPE_LABEL[s.type] }}</span>
                <span v-if="s.type === 'API' && s.method" class="method" :class="`method--${s.method.toLowerCase()}`">{{ s.method }}</span>
                <span v-for="t in s.tags.slice(0, 2)" :key="t" class="tag-chip">{{ t }}</span>
              </div>
            </div>
            <span class="ver">{{ s.version }}</span>
          </div>
          <p class="svc-card__desc">{{ s.description }}</p>
          <div v-if="s.endpoint" class="svc-card__ep">
            <CIcon name="settings" :size="13" />
            <code>{{ s.endpoint }}</code>
          </div>
          <div class="svc-card__stats">
            <div class="stat">
              <div class="stat__label">今日调用</div>
              <div class="stat__val">{{ s.callCount24h.toLocaleString() }}</div>
            </div>
            <div class="stat">
              <div class="stat__label">平均延迟</div>
              <div class="stat__val">{{ s.avgLatency }}<span class="stat__unit">ms</span></div>
            </div>
            <div class="stat">
              <div class="stat__label">错误率</div>
              <div class="stat__val" :class="{ 'stat__val--bad': s.errorRate > 0.5 }">{{ s.errorRate.toFixed(2) }}<span class="stat__unit">%</span></div>
            </div>
          </div>
          <div v-if="s.fields.length" class="svc-card__fields">
            <span class="flds-label">返回字段：</span>
            <code v-for="f in s.fields.slice(0, 5)" :key="f" class="fld-chip">{{ f }}</code>
            <span v-if="s.fields.length > 5" class="flds-more">+{{ s.fields.length - 5 }}</span>
          </div>
          <div class="svc-card__foot">
            <span class="owner"><CIcon name="user" :size="13" />{{ s.owner }} · {{ fmtDate(s.createdAt) }}</span>
            <div class="svc-card__ops">
              <CButton v-if="auth.can('dataService:apply') && s.status === 'PUBLISHED'" size="sm" variant="text" @click="openApply(s)">
                <CIcon name="handover" :size="13" />申请权限
              </CButton>
              <CButton size="sm" variant="text" @click="viewDoc(s)">
                <CIcon name="order" :size="13" />文档
              </CButton>
              <CButton v-if="auth.can('dataService:publish')" size="sm" variant="text"
                :disabled="s.status === 'DRAFT' && false" @click="togglePublish(s)">
                <CIcon :name="s.status === 'PUBLISHED' ? 'export' : 'upload'" :size="13" />
                {{ s.status === 'PUBLISHED' ? '下线' : '发布' }}
              </CButton>
            </div>
          </div>
        </div>
        <div v-if="filtered.length === 0" class="empty">暂无符合条件的服务</div>
      </div>

      <!-- 权限审批 -->
      <table v-else class="ctable">
        <thead>
          <tr>
            <th>服务</th>
            <th style="width:120px">申请人</th>
            <th>申请理由</th>
            <th style="width:110px">状态</th>
            <th style="width:160px">申请时间</th>
            <th style="width:200px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="p in store.permissions" :key="p.id">
            <td class="strong">{{ p.serviceName }}</td>
            <td>{{ p.applicant }}</td>
            <td class="muted reason">{{ p.reason }}</td>
            <td>
              <CStatusPill :status="p.status === 'PENDING' ? 'warning' : p.status === 'APPROVED' ? 'success' : 'danger'">
                {{ store.PERMISSION_STATUS_LABEL[p.status] }}
              </CStatusPill>
            </td>
            <td>{{ fmtDate(p.appliedAt) }}</td>
            <td>
              <template v-if="p.status === 'PENDING' && auth.can('dataService:publish')">
                <CButton size="sm" variant="text" @click="store.approvePermission(p.id)">
                  <CIcon name="check" :size="13" />批准
                </CButton>
                <CButton size="sm" variant="text" @click="store.rejectPermission(p.id)">
                  <CIcon name="close" :size="13" />拒绝
                </CButton>
              </template>
              <span v-else class="muted">{{ p.decidedBy }} @ {{ fmtDate(p.decidedAt!) }}</span>
            </td>
          </tr>
        </tbody>
      </table>
    </CCard>

    <!-- 权限申请 Drawer -->
    <CDrawer :show="applyOpen" title="申请数据服务权限" size="md" @update:show="applyOpen = $event">
      <div v-if="applyTarget" class="form">
        <CCard padding="md" class="apply-target">
          <div class="apply-target__name">{{ applyTarget.name }}</div>
          <div class="apply-target__sub">
            <CStatusPill :status="typeBadge(applyTarget.type)">{{ store.SERVICE_TYPE_LABEL[applyTarget.type] }}</CStatusPill>
            <code v-if="applyTarget.endpoint">{{ applyTarget.endpoint }}</code>
          </div>
        </CCard>
        <CInput v-model="applyForm.applicant" label="申请人" />
        <CTextarea v-model="applyForm.reason" :rows="5" label="申请理由" placeholder="请说明使用场景、调用频率预估、数据用途等" />
      </div>
      <template #footer>
        <CButton variant="ghost" @click="applyOpen = false">取消</CButton>
        <CButton variant="primary" :disabled="!applyForm.reason.trim()" @click="submitApply">提交申请</CButton>
      </template>
    </CDrawer>

    <!-- 新建服务 Drawer -->
    <CDrawer :show="createOpen" title="新建数据服务" size="md" @update:show="createOpen = $event">
      <div class="form">
        <CInput v-model="form.name" label="服务名称" placeholder="例如：客户 360 画像" />
        <div class="form__row">
          <div class="form__field">
            <label class="fld-label">类型</label>
            <CSelect v-model="form.type" :options="typeFormOpts" width="100%" />
          </div>
          <div v-if="form.type === 'API'" class="form__field">
            <label class="fld-label">Method</label>
            <CSelect v-model="form.method" :options="methodOpts" width="100%" />
          </div>
        </div>
        <CInput v-if="form.type === 'API'" v-model="form.endpoint" label="Endpoint" placeholder="/api/v1/..." />
        <CTextarea v-model="form.description" :rows="3" label="服务描述" placeholder="说明服务用途、返回内容、使用注意事项" />
        <CTextarea v-model="form.fieldsText" :rows="4" label="返回字段（每行一个）" placeholder="customer_id&#10;name_mask&#10;phone_mask" />
        <CInput v-model="form.tagsText" label="标签（逗号分隔）" placeholder="客户, 画像, 高频" />
      </div>
      <template #footer>
        <CButton variant="ghost" @click="createOpen = false">取消</CButton>
        <CButton variant="primary" :disabled="!canSubmit" @click="submitCreate">创建（草稿）</CButton>
      </template>
    </CDrawer>
  </div>
</template>

<style scoped>
.svc { display: flex; flex-direction: column; gap: var(--s-md); }
.svc__head { display: flex; align-items: stretch; gap: var(--s-md); }
.svc__kpis { flex: 1; display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); }
.svc__main :deep(.card__body) { display: flex; flex-direction: column; gap: var(--s-md); padding: 0; }
.svc__toolbar { padding: var(--s-md) var(--s-lg) 0; display: flex; justify-content: space-between; align-items: center; gap: var(--s-md); flex-wrap: wrap; }
.svc__filters { display: flex; gap: var(--s-sm); align-items: center; }

.svc-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(380px, 1fr));
  gap: var(--s-md);
  padding: 0 var(--s-lg) var(--s-lg);
}
.svc-card {
  display: flex;
  flex-direction: column;
  gap: var(--s-sm);
  padding: var(--s-md);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--r-lg);
  transition: border-color .15s, box-shadow .15s;
}
.svc-card:hover { border-color: var(--c-brand-border); box-shadow: var(--shadow-card); }
.svc-card__head { display: flex; justify-content: space-between; align-items: flex-start; gap: var(--s-sm); }
.svc-card__title { display: flex; flex-direction: column; gap: 6px; min-width: 0; flex: 1; }
.svc-card__name-row { display: flex; align-items: center; gap: var(--s-xs); flex-wrap: wrap; }
.svc-card__name { font-size: var(--t-md); font-weight: 700; color: var(--c-text); }
.svc-card__tags { display: flex; gap: 4px; flex-wrap: wrap; align-items: center; }
.ver { font-family: ui-monospace, monospace; font-size: 11px; color: var(--c-text-3); background: var(--c-bg-page); padding: 2px 6px; border-radius: var(--r-sm); flex-shrink: 0; }
.badge { display: inline-flex; align-items: center; padding: 2px 8px; border-radius: var(--r-pill); font-size: var(--t-xs); font-weight: 500; }
.badge--primary { color: var(--c-brand); background: var(--c-brand-soft); }
.badge--warning { color: var(--c-warning-fg); background: var(--c-warning-bg); }
.method { display: inline-flex; align-items: center; padding: 2px 6px; font-family: ui-monospace, monospace; font-size: 10px; font-weight: 700; border-radius: 3px; }
.method--get { color: var(--c-success-fg); background: var(--c-success-bg); }
.method--post { color: var(--c-info-fg); background: var(--c-info-bg); }
.tag-chip { font-size: 11px; color: var(--c-text-3); background: var(--c-bg-page); padding: 1px 6px; border-radius: var(--r-pill); }

.svc-card__desc { font-size: var(--t-sm); color: var(--c-text-2); line-height: 1.6; margin: 0; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.svc-card__ep { display: flex; align-items: center; gap: 6px; color: var(--c-text-3); }
.svc-card__ep code { font-family: ui-monospace, monospace; font-size: 11px; color: var(--c-brand); background: var(--c-brand-soft); padding: 3px 8px; border-radius: var(--r-sm); }

.svc-card__stats { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--s-sm); padding: var(--s-sm) 0; border-top: 1px dashed var(--c-border); border-bottom: 1px dashed var(--c-border); }
.stat { display: flex; flex-direction: column; gap: 2px; }
.stat__label { font-size: 11px; color: var(--c-text-3); }
.stat__val { font-size: var(--t-md); font-weight: 700; color: var(--c-text); font-variant-numeric: tabular-nums; }
.stat__unit { font-size: 11px; font-weight: 400; color: var(--c-text-3); margin-left: 2px; }
.stat__val--bad { color: var(--c-danger-fg); }

.svc-card__fields { display: flex; flex-wrap: wrap; gap: 4px; align-items: center; }
.flds-label { font-size: 11px; color: var(--c-text-3); }
.fld-chip { font-family: ui-monospace, monospace; font-size: 10px; color: var(--c-text-2); background: var(--c-bg-page); padding: 1px 6px; border-radius: 3px; }
.flds-more { font-size: 11px; color: var(--c-text-3); }

.svc-card__foot { display: flex; justify-content: space-between; align-items: center; gap: var(--s-sm); padding-top: var(--s-xs); }
.owner { font-size: var(--t-xs); color: var(--c-text-3); display: inline-flex; align-items: center; gap: 4px; }
.svc-card__ops { display: flex; gap: 2px; }
.svc-card__ops :deep(.cbtn--text) { padding: 2px 6px; font-size: var(--t-xs); height: 24px; }

.ctable { width: 100%; border-collapse: collapse; font-size: var(--t-sm); }
.ctable thead th { padding: 12px var(--s-lg); background: var(--c-bg-page); color: var(--c-text); font-weight: 600; font-size: var(--t-xs); text-align: left; border-bottom: 1px solid var(--c-border); white-space: nowrap; }
.ctable tbody td { padding: 12px var(--s-lg); color: var(--c-text-2); border-bottom: 1px solid var(--c-border); vertical-align: middle; }
.ctable tbody tr:last-child td { border-bottom: none; }
.ctable tbody tr:hover { background: var(--c-brand-soft); }
.ctable .strong { color: var(--c-text); font-weight: 600; }
.ctable .muted { color: var(--c-text-3); font-size: var(--t-xs); }
.ctable .reason { max-width: 360px; }

.empty { grid-column: 1 / -1; text-align: center; color: var(--c-text-3); padding: var(--s-xl); }

.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.form__field { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.fld-label { font-size: 13px; color: var(--c-text); line-height: 18px; }

.apply-target { background: var(--c-bg-page); }
.apply-target :deep(.card__body) { padding: var(--s-md); }
.apply-target__name { font-weight: 700; color: var(--c-text); }
.apply-target__sub { display: flex; gap: var(--s-sm); align-items: center; margin-top: 6px; }
.apply-target__sub code { font-family: ui-monospace, monospace; font-size: 11px; color: var(--c-brand); }
</style>
