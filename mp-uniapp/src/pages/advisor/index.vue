<script setup lang="ts">
/* C 端专属顾问 pages/advisor/index */
import { navTo, toast } from '@/utils/nav'

const advisor = {
  name: '林微',
  title: '资深皮肤管理顾问',
  avatar: '林',
  years: '8 年医美咨询经验',
  tags: ['皮肤管理', '抗衰方案', '术后护理'],
  served: '已服务 1,200+ 会员',
  rating: '4.9 分好评',
}
const faqs = [
  { q: '项目做完后多久能化妆？', a: '光电类项目建议 24-48 小时后再化妆，具体遵医嘱。' },
  { q: '疗程可以分次做吗？', a: '可以，疗程有效期内按建议周期分次到店即可。' },
  { q: '会员卡余额怎么用？', a: '到店消费出示会员码，可直接抵扣项目费用。' },
]

function book() { navTo('/pages/booking/new') }
function chat() { toast('在线咨询即将开启') }
function call() {
  try { uni.makePhoneCall({ phoneNumber: '400-000-0000' }) } catch (e) { toast('拨打失败') }
}
</script>

<template>
  <view class="adv">
    <MNavbar title="专属顾问" />
    <!-- 顾问卡片 -->
    <view class="profile card">
      <view class="profile__avatar">{{ advisor.avatar }}</view>
      <view class="profile__info">
        <view class="profile__name">{{ advisor.name }} <text class="profile__badge">专属</text></view>
        <view class="profile__title">{{ advisor.title }}</view>
        <view class="profile__tags">
          <text v-for="t in advisor.tags" :key="t" class="profile__tag">{{ t }}</text>
        </view>
      </view>
    </view>
    <view class="stats card">
      <view class="stats__item"><text class="stats__b">{{ advisor.years }}</text><text class="stats__l">从业经验</text></view>
      <view class="stats__item"><text class="stats__b">{{ advisor.served }}</text><text class="stats__l">服务规模</text></view>
      <view class="stats__item"><text class="stats__b">{{ advisor.rating }}</text><text class="stats__l">会员口碑</text></view>
    </view>

    <!-- 快捷操作 -->
    <view class="card actions">
      <view class="action" @click="book"><uni-icons class="action__ic" type="calendar" size="26" color="#ff6b9e" /><text>预约顾问</text></view>
      <view class="action" @click="chat"><uni-icons class="action__ic" type="chat" size="26" color="#ff6b9e" /><text>在线咨询</text></view>
      <view class="action" @click="call"><uni-icons class="action__ic" type="phone" size="26" color="#ff6b9e" /><text>电话联系</text></view>
    </view>

    <!-- 常见问题 -->
    <view class="card faq">
      <view class="faq__title">常见问题</view>
      <view v-for="(f, i) in faqs" :key="i" class="faq__item">
        <view class="faq__q">Q：{{ f.q }}</view>
        <view class="faq__a">{{ f.a }}</view>
      </view>
    </view>
  </view>
</template>

<style lang="scss" scoped>
.adv { padding: 24rpx 0 48rpx; }
.card { background: #fff; border-radius: 28rpx; margin: 0 24rpx 24rpx; padding: 32rpx; }
.profile { display: flex; }
.profile__avatar { width: 128rpx; height: 128rpx; border-radius: 50%; background: linear-gradient(135deg,#FFBFF0,#FF6B9E); color: #fff; display: flex; align-items: center; justify-content: center; font-size: 52rpx; font-weight: 700; flex-shrink: 0; }
.profile__info { flex: 1; margin-left: 28rpx; }
.profile__name { font-size: 34rpx; font-weight: 700; color: #1a1a1a; }
.profile__badge { font-size: 20rpx; color: #fff; background: linear-gradient(135deg,#FFBFF0,#FF6B9E); padding: 4rpx 14rpx; border-radius: 16rpx; margin-left: 12rpx; }
.profile__title { font-size: 26rpx; color: #888; margin-top: 8rpx; }
.profile__tags { display: flex; margin-top: 16rpx; flex-wrap: wrap; }
.profile__tag { font-size: 22rpx; color: #ff6b9e; background: #fff0f5; padding: 6rpx 18rpx; border-radius: 20rpx; margin-right: 12rpx; }
.stats { display: flex; }
.stats__item { flex: 1; text-align: center; }
.stats__b { display: block; font-size: 28rpx; color: #ff4d6d; font-weight: 700; }
.stats__l { font-size: 24rpx; color: #999; margin-top: 8rpx; display: block; }
.actions { display: flex; padding: 32rpx 24rpx; }
.action { flex: 1; background: #faf5f8; border-radius: 24rpx; padding: 28rpx 0; display: flex; flex-direction: column; align-items: center; font-size: 26rpx; color: #555; margin: 0 10rpx; }
.action__ic { margin-bottom: 12rpx; }
.faq__title { margin-bottom: 24rpx; font-size: 30rpx; font-weight: 700; color: #1a1a1a; }
.faq__item { padding: 24rpx 0; border-bottom: 1rpx solid #f5f5f5; }
.faq__item:last-child { border-bottom: none; }
.faq__q { font-size: 28rpx; font-weight: 600; color: #333; }
.faq__a { font-size: 26rpx; color: #888; margin-top: 12rpx; line-height: 1.6; }
</style>
