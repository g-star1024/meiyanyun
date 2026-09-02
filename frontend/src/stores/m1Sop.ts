import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

// 标准作业 SOP：流程模板库 + 门店执行任务 + 步骤勾选
export type SopCategory = 'MEDICAL' | 'SERVICE' | 'SAFETY' | 'HYGIENE' | 'MANAGEMENT' | 'TRAINING'
export type SopStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'
export type TaskStatus = 'PENDING' | 'IN_PROGRESS' | 'DONE' | 'OVERDUE'
export type Priority = 'HIGH' | 'MEDIUM' | 'LOW'

export interface SopStep { id: string; title: string; desc: string; requirePhoto?: boolean }

export interface SopTemplate {
  id: string
  code: string
  title: string
  category: SopCategory
  version: string
  status: SopStatus
  steps: SopStep[]
  owner: string
  updatedAt: string
  applicableStores: string[] // 'ALL' 或门店 id
}

export interface SopTask {
  id: string
  templateId: string
  templateTitle: string
  category: SopCategory
  tenantId: string
  tenantName: string
  assignee: string
  priority: Priority
  dueAt: string
  status: TaskStatus
  completedSteps: string[]
  note?: string
  startedAt?: string
  completedAt?: string
}

export const CAT_LABEL: Record<SopCategory, string> = {
  MEDICAL: '医疗操作', SERVICE: '服务流程', SAFETY: '安全应急',
  HYGIENE: '感控消毒', MANAGEMENT: '门店管理', TRAINING: '培训考核',
}
export const CAT_ICON: Record<SopCategory, string> = {
  MEDICAL: 'sign', SERVICE: 'profile', SAFETY: 'shield',
  HYGIENE: 'check-square', MANAGEMENT: 'org', TRAINING: 'calendar',
}
export const STATUS_LABEL: Record<SopStatus, string> = { DRAFT: '草稿', PUBLISHED: '已发布', ARCHIVED: '已归档' }
export const TASK_STATUS_LABEL: Record<TaskStatus, string> = {
  PENDING: '待执行', IN_PROGRESS: '进行中', DONE: '已完成', OVERDUE: '已逾期',
}

function mkTemplates(): SopTemplate[] {
  return [
    {
      id: 'S01', code: 'SOP-M-001', title: '术前知情同意签署规范', category: 'MEDICAL', version: 'v3.2',
      status: 'PUBLISHED', owner: '医务部', updatedAt: '2026-08-10', applicableStores: ['ALL'],
      steps: [
        { id: 's1', title: '核验客户身份与项目', desc: '核对身份证、订单项目与主治医生' },
        { id: 's2', title: '充分告知风险与替代方案', desc: '逐条讲解知情同意书，答疑不少于 10 分钟', requirePhoto: true },
        { id: 's3', title: '客户本人签署', desc: '客户手写签名并按手印，禁止代签' },
        { id: 's4', title: '医生双签归档', desc: '主治医生与见证护士共同签字，扫描入 EMR' },
      ],
    },
    {
      id: 'S02', code: 'SOP-H-002', title: '医疗器械高温高压消毒流程', category: 'HYGIENE', version: 'v2.1',
      status: 'PUBLISHED', owner: '感控办', updatedAt: '2026-07-28', applicableStores: ['ALL'],
      steps: [
        { id: 's1', title: '器械预处理清洗', desc: '使用后立即酶洗液浸泡 5 分钟' },
        { id: 's2', title: '封装与化学指示卡', desc: '封装后内置 5 类化学指示卡', requirePhoto: true },
        { id: 's3', title: '高温高压灭菌', desc: '134℃ 灭菌 4 分钟，记录批次号' },
        { id: 's4', title: '生物监测', desc: '每周一次嗜热脂肪杆菌生物监测并留档' },
      ],
    },
    {
      id: 'S03', code: 'SOP-S-003', title: '过敏性休克应急处置', category: 'SAFETY', version: 'v1.5',
      status: 'PUBLISHED', owner: '安全委员会', updatedAt: '2026-06-15', applicableStores: ['ALL'],
      steps: [
        { id: 's1', title: '立即停止操作并呼救', desc: '平卧、抬腿、保暖' },
        { id: 's2', title: '肾上腺素肌注', desc: '0.1% 肾上腺素 0.3-0.5ml 大腿外侧肌注', requirePhoto: true },
        { id: 's3', title: '建立静脉通路与吸氧', desc: '生理盐水快速补液，高流量吸氧' },
        { id: 's4', title: '拨打 120 并持续监护', desc: '记录生命体征，转诊交接' },
      ],
    },
    {
      id: 'S04', code: 'SOP-SV-004', title: '到店接待与分诊标准', category: 'SERVICE', version: 'v2.0',
      status: 'PUBLISHED', owner: '运营中心', updatedAt: '2026-08-01', applicableStores: ['T01', 'T02', 'T04'],
      steps: [
        { id: 's1', title: '3 秒迎宾', desc: '客户进门 3 秒内主动问候' },
        { id: 's2', title: '建档与预约核对', desc: '核对预约信息，更新客情' },
        { id: 's3', title: '引导至休息区', desc: '奉上饮品，告知预计等待时间' },
      ],
    },
    {
      id: 'S05', code: 'SOP-MG-005', title: '日结收银对账流程', category: 'MANAGEMENT', version: 'v1.2',
      status: 'DRAFT', owner: '财务部', updatedAt: '2026-08-20', applicableStores: ['ALL'],
      steps: [
        { id: 's1', title: '打印当日流水', desc: '汇总现金/刷卡/扫码/分期' },
        { id: 's2', title: '账实核对', desc: '现金盘点与系统流水逐笔核对' },
        { id: 's3', title: '差异说明与签字', desc: '差异需店长签字说明原因' },
      ],
    },
  ]
}

