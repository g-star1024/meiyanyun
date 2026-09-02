<script setup lang="ts">
/* ============================================================
 * C-Drawer 右侧滑出抽屉
 * 用于数据密集型操作（如撞单消解详情+归属确认）
 * 使用 position:fixed 实现全屏覆盖，无需 Teleport
 * ============================================================ */
defineProps<{
  show: boolean
  title: string
  size?: 'sm' | 'md' | 'lg'
}>()

const emit = defineEmits<{
  (e: 'update:show', v: boolean): void
}>()

function close() {
  emit('update:show', false)
}

function onEsc(e: KeyboardEvent) {
  if (e.key === 'Escape') close()
}
</script>

<script lang="ts">
export default { name: 'CDrawer' }
</script>

<template>
  <div
    v-show="show"
    class="c-drawer__overlay"
    @click.self="close"
    @keydown.escape="onEsc"
  >
    <div
      class="c-drawer"
      :class="[`c-drawer--${size}`]"
    >
      <div class="c-drawer__header">
        <h3 class="c-drawer__title">{{ title }}</h3>
        <button class="c-drawer__close" @click="close" aria-label="关闭">
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
            <line x1="6" y1="6" x2="18" y2="18" />
            <line x1="18" y1="6" x2="6" y2="18" />
          </svg>
        </button>
      </div>
      <div class="c-drawer__body">
        <slot />
      </div>
      <div class="c-drawer__footer" v-if="$slots.footer">
        <slot name="footer" />
      </div>
    </div>
  </div>
</template>

<style>
/* Hide component root — content is rendered in the overlay div */
cdrawer {
  display: none !important;
}
.c-drawer__overlay {
  position: fixed;
  inset: 0;
  background: rgba(20, 21, 43, 0.4);
  z-index: 1000;
  display: flex;
  justify-content: flex-end;
  animation: c-drawer-fade 0.2s ease;
}
@keyframes c-drawer-fade {
  from { opacity: 0 }
  to { opacity: 1 }
}

.c-drawer {
  width: 480px;
  height: 100%;
  background: var(--c-surface);
  box-shadow: var(--shadow-pop);
  display: flex;
  flex-direction: column;
  animation: c-drawer-slide 0.25s ease;
}
@keyframes c-drawer-slide {
  from { transform: translateX(100%) }
  to { transform: translateX(0) }
}
.c-drawer--sm { width: 360px }
.c-drawer--md { width: 480px }
.c-drawer--lg { width: 640px }

.c-drawer__header {
  height: 56px;
  padding: 0 var(--s-lg);
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--c-border-light);
  flex-shrink: 0;
}
.c-drawer__title {
  font-size: var(--t-lg);
  font-weight: 700;
  color: var(--c-text);
}
.c-drawer__close {
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  color: var(--c-text-3);
  cursor: pointer;
  border-radius: var(--r-sm);
}
.c-drawer__close:hover {
  background: var(--c-bg-page);
}

.c-drawer__body {
  flex: 1;
  overflow-y: auto;
  padding: var(--s-lg);
}

.c-drawer__footer {
  padding: var(--s-md) var(--s-lg);
  border-top: 1px solid var(--c-border-light);
  display: flex;
  gap: var(--s-sm);
  justify-content: flex-end;
  flex-shrink: 0;
}

@media (max-width: 1024px) {
  .c-drawer,
  .c-drawer--sm,
  .c-drawer--md,
  .c-drawer--lg {
    width: 100%;
  }
}
</style>