<script setup lang="ts">
/* 消费记录 pages/records/index — 按时间分组的到店消费流水 */
import { ref } from 'vue'
import { navTo } from '@/utils/nav'

const tabs = ['全部', '项目消费', '卡项充值', '退款']
const active = ref('全部')
const records = [
  {
    date: '2026-08-25',
    list: [
      { name: '光子嫩肤（第3次）', store: '静安旗舰店', amount: 0, note: '疗程卡扣次', time: '15:20', type: '项目消费' },
    ],
  },
  {
    date: '2026-08-18',
    list: [
      { name: '闺蜜分享次卡', store: '静安旗舰店', amount: -3980, note: '微信支付', time: '14:02', type: '卡项充值' },
    ],
  },
  {
    date: '2026-08-10',
    list: [
      { name: '玻尿酸填充（瑞蓝2号）', store: '静安旗舰店', amount: -5280, note: '卡余额支付', time: '16:40', type: '项目消费' },
      { name: '储值卡充值', store: '静安旗舰店', amount: -10000, note: '微信支付', time: '16:10', type: '卡项充值' },
    ],
  },
]
const shown = (t: string) => active.value === '全部' || active.value === t
function iconOf(type: string): { type: string; color: string } {
  if (type === '退款') return { type: 'undo', color: '#fa8c16' }
  if (type === '卡项充值') return { type: 'wallet', color: '#ff6b9e' }
  return { type: 'staff', color: '#ff6b9e' }
}
function goOrders() {
  navTo('/pages/orders/list')
}
</script>

<template>
  <view class="rec">
    <MNavbar title="消费记录" />
    <view class="tabbar">
      <view
        v-for="t in tabs"
        :key="t"
        class="tab"
        :class="{ on: active === t }"
        @click="active = t"
      >{{ t }}</view>
    </view>
    <view v-for="g in records" :key="g.date" class="group">
      <view class="group__date">{{ g.date }}</view>
      <view class="group__list">
        <view
          v-for="(r, i) in g.list"
          :key="i"
          v-show="shown(r.type)"
          class="rrow"
          @click="goOrders"
        >
          <view class="rrow__icon" :class="{ refund: r.type === '退款' }">
            <uni-icons :type="iconOf(r.type).type" size="22" :color="iconOf(r.type).color" />
          </view>
          <view class="rrow__body">
            <view class="rrow__name">{{ r.name }}</view>
            <view class="rrow__meta">{{ r.store }} · {{ r.note }} · {{ r.time }}</view>
          </view>
          <view class="rrow__amount" :class="{ in: r.amount > 0 }">
            {{ r.amount === 0 ? '扣次' : (r.amount > 0 ? '+' : '') + '¥' + Math.abs(r.amount).toLocaleString() }}
          </view>
        </view>
      </view>
    </view>
  </view>
</template>

<style lang="scss" scoped>
.rec {
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
.group {
  margin-top: 24rpx;
}
.group__date {
  font-size: 24rpx;
  color: #999;
  padding: 0 32rpx 16rpx;
}
.group__list {
  background: #fff;
  margin: 0 24rpx;
  border-radius: 28rpx;
  overflow: hidden;
}
.rrow {
  display: flex;
  align-items: center;
  padding: 28rpx 32rpx;
  border-bottom: 1rpx solid #f5f5f5;
}
.rrow:last-child {
  border-bottom: none;
}
.rrow__icon {
  width: 80rpx;
  height: 80rpx;
  border-radius: 20rpx;
  background: #fff0f5;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-right: 24rpx;
  flex-shrink: 0;
}
.rrow__icon.refund {
  background: #fff5e6;
}
.rrow__body {
  flex: 1;
  min-width: 0;
}
.rrow__name {
  font-size: 28rpx;
  font-weight: 600;
  color: #1a1a1a;
}
.rrow__meta {
  font-size: 24rpx;
  color: #999;
  margin-top: 6rpx;
}
.rrow__amount {
  font-size: 32rpx;
  font-weight: 700;
  color: #1a1a1a;
}
.rrow__amount.in {
  color: #52c41a;
}
</style>