function mkTasks(): SopTask[] {
  return [
    { id: 'TK01', templateId: 'S02', templateTitle: '医疗器械高温高压消毒流程', category: 'HYGIENE',
      tenantId: 'T02', tenantName: '上海静安分院', assignee: '王护士长', priority: 'HIGH',
      dueAt: '2026-08-25', status: 'IN_PROGRESS', completedSteps: ['s1', 's2'], startedAt: '2026-08-25' },
    { id: 'TK02', templateId: 'S01', templateTitle: '术前知情同意签署规范', category: 'MEDICAL',
      tenantId: 'T01', tenantName: '杭州西湖旗舰院', assignee: '顾医生', priority: 'HIGH',
      dueAt: '2026-08-25', status: 'PENDING', completedSteps: [] },
    { id: 'TK03', templateId: 'S03', templateTitle: '过敏性休克应急处置', category: 'SAFETY',
      tenantId: 'T03', tenantName: '北京朝阳分院', assignee: '张院长', priority: 'HIGH',
      dueAt: '2026-08-20', status: 'OVERDUE', completedSteps: [] },
    { id: 'TK04', templateId: 'S04', templateTitle: '到店接待与分诊标准', category: 'SERVICE',
      tenantId: 'T04', tenantName: '广州天河分院', assignee: '陈前台', priority: 'MEDIUM',
      dueAt: '2026-08-26', status: 'DONE', completedSteps: ['s1', 's2', 's3'],
      startedAt: '2026-08-24', completedAt: '2026-08-24', note: '当日接待 42 人，流程执行良好' },
    { id: 'TK05', templateId: 'S02', templateTitle: '医疗器械高温高压消毒流程', category: 'HYGIENE',
      tenantId: 'T05', tenantName: '成都高新分院', assignee: '赵护士', priority: 'MEDIUM',
      dueAt: '2026-08-27', status: 'PENDING', completedSteps: [] },
    { id: 'TK06', templateId: 'S01', templateTitle: '术前知情同意签署规范', category: 'MEDICAL',
      tenantId: 'T02', tenantName: '上海静安分院', assignee: '李医生', priority: 'HIGH',
      dueAt: '2026-08-26', status: 'PENDING', completedSteps: [] },
  ]
}

export const useM1SopStore = defineStore('m1Sop', () => {
  const templates = ref<SopTemplate[]>([])
  const tasks = ref<SopTask[]>([])
  const seeded = ref(false)

  function seed() {
    if (seeded.value) return
    templates.value = mkTemplates()
    tasks.value = mkTasks()
    seeded.value = true
  }

  const published = computed(() => templates.value.filter((t) => t.status === 'PUBLISHED'))
  const taskStats = computed(() => ({
    pending: tasks.value.filter((t) => t.status === 'PENDING').length,
    inProgress: tasks.value.filter((t) => t.status === 'IN_PROGRESS').length,
    done: tasks.value.filter((t) => t.status === 'DONE').length,
    overdue: tasks.value.filter((t) => t.status === 'OVERDUE').length,
  }))
  const completionRate = computed(() => {
    const total = tasks.value.length
    if (!total) return 0
    return Math.round((taskStats.value.done / total) * 100)
  })

  function template(id: string) { return templates.value.find((t) => t.id === id) }

  function startTask(id: string) {
    const t = tasks.value.find((x) => x.id === id)
    if (t && t.status === 'PENDING') { t.status = 'IN_PROGRESS'; t.startedAt = new Date().toISOString().slice(0, 10) }
  }
  function toggleStep(taskId: string, stepId: string) {
    const t = tasks.value.find((x) => x.id === taskId)
    if (!t || t.status === 'DONE') return
    const idx = t.completedSteps.indexOf(stepId)
    if (idx >= 0) t.completedSteps.splice(idx, 1)
    else t.completedSteps.push(stepId)
  }
  function completeTask(id: string, note: string) {
    const t = tasks.value.find((x) => x.id === id)
    if (!t) return
    t.status = 'DONE'
    t.completedAt = new Date().toISOString().slice(0, 10)
    t.note = note
    // 补齐所有步骤
    const tmpl = template(t.templateId)
    if (tmpl) t.completedSteps = tmpl.steps.map((s) => s.id)
  }
  function publishTemplate(id: string) {
    const t = templates.value.find((x) => x.id === id)
    if (t && t.status === 'DRAFT') { t.status = 'PUBLISHED'; t.version = t.version.replace(/\d+$/, (n) => String(+n + 1)) }
  }

  function createTemplate(input: Omit<SopTemplate, 'id' | 'code' | 'version' | 'status' | 'updatedAt'> & { code?: string }): SopTemplate {
    const idx = templates.value.length + 1
    const t: SopTemplate = {
      ...input,
      id: `S${String(idx).padStart(2, '0')}`,
      code: input.code || `SOP-${input.category[0]}-${String(idx).padStart(3, '0')}`,
      version: 'v1.0',
      status: 'DRAFT',
      updatedAt: new Date().toISOString().slice(0, 10),
    }
    templates.value.unshift(t)
    return t
  }

  return {
    templates, tasks, seeded, seed, published, taskStats, completionRate,
    template, startTask, toggleStep, completeTask, publishTemplate, createTemplate,
  }
})
