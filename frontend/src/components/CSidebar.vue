<script setup lang="ts">
// C-Sidebar 桌面侧栏（240 深色，品牌 + 门店选择 + 导航 + 底部业务域切换）
import { ref, nextTick } from 'vue'
import CIcon from './CIcon.vue'

interface NavItem { to: string; label: string; icon: string; badge?: string | number }
interface NavGroup { title?: string; items: NavItem[] }
interface DomainItem { key: string; label: string; icon: string }

defineProps<{
  groups: NavGroup[]
  domains?: DomainItem[]
  currentDomain?: string
  store?: string
  stores?: string[]
}>()

const emit = defineEmits<{
  (e: 'pick-store', s: string): void
  (e: 'pick-domain', key: string): void
}>()

const open = ref(false)
const domainsOpen = ref(false)
const current = ref('上海静安旗舰店')
const navEl = ref<HTMLElement | null>(null)
function pick(s: string) {
  current.value = s
  open.value = false
  emit('pick-store', s)
}
function pickDomain(key: string) {
  domainsOpen.value = false
  nextTick(() => navEl.value?.scrollTo({ top: 0, behavior: 'smooth' }))
  emit('pick-domain', key)
}
</script>

<template>
  <aside class="sidebar">
    <div class="sidebar__brand">
      <span class="sidebar__logo">
        <CIcon name="store" :size="20" />
      </span>
      <div class="sidebar__name">
        <div class="sidebar__title">美研云 · 门店中台</div>
      </div>
    </div>

    <!-- 门店选择器 -->
    <div class="sidebar__store-wrap">
      <button class="store-pick" @click="open = !open">
        <span>{{ store || current }}</span>
        <CIcon name="chevron-down" :size="16" :class="{ 'is-open': open }" />
      </button>
      <div v-if="open" class="store-pop">
        <button
          v-for="s in (stores || ['上海静安旗舰店', '上海徐汇店', '北京朝阳店', '深圳南山店'])"
          :key="s"
          class="store-pop__item"
          :class="{ 'is-active': s === (store || current) }"
          @click="pick(s)"
        >{{ s }}</button>
      </div>
    </div>

    <nav ref="navEl" class="sidebar__nav">
      <template v-for="(g, gi) in groups" :key="gi">
        <div v-if="g.title" class="snav-group__title">{{ g.title }}</div>
        <RouterLink
          v-for="item in g.items"
          :key="item.to"
          :to="item.to"
          class="snav-item"
          active-class="is-active"
        >
          <CIcon :name="(item.icon as any)" :size="19" />
          <span class="snav-item__label">{{ item.label }}</span>
          <span v-if="item.badge" class="snav-item__badge">{{ item.badge }}</span>
        </RouterLink>
      </template>
    </nav>

    <!-- 底部业务域切换（可折叠） -->
    <div v-if="domains && domains.length" class="sidebar__domains" :class="{ 'is-collapsed': !domainsOpen }">
      <button class="domains-toggle" @click="domainsOpen = !domainsOpen">
        <span class="snav-group__title domains-toggle__label">业务中心</span>
        <CIcon name="chevron-down" :size="15" class="domains-toggle__chevron" :class="{ 'is-open': domainsOpen }" />
      </button>
      <div v-show="domainsOpen" class="domain-list">
        <button
          v-for="d in domains"
          :key="d.key"
          class="domain-item"
          :class="{ 'is-active': d.key === currentDomain }"
          @click="pickDomain(d.key)"
        >
          <CIcon :name="(d.icon as any)" :size="17" />
          <span class="domain-item__label">{{ d.label }}</span>
        </button>
      </div>
    </div>
  </aside>
</template>

