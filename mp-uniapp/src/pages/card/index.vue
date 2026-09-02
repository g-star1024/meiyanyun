<script setup lang="ts">
/* 我的卡 pages/card/index — 会员卡余额/积分/电子小票 */
import { computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { useMemberStore } from '@/stores/member'
import { useOrderStore } from '@/stores/order'
import { navTo } from '@/utils/nav'

const points = useMemberStore()
const order = useOrderStore()

onShow(() => {
  points.seed()
  order.seed()
})

const member = computed(() => points.member)

interface MyCard {
  name: string
  total?: number
  remaining?: number
  balance?: number
  level?: string
  expire: string
}
const myCards: MyCard[] = [
  { name: '水光焕肤 10 次卡', total: 10, remaining: 6, expire: '2027-06-30' },
  { name: 'VIP 钻石会员卡', balance: 12600, level: '钻石', expire: '2027-12-31' },
  { name: '光子嫩肤 5 次卡', total: 5, remaining: 3, expire: '2026-12-31' },
]

const recentReceipts = computed(() =>
  order.orders
    .filter((o) => o.customerId === member.value.memberId && (o.status === 'PAID' || o.status === 'COMPLETED'))
    .slice(0, 5),
)

function fmt(n: number) {
  return '¥' + n.toLocaleString()
}
function fmtDate(s: string) {
  return s?.slice(0, 16).replace('T', ' ')
}
function goReceipt(id: string) {
  navTo(`/pages/receipt/detail?id=${id}`)
}
</script>

<template>
  <view class="card-page">
    <MNavbar title="我的会员卡" />
    <!-- 会员卡头部 -->
    <view class="vip-card">
      <view class="vip-card__level">{{ myCards[1].level }}会员</view>
      <view class="vip-card__name">{{ member.name }}</view>
      <view class="vip-card__no">No. {{ member.memberId }}</view>
      <view class="vip-card__balance">
        <view>
          <text class="vip-card__label">卡余额</text>
          <text class="vip-card__strong">¥{{ member.cardBalance.toLocaleString() }}</text>
        </view>
        <view>
          <text class="vip-card__label">积分</text>
          <text class="vip-card__strong">{{ member.points.toLocaleString() }}</text>
        </view>
        <view>
          <text class="vip-card__label">优惠券</text>
          <text class="vip-card__strong">{{ member.couponCount }}</text>
        </view>
      </view>
    </view>

    <!-- 我的卡项 -->
    <view class="section">
      <view class="section__h">我的卡项</view>
      <view v-for="c in myCards" :key="c.name" class="item-card">
        <view class="item-card__name">{{ c.name }}</view>
        <view v-if="c.remaining !== undefined" class="item-card__meta">
          剩余 <text class="item-card__strong">{{ c.remaining }}/{{ c.total }}</text> 次
        </view>
        <view v-else class="item-card__meta">余额 <text class="item-card__strong">{{ fmt(c.balance!) }}</text></view>
        <view class="item-card__expire">有效期至 {{ c.expire }}</view>
      </view>
    </view>

    <!-- 电子小票 -->
    <view class="section">
      <view class="section__h">电子小票</view>
      <view v-if="!recentReceipts.length" class="empty">暂无消费记录</view>
      <view
        v-for="o in recentReceipts"
        :key="o.id"
        class="receipt-row"
        @click="goReceipt(o.id)"
      >
        <view class="receipt-row__left">
          <view class="receipt-row__no">{{ o.orderNo }}</view>
          <view class="receipt-row__time">{{ fmtDate(o.createdAt) }}</view>
        </view>
        <view class="receipt-row__right">
          <view class="receipt-row__amount">{{ fmt(o.amount) }}</view>
          <view class="receipt-row__arrow">查看小票 ›</view>
        </view>
      </view>
    </view>

    <view class="bc-hint">B/C 联动：卡余额接 M6-11，积分接 M3-05，电子小票接 M4-15 收款。</view>
  </view>
</template>

<style lang="scss" scoped>
.card-page {
  padding: 0 0 64rpx;
}
.vip-card {
  margin: 0 24rpx 24rpx;
  padding: 64rpx 48rpx;
  background: linear-gradient(135deg, #2d1b4e, #5a3d8a);
  color: #fff;
  border-radius: 28rpx;
  position: relative;
  overflow: hidden;
}
.vip-card__level {
  display: inline-block;
  padding: 4rpx 20rpx;
  background: #ffcc47;
  color: #5a3d00;
  border-radius: 999rpx;
  font-size: 22rpx;
  font-weight: 600;
}
.vip-card__name {
  font-size: 32rpx;
  font-weight: 700;
  margin-top: 16rpx;
}
.vip-card__no {
  font-size: 22rpx;
  opacity: 0.7;
}
.vip-card__balance {
  display: flex;
  margin-top: 24rpx;
}
.vip-card__balance view {
  margin-right: 64rpx;
}
.vip-card__label {
  display: block;
  font-size: 22rpx;
  opacity: 0.7;
}
.vip-card__strong {
  display: block;
  font-size: 28rpx;
  font-weight: 700;
  margin-top: 4rpx;
}

.section {
  background: #fff;
  margin: 0 24rpx 24rpx;
  border-radius: 28rpx;
  padding: 24rpx;
  box-shadow: 0 2rpx 8rpx rgba(0, 0, 0, 0.04);
}
.section__h {
  margin: 0 0 16rpx;
  font-size: 28rpx;
  font-weight: 600;
  color: #1a1a1a;
}
.item-card {
  padding: 16rpx 0;
  border-bottom: 2rpx solid #f0f0f0;
}
.item-card:last-child {
  border-bottom: none;
}
.item-card__name {
  font-size: 24rpx;
  color: #1a1a1a;
  font-weight: 500;
}
.item-card__meta {
  font-size: 20rpx;
  color: #666;
  margin-top: 4rpx;
}
.item-card__strong {
  color: #ff6b9e;
}
.item-card__expire {
  font-size: 22rpx;
  color: #999;
  margin-top: 4rpx;
}

.empty {
  text-align: center;
  color: #999;
  font-size: 24rpx;
  padding: 64rpx 0;
}
.receipt-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16rpx 0;
  border-bottom: 2rpx solid #f0f0f0;
}
.receipt-row:last-child {
  border-bottom: none;
}
.receipt-row__no {
  font-size: 24rpx;
  color: #1a1a1a;
  font-weight: 500;
}
.receipt-row__time {
  font-size: 22rpx;
  color: #999;
  margin-top: 4rpx;
}
.receipt-row__right {
  text-align: right;
}
.receipt-row__amount {
  font-size: 28rpx;
  font-weight: 700;
  color: #ff6b9e;
}
.receipt-row__arrow {
  font-size: 22rpx;
  color: #999;
  margin-top: 4rpx;
}
.bc-hint {
  font-size: 22rpx;
  color: #999;
  text-align: center;
  margin: 0;
  padding: 0 48rpx;
}
</style>
