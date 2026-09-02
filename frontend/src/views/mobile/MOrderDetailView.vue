<script setup lang="ts">
/* C 端订单详情 /m/order/:id — 商品 + 核销码 + 金额明细 */
import { onMounted, computed } from 'vue'
import { useRoute } from 'vue-router'
import { useOrderStore } from '@/stores/order'
import CIcon from '@/components/CIcon.vue'

const route = useRoute()
const order = useOrderStore()
onMounted(() => order.seed())

import { ORDER_STATUS } from '@/config/dictionary'

const o = computed(() => order.get(route.params.id as string))

// B 端无则用示例（mock 历史单）
const mock = !o.value ? {
  orderNo: 'SO20260818002',
  status: 'COMPLETED',
  items: [{ name: '闺蜜分享次卡', spec: '10 次水光', qty: 1, price: 3980 }],
  amount: 3980,
  payMethod: '微信支付',
  createdAt: '2026-08-18 14:02',
  store: '上海静安旗舰店',
} : null
</script>

<template>
  <div class="od">
    <!-- 状态条 -->
    <div class="status">
      <div class="status__icon" :class="{ done: (o?.status || mock?.status) === 'COMPLETED' }">
        <CIcon :name="(o?.status || mock?.status) === 'COMPLETED' ? 'check' : 'ticket'" :size="30" />
      </div>
      <div class="status__text">{{ ORDER_STATUS[o?.status as keyof typeof ORDER_STATUS || '']?.label || ORDER_STATUS[mock?.status as keyof typeof ORDER_STATUS || '']?.label || '待核销' }}</div>
      <div class="status__sub">{{ (o?.status || mock?.status) === 'COMPLETED' ? '服务已完成，感谢您的信任' : '到店出示核销码即可服务' }}</div>
    </div>

    <!-- 核销码 -->
    <div v-if="(o?.status || mock?.status) !== 'COMPLETED'" class="qr card">
      <div class="qr__code">
        <div class="qr__grid">
          <i v-for="n in 49" :key="n" :style="{ background: (n * 7 + (n % 3)) % 3 === 0 ? '#1a1a1a' : 'transparent' }"></i>
        </div>
      </div>
      <div class="qr__hint">到店请向工作人员出示此核销码</div>
    </div>

    <!-- 门店 -->
    <div class="card cell">
      <span class="cell__label"><CIcon name="store" :size="14" /> 服务门店</span><span>{{ mock?.store || '上海静安旗舰店' }} ›</span>
    </div>

    <!-- 商品 -->
    <div class="card goods">
      <div v-for="(it, i) in (o?.items || mock?.items)" :key="i" class="goods__row">
        <div class="goods__img">{{ it.name.charAt(0) }}</div>
        <div class="goods__body">
          <div class="goods__name">{{ it.name }}</div>
          <div class="goods__spec">{{ it.spec || '到店服务' }}</div>
        </div>
        <div class="goods__right">
          <div class="goods__price">¥{{ it.price.toLocaleString() }}</div>
          <div class="goods__qty">×{{ it.qty }}</div>
        </div>
      </div>
    </div>

    <!-- 金额明细 -->
    <div class="card amount">
      <div class="amount__row"><span>商品总额</span><span>¥{{ (o?.amount || mock?.amount || 0).toLocaleString() }}</span></div>
      <div class="amount__row"><span>优惠</span><span class="amount__off">-¥0</span></div>
      <div class="amount__row amount__row--total"><span>实付</span><b>¥{{ (o?.amount || mock?.amount || 0).toLocaleString() }}</b></div>
    </div>

    <!-- 订单信息 -->
    <div class="card info">
      <div class="info__row"><span>订单编号</span><span>{{ o?.orderNo || mock?.orderNo }}</span></div>
      <div class="info__row"><span>下单时间</span><span>{{ mock?.createdAt || o?.createdAt?.slice(0, 16).replace('T', ' ') }}</span></div>
      <div class="info__row"><span>支付方式</span><span>{{ mock?.payMethod || '会员卡支付' }}</span></div>
    </div>

    <div v-if="(o?.status || mock?.status) !== 'COMPLETED'" class="bottom-space"></div>
    <div v-if="(o?.status || mock?.status) !== 'COMPLETED'" class="bar">
      <button class="bar__btn bar__btn--ghost">联系顾问</button>
      <button class="bar__btn bar__btn--main">预约到店</button>
    </div>
  </div>
