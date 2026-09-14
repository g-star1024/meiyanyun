# 美研云门店中台 · 自动开发接力哨兵（HANDOFF-AUTO）

> 用途：铁律 11 自主续跑的唯一事实源。任何接续会话/定时任务先读本文件，再按「开发前 checklist」执行。
> 维护规则：每批开工改 ACTIVE+心跳；批末（或中断前）改 DORMANT+存档。心跳格式 `YYYY-MM-DD HH:mm CST`。

<!-- MACHINE:STATUS=ACTIVE -->
<!-- MACHINE:HEARTBEAT=2026-09-14 22:40 CST -->
<!-- MACHINE:BATCH=P5-B49 -->
<!-- MACHINE:CARD=B49 卡6 批中回写已闭合 docs `待补CARD` 已推送 → 卡7 集团大屏 /m1-screen 侦察 b49-c7-recon 进行中（队列：卡7 大屏 → 卡8+ 全新域五页；分组定案见 DELIVERY-P5-B49 §2.3） -->

## 当前状态（人读区）

- **批次**：P5-B49（M1 集团管控 14 页批，C 方向）
- **阶段**：卡0 落账纠错+卡1 侦察+卡2 审计日志接真（feat `43f52ca` 已推送、三轨对账 total=553 一致、批中回写完成）+卡3 区域+门店主数据读侧接真（feat `494c272` 已推送）+卡4 集团聚合端点+概览/矩阵/对标三页接真（feat `f672a80` 已推送、/finance/group-overview 铁律 -1-D 跨店例外域、三轨真验全绿、E011 华东域默认期 2026-09 域收窄正确非 bug）+卡5 采购补全（feat `a108203` 已推送：store-service 新建 procurement 域 supplier+PO 四表+收货、六态状态机/审批阈值/库存联动移动均价、前端 /m1-procurement view 零改动切真、三轨真验全绿）+卡6 SOP /m1-sop（feat `d4c6453` 已推送：store-service 新建 sop 域模板+步骤+任务三表 ddl-auto 建表+种子、SopController 八端点、/m1-sop 切真实 API、三轨真验全绿）+批中回写均已闭合，**卡7 集团大屏 /m1-screen 侦察 b49-c7-recon 进行中**
- **上一批**：P5-B48 技术债纯还债批四卡全部闭合入库（见文末「P5-B48 闭合存档」）

## 批概览（B49）

- 入口台账：02-modules「| M1 集团其余 6 页 | ⬜ | — | — | 远期 M1 |」（已拆出 ✅ 八行：审计日志、区域管理、门店主数据、集团经营概览、指标矩阵、门店对标、采购管理、标准作业SOP；行号随插入漂移不落死值）
- 卡1 侦察已确权：17 条 M1 路由=3 真实（采购半真实：仅库存台账接真，供应商/PO 仍 mock）+13 个 M1 专属 mock 页+1 共享 SettingsView；台账「14 页」=13 mock+/m1-settings，三清单详见 DELIVERY-P5-B49 §2
- 集团多店对比后端聚合：走**跨店例外域（铁律 -1-D）**，禁止在单店域内泄漏跨店查询
- 完成度影响：B49 起每闭合一页/一组，94/166 完成度递增（B48 纯还债批数字不变系正常）

## 开工基线

- 代码 HEAD=`d4c6453`（B49 卡6 已推送）
- docs HEAD=`待补HEAD`（卡6 批中回写已推送）
- 后端 19 服务 + 网关全部在线（`bash /tmp/meiyun-health.sh` 复核）；前端 dev http://localhost:8080
- 登录：curl 通道 POST `https://127.0.0.1:8443/api/org/auth/login`（curl -k，E011/meiyun123=REGION_MGR，token 存 /tmp/meiyun_token.txt，验证前重新登录）；Chrome 通道 http://localhost:8080 手工填表（快捷登录已关闭）

## 下一步动作（卡0 纠错 → B49 序列）

