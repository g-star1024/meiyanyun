// ============================================================
// T2-01 数据采集监控 store（P5-B98 接真 marketing-service）
// 数据源＝touch_event 五通道聚合（summary 端点）；同步任务＝触点时间线（recent 端点）。
// v1 纯监控（DESIGN-T2 §6）：无创建/测试/同步/编辑写动作；CDC/三方接入 §7 移交。
// 权限：collect:view（路由挂牌）；端点 @RequirePerm("collect:view") 服务端双保险。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import * as api from '@/api/touchEvent'
import type { TouchEventRow, TouchType } from '@/api/touchEvent'

/** 通道卡（数据源列表＝五采集通道聚合视图）。 */
export interface ChannelCard {
  touchType: TouchType
  name: string
  desc: string
  count: number
  todayCount: number
  latestAt: string | null
  status: 'EMPTY' | 'ACTIVE'
}

export const TOUCH_TYPE_LABEL: Record<TouchType, string> = {
  LANDING_VISIT: '落地页访问',
  LANDING_LEAD: '落地页留资',
  POSTER_SCAN: '海报扫码',
  PUSH_SEND: '推送发送',
  RETURNBACK: '外渠道回传',
}

/** 通道接入方式说明（卡片副标题）。 */
const CHANNEL_DESC: Record<TouchType, string> = {
  LANDING_VISIT: '公开端点 /api/public/landing/{token}/visit（免鉴权·限流·幂等）',
  LANDING_LEAD: '公开端点 /api/public/landing/{token}/lead（表单留资）',
  POSTER_SCAN: 'C 端海报扫码（待 C 端接入，DESIGN-T2 §7 移交）',
  PUSH_SEND: '营销推送落库旁路快照（短信/企微/公众号）',
  RETURNBACK: '外渠道回传落库旁路快照（抖音/小红书/美团）',
}

/** 触点渠道码 → 中文（旁路 channel 语义：推送=pushType，回传=channelCode，落地页=LANDING）。 */
export const CHANNEL_LABEL: Record<string, string> = {
  LANDING: '落地页',
  SMS: '短信',
  WECOM: '企微',
  WECHAT_MP: '公众号',
  DOUYIN: '抖音',
  RED: '小红书',
  MEITUAN: '美团',
}

/** 触点来源表 → 中文（ref_type 三值）。 */
export const REF_TYPE_LABEL: Record<string, string> = {
  LANDING_PAGE: '落地页',
  PUSH_RECORD: '推送记录',
  CHANNEL_RETURNBACK: '渠道回传',
}

/** 从 axios 错误中取后端中文 message（与全平台视图错误范式一致） */
function errMsg(e: unknown, fallback = '网络异常，请稍后重试'): string {
  const anyE = e as { response?: { data?: { message?: string } }; message?: string }
  return anyE?.response?.data?.message || anyE?.message || fallback
}

export const useT2DataCollectStore = defineStore('t2DataCollect', () => {
  const channels = ref<ChannelCard[]>([])
  const events = ref<TouchEventRow[]>([])
  const loading = ref(false)
  const loaded = ref(false)
  const loadError = ref('')

  // ---- KPI ----
  const activeCount = computed(() => channels.value.filter((c) => c.status === 'ACTIVE').length)
  const emptyCount = computed(() => channels.value.filter((c) => c.status === 'EMPTY').length)
  const totalTouches = computed(() => channels.value.reduce((s, c) => s + c.count, 0))
  const todayTouches = computed(() => channels.value.reduce((s, c) => s + c.todayCount, 0))

  function channelLabel(code: string): string {
    return CHANNEL_LABEL[code] ?? code
  }

  function refTypeLabel(code: string | null): string {
    return code ? (REF_TYPE_LABEL[code] ?? code) : '—'
  }

  // ---- 装载 ----
  async function load() {
    loading.value = true
    loadError.value = ''
    try {
      const [sumResp, recentResp] = await Promise.all([
        api.fetchTouchSummary(),
        api.fetchTouchRecent(100),
      ])
      channels.value = sumResp.data.channels.map((r) => ({
        touchType: r.touchType,
        name: TOUCH_TYPE_LABEL[r.touchType] ?? r.touchType,
        desc: CHANNEL_DESC[r.touchType] ?? '',
        count: r.count,
        todayCount: r.todayCount,
        latestAt: r.latestAt,
        status: r.status,
      }))
      events.value = recentResp.data
      loaded.value = true
    } catch (e) {
      loadError.value = errMsg(e)
      console.warn('[t2DataCollect] 触点监控加载失败', e)
    } finally {
      loading.value = false
    }
  }

  /** 进页装载（B86 范式：每次进页重拉真实数据） */
  async function seed() {
    await load()
  }

  return {
    channels, events, loading, loaded, loadError,
    activeCount, emptyCount, totalTouches, todayTouches,
    TOUCH_TYPE_LABEL, channelLabel, refTypeLabel,
    load, seed,
  }
})
