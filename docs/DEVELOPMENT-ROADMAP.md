# 美研云门店中台 · 整体开发规划与进度台账（DEVELOPMENT-ROADMAP）

> 本文件是**唯一的整体进度活台账**（铁律 9）。每批交付 commit+push 后、汇报前必须同步更新。
> 状态判定以**真实交付**为据：✅ 必须「真实端点经网关 200 + 前端接真实 API（非 mock）+ 交付文档验证记录」三者齐备；🔧 半成品（后端有端点前端 mock，或前后端均实但无交付文档验证）；⬜ 未开始（mock/占位）；⏸ 暂缓。
> 旧 `DEVELOPMENT_PLAN.md`（2026-09-01）仅作历史参考，不再回写。
>
> **基线**：P5-B24 已交付（2026-09-09，财务边角两卡 + 诊疗主轴三卡：财务四页去 mock/发票 CSV、卡余额时间线/核销双签、咨询草稿接诊、treat-start/treat-done 治疗全链路、我的工作台计数真实化）。
> **最近更新**：2026-09-11（B33 交付：组织树写能力整建两卡——闭合 §⑦ 大表「组织树 🔧 待接线收口」。卡A `c257165` 后端：OrgUnit 扩 leader_name/headcount/remark/inactive_reason 四列（ddl-auto），新增 OrgAdminController 三写端点全挂 org:manage——新建（**仅门店下建部门**，parentCode 非门店中文 400，orgCode 服务端 toUpperCase 归一+唯一校验，orgType 固定「部门」不收前端值，region/store_code 由父门店继承推导）、编辑（**缺省=保持/空串=清空/非空=更新** 三态语义，orgCode/orgType 传了也忽略，parentCode **仅部门可下发**、跨店移动联动改写 store_code+region）、启停（停用 reason trim 非空必填否则中文 400，**启用强制清空 inactive_reason**，同态幂等成功且**不重复写审计**）；DataScope 走 assertManageable，**越权返回中文 404 而非 403**（防编码枚举探测）；ORG 四 action（CREATE/UPDATE 含 toStore/DISABLE/ENABLE）合法 JSON 审计；GET /api/org/tree 补四富化字段，**该端点仅 org:view 且不做 DataScope**（组织目录全局可见为既有设计，隔离由写端点兜底）。卡B `079d4b9` 前端：T1OrgView 去 mock 接真实 API 并收窄写边界（树头「新建部门」、上级下拉仅列 6 门店、类型 disabled、编辑态编码/类型置灰、全程无状态字段、状态只走启停弹窗且「确认停用」按钮原因非空才解锁、启用弹窗无原因字段、后端中文 message 直出、提交期禁用防重复），m1Org store 重写 205 行（/tree 裸根 flatten、三命令 async 且成功后强制回源刷新不做乐观更新、仅部门下发 parentCode），**`<style scoped>` diff 0 行**。整体 72→**73/166=44%**（🔧 5→4；组织树大表已有独立行，本批为 🔧→✅ 状态跃迁不加行，三写端点与前端写边界收窄计入该行纵深，沿用历史计数差口径）。网关 curl 19 项断言（编码大写归一/重复编码 400/非门店父 400/三态编辑/跨店联动 region/停用缺因 400/启用清因/同态幂等审计不增/SE001 越权两条中文 404）+浏览器 6 组断言（写边界收窄/必填校验/小写 d-b33ui→D-B33UI/编辑置灰/跨店移动 SST01→SST06/停用带因→启用清因整块消失）双轨全绿；造数全量还原（删 2 部门+带 max(id) 保护谓词删链尾 ORG 审计 id79-88，精确回 org_unit=11/audit_log=36/max=36/ORG=0，verify `{ok:true,total:36}`），门店/大区/集团层级写能力与兼岗上级路由仍留 Backlog，详见 DELIVERY-P5-B33）。
> 上一批 B32：2026-09-11（客户搜索 outbox「DEAD 事件处置台」两卡——闭合 §④ Backlog「DEAD 事件处置台」。卡A `f02384b` 后端：customer_search_event 扩 DISCARDED 终态+resolve_note/resolved_by/resolved_at（ddl-auto），新增 CustomerSearchEventAdminService 三动作状态机——retry（仅 DEAD→PENDING、rc 清零/last_error 清空、PENDING 幂等，SENT/DISCARDED 中文 400）、replay（PENDING/DEAD 当场同步投递 ES，ES 不可用保持 PENDING 走 RETRY）、discard（note trim≥2 字+处置三字段）；GET /api/customer/search-events（status/customerId/eventId 过滤+固定 event_id 倒序真分页）+/stats 四键；reindex 换权 customer:search:admin；CUSTOMER_SEARCH_EVENT 四 action（MANUAL_RETRY/REPLAY/DISCARD/REINDEX）合法 JSON 审计；权限码仅挂 REGION_MGR；附修 GlobalExceptionHandler 漏接 ResponseStatusException 致中文 400/404 被兜 500、audit_log.biz_type varchar(16)→(32)。卡B `dbf8158` 前端：/search-events 处置台整页（KPI 四宫格点击筛选/筛选条事件ID 正整数校验/左表右详情双栏 1108px+380px、选中粉行/last_error 红块/三按钮按状态机禁用/丢弃 modal 行内校验/写后 list+stats 两连刷新/全量重建入口/v-perm.disable），nav 客情洞察组+路由守卫，SE001 无权限菜单隐藏+直访跳 /no-auth。整体 70→**72/166=43%**（仪表盘净增 2：后端处置闭环+处置台整页 ⬜→✅；大表域②新增「检索事件处置台」1 行 7✅→8✅，reindex/三动作/审计为既有「客情登记（B30 实时写 ES）」行纵深增强只改备注，沿用历史计数差口径）。网关 curl 全矩阵（400 中文/404/401/SE001 403/reindex=100/审计合法 JSON）+浏览器双轨取证（中继 Job 活行为：PENDING 经 RETRY rc 封顶 20 转 DEAD；network 23 个全 200、console 零 error/warn、双截图）；造数外科手术式还原（删 4 事件+setval 归 1+按 biz_type 删 8 行测试审计 id42-49，他类审计完好 max=36），ES reindex 对账治理（ES110 vs PG100 漂移 10 文档）仍留 Backlog，详见 DELIVERY-P5-B32）。
> 上一批 B31：2026-09-11（术后随访 SOP 引擎收口两卡——闭合 B30 明示 Backlog「/followup 与 /sop 整页管理 UI、手工建随访端点」。卡A：POST /api/txn/followup 手工建普通随访（门店强制取 JWT、客户经 customer-service 解析不存在中文 400、planDate≥serviceDate、method 白名单默认 PHONE、sopStage=MANUAL、FOLLOWUP/CREATE 审计）+stats 九键（sopPending/sopOverdue 旧两键不动+pending/todayPending/overdue/done/skipped/avgSatisfaction/adverseCount）+keyword 三列 OR LIKE 真分页；随访工作台 FollowupView 整页接真（CPagination/350ms 防抖/三 tab 角标/四 KPI/超期 warnbar/真实客户检索新建弹层/完成·免回访写动作/v-perm）；流水牌待回访列 sopTodos 走真库、schedulePostOpSop 降 async 空 shim 防双排程；C 端「陈美玲」演示种子保留。卡B：SOP 编排九端点（模板节点增改/启停/删/恢复默认五动作 SOP_TEMPLATE 审计，内置节点可停用可改不可删中文 400、停用不参与新批次历史批次不变；批次分页聚合+summary 五键+一键升级 `{"escalated":N}`），FollowupSopEscalator 抽取供 60s Job 与手动共用（补 escalate 后 save 防标记丢失），FollowupScheduler 改读启用节点；SopManagementView 双 tab（批次执行看板四 KPI/warnbar 一键升级↔infobar 已升级态/节点徽标/模板 checkbox 编排/恢复默认）。整体 68→**70/166=42%**（两整页管理 UI 大表新增 2 行，域③ 6✅→8✅ 仅剩复诊召回远期；端点/九键/五动作为既有 SOP 行纵深增强不加行）。附修 **1 项运行态真实缺陷**：d130dfc（空库 AVG 聚合 getResultStream().findFirst() 遇 null NPE 致 /stats 500→getSingleResult）。网关 curl 全路径+浏览器两页双轨取证（一键升级 warnbar→infobar、console 仅 1 a11y 零 error/warn）；冒烟 3 批 12 随访+模板五动作全量还原（setup-seed-db.sh 全量重建，**如实披露脚本 TRUNCATE 含 audit_log、终态 36 行 CONSUMABLE 播种基线**，节点恢复 id1-4、followup/batch=0、SOP-ESC 通知=0），详见 DELIVERY-P5-B31）。
> 上一批 B30：2026-09-11，近线收口四卡——③ 复购提醒前端接线（RepurchaseView 全链路：客户真实检索/目标项目必填+知情同意硬勾选/待签核统计实时/客户·经办·店长三方签核，🔧→✅）；④ EMR 模板库（emr_template 新域+集团 4 种子 EMT-SEED-001..004+本店建/停用+模板库弹层）+框架异常中文 400+列表真分页/统计后端聚合；② 建档实时写 ES（customer_search_event outbox+10s 中继 Job SENT/RETRY/DEAD+连续失败 3 次熔断 30s 降级 DB+读时合并+显式 mapping）；① 术后随访 SOP 引擎（followup_sop_template/node/batch/followup 四表+treat-done AFTER_COMMIT 幂等排程 1/3/7/30 天节点+60s 超期升级 Job 店长幂等通知免打扰+/followup 端点+工作台两演示项切真摘除）。整体 64→**68/166=41%**（复购为 🔧→✅，SOP/实时写 ES/EMR 模板库为 Backlog 闭合 ⬜→✅）。验证中附修 **2 项运行态真实缺陷**：fa549d5（afterCommit 无活动事务致 SOP 排程静默丢写→REQUIRES_NEW 物理事务）、fc97d00（模板库裸 null 参数致 PG 42P18 查询 500→JPQL cast string）。网关 curl 全路径（含事件 SENT/7 次 RETRY 自愈）+浏览器复购 RP20260911-003194 创建→三方签核与模板库 UI 双轨取证（SE002 咨询师 SELF 数据域隔离正向实证），console 零错；audit id 81–93 共 13 行 append-only 保留；冒烟全量还原（PG 当日造数零残留、customer 基线 100、ES 113→110 三漂移槽位如实记录），详见 DELIVERY-P5-B30）。
> 上一批 B29：预约接待尾工批三卡——① 排队智能候补（arrival_waitlist 新域：WL 单号/手机号锚定会员·散客快照两态、WAITING→NOTIFIED→FULFILLED/CANCELLED 状态机、号源释放同事务 FIFO 递补首位+店长站内信幂等、/queue 第三卡+候补登记弹层+智能候补 KPI）；② 候诊超时自动释放号源（ArrivalAutoReleaseJob 60s 扫描「30min 阈值+10min 宽限」、系统释放 LEFT 同事务递补、手工释放按钮接真、重载状态防重入）；③ 到店核销↔预约/划扣自动勾连（APPOINTMENT 手机号命中当日预约，同事务回写 appt_no/wd_no+预约置已到店+自动建到店号+writeoff_desk_task PENDING，核销详情透出勾连单号）。整体 63→64/166=39%，网关 curl 24 项全绿+浏览器两页取证（audit 314–331），冒烟全量还原，详见 DELIVERY-P5-B29。
> 上一批 B28：预约接待收口批三卡——会员到店核销 /m2-checkin（checkin_record 新域、CI 单号、三方式/三态/四异常码、timeline、手机号掩码）、客情登记十类扩展字段后端化（customer 加 9 列、白名单+过敏互斥、360 有值才显示）、triage 改派时间线（triage_reassign 新表）。62→63/166=38%，详见 DELIVERY-P5-B28。
> 上一批 B27：主轴「接待台/候诊分诊 + EMR 电子病历独立域」——arrival/triage/emr_record 三新表、到店五态队列、分诊建 CP 草稿双向回挂、预约签到自动到店、EMR DRAFT→SIGNED→ARCHIVED→修订全状态机+signEmr/treatDone 联动；工作台两演示项摘除（附修 2 项运行态缺陷），详见 DELIVERY-P5-B27。
> 再上一批 B26：通知多渠道（Inbox/SMS/企微/邮件+60s 扇出退避/死信+SSE+免打扰）+赠金高级规则+外部渠道回传骨架，57→60/166=36%，详见 DELIVERY-P5-B26。

