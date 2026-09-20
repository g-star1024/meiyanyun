# DESIGN · P5-B80 · M2-11 物料申领 ＋ M2-12 损耗报损 两页切真（卡0 定案）

> 日期：2026-09-20（以机器真实 date 为准；B79 文档日期 09-21 与时钟差，B80 一律用 09-20）
> 状态：卡0 定案（只读侦察完成，待卡1 施工）
> 铁律：先查再写（铁律0）；API 契约/网关零改（铁律1）；JPA ddl-auto=update 自动建表、零 Flyway（铁律2，仿 B49 procurement 先例）；枚举中文外露（铁律3）；数组返回非 Page（铁律4）；前端诚实接真、模板/样式零改（铁律5、铁律 -1-B）；写接口四件套＋append-only 审计（铁律6）；三轨真验（铁律7）；一卡一 commit 紧跟 push（铁律8）
> 数字约束：✅109 / 🔧1 / ⬜55 = 166（约 66%）三个数字本卡一律不动；功能计数仅卡3 批末按 B54 先例跃迁

---

## §1 现状与缺口

### 1.1 模板定义（本批切什么）

| 编号 | 页面 | 路由 | 当前数据源 | 目标数据源 |
|---|---|---|---|---|
| M2-11 | 物料申领 | `/m2-requisition` | 前端 mock store `src/stores/requisition.ts`（6 条种子） | store-service 真实 JPA＋数据域＋审计 |
| M2-12 | 损耗报损 | `/m2-wastage` | 前端 mock store `src/stores/wastage.ts`（7 条种子） | store-service 真实 JPA＋数据域＋审计 |

两页均为「既有页面换数据源」，**零新页面、零菜单、零路由**（前端 router/index.ts L94-95、nav.ts L191-192/L452-453 已注册）。

### 1.2 当前状态

- HEAD `e6c41e1`（B79 已闭、已 push），工作区 clean；M2 进度 1/13（M2-10 采购已随 M1 切真）。
- 两 View 已存在：`src/views/RequisitionView.vue`（375 行）、`src/views/WastageView.vue`（379 行）。
- 两 mock store 即活规格（铁律 -1-A：mock 与 README 冲突时以 mock 为准），本 DESIGN 的字段/状态机/种子/KPI 全部逐字对齐 mock。

### 1.3 已有基础设施（直接复用，不新建）

| 设施 | 位置 | 复用结论 |
|---|---|---|
| 采购实体母本 | `store/procurement/PurchaseOrder.java`（86 行） | 注解/索引/时间列/`@PrePersist` 直接仿抄 |
| 单号生成母本 | `PoNoGenerator.java`（30 行） | synchronized＋maxSeqOfDay 防重号模式仿抄 |
| 数据域 | `com.meiyun.security.DataScope.storeSpec(...)`＋`canReadStore` | Specification 基座＋三守卫直接仿抄 |
| 权限码 | org-service `PermissionMatrix.java` | `requisition:view/create/edit/sign`、`wastage:view/create/edit/sign` **8 码已三处角色块播种（L59-60/L197-202 等），零新增** |
| 审计 | `ConsumableAuditRecorder.record(bizType,txnNo,actor,action,payload)`＋audit_outbox 兜底 | bizType 复用既有字符串 **"REQUISITION" / "LOSS_REPORT"**（见 ConsumableService 既有映射） |
| 网关 | Go 网关 `/api/stores` 子树 → store-service:8085 | **零改**；新端点挂在既有子树下 |
| 建表 | `spring.jpa.hibernate.ddl-auto: update`（application.yml 已配） | 新表由 Hibernate 自动建，**零 Flyway** |

### 1.4 缺口清单

| 缺口 | 说明 | 收口卡 |
|---|---|---|
| G1 | 申领领域无后端：实体/仓库/单号/Service/Controller/种子全缺 | 卡1 |
| G2 | 申领前端未接真：api 缺、store 为 mock、View 读 mock | 卡1 |
| G3 | 报损领域无后端（同构） | 卡2 |
| G4 | 报损前端未接真（同构） | 卡2 |
| G5 | 批末台账/哨兵/交付物回写 | 卡3 |

