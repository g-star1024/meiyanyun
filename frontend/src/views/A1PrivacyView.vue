<script setup lang="ts">
/* ============================================================
 * A1-17 隐私合规 — 红线页
 * 路由 /ai/privacy
 * 红线：全站隐私字段脱敏，AI 数据本地隔离，等保三级认证，审计日志 append-only 不可篡改
 * B47 卡8 去 mock：脱敏规则/等保台账/合规报告导出全部走 /api/ai/privacy 真实端点，
 * 报告哈希为后端对区间内 audit_log 全链规范化后的真实 SHA-256（空区间 audit_count=0）
 * ============================================================ */
import { ref, computed, onMounted } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CKpi from '@/components/CKpi.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CSegmented from '@/components/CSegmented.vue'
import CTable from '@/components/CTable.vue'
import CIcon from '@/components/CIcon.vue'
import { useToast } from '@/composables/useToast'
import { useAuthStore } from '@/stores/auth'
import { errMsg } from '@/stores/m5Coupon'
import { fmtDateTimeSec, shDateStr } from '@/utils/datetime'
import {
  getPrivacyStats,
  listPrivacyMaskRules,
  togglePrivacyMaskRule,
  listPrivacyComplianceItems,
  togglePrivacyComplianceItem,
  listPrivacyExports,
  createPrivacyExport,
  type PrivacyMaskRule,
  type PrivacyComplianceItem,
  type PrivacyExport,
  type PrivacyStats,
} from '@/api/ai'

const toast = useToast()
const auth = useAuthStore()
const canEdit = computed(() => auth.can('aiPrivacy:edit'))

type Tab = 'mask' | 'compliance' | 'audit'

const tab = ref<Tab>('mask')
const tabOptions = [
  { label: '脱敏配置', value: 'mask' },
  { label: '等保清单', value: 'compliance' },
  { label: '审计导出', value: 'audit' },
]

const MASK_TYPE_LABEL: Record<string, string> = {
  phone: '手机号',
  idcard: '身份证',
  name: '姓名',
  amount: '金额',
  bankcard: '银行卡',
  address: '地址',
  medical: '诊疗记录',
  email: '邮箱',
}

// 脱敏配置
const maskColumns = [
  { key: 'field', label: '字段名' },
  { key: 'module', label: '所属模块' },
  { key: 'rule', label: '脱敏规则' },
  { key: 'status', label: '状态' },
  { key: 'actions', label: '操作', align: 'right' as const, width: 120 },
]

interface MaskRow {
  id: number
  field: string
  module: string
  rule: string
  status: 'enabled' | 'disabled'
  raw: PrivacyMaskRule
}

const maskRows = ref<MaskRow[]>([])
const busyMaskId = ref<number | null>(null)

// 等保三级清单
interface ComplianceRow {
  id: number
  label: string
  checked: boolean
  raw: PrivacyComplianceItem
}

const complianceItems = ref<ComplianceRow[]>([])
const busyCompId = ref<number | null>(null)

// 审计导出
const exportFrom = ref(shDateStr(new Date(Date.now() - 7 * 86400 * 1000)))
const exportTo = ref(shDateStr())
const exporting = ref(false)

const exportColumns = [
  { key: 'time', label: '导出时间' },
  { key: 'range', label: '范围' },
  { key: 'operator', label: '操作人' },
  { key: 'hash', label: '哈希值 (SHA-256)' },
]

interface ExportRow {
  id: number
  time: string
  range: string
  operator: string
  hash: string
  fullHash: string
}

const exportRows = ref<ExportRow[]>([])

const stats = ref<PrivacyStats>({ maskFieldCount: 0, compliancePct: 0, pendingCount: 0, auditCount: 0 })
const loading = ref(false)

function shortHash(h: string): string {
  return h.length > 16 ? `${h.slice(0, 8)}...${h.slice(-4)}` : h
}