---

## ① 总览仪表盘

### 全量口径（页面 / 功能模块粒度，去重约 166 项）

| 状态 | 数量 | 占比 | 说明 |
|---|---|---|---|
| ✅ 已完成 | **73** | 44% | 三者齐备，真实闭环 |
| 🔧 半成品 | **4** | 2% | 后端有端点/前后端均实但缺验证或前端 mock |
| ⬜ 未开始 | **89** | 54% | mock/占位，含大量远期独立阶段页面 |
| **合计** | **166** | 100% | |

> 注：P5-B24「两线合并全收」5 卡收口（财务四页去 mock/发票 CSV、卡余额时间线/核销双签投影、咨询草稿/接诊、treat-start/treat-done 治疗全链路、我的工作台计数真实化），页面口径 47→**54/166=33%**。工作台 7 项计数切真（审批/待收款/今日预约/方案单四态），5 项无后端域（候诊接待/病历草稿/术后回访/SOP 超期/复诊提醒）保留演示数据并显式标注「（演示）」、不计入真实总数；方案单状态机 PENDING→ACTIVE→PENDING_REVIEW→APPROVED→READY_PAY→PAID→TREATING→DONE 全链路双栈真实打通。EMR 独立域、术后随访 SOP、沉睡唤醒、通用异常中心、异常账务登记、预收合规监控、进项税抵扣、M4Repurchase 三方双签列 Backlog。详见 DELIVERY-P5-B24。
> 口径备注：大表逐行加总与仪表盘合计存在历史计数差（域④ 大表 ✅ 行粒度多计 1、财务/咨询行合并粒度不同），不影响真实交付结论，后续盘点统一校准。B30 仪表盘净增 4 项：复购提醒 🔧→✅，术后随访 SOP/建档实时写 ES/EMR 模板库三项 Backlog ⬜→✅；大表仅术后随访 SOP 新增 1 行（域③ 4✅1🔧1⬜→6✅0🔧1⬜），实时写 ES 与 EMR 模板库/分页/400 作为既有 ✅ 行（客情登记/客户档案、EMR）的纵深增强只改备注不加行，沿用上述历史计数差口径。B31 仪表盘净增 2 项：闭合 B30 明示 Backlog 的两个整页管理 UI——随访工作台（/followup）、SOP 编排（/sop）⬜→✅，域③大表新增 2 行（6✅→8✅）；手工建随访端点、stats 九键、模板节点五动作/批次聚合/一键升级作为既有「术后随访 SOP」行纵深增强只改备注不加行。B32 仪表盘净增 2 项：DEAD 事件处置后端能力（三动作状态机+五端点+四 action 审计）与检索事件处置台整页（/search-events）⬜→✅；大表仅域②新增「检索事件处置台」1 行（7✅→8✅，转化漏斗仍 ⬜），reindex 换权/三动作/审计作为既有「客情登记（B30 建档实时写 ES）」行纵深增强只改备注不加行，沿用历史计数差口径。B33 仪表盘净增 1 项：**组织树 🔧→✅**（后端三写端点+前端去 mock 收窄写边界，🔧 5→4）；组织树在大表域⑦ 已有独立 1 行，本批为状态跃迁**不新增行**，三写端点与写边界收窄均计入该行纵深，沿用历史计数差口径。

### 两个口径，避免「完成度错觉」

- **全量页面口径：44%**——分母含 M1 集团 / M2 门店运营 / M3 客户运营 / A1 AI 中心 / T2 数据 / T4 算力 / C 端移动端等**远期独立阶段**约 **81 个页面**（占 ⬜ 的大头）。这些是与当前门店中台主线并行的后续大阶段，不应算作「主线欠债」。
- **门店中台核心主线口径（交易 + 财务 + 组织权限 + 门店主数据 + 营销 + 客户运营基础 + 诊疗主轴）**：**核心闭环已打通** ✅。退款/退卡/划扣/审批/卡项/资金/对账/月结/成本/提成/权限/主数据链路全部真实落地，**营销 9 页与客户域 4 卡均收口闭合**（券/推送/核销/ROI、积分商城/积分账户/标签/等级真实闭环）；B24 把**咨询→方案→支付→治疗→归档诊疗主轴全链路贯通**，B27 再补齐主轴两端的**接待台/候诊分诊**（到店→分诊→同事务建方案草稿→叫号→完成，预约签到自动到店）与 **EMR 电子病历独立域**（DRAFT→SIGNED→ARCHIVED→修订全状态机 + signEmr/treatDone 病历联动），B28 收口**到店核销 /m2-checkin** 真实闭环，B29 再补**排队智能候补 + 候诊超时自动释放 + 核销↔预约/划扣勾连**，预约与接待域 8✅（仅剩转化漏斗依赖数据分析）；B30 **近线收口四卡**（复购提醒前端接线 🔧→✅、EMR 模板库/真分页/中文 400、建档实时写 ES、术后随访 SOP 引擎），B31 **术后随访 SOP 引擎收口两卡**（随访工作台 /followup 整页接真+手工建随访端点+stats 九键、SOP 编排 /sop 整页+模板节点五动作+批次看板+一键升级，B30 明示 Backlog 两项全勾销），咨询诊疗域 8✅（仅剩复诊召回远期），工作台再摘除「术后回访/SOP 超期」两演示项（5 个演示项已摘除 4 个，仅剩复诊提醒）；B32 **客户搜索 DEAD 事件处置台两卡**（retry/replay/discard 三动作状态机+/search-events 处置台整页+全量重建入口，customer:search:admin 仅授 REGION_MGR，B30 outbox 遗留 DEAD 处置 Backlog 勾销），预约接待域 8✅（仅剩转化漏斗依赖数据分析）；B33 **组织树写能力整建两卡**（后端三写端点：仅门店下建部门/编辑三态语义/部门跨店移动联动 region/启停带停用原因，越权中文 404 防枚举探测；前端 T1OrgView 去 mock 并收窄写边界，组织树 🔧→✅），组织权限域仅剩兼岗上级路由与门店/大区层级写能力留 Backlog；当前仅剩 **🔧 少量近线收口项**（营销设置、充值赠金、BOM 异常前端、M1/移动端远期页）与**近线 ⬜ 补点**（资产转移、疗程跟踪等；ES reindex 对账治理仍留 Backlog）。

### 分业务域完成度

| # | 业务域 | ✅ | 🔧 | ⬜ | 主线状态 |
|---|---|---|---|---|---|
| ① | 客户与会员 | 8 | 0 | 10 | **客户基础运营 + 自动化 8 模块收口✅**（档案/积分商城/积分账户/标签/等级 B23；标签自动化/自动积分/等级批处理 B25）；M3 平台为后续 |
| ② | 预约与接待 | 8 | 0 | 1 | 预约/新建/我的工作台✅；**接待台+客情登记 B27 收口✅**（到店五态队列/分诊建 CP 草稿/预约签到自动到店/客情建档自动到店）；**到店核销 B28 收口✅**（/m2-checkin 登记→核销/异常/解除+timeline+客情扩展字段后端化+改派时间线）；**排队候补 B29 收口✅**（arrival_waitlist 智能候补/超时自动释放/核销↔预约勾连）；仅剩转化漏斗（依赖数据分析）⬜ |
| ③ | 咨询与诊疗 | 8 | 0 | 1 | 开方开单✅；咨询/医师工作台✅；**EMR 电子病历 B27 收口✅**（独立域全状态机+修订+方案联动；B30 补模板库/真分页/中文 400）；**复购提醒 B30 全链路收口✅**（知情同意硬前置+三方签核，`7c922cb`）；**术后随访 SOP 引擎 B30 收口、B31 两卡整页收口✅**（四表+幂等排程+超期升级 Job，`40a35ad`/`fa549d5`；B31 随访工作台/SOP 编排两整页+手工建+节点五动作+批次看板+一键升级，`70d2b3e`/`53901f6`）；仅剩复诊召回远期 ⬜ |
| ④ | 收银与交易 | 10 | 1 | 1 | **闭环✅**（收/退/划/批/卡/渠道全通；SLA/手续费收口；B24 治疗状态机贯通 PAID→TREATING→DONE） |
| ⑤ | 营销与留存 | 11 | 1 | 6 | **营销 9 页收口✅**（B22）+ **赠金高级规则✅/外部渠道回传✅（B26）**；营销设置仍 🔧（前端 m5-settings 接线）；落地页/转介绍 Backlog |
| ⑥ | 财务 | 11 | 0 | 1 | **全主线收口✅**（台账/对账/月结/成本/提成 + B24 发票/预算/设置/日报/卡余额/核销明细全部切真；经营毛利依赖全量收入，远期） |
| ⑦ | 组织与权限 | 10 | 0 | 14 | **闭环✅**（登录/RBAC/主数据）；B26 通知多渠道（SSE/扇出/免打扰）收口；**B33 组织树写能力整建 🔧→✅**；M2 运营为远期 |
| ⑧ | 平台与基建 | 4 | 2 | 62 | 网关/审计/字典/测试体系✅；M1/A1/T2/T4/移动端为远期 |

> ⑧ 的 ⬜ 62 主要是远期阶段页面群（M1 集团 14、A1 AI 15、移动端 20、T2/T4 等 13），非当前主线。

---

## ② 模块清单大表

> 「完成批次」列对应 §③ 时间线；「真实依据」列给出可核验的交付文档或代码位置。

### 域① 客户与会员（customer-service）

