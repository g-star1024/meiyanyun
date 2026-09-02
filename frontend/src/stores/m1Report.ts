import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

// 报表中心：报表模板 + 生成历史 + 数据预览
export type ReportCategory = 'REVENUE' | 'CUSTOMER' | 'OPERATION' | 'FINANCE' | 'COMPLIANCE' | 'STAFF'
export type ReportStatus = 'READY' | 'GENERATING' | 'FAILED'
export type ExportFormat = 'XLSX' | 'PDF' | 'CSV'

export interface ReportTemplate {
  id: string
  name: string
  category: ReportCategory
  desc: string
  period: 'DAY' | 'WEEK' | 'MONTH' | 'QUARTER' | 'YEAR' | 'RANGE'
  dimensions: string[]
  metrics: string[]
  lastRunAt?: string
  subscribed: boolean
}

export interface ReportJob {
  id: string
  templateId: string
  templateName: string
  category: ReportCategory
  period: string
  status: ReportStatus
  format: ExportFormat
  createdAt: string
  createdBy: string
  rowCount?: number
  fileSize?: string
  error?: string
}

export const CAT_LABEL: Record<ReportCategory, string> = {
  REVENUE: '营收分析', CUSTOMER: '客户分析', OPERATION: '运营分析',
  FINANCE: '财务报表', COMPLIANCE: '合规报表', STAFF: '人效报表',
}
export const STATUS_LABEL: Record<ReportStatus, string> = { READY: '已生成', GENERATING: '生成中', FAILED: '失败' }
export const FORMAT_LABEL: Record<ExportFormat, string> = { XLSX: 'Excel', PDF: 'PDF', CSV: 'CSV' }

function mkTemplates(): ReportTemplate[] {
  return [
    { id: 'R01', name: '门店营收日报', category: 'REVENUE', desc: '按门店/支付方式汇总当日营收、客单价、笔数',
      period: 'DAY', dimensions: ['门店', '支付方式'], metrics: ['营收', '客单价', '笔数'], lastRunAt: '2026-08-25 09:00', subscribed: true },
    { id: 'R02', name: '月度经营分析报告', category: 'REVENUE', desc: '集团/区域/门店三级营收、成本、毛利、环比同比',
      period: 'MONTH', dimensions: ['区域', '门店', '项目品类'], metrics: ['营收', '成本', '毛利率', '环比'], lastRunAt: '2026-08-01', subscribed: true },
    { id: 'R03', name: '新客转化漏斗', category: 'CUSTOMER', desc: '到店→咨询→成交各环节转化率与流失分析',
      period: 'WEEK', dimensions: ['渠道', '门店'], metrics: ['到店数', '咨询数', '成交数', '转化率'], subscribed: false },
    { id: 'R04', name: '客户复购与 RFM 分层', category: 'CUSTOMER', desc: 'RFM 分层、复购率、沉睡客户、生命周期价值',
      period: 'MONTH', dimensions: ['门店', '客户分层'], metrics: ['复购率', '客单价', 'LTV'], lastRunAt: '2026-08-03', subscribed: true },
    { id: 'R05', name: '项目疗程消耗报表', category: 'OPERATION', desc: '卡项/疗程剩余次数、核销率、即将到期预警',
      period: 'MONTH', dimensions: ['门店', '项目品类'], metrics: ['剩余次数', '核销率', '到期数'], lastRunAt: '2026-08-20', subscribed: false },
    { id: 'R06', name: '应收账款账龄分析', category: 'FINANCE', desc: '按账龄区间统计应收、逾期、坏账拨备',
      period: 'MONTH', dimensions: ['门店', '账龄区间'], metrics: ['应收余额', '逾期金额', '逾期率'], lastRunAt: '2026-08-01', subscribed: true },
    { id: 'R07', name: '退款与纠纷台账', category: 'FINANCE', desc: '退款金额、原因分布、处理时效、纠纷升级',
      period: 'MONTH', dimensions: ['门店', '退款原因'], metrics: ['退款笔数', '退款金额', '处理时长'], lastRunAt: '2026-08-24', subscribed: false },
    { id: 'R08', name: '合规检查月报', category: 'COMPLIANCE', desc: '资质、知情同意、药品溯源、隐私合规检查结果',
      period: 'MONTH', dimensions: ['门店', '合规项'], metrics: ['通过率', '问题数', '整改率'], lastRunAt: '2026-08-01', subscribed: true },
    { id: 'R09', name: '员工业绩排行', category: 'STAFF', desc: '咨询师/医生业绩、提薪、服务人次、满意度',
      period: 'MONTH', dimensions: ['门店', '员工'], metrics: ['业绩', '服务人次', '满意度', '提成'], lastRunAt: '2026-08-01', subscribed: false },
  ]
}

