<script setup lang="ts">
/* ============================================================
 * 投诉与医疗风险处理 /complaint（Desktop 优先 · 平板堆叠）
 * 状态机：待受理 → 处理中 → 待结案审批 → 已结案 / 已驳回。
 * 赔付金额签署层级由 settings.tierFor() 推导；医疗风险单强提示。
 * ============================================================ */
import { computed, onMounted, ref, watch } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CTextarea from '@/components/CTextarea.vue'
import CSelect from '@/components/CSelect.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import {
  useComplaintStore,
  type Complaint,
  type ComplaintSeverity,
  type ComplaintSource,
  type ComplaintCategory,
} from '@/stores/complaint'
import { useSettingsStore } from '@/stores/settings'
import { COMPLAINT_STATUS, COMPLAINT_SEVERITY, COMPLAINT_SOURCE, COMPLAINT_CATEGORY, dictPill } from '@/config/dictionary'

const complaint = useComplaintStore()
const settings = useSettingsStore()

onMounted(() => complaint.seed())

type Tab = 'pending_accept' | 'processing' | 'pending_review' | 'closed' | 'rejected'
const tab = ref<Tab>('pending_accept')
const selectedId = ref<string | null>(null)
const keyword = ref('')

const tabs = computed(() => [
  { k: 'pending_accept' as Tab, label: `待受理 (${complaint.pendingAccept.length})` },
  { k: 'processing' as Tab, label: `处理中 (${complaint.processing.length})` },
  { k: 'pending_review' as Tab, label: `待结案审批 (${complaint.pendingReview.length})` },
  { k: 'closed' as Tab, label: `已结案 (${complaint.closed.length})` },
  { k: 'rejected' as Tab, label: `已驳回 (${complaint.rejected.length})` },
])

const baseList = computed<Complaint[]>(() => {
  if (tab.value === 'pending_accept') return complaint.pendingAccept
  if (tab.value === 'processing') return complaint.processing
  if (tab.value === 'pending_review') return complaint.pendingReview
  if (tab.value === 'closed') return complaint.closed
  return complaint.rejected
})

const list = computed<Complaint[]>(() => {
  const kw = keyword.value.trim()
  if (!kw) return baseList.value
  return baseList.value.filter(
    (c) => c.customerName.includes(kw) || c.complaintNo.includes(kw) || c.description.includes(kw),
  )
})

const selected = computed(() => {
  if (selectedId.value) return complaint.get(selectedId.value) ?? null
  return list.value[0] ?? null
})

function selectTab(t: Tab) {
  tab.value = t
  selectedId.value = null
}

const kpis = computed(() => [
  { label: '待受理', value: complaint.pendingAccept.length, tone: 'danger' as const, icon: 'alert' as const },
  { label: '处理中', value: complaint.processing.length, tone: 'warning' as const, icon: 'edit' as const },
  { label: '待结案审批', value: complaint.pendingReview.length, tone: 'warning' as const, icon: 'check-square' as const },
  { label: '医疗风险待处理', value: complaint.medicalRiskOpen.length, tone: 'danger' as const, icon: 'shield' as const },
])