| 模块 | 状态 | 完成批次 | 真实依据 | 缺口 / 备注 |
|---|---|---|---|---|
| 客户档案 | ✅ | M4/P3 | 客户视图直连 @/api，交付文档 | — |
| 客户标签 | ✅ | P5-B23 | DELIVERY-P5-B23，`9fd5664` | 五分类 CRUD、覆盖汇总、防重打标、客户 360 打标/删标、tagId exists 过滤真实闭环；标签自动化规则列 Backlog |
| 会员等级 | ✅ | P5-B23 | DELIVERY-P5-B23，`9df9e7e` | 五档中文等级、阈值/权益/规则、手工调级、自动升级只升不降、实时人数；定时批处理与自动降级列 Backlog |
| 积分账户 | ✅ | P5-B23 | DELIVERY-P5-B23，`852d2f2` | 客户 360「档案」tab 流水与人工调分；clientToken 幂等、余额非负、全动作审计；消费自动积分列 Backlog |
| 积分商城 | ✅ | P5-B23 | DELIVERY-P5-B23，`64e8fe3` | 商品/规则/兑换/双签审核/拒绝/履约/KPI 真实闭环；持久单号、幂等下单、同事务扣积分扣库存 |
| 撞单合并 | ⬜ | — | business-flows 缺口 | 依赖客户查重规则，Backlog |
| 公海客户 | ⬜ | — | — | 远期 |
| 客诉管理 | ⬜ | — | — | 远期 |
| M3 客户运营平台（旅程/分群/任务/关怀/流失/NPS/导入导出/风控/洞察/设置 10 页） | ⬜ | — | — | **远期独立阶段 M3**，Backlog |

### 域② 预约与接待

| 模块 | 状态 | 完成批次 | 真实依据 | 缺口 / 备注 |
|---|---|---|---|---|
| 预约看板 | ✅ | M4 | 视图直连 @/api | — |
| 新建预约 | ✅ | M4 | 视图直连 @/api | — |
| 我的工作台 | ✅ | P5-B24/B27/B30 | DELIVERY-P5-B24，`50d694d`；B27 `a08bf4d`；B30 `40a35ad` | 7 项计数真实；B27 摘除「候诊待接待」「病历草稿」两演示项（改 listArrivals(WAITING)/listEmr(DRAFT) 真实计数）；**B30 再摘除「术后回访」「SOP 超期」两演示项**（/api/txn/followup/stats 真实 sopPending/sopOverdue 计数，60s 超期升级 Job 驱动）；仅剩复诊提醒 1 演示项，依赖营销自动化 Flow |
| 排队候补 | ✅ | P5-B29 | DELIVERY-P5-B29，`b85ee45`、`2e4d010` | arrival_waitlist 新域：WL 单号查库号池、手机号锚定会员/散客快照两态、WAITING→NOTIFIED→FULFILLED/CANCELLED 状态机；号源释放（手工/超时）同事务 FIFO 递补首位+店长站内信幂等（idemKey `WL:<wlNo>:<staffId>`）；/queue 第三卡+候补登记弹层+候补 KPI；超时自动释放见接待台行 |
| 接待台 | ✅ | P5-B27/B28/B29 | DELIVERY-P5-B27，`759990d`、`a08bf4d`；B28 `cac3f37`；B29 `ae646ed`、`2e4d010` | arrival 独立域：AH 单号/queue_no 本店当日连号/四渠道/WAITING→TRIAGED→CALLING→DONE 五态；分诊（CONSULT/MEDICAL/SERVICE）同事务建 consult_plan 草稿双向回挂、叫号/完成；预约签到同事务自动到店；审计 ARRIVAL 全动作；**B28 改派历史时间线（triage_reassign 同事务追加、正序富化、右栏最小内联改派 UI，同资质候选池剔除当前负责人）**；**B29 候诊超时自动释放（ArrivalAutoReleaseJob 60s 扫描「30min 阈值+10min 宽限」、批 50、系统释放 LEFT 同事务 FIFO 递补、手工释放按钮接真、重载状态防重入、`meiyun.queue.auto-release-enabled` 开关收敛）** |
| 客情登记 | ✅ | P5-B27/B28/B30 | DELIVERY-P5-B27，`a08bf4d`；B28 `aa87fcb`；B30 `09f590b` | GuestReg 手机号 watch 查重（searchCustomers+hydrate）、真实建档成功后自动 checkIn 到店并跳接待台；**B28 十类扩展字段后端化（customer 加 9 列：年龄/肤质/诉求/过敏史三列/意向项目/意向等级/预算/沟通要点，白名单+过敏互斥，客户 360 档案 tab 有值才显示 kv 回显）**；**B30 建档实时写 ES 已交付**（customer_search_event outbox 只存 customerId+10s 中继 Job 批 50 FIFO，SENT/RETRY/DEAD 三态，连续失败 3 次熔断 30s 降级 DB，显式 mapping name 分词+keyword 子字段，读时 mergeRecentFromDb 融合+DataScope 数据域过滤；**B32 DEAD 事件处置台已交付**（/search-events retry·replay·discard 三动作+全量重建入口，customer:search:admin 仅授 REGION_MGR，`f02384b`/`dbf8158`，见下「检索事件处置台」行）；ES reindex 对账治理（ES110 vs PG100 漂移 10 文档）仍留 Backlog） |
| 到店核销 | ✅ | P5-B28/B29 | DELIVERY-P5-B28，`8ae7389`；B29 `abbe49e`、`2e4d010` | checkin_record 独立域：CI 单号查库号池、三方式（SCAN/APPOINTMENT/WALKIN）、三态（PENDING/DONE/EXCEPTION）+四异常码（NOT_SELF/ALREADY_DONE/NO_APPOINTMENT/INFO_MISMATCH）、timeline JSON 同事务流水、手机号掩码出域；by-phone 内部目录端点（本店→公海→404）锚定会员/散客快照两态；当日同号 PENDING 幂等；**B29 APPOINTMENT 手机号命中当日预约后同事务四连写（回写 ci.appt_no/wd_no、预约置已到店、自动建到店号 AH、生成 writeoff_desk_task PENDING），核销详情透出勾连单号** |
| 检索事件处置台 | ✅ | P5-B32 | DELIVERY-P5-B32，`f02384b`、`dbf8158` | /search-events：KPI 四宫格点击筛选/状态·客户ID·事件ID 筛选/左表右详情双栏，retry·replay·discard 三动作状态机（DEAD→PENDING rc 清零、当场重放、丢弃 note≥2 字+处置三字段）+中文 400/404+CUSTOMER_SEARCH_EVENT 四 action 审计+全量重建索引入口；customer_search_event 扩 DISCARDED（ddl-auto）；权限 customer:search:admin（仅 REGION_MGR），无权限菜单隐藏+路由守卫 /no-auth；附修异常处理透传、audit biz_type varchar(32) |
| 转化漏斗 | ⬜ | — | — | 依赖数据分析 |

### 域③ 咨询与诊疗

| 模块 | 状态 | 完成批次 | 真实依据 | 缺口 / 备注 |
|---|---|---|---|---|
| 开方开单 | ✅ | M4/P3 | 视图直连 @/api | — |
| 咨询工作台 | ✅ | P5-B24 | DELIVERY-P5-B24，`d2d369b` | 方案单草稿保存/接诊真实端点（draft/start），会话区接真实方案单；EMR 独立域列 Backlog |
| 医师工作台 | ✅ | P5-B24/B30 | DELIVERY-P5-B24，`512990d`；B30 `40a35ad` | treat-start/treat-done 全链路（PAID→TREATING→DONE，术前四项/治疗归档），治疗队列切真；**B30 起 treat-done AFTER_COMMIT 幂等触发术后随访 SOP 排程**（物理事务 REQUIRES_NEW，见术后随访 SOP 行） |
| EMR 电子病历 | ✅ | P5-B27/B30 | DELIVERY-P5-B27，`759990d`、`a08bf4d`；B30 `5aa1e69`、`fc97d00` | emr_record 独立域：EM 单号查库号池（修订号 源号-R{n}）、DRAFT→SIGNED→ARCHIVED 全状态机、修订 parent_id/version、FIRST_VISIT/TREATMENT 两型；signEmr/treatDone 同事务幂等落病历并回挂 plan.emr_id/treat_emr_id；门店域全见越权 404；全链路网关+浏览器取证（audit 276–300）；**B30 补齐 EMR 模板库（emr_template 新域+集团 4 种子 EMT-SEED-001..004 只读+本店建/停用两态+模板库弹层九段范文，权限 emr:view/create 复用）、列表真分页（默认 20）/stats 后端聚合、ChineseValidationAdvice 框架异常中文 400；附修 fc97d00（JPQL cast(:type as string) 解决 PG 42P18 查询 500）；集团模板运营管理留 Backlog** |
| 复购提醒 | ✅ | P5-B30 | DELIVERY-P5-B30，`7c922cb` | RepurchaseView 全链路切真：客户 /api/customer/search 真实检索（DataScope 数据域隔离）、目标项目必填+知情同意硬勾选前置、待签核统计实时、客户（预填）/经办/店长三方签核填齐解禁、RP 单号查库序号不回退（冒烟 RP20260911-003194 已还原）、REPURCHASE/CREATE+TRIPLE_SIGN 审计；**ApprovalService 医师/店长/财务多级审批流留 Backlog** |
| 术后随访 SOP | ✅ | P5-B30/B31 | DELIVERY-P5-B30，`40a35ad`、`fa549d5`；DELIVERY-P5-B31，`3825a48`、`4492da4`、`d130dfc`、`70d2b3e`、`53901f6` | followup_sop_template/node/batch/followup 四表；treat-done AFTER_COMMIT→REQUIRES_NEW 物理事务幂等排程（source_plan_id uk+exists 双幂等），默认 1/3/7/30 天节点（微信/电话），种子 SPT-SEED-001 @Order(62) 仅 seed 库；batch_no 号池 `SOP{yyyyMMdd}-%` maxSeqOfDay 不回退；FollowupSopDueJob 60s 扫描批 50 FIFO 超期升级店长幂等通知（idemKey `SOP-ESC:{followupNo}:{staffId}`，免打扰）；/api/txn/followup 五端点（list/stats/get/complete/skip，followup:view/edit）+工作台两演示项摘除；附修 fa549d5（afterCommit 无活动事务致排程静默丢写）；**B31 纵深增强（不加行）：手工建随访 POST（门店强制取 JWT/客户实名解析/planDate≥serviceDate/method 白名单/sopStage=MANUAL/FOLLOWUP·CREATE 审计）、stats 扩九键（+pending/todayPending/overdue/done/skipped/avgSatisfaction/adverseCount，空库 AVG null 修 getSingleResult 的 d130dfc）、keyword 三字段 OR 模糊+固定排序分页；SOP 侧九端点（模板查改/节点增改停删/重置/批次分页聚合/summary 五键/一键升级），SOP_TEMPLATE 五动作审计，FollowupSopEscalator 抽为 DueJob 与一键升级共享组件（escalate 后 save 防标记丢失）** |
| 随访工作台 | ✅ | P5-B31 | DELIVERY-P5-B31，`3825a48`、`4492da4` | /followup 整页接真：FollowupView 列表（九键 KPI/keyword/分页/手工建弹层/完成·跳过流转）+ BoardView 流水牌 sopTodos 真库计数；schedulePostOpSop 降 async 空 shim 防前后端重复排程；C 端 /m/followup「陈美玲」2 条演示种子与 submitByCustomer 保留 |
| SOP 编排 | ✅ | P5-B31 | DELIVERY-P5-B31，`70d2b3e`、`53901f6` | /sop 整页接真：SopManagementView 双 tab（模板节点五动作+增改 dayOffset 0-365/method 白名单/内置可停用可改不可删（删除中文 400）/reset 同构重建；批次看板未完结在前+节点内嵌随访 24 字段）、五键 KPI、超期 warnbar 一键升级（v-perm followup:edit，成功 infobar 静默刷新）；删 SOP mock，router/nav 零改动（既有占位直接接真） |
| 复诊召回 | ⬜ | — | — | 依赖营销自动化，远期 |

