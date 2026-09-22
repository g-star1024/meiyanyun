// ============================================================
// P5-B85 卡4 合同管理 API（对接 txn-service 合同域：/api/txn/contracts）
//
// 红线（后端强制，前端同契约）：
// ① 合同类型仅支持 COURSE/STORED_VALUE/PACKAGE/SERVICE（后端 400 中文）；
// ② totalAmount 单位「分」必须 > 0；penaltyRate 单位「基点万分比」（2000=20%），0~10000；
// ③ 状态机：草稿 → 生效中 → 已履行；草稿|生效中 → 已终止（须 terminateReason）；
// ④ 仅「生效中」合同可被复购/资产转移单 contractNo 引用（D6 联动）；
// ⑤ 全部状态流转落 append-only 审计链（CONTRACT/CREATE|ACTIVATE|COMPLETE|TERMINATE）。
// 权限：查看 contract:view / 编辑 contract:edit；门店由后端 JWT 数据域收敛。
// ============================================================
import client from './client'

export type ContractStatus = '草稿' | '生效中' | '已履行' | '已终止'
export type ContractType = 'COURSE' | 'STORED_VALUE' | 'PACKAGE' | 'SERVICE'

/** 合同读模型（字段与后端 Contract 实体同名驼峰）。 */
export interface ContractDTO {
  contractNo: string
  customerId: string
  storeCode: string
  /** COURSE 课程 | STORED_VALUE 储值 | PACKAGE 套餐 | SERVICE 服务 */
  contractType: ContractType | string
  title: string
  /** 签署日期（yyyy-MM-dd） */
  signDate: string
  /** 合同总额：bigint，单位「分」（展示需 /100 转元） */
  totalAmount: number
  /** 签署订单快照 JSON 数组字符串：[{orderNo,amount,itemName}] */
  ordersJson: string | null
  /** 关联卡资产快照 JSON 数组字符串：[{cardNo,cardItem}] */
  assetsJson: string | null
  /** 冷静期天数（默认 7） */
  coolingDays: number
  /** 违约金率：基点万分比（2000=20%，展示需 /10000 转 0-1 小数） */
  penaltyRate: number
  refundTerms: string | null
  remarks: string | null
  signedBy: string | null
  status: ContractStatus | string
  effectiveAt: string | null
  completedAt: string | null
  terminatedAt: string | null
  terminateReason: string | null
  createdAt: string
}

export interface CreateContractCmd {
  customerId: string
  storeCode: string
  /** COURSE | STORED_VALUE | PACKAGE | SERVICE */
  contractType: string
  title: string
  /** 签署日期（yyyy-MM-dd），缺省后端取当日 */
  signDate?: string
  /** 合同总额，单位「分」 */
  totalAmount: number
  /** 签署订单快照 JSON 数组字符串 */
  ordersJson?: string
  /** 关联卡资产快照 JSON 数组字符串 */
  assetsJson?: string
  /** 冷静期天数（缺省 7） */
  coolingDays?: number
  /** 违约金率，基点万分比（缺省 2000=20%） */
  penaltyRate?: number
  refundTerms?: string
  remarks?: string
  signedBy?: string
}

/** 合同列表（本店数据域，创建时间倒序；可按 customerId/status 过滤）。 */
export const listContracts = (customerId?: string, status?: string) =>
  client.get<ContractDTO[]>('/txn/contracts', { params: { customerId, status } })

/** 合同详情（跨店/不存在统一 404，不泄露存在性）。 */
export const getContract = (no: string) =>
  client.get<ContractDTO>(`/txn/contracts/${no}`)

/** 新建草稿合同（单号服务端生成 HT+yyyyMMdd-6位）。 */
export const createContract = (cmd: CreateContractCmd) =>
  client.post<ContractDTO>('/txn/contracts', cmd)

/** 草稿 → 生效中（仅生效中可被 repurchase.contractNo 引用）。 */
export const activateContract = (no: string) =>
  client.post<ContractDTO>(`/txn/contracts/${no}/activate`)

/** 生效中 → 已履行。 */
export const completeContract = (no: string) =>
  client.post<ContractDTO>(`/txn/contracts/${no}/complete`)

/** 草稿|生效中 → 已终止（须终止原因；已履行/已终止不可再终止）。 */
export const terminateContract = (no: string, terminateReason: string) =>
  client.post<ContractDTO>(`/txn/contracts/${no}/terminate`, { terminateReason })
