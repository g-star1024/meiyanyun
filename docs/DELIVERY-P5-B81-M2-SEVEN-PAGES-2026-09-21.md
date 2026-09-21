# DELIVERY-P5-B81 · M2 余页七页 mock 切真大批（7 卡）＋ M2 13/13 数字跃迁（2026-09-21）

> **批次性质**：B80 闭合转 M2 门店运营平台后的第三个切真批，也是 M2 的收官大批——卡0 三轨核实发现 M2 台账登记 3/13（B54 排班＋B80 申领/报损），但 **房间床位、设备仪器、异常中心三页事实早已真实**（room/equipment 域齐全＋B63 卡2 异常中心三源归集），M2 事实进度实为 6/13。本批把真正仍为前端 mock 的 **7 页**整页切换为 store-service 真实 JPA＋数据域＋状态机＋append-only 审计全链：后端新建七域 **51 个 Java 文件**（外加卡1 新增全局 `GlobalExceptionHandler` 1 文件），共 **12 张业务表**由 ddl-auto 自动建成（零 Flyway）；前端新建 7 个 api、诚实重写 7 个 mock store（零 mock 回落），7 个 View 的 `<template>/<style>` 零改、仅 script async 化。**零新页面、零菜单、零路由、零新权限码、零网关改动、零新 npm 依赖**。
> **用户拍板（原文照录）**：总路线拍板「按 M1、M2、M3 的顺序进行吧」「继续 M1 纵深批（B72 R02 维度扩展 → B73+ R03-R09 逐一接真），处理完后转 M2 门店运营平台」；本批承接指令「继续 M2 余页」；铁律拍板「每次开发前一定要调用meiyun-dev-rules 的 skill」。总授权沿用「剩下的我没有决定的你按照推荐来就行」。
> **提交清单（8 commit 全 push origin/main，HEAD=`7111d37`）**：
> - 卡0 `49c2d04` docs：M2 余页七页切真 DESIGN 七章体落定＋哨兵转 ACTIVE
> - 卡1 `e4f059d` feat：M2-08 服务工单 mock 切真，后端 JPA 全链＋前端诚实接真，**12 files +952/-130**（9 后端＋3 前端，含新增 GlobalExceptionHandler +59）
> - 卡2 `6d421f6` feat：M2-06 营业日报 mock 切真，**12 files +1070/-158**（9 后端＋3 前端）
> - 卡3 `f0a5a28` feat：M2-07 员工绩效 mock 切真，**8 files +559/-39**（5 后端＋3 前端）
> - 卡4 `547a6dd` feat：M2-20 经营周报 mock 切真，**8 files +696/-122**（5 后端＋3 前端）
> - 卡5 `540ea7f` feat：M2-10 巡店检查 mock 切真，**13 files +973/-191**（10 后端＋3 前端）
> - 卡6 `872ea89` feat：M2-16 拓客活动 mock 切真，**9 files +682/-94**（6 后端＋3 前端）
> - 卡7 `7111d37` feat：M2-17 沉睡唤醒 mock 切真，**11 files +746/-109**（8 后端＋3 前端）
>
> 七 feat 合计 **73 files +5678/-843**（去重唯一文件：后端 52＝51 新建七域＋GlobalExceptionHandler 1；前端 21＝7 新建 api＋7 重写 store＋7 View script 小改）。批末 docs 卡（台账五分册＋本 DELIVERY＋哨兵）另作原子提交，代码与 docs 分离。
> **铁律对齐**：0 先查再写（卡0 全站侦察七域零后端、七 api 零文件、权限码已三处角色块播种、三页事实翻转核实）、1 API 契约/网关零改（新端点全挂既有 `/api/stores` 子树）、2 JPA ddl-auto=update 自动建 12 表零 Flyway（仿 B80/B49 先例）、3 枚举中文外露（各页状态/类型/渠道/岗位中文外露）、4 数组返回非 Page、-1/5 mock 即活规格＋前端诚实接真（七 store 无 mock 回落、View 模板样式零改）、6 写接口四件套＋append-only 审计（七 bizType、合法 JSON payload、audit_outbox 兜底）、7 三轨真验（构建＋PG＋curl＋审计＋浏览器 UI 全绿）、8 一卡一 commit 紧跟 push、9/10 台账六册原子提交回写通读勾稽、11 哨兵 HANDOFF-AUTO 全程心跳、12 OCR 门禁（无 ocr CLI，8 类正则＋人工目检替代）。

