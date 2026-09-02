<script setup lang="ts">
// C-FLD-Input 输入框（对齐 Ardot 281:302）
// cornerRadius=6, padding=10, bg=#FFF, border=#D1D1D9
// label 13px #1A1A2E, placeholder 13px #BFBFBF
import { ref } from 'vue'
withDefaults(
  defineProps<{
    modelValue?: string
    label?: string
    placeholder?: string
    disabled?: boolean
    error?: boolean
    type?: 'text' | 'password' | 'number'
  }>(),
  { modelValue: '', label: '', placeholder: '', disabled: false, error: false, type: 'text' },
)

const emit = defineEmits<{ (e: 'update:modelValue', v: string): void }>()

const fieldEl = ref<HTMLInputElement | null>(null)
function focus() { fieldEl.value?.focus() }
defineExpose({ focus })

function onInput(ev: Event) {
  emit('update:modelValue', (ev.target as HTMLInputElement).value)
}
</script>

<template>
  <div class="cinput" :class="{ 'is-error': error, 'is-disabled': disabled }">
    <label v-if="label" class="cinput__label">{{ label }}</label>
    <input
      :type="type"
      class="cinput__field"
      :value="modelValue"
      :placeholder="placeholder"
      :disabled="disabled"
      @input="onInput"
    />
  </div>
</template>

<style scoped>
/* Ardot 真值：C-FLD-Input (281:302) */
.cinput {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.cinput__label {
  font-size: 13px;
  font-weight: 400;
  color: var(--c-text);
  line-height: 18px;
}
.cinput__field {
  width: 100%;
  padding: 10px;
  border: 1px solid #D1D1D9;
  border-radius: var(--r-sm);
  background: var(--c-surface);
  font-size: 13px;
  font-weight: 400;
  color: var(--c-text);
  line-height: 20px;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.cinput__field::placeholder {
  color: #BFBFBF;
}
.cinput__field:focus {
  outline: none;
  border-color: #4D5AD9;
  box-shadow: 0 0 0 2px rgba(77, 90, 217, 0.12);
}
.cinput.is-error .cinput__field {
  border-color: var(--c-danger-fg);
}
.cinput.is-error .cinput__field:focus {
  box-shadow: 0 0 0 2px rgba(255, 77, 79, 0.12);
}
.cinput.is-disabled .cinput__field {
  background: var(--c-disabled-bg);
  color: var(--c-disabled-fg);
  cursor: not-allowed;
}
</style>
