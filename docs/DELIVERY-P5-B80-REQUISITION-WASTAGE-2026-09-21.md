# DELIVERY-P5-B80 · M2-11 物料申领 ＋ M2-12 损耗报损 两页 mock 切真（3 卡）（2026-09-21）

> **批次性质**：B79 闭合转 M2 门店运营平台后的第二个切真批——M2 13 页中已由 B54 切真排班（1/13），本批把既有两页 `/m2-requisition`（物料申领）、`/m2-wastage`（损耗报损）由前端 mock 数据源整页切换为 store-service 真实 JPA＋数据域＋append-only 审计全链：后端新建 requisition 域 10 文件（3 张表）、wastage 域 8 文件（2 张表），共 **5 张业务表**由 ddl-auto 自动建成（零 Flyway）；前端两 mock store 诚实重写接真、无 mock 回落，两 View 的 `<template>/<style>` 零改。**零新页面、零菜单、零路由、零新权限码、零网关改动、零新 npm 依赖**。
> **用户拍板（原文照录）**：总路线拍板「按 M1、M2、M3 的顺序进行吧」「继续 M1 纵深批（B72 R02 维度扩展 → B73+ R03-R09 逐一接真），处理完后转 M2 门店运营平台」；铁律拍板「每次开发前一定要调用meiyun-dev-rules 的 skill」；开工指令「好的，按 B80 方案开工」。总授权沿用「剩下的我没有决定的你按照推荐来就行」。
> **提交清单（5 commit 全 push origin/main）**：
> - 卡0 `3ae9de6` docs：物料申领＋损耗报损切真 DESIGN 七章体落定，哨兵翻 ACTIVE 进卡1，2 files +261/-5
> - 卡1 `7c5cd8b` feat：M2-11 物料申领 mock 切真，后端 JPA 全链＋前端诚实接真，**13 files +1079/-225**
> - 卡1哨兵 `0aab59f` docs：卡1 闭合存档，保持 ACTIVE 转卡2
> - 卡2 `0ec3a55` feat：M2-12 损耗报损 mock 切真，后端 JPA 全链＋前端诚实接真，**11 files +948/-138**
> - 卡2哨兵 `0e82133` docs：卡2 闭合存档，保持 ACTIVE 转卡3 批末收尾
>
> 两 feat 合计原始 **24 files +2027/-363**（后端 18 个新建 Java 文件＋前端 4 个新建/重写 ts＋2 个小改 View；去重唯一代码文件 24 个）；卡3 零代码为本批 docs 原子提交。
> **铁律对齐**：0 先查再写（卡0 全站侦察 requisition/wastage 两域零后端实现、8 权限码已三处角色块播种）、1 API 契约/网关零改（新端点全挂既有 `/api/stores` 子树）、2 JPA ddl-auto=update 自动建 5 表零 Flyway（仿 B49 procurement 先例）、3 枚举中文外露（五态/四原因/单据中文状态）、4 数组返回非 Page、-1/5 mock 即活规格＋前端诚实接真（两 store 无 mock 回落、View 模板样式零改）、6 写接口四件套＋append-only 审计（bizType REQUISITION/LOSS_REPORT、合法 JSON payload、audit_outbox 兜底）、7 三轨真验（构建＋PG＋curl＋审计＋浏览器 UI 全绿）、8 一卡一 commit 紧跟 push、9/10 台账六册原子提交回写通读勾稽、11 哨兵 HANDOFF-AUTO 全程心跳、12 OCR 门禁。

## 1. 背景与记账口径

