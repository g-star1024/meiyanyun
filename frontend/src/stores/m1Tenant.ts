// 门店主数据（M1 集团管控 / 门店主数据）——B49 卡3 接真。
// 数据源：GET /api/stores（store-service，DataScope 注入：区域经理见本区、集团账号见全量；
// E011=REGION_MGR 实测返回华东 6 店，PG store 表全量 23 店系数据域正确收窄）。
// 写侧收窄：门店新建/编辑/停用不支持（OrgAdminController 明示入 Backlog），本 store 只读。
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { listStores } from '@/api/org'

export type TenantStatus = 'OPERATING' | 'SETTING_UP' | 'SUSPENDED'

export interface Tenant {
  id: string // = storeCode
  code: string // 门店编码
  name: string // 门店名称
  region: string // 大区（中文短名：华东/华南…）
  city: string // 城市（由门店名称前缀派生，未匹配显 '—'）
  status: TenantStatus // 已知三态枚举（驱动 tone）；未知中文值归 SUSPENDED 并 console.warn
  statusText: string // 后端中文原文（营业中/筹建中/已关店），页面标签一律用原文
  nature: string // 经营性质（直营/联营）
  openDate: string // 开业日期 YYYY-MM-DD；null → '—'
}

// store 表中文状态 → 页面枚举（PG 实证闭集：营业中 18 / 筹建中 3 / 已关店 2）
const STATUS_FROM_CN: Record<string, TenantStatus> = {
  营业中: 'OPERATING',
  筹建中: 'SETTING_UP',
  已关店: 'SUSPENDED',
}

const TENANT_STATUS_LABEL: Record<TenantStatus, string> = {
  OPERATING: '营业中',
  SETTING_UP: '筹建中',
  SUSPENDED: '已关店',
}

// 城市派生口径：真实库门店名均含城市前缀（如 上海旗舰店/南京新街口店），前缀匹配常量表
const CITY_PREFIXES = [
  '上海', '北京', '广州', '深圳', '杭州', '南京', '苏州', '天津', '青岛',
  '成都', '重庆', '西安', '武汉', '长沙', '郑州', '佛山',
]
function deriveCity(name: string): string {
  return CITY_PREFIXES.find((c) => name.startsWith(c)) ?? '—'
}

export const useM1TenantStore = defineStore('m1Tenant', () => {
  const tenants = ref<Tenant[]>([])
  const loaded = ref(false)
  const loading = ref(false)
  const error = ref('')

  // ---- 查询 ----
  const operating = computed(() => tenants.value.filter((t) => t.status === 'OPERATING'))
  const byRegion = computed(() => {
    const m: Record<string, Tenant[]> = {}
    for (const t of tenants.value) {
      ;(m[t.region] ||= []).push(t)
    }
    return m
  })
  const natures = computed(() => [...new Set(tenants.value.map((t) => t.nature).filter(Boolean))])

  function get(id: string) {
    return tenants.value.find((t) => t.id === id)
  }

  // ---- 拉取（只读） ----
  async function fetchAll(force = false) {
    if (loaded.value && !force) return
    loading.value = true
    error.value = ''
    try {
      const res = await listStores()
      tenants.value = (res.data || []).map((s) => {
        const cn = s.status ?? ''
        let status = STATUS_FROM_CN[cn]
        if (!status) {
          console.warn('[m1Tenant] 未识别的门店营业状态原文：', cn, s.storeCode)
          status = 'SUSPENDED'
        }
        return {
          id: s.storeCode,
          code: s.storeCode,
          name: s.storeName,
          region: s.region ?? '—',
          city: deriveCity(s.storeName),
          status,
          statusText: cn || '—',
          nature: s.nature ?? '—',
          openDate: s.openDate ?? '—',
        }
      })
      loaded.value = true
    } catch (e) {
      error.value = e instanceof Error ? e.message : '门店列表加载失败'
      loaded.value = false
    } finally {
      loading.value = false
    }
  }

  return {
    tenants, loaded, loading, error,
    operating, byRegion, natures, get, fetchAll,
    TENANT_STATUS_LABEL,
  }
})
