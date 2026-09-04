// 组织架构聚合（M1 集团管控）。
// 树形结构：集团 → 大区 → 门店 → 部门。承载组织单元、负责人、人数、状态。
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { nextId, useActivityStore } from './activity'
import { useAuthStore } from './auth'

export type OrgType = 'GROUP' | 'REGION' | 'STORE' | 'DEPT'
export type OrgStatus = 'ACTIVE' | 'INACTIVE'

export interface OrgNode {
  id: string
  code: string
  name: string
  type: OrgType
  parentId: string | null
  leaderName: string
  headcount: number
  status: OrgStatus
  sort: number
  remark?: string
  /** 停用时必填的原因（本地演示数据，记入本地活动日志；后端组织写接口落地后随写接口持久化） */
  inactiveReason?: string
  createdAt: string
}

const ORG_TYPE_LABEL: Record<OrgType, string> = {
  GROUP: '集团', REGION: '大区', STORE: '门店', DEPT: '部门',
}
const ORG_STATUS_LABEL: Record<OrgStatus, string> = {
  ACTIVE: '正常', INACTIVE: '已停用',
}

export const useM1OrgStore = defineStore('m1Org', () => {
  const activity = useActivityStore()
  const auth = useAuthStore()
  const nodes = ref<OrgNode[]>([])
  const loaded = ref(false)

  // ---- 查询 ----
  const roots = computed(() => nodes.value.filter((n) => n.parentId === null).sort((a, b) => a.sort - b.sort))

  function children(parentId: string | null) {
    return nodes.value.filter((n) => n.parentId === parentId).sort((a, b) => a.sort - b.sort)
  }

  function get(id: string) { return nodes.value.find((n) => n.id === id) }

  /** 某节点的所有后代 id（用于级联统计） */
  function descendantIds(id: string): string[] {
    const result: string[] = []
    const stack = [id]
    while (stack.length) {
      const cur = stack.pop()!
      const kids = nodes.value.filter((n) => n.parentId === cur)
      for (const k of kids) { result.push(k.id); stack.push(k.id) }
    }
    return result
  }

  /** 某节点及其所有后代的总人数 */
  function totalHeadcount(id: string): number {
    const node = get(id)
    if (!node) return 0
    const ids = [id, ...descendantIds(id)]
    return ids.reduce((sum, nid) => sum + (get(nid)?.headcount ?? 0), 0)
  }

  /** 某节点下直接子节点中各类型数量 */
  function childTypeCount(id: string) {
    const kids = children(id)
    return {
      regions: kids.filter((k) => k.type === 'REGION').length,
      stores: kids.filter((k) => k.type === 'STORE').length,
      depts: kids.filter((k) => k.type === 'DEPT').length,
    }
  }

  function canEdit() { return auth.can('org:edit') }

  // ---- 命令 ----
  function create(input: Omit<OrgNode, 'id' | 'createdAt'>): OrgNode {
    if (!auth.can('org:edit')) throw new Error('无组织架构编辑权限')
    const n: OrgNode = { ...input, id: nextId('org'), createdAt: new Date().toISOString() }
    nodes.value.push(n)
    activity.log(auth.user.name, `新建组织单元「${n.name}」（${ORG_TYPE_LABEL[n.type]}）`, n.id)
    return n
  }

  function update(id: string, patch: Partial<OrgNode>) {
    if (!auth.can('org:edit')) throw new Error('无组织架构编辑权限')
    const n = get(id)
    if (!n) return
    Object.assign(n, patch)
    activity.log(auth.user.name, `更新组织「${n.name}」信息`, id)
  }

  function setStatus(id: string, status: OrgStatus, reason?: string) {
    if (!auth.can('org:edit')) throw new Error('无组织架构编辑权限')
    const n = get(id)
    if (!n) return
    if (status === 'INACTIVE' && (!reason || !reason.trim())) {
      throw new Error('停用组织单元必须填写原因')
    }
    n.status = status
    n.inactiveReason = status === 'INACTIVE' ? reason!.trim() : undefined
    activity.log(auth.user.name, `组织「${n.name}」${status === 'ACTIVE' ? '启用' : '停用'}${reason ? `：${reason}` : ''}`, id)
  }

  // ---- 种子 ----
  function seed() {
    if (loaded.value) return
    loaded.value = true
    const now = Date.now()
    // 集团
    const groupId = nextId('org')
    nodes.value.push({
      id: groupId, code: 'G001', name: '美研云医疗集团', type: 'GROUP', parentId: null,
      leaderName: '周岚', headcount: 0, status: 'ACTIVE', sort: 0, remark: '集团总部',
      createdAt: new Date(now - 365 * 86400000).toISOString(),
    })

    // 大区
    const regions = [
      { code: 'R-EAST', name: '华东大区', leaderName: '陈野', headcount: 0, cities: 4 },
      { code: 'R-NORTH', name: '华北大区', leaderName: '周岚', headcount: 0, cities: 3 },
      { code: 'R-SOUTH', name: '华南大区', leaderName: '林哲', headcount: 0, cities: 3 },
      { code: 'R-WEST', name: '华西大区', leaderName: '待任命', headcount: 0, cities: 3, status: 'INACTIVE' as OrgStatus, remark: '新拓展区域' },
    ]
    const regionIds: Record<string, string> = {}
    regions.forEach((r, i) => {
      const id = nextId('org')
      regionIds[r.code] = id
      nodes.value.push({
        id, code: r.code, name: r.name, type: 'REGION', parentId: groupId,
        leaderName: r.leaderName, headcount: r.headcount, status: r.status ?? 'ACTIVE',
        sort: i, remark: r.remark,
        createdAt: new Date(now - (365 - i * 30) * 86400000).toISOString(),
      })
    })

    // 门店（华东）
    const stores = [
      { parent: 'R-EAST', code: 'M001', name: '静安旗舰店', leaderName: '苏晴', headcount: 28 },
      { parent: 'R-EAST', code: 'M002', name: '徐汇标准店', leaderName: '陈昊', headcount: 16 },
      { parent: 'R-NORTH', code: 'M003', name: '朝阳旗舰店', leaderName: '周岚', headcount: 32 },
      { parent: 'R-SOUTH', code: 'M004', name: '天河标准店', leaderName: '林哲', headcount: 14 },
      { parent: 'R-SOUTH', code: 'M006', name: '南山标准店', leaderName: '黄晟', headcount: 12, status: 'INACTIVE' as OrgStatus },
    ]
    const storeIds: string[] = []
    stores.forEach((s) => {
      const id = nextId('org')
      storeIds.push(id)
      nodes.value.push({
        id, code: s.code, name: s.name, type: 'STORE', parentId: regionIds[s.parent],
        leaderName: s.leaderName, headcount: s.headcount, status: s.status ?? 'ACTIVE',
        sort: 0,
        createdAt: new Date(now - 180 * 86400000).toISOString(),
      })
    })

    // 部门（挂在静安旗舰店下）
    const depts = [
      { name: '咨询部', leaderName: '林微', headcount: 8 },
      { name: '医疗部', leaderName: '顾屿', headcount: 6 },
      { name: '运营部', leaderName: '白桥', headcount: 5 },
      { name: '前台收银', leaderName: '夏沫', headcount: 4 },
      { name: '后勤保障', leaderName: '待任命', headcount: 5 },
    ]
    depts.forEach((d, i) => {
      nodes.value.push({
        id: nextId('org'), code: `D${String(i + 1).padStart(3, '0')}`, name: d.name,
        type: 'DEPT', parentId: storeIds[0], leaderName: d.leaderName, headcount: d.headcount,
        status: 'ACTIVE', sort: i,
        createdAt: new Date(now - 90 * 86400000).toISOString(),
      })
    })
  }

  return {
    nodes, roots, children, get, descendantIds, totalHeadcount, childTypeCount, canEdit,
    create, update, setStatus, seed,
    ORG_TYPE_LABEL, ORG_STATUS_LABEL,
  }
})
