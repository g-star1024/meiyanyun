<script setup lang="ts">
/* C 端我的卡 /m/card — 会员卡余额/积分/电子小票（接 M3-05/M6-11/M4-15） */
import { onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { usePointsStore } from '@/stores/points'
import { useOrderStore } from '@/stores/order'
import { useCustomerStore } from '@/stores/customer'

const router = useRouter()
const points = usePointsStore()
const order = useOrderStore()
const customer = useCustomerStore()

onMounted(() => {
  points.seed()
  order.seed()
  customer.seedProfile()
})

const member = computed(() => points.member)

const myCards = [
  { name: '水光焕肤 10 次卡', total: 10, remaining: 6, expire: '2027-06-30' },
  { name: 'VIP 钻石会员卡', balance: 12600, level: '钻石', expire: '2027-12-31' },
  { name: '光子嫩肤 5 次卡', total: 5, remaining: 3, expire: '2026-12-31' },
]

const recentReceipts = computed(() =>
  order.orders
    .filter((o) => o.customerId === member.value.memberId && o.status === 'PAID')
    .slice(0, 5),
)

function fmt(n: number) { return '¥' + n.toLocaleString() }
function fmtDate(s: string) { return s?.slice(0, 16).replace('T', ' ') }
</script>

<template>
  <div class="card-page">
    <!-- 会员卡头部 -->
    <div class="vip-card">
      <div class="vip-card__level">{{ myCards[1].level }}会员</div>
      <div class="vip-card__name">{{ member.name }}</div>
      <div class="vip-card__no">No. {{ member.memberId }}</div>
      <div class="vip-card__balance">
        <div><label>卡余额</label><strong>¥{{ member.cardBalance.toLocaleString() }}</strong></div>
        <div><label>积分</label><strong>{{ member.points.toLocaleString() }}</strong></div>
        <div><label>优惠券</label><strong>{{ member.couponCount }}</strong></div>
      </div>
    </div>

    <!-- 我的卡项 -->
    <div class="section">
      <h3>我的卡项</h3>
      <div v-for="c in myCards" :key="c.name" class="item-card">
        <div class="item-card__name">{{ c.name }}</div>
        <div v-if="c.remaining !== undefined" class="item-card__meta">
          剩余 <strong>{{ c.remaining }}/{{ c.total }}</strong> 次
        </div>
        <div v-else class="item-card__meta">余额 <strong>{{ fmt(c.balance!) }}</strong></div>
        <div class="item-card__expire">有效期至 {{ c.expire }}</div>
      </div>
    </div>

    <!-- 电子小票 -->
    <div class="section">
      <h3>电子小票</h3>
      <div v-if="!recentReceipts.length" class="empty">暂无消费记录</div>
      <div
        v-for="o in recentReceipts" :key="o.id"
        class="receipt-row"
        @click="router.push(`/m/receipt/${o.id}`)"
      >
        <div class="receipt-row__left">
          <div class="receipt-row__no">{{ o.orderNo }}</div>
          <div class="receipt-row__time">{{ fmtDate(o.createdAt) }}</div>
        </div>
        <div class="receipt-row__right">
          <div class="receipt-row__amount">{{ fmt(o.amount) }}</div>
          <div class="receipt-row__arrow">查看小票 ›</div>
        </div>
      </div>
    </div>

    <p class="bc-hint">B/C 联动：卡余额接 M6-11，积分接 M3-05，电子小票接 M4-15 收款。</p>
  </div>
</template>

<style scoped>
.card-page { padding: 0 0 var(--s-lg); }
.vip-card { margin: 0 var(--s-md) var(--s-md); padding: var(--s-lg) var(--s-md); background: linear-gradient(135deg, #2d1b4e, #5a3d8a); color: #fff; border-radius: var(--r-lg); position: relative; overflow: hidden; }
.vip-card::after { content: ''; position: absolute; right: -30px; top: -30px; width: 120px; height: 120px; border-radius: 50%; background: rgba(255,255,255,.06); }
.vip-card__level { display: inline-block; padding: 2px 10px; background: var(--c-gold, #ffcc47); color: #5a3d00; border-radius: var(--r-pill); font-size: 11px; font-weight: 600; }
.vip-card__name { font-size: var(--t-lg); font-weight: 700; margin-top: var(--s-sm); }
.vip-card__no { font-size: 11px; opacity: .7; }
.vip-card__balance { display: flex; gap: var(--s-lg); margin-top: var(--s-md); }
.vip-card__balance label { display: block; font-size: 11px; opacity: .7; }
.vip-card__balance strong { font-size: var(--t-md); font-weight: 700; }

.section { background: #fff; margin: 0 var(--s-md) var(--s-md); border-radius: var(--r-lg); padding: var(--s-md); box-shadow: 0 1px 4px rgba(0,0,0,.04); }
.section h3 { margin: 0 0 var(--s-sm); font-size: var(--t-base); font-weight: 600; color: var(--c-text); }
.item-card { padding: var(--s-sm) 0; border-bottom: 1px solid var(--c-border-light); }
.item-card:last-child { border-bottom: none; }
.item-card__name { font-size: var(--t-sm); color: var(--c-text); font-weight: 500; }
.item-card__meta { font-size: var(--t-xs); color: var(--c-text-2); margin-top: 2px; }
.item-card__meta strong { color: var(--c-brand); }
.item-card__expire { font-size: 11px; color: var(--c-text-3); margin-top: 2px; }

.empty { text-align: center; color: var(--c-text-3); font-size: var(--t-sm); padding: var(--s-lg) 0; }
.receipt-row { display: flex; justify-content: space-between; align-items: center; padding: var(--s-sm) 0; border-bottom: 1px solid var(--c-border-light); cursor: pointer; }
.receipt-row:last-child { border-bottom: none; }
.receipt-row__no { font-size: var(--t-sm); color: var(--c-text); font-weight: 500; }
.receipt-row__time { font-size: 11px; color: var(--c-text-3); margin-top: 2px; }
.receipt-row__amount { font-size: var(--t-base); font-weight: 700; color: var(--c-brand); text-align: right; }
.receipt-row__arrow { font-size: 11px; color: var(--c-text-3); }
.bc-hint { font-size: 11px; color: var(--c-text-3); text-align: center; margin: 0; padding: 0 var(--s-md); }
</style>
