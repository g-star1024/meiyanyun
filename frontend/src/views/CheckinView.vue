<script setup lang="ts">
/* ============================================================
 * 会员到店核销 /m2-checkin（M2-09）
 * 扫码核销 / 预约到店 / 直接到店，异常标记。
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import CCard from '@/components/CCard.vue'
import CWorkbenchShell from '@/components/CWorkbenchShell.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CTextarea from '@/components/CTextarea.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import { useCheckinStore, type CheckinRecord, type CheckinMethod, type CheckinExceptionReason } from '@/stores/checkin'
import { CHECKIN_STATUS, dictPill } from '@/config/dictionary'
import { useToast } from '@/composables/useToast'

const store = useCheckinStore()
const router = useRouter()
const toast = useToast()
onMounted(() => store.seed())

const selectedId = ref<string | null>(null)
const selected = computed<CheckinRecord | null>(() => {
  if (selectedId.value) return store.get(selectedId.value) ?? null
  return store.filtered[0] ?? null
})

const kpis = computed(() => [
  { label: '今日到店', icon: 'user-check', value: String(store.today.length), tone: 'brand' as const },
  { label: '已核销', icon: 'user-check', value: String(store.done.length), tone: 'success' as const },
  { label: '待确认', icon: 'check-square', value: String(store.pending.length), tone: 'warning' as const },
  { label: '异常', icon: 'alert', value: String(store.exception.length), tone: 'danger' as const },
])

const methodOptions = [
  { value: 'ALL', label: '全部方式' },
  { value: 'SCAN', label: '扫码核销' },
  { value: 'APPOINTMENT', label: '预约到店' },
  { value: 'WALKIN', label: '直接到店' },
]
const statusOptions = [
  { value: 'ALL', label: '全部状态' },
  { value: 'DONE', label: '已核销' },
  { value: 'PENDING', label: '待确认' },
  { value: 'EXCEPTION', label: '异常' },
]
const methodIcon: Record<CheckinMethod, string> = {
  SCAN: 'scan',
  APPOINTMENT: 'calendar',
  WALKIN: 'store',
}
const exOptions = [
  { value: 'NOT_SELF', label: '非本人' },
  { value: 'ALREADY_DONE', label: '已核销' },
  { value: 'NO_APPOINTMENT', label: '无预约' },
  { value: 'INFO_MISMATCH', label: '信息不符' },
]

function fmtTime(iso?: string) {
  if (!iso) return '—'
  const d = new Date(iso)
  return `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

// 登记到店弹层
const showForm = ref(false)
const form = ref({ customerName: '', phone: '', project: '', method: 'SCAN' as CheckinMethod })
const canSubmit = computed(() => form.value.customerName.trim() && form.value.phone.trim() && form.value.project.trim())
function submitForm() {
  if (!canSubmit.value) return
  const r = store.register({ ...form.value })
  if (r) {
    showForm.value = false
    form.value = { customerName: '', phone: '', project: '', method: 'SCAN' }
    selectedId.value = r.id
    toast.success('已登记到店')
  }
}

function doConfirm() {
  if (!selected.value) return
  store.confirm(selected.value.id)
  toast.success('核销完成，客户可进入咨询/诊疗')
}

// 异常标记弹层
const showEx = ref(false)
const exForm = ref<{ reason: CheckinExceptionReason; note: string }>({ reason: 'NOT_SELF', note: '' })
function openEx() {
  if (!selected.value || selected.value.status === 'DONE') return
  exForm.value = { reason: 'NOT_SELF', note: '' }
  showEx.value = true
}
function submitEx() {
  if (!selected.value) return
  store.markException(selected.value.id, exForm.value.reason, exForm.value.note || undefined)
  showEx.value = false
  toast.error('已标记异常，待核实处理')
}
function doReset() {
  if (!selected.value) return
  store.resetToPending(selected.value.id)
  toast.info('已解除异常，恢复待确认')
}
</script>

<template>
  <div class="ci">
    <CWorkbenchShell
      :has-selection="!!selected"
      empty-icon="user-check"
      empty-title="请选择一条到店记录"
      empty-desc="待确认记录可扫码/预约核销或标记异常；核销后客户进入咨询诊疗动线"
      list-width="380px"
    >
      <template #kpis>
        <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
      </template>

      <!-- 左：列表 -->
      <template #list>
        <div class="filters">
          <CSelect v-model="store.filterMethod" :options="methodOptions" width="130px" />
          <CSelect v-model="store.filterStatus" :options="statusOptions" width="120px" />
          <CButton class="filters__add" variant="primary" size="sm" v-perm.disable="'checkin:create'" @click="showForm = true">
            <CIcon name="scan" :size="15" />登记到店
          </CButton>
        </div>
        <div class="list">
          <div v-if="store.filtered.length === 0" class="empty">
            <CIcon name="user-check" :size="28" class="empty__icon" />
            <div>暂无到店记录</div>
          </div>
          <button
            v-for="r in store.filtered" :key="r.id"
            class="row" :class="{ 'row--active': selected?.id === r.id }"
            @click="selectedId = r.id"
          >
            <div class="row__top">
              <span class="row__no">{{ r.no }}</span>
              <CStatusPill :status="dictPill(CHECKIN_STATUS[r.status]).status">{{ dictPill(CHECKIN_STATUS[r.status]).text }}</CStatusPill>
            </div>
            <div class="row__title">{{ r.customerName }}</div>
            <div class="row__meta">
              <span><CIcon :name="methodIcon[r.method] as any" :size="12" /> {{ store.METHOD_LABEL[r.method] }}</span>
              <span><CIcon name="clock" :size="12" /> {{ fmtTime(r.arrivedAt) }}</span>
            </div>
            <div class="row__proj">{{ r.project }}</div>
          </button>
        </div>
      </template>

      <!-- 右：详情 -->
      <template #head>
        <div v-if="selected" class="wb-head">
          <h3 class="ci__detail-title">{{ selected.no }}</h3>
          <CStatusPill :status="dictPill(CHECKIN_STATUS[selected.status]).status">{{ dictPill(CHECKIN_STATUS[selected.status]).text }}</CStatusPill>
        </div>
      </template>

      <template v-if="selected">
        <div class="detail__head">
          <div>
            <div class="detail__title">{{ selected.customerName }}</div>
            <div class="detail__sub">
              <span class="tag tag--method"><CIcon :name="methodIcon[selected.method] as any" :size="12" /> {{ store.METHOD_LABEL[selected.method] }}</span>
              <span class="tag"><CIcon name="phone" :size="12" /> {{ selected.phone }}</span>
            </div>
          </div>
          <div class="detail__assign">
            <div class="detail__assign-label">接待人</div>
            <div class="detail__assign-name">{{ selected.operator }}</div>
          </div>
        </div>

        <div class="detail__grid">
          <div class="field"><span class="field__label">预约/核销项目</span><span class="field__val">{{ selected.project }}</span></div>
          <div class="field"><span class="field__label">到店时间</span><span class="field__val">{{ fmtTime(selected.arrivedAt) }}</span></div>
          <div class="field"><span class="field__label">核销时间</span><span class="field__val">{{ fmtTime(selected.checkedAt) }}</span></div>
          <div v-if="selected.status === 'EXCEPTION'" class="field">
            <span class="field__label">异常原因</span>
            <span class="field__val is-danger">{{ store.EXCEPTION_LABEL[selected.exceptionReason] }}</span>
          </div>
        </div>

        <div v-if="selected.note" class="detail__note">
          <div class="detail__sec-title">备注</div>
          <p>{{ selected.note }}</p>
        </div>

        <div class="detail__notes">
          <div class="detail__sec-title">操作记录</div>
          <div v-for="(t, i) in selected.timeline" :key="i" class="note">
            <span class="note__who">{{ t.by }}</span>
            <span class="note__text">{{ t.text }}</span>
            <span class="note__time">{{ fmtTime(t.at) }}</span>
          </div>
        </div>
      </template>

      <template #foot>
        <template v-if="selected">
          <template v-if="selected.status === 'PENDING'">
            <CButton variant="ghost" v-perm.disable="'checkin:create'" @click="openEx">
              <CIcon name="alert" :size="16" />标记异常
            </CButton>
            <CButton variant="primary" v-perm.disable="'checkin:create'" @click="doConfirm">
              <CIcon name="check" :size="16" />确认核销
            </CButton>
          </template>
          <template v-else-if="selected.status === 'EXCEPTION'">
            <CButton variant="ghost" v-perm.disable="'checkin:create'" @click="doReset">解除异常</CButton>
            <span class="ops__hint">异常记录待核实处理</span>
          </template>
          <template v-else>
            <span class="wbs-foot-done">
              <CIcon name="check" :size="15" />已于 {{ fmtTime(selected.checkedAt) }} 核销
            </span>
            <CButton variant="primary" @click="router.push('/consultation')">
              <CIcon name="chat" :size="14" />进入咨询工作台
            </CButton>
          </template>
        </template>
      </template>
    </CWorkbenchShell>

    <!-- 登记到店弹层 -->
    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <CCard class="modal" title="登记到店" padding="lg">
        <div class="form">
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">客户姓名 <span class="req">*</span></label>
              <CInput v-model="form.customerName" placeholder="如：陈美玲" />
            </div>
            <div>
              <label class="form__label">手机号 <span class="req">*</span></label>
              <CInput v-model="form.phone" placeholder="如：138****2046" />
            </div>
          </div>
          <div class="form__row">
            <label class="form__label">到店项目 <span class="req">*</span></label>
            <CInput v-model="form.project" placeholder="如：超声炮全脸提拉" />
          </div>
          <div class="form__row">
            <label class="form__label">到店方式</label>
            <CSelect v-model="form.method" :options="[
              {value:'SCAN',label:'扫码核销'},{value:'APPOINTMENT',label:'预约到店'},{value:'WALKIN',label:'直接到店'}
            ]" width="100%" />
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showForm = false">取消</CButton>
          <CButton variant="primary" :disabled="!canSubmit" @click="submitForm">登记</CButton>
        </template>
      </CCard>
    </div>

    <!-- 异常标记弹层 -->
    <div v-if="showEx" class="modal-mask" @click.self="showEx = false">
      <CCard class="modal modal--sm" title="标记异常" padding="lg">
        <div class="form">
          <div class="form__row">
            <label class="form__label">异常原因</label>
            <CSelect v-model="exForm.reason" :options="exOptions" width="100%" />
          </div>
          <div class="form__row">
            <label class="form__label">补充说明（可选）</label>
            <CTextarea v-model="exForm.note" placeholder="描述具体情况" />
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showEx = false">取消</CButton>
          <CButton variant="danger" @click="submitEx">确认标记</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.ci { display: flex; flex-direction: column; gap: var(--s-lg); }

.filters { display: flex; gap: var(--s-sm); align-items: center; padding: var(--s-md); border-bottom: 1px solid var(--c-border-light); flex-shrink: 0; }
.filters__add { margin-left: auto; flex-shrink: 0; }
.list { flex: 1; min-height: 0; overflow-y: auto; }
.empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); font-size: var(--t-sm); }
.empty__icon { color: var(--c-text-4); }

.row {
  display: block; width: 100%; text-align: left; padding: var(--s-md) var(--s-lg);
  background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer;
}
.row:hover { background: var(--c-brand-soft); }
.row--active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.row__top { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--s-xs); }
.row__no { font-size: var(--t-xs); color: var(--c-text-3); font-weight: 600; }
.row__title { font-size: var(--t-sm); color: var(--c-text); margin-bottom: var(--s-xs); font-weight: 600; }
.row__meta { display: flex; flex-wrap: wrap; gap: var(--s-sm); font-size: var(--t-xs); color: var(--c-text-3); align-items: center; }
.row__meta span { display: inline-flex; align-items: center; gap: 3px; }
.row__proj { margin-top: var(--s-xs); font-size: var(--t-xs); color: var(--c-text-2); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

.ci__detail-title { font-size: var(--t-md); font-weight: 700; margin: 0; }
.wb-head { display: flex; justify-content: space-between; align-items: center; gap: var(--s-sm); }
.detail__head { display: flex; justify-content: space-between; gap: var(--s-md); padding-bottom: var(--s-md); border-bottom: 1px solid var(--c-border-light); }
.detail__title { font-size: var(--t-lg); font-weight: 700; color: var(--c-text); }
.detail__sub { display: flex; flex-wrap: wrap; gap: var(--s-xs); margin-top: var(--s-xs); }
.tag { font-size: var(--t-xs); padding: 2px 8px; border-radius: var(--r-sm); background: var(--c-disabled-bg); color: var(--c-text-2); display: inline-flex; align-items: center; gap: 3px; }
.tag--method { background: var(--c-brand-soft); color: var(--c-brand); }
.detail__assign { text-align: right; }
.detail__assign-label { font-size: var(--t-xs); color: var(--c-text-3); }
.detail__assign-name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }

.detail__grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md) var(--s-lg); margin: var(--s-lg) 0; }
.field { display: flex; flex-direction: column; gap: 2px; }
.field__label { font-size: var(--t-xs); color: var(--c-text-3); }
.field__val { font-size: var(--t-sm); color: var(--c-text); }
.is-danger { color: var(--c-danger-fg); font-weight: 600; }

.detail__note { margin-bottom: var(--s-lg); }
.detail__note p { font-size: var(--t-sm); color: var(--c-text-2); line-height: var(--lh-md); margin: 0; background: var(--c-disabled-bg); padding: var(--s-sm) var(--s-md); border-radius: var(--r-sm); }
.detail__notes { margin-bottom: 0; }
.detail__sec-title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); margin-bottom: var(--s-sm); }
.note { display: flex; gap: var(--s-sm); align-items: baseline; padding: var(--s-xs) 0; border-bottom: 1px solid var(--c-border-light); font-size: var(--t-sm); }
.note:last-child { border-bottom: none; }
.note__who { font-weight: 600; color: var(--c-text); flex-shrink: 0; }
.note__text { color: var(--c-text-2); flex: 1; }
.note__time { font-size: var(--t-xs); color: var(--c-text-3); flex-shrink: 0; }

.ops__hint { font-size: var(--t-sm); color: var(--c-text-3); margin-right: auto; }
.wbs-foot-done { display: inline-flex; align-items: center; gap: var(--s-xs); color: var(--c-success-fg); font-size: var(--t-sm); font-weight: 600; margin-right: auto; }

.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 520px; max-width: 100%; max-height: 90vh; overflow-y: auto; box-shadow: var(--shadow-pop); }
.modal--sm { width: 400px; }
.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.form__row--2 { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.form__label { font-size: var(--t-xs); color: var(--c-text-3); }
.req { color: var(--c-danger-fg); }

@media (max-width: 1024px) {
  .detail__head { flex-direction: column; gap: var(--s-sm); }
  .detail__assign { text-align: left; }
}
</style>
