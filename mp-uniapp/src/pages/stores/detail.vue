<script setup lang="ts">
/* C 端门店详情 pages/stores/detail?id= */
import { onLoad } from '@dcloudio/uni-app'
import { ref } from 'vue'
import { navTo, toast } from '@/utils/nav'

const id = ref('')
// 自定义导航栏总高（状态栏 + 44px），用于透明模式下 Hero 沉浸上移
const navH = (uni.getSystemInfoSync().statusBarHeight || 20) + 44
onLoad((options) => {
  id.value = options?.id || ''
})

const store = {
  name: '上海静安旗舰店',
  addr: '静安区南京西路 1266 号恒隆广场 B1',
  hours: '10:00 - 21:00',
  phone: '021-6288-1234',
  rating: 4.9,
  sold: '月服务 2,300+ 人次',
  intro: '美研云旗舰医美中心，配备热玛吉 FLX、M22 光子、VISIA 皮肤检测等高端光电设备，拥有 12 位执业医师团队，提供注射、光电、皮肤管理、形体塑形一站式服务。',
  facilities: ['免费停车', 'VIP 休息室', '术后修复区', '皮肤检测室', '无菌手术室'],
}
const hotProjects = ['热玛吉紧致', '光子嫩肤', '玻尿酸填充', '水光焕肤', 'VISIA 检测']

function goProjects() { navTo('/pages/projects/list') }
function call() {
  try { uni.makePhoneCall({ phoneNumber: '02162881234' }) } catch (e) { toast('拨打失败') }
}
function navigate() { toast('即将打开地图导航') }
function book() { navTo('/pages/booking/new') }
</script>

<template>
  <view class="sd">
    <!-- 自定义导航栏：透明底，浮于渐变 Hero 之上 -->
    <MNavbar title="门店详情" bg="transparent" color="#fff" />
    <!-- 渐变 Hero（负 margin 上移沉浸到状态栏/导航栏后方） -->
    <view class="hero" :style="{ marginTop: -navH + 'px', paddingTop: navH + 'px' }">
      <view class="hero__icon"><uni-icons type="shop" size="48" color="#fff" /></view>
      <view class="hero__name">{{ store.name }}</view>
      <view class="hero__rating"><uni-icons type="star" size="14" color="#ffe08a" /> {{ store.rating }} · {{ store.sold }}</view>
    </view>

    <view class="card info">
      <view class="info__row"><uni-icons type="map-pin" size="15" color="#ff6b9e" /> {{ store.addr }}</view>
      <view class="info__row"><uni-icons type="calendar" size="15" color="#ff6b9e" /> 营业时间 {{ store.hours }}</view>
      <view class="info__row"><uni-icons type="phone" size="15" color="#ff6b9e" /> {{ store.phone }}</view>
    </view>

    <view class="card">
      <view class="t">门店设施</view>
      <view class="fac">
        <view v-for="f in store.facilities" :key="f" class="fac__item"><uni-icons type="checkmarkempty" size="13" color="#52c41a" /><text>{{ f }}</text></view>
      </view>
    </view>

    <view class="card">
      <view class="t">门店介绍</view>
      <view class="intro">{{ store.intro }}</view>
    </view>

    <view class="card">
      <view class="t">热门项目</view>
      <view class="hot">
        <view v-for="p in hotProjects" :key="p" class="hot__item" @click="goProjects">{{ p }} ›</view>
      </view>
    </view>

    <view class="bottom-space"></view>
    <view class="bar">
      <view class="bar__call" @click="call"><uni-icons type="phone" size="16" color="#666" /><text>电话</text></view>
      <view class="bar__nav" @click="navigate"><uni-icons type="navigate" size="16" color="#666" /><text>导航</text></view>
      <view class="bar__book" @click="book">预约到店</view>
    </view>
  </view>
</template>

<style lang="scss" scoped>
.sd { padding-bottom: 0; }
.hero { background: linear-gradient(160deg,#FFBFF0,#FF6B9E); padding: 60rpx 40rpx 72rpx; text-align: center; color: #fff; }
.hero__icon { width: 128rpx; height: 128rpx; border-radius: 36rpx; background: rgba(255,255,255,.22); display: inline-flex; align-items: center; justify-content: center; }
.hero__name { font-size: 40rpx; font-weight: 700; margin-top: 16rpx; }
.hero__rating { font-size: 26rpx; margin-top: 12rpx; opacity: .95; display: flex; align-items: center; justify-content: center; }
.card { background: #fff; border-radius: 28rpx; margin: 24rpx; padding: 32rpx; }
.info__row { font-size: 28rpx; color: #555; line-height: 2.1; display: flex; align-items: center; }
.t { margin-bottom: 24rpx; font-size: 30rpx; font-weight: 700; color: #1a1a1a; }
.fac { display: flex; flex-wrap: wrap; }
.fac__item { font-size: 24rpx; color: #666; background: #f6f6f8; padding: 12rpx 24rpx; border-radius: 28rpx; margin: 0 16rpx 16rpx 0; display: inline-flex; align-items: center; }
.intro { font-size: 28rpx; color: #666; line-height: 1.7; }
.hot { display: flex; flex-direction: column; }
.hot__item { background: #fafafa; border-radius: 20rpx; padding: 26rpx 28rpx; margin-bottom: 16rpx; font-size: 28rpx; color: #333; }
.bottom-space { height: 160rpx; }
.bar { position: fixed; bottom: 0; left: 0; right: 0; width: 100%; background: #fff; border-top: 1rpx solid #eee; padding: 16rpx 24rpx calc(16rpx + env(safe-area-inset-bottom)); display: flex; box-sizing: border-box; z-index: 20; }
.bar__call, .bar__nav { width: 128rpx; height: 88rpx; display: flex; flex-direction: column; align-items: center; justify-content: center; border: 1rpx solid #eee; background: #fff; border-radius: 44rpx; font-size: 22rpx; color: #666; margin-right: 16rpx; box-sizing: border-box; }
.bar__book { flex: 1; height: 88rpx; line-height: 88rpx; text-align: center; border-radius: 44rpx; background: linear-gradient(135deg,#FFBFF0,#FF6B9E); color: #fff; font-size: 32rpx; font-weight: 700; }
</style>
