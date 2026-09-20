# DESIGN · P5-B81 · M2 余页七页切真（卡0 定案）

> 日期：2026-09-21（机器真实 date＝2026-09-21 03:55 Monday，已实测）
> 状态：卡0 定案（只读侦察完成，待卡1 起逐页施工）
> 铁律：先查再写（铁律0）；API 契约/网关零改（铁律1）；JPA ddl-auto=update 自动建表、零 Flyway（铁律2，仿 B80/B49 先例）；枚举中文外露（铁律3）；数组返回非 Page（铁律4）；前端诚实接真、模板/样式零改（铁律5、铁律 -1-B）；写接口四件套＋append-only 审计（铁律6）；三轨真验（铁律7）；一卡一 commit 紧跟 push（铁律8）
> 数字约束：台账口径 ✅111 / 🔧1 / ⬜53（总数标注 166，存在 ±1 勾稽差，见 §7）三个功能数字卡0-8 一律不动，仅卡9 批末按 B80/B54 先例跃迁

---

## §1 现状与缺口

### 1.1 本批切什么 —— 「页面 × 数据源 × 后端端点 × 状态」链路映射表

| 编号 | 页面 | 路由 | View | 当前数据源（活规格） | 目标后端资源（端点基址） | 状态 |
|---|---|---|---|---|---|---|
| M2-08 | 服务工单 | `/m2-workorder` | WorkOrderView.vue（394） | mock `stores/workorder.ts`（225，6 种子） | `/api/stores/work-orders` | 待新建 |
| M2-06 | 门店日报 | `/m2-daily` | DailyView.vue（315） | mock `stores/daily.ts`（258，2 条） | `/api/stores/daily-reports` | 待新建 |
| M2-07 | 员工绩效 | `/m2-performance` | PerformanceView.vue（365） | mock `stores/performance.ts`（136，7 员工） | `/api/stores/perf-staff` | 待新建 |
| M2-20 | 经营周报 | `/m2-weekly` | WeeklyView.vue（325） | mock `stores/weekly.ts`（188，6 条） | `/api/stores/weekly-reports` | 待新建 |
| M2-10 | 巡店检查 | `/m2-inspection` | InspectionView.vue（410） | mock `stores/inspection.ts`（313，6 种子） | `/api/stores/inspections` | 待新建 |
| M2-16 | 拓客活动 | `/m2-acquisition` | AcquisitionView.vue（373） | mock `stores/acquisition.ts`（198，7 种子） | `/api/stores/acquisitions` | 待新建 |
| M2-17 | 沉睡唤醒 | `/m2-reactivate` | ReactivateView.vue（364） | mock `stores/reactivate.ts`（206，8 种子） | `/api/stores/reactivates` | 待新建 |

七页均为「**既有页面换数据源**」：View、路由（router/index.ts）、菜单（nav.ts L195-207）全部已存在，**零新页面、零菜单、零路由**。

**事实翻转三页（本批不再施工，仅批末台账订正口径）**：

| 编号 | 页面 | 三轨核实结论 |
|---|---|---|
| M2-04 | 房间床位 | 已真实：room 域后端齐全＋前端 api/View 接真 |
| M2-05 | 设备仪器 | 已真实：equipment 域后端齐全＋前端接真 |
| M2-09 | 异常中心 | 已真实：B63 卡2 ExceptionCenter 三源只读归集已闭合 |

故 M2 台账登记 3/13，**事实已为 6/13**；本批新建 7 页后批末到 **13/13**。

### 1.2 当前状态

- HEAD `22255c4`（B80 已闭、已 push），工作区 clean（`git status --short` 0 行，已实测）；M2 台账 3/13。
- 七 View 均在；七 mock store 即活规格（铁律 -1-A：mock 与 README 冲突以 mock 为准），本 DESIGN 字段/状态机/种子/KPI 逐字对齐 mock。
- 后端：七域**零 Java 文件**（store-service 现有 13 业务包不含此七域）；前端：七 api 文件**零存在**。

### 1.3 已有基础设施（直接复用，不新建）

