<script setup lang="ts">
/* 我的订单 pages/orders/list — 订单列表（store 订单 + 模拟历史单） */
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { useOrderStore } from '@/stores/order'
import { useMemberStore } from '@/stores/member'
import { navTo } from '@/utils/nav'

const order = useOrderStore()
const points = useMemberStore()

onShow(() => {
  order.seed()
  points.seed()
})

const tabs = [
  { key: 'ALL', label: '全部' },
  { key: 'PENDING_PAY', label: '待付款' },
  { key: 'PENDING_WRITE', label: '待核销' },
  { key: 'DONE', label: '已完成' },
]
const active = ref('ALL')

// 订单状态映射
const STATUS: Record<string, { label: string; cls: string }> = {
  PENDING_PAY: { label: '待付款', cls: 'warn' },
  PENDING_SIGN: { label: '待确认', cls: 'warn' },
  PENDING_WRITE: { label: '待核销', cls: 'ok' },
  PAID: { label: '待核销', cls: 'ok' },
  COMPLETED: { label: '已完成', cls: 'done' },
}
// 模拟历史单（演示用）
const mockHistory = [
  { id: 'mk1', orderNo: 'SO20260818002', items: [{ name: '闺蜜分享次卡', qty: 1, price: 3980 }], amount: 3980, status: 'COMPLETED' as const },
  { id: 'mk2', orderNo: 'SO20260810008', items: [{ name: '玻尿酸填充（瑞蓝2号）', qty: 1, price: 5280 }], amount: 5280, status: 'COMPLETED' as const },
]
const all = computed(() => {
  const mine = order.byCustomer(points.member.memberId).map((o) => ({
    id: o.id,
    orderNo: o.orderNo,
    items: o.items,
    amount: o.amount,
    status: o.status as string,
  }))
  return [...mine, ...mockHistory]
})
const filtered = computed(() => {
  if (active.value === 'ALL') return all.value
  if (active.value === 'PENDING_WRITE') return all.value.filter((o) => o.status === 'PENDING_WRITE' || o.status === 'PAID')
  if (active.value === 'DONE') return all.value.filter((o) => o.status === 'COMPLETED')
  return all.value.filter((o) => o.status === active.value)
})
function st(s: string) {
  return STATUS[s] || { label: s, cls: 'muted' }
}
function goDetail(id: string) {
  navTo(`/pages/orders/detail?id=${id}`)
}
</script>

<template>
  <view class="orders">
    <MNavbar title="我的订单" />
    <view class="tabbar">
      <view
        v-for="t in tabs"
        :key="t.key"
        class="tab"
        :class="{ on: active === t.key }"
        @click="active = t.key"
      >{{ t.label }}</view>
    </view>
    <view class="list">
      <view v-if="!filtered.length" class="empty">
        <view class="empty__icon"><uni-icons type="list" size="44" color="#ff6b9e" /></view>
        <view class="empty__text">暂无订单</view>
      </view>
      <view v-for="o in filtered" :key="o.id" class="ocard" @click="goDetail(o.id)">
        <view class="ocard__head">
          <text class="ocard__no">订单号 {{ o.orderNo }}</text>
          <text class="ocard__status" :class="st(o.status).cls">{{ st(o.status).label }}</text>
        </view>
        <view v-for="(it, i) in o.items.slice(0, 2)" :key="i" class="ocard__item">
          <view class="ocard__img">{{ it.name.charAt(0) }}</view>
          <view class="ocard__name">{{ it.name }}<text v-if="it.qty > 1"> ×{{ it.qty }}</text></view>
          <view class="ocard__price">¥{{ it.price.toLocaleString() }}</view>
        </view>
        <view class="ocard__foot">
          共 {{ o.items.reduce((s, it) => s + it.qty, 0) }} 件 合计 <text class="ocard__total">¥{{ o.amount.toLocaleString() }}</text>
        </view>
      </view>
    </view>
  </view>
</template>

<style lang="scss" scoped>
.orders {
  padding-bottom: 48rpx;
}
.tabbar {
  position: sticky;
  top: 0;
  z-index: 5;
  background: #fff;
  display: flex;
  padding: 0 16rpx;
  border-bottom: 1rpx solid #eee;
}
.tab {
  flex: 1;
  padding: 26rpx 0;
  font-size: 28rpx;
  color: #666;
  text-align: center;
  position: relative;
}
.tab.on {
  color: #ff4d6d;
  font-weight: 600;
}
.tab.on::after {
  content: '';
  position: absolute;
  bottom: 0;
  left: 50%;
  transform: translateX(-50%);
  width: 48rpx;
  height: 6rpx;
  border-radius: 4rpx;
  background: #ff6b9e;
}
.list {
  padding: 24rpx 32rpx;
  display: flex;
  flex-direction: column;
  gap: 24rpx;
}
.ocard {
  background: #fff;
  border-radius: 28rpx;
  padding: 28rpx;
}
.ocard__head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-bottom: 24rpx;
  border-bottom: 1rpx solid #f5f5f5;
}
.ocard__no {
  font-size: 24rpx;
  color: #999;
}
.ocard__status {
  font-size: 24rpx;
  padding: 6rpx 20rpx;
  border-radius: 24rpx;
}
.ocard__status.warn {
  color: #fa8c16;
  background: #fff7e6;
}
.ocard__status.ok {
  color: #ff6b9e;
  background: #fff0f5;
}
.ocard__status.done {
  color: #52c41a;
  background: #f0fff0;
}
.ocard__status.muted {
  color: #bbb;
  background: #f5f5f5;
}
.ocard__item {
  display: flex;
  align-items: center;
  padding: 24rpx 0;
}
.ocard__img {
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
.ocard__name {
  flex: 1;
  font-size: 28rpx;
  color: #333;
}
.ocard__price {
  font-size: 28rpx;
  color: #333;
  font-weight: 600;
}
.ocard__foot {
  text-align: right;
  font-size: 26rpx;
  color: #999;
  padding-top: 20rpx;
  border-top: 1rpx solid #f5f5f5;
}
.ocard__total {
  color: #ff4d6d;
  font-size: 32rpx;
  font-weight: 700;
}
.empty {
  text-align: center;
  padding: 160rpx 0;
}
.empty__icon {
  width: 140rpx;
  height: 140rpx;
  border-radius: 36rpx;
  background: #fff0f5;
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 0 auto;
}
.empty__text {
  font-size: 28rpx;
  color: #bbb;
  margin-top: 24rpx;
}
</style>
