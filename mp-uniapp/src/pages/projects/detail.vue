<script setup lang="ts">
/* 项目详情 pages/projects/detail — 粉色渐变 Hero + 项目信息 + 底部购买/预约 */
import { computed, ref } from 'vue'
import { onShow, onLoad } from '@dcloudio/uni-app'
import { usePricelistStore } from '@/stores/pricelist'
import { navTo } from '@/utils/nav'

const pricelist = usePricelistStore()
// 自定义导航栏总高（状态栏 + 44px），用于透明模式下 Hero 沉浸上移
const navH = (uni.getSystemInfoSync().statusBarHeight || 20) + 44
const id = ref('')
onLoad((options) => {
  id.value = options?.id || ''
})
onShow(() => pricelist.seed())

const item = computed(() => pricelist.get(id.value))
const price = computed(() => item.value?.promoPrice ?? item.value?.memberPrice ?? 0)

const features = [
  '正品保障 · 药品/仪器全程可溯源',
  '执业医师面诊定制方案',
  '术前检测 + 术后修复全程跟进',
  '支持会员卡余额 / 疗程次卡抵扣',
]
function buy() {
  if (item.value) navTo(`/pages/projects/buy?id=${item.value.id}`)
}
function book() {
  if (item.value) navTo(`/pages/booking/new?project=${encodeURIComponent(item.value.name)}`)
}
</script>

<template>
  <!-- 自定义导航栏：透明底，浮于渐变 Hero 之上 -->
  <MNavbar title="项目详情" bg="transparent" color="#fff" />
  <view v-if="item" class="pdetail">
    <!-- 粉色渐变 Hero（负 margin 上移沉浸到状态栏/导航栏后方） -->
    <view class="hero" :style="{ marginTop: -navH + 'px', paddingTop: navH + 'px' }">
      <view class="hero__img">{{ item.name.charAt(0) }}</view>
    </view>

    <!-- 价格卡 -->
    <view class="price-card">
      <view class="price-card__row">
        <text class="price-card__now">¥{{ price.toLocaleString() }}</text>
        <text class="price-card__old">¥{{ item.originalPrice.toLocaleString() }}</text>
        <text v-if="item.promoPrice" class="price-card__promo">限时特惠</text>
      </view>
      <view class="price-card__member">会员价 ¥{{ item.memberPrice.toLocaleString() }} / {{ item.unit }}</view>
      <view class="price-card__name">{{ item.name }}</view>
      <view class="price-card__meta">
        <view class="price-card__ic"><uni-icons type="calendar" size="13" color="#888" /></view>
        <text>服务时长 {{ item.duration }} 分钟</text>
        <text>·</text>
        <text>{{ item.unit }}</text>
      </view>
    </view>

    <!-- 项目介绍 -->
    <view class="block">
      <view class="block__title">项目介绍</view>
      <view class="block__text">
        <text>{{ item.name }}由专业医师操作，针对您的肤质与需求定制个性化方案。全程使用正品仪器与耗材，术前进行专业皮肤检测评估，术中严格无菌操作，术后配备修复护理与随访，确保安全与效果。</text>
      </view>
    </view>

    <!-- 服务保障 -->
    <view class="block">
      <view class="block__title">服务保障</view>
      <view class="feat">
        <view v-for="f in features" :key="f" class="feat__item">
          <text class="feat__check">✓</text>
          <text>{{ f }}</text>
        </view>
      </view>
    </view>

    <!-- 推荐门店 -->
    <view class="block" @click="navTo('/pages/stores/list')">
      <view class="block__title">
        <text>可服务门店</text>
        <text class="block__more">查看全部 ›</text>
      </view>
      <view class="store-row">
        <view class="store-row__icon"><uni-icons type="shop" size="24" color="#ff6b9e" /></view>
        <view class="store-row__body">
          <view class="store-row__name">上海静安旗舰店</view>
          <view class="store-row__sub">静安区南京西路 1266 号 · 距您 1.2km</view>
        </view>
        <text class="store-row__go">›</text>
      </view>
    </view>

    <view class="bottom-space"></view>

    <!-- 底部操作栏 -->
    <view class="action-bar">
      <view class="action-bar__btn" @click="book">
        <uni-icons type="calendar" size="20" color="#666" />
        <text class="action-bar__txt">预约</text>
      </view>
      <view class="action-bar__btn" @click="navTo('/pages/advisor/index')">
        <uni-icons type="chat" size="20" color="#666" />
        <text class="action-bar__txt">咨询</text>
      </view>
      <view class="action-bar__buy" @click="buy">立即购买</view>
    </view>
  </view>

  <view v-else class="nf">项目不存在或已下架</view>
