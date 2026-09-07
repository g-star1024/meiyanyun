# P3 财务域后续批次方案设计（p-later）：合规写 → 台账写 → 成本域

> 时间：2026-09-05
> 状态：**方案设计，待用户拍板，批准前不动业务代码**（铁律：链路先行、方向决策白天先问用户）
> 前置：P3 第一批只读接线 + 读时聚合已双栈交付（`docs/DELIVERY-P3-B1-2026-09-05.md`）
> 事实基础：2026-09-05 全后端 + 前端只读盘点（文件行号均已实证，非记忆）
> 铁律遵循：meiyun-dev-rules——写接口四件套（校验/幂等/全动作审计/中文错误）；跨服务取数走 RestTemplate（禁直读别域表）；金额后端 Long 分、前端元；业务表 JPA ddl-auto、系统表 Flyway 且已执行迁移禁改；样式零改动；UI 模型≠后端能力诚实降级不伪造。

***

## 〇、设计总原则

1. **资金动作只在 finance 落账，经营域只发事件**：txn/customer 不直接写 finance 表；终审/收款/充值等业务动作完成后，经内部写端点通知 finance，由 finance 校验、幂等落账、审计。经营域保持「业务事实」，finance 保持「资金镜像」，两者可对账、可重放。
2. **幂等键 = 业务单号**：RF/WO/PM/充值单号全局唯一，finance 落账前先查单号，重复通知返回已存在（200 而非 500/409 报错风暴），支持安全重试。
3. **读时聚合与落账镜像并存、不双算**：已收款订单/退款/划扣目前由读时聚合实时算出；引入落账后，台账读模型改为「读 finance 自有分录表」，聚合退为回填/校验来源。迁移期采用「聚合为准、落账补渠道/补动作」的双跑核对，核对平后再切读源（见 §7 迁移与回滚）。
4. **每个写端点四件套齐全**：入参校验（422 中文）、幂等（单号唯一约束 + 先查后写）、全动作审计（audit 服务 jsonb + 哈希链）、中文错误（无技术码外露）。
5. **权限码先行**：新增写动作在 org PermissionMatrix 注册权限码并接入 @RequirePerm；空挂权限码（settlement/commission/inventory）随对应批次激活。

***

## 一、现状盘点（实证事实，设计的事实地基）

### 1.1 finance-service 现状

- [FinanceController.java](../backend/finance-service/src/main/java/com/meiyun/finance/FinanceController.java)：`@RequestMapping("/api/finance")` + 类级 `@RequirePerm("finance:view")`，**9 个 GET、零写端点**：/ledger(L50)、/cards/balance(L63)、/prepay-pool(L69 `findById(1)`)、/tax(L75)、/tax/total(L81)、/accounts(L92)、/revenue(L98)、/outbox(L115)、/outbox/reconcile(L121)。

- [FinanceAggregationService.java](../backend/finance-service/src/main/java/com/meiyun/finance/FinanceAggregationService.java)：读时聚合核心。订单→RF-REVENUE/IN（L121-127，**L125 channel 硬编码 null**）；退款→RF-REFUND/OUT（L129-135）；划扣成对分录（L137-151）；卡余额（L156-184，疗程估值 remainTimes×2000 元 L54，赠送金恒 0）；降级空集合 + log.warn（L198-201/L212-215/L233-238）；mapChannel L262-269（ORIGINAL→null，L267 注释自认「order\_payment 表无流水，渠道未知，留空待后续」）。

- 三张资金表**无任何运行时写入方**：

  - [OutboxRecord.java](../backend/finance-service/src/main/java/com/meiyun/finance/OutboxRecord.java) L14-39：outbox\_id/biz\_type(varchar16)/txn\_no(varchar24)/amount(Long 分)/channel(varchar8)/status(varchar8)/created\_at/reconciled\_at；**无枚举类、无 CHECK 约束**；OutboxRepository 仅 `findByStatusOrderByCreatedAtDesc`；全仓**无 save 调用**。

  - [AccountMirror.java](../backend/finance-service/src/main/java/com/meiyun/finance/AccountMirror.java) L12-25：仅 acct\_id/acct\_name/balance/acct\_type 四字段余额快照，无流水列；Repository 空。

  - [PrepayPool.java](../backend/finance-service/src/main/java/com/meiyun/finance/PrepayPool.java) L12-28：poolId/total/pendingConsume/refundable/earnedPending（Long 分），恒等式 total=三者之和仅存于 javadoc；无 save、无 CHECK。

- finance-service **无 Flyway 迁移目录**（业务表全走 JPA ddl-auto）。

### 1.2 txn-service 现状

- [InternalFinanceController.java](../backend/txn-service/src/main/java/com/meiyun/txn/InternalFinanceController.java)：GET /api/txn/internal/finance-flows（L46-76，`internal:finance-flow`）；OrderFlow 投影 L59-61 字段 orderNo/storeCode/customerId/project/amount/status/createdAt——**不含 payMethod**；writeoffSpec L100-109 固定 status=DONE + 门店 + 时间区间。