function fmtMoney(n: number) { return '¥' + n.toLocaleString('zh-CN') }
function fmtTime(iso?: string) {
  if (!iso) return '—'
  const d = new Date(iso)
  return `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

// 处理方案提交：选中处理中单据时预填已有方案与赔付
const resolution = ref('')
const compensation = ref('')
const compNum = computed(() => Number(compensation.value) || 0)
const compTier = computed(() => settings.tierFor(compNum.value))
watch(
  selected,
  (c) => {
    if (c && c.status === 'PROCESSING') {
      resolution.value = c.resolution ?? ''
      compensation.value = c.compensationAmount ? String(c.compensationAmount) : ''
    }
  },
  { immediate: true },
)
function doSubmitResolution() {
  if (!selected.value || !resolution.value.trim()) return
  complaint.submitResolution(selected.value.id, resolution.value.trim(), compNum.value)
}
function doAccept() { if (selected.value) complaint.accept(selected.value.id) }
function doApproveClose() { if (selected.value) complaint.approveClose(selected.value.id) }

const sendBackNote = ref('')
const showSendBack = ref(false)
function doSendBack() {
  if (!selected.value || !sendBackNote.value.trim()) return
  complaint.sendBack(selected.value.id, sendBackNote.value.trim())
  showSendBack.value = false
  sendBackNote.value = ''
}

const rejectReason = ref('')
const showReject = ref(false)
function doReject() {
  if (!selected.value || !rejectReason.value.trim()) return
  complaint.reject(selected.value.id, rejectReason.value.trim())
  showReject.value = false
  rejectReason.value = ''
}

// 发起投诉
const showForm = ref(false)
const form = ref({
  customerName: '',
  source: 'STORE' as ComplaintSource,
  severity: 'MEDIUM' as ComplaintSeverity,
  category: 'SERVICE' as ComplaintCategory,
  medicalRisk: false,
  relatedOrderNo: '',
  description: '',
  compensationAmount: '',
})
const formCompNum = computed(() => Number(form.value.compensationAmount) || 0)
const formTier = computed(() => settings.tierFor(formCompNum.value))
const canSubmit = computed(
  () => form.value.customerName.trim() && form.value.description.trim(),
)
function submitForm() {
  if (!canSubmit.value) return
  const c = complaint.create({
    customerId: 'C-NEW',
    customerName: form.value.customerName.trim(),
    source: form.value.source,
    severity: form.value.severity,
    category: form.value.category,
    medicalRisk: form.value.medicalRisk,
    relatedOrderNo: form.value.relatedOrderNo,
    description: form.value.description.trim(),
    compensationAmount: formCompNum.value,
  })
  if (c) {
    showForm.value = false
    form.value = {
      customerName: '', source: 'STORE', severity: 'MEDIUM', category: 'SERVICE',
      medicalRisk: false, relatedOrderNo: '', description: '', compensationAmount: '',
    }
    selectedId.value = c.id
    tab.value = 'pending_accept'
  }
}
</script>

<template>
  <div class="cp">
    <!-- 医疗风险预警条 -->
    <div v-if="complaint.medicalRiskOpen.length > 0" class="riskbar">
      <CIcon name="alert" :size="16" />
      <span>有 <strong>{{ complaint.medicalRiskOpen.length }}</strong> 起医疗风险投诉待处理，请优先跟进并确保处理方案留痕。</span>
    </div>

    <div class="cp__head">
      <div class="cp__kpis">
        <div v-for="k in kpis" :key="k.label" class="kpi-item">
          <div class="kpi-item__icon" :class="`kpi-item__icon--${k.tone}`">
            <CIcon :name="k.icon" :size="20" />
          </div>
          <div class="kpi-item__body">
            <div class="kpi-item__label">{{ k.label }}</div>
            <div class="kpi-item__value" :class="`kpi__value--${k.tone}`">{{ k.value }}</div>
          </div>
        </div>
      </div>
    </div>

    <CCard class="cp__toolbar" padding="none">
      <div class="cp__tools">
        <CInput v-model="keyword" placeholder="搜索单号/客户/内容" />
        <CButton variant="primary" v-perm.disable="'complaint:create'" @click="showForm = true">
          <CIcon name="plus" :size="16" />登记投诉
        </CButton>
      </div>
    </CCard>

    <div class="cp__body">
      <!-- 左列 -->
      <CCard class="cp__list" padding="none">
        <div class="tabs">
          <button
            v-for="t in tabs" :key="t.k"
            class="tab" :class="{ 'tab--active': tab === t.k }"
            @click="selectTab(t.k)"
          >{{ t.label }}</button>
        </div>
        <div class="list">
          <div v-if="list.length === 0" class="empty">
            <CIcon name="alert" :size="28" class="empty__icon" />
            <div>暂无记录</div>
          </div>
          <button
            v-for="c in list" :key="c.id"
            class="rec" :class="{ 'rec--active': selected?.id === c.id, 'rec--risk': c.medicalRisk }"
            @click="selectedId = c.id"
          >
            <div class="rec__top">
              <span class="rec__no">{{ c.complaintNo }}</span>
              <CStatusPill :status="dictPill(COMPLAINT_STATUS[c.status]).status">{{ dictPill(COMPLAINT_STATUS[c.status]).text }}</CStatusPill>
            </div>
            <div class="rec__cust">
              {{ c.customerName }}
              <span v-if="c.medicalRisk" class="rec__tag rec__tag--risk">医疗风险</span>
              <CStatusPill :status="dictPill(COMPLAINT_SEVERITY[c.severity]).status" dot>{{ dictPill(COMPLAINT_SEVERITY[c.severity]).text }}级</CStatusPill>
            </div>
            <div class="rec__proj">{{ c.description }}</div>
            <div class="rec__meta">
              <span>{{ COMPLAINT_CATEGORY[c.category]?.label }} · {{ COMPLAINT_SOURCE[c.source]?.label }}</span>
              <span v-if="c.compensationAmount > 0" class="rec__amt">{{ fmtMoney(c.compensationAmount) }}</span>
            </div>
          </button>
        </div>
      </CCard>

      <!-- 右列详情 -->
      <CCard v-if="selected" class="cp__detail" :title="selected.complaintNo">
        <template #header>
          <h3 class="cp__detail-title">{{ selected.complaintNo }}</h3>
          <div class="cp__detail-tags">
            <CStatusPill v-if="selected.medicalRisk" status="danger" dot>医疗风险</CStatusPill>
            <CStatusPill :status="dictPill(COMPLAINT_STATUS[selected.status]).status">{{ dictPill(COMPLAINT_STATUS[selected.status]).text }}</CStatusPill>
          </div>
        </template>

        <div class="cust">
          <div class="cust__name">
            {{ selected.customerName }}
            <span class="cust__sev">{{ dictPill(COMPLAINT_SEVERITY[selected.severity]).text }}级投诉</span>
          </div>
          <div class="cust__sub">
            {{ COMPLAINT_CATEGORY[selected.category]?.label }} · {{ COMPLAINT_SOURCE[selected.source]?.label }}
            <template v-if="selected.relatedOrderNo"> · 关联订单 {{ selected.relatedOrderNo }}</template>
          </div>
        </div>

        <div class="desc">
          <div class="desc__label">投诉内容</div>
          <div class="desc__val">{{ selected.description }}</div>
        </div>

        <div class="grid">
          <div class="field"><span class="field__label">责任门店</span><span class="field__val">{{ selected.storeName }}</span></div>
          <div class="field"><span class="field__label">登记时间</span><span class="field__val">{{ fmtTime(selected.createdAt) }}</span></div>
          <div class="field"><span class="field__label">赔付金额</span><span class="field__val field__val--amt">{{ fmtMoney(selected.compensationAmount) }}</span></div>
          <div class="field"><span class="field__label">签署层级</span><span class="field__val">{{ selected.signTier }}（阈值来自设置中心）</span></div>
        </div>

        <div v-if="selected.resolution" class="resolution">
          <div class="resolution__label">处理方案（{{ selected.submittedByName }}）</div>
          <div class="resolution__val">{{ selected.resolution }}</div>
        </div>
        <div v-if="selected.rejectionReason" class="reject-note">
          <strong>驳回原因：</strong>{{ selected.rejectionReason }}
        </div>

        <!-- 时间线 -->
        <div class="tl">
          <div class="tl__title">处理轨迹</div>
          <div v-for="(e, i) in selected.timeline" :key="i" class="tl__item">
            <div class="tl__dot" />
            <div class="tl__body">
              <div class="tl__head">
                <span class="tl__action">{{ e.action }}</span>
                <span class="tl__time">{{ fmtTime(e.at) }} · {{ e.by }}</span>
              </div>
              <div v-if="e.note" class="tl__note">{{ e.note }}</div>
            </div>
          </div>
        </div>

        <!-- 处理中：填写方案 -->
        <div v-if="selected.status === 'PROCESSING'" class="work">
          <div class="work__title">提交处理方案</div>
          <CInput v-model="compensation" type="number" placeholder="赔付金额（元，无赔付填 0）" />
          <div class="work__tier">
            赔付签署层级：<strong>{{ compTier }}</strong>
            <span class="work__tier-hint">{{ compTier === 'L1' ? '门店审批即可结案' : '需区域/集团审批结案' }}</span>
          </div>
          <CTextarea v-model="resolution" placeholder="请填写处理方案与客户沟通结果（医疗风险单须说明处置与随访安排）" />
          <div class="ops">
            <CButton variant="ghost" v-perm.disable="'complaint:approve'" @click="showReject = true">驳回（无效投诉）</CButton>
            <CButton variant="primary" :disabled="!resolution.trim()" v-perm.disable="'complaint:edit'" @click="doSubmitResolution">
              提交方案并送审
            </CButton>
          </div>
        </div>

        <!-- 操作区 -->
        <div v-else class="ops">
          <template v-if="selected.status === 'PENDING_ACCEPT'">
            <CButton variant="ghost" v-perm.disable="'complaint:approve'" @click="showReject = true">驳回</CButton>
            <CButton variant="primary" v-perm.disable="'complaint:edit'" @click="doAccept">
              <CIcon name="check" :size="16" />受理投诉
            </CButton>
          </template>
          <template v-else-if="selected.status === 'PENDING_REVIEW'">
            <CButton variant="ghost" v-perm.disable="'complaint:approve'" @click="showSendBack = true">退回补充</CButton>
            <CButton variant="ghost" v-perm.disable="'complaint:approve'" @click="showReject = true">驳回</CButton>
            <CButton variant="primary" v-perm.disable="'complaint:approve'" @click="doApproveClose">
              <CIcon name="check-square" :size="16" />审批结案
            </CButton>
          </template>
          <div v-else-if="selected.status === 'CLOSED'" class="ops__done">
            <CIcon name="check-square" :size="16" />已于 {{ fmtTime(selected.closedAt) }} 由 {{ selected.closedByName }} 结案
          </div>
        </div>

        <!-- 退回输入 -->
        <div v-if="showSendBack" class="inline-box">
          <CInput v-model="sendBackNote" placeholder="请输入退回补充说明（必填）" />
          <div class="inline-box__btns">
            <CButton variant="ghost" @click="showSendBack = false; sendBackNote = ''">取消</CButton>
            <CButton variant="primary" :disabled="!sendBackNote.trim()" @click="doSendBack">确认退回</CButton>
          </div>
        </div>
        <!-- 驳回输入 -->
        <div v-if="showReject" class="inline-box">
          <CInput v-model="rejectReason" placeholder="请输入驳回原因（必填）" />
          <div class="inline-box__btns">
            <CButton variant="ghost" @click="showReject = false; rejectReason = ''">取消</CButton>
            <CButton variant="primary" :disabled="!rejectReason.trim()" @click="doReject">确认驳回</CButton>
          </div>
        </div>
      </CCard>

      <CCard v-else class="cp__detail cp__detail--empty" title="投诉详情">
        <div class="detail-empty">
          <CIcon name="alert" :size="40" class="detail-empty__icon" />
          <p>请从左侧选择一笔投诉</p>
        </div>
      </CCard>
    </div>

    <!-- 登记投诉弹层 -->
    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <CCard class="modal" title="登记投诉" padding="lg">
        <div class="form">
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">客户姓名</label>
              <CInput v-model="form.customerName" placeholder="如：王美丽" />
            </div>
            <div>
              <label class="form__label">关联订单号（选填）</label>
              <CInput v-model="form.relatedOrderNo" placeholder="如：SO20260824001" />
            </div>
          </div>
          <div class="form__row form__row--3">
            <div>
              <label class="form__label">来源</label>
              <CSelect v-model="form.source" width="100%" :options="[
                { value: 'STORE', label: '到店' },
                { value: 'PHONE', label: '电话' },
                { value: 'ONLINE', label: '线上' },
                { value: 'THIRD_PARTY', label: '第三方平台' },
              ]" />
            </div>
            <div>
              <label class="form__label">类别</label>
              <CSelect v-model="form.category" width="100%" :options="[
                { value: 'SERVICE', label: '服务态度' },
                { value: 'MEDICAL', label: '医疗风险' },
                { value: 'BILLING', label: '收费争议' },
                { value: 'OUTCOME', label: '效果争议' },
                { value: 'OTHER', label: '其他' },
              ]" />
            </div>
            <div>
              <label class="form__label">严重等级</label>
              <CSelect v-model="form.severity" width="100%" :options="[
                { value: 'LOW', label: '低' },
                { value: 'MEDIUM', label: '中' },
                { value: 'HIGH', label: '高' },
              ]" />
            </div>
          </div>
          <label class="form__check">
            <input type="checkbox" v-model="form.medicalRisk" />
            <span>标记为医疗风险投诉（将高亮预警，处理须留痕）</span>
          </label>
          <div class="form__row">
            <label class="form__label">投诉内容</label>
            <CTextarea v-model="form.description" placeholder="请详细描述投诉经过与客户诉求" />
          </div>
          <div class="form__row form__row--2">
            <div>
              <label class="form__label">拟赔付金额（元）</label>
              <CInput v-model="form.compensationAmount" type="number" placeholder="0" />
            </div>
            <div class="form__tier">
              签署层级：<strong>{{ formTier }}</strong>
              <span class="form__tier-hint">{{ formTier === 'L1' ? '门店可结案' : '需区域/集团审批结案' }}</span>
            </div>
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showForm = false">取消</CButton>
          <CButton variant="primary" :disabled="!canSubmit" @click="submitForm">提交登记</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.cp { display: flex; flex-direction: column; gap: var(--s-lg); }

.riskbar {
  display: flex; align-items: center; gap: var(--s-sm);
  padding: var(--s-sm) var(--s-md); border-radius: var(--r-md);
  background: var(--c-danger-bg); color: var(--c-danger-fg); font-size: var(--t-sm);
  border: 1px solid var(--c-danger-fg);
}
.riskbar strong { margin: 0 2px; }

.cp__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .cp__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
.cp__kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); }
.kpi-item {
  display: flex; align-items: center; gap: var(--s-md);
  background: var(--c-surface); border: 1px solid var(--c-border-light);
  border-radius: var(--r-xl); padding: var(--s-md);
}
.kpi-item__icon {
  width: 40px; height: 40px; border-radius: var(--r-lg);
  background: var(--c-brand-soft); color: var(--c-brand);
  display: flex; align-items: center; justify-content: center; flex-shrink: 0;
}
.kpi-item__icon--warning { background: var(--c-warning-bg, #fff7e6); color: var(--c-warning-fg); }
.kpi-item__icon--danger { background: var(--c-danger-bg, #fff1f0); color: var(--c-danger-fg); }
.kpi-item__icon--success { background: var(--c-success-bg, #f0fbf0); color: var(--c-success-fg); }
.kpi-item__body { min-width: 0; flex: 1; }
.kpi-item__label { font-size: var(--t-xs); color: var(--c-text-3); line-height: var(--lh-xs); }
.kpi-item__value { font-size: var(--t-lg); font-weight: 700; line-height: 1.3; font-variant-numeric: tabular-nums; }
.kpi__value--warning { color: var(--c-warning-fg); }
.kpi__value--danger { color: var(--c-danger-fg); }

.cp__body { display: grid; grid-template-columns: 380px 1fr; gap: var(--s-lg); align-items: start; }

.cp__detail-title { font-size: var(--t-md); line-height: var(--lh-md); font-weight: 700; color: var(--c-text); margin: 0; }
.cp__detail-tags { display: flex; gap: var(--s-xs); }

.tabs { display: flex; align-items: center; border-bottom: 1px solid var(--c-border); overflow-x: auto; padding: 0 var(--s-sm); gap: var(--s-xs); }
.tab {
  flex: 1 0 auto; padding: var(--s-md) var(--s-sm); font-size: var(--t-xs); white-space: nowrap;
  color: var(--c-text-3); background: none; border: none; cursor: pointer;
  border-bottom: 2px solid transparent;
}
.tab--active { color: var(--c-brand); border-bottom-color: var(--c-brand); font-weight: 600; }

/* 独立工具行：搜索 + 登记（卡片底部） */
.cp__toolbar { flex-shrink: 0; }
.cp__tools { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-md); flex-wrap: nowrap; }
.cp__tools > .cinput { flex: 1; min-width: 0; }
.cp__tools :deep(.cbtn) { flex-shrink: 0; white-space: nowrap; }

.list { max-height: 560px; overflow-y: auto; }
.empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); font-size: var(--t-sm); }
.empty__icon { color: var(--c-text-4); }

.rec {
  display: block; width: 100%; text-align: left; padding: var(--s-md) var(--s-lg);
  background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer;
  border-left: 3px solid transparent;
}
.rec:hover { background: var(--c-brand-soft); }
.rec--active { background: var(--c-brand-soft); }
.rec--risk { border-left-color: var(--c-danger-fg); }
.rec__top { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--s-xs); }
.rec__no { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.rec__cust { font-size: var(--t-sm); color: var(--c-text); margin-bottom: 4px; display: flex; align-items: center; gap: var(--s-xs); }
.rec__tag { font-size: var(--t-xs); padding: 0 6px; border-radius: var(--r-pill); }
.rec__tag--risk { background: var(--c-danger-bg); color: var(--c-danger-fg); }
.rec__proj { font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: var(--s-xs); display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.rec__meta { display: flex; justify-content: space-between; font-size: var(--t-xs); color: var(--c-text-3); }
.rec__amt { font-weight: 600; color: var(--c-danger-fg); }

.cust { padding-bottom: var(--s-md); border-bottom: 1px solid var(--c-border-light); }
.cust__name { font-size: var(--t-lg); font-weight: 700; color: var(--c-text); display: flex; align-items: center; gap: var(--s-sm); }
.cust__sev { font-size: var(--t-xs); font-weight: 400; color: var(--c-warning-fg); }
.cust__sub { font-size: var(--t-sm); color: var(--c-text-3); margin-top: 2px; }

.desc { margin: var(--s-md) 0; padding: var(--s-md); background: var(--c-bg-page); border-radius: var(--r-md); }
.desc__label { font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: 4px; }
.desc__val { font-size: var(--t-sm); color: var(--c-text); line-height: 1.7; }

.grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md) var(--s-lg); margin-bottom: var(--s-md); }
.field { display: flex; flex-direction: column; gap: 2px; }
.field__label { font-size: var(--t-xs); color: var(--c-text-3); }
.field__val { font-size: var(--t-sm); color: var(--c-text); }
.field__val--amt { font-size: var(--t-lg); font-weight: 700; color: var(--c-danger-fg); }

.resolution { margin-bottom: var(--s-md); padding: var(--s-md); background: var(--c-brand-soft); border-radius: var(--r-md); }
.resolution__label { font-size: var(--t-xs); color: var(--c-brand); font-weight: 600; margin-bottom: 4px; }
.resolution__val { font-size: var(--t-sm); color: var(--c-text); line-height: 1.7; }

.reject-note { margin-bottom: var(--s-md); padding: var(--s-sm) var(--s-md); background: var(--c-danger-bg); color: var(--c-danger-fg); border-radius: var(--r-md); font-size: var(--t-sm); line-height: 1.6; }

.tl { border-left: 2px solid var(--c-border); padding-left: var(--s-md); margin: var(--s-md) 0; display: flex; flex-direction: column; gap: var(--s-md); }
.tl__title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); margin-left: calc(-1 * var(--s-md) - 1px); }
.tl__item { position: relative; }
.tl__dot { position: absolute; left: calc(-1 * var(--s-md) - 5px); top: 4px; width: 8px; height: 8px; border-radius: 50%; background: var(--c-brand); border: 2px solid var(--c-surface); }
.tl__head { display: flex; justify-content: space-between; gap: var(--s-sm); font-size: var(--t-sm); }
.tl__action { font-weight: 600; color: var(--c-text); }
.tl__time { color: var(--c-text-3); font-size: var(--t-xs); white-space: nowrap; }
.tl__note { font-size: var(--t-xs); color: var(--c-text-2); margin-top: 2px; line-height: 1.6; }

.work { margin-top: var(--s-lg); padding-top: var(--s-lg); border-top: 1px solid var(--c-border-light); display: flex; flex-direction: column; gap: var(--s-sm); }
.work__title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.work__tier { font-size: var(--t-sm); color: var(--c-text-2); padding: var(--s-sm) var(--s-md); background: var(--c-brand-soft); border-radius: var(--r-md); }
.work__tier strong { color: var(--c-brand); margin: 0 var(--s-xs); }
.work__tier-hint { color: var(--c-text-3); font-size: var(--t-xs); }

.ops { display: flex; justify-content: flex-end; gap: var(--s-sm); margin-top: var(--s-md); }
.ops__done { display: flex; align-items: center; gap: var(--s-sm); font-size: var(--t-sm); color: var(--c-success-fg); font-weight: 600; margin-left: auto; }

.inline-box { margin-top: var(--s-md); display: flex; flex-direction: column; gap: var(--s-sm); }
.inline-box__btns { display: flex; justify-content: flex-end; gap: var(--s-sm); }

.detail-empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-md); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); }
.detail-empty__icon { color: var(--c-text-4); }

.modal-mask { position: fixed; inset: 0; background: rgba(20,21,43,.45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 600px; max-width: 100%; max-height: 90vh; overflow-y: auto; box-shadow: var(--shadow-pop); }
.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.form__row--2 { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.form__row--3 { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: var(--s-md); }
.form__label { font-size: var(--t-xs); color: var(--c-text-3); }
.form__check { display: flex; align-items: center; gap: var(--s-xs); font-size: var(--t-sm); color: var(--c-danger-fg); cursor: pointer; }
.form__tier { font-size: var(--t-sm); color: var(--c-text-2); padding: var(--s-sm) var(--s-md); background: var(--c-brand-soft); border-radius: var(--r-md); align-self: end; }
.form__tier strong { color: var(--c-brand); margin: 0 var(--s-xs); }
.form__tier-hint { color: var(--c-text-3); font-size: var(--t-xs); }

@media (max-width: 1024px) {
  .cp__body { grid-template-columns: 1fr; }
  .cp__kpis { grid-template-columns: repeat(2, 1fr); min-width: 0; }
  .list { max-height: 320px; }
}
</style>
