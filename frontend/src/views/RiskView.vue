<script setup lang="ts">
/* ============================================================
 * 黑名单与风控 /m3-risk（M3-17）
 * 黑/风险名单、风控规则、拉黑解黑审批、命中拦截交易。
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
import { useRiskStore, type RiskLevel, type RiskReason, type RiskRecord } from '@/stores/risk'
import { useAuthStore } from '@/stores/auth'

const store = useRiskStore()
const auth = useAuthStore()
onMounted(() => {})

const selectedId = ref<string | null>(null)
const selected = computed<RiskRecord | null>(() => {
  if (selectedId.value) return store.get(selectedId.value) ?? null
  return store.filtered[0] ?? null
})

const kpis = computed(() => [
  { label: '已拉黑', icon: 'alert', value: String(store.blacklisted.length), tone: 'danger' as const },
  { label: '待审核', icon: 'check-square', value: String(store.pending.length), tone: 'warning' as const },
  { label: '观察中', icon: 'alert', value: String(store.watching.length), tone: 'orange' as const },
  { label: '高风险', icon: 'alert', value: String(store.highRisk.length), tone: 'brand' as const },
])

const statusOptions = [
  { value: 'ALL', label: '全部状态' },
  { value: 'BLACKLISTED', label: '已拉黑' },
  { value: 'PENDING_REVIEW', label: '待审核' },
  { value: 'WATCHING', label: '观察中' },
  { value: 'RELEASED', label: '已解除' },
]
const levelOptions = [
  { value: 'ALL', label: '全部级别' },
  { value: 'HIGH', label: '高风险' },
  { value: 'MEDIUM', label: '中风险' },
  { value: 'LOW', label: '低风险' },
]

function fmtDate(iso: string) {
  const d = new Date(iso)
  return `${d.getMonth() + 1}/${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

// 新建拉黑
const showForm = ref(false)
const form = ref({ customerName: '', phoneMask: '', level: 'HIGH' as RiskLevel, reason: 'FRAUD' as RiskReason, detail: '' })
const canSubmit = computed(() => form.value.customerName.trim() && form.value.detail.trim())
function submitForm() {
  if (!canSubmit.value) return
  store.addToBlacklist(form.value.customerName, form.value.phoneMask || '138****0000', form.value.level, form.value.reason, form.value.detail)
  showForm.value = false
  form.value = { customerName: '', phoneMask: '', level: 'HIGH', reason: 'FRAUD', detail: '' }
}

// 审批/解除
const rejectOpen = ref(false)
const releaseOpen = ref(false)
const reasonText = ref('')
function openReject() { reasonText.value = ''; rejectOpen.value = true }
function openRelease() { reasonText.value = ''; releaseOpen.value = true }
function doApprove() { if (selected.value) store.approve(selected.value.id) }
function doReject() { if (selected.value && reasonText.value.trim()) { store.reject(selected.value.id, reasonText.value); rejectOpen.value = false } }
function doRelease() { if (selected.value && reasonText.value.trim()) { store.release(selected.value.id, reasonText.value); releaseOpen.value = false } }

const canApprove = computed(() => auth.can('risk:approve'))
const canEdit = computed(() => auth.can('risk:edit'))
</script>

<template>
  <div class="rk">
    <div class="rk__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <div class="rk__body">
      <CCard class="rk__list" padding="none">
        <div class="filters">
          <CSelect v-model="store.filterStatus" :options="statusOptions" />
          <CSelect v-model="store.filterLevel" :options="levelOptions" />
          <CButton variant="primary" size="sm" class="filters__create" v-perm.disable="'risk:edit'" @click="showForm = true">
            <CIcon name="plus" :size="14" />提交拉黑
          </CButton>
        </div>
        <div class="list">
          <div v-if="store.filtered.length === 0" class="empty"><CIcon name="shield" :size="28" class="empty__icon" /><div>暂无记录</div></div>
          <button v-for="r in store.filtered" :key="r.id" class="row" :class="{ 'row--active': selected?.id === r.id, 'row--black': r.status === 'BLACKLISTED' }" @click="selectedId = r.id">
            <div class="row__top">
              <span class="row__name">{{ r.customerName }}</span>
              <CStatusPill :status="store.STATUS_PILL[r.status]">{{ store.STATUS_LABEL[r.status] }}</CStatusPill>
            </div>
            <div class="row__meta">
              <span class="lvl" :class="`lvl--${r.level.toLowerCase()}`">{{ store.LEVEL_LABEL[r.level] }}</span>
              <span>{{ store.REASON_LABEL[r.reason] }}</span>
              <span>命中 {{ r.hitCount }} 次</span>
            </div>
            <div class="row__phone">{{ r.phoneMask }}</div>
          </button>
        </div>
      </CCard>

      <CCard v-if="selected" class="rk__detail" padding="lg">
        <template #header>
          <div class="det__head">
            <div>
              <h3 class="det__name">{{ selected.customerName }} <span class="det__no">{{ selected.riskNo }}</span></h3>
              <div class="det__sub">
                <CStatusPill :status="store.STATUS_PILL[selected.status]">{{ store.STATUS_LABEL[selected.status] }}</CStatusPill>
                <span class="lvl-tag" :class="`lvl--${selected.level.toLowerCase()}`">{{ store.LEVEL_LABEL[selected.level] }}</span>
                <span class="reason-tag">{{ store.REASON_LABEL[selected.reason] }}</span>
              </div>
            </div>
            <label class="block-switch">
              <input type="checkbox" :checked="selected.blockTransactions" disabled />
              <span>拦截交易</span>
            </label>
          </div>
        </template>

        <div class="det__grid">
          <div class="field"><span class="field__label">手机号</span><span class="field__val">{{ selected.phoneMask }}</span></div>
          <div class="field"><span class="field__label">命中次数</span><span class="field__val">{{ selected.hitCount }} 次</span></div>
          <div class="field"><span class="field__label">提交人</span><span class="field__val">{{ selected.operator }}</span></div>
          <div class="field"><span class="field__label">提交时间</span><span class="field__val">{{ fmtDate(selected.createdAt) }}</span></div>
        </div>

        <div class="det__sec">
          <div class="det__sec-title">风险说明</div>
          <p class="det__desc">{{ selected.reasonDetail }}</p>
        </div>

        <div class="det__sec">
          <div class="det__sec-title">处理时间线</div>
          <div class="timeline">
            <div v-for="(t, i) in selected.timeline" :key="i" class="tl-row">
              <div class="tl-dot" />
              <div class="tl-body">
                <div class="tl-action">{{ t.action }}</div>
                <div class="tl-meta">{{ t.by }} · {{ fmtDate(t.at) }}</div>
                <div v-if="t.comment" class="tl-comment">{{ t.comment }}</div>
              </div>
            </div>
          </div>
        </div>

        <div class="det__ops">
          <template v-if="selected.status === 'PENDING_REVIEW'">
            <CButton variant="ghost" v-if="canApprove" @click="openReject">驳回转观察</CButton>
            <CButton variant="primary" v-if="canApprove" @click="doApprove">
              <CIcon name="check" :size="16" />审核通过拉黑
            </CButton>
          </template>
          <template v-else-if="selected.status === 'BLACKLISTED' || selected.status === 'WATCHING'">
            <CButton variant="ghost" v-if="canEdit" @click="openRelease">
              <CIcon name="check" :size="16" />解除风险
            </CButton>
            <span v-if="!canEdit && !canApprove" class="ops__hint">无风控操作权限</span>
          </template>
          <div v-else class="ops__done">
            <CIcon name="check" :size="16" />已于 {{ fmtDate(selected.resolvedAt!) }} 由 {{ selected.resolvedBy }} 解除
          </div>
        </div>
      </CCard>

      <CCard v-else class="rk__detail" padding="lg"><div class="empty-big">请选择一条记录</div></CCard>
    </div>

    <!-- 风控规则 -->
    <CCard class="rk__rules" padding="lg">
      <template #header><h3 class="rules__title">风控规则引擎</h3><span class="rules__hint">已启用 {{ store.enabledRules.length }} / {{ store.rules.length }} 条</span></template>
      <div class="rules-grid">
        <div v-for="r in store.rules" :key="r.id" class="rule">
          <div class="rule__top">
            <span class="rule__name">{{ r.name }}</span>
            <label class="switch">
              <input type="checkbox" :checked="r.enabled" :disabled="!canEdit" @change="store.toggleRule(r.id)" />
              <span class="slider" />
            </label>
          </div>
          <p class="rule__desc">{{ r.description }}</p>
          <div class="rule__foot">
            <CStatusPill :status="r.action === 'BLOCK' ? 'danger' : r.action === 'REVIEW' ? 'warning' : 'info'">{{ r.action === 'BLOCK' ? '拦截交易' : r.action === 'REVIEW' ? '人工审核' : '预警提示' }}</CStatusPill>
            <span class="rule__hits">累计命中 {{ r.hitCount }} 次</span>
          </div>
        </div>
      </div>
    </CCard>

    <!-- 新建弹层 -->
    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <CCard class="modal" title="提交拉黑审核" padding="lg">
        <div class="form">
          <div class="form__row"><label class="form__label">客户姓名</label><CInput v-model="form.customerName" placeholder="如：赵某某" /></div>
          <div class="form__row"><label class="form__label">手机号（脱敏）</label><CInput v-model="form.phoneMask" placeholder="如：135****0011" /></div>
          <div class="form__row form__row--2">
            <div><label class="form__label">风险级别</label>
              <CSelect v-model="form.level" :options="[{value:'HIGH',label:'高风险'},{value:'MEDIUM',label:'中风险'},{value:'LOW',label:'低风险'}]" />
            </div>
            <div><label class="form__label">风险类型</label>
              <CSelect v-model="form.reason" :options="[{value:'FRAUD',label:'疑似欺诈'},{value:'CHARGEBACK',label:'恶意退单'},{value:'MALICIOUS_COMPLAINT',label:'恶意投诉'},{value:'ILLEGAL_PRACTICE',label:'违规医托'},{value:'OTHER',label:'其他'}]" />
            </div>
          </div>
          <div class="form__row"><label class="form__label">详细说明</label><CTextarea v-model="form.detail" placeholder="请描述风险事实与证据" /></div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showForm = false">取消</CButton>
          <CButton variant="primary" :disabled="!canSubmit" @click="submitForm">提交审核</CButton>
        </template>
      </CCard>
    </div>

    <!-- 驳回/解除弹层共用 -->
    <div v-if="rejectOpen || releaseOpen" class="modal-mask" @click.self="rejectOpen = false; releaseOpen = false">
      <CCard class="modal modal--sm" :title="rejectOpen ? '驳回转观察' : '解除风险'" padding="lg">
        <label class="form__label">{{ rejectOpen ? '驳回原因' : '解除原因' }}</label>
        <CTextarea v-model="reasonText" :placeholder="rejectOpen ? '说明驳回理由' : '说明解除依据'" />
        <template #footer>
          <CButton variant="ghost" @click="rejectOpen = false; releaseOpen = false">取消</CButton>
          <CButton variant="primary" @click="rejectOpen ? doReject() : doRelease()">确认</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.rk { display: flex; flex-direction: column; gap: var(--s-lg); }
.rk__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .rk__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }

.rk__body { display: grid; grid-template-columns: 380px 1fr; gap: var(--s-lg); align-items: start; }
.filters { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-md); border-bottom: 1px solid var(--c-border-light); flex-wrap: nowrap; overflow-x: auto; }
.filters__create { margin-left: auto; flex-shrink: 0; }
.list { max-height: 560px; overflow-y: auto; }
.empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); font-size: var(--t-sm); }
.empty__icon { color: var(--c-text-4); }
.row { display: block; width: 100%; text-align: left; padding: var(--s-md) var(--s-lg); background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer; border-left: 3px solid transparent; }
.row:hover { background: var(--c-brand-soft); }
.row--active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.row--black { border-left-color: var(--c-danger-fg); }
.row__top { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--s-xs); }
.row__name { font-weight: 600; color: var(--c-text); font-size: var(--t-sm); }
.row__meta { display: flex; flex-wrap: wrap; gap: var(--s-xs); font-size: var(--t-xs); color: var(--c-text-3); align-items: center; }
.row__phone { font-size: var(--t-xs); color: var(--c-text-4); margin-top: 2px; }
.lvl { padding: 1px 6px; border-radius: var(--r-sm); font-size: 10px; }
.lvl--high { background: var(--c-danger-bg); color: var(--c-danger-fg); }
.lvl--medium { background: var(--c-warning-bg); color: var(--c-warning-fg); }
.lvl--low { background: var(--c-surface-muted, #f0f2f5); color: var(--c-text-3); }

.det__head { display: flex; justify-content: space-between; gap: var(--s-md); padding-bottom: var(--s-md); border-bottom: 1px solid var(--c-border-light); }
.det__name { font-size: var(--t-lg); font-weight: 700; margin: 0; color: var(--c-text); }
.det__no { font-size: var(--t-xs); color: var(--c-text-3); font-weight: 400; }
.det__sub { display: flex; flex-wrap: wrap; gap: var(--s-xs); margin-top: var(--s-xs); align-items: center; }
.lvl-tag { font-size: var(--t-xs); padding: 1px 8px; border-radius: var(--r-sm); }
.reason-tag { font-size: var(--t-xs); color: var(--c-text-2); background: var(--c-surface-muted, #f3f4f8); padding: 1px 8px; border-radius: var(--r-sm); }
.block-switch { display: flex; align-items: center; gap: 6px; font-size: var(--t-sm); color: var(--c-text-2); white-space: nowrap; }

.det__grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md) var(--s-lg); margin: var(--s-lg) 0; }
.field { display: flex; flex-direction: column; gap: 2px; }
.field__label { font-size: var(--t-xs); color: var(--c-text-3); }
.field__val { font-size: var(--t-sm); color: var(--c-text); }
.det__sec { margin-bottom: var(--s-lg); }
.det__sec-title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); margin-bottom: var(--s-sm); }
.det__desc { font-size: var(--t-sm); color: var(--c-text-2); line-height: var(--lh-md); margin: 0; padding: var(--s-md); background: var(--c-danger-bg); border-radius: var(--r-md); }

.timeline { display: flex; flex-direction: column; }
.tl-row { display: flex; gap: var(--s-sm); padding: var(--s-xs) 0; }
.tl-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--c-brand); margin-top: 6px; flex-shrink: 0; }
.tl-body { flex: 1; padding-bottom: var(--s-sm); border-bottom: 1px solid var(--c-border-light); }
.tl-action { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.tl-meta { font-size: var(--t-xs); color: var(--c-text-3); }
.tl-comment { font-size: var(--t-xs); color: var(--c-text-2); margin-top: 2px; }

.det__ops { display: flex; justify-content: flex-end; gap: var(--s-sm); margin-top: var(--s-lg); padding-top: var(--s-lg); border-top: 1px solid var(--c-border-light); }
.ops__hint { font-size: var(--t-sm); color: var(--c-text-3); }
.ops__done { display: flex; align-items: center; gap: var(--s-xs); font-size: var(--t-sm); color: var(--c-success-fg); font-weight: 600; margin-left: auto; }
.empty-big { text-align: center; color: var(--c-text-3); padding: var(--s-xxl) 0; }

.rules__title { font-size: var(--t-md); font-weight: 700; margin: 0; }
.rules__hint { font-size: var(--t-xs); color: var(--c-text-3); }
.rules-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--s-md); }
.rule { border: 1px solid var(--c-border); border-radius: var(--r-md); padding: var(--s-md); }
.rule__top { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--s-xs); }
.rule__name { font-weight: 600; font-size: var(--t-sm); color: var(--c-text); }
.rule__desc { font-size: var(--t-xs); color: var(--c-text-3); line-height: var(--lh-md); margin: 0 0 var(--s-sm); min-height: 32px; }
.rule__foot { display: flex; justify-content: space-between; align-items: center; }
.rule__hits { font-size: 10px; color: var(--c-text-4); }
.switch { position: relative; display: inline-block; width: 38px; height: 20px; }
.switch input { opacity: 0; width: 0; height: 0; }
.slider { position: absolute; cursor: pointer; inset: 0; background: var(--c-border); border-radius: 20px; transition: .2s; }
.slider::before { content: ''; position: absolute; height: 16px; width: 16px; left: 2px; top: 2px; background: #fff; border-radius: 50%; transition: .2s; }
.switch input:checked + .slider { background: var(--c-success-fg); }
.switch input:checked + .slider::before { transform: translateX(18px); }

.modal-mask { position: fixed; inset: 0; background: rgba(20,21,43,.45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 520px; max-width: 100%; box-shadow: var(--shadow-pop); }
.modal--sm { width: 380px; }
.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.form__row--2 { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.form__label { font-size: var(--t-xs); color: var(--c-text-3); }

@media (max-width: 1024px) {
  .rk__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .rk__body { grid-template-columns: 1fr; }
  .rules-grid { grid-template-columns: 1fr; }
  .det__grid { grid-template-columns: 1fr; }
}
</style>