### 域④ 收银与交易（txn-service）— 核心闭环 ✅

| 模块 | 状态 | 完成批次 | 真实依据 | 缺口 / 备注 |
|---|---|---|---|---|
| 订单收款 | ✅ | M4/P3 | 网关 200 + 交付文档 | — |
| 整单划扣 | ✅ | B17 | DELIVERY-P5-B17 | 三路径打通 |
| 划扣执行台（双签） | ✅ | B17/B18 | WriteoffDeskService 硬闸门 | L1 双签 |
| 退款（L3 三签） | ✅ | B19 | DELIVERY-P5-B19，fbe65c2 | 店长→区域→财务 |
| 退卡（冻结/终审） | ✅ | B18/B19/B20 | DELIVERY-P5-B18/B19/B20 | 手续费 RF-REVENUE/IN 收入分录 B20 收口；退卡 SLA 经 B21 实证由无差别 SLA Job 覆盖 |
| 审批中心 | ✅ | B19/B20/B21 | ApprovalService 状态机，DELIVERY-P5-B20/B21 | SLA/候选过滤 B20 收口；转交/加签目标人硬校验（存在/在职/阶段角色/自转交/重复加签/加签给指派人）+ 详情指派人/会签人展示 B21 收口；REGION 兼岗路由留 Backlog |
| 会员卡项 / 疗程 | ✅ | B16/B17 | member_card 真实 | — |
| 卡项目录 | ✅ | B15 | DELIVERY-P5-B15 | — |
| 支付渠道 | ✅ | P3 | 渠道账实 B12 | — |
| BOM 异常 | 🔧 | B10 | 后端 only | 前端待接 |
| 疗程跟踪 | ⬜ | — | — | 近线缺口 |
| 资产转移 / 合同 | ⬜ | — | business-flows 缺口 | Backlog |

### 域⑤ 营销与留存（marketing-service）

| 模块 | 状态 | 完成批次 | 真实依据 | 缺口 / 备注 |
|---|---|---|---|---|
| 优惠券 | ✅ | B22 | DELIVERY-P5-B22 | 券模板/发放/核销真实闭环；8 券种网关+浏览器验证 |
| 精准推送 | ✅ | B22 | DELIVERY-P5-B22，PushService | 写链路四件套：幂等(SHA-256 dedupKey 60s)/审计(PUSH/SEND)/周频控(7天≤3)/违禁词；13 客户真实 |
| 券核销 | ✅ | B22 | DELIVERY-P5-B22，CouponWriteoffService | 三态(FORGED/DUPLICATE/EXPIRED)+折扣(分)+审计(WRITEOFF/BLOCK)；门店三级兜底(登录门店→/internal/first→SST01) |
| 营销总览 | ✅ | B22 | DELIVERY-P5-B22 | 触达/漏斗/趋势/渠道排行真实；导出报告/订阅周报占位 Backlog |
| 素材库 | ✅ | B22 | DELIVERY-P5-B22 | 10 素材元数据/标签/授权门店/引用计数；文件二进制(S3/MinIO) Backlog |
| 海报 | ✅ | B22 | DELIVERY-P5-B22，m5Poster | 推荐人下拉接 listStaff 真实在职员工(19名)；模板/漏斗/佣金真实；图片渲染 Backlog |
| 直播/短视频 | ✅ | B22 | DELIVERY-P5-B22 | 7 场直播/挂链成交/漏斗真实；开播写接口/成交回写 Backlog |
| 营销 ROI | ✅ | B22 | DELIVERY-P5-B22，m5Roi | 活动维度 ROI/发券核销统计真实(GET /stats/overview)；渠道区 7 平台+归因模型为外部依赖 Backlog |
| 渠道业绩 | ✅ | B22 | DELIVERY-P5-B22，m5Channel | 7 渠道卡片/接入状态/对账日真实渲染；外部广告平台(美团/抖音/小红书/大众点评/新氧)数据接入 Backlog |
| 营销设置 | 🔧 | — | 前后端均实 | 不在 B22 9 页内；随营销规则引擎(赠金高级规则/频控配置化)收口 |
| 充值赠金 | 🔧 | B18 | DELIVERY-P5-B18 | 满赠/有效期/报表留后续 |
| 落地页 / 日历 / 转介绍 | ⬜ | — | business-flows 缺口 | 转介绍到期重分配 Backlog；referral mock 待客户域 M3/营销渠道 |
| 随访 / SOP / 关怀 / 召回 | ⬜ | — | — | 依赖私域自动化 Flow，远期；术后随访 SOP 引擎及随访工作台/SOP 编排两整页已由 B30/B31 在诊疗域（txn-service）闭合（见域③ 术后随访 SOP/随访工作台/SOP 编排行），本行指营销侧关怀/沉睡唤醒/复诊召回 Flow，仍远期 |

### 域⑥ 财务（finance-service）— 核心闭环 ✅

| 模块 | 状态 | 完成批次 | 真实依据 | 缺口 / 备注 |
|---|---|---|---|---|
| 资金台账（fund_entry） | ✅ | B1/P3 | FundEntryService.postEntries 幂等 | — |
| 三方对账 | ✅ | B6 | DELIVERY-P3-B6 | — |
| 渠道账实 | ✅ | B12 | DELIVERY-P3-B12 | — |
| 月结封账 | ✅ | B11 | DELIVERY-P3-B11 | 封账月禁重算 |
| 成本结转 | ✅ | B8 | CostCarryService（COST 分录） | — |
| 提成 | ✅ | B9 | DELIVERY-P3-B9 | — |
| CSV 导出 | ✅ | B7 | DELIVERY-P3-B7 | — |
| 发票管理 | ✅ | P5-B24 | DELIVERY-P5-B24，`1eaef84` | 发票列表去 mock 接真实端点，发票 CSV 真实导出（/api/finance/export/invoices.csv） |
| 预算 / 财务设置 | ✅ | P5-B24 | DELIVERY-P5-B24，`1eaef84` | 预算与财务设置页去 mock 切真；进项税抵扣列 Backlog |
| 税 / 卡余额 / 日结 / 异常 | ✅ | P5-B24 | DELIVERY-P5-B24，`1eaef84`、`dfbe14a` | 资金日报去 mock；卡余额时间线切真（customer-service 内部投影 /internal/cards/{cardNo}/ledger）；通用异常中心列 Backlog |
| 财务核销 / 预收 | ✅ | P5-B24 | DELIVERY-P5-B24，`dfbe14a` | 核销双签明细切真（txn-service /internal/finance/writeoff-details，X-Internal-Token），卡台账/核销 CSV 导出；预收合规监控列 Backlog |
| 经营毛利 | ⬜ | — | — | 依赖成本+收入全量，远期 |

### 域⑦ 组织与权限（org-service）— 核心闭环 ✅

| 模块 | 状态 | 完成批次 | 真实依据 | 缺口 / 备注 |
|---|---|---|---|---|
| 登录 / JWT | ✅ | M7 | DELIVERY-M7，/api/org/auth/login | — |
| 员工管理 | ✅ | M7 | staff/staff_role 真实 | — |
| 角色 RBAC / 权限矩阵 | ✅ | M7/B19 | PermissionMatrix | 角色管理页待补（Backlog） |
| 房间床位 | ✅ | B13 | DELIVERY-P4-B13 | — |
| 设备台账 | ✅ | B13 | DELIVERY-P4-B13 | — |
| 项目 / 品牌 | ✅ | P4 | 主数据真实 | — |
| 价目表 | ✅ | B14 | DELIVERY-P5-B14 | — |
| 耗材库存 | ✅ | B8/B13 | 库存真实 | — |
| 组织树 | ✅ | B33 | DELIVERY-P5-B33，OrgAdminController 三写端点 + T1OrgView 去 mock | 部门层级写闭环真实（仅门店下建部门/编辑三态语义/部门跨店移动联动 store_code+region/启停带停用原因，越权中文 404）；**门店/大区/集团层级写能力与兼岗上级路由留 Backlog**，部门只停用不物理删 |
| 消息通知中心 | ✅ | B20/B21 | DELIVERY-P5-B20/B21，NotificationController 6 端点 | 系统内通知真实闭环（铃铛/列表/已读）；通知偏好持久化（notify_preference 表 + GET/PUT 端点 + SLA 催办偏好免打扰双向实证）B21 收口；**多渠道（短信/企微/邮件 dev 网关 + SSE 实时推送 + 免打扰 + 失败重试/死信）B26 收口**，真实运营商网关接入留 Backlog |
| 交接班 | ⬜ | — | — | 近线缺口 |
| M2 门店运营（排班/工单/日结/申购/报损/绩效/周报/巡检/拓客/唤醒/异常等 13 页） | ⬜ | — | — | **远期独立阶段 M2**，Backlog |

### 域⑧ 平台与基建

| 模块 | 状态 | 完成批次 | 真实依据 | 缺口 / 备注 |
|---|---|---|---|---|
| 审计链（audit-service） | ✅ | M7 | 审计落库真实 | — |
| 字典管理 | ✅ | M7 | 字典端点真实 | — |
| 国密网关双栈（Go） | ✅ | M7 | 18443/8443 双栈 | — |
| 测试 / 双栈验证体系 | ✅ | 全程 | 每批 DoD（mvn+vue-tsc+双栈+PG 对账） | — |
| M1 集团（brand/procurement/marketing 3 页真实） | 🔧 | — | 3 页直连 | 其余 14 页 mock，**远期 M1** |
| C 端移动端（packages/coupons 2 页真实） | 🔧 | — | mp-uniapp 2 页 | 其余 20 页 mock，**远期移动端** |
| M1 集团其余 14 页 | ⬜ | — | — | 远期 M1 |
| T2 数据分析（4 页） | ⬜ | — | — | 远期 T2 |
| T3 外部集成 | ⬜ | — | — | 远期 T3 |
| T4 AI 算力（4 页） | ⬜ | — | — | 远期 T4 |
| A1 AI 中心（15 页） | ⬜ | — | — | 远期 A1 |
| 通用页（全局搜索/帮助/403/闭环演示） | ⬜ | — | — | 近线/体验项 |

