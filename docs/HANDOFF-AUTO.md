# 美研云门店中台 · 自动开发接力哨兵（HANDOFF-AUTO）

> 用途：铁律 11 自主续跑的唯一事实源。任何接续会话/定时任务先读本文件，再按「开发前 checklist」执行。
> 维护规则：每批开工改 ACTIVE+心跳；批末（或中断前）改 DORMANT+存档。心跳格式 `YYYY-MM-DD HH:mm CST`。

<!-- MACHINE:STATUS=DORMANT -->
<!-- MACHINE:HEARTBEAT=2026-09-15 17:14 CST -->
<!-- MACHINE:BATCH=P5-B50 已闭合存档（2026-09-15）；下一指 P5-B51（M1 调度六缺口 04-backlog L140-145，用户已拍板顺序②，开工先侦察数据源就绪度） -->
<!-- MACHINE:CARD=P5-B50 平台治理/还债批九卡全部闭合：卡2 da3d812（L127）、卡3 cbd6692（L129）、卡4 3a263ab（L132）、卡5 7f08103（L146）、卡6 aecf97f（L122）、卡7 5251306（L134）、卡8a dd4307a（L147）、卡8b 零代码（L124）、卡8c 43f009b（L148）；B49 四项观察项（登录壳四路401/#380全量断链清单/审计真实IP/大屏UTC）全清零；纯还债数字不变 ✅108/166≈65%、🔧1、⬜56，域⑧ 34✅2🔧31⬜；04 九行勾销 L122/L124/L127/L129/L132/L134/L146/L147/L148（L123/L133 保留登记不动工）；DELIVERY-P5-B50-2026-09-15.md 14 章；append-only 留档 prod compliance_check id=1 PRIVACY FAIL、审计 id588/589/590、seed id180-186。代码 HEAD=43f009b，批末 docs 原子提交见 git log。P5-B51 开工：哨兵改 ACTIVE+刷新心跳，铁律 10 全读五分册，逐卡侦察 04 L140-145 六缺口数据源（DEVICE/URGENT/durationMin/DONE/班次表/派单改期），无源继续诚实空态 -->

## 当前状态（人读区）

- **批次**：P5-B50 平台治理/还债批 **2026-09-15 17:14 已批末闭合存档**（九卡 8 commit+卡8b 零代码）；哨兵 DORMANT
- **阶段**：九卡全部闭合 push——卡2 `da3d812`（L127 采购越权）、卡3 `cbd6692`（L129 大屏时区）、卡4 `3a263ab`（L132 target 行级 DataScope）、卡5 `7f08103`（L146 登录四门控）、卡6 `aecf97f`（L122 verifyChain 全量断链清单 prod [380,520]）、卡7 `5251306`（L134 真实客户端 IP）、卡8a `dd4307a`（L147 耗材越权+库存 watch）、卡8b 零代码（L124 SE101 GROUP 口径核实）、卡8c `43f009b`（L148 actor 按通道强制）。B49 四项观察项全部清零。批末：04 九行勾销、五分册回写、DELIVERY-P5-B50-2026-09-15.md 已成稿并同批原子提交。
- **下一指 P5-B51（用户已拍板顺序②）**：M1 调度六缺口（04-backlog L140-145：DEVICE 设备档案 / URGENT 加急源 / durationMin 真实时长 / assignment DONE 完成态 / 医生房间班次表 / 派单时段自由度改期）；多依赖跨域新数据源，**开工逐卡只读侦察数据源就绪度，无源继续诚实空态**，白天先建议后拍板。
- **保留登记（B50 未动工，随后续批次评估）**：L133 impersonate＝大（JWT act/realSub claim+meiyun-security 全服务回归+前端换 token，与 L46 合并）、L123 报告哈希验真 UI＝中（content_hash+规范化字节口径，DSAR/consent 远期）。非本批：L89 setup-seed-db 保留 audit_log、L128 ai-service seed Flyway V17-V29 悬置。
- **记账口径**：纯还债批照 B36/B38/B40/B48 先例——完成度数字不变（✅108/166≈65%、⬜56、🔧1、域⑧ 34✅2🔧31⬜），只勾 04-backlog 行、03-timeline 加行、00-history 顶部加简报。
- **上一批**：P5-B49 M1 集团管控屏批全 12 卡闭合（M1 17/17 全切真，见文末存档）

