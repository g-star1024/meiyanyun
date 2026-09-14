<script setup lang="ts">
// M1 集团管控 · 门店主数据（B49 卡3 接真 · 只读）
// 数据源 GET /api/stores；数据范围随登录人数据域（DataScope）：区域经理见本区、集团账号见全量。
// 写侧收窄：门店新建/编辑/停用后端不支持（入 Backlog），本页不提供写操作。
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CSelect from '@/components/CSelect.vue'
import CTable from '@/components/CTable.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import { useM1TenantStore, type TenantStatus } from '@/stores/m1Tenant'

const tenant = useM1TenantStore()
onMounted(() => tenant.fetchAll())

// ---- 筛选 ----
const keyword = ref('')
const fRegion = ref('')
const fStatus = ref('')
const fNature = ref('')

const regionOptions = computed(() => [
  { value: '', label: '全部区域' },
  ...Object.keys(tenant.byRegion).map((r) => ({ value: r, label: r })),
])
const statusOptions = [
  { value: '', label: '全部状态' },
  { value: 'OPERATING', label: '营业中' },
  { value: 'SETTING_UP', label: '筹建中' },
  { value: 'SUSPENDED', label: '已关店' },
]
const natureOptions = computed(() => [
  { value: '', label: '全部性质' },
  ...tenant.natures.map((n) => ({ value: n, label: n })),
])

const filtered = computed(() => {
  const kw = keyword.value.trim()
  return tenant.tenants.filter((t) => {
    if (fRegion.value && t.region !== fRegion.value) return false
    if (fStatus.value && t.status !== fStatus.value) return false
    if (fNature.value && t.nature !== fNature.value) return false
    if (kw && !`${t.code} ${t.name} ${t.city}`.includes(kw)) return false
    return true
  })
})

const kpis = computed(() => ({
  operating: tenant.tenants.filter((t) => t.status === 'OPERATING').length,
  settingUp: tenant.tenants.filter((t) => t.status === 'SETTING_UP').length,
  suspended: tenant.tenants.filter((t) => t.status === 'SUSPENDED').length,
  total: tenant.tenants.length,
}))

// ---- 表格 ----
const columns = [
  { key: 'code', label: '门店编码', width: 110 },
  { key: 'name', label: '门店名称' },
  { key: 'region', label: '大区', width: 90 },
  { key: 'city', label: '城市', width: 80 },
  { key: 'nature', label: '经营性质', width: 100 },
  { key: 'status', label: '状态', width: 100 },
  { key: 'openDate', label: '开业日期', width: 120 },
]

const emptyText = computed(() => {
  if (tenant.loading) return '加载中…'
  if (tenant.error) return tenant.error
  return '无匹配门店'
})

function statusTone(s: TenantStatus) {
  return s === 'OPERATING' ? 'success' : s === 'SETTING_UP' ? 'warning' : 'disabled'
}
function natureTone(n: string) {
  return n === '直营' ? 'primary' : n === '联营' ? 'info' : 'default'
}
</script>

