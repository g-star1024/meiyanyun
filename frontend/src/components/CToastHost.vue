<script setup lang="ts">
/* ============================================================
 * CToastHost 全局轻提示宿主 — 在 App.vue 挂载一次，渲染 useToast 队列。
 * 右上角堆叠，按 tone 着色，自动消失，可点关闭。样式走 tokens。
 * ============================================================ */
import CIcon from './CIcon.vue'
import { useToast, type ToastTone } from '@/composables/useToast'

const { toasts, dismiss } = useToast()

const ICON: Record<ToastTone, string> = {
  success: 'check',
  error: 'alert',
  warning: 'alert',
  info: 'info',
}
</script>

<script lang="ts">
export default { name: 'CToastHost' }
</script>

<template>
  <div class="toast-host" aria-live="polite">
    <transition-group name="toast">
      <div
        v-for="t in toasts"
        :key="t.id"
        class="toast"
        :class="[`toast--${t.tone}`, { 'toast--leaving': t.leaving }]"
        @click="dismiss(t.id)"
      >
        <span class="toast__icon"><CIcon :name="(ICON[t.tone]) as any" :size="16" /></span>
        <span class="toast__msg">{{ t.message }}</span>
        <CIcon name="close" :size="13" class="toast__close" />
      </div>
    </transition-group>
  </div>
</template>

<style scoped>
.toast-host {
  position: fixed;
  top: var(--s-lg);
  right: var(--s-lg);
  z-index: 3000;
  display: flex;
  flex-direction: column;
  gap: var(--s-xs);
  pointer-events: none;
}
.toast {
  pointer-events: auto;
  display: flex;
  align-items: center;
  gap: var(--s-sm);
  min-width: 240px;
  max-width: 380px;
  padding: 10px var(--s-md);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-left-width: 3px;
  border-radius: var(--r-md);
  box-shadow: var(--shadow-card);
  cursor: pointer;
}
.toast__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: var(--r-pill);
  flex-shrink: 0;
}
.toast__msg {
  font-size: var(--t-sm);
  color: var(--c-text);
  line-height: 1.4;
  flex: 1;
}
.toast__close { color: var(--c-text-4); flex-shrink: 0; }

.toast--success { border-left-color: var(--c-success-fg); }
.toast--success .toast__icon { background: var(--c-success-bg); color: var(--c-success-fg); }
.toast--error { border-left-color: var(--c-danger-fg); }
.toast--error .toast__icon { background: var(--c-danger-bg); color: var(--c-danger-fg); }
.toast--warning { border-left-color: var(--c-warning-fg); }
.toast--warning .toast__icon { background: var(--c-warning-bg); color: var(--c-warning-fg); }
.toast--info { border-left-color: var(--c-brand); }
.toast--info .toast__icon { background: var(--c-brand-soft); color: var(--c-brand); }

.toast-enter-active,
.toast-leave-active { transition: all .25s ease; }
.toast-enter-from { opacity: 0; transform: translateX(24px); }
.toast-leave-to { opacity: 0; transform: translateX(24px); }
</style>
