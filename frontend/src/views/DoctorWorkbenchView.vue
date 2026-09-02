<script setup lang="ts">
/* ============================================================
 * M4-09b 医师工作台（/doctor）· 审核 + 病历 + 治疗执行
 * 一条主线（按钮文案只写动作，不写流程）：
 *   开单审核：待审核单【驳回 / 改单 / 审核通过】。
 *     审核通过 = 签署首程病历 + 系统自动生成缴费单（诊断/方案自动带入，可直接补改）。
 *   待支付：前往收银台收款（收款后自动解锁治疗）。
 *   治疗执行：待治疗【术前四项核对 → 开始治疗】→ 治疗中【完成治疗】（自动排术后随访）。
 * 合规顺序：病历先于收费、收费先于治疗；治疗解锁双条件＝首程病历已签 + 缴费单已支付。
 * ============================================================ */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useConsultationStore } from '@/stores/consultation'
import { useCustomerStore } from '@/stores/customer'
import { useOrderStore } from '@/stores/order'
import { useAuthStore } from '@/stores/auth'
import { useStoreContext } from '@/stores/storeContext'
import { staffName } from '@/config/staff'
import { RISK_TAG_LABEL } from '@/composables/useCompliance'
import { useToast } from '@/composables/useToast'
import { listPlans, rejectPlan, doctorEditPlan, signPlanEmr, type PlanViewDTO } from '@/api/consultPlan'
import { injectPlanShadows, toPlanItemCmd, isRealPlan, REAL_FLAG } from '@/adapters/consultPlan'
import CWorkbenchShell from '@/components/CWorkbenchShell.vue'
import CButton from '@/components/CButton.vue'
import CIcon from '@/components/CIcon.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CTextarea from '@/components/CTextarea.vue'
import CInput from '@/components/CInput.vue'
import CCheckbox from '@/components/CCheckbox.vue'
import CKpi from '@/components/CKpi.vue'
import CPhotoCompare from '@/components/CPhotoCompare.vue'
import type { ConsultStatus, ConsultContraindication, PlanItem, PlanRevision, PreOpChecklist } from '@/types/domain'

const router = useRouter()
const route = useRoute()
const consultation = useConsultationStore()
const customer = useCustomerStore()
const order = useOrderStore()
const auth = useAuthStore()
const toast = useToast()
const storeCtx = useStoreContext()

onMounted(async () => {
  order.seed()
  customer.seedProfile()
  consultation.seed()
  // 从电子病历管理/开方开单等页跳回时，自动定位到对应方案单
  const cid = route.query.fromConsult
  await loadRealPlans()
  if (typeof cid === 'string' && consultation.get(cid)) selectConsult(cid)
})

// ============================================================
// 真实方案单加载（开单审核队列）：listPlans(PENDING_REVIEW/APPROVED) → 影子注入 mock store
// 治疗执行（READY_PAY/PAID/TREATING）后端暂无「开始/完成治疗」端点，保持 mock 演示、不注入真实单，
// 避免真实单落入缺口动作造成本地假交互（真实单签病历后走收银台收款，符合诊疗主线）。
// ============================================================
async function loadRealPlans() {
  if (!storeCtx.loaded) await storeCtx.loadStores()
  try {
    const store = storeCtx.currentStoreCode
    const [pend, appr] = await Promise.all([
      listPlans({ size: 50, status: 'PENDING_REVIEW', storeCode: store }),
      listPlans({ size: 50, status: 'APPROVED', storeCode: store }),
    ])
    const dtos: PlanViewDTO[] = [...pend.data.content, ...appr.data.content]
    // 清掉上一批真实影子单（mock seed 单无 REAL_FLAG，不受影响）
    consultation.consultations = consultation.consultations.filter((c) => !isRealPlan(c as any))
    dtos.forEach((d) => {
      const c = injectPlanShadows(d, { consultation: consultation as any, customer: customer as any })
      ;(c as any)[REAL_FLAG] = true
    })
  } catch (e: any) {
    toast.error('方案单队列加载失败：' + (e?.response?.data?.message || e?.message || '网络异常'))
  }
}

const reviewing = computed(() =>
  consultation.reviewing.filter((c) => isRealPlan(c as any)),
)
const approved = computed(() =>
  consultation.approved.filter((c) => isRealPlan(c as any)),
)
const readyPay = computed(() => consultation.readyPay)
const paid = computed(() => consultation.paid)
const treating = computed(() => consultation.treating)
const reviewQueue = computed(() => [...reviewing.value, ...approved.value])
// 治疗执行队列：仅 mock 演示单（排除真实影子单，治疗后端端点待补）
const treatQueue = computed(() =>
  consultation.treatmentQueue.filter((c) => !isRealPlan(c as any)),
)

const canSeeMargin = computed(() => auth.can('finance:margin:view'))

// 左侧分段
const listTab = ref<'review' | 'treat'>('review')
const selectedId = ref('')
const listData = computed(() => (listTab.value === 'review' ? reviewQueue.value : treatQueue.value))

function pill(status: ConsultStatus): { s: any; t: string } {
  const map: Record<ConsultStatus, { s: any; t: string }> = {
    PENDING: { s: 'default', t: '待咨询' },
    ACTIVE: { s: 'primary', t: '咨询中' },
    PENDING_REVIEW: { s: 'warning', t: '待审核' },
    APPROVED: { s: 'primary', t: '待写病历' },
    REJECTED: { s: 'danger', t: '已驳回' },
    READY_PAY: { s: 'warning', t: '待支付' },
    PAID: { s: 'success', t: '待治疗' },
    TREATING: { s: 'success', t: '治疗中' },
    DONE: { s: 'success', t: '已完成' },
    ABANDONED: { s: 'danger', t: '已作废' },
  }
  return map[status]
}