<template>
  <div class="mt-page">
    <!-- KPI -->
    <div class="mt-kpis">
      <div class="kpi kpi--brand">
        <div class="kpi__icon"><CIcon name="store" :size="20" /></div>
        <div class="kpi__body">
          <div class="kpi__label">营业中门店</div>
          <div class="kpi__value">{{ kpis.operating }}</div>
        </div>
      </div>
      <div class="kpi kpi--warning">
        <div class="kpi__icon"><CIcon name="settings" :size="20" /></div>
        <div class="kpi__body">
          <div class="kpi__label">筹建中</div>
          <div class="kpi__value">{{ kpis.settingUp }}</div>
        </div>
      </div>
      <div class="kpi kpi--muted">
        <div class="kpi__icon"><CIcon name="shield" :size="20" /></div>
        <div class="kpi__body">
          <div class="kpi__label">已关店</div>
          <div class="kpi__value">{{ kpis.suspended }}</div>
        </div>
      </div>
      <div class="kpi kpi--neutral">
        <div class="kpi__icon"><CIcon name="org" :size="20" /></div>
        <div class="kpi__body">
          <div class="kpi__label">门店总数</div>
          <div class="kpi__value">{{ kpis.total }}</div>
        </div>
      </div>
    </div>

    <!-- 筛选 -->
    <CCard padding="md">
      <div class="mt-toolbar">
        <div class="filters">
          <CSelect v-model="fRegion" :options="regionOptions" />
          <CSelect v-model="fStatus" :options="statusOptions" />
          <CSelect v-model="fNature" :options="natureOptions" />
          <CInput v-model="keyword" placeholder="搜索编码/名称/城市" />
        </div>
        <CButton v-if="tenant.error" variant="secondary" @click="tenant.fetchAll(true)">
          <CIcon name="refresh" :size="16" /> 重试
        </CButton>
      </div>
    </CCard>

    <!-- 表格 -->
    <CCard padding="none" class="mt-table-card">
      <CTable :columns="columns" :rows="filtered" row-key="id" stripe :empty-text="emptyText">
        <template #col-nature="{ row }">
          <CStatusPill :status="natureTone(row.nature)" dot>{{ row.nature }}</CStatusPill>
        </template>
        <template #col-status="{ row }">
          <CStatusPill :status="statusTone(row.status as TenantStatus)" dot>{{ row.statusText }}</CStatusPill>
        </template>
      </CTable>
    </CCard>

    <p class="mt-footnote">
      数据源：GET /api/stores（store-service）。数据范围随登录人数据域（DataScope）：区域经理见本区门店，集团账号见全部门店。
      门店新建 / 编辑 / 停用暂不支持（已入 Backlog），本页只读。
    </p>
  </div>
</template>

<style scoped>
.mt-page { display: flex; flex-direction: column; gap: var(--s-md); }

/* KPI */
.mt-kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); }
.kpi {
  display: flex; align-items: center; gap: var(--s-md);
  padding: var(--s-md); border-radius: var(--r-xl);
  background: var(--c-surface); border: 1px solid var(--c-border-light);
}
.kpi__icon {
  width: 44px; height: 44px; border-radius: var(--r-lg);
  display: flex; align-items: center; justify-content: center; flex: none;
}
.kpi--brand .kpi__icon { background: var(--c-brand-soft); color: var(--c-brand); }
.kpi--warning .kpi__icon { background: var(--c-warning-bg, #FFF7E6); color: var(--c-warning-fg); }
.kpi--muted .kpi__icon { background: var(--c-surface, #f7f8fa); color: var(--c-text-3); }
.kpi--neutral .kpi__icon { background: var(--c-info-bg, #EAF2FF); color: var(--c-info-fg); }
.kpi__label { font-size: var(--t-xs); color: var(--c-text-3); }
.kpi__value { font-size: var(--t-xl); font-weight: 700; color: var(--c-text); line-height: 1.2; }

/* toolbar */
.mt-toolbar { display: flex; align-items: center; justify-content: space-between; gap: var(--s-md); flex-wrap: nowrap; }
.mt-toolbar > .cbtn { flex-shrink: 0; white-space: nowrap; }
.filters { display: flex; gap: var(--s-sm); flex: 1; min-width: 0; flex-wrap: nowrap; overflow-x: auto; align-items: center; }
.filters > :deep(.csel) { flex-shrink: 0; }
.filters > :deep(.cinput) { flex: 1; min-width: 180px; max-width: 260px; }

/* table */
.mt-table-card :deep(.ctable-wrap) { border-radius: var(--r-lg); overflow: hidden; }

/* footnote */
.mt-footnote { margin: 0; font-size: var(--t-xs); color: var(--c-text-4, var(--c-text-3)); line-height: 1.6; }
</style>
