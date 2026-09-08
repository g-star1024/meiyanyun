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

/** 积分池读模型（GET /customer/points-pool 四口径统计） */
export interface PointsPool {
  /** 累计发放 */
  totalIssued: number
  /** 本月获得 */
  gainedMonth: number
  /** 本月核销 */
  redeemedMonth: number
  /** 90 天内到期 */
  expiring90d: number
}

/** Spring Data Page 序列化结构（积分流水倒序分页） */
export interface PointsLedgerPage {
  content: PointsLedgerDTO[]
  totalElements: number
  totalPages: number
  number: number
  size: number
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
  /** 赠金余额（分）；B18 起充值支持录入赠送金额，消费先扣赠金后扣本金；售卡赠金仍固定 0。 */
  giftBalance?: number
  /** 卡项模板编码快照（CD-/CS-），开卡后模板改价/下架不影响本卡。 */
  productCode?: string | null
  /** 卡类型快照：CARD 储值卡 / COURSE 疗程卡。 */
  cardType?: string | null
  /** 有效期截止（ISO-8601）；null=长期有效。 */
  expiresAt?: string | null
  /** 售卡订单号（OD 单号），开卡幂等键；充值卡无此字段。 */
  saleNo?: string | null
  /** 卡状态：中文 在用/已退/... */
  status: string
  createdAt: string
}

/** 卡储值流水（GET /customer/cards/{cardNo}/ledger 真实字段；金额单位「分」） */
export interface CardLedgerDTO {
  ledgerId: number
  /** 变动类型：RECHARGE 充值/开卡首笔 / CONSUME 消费扣款 / REFUND 退卡退款 / ADJUST 人工调整 */
  changeType: 'RECHARGE' | 'CONSUME' | 'REFUND' | 'ADJUST'
  /** 本次变动金额（分，带符号：充值 +、消费/退款 -） */
  amount: number
  /** 变动后卡余额（分） */
  balanceAfter: number
  /** B18 赠金变动（分，带符号：充值赠送 +、消费先扣赠金 -、退卡清零 -）；冻结/解冻 ADJUST 行为 0/null */
  giftAmount?: number
  /** B18 变动后赠金余额（分） */
  giftAfter?: number
  /** 业务单号：充值 RC 单号 / 消费订单号 / 退卡 CC 单号 */
  bizRef: string
  /** 操作人 */
  operator: string
  createdAt: string
}

/** 充值返回（POST /customer/cards/{cardNo}/recharge）；金额单位「分」 */
export interface RechargeResultDTO {
  ledgerId: number
  /** 充值单号（RC 开头，重放幂等） */
  bizRef: string
  /** 充值后卡余额（分） */
  balanceAfter: number
  /** B18 充值后赠金余额（分） */
  giftAfter: number
}

/** 标签（GET /customer/tags 全量字典，带覆盖客户数） */
export interface CustomerTagDTO {
  tagId: string
  tagName: string
  /** 标签分类（五分类中文枚举：消费/肤质/行为/价值/医疗，库内即中文） */
  category: string
  /** 覆盖客户数（customer_tag_rel group by 聚合，无关联为 0） */
  customerCount?: number
}

/** 客户-标签关联（GET /customer/{id}/tags，仅含 tagId，需与全量标签 join 取名） */
export interface CustomerTagRelDTO {
  customerId: string
  tagId: string
}

/** 标签新建/改名入参（tagName 必填且唯一 ≤32 字，category 五分类；重名 409、非法分类 400） */
export interface TagUpsertReq {
  tagName: string
  category: string
}