## 1. 背景与记账口径

- **缺口实质**：M2 门店运营 13 页中，排班（B54）、物料申领/损耗报损（B80）3 页已真实；卡0 三轨核实又确认房间床位（room 域）、设备仪器（equipment 域）、异常中心（B63 卡2 ExceptionCenter 三源只读归集）3 页**事实已真但台账未计**。真正缺口为剩余 7 页——服务工单、营业日报、员工绩效、经营周报、巡店检查、拓客活动、沉睡唤醒，七页视图完备（315~410 行）但数据源是前端 mock store，后端 store-service 无任何对应实体/端点，数据不跨会话、无门店隔离、无审计。
- **落地范围（对齐卡0 DESIGN）**：
  1. 七后端域：workorder/daily/performance/weekly/inspection/acquisition/reactivate，共 12 张业务表＋七个 `/api/stores/*` 端点族＋各页状态机＋全字段种子；
  2. 七前端：新建 7 api、重写 7 mock store 诚实接真，适配层消化 id 字符串化、fen↔yuan、单号 6→3 位展示、period/跨店差异，View 模板/样式零改；
  3. 批末台账订正：3 事实翻转页口径订正，M2 13/13 收口。
- **完成度记账（本批七个真实闭环页，数字跃迁）**：七页均由 mock（⬜）变为实体＋真实端点经网关＋整页接真＋交付验证四者齐备的真实闭环（✅）。
  - 批前数字因卡6 闭合时先行据实跃迁至 ✅112/🔧1/⬜52（✅111→112、⬜53→52），本批批末再就其余 6 页跃迁：**总数 ✅112→118/标注总数 166＝约71%、🔧1 不变、⬜52→46（约28%）；新增完成模块 7 个（卡6 已计 1＋本批 6）**。
  - **域归属口径（沿用 B54/B80 判定）**：七页连同既有 M2 页均计入**域⑦ 组织与权限**而非域⑧——①02-modules「M2 门店运营」聚合行物理位于域⑦表末；②M2 13 页容量在域⑦ ⬜ 计数内；③域⑧构成注不含 M2。批前域⑦ 14✅0🔧10⬜（卡6 已先行 +1），批末 **→20✅0🔧4⬜**；域⑧ 数字完全不动。聚合行照 A1/B54 范式批末翻 ✅、备注列明 **M2 13/13 全部真实闭环**，另起七页明细 ✅ 行。
  - **04-backlog 勾销口径**：七页在 04 无独立登记行，仅 M2 总登行（L104）更新为 M2 13/13 并标 ✅ B81；**L103「BOM 缺料明细 → 申购单联动」一字不动、不勾销**——本批未做一键联动。

## 2. 影响面只读核实（卡0 侦察结论）

- store-service 七域**零既有实现**（无实体/端点/服务）；B80 两域（requisition/wastage）提供实体注解/索引/时间列/`@PrePersist` 母本，`RqNoGenerator`（synchronized＋maxSeqOfDay、substring from 13）提供日序号母本，采购域 `PoNoGenerator` 同源。
- 权限矩阵七页码 `workorder:view/create/edit/close`、`daily:view/edit/submit`、`performance:view/edit`、`weekly:submit`、`inspection:view/create/edit`、`acquisition:edit`、`reactivate:edit` **已在 auth.ts L38-43/L90-95/L149-154 三处角色块播种，零新增**；PermissionMatrix 由 gen_perm_matrix.mjs 生成，不改 Java。
- 审计设施 `ConsumableAuditRecorder.record(bizType,txnNo,actor,action,payload)` 可直接复用；audit-service **无 bizType 白名单**（任意非空、≤32 字符，AuditService L47-51 实测），七域各命名 bizType。
- Go 网关 `/api/stores` 子树已路由到 store-service:8085，**零改**；store-service 已配 `ddl-auto=update`，新表 Hibernate 自动建、零 Flyway。
- 数据域 `DataScope.storeSpec("storeCode")`＋三守卫范式取自 B80：读越权 detail 统一 404、列表短路空，写越权统一中文 400。
- 前端七 mock store 即活规格：字段/状态机/种子/KPI 逐字对齐 mock；router/index.ts、nav.ts L195-207 已注册全部七路由/菜单，零路由/菜单改动。

