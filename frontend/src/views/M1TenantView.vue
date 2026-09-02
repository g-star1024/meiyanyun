<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CTable from '@/components/CTable.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CTextarea from '@/components/CTextarea.vue'
import CIcon from '@/components/CIcon.vue'
import { useM1TenantStore, type Tenant, type TenantStatus, type TenantType } from '@/stores/m1Tenant'
import { useAuthStore } from '@/stores/auth'

const tenant = useM1TenantStore()
const auth = useAuthStore()
onMounted(() => tenant.seed())

const canEdit = computed(() => auth.can('tenant:edit'))

// ---- 筛选 ----
const keyword = ref('')
const fRegion = ref('')
const fStatus = ref('')
const fType = ref('')

const regionOptions = computed(() => [
  { value: '', label: '全部区域' },
  ...Object.keys(tenant.byRegion).map((r) => ({ value: r, label: r })),
])
const statusOptions = [
  { value: '', label: '全部状态' },
  { value: 'OPERATING', label: '营业中' },
  { value: 'SETTING_UP', label: '筹建中' },
  { value: 'SUSPENDED', label: '已停用' },
]
const typeOptions = [
  { value: '', label: '全部店型' },
  { value: 'FLAGSHIP', label: '旗舰店' },
  { value: 'STANDARD', label: '标准店' },
  { value: 'COMMUNITY', label: '社区店' },
]

const filtered = computed(() => {
  const kw = keyword.value.trim()
  return tenant.tenants.filter((t) => {
    if (fRegion.value && t.region !== fRegion.value) return false
    if (fStatus.value && t.status !== fStatus.value) return false
    if (fType.value && t.type !== fType.value) return false
    if (kw && !`${t.code} ${t.name} ${t.city} ${t.managerName}`.includes(kw)) return false
    return true
  })
})

const kpis = computed(() => ({
  operating: tenant.tenants.filter((t) => t.status === 'OPERATING').length,
  settingUp: tenant.tenants.filter((t) => t.status === 'SETTING_UP').length,
  suspended: tenant.tenants.filter((t) => t.status === 'SUSPENDED').length,
  total: tenant.tenants.length,
}))

// ---- 表格 ----
const columns = [
  { key: 'code', label: '门店编码', width: 100 },
  { key: 'name', label: '门店名称' },
  { key: 'region', label: '大区', width: 110 },
  { key: 'city', label: '城市', width: 80 },
  { key: 'type', label: '店型', width: 100 },
  { key: 'status', label: '状态', width: 100 },
  { key: 'managerName', label: '店长', width: 100 },
  { key: 'rooms', label: '治疗室', width: 80, align: 'center' as const },
  { key: 'openDate', label: '开业日期', width: 120 },
  { key: 'ops', label: '操作', width: 180, align: 'center' as const },
]

function statusTone(s: TenantStatus) {
  return s === 'OPERATING' ? 'success' : s === 'SETTING_UP' ? 'warning' : 'disabled'
}
function typeTone(t: TenantType) {
  return t === 'FLAGSHIP' ? 'primary' : t === 'STANDARD' ? 'info' : 'default'
}

// ---- 新建/编辑弹层 ----
const showModal = ref(false)
const editing = ref<Tenant | null>(null)
const formErr = ref('')
const form = reactive({
  code: '', name: '', region: '', city: '', status: 'OPERATING' as TenantStatus,
  type: 'STANDARD' as TenantType, area: '', rooms: '', seats: '', openDate: '',
  managerName: '', phone: '', address: '', businessHours: '10:00-21:00',
  licenseNo: '', currency: 'CNY', timezone: 'Asia/Shanghai', remark: '',
})

function resetForm() {
  Object.assign(form, {
    code: '', name: '', region: '', city: '', status: 'OPERATING', type: 'STANDARD',
    area: '', rooms: '', seats: '', openDate: '', managerName: '', phone: '',
    address: '', businessHours: '10:00-21:00', licenseNo: '', currency: 'CNY',
    timezone: 'Asia/Shanghai', remark: '',
  })
  formErr.value = ''
}

