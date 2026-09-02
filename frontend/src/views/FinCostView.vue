<script setup lang="ts">
/* M6-06 成本分析 /m6-cost — 只读镜像，TK 成本四分类，按门店/科目归集 */
import { computed, onMounted, ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import CKpi from '@/components/CKpi.vue'
import CSelect from '@/components/CSelect.vue'
import CDonutChart from '@/components/CDonutChart.vue'
import { useFinCostStore, type CostSubject } from '@/stores/finCost'
import { useAuthStore } from '@/stores/auth'

const store = useFinCostStore()
const auth = useAuthStore()
const canExport = computed(() => auth.can('finance:export'))

onMounted(() => store.init())

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

function exportCsv() {
  if (!canExport.value) return
  const head = '科目,明细项,门店,金额,来源,日期,备注\n'
  const rows = store.filtered.map((r) =>
    [store.SUBJECT_LABEL[r.subject], r.itemName, r.store, r.amount, r.source, r.occurredAt, r.memo ?? ''].join(','),
  ).join('\n')
  const blob = new Blob(['\uFEFF' + head + rows], { type: 'text/csv;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `成本分析-${new Date().toISOString().slice(0, 10)}.csv`
  a.click()
  URL.revokeObjectURL(a.href)
}
</script>

<template>
  <div class="fc">
    <div class="fc__head">
      <CKpi v-for="k in kpis" :key="k.label" :label="k.label" :value="k.value" :tone="k.tone" :sub="k.sub" :icon="k.icon" />
    </div>

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
</style>
