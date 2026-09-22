# P5-B85 交易资产批（04-backlog L67 资产转移 / 合同）—— 只读侦察与口径书面定案

> 时间：2026-09-22（13:54 开工拍接力自 docs 24b62e6，14:xx 完稿）
> 状态：**方案定案（卡0 产出，定案拍板前不写业务代码）**
> 用户决策原文（逐字）：「继续剩余未完成的开发」＋ AskUserQuestion 拍板「**确认开工（推荐）**」——批准按汇报方案开工 B85 · 交易资产批 · 资产转移 / 合同。
> 批定义（PLAN-M3 §4 逐字）：「范围：L67 资产转移 / 合同（1 行）；前置/复用：B64 卡1 大额多级审批＋RepurchaseTransferExecutor 已具搬账基础；**本批补全量资产转移规则与合同实体**；依赖：审批中心、资金台账（均已闭环）；批末数字：113 → 114✅，⬜ 13 → 12」。
> 事实基础：六册台账已读齐（✅113/🔧1/⬜13＝127≈89%）；本卡只读侦察覆盖 txn 侧五文件（RepurchaseTransferExecutor/RepurchaseService/ApprovalService/M4RepurchaseController/两实体）＋ customer 侧四文件（MemberCard/CardLedger/CardLedgerService/InternalCardController）＋ CustomerCardClient＋ PG 现网只读实测＋前端挂载点与权限矩阵。
> 铁律遵循：真实库/接口>代码>文档（0）；API 三处一致（1）；中文枚举、金额存分（2/3）；Page+Specification（4）；前端真实 API、template/style 零改动只换数据源（5）；构建部署（6）；curl+PG+Chrome 三轨真验（7）；一卡一 feat commit/push、代码与 docs 分离（8）；台账六册开发前读交付后写（9/10）；哨兵续跑（11）；提交前 OCR（12）。

---

## 〇、调研总述

### 0.1 批次定位

L67「资产转移 / 合同」为域④唯一 ⬜ 行。B64 卡1 已落地「资产转移」bizType 的最小闭环（repurchase 单＋三签＋大额多级审批＋Executor 本地搬账），本批在其上补**全量规则**（赠金随转 / 卡主约束 / 跨客户语义 / 动账权威收口）与**合同实体**（后端零基建，前端页面已预留）。

### 0.2 行业口径调研要点（2026-09 检索综合）

| 来源 | 关键形态 | 本批取舍 |
|---|---|---|
| 国内美业/医美 SaaS 通行做法（美盈易等公开资料） | 转卡/过户须**转出方与接收方双确认**＋超限升级审批；赠金通常**不随转或按比例随转**（防营销赠送套现）；转卡留完整台账流水 | 采纳「赠金独立字段可选随转、上限=来源卡赠金余额」；双卡双向流水留痕；跨客户强制审批 |
| 预付费卡资金监管口径（单用途预付卡监管精神） | 本金与赠送金额**分账管理**、流向可追 | 本金/赠金分列转移、双快照对账锚点，与 B18 赠金台账同口径 |
| 医美合同管理合规标配 | 合同为交易与退款条款法律载体：冷静期（通行 7 天）内无责退、期后按违约金比例倒扣 | 合同实体含 coolingDays/penaltyRate/refundTerms，对齐前端已定稿模型 |

---

## 一、现有基础设施盘点（代码实证，2026-09-22 复核）

### 1.1 txn 侧（B64 卡1 资产链，feat 52feb20 上线）

