// ============================================================
// BOM 项目配方 & 扣料异常 API（B10）
// 配方挂 store-service（/stores/project-boms，耗材 SKU 与配方同域解析）；
// 扣料异常挂 txn-service（/txn/bom-exceptions，划扣后 afterCommit 自动扣料的异常登记）。
// 自动扣料本身不对页面开放——由划扣事务提交后系统身份回调 store 内部端点。
// 权限码复用耗材域：inventory:consumable:view（读）/ inventory:consumable:edit（写/重试/标记）。
// ============================================================
import client from './client'

/** 配方行（GET /stores/project-boms）。storeCode="GROUP" 表示集团模板，门店码表示本店覆盖行。 */
export interface ProjectBomDTO {
  bomId: string
  projectName: string
  /** GROUP=集团模板；否则为门店编码 */
  storeCode: string
  skuCode: string
  qty: number
  enabled: boolean
  /** 集团模板行 skuName 为 null（跨店不解析）；门店行后端富化耗材名 */
  skuName: string | null
  updatedBy: string | null
  updatedAt: string | null
}

/** 配方新增/更新入参：同 项目+门店+SKU 唯一，重复提交即更新用量/启用态。storeCode 空/GROUP=集团模板。 */
export interface BomUpsertCmd {
  projectName: string
  storeCode: string
  skuCode: string
  qty: number
  enabled?: boolean
}

/** 扣料异常行（GET /txn/bom-exceptions）。一个划扣单最多一条，成功补扣后自动/人工置 RESOLVED。 */
export interface BomExceptionDTO {
  excId: string
  writeoffId: string
  storeCode: string
  storeName: string
  projectName: string | null
  reason: string
  /** PENDING=待处理；RESOLVED=已处理（重试成功或手工标记） */
  status: 'PENDING' | 'RESOLVED'
  failCount: number
  createdAt: string
  resolvedAt: string | null
  resolvedBy: string | null
}

/** 配方列表：projectName 按项目过滤；storeCode 传 GROUP/空查集团模板，传门店码查门店配方，不传查全部可见域。 */
export const listProjectBoms = (params?: { projectName?: string; storeCode?: string }) =>
  client.get<ProjectBomDTO[]>('/stores/project-boms', { params })

/** 新增/更新/停用配方行，返回落库行（storeCode 已规范化为 GROUP 或门店码）。 */
export const upsertProjectBom = (cmd: BomUpsertCmd) =>
  client.post<ProjectBomDTO>('/stores/project-boms', cmd)

/** 扣料异常清单：status 过滤（PENDING/RESOLVED）；门店角色后端强制本店。 */
export const listBomExceptions = (params?: { status?: string; storeCode?: string }) =>
  client.get<BomExceptionDTO[]>('/txn/bom-exceptions', { params })

/** 重试自动扣料：库存不足等原因排除后由系统重放扣料 + 成本事件；成功自动置 RESOLVED。 */
export const retryBomException = (excId: string) =>
  client.post<BomExceptionDTO>(`/txn/bom-exceptions/${excId}/retry`)

/** 手工标记已处理：已走领用审批手工补单等场景，不再自动扣料。 */
export const resolveBomException = (excId: string) =>
  client.post<BomExceptionDTO>(`/txn/bom-exceptions/${excId}/resolve`)
