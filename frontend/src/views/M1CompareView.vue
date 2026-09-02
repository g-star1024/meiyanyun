<template>
  <div class="cmp">
    <div class="cmp__body">
      <!-- 左：门店选择 + 排行榜 -->
      <CCard title="选择对标门店（最多 5 家）" padding="none" class="cmp__left">
        <div class="picker">
          <label v-for="s in cmp.stores" :key="s.id" class="pick" :class="{ 'is-checked': cmp.selectedIds.includes(s.id) }">
            <input type="checkbox" :checked="cmp.selectedIds.includes(s.id)" :disabled="!cmp.selectedIds.includes(s.id) && cmp.selectedIds.length >= 5" @change="cmp.toggle(s.id)" />
            <span class="pick__dot" :style="{ background: colorOf(s.id) }" />
            <span class="pick__name">{{ s.name }}</span>
            <span class="pick__region">{{ s.region }}</span>
          </label>
        </div>
        <div class="ranking">
          <div class="ranking__h">综合排名</div>
          <div v-for="(s, i) in cmp.ranked" :key="s.id" class="rank"
               :class="{ 'rank--sel': cmp.selectedIds.includes(s.id) }" @click="cmp.toggle(s.id)">
            <span class="rank__no" :class="'rank__no--' + (i + 1)">{{ i + 1 }}</span>
            <span class="rank__dot" :style="{ background: colorOf(s.id) }" />
            <span class="rank__name">{{ s.name }}</span>
            <span class="rank__score">{{ s.score }}</span>
          </div>
        </div>
      </CCard>

      <!-- 右：雷达 + 对比表 -->
      <div class="cmp__right">
        <CCard title="多维雷达对比" padding="lg">
          <div class="radar-wrap">
            <svg viewBox="0 0 320 320" class="radar">
              <!-- 网格 -->
              <polygon v-for="ring in rings" :key="ring" class="radar__grid" :points="gridPoints(ring)" />
              <line v-for="(p, i) in axisPoints" :key="'a' + i" class="radar__axis" :x1="160" :y1="160" :x2="p.x" :y2="p.y" />
              <!-- 各店多边形 -->
              <polygon v-for="s in cmp.selectedStores" :key="s.id" class="radar__poly"
                       :points="polyPoints(s.id)" :style="{ '--pc': colorOf(s.id) }" />
              <!-- 轴标签 -->
              <text v-for="(_p, i) in axisPoints" :key="'l' + i" class="radar__label"
                    :x="labelPos(i).x" :y="labelPos(i).y">{{ cmp.COMPARE_METRICS[i].label }}</text>
            </svg>
            <div class="radar-legend">
              <span v-for="s in cmp.selectedStores" :key="s.id">
                <i :style="{ background: colorOf(s.id) }" />{{ s.name }}
              </span>
            </div>
          </div>
        </CCard>

        <CCard title="指标明细对比" padding="none">
          <table class="cmptable">
            <thead>
              <tr><th>指标</th><th>基准</th>
                <th v-for="s in cmp.selectedStores" :key="s.id">
                  <span class="th-dot" :style="{ background: colorOf(s.id) }" />{{ shortName(s.name) }}
                </th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="m in cmp.COMPARE_METRICS" :key="m.key">
                <th class="cmptable__label">{{ m.label }}<span class="u">{{ m.unit }}</span></th>
                <td class="cmptable__bench">{{ m.benchmark }}</td>
                <td v-for="s in cmp.selectedStores" :key="s.id" class="cmptable__cell">
                  <div class="bar-cell">
                    <div class="bar-cell__fill" :style="{ width: barWidth(s.id, m.key) + '%', background: colorOf(s.id) }" />
                    <span class="bar-cell__v">{{ cmp.value(s.id, m.key) }}</span>
                  </div>
                </td>
              </tr>
              <tr class="cmptable__total">
                <th>综合得分</th><td>100</td>
                <td v-for="s in cmp.selectedStores" :key="s.id"><b>{{ cmp.score(s.id) }}</b></td>
              </tr>
            </tbody>
          </table>
        </CCard>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import CCard from '@/components/CCard.vue'
import { useM1CompareStore } from '@/stores/m1Compare'

const cmp = useM1CompareStore()
onMounted(() => {})

const COLORS = ['var(--c-series-1)', 'var(--c-series-2)', 'var(--c-series-3)', 'var(--c-series-4)', 'var(--c-series-5)']
function colorOf(id: string) {
  const i = cmp.stores.findIndex((s) => s.id === id)
  return COLORS[i % COLORS.length]
}
function shortName(n: string) { return n.replace('分院', '').replace('旗舰院', '') }

