# P3 运营自动化四域方案设计（p-ops）：支付渠道接入 · 月结成本结转 · 薪酬提成 · BOM 自动扣料

> 时间：2026-09-06
> 状态：**方案设计，用户已拍板四域方向，本文件给出落地细节，按批次实施**
> 用户决策原文（2026-09-06）：
> 1. 非现金渠道账实接入（B7 §七.1）：**通过 API 接口接入，接口在支付方式里配置**；
> 2. 月结成本结转自动化（B7 §七.4）：**看竞品，按竞品通行做法设置，最好后台可配置、随时调整**；
> 3. 咨询师提成自动化（DESIGN 决策点 #4）、BOM 自动扣耗材（决策点 #5）：**薪酬、提成后台设置——后台设置好每个咨询师的薪酬（底薪）+ 提成比例；库存自动动作分析竞品后安排**。
> 事实基础：2026-09-06 三路代码链路调研（支付 / 成本月结+库存 / 提成，文件行号均实证）+ 三方向竞品调研（提成 / 期末结转 / 项目 BOM 扣料）。
> 铁律遵循：meiyun-dev-rules——写接口四件套（校验/幂等/全动作审计/中文错误）；跨服务取数走 RestTemplate 内部端点 + X-Internal-Token（禁直读别域表）；金额后端 Long 分、前端元；业务表 JPA ddl-auto、系统表 Flyway 且已执行迁移禁改；种子库 meiyun_seed 走 Flyway、prod meiyun_core 不走；样式零改动（接真实 API 只换数据源、不重排页面）；UI 模型≠后端能力诚实降级不伪造。

---

## 〇、设计总原则

1. **后台可配置、随时调整、不追溯历史**：四域的规则全部落表，前端有维护入口，生效中规则可停用/改比例/改金额；规则变更只影响变更后的新业务/新期间，历史单据与历史提成不重算（竞品通行：规则调整不追溯）。
2. **资金红线不变**：计提/结转/提成试算审批**全部只产生成本镜像（cost_allocation / commission_record），绝不在 fund_entry 生成资金实付分录**；提成 PAID 仅镜像外部薪酬系统回传状态，不划款（沿用 finCommission 红线）。
3. **复用既有闭环通道，不另造管道**：BOM 扣料复用审批扣库 `StoreConsumableClient.deduct`（bizRef 幂等 + 移动平均 + 行锁）；耗材成本归集复用 `emitConsumableCost` → TK-MATERIAL；结转成本复用 `FundEntryService.recordManualCost` 通道（source 扩展为 SYSTEM）；封账复用 SettlementService 快照。
4. **跨域只读内部端点**：finance 取业绩走 txn 内部聚合端点（禁直读 txn 表）；txn 取 BOM/渠道配置走 store/txn 自有表；org 员工档案不扩薪酬字段（薪酬属财务敏感域，独立表挂 finance）。
5. **诚实降级**：微信/支付宝/银行**真实第三方支付与账单拉取本期不联调**（需商户号/证书/对公账户，现场不具备），渠道配置域与「账单导入 + 系统账勾兑」先落地，真实拉取预留适配器接口（ChannelGateway），不伪造账单数据。
6. **不阻断医疗服务**：BOM 自动扣料失败（库存不足/SKU 缺失/服务异常）**绝不回滚划扣**，写入扣料异常登记，划扣照常 DONE，异常进清单由门店处理。

---

## 一、现状盘点（实证事实，设计的事实地基）

### 1.1 支付/渠道域（txn-service）

- 支付方式白名单硬编码：[PaymentService.java](../backend/txn-service/src/main/java/com/meiyun/txn/PaymentService.java) L29 `Set.of("cash","card","wxpay","alipay","balance")`；[FundEntryService.java](../backend/finance-service/src/main/java/com/meiyun/finance/FundEntryService.java) L46 CHANNELS 多一个 `transfer`。
- 收款落库：[OrderPayment.java](../backend/txn-service/src/main/java/com/meiyun/txn/OrderPayment.java) 表 order_payment，pay_method varchar(16) 存小写码，posted_amount 分；每笔真实落库；txn_order 无 pay_method 字段。
- 渠道资金镜像：FinanceEventPublisher.resolvePayChannel L248-261 取 posted_amount 最大笔 pay_method，wxpay→ACCT-WX、alipay→ACCT-ALI、cash/card/transfer→ACCT-CASH（FundEntryService L231-256）；多渠道 memo 标「（混合支付）」。
- 字典 PAY_METHOD（V2__seed_dictionaries.sql L39-43）大写口径 CASH/WECHAT/ALIPAY/CARD/CREDIT，与写库小写码两套并存（本期不强行并轨，配置表以小写渠道码为准并给中文名映射）。
- **后端无任何支付渠道配置表、无第三方对接代码**；唯一配置 UI [MpSettingsView.vue](../frontend/src/views/MpSettingsView.vue)（/admin/mp-settings，权限 integration:view）+ [mpSettings.ts](../frontend/src/stores/mpSettings.ts) 为**纯内存 mock**（AppID/mchId/APIv3 密钥/证书序列号/回调地址，savePay 无 http，刷新即丢）。
- 账实核对：/m6-reconcile 现金双签工单 + 现金日结（M4RepurchaseController /cash-settle）；**微信/支付宝/银行回单无导入、无勾兑**。
- 收银台五方式按钮：[OrderView.vue](../frontend/src/views/OrderView.vue) L141-147，真实调 api/order.ts payOrder。

