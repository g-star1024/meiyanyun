<script setup lang="ts">
/* 首页 pages/home/index — 轮播 + 快捷入口 + 推荐项目 + 最近预约 */
import { computed, ref } from 'vue'
import { onShow, onPullDownRefresh } from '@dcloudio/uni-app'
import { useMemberStore } from '@/stores/member'
import { useAppointmentStore } from '@/stores/appointment'
import { usePricelistStore } from '@/stores/pricelist'
import { navTo } from '@/utils/nav'

const points = useMemberStore()
const appt = useAppointmentStore()
const pricelist = usePricelistStore()

onShow(() => {
  points.seed()
  appt.seed()
  pricelist.seed()
})
onPullDownRefresh(() => {
  setTimeout(() => uni.stopPullDownRefresh(), 500)
})

const member = computed(() => points.member)

const banners = [
  { title: '新人专享 · 皮肤检测免费领', sub: '到店即送 VISIA 深度检测 1 次', bg: 'linear-gradient(135deg,#FFBFF0,#FF6B9E)' },
  { title: '热玛吉紧致疗程', sub: '3 次疗程立省 ¥17,600', bg: 'linear-gradient(135deg,#ffd1e3,#ff9ebc)' },
  { title: '闺蜜同行 次卡共享', sub: '10 次水光 2 人拼团 ¥3,980', bg: 'linear-gradient(135deg,#e0d4ff,#b7a6ff)' },
]
const bannerIdx = ref(0)

const quicks: { label: string; icon: string; to: string }[] = [
  { label: '在线预约', icon: 'calendar', to: '/pages/booking/list' },
  { label: '全部项目', icon: 'staff', to: '/pages/projects/list' },
  { label: '门店', icon: 'shop', to: '/pages/stores/list' },
  { label: '我的订单', icon: 'list', to: '/pages/orders/list' },
  { label: '优惠券', icon: 'medal', to: '/pages/coupons/index' },
  { label: '积分商城', icon: 'gift', to: '/pages/points-mall/index' },
  { label: '专属顾问', icon: 'headphones', to: '/pages/advisor/index' },
  { label: '邀请有礼', icon: 'link', to: '/pages/invite/index' },
]

const recommends = computed(() =>
  pricelist.active
    .slice()
    .sort((a, b) => (b.promoPrice ? 1 : 0) - (a.promoPrice ? 1 : 0))
    .slice(0, 6),
)
function priceOf(p: { promoPrice: number | null; memberPrice: number }) {
  return p.promoPrice ?? p.memberPrice
}

const upcoming = computed(() =>
  appt.appointments
    .filter((a) => a.customerId === member.value.memberId && (a.status === 'CONFIRMED' || a.status === 'NEW'))
    .slice(0, 1),
)

function go(to: string) {
  navTo(to)
}
function goProject(id: string) {
  navTo(`/pages/projects/detail?id=${id}`)
}
</script>