function statusPill(s: string) {
  return s === 'enabled'
    ? { status: 'success' as const, label: '启用' }
    : { status: 'disabled' as const, label: '停用' }
}

async function loadStats() {
  try {
    stats.value = await getPrivacyStats()
  } catch (e) {
    toast.error('统计加载失败：' + errMsg(e))
  }
}

async function loadMaskRules() {
  const rules = await listPrivacyMaskRules()
  maskRows.value = rules.map((r) => ({
    id: r.ruleId,
    field: r.fieldLabel,
    module: r.moduleName,
    rule: MASK_TYPE_LABEL[r.maskType] ?? r.maskType,
    status: r.enabled ? 'enabled' : 'disabled',
    raw: r,
  }))
}

async function loadComplianceItems() {
  const items = await listPrivacyComplianceItems()
  complianceItems.value = items.map((i) => ({
    id: i.itemId,
    label: i.label,
    checked: i.checked,
    raw: i,
  }))
}

async function loadExports() {
  const page = await listPrivacyExports(0, 20)
  exportRows.value = page.content.map((e: PrivacyExport): ExportRow => ({
    id: e.exportId,
    time: fmtDateTimeSec(e.createdAt),
    range: `${e.rangeFrom} ~ ${e.rangeTo}（${e.auditCount} 条审计）`,
    operator: e.staffName ?? '系统',
    hash: shortHash(e.reportHash),
    fullHash: e.reportHash,
  }))
}

async function loadAll() {
  loading.value = true
  try {
    await Promise.all([loadMaskRules(), loadComplianceItems(), loadExports()])
  } catch (e) {
    toast.error('隐私合规数据加载失败：' + errMsg(e))
  } finally {
    loading.value = false
  }
  void loadStats()
}

async function toggleMask(row: MaskRow) {
  if (!canEdit.value || busyMaskId.value !== null) return
  busyMaskId.value = row.id
  try {
    const updated = await togglePrivacyMaskRule(row.id)
    row.status = updated.enabled ? 'enabled' : 'disabled'
    row.raw = updated
    toast.success(updated.enabled ? `已启用脱敏规则：${row.field}` : `已停用脱敏规则：${row.field}`)
    void loadStats()
  } catch (e) {
    toast.error('脱敏规则启停失败：' + errMsg(e))
  } finally {
    busyMaskId.value = null
  }
}

async function toggleCompliance(item: ComplianceRow) {
  if (!canEdit.value || busyCompId.value !== null) return
  busyCompId.value = item.id
  try {
    const updated = await togglePrivacyComplianceItem(item.id)
    item.checked = updated.checked
    item.raw = updated
    toast.success(updated.checked ? '已标记达标：' + item.label : '已取消达标标记：' + item.label)
    void loadStats()
  } catch (e) {
    toast.error('达标状态更新失败：' + errMsg(e))
  } finally {
    busyCompId.value = null
  }
}

async function exportReport() {
  if (!canEdit.value || exporting.value) return
  if (!exportFrom.value || !exportTo.value) {
    toast.info('请先选择导出的开始与结束日期')
    return
  }
  if (exportTo.value < exportFrom.value) {
    toast.error('结束日期不能早于开始日期')
    return
  }
  exporting.value = true
  try {
    const res = await createPrivacyExport(exportFrom.value, exportTo.value)
    toast.success(`合规报告已导出并追加审计哈希，区间覆盖审计 ${res.auditCount} 条`)
    await loadExports()
    void loadStats()
  } catch (e) {
    toast.error('导出合规报告失败：' + errMsg(e))
  } finally {
    exporting.value = false
  }
}

const complianceCheckedCount = computed(() => complianceItems.value.filter((i) => i.checked).length)
const auditCountText = computed(() => stats.value.auditCount.toLocaleString('zh-CN'))

onMounted(() => {
  void loadAll()
})
</script>

