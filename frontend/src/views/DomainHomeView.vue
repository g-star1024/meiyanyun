<script setup lang="ts">
/* ============================================================
 * 业务域「频道首页」聚合页（工作台/客户/门店/营销/财务/管理后台）
 * 结构：KPI 概览 + 模块矩阵（自动从该域导航菜单聚合，按权限过滤）+ 关键信息/待办侧栏
 * AI 中心保持独立的 A1HomeView（/ai），不走此组件。
 * ============================================================ */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import CCard from '@/components/CCard.vue'
import CButton from '@/components/CButton.vue'
import CKpi from '@/components/CKpi.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CIcon from '@/components/CIcon.vue'
import { useAuthStore } from '@/stores/auth'
import { buildNavForDomain, type DomainKey } from '@/config/nav'
import { DOMAIN_HOME, MODULE_STATS } from '@/config/domain-home'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

// 路由 meta.domain 标识当前域
const domain = computed<Exclude<DomainKey, 'ai'>>(() => (route.meta.domain as any) || 'workbench')
const meta = computed(() => DOMAIN_HOME[domain.value])

// 模块矩阵：自动聚合该域所有有权限的菜单项；排除写死示例 ID 的深度页（如 /customers/C-201）
const modules = computed(() => {
  const groups = buildNavForDomain((p) => auth.can(p), domain.value)
  return groups
    .flatMap((g) => g.items.map((i) => ({ ...i, group: g.title })))
    .filter((i) => !/\/[A-Z]-\d+/.test(i.to))
    .filter((i) => i.to !== '/closed-loop')
})

function go(to: string) {
  router.push(to)
}

const todoPill = (t: string) => {
  if (t === 'approval') return { status: 'warning' as const, label: '审批' }
  if (t === 'alert') return { status: 'danger' as const, label: '告警' }
  if (t === 'review') return { status: 'info' as const, label: '复核' }
  return { status: 'success' as const, label: '通知' }
}

// 主题色：模块卡 soft 底 + 实底 icon 方块
const themeStyle = computed(() => {
  switch (meta.value.theme) {
    case 'brand':   return { soft: 'var(--c-brand-soft)', solid: 'var(--c-brand)', hover: 'rgba(255,107,158,.12)' }
    case 'teal':
    case 'success': return { soft: 'var(--c-success-bg)', solid: 'var(--c-success-fg)', hover: 'rgba(82,196,26,.10)' }
    case 'orange':
    case 'warning': return { soft: 'var(--c-warning-bg)', solid: 'var(--c-warning-fg)', hover: 'rgba(250,140,22,.10)' }
    case 'purple':  return { soft: 'var(--c-purple-soft)', solid: 'var(--c-purple)', hover: 'rgba(140,92,245,.12)' }
    default:        return { soft: '#eef3ff', solid: '#4c7dff', hover: 'rgba(76,125,255,.10)' } // blue
  }
})
</script>

<template>
  <div v-if="meta" class="dhome">
    <!-- KPI 概览 -->
    <div class="dhome__kpis">
      <CKpi v-for="k in meta.kpis" :key="k.label" v-bind="k" />
    </div>

    <div class="dhome__body">
      <!-- 模块矩阵 -->
      <CCard padding="lg" class="dhome__modules">
        <template #header>
          <div class="card-head">
            <h3>{{ meta.title }}模块</h3>
            <span class="card-sub">{{ modules.length }} 个模块 · 点击进入</span>
          </div>
        </template>
        <div class="mod-grid">
          <div
            v-for="m in modules"
            :key="m.to"
            class="mod-card"
            :style="{ background: themeStyle.soft }"
            @click="go(m.to)"
          >
            <div class="mod-card__icon" :style="{ background: themeStyle.solid }">
              <CIcon :name="(m.icon as any)" :size="22" />
            </div>
            <div class="mod-card__body">
              <div class="mod-card__title">{{ m.label }}</div>
              <div v-if="MODULE_STATS[m.to]" class="mod-card__stat" :style="{ color: themeStyle.solid }">{{ MODULE_STATS[m.to] }}</div>
              <div v-else-if="m.group" class="mod-card__group">{{ m.group }}</div>
            </div>
            <CIcon name="chevron-right" :size="16" class="mod-card__arrow" />
          </div>
        </div>
      </CCard>

      <!-- 侧栏：关键信息 + 待办 -->
      <div class="dhome__side">
        <CCard padding="lg">
          <template #header>
            <div class="card-head"><h3>{{ meta.highlightsTitle }}</h3></div>
          </template>
          <div class="hl-list">
            <button
              v-for="(h, i) in meta.highlights"
              :key="i"
              class="hl-item"
              @click="h.to && go(h.to)"
              :class="{ 'is-link': h.to }"
            >
              <span class="hl-item__label">{{ h.label }}</span>
              <span class="hl-item__value" :class="`is-${h.tone || 'text'}`">{{ h.value }}</span>
            </button>
          </div>
        </CCard>

        <CCard padding="lg">
          <template #header>
            <div class="card-head">
              <h3>{{ meta.todosTitle }}</h3>
              <CButton size="sm" variant="secondary" @click="go('/approval')">全部</CButton>
            </div>
          </template>
          <div class="todo-list">
            <div v-for="(t, i) in meta.todos" :key="i" class="todo-item" @click="go(t.to)">
              <CStatusPill :status="todoPill(t.type).status" dot>{{ todoPill(t.type).label }}</CStatusPill>
              <div class="todo-item__body">
                <div class="todo-item__title">{{ t.title }}</div>
                <div class="todo-item__desc">{{ t.desc }}</div>
              </div>
              <div class="todo-item__time">{{ t.time }}</div>
            </div>
          </div>
          <div v-if="meta.notice" class="notice-bar">
            <CIcon name="alert" :size="15" />
            <span>{{ meta.notice }}</span>
          </div>
        </CCard>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dhome { display: flex; flex-direction: column; gap: var(--s-lg); }

