<script setup lang="ts">
/* CMobileShell — C 端微信小程序外壳（390px，PC 浏览器高保真演示）
 * 结构：44px 微信状态栏 + 44px 导航栏（左返回 / 居中标题 / 右胶囊）+ 内容 + 50px TabBar
 * 页面标题与返回按钮通过路由 meta 配置：meta.mTitle / meta.mBack
 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()

const title = computed(() => (route.meta.mTitle as string) || '')
const showBack = computed(() => route.meta.mBack === true)
// TabBar 只在 3 个 tab 页显示
const tabs = [
  { to: '/m', label: '首页', icon: 'home', activeIcon: 'home' },
  { to: '/m/projects', label: '项目', icon: 'marketing', activeIcon: 'marketing' },
  { to: '/m/me', label: '我的', icon: 'user', activeIcon: 'user' },
]
const showTabBar = computed(() => tabs.some((t) => route.path === t.to))
const activeTab = computed(() => route.path)

function go(to: string) {
  if (route.path !== to) router.push(to)
}
function back() {
  // history.state.back 为 null 说明是直接落地/刷新进入（无上一页），回首页 tab；
  // 否则正常后退。避免 back 到站外 about:blank 导致"只能关小程序"。
  if (window.history.state && window.history.state.back) {
    router.back()
  } else {
    router.push('/m')
  }
}
</script>

<template>
  <div class="wx-shell">
    <div class="wx-phone">
      <!-- 微信状态栏（时间 + 信号/电量） -->
      <div class="wx-statusbar">
        <span class="wx-statusbar__time">9:41</span>
        <span class="wx-statusbar__icons">
          <span class="wx-sig">
            <i></i><i></i><i></i><i></i>
          </span>
          <span class="wx-wifi">
            <svg viewBox="0 0 16 12" width="15" height="11" fill="currentColor"><path d="M8 9.5a1.6 1.6 0 1 1 0 3.2 1.6 1.6 0 0 1 0-3.2zM8 6c1.7 0 3.3.7 4.5 1.8l-1.3 1.4A4.6 4.6 0 0 0 8 7.8c-1.2 0-2.3.5-3.2 1.4L3.5 7.8A6.4 6.4 0 0 1 8 6zm0-3.5c2.6 0 5 1 6.8 2.7l-1.3 1.3A7.7 7.7 0 0 0 8 4.3c-2.1 0-4 .8-5.5 2.2L1.2 5.2A9.5 9.5 0 0 1 8 2.5z"/></svg>
          </span>
          <span class="wx-batt"><i></i></span>
        </span>
      </div>

      <!-- 导航栏 -->
      <div class="wx-navbar">
        <button v-if="showBack" class="wx-navbar__back" @click="back" aria-label="返回">
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"/></svg>
        </button>
        <span v-else class="wx-navbar__placeholder"></span>
        <span class="wx-navbar__title">{{ title }}</span>
        <!-- 微信胶囊按钮 -->
        <span class="wx-capsule">
          <span class="wx-capsule__dots"><i></i><i></i><i></i></span>
          <span class="wx-capsule__divider"></span>
          <span class="wx-capsule__close">
            <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="9"/><line x1="14.5" y1="9.5" x2="9.5" y2="14.5"/><line x1="9.5" y1="9.5" x2="14.5" y2="14.5"/></svg>
          </span>
        </span>
      </div>

      <!-- 页面内容 -->
      <main class="wx-content" :class="{ 'wx-content--tab': showTabBar }">
        <router-view />
      </main>

      <!-- 底部 TabBar -->
      <nav v-if="showTabBar" class="wx-tabbar">
        <button
          v-for="t in tabs"
          :key="t.to"
          class="wx-tab"
          :class="{ 'wx-tab--active': activeTab === t.to }"
          @click="go(t.to)"
        >
          <span class="wx-tab__icon">
            <!-- 首页 -->
            <svg v-if="t.icon === 'home'" viewBox="0 0 24 24" width="24" height="24" :fill="activeTab === t.to ? 'currentColor' : 'none'" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"><path d="M3 10.5 12 3l9 7.5"/><path d="M5 9.5V20h14V9.5" :fill="activeTab === t.to ? 'currentColor' : 'none'"/></svg>
            <!-- 项目（网格） -->
            <svg v-else-if="t.icon === 'marketing'" viewBox="0 0 24 24" width="23" height="23" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="3" y="3" width="7" height="7" rx="1.5" :fill="activeTab === t.to ? 'currentColor' : 'none'"/><rect x="14" y="3" width="7" height="7" rx="1.5" :fill="activeTab === t.to ? 'currentColor' : 'none'"/><rect x="3" y="14" width="7" height="7" rx="1.5" :fill="activeTab === t.to ? 'currentColor' : 'none'"/><rect x="14" y="14" width="7" height="7" rx="1.5" :fill="activeTab === t.to ? 'currentColor' : 'none'"/></svg>
            <!-- 我的 -->
            <svg v-else viewBox="0 0 24 24" width="23" height="23" :fill="activeTab === t.to ? 'currentColor' : 'none'" stroke="currentColor" stroke-width="1.8"><circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/></svg>
          </span>
          <span class="wx-tab__label">{{ t.label }}</span>
        </button>
      </nav>
    </div>
  </div>
</template>

<style scoped>
.wx-shell {
  min-height: 100vh;
  background: #e9e9ec;
  display: flex;
  justify-content: center;
}
.wx-phone {
  width: 100%;
  max-width: 390px;
  min-height: 100vh;
  background: #f6f6f8;
  display: flex;
  flex-direction: column;
  position: relative;
  box-shadow: 0 0 32px rgba(0, 0, 0, 0.08);
}

/* 状态栏 */
.wx-statusbar {
  flex-shrink: 0;
  height: 44px;
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px 0  24px;
  color: #111;
}
.wx-statusbar__time { font-size: 15px; font-weight: 600; letter-spacing: .3px; }
.wx-statusbar__icons { display: flex; align-items: center; gap: 5px; }
.wx-sig { display: inline-flex; align-items: flex-end; gap: 1.5px; height: 11px; }
.wx-sig i { width: 3px; background: #111; border-radius: 1px; }
.wx-sig i:nth-child(1) { height: 4px; }
.wx-sig i:nth-child(2) { height: 6px; }
.wx-sig i:nth-child(3) { height: 8px; }
.wx-sig i:nth-child(4) { height: 11px; }
.wx-batt { width: 24px; height: 12px; border: 1px solid #111; border-radius: 3px; position: relative; padding: 1.5px; box-sizing: border-box; }
.wx-batt::after { content: ''; position: absolute; right: -3px; top: 3px; width: 2px; height: 5px; background: #111; border-radius: 0 1px 1px 0; }
.wx-batt i { display: block; height: 100%; width: 78%; background: #111; border-radius: 1px; }

/* 导航栏 */
.wx-navbar {
  flex-shrink: 0;
  height: 44px;
  background: #fff;
  display: flex;
  align-items: center;
  position: relative;
  border-bottom: 0.5px solid rgba(0, 0, 0, 0.04);
}
.wx-navbar__back {
  width: 40px; height: 44px;
  display: flex; align-items: center; justify-content: center;
  border: none; background: transparent; color: #111; cursor: pointer; margin-left: 4px;
}
.wx-navbar__placeholder { width: 44px; }
.wx-navbar__title {
  position: absolute;
  left: 50%; transform: translateX(-50%);
  font-size: 17px; font-weight: 600; color: #111;
  max-width: 60%; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
/* 微信胶囊 */
.wx-capsule {
  position: absolute; right: 12px; top: 50%; transform: translateY(-50%);
  width: 87px; height: 32px;
  border: 0.5px solid rgba(0, 0, 0, 0.12);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.7);
  display: flex; align-items: center; justify-content: space-around;
  color: #111;
}
.wx-capsule__dots { display: flex; gap: 3px; align-items: center; }
.wx-capsule__dots i { width: 4px; height: 4px; border-radius: 50%; background: #111; }
.wx-capsule__divider { width: 0.5px; height: 20px; background: rgba(0, 0, 0, 0.12); }
.wx-capsule__close { display: flex; }

/* 内容 */
.wx-content { flex: 1; overflow-y: auto; }
.wx-content--tab { padding-bottom: 0; }

/* TabBar */
.wx-tabbar {
  flex-shrink: 0;
  height: 50px;
  background: #fff;
  border-top: 0.5px solid rgba(0, 0, 0, 0.08);
  display: flex;
  padding-bottom: env(safe-area-inset-bottom);
}
.wx-tab {
  flex: 1; border: none; background: transparent; cursor: pointer;
  display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 2px;
  color: #9aa0a6;
}
.wx-tab--active { color: #ff6b9e; }
.wx-tab__icon { display: flex; }
.wx-tab__label { font-size: 10px; line-height: 1; }
</style>
