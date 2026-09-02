<script setup lang="ts">
/* ============================================================
 * 物料申领 /m2-requisition（M2-11）
 * 双栏范式：左申领单列表，右详情；4 KPI + 新建弹层。
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
  useRequisitionStore,
  type Requisition,
  type RequisitionItem,
} from '@/stores/requisition'

const auth = useAuthStore()
const store = useRequisitionStore()
onMounted(() => store.seed())

const selectedId = ref<string | null>(null)
const selected = computed<Requisition | null>(() => {
  if (selectedId.value) return store.get(selectedId.value) ?? null
  return store.filtered[0] ?? null
})

const kpis = computed(() => [
  { label: '待提交', icon: 'check-square', value: String(store.drafts.length), tone: 'warning' as const },
  { label: '审批中', icon: 'check-square', value: String(store.submitting.length), tone: 'brand' as const },
  { label: '待签收', icon: 'user-check', value: String(store.approved.length), tone: 'orange' as const },
  { label: '已驳回', icon: 'alert', value: String(store.rejected.length), tone: 'danger' as const },
])

const statusOptions = [
  { value: 'ALL', label: '全部状态' },
  { value: 'DRAFT', label: '草稿' },
  { value: 'SUBMITTING', label: '审批中' },
  { value: 'APPROVED', label: '待签收' },
  { value: 'RECEIVED', label: '已签收' },
  { value: 'REJECTED', label: '已驳回' },
]

function fmtTime(iso?: string) {
  if (!iso) return '—'
  const d = new Date(iso)
  return `${d.getMonth() + 1}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

function totalQty(r: Requisition) {
  return r.items.reduce((s, it) => s + it.qty, 0)
}

// 新建弹层
const showForm = ref(false)
const form = ref({
  purpose: '',
  remark: '',
  rows: [{ name: '', spec: '', qty: 1, unit: '件' }] as Array<{ name: string; spec: string; qty: number; unit: string }>,
})
const canSubmit = computed(
  () => form.value.purpose.trim() && form.value.rows.some((r) => r.name.trim() && r.qty > 0),
)
function addRow() {
  form.value.rows.push({ name: '', spec: '', qty: 1, unit: '件' })
}
function removeRow(i: number) {
  if (form.value.rows.length === 1) return
  form.value.rows.splice(i, 1)
}
function openForm() {
  form.value = {
    purpose: '',
    remark: '',
    rows: [{ name: '', spec: '', qty: 1, unit: '件' }],
  }
  showForm.value = true
}
function submitForm() {
  if (!canSubmit.value) return
  const items: RequisitionItem[] = form.value.rows
    .filter((r) => r.name.trim() && r.qty > 0)
    .map((r) => ({ name: r.name.trim(), spec: r.spec.trim() || undefined, qty: Number(r.qty), unit: r.unit || '件' }))
  const o = store.create({ purpose: form.value.purpose.trim(), remark: form.value.remark.trim() || undefined, items })
  if (o) {
    showForm.value = false
    selectedId.value = o.id
  }
}

// 操作
function doSubmit() {
  if (selected.value) store.submit(selected.value.id)
}
function doApprove() {
  if (selected.value) store.approve(selected.value.id)
}
function doReject() {
  if (!selected.value) return
  const reason = window.prompt('请填写驳回原因', '数量或用途需调整')
  if (reason) store.reject(selected.value.id, reason)
}
function doReceive() {
  if (selected.value) store.receive(selected.value.id)
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
  <div class="rq">
    <div class="rq__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <div class="rq__body">
      <CCard class="rq__list" padding="none">
        <div class="filters">
          <CSelect v-model="store.filterStatus" :options="statusOptions" width="140px" />
          <CInput v-model="store.keyword" placeholder="搜索单号/申领人/物料" />
        </div>
        <div class="list">
          <div v-if="store.filtered.length === 0" class="empty">
            <CIcon name="box" :size="28" class="empty__icon" />
            <div>暂无申领单</div>
          </div>
          <button
            v-for="r in store.filtered" :key="r.id"
            class="row" :class="{ 'row--active': selected?.id === r.id }"
            @click="selectedId = r.id"
          >
            <div class="row__top">
              <span class="row__no">{{ r.rqNo }}</span>
              <CStatusPill :status="store.STATUS_PILL[r.status]">{{ store.STATUS_LABEL[r.status] }}</CStatusPill>
            </div>
            <div class="row__title">{{ r.purpose }}</div>
            <div class="row__meta">
              <span><CIcon name="user" :size="12" /> {{ r.applicant }}</span>
              <span><CIcon name="package" :size="12" /> {{ r.items.length }} 项 / 共 {{ totalQty(r) }} 件</span>
            </div>
          </button>
          <CFab
            :actions="[{ icon: 'plus', label: '新建申领', disabled: !auth.can('requisition:create'), onClick: openForm }]"
          />
        </div>
      </CCard>

      <CCard v-if="selected" class="rq__detail" :title="selected.rqNo">
        <template #header>
          <h3 class="rq__detail-title">{{ selected.rqNo }}</h3>
          <CStatusPill :status="store.STATUS_PILL[selected.status]">{{ store.STATUS_LABEL[selected.status] }}</CStatusPill>
        </template>

        <div class="detail__head">
          <div>
            <div class="detail__title">{{ selected.purpose }}</div>
            <div class="detail__sub">
              <span class="tag tag--brand">申领人 {{ selected.applicant }}</span>
              <span v-if="selected.approver" class="tag">审批 {{ selected.approver }}</span>
              <span v-if="selected.receiver" class="tag">签收 {{ selected.receiver }}</span>
            </div>
          </div>
          <div class="detail__time">
            <div class="detail__time-label">创建时间</div>
            <div class="detail__time-val">{{ fmtTime(selected.createdAt) }}</div>
          </div>
        </div>

        <div class="detail__sec">
          <div class="detail__sec-title">物料清单</div>
          <div class="items">
            <div v-for="(it, i) in selected.items" :key="i" class="item">
              <div class="item__name">
                <span class="item__idx">{{ i + 1 }}</span>
                <div>
                  <div class="item__n">{{ it.name }}</div>
                  <div v-if="it.spec" class="item__spec">{{ it.spec }}</div>
                </div>
              </div>
              <div class="item__qty">{{ it.qty }} {{ it.unit }}</div>
            </div>
          </div>
        </div>

        <div v-if="selected.remark" class="detail__desc">
          <div class="detail__sec-title">备注</div>
          <p>{{ selected.remark }}</p>
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
            <CButton variant="primary" v-perm.disable="'requisition:edit'" @click="ask('确认提交审批？', doSubmit)">
              <CIcon name="check" :size="16" />提交审批
            </CButton>
          </template>
          <template v-else-if="selected.status === 'SUBMITTING'">
            <CButton variant="ghost" v-perm.disable="'requisition:sign'" @click="doReject">驳回</CButton>
            <CButton variant="primary" v-perm.disable="'requisition:sign'" @click="ask('确认审批通过？', doApprove)">
              <CIcon name="check" :size="16" />审批通过
            </CButton>
          </template>
          <template v-else-if="selected.status === 'APPROVED'">
            <CButton variant="primary" v-perm.disable="'requisition:edit'" @click="ask('确认签收全部物料？', doReceive)">
              <CIcon name="check-square" :size="16" />确认签收
            </CButton>
          </template>
          <div v-else class="ops__done">
            <CIcon name="check" :size="16" />
            {{ selected.status === 'RECEIVED' ? `已于 ${fmtTime(selected.receivedAt)} 签收` : '流程已结束' }}
          </div>
        </div>
      </CCard>

      <CCard v-else class="rq__detail rq__detail--empty" title="申领详情">
        <div class="detail-empty">
          <CIcon name="box" :size="40" class="detail-empty__icon" />
          <p>请选择一条申领单</p>
        </div>
      </CCard>
    </div>

    <!-- 新建弹层 -->
    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <CCard class="modal" title="新建物料申领" padding="lg">
        <div class="form">
          <div class="form__row">
            <label class="form__label">用途 / 申领部门</label>
            <CInput v-model="form.purpose" placeholder="如：A03 治疗室日常耗材补充" />
          </div>
          <div class="form__row">
            <label class="form__label">物料明细</label>
            <div class="rows">
              <div v-for="(row, i) in form.rows" :key="i" class="rows__item">
                <CInput v-model="row.name" placeholder="物料名称" />
                <CInput v-model="row.spec" placeholder="规格" />
                <input v-model.number="row.qty" class="num" type="number" min="1" />
                <CInput v-model="row.unit" placeholder="单位" />
                <CButton variant="text" @click="removeRow(i)">
                  <CIcon name="delete" :size="16" />
                </CButton>
              </div>
            </div>
            <CButton variant="text" @click="addRow"><CIcon name="plus" :size="14" />添加物料</CButton>
          </div>
          <div class="form__row">
            <label class="form__label">备注（可选）</label>
            <CTextarea v-model="form.remark" placeholder="如急需、补货时间要求等" />
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
.rq { display: flex; flex-direction: column; gap: var(--s-lg); }
.rq__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .rq__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }

.rq__body { display: grid; grid-template-columns: 380px 1fr; gap: var(--s-lg); align-items: start; }
.rq__list { min-width: 0; display: flex; flex-direction: column; }
.filters { display: flex; gap: var(--s-sm); padding: var(--s-md); border-bottom: 1px solid var(--c-border-light); align-items: center; flex-wrap: wrap; }
.filters :deep(.cinput) { flex: 1; }
.list { max-height: 560px; overflow-y: auto; display: flex; flex-direction: column; }
.empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); font-size: var(--t-sm); }
.empty__icon { color: var(--c-text-4); }

.row {
  display: block; width: 100%; text-align: left; padding: var(--s-md) var(--s-lg);
  background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer;
}
.row:hover { background: var(--c-brand-soft); }
.row--active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.row__top { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--s-xs); }
.row__no { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.row__title { font-size: var(--t-sm); color: var(--c-text); margin-bottom: var(--s-xs); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.row__meta { display: flex; flex-wrap: wrap; gap: var(--s-xs); font-size: var(--t-xs); color: var(--c-text-3); align-items: center; }
.row__meta span { display: inline-flex; align-items: center; gap: 3px; }

.rq__detail-title { font-size: var(--t-md); font-weight: 700; margin: 0; }
.detail__head { display: flex; justify-content: space-between; gap: var(--s-md); padding-bottom: var(--s-md); border-bottom: 1px solid var(--c-border-light); }
.detail__title { font-size: var(--t-lg); font-weight: 700; color: var(--c-text); }
.detail__sub { display: flex; flex-wrap: wrap; gap: var(--s-xs); margin-top: var(--s-xs); }
.tag { font-size: var(--t-xs); padding: 2px 8px; border-radius: var(--r-sm); background: var(--c-disabled-bg); color: var(--c-text-2); }
.tag--brand { background: var(--c-brand-soft); color: var(--c-brand); }
.detail__time { text-align: right; }
.detail__time-label { font-size: var(--t-xs); color: var(--c-text-3); }
.detail__time-val { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }

.detail__sec { margin: var(--s-lg) 0; }
.detail__sec-title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); margin-bottom: var(--s-sm); }
.items { border: 1px solid var(--c-border-light); border-radius: var(--r-md); overflow: hidden; }
.item { display: flex; justify-content: space-between; align-items: center; padding: var(--s-sm) var(--s-md); border-bottom: 1px solid var(--c-border-light); font-size: var(--t-sm); }
.item:last-child { border-bottom: none; }
.item__name { display: flex; gap: var(--s-sm); align-items: center; }
.item__idx { width: 20px; height: 20px; border-radius: 50%; background: var(--c-brand-soft); color: var(--c-brand); font-size: var(--t-xs); display: inline-flex; align-items: center; justify-content: center; font-weight: 600; flex-shrink: 0; }
.item__n { color: var(--c-text); }
.item__spec { font-size: var(--t-xs); color: var(--c-text-3); }
.item__qty { color: var(--c-text); font-weight: 600; }

.detail__desc { margin-bottom: var(--s-lg); }
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
.form__label { font-size: var(--t-xs); color: var(--c-text-3); }
.rows { display: flex; flex-direction: column; gap: var(--s-xs); margin-bottom: var(--s-xs); }
.rows__item { display: grid; grid-template-columns: 2fr 1.4fr 80px 80px 36px; gap: var(--s-xs); align-items: center; }
.num { height: 36px; padding: 0 var(--s-sm); border: 1px solid var(--c-border); border-radius: var(--r-sm); background: var(--c-surface); font-size: var(--t-sm); color: var(--c-text); width: 100%; }
.num:focus { outline: none; border-color: var(--c-brand); }
.confirm__text { font-size: var(--t-sm); color: var(--c-text); text-align: center; margin: var(--s-md) 0; }

@media (max-width: 1024px) {
  .rq__body { grid-template-columns: 1fr; }
  .rq__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .detail__head { flex-direction: column; gap: var(--s-sm); }
  .detail__time { text-align: left; }
  .list { max-height: 320px; }
}
</style>
