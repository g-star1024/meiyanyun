<script setup lang="ts">
/* ============================================================
 * App 根组件 — B 端桌面壳 + C 端手机壳自动切换
 * /m 前缀路由使用 CMobileShell，其余走 CShellDesktop
 * Wave 8: 7 业务域 Tab + 顶栏通用入口
 * ============================================================ */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import CShellDesktop from '@/components/CShellDesktop.vue'
import CMobileShell from '@/components/CMobileShell.vue'
import CToastHost from '@/components/CToastHost.vue'
import { useAuthStore } from '@/stores/auth'
import { useStoreContext } from '@/stores/storeContext'
import {
  DOMAINS,
  NAV_GROUPS,
  TOPBAR_QUICK,
  PAGE_TITLES,
  buildNavForDomain,
  isDomainVisible,
  type DomainKey,
} from '@/config/nav'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const storeCtx = useStoreContext()

onMounted(() => {
  // 全局门店上下文：恢复缓存 + 拉真实门店列表（业务页按此隔离数据）
  storeCtx.init()
})
function onPickStore(name: string) {
  storeCtx.setStoreByName(name)
}
const isMobile = computed(() => route.path === '/m' || route.path.startsWith('/m/'))
// 公共页（登录页）：不套桌面外壳（侧栏/顶栏），整页裸布局渲染
const isAuthPage = computed(() => route.meta?.public === true)

// Quick 入口路径集合（不属于任何业务域，切换时保持上一个域的侧栏）
const QUICK_PATHS = new Set(TOPBAR_QUICK.map((q) => q.to))

// 业务域频道首页（聚合页）路径
const DOMAIN_HOME_PATH: Record<DomainKey, string> = {
  workbench: '/workbench',
  customer: '/customer',
  store: '/store',
  marketing: '/marketing',
  finance: '/finance',
  ai: '/ai',
  admin: '/admin',
}

// 当前域：默认工作台；路由变化时若 detect 有结果则更新，否则保持（quick 页）
const currentDomain = ref<DomainKey>('workbench')

// 根据当前路径推导所属域；G 通用/quick 入口归 workbench（避免侧栏显示上一个域的菜单）
function detectDomain(path: string): DomainKey | null {
  if (QUICK_PATHS.has(path)) return 'workbench'
  // 业务域频道首页（聚合页）精确命中
  if (path === '/ai') return 'ai'
  const homeMap: Record<string, DomainKey> = {
    '/workbench': 'workbench', '/customer': 'customer', '/store': 'store',
    '/marketing': 'marketing', '/finance': 'finance', '/admin': 'admin',
  }
  if (homeMap[path]) return homeMap[path]

  // 如果路径在当前域的导航中，保持当前域（解决跨域跳转问题）
  if (currentDomain.value) {
    const inCurrentDomain = NAV_GROUPS.some(g =>
      g.domain === currentDomain.value &&
      g.items.some(item => item.to === path || path.startsWith(item.to + '/'))
    )
    if (inCurrentDomain) {
      return currentDomain.value
    }
  }

  // 客户资产页（会员列表/合并去重/客户 360）归属客户运营域。
  // /customers 同时被工作台域「客户档案」快捷入口与客户域「会员列表」引用，
  // 需在通用菜单查找前优先判定，否则会因工作台组在前而误判为 workbench，
  // 导致从客户运营域点卡片跳回工作台域。
  if (path === '/customers' || path.startsWith('/customers/')) return 'customer'
  // 精确匹配或子路径匹配
  const item = NAV_GROUPS.flatMap((g) => g.items).find(
    (i) => path === i.to || path.startsWith(i.to + '/'),
  )
  if (item) {
    const g = NAV_GROUPS.find((g) => g.items.includes(item))
    if (g) return g.domain
  }
  // 前缀兜底
  if (path.startsWith('/ai')) return 'ai'
  if (path.startsWith('/m5') || path === '/m1-marketing') return 'marketing'
  if (path.startsWith('/m6') || path === '/refund' || path === '/card-cancel') return 'finance'
  if (
    path.startsWith('/admin') || path.startsWith('/data') ||
    path === '/workorders' || path === '/integrations' ||
    path.startsWith('/m1-')
  ) return 'admin'
  if (path.startsWith('/m2')) return 'store'
  if (
    path.startsWith('/customers') || path.startsWith('/m3') ||
    path === '/customer-graph' || path === '/followup' || path === '/complaint' ||
    path === '/card-course' || path === '/course-track' ||
    path === '/contract' || path === '/asset-transfer'
  ) return 'customer'
  if (
    path === '/' || path === '/appointment' || path === '/queue' ||
    path === '/reception' || path === '/guest-reg' || path === '/closed-loop' ||
    path === '/approval' || path === '/notifications' || path === '/m1' ||
    path === '/consultation' || path === '/prescription' || path === '/order' ||
    path === '/writeoff' || path === '/m2-writeoff-desk' || path === '/m2-checkin' ||
    path.startsWith('/emr') || path.startsWith('/recall') || path === '/handover'
  ) return 'workbench'
  return null
}

// 初始化当前域
currentDomain.value = detectDomain(route.path) || 'workbench'

// 路由变化时更新域
watch(
  () => route.path,
  (p) => {
    const d = detectDomain(p)
    if (d) currentDomain.value = d
  },
)

const domains = computed(() => DOMAINS.filter((d) => isDomainVisible(d, (p) => auth.can(p))))
const groups = computed(() => buildNavForDomain((p) => auth.can(p), currentDomain.value))
const quickItems = computed(() => TOPBAR_QUICK)

// 切换业务域：默认进入该域的频道聚合首页
function pickDomain(key: DomainKey) {
  currentDomain.value = key
  router.push(DOMAIN_HOME_PATH[key])
}

// 中文数据域
const SCOPE_LABEL: Record<string, string> = {
  SELF: '仅本人',
  STORE: '本店',
  BRAND: '本品牌',
  REGION: '本区域',
  GROUP: '全集团',
}
const scopeLabel = computed(() => SCOPE_LABEL[auth.scope] || auth.scope)
</script>

<template>
  <CMobileShell v-if="isMobile" />
  <router-view v-else-if="isAuthPage" />
  <CShellDesktop
    v-else
    :groups="groups"
    :domains="domains"
    :current-domain="currentDomain"
    :quick-items="quickItems"
    :page-titles="PAGE_TITLES"
    :store="storeCtx.currentStoreName"
    :stores="storeCtx.storeNames"
    :username="auth.user.name"
    :user-job-title="auth.user.jobTitle"
    :avatar-letter="auth.user.avatarLetter"
    :scope-label="scopeLabel"
    @update:current-domain="(d: string) => pickDomain(d as DomainKey)"
    @pick-store="onPickStore"
  >
    <router-view />
  </CShellDesktop>
  <CToastHost />
</template>

<style>
/* 全局过渡 */
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