0. ✅ 卡0 已闭合（纯文档纠错，代码与四 feat 未动）：f2181aa 把卡2 写成「孤儿清理/漂移归零」失实。正确口径（git show 无 delete+commit body+审计 id=567/568+PG customer=13 四重证据）：missing 逐条 upsert 自动补齐 es110→113 二跑幂等；**orphan 仅 diff 清单+log.warn 待人工绝不删除，两轮恒=100（SC* 共享索引拓扑预期）**；「漂移 10」系 seed-only 口径误判，权威源=seed∪core=113。DELIVERY 与哨兵存档卡2 行由 11:38 接力实例先行修正，roadmap 三处（02-modules L33/03-timeline B48 行/04-backlog L76）由本实例补齐，随卡1 侦察落盘一并 docs 提交。
1. ✅ 卡1 侦察：17 条 M1 路由=3 真实（采购半真实）+13 mock+1 共享 settings；后端 78 Controller 摸排完成；分组序列定案 DELIVERY-P5-B49 §2.3
2. ✅ 卡2 审计日志 /m1-audit-log 已闭合（feat `43f52ca` 已推送：audit-service +GET /page 五过滤服务端分页+/facets 两新端点，原全链 List 端点零改动；前端 m1Audit store 接真去 seed，三轨对账 total=553 一致；/verify 如实显历史断链 #380，全量断链清单增强已登记 04-backlog 平台治理批）
2.1. ✅ 卡3 区域管理+门店主数据 /m1-region+/m1-tenant 已闭合（feat `494c272` 已推送，6 前端文件 +248/-653）：api/org.ts 扩 Store 四可选字段+StoreRegionDist+listStoreRegionDist（铁律 1 只加不改）；m1Tenant/m1Region 两 store 去 mock/seed/写侧只读化；两 view 重写（门店页 7 列+动态筛选+KPI 含已关店+三态 emptyText+页脚注；区域页 KPI 四卡+六区卡体统计+三态）；domain-home 中性化。三轨真验全绿：build exit 0；curl 三端点与 PG 对账逐区吻合（23=营业18+筹建3+关店2，六区 6/5/4/4/3/1）；Chrome E011 门店页 6 行（华东 DataScope 域收窄正确）、区域页 6 卡 KPI 6/6/23/18、两页 console 零 error 零 warn
2.2. ✅ 卡4 集团聚合端点（铁律 -1-D 跨店例外域）+概览/矩阵/对标三页接真去 mock 已闭合——feat `f672a80`（8 文件，+407/-154）已推送：FinanceController 新增 GET /api/finance/group-overview（DataScope.storeSpec("storeCode")、months/rows/monthTotals LinkedHashMap 聚合、parallel 空安全）；api/finance.ts 只加不改（+GroupMonthTotal/GroupOverviewView/listGroupOverview）；三 store（m1Overview 143/m1Matrix 150/m1Compare 154）全量重写（真实端点单源、null 显「—」不伪造、fen2wan/score 权重归一+负截 0）；三 view 23 处接线+页脚注（数据源+口径规则+并列取最新）。三轨真验全绿：vue-tsc 0 error；vite build×2+docker cp；PG 2026-07 上海旗舰店营收 280000000 分/grossRate 0.476 精确吻合；Chrome 矩阵 2026-07↔2026-09 双向切换（280/47.6 全「—」）·/m1 hero 0.5万元·/m1-compare score 0 归一+500/0.5·55/-198.9，三页 console 零 error 零 warn。E011 华东域默认期 2026-09 系域收窄正确非 bug（仅 ST-SH-001 有月报，并列取最新）。脚注口径自发现自修复 4 处（硬编码「2026-07 为最新」→generic「并列取最新，随数据域而定」）。
2.3. ✅ 卡5 采购补全 /m1-procurement 已闭合（feat `a108203` 已推送，15 文件 +1425/-80）：store-service 新建 procurement 包 13 文件（Supplier/PurchaseOrder/PurchaseOrderItem/GoodsReceipt 四表+收货、PoNoGenerator、ProcurementDataInitializer 种子）；六态状态机 DRAFT→SUBMITTED→APPROVED/REJECTED→PARTIAL→RECEIVED（驳回回 DRAFT 带 rejectNote、作废仅 DRAFT/SUBMITTED）；审批阈值门店 ≤5000/区域 ≤50000/集团 >50000 元；收货联动库存+移动均价（真验 (99×1500+100×1200)/199=1348 分）；批次号 PO<poNo>-R%02d-<lineNo> 幂等；resolveReadStoreCode 三段守卫与 consumable 端点逐条一致。前端 api/procurement.ts 9 端点新建+m1Procurement store 重写切真（view 零改动）。三轨真验全绿：双栈 docker cp 部署、种子 4 供应商/6 PO 六态/2 收货单、curl 全分支 201/409/400/422×4/404×2/403 中文、库存对账 SST02 HC-001 50→90/SST03 HC-002 30→80/HC-006 20→120、审计种子 21 条、Chrome 三 tab 真实渲染 console 零 error 零 warn、audit_outbox 无新增。真验数据 SUP-T1/PO1 RECEIVED/PO2 CANCELLED/PO3 DRAFT/PO4 APPROVED（含 GHOST-1 行）append-only 留存）→ 卡6 SOP → 卡7 大屏 → 卡8+ 五页全新域
2.4. ✅ 卡6 SOP /m1-sop 已闭合（feat `d4c6453` 已推送，11 文件 +1283/-10）：store-service 新建 sop 包 9 文件（SopTemplate/SopTemplateStep/SopTask 三表 ddl-auto 建表+SopDataInitializer 种子）；SopController 八端点（模板列表/新建/publish 发布、任务列表/按模板生成、start 开始、steps/{stepRef}/toggle 步骤逐项勾选、complete 完成）；前端 api/sop.ts+m1Sop store 切真。三轨真验全绿（curl 状态机 16 断言+权限三角 9 断言+审计 17 行、种子 PG 对账、Chrome 双 tab console 零 error/warn、mvn+vue-tsc+vite build）；批中回写 docs `待补24` 已推送）→ 卡7 大屏 → 卡8+ 五页全新域
3. ⬜ 每卡：铁律 7 三轨真验（curl+PG/Chrome+构建）→ 铁律 6 构建 → 铁律 8 一卡一 feat commit+push
4. ⬜ 批中：02-modules M1 区逐页翻 ✅、01-dashboard 数字递增、03-timeline 逐卡加行
5. ⬜ 批末：DELIVERY-P5-B49 + 五分册回写 + docs 原子提交 + 铁律 9 汇报 + 哨兵改 B50