---

## ③ 批次时间线

| 批次 | 日期 | 主题 | commit | 交付文档 | 闭合 / 推进模块 |
|---|---|---|---|---|---|
| M7 | 2026-09-02 | 权限/JWT/数据域/审计/网关/字典 | — | DELIVERY-M7 / M7-5 / M7-8 / M7-9 | 登录、RBAC、审计、字典、网关 |
| M4 | 2026-09-02 | 交易骨架 / 客户 / 预约 / 开单 | — | DELIVERY-M4 | 订单收款、客户档案、预约、开方开单 |
| P3-B1 | 2026-09-05 | 财务接线 / fund_entry 台账 | — | DELIVERY-P3-B1 | 资金台账 |
| P3-B2 | 2026-09-05 | 合规落账 | — | DELIVERY-P3-B2 | 资金台账 |
| P3-B3 | 2026-09-05 | 充值台账 | — | DELIVERY-P3-B3 | 充值/资金 |
| P3-B4 | 2026-09-05 | 财务配置 | — | DELIVERY-P3-B4 | 财务设置半成 |
| P3-B5 | 2026-09-06 | 划扣 G1 | — | DELIVERY-P3-B5 | 划扣基础 |
| P3-B6 | 2026-09-06 | 三方对账 | — | DELIVERY-P3-B6 | 三方对账 ✅ |
| P3-B7 | 2026-09-06 | CSV 导出 | — | DELIVERY-P3-B7 | CSV 导出 ✅ |
| P3-B8 | 2026-09-06 | 成本 / 库存 | — | DELIVERY-P3-B8 | 成本结转、耗材库存 |
| P3-B9 | 2026-09-06 | 提成 | — | DELIVERY-P3-B9 | 提成 ✅ |
| P3-B10 | 2026-09-06 | BOM | — | DELIVERY-P3-B10 | BOM 异常（后端） |
| P3-B11 | 2026-09-07 | 月结封账 | — | DELIVERY-P3-B11 | 月结封账 ✅ |
| P3-B12 | 2026-09-07 | 渠道账实 | — | DELIVERY-P3-B12 | 渠道账实、支付渠道 ✅ |
| P4-B13 | 2026-09-07 | 房间床位 / 设备 | — | DELIVERY-P4-B13 | 房间床位、设备台账 ✅ |
| P5-B14 | 2026-09-07 | 价目设计 | — | DESIGN/DELIVERY-P5-B14 | 价目表 ✅ |
| P5-B15 | 2026-09-07 | 卡项目录 | — | DELIVERY-P5-B15 | 卡项目录 ✅ |
| P5-B16 | 2026-09-07 | 售卡开卡 | — | DELIVERY-P5-B16 | 售卡/开卡 |
| P5-B17 | 2026-09-07 | 卡核销三路径 + 去划扣动线 | `c40f42d` | DELIVERY-P5-B17 | 整单划扣、划扣台、会员卡项 ✅ |
| P5-B18 | 2026-09-08 | 赠金 + 退卡冻结 + 双签金额分级 | `3162abf` | DELIVERY-P5-B18 | 赠金、退卡冻结、双签硬校验 |
| P5-B19 | 2026-09-08 | L3 大额三签审批（店长→区域→财务）+ sign3 | `fbe65c2` | DELIVERY-P5-B19 | 退款/退卡/审批中心 ✅ |
| P5-B20 | 2026-09-08 | 审批 SLA 超时收口（三阶段时限/催办/标记）+ 消息通知中心真实落地 + 候选人门店预过滤 + 退卡手续费收入分录 | `b9e5829` | DELIVERY-P5-B20 | 审批中心 SLA、**消息通知中心 ✅ 新闭环**、退卡手续费 |
| P5-B21 | 2026-09-08 | 审批转交/加签目标人硬校验（存在/在职/阶段角色/自转交/重复加签/加签给指派人）+ 通知偏好持久化（notify_preference 表 + GET/PUT 端点 + SLA 催办偏好免打扰）+ 审批详情指派人/会签人展示 + 退卡冻结 SLA 覆盖实证勾销 | `89c3c64` | DELIVERY-P5-B21 | 审批硬校验、通知偏好持久化 ✅（既有模块纵深增强，不新增页面） |
| P5-B22 | 2026-09-08 | 营销 9 页收口：精准推送写链路四件套（参数校验/幂等 dedupKey 60s/周频硬上限 3/违禁词四类实时校验 + 全动作审计 PUSH-SEND）+ 券核销门店三级兜底（登录门店→store-service /internal/first 内部端点→SST01 降级，FORGED 异常单也落真实门店）+ 核销链路漏斗播种器（CH-001/002/003）+ 海报推荐人接真实员工（listStaff 19 名在职，替换 mock 林晚）+ 9 页浏览器逐页真实验证零控制台报错 | `1977df4` | DELIVERY-P5-B22 | 营销 9 页 ✅（优惠券/精准推送/券核销/营销总览/素材库/海报/直播短视频/营销ROI/渠道业绩；34→43/166=26%） |
| P5-B23 | 2026-09-09 | 客户域 4 卡收口：积分商城（持久单号/双签审核/幂等下单/履约/规则四件套）、积分账户人工调分（clientToken 防重放/余额非负/流水分页/积分池真实读模型）、客户标签（五分类 CRUD/防重打标/覆盖汇总/tagId 过滤/360 打标删标/全程审计）、会员等级（中文五档/阈值权益/单行规则/手工调级/实时人数/自动升级只升不降） | `64e8fe3`、`852d2f2`、`9fd5664`、`9df9e7e` | DELIVERY-P5-B23 | 积分商城、积分账户、客户标签、会员等级 4 页 ✅（43→47/166=28%；客户域 ✅1→5、🔧4→0） |
| P5-B24 | 2026-09-09 | 「两线合并全收」5 卡：财务四页去 mock + 发票真实 CSV、卡余额时间线 + 核销双签明细服务间内部投影切真、咨询草稿/接诊端点 + ConsultationView 切真、treat-start/treat-done 治疗全链路（术前四项/治疗归档 EMR）+ 医师工作台切真、我的工作台 7 项计数真实化（5 项无后端域诚实标注「（演示）」不计总数） | `1eaef84`、`dfbe14a`、`d2d369b`、`512990d`、`50d694d` | DELIVERY-P5-B24 | 发票/预算/财务设置/资金日报、卡余额时间线/核销明细、咨询工作台、医师工作台、我的工作台 7 项 ✅（47→54/166=33%；财务 ✅7→11、🔧4→0；咨询诊疗 ✅1→3、🔧4→2） |
| P5-B25 | 2026-09-09 | 域①客户域自动化三件套：标签自动化规则引擎（TA### 规则 CRUD + TagAutoRuleJob 定时扫描 + 5 类条件 + 打/撤标 + 审计）、消费自动积分（txn 内部只读端点拉取已收款/已退款订单 + 积分规则折算 + AUTOPOINTS 幂等键发分/回退）、等级定时批处理（LevelMonthlyJob 每月1号 cron + autoUpgrade 开关收敛 + 汇总审计） | `00a06e3`（代码）、见 DELIVERY-P5-B25 §六（docs） | DELIVERY-P5-B25 | 标签自动化规则、消费自动积分、等级定时批处理 3 模块 ✅（54→57/166=34%；客户域 ✅5→8、⬜13→10） |
| P5-B26 | 2026-09-09 | 域⑦ 通知多渠道 + 域⑤ 赠金高级规则 + 外部渠道回传骨架：渠道适配层（Inbox/SMS/Wechat/Email dev 网关 POST）+ NotificationFanoutJob 定时扇出 + NotificationStreamController SSE 实时推送 + 免打扰全局配置；GrantRule/GrantService（满赠阶梯/有效期/账本/过期/报表）+ GrantExpireJob；ExternalChannelController 通用回传回调 + channel_returnback 表 + 种子联调 | 见 DELIVERY-P5-B26（docs） | DELIVERY-P5-B26 | 通知多渠道、赠金高级规则、外部渠道回传 3 模块 ✅（57→60/166=36%；营销域 ✅9→11、🔧2→1） |
| P5-B27 | 2026-09-10 | 主轴接待台/候诊分诊 + EMR 独立域（链路+完整页面交互）：arrival/triage/emr_record 三新表；到店五态队列（AH 单号、queue_no 本店当日连号、四渠道、预约签到同事务自动到店）；分诊三型同事务建 consult_plan 草稿+双向回挂+改派留痕+叫号/完成；EMR DRAFT→SIGNED→ARCHIVED+修订（-R{n}）全状态机，signEmr/treatDone 同事务幂等落病历回挂方案；客情登记真实建档+自动到店；工作台两演示项摘除；**附带修复 2 项运行态缺陷**（EMR 号池 char_length 18→17 每日重号 500；nanoTime 伪随机病历号删除收敛查库池）；前端 14 文件模板零改动 | `759990d`、`a08bf4d` | DELIVERY-P5-B27 | 接待台、客情登记、EMR 电子病历 3 模块 ✅（60→62/166=37%；预约接待 ✅3→5、⬜5→3；咨询诊疗 ✅3→4、🔧2→1）；冒烟全量还原 |
| P5-B28 | 2026-09-10 | 预约接待收口批三卡：到店核销 /m2-checkin 真实闭环（checkin_record 新表+CI 单号池、SCAN/APPOINTMENT/WALKIN 三方式、PENDING/DONE/EXCEPTION 三态+四异常码、确认核销/标记异常/解除异常+timeline 同事务流水、KPI/过滤、by-phone 本店→公海→404 锚定、当日同号 PENDING 幂等、手机号掩码出域）；客情登记十类扩展字段后端化（customer 加 9 列、白名单+过敏互斥、360 档案有值才显示 kv）；triage 改派历史时间线（triage_reassign 新表同事务追加+读模型批量富化+接待台最小 UI） | `aa87fcb`、`cac3f37`、`8ae7389` | DELIVERY-P5-B28 | 到店核销 1 模块 ✅（62→63/166=38%；预约接待 ✅5→6、⬜3→2；客情扩展/改派时间线为既有模块纵深增强）；audit 301–313 共 13 行；冒烟全量还原 |
| P5-B29 | 2026-09-10 | 预约接待尾工批三卡：排队智能候补（arrival_waitlist 新表+WL 单号池、手机号锚定会员/散客快照两态、WAITING→NOTIFIED→FULFILLED/CANCELLED 状态机、号源释放同事务 FIFO 递补首位+店长站内信幂等、/queue 第三卡+候补登记弹层+候补 KPI+30s 双刷）；候诊超时自动释放号源（ArrivalAutoReleaseJob 60s 扫描「30min 阈值+10min 宽限」、批 50、系统释放 LEFT 同事务递补、手工释放按钮接真、重载状态防重入、auto-release-enabled 开关收敛）；到店核销↔预约/划扣自动勾连（APPOINTMENT 手机号命中当日预约，同事务回写 appt_no/wd_no+预约置已到店+自动建到店号+writeoff_desk_task PENDING，核销详情透出勾连单号） | `b85ee45`、`ae646ed`、`abbe49e`、`2e4d010` | DELIVERY-P5-B29 | 排队候补 1 模块 ✅（63→64/166=39%；预约接待 ✅6→7、⬜2→1；超时释放/核销勾连为既有模块纵深增强，转化漏斗仍 ⬜）；网关 curl 五阶段 24 项全绿（含 Job 自动释放实证）+浏览器两页双轨取证 console 零错；audit 314–331 共 18 行、哈希链 17/17；冒烟全量还原（删除计数 2/2/1/1/4/3/1，审计 append-only） |
| P5-B30 | 2026-09-11 | 近线收口四卡：③ 复购提醒前端接线（RepurchaseView 全链路：客户真实检索+目标项目必填+知情同意硬前置+客户·经办·店长三方签核+RP 单号池+REPURCHASE/CREATE·TRIPLE_SIGN 审计，🔧→✅）；④ EMR 模板库/400 化（emr_template 新域+集团 4 种子 EMT-SEED-001..004 只读+本店建/停用+模板库弹层九段范文、列表真分页/统计后端聚合、ChineseValidationAdvice 框架异常中文 400）；② 建档实时写 ES（customer_search_event outbox 只存 customerId+10s 中继 Job 批 50 FIFO+SENT/RETRY/DEAD+连续失败 3 次熔断 30s 降级 DB+显式 mapping+读时 mergeRecentFromDb 融合+DataScope 过滤+新索引全量回填）；① 术后随访 SOP 引擎（followup_sop_template/node/batch/followup 四表+treat-done AFTER_COMMIT 幂等排程 1/3/7/30 天+60s 超期升级 Job 店长幂等通知免打扰+/api/txn/followup 五端点+工作台两演示项切真摘除）；**附修 2 项运行态真实缺陷**（fa549d5 afterCommit 无活动事务致 SOP 排程静默丢写→REQUIRES_NEW 物理事务；fc97d00 模板库裸 null 参数致 PG 42P18 查询 500→JPQL cast string）；样式零改动 | `7c922cb`、`5aa1e69`、`09f590b`、`40a35ad`、`fa549d5`、`fc97d00` | DELIVERY-P5-B30 | 复购提醒、术后随访 SOP 2 模块 + 建档实时写 ES/EMR 模板库 2 项 Backlog 纵深闭合（64→68/166=41%，🔧6→5、⬜96→93；咨询诊疗 ✅4→6、🔧1→0）；网关 curl 全路径（事件 SENT/7 次 RETRY 自愈/熔断/DataScope SELF 隔离 []）+浏览器 RP20260911-003194 创建→三方签核与模板库 UI 双轨取证 console 零错；audit 81–93 共 13 行九类动作 append-only 保留；冒烟全量还原（PG 当日造数零残留、customer 基线 100、ES 113→110 三历史漂移槽位如实记录） |
| P5-B31 | 2026-09-11 | 术后随访 SOP 引擎收口两卡（闭合 B30 明示 Backlog 两项）：卡A 随访工作台 /followup 整页接真（手工建随访 POST：门店强制取 JWT/客户实名解析/planDate≥serviceDate/method 白名单默认 PHONE/sopStage=MANUAL；stats 扩九键 sopPending·sopOverdue 旧键不动+pending/todayPending/overdue/done/skipped/avgSatisfaction/adverseCount；keyword 客户名/项目/关联单号 OR 模糊+固定排序分页；FollowupView 整页+BoardView sopTodos 真库；schedulePostOpSop 降 async 空 shim 防前后端重复排程；C 端 /m/followup「陈美玲」种子与 submitByCustomer 保留；**附修空库 AVG null 致 /stats 500 的 NPE→getSingleResult**）；卡B SOP 编排 /sop 整页接真（模板节点增改停删+重置五动作、dayOffset 0-365/内置可停用可改不可删（删除中文 400）、批次分页聚合未完结在前、summary 五键、一键升级超期节点，FollowupSopEscalator 抽为 DueJob/一键升级共享组件+escalate 后 save 防标记丢失，SOP_TEMPLATE 五动作审计，前端删 SOP mock 双 tab/KPI/warnbar·infobar）；样式零改动、router/nav 零改动 | `3825a48`、`4492da4`、`d130dfc`、`70d2b3e`、`53901f6` | DELIVERY-P5-B31 | 随访工作台、SOP 编排 2 页 ✅（68→70/166=42%，⬜93→91、🔧5 不变；咨询诊疗 ✅6→8）；网关 curl 全路径（空体 Bean Validation 422 中文原文、内置删除 400、他店批次 404、一键升级 escalated 计数）+浏览器两页双轨取证（KPI 2/5/2/1、reqid 104→105/106 静默刷新 infobar、console 仅 1 a11y 零 error/warn）；**偏差如实披露：全量重建经 setup-seed-db.sh TRUNCATE audit_log，本批冒烟审计不存活，终态 36 行全 CONSUMABLE 播种基线，已登记「脚本增补保留 audit 选项」Backlog**；冒烟全量还原（followup=0、template=1、node=4 全启用、SOP-ESC 幂等键=0） |
| P5-B32 | 2026-09-11 | 客户搜索 outbox「DEAD 事件处置台」两卡（闭合 B30 遗留 Backlog）：卡A 后端（customer_search_event 扩 DISCARDED+resolve_note/resolved_by/resolved_at，CustomerSearchEventAdminService 三动作状态机：retry 仅 DEAD→PENDING rc 清零/last_error 清空、PENDING 幂等、终态中文 400；replay PENDING/DEAD 当场同步投递 ES、ES 不可用保持 PENDING 走 RETRY；discard note trim≥2 字+处置三字段；GET search-events 三过滤+event_id 倒序真分页+/stats 四键；reindex 换权 customer:search:admin；CUSTOMER_SEARCH_EVENT 四 action MANUAL_RETRY/REPLAY/DISCARD/REINDEX 合法 JSON 审计；权限码仅挂 REGION_MGR；附修 GlobalExceptionHandler 漏接 ResponseStatusException 中文 400/404 被兜 500、audit_log.biz_type varchar(16)→(32)）；卡B 前端（/search-events 处置台整页：KPI 四宫格点击筛选/筛选条事件ID 正整数校验/左表右详情双栏/选中粉行/last_error 红块/三按钮按状态机禁用/丢弃 modal 行内校验/写后 list+stats 两连刷新/全量重建 toast，nav 客情洞察组+路由守卫） | `f02384b`、`dbf8158` | DELIVERY-P5-B32 | DEAD 事件处置能力+处置台 2 项 Backlog 闭合（70→72/166=43%，⬜91→89、🔧5 不变；预约接待 ✅7→8）；网关 curl 全矩阵（中文 400/404/401/SE001 403/reindex=100/审计 JSON）+浏览器双轨取证（中继 Job 活行为：PENDING 经 RETRY rc 封顶 20 转 DEAD；丢弃 1 字行内报错/三 toast；network 23 个全 200、console 零 error/warn、双截图；SE001 菜单隐藏+守卫 /no-auth 403）；造数外科手术式还原（删 4 事件+setval 归 1+按 biz_type 删测试审计 id42-49，他类审计完好 max=36）；ES reindex 对账治理仍留 Backlog |

