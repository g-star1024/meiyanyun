<script setup lang="ts">
/* 项目购买 pages/projects/buy — 确认单 + 支付方式 + 提交订单 */
import { computed, ref } from 'vue'
import { onShow, onLoad } from '@dcloudio/uni-app'
import { usePricelistStore } from '@/stores/pricelist'
import { useMemberStore } from '@/stores/member'
import { createOrderAndPay } from '@/api/pay'
import { navTo, toast } from '@/utils/nav'

const pricelist = usePricelistStore()
const points = useMemberStore()
const id = ref('')
onLoad((options) => {
  id.value = options?.id || ''
})
onShow(() => {
  pricelist.seed()
  points.seed()
})

const item = computed(() => pricelist.get(id.value))
const qty = ref(1)
const payMethod = ref<'card' | 'wechat'>('card')
const useBalance = ref(true)
const done = ref(false)
const submitting = ref(false)

const unit = computed(() => item.value?.promoPrice ?? item.value?.memberPrice ?? 0)
const total = computed(() => unit.value * qty.value)
const balance = computed(() => points.member.cardBalance)
const balanceUsed = computed(() => (useBalance.value && payMethod.value === 'card' ? Math.min(balance.value, total.value) : 0))
const needPay = computed(() => Math.max(0, total.value - balanceUsed.value))

function changeQty(d: number) {
  qty.value = Math.min(9, Math.max(1, qty.value + d))
}

