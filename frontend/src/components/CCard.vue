<script setup lang="ts">
// CCard 卡片容器（对齐 DESIGN-TOKEN：r-lg 12 / surface 白 / shadow.card）
withDefaults(
  defineProps<{
    title?: string
    padding?: 'sm' | 'md' | 'lg' | 'none'
    headerBorder?: boolean
  }>(),
  { title: '', padding: 'md', headerBorder: true },
)
</script>

<template>
  <section class="card" :class="[`card--pad-${padding}`, { 'card--no-header-border': !headerBorder }]">
    <header v-if="title || $slots.header" class="card__header">
      <slot name="header">
        <h3 class="card__title">{{ title }}</h3>
      </slot>
    </header>
    <div class="card__body">
      <slot />
    </div>
    <footer v-if="$slots.footer" class="card__footer">
      <slot name="footer" />
    </footer>
  </section>
</template>

<style scoped>
.card {
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--r-xl);
  box-shadow: var(--shadow-card);
}
.card__header {
  padding: var(--s-md) var(--s-lg);
  border-bottom: 1px solid var(--c-border);
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.card__title {
  font-size: var(--t-md);
  line-height: var(--lh-md);
  font-weight: 700;
}
.card__body {
  /* padding 由修饰类控制 */
}
.card--pad-sm .card__body { padding: var(--s-sm); }
.card--pad-md .card__body { padding: var(--s-md) var(--s-lg); }
.card--pad-lg .card__body { padding: var(--s-lg); }
.card--pad-none .card__body { padding: 0; }
.card--no-header-border .card__header {
  border-bottom: none;
  padding-bottom: var(--s-sm);
}
.card__footer {
  padding: var(--s-sm) var(--s-lg);
  border-top: 1px solid var(--c-border);
  display: flex;
  justify-content: flex-end;
  gap: var(--s-sm);
}
</style>
