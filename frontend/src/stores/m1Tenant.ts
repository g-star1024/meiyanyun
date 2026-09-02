// 门店主数据聚合（M1 集团管控基座）。
// 承载多门店基础档案：编码/区域/城市/店型/状态/店长/经营资质等。
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

export type TenantStatus = 'OPERATING' | 'SETTING_UP' | 'SUSPENDED'
export type TenantType = 'FLAGSHIP' | 'STANDARD' | 'COMMUNITY'

export interface Tenant {
  id: string
  code: string            // 门店编码
  name: string            // 门店名称
  region: string          // 大区
  city: string
  status: TenantStatus
  type: TenantType
  area: number            // 面积（㎡）
  rooms: number           // 治疗室数
  seats: number           // 工位/咨询室数
  openDate: string        // 开业日期 YYYY-MM-DD
  managerName: string
  phone: string
  address: string
  businessHours: string
  licenseNo: string       // 营业执照号
  currency: string
  timezone: string
  remark?: string
  createdAt: string
}

const TENANT_STATUS_LABEL: Record<TenantStatus, string> = {
  OPERATING: '营业中',
  SETTING_UP: '筹建中',
  SUSPENDED: '已停用',
}
const TENANT_TYPE_LABEL: Record<TenantType, string> = {
  FLAGSHIP: '旗舰店',
  STANDARD: '标准店',
  COMMUNITY: '社区店',
}

export const useM1TenantStore = defineStore('m1Tenant', () => {
  const activity = useActivityStore()
  const auth = useAuthStore()
  const tenants = ref<Tenant[]>([])
  const loaded = ref(false)

  // ---- 查询 ----
  const operating = computed(() => tenants.value.filter((t) => t.status === 'OPERATING'))
  const byRegion = computed(() => {
    const m: Record<string, Tenant[]> = {}
    for (const t of tenants.value) {
      ;(m[t.region] ||= []).push(t)
    }
    return m
  })

  function get(id: string) {
    return tenants.value.find((t) => t.id === id)
  }

  function can(perm: string) {
    return auth.can(perm)
  }

  // ---- 命令 ----
  function create(input: Omit<Tenant, 'id' | 'createdAt'>): Tenant {
    if (!can('tenant:edit')) throw new Error('无门店主数据编辑权限')
    const t: Tenant = { ...input, id: nextId('tenant'), createdAt: new Date().toISOString() }
    tenants.value.unshift(t)
    activity.log(auth.user.name, `新建门店「${t.name}」（${t.code}）`, t.id)
    return t
  }

  function update(id: string, patch: Partial<Tenant>) {
    if (!can('tenant:edit')) throw new Error('无门店主数据编辑权限')
    const t = get(id)
    if (!t) return
    Object.assign(t, patch)
    activity.log(auth.user.name, `更新门店「${t.name}」信息`, id)
  }

  function setStatus(id: string, status: TenantStatus, reason?: string) {
    if (!can('tenant:edit')) throw new Error('无门店主数据编辑权限')
    const t = get(id)
    if (!t) return
    t.status = status
    const label = TENANT_STATUS_LABEL[status]
    activity.log(auth.user.name, `门店「${t.name}」状态变更为「${label}」${reason ? `：${reason}` : ''}`, id)
  }

  // ---- 种子 ----
  function seed() {
    if (loaded.value) return
    loaded.value = true
    const now = Date.now()
    const raw: Array<Partial<Tenant> & Pick<Tenant, 'code' | 'name' | 'region' | 'city' | 'status' | 'type' | 'managerName'>> = [
      { code: 'M001', name: '静安旗舰店', region: '华东大区', city: '上海', status: 'OPERATING', type: 'FLAGSHIP', managerName: '苏晴', area: 580, rooms: 8, seats: 6, openDate: '2023-03-18', phone: '021-5288-1001', address: '上海市静安区南京西路 1266 号', businessHours: '10:00-21:00', licenseNo: '91310106MA1EY001XX', currency: 'CNY', timezone: 'Asia/Shanghai' },
      { code: 'M002', name: '徐汇标准店', region: '华东大区', city: '上海', status: 'OPERATING', type: 'STANDARD', managerName: '陈昊', area: 320, rooms: 5, seats: 4, openDate: '2023-09-01', phone: '021-6432-2002', address: '上海市徐汇区淮海中路 999 号', businessHours: '10:00-21:00', licenseNo: '91310104MA1EY002XX', currency: 'CNY', timezone: 'Asia/Shanghai' },
      { code: 'M003', name: '朝阳旗舰店', region: '华北大区', city: '北京', status: 'OPERATING', type: 'FLAGSHIP', managerName: '周岚', area: 620, rooms: 9, seats: 7, openDate: '2022-11-20', phone: '010-8590-3003', address: '北京市朝阳区建国路 88 号', businessHours: '10:00-21:00', licenseNo: '91110105MA1EY003XX', currency: 'CNY', timezone: 'Asia/Shanghai' },
      { code: 'M004', name: '天河标准店', region: '华南大区', city: '广州', status: 'OPERATING', type: 'STANDARD', managerName: '林哲', area: 280, rooms: 4, seats: 3, openDate: '2024-05-12', phone: '020-3880-4004', address: '广州市天河区天河路 208 号', businessHours: '10:00-21:30', licenseNo: '91440101MA1EY004XX', currency: 'CNY', timezone: 'Asia/Shanghai' },
      { code: 'M005', name: '锦江社区店', region: '华东大区', city: '成都', status: 'SETTING_UP', type: 'COMMUNITY', managerName: '待任命', area: 150, rooms: 2, seats: 2, openDate: '2026-10-01', phone: '028-8600-5005', address: '成都市锦江区春熙路 18 号', businessHours: '10:30-20:30', licenseNo: '91510104MA1EY005XX', currency: 'CNY', timezone: 'Asia/Shanghai' },
      { code: 'M006', name: '南山标准店', region: '华南大区', city: '深圳', status: 'SUSPENDED', type: 'STANDARD', managerName: '黄晟', area: 300, rooms: 5, seats: 4, openDate: '2023-06-08', phone: '0755-8600-6006', address: '深圳市南山区科技园南路 66 号', businessHours: '10:00-21:00', licenseNo: '91440305MA1EY006XX', currency: 'CNY', timezone: 'Asia/Shanghai' },
    ]
    raw.forEach((r) => {
      tenants.value.push({
        id: nextId('tenant'), code: r.code!, name: r.name!, region: r.region!, city: r.city!,
        status: r.status!, type: r.type!, managerName: r.managerName!,
        area: r.area ?? 0, rooms: r.rooms ?? 0, seats: r.seats ?? 0,
        openDate: r.openDate ?? '', phone: r.phone ?? '', address: r.address ?? '',
        businessHours: r.businessHours ?? '', licenseNo: r.licenseNo ?? '',
        currency: r.currency ?? 'CNY', timezone: r.timezone ?? 'Asia/Shanghai',
        createdAt: new Date(now - tenants.value.length * 30 * 86400000).toISOString(),
      })
    })
  }

  return {
    tenants, operating, byRegion, get, can,
    create, update, setStatus, seed,
    TENANT_STATUS_LABEL, TENANT_TYPE_LABEL,
  }
})
