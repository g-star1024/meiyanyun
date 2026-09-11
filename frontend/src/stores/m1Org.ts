// 组织架构聚合（M1 集团管控）。
// 树形结构：集团 → 大区 → 门店 → 部门。权威源 org-service /org/tree（B33 去 mock）。
// 写边界（后端强制）：仅可在门店下新建部门；编码/类型不可改；仅部门可跨门店移动；
// 停用必填原因。前端只做交互收口，任何越权/校验以网关返回的中文 message 为准。
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import {
  getOrgTree, createOrgUnit, updateOrgUnit, toggleOrgUnitStatus,
  type OrgTreeNode, type OrgUnitCreatePayload, type OrgUnitUpdatePayload,
} from '@/api/org'
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
  /** 停用时必填的原因（随启停接口持久化并记入审计） */
  inactiveReason?: string
  createdAt: string
}

const ORG_TYPE_LABEL: Record<OrgType, string> = {
  GROUP: '集团', REGION: '大区', STORE: '门店', DEPT: '部门',
}
const ORG_STATUS_LABEL: Record<OrgStatus, string> = {
  ACTIVE: '正常', INACTIVE: '已停用',
}

function mapNode(n: OrgTreeNode): OrgNode {
  const code = n.orgCode
  const type = (n.orgTypeCode || 'STORE') as OrgType
  return {
    id: code,
    code,
    name: n.orgName,
    type,
    parentId: n.parentCode ?? null,
    leaderName: n.leaderName ?? '',
    headcount: n.headcount ?? 0,
    status: n.statusCode === 'INACTIVE' ? 'INACTIVE' : 'ACTIVE',
    sort: n.sortNo ?? 0,
    remark: n.remark ?? undefined,
    inactiveReason: n.inactiveReason ?? undefined,
    createdAt: n.createdAt ?? '',
  }
}

export const useM1OrgStore = defineStore('m1Org', () => {
  const auth = useAuthStore()
  const nodes = ref<OrgNode[]>([])
  const loaded = ref(false)
  const loading = ref(false)
  const loadError = ref('')

  /** 单根树递归展平为节点数组（DataScope 已在树内过滤：只含可见层级） */
  function flatten(root: OrgTreeNode | null): OrgNode[] {
    const out: OrgNode[] = []
    const walk = (n: OrgTreeNode | null | undefined) => {
      if (!n) return
      out.push(mapNode(n))
      for (const c of n.children ?? []) walk(c)
    }
    walk(root)
    return out
  }

  async function load(force = false): Promise<void> {
    if (loaded.value && !force) return
    loading.value = true
    loadError.value = ''
    try {
      const resp = await getOrgTree()
      nodes.value = flatten(resp.data)
      loaded.value = true
    } catch (e: any) {
      loadError.value = e?.response?.data?.message || e?.message || '组织树加载失败'
      throw e
    } finally {
      loading.value = false
    }
  }

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

  // ---- 命令（真实 API；失败向上抛出，由视图展示后端中文 message） ----
  async function create(input: {
    code: string
    name: string
    parentId: string
    leaderName: string
    headcount: number
    sort: number
    remark: string
  }): Promise<void> {
    if (!auth.can('org:edit')) throw new Error('无组织架构编辑权限')
    const payload: OrgUnitCreatePayload = {
      orgCode: input.code,
      orgName: input.name,
      parentCode: input.parentId,
      leaderName: input.leaderName || null,
      headcount: input.headcount,
      sortNo: input.sort,
      remark: input.remark || null,
    }
    await createOrgUnit(payload)
    await load(true)
  }

  /**
   * 编辑节点。parentId 仅部门且发生变化时下发（跨门店移动）；
   * 可空文本始终随表单提交（空串由后端清空）。
   */
  async function update(id: string, patch: {
    name: string
    parentId?: string | null
    leaderName: string
    headcount: number
    sort: number
    remark: string
  }): Promise<void> {
    if (!auth.can('org:edit')) throw new Error('无组织架构编辑权限')
    const payload: OrgUnitUpdatePayload = {
      orgName: patch.name,
      leaderName: patch.leaderName,
      headcount: patch.headcount,
      sortNo: patch.sort,
      remark: patch.remark,
    }
    const cur = get(id)
    if (cur && cur.type === 'DEPT' && patch.parentId != null && patch.parentId !== cur.parentId) {
      payload.parentCode = patch.parentId
    }
    await updateOrgUnit(id, payload)
    await load(true)
  }

  async function setStatus(id: string, status: OrgStatus, reason?: string): Promise<void> {
    if (!auth.can('org:edit')) throw new Error('无组织架构编辑权限')
    if (status === 'INACTIVE' && (!reason || !reason.trim())) {
      throw new Error('停用组织单元必须填写原因')
    }
    await toggleOrgUnitStatus(id, {
      enable: status === 'ACTIVE',
      reason: status === 'INACTIVE' ? reason!.trim() : undefined,
    })
    await load(true)
  }

  return {
    nodes, roots, children, get, descendantIds, totalHeadcount, childTypeCount, canEdit,
    loading, loaded, loadError, load,
    create, update, setStatus,
    ORG_TYPE_LABEL, ORG_STATUS_LABEL,
  }
})