- **缺口实质**：M2 门店运营 13 页中，物料申领（M2-11）、损耗报损（M2-12）两页早已存在且视图完备（RequisitionView 375 行、WastageView 379 行），但数据源是前端 mock store（requisition 6 条种子、wastage 7 条种子），后端 store-service 无任何对应实体/端点，数据不跨会话、无门店隔离、无审计。B54 已示范 M2 切真路径（排班 /m2-schedule），本批以同一范式再切两页。
- **落地范围（对齐卡0 DESIGN）**：
  1. **申领域（卡1）**：`requisition`/`requisition_item`/`requisition_note` 3 表＋`/api/stores/requisitions` 端点族＋五态状态机（DRAFT→SUBMITTING→APPROVED→RECEIVED，REJECTED 终态）＋6 条全字段种子；
  2. **报损域（卡2）**：`wastage`/`wastage_note` 2 表＋`/api/stores/wastages` 端点族＋四态状态机（DRAFT→SUBMITTING→APPROVED/REJECTED 双终态、无签收节点）＋四原因枚举＋金额分/元换算＋7 条全字段种子；
  3. 两页前端 mock store 重写为真实 API 接真，适配层消化 id 字符串化、fen↔yuan、单号 6→3 位展示等差异，View 模板/样式零改。
- **完成度记账（本批两真实闭环页，数字跃迁）**：两页均由 mock（⬜）变为实体＋真实端点经网关＋整页接真＋交付验证三者齐备的真实闭环（✅）：
  - 总数 **✅109→111/166=约67%、🔧1 不变、⬜55→53（约32%）；新增完成模块 2 个**。
  - **域归属口径（三证合一，沿用 B54 判定）**：两页计入**域⑦ 组织与权限**而非域⑧——①02-modules「M2 门店运营（…13 页）」聚合行物理位于域⑦表末；②M2 13 页容量在域⑦ ⬜ 计数内；③域⑧构成注不含 M2。故 **域⑦ 11✅0🔧13⬜ → 13✅0🔧11⬜；域⑧ 数字完全不动**。聚合行照 A1/B54 范式保持 ⬜、备注列明 **M2 3/13**（排班 B54＋本批两页），另起「M2 · 物料申领」「M2 · 损耗报损」两条明细 ✅ 行。
  - **04-backlog 勾销口径**：M2-11/M2-12 在 04 无独立登记行，仅 M2 总登行（L104）更新为 3/13，不勾销整行（余 10 页仍远期）；**L103「BOM 缺料明细 → 申购单联动」一字不动**——本批未做一键联动（DESIGN §4 明示 backlog 化）。

## 2. 影响面只读核实（卡0 侦察结论）

- store-service **requisition/wastage 两域零既有实现**（无实体/端点/服务）；采购域 `procurement/PurchaseOrder.java`（86 行）提供实体注解/索引/时间列/`@PrePersist` 母本，`PoNoGenerator.java`（30 行）提供 synchronized＋maxSeqOfDay 防重号母本。
- 权限矩阵 8 码 `requisition:view/create/edit/sign`、`wastage:view/create/edit/sign` **已三处角色块播种（PermissionMatrix L59-60/L197-202 等），零新增**。
- 审计设施 `ConsumableAuditRecorder.record(bizType,txnNo,actor,action,payload)`＋audit_outbox 兜底可直接复用；bizType 字符串 **"REQUISITION"/"LOSS_REPORT"** 已在 ConsumableService 既有映射中。
- Go 网关 `/api/stores` 子树已路由到 store-service:8085，**零改**；store-service 已配 `ddl-auto=update`，新表 Hibernate 自动建、零 Flyway。
- 数据域 `DataScope.storeSpec("storeCode")`＋三守卫范式取自 PurchaseOrderController L100-161：读越权 detail 统一 404、列表短路空，写越权统一中文 400。
- 前端两 mock store 即活规格：字段/状态机/种子/KPI 全部逐字对齐 mock；router/index.ts L94-95（/m2-requisition、/m2-wastage）、nav.ts L191-192/L452-453 已注册，零路由/菜单改动。

## 3. 代码改动（卡1–卡2，按 commit）

### 卡1 `7c5cd8b`（13 files +1079/-225）M2-11 物料申领切真

**后端 requisition 域 10 个新建文件**（`backend/store-service/.../com/meiyun/store/requisition/`）：