## 开工基线

- 代码 HEAD=`0b3dfb0`（B49 卡12 收官）；总 HEAD=`ceefb8b`（B49 哨兵闭合 docs），工作区 clean，均已 push origin/main
- 后端 19 服务 + 网关在线（卡1 开工用 `bash /tmp/meiyun-health.sh` 复核）；正式前端 http://localhost:8080 / 网关 8443；seed 前端 127.0.0.1:18080 / 网关 18443
- 登录：curl 通道 POST `https://127.0.0.1:8443/api/org/auth/login`（curl -k，E011/meiyun123=REGION_MGR 华东域，token 存 /tmp/meiyun_token.txt，验证前重新登录）；Chrome 通道 http://localhost:8080 手工填表（快捷登录已关闭）
- 铁律 10 开工读数：索引+00～04 五分册本批开工前已全部整读
- docs 提交坑：`docs/` 被 .gitignore 整体忽略；已跟踪文档用 `git add -u docs/`，仅新增 docs 文件用 `git add -f`；Grep 工具对 docs 超长中文行失效，用 shell `grep -rn` 或 Read

## 下一步动作（B50 卡序列）

0. ✅ 卡0 哨兵激活（本 docs 提交）：五分册全读 → STATUS=ACTIVE、心跳 12:45、BATCH=P5-B50、卡序列落盘
1. ✅ 卡1 链路先行只读侦察（铁律 -1，不动代码）：procurement 三段守卫 / m1Screen paidAt slice / BizTarget DataScope 已完成，改法清单落盘
2. ✅ 卡2 L127：采购组合域列表 store_id 强制过滤+越权用例（`da3d812`，curl 跨域本域真验）
3. ✅ 卡3 L129：大屏成交流时间 Asia/Shanghai（`cbd6692`，复用 utils/datetime，Chrome+PG 零误差）
4. ✅ 卡4 L132：BizTarget 列表 DataScope 行级过滤+越权用例（`3a263ab`，E011 四角真验）
5. ✅ 卡5 L146：数据层按 token 门控预拉取+登录后补拉+stores inflight 去重（`7f08103`，Chrome 无 token 零请求/登录三路各 1 次 200/硬刷新 200，双栈部署）
6. ✅ 卡6 L122（`aecf97f`，5 files +201/-8）：verifyChain 遍历全链沿存储 curHash 推进返回全量 breaks（brokenAtId 首处口径保留）；AuditServiceVerifyChainTest 3 用例（篡改+重链同型、断链在下游暴露）；前端断链清单卡。三轨：prod breaks=[380,520]/569、seed []/117
7. ✅ 卡7 L134（`5251306` 已 push，7 files +364/-7）：Go 网关 router.go clientIPFromRequest/remoteHost+Director 规范化（X-Real-IP=解析客户端、XFF 追加直接对端 peer，直连采 RemoteAddr），router_test.go 9 用例；meiyun-security 新增 ClientIp（XFF 首段→X-Real-IP→remoteAddr→"unknown"）+ClientIpTest 6 用例（JDK 动态代理 fake，零新增依赖）；ComplianceController 注入 HttpServletRequest、ComplianceService.recheck 加 clientIp 参去 "web"，ComplianceServiceRecheckIpTest 2 用例。共 20 测试用例全绿。双栈部署：交叉编译重建 meiyun/gateway:latest 双栈 recreate、audit 胖包 docker cp 双栈重启均 healthy。三轨真验全绿：curl 四场景（经 nginx/直连伪造 XFF 203.0.113.77/无头兜底，seed id180-182、prod id588-589）、PG payload.ip 全部真实非 web、Chrome 浏览器内复检 seed id183 actor=冯区域 ip=192.168.65.1，审计页渲染 121 条「哈希链完整」；seed verify ok total=121 breaks=[]、prod 571 仅历史断链[380,520] 新行均正常延长链。**prod 有临时验真数据需批末记录：meiyun_core.compliance_check 手插 id=1（PRIVACY「L134 平台治理验真临时项」，现 FAIL），审计 id588/589**
8. 🔄 卡8 平台类小项收口（依据批次授权「各卡平台类小项…开始处理吧」自主口径：小项本批闭合、中大项登记 backlog 不动工）：
   - ✅ **卡8a 耗材列表/流水越权（同源还债，`dd4307a` 已 push，5 files +179/-39，2026-09-15 16:05 三轨闭合）**：ConsumableRepository/ConsumableMovementRepository 改继承 JpaSpecificationExecutor 删除 `cast null` 全量退化 JPQL；ConsumableService 两列表方法以 DataScope.storeSpec("storeCode") 为基座（"__NONE__" 哨兵短路、显式参叠加、档案 Sort ASC skuCode/流水 Sort DESC id），写路径未动；新增 ConsumableServiceScopeTest 6 用例，store-service 全量 12 绿。双栈 docker cp+restart healthy。三轨：curl seed E011（REGION stores=[SST01,02,06]）档案无参 36→18、越界 SST03=0、本域 SST01=6；流水无参 38→18（5+7+6）、越界=0、本域 SST02=7；无 token 401；prod E011 档案 2/流水 4 均 ST-SH-001。PG 对账 18/18/2 全符。Chrome 库存页切换器仅现在域三店。**附带发现并同批修复前端陈旧缺陷**：InventoryView 漏挂门店切换 watch（OrderView/Writeoff/AppointmentBoard 三页均有范式），切店不重拉耗材/配方/异常；补 watch（inv.seed(true)、STORE 范围 loadBoms(true)、loadExceptions(true)），npm build 过、双栈前端部署，Chrome 实测浦东 SST02（库存90/总值¥16,020）切徐汇 SST01（50/¥5,700/缺货1）无刷新即重拉，四路 XHR 全带新店码 200，console 零告警。
   - ✅ **卡8b L124 GROUP 全域口径（零代码，2026-09-15 16:20 三轨闭合）**：侦察阶段核实 EXTRA_STAFF SE101 周岚在 meiyun_core/meiyun_seed 双库 staff 均已播种为 SUPER_ADMIN/store_code=null/region=null/在职，登录实测 scope=GROUP、stores=[]、perms=*（AuthController ROLE_SCOPE SUPER_ADMIN→GROUP、resolveVisibleStores GROUP 返空=DataScope 全量）。L124 原文含「增补账号 **或** 明确 SUPER_ADMIN 全量域口径」两路径，既有账号已合格，新增 SE106 既冗余又多一个弱口令账号故不新增。/finance/group-overview（DataScope.storeSpec 基座）三口径真验对齐 PG 基线：seed SE101 全域 7 行/4 店（SST01/02/03/06，8月4店¥1,259,000,000）vs E011 华东 6 行/3 店（SST01/02/06，8月¥260,000,000，差额恰为越界 SST03 的 ¥999,000,000）；prod SE101 全域 7 行/5 店（全 ST-*）vs E011 2 行/仅 ST-SH-001；双栈匿名 401。批末 04 L124 按「明确口径+SE101 真验」勾销。
   - ✅ **卡8c AuditController actor 自报缺陷（`43f009b` 已 push，1 file +9/-2，2026-09-15 16:26 三轨闭合）**：append 按鉴权通道强制 actor——`"system".equals(DataScope.currentActor())`（X-Internal-Token 系统身份，业务服务已在本地 SecurityContext 代填真实操作人）沿用 req.actor() 且空值兜底 "system"；否则（员工 JWT 直连，合规 impersonate 留痕）强制取 JWT staffId 忽略请求体 actor；AppendRequest.actor 去 @NotBlank。mvn package audit-service 胖包双栈 docker cp+restart healthy。五通道真验（B50_8C_TEST 永久留痕）：T1 伪造 actor=FORGED_E011→PG 落 SE101、T2 不传 actor→SE101、T3 匿名→401、T4 内部 token 代填 E011→沿用 E011（宿主 18084 直连绕网关）、T5 错内部 token→401；prod 同构 P1 伪造 HACK→落 SE101（id590）；seed verifyChain ok total=124 breaks=[]，prod 断链仍仅历史 #380（卡6 已治理为清单展示，新行正常延链）。
   - 📝 **卡8d backlog 登记（批末回写 04）**：耗材越权新增行并随闭合勾销；L133 impersonate＝大（JWT 自包含被 5+ 服务本地验签，需加 act/realSub claim+meiyun-security 全服务回归+审计 actor 口径全局统一+前端 auth store 换 token，远期独立大项）；L123 报告哈希验真 UI＝中（report_job 加 content_hash 列+生成端 SHA-256+验真端点+前端卡，关键坑 CSV BOM/时间戳致同参数重导字节不同，须先定义规范化字节口径；DSAR/consent 远期不同批）。
