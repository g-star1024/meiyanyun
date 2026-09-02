<script setup lang="ts">
/* ============================================================
 * C-Donut-Chart 环形图（零依赖 SVG，token 序列色）
 * data: { label, value, color? }[]；中心可显示总量/标题
 * 铁律：禁止裸值，全部引用 tokens.css 变量
 * ============================================================ */
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    data: { label: string; value: number; color?: string }[]
    size?: number
    thickness?: number
    centerLabel?: string
    centerValue?: string
    showLegend?: boolean
  }>(),
  { size: 160, thickness: 18, centerLabel: '', centerValue: '', showLegend: true },
)

const total = computed(() => props.data.reduce((s, d) => s + d.value, 0) || 1)
const r = computed(() => (props.size - props.thickness) / 2)
const c = computed(() => 2 * Math.PI * r.value)
const cx = computed(() => props.size / 2)
const cy = computed(() => props.size / 2)

const segments = computed(() => {
  let acc = 0
  return props.data.map((d, i) => {
    const frac = d.value / total.value
    const len = frac * c.value
    const seg = {
      dash: `${len} ${c.value - len}`,
      offset: -acc,
      color: d.color || `var(--c-series-${(i % 8) + 1})`,
      label: d.label,
      value: d.value,
      pct: Math.round(frac * 100),
    }
    acc += len
    return seg
  })
})
</script>

<template>
  <div class="dc">
    <div class="dc__chart" :style="{ width: size + 'px', height: size + 'px' }">
      <svg :width="size" :height="size" :viewBox="`0 0 ${size} ${size}`">
        <circle :cx="cx" :cy="cy" :r="r" fill="none" stroke="var(--c-chart-track)" :stroke-width="thickness" />
        <circle
          v-for="(s, i) in segments"
          :key="i"
          :cx="cx"
          :cy="cy"
          :r="r"
          fill="none"
          :stroke="s.color"
          :stroke-width="thickness"
          :stroke-dasharray="s.dash"
          :stroke-dashoffset="s.offset"
          transform="rotate(-90)"
          :transform-origin="`${cx} ${cy}`"
          stroke-linecap="butt"
        />
      </svg>
      <div v-if="centerValue || centerLabel" class="dc__center">
        <div v-if="centerValue" class="dc__cval">{{ centerValue }}</div>
        <div v-if="centerLabel" class="dc__clabel">{{ centerLabel }}</div>
      </div>
    </div>
    <ul v-if="showLegend" class="dc__legend">
      <li v-for="(s, i) in segments" :key="i" class="dc__legend-item">
        <span class="dc__legend-dot" :style="{ background: s.color }" />
        <span class="dc__legend-label">{{ s.label }}</span>
        <span class="dc__legend-pct">{{ s.pct }}%</span>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.dc {
  display: flex;
  align-items: center;
  gap: var(--s-lg);
  flex-wrap: wrap;
}
.dc__chart {
  position: relative;
  flex-shrink: 0;
}
.dc__chart svg {
  display: block;
}
.dc__center {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  pointer-events: none;
}
.dc__cval {
  font-size: var(--t-lg);
  font-weight: 700;
  color: var(--c-text);
  font-variant-numeric: tabular-nums;
}
.dc__clabel {
  font-size: var(--t-xs);
  color: var(--c-text-3);
  margin-top: 2px;
}
.dc__legend {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--s-xs);
  min-width: 120px;
}
.dc__legend-item {
  display: flex;
  align-items: center;
  gap: var(--s-xs);
  font-size: var(--t-sm);
  color: var(--c-text-2);
}
.dc__legend-dot {
  width: 10px;
  height: 10px;
  border-radius: 3px;
  flex-shrink: 0;
}
.dc__legend-label {
  flex: 1;
}
.dc__legend-pct {
  font-weight: 600;
  color: var(--c-text);
  font-variant-numeric: tabular-nums;
}
</style>
