import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

// 数据大屏：集团经营实时看板（深色大屏专用，演示数据）
export interface ScreenKpi { label: string; value: number; unit: string; delta: number; prefix?: string }
export interface RealtimeOrder {
  id: string; store: string; customer: string; item: string; amount: number; time: string; channel: string
}

export const useM1ScreenStore = defineStore('m1Screen', () => {
  const now = ref(new Date())
  function tick() { now.value = new Date() }

  const kpis = computed<ScreenKpi[]>(() => [
    { label: '今日营收', value: 1286400, unit: '元', delta: 12.6, prefix: '¥' },
    { label: '今日到店', value: 486, unit: '人', delta: 8.3 },
    { label: '今日成交单', value: 312, unit: '单', delta: 5.1 },
    { label: '客单价', value: 4123, unit: '元', delta: 3.2, prefix: '¥' },
    { label: '在院治疗', value: 47, unit: '人', delta: -2.4 },
    { label: '今日预约', value: 218, unit: '人', delta: 15.7 },
  ])

  // 实时营收（按小时）
  const hourly = computed(() => [
    { h: '09', v: 86 }, { h: '10', v: 142 }, { h: '11', v: 205 }, { h: '12', v: 168 },
    { h: '13', v: 186 }, { h: '14', v: 268 }, { h: '15', v: 312 }, { h: '16', v: 285 },
  ])

  // 项目品类占比
  const categoryShare = computed(() => [
    { name: '注射美容', value: 38, color: '#ff6b9e' },
    { name: '皮肤光电', value: 27, color: '#6b8aff' },
    { name: '手术整形', value: 18, color: '#2ed4bf' },
    { name: '口腔美容', value: 11, color: '#ffcb47' },
    { name: '其他', value: 6, color: '#8b5cf6' },
  ])

  const storeRanks = computed(() => [
    { name: '杭州西湖旗舰院', value: 38.6, target: 100 },
    { name: '广州天河分院', value: 32.1, target: 100 },
    { name: '上海静安分院', value: 26.8, target: 100 },
    { name: '北京朝阳分院', value: 18.4, target: 100 },
    { name: '成都高新分院', value: 12.7, target: 100 },
  ])

  const realtime = ref<RealtimeOrder[]>([
    { id: 'O8821', store: '杭州西湖旗舰院', customer: '王**', item: '热玛吉FLX面部', amount: 26800, time: '16:42', channel: '美团' },
    { id: 'O8820', store: '上海静安分院', customer: '李**', item: '玻尿酸1ml', amount: 3980, time: '16:38', channel: '新氧' },
    { id: 'O8819', store: '广州天河分院', customer: '陈**', item: '光子嫩肤年卡', amount: 9800, time: '16:31', channel: '到店' },
    { id: 'O8818', store: '北京朝阳分院', customer: '张**', item: '水光针套餐', amount: 5680, time: '16:20', channel: '抖音' },
    { id: 'O8817', store: '杭州西湖旗舰院', customer: '刘**', item: '肉毒素除皱', amount: 2980, time: '16:05', channel: '老客' },
    { id: 'O8816', store: '成都高新分院', customer: '赵**', item: '皮秒祛斑', amount: 6800, time: '15:58', channel: '美团' },
  ])

  function pushOrder() {
    const stores = ['杭州西湖旗舰院', '上海静安分院', '广州天河分院', '北京朝阳分院', '成都高新分院']
    const items = ['热玛吉FLX', '玻尿酸填充', '光子嫩肤', '水光针', '肉毒素', '皮秒激光', '果酸焕肤']
    const channels = ['美团', '新氧', '抖音', '到店', '老客']
    const surnames = ['周', '吴', '郑', '孙', '钱', '冯']
    const o: RealtimeOrder = {
      id: 'O' + (8822 + Math.floor(Math.random() * 100)),
      store: stores[Math.floor(Math.random() * stores.length)],
      customer: surnames[Math.floor(Math.random() * surnames.length)] + '**',
      item: items[Math.floor(Math.random() * items.length)],
      amount: [2980, 3980, 5680, 6800, 9800, 26800][Math.floor(Math.random() * 6)],
      time: new Date().toTimeString().slice(0, 5),
      channel: channels[Math.floor(Math.random() * channels.length)],
    }
    realtime.value.unshift(o)
    if (realtime.value.length > 8) realtime.value.pop()
  }

  const timeStr = computed(() => {
    const d = now.value
    const pad = (n: number) => String(n).padStart(2, '0')
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
  })

  return { now, kpis, hourly, categoryShare, storeRanks, realtime, timeStr, tick, pushOrder }
})
