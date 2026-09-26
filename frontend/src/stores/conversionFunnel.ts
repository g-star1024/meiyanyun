/* ============================================================
 * P5-B99 转化漏斗 store（/conversion-funnel 切真）
 * 数据源：GET /finance/funnel（finance 聚合 txn 客户级五级 + marketing 落地页留资）
 * 区间：全量宽区间 [2020-01-01, 明日)（页面无日期控件，保持原「全部数据」语义）
 * 钳制/转化率计算自 ConversionFunnelView 搬移（模板零改动 SOP）
 * ============================================================ */
import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import { getFunnelBundle, type FunnelBundle } from '@/api/finance'
import { staffName } from '@/config/staff'
import { shDateStr } from '@/utils/datetime'

export interface FunnelStageRow {
  key: string
  label: string
  sub: string
  value: number
  tone: string
  icon: string
  width: number
  stepRate: number
  overallRate: number
}

export interface ConsultantRankRow {
  id: string
  name: string
  consult: number
  deal: number
  rate: number
  amount: number // 元
}

const STAGE_META = [
  { key: 'lead', label: '线索', sub: '有效预约 + 落地页留资', tone: 'var(--c-blue)', icon: 'bell' },
  { key: 'arrive', label: '到院', sub: '到店登记', tone: 'var(--c-purple)', icon: 'customer' },
  { key: 'consult', label: '咨询', sub: '面诊开单', tone: 'var(--c-brand)', icon: 'chat' },
  { key: 'deal', label: '成交', sub: '缴费单收款', tone: 'var(--c-orange-dark)', icon: 'pos' },
  { key: 'repurchase', label: '复购', sub: '二次及以上消费', tone: 'var(--c-teal)', icon: 'trend-up' },
] as const

/** 全量宽区间（页面无日期控件）：[2020-01-01, 明日) 上海日界半闭 */
function defaultRange(): { from: string; to: string } {
  return { from: '2020-01-01', to: shDateStr(new Date(Date.now() + 86400000)) }
}

export const useConversionFunnelStore = defineStore('conversionFunnel', () => {
  const bundle = ref<FunnelBundle | null>(null)
  const loaded = ref(false)
  const error = ref(false)

  let seeding: Promise<void> | null = null
  function seed(force = false): Promise<void> {
    if (seeding && !force) return seeding
    if (loaded.value && !force) return Promise.resolve()
    seeding = (async () => {
      try {
        const resp = await getFunnelBundle(defaultRange())
        bundle.value = resp.data ?? null
        loaded.value = true
        error.value = false
      } catch (e) {
        console.error('[conversionFunnel] 转化漏斗加载失败', e)
        bundle.value = null
        loaded.value = true
        error.value = true
      }
    })()
    return seeding
  }

  // 漏斗五级真源口径：value 取原始值如实展示（跨域口径可倒置，如 walk-in 直接成交不进 consult_plan）；
  // width 用逐级钳制值保持漏斗视觉单调；stepRate/overallRate 按原始值计算，倒置时 >100% 如实呈现不伪造
  const funnel = computed<FunnelStageRow[]>(() => {
    const s = bundle.value?.stages
    const raw = [s?.lead ?? 0, s?.arrive ?? 0, s?.consult ?? 0, s?.deal ?? 0, s?.repurchase ?? 0]
    const visual: number[] = []
    raw.forEach((v, i) => { visual.push(i === 0 ? v : Math.min(v, visual[i - 1])) })
    const max = Math.max(...visual, 1)
    return STAGE_META.map((m, i) => {
      const value = raw[i]
      const prev = i === 0 ? value : raw[i - 1]
      const stepRate = prev > 0 ? Math.round((value / prev) * 100) : 0
      const overallRate = raw[0] > 0 ? Math.round((value / raw[0]) * 100) : 0
      return { ...m, value, width: Math.max(8, Math.round((visual[i] / max) * 100)), stepRate, overallRate }
    })
  })

  // 咨询师转化排行：amountFen 分 → 元（整数展示，对齐原页面口径）
  const consultantRank = computed<ConsultantRankRow[]>(() =>
    (bundle.value?.consultants ?? [])
      .map((r) => ({
        id: r.consultantId,
        name: staffName(r.consultantId),
        consult: r.consult,
        deal: r.deal,
        rate: r.consult > 0 ? Math.round((r.deal / r.consult) * 100) : 0,
        amount: Math.round((r.amountFen ?? 0) / 100),
      }))
      .sort((a, b) => b.rate - a.rate),
  )

  return { funnel, consultantRank, seed, loaded, error }
})