| # | 事实 | 位置 | 对本批影响 |
|---|---|---|---|
| 1 | `RepurchaseTransferExecutor.executeTransfer(Repurchase)`：仅「资产转移」bizType 搬账；from/to 卡 findById 404；剩余次数/余额不足 400 整事务回滚；双卡扣减/增加同事务 save；来源卡清零置「已用完」；**不搬 giftBalance、无卡主（customerId）归属校验、不写 card_ledger 流水**（javadoc 红线④「不做状态写库，本组件只搬卡账」） | txn-service `RepurchaseTransferExecutor.java`（64 行全文） | **定案 D1：动账权威收口 customer 域**——txn 直写 member_card 绕过 card_ledger，破坏 Σ card_ledger.amount = member_card.balance 对账恒等式（见 #8），本批改经 CustomerCardClient 内部端动账 |
| 2 | `RepurchaseService`：create（consentAck 硬前置 400／资产转移须 fromCardNo+toCardNo／RP 单号／audit CREATE）；sign（待签核闸 409／三签齐全两两不同人／reject 置已拒绝／**小额<L1_MIN 即时 Executor 并账置已完成／大额置审批中 submitRepurchase**）；applyApproved/applyRejected（审批中闸 409 防重／终审回调并账／audit） | `RepurchaseService.java`（222 行全文） | 全量规则（赠金/卡主/跨客户）在 create/sign 校验层与并账入口两侧追加；状态机不动 |
| 3 | `ApprovalService.submitRepurchase`：金额>0 校验／guardWriteStore／findByBizNo 幂等重放回原待办／stage=L1→FINANCE 否则 REVIEW／priority≥200万分 HIGH／payload 七键 JSON／audit SUBMIT；approve/reject REPURCHASE 分支 finalStage **同 JVM 直调** applyApproved/applyRejected；L3 REVIEW 通过插入 REGION 区域复审 | `ApprovalService.java` L215-310、L475-594 | **零改动复用**；跨客户转移强制走审批由 RepurchaseService 侧保证（见定案 D3），审批引擎无感 |
| 4 | tierFor 阈值（分）：L1_MIN=100_000（¥1000）／L2_MIN=500_000（¥5000）／L3_MIN=2_000_000（¥20000）；≥L3→L3、≥L2→L2、否则 L1 | `TxnService.java` L33-35、L447-451 | 直接复用，不自造阈值 |
| 5 | `Repurchase` 实体：repurchaseNo PK／customerId／storeCode／bizType(复购\|资产转移 CHECK)／targetProject／fromCardNo/toCardNo（FK→member_card）／transferTimes/transferAmount／consentAck/consentText／status(待签核\|审批中\|已完成\|已拒绝 CHECK)／三签字段组／note；**无 toCustomerId／无赠金字段／无合同关联** | `Repurchase.java`（82 行全文）；PG `\d repurchase` 实测 | 定案 D2/D3/D6：ddl-auto=update 加三列（业务表加列既有范式，无需 DDL）；CHECK 值域不动 |
| 6 | `M4RepurchaseController`（259 行薄边界）：POST /api/txn/repurchase（followup:create）、GET list/{no}（followup:view）、POST /{no}/sign（followup:edit） | `M4RepurchaseController.java` 全文 | 端点族内扩展请求字段，**网关零改、不加新权限码**；合同端点新建平级控制器（见定案④） |

### 1.2 customer 侧（卡资产权威域，本卡精读）