- [FinanceFlowDTO.java](../backend/txn-service/src/main/java/com/meiyun/txn/FinanceFlowDTO.java)：OrderFlow record L18-27 无 payMethod 字段。

- [OrderPayment.java](../backend/txn-service/src/main/java/com/meiyun/txn/OrderPayment.java)：表 order\_payment，paymentId(PM+yyyyMMdd-6 位序号)/orderNo/**payMethod(@Column pay\_method，cash/card/wxpay/alipay/balance)**/cashTendered/postedAmount/changeAmount/paidAfter/operator/createdAt；每笔收款真实落库；JPA 建表无 CHECK。

- [PaymentService.java](../backend/txn-service/src/main/java/com/meiyun/txn/PaymentService.java)：支付方式白名单 `Set.of("cash","card","wxpay","alipay","balance")` L29；每笔 payRepo.save L119-128；收齐联动方案单 PAID L140-143；**balance 方式全流程不扣 member\_card.balance**（L61-146）。

- [TxnService.java](../backend/txn-service/src/main/java/com/meiyun/txn/TxnService.java)：confirmRefund L245-277（RF：PENDING\_FINANCE→REFUNDED + refundRepo.save + audit CONFIRM；CC 退卡同理；javadoc L245「资金出入账由 M6 补」；**不通知 finance、不回写 member\_card 状态**；txn 无任何指向 finance 的 RestTemplate 写调用）。

- [TxnRefund.java](../backend/txn-service/src/main/java/com/meiyun/txn/TxnRefund.java)：状态机 PENDING\_REVIEW→PENDING\_FINANCE→REFUNDED/REJECTED（L57-59）；channel ORIGINAL/CASH/TRANSFER（L43-45）；含 fee 手续费。

- [WriteoffRecord.java](../backend/txn-service/src/main/java/com/meiyun/txn/WriteoffRecord.java)：writeoffId(WO+日期+6 位)/orderNo/cardNo/customerId/storeCode/project/timesUsed/amount/operator/status(DONE/ABNORMAL/VOID L46-48)/abnormalReason/sign1/sign2/createdAt。

- [M4FlowController.java](../backend/txn-service/src/main/java/com/meiyun/txn/M4FlowController.java)：POST /writeoff L350-399（仅扣 MemberCard.remainTimes/balance L371-378 + 写 WriteoffRecord L380-393，**无库存联动**）；GET /writeoff/{id} L401；GET /writeoff L414-419（**仅门店数据域 + 时间倒序，无 cardNo/customerId/from/to 参数**）；GET /order-writeoff L478-490。

- [WriteoffRepository.java](../backend/txn-service/src/main/java/com/meiyun/txn/WriteoffRepository.java) L11-25：**无 findByCardNo/findByCustomerId/时间区间方法**。

- [M4RepurchaseController.java](../backend/txn-service/src/main/java/com/meiyun/txn/M4RepurchaseController.java)：双签工单 POST/GET /api/txn/dualsign-ticket（L192/L214，类型含耗材领用/报损，**审批通过后无动账**）；GET /api/txn/cash-settle L230-239（现金日结只读汇总）。

### 1.3 customer-service 现状

- [InternalCardController.java](../backend/customer-service/src/main/java/com/meiyun/customer/InternalCardController.java)：GET /api/customer/internal/card-balances（`internal:card-balance`）。

- [PointsLedger.java](../backend/customer-service/src/main/java/com/meiyun/customer/PointsLedger.java)：积分流水 append-only 表（**储值卡无对应 ledger 表**的对照样板）。

- member\_card 有 balance/remainTimes/status（在用/退卡中/已退卡/已用完）；退卡终审不回写 status。

### 1.4 成本/库存域现状（全空白）

- TK-MATERIAL/TK-COST/TK-DEPRECIATION/TK-LOSS/TK-LABOR 仅存在于前端 [financeCore.ts](../frontend/src/stores/financeCore.ts) mock（SubjectCode L28-30、SUBJECT\_LABEL L40-43、seedLedger L111-115：耗材 1860/420、折旧 1250、报损 680、人工 8400；totalCost 四项和 L312）。

- 后端**无成本实体/表/端点**；store-service 无库存耗材域（仅 Store/RegionDist）；M4 划扣不联动库存。

- 耗材领用/报损仅有双签审批工单（[BizType.java](../backend/meiyun-common/src/main/java/com/meiyun/common/dualsign/BizType.java)：CONSUMABLE 耗材领用 L10、COMMISSION 提成/佣金 L15、SCRAP 报损 L17），审批通过后无动账。

- 咨询师提成无表无端点（finCommission 纯 mock，权限码 finance:commission:\* 空挂）。

### 1.5 前端现状

