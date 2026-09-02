<script setup lang="ts">
/* C 端首页 /m — 微信小程序风格（轮播 + 快捷入口 + 推荐项目 + 最近预约） */
import { onMounted, computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { usePointsStore } from '@/stores/points'
import { useAppointmentStore } from '@/stores/appointment'
import { usePricelistStore } from '@/stores/pricelist'
import CIcon from '@/components/CIcon.vue'

const router = useRouter()
const points = usePointsStore()
const appt = useAppointmentStore()
const pricelist = usePricelistStore()

onMounted(() => {
  points.seed()
  appt.seed()
  pricelist.seed()
})

const member = computed(() => points.member)

// 轮播
const banners = [
  { title: '新人专享 · 皮肤检测免费领', sub: '到店即送 VISIA 深度检测 1 次', bg: 'linear-gradient(135deg,#FFBFF0,#FF6B9E)' },
  { title: '热玛吉紧致疗程', sub: '3 次疗程立省 ¥17,600', bg: 'linear-gradient(135deg,#ffd1e3,#ff9ebc)' },
  { title: '闺蜜同行 次卡共享', sub: '10 次水光 2 人拼团 ¥3,980', bg: 'linear-gradient(135deg,#e0d4ff,#b7a6ff)' },
]
const bannerIdx = ref(0)

// 快捷入口（线性图标，对齐 CIcon 规范）
type IconName = 'calendar' | 'beauty' | 'store' | 'order' | 'ticket' | 'gift' | 'headset' | 'share'
const quicks: { label: string; icon: IconName; to: string }[] = [
  { label: '在线预约', icon: 'calendar', to: '/m/booking' },
  { label: '全部项目', icon: 'beauty', to: '/m/projects' },
  { label: '门店', icon: 'store', to: '/m/stores' },
  { label: '我的订单', icon: 'order', to: '/m/orders' },
  { label: '优惠券', icon: 'ticket', to: '/m/coupons' },
  { label: '积分商城', icon: 'gift', to: '/m/points-mall' },
  { label: '专属顾问', icon: 'headset', to: '/m/advisor' },
  { label: '邀请有礼', icon: 'share', to: '/m/invite' },
]

// 推荐项目（取价目表在售 + 有促销价优先）
const recommends = computed(() =>
  pricelist.active
    .slice()
    .sort((a, b) => (b.promoPrice ? 1 : 0) - (a.promoPrice ? 1 : 0))
    .slice(0, 6),
)
function priceOf(p: { promoPrice: number | null; memberPrice: number }) {
  return p.promoPrice ?? p.memberPrice
}

// 最近预约
const upcoming = computed(() =>
  appt.appointments
    .filter((a) => a.customerId === member.value.memberId && (a.status === 'CONFIRMED' || a.status === 'NEW'))
    .slice(0, 1),
)

function go(to: string) {
  router.push(to)
}
function goProject(id: string) {
  router.push(`/m/project/${id}`)
}
</script>

<template>
  <div class="home">
    <!-- 顶部会员条 -->
    <div class="memberbar">
      <div class="memberbar__user" @click="go('/m/me')">
        <div class="memberbar__avatar">{{ member.name.charAt(0) }}</div>
        <div class="memberbar__info">
          <div class="memberbar__name">{{ member.name }} <span class="memberbar__level">黑金会员</span></div>
          <div class="memberbar__sub">愿你今天也美丽</div>
        </div>
      </div>
      <div class="memberbar__bell" @click="go('/m/notifications')">
        <CIcon name="bell" :size="22" />
        <i class="memberbar__dot"></i>
      </div>
    </div>

    <!-- 轮播 -->
    <div class="banner-wrap">
      <div class="banner" :style="{ background: banners[bannerIdx].bg }">
        <div class="banner__title">{{ banners[bannerIdx].title }}</div>
        <div class="banner__sub">{{ banners[bannerIdx].sub }}</div>
      </div>
      <div class="banner__dots">
        <i v-for="(_, i) in banners" :key="i" :class="{ on: i === bannerIdx }" @click="bannerIdx = i"></i>
      </div>
    </div>

    <!-- 快捷入口 -->
    <div class="quick card">
      <button v-for="q in quicks" :key="q.label" class="quick__item" @click="go(q.to)">
        <span class="quick__icon"><CIcon :name="q.icon" :size="24" /></span>
        <span class="quick__label">{{ q.label }}</span>
      </button>
    </div>

    <!-- 最近预约 -->
    <div v-if="upcoming.length" class="appt-card card" @click="go('/m/booking')">
      <div class="appt-card__tag">即将到店</div>
      <div class="appt-card__body">
        <div class="appt-card__title">{{ upcoming[0].project || '到店服务' }}</div>
        <div class="appt-card__meta"><CIcon name="clock" :size="13" /> {{ upcoming[0].timeSlot?.slice(5, 16).replace('T', ' ') || '今日' }} · 上海静安旗舰店</div>
      </div>
      <span class="appt-card__go">详情 ›</span>
    </div>

    <!-- 推荐项目 -->
    <div class="sec-head">
      <h3><CIcon name="star" :size="16" class="sec-head__ico" /> 热门推荐</h3>
      <span class="sec-head__more" @click="go('/m/projects')">全部项目 ›</span>
    </div>
    <div class="proj-list">
      <div v-for="p in recommends" :key="p.id" class="proj card" @click="goProject(p.id)">
        <div class="proj__img">{{ p.name.charAt(0) }}</div>
        <div class="proj__body">
          <div class="proj__name">{{ p.name }}</div>
          <div class="proj__meta">{{ p.duration }}分钟 · {{ p.unit }}</div>
          <div class="proj__price">
            <span class="proj__now">¥{{ priceOf(p).toLocaleString() }}</span>
            <span v-if="p.promoPrice" class="proj__old">¥{{ p.originalPrice.toLocaleString() }}</span>
            <span v-if="p.promoPrice" class="proj__tag">限时</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 门店入口 -->
    <div class="store-card card" @click="go('/m/stores')">
      <span class="store-card__icon"><CIcon name="store" :size="24" /></span>
      <div class="store-card__body">
        <div class="store-card__name">附近门店</div>
        <div class="store-card__sub">上海静安旗舰店 · 距您 1.2km</div>
      </div>
      <CIcon name="chevron-right" :size="18" class="store-card__go" />
    </div>
  </div>
</template>

<style scoped>
.home { padding: 0 0 24px; }
.card { background: #fff; border-radius: 14px; }

.memberbar { display: flex; align-items: center; justify-content: space-between; padding: 14px 16px 12px; background: #fff; }
.memberbar__user { display: flex; align-items: center; gap: 10px; cursor: pointer; }
.memberbar__avatar { width: 42px; height: 42px; border-radius: 50%; background: linear-gradient(135deg,#FFBFF0,#FF6B9E); color: #fff; display: flex; align-items: center; justify-content: center; font-size: 17px; font-weight: 700; }
.memberbar__name { font-size: 16px; font-weight: 700; color: #1a1a1a; }
.memberbar__level { font-size: 10px; color: #b8860b; background: #fff6e0; padding: 1px 6px; border-radius: 6px; margin-left: 4px; font-weight: 500; }
.memberbar__sub { font-size: 12px; color: #999; margin-top: 2px; }
.memberbar__bell { position: relative; color: #333; cursor: pointer; }
.memberbar__dot { position: absolute; top: -1px; right: -1px; width: 8px; height: 8px; background: #ff4d4f; border: 1.5px solid #fff; border-radius: 50%; }

.banner-wrap { padding: 8px 16px 0; }
.banner { height: 120px; border-radius: 14px; padding: 22px 20px; color: #fff; box-sizing: border-box; display: flex; flex-direction: column; justify-content: center; }
.banner__title { font-size: 19px; font-weight: 700; text-shadow: 0 1px 3px rgba(0,0,0,.08); }
.banner__sub { font-size: 13px; margin-top: 6px; opacity: .95; }
.banner__dots { display: flex; gap: 5px; justify-content: center; margin-top: 8px; }
.banner__dots i { width: 6px; height: 6px; border-radius: 50%; background: #d8d8d8; cursor: pointer; }
.banner__dots i.on { width: 16px; border-radius: 3px; background: #ff6b9e; }

.quick { margin: 14px 16px; padding: 16px 8px; display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px 4px; }
.quick__item { border: none; background: transparent; display: flex; flex-direction: column; align-items: center; gap: 6px; cursor: pointer; }
.quick__icon { width: 44px; height: 44px; border-radius: 14px; background: #fff0f5; color: #ff6b9e; display: flex; align-items: center; justify-content: center; }
.quick__label { font-size: 12px; color: #555; }

.appt-card { margin: 0 16px 14px; padding: 14px; display: flex; align-items: center; gap: 12px; position: relative; cursor: pointer; }
.appt-card__tag { position: absolute; top: 0; left: 14px; transform: translateY(-50%); font-size: 10px; color: #fff; background: #ff6b9e; padding: 2px 8px; border-radius: 8px; }
.appt-card__body { flex: 1; margin-top: 4px; }
.appt-card__title { font-size: 15px; font-weight: 600; color: #1a1a1a; }
.appt-card__meta { font-size: 12px; color: #888; margin-top: 4px; display: flex; align-items: center; gap: 4px; }
.appt-card__go { font-size: 13px; color: #ff6b9e; }

.sec-head { display: flex; align-items: center; justify-content: space-between; padding: 4px 16px 10px; }
.sec-head h3 { margin: 0; font-size: 16px; font-weight: 700; color: #1a1a1a; display: flex; align-items: center; gap: 6px; }
.sec-head__ico { color: #ff6b9e; }
.sec-head__more { font-size: 12px; color: #999; cursor: pointer; }

.proj-list { padding: 0 16px; display: flex; flex-direction: column; gap: 10px; }
.proj { display: flex; gap: 12px; padding: 12px; cursor: pointer; }
.proj__img { width: 76px; height: 76px; border-radius: 10px; flex-shrink: 0; background: linear-gradient(135deg,#fff0f5,#ffe0ec); display: flex; align-items: center; justify-content: center; font-size: 30px; color: #ff6b9e; }
.proj__body { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.proj__name { font-size: 15px; font-weight: 600; color: #1a1a1a; }
.proj__meta { font-size: 12px; color: #999; margin-top: 4px; }
.proj__price { margin-top: auto; display: flex; align-items: baseline; gap: 6px; }
.proj__now { font-size: 17px; font-weight: 700; color: #ff4d6d; }
.proj__old { font-size: 12px; color: #bbb; text-decoration: line-through; }
.proj__tag { font-size: 10px; color: #ff4d6d; background: #fff0f3; padding: 1px 5px; border-radius: 4px; }

.store-card { margin: 14px 16px 0; padding: 14px; display: flex; align-items: center; gap: 12px; cursor: pointer; }
.store-card__icon { width: 44px; height: 44px; border-radius: 14px; background: #fff0f5; color: #ff6b9e; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.store-card__body { flex: 1; }
.store-card__name { font-size: 15px; font-weight: 600; color: #1a1a1a; }
.store-card__sub { font-size: 12px; color: #888; margin-top: 3px; }
.store-card__go { color: #ccc; }
</style>
