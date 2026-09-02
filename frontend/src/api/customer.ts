// ============================================================
// Customer 聚合 API（对接 customer-service）
// 真实后端路径经 Vite proxy / 国密网关转发。
// 合并自旧 m4.ts / mgmt.ts 中重复的 listCustomers / searchCustomers。
// ============================================================
import client from './client'

export interface CustomerDTO {
  customerId: string
  name: string
  phone: string
  gender: string
  /** 会员等级：中文枚举 普通/银卡/金卡/钻石/黑卡（库内即中文，直接展示） */
  level: string
  storeCode: string | null
  /** 门店中文名（后端只读解析冗余，直接展示；无则回退门店编码） */
  storeName?: string | null
  /** 获客渠道：英文码 WALK_IN/WECHAT/... ，展示需经 CUSTOMER_SOURCE 字典转中文 */
  channel?: string | null
  /** 累计消费：decimal，单位「元」 */
  totalSpend?: number | null
  /** 到店次数 */
  visitCount?: number | null
  /** 归属咨询师/员工工号（内部标识） */
  ownerStaffId?: string | null
  /** 归属咨询师中文名（后端只读解析冗余，直接展示；无则回退工号） */
  ownerStaffName?: string | null
  /** 客户状态：中文 活跃/沉睡/流失 */
  status?: string | null
  /** 积分余额（单位「积分」，非元） */
  points?: number | null
  /** 列表接口聚合返回的标签「名称」数组；详情接口无此字段，需另调标签接口 */
  tags?: string[]
  /** 出生日期 yyyy-MM-dd（详情接口返回） */
  birthDate?: string | null
  /** 注册时间 ISO-8601 UTC（详情接口返回） */
  createdAt?: string | null
}

/** Spring Data Page 序列化结构（仅取列表所需字段） */
export interface CustomerPage {
  content: CustomerDTO[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

export interface MemberLevel {
  code: string
  name: string
  threshold: number
  discount: number
}

export interface PointsPool {
  totalPoints: number
  expiringSoon: number
}

/** 积分流水（GET /customer/{id}/points 真实字段；余额单位「积分」） */
export interface PointsLedgerDTO {
  ledgerId: number
  customerId: string
  /** 本次变动积分（正=获得） */
  changeAmt: number
  /** 变动后余额 */
  balanceAfter: number
  /** 变动原因（中文，如「消费累积」「开卡赠积分」） */
  reason: string
  createdAt: string
}

/** 会员卡（GET /customer/{id}/cards 真实字段） */
export interface MemberCardDTO {
  cardNo: string
  customerId: string
  /** 卡项名称（中文） */
  cardItem: string
  storeCode: string | null
  /** 总次数 / 剩余次数（次卡） */
  totalTimes: number | null
  remainTimes: number | null
  /** 卡余额：bigint，单位「分」（展示需 /100 转元） */
  balance: number
  /** 卡状态：中文 在用/已退/... */
  status: string
  createdAt: string
}

/** 标签（GET /customer/tags 全量字典） */
export interface CustomerTagDTO {
  tagId: string
  tagName: string
  /** 标签分类（价值/行为/消费/...） */
  category: string
}

/** 客户-标签关联（GET /customer/{id}/tags，仅含 tagId，需与全量标签 join 取名） */
export interface CustomerTagRelDTO {
  customerId: string
  tagId: string
}

/** 客户列表（分页 + 门店/等级/状态/来源过滤 + 姓名手机号模糊搜索）
 * 等级 level 全站中文契约：普通/银卡/金卡/钻石/黑卡（与真实库 customer.level 一致，前端展示即中文）。 */
export const listCustomers = (params?: {
  page?: number
  size?: number
  storeCode?: string
  level?: string
  status?: string
  channel?: string
  keyword?: string
}) => client.get<CustomerPage>('/customer', { params })

export const getCustomer = (id: string) =>
  client.get<CustomerDTO>(`/customer/${id}`)

export const createCustomer = (data: Omit<CustomerDTO, 'customerId'> & { customerId?: string }) =>
  client.post<CustomerDTO>('/customer', data)

export const searchCustomers = (q: string) =>
  client.get<CustomerDTO[]>('/customer/search', { params: { q } })

export const listMemberLevels = () =>
  client.get<MemberLevel[]>('/customer/member-levels')

export const getPointsPool = () =>
  client.get<PointsPool>('/customer/points-pool')

/** 客户积分流水（单位「积分」） */
export const listPointsLog = (id: string) =>
  client.get<PointsLedgerDTO[]>(`/customer/${id}/points`)

/** 客户会员卡（balance 单位「分」） */
export const listCustomerCards = (id: string) =>
  client.get<MemberCardDTO[]>(`/customer/${id}/cards`)

/** 全量标签字典（tagId → tagName/category） */
export const listAllTags = () =>
  client.get<CustomerTagDTO[]>('/customer/tags')

/** 某客户的标签关联（仅 tagId，需与 listAllTags join 取中文名） */
export const listCustomerTagRels = (id: string) =>
  client.get<CustomerTagRelDTO[]>(`/customer/${id}/tags`)
