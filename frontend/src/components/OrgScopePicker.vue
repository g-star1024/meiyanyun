<script setup lang="ts">
/* ============================================================
 * OrgScopePicker 集团→区域→门店 三级层级选择器（棒②卡1 新建）
 * 数据源：m1Context（复用 m1Org 组织树，选项只列启用节点）
 * 集团单根固定展示；区域/门店可回「全部」；区域切换自动清空门店
 * ============================================================ */
import { storeToRefs } from 'pinia'
import CIcon from './CIcon.vue'
import CSelect from './CSelect.vue'
import { useM1ContextStore } from '@/stores/m1Context'

const scope = useM1ContextStore()
const { regionCode, storeCode } = storeToRefs(scope)
</script>

<template>
  <div class="scope-picker">
    <CIcon name="org" :size="16" class="scope-picker__icon" />
    <span class="scope-picker__group">{{ scope.groupLabel }}</span>
    <CIcon name="chevron-right" :size="14" class="scope-picker__sep" />
    <CSelect
      v-model="regionCode"
      :options="scope.regionOptions"
      width="150px"
      placeholder="全部区域"
    />
    <CIcon name="chevron-right" :size="14" class="scope-picker__sep" />
    <CSelect
      v-model="storeCode"
      :options="scope.storeOptions"
      width="170px"
      placeholder="全部门店"
    />
  </div>
</template>

<style scoped>
/* 棒②卡3：卡片壳内置组件根（全铺 14 页一处挂接一致外观；M1Overview 旧 .ov__scope 外壳样式同步移除） */
.scope-picker { display: flex; align-items: center; gap: var(--s-sm); flex-wrap: nowrap; padding: var(--s-sm) var(--s-md); background: var(--c-surface); border: 1px solid var(--c-border-light); border-radius: var(--r-xl); }
.scope-picker__icon { color: var(--c-brand); flex: none; }
.scope-picker__group { font-size: var(--t-sm); font-weight: 700; color: var(--c-text); white-space: nowrap; }
.scope-picker__sep { color: var(--c-text-3); flex: none; }
</style>
