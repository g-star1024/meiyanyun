<script setup lang="ts">
/* 自定义导航栏：状态栏占位 + 44px 白底导航栏（左返回键 + 居中标题）。
 * 对齐方案 A 微信小程序外壳。全局 pages.json 已设 navigationStyle:custom。
 * 返回逻辑：能后退就后退，否则回首页 tab。 */
import { ref } from 'vue'
import { navigateBack } from '@/utils/nav'

const props = withDefaults(
  defineProps<{
    title?: string
    showBack?: boolean
    bg?: string // 背景色，默认白；首页渐变可传 transparent/渐变色
    color?: string // 标题/返回色，默认 #1a1a1a
    placeholderOnly?: boolean // 仅状态栏占位（用于自带头部的首页），不渲染导航栏
  }>(),
  { title: '', showBack: true, bg: '#ffffff', color: '#1a1a1a', placeholderOnly: false },
)

const sys = uni.getSystemInfoSync()
const statusBarHeight = ref(sys.statusBarHeight || 20)
// 导航栏内容高度：微信标准 44px
const navBarHeight = 44
// 右侧胶囊预留宽度（胶囊约 87px 宽 + 右边距），标题居中时避让
const capsuleGap = 96

function onBack() {
  navigateBack('/pages/home/index')
}
</script>

<template>
  <view class="mnav-wrap" :style="{ '--mnav-h': (statusBarHeight + (placeholderOnly ? 0 : navBarHeight)) + 'px' }">
    <!-- 占位块（撑开文档流）：仅占位模式只占状态栏高度，否则占 状态栏+44px -->
    <view :style="{ height: (statusBarHeight + (placeholderOnly ? 0 : navBarHeight)) + 'px' }"></view>
    <!-- fixed 导航栏（占位模式只保留状态栏底色） -->
    <view class="mnav" :style="{ background: bg }">
      <view class="mnav__status" :style="{ height: statusBarHeight + 'px' }"></view>
      <view v-if="!placeholderOnly" class="mnav__bar" :style="{ height: navBarHeight + 'px' }">
        <view
          v-if="showBack"
          class="mnav__back"
          :style="{ color }"
          hover-class="mnav__back--hover"
          @click="onBack"
        >
          <uni-icons type="left" size="22" :color="color" />
        </view>
        <view
          class="mnav__title"
          :style="{ color, paddingLeft: (showBack ? 72 : 0) + 'rpx', paddingRight: capsuleGap + 'rpx' }"
        >{{ title }}</view>
      </view>
    </view>
  </view>
</template>

<style lang="scss" scoped>
.mnav-wrap {
  position: relative;
  z-index: 999;
}
.mnav {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  z-index: 999;
  background: #fff;
}
.mnav__bar {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
}
.mnav__back {
  position: absolute;
  left: 8rpx;
  top: 0;
  bottom: 0;
  width: 72rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
}
.mnav__back--hover {
  background: rgba(0, 0, 0, 0.05);
}
.mnav__title {
  flex: 1;
  font-size: 32rpx;
  font-weight: 600;
  text-align: center;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
</style>
