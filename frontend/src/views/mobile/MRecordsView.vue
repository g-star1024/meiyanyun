<script setup lang="ts">
/* C 端消费记录 /m/records — 按时间分组的到店消费流水 */
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import CIcon from '@/components/CIcon.vue'
const router = useRouter()
const tabs = ['全部', '项目消费', '卡项充值', '退款']
const active = ref('全部')
const records = [
  { date: '2026-08-25', list: [
    { name: '光子嫩肤（第3次）', store: '静安旗舰店', amount: -0, note: '疗程卡扣次', time: '15:20', type: '项目消费' },
  ]},
  { date: '2026-08-18', list: [
    { name: '闺蜜分享次卡', store: '静安旗舰店', amount: -3980, note: '微信支付', time: '14:02', type: '卡项充值' },
  ]},
  { date: '2026-08-10', list: [
    { name: '玻尿酸填充（瑞蓝2号）', store: '静安旗舰店', amount: -5280, note: '卡余额支付', time: '16:40', type: '项目消费' },
    { name: '储值卡充值', store: '静安旗舰店', amount: -10000, note: '微信支付', time: '16:10', type: '卡项充值' },
  ]},
]
const shown = (t: string) => active.value === '全部' || active.value === t
function iconOf(t: string) {
  return t === '退款' ? 'refund' : t === '卡项充值' ? 'card' : 'beauty'
}
</script>

<template>
  <div class="rec">
    <div class="tabbar">
      <button v-for="t in tabs" :key="t" class="tab" :class="{ on: active === t }" @click="active = t">{{ t }}</button>
    </div>
    <div v-for="g in records" :key="g.date" class="group">
      <div class="group__date">{{ g.date }}</div>
      <div class="group__list">
        <div v-for="(r, i) in g.list" :key="i" v-show="shown(r.type)" class="rrow" @click="router.push('/m/orders')">
          <div class="rrow__icon" :class="{ 'rrow__icon--refund': r.type === '退款' }"><CIcon :name="iconOf(r.type)" :size="20" /></div>
          <div class="rrow__body">
            <div class="rrow__name">{{ r.name }}</div>
            <div class="rrow__meta">{{ r.store }} · {{ r.note }} · {{ r.time }}</div>
          </div>
          <div class="rrow__amount" :class="{ in: r.amount > 0 }">
            {{ r.amount === 0 ? '扣次' : (r.amount > 0 ? '+' : '') + '¥' + Math.abs(r.amount).toLocaleString() }}
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.rec { padding-bottom: 24px; }
.tabbar { position: sticky; top: 0; z-index: 5; background: #fff; display: flex; padding: 0 8px; border-bottom: .5px solid #eee; }
.tab { flex: 1; border: none; background: transparent; padding: 13px 0; font-size: 14px; color: #666; cursor: pointer; position: relative; }
.tab.on { color: #ff4d6d; font-weight: 600; }
.tab.on::after { content: ''; position: absolute; bottom: 0; left: 50%; transform: translateX(-50%); width: 24px; height: 3px; border-radius: 2px; background: #ff6b9e; }
.group { margin-top: 12px; }
.group__date { font-size: 12px; color: #999; padding: 0 16px 8px; }
.group__list { background: #fff; margin: 0 12px; border-radius: 14px; overflow: hidden; }
.rrow { display: flex; align-items: center; gap: 12px; padding: 14px 16px; border-bottom: .5px solid #f5f5f5; cursor: pointer; }
.rrow:last-child { border-bottom: none; }
.rrow__icon { width: 40px; height: 40px; border-radius: 10px; background: #fff0f5; color: #ff6b9e; display: flex; align-items: center; justify-content: center; }
.rrow__icon--refund { background: #fff5e6; color: #fa8c16; }
.rrow__body { flex: 1; min-width: 0; }
.rrow__name { font-size: 14px; font-weight: 600; color: #1a1a1a; }
.rrow__meta { font-size: 12px; color: #999; margin-top: 3px; }
.rrow__amount { font-size: 16px; font-weight: 700; color: #1a1a1a; }
.rrow__amount.in { color: #52c41a; }
</style>
