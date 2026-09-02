<script setup lang="ts">
/* C 端我的订单 /m/orders — 对接 B 端 order store */
import { onMounted, computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useOrderStore } from '@/stores/order'
import { usePointsStore } from '@/stores/points'
import CIcon from '@/components/CIcon.vue'

const router = useRouter()
const order = useOrderStore()
const points = usePointsStore()
onMounted(() => { order.seed(); points.seed() })

const tabs = [
  { key: 'ALL', label: '全部' },
  { key: 'PENDING_PAY', label: '待付款' },
  { key: 'PENDING_WRITE', label: '待核销' },
  { key: 'DONE', label: '已完成' },
]
const active = ref('ALL')

// 订单状态映射
const STATUS: Record<string, { label: string; cls: string }> = {
  PENDING_PAY: { label: '待付款', cls: 'warn' },
  PENDING_SIGN: { label: '待确认', cls: 'warn' },
  PENDING_WRITE: { label: '待核销', cls: 'ok' },
  PAID: { label: '待核销', cls: 'ok' },
  COMPLETED: { label: '已完成', cls: 'done' },
}
// B 端订单 + 模拟历史单（演示用）
const mockHistory = [
  { id: 'mk1', orderNo: 'SO20260818002', items: [{ name: '闺蜜分享次卡', qty: 1, price: 3980 }], amount: 3980, status: 'COMPLETED' as const },
  { id: 'mk2', orderNo: 'SO20260810008', items: [{ name: '玻尿酸填充（瑞蓝2号）', qty: 1, price: 5280 }], amount: 5280, status: 'COMPLETED' as const },
]
const all = computed(() => {
  const mine = order.byCustomer(points.member.memberId).map((o) => ({
    id: o.id, orderNo: o.orderNo, items: o.items, amount: o.amount, status: o.status as string,
  }))
  return [...mine, ...mockHistory]
})
const filtered = computed(() => {
  if (active.value === 'ALL') return all.value
  if (active.value === 'PENDING_WRITE') return all.value.filter((o) => o.status === 'PENDING_WRITE' || o.status === 'PAID')
  if (active.value === 'DONE') return all.value.filter((o) => o.status === 'COMPLETED')
  return all.value.filter((o) => o.status === active.value)
})
function st(s: string) { return STATUS[s] || { label: s, cls: 'muted' } }
</script>

<template>
  <div class="orders">
    <div class="tabbar">
      <button v-for="t in tabs" :key="t.key" class="tab" :class="{ on: active === t.key }" @click="active = t.key">{{ t.label }}</button>
    </div>
    <div class="list">
      <div v-if="!filtered.length" class="empty"><div class="empty__icon"><CIcon name="package" :size="36" /></div><div class="empty__text">暂无订单</div></div>
      <div v-for="o in filtered" :key="o.id" class="ocard" @click="router.push(`/m/order/${o.id}`)">
        <div class="ocard__head">
          <span class="ocard__no">订单号 {{ o.orderNo }}</span>
          <span class="ocard__status" :class="st(o.status).cls">{{ st(o.status).label }}</span>
        </div>
        <div v-for="(it, i) in o.items.slice(0, 2)" :key="i" class="ocard__item">
          <div class="ocard__img">{{ it.name.charAt(0) }}</div>
          <div class="ocard__name">{{ it.name }}<span v-if="it.qty > 1"> ×{{ it.qty }}</span></div>
          <div class="ocard__price">¥{{ it.price.toLocaleString() }}</div>
        </div>
        <div class="ocard__foot">
          共 {{ o.items.reduce((s, it) => s + it.qty, 0) }} 件 合计 <b>¥{{ o.amount.toLocaleString() }}</b>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.orders { padding-bottom: 24px; }
.tabbar { position: sticky; top: 0; z-index: 5; background: #fff; display: flex; padding: 0 8px; border-bottom: .5px solid #eee; }
.tab { flex: 1; border: none; background: transparent; padding: 13px 0; font-size: 14px; color: #666; cursor: pointer; position: relative; }
.tab.on { color: #ff4d6d; font-weight: 600; }
.tab.on::after { content: ''; position: absolute; bottom: 0; left: 50%; transform: translateX(-50%); width: 24px; height: 3px; border-radius: 2px; background: #ff6b9e; }
.list { padding: 12px 16px; display: flex; flex-direction: column; gap: 12px; }
.ocard { background: #fff; border-radius: 14px; padding: 14px; cursor: pointer; }
.ocard__head { display: flex; justify-content: space-between; align-items: center; padding-bottom: 12px; border-bottom: .5px solid #f5f5f5; }
.ocard__no { font-size: 12px; color: #999; }
.ocard__status { font-size: 12px; padding: 3px 10px; border-radius: 12px; }
.ocard__status.warn { color: #fa8c16; background: #fff7e6; }
.ocard__status.ok { color: #ff6b9e; background: #fff0f5; }
.ocard__status.done { color: #52c41a; background: #f0fff0; }
.ocard__status.muted { color: #bbb; background: #f5f5f5; }
.ocard__item { display: flex; align-items: center; gap: 12px; padding: 12px 0; }
.ocard__img { width: 56px; height: 56px; border-radius: 10px; background: linear-gradient(135deg,#fff0f5,#ffe0ec); display: flex; align-items: center; justify-content: center; font-size: 24px; color: #ff6b9e; flex-shrink: 0; }
.ocard__name { flex: 1; font-size: 14px; color: #333; }
.ocard__price { font-size: 14px; color: #333; font-weight: 600; }
.ocard__foot { text-align: right; font-size: 13px; color: #999; padding-top: 10px; border-top: .5px solid #f5f5f5; }
.ocard__foot b { color: #ff4d6d; font-size: 16px; }
.empty { text-align: center; padding: 80px 0; }
.empty__icon { width: 64px; height: 64px; margin: 0 auto; border-radius: 16px; background: #fff0f5; color: #ff6b9e; display: flex; align-items: center; justify-content: center; }
.empty__text { font-size: 14px; color: #bbb; margin-top: 12px; }
</style>