### 1.2 成本月结域（finance-service）

- 成本归集：[CostAllocation.java](../backend/finance-service/src/main/java/com/meiyun/finance/CostAllocation.java) 表 cost_allocation（JPA 建表），四类 cost_type：MATERIAL 耗材领用 / LOSS 耗材报损（审批终审自动落账，emitConsumableCost→postOne→applyCostAllocation L377-388）；DEPRECIATION 折旧 / LABOR 人工（**仅** [FundEntryService.recordManualCost](../backend/finance-service/src/main/java/com/meiyun/finance/FundEntryService.java) L314-362 手工录入，source=MANUAL，occurredAt=归属月 15 日，MATERIAL/LOSS 手工录抛 422）。
- 封账：[SettlementService.java](../backend/finance-service/src/main/java/com/meiyun/finance/SettlementService.java) close() L63-151——校验未来期间 422 / 幂等 duplicated / 快照 fund_entry 净额 / 插 settlement_period 锁行（V7 迁移，status CHECK 仅 CLOSED、无解封）；**不生成任何结转分录**；差错走 ADJUST 调平分录落当前期间。
- 无设备资产实体（finCost.ts mockRows L50-76 含假来源「设备资产原值 50.4 万/10 年」「薪酬镜像」，后端均不存在）；无薪酬系统。
- 财务设置：[FinSetting.java](../backend/finance-service/src/main/java/com/meiyun/finance/FinSetting.java) + FinConfigService，仅 commission_pay_day（发放日 1-28 默认 10，L323/L386 校验）。

### 1.3 库存/BOM 域（store-service）

- 三表真实：consumable（category CONSUMABLE/PRODUCT/DRUG/DEVICE，cost_price 移动平均，safety_stock）、consumable_stock（qty）、consumable_movement（PURCHASE 入库/USE 领用/SCRAP 报损/ADJUST 盘点，unit_cost，biz_ref 幂等）。
- [ConsumableService.java](../backend/store-service/src/main/java/com/meiyun/store/consumable/ConsumableService.java)：stockIn L102-134（batchNo 幂等、移动平均加权）；deduct L146-194（仅 USE/SCRAP、PESSIMISTIC_WRITE 行锁、bizRef+SKU 幂等、库存不足 422 整批回滚）。
- 出库唯一通道：[InternalConsumableController](../backend/store-service/src/main/java/com/meiyun/store/consumable/InternalConsumableController.java) `POST /api/stores/internal/consumables/deduct`（internal:consumable-write + X-Internal-Token，DeductCmd(bizRef,storeCode,moveType,operator,lines)）；txn 侧 [ApprovalService.deductOnFinalApprove](../backend/txn-service/src/main/java/com/meiyun/txn/ApprovalService.java) L230-258 终审先扣库再 emitConsumableCost，[StoreConsumableClient.java](../backend/txn-service/src/main/java/com/meiyun/txn/StoreConsumableClient.java) L58-118（4xx 中文透传、5xx→502、失败回滚）。
- **全仓无 BOM/配方表；治疗划扣不扣物料**（WriteoffDeskService grep consumable/material/sku 零匹配）。
- BOM 触发点：[WriteoffDeskService.execute()](../backend/txn-service/src/main/java/com/meiyun/txn/WriteoffDeskService.java) L157-237，L214 落 WriteoffRecord（writeoffId WO 单号、storeCode、project 项目名、customerId、amount、operator），L217 emitWriteoffDone；与审批扣库同构。

### 1.4 薪酬提成域

- [finCommission.ts](../frontend/src/stores/finCommission.ts) **纯前端 mock，活规格**：CommissionRule{base: ORDER|WRITEOFF|RECHARGE, role: CONSULTANT|DOCTOR|BEAUTICIAN, tiers[{min,rate,label}] 超额累进}；calcTiers L120-132 个税式分段（跨档只对超出部分按高档）；内置 r1 咨询师 WRITEOFF 0+6%/8万+8%/15万+10%/25万+12%、r2 医生 0+10%/10万+12%；状态机 DRAFT→SUBMITTED→APPROVED→PAID/REJECTED；红线注释：仅试算+审批+登记，**发放走外部薪酬系统、绝不动账**；种子 consultantId 为 u01-u05（与真实工号 SE00x 不一致，后端化时纠正）。
- 业绩取数：[TxnOrder.java](../backend/txn-service/src/main/java/com/meiyun/txn/TxnOrder.java) consultant varchar(32) 存工号（SE007）；[WriteoffRecord.java](../backend/txn-service/src/main/java/com/meiyun/txn/WriteoffRecord.java) **无 consultant 字段**——按咨询师汇总划扣业绩需 writeoff_record JOIN txn_order ON order_no（txn 侧内部聚合端点实现）。
- 员工档案：[Staff.java](../backend/org-service/src/main/java/com/meiyun/org/Staff.java) staff_id(SE00x)/staff_name/role_code/store_code/status，**无任何薪酬字段**；[RbacAdminController](../backend/org-service/src/main/java/com/meiyun/org/RbacAdminController.java) 无通用编辑端点。
- 权限码已注册（[PermissionMatrix.java](../backend/org-service/src/main/java/com/meiyun/org/PermissionMatrix.java)）：finance:commission:view L95 / approve L234,L535 / edit L310,L999；inventory:consumable:view/edit。
- 前端入口：/m6-commission（nav L287 finance:commission:view）；咨询页 ConsultationView L232-246 用同 store 做本单提成预估。

