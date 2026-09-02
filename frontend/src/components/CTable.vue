<script setup lang="ts">
// C-TBL 表格组件（对齐 GLOBAL-04 §五）
// 用法：<CTable :columns="[{key,label,width?}]" :rows="data" row-key="id">
//       列内容可用具名插槽 #col-{key} 自定义
withDefaults(
  defineProps<{
    columns: { key: string; label: string; width?: string | number; align?: 'left' | 'center' | 'right' }[]
    rows: Record<string, any>[]
    rowKey?: string
    stripe?: boolean
    emptyText?: string
  }>(),
  { rowKey: 'id', stripe: false, emptyText: '暂无数据' },
)
</script>

<template>
  <div class="ctable-wrap">
    <table class="ctable">
      <thead>
        <tr>
          <th
            v-for="col in columns"
            :key="col.key"
            :style="{ width: col.width, textAlign: col.align || 'left' }"
          >
            {{ col.label }}
          </th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(row, i) in rows" :key="row[rowKey] ?? i" :class="{ 'is-stripe': stripe && i % 2 }">
          <td
            v-for="col in columns"
            :key="col.key"
            :style="{ textAlign: col.align || 'left' }"
          >
            <slot :name="`col-${col.key}`" :row="row" :value="row[col.key]">
              {{ row[col.key] }}
            </slot>
          </td>
        </tr>
        <tr v-if="!rows.length">
          <td :colspan="columns.length" class="ctable__empty">
            <span class="ctable__empty-icon">
              <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
                <rect x="3" y="3" width="18" height="18" rx="2" />
                <path d="M3 9h18M9 21V9" />
              </svg>
            </span>
            <span class="ctable__empty-text">{{ emptyText }}</span>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
/* Ardot 真值：C-TBL-Table batch_read */
.ctable-wrap {
  width: 100%;
  overflow-x: auto;
}
.ctable {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--t-base);
  line-height: var(--lh-base);
}
.ctable thead th {
  padding: 12px 24px;
  background: var(--c-bg-page);
  color: var(--c-text);
  font-weight: 600;
  font-size: var(--t-sm);
  line-height: var(--lh-sm);
  text-align: left;
  border-bottom: 1px solid var(--c-border);
  white-space: nowrap;
}
.ctable tbody td {
  padding: 14px 24px;
  color: var(--c-text-2);
  font-size: var(--t-sm);
  line-height: var(--lh-sm);
  border-bottom: 1px solid var(--c-border);
  vertical-align: middle;
}
.ctable tbody tr:last-child td {
  border-bottom: none;
}
.ctable tbody tr:hover {
  background: var(--c-brand-soft);
}
.ctable tbody tr.is-stripe {
  background: var(--c-surface);
}
.ctable__empty {
  text-align: center;
  color: var(--c-text-3);
  padding: var(--s-xxl) var(--s-md);
}
.ctable__empty-icon {
  display: flex;
  justify-content: center;
  margin-bottom: var(--s-sm);
  color: var(--c-text-4);
}
.ctable__empty-text {
  font-size: var(--t-sm);
}
</style>