9. ⬜ 每卡：铁律 7 三轨真验（curl 经网关 + PG/Chrome + 构建）→ 铁律 8 一卡一 feat commit 紧跟 push
10. ✅ 批末（2026-09-15 17:14）：DELIVERY-P5-B50-2026-09-15.md（14 章 170 行）+ 五分册回写（00 顶部简报/03 表底 P5-B50 全行/04 九行勾销/02 十处挂载备注/01 L18+L36 两处尾接数字不动）+ 同批原子 docs 提交 + 铁律 9 汇报 + 哨兵 DORMANT/B51（本提交）

## 中断恢复指引

- 心跳 <15 分钟：另一实例活跃，**只读不接管**，直接退出
- 心跳 ≥15 分钟且 STATUS=ACTIVE：前实例中断，从「下一步动作」第一个 ⬜ 续跑
- 429/模型上限：刷新本文件心跳后退出，等每小时定时任务重试
- 每卡开工前必须重读本文件 + 铁律 10 全读五分册

## P5-B50 闭合存档（2026-09-15）

- **主题**：平台治理/纯还债批（用户拍板顺序①），卡1 只读侦察+卡2–卡8 共九卡全闭合（8 个 feat commit+卡8b 零代码）；B49 登记四项观察项全部清零
- **完成度**：纯还债批数字一律不变——**✅108/166=约65%、🔧1、⬜56 约34%；域⑧ 34✅ 2🔧 31⬜**
- 卡2 采购列表越权 `da3d812`（3 files +116/-14，DataScope 基座+越权用例，seed E011 6→4/SST03 消失）；卡3 大屏时区 `cbd6692`（2 files +9/-2，shTimeStr 锚 Asia/Shanghai，05:26Z→13:26）
- 卡4 target 行级 DataScope `3a263ab`（6 files +206/-5，canReadTarget 四角真验 7/5/5/10）；卡5 登录四门控 `7f08103`（4 files +52/-23，auth/storeContext/CShellDesktop/notification，无 token 零请求）
- 卡6 哈希链全量断链清单 `aecf97f`（5 files +201/-8，prod breaks=[380,520] 均 B48 修复前历史窗口、seed []/117、卡8c 后 total=124）；卡7 网关真实客户端 IP `5251306`（7 files +364/-7，XFF→X-Real-IP→remoteAddr→unknown，20 单测，合规链路去硬编码 "web"）
- 卡8a 耗材越权+库存 watch `dd4307a`（5 files +179/-39，两 Repository 删 cast null JPQL，seed 档案 36→18/流水 38→18，InventoryView 切店重拉）；卡8b 零代码（SE101 周岚双库 SUPER_ADMIN/scope=GROUP/stores=[]/perms=* 即合格，不新增 SE106；三口径对账差额恰为越界 SST03 ¥999,000,000）；卡8c actor 按通道强制 `43f009b`（1 file +9/-2，五通道真验，P1 伪造 HACK→SE101）
- 批末回写：DELIVERY-P5-B50-2026-09-15.md（14 章 170 行）+五分册（00 顶部简报/03 表底全行/04 九行勾销 L122/L124/L127/L129/L132/L134/L146/L147/L148/02 十处挂载/01 L18+L36 尾接数字不动），同批原子 docs 提交
- **保留登记不动工**：04 L133 impersonate（大，act/realSub+全服务回归+前端换 token，与 L46 合并）、L123 报告哈希验真 UI（中，content_hash+规范化字节口径；DSAR/consent 远期）
- **append-only 留档（不可删）**：prod meiyun_core.compliance_check 手插 id=1 PRIVACY「L134 平台治理验真临时项」现 FAIL；prod 审计 id588/589（卡7）、id590（卡8c P1 伪造）；seed 审计 id180-182（卡7 curl）、id183（Chrome 冯区域 ip=192.168.65.1）、id184/185/186（B50_8C_TEST T1/T2/T4）
- **下一批 P5-B51**：M1 调度六缺口（04 L140-145，用户已拍板顺序②）——DEVICE 设备档案/URGENT 加急源/durationMin 真实时长（现 SKU↔project 名匹配率 0%）/assignment DONE 完成态/医生房间班次表（现固定 09:00-20:00）/派单时段自由度改期（start 现锚 apptTime）；多依赖跨域新数据源，开工逐卡只读侦察，无源继续诚实空态

