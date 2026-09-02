<script setup lang="ts">
/* ============================================================
 * M4-09 咨询工作台（/consultation）· 咨询师视角
 * 左列表 + 右详情布局：
 *   左侧分段【待咨询（含咨询中草稿）/ 已驳回 / 已提交】：
 *     待咨询 = PENDING 待咨询 + ACTIVE 咨询中草稿；点「开始咨询」状态转咨询中但不移出列表，
 *     提交审核后才流转（修复"一开始咨询列表就消失、取消后找不到"的问题）。
 *     已提交 = 今日已提交审核、进入审核/缴费/治疗链路的单，点开为只读回看（方案摘要+进度步骤+下一步出口）。
 *   客户信息 + 快捷开单（面诊禁忌 / AI 语音面诊 / 结构化面诊报告 /
 *   项目检索算价 / 本单提成预估 / 敏感词合规引擎 / 知情同意电子签）。
 *   已驳回单可查看医生驳回原因，改单后重新提交。
 * 提交后方案流转「医师工作台」(/doctor) 完成 审核→写病历→缴费→治疗。
 * ============================================================ */
import { computed, onMounted, ref, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useConsultationStore } from '@/stores/consultation'
import { usePricelistStore } from '@/stores/pricelist'
import { useCustomerStore } from '@/stores/customer'
import { useAppointmentStore } from '@/stores/appointment'
import { useAuthStore } from '@/stores/auth'
import { useFinCommissionStore } from '@/stores/finCommission'
import { useStoreContext } from '@/stores/storeContext'
import { useCompliance, RISK_TAG_LABEL } from '@/composables/useCompliance'
import { useToast } from '@/composables/useToast'
import { listPlans, type PlanViewDTO } from '@/api/consultPlan'
import { injectPlanShadows, isRealPlan, REAL_FLAG } from '@/adapters/consultPlan'
import { staffName, DOCTORS } from '@/config/staff'
import CWorkbenchShell from '@/components/CWorkbenchShell.vue'
import CButton from '@/components/CButton.vue'
import CIcon from '@/components/CIcon.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CInput from '@/components/CInput.vue'
import CTextarea from '@/components/CTextarea.vue'
import CSelect from '@/components/CSelect.vue'
import CCheckbox from '@/components/CCheckbox.vue'
import CChoiceChip from '@/components/CChoiceChip.vue'
import CKpi from '@/components/CKpi.vue'
import CSignaturePad from '@/components/CSignaturePad.vue'
import CPhotoUpload, { type UploadedPhoto } from '@/components/CPhotoUpload.vue'
import type { ConsultStatus, ConsultContraindication, PlanItem, SkinMetric } from '@/types/domain'

const router = useRouter()
const consultation = useConsultationStore()
const pricelist = usePricelistStore()
const customer = useCustomerStore()
const appointment = useAppointmentStore()
const auth = useAuthStore()
const finCommission = useFinCommissionStore()
const compliance = useCompliance()
const toast = useToast()
const storeCtx = useStoreContext()

onMounted(async () => {
  appointment.seed()
  pricelist.seed()
  customer.seedProfile()
  consultation.seed()
  finCommission.seed()
  await loadRealPlans()
})

// ============================================================
// 真实方案单加载（已驳回 / 已提交只读回看）：
//   咨询师「待咨询/咨询中」接诊草稿段后端暂无方案单草稿/接诊端点，保持 mock 演示；
//   已驳回（REJECTED）与已提交（PENDING_REVIEW→DONE 履约链路）接真实数据，注入 mock store 影子记录，
//   详情只读回看（真实驳回原因/进度时间线）。真实单不可在本页编辑重提（后端改单重提由医师工作台/后续端点承接）。
// ============================================================
async function loadRealPlans() {
  if (!storeCtx.loaded) await storeCtx.loadStores()
  try {
    const store = storeCtx.currentStoreCode
    // 一次拉足各状态真实单（分页 size 取大）
    const [rej, flow] = await Promise.all([
      listPlans({ size: 50, status: 'REJECTED', storeCode: store }),
      listPlans({ size: 100, storeCode: store }),
    ])
    // 履约链路状态（已提交回看）：待审核→完成
    const FLOW = ['PENDING_REVIEW', 'APPROVED', 'READY_PAY', 'PAID', 'TREATING', 'DONE']
    const flowDtos = flow.data.content.filter((d) => FLOW.includes(d.status))
    const dtos: PlanViewDTO[] = [...rej.data.content, ...flowDtos]
    // 清上一批真实影子单（mock seed 无 REAL_FLAG，不受影响）
    consultation.consultations = consultation.consultations.filter((c) => !isRealPlan(c as any))
    dtos.forEach((d) => {
      const c = injectPlanShadows(d, { consultation: consultation as any, customer: customer as any })
      ;(c as any)[REAL_FLAG] = true
    })
  } catch (e: any) {
    toast.error('方案单队列加载失败：' + (e?.response?.data?.message || e?.message || '网络异常'))
  }
}

const queue = computed(() =>
  consultation.queue.filter((c) => !isRealPlan(c as any)),
)
const rejected = computed(() =>
  consultation.active.filter((c) => c.status === 'REJECTED' && isRealPlan(c as any)),
)
const reviewing = computed(() => consultation.reviewing)
const submittedList = computed(() =>
  consultation.submitted.filter((c) => isRealPlan(c as any)),
)

const canSeePhone = computed(() => auth.can('customer:phone:decrypt'))
const canSeeMargin = computed(() => auth.can('finance:margin:view'))
const canConsult = computed(() => auth.can('consult:edit'))
const canReview = computed(() => auth.can('consult:review'))

// 左侧分段：待咨询（含咨询中草稿）/ 已驳回 / 已提交（今日已提交，只读回看）
const listTab = ref<'queue' | 'rejected' | 'submitted'>('queue')
const selectedId = ref('')
const listData = computed(() =>
  listTab.value === 'queue' ? queue.value : listTab.value === 'rejected' ? rejected.value : submittedList.value,
)
// 已提交进入审核/履约链路的单为只读回看（不可再编辑方案）
const READONLY_STATUSES: ConsultStatus[] = ['PENDING_REVIEW', 'APPROVED', 'READY_PAY', 'PAID', 'TREATING', 'DONE']

