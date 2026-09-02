// ============================================================
// Journey 客户旅程 store（M3-07）
// 覆盖：预约→到店→咨询→消费→回访→复购 六阶段。
// 对齐设计稿 SCREEN-M3-07：4 KPI + 左客户列表 + 右旅程时间轴 + 消费明细。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

export type JourneyStage = 'APPT' | 'ARRIVE' | 'CONSULT' | 'PAY' | 'FOLLOW' | 'REBUY'

export interface JourneyNode {
  stage: JourneyStage
  date: string // MM-DD
  title: string
  desc: string
  amount?: number
  operator?: string
  done: boolean
}

export interface JourneyCustomer {
  id: string
  name: string
  avatarLetter: string
  phoneMask: string
  level: string
  currentStage: JourneyStage
  risk: 'HIGH' | 'MEDIUM' | 'LOW'
  /** 全部旅程节点（按顺序） */
  nodes: JourneyNode[]
}

const STAGE_LABEL: Record<JourneyStage, string> = {
  APPT: '预约',
  ARRIVE: '到店',
  CONSULT: '咨询',
  PAY: '消费',
  FOLLOW: '回访',
  REBUY: '复购',
}

const STAGE_COLOR: Record<JourneyStage, string> = {
  APPT: 'var(--c-blue)',
  ARRIVE: 'var(--c-teal)',
  CONSULT: 'var(--c-brand)',
  PAY: 'var(--c-purple)',
  FOLLOW: 'var(--c-orange-dark)',
  REBUY: 'var(--c-teal)',
}

