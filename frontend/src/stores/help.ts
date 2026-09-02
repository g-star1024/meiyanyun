// ============================================================
// Help 帮助 / 培训 store（M2-22）
// SOP 文档与培训资料：操作指南 / 合规培训 / 视频教程 / 常见问题。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

export type HelpCategory = 'GUIDE' | 'COMPLIANCE' | 'VIDEO' | 'FAQ'
export type HelpType = 'DOC' | 'VIDEO' | 'EXAM'

export interface HelpArticle {
  id: string
  category: HelpCategory
  title: string
  type: HelpType
  summary: string
  updatedAt: string
  readMin: number
  tags: string[]
  content: string[]
}

const CATEGORY_LABEL: Record<HelpCategory, string> = {
  GUIDE: '操作指南',
  COMPLIANCE: '合规培训',
  VIDEO: '视频教程',
  FAQ: '常见问题',
}
const CATEGORY_ICON: Record<HelpCategory, string> = {
  GUIDE: 'order',
  COMPLIANCE: 'shield',
  VIDEO: 'marketing',
  FAQ: 'chat',
}
const TYPE_LABEL: Record<HelpType, string> = {
  DOC: '文档',
  VIDEO: '视频',
  EXAM: '考核',
}

export const useHelpStore = defineStore('help', () => {
  const articles = ref<HelpArticle[]>([])
  const currentCategory = ref<HelpCategory | 'ALL'>('ALL')
  const keyword = ref('')

  const docCount = computed(() => articles.value.filter((a) => a.type === 'DOC').length)
  const videoCount = computed(() => articles.value.filter((a) => a.type === 'VIDEO').length)
  const examCount = computed(() => articles.value.filter((a) => a.type === 'EXAM').length)
  const latestAt = computed(() => {
    if (!articles.value.length) return ''
    return articles.value
      .map((a) => a.updatedAt)
      .sort((a, b) => new Date(b).getTime() - new Date(a).getTime())[0]
  })

  const categories = computed(() => {
    const counts: Record<HelpCategory, number> = { GUIDE: 0, COMPLIANCE: 0, VIDEO: 0, FAQ: 0 }
    articles.value.forEach((a) => (counts[a.category] += 1))
    return [
      { value: 'ALL' as const, label: '全部资料', count: articles.value.length },
      { value: 'GUIDE' as const, label: CATEGORY_LABEL.GUIDE, count: counts.GUIDE },
      { value: 'COMPLIANCE' as const, label: CATEGORY_LABEL.COMPLIANCE, count: counts.COMPLIANCE },
      { value: 'VIDEO' as const, label: CATEGORY_LABEL.VIDEO, count: counts.VIDEO },
      { value: 'FAQ' as const, label: CATEGORY_LABEL.FAQ, count: counts.FAQ },
    ]
  })

  const filtered = computed(() => {
    let list = articles.value
    if (currentCategory.value !== 'ALL') list = list.filter((a) => a.category === currentCategory.value)
    if (keyword.value.trim()) {
      const k = keyword.value.trim().toLowerCase()
      list = list.filter(
        (a) => a.title.toLowerCase().includes(k) || a.summary.toLowerCase().includes(k) || a.tags.some((t) => t.toLowerCase().includes(k)),
      )
    }
    return list.sort((a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime())
  })

  function get(id: string) {
    return articles.value.find((a) => a.id === id)
  }

  let seeded = false
  function seed() {
    if (seeded) return
    seeded = true
    const daysAgo = (d: number) => {
      const x = new Date()
      x.setDate(x.getDate() - d)
      return x.toISOString()
    }
    const base: Array<Omit<HelpArticle, 'id'>> = [
      {
        category: 'GUIDE', type: 'DOC', title: '新员工入职操作手册',
        summary: '门店中台登录、角色切换、基础导航与常用功能一览。',
        updatedAt: daysAgo(2), readMin: 8, tags: ['入职', '入门'],
        content: [
          '本手册面向首次使用美研云门店中台的新员工，帮助你在 30 分钟内完成账号初始化与基础功能熟悉。',
          '一、登录与角色切换：使用企业微信扫码登录，若同时担任多个角色，可在右上角头像处切换或叠加角色。',
          '二、首页工作台：工作台按"今日预约 / 待办 / 预警 / 经营简报"四象限组织信息，所有卡片均可点击进入详情。',
          '三、常用模块：前台收银、预约排期、客户档案、库存管理、经营报表是门店日常高频模块，建议加入左侧快捷栏。',
          '四、遇到问题：点击右下角"帮助"图标，可快速搜索 SOP 或联系运营支持。',
        ],
      },
      {
        category: 'GUIDE', type: 'DOC', title: '预约排期操作 SOP',
        summary: '从客户预约到到店核销的完整排期流程，包含改约、爽约处理。',
        updatedAt: daysAgo(5), readMin: 6, tags: ['预约', 'SOP'],
        content: [
          '预约是门店业务流转的起点，所有到店客户原则上必须先建立预约。',
          '1. 进入"预约排期"，在日历上选择咨询师/医生/床位的时间段，点击"新建预约"。',
          '2. 选择客户、项目、预计时长；系统会自动检测人员/床位冲突。',
          '3. 预约确认后，系统自动发送短信与企微提醒。',
          '4. 客户到店后在"到店核销"模块完成签到；未到店按"爽约扣费"规则处理。',
          '5. 如需改约，直接拖拽预约卡片到目标时段，系统会通知客户。',
        ],
      },
      {
        category: 'COMPLIANCE', type: 'DOC', title: '医疗美容合规经营红线（2026 版）',
        summary: '医师资质、药械台账、知情同意、宣传话术四大合规要点。',
        updatedAt: daysAgo(12), readMin: 12, tags: ['合规', '红线'],
        content: [
          '医疗美容行业受卫生健康、市场监管、广告法多重监管，门店必须严格遵守以下红线：',
          '一、人员资质：主诊医师必须取得《医疗美容主诊医师备案证》并在门店公示；护士须持有效执业证。',
          '二、药械管理：麻醉药品、注射剂必须做到"票、账、货、批"一致；冷藏药品温度记录每日两次。',
          '三、知情同意：所有项目前必须签署书面知情同意书；未成年人须监护人签字。',
          '四、宣传合规：禁止使用"最佳 / 第一 / 永久 / 无毒副作用"等绝对化用语；案例展示需签署肖像授权。',
        ],
      },
      {
        category: 'COMPLIANCE', type: 'EXAM', title: '季度合规考核（Q3）',
        summary: '10 道题，覆盖药械台账、知情同意、投诉处理。合格分 90。',
        updatedAt: daysAgo(8), readMin: 15, tags: ['考核', '季度'],
        content: [
          '本季度合规考核共 10 题，包含单选 6 题、多选 4 题，限时 20 分钟。',
          '考核范围：医疗美容合规经营红线、麻醉药品台账、客户投诉处理流程、广告法敏感词。',
          '合格分数线为 90 分；未通过者需在 7 日内完成补考，补考仍未通过将暂停对应业务权限。',
          '考核成绩将纳入门店季度合规评分，并与店长绩效挂钩。',
        ],
      },
      {
        category: 'VIDEO', type: 'VIDEO', title: '热玛吉操作全流程演示',
        summary: '视频 6 分 32 秒：术前评估、参数设置、术中要点、术后护理。',
        updatedAt: daysAgo(18), readMin: 7, tags: ['热玛吉', '操作演示'],
        content: [
          '本视频由首席培训医师录制，完整演示热玛吉第五代设备从开机到治疗结束的全流程。',
          '章节一：术前皮肤评估与禁忌筛查（00:00 - 01:20）',
          '章节二：治疗区域标记与能量参数选择（01:20 - 02:45）',
          '章节三：术中操作要点与客户沟通（02:45 - 05:10）',
          '章节四：术后即刻护理与回访安排（05:10 - 06:32）',
          '观看后建议在导师监督下完成 3 例实操再独立操作。',
        ],
      },
      {
        category: 'VIDEO', type: 'VIDEO', title: '咨询师企微跟进话术示范',
        summary: '视频 4 分 10 秒：到店后 24h、7 天、30 天三阶段回访话术。',
        updatedAt: daysAgo(25), readMin: 5, tags: ['回访', '话术'],
        content: [
          '高质量的回访是复购与口碑的关键，本视频示范三阶段标准回访话术。',
          '阶段一（术后 24h）：关注恢复情况，提醒注意事项，避免销售话术。',
          '阶段二（术后 7 天）：询问效果与满意度，引导晒图/好评。',
          '阶段三（术后 30 天）：根据恢复情况推荐下一阶段方案或到店复查。',
          '所有话术必须通过企业微信发送，严禁私下添加客户个人微信。',
        ],
      },
      {
        category: 'FAQ', type: 'DOC', title: '收银对账常见问题',
        summary: '日结对账不平、退款失败、发票开具等高频问题排查。',
        updatedAt: daysAgo(3), readMin: 5, tags: ['收银', '对账'],
        content: [
          'Q1：日结时系统金额与实收不一致怎么办？',
          'A：先核对现金/刷卡/移动支付三栏；再检查是否有未完成的退款单或挂账单。如仍不平，上报财务并保留流水截图。',
          'Q2：客户退款一直不到账？',
          'A：银行卡退款一般 T+3 到账，微信/支付宝 T+1；超过时限联系财务查询渠道侧状态。',
          'Q3：发票开错能重开吗？',
          'A：当月发票可直接作废重开；跨月发票需走红字冲销流程，联系财务处理。',
        ],
      },
      {
        category: 'FAQ', type: 'DOC', title: '卡项与疗程划扣疑问',
        summary: '卡余查询、跨店使用、疗程转让、过期延期处理。',
        updatedAt: daysAgo(30), readMin: 4, tags: ['卡项', '疗程'],
        content: [
          'Q1：客户在 A 店买的卡能在 B 店用吗？',
          'A：同一品牌下通用卡可跨店使用；门店专属卡仅可在购卡门店使用，系统会自动识别。',
          'Q2：疗程快过期了能延期吗？',
          'A：金卡及以上会员每年可申请一次 30 天延期；其他情况需店长审批。',
          'Q3：疗程能转让给其他人吗？',
          'A：未使用部分可转让一次，需双方到店签署转让协议，收取 50 元手续费。',
        ],
      },
    ]
    base.forEach((a) => {
      articles.value.push({ id: `help-${articles.value.length + 1}`, ...a })
    })
  }

  return {
    articles, currentCategory, keyword,
    docCount, videoCount, examCount, latestAt,
    categories, filtered,
    get, seed,
    CATEGORY_LABEL, CATEGORY_ICON, TYPE_LABEL,
  }
})