function openCreate() {
  editing.value = null
  resetForm()
  // 自动建议下一个编码
  const used = tenant.tenants.map((t) => Number(t.code.replace(/\D/g, ''))).filter(Boolean)
  const next = used.length ? Math.max(...used) + 1 : 1
  form.code = `M${String(next).padStart(3, '0')}`
  showModal.value = true
}

function openEdit(t: Tenant) {
  editing.value = t
  Object.assign(form, {
    code: t.code, name: t.name, region: t.region, city: t.city, status: t.status,
    type: t.type, area: String(t.area), rooms: String(t.rooms), seats: String(t.seats),
    openDate: t.openDate, managerName: t.managerName, phone: t.phone, address: t.address,
    businessHours: t.businessHours, licenseNo: t.licenseNo, currency: t.currency,
    timezone: t.timezone, remark: t.remark ?? '',
  })
  formErr.value = ''
  showModal.value = true
}

function submit() {
  if (!form.name.trim()) { formErr.value = '请填写门店名称'; return }
  if (!form.code.trim()) { formErr.value = '请填写门店编码'; return }
  if (!form.region.trim()) { formErr.value = '请填写所属大区'; return }
  if (!form.city.trim()) { formErr.value = '请填写城市'; return }
  formErr.value = ''
  const payload = {
    code: form.code.trim(), name: form.name.trim(), region: form.region.trim(),
    city: form.city.trim(), status: form.status, type: form.type,
    area: Number(form.area) || 0, rooms: Number(form.rooms) || 0, seats: Number(form.seats) || 0,
    openDate: form.openDate, managerName: form.managerName.trim() || '待任命',
    phone: form.phone.trim(), address: form.address.trim(),
    businessHours: form.businessHours.trim(), licenseNo: form.licenseNo.trim(),
    currency: form.currency, timezone: form.timezone, remark: form.remark.trim() || undefined,
  }
  if (editing.value) {
    tenant.update(editing.value.id, payload)
  } else {
    tenant.create(payload)
  }
  showModal.value = false
}

// ---- 停用/启用 ----
const showSuspend = ref(false)
const suspendTarget = ref<Tenant | null>(null)
const suspendTo = ref<TenantStatus>('SUSPENDED')
const suspendReason = ref('')

function openStatus(t: Tenant, to: TenantStatus) {
  suspendTarget.value = t
  suspendTo.value = to
  suspendReason.value = ''
  showSuspend.value = true
}
function confirmStatus() {
  if (!suspendTarget.value) return
  if (suspendTo.value === 'SUSPENDED' && !suspendReason.value.trim()) return
  tenant.setStatus(suspendTarget.value.id, suspendTo.value, suspendReason.value.trim() || undefined)
  showSuspend.value = false
}
</script>

