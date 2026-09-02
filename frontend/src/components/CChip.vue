<script setup lang="ts">
// C-Chip 选择胶囊
// Ardot 真值：M4-02 日期/时段 chip
// 默认：白底灰边深字；选中：粉底白字
withDefaults(
  defineProps<{
    modelValue?: boolean
    label?: string
  }>(),
  { modelValue: false, label: '' },
)

const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void }>()

function toggle() {
  emit('update:modelValue', true)
}
</script>

<template>
  <button
    type="button"
    class="cchip"
    :class="{ 'is-active': modelValue }"
    @click="toggle"
  >
    <slot>{{ label }}</slot>
  </button>
</template>

<style scoped>
.cchip {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 36px;
  padding: 0 16px;
  border: 1px solid var(--c-border-light);
  border-radius: var(--r-md);
  background: var(--c-surface);
  color: var(--c-text);
  font-size: var(--t-sm);
  font-weight: 700;
  line-height: 1;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s, color 0.15s;
}
.cchip:hover:not(.is-active) {
  border-color: var(--c-brand-border);
}
.cchip.is-active {
  background: var(--c-brand);
  border-color: var(--c-brand);
  color: #fff;
}
</style>