## 3. 代码改动（卡1–卡7，按 commit）

### 卡1 `e4f059d`（12 files +952/-130）M2-08 服务工单切真

**后端 workorder 域 8 新建＋全局 1 新建**（`.../com/meiyun/store/workorder/`）：

- `WorkOrder.java`（83 行）：主表 wo_no(32) uk、store_code(16) idx、status(16) idx、type(16)、priority(16)、title/description、assignee(64)、deadline、created_at（@PrePersist）。
- `WorkOrderNote.java`（39 行）：append-only 时间线，wo_id idx、note_by(64)、content(255)、note_at。
- `WorkOrderRepository.java`（18 行，Jpa＋JpaSpecificationExecutor＋maxSeqOfDay `substring(wo_no from 13)`）、`WorkOrderNoteRepository.java`（8 行）。
- `WoNoGenerator.java`（25 行）：`WO-yyyyMMdd-000001`，String.format %06d，synchronized 防重号。
- `WorkOrderService.java`（293 行）：状态机 PENDING→start→IN_PROGRESS→complete→DONE（终态），PENDING/IN_PROGRESS→escalate→ESCALATED（终态不可逆）；非法迁移中文拦截；deadline 默认 now+4h；DataScope＋DTO＋全动作审计（CREATE/START/COMPLETE/ESCALATE，payload 手工拼合法 JSON 转义）。
- `WorkOrderController.java`（136 行）：端点挂 `/api/stores/work-orders`——list/detail/create/start/complete/escalate/notes；view/create/edit/close 权限＋三守卫。
- `WorkOrderDataInitializer.java`（80 行）：@Order(70) meiyun_seed 门控，6 条全字段种子落 SST01（notes 8 条）。
- 全局 `GlobalExceptionHandler.java`（59 行，`com/meiyun/store/`）：卡1 新增的 @RestControllerAdvice，统一兜底异常→中文响应，替代各控制器散落处理。

**前端 3 文件**：新建 `api/workorder.ts`（69 行）；重写 `stores/workorder.ts`（126/120 增改，真源无回落，KPI pending/inProgress/done/overdue 由真实数组 computed，String(id)/单号 3 位适配）；`WorkOrderView.vue`（16/10 行 script async 适配，template/style 零改）。

### 卡2 `6d421f6`（12 files +1070/-158）M2-06 营业日报切真

**后端 daily 域 9 新建**：

- `DailyReport.java`：主表 dr_no（**一日一份无序号**）、date uk_dr_date、store_code idx、status(16)、footfall/orders/services/inventory_alerts、hourly（12 时点 10:00~21:00，IntListJsonConverter）、exceptions、note、submitted_by/submitted_at、created_at。
- `DailyTodo.java`：todo 行，dr_id idx、content、kind（TASK/CUSTOMER/ISSUE）、done、urgent。
- `DailyReportRepository.java`、`DailyTodoRepository.java`；`TimelineJsonConverter.java`、`IntListJsonConverter.java`（JSON 列转换器）。
- `DailyService.java`：DRAFT→submit→SUBMITTED 终态锁定；setHourly 时 footfall＝hourly 求和；DataScope＋审计 DAILY_REPORT（SUBMIT）。
- `DailyController.java`：挂 `/api/stores/daily-reports`——list/get/today、fields、hourly、todos 增删改；daily:view/edit/submit。
- `DailyDataInitializer.java`：@Order(71) seed 门控，启动幂等保证「今日 DRAFT」存在＋昨日 SUBMITTED，种子 2 主单/6 todo。

**前端 3 文件**：新建 `api/daily.ts`；重写 `stores/daily.ts`（ensureToday 改同步——从已加载列表 find(date===today)，未命中返本地瞬态空壳不写库；正常流程因后端幂等保今日＋GET /today 兜底恒命中）；DailyView onMounted await，template 零改。

