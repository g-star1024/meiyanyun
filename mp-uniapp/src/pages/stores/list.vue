<script setup lang="ts">
/* C 端门店列表 pages/stores/list */
import { ref, computed } from 'vue'
import { navTo } from '@/utils/nav'

const keyword = ref('')
const stores = [
  { id: 's1', name: '上海静安旗舰店', addr: '静安区南京西路 1266 号恒隆广场', distance: '1.2km', tags: ['旗舰店', '光电中心', '营业中'], hours: '10:00-21:00', phone: '021-6288-1234', rating: 4.9 },
  { id: 's2', name: '上海徐汇万象城店', addr: '徐汇区淮海中路 999 号环贸 iapm', distance: '3.8km', tags: ['医美中心', '营业中'], hours: '10:00-21:00', phone: '021-5456-7890', rating: 4.8 },
  { id: 's3', name: '上海浦东陆家嘴店', addr: '浦东新区世纪大道 8 号国金中心', distance: '5.5km', tags: ['精品店', '营业中'], hours: '10:00-22:00', phone: '021-2059-4567', rating: 4.7 },
  { id: 's4', name: '上海虹桥天地店', addr: '闵行区申长路 99 弄虹桥天地', distance: '12km', tags: ['社区店', '营业中'], hours: '10:00-20:30', phone: '021-6220-8888', rating: 4.6 },
]
const list = computed(() => stores.filter((s) => !keyword.value || s.name.includes(keyword.value) || s.addr.includes(keyword.value)))
function go(id: string) { navTo(`/pages/stores/detail?id=${id}`) }
</script>

<template>
  <view class="stores">
    <MNavbar title="附近门店" />
    <view class="search">
      <uni-icons class="search__icon" type="search" size="16" color="#999" />
      <input v-model="keyword" class="search__input" placeholder="搜索门店 / 商圈" />
    </view>
    <view class="list">
      <view v-for="s in list" :key="s.id" class="scard" @click="go(s.id)">
        <view class="scard__img"><uni-icons type="shop" size="30" color="#ff6b9e" /></view>
        <view class="scard__body">
          <view class="scard__name">{{ s.name }} <text class="scard__rating"><uni-icons type="star" size="13" color="#f5a623" /> {{ s.rating }}</text></view>
          <view class="scard__tags">
            <text v-for="t in s.tags" :key="t" class="scard__tag" :class="{ open: t.includes('营业') }">{{ t }}</text>
          </view>
          <view class="scard__addr"><uni-icons type="map-pin" size="13" color="#999" /> {{ s.addr }}</view>
          <view class="scard__meta"><uni-icons type="calendar" size="12" color="#aaa" /> {{ s.hours }} · <uni-icons type="phone" size="12" color="#aaa" /> {{ s.phone }}</view>
        </view>
        <view class="scard__dist">{{ s.distance }}</view>
      </view>
    </view>
  </view>
</template>

<style lang="scss" scoped>
.stores { padding-bottom: 48rpx; }
.search { margin: 24rpx 32rpx; background: #fff; border-radius: 40rpx; padding: 18rpx 32rpx; display: flex; align-items: center; }
.search__icon { margin-right: 16rpx; }
.search__input { flex: 1; font-size: 28rpx; }
.list { padding: 0 32rpx; display: flex; flex-direction: column; }
.scard { background: #fff; border-radius: 28rpx; padding: 28rpx; display: flex; margin-bottom: 24rpx; position: relative; }
.scard__img { width: 120rpx; height: 120rpx; border-radius: 20rpx; background: #fff0f5; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.scard__body { flex: 1; min-width: 0; margin-left: 24rpx; }
.scard__name { font-size: 30rpx; font-weight: 700; color: #1a1a1a; }
.scard__rating { font-size: 24rpx; color: #f5a623; font-weight: 500; margin-left: 12rpx; display: inline-flex; align-items: center; }
.scard__tags { display: flex; margin-top: 12rpx; flex-wrap: wrap; }
.scard__tag { font-size: 20rpx; color: #999; background: #f5f5f7; padding: 4rpx 14rpx; border-radius: 12rpx; margin-right: 10rpx; }
.scard__tag.open { color: #52c41a; background: #f0fff0; }
.scard__addr { font-size: 24rpx; color: #666; margin-top: 16rpx; display: flex; align-items: center; }
.scard__meta { font-size: 22rpx; color: #aaa; margin-top: 8rpx; display: flex; align-items: center; flex-wrap: wrap; }
.scard__dist { position: absolute; top: 28rpx; right: 28rpx; font-size: 24rpx; color: #ff6b9e; font-weight: 600; }
</style>
