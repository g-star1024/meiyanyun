<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CTextarea from '@/components/CTextarea.vue'
import CIcon from '@/components/CIcon.vue'
import { useM1RegionStore, type Region, type RegionStatus } from '@/stores/m1Region'
import { useM1TenantStore } from '@/stores/m1Tenant'
import { useAuthStore } from '@/stores/auth'

const region = useM1RegionStore()
const tenant = useM1TenantStore()
const auth = useAuthStore()
onMounted(() => { tenant.seed(); region.seed() })

const canEdit = computed(() => auth.can('tenant:edit'))

const keyword = ref('')
const fStatus = ref('')

const filtered = computed(() => {
  const kw = keyword.value.trim()
  return region.withStats.filter((r) => {
    if (fStatus.value && r.status !== fStatus.value) return false
    if (kw && !`${r.code} ${r.name} ${r.managerName} ${r.cities.join(' ')}`.includes(kw)) return false
    return true
  })
})

const kpis = computed(() => {
  const list = region.withStats
  return {
    active: list.filter((r) => r.status === 'ACTIVE').length,
    total: list.length,
    cities: new Set(list.flatMap((r) => r.cities)).size,
    stores: list.reduce((s, r) => s + r.storeCount, 0),
  }
})

function statusTone(s: RegionStatus) { return s === 'ACTIVE' ? 'success' : 'disabled' }
function fmtMoney(n: number) { return '¥' + n.toLocaleString('zh-CN') }

// ---- 弹层 ----
const showModal = ref(false)
const editing = ref<Region | null>(null)
const formErr = ref('')
const form = reactive({
  code: '', name: '', managerName: '', cities: '', monthlyTarget: '',
  status: 'ACTIVE' as RegionStatus, remark: '',
})

function resetForm() {
  Object.assign(form, { code: '', name: '', managerName: '', cities: '', monthlyTarget: '', status: 'ACTIVE', remark: '' })
  formErr.value = ''
}

function openCreate() {
  editing.value = null
  resetForm()
  form.code = `R-${String(region.regions.length + 1).padStart(2, '0')}`
  showModal.value = true
}

function openEdit(r: Region) {
  editing.value = r
  Object.assign(form, {
    code: r.code, name: r.name, managerName: r.managerName,
    cities: r.cities.join('、'), monthlyTarget: String(r.monthlyTarget),
    status: r.status, remark: r.remark ?? '',
  })
  formErr.value = ''
  showModal.value = true
}

function submit() {
  if (!form.name.trim()) { formErr.value = '请填写区域名称'; return }
  if (!form.code.trim()) { formErr.value = '请填写区域编码'; return }
  if (!form.managerName.trim()) { formErr.value = '请填写区域经理'; return }
  formErr.value = ''
  const cities = form.cities.split(/[、,，\s]+/).map((s) => s.trim()).filter(Boolean)
  const payload = {
    code: form.code.trim(), name: form.name.trim(), managerName: form.managerName.trim(),
    cities, monthlyTarget: Number(form.monthlyTarget) || 0,
    status: form.status, remark: form.remark.trim() || undefined,
  }
  if (editing.value) region.update(editing.value.id, payload)
  else region.create(payload)
  showModal.value = false
}

// ---- 停用/启用 ----
const showConfirm = ref(false)
const confirmTarget = ref<Region | null>(null)
const confirmTo = ref<RegionStatus>('ACTIVE')
const confirmReason = ref('')

function openStatus(r: Region, to: RegionStatus) {
  confirmTarget.value = r; confirmTo.value = to; confirmReason.value = ''
  showConfirm.value = true
}
function confirmStatus() {
  if (!confirmTarget.value) return
  if (confirmTo.value === 'INACTIVE' && !confirmReason.value.trim()) return
  region.setStatus(confirmTarget.value.id, confirmTo.value, confirmReason.value.trim() || undefined)
  showConfirm.value = false
}
</script>