<style scoped>
/* Ardot 真值：C-Sidebar 3:18 batch_read */
.sidebar {
  width: var(--sidebar-w);
  min-width: var(--sidebar-w);
  max-width: var(--sidebar-w);
  flex: 0 0 var(--sidebar-w);
  flex-shrink: 0;
  background: var(--c-sidebar);
  color: #c7c8d6;
  display: flex;
  flex-direction: column;
  height: 100vh;
  position: sticky;
  top: 0;
  box-sizing: border-box;
}
.sidebar__brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 22px 18px;
  flex-shrink: 0;
}
.sidebar__logo {
  width: 28px;
  height: 28px;
  border-radius: 9px;
  background: var(--c-brand);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.sidebar__title {
  color: #fff;
  font-size: var(--t-md);
  font-weight: 700;
  line-height: var(--lh-md);
  letter-spacing: 0.2px;
  white-space: nowrap;
}

/* 门店选择器 */
.sidebar__store-wrap {
  position: relative;
  padding: 0 var(--s-md) var(--s-md);
  flex-shrink: 0;
}
.store-pick {
  width: 100%;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 var(--s-md);
  border: none;
  border-radius: var(--r-md);
  background: rgba(255, 255, 255, 0.08);
  color: #fff;
  font-size: var(--t-sm);
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s;
  box-sizing: border-box;
}
.store-pick:hover {
  background: rgba(255, 255, 255, 0.14);
}
.store-pop {
  position: absolute;
  left: var(--s-md);
  right: var(--s-md);
  top: calc(100% - 6px);
  background: #1f2040;
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-pop);
  padding: var(--s-xxs);
  z-index: 50;
}
.store-pop__item {
  display: block;
  width: 100%;
  text-align: left;
  padding: var(--s-xs) var(--s-sm);
  border: none;
  background: transparent;
  border-radius: var(--r-sm);
  font-size: var(--t-sm);
  color: #d7d8e3;
  cursor: pointer;
}
.store-pop__item:hover {
  background: rgba(255, 255, 255, 0.08);
  color: #fff;
}
.store-pop__item.is-active {
  color: var(--c-brand);
  font-weight: 600;
}
:deep(.cicon) {
  transition: transform 0.15s;
}
:deep(.cicon.is-open) {
  transform: rotate(180deg);
}

.sidebar__nav {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.snav-group__title {
  padding: 8px 12px 4px;
  font-size: 11px;
  font-weight: 600;
  color: #6E6F80;
  letter-spacing: 0.5px;
}
.snav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 12px;
  height: 40px;
  border-radius: var(--r-lg);
  color: #B6B7C6;
  font-size: 14px;
  line-height: 20px;
  transition: background 0.15s, color 0.15s;
  flex-shrink: 0;
}
.snav-item:hover {
  background: rgba(255, 255, 255, 0.06);
  color: #fff;
}
.snav-item.is-active {
  background: rgba(255, 107, 158, 0.12);
  color: var(--c-brand);
  font-weight: 600;
}
.snav-item__label {
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.snav-item__badge {
  min-width: 18px;
  height: 18px;
  padding: 0 6px;
  border-radius: var(--r-pill);
  background: var(--c-brand);
  color: #fff;
  font-size: var(--t-xs);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

/* 底部业务域切换（可折叠）- 升级版 */
.sidebar__domains {
  flex-shrink: 0;
  border-top: 2px solid rgba(255, 107, 158, 0.3);
  padding: 8px 12px 12px;
  background: linear-gradient(180deg, rgba(255, 107, 158, 0.05) 0%, rgba(255, 107, 158, 0.02) 100%);
  margin-top: 8px;
}
.domains-toggle {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 12px;
  height: 36px;
  border: none;
  background: rgba(255, 107, 158, 0.1);
  cursor: pointer;
  border-radius: var(--r-md);
  transition: background 0.2s;
}
.domains-toggle:hover {
  background: rgba(255, 107, 158, 0.15);
}
.domains-toggle__label {
  padding: 0;
  color: var(--c-brand);
  font-weight: 600;
  font-size: 13px;
  letter-spacing: 0.3px;
}
.domains-toggle__chevron {
  color: var(--c-brand);
  transition: transform 0.2s;
}
.domains-toggle__chevron.is-open {
  transform: rotate(180deg);
}
.domain-list {
  display: flex;
  flex-direction: column;
  gap: 3px;
  margin-top: 6px;
  padding: 6px;
  background: rgba(0, 0, 0, 0.15);
  border-radius: var(--r-lg);
}
.domain-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  height: 36px;
  padding: 0 12px;
  border: none;
  border-radius: var(--r-md);
  background: transparent;
  color: #a0a1b5;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  text-align: left;
  transition: all 0.2s;
  box-sizing: border-box;
}
.domain-item:hover {
  background: rgba(255, 255, 255, 0.08);
  color: #fff;
  transform: translateX(2px);
}
.domain-item.is-active {
  background: rgba(255, 107, 158, 0.18);
  color: var(--c-brand);
  font-weight: 600;
  box-shadow: inset 0 0 0 1px rgba(255, 107, 158, 0.2);
}
.domain-item__label {
  white-space: nowrap;
}
</style>
