<script setup lang="ts">
/* C 端设置 pages/settings/index */
import { reactive } from 'vue'
import { navTo, toast } from '@/utils/nav'

const toggles = reactive({ order: true, appt: true, promo: false })
type Row = { label: string; value?: string; toggle?: keyof typeof toggles }
const groups: { title: string; items: Row[] }[] = [
  { title: '账户与安全', items: [
    { label: '手机号', value: '138****1234' },
    { label: '实名认证', value: '已认证' },
    { label: '收货地址', value: '' },
  ]},
  { title: '消息通知', items: [
    { label: '订单状态通知', toggle: 'order' },
    { label: '预约提醒', toggle: 'appt' },
    { label: '优惠活动推送', toggle: 'promo' },
  ]},
  { title: '通用', items: [
    { label: '清除缓存', value: '12.6 MB' },
    { label: '关于美研云', value: 'v1.0.0' },
    { label: '帮助中心', value: '' },
  ]},
]

function onToggle(key: keyof typeof toggles) {
  toggles[key] = !toggles[key]
}
function onRow(it: Row) {
  if (it.toggle) return
  if (it.label === '清除缓存') { toast('缓存已清除'); return }
  toast(it.value || '敬请期待')
}
function logout() { navTo('/pages/home/index') }
</script>

<template>
  <view class="set">
    <MNavbar title="设置" />
    <view v-for="g in groups" :key="g.title" class="group">
      <view class="group__title">{{ g.title }}</view>
      <view class="group__card">
        <view v-for="(it, i) in g.items" :key="i" class="row" @click="onRow(it)">
          <text class="row__label">{{ it.label }}</text>
          <view v-if="it.toggle" class="switch" :class="{ on: toggles[it.toggle] }" @click.stop="onToggle(it.toggle)">
            <view class="switch__dot"></view>
          </view>
          <view v-else class="row__value">
            <text>{{ it.value }}</text>
            <text class="row__arrow">›</text>
          </view>
        </view>
      </view>
    </view>

    <view class="logout" @click="logout">退出登录</view>
    <view class="version">美研云会员小程序 · v1.0.0</view>
  </view>
</template>

<style lang="scss" scoped>
.set { padding: 24rpx 0 48rpx; }
.group { margin-bottom: 32rpx; }
.group__title { font-size: 24rpx; color: #999; padding: 0 32rpx 16rpx; }
.group__card { background: #fff; margin: 0 24rpx; border-radius: 28rpx; overflow: hidden; }
.row { display: flex; justify-content: space-between; align-items: center; padding: 30rpx 32rpx; border-bottom: 1rpx solid #f5f5f5; font-size: 30rpx; color: #333; }
.row:last-child { border-bottom: none; }
.row__label { font-size: 30rpx; color: #333; }
.row__value { display: flex; align-items: center; font-size: 26rpx; color: #999; }
.row__arrow { font-size: 36rpx; color: #ccc; margin-left: 12rpx; }
.switch { width: 92rpx; height: 56rpx; border-radius: 28rpx; background: #e4e4e7; position: relative; transition: background .2s; }
.switch__dot { position: absolute; top: 4rpx; left: 4rpx; width: 48rpx; height: 48rpx; border-radius: 50%; background: #fff; box-shadow: 0 2rpx 6rpx rgba(0,0,0,.2); transition: left .2s; }
.switch.on { background: #ff6b9e; }
.switch.on .switch__dot { left: 40rpx; }
.logout { width: calc(100% - 48rpx); margin: 16rpx 24rpx; height: 92rpx; line-height: 92rpx; text-align: center; border-radius: 46rpx; background: #fff; color: #ff4d4f; font-size: 30rpx; font-weight: 600; box-sizing: border-box; }
.version { text-align: center; font-size: 24rpx; color: #ccc; margin-top: 32rpx; }
</style>
