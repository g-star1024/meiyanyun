<script setup lang="ts">
/* ============================================================
 * C-KPI 关键指标卡（统一组件，消除 .kpi 重复定义）
 * 支持：label / value / tone / trend / icon
 *   - 无 icon：传统纵向卡片（label 上 / value 下）
 *   - 有 icon：横向卡片（左彩色软底图标方块 + 右 label/value）
 * Ardot 规范：border-radius = --r-xl (12px)
 * ============================================================ */
import { computed } from 'vue'
import CIcon from './CIcon.vue'

const props = withDefaults(
  defineProps<{
    value: string
    label: string
    /** 数值/图标色调：text(默认) / brand(粉) / teal(青) / orange(橙) / warning(黄) / danger(红) / success(绿) / purple(紫) / blue(蓝) */
    tone?: 'text' | 'brand' | 'teal' | 'orange' | 'warning' | 'danger' | 'success' | 'purple' | 'blue'
    trend?: string
    trendUp?: boolean
    trendGood?: boolean
    /** 传入 CIcon 名称后显示左侧彩色图标方块 */
    icon?: string
  }>(),
  {
    tone: 'text',
    trend: '',
    trendUp: false,
    trendGood: true,
    icon: '',
  },
)

const trendColor = computed(() =>
  props.trendGood ? 'var(--c-trend-up)' : 'var(--c-trend-down)',
)

// 图标方块配色（前景 + 软底）
const iconStyle = computed(() => {
  switch (props.tone) {
    case 'brand':   return { color: 'var(--c-brand)', background: 'var(--c-brand-soft)' }
    case 'teal':
    case 'success': return { color: 'var(--c-success-fg)', background: 'var(--c-success-bg)' }
    case 'orange':
    case 'warning': return { color: 'var(--c-warning-fg)', background: 'var(--c-warning-bg)' }
    case 'danger':  return { color: 'var(--c-danger-fg)', background: 'var(--c-danger-bg)' }
    case 'purple':  return { color: 'var(--c-purple)', background: 'var(--c-purple-soft)' }
    case 'blue':    return { color: '#4c7dff', background: '#eef3ff' }
    default:        return { color: 'var(--c-text-2)', background: 'var(--c-bg-right)' }
  }
})
</script>

<script lang="ts">
export default { name: 'CKpi' }
</script>

<template>
  <div class="ckpi" :class="[`ckpi--${tone}`, { 'ckpi--with-icon': icon }]">
    <span v-if="icon" class="ckpi__icon" :style="iconStyle">
      <CIcon :name="icon as any" :size="24" />
    </span>
    <div class="ckpi__body">
      <div class="ckpi__label">{{ label }}</div>
      <div class="ckpi__value-row">
        <span class="ckpi__value">{{ value }}</span>
        <span v-if="trend" class="ckpi__trend" :style="{ color: trendColor }">
          <svg class="ckpi__arrow" viewBox="0 0 10 8" width="9" height="7" fill="currentColor">
            <path :d="trendUp ? 'M5 0L10 8H0z' : 'M5 8L10 0H0z'" />
          </svg>
          {{ trend }}
        </span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ckpi {
  flex: 1;
  min-width: 0;
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: var(--r-xl);
  padding: var(--s-md);
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 4px;
}

/* 带图标：横向布局，左对齐 */
.ckpi--with-icon {
  flex-direction: row;
  align-items: center;
  justify-content: flex-start;
  gap: var(--s-md);
}
.ckpi__icon {
  width: 52px;
  height: 52px;
  border-radius: var(--r-lg);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.ckpi__body {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.ckpi__label {
  font-size: var(--t-xs);
  color: var(--c-text-3);
  line-height: var(--lh-xs);
}

.ckpi__value-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
}

.ckpi__value {
  font-size: 26px;
  font-weight: 700;
  line-height: 1.2;
  font-variant-numeric: tabular-nums;
}

.ckpi__trend {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  font-weight: 400;
}
.ckpi__arrow { flex-shrink: 0; }

/* 数值色调 */
.ckpi--text     .ckpi__value { color: var(--c-text) }
.ckpi--brand    .ckpi__value { color: var(--c-brand) }
.ckpi--teal     .ckpi__value { color: var(--c-teal-dark) }
.ckpi--orange   .ckpi__value { color: var(--c-orange-dark) }
.ckpi--warning  .ckpi__value { color: var(--c-warning-fg) }
.ckpi--danger   .ckpi__value { color: var(--c-danger-fg) }
.ckpi--success  .ckpi__value { color: var(--c-success-fg) }
.ckpi--purple   .ckpi__value { color: var(--c-purple) }
.ckpi--blue     .ckpi__value { color: #2f5fe0 }
</style>
