<script setup lang="ts">
/* ============================================================
 * 电子病历管理 /emr（Desktop 优先 · 平板堆叠）
 * 状态机：草稿 → 已签名（锁定）→ 已归档。
 * 合规：已签名/归档病历只读，更正只能"新建修订"（复制为新草稿，version+1，parentId 溯源）。
 * P5-B30：列表真分页（CPagination 1 起页码）+ 后端 stats 计数 + emr_template 模板套用/维护。
 * ============================================================ */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import CCard from '@/components/CCard.vue'
import CWorkbenchShell from '@/components/CWorkbenchShell.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CTextarea from '@/components/CTextarea.vue'
import CSelect from '@/components/CSelect.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CKpi from '@/components/CKpi.vue'
import CIcon from '@/components/CIcon.vue'
import CPagination from '@/components/CPagination.vue'
import {
  useEmrStore, EMR_TYPE_LABEL,
  type EmrTemplate, type EmrType,
} from '@/stores/emr'
import { EMR_STATUS, dictPill } from '@/config/dictionary'
import { useConsultationStore } from '@/stores/consultation'
import { useCustomerStore } from '@/stores/customer'
import { useAuthStore } from '@/stores/auth'
import { useToast } from '@/composables/useToast'
import { errMsg } from '@/stores/m5Coupon'
import { getPlan, type PlanViewDTO } from '@/api/consultPlan'
import { searchCustomers } from '@/api/customer'

const route = useRoute()
const router = useRouter()
const emr = useEmrStore()
const consultation = useConsultationStore()
const customer = useCustomerStore()
const auth = useAuthStore()
const toast = useToast()

// 据方案单写病历的预填来源（真实 /txn/consult-plan/{id}；getPlan 失败回落 consultation mock）
interface PlanBrief {
  id: string
  customerId: string
  customerName: string
  conclusion: string
  items: Array<{ name: string; qty: number }>
  allergyNote?: string
}
const planInfo = ref<PlanBrief | null>(null)
function adaptPlan(d: PlanViewDTO): PlanBrief {
  const contra = d.contraindications as { allergy?: boolean; note?: string } | null
  return {
    id: d.planId,
    customerId: d.customerId,
    customerName: d.customerName ?? '',
    conclusion: d.conclusion ?? '',
    items: d.items.map((i) => ({ name: i.itemName, qty: i.qty })),
    allergyNote: contra?.allergy
      ? `过敏史阳性${contra.note ? `；面诊备注：${contra.note}` : ''}`
      : undefined,
  }
}

type Tab = 'draft' | 'signed' | 'archived'
const TAB_STATUS: Record<Tab, string> = { draft: 'DRAFT', signed: 'SIGNED', archived: 'ARCHIVED' }
const tab = ref<Tab>('draft')
const selectedId = ref<string | null>(null)
const keyword = ref('')

// 当前 tab/status + q 下刷新分页（页码 1 起；写动作后末条迁出导致空页时自动回退）
async function reloadList(opts?: { page?: number }) {
  const targetPage = opts?.page ?? emr.page
  await emr.refresh(
    auth.user.storeId,
    { status: TAB_STATUS[tab.value], q: keyword.value.trim() || undefined },
    { page: targetPage },
  )
  if (emr.records.length === 0 && emr.total > 0 && emr.page > 1) {
    await reloadList({ page: Math.max(1, emr.totalPages) })
  }
}
async function selectTab(t: Tab) {
  if (tab.value === t) return
  tab.value = t
  selectedId.value = null
  await reloadList({ page: 1 })
}
async function onPage(p: number) {
  await reloadList({ page: p })
}
// 关键字防抖下推后端 q（四字段模糊），350ms 静默期后拉第一页
let kwTimer: ReturnType<typeof setTimeout> | undefined
watch(keyword, () => {
  clearTimeout(kwTimer)
  kwTimer = setTimeout(() => {
    selectedId.value = null
    void reloadList({ page: 1 })
  }, 350)
})

