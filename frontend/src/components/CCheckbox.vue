<script setup lang="ts">
/* ============================================================
 * C-Checkbox 复选框
 * 对齐 Ardot C-FLD-Checkbox：18px 方框，选中粉底白勾，未选中灰边
 * ============================================================ */
const props = withDefaults(
  defineProps<{
    modelValue?: boolean
    disabled?: boolean
  }>(),
  { modelValue: false, disabled: false },
)

const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void }>()

function toggle() {
  if (props.disabled) return
  emit('update:modelValue', !props.modelValue)
}
</script>

<template>
  <label class="ccheckbox" :class="{ 'is-disabled': disabled }">
    <input
      type="checkbox"
      class="ccheckbox__input"
      :checked="modelValue"
      :disabled="disabled"
      @change="toggle"
    />
    <span class="ccheckbox__box" :class="{ 'is-checked': modelValue }">
      <svg
        v-if="modelValue"
        class="ccheckbox__check"
        viewBox="0 0 24 24"
        width="12"
        height="12"
        fill="none"
        stroke="currentColor"
        stroke-width="3"
        stroke-linecap="round"
        stroke-linejoin="round"
      >
        <polyline points="20 6 9 17 4 12" />
      </svg>
    </span>
    <span v-if="$slots.default" class="ccheckbox__label">
      <slot />
    </span>
  </label>
</template>

<style scoped>
.ccheckbox {
  display: inline-flex;
  align-items: center;
  gap: var(--s-sm);
  cursor: pointer;
  user-select: none;
}
.ccheckbox.is-disabled {
  cursor: not-allowed;
  opacity: 0.6;
}
.ccheckbox__input {
  position: absolute;
  opacity: 0;
  width: 0;
  height: 0;
}
.ccheckbox__box {
  width: 18px;
  height: 18px;
  border-radius: 4px;
  border: 2px solid #CCCCD9;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: border-color 0.15s, background 0.15s, color 0.15s;
}
.ccheckbox__box.is-checked {
  background: var(--c-brand);
  border-color: var(--c-brand);
  color: #fff;
}
.ccheckbox__check {
  display: block;
}
.ccheckbox__label {
  font-size: var(--t-base);
  color: var(--c-text);
  line-height: var(--lh-base);
}
</style>