.dhome__kpis { display: flex; gap: var(--s-md); }

.dhome__body { display: grid; grid-template-columns: 1fr 360px; gap: var(--s-lg); align-items: start; }
@media (max-width: 1100px) { .dhome__body { grid-template-columns: 1fr; } }

.card-head { display: flex; align-items: center; justify-content: space-between; width: 100%; }
.card-head h3 { margin: 0; font-size: var(--t-md); font-weight: 600; color: var(--c-text); }
.card-sub { font-size: var(--t-xs); color: var(--c-text-3); }

/* 模块矩阵 */
.mod-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: var(--s-md); }
.mod-card {
  display: flex; align-items: flex-start; gap: var(--s-md);
  padding: var(--s-md); border-radius: var(--r-lg);
  cursor: pointer; transition: transform .15s, box-shadow .15s;
  border: 1px solid transparent;
}
.mod-card:hover { transform: translateY(-2px); box-shadow: var(--shadow-pop); }
.mod-card__icon {
  flex-shrink: 0; width: 44px; height: 44px; border-radius: var(--r-md);
  color: #fff; display: flex; align-items: center; justify-content: center;
}
.mod-card__body { min-width: 0; flex: 1; }
.mod-card__title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.mod-card__stat {
  font-size: 11px; margin-top: 5px; line-height: 1.4;
  font-variant-numeric: tabular-nums; font-weight: 500;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.mod-card__group { font-size: 11px; color: var(--c-text-3); margin-top: 3px; }
.mod-card__arrow { color: var(--c-text-3); flex-shrink: 0; margin-top: 2px; }

/* 侧栏 */
.dhome__side { display: flex; flex-direction: column; gap: var(--s-lg); }
.hl-list { display: flex; flex-direction: column; }
.hl-item {
  display: flex; align-items: center; justify-content: space-between;
  padding: var(--s-sm) var(--s-xs); border: none; background: transparent;
  border-bottom: 1px solid var(--c-border-light); cursor: default;
  font-size: var(--t-sm); text-align: left;
}
.hl-item:last-child { border-bottom: none; }
.hl-item.is-link { cursor: pointer; border-radius: var(--r-sm); }
.hl-item.is-link:hover { background: var(--c-bg-right); }
.hl-item__label { color: var(--c-text-2); }
.hl-item__value { font-weight: 600; font-variant-numeric: tabular-nums; }
.is-text { color: var(--c-text); }
.is-brand { color: var(--c-brand); }
.is-teal, .is-success { color: var(--c-success-fg); }
.is-orange, .is-warning { color: var(--c-warning-fg); }
.is-danger { color: var(--c-danger-fg); }
.is-purple { color: var(--c-purple); }
.is-blue { color: #2f5fe0; }

/* 待办 */
.todo-list { display: flex; flex-direction: column; gap: var(--s-sm); }
.todo-item {
  display: flex; align-items: flex-start; gap: var(--s-sm); padding: var(--s-sm) 0;
  border-bottom: 1px solid var(--c-border-light); cursor: pointer;
}
.todo-item:last-child { border-bottom: none; }
.todo-item:hover .todo-item__title { color: var(--c-brand); }
.todo-item__body { flex: 1; min-width: 0; }
.todo-item__title { font-size: var(--t-sm); font-weight: 500; color: var(--c-text); }
.todo-item__desc { font-size: var(--t-xs); color: var(--c-text-3); line-height: 1.4; margin-top: 2px; }
.todo-item__time { font-size: 11px; color: var(--c-text-3); flex-shrink: 0; }

.notice-bar {
  margin-top: var(--s-md); padding: var(--s-sm) var(--s-md);
  background: var(--c-brand-soft); border-radius: var(--r-md);
  display: flex; align-items: center; gap: var(--s-sm);
  font-size: 11px; color: var(--c-brand); line-height: 1.4;
}
</style>
