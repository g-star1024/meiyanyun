<script setup lang="ts">
// C-SelectableRow 单选/多选行
// Ardot 真值：M4-02 客户选项（radio + tag）、服务项目（checkbox + 价格）
// type='radio' | 'checkbox'
const props = withDefaults(
  defineProps<{
    type?: 'radio' | 'checkbox'
    modelValue?: boolean
  }>(),
  { type: 'radio', modelValue: false },
)

const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void }>()

function toggle() {
  emit('update:modelValue', !props.modelValue)
}
</script>

<template>
  <button
    type="button"
    class="row"
    :class="{ 'is-active': modelValue }"
    @click="toggle"
  >
    <span class="row__control">
      <span v-if="type === 'radio'" class="radio" :class="{ 'is-checked': modelValue }">
        <span v-if="modelValue" class="radio__dot" />
      </span>
      <span v-else class="checkbox" :class="{ 'is-checked': modelValue }">
        <svg v-if="modelValue" class="checkbox__check" viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round">
          <polyline points="20 6 9 17 4 12" />
        </svg>
      </span>
    </span>

    <span class="row__label">
      <slot />
    </span>

    <span class="row__trailing">
      <slot name="trailing" />
    </span>
  </button>
</template>

<style scoped>
.row {
  display: flex;
  align-items: center;
  width: 100%;
  min-height: 52px;
  padding: 12px;
  gap: 12px;
  border: 1px solid var(--c-border-light);
  border-radius: var(--r-md);
  background: var(--c-surface);
  color: var(--c-text);
  font-size: var(--t-base);
  text-align: left;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}
.row:hover:not(.is-active) {
  border-color: var(--c-brand-border);
}
.row.is-active {
  border-color: var(--c-brand);
}
.row__control {
  display: flex;
  align-items: center;
  flex-shrink: 0;
}
.row__label {
  flex: 1;
  min-width: 0;
  font-weight: 700;
  font-size: var(--t-base);
  line-height: var(--lh-base);
}
.row__trailing {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
  margin-left: auto;
}

/* radio */
.radio {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  border: 2px solid #CCCCD9;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: border-color 0.15s, background 0.15s;
}
.radio.is-checked {
  background: var(--c-brand);
  border-color: var(--c-brand);
}
.radio__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #fff;
}

/* checkbox */
.checkbox {
  width: 18px;
  height: 18px;
  border-radius: 4px;
  border: 2px solid #CCCCD9;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: border-color 0.15s, background 0.15s;
}
.checkbox.is-checked {
  background: var(--c-brand);
  border-color: var(--c-brand);
  color: #fff;
}
.checkbox__check {
  display: block;
}
</style>
