<script setup lang="ts">
/* ============================================================
 * C-Segmented 分段控制器（今日/本周、月/季/年 等切换）
 * v-model 绑定当前 value；圆角胶囊，激活态品牌粉实底
 * 铁律：禁止裸值，全部引用 tokens.css 变量
 * ============================================================ */
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    modelValue: string
    options: { label: string; value: string }[]
    size?: 'sm' | 'md'
    disabled?: boolean
  }>(),
  { size: 'md', disabled: false },
)

const emit = defineEmits<{ (e: 'update:modelValue', v: string): void }>()

function pick(v: string) {
  if (props.disabled) return
  emit('update:modelValue', v)
}

const cls = computed(() => ['seg', `seg--${props.size}`, { 'is-disabled': props.disabled }])
</script>

<template>
  <div :class="cls" role="tablist">
    <button
      v-for="opt in options"
      :key="opt.value"
      class="seg__item"
      :class="{ 'is-active': opt.value === modelValue }"
      role="tab"
      :aria-selected="opt.value === modelValue"
      :disabled="disabled"
      @click="pick(opt.value)"
    >
      {{ opt.label }}
    </button>
  </div>
</template>

<style scoped>
.seg {
  display: inline-flex;
  gap: var(--s-xxs);
  padding: 4px;
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-card);
}
.seg__item {
  border: none;
  background: transparent;
  border-radius: 16px;
  font-family: inherit;
  font-weight: 600;
  color: var(--c-text-3);
  cursor: pointer;
  transition: all 0.15s;
  white-space: nowrap;
}
.seg--md .seg__item {
  height: 32px;
  padding: 0 var(--s-md);
  font-size: var(--t-sm);
}
.seg--sm .seg__item {
  height: 28px;
  padding: 0 var(--s-sm);
  font-size: var(--t-xs);
}
.seg__item.is-active {
  background: var(--c-brand);
  color: #fff;
  box-shadow: 0 2px 6px rgba(255, 107, 157, 0.3);
}
.seg__item:not(.is-active):hover {
  color: var(--c-text);
  background: var(--c-brand-soft);
}
.seg.is-disabled { opacity: .55; }
.seg.is-disabled .seg__item { cursor: not-allowed; }
.seg.is-disabled .seg__item:not(.is-active):hover { background: transparent; color: var(--c-text-3); }
</style>
