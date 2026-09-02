<script setup lang="ts">
/* C 端消息通知 /m/notifications — 预约提醒/回访通知/优惠通知 */
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import CIcon from '@/components/CIcon.vue'

const router = useRouter()
const tab = ref<'all' | 'appt' | 'promo'>('all')

interface Notif {
  id: string
  type: 'appt' | 'promo' | 'system'
  title: string
  body: string
  time: string
  read: boolean
  to?: string
}

const notifs = ref<Notif[]>([
  { id: 'n1', type: 'appt', title: '预约提醒', body: '您预约的「水光焕肤」将于明日 14:00 到店，请准时到达。', time: '今天 10:20', read: false, to: '/m/booking' },
  { id: 'n2', type: 'promo', title: '专属优惠券到账', body: '满 500 减 100 优惠券已到账，有效期 7 天，快去使用吧~', time: '昨天 16:45', read: false, to: '/m/coupons' },
  { id: 'n3', type: 'appt', title: '回访邀请', body: '您的「光子嫩肤」已到恢复期，点击填写回访反馈，帮助我们更好服务。', time: '2 天前', read: true, to: '/m/followup' },
  { id: 'n4', type: 'promo', title: '积分即将过期', body: '您有 200 积分将于月底过期，快去积分商城兑换心仪好礼！', time: '3 天前', read: true, to: '/m/points-mall' },
  { id: 'n5', type: 'system', title: '电子小票已生成', body: '您 8 月 22 日的消费小票已生成，点击查看详情。', time: '4 天前', read: true, to: '/m/card' },
  { id: 'n6', type: 'appt', title: '预约确认', body: '您的预约已确认，订单号 APT20260820005，请按时到店。', time: '6 天前', read: true, to: '/m/booking' },
])

const filtered = computed(() => {
  if (tab.value === 'all') return notifs.value
  return notifs.value.filter((n) => n.type === tab.value)
})

const unreadCount = computed(() => notifs.value.filter((n) => !n.read).length)

function iconOf(n: Notif) {
  return n.type === 'appt' ? 'calendar' : n.type === 'promo' ? 'gift' : 'bell'
}

function open(n: Notif) {
  n.read = true
  if (n.to) router.push(n.to)
}

function markAllRead() {
  notifs.value.forEach((n) => (n.read = true))
}
</script>

<template>
  <div class="notif">
    <div class="notif__header">
      <h3>消息中心</h3>
      <button v-if="unreadCount > 0" class="notif__read-all" @click="markAllRead">全部已读</button>
    </div>

    <div class="notif__tabs">
      <button :class="{ active: tab === 'all' }" @click="tab = 'all'">
        全部<span v-if="unreadCount > 0" class="badge">{{ unreadCount }}</span>
      </button>
      <button :class="{ active: tab === 'appt' }" @click="tab = 'appt'">预约/服务</button>
      <button :class="{ active: tab === 'promo' }" @click="tab = 'promo'">优惠活动</button>
    </div>

    <div class="notif__list">
      <div
        v-for="n in filtered"
        :key="n.id"
        class="notif-item"
        :class="{ unread: !n.read }"
        @click="open(n)"
      >
        <div class="notif-item__icon" :class="n.type">
          <CIcon :name="iconOf(n)" :size="20" />
        </div>
        <div class="notif-item__body">
          <div class="notif-item__head">
            <span class="notif-item__title">{{ n.title }}</span>
            <span class="notif-item__time">{{ n.time }}</span>
          </div>
          <div class="notif-item__text">{{ n.body }}</div>
        </div>
        <div v-if="!n.read" class="notif-item__dot"></div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.notif { min-height: 100vh; background: var(--c-bg-page); padding-bottom: 24px; }
.notif__header {
  display: flex; align-items: center; justify-content: space-between;
  padding: var(--s-md) var(--s-lg); background: var(--c-bg-card);
}
.notif__header h3 { margin: 0; font-size: var(--t-lg); }
.notif__read-all { border: none; background: none; color: var(--c-brand); font-size: var(--t-sm); cursor: pointer; }
.notif__tabs {
  display: flex; background: var(--c-bg-card);
  border-bottom: 1px solid var(--c-border); padding: 0 var(--s-md);
}
.notif__tabs button {
  flex: none; border: none; background: none; padding: 10px 16px;
  font-size: var(--t-sm); color: var(--c-text-2); cursor: pointer;
  position: relative; display: flex; align-items: center; gap: 4px;
}
.notif__tabs button.active { color: var(--c-brand); font-weight: 600; }
.notif__tabs button.active::after {
  content: ''; position: absolute; bottom: 0; left: 16px; right: 16px;
  height: 2px; background: var(--c-brand); border-radius: 1px;
}
.badge {
  background: var(--c-brand); color: #fff; font-size: 10px;
  padding: 0 5px; border-radius: var(--r-full); min-width: 16px; text-align: center;
}
.notif__list { padding: var(--s-sm) var(--s-md); display: flex; flex-direction: column; gap: var(--s-sm); }
.notif-item {
  display: flex; gap: var(--s-md); padding: var(--s-md);
  background: var(--c-bg-card); border-radius: var(--r-md); cursor: pointer;
  position: relative;
}
.notif-item.unread { background: var(--c-brand-soft); }
.notif-item__icon {
  width: 40px; height: 40px; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
}
.notif-item__icon.appt { background: #e6f4ff; color: #1677ff; }
.notif-item__icon.promo { background: var(--c-brand-soft); color: #ff6b9e; }
.notif-item__icon.system { background: var(--c-bg-page); color: #999; }
.notif-item__body { flex: 1; min-width: 0; }
.notif-item__head { display: flex; justify-content: space-between; align-items: center; }
.notif-item__title { font-size: var(--t-sm); font-weight: 600; color: var(--c-text-1); }
.notif-item__time { font-size: var(--t-xs); color: var(--c-text-3); flex-shrink: 0; margin-left: 8px; }
.notif-item__text {
  font-size: var(--t-sm); color: var(--c-text-2); margin-top: 4px;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}
.notif-item__dot {
  position: absolute; top: var(--s-md); right: var(--s-md);
  width: 8px; height: 8px; border-radius: 50%; background: var(--c-brand);
}
</style>