---

## §2 口径定义

### 2.1 M2-11 物料申领状态机（逐字对齐 mock）

```
DRAFT 草稿 ──submit──▶ SUBMITTING 审批中 ──approve──▶ APPROVED 待签收 ──receive──▶ RECEIVED 已签收
                                  └──reject──▶ REJECTED 已驳回（终态，不回草稿）
```

- 状态外露中文：草稿 / 审批中 / 待签收 / 已签收 / 已驳回；pill：draft / primary / warning / success / danger。
- 列表/详情：`requisition:view`；create：`requisition:create`；submit/receive/addNote：`requisition:edit`；approve/reject：`requisition:sign`。
- 状态落字段：approve 同事务落 approver/approvedAt；reject 同事务落 approver/approvedAt/rejectReason（终态）；receive 落 receiver/receivedAt（mock 中 receiver＝applicant 本人）。
- notes（append-only 时间线，外露文案逐字）：
  - create「创建申领单（草稿）」（mock 种子首条文案为「创建申领单」，真实 create 用带括号全文，种子照 mock）
  - submit「提交审批」
  - approve「审批通过」（带 note 时「审批通过：{note}」）
  - reject「驳回：{reason}」
  - receive「已签收物料」
  - addNote：自由备注（不进状态机）

### 2.2 M2-12 损耗报损状态机（逐字对齐 mock）

```
DRAFT 草稿 ──submit──▶ SUBMITTING 待审批 ──approve──▶ APPROVED 已通过（终态）
                                    └──reject──▶ REJECTED 已驳回（终态）
```

- **无签收节点**；状态外露：草稿 / 待审批 / 已通过 / 已驳回；pill：draft / primary / success / danger。
- 原因枚举（WastageReason，外露中文）：BROKEN 破损 / EXPIRED 过期 / INVENTORY_LOSS 盘亏 / OTHER 其他。
  - reasonPill（danger/warning/info/default）映射在 **WastageView 本地**，后端不输出 pill。
- 权限：列表/详情 `wastage:view`；create `wastage:create`；submit/addNote `wastage:edit`；approve/reject `wastage:sign`。
- 高值阈值：**HIGH_VALUE_THRESHOLD＝500 元**；任意状态 amount≥500 行加 `row--high`。
- 月度 KPI（前端 computed）：仅 APPROVED 单据，按 `(approvedAt||createdAt)` 落在浏览器本年本月，统计笔数与金额求和。
- 列表排序：filtered 按 occurredAt DESC。

### 2.3 单号定案（关键，明示存储/展示/位移）

| 项 | 定案 |
|---|---|
| 存储格式（落库防重号） | **`RQ-yyyyMMdd-000001`** / **`WS-yyyyMMdd-000001`**（前缀含连字符共 3 字符，连字符第 3 位与第 12 位，序号 6 位） |
| 当日序号 SQL | `substring(xx_no from 13)`（**3 字符前缀几何同 BOM，勿用 PO 的 from 12**，否则 cast 错位回退撞键） |
| Generator | `RqNoGenerator`/`WsNoGenerator`：前缀 `"RQ-"+day+"-"` / `"WS-"+day+"-"`，`String.format("%06d",max+1)`，synchronized |
| 展示格式（对齐 mock） | `RQ-yyyyMMdd-001` / `WS-yyyyMMdd-006`（序号 **6→3 位**） |
| 前端适配 | parse 存储串末段序号 `Number`，`padStart(3,'0')` 重组；序号 >999 时回落展示完整 6 位串；适配在 store 内消化，View 零改 |
| 历史原则 | 后端 6 位为防重准号；mock 的 3 位仅为展示形态 |

### 2.4 金额 / 字段口径