---

## 二、竞品调研结论（2026-09-06 检索）

### 2.1 期末成本结转（用友畅捷通好会计/易代账、金蝶精斗云）

- **结转模板化**：期末结账界面预设常用结转项 + 支持自定义结转模板，模板可增删、禁用、启用（金蝶：模板名称/科目分录/金额取值方式 4 种——不取数/固定手填金额分摊/取科目余额发生额分摊/按分录逐行取余额转出）。
- **结转销售成本**：默认「收入百分比法」（可手工调比例并记忆），或「全月一次加权平均法」（库存商品与收入科目一一对应）。
- **计提工资**：按工资模块数据或上月计提数自动测算，可手工修改后保存；首月手填。
- **计提折旧**：按固定资产档案（科目/原值/月折旧额/年限）自动计提合计数。
- **摊销待摊费用**：按长期待摊余额与上期摊销数测算。
- **一键测算 → 生成凭证 → 可编辑 → 结账**；比例/基数记忆，后续月份免重复设置。
- 美业专项（好会计医美版）：自动归集项目收款、**耗材成本**、员工提成；工资/个税/社保计提发放一键生成凭证；库存耗材消耗自动关联成本。
- **落点**：我们不做总账凭证（无科目体系），落到 cost_allocation 四类成本镜像即可；「模板 + 基数方式 + 比例/固定额 + 可停用 + 测算后可改」是核心可配形态。

### 2.2 项目 BOM 自动扣耗材（丽晶 ERP、医美易、美盈易、君科、岚时云）

- **服务项目绑定耗材 BOM（物料清单）**：每个服务项目后台预设标准用量（如面部护理=面膜 20ml+精华 5ml+棉片 2）；不同项目/等级分别配置，店长后台维护。
- **服务完成自动扣库**：POS 标记「服务完成」→ 按 BOM 自动扣减库存、生成消耗记录（服务单号/项目/耗材/数量/操作人）、实时更新库存。
- **成本锁定到项目**：出库即把该批次成本锁定为项目直接成本，项目完成自动归集全部耗材成本算真实毛利；退库则成本从项目扣除。
- **用量微调**：实际用量与标准 BOM 有差异可改（多用 5ml 精华调实际消耗）。
- **预约预留/库存预警**：预约时算所需耗材、不足提醒补货/限约，锁定库存（一期不做，列不做项）。
- 药耗分离、进销存 ERP（采购/入库/出库/请领/盘点）；提成三法：比例提成/固定提成/成本提成（君科）。
- **落点**：BOM 配方表挂 store 域；划扣 DONE 触发自动扣库（复用既有 deduct）；bizRef=WO 单号幂等；失败不阻断、登记异常；用量微调与预约锁库存一期不做。

### 2.3 咨询师提成（前期已调研，摘要）

- 五种方案：固定比例 / 分项目比例（注射 8%·光电 12%·护理 15%）/ 阶梯递增（5 万以下 8%、5-10 万 10%、10 万以上 12%）/ 新客复购差异 / 团队+个人组合。
- 阶梯两种算法：按最终档位全额 × 高档率 vs **超额累进分段**（我们 mock 已用超额累进，延续）。
- 退款扣回提成；多咨询师分成（70/30）；规则调整不追溯历史；可按业务类型（服务/商品/开卡/充值/次卡）与支付方式选择是否参与累计。
- **落点**：一期延续 mock 活规格——超额累进阶梯 + WRITEOFF 划扣确认收入口径 + 角色（咨询师/医生）；每人底薪 + 适用规则后台可配；分项目比例/新客复购/团队分成列不做项。

---

## 三、域一：非现金渠道账实接入（渠道配置在「支付方式」里）

### 3.1 落地范围（诚实降级）

| 能力 | 本期 | 说明 |
|---|---|---|
| 支付方式/渠道配置域（后台可配） | ✅ | 渠道启用、密钥参数、对账方式，落库 + 维护 UI（替换 mp-settings mock） |
| 渠道账单导入（CSV/手工录入） | ✅ | 微信/支付宝/银行结算单导入 pay_channel_bill |
| 系统账 × 渠道账单自动勾兑 | ✅ | fund_entry 渠道 IN 分录 vs 账单，按订单号勾兑、差异列示 |
| 收银台发起真实支付/退款 | ❌ | 需商户号证书对公环境，预留 ChannelGateway 适配器接口，不伪造 |
| 账单 API 自动拉取 | ❌ | 同上，预留 fetch 接口签名，本期仅 IMPORT 手动 |

### 3.2 表结构（业务表 JPA ddl-auto；seed 种子走 Flyway）

**pay_channel_config（支付渠道配置，txn 域，配置随支付方式走）**

