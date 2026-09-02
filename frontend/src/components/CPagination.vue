<script setup lang="ts">
// C-PAG-Pagination 分页器（对齐 GLOBAL-04 §6.3）
// 当前页粉底 32×32 / 非当前白底灰边
const props = withDefaults(
  defineProps<{
    page: number
    pageSize: number
    total: number
  }>(),
  { page: 1, pageSize: 10, total: 0 },
)
const emit = defineEmits<{ (e: 'update:page', v: number): void }>()

const totalPages = () => Math.max(1, Math.ceil(props.total / props.pageSize))
function go(n: number) {
  const tp = totalPages()
  if (n < 1 || n > tp || n === props.page) return
  emit('update:page', n)
}
</script>

<template>
  <div class="cpag">
    <span class="cpag__total">共 {{ total }} 条</span>
    <div class="cpag__nums">
      <button class="cpag__btn" :disabled="page <= 1" @click="go(page - 1)">‹</button>
      <button
        v-for="n in totalPages()"
        :key="n"
        class="cpag__num"
        :class="{ 'is-active': n === page }"
        @click="go(n)"
      >{{ n }}</button>
      <button class="cpag__btn" :disabled="page >= totalPages()" @click="go(page + 1)">›</button>
    </div>
  </div>
</template>

<style scoped>
/* Ardot 真值：C-PAG-Pagination batch_read */
.cpag {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--s-md);
  padding: var(--s-md) 0;
}
.cpag__total {
  font-size: var(--t-sm);
  color: var(--c-text-3);
}
.cpag__nums {
  display: flex;
  align-items: center;
  gap: var(--s-xxs);
}
.cpag__btn,
.cpag__num {
  min-width: 32px;
  height: 32px;
  padding: 0 var(--s-xs);
  border: 1px solid var(--c-border);
  border-radius: var(--r-sm);
  background: var(--c-surface);
  color: var(--c-text-3);
  font-size: var(--t-sm);
  cursor: pointer;
}
.cpag__btn:hover:not(:disabled),
.cpag__num:hover {
  border-color: var(--c-brand-border);
  color: var(--c-brand);
}
.cpag__num.is-active {
  background: var(--c-brand);
  border-color: var(--c-brand);
  color: var(--c-surface);
  font-weight: 600;
}
.cpag__btn:disabled {
  color: var(--c-text-4);
  cursor: not-allowed;
}
</style>