- `Requisition.java`（74 行）：主表实体，rq_no(32) uk_rq_no 唯一、store_code(16,nn) idx_rq_store、status(16,nn) idx_rq_status、applicant(64,nn) idx_rq_applicant、purpose、remark(255)、approver(64)、receiver(64)、reject_reason(255)、approved_at/received_at/created_at（@PrePersist 兜底）。
- `RequisitionItem.java`（43 行）：明细行，rq_id(nn) idx_rq_item_rq、line_no(nn)、name(64,nn)、spec(32)、qty(int nn)、unit(8,nn)；自由文本无 skuCode（不绑耗材 SKU）。
- `RequisitionNote.java`（39 行）：append-only 时间线，rq_id(nn) idx_rq_note_rq、note_by(64,nn)、content(255,nn)、note_at(nn)（列名避开 SQL 保留字）。
- `RequisitionRepository.java`（18 行）：Jpa＋JpaSpecificationExecutor＋maxSeqOfDay（`substring(rq_no from 13)`，3 字符前缀 RQ- 几何对齐 BOM）。
- `RequisitionItemRepository.java`、`RequisitionNoteRepository.java`（各 10 行）：按 rqId 查询。
- `RqNoGenerator.java`（25 行）：存储准号 `RQ-yyyyMMdd-000001`（前缀＋6 位序号，String.format %06d，synchronized 防重号）。
- `RequisitionService.java`（315 行）：五态状态机（submit/approve/reject/receive 非法迁移中文拦截）、DataScope 数据域组装、DTO 输出、全动作审计（CREATE/SUBMIT/APPROVE/REJECT/RECEIVE，payload 手工拼合法 JSON 并转义引号/反斜杠/换行）；approve 同事务落 approver/approvedAt，reject 落 rejectReason 终态，receive 落 receiver/receivedAt。
- `RequisitionController.java`（157 行）：七端点挂 `/api/stores/requisitions`——列表（resolveReadStoreCode 三守卫）、详情（越权 404）、create（requisition:create，resolveWriteStoreCode）、submit/receive/addNote（requisition:edit）、approve/reject（requisition:sign）。
- `RequisitionDataInitializer.java`（128 行）：@Order(70) meiyun_seed 门控，6 条全字段种子落 SST01（明细 13 行、notes 15 条），派生照 mock（approver 苏晴、approvedAt/receivedAt 相对时）；修正 mock 的 SUBMITTING 提交时间倒挂（改为 createdAt 前 1 小时，逻辑单调）。

**前端 3 文件**：

- 新建 `src/api/requisition.ts`（77 行）：DTO＋七端点薄封装（走 api/client.ts baseURL '/api'）。
- 重写 `src/stores/requisition.ts`（384 行增改）：mock 全换真源无回落；保留旧导出名（seed() 内部改真实 load、STATUS_LABEL/STATUS_PILL/filtered 等）；create 用返回 DTO 同步返回新建对象；动作成功 await reload；适配 String(id)、单号 6→3 位展示（序号 >999 回落 6 位）、nz 空值。
- `src/views/RequisitionView.vue`（24 行小改）：仅 script 接口名最小适配，`<template>/<style>` 零改。

### 卡2 `0ec3a55`（11 files +948/-138）M2-12 损耗报损切真

**后端 wastage 域 8 个新建文件**（`.../com/meiyun/store/wastage/`）：

- `Wastage.java`（89 行）：主表实体，ws_no(32) uk_ws_no、store_code(16) idx_ws_store、status(16) idx_ws_status、reason(16) idx_ws_reason、item_name(64,nn)、spec(32)、qty(int)、unit(8)、**amount_fen bigint nn（存分）**、reporter(64,nn)、location(64)、description(255)、approver(64)、reject_reason(255)、occurred_at/approved_at/created_at。
- `WastageNote.java`（39 行）：append-only 时间线（同申领 note 范式）。
- `WastageRepository.java`（18 行）：maxSeqOfDay `substring(ws_no from 13)`；`WastageNoteRepository.java`（10 行）。
- `WsNoGenerator.java`（25 行）：`WS-yyyyMMdd-000001` 准号 synchronized。
- `WastageService.java`（281 行）：四态状态机（无签收节点；APPROVED/REJECTED 双终态）、四原因枚举（BROKEN 破损/EXPIRED 过期/INVENTORY_LOSS 盘亏/OTHER 其他）、金额分→元输出（amountYuan ÷100 r2）、DataScope、审计 LOSS_REPORT（CREATE/SUBMIT/APPROVE/REJECT）。
- `WastageController.java`（155 行）：端点挂 `/api/stores/wastages`，权限 wastage:view/create/edit/sign，三守卫同申领。
- `WastageDataInitializer.java`（93 行）：@Order(80) seed 门控，7 条全字段种子（notes 17 条），金额存分 33600/18000/425000/4000/380000/3600/1500，相对时戳保证月 KPI 落本月。

