<script setup lang="ts">
/* 订单详情 pages/orders/detail — 商品 + 核销码 + 金额明细 */
import { computed, ref } from 'vue'
import { onShow, onLoad } from '@dcloudio/uni-app'
import { useOrderStore, type OrderItem } from '@/stores/order'
import { navTo } from '@/utils/nav'

const order = useOrderStore()
const id = ref('')

onShow(() => order.seed())
onLoad((options) => {
  id.value = options?.id || ''
})

const statusMap: Record<string, string> = {
  PENDING_PAY: '待付款',
  PENDING_SIGN: '待确认',
  PENDING_WRITE: '待核销',
  PAID: '待核销',
  COMPLETED: '已完成',
}

interface DetailOrder {
  orderNo: string
  status: string
  items: OrderItem[]
  amount: number
  payMethod: string
  createdAt: string
  store: string
}

// store 无此单时用示例兜底（mock 历史单）
const detail = computed<DetailOrder>(() => {
  const o = order.get(id.value)
  if (o) {
    return {
      orderNo: o.orderNo,
      status: o.status,
      items: o.items,
      amount: o.amount,
      payMethod: o.payMethod || '微信支付',
      createdAt: o.createdAt.slice(0, 16).replace('T', ' '),
      store: o.store,
    }
  }
  return {
    orderNo: 'SO20260818002',
    status: 'COMPLETED',
    items: [{ name: '闺蜜分享次卡', spec: '10 次水光', qty: 1, price: 3980 }],
    amount: 3980,
    payMethod: '微信支付',
    createdAt: '2026-08-18 14:02',
    store: '上海静安旗舰店',
  }
})

const isCompleted = computed(() => detail.value.status === 'COMPLETED')
const statusIcon = computed(() => (isCompleted.value ? 'checkmarkempty' : 'medal'))
function goAdvisor() {
  navTo('/pages/advisor/index')
}
function goBooking() {
  navTo('/pages/booking/list')
}
</script>

<template>
  <view class="od">
    <MNavbar title="订单详情" />
    <!-- 状态条 -->
    <view class="status">
      <view class="status__icon" :class="{ done: isCompleted }">
        <uni-icons :type="statusIcon" size="36" :color="isCompleted ? '#52c41a' : '#ff6b9e'" />
      </view>
      <view class="status__text">{{ statusMap[detail.status] || '待核销' }}</view>
      <view class="status__sub">{{ isCompleted ? '服务已完成，感谢您的信任' : '到店出示核销码即可服务' }}</view>
    </view>

    <!-- 核销码 -->
    <view v-if="!isCompleted" class="qr card">
      <view class="qr__code">
        <view class="qr__grid">
          <view
            v-for="n in 49"
            :key="n"
            class="qr__cell"
            :style="{ background: (n * 7 + (n % 3)) % 3 === 0 ? '#1a1a1a' : 'transparent' }"
          ></view>
        </view>
      </view>
      <view class="qr__hint">到店请向工作人员出示此核销码</view>
    </view>

    <!-- 门店 -->
    <view class="card cell">
      <view class="cell__left">
        <uni-icons type="shop" size="14" color="#333" />
        <text>服务门店</text>
      </view>
      <text class="cell__right">{{ detail.store }} ›</text>
    </view>

    <!-- 商品 -->
    <view class="card goods">
      <view v-for="(it, i) in detail.items" :key="i" class="goods__row">
        <view class="goods__img">{{ it.name.charAt(0) }}</view>
        <view class="goods__body">
          <view class="goods__name">{{ it.name }}</view>
          <view class="goods__spec">{{ it.spec || '到店服务' }}</view>
        </view>
        <view class="goods__right">
          <view class="goods__price">¥{{ it.price.toLocaleString() }}</view>
          <view class="goods__qty">×{{ it.qty }}</view>
        </view>
      </view>
    </view>

    <!-- 金额明细 -->
    <view class="card amount">
      <view class="amount__row"><text>商品总额</text><text>¥{{ detail.amount.toLocaleString() }}</text></view>
      <view class="amount__row"><text>优惠</text><text class="amount__off">-¥0</text></view>
      <view class="amount__row amount__row--total"><text>实付</text><text class="amount__pay">¥{{ detail.amount.toLocaleString() }}</text></view>
    </view>

    <!-- 订单信息 -->
    <view class="card info">
      <view class="info__row"><text>订单编号</text><text>{{ detail.orderNo }}</text></view>
      <view class="info__row"><text>下单时间</text><text>{{ detail.createdAt }}</text></view>
      <view class="info__row"><text>支付方式</text><text>{{ detail.payMethod }}</text></view>
    </view>

    <view v-if="!isCompleted" class="bottom-space"></view>
    <view v-if="!isCompleted" class="bar">
      <view class="bar__btn bar__btn--ghost" @click="goAdvisor">联系顾问</view>
      <view class="bar__btn bar__btn--main" @click="goBooking">预约到店</view>
    </view>
  </view>
