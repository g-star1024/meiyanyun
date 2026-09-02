<script setup lang="ts">
/* ============================================================
 * 损耗报损 /m2-wastage（M2-12）
 * 双栏范式：左报损单列表，右详情；4 KPI + 新建弹层。
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
import CFab from '@/components/CFab.vue'
import { useAuthStore } from '@/stores/auth'
import {
  useWastageStore,
  type Wastage,
  type WastageReason,
} from '@/stores/wastage'

const auth = useAuthStore()
const store = useWastageStore()
onMounted(() => store.seed())

const selectedId = ref<string | null>(null)
const selected = computed<Wastage | null>(() => {
  if (selectedId.value) return store.get(selectedId.value) ?? null
  return store.filtered[0] ?? null
})

const kpis = computed(() => [
  { label: '本月报损笔数', icon: 'alert', value: String(store.monthCount), tone: 'text' as const },
  { label: '本月损失金额', icon: 'alert', value: `¥${store.monthAmount.toLocaleString()}`, tone: 'danger' as const },
  { label: '待审批', icon: 'check-square', value: String(store.submitting.length), tone: 'warning' as const },
  { label: '高值报损', icon: 'alert', value: String(store.highValue.length), tone: 'orange' as const },
])

const statusOptions = [
  { value: 'ALL', label: '全部状态' },
  { value: 'DRAFT', label: '草稿' },
  { value: 'SUBMITTING', label: '待审批' },
  { value: 'APPROVED', label: '已通过' },
  { value: 'REJECTED', label: '已驳回' },
]
const reasonOptions = [
  { value: 'ALL', label: '全部原因' },
  { value: 'BROKEN', label: '破损' },
  { value: 'EXPIRED', label: '过期' },
  { value: 'INVENTORY_LOSS', label: '盘亏' },
  { value: 'OTHER', label: '其他' },
]

const reasonPill: Record<WastageReason, 'danger' | 'warning' | 'info' | 'default'> = {
  BROKEN: 'danger',
  EXPIRED: 'warning',
  INVENTORY_LOSS: 'info',
  OTHER: 'default',
}

function fmtTime(iso?: string) {
  if (!iso) return '—'
  const d = new Date(iso)
  return `${d.getMonth() + 1}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

function fmtDate(iso?: string) {
  if (!iso) return '—'
  const d = new Date(iso)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

// 新建弹层
const showForm = ref(false)
const form = ref({
  itemName: '',
  spec: '',
  qty: 1,
  unit: '件',
  amount: 0,
  reason: 'BROKEN' as WastageReason,
  description: '',
  location: '',
})
const canSubmit = computed(() => form.value.itemName.trim() && form.value.qty > 0 && form.value.amount >= 0)
function openForm() {
  form.value = { itemName: '', spec: '', qty: 1, unit: '件', amount: 0, reason: 'BROKEN', description: '', location: '' }
  showForm.value = true
}
function submitForm() {
  if (!canSubmit.value) return
  const o = store.create({ ...form.value, itemName: form.value.itemName.trim() })
  if (o) {
    showForm.value = false
    selectedId.value = o.id
  }
}

function doSubmit() {
  if (selected.value) store.submit(selected.value.id)
}
function doApprove() {
  if (selected.value) store.approve(selected.value.id)
}
function doReject() {
  if (!selected.value) return
  const reason = window.prompt('请填写驳回原因', '说明不充分，请补充凭证')
  if (reason) store.reject(selected.value.id, reason)
}

const confirm = ref<{ show: boolean; title: string; action: () => void } | null>(null)
function ask(title: string, action: () => void) {
  confirm.value = { show: true, title, action }
}
function runConfirm() {
  confirm.value?.action()
  confirm.value = null
}
</script>

<template>
  <div class="ws">
    <div class="ws__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <div class="ws__body">
      <CCard class="ws__list" padding="none">
        <div class="filters">
          <CSelect v-model="store.filterStatus" :options="statusOptions" width="120px" />
          <CSelect v-model="store.filterReason" :options="reasonOptions" width="120px" />
        </div>
        <div class="list">
          <div v-if="store.filtered.length === 0" class="empty">
            <CIcon name="alert" :size="28" class="empty__icon" />
            <div>暂无报损单</div>
          </div>
          <button
            v-for="w in store.filtered" :key="w.id"
            class="row" :class="{ 'row--active': selected?.id === w.id, 'row--high': w.amount >= store.HIGH_VALUE_THRESHOLD }"
            @click="selectedId = w.id"
          >
            <div class="row__top">
              <span class="row__no">{{ w.wsNo }}</span>
              <CStatusPill :status="store.STATUS_PILL[w.status]">{{ store.STATUS_LABEL[w.status] }}</CStatusPill>
            </div>
            <div class="row__title">{{ w.itemName }}<span v-if="w.spec" class="row__spec"> · {{ w.spec }}</span></div>
            <div class="row__meta">
              <span><CStatusPill :status="reasonPill[w.reason]">{{ store.REASON_LABEL[w.reason] }}</CStatusPill></span>
              <span class="row__amount">¥{{ w.amount.toLocaleString() }}</span>
            </div>
          </button>
          <CFab
            :actions="[{ icon: 'plus', label: '新建报损', disabled: !auth.can('wastage:create'), onClick: openForm }]"
          />
        </div>
      </CCard>

      <CCard v-if="selected" class="ws__detail" :title="selected.wsNo">
        <template #header>
          <h3 class="ws__detail-title">{{ selected.wsNo }}</h3>
          <CStatusPill :status="store.STATUS_PILL[selected.status]">{{ store.STATUS_LABEL[selected.status] }}</CStatusPill>
        </template>

        <div class="detail__head">
          <div>
            <div class="detail__title">
              {{ selected.itemName }}
              <span v-if="selected.spec" class="detail__spec">/ {{ selected.spec }}</span>
            </div>
            <div class="detail__sub">
              <CStatusPill :status="reasonPill[selected.reason]">{{ store.REASON_LABEL[selected.reason] }}</CStatusPill>
              <span v-if="selected.amount >= store.HIGH_VALUE_THRESHOLD" class="tag tag--high">
                <CIcon name="alert" :size="12" />高值
              </span>
            </div>
          </div>
          <div class="detail__amount">
            <div class="detail__amount-label">损失金额</div>
            <div class="detail__amount-val">¥{{ selected.amount.toLocaleString() }}</div>
          </div>
        </div>

        <div class="detail__grid">
          <div class="field"><span class="field__label">报损数量</span><span class="field__val">{{ selected.qty }} {{ selected.unit }}</span></div>
          <div class="field"><span class="field__label">发生位置</span><span class="field__val">{{ selected.location || '—' }}</span></div>
          <div class="field"><span class="field__label">报损人</span><span class="field__val">{{ selected.reporter }}</span></div>
          <div class="field"><span class="field__label">发生日期</span><span class="field__val">{{ fmtDate(selected.occurredAt) }}</span></div>
          <div v-if="selected.approver" class="field"><span class="field__label">审批人</span><span class="field__val">{{ selected.approver }}</span></div>
          <div v-if="selected.approvedAt" class="field"><span class="field__label">审批时间</span><span class="field__val">{{ fmtTime(selected.approvedAt) }}</span></div>
        </div>

        <div v-if="selected.description" class="detail__desc">
          <div class="detail__sec-title">情况说明</div>
          <p>{{ selected.description }}</p>
        </div>
        <div v-if="selected.rejectReason" class="detail__reject">
          <CIcon name="alert" :size="14" /> 驳回原因：{{ selected.rejectReason }}
        </div>

        <div class="detail__notes">
          <div class="detail__sec-title">处理记录</div>
          <div v-for="(n, i) in selected.notes" :key="i" class="note">
            <span class="note__who">{{ n.by }}</span>
            <span class="note__text">{{ n.text }}</span>
            <span class="note__time">{{ fmtTime(n.at) }}</span>
          </div>
        </div>

        <div class="detail__ops">
          <template v-if="selected.status === 'DRAFT'">
            <CButton variant="primary" v-perm.disable="'wastage:edit'" @click="ask('确认提交审批？', doSubmit)">
              <CIcon name="check" :size="16" />提交审批
            </CButton>
          </template>
          <template v-else-if="selected.status === 'SUBMITTING'">
            <CButton variant="ghost" v-perm.disable="'wastage:sign'" @click="doReject">驳回</CButton>
            <CButton variant="primary" v-perm.disable="'wastage:sign'" @click="ask('确认审批通过？', doApprove)">
              <CIcon name="check" :size="16" />审批通过
            </CButton>
          </template>
          <div v-else class="ops__done">
            <CIcon name="check" :size="16" />流程已结束
          </div>
        </div>
      </CCard>

      <CCard v-else class="ws__detail ws__detail--empty" title="报损详情">
        <div class="detail-empty">
          <CIcon name="alert" :size="40" class="detail-empty__icon" />
          <p>请选择一条报损单</p>
        </div>
      </CCard>
    </div>

    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <CCard class="modal" title="新建损耗报损" padding="lg">
        <div class="form">
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">物料名称 *</label>
              <CInput v-model="form.itemName" placeholder="如：医用修复面膜" />
            </div>
            <div>
              <label class="form__label">规格</label>
              <CInput v-model="form.spec" placeholder="如：6 片/盒" />
            </div>
          </div>
          <div class="form__row form__row--3">
            <div>
              <label class="form__label">数量 *</label>
              <input v-model.number="form.qty" type="number" min="1" class="num" />
            </div>
            <div>
              <label class="form__label">单位</label>
              <CInput v-model="form.unit" placeholder="盒 / 支 / 瓶" />
            </div>
            <div>
              <label class="form__label">损失金额（元）*</label>
              <input v-model.number="form.amount" type="number" min="0" step="0.01" class="num" />
            </div>
          </div>
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">原因标签 *</label>
              <CSelect v-model="form.reason" :options="reasonOptions.filter((o) => o.value !== 'ALL')" width="100%" />
            </div>
            <div>
              <label class="form__label">发生位置</label>
              <CInput v-model="form.location" placeholder="如：耗材仓 / B02 治疗室" />
            </div>
          </div>
          <div class="form__row">
            <label class="form__label">情况说明</label>
            <CTextarea v-model="form.description" placeholder="请描述报损原因、经过及涉及范围" />
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showForm = false">取消</CButton>
          <CButton variant="primary" :disabled="!canSubmit" @click="submitForm">保存草稿</CButton>
        </template>
      </CCard>
    </div>

    <div v-if="confirm?.show" class="modal-mask" @click.self="confirm = null">
      <CCard class="modal modal--sm" title="确认操作" padding="lg">
        <p class="confirm__text">{{ confirm.title }}</p>
        <template #footer>
          <CButton variant="ghost" @click="confirm = null">取消</CButton>
          <CButton variant="primary" @click="runConfirm">确认</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.ws { display: flex; flex-direction: column; gap: var(--s-lg); }
.ws__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .ws__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }

.ws__body { display: grid; grid-template-columns: 380px 1fr; gap: var(--s-lg); align-items: start; }
.ws__list { min-width: 0; display: flex; flex-direction: column; }
.filters { display: flex; gap: var(--s-sm); padding: var(--s-md); border-bottom: 1px solid var(--c-border-light); align-items: center; flex-wrap: nowrap; overflow-x: auto; }
.filters > * { flex-shrink: 0; }
.list { max-height: 560px; overflow-y: auto; display: flex; flex-direction: column; }
.empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); font-size: var(--t-sm); }
.empty__icon { color: var(--c-text-4); }

.row {
  display: block; width: 100%; text-align: left; padding: var(--s-md) var(--s-lg);
  background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer; position: relative;
}
.row:hover { background: var(--c-brand-soft); }
.row--active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.row--high::before { content: ''; position: absolute; left: 0; top: 0; bottom: 0; width: 3px; background: var(--c-warning-fg); }
.row--active.row--high::before { display: none; }
.row__top { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--s-xs); }
.row__no { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.row__title { font-size: var(--t-sm); color: var(--c-text); margin-bottom: var(--s-xs); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.row__spec { color: var(--c-text-3); font-weight: 400; }
.row__meta { display: flex; justify-content: space-between; align-items: center; }
.row__amount { font-size: var(--t-sm); font-weight: 600; color: var(--c-danger-fg); }

.ws__detail-title { font-size: var(--t-md); font-weight: 700; margin: 0; }
.detail__head { display: flex; justify-content: space-between; gap: var(--s-md); padding-bottom: var(--s-md); border-bottom: 1px solid var(--c-border-light); }
.detail__title { font-size: var(--t-lg); font-weight: 700; color: var(--c-text); }
.detail__spec { color: var(--c-text-3); font-weight: 400; font-size: var(--t-md); }
.detail__sub { display: flex; flex-wrap: wrap; gap: var(--s-xs); margin-top: var(--s-xs); align-items: center; }
.tag { display: inline-flex; align-items: center; gap: 3px; font-size: var(--t-xs); padding: 2px 8px; border-radius: var(--r-sm); }
.tag--high { background: var(--c-warning-bg); color: var(--c-warning-fg); }
.detail__amount { text-align: right; }
.detail__amount-label { font-size: var(--t-xs); color: var(--c-text-3); }
.detail__amount-val { font-size: var(--t-xl); font-weight: 700; color: var(--c-danger-fg); font-variant-numeric: tabular-nums; }

.detail__grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md) var(--s-lg); margin: var(--s-lg) 0; }
.field { display: flex; flex-direction: column; gap: 2px; }
.field__label { font-size: var(--t-xs); color: var(--c-text-3); }
.field__val { font-size: var(--t-sm); color: var(--c-text); }

.detail__desc { margin-bottom: var(--s-lg); }
.detail__sec-title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); margin-bottom: var(--s-sm); }
.detail__desc p { font-size: var(--t-sm); color: var(--c-text-2); line-height: var(--lh-md); margin: 0; }
.detail__reject { display: inline-flex; align-items: center; gap: var(--s-xs); padding: var(--s-xs) var(--s-sm); background: var(--c-danger-bg); color: var(--c-danger-fg); border-radius: var(--r-sm); font-size: var(--t-xs); margin-bottom: var(--s-lg); }

.detail__notes { margin-bottom: var(--s-lg); }
.note { display: flex; gap: var(--s-sm); align-items: baseline; padding: var(--s-xs) 0; border-bottom: 1px solid var(--c-border-light); font-size: var(--t-sm); }
.note:last-child { border-bottom: none; }
.note__who { font-weight: 600; color: var(--c-text); flex-shrink: 0; }
.note__text { color: var(--c-text-2); flex: 1; }
.note__time { font-size: var(--t-xs); color: var(--c-text-3); flex-shrink: 0; }

.detail__ops { display: flex; justify-content: flex-end; gap: var(--s-sm); margin-top: var(--s-lg); padding-top: var(--s-lg); border-top: 1px solid var(--c-border-light); }
.ops__done { display: flex; align-items: center; gap: var(--s-sm); font-size: var(--t-sm); color: var(--c-success-fg); font-weight: 600; margin-left: auto; }

.detail-empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-md); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); }
.detail-empty__icon { color: var(--c-text-4); }

.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 600px; max-width: 100%; max-height: 90vh; overflow-y: auto; box-shadow: var(--shadow-pop); }
.modal--sm { width: 360px; }
.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.form__row--2 { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.form__row--3 { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: var(--s-md); }
.form__label { font-size: var(--t-xs); color: var(--c-text-3); }
.num { width: 100%; height: 36px; padding: 0 var(--s-sm); border: 1px solid var(--c-border); border-radius: var(--r-sm); background: var(--c-surface); font-size: var(--t-sm); color: var(--c-text); }
.num:focus { outline: none; border-color: var(--c-brand); }
.confirm__text { font-size: var(--t-sm); color: var(--c-text); text-align: center; margin: var(--s-md) 0; }

@media (max-width: 1024px) {
  .ws__body { grid-template-columns: 1fr; }
  .ws__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .detail__head { flex-direction: column; gap: var(--s-sm); }
  .detail__amount { text-align: left; }
  .list { max-height: 320px; }
}
</style>