<template>
  <div class="a1-privacy">
    <div class="a1-privacy__kpis">
      <CKpi label="脱敏字段" :value="String(stats.maskFieldCount)" tone="purple" icon="settings" />
      <CKpi label="合规项达标" :value="stats.compliancePct + '%'" tone="success" icon="check-square" />
      <CKpi label="待处理" :value="String(stats.pendingCount)" tone="warning" icon="check-square" />
      <CKpi label="审计记录" :value="auditCountText" tone="brand" icon="check-square" />
    </div>

    <!-- 红线提示条 -->
    <div class="redline">
      <CIcon name="shield" :size="16" class="redline__icon" />
      <div class="redline__body">
        <div class="redline__title">A1-17 隐私合规红线</div>
        <div class="redline__text">全站隐私字段脱敏，AI 数据本地隔离，等保三级认证，审计日志 append-only 不可篡改</div>
      </div>
    </div>

    <CCard padding="lg">
      <template #header>
        <div class="card-head">
          <h3>合规配置中心</h3>
          <CSegmented v-model="tab" :options="tabOptions" size="sm" />
        </div>
      </template>

      <!-- 脱敏配置 -->
      <div v-if="tab === 'mask'">
        <CTable
          :columns="maskColumns"
          :rows="maskRows"
          row-key="id"
          :empty-text="loading ? '加载中…' : '暂无脱敏规则'"
        >
          <template #col-rule="{ value }">
            <CStatusPill status="info">{{ value }}</CStatusPill>
          </template>
          <template #col-status="{ value }">
            <CStatusPill :status="statusPill(value).status" dot>
              {{ statusPill(value).label }}
            </CStatusPill>
          </template>
          <template #col-actions="{ row }">
            <CButton
              v-if="canEdit"
              size="sm"
              variant="text"
              :disabled="busyMaskId !== null"
              @click="toggleMask(row as unknown as MaskRow)"
            >
              {{ (row as unknown as MaskRow).status === 'enabled' ? '停用' : '启用' }}
            </CButton>
            <span v-else class="field-label">只读</span>
          </template>
        </CTable>
      </div>

      <!-- 等保清单 -->
      <div v-else-if="tab === 'compliance'" class="compliance">
        <div class="compliance__summary">
          <span class="compliance__count">{{ complianceCheckedCount }}/{{ complianceItems.length }}</span>
          <span class="compliance__label">项已达标</span>
        </div>
        <div class="checklist">
          <div
            v-for="item in complianceItems"
            :key="item.id"
            class="check-item"
            :style="canEdit ? undefined : 'cursor: default'"
            @click="toggleCompliance(item)"
          >
            <span class="checkbox" :class="{ 'is-checked': item.checked }">
              <CIcon v-if="item.checked" name="check" :size="12" />
            </span>
            <span class="check-item__label">{{ item.label }}</span>
            <CStatusPill :status="item.checked ? 'success' : 'warning'">
              {{ item.checked ? '已达标' : '待整改' }}
            </CStatusPill>
          </div>
        </div>
      </div>

      <!-- 审计导出 -->
      <div v-else class="audit">
        <div class="audit__form">
          <div class="audit__field">
            <label class="field-label">开始日期</label>
            <input
              v-model="exportFrom"
              type="date"
              class="date-input"
              :disabled="!canEdit"
            />
          </div>
          <div class="audit__field">
            <label class="field-label">结束日期</label>
            <input
              v-model="exportTo"
              type="date"
              class="date-input"
              :disabled="!canEdit"
            />
          </div>
          <CButton v-if="canEdit" variant="primary" :disabled="exporting" @click="exportReport">
            <CIcon name="export" :size="14" />
            {{ exporting ? '导出中…' : '导出合规报告' }}
          </CButton>
          <span v-else class="field-label">当前角色仅可查看导出记录，导出需 aiPrivacy:edit 权限</span>
        </div>

        <div class="audit__divider"></div>

        <h4 class="section-title">最近导出记录</h4>
        <CTable
          :columns="exportColumns"
          :rows="exportRows"
          row-key="id"
          :empty-text="loading ? '加载中…' : '暂无导出记录，选择日期区间后导出首份合规报告'"
        >
          <template #col-hash="{ row }">
            <code class="hash-code" :title="(row as unknown as ExportRow).fullHash">{{ (row as unknown as ExportRow).hash }}</code>
          </template>
        </CTable>
      </div>
    </CCard>
  </div>
