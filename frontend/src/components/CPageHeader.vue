<script setup lang="ts">
/* ============================================================
 * C-Page-Header 页面级顶栏
 * 与 CShellDesktop 全局顶栏同结构：左侧面包屑+标题，右侧操作区。
 * 用于隐藏全局顶栏的独立页面（如详情页），确保视觉规范统一。
 * ============================================================ */
import { useRouter } from 'vue-router'
import CIcon from './CIcon.vue'

withDefaults(
  defineProps<{
    breadcrumb: string
    title: string
    showBack?: boolean
    fullBleed?: boolean
    sticky?: boolean
  }>(),
  { showBack: false, fullBleed: false, sticky: true },
)

const router = useRouter()
</script>

<template>
  <header class="c-page-header" :class="{ 'is-full-bleed': fullBleed, 'is-static': !sticky }">
    <div class="c-page-header__left">
      <button v-if="showBack" type="button" class="c-page-header__back" @click="router.back()">
        <CIcon name="chevron-left" :size="18" />
      </button>
      <div class="c-page-header__title-group">
        <span class="c-page-header__breadcrumb">{{ breadcrumb }}</span>
        <span class="c-page-header__title">{{ title }}</span>
      </div>
    </div>
    <div class="c-page-header__right">
      <slot />
    </div>
  </header>
</template>

<style scoped>
.c-page-header {
  height: 60px;
  min-height: 60px;
  flex-shrink: 0;
  background: var(--c-surface);
  border-bottom: 1px solid var(--c-border-light);
  padding: 0 24px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  position: sticky;
  top: 0;
  z-index: 20;
}
.c-page-header.is-static {
  position: relative;
}
/* 与 CShellDesktop 全局顶栏位置对齐：抵消 shell__content 的 --s-lg padding */
.c-page-header.is-full-bleed {
  margin: calc(-1 * var(--s-lg)) calc(-1 * var(--s-lg)) 0 calc(-1 * var(--s-lg));
  width: calc(100% + 2 * var(--s-lg));
}
.c-page-header__left {
  display: flex;
  align-items: center;
  gap: 12px;
}
.c-page-header__back {
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--c-border-light);
  background: var(--c-surface);
  color: var(--c-text);
  cursor: pointer;
  border-radius: var(--r-md);
}
.c-page-header__back:hover {
  background: var(--c-brand-soft);
}
.c-page-header__title-group {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.c-page-header__breadcrumb {
  font-size: var(--t-xs);
  color: var(--c-text-3);
  line-height: 1;
}
.c-page-header__title {
  font-size: 20px;
  font-weight: 700;
  color: var(--c-text);
  line-height: 1.2;
}
.c-page-header__right {
  display: flex;
  align-items: center;
  gap: 12px;
}

/* Tablet */
@media (max-width: 1024px) {
  .c-page-header {
    height: 56px;
    min-height: 56px;
    padding: 0 16px;
  }
  .c-page-header__back {
    width: 32px;
    height: 32px;
  }
  .c-page-header__title {
    font-size: 18px;
  }
}
</style>