| 设施 | 位置 | 复用结论 |
|---|---|---|
| 实体母本 | `store/requisition/Requisition.java`、`store/procurement/PurchaseOrder.java` | 注解/索引/时间列/`@PrePersist` 仿抄 |
| 单号母本 | `RqNoGenerator.java`（synchronized＋maxSeqOfDay，substring from 13） | 五域日序号仿抄 |
| 数据域 | `com.meiyun.security.DataScope.storeSpec(...)`＋resolve 守卫 | Specification 基座＋三守卫仿抄 |
| 权限码 | auth.ts＋org PermissionMatrix.java（gen_perm_matrix.mjs 生成，勿手改） | 七页全部权限码已注册，**零新增**（见 §2.8） |
| 审计 | `ConsumableAuditRecorder.record(bizType,txnNo,actor,action,payload)`＋audit_outbox 兜底 | 通用 recorder；audit-service **无 bizType 白名单**（任意非空、≤32 字符，已实测 AuditService L47-51），七域各命名 |
| 网关 | Go `/api/stores` → store-service:8085 | **零改**，端点挂既有子树 |
| 建表 | `ddl-auto: update` | Hibernate 自动建，**零 Flyway** |
| 前端母本 | `api/requisition.ts`、重写后 `stores/requisition.ts`、RequisitionView await 化 | api DTO/adapt/async 范式照抄 |

### 1.4 缺口清单

| 缺口 | 说明 | 收口卡 |
|---|---|---|
| G1 | 服务工单后端＋前端接真全缺 | 卡1 |
| G2 | 门店日报后端＋前端接真全缺（含 ensureToday 难点） | 卡2 |
| G3 | 员工绩效后端＋前端接真全缺 | 卡3 |
| G4 | 经营周报后端＋前端接真全缺（含 ISO 周号修正） | 卡4 |
| G5 | 巡店检查后端＋前端接真全缺（含跨店） | 卡5 |
| G6 | 拓客活动后端＋前端接真全缺 | 卡6 |
| G7 | 沉睡唤醒后端＋前端接真全缺 | 卡7 |
| G8 | 批末台账/哨兵/DELIVERY 回写 | 卡9（卡8 视篇幅为机动收尾） |

---

## §2 口径定义

### 2.1 M2-08 服务工单（逐字对齐 mock）

```
PENDING 待服务 ──start──▶ IN_PROGRESS 进行中 ──complete──▶ DONE 已完成（终态）
   PENDING/IN_PROGRESS ──escalate──▶ ESCALATED 已升级
```

- 类型 type：REPAIR 报修 / INSPECTION 巡检 / CUSTOMER 客诉 / CONSULT 咨询。
- 优先级 priority：HIGH / MEDIUM（默认）/ LOW。
- 状态外露：待服务 / 进行中 / 已完成 / 已升级；TYPE_ICON：tool/scan/customer/message。
- 权限：list/detail `workorder:view`；create `workorder:create`；start/escalate `workorder:edit`；complete `workorder:close`。
- create 默认 deadline＝now+4h；assignee 缺省「待分配」；首条 note「创建工单」。
- notes（append-only）：create「创建工单」、start「开始处理」、complete「完成：{note}」（View 固定传「已按要求处理完毕」）、escalate「升级：{reason}」（View 固定「问题复杂，需店长介入」）。
- KPI（前端 computed）：pending/inProgress/done/overdue（非 DONE 且 deadline<now）。

### 2.2 M2-06 门店日报（逐字对齐 mock）

```
DRAFT 草稿 ──submit──▶ SUBMITTED 已提交（终态，锁定留痕）
```

- 一天一份；字段：date(YYYY-MM-DD)、footfall 客流、orders 成交、services 服务完成、inventoryAlerts 库存预警、hourly[12]（营业 10:00~21:00 共 12 时点）、todos[]、exceptions 异常说明、note 备注、submittedBy/submittedAt、timeline[]。
- todo：id、content、kind（TASK 待办事务/CUSTOMER 客户跟进/ISSUE 异常处理）、done、urgent。
- 权限：读 `daily:view`；保存/分时/待办 `daily:edit`；submit `daily:submit`。
- setHourly 时 footfall＝hourly 求和（mock 口径）。
- KPI：openTodos（今日未完成待办）。

**ensureToday 难点定案（computed 不可 async）**：

