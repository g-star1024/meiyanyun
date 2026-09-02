<script setup lang="ts">
// T3-03 + G-02 消息通知中心：分类导航 + 未读/全部 + 通知列表 + 通知偏好。
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useNotificationStore, type NotifyCategory, type NotifyChannel } from '@/stores/notification'
import CKpi from '@/components/CKpi.vue'
import CCard from '@/components/CCard.vue'
import CStatusPill from '@/components/CStatusPill.vue'
import CButton from '@/components/CButton.vue'
import CCheckbox from '@/components/CCheckbox.vue'
import CIcon from '@/components/CIcon.vue'

const router = useRouter()
const nt = useNotificationStore()
onMounted(() => nt.seed())

const categories: { key: NotifyCategory | 'ALL'; label: string; icon: string }[] = [
  { key: 'ALL', label: '全部通知', icon: 'bell' },
  { key: 'APPROVAL', label: '审批待办', icon: 'check-square' },
  { key: 'CUSTOMER', label: '客户提醒', icon: 'customer' },
  { key: 'INVENTORY', label: '库存预警', icon: 'box' },
  { key: 'MARKETING', label: '营销任务', icon: 'marketing' },
  { key: 'SYSTEM', label: '系统公告', icon: 'settings' },
]

const channelOptions: { key: NotifyChannel; label: string }[] = [
  { key: 'INBOX', label: '站内信' },
  { key: 'SMS', label: '短信' },
  { key: 'WECHAT', label: '企微/微信' },
  { key: 'EMAIL', label: '邮件' },
]

const kpi = computed(() => ({
  unread: nt.unreadCount,
  urgent: nt.items.filter((n) => !n.read && n.level === 'URGENT').length,
  approval: nt.unreadByCategory.APPROVAL || 0,
  inventory: nt.unreadByCategory.INVENTORY || 0,
}))

function levelPill(level: string) {
  if (level === 'URGENT') return { status: 'danger' as const, text: '紧急' }
  if (level === 'WARNING') return { status: 'warning' as const, text: '提醒' }
  return { status: 'info' as const, text: '通知' }
}

function catIcon(c: NotifyCategory): string {
  return { APPROVAL: 'check-square', CUSTOMER: 'customer', INVENTORY: 'box', MARKETING: 'marketing', SYSTEM: 'settings' }[c]
}

function openItem(id: string, link?: string) {
  nt.markRead(id)
  if (link) router.push(link).catch(() => {})
}

function fmtTime(iso: string) {
  const d = new Date(iso)
  const diff = (Date.now() - d.getTime()) / 1000
  if (diff < 60) return '刚刚'
  if (diff < 3600) return `${Math.floor(diff / 60)} 分钟前`
  if (diff < 86400) return `${Math.floor(diff / 3600)} 小时前`
  return `${d.getMonth() + 1}/${d.getDate()}`
}
</script>

<template>
  <div class="nt">
    <!-- KPI -->
    <div class="nt__kpis">
      <CKpi :value="String(kpi.unread)" label="未读消息" tone="brand" icon="bell" />
      <CKpi :value="String(kpi.urgent)" label="紧急待处理" tone="danger" icon="alert" />
      <CKpi :value="String(kpi.approval)" label="审批待办" tone="warning" icon="check-square" />
      <CKpi :value="String(kpi.inventory)" label="库存预警" tone="danger" icon="alert" />
    </div>

    <div class="nt__body">
      <!-- 左：分类 -->
      <CCard padding="none" class="nt__cats">
        <button v-for="c in categories" :key="c.key"
                class="cat" :class="{ 'is-active': nt.activeCategory === c.key }"
                @click="nt.activeCategory = c.key">
          <CIcon :name="(c.icon as any)" :size="16" />
          <span class="cat__label">{{ c.label }}</span>
          <span v-if="c.key !== 'ALL' && nt.unreadByCategory[c.key]" class="cat__badge">
            {{ nt.unreadByCategory[c.key] }}
          </span>
        </button>
        <div class="cat__footer">
          <CButton variant="text" size="sm" :disabled="nt.unreadCount === 0" @click="nt.markAllRead()">
            <CIcon name="check" :size="14" /> 全部标记已读
          </CButton>
        </div>
      </CCard>

      <!-- 中：消息列表 -->
      <CCard padding="none" class="nt__list">
        <div class="nt__list-head">
          <h3 class="nt__list-title">{{ categories.find((c) => c.key === nt.activeCategory)?.label }}</h3>
          <div class="nt__filter">
            <button :class="{ 'is-active': nt.readFilter === 'UNREAD' }" @click="nt.readFilter = 'UNREAD'">未读</button>
            <button :class="{ 'is-active': nt.readFilter === 'ALL' }" @click="nt.readFilter = 'ALL'">全部</button>
          </div>
        </div>
        <div class="nt__rows">
          <div v-for="n in nt.filtered" :key="n.id"
               class="msg" :class="{ 'is-unread': !n.read }"
               @click="openItem(n.id, n.link)">
            <div class="msg__icon" :class="'msg__icon--' + n.category.toLowerCase()">
              <CIcon :name="(catIcon(n.category) as any)" :size="16" />
            </div>
            <div class="msg__body">
              <div class="msg__top">
                <span class="msg__title">{{ n.title }}</span>
                <CStatusPill :status="levelPill(n.level).status" dot>{{ levelPill(n.level).text }}</CStatusPill>
              </div>
              <p class="msg__content">{{ n.content }}</p>
              <div class="msg__meta">
                <span>{{ n.sender }}</span>
                <span>{{ fmtTime(n.createdAt) }}</span>
                <span v-if="n.link" class="msg__link">查看详情 →</span>
              </div>
            </div>
            <span v-if="!n.read" class="msg__dot"></span>
          </div>
          <div v-if="nt.filtered.length === 0" class="empty">
            <CIcon name="check" :size="32" />
            <p>暂无消息</p>
          </div>
        </div>
      </CCard>

      <!-- 右：通知偏好 -->
      <CCard title="通知偏好" padding="lg" class="nt__prefs">
        <p class="prefs__hint">按类别设置是否接收及接收渠道。</p>
        <div v-for="p in nt.preferences" :key="p.category" class="pref">
          <div class="pref__head">
            <span class="pref__label">{{ nt.categoryLabel(p.category) }}</span>
            <CCheckbox :model-value="p.enabled" @update:model-value="(v: boolean) => nt.togglePreference(p.category, v)">
              {{ p.enabled ? '已开启' : '已关闭' }}
            </CCheckbox>
          </div>
          <div v-if="p.enabled" class="pref__channels">
            <label v-for="ch in channelOptions" :key="ch.key" class="ch">
              <input type="checkbox" :checked="p.channels.includes(ch.key)"
                     @change="nt.toggleChannel(p.category, ch.key)" />
              <span>{{ ch.label }}</span>
            </label>
          </div>
        </div>
      </CCard>
    </div>
  </div>
