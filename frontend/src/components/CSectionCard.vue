<script setup lang="ts">
/* ============================================================
 * C-Section-Card 段标题卡（仪表盘通用容器）
 * 顶部：标题 + 可选副标题 + 右侧操作区（具名插槽 header-right）
 * 主体：默认插槽；noPadding 时去掉内边距（用于嵌表格）
 * 铁律：禁止裸值，全部引用 tokens.css 变量
 * ============================================================ */
withDefaults(
  defineProps<{
    title?: string
    subtitle?: string
    noPadding?: boolean
    bodyClass?: string
  }>(),
  { noPadding: false, bodyClass: '' },
)
</script>

<template>
  <section class="scard">
    <header v-if="title || $slots['header-right']" class="scard__head">
      <div class="scard__titles">
        <h3 v-if="title" class="scard__title">{{ title }}</h3>
        <span v-if="subtitle" class="scard__subtitle">{{ subtitle }}</span>
      </div>
      <div class="scard__right">
        <slot name="header-right" />
      </div>
    </header>
    <div class="scard__body" :class="[{ 'is-nopad': noPadding }, bodyClass]">
      <slot />
    </div>
  </section>
</template>

<style scoped>
.scard {
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: var(--r-xl);
  box-shadow: var(--shadow-card);
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.scard__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-md);
  padding: var(--s-md) var(--s-md) 0;
  flex-shrink: 0;
}
.scard__titles {
  display: flex;
  align-items: baseline;
  gap: var(--s-sm);
  min-width: 0;
}
.scard__title {
  font-size: var(--t-md);
  font-weight: 700;
  color: var(--c-text);
  line-height: var(--lh-md);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.scard__subtitle {
  font-size: var(--t-xs);
  color: var(--c-text-3);
  white-space: nowrap;
}
.scard__right {
  display: flex;
  align-items: center;
  gap: var(--s-xs);
  flex-shrink: 0;
}
.scard__body {
  padding: var(--s-md);
  flex: 1;
  min-width: 0;
  min-height: 0;
}
.scard__body.is-nopad {
  padding: 0;
}
</style>
