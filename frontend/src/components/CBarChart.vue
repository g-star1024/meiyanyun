<script setup lang="ts">
/* ============================================================
 * C-Bar-Chart 柱状图（零依赖 SVG/div，token 序列色）
 * orientation:
 *  - 'vertical'   分组柱状（多序列对比，如多店业绩）
 *  - 'horizontal' 横向条形（排行/赛马榜，单序列）
 * items: { label, values: number[] }
 * series: 序列名（决定 legend 与颜色）；单序列传 [''] 或省略
 * 铁律：禁止裸值，全部引用 tokens.css 变量
 * ============================================================ */
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    items: { label: string; values: number[] }[]
    series?: string[]
    orientation?: 'vertical' | 'horizontal'
    height?: number
    unit?: string
    max?: number
    showValue?: boolean
  }>(),
  {
    series: () => [],
    orientation: 'vertical',
    height: 240,
    unit: '',
    max: 0,
    showValue: true,
  },
)

const isH = computed(() => props.orientation === 'horizontal')
const seriesCount = computed(() => {
  const maxLen = Math.max(1, ...props.items.map((i) => i.values.length))
  return maxLen
})
const seriesNames = computed(() =>
  props.series.length
    ? props.series
    : Array.from({ length: seriesCount.value }, (_, i) => `系列${i + 1}`),
)
const colorOf = (i: number) => `var(--c-series-${(i % 8) + 1})`

const computedMax = computed(() => {
  if (props.max > 0) return props.max
  let m = 0
  props.items.forEach((it) => it.values.forEach((v) => (m = Math.max(m, v))))
  if (m === 0) m = 1
  // 取整到友好刻度
  const pow = Math.pow(10, Math.floor(Math.log10(m)))
  return Math.ceil(m / pow) * pow
})

const gridTicks = computed(() => {
  const m = computedMax.value
  return [0, 0.25, 0.5, 0.75, 1].map((r) => ({ r, v: Math.round(m * r) }))
})

function fmt(n: number) {
  return n >= 10000 ? (n / 10000).toFixed(1) + '万' : String(n)
}
</script>

<template>
  <div class="bc" :class="isH ? 'bc--h' : 'bc--v'">
    <!-- ===== 纵向分组柱状 ===== -->
    <template v-if="!isH">
      <div class="bc__plot" :style="{ height: height + 'px' }">
        <div class="bc__grid">
          <div v-for="t in gridTicks" :key="t.r" class="bc__gridline" :style="{ bottom: t.r * 100 + '%' }">
            <span class="bc__tick">{{ fmt(t.v) }}</span>
          </div>
        </div>
        <div class="bc__groups">
          <div v-for="(it, gi) in items" :key="gi" class="bc__group">
            <div class="bc__bars">
              <div
                v-for="(v, vi) in it.values"
                :key="vi"
                class="bc__bar"
                :style="{
                  height: (v / computedMax) * 100 + '%',
                  background: colorOf(vi),
                }"
              >
                <span v-if="showValue" class="bc__barval">{{ fmt(v) }}{{ unit }}</span>
              </div>
            </div>
            <div class="bc__cat">{{ it.label }}</div>
          </div>
        </div>
      </div>
    </template>

    <!-- ===== 横向条形（排行） ===== -->
    <template v-else>
      <div class="bc__hlist">
        <div v-for="(it, gi) in items" :key="gi" class="bc__hrow">
          <div class="bc__hlabel">{{ it.label }}</div>
          <div class="bc__htrack">
            <div
              class="bc__hfill"
              :style="{ width: (it.values[0] / computedMax) * 100 + '%', background: colorOf(gi) }"
            >
              <span v-if="showValue" class="bc__hval">{{ fmt(it.values[0]) }}{{ unit }}</span>
            </div>
          </div>
        </div>
      </div>
    </template>

    <!-- legend -->
    <div v-if="seriesNames.length > 1 && !isH" class="bc__legend">
      <span v-for="(s, i) in seriesNames" :key="i" class="bc__legend-item">
        <span class="bc__legend-dot" :style="{ background: colorOf(i) }" />{{ s }}
      </span>
    </div>
  </div>
</template>

<style scoped>
.bc {
  width: 100%;
}
/* ---------- 纵向 ---------- */
.bc__plot {
  position: relative;
  width: 100%;
}
.bc__grid {
  position: absolute;
  inset: 0 0 22px 0;
  pointer-events: none;
}
.bc__gridline {
  position: absolute;
  left: 0;
  right: 0;
  border-top: 1px dashed var(--c-chart-grid);
}
.bc__tick {
  position: absolute;
  left: 0;
  top: -8px;
  font-size: var(--t-xs);
  color: var(--c-chart-label);
  background: var(--c-surface);
  padding-right: 4px;
}
.bc__groups {
  position: absolute;
  inset: 0 0 22px 28px;
  display: flex;
  align-items: flex-end;
  gap: var(--s-sm);
}
.bc__group {
  flex: 1;
  height: 100%;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  align-items: center;
  min-width: 0;
}
.bc__bars {
  flex: 1;
  width: 100%;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  gap: 4px;
}
.bc__bar {
  flex: 1;
  max-width: 28px;
  min-width: 6px;
  border-radius: var(--r-sm) var(--r-sm) 0 0;
  position: relative;
  display: flex;
  justify-content: center;
  transition: height 0.4s ease;
}
.bc__barval {
  position: absolute;
  top: -18px;
  font-size: 11px;
  color: var(--c-text-2);
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}
.bc__cat {
  height: 22px;
  display: flex;
  align-items: center;
  font-size: var(--t-xs);
  color: var(--c-text-3);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 100%;
}
/* ---------- 横向 ---------- */
.bc__hlist {
  display: flex;
  flex-direction: column;
  gap: var(--s-md);
}
.bc__hrow {
  display: flex;
  align-items: center;
  gap: var(--s-md);
}
.bc__hlabel {
  width: 96px;
  flex-shrink: 0;
  font-size: var(--t-sm);
  color: var(--c-text-2);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  text-align: right;
}
.bc__htrack {
  flex: 1;
  min-width: 0;
  background: var(--c-chart-track);
  border-radius: 999px;
  height: 16px;
  overflow: hidden;
}
.bc__hfill {
  height: 100%;
  border-radius: 999px;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  padding-right: 8px;
  min-width: 2px;
  transition: width 0.4s ease;
}
.bc__hval {
  font-size: 11px;
  color: #fff;
  font-weight: 600;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}
/* ---------- legend ---------- */
.bc__legend {
  display: flex;
  flex-wrap: wrap;
  gap: var(--s-md);
  margin-top: var(--s-sm);
  justify-content: center;
}
.bc__legend-item {
  display: inline-flex;
  align-items: center;
  gap: var(--s-xxs);
  font-size: var(--t-xs);
  color: var(--c-text-3);
}
.bc__legend-dot {
  width: 10px;
  height: 10px;
  border-radius: 3px;
}
</style>
