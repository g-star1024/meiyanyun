<script setup lang="ts">
/* ============================================================
 * C-Select 下拉选择器（仪表盘筛选器）
 * v-model 绑定当前 value；自定义展开面板，点击外部关闭
 * 铁律：禁止裸值，全部引用 tokens.css 变量
 * ============================================================ */
import { ref, onMounted, onUnmounted, computed } from 'vue'
import CIcon from './CIcon.vue'

const props = withDefaults(
  defineProps<{
    modelValue: string
    options: { label: string; value: string }[]
    placeholder?: string
    width?: string
  }>(),
  { placeholder: '请选择', width: '160px' },
)

const emit = defineEmits<{ (e: 'update:modelValue', v: string): void }>()

const open = ref(false)
const root = ref<HTMLElement | null>(null)

const currentLabel = computed(
  () => props.options.find((o) => o.value === props.modelValue)?.label ?? props.placeholder,
)

function toggle() {
  open.value = !open.value
}
function pick(v: string) {
  emit('update:modelValue', v)
  open.value = false
}
function onDocClick(e: MouseEvent) {
  if (root.value && !root.value.contains(e.target as Node)) open.value = false
}
onMounted(() => document.addEventListener('click', onDocClick))
onUnmounted(() => document.removeEventListener('click', onDocClick))
</script>

<template>
  <div ref="root" class="csel" :style="{ width }">
    <button class="csel__trigger" :class="{ 'is-open': open }" @click="toggle">
      <span class="csel__label" :class="{ 'is-placeholder': !options.some((o) => o.value === modelValue) }">
        {{ currentLabel }}
      </span>
      <CIcon name="chevron-down" :size="16" class="csel__caret" />
    </button>
    <Transition name="csel-pop">
      <ul v-if="open" class="csel__panel">
        <li
          v-for="opt in options"
          :key="opt.value"
          class="csel__opt"
          :class="{ 'is-active': opt.value === modelValue }"
          @click="pick(opt.value)"
        >
          {{ opt.label }}
          <CIcon v-if="opt.value === modelValue" name="check" :size="14" />
        </li>
        <li v-if="!options.length" class="csel__empty">无匹配选项</li>
      </ul>
    </Transition>
  </div>
</template>

<style scoped>
.csel {
  position: relative;
  display: inline-block;
}
.csel__trigger {
  width: 100%;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-xs);
  padding: 0 var(--s-sm);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--r-sm);
  font-family: inherit;
  font-size: var(--t-sm);
  color: var(--c-text);
  cursor: pointer;
  transition: border-color 0.15s;
}
.csel__trigger:hover,
.csel__trigger.is-open {
  border-color: var(--c-brand-border);
}
.csel__label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.csel__label.is-placeholder {
  color: var(--c-text-3);
}
.csel__caret {
  color: var(--c-text-3);
  flex-shrink: 0;
  transition: transform 0.15s;
}
.csel__trigger.is-open .csel__caret {
  transform: rotate(180deg);
}
.csel__panel {
  position: absolute;
  top: calc(100% + 4px);
  left: 0;
  right: 0;
  margin: 0;
  padding: var(--s-xxs);
  list-style: none;
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: var(--r-md);
  box-shadow: var(--shadow-pop);
  z-index: 60;
  max-height: 280px;
  overflow-y: auto;
}
.csel__opt {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--s-xs);
  padding: var(--s-xs) var(--s-sm);
  border-radius: var(--r-sm);
  font-size: var(--t-sm);
  color: var(--c-text-2);
  cursor: pointer;
}
.csel__opt:hover {
  background: var(--c-brand-soft);
  color: var(--c-text);
}
.csel__opt.is-active {
  color: var(--c-brand);
  font-weight: 600;
}
.csel__empty {
  padding: var(--s-sm) var(--s-sm);
  font-size: var(--t-sm);
  color: var(--c-text-3);
  text-align: center;
  cursor: default;
}
.csel-pop-enter-active,
.csel-pop-leave-active {
  transition: opacity 0.15s, transform 0.15s;
}
.csel-pop-enter-from,
.csel-pop-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}
</style>
