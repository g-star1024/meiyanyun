<script setup lang="ts">
/* 订单详情 pages/orders/detail — 商品 + 核销凭证（订单号）+ 金额明细（C-B4 切真：无单如实空态，无假数据兜底） */
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
  PENDING_SIGN: '待确认',
  PENDING_PAY: '待付款',
  PAID: '待核销',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
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

// store 无此单（不存在/越权/未登录）如实空态，不兜底假数据
const detail = computed<DetailOrder | null>(() => {
  const o = order.get(id.value)
  if (!o) return null
  return {
    orderNo: o.orderNo,
    status: o.status,
    items: o.items,
    amount: o.amount,
    payMethod: o.payMethod || '—',
    createdAt: o.createdAt.slice(0, 16).replace('T', ' '),
    store: o.store || o.storeName || '—',
  }
})

const isCompleted = computed(() => detail.value?.status === 'COMPLETED')
const statusIcon = computed(() => (isCompleted.value ? 'checkmarkempty' : 'medal'))
const statusSub = computed(() => {
  switch (detail.value?.status) {
    case 'COMPLETED':
      return '服务已完成，感谢您的信任'
    case 'PAID':
      return '到店出示订单号即可核销服务'
    case 'PENDING_PAY':
      return '订单待收款，请到店收银台完成支付'
    case 'PENDING_SIGN':
      return '订单已提交，待门店确认'
    case 'CANCELLED':
      return '订单已取消'
    default:
      return ''
  }
})
const showActionBar = computed(() => {
  const s = detail.value?.status
  return s === 'PENDING_SIGN' || s === 'PENDING_PAY' || s === 'PAID'
})
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
    <template v-if="detail">
    <!-- 状态条 -->
    <view class="status">
      <view class="status__icon" :class="{ done: isCompleted }">
        <uni-icons :type="statusIcon" size="36" :color="isCompleted ? '#52c41a' : '#ff6b9e'" />
      </view>
      <view class="status__text">{{ statusMap[detail.status] || detail.status }}</view>
      <view class="status__sub">{{ statusSub }}</view>
    </view>

    <!-- 核销凭证（订单号核销；假二维码已按红线移除） -->
    <view v-if="detail.status === 'PAID'" class="qr card">
      <view class="qr__code">
        <view class="qr__no">{{ detail.orderNo }}</view>
      </view>
      <view class="qr__hint">到店请向工作人员出示订单号核销</view>
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

    <view v-if="showActionBar" class="bottom-space"></view>
    <view v-if="showActionBar" class="bar">
      <view class="bar__btn bar__btn--ghost" @click="goAdvisor">联系顾问</view>
      <view class="bar__btn bar__btn--main" @click="goBooking">预约到店</view>
    </view>
    </template>
    <view v-else class="nf">订单不存在或无权查看</view>
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
  margin: 0 auto;
  padding: 40rpx 24rpx;
  border: 2rpx dashed #ffb3cd;
  border-radius: 24rpx;
  box-sizing: border-box;
  display: flex;
  align-items: center;
  justify-content: center;
}
.qr__no {
  font-size: 40rpx;
  font-weight: 700;
  color: #1a1a1a;
  letter-spacing: 4rpx;
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
.nf {
  text-align: center;
  color: #bbb;
  font-size: 28rpx;
  padding: 200rpx 0;
}
</style>
