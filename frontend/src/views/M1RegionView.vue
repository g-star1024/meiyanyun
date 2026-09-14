<script setup lang="ts">
// M1 集团管控 · 区域管理（B49 卡3 接真 · 只读）
// 数据源：GET /api/org/tree（六区组织框架）+ GET /api/stores/regions/dist（六区门店统计，集团聚合视角）。
// 写侧收窄：区域新建/编辑/停用后端不支持（入 Backlog），本页不提供写操作。
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CInput from '@/components/CInput.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import { useM1RegionStore, type RegionStatus } from '@/stores/m1Region'

const region = useM1RegionStore()
onMounted(() => region.fetchAll())

const keyword = ref('')
const fStatus = ref('')

const filtered = computed(() => {
  const kw = keyword.value.trim()
  return region.withStats.filter((r) => {
    if (fStatus.value && r.status !== fStatus.value) return false
    if (kw && !`${r.code} ${r.name} ${r.orgName} ${r.managerName}`.includes(kw)) return false
    return true
  })
})

const kpis = computed(() => {
  const list = region.withStats
  return {
    active: list.filter((r) => r.status === 'ACTIVE').length,
    total: list.length,
    stores: list.reduce((s, r) => s + r.storeCount, 0),
    operating: list.reduce((s, r) => s + r.operatingCount, 0),
  }
})

const emptyText = computed(() => {
  if (region.loading) return '加载中…'
  if (region.error) return region.error
  return '暂无符合条件的区域'
})

function statusTone(s: RegionStatus) { return s === 'ACTIVE' ? 'success' : 'disabled' }
</script>

<template>
  <div class="mr-page">
    <div class="mr-kpis">
      <div class="kpi kpi--brand">
        <div class="kpi__icon"><CIcon name="org" :size="20" /></div>
        <div class="kpi__body"><div class="kpi__label">运营中区域</div><div class="kpi__value">{{ kpis.active }}</div></div>
      </div>
      <div class="kpi kpi--neutral">
        <div class="kpi__icon"><CIcon name="box" :size="20" /></div>
        <div class="kpi__body"><div class="kpi__label">区域总数</div><div class="kpi__value">{{ kpis.total }}</div></div>
      </div>
      <div class="kpi kpi--info">
        <div class="kpi__icon"><CIcon name="store" :size="20" /></div>
        <div class="kpi__body"><div class="kpi__label">门店总数</div><div class="kpi__value">{{ kpis.stores }}</div></div>
      </div>
      <div class="kpi kpi--success">
        <div class="kpi__icon"><CIcon name="pos" :size="20" /></div>
        <div class="kpi__body"><div class="kpi__label">营业中门店</div><div class="kpi__value">{{ kpis.operating }}</div></div>
      </div>
    </div>

    <CCard padding="md">
      <div class="mr-toolbar">
        <div class="filters">
          <select v-model="fStatus" class="sel">
            <option value="">全部状态</option>
            <option value="ACTIVE">运营中</option>
            <option value="INACTIVE">已停用</option>
          </select>
          <CInput v-model="keyword" placeholder="搜索编码/名称/经理" />
        </div>
        <CButton v-if="region.error" variant="secondary" @click="region.fetchAll(true)">
          <CIcon name="refresh" :size="16" /> 重试
        </CButton>
      </div>
    </CCard>

    <div class="mr-grid">
      <CCard v-for="r in filtered" :key="r.id" padding="none" class="region-card" :class="{ 'region-card--inactive': r.status === 'INACTIVE' }">
        <div class="region-card__head">
          <div class="region-card__title">
            <span class="region-card__code">{{ r.code }}</span>
            <span class="region-card__name">{{ r.orgName }}</span>
          </div>
          <CStatusPill :status="statusTone(r.status)" dot>{{ r.statusText }}</CStatusPill>
        </div>
        <div class="region-card__body">
          <div class="region-stat">
            <div class="region-stat__label">区域经理</div>
            <div class="region-stat__value">{{ r.managerName }}</div>
          </div>
          <div class="region-stat">
            <div class="region-stat__label">门店数</div>
            <div class="region-stat__value">{{ r.storeCount }}<span class="region-stat__sub">（营业 {{ r.operatingCount }}）</span></div>
          </div>
          <div class="region-stat">
            <div class="region-stat__label">经营性质</div>
            <div class="region-stat__value region-stat__value--sm">直营 {{ r.ownCnt }} · 联营 {{ r.jointCnt }}</div>
          </div>
          <div class="region-stat">
            <div class="region-stat__label">筹建 / 关店</div>
            <div class="region-stat__value region-stat__value--sm">{{ r.buildingCnt }} / {{ r.closedCnt }}</div>
          </div>
        </div>
      </CCard>
      <div v-if="filtered.length === 0" class="empty">{{ emptyText }}</div>
    </div>

    <p class="mr-footnote">
      数据源：GET /api/org/tree + GET /api/stores/regions/dist（集团聚合视角，不受登录人数据域收窄影响）。
      区域新建 / 编辑 / 停用暂不支持（已入 Backlog），本页只读。
    </p>
  </div>
