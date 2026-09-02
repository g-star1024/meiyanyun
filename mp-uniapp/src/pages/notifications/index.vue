<script setup lang="ts">
/* C 端消息通知 pages/notifications/index — 预约提醒/回访通知/优惠通知 */
import { ref, computed } from 'vue'
import { navTo } from '@/utils/nav'

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
  { id: 'n1', type: 'appt', title: '预约提醒', body: '您预约的「水光焕肤」将于明日 14:00 到店，请准时到达。', time: '今天 10:20', read: false, to: '/pages/booking/list' },
  { id: 'n2', type: 'promo', title: '专属优惠券到账', body: '满 500 减 100 优惠券已到账，有效期 7 天，快去使用吧~', time: '昨天 16:45', read: false, to: '/pages/coupons/index' },
  { id: 'n3', type: 'appt', title: '回访邀请', body: '您的「光子嫩肤」已到恢复期，点击填写回访反馈，帮助我们更好服务。', time: '2 天前', read: true, to: '/pages/followup/index' },
  { id: 'n4', type: 'promo', title: '积分即将过期', body: '您有 200 积分将于月底过期，快去积分商城兑换心仪好礼！', time: '3 天前', read: true, to: '/pages/points-mall/index' },
  { id: 'n5', type: 'system', title: '电子小票已生成', body: '您 8 月 22 日的消费小票已生成，点击查看详情。', time: '4 天前', read: true, to: '/pages/card/index' },
  { id: 'n6', type: 'appt', title: '预约确认', body: '您的预约已确认，订单号 APT20260820005，请按时到店。', time: '6 天前', read: true, to: '/pages/booking/list' },
])

const filtered = computed(() => {
  if (tab.value === 'all') return notifs.value
  return notifs.value.filter((n) => n.type === tab.value)
})

const unreadCount = computed(() => notifs.value.filter((n) => !n.read).length)

function iconOf(type: Notif['type']) {
  return type === 'appt' ? 'calendar' : type === 'promo' ? 'gift' : 'notification'
}
function iconColorOf(type: Notif['type']) {
  return type === 'appt' ? '#1677ff' : type === 'promo' ? '#ff6b9e' : '#999'
}

function open(n: Notif) {
  n.read = true
  if (n.to) navTo(n.to)
}

function markAllRead() {
  notifs.value.forEach((n) => (n.read = true))
}
</script>

<template>
  <view class="notif">
    <MNavbar title="消息中心" />
    <view v-if="unreadCount > 0" class="notif__header">
      <view class="notif__read-all" @click="markAllRead">全部已读</view>
    </view>

    <view class="notif__tabs">
      <view class="notif__tab" :class="{ active: tab === 'all' }" @click="tab = 'all'">
        <text>全部</text><text v-if="unreadCount > 0" class="badge">{{ unreadCount }}</text>
      </view>
      <view class="notif__tab" :class="{ active: tab === 'appt' }" @click="tab = 'appt'">
        <text>预约/服务</text>
      </view>
      <view class="notif__tab" :class="{ active: tab === 'promo' }" @click="tab = 'promo'">
        <text>优惠活动</text>
      </view>
    </view>

    <view class="notif__list">
      <view
        v-for="n in filtered"
        :key="n.id"
        class="notif-item"
        :class="{ unread: !n.read }"
        @click="open(n)"
      >
        <view class="notif-item__icon" :class="n.type">
          <uni-icons :type="iconOf(n.type)" size="22" :color="iconColorOf(n.type)" />
        </view>
        <view class="notif-item__body">
          <view class="notif-item__head">
            <text class="notif-item__title">{{ n.title }}</text>
            <text class="notif-item__time">{{ n.time }}</text>
          </view>
          <view class="notif-item__text">{{ n.body }}</view>
        </view>
        <view v-if="!n.read" class="notif-item__dot"></view>
      </view>
    </view>
  </view>
</template>

<style lang="scss" scoped>
.notif { min-height: 100vh; background: #f6f6f8; padding-bottom: 48rpx; }
.notif__header {
  display: flex; align-items: center; justify-content: flex-end;
  padding: 20rpx 32rpx; background: #fff;
}
.notif__read-all { color: #ff6b9e; font-size: 24rpx; }
.notif__tabs {
  display: flex; background: #fff;
  border-bottom: 1rpx solid #eee; padding: 0 24rpx;
}
.notif__tab {
  padding: 20rpx 32rpx;
  font-size: 24rpx; color: #666;
  position: relative; display: flex; align-items: center;
}
.notif__tab.active { color: #ff6b9e; font-weight: 600; }
.notif__tab.active::after {
  content: ''; position: absolute; bottom: 0; left: 32rpx; right: 32rpx;
  height: 4rpx; background: #ff6b9e; border-radius: 2rpx;
}
.badge {
  background: #ff6b9e; color: #fff; font-size: 20rpx;
  padding: 0 10rpx; border-radius: 999rpx; min-width: 32rpx; text-align: center; margin-left: 8rpx;
}
.notif__list { padding: 16rpx 24rpx; display: flex; flex-direction: column; }
.notif-item {
  display: flex; padding: 24rpx;
  background: #fff; border-radius: 20rpx;
  position: relative; margin-bottom: 16rpx;
}
.notif-item.unread { background: #fff0f5; }
.notif-item__icon {
  width: 80rpx; height: 80rpx; border-radius: 50%;
  display: flex; align-items: center; justify-content: center; font-size: 40rpx;
  flex-shrink: 0; margin-right: 24rpx;
}
.notif-item__icon.appt { background: #e6f4ff; }
.notif-item__icon.promo { background: #fff0f5; }
.notif-item__icon.system { background: #f6f6f8; }
.notif-item__body { flex: 1; min-width: 0; }
.notif-item__head { display: flex; justify-content: space-between; align-items: center; }
.notif-item__title { font-size: 24rpx; font-weight: 600; color: #1a1a1a; }
.notif-item__time { font-size: 20rpx; color: #999; flex-shrink: 0; margin-left: 16rpx; }
.notif-item__text {
  font-size: 24rpx; color: #666; margin-top: 8rpx;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}
.notif-item__dot {
  position: absolute; top: 24rpx; right: 24rpx;
  width: 16rpx; height: 16rpx; border-radius: 50%; background: #ff6b9e;
}
</style>