async function submit() {
  if (!item.value || submitting.value) return
  submitting.value = true
  try {
    if (payMethod.value === 'wechat') {
      // 微信支付：后端统一下单 + 唤起收银台；用户取消会抛异常
      await createOrderAndPay({ itemId: item.value.id, qty: qty.value })
    }
    // 会员卡支付/演示：直接成功
    done.value = true
  } catch (e: any) {
    if (e?.message === 'CANCEL') {
      toast('已取消支付')
    } else {
      toast('支付失败，请稍后重试')
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <MNavbar title="确认订单" />
  <view v-if="item" class="buy">
    <!-- 成功态 -->
    <view v-if="done" class="success">
      <view class="success__icon"><uni-icons type="checkmarkempty" size="52" color="#52c41a" /></view>
      <view class="success__title">下单成功</view>
      <view class="success__sub">{{ item.name }} × {{ qty }}</view>
      <view class="success__amount">实付 ¥{{ needPay.toLocaleString() }}</view>
      <view class="success__btn" @click="navTo('/pages/orders/list')">查看订单</view>
      <view class="success__link" @click="navTo('/pages/booking/list')">去预约到店时间 ›</view>
    </view>

    <template v-else>
      <!-- 商品 -->
      <view class="goods card">
        <view class="goods__img">{{ item.name.charAt(0) }}</view>
        <view class="goods__body">
          <view class="goods__name">{{ item.name }}</view>
          <view class="goods__price">
            <text>¥{{ unit.toLocaleString() }}</text>
            <text class="goods__unit">/{{ item.unit }}</text>
          </view>
        </view>
        <view class="qty">
          <view class="qty__btn" @click="changeQty(-1)">−</view>
          <text class="qty__num">{{ qty }}</text>
          <view class="qty__btn" @click="changeQty(1)">+</view>
        </view>
      </view>

      <!-- 服务门店 -->
      <view class="cell card" @click="navTo('/pages/stores/list')">
        <text class="cell__label">服务门店</text>
        <text class="cell__value">上海静安旗舰店 ›</text>
      </view>

      <!-- 支付方式 -->
      <view class="card pay">
        <view class="pay__item" @click="payMethod = 'card'">
          <view class="pay__left">
            <view class="pay__icon"><uni-icons type="wallet" size="20" color="#ff6b9e" /></view>
            <text>会员卡支付</text>
          </view>
          <view class="radio" :class="{ on: payMethod === 'card' }"></view>
        </view>
        <view class="pay__item" @click="payMethod = 'wechat'">
          <view class="pay__left">
            <view class="pay__icon pay__icon--wx"><uni-icons type="weixin" size="20" color="#07c160" /></view>
            <text>微信支付</text>
          </view>
          <view class="radio" :class="{ on: payMethod === 'wechat' }"></view>
        </view>
        <view v-if="payMethod === 'card'" class="pay__sub">
          <text>卡余额 ¥{{ balance.toLocaleString() }}，本次抵扣 ¥{{ balanceUsed.toLocaleString() }}</text>
        </view>
      </view>

      <view class="tip card">
        <view class="tip__icon"><uni-icons type="info" size="14" color="#fa8c16" /></view>
        <text class="tip__text">购买后可在「我的订单」查看，到店出示订单码即可核销服务。</text>
      </view>

      <view class="bottom-space"></view>

      <!-- 底部结算 -->
      <view class="bar">
        <view class="bar__total">
          <text>合计 </text>
          <text class="bar__strong">¥{{ total.toLocaleString() }}</text>
        </view>
        <view class="bar__btn" :class="{ disabled: submitting }" @click="submit">
          {{ submitting ? '提交中…' : '提交订单' }}
        </view>
      </view>
    </template>
  </view>
  <view v-else class="nf">项目不存在</view>
</template>

<style lang="scss" scoped>
.buy {
  padding-bottom: 0;
}
.card {
  background: #fff;
  border-radius: 28rpx;
  margin: 24rpx;
  padding: 28rpx;
}
.goods {
  display: flex;
  align-items: center;
}
.goods__img {
  width: 128rpx;
  height: 128rpx;
  border-radius: 20rpx;
  background: linear-gradient(135deg, #fff0f5, #ffe0ec);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 56rpx;
  color: #ff6b9e;
}
.goods__body {
  flex: 1;
  min-width: 0;
  margin-left: 24rpx;
}
.goods__name {
  font-size: 30rpx;
  font-weight: 600;
  color: #1a1a1a;
}
.goods__price {
  font-size: 36rpx;
  font-weight: 700;
  color: #ff4d6d;
  margin-top: 12rpx;
}
.goods__unit {
  font-size: 24rpx;
  color: #999;
  font-weight: 400;
}
.qty {
  display: flex;
  align-items: center;
}
.qty__btn {
  width: 52rpx;
  height: 52rpx;
  border-radius: 50%;
  border: 1rpx solid #ddd;
  background: #fff;
  font-size: 32rpx;
  color: #666;
  line-height: 48rpx;
  text-align: center;
  margin: 0 16rpx;
}
.qty__num {
  font-size: 30rpx;
  min-width: 40rpx;
  text-align: center;
}
.cell {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.cell__label {
  font-size: 28rpx;
  color: #333;
}
.cell__value {
  font-size: 28rpx;
  color: #999;
}
.pay {
  padding: 8rpx 28rpx;
}
.pay__item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 28rpx 0;
  border-bottom: 1rpx solid #f2f2f2;
}
.pay__left {
  display: flex;
  align-items: center;
  font-size: 30rpx;
  color: #1a1a1a;
}
.pay__icon {
  width: 48rpx;
  height: 48rpx;
  border-radius: 14rpx;
  background: #fff0f5;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-right: 20rpx;
}
.pay__icon--wx {
  background: #e7f9ee;
}
.radio {
  width: 40rpx;
  height: 40rpx;
  border-radius: 50%;
  border: 3rpx solid #d0d0d0;
  position: relative;
  box-sizing: border-box;
}
.radio.on {
  border-color: #ff6b9e;
  background: #ff6b9e;
}
.radio.on::after {
  content: '';
  position: absolute;
  left: 12rpx;
  top: 6rpx;
  width: 10rpx;
  height: 18rpx;
  border: solid #fff;
  border-width: 0 4rpx 4rpx 0;
  transform: rotate(45deg);
}
.pay__sub {
  padding: 24rpx 0;
  font-size: 24rpx;
  color: #ff8c42;
}
.tip {
  font-size: 24rpx;
  color: #999;
  line-height: 1.6;
  display: flex;
  align-items: flex-start;
}
.tip__icon {
  margin-right: 10rpx;
  margin-top: 2rpx;
  display: flex;
  align-items: center;
}
.tip__text {
  flex: 1;
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
  padding: 16rpx 32rpx calc(16rpx + env(safe-area-inset-bottom));
  display: flex;
  align-items: center;
  justify-content: space-between;
  box-sizing: border-box;
  z-index: 20;
}
.bar__total {
  font-size: 28rpx;
  color: #333;
}
.bar__strong {
  font-size: 44rpx;
  color: #ff4d6d;
  font-weight: 800;
}
.bar__btn {
  height: 88rpx;
  line-height: 88rpx;
  padding: 0 72rpx;
  border-radius: 44rpx;
  background: linear-gradient(135deg, #ffbff0, #ff6b9e);
  color: #fff;
  font-size: 32rpx;
  font-weight: 700;
  text-align: center;
}
.bar__btn.disabled {
  opacity: 0.6;
}
.success {
  text-align: center;
  padding: 160rpx 48rpx;
}
.success__icon {
  width: 160rpx;
  height: 160rpx;
  border-radius: 40rpx;
  background: #eaf8ef;
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 0 auto;
}
.success__title {
  font-size: 40rpx;
  font-weight: 700;
  color: #1a1a1a;
  margin-top: 32rpx;
}
.success__sub {
  font-size: 28rpx;
  color: #888;
  margin-top: 16rpx;
}
.success__amount {
  font-size: 32rpx;
  color: #ff4d6d;
  font-weight: 700;
  margin-top: 16rpx;
}
.success__btn {
  height: 92rpx;
  line-height: 92rpx;
  margin-top: 64rpx;
  border-radius: 46rpx;
  background: linear-gradient(135deg, #ffbff0, #ff6b9e);
  color: #fff;
  font-size: 32rpx;
  font-weight: 700;
}
.success__link {
  margin-top: 32rpx;
  color: #ff6b9e;
  font-size: 28rpx;
}
.nf {
  text-align: center;
  color: #bbb;
  padding: 200rpx 0;
}
</style>
