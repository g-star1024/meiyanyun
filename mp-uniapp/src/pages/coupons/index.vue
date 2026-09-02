<script setup lang="ts">
/* 优惠券 pages/coupons/index — 可领券 + 已使用/已过期示例 */
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { useCouponStore } from '@/stores/coupon'
import { toast } from '@/utils/nav'

const coupon = useCouponStore()

onShow(() => coupon.seed())

const tab = ref('available')
const tabs = [
  { label: '可使用', value: 'available' },
  { label: '已使用', value: 'used' },
  { label: '已过期', value: 'expired' },
]

const available = computed(() => coupon.coupons.filter((c) => c.status === 'ACTIVE' && coupon.stockLeft(c) > 0))
const used = ref([
  { id: 'u1', name: '新客专享 8 折券', desc: '全场项目可用', usedOn: '2026-08-20 水光焕肤' },
])
const expired = ref([
  { id: 'e1', name: '满 1000 减 200', desc: '疗程项目可用', date: '2026-07-31' },
])

function claim(c: { id: string; name: string }) {
  const r = coupon.grant(c.id, 'DESIGNATED', 'C端会员-陈美玲', 1)
  if (r.status === 'GRANTED') {
    toast(`已领取「${c.name}」！可在收银台核销使用。`, 'success')
  } else {
    toast('领取失败，库存不足或已领过。')
  }
}
function isClaimed(id: string) {
  return coupon.claimedIds.includes(id)
}
</script>

<template>
  <view class="cp">
    <MNavbar title="优惠券" />
    <view class="tabs">
      <view
        v-for="t in tabs"
        :key="t.value"
        class="tab"
        :class="{ 'tab--active': tab === t.value }"
        @click="tab = t.value"
      >{{ t.label }}</view>
    </view>

    <!-- 可使用 -->
    <view v-if="tab === 'available'" class="list">
      <view v-for="c in available" :key="c.id" class="coupon">
        <view class="coupon__left">
          <view class="coupon__amount">
            <text v-if="c.type === 'AMOUNT'">¥<text class="coupon__strong">{{ c.value }}</text></text>
            <text v-else><text class="coupon__strong">{{ c.value / 10 }}</text><text class="coupon__em">折</text></text>
          </view>
          <view class="coupon__cond">满{{ c.threshold }}可用</view>
        </view>
        <view class="coupon__mid">
          <view class="coupon__name">{{ c.name }}</view>
          <view class="coupon__date">{{ c.startDate?.slice(5) }} ~ {{ c.endDate?.slice(5) }}</view>
        </view>
        <view
          class="coupon__btn"
          :class="{ 'coupon__btn--done': isClaimed(c.id) }"
          @click="!isClaimed(c.id) && claim(c)"
        >{{ isClaimed(c.id) ? '已领取' : '领取' }}</view>
      </view>
      <view v-if="!available.length" class="empty">暂无可用优惠券</view>
    </view>

    <!-- 已使用 -->
    <view v-else-if="tab === 'used'" class="list">
      <view v-for="u in used" :key="u.id" class="coupon coupon--used">
        <view class="coupon__left">
          <view class="coupon__amount"><text class="coupon__strong">已用</text></view>
        </view>
        <view class="coupon__mid">
          <view class="coupon__name">{{ u.name }}</view>
          <view class="coupon__date">使用于：{{ u.usedOn }}</view>
        </view>
      </view>
    </view>

    <!-- 已过期 -->
    <view v-else class="list">
      <view v-for="e in expired" :key="e.id" class="coupon coupon--expired">
        <view class="coupon__left">
          <view class="coupon__amount"><text class="coupon__strong">过期</text></view>
        </view>
        <view class="coupon__mid">
          <view class="coupon__name">{{ e.name }}</view>
          <view class="coupon__date">过期于 {{ e.date }}</view>
        </view>
      </view>
    </view>

    <view class="bc-hint">优惠券由门店配置发放，下单/收银时可直接抵扣使用。</view>
  </view>
</template>

<style lang="scss" scoped>
.cp {
  padding: 24rpx;
}
.tabs {
  display: flex;
  background: #fff;
  border-radius: 20rpx;
  padding: 8rpx;
  margin-bottom: 24rpx;
}
.tab {
  flex: 1;
  padding: 16rpx;
  font-size: 24rpx;
  color: #666;
  border-radius: 12rpx;
  text-align: center;
}
.tab--active {
  background: #ff6b9e;
  color: #fff;
  font-weight: 600;
}
.list {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}
.coupon {
  display: flex;
  align-items: center;
  background: #fff;
  border-radius: 28rpx;
  overflow: hidden;
  box-shadow: 0 2rpx 8rpx rgba(0, 0, 0, 0.04);
}
.coupon__left {
  width: 180rpx;
  padding: 24rpx 16rpx;
  text-align: center;
  background: linear-gradient(135deg, #ff6b9e, #ff8fb3);
  color: #fff;
}
.coupon__amount {
  font-size: 28rpx;
}
.coupon__strong {
  font-size: 40rpx;
  font-weight: 700;
}
.coupon__em {
  font-size: 24rpx;
}
.coupon__cond {
  font-size: 20rpx;
  opacity: 0.9;
  margin-top: 4rpx;
}
.coupon__mid {
  flex: 1;
  padding: 16rpx 24rpx;
  min-width: 0;
}
.coupon__name {
  font-size: 24rpx;
  font-weight: 600;
  color: #1a1a1a;
}
.coupon__date {
  font-size: 22rpx;
  color: #999;
  margin-top: 8rpx;
}
.coupon__btn {
  margin-right: 24rpx;
  padding: 12rpx 32rpx;
  background: #ff6b9e;
  color: #fff;
  border-radius: 999rpx;
  font-size: 20rpx;
  font-weight: 600;
}
.coupon__btn--done {
  background: #ccc;
}
.coupon--used .coupon__left,
.coupon--expired .coupon__left {
  background: #999;
}
.empty {
  text-align: center;
  color: #999;
  font-size: 24rpx;
  padding: 128rpx 0;
}
.bc-hint {
  font-size: 22rpx;
  color: #999;
  text-align: center;
  margin-top: 24rpx;
}
</style>