## 中断恢复指引

- 心跳 <15 分钟：另一实例活跃，**只读不接管**，直接退出
- 心跳 ≥15 分钟且 STATUS=ACTIVE：前实例中断，从「下一步动作」第一个 ⬜ 续跑
- 429/模型上限：刷新本文件心跳后退出，等每小时定时任务重试
- 每卡开工前必须重读本文件 + 铁律 10 全读五分册

## P5-B48 闭合存档（2026-09-14）

- 卡1 审计写失败补偿/对账监测 feat `4bfb658`（14 文件：outbox+中继+对账监测三道防线，KB-DOC-7 幻影审计观察项复核登记，历史断链 id=380 append-only 不可篡改存量保留）
- 卡2 ES reindex 对账治理 feat `9c1fd6a`（4 文件：文档级 diff 三清单——missing 逐条 upsert 自动补齐、orphan 仅报告+warn 待人工不自动删（两轮恒=100，SC* seed/app 共享索引拓扑预期）+6h 定时巡检；M001–M003 首跑补齐 es110→113、二跑幂等；原「ES110 vs PG100 漂移 10」系 seed-only 口径误判，权威源 seed∪core=113）
- 卡3 marketing ddl-auto update→validate feat `188c90b`（1 文件：18 表零 drift，现网+scratch 全新库双场景一次通过）
- 卡4 推理模型 max_tokens 遵从度+前端日期统一锚定 Asia/Shanghai feat `14cdd8f`（40 文件 +146/-70：finish_reason=length WARN/空回答异常双分支，lenient ping 实证 128>8 仍 SUCCESS；时区实际 56 处 slice，登记 21 处系低估本批更正）
- 纯还债批：完成度 **94/166=约57%、🔧2、⬜70 均不变**；勾销 04-backlog 四行（L121 审计静默监测、L76 尾 ES 对账、L97 ddl-auto validate、L117 中段时区推广）
- 交付文档：DELIVERY-P5-B48-2026-09-14.md

## P5-B47 闭合存档（2026-09-14）

- 卡8 AI 知识库检索闭环 feat `b26d52e`（检索事件处置台+zhparser/pg_trgm 分词+附件版本审批流）
- 完成度 94/166=约57%、🔧2、⬜70
- 交付文档：DELIVERY-P5-B47-8-2026-09-14.md