</template>

<style lang="scss" scoped>
.pdetail {
  padding-bottom: 0;
}
.hero {
  height: 560rpx;
  background: linear-gradient(160deg, #ffbff0 0%, #ff6b9e 100%);
  display: flex;
  align-items: center;
  justify-content: center;
}
.hero__img {
  width: 260rpx;
  height: 260rpx;
  border-radius: 48rpx;
  background: rgba(255, 255, 255, 0.28);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 112rpx;
  color: #fff;
}
.price-card {
  background: #fff;
  margin: -40rpx 24rpx 0;
  border-radius: 32rpx;
  padding: 32rpx;
  position: relative;
}
.price-card__row {
  display: flex;
  align-items: baseline;
}
.price-card__now {
  font-size: 56rpx;
  font-weight: 800;
  color: #ff4d6d;
  margin-right: 16rpx;
}
.price-card__old {
  font-size: 28rpx;
  color: #c0c0c0;
  text-decoration: line-through;
  margin-right: 16rpx;
}
.price-card__promo {
  font-size: 22rpx;
  color: #fff;
  background: linear-gradient(135deg, #ffbff0, #ff6b9e);
  padding: 4rpx 16rpx;
  border-radius: 16rpx;
}
.price-card__member {
  font-size: 24rpx;
  color: #ff8c42;
  margin-top: 8rpx;
}
.price-card__name {
  font-size: 38rpx;
  font-weight: 700;
  color: #1a1a1a;
  margin: 24rpx 0 16rpx;
}
.price-card__meta {
  display: flex;
  align-items: center;
  font-size: 26rpx;
  color: #888;
}
.price-card__meta text {
  margin-right: 16rpx;
}
.price-card__ic {
  margin-right: 8rpx;
  display: flex;
  align-items: center;
}
.block {
  background: #fff;
  margin: 24rpx;
  border-radius: 28rpx;
  padding: 32rpx;
}
.block__title {
  margin-bottom: 24rpx;
  font-size: 30rpx;
  font-weight: 700;
  color: #1a1a1a;
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.block__more {
  font-size: 24rpx;
  color: #999;
  font-weight: 400;
}
.block__text {
  font-size: 28rpx;
  color: #666;
  line-height: 1.7;
}
.feat {
  display: flex;
  flex-direction: column;
}
.feat__item {
  display: flex;
  align-items: center;
  font-size: 26rpx;
  color: #555;
  margin-bottom: 20rpx;
}
.feat__item:last-child {
  margin-bottom: 0;
}
.feat__check {
  width: 36rpx;
  height: 36rpx;
  border-radius: 50%;
  background: #fff0f5;
  color: #ff6b9e;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22rpx;
  flex-shrink: 0;
  margin-right: 16rpx;
}
.store-row {
  display: flex;
  align-items: center;
}
.store-row__icon {
  width: 88rpx;
  height: 88rpx;
  border-radius: 28rpx;
  background: #fff0f5;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-right: 24rpx;
  flex-shrink: 0;
}
.store-row__body {
  flex: 1;
}
.store-row__name {
  font-size: 28rpx;
  font-weight: 600;
  color: #1a1a1a;
}
.store-row__sub {
  font-size: 24rpx;
  color: #999;
  margin-top: 6rpx;
}
.store-row__go {
  font-size: 40rpx;
  color: #ccc;
}
.bottom-space {
  height: 160rpx;
}
.action-bar {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  width: 100%;
  background: #fff;
  border-top: 1rpx solid #eee;
  padding: 16rpx 24rpx calc(16rpx + env(safe-area-inset-bottom));
  display: flex;
  align-items: center;
  box-sizing: border-box;
  z-index: 20;
}
.action-bar__btn {
  background: #f6f6f8;
  border-radius: 20rpx;
  padding: 12rpx 28rpx;
  margin-right: 20rpx;
  display: flex;
  flex-direction: column;
  align-items: center;
  color: #666;
}
.action-bar__txt {
  font-size: 22rpx;
  margin-top: 4rpx;
}
.action-bar__buy {
  flex: 1;
  height: 88rpx;
  line-height: 88rpx;
  text-align: center;
  border-radius: 44rpx;
  background: linear-gradient(135deg, #ffbff0, #ff6b9e);
  color: #fff;
  font-size: 32rpx;
  font-weight: 700;
}
.nf {
  text-align: center;
  color: #bbb;
  padding: 200rpx 0;
}
</style>