function mkJobs(): ReportJob[] {
  return [
    { id: 'J01', templateId: 'R02', templateName: '月度经营分析报告', category: 'REVENUE',
      period: '2026-07', status: 'READY', format: 'XLSX', createdAt: '2026-08-01 08:15',
      createdBy: '系统（订阅）', rowCount: 1284, fileSize: '248 KB' },
    { id: 'J02', templateId: 'R06', templateName: '应收账款账龄分析', category: 'FINANCE',
      period: '2026-07', status: 'READY', format: 'PDF', createdAt: '2026-08-01 09:30',
      createdBy: '刘财务', rowCount: 326, fileSize: '156 KB' },
    { id: 'J03', templateId: 'R01', templateName: '门店营收日报', category: 'REVENUE',
      period: '2026-08-24', status: 'READY', format: 'XLSX', createdAt: '2026-08-25 09:00',
      createdBy: '系统（订阅）', rowCount: 58, fileSize: '42 KB' },
    { id: 'J04', templateId: 'R08', templateName: '合规检查月报', category: 'COMPLIANCE',
      period: '2026-07', status: 'FAILED', format: 'PDF', createdAt: '2026-08-01 10:12',
      createdBy: '王质控', error: '部分门店数据未同步，请重试' },
    { id: 'J05', templateId: 'R09', templateName: '员工业绩排行', category: 'STAFF',
      period: '2026-07', status: 'READY', format: 'XLSX', createdAt: '2026-08-02 14:20',
      createdBy: '张经理', rowCount: 86, fileSize: '98 KB' },
  ]
}

// 模拟报表数据预览
const SAMPLE_DATA: Record<string, { headers: string[]; rows: (string | number)[][] }> = {
  R02: {
    headers: ['门店', '营收(万)', '成本(万)', '毛利率', '环比'],
    rows: [
      ['杭州西湖旗舰院', 680, 285, '58.1%', '+12.3%'],
      ['上海静安分院', 510, 240, '52.9%', '+8.1%'],
      ['北京朝阳分院', 420, 230, '45.2%', '-3.4%'],
      ['广州天河分院', 600, 258, '57.0%', '+15.6%'],
      ['成都高新分院', 380, 175, '53.9%', '+6.2%'],
    ],
  },
  R01: {
    headers: ['门店', '营收(元)', '客单价', '笔数'],
    rows: [
      ['杭州西湖旗舰院', 186420, 3242, 58],
      ['上海静安分院', 142800, 2915, 49],
      ['广州天河分院', 168900, 3070, 55],
    ],
  },
}

export const useM1ReportStore = defineStore('m1Report', () => {
  const templates = ref<ReportTemplate[]>([])
  const jobs = ref<ReportJob[]>([])
  const seeded = ref(false)
  function seed() { if (!seeded.value) { templates.value = mkTemplates(); jobs.value = mkJobs(); seeded.value = true } }

  const catFilter = ref<ReportCategory | 'ALL'>('ALL')
  const filtered = computed(() => catFilter.value === 'ALL' ? templates.value : templates.value.filter((t) => t.category === catFilter.value))

  function toggleSubscribe(id: string) {
    const t = templates.value.find((x) => x.id === id)
    if (t) t.subscribed = !t.subscribed
  }

  let jobSeq = 6
  function generate(templateId: string, period: string, format: ExportFormat) {
    const tpl = templates.value.find((t) => t.id === templateId)
    if (!tpl) return
    const id = 'J' + String(jobSeq++).padStart(2, '0')
    const job: ReportJob = {
      id, templateId, templateName: tpl.name, category: tpl.category, period,
      status: 'GENERATING', format, createdAt: new Date().toISOString().slice(0, 16).replace('T', ' '),
      createdBy: '当前用户',
    }
    jobs.value.unshift(job)
    // 模拟生成
    setTimeout(() => {
      const j = jobs.value.find((x) => x.id === id)
      if (j) {
        j.status = 'READY'
        j.rowCount = Math.floor(Math.random() * 1200) + 50
        j.fileSize = (Math.floor(Math.random() * 200) + 40) + ' KB'
      }
      tpl.lastRunAt = j ? jobs.value[0].createdAt : tpl.lastRunAt
    }, 1500)
    return id
  }

  function retry(jobId: string) {
    const j = jobs.value.find((x) => x.id === jobId)
    if (!j) return
    j.status = 'GENERATING'
    j.error = undefined
    setTimeout(() => {
      const x = jobs.value.find((y) => y.id === jobId)
      if (x) { x.status = 'READY'; x.rowCount = 200; x.fileSize = '120 KB' }
    }, 1200)
  }

  function preview(templateId: string) { return SAMPLE_DATA[templateId] ?? null }
  const subscribedCount = computed(() => templates.value.filter((t) => t.subscribed).length)

  return {
    templates, jobs, seeded, seed, catFilter, filtered,
    toggleSubscribe, generate, retry, preview, subscribedCount,
  }
})