### 卡3 `f0a5a28`（8 files +559/-39）M2-07 员工绩效切真

**后端 performance 域 5 新建**：

- `PerfStaff.java`：name、role（CONSULTANT/DOCTOR/BEAUTICIAN）、title、avatar_letter、target_fen/actual_fen（存分）、orders、commission_rate、status（ON_DUTY/LEAVE/PROBATION）、joined_at、period（THIS_MONTH/LAST_MONTH 月份列）、trend（近 6 月 JSON）。
- `PerfStaffRepository.java`（JpaSpec，period/role 过滤）。
- `PerformanceService.java`：updateTarget 校验＋审计 PERFORMANCE（UPDATE_TARGET）；金额分↔元。
- `PerformanceController.java`：挂 `/api/stores/perf-staff`——list?period&role、get、/{id}/target；performance:view/edit。
- `PerformanceDataInitializer.java`：@Order(72) seed 门控，本月＋上月两组共 14 行（每组 7 员工），trend 近 6 月。

**前端 3 文件**：新建 `api/performance.ts`；重写 `stores/performance.ts`（period 作 load 参数、切换 CSelect 触发 reload；**simulateCommission 保持纯前端**按 COMMISSION_TIERS 6/8/10/12%（门槛 0/80000/150000/250000）试算不落库；trendLabels 由 period 动态生成 6 个「M月」替代 View 硬编码）；PerformanceView script 引用替换，template/style 零改。

### 卡4 `547a6dd`（8 files +696/-122）M2-20 经营周报切真

**后端 weekly 域 5 新建**：

- `WeeklyReport.java`：week_no（**ISO-8601 周年 `yyyy-Www`** uk）、start_date/end_date、revenue_fen/prev_revenue_fen、footfall/orders/new_customers、repurchase_rate、highlights/issues/next_week_plan（JSON）、status、submitted_by/submitted_at。
- `WeeklyReportRepository.java`。
- `WeeklyService.java`：**WeekFields.ISO**（weekBasedYear/weekOfWeekBody，周一为周首）统一周号，修正 mock createWeekly 非 ISO 几何冲突；DRAFT→submit→SUBMITTED；create 取最新周 startDate+7 派生、prevRevenue 取上一张；审计 WEEKLY_REPORT（SAVE/SUBMIT）。
- `WeeklyController.java`：挂 `/api/stores/weekly-reports`——list/get/create/save/submit，统一 weekly:submit。
- `WeeklyDataInitializer.java`：@Order(73) seed 门控，6 张 W29~W34（最新 2026-W34），create 下一张为 2026-W35（2026-08-24~08-30）。

**前端 3 文件**：新建 `api/weekly.ts`；重写 `stores/weekly.ts`（createWeekly async，POST 返回新对象）；WeeklyView doCreateWeekly/save/submit await，template/style 零改。

### 卡5 `540ea7f`（13 files +973/-191）M2-10 巡店检查切真

**后端 inspection 域 10 新建**：

- `Inspection.java`：主表 ins_no uk、store_code idx（数据域列）、inspected_at、type(16)（ENV/SERVICE/COMPLIANCE）、total_score、issue_count、status、inspector、created/completed_at。
- `InspectionItem.java`：ins_id idx、name、score(0-10 clamp)、note；总分＝Σscore/(n*10)*100；item score<7 派生一项 issue。
- `RectifyIssue.java`：ins_id idx、desc、owner、status（OPEN/DOING/DONE）、due_at(+7d)、has_photo。
- 各 Repository（Inspection maxSeqOfDay `substring(ins_no from 13)`、Item、Rectify）。
- `InsNoGenerator.java`：`INS-yyyyMMdd-000001` synchronized。
- `InspectionService.java`：无 issue create 即 DONE，有 issue PENDING；assignIssue（OPEN→DOING、单据 PENDING→IN_PROGRESS）；全 issue DONE→单据 DONE；DataScope＋审计 INSPECTION（CREATE/ASSIGN/COMPLETE）。
- `InspectionController.java`：挂 `/api/stores/inspections`——list/get/create、issues/assign、issues/complete；inspection:view/create/edit。
- `InspectionDataInitializer.java`：@Order(74) seed 门控，**跨三店 6 单**（SST01 4/SST02 1/SST03 1），item 30 条、issue 5 条；store 展示名 adapt 映射。