## P5-B49 闭合存档（2026-09-15）

- **主题**：M1 集团管控屏接真批（C 方向），卡0 落账纠错+卡1 侦察+卡2–卡12 共 12 卡全闭合；M1 集团屏 **17/17 全切真**（3 真实页+14 已切真页+0 mock，M1 批目标达成）
- **完成度**：批初 94/166=约57% → 批末 **✅108/166=约65%、⬜56、🔧1**；域⑧ 平台与基建 34✅ 2🔧 31⬜
- 卡2 审计日志 `43f52ca`（total=553 三轨对账；orphan 只 warn 不删、两轮恒=100 共享索引拓扑；卡0 已纠 f2181aa 失实）
- 卡3 区域+门店主数据 `494c272`（6 前端文件 +248/-653）；卡4 集团聚合三页 `f672a80`（跨店例外域 -1-D GET /finance/group-overview，8 文件 +407/-154）
- 卡5 采购补全 `a108203`（procurement 四表六态状态机/审批阈值/移动均价，15 文件 +1425/-80）；卡6 SOP `d4c6453`（sop 三表八端点，11 文件 +1283/-10）
- 卡7 数据大屏 `815f006`（screen 包 overview+SSE，修复 SSE 认证串扰+nginx 缓冲双缺陷，13 文件 +848/-79）
- 卡8 目标管理 `1f432a2`（biz_target 五指标×三级×四态+idemKey 幂等，8 文件 +820/-54）；卡9 合规中心 `b7baee5`（compliance 域+V31+RECHECK 八键）
- 卡10 健康度巡检 `177a32e`（health 三表、clamp[40,98] 扣分服务端化，12 文件 +808/-90）；卡11 报表中心 `01c7af1`（report 域 BYTEA CSV 异步、R01/R02 真实聚合，13 文件 +1241/-162）
- 卡12 调度中心 `0b3dfb0`（收官，10 文件 +964/-99 跨 txn/store/org 三服务：dispatch_assignment ddl-auto、四端点 resources/jobs/dispatch/release、改派 422/重叠 409/重复 409、权限预置零改动、前端删 urgentJobs 死代码；三轨真验四角全绿）
- 批末回写：DELIVERY-P5-B49-2026-09-14.md 卡12 设计章+交付章（L461-537）+五分册（history/dashboard/modules/timeline/backlog）docs `05f1593`（6 文件 79+/10-，17/17 收官口径全册统一，历史卡 1/17…13/17 记录保留）
- **Backlog 净增**：卡12 新登记 6 纵深缺口（DEVICE 设备档案域/URGENT 加急源/durationMin 真实时长/DONE 完成态/班次表/派单时段自由度，建议远期 M1）+1 观察项（登录页 CShellDesktop 全局壳无 token 四路 401 console 噪音，既有现象非卡12 引入，建议平台治理批修复=壳按 token 门控预拉取）；另历史卡累计 backlog 见 04-backlog
- **下一批 P5-B50 候选方向（待用户拍板）**：①平台治理批（登录页 401 观察项+卡2 历史断链 #380 全量断链清单增强+各卡登记的平台类小项）；②M1 Backlog 纵深缺口（DEVICE/URGENT/durationMin 等，业务纵深但多数依赖跨域新数据源）；③其他用户指定方向

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