</template>

<style scoped>
.a1-privacy { display: flex; flex-direction: column; gap: var(--s-lg); }
.a1-privacy__kpis { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }
@media (max-width: 1024px) { .a1-privacy__kpis { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); } }

.redline {
  display: flex; align-items: flex-start; gap: var(--s-md);
  padding: var(--s-md) var(--s-lg);
  background: var(--c-danger-bg);
  border: 1px solid var(--c-danger-fg);
  border-radius: var(--r-lg);
}
.redline__icon { color: var(--c-danger-fg); flex-shrink: 0; margin-top: 2px; }
.redline__body { flex: 1; }
.redline__title { font-size: var(--t-sm); font-weight: 700; color: var(--c-danger-fg); margin-bottom: 2px; }
.redline__text { font-size: var(--t-xs); color: var(--c-danger-fg); line-height: 1.5; }

.card-head { display: flex; align-items: center; justify-content: space-between; width: 100%; }
.card-head h3 { margin: 0; font-size: var(--t-md); font-weight: 700; }

/* 等保清单 */
.compliance { display: flex; flex-direction: column; gap: var(--s-lg); }
.compliance__summary {
  display: flex; align-items: baseline; gap: var(--s-xs);
  padding: var(--s-md);
  background: var(--c-success-bg);
  border-radius: var(--r-md);
}
.compliance__count { font-size: var(--t-number); font-weight: 700; color: var(--c-success-fg); }
.compliance__label { font-size: var(--t-sm); color: var(--c-success-fg); }

.checklist { display: flex; flex-direction: column; gap: var(--s-xs); }
.check-item {
  display: flex; align-items: center; gap: var(--s-sm);
  padding: var(--s-sm) var(--s-md);
  border-radius: var(--r-sm);
  cursor: pointer;
  transition: background 0.15s;
}
.check-item:hover { background: var(--c-bg-page); }
.checkbox {
  width: 18px; height: 18px;
  border: 1.5px solid var(--c-border);
  border-radius: var(--r-sm);
  display: inline-flex; align-items: center; justify-content: center;
  flex-shrink: 0;
  transition: all 0.15s;
}
.checkbox.is-checked {
  background: var(--c-brand);
  border-color: var(--c-brand);
  color: #fff;
}
.check-item__label { flex: 1; font-size: var(--t-sm); color: var(--c-text); }

/* 审计导出 */
.audit { display: flex; flex-direction: column; gap: var(--s-md); }
.audit__form { display: flex; align-items: flex-end; gap: var(--s-md); flex-wrap: wrap; }
.audit__field { display: flex; flex-direction: column; gap: 6px; }
.field-label { font-size: var(--t-xs); color: var(--c-text-3); }
.date-input {
  height: 36px; padding: 0 var(--s-sm);
  border: 1px solid var(--c-border); border-radius: var(--r-sm);
  background: var(--c-surface); font-size: var(--t-sm); color: var(--c-text);
  outline: none;
}
.date-input:focus { border-color: var(--c-brand); }
.audit__divider { height: 1px; background: var(--c-border); }
.section-title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); margin: 0; }
.hash-code {
  font-family: var(--f-latin);
  font-size: 11px;
  color: var(--c-purple);
  background: var(--c-purple-soft);
  padding: 2px 8px;
  border-radius: var(--r-sm);
}
</style>
