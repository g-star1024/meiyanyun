<script setup lang="ts">
/* 我的预约 pages/booking/list — 预约记录列表 + 新建入口 */
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { useAppointmentStore } from '@/stores/appointment'
import { useMemberStore } from '@/stores/member'
import { navTo } from '@/utils/nav'

const appt = useAppointmentStore()
const points = useMemberStore()
onShow(() => {
  appt.seed()
  points.seed()
})

const myAppts = computed(() =>
  appt.appointments
    .filter((a) => a.customerId === points.member.memberId)
    .sort((a, b) => (b.timeSlot || '').localeCompare(a.timeSlot || '')),
)
const tabs = ['全部', '待确认', '已确认', '已完成']
const activeTab = ref('全部')
const filtered = computed(() => {
  if (activeTab.value === '全部') return myAppts.value
  const map: Record<string, string> = { 待确认: 'NEW', 已确认: 'CONFIRMED', 已完成: 'COMPLETED' }
  return myAppts.value.filter((a) => a.status === map[activeTab.value])
})

function statusLabel(s: string) {
  return ({ NEW: '待确认', CONFIRMED: '已确认', ARRIVED: '已到店', COMPLETED: '已完成', CANCELLED: '已取消', NO_SHOW: '未到店' } as Record<string, string>)[s] || s
}
function statusCls(s: string) {
  if (s === 'CONFIRMED' || s === 'ARRIVED') return 'ok'
  if (s === 'COMPLETED') return 'done'
  if (s === 'CANCELLED' || s === 'NO_SHOW') return 'muted'
  return 'warn'
}
</script>

<template>
  <view class="bk">
    <MNavbar title="我的预约" />
    <!-- 状态 tab -->
    <view class="tabbar">
      <view
        v-for="t in tabs"
        :key="t"
        class="tab"
        :class="{ on: activeTab === t }"
        @click="activeTab = t"
      >{{ t }}</view>
    </view>

    <view class="list">
      <view v-if="!filtered.length" class="empty">
        <view class="empty__icon"><uni-icons type="calendar" size="44" color="#ff6b9e" /></view>
        <view class="empty__text">暂无预约记录</view>
      </view>
      <view v-for="a in filtered" :key="a.id" class="acard">
        <view class="acard__top">
          <text class="acard__project">{{ a.project || '到店服务' }}</text>
          <text class="acard__status" :class="statusCls(a.status)">{{ statusLabel(a.status) }}</text>
        </view>
        <view class="acard__row"><uni-icons type="calendar" size="13" color="#888" /> <text>{{ a.timeSlot?.replace('T', ' ').slice(5, 16) || '时间待定' }}</text></view>
        <view class="acard__row"><uni-icons type="shop" size="13" color="#888" /> <text>上海静安旗舰店</text></view>
        <view class="acard__foot">
          <text class="acard__no">预约号 {{ a.id }}</text>
          <text v-if="a.status === 'NEW' || a.status === 'CONFIRMED'" class="acard__src">来源：{{ a.source }}</text>
        </view>
      </view>
    </view>

    <!-- 新建预约悬浮按钮 -->
    <view class="fab" @click="navTo('/pages/booking/new')">
      <text class="fab__plus">＋</text>
      <text>新建预约</text>
    </view>
  </view>
</template>

<style lang="scss" scoped>
.bk {
  padding-bottom: 180rpx;
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
}
.acard {
  background: #fff;
  border-radius: 28rpx;
  padding: 28rpx;
  margin-bottom: 24rpx;
}
.acard__top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20rpx;
}
.acard__project {
  font-size: 32rpx;
  font-weight: 700;
  color: #1a1a1a;
}
.acard__status {
  font-size: 24rpx;
  padding: 6rpx 20rpx;
  border-radius: 24rpx;
}
.acard__status.ok {
  color: #ff6b9e;
  background: #fff0f5;
}
.acard__status.done {
  color: #52c41a;
  background: #f0fff0;
}
.acard__status.warn {
  color: #fa8c16;
  background: #fff7e6;
}
.acard__status.muted {
  color: #bbb;
  background: #f5f5f5;
}
.acard__row {
  font-size: 26rpx;
  color: #666;
  line-height: 2;
  display: flex;
  align-items: center;
}
.acard__row text {
  margin-left: 8rpx;
}
.acard__foot {
  display: flex;
  justify-content: space-between;
  margin-top: 20rpx;
  padding-top: 20rpx;
  border-top: 1rpx solid #f2f2f2;
}
.acard__no {
  font-size: 24rpx;
  color: #bbb;
}
.acard__src {
  font-size: 24rpx;
  color: #bbb;
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
.fab {
  position: fixed;
  bottom: 48rpx;
  left: 32rpx;
  right: 32rpx;
  height: 96rpx;
  border-radius: 48rpx;
  background: linear-gradient(135deg, #ffbff0, #ff6b9e);
  color: #fff;
  font-size: 32rpx;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 12rpx 36rpx rgba(255, 107, 158, 0.4);
  z-index: 20;
}
.fab__plus {
  font-size: 36rpx;
  margin-right: 8rpx;
}
</style>