<template>
  <div class="mr-page">
    <div class="mr-kpis">
      <div class="kpi kpi--brand">
        <div class="kpi__icon"><CIcon name="org" :size="20" /></div>
        <div class="kpi__body"><div class="kpi__label">运营中区域</div><div class="kpi__value">{{ kpis.active }}</div></div>
      </div>
      <div class="kpi kpi--neutral">
        <div class="kpi__icon"><CIcon name="box" :size="20" /></div>
        <div class="kpi__body"><div class="kpi__label">区域总数</div><div class="kpi__value">{{ kpis.total }}</div></div>
      </div>
      <div class="kpi kpi--info">
        <div class="kpi__icon"><CIcon name="store" :size="20" /></div>
        <div class="kpi__body"><div class="kpi__label">覆盖城市</div><div class="kpi__value">{{ kpis.cities }}</div></div>
      </div>
      <div class="kpi kpi--success">
        <div class="kpi__icon"><CIcon name="pos" :size="20" /></div>
        <div class="kpi__body"><div class="kpi__label">区域内门店</div><div class="kpi__value">{{ kpis.stores }}</div></div>
      </div>
    </div>

    <CCard padding="md">
      <div class="mr-toolbar">
        <div class="filters">
          <select v-model="fStatus" class="sel">
            <option value="">全部状态</option>
            <option value="ACTIVE">运营中</option>
            <option value="INACTIVE">已停用</option>
          </select>
          <CInput v-model="keyword" placeholder="搜索编码/名称/经理/城市" />
        </div>
        <CButton variant="primary" :disabled="!canEdit" v-perm="'tenant:edit'" @click="openCreate">
          <CIcon name="plus" :size="16" /> 新建区域
        </CButton>
      </div>
    </CCard>

    <div class="mr-grid">
      <CCard v-for="r in filtered" :key="r.id" padding="none" class="region-card" :class="{ 'region-card--inactive': r.status === 'INACTIVE' }">
        <div class="region-card__head">
          <div class="region-card__title">
            <span class="region-card__code">{{ r.code }}</span>
            <span class="region-card__name">{{ r.name }}</span>
          </div>
          <CStatusPill :status="statusTone(r.status)" dot>{{ region.REGION_STATUS_LABEL[r.status] }}</CStatusPill>
        </div>
        <div class="region-card__body">
          <div class="region-stat">
            <div class="region-stat__label">区域经理</div>
            <div class="region-stat__value">{{ r.managerName }}</div>
          </div>
          <div class="region-stat">
            <div class="region-stat__label">门店数</div>
            <div class="region-stat__value">{{ r.storeCount }}<span class="region-stat__sub">（营业 {{ r.operatingCount }}）</span></div>
          </div>
          <div class="region-stat region-stat--full">
            <div class="region-stat__label">覆盖城市</div>
            <div class="region-cities">
              <span v-for="c in r.cities" :key="c" class="city-chip">{{ c }}</span>
            </div>
          </div>
          <div class="region-stat region-stat--full">
            <div class="region-stat__label">月营收目标</div>
            <div class="region-target">{{ fmtMoney(r.monthlyTarget) }}</div>
          </div>
          <div v-if="r.remark" class="region-remark">{{ r.remark }}</div>
        </div>
        <div class="region-card__foot">
          <CButton variant="text" size="sm" :disabled="!canEdit" v-perm="'tenant:edit'" @click="openEdit(r)">编辑</CButton>
          <CButton
            v-if="r.status === 'ACTIVE'" variant="text" size="sm" :disabled="!canEdit" v-perm="'tenant:edit'"
            @click="openStatus(r, 'INACTIVE')"
          >停用</CButton>
          <CButton
            v-else variant="text" size="sm" :disabled="!canEdit" v-perm="'tenant:edit'"
            @click="openStatus(r, 'ACTIVE')"
          >启用</CButton>
        </div>
      </CCard>
      <div v-if="filtered.length === 0" class="empty">暂无符合条件的区域</div>
    </div>

    <!-- 新建/编辑弹层 -->
    <div v-if="showModal" class="modal-mask" @click.self="showModal = false">
      <div class="modal">
        <div class="modal__head">
          <h3>{{ editing ? '编辑区域' : '新建区域' }}</h3>
          <button class="modal__close" @click="showModal = false"><CIcon name="close" :size="18" /></button>
        </div>
        <div class="modal__body">
          <div class="form-grid">
            <label class="field"><span class="field__label">区域编码 <i>*</i></span><CInput v-model="form.code" placeholder="如 R-EAST" /></label>
            <label class="field"><span class="field__label">区域名称 <i>*</i></span><CInput v-model="form.name" placeholder="如 华东大区" /></label>
            <label class="field"><span class="field__label">区域经理 <i>*</i></span><CInput v-model="form.managerName" placeholder="如 陈野" /></label>
            <label class="field">
              <span class="field__label">状态</span>
              <select v-model="form.status" class="sel sel--full">
                <option value="ACTIVE">运营中</option>
                <option value="INACTIVE">已停用</option>
              </select>
            </label>
            <label class="field field--full"><span class="field__label">覆盖城市</span><CInput v-model="form.cities" placeholder="多个城市用顿号或逗号分隔，如 上海、杭州、南京" /></label>
            <label class="field"><span class="field__label">月营收目标（元）</span><CInput v-model="form.monthlyTarget" placeholder="如 5000000" /></label>
            <label class="field field--full"><span class="field__label">备注</span><CTextarea v-model="form.remark" placeholder="区域备注（可选）" :rows="2" /></label>
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
    <div v-if="showConfirm" class="modal-mask" @click.self="showConfirm = false">
      <div class="modal modal--sm">
        <div class="modal__head">
          <h3>{{ confirmTo === 'INACTIVE' ? '停用区域' : '启用区域' }}</h3>
          <button class="modal__close" @click="showConfirm = false"><CIcon name="close" :size="18" /></button>
        </div>
        <div class="modal__body">
          <p class="confirm-txt">确认将「<b>{{ confirmTarget?.name }}</b>」{{ confirmTo === 'INACTIVE' ? '停用' : '启用' }}？</p>
          <label v-if="confirmTo === 'INACTIVE'" class="field">
            <span class="field__label">停用原因 <i>*</i></span>
            <CTextarea v-model="confirmReason" placeholder="请说明停用原因，将记入审计日志" :rows="3" />
          </label>
        </div>
        <div class="modal__foot">
          <CButton variant="secondary" @click="showConfirm = false">取消</CButton>
          <CButton variant="primary" :disabled="confirmTo === 'INACTIVE' && !confirmReason.trim()" @click="confirmStatus">确认</CButton>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.mr-page { display: flex; flex-direction: column; gap: var(--s-md); }

