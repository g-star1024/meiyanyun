// ============================================================
// T4 AI 中台底座 - 算力管理 store
// GPU 节点监控 + 部门配额 + 成本模拟
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

export type GpuStatus = 'IDLE' | 'BUSY' | 'OFFLINE' | 'RESERVED'

export interface GpuNode {
  id: string
  name: string
  model: string
  vramTotal: number
  vramUsed: number
  utilization: number
  temperature: number
  status: GpuStatus
  currentTask?: string
  podName?: string
  costPerHour: number
  region: string
}

export interface QuotaAllocation {
  id: string
  department: string
  project: string
  gpuHours: number
  gpuHoursUsed: number
  budget: number
  spent: number
  period: string
  status: 'ACTIVE' | 'EXCEEDED' | 'EXPIRED'
}

export const GPU_STATUS_LABEL: Record<GpuStatus, string> = {
  IDLE: '空闲',
  BUSY: '繁忙',
  OFFLINE: '离线',
  RESERVED: '预留',
}

// 各 GPU 型号每小时单价（元）
export const GPU_MODEL_PRICE: Record<string, number> = {
  A100: 28,
  H100: 58,
  V100: 16,
  T4: 8,
}

export const useT4ComputeStore = defineStore('t4Compute', () => {
  const auth = useAuthStore()
  const activity = useActivityStore()

  const gpus = ref<GpuNode[]>([])
  const quotas = ref<QuotaAllocation[]>([])
  const loaded = ref(false)

  // ---- 查询 ----
  const kpi = computed(() => {
    const totalGpus = gpus.value.length
    const busyGpus = gpus.value.filter((g) => g.status === 'BUSY').length
    const totalVram = gpus.value.reduce((s, g) => s + g.vramTotal, 0)
    // 今日成本：按当前运行中 GPU 估算 8 小时
    const costToday = gpus.value
      .filter((g) => g.status === 'BUSY' || g.status === 'RESERVED')
      .reduce((s, g) => s + g.costPerHour * 8, 0)
    return { totalGpus, busyGpus, totalVram, costToday: Math.round(costToday) }
  })

  const utilizationPct = computed(() => {
    const active = gpus.value.filter((g) => g.status !== 'OFFLINE')
    if (!active.length) return 0
    return Math.round(active.reduce((s, g) => s + g.utilization, 0) / active.length)
  })

  function can(perm: string) {
    return auth.can(perm)
  }

  // ---- 命令 ----
  function allocateQuota(input: {
    department: string
    project: string
    gpuHours: number
    budget: number
    period: string
  }): QuotaAllocation {
    if (!auth.can('compute:alloc')) throw new Error('无配额分配权限')
    const q: QuotaAllocation = {
      id: nextId('quota'),
      department: input.department,
      project: input.project,
      gpuHours: input.gpuHours,
      gpuHoursUsed: 0,
      budget: input.budget,
      spent: 0,
      period: input.period,
      status: 'ACTIVE',
    }
    quotas.value.unshift(q)
    activity.log(auth.user.name, `分配算力配额：${input.department}/${input.project} ${input.gpuHours} GPU·h / ¥${input.budget}`)
    return q
  }

  function updateGpuStatus(id: string, status: GpuStatus, patch?: Partial<GpuNode>) {
    if (!auth.can('compute:edit')) throw new Error('无算力编辑权限')
    const g = gpus.value.find((x) => x.id === id)
    if (!g) return
    g.status = status
    if (patch) Object.assign(g, patch)
    activity.log(auth.user.name, `GPU 节点「${g.name}」状态变更为 ${GPU_STATUS_LABEL[status]}`)
  }

  /**
   * 成本模拟器：模型类型 × GPU 型号 × 训练时长 × 并行数 → 预估费用
   */
  function simulateCost(params: {
    modelType: string
    gpuModel: string
    hours: number
    parallel: number
  }): { gpuHours: number; unitPrice: number; totalCost: number } {
    const unitPrice = GPU_MODEL_PRICE[params.gpuModel] ?? 16
    const gpuHours = params.hours * params.parallel
    const totalCost = Math.round(gpuHours * unitPrice)
    return { gpuHours, unitPrice, totalCost }
  }

  // ---- 种子 ----
  function seed() {
    if (loaded.value) return
    loaded.value = true

    const gpuSeed: Array<Partial<GpuNode> & Pick<GpuNode, 'name' | 'model' | 'status' | 'region'>> = [
      { name: 'gpu-a100-01', model: 'A100', status: 'BUSY', region: '上海-可用区A', vramTotal: 80, vramUsed: 68, utilization: 87, temperature: 72, currentTask: '客户流失预测 v3.2 重训练', podName: 'train-mdl-001', costPerHour: 28 },
      { name: 'gpu-a100-02', model: 'A100', status: 'BUSY', region: '上海-可用区A', vramTotal: 80, vramUsed: 74, utilization: 92, temperature: 78, currentTask: '皮肤影像分类 v1.6 训练', podName: 'train-cv-014', costPerHour: 28 },
      { name: 'gpu-a100-03', model: 'A100', status: 'IDLE', region: '上海-可用区B', vramTotal: 80, vramUsed: 0, utilization: 0, temperature: 38, costPerHour: 28 },
      { name: 'gpu-h100-01', model: 'H100', status: 'BUSY', region: '北京-可用区A', vramTotal: 80, vramUsed: 76, utilization: 95, temperature: 83, currentTask: '营销文案 LLM 微调', podName: 'train-llm-002', costPerHour: 58 },
      { name: 'gpu-h100-02', model: 'H100', status: 'RESERVED', region: '北京-可用区A', vramTotal: 80, vramUsed: 12, utilization: 8, temperature: 42, currentTask: '预留：推荐模型 20:00 训练', costPerHour: 58 },
      { name: 'gpu-v100-01', model: 'V100', status: 'BUSY', region: '上海-可用区A', vramTotal: 32, vramUsed: 28, utilization: 76, temperature: 68, currentTask: '门店销量预测定时任务', podName: 'pred-sales-007', costPerHour: 16 },
      { name: 'gpu-v100-02', model: 'V100', status: 'IDLE', region: '上海-可用区B', vramTotal: 32, vramUsed: 0, utilization: 0, temperature: 35, costPerHour: 16 },
      { name: 'gpu-t4-01', model: 'T4', status: 'BUSY', region: '广州-可用区A', vramTotal: 16, vramUsed: 11, utilization: 54, temperature: 62, currentTask: '客服意图在线推理', podName: 'infer-nlp-031', costPerHour: 8 },
      { name: 'gpu-t4-02', model: 'T4', status: 'BUSY', region: '广州-可用区A', vramTotal: 16, vramUsed: 9, utilization: 48, temperature: 58, currentTask: '流失预测在线推理', podName: 'infer-churn-022', costPerHour: 8 },
      { name: 'gpu-t4-03', model: 'T4', status: 'OFFLINE', region: '广州-可用区B', vramTotal: 16, vramUsed: 0, utilization: 0, temperature: 28, costPerHour: 8 },
      { name: 'gpu-t4-04', model: 'T4', status: 'IDLE', region: '广州-可用区B', vramTotal: 16, vramUsed: 0, utilization: 0, temperature: 34, costPerHour: 8 },
      { name: 'gpu-a100-04', model: 'A100', status: 'BUSY', region: '北京-可用区B', vramTotal: 80, vramUsed: 62, utilization: 81, temperature: 71, currentTask: '推荐模型 DeepFM 训练', podName: 'train-rec-009', costPerHour: 28 },
    ]
    gpuSeed.forEach((g) => {
      gpus.value.push({
        id: nextId('gpu'),
        name: g.name!,
        model: g.model!,
        vramTotal: g.vramTotal!,
        vramUsed: g.vramUsed!,
        utilization: g.utilization!,
        temperature: g.temperature!,
        status: g.status!,
        currentTask: g.currentTask,
        podName: g.podName,
        costPerHour: g.costPerHour!,
        region: g.region!,
      })
    })

    const quotaSeed: QuotaAllocation[] = [
      { id: nextId('quota'), department: '数据智能部', project: '客户流失预测', gpuHours: 500, gpuHoursUsed: 342, budget: 14000, spent: 9576, period: '2026-08', status: 'ACTIVE' },
      { id: nextId('quota'), department: '数据智能部', project: '销量预测', gpuHours: 200, gpuHoursUsed: 186, budget: 3200, spent: 2976, period: '2026-08', status: 'ACTIVE' },
      { id: nextId('quota'), department: '皮肤科', project: '皮肤影像分类', gpuHours: 300, gpuHoursUsed: 312, budget: 8400, spent: 8736, period: '2026-08', status: 'EXCEEDED' },
      { id: nextId('quota'), department: '营销中心', project: '文案生成 LLM', gpuHours: 800, gpuHoursUsed: 425, budget: 46400, spent: 24650, period: '2026-Q3', status: 'ACTIVE' },
      { id: nextId('quota'), department: '营销中心', project: '推荐模型训练', gpuHours: 400, gpuHoursUsed: 158, budget: 11200, spent: 4424, period: '2026-08', status: 'ACTIVE' },
      { id: nextId('quota'), department: '客户体验部', project: '智能客服意图', gpuHours: 120, gpuHoursUsed: 120, budget: 1920, spent: 1920, period: '2026-07', status: 'EXPIRED' },
    ]
    quotas.value = quotaSeed
  }

  return {
    gpus, quotas, kpi, utilizationPct,
    GPU_STATUS_LABEL, GPU_MODEL_PRICE,
    can,
    allocateQuota, updateGpuStatus, simulateCost,
    seed,
  }
})
