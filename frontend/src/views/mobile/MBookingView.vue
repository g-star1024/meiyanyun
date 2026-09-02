<script setup lang="ts">
/* C 端我的预约 /m/booking — 预约记录列表 + 新建入口 */
import { onMounted, computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAppointmentStore } from '@/stores/appointment'
import { usePointsStore } from '@/stores/points'
import CIcon from '@/components/CIcon.vue'

const router = useRouter()
const appt = useAppointmentStore()
const points = usePointsStore()
onMounted(() => { appt.seed(); points.seed() })

const myAppts = computed(() =>
  appt.appointments
    .filter((a) => a.customerId === points.member.memberId)
    .sort((a, b) => (b.timeSlot || '').localeCompare(a.timeSlot || '')),
)
const tabs = ['全部', '待确认', '已确认', '已完成']
const activeTab = ref('全部')
const filtered = computed(() => {
  if (activeTab.value === '全部') return myAppts.value
  const map: Record<string, string> = { 待确认: 'NEW', 已确认: 'CONFIRMED', 已完成: 'COMPLETED' }
  return myAppts.value.filter((a) => a.status === map[activeTab.value])
})

function statusLabel(s: string) {
  return ({ NEW: '待确认', CONFIRMED: '已确认', ARRIVED: '已到店', COMPLETED: '已完成', CANCELLED: '已取消', NO_SHOW: '未到店' } as Record<string, string>)[s] || s
}
function statusCls(s: string) {
  if (s === 'CONFIRMED' || s === 'ARRIVED') return 'ok'
  if (s === 'COMPLETED') return 'done'
  if (s === 'CANCELLED' || s === 'NO_SHOW') return 'muted'
  return 'warn'
}
</script>

<template>
  <div class="bk">
    <!-- 状态 tab -->
    <div class="tabbar">
      <button v-for="t in tabs" :key="t" class="tab" :class="{ on: activeTab === t }" @click="activeTab = t">{{ t }}</button>
    </div>

    <div class="list">
      <div v-if="!filtered.length" class="empty">
        <div class="empty__icon"><CIcon name="calendar" :size="36" /></div>
        <div class="empty__text">暂无预约记录</div>
      </div>
      <div v-for="a in filtered" :key="a.id" class="acard">
        <div class="acard__top">
          <span class="acard__project">{{ a.project || '到店服务' }}</span>
          <span class="acard__status" :class="statusCls(a.status)">{{ statusLabel(a.status) }}</span>
        </div>
        <div class="acard__row"><CIcon name="clock" :size="13" /> {{ a.timeSlot?.replace('T', ' ').slice(5, 16) || '时间待定' }}</div>
        <div class="acard__row acard__row--flex"><CIcon name="store" :size="13" /> 上海静安旗舰店</div>
        <div class="acard__foot">
          <span class="acard__no">预约号 {{ a.id }}</span>
          <span v-if="a.status === 'NEW' || a.status === 'CONFIRMED'" class="acard__src">来源：{{ a.source }}</span>
        </div>
      </div>
    </div>

    <!-- 新建预约悬浮按钮 -->
    <button class="fab" @click="router.push('/m/booking/new')">
      <span>＋</span> 新建预约
    </button>
  </div>
</template>

<style scoped>
.bk { padding-bottom: 90px; }
.tabbar { position: sticky; top: 0; z-index: 5; background: #fff; display: flex; padding: 0 8px; border-bottom: 0.5px solid #eee; }
.tab { flex: 1; border: none; background: transparent; padding: 13px 0; font-size: 14px; color: #666; cursor: pointer; position: relative; }
.tab.on { color: #ff4d6d; font-weight: 600; }
.tab.on::after { content: ''; position: absolute; bottom: 0; left: 50%; transform: translateX(-50%); width: 24px; height: 3px; border-radius: 2px; background: #ff6b9e; }
.list { padding: 12px 16px; display: flex; flex-direction: column; gap: 12px; }
.acard { background: #fff; border-radius: 14px; padding: 14px; }
.acard__top { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
.acard__project { font-size: 16px; font-weight: 700; color: #1a1a1a; }
.acard__status { font-size: 12px; padding: 3px 10px; border-radius: 12px; }
.acard__status.ok { color: #ff6b9e; background: #fff0f5; }
.acard__status.done { color: #52c41a; background: #f0fff0; }
.acard__status.warn { color: #fa8c16; background: #fff7e6; }
.acard__status.muted { color: #bbb; background: #f5f5f5; }
.acard__row { font-size: 13px; color: #666; line-height: 2; display: flex; align-items: center; gap: 4px; }
.acard__foot { display: flex; justify-content: space-between; margin-top: 10px; padding-top: 10px; border-top: 0.5px solid #f2f2f2; }
.acard__no { font-size: 12px; color: #bbb; }
.acard__src { font-size: 12px; color: #bbb; }
.empty { text-align: center; padding: 80px 0; }
.empty__icon { width: 64px; height: 64px; margin: 0 auto; border-radius: 16px; background: #fff0f5; color: #ff6b9e; display: flex; align-items: center; justify-content: center; }
.empty__text { font-size: 14px; color: #bbb; margin-top: 12px; }
.fab { position: fixed; bottom: 24px; left: 50%; transform: translateX(-50%); max-width: calc(390px - 32px); width: calc(100% - 64px); height: 48px; border: none; border-radius: 24px; background: linear-gradient(135deg,#FFBFF0,#FF6B9E); color: #fff; font-size: 16px; font-weight: 700; cursor: pointer; box-shadow: 0 6px 18px rgba(255,107,158,.4); z-index: 20; }
.fab span { font-size: 18px; margin-right: 4px; }
</style>
