<script setup lang="ts">
// C-DAT-StatusPill 状态胶囊（对齐 GLOBAL-04 §三 / DESIGN-TOKEN §1.3）
// 8 变体：default primary success warning danger info disabled draft
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    status?:
      | 'default' | 'primary' | 'success' | 'warning'
      | 'danger' | 'info' | 'disabled' | 'draft'
    dot?: boolean
  }>(),
  { status: 'default', dot: false },
)

const cls = computed(() => [`pill`, `pill--${props.status}`])
</script>

<template>
  <span :class="cls">
    <span v-if="dot" class="pill__dot" />
    <slot />
  </span>
</template>

<style scoped>
/* Ardot 真值：C-DAT-StatusPill-* batch_read
   cornerRadius: 10
   padding: 3px 10px
   fontSize: 12px
   fontWeight: Regular (400) */
.pill {
  display: inline-flex;
  align-items: center;
  gap: var(--s-xxs);
  padding: 3px 10px;
  border-radius: var(--r-pill);
  font-size: var(--t-xs);
  font-weight: 400;
  line-height: 18px;
  white-space: nowrap;
}
.pill__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}
.pill--default  { color: var(--c-default-fg);  background: var(--c-default-bg); }
.pill--primary  { color: var(--c-brand);       background: var(--c-brand-soft); }
.pill--success  { color: var(--c-success-fg);  background: var(--c-success-bg); }
.pill--warning  { color: var(--c-warning-fg);  background: var(--c-warning-bg); }
.pill--danger   { color: var(--c-danger-fg);   background: var(--c-danger-bg); }
.pill--info     { color: var(--c-info-fg);     background: var(--c-info-bg); }
.pill--disabled { color: var(--c-disabled-fg); background: var(--c-disabled-bg); }
.pill--draft    { color: var(--c-draft-fg);    background: var(--c-draft-bg); }
</style>
