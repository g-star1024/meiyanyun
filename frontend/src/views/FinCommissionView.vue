<script setup lang="ts">
/* ============================================================
 * M6-08 咨询师提成 /m6-commission
 * 4 KPI：应发提成合计 / 已审批待发放 / 待审批 / 已发放
 * 左：提成单列表（按期间筛选）；右：详情（业绩基数 + 阶梯明细 + 提交/审批/驳回/发放）
 * 红线：业绩基数固定 WRITEOFF 口径（已双签划扣确认收入）；
 *       提成发放仅镜像外部薪酬系统回传，本系统不直接动账。
 * 严格消费 src/stores/finCommission.ts 现有 API，不改 store。
 * ============================================================ */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CTextarea from '@/components/CTextarea.vue'
import CSelect from '@/components/CSelect.vue'
import CInput from '@/components/CInput.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CBarChart from '@/components/CBarChart.vue'
import CDrawer from '@/components/CDrawer.vue'
import { useFinCommissionStore, yuanToFen, rateToBp, type CommRole } from '@/stores/finCommission'
import { useToast } from '@/composables/useToast'
import {
  exportCommissionCsv, createCommissionRule, updateCommissionRule,
  saveCompConfig,
} from '@/api/commission'
import { listStaff, type Staff } from '@/api/org'
import { useStoreContext } from '@/stores/storeContext'

const store = useFinCommissionStore()
const storeCtx = useStoreContext()
const toast = useToast()

function errMsg(e: unknown, fallback: string): string {
  const anyE = e as { response?: { data?: { message?: string } }; message?: string }
  return anyE?.response?.data?.message || anyE?.message || fallback
}

onMounted(async () => {
  try {
    await store.seed()
  } catch (e) {
    toast.error(errMsg(e, '提成数据载入失败，请稍后重试'))
  }
})