| 字段 | 类型 | 说明 |
|---|---|---|
| config_id | varchar(24) PK | PCC+yyyyMMdd-6 位 |
| channel_code | varchar(16) | wxpay/alipay/transfer（cash/balance 无需配置） |
| channel_name | varchar(32) | 微信支付/支付宝/银行转账 |
| store_code | varchar(16) | 门店；空=集团默认模板 |
| enabled | boolean | 启用/停用（随时调整） |
| app_id / mch_id | varchar(64) | AppID / 商户号 |
| api_v3_key | varchar(128) | APIv3 密钥（**写后不可读回**，前端掩码 \*\*\*\*，仅权限内可改） |
| cert_serial | varchar(128) | 证书序列号 |
| notify_url | varchar(256) | 回调地址 |
| reconcile_mode | varchar(16) | IMPORT 手动导入（本期唯一）/ API 自动拉取（预留） |
| fee_rate | int | 手续费率（万分位，如 60 = 0.6%），勾兑时估算手续费参考 |
| remark | varchar(256) | |
| created_by / created_at / updated_by / updated_at | | 审计四件套 |

UNIQUE(channel_code, store_code)；密钥类字段日志脱敏、审计 jsonb 不记明文。

**pay_channel_bill（渠道账单，finance 域，账实核对原料）**

| 字段 | 类型 | 说明 |
|---|---|---|
| bill_id | varchar(24) PK | PCB+yyyyMMdd-6 位 |
| channel_code | varchar(16) | wxpay/alipay/transfer |
| store_code | varchar(16) | |
| order_no | varchar(24) | 渠道订单/商户单号（勾兑键，可空=手续费等无订单行） |
| txn_amount | bigint | 交易金额（分） |
| fee_amount | bigint | 手续费（分） |
| net_amount | bigint | 实际到账（分） |
| bill_status | varchar(16) | SUCCESS/REFUND/FAILED |
| bill_time | timestamptz | 渠道交易时间 |
| settle_batch | varchar(32) | 结算批次号 |
| import_batch | varchar(24) | 导入批次（幂等：同批次同 order_no+channel 不重复） |
| created_at | | |

UNIQUE(channel_code, order_no, settle_batch)。

### 3.3 API（三处一致：后端 @RequestMapping /api 前缀 + 网关 router.go + 前端 api/*.ts）

txn-service（渠道配置，挂 /api/txn，权限 integration:view 查 / 新增 finance:channel:edit）：
- `GET  /api/txn/pay-channels` 渠道配置列表（含系统内置 cash/balance 只读行）
- `POST /api/txn/pay-channels` 新增/更新配置（upsert by channel+store；密钥空串=不修改）
- `POST /api/txn/pay-channels/{id}/toggle` 启用/停用

finance-service（账单与勾兑，挂 /api/finance）：
- `POST /api/finance/channel-bills/import`（finance:reconcile:edit）CSV 导入，整批校验、错误行中文提示、import_batch 幂等
- `GET  /api/finance/channel-bills`（finance:view）账单查询（渠道/门店/时间/批次）
- `GET  /api/finance/channel-reconcile?month=yyyy-MM-01&channel=&storeCode=`（finance:view）返回：系统账（fund_entry 该渠道 IN 分录，按 idem_key 解析订单号）合计/笔数、账单合计/笔数、已勾兑（订单号两边都有且金额一致）、**系统有账单无**（漏单）、**账单有系统无**（多单/未入账）、金额不符清单。

### 3.4 UI 入口（样式零改动，只换数据源）

- /admin/mp-settings（MpSettingsView）：现有「微信支付/小程序」配置区由 mock 改真实 `GET/POST /api/txn/pay-channels`，掩码展示密钥；增加支付宝、银行转账两个渠道卡片（沿用现有卡片样式，不新增视觉）。
- /m6-reconcile（FinReconcileView）：在现金核对区旁新增「非现金渠道核对」tab/区（沿用现有列表样式）：选月份+渠道→展示系统账/账单/差异三段 + 「导入账单 CSV」按钮；差异行中文说明。

### 3.5 种子数据（meiyun_seed Flyway）

- pay_channel_config：上海徐汇/浦东店 wxpay、alipay 各一行 enabled（测试参数、密钥占位），transfer 一行 disabled；pay_channel_bill：按 seed 既有已收款订单（wxpay/alipay 渠道）造匹配账单 + 故意 1 条漏单差异供核对演示。

---

## 四、域二：月结成本结转自动化（后台可配结转规则）

### 4.1 落地范围

竞品「结转模板」落到我们的成本镜像：四类成本中 MATERIAL/LOSS 已实时自动闭环，**无需模板**；需模板化的是 DEPRECIATION（折旧）与 LABOR（人工，含底薪+已审批提成）。

| 结转项 | 基数方式 | 一期实现 |
|---|---|---|
| 折旧 DEPRECIATION | 固定资产档案 | 新增轻量设备资产表，直线法月折旧 =（原值-残值）/ 月限，月结按门店汇总自动计提 |
| 人工 LABOR | 固定额 + 提成结转 | ① 在岗咨询师月底薪合计（staff_comp_config.base_salary）；② 当月 APPROVED 提成合计（commission_record）；可配开关 |
| 耗材 MATERIAL / 报损 LOSS | 实际发生 | 已实时闭环，模板不重复计提 |

### 4.2 表结构（finance 域，JPA ddl-auto；seed Flyway）

**fin_asset（设备资产，轻量，不做完整固定资产模块）**

| 字段 | 类型 | 说明 |
|---|---|---|
| asset_id | varchar(24) PK | FA+yyyyMMdd-6 位 |
| asset_name | varchar(64) | 设备名 |
| store_code | varchar(16) | 所属门店 |
| original_value | bigint | 原值（分） |
| salvage_rate | int | 残值率（百分比，默认 5） |
| useful_months | int | 折旧月限（如 120 = 10 年） |
| start_month | date | 起折月（yyyy-MM-01） |
| status | varchar(16) | IN_USE 在用 / DISPOSED 已处置 |
| created_by/created_at | | |

