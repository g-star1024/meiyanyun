// 区域管理聚合（M1 集团管控）。
// 区域是门店的上层组织单元，承载区域经理、覆盖城市、营收目标等。
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'
import { useM1TenantStore } from './m1Tenant'

export type RegionStatus = 'ACTIVE' | 'INACTIVE'

export interface Region {
  id: string
  code: string            // 区域编码
  name: string            // 区域名称
  managerName: string     // 区域经理
  cities: string[]        // 覆盖城市
  monthlyTarget: number   // 月营收目标（元）
  status: RegionStatus
  remark?: string
  createdAt: string
}

const REGION_STATUS_LABEL: Record<RegionStatus, string> = {
  ACTIVE: '运营中',
  INACTIVE: '已停用',
}

export const useM1RegionStore = defineStore('m1Region', () => {
  const activity = useActivityStore()
  const auth = useAuthStore()
  const tenantStore = useM1TenantStore()
  const regions = ref<Region[]>([])
  const loaded = ref(false)

  // ---- 查询 ----
  // 从门店主数据实时统计区域内门店数
  const withStats = computed(() =>
    regions.value.map((r) => {
      const storesInRegion = tenantStore.tenants.filter((t) => t.region === r.name)
      return {
        ...r,
        storeCount: storesInRegion.length,
        operatingCount: storesInRegion.filter((t) => t.status === 'OPERATING').length,
        cityCount: new Set(storesInRegion.map((t) => t.city)).size,
      }
    }),
  )

  const active = computed(() => regions.value.filter((r) => r.status === 'ACTIVE'))

  function get(id: string) {
    return regions.value.find((r) => r.id === id)
  }

  // ---- 命令 ----
  function create(input: Omit<Region, 'id' | 'createdAt'>): Region {
    if (!auth.can('tenant:edit')) throw new Error('无区域管理编辑权限')
    const r: Region = { ...input, id: nextId('region'), createdAt: new Date().toISOString() }
    regions.value.unshift(r)
    activity.log(auth.user.name, `新建区域「${r.name}」（${r.code}）`, r.id)
    return r
  }

  function update(id: string, patch: Partial<Region>) {
    if (!auth.can('tenant:edit')) throw new Error('无区域管理编辑权限')
    const r = get(id)
    if (!r) return
    Object.assign(r, patch)
    activity.log(auth.user.name, `更新区域「${r.name}」信息`, id)
  }

  function setStatus(id: string, status: RegionStatus, reason?: string) {
    if (!auth.can('tenant:edit')) throw new Error('无区域管理编辑权限')
    const r = get(id)
    if (!r) return
    r.status = status
    activity.log(auth.user.name, `区域「${r.name}」${status === 'ACTIVE' ? '启用' : '停用'}${reason ? `：${reason}` : ''}`, id)
  }

  // ---- 种子 ----
  function seed() {
    if (loaded.value) return
    loaded.value = true
    tenantStore.seed()
    const raw: Array<Partial<Region> & Pick<Region, 'code' | 'name' | 'managerName' | 'cities' | 'monthlyTarget' | 'status'>> = [
      { code: 'R-EAST', name: '华东大区', managerName: '陈野', cities: ['上海', '杭州', '南京', '苏州'], monthlyTarget: 5000000, status: 'ACTIVE', remark: '集团核心营收区' },
      { code: 'R-NORTH', name: '华北大区', managerName: '周岚', cities: ['北京', '天津', '青岛'], monthlyTarget: 4200000, status: 'ACTIVE' },
      { code: 'R-SOUTH', name: '华南大区', managerName: '林哲', cities: ['广州', '深圳', '佛山'], monthlyTarget: 3800000, status: 'ACTIVE' },
      { code: 'R-WEST', name: '华西大区', managerName: '待任命', cities: ['成都', '重庆', '西安'], monthlyTarget: 2500000, status: 'ACTIVE', remark: '新拓展区域' },
      { code: 'R-CENTRAL', name: '华中大区', managerName: '赵明', cities: ['武汉', '长沙', '郑州'], monthlyTarget: 2000000, status: 'INACTIVE', remark: '战略筹备中，暂未开放' },
    ]
    raw.forEach((r) => {
      regions.value.push({
        id: nextId('region'), code: r.code!, name: r.name!, managerName: r.managerName!,
        cities: r.cities!, monthlyTarget: r.monthlyTarget!, status: r.status!,
        remark: r.remark, createdAt: new Date().toISOString(),
      })
    })
  }

  return {
    regions, withStats, active, get,
    create, update, setStatus, seed,
    REGION_STATUS_LABEL,
  }
})