.mr-kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); }
.kpi { display: flex; align-items: center; gap: var(--s-md); padding: var(--s-md); border-radius: var(--r-xl); background: var(--c-surface); border: 1px solid var(--c-border-light); }
.kpi__icon { width: 44px; height: 44px; border-radius: var(--r-lg); display: flex; align-items: center; justify-content: center; flex: none; }
.kpi--brand .kpi__icon { background: var(--c-brand-soft); color: var(--c-brand); }
.kpi--neutral .kpi__icon { background: var(--c-surface, #f7f8fa); color: var(--c-text-3); }
.kpi--info .kpi__icon { background: var(--c-info-bg, #EAF2FF); color: var(--c-info-fg); }
.kpi--success .kpi__icon { background: var(--c-success-bg, #f0fbf0); color: var(--c-success-fg); }
.kpi__label { font-size: var(--t-xs); color: var(--c-text-3); }
.kpi__value { font-size: var(--t-xl); font-weight: 700; color: var(--c-text); line-height: 1.2; }

.mr-toolbar { display: flex; align-items: center; justify-content: space-between; gap: var(--s-md); flex-wrap: nowrap; }
.mr-toolbar > .cbtn { flex-shrink: 0; white-space: nowrap; }
.filters { display: flex; gap: var(--s-sm); flex: 1; min-width: 0; flex-wrap: nowrap; overflow-x: auto; align-items: center; }
.filters .sel { flex-shrink: 0; }
.filters > :deep(.cinput) { flex: 1; min-width: 200px; max-width: 280px; }
.sel { height: 36px; padding: 0 12px; border: 1px solid var(--c-border); border-radius: var(--r-md); font-size: var(--t-sm); color: var(--c-text); background: var(--c-surface); }
.sel--full { width: 100%; }

.mr-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--s-md); }
.region-card { display: flex; flex-direction: column; transition: box-shadow .15s; }
.region-card:hover { box-shadow: var(--shadow-pop, 0 8px 24px rgba(0,0,0,.08)); }
.region-card--inactive { opacity: .65; }
.region-card__head { display: flex; align-items: center; justify-content: space-between; padding: var(--s-md); border-bottom: 1px solid var(--c-border-light); }
.region-card__title { display: flex; align-items: center; gap: var(--s-sm); }
.region-card__code { font-size: var(--t-xs); color: var(--c-text-3); font-family: var(--t-number, monospace); background: var(--c-surface, #f7f8fa); padding: 2px 8px; border-radius: var(--r-sm); }
.region-card__name { font-size: var(--t-md); font-weight: 700; color: var(--c-text); }
.region-card__body { padding: var(--s-md); display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); flex: 1; }
.region-stat { display: flex; flex-direction: column; gap: 4px; }
.region-stat--full { grid-column: 1 / -1; }
.region-stat__label { font-size: var(--t-xs); color: var(--c-text-3); }
.region-stat__value { font-size: var(--t-lg); font-weight: 700; color: var(--c-text); }
.region-stat__sub { font-size: var(--t-xs); font-weight: 400; color: var(--c-text-3); }
.region-cities { display: flex; flex-wrap: wrap; gap: 4px; }
.city-chip { font-size: var(--t-xs); padding: 2px 8px; background: var(--c-brand-soft); color: var(--c-brand); border-radius: var(--r-capsule); }
.region-target { font-size: var(--t-lg); font-weight: 700; color: var(--c-brand); }
.region-remark { grid-column: 1 / -1; font-size: var(--t-xs); color: var(--c-text-3); padding: var(--s-sm); background: var(--c-surface, #f7f8fa); border-radius: var(--r-sm); border-left: 3px solid var(--c-warning-fg); }
.region-card__foot { display: flex; gap: var(--s-xs); padding: var(--s-sm) var(--s-md); border-top: 1px solid var(--c-border-light); }
.empty { grid-column: 1 / -1; text-align: center; padding: var(--s-xl); color: var(--c-text-3); font-size: var(--t-sm); }

.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,.45); display: flex; align-items: center; justify-content: center; z-index: 100; }
.modal { background: var(--c-surface); border-radius: var(--r-xl); width: 560px; max-width: calc(100vw - 48px); max-height: 86vh; display: flex; flex-direction: column; box-shadow: var(--shadow-pop, 0 12px 40px rgba(0,0,0,.18)); }
.modal--sm { width: 420px; }
.modal__head { display: flex; align-items: center; justify-content: space-between; padding: var(--s-md) var(--s-lg); border-bottom: 1px solid var(--c-border-light); }
.modal__head h3 { margin: 0; font-size: var(--t-lg); font-weight: 700; }
.modal__close { border: none; background: none; cursor: pointer; color: var(--c-text-3); padding: 4px; display: flex; border-radius: var(--r-sm); }
.modal__close:hover { background: var(--c-surface, #f7f8fa); color: var(--c-text); }
.modal__body { padding: var(--s-lg); overflow-y: auto; }
.modal__foot { display: flex; justify-content: flex-end; gap: var(--s-sm); padding: var(--s-md) var(--s-lg); border-top: 1px solid var(--c-border-light); }

.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.field { display: flex; flex-direction: column; gap: 6px; }
.field--full { grid-column: 1 / -1; }
.field__label { font-size: var(--t-xs); color: var(--c-text-2); font-weight: 500; }
.field__label i { color: var(--c-danger-fg); font-style: normal; }
.form-err { margin-top: var(--s-sm); color: var(--c-danger-fg); font-size: var(--t-xs); }
.confirm-txt { margin: 0 0 var(--s-md); font-size: var(--t-sm); color: var(--c-text-2); }
</style>