<template>
  <view class="home">
    <!-- 自定义导航栏：首页仅状态栏占位（头部用会员条） -->
    <MNavbar placeholder-only bg="#ffffff" />
    <!-- 顶部会员条 -->
    <view class="memberbar">
      <view class="memberbar__user" @click="go('/pages/me/index')">
        <view class="memberbar__avatar">{{ member.name.charAt(0) }}</view>
        <view class="memberbar__info">
          <view class="memberbar__name">
            <text>{{ member.name }}</text>
            <text class="memberbar__level">黑金会员</text>
          </view>
          <view class="memberbar__sub">愿你今天也美丽</view>
        </view>
      </view>
      <view class="memberbar__bell" @click="go('/pages/notifications/index')">
        <uni-icons type="notification" size="22" color="#333" />
        <view class="memberbar__dot"></view>
      </view>
    </view>

    <!-- 轮播 -->
    <view class="banner-wrap">
      <view class="banner" :style="{ background: banners[bannerIdx].bg }">
        <view class="banner__title">{{ banners[bannerIdx].title }}</view>
        <view class="banner__sub">{{ banners[bannerIdx].sub }}</view>
      </view>
      <view class="banner__dots">
        <view
          v-for="(_, i) in banners"
          :key="i"
          class="banner__dot"
          :class="{ on: i === bannerIdx }"
          @click="bannerIdx = i"
        ></view>
      </view>
    </view>

    <!-- 快捷入口 -->
    <view class="quick card">
      <view v-for="q in quicks" :key="q.label" class="quick__item" @click="go(q.to)">
        <view class="quick__icon"><uni-icons :type="q.icon" size="24" color="#ff6b9e" /></view>
        <text class="quick__label">{{ q.label }}</text>
      </view>
    </view>

    <!-- 最近预约 -->
    <view v-if="upcoming.length" class="appt-card card" @click="go('/pages/booking/list')">
      <view class="appt-card__tag">即将到店</view>
      <view class="appt-card__body">
        <view class="appt-card__title">{{ upcoming[0].project || '到店服务' }}</view>
        <view class="appt-card__meta"><uni-icons type="calendar" size="13" color="#888" /> <text>{{ upcoming[0].timeSlot?.slice(5, 16).replace('T', ' ') || '今日' }} · 上海静安旗舰店</text></view>
      </view>
      <text class="appt-card__go">详情 ›</text>
    </view>

    <!-- 推荐项目 -->
    <view class="sec-head">
      <view class="sec-head__title"><uni-icons type="fire" size="16" color="#ff6b9e" /><text>热门推荐</text></view>
      <text class="sec-head__more" @click="go('/pages/projects/list')">全部项目 ›</text>
    </view>
    <view class="proj-list">
      <view v-for="p in recommends" :key="p.id" class="proj card" @click="goProject(p.id)">
        <view class="proj__img">{{ p.name.charAt(0) }}</view>
        <view class="proj__body">
          <view class="proj__name">{{ p.name }}</view>
          <view class="proj__meta">{{ p.duration }}分钟 · {{ p.unit }}</view>
          <view class="proj__price">
            <text class="proj__now">¥{{ priceOf(p).toLocaleString() }}</text>
            <text v-if="p.promoPrice" class="proj__old">¥{{ p.originalPrice.toLocaleString() }}</text>
            <text v-if="p.promoPrice" class="proj__tag">限时</text>
          </view>
        </view>
      </view>
    </view>

    <!-- 门店入口 -->
    <view class="store-card card" @click="go('/pages/stores/list')">
      <view class="store-card__icon"><uni-icons type="shop" size="24" color="#ff6b9e" /></view>
      <view class="store-card__body">
        <view class="store-card__name">附近门店</view>
        <view class="store-card__sub">上海静安旗舰店 · 距您 1.2km</view>
      </view>
      <uni-icons type="arrow-right" size="16" color="#ccc" />
    </view>
  </view>
</template>

