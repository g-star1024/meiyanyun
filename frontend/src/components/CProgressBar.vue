<script setup lang="ts">
/* ============================================================
 * C-ProgressBar 线性进度条（目标完成度 / 占比）
 * value 0~100；color 取 token 序列色或自定义
 * 铁律：禁止裸值，全部引用 tokens.css 变量
 * ============================================================ */
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    value: number
    max?: number
    color?: string
    height?: number
    showLabel?: boolean
    label?: string
  }>(),
  { max: 100, color: 'var(--c-brand)', height: 8, showLabel: true, label: '' },
)

const pct = computed(() => {
  const v = Math.max(0, Math.min(100, (props.value / props.max) * 100))
  return Math.round(v)
})
</script>

<template>
  <div class="pbar">
    <div class="pbar__track" :style="{ height: height + 'px' }">
      <div class="pbar__fill" :style="{ width: pct + '%', background: color }" />
    </div>
    <span v-if="showLabel" class="pbar__label">{{ label || pct + '%' }}</span>
  </div>
</template>

<style scoped>
.pbar {
  display: flex;
  align-items: center;
  gap: var(--s-sm);
  width: 100%;
}
.pbar__track {
  flex: 1;
  min-width: 0;
  background: var(--c-chart-track);
  border-radius: 999px;
  overflow: hidden;
}
.pbar__fill {
  height: 100%;
  border-radius: 999px;
  transition: width 0.4s ease;
}
.pbar__label {
  font-size: var(--t-xs);
  color: var(--c-text-2);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  flex-shrink: 0;
}
</style>