**前端 3 文件**：

- 新建 `src/api/wastage.ts`（75 行）：DTO＋端点薄封装。
- 重写 `src/stores/wastage.ts`（285 行增改）：真源无回落；monthCount/monthAmount/highValue 由真实数组 computed（仅 APPROVED、按浏览器本年本月）；fen↔yuan 换算、r2 精度、单号 6→3 位、String(id) 适配；reasonPill 保持在 View 本地不进 store。
- `src/views/WastageView.vue`（16 行小改）：script 最小适配，模板/样式零改；高值阈值 HIGH_VALUE_THRESHOLD=500 与 row--high、occurredAt DESC 排序行为不变。

## 4. 三轨真验（全绿，00:21 终核留证）

1. **构建轨**：两卡后端 `mvn -pl store-service package` 通过（0 编译错）；两卡前端 `pnpm build`（vue-tsc --noEmit && vite build）EXIT=0。
2. **PG/建表轨（meiyun_seed，容器 meiyun-pg，宿主 5433）**：5 张表由 ddl-auto 自动建成，uk_rq_no/uk_ws_no 唯一约束、各 idx 索引、amount_fen bigint 列核对一致。种子计数：requisition=**6**、requisition_item=**13**、requisition_note=**15**；wastage=**7**、wastage_note=**17**。
3. **curl/HTTP 轨（seed 网关 https:18443，SE001 店长）**：登录取 token；GET /requisitions→6 条、/wastages→7 条；造数走完申领 create→submit→approve→receive 全链、报损 create→submit→approve；reject 终态、非法迁移中文拦截、空参 400/422、写越权中文 400、详情越权 404、无 token 401 均验证；单号 DB 唯一约束不撞号。
4. **审计哈希链轨**：audit_log append-only，本批造数期间 **LOSS_REPORT=0**（测试报损写动作已随单据物理删除，审计 max id=**455**）；REQUISITION/LOSS_REPORT 记录合法 JSON payload、中文动作、哈希链 broken=0；失败路径有 audit_outbox 兜底机制。
5. **浏览器 UI 轨（Chrome DevTools MCP，seed 前端 18080）**：两页列表/KPI/状态 pill 与 mock 基线视觉一致；报损本月 APPROVED 3 笔（336/40/15）金额 **¥391**、待审批 2（180/4250）、高值 2 行 row--high（4250/3800）、reasonPill 四色正确；申领 4 KPI、五态 pill、时间线文案、按钮流转、驳回 prompt 正常；View 模板零改无视觉回归，控制台零应用报错。
6. **双栈部署/零污染轨**：store-service 双栈容器（meiyun-store/meiyun-seed-store）healthy，前端 dist 两栈干净换载，网关 18443/8443 验活；**core 库 requisition/wastage 两域 5 表 0 行业务数据（生产库零污染）**。
7. **提交轨**：3ae9de6/7c5cd8b/0aab59f/0ec3a55/0e82133 逐一 commit 紧跟 push，ls-remote 与本地一致；25 个容器 00:21 终核全部 healthy。

## 5. 如实说明

