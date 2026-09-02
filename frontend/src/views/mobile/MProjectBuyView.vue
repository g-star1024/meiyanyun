<script setup lang="ts">
/* C 端项目购买 /m/project/:id/buy — 确认单 + 支付方式 + 提交订单 */
import { onMounted, computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { usePricelistStore } from '@/stores/pricelist'
import { usePointsStore } from '@/stores/points'
import CIcon from '@/components/CIcon.vue'

const route = useRoute()
const router = useRouter()
const pricelist = usePricelistStore()
const points = usePointsStore()
onMounted(() => {
  pricelist.seed()
  points.seed()
})

const item = computed(() => pricelist.get(route.params.id as string))
const qty = ref(1)
const payMethod = ref<'card' | 'wechat'>('card')
const useBalance = ref(true)
const done = ref(false)

const unit = computed(() => item.value?.promoPrice ?? item.value?.memberPrice ?? 0)
const total = computed(() => unit.value * qty.value)
const balance = computed(() => points.member.cardBalance)
const balanceUsed = computed(() => (useBalance.value && payMethod.value === 'card' ? Math.min(balance.value, total.value) : 0))
const needPay = computed(() => Math.max(0, total.value - balanceUsed.value))

function changeQty(d: number) {
  qty.value = Math.min(9, Math.max(1, qty.value + d))
}
function submit() {
  done.value = true
}
</script>

<template>
  <div class="buy" v-if="item">
    <!-- 成功态 -->
    <div v-if="done" class="success">
      <div class="success__icon"><CIcon name="check" :size="34" /></div>
      <div class="success__title">下单成功</div>
      <div class="success__sub">{{ item.name }} × {{ qty }}</div>
      <div class="success__amount">实付 ¥{{ needPay.toLocaleString() }}</div>
      <button class="success__btn" @click="router.push('/m/orders')">查看订单</button>
      <button class="success__link" @click="router.push('/m/booking')">去预约到店时间 ›</button>
    </div>

    <template v-else>
      <!-- 商品 -->
      <div class="goods card">
        <div class="goods__img">{{ item.name.charAt(0) }}</div>
        <div class="goods__body">
          <div class="goods__name">{{ item.name }}</div>
          <div class="goods__price">¥{{ unit.toLocaleString() }}<span class="goods__unit">/{{ item.unit }}</span></div>
        </div>
        <div class="qty">
          <button class="qty__btn" @click="changeQty(-1)">−</button>
          <span class="qty__num">{{ qty }}</span>
          <button class="qty__btn" @click="changeQty(1)">+</button>
        </div>
      </div>

      <!-- 服务门店 -->
      <div class="cell card" @click="router.push('/m/stores')">
        <span class="cell__label">服务门店</span>
        <span class="cell__value">上海静安旗舰店 ›</span>
      </div>

      <!-- 支付方式 -->
      <div class="card pay">
        <label class="pay__item" @click="payMethod = 'card'">
          <span class="pay__left"><span class="pay__ico pay__ico--card"><CIcon name="card" :size="20" /></span>会员卡支付</span>
          <span class="radio" :class="{ on: payMethod === 'card' }"></span>
        </label>
        <label class="pay__item" @click="payMethod = 'wechat'">
          <span class="pay__left"><span class="pay__ico pay__ico--wechat"><CIcon name="wallet" :size="20" /></span>微信支付</span>
          <span class="radio" :class="{ on: payMethod === 'wechat' }"></span>
        </label>
        <label v-if="payMethod === 'card'" class="pay__sub">
          <span>卡余额 ¥{{ balance.toLocaleString() }}，本次抵扣 ¥{{ balanceUsed.toLocaleString() }}</span>
        </label>
      </div>

      <div class="tip card">
        <CIcon name="alert" :size="14" /> 购买后可在「我的订单」查看，到店出示订单码即可核销服务。
      </div>

      <div class="bottom-space"></div>

      <!-- 底部结算 -->
      <div class="bar">
        <div class="bar__total">合计 <strong>¥{{ total.toLocaleString() }}</strong></div>
        <button class="bar__btn" @click="submit">提交订单</button>
      </div>
    </template>
  </div>
  <div v-else class="nf">项目不存在</div>
</template>

<style scoped>
.buy { padding-bottom: 0; }
.card { background: #fff; border-radius: 14px; margin: 12px; padding: 14px; }
.goods { display: flex; align-items: center; gap: 12px; }
.goods__img { width: 64px; height: 64px; border-radius: 10px; background: linear-gradient(135deg,#fff0f5,#ffe0ec); display: flex; align-items: center; justify-content: center; font-size: 28px; color: #ff6b9e; }
.goods__body { flex: 1; min-width: 0; }
.goods__name { font-size: 15px; font-weight: 600; color: #1a1a1a; }
.goods__price { font-size: 18px; font-weight: 700; color: #ff4d6d; margin-top: 6px; }
.goods__unit { font-size: 12px; color: #999; font-weight: 400; }
.qty { display: flex; align-items: center; gap: 12px; }
.qty__btn { width: 26px; height: 26px; border-radius: 50%; border: 1px solid #ddd; background: #fff; font-size: 16px; color: #666; cursor: pointer; line-height: 1; }
.qty__num { font-size: 15px; min-width: 20px; text-align: center; }
.cell { display: flex; justify-content: space-between; align-items: center; cursor: pointer; }
.cell__label { font-size: 14px; color: #333; }
.cell__value { font-size: 14px; color: #999; }
.pay { padding: 4px 14px; }
.pay__item { display: flex; justify-content: space-between; align-items: center; padding: 14px 0; border-bottom: 0.5px solid #f2f2f2; cursor: pointer; }
.pay__left { display: flex; align-items: center; gap: 10px; font-size: 15px; color: #1a1a1a; }
.pay__ico { width: 34px; height: 34px; border-radius: 10px; display: inline-flex; align-items: center; justify-content: center; }
.pay__ico--card { background: #fff0f5; color: #ff6b9e; }
.pay__ico--wechat { background: #e8f9ef; color: #07c160; }
.radio { width: 20px; height: 20px; border-radius: 50%; border: 1.5px solid #d0d0d0; position: relative; }
.radio.on { border-color: #ff6b9e; background: #ff6b9e; }
.radio.on::after { content: ''; position: absolute; left: 6px; top: 3px; width: 5px; height: 9px; border: solid #fff; border-width: 0 2px 2px 0; transform: rotate(45deg); }
.pay__sub { display: block; padding: 12px 0; font-size: 12px; color: #ff8c42; }
.tip { font-size: 12px; color: #999; line-height: 1.6; display: flex; align-items: flex-start; gap: 5px; }
.tip .cicon { color: #fa8c16; margin-top: 1px; }
.bottom-space { height: 80px; }
.bar { position: fixed; bottom: 0; left: 50%; transform: translateX(-50%); width: 100%; max-width: 390px; background: #fff; border-top: 0.5px solid #eee; padding: 8px 16px calc(8px + env(safe-area-inset-bottom)); display: flex; align-items: center; justify-content: space-between; box-sizing: border-box; z-index: 20; }
.bar__total { font-size: 14px; color: #333; }
.bar__total strong { font-size: 22px; color: #ff4d6d; font-weight: 800; }
.bar__btn { height: 44px; padding: 0 36px; border: none; border-radius: 22px; background: linear-gradient(135deg,#FFBFF0,#FF6B9E); color: #fff; font-size: 16px; font-weight: 700; cursor: pointer; }
.success { text-align: center; padding: 80px 24px; }
.success__icon { width: 72px; height: 72px; margin: 0 auto; border-radius: 50%; background: #eaf8ef; color: #52c41a; display: flex; align-items: center; justify-content: center; }
.success__title { font-size: 20px; font-weight: 700; color: #1a1a1a; margin-top: 16px; }
.success__sub { font-size: 14px; color: #888; margin-top: 8px; }
.success__amount { font-size: 16px; color: #ff4d6d; font-weight: 700; margin-top: 8px; }
.success__btn { display: block; width: 100%; height: 46px; margin-top: 32px; border: none; border-radius: 23px; background: linear-gradient(135deg,#FFBFF0,#FF6B9E); color: #fff; font-size: 16px; font-weight: 700; cursor: pointer; }
.success__link { display: block; margin-top: 16px; border: none; background: transparent; color: #ff6b9e; font-size: 14px; cursor: pointer; }
.nf { text-align: center; color: #bbb; padding: 100px 0; }
</style>