// 从咨询工作台"写病历/治疗"跳入时携带的方案单（据审核通过方案写病历）
const fromConsultId = ref('')
onMounted(async () => {
  consultation.seed()
  await emr.refresh(auth.user.storeId, { status: 'DRAFT' }, { page: 1 })
  const cid = typeof route.query.fromConsult === 'string' ? route.query.fromConsult : ''
  if (!cid) return
  fromConsultId.value = cid
  // ① 该方案单已有病历 → 切到其所在 tab 并定位（医师台「查看电子病历」）
  const exist = (await emr.byConsult(cid))[0]
  if (exist) {
    tab.value = exist.status === 'DRAFT' ? 'draft' : exist.status === 'SIGNED' ? 'signed' : 'archived'
    selectedId.value = exist.id
    await reloadList({ page: 1 })
    return
  }
  // ② 尚无病历 → 拉真实方案单预填并打开新建表单；getPlan 失败回落 consultation mock
  let brief: PlanBrief | null = null
  try {
    const res = await getPlan(cid)
    brief = adaptPlan(res.data)
    customer.hydrate([{ customerId: brief.customerId, name: brief.customerName }])
  } catch (e) {
    const c = consultation.get(cid)
    if (c) {
      brief = {
        id: cid, customerId: c.customerId,
        customerName: customer.get(c.customerId)?.name || customer.nameOf(c.customerId),
        conclusion: c.conclusion,
        items: (c.planItems ?? []).map((i) => ({ name: i.name, qty: i.qty })),
      }
      toast.info('方案单详情接口不可用，已使用本地缓存预填')
    } else {
      toast.error(errMsg(e, '方案单详情加载失败'))
    }
  }
  if (!brief) return
  planInfo.value = brief
  showForm.value = true
  newRec.value.customerId = brief.customerId
  newRec.value.customerName = brief.customerName || customer.nameOf(brief.customerId)
  newRec.value.type = 'TREATMENT'
  newRec.value.chiefComplaint = `按方案单 ${cid} 来院治疗`
  newRec.value.diagnosis = brief.conclusion
  newRec.value.treatment = brief.items.map((i) => `${i.name}×${i.qty}`).join('、')
  if (brief.allergyNote) newRec.value.allergy = brief.allergyNote
})

const tabs = computed(() => [
  { k: 'draft' as Tab, label: `草稿 (${emr.drafts})` },
  { k: 'signed' as Tab, label: `已签名 (${emr.signed})` },
  { k: 'archived' as Tab, label: `已归档 (${emr.archived})` },
])

// 服务端已按 tab status + q 过滤，当前页直接渲染
const list = computed(() => emr.records)

const selected = computed(() => {
  if (selectedId.value) return emr.get(selectedId.value) ?? null
  return list.value[0] ?? null
})

const kpis = computed(() => [
  { label: '草稿病历', value: String(emr.stats.draft), tone: 'warning' as const, icon: 'edit' },
  { label: '本月已签名', value: String(emr.stats.signedThisMonth), tone: 'brand' as const, icon: 'sign' },
  { label: '已归档', value: String(emr.stats.archived), tone: 'success' as const, icon: 'box' },
  { label: '锁定病历', value: String(emr.stats.signed + emr.stats.archived), tone: 'purple' as const, icon: 'shield' },
])


