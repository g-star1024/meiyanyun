<script setup lang="ts">
/* A1-08 智能排班 /ai/scheduling — 验收页：建议回填 M2-03 */
import { ref } from 'vue'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CKpi from '@/components/CKpi.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CTable from '@/components/CTable.vue'
import CSegmented from '@/components/CSegmented.vue'

const tab = ref('gantt')
const tabOptions = [
  { label: '排班建议', value: 'gantt' },
  { label: '成本模拟', value: 'cost' },
  { label: '对比分析', value: 'compare' },
]

const kpis = [
  { label: '预测客流', icon: 'customer', value: '1,280', tone: 'purple' as const },
  { label: '建议人力', icon: 'profile', value: '32人', tone: 'brand' as const },
  { label: '预估人力成本', icon: 'profile', value: '¥48,600', tone: 'orange' as const },
  { label: '与现排差异', icon: 'calendar', value: '-3人', tone: 'teal' as const },
]

const days = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
const shifts = [
  { name: '早班 09-15', counts: [5, 4, 5, 4, 5, 7, 6], color: 'var(--c-brand-soft)' },
  { name: '中班 12-18', counts: [4, 4, 4, 5, 5, 6, 5], color: 'var(--c-info-bg)' },
  { name: '晚班 15-21', counts: [3, 3, 4, 4, 5, 7, 6], color: 'var(--c-purple-soft)' },
]

const costRows = [
  { id: 1, name: '张美容师', role: '高级美容师', shift: '早班', hours: 6, cost: 480 },
  { id: 2, name: '李顾问', role: '咨询师', shift: '中班', hours: 6, cost: 540 },
  { id: 3, name: '王技师', role: '高级技师', shift: '晚班', hours: 6, cost: 520 },
  { id: 4, name: '赵护士', role: '护士', shift: '早班', hours: 6, cost: 420 },
  { id: 5, name: '陈前台', role: '前台', shift: '中班', hours: 6, cost: 360 },
  { id: 6, name: '刘美容师', role: '初级美容师', shift: '晚班', hours: 6, cost: 360 },
  { id: 7, name: '孙顾问', role: '高级咨询师', shift: '早班', hours: 6, cost: 600 },
  { id: 8, name: '周技师', role: '技师', shift: '中班', hours: 6, cost: 440 },
]
const costCols = [
  { key: 'name', label: '员工' }, { key: 'role', label: '岗位' },
  { key: 'shift', label: '建议班次' }, { key: 'hours', label: '工时', align: 'right' as const },
  { key: 'cost', label: '预估成本', align: 'right' as const },
]

function adopt() {
  window.alert('排班建议已回填 M2-03 排班管理，可在排班页微调后发布。')
}
</script>

<template>
  <div class="a1-sched">
    <div class="kpis"><CKpi v-for="k in kpis" :key="k.label" v-bind="k" /></div>
    <div class="bar"><CSegmented v-model="tab" :options="tabOptions" /><CButton variant="primary" @click="adopt">采纳建议并回填 M2-03</CButton></div>

    <CCard v-if="tab === 'gantt'" padding="lg">
      <template #header><h3>AI 排班建议甘特（基于客流预测）</h3></template>
      <div class="gantt">
        <div class="gantt__row gantt__head">
          <div class="gantt__label">班次</div>
          <div v-for="d in days" :key="d" class="gantt__cell">{{ d }}</div>
        </div>
        <div v-for="s in shifts" :key="s.name" class="gantt__row">
          <div class="gantt__label">{{ s.name }}</div>
          <div v-for="(c, i) in s.counts" :key="i" class="gantt__cell">
            <div class="bar" :style="{ background: s.color, height: c * 8 + 'px' }">{{ c }}</div>
          </div>
        </div>
      </div>
      <p class="hint">预测模型由 T4 提供，客流数据来自 T2；采纳后将回填 M2-03。</p>
    </CCard>

    <CCard v-else-if="tab === 'cost'" padding="lg">
      <template #header><h3>人力成本模拟</h3></template>
      <CTable :columns="costCols" :rows="costRows" row-key="id" stripe>
        <template #col-cost="{ value }">¥{{ value }}</template>
      </CTable>
      <div class="total">合计：¥{{ costRows.reduce((s, r) => s + r.cost, 0).toLocaleString() }} / 日</div>
    </CCard>

    <CCard v-else padding="lg">
      <template #header><h3>现排 vs AI 建议对比</h3></template>
      <div class="compare">
        <div class="compare__col"><h4>现排方案</h4><ul><li>总人力：35人</li><li>人力成本：¥52,400</li><li>高峰缺口：周六晚班 2人</li><li>人效：36.6 元/客流</li></ul></div>
        <div class="compare__col compare__col--ai"><h4>AI 建议 <CStatusPill status="success" dot>推荐</CStatusPill></h4><ul><li>总人力：32人</li><li>人力成本：¥48,600</li><li>高峰缺口：0</li><li>人效：38.0 元/客流</li><li>预计节省：¥3,800/日</li></ul></div>
      </div>
    </CCard>
  </div>
</template>

<style scoped>
.a1-sched { display: flex; flex-direction: column; gap: var(--s-lg); }
.kpis { display: flex; gap: var(--s-md); }
.bar { display: flex; justify-content: space-between; align-items: center; }
.gantt { display: flex; flex-direction: column; gap: 2px; }
.gantt__row { display: contents; }
.gantt__head .gantt__label, .gantt__head .gantt__cell { font-weight: 600; font-size: var(--t-xs); color: var(--c-text-2); }
.gantt__label { grid-column: 1; padding: var(--s-sm) var(--s-md); font-size: var(--t-sm); color: var(--c-text); display: flex; align-items: center; }
.gantt { display: grid; grid-template-columns: 120px repeat(7, 1fr); gap: 2px; }
.gantt__cell { display: flex; align-items: flex-end; justify-content: center; padding: var(--s-xs); background: var(--c-bg-page); border-radius: var(--r-sm); min-height: 70px; }
.bar { width: 100%; border-radius: var(--r-sm) var(--r-sm) 0 0; display: flex; align-items: flex-start; justify-content: center; font-size: 11px; color: var(--c-text); padding-top: 2px; min-width: 30px; }
.hint { font-size: var(--t-xs); color: var(--c-text-3); margin: var(--s-md) 0 0; }
.total { text-align: right; font-size: var(--t-md); font-weight: 700; color: var(--c-text); margin-top: var(--s-md); }
.compare { display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-lg); }
.compare__col { padding: var(--s-md); border: 1px solid var(--c-border-light); border-radius: var(--r-lg); }
.compare__col--ai { border-color: var(--c-brand); background: var(--c-brand-soft); }
.compare__col h4 { margin: 0 0 var(--s-sm); display: flex; align-items: center; gap: var(--s-sm); }
.compare__col ul { margin: 0; padding-left: var(--s-md); line-height: 2; font-size: var(--t-sm); color: var(--c-text-2); }
</style>