<template>
  <div class="mt-page">
    <!-- KPI -->
    <div class="mt-kpis">
      <div class="kpi kpi--brand">
        <div class="kpi__icon"><CIcon name="store" :size="20" /></div>
        <div class="kpi__body">
          <div class="kpi__label">营业中门店</div>
          <div class="kpi__value">{{ kpis.operating }}</div>
        </div>
      </div>
      <div class="kpi kpi--warning">
        <div class="kpi__icon"><CIcon name="settings" :size="20" /></div>
        <div class="kpi__body">
          <div class="kpi__label">筹建中</div>
          <div class="kpi__value">{{ kpis.settingUp }}</div>
        </div>
      </div>
      <div class="kpi kpi--muted">
        <div class="kpi__icon"><CIcon name="shield" :size="20" /></div>
        <div class="kpi__body">
          <div class="kpi__label">已停用</div>
          <div class="kpi__value">{{ kpis.suspended }}</div>
        </div>
      </div>
      <div class="kpi kpi--neutral">
        <div class="kpi__icon"><CIcon name="org" :size="20" /></div>
        <div class="kpi__body">
          <div class="kpi__label">门店总数</div>
          <div class="kpi__value">{{ kpis.total }}</div>
        </div>
      </div>
    </div>

    <!-- 筛选 + 操作 -->
    <CCard padding="md">
      <div class="mt-toolbar">
        <div class="filters">
          <CSelect v-model="fRegion" :options="regionOptions" />
          <CSelect v-model="fStatus" :options="statusOptions" />
          <CSelect v-model="fType" :options="typeOptions" />
          <CInput v-model="keyword" placeholder="搜索编码/名称/城市/店长" />
        </div>
        <CButton variant="primary" :disabled="!canEdit" v-perm="'tenant:edit'" @click="openCreate">
          <CIcon name="plus" :size="16" /> 新建门店
        </CButton>
      </div>
    </CCard>

    <!-- 表格 -->
    <CCard padding="none" class="mt-table-card">
      <CTable :columns="columns" :rows="filtered" row-key="id" stripe>
        <template #col-name="{ row }">
          <div class="cell-name">
            <span class="cell-name__txt">{{ row.name }}</span>
            <span class="cell-name__addr">{{ row.address }}</span>
          </div>
        </template>
        <template #col-type="{ row }">
          <CStatusPill :status="typeTone(row.type)" dot>{{ tenant.TENANT_TYPE_LABEL[row.type as TenantType] }}</CStatusPill>
        </template>
        <template #col-status="{ row }">
          <CStatusPill :status="statusTone(row.status as TenantStatus)" dot>
            {{ tenant.TENANT_STATUS_LABEL[row.status as TenantStatus] }}
          </CStatusPill>
        </template>
        <template #col-ops="{ row }">
          <div class="ops">
            <CButton variant="text" size="sm" :disabled="!canEdit" v-perm="'tenant:edit'" @click="openEdit(row as Tenant)">编辑</CButton>
            <CButton
              v-if="row.status === 'OPERATING'"
              variant="text" size="sm" :disabled="!canEdit" v-perm="'tenant:edit'"
              @click="openStatus(row as Tenant, 'SUSPENDED')"
            >停用</CButton>
            <CButton
              v-else-if="row.status === 'SUSPENDED'"
              variant="text" size="sm" :disabled="!canEdit" v-perm="'tenant:edit'"
              @click="openStatus(row as Tenant, 'OPERATING')"
            >启用</CButton>
            <CButton
              v-else
              variant="text" size="sm" :disabled="!canEdit" v-perm="'tenant:edit'"
              @click="openStatus(row as Tenant, 'OPERATING')"
            >开业</CButton>
          </div>
        </template>
      </CTable>
    </CCard>

    <!-- 新建/编辑弹层 -->
    <div v-if="showModal" class="modal-mask" @click.self="showModal = false">
      <div class="modal modal--lg">
        <div class="modal__head">
          <h3>{{ editing ? '编辑门店' : '新建门店' }}</h3>
          <button class="modal__close" @click="showModal = false"><CIcon name="close" :size="18" /></button>
        </div>
        <div class="modal__body">
          <div class="form-grid">
            <label class="field">
              <span class="field__label">门店编码 <i>*</i></span>
              <CInput v-model="form.code" placeholder="如 M007" />
            </label>
            <label class="field">
              <span class="field__label">门店名称 <i>*</i></span>
              <CInput v-model="form.name" placeholder="如 浦东旗舰店" />
            </label>
            <label class="field">
              <span class="field__label">所属大区 <i>*</i></span>
              <CInput v-model="form.region" placeholder="如 华东大区" />
            </label>
            <label class="field">
              <span class="field__label">城市 <i>*</i></span>
              <CInput v-model="form.city" placeholder="如 上海" />
            </label>
            <label class="field">
              <span class="field__label">店型</span>
              <CSelect v-model="form.type" :options="typeOptions.filter(o => o.value)" />
            </label>
            <label class="field">
              <span class="field__label">状态</span>
              <CSelect v-model="form.status" :options="statusOptions.filter(o => o.value)" />
            </label>
            <label class="field">
              <span class="field__label">面积（㎡）</span>
              <CInput v-model="form.area" placeholder="如 320" />
            </label>
            <label class="field">
              <span class="field__label">治疗室数</span>
              <CInput v-model="form.rooms" placeholder="如 5" />
            </label>
            <label class="field">
              <span class="field__label">工位/咨询室</span>
              <CInput v-model="form.seats" placeholder="如 4" />
            </label>
            <label class="field">
              <span class="field__label">开业日期</span>
              <input v-model="form.openDate" type="date" class="date-input" />
            </label>
            <label class="field">
              <span class="field__label">店长</span>
              <CInput v-model="form.managerName" placeholder="如 苏晴" />
            </label>
            <label class="field">
              <span class="field__label">联系电话</span>
              <CInput v-model="form.phone" placeholder="如 021-5288-1001" />
            </label>
            <label class="field field--full">
              <span class="field__label">门店地址</span>
              <CInput v-model="form.address" placeholder="详细地址" />
            </label>
            <label class="field">
              <span class="field__label">营业时间</span>
              <CInput v-model="form.businessHours" placeholder="如 10:00-21:00" />
            </label>
            <label class="field">
              <span class="field__label">营业执照号</span>
              <CInput v-model="form.licenseNo" placeholder="统一社会信用代码" />
            </label>
            <label class="field field--full">
              <span class="field__label">备注</span>
              <CTextarea v-model="form.remark" placeholder="门店备注（可选）" :rows="2" />
            </label>
          </div>
          <div v-if="formErr" class="form-err">{{ formErr }}</div>
        </div>
        <div class="modal__foot">
          <CButton variant="secondary" @click="showModal = false">取消</CButton>
          <CButton variant="primary" @click="submit">{{ editing ? '保存' : '创建' }}</CButton>
        </div>
      </div>
    </div>

    <!-- 停用/启用确认 -->
    <div v-if="showSuspend" class="modal-mask" @click.self="showSuspend = false">
      <div class="modal modal--sm">
        <div class="modal__head">
          <h3>{{ suspendTo === 'SUSPENDED' ? '停用门店' : suspendTarget?.status === 'SETTING_UP' ? '确认开业' : '启用门店' }}</h3>
          <button class="modal__close" @click="showSuspend = false"><CIcon name="close" :size="18" /></button>
        </div>
        <div class="modal__body">
          <p class="confirm-txt">
            确认将「<b>{{ suspendTarget?.name }}</b>」状态变更为
            <CStatusPill :status="statusTone(suspendTo)" dot>{{ tenant.TENANT_STATUS_LABEL[suspendTo] }}</CStatusPill>？
          </p>
          <label v-if="suspendTo === 'SUSPENDED'" class="field">
            <span class="field__label">停用原因 <i>*</i></span>
            <CTextarea v-model="suspendReason" placeholder="请说明停用原因，将记入审计日志" :rows="3" />
          </label>
        </div>
        <div class="modal__foot">
          <CButton variant="secondary" @click="showSuspend = false">取消</CButton>
          <CButton variant="primary" :disabled="suspendTo === 'SUSPENDED' && !suspendReason.trim()" @click="confirmStatus">确认</CButton>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.mt-page { display: flex; flex-direction: column; gap: var(--s-md); }