const R = 110
const N = cmp.COMPARE_METRICS.length
function axis(angle: number, radius: number) {
  const a = (Math.PI * 2 * angle) / N - Math.PI / 2
  return { x: 160 + Math.cos(a) * radius, y: 160 + Math.sin(a) * radius }
}
const axisPoints = Array.from({ length: N }, (_, i) => axis(i, R))
const rings = [0.25, 0.5, 0.75, 1]
function gridPoints(scale: number) {
  return Array.from({ length: N }, (_, i) => { const p = axis(i, R * scale); return p.x + ',' + p.y }).join(' ')
}
function radarVerts(storeId: string) {
  return cmp.radarPoints(storeId, R, 160, 160)
}
function polyPoints(storeId: string) {
  return radarVerts(storeId).map((p) => p.x + ',' + p.y).join(' ')
}
function labelPos(i: number) {
  const a = (Math.PI * 2 * i) / N - Math.PI / 2
  return { x: 160 + Math.cos(a) * (R + 20), y: 160 + Math.sin(a) * (R + 20) + 4 }
}
function barWidth(storeId: string, metricKey: string) {
  const range = cmp.metricRange(metricKey)
  if (range.max === range.min) return 50
  const v = cmp.value(storeId, metricKey)
  return Math.round(((v - range.min) / (range.max - range.min)) * 70 + 30)
}
</script>

<style scoped>
.cmp { display: flex; flex-direction: column; gap: var(--s-lg); }
.cmp__body { display: grid; grid-template-columns: 320px 1fr; gap: var(--s-lg); align-items: start; }
.picker { padding: var(--s-sm) var(--s-md); border-bottom: 1px solid var(--c-border); display: flex; flex-direction: column; gap: var(--s-xs); }
.pick { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-xs) var(--s-sm); border-radius: var(--r-sm); cursor: pointer; font-size: var(--t-sm); transition: background .15s; }
.pick:hover { background: var(--c-surface-muted); }
.pick.is-checked { background: var(--c-brand-soft); }
.pick input { margin: 0; width: 16px; height: 16px; accent-color: var(--c-brand); }
.pick__dot { width: 10px; height: 10px; border-radius: 50%; flex-shrink: 0; }
.pick__name { flex: 1; }
.pick__region { font-size: 10px; color: var(--c-text-3); }
.ranking { padding: var(--s-md); }
.ranking__h { font-weight: 600; font-size: var(--t-sm); margin-bottom: var(--s-sm); }
.rank { display: flex; align-items: center; gap: var(--s-sm); padding: var(--s-xs) var(--s-sm); border-radius: var(--r-sm); cursor: pointer; font-size: var(--t-sm); transition: background .15s; }
.rank:hover { background: var(--c-surface-muted); }
.rank--sel { background: var(--c-brand-soft); }
.rank__no { width: 24px; height: 24px; border-radius: 50%; background: var(--c-surface-muted); color: var(--c-text-2); display: flex; align-items: center; justify-content: center; font-size: var(--t-xs); font-weight: 700; flex-shrink: 0; }
.rank__no--1 { background: #f5c518; color: #fff; }
.rank__no--2 { background: #b0b8c1; color: #fff; }
.rank__no--3 { background: #cd7f32; color: #fff; }
.rank__dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.rank__name { flex: 1; }
.rank__score { font-weight: 700; font-variant-numeric: tabular-nums; }
.cmp__right { display: flex; flex-direction: column; gap: var(--s-lg); }
.radar-wrap { display: flex; flex-direction: column; align-items: center; padding: var(--s-sm) 0; }
.radar { width: 100%; max-width: 440px; height: auto; }
.radar__grid { fill: none; stroke: var(--c-border); stroke-width: 1; }
.radar__axis { stroke: var(--c-border); stroke-width: 1; }
.radar__poly { stroke-width: 2; fill: var(--pc); stroke: var(--pc); fill-opacity: .18; }
.radar__label { font-size: 12px; fill: var(--c-text-2); text-anchor: middle; font-weight: 500; }
.radar-legend { display: flex; flex-wrap: wrap; gap: var(--s-md); margin-top: var(--s-md); font-size: var(--t-xs); color: var(--c-text-2); justify-content: center; }
.radar-legend span { display: inline-flex; align-items: center; gap: var(--s-xs); }
.radar-legend i { width: 12px; height: 12px; border-radius: 3px; }
.cmptable { width: 100%; border-collapse: collapse; font-size: var(--t-sm); }
.cmptable th, .cmptable td { padding: var(--s-sm) var(--s-md); border-bottom: 1px solid var(--c-border); text-align: center; }
.cmptable thead th { background: var(--c-surface-muted); font-weight: 600; color: var(--c-text-2); }
.cmptable__label { text-align: left !important; font-weight: 600; }
.u { font-size: 10px; color: var(--c-text-3); font-weight: 400; margin-left: 3px; }
.cmptable__bench { color: var(--c-text-3); }
.th-dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-right: 4px; }
.bar-cell { position: relative; display: flex; align-items: center; justify-content: center; }
.bar-cell__fill { position: absolute; left: 0; top: 50%; transform: translateY(-50%); height: 22px; border-radius: 4px; opacity: .35; }
.bar-cell__v { position: relative; font-weight: 600; font-variant-numeric: tabular-nums; }
.cmptable__total td, .cmptable__total th { background: var(--c-brand-soft); border-bottom: none; font-weight: 600; }
@media (max-width: 900px) { .cmp__body { grid-template-columns: 1fr; } }
</style>