function pill(status: ConsultStatus): { s: any; t: string } {
  const map: Record<ConsultStatus, { s: any; t: string }> = {
    PENDING: { s: 'default', t: '待咨询' },
    ACTIVE: { s: 'primary', t: '咨询中' },
    PENDING_REVIEW: { s: 'warning', t: '待审核' },
    APPROVED: { s: 'success', t: '待写病历' },
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

// ============================================================
// 右侧详情：快捷开单表单
// ============================================================
const planConclusion = ref('')
const planItems = ref<PlanItem[]>([])
const planDoctorId = ref('')
const planConsentC = ref(false)
const planConsentCustomer = ref(false)
const contra = ref<ConsultContraindication>({
  pregnant: false, allergy: false, scarConstitution: false,
  skinLesion: false, coagulationAbn: false, seriousIllness: false, note: '',
})
const itemKeyword = ref('')

const signatureDataUrl = ref('')
const signerName = ref('')
const uploadedPhotos = ref<UploadedPhoto[]>([])
const showSkinReport = ref(false)
const skinType = ref('')
const skinComplaint = ref('')
const skinAnalysis = ref('')
const skinMetrics = ref<SkinMetric[]>([])
const savedSkinReportId = ref('')

const SKIN_METRIC_DEFS: { key: string; label: string }[] = [
  { key: 'spots', label: '色斑' },
  { key: 'wrinkle', label: '皱纹' },
  { key: 'texture', label: '纹理' },
  { key: 'pores', label: '毛孔' },
  { key: 'uvSpots', label: '紫外斑' },
  { key: 'brownSpots', label: '棕斑' },
  { key: 'redAreas', label: '红区' },
  { key: 'porphyrins', label: '紫质' },
]
const SKIN_TYPES = ['干性·屏障受损', '油性·痤疮', '混合性', '敏感性', '中性·光老化']

const planConsult = computed(() => consultation.get(selectedId.value))
const planCustomer = computed(() => (planConsult.value ? customer.get(planConsult.value.customerId) : undefined))

// 已提交单：只读回看（不可再编辑方案）；真实方案单（含真实驳回单）一律只读回看，
// 不在本页走本地 mock 重提（后端改单重提由医师工作台/后续端点承接，避免本地改状态不持久化的假交互）。
const isReadonly = computed(
  () =>
    !!planConsult.value &&
    (READONLY_STATUSES.includes(planConsult.value.status) || isRealPlan(planConsult.value as any)),
)

/** 已提交单的履约进度步骤（用于只读回看时间线） */
const FLOW_STEPS: { key: ConsultStatus; label: string }[] = [
  { key: 'PENDING_REVIEW', label: '已提交·待审核' },
  { key: 'APPROVED', label: '审核通过·待写病历' },
  { key: 'READY_PAY', label: '病历已签·待支付' },
  { key: 'PAID', label: '已支付·待治疗' },
  { key: 'TREATING', label: '治疗中' },
  { key: 'DONE', label: '已完成' },
]
const flowSteps = computed(() => {
  const cur = planConsult.value?.status
  const idx = FLOW_STEPS.findIndex((s) => s.key === cur)
  // 当前态及之前的步骤标为已完成（当前态高亮）
  return FLOW_STEPS.map((s, i) => ({
    ...s,
    done: idx >= 0 && i < idx,
    current: i === idx,
  }))
})
/** 已提交单下一步的去向（医师工作台 / 收银台 / 治疗执行） */
const readonlyNext = computed(() => {
  const st = planConsult.value?.status
  if (st === 'PENDING_REVIEW' || st === 'APPROVED') return { label: '前往医师工作台', to: '/doctor' }
  if (st === 'READY_PAY') return { label: '前往收银台收款', to: '/order' }
  if (st === 'PAID' || st === 'TREATING') return { label: '前往治疗执行', to: '/doctor' }
  return null
})
const customerSkinReports = computed(() =>
  planConsult.value ? customer.skinReportsOf(planConsult.value.customerId) : [],
)
const savedSkinReport = computed(() => customerSkinReports.value.find((r) => r.id === savedSkinReportId.value))

const contraFields = compliance.CONTRA_FIELDS
const positiveContra = computed(() => contraFields.filter((f) => contra.value[f.key]))

const searchResults = computed(() => {
  const kw = itemKeyword.value.trim().toLowerCase()
  let list = pricelist.active
  if (kw) list = list.filter((p) => p.name.toLowerCase().includes(kw) || p.code.toLowerCase().includes(kw))
  return list.slice(0, 6)
})

const planTotal = computed(() => planItems.value.reduce((s, it) => s + it.qty * it.price, 0))
const scan = computed(() => compliance.checkPlan(planItems.value, contra.value, planConclusion.value))

// —— 本单提成预估（复用 finCommission 咨询师阶梯规则，仅预估不落账）——
const commissionEstimate = computed(() => {
  const rule = finCommission.activeRule('CONSULTANT')
  if (!rule) return null
  const period = new Date().toISOString().slice(0, 7)
  // 当月已生成提成单的业绩基数合计（演示口径）
  const monthBase = finCommission.items
    .filter((i) => i.period === period && i.consultantId === (planConsult.value?.consultantId || 'staff-lin'))
    .reduce((s, i) => s + i.baseAmount, 0)
  const before = finCommission.calcTiers(rule, monthBase).reduce((s, t) => s + t.commission, 0)
  const after = finCommission.calcTiers(rule, monthBase + planTotal.value).reduce((s, t) => s + t.commission, 0)
  const est = Math.round((after - before) * 100) / 100
  // 本单落在哪一档
  const hit = [...rule.tiers].reverse().find((t) => monthBase + planTotal.value > t.min)
  return { est, rate: hit ? hit.rate : rule.tiers[0].rate, label: hit?.label ?? rule.tiers[0].label }
})

function blockedOf(item: PlanItem) {
  return compliance.checkItem(item, contra.value)
}

function selectConsult(id: string) {
  const c = consultation.get(id)
  if (!c) return
  selectedId.value = id
  // 点卡片仅「选中查看 + 加载表单」，不改变状态；
  // 待咨询单的接诊激活（PENDING→ACTIVE）由底部「开始咨询」按钮显式触发，
  // 避免浏览一眼就把待咨询全部翻成咨询中。
  if (READONLY_STATUSES.includes(c.status)) return
  loadPlanForm(consultation.get(id)!)
}

/** 待咨询单：显式开始咨询，激活接诊状态（PENDING→ACTIVE） */
function beginConsult() {
  if (!selectedId.value) return
  const ok = consultation.start(selectedId.value)
  if (ok) toast.success('已开始咨询，请完善面诊与方案')
  else toast.warning('当前无咨询权限或单据状态不可开始')
}

function loadPlanForm(c: NonNullable<ReturnType<typeof consultation.get>>) {
  planConclusion.value = c.conclusion || ''
  planItems.value = c.planItems ? JSON.parse(JSON.stringify(c.planItems)) : []
  planDoctorId.value = c.doctorId || ''
  planConsentC.value = !!c.consentConsultant
  planConsentCustomer.value = !!c.consentCustomer
  const cust = customer.get(c.customerId)
  contra.value = c.contraindications
    ? JSON.parse(JSON.stringify(c.contraindications))
    : {
      pregnant: false,
      allergy: !!cust?.allergies?.length,
      scarConstitution: false, skinLesion: false, coagulationAbn: false, seriousIllness: false,
      note: cust?.allergies?.length ? cust.allergies.join('；') : '',
    }
  itemKeyword.value = ''
  signatureDataUrl.value = c.consentSignatureDataUrl || ''
  signerName.value = c.consentSignerName || customer.get(c.customerId)?.name || ''
  uploadedPhotos.value = []
  showSkinReport.value = false
  skinType.value = ''
  skinComplaint.value = c.conclusion || ''
  skinAnalysis.value = ''
  skinMetrics.value = SKIN_METRIC_DEFS.map((d) => ({ key: d.key, label: d.label, score: 30, percentile: 60 }))
  savedSkinReportId.value = c.skinReportId || ''
  resetVoice()
}

function closeDetail() {
  selectedId.value = ''
}

function saveSkinReport() {
  if (!planConsult.value || !planCustomer.value) return
  if (!skinType.value) {
    toast.warning('请选择肤质分型')
    return
  }
  const r = customer.addSkinReport({
    customerId: planCustomer.value.id,
    consultId: planConsult.value.id,
    device: 'VISIA 7 皮肤检测仪',
    skinType: skinType.value,
    chiefComplaint: skinComplaint.value.trim(),
    metrics: skinMetrics.value.map((m) => ({ ...m })),
    analysis: skinAnalysis.value.trim() || '面诊评估：结合检测指标与主诉，建议以温和、规范的治疗与护理方案改善，具体以医生审核为准。',
    recommendations: planItems.value.map((i) => i.name),
  })
  savedSkinReportId.value = r.id
  showSkinReport.value = false
  toast.success('面诊/皮肤检测报告已生成并归档')
}

function persistPhotos() {
  if (!planConsult.value || !uploadedPhotos.value.length) return
  uploadedPhotos.value.forEach((ph) => {
    customer.addPhoto({
      customerId: planConsult.value!.customerId,
      consultId: planConsult.value!.id,
      category: 'before',
      part: ph.part,
      dataUrl: ph.dataUrl,
    })
  })
  uploadedPhotos.value = []
}

function openCustomer360() {
  if (planConsult.value) router.push(`/customers/${planConsult.value.customerId}?t=360`)
}

function addItem(pi: { id: string; name: string; memberPrice: number; unit: string; riskTags?: string[] }) {
  const draft: PlanItem = {
    itemId: pi.id, name: pi.name, spec: pi.unit, qty: 1, price: pi.memberPrice, riskTags: pi.riskTags,
  }
  const issue = blockedOf(draft)
  if (issue?.level === 'BLOCK') {
    toast.error(issue.text)
    return
  }
  const exist = planItems.value.find((x) => x.itemId === pi.id)
  if (exist) exist.qty += 1
  else planItems.value.push(draft)
  itemKeyword.value = ''
}

function removeItem(idx: number) {
  planItems.value.splice(idx, 1)
}

function submitPlan() {
  if (!selectedId.value || !planConsult.value) return
  if (!signatureDataUrl.value || !signerName.value.trim()) {
    toast.warning('请客户在《知情同意书》上手写电子签名后再提交')
    return
  }
  persistPhotos()
  const r = consultation.submitPlan(selectedId.value, {
    conclusion: planConclusion.value.trim(),
    planItems: planItems.value,
    doctorId: planDoctorId.value,
    contraindications: contra.value,
    consentConsultant: planConsentC.value,
    consentCustomer: planConsentCustomer.value,
    customerName: customer.nameOf(planConsult.value.customerId),
    consentSignatureDataUrl: signatureDataUrl.value,
    consentSignerName: signerName.value,
    skinReportId: savedSkinReportId.value || undefined,
  })
  if (!r.ok) {
    toast.error(r.error || '提交失败')
    return
  }
  toast.success('方案已提交医生审核')
  selectedId.value = ''
}

// ============================================================
// AI 语音面诊（真实 Web Speech API 语音识别 + 关键词要点提取；不支持时降级模拟转写）
// ============================================================
type VoiceState = 'idle' | 'recording' | 'transcribing' | 'done'
type VoiceAsrMode = 'real' | 'mock'
const voiceState = ref<VoiceState>('idle')
const voiceSeconds = ref(0)
const voiceTranscript = ref('')
const voiceAsrMode = ref<VoiceAsrMode>('real')
const voiceError = ref('')
const voiceInsights = ref<{ complaint: string; skinType: string; contra: string[]; advice: string } | null>(null)
let voiceTimer: ReturnType<typeof setInterval> | null = null

// Web Speech API 特性检测（Chrome/Edge 支持；Safari/Firefox 部分支持或不支持）
const SpeechRecognitionCtor =
  (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition
const supportsASR = !!SpeechRecognitionCtor
let recognition: any = null
let asrFinalText = ''

function resetVoice() {
  voiceState.value = 'idle'
  voiceSeconds.value = 0
  voiceTranscript.value = ''
  voiceInsights.value = null
  voiceError.value = ''
  if (voiceTimer) { clearInterval(voiceTimer); voiceTimer = null }
  if (recognition) {
    try { recognition.onend = null; recognition.abort() } catch { /* noop */ }
    recognition = null
  }
}

/** 从转写文本提取面诊要点（关键词规则；生产可替换为后端 NLP 接口） */
function extractInsights(text: string): { complaint: string; skinType: string; contra: string[]; advice: string } {
  const t = text
  const complaints: string[] = []
  if (/暗沉|暗黄|发黄|肤色不均/.test(t)) complaints.push('肤色暗沉/不均')
  if (/出油|油光|T区|t区/.test(t)) complaints.push('T 区出油')
  if (/毛孔|毛孔粗/.test(t)) complaints.push('毛孔粗大')
  if (/泛红|发红|红血丝/.test(t)) complaints.push('泛红/红血丝')
  if (/干|脱皮|缺水|卡粉/.test(t)) complaints.push('干燥缺水')
  if (/痘痘|痘|闭口|粉刺/.test(t)) complaints.push('痘痘/闭口')
  if (/松弛|下垂|皱纹|法令纹/.test(t)) complaints.push('松弛/细纹')
  if (/斑|晒斑|雀斑|色沉/.test(t)) complaints.push('色斑/色沉')
  if (/敏感|刺痛|屏障/.test(t)) complaints.push('敏感屏障受损')

  // 肤质推断
  const oily = /出油|油光|毛孔/.test(t)
  const dry = /干|脱皮|缺水/.test(t)
  const sensitive = /敏感|泛红|发红|刺痛/.test(t)
  let skinType = '待面诊仪检测'
  if (oily && dry) skinType = '混合性'
  else if (oily) skinType = '油性'
  else if (dry) skinType = '干性'
  if (sensitive) skinType += '（偏敏感）'

  // 禁忌/风险
  const contra: string[] = []
  if (/麻药|利多卡因|表麻|麻醉/.test(t) && /过敏|红痒|痒|红/.test(t))
    contra.push('过敏史：表麻/麻药敷贴处红痒（利多卡因过敏待排查）')
  else if (/过敏/.test(t)) contra.push('客户提及过敏史，需进一步排查过敏原')
  if (/怀孕|孕期|备孕|哺乳/.test(t)) contra.push('妊娠/备孕/哺乳期：光电项目禁忌，需延期')
  if (/瘢痕|疤痕体质|增生/.test(t)) contra.push('瘢痕体质风险：有创项目需谨慎评估')
  if (/暴晒|晒太阳/.test(t)) contra.push('近期暴晒史：光电项目需延后 2 周')

  // 项目建议
  const advice: string[] = []
  if (/暗沉|泛红|毛孔|出油|斑/.test(t)) advice.push('光子嫩肤改善暗沉泛红与毛孔')
  if (/干|缺水|脱皮|水光/.test(t)) advice.push('基础水光/补水疗程')
  if (/松弛|皱纹|下垂|抗衰|热玛吉/.test(t)) advice.push('热玛吉/射频抗衰评估')
  if (/痘痘|闭口|粉刺/.test(t)) advice.push('果酸/水杨酸焕肤')
  if (advice.length === 0) advice.push('建议先做皮肤检测（VISIA）后定制方案')
  if (contra.some((c) => c.includes('麻药'))) advice.push('表麻改用非利多卡因方案并先行斑贴测试')

  return {
    complaint: complaints.length ? complaints.join('、') + '。' : '未识别到明确主诉，请咨询师人工补充。',
    skinType,
    contra: contra.length ? contra : ['未识别到明确禁忌，请咨询师核对过敏史/孕哺状态'],
    advice: '建议' + advice.join('；') + '。',
  }
}

/** 转写完成 → AI 提取要点（真实/模拟共用） */
function runTranscription(text: string, mode: VoiceAsrMode) {
  voiceState.value = 'transcribing'
  voiceAsrMode.value = mode
  setTimeout(() => {
    if (mode === 'mock' || !text.trim()) {
      const name = planCustomer.value?.name ?? '客户'
      voiceTranscript.value =
        `「${name}：最近肤色比较暗沉，T 区出油、毛孔有点粗，脸颊换季容易泛红发干。` +
        `上个月打过一次水光，感觉还可以。没有怀孕，不过对麻药好像有点过敏，敷麻药的地方会红痒。` +
        `想先改善暗沉和毛孔，预算大概几千块，怕疼，希望恢复期短一点。」`
      voiceInsights.value = {
        complaint: '肤色暗沉、T 区出油毛孔粗大、脸颊换季泛红干燥；上月曾做水光，体验可接受。',
        skinType: '混合性',
        contra: ['过敏史：表麻/麻药敷贴处红痒（利多卡因过敏待排查）'],
        advice: '建议光子嫩肤改善暗沉泛红 + 基础水光补水；表麻改用非利多卡因方案并先行斑贴测试。',
      }
    } else {
      voiceTranscript.value = text.trim()
      voiceInsights.value = extractInsights(text)
    }
    voiceState.value = 'done'
  }, 900)
}

function startRealRecognition() {
  recognition = new SpeechRecognitionCtor()
  recognition.lang = 'zh-CN'
  recognition.continuous = true
  recognition.interimResults = true
  asrFinalText = ''
  voiceError.value = ''
  recognition.onresult = (e: any) => {
    let interim = ''
    for (let i = e.resultIndex; i < e.results.length; i++) {
      const transcript = e.results[i][0].transcript
      if (e.results[i].isFinal) asrFinalText += transcript
      else interim += transcript
    }
    voiceTranscript.value = (asrFinalText + interim).trim()
  }
  recognition.onerror = (e: any) => {
    // not-allowed/无麦克风权限 → 降级模拟；no-speech 仅提示
    if (e.error === 'not-allowed' || e.error === 'service-not-allowed') {
      voiceError.value = '麦克风权限被拒绝，已切换为模拟转写演示'
      stopTimer()
      try { recognition.abort() } catch { /* noop */ }
      recognition = null
      runTranscription('', 'mock')
    } else if (e.error === 'no-speech') {
      voiceError.value = '未检测到语音，请靠近麦克风重试'
    }
  }
  try {
    recognition.start()
  } catch {
    runTranscription('', 'mock')
    return
  }
}

function stopTimer() {
  if (voiceTimer) { clearInterval(voiceTimer); voiceTimer = null }
}

function toggleVoice() {
  if (voiceState.value === 'idle' || voiceState.value === 'done') {
    // 开始录音
    voiceState.value = 'recording'
    voiceSeconds.value = 0
    voiceTranscript.value = ''
    voiceInsights.value = null
    voiceError.value = ''
    voiceTimer = setInterval(() => { voiceSeconds.value += 1 }, 1000)
    if (supportsASR) {
      voiceAsrMode.value = 'real'
      startRealRecognition()
    } else {
      voiceAsrMode.value = 'mock'
    }
  } else if (voiceState.value === 'recording') {
    // 停止 → 转写
    stopTimer()
    if (supportsASR && recognition) {
      recognition.onend = () => {
        recognition = null
        runTranscription(asrFinalText, asrFinalText.trim() ? 'real' : 'mock')
      }
      try { recognition.stop() } catch { runTranscription(asrFinalText, 'real') }
    } else {
      runTranscription('', 'mock')
    }
  }
}

/** 一键应用 AI 提取结果到表单 */
function applyVoiceInsights() {
  if (!voiceInsights.value) return
  if (!skinComplaint.value.trim()) skinComplaint.value = voiceInsights.value.complaint
  if (!skinType.value) skinType.value = voiceInsights.value.skinType
  // 过敏提示：预勾过敏项并写入备注
  contra.value.allergy = true
  const note = voiceInsights.value.contra.join('；')
  const curNote = contra.value.note ?? ''
  if (!curNote.includes(note)) {
    contra.value.note = curNote ? `${curNote}；${note}` : note
  }
  if (!planConclusion.value.trim()) {
    planConclusion.value = `面诊（AI 语音转写）：${voiceInsights.value.complaint}${voiceInsights.value.advice}`
  }
  toast.success('AI 面诊要点已填入：主诉 / 肤质分型 / 过敏禁忌已预勾，请咨询师人工复核')
}

onUnmounted(() => {
  if (voiceTimer) clearInterval(voiceTimer)
  if (recognition) { try { recognition.abort() } catch { /* noop */ } recognition = null }
})
</script>

<template>
  <CWorkbenchShell
    :has-selection="!!planConsult && !!planCustomer"
    empty-icon="chat"
    empty-title="从左侧选择一位客户开始咨询"
    empty-desc="待咨询客户点击「开始咨询」；已驳回单可查看驳回原因后修改重新提交"
  >
    <template #kpis>
      <CKpi :value="String(queue.length)" label="待咨询" tone="warning" icon="chat" />
      <CKpi :value="String(rejected.length)" label="已驳回" tone="danger" icon="refresh" />
      <CKpi :value="String(reviewing.length)" label="待医生审核" tone="orange" icon="shield" />
      <CKpi :value="String(consultation.treatmentQueue.length)" label="诊疗履约中" tone="teal" icon="check" />
    </template>

    <!-- ============ 左侧列表 ============ -->
    <template #list>
        <div class="tabs">
          <button class="tab" :class="{ 'tab--active': listTab === 'queue' }" @click="listTab = 'queue'">
            待咨询 ({{ queue.length }})
          </button>
          <button class="tab" :class="{ 'tab--active': listTab === 'rejected' }" @click="listTab = 'rejected'">
            已驳回 ({{ rejected.length }})
          </button>
          <button class="tab" :class="{ 'tab--active': listTab === 'submitted' }" @click="listTab = 'submitted'">
            已提交 ({{ submittedList.length }})
          </button>
        </div>

        <div class="cw__items">
          <div
            v-for="c in listData"
            :key="c.id"
            class="row"
            :class="{ 'row--active': selectedId === c.id, 'row--reject': c.status === 'REJECTED', 'row--ongoing': c.status === 'ACTIVE' }"
            @click="selectConsult(c.id)"
          >
            <div class="row__top">
              <span class="row__title">{{ customer.nameOf(c.customerId) }}
                <span class="row__id">{{ c.customerId }}</span>
              </span>
              <CStatusPill :status="pill(c.status).s">{{ pill(c.status).t }}</CStatusPill>
            </div>
            <div class="row__meta">
              <span>{{ staffName(c.consultantId) }}</span>
              <span v-if="canSeePhone">{{ customer.phoneOf(c.customerId) }}</span>
            </div>
            <div v-if="c.status === 'REJECTED'" class="row__reject">
              <CIcon name="alert" :size="12" />
              <span>{{ c.rejectReason }}</span>
            </div>
            <div v-else-if="c.planItems?.length" class="row__items">
              <span v-for="(it, i) in c.planItems" :key="i" class="mini-tag">{{ it.name }}×{{ it.qty }}</span>
              <span v-if="c.planAmount" class="mini-tag mini-tag--amt">¥{{ c.planAmount }}</span>
            </div>
          </div>
          <div v-if="!listData.length" class="cw__empty">
            {{ listTab === 'queue' ? '暂无待咨询客户' : listTab === 'rejected' ? '暂无已驳回方案' : '今日暂无已提交咨询单' }}
          </div>
        </div>

        <div class="cw__handoff">
          <span>方案提交后进入 <strong>医师工作台</strong> 完成审核→写病历→缴费→治疗。</span>
          <CButton v-if="canReview" variant="ghost" size="sm" @click="router.push('/doctor')">前往医师工作台 →</CButton>
        </div>
    </template>

    <!-- ============ 右侧详情 ============ -->
    <template #head>
      <template v-if="planConsult && planCustomer">
            <div class="p360">
              <span class="p360__avatar">{{ planCustomer.avatarLetter }}</span>
              <div class="p360__meta">
                <div class="p360__name">
                  {{ planCustomer.name }} <span class="p360__id">{{ planCustomer.id }}</span>
                  <span class="p360__level">{{ planCustomer.level }} 客</span>
                  <CStatusPill :status="pill(planConsult.status).s">{{ pill(planConsult.status).t }}</CStatusPill>
                </div>
                <div class="p360__sub">
                  累计消费 ¥{{ planCustomer.totalSpend ?? 0 }} · 到店 {{ planCustomer.visitCount ?? 0 }} 次
                  <span v-if="canSeePhone"> · {{ customer.phoneOf(planCustomer.id) }}</span>
                </div>
                <div v-if="planCustomer.allergies?.length" class="p360__allergy">
                  档案过敏史：{{ planCustomer.allergies.join('；') }}
                </div>
              </div>
              <CButton variant="secondary" size="sm" class="p360__btn" @click="openCustomer360">
                <CIcon name="customer" :size="14" />客户 360
              </CButton>
            </div>

            <!-- 驳回原因横幅 -->
            <div v-if="planConsult.status === 'REJECTED'" class="reject-banner">
              <div class="reject-banner__title">
                <CIcon name="alert" :size="14" />医生驳回原因（{{ planConsult.reviewedByName }} · {{ fmtTime(planConsult.reviewedAt) }}）
              </div>
              <div class="reject-banner__text">{{ planConsult.rejectReason }}</div>
              <div class="reject-banner__tip">请据下方原因调整方案/禁忌后重新提交，修改将留痕。</div>
            </div>
      </template>
    </template>

          <!-- 详情体（滚动区，默认插槽） -->
    <template v-if="planConsult && planCustomer">

      <!-- ============ 已提交：只读回看 ============ -->
      <template v-if="isReadonly">
        <section class="blk ro-summary">
          <h4 class="blk__title"><CIcon name="profile" :size="15" />方案摘要（已提交 · 只读）</h4>
          <div class="ro-flow">
            <div
              v-for="s in flowSteps"
              :key="s.key"
              class="ro-flow__step"
              :class="{ 'is-done': s.done, 'is-current': s.current }"
            >
              <span class="ro-flow__dot">
                <CIcon v-if="s.done" name="check" :size="12" />
                <template v-else-if="s.current"><i class="ro-flow__pulse"></i></template>
              </span>
              <span class="ro-flow__label">{{ s.label }}</span>
            </div>
          </div>
          <div class="ro-conclusion">
            <label>咨询结论 / 诊断</label>
            <p>{{ planConsult.conclusion || '—' }}</p>
          </div>
          <div class="ro-items">
            <div class="ro-items__head">
              <span>方案项目（{{ planConsult.planItems?.length || 0 }} 项）</span>
              <span v-if="canSeeMargin && planConsult.planAmount" class="ro-items__amt">合计 ¥{{ planConsult.planAmount }}</span>
            </div>
            <div v-if="planConsult.planItems?.length" class="ro-items__list">
              <div v-for="(it, i) in planConsult.planItems" :key="i" class="ro-item">
                <span class="ro-item__name">{{ it.name }} <em>{{ it.spec }}</em></span>
                <span class="ro-item__qty">×{{ it.qty }}</span>
                <span class="ro-item__price">¥{{ it.price * it.qty }}</span>
              </div>
            </div>
            <p v-else class="ro-items__empty">无方案项目</p>
          </div>
          <div class="ro-meta">
            <span>接诊医生：{{ staffName(planConsult.doctorId) }}</span>
            <span>提交时间：{{ fmtTime(planConsult.submittedAt) }}</span>
          </div>
        </section>
      </template>

      <!-- ============ 待咨询 / 咨询中 / 已驳回：编辑方案 ============ -->
      <template v-else>
            <!-- AI 语音面诊 -->
            <section class="blk">
              <h4 class="blk__title"><CIcon name="mic" :size="15" />AI 语音面诊
                <span class="blk__hint">语音转写 + AI 自动提取主诉/肤质/禁忌，咨询师复核</span>
                <span class="voice__mode" :class="`voice__mode--${supportsASR ? 'real' : 'mock'}`">
                  {{ supportsASR ? '真实语音识别' : '模拟转写' }}
                </span>
              </h4>
              <div class="voice">
                <div class="voice__bar">
                  <CButton
                    :variant="voiceState === 'recording' ? 'danger' : 'secondary'"
                    size="sm"
                    @click="toggleVoice"
                  >
                    <CIcon :name="voiceState === 'recording' ? 'pos' : 'mic'" :size="14" />
                    {{ voiceState === 'idle' || voiceState === 'done' ? '开始语音面诊' : voiceState === 'recording' ? `录音中 ${voiceSeconds}s · 点击结束` : 'AI 转写中…' }}
                  </CButton>
                  <span v-if="voiceState === 'recording'" class="voice__rec">
                    <i></i><i></i><i></i><i></i> 正在{{ supportsASR ? '实时识别面诊对话' : '采集面诊对话' }}
                  </span>
                  <span v-else-if="voiceState === 'transcribing'" class="voice__tip">AI 正在识别语音并提取要点…</span>
                </div>
                <div v-if="voiceError" class="voice__err"><CIcon name="alert" :size="13" />{{ voiceError }}</div>
                <div v-if="voiceTranscript" class="voice__transcript">
                  <span v-if="voiceState === 'recording' && supportsASR" class="voice__live">实时转写：</span>{{ voiceTranscript }}
                </div>
                <div v-if="voiceInsights" class="voice__ai">
                  <div class="voice__ai-title"><CIcon name="scan" :size="13" />AI 面诊要点（待人工复核）</div>
                  <div class="voice__ai-row"><label>主诉</label><span>{{ voiceInsights.complaint }}</span></div>
                  <div class="voice__ai-row"><label>肤质</label><span>{{ voiceInsights.skinType }}</span></div>
                  <div class="voice__ai-row"><label>禁忌</label><span class="is-warn">{{ voiceInsights.contra.join('；') }}</span></div>
                  <div class="voice__ai-row"><label>建议</label><span>{{ voiceInsights.advice }}</span></div>
                  <CButton variant="secondary" size="sm" @click="applyVoiceInsights">
                    <CIcon name="check" :size="14" />一键填入面诊单
                  </CButton>
                </div>
              </div>
            </section>

            <!-- 面诊禁忌初筛 -->
            <section class="blk">
              <h4 class="blk__title">面诊禁忌初筛 <span class="blk__hint">阳性项将实时阻断冲突项目</span></h4>
              <div class="chips">
                <CChoiceChip
                  v-for="f in contraFields"
                  :key="f.key"
                  type="checkbox"
                  :modelValue="contra[f.key]"
                  :label="f.label"
                  solid
                  @update:modelValue="(v) => (contra[f.key] = v as boolean)"
                />
              </div>
              <CTextarea
                v-model="contra.note"
                :placeholder="positiveContra.length ? '存在阳性项，必须填写医生备注 / 处置说明（必填）' : '面诊备注（选填）'"
                :rows="2"
                class="blk__note"
              />
            </section>

            <!-- 咨询结论 + 敏感词 -->
            <section class="blk">
              <h4 class="blk__title">咨询结论 / 诊断 <span class="blk__req">必填</span>
                <span class="blk__hint">客观皮肤评估与建议项目，将作为病历诊断；为空不可提交</span>
              </h4>
              <CTextarea
                v-model="planConclusion"
                placeholder="客观描述皮肤评估与建议项目，禁止疗效承诺（如“根治/100%/永久/无效退款”等违禁词将被拦截）"
                :rows="3"
                :error="scan.sensitive.length > 0"
              />
              <div v-if="!planConclusion.trim()" class="issue issue--warn">请填写咨询结论 / 诊断（必填），提交后将作为医生首程病历的诊断依据。</div>
              <div v-if="scan.sensitive.length" class="issue issue--block">
                命中医疗广告违禁词：{{ scan.sensitive.join('、') }}，咨询师不得作疗效承诺，请删除后提交
              </div>
            </section>

            <!-- 结构化面诊报告 -->
            <section class="blk">
              <h4 class="blk__title">结构化面诊报告
                <span class="blk__hint">VISIA 皮肤检测 + 肤质分型，供医生审核参考</span>
              </h4>
              <div v-if="savedSkinReport" class="skin-saved">
                <div class="skin-saved__head">
                  <CStatusPill status="success"><CIcon name="check" :size="12" />报告已归档</CStatusPill>
                  <span class="skin-saved__meta">{{ savedSkinReport.device }} · {{ savedSkinReport.skinType }}</span>
                </div>
                <div class="skin-metrics skin-metrics--mini">
                  <span v-for="m in savedSkinReport.metrics" :key="m.key" class="skin-chip">
                    {{ m.label }}<i :class="{ 'is-high': m.score >= 60 }">{{ m.score }}</i>
                  </span>
                </div>
                <CButton variant="ghost" size="sm" @click="showSkinReport = !showSkinReport">
                  {{ showSkinReport ? '收起报告' : '查看 / 重新录入' }}
                </CButton>
              </div>

              <div v-if="showSkinReport || !savedSkinReport" class="skin-form">
                <div class="skin-form__types">
                  <button
                    v-for="t in SKIN_TYPES"
                    :key="t"
                    type="button"
                    class="skin-type"
                    :class="{ 'is-active': skinType === t }"
                    @click="skinType = t"
                  >{{ t }}</button>
                </div>
                <CTextarea v-model="skinComplaint" label="客户主诉 / 诉求" :rows="2" placeholder="如：肤色暗沉、毛孔粗大、想改善细纹" />
                <div class="skin-metrics">
                  <div v-for="m in skinMetrics" :key="m.key" class="skin-metric">
                    <span class="skin-metric__label">{{ m.label }}</span>
                    <input v-model.number="m.score" type="range" min="0" max="100" class="skin-metric__range" />
                    <span class="skin-metric__v" :class="{ 'is-high': m.score >= 60 }">{{ m.score }}</span>
                  </div>
                </div>
                <CTextarea v-model="skinAnalysis" label="面诊分析（客观描述，禁疗效承诺）" :rows="2" placeholder="留空将按指标自动生成客观分析" />
                <CButton variant="secondary" size="sm" @click="saveSkinReport">
                  <CIcon name="scan" :size="14" />生成并归档面诊报告
                </CButton>
              </div>
            </section>

            <!-- 面诊档案照 -->
            <section class="blk">
              <h4 class="blk__title">面诊档案照 <span class="blk__hint">正面/45°/下颌，自动脱敏加水印</span></h4>
              <CPhotoUpload v-model="uploadedPhotos" label="面诊照" />
            </section>

            <!-- 项目检索 + 明细 -->
            <section class="blk">
              <h4 class="blk__title">方案项目 <span class="blk__hint">选自价目表，自动算价</span></h4>
              <CInput v-model="itemKeyword" placeholder="搜索项目名 / 编码（如 光子、水光、LS-003）" />
              <div v-if="itemKeyword.trim()" class="search-list">
                <div v-for="p in searchResults" :key="p.id" class="search-item" @click="addItem(p)">
                  <span class="search-item__name">{{ p.name }}</span>
                  <span class="search-item__tags">
                    <span v-for="t in p.riskTags || []" :key="t" class="risk-tag">{{ RISK_TAG_LABEL[t] }}</span>
                  </span>
                  <span class="search-item__price">¥{{ p.memberPrice }}/{{ p.unit }}</span>
                </div>
                <div v-if="!searchResults.length" class="search-empty">无匹配项目</div>
              </div>

              <div class="items">
                <div v-for="(it, idx) in planItems" :key="idx" class="item-row">
                  <div class="item-row__head">
                    <span class="item-row__name">{{ it.name }}</span>
                    <button class="item-row__del" @click="removeItem(idx)">删除</button>
                  </div>
                  <div class="item-row__tags">
                    <span v-for="t in it.riskTags || []" :key="t" class="risk-tag">{{ RISK_TAG_LABEL[t] }}</span>
                  </div>
                  <div class="item-row__edit">
                    <label>数量
                      <input v-model.number="it.qty" type="number" min="1" class="mini-input" />
                    </label>
                    <label>单价 ¥
                      <input v-model.number="it.price" type="number" min="0" class="mini-input" />
                    </label>
                    <span class="item-row__sum">小计 ¥{{ it.qty * it.price }}</span>
                  </div>
                  <div v-if="blockedOf(it)?.level === 'BLOCK'" class="issue issue--block">{{ blockedOf(it)?.text }}</div>
                  <div v-else-if="blockedOf(it)?.level === 'WARN'" class="issue issue--warn">{{ blockedOf(it)?.text }}</div>
                </div>
                <div v-if="!planItems.length" class="cw__empty">尚未添加项目，搜索后点击加入方案</div>
              </div>
              <div class="total-row">
                方案合计 <strong>¥{{ planTotal }}</strong>
                <span v-if="canSeeMargin" class="total-row__cost">成本约 ¥{{ Math.round(planTotal * 0.35) }}</span>
              </div>
              <!-- 本单提成预估 -->
              <div v-if="commissionEstimate && planTotal > 0" class="comm-est">
                <CIcon name="pos" :size="13" />
                <span>本单咨询师提成预估 <strong>¥{{ commissionEstimate.est }}</strong>（{{ commissionEstimate.label }} · 费率 {{ (commissionEstimate.rate * 100).toFixed(0) }}%，随当月业绩跨档累进，最终以财务划扣口径结算）</span>
              </div>
            </section>

            <!-- 指定接诊医生 -->
            <section class="blk">
              <h4 class="blk__title">接诊医生</h4>
              <CSelect
                v-model="planDoctorId"
                :options="DOCTORS.map((d) => ({ label: `${d.name} · ${d.title}`, value: d.id }))"
                placeholder="选择接诊医生"
                width="100%"
              />
            </section>

            <!-- 合规扫描 -->
            <section class="blk">
              <div v-for="(b, i) in scan.blocks" :key="'b' + i" class="issue issue--block">{{ b.text }}</div>
              <div v-for="(w, i) in scan.warnings" :key="'w' + i" class="issue issue--warn">{{ w.text }}</div>
              <div v-if="scan.canSubmit && !scan.warnings.length" class="issue issue--ok">
                合规校验通过：无违禁词、无禁忌冲突，可提交医生审核
              </div>
            </section>

            <!-- 知情同意 + 电子签 -->
            <section class="blk consent">
              <h4 class="blk__title">
                <CIcon name="sign" :size="15" />知情同意书 · 电子签署
                <span class="blk__hint">版本 MEIYUN-ICF-v2026.1</span>
              </h4>
              <div class="consent-doc">
                <p>本人已在咨询师/医生充分告知下，了解拟实施项目的<strong>适应症、禁忌症、医疗风险、可能并发症及替代方案</strong>，并确认：</p>
                <ul>
                  <li>如实告知过敏史、基础疾病、用药与妊娠/哺乳情况；</li>
                  <li>知晓医美治疗存在个体差异，<strong>不承诺绝对效果</strong>，无「根治/永久/100% 有效」等保证；</li>
                  <li>同意术中拍照（脱敏归档）并按医嘱术后护理、按时复诊。</li>
                </ul>
              </div>
              <CCheckbox :model-value="planConsentC" @update:modelValue="planConsentC = $event">
                我已向客户逐条告知上述内容并解答疑问（咨询师确认）
              </CCheckbox>
              <div class="consent-sign">
                <CInput v-model="signerName" label="客户本人姓名" placeholder="请客户填写与身份证件一致的姓名" />
                <CSignaturePad v-model="signatureDataUrl" :signer-name="signerName" :height="130" />
              </div>
              <CCheckbox :model-value="planConsentCustomer" @update:modelValue="planConsentCustomer = $event">
                客户已阅读同意书并在上方手写签名，确认电子签名真实有效
              </CCheckbox>
            </section>
      </template>
    </template>

    <!-- 底部操作条 -->
    <template #foot>
      <template v-if="planConsult && planCustomer">
        <!-- 已提交只读回看：关闭 + 前往下一步环节 -->
        <template v-if="isReadonly">
          <CButton variant="ghost" @click="closeDetail">关闭</CButton>
          <CButton v-if="readonlyNext" variant="secondary" class="ro-foot-next" @click="router.push(readonlyNext.to)">
            {{ readonlyNext.label }} →
          </CButton>
        </template>
        <!-- 待咨询：先「开始咨询」激活接诊；咨询中/已驳回：完善方案后「提交审核」 -->
        <template v-else>
          <CButton variant="ghost" @click="closeDetail">取消</CButton>
          <!-- 待咨询（尚未接诊）：显式开始咨询 -->
          <CButton
            v-if="planConsult.status === 'PENDING'"
            variant="primary"
            :disabled="!canConsult"
            @click="beginConsult"
          >
            开始咨询
          </CButton>
          <!-- 咨询中 / 已驳回改单：提交审核 -->
          <CButton
            v-else
            variant="primary"
            :disabled="!scan.canSubmit || !planConclusion.trim() || !planItems.length || !planDoctorId || !planConsentC || !planConsentCustomer || !signatureDataUrl || !signerName.trim()"
            @click="submitPlan"
          >
            提交审核
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

.cw__items { flex: 1; overflow-y: auto; }
.row {
  display: block; width: 100%; text-align: left; padding: var(--s-md) var(--s-lg);
  background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer;
}
.row:hover { background: var(--c-brand-soft); }
.row--active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.row--reject .row__title { color: var(--c-danger-fg); }
.row--ongoing .row__title { color: var(--c-brand); }
.row__top { display: flex; justify-content: space-between; align-items: center; margin-bottom: var(--s-xs); }
.row__title { font-size: var(--t-sm); color: var(--c-text); font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; display: flex; align-items: center; gap: var(--s-xs); flex-wrap: wrap; }
.row__id { font-size: var(--t-xs); color: var(--c-text-3); font-weight: 400; }
.row__meta { display: flex; flex-wrap: wrap; gap: var(--s-sm); font-size: var(--t-xs); color: var(--c-text-3); align-items: center; }
.row__items { display: flex; flex-wrap: wrap; gap: 4px; margin-top: var(--s-xs); }
.row__reject { display: flex; gap: 6px; margin-top: var(--s-xs); font-size: var(--t-xs); color: var(--c-danger-fg); line-height: 1.5; }
.mini-tag { font-size: var(--t-xs); color: var(--c-brand); background: var(--c-brand-soft); border-radius: var(--r-sm); padding: 2px 6px; }
.mini-tag--amt { color: var(--c-teal-fg); background: var(--c-success-bg, #f0fbf0); font-weight: 600; }

.cw__empty { color: var(--c-text-3); font-size: var(--t-sm); text-align: center; padding: var(--s-xl) var(--s-md); }

.cw__handoff { border-top: 1px solid var(--c-border-light); padding: var(--s-sm); font-size: var(--t-xs); color: var(--c-text-3); line-height: 1.6; display: flex; flex-direction: column; gap: 6px; }
.cw__handoff strong { color: var(--c-brand); }

/* 详情 head/body/foot 外壳样式由 CWorkbenchShell 提供 */

.p360 { display: flex; gap: var(--s-sm); padding: var(--s-md); border-radius: var(--r-lg); background: var(--c-bg-page); align-items: flex-start; }
.p360__avatar { width: 44px; height: 44px; border-radius: var(--r-avatar); background: var(--c-brand-soft); color: var(--c-brand); font-weight: 700; font-size: var(--t-lg); display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.p360__meta { flex: 1; min-width: 0; }
.p360__name { font-weight: 700; font-size: var(--t-base); display: flex; align-items: center; gap: var(--s-xs); flex-wrap: wrap; }
.p360__id { font-size: var(--t-xs); color: var(--c-text-3); font-weight: 400; }
.p360__level { font-size: var(--t-xs); color: var(--c-brand); background: var(--c-brand-soft); border-radius: var(--r-sm); padding: 1px 6px; font-weight: 600; }
.p360__sub { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 3px; }
.p360__allergy { font-size: var(--t-xs); color: var(--c-danger-fg); margin-top: 4px; line-height: 1.5; }
.p360__btn { flex-shrink: 0; }

.reject-banner { margin-top: var(--s-md); padding: var(--s-md); border-radius: var(--r-md); background: var(--c-danger-soft, #fff1f0); border: 1px solid var(--c-danger-border, #ffccc7); }
.reject-banner__title { font-size: var(--t-sm); font-weight: 700; color: var(--c-danger-fg); display: flex; align-items: center; gap: 6px; }
.reject-banner__text { font-size: var(--t-sm); color: var(--c-text); margin-top: 6px; line-height: 1.6; }
.reject-banner__tip { font-size: var(--t-xs); color: var(--c-danger-fg); margin-top: 6px; }

/* AI 语音面诊 */
.voice { display: flex; flex-direction: column; gap: var(--s-sm); }
.voice__bar { display: flex; align-items: center; gap: var(--s-md); flex-wrap: wrap; }
.voice__rec { display: inline-flex; align-items: center; gap: 4px; font-size: var(--t-xs); color: var(--c-danger-fg); font-weight: 600; }
.voice__rec i { width: 3px; height: 12px; background: var(--c-danger-fg); border-radius: 2px; animation: vwave 0.9s ease-in-out infinite; }
.voice__rec i:nth-child(2) { animation-delay: .15s; } .voice__rec i:nth-child(3) { animation-delay: .3s; } .voice__rec i:nth-child(4) { animation-delay: .45s; }
@keyframes vwave { 0%,100% { height: 6px; } 50% { height: 16px; } }
.voice__tip { font-size: var(--t-xs); color: var(--c-text-3); }
.voice__mode { margin-left: auto; padding: 1px 8px; border-radius: var(--r-pill); font-size: 10px; font-weight: 600; }
.voice__mode--real { background: var(--c-success-bg); color: var(--c-success-fg); }
.voice__mode--mock { background: var(--c-warning-bg); color: var(--c-warning-fg); }
.voice__err { display: flex; align-items: center; gap: 4px; font-size: var(--t-xs); color: var(--c-danger-fg); }
.voice__live { color: var(--c-brand); font-weight: 600; margin-right: 4px; }
.voice__transcript { font-size: var(--t-sm); color: var(--c-text-2); line-height: 1.7; background: var(--c-bg-page); border-radius: var(--r-md); padding: var(--s-sm) var(--s-md); border-left: 3px solid var(--c-brand); }
.voice__ai { border: 1px solid var(--c-brand); border-radius: var(--r-md); padding: var(--s-md); display: flex; flex-direction: column; gap: 6px; background: var(--c-brand-soft); }
.voice__ai-title { font-size: var(--t-xs); font-weight: 700; color: var(--c-brand); display: flex; align-items: center; gap: 4px; }
.voice__ai-row { display: flex; gap: var(--s-sm); font-size: var(--t-xs); line-height: 1.6; }
.voice__ai-row label { flex-shrink: 0; width: 36px; color: var(--c-text-3); font-weight: 600; }
.voice__ai-row span { color: var(--c-text-2); }
.voice__ai-row span.is-warn { color: var(--c-danger-fg); font-weight: 600; }

/* 区块 */
.blk { margin-bottom: var(--s-md); }
.blk__title { font-size: var(--t-sm); font-weight: 700; color: var(--c-text); margin: 0 0 var(--s-sm); display: flex; align-items: center; gap: var(--s-xs); flex-wrap: wrap; }
.blk__hint { font-size: var(--t-xs); font-weight: 400; color: var(--c-text-3); }
.blk__req { font-size: 10px; font-weight: 700; color: #fff; background: var(--c-danger-fg); border-radius: var(--r-sm); padding: 1px 6px; line-height: 16px; }
.blk__note { margin-top: var(--s-sm); }
.chips { display: flex; flex-wrap: wrap; gap: var(--s-xs); }

.search-list { margin-top: var(--s-xs); border: 1px solid var(--c-border-light); border-radius: var(--r-md); overflow: hidden; }
.search-item { display: flex; align-items: center; gap: var(--s-sm); padding: 8px 12px; cursor: pointer; font-size: var(--t-sm); }
.search-item:hover { background: var(--c-brand-soft); }
.search-item__name { flex: 1; min-width: 0; }
.search-item__tags { display: flex; gap: 4px; }
.search-item__price { color: var(--c-brand); font-weight: 700; white-space: nowrap; }
.search-empty { padding: 10px 12px; font-size: var(--t-sm); color: var(--c-text-3); text-align: center; }

.risk-tag { font-size: 11px; color: var(--c-warning-fg, #d46b08); background: var(--c-warning-soft, #fff7e6); border-radius: var(--r-sm); padding: 1px 6px; white-space: nowrap; }
.items { display: flex; flex-direction: column; gap: var(--s-sm); margin-top: var(--s-sm); }
.item-row { border: 1px solid var(--c-border-light); border-radius: var(--r-md); padding: var(--s-sm); }
.item-row__head { display: flex; align-items: center; justify-content: space-between; gap: var(--s-sm); }
.item-row__name { font-weight: 600; font-size: var(--t-sm); }
.item-row__del { border: none; background: none; color: var(--c-danger-fg); font-size: var(--t-xs); cursor: pointer; padding: 0; }
.item-row__tags { display: flex; gap: 4px; margin-top: 4px; }
.item-row__edit { display: flex; align-items: center; gap: var(--s-md); margin-top: var(--s-sm); font-size: var(--t-xs); color: var(--c-text-3); }
.item-row__sum { margin-left: auto; font-weight: 700; color: var(--c-text); }
.mini-input { width: 64px; padding: 4px 8px; border: 1px solid var(--c-border-light); border-radius: var(--r-sm); font-size: var(--t-sm); margin-left: 4px; }
.total-row { display: flex; align-items: center; gap: var(--s-sm); justify-content: flex-end; margin-top: var(--s-md); padding-top: var(--s-sm); border-top: 1px dashed var(--c-border); font-size: var(--t-sm); color: var(--c-text-2); }
.total-row strong { font-size: var(--t-lg); color: var(--c-brand); }
.total-row__cost { font-size: var(--t-xs); color: var(--c-text-3); font-weight: 400; }

.comm-est { display: flex; align-items: center; gap: 6px; margin-top: var(--s-sm); font-size: var(--t-xs); color: var(--c-teal-fg); background: var(--c-success-soft, #f6ffed); border: 1px solid var(--c-success-border, #b7eb8f); border-radius: var(--r-md); padding: 6px 10px; line-height: 1.5; }
.comm-est strong { color: var(--c-success-fg, #389e0d); }

.issue { border-radius: var(--r-md); padding: 8px 12px; font-size: var(--t-xs); line-height: 1.6; margin-top: var(--s-sm); }
.issue--block { background: var(--c-danger-soft, #fff1f0); color: var(--c-danger-fg); border: 1px solid var(--c-danger-border, #ffccc7); }
.issue--warn { background: var(--c-warning-soft, #fff7e6); color: var(--c-warning-fg, #d46b08); border: 1px solid var(--c-warning-border, #ffd591); }
.issue--ok { background: var(--c-success-soft, #f6ffed); color: var(--c-success-fg, #389e0d); border: 1px solid var(--c-success-border, #b7eb8f); }

.consent { display: flex; flex-direction: column; gap: var(--s-sm); padding: var(--s-md); border-radius: var(--r-md); background: var(--c-bg-page); }
.consent-doc { background: var(--c-surface); border: 1px solid var(--c-border-light); border-radius: var(--r-md); padding: var(--s-sm) var(--s-md); font-size: var(--t-xs); color: var(--c-text-2); line-height: 1.7; }
.consent-doc p { margin: 0 0 4px; }
.consent-doc ul { margin: 0; padding-left: var(--s-md); }
.consent-doc strong { color: var(--c-text); }
.consent-sign { display: flex; flex-direction: column; gap: var(--s-xs); margin: var(--s-xs) 0; }

/* 面诊报告 */
.skin-saved { border: 1px solid var(--c-success-border, #b7eb8f); background: var(--c-success-soft, #f6ffed); border-radius: var(--r-md); padding: var(--s-sm) var(--s-md); display: flex; flex-direction: column; gap: var(--s-xs); align-items: flex-start; }
.skin-saved__head { display: flex; align-items: center; gap: var(--s-sm); }
.skin-saved__meta { font-size: var(--t-xs); color: var(--c-text-3); }
.skin-form { display: flex; flex-direction: column; gap: var(--s-sm); }
.skin-form__types { display: flex; flex-wrap: wrap; gap: 6px; }
.skin-type { border: 1px solid var(--c-border); background: var(--c-surface); border-radius: var(--r-pill); padding: 5px 12px; font-size: var(--t-xs); color: var(--c-text-2); cursor: pointer; }
.skin-type.is-active { border-color: var(--c-brand); background: var(--c-brand-soft); color: var(--c-brand); font-weight: 600; }
.skin-metrics { display: grid; grid-template-columns: 1fr 1fr; gap: 4px var(--s-md); }
.skin-metrics--mini { display: flex; flex-wrap: wrap; gap: 6px; }
.skin-metric { display: flex; align-items: center; gap: var(--s-xs); font-size: var(--t-xs); }
.skin-metric__label { width: 44px; color: var(--c-text-3); flex-shrink: 0; }
.skin-metric__range { flex: 1; accent-color: var(--c-brand); }
.skin-metric__v { width: 22px; text-align: right; font-weight: 600; color: var(--c-text-2); }
.skin-metric__v.is-high { color: var(--c-warning-fg, #d46b08); }
.skin-chip { font-size: 11px; color: var(--c-text-2); background: var(--c-surface); border: 1px solid var(--c-border-light); border-radius: var(--r-sm); padding: 2px 8px; display: inline-flex; align-items: center; gap: 4px; }
.skin-chip i { font-style: normal; font-weight: 700; color: var(--c-teal-fg); }
.skin-chip i.is-high { color: var(--c-warning-fg, #d46b08); }

/* —— 已提交只读回看 —— */
.ro-summary { display: flex; flex-direction: column; gap: var(--s-md); }
.ro-flow { display: flex; align-items: flex-start; gap: var(--s-xs); flex-wrap: wrap; }
.ro-flow__step { display: flex; flex-direction: column; align-items: center; gap: 6px; flex: 1; min-width: 96px; position: relative; }
.ro-flow__dot { width: 26px; height: 26px; border-radius: var(--r-pill, 999px); border: 1.5px solid var(--c-border); background: var(--c-surface); color: var(--c-text-3); display: flex; align-items: center; justify-content: center; }
.ro-flow__step.is-done .ro-flow__dot { border-color: var(--c-teal); background: var(--c-teal); color: var(--c-surface, #fff); }
.ro-flow__step.is-current .ro-flow__dot { border-color: var(--c-brand); background: var(--c-brand-soft); color: var(--c-brand); }
.ro-flow__pulse { width: 8px; height: 8px; border-radius: var(--r-pill, 999px); background: var(--c-brand); display: block; }
.ro-flow__label { font-size: var(--t-xs); color: var(--c-text-3); text-align: center; line-height: 1.4; }
.ro-flow__step.is-done .ro-flow__label { color: var(--c-teal-fg); }
.ro-flow__step.is-current .ro-flow__label { color: var(--c-brand); font-weight: 600; }
.ro-conclusion label { display: block; font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: 4px; }
.ro-conclusion p { margin: 0; font-size: var(--t-sm); color: var(--c-text-1); line-height: 1.6; background: var(--c-bg-page); border-radius: var(--r-md); padding: var(--s-sm); }
.ro-items__head { display: flex; justify-content: space-between; font-size: var(--t-sm); font-weight: 600; color: var(--c-text-1); margin-bottom: var(--s-xs); }
.ro-items__amt { color: var(--c-teal-fg); }
.ro-items__list { display: flex; flex-direction: column; gap: 6px; }
.ro-item { display: flex; align-items: center; gap: var(--s-sm); font-size: var(--t-sm); padding: 6px 0; border-bottom: 1px solid var(--c-border-light); }
.ro-item__name { flex: 1; color: var(--c-text-1); }
.ro-item__name em { font-style: normal; color: var(--c-text-3); margin-left: 6px; font-size: var(--t-xs); }
.ro-item__qty { color: var(--c-text-3); }
.ro-item__price { width: 90px; text-align: right; font-weight: 600; color: var(--c-text-1); }
.ro-items__empty { color: var(--c-text-3); font-size: var(--t-sm); }
.ro-meta { display: flex; gap: var(--s-lg); flex-wrap: wrap; font-size: var(--t-xs); color: var(--c-text-3); }
.ro-foot-next { margin-left: auto; }

</style>
