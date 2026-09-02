<script setup lang="ts">
/* ============================================================
 * T2-02 数据治理 /data/govern
 * 质量规则 / 问题清单 / 数据血缘，KPI×4，新建规则 Drawer，SVG DAG
 * ============================================================ */
import { computed, onMounted, reactive, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CTextarea from '@/components/CTextarea.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CSegmented from '@/components/CSegmented.vue'
import CDrawer from '@/components/CDrawer.vue'
import CProgressBar from '@/components/CProgressBar.vue'
import { useT2DataGovernStore, type RuleType, type RuleSeverity, type LineageNode, type LineageEdge } from '@/stores/t2DataGovern'
import { useAuthStore } from '@/stores/auth'

const store = useT2DataGovernStore()
const auth = useAuthStore()
onMounted(() => store.seed())

const tab = ref<'rules' | 'issues' | 'lineage'>('rules')
const tabOpts = [
  { value: 'rules', label: '质量规则' },
  { value: 'issues', label: '问题清单' },
  { value: 'lineage', label: '数据血缘' },
]

const kpis = computed(() => [
  { label: '规则总数', icon: 'settings', value: String(store.rules.length), tone: 'brand' as const },
  { label: '开启规则', icon: 'settings', value: String(store.enabledRules.length), tone: 'success' as const },
  { label: '平均通过率', icon: 'trend-up', value: store.passRate + '%', tone: store.passRate >= 98 ? 'success' as const : store.passRate >= 95 ? 'warning' as const : 'danger' as const },
  { label: '待处理问题', icon: 'alert', value: String(store.openIssues.length), tone: store.openIssues.length > 0 ? 'danger' as const : 'text' as const },
])

// 严重度 pill
function sevPill(s: RuleSeverity) {
  return s === 'HIGH' ? 'danger' : s === 'MEDIUM' ? 'warning' : 'info'
}
function issuePill(s: 'OPEN' | 'RESOLVED' | 'IGNORED') {
  return s === 'OPEN' ? 'danger' : s === 'RESOLVED' ? 'success' : 'disabled'
}
function fmtTime(iso: string | null | undefined) {
  if (!iso) return '—'
  const d = new Date(iso)
  return `${d.getMonth() + 1}/${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

// ---- 新建规则 ----
const showCreate = ref(false)
const editingId = ref<string | null>(null)
const form = reactive({
  name: '', table: '', column: '', type: 'NOT_NULL' as RuleType,
  severity: 'MEDIUM' as RuleSeverity, expression: '', enabled: true,
})
const typeOptions = [
  { value: 'NOT_NULL', label: '非空校验' },
  { value: 'UNIQUE', label: '唯一约束' },
  { value: 'RANGE', label: '数值范围' },
  { value: 'REGEX', label: '正则匹配' },
  { value: 'CUSTOM', label: '自定义 SQL' },
]
const sevOptions = [
  { value: 'HIGH', label: '高' },
  { value: 'MEDIUM', label: '中' },
  { value: 'LOW', label: '低' },
]
const canSubmit = computed(() => form.name.trim() && form.table.trim() && form.column.trim())
function openCreate() {
  Object.assign(form, { name: '', table: '', column: '', type: 'NOT_NULL', severity: 'MEDIUM', expression: '', enabled: true })
  editingId.value = null
  showCreate.value = true
}
function submitRule() {
  if (!canSubmit.value) return
  if (editingId.value) {
    store.updateRule(editingId.value, { ...form })
  } else {
    store.createRule({ ...form })
  }
  showCreate.value = false
  editingId.value = null
}
function editRule(id: string) {
  const r = store.getRule(id)
  if (!r) return
  Object.assign(form, {
    name: r.name, table: r.table, column: r.column, type: r.type,
    severity: r.severity, expression: r.expression, enabled: r.enabled,
  })
  editingId.value = id
  showCreate.value = true
}

// ---- 血缘图 ----
const NODE_W = 160
const NODE_H = 56
function nodeCenter(n: LineageNode) {
  return { cx: n.x + NODE_W / 2, cy: n.y + NODE_H / 2 }
}
function edgePath(e: LineageEdge) {
  const from = store.lineageNodes.find((n) => n.id === e.from)
  const to = store.lineageNodes.find((n) => n.id === e.to)
  if (!from || !to) return ''
  const a = nodeCenter(from)
  const b = nodeCenter(to)
  // 从节点右边出发，到节点左边
  const x1 = a.cx + NODE_W / 2
  const y1 = a.cy
  const x2 = b.cx - NODE_W / 2
  const y2 = b.cy
  const dx = Math.max(40, (x2 - x1) / 2)
  return `M ${x1} ${y1} C ${x1 + dx} ${y1}, ${x2 - dx} ${y2}, ${x2} ${y2}`
}
function nodeFill(t: LineageNode['type']) {
  return t === 'SOURCE' ? 'var(--c-info-bg)'
    : t === 'TABLE' ? 'var(--c-brand-soft)'
    : t === 'TAG' ? 'var(--c-warning-bg)'
    : t === 'API' ? 'var(--c-success-bg)'
    : 'var(--c-disabled-bg)'
}
function nodeFg(t: LineageNode['type']) {
  return t === 'SOURCE' ? 'var(--c-info-fg)'
    : t === 'TABLE' ? 'var(--c-brand)'
    : t === 'TAG' ? 'var(--c-warning-fg)'
    : t === 'API' ? 'var(--c-success-fg)'
    : 'var(--c-disabled-fg)'
}
const lineageViewBox = computed(() => {
  const w = Math.max(...store.lineageNodes.map((n) => n.x)) + NODE_W + 40
  const h = Math.max(...store.lineageNodes.map((n) => n.y)) + NODE_H + 40
  return `0 0 ${w} ${h}`
})
const lineageGroups = computed(() => {
  const groups: { x: number; title: string }[] = [
    { x: 40, title: '数据源' },
    { x: 260, title: '明细层 DWD' },
    { x: 480, title: '汇总层 DWS' },
    { x: 700, title: '标签层' },
    { x: 920, title: '服务 / 报表' },
  ]
  return groups
})
</script>

<template>
  <div class="gov">
    <div class="gov__head">
      <div class="gov__kpis">
        <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :icon="k.icon" />
      </div>
      <CButton v-if="auth.can('govern:rule:create')" variant="primary" size="sm" @click="openCreate">
        <CIcon name="plus" :size="16" />新建质量规则
      </CButton>
    </div>

    <CCard class="gov__main" padding="none">
      <div class="gov__toolbar">
        <CSegmented v-model="tab" :options="tabOpts" size="sm" />
      </div>

      <!-- 质量规则 -->
      <table v-if="tab === 'rules'" class="ctable">
        <thead>
          <tr>
            <th>规则名称</th>
            <th style="width:200px">表.字段</th>
            <th style="width:100px">类型</th>
            <th style="width:90px">严重度</th>
            <th style="width:220px">通过率</th>
            <th style="width:100px" class="num">错误数</th>
            <th style="width:80px">启用</th>
            <th style="width:140px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in store.rules" :key="r.id">
            <td>
              <div class="rulename">{{ r.name }}</div>
              <div class="rulename__sub">{{ r.expression }}</div>
            </td>
            <td><code class="tbl">{{ r.table }}.{{ r.column }}</code></td>
            <td>{{ store.RULE_TYPE_LABEL[r.type] }}</td>
            <td><CStatusPill :status="sevPill(r.severity)">{{ store.RULE_SEVERITY_LABEL[r.severity] }}</CStatusPill></td>
            <td><CProgressBar :value="r.passRate" :color="r.passRate >= 99 ? 'var(--c-success-fg)' : r.passRate >= 95 ? 'var(--c-warning-fg)' : 'var(--c-danger-fg)'" /></td>
            <td class="num" :class="{ 'has-err': r.errorCount > 0 }">{{ r.errorCount }}</td>
            <td>
              <label class="switch">
                <input type="checkbox" :checked="r.enabled" :disabled="!auth.can('govern:rule:edit')" @change="store.toggleRule(r.id)" />
                <span class="slider" />
              </label>
            </td>
            <td>
              <CButton v-if="auth.can('govern:rule:edit')" size="sm" variant="text" @click="editRule(r.id)">
                <CIcon name="edit" :size="14" />编辑
              </CButton>
            </td>
          </tr>
        </tbody>
      </table>

      <!-- 问题清单 -->
      <table v-else-if="tab === 'issues'" class="ctable">
        <thead>
          <tr>
            <th>规则</th>
            <th style="width:200px">表.字段</th>
            <th>异常样本</th>
            <th style="width:100px" class="num">异常数</th>
            <th style="width:110px">状态</th>
            <th style="width:160px">检测时间</th>
            <th style="width:140px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="i in store.issues" :key="i.id">
            <td>{{ i.ruleName }}</td>
            <td><code class="tbl">{{ i.table }}.{{ i.column }}</code></td>
            <td class="muted">{{ i.sample }}</td>
            <td class="num">{{ i.count }}</td>
            <td><CStatusPill :status="issuePill(i.status)">{{ store.ISSUE_STATUS_LABEL[i.status] }}</CStatusPill></td>
            <td>{{ fmtTime(i.detectedAt) }}</td>
            <td>
              <template v-if="i.status === 'OPEN' && auth.can('govern:rule:edit')">
                <CButton size="sm" variant="text" @click="store.resolveIssue(i.id)">
                  <CIcon name="check" :size="14" />标记已解决
                </CButton>
              </template>
              <span v-else class="muted">{{ i.status === 'RESOLVED' ? '已解决于 ' + fmtTime(i.resolvedAt) : '已忽略' }}</span>
            </td>
          </tr>
        </tbody>
      </table>

      <!-- 数据血缘 -->
      <div v-else class="lineage">
        <div class="lineage__legend">
          <span v-for="g in lineageGroups" :key="g.x" class="lineage__legend-item">
            <span class="lineage__legend-dot" :style="{ background: nodeFill(g.title === '数据源' ? 'SOURCE' : g.title.includes('DWD') ? 'TABLE' : g.title.includes('DWS') ? 'TABLE' : g.title.includes('标签') ? 'TAG' : g.title.includes('服务') ? 'API' : 'REPORT') }" />
            {{ g.title }}
          </span>
        </div>
        <svg :viewBox="lineageViewBox" class="lineage__svg" preserveAspectRatio="xMinYMin meet">
          <defs>
            <marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto">
              <path d="M 0 0 L 10 5 L 0 10 z" fill="var(--c-border-strong, #C4C4CF)" />
            </marker>
          </defs>
          <!-- 列分隔 -->
          <g class="lineage__cols">
            <line v-for="g in lineageGroups.slice(0, -1)" :key="g.x"
              :x1="g.x + 230" :y1="20" :x2="g.x + 230" :y2="lineageViewBox.split(' ')[3]"
              stroke="var(--c-border-light)" stroke-dasharray="4 4" />
          </g>
          <!-- edges -->
          <g class="lineage__edges">
            <path v-for="(e, i) in store.lineageEdges" :key="i" :d="edgePath(e)" fill="none"
              stroke="var(--c-border-strong, #B5B5C0)" stroke-width="1.5" marker-end="url(#arrow)" />
          </g>
          <!-- nodes -->
          <g v-for="n in store.lineageNodes" :key="n.id" class="lineage__node">
            <rect :x="n.x" :y="n.y" :width="NODE_W" :height="NODE_H" rx="8"
              :fill="nodeFill(n.type)" stroke="var(--c-border)" />
            <text :x="n.x + 12" :y="n.y + 22" class="lineage__node-type" :fill="nodeFg(n.type)">
              {{ store.LINEAGE_NODE_LABEL[n.type] }}
            </text>
            <text :x="n.x + 12" :y="n.y + 42" class="lineage__node-name" fill="var(--c-text)">
              {{ n.name }}
            </text>
          </g>
        </svg>
      </div>
    </CCard>

    <CDrawer :show="showCreate" :title="editingId ? '编辑质量规则' : '新建质量规则'" size="md" @update:show="showCreate = $event">
      <div class="form">
        <CInput v-model="form.name" label="规则名称" placeholder="例如：订单号非空" />
        <div class="form__row">
          <CInput v-model="form.table" label="表名" placeholder="orders" />
          <CInput v-model="form.column" label="字段名" placeholder="order_no" />
        </div>
        <div class="form__row">
          <div class="form__field">
            <label class="fld-label">规则类型</label>
            <CSelect v-model="form.type" :options="typeOptions" width="100%" />
          </div>
          <div class="form__field">
            <label class="fld-label">严重度</label>
            <CSelect v-model="form.severity" :options="sevOptions" width="100%" />
          </div>
        </div>
        <CTextarea v-model="form.expression" :rows="3" label="校验表达式" placeholder="例如：order_no IS NOT NULL" />
        <label class="switch-line">
          <input type="checkbox" v-model="form.enabled" />
          <span class="slider" />
          <span>立即启用</span>
        </label>
      </div>
      <template #footer>
        <CButton variant="ghost" @click="showCreate = false">取消</CButton>
        <CButton variant="primary" :disabled="!canSubmit" @click="submitRule">{{ editingId ? '保存' : '创建' }}</CButton>
      </template>
    </CDrawer>
  </div>
</template>

<style scoped>
.gov { display: flex; flex-direction: column; gap: var(--s-md); }
.gov__head { display: flex; align-items: stretch; gap: var(--s-md); }
.gov__kpis { flex: 1; display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); }
.gov__main :deep(.card__body) { display: flex; flex-direction: column; gap: var(--s-md); padding: 0; }
.gov__toolbar { padding: var(--s-md) var(--s-lg) 0; }

.ctable { width: 100%; border-collapse: collapse; font-size: var(--t-sm); }
.ctable thead th { padding: 12px var(--s-lg); background: var(--c-bg-page); color: var(--c-text); font-weight: 600; font-size: var(--t-xs); text-align: left; border-bottom: 1px solid var(--c-border); white-space: nowrap; }
.ctable tbody td { padding: 12px var(--s-lg); color: var(--c-text-2); border-bottom: 1px solid var(--c-border); vertical-align: middle; }
.ctable tbody tr:last-child td { border-bottom: none; }
.ctable tbody tr:hover { background: var(--c-brand-soft); }
.ctable .num { text-align: right; font-variant-numeric: tabular-nums; }
.ctable th.num { text-align: right; }
.ctable .muted { color: var(--c-text-3); font-size: var(--t-xs); }
.ctable .has-err { color: var(--c-danger-fg); font-weight: 600; }

.rulename { font-weight: 600; color: var(--c-text); }
.rulename__sub { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 11px; color: var(--c-text-3); margin-top: 2px; }
.tbl { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 11px; background: var(--c-bg-page); padding: 2px 6px; border-radius: var(--r-sm); color: var(--c-text-2); }

/* 开关 */
.switch { position: relative; display: inline-block; width: 36px; height: 20px; }
.switch input { opacity: 0; width: 0; height: 0; }
.switch .slider { position: absolute; cursor: pointer; inset: 0; background: var(--c-border); border-radius: 999px; transition: .2s; }
.switch .slider::before { content: ''; position: absolute; height: 16px; width: 16px; left: 2px; top: 2px; background: #fff; border-radius: 50%; transition: .2s; box-shadow: 0 1px 2px rgba(0,0,0,.2); }
.switch input:checked + .slider { background: var(--c-brand); }
.switch input:checked + .slider::before { transform: translateX(16px); }
.switch input:disabled + .slider { opacity: .5; cursor: not-allowed; }

.switch-line { display: inline-flex; align-items: center; gap: var(--s-sm); font-size: var(--t-sm); color: var(--c-text-2); cursor: pointer; }
.switch-line .slider { position: relative; display: inline-block; width: 36px; height: 20px; background: var(--c-border); border-radius: 999px; transition: .2s; cursor: pointer; }
.switch-line .slider::before { content: ''; position: absolute; height: 16px; width: 16px; left: 2px; top: 2px; background: #fff; border-radius: 50%; transition: .2s; box-shadow: 0 1px 2px rgba(0,0,0,.2); }
.switch-line input { display: none; }
.switch-line input:checked + .slider { background: var(--c-brand); }
.switch-line input:checked + .slider::before { transform: translateX(16px); }

/* 血缘 */
.lineage { padding: var(--s-md) var(--s-lg) var(--s-lg); display: flex; flex-direction: column; gap: var(--s-md); overflow: auto; }
.lineage__legend { display: flex; gap: var(--s-md); flex-wrap: wrap; }
.lineage__legend-item { display: inline-flex; align-items: center; gap: 6px; font-size: var(--t-xs); color: var(--c-text-2); }
.lineage__legend-dot { width: 10px; height: 10px; border-radius: 50%; display: inline-block; }
.lineage__svg { width: 100%; min-width: 1120px; height: auto; }
.lineage__node-type { font-size: 10px; font-weight: 600; }
.lineage__node-name { font-size: 12px; font-weight: 600; }

.form { display: flex; flex-direction: column; gap: var(--s-md); }
.form__row { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); }
.form__field { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.fld-label { font-size: 13px; font-weight: 400; color: var(--c-text); line-height: 18px; }
</style>