/** 当前月份 yyyy-MM-01（后端契约） */
function currentMonth(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-01`
}
/** 当前操作期间：筛选了具体期间取筛选期间，否则当月 */
function targetPeriod(): string {
  return store.filterPeriod !== 'ALL' ? `${store.filterPeriod}-01` : currentMonth()
}

const selectedId = ref<string | null>(null)
const selected = computed(() => {
  if (selectedId.value) return store.get(selectedId.value) ?? null
  return store.filtered[0] ?? null
})

const kpis = computed(() => [
  { label: '应发提成合计', icon: 'profile', value: money(store.totalCommission), tone: 'brand' as const },
  { label: '已审批待发放', icon: 'check-square', value: money(store.approvedCommission), tone: 'orange' as const },
  { label: '待审批', icon: 'check-square', value: money(store.pendingCommission), tone: 'warning' as const },
  { label: '已发放', icon: 'finance', value: money(store.paidCommission), tone: 'success' as const },
])

const statusOptions = [
  { value: 'ALL', label: '全部状态' },
  { value: 'DRAFT', label: '待提交' },
  { value: 'SUBMITTED', label: '待审批' },
  { value: 'APPROVED', label: '已审批待发放' },
  { value: 'PAID', label: '已发放' },
  { value: 'REJECTED', label: '已驳回' },
]
const periodOptions = computed(() => [
  { value: 'ALL', label: '全部期间' },
  ...store.periods.map((p) => ({ value: p, label: p })),
])

function money(n: number) {
  return `¥${Math.round(n).toLocaleString('zh-CN')}`
}
function fmtTime(iso?: string | null) {
  return iso ? iso.slice(0, 16).replace('T', ' ') : '—'
}
function effectiveRate(item: { commission: number; baseAmount: number }) {
  return item.baseAmount > 0 ? (item.commission / item.baseAmount) * 100 : 0
}

/** 当前筛选期间内的提成排行（横向条形图） */
const ranking = computed(() => {
  return store.filtered
    .slice()
    .sort((a, b) => b.commission - a.commission)
    .slice(0, 6)
    .map((i) => ({ label: i.consultantName, values: [i.commission] }))
})

// 操作
const actionBusy = ref(false)
async function runAction(fn: () => Promise<unknown>, okMsg: string, failMsg: string) {
  if (actionBusy.value) return
  actionBusy.value = true
  try {
    await fn()
    toast.success(okMsg)
  } catch (e) {
    toast.error(errMsg(e, failMsg))
  } finally {
    actionBusy.value = false
  }
}
function doSubmit() {
  const sel = selected.value
  if (sel) return runAction(() => store.submit(sel.id), '提成单已提交审批', '提交失败，请稍后重试')
}
function doApprove() {
  const sel = selected.value
  if (sel) return runAction(() => store.approve(sel.id), '审批通过', '审批失败，请稍后重试')
}
function doPay() {
  const sel = selected.value
  if (sel) return runAction(() => store.markPaid(sel.id), '发放状态已登记（外部薪酬系统回传镜像）', '登记失败，请稍后重试')
}

// 生成 / 重算试算
async function doGenerate() {
  const period = targetPeriod()
  await runAction(
    () => store.generate(period),
    `${period.slice(0, 7)} 期提成单已生成 / 重算`,
    '提成单生成失败，请稍后重试',
  )
}

// 导出薪酬表 CSV
async function doExport() {
  await runAction(
    () => exportCommissionCsv({ period: targetPeriod() }),
    '薪酬表已导出',
    '导出失败，请稍后重试',
  )
}

// 驳回弹层
const showReject = ref(false)
const rejectReason = ref('')
function openReject() {
  rejectReason.value = ''
  showReject.value = true
}
async function confirmReject() {
  const sel = selected.value
  if (!sel || !rejectReason.value.trim()) return
  actionBusy.value = true
  try {
    await store.reject(sel.id, rejectReason.value.trim())
    toast.success('提成单已驳回')
    showReject.value = false
  } catch (e) {
    toast.error(errMsg(e, '驳回失败，请稍后重试'))
  } finally {
    actionBusy.value = false
  }
}

// ---------------- 配置抽屉 ----------------
type DrawerKind = '' | 'rules' | 'comp'
const drawerKind = ref<DrawerKind>('')
const drawerOpen = computed({
  get: () => drawerKind.value !== '',
  set: (v: boolean) => { if (!v) drawerKind.value = '' },
})

const BASE_OPTIONS = [
  { value: 'WRITEOFF', label: '划扣确认收入（已双签，业财一体口径）' },
  { value: 'ORDER', label: '成交额' },
  { value: 'RECHARGE', label: '充值额' },
]
const ROLE_OPTIONS: { value: CommRole | 'ALL'; label: string }[] = [
  { value: 'CONSULTANT', label: '咨询师' },
  { value: 'DOCTOR', label: '主诊医生' },
  { value: 'BEAUTICIAN', label: '美疗师' },
  { value: 'ALL', label: '通用岗位' },
]
const ROLE_LABEL: Record<string, string> = Object.fromEntries(ROLE_OPTIONS.map((r) => [r.value, r.label]))
const BASE_LABEL_SM: Record<string, string> = { WRITEOFF: '划扣确认收入', ORDER: '成交额', RECHARGE: '充值额' }

// ---- 提成规则抽屉 ----
interface RuleTierForm { min: string; rate: string; label: string }
const ruleForm = reactive({
  id: '',
  name: '',
  base: 'WRITEOFF' as 'WRITEOFF' | 'ORDER' | 'RECHARGE',
  role: 'CONSULTANT' as CommRole | 'ALL',
  tiers: [] as RuleTierForm[],
})
const ruleSaving = ref(false)

function resetRuleForm() {
  ruleForm.id = ''
  ruleForm.name = ''
  ruleForm.base = 'WRITEOFF'
  ruleForm.role = 'CONSULTANT'
  ruleForm.tiers = [{ min: '0', rate: '', label: '' }]
}
function openRules() {
  drawerKind.value = 'rules'
}
function openCreateRule() {
  resetRuleForm()
}
function openEditRule(id: string) {
  const r = store.rules.find((x) => x.id === id)
  if (!r) return
  ruleForm.id = r.id
  ruleForm.name = r.name
  ruleForm.base = r.base
  ruleForm.role = r.role
  ruleForm.tiers = r.tiers.map((t) => ({ min: String(t.min), rate: String(Math.round(t.rate * 1000) / 10), label: t.label }))
}
function addTier() {
  ruleForm.tiers.push({ min: '', rate: '', label: '' })
}
function removeTier(i: number) {
  if (ruleForm.tiers.length > 1) ruleForm.tiers.splice(i, 1)
}
async function submitRule() {
  if (!ruleForm.name.trim()) { toast.warning('请填写规则名称'); return }
  const tiers = ruleForm.tiers
    .map((t) => ({ min: Number(t.min) || 0, rate: Math.round(Number(t.rate) * 100) / 100, label: t.label.trim() }))
    .sort((a, b) => a.min - b.min)
  if (!tiers.length || tiers.some((t) => !(t.rate > 0))) {
    toast.warning('请为每个档位填写有效的提成比例（%，大于 0）')
    return
  }
  const tiersDto = tiers.map((t) => ({
    min: yuanToFen(t.min),
    rate: rateToBp(t.rate / 100),
    label: t.label || `${t.min} 元以上 ${t.rate}%`,
  }))
  ruleSaving.value = true
  try {
    if (ruleForm.id) {
      await updateCommissionRule(ruleForm.id, { name: ruleForm.name.trim(), tiers: tiersDto })
      toast.success('规则已更新（已发放提成单不受影响）')
    } else {
      await createCommissionRule({ name: ruleForm.name.trim(), base: ruleForm.base, role: ruleForm.role, tiers: tiersDto })
      toast.success(`提成规则「${ruleForm.name.trim()}」已创建`)
    }
    resetRuleForm()
    await store.seed()
  } catch (e) {
    toast.error(errMsg(e, '规则保存失败，请稍后重试'))
  } finally {
    ruleSaving.value = false
  }
}
async function toggleRule(id: string, active: boolean) {
  try {
    await updateCommissionRule(id, { active: !active })
    toast.success(active ? '规则已停用' : '规则已启用')
    await store.seed()
  } catch (e) {
    toast.error(errMsg(e, '规则状态更新失败，请稍后重试'))
  }
}

// ---- 薪酬配置抽屉 ----
const compStaff = ref<Staff[]>([])
const compLoading = ref(false)
const compSaving = ref(false)
const compForm = reactive({ staffId: '', baseSalary: '', ruleId: '', effMonth: '' })

function currentMonthInput(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}
function openComp() {
  drawerKind.value = 'comp'
  compForm.staffId = ''
  compForm.baseSalary = ''
  compForm.ruleId = ''
  compForm.effMonth = currentMonthInput()
  compLoading.value = true
  Promise.all([listStaff(), store.seed()])
    .then(([staffRes]) => {
      compStaff.value = staffRes.data.filter((s) => s.status === '在职')
    })
    .catch((e) => toast.error(errMsg(e, '员工数据载入失败，请稍后重试')))
    .finally(() => { compLoading.value = false })
}
const staffOptions = computed(() => [
  { value: '', label: '请选择员工' },
  ...compStaff.value.map((s) => ({ value: s.staffId, label: `${s.staffName}（${s.staffId}）` })),
])
const ruleOptions = computed(() => [
  { value: '', label: '无提成（仅底薪）' },
  ...store.rules.filter((r) => r.active).map((r) => ({
    value: r.id,
    label: `${r.name} · ${ROLE_LABEL[r.role] ?? r.role} · ${BASE_LABEL_SM[r.base]}`,
  })),
])
watch(() => compForm.staffId, (sid) => {
  if (!sid) return
  const cfg = store.compConfigs.find((c) => c.staffId === sid && c.status === 'ACTIVE')
  if (cfg) {
    compForm.baseSalary = String(cfg.baseSalary)
    compForm.ruleId = cfg.commissionRuleId ?? ''
    compForm.effMonth = cfg.effectiveMonth || currentMonthInput()
  }
})
async function submitComp() {
  if (!compForm.staffId) { toast.warning('请选择员工'); return }
  if (!(Number(compForm.baseSalary) >= 0)) { toast.warning('请填写有效的月底薪（元）'); return }
  if (!compForm.effMonth) { toast.warning('请选择生效月份'); return }
  const staff = compStaff.value.find((s) => s.staffId === compForm.staffId)
  compSaving.value = true
  try {
    await saveCompConfig({
      staffId: compForm.staffId,
      staffName: staff?.staffName ?? compForm.staffId,
      storeCode: staff?.storeCode || storeCtx.currentStoreCode,
      baseSalary: yuanToFen(Number(compForm.baseSalary)),
      commissionRuleId: compForm.ruleId || null,
      effectiveMonth: `${compForm.effMonth}-01`,
    })
    toast.success(`已保存 ${staff?.staffName ?? compForm.staffId} 的薪酬配置，自 ${compForm.effMonth} 起生效（不追溯历史期间）`)
    await store.seed()
    drawerKind.value = ''
  } catch (e) {
    toast.error(errMsg(e, '薪酬配置保存失败，请稍后重试'))
  } finally {
    compSaving.value = false
  }
}
</script>

<template>
  <div class="fcm">
    <div class="fcm__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
    </div>

    <div class="fcm__body">
      <!-- 左：提成单列表 -->
      <CCard class="fcm__list" padding="none">
        <div class="filters">
          <CSelect v-model="store.filterPeriod" :options="periodOptions" width="130px" />
          <CSelect v-model="store.filterStatus" :options="statusOptions" width="140px" />
          <div class="filters__right">
            <CButton variant="secondary" size="sm" v-perm.disable="'finance:commission:edit'" @click="doGenerate" :disabled="actionBusy">
              <CIcon name="refresh" :size="14" />生成/重算试算
            </CButton>
            <CButton variant="secondary" size="sm" v-perm.disable="'finance:commission:edit'" @click="openRules">
              <CIcon name="tool" :size="14" />提成规则
            </CButton>
            <CButton variant="secondary" size="sm" v-perm.disable="'finance:commission:edit'" @click="openComp">
              <CIcon name="profile" :size="14" />薪酬配置
            </CButton>
            <CButton variant="secondary" size="sm" v-perm.disable="'finance:commission:edit'" @click="doExport" :disabled="actionBusy">
              <CIcon name="export" :size="14" />导出薪酬表
            </CButton>
          </div>
        </div>
        <div class="list">
          <div v-if="store.loading" class="empty">
            <CIcon name="loading" :size="24" class="empty__icon" />
            <div>提成数据加载中…</div>
          </div>
          <div v-else-if="store.filtered.length === 0" class="empty">
            <CIcon name="sign" :size="28" class="empty__icon" />
            <div>暂无提成单数据，可点击「生成/重算试算」按月生成</div>
          </div>
          <button
            v-for="it in store.filtered" :key="it.id"
            class="row" :class="{ 'row--active': selected?.id === it.id }"
            @click="selectedId = it.id"
          >
            <div class="row__avatar">{{ it.consultantName.slice(0, 1) }}</div>
            <div class="row__main">
              <div class="row__top">
                <span class="row__name">{{ it.consultantName }}</span>
                <CStatusPill :status="store.STATUS_PILL[it.status]" dot>
                  {{ store.STATUS_LABEL[it.status] }}
                </CStatusPill>
              </div>
              <div class="row__sub">{{ it.title }} · {{ it.period }} · {{ it.orderCount }} 单</div>
              <div class="row__sub">{{ it.ruleName }}</div>
            </div>
            <div class="row__right">
              <div class="row__amount">{{ money(it.commission) }}</div>
              <div class="row__base">基数 {{ money(it.baseAmount) }}</div>
            </div>
          </button>
        </div>
      </CCard>

      <!-- 右：详情 -->
      <CCard v-if="selected" class="fcm__detail" padding="none">
        <template #header>
          <div class="fcm__detail-head">
            <div class="fcm__avatar">{{ selected.consultantName.slice(0, 1) }}</div>
            <div class="fcm__who">
              <div class="fcm__name">{{ selected.consultantName }} · {{ selected.title }}</div>
              <div class="fcm__sub">{{ selected.period }} · 业绩口径：{{ store.BASE_LABEL[selected.base] }} · {{ selected.orderCount }} 单</div>
            </div>
            <CStatusPill :status="store.STATUS_PILL[selected.status]" dot>
              {{ store.STATUS_LABEL[selected.status] }}
            </CStatusPill>
          </div>
        </template>

        <div class="detail-body">
          <!-- 核心指标 -->
          <div class="stat-grid">
            <div class="stat">
              <div class="stat__label">业绩基数（已双签口径）</div>
              <div class="stat__value stat__value--brand">{{ money(selected.baseAmount) }}</div>
            </div>
            <div class="stat">
              <div class="stat__label">提成合计</div>
              <div class="stat__value stat__value--orange">{{ money(selected.commission) }}</div>
            </div>
            <div class="stat">
              <div class="stat__label">综合提成率</div>
              <div class="stat__value">{{ effectiveRate(selected).toFixed(2) }}%</div>
            </div>
            <div class="stat">
              <div class="stat__label">阶梯档位数</div>
              <div class="stat__value">{{ selected.tiers.length }}</div>
            </div>
          </div>

          <!-- 阶梯明细 -->
          <div class="block">
            <div class="block__title">
              <span>阶梯提成明细</span>
              <span class="block__hint">{{ selected.ruleName }}</span>
            </div>
            <div class="tier-table">
              <div class="tier-head">
                <span>档位</span>
                <span>分段金额</span>
                <span>比例</span>
                <span class="t-col-r">提成</span>
              </div>
              <div v-for="(t, i) in selected.tiers" :key="i" class="tier-row">
                <span>{{ t.label }}</span>
                <span>{{ money(t.amount) }}</span>
                <span>{{ (t.rate * 100).toFixed(0) }}%</span>
                <span class="t-col-r">{{ money(t.commission) }}</span>
              </div>
              <div class="tier-row tier-row--total">
                <span>合计</span>
                <span>{{ money(selected.baseAmount) }}</span>
                <span>—</span>
                <span class="t-col-r">{{ money(selected.commission) }}</span>
              </div>
            </div>
          </div>

          <!-- 提成排行 -->
          <div class="block">
            <div class="block__title"><span>当前筛选 Top{{ ranking.length }}</span></div>
            <CBarChart v-if="ranking.length" :items="ranking" orientation="horizontal" :height="0" :show-value="true" />
            <div v-else class="empty-inline">暂无数据</div>
          </div>

          <!-- 备注/审批信息 -->
          <div v-if="selected.remark || selected.approver || selected.paidAt" class="kv">
            <div v-if="selected.approver" class="kv__row">
              <span class="kv__k">审批人</span><span class="kv__v">{{ selected.approver }} · {{ fmtTime(selected.approvedAt) }}</span>
            </div>
            <div v-if="selected.paidAt" class="kv__row">
              <span class="kv__k">发放时间</span><span class="kv__v kv__v--success">{{ fmtTime(selected.paidAt) }}</span>
            </div>
            <div v-if="selected.remark" class="kv__row">
              <span class="kv__k">备注</span><span class="kv__v">{{ selected.remark }}</span>
            </div>
          </div>

          <!-- 操作流 -->
          <div v-if="selected.status === 'DRAFT' || selected.status === 'REJECTED'" class="actions">
            <CButton variant="primary" v-perm.disable="'finance:commission:edit'" @click="doSubmit">
              <CIcon name="check-square" :size="14" />提交审批
            </CButton>
            <span v-if="selected.status === 'REJECTED' && selected.remark" class="actions__hint">
              <CIcon name="alert" :size="12" />上次驳回原因：{{ selected.remark }}
            </span>
          </div>
          <div v-else-if="selected.status === 'SUBMITTED'" class="actions">
            <CButton variant="primary" v-perm.disable="'finance:commission:approve'" @click="doApprove">
              <CIcon name="check" :size="14" />审批通过
            </CButton>
            <CButton variant="ghost" size="sm" v-perm.disable="'finance:commission:approve'" @click="openReject">驳回</CButton>
          </div>
          <div v-else-if="selected.status === 'APPROVED'" class="actions">
            <CButton variant="primary" v-perm.disable="'finance:commission:approve'" @click="doPay">
              <CIcon name="check" :size="14" />确认发放（薪酬系统回传）
            </CButton>
            <span class="actions__hint"><CIcon name="shield" :size="12" />仅镜像状态，真实薪酬由外部通道划付</span>
          </div>
          <div v-else-if="selected.status === 'PAID'" class="paid-meta">
            <CIcon name="check" :size="12" />
            已发放{{ selected.approver ? `（审批：${selected.approver}）` : '' }}，发放时间 {{ fmtTime(selected.paidAt) }}
          </div>
        </div>
      </CCard>

      <CCard v-else class="fcm__detail fcm__detail--empty" title="提成详情" padding="lg">
        <div class="detail-empty">
          <CIcon name="sign" :size="40" class="detail-empty__icon" />
          <p>请选择一张提成单</p>
        </div>
      </CCard>
    </div>

    <!-- 抽屉：提成规则维护 -->
    <CDrawer v-if="drawerKind === 'rules'" v-model:show="drawerOpen" title="提成规则维护" size="lg">
      <div class="drw">
        <div class="drw__section">
          <div class="drw__section-title">
            <span>现有规则（{{ store.rules.length }}）</span>
            <CButton variant="secondary" size="sm" @click="openCreateRule">
              <CIcon name="plus" :size="13" />新建规则
            </CButton>
          </div>
          <div class="rule-list">
            <div v-for="r in store.rules" :key="r.id" class="rule-item">
              <div class="rule-item__info">
                <div class="rule-item__head">
                  <span class="rule-item__name">{{ r.name }}</span>
                  <CStatusPill :status="r.active ? 'success' : 'disabled'" dot>{{ r.active ? '启用中' : '已停用' }}</CStatusPill>
                </div>
                <div class="rule-item__meta">{{ ROLE_LABEL[r.role] ?? r.role }} · 口径：{{ BASE_LABEL_SM[r.base] }} · {{ r.tiers.map(t => `${t.min}元起${(t.rate*100).toFixed(1)}%`).join(' / ') }}</div>
              </div>
              <div class="rule-item__ops">
                <CButton variant="text" size="sm" @click="openEditRule(r.id)">编辑</CButton>
                <CButton variant="text" size="sm" @click="toggleRule(r.id, r.active)">{{ r.active ? '停用' : '启用' }}</CButton>
              </div>
            </div>
            <div v-if="store.rules.length === 0" class="empty-inline">暂无规则，请新建</div>
          </div>
        </div>

        <div class="drw__section">
          <div class="drw__section-title">
            <span>{{ ruleForm.id ? '编辑规则（名称 / 阶梯；岗位与口径建后不可改）' : '新建规则' }}</span>
          </div>
          <div class="form">
            <label class="field">
              <span class="field__label">规则名称 <i>*</i></span>
              <CInput v-model="ruleForm.name" placeholder="如 咨询师提成-超额累进 6/8/10/12%" />
            </label>
            <div v-if="!ruleForm.id" class="field-row">
              <label class="field">
                <span class="field__label">适用岗位 <i>*</i></span>
                <CSelect v-model="ruleForm.role" width="100%" :options="ROLE_OPTIONS" />
              </label>
              <label class="field">
                <span class="field__label">业绩口径 <i>*</i></span>
                <CSelect v-model="ruleForm.base" width="100%" :options="BASE_OPTIONS" />
              </label>
            </div>
            <div v-else class="form-tip">
              <CIcon name="info" :size="13" />岗位与业绩口径建后不可修改（历史提成单按快照口径结算）；如需调整请新建规则并在薪酬配置中切换。
            </div>
            <div class="field">
              <span class="field__label">阶梯档位（超额累进；业绩超过下限的部分按本档比例计提） <i>*</i></span>
              <div class="tier-edit">
                <div class="tier-edit__head"><span>档位名称</span><span>业绩下限（元）</span><span>提成比例（%）</span><span></span></div>
                <div v-for="(t, i) in ruleForm.tiers" :key="i" class="tier-edit__row">
                  <CInput v-model="t.label" placeholder="如 基础档（可留空自动生成）" />
                  <CInput v-model="t.min" type="number" placeholder="0" />
                  <CInput v-model="t.rate" type="number" placeholder="如 6" />
                  <CButton variant="ghost" size="sm" :disabled="ruleForm.tiers.length === 1" @click="removeTier(i)">
                    <CIcon name="delete" :size="13" />
                  </CButton>
                </div>
                <CButton variant="ghost" size="sm" @click="addTier"><CIcon name="plus" :size="13" />增加档位</CButton>
              </div>
            </div>
            <div class="form-tip">
              <CIcon name="info" :size="13" />比例按百分比填写（6 表示 6%）；金额单位为元，后端按分存储。例：0 元起 6%、3 万元起 8%、6 万元起 10%、10 万元起 12%。
            </div>
          </div>
        </div>
      </div>
      <template #footer>
        <CButton variant="secondary" @click="resetRuleForm()">清空表单</CButton>
        <CButton variant="primary" :disabled="ruleSaving" @click="submitRule">{{ ruleForm.id ? '保存修改' : '创建规则' }}</CButton>
      </template>
    </CDrawer>

    <!-- 抽屉：员工薪酬配置 -->
    <CDrawer v-else-if="drawerKind === 'comp'" v-model:show="drawerOpen" title="员工薪酬配置（底薪 + 提成规则）" size="lg">
      <div v-if="compLoading" class="state-row">
        <CIcon name="loading" :size="16" />数据加载中…
      </div>
      <div v-else class="drw">
        <div class="drw__section">
          <div class="drw__section-title"><span>当前生效配置（{{ store.compConfigs.filter(c => c.status === 'ACTIVE').length }} 人）</span></div>
          <div class="rule-list">
            <div v-for="c in store.compConfigs.filter(x => x.status === 'ACTIVE')" :key="c.compId" class="rule-item">
              <div class="rule-item__info">
                <div class="rule-item__head">
                  <span class="rule-item__name">{{ c.staffName }}</span>
                  <span class="rule-item__code">{{ c.staffId }}</span>
                </div>
                <div class="rule-item__meta">
                  底薪 ¥{{ Math.round(c.baseSalary).toLocaleString('zh-CN') }} / 月 ·
                  规则：{{ c.commissionRuleId ? (store.rules.find(r => r.id === c.commissionRuleId)?.name ?? '已停用/缺失') : '无提成（仅底薪）' }}
                  · 生效 {{ c.effectiveMonth }}
                </div>
              </div>
            </div>
            <div v-if="!store.compConfigs.some(c => c.status === 'ACTIVE')" class="empty-inline">暂无生效配置</div>
          </div>
        </div>

        <div class="drw__section">
          <div class="drw__section-title"><span>{{ compForm.staffId ? '调整 / 新增配置' : '新增配置' }}</span></div>
          <div class="form">
            <label class="field">
              <span class="field__label">员工 <i>*</i></span>
              <CSelect v-model="compForm.staffId" width="100%" :options="staffOptions" />
            </label>
            <div class="field-row">
              <label class="field">
                <span class="field__label">月底薪（元） <i>*</i></span>
                <CInput v-model="compForm.baseSalary" type="number" placeholder="如 5000" />
              </label>
              <label class="field">
                <span class="field__label">生效月份 <i>*</i></span>
                <input v-model="compForm.effMonth" type="month" class="month-input" />
              </label>
            </div>
            <label class="field">
              <span class="field__label">适用提成规则</span>
              <CSelect v-model="compForm.ruleId" width="100%" :options="ruleOptions" />
            </label>
            <div class="form-tip">
              <CIcon name="info" :size="13" />同一员工仅保留一条生效配置：保存后旧配置自动置为失效（调薪留痕，不追溯历史期间）；选择「无提成」则该员工只计底薪。提成规则可在本页「提成规则」中维护。
            </div>
          </div>
        </div>
      </div>
      <template #footer>
        <CButton variant="secondary" @click="drawerKind = ''">取消</CButton>
        <CButton variant="primary" :disabled="compSaving" @click="submitComp">保存配置</CButton>
      </template>
    </CDrawer>

    <!-- 驳回弹层 -->
    <div v-if="showReject" class="modal-mask" @click.self="showReject = false">
      <CCard class="modal" title="驳回提成单" padding="lg">
        <p class="modal__hint">驳回后提成单回到草稿状态，可调整后重新提交。</p>
        <label class="form__label">驳回原因</label>
        <CTextarea v-model="rejectReason" :rows="3" placeholder="请填写驳回原因" />
        <template #footer>
          <CButton variant="ghost" @click="showReject = false">取消</CButton>
          <CButton variant="primary" @click="confirmReject">确认驳回</CButton>
        </template>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.fcm { display: flex; flex-direction: column; gap: var(--s-lg); }
.fcm__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .fcm__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }

.fcm__body { display: grid; grid-template-columns: 420px 1fr; gap: var(--s-lg); align-items: start; }
.fcm__list { min-width: 0; }
.filters { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-md); border-bottom: 1px solid var(--c-border-light); flex-wrap: nowrap; overflow-x: auto; }
.filters__right { display: flex; align-items: center; gap: var(--s-sm); margin-left: auto; flex-shrink: 0; }
.list { max-height: 700px; overflow-y: auto; }
.empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-sm); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); font-size: var(--t-sm); }
.empty__icon { color: var(--c-text-4); }

.row {
  display: flex; align-items: center; gap: var(--s-sm); width: 100%; text-align: left;
  padding: var(--s-md) var(--s-lg); background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer;
}
.row:hover { background: var(--c-brand-soft); }
.row--active { background: var(--c-brand-soft); box-shadow: inset 3px 0 0 var(--c-brand); }
.row__avatar {
  width: 36px; height: 36px; border-radius: 50%; background: var(--c-brand-soft); color: var(--c-brand);
  display: flex; align-items: center; justify-content: center; font-weight: 700; font-size: var(--t-sm); flex-shrink: 0;
}
.row__main { flex: 1; min-width: 0; }
.row__top { display: flex; align-items: center; gap: var(--s-xs); margin-bottom: 2px; }
.row__name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.row__sub { font-size: var(--t-xs); color: var(--c-text-3); line-height: 1.5; }
.row__right { text-align: right; flex-shrink: 0; }
.row__amount { font-size: var(--t-md); font-weight: 700; color: var(--c-text); font-variant-numeric: tabular-nums; }
.row__base { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }

.fcm__detail-head { display: flex; align-items: center; gap: var(--s-md); width: 100%; }
.fcm__avatar {
  width: 48px; height: 48px; border-radius: 50%; background: var(--c-brand-soft); color: var(--c-brand);
  display: flex; align-items: center; justify-content: center; font-size: var(--t-lg); font-weight: 700;
}
.fcm__who { flex: 1; min-width: 0; }
.fcm__name { font-size: var(--t-lg); font-weight: 700; color: var(--c-text); }
.fcm__sub { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }

.detail-body { padding: var(--s-lg); display: flex; flex-direction: column; gap: var(--s-lg); }

.stat-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); }
.stat { background: var(--c-bg-right); border-radius: var(--r-md); padding: var(--s-md); }
.stat__label { font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: 4px; }
.stat__value { font-size: var(--t-md); font-weight: 700; color: var(--c-text); font-variant-numeric: tabular-nums; }
.stat__value--brand { color: var(--c-brand); }
.stat__value--orange { color: var(--c-orange-dark); }

.block { display: flex; flex-direction: column; gap: var(--s-sm); }
.block__title { display: flex; justify-content: space-between; align-items: center; font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.block__hint { font-size: var(--t-xs); color: var(--c-text-3); font-weight: 400; }

.tier-table { border: 1px solid var(--c-border-light); border-radius: var(--r-md); overflow: hidden; }
.tier-head, .tier-row {
  display: grid; grid-template-columns: 1.4fr 1fr 0.6fr 1fr;
  padding: var(--s-sm) var(--s-md); font-size: var(--t-sm); align-items: center;
}
.tier-head { background: var(--c-bg-right); color: var(--c-text-3); font-size: var(--t-xs); font-weight: 600; }
.tier-row { border-top: 1px solid var(--c-border-light); color: var(--c-text-2); }
.t-col-r { text-align: right; font-variant-numeric: tabular-nums; font-weight: 600; color: var(--c-text); }
.tier-row--total { background: var(--c-brand-soft); font-weight: 700; color: var(--c-text); }
.tier-row--total .t-col-r { color: var(--c-brand); font-size: var(--t-md); }

.empty-inline { font-size: var(--t-sm); color: var(--c-text-3); padding: var(--s-md) 0; }

.kv { display: flex; flex-direction: column; gap: var(--s-sm); background: var(--c-bg-right); border-radius: var(--r-md); padding: var(--s-md); }
.kv__row { display: flex; justify-content: space-between; gap: var(--s-md); font-size: var(--t-sm); }
.kv__k { color: var(--c-text-3); flex-shrink: 0; }
.kv__v { color: var(--c-text-2); text-align: right; word-break: break-all; }
.kv__v--success { color: var(--c-success-fg); font-weight: 600; }

.actions { display: flex; gap: var(--s-sm); align-items: center; flex-wrap: wrap; }
.actions__hint { display: inline-flex; align-items: center; gap: 4px; font-size: var(--t-xs); color: var(--c-text-3); }
.paid-meta { display: flex; align-items: center; gap: 4px; font-size: var(--t-xs); color: var(--c-success-fg); }

.detail-empty { display: flex; flex-direction: column; align-items: center; gap: var(--s-md); padding: var(--s-xxl) var(--s-lg); color: var(--c-text-3); }
.detail-empty__icon { color: var(--c-text-4); }

.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 440px; max-width: 100%; box-shadow: var(--shadow-pop); }
.modal__hint { font-size: var(--t-xs); color: var(--c-text-3); margin: 0 0 var(--s-md); line-height: 1.6; }
.form__label { display: block; font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: var(--s-xs); }

@media (max-width: 1024px) {
  .fcm__body { grid-template-columns: 1fr; }
  .stat-grid { grid-template-columns: repeat(2, 1fr); }
  .list { max-height: 360px; }
}

/* ---------- 配置抽屉（提成规则 / 薪酬配置） ---------- */
.drw { display: flex; flex-direction: column; gap: var(--s-lg); }
.drw__section { display: flex; flex-direction: column; gap: var(--s-sm); }
.drw__section-title {
  display: flex; align-items: center; justify-content: space-between; gap: var(--s-sm);
  font-size: var(--t-sm); font-weight: 600; color: var(--c-text);
}
.rule-list { display: flex; flex-direction: column; gap: var(--s-xs); }
.rule-item {
  display: flex; align-items: center; gap: var(--s-sm);
  padding: var(--s-sm) var(--s-md);
  border: 1px solid var(--c-border-light); border-radius: var(--r-md);
  background: var(--c-surface);
}
.rule-item__info { display: flex; flex-direction: column; gap: 2px; flex: 1; min-width: 0; }
.rule-item__head { display: flex; align-items: center; gap: var(--s-xs); }
.rule-item__name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.rule-item__code { font-size: var(--t-xs); color: var(--c-text-3); font-family: monospace; }
.rule-item__meta {
  font-size: var(--t-xs); color: var(--c-text-3); line-height: 1.6;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.rule-item__ops { display: flex; gap: 2px; flex-shrink: 0; }

.form { display: flex; flex-direction: column; gap: var(--s-md); }
.field { display: flex; flex-direction: column; gap: 6px; }
.field-row { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-sm); }
.field__label { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.field__label i { color: var(--c-danger-fg); font-style: normal; }
.form-tip { display: flex; align-items: flex-start; gap: 6px; font-size: var(--t-xs); color: var(--c-text-3); line-height: 1.6; }

.tier-edit { display: flex; flex-direction: column; gap: var(--s-xs); }
.tier-edit__head, .tier-edit__row {
  display: grid; grid-template-columns: 1.6fr 1fr 1fr 36px; gap: var(--s-xs); align-items: center;
}
.tier-edit__head { font-size: var(--t-xs); color: var(--c-text-3); font-weight: 600; padding: 0 2px; }

.month-input {
  width: 100%; padding: 10px;
  border: 1px solid #D1D1D9; border-radius: var(--r-sm);
  background: var(--c-surface);
  font-size: 13px; color: var(--c-text); line-height: 20px;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.month-input:focus { outline: none; border-color: #4D5AD9; box-shadow: 0 0 0 2px rgba(77, 90, 217, 0.12); }

.state-row { display: flex; align-items: center; justify-content: center; gap: var(--s-xs); padding: var(--s-xl) 0; color: var(--c-text-3); font-size: var(--t-sm); }
</style>