function fmtTime(iso?: string) {
  if (!iso) return '—'
  const d = new Date(iso)
  return `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

const REV_KIND_LABEL: Record<PlanRevision['kind'], string> = {
  SUBMIT: '提交审核',
  RESUBMIT: '重新提交',
  APPROVE: '审核通过',
  REJECT: '驳回 / 作废',
  DOCTOR_EDIT: '医生改单',
  EMR_SIGN: '病历签署',
  PAY: '收款',
  TREAT_START: '开始治疗',
  TREAT_DONE: '完成治疗',
}

function orderOf(c: { orderId?: string }) {
  return c.orderId ? order.get(c.orderId) : undefined
}
function positiveOf(c: { contraindications?: ConsultContraindication }) {
  if (!c.contraindications) return []
  const keys: { key: keyof ConsultContraindication; label: string }[] = [
    { key: 'pregnant', label: '妊娠/哺乳' },
    { key: 'allergy', label: '过敏史' },
    { key: 'scarConstitution', label: '瘢痕体质' },
    { key: 'skinLesion', label: '治疗区皮损' },
    { key: 'coagulationAbn', label: '凝血异常' },
    { key: 'seriousIllness', label: '严重基础病' },
  ]
  return keys.filter((f) => c.contraindications![f.key])
}

const sel = computed(() => consultation.get(selectedId.value))
const selCustomer = computed(() => (sel.value ? customer.get(sel.value.customerId) : undefined))

function selectConsult(id: string) {
  selectedId.value = id
  const c = consultation.get(id)
  if (!c) return
  listTab.value = ['PENDING_REVIEW', 'APPROVED'].includes(c.status) ? 'review' : 'treat'
  // 审核面板状态
  editMode.value = false
  showReject.value = false
  rejectReason.value = ''
  editReason.value = ''
  editConclusion.value = c.conclusion
  editItems.value = c.planItems ? JSON.parse(JSON.stringify(c.planItems)) : []
  emrChief.value = c.conclusion ? `${c.conclusion}，面诊确认方案。` : ''
  emrPresent.value = ''
  emrPast.value = c.contraindications?.note || ''
  // 诊断默认带入咨询结论、治疗方案默认带入项目明细，医生可在签署前直接补改（无需退回咨询）
  emrDiagnosis.value = c.conclusion || ''
  emrTreatment.value = c.planItems?.length
    ? c.planItems.map((i) => `${i.name}×${i.qty}（${i.spec}）`).join('；')
    : ''
  emrPrescription.value = '严格防晒保湿；术后如有持续红肿/渗液及时复诊。'
  // 术前核对
  preOp.value = { consentChecked: false, contraChecked: false, drugChecked: false, siteChecked: false, room: '', note: '' }
  // 治疗记录
  treatNote.value = c.planItems ? `按方案执行：${c.planItems.map((i) => `${i.name}×${i.qty}`).join('、')}，术中生命体征平稳，无异常反应。` : ''
  treatPrescription.value = '术后即刻冷敷/护理；严格防晒；一周内避免高温环境与辛辣；如有异常及时复诊。'
}

// ============================================================
// 审核 + 快捷写病历
// ============================================================
const editMode = ref(false)
const editConclusion = ref('')
const editItems = ref<PlanItem[]>([])
const editReason = ref('')
const rejectReason = ref('')
const showReject = ref(false)
const emrChief = ref('')
const emrPresent = ref('')
const emrPast = ref('')
const emrDiagnosis = ref('')
const emrTreatment = ref('')
const emrPrescription = ref('')

const reviewSkinReport = computed(() =>
  sel.value?.skinReportId
    ? customer.skinReportsOf(sel.value.customerId).find((r) => r.id === sel.value!.skinReportId)
    : undefined,
)
function photosOfConsult(custId: string, consultId?: string) {
  return customer.photosOf(custId).filter((p) => !consultId || p.consultId === consultId || p.category === 'before')
}

const emrDiagnosisMissing = computed(() => !emrDiagnosis.value.trim() || !emrTreatment.value.trim())

async function approveWithEmr() {
  if (!selectedId.value || !sel.value) return
  if (emrDiagnosisMissing.value) {
    toast.warning('请补全「诊断 / 皮肤评估」与「治疗方案 / 操作记录」后再签署（可直接在下方编辑，无需退回咨询）')
    return
  }
  // 真实方案单：走后端 sign-emr（自动审核通过 + 建首程病历 + 生成待收款缴费单，幂等）
  if (isRealPlan(sel.value as any)) {
    try {
      const res = await signPlanEmr(selectedId.value, {
        operator: auth.user.staffId,
        customerName: customer.nameOf(sel.value.customerId),
        chiefComplaint: emrChief.value,
        presentIllness: emrPresent.value,
        pastHistory: emrPast.value,
        diagnosis: emrDiagnosis.value,
        treatment: emrTreatment.value,
        prescription: emrPrescription.value,
      })
      toast.success(`已签署病历，缴费单 ${res.data.orderNo} 已生成，待收银台收款后解锁治疗`)
      selectedId.value = ''
      await loadRealPlans()
    } catch (e: any) {
      toast.error('签署病历失败：' + (e?.response?.data?.message || e?.message || '网络异常'))
    }
    return
  }
  const r = consultation.approveAndSignEmr(selectedId.value, {
    customerName: customer.nameOf(sel.value.customerId),
    chiefComplaint: emrChief.value,
    presentIllness: emrPresent.value,
    pastHistory: emrPast.value,
    diagnosis: emrDiagnosis.value,
    treatment: emrTreatment.value,
    prescription: emrPrescription.value,
  })
  if (!r.ok) {
    toast.error(r.error || '操作失败')
    return
  }
  toast.success(`已签署病历，缴费单 ${r.orderNo} 已生成，待收款后解锁治疗`)
  selectedId.value = ''
}
async function reject() {
  if (!selectedId.value) return
  if (!rejectReason.value.trim()) {
    toast.warning('请填写驳回原因')
    return
  }
  // 真实方案单：走后端 reject（须填原因，咨询师据此改单重提）
  if (sel.value && isRealPlan(sel.value as any)) {
    try {
      await rejectPlan(selectedId.value, auth.user.staffId, rejectReason.value.trim())
      toast.success('已驳回，咨询师可改单后重新提交')
      selectedId.value = ''
      await loadRealPlans()
    } catch (e: any) {
      toast.error('驳回失败：' + (e?.response?.data?.message || e?.message || '网络异常'))
    }
    return
  }
  const r = consultation.reject(selectedId.value, rejectReason.value)
  if (!r.ok) toast.error(r.error || '操作失败')
  else { toast.success('已驳回，咨询师可改单后重新提交'); selectedId.value = '' }
}
async function saveDoctorEdit() {
  if (!selectedId.value) return
  if (!editReason.value.trim()) {
    toast.warning('请填写改单说明（将留痕）')
    return
  }
  // 真实方案单：走后端 doctor-edit（改单即通过 → APPROVED，改单说明留痕）
  if (sel.value && isRealPlan(sel.value as any)) {
    try {
      await doctorEditPlan(selectedId.value, {
        operator: auth.user.staffId,
        conclusion: editConclusion.value.trim(),
        items: toPlanItemCmd(editItems.value),
        reason: editReason.value.trim(),
      })
      toast.success('改单已保存并留痕')
      await loadRealPlans()
    } catch (e: any) {
      toast.error('改单失败：' + (e?.response?.data?.message || e?.message || '网络异常'))
    }
    return
  }
  const r = consultation.doctorEdit(selectedId.value, {
    conclusion: editConclusion.value.trim(),
    planItems: editItems.value,
    reason: editReason.value,
  })
  if (!r.ok) toast.error(r.error || '操作失败')
  else toast.success('改单已保存并留痕')
}

// ============================================================
// 术前核对（PAID → TREATING）
// ============================================================
const preOp = ref<PreOpChecklist>({
  consentChecked: false, contraChecked: false, drugChecked: false, siteChecked: false, room: '', note: '',
})
const preOpAllChecked = computed(
  () => preOp.value.consentChecked && preOp.value.contraChecked && preOp.value.drugChecked && preOp.value.siteChecked,
)
const preOpPhotos = computed(() =>
  sel.value ? customer.photosOf(sel.value.customerId).filter((p) => p.category === 'before') : [],
)
const preOpBefore = computed(() => preOpPhotos.value[0]?.dataUrl)

function confirmStartTreatment() {
  if (!selectedId.value) return
  const r = consultation.startTreatment(selectedId.value, { ...preOp.value })
  if (!r.ok) {
    toast.error(r.error || '操作失败')
    return
  }
  toast.success('术前核对完成，已开始治疗')
  // 留在原单，面板会随状态切到治疗中
  selectConsult(selectedId.value)
}

// ============================================================
// 治疗记录（TREATING → DONE）
// ============================================================
const treatNote = ref('')
const treatPrescription = ref('')
function completeTreatment() {
  if (!selectedId.value || !sel.value) return
  const r = consultation.completeTreatment(selectedId.value, {
    customerName: customer.nameOf(sel.value.customerId),
    treatmentNote: treatNote.value,
    prescription: treatPrescription.value,
  })
  if (!r.ok) {
    toast.error(r.error || '操作失败')
    return
  }
  toast.success('治疗记录已归档，术后 SOP 多节点随访已自动生成')
  toast.info('可在「术后 SOP 编排」查看批次进度，或去随访工作台登记回访')
  selectedId.value = ''
}

function openCustomer360() {
  if (sel.value) router.push(`/customers/${sel.value.customerId}?t=360`)
}
function goCashier() {
  router.push('/order')
}
/** 查看该方案单关联的电子病历（/emr 据 fromConsult 定位已有病历或新建） */
function goEmr() {
  if (sel.value) router.push(`/emr?fromConsult=${sel.value.id}`)
}
</script>

<template>
  <CWorkbenchShell
    :has-selection="!!sel && !!selCustomer"
    empty-icon="shield"
    empty-title="从左侧选择一张方案单"
    empty-desc="待审核方案单可驳回 / 改单 / 审核通过；待治疗方案完成术前核对与治疗记录"
  >
    <template #kpis>
      <CKpi :value="String(reviewing.length)" label="待我审核方案" tone="orange" icon="shield" />
      <CKpi :value="String(approved.length)" label="待写病历" tone="brand" icon="edit" />
      <CKpi :value="String(readyPay.length)" label="待客户支付" tone="warning" icon="pos" />
      <CKpi :value="String(paid.length + treating.length)" label="待治疗 / 治疗中" tone="teal" icon="check" />
    </template>

    <!-- ============ 左侧列表 ============ -->
    <template #list>
        <div class="tabs">
          <button class="tab" :class="{ 'tab--active': listTab === 'review' }" @click="listTab = 'review'">
            开单审核 ({{ reviewQueue.length }})
          </button>
          <button class="tab" :class="{ 'tab--active': listTab === 'treat' }" @click="listTab = 'treat'">
            治疗执行 ({{ treatQueue.length }})
          </button>
        </div>

        <div class="dw__items">
          <div
            v-for="c in listData"
            :key="c.id"
            class="row"
            :class="{
              'row--active': selectedId === c.id,
              'row--approve': c.status === 'APPROVED',
              'row--pay': c.status === 'READY_PAY',
              'row--paid': c.status === 'PAID',
              'row--treating': c.status === 'TREATING',
            }"
            @click="selectConsult(c.id)"
          >
            <div class="row__top">
              <span class="row__title">{{ customer.nameOf(c.customerId) }}
                <span class="row__id">{{ c.customerId }}</span>
              </span>
              <CStatusPill :status="pill(c.status).s">{{ pill(c.status).t }}</CStatusPill>
            </div>
            <div class="row__meta">
              <span>{{ staffName(c.consultantId) }} 开单 → {{ staffName(c.doctorId) }}</span>
              <span>方案 ¥{{ c.planAmount ?? 0 }}<template v-if="canSeeMargin"> · 成本 ¥{{ c.planCost ?? 0 }}</template></span>
            </div>
            <div v-if="c.planItems?.length" class="row__items">
              <span v-for="(it, i) in c.planItems" :key="i" class="mini-tag">{{ it.name }}×{{ it.qty }}</span>
            </div>
            <div v-if="positiveOf(c).length" class="row__warn">
              面诊阳性：{{ positiveOf(c).map((f) => f.label).join('、') }}
            </div>
          </div>
          <div v-if="!listData.length" class="dw__empty">
            {{ listTab === 'review' ? '暂无待处理方案' : '暂无治疗任务' }}
          </div>
        </div>
    </template>

    <!-- ============ 右侧详情 ============ -->
    <template #head>
      <div v-if="sel && selCustomer" class="p360">
              <span class="p360__avatar">{{ customer.nameOf(sel.customerId)[0] }}</span>
              <div class="p360__meta">
                <div class="p360__name">
                  {{ customer.nameOf(sel.customerId) }}
                  <span class="p360__id">{{ sel.customerId }}</span>
                  <CStatusPill :status="pill(sel.status).s">{{ pill(sel.status).t }}</CStatusPill>
                </div>
                <div class="p360__sub">
                  咨询师 {{ staffName(sel.consultantId) }} 提交于 {{ fmtTime(sel.submittedAt) }}
                  <template v-if="sel.orderId"> · 缴费单 {{ orderOf(sel)?.orderNo }}</template>
                </div>
              </div>
              <CButton variant="secondary" size="sm" class="p360__btn" @click="openCustomer360">
                <CIcon name="customer" :size="14" />客户 360
              </CButton>
            </div>
    </template>

    <!-- 详情体（默认插槽，滚动） -->
    <template v-if="sel && selCustomer">
            <!-- ========== 审核 / 写病历面板 ========== -->
            <template v-if="sel.status === 'PENDING_REVIEW' || sel.status === 'APPROVED'">
              <!-- 面诊禁忌 -->
              <section class="blk">
                <h4 class="blk__title">面诊禁忌初筛</h4>
                <div v-if="positiveOf(sel).length" class="issue issue--warn">
                  阳性项：{{ positiveOf(sel).map((f) => f.label).join('、') }}
                </div>
                <div v-else class="issue issue--ok">面诊禁忌全部阴性</div>
                <div v-if="sel.contraindications?.note" class="contra-note">
                  咨询师备注：{{ sel.contraindications.note }}
                </div>
              </section>

              <!-- 面诊报告 -->
              <section v-if="reviewSkinReport" class="blk">
                <h4 class="blk__title"><CIcon name="scan" :size="15" />面诊 / 皮肤检测报告
                  <span class="blk__hint">{{ reviewSkinReport.device }} · {{ reviewSkinReport.skinType }}</span>
                </h4>
                <div class="skin-metrics skin-metrics--mini">
                  <span v-for="m in reviewSkinReport.metrics" :key="m.key" class="skin-chip">
                    {{ m.label }}<i :class="{ 'is-high': m.score >= 60 }">{{ m.score }}</i>
                  </span>
                </div>
                <div v-if="reviewSkinReport.chiefComplaint" class="contra-note">主诉：{{ reviewSkinReport.chiefComplaint }}</div>
                <div v-if="reviewSkinReport.analysis" class="contra-note">{{ reviewSkinReport.analysis }}</div>
              </section>

              <!-- 档案照 -->
              <section v-if="photosOfConsult(sel.customerId, sel.id).length" class="blk">
                <h4 class="blk__title"><CIcon name="beauty" :size="15" />面诊档案照 <span class="blk__hint">已脱敏加水印</span></h4>
                <div class="dr-photos">
                  <div v-for="p in photosOfConsult(sel.customerId, sel.id)" :key="p.id" class="dr-photo">
                    <img :src="p.dataUrl" :alt="p.part" />
                    <span>{{ p.part }}</span>
                  </div>
                </div>
              </section>

              <!-- 方案内容 -->
              <section class="blk">
                <h4 class="blk__title">方案内容</h4>
                <CTextarea v-if="editMode" v-model="editConclusion" label="咨询结论 / 方案说明" :rows="2" />
                <div v-else class="readonly-box">{{ sel.conclusion || '（无）' }}</div>

                <div class="items">
                  <div v-for="(it, idx) in (editMode ? editItems : sel.planItems)" :key="idx" class="item-row">
                    <div class="item-row__head">
                      <span class="item-row__name">{{ it.name }}</span>
                      <button v-if="editMode" class="item-row__del" @click="editItems.splice(idx, 1)">删除</button>
                    </div>
                    <div class="item-row__tags">
                      <span v-for="t in it.riskTags || []" :key="t" class="risk-tag">{{ RISK_TAG_LABEL[t] }}</span>
                    </div>
                    <div v-if="editMode" class="item-row__edit">
                      <label>数量
                        <input v-model.number="it.qty" type="number" min="1" class="mini-input" />
                      </label>
                      <label>单价 ¥
                        <input v-model.number="it.price" type="number" min="0" class="mini-input" />
                      </label>
                      <span class="item-row__sum">小计 ¥{{ it.qty * it.price }}</span>
                    </div>
                    <div v-else class="item-row__line">
                      {{ it.spec }} · ×{{ it.qty }} · ¥{{ it.price }} · 小计 ¥{{ it.qty * it.price }}
                    </div>
                  </div>
                </div>
                <div class="total-row">
                  方案合计 <strong>¥{{ editMode ? editItems.reduce((s, i) => s + i.qty * i.price, 0) : sel.planAmount ?? 0 }}</strong>
                </div>
              </section>

              <!-- 知情同意 + 电子签 -->
              <section class="blk">
                <h4 class="blk__title"><CIcon name="sign" :size="15" />知情同意书 · 电子签
                  <span class="blk__hint">{{ sel.consentDocVersion || 'MEIYUN-ICF-v2026.1' }}</span>
                </h4>
                <div class="consent-state">
                  <span :class="{ ok: sel.consentConsultant }">咨询师风险告知 {{ sel.consentConsultant ? '✓' : '✗' }}</span>
                  <span :class="{ ok: sel.consentCustomer && sel.consentSignatureDataUrl }">
                    客户手写签名 {{ sel.consentSignatureDataUrl ? '✓' : '✗ 缺失' }}
                  </span>
                </div>
                <div v-if="sel.consentSignatureDataUrl" class="sig-card">
                  <img :src="sel.consentSignatureDataUrl" alt="客户电子签名" class="sig-card__img" />
                  <div class="sig-card__meta">
                    签署人：<strong>{{ sel.consentSignerName || '客户本人' }}</strong>
                    · 签署时间 {{ fmtTime(sel.consentAt) }}
                  </div>
                </div>
                <div v-else class="issue issue--block">未检测到客户手写电子签名，不可进入病历与缴费环节，请退回咨询环节补签。</div>
              </section>

              <!-- 快捷写病历 -->
              <section class="blk emr-blk">
                <h4 class="blk__title"><CIcon name="edit" :size="15" />快捷写首程病历
                  <span class="blk__hint">诊断、治疗方案已按咨询结论/项目明细自动带入，可直接补改；签署后自动生成缴费单</span>
                </h4>
                <div class="emr-form">
                  <div class="emr-field emr-field--key">
                    <CTextarea v-model="emrDiagnosis" label="诊断 / 皮肤评估（必填）" :rows="2"
                      placeholder="如：面部光老化、毛孔粗大（Fitzpatrick III 型）；留空则取咨询结论" />
                  </div>
                  <div class="emr-field emr-field--key">
                    <CTextarea v-model="emrTreatment" label="治疗方案 / 操作记录（必填）" :rows="2"
                      placeholder="如：IPL 光子嫩肤全脸，能量 16-18J/cm²，术后即刻冷敷 20 分钟" />
                  </div>
                  <div class="emr-field">
                    <CTextarea v-model="emrChief" label="主诉" :rows="2" placeholder="客户主诉与面诊确认（留空则按方案结论带入）" />
                  </div>
                  <div class="emr-field">
                    <CTextarea v-model="emrPresent" label="现病史" :rows="2" placeholder="现病史 / 皮肤评估（选填）" />
                  </div>
                  <div class="emr-field">
                    <CTextarea v-model="emrPast" label="既往史 / 过敏史" :rows="2" placeholder="既往史、过敏史、禁忌处置（已带入面诊备注，可补充）" />
                  </div>
                  <div class="emr-field">
                    <CTextarea v-model="emrPrescription" label="术后医嘱" :rows="2" placeholder="术后注意事项与复诊要求" />
                  </div>
                </div>
                <div v-if="emrDiagnosisMissing" class="issue issue--warn">
                  诊断与治疗方案为病历签署必填项，请补全后再签署（咨询结论为空时可在此直接填写，无需退回咨询环节）。
                </div>
              </section>

              <!-- 改单说明 / 驳回原因 -->
              <section v-if="editMode" class="blk">
                <CTextarea v-model="editReason" label="改单说明（必填）" placeholder="说明调整了哪些项目/价格及医学依据" :rows="2" />
              </section>
              <section v-if="showReject" class="blk">
                <CTextarea v-model="rejectReason" label="驳回原因（必填）" placeholder="如：治疗区存在活动性炎症，建议先抗炎 2 周后再评估" :rows="2" />
              </section>

              <!-- 留痕时间线 -->
              <section class="blk">
                <h4 class="blk__title">审核 / 病历 / 收款 留痕</h4>
                <div class="timeline">
                  <div v-for="rev in [...(sel.revisions || [])].reverse()" :key="rev.id" class="tl-item">
                    <span class="tl-dot" :class="`tl-dot--${rev.kind.toLowerCase()}`"></span>
                    <div class="tl-body">
                      <div class="tl-head">
                        <strong>{{ rev.actorName }}</strong> · {{ REV_KIND_LABEL[rev.kind] }}
                        <span class="tl-time">{{ fmtTime(rev.at) }}</span>
                      </div>
                      <div v-if="rev.reason" class="tl-text">{{ rev.reason }}</div>
                      <div v-for="(ch, i) in rev.changes || []" :key="i" class="tl-change">
                        <span class="tl-change__label">{{ ch.label }}：</span>
                        <span class="tl-change__from">{{ ch.from }}</span>
                        → <span class="tl-change__to">{{ ch.to }}</span>
                      </div>
                    </div>
                  </div>
                </div>
              </section>
            </template>

            <!-- ========== 待支付面板 ========== -->
            <template v-else-if="sel.status === 'READY_PAY'">
              <section class="blk">
                <div class="pay-panel">
                  <CIcon name="pos" :size="28" class="pay-panel__ic" />
                  <div class="pay-panel__main">
                    <h4>病历已签 · 缴费单待支付</h4>
                    <p>缴费单 <strong>{{ orderOf(sel)?.orderNo || '—' }}</strong> · 金额 <strong>¥{{ sel.planAmount ?? 0 }}</strong> 已推送收银台与客户小程序。</p>
                    <p class="pay-panel__tip">收款完成后<strong>自动转入待治疗</strong>，未支付不可排治疗。可在收银台核单收款。</p>
                  </div>
                  <CButton variant="primary" size="sm" @click="goCashier">前往收银台收款 →</CButton>
                </div>
              </section>
              <section class="blk">
                <h4 class="blk__title">方案项目</h4>
                <div class="items">
                  <div v-for="(it, idx) in sel.planItems" :key="idx" class="item-row">
                    <div class="item-row__head"><span class="item-row__name">{{ it.name }}</span></div>
                    <div class="item-row__line">{{ it.spec }} · ×{{ it.qty }} · ¥{{ it.price }} · 小计 ¥{{ it.qty * it.price }}</div>
                  </div>
                </div>
              </section>
            </template>

            <!-- ========== 术前核对面板 ========== -->
            <template v-else-if="sel.status === 'PAID'">
              <section class="blk">
                <h4 class="blk__title">治疗方案</h4>
                <div class="readonly-box">
                  {{ sel.planItems?.map((i) => `${i.name}×${i.qty}`).join('、') }} · ¥{{ sel.planAmount ?? 0 }}
                  · 收款 {{ fmtTime(sel.paidAt) }}
                </div>
              </section>

              <section class="blk">
                <h4 class="blk__title"><CIcon name="sign" :size="15" />知情同意签名核对</h4>
                <div v-if="sel.consentSignatureDataUrl" class="sig-card sig-card--mini">
                  <img :src="sel.consentSignatureDataUrl" alt="客户电子签名" class="sig-card__img" />
                  <div class="sig-card__meta">签署人：<strong>{{ sel.consentSignerName || '客户本人' }}</strong> · {{ fmtTime(sel.consentAt) }}</div>
                </div>
                <div v-else class="issue issue--block">未查到客户手写电子签名，不可开始治疗，请先补签知情同意书。</div>
              </section>

              <section v-if="preOpPhotos.length" class="blk">
                <h4 class="blk__title"><CIcon name="beauty" :size="15" />术前档案照核对</h4>
                <CPhotoCompare :before="preOpBefore" after-label="术后复查（治疗后拍摄）" />
              </section>

              <section class="blk consent">
                <CCheckbox :model-value="preOp.consentChecked" @update:modelValue="preOp.consentChecked = $event">
                  知情同意书已签署并归档（已核对上方客户本人签名）
                </CCheckbox>
                <CCheckbox :model-value="preOp.contraChecked" @update:modelValue="preOp.contraChecked = $event">
                  禁忌 / 过敏史已当面复核，无新增禁忌
                </CCheckbox>
                <CCheckbox :model-value="preOp.drugChecked" @update:modelValue="preOp.drugChecked = $event">
                  药品 / 耗材批号、有效期已核对（扫码溯源）
                </CCheckbox>
                <CCheckbox :model-value="preOp.siteChecked" @update:modelValue="preOp.siteChecked = $event">
                  治疗部位、项目与能量参数已与客户当面确认
                </CCheckbox>
              </section>
              <section class="blk">
                <CInput v-model="preOp.room" label="治疗室 / 操作医生" placeholder="如：治疗室 2 · 顾屿" />
                <CTextarea v-model="preOp.note" label="核对备注（选填）" :rows="2" placeholder="术前拍照、体表标记等" class="blk__note" />
              </section>
              <div v-if="!preOpAllChecked" class="issue issue--warn">请逐项完成术前四项核对，全部确认后方可开始治疗。</div>
            </template>

            <!-- ========== 治疗记录面板 ========== -->
            <template v-else-if="sel.status === 'TREATING'">
              <section class="blk">
                <div class="readonly-box">
                  {{ sel.preOp?.room || '治疗室' }} · 开始于 {{ fmtTime(sel.treatingAt) }}
                  · {{ sel.planItems?.map((i) => `${i.name}×${i.qty}`).join('、') }}
                </div>
              </section>
              <section class="blk">
                <CTextarea v-model="treatNote" label="治疗过程 / 操作记录" :rows="4" placeholder="治疗项目、能量参数、术中反应、生命体征等" />
              </section>
              <section class="blk">
                <CTextarea v-model="treatPrescription" label="术后医嘱 / 注意事项" :rows="3" placeholder="术后护理、防晒、复诊要求" />
              </section>
              <div class="issue issue--ok">
                提交后治疗记录将电子签名归档，并按术后 SOP <strong>自动生成多节点随访计划</strong>（24h 关怀 / 第3天回访 / 第7天恢复 / 第30天复诊）。
              </div>
            </template>

            <!-- ========== 已完成面板（终态） ========== -->
            <template v-else-if="sel.status === 'DONE'">
              <section class="blk">
                <div class="pay-panel pay-panel--done">
                  <CIcon name="check" :size="28" class="pay-panel__ic" />
                  <div class="pay-panel__main">
                    <h4>治疗已完成并归档</h4>
                    <p>治疗记录已电子签名归档，术后 SOP 多节点随访计划已自动生成。</p>
                    <p class="pay-panel__tip">下一步：按 SOP 跟进术后回访与复诊，复诊可转新方案单。</p>
                  </div>
                  <CButton variant="secondary" size="sm" @click="router.push('/sop')">查看 SOP 进度 →</CButton>
                </div>
              </section>
            </template>
    </template>

    <!-- 底部操作条 -->
    <template #foot>
      <template v-if="sel && selCustomer">
            <!-- 待审核 -->
            <template v-if="sel.status === 'PENDING_REVIEW'">
              <template v-if="!showReject && !editMode">
                <CButton variant="ghost" @click="showReject = true">驳回</CButton>
                <CButton variant="secondary" v-perm.disable="'consult:review'" @click="editMode = true">改单</CButton>
                <CButton variant="primary" v-perm.disable="'consult:review'" @click="approveWithEmr">审核通过</CButton>
              </template>
              <template v-else-if="showReject">
                <CButton variant="ghost" @click="showReject = false">取消</CButton>
                <CButton variant="danger" v-perm.disable="'consult:review'" @click="reject">确认驳回</CButton>
              </template>
              <template v-else>
                <CButton variant="ghost" @click="editMode = false">取消</CButton>
                <CButton variant="primary" v-perm.disable="'consult:review'" @click="saveDoctorEdit">保存改单</CButton>
              </template>
            </template>
            <!-- 待写病历（沿用主线：签署病历后自动生成缴费单） -->
            <template v-else-if="sel.status === 'APPROVED'">
              <CButton variant="ghost" @click="selectedId = ''">关闭</CButton>
              <CButton variant="primary" v-perm.disable="'consult:review'" @click="approveWithEmr">签署病历</CButton>
            </template>
            <!-- 待支付：无操作，提示去收银台 -->
            <template v-else-if="sel.status === 'READY_PAY'">
              <CButton variant="ghost" @click="selectedId = ''">关闭</CButton>
              <CButton variant="secondary" v-perm.disable="'emr:view'" @click="goEmr">
                <CIcon name="profile" :size="14" />查看电子病历
              </CButton>
              <CButton variant="primary" @click="goCashier">前往收银台</CButton>
            </template>
            <!-- 待治疗：术前核对 -->
            <template v-else-if="sel.status === 'PAID'">
              <CButton variant="ghost" @click="selectedId = ''">取消</CButton>
              <CButton variant="secondary" v-perm.disable="'emr:view'" @click="goEmr">
                <CIcon name="profile" :size="14" />查看电子病历
              </CButton>
              <CButton variant="primary" :disabled="!preOpAllChecked" v-perm.disable="'consult:review'" @click="confirmStartTreatment">
                开始治疗
              </CButton>
            </template>
            <!-- 治疗中：完成归档 -->
            <template v-else-if="sel.status === 'TREATING'">
              <CButton variant="ghost" @click="selectedId = ''">取消</CButton>
              <CButton variant="secondary" v-perm.disable="'emr:view'" @click="goEmr">
                <CIcon name="profile" :size="14" />查看电子病历
              </CButton>
              <CButton variant="primary" :disabled="!treatNote.trim()" v-perm.disable="'consult:review'" @click="completeTreatment">
                完成治疗
              </CButton>
            </template>
            <!-- 已完成（终态）：下一步出口，避免流程断头 -->
            <template v-else-if="sel.status === 'DONE'">
              <CButton variant="ghost" @click="selectedId = ''">关闭</CButton>
              <CButton variant="secondary" @click="router.push('/sop')">
                <CIcon name="layers" :size="14" />术后 SOP 进度
              </CButton>
              <CButton variant="secondary" v-perm.disable="'emr:view'" @click="goEmr">
                <CIcon name="profile" :size="14" />查看电子病历
              </CButton>
              <CButton variant="primary" @click="openCustomer360">
                <CIcon name="customer" :size="14" />客户 360 · 跟进复诊
              </CButton>
            </template>
      </template>
    </template>
  </CWorkbenchShell>
</template>

<style scoped>
/* 外壳网格 / KPI / 详情 head·body·foot 已收敛至 CWorkbenchShell（wbs__） */

/* 左侧 tab —— 与核销台 WriteoffView / 退款页 RefundView 统一（文字下划线 tab，数量内联） */
.tabs { display: flex; border-bottom: 1px solid var(--c-border); flex-shrink: 0; }
.tab {
  flex: 1; padding: var(--s-md) var(--s-sm); font-size: var(--t-sm);
  color: var(--c-text-3); background: none; border: none; cursor: pointer;
  border-bottom: 2px solid transparent; transition: all .15s;
}
.tab--active { color: var(--c-brand); border-bottom-color: var(--c-brand); font-weight: 600; }

.dw__items { flex: 1; overflow-y: auto; }
.row {
  display: block; width: 100%; text-align: left; padding: var(--s-md) var(--s-lg);
  background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer;
}
.row:hover { background: var(--c-brand-soft); }
.row--active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.row--approve .row__title { color: var(--c-brand); }
.row--pay .row__title { color: var(--c-warning-fg); }
.row--treating .row__title { color: var(--c-success-fg, #389e0d); }
.row__top { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--s-xs); }
.row__title { font-size: var(--t-sm); color: var(--c-text); font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; display: flex; align-items: center; gap: var(--s-xs); flex-wrap: wrap; }
.row__id { font-size: var(--t-xs); color: var(--c-text-3); font-weight: 400; }
.row__meta { display: flex; flex-wrap: wrap; gap: var(--s-sm); font-size: var(--t-xs); color: var(--c-text-3); align-items: center; }
.row__items { display: flex; flex-wrap: wrap; gap: 4px; margin-top: var(--s-xs); }
.row__warn { margin-top: var(--s-xs); font-size: var(--t-xs); color: var(--c-warning-fg, #d46b08); line-height: 1.5; }
.mini-tag { font-size: var(--t-xs); color: var(--c-brand); background: var(--c-brand-soft); border-radius: var(--r-sm); padding: 2px 6px; }
.dw__empty { color: var(--c-text-3); font-size: var(--t-sm); text-align: center; padding: var(--s-xl) var(--s-md); }

/* 详情 head/body/foot 外壳样式由 CWorkbenchShell 提供 */

.p360 { display: flex; gap: var(--s-sm); padding: var(--s-md); border-radius: var(--r-lg); background: var(--c-bg-page, #f7f8fa); align-items: flex-start; }
.p360__avatar { width: 44px; height: 44px; border-radius: var(--r-avatar); background: var(--c-brand-soft); color: var(--c-brand); font-weight: 700; font-size: var(--t-lg); display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.p360__meta { flex: 1; min-width: 0; }
.p360__name { font-weight: 700; font-size: var(--t-base); display: flex; align-items: center; gap: var(--s-xs); flex-wrap: wrap; }
.p360__id { font-size: var(--t-xs); color: var(--c-text-3); font-weight: 400; }
.p360__sub { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 3px; line-height: 1.5; }
.p360__btn { flex-shrink: 0; }

.blk { margin-bottom: var(--s-md); }
.blk__title { font-size: var(--t-sm); font-weight: 700; color: var(--c-text); margin: 0 0 var(--s-sm); display: flex; align-items: center; gap: var(--s-xs); flex-wrap: wrap; }
.blk__hint { font-size: var(--t-xs); font-weight: 400; color: var(--c-text-3); }
.blk__note { margin-top: var(--s-sm); }
/* 快捷写病历：白底卡片 + 字段分组，关键字段（诊断/治疗方案）高亮 */
.emr-blk { border: 1px solid var(--c-border-light); border-radius: var(--r-lg); padding: var(--s-lg); background: var(--c-surface); box-shadow: var(--shadow-sm, 0 1px 4px rgba(20,21,43,.04)); }
.emr-form { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md) var(--s-lg); margin-top: var(--s-sm); }
.emr-field { min-width: 0; padding: var(--s-md); border: 1px solid var(--c-border-light); border-radius: var(--r-md); background: var(--c-bg-page, #f7f8fa); }
.emr-field :deep(.ctextarea__field) { background: var(--c-surface); border: 1px solid var(--c-border-light); }
.emr-field--key { grid-column: 1 / -1; border-color: var(--c-brand); background: var(--c-brand-soft); }
.emr-field--key :deep(.ctextarea__label) { color: var(--c-brand); font-weight: 700; }
.emr-field--key :deep(.ctextarea__field) { background: var(--c-surface); border: 1px solid var(--c-brand); }
@media (max-width: 720px) { .emr-form { grid-template-columns: 1fr; } }

.risk-tag { font-size: 11px; color: var(--c-warning-fg, #d46b08); background: var(--c-warning-soft, #fff7e6); border-radius: var(--r-sm); padding: 1px 6px; white-space: nowrap; }
.items { display: flex; flex-direction: column; gap: var(--s-sm); margin-top: var(--s-sm); }
.item-row { border: 1px solid var(--c-border-light); border-radius: var(--r-md); padding: var(--s-sm); }
.item-row__head { display: flex; align-items: center; justify-content: space-between; gap: var(--s-sm); }
.item-row__name { font-weight: 600; font-size: var(--t-sm); }
.item-row__del { border: none; background: none; color: var(--c-danger-fg, #cf1322); font-size: var(--t-xs); cursor: pointer; padding: 0; }
.item-row__tags { display: flex; gap: 4px; margin-top: 4px; }
.item-row__edit { display: flex; align-items: center; gap: var(--s-md); margin-top: var(--s-sm); font-size: var(--t-xs); color: var(--c-text-3); }
.item-row__line { font-size: var(--t-xs); color: var(--c-text-2); margin-top: 6px; }
.item-row__sum { margin-left: auto; font-weight: 700; color: var(--c-text); }
.mini-input { width: 64px; padding: 4px 8px; border: 1px solid var(--c-border-light); border-radius: var(--r-sm); font-size: var(--t-sm); margin-left: 4px; }
.total-row { display: flex; align-items: center; gap: var(--s-sm); justify-content: flex-end; margin-top: var(--s-md); padding-top: var(--s-sm); border-top: 1px dashed var(--c-border); font-size: var(--t-sm); color: var(--c-text-2); }
.total-row strong { font-size: var(--t-lg); color: var(--c-brand); }

.issue { border-radius: var(--r-md); padding: 8px 12px; font-size: var(--t-xs); line-height: 1.6; margin-top: var(--s-sm); }
.issue--block { background: var(--c-danger-soft, #fff1f0); color: var(--c-danger-fg, #cf1322); border: 1px solid var(--c-danger-border, #ffccc7); }
.issue--warn { background: var(--c-warning-soft, #fff7e6); color: var(--c-warning-fg, #d46b08); border: 1px solid var(--c-warning-border, #ffd591); }
.issue--ok { background: var(--c-success-soft, #f6ffed); color: var(--c-success-fg, #389e0d); border: 1px solid var(--c-success-border, #b7eb8f); }

.readonly-box { background: var(--c-bg-page, #f7f8fa); border-radius: var(--r-md); padding: var(--s-sm); font-size: var(--t-sm); color: var(--c-text); line-height: 1.6; }
.contra-note { font-size: var(--t-xs); color: var(--c-text-2); margin-top: 6px; line-height: 1.5; }
.consent { display: flex; flex-direction: column; gap: var(--s-sm); padding: var(--s-md); border-radius: var(--r-md); background: var(--c-bg-page, #f7f8fa); }
.consent-state { display: flex; gap: var(--s-md); font-size: var(--t-sm); color: var(--c-danger-fg, #cf1322); flex-wrap: wrap; }
.consent-state .ok { color: var(--c-success-fg, #389e0d); }

.sig-card { margin-top: var(--s-sm); border: 1px solid var(--c-border-light); border-radius: var(--r-md); background: var(--c-surface); padding: var(--s-sm); }
.sig-card--mini { margin-top: 0; }
.sig-card__img { width: 100%; max-height: 110px; object-fit: contain; background: var(--c-surface); display: block; }
.sig-card__meta { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 4px; }
.sig-card__meta strong { color: var(--c-text-2); }

.skin-metrics--mini { display: flex; flex-wrap: wrap; gap: 6px; }
.skin-chip { font-size: 11px; color: var(--c-text-2); background: var(--c-bg-page, #f7f8fa); border: 1px solid var(--c-border-light); border-radius: var(--r-sm); padding: 2px 8px; display: inline-flex; align-items: center; gap: 4px; }
.skin-chip i { font-style: normal; font-weight: 700; color: var(--c-teal-fg); }
.skin-chip i.is-high { color: var(--c-warning-fg, #d46b08); }

.dr-photos { display: grid; grid-template-columns: repeat(3, 1fr); gap: 6px; }
.dr-photo { position: relative; aspect-ratio: 3/4; border-radius: var(--r-sm); overflow: hidden; border: 1px solid var(--c-border-light); }
.dr-photo img { width: 100%; height: 100%; object-fit: cover; }
.dr-photo span { position: absolute; left: 4px; bottom: 4px; font-size: 10px; color: #fff; background: rgba(20,21,43,.6); border-radius: 4px; padding: 0 5px; }

/* 待支付面板 */
.pay-panel { display: flex; align-items: center; gap: var(--s-md); padding: var(--s-lg); border: 1px solid var(--c-warning-border, #ffd591); background: var(--c-warning-soft, #fff7e6); border-radius: var(--r-lg); }
.pay-panel__ic { color: var(--c-warning-fg, #d46b08); flex-shrink: 0; }
.pay-panel__main { flex: 1; min-width: 0; }
.pay-panel__main h4 { margin: 0 0 6px; font-size: var(--t-md); color: var(--c-text); }
.pay-panel__main p { margin: 2px 0; font-size: var(--t-sm); color: var(--c-text-2); line-height: 1.6; }
.pay-panel__tip { color: var(--c-warning-fg, #d46b08); }

.timeline { display: flex; flex-direction: column; gap: var(--s-sm); }
.tl-item { display: flex; gap: var(--s-sm); }
.tl-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--c-text-4); margin-top: 5px; flex-shrink: 0; }
.tl-dot--approve, .tl-dot--doctor_edit, .tl-dot--emr_sign { background: var(--c-success-fg, #389e0d); }
.tl-dot--reject { background: var(--c-danger-fg, #cf1322); }
.tl-dot--submit, .tl-dot--resubmit { background: var(--c-brand); }
.tl-dot--pay { background: var(--c-warning-fg, #d46b08); }
.tl-dot--treat_start, .tl-dot--treat_done { background: var(--c-teal, #13c2c2); }
.tl-body { flex: 1; min-width: 0; }
.tl-head { font-size: var(--t-xs); color: var(--c-text-2); }
.tl-time { color: var(--c-text-3); margin-left: 6px; }
.tl-text { font-size: var(--t-xs); color: var(--c-text-2); margin-top: 2px; line-height: 1.5; }
.tl-change { font-size: var(--t-xs); margin-top: 4px; line-height: 1.5; color: var(--c-text-2); background: var(--c-bg-page, #f7f8fa); border-radius: var(--r-sm); padding: 6px 8px; }
.tl-change__from { color: var(--c-danger-fg, #cf1322); text-decoration: line-through; }
.tl-change__to { color: var(--c-success-fg, #389e0d); font-weight: 600; }

/* 终态面板：完成态绿色 */
.pay-panel--done { background: var(--c-success-soft, #f6ffed); border-color: var(--c-success-border, #b7eb8f); }
</style>