/** 标签覆盖汇总（GET /customer/tags/overview） */
export interface TagOverviewDTO {
  /** 标签总数 */
  totalTags: number
  /** 至少打过一个标签的去重客户数 */
  coveredCustomers: number
  /** 累计打标人次（customer_tag_rel 行数） */
  totalAssignments: number
  /** 已打标客户人均标签数 */
  avgTagsPerCustomer: number
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
  /** 按标签过滤：命中该 tagId 的客户（exists 子查询） */
  tagId?: string
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

/** 客户积分流水（账龄倒序分页，单位「积分」） */
export const listPointsLog = (id: string, params?: { page?: number; size?: number }) =>
  client.get<PointsLedgerPage>(`/customer/${id}/points`, { params: { size: 50, ...params } })

/** 人工调分（changeAmt 正=加分/负=扣分；reason 必填；clientToken 幂等键，同键重放不重复加减分） */
export const changeCustomerPoints = (
  id: string,
  data: { changeAmt: number; reason: string; clientToken: string },
) => client.post<PointsLedgerDTO>(`/customer/${id}/points`, data)

/** 客户会员卡（balance 单位「分」） */
export const listCustomerCards = (id: string) =>
  client.get<MemberCardDTO[]>(`/customer/${id}/cards`)

/** 会员卡充值（amount 本金分；giftAmount 赠送金额分，默认 0；payMethod cash/card/wxpay/alipay，禁用 balance；RC 单号重放幂等） */
export const rechargeCard = (cardNo: string, amount: number, payMethod: string, giftAmount = 0) =>
  client.post<RechargeResultDTO>(`/customer/cards/${cardNo}/recharge`, { amount, giftAmount, payMethod })

/** 卡储值流水（账龄正序；amount 带符号分：RECHARGE + / CONSUME - / REFUND -） */
export const listCardLedger = (cardNo: string) =>
  client.get<CardLedgerDTO[]>(`/customer/cards/${cardNo}/ledger`)

/** 全量标签字典（tagId → tagName/category/customerCount） */
export const listAllTags = () =>
  client.get<CustomerTagDTO[]>('/customer/tags')

/** 标签覆盖汇总（标签总数/覆盖客户数/累计打标人次/人均标签数） */
export const getTagOverview = () =>
  client.get<TagOverviewDTO>('/customer/tags/overview')

/** 某客户的标签关联（仅 tagId，需与 listAllTags join 取中文名） */
export const listCustomerTagRels = (id: string) =>
  client.get<CustomerTagRelDTO[]>(`/customer/${id}/tags`)

/** 新建标签（tagName 必填且唯一、category 五分类；重名 409；TG### 由后端生成） */
export const createTag = (data: TagUpsertReq) =>
  client.post<CustomerTagDTO>('/customer/tags', data)

/** 标签改名/改分类（不存在 404、重名 409、分类非法 400） */
export const updateTag = (tagId: string, data: TagUpsertReq) =>
  client.put<CustomerTagDTO>(`/customer/tags/${tagId}`, data)

/** 删除标签定义（后端先级联解绑全部客户关联；返回解绑客户数） */
export const deleteTag = (tagId: string) =>
  client.delete<{ deleted: string; unassignedCustomers: number }>(`/customer/tags/${tagId}`)

/** 给客户打标（重复打标 409） */
export const assignCustomerTag = (id: string, tagId: string) =>
  client.post<CustomerTagRelDTO>(`/customer/${id}/tags/${tagId}`)

/** 给客户删标（解绑单个标签；未打此标 404） */
export const removeCustomerTag = (id: string, tagId: string) =>
  client.delete<{ removed: boolean }>(`/customer/${id}/tags/${tagId}`)

// ============================================================
// 积分商城（M3-20，对接 customer-service /customer/mall/*）
// 后端状态/类型为中文枚举（库内即中文），前端在 store 适配层映射英文码喂字典。
// ============================================================

/** 积分商品（GET /customer/mall/products 真实字段；状态/类型为中文） */
export interface MallProductDTO {
  productId: string
  productName: string
  /** 商品类型中文：项目/实物/优惠券/服务 */
  productType: string
  /** 积分单价（单位「积分」） */
  pointsPrice: number
  /** 库存（-1 表示不限库存，如优惠券） */
  stock: number
  /** 商品状态中文：已上架/已下架（低库存≤50 为前端派生，不入库） */
  status: string
  /** 封面文案/图（可为空，前端按分类派生占位字） */
  cover?: string | null
  description?: string | null
  /** 已兑数量 */
  redeemedCount: number
  createdAt: string
}

/** 兑换单（GET /customer/mall/exchanges 真实字段；customerName/productName 为后端只读冗余） */
export interface MallExchangeDTO {
  exchangeId: string
  productId: string
  customerId: string
  /** 消耗积分总额（= 单价 × 数量；前端单价反推 pointsSpent / qty） */
  pointsSpent: number
  qty: number
  /** 状态中文：待审核/已通过/已拒绝/已发放 */
  status: string
  sign1?: string | null
  sign1Role?: string | null
  signedAt1?: string | null
  sign2?: string | null
  sign2Role?: string | null
  signedAt2?: string | null
  rejectReason?: string | null
  shipName?: string | null
  shipPhone?: string | null
  shipAddress?: string | null
  clientToken?: string | null
  fulfilledAt?: string | null
  createdAt: string
  /** 客户姓名（后端只读解析冗余，直接展示；缺失回退客户 ID） */
  customerName?: string | null
  /** 商品名称（后端只读解析冗余，直接展示；缺失回退商品 ID） */
  productName?: string | null
}

/** 积分规则（GET /customer/mall/rule 真实字段；单行 rule_id=1） */
export interface PointRuleDTO {
  ruleId?: number
  /** 消费 1 元累计积分数（倍率，对应前端 earnPerYuan） */
  earnRate: number
  /** 积分抵扣比例（百分比，前端表单无此格，保存时原值回传） */
  redeemRatio: number
  expireMonths: number
  signInReward?: number | null
  birthdayMultiplier?: number | null
  referralReward?: number | null
  manualGrantEnabled?: boolean | null
  updatedAt?: string | null
}

/** 新建/编辑商品入参（type 兼容英文码 PROJECT/PHYSICAL/COUPON/SERVICE，后端归一中文） */
export interface MallProductCmd {
  name: string
  type: string
  pointsPrice: number
  stock?: number
  cover?: string
  description?: string
}

/** 双签审核入参：店长初审 sign1 + 运营复核 sign2，两签不得同一人 */
export interface MallReviewCmd {
  sign1: string
  sign1Role?: string
  sign2: string
  sign2Role?: string
  reject?: boolean
  rejectReason?: string
}

/** 兑换申请入参（积分按商品定价 × 数量后端计算，防篡改；实物须带收货信息；clientToken 幂等） */
export interface MallPlaceExchangeCmd {
  productId: string
  customerId: string
  qty?: number
  shipName?: string
  shipPhone?: string
  shipAddress?: string
  clientToken?: string
}

/** 商品列表（status 可选：传中文「已上架/已下架」过滤，不传查全部） */
export const listMallProducts = (status?: string) =>
  client.get<MallProductDTO[]>('/customer/mall/products', { params: status ? { status } : {} })

/** 新建商品（stock=0 待上架，>0 或 -1 直接上架） */
export const createMallProduct = (data: MallProductCmd) =>
  client.post<MallProductDTO>('/customer/mall/product', data)

/** 编辑商品资料（名称/类型/定价/说明/封面；库存走 adjust，上下架走 toggle） */
export const editMallProduct = (id: string, data: MallProductCmd) =>
  client.put<MallProductDTO>(`/customer/mall/product/${id}`, data)

/** 上下架切换（库存 0 上架返回 422；幂等：目标状态一致直接返回） */
export const toggleMallProduct = (id: string) =>
  client.post<MallProductDTO>(`/customer/mall/product/${id}/toggle`)

/** 调整库存/积分定价（库存 0 在售自动下架；stock=-1 不限库存） */
export const adjustMallProduct = (id: string, data: { stock?: number; pointsPrice?: number }) =>
  client.post<MallProductDTO>(`/customer/mall/product/${id}/adjust`, data)

/** 积分规则配置（单行） */
export const getMallRule = () =>
  client.get<PointRuleDTO>('/customer/mall/rule')

/** 保存积分规则（全字段覆盖；redeemRatio 表单无此格，原值回传） */
export const saveMallRule = (data: Partial<PointRuleDTO>) =>
  client.put<PointRuleDTO>('/customer/mall/rule', data)

/** 兑换单列表（审核队列；status 可选中文过滤） */
export const listMallExchanges = (status?: string) =>
  client.get<MallExchangeDTO[]>('/customer/mall/exchanges', { params: status ? { status } : {} })

/** 提交兑换申请（生成「待审核」单，审核通过时才扣积分/库存；clientToken 重放幂等） */
export const placeMallExchange = (data: MallPlaceExchangeCmd) =>
  client.post<MallExchangeDTO>('/customer/mall/exchange', data)

/** 双签审核（通过/驳回；非「待审核」返回 409；通过时扣库存+积分，积分不足返回 422） */
export const reviewMallExchange = (id: string, data: MallReviewCmd) =>
  client.post<MallExchangeDTO>(`/customer/mall/exchange/${id}/review`, data)

/** 履约发放（仅「已通过」可履约，其余 409；「已发放」幂等直返） */
export const fulfillMallExchange = (id: string) =>
  client.post<MallExchangeDTO>(`/customer/mall/exchange/${id}/fulfill`)
