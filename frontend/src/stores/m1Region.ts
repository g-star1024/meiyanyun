// M1 集团域 · 区域管理 store（B49 卡3 接真 · 只读）
// 数据源（均为现成读接口，集团聚合视角）：
//   - GET /api/org/tree             集团→六区→门店全量树（区域节点 orgTypeCode=REGION）
//   - GET /api/stores/regions/dist  六区门店分布统计（营业/直营/联营/筹建/关店/总数；DataScope 豁免收窄的聚合通道）
// 写侧收窄：区域新建/编辑/停用后端不支持（OrgAdminController 明示入 Backlog），本 store 只读。
// 如实说明：①区域经理 leaderName 现网全 null → 页面显 '—'；
//           ②区域短名（华东…）由 orgName 去「事业部」后缀派生，作为与 dist 的匹配键。
import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { getOrgTree, listStoreRegionDist, type StoreRegionDist } from '@/api/org'

export type RegionStatus = 'ACTIVE' | 'INACTIVE'

export interface Region {
  id: string // = orgCode
  code: string // ORG-EAST…
  name: string // 区域短名（华东；与门店 region / dist 键同源）
  orgName: string // 组织全称（华东事业部）
  managerName: string // 区域经理（leaderName 现网全 null → '—'）
  status: RegionStatus
  statusText: string
  sortNo: number
}

export interface RegionStats {
  storeCount: number
  operatingCount: number
  ownCnt: number
  jointCnt: number
  buildingCnt: number
  closedCnt: number
}

export type RegionWithStats = Region & RegionStats

export const REGION_STATUS_LABEL: Record<RegionStatus, string> = {
  ACTIVE: '运营中',
  INACTIVE: '已停用',
}

const EMPTY_STATS: RegionStats = {
  storeCount: 0,
  operatingCount: 0,
  ownCnt: 0,
  jointCnt: 0,
  buildingCnt: 0,
  closedCnt: 0,
}

export const useM1RegionStore = defineStore('m1Region', () => {
  const regions = ref<Region[]>([])
  const dist = ref<StoreRegionDist[]>([])
  const loaded = ref(false)
  const loading = ref(false)
  const error = ref('')

  const withStats = computed<RegionWithStats[]>(() =>
    regions.value.map((r) => {
      const d = dist.value.find((x) => x.region === r.name)
      if (!d) return { ...r, ...EMPTY_STATS }
      return {
        ...r,
        storeCount: d.total,
        operatingCount: d.openCnt,
        ownCnt: d.ownCnt,
        jointCnt: d.jointCnt,
        buildingCnt: d.buildingCnt,
        closedCnt: d.closedCnt,
      }
    }),
  )
  const active = computed(() => regions.value.filter((r) => r.status === 'ACTIVE'))
  const get = (id: string) => regions.value.find((r) => r.id === id)

  async function fetchAll(force = false) {
    if (loading.value) return
    if (loaded.value && !force) return
    loading.value = true
    error.value = ''
    try {
      const [tree, distRows] = await Promise.all([getOrgTree(), listStoreRegionDist()])
      dist.value = distRows.data ?? []
      regions.value = (tree.data?.children ?? [])
        .filter((n) => n.orgTypeCode === 'REGION')
        .slice()
        .sort((a, b) => (a.sortNo ?? 99) - (b.sortNo ?? 99))
        .map((n) => {
          const st: RegionStatus = n.statusCode === 'ACTIVE' ? 'ACTIVE' : 'INACTIVE'
          return {
            id: n.orgCode,
            code: n.orgCode,
            name: n.orgName.replace(/事业部$/, ''),
            orgName: n.orgName,
            managerName: n.leaderName ?? '—',
            status: st,
            statusText: REGION_STATUS_LABEL[st],
            sortNo: n.sortNo ?? 99,
          }
        })
      loaded.value = true
    } catch (e) {
      error.value = e instanceof Error ? e.message : '区域数据加载失败'
    } finally {
      loading.value = false
    }
  }

  return { regions, loaded, loading, error, withStats, active, get, fetchAll, REGION_STATUS_LABEL }
})
