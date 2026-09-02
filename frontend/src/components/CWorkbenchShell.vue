<script setup lang="ts">
/* ============================================================
 * CWorkbenchShell 岗位工作台统一骨架
 * 收敛咨询台(cw__)/医师台(dw__) 等 8 套重复外壳为一套 wbs__ 类名。
 * 结构：顶部 KPI 行（#kpis，按实际数量均分撑满）+ 工具行（#toolbar，搜索撑满左、按钮靠右）
 *   + 左列表卡（#list） + 右详情卡
 *   右详情：有选中时 head(#head) / body(默认插槽, 滚动) / foot(#foot)；
 *           无选中时渲染空态（#empty，可用 emptyIcon/Title/Desc 走默认）。
 * 左列宽度、详情体滚动高度均与历史工作台一致，≤1100px 降单列。
 * ============================================================ */
import CIcon from './CIcon.vue'

withDefaults(
  defineProps<{
    /** 右侧是否有选中对象；false 显示空态 */
    hasSelection: boolean
    emptyIcon?: string
    emptyTitle?: string
    emptyDesc?: string
    /** 左列宽度（默认 360px，与咨询/医师台一致） */
    listWidth?: string
  }>(),
  {
    emptyIcon: 'chat',
    emptyTitle: '从左侧选择一项开始处理',
    emptyDesc: '',
    listWidth: '360px',
  },
)
</script>

<script lang="ts">
export default { name: 'CWorkbenchShell' }
</script>

<template>
  <div class="wbs">
    <div v-if="$slots.kpis" class="wbs__kpis"><slot name="kpis" /></div>

    <!-- 工具行：搜索框靠左拉长撑满、操作按钮靠右；全宽独占一行，避免右侧拥挤/左侧留白 -->
    <div v-if="$slots.toolbar" class="wbs__toolbar"><slot name="toolbar" /></div>

    <div class="wbs__split" :style="{ gridTemplateColumns: `${listWidth} 1fr` }">
      <!-- 左列表卡 -->
      <section class="wbs__list"><slot name="list" /></section>

      <!-- 右详情卡 -->
      <section class="wbs__detail">
        <template v-if="hasSelection">
          <div class="wbs__head"><slot name="head" /></div>
          <div class="wbs__body"><slot /></div>
          <div v-if="$slots.foot" class="wbs__foot"><slot name="foot" /></div>
        </template>
        <div v-else class="wbs__empty">
          <slot name="empty">
            <CIcon :name="emptyIcon as any" :size="40" />
            <p>{{ emptyTitle }}</p>
            <span v-if="emptyDesc">{{ emptyDesc }}</span>
          </slot>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.wbs { display: flex; flex-direction: column; gap: var(--s-md); }
/* KPI 按实际数量同一行均分撑满（3 个则 3 等分，不留空列） */
.wbs__kpis { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: var(--s-md); }

/* 工具行：全宽；搜索框 flex:1 靠左撑满，按钮靠右 */
.wbs__toolbar { display: flex; align-items: center; gap: var(--s-sm); }
.wbs__toolbar :deep(.cinput) { flex: 1; min-width: 160px; }
.wbs__toolbar :deep(.wbs-toolbar__spacer) { flex: 1; }

.wbs__split {
  display: grid;
  gap: var(--s-md);
  align-items: start;
}
.wbs__list {
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--r-xl);
  box-shadow: var(--shadow-card);
  display: flex;
  flex-direction: column;
  max-height: calc(100vh - 190px);
  position: sticky;
  top: var(--s-md);
  overflow: hidden;
}
.wbs__detail {
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--r-xl);
  box-shadow: var(--shadow-card);
  display: flex;
  flex-direction: column;
  min-height: 480px;
  overflow: hidden;
}

.wbs__head { padding: var(--s-md); border-bottom: 1px solid var(--c-border-light); }
.wbs__body { padding: var(--s-md); overflow-y: auto; max-height: calc(100vh - 380px); }
.wbs__foot {
  display: flex;
  justify-content: flex-end;
  gap: var(--s-sm);
  padding: var(--s-md);
  border-top: 1px solid var(--c-border-light);
  background: var(--c-bg-page);
}

.wbs__empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--s-sm);
  color: var(--c-text-3);
  padding: var(--s-xl);
}
.wbs__empty p { font-size: var(--t-md); font-weight: 600; color: var(--c-text-2); margin: 0; }
.wbs__empty span { font-size: var(--t-sm); }

@media (max-width: 1100px) {
  .wbs__split { grid-template-columns: 1fr !important; }
  .wbs__list { position: static; max-height: none; }
  .wbs__kpis { grid-auto-flow: row; grid-template-columns: repeat(2, 1fr); grid-auto-columns: auto; }
  .wbs__toolbar { flex-wrap: wrap; }
  .wbs__toolbar :deep(.cinput) { flex: 1 1 100%; }
  .wbs__body { max-height: none; }
}
</style>