---

## ④ 后续规划 Backlog

> 登记「本批做不了、涉及后续模块/阶段」的事项，逐条注明**来源**与**依赖/前置**与**建议批次**。能收口的在当前批次收口，不入此表。

### B20 已收口（2026-09-08 交付，详见 DELIVERY-P5-B20）

L3 审批 SLA 超时扫描+催办（ApprovalSlaJob 60s/三阶段时限 24h-8h-4h/120min 节流/幂等）、消息通知中心最小落地（notification 表 + 4 真实端点 + 铃铛角标/跳转已读）、审批中心候选人门店预过滤、退卡手续费 RF-REVENUE/IN 收入分录对账、sign_tier 双库回溯（无空值/在途全对）——全部闭合。

### B21 已收口（2026-09-08 交付，详见 DELIVERY-P5-B21）

审批转交/加签目标人硬校验（guardTargetApprover：工号存在/在职/主角色+兼岗并集按阶段校验 STORE_MGR/REGION_MGR/FINANCE/自转交拦截/重复加签拦截/加签给当前指派人拦截，网关硬校验矩阵 10/10 全中、中文报错原文留档；审计历史带 toName/whoName 中文姓名）、通知偏好持久化（notify_preference 表 + uk 唯一约束 + GET/PUT /api/txn/notifications/preferences 端点，五类×四渠道校验，前端 Pinia 乐观更新+snapshot 回滚、UI 零改动）、SLA 催办偏好免打扰（ApprovalSlaJob 收件人偏好过滤，关订阅→跳过1人/通知0人、恢复→通知1人，双向实证）、审批详情指派人/会签人展示（ApprovalView 模板补「当前指派人/会签人」）、退卡冻结 SLA 超时提醒（**实证勾销**：Job 仅按 status=PENDING 扫描不分 bizType + seed 库 CARD_CANCEL 6 单全走 approval_todo 办结，B20 已无差别覆盖）——全部闭合。

### B22 已收口（2026-09-08 交付，详见 DELIVERY-P5-B22）

营销 9 页收口全部闭合：**精准推送写链路四件套**（PushService：Bean Validation 参数校验 422、dedupKey=SHA-256(customerId|pushType|content) 前 16 位 60s 窗口幂等重放返回同一 pushId 不重复落库/不占频控/不重复审计、DbRateLimiter 周频硬上限 3 超限 400 中文报错、违禁词四类实时校验 400；全动作 audit_log bizType=PUSH/action=SEND，网关矩阵 7/7）、**券核销门店三级兜底**（currentStore：登录人 storeCode→store-service 新增 GET /api/stores/internal/first 内部端点（X-Internal-Token，服务间走 HTTP 不直读别域表）→FALLBACK SST01 降级；门店字段在三态分支之前设置，FORGED 异常单也落真实门店；E014 无门店上下文实证返回 ST-SH-001/上海旗舰店）、**核销链路漏斗播种器**（CouponWriteoffChainDataInitializer @Order(33)：CH-001 正常核销 774 / CH-002 异常核销 9 / CH-003 待处理 59，count>0 skip 幂等）、**海报推荐人接真实员工**（m5Poster store loadReferrers 改 listStaff，filter 非离职 map staffName/role.roleName，zh-Hans-CN 排序；Pinia 直查 19 名真实在职员工，mock「林晚」已替换）、**9 页浏览器逐页真实验证**（优惠券 8 券/1742 领取/3 核销、推送 13 客户/周频提示、核销今日 12/正常 3/异常 9/抵扣 ¥1,140、海报 6 模板/佣金 ¥9,370、素材库 10 素材、直播 7 场/挂链 ¥471,400、ROI 发券核销真实、渠道业绩 7 渠道卡片、总览 651,030 触达漏斗，控制台零报错）。核销三态对账：audit_log COUPON_WRITEOFF 8 条（WRITEOFF 1：VIP85 discountFen=55200；BLOCK 7：FORGED×4/DUPLICATE×3）。

