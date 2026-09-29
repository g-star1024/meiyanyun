<script setup lang="ts">
/* C 端门店详情 pages/stores/detail?id= — C-B6 切真：GET /c/stores/{id}（404 中文原话 http 层弹出；hotProjects=全局 ACTIVE 前 5；无源字段空值回落 §七） */
import { onLoad } from '@dcloudio/uni-app'
import { reactive, ref } from 'vue'
import { useShopStore } from '@/stores/shop'
import { navTo, toast } from '@/utils/nav'

const shop = useShopStore()
const id = ref('')
// 自定义导航栏总高（状态栏 + 44px），用于透明模式下 Hero 沉浸上移
const navH = (uni.getSystemInfoSync().statusBarHeight || 20) + 44
onLoad((options) => {
  id.value = options?.id || ''
  load()
})

const store = reactive({
  name: '',
  addr: '',
  hours: '',
  phone: '',
  rating: null as number | null,
  sold: '',
  intro: '',
  facilities: [] as string[],
})
const hotProjects = ref<string[]>([])

async function load() {
  const d = await shop.fetchDetail(id.value)
  if (!d) return
  store.name = d.name
  store.addr = d.addr
  store.hours = d.hours
  store.phone = d.phone
  store.rating = d.rating
  store.sold = d.sold
  store.intro = d.intro
  store.facilities = d.facilities
  hotProjects.value = d.hotProjects
}

function goProjects() { navTo('/pages/projects/list') }
function call() {
  const num = store.phone.replace(/\D/g, '')
  if (!num) { toast('门店电话暂未收录'); return }
  try { uni.makePhoneCall({ phoneNumber: num }) } catch (e) { toast('拨打失败') }
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
      <view v-if="store.rating != null || store.sold" class="hero__rating">
        <template v-if="store.rating != null"><uni-icons type="star" size="14" color="#ffe08a" /> {{ store.rating }} · </template>{{ store.sold }}
      </view>
    </view>

    <view class="card info">
      <view v-if="store.addr" class="info__row"><uni-icons type="map-pin" size="15" color="#ff6b9e" /> {{ store.addr }}</view>
      <view v-if="store.hours" class="info__row"><uni-icons type="calendar" size="15" color="#ff6b9e" /> 营业时间 {{ store.hours }}</view>
      <view v-if="store.phone" class="info__row"><uni-icons type="phone" size="15" color="#ff6b9e" /> {{ store.phone }}</view>
    </view>

    <view v-if="store.facilities.length" class="card">
      <view class="t">门店设施</view>
      <view class="fac">
        <view v-for="f in store.facilities" :key="f" class="fac__item"><uni-icons type="checkmarkempty" size="13" color="#52c41a" /><text>{{ f }}</text></view>
      </view>
    </view>

    <view v-if="store.intro" class="card">
      <view class="t">门店介绍</view>
      <view class="intro">{{ store.intro }}</view>
    </view>

    <view v-if="hotProjects.length" class="card">
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