1. 后端 DataInitializer 启动即幂等保证「**今日 DRAFT**」存在（昨日 SUBMITTED＋今日 DRAFT 两条基线）。
2. store `seed()` 内部 `await GET /daily-reports/today` 拉今日；后端 today 不存在则服务端兜底建 DRAFT（双保险）。
3. `ensureToday()` **改为同步**：从已加载列表 `find(date===today)`；命中即返回，未命中返回一个**本地瞬态空壳对象**（不写库、不入列表，仅保 computed 不崩）；正常流程因 1/2 已恒命中，空壳不出现。
4. View 的 `const report = computed(() => store.ensureToday())` **template/结构零改**，仅 script 内 onMounted 已 async（seed 本就被 onMounted 调）。

### 2.3 M2-07 员工绩效（逐字对齐 mock）

- 字段：name、role（CONSULTANT 咨询师/DOCTOR 医生/BEAUTICIAN 美容师）、title、avatarLetter、target 目标（元）、actual 实际（元）、orders 成交单数、commissionRate 提成比例 0~1、status（ON_DUTY 在岗/LEAVE 休假/PROBATION 试用期）、joinedAt、trend[6] 近 6 月业绩。
- 提成阶梯 COMMISSION_TIERS（6%/8%/10%/12%，门槛 0/80000/150000/250000）。
- 权限：读 `performance:view`；updateTarget `performance:edit`。
- **period（THIS_MONTH/LAST_MONTH）作 load 参数**：`GET /perf-staff?period=`，切换 CSelect 触发 reload；View 的 CSelect 已绑 `store.period`，**UI 零改**。
- **simulateCommission 不落后端**：纯前端按 COMMISSION_TIERS 试算（mock 本就纯本地），保持 View 同步返回 `{label,commission,delta}`，无网络契约。
- KPI（前端 computed）：totalActual（万元展示）、totalTarget、achievement、topStaff、onDuty、completion、commission。
- **近 6 月走势标签疑点定案**：View 硬编码 `['3月'..'8月']`。改为 store 暴露响应式 `trendLabels`（由当前 period 基线动态生成 6 个「M月」），View template 仅把常量引用换为 store 导出（属 script 数据引用替换，非样式改动）。

### 2.4 M2-20 经营周报（逐字对齐 mock，修正 weekNo）

- 字段：weekNo、startDate、endDate、revenue、prevRevenue（环比基准）、footfall、orders、newCustomers、repurchaseRate、highlights、issues、nextWeekPlan、status、submittedBy/submittedAt。
- 状态：DRAFT 草稿 / SUBMITTED 已提交。权限：新建/保存/提交**统一 `weekly:submit`**（无独立码，照 mock）。
- **ISO 周号修正（核心疑点）**：mock 种子 weekNo＝`2026-W34`（ISO 周年），但 `createWeekly()` 生成 `W202608-{ceil(day/7)}` 非 ISO、与种子几何不一致。**后端统一 ISO-8601 周年**：用 `java.time.temporal.WeekFields.ISO`（weekBasedYear/weekOfWeekBody）计算，周一为周首、周日为周末；create 取最新一周 startDate＋7 派生，weekNo＝`%04d-W%02d`；种子最新 2026-W34，create 下一张＝**2026-W35（2026-08-24~08-30）**，prevRevenue＝上一张 revenue。
- createWeekly 成功后 View 取 sorted[0]；改为 async（POST 返回新对象），View `const ok = await store.createWeekly()` 后续逻辑不变。
- KPI：latest、wowRevenue（(revenue-prevRevenue)/prevRevenue）。

### 2.5 M2-10 巡店检查（逐字对齐 mock，跨店）

- 类型 type：ENV 环境 / SERVICE 服务 / COMPLIANCE 合规（icon sun/customer/shield）。
- Inspection：no、store（展示名）、storeCode（数据域，新增）、inspectedAt、type、totalScore（百分制）、issueCount、status、inspector、items[]、issues[]、createdAt/completedAt。
- item：name、score（0-10，clamp）、note；总分＝Σscore/(n*10)*100；**item score<7 记一项 issue**。
- issue：id、desc、owner、status（OPEN 待整改/DOING 整改中/DONE 已完成）、dueAt（+7 天）、hasPhoto。
- 状态：无 issue→create 即 DONE；有 issue→PENDING；assignIssue（OPEN→DOING，单据 PENDING→IN_PROGRESS）；全部 issue DONE→单据 DONE。
- 权限：读 `inspection:view`；create `inspection:create`；assignIssue/completeIssue `inspection:edit`。
- KPI：monthCount、avgScore、待整改、overdue（非 DONE 且有 issue 逾期）。
- **跨店定案**：实体 storeCode 为数据域列，store 展示名 adapt 映射。6 种子照 mock 跨三店，storeCode 按下表落；SE001（仅 SST01）登录只可见本店行，**全量 6 条经 PG 轨核对，Chrome 多店可见性以 REGION_MGR 账号复验**（见 §6）。
- ownerOptions 4 人（李娜前台主管默认/吴桐运营/周敏美容师/张磊设备主管）为前端硬编码，落 owner 中文名字符串。

