<script setup lang="ts">
/* C 端电子小票 pages/receipt/detail?id= — 接 M4-15 收款（联动 6） */
import { computed, ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import { useOrderStore } from '@/stores/order'
import { navTo, toast } from '@/utils/nav'

const orderStore = useOrderStore()
const orderId = ref('')

onLoad((options) => {
  orderId.value = options?.id || ''
})
onShow(() => {
  orderStore.seed()
})

const order = computed(() => {
  return orderStore.get(orderId.value) || orderStore.orders.find((o) => o.status === 'PENDING_WRITE') || orderStore.orders[0]
})

const paidAmount = computed(() => (order.value ? order.value.amount : 0))
const payMethodLabel = computed(() => order.value?.payMethod || '会员卡')

function fmt(n: number) { return '¥' + n.toFixed(2) }
function fmtDate(s: string) { return s ? s.slice(0, 19).replace('T', ' ') : '' }

function goBack() { navTo('/pages/card/index') }
function saveReceipt() { toast('小票已保存到相册') }
</script>

<template>
  <MNavbar title="电子小票" />
  <view v-if="order" class="receipt">
    <view class="receipt__card">
      <view class="receipt__store">美研云医疗美容</view>
      <view class="receipt__no">订单号：{{ order.orderNo }}</view>
      <view class="receipt__time">{{ fmtDate(order.createdAt) }}</view>

      <view class="receipt__divider"></view>

      <view class="receipt__items">
        <view v-for="(item, idx) in order.items" :key="idx" class="receipt__item">
          <view class="receipt__item-name">{{ item.name }} <text class="receipt__item-qty">x{{ item.qty }}</text></view>
          <view class="receipt__item-price">{{ fmt(item.price * item.qty) }}</view>
        </view>
      </view>

      <view class="receipt__divider"></view>

      <view class="receipt__totals">
        <view class="receipt__row"><text>商品合计</text><text>{{ fmt(order.amount) }}</text></view>
        <view class="receipt__row receipt__row--total"><text>实付金额</text><text class="receipt__paid">{{ fmt(paidAmount) }}</text></view>
      </view>

      <view class="receipt__divider"></view>

      <view class="receipt__pay">
        <text>支付方式</text>
        <text>{{ payMethodLabel }}</text>
      </view>
      <view class="receipt__pay">
        <text>支付状态</text>
        <text class="receipt__status--paid">已支付</text>
      </view>
      <view class="receipt__pay">
        <text>本单获得积分</text>
        <text class="receipt__points">+{{ Math.floor(paidAmount / 10) }}</text>
      </view>

      <view class="receipt__divider"></view>

      <view class="receipt__footer">
        <view class="receipt__barcode"></view>
        <view class="receipt__thanks">感谢您的惠顾，期待下次光临</view>
        <view class="receipt__sub">如有疑问请联系门店或拨打客服热线</view>
      </view>
    </view>

    <view class="receipt__actions">
      <view class="receipt__btn receipt__btn--outline" @click="saveReceipt">保存到相册</view>
      <view class="receipt__btn receipt__btn--primary" @click="goBack">返回我的卡</view>
    </view>
  </view>
  <view v-else class="receipt__empty">
    <view>小票不存在</view>
    <view class="receipt__empty-btn" @click="goBack">返回</view>
  </view>
</template>

<style lang="scss" scoped>
.receipt { min-height: 100vh; background: #f6f6f8; padding-bottom: 160rpx; }
.receipt__card {
  margin: 24rpx; background: #fff; border-radius: 20rpx;
  padding: 32rpx; box-shadow: 0 2rpx 8rpx rgba(0,0,0,.06);
}
.receipt__store { text-align: center; font-size: 32rpx; font-weight: 700; color: #1a1a1a; }
.receipt__no { text-align: center; font-size: 20rpx; color: #999; margin-top: 8rpx; }
.receipt__time { text-align: center; font-size: 20rpx; color: #999; }
.receipt__divider {
  height: 2rpx; background: repeating-linear-gradient(90deg, #eee 0 12rpx, transparent 12rpx 24rpx);
  margin: 24rpx 0;
}
.receipt__item { display: flex; justify-content: space-between; padding: 8rpx 0; font-size: 24rpx; }
.receipt__item-qty { color: #999; margin-left: 16rpx; }
.receipt__item-price { color: #1a1a1a; }
.receipt__totals { display: flex; flex-direction: column; }
.receipt__row { display: flex; justify-content: space-between; font-size: 24rpx; color: #666; padding: 6rpx 0; }
.receipt__row--total { font-size: 28rpx; font-weight: 700; color: #1a1a1a; padding-top: 12rpx; border-top: 1rpx solid #eee; }
.receipt__paid { color: #ff6b9e; font-size: 32rpx; }
.receipt__pay { display: flex; justify-content: space-between; padding: 12rpx 0; font-size: 24rpx; color: #666; }
.receipt__status--paid { color: #52c41a; }
.receipt__points { color: #ff6b9e; font-weight: 600; }
.receipt__footer { text-align: center; padding-top: 24rpx; }
.receipt__barcode {
  height: 80rpx; background: repeating-linear-gradient(90deg, #1a1a1a 0 4rpx, transparent 4rpx 8rpx, #1a1a1a 8rpx 10rpx, transparent 10rpx 16rpx);
  margin: 0 auto 16rpx; max-width: 360rpx;
}
.receipt__thanks { font-size: 24rpx; color: #666; }
.receipt__sub { font-size: 20rpx; color: #999; margin-top: 8rpx; }
.receipt__actions {
  position: fixed; bottom: 0; left: 0; right: 0; width: 100%;
  display: flex; padding: 24rpx;
  background: #fff; box-shadow: 0 -2rpx 8rpx rgba(0,0,0,.06); box-sizing: border-box;
}
.receipt__btn { flex: 1; height: 88rpx; line-height: 88rpx; text-align: center; border-radius: 20rpx; font-size: 28rpx; margin: 0 12rpx; box-sizing: border-box; }
.receipt__btn--outline { background: #f6f6f8; color: #1a1a1a; border: 1rpx solid #eee; }
.receipt__btn--primary { background: #ff6b9e; color: #fff; }
.receipt__empty { text-align: center; padding: 160rpx 40rpx; color: #999; font-size: 28rpx; }
.receipt__empty-btn { margin-top: 32rpx; padding: 16rpx 48rpx; border-radius: 20rpx; background: #ff6b9e; color: #fff; display: inline-block; }
</style>
