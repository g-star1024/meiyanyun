<script setup lang="ts">
/* C 端设置 /m/settings */
import { reactive } from 'vue'
import { useRouter } from 'vue-router'
const router = useRouter()
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
</script>

<template>
  <div class="set">
    <div v-for="g in groups" :key="g.title" class="group">
      <div class="group__title">{{ g.title }}</div>
      <div class="group__card">
        <div v-for="(it, i) in g.items" :key="i" class="row">
          <span class="row__label">{{ it.label }}</span>
          <label v-if="it.toggle" class="switch" :class="{ on: toggles[it.toggle] }">
            <input type="checkbox" v-model="toggles[it.toggle]" hidden />
            <i></i>
          </label>
          <span v-else class="row__value">{{ it.value }}<b class="row__arrow">›</b></span>
        </div>
      </div>
    </div>

    <button class="logout" @click="router.push('/m')">退出登录</button>
    <div class="version">美研云会员小程序 · v1.0.0</div>
  </div>
</template>

<style scoped>
.set { padding: 12px 0 24px; }
.group { margin-bottom: 16px; }
.group__title { font-size: 12px; color: #999; padding: 0 16px 8px; }
.group__card { background: #fff; margin: 0 12px; border-radius: 14px; overflow: hidden; }
.row { display: flex; justify-content: space-between; align-items: center; padding: 15px 16px; border-bottom: .5px solid #f5f5f5; font-size: 15px; color: #333; }
.row:last-child { border-bottom: none; }
.row__value { display: flex; align-items: center; gap: 6px; font-size: 13px; color: #999; }
.row__arrow { font-size: 18px; color: #ccc; font-weight: 400; }
.switch { width: 46px; height: 28px; border-radius: 14px; background: #e4e4e7; position: relative; cursor: pointer; transition: background .2s; }
.switch i { position: absolute; top: 2px; left: 2px; width: 24px; height: 24px; border-radius: 50%; background: #fff; box-shadow: 0 1px 3px rgba(0,0,0,.2); transition: left .2s; }
.switch.on { background: #ff6b9e; }
.switch.on i { left: 20px; }
.logout { display: block; width: calc(100% - 24px); margin: 8px 12px; height: 46px; border: none; border-radius: 23px; background: #fff; color: #ff4d4f; font-size: 15px; font-weight: 600; cursor: pointer; }
.version { text-align: center; font-size: 12px; color: #ccc; margin-top: 16px; }
</style>