月折旧额 = round(original_value × (100-salvage_rate)/100 / useful_months)；处置月起停折。

**cost_carry_rule（期末结转规则，后台可配、随时停用调整）**

| 字段 | 类型 | 说明 |
|---|---|---|
| rule_id | varchar(24) PK | CCR+yyyyMMdd-6 位 |
| rule_name | varchar(64) | 如「设备折旧自动计提」「咨询师人工结转」 |
| cost_type | varchar(16) | DEPRECIATION / LABOR |
| calc_mode | varchar(16) | ASSET 资产折旧 / BASE_SALARY 底薪合计 / COMMISSION 已审批提成 / FIXED 固定额 |
| fixed_amount | bigint | FIXED 时每月固定额（分） |
| store_code | varchar(16) | 空=全门店通用 |
| enabled | boolean | |
| run_on_close | boolean | 封账时是否自动执行（默认 true；false 则需手动点结转） |
| remark / created_by / created_at / updated_by / updated_at | | |

seed 预置三条 enabled 规则：折旧（ASSET）、人工-底薪（BASE_SALARY）、人工-提成（COMMISSION）。

结转结果复用 cost_allocation：新增 source 值 **SYSTEM**（现有 MANUAL/ERP 之外），source_ref = CCR 规则号+期间（幂等键：`CARRY:{ruleId}:{periodMonth}:{storeCode}`，重复执行先查后写、同月同规则同门店不重复；重算需先删除该 SYSTEM 行再跑，一期提供「重算本月」按钮做删除重跑）。

### 4.3 月结流程（串接现有封账）

1. 财务在 /m6-cost 或封账界面点「**测算本月结转**」→ 后端 dry-run：逐规则算各门店金额（折旧=资产月折汇总；底薪=staff_comp_config 在岗人员底薪；提成=当月 APPROVED commission_record 合计；FIXED=固定额），返回明细**不落库**。
2. 财务核对可改（沿用竞品「测算后可编辑」：FIXED 额直接改规则；资产/底薪/提成自动项如需调整，改主数据后重新测算）。
3. 点「**执行结转**」→ 落 cost_allocation（source=SYSTEM，幂等）→ 审计 FIN_CARRY/RUN。
4. 封账（SettlementService.close）前置检查：存在 enabled 且 run_on_close 的规则本月未结转时，**提示先结转**（或按 run_on_close 自动先跑结转再封账，默认自动）。
5. 结转只写成本镜像，不产生 fund_entry 资金分录（红线）。

API（finance-service，/api/finance）：
- `GET  /api/finance/carry-rules`（finance:cost:view）规则列表
- `POST /api/finance/carry-rules`（finance:cost:edit）新增/更新/停用
- `POST /api/finance/carry/preview?month=yyyy-MM-01`（finance:cost:view）dry-run 明细
- `POST /api/finance/carry/run?month=yyyy-MM-01`（finance:cost:edit）执行结转（幂等）
- `GET/POST /api/finance/assets`（finance:cost:view/edit）设备资产维护

### 4.4 UI 入口

- /m6-cost（FinCostView）：页头区新增「期末结转」区（沿用现有卡片/按钮样式）：规则列表（开关、停用）、「测算本月」→ 明细弹层（各门店各项金额）、「执行结转」「重算本月」；设备资产「资产台账」入口（表格 + 新增/处置，沿用现有表格样式）。
- /m6-settlement 封账：封账按钮旁提示「本月 N 项结转未执行」，可跳转。

### 4.5 种子数据

fin_asset：徐汇店光电设备等 2-3 项（原值/年限/起折月），使每月折旧有数（替换 finCost mock 的假「设备资产」来源）；cost_carry_rule 三条；不预置历史结转行（部署后首月测算生成）。

---

## 五、域三：咨询师薪酬 + 提成后台设置

### 5.1 落地范围

- **每人薪酬可配**：底薪（月薪，分）+ 适用提成规则 + 生效月，一人一单当前配置（调薪不追溯）。
- **提成规则后台可配**：延续 mock 活规格——超额累进阶梯（tiers[{min,rate}]）、基数口径（WRITEOFF 划扣确认收入 / ORDER 收款 / RECHARGE 充值）、角色（咨询师/医生/美容师）；规则可停用、可新建（调整不追溯历史期间）。
- **提成单后端化**：/m6-commission 由纯 mock 切真实 API——按月生成试算（DRAFT）→ 提交 SUBMITTED → 审批 APPROVED/REJECTED → PAID（外部薪酬系统回传镜像，**不动账**）；业绩取数走 txn 内部聚合。
- **结转联动**：当月 APPROVED 提成 + 在岗底薪，经域二 LABOR 结转规则计入当月人工成本（镜像，不付款）。
- 红线：系统只试算+审批+登记；发放走外部薪酬系统；markPaid 仅回传状态。

### 5.2 表结构（finance 域，JPA ddl-auto；seed Flyway）

**staff_comp_config（员工薪酬配置，一人一当前单）**