| # | 事实 | 位置 | 对本批影响 |
|---|---|---|---|
| 7 | `MemberCard`（customer 域权威）：cardNo PK／customerId（FK→customer）／cardItem／storeCode／totalTimes/remainTimes／**balance 本金（分）＋ giftBalance 赠金（分）双列**／status CHECK 四值（在用\|退卡中\|已退卡\|已用完）／productCode/cardType/expiresAt/saleNo | customer-service `MemberCard.java`（78 行）；PG `\d member_card` 实测（chk_card_times、balance>=0、status CHECK） | 转移双账户（本金/赠金）与次数的权威落点；状态校验以本域为准 |
| 8 | `CardLedger` append-only：**change_type CHECK 四值 {RECHARGE, CONSUME, REFUND, ADJUST}**；对账恒等式 Σ amount = member_card.balance、Σ gift_amount = gift_balance；biz_ref 幂等锚点；gift 两列历史行 NULL 视为 0 | `CardLedger.java`（73 行）；PG `chk_card_ledger_change_type` 实测 | **定案 D4：Flyway V43 扩 CHECK 加 'TRANSFER'**（共享表铁律走 Flyway 双库可重入）；转移写双向流水恢复恒等式覆盖 |
| 9 | `CardLedgerService` 写接口四件套范式：①中文校验（金额/状态/余额/防超退）②幂等（bizRef 重放返回既有行）③全审计（CARD 链 payload 合法 JSON、authority=customer）④并发安全（findForUpdate 行锁＋synchronized 单号）；消费/划扣**先赠金后本金**；冻结期非「在用」校验自然拦截全部动账 | `CardLedgerService.java`（740 行全文） | 新增 `transfer()` 方法照此范式：双卡行锁（按卡号排序防死锁）、双卡「在用」校验、账实 422、双向 TRANSFER 流水、RP 单号幂等 |
| 10 | `InternalCardController`：系统身份端点族（X-Internal-Token，perms=["*"]）：consume/refund/freeze/unfreeze/refund-order/writeoff/issue 七写端点（internal:card-write）＋投影三读端点；红线「交易域不直写卡台账，拆库后零改动」 | `InternalCardController.java`（352 行全文） | 新增 `POST /api/customer/internal/cards/transfer` 端点（internal:card-write），红线语义完全一致 |
| 11 | `CustomerCardClient`（txn→customer RestTemplate 封装）：4xx 透传中文（调用方事务回滚）／5xx/网络异常→502 中文「本笔操作已回滚」；幂等键由 customer 侧裁决 | txn-service `CustomerCardClient.java`（242 行全文） | 新增 `transfer(...)` 方法照 post() 既有封装，零新基建 |

### 1.3 PG 现网只读实测（meiyun_seed，2026-09-22）

| # | 实测 | 读数 |
|---|---|---|
| 12 | member_card 存量 | 在用 90／已用完 19／已退卡 5＝114 张；**含赠金卡仅 1 张**（gift_balance>0） |
| 13 | repurchase 存量 | **0 行**（B64 上线后 seed 库无资产转移单，Executor 本地搬账缺口无存量污染，收口改造零回填负担） |
| 14 | card_ledger 存量 | 317 行全 CONSUME（B6 存量回填补记；历史导入卡无 RECHARGE 首笔，恒等式对历史卡本不严格成立，TRANSFER 流水只对转移增量负责） |
| 15 | Flyway 链 | 全平台共享 flyway_schema_history 最大 **V42**（org_code_tombstone）；customer-service 拥有 card_ledger（V5__card_ledger.sql）；**txn-service 无 Flyway**（ddl-auto=update）；**V43 可用** |
| 16 | 合同/转移实体表 | information_schema 无 contract\|transfer 任何表——**合同后端零基建** |

### 1.4 前端挂载点与权限（代码实证）

| # | 事实 | 位置 | 对本批影响 |
|---|---|---|---|
| 17 | nav.ts 已挂两入口：L150 `/contract` 合同管理（contract:view）、L151 `/asset-transfer` 客户资产转移（transfer:view）；meta/breadcrumb 齐备；router L57-58 → TransferView.vue（559 行）/ContractView.vue（543 行） | `config/nav.ts`、`router/index.ts` | **页面已存在，零新路由零新菜单**；本批只做真实接入 |
| 18 | `stores/transfer.ts`（263 行）**纯前端 mock**：内存 transfers＋seed 假数据＋asset store 假资产模型；注释明示「后端就绪前以内存+activity 流水兜底」；模型含 from/toCustomer、assetType(CASH\|TIMES)、amount/times、itemSku/itemName、reason、signTier、状态机 PENDING_REVIEW→PENDING_FINANCE→TRANSFERRED/REJECTED、transfer:create/approve/edit 三码校验 | `stores/transfer.ts` 全文 | 铁律5：template/style 零改动，store 换真 API；状态映射见定案⑤ |
| 19 | `stores/contract.ts`（242 行）**纯前端 mock**：合同模型已定稿（contractNo/customerId/type(COURSE\|STORED_VALUE\|PACKAGE\|SERVICE)/title/storeId/signDate/totalAmount/orders[]/assetIds[]/**coolingDays 默认 7／penaltyRate 默认 0.2／refundTerms**/signedByName/status DRAFT→EFFECTIVE→COMPLETED\|TERMINATED）；注释「后端就绪前以内存+activity 兜底」 | `stores/contract.ts` L1-120 | 后端合同实体**对齐前端已定稿模型**（字段一一映射，元→分、比率→基点），视图零改 |
| 20 | 权限六码已播种 permission_def：contract:view/edit、transfer:view/create/edit/approve | PG `permission_def` 实测 | **零新增权限码**；后端 @RequirePerm 直接引用 |
| 21 | 复购页（RepurchaseView＋stores/repurchase.ts＋api/repurchase.ts）B64 已真接入 /api/txn/repurchase，含资产转移 bizType 发起与三签 | 前端 repurchase 三件套 | 全量转移规则的表单字段（赠金/接收客户/合同号）在此页扩展，样式零改只加字段绑定 |

