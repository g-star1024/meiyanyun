<script setup lang="ts">
/* M6-06 成本分析 /m6-cost — 只读镜像，TK 成本四分类，按门店/科目归集
 * B11 期末结转区（DESIGN §4.4）：规则后台可配（随时调整）、测算本月明细弹层、
 * 执行结转、重算本月、设备资产台账抽屉。资金红线：结转只写成本镜像
 * （TK-DEPRECIATION/TK-LABOR OUT、source=SYSTEM），绝不生成实付渠道分录；
 * 封账永久不可逆，已封账月重算由后端 422 拒绝。写操作权限 finance:cost:edit。 */
import { computed, onMounted, reactive, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CSelect from '@/components/CSelect.vue'
import CInput from '@/components/CInput.vue'
import CTextarea from '@/components/CTextarea.vue'
import CDrawer from '@/components/CDrawer.vue'
import CDonutChart from '@/components/CDonutChart.vue'
import { useFinCostStore, type CostSubject } from '@/stores/finCost'
import {
  useFinCarryStore, yuanToFen, currentMonth,
  type CarryRule, type CarryPreviewLine, type CarryRunResult, type FinAsset,
} from '@/stores/finCarry'
import { useStoreContext } from '@/stores/storeContext'
import { useToast } from '@/composables/useToast'
import { useAuthStore } from '@/stores/auth'
import { exportCostCsv } from '@/api/finance'

const store = useFinCostStore()
const carry = useFinCarryStore()
const storeCtx = useStoreContext()
const toast = useToast()
const auth = useAuthStore()
const canExport = computed(() => auth.can('finance:export'))

function errMsg(e: unknown, fallback: string): string {
  const anyE = e as { response?: { data?: { message?: string } }; message?: string }
  return anyE?.response?.data?.message || anyE?.message || fallback
}

onMounted(async () => {
  store.init()
  if (!storeCtx.loaded) {
    try { await storeCtx.loadStores() } catch { /* 门店名回落编码 */ }
  }
  try {
    await carry.seed()
  } catch (e) {
    toast.error(errMsg(e, '期末结转配置载入失败，请稍后重试'))
  }
})

function yuan(n: number | null | undefined): string {
  return `¥${(n ?? 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}
function storeNameOf(code: string | null): string {
  if (!code) return '全部门店'
  return storeCtx.stores.find((s) => s.storeCode === code)?.storeName || code
}

const selectedId = ref<string | null>(null)
const selected = computed(() => store.filtered.find((r) => r.id === selectedId.value) ?? store.filtered[0] ?? null)

const SUBJECT_PILL: Record<CostSubject, 'primary' | 'info' | 'danger' | 'warning'> = {
  MATERIAL: 'primary', DEPRECIATION: 'info', LOSS: 'danger', LABOR: 'warning',
}

const kpis = computed(() => [
  { label: '成本合计', icon: 'finance', value: `¥${store.totalCost.toLocaleString('zh-CN')}`, tone: 'brand' as const, sub: '本期 TK 成本镜像' },
  { label: '耗材成本', icon: 'package', value: `¥${store.totalMaterial.toLocaleString('zh-CN')}`, tone: 'text' as const, sub: `${pct(store.totalMaterial)}%` },
  { label: '设备折旧', icon: 'settings', value: `¥${store.totalDepreciation.toLocaleString('zh-CN')}`, tone: 'teal' as const, sub: `${pct(store.totalDepreciation)}%` },
  { label: '报损 / 人工', icon: 'alert', value: `¥${store.totalLoss.toLocaleString('zh-CN')} / ¥${store.totalLabor.toLocaleString('zh-CN')}`, tone: store.totalLoss ? 'warning' as const : 'text' as const, sub: `人工 ${pct(store.totalLabor)}%` },
])

function pct(n: number) {
  return store.totalCost ? Math.round((n / store.totalCost) * 100) : 0
}

const donutData = computed(() => [
  { label: '耗材', value: store.totalMaterial, color: 'var(--c-series-1)' },
  { label: '折旧', value: store.totalDepreciation, color: 'var(--c-series-2)' },
  { label: '报损', value: store.totalLoss, color: 'var(--c-series-5)' },
  { label: '人工', value: store.totalLabor, color: 'var(--c-series-4)' },
])

async function exportCsv() {
  if (!canExport.value) return
  // 优先后端运维报表（门店 × 月份四类成本汇总，门店域同源收敛）；服务不可达时诚实回落本地明细
  try {
    await exportCostCsv()
    return
  } catch {
    /* 回落本地导出 */
  }
  const head = '科目,明细项,门店,金额,来源,日期,备注\n'
  const rows = store.filtered.map((r) =>
    [store.SUBJECT_LABEL[r.subject], r.itemName, r.store, r.amount, r.source, r.occurredAt, r.memo ?? ''].join(','),
  ).join('\n')
  const blob = new Blob(['﻿' + head + rows], { type: 'text/csv;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `成本分析-${new Date().toISOString().slice(0, 10)}.csv`
  a.click()
  URL.revokeObjectURL(a.href)
}

// ==================== B11 期末结转 ====================
const actionBusy = ref(false)

// ---- 测算本月（明细弹层） ----
const showPreview = ref(false)
const previewLoading = ref(false)
const previewMonth = ref(currentMonth())
const previewLines = ref<CarryPreviewLine[]>([])
const previewTotals = ref({ total: 0, executed: 0, pending: 0, pendingCount: 0, executedCount: 0 })
/** native month 输入（yyyy-MM）<-> 后端契约 yyyy-MM-01 */
const previewMonthInput = computed({
  get: () => previewMonth.value.slice(0, 7),
  set: (v: string) => { if (v) previewMonth.value = `${v}-01` },
})

async function loadPreview(closeOnFail: boolean) {
  previewLoading.value = true
  try {
    const r = await carry.preview(previewMonth.value)
    previewLines.value = r.lines
    previewTotals.value = {
      total: r.total, executed: r.executed, pending: r.pending,
      pendingCount: r.pendingCount, executedCount: r.executedCount,
    }
  } catch (e) {
    toast.error(errMsg(e, '本月结转测算失败，请稍后重试'))
    if (closeOnFail) showPreview.value = false
  } finally {
    previewLoading.value = false
  }
}
async function openPreview() {
  showPreview.value = true
  await loadPreview(true)
}

// ---- 执行结转 / 重算本月 ----
async function doRun(recalc: boolean, month?: string) {
  if (actionBusy.value) return
  const m = month ?? currentMonth()
  if (recalc) {
    const ok = window.confirm(
      `重算 ${m.slice(0, 7)} 将先删除该月系统自动结转的成本镜像行（不影响手工账与已实付分录）后重新计提；若该月已有门店封账将被拒绝。确认继续？`,
    )
    if (!ok) return
  }
  actionBusy.value = true
  try {
    const r: CarryRunResult = await carry.run(m, recalc)
    showRunResult(r)
    showPreview.value = false
    await carry.seed()
    try { await store.init() } catch { /* 成本镜像刷新失败不阻断结转结果提示 */ }
  } catch (e) {
    toast.error(errMsg(e, recalc ? '重算失败（该月可能已有门店封账，封账不可逆）' : '结转执行失败，请稍后重试'))
  } finally {
    actionBusy.value = false
  }
}

function showRunResult(r: CarryRunResult) {
  const parts = [`${r.message}`, `新增计提 ${r.postedCount} 项 / ${yuan(r.postedAmount)}`]
  if (r.skippedCount > 0) {
    const closed = r.skipped.filter((s) => s.closed).length
    const dup = r.skipped.filter((s) => s.duplicated).length
    parts.push(`跳过 ${r.skippedCount} 项（已结转 ${dup}、闭期 ${closed}、其他 ${r.skippedCount - closed - dup}）`)
  }
  toast.success(parts.join('；'))
}

// ---- 规则维护（弹层） ----
const showRule = ref(false)
const ruleSaving = ref(false)
const ruleForm = reactive({
  id: '',
  name: '',
  costType: 'DEPRECIATION' as CarryRule['costType'],
  calcMode: 'ASSET' as CarryRule['calcMode'],
  fixedAmount: '',
  storeCode: '',
  enabled: true,
  runOnClose: true,
  remark: '',
})
const COST_TYPE_OPTIONS = [
  { value: 'DEPRECIATION', label: '设备折旧（TK-DEPRECIATION）' },
  { value: 'LABOR', label: '人工成本（TK-LABOR）' },
]
const CALC_MODE_OPTIONS = [
  { value: 'ASSET', label: '资产折旧（台账直线法）' },
  { value: 'BASE_SALARY', label: '底薪合计（薪酬配置）' },
  { value: 'COMMISSION', label: '已审批提成（薪酬镜像）' },
  { value: 'FIXED', label: '固定额（须指定门店与金额）' },
]
const ruleStoreOptions = computed(() => [
  { value: '', label: '全部门店通用' },
  ...storeCtx.stores.map((s) => ({ value: s.storeCode, label: `${s.storeName}（${s.storeCode}）` })),
])

function resetRuleForm() {
  ruleForm.id = ''
  ruleForm.name = ''
  ruleForm.costType = 'DEPRECIATION'
  ruleForm.calcMode = 'ASSET'
  ruleForm.fixedAmount = ''
  ruleForm.storeCode = ''
  ruleForm.enabled = true
  ruleForm.runOnClose = true
  ruleForm.remark = ''
}
function openCreateRule() {
  resetRuleForm()
  showRule.value = true
}
function openEditRule(r: CarryRule) {
  ruleForm.id = r.id
  ruleForm.name = r.name
  ruleForm.costType = r.costType
  ruleForm.calcMode = r.calcMode
  ruleForm.fixedAmount = r.fixedAmount == null ? '' : String(r.fixedAmount)
  ruleForm.storeCode = r.storeCode ?? ''
  ruleForm.enabled = r.enabled
  ruleForm.runOnClose = r.runOnClose
  ruleForm.remark = r.remark
  showRule.value = true
}
async function submitRule() {
  if (!ruleForm.name.trim()) { toast.warning('请填写规则名称'); return }
  if (ruleForm.calcMode === 'FIXED') {
    if (!(Number(ruleForm.fixedAmount) > 0)) { toast.warning('固定额规则须填写大于 0 的月计提金额（元）'); return }
    if (!ruleForm.storeCode) { toast.warning('固定额规则必须指定单一门店'); return }
  }
  if (ruleForm.costType === 'DEPRECIATION' && ruleForm.calcMode !== 'ASSET' && ruleForm.calcMode !== 'FIXED') {
    toast.warning('设备折旧仅支持「资产折旧」或「固定额」取数方式'); return
  }
  if (ruleForm.costType === 'LABOR' && ruleForm.calcMode === 'ASSET') {
    toast.warning('人工成本不可使用资产折旧取数'); return
  }
  ruleSaving.value = true
  try {
    const payload = {
      ruleName: ruleForm.name.trim(),
      costType: ruleForm.costType,
      calcMode: ruleForm.calcMode,
      fixedAmount: ruleForm.calcMode === 'FIXED' ? yuanToFen(Number(ruleForm.fixedAmount)) : null,
      storeCode: ruleForm.storeCode || null,
      enabled: ruleForm.enabled,
      runOnClose: ruleForm.runOnClose,
      remark: ruleForm.remark.trim() || null,
    }
    if (ruleForm.id) {
      await carry.updateRule(ruleForm.id, payload)
      toast.success('结转规则已更新（停用/调整不追溯已结转期间）')
    } else {
      await carry.createRule(payload)
      toast.success(`结转规则「${payload.ruleName}」已创建`)
    }
    showRule.value = false
    resetRuleForm()
  } catch (e) {
    toast.error(errMsg(e, '规则保存失败，请稍后重试'))
  } finally {
    ruleSaving.value = false
  }
}
async function toggleRule(r: CarryRule) {
  try {
    await carry.toggleRule(r.id, r.enabled)
    toast.success(r.enabled ? '规则已停用（不追溯已结转期间）' : '规则已启用')
  } catch (e) {
    toast.error(errMsg(e, '规则状态更新失败，请稍后重试'))
  }
}
async function toggleRunOnClose(r: CarryRule) {
  try {
    await carry.toggleRunOnClose(r.id, r.runOnClose)
    toast.success(r.runOnClose ? '已关闭封账自动结转' : '已开启封账自动结转（月结封账前自动执行）')
  } catch (e) {
    toast.error(errMsg(e, '封账自动结转设置更新失败，请稍后重试'))
  }
}

// ---- 设备资产台账（抽屉 + 新增/处置） ----
const drawerOpen = ref(false)
const assetSaving = ref(false)
const assetForm = reactive({
  name: '', storeCode: '', originalValue: '', salvageRate: '5',
  usefulMonths: '120', startMonth: currentMonth().slice(0, 7),
})
const assetStoreOptions = computed(() => [
  { value: '', label: '请选择门店' },
  ...storeCtx.stores.map((s) => ({ value: s.storeCode, label: `${s.storeName}（${s.storeCode}）` })),
])
const inUseAssets = computed<FinAsset[]>(() => carry.assets.filter((a) => a.status === 'IN_USE'))
const disposedAssets = computed<FinAsset[]>(() => carry.assets.filter((a) => a.status === 'DISPOSED'))
/** 新增表单内资产月折旧预估（元，与后端同一直线法公式） */
const assetPreviewDep = computed(() => {
  const val = Number(assetForm.originalValue)
  const rate = Number(assetForm.salvageRate)
  const months = Number(assetForm.usefulMonths)
  if (!(val > 0) || !(months > 0)) return 0
  return Math.round(val * (100 - (rate || 0)) / 100 / months * 100) / 100
})

async function openAssets() {
  drawerOpen.value = true
  try {
    await carry.reloadAssets()
  } catch (e) {
    toast.error(errMsg(e, '资产台账载入失败，请稍后重试'))
  }
}
function resetAssetForm() {
  assetForm.name = ''
  assetForm.storeCode = ''
  assetForm.originalValue = ''
  assetForm.salvageRate = '5'
  assetForm.usefulMonths = '120'
  assetForm.startMonth = currentMonth().slice(0, 7)
}
async function submitAsset() {
  if (!assetForm.name.trim()) { toast.warning('请填写设备名称'); return }
  if (!assetForm.storeCode) { toast.warning('请选择所属门店'); return }
  if (!(Number(assetForm.originalValue) > 0)) { toast.warning('请填写有效的设备原值（元）'); return }
  if (!(Number(assetForm.usefulMonths) > 0)) { toast.warning('请填写有效的折旧月限'); return }
  if (!assetForm.startMonth) { toast.warning('请选择起折月份'); return }
  assetSaving.value = true
  try {
    await carry.addAsset({
      assetName: assetForm.name.trim(),
      storeCode: assetForm.storeCode,
      originalValue: yuanToFen(Number(assetForm.originalValue)),
      salvageRate: Math.round(Number(assetForm.salvageRate) || 0),
      usefulMonths: Math.round(Number(assetForm.usefulMonths)),
      startMonth: `${assetForm.startMonth}-01`,
    })
    toast.success(`设备「${assetForm.name.trim()}」已入台账，自 ${assetForm.startMonth} 起按月计提折旧`)
    resetAssetForm()
  } catch (e) {
    toast.error(errMsg(e, '资产入账失败，请稍后重试'))
  } finally {
    assetSaving.value = false
  }
}
async function disposeAssetItem(a: FinAsset) {
  const ok = window.confirm(`确认处置设备「${a.name}」？处置后自处置月起停折（状态不可逆）。`)
  if (!ok) return
  try {
    await carry.dispose(a.id)
    toast.success(`设备「${a.name}」已处置，自本月起停折`)
  } catch (e) {
    toast.error(errMsg(e, '资产处置失败，请稍后重试'))
  }
}
</script>

<template>
  <div class="fc">
    <div class="fc__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :sub="k.sub" :icon="k.icon" />
    </div>

    <!-- B11 期末结转：规则后台可配（随时调整）；测算/执行/重算；资产台账入口 -->
    <CCard class="cc" padding="lg">
      <div class="cc__head">
        <div class="cc__title">
          <CIcon name="check-square" :size="16" />
          <span>期末结转（月结成本自动化）</span>
          <span class="cc__hint">启用规则 {{ carry.enabledRules.length }} / {{ carry.rules.length }} 条</span>
        </div>
        <div class="cc__ops">
          <CButton variant="secondary" size="sm" @click="openAssets">
            <CIcon name="box" :size="13" />资产台账
          </CButton>
          <CButton variant="secondary" size="sm" @click="openCreateRule" v-perm.disable="'finance:cost:edit'">
            <CIcon name="plus" :size="13" />结转规则
          </CButton>
          <CButton variant="secondary" size="sm" :disabled="actionBusy" @click="doRun(false)" v-perm.disable="'finance:cost:edit'">
            <CIcon name="check" :size="13" />执行结转
          </CButton>
          <CButton variant="secondary" size="sm" :disabled="actionBusy" @click="doRun(true)" v-perm.disable="'finance:cost:edit'">
            <CIcon name="refresh" :size="13" />重算本月
          </CButton>
          <CButton variant="primary" size="sm" @click="openPreview">
            <CIcon name="dashboard" :size="13" />测算本月
          </CButton>
        </div>
      </div>

      <div class="rule-list">
        <div v-for="r in carry.rules" :key="r.id" class="rule-item">
          <div class="rule-item__info">
            <div class="rule-item__head">
              <span class="rule-item__name">{{ r.name }}</span>
              <CStatusPill :status="r.enabled ? 'success' : 'disabled'" dot>{{ r.enabled ? '启用中' : '已停用' }}</CStatusPill>
            </div>
            <div class="rule-item__meta">
              {{ carry.COST_TYPE_LABEL[r.costType] }} · {{ carry.CALC_MODE_LABEL[r.calcMode] }}
              <template v-if="r.calcMode === 'FIXED'"> · {{ yuan(r.fixedAmount) }}/月</template>
              · {{ storeNameOf(r.storeCode) }} · 封账自动结转：<b>{{ r.runOnClose ? '开' : '关' }}</b>
            </div>
            <div v-if="r.remark" class="rule-item__meta">{{ r.remark }}</div>
          </div>
          <div class="rule-item__ops">
            <CButton variant="text" size="sm" @click="openEditRule(r)" v-perm.disable="'finance:cost:edit'">编辑</CButton>
            <CButton variant="text" size="sm" @click="toggleRule(r)" v-perm.disable="'finance:cost:edit'">{{ r.enabled ? '停用' : '启用' }}</CButton>
            <CButton variant="text" size="sm" @click="toggleRunOnClose(r)" v-perm.disable="'finance:cost:edit'">{{ r.runOnClose ? '关闭封账自动' : '开启封账自动' }}</CButton>
          </div>
        </div>
        <div v-if="carry.rules.length === 0" class="empty-inline">暂无结转规则，点击「结转规则」新建；规则后台可配、随时调整（停用/调整不追溯已结转期间）。</div>
      </div>

      <div class="cc__note">
        <CIcon name="shield" :size="13" />
        资金红线：结转只生成成本镜像（TK-折旧/TK-人工，来源「系统自动」），不产生任何实付渠道分录；
        底薪/提成取数自薪酬系统只读镜像，资产折旧取数自设备台账直线法；已封账月份不可重算。
      </div>
    </CCard>

    <CCard class="fc__toolbar" padding="none">
      <div class="fc__tools">
        <CSelect v-model="store.filterSubject" :options="[{ value: 'ALL', label: '全部科目' }, { value: 'MATERIAL', label: '耗材' }, { value: 'DEPRECIATION', label: '折旧' }, { value: 'LOSS', label: '报损' }, { value: 'LABOR', label: '人工' }]" />
        <CSelect v-model="store.filterStore" :options="[{ value: 'ALL', label: '全部门店' }, ...store.stores.map((s) => ({ value: s, label: s }))]" />
        <CButton class="fc__tools-export" variant="secondary" size="sm" :disabled="!canExport" @click="exportCsv">
          <CIcon name="export" :size="14" />导出
        </CButton>
      </div>
    </CCard>

    <div class="fc__body">
      <CCard class="fc__list" padding="none">
        <div class="list-head">
          <span class="list-head__title">成本明细（只读镜像）<span class="list-head__hint">{{ store.filtered.length }} 笔</span></span>
        </div>
        <div class="cost-list">
          <button
            v-for="r in store.filtered" :key="r.id"
            class="cost-row" :class="{ 'cost-row--active': selected?.id === r.id }"
            @click="selectedId = r.id"
          >
            <div class="cost-row__top">
              <CStatusPill :status="SUBJECT_PILL[r.subject]" dot>{{ store.SUBJECT_LABEL[r.subject] }}</CStatusPill>
              <span class="cost-row__amount">¥{{ r.amount.toLocaleString('zh-CN') }}</span>
            </div>
            <div class="cost-row__name">{{ r.itemName }}</div>
            <div class="cost-row__sub">{{ r.store }} · {{ r.source }} · {{ r.occurredAt }}</div>
          </button>
        </div>
      </CCard>

      <div class="fc__right">
        <CCard v-if="selected" padding="lg">
          <div class="det-head">
            <div>
              <h3 class="det-head__name">{{ selected.itemName }}</h3>
              <div class="det-head__sub">{{ selected.store }} · {{ selected.occurredAt }}</div>
            </div>
            <CStatusPill :status="SUBJECT_PILL[selected.subject]" dot>{{ store.SUBJECT_LABEL[selected.subject] }}</CStatusPill>
          </div>
          <div class="det-amount">¥{{ selected.amount.toLocaleString('zh-CN') }}</div>
          <dl class="det-meta">
            <div><dt>成本科目</dt><dd>{{ store.SUBJECT_LABEL[selected.subject] }}（TK）</dd></div>
            <div><dt>数据来源</dt><dd>{{ selected.source }}</dd></div>
            <div><dt>所属门店</dt><dd>{{ selected.store }}</dd></div>
            <div><dt>发生日期</dt><dd>{{ selected.occurredAt }}</dd></div>
          </dl>
          <div v-if="selected.memo" class="det-memo">{{ selected.memo }}</div>
          <div class="mirror-note"><CIcon name="shield" :size="13" />成本数据单向镜像自库存/设备/报损/薪酬系统，财务域不可修改。</div>
        </CCard>

        <CCard padding="lg">
          <div class="chart-title"><CIcon name="dashboard" :size="14" />成本结构</div>
          <div class="chart-wrap">
            <CDonutChart :data="donutData" :center-label="`¥${store.totalCost.toLocaleString('zh-CN')}`" center-sub="成本合计" />
          </div>
        </CCard>
      </div>
    </div>

    <CCard padding="lg">
      <div class="chart-title"><CIcon name="store" :size="14" />门店成本对比</div>
      <div class="store-table">
        <div class="store-table__head">
          <span>门店</span><span>耗材</span><span>折旧</span><span>报损</span><span>人工</span><span>合计</span>
        </div>
        <div v-for="b in store.byStore" :key="b.store" class="store-table__row">
          <span class="st-name">{{ b.store }}</span>
          <span>¥{{ b.material.toLocaleString('zh-CN') }}</span>
          <span>¥{{ b.depreciation.toLocaleString('zh-CN') }}</span>
          <span>¥{{ b.loss.toLocaleString('zh-CN') }}</span>
          <span>¥{{ b.labor.toLocaleString('zh-CN') }}</span>
          <span class="st-total">¥{{ b.total.toLocaleString('zh-CN') }}</span>
        </div>
      </div>
    </CCard>

    <!-- B11 结转测算（dry-run 明细弹层） -->
    <div v-if="showPreview" class="modal-mask" @click.self="showPreview = false">
      <CCard class="modal modal--wide" title="期末结转测算（仅试算，不落账）" padding="lg">
        <div class="pv">
          <div class="pv__bar">
            <span class="field__label">结转月份</span>
            <input v-model="previewMonthInput" type="month" class="month-input" @change="loadPreview(false)" />
            <span class="pv__tip"><CIcon name="info" :size="13" />按后端成本镜像口径（occurredAt 落当月）试算</span>
          </div>

          <div v-if="previewLoading" class="state-row"><CIcon name="loading" :size="16" />测算中…</div>
          <template v-else>
            <div class="stat-grid">
              <div class="stat">
                <div class="stat__label">本月应计提合计</div>
                <div class="stat__value stat__value--brand">{{ yuan(previewTotals.total) }}</div>
              </div>
              <div class="stat">
                <div class="stat__label">已结转（{{ previewTotals.executedCount }} 项）</div>
                <div class="stat__value" style="color: var(--c-success-fg)">{{ yuan(previewTotals.executed) }}</div>
              </div>
              <div class="stat">
                <div class="stat__label">待结转（{{ previewTotals.pendingCount }} 项）</div>
                <div class="stat__value stat__value--orange">{{ yuan(previewTotals.pending) }}</div>
              </div>
            </div>

            <div class="pv__table">
              <div class="pv__row pv__row--head">
                <span>门店</span><span>规则 / 取数说明</span><span>成本类型</span><span>金额</span><span>状态</span>
              </div>
              <div v-for="l in previewLines" :key="`${l.ruleId}-${l.storeCode}`" class="pv__row">
                <span class="pv__store">{{ l.storeName || storeNameOf(l.storeCode) }}</span>
                <span>
                  <div class="pv__rule">{{ l.ruleName }}</div>
                  <div class="pv__basis">{{ l.basis }}</div>
                </span>
                <span>{{ carry.COST_TYPE_LABEL[l.costType] }}</span>
                <span class="pv__amount">{{ yuan(l.amount) }}</span>
                <span>
                  <CStatusPill :status="l.executed ? 'success' : 'warning'" dot>{{ l.executed ? '已结转' : '待结转' }}</CStatusPill>
                </span>
              </div>
              <div v-if="previewLines.length === 0" class="empty-inline">本月无任何启用规则的应计提项</div>
            </div>
          </template>
        </div>
        <template #footer>
          <CButton variant="ghost" :disabled="actionBusy" @click="showPreview = false">关闭</CButton>
          <CButton variant="secondary" :disabled="actionBusy" @click="doRun(true, previewMonth)" v-perm.disable="'finance:cost:edit'">重算本月（已结转先冲回）</CButton>
          <CButton variant="primary" :disabled="actionBusy" @click="doRun(false, previewMonth)" v-perm.disable="'finance:cost:edit'">
            {{ previewTotals.pendingCount ? `执行结转（待结转 ${previewTotals.pendingCount} 项）` : '执行结转' }}
          </CButton>
        </template>
      </CCard>
    </div>

    <!-- B11 结转规则维护弹层 -->
    <div v-if="showRule" class="modal-mask" @click.self="showRule = false">
      <CCard class="modal" :title="ruleForm.id ? '编辑结转规则' : '新建结转规则'" padding="lg">
        <div class="form">
          <label class="field">
            <span class="field__label">规则名称 <i>*</i></span>
            <CInput v-model="ruleForm.name" placeholder="如 门店设备月折旧（资产台账直线法）" />
          </label>
          <div class="field-row">
            <label class="field">
              <span class="field__label">成本类型 <i>*</i></span>
              <CSelect v-model="ruleForm.costType" width="100%" :options="COST_TYPE_OPTIONS" />
            </label>
            <label class="field">
              <span class="field__label">取数方式 <i>*</i></span>
              <CSelect v-model="ruleForm.calcMode" width="100%" :options="CALC_MODE_OPTIONS" />
            </label>
          </div>
          <div v-if="ruleForm.calcMode === 'FIXED'" class="field-row">
            <label class="field">
              <span class="field__label">月计提金额（元） <i>*</i></span>
              <CInput v-model="ruleForm.fixedAmount" type="number" placeholder="如 3000" />
            </label>
            <label class="field">
              <span class="field__label">适用门店 <i>*</i></span>
              <CSelect v-model="ruleForm.storeCode" width="100%" :options="ruleStoreOptions.filter((o) => o.value !== '')" />
            </label>
          </div>
          <div v-else class="field">
            <span class="field__label">适用门店</span>
            <CSelect v-model="ruleForm.storeCode" width="100%" :options="ruleStoreOptions" />
          </div>
          <div class="field-row">
            <label class="ack">
              <input v-model="ruleForm.enabled" type="checkbox" />
              <span>规则启用（停用后不再计提，不追溯已结转期间）</span>
            </label>
            <label class="ack">
              <input v-model="ruleForm.runOnClose" type="checkbox" />
              <span>月结封账前自动执行本规则</span>
            </label>
          </div>
          <label class="field">
            <span class="field__label">备注</span>
            <CTextarea v-model="ruleForm.remark" :rows="2" placeholder="取数口径、调整说明等（可选）" />
          </label>
          <div class="form-tip">
            <CIcon name="info" :size="13" />
            取数方式：「资产折旧」按设备台账直线法自动汇总当月在用设备；「底薪合计」「已审批提成」取薪酬系统只读镜像；「固定额」须指定单一门店与月计提金额。设备折旧仅支持资产折旧/固定额，人工成本不适用资产折旧。
          </div>
        </div>
        <template #footer>
          <CButton variant="ghost" :disabled="ruleSaving" @click="showRule = false">取消</CButton>
          <CButton variant="primary" :disabled="ruleSaving" @click="submitRule" v-perm.disable="'finance:cost:edit'">
            {{ ruleForm.id ? '保存修改' : '创建规则' }}
          </CButton>
        </template>
      </CCard>
    </div>

    <!-- B11 设备资产台账抽屉 -->
    <CDrawer v-model:show="drawerOpen" title="设备资产台账（折旧取数源）" size="lg">
      <div class="drw">
        <div class="drw__section">
          <div class="drw__section-title">
            <span>在用资产（{{ inUseAssets.length }} 台，按月直线折旧）</span>
          </div>
          <div class="ast">
            <div class="ast__head">
              <span>设备</span><span>门店</span><span>原值</span><span>月折旧</span><span>起折月</span><span></span>
            </div>
            <div v-for="a in inUseAssets" :key="a.id" class="ast__row">
              <span>{{ a.name }}</span>
              <span>{{ storeNameOf(a.storeCode) }}</span>
              <span class="ast__num">{{ yuan(a.originalValue) }}</span>
              <span class="ast__num">{{ yuan(a.monthlyDep) }}</span>
              <span class="ast__num">{{ a.startMonth }}</span>
              <span class="ast__ops">
                <CButton variant="text" size="sm" @click="disposeAssetItem(a)" v-perm.disable="'finance:cost:edit'">处置</CButton>
              </span>
            </div>
            <div v-if="inUseAssets.length === 0" class="empty-inline">暂无在用设备</div>
          </div>
        </div>

        <div v-if="disposedAssets.length > 0" class="drw__section">
          <div class="drw__section-title"><span>已处置资产（{{ disposedAssets.length }} 台，自处置月起停折）</span></div>
          <div class="ast">
            <div class="ast__head">
              <span>设备</span><span>门店</span><span>原值</span><span>月折旧</span><span>起折月</span><span>状态</span>
            </div>
            <div v-for="a in disposedAssets" :key="a.id" class="ast__row ast__row--off">
              <span>{{ a.name }}</span>
              <span>{{ storeNameOf(a.storeCode) }}</span>
              <span class="ast__num">{{ yuan(a.originalValue) }}</span>
              <span class="ast__num">{{ yuan(a.monthlyDep) }}</span>
              <span class="ast__num">{{ a.startMonth }}</span>
              <span><CStatusPill status="disabled" dot>已处置</CStatusPill></span>
            </div>
          </div>
        </div>

        <div class="drw__section">
          <div class="drw__section-title"><span>新增设备入账</span></div>
          <div class="form">
            <label class="field">
              <span class="field__label">设备名称 <i>*</i></span>
              <CInput v-model="assetForm.name" placeholder="如 皮秒激光治疗仪 P-2026" />
            </label>
            <div class="field-row">
              <label class="field">
                <span class="field__label">所属门店 <i>*</i></span>
                <CSelect v-model="assetForm.storeCode" width="100%" :options="assetStoreOptions" />
              </label>
              <label class="field">
                <span class="field__label">设备原值（元） <i>*</i></span>
                <CInput v-model="assetForm.originalValue" type="number" placeholder="如 5000000" />
              </label>
            </div>
            <div class="field-row">
              <label class="field">
                <span class="field__label">残值率（%）</span>
                <CInput v-model="assetForm.salvageRate" type="number" placeholder="默认 5" />
              </label>
              <label class="field">
                <span class="field__label">折旧月限（月） <i>*</i></span>
                <CInput v-model="assetForm.usefulMonths" type="number" placeholder="如 120（10 年）" />
              </label>
            </div>
            <label class="field">
              <span class="field__label">起折月份 <i>*</i></span>
              <input v-model="assetForm.startMonth" type="month" class="month-input" />
            </label>
            <div class="form-tip">
              <CIcon name="info" :size="13" />
              月折旧 = 原值 ×（1 - 残值率）÷ 月限（直线法，前后端同公式）；当前表单预估月折旧
              <b>{{ yuan(assetPreviewDep) }}</b>。设备自起折月起按月计提折旧，处置当月起停折。
            </div>
          </div>
        </div>
      </div>
      <template #footer>
        <CButton variant="secondary" @click="drawerOpen = false">关闭</CButton>
        <CButton variant="primary" :disabled="assetSaving" @click="submitAsset" v-perm.disable="'finance:cost:edit'">
          设备入账
        </CButton>
      </template>
    </CDrawer>
  </div>
</template>

<style scoped>
.fc { display: flex; flex-direction: column; gap: var(--s-lg); }
.fc__head { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .fc__head { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }
:deep(.ckpi) { min-width: 0; }

.fc__body { display: grid; grid-template-columns: 380px 1fr; gap: var(--s-lg); align-items: start; }
.fc__list { min-width: 0; }
.list-head { display: flex; justify-content: space-between; align-items: center; gap: var(--s-sm); padding: var(--s-md) var(--s-lg); border-bottom: 1px solid var(--c-border-light); flex-wrap: nowrap; overflow-x: auto; }
.list-head__title { font-size: var(--t-sm); font-weight: 700; display: flex; align-items: baseline; gap: var(--s-sm); flex-shrink: 0; white-space: nowrap; margin-right: auto; }
.list-head__hint { font-size: var(--t-xs); color: var(--c-text-3); font-weight: 400; }
.fc__toolbar { flex-shrink: 0; }
.fc__tools { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-md); flex-wrap: nowrap; }
.fc__tools > * { flex-shrink: 0; }
.fc__tools :deep(.cselect) { width: 130px; }
.fc__tools-export { margin-left: auto; white-space: nowrap; }
.cost-list { max-height: 560px; overflow-y: auto; }
.cost-row { display: block; width: 100%; text-align: left; padding: var(--s-md) var(--s-lg); background: none; border: none; border-bottom: 1px solid var(--c-border-light); cursor: pointer; border-left: 3px solid transparent; }
.cost-row:hover { background: var(--c-brand-soft); }
.cost-row--active { background: var(--c-brand-soft); border-left-color: var(--c-brand); }
.cost-row__top { display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px; }
.cost-row__amount { font-size: var(--t-md); font-weight: 700; font-variant-numeric: tabular-nums; }
.cost-row__name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.cost-row__sub { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }

.fc__right { display: flex; flex-direction: column; gap: var(--s-lg); min-width: 0; }
.det-head { display: flex; justify-content: space-between; align-items: flex-start; }
.det-head__name { margin: 0; font-size: var(--t-lg); font-weight: 700; }
.det-head__sub { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; }
.det-amount { font-size: 28px; font-weight: 800; color: var(--c-brand); margin: var(--s-md) 0; font-variant-numeric: tabular-nums; }
.det-meta { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-sm) var(--s-lg); margin: 0 0 var(--s-md); }
.det-meta div { display: flex; flex-direction: column; gap: 2px; }
.det-meta dt { font-size: var(--t-xs); color: var(--c-text-3); }
.det-meta dd { margin: 0; font-size: var(--t-sm); font-weight: 600; }
.det-memo { font-size: var(--t-xs); color: var(--c-text-2); background: var(--c-bg-right); padding: var(--s-sm); border-radius: var(--r-sm); margin-bottom: var(--s-sm); }
.mirror-note { display: flex; align-items: center; gap: 4px; font-size: var(--t-xs); color: var(--c-text-3); }

.chart-title { display: flex; align-items: center; gap: 6px; font-size: var(--t-sm); font-weight: 700; margin-bottom: var(--s-md); }
.chart-wrap { display: flex; justify-content: center; }

.store-table { font-size: var(--t-sm); }
.store-table__head, .store-table__row { display: grid; grid-template-columns: 1.4fr repeat(5, 1fr); gap: var(--s-sm); padding: var(--s-sm) 0; align-items: center; }
.store-table__head { font-size: var(--t-xs); color: var(--c-text-3); border-bottom: 1px solid var(--c-border-light); font-weight: 600; }
.store-table__row { border-bottom: 1px solid var(--c-border-light); font-variant-numeric: tabular-nums; }
.st-name { font-weight: 600; }
.st-total { font-weight: 700; color: var(--c-brand); }

@media (max-width: 1024px) {
  .fc__body { grid-template-columns: 1fr; }
  .det-meta { grid-template-columns: 1fr; }
  .store-table__head, .store-table__row { grid-template-columns: 1.2fr repeat(5, 1fr); font-size: var(--t-xs); }
}

/* ==================== B11 期末结转 ==================== */
.cc { display: flex; flex-direction: column; gap: var(--s-md); }
.cc__head { display: flex; align-items: center; justify-content: space-between; gap: var(--s-sm); flex-wrap: wrap; }
.cc__title { display: flex; align-items: center; gap: var(--s-xs); font-size: var(--t-sm); font-weight: 700; color: var(--c-text); }
.cc__hint { font-size: var(--t-xs); color: var(--c-text-3); font-weight: 400; }
.cc__ops { display: flex; align-items: center; gap: var(--s-sm); flex-wrap: wrap; }
.cc__note { display: flex; align-items: flex-start; gap: 4px; font-size: var(--t-xs); color: var(--c-text-3); line-height: 1.6; }

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
.rule-item__meta {
  font-size: var(--t-xs); color: var(--c-text-3); line-height: 1.6;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.rule-item__meta b { color: var(--c-text); font-weight: 600; }
.rule-item__ops { display: flex; gap: 2px; flex-shrink: 0; }
.empty-inline { font-size: var(--t-sm); color: var(--c-text-3); padding: var(--s-md) 0; }

.modal-mask { position: fixed; inset: 0; background: rgba(20, 21, 43, .45); display: flex; align-items: center; justify-content: center; z-index: 200; padding: var(--s-lg); }
.modal { width: 440px; max-width: 100%; box-shadow: var(--shadow-pop); }
.modal--wide { width: 820px; }

.pv { display: flex; flex-direction: column; gap: var(--s-md); }
.pv__bar { display: flex; align-items: center; gap: var(--s-sm); flex-wrap: wrap; }
.pv__bar .month-input { width: auto; min-width: 170px; }
.pv__tip { display: inline-flex; align-items: center; gap: 4px; font-size: var(--t-xs); color: var(--c-text-3); }

.stat-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--s-md); }
.stat { background: var(--c-bg-right); border-radius: var(--r-md); padding: var(--s-md); }
.stat__label { font-size: var(--t-xs); color: var(--c-text-3); margin-bottom: 4px; }
.stat__value { font-size: var(--t-md); font-weight: 700; color: var(--c-text); font-variant-numeric: tabular-nums; }
.stat__value--brand { color: var(--c-brand); }
.stat__value--orange { color: var(--c-orange-dark); }

.pv__table { border: 1px solid var(--c-border-light); border-radius: var(--r-md); overflow: hidden; }
.pv__row {
  display: grid; grid-template-columns: 1.1fr 2fr 0.9fr 1fr 0.8fr;
  gap: var(--s-sm); padding: var(--s-sm) var(--s-md); align-items: center;
  font-size: var(--t-sm); color: var(--c-text-2); border-top: 1px solid var(--c-border-light);
}
.pv__row:first-child { border-top: none; }
.pv__row--head { background: var(--c-bg-right); color: var(--c-text-3); font-size: var(--t-xs); font-weight: 600; }
.pv__store { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.pv__rule { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.pv__basis { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 2px; line-height: 1.5; }
.pv__amount { font-variant-numeric: tabular-nums; font-weight: 600; color: var(--c-text); text-align: right; }

.form { display: flex; flex-direction: column; gap: var(--s-md); }
.field { display: flex; flex-direction: column; gap: 6px; }
.field-row { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-sm); }
.field__label { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.field__label i { color: var(--c-danger-fg); font-style: normal; }
.form-tip { display: flex; align-items: flex-start; gap: 6px; font-size: var(--t-xs); color: var(--c-text-3); line-height: 1.6; }
.form-tip b { color: var(--c-text); }

.ack { display: flex; align-items: center; gap: var(--s-xs); font-size: var(--t-xs); color: var(--c-text-2); margin-top: 4px; cursor: pointer; }
.ack input { width: 15px; height: 15px; accent-color: var(--c-warning); }

.month-input {
  width: 100%; padding: 10px;
  border: 1px solid #D1D1D9; border-radius: var(--r-sm);
  background: var(--c-surface);
  font-size: 13px; color: var(--c-text); line-height: 20px;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.month-input:focus { outline: none; border-color: #4D5AD9; box-shadow: 0 0 0 2px rgba(77, 90, 217, 0.12); }

.state-row { display: flex; align-items: center; justify-content: center; gap: var(--s-xs); padding: var(--s-xl) 0; color: var(--c-text-3); font-size: var(--t-sm); }

.drw { display: flex; flex-direction: column; gap: var(--s-lg); }
.drw__section { display: flex; flex-direction: column; gap: var(--s-sm); }
.drw__section-title {
  display: flex; align-items: center; justify-content: space-between; gap: var(--s-sm);
  font-size: var(--t-sm); font-weight: 600; color: var(--c-text);
}

.ast { border: 1px solid var(--c-border-light); border-radius: var(--r-md); overflow: hidden; }
.ast__head, .ast__row {
  display: grid; grid-template-columns: 1.4fr 1fr 0.9fr 0.9fr 0.8fr 0.7fr;
  gap: var(--s-sm); padding: var(--s-sm) var(--s-md); align-items: center;
}
.ast__head { background: var(--c-bg-right); color: var(--c-text-3); font-size: var(--t-xs); font-weight: 600; }
.ast__row { border-top: 1px solid var(--c-border-light); font-size: var(--t-sm); color: var(--c-text-2); }
.ast__num { font-variant-numeric: tabular-nums; }
.ast__ops { text-align: right; }
.ast__row--off { opacity: .6; }

@media (max-width: 1024px) {
  .stat-grid { grid-template-columns: 1fr; }
  .pv__row { grid-template-columns: 1fr 1.6fr 0.8fr 0.9fr 0.7fr; font-size: var(--t-xs); }
  .ast__head, .ast__row { grid-template-columns: 1.2fr 0.9fr 0.8fr 0.8fr 0.7fr 0.6fr; font-size: var(--t-xs); }
  .modal--wide { width: 100%; }
}
</style>
