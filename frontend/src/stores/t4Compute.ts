// ============================================================
// T4 AI 中台底座 - 算力管理 store（T4-卡2 切真 ai-service）
// GPU 节点监控 + 部门配额 + 成本模拟
// 数据源：/api/ai/t4/compute（V79 落库；类级 compute:view，
// 配额分配=compute:alloc，GPU 状态调整=compute:edit）。
// 成本模拟 simulateCost / GPU_MODEL_PRICE 为纯前端逻辑，不落库；
// GPU 遥测（显存/利用率/温度）以服务端种子为准，前端不伪造联动。
// ============================================================
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { useToast } from '@/composables/useToast'
import { errMsg } from './m5Coupon'
import * as api from '@/api/t4Compute'
import type { GpuView, QuotaView } from '@/api/t4Compute'

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
  const toast = useToast()

  const gpus = ref<GpuNode[]>([])
  const quotas = ref<QuotaAllocation[]>([])
  const loaded = ref(false)
  const loading = ref(false)
  const loadError = ref('')

  // ---- 适配层：后端 View → 前端类型（铁律 -1-B：前后端差异只在此消化；id=后端 code） ----
  function mapGpu(v: GpuView): GpuNode {
    return {
      id: v.code,
      name: v.name,
      model: v.model,
      vramTotal: v.vramTotal,
      vramUsed: v.vramUsed,
      utilization: v.utilization,
      temperature: v.temperature,
      status: v.status as GpuStatus,
      currentTask: v.currentTask ?? undefined,
      podName: v.podName ?? undefined,
      costPerHour: Number(v.costPerHour),
      region: v.region,
    }
  }

  function mapQuota(v: QuotaView): QuotaAllocation {
    return {
      id: v.code,
      department: v.department,
      project: v.project,
      gpuHours: v.gpuHours,
      gpuHoursUsed: v.gpuHoursUsed,
      budget: Number(v.budget),
      spent: Number(v.spent),
      period: v.period,
      status: v.status as QuotaAllocation['status'],
    }
  }

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

  // ---- 装载 ----
  async function load() {
    loading.value = true
    loadError.value = ''
    try {
      const data = await api.overview()
      gpus.value = (data.gpus ?? []).map(mapGpu)
      quotas.value = (data.quotas ?? []).map(mapQuota)
      loaded.value = true
    } catch (e) {
      loadError.value = errMsg(e)
      console.warn('[t4Compute] 算力总览加载失败', e)
    } finally {
      loading.value = false
    }
  }

  /** 进页装载（每次进页重拉真实数据） */
  async function seed() {
    await load()
  }

  // ---- 命令 ----
  async function allocateQuota(input: {
    department: string
    project: string
    gpuHours: number
    budget: number
    period: string
  }): Promise<QuotaAllocation | null> {
    if (!auth.can('compute:alloc')) {
      toast.error('无配额分配权限')
      return null
    }
    try {
      const v = await api.allocateQuota({
        department: input.department,
        project: input.project,
        gpuHours: input.gpuHours,
        budget: input.budget,
        period: input.period,
      })
      const q = mapQuota(v)
      quotas.value.unshift(q)
      activity.log(auth.user.name, `分配算力配额：${input.department}/${input.project} ${input.gpuHours} GPU·h / ¥${input.budget}`)
      toast.success(`配额已分配：${input.department}/${input.project}`)
      return q
    } catch (e) {
      toast.error(errMsg(e, '配额分配失败'))
      return null
    }
  }

  async function updateGpuStatus(id: string, status: GpuStatus, patch?: Partial<GpuNode>): Promise<void> {
    if (!auth.can('compute:edit')) {
      toast.error('无算力编辑权限')
      return
    }
    const g = gpus.value.find((x) => x.id === id)
    if (!g) return
    try {
      const v = await api.updateGpuStatus(id, status)
      const next = mapGpu(v)
      Object.assign(g, next)
      if (patch) Object.assign(g, patch)
      activity.log(auth.user.name, `GPU 节点「${g.name}」状态变更为 ${GPU_STATUS_LABEL[status]}`)
      toast.success(`GPU 节点「${g.name}」已置为${GPU_STATUS_LABEL[status]}`)
    } catch (e) {
      toast.error(errMsg(e, 'GPU 状态调整失败'))
    }
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

  return {
    gpus, quotas, kpi, utilizationPct,
    GPU_STATUS_LABEL, GPU_MODEL_PRICE,
    can,
    allocateQuota, updateGpuStatus, simulateCost,
    seed,
  }
})
