<script setup lang="ts">
/* 项目列表 pages/projects/list — 分类筛选 + 项目卡片流（tab 页） */
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { usePricelistStore } from '@/stores/pricelist'
import { navTo } from '@/utils/nav'

const pricelist = usePricelistStore()
onShow(() => pricelist.seed())

const cats = [
  { key: 'ALL', label: '全部' },
  { key: 'INJECTION', label: '注射美容' },
  { key: 'LASER', label: '光电仪器' },
  { key: 'SKINCARE', label: '皮肤管理' },
  { key: 'BODY', label: '形体管理' },
  { key: 'EXAM', label: '检测咨询' },
] as const
const activeCat = ref<(typeof cats)[number]['key']>('ALL')

const list = computed(() =>
  activeCat.value === 'ALL' ? pricelist.active : pricelist.active.filter((p) => p.category === activeCat.value),
)
function priceOf(p: { promoPrice: number | null; memberPrice: number }) {
  return p.promoPrice ?? p.memberPrice
}
function go(id: string) {
  navTo(`/pages/projects/detail?id=${id}`)
}
</script>

<template>
  <view class="plist">
    <MNavbar title="全部项目" />
    <!-- 分类 tab -->
    <scroll-view scroll-x class="catbar" :show-scrollbar="false">
      <view
        v-for="c in cats"
        :key="c.key"
        class="cat"
        :class="{ on: activeCat === c.key }"
        @click="activeCat = c.key"
      >{{ c.label }}</view>
    </scroll-view>

    <view class="plist__list">
      <view v-for="p in list" :key="p.id" class="pcard" @click="go(p.id)">
        <view class="pcard__img">{{ p.name.charAt(0) }}</view>
        <view class="pcard__body">
          <view class="pcard__name">{{ p.name }}</view>
          <view class="pcard__tags">
            <text class="pcard__tag"><uni-icons type="calendar" size="12" color="#999" /> {{ p.duration }}分钟</text>
            <text v-if="p.promoPrice" class="pcard__tag pcard__tag--hot">限时特惠</text>
          </view>
          <view class="pcard__price">
            <text class="pcard__now">¥{{ priceOf(p).toLocaleString() }}</text>
            <text class="pcard__old">¥{{ p.originalPrice.toLocaleString() }}</text>
            <text class="pcard__unit">/{{ p.unit }}</text>
          </view>
        </view>
      </view>
      <view v-if="!list.length" class="empty">该分类暂无在售项目</view>
    </view>
  </view>
</template>

<style lang="scss" scoped>
.plist {
  padding-bottom: 48rpx;
}
.catbar {
  position: sticky;
  top: 0;
  z-index: 5;
  background: #fff;
  white-space: nowrap;
  padding: 20rpx 24rpx;
  border-bottom: 1rpx solid #eee;
}
.cat {
  display: inline-block;
  flex-shrink: 0;
  margin-right: 16rpx;
  background: #f4f4f6;
  color: #666;
  font-size: 26rpx;
  padding: 12rpx 28rpx;
  border-radius: 32rpx;
}
.cat.on {
  background: linear-gradient(135deg, #ffbff0, #ff6b9e);
  color: #fff;
  font-weight: 600;
}
.plist__list {
  padding: 24rpx 32rpx;
  display: flex;
  flex-direction: column;
}
.pcard {
  background: #fff;
  border-radius: 28rpx;
  padding: 24rpx;
  display: flex;
  margin-bottom: 24rpx;
}
.pcard__img {
  width: 184rpx;
  height: 184rpx;
  border-radius: 20rpx;
  flex-shrink: 0;
  background: linear-gradient(135deg, #fff0f5, #ffe0ec);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 72rpx;
  color: #ff6b9e;
}
.pcard__body {
  flex: 1;
  min-width: 0;
  margin-left: 24rpx;
  display: flex;
  flex-direction: column;
}
.pcard__name {
  font-size: 30rpx;
  font-weight: 600;
  color: #1a1a1a;
}
.pcard__tags {
  display: flex;
  margin-top: 12rpx;
}
.pcard__tag {
  font-size: 22rpx;
  color: #999;
  background: #f6f6f8;
  padding: 4rpx 14rpx;
  border-radius: 12rpx;
  margin-right: 12rpx;
  display: flex;
  align-items: center;
}
.pcard__tag--hot {
  color: #ff4d6d;
  background: #fff0f3;
}
.pcard__price {
  margin-top: auto;
  display: flex;
  align-items: baseline;
}
.pcard__now {
  font-size: 38rpx;
  font-weight: 700;
  color: #ff4d6d;
  margin-right: 12rpx;
}
.pcard__old {
  font-size: 24rpx;
  color: #c0c0c0;
  text-decoration: line-through;
  margin-right: 8rpx;
}
.pcard__unit {
  font-size: 24rpx;
  color: #999;
}
.empty {
  text-align: center;
  color: #bbb;
  font-size: 28rpx;
  padding: 120rpx 0;
}
</style>