- [finance.ts](../frontend/src/api/finance.ts)：8 个导出函数 L92-107 全部有后端；无成本/提成/预算/发票 API。

- 16 个 Fin\*.vue：**9 真**（Ledger/Reconcile/Writeoff/Prepay/Monthly/CashDaily/Tax/CardBalance/Abnormal，失败回落 mock seed）、**7 纯 mock**（Cost/Budget/Margin/Commission/Settlement/Invoice/Settings）。

- [finReports.ts](../frontend/src/stores/finReports.ts)：CHANNEL\_LABEL L333-336（cash/wxpay/alipay/card/balance/transfer/bank）；channelFlows L375-392（L385 channel 空→「未标记渠道」）；monthlyTrend L404-421 / storeMonthly L423-433（cost/grossProfit/grossRate 硬编码 0）。

- [FinCashDailyView.vue](../frontend/src/views/FinCashDailyView.vue)：渠道表 L68、红线文案 L89-92（order\_payment 未接入说明）。

- [OrderView.vue](../frontend/src/views/OrderView.vue)：PayMethod 类型 L24、PAY\_METHODS 标签 L139-147；[order.ts](../frontend/src/api/order.ts) OrderPaymentDTO L40-53、payOrder L125、listOrderPayments L129。

- 库存页 [InventoryView.vue](../frontend/src/views/InventoryView.vue)（/m2-inventory）+ m1Procurement.ts L168-185 全 mock。

- 权限码空挂：finance:settlement:*、finance:commission:*、inventory:\*（org PermissionMatrix L43/L92/L94/L161/L179/L231/L232/L303/L306）。

