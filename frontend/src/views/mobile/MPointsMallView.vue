<script setup lang="ts">
/* C 端积分商城 /m/points-mall — 兑换 B 端 M3-20 配置的商品（联动 3） */
import { onMounted, computed, ref } from 'vue'
import { usePointsStore } from '@/stores/points'

const points = usePointsStore()
onMounted(() => points.seed())

const member = computed(() => points.member)
const onSaleProducts = computed(() => points.products.filter((p) => p.status === 'ON_SALE'))
const myRedemptions = computed(() => points.memberRedemptions)
const tab = ref<'mall' | 'orders'>('mall')

function redeem(product: (typeof points.products)[0]) {
  if (member.value.points < product.pointsCost) {
    window.alert('积分不足，继续消费可累积积分哦~')
    return
  }
  if (!window.confirm(`确认使用 ${product.pointsCost} 积分兑换「${product.name}」？\n兑换申请将提交至 B 端 M3-20 审核队列。`)) return
  const res = points.redeemFromMember(product.id, 1)
  if (res.ok) {
    window.alert(`兑换申请已提交！审核通过后将通知您。\n（已自动进入 B 端 M3-20 待审核队列，剩余积分 ${member.value.points}）`)
  } else {
    window.alert('兑换失败：' + res.reason)
  }
}
</script>

<template>
  <div class="mall">
    <div class="mall__header">
      <div class="mall__points">
        <span class="mall__points-label">我的积分</span>
        <strong class="mall__points-value">{{ member.points.toLocaleString() }}</strong>
      </div>
      <div class="mall__tabs">
        <button :class="{ active: tab === 'mall' }" @click="tab = 'mall'">积分兑换</button>
        <button :class="{ active: tab === 'orders' }" @click="tab = 'orders'">我的兑换</button>
      </div>
    </div>

    <!-- 兑换商城 -->
    <div v-if="tab === 'mall'" class="mall__grid">
      <div v-for="p in onSaleProducts" :key="p.id" class="product-card">
        <div class="product-card__img">
          <span>{{ p.name.slice(0, 1) }}</span>
        </div>
        <div class="product-card__name">{{ p.name }}</div>
        <div class="product-card__meta">
          <span class="product-card__stock">库存 {{ p.stock }}</span>
          <span v-if="p.category === 'SERVICE'" class="product-card__tag">服务</span>
          <span v-else-if="p.category === 'COUPON'" class="product-card__tag">券</span>
          <span v-else class="product-card__tag">实物</span>
        </div>
        <div class="product-card__footer">
          <span class="product-card__points">{{ p.pointsCost }} 积分</span>
          <button
            class="product-card__btn"
            :disabled="member.points < p.pointsCost || p.stock === 0"
            @click="redeem(p)"
          >{{ p.stock === 0 ? '已售罄' : member.points < p.pointsCost ? '积分不足' : '立即兑换' }}</button>
        </div>
      </div>
    </div>

    <!-- 我的兑换 -->
    <div v-else class="orders">
      <div v-if="myRedemptions.length === 0" class="orders__empty">暂无兑换记录</div>
      <div v-for="r in myRedemptions" :key="r.id" class="order-item">
        <div class="order-item__info">
          <div class="order-item__name">{{ r.productName }}</div>
          <div class="order-item__meta">数量 {{ r.qty }} · {{ r.createdAt?.slice(0, 10) }}</div>
        </div>
        <div class="order-item__right">
          <span class="order-item__points">-{{ r.pointsCost }}</span>
          <span
            class="order-item__status"
            :class="{
              pending: r.status === 'PENDING',
              approved: r.status === 'APPROVED',
              rejected: r.status === 'REJECTED',
            }"
          >
            {{ r.status === 'PENDING' ? '待审核' : r.status === 'APPROVED' ? '已通过' : '已驳回' }}
          </span>
        </div>
      </div>
    </div>

    <div class="mall__tip">兑换申请经 B 端 M3-20 审核队列处理，审核通过后积分扣减生效</div>
  </div>
</template>

<style scoped>
.mall { padding: 0 0 24px; }
.mall__header {
  background: linear-gradient(135deg, var(--c-purple, #8c5cf5), var(--c-brand, #ff6b9d));
  padding: var(--s-lg);
  color: #fff;
}
.mall__points-label { font-size: var(--t-sm); opacity: .85; }
.mall__points-value { display: block; font-size: 32px; font-weight: 700; margin-top: 4px; }
.mall__tabs { display: flex; gap: var(--s-md); margin-top: var(--s-md); }
.mall__tabs button {
  background: rgba(255,255,255,.2); border: none; color: #fff;
  padding: 6px 16px; border-radius: var(--r-full); font-size: var(--t-sm); cursor: pointer;
}
.mall__tabs button.active { background: #fff; color: var(--c-brand); font-weight: 600; }
.mall__grid {
  display: grid; grid-template-columns: 1fr 1fr; gap: var(--s-md);
  padding: var(--s-md);
}
.product-card {
  background: var(--c-bg-card); border-radius: var(--r-md); overflow: hidden;
  box-shadow: 0 1px 4px rgba(0,0,0,.06);
}
.product-card__img {
  height: 100px; background: var(--c-bg-page);
  display: flex; align-items: center; justify-content: center;
  font-size: 36px; color: var(--c-text-3);
}
.product-card__name {
  padding: var(--s-sm) var(--s-md) 0; font-size: var(--t-sm);
  font-weight: 600; color: var(--c-text-1);
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}
.product-card__meta {
  padding: 4px var(--s-md); display: flex; gap: 6px; align-items: center;
  font-size: var(--t-xs); color: var(--c-text-3);
}
.product-card__tag {
  background: var(--c-brand-soft); color: var(--c-brand);
  padding: 1px 6px; border-radius: var(--r-sm); font-size: 11px;
}
.product-card__footer {
  padding: var(--s-sm) var(--s-md); display: flex; align-items: center; justify-content: space-between;
}
.product-card__points { font-size: var(--t-sm); font-weight: 700; color: var(--c-brand); }
.product-card__btn {
  background: var(--c-brand); color: #fff; border: none;
  padding: 5px 12px; border-radius: var(--r-sm); font-size: var(--t-xs); cursor: pointer;
}
.product-card__btn:disabled { background: var(--c-text-4); cursor: not-allowed; }
.orders { padding: var(--s-md); }
.orders__empty { text-align: center; color: var(--c-text-3); padding: 48px 0; }
.order-item {
  display: flex; align-items: center; justify-content: space-between;
  padding: var(--s-md); background: var(--c-bg-card);
  border-radius: var(--r-md); margin-bottom: var(--s-sm);
}
.order-item__name { font-size: var(--t-sm); font-weight: 600; }
.order-item__meta { font-size: var(--t-xs); color: var(--c-text-3); margin-top: 4px; }
.order-item__right { text-align: right; }
.order-item__points { display: block; font-size: var(--t-sm); font-weight: 600; color: var(--c-brand); }
.order-item__status { font-size: var(--t-xs); }
.order-item__status.pending { color: var(--c-warning); }
.order-item__status.approved { color: var(--c-success); }
.order-item__status.rejected { color: var(--c-danger); }
.mall__tip {
  text-align: center; font-size: var(--t-xs); color: var(--c-text-3);
  padding: var(--s-md);
}
</style>