<script setup lang="ts">
/* ============================================================
 * C-Shell-Desktop 桌面主结构件
 * 侧栏 240（业务域切换在侧栏底部）+ 顶栏 56 + 内容区（flex:1）
 * Wave 8: 业务域在侧栏底部 + 顶栏仅搜索/消息 + 用户下拉菜单
 * 铁律：禁止裸值，全部 tokens.css 变量
 * ============================================================ */
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import CSidebar from './CSidebar.vue'
import CIcon from './CIcon.vue'
import CButton from './CButton.vue'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notification'
import type { Role } from '@/types/domain'

interface DomainItem { key: string; label: string; icon: string }
interface NavItem { to: string; label: string; icon: string; badge?: string | number; authOnly?: boolean; permission?: string }
interface NavGroup { title?: string; items: NavItem[] }
interface QuickItem { to: string; label: string; icon: string; authOnly?: boolean }

export interface PageAction {
  label: string
  to?: string
  variant?: 'primary' | 'secondary' | 'ghost' | 'text' | 'danger'
  size?: 'sm' | 'md' | 'lg'
}

export interface PageTitle {
  breadcrumb: string
  title: string
  /** 频道首页专用：大字标题下方的小字模块描述（设置后顶栏改为「大字在上、小字在下」） */
  subtitle?: string
  hideTopbar?: boolean
  hideTopbarDesktop?: boolean
  action?: PageAction
}

const props = defineProps<{
  groups: NavGroup[]
  domains?: DomainItem[]
  currentDomain?: string
  quickItems?: QuickItem[]
  store?: string
  stores?: string[]
  pageTitles?: Record<string, PageTitle>
  username?: string
  userJobTitle?: string
  avatarLetter?: string
  scopeLabel?: string
}>()

const emit = defineEmits<{
  (e: 'update:currentDomain', d: string): void
  (e: 'pick-store', s: string): void
}>()

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const notification = useNotificationStore()
// 门店上下文为受控值：显示用 props.store（来自全局 storeContext），选择只 emit 给父更新
const drawerOpen = ref(false)
const userMenuOpen = ref(false)
const roleSwitching = ref<Role | ''>('')

onMounted(() => {
  notification.seed()
})

const currentPage = computed(() => {
  if (!props.pageTitles) return { breadcrumb: '美研云', title: '门店中台' }
  if (props.pageTitles[route.path]) return props.pageTitles[route.path]
  const dynamicPath = route.path.replace(/\/\d+/, '/detail')
  if (props.pageTitles[dynamicPath]) return props.pageTitles[dynamicPath]
  return { breadcrumb: '美研云', title: '门店中台' }
})

// 大字标题下方的小字：频道首页用 subtitle（模块描述）；普通页面用面包屑的上级路径（去掉与标题重复的末级）
const titleSub = computed(() => {
  const p = currentPage.value
  if (p.subtitle) return p.subtitle
  const parts = p.breadcrumb.split('/').map((s) => s.trim()).filter(Boolean)
  if (parts.length <= 1) return parts[0] === p.title ? '' : parts[0] || ''
  // 去掉末级（通常与 title 重复）
  if (parts[parts.length - 1] === p.title) parts.pop()
  return parts.join(' / ')
})

function pickDomain(d: { key: string }) {
  emit('update:currentDomain', d.key)
}

// 顶栏只保留：全局搜索 + 消息铃铛
const topbarQuick = computed(() =>
  (props.quickItems || []).filter((q) => q.to === '/search' || q.to === '/notifications'),
)

const QUICK_TO_PROFILE: QuickItem[] = [
  { to: '/profile', label: '个人中心', icon: 'user' },
  { to: '/notif-settings', label: '消息设置', icon: 'bell' },
  { to: '/theme', label: '主题外观', icon: 'sun' },
  { to: '/help', label: '帮助中心', icon: 'chat' },
]

const ALL_ROLES: { key: Role; label: string }[] = [
  { key: 'SUPER_ADMIN', label: '集团管理员' },
  { key: 'REGION_MGR', label: '区域经理' },
  { key: 'STORE_MGR', label: '门店店长' },
  { key: 'CONSULTANT', label: '咨询顾问' },
  { key: 'DOCTOR', label: '医生' },
  { key: 'FRONT_DESK', label: '前台/收银' },
  { key: 'OPERATOR', label: '运营' },
  { key: 'FINANCE', label: '财务' },
]

