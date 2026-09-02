<script setup lang="ts">
/* ============================================================
 * A1-17 隐私合规 — 红线页
 * 路由 /ai/privacy
 * 红线：全站隐私字段脱敏，AI 数据本地隔离，等保三级认证，审计日志 append-only 不可篡改
 * ============================================================ */
import { ref, computed } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CKpi from '@/components/CKpi.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CSegmented from '@/components/CSegmented.vue'
import CTable from '@/components/CTable.vue'
import CIcon from '@/components/CIcon.vue'

type Tab = 'mask' | 'compliance' | 'audit'

const tab = ref<Tab>('mask')
const tabOptions = [
  { label: '脱敏配置', value: 'mask' },
  { label: '等保清单', value: 'compliance' },
  { label: '审计导出', value: 'audit' },
]

// 脱敏配置
const maskColumns = [
  { key: 'field', label: '字段名' },
  { key: 'module', label: '所属模块' },
  { key: 'rule', label: '脱敏规则' },
  { key: 'status', label: '状态' },
  { key: 'actions', label: '操作', align: 'right' as const, width: 120 },
]

const maskRows = ref([
  { id: 'M1', field: '手机号', module: 'M3 客户中心', rule: '手机号', status: 'enabled' },
  { id: 'M2', field: '身份证号', module: 'M3 客户中心', rule: '身份证', status: 'enabled' },
  { id: 'M3', field: '真实姓名', module: 'M3 客户中心', rule: '姓名', status: 'enabled' },
  { id: 'M4', field: '消费金额', module: 'M6 财务中心', rule: '金额', status: 'enabled' },
  { id: 'M5', field: '银行卡号', module: 'M6 财务中心', rule: '身份证', status: 'enabled' },
  { id: 'M6', field: '联系地址', module: 'M3 客户中心', rule: '姓名', status: 'enabled' },
  { id: 'M7', field: '诊疗记录', module: 'M4 咨询工作台', rule: '姓名', status: 'disabled' },
  { id: 'M8', field: '佣金金额', module: 'M6 财务中心', rule: '金额', status: 'enabled' },
])

// 等保三级清单
const complianceItems = ref([
  { id: 'C1', label: '安全物理环境 — 机房访问控制、防火防水', checked: true },
  { id: 'C2', label: '安全通信网络 — 国密 TLS 1.3 强制加密', checked: true },
  { id: 'C3', label: '安全区域边界 — 入侵检测 / 访问控制列表', checked: true },
  { id: 'C4', label: '安全计算环境 — 身份鉴别、权限最小化', checked: true },
  { id: 'C5', label: '安全管理中心 — 集中审计、集中管控', checked: true },
  { id: 'C6', label: 'AI 数据本地隔离 — 训练数据不出域', checked: true },
  { id: 'C7', label: '隐私字段全站脱敏 — 展示层 / 接口层双脱敏', checked: false },
  { id: 'C8', label: '审计日志 append-only — WORM 存储、不可篡改', checked: true },
])

// 审计导出
const exportFrom = ref('2026-08-01')
const exportTo = ref('2026-08-26')

const exportColumns = [
  { key: 'time', label: '导出时间' },
  { key: 'range', label: '范围' },
  { key: 'operator', label: '操作人' },
  { key: 'hash', label: '哈希值 (SHA-256)' },
]

const exportRows = ref([
  { id: 'E1', time: '2026-08-26 10:32:14', range: '2026-08-19 ~ 2026-08-25', operator: '张管理', hash: 'a3f7b9c2...e8d1' },
  { id: 'E2', time: '2026-08-19 09:15:08', range: '2026-08-12 ~ 2026-08-18', operator: '张管理', hash: 'b7e2c4f1...a9d3' },
  { id: 'E3', time: '2026-08-12 09:20:42', range: '2026-08-05 ~ 2026-08-11', operator: '李审计', hash: 'c9a1d8e3...f4b2' },
  { id: 'E4', time: '2026-08-05 10:05:30', range: '2026-07-29 ~ 2026-08-04', operator: '张管理', hash: 'd2f4a6b8...c7e5' },
])

function statusPill(s: string) {
  return s === 'enabled'
    ? { status: 'success' as const, label: '启用' }
    : { status: 'disabled' as const, label: '停用' }
}

function toggleMask(row: Record<string, any>) {
  row.status = row.status === 'enabled' ? 'disabled' : 'enabled'
}

function toggleCompliance(item: typeof complianceItems.value[0]) {
  item.checked = !item.checked
}

function exportReport() {
  alert('正在导出合规报告 ' + exportFrom.value + ' ~ ' + exportTo.value + '，文件将追加审计哈希')
}

const complianceCheckedCount = computed(() => complianceItems.value.filter((i) => i.checked).length)
</script>

<template>
  <div class="a1-privacy">
    <div class="a1-privacy__kpis">
      <CKpi label="脱敏字段" value="48" tone="purple" icon="settings" />
      <CKpi label="合规项达标" value="96%" tone="success" icon="check-square" />
      <CKpi label="待处理" value="2" tone="warning" icon="check-square" />
      <CKpi label="审计记录" value="12,840" tone="brand" icon="check-square" />
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
        <CTable :columns="maskColumns" :rows="maskRows" row-key="id">
          <template #col-rule="{ value }">
            <CStatusPill status="info">{{ value }}</CStatusPill>
          </template>
          <template #col-status="{ value }">
            <CStatusPill :status="statusPill(value).status" dot>
              {{ statusPill(value).label }}
            </CStatusPill>
          </template>
          <template #col-actions="{ row }">
            <CButton size="sm" variant="text" @click="toggleMask(row)">
              {{ row.status === 'enabled' ? '停用' : '启用' }}
            </CButton>
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
            <input v-model="exportFrom" type="date" class="date-input" />
          </div>
          <div class="audit__field">
            <label class="field-label">结束日期</label>
            <input v-model="exportTo" type="date" class="date-input" />
          </div>
          <CButton variant="primary" @click="exportReport">
            <CIcon name="export" :size="14" />
            导出合规报告
          </CButton>
        </div>

        <div class="audit__divider"></div>

        <h4 class="section-title">最近导出记录</h4>
        <CTable :columns="exportColumns" :rows="exportRows" row-key="id">
          <template #col-hash="{ value }">
            <code class="hash-code">{{ value }}</code>
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