- 报损金额：后端 `amount_fen bigint` 存**分**；读接口输出 `amountYuan`（元，÷100，r2 精度）；写接口入参 `amountFen`（分，×100）。
- 申领单无金额（mock 明细只有 name/spec/qty/unit），不设计价、不汇总。
- 明细/备注全部自由文本，**无 skuCode**（不绑定耗材台账 SKU）。
- id：后端 Long；读 DTO id 为 number，前端 String(id)；写动作按 Long 路径参。
- 时间：`java.time.OffsetDateTime`，`@PrePersist` 兜底 createdAt。

### 2.5 数据域（多门店隔离，铁律 -1-D）

- 列表 Service 以 `DataScope.storeSpec("storeCode")` 强制叠加 Specification。
- Controller 三守卫（仿 PurchaseOrderController L100-161）：
  - resolveReadStoreCode：GROUP/BRAND/超按传参（空＝全量）；REGION 传参越权→`"__NONE__"` 短路空列表，无参且仅 1 可见店→该店；STORE 强制 `u.storeCode()`，无门店→`__NONE__`。
  - resolveWriteStoreCode：GROUP/BRAND 须显式门店；REGION/STORE 须显式且在可写范围，否则中文 400。
  - detail 越权统一 **404**；写越权统一 **400**「无权操作该门店…」。
- 门店码来源：mock 单据无门店字段，种子全部落 **SST01**（SE001 店长本店，保证 seed 登录可见）；真实建单由前端 storeContext 传 currentStoreCode。

### 2.6 审计口径（append-only＋写接口四件套）