---

## 二、定案①：范围与卡序

- **本批唯一施工项＝L67**：资产转移全量规则（赠金随转／卡主约束／跨客户语义／动账权威收口）＋合同实体（后端新建＋前端真实接入）。
- **卡序（一卡一 feat commit/push）**：
  - **卡1** customer 域转移动账：Flyway V43 扩 chk_card_ledger_change_type 加 TRANSFER（双库可重入）＋ CardLedgerService.transfer()＋ InternalCardController 端点＋ CustomerCardClient.transfer()。
  - **卡2** txn 域全量转移规则：Repurchase/RepurchaseCmd 加列（toCustomerId/transferGift/contractNo）＋create/sign 校验（卡主归属/双卡在用/赠金上限/跨客户强制审批）＋Executor 改走 CustomerCardClient（废弃 txn 本地直写）。
  - **卡3** 合同实体后端：contract 表（ddl-auto=update）＋ContractService/ContractController（/api/txn/contracts）＋审计；repurchase.contractNo 联动校验。
  - **卡4** 前端真实接入：stores/transfer.ts、stores/contract.ts 换真 API（视图零改）＋RepurchaseView 表单字段扩展＋三轨真验（curl 18443＋PG 对账＋Chrome 18080）＋测试数据单事务物理还原。
  - **批末**：OCR 审查＋台账六册回写（L67 ⬜→✅，113→114✅、⬜13→12）＋哨兵 DORMANT。
- 数字纪律：✅/🔧/⬜ 只在批末跃迁；卡1-卡4 不动数字。

## 三、定案②：资产转移全量规则

### D1 动账权威收口 customer 域（推荐值）

废弃 `RepurchaseTransferExecutor` 的 txn 本地 JPA 直写（绕过 card_ledger，破坏对账恒等式 #1/#8），改为终审/即时并账时经 `CustomerCardClient.transfer()` 调 customer 新内部端点，由 customer 域以四件套范式动账（行锁双卡＋双向 TRANSFER 流水＋审计）。RepurchaseService 侧保留「审批中」状态闸防重（终审重放 409），customer 侧以 RP 单号幂等——双闸与 B64 口径一致。Executor 类删除或退化为 client 适配（推荐**删除**，调用点改注 CustomerCardClient；共表部署下亦不直写，红线「交易域不直写卡台账」一致成立）。

### D2 赠金随转（推荐值）

Repurchase/RepurchaseCmd 加 `transferGift`（bigint 分，≥0，可省=0）：赠金**独立字段可选随转**，上限=来源卡 gift_balance（超出 422 中文）；赠金流向与本金同卡（from→to），不允许本金转 A 卡赠金转 B 卡。消费侧「先赠后本」口径不受影响。行业口径（赠金不随转防套现）以「默认 0、显式填写才随转」满足——不随转是默认行为，随转是显式授权行为且全台账留痕。

### D3 卡主约束与跨客户语义（推荐值）

