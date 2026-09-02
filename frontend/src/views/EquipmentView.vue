<script setup lang="ts">
/* ============================================================
 * 设备资产管理 /m2-equipment（M2-05）
 * 双栏范式：左设备台账列表，右设备详情+校准/维保记录。
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CTextarea from '@/components/CTextarea.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CDrawer from '@/components/CDrawer.vue'
import {
  useEquipmentStore,
  type Equipment,
  type EquipmentStatus,
  type MaintenanceRecord,
  type MaintenanceType,
  type EquipmentCategory,
} from '@/stores/equipment'
import { useToast } from '@/composables/useToast'

const store = useEquipmentStore()
const toast = useToast()
onMounted(() => store.seed())

const selectedId = ref<string | null>(null)
const selected = computed<Equipment | null>(() => {
  if (selectedId.value) return store.get(selectedId.value) ?? null
  return store.filtered[0] ?? null
})

const kpis = computed(() => [
  { label: '设备总数', icon: 'settings', value: String(store.list.length), tone: 'text' as const },
  { label: '正常', icon: 'settings', value: String(store.normal.length), tone: 'success' as const },
  { label: '待校准', icon: 'alert', value: String(store.dueCalibration.length), tone: 'warning' as const },
  { label: '维修中', icon: 'tool', value: String(store.repairing.length), tone: 'danger' as const },
])

const statusOptions = [
  { value: 'ALL', label: '全部状态' },
  { value: 'NORMAL', label: '正常' },
  { value: 'CALIBRATING', label: '校准中' },
  { value: 'REPAIRING', label: '维修中' },
  { value: 'DISABLED', label: '停用' },
]

function fmtDate(iso?: string) {
  if (!iso) return '—'
  const d = new Date(iso)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
function fmtDateTime(iso?: string) {
  if (!iso) return '—'
  const d = new Date(iso)
  return `${fmtDate(iso)} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

function dueText(days: number | null) {
  if (days === null) return '未设置'
  if (days < 0) return `已过期 ${-days} 天`
  if (days === 0) return '今日到期'
  return `还剩 ${days} 天`
}
function dueTone(days: number | null): 'default' | 'warning' | 'danger' | 'success' {
  if (days === null) return 'default'
  if (days < 0) return 'danger'
  if (days <= store.DUE_SOON_DAYS) return 'warning'
  return 'success'
}

function money(n: number) {
  return `¥${n.toLocaleString()}`
}

// 新建设备
const showCreateEq = ref(false)
const eqForm = ref({
  assetNo: '', name: '', category: 'LASER' as EquipmentCategory,
  brand: '', model: '', location: '',
  purchaseAmount: '', lifespanYears: '',
  nextCalibrationAt: '', nextMaintenanceAt: '',
})
const eqCatOptions = [
  { value: 'LASER', label: '激光仪器' },
  { value: 'RF', label: '射频仪器' },
  { value: 'ULTRASOUND', label: '超声仪器' },
  { value: 'INJECTION', label: '注射设备' },
  { value: 'MONITOR', label: '监护设备' },
  { value: 'OTHER', label: '其他' },
]
function openCreateEq() {
  eqForm.value = {
    assetNo: '', name: '', category: 'LASER',
    brand: '', model: '', location: '',
    purchaseAmount: '', lifespanYears: '',
    nextCalibrationAt: '', nextMaintenanceAt: '',
  }
  showCreateEq.value = true
}
function doCreateEq() {
  const f = eqForm.value
  if (!f.assetNo.trim() || !f.name.trim()) return
  const ok = store.addEquipment({
    assetNo: f.assetNo.trim(), name: f.name.trim(), category: f.category,
    brand: f.brand.trim() || undefined, model: f.model.trim() || undefined,
    location: f.location.trim() || '未设置',
    status: 'NORMAL',
    purchasedAt: new Date().toISOString(),
    purchaseAmount: Number(f.purchaseAmount) || 0,
    lifespanYears: Number(f.lifespanYears) || 8,
    depreciated: 0,
    nextCalibrationAt: f.nextCalibrationAt ? new Date(f.nextCalibrationAt).toISOString() : undefined,
    nextMaintenanceAt: f.nextMaintenanceAt ? new Date(f.nextMaintenanceAt).toISOString() : undefined,
  })
  if (ok) {
    showCreateEq.value = false
    toast.success(`已创建设备「${f.name}」`)
  }
}

// 状态变更
function askStatus(status: EquipmentStatus) {
  if (!selected.value) return
  const map: Record<EquipmentStatus, string> = {
    NORMAL: '确认将设备状态改为「正常」？',
    CALIBRATING: '确认将设备设为「校准中」？',
    REPAIRING: '确认将设备设为「维修中」？',
    DISABLED: '确认停用该设备？停用后将不再参与排班。',
  }
  if (window.confirm(map[status])) store.setStatus(selected.value.id, status)
}

// 新增校准/维保记录弹层
const recForm = ref({
  show: false,
  type: 'CALIBRATION' as MaintenanceType,
  summary: '',
  vendor: '',
  cost: 0,
  nextAt: '',
})
const recTypeOptions = [
  { value: 'CALIBRATION', label: '校准' },
  { value: 'MAINTENANCE', label: '维保' },
  { value: 'REPAIR', label: '维修' },
]
function openRec(type?: MaintenanceType) {
  recForm.value = { show: true, type: type || 'CALIBRATION', summary: '', vendor: '', cost: 0, nextAt: '' }
}
function submitRec() {
  if (!selected.value || !recForm.value.summary.trim()) return
  const r: Omit<MaintenanceRecord, 'id' | 'at' | 'by'> = {
    type: recForm.value.type,
    summary: recForm.value.summary.trim(),
    vendor: recForm.value.vendor.trim() || undefined,
    cost: recForm.value.cost || undefined,
    nextAt: recForm.value.nextAt ? new Date(recForm.value.nextAt).toISOString() : undefined,
  }
  store.addRecord(selected.value.id, r)
  recForm.value.show = false
}
</script>

<template>
  <div class="eq">
    <div class="eq__head">
      <div class="eq__kpis">
        <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
      </div>
    </div>

    <div class="eq__body">
      <CCard class="eq__list eq__list--fab" padding="none">
        <div class="filters">
          <CSelect v-model="store.filterStatus" :options="statusOptions" width="110px" />
          <CInput v-model="store.keyword" placeholder="搜索编号/名称/位置" />
        </div>
        <div class="list">
          <div v-if="store.filtered.length === 0" class="empty">
            <CIcon name="settings" :size="28" class="empty__icon" />
            <div>暂无设备</div>
          </div>
          <button
            v-for="e in store.filtered" :key="e.id"
            class="row" :class="{ 'row--active': selected?.id === e.id, 'row--due': store.dueStatus(e).calibration !== null && (store.dueStatus(e).calibration as number) <= store.DUE_SOON_DAYS && e.status !== 'DISABLED' }"
            @click="selectedId = e.id"
          >
            <div class="row__top">
              <span class="row__no">{{ e.assetNo }}</span>
              <CStatusPill :status="store.STATUS_PILL[e.status]">{{ store.STATUS_LABEL[e.status] }}</CStatusPill>
            </div>
            <div class="row__title">{{ e.name }}</div>
            <div class="row__meta">
              <span><CIcon name="home" :size="12" /> {{ e.location }}</span>
            </div>
            <div v-if="e.nextCalibrationAt" class="row__due">
              <CStatusPill :status="dueTone(store.dueStatus(e).calibration)" dot>
                校准 {{ dueText(store.dueStatus(e).calibration) }}
              </CStatusPill>
            </div>
          </button>
        </div>
        <button class="eq__fab" v-perm.disable="'equipment:edit'" @click="openCreateEq" title="新建设备">
          <CIcon name="plus" :size="20" />
        </button>
      </CCard>

      <CCard v-if="selected" class="eq__detail" :title="selected.assetNo">
        <template #header>
          <h3 class="eq__detail-title">{{ selected.assetNo }}</h3>
          <CStatusPill :status="store.STATUS_PILL[selected.status]">{{ store.STATUS_LABEL[selected.status] }}</CStatusPill>
        </template>

        <div class="detail__head">
          <div>
            <div class="detail__title">{{ selected.name }}</div>
            <div class="detail__sub">
              <span class="tag tag--brand">{{ store.CATEGORY_LABEL[selected.category] }}</span>
              <span v-if="selected.brand" class="tag">{{ selected.brand }} {{ selected.model }}</span>
            </div>
          </div>
          <div class="detail__value">
            <div class="detail__value-label">账面净值</div>
            <div class="detail__value-val">{{ money(store.netValue(selected)) }}</div>
          </div>
        </div>

        <div class="detail__grid">
          <div class="field"><span class="field__label">所在位置</span><span class="field__val">{{ selected.location }}</span></div>
          <div class="field"><span class="field__label">购置日期</span><span class="field__val">{{ fmtDate(selected.purchasedAt) }}</span></div>
          <div class="field"><span class="field__label">购置金额</span><span class="field__val">{{ money(selected.purchaseAmount) }}</span></div>
          <div class="field"><span class="field__label">预计使用年限</span><span class="field__val">{{ selected.lifespanYears }} 年</span></div>
          <div class="field"><span class="field__label">累计折旧</span><span class="field__val">{{ money(selected.depreciated) }}</span></div>
          <div class="field">
            <span class="field__label">下次校准</span>
            <span class="field__val">
              {{ fmtDate(selected.nextCalibrationAt) }}
              <CStatusPill v-if="selected.status !== 'DISABLED'" :status="dueTone(store.dueStatus(selected).calibration)" class="due-pill">
                {{ dueText(store.dueStatus(selected).calibration) }}
              </CStatusPill>
            </span>
          </div>
          <div class="field">
            <span class="field__label">下次维保</span>
            <span class="field__val">
              {{ fmtDate(selected.nextMaintenanceAt) }}
              <CStatusPill v-if="selected.status !== 'DISABLED' && store.dueStatus(selected).maintenance !== null && (store.dueStatus(selected).maintenance as number) <= store.DUE_SOON_DAYS" status="warning" class="due-pill">
                {{ dueText(store.dueStatus(selected).maintenance) }}
              </CStatusPill>
            </span>
          </div>
        </div>

        <div v-if="selected.note" class="detail__note">
          <CIcon name="alert" :size="14" /> {{ selected.note }}
        </div>

        <div class="detail__sec">
          <div class="detail__sec-title">
            校准 / 维保记录
            <CButton variant="text" size="sm" v-perm.disable="'equipment:edit'" @click="openRec()">
              <CIcon name="plus" :size="14" />新增记录
            </CButton>
          </div>
          <div v-if="selected.records.length === 0" class="records-empty">暂无记录</div>
          <div v-else class="records">
            <div v-for="r in selected.records" :key="r.id" class="rec">
              <div class="rec__head">
                <CStatusPill :status="r.type === 'CALIBRATION' ? 'info' : r.type === 'REPAIR' ? 'danger' : 'primary'">
                  {{ store.MAINT_TYPE_LABEL[r.type] }}
                </CStatusPill>
                <span class="rec__date">{{ fmtDateTime(r.at) }}</span>
              </div>
              <div class="rec__sum">{{ r.summary }}</div>
              <div class="rec__meta">
                <span v-if="r.by">{{ r.by }}</span>
                <span v-if="r.vendor">· {{ r.vendor }}</span>
                <span v-if="r.cost">· 费用 {{ money(r.cost) }}</span>
                <span v-if="r.nextAt">· 下次 {{ fmtDate(r.nextAt) }}</span>
              </div>
            </div>
          </div>
        </div>

        <div class="detail__ops">
          <template v-if="selected.status === 'NORMAL'">
            <CButton variant="ghost" v-perm.disable="'equipment:edit'" @click="openRec('MAINTENANCE')">
              <CIcon name="check-square" :size="16" />维保
            </CButton>
            <CButton variant="ghost" v-perm.disable="'equipment:edit'" @click="askStatus('CALIBRATING')">
              <CIcon name="scan" :size="16" />送校准
            </CButton>
            <CButton variant="danger" v-perm.disable="'equipment:edit'" @click="askStatus('REPAIRING')">
              <CIcon name="alert" :size="16" />报修
            </CButton>
          </template>
          <template v-else-if="selected.status === 'CALIBRATING'">
            <CButton variant="primary" v-perm.disable="'equipment:edit'" @click="openRec('CALIBRATION')">
              <CIcon name="check" :size="16" />登记校准结果
            </CButton>
          </template>
          <template v-else-if="selected.status === 'REPAIRING'">
            <CButton variant="primary" v-perm.disable="'equipment:edit'" @click="openRec('REPAIR')">
              <CIcon name="check" :size="16" />维修完成
            </CButton>
          </template>
          <template v-if="selected.status !== 'DISABLED' && selected.status !== 'REPAIRING'">
            <CButton variant="ghost" v-perm.disable="'equipment:edit'" @click="askStatus('DISABLED')">停用</CButton>
          </template>
          <template v-if="selected.status === 'DISABLED'">
            <CButton variant="primary" v-perm.disable="'equipment:edit'" @click="askStatus('NORMAL')">
              <CIcon name="check" :size="16" />恢复启用
            </CButton>
          </template>
        </div>
      </CCard>

      <CCard v-else class="eq__detail eq__detail--empty" title="设备详情">
        <div class="detail-empty">
          <CIcon name="settings" :size="40" class="detail-empty__icon" />
          <p>请选择一台设备</p>
        </div>
      </CCard>
    </div>

    <!-- 新建设备抽屉 -->
    <CDrawer v-model:show="showCreateEq" title="新建设备" size="sm">
      <div class="opform">
        <CInput v-model="eqForm.assetNo" label="资产编号 *" placeholder="如：EQ-L009" />
        <CInput v-model="eqForm.name" label="设备名称 *" placeholder="如：超皮秒治疗仪" />
        <div class="opform__field">
          <label class="opform__label">设备分类 *</label>
          <CSelect v-model="eqForm.category" :options="eqCatOptions" width="100%" />
        </div>
        <CInput v-model="eqForm.brand" label="品牌" placeholder="如：赛诺秀" />
        <CInput v-model="eqForm.model" label="型号" placeholder="如：PicoSure" />
        <CInput v-model="eqForm.location" label="位置" placeholder="如：A01 激光治疗室" />
        <CInput v-model="eqForm.purchaseAmount" label="购置金额（元）" placeholder="0" />
        <CInput v-model="eqForm.lifespanYears" label="预计使用年限" placeholder="8" />
        <CInput v-model="eqForm.nextCalibrationAt" label="下次校准日期" placeholder="如：2026-12-31" />
        <CInput v-model="eqForm.nextMaintenanceAt" label="下次维保日期" placeholder="如：2026-10-15" />
      </div>
      <div class="drawer__ops">
        <CButton variant="ghost" size="sm" @click="showCreateEq = false">取消</CButton>
        <CButton variant="primary" size="sm" :disabled="!eqForm.assetNo.trim() || !eqForm.name.trim()" @click="doCreateEq">
          创建
        </CButton>
      </div>
    </CDrawer>

    <!-- 新增记录弹层 -->
    <div v-if="recForm.show" class="modal-mask" @click.self="recForm.show = false">
      <CCard class="modal" title="新增校准 / 维保记录" padding="lg">
        <div class="form">
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">类型</label>
              <CSelect v-model="recForm.type" :options="recTypeOptions" width="100%" />
            </div>
            <div>
              <label class="form__label">服务商 / 工程师</label>
              <CInput v-model="recForm.vendor" placeholder="如：赛诺秀原厂" />
            </div>
          </div>
          <div class="form__row">
            <label class="form__label">内容 / 结果 *</label>
            <CTextarea v-model="recForm.summary" placeholder="如：年度能量校准，输出稳定" />
          </div>
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">费用（元，可选）</label>
              <input v-model.number="recForm.cost" type="number" min="0" class="num" />
            </div>
            <div>
              <label class="form__label">下次日期</label>
              <input v-model="recForm.nextAt" type="date" class="num" />
            </div>
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="recForm.show = false">取消</CButton>
          <CButton variant="primary" :disabled="!recForm.summary.trim()" @click="submitRec">保存</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.eq { display: flex; flex-direction: column; gap: var(--s-lg); }
.eq__head { display: flex; justify-content: space-between; align-items: center; gap: var(--s-md); flex-wrap: wrap; }
.eq__kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); flex: 1; min-width: 480px; }

.eq__body { display: grid; grid-template-columns: 380px 1fr; gap: var(--s-lg); align-items: start; }
.eq__list { min-width: 0; }
.eq__list--fab { position: relative; }
.eq__fab {
  position: absolute; right: 12px; bottom: 12px; z-index: 10;
  width: 42px; height: 42px; border-radius: 50%;
  background: var(--c-brand); color: #fff; border: none;
  display: flex; align-items: center; justify-content: center;
  box-shadow: 0 4px 14px rgba(0,0,0,.2); cursor: pointer;
  transition: transform .15s, box-shadow .15s;
}
.eq__fab:hover { transform: scale(1.08); box-shadow: 0 6px 18px rgba(0,0,0,.28); }
.filters { display: flex; gap: var(--s-sm); padding: var(--s-md); border-bottom: 1px solid var(--c-border-light); align-items: center; }
.filters :deep(.cinput) { flex: 1; }
.list { max-height: 620px; overflow-y: auto; }
.empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); font-size: var(--t-sm); }
.empty__icon { color: var(--c-text-4); }

.row {
  display: block; width: 100%; text-align: left; padding: var(--s-md) var(--s-lg);
  background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer;
}
.row:hover { background: var(--c-brand-soft); }
.row--active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.row--due:not(.row--active) { background: var(--c-warning-bg); }
.row__top { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--s-xs); }
.row__no { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.row__title { font-size: var(--t-sm); color: var(--c-text); margin-bottom: var(--s-xs); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.row__meta { display: flex; flex-wrap: wrap; gap: var(--s-xs); font-size: var(--t-xs); color: var(--c-text-3); }
.row__meta span { display: inline-flex; align-items: center; gap: 3px; }
.row__due { margin-top: var(--s-xs); display: flex; }

.eq__detail-title { font-size: var(--t-md); font-weight: 700; margin: 0; }
.detail__head { display: flex; justify-content: space-between; gap: var(--s-md); padding-bottom: var(--s-md); border-bottom: 1px solid var(--c-border-light); }
.detail__title { font-size: var(--t-lg); font-weight: 700; color: var(--c-text); }
.detail__sub { display: flex; flex-wrap: wrap; gap: var(--s-xs); margin-top: var(--s-xs); align-items: center; }
.tag { font-size: var(--t-xs); padding: 2px 8px; border-radius: var(--r-sm); background: var(--c-disabled-bg); color: var(--c-text-2); }
.tag--brand { background: var(--c-brand-soft); color: var(--c-brand); }
.detail__value { text-align: right; }
.detail__value-label { font-size: var(--t-xs); color: var(--c-text-3); }
.detail__value-val { font-size: var(--t-lg); font-weight: 700; color: var(--c-brand); font-variant-numeric: tabular-nums; }

.detail__grid { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: var(--s-md) var(--s-lg); margin: var(--s-lg) 0; }
.field { display: flex; flex-direction: column; gap: 2px; }
.field__label { font-size: var(--t-xs); color: var(--c-text-3); }
.field__val { font-size: var(--t-sm); color: var(--c-text); display: inline-flex; align-items: center; gap: var(--s-xs); flex-wrap: wrap; }
.due-pill { margin-left: var(--s-xxs); }

.detail__note { display: inline-flex; align-items: center; gap: var(--s-xs); padding: var(--s-xs) var(--s-sm); background: var(--c-warning-bg); color: var(--c-warning-fg); border-radius: var(--r-sm); font-size: var(--t-xs); margin-bottom: var(--s-lg); }

.detail__sec { margin-bottom: var(--s-lg); }
.detail__sec-title {
  font-size: var(--t-sm); font-weight: 600; color: var(--c-text);
  margin-bottom: var(--s-sm); display: flex; justify-content: space-between; align-items: center;
}
.records-empty { padding: var(--s-lg); text-align: center; color: var(--c-text-3); font-size: var(--t-sm); border: 1px dashed var(--c-border); border-radius: var(--r-md); }
.records { display: flex; flex-direction: column; gap: var(--s-sm); }
.rec { padding: var(--s-sm) var(--s-md); border: 1px solid var(--c-border-light); border-radius: var(--r-md); background: var(--c-surface); }
.rec__head { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--s-xs); }
.rec__date { font-size: var(--t-xs); color: var(--c-text-3); }
.rec__sum { font-size: var(--t-sm); color: var(--c-text); margin-bottom: 2px; }
.rec__meta { font-size: var(--t-xs); color: var(--c-text-3); display: flex; gap: var(--s-xxs); flex-wrap: wrap; }

.detail__ops { display: flex; justify-content: flex-end; gap: var(--s-sm); flex-wrap: wrap; margin-top: var(--s-lg); padding-top: var(--s-lg); border-top: 1px solid var(--c-border-light); }

.detail-empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-md); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); }
.detail-empty__icon { color: var(--c-text-4); }

.opform { display: flex; flex-direction: column; gap: var(--s-md); }
.opform__field { display: flex; flex-direction: column; gap: var(--s-xs); }
.opform__label { font-size: var(--t-xs); color: var(--c-text-3); }
.drawer__ops { display: flex; justify-content: flex-end; gap: var(--s-xs); margin-top: var(--s-lg); }

.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 560px; max-width: 100%; max-height: 90vh; overflow-y: auto; box-shadow: var(--shadow-pop); }
.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.form__row--2 { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.form__label { font-size: var(--t-xs); color: var(--c-text-3); }
.num { width: 100%; height: 36px; padding: 0 var(--s-sm); border: 1px solid var(--c-border); border-radius: var(--r-sm); background: var(--c-surface); font-size: var(--t-sm); color: var(--c-text); }
.num:focus { outline: none; border-color: var(--c-brand); }

@media (max-width: 1024px) {
  .eq__body { grid-template-columns: 1fr; }
  .eq__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .detail__head { flex-direction: column; gap: var(--s-sm); }
  .detail__value { text-align: left; }
  .detail__grid { grid-template-columns: 1fr 1fr; }
  .list { max-height: 320px; }
}
</style>