</template>

<style scoped>
.od { padding-bottom: 24px; }
.card { background: #fff; border-radius: 14px; margin: 12px; padding: 16px; }
.status { background: linear-gradient(160deg,#FFBFF0,#FF6B9E); padding: 28px 20px; text-align: center; color: #fff; }
.status__icon { width: 64px; height: 64px; margin: 0 auto; border-radius: 50%; background: rgba(255,255,255,.25); color: #fff; display: flex; align-items: center; justify-content: center; }
.status__icon.done { background: #eaf8ef; color: #52c41a; }
.status__text { font-size: 20px; font-weight: 700; margin-top: 10px; }
.status__sub { font-size: 13px; opacity: .95; margin-top: 6px; }
.qr { text-align: center; }
.qr__code { width: 160px; height: 160px; margin: 0 auto; padding: 12px; border: 1px dashed #ffb3cd; border-radius: 12px; }
.qr__grid { width: 100%; height: 100%; display: grid; grid-template-columns: repeat(7, 1fr); gap: 2px; }
.qr__grid i { border-radius: 1px; }
.qr__hint { font-size: 12px; color: #999; margin-top: 12px; }
.cell { display: flex; justify-content: space-between; font-size: 14px; color: #333; }
.cell__label { display: inline-flex; align-items: center; gap: 4px; color: #ff6b9e; }
.cell span:last-child { color: #999; }
.goods__row { display: flex; align-items: center; gap: 12px; padding: 8px 0; }
.goods__img { width: 56px; height: 56px; border-radius: 10px; background: linear-gradient(135deg,#fff0f5,#ffe0ec); display: flex; align-items: center; justify-content: center; font-size: 24px; color: #ff6b9e; flex-shrink: 0; }
.goods__body { flex: 1; min-width: 0; }
.goods__name { font-size: 14px; font-weight: 600; color: #1a1a1a; }
.goods__spec { font-size: 12px; color: #999; margin-top: 4px; }
.goods__right { text-align: right; }
.goods__price { font-size: 14px; font-weight: 600; color: #1a1a1a; }
.goods__qty { font-size: 12px; color: #999; margin-top: 4px; }
.amount__row { display: flex; justify-content: space-between; font-size: 14px; color: #666; padding: 6px 0; }
.amount__off { color: #ff4d6d; }
.amount__row--total { border-top: .5px solid #f2f2f2; margin-top: 6px; padding-top: 12px; color: #333; }
.amount__row--total b { font-size: 19px; color: #ff4d6d; font-weight: 800; }
.info__row { display: flex; justify-content: space-between; font-size: 13px; color: #999; padding: 6px 0; }
.bottom-space { height: 80px; }
.bar { position: fixed; bottom: 0; left: 50%; transform: translateX(-50%); width: 100%; max-width: 390px; background: #fff; border-top: .5px solid #eee; padding: 8px 16px calc(8px + env(safe-area-inset-bottom)); display: flex; gap: 10px; box-sizing: border-box; z-index: 20; }
.bar__btn { flex: 1; height: 44px; border-radius: 22px; font-size: 15px; font-weight: 600; cursor: pointer; }
.bar__btn--ghost { border: 1px solid #eee; background: #fff; color: #666; }
.bar__btn--main { border: none; background: linear-gradient(135deg,#FFBFF0,#FF6B9E); color: #fff; }
</style>
