<script setup lang="ts">
/* 积分商城 pages/points-mall/index — 积分兑换商品 + 我的兑换记录 */
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { useMemberStore, type PointsProduct } from '@/stores/member'
import { toast } from '@/utils/nav'

const points = useMemberStore()

onShow(() => points.seed())

const member = computed(() => points.member)
const onSaleProducts = computed(() => points.onSaleProducts)
const myRedemptions = computed(() => points.myRedemptions)
const tab = ref<'mall' | 'orders'>('mall')

function redeem(product: PointsProduct) {
  if (member.value.points < product.pointsCost) {
    toast('积分不足，继续消费可累积积分哦~')
    return
  }
  uni.showModal({
    title: '确认兑换',
    content: `确认使用 ${product.pointsCost} 积分兑换「${product.name}」？\n兑换申请将提交至门店审核队列。`,
    confirmText: '确认兑换',
    success: (res) => {
      if (!res.confirm) return
      const r = points.redeem(product.id, 1)
      if (r.ok) {
        toast(`兑换申请已提交！审核通过后将通知您。剩余积分 ${member.value.points}`, 'success')
      } else {
        toast('兑换失败：' + (r.reason || '未知原因'))
      }
    },
  })
}
function tagOf(p: PointsProduct) {
  if (p.category === 'SERVICE') return '服务'
  if (p.category === 'COUPON') return '券'
  if (p.category === 'PROJECT') return '项目'
  return '实物'
}
function statusText(s: string) {
  return s === 'PENDING' ? '待审核' : s === 'APPROVED' ? '已通过' : s === 'FULFILLED' ? '已发放' : '已驳回'
}
</script>

<template>
  <view class="mall">
    <MNavbar title="积分商城" />
    <view class="mall__header">
      <view class="mall__points">
        <text class="mall__points-label">我的积分</text>
        <text class="mall__points-value">{{ member.points.toLocaleString() }}</text>
      </view>
      <view class="mall__tabs">
        <view class="mall__tab" :class="{ active: tab === 'mall' }" @click="tab = 'mall'">积分兑换</view>
        <view class="mall__tab" :class="{ active: tab === 'orders' }" @click="tab = 'orders'">我的兑换</view>
      </view>
    </view>

    <!-- 兑换商城 -->
    <view v-if="tab === 'mall'" class="mall__grid">
      <view v-for="p in onSaleProducts" :key="p.id" class="product-card">
        <view class="product-card__img">
          <text>{{ p.name.slice(0, 1) }}</text>
        </view>
        <view class="product-card__name">{{ p.name }}</view>
        <view class="product-card__meta">
          <text v-if="p.stock > 0" class="product-card__stock">库存 {{ p.stock }}</text>
          <text v-else class="product-card__stock">库存充足</text>
          <text class="product-card__tag">{{ tagOf(p) }}</text>
        </view>
        <view class="product-card__footer">
          <text class="product-card__points">{{ p.pointsCost }} 积分</text>
          <view
            class="product-card__btn"
            :class="{ 'product-card__btn--off': member.points < p.pointsCost || p.stock === 0 }"
            @click="redeem(p)"
          >{{ p.stock === 0 ? '已售罄' : member.points < p.pointsCost ? '积分不足' : '立即兑换' }}</view>
        </view>
      </view>
    </view>

    <!-- 我的兑换 -->
    <view v-else class="orders">
      <view v-if="myRedemptions.length === 0" class="orders__empty">暂无兑换记录</view>
      <view v-for="r in myRedemptions" :key="r.id" class="order-item">
        <view class="order-item__info">
          <view class="order-item__name">{{ r.productName }}</view>
          <view class="order-item__meta">数量 {{ r.qty }} · {{ r.createdAt?.slice(0, 10) }}</view>
        </view>
        <view class="order-item__right">
          <text class="order-item__points">-{{ r.pointsCost }}</text>
          <text
            class="order-item__status"
            :class="{
              pending: r.status === 'PENDING',
              approved: r.status === 'APPROVED' || r.status === 'FULFILLED',
              rejected: r.status === 'REJECTED',
            }"
          >{{ statusText(r.status) }}</text>
        </view>
      </view>
    </view>

    <view class="mall__tip">兑换申请经门店审核队列处理，审核通过后积分扣减生效</view>
  </view>
</template>

<style lang="scss" scoped>
.mall {
  padding: 0 0 48rpx;
}
.mall__header {
  background: linear-gradient(135deg, #8c5cf5, #ff6b9e);
  padding: 64rpx 32rpx;
  color: #fff;
}
.mall__points-label {
  font-size: 24rpx;
  opacity: 0.85;
}
.mall__points-value {
  display: block;
  font-size: 64rpx;
  font-weight: 700;
  margin-top: 8rpx;
}
.mall__tabs {
  display: flex;
  gap: 24rpx;
  margin-top: 24rpx;
}
.mall__tab {
  background: rgba(255, 255, 255, 0.2);
  color: #fff;
  padding: 12rpx 32rpx;
  border-radius: 999rpx;
  font-size: 24rpx;
}
.mall__tab.active {
  background: #fff;
  color: #ff6b9e;
  font-weight: 600;
}
.mall__grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 24rpx;
  padding: 24rpx;
}
.product-card {
  background: #fff;
  border-radius: 20rpx;
  overflow: hidden;
  box-shadow: 0 2rpx 8rpx rgba(0, 0, 0, 0.06);
}
.product-card__img {
  height: 200rpx;
  background: #f6f6f8;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 72rpx;
  color: #999;
}
.product-card__name {
  padding: 16rpx 24rpx 0;
  font-size: 24rpx;
  font-weight: 600;
  color: #1a1a1a;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.product-card__meta {
  padding: 8rpx 24rpx;
  display: flex;
  align-items: center;
  gap: 12rpx;
  font-size: 20rpx;
  color: #999;
}
.product-card__tag {
  background: #fff0f5;
  color: #ff6b9e;
  padding: 2rpx 12rpx;
  border-radius: 12rpx;
  font-size: 22rpx;
}
.product-card__footer {
  padding: 16rpx 24rpx;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.product-card__points {
  font-size: 24rpx;
  font-weight: 700;
  color: #ff6b9e;
}
.product-card__btn {
  background: #ff6b9e;
  color: #fff;
  padding: 10rpx 24rpx;
  border-radius: 12rpx;
  font-size: 20rpx;
}
.product-card__btn--off {
  background: #ccc;
}
.orders {
  padding: 24rpx;
}
.orders__empty {
  text-align: center;
  color: #999;
  padding: 96rpx 0;
  font-size: 24rpx;
}
.order-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 24rpx;
  background: #fff;
  border-radius: 20rpx;
  margin-bottom: 16rpx;
}
.order-item__name {
  font-size: 24rpx;
  font-weight: 600;
  color: #1a1a1a;
}
.order-item__meta {
  font-size: 20rpx;
  color: #999;
  margin-top: 8rpx;
}
.order-item__right {
  text-align: right;
}
.order-item__points {
  display: block;
  font-size: 24rpx;
  font-weight: 600;
  color: #ff6b9e;
}
.order-item__status {
  display: block;
  font-size: 20rpx;
  margin-top: 6rpx;
}
.order-item__status.pending {
  color: #fa8c16;
}
.order-item__status.approved {
  color: #52c41a;
}
.order-item__status.rejected {
  color: #ff4d4f;
}
.mall__tip {
  text-align: center;
  font-size: 20rpx;
  color: #999;
  padding: 24rpx;
}
</style>
