<script setup lang="ts">
/* C 端我的 /m/me — 会员卡 + 资产 + 订单/服务入口聚合 */
import { onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { usePointsStore } from '@/stores/points'
import { useAppointmentStore } from '@/stores/appointment'
import CIcon from '@/components/CIcon.vue'

const router = useRouter()
const points = usePointsStore()
const appt = useAppointmentStore()
onMounted(() => { points.seed(); appt.seed() })

const member = computed(() => points.member)
const upcomingCount = computed(
  () => appt.appointments.filter((a) => a.customerId === member.value.memberId && (a.status === 'NEW' || a.status === 'CONFIRMED')).length,
)

// 订单状态入口（线性图标，对齐 CIcon 规范）
type IconName = 'wallet' | 'ticket' | 'check-square' | 'refund' | 'calendar' | 'order' | 'headset' | 'bell' | 'gift' | 'settings' | 'alert'
const orderEntries: { label: string; icon: IconName; to: string }[] = [
  { label: '待付款', icon: 'wallet', to: '/m/orders' },
  { label: '待核销', icon: 'ticket', to: '/m/orders' },
  { label: '已完成', icon: 'check-square', to: '/m/orders' },
  { label: '退款/售后', icon: 'refund', to: '/m/orders' },
]
// 资产入口
const assetEntries = [
  { label: '会员卡', value: '¥' + member.value.cardBalance.toLocaleString(), to: '/m/card' },
  { label: '优惠券', value: member.value.couponCount + ' 张', to: '/m/coupons' },
  { label: '积分', value: member.value.points.toLocaleString(), to: '/m/points-mall' },
  { label: '套餐疗程', value: '3 个', to: '/m/packages' },
]
// 服务入口
const serviceEntries: { label: string; icon: IconName; to: string }[] = [
  { label: '我的预约', icon: 'calendar', to: '/m/booking' },
  { label: '消费记录', icon: 'order', to: '/m/records' },
  { label: '术后回访', icon: 'check-square', to: '/m/followup' },
  { label: '专属顾问', icon: 'headset', to: '/m/advisor' },
  { label: '消息中心', icon: 'bell', to: '/m/notifications' },
  { label: '邀请有礼', icon: 'gift', to: '/m/invite' },
  { label: '设置', icon: 'settings', to: '/m/settings' },
  { label: '帮助中心', icon: 'alert', to: '/m/settings' },
]
function go(to: string) { router.push(to) }
</script>

<template>
  <div class="me">
    <!-- 会员头部 -->
    <div class="profile">
      <div class="profile__top">
        <div class="profile__avatar">{{ member.name.charAt(0) }}</div>
        <div class="profile__info">
          <div class="profile__name">{{ member.name }} <span class="profile__badge">黑金会员</span></div>
          <div class="profile__phone">{{ member.phone }}</div>
        </div>
        <span class="profile__set" @click="go('/m/settings')"><CIcon name="settings" :size="20" /></span>
      </div>
      <!-- 会员卡 -->
      <div class="vipcard" @click="go('/m/card')">
        <div class="vipcard__row">
          <span class="vipcard__label">卡余额</span>
          <span class="vipcard__value">¥{{ member.cardBalance.toLocaleString() }}</span>
        </div>
        <div class="vipcard__row vipcard__row--sub">
          <span>积分 <b>{{ member.points.toLocaleString() }}</b></span>
          <span>优惠券 <b>{{ member.couponCount }}</b> 张</span>
          <span class="vipcard__more">我的卡 ›</span>
        </div>
      </div>
    </div>

    <!-- 我的订单 -->
    <div class="card block">
      <div class="block__head">
        <span class="block__title">我的订单</span>
        <span class="block__more" @click="go('/m/orders')">全部订单 ›</span>
      </div>
      <div class="order-grid">
        <button v-for="o in orderEntries" :key="o.label" class="order-item" @click="go(o.to)">
          <span class="order-item__icon"><CIcon :name="o.icon" :size="22" /></span>
          <span class="order-item__label">{{ o.label }}</span>
        </button>
      </div>
    </div>

    <!-- 我的资产 -->
    <div class="card block">
      <div class="block__head"><span class="block__title">我的资产</span></div>
      <div class="asset-grid">
        <button v-for="a in assetEntries" :key="a.label" class="asset-item" @click="go(a.to)">
          <span class="asset-item__value">{{ a.value }}</span>
          <span class="asset-item__label">{{ a.label }}</span>
        </button>
      </div>
    </div>

    <!-- 待办提醒 -->
    <div v-if="upcomingCount" class="notice card" @click="go('/m/booking')">
      <span class="notice__icon"><CIcon name="bell" :size="18" /></span>
      <span class="notice__text">您有 <b>{{ upcomingCount }}</b> 个预约待确认/即将到店</span>
      <span class="notice__go">›</span>
    </div>

    <!-- 常用服务 -->
    <div class="card block">
      <div class="block__head"><span class="block__title">常用服务</span></div>
      <div class="svc-grid">
        <button v-for="s in serviceEntries" :key="s.label" class="svc-item" @click="go(s.to)">
          <span class="svc-item__icon"><CIcon :name="s.icon" :size="22" /></span>
          <span class="svc-item__label">{{ s.label }}</span>
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.me { padding-bottom: 24px; }
.card { background: #fff; border-radius: 14px; }

.profile { background: linear-gradient(160deg,#FFBFF0 0%,#FF6B9E 100%); padding: 20px 16px 60px; }
.profile__top { display: flex; align-items: center; gap: 12px; }
.profile__avatar { width: 58px; height: 58px; border-radius: 50%; background: rgba(255,255,255,.3); border: 2px solid rgba(255,255,255,.6); display: flex; align-items: center; justify-content: center; font-size: 24px; font-weight: 700; color: #fff; }
.profile__info { flex: 1; }
.profile__name { font-size: 19px; font-weight: 700; color: #fff; }
.profile__badge { font-size: 10px; color: #8a5a00; background: linear-gradient(135deg,#fff3d0,#ffe08a); padding: 2px 7px; border-radius: 8px; margin-left: 6px; vertical-align: middle; }
.profile__phone { font-size: 13px; color: rgba(255,255,255,.9); margin-top: 4px; }
.profile__set { color: #fff; cursor: pointer; display: inline-flex; align-items: center; }
.vipcard { background: rgba(255,255,255,.18); backdrop-filter: blur(6px); border: 1px solid rgba(255,255,255,.25); border-radius: 14px; padding: 14px 16px; margin-top: 16px; cursor: pointer; }
.vipcard__row { display: flex; align-items: baseline; gap: 10px; }
.vipcard__label { font-size: 12px; color: rgba(255,255,255,.9); }
.vipcard__value { font-size: 26px; font-weight: 800; color: #fff; }
.vipcard__row--sub { display: flex; gap: 18px; margin-top: 10px; font-size: 12px; color: rgba(255,255,255,.92); }
.vipcard__row--sub b { color: #fff; font-weight: 700; }
.vipcard__more { margin-left: auto; font-size: 12px; }

.block { margin: -44px 12px 12px; padding: 16px; position: relative; }
.block + .block { margin-top: 0; }
.block__head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.block__title { font-size: 16px; font-weight: 700; color: #1a1a1a; }
.block__more { font-size: 12px; color: #999; cursor: pointer; }

.order-grid, .svc-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px 4px; }
.order-item, .svc-item, .asset-item { border: none; background: transparent; display: flex; flex-direction: column; align-items: center; gap: 6px; cursor: pointer; }
.order-item__icon, .svc-item__icon { width: 42px; height: 42px; border-radius: 13px; background: #fff0f5; color: #ff6b9e; display: flex; align-items: center; justify-content: center; }
.order-item__label, .svc-item__label { font-size: 12px; color: #555; }

.asset-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; }
.asset-item { gap: 3px; }
.asset-item__value { font-size: 16px; font-weight: 700; color: #ff4d6d; }
.asset-item__label { font-size: 12px; color: #888; }

.notice { margin: 0 12px 12px; padding: 14px; display: flex; align-items: center; gap: 10px; cursor: pointer; }
.notice__icon { color: #ff6b9e; display: inline-flex; align-items: center; }
.notice__text { flex: 1; font-size: 13px; color: #555; }
.notice__text b { color: #ff4d6d; }
.notice__go { font-size: 18px; color: #ccc; }

.svc-grid { gap: 18px 4px; }
</style>
