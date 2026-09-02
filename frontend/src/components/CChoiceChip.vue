<script setup lang="ts">
/* ============================================================
 * C-Choice-Chip 单选/多选胶囊
 * 对齐 Ardot C-FLD-Checkbox/Radio 选中态：粉底粉边粉字
 * ============================================================ */
const props = defineProps<{
  type: 'checkbox' | 'radio'
  modelValue: boolean | string | number
  label: string
  value?: string | number
  /** 实底选中态（深粉 bg + 白字），用于医疗风险/阳性发现项 */
  solid?: boolean
  /** 青色实底选中态（--c-teal bg + --c-teal-fg 字），用于"无"安全选项 */
  cyan?: boolean
}>()

const emit = defineEmits<{ (e: 'update:modelValue', v: boolean | string | number): void }>()

const isSelected = computed(() => {
  if (props.type === 'radio') return props.modelValue === props.value
  return !!props.modelValue
})

function toggle() {
  if (props.type === 'radio') {
    emit('update:modelValue', props.value as string | number)
  } else {
    emit('update:modelValue', !props.modelValue)
  }
}
</script>

<script lang="ts">
import { computed } from 'vue'
export default { name: 'CChoiceChip' }
</script>

<template>
  <button
    type="button"
    class="c-choice-chip"
    :class="{ 'is-selected': isSelected, 'is-solid': solid && isSelected, 'is-cyan': cyan && isSelected }"
    @click="toggle"
  >
    <slot>
      <span v-if="type === 'checkbox' && isSelected" class="c-choice-chip__check">
        <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round">
          <polyline points="20 6 9 17 4 12" />
        </svg>
      </span>
      {{ label }}
    </slot>
  </button>
</template>

<style scoped>
.c-choice-chip {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
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
  white-space: nowrap;
}
.c-choice-chip:hover:not(.is-selected) {
  border-color: var(--c-brand-border);
}
.c-choice-chip.is-selected {
  background: var(--c-brand-soft);
  border-color: var(--c-brand);
  color: var(--c-brand);
}
.c-choice-chip.is-selected.is-solid {
  background: var(--c-brand);
  border-color: var(--c-brand);
  color: var(--c-surface);
}
.c-choice-chip.is-selected.is-cyan {
  background: var(--c-teal);
  border-color: var(--c-teal);
  color: var(--c-surface);
}
.c-choice-chip__check {
  display: flex;
  align-items: center;
}
</style>
