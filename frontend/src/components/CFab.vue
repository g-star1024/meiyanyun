<script setup lang="ts">
/* ============================================================
 * C-Fab 悬浮操作钮（Floating Action Button）
 * 钉在滚动列表容器右下角的圆形主按钮。
 * - 定位：position: sticky；须作为滚动容器（overflow-y:auto）的
 *   最后一个子元素，滚动全程钉在可视区右下角、距边框恒定 --s-md，
 *   滚到底回落到流末尾同样距底 --s-md，不会贴卡片边框。
 * - 单操作：actions.length === 1 时点击直接执行 onClick（弹窗/抽屉），
 *   不再展开二级菜单；主按钮直接显示该操作图标（新建默认 plus）。
 * - 多操作：点击 + 钮向上展开带文字标签的菜单。
 * 用法：<CFab :actions="[{ icon:'plus', label:'新建合同', onClick: openForm }]" />
 * ============================================================ */
import { ref, computed, onMounted, onUnmounted } from 'vue'
import CIcon from './CIcon.vue'

interface FabAction {
  icon?: string
  label: string
  onClick: () => void
  disabled?: boolean
}
const props = defineProps<{ actions: FabAction[] }>()

const open = ref(false)
const root = ref<HTMLElement | null>(null)
const single = computed(() => props.actions.length === 1)
const primaryIcon = computed(() => (single.value ? props.actions[0].icon || 'plus' : 'plus'))

function toggle() { open.value = !open.value }
function run(a: FabAction) {
  if (a.disabled) return
  open.value = false
  a.onClick()
}
function onPrimary() {
  if (single.value) {
    run(props.actions[0])
  } else {
    toggle()
  }
}
function onDocClick(e: MouseEvent) {
  if (root.value && !root.value.contains(e.target as Node)) open.value = false
}
onMounted(() => document.addEventListener('click', onDocClick))
onUnmounted(() => document.removeEventListener('click', onDocClick))
</script>

<template>
  <div ref="root" class="cfab" :class="{ 'cfab--single': single }">
    <Transition name="cfab-pop">
      <ul v-if="open && !single" class="cfab__menu">
        <li v-for="(a, i) in actions" :key="i">
          <button class="cfab__item" :disabled="a.disabled" @click="run(a)">
            <span class="cfab__item-label">{{ a.label }}</span>
            <span class="cfab__item-icon"><CIcon :name="((a.icon || 'plus') as any)" :size="16" /></span>
          </button>
        </li>
      </ul>
    </Transition>
    <button
      class="cfab__btn"
      :class="{ 'is-open': open && !single }"
      :disabled="single ? actions[0].disabled : false"
      :aria-expanded="open"
      :aria-label="single ? actions[0].label : '更多操作'"
      @click="onPrimary"
    >
      <CIcon :name="((primaryIcon as any))" :size="22" />
    </button>
  </div>
</template>

<style scoped>
/* sticky：作为滚动容器末尾子元素，滚动全程钉在右下角 */
.cfab {
  position: sticky;
  bottom: var(--s-md);
  z-index: 30;
  width: fit-content;
  margin-left: auto;
  margin-right: var(--s-md);
  margin-top: auto; /* 短内容时沉到滚动列底部 */
  padding-top: var(--s-md);
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: var(--s-xs);
  pointer-events: none; /* 容器不挡列表点击，仅按钮可点 */
}
.cfab__btn,
.cfab__menu,
.cfab__item { pointer-events: auto; }

.cfab__btn {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  border: none;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--c-brand);
  color: #fff;
  cursor: pointer;
  box-shadow: var(--shadow-pop, 0 8px 24px rgba(0, 0, 0, 0.18));
  transition: transform 0.2s, background 0.15s;
}
.cfab__btn:hover:not(:disabled) { background: var(--c-brand-press, var(--c-brand)); }
.cfab__btn:disabled { opacity: 0.5; cursor: not-allowed; }
.cfab__btn.is-open { transform: rotate(45deg); }

.cfab__menu {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: var(--s-xs);
}
.cfab__item {
  display: inline-flex;
  align-items: center;
  gap: var(--s-sm);
  border: 1px solid var(--c-border);
  background: var(--c-surface);
  border-radius: var(--r-pill);
  padding: 0 var(--s-xs) 0 var(--s-md);
  height: 38px;
  cursor: pointer;
  box-shadow: var(--shadow-card, 0 2px 8px rgba(0, 0, 0, 0.08));
  font-family: inherit;
  white-space: nowrap;
  transition: background 0.15s;
}
.cfab__item:hover:not(:disabled) { background: var(--c-brand-soft); }
.cfab__item:disabled { opacity: 0.5; cursor: not-allowed; }
.cfab__item-label { font-size: var(--t-sm); color: var(--c-text); font-weight: 600; }
.cfab__item-icon {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: var(--c-brand);
  color: #fff;
  flex-shrink: 0;
}

.cfab-pop-enter-active,
.cfab-pop-leave-active { transition: opacity 0.18s, transform 0.18s; }
.cfab-pop-enter-from,
.cfab-pop-leave-to { opacity: 0; transform: translateY(8px) scale(0.95); }
</style>