export const useJourneyStore = defineStore('journey', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const customers = ref<JourneyCustomer[]>([])
  const selectedId = ref<string | null>(null)
  const range = ref<'180d' | '90d' | '30d'>('180d')

  const selected = computed<JourneyCustomer | null>(() => {
    if (selectedId.value) return customers.value.find((c) => c.id === selectedId.value) ?? null
    return customers.value[0] ?? null
  })

  // —— KPI ——
  const inProgress = computed(() => customers.value.filter((c) => c.currentStage !== 'REBUY'))
  const convertedThisWeek = computed(() => customers.value.filter((c) => c.nodes.some((n) => n.stage === 'REBUY' && n.done)))
  const avgDays = computed(() => {
    const daysList = customers.value.map((c) => {
      const doneNodes = c.nodes.filter((n) => n.done)
      if (doneNodes.length < 2) return 0
      return Math.max(...doneNodes.map((_, i) => i)) * 7 + 3
    })
    const valid = daysList.filter((d) => d > 0)
    return valid.length ? Math.round(valid.reduce((s, d) => s + d, 0) / valid.length) : 0
  })
  const churnRisk = computed(() => customers.value.filter((c) => c.risk === 'HIGH'))

  // —— 选中客户最近一笔消费明细 ——
  const lastPayment = computed(() => {
    if (!selected.value) return null
    const pay = [...selected.value.nodes].reverse().find((n) => n.stage === 'PAY' && n.done)
    return pay ?? null
  })

  function select(id: string) {
    selectedId.value = id
  }

  function addFollowNote(text: string) {
    if (!selected.value || !auth.can('followup:edit')) return
    activity.log(auth.user.name, `为 ${selected.value.name} 添加旅程备注：${text}`, selected.value.id)
  }

  // —— 种子 6+ ——
  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true

    const mk = (c: Omit<JourneyCustomer, 'id'>): JourneyCustomer => ({
      id: nextId('jc'),
      ...c,
    })

    customers.value = [
      mk({
        name: '林晚', avatarLetter: '林', phoneMask: '138****6021', level: '钻石会员',
        currentStage: 'REBUY', risk: 'LOW',
        nodes: [
          { stage: 'APPT', date: '08-01', title: '光子嫩肤预约', desc: '线上预约', done: true },
          { stage: 'ARRIVE', date: '08-02', title: '按时到店', desc: '前台签到', operator: '夏沫', done: true },
          { stage: 'CONSULT', date: '08-02', title: '抗衰方案咨询', desc: '面诊+皮肤检测', operator: '陈思', done: true },
          { stage: 'PAY', date: '08-02', title: '光子嫩肤（全模式）', desc: '储值卡支付', amount: 3800, operator: '陈思', done: true },
          { stage: 'FOLLOW', date: '08-05', title: '电话回访', desc: '术后关怀，无不适', operator: '林微', done: true },
          { stage: 'REBUY', date: '08-12', title: '复购预约中', desc: '超声炮复购', operator: '林微', done: true },
        ],
      }),
      mk({
        name: '赵雨晴', avatarLetter: '赵', phoneMask: '139****8812', level: '金卡会员',
        currentStage: 'FOLLOW', risk: 'MEDIUM',
        nodes: [
          { stage: 'APPT', date: '07-20', title: '射频紧肤预约', desc: '电话预约', done: true },
          { stage: 'ARRIVE', date: '07-22', title: '到店', desc: '准时到店', operator: '夏沫', done: true },
          { stage: 'CONSULT', date: '07-22', title: '方案确认', desc: '紧肤+下颌缘', operator: '陈思', done: true },
          { stage: 'PAY', date: '07-22', title: '射频紧肤', desc: '微信支付', amount: 6800, operator: '夏沫', done: true },
          { stage: 'FOLLOW', date: '07-26', title: '回访（待跟进）', desc: '客户反馈效果不明显，需重做', operator: '林微', done: false },
        ],
      }),
      mk({
        name: '孙佳宁', avatarLetter: '孙', phoneMask: '137****3301', level: '银卡会员',
        currentStage: 'CONSULT', risk: 'HIGH',
        nodes: [
          { stage: 'APPT', date: '08-10', title: '玻尿酸咨询预约', desc: '企微预约', done: true },
          { stage: 'ARRIVE', date: '08-12', title: '到店', desc: '首次到店', operator: '夏沫', done: true },
          { stage: 'CONSULT', date: '08-12', title: '玻尿酸方案', desc: '价格敏感，需跟进', operator: '顾屿', done: false },
        ],
      }),
      mk({
        name: '周晓彤', avatarLetter: '周', phoneMask: '135****9902', level: '钻石会员',
        currentStage: 'PAY', risk: 'LOW',
        nodes: [
          { stage: 'APPT', date: '08-08', title: '热玛吉预约', desc: '老客预约', done: true },
          { stage: 'ARRIVE', date: '08-15', title: '到店', desc: '准时到店', operator: '夏沫', done: true },
          { stage: 'CONSULT', date: '08-15', title: '全面部抗衰', desc: '维持方案', operator: '陈思', done: true },
          { stage: 'PAY', date: '08-15', title: '热玛吉 FLX', desc: '储值卡支付', amount: 12800, operator: '夏沫', done: true },
        ],
      }),
      mk({
        name: '吴诗韵', avatarLetter: '吴', phoneMask: '186****7788', level: '新客',
        currentStage: 'APPT', risk: 'MEDIUM',
        nodes: [
          { stage: 'APPT', date: '08-20', title: '海菲秀体验预约', desc: '抖音引流', done: false },
        ],
      }),
      mk({
        name: '陈美玲', avatarLetter: '陈', phoneMask: '138****2041', level: '金卡会员',
        currentStage: 'FOLLOW', risk: 'HIGH',
        nodes: [
          { stage: 'APPT', date: '06-15', title: '超声炮预约', desc: '老客复购', done: true },
          { stage: 'ARRIVE', date: '06-20', title: '到店', desc: '到店', operator: '夏沫', done: true },
          { stage: 'CONSULT', date: '06-20', title: '下颌缘+颈部', desc: '升级方案', operator: '陈思', done: true },
          { stage: 'PAY', date: '06-20', title: '超声炮下颌缘', desc: '储值卡支付', amount: 12800, operator: '夏沫', done: true },
          { stage: 'FOLLOW', date: '07-05', title: '回访失联', desc: '3 次电话未接，高流失风险', operator: '林微', done: false },
        ],
      }),
      mk({
        name: '黄思琪', avatarLetter: '黄', phoneMask: '139****1122', level: '银卡会员',
        currentStage: 'ARRIVE', risk: 'LOW',
        nodes: [
          { stage: 'APPT', date: '08-18', title: '祛痘护理预约', desc: '小程序预约', done: true },
          { stage: 'ARRIVE', date: '08-22', title: '已到店等候', desc: '排队中', operator: '夏沫', done: true },
        ],
      }),
    ]
  }

  return {
    customers, selectedId, selected, range,
    inProgress, convertedThisWeek, avgDays, churnRisk, lastPayment,
    select, addFollowNote, seed,
    STAGE_LABEL, STAGE_COLOR,
  }
})