### 2.6 M2-16 拓客活动（逐字对齐 mock）

- 类型 type：TRIAL 体验价 / GROUP 拼团 / REFERRAL 老带新（icon marketing/customer/user-check）。
- 字段：no、name、type、exposure 曝光、arrival 到店、deal 成交、budget、spent、status（ONGOING/ENDED/DRAFT）、startDate、endDate、owner、channel。
- 状态机：create→DRAFT；launch（DRAFT→ONGOING）；end（ONGOING→ENDED，endDate=now）。
- 权限：FAB 与全部写按钮**统一 `acquisition:edit`**（无 create 码，照 mock）。
- CHANNEL_OPTS＝小红书/抖音/大众点评/美团/私域社群/微信朋友圈；多选以「+」拼接存 channel 字符串。
- owner 取 ALL_STAFF 中文名字符串；create 默认 start 今日、end+30 天、owner 缺省当前用户、channel 缺省「私域社群」。
- KPI：ongoing、monthLeads（Σarrival）、monthDeals（Σdeal）、avgConversion、conversionRate（deal/arrival）。

### 2.7 M2-17 沉睡唤醒（逐字对齐 mock）

- tier：T30 30 天沉睡 / T60 60 天沉睡 / T90 90 天+ 深度沉睡（tierOf：≥90 T90，≥60 T60，否则 T30）。
- 状态：PENDING 待唤醒 / ASSIGNED 已指派 / VISITED 已回访 / RECOVERED 已挽回。
- channel：PHONE 电话 / WECHAT 企业微信 / SMS 短信。
- 字段：name、level、phone、lastVisitDays、cardBalance、tier、status、assignee、channel、nextFollowAt、logs[]。
- assign：assignee/channel；status 仅当 <ASSIGNED 才置 ASSIGNED；nextFollowAt=now+2 天；log「指派给 {assignee}（{channel中文}）」。
- logVisit：recovered=true→status RECOVERED、lastVisitDays=0、log「客户已挽回」；否则→VISITED、log「回访记录」（RECOVERED 不回退）。
- 权限：统一 `reactivate:edit`。assigneeOptions 3 人（林微资深咨询师默认/白桥私域运营/苏晴店长）硬编码，落中文名字符串。
- KPI：total、t30、t90、monthRecovered（本月 RECOVERED，按「客户已挽回」log 时间）。

### 2.8 权限码（零新增，已逐码实测 auth.ts）

- STORE_MGR（L90-95）、REGION_MGR（L149-154）均持：`workorder:edit/create/close`、`daily:edit/submit`、`performance:edit`、`weekly:submit`、`inspection:create/edit`、`acquisition:edit`、`reactivate:edit`；七页 view 码 L38-43 已注。
- 无 403 风险；PermissionMatrix 由生成器产出，**不改 Java**。

### 2.9 单号定案（五域日序号，统一 3 字符前缀几何）

| 域 | 存储格式 | 序号 SQL | 展示 |
|---|---|---|---|
| 工单 | `WO-yyyyMMdd-000001` | substring(wo_no from 13) | `WO-yyyyMMdd-001`（6→3 位） |
| 日报 | `DR-yyyyMMdd`（**一日一份，无序号**，uk_dr_date(date) 唯一） | — | 同存储 |
| 巡检 | `INS-yyyyMMdd-000001` | substring(ins_no from 13) | 3 位 |
| 拓客 | `AQ-yyyyMMdd-000001` | substring(aq_no from 13) | 3 位 |
| 唤醒 | `RC-yyyyMMdd-000001` | substring(rc_no from 13) | 3 位 |
| 周报 | `yyyy-Www`（ISO 周年，uk_week_no） | — | 同存储 |
| 绩效 | 无业务单号（仅 id） | — | — |

- Generator 前缀 `"XX-"+day+"-"`、`String.format("%06d",max+1)`、synchronized；>999 时前端回落展示完整 6 位串。
- id：后端 Long，读 DTO id number，前端 String(id)；写按 Long 路径参。

