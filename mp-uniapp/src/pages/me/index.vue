<script setup lang="ts">
/* 我的 pages/me/index（tab）— 会员卡 + 资产 + 订单/服务入口聚合 */
import { computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { useMemberStore } from '@/stores/member'
import { useAppointmentStore } from '@/stores/appointment'
import { navTo } from '@/utils/nav'

const points = useMemberStore()
const appt = useAppointmentStore()

onShow(() => {
  points.seed()
  appt.seed()
})

const member = computed(() => points.member)
const upcomingCount = computed(
  () => appt.appointments.filter((a) => a.customerId === member.value.memberId && (a.status === 'NEW' || a.status === 'CONFIRMED')).length,
)

// 订单状态入口
const orderEntries = [
  { label: '待付款', icon: 'wallet', to: '/pages/orders/list' },
  { label: '待核销', icon: 'medal', to: '/pages/orders/list' },
  { label: '已完成', icon: 'checkmarkempty', to: '/pages/orders/list' },
  { label: '退款/售后', icon: 'undo', to: '/pages/orders/list' },
]
// 资产入口
const assetEntries = computed(() => [
  { label: '会员卡', value: '¥' + member.value.cardBalance.toLocaleString(), to: '/pages/card/index' },
  { label: '优惠券', value: member.value.couponCount + ' 张', to: '/pages/coupons/index' },
  { label: '积分', value: member.value.points.toLocaleString(), to: '/pages/points-mall/index' },
  { label: '套餐疗程', value: '3 个', to: '/pages/packages/index' },
])
// 服务入口
const serviceEntries = [
  { label: '我的预约', icon: 'calendar', to: '/pages/booking/list' },
  { label: '消费记录', icon: 'list', to: '/pages/records/index' },
  { label: '术后回访', icon: 'compose', to: '/pages/followup/index' },
  { label: '专属顾问', icon: 'headphones', to: '/pages/advisor/index' },
  { label: '消息中心', icon: 'notification', to: '/pages/notifications/index' },
  { label: '邀请有礼', icon: 'gift', to: '/pages/invite/index' },
  { label: '设置', icon: 'gear', to: '/pages/settings/index' },
  { label: '帮助中心', icon: 'help', to: '/pages/settings/index' },
]
function go(to: string) {
  navTo(to)
}
</script>

<template>
  <view class="me">
    <!-- 自定义导航栏：会员头为粉色渐变，状态栏用同款渐变占位 -->
    <MNavbar placeholder-only bg="linear-gradient(160deg,#ffbff0 0%,#ff6b9e 100%)" />
    <!-- 会员头部 -->
    <view class="profile">
      <view class="profile__top">
        <view class="profile__avatar">{{ member.name.charAt(0) }}</view>
        <view class="profile__info">
          <view class="profile__name">
            <text>{{ member.name }}</text>
            <text class="profile__badge">黑金会员</text>
          </view>
          <view class="profile__phone">{{ member.phone }}</view>
        </view>
        <view class="profile__set" @click="go('/pages/settings/index')">
          <uni-icons type="gear" size="22" color="#fff" />
        </view>
      </view>
      <!-- 会员卡 -->
      <view class="vipcard" @click="go('/pages/card/index')">
        <view class="vipcard__row">
          <text class="vipcard__label">卡余额</text>
          <text class="vipcard__value">¥{{ member.cardBalance.toLocaleString() }}</text>
        </view>
        <view class="vipcard__row vipcard__row--sub">
          <text>积分 <text class="vipcard__b">{{ member.points.toLocaleString() }}</text></text>
          <text>优惠券 <text class="vipcard__b">{{ member.couponCount }}</text> 张</text>
          <text class="vipcard__more">我的卡 ›</text>
        </view>
      </view>
    </view>

    <!-- 我的订单 -->
    <view class="card block block--up">
      <view class="block__head">
        <text class="block__title">我的订单</text>
        <text class="block__more" @click="go('/pages/orders/list')">全部订单 ›</text>
      </view>
      <view class="order-grid">
        <view v-for="o in orderEntries" :key="o.label" class="order-item" @click="go(o.to)">
          <view class="order-item__icon"><uni-icons :type="o.icon" size="24" color="#ff6b9e" /></view>
          <text class="order-item__label">{{ o.label }}</text>
        </view>
      </view>
    </view>

    <!-- 我的资产 -->
    <view class="card block">
      <view class="block__head"><text class="block__title">我的资产</text></view>
      <view class="asset-grid">
        <view v-for="a in assetEntries" :key="a.label" class="asset-item" @click="go(a.to)">
          <text class="asset-item__value">{{ a.value }}</text>
          <text class="asset-item__label">{{ a.label }}</text>
        </view>
      </view>
    </view>

    <!-- 待办提醒 -->
    <view v-if="upcomingCount" class="notice card" @click="go('/pages/booking/list')">
      <view class="notice__icon"><uni-icons type="notification" size="18" color="#fa8c16" /></view>
      <text class="notice__text">您有 <text class="notice__b">{{ upcomingCount }}</text> 个预约待确认/即将到店</text>
      <text class="notice__go">›</text>
    </view>

    <!-- 常用服务 -->
    <view class="card block">
      <view class="block__head"><text class="block__title">常用服务</text></view>
      <view class="svc-grid">
        <view v-for="s in serviceEntries" :key="s.label" class="svc-item" @click="go(s.to)">
          <view class="svc-item__icon"><uni-icons :type="s.icon" size="24" color="#ff6b9e" /></view>
          <text class="svc-item__label">{{ s.label }}</text>
        </view>
      </view>
    </view>
  </view>
</template>

<style lang="scss" scoped>
.me {
  padding-bottom: 48rpx;
}
.card {
  background: #fff;
  border-radius: 28rpx;
}
.profile {
  background: linear-gradient(160deg, #ffbff0 0%, #ff6b9e 100%);
  padding: 40rpx 32rpx 120rpx;
}
.profile__top {
  display: flex;
  align-items: center;
}
.profile__avatar {
  width: 116rpx;
  height: 116rpx;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.3);
  border: 4rpx solid rgba(255, 255, 255, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 48rpx;
  font-weight: 700;
  color: #fff;
}
.profile__info {
  flex: 1;
  margin-left: 24rpx;
}
.profile__name {
  font-size: 38rpx;
  font-weight: 700;
  color: #fff;
}
.profile__badge {
  font-size: 20rpx;
  color: #8a5a00;
  background: linear-gradient(135deg, #fff3d0, #ffe08a);
  padding: 4rpx 14rpx;
  border-radius: 16rpx;
  margin-left: 12rpx;
}
.profile__phone {
  font-size: 26rpx;
  color: rgba(255, 255, 255, 0.9);
  margin-top: 8rpx;
}
.profile__set {
  padding: 8rpx;
  display: flex;
  align-items: center;
}
.vipcard {
  background: rgba(255, 255, 255, 0.18);
  border: 2rpx solid rgba(255, 255, 255, 0.25);
  border-radius: 28rpx;
  padding: 28rpx 32rpx;
  margin-top: 32rpx;
}
.vipcard__row {
  display: flex;
  align-items: baseline;
}
.vipcard__label {
  font-size: 24rpx;
  color: rgba(255, 255, 255, 0.9);
  margin-right: 20rpx;
}
.vipcard__value {
  font-size: 52rpx;
  font-weight: 800;
  color: #fff;
}
.vipcard__row--sub {
  display: flex;
  align-items: center;
  margin-top: 20rpx;
  font-size: 24rpx;
  color: rgba(255, 255, 255, 0.92);
}
.vipcard__row--sub text {
  margin-right: 36rpx;
}
.vipcard__b {
  color: #fff;
  font-weight: 700;
}
.vipcard__more {
  margin-left: auto;
  margin-right: 0;
  font-size: 24rpx;
}

.block {
  margin: 0 24rpx 24rpx;
  padding: 32rpx;
  position: relative;
}
.block--up {
  margin-top: -88rpx;
}
.block__head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 32rpx;
}
.block__title {
  font-size: 32rpx;
  font-weight: 700;
  color: #1a1a1a;
}
.block__more {
  font-size: 24rpx;
  color: #999;
}

.order-grid,
.svc-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  row-gap: 28rpx;
  column-gap: 8rpx;
}
.order-item,
.svc-item,
.asset-item {
  display: flex;
  flex-direction: column;
  align-items: center;
}
.order-item {
  gap: 12rpx;
}
.order-item__icon,
.svc-item__icon {
  width: 88rpx;
  height: 88rpx;
  border-radius: 28rpx;
  background: #fff0f5;
  display: flex;
  align-items: center;
  justify-content: center;
}
.order-item__label,
.svc-item__label {
  font-size: 24rpx;
  color: #555;
}

.asset-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
}
.asset-item {
  gap: 6rpx;
}
.asset-item__value {
  font-size: 32rpx;
  font-weight: 700;
  color: #ff4d6d;
}
.asset-item__label {
  font-size: 24rpx;
  color: #888;
}

.notice {
  margin: 0 24rpx 24rpx;
  padding: 28rpx;
  display: flex;
  align-items: center;
}
.notice__icon {
  width: 56rpx;
  height: 56rpx;
  border-radius: 50%;
  background: #fff5e6;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-right: 20rpx;
  flex-shrink: 0;
}
.notice__text {
  flex: 1;
  font-size: 26rpx;
  color: #555;
}
.notice__b {
  color: #ff4d6d;
}
.notice__go {
  font-size: 36rpx;
  color: #ccc;
}

.svc-grid {
  row-gap: 36rpx;
}
.svc-item {
  gap: 10rpx;
}
</style>