### B23 已收口（2026-09-09 交付，详见 DELIVERY-P5-B23）

客户域 4 卡全部闭合：**积分商城**（商品/规则/兑换/双签审核/拒绝/履约/KPI，持久单号、幂等下单、审核通过同事务扣积分扣库存、履约幂等）、**积分账户人工调分**（客户 360「档案」tab、流水分页、积分池真实读模型、非零整数/原因长度/余额非负校验、clientToken 部分唯一索引防重放、全动作审计）、**客户标签**（消费/肤质/行为/价值/医疗五分类 CRUD、标签名全局唯一、重复打标 409、删除定义级联解绑、覆盖汇总、客户列表 tagId exists 过滤、360 打标/删标）、**会员等级**（普通/银卡/金卡/钻石/黑卡中文五档、阈值/折扣/权益、单行等级规则、手工调级、自动升级只升不降、客户表实时人数聚合）。prod 当前 B23 审计 33 条、17 个动作分组、null_payload=0；seed 重置后客户 100、标签 12、打标 265、商城商品 7、兑换单 8、B23 审计 0，四页浏览器回归无 JS 报错。

### B24 已收口（2026-09-09 交付，详见 DELIVERY-P5-B24）

「两线合并全收」5 卡全部闭合：**财务四页去 mock + 发票 CSV**（发票/预算/财务设置/资金日报接真实端点，`/api/finance/export/invoices.csv` 真实导出）、**卡余额时间线 + 核销双签明细**（customer-service `GET /internal/cards/{cardNo}/ledger` 与 txn-service `GET /internal/finance/writeoff-details` 两个 X-Internal-Token 内部投影，服务间走 HTTP 不跨库直读；finance 聚合暴露卡时间线/核销明细/卡台账 CSV/核销 CSV，四类枚举中文映射）、**咨询草稿/接诊**（consult-plan draft/start 端点 + ConsultationView 会话区切真实方案单）、**treat-start/treat-done 治疗全链路**（方案单状态机 PENDING→ACTIVE→PENDING_REVIEW→APPROVED→READY_PAY→PAID→TREATING→DONE 贯通，TreatStartCmd 扁平四项布尔术前核对、treat-done 治疗小结必填并生成 treatEmrId，权限 consult:review+emr:edit 双栈硬校验）、**我的工作台 7 项计数真实化**（审批/待收款/今日预约/方案单四态，每域 auth.can 门控 + 独立容错；候诊接待/病历草稿/术后回访/SOP 超期/复诊提醒 5 项无后端域保留演示并显式标注「（演示）」、`todoTotal` 只累加真实项）。prod 终验：E003 陈医生真实待办 2（ACTIVE=1/PENDING_REVIEW=1，无权限项隐藏，order 403）、E005 李店长待收款 1、SE101 真实待办 3，/doctor 刘女士 M002 ¥2280 真实方案单，三页 console 零错；prod 凭证澄清（E001–E014/SE101–SE105 + meiyun123，SE001 仅 seed 库）。seed 端到端造数 TREATING 链路实证（江医生 5/许店长 11），测试残留单号已在交付文档 §5.2 诚实登记。样式零改动。

### 移交后续（Backlog）