**前端 3 文件**：新建 `api/inspection.ts`；重写 `stores/inspection.ts`（KPI monthCount/avgScore/待整改/overdue；ownerOptions 4 人硬编码落中文名）；InspectionView script async，template/style 零改。

### 卡6 `872ea89`（9 files +682/-94）M2-16 拓客活动切真

**后端 acquisition 域 6 新建**：

- `AcquisitionCampaign.java`：aq_no uk、store_code idx、name、type(16)（TRIAL/GROUP/REFERRAL）、exposure/arrival/deal、budget_fen/spent_fen（存分）、status（DRAFT/ONGOING/ENDED）、start_date/end_date、owner、channel。
- `AcquisitionRepository.java`（maxSeqOfDay `substring(aq_no from 13)`）。
- `AqNoGenerator.java`：`AQ-yyyyMMdd-000001`，**全局日序号**（maxSeqOfDay 不分店），SST02 建单得全局序号属设计定案。
- `AcquisitionService.java`：create→DRAFT；launch（DRAFT→ONGOING）；end（ONGOING→ENDED、endDate=now）；非法迁移中文拦截；审计 ACQUISITION（CREATE/LAUNCH/END）。
- `AcquisitionController.java`：挂 `/api/stores/acquisitions`，FAB 与全部写按钮统一 acquisition:edit。
- `AcquisitionDataInitializer.java`：@Order(75) seed 门控，7 条种子（3 ONGOING/3 ENDED/1 DRAFT）；CHANNEL_OPTS 小红书/抖音/大众点评/美团/私域社群/微信朋友圈，多选「+」拼接；create 默认 start 今日/end+30、owner 当前用户、channel「私域社群」。

**前端 3 文件**：新建 `api/acquisition.ts`；重写 `stores/acquisition.ts`（KPI ongoing/monthLeads/monthDeals/avgConversion/conversionRate；修复 end() 成功分支误返 false）；AcquisitionView submitForm async/await，template/style 零改。

### 卡7 `7111d37`（11 files +746/-109）M2-17 沉睡唤醒切真

**后端 reactivate 域 8 新建**：

- `ReactivateCustomer.java`：主表 rc_no uk、store_code idx、name、level、phone、last_visit_days、card_balance_fen（存分）、tier(16)（T30/T60/T90，tierOf：≥90 T90/≥60 T60/否则 T30）、status（PENDING/ASSIGNED/VISITED/RECOVERED）、assignee、channel(16)（PHONE/WECHAT/SMS）、next_follow_at。
- `ReactivateLog.java`：rc_id idx append-only、log_by、content、log_at；`ReactivateLogRepository.java`。
- `ReactivateRepository.java`（maxSeqOfDay `substring(rc_no from 13)`）。
- `RcNoGenerator.java`：`RC-yyyyMMdd-000001` synchronized。
- `ReactivateService.java`：assign（status<ASSIGNED 才置 ASSIGNED、nextFollowAt=now+2、log「指派给…」）；logVisit（recovered=true→RECOVERED、lastVisitDays=0；否则→VISITED；**单向不回退**、非法跳转中文 422）；审计 REACTIVATE（ASSIGN/LOG_VISIT）。
- `ReactivateController.java`：挂 `/api/stores/reactivate`，统一 reactivate:view/edit。
- `ReactivateDataInitializer.java`：@Order(76) seed 门控，8 客户/8 logs；assigneeOptions 3 人（林微默认/白桥/苏晴）硬编码。

**前端 3 文件**：新建 `api/reactivate.ts`；重写 `stores/reactivate.ts`（KPI total/t30/t90/monthRecovered）；ReactivateView script async，template/style 零改。

## 4. 三轨真验（全绿，11:39 终核留证）

