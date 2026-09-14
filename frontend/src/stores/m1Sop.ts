import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { shDateStr } from '@/utils/datetime'
import {
  listSopTemplates, listSopTasks, createSopTemplate, publishSopTemplate,
  startSopTask, toggleSopStep, completeSopTask,
  type SopTemplateDTO, type SopTaskDTO,
} from '@/api/sop'

// ============================================================
// 标准作业 SOP：流程模板库 + 门店执行任务 + 步骤勾选
// B49 卡6 全量接真（诚实降级）：
// - 权威源 store-service /stores/sop（模板三态 DRAFT→PUBLISHED 版本末位+1；
//   任务 PENDING→IN_PROGRESS→DONE，OVERDUE 后端派生不落库）。
// - 动作为「乐观本地变更 + 后台同步」：本地即时生效保 view 零改动（同步签名），
//   服务端返回主体后原位替换为权威值；失败回滚/重拉并 console.error。
// - API 不可用/空库时回落本地演示数据（demo=true，行为同原 mock store）。
// ============================================================
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

// ---- DTO → view 模型映射（服务端 id 即 S%02d / TK%02d / 's'+序号，透传）----
function mapTemplate(d: SopTemplateDTO): SopTemplate {
  return {
    id: d.id,
    code: d.code,
    title: d.title,
    category: d.category,
    version: d.version,
    status: d.status,
    owner: d.owner,
    updatedAt: d.updatedAt ?? '',
    applicableStores: d.applicableStores ?? ['ALL'],
    steps: (d.steps ?? []).map((s) => ({
      id: s.id,
      title: s.title,
      desc: s.desc ?? '',
      ...(s.requirePhoto ? { requirePhoto: true } : {}),
    })),
  }
}

function mapTask(d: SopTaskDTO): SopTask {
  const out: SopTask = {
    id: d.id,
    templateId: d.templateId,
    templateTitle: d.templateTitle,
    category: d.category,
    tenantId: d.tenantId,
    tenantName: d.tenantName,
    assignee: d.assignee,
    priority: d.priority,
    dueAt: d.dueAt,
    status: d.status,
    completedSteps: d.completedSteps ?? [],
  }
  if (d.note) out.note = d.note
  if (d.startedAt) out.startedAt = d.startedAt
  if (d.completedAt) out.completedAt = d.completedAt
  return out
}