Repurchase/RepurchaseCmd 加 `toCustomerId`（varchar(16)，可空）：
- from 卡**必须**属于 cmd.customerId（转出客户），否则 400「来源卡不属于转出客户」；
- toCustomerId 空=同客户卡间调拨（to 卡须同属 customerId）；非空=**跨客户过户**（to 卡须属 toCustomerId，customer 域以 InternalCardController 客户目录投影硬校验客户存在）；
- **跨客户转移强制走审批**（不论金额：create 时若 toCustomerId≠customerId 则三签齐后一律置「审批中」submitRepurchase，金额<L1_MIN 时以 L1_MIN 入审批单金额口径……**修正**：审批金额仍取 transferAmount 真实值，tierFor 自然定级，仅跳过「小额即时并账」捷径）；同客户卡间调拨维持 B64 现状（≥L1_MIN 才审批）。

### D4 TRANSFER 流水与 Flyway V43

V43__card_ledger_transfer.sql（customer-service，双库可重入）：
```sql
ALTER TABLE card_ledger DROP CONSTRAINT IF EXISTS chk_card_ledger_change_type;
ALTER TABLE card_ledger ADD CONSTRAINT chk_card_ledger_change_type
  CHECK (change_type IN ('RECHARGE','CONSUME','REFUND','ADJUST','TRANSFER'));
```
transfer() 写**双向两行**：from 卡 TRANSFER 负额行（amount=-本金、gift_amount=-赠金、balance_after/gift_after 双快照、bizRef=RP 单号）、to 卡 TRANSFER 正额行（同 bizRef）。同 bizRef 两行按 card_no 区分；幂等=findFirstByBizRefAndCardNo 命中即返回。次数转移（transferTimes）不动恒等式（次数非台账列），双卡 remain_times 直接增减＋审计 payload 留痕。

### D5 双卡约束细则（推荐值）

- 双卡均须「在用」（退卡中/已退卡/已用完 400 中文）；
- 次数转移要求**同 productCode**（疗程次数不可跨项目搬家；同卡名不同模板编码亦拒绝）；金额/赠金转移不限卡项；
- from≠to（同卡 400）；
- 转移后 from 卡 remain_times==0 且 balance==0 且 gift_balance==0 → 置「已用完」（与 B64 口径一致）。

### D6 合同联动

Repurchase 加 `contractNo`（varchar(24)，可空，FK 约束不加——合同为 txn 域表，跨表 FK 免）：跨客户转移**建议**附合同号（v1 不强制，前端表单显著提示）；合同号填写时校验合同存在且 status=生效中且 customer_id 匹配转出或接收方，否则 400。

## 四、定案③：合同实体

### 4.1 建模（txn 域业务表，ddl-auto=update 自动建表，零 Flyway）

`Contract` 实体（对齐前端 stores/contract.ts 已定稿模型 #19，单位换算：totalAmount **分**、penaltyRate **基点 int**（万分比，2000=20%））：

| 字段 | 类型 | 说明 |
|---|---|---|
| contract_no | varchar(24) PK | HT+yyyyMMdd-6位（库内当日最大号递增，synchronized 与 RP/RC 同口径） |
| customer_id | varchar(16) nn | FK→customer |
| store_code | varchar(16) nn | FK→store |
| contract_type | varchar(16) nn | COURSE/STORED_VALUE/PACKAGE/SERVICE（CHECK 四值） |
| title | varchar(128) nn | 合同标题 |
| sign_date | date nn | 签署日 |
| total_amount | bigint nn | 合同总金额（分） |
| orders_json | jsonb | 关联订单快照数组 [{orderNo,amount,itemName}]（签署时固化快照语义，仓内 approval_todo.history JSON 先例） |
| assets_json | jsonb | 关联资产快照数组 [{cardNo,cardItem}] |
| cooling_days | int nn default 7 | 冷静期天数 |
| penalty_rate | int nn default 2000 | 违约金基点（万分比） |
| refund_terms | varchar(512) | 退款/终止条款 |
| remarks | varchar(256) | |
| signed_by | varchar(32) | 经办签署人 |
| status | varchar(8) nn default 草稿 | 草稿/生效中/已履行/已终止（CHECK 四值） |
| effective_at / completed_at / terminated_at | timestamptz | 状态时点 |
| terminate_reason | varchar(256) | |
| created_at | timestamptz nn | |

### 4.2 状态机与端点

