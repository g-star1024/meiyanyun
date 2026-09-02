<script setup lang="ts">
// C-BTN 按钮（对齐 GLOBAL-04 §二）
// Primary 实底胶囊 40h / Secondary 白粉底边 / Danger 红 / Text 纯文字
// 禁 Medium（用 600）；行高显式
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    variant?: 'primary' | 'secondary' | 'danger' | 'ghost' | 'text'
    size?: 'sm' | 'md' | 'lg'
    disabled?: boolean
    block?: boolean
    type?: 'button' | 'submit'
  }>(),
  { variant: 'primary', size: 'md', disabled: false, block: false, type: 'button' },
)

const cls = computed(() => [
  'cbtn',
  `cbtn--${props.variant}`,
  `cbtn--${props.size}`,
  { 'cbtn--block': props.block, 'is-disabled': props.disabled },
])
</script>

<template>
  <button :type="type" :class="cls" :disabled="disabled">
    <slot />
  </button>
</template>

<style scoped>
.cbtn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--s-xs);
  height: var(--btn-h);
  padding: 0 var(--s-lg);
  border: 1px solid transparent;
  border-radius: var(--r-capsule);
  font-family: inherit;
  font-size: var(--t-base);
  font-weight: 600;
  line-height: var(--lh-base);
  white-space: nowrap;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s, color 0.15s, opacity 0.15s;
}
.cbtn--sm { height: 32px; padding: 0 var(--s-md); font-size: var(--t-sm); }
.cbtn--lg { height: 48px; padding: 0 var(--s-xl); font-size: var(--t-md); }
.cbtn--block { width: 100%; }

/* Primary：品牌粉实底胶囊 */
.cbtn--primary {
  background: var(--c-brand);
  color: #fff;
}
.cbtn--primary:hover:not(:disabled) { background: var(--c-brand-press); }

/* Secondary：白底粉边粉字 */
.cbtn--secondary {
  background: var(--c-surface);
  border-color: var(--c-brand-border);
  color: var(--c-brand);
}
.cbtn--secondary:hover:not(:disabled) {
  background: var(--c-brand-soft);
  border-color: var(--c-brand);
}

/* Danger：红语义 */
.cbtn--danger {
  background: var(--c-danger-fg);
  color: #fff;
}
.cbtn--danger:hover:not(:disabled) { opacity: 0.9; }

/* Ghost：灰底 */
.cbtn--ghost {
  background: var(--c-disabled-bg);
  color: var(--c-text);
}
.cbtn--ghost:hover:not(:disabled) { background: var(--c-border); }

/* Text：纯文字链接 */
.cbtn--text {
  height: auto;
  padding: var(--s-xs) var(--s-sm);
  background: transparent;
  color: var(--c-brand);
  border-radius: var(--r-sm);
}
.cbtn--text:hover:not(:disabled) { background: var(--c-brand-soft); }

.cbtn:disabled,
.cbtn.is-disabled {
  background: var(--c-brand-soft);
  border-color: transparent;
  color: var(--c-brand-border);
  cursor: not-allowed;
  opacity: 1;
}
.cbtn--secondary:disabled {
  background: var(--c-surface);
  border-color: var(--c-border);
  color: var(--c-text-4);
}
</style>
