import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { getScreenOverview, screenStreamUrl, type ScreenOrderPaidDto } from '@/api/screen'
import { listStores } from '@/api/org'

// 数据大屏：集团经营实时看板（B49 卡7 已切真）
// 口径：overview 30s 重取为 KPI/图表唯一数据来源；SSE 仅驱动实时成交流顶插（>8 pop），不累加 KPI。
export interface ScreenKpi { label: string; value: number; unit: string; delta: number | null; prefix?: string }
export interface RealtimeOrder {
  id: string; store: string; customer: string; item: string; amount: number; time: string; channel: string
}

const CATEGORY_COLORS = ['#ff6b9e', '#6b8aff', '#2ed4bf', '#ffcb47', '#8b5cf6', '#ff8f6b']
// 与收银台 pay_method 落库值对齐
const PAY_METHOD_LABEL: Record<string, string> = {
  cash: '现金', card: '刷卡', wxpay: '微信', alipay: '支付宝', balance: '余额',
}

function maskName(name: string): string {
  if (!name) return ''
  return name.length <= 1 ? name : name[0] + '**'
}

export const useM1ScreenStore = defineStore('m1Screen', () => {
  const now = ref(new Date())
  function tick() { now.value = new Date() }

  const kpis = ref<ScreenKpi[]>([])
  const hourly = ref<{ h: string; v: number }[]>([])
  const categoryShare = ref<{ name: string; value: number; color: string }[]>([])
  const storeRanks = ref<{ name: string; value: number }[]>([])
  const notes = ref<string[]>([])
  const realtime = ref<RealtimeOrder[]>([])

  // 门店名前端 join（/api/stores，集团聚合通道）；失败保持 null 下轮重试，展示降级为 storeCode
  let storeNameMap: Record<string, string> | null = null
  async function ensureStoreNames() {
    if (storeNameMap) return
    try {
      const resp = await listStores()
      const map: Record<string, string> = {}
      for (const s of resp.data || []) map[s.storeCode] = s.storeName
      storeNameMap = map
    } catch {
      storeNameMap = null
    }
  }
  const storeName = (code: string) => storeNameMap?.[code] || code

  async function fetchOverview() {
    try {
      await ensureStoreNames()
      const { data } = await getScreenOverview()
      kpis.value = data.kpis.map((k) => ({
        label: k.label,
        value: k.fen ? k.value / 100 : k.value,
        unit: k.unit,
        delta: k.deltaPct,
        prefix: k.fen ? '¥' : undefined,
      }))
      // 营业时段 09-21 渲染（后端 0-23 全量；分→万元，一位小数）
      hourly.value = data.hourly
        .filter((h) => h.hour >= '09' && h.hour <= '21')
        .map((h) => ({ h: h.hour, v: Math.round(h.amountFen / 1000) / 10 }))
      categoryShare.value = data.categoryShare.map((c, i) => ({
        name: c.name,
        value: c.pct,
        color: CATEGORY_COLORS[i % CATEGORY_COLORS.length],
      }))
      storeRanks.value = data.storeRanks.map((s) => ({
        name: storeName(s.storeCode),
        value: Math.round(s.amountFen / 1000) / 10,
      }))
      notes.value = data.notes
    } catch {
      // 快照重取失败沿用旧值，下轮自愈
    }
  }

  let es: EventSource | null = null
  const seenPaymentIds = new Set<string>()

  function connectStream() {
    if (es) return
    es = new EventSource(screenStreamUrl())
    es.addEventListener('order-paid', (ev: MessageEvent) => {
      try {
        const p = JSON.parse(ev.data as string) as ScreenOrderPaidDto
        if (seenPaymentIds.has(p.paymentId)) return
        if (seenPaymentIds.size > 1000) seenPaymentIds.clear()
        seenPaymentIds.add(p.paymentId)
        realtime.value.unshift({
          id: p.paymentId,
          store: storeName(p.storeCode),
          customer: maskName(p.customerName),
          item: p.item,
          amount: p.amountFen / 100,
          time: (p.paidAt || '').slice(11, 16),
          channel: PAY_METHOD_LABEL[p.payMethod] || p.payMethod,
        })
        if (realtime.value.length > 8) realtime.value.pop()
      } catch {
        // 单条负载异常丢弃，不影响流
      }
    })
    es.onerror = () => {
      // EventSource 浏览器侧自动重连，无需手工干预
    }
  }

  function disconnectStream() {
    es?.close()
    es = null
  }

  const timeStr = computed(() => {
    const d = now.value
    const pad = (n: number) => String(n).padStart(2, '0')
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
  })

  return {
    now, kpis, hourly, categoryShare, storeRanks, notes, realtime, timeStr,
    tick, fetchOverview, connectStream, disconnectStream,
  }
})