function fmtDate(iso: string) { return iso.slice(0, 10) }
function fmtDateTime(iso?: string) {
  if (!iso) return '—'
  const d = new Date(iso)
  return `${fmtDate(iso)} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

// 编辑表单（草稿态）
const form = ref({
  chiefComplaint: '', presentIllness: '', pastHistory: '', allergy: '',
  diagnosis: '', treatment: '', prescription: '',
})
watch(
  selected,
  (r) => {
    if (r && r.status === 'DRAFT') {
      form.value = {
        chiefComplaint: r.chiefComplaint, presentIllness: r.presentIllness, pastHistory: r.pastHistory,
        allergy: r.allergy, diagnosis: r.diagnosis, treatment: r.treatment, prescription: r.prescription,
      }
    }
  },
  { immediate: true },
)
const canSign = computed(() => form.value.diagnosis.trim() && form.value.treatment.trim())

async function saveDraft() {
  if (!selected.value) return
  selectedId.value = selected.value.id
  const ok = await emr.updateDraft(selected.value.id, { ...form.value })
  if (ok) toast.success('草稿已保存')
}
async function doSign() {
  if (!selected.value || !canSign.value) return
  selectedId.value = selected.value.id
  // 签名前先保存
  const saved = await emr.updateDraft(selected.value.id, { ...form.value })
  if (!saved) return
  const r = await emr.sign(selected.value.id)
  if (r) {
    toast.success('病历已电子签名并锁定')
    await reloadList({ page: emr.page })
  }
}
async function doArchive() {
  if (!selected.value) return
  selectedId.value = selected.value.id
  const r = await emr.archive(selected.value.id)
  if (r) {
    toast.success('病历已归档')
    await reloadList({ page: emr.page })
  }
}
async function doRevise() {
  if (!selected.value) return
  const r = await emr.revise(selected.value.id)
  if (r) {
    tab.value = 'draft'
    await reloadList({ page: 1 })
    selectedId.value = r.id
    toast.info('已基于原病历创建修订草稿（v' + r.version + '），原病历留痕保留')
  }
}

// 新建病历
function todayLocal(): string {
  const d = new Date()
  const m = `${d.getMonth() + 1}`.padStart(2, '0')
  const day = `${d.getDate()}`.padStart(2, '0')
  return `${d.getFullYear()}-${m}-${day}`
}
const emptyNewRec = () => ({
  customerId: '', customerName: '', type: 'FIRST_VISIT' as EmrType,
  visitDate: todayLocal(),
  relatedOrderNo: '',
  chiefComplaint: '', presentIllness: '', pastHistory: '', allergy: '',
  diagnosis: '', treatment: '', prescription: '',
})
const showForm = ref(false)
const newRec = ref(emptyNewRec())

// ---- 模板套用（弹层打开/类型变化时拉「集团通用 + 本店自建 + 类型通用」候选） ----
const templates = ref<EmrTemplate[]>([])
const tplNo = ref('')
const tplOptions = computed(() => templates.value.map((t) => ({
  value: t.templateNo,
  label: `${t.storeCode ? '本店' : '集团'} · ${t.type ? EMR_TYPE_LABEL[t.type] : '通用'} · ${t.name}`,
})))
watch(
  [showForm, () => newRec.value.type],
  async ([open]) => {
    if (open) {
      tplNo.value = ''
      templates.value = await emr.loadTemplates(newRec.value.type)
    } else {
      tplNo.value = ''
    }
  },
)
watch(tplNo, (no) => {
  const t = templates.value.find((x) => x.templateNo === no)
  if (!t) return
  const n = newRec.value
  n.chiefComplaint = t.chiefComplaint
  n.presentIllness = t.presentIllness
  n.pastHistory = t.pastHistory
  n.allergy = t.allergy
  n.diagnosis = t.diagnosis
  n.treatment = t.treatment
  n.prescription = t.prescription
  toast.info(`已套用模板「${t.name}」，可继续修改`)
})

// 模板「据审核通过方案单」提示条：真实方案单优先，回落本地 consultation mock
const fromConsult = computed(() => {
  if (!fromConsultId.value) return null
  if (planInfo.value) return { id: planInfo.value.id }
  return consultation.get(fromConsultId.value)
})
const canCreate = computed(() => newRec.value.customerName.trim() && newRec.value.chiefComplaint.trim())
async function createRecord() {
  if (!canCreate.value) return
  const n = newRec.value
  // customerId：方案单带入优先；否则按姓名远程唯一匹配（零/多匹配交由后端 400 中文提示）
  let customerId = n.customerId.trim()
  if (!customerId) {
    try {
      const res = await searchCustomers(n.customerName.trim())
      const exact = res.data.filter((c) => c.name === n.customerName.trim())
      if (exact.length === 1) customerId = exact[0].customerId
    } catch (e) {
      toast.error(errMsg(e, '客户查询失败'))
      return
    }
  }
  const r = await emr.create({
    customerId,
    customerName: n.customerName.trim(),
    type: n.type,
    visitDate: n.visitDate,
    relatedOrderNo: n.relatedOrderNo.trim() || undefined,
    chiefComplaint: n.chiefComplaint,
    presentIllness: n.presentIllness,
    pastHistory: n.pastHistory,
    allergy: n.allergy,
    diagnosis: n.diagnosis,
    treatment: n.treatment,
    prescription: n.prescription,
    // 据咨询方案单写病历：后端校验方案状态并回写 emr 关联
    consultId: fromConsultId.value || undefined,
  })
  if (r) {
    showForm.value = false
    fromConsultId.value = ''
    planInfo.value = null
    newRec.value = emptyNewRec()
    tab.value = 'draft'
    await reloadList({ page: 1 })
    selectedId.value = r.id
  }
}

// ---- 模板库维护（门店自建/停用；集团模板只读。读 emr:view，维护 emr:create，不新增权限码） ----
const showTplLib = ref(false)
const tplList = ref<EmrTemplate[]>([])
const tplSaving = ref(false)
const emptyTplForm = () => ({
  name: '', type: '',
  chiefComplaint: '', presentIllness: '', pastHistory: '', allergy: '',
  diagnosis: '', treatment: '', prescription: '',
})
const tplForm = ref(emptyTplForm())
const canSaveTpl = computed(() => tplForm.value.name.trim().length > 0)
async function openTplLib() {
  showTplLib.value = true
  tplList.value = await emr.loadTemplates()
}
async function saveTpl() {
  if (!canSaveTpl.value) return
  tplSaving.value = true
  const f = tplForm.value
  const t = await emr.createTemplate({
    name: f.name,
    type: f.type || null,
    chiefComplaint: f.chiefComplaint,
    presentIllness: f.presentIllness,
    pastHistory: f.pastHistory,
    allergy: f.allergy,
    diagnosis: f.diagnosis,
    treatment: f.treatment,
    prescription: f.prescription,
  })
  tplSaving.value = false
  if (t) {
    toast.success(`门店模板「${t.name}」已保存`)
    tplForm.value = emptyTplForm()
    tplList.value = await emr.loadTemplates()
    if (showForm.value) templates.value = await emr.loadTemplates(newRec.value.type)
  }
}
async function disableTpl(t: EmrTemplate) {
  if (await emr.disableTemplate(t.templateNo)) {
    toast.success(`模板「${t.name}」已停用`)
    tplList.value = await emr.loadTemplates()
    if (showForm.value) templates.value = await emr.loadTemplates(newRec.value.type)
  }
}
</script>

<template>
  <div class="emr">
    <CWorkbenchShell
      :has-selection="!!selected"
      empty-icon="profile"
      empty-title="请从左侧选择一份病历"
      empty-desc="草稿可编辑并电子签名锁定；已签名/归档病历只读，更正请新建修订版本"
      list-width="380px"
    >
      <template #kpis>
        <CKpi v-for="k in kpis" :key="k.label" :value="String(k.value)" :label="k.label" :tone="k.tone" :icon="k.icon" />
      </template>

      <template #toolbar>
        <CInput v-model="keyword" placeholder="搜索客户 / 病历号 / 诊断" />
        <CButton variant="ghost" v-perm.disable="'emr:view'" @click="openTplLib">
          <CIcon name="layers" :size="16" />模板库
        </CButton>
        <CButton variant="primary" v-perm.disable="'emr:create'" @click="showForm = true">
          <CIcon name="plus" :size="16" />新建病历
        </CButton>
      </template>

      <template #list>
        <div class="tabs">
          <button
            v-for="t in tabs" :key="t.k"
            class="tab" :class="{ 'tab--active': tab === t.k }"
            @click="selectTab(t.k)"
          >{{ t.label }}</button>
        </div>
        <div class="list">
          <div v-if="list.length === 0" class="empty">
            <CIcon name="profile" :size="28" class="empty__icon" />
            <div>{{ emr.loading ? '加载中…' : '暂无病历' }}</div>
          </div>
          <button
            v-for="r in list" :key="r.id"
            class="rec" :class="{ 'rec--active': selected?.id === r.id, 'rec--revised': r.version > 1 }"
            @click="selectedId = r.id"
          >
            <div class="rec__top">
              <span class="rec__name">{{ r.customerName }}</span>
              <CStatusPill :status="dictPill(EMR_STATUS[r.status]).status">{{ dictPill(EMR_STATUS[r.status]).text }}</CStatusPill>
            </div>
            <div class="rec__sub">
              <span class="rec__type">{{ EMR_TYPE_LABEL[r.type] }}</span>
              <span v-if="r.version > 1" class="rec__rev">修订 v{{ r.version }}</span>
              <span>{{ r.emrNo }}</span>
            </div>
            <div class="rec__diag">{{ r.diagnosis || '（待填写诊断）' }}</div>
            <div class="rec__meta">
              <span><CIcon name="calendar" :size="12" />{{ fmtDate(r.visitDate) }}</span>
              <span>{{ r.doctorName }}</span>
            </div>
          </button>
        </div>
        <CPagination
          v-if="emr.total > 0"
          :page="emr.page"
          :page-size="emr.pageSize"
          :total="emr.total"
          @update:page="onPage"
        />
      </template>

      <!-- 右列详情 -->
      <template #head>
        <div v-if="selected" class="wb-head">
          <h3 class="emr__detail-title">
            {{ selected.customerName }}
            <span class="emr__type-tag">{{ EMR_TYPE_LABEL[selected.type] }}</span>
            <span v-if="selected.version > 1" class="emr__ver">修订 v{{ selected.version }}</span>
          </h3>
          <div class="emr__detail-tags">
            <CStatusPill :status="dictPill(EMR_STATUS[selected.status]).status">{{ dictPill(EMR_STATUS[selected.status]).text }}</CStatusPill>
          </div>
        </div>
      </template>

      <template v-if="selected">
        <!-- 锁定提示（已签名/归档） -->
        <div v-if="selected.status !== 'DRAFT'" class="lockbar">
          <CIcon name="sign" :size="16" />
          <span>本病历已于 <strong>{{ fmtDateTime(selected.signedAt) }}</strong> 由 {{ selected.signedByName }} 电子签名并锁定，内容不可修改。如需更正请新建修订版本（原病历留痕保留）。</span>
        </div>

        <div class="meta-row">
          <div class="meta-item"><span class="meta-label">就诊日期</span><span class="meta-val">{{ fmtDate(selected.visitDate) }}</span></div>
          <div class="meta-item"><span class="meta-label">主治医生</span><span class="meta-val">{{ selected.doctorName }}</span></div>
          <div v-if="selected.relatedAppointmentNo" class="meta-item"><span class="meta-label">关联预约</span><span class="meta-val">{{ selected.relatedAppointmentNo }}</span></div>
          <div v-if="selected.relatedOrderNo" class="meta-item"><span class="meta-label">关联订单</span><span class="meta-val">{{ selected.relatedOrderNo }}</span></div>
          <div v-if="selected.consultId" class="meta-item">
            <span class="meta-label">关联方案单</span>
            <button class="meta-link" @click="router.push(`/doctor?fromConsult=${selected.consultId}`)">
              {{ selected.consultId }} · 去医师工作台 <CIcon name="chevron-right" :size="12" />
            </button>
          </div>
        </div>

        <!-- 草稿：可编辑表单 -->
        <div v-if="selected.status === 'DRAFT'" class="edit-form">
          <div class="sec">
            <label class="sec__label">主诉 <span class="req">*</span></label>
            <CTextarea v-model="form.chiefComplaint" placeholder="客户主要诉求与症状" />
          </div>
          <div class="sec sec--2">
            <div class="sec">
              <label class="sec__label">现病史</label>
              <CTextarea v-model="form.presentIllness" placeholder="发病经过、治疗史" />
            </div>
            <div class="sec">
              <label class="sec__label">既往史</label>
              <CTextarea v-model="form.pastHistory" placeholder="既往疾病、手术史" />
            </div>
          </div>
          <div class="sec">
            <label class="sec__label">过敏史</label>
            <CInput v-model="form.allergy" placeholder="药物/化妆品/食物过敏史" />
          </div>
          <div class="sec">
            <label class="sec__label">诊断 / 皮肤评估 <span class="req">*</span></label>
            <CTextarea v-model="form.diagnosis" placeholder="诊断结论（签名必填）" />
          </div>
          <div class="sec">
            <label class="sec__label">治疗方案 / 操作记录 <span class="req">*</span></label>
            <CTextarea v-model="form.treatment" placeholder="治疗项目、参数、术中情况（签名必填）" />
          </div>
          <div class="sec">
            <label class="sec__label">医嘱 / 术后注意事项</label>
            <CTextarea v-model="form.prescription" placeholder="术后护理、用药、复诊安排" />
          </div>
          <div v-if="!canSign" class="ops__hint">诊断与治疗方案为签名必填项。</div>
        </div>

        <!-- 已签名/归档：只读展示 -->
        <div v-else class="readonly">
          <div class="rsec">
            <div class="rsec__label">主诉</div>
            <div class="rsec__val">{{ selected.chiefComplaint || '—' }}</div>
          </div>
          <div class="rsec rsec--2">
            <div>
              <div class="rsec__label">现病史</div>
              <div class="rsec__val">{{ selected.presentIllness || '—' }}</div>
            </div>
            <div>
              <div class="rsec__label">既往史</div>
              <div class="rsec__val">{{ selected.pastHistory || '—' }}</div>
            </div>
          </div>
          <div class="rsec">
            <div class="rsec__label">过敏史</div>
            <div class="rsec__val" :class="{ 'rsec__val--allergy': selected.allergy && selected.allergy !== '否认过敏。' && selected.allergy !== '否认过敏' && selected.allergy !== '否认药物及化妆品过敏。' }">
              {{ selected.allergy || '—' }}
            </div>
          </div>
          <div class="rsec">
            <div class="rsec__label">诊断 / 皮肤评估</div>
            <div class="rsec__val rsec__val--diag">{{ selected.diagnosis }}</div>
          </div>
          <div class="rsec">
            <div class="rsec__label">治疗方案 / 操作记录</div>
            <div class="rsec__val">{{ selected.treatment }}</div>
          </div>
          <div class="rsec">
            <div class="rsec__label">医嘱 / 术后注意事项</div>
            <div class="rsec__val">{{ selected.prescription || '—' }}</div>
          </div>

          <div class="sign-info">
            <CIcon name="check-square" :size="16" />
            <span>电子签名：{{ selected.signedByName }} · {{ fmtDateTime(selected.signedAt) }}</span>
          </div>
        </div>
      </template>

      <template #foot>
        <template v-if="selected">
          <!-- 草稿：保存 / 签名 -->
          <template v-if="selected.status === 'DRAFT'">
            <CButton variant="ghost" v-perm.disable="'emr:edit'" @click="saveDraft">保存草稿</CButton>
            <CButton variant="primary" :disabled="!canSign" v-perm.disable="'emr:edit'" @click="doSign">
              <CIcon name="sign" :size="16" />电子签名并锁定
            </CButton>
          </template>
          <!-- 已签名：归档 / 修订 -->
          <template v-else-if="selected.status === 'SIGNED'">
            <CButton variant="ghost" v-perm.disable="'emr:edit'" @click="doArchive">
              <CIcon name="box" :size="16" />归档病历
            </CButton>
            <CButton variant="primary" v-perm.disable="'emr:create'" @click="doRevise">
              <CIcon name="edit" :size="16" />新建修订版本
            </CButton>
          </template>
          <!-- 已归档：终态 -->
          <template v-else>
            <span class="wbs-foot-done">
              <CIcon name="box" :size="15" />病历已归档，进入合规留档
            </span>
            <CButton variant="primary" v-perm.disable="'emr:create'" @click="doRevise">
              <CIcon name="edit" :size="16" />新建修订版本
            </CButton>
          </template>
        </template>
      </template>
    </CWorkbenchShell>

    <!-- 新建病历弹层 -->
    <div v-if="showForm" class="modal-mask" @click.self="showForm = false">
      <CCard class="modal" title="新建病历" padding="lg">
        <div class="nform">
          <div v-if="fromConsult" class="nform__consult">
            据审核通过方案单 <strong>{{ fromConsult.id }}</strong> 创建治疗记录，方案项目/禁忌已自动带入，创建后回写关联。
          </div>
          <div class="nform__row nform__row--2">
            <div>
              <label class="nform__label">客户姓名 <span class="req">*</span></label>
              <CInput v-model="newRec.customerName" placeholder="如：王美丽" />
            </div>
            <div>
              <label class="nform__label">病历类型</label>
              <CSelect v-model="newRec.type" width="100%" :options="[
                { value: 'FIRST_VISIT', label: '初诊' },
                { value: 'FOLLOW_UP', label: '复诊' },
                { value: 'TREATMENT', label: '治疗记录' },
                { value: 'PROCEDURE', label: '操作记录' },
              ]" />
            </div>
          </div>
          <div class="nform__row nform__row--2">
            <div>
              <label class="nform__label">就诊日期</label>
              <input type="date" v-model="newRec.visitDate" class="date-input" />
            </div>
            <div>
              <label class="nform__label">关联订单号（选填）</label>
              <CInput v-model="newRec.relatedOrderNo" placeholder="如：SO20260825001" />
            </div>
          </div>
          <div class="nform__row">
            <label class="nform__label">套用模板</label>
            <CSelect
              v-model="tplNo"
              width="100%"
              :disabled="tplOptions.length === 0"
              :options="tplOptions"
              :placeholder="tplOptions.length ? '选择模板一键填入七段内容（可再修改）' : '暂无可用模板'"
            />
          </div>
          <div class="nform__group-title">病史采集</div>
          <div class="nform__row">
            <label class="nform__label">主诉 <span class="req">*</span></label>
            <CTextarea v-model="newRec.chiefComplaint" placeholder="客户主要诉求（必填），如：面部肤色暗沉、毛孔粗大，咨询光子嫩肤" />
          </div>
          <div class="nform__row">
            <label class="nform__label">现病史</label>
            <CTextarea v-model="newRec.presentIllness" placeholder="发病/求美经过、既往同类治疗史、当前症状" />
          </div>
          <div class="nform__row nform__row--2">
            <div>
              <label class="nform__label">既往史</label>
              <CTextarea v-model="newRec.pastHistory" placeholder="基础疾病、瘢痕体质、植入物等" />
            </div>
            <div>
              <label class="nform__label">过敏史</label>
              <CTextarea v-model="newRec.allergy" placeholder="药物/化妆品/食物过敏史" />
            </div>
          </div>

          <div class="nform__group-title">诊断与治疗</div>
          <div class="nform__row">
            <label class="nform__label">初步诊断 / 皮肤评估 <span class="nform__sign-hint">（签名必填，可先建草稿）</span></label>
            <CTextarea v-model="newRec.diagnosis" placeholder="如：面部光老化、毛孔粗大（Fitzpatrick III 型）" />
          </div>
          <div class="nform__row">
            <label class="nform__label">治疗方案 / 操作记录 <span class="nform__sign-hint">（签名必填，可先建草稿）</span></label>
            <CTextarea v-model="newRec.treatment" placeholder="项目、部位、能量/剂量、术中情况等" />
          </div>
          <div class="nform__row">
            <label class="nform__label">医嘱 / 术后注意事项</label>
            <CTextarea v-model="newRec.prescription" placeholder="术后护理、用药、防晒、复诊安排" />
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showForm = false">取消</CButton>
          <CButton variant="primary" :disabled="!canCreate" @click="createRecord">创建草稿</CButton>
        </template>
      </CCard>
    </div>

    <!-- 病历模板库（集团模板只读；门店可自建/停用，复用 emr:create） -->
    <div v-if="showTplLib" class="modal-mask" @click.self="showTplLib = false">
      <CCard class="modal" title="病历模板库" padding="lg">
        <div class="nform">
          <div class="nform__group-title">可用模板（{{ tplList.length }}）</div>
          <div v-if="tplList.length === 0" class="empty">
            <CIcon name="layers" :size="28" class="empty__icon" />
            <div>暂无可用模板</div>
          </div>
          <div v-for="t in tplList" :key="t.templateNo" class="tpl-row">
            <div class="tpl-row__main">
              <div class="tpl-row__name">
                {{ t.name }}
                <span class="tpl-tag" :class="t.storeCode ? 'tpl-tag--store' : 'tpl-tag--group'">{{ t.storeCode ? '本店' : '集团' }}</span>
                <span class="tpl-tag">{{ t.type ? EMR_TYPE_LABEL[t.type] : '全类型通用' }}</span>
              </div>
              <div class="tpl-row__no">{{ t.templateNo }}</div>
            </div>
            <CButton
              v-if="t.storeCode"
              variant="ghost"
              v-perm.disable="'emr:create'"
              @click="disableTpl(t)"
            >
              <CIcon name="close" :size="14" />停用
            </CButton>
          </div>

          <div class="nform__group-title">新建本店模板</div>
          <div class="nform__row nform__row--2">
            <div>
              <label class="nform__label">模板名称 <span class="req">*</span></label>
              <CInput v-model="tplForm.name" placeholder="如：光子嫩肤初诊标准病历" />
            </div>
            <div>
              <label class="nform__label">适用类型</label>
              <CSelect v-model="tplForm.type" width="100%" :options="[
                { value: '', label: '全类型通用' },
                { value: 'FIRST_VISIT', label: '初诊' },
                { value: 'FOLLOW_UP', label: '复诊' },
                { value: 'TREATMENT', label: '治疗记录' },
                { value: 'PROCEDURE', label: '操作记录' },
              ]" />
            </div>
          </div>
          <div class="nform__row">
            <label class="nform__label">主诉</label>
            <CTextarea v-model="tplForm.chiefComplaint" placeholder="新建病历时一键填入的主诉范文" />
          </div>
          <div class="nform__row">
            <label class="nform__label">现病史</label>
            <CTextarea v-model="tplForm.presentIllness" placeholder="发病/求美经过、既往同类治疗史" />
          </div>
          <div class="nform__row nform__row--2">
            <div>
              <label class="nform__label">既往史</label>
              <CTextarea v-model="tplForm.pastHistory" placeholder="基础疾病、瘢痕体质、植入物等" />
            </div>
            <div>
              <label class="nform__label">过敏史</label>
              <CTextarea v-model="tplForm.allergy" placeholder="药物/化妆品/食物过敏史" />
            </div>
          </div>
          <div class="nform__row">
            <label class="nform__label">诊断 / 皮肤评估</label>
            <CTextarea v-model="tplForm.diagnosis" placeholder="诊断结论范文" />
          </div>
          <div class="nform__row">
            <label class="nform__label">治疗方案 / 操作记录</label>
            <CTextarea v-model="tplForm.treatment" placeholder="项目、部位、能量/剂量范文" />
          </div>
          <div class="nform__row">
            <label class="nform__label">医嘱 / 术后注意事项</label>
            <CTextarea v-model="tplForm.prescription" placeholder="术后护理、用药、复诊安排范文" />
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" @click="showTplLib = false">关闭</CButton>
          <CButton variant="primary" :disabled="!canSaveTpl || tplSaving" v-perm.disable="'emr:create'" @click="saveTpl">
            <CIcon name="plus" :size="16" />保存为本店模板
          </CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.emr { display: flex; flex-direction: column; gap: var(--s-lg); }

.emr__detail-title { font-size: var(--t-md); line-height: var(--lh-md); font-weight: 700; color: var(--c-text); margin: 0; display: flex; align-items: center; gap: var(--s-sm); }
.emr__type-tag { font-size: var(--t-xs); font-weight: 400; padding: 1px 8px; background: var(--c-brand-soft); color: var(--c-brand); border-radius: var(--r-pill); }
.emr__ver { font-size: var(--t-xs); font-weight: 400; padding: 1px 8px; background: var(--c-warning-bg); color: var(--c-warning-fg); border-radius: var(--r-pill); }
.emr__detail-tags { display: flex; gap: var(--s-xs); }
.wb-head { display: flex; justify-content: space-between; align-items: center; gap: var(--s-sm); }

.tabs { display: flex; border-bottom: 1px solid var(--c-border); flex-shrink: 0; }
.tab {
  flex: 1; padding: var(--s-md) var(--s-xs); font-size: var(--t-xs); white-space: nowrap;
  color: var(--c-text-3); background: none; border: none; cursor: pointer;
  border-bottom: 2px solid transparent;
}
.tab--active { color: var(--c-brand); border-bottom-color: var(--c-brand); font-weight: 600; }

.list { flex: 1; min-height: 0; overflow-y: auto; }
.empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); font-size: var(--t-sm); }
.empty__icon { color: var(--c-text-4); }

.rec {
  display: block; width: 100%; text-align: left; padding: var(--s-md) var(--s-lg);
  background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer;
}
.rec:hover { background: var(--c-brand-soft); }
.rec--active { background: var(--c-brand-soft); }
.rec--revised { border-left: 3px solid var(--c-warning-fg); }
.rec__top { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; }
.rec__name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.rec__sub { display: flex; align-items: center; gap: var(--s-xs); font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: 4px; }
.rec__type { color: var(--c-brand); font-weight: 600; }
.rec__rev { background: var(--c-warning-bg); color: var(--c-warning-fg); padding: 0 6px; border-radius: var(--r-pill); }
.rec__diag { font-size: var(--t-xs); color: var(--c-text-2); margin-bottom: var(--s-xs); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.rec__meta { display: flex; justify-content: space-between; font-size: var(--t-xs); color: var(--c-text-3); }
.rec__meta span { display: inline-flex; align-items: center; gap: 3px; }

.tpl-row { display: flex; align-items: center; justify-content: space-between; gap: var(--s-sm); padding: var(--s-sm) 0; border-bottom: 1px solid var(--c-border-light); }
.tpl-row__main { min-width: 0; }
.tpl-row__name { display: flex; align-items: center; gap: var(--s-xs); font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.tpl-row__no { margin-top: 2px; font-size: var(--t-xs); color: var(--c-text-3); }
.tpl-tag { font-size: var(--t-xs); font-weight: 400; padding: 1px 8px; background: var(--c-brand-soft); color: var(--c-brand); border-radius: var(--r-pill); }
.tpl-tag--group { background: var(--c-warning-bg); color: var(--c-warning-fg); }
.tpl-tag--store { background: var(--c-success-bg, #f0fff4); color: var(--c-success-fg, #389e0d); }

.lockbar {
  display: flex; gap: var(--s-sm); align-items: flex-start;
  padding: var(--s-md); background: var(--c-brand-soft); border-radius: var(--r-md);
  font-size: var(--t-sm); color: var(--c-text-2); line-height: 1.6; margin-bottom: var(--s-md);
}
.lockbar strong { color: var(--c-brand); }

.meta-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); padding-bottom: var(--s-md); border-bottom: 1px solid var(--c-border-light); }
.meta-item { display: flex; flex-direction: column; gap: 2px; }
.meta-label { font-size: var(--t-xs); color: var(--c-text-3); }
.meta-val { font-size: var(--t-sm); color: var(--c-text); font-weight: 500; }
.meta-link { display: inline-flex; align-items: center; gap: 2px; padding: 0; border: none; background: none; font-size: var(--t-sm); font-weight: 600; color: var(--c-brand); cursor: pointer; }
.meta-link:hover { text-decoration: underline; }

.edit-form { display: flex; flex-direction: column; gap: var(--s-md); padding-top: var(--s-md); }
.sec { display: flex; flex-direction: column; gap: var(--s-xs); }
.sec--2 { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.sec__label { font-size: var(--t-xs); color: var(--c-text-3); font-weight: 600; }
.req { color: var(--c-danger-fg); }

.readonly { display: flex; flex-direction: column; gap: var(--s-md); padding-top: var(--s-md); }
.rsec { display: flex; flex-direction: column; gap: 4px; }
.rsec--2 { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.rsec__label { font-size: var(--t-xs); color: var(--c-text-3); font-weight: 600; }
.rsec__val { font-size: var(--t-sm); color: var(--c-text); line-height: 1.7; white-space: pre-wrap; }
.rsec__val--diag { font-weight: 600; color: var(--c-brand); }
.rsec__val--allergy { color: var(--c-danger-fg); font-weight: 600; }

.sign-info { display: flex; align-items: center; gap: var(--s-sm); font-size: var(--t-sm); color: var(--c-success-fg); padding: var(--s-sm) var(--s-md); background: var(--c-success-bg, #f0fff4); border-radius: var(--r-md); }

.ops__hint { text-align: right; font-size: var(--t-xs); color: var(--c-text-3); }

.wbs-foot-done { display: inline-flex; align-items: center; gap: var(--s-xs); color: var(--c-success-fg, #389e0d); font-size: var(--t-sm); font-weight: 600; margin-right: auto; }

.date-input { padding: 10px; border: 1px solid var(--c-border); border-radius: var(--r-md); font-size: var(--t-sm); color: var(--c-text); background: #fff; font-family: inherit; width: 100%; box-sizing: border-box; }
.date-input:focus { outline: none; border-color: var(--c-brand); }

.modal-mask { position: fixed; inset: 0; background: rgba(20,21,43,.45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 620px; max-width: 100%; max-height: 90vh; overflow-y: auto; box-shadow: var(--shadow-pop); }
.nform { display: flex; flex-direction: column; gap: var(--s-md); }
.nform__consult { font-size: var(--t-xs); color: var(--c-success-fg, #389e0d); background: var(--c-success-soft, #f6ffed); border: 1px solid var(--c-success-border, #b7eb8f); border-radius: var(--r-md); padding: 8px 12px; line-height: 1.6; }
.nform__group-title {
  font-size: var(--t-xs); font-weight: 700; color: var(--c-text-2);
  padding-top: var(--s-sm); margin-top: var(--s-xs);
  border-top: 1px dashed var(--c-border);
  display: flex; align-items: center; gap: var(--s-xs);
}
.nform__group-title:first-child { border-top: none; padding-top: 0; margin-top: 0; }
.nform__sign-hint { font-weight: 400; color: var(--c-text-4); }
.nform__row { display: flex; flex-direction: column; gap: var(--s-xs); }
.nform__row--2 { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.nform__label { font-size: var(--t-xs); color: var(--c-text-3); }

@media (max-width: 1024px) {
  .meta-row { grid-template-columns: repeat(2, 1fr); }
}
</style>