1. **构建轨**：七卡后端 `mvn -pl store-service -am package/compile` 通过（0 编译错；工具链 mvn `/Users/huluobo/.workbuddy/binaries/maven/apache-maven-3.9.16/bin/mvn`、JAVA_HOME jdk-17.0.19）；七卡前端 `pnpm build`（vue-tsc --noEmit && vite build）EXIT=0。
2. **PG/建表轨（meiyun_seed，容器 meiyun-pg，宿主 5433）**：12 张业务表由 ddl-auto 自动建成，uk/idx/列核对一致。种子计数实测：work_order=**6**/work_order_note=**8**、daily_report=**2**/daily_todo=**6**、perf_staff=**14**、weekly_report=**6**、inspection=**6**/inspection_item=**30**/rectify_issue=**5**、acquisition_campaign=**7**、reactivate=**8**/reactivate_log=**8**。
3. **curl/HTTP 轨（seed 网关 https:18443，SE001 店长 SST01）**：登录取 token；七页列表种子数核对、字段 fen→元转换正确；逐页造数走完各状态机全链——工单 create→start→complete 与 escalate 双终态、日报 submit 锁定、绩效 updateTarget、周报 create(W35)→save→submit、巡检 create（无 issue 即 DONE/有 issue PENDING）→assignIssue→completeIssue、拓客 create(DRAFT)→launch→end、唤醒 assign→logVisit→recover；非法迁移/重复动作中文拦截、空参/负向 400、detail 越权 404、列表越权空、写越权中文 400、无 token 401 均验证；单号全局唯一约束不撞号。
4. **审计哈希链轨**：audit_log append-only，七 bizType 记录合法 JSON payload、中文动作、哈希链断链 0；失败路径有 audit_outbox 兜底。批末基线 **audit_log=394/max id=482**（各卡测试造数写动作随单据物理删除，业务种子 seed() 不记审计）。
5. **浏览器 UI 轨（Chrome DevTools MCP，seed 前端 18080）**：SE001 真实登录（非注入 token），七页 KPI/列表/pill/弹层与 mock 基线视觉一致；逐页按钮流转、prompt、筛选、tag 正常，console error/warn 0（仅个别 a11y 建议）；写动作后 xhr 全 200、印证 store 写后 await reload；**跨店可见性**巡检页 SE001 只见 SST01 行（4 条），REGION_MGR 登录复验多店行与 KPI；View 模板零改无视觉回归。
6. **双栈/零污染轨**：seed 栈（meiyun-seed-store/meiyun-seed-frontend/meiyun-seed-gateway）换载 healthy；**core 生产库经实测连七域及 B80 的业务表都不存在**（生产 meiyun-store 容器未换载 B80/B81 代码），生产零表零数据、零污染。
7. **提交轨**：49c2d04/e4f059d/6d421f6/f0a5a28/547a6dd/540ea7f/872ea89/7111d37 逐一 commit 紧跟 push，HEAD=7111d37 与 origin/main 一致。

## 5. 如实说明

- **台账总数 ±1 勾稽差（沿用历史口径，不擅自校准）**：台账标注总数 **166**，但 ✅118＋🔧1＋⬜46＝**165**，存在 ±1 差。该差早在 B80 DESIGN 即存在（当时 109＋1＋55＝165 而标注 166），系历史台账口径漂移。本批按 DESIGN §7 不擅改总数、不重排历史，仅在此及 dashboard/history 注明**沿用 166 标注、实算 165**；是否校准总数口径（改标注为 165，或排查缺口行）**请用户拍板**。
- **3 页事实翻转未单独跃迁**：房间床位、设备仪器、异常中心三页经卡0 三轨核实事实已真，本批仅作台账口径订正（计入 M2 13/13），不另起新建行、不单独数字跃迁；M2 七页数字跃迁全部归本批七卡。
- **卡6 种子误伤修复经过**：卡6 真验期间上拍清理曾误删业务种子第 7 条 DRAFT「新年焕颜体验价（策划中）」，经识别后以 restore-seed-draft.sql 单事务（ON_ERROR_STOP=1＋前后置 DO 断言）补回，批末基线恢复正确 7 种子；详见 01-dashboard 域⑦及哨兵 09:28 心跳。
- **未做 BOM/库存联动（与 L103 口径一致）**：本批七页不涉及一键联动；日报/周报数字为手工填报、不自动从交易/工单归集；绩效 actual/orders/trend 为月度快照种子、不实时聚合成交；指派人/责任人均落中文名字符串、不绑 org 员工工号；拓客 ROI 深度归因/凭证上传/唤醒短信真实下发均未做（DESIGN §4 backlog 化）。故 04-backlog **L103 一字不动、不勾销**。
- **仅 seed 栈部署**：本批七卡代码只换载 seed 栈验证，生产 store-service 未换载；core 库无对应表，属本阶段（演示/近线）既定范围，生产发布待用户指令。