- 网关 [router.go](../gateway/internal/proxy/router.go) L25-33：/api/txn→8083、/api/finance→8087；**/internal/** 走同一公网前缀，仅应用层 token 拦截，网关无网络隔离\*\*。

***

## 二、目标数据模型（finance 落账层）

第一批读时聚合不落库；后续批次要让资金动作可审计、可对账、可出成本，需在 finance-service 新增以下表（**业务表走 JPA ddl-auto**，与现有 outbox\_record 等一致；如需 CHECK 约束/索引补强，用新增 Flyway 迁移 V1，禁改已执行迁移）。

### 2.1 `fund_entry` 资金分录表（台账落账，替代读时聚合为读源）

| 字段           | 类型                 | 说明                                                       |
| ------------ | ------------------ | -------------------------------------------------------- |
| entry\_id    | bigint PK          | 分录 ID（FE+yyyyMMdd-序号 或自增）                                |
| entry\_no    | varchar(24) UNIQUE | 分录业务编号                                                   |
| biz\_ref     | varchar(24)        | 来源业务单号（orderNo/RF 号/WO 号/充值号/工单号），幂等键之一                  |
| biz\_type    | varchar(16)        | ORDER/REFUND/WRITEOFF/RECHARGE/CARD\_CANCEL/COST/ADJUST  |
| subject      | varchar(16)        | RF-REVENUE/RF-REFUND/RF-DEPOSIT/RF-COST/RF-LABOR…        |
| direction    | varchar(4)         | IN/OUT                                                   |
| amount       | bigint             | **Long 分**                                               |
| channel      | varchar(8)         | cash/card/wxpay/alipay/balance/transfer/null（内部结转）       |
| source       | varchar(8)         | CASHIER/ERP                                              |
| store\_code  | varchar(16)        | 门店码（数据域收敛用）                                              |
| idem\_key    | varchar(48) UNIQUE | 幂等键 = biz\_type + ':' + biz\_ref + ':' + subject（成对分录不撞） |
| occurred\_at | timestamptz        | 业务发生时间                                                   |
| created\_at  | timestamptz        | 落账时间                                                     |

- 一对业务动作可生成多条分录（如划扣 = DEPOSIT/OUT + REVENUE/IN），用 idem\_key 区分。

- 台账读模型：`GET /finance/ledger` 改为读 fund\_entry（聚合退为回填/校验，见 §7）。

### 2.2 `card_ledger` 储值卡流水表（customer 域，对齐 PointsLedger 样板）

| 字段             | 类型          | 说明                                           |
| -------------- | ----------- | -------------------------------------------- |
| ledger\_id     | bigint PK   | <br />                                       |
| card\_no       | varchar(24) | 卡号                                           |
| customer\_id   | varchar(24) | 客户                                           |
| change\_type   | varchar(16) | RECHARGE（充值）/CONSUME（消费扣额）/REFUND（退卡）/ADJUST |
| amount         | bigint      | 变动额（分，充值正、消费负）                               |
| balance\_after | bigint      | 变动后余额（对账锚点）                                  |
| biz\_ref       | varchar(24) | 来源单号（充值号/订单号/RF-CC 号）                        |
| operator       | varchar(24) | 经办人                                          |
| store\_code    | varchar(16) | 门店                                           |
| created\_at    | timestamptz | <br />                                       |

- append-only，禁 UPDATE/DELETE；member\_card.balance 为该卡流水的累计快照（对账：Σ card\_ledger = member\_card.balance）。

### 2.3 outbox\_record 补强（枚举 + CHECK + 写入方）

- 新增枚举 `OutboxStatus`：PENDING（已投递待对账）/RECONCILED（已对账）/DIFF（差异）/ADJUSTED（已调平）。

- 新增枚举 `OutboxBizType`：ORDER/REFUND/WRITEOFF/RECHARGE/CARD\_CANCEL/COST。

- DDL（Flyway V1，仅加约束不改列）：`status CHECK (status IN ('PENDING','RECONCILED','DIFF','ADJUSTED'))`、`biz_type CHECK (...)`、`txn_no` 索引、`(biz_type,txn_no)` 唯一（幂等）。

- 写入方：finance 落账时同事务写 outbox（PENDING）；对账核验后置 RECONCILED；差异置 DIFF；调平后置 ADJUSTED。

### 2.4 prepay\_pool / account\_mirror 联动

- prepay\_pool 恒等式 `total = pending_consume + refundable + earned_pending` 加 CHECK（Flyway V1）；充值→total 与 pending\_consume 同增；划扣确认收入→pending\_consume 减、earned\_pending 结转为收入（或按会计口径入 revenue）；退卡→refundable 减、total 减。所有变动经 fund\_entry 留痕，pool 行加行级锁（SELECT … FOR UPDATE）防并发超改。

- account\_mirror 增加 `updated_at`；资金按渠道入账时更新对应账户镜像余额（cash→对公现金、wxpay/alipay→商户户），与 fund\_entry 同事务。

### 2.5 成本域表（B4，store-service 新建库存耗材域 + finance 成本端点）

- `consumable`（耗材档案）：sku/name/unit/cost\_price(分)/store\_code。

- `consumable_stock`（库存余量）：sku/store\_code/qty。

- `consumable_movement`（出入库流水）：sku/qty\_change(正负)/move\_type(PURCHASE/USE/SCRAP)/biz\_ref(工单号)/operator/created\_at。

- `cost_allocation`（成本分摊，finance 域）：period\_month/store\_code/cost\_type(MATERIAL/DEPRECIATION/LOSS/LABOR)/amount(分)/source\_ref/created\_at。

- 折旧/人工为期末分摊（按月录入或规则分摊），耗材/报损由双签工单审批通过后驱动。

***

## 三、批次划分与优先级

| 批次     | 主题                                                   | 类型              | 依赖             | 建议优先级 |
| ------ | ---------------------------------------------------- | --------------- | -------------- | ----- |
| **B2** | 支付渠道采集 + 划扣历史查询                                      | 读链路补全（低风险、立竿见影） | 无              | 🥇 先做 |
| **B3** | 合规写：资金动作落账（退款/退卡终审→finance、outbox 对账写、prepay/账户镜像联动） | 写链路             | B2 渠道字段        | 🥈    |
| **B4** | 台账写：充值/储值链路（card\_ledger + 开卡充值端点 + balance 支付实扣）    | 写链路             | B3 落账框架        | 🥈    |
| **B5** | 成本与库存域（耗材/折旧/报损/人工 → 成本端点 → 月报成本/毛利解锁）               | 新域              | B3 落账框架；双签工单已在 | 🥉    |
| **B6** | 三方对账模型补全 + settlement 结算 + 内部端点网络隔离加固                | 加固              | B3/B4          | 🥉    |

***

## 四、B2 详细设计：支付渠道采集 + 划扣历史查询（读链路补全）

> 本批**不新增写动作**，把「已采集但没接通」的渠道数据接进台账，并补齐划扣历史查询条件。风险最低、用户可见收益直接（日报渠道表解除「未标记渠道」）。

### 4.1 订单分录渠道接通

**改动点（txn 侧投影补字段）**：

- [FinanceFlowDTO.java](../backend/txn-service/src/main/java/com/meiyun/txn/FinanceFlowDTO.java) OrderFlow record L18-27 增 `String payMethod`。

- [InternalFinanceController.java](../backend/txn-service/src/main/java/com/meiyun/txn/InternalFinanceController.java) L58-61：批量查订单区间内 order\_payment（按 orderNo 分组，取每单最后一笔/或按金额加权——口径见下「口径决策」），填 payMethod。

  - 建议新增 OrderPaymentRepository 方法：`findByCreatedAtBetween` 或 `findByOrderNoIn(...)`，在内存按 orderNo 归组；一笔订单可能多次分笔支付（现金+微信），台账订单行渠道取**该单金额最大的一笔** payMethod，并在订单为混合支付时可附 `mixed=true`（前端可展示「混合」）。

- [FinanceAggregationService.java](../backend/finance-service/src/main/java/com/meiyun/finance/FinanceAggregationService.java) toOrderEntry L121-127：L125 的 `null` 改为 `mapPayMethod(o.get("payMethod"))`；新增 mapPayMethod（cash→cash、card→card、wxpay→wxpay、alipay→alipay、balance→balance；未知→null 保守留空）。

- mapChannel L262-269 保留给退款渠道（ORIGINAL/CASH/TRANSFER）；退款 ORIGINAL 时可**反查原订单 payMethod** 作为退回渠道（B2 内一并做：RefundFlow 已带 orderNo，finance 可用订单渠道映射回填 ORIGINAL）。

**口径决策（需用户确认）**：一笔订单多笔支付（混合渠道）时，台账订单行渠道如何呈现？

- 方案 a（建议）：取金额最大笔渠道 + mixed 标记，前端渠道标签「混合（主：微信）」；

- 方案 b：按支付笔数拆成多条台账分录（每条对应一笔 order\_payment），渠道精确但台账行数增多。

### 4.2 划扣历史查询端点补条件

- [M4FlowController.java](../backend/txn-service/src/main/java/com/meiyun/txn/M4FlowController.java) GET /writeoff L414-419 增可选参数 `cardNo`、`customerId`、`from`、`to`（yyyy-MM-dd）。

- [WriteoffRepository.java](../backend/txn-service/src/main/java/com/meiyun/txn/WriteoffRepository.java) L11-25：改用 JPA Specification（或派生方法），叠加 status/门店数据域 + cardNo 精确 + customerId 精确 + created\_at 闭区间；排序保持时间倒序。

- 权限：沿用现有划扣查看权限码；数据域 DataScope.storeSpec 不变。

- 前端：划扣台/卡详情页接 cardNo/customerId 过滤（卡 360 页「该卡划扣历史」、客户 360 页「该客户划扣记录」）。

### 4.3 前端接线

- [finReports.ts](../frontend/src/stores/finReports.ts) channelFlows L375-392：channel 非空后进真实渠道桶；L385「未标记渠道」仅在确无渠道时出现（混合支付按 §4.1 口径）。

- [FinCashDailyView.vue](../frontend/src/views/FinCashDailyView.vue)：红线文案 L89-92 在渠道接通后改为真实渠道分布；保留降级说明。

- 台账 LedgerEntry channel 列真实展示。

### 4.4 B2 验收

- seed 库订单渠道分布非全「未标记渠道」；混合支付口径与用户确认一致；

- 划扣历史按卡号/客户/时间过滤 curl 用例全过；vue-tsc 0 error；双栈部署 + 浏览器复验。

***

## 五、B3 详细设计：合规写（资金动作落账 + outbox 对账）

> 本批首次在 finance 引入写端点。核心：经营域终审/收款动作完成后，通知 finance 落账；finance 校验、幂等、审计、更新镜像。

### 5.1 finance 新增内部写端点（服务间，X-Internal-Token + 内部权限码）

| 方法/路径                                              | 入参                                                                                       | 动作                                                                                            | 幂等                     |
| -------------------------------------------------- | ---------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------- | ---------------------- |
| `POST /api/finance/internal/entries`               | FundEntryCmd（bizRef/bizType/subject/direction/amount 分/channel/storeCode/occurredAt，可批量） | 校验→幂等查 idem\_key→写 fund\_entry + outbox(PENDING) + 更新 account\_mirror/prepay\_pool（同事务）→audit | idem\_key 唯一，重复返回已落账结果 |
| `POST /api/finance/internal/reconcile/outbox/{id}` | outboxId + 对账结果                                                                          | PENDING→RECONCILED（或 DIFF）                                                                    | 状态机校验，非 PENDING 400 中文 |
| `POST /api/finance/internal/adjust/{id}`           | outboxId + 调整分录                                                                          | DIFF→ADJUSTED，补一条 ADJUST 分录                                                                   | 全动作审计                  |

- 权限码：`internal:fund-write`（新增，仅服务间 system 身份）；对账/调平的**人工页面端点**用 `finance:reconcile:*`（面向财务角色）。

- 校验：amount>0、direction∈{IN,OUT}、subject 白名单、storeCode 非空、bizRef 非空；422 中文。

- 审计：每笔落账 audit.record("FUND\_ENTRY", bizRef, actor, "POST", jsonb)；调平额外记录前后差异。

### 5.2 经营域触发点（txn/customer 调 finance）

- **退款/退卡终审**：[TxnService.confirmRefund](../backend/txn-service/src/main/java/com/meiyun/txn/TxnService.java) L245-277 在本地状态改 REFUNDED + audit 之后，**同事务提交后**调用 finance `POST /internal/entries`（RF-REFUND/OUT，渠道取 refund.channel 映射或原单 payMethod；退卡 CC 另加 RF-DEPOSIT/OUT 冲回预收）。

  - 调用方式：新增 txn→finance RestTemplate 客户端（仿 finance 聚合的 internalEntity token 头）；**失败不回滚业务**（退款业务已成立），失败落 txn 本地「待通知」标记 + 定时重试/对账补偿（eventual consistency）；log.warn 告警。

  - 退卡终审同时**回写 member\_card.status**：经 customer 内部端点或 customer 消费退卡事件（退卡中→已退卡）。

- **收款**：PaymentService 收齐 L140-143 后，通知 finance 落 RF-REVENUE/IN（渠道=payMethod）。B2 已让读模型能算出渠道；B3 起改为落账驱动，读时聚合退为校验。

- **划扣**：M4FlowController POST /writeoff L350-399 完成后通知 finance 落成对分录（DEPOSIT/OUT + REVENUE/IN，channel=null 内部结转）+ prepay\_pool 结转。

> 关键决策（需用户确认）：**事件投递可靠性**。方案 a（建议，轻量）：业务库写「outbox 待通知」本地表 + 定时任务重试投递 finance，至少一次 + finance 幂等去重；方案 b（更重）：引入消息队列。单库 MVP 建议 a。

### 5.3 outbox 对账页面（人工）

- `GET /finance/outbox` 已有；新增 `POST /finance/outbox/{id}/mark-reconciled`、`POST /finance/outbox/{id}/mark-diff`、`POST /finance/outbox/{id}/adjust`（权限 finance:reconcile:edit，财务/超管）。

- [FinReconcileView.vue](../frontend/src/views/FinReconcileView.vue) 接真实写按钮（双签？对账调平建议二级确认 + 全审计）；差异态红色、调平留痕。

- `/finance/outbox/reconcile` 统计扩展：byStatus 含 PENDING/RECONCILED/DIFF/ADJUSTED + 净额 + 差异笔数。

### 5.4 B3 验收

- 退款终审后 fund\_entry 出现 RF-REFUND/OUT、outbox PENDING；对账后 RECONCILED；重复通知幂等不双算；

- prepay\_pool 恒等式 CHECK 不被违反；账户镜像随渠道更新；

- 失败补偿：停 finance 时终审业务仍成功、恢复后补偿落账；

- curl 异常路径全中文；审计哈希链 verify ok；双栈 + 浏览器复验对账页。

***

## 六、B4 详细设计：充值/储值台账（card\_ledger + 开卡充值 + balance 实扣）

### 6.1 customer 域：开卡/充值端点 + card\_ledger

- 新增 `POST /api/customer/cards/{cardNo}/recharge`（权限 customer:card:recharge，收银/店长/超管）：

  - 入参：cardNo、amount（分，>0）、payMethod（cash/card/wxpay/alipay；**充值不能用 balance**）、operator、storeCode；

  - 动作：校验卡状态在用→member\_card.balance += amount（行锁）→**写 card\_ledger（RECHARGE，balance\_after 快照）**→audit；

  - 幂等：充值单号 RC+yyyyMMdd-序号，idem 唯一；重复提交 409/返回已存在。

- 退卡终审联动：card\_ledger 写 REFUND（负额）、member\_card.status=已退卡、余额清零（balance\_after=0）。

- **balance 支付实扣**：[PaymentService.java](../backend/txn-service/src/main/java/com/meiyun/txn/PaymentService.java) pay() L61-146 当 payMethod=balance 时，经 customer 内部端点 `POST /api/customer/internal/cards/consume`（cardNo/customerId/amount/orderNo）扣 member\_card.balance 并写 card\_ledger（CONSUME）；余额不足 422「储值余额不足，当前余额 X 元」；跨服务失败则整笔收款失败回滚（不能出现订单收款成功但卡没扣）。

  - 顺序：先扣卡（customer）→ 再写 order\_payment（balance）→ 收齐联动；扣卡失败即中止。

### 6.2 finance 联动

- 充值成功 → finance 落 RF-DEPOSIT/IN（资金流入预收池，渠道=充值支付方式）+ prepay\_pool.total/pending\_consume 同增 + account\_mirror 按渠道增。

- balance 消费（用储值余额支付订单）→ 资金不进出商户户（余额已是预收负债），台账记 RF-DEPOSIT/OUT（预收转出）+ RF-REVENUE/IN（确认收入），channel=balance/内部结转。

### 6.3 前端接线

- 收银台/卡 360 页新增「充值」按钮 + 支付方式选择（OrderView PayMethod L24 复用，充值剔除 balance）；

- 卡详情页「储值流水」接 card\_ledger 查询端点（新增 `GET /api/customer/cards/{cardNo}/ledger`）；

- [FinCardBalanceView.vue](../frontend/src/views/FinCardBalanceView.vue) 卡余额合计与 card\_ledger 累计可对账；

- [FinPrepayView.vue](../frontend/src/views/FinPrepayView.vue) 预收池随充值/划扣/退卡真实变动。

### 6.4 B4 验收

- 充值后 card\_ledger 有 RECHARGE、member\_card.balance 增加、finance 预收池增加、账户镜像增加，四处金额一致；

- balance 支付订单真实扣卡、余额不足中文拦截、card\_ledger CONSUME 记录；

- Σ card\_ledger = member\_card.balance 对账平；幂等重放不双充；双栈 + 浏览器复验。

***

## 七、读模型切换与迁移/回滚（B3/B4 共用）

- **双跑期**：/finance/ledger 保留读时聚合结果，同时落 fund\_entry；新增内部校验端点 `GET /api/finance/internal/ledger-diff?from&to` 比对「聚合净额 vs 落账净额」，差异为 0 才切。

- **切换**：差异平后，ledger 读源切为 fund\_entry（聚合代码保留为冷备/回填工具）。

- **历史回填**：切换前提供一次性回填任务（按既有 orders/refunds/writeoffs 生成 fund\_entry，idem\_key 幂等，可重复执行）。

- **回滚**：若落账异常，ledger 可切回读时聚合（配置开关），fund\_entry 保留不删；写端点可 feature flag 关闭，经营域降级为「只记业务不通知」。

***

## 八、B5 详细设计：成本与库存域（解锁月报成本/毛利）

### 8.1 store-service 新建库存耗材域

- 表：consumable / consumable\_stock / consumable\_movement（§2.5），JPA ddl-auto。

- 端点：

  - `GET/POST /api/store/consumables`（档案，inventory:consumable:\* 权限）；

  - `POST /api/store/consumables/{sku}/stock-in`（采购入库，写 movement PURCHASE + stock 增）；

  - 双签工单审批通过回调：CONSUMABLE（耗材领用）→ movement USE（stock 减）+ finance cost\_allocation(MATERIAL)；SCRAP（报损）→ movement SCRAP + cost\_allocation(LOSS)。

    - 接线点：M4RepurchaseController 双签工单审批通过处（L192 附近）增「审批通过后动账」分支，调 store 扣库 + finance 落成本。

  - `GET /api/store/consumables/movements`（出入库流水，可按 sku/门店/时间）。

- M4 划扣联动库存（M5 遗留项，可评估纳入）：服务项目绑定耗材 BOM，POST /writeoff 同事务扣耗材——**建议 B5 先做「双签领用驱动」，划扣自动扣耗材列为 B5+ 可选**（需先建项目-耗材 BOM 映射，工作量另估）。

### 8.2 finance 成本端点

- `POST /api/finance/cost-allocation`（finance:cost:edit）：期末录入/分摊折旧（DEPRECIATION）、人工（LABOR）；耗材/报损由工单自动写入 MATERIAL/LOSS。

- `GET /api/finance/cost?month&storeCode`：按月/门店汇总四类成本（Long 分）。

- 成本分录落 fund\_entry（RF-COST/OUT，cost\_type 区分）+ outbox。

### 8.3 前端解锁

- [FinCostView.vue](../frontend/src/views/FinCostView.vue) 接 /finance/cost 真实四类成本（替 mock seedLedger L111-115）；

- [FinMonthlyView.vue](../frontend/src/views/FinMonthlyView.vue)：成本/毛利/毛利率由「待接入」转真实（revenue - cost = grossProfit），**评级列在无真实评级模型前不恢复**（诚实降级纪律）；

- [finReports.ts](../frontend/src/stores/finReports.ts) monthlyTrend L404-421/storeMonthly L423-433 cost/grossProfit/grossRate 接真实；

- \[FinMarginView\.vue] 毛利分析接真实收入-成本；

- 库存页 [InventoryView.vue](../frontend/src/views/InventoryView.vue) + m1Procurement.ts 去 mock 接 consumable 端点；

- \[FinCommissionView\.vue] 提成：双签 COMMISSION 工单审批通过 → cost\_allocation(LABOR)，或明确「提成走外部薪酬系统」维持降级（需用户拍板口径，页面 L2-4 现有注释即此说）。

### 8.4 B5 验收

- 耗材领用双签审批通过后库存真实扣减、finance 成本落账；报损同理；

- 月报成本=四类合计、毛利=收入-成本、毛利率口径正确；毛利率异常（负/超 100%）红字提示；

- 库存页/成本页/月报浏览器复验真实数据；vue-tsc 0 error。

***

## 九、B6 详细设计：三方对账模型补全 + 结算 + 加固

### 9.1 三方对账

- 三方 = 经营域（txn 订单/退款/划扣）× 资金域（finance fund\_entry/账户镜像）× 渠道/外部（现金日结、微信/支付宝商户流水——本期外部流水无自动导入，先做「内部两方对账 + 现金日结」）。

- `GET /api/finance/reconcile/tripartite?date&storeCode`：按日聚合 订单收款/退款/划扣（txn）vs fund\_entry（finance）vs cash-settle（M4RepurchaseController L230 现金日结），输出每方净额与差异笔数；差异列 DIFF 待人工调平。

- \[FinReconcileView\.vue] 增「三方对账」标签页。

### 9.2 结算 settlement（权限码已空挂）

- 激活 finance:settlement:\* 权限码；`POST /api/finance/settlement`（日结/月结锁定：把当日/当月分录置 SETTLED，后续不可改、差错走 ADJUST）；`GET /finance/settlement?period`。

- \[FinSettlementView\.vue] 去 mock；结算后台账期间封账。

### 9.3 加固

- **内部端点网络隔离**：网关 [router.go](../gateway/internal/proxy/router.go) L25-33 对 `/api/*/internal/**` 增加仅内网/服务网格可达的拦截（或独立内部端口不经过公网网关），不再仅靠应用层 token。

- 发票/预算/设置（FinInvoice/FinBudget/FinSettings）：本期建议维持降级或单列（发票依赖外部开票、预算属管控域），需用户拍板是否进当期。

***

## 十、页面接线总清单

| 页面                                               | 现状            | 批次    | 接线动作                            |
| ------------------------------------------------ | ------------- | ----- | ------------------------------- |
| FinCashDailyView                                 | 真（渠道全「未标记」）   | B2    | 渠道分布真实、混合口径                     |
| FinLedgerView                                    | 真（订单行渠道 null） | B2→B3 | B2 渠道填充；B3 读源切 fund\_entry      |
| 卡360/客户360 划扣历史                                  | 无             | B2    | writeoff 按 cardNo/customerId/时间 |
| FinReconcileView                                 | 真（只读）         | B3/B6 | outbox 对账写按钮、三方对账页              |
| FinPrepayView                                    | 真（静态 940 万）   | B3/B4 | 预收池随充值/划扣/退卡真实变动                |
| 卡详情/收银台充值                                        | 无             | B4    | 充值按钮、card\_ledger 流水            |
| FinCardBalanceView                               | 真             | B4    | 余额与 card\_ledger 对账             |
| FinCostView                                      | mock          | B5    | 四类成本真实                          |
| FinMonthlyView                                   | 真（成本待接入）      | B5    | 成本/毛利/毛利率真实（评级不恢复）              |
| FinMarginView                                    | mock          | B5    | 毛利分析真实                          |
| InventoryView                                    | mock          | B5    | 耗材档案/库存/流水真实                    |
| FinCommissionView                                | mock          | B5?   | 提成口径拍板（外部薪酬 vs 工单驱动）            |
| FinSettlementView                                | mock          | B6    | 日结/月结封账                         |
| FinBudgetView / FinInvoiceView / FinSettingsView | mock          | 待定    | 拍板是否进当期                         |

***

## 十一、红线与风险

1. **资金安全**：所有写端点四件套（校验/幂等/审计/中文错误）；金额 Long 分；并发更新行锁；禁硬编码余额。
2. **不双算**：读时聚合与落账双跑期以 diff 校验为准，切读源前必须净额为 0。
3. **跨服务一致性**：业务成立但通知 finance 失败不得回滚业务，走本地 outbox + 补偿重试；balance 支付反向（先扣卡后落单）失败即中止，防「收款成功卡未扣」。
4. **禁直读别域表**：txn/customer/finance 互访一律 RestTemplate + internal token；DDL 变更走新增 Flyway（finance 首建 V1），禁改已执行迁移。
5. **诚实降级**：无数据源的数字（评级、预算、发票、外部渠道流水）不伪造，显式「待接入」。
6. **权限**：新权限码先注册 PermissionMatrix 再用；内部写端点仅服务间 system 身份。

***

## 十二、需用户拍板的决策点

1. **批次顺序**：是否按 B2（渠道+划扣查询，低风险快赢）→ B3（合规写落账）→ B4（充值储值）→ B5（成本库存）→ B6（对账结算加固）推进？
2. **混合支付渠道口径**（§4.1）：取最大笔渠道+混合标记（方案 a，建议）vs 按支付笔拆多条分录（方案 b）。
3. **事件投递可靠性**（§5.2）：本地 outbox 表 + 定时重试 + finance 幂等（方案 a，建议，贴合单库 MVP）vs 引入消息队列（方案 b）。
4. **提成口径**（§8.3）：双签 COMMISSION 工单审批通过驱动成本入账 vs 维持「走外部薪酬系统」降级。
5. **划扣自动扣耗材**（§8.1）：B5 仅做双签领用驱动，划扣自动扣 BOM 耗材列为 B5+ 可选——是否当期做。
6. **预算/发票/设置三页**：是否进当期，还是继续 HOLD。
7. **内部端点网络隔离**（§9.3）：B6 随批次做，还是提前到 B3（首个写端点）前做（更安全但需动网关）。

> 批准后按批次开工，每批独立过 DoD（fat jar ≥20MB + vue-tsc 0 error + curl 异常路径中文 + SQL/金额对账 + 双栈部署 + 浏览器真实验证 + 同类全站扫）。