</template>

<style scoped>
.mr-page { display: flex; flex-direction: column; gap: var(--s-md); }

.mr-kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--s-md); }
.kpi { display: flex; align-items: center; gap: var(--s-md); padding: var(--s-md); border-radius: var(--r-xl); background: var(--c-surface); border: 1px solid var(--c-border-light); }
.kpi__icon { width: 44px; height: 44px; border-radius: var(--r-lg); display: flex; align-items: center; justify-content: center; flex: none; }
.kpi--brand .kpi__icon { background: var(--c-brand-soft); color: var(--c-brand); }
.kpi--neutral .kpi__icon { background: var(--c-surface, #f7f8fa); color: var(--c-text-3); }
.kpi--info .kpi__icon { background: var(--c-info-bg, #EAF2FF); color: var(--c-info-fg); }
.kpi--success .kpi__icon { background: var(--c-success-bg, #f0fbf0); color: var(--c-success-fg); }
.kpi__label { font-size: var(--t-xs); color: var(--c-text-3); }
.kpi__value { font-size: var(--t-xl); font-weight: 700; color: var(--c-text); line-height: 1.2; }

.mr-toolbar { display: flex; align-items: center; justify-content: space-between; gap: var(--s-md); flex-wrap: nowrap; }
.mr-toolbar > .cbtn { flex-shrink: 0; white-space: nowrap; }
.filters { display: flex; gap: var(--s-sm); flex: 1; min-width: 0; flex-wrap: nowrap; overflow-x: auto; align-items: center; }
.filters .sel { flex-shrink: 0; }
.filters > :deep(.cinput) { flex: 1; min-width: 200px; max-width: 280px; }
.sel { height: 36px; padding: 0 12px; border: 1px solid var(--c-border); border-radius: var(--r-md); font-size: var(--t-sm); color: var(--c-text); background: var(--c-surface); }

.mr-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--s-md); }
.region-card { display: flex; flex-direction: column; transition: box-shadow .15s; }
.region-card:hover { box-shadow: var(--shadow-pop, 0 8px 24px rgba(0,0,0,.08)); }
.region-card--inactive { opacity: .65; }
.region-card__head { display: flex; align-items: center; justify-content: space-between; padding: var(--s-md); border-bottom: 1px solid var(--c-border-light); }
.region-card__title { display: flex; align-items: center; gap: var(--s-sm); }
.region-card__code { font-size: var(--t-xs); color: var(--c-text-3); font-family: var(--t-number, monospace); background: var(--c-surface, #f7f8fa); padding: 2px 8px; border-radius: var(--r-sm); }
.region-card__name { font-size: var(--t-md); font-weight: 700; color: var(--c-text); }
.region-card__body { padding: var(--s-md); display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md); flex: 1; }
.region-stat { display: flex; flex-direction: column; gap: 4px; }
.region-stat__label { font-size: var(--t-xs); color: var(--c-text-3); }
.region-stat__value { font-size: var(--t-lg); font-weight: 700; color: var(--c-text); }
.region-stat__value--sm { font-size: var(--t-md); }
.region-stat__sub { font-size: var(--t-xs); font-weight: 400; color: var(--c-text-3); }
.empty { grid-column: 1 / -1; text-align: center; padding: var(--s-xl); color: var(--c-text-3); font-size: var(--t-sm); }

.mr-footnote { margin: 0; font-size: var(--t-xs); color: var(--c-text-4, var(--c-text-3)); line-height: 1.6; }
</style>