</template>

<style scoped>
.nt { display: flex; flex-direction: column; gap: var(--s-lg); }
.nt__kpis { display: flex; gap: var(--s-md); flex-wrap: wrap; }
.nt__kpis :deep(.ckpi) { flex: 1 1 0; min-width: 168px; }

.nt__body { display: grid; grid-template-columns: 220px 1fr 300px; gap: var(--s-lg); align-items: start; }

/* 分类 */
.cat { display: flex; align-items: center; gap: var(--s-sm); width: 100%; padding: var(--s-sm) var(--s-lg); border: none; background: none; font-size: var(--t-sm); color: var(--c-text-2); cursor: pointer; text-align: left; transition: background .15s; }
.cat:hover { background: var(--c-surface-muted, #f7f8fa); }
.cat.is-active { background: var(--c-brand-soft); color: var(--c-brand); font-weight: 600; box-shadow: inset 3px 0 0 var(--c-brand); }
.cat__label { flex: 1; }
.cat__badge { background: var(--c-danger-fg); color: #fff; font-size: 10px; padding: 0 6px; border-radius: 10px; line-height: 16px; }
.cat__footer { padding: var(--s-sm) var(--s-xs); border-top: 1px solid var(--c-border); }

/* 列表 */
.nt__list-head { display: flex; justify-content: space-between; align-items: center; padding: var(--s-md) var(--s-lg); border-bottom: 1px solid var(--c-border); }
.nt__list-title { margin: 0; font-size: var(--t-md); font-weight: 600; }
.nt__filter { display: flex; gap: var(--s-xxs); }
.nt__filter button { border: none; background: none; padding: 4px 12px; border-radius: var(--r-sm); font-size: var(--t-xs); color: var(--c-text-3); cursor: pointer; }
.nt__filter button.is-active { background: var(--c-brand-soft); color: var(--c-brand); font-weight: 600; }
.nt__rows { max-height: 620px; overflow-y: auto; }
.msg { display: flex; gap: var(--s-sm); padding: var(--s-md) var(--s-lg); border-bottom: 1px solid var(--c-border); cursor: pointer; transition: background .15s; position: relative; }
.msg:hover { background: var(--c-surface-muted, #f7f8fa); }
.msg.is-unread { background: var(--c-brand-soft); }
.msg.is-unread:hover { background: #ffdfee; }
.msg__icon { width: 32px; height: 32px; border-radius: 50%; display: flex; align-items: center; justify-content: center; flex-shrink: 0; color: #fff; }
.msg__icon--approval { background: var(--c-brand); }
.msg__icon--customer { background: var(--c-brand-secondary); }
.msg__icon--inventory { background: var(--c-warning-fg); }
.msg__icon--marketing { background: var(--c-purple); }
.msg__icon--system { background: var(--c-text-3); }
.msg__body { flex: 1; min-width: 0; }
.msg__top { display: flex; justify-content: space-between; align-items: center; gap: var(--s-sm); }
.msg__title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.msg__content { margin: 4px 0; font-size: var(--t-xs); color: var(--c-text-2); line-height: 1.5; }
.msg__meta { display: flex; gap: var(--s-md); font-size: 10px; color: var(--c-text-3); }
.msg__link { color: var(--c-brand); }
.msg__dot { position: absolute; top: var(--s-md); right: var(--s-lg); width: 8px; height: 8px; border-radius: 50%; background: var(--c-danger-fg); }
.empty { text-align: center; color: var(--c-text-3); padding: var(--s-xl) 0; }
.empty p { margin-top: var(--s-sm); font-size: var(--t-sm); }

/* 偏好 */
.prefs__hint { margin: 0 0 var(--s-md); font-size: var(--t-xs); color: var(--c-text-3); }
.pref { padding: var(--s-sm) 0; border-bottom: 1px solid var(--c-border); }
.pref:last-child { border-bottom: none; }
.pref__head { display: flex; justify-content: space-between; align-items: center; }
.pref__label { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.pref__channels { display: flex; flex-wrap: wrap; gap: var(--s-sm); margin-top: var(--s-xs); padding-left: var(--s-xxs); }
.ch { display: inline-flex; align-items: center; gap: 4px; font-size: var(--t-xs); color: var(--c-text-2); cursor: pointer; }

@media (max-width: 900px) {
  .nt__body { grid-template-columns: 1fr; }
}
</style>