</template>

<style lang="scss" scoped>
.od {
  padding-bottom: 48rpx;
}
.card {
  background: #fff;
  border-radius: 28rpx;
  margin: 24rpx;
  padding: 32rpx;
}
.status {
  background: linear-gradient(160deg, #ffbff0, #ff6b9e);
  padding: 56rpx 40rpx;
  text-align: center;
  color: #fff;
}
.status__icon {
  width: 112rpx;
  height: 112rpx;
  border-radius: 32rpx;
  background: #fff0f5;
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 0 auto;
}
.status__icon.done {
  background: #eaf8ef;
}
.status__text {
  font-size: 40rpx;
  font-weight: 700;
  margin-top: 20rpx;
}
.status__sub {
  font-size: 26rpx;
  opacity: 0.95;
  margin-top: 12rpx;
}
.qr {
  text-align: center;
}
.qr__code {
  width: 320rpx;
  height: 320rpx;
  margin: 0 auto;
  padding: 24rpx;
  border: 2rpx dashed #ffb3cd;
  border-radius: 24rpx;
  box-sizing: border-box;
}
.qr__grid {
  width: 100%;
  height: 100%;
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 4rpx;
}
.qr__cell {
  border-radius: 2rpx;
}
.qr__hint {
  font-size: 24rpx;
  color: #999;
  margin-top: 24rpx;
}
.cell {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 28rpx;
  color: #333;
}
.cell__left {
  display: flex;
  align-items: center;
}
.cell__left text {
  margin-left: 8rpx;
}
.cell__right {
  color: #999;
}
.goods__row {
  display: flex;
  align-items: center;
  padding: 16rpx 0;
}
.goods__img {
  width: 112rpx;
  height: 112rpx;
  border-radius: 20rpx;
  background: linear-gradient(135deg, #fff0f5, #ffe0ec);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 48rpx;
  color: #ff6b9e;
  flex-shrink: 0;
  margin-right: 24rpx;
}
.goods__body {
  flex: 1;
  min-width: 0;
}
.goods__name {
  font-size: 28rpx;
  font-weight: 600;
  color: #1a1a1a;
}
.goods__spec {
  font-size: 24rpx;
  color: #999;
  margin-top: 8rpx;
}
.goods__right {
  text-align: right;
}
.goods__price {
  font-size: 28rpx;
  font-weight: 600;
  color: #1a1a1a;
}
.goods__qty {
  font-size: 24rpx;
  color: #999;
  margin-top: 8rpx;
}
.amount__row {
  display: flex;
  justify-content: space-between;
  font-size: 28rpx;
  color: #666;
  padding: 12rpx 0;
}
.amount__off {
  color: #ff4d6d;
}
.amount__row--total {
  border-top: 1rpx solid #f2f2f2;
  margin-top: 12rpx;
  padding-top: 24rpx;
  color: #333;
}
.amount__pay {
  font-size: 38rpx;
  color: #ff4d6d;
  font-weight: 800;
}
.info__row {
  display: flex;
  justify-content: space-between;
  font-size: 26rpx;
  color: #999;
  padding: 12rpx 0;
}
.bottom-space {
  height: 160rpx;
}
.bar {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  width: 100%;
  background: #fff;
  border-top: 1rpx solid #eee;
  padding: 16rpx 32rpx;
  padding-bottom: calc(16rpx + env(safe-area-inset-bottom));
  display: flex;
  gap: 20rpx;
  box-sizing: border-box;
  z-index: 20;
}
.bar__btn {
  flex: 1;
  height: 88rpx;
  line-height: 88rpx;
  text-align: center;
  border-radius: 44rpx;
  font-size: 30rpx;
  font-weight: 600;
}
.bar__btn--ghost {
  border: 2rpx solid #eee;
  background: #fff;
  color: #666;
}
.bar__btn--main {
  background: linear-gradient(135deg, #ffbff0, #ff6b9e);
  color: #fff;
}
</style>