async function switchRole(r: Role) {
  roleSwitching.value = r
  // 优先走后端 dev-login 换发该角色真实 token；后端不可用时回退离线演示视角
  await auth.loginAs(r)
  roleSwitching.value = ''
  userMenuOpen.value = false
  // 切角色后回到工作台频道首页，避免停在无权限页
  router.push('/workbench')
}

function logout() {
  auth.logout()
  userMenuOpen.value = false
  router.push('/login')
}
</script>

<template>
  <div class="shell">
    <!-- Desktop sidebar（业务域切换在侧栏底部） -->
    <CSidebar
      class="shell__sidebar"
      :groups="groups"
      :domains="domains"
      :current-domain="currentDomain"
      :store="store"
      :stores="stores"
      @pick-store="(s: string) => $emit('pick-store', s)"
      @pick-domain="(k: string) => pickDomain({ key: k })"
    />

    <!-- Drawer mask -->
    <Transition name="fade">
      <div v-if="drawerOpen" class="shell__mask" @click="drawerOpen = false" />
    </Transition>
    <CSidebar
      v-if="drawerOpen"
      class="shell__drawer"
      :groups="groups"
      :domains="domains"
      :current-domain="currentDomain"
      :store="store"
      :stores="stores"
      @click="drawerOpen = false"
      @pick-domain="(k: string) => { pickDomain({ key: k }); drawerOpen = false }"
    />

    <div class="shell__main">
      <header
        class="shell__topbar"
        :class="{
          'is-hidden-tablet': currentPage.hideTopbar,
          'is-hidden': currentPage.hideTopbarDesktop,
        }"
      >
        <div class="topbar__left">
          <button class="topbar__menu" @click="drawerOpen = true">
            <CIcon name="menu" :size="20" />
          </button>
          <div class="topbar__title-group">
            <span class="topbar__page-title">{{ currentPage.title }}</span>
            <span v-if="titleSub" class="topbar__subtitle">{{ titleSub }}</span>
          </div>
        </div>
        <div class="topbar__right">
          <CButton
            v-if="currentPage.action"
            :variant="currentPage.action.variant || 'primary'"
            :size="currentPage.action.size || 'sm'"
            @click="currentPage.action.to ? router.push(currentPage.action.to) : undefined"
          >
            {{ currentPage.action.label }}
          </CButton>

          <!-- 顶栏快捷入口：仅全局搜索 + 消息 -->
          <div class="topbar__quick">
            <RouterLink
              v-for="q in topbarQuick"
              :key="q.to"
              :to="q.to"
              class="quick-btn"
              :title="q.label"
              active-class="is-active"
            >
              <CIcon :name="q.icon as any" :size="18" />
              <!-- 消息未读角标 -->
              <span
                v-if="q.to === '/notifications' && notification.unreadCount > 0"
                class="badge"
              >{{ notification.unreadCount > 99 ? '99+' : notification.unreadCount }}</span>
            </RouterLink>
          </div>

          <!-- 用户下拉菜单 -->
          <div class="topbar__user-menu" @click.stop>
            <button class="user-trigger" @click="userMenuOpen = !userMenuOpen">
              <span class="user-trigger__avatar">{{ avatarLetter || '苏' }}</span>
              <span class="user-trigger__info">
                <span class="user-trigger__name">{{ username || '苏晴' }}</span>
                <span class="user-trigger__job">{{ userJobTitle || '门店店长' }}</span>
              </span>
              <CIcon name="chevron-down" :size="14" :class="{ 'is-open': userMenuOpen }" />
            </button>
            <!-- 点击外部关闭 -->
            <div v-if="userMenuOpen" class="user-pop-backdrop" @click="userMenuOpen = false" />
            <Transition name="menu">
              <div v-if="userMenuOpen" class="user-pop">
                <div class="user-pop__head">
                  <span class="user-pop__avatar">{{ avatarLetter || '苏' }}</span>
                  <div class="user-pop__meta">
                    <div class="user-pop__name">{{ username || '苏晴' }}</div>
                    <div class="user-pop__sub">{{ userJobTitle || '门店店长' }} · {{ scopeLabel || '本店' }}</div>
                  </div>
                </div>
                <div class="user-pop__group">
                  <RouterLink
                    v-for="q in QUICK_TO_PROFILE"
                    :key="q.to"
                    :to="q.to"
                    class="user-pop__item"
                    @click="userMenuOpen = false"
                  >
                    <CIcon :name="q.icon as any" :size="16" />
                    <span>{{ q.label }}</span>
                  </RouterLink>
                </div>
                <div class="user-pop__group user-pop__group--role">
                  <div class="user-pop__label">切换角色（演示）</div>
                  <div class="role-grid">
                    <button
                      v-for="r in ALL_ROLES"
                      :key="r.key"
                      class="role-chip"
                      :class="{ 'is-active': auth.currentRoles[0] === r.key }"
                      :disabled="!!roleSwitching"
                      @click="switchRole(r.key)"
                    >{{ roleSwitching === r.key ? '切换中…' : r.label }}</button>
                  </div>
                </div>
                <div class="user-pop__foot">
                  <button class="user-pop__logout" @click="logout">
                    <CIcon name="logout" :size="15" />
                    <span>退出登录</span>
                  </button>
                </div>
              </div>
            </Transition>
          </div>
        </div>
      </header>

      <main class="shell__content">
        <slot />
      </main>
    </div>
  </div>