### 2.10 金额 / 时间 / 数据域 / 审计

- 金额：拓客 budget/spent、唤醒 cardBalance、绩效 target/actual/trend 均为**元**（mock 口径整数元）。为统一精度，**金额类列以分存储 `*_fen bigint`**（仿 B80），读输出元、写入参分；绩效展示万元由前端 ÷10000。
- 时间：`java.time.OffsetDateTime`，`@PrePersist` 兜底 createdAt；相对偏移在播种时 `now().minusDays/Hours`。
- 数据域：七实体均含 `store_code(16,nn)`，Service 以 `DataScope.storeSpec("storeCode")` 强叠加；Controller resolveRead/resolveWriteStoreCode 守卫照 B80；detail 越权 404、写越权 400。
- 种子：除巡检跨店外，六域种子全落 **SST01**（保 SE001 可见）；真实建单由 storeContext currentStoreCode 传。
- 审计 bizType：`WORK_ORDER` / `DAILY_REPORT` / `PERFORMANCE` / `WEEKLY_REPORT` / `INSPECTION` / `ACQUISITION` / `REACTIVATE`；txnNo＝单号（绩效用 id）。
- action：CREATE/START/COMPLETE/ESCALATE、SUBMIT、UPDATE_TARGET、SAVE、LAUNCH/END、ASSIGN/LOG_VISIT 等；备注/待办类是否记审计仿 B80（默认不记状态类外的细碎项，逐卡在 Service 明示）。
- payload：手工拼**合法 JSON**（jsonStr 转义 `"`/`\`/换行），走 recorder，失败落 audit_outbox。
- 写接口四件套：入参校验（中文 400）＋状态机前置校验（非法迁移中文拦截）＋幂等防重（uk＋synchronized）＋全动作审计。

---

## §3 不动项

1. 七 View 的 `<template>`/`<style>` 零改（铁律 -1-B）；差异全在 script 数据引用与 store 适配层。
2. 路由、菜单、面包屑、v-perm 键名零改。
3. 网关路由/前缀零改；不新增 internal HTTP（同 JVM 直调）。
4. 权限矩阵零新增；application.yml 零改；零 Flyway、零新增 CSS、零新 npm 依赖。
5. ✅111/🔧1/⬜53 功能计数卡0-8 一律不动。

## §4 不做项（backlog 化）

1. **人员主数据绑定**：七页指派人/责任人均落中文名字符串（mock 口径），不绑 org 员工工号/岗位序列校验；工号绑定、按岗位过滤入 backlog。
2. **绩效真实成交联动**：performance 的 actual/orders/trend 为月度快照种子，不实时聚合 txn 成交单；真实业绩聚合、提成单生成入 backlog。
3. **日报/周报数字自动归集**：客流/成交/服务数为手工填报，不自动从交易/工单汇总，入 backlog。
4. **拓客 ROI 深度归因 / 凭证图片上传 / 唤醒短信真实下发**：不做，入 backlog。
5. 分页（数组返回＋前端 computed）；单号规则改造——均不做。

---

## §5 施工清单（一卡一 feat commit，七卡七 feat）

每卡后端新建扁平包 `backend/store-service/.../com/meiyun/store/<域>/`，前端新建 `api/<域>.ts`、重写 `stores/<域>.ts`、View 仅 script 最小 async 化。

### 卡1 · M2-08 服务工单
- 后端：`WorkOrder`、`WorkOrderNote`、`WorkOrderRepository`（JpaSpec＋maxSeqOfDay from 13）、`WorkOrderNoteRepository`、`WoNoGenerator`、`WorkOrderService`、`WorkOrderController`（`/work-orders`，端点 list/get/create/{start,complete,escalate}/notes）、`WorkOrderDataInitializer`（@Order70，6 种子）。
- 前端：`api/workorder.ts`、重写 `stores/workorder.ts`；WorkOrderView doCreate/doStart/doComplete/doEscalate 加 async/await（照 B80）。

### 卡2 · M2-06 门店日报
- 后端：`DailyReport`、`DailyTodo`、`DailyTimeline`（或 timeline JSON 列）、各 Repository、`DailyService`、`DailyController`（`/daily-reports`：list/get/today、`/fields`、`/hourly`、`/todos` 增删改）、`DailyDataInitializer`（@Order71，昨日 SUBMITTED＋今日 DRAFT，并启动幂等保今日）。
- 前端：`api/daily.ts`、重写 `stores/daily.ts`（ensureToday 同步化方案 §2.2）；DailyView onMounted await，template 零改。

### 卡3 · M2-07 员工绩效
- 后端：`PerfStaff`（含 period 月份列、trend JSON 列）、Repository（JpaSpec）、`PerformanceService`、`PerformanceController`（`/perf-staff`：list?period&role、get、`/{id}/target`）、`PerformanceDataInitializer`（@Order72，本月＋上月两组 7 员工，trend 近 6 月）。
- 前端：`api/performance.ts`、重写 `stores/performance.ts`（period load、simulate 留前端、trendLabels 动态）；PerformanceView script 引用替换。

### 卡4 · M2-20 经营周报
- 后端：`WeeklyReport`（weekNo ISO）、Repository、`WeeklyService`（WeekFields.ISO）、`WeeklyController`（`/weekly-reports`：list/get、create、`/{id}` save、`/{id}/submit`）、`WeeklyDataInitializer`（@Order73，W29-W34 六张）。
- 前端：`api/weekly.ts`、重写 `stores/weekly.ts`（createWeekly async）；WeeklyView doCreateWeekly/save/submit await。

### 卡5 · M2-10 巡店检查
- 后端：`Inspection`、`InspectionItem`、`RectifyIssue`、各 Repository（maxSeqOfDay from 13）、`InsNoGenerator`、`InspectionService`、`InspectionController`（`/inspections`：list/get/create、`/issues/{assign,complete}`）、`InspectionDataInitializer`（@Order74，6 种子跨三店，storeCode 映射）。
- 前端：`api/inspection.ts`、重写 `stores/inspection.ts`；InspectionView create/assign/complete await。

### 卡6 · M2-16 拓客活动
- 后端：`AcquisitionCampaign`、Repository（maxSeqOfDay from 13）、`AqNoGenerator`、`AcquisitionService`、`AcquisitionController`（`/acquisitions`：list/get/create、`/{launch,end}`）、`AcquisitionDataInitializer`（@Order75，7 种子）。
- 前端：`api/acquisition.ts`、重写 `stores/acquisition.ts`；AcquisitionView create/launch/end await。

### 卡7 · M2-17 沉睡唤醒
- 后端：`ReactivateCustomer`、`ReactivateLog`、各 Repository（maxSeqOfDay from 13）、`RcNoGenerator`、`ReactivateService`、`ReactivateController`（`/reactivates`：list/get、`/assign`、`/visit`）、`ReactivateDataInitializer`（@Order76，8 种子）。
- 前端：`api/reactivate.ts`、重写 `stores/reactivate.ts`；ReactivateView assign/logVisit await。

### 建表清单（ddl-auto=update 自动建，零 Flyway）

| 域 | 主表（uk / 索引） | 子表 |
|---|---|---|
| 工单 | `work_order` uk_wo_no；idx store/status/type/deadline | `work_order_note`(wo_id) |
| 日报 | `daily_report` uk_dr_date；idx store/status | `daily_todo`(dr_id)、`daily_timeline`(dr_id，或主表 JSON) |
| 绩效 | `perf_staff` uk_pf(period,store_code,name)；idx store/period/role | —（trend JSON 列） |
| 周报 | `weekly_report` uk_week_no；idx store/status/start | — |
| 巡检 | `inspection` uk_ins_no；idx store/status/type | `inspection_item`(ins_id)、`rectify_issue`(ins_id) |
| 拓客 | `acquisition` uk_aq_no；idx store/status/type | — |
| 唤醒 | `reactivate` uk_rc_no；idx store/status/tier | `reactivate_log`(rc_id) |

> 列名避 SQL 保留字：by→note_by/action_by、text→content、at→note_at/action_at。

### 门店名 → storeCode 映射（巡检种子用）

| mock 展示名 | 落库 storeCode | adapt 展示名（映射表，未知码回落显示码） |
|---|---|---|
| 静安旗舰店（mock 4 条） | **SST01** | 上海徐汇店 |
| 徐汇店（mock 1 条） | **SST02** | 上海浦东店 |
| 陆家嘴店（mock 1 条） | **SST03** | 北京国贸店 |

> 说明：mock 虚构店名为演示态，seed 库权威门店＝上海徐汇店/上海浦东店/北京国贸店（已查活库）。实体以 storeCode 为隔离事实，展示名由 adapt 映射；此映射仅用于巡检种子跨店演示数据域，不改动 store 主数据。

### 前端 store 接真约定（七 store 同范式）

- `seed()` 保留旧名，内部改真实 load；保留 View 实际引用的全部方法/常量/计算导出名。
- create 类 POST 后用返回 DTO 组装对象返回（View 取 id）；动作成功后 `await load()` 重拉；**诚实接真、无 mock 回落**（仿 schedule/B80）。
- 适配层消化：String(id)、fen↔元、字段改名、nz 空值、单号 6→3、ISO 周号、trendLabels、门店码→展示名。

---

## §6 三轨真验计划

### 轨1 · curl（seed 栈，https:18443，SE001 / meiyun123）
1. `POST /api/org/auth/login` 取 token。
2. 各域 list 核对基线条数：工单 6 / 日报 2（今日＋昨日）/ 绩效 7（THIS_MONTH）/ 周报 6 / 巡检（SE001 仅见 SST01 4 条）/ 拓客 7 / 唤醒 8；单号存储串、状态、金额元值。
3. 逐页走完整状态机：工单 create→start→complete、escalate；日报 fields→hourly→todo 增删改→submit；绩效 target、period 切换；周报 create(验 W35 ISO)→save→submit；巡检 create（满分即 DONE/有 issue PENDING）→assign→complete；拓客 create→launch→end；唤醒 assign→logVisit(挽回/未挽回)。
4. 负向：空参/非法迁移 400 中文、越权（REGION_MGR/集团账号验数据域，必要时 SE102/SE101）、重复 uk 不撞。

### 轨2 · PostgreSQL（meiyun_seed，容器 meiyun-pg，宿主 5433）
- 七主表＋子表由 Hibernate 建成（`\dt`），列/索引/uk 存在。
- 核对各域种子条数（巡检跨店全量 6 条按 store_code 分组 4/1/1）、金额 _fen、时间戳、notes/logs/items 条数文案。
- 审计核对：audit_log 出现七 bizType，哈希链无断环、payload 合法 JSON；失败时 audit_outbox 兜底。

### 轨3 · Chrome（seed 前端 18080）
- SE001 登录（非注入 token），七页 KPI/列表与 mock 基线视觉一致（截图对照，take_screenshot 以附件为准）。
- 逐页按钮流转、prompt、pill、tag；console error/warn 0；核验 template/style 零改无视觉回归。
- **跨店可见性**：巡检页 SE001 只见 SST01 行；切换 REGION_MGR 登录复验多店行与 KPI。

### 造数还原
- 轨1 测试单据验毕按单号/门店在**单事务内物理 DELETE**（主子表）还原基线；操作前对相关表 `pg_dump` 单表备份到临时文件；审计 append-only 不删（如须核对净增，登记区间）。

### OCR 门禁（铁律12）
- commit 前 `ocr review`；无 CLI 则人工 8 类敏感正则（password/secret/api_key/token、手机号、身份证、银行卡、邮箱、内网 IP/localhost、meiyun123、真实人名）逐文件扫描＋目检替代，汇报注明。

---

## §7 数字影响表

| 项 | 本批影响 | 说明 |
|---|---|---|
| 新页面 | 0 | 七页均既有 |
| 功能计数（✅/🔧/⬜） | 卡0-8 不动 | 卡9 批末按 B80/B54 跃迁：✅111→118、⬜53→46；🔧1 不变 |
| M2 进度 | 6/13→13/13 | 先订正三页事实翻转（3/13→6/13），再登七页 |
| 新业务表 | **约 12 张主/子表** | ddl-auto=update 自动建，零 Flyway，不影响功能计数 |
| 新权限码 | 0 | 七页码已注册 |
| 新网关路由 | 0 | 挂既有 /api/stores 子树 |
| 新 CSS / Flyway / npm 依赖 | 0 | 模板零改 |

> **勾稽疑点（批末处理）**：台账标注总数 166，但 ✔111＋🔧1＋⬜53＝**165**，存在 ±1 差（B80 DESIGN 亦写 109＋1＋55＝166 实为 165）。本批不擅改总数口径，卡9 回写时据实重算并在 DELIVE​RY 注明，必要时请用户拍板。
> 域⑦门店运营：批末七页全 ✅，M2 13/13 闭合。