- bizType：`"REQUISITION"`（申领）、`"LOSS_REPORT"`（报损）；txnNo＝单号。
- action：`CREATE` / `SUBMIT` / `APPROVE` / `REJECT` / `RECEIVE`（NOTE 仅落单据 notes，是否记审计仿采购 addNote 母本，默认不记状态类审计）。
- payload：手工拼**合法 JSON** 字符串（jsonStr 对文本值做 `"`/`\`/换行转义），禁裸文本；走 recorder，失败落 audit_outbox。
- 写接口四件套：入参校验（中文 400/422）＋状态机前置校验（非法迁移中文拦截）＋幂等/防重（单号 DB 唯一约束 uk_rq_no/uk_ws_no＋序号 synchronized）＋全动作审计。

---

## §3 不动项

1. `<template>` / `<style>`：RequisitionView.vue、WastageView.vue **零改动**（铁律 -1-B；差异全在 script/store 适配层消化）。
2. 前端路由、菜单项、面包屑、v-perm 键名零改。
3. 网关路由/前缀零改；不新增 internal HTTP（同 JVM 直调）。
4. 权限矩阵零新增（8 码已播种）；application.yml 零改。
5. 零 Flyway、零新增 CSS、零新 npm 依赖。
6. ✅109/🔧1/⬜55＝166 计数本卡不动。

## §4 不做项（backlog 化）

1. **严格双签 / 岗位序列校验**：README 红线要求领用/报损双签，mock 为申请人→店长「苏晴」单级签。依铁律 -1-A-4 以 mock 活规格为准做单级；严格双签（店长＋区域/职能分离）写入 04-backlog。
2. **SKU 绑定 / 实物扣库**：B80 仅做单据审批流，不联动耗材台账（不抄 GoodsReceipt 的 stockIn 联动）；SKU 绑定、签收扣减/报损冲销库存入 backlog。
3. **BOM 缺料→申领/申购联动**（04-backlog L103 相关）：本批不做，预留。
4. 分页（当前数组返回＋前端 computed，数据量小）；单号规则改造；附件/凭证上传——均不做。

---

## §5 施工清单

### 卡1 · M2-11 申领（一卡一 feat commit）

后端（新建扁平包 `backend/store-service/.../com/meiyun/store/requisition/`，仿 procurement）：

| 文件 | 说明 |
|---|---|
| `Requisition.java` | 主表实体 |
| `RequisitionItem.java` | 明细行 |
| `RequisitionNote.java` | 时间线条目 |
| `RequisitionRepository.java` | Jpa＋JpaSpecificationExecutor＋`maxSeqOfDay`（substring from 13） |
| `RequisitionItemRepository.java`、`RequisitionNoteRepository.java` | 按 rqId 查 |
| `RqNoGenerator.java` | synchronized 防重号 |
| `RequisitionService.java` | 状态机/数据域/审计/DTO 组装 |
| `RequisitionController.java` | `/api/stores/requisitions` |
| `RequisitionDataInitializer.java` | `@Order(70)`，meiyun_seed 门控，6 条种子 |

前端：

| 文件 | 说明 |
|---|---|
| `src/api/requisition.ts` | DTO＋端点（新建） |
| `src/stores/requisition.ts` | **重写**为接真 store（保留旧导出/方法名，View 零改） |
| `src/views/RequisitionView.vue` | 仅当 script 内接口名不可避免时最小编辑；template/style 零改 |

预估：后端 ~9 files，前端 ~2 files（+1 新建 / 1 重写）。

### 卡2 · M2-12 报损（同构，一卡一 feat commit）

- 新建包 `com/meiyun/store/wastage/`：`Wastage`（含 amount_fen/reporter/occurred_at/location/description）、`WastageNote`、两 Repository（maxSeqOfDay from 13）、`WsNoGenerator`、`WastageService`、`WastageController`（`/api/stores/wastages`）、`WastageDataInitializer`（`@Order(80)`，7 条种子）。
- 前端：`src/api/wastage.ts`（新建）、`src/stores/wastage.ts`（重写，monthCount/monthAmount/highValue 由真实数组 computed、reasonPill 不进 store）、WastageView template/style 零改。
- 预估：后端 ~8 files，前端 ~2 files。

### 建表清单（5 张，Hibernate ddl-auto=update 自动建，零 Flyway）

| 表 | 唯一约束 | 索引 | 关键字段 |
|---|---|---|---|
| `requisition` | uk_rq_no(rq_no) | idx_rq_store(store_code)、idx_rq_status(status)、idx_rq_applicant(applicant) | rq_no(32)、store_code(16,nn)、status(16,nn)、applicant(64,nn)、purpose、remark(255)、approver(64)、receiver(64)、reject_reason(255)、approved_at/received_at/created_at |
| `requisition_item` | — | idx_rq_item_rq(rq_id) | rq_id(nn)、line_no(nn)、name(64,nn)、spec(32)、qty(int nn)、unit(8,nn) |
| `requisition_note` | — | idx_rq_note_rq(rq_id) | rq_id(nn)、note_by(64,nn)、content(255,nn)、note_at(nn) |
| `wastage` | uk_ws_no(ws_no) | idx_ws_store(store_code)、idx_ws_status(status)、idx_ws_reason(reason) | ws_no(32)、store_code(16,nn)、status(16,nn)、reason(16,nn)、item_name(64,nn)、spec(32)、qty(int nn)、unit(8)、amount_fen(bigint nn)、reporter(64,nn)、location(64)、description(255)、approver(64)、reject_reason(255)、occurred_at/approved_at/created_at |
| `wastage_note` | — | idx_ws_note_ws(ws_id) | ws_id(nn)、note_by(64,nn)、content(255,nn)、note_at(nn) |

> 注：列名避开 SQL 保留字（by→note_by、text→content、at→note_at）。

### 种子数据（全字段对齐 mock，逐条落 SST01）

**申领 6 条（@Order70）**：①周敏（美容师）A03 治疗室日常耗材补充 DRAFT h-2〔一次性治疗巾 40×50cm 50 包；医用棉签 竹棒 20 盒〕②李娜（前台）B02 射频项目客户使用 SUBMITTING h-5〔冷凝胶 500ml 6 瓶；一次性手套 M 码 4 盒〕③吴桐（运营）激光术后护理备货 APPROVED h-20〔医用修复面膜 6片/盒 30 盒；生理盐水 250ml 20 瓶；纱布块 8层 10 包〕④顾屿（主治医师）C01 注射室一次性器械 RECEIVED h-48〔一次性注射器 1ml 100 支；医用酒精棉片 独立包装 200 片〕⑤夏沫（前台）候诊区日常物资 REJECTED h-72 remark/驳回「数量超出月度预算，请重新提交」〔瓶装饮用水 350ml 500 瓶；一次性纸杯 200ml 20 条〕⑥周敏（美容师）美容床品换洗 SUBMITTING h-8〔美容床笠 粉色 15 条；一次性枕套 无纺布 60 个〕。

- 派生照 mock：approver 苏晴（店长）（APPROVED/RECEIVED/REJECTED）；approvedAt＝h-(i+3)；receiver/receivedAt（RECEIVED，i+2）；rqNo 用 createdAt 日期（h-48/h-72 落昨日号）＋序号 i+1。
- **mock 瑕疵修正**：mock 中 SUBMITTING 的「提交审批」时间＝h-(i+10) 早于创建（对 i=1 为 h-11 < h-5），时间倒挂。种子改为「提交审批」＝createdAt 前 1 小时（逻辑单调），其余 notes 时间照 mock；此偏差仅为修正倒挂，页面文案/状态/KPI 不变。

**报损 7 条（@Order80）**：①医用修复面膜 6片/盒 2盒 ¥336 EXPIRED「库存盘点发现 2 盒已过保质期」耗材仓 B 区 APPROVED 吴桐 h-20 ②冷凝胶 500ml 1瓶 ¥180 BROKEN「取用过程中不慎跌落，瓶身破裂无法使用」B02 治疗室 SUBMITTING 周敏 h-3 ③玻尿酸原液 1ml/支 5支 ¥4250 EXPIRED「冷链断电 4 小时，整盒效期受影响报废」冷藏柜 1 SUBMITTING 顾屿 h-6 ④一次性注射器 1ml 20支 ¥40 INVENTORY_LOSS「月末盘点账实不符，差异 20 支」耗材仓 APPROVED 苏晴 h-72 ⑤射频治疗手柄 标配 1个 ¥3800 BROKEN「客户治疗过程中手柄异常发热，检修判定主板损坏」B02 治疗室 REJECTED 李娜 h-96 驳回「建议先走设备维修工单，再按维修结果处理资产报废」⑥瓶装饮用水 350ml 12瓶 ¥36 OTHER「包装破损污染，无法提供给客户」候诊区 DRAFT 夏沫 h-1 ⑦医用酒精棉片 独立包装 30片 ¥15 EXPIRED「独立包装上有效期已过」C01 注射室 APPROVED 顾屿 h-120。

- 金额存分：33600/18000/425000/4000/380000/3600/1500。
- 派生照 mock：approver 苏晴（APPROVED/REJECTED）、approvedAt＝h-max(i,1)、wsNo 按 createdAt 日期＋序号；notes＝创建→(非DRAFT)提交审批 h-(i+2)→(APPROVED)审批通过 h-max(i,1)→(REJECTED)驳回全文。
- **可复现基线 KPI**：本月 APPROVED＝①④⑦共 3 笔、金额 336＋40＋15＝**¥391**；高值（全状态≥500）＝③4250、⑤3800 共 **2 笔**；SUBMITTING 待审批＝②③共 2。
- 相对时间戳以播种运行时 `OffsetDateTime.now().minusHours(n)` 计算，保证月 KPI 落本月（月初前 5 日边界属 seed-only 可接受，沿用各 DataInitializer 先例）。

### 前端 store 接真约定（两 store 同范式）

- `seed()` **保留旧名**（View 的 onMounted 调 `store.seed()`），内部改为调真实 load；同步保留 View 实际引用的全部方法/导出名（STATUS_LABEL/STATUS_PILL/REASON_LABEL/HIGH_VALUE_THRESHOLD/filtered/各 KPI 等）。
- `create(...)`：POST 后端，用返回 DTO **同步返回新建对象**（View 内 `const o = store.create(...)` 取 `o.id`）；内部完成入参元→分换算。
- 动作成功后 `await reload()` 重拉，保证与后端一致；**诚实接真，不保留 mock 回落**（仿 schedule 先例；m1Procurement 的 demo/workflowDemo 回落不抄）。
- 适配层消化：String(id)、fen↔yuan、字段改名（storeCode/amountYuan 等）、nz 空值、r2 精度、单号 6→3 展示。

---

## §6 三轨真验计划

### 轨1 · curl（seed 栈，网关 https:18443，SE001 店长）

1. 登录 `POST /api/org/auth/login`（SE001 / meiyun123）取 token。
2. `GET /api/stores/requisitions` → 6 条；`GET /api/stores/wastages` → 7 条；核对单号展示前形态（存储串）、状态/金额。
3. 造数走完整链路：申领 create→submit→approve→receive；报损 create→submit→approve；再验 reject 终态、非法迁移中文错误、越权（必要时换 SE102 区域/SE101 集团验数据域）。
4. 验写接口四件套：空参/非法状态机 400 中文；重复单号不撞（唯一约束）。

### 轨2 · PostgreSQL（meiyun_seed，容器 meiyun-pg，宿主 5433）

- 核对 5 张表由 Hibernate 自动建成（`\dt requisition*` / `wastage*`）、列/索引/唯一约束存在。
- 核对种子条数（6/7）、amount_fen 分值、approver/receiver/时间戳、notes 条数与文案。
- 审计核对：audit-service 侧收到 REQUISITION/LOSS_REPORT 记录（或失败时 audit_outbox 有兜底）。

### 轨3 · Chrome（seed 前端 18080，SE001 登录态）

- 两页列表/KPI 与 mock 基线视觉一致（截图对照；take_screenshot 不落盘，以对话附件为准）。
- 申领：4 KPI、状态 pill、时间线文案、提交/审批/签收按钮流转、驳回 prompt。
- 报损：本月 3 笔/¥391、待审批 2、高值 2（row--high 高亮 4250/3800）、reasonPill、驳回 prompt。
- 核验 template/style 零改导致无视觉回归。

### 造数还原

- 轨1 造的测试单据在验毕后，按其生成单号/门店在**单事务内物理 DELETE**（主表＋item＋note）还原 seed 库，恢复 6/7 基线；操作前先对相关表做 `pg_dump` 单表备份到临时文件，验毕确认后清理。

### OCR 门禁（铁律12）

- commit 前执行 `ocr review`；无 CLI 则人工 8 类敏感正则（password/secret/api_key/token、手机号、身份证、银行卡、邮箱、内网 IP/localhost、meiyun123、真实人名）逐文件扫描＋目检替代，并在汇报注明。

---

## §7 数字影响表

| 项 | 本批影响 | 说明 |
|---|---|---|
| 新页面 | 0 | 两页均既有 |
| 新功能计数（✅/🔧/⬜） | 0（卡0-2 不动） | 卡3 批末按 B54 先例跃迁：M2 1/13→3/13，✅109→111、⬜55→53；🔧1 不变 |
| 新业务表 | **5** | JPA ddl-auto=update 自动建，**零 Flyway**；建表不影响 166 功能计数 |
| 新权限码 | 0 | 8 码已播种 |
| 新网关路由 | 0 | 挂既有 /api/stores 子树 |
| 新 CSS | 0 | 模板/样式零改 |
| 新 Flyway 迁移 | 0 | 沿用 procurement 先例 |
| 新 npm 依赖 | 0 | 复用 client/pinia/storeContext |

> 域⑦门店运营：批末 11⬜/13 页面口径随之变为 13✅/11⬜（仅卡3 台账原子回写时落数）。