/* KPI */
.mt-kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); }
.kpi {
  display: flex; align-items: center; gap: var(--s-md);
  padding: var(--s-md); border-radius: var(--r-xl);
  background: var(--c-surface); border: 1px solid var(--c-border-light);
}
.kpi__icon {
  width: 44px; height: 44px; border-radius: var(--r-lg);
  display: flex; align-items: center; justify-content: center; flex: none;
}
.kpi--brand .kpi__icon { background: var(--c-brand-soft); color: var(--c-brand); }
.kpi--warning .kpi__icon { background: var(--c-warning-bg, #FFF7E6); color: var(--c-warning-fg); }
.kpi--muted .kpi__icon { background: var(--c-surface, #f7f8fa); color: var(--c-text-3); }
.kpi--neutral .kpi__icon { background: var(--c-info-bg, #EAF2FF); color: var(--c-info-fg); }
.kpi__label { font-size: var(--t-xs); color: var(--c-text-3); }
.kpi__value { font-size: var(--t-xl); font-weight: 700; color: var(--c-text); line-height: 1.2; }

/* toolbar */
.mt-toolbar { display: flex; align-items: center; justify-content: space-between; gap: var(--s-md); flex-wrap: nowrap; }
.mt-toolbar > .cbtn { flex-shrink: 0; white-space: nowrap; }
.filters { display: flex; gap: var(--s-sm); flex: 1; min-width: 0; flex-wrap: nowrap; overflow-x: auto; align-items: center; }
.filters > :deep(.csel) { flex-shrink: 0; }
.filters > :deep(.cinput) { flex: 1; min-width: 180px; max-width: 260px; }

/* table */
.mt-table-card :deep(.ctable-wrap) { border-radius: var(--r-lg); overflow: hidden; }
.cell-name { display: flex; flex-direction: column; gap: 2px; }
.cell-name__txt { font-weight: 600; color: var(--c-text); }
.cell-name__addr { font-size: var(--t-xs); color: var(--c-text-3); }
.ops { display: flex; gap: var(--s-xs); justify-content: center; }

/* modal */
.modal-mask {
  position: fixed; inset: 0; background: rgba(0, 0, 0, 0.45);
  display: flex; align-items: center; justify-content: center; z-index: 100;
}
.modal {
  background: var(--c-surface); border-radius: var(--r-xl);
  width: 640px; max-width: calc(100vw - 48px); max-height: 86vh;
  display: flex; flex-direction: column; box-shadow: var(--shadow-pop, 0 12px 40px rgba(0,0,0,.18));
}
.modal--lg { width: 720px; }
.modal--sm { width: 440px; }
.modal__head {
  display: flex; align-items: center; justify-content: space-between;
  padding: var(--s-md) var(--s-lg); border-bottom: 1px solid var(--c-border-light);
}
.modal__head h3 { margin: 0; font-size: var(--t-lg); font-weight: 700; }
.modal__close {
  border: none; background: none; cursor: pointer; color: var(--c-text-3);
  padding: 4px; display: flex; border-radius: var(--r-sm);
}
.modal__close:hover { background: var(--c-surface, #f7f8fa); color: var(--c-text); }
.modal__body { padding: var(--s-lg); overflow-y: auto; }
.modal__foot {
  display: flex; justify-content: flex-end; gap: var(--s-sm);
  padding: var(--s-md) var(--s-lg); border-top: 1px solid var(--c-border-light);
}

/* form */
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md) var(--s-md); }
.field { display: flex; flex-direction: column; gap: 6px; }
.field--full { grid-column: 1 / -1; }
.field__label { font-size: var(--t-xs); color: var(--c-text-2); font-weight: 500; }
.field__label i { color: var(--c-danger-fg); font-style: normal; }
.date-input {
  height: 36px; padding: 0 12px; border: 1px solid var(--c-border);
  border-radius: var(--r-md); font-size: var(--t-sm); color: var(--c-text);
  background: var(--c-surface); width: 100%; box-sizing: border-box;
}
.date-input:focus { outline: none; border-color: var(--c-brand); }
.form-err { margin-top: var(--s-sm); color: var(--c-danger-fg); font-size: var(--t-xs); }
.confirm-txt { margin: 0 0 var(--s-md); font-size: var(--t-sm); color: var(--c-text-2); display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
</style>