状态机：草稿→生效中（activate，contract:edit）→已履行（complete）／已终止（terminate，须 terminateReason）；生效中才可被 repurchase.contractNo 引用；**v1 不走审批中心**（对齐前端模型直接流转），全状态流转 audit CONTRACT 链（CREATE/ACTIVATE/COMPLETE/TERMINATE，payload 合法 JSON）。

`ContractController`（txn 域平级新控制器）：
- POST /api/txn/contracts（contract:edit）新建草稿（单号服务端生成）
- GET /api/txn/contracts?customerId=&status=（contract:view）列表（DataScope.storeSpec 门店收敛）
- GET /api/txn/contracts/{no}（contract:view）详情
- POST /api/txn/contracts/{no}/activate|complete|terminate（contract:edit）状态流转（状态闸 409 中文）

### 4.3 边界

合同与订单/退款的**执行联动**（冷静期内退款免违约金自动判定等）**不在本批**——本批合同为「档案实体＋转移引用」，退款条款自动执行列 04 新 backlog 行（跨 refund 链路，单独立项）。

## 五、定案④：前端真实接入（铁律5：template/style 零改动，只换数据源）

| 文件 | 改动 |
|---|---|
| `api/contract.ts`（新建） | GET/POST /txn/contracts 四端点 DTO（元↔分、比率↔基点换算在 store 层） |
| `stores/contract.ts` | 内存 mock → 真 API（load/saveDraft/activate/complete/terminate 改写为异步调 API；模型字段名不动；状态 DRAFT/EFFECTIVE/COMPLETED/TERMINATED ↔ 后端 草稿/生效中/已履行/已终止 在 api 层映射） |
| `stores/transfer.ts` | 内存 mock → 真 API：create → POST /txn/repurchase（bizType=资产转移，from/toCustomerId、amount/times、transferGift、contractNo）；列表 → GET /txn/repurchase；approve/reject/execute 由审批中心真链路承担（本 store 只留读投影＋发起）；状态映射 PENDING_REVIEW→待签核/审批中(REVIEW 段)、PENDING_FINANCE→审批中(FINANCE 段)、TRANSFERRED→已完成、REJECTED→已拒绝 |
| `views/TransferView.vue`、`views/ContractView.vue` | **零改动** |
| `views/RepurchaseView.vue`＋`stores/repurchase.ts`＋`api/repurchase.ts` | 资产转移表单加三字段（接收客户 toCustomerId／赠金 transferGift／合同号 contractNo），样式零改只加字段绑定与元转分 |

## 六、定案⑤：验证与批末

- **三轨真验**（每卡）：curl -sk 经网关 18443（SE101 周岚登录取 token）＋PG 对账（card_ledger TRANSFER 双向行、member_card 双余额、contract 行、audit_log 链）＋Chrome 18080（/asset-transfer 发起-审批-执行全链路、/contract 建稿-生效、复购页资产转移表单）。
- **对账专项**：转移前后 Σ card_ledger.amount = member_card.balance 对两张实验卡成立（增量口径）；测试数据单事务物理还原。
- **批末**：铁律12 OCR 审查→台账六册回写（L67 ⬜→✅）→哨兵 DORMANT→数字 113→114✅、⬜13→12（≈90%）。

## 七、延期与边界（固化）

| 项 | 决策 |
|---|---|
| 合同↔退款执行联动（冷静期/违约金自动判定） | 04 新 backlog 行，跨 refund 链路单独立项 |
| asset.ts 前端假资产模型广域真实接入 | 不在本批（仅 transfer 链路换真，避免范围蔓延） |
| 合同审批流（v1 不走审批中心） | 对齐前端已定稿模型；如需审批列后续批 |
| sign_tier 错配 15 笔专项 | 独立专项，不紧急，不在本批 |
| txn 侧 Flyway 首引 | 本批仍不需要（合同走 ddl-auto=update；V43 落 customer-service） |

---

> 本稿为卡0 产出，定案 D1/D2/D3/D5 推荐值待用户拍板后进入卡1 施工；哨兵 CARD 行随施工推进更新。
