<script setup lang="ts">
/* C 端电子小票 /m/receipt/:id — 接 M4-15 收款（联动 6） */
import { onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useOrderStore } from '@/stores/order'
import { usePointsStore } from '@/stores/points'

const route = useRoute()
const router = useRouter()
const orderStore = useOrderStore()
const points = usePointsStore()

onMounted(() => {
  orderStore.seed()
  points.seed()
})

const order = computed(() => {
  const id = route.params.id as string
  return orderStore.get(id) || orderStore.orders.find((o) => o.status === 'PAID')
})

const paidAmount = computed(() => {
  if (!order.value) return 0
  return order.value.payments.reduce((s, p) => s + p.amount, 0) || order.value.amount
})
const payMethodLabel = computed(() => {
  const m = order.value?.payments?.[0]?.method
  const map: Record<string, string> = {
    card: '会员卡', wxpay: '微信支付', alipay: '支付宝', cash: '现金', balance: '余额',
  }
  return (m && map[m]) || '会员卡'
})

function fmt(n: number) { return '¥' + n.toFixed(2) }
function fmtDate(s: string) { return s?.slice(0, 19).replace('T', ' ') }

function goBack() { router.push('/m/card') }
function saveReceipt() { window.alert('小票已保存到相册') }
</script>

<template>
  <div class="receipt" v-if="order">
    <div class="receipt__header">
      <button class="receipt__back" @click="goBack">‹</button>
      <span>电子小票</span>
    </div>

    <div class="receipt__card">
      <div class="receipt__store">美研云医疗美容</div>
      <div class="receipt__no">订单号：{{ order.orderNo }}</div>
      <div class="receipt__time">{{ fmtDate(order.createdAt) }}</div>

      <div class="receipt__divider"></div>

      <div class="receipt__items">
        <div v-for="(item, idx) in order.items" :key="idx" class="receipt__item">
          <div class="receipt__item-name">{{ item.name }} <span class="receipt__item-qty">x{{ item.qty }}</span></div>
          <div class="receipt__item-price">{{ fmt(item.price * item.qty) }}</div>
        </div>
      </div>

      <div class="receipt__divider"></div>

      <div class="receipt__totals">
        <div class="receipt__row"><span>商品合计</span><span>{{ fmt(order.amount) }}</span></div>
        <div class="receipt__row receipt__row--total"><span>实付金额</span><span class="receipt__paid">{{ fmt(paidAmount) }}</span></div>
      </div>

      <div class="receipt__divider"></div>

      <div class="receipt__pay">
        <span>支付方式</span>
        <span>{{ payMethodLabel }}</span>
      </div>
      <div class="receipt__pay">
        <span>支付状态</span>
        <span class="receipt__status--paid">已支付</span>
      </div>
      <div class="receipt__pay">
        <span>本单获得积分</span>
        <span class="receipt__points">+{{ Math.floor(paidAmount / 10) }}</span>
      </div>

      <div class="receipt__divider"></div>

      <div class="receipt__footer">
        <div class="receipt__barcode"></div>
        <div class="receipt__thanks">感谢您的惠顾，期待下次光临</div>
        <div class="receipt__sub">如有疑问请联系门店或拨打客服热线</div>
      </div>
    </div>

    <div class="receipt__actions">
      <button class="receipt__btn receipt__btn--outline" @click="saveReceipt">保存到相册</button>
      <button class="receipt__btn receipt__btn--primary" @click="goBack">返回我的卡</button>
    </div>
  </div>
  <div v-else class="receipt__empty">
    <p>小票不存在</p>
    <button @click="goBack">返回</button>
  </div>
</template>

<style scoped>
.receipt { min-height: 100vh; background: var(--c-bg-page); padding-bottom: 80px; }
.receipt__header {
  height: 48px; display: flex; align-items: center; justify-content: center;
  position: relative; background: var(--c-bg-card); font-size: var(--t-md); font-weight: 600;
  box-shadow: 0 1px 3px rgba(0,0,0,.05);
}
.receipt__back {
  position: absolute; left: 12px; font-size: 28px; border: none;
  background: none; cursor: pointer; color: var(--c-text-1); line-height: 1;
}
.receipt__card {
  margin: var(--s-md); background: var(--c-bg-card); border-radius: var(--r-md);
  padding: var(--s-lg); box-shadow: 0 1px 4px rgba(0,0,0,.06);
}
.receipt__store { text-align: center; font-size: var(--t-lg); font-weight: 700; color: var(--c-text-1); }
.receipt__no { text-align: center; font-size: var(--t-xs); color: var(--c-text-3); margin-top: 4px; }
.receipt__time { text-align: center; font-size: var(--t-xs); color: var(--c-text-3); }
.receipt__divider {
  height: 1px; background: repeating-linear-gradient(90deg, var(--c-border) 0 6px, transparent 6px 12px);
  margin: var(--s-md) 0;
}
.receipt__item { display: flex; justify-content: space-between; padding: 4px 0; font-size: var(--t-sm); }
.receipt__item-qty { color: var(--c-text-3); margin-left: 8px; }
.receipt__item-price { color: var(--c-text-1); }
.receipt__totals { display: flex; flex-direction: column; gap: 6px; }
.receipt__row { display: flex; justify-content: space-between; font-size: var(--t-sm); color: var(--c-text-2); }
.receipt__row--total { font-size: var(--t-md); font-weight: 700; color: var(--c-text-1); padding-top: 6px; border-top: 1px solid var(--c-border); }
.receipt__paid { color: var(--c-brand); font-size: var(--t-lg); }
.receipt__discount { color: var(--c-danger); }
.receipt__pay { display: flex; justify-content: space-between; padding: 6px 0; font-size: var(--t-sm); color: var(--c-text-2); }
.receipt__status--paid { color: var(--c-success); }
.receipt__points { color: var(--c-brand); font-weight: 600; }
.receipt__footer { text-align: center; padding-top: var(--s-md); }
.receipt__barcode {
  height: 40px; background: repeating-linear-gradient(90deg, var(--c-text-1) 0 2px, transparent 2px 4px, var(--c-text-1) 4px 5px, transparent 5px 8px);
  margin: 0 auto 8px; max-width: 180px;
}
.receipt__thanks { font-size: var(--t-sm); color: var(--c-text-2); }
.receipt__sub { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 4px; }
.receipt__actions {
  position: fixed; bottom: 0; left: 0; right: 0; max-width: 420px; margin: 0 auto;
  display: flex; gap: var(--s-md); padding: var(--s-md);
  background: var(--c-bg-card); box-shadow: 0 -1px 4px rgba(0,0,0,.06);
}
.receipt__btn { flex: 1; height: 44px; border-radius: var(--r-md); font-size: var(--t-md); cursor: pointer; border: none; }
.receipt__btn--outline { background: var(--c-bg-page); color: var(--c-text-1); border: 1px solid var(--c-border); }
.receipt__btn--primary { background: var(--c-brand); color: #fff; }
.receipt__empty { text-align: center; padding: 80px 20px; color: var(--c-text-3); }
.receipt__empty button { margin-top: 16px; padding: 8px 24px; border-radius: var(--r-md); border: none; background: var(--c-brand); color: #fff; cursor: pointer; }
</style>