</template>

<style scoped>
.shell {
  display: flex;
  height: 100vh;
  overflow: hidden;
}
.shell__drawer {
  display: none;
}
.shell__main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

/* ---------- 顶栏 ---------- */
.shell__topbar {
  height: 56px;
  min-height: 56px;
  flex-shrink: 0;
  background: var(--c-surface);
  border-bottom: 1px solid var(--c-border-light);
  padding: 0 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  position: sticky;
  top: 0;
  z-index: 20;
}
.shell__topbar.is-hidden {
  display: none;
}
.topbar__left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}
.topbar__menu {
  display: none;
  width: 36px;
  height: 36px;
  border: none;
  background: transparent;
  border-radius: 8px;
  color: var(--c-text-2);
  cursor: pointer;
  align-items: center;
  justify-content: center;
}
.topbar__title-group {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.topbar__page-title {
  font-size: 18px;
  font-weight: 700;
  color: var(--c-text);
  line-height: 1.2;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.topbar__subtitle {
  font-size: var(--t-xs);
  color: var(--c-text-3);
  line-height: 1.2;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.topbar__right {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
}

/* 快捷图标：仅搜索 + 消息 */
.topbar__quick {
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 0 6px;
  border-right: 1px solid var(--c-border-light);
  margin-right: 4px;
}
.quick-btn {
  width: 34px;
  height: 34px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  color: var(--c-text-2);
  transition: background 0.15s, color 0.15s;
}
.quick-btn:hover {
  background: var(--c-bg-right);
  color: var(--c-text);
}
.quick-btn.is-active {
  color: var(--c-brand);
  background: var(--c-brand-soft, rgba(255, 107, 158, 0.1));
}
.quick-btn {
  position: relative;
}
.badge {
  position: absolute;
  top: -6px;
  right: -12px;
  background: var(--c-danger, #e5484d);
  color: #fff;
  font-size: 10px;
  font-weight: 600;
  padding: 0 4px;
  border-radius: 8px;
  line-height: 14px;
  white-space: nowrap;
}

/* 用户触发器 */
.topbar__user-menu {
  position: relative;
}
.user-trigger {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 4px 10px 4px 4px;
  border: none;
  background: transparent;
  border-radius: 10px;
  cursor: pointer;
  transition: background 0.15s;
}
.user-trigger:hover {
  background: var(--c-bg-right);
}
.user-trigger__avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: var(--c-brand);
  color: #fff;
  font-weight: 600;
  font-size: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.user-trigger__info {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  line-height: 1.2;
}
.user-trigger__name {
  font-size: 13px;
  font-weight: 600;
  color: var(--c-text);
}
.user-trigger__job {
  font-size: 11px;
  color: var(--c-text-3);
}
.user-trigger :deep(.cicon) {
  color: var(--c-text-3);
  transition: transform 0.15s;
}
.user-trigger :deep(.cicon.is-open) {
  transform: rotate(180deg);
}

/* 用户下拉弹层 */
.user-pop-backdrop {
  position: fixed;
  inset: 0;
  z-index: 99;
}
.user-pop {
  position: absolute;
  right: 0;
  top: calc(100% + 6px);
  width: 280px;
  background: var(--c-surface);
  border: 1px solid var(--c-border-light);
  border-radius: 12px;
  box-shadow: var(--shadow-pop);
  padding: 8px;
  z-index: 100;
}
.user-pop__head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 10px 12px;
  border-bottom: 1px solid var(--c-border-light);
  margin-bottom: 6px;
}
.user-pop__avatar {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: var(--c-brand);
  color: #fff;
  font-weight: 600;
  font-size: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.user-pop__meta { min-width: 0; flex: 1; }
.user-pop__name {
  font-size: 14px;
  font-weight: 600;
  color: var(--c-text);
}
.user-pop__sub {
  font-size: 12px;
  color: var(--c-text-3);
  margin-top: 2px;
}
.user-pop__group {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.user-pop__item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-radius: 8px;
  color: var(--c-text);
  font-size: 13px;
  transition: background 0.15s;
}
.user-pop__item:hover {
  background: var(--c-bg-right);
}
.user-pop__item :deep(.cicon) {
  color: var(--c-text-3);
}
.user-pop__group--role {
  padding: 10px 10px 6px;
  border-top: 1px solid var(--c-border-light);
  margin-top: 6px;
}
.user-pop__label {
  font-size: 11px;
  color: var(--c-text-3);
  font-weight: 600;
  letter-spacing: 0.3px;
  margin-bottom: 8px;
}
.role-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px;
}
.role-chip {
  padding: 6px 8px;
  border: 1px solid var(--c-border-light);
  background: var(--c-surface);
  border-radius: 6px;
  font-size: 12px;
  color: var(--c-text-2);
  cursor: pointer;
  text-align: center;
  transition: all 0.15s;
}
.role-chip:hover {
  border-color: var(--c-brand);
  color: var(--c-brand);
}
.role-chip.is-active {
  background: var(--c-brand-soft, rgba(255, 107, 158, 0.1));
  border-color: var(--c-brand);
  color: var(--c-brand);
  font-weight: 600;
}
.user-pop__foot {
  border-top: 1px solid var(--c-border-light);
  margin-top: 6px;
  padding-top: 6px;
}
.user-pop__logout {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border: none;
  background: transparent;
  border-radius: 8px;
  color: var(--c-danger, #e5484d);
  font-size: 13px;
  cursor: pointer;
  transition: background 0.15s;
}
.user-pop__logout:hover {
  background: rgba(229, 72, 77, 0.08);
}

/* 内容区 */
.shell__content {
  flex: 1;
  padding: var(--s-lg);
  overflow: auto;
  background: var(--c-bg-right);
}

/* ---------- Tablet ≤1024 ---------- */
@media (max-width: 1024px) {
  .shell__sidebar { display: none; }
  .shell__topbar {
    height: 52px;
    min-height: 52px;
    padding: 0 12px;
  }
  .topbar__menu { display: flex; }
  .topbar__right { gap: 6px; }
  .user-trigger__info { display: none; }
  .user-trigger { padding: 4px; }
  .topbar__quick {
    padding: 0 4px;
    gap: 0;
  }
  .quick-btn { width: 32px; height: 32px; }
  .shell__content { padding: var(--s-md); }
  .shell__mask {
    display: block;
    position: fixed;
    inset: 0;
    background: rgba(20, 21, 43, 0.4);
    z-index: 30;
  }
  .shell__drawer {
    display: flex;
    position: fixed;
    top: 0;
    left: 0;
    bottom: 0;
    z-index: 40;
    box-shadow: var(--shadow-pop);
  }
  .user-pop {
    width: calc(100vw - 24px);
    right: -8px;
  }
}

.fade-enter-active,
.fade-leave-active { transition: opacity 0.2s; }
.fade-enter-from,
.fade-leave-to { opacity: 0; }
.menu-enter-active,
.menu-leave-active {
  transition: opacity 0.15s, transform 0.15s;
  transform-origin: top right;
}
.menu-enter-from,
.menu-leave-to {
  opacity: 0;
  transform: scale(0.96) translateY(-4px);
}
</style>