| 字段 | 类型 | 说明 |
|---|---|---|
| comp_id | varchar(24) PK | SC+yyyyMMdd-6 位 |
| staff_id | varchar(16) | 工号 SE00x（org staff 逻辑外键，不物理关联） |
| staff_name | varchar(32) | 冗余姓名（展示用） |
| store_code | varchar(16) | |
| base_salary | bigint | 月底薪（分） |
| commission_rule_id | varchar(24) | 适用提成规则（可空=无提成） |
| effective_month | date | 生效月 yyyy-MM-01 |
| status | varchar(16) | ACTIVE 生效 / INACTIVE 停用（调薪=旧单 INACTIVE + 新单 ACTIVE，不追溯） |
| created_by/created_at/updated_by/updated_at | | |

UNIQUE(staff_id) WHERE status='ACTIVE'（部分唯一，或应用层保证一人一 ACTIVE）。

**commission_rule（提成规则，后台可配）**

| 字段 | 类型 | 说明 |
|---|---|---|
| rule_id | varchar(24) PK | CR+yyyyMMdd-6 位 |
| rule_name | varchar(64) | |
| base | varchar(16) | WRITEOFF/ORDER/RECHARGE |
| role | varchar(16) | CONSULTANT/DOCTOR/BEAUTICIAN |
| tiers_json | text | [{\"min\":0,\"rate\":600},{\"min\":8000000,\"rate\":800}…]（rate 万分位：600=6%；金额分） |
| active | boolean | 停用/启用（随时调整，不追溯） |
| created_by/created_at/updated_by/updated_at | | |

**commission_record（提成单，月度 per 人）**

| 字段 | 类型 | 说明 |
|---|---|---|
| record_id | varchar(24) PK | CM+yyyyMM(yyyyMM)-工号-序号 |
| period | date | yyyy-MM-01 |
| staff_id / staff_name | | |
| store_code | varchar(16) | |
| rule_id / rule_name | | 生成时快照规则名（规则后改不影响本单） |
| base_amount | bigint | 当月业绩基数（分） |
| order_count | int | 业绩单数 |
| tiers_json | text | 试算明细 [{label,amount,rate,commission}] 快照 |
| commission | bigint | 提成额（分） |
| status | varchar(16) | DRAFT/SUBMITTED/APPROVED/PAID/REJECTED |
| approver / approved_at / paid_at / remark | | |
| created_at | | |

UNIQUE(period, staff_id)。

### 5.3 业绩取数（跨域内部端点，禁直读 txn 表）

txn-service 新增内部端点（X-Internal-Token，internal:finance-flow 复用或新 internal:commission-base）：
`GET /api/txn/internal/commission-base?month=yyyy-MM-01&storeCode=`
返回按月、按咨询师工号聚合：
- base=WRITEOFF：writeoff_record(DONE, 当月) JOIN txn_order ON order_no → 按 txn_order.consultant 汇总 amount、笔数（实现时核对 WriteoffRecord.orderNo 与 TxnOrder.consultant 字段名）；
- base=ORDER：txn_order(已收款, 当月) 按 consultant 汇总；
- base=RECHARGE：充值单按经办人汇总（一期可不实现，预留）。
- 退款扣回：当月 REFUNDED 退款单按顾问负向冲减（沿用竞品退款扣回）。
finance 侧 RestTemplate 拉取 + 降级空集合（中文日志），不臆造业绩。

### 5.4 API（finance-service，/api/finance）

- `GET/POST /api/finance/comp-configs`（finance:commission:view / edit）员工薪酬列表/保存（底薪+规则+生效月）
- `GET/POST /api/finance/commission-rules`（view / edit）规则列表/新建/停用
- `POST /api/finance/commission/generate?period=yyyy-MM-01`（edit）按月生成试算单（幂等：已存在则返回现有 DRAFT）
- `GET  /api/finance/commission?period=`（view）提成单列表
- `POST /api/finance/commission/{id}/submit`、`/approve`、`/reject`、`/mark-paid`（approve 权限审批；mark-paid 仅状态镜像）
- `POST /api/finance/commission/estimate?staffId=&amount=`（view）本单提成预估（供咨询页 ConsultationView 用，替换 mock 预估）

写端点四件套：校验（底薪/比例范围、rate 万分位 0-10000、阶梯 min 递增）、幂等（UNIQUE + 先查后写）、审计（FIN_COMP/FIN_COMM）、中文错误。

### 5.5 UI 入口（用户问「在哪里设置方便」）

- **薪酬（底薪+适用规则）设置在员工管理页**：/admin/staff（T1StaffView）每行增加「薪酬」操作 → 抽屉/弹层设置月底薪 + 选提成规则 + 生效月（沿用现有员工抽屉样式；页面跨域调 /api/finance/comp-configs，权限 finance:commission:edit 控制按钮显隐）。理由：HR/财务在维护员工档案处一并设薪酬最顺手，符合「后台设置好每个咨询师的薪酬+提成比例」。
- **提成规则维护在提成页**：/m6-commission 顶部新增「提成规则」区（规则名/基数/角色/阶梯表/启用开关 + 新建规则），沿用现有卡片样式。
- 提成单列表/详情/审批：FinCommissionView 由 mock store 切真实 API（阶梯明细表/综合提成率/Top6 图表数据均来自后端 tiers_json/聚合），零样式改动；「导出薪酬表」按钮接 CSV（复用 B8 FinanceExportService 模式）。
- 咨询页本单预估：ConsultationView 改调 estimate 端点。

### 5.6 种子数据

commission_rule：r1 咨询师阶梯（6/8/10/12%）、r2 医生（10/12%）落库；staff_comp_config：seed 在岗咨询师/医生（苏晴 SE?、李娜、王诗涵、周慧敏、王医生等，工号对齐真实 SE00x，纠正 mock 的 u01-u05）各配底薪（如 8000-15000 元）+ 适用规则；commission_record：当前月若干 DRAFT/APPROVED 单供演示。

---

## 六、域四：BOM 自动扣耗材（治疗划扣自动出库）

### 6.1 落地范围

- 项目用料配方（BOM）后台可配：项目名 + SKU + 标准用量，集团模板/门店覆盖。
- 双签划扣 DONE 后自动按 BOM 扣库（复用 StoreConsumableClient.deduct，moveType=USE，bizRef=`BOM:{writeoffId}` 幂等），耗材成本经 emitConsumableCost 自动归集 TK-MATERIAL（既有闭环）。
- 扣料失败不阻断划扣：登记 bom_deduct_exception，异常清单可重试/补单。
- 不做（一期）：预约锁库存、实际用量微调 UI（差异走现有领用审批补单或 ADJUST 盘点）、按 BOM 算项目毛利报表（数据已齐，报表后续批次）。

### 6.2 表结构（store 域，JPA ddl-auto；seed Flyway）

**project_bom（项目用料配方）**

| 字段 | 类型 | 说明 |
|---|---|---|
| bom_id | varchar(24) PK | BOM+yyyyMMdd-6 位 |
| project_name | varchar(64) | 项目名（与 writeoff_record.project / txn_order.project 一致，勾兑键） |
| store_code | varchar(16) | 空=集团模板；门店行覆盖模板 |
| sku_code | varchar(32) | 耗材 SKU（consumable.sku_code 逻辑外键） |
| qty | int | 标准用量（整数，单位取 SKU 主单位；一期不支持小数 ml，按 SKU 最小单位建档） |
| enabled | boolean | |
| created_by/created_at/updated_by/updated_at | | |

UNIQUE(project_name, store_code, sku_code)（store_code 空值用空串或集团标记，实现时定）；匹配优先级：门店行 > 集团模板。

**bom_deduct_exception（扣料异常登记，txn 域）**

| 字段 | 类型 | 说明 |
|---|---|---|
| exc_id | varchar(24) PK | BEX+yyyyMMdd-6 位 |
| writeoff_id | varchar(24) | WO 单号 |
| store_code | varchar(16) | |
| project_name | varchar(64) | |
| reason | varchar(256) | 中文原因（库存不足/SKU 未建档/BOM 未配置不登记） |
| detail_json | text | 失败行明细 [{skuCode,needQty,stockQty}] |
| status | varchar(16) | PENDING 待处理 / RESOLVED 已处理 |
| created_at / resolved_at / resolved_by | | |

UNIQUE(writeoff_id)（一个划扣单最多一条异常，重试成功后 RESOLVED）。

### 6.3 触发流程（WriteoffDeskService.execute，L217 emitWriteoffDone 之后）

1. 划扣 DONE、writeoff 落库、资金事件发出后（**不与划扣同事务强绑定**：扣料失败 try/catch 不抛出，划扣已提交）：
2. 查 BOM：按 project_name + store_code（门店优先，回落集团）取 enabled 配方行；无配方 → 静默跳过（医疗服务不强制配 BOM）。
3. 组 DeductCmd：bizRef=`BOM:{writeoffId}`、storeCode、moveType=USE、operator=划扣操作人、lines=[{skuCode, qty}]。
4. 调 StoreConsumableClient.deduct：
   - 成功 → store 侧移动平均出库 + movement(USE, bizRef)；txn 侧 emitConsumableCost（todoNo=`BOM:{writeoffId}`，USE→TK-MATERIAL，source=ERP 通道复用）→ finance 自动归集耗材成本。幂等：bizRef 重复不双扣。
   - 失败（4xx 库存不足/SKU 缺失 中文透传，5xx 502）→ **不回滚划扣**，upsert bom_deduct_exception（PENDING，记原因+明细），审计 BOM/FAIL。
5. 后续：门店在异常清单点「重试」→ 重新调 deduct（补货后成功则 RESOLVED）；或走现有领用审批手工补单后标记 RESOLVED。

实现位置：参考 ApprovalService.deductOnFinalApprove L230-258 模式；为避免划扣主事务被远程调用拖慢/拖累，扣料放在事务提交后（TransactionTemplate afterCommit 或独立事件监听）执行，异常内部消化。

### 6.4 API

store-service（/api/stores，BOM 维护，权限 inventory:consumable:view/edit 复用）：
- `GET  /api/stores/project-boms?projectName=&storeCode=` 配方列表
- `POST /api/stores/project-boms` 新增/更新/停用（校验 SKU 存在、qty>0）

txn-service（异常处理，/api/txn，权限 inventory:consumable:view + edit）：
- `GET  /api/txn/bom-exceptions?status=PENDING&storeCode=` 异常清单
- `POST /api/txn/bom-exceptions/{id}/retry` 重试扣料
- `POST /api/txn/bom-exceptions/{id}/resolve` 手工标记已处理（补单后）

### 6.5 UI 入口

- /m2-inventory（库存台账页）：新增「项目配方 BOM」区（沿用现有表格样式）：选项目 → 列 SKU 行 + 用量 + 启用开关，可增删；门店/集团切换。
- /m2-wastage 或 /m2-requisition 旁（或库存页内）「扣料异常」区：PENDING 清单（WO 单号/项目/原因/缺料明细）+「重试」「标记已处理」。
- 划扣台 /writeoff 不新增交互（自动动作，失败仅在异常清单可见；可在划扣完成 timeline 追加一行「BOM 自动扣料成功/失败」，沿用现有 timeline 样式）。

### 6.6 种子数据

project_bom：为 seed 高频治疗项目（如「水光针」「光子嫩肤」「热玛吉」等，按 seed 实际项目名）配 2-4 个项目配方，耗材 SKU 用 seed 库存现有 SKU（确保有库存可扣）；至少 1 个项目配方引用 1 个库存不足 SKU 以演示异常清单。

---

## 七、权限矩阵新增（org PermissionMatrix 注册）

| 权限码 | 说明 | 挂点 |
|---|---|---|
| finance:channel:edit | 支付渠道配置维护 | txn pay-channels 写 |
| finance:reconcile:edit | 渠道账单导入/勾兑操作 | finance channel-bills 写 |
| finance:cost:edit | 结转规则/执行/资产维护（**已存在**，复用） | carry-rules/carry/assets 写 |
| finance:commission:edit | 薪酬配置/提成规则/生成（**已存在**） | comp-configs/commission-rules/generate |
| finance:commission:approve | 提成审批（**已存在**） | approve/reject |
| inventory:consumable:edit | BOM 配方维护（**已存在**，复用） | project-boms 写 |

网关 router.go：/api/txn、/api/finance、/api/stores 前缀均已透传，新增子路径无需改网关（实现时核对路由表）。

---

## 八、实施批次（建议顺序，依赖驱动）

| 批次 | 内容 | 服务 | 依赖 |
|---|---|---|---|
| **B9** | 域三 薪酬提成后端化（staff_comp_config/commission_rule/commission_record + txn 业绩聚合内部端点 + finance API + 员工页薪酬抽屉 + 提成页切真实 API + 规则维护区） | finance/txn/org-gateway/frontend | 无（域二 LABOR 提成结转依赖它，先行） |
| **B10** | 域四 BOM 自动扣料（project_bom + 划扣 afterCommit 自动扣库 + 异常登记/重试 + BOM 维护 UI） | store/txn/frontend | 无（域二材料成本已闭环，BOM 让其更完整） |
| **B11** | 域二 月结结转（fin_asset + cost_carry_rule + 测算/执行/重算 + 封账串接 + 成本页结转区/资产台账） | finance/frontend | B9（提成结转） |
| **B12** | 域一 渠道账实（pay_channel_config + 渠道配置 UI 替换 mock + pay_channel_bill 导入 + 勾兑 + 核对页非现金区；ChannelGateway 预留） | txn/finance/frontend | 无（外部依赖最多，放最后；真实联调另立批次） |

每批独立交付：双栈部署（seed Flyway 种子 + prod JPA ddl-auto）、真实数据验证、浏览器端到端、DELIVERY 文档归档。

---

## 九、铁律自查清单（交付前逐条核验）

1. 金额后端 Long 分、前端元；rate 万分位；月份契约 yyyy-MM-01。
2. 写接口四件套：入参校验（422 中文）/ 幂等（UNIQUE + 先查后写，业务单号幂等键）/ 全动作审计（audit jsonb）/ 中文错误（无技术码外露）。
3. 跨服务：finance 取业绩走 txn 内部端点 + X-Internal-Token；txn 扣库走 store 内部端点；**禁直读别域表**。
4. 表：业务配置/单据表 JPA ddl-auto；seed meiyun_seed 种子走 Flyway 新 V 号；prod meiyun_core 不跑种子；已执行迁移禁改。
5. 密钥：api_v3_key 等不回读、日志/审计脱敏。
6. 样式零改动：所有新 UI 沿用现有页面卡片/表格/按钮样式，只换数据源、不重排；不引入新视觉语言。
7. 诚实降级：真实支付/账单 API 拉取不伪造，ChannelGateway 仅留接口；无 BOM 项目静默跳过；业绩聚合降级空集合不臆造。
8. 资金红线：结转/折旧/薪酬/提成**只写成本镜像与状态，不生成 fund_entry 实付分录**；PAID 仅镜像。
9. 医疗红线：BOM 扣料失败不阻断划扣、不回滚，异常登记可追溯。
10. 双栈真实验证：seed + prod 均部署、真实数据/浏览器端到端，拒绝假交付；mock 回落仅保留现有空态降级且不伪装成真实数据。

---

## 十、不做项（明确边界）

- 真实微信/支付宝/银行支付发起、退款、账单 API 自动拉取（需商户资质环境，预留适配器，另立批次）。
- 总账凭证/科目体系（不做财务软件凭证，仅成本镜像）。
- 完整固定资产模块（折旧仅直线法 + 轻量资产台账，不做处置损益/盘点/减值）。
- 提成高级玩法：分项目比例、新客/复购差异、团队分成、多咨询师拆账、包干模型（数据模型 tiers_json 可扩展，一期不做 UI）。
- BOM 预约锁库存、用量微调 UI、项目毛利报表（后续批次）。
- 小数单位耗材（ml/滴）：一期按 SKU 最小整数单位建档。
- 支付方式两套码（小写写库/大写字典）并轨：本期不动，配置表以小写渠道码为准。
