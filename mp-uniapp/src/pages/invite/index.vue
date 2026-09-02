<script setup lang="ts">
/* C 端邀请有礼 pages/invite/index */
import { ref } from 'vue'
import { toast } from '@/utils/nav'

const code = 'MY8888'
const copied = ref(false)
// 自定义导航栏总高（状态栏 + 44px），用于透明模式下 Hero 沉浸上移
const navH = (uni.getSystemInfoSync().statusBarHeight || 20) + 44
const rewards = [
  { icon: 'gift', title: '好友首单', desc: '好友通过您的链接首次到店消费', reward: '您得 200 积分' },
  { icon: 'wallet', title: '好友开卡', desc: '好友购买会员卡/疗程', reward: '您得 ¥200 卡余额' },
  { icon: 'link', title: '双人同行', desc: '邀请满 3 位好友', reward: '赠水光护理 1 次' },
]
function copy() {
  uni.setClipboardData({
    data: code,
    success: () => {
      copied.value = true
      setTimeout(() => (copied.value = false), 2000)
    },
  })
}
function inviteNow() { toast('请通过微信分享邀请好友') }
</script>

<template>
  <view class="inv">
    <!-- 自定义导航栏：透明底，浮于渐变 Hero 之上 -->
    <MNavbar title="邀请有礼" bg="transparent" color="#fff" />
    <!-- 主视觉（负 margin 上移沉浸到状态栏/导航栏后方） -->
    <view class="hero" :style="{ marginTop: -navH + 'px', paddingTop: navH + 'px' }">
      <view class="hero__icon"><uni-icons type="gift" size="48" color="#fff" /></view>
      <view class="hero__title">邀请好友 · 一起变美</view>
      <view class="hero__sub">好友首单立减 ¥100，您享积分/余额双重奖励</view>
      <view class="hero__code">
        <text class="hero__code-label">我的邀请码</text>
        <text class="hero__code-b">{{ code }}</text>
        <view class="hero__copy" @click="copy">{{ copied ? '已复制' : '复制' }}</view>
      </view>
    </view>

    <!-- 奖励规则 -->
    <view class="card">
      <view class="t">邀请奖励</view>
      <view v-for="(r, i) in rewards" :key="i" class="rrow">
        <view class="rrow__emoji"><uni-icons :type="r.icon" size="26" color="#ff6b9e" /></view>
        <view class="rrow__body">
          <view class="rrow__title">{{ r.title }}</view>
          <view class="rrow__desc">{{ r.desc }}</view>
        </view>
        <view class="rrow__reward">{{ r.reward }}</view>
      </view>
    </view>

    <!-- 邀请记录 -->
    <view class="card stat">
      <view class="stat__item"><text class="stat__b">6</text><text class="stat__l">已邀请</text></view>
      <view class="stat__item"><text class="stat__b">3</text><text class="stat__l">已到店</text></view>
      <view class="stat__item"><text class="stat__b">1,200</text><text class="stat__l">累计积分</text></view>
    </view>

    <view class="bottom-space"></view>
    <view class="bar">
      <view class="bar__btn" @click="inviteNow"><uni-icons type="paperplane" size="18" color="#fff" /> <text>立即邀请好友</text></view>
    </view>
  </view>
</template>

<style lang="scss" scoped>
.inv { padding-bottom: 0; }
.hero { background: linear-gradient(160deg,#FFBFF0,#FF6B9E); padding: 72rpx 48rpx 60rpx; text-align: center; color: #fff; }
.hero__icon { width: 128rpx; height: 128rpx; border-radius: 36rpx; background: rgba(255,255,255,.22); display: inline-flex; align-items: center; justify-content: center; }
.hero__title { font-size: 44rpx; font-weight: 800; margin-top: 20rpx; }
.hero__sub { font-size: 26rpx; opacity: .95; margin-top: 16rpx; }
.hero__code { display: inline-flex; align-items: center; margin-top: 40rpx; background: rgba(255,255,255,.2); border-radius: 48rpx; padding: 16rpx 16rpx 16rpx 36rpx; }
.hero__code-label { font-size: 24rpx; opacity: .9; }
.hero__code-b { font-size: 36rpx; letter-spacing: 2rpx; margin: 0 20rpx; font-weight: 700; }
.hero__copy { background: #fff; color: #ff4d6d; font-weight: 700; font-size: 26rpx; padding: 14rpx 32rpx; border-radius: 32rpx; }
.card { background: #fff; border-radius: 28rpx; margin: 24rpx; padding: 32rpx; }
.t { margin-bottom: 16rpx; font-size: 30rpx; font-weight: 700; color: #1a1a1a; }
.rrow { display: flex; align-items: center; padding: 24rpx 0; border-bottom: 1rpx solid #f5f5f5; }
.rrow:last-child { border-bottom: none; }
.rrow__emoji { width: 88rpx; height: 88rpx; border-radius: 24rpx; background: #fff0f5; display: flex; align-items: center; justify-content: center; margin-right: 24rpx; flex-shrink: 0; }
.rrow__body { flex: 1; }
.rrow__title { font-size: 28rpx; font-weight: 600; color: #1a1a1a; }
.rrow__desc { font-size: 24rpx; color: #999; margin-top: 6rpx; }
.rrow__reward { font-size: 26rpx; color: #ff4d6d; font-weight: 700; }
.stat { display: flex; }
.stat__item { flex: 1; text-align: center; }
.stat__b { display: block; font-size: 40rpx; color: #ff4d6d; font-weight: 800; }
.stat__l { font-size: 24rpx; color: #999; margin-top: 8rpx; display: block; }
.bottom-space { height: 160rpx; }
.bar { position: fixed; bottom: 0; left: 0; right: 0; width: 100%; background: #fff; border-top: 1rpx solid #eee; padding: 16rpx 32rpx calc(16rpx + env(safe-area-inset-bottom)); box-sizing: border-box; z-index: 20; }
.bar__btn { width: 100%; height: 92rpx; display: flex; align-items: center; justify-content: center; border-radius: 46rpx; background: linear-gradient(135deg,#FFBFF0,#FF6B9E); color: #fff; font-size: 32rpx; font-weight: 700; box-sizing: border-box; }
.bar__btn uni-icons { margin-right: 10rpx; }
</style>
