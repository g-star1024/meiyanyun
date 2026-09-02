<script setup lang="ts">
/* 我的套餐 pages/packages/index — 会员已购卡项/疗程（模拟数据） */
interface MyPackage {
  name: string
  total: number
  used: number
  expire: string
  type: string
  balance?: string
}
// 已购套餐疗程
const myPackages: MyPackage[] = [
  { name: '光子嫩肤亮肤疗程', total: 6, used: 2, expire: '2027-02-18', type: '美肤疗程' },
  { name: '闺蜜分享次卡', total: 10, used: 4, expire: '2027-01-10', type: '次卡' },
  { name: '焕颜抗衰储值卡', total: 1, used: 0, expire: '2027-08-20', type: '储值卡', balance: '¥8,600' },
]
</script>

<template>
  <view class="pk">
    <MNavbar title="我的套餐" />
    <view class="list">
      <view v-for="(p, i) in myPackages" :key="i" class="pk-card">
        <view class="pk-card__head">
          <text class="pk-card__name">{{ p.name }}</text>
          <text class="pk-card__type">{{ p.type }}</text>
        </view>
        <view class="pk-card__progress">
          <view v-if="p.total > 1">
            <text class="pk-card__used">{{ p.used }}</text>
            <text> / {{ p.total }} 次</text>
            <view class="bar">
              <view class="bar__i" :style="{ width: (p.used / p.total * 100) + '%' }"></view>
            </view>
          </view>
          <view v-else>
            <text class="pk-card__used">{{ p.balance || '未使用' }}</text>
          </view>
        </view>
        <view class="pk-card__foot">
          <text>有效期至 {{ p.expire }}</text>
          <text v-if="p.total > 1" class="pk-card__left">剩余 {{ p.total - p.used }} 次</text>
        </view>
      </view>
    </view>
    <view class="tip">套餐/疗程到店核销后自动扣次，消费记录可查看明细。</view>
  </view>
</template>

<style lang="scss" scoped>
.pk {
  padding: 24rpx 32rpx 48rpx;
}
.list {
  display: flex;
  flex-direction: column;
  gap: 24rpx;
}
.pk-card {
  background: linear-gradient(135deg, #fff, #fff7fa);
  border: 2rpx solid #ffe3ee;
  border-radius: 28rpx;
  padding: 32rpx;
}
.pk-card__head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.pk-card__name {
  font-size: 32rpx;
  font-weight: 700;
  color: #1a1a1a;
}
.pk-card__type {
  font-size: 22rpx;
  color: #ff6b9e;
  background: #fff0f5;
  padding: 6rpx 18rpx;
  border-radius: 20rpx;
}
.pk-card__progress {
  margin: 28rpx 0;
  font-size: 28rpx;
  color: #666;
}
.pk-card__used {
  font-size: 44rpx;
  color: #ff4d6d;
  font-weight: 800;
}
.bar {
  height: 12rpx;
  background: #ffeef4;
  border-radius: 6rpx;
  margin-top: 16rpx;
  overflow: hidden;
}
.bar__i {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, #ffbff0, #ff6b9e);
  border-radius: 6rpx;
}
.pk-card__foot {
  display: flex;
  justify-content: space-between;
  font-size: 24rpx;
  color: #999;
}
.pk-card__left {
  color: #ff6b9e;
  font-weight: 600;
}
.tip {
  font-size: 24rpx;
  color: #bbb;
  text-align: center;
  margin-top: 32rpx;
  line-height: 1.6;
}
</style>
