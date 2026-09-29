<script setup lang="ts">
/* C 端消息通知 pages/notifications/index — C-B6 切真：GET /c/notifications（notice store，V77 行级隔离 LIMIT 50 倒序）；已读/全读接 PUT 写路径 */
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { useNoticeStore, type Notif } from '@/stores/notice'
import { navTo } from '@/utils/nav'

const notice = useNoticeStore()
onShow(() => notice.seed())
const tab = ref<'all' | 'appt' | 'promo'>('all')

const filtered = computed(() => {
  if (tab.value === 'all') return notice.notifs
  return notice.notifs.filter((n) => n.type === tab.value)
})

const unreadCount = computed(() => notice.notifs.filter((n) => !n.read).length)

function iconOf(type: Notif['type']) {
  return type === 'appt' ? 'calendar' : type === 'promo' ? 'gift' : 'notification'
}
function iconColorOf(type: Notif['type']) {
  return type === 'appt' ? '#1677ff' : type === 'promo' ? '#ff6b9e' : '#999'
}

function open(n: Notif) {
  notice.markRead(n)
  if (n.to) navTo(n.to)
}

function markAllRead() {
  notice.markAllRead()
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
      <view v-if="!filtered.length" class="notif-empty">
        <view class="notif-empty__icon"><uni-icons type="notification" size="36" color="#ddd" /></view>
        <view class="notif-empty__t">暂无消息</view>
        <view class="notif-empty__d">预约提醒与优惠活动将在此通知您</view>
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
.notif-empty { text-align: center; padding: 96rpx 32rpx; }
.notif-empty__icon {
  width: 128rpx; height: 128rpx; border-radius: 50%; background: #fff;
  display: inline-flex; align-items: center; justify-content: center;
}
.notif-empty__t { font-size: 28rpx; font-weight: 600; color: #666; margin-top: 24rpx; }
.notif-empty__d { font-size: 24rpx; color: #999; margin-top: 12rpx; }
</style>
