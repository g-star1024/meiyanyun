<script setup lang="ts">
/* C 端优惠券 /m/coupons — 接 B 端 M5-02 券管理 */
import { onMounted, ref, computed } from 'vue'
import { useM5CouponStore } from '@/stores/m5Coupon'

const coupon = useM5CouponStore()
onMounted(() => coupon.seed())

const tab = ref('available')
const tabs = [
  { label: '可使用', value: 'available' },
  { label: '已使用', value: 'used' },
  { label: '已过期', value: 'expired' },
]

const available = computed(() => coupon.coupons.filter((c) => c.status === 'ACTIVE' && coupon.stockLeft(c) > 0))
const used = ref([
  { id: 'u1', name: '新客专享 8 折券', desc: '全场项目可用', usedOn: '2026-08-20 水光焕肤' },
])
const expired = ref([
  { id: 'e1', name: '满 1000 减 200', desc: '疗程项目可用', date: '2026-07-31' },
])

function claim(c: Record<string, any>) {
  const r = coupon.grant(c.id, 'DESIGNATED', 'C端会员-陈美玲', 1)
  if (r.status === 'GRANTED') {
    window.alert(`已领取「${c.name}」！可在 B 端 M4-15 收银核销。`)
  } else {
    window.alert('领取失败，库存不足或已领过。')
  }
}
</script>

<template>
  <div class="cp">
    <div class="tabs">
      <button
        v-for="t in tabs" :key="t.value"
        class="tab" :class="{ 'tab--active': tab === t.value }"
        @click="tab = t.value"
      >{{ t.label }}</button>
    </div>

    <!-- 可使用 -->
    <div v-if="tab === 'available'" class="list">
      <div v-for="c in available" :key="c.id" class="coupon">
        <div class="coupon__left">
          <div class="coupon__amount">
            <span v-if="c.type === 'AMOUNT'">¥<strong>{{ c.value }}</strong></span>
            <strong v-else>{{ c.value / 10 }}<em>折</em></strong>
          </div>
          <div class="coupon__cond">满{{ c.threshold }}可用</div>
        </div>
        <div class="coupon__mid">
          <div class="coupon__name">{{ c.name }}</div>
          <div class="coupon__date">{{ c.startDate?.slice(5) }} ~ {{ c.endDate?.slice(5) }}</div>
        </div>
        <button class="coupon__btn" @click="claim(c)">领取</button>
      </div>
      <div v-if="!available.length" class="empty">暂无可用优惠券</div>
    </div>

    <!-- 已使用 -->
    <div v-else-if="tab === 'used'" class="list">
      <div v-for="u in used" :key="u.id" class="coupon coupon--used">
        <div class="coupon__left"><div class="coupon__amount"><strong>已用</strong></div></div>
        <div class="coupon__mid">
          <div class="coupon__name">{{ u.name }}</div>
          <div class="coupon__date">使用于：{{ u.usedOn }}</div>
        </div>
      </div>
    </div>

    <!-- 已过期 -->
    <div v-else class="list">
      <div v-for="e in expired" :key="e.id" class="coupon coupon--expired">
        <div class="coupon__left"><div class="coupon__amount"><strong>过期</strong></div></div>
        <div class="coupon__mid">
          <div class="coupon__name">{{ e.name }}</div>
          <div class="coupon__date">过期于 {{ e.date }}</div>
        </div>
      </div>
    </div>

    <p class="bc-hint">B/C 联动：优惠券由 B 端 M5-02 配置发放，在 M4-15 收银可核销。</p>
  </div>
</template>

<style scoped>
.cp { padding: var(--s-md); }
.tabs { display: flex; background: #fff; border-radius: var(--r-md); padding: 4px; margin-bottom: var(--s-md); }
.tab { flex: 1; border: none; background: transparent; padding: 8px; font-size: var(--t-sm); color: var(--c-text-2); border-radius: var(--r-sm); cursor: pointer; }
.tab--active { background: var(--c-brand); color: #fff; font-weight: 600; }
.list { display: flex; flex-direction: column; gap: var(--s-sm); }
.coupon { display: flex; align-items: center; background: #fff; border-radius: var(--r-lg); overflow: hidden; box-shadow: 0 1px 4px rgba(0,0,0,.04); }
.coupon__left { width: 90px; padding: var(--s-md) var(--s-sm); text-align: center; background: linear-gradient(135deg, var(--c-brand), #ff8fb3); color: #fff; }
.coupon__amount strong { font-size: var(--t-xl); font-weight: 700; }
.coupon__amount em { font-size: var(--t-sm); font-style: normal; }
.coupon__cond { font-size: 10px; opacity: .9; margin-top: 2px; }
.coupon__mid { flex: 1; padding: var(--s-sm) var(--s-md); }
.coupon__name { font-size: var(--t-sm); font-weight: 600; color: var(--c-text); }
.coupon__date { font-size: 11px; color: var(--c-text-3); margin-top: 4px; }
.coupon__btn { margin-right: var(--s-md); padding: 6px 16px; background: var(--c-brand); color: #fff; border: none; border-radius: var(--r-pill); font-size: var(--t-xs); font-weight: 600; cursor: pointer; }
.coupon--used .coupon__left, .coupon--expired .coupon__left { background: var(--c-text-3); }
.empty { text-align: center; color: var(--c-text-3); font-size: var(--t-sm); padding: var(--s-xl) 0; }
.bc-hint { font-size: 11px; color: var(--c-text-3); text-align: center; margin-top: var(--s-md); }
</style>