| 事项 | 来源 | 依赖 / 前置 | 建议批次 |
|---|---|---|---|
| SLA 超时自动升级/自动代办 | B20/B21 移交 | 组织树兼岗模型完善（兼岗上级路由）；现仅标记+催办+偏好免打扰。**B33 复核：本批只补齐部门层级写能力（建/改/启停），未引入兼岗上级字段与路由算法，本项维持 Backlog** | 组织树二批（兼岗模型） |
| 通知多渠道（短信/企微/邮件/WebSocket 实时推送） | B20/B21 移交 | 渠道适配层；notify_preference.channels 已存渠道位（INBOX/SMS/WECHAT/EMAIL）不返工；现为系统内通知+轮询 | B22+ |
| 审批 REGION 阶段兼岗路由（指派人/会签按兼岗上级） | B19/B21 移交 | 组织树兼岗模型完善；硬校验 B21 已收口（主角色+兼岗并集校验），缺兼岗上级自动路由。**B33 复核：org_unit 已有 leader_name 但仅为文本展示字段，未与 employee 建立外键与兼岗关系表，路由仍无数据基础** | 组织树二批（兼岗模型） |
| 组织树门店/大区/集团层级写能力 | B33 新增 | B33 三写端点仅放行 org_type='部门'（新建父级必须为门店、编辑不可改类型/编码、跨店移动联动 store_code+region）；门店/大区/集团节点的增改停仍需手工改库或走 store 域，缺层级重挂与子树级联规则 | 组织树二批 |
| 组织节点物理删除与编码回收 | B33 新增 | B33 只做停用（status 启停 + 停用原因必填），不提供 delete；物理删需先定义引用检查（employee.org_code / 审批链 / 历史单据）与 org_code 回收策略，否则会产生悬挂引用 | 组织树二批 |
| 免打扰时段策略（静默时间段/加急例外） | B21 新增 | notify_preference 已就绪，需加时段字段与 Job 判断 | B22+ |
| prod 8 月已办结单 sign_tier 旧阈值错配 15 笔 | B20 回溯 | refund 4 + card_cancel 11（8/16、8/20 旧阈值遗留）；改已办结单与审批轨迹矛盾，需历史迁移专项连同轨迹一并处理 | 数据治理专项（不紧急） |
| 赠金高级规则（满赠阶梯/有效期/报表） | B18 移交 | 营销规则引擎 | 营销收口批 |
| 86 张卡开卡首笔流水修复 | B17 移交 | 售卡开卡链路排查 | B21（数据修复） |
| 去划扣项目智能预填 | B17 移交 | 卡项-项目映射 | 交易收口批 |
| 撞单合并 | business-flows | 客户查重/合并规则 | 客户域批 |
| 资产转移 / 合同 | business-flows | 卡资产模型 | 交易域批 |
| 超管 Impersonate（ impersonation 登录） | business-flows | RBAC 扩展 + 审计 | 平台批 |
| ~~排队智能候补~~ | business-flows | **B29 已闭合（2026-09-10，arrival_waitlist 新域：WL 单号/手机号锚定会员·散客快照两态/WAITING→NOTIFIED→FULFILLED·CANCELLED、号源释放同事务 FIFO 递补+店长站内信幂等、/queue 第三卡+登记弹层+KPI，同批附带候诊超时自动释放与核销↔预约/划扣勾连，网关 24 项+浏览器两页取证 audit 314–331，详见 DELIVERY-P5-B29）** | ✅ B29 |
| ~~转介绍到期重分配~~ | business-flows | 营销渠道 + 定时任务；**B22 复核：转介绍页 /m5-referral 不在 9 页内仍为 mock，老带新数据未接真实客户关系，维持 Backlog** | 营销二批 |
| 私域自动化 Flow（随访/SOP/关怀/召回） | business-flows | 流程引擎 + 营销；术后随访 SOP 引擎及随访工作台/SOP 编排两整页（诊疗侧）B30/B31 已闭合，本行余营销侧关怀/沉睡唤醒/复诊召回 Flow | 远期营销阶段 |
| 角色管理页（RBAC 可视化配置） | permission-matrix | 后端 RBAC 已就绪，缺前端管理页 | 组织收口批 |
| ~~营销 9 页交付验证收口~~ | 盘点 | **B22 已闭合（2026-09-08，9 页逐页网关+浏览器验证、零控制台报错，详见 DELIVERY-P5-B22）** | ✅ B22 |
| 外部广告渠道接入（美团/抖音/小红书/大众点评/新氧投放回传） | B22 移交 | m5-channel 7 平台卡片 + m5-roi 渠道区现为前端 mock；**B26 已落通用回传骨架**（ExternalChannelController：HMAC-SHA256 签名/±300s 窗口/限流 60/min/幂等 bizRef/客户硬校验/channel_returnback 落库，双栈冒烟取证）；仍需各平台 OAuth 对接 + 投放 spend 落库 + ROI 联调 | 外部集成批（T3） |
| 营销归因模型（首次触点/末次触点/多触点加权） | B22 移交 | m5-roi 归因区现为 mock；依赖客户触点事件流（浏览/咨询/到店/成交）埋点归集，现触点数据未采集 | 数据仓库批（T2） |
| 素材库对象存储（S3/MinIO 替换本地/静态资源） | B22 移交 | m5-assets 10 素材现为种子静态数据；需文件上传服务 + 桶策略 + 素材审核流 | 平台批 |
| 海报图片真实渲染（模板+二维码+推荐人工号合成） | B22 移交 | m5-poster 6 模板为静态展示；需后端渲染服务（如 html2image/画布合成）+ 分销二维码绑定 referrer | 营销二批 |
| 直播/短视频写接口与成交回写 | B22 移交 | m5-live 7 场/挂链成交 ¥471,400 为种子只读数据；需直播排期写接口 + 挂链订单成交回写 + 审计四件套 | 营销二批 |
| 营销总览导出报告 / 订阅周报 | B22 移交 | m5-dashboard 651,030 触达漏斗真实只读；需报表导出（Excel/PDF）+ 订阅定时推送（可复用 B20 通知中心） | 营销二批 |
| 营销设置页 /m5-settings 接线 | B22 盘点 | 不在 B22 9 页内，保持 🔧；规则参数（周频上限/违禁词库/兜底门店）现走配置文件，缺前端管理页 | 营销二批 |
| 赠金高级规则（满赠阶梯/有效期/报表） | ✅ B26 | GrantRule/GrantService/GrantExpireJob 真实闭环（规则 CRUD + 发赠金账本 + 过期批处理 + 报表）；收银台抵扣列下游 Backlog | 营销二批 |
| 赠金「收银台抵扣」（status VALID→USED） | B26 新增 | customer_grant 账本已就绪（VALID/EXPIRED/USED），缺收银台选赠金→核销→余额扣减→USED 写链路与对账 | 交易-营销联动批 |
| 通知真实运营商网关（短信/企微/邮件） | B26 新增 | 现 dev 网关未配时诚实 SKIPPED、配置 URL+密钥后 POST 联调（冒烟以容器化 mock 取证 200/4xx/拒连三路径）；缺各厂商签名/模板审核/回执与投递状态回写 | 外部集成批（T3） |
| 通知免打扰员工个人级时段 | B26 新增 | B26 实现全局配置版（meiyun.notify.quiet.*）；个人级需 notify_preference 加时段字段 + 前端设置页 | 组织/通知收口批 |
| 标签自动化规则（按消费/肤质/行为/价值/医疗规则自动打标、摘标与规则审计） | ✅ B25 | 标签定义/人工打标已闭环；B25 完成规则引擎+定时扫描+审计（TA### 规则 / TagAutoRuleJob / 5 类条件），详见 DELIVERY-P5-B25 | 客户运营自动化批 |
| 消费自动积分与退款回退 | ✅ B25 | 积分账户/流水已闭环；B25 接入 txn 内部只读端点拉取已收款/已退款订单，按积分规则幂等发分与回退（AUTOPOINTS 幂等键），详见 DELIVERY-P5-B25 | 交易-客户联动批 |
| 等级定时批处理（每月 1 号扫描、执行报告、通知、批处理幂等记录） | ✅ B25 | 自动升级服务与规则已就绪；B25 新增 LevelMonthlyJob（每月1号 cron、autoUpgrade 开关收敛、汇总审计），详见 DELIVERY-P5-B25 | 客户运营自动化批 |
| 自动降级引擎（连续未达标/保护期/客户通知/审批策略） | B23 移交 | 当前只升不降；需计算周期事实表、保护月逻辑、降级审计与 KPI 口径；引擎上线前本月降级 KPI 仅统计手工降级 | 客户运营自动化批 |
| 客户详情会员权益真实联动（discount 实际计价 + benefits 可享权益） | B23 新增 | 等级阈值/权益配置已就绪；需订单计价与权益核销链路接入 | 交易计价收口批 |
| ~~积分/标签/会员等级/积分商城接线~~ | 盘点 | **B23 已闭合（2026-09-09，4 卡网关+PG+浏览器验证，详见 DELIVERY-P5-B23）** | ✅ B23 |
| ~~财务边角页（发票/预算/税/日结/核销预收）接线~~ | 盘点 | **B24 已闭合（2026-09-09，财务四页去 mock + 发票 CSV、卡余额时间线/核销双签明细内部投影切真，详见 DELIVERY-P5-B24）** | ✅ B24 |
| ~~工作台/咨询/医师/EMR 会话区接线~~ | 盘点 | **B24 大部分闭合（2026-09-09，工作台 7 项计数真实、咨询草稿接诊、医师 treat 全链路切真；EMR 独立域仍留 Backlog），详见 DELIVERY-P5-B24** | ✅ B24（EMR 除外） |
| ~~EMR 电子病历独立域（病历书写/模板/审签/归档实体与页面）~~ | B24 移交 | **B27 已闭合（2026-09-10，emr_record 独立域全状态机+修订+signEmr/treatDone 联动+EmrView 浏览器全链路取证，工作台「病历草稿」演示项摘除，详见 DELIVERY-P5-B27）**；余 EMR 列表分页/缺参 400 化/模板库见下方新增行 | ✅ B27 |
| ~~EMR 病历模板库 / 列表分页 / 缺参 400 化~~ | B27 新增 | **B30 已闭合（2026-09-11，emr_template 新域+集团 4 种子只读+本店建/停用+模板库弹层九段范文、EMR 列表真分页默认 20/stats 后端聚合、ChineseValidationAdvice 框架异常中文 400，附修 fc97d00 PG 42P18，详见 DELIVERY-P5-B30）**；集团模板运营管理（上架/版本/审核）留 Backlog | ✅ B30 |
| ~~建档实时写 ES + 客户搜索 DB 融合~~ | B27 新增 | **B30 已闭合（2026-09-11，customer_search_event outbox+10s 中继 Job SENT/RETRY/DEAD+熔断 30s 降级 DB+显式 mapping+新索引全量回填+读时 mergeRecentFromDb 融合+DataScope 过滤，网关事件 SENT/7 次 RETRY 自愈/SELF 隔离 [] 实证，详见 DELIVERY-P5-B30）**；**DEAD 事件处置台 B32 已闭合**（2026-09-11，retry/replay/discard 三动作状态机+/search-events 处置台整页+全量重建入口，customer:search:admin 仅授 REGION_MGR，附修异常处理透传+biz_type 扩宽，`f02384b`/`dbf8158`，详见 DELIVERY-P5-B32）；ES reindex 对账治理（ES110 vs PG100 历史漂移 10 文档：ES/PG 文档级 diff 对账与孤儿文档清理）仍留 Backlog | ✅ B30/B32 |
| ~~客情登记扩展字段后端化~~ | B27 新增 | **B28 已闭合（2026-09-10，customer 加 9 列落十类扩展字段：年龄/肤质/诉求/过敏史三列/意向项目/意向等级/预算/沟通要点，白名单+过敏互斥，360 档案 tab 有值才显示 kv 回显，详见 DELIVERY-P5-B28）** | ✅ B28 |
| ~~triage 改派时间线~~ | B27 新增 | **B28 已闭合（2026-09-10，triage_reassign 历史表+reassign 同事务追加+读模型批量富化+接待台最小 UI 时间线，详见 DELIVERY-P5-B28）** | ✅ B28 |
| ~~术后随访 SOP 引擎（随访计划编排/超期扫描/回访记录）~~ | B24 移交 | **B30 已闭合引擎（2026-09-11，followup_sop_template/node/batch/followup 四表+treat-done AFTER_COMMIT(REQUIRES_NEW) 幂等排程 1/3/7/30 天节点+FollowupSopDueJob 60s 超期升级店长幂等通知+/api/txn/followup 五端点+工作台两演示项摘除，附修 fa549d5，详见 DELIVERY-P5-B30）**；**B31 已闭合整页与手工建（2026-09-11，/followup 随访工作台整页接真+手工建随访端点+stats 九键+keyword 模糊，/sop SOP 编排整页+模板节点五动作+批次聚合看板+summary 五键+一键升级，FollowupSopEscalator 共享组件，附修 d130dfc 空库 AVG NPE，详见 DELIVERY-P5-B31）** | ✅ B30/B31 |
| 沉睡客户唤醒 / 复诊召回自动化 | B24 移交 | 工作台「复诊待提醒」演示项依赖营销自动化 Flow，仍远期；**其中「复购提醒前端未接」已由 B30 闭合**（RepurchaseView 全链路+三方签核，`7c922cb`，ApprovalService 多级审批见末行） | 远期营销自动化批 |
| ~~接待台 / 候诊接待域~~ | B24 移交 | **B27 已闭合（2026-09-10，到店五态队列+分诊建 CP 草稿+预约/客情自动到店+工作台「候诊待接待」演示项摘除，详见 DELIVERY-P5-B27）**；余排队智能候补/超时自动释放**已由 B29 闭合**（ArrivalAutoReleaseJob 30min+10min 宽限 60s 扫描、系统释放同事务 FIFO 递补，详见 DELIVERY-P5-B29）；到店核销见下条 B28 已闭合 | ✅ B27/B29 |
| ~~到店核销 /m2-checkin~~ | B27 明确 | **B28 已闭合（2026-09-10，checkin_record 独立域 CI 单号池+三方式/三态/四异常码、确认核销/标记异常/解除异常+timeline+KPI/过滤、by-phone 本店→公海→404 锚定、当日同号 PENDING 幂等、手机号掩码出域，网关+浏览器全链路取证 audit 301–313，详见 DELIVERY-P5-B28）**；与预约/writeoff 自动勾连**已由 B29 闭合**（APPOINTMENT 命中当日预约同事务四连写 appt/wd/ci/arrival+核销详情透出勾连单号，audit 314–331，详见 DELIVERY-P5-B29） | ✅ B28/B29 |
| 通用异常中心（跨域异常单据统一归集与处置工作台） | B24 移交 | BOM 异常等现为各域分散后端 only；财务税/日结/异常页 B24 已切真但无统一处置中心 | 平台/交易收口批 |
| 异常账务处置登记（长短款/错账登记与审批闭环） | B24 移交 | 财务台账真实但缺异常账务专门登记审批流 | 财务收口批 |
| 预收合规监控（合规指标/挂账时长监控预警） | B24 移交 | 核销/预收明细 B24 已切真可读，缺合规监控规则与预警 | 财务收口批 |
| 进项税抵扣链路 | B24 移交 | 当前发票/税页仅覆盖销项与台账视角，进项抵扣诚实未实现 | 财务收口批 |
| M4Repurchase 复购方案签核 / 多级审批 | B24 移交 | **单据内客户（预填）/经办/店长三方签核 B30 已闭合**（RepurchaseView 知情同意硬前置+三方签核填齐解禁生效+REPURCHASE/TRIPLE_SIGN 审计，`7c922cb`，详见 DELIVERY-P5-B30）；医师/店长/财务 ApprovalService 多级审批流仍留 Backlog | 诊疗域收口批 |
| seed 端到端造数/清理脚本工具化（幂等） | B24 移交 | B24 手工造数残留（CP…004/005/000006、OD…000003、PM…000004、SC026）已在 DELIVERY-P5-B24 §5.2 登记 | 测试体系批（不紧急） |
| setup-seed-db.sh 全量重建保留 audit_log 选项 | B31 新增 | 脚本约 L674-675 TRUNCATE 清单含 audit_log，B31 全量重建致冒烟审计不存活（终态 36 行全 CONSUMABLE 播种基线），audit append-only 取证口径受影响；需增补「保留 audit」开关（如按 bizType 导出/跳过 TRUNCATE） | 测试体系批（不紧急） |
| SOP 多模板 / 门店级模板 / 节点拖拽排序 | B31 新增 | 现集团通用单模板 SPT-SEED-001（store_code 空），节点 lineNo 按 dayOffset 自动 renumber；门店自建模板、模板上下架/版本管理、手工拖拽排序未做 | 诊疗域后续批 |
| 随访结果结构化分析（满意度趋势 / 不良反应专项处置台） | B31 新增 | stats 已提供 avgSatisfaction/adverseCount 实时计数，趋势分析与不良反应（adverse）专项处置工作台未做 | 数据/诊疗后续批 |
| M2 门店运营平台（13 页） | 远期阶段 | 排班/工单/日结/申购/报损/绩效等 | 远期 M2 |
| M3 客户运营平台（10 页） | 远期阶段 | 分群/旅程/自动化 | 远期 M3 |
| M1 集团管控（14 页） | 远期阶段 | 集团-门店多租户 | 远期 M1 |
| A1 AI 中心（15 页）/ T4 AI 算力（4 页） | 远期阶段 | AI 能力接入 | 远期 A1/T4 |
| T2 数据分析（4 页）/ T3 外部集成 | 远期阶段 | 数据仓库 / 开放 API | 远期 T2/T3 |
| C 端移动端（20 页） | 远期阶段 | mp-uniapp，2 页已真实 | 远期移动端 |

---

> **更新约定（铁律 9）**：每批 commit+push 后、汇报前，更新 §① 仪表盘数字、§② 相关模块状态与批次、§③ 时间线行、§④ Backlog 增删；交付汇报必带「整体完成度 X/Y 模块（Z%），本批后新增完成 N 个」。