<style lang="scss" scoped>
.home {
  padding: 0 0 48rpx;
}
.card {
  background: #fff;
  border-radius: 28rpx;
}
.memberbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 28rpx 32rpx 24rpx;
  background: #fff;
}
.memberbar__user {
  display: flex;
  align-items: center;
}
.memberbar__avatar {
  width: 84rpx;
  height: 84rpx;
  border-radius: 50%;
  background: linear-gradient(135deg, #ffbff0, #ff6b9e);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 34rpx;
  font-weight: 700;
}
.memberbar__info {
  margin-left: 20rpx;
}
.memberbar__name {
  font-size: 32rpx;
  font-weight: 700;
  color: #1a1a1a;
}
.memberbar__level {
  font-size: 20rpx;
  color: #b8860b;
  background: #fff6e0;
  padding: 2rpx 12rpx;
  border-radius: 12rpx;
  margin-left: 8rpx;
}
.memberbar__sub {
  font-size: 24rpx;
  color: #999;
  margin-top: 4rpx;
}
.memberbar__bell {
  position: relative;
  padding: 8rpx;
}
.bell-ic {
  font-size: 40rpx;
}
.memberbar__dot {
  position: absolute;
  top: 2rpx;
  right: 2rpx;
  width: 16rpx;
  height: 16rpx;
  background: #ff4d4f;
  border: 3rpx solid #fff;
  border-radius: 50%;
}
.banner-wrap {
  padding: 16rpx 32rpx 0;
}
.banner {
  height: 240rpx;
  border-radius: 28rpx;
  padding: 44rpx 40rpx;
  color: #fff;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  justify-content: center;
}
.banner__title {
  font-size: 38rpx;
  font-weight: 700;
}
.banner__sub {
  font-size: 26rpx;
  margin-top: 12rpx;
  opacity: 0.95;
}
.banner__dots {
  display: flex;
  justify-content: center;
  margin-top: 16rpx;
}
.banner__dot {
  width: 12rpx;
  height: 12rpx;
  border-radius: 50%;
  background: #d8d8d8;
  margin: 0 5rpx;
}
.banner__dot.on {
  width: 32rpx;
  border-radius: 6rpx;
  background: #ff6b9e;
}
.quick {
  margin: 28rpx 32rpx;
  padding: 32rpx 16rpx;
  display: grid;
  grid-template-columns: repeat(4, 1fr);
}
.quick__item {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 12rpx 0;
}
.quick__icon {
  width: 88rpx;
  height: 88rpx;
  border-radius: 28rpx;
  background: #fff0f5;
  display: flex;
  align-items: center;
  justify-content: center;
}
.quick__label {
  font-size: 24rpx;
  color: #555;
  margin-top: 12rpx;
}
.appt-card {
  margin: 0 32rpx 28rpx;
  padding: 28rpx;
  display: flex;
  align-items: center;
  position: relative;
}
.appt-card__tag {
  position: absolute;
  top: 0;
  left: 28rpx;
  transform: translateY(-50%);
  font-size: 20rpx;
  color: #fff;
  background: #ff6b9e;
  padding: 4rpx 16rpx;
  border-radius: 16rpx;
}
.appt-card__body {
  flex: 1;
  margin-top: 8rpx;
}
.appt-card__title {
  font-size: 30rpx;
  font-weight: 600;
  color: #1a1a1a;
}
.appt-card__meta {
  font-size: 24rpx;
  color: #888;
  margin-top: 8rpx;
}
.appt-card__go {
  font-size: 26rpx;
  color: #ff6b9e;
}
.sec-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8rpx 32rpx 20rpx;
}
.sec-head__title {
  font-size: 32rpx;
  font-weight: 700;
  color: #1a1a1a;
}
.sec-head__more {
  font-size: 24rpx;
  color: #999;
}
.proj-list {
  padding: 0 32rpx;
  display: flex;
  flex-direction: column;
}
.proj {
  display: flex;
  padding: 24rpx;
  margin-bottom: 20rpx;
}
.proj__img {
  width: 152rpx;
  height: 152rpx;
  border-radius: 20rpx;
  flex-shrink: 0;
  background: linear-gradient(135deg, #fff0f5, #ffe0ec);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 60rpx;
  color: #ff6b9e;
}
.proj__body {
  flex: 1;
  min-width: 0;
  margin-left: 24rpx;
  display: flex;
  flex-direction: column;
}
.proj__name {
  font-size: 30rpx;
  font-weight: 600;
  color: #1a1a1a;
}
.proj__meta {
  font-size: 24rpx;
  color: #999;
  margin-top: 8rpx;
}
.proj__price {
  margin-top: auto;
  display: flex;
  align-items: baseline;
}
.proj__now {
  font-size: 34rpx;
  font-weight: 700;
  color: #ff4d6d;
}
.proj__old {
  font-size: 24rpx;
  color: #bbb;
  text-decoration: line-through;
  margin-left: 12rpx;
}
.proj__tag {
  font-size: 20rpx;
  color: #ff4d6d;
  background: #fff0f3;
  padding: 2rpx 10rpx;
  border-radius: 8rpx;
  margin-left: 12rpx;
}
.store-card {
  margin: 8rpx 32rpx 0;
  padding: 28rpx;
  display: flex;
  align-items: center;
}
.store-card__icon {
  font-size: 52rpx;
}
.store-card__body {
  flex: 1;
  margin-left: 24rpx;
}
.store-card__name {
  font-size: 30rpx;
  font-weight: 600;
  color: #1a1a1a;
}
.store-card__sub {
  font-size: 24rpx;
  color: #888;
  margin-top: 6rpx;
}
.store-card__go {
  font-size: 40rpx;
  color: #ccc;
}
</style>
