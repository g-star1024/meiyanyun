<script setup lang="ts">
/* ============================================================
 * C-Line-Chart 折线/面积图（零依赖 SVG，token 序列色）
 * categories: x 轴标签；series: { name, values }
 * 铁律：禁止裸值，全部引用 tokens.css 变量
 * ============================================================ */
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    categories: string[]
    series: { name: string; values: number[] }[]
    height?: number
    unit?: string
    area?: boolean
    max?: number
    min?: number
  }>(),
  { height: 240, unit: '', area: true, max: 0, min: 0 },
)

const W = 1000
const PAD_L = 44
const PAD_R = 12
const PAD_T = 16
const PAD_B = 26

const plotW = computed(() => W - PAD_L - PAD_R)
const plotH = computed(() => props.height - PAD_T - PAD_B)

const allVals = computed(() => props.series.flatMap((s) => s.values))
const dMax = computed(() =>
  props.max > 0 ? props.max : (allVals.value.length ? Math.max(...allVals.value) : 1) || 1,
)
const dMin = computed(() => (props.min !== 0 ? props.min : 0))
const span = computed(() => {
  const s = dMax.value - dMin.value
  return s === 0 ? 1 : s
})

function xAt(i: number) {
  const n = props.categories.length
  if (n <= 1) return PAD_L + plotW.value / 2
  return PAD_L + (i / (n - 1)) * plotW.value
}
function yAt(v: number) {
  return PAD_T + plotH.value - ((v - dMin.value) / span.value) * plotH.value
}
function colorOf(i: number) {
  return `var(--c-series-${(i % 8) + 1})`
}

const paths = computed(() =>
  props.series.map((s, si) => {
    if (!s.values.length) return { line: '', area: '', color: colorOf(si), name: s.name }
    const pts = s.values.map((v, i) => `${xAt(i).toFixed(1)},${yAt(v).toFixed(1)}`)
    const line = 'M' + pts.join(' L')
    const baseY = (PAD_T + plotH.value).toFixed(1)
    const area = props.area
      ? line + ` L${xAt(s.values.length - 1).toFixed(1)},${baseY} L${xAt(0).toFixed(1)},${baseY} Z`
      : ''
    return { line, area, color: colorOf(si), name: s.name }
  }),
)

const ticks = computed(() => {
  return [0, 0.25, 0.5, 0.75, 1].map((r) => ({
    y: (PAD_T + plotH.value - r * plotH.value).toFixed(1),
    v: Math.round(dMin.value + r * span.value),
  }))
})
function fmt(n: number) {
  return n >= 10000 ? (n / 10000).toFixed(1) + '万' : String(n)
}
</script>

<template>
  <div class="lc">
    <svg :viewBox="`0 0 ${W} ${height}`" class="lc__svg" preserveAspectRatio="none">
      <!-- 网格 -->
      <line
        v-for="(t, i) in ticks"
        :key="'g' + i"
        :x1="PAD_L"
        :x2="W - PAD_R"
        :y1="t.y"
        :y2="t.y"
        class="lc__grid"
      />
      <!-- 面积 -->
      <path
        v-for="(p, i) in paths"
        v-show="area"
        :key="'a' + i"
        :d="p.area"
        :fill="p.color"
        fill-opacity="0.12"
      />
      <!-- 折线 -->
      <path
        v-for="(p, i) in paths"
        :key="'l' + i"
        :d="p.line"
        :stroke="p.color"
        class="lc__line"
        fill="none"
      />
      <!-- 数据点 -->
      <template v-for="(p, si) in paths" :key="'d' + si">
        <circle
          v-for="(v, vi) in series[si].values"
          :key="vi"
          :cx="xAt(vi)"
          :cy="yAt(v)"
          r="3"
          :fill="p.color"
        />
      </template>
      <!-- y 轴刻度 -->
      <text
        v-for="(t, i) in ticks"
        :key="'t' + i"
        :x="PAD_L - 6"
        :y="Number(t.y) + 4"
        class="lc__tick"
        text-anchor="end"
      >{{ fmt(t.v) }}</text>
    </svg>
    <!-- x 轴标签 -->
    <div class="lc__xaxis">
      <span
        v-for="(c, i) in categories"
        :key="i"
        class="lc__xlabel"
        :style="{ left: ((i / Math.max(1, categories.length - 1)) * 100) + '%' }"
      >{{ c }}</span>
    </div>
    <!-- legend -->
    <div v-if="series.length > 1" class="lc__legend">
      <span v-for="(s, i) in series" :key="i" class="lc__legend-item">
        <span class="lc__legend-dot" :style="{ background: colorOf(i) }" />{{ s.name }}
      </span>
    </div>
  </div>
</template>

<style scoped>
.lc {
  width: 100%;
}
.lc__svg {
  width: 100%;
  height: v-bind('height + "px"');
  display: block;
  overflow: visible;
}
.lc__grid {
  stroke: var(--c-chart-grid);
  stroke-width: 1;
  stroke-dasharray: 3 4;
}
.lc__line {
  stroke-width: 2.5;
  vector-effect: non-scaling-stroke;
  stroke-linejoin: round;
  stroke-linecap: round;
}
.lc__tick {
  font-size: 11px;
  fill: var(--c-chart-label);
}
.lc__xaxis {
  position: relative;
  height: 18px;
  margin-top: 2px;
}
.lc__xlabel {
  position: absolute;
  transform: translateX(-50%);
  font-size: var(--t-xs);
  color: var(--c-chart-label);
  white-space: nowrap;
}
.lc__legend {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-md);
  margin-top: var(--s-sm);
  justify-content: center;
}
.lc__legend-item {
  display: inline-flex;
  align-items: center;
  gap: var(--s-xxs);
  font-size: var(--t-xs);
  color: var(--c-text-3);
}
.lc__legend-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
}
</style>