export const useM1SopStore = defineStore('m1Sop', () => {
  const templates = ref<SopTemplate[]>([])
  const tasks = ref<SopTask[]>([])
  const seeded = ref(false)
  /** 是否为本地演示数据（真实端点不可用/空库回落） */
  const demo = ref(false)

  // ---- seed：先落演示数据保 UI 不空，再拉真实端点；失败/空库回落演示 ----
  let seeding: Promise<void> | null = null
  function seed(): Promise<void> {
    if (seeding) return seeding
    if (seeded.value) return Promise.resolve()
    seeding = (async () => {
      templates.value = mkTemplates()
      tasks.value = mkTasks()
      try {
        const [tplResp, taskResp] = await Promise.all([listSopTemplates(), listSopTasks()])
        const tplList = (tplResp.data ?? []).map(mapTemplate)
        const taskList = (taskResp.data ?? []).map(mapTask)
        if (tplList.length === 0 && taskList.length === 0) throw new Error('空库')
        templates.value = tplList
        tasks.value = taskList
        demo.value = false
      } catch (e) {
        console.error('[m1Sop] 加载 SOP 模板/任务失败，回落本地演示数据', e)
        demo.value = true
      }
      seeded.value = true
    })()
    return seeding
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

  /** 后台同步：成功用服务端主体原位替换；失败回滚/重拉。 */
  function syncTask(id: string, p: Promise<{ data: SopTaskDTO }>, rollback: () => void) {
    p.then((resp) => {
      const idx = tasks.value.findIndex((x) => x.id === id)
      if (idx >= 0 && resp.data) tasks.value[idx] = mapTask(resp.data)
    }).catch((e) => {
      console.error('[m1Sop] 任务操作失败，已回滚', e)
      rollback()
    })
  }

  function startTask(id: string) {
    const t = tasks.value.find((x) => x.id === id)
    if (!t || t.status !== 'PENDING') return
    t.status = 'IN_PROGRESS'
    t.startedAt = shDateStr()
    if (demo.value) return
    const tid = id
    syncTask(tid, startSopTask(tid), () => {
      const cur = tasks.value.find((x) => x.id === tid)
      if (cur) { cur.status = 'PENDING'; delete cur.startedAt }
    })
  }

  function toggleStep(taskId: string, stepId: string) {
    const t = tasks.value.find((x) => x.id === taskId)
    if (!t || t.status === 'DONE') return
    const idx = t.completedSteps.indexOf(stepId)
    if (idx >= 0) t.completedSteps.splice(idx, 1)
    else t.completedSteps.push(stepId)
    if (demo.value) return
    syncTask(taskId, toggleSopStep(taskId, stepId), () => {
      const cur = tasks.value.find((x) => x.id === taskId)
      if (!cur) return
      const i = cur.completedSteps.indexOf(stepId)
      if (i >= 0) cur.completedSteps.splice(i, 1)
      else cur.completedSteps.push(stepId)
    })
  }

  function completeTask(id: string, note: string) {
    const t = tasks.value.find((x) => x.id === id)
    if (!t) return
    const prev = { status: t.status as TaskStatus, completedAt: t.completedAt, note: t.note, steps: [...t.completedSteps] }
    t.status = 'DONE'
    t.completedAt = shDateStr()
    t.note = note
    const tmpl = template(t.templateId)
    if (tmpl) t.completedSteps = tmpl.steps.map((s) => s.id)
    if (demo.value) return
    syncTask(id, completeSopTask(id, note), () => {
      const cur = tasks.value.find((x) => x.id === id)
      if (!cur) return
      cur.status = prev.status
      cur.completedSteps = prev.steps
      if (prev.completedAt) cur.completedAt = prev.completedAt
      else delete cur.completedAt
      if (prev.note) cur.note = prev.note
      else delete cur.note
    })
  }

  function publishTemplate(id: string) {
    const t = templates.value.find((x) => x.id === id)
    if (!t || t.status !== 'DRAFT') return
    const prevVersion = t.version
    t.status = 'PUBLISHED'
    t.version = t.version.replace(/\d+$/, (n) => String(+n + 1))
    if (demo.value) return
    publishSopTemplate(id).then((resp) => {
      const idx = templates.value.findIndex((x) => x.id === id)
      if (idx >= 0 && resp.data) templates.value[idx] = mapTemplate(resp.data)
    }).catch((e) => {
      console.error('[m1Sop] 模板发布失败，已回滚', e)
      const cur = templates.value.find((x) => x.id === id)
      if (cur) { cur.status = 'DRAFT'; cur.version = prevVersion }
    })
  }

  function createTemplate(input: Omit<SopTemplate, 'id' | 'code' | 'version' | 'status' | 'updatedAt'> & { code?: string }): SopTemplate {
    const maxNo = templates.value.reduce((m, t) => {
      const n = Number(t.id.replace(/^S/, ''))
      return Number.isFinite(n) ? Math.max(m, n) : m
    }, 0)
    const idx = maxNo + 1
    const t: SopTemplate = {
      ...input,
      id: `S${String(idx).padStart(2, '0')}`,
      code: input.code || `SOP-${input.category[0]}-${String(idx).padStart(3, '0')}`,
      version: 'v1.0',
      status: 'DRAFT',
      updatedAt: shDateStr(),
    }
    templates.value.unshift(t)
    if (!demo.value) {
      createSopTemplate({
        title: input.title,
        category: input.category,
        owner: input.owner,
        applicableStores: input.applicableStores,
        steps: input.steps.map((s) => ({ title: s.title, desc: s.desc, requirePhoto: s.requirePhoto })),
      }).then((resp) => {
        if (!resp.data) return
        const real = mapTemplate(resp.data)
        const i = templates.value.findIndex((x) => x.id === t.id)
        if (real.id === t.id && i >= 0) {
          templates.value[i] = real
        } else if (i >= 0) {
          templates.value[i] = real
          console.warn('[m1Sop] 新建模板服务端 id 与乐观预测不一致，已按服务端值替换', t.id, '→', real.id)
        } else {
          templates.value.unshift(real)
        }
      }).catch((e) => {
        console.error('[m1Sop] 新建模板失败，已移除乐观条目', e)
        const i = templates.value.findIndex((x) => x.id === t.id)
        if (i >= 0) templates.value.splice(i, 1)
      })
    }
    return t
  }

  return {
    templates, tasks, seeded, demo, seed, published, taskStats, completionRate,
    template, startTask, toggleStep, completeTask, publishTemplate, createTemplate,
  }
})