**测试数据还原（铁律：测试数据必须还原）**：各卡造数测试单据验毕按单号/门店在单事务内物理 DELETE（主子表＋logs/notes/items），操作前 CSV＋pg_dump 备份留 scripts/backup-b81-*/（git 忽略不入库），恢复各域种子基线（计数见 §4 轨2）；审计 append-only 留痕不删。

## 6. 进度落账

**完成度数字跃迁**：✅ **112→118**/标注总数 166＝约**71%**、🔧 **1** 不变、⬜ **52→46**（约28%）；**域⑦ 组织与权限 14✅0🔧10⬜ → 20✅0🔧4⬜；域⑧ 数字完全不动**。新增完成模块 **7 个**（M2-08 服务工单 /m2-workorder、M2-06 营业日报 /m2-daily、M2-07 员工绩效 /m2-performance、M2-20 经营周报 /m2-weekly、M2-10 巡店检查 /m2-inspection、M2-16 拓客活动 /m2-acquisition、M2-17 沉睡唤醒 /m2-reactivate），M2 13 页进度 6/13→**13/13 全部真实闭环**。

- **04-backlog**：七页无独立登记行；L104 M2 总登行更新为「B81 已闭合（2026-09-21）M2 13/13 全部真实闭环」并标 ✅ B81；**L103 一字不动**。
- **03-timeline**：补登卡1~卡5 批末补登行（旧卡6 登记作时点事实保留不抹，沿 B61 先例，数字不逐行跃迁）、卡7 行、批总（卡8 台账）数字跃迁行（✅112→118、⬜52→46、域⑦ 20✅/4⬜、M2 13/13、±1 勾稽沿用不校准）。
- **02-modules**：M2 聚合行翻 ✅「M2 13/13 全部真实闭环」（七 commit 哈希全列）；拓客行后新增工单/日报/绩效/周报/巡检/唤醒 6 独立明细行（M2 编号 5、13~10/13）。
- **01-dashboard**：数字表 ✅118/约71%、⬜46/约28%（🔧1、合计166 不变）；批总 changelog 条目（七哈希全列＋±1 勾稽）；两个口径段（约71%、M2 13/13、十页路径哈希）；域⑦行 20✅/4⬜ 计数＋B81 批总六页完整状态机叙事。
- **00-history**：顶部新增 B81 批总倒序简报（七卡哈希/状态机/files、3 事实翻转页、数字跃迁、±1 勾稽、DELIVERY＋哨兵 DORMANT），原卡6 简报及历史条目下沉保留、零丢失。
- 索引＋五分册＋本 DELIVERY＋哨兵同一 commit 原子提交（docs/ 被 .gitignore 忽略，git add -f 强制纳入），提交后通读勾稽（118/46、域⑦ 20/4、M2 13/13 各处一致）。

## 7. 下一批

- **M2 门店运营平台 13/13 全部真实闭环，M2 阶段收官**。按用户总路线「按 M1、M2、M3 的顺序」，下一阶段为 **M3**（具体范围/页面待台账与用户确认后开工）。
- 可回填的 backlog 纵深：L103 BOM 缺料→申购单一键联动、人员主数据工号绑定、绩效真实成交聚合与提成单、日报/周报数字自动归集、严格双签、拓客 ROI 归因与短信真实下发——均需用户指令后开工。
- **待用户拍板项**：台账总数 166 vs 实算 165 的 ±1 勾稽差是否校准。
- 无用户新指令前不擅自提前开工；哨兵批末置 DORMANT。