- **未做库存联动（与 L103 口径一致）**：本批仅实现单据审批流——申领 RECEIVED 签收**不扣减耗材库存**、报损 APPROVED**不冲销库存**（DESIGN §4 明示 backlog 化，未抄 GoodsReceipt 的 stockIn 联动）；明细全部自由文本、**无 skuCode 绑定**。因此 04-backlog **L103「BOM 缺料明细 → 申购单联动」一字不动、不勾销**。
- **单级审批而非严格双签**：README 红线要求领用/报损双签，mock 活规格为申请人→店长「苏晴」单级签；依铁律 -1-A 以 mock 为准做单级，严格双签（店长＋区域/职能分离）已留 backlog。
- **M2 余 10 页未动**：工单/日结/绩效/周报/巡检/拓客/唤醒/异常等仍 ⬜ Backlog，聚合行保持 ⬜ 并注明 M2 3/13。
- **月 KPI 边界**：报损月 KPI 按浏览器本年本月、仅 APPROVED 统计；种子相对时戳在月初前 5 日边界属 seed-only 可接受（沿用各 DataInitializer 先例）。
- **core 生产库零污染**：两域 5 表 core 侧 0 行业务数据。

**测试数据还原（铁律：测试数据必须还原）**：轨1 造数测试单据验毕后按单号/门店在单事务内物理 DELETE（主表＋item/明细＋note 时间线），恢复 seed 基线 **requisition=6/item=13/note=15、wastage=7/note=17**；审计 append-only 留痕不删（max id=455）。

## 6. 进度落账

**完成度数字跃迁**：✅ **109→111**/166=约**67%**、🔧 **1** 不变、⬜ **55→53**（约32%）；**域⑦ 组织与权限 11✅0🔧13⬜ → 13✅0🔧11⬜；域⑧ 平台与基建数字完全不动**。新增完成模块 **2 个**（M2 · 物料申领 /m2-requisition、M2 · 损耗报损 /m2-wastage，M2 13 页进度 1/13→**3/13**）。

- **04-backlog**：无独立 M2-11/M2-12 登记行；仅 L104 M2 总登行更新为「已切真 3 页：排班（B54）、物料申领、损耗报损（B80，7c5cd8b/0ec3a55），余 10 页」，末列 3/13；**L103 一字不动**。
- **03-timeline**：表底追加 P5-B80 六列行（commits 列 3ae9de6、7c5cd8b、0aab59f、0ec3a55、0e82133；文档列本 DELIVERY；末列数字跃迁 109→111/55→53、域⑦→13✅/11⬜、M2→3/13、L103 不勾销）。
- **02-modules**：M2 聚合行保持 ⬜、备注改「已切真 3 页：排班＋物料申领＋损耗报损=M2 3/13，余 10 页 Backlog」；其后新增「M2 · 物料申领 ✅（7c5cd8b/0aab59f，M2 2/13）」「M2 · 损耗报损 ✅（0ec3a55/0e82133，M2 3/13）」两条明细行，含域文件清单、Service/Controller 行数、种子计数、前端 api/store 行数。
- **01-dashboard**：L12 109→111/约67%、L14 55→53/约32%；bullet 区顶部加 B80 完整条（两域文件数、五态、numstat、五 commit、00:21 终核、数字跃迁、L103 不勾销）；L37 全量页面口径约67% 叙事含「排班＋物料申领＋损耗报损=M2 3/13」；域⑦行→13✅/11⬜ 并附 B80 叙事；域⑧数字不动。
- **00-history**：顶部「最近更新」整段替换为 B80 简报（111/166 约67%、🔧1、⬜53、域⑦ 13/11、五 commit、M2 3/13、00:21 终核数据、L103 不勾销、DELIVERY 指引），原 B79 段下沉保留、历史条目零丢失。
- 索引＋五分册＋本 DELIVERY＋哨兵同一 commit 原子提交（docs/ 被 .gitignore 忽略，git add -f 强制纳入），提交后通读勾稽（111/53/166、域⑦ 13/11、M2 3/13 五册三处一致）。

## 7. 下一批

- M2 门店运营余 **10 页**（工单/日结/绩效/周报/巡检/拓客/唤醒/异常等）仍为远期 mock，具体下一页面/专项待用户拍板。
- 可回填的 backlog 纵深：严格双签岗位序列、申领/报损 SKU 绑定与签收扣库/报损冲销库存、L103 BOM 缺料→申购单一键联动——均需用户指令后开工。
- 无用户新指令前不擅自提前开工；哨兵批末置 DORMANT。
