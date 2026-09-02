<script setup lang="ts">
// C-FLD-Textarea 多行文本框（对齐 CInput 风格，Ardot 8:32 备注框）
withDefaults(
  defineProps<{
    modelValue?: string
    label?: string
    placeholder?: string
    disabled?: boolean
    error?: boolean
    rows?: number
  }>(),
  { modelValue: '', label: '', placeholder: '', disabled: false, error: false, rows: 3 },
)

const emit = defineEmits<{ (e: 'update:modelValue', v: string): void }>()

function onInput(ev: Event) {
  emit('update:modelValue', (ev.target as HTMLTextAreaElement).value)
}
</script>

<template>
  <div class="ctextarea" :class="{ 'is-error': error, 'is-disabled': disabled }">
    <label v-if="label" class="ctextarea__label">{{ label }}</label>
    <textarea
      class="ctextarea__field"
      :rows="rows"
      :value="modelValue"
      :placeholder="placeholder"
      :disabled="disabled"
      @input="onInput"
    />
  </div>
</template>

<style scoped>
/* Ardot 真值：备注输入框 bg=#F5F6FA, r=8, padding=12, border=transparent */
.ctextarea {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.ctextarea__label {
  font-size: 13px;
  font-weight: 400;
  color: var(--c-text);
  line-height: 18px;
}
.ctextarea__field {
  width: 100%;
  padding: 12px;
  border: 1px solid transparent;
  border-radius: var(--r-md);
  background: #f5f6fa;
  font-size: 13px;
  font-weight: 400;
  color: var(--c-text);
  line-height: 20px;
  resize: vertical;
  outline: none;
  transition: border-color 0.15s, box-shadow 0.15s;
  font-family: inherit;
}
.ctextarea__field::placeholder {
  color: var(--c-text-4);
}
.ctextarea__field:focus {
  border-color: var(--c-brand);
  box-shadow: 0 0 0 2px rgba(255, 107, 158, 0.12);
}
.ctextarea.is-error .ctextarea__field {
  border-color: var(--c-danger-fg);
}
.ctextarea.is-error .ctextarea__field:focus {
  box-shadow: 0 0 0 2px rgba(255, 77, 79, 0.12);
}
.ctextarea.is-disabled .ctextarea__field {
  background: var(--c-disabled-bg);
  color: var(--c-disabled-fg);
  cursor: not-allowed;
  resize: none;
}
</style>
