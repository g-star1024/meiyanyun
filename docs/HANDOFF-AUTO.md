# 美研云门店中台 · 自动开发接力哨兵（HANDOFF-AUTO）

> 用途：铁律 11 自主续跑的唯一事实源。任何接续会话/定时任务先读本文件，再按「开发前 checklist」执行。
> 维护规则：每批开工改 ACTIVE+心跳；批末（或中断前）改 DORMANT+存档。心跳格式 `YYYY-MM-DD HH:mm CST`。

<!-- MACHINE:STATUS=ACTIVE -->
<!-- MACHINE:HEARTBEAT=2026-09-15 12:45 CST -->
<!-- MACHINE:BATCH=P5-B50（平台治理/还债批，用户已拍板：先①平台治理后②M1 纵深缺口） -->
<!-- MACHINE:CARD=P5-B50 卡0 哨兵激活完成，即将进入卡1 链路先行侦察（L127 采购 store_id 越权+L129 大屏 paidAt UTC+L132 target DataScope 三快修只读侦察）。卡序：卡2-4 三快修→卡5 登录页 401 门控（L146）→卡6 /verify 全量断链清单（L122/#380/#520+前端 KPI）→卡7 网关 X-Real-IP 审计 IP（L134）；大项 L133 impersonate/L124 GROUP 账号/L123 验真 UI 白天报用户拍板。B50 纯还债批完成度数字预期不变（108/166）。开工前五分册已全读 -->

## 当前状态（人读区）

- **批次**：P5-B50（平台治理/还债批；用户 2026-09-15 拍板：先①平台治理批，B50 闭合后再做②M1 Backlog 纵深缺口=P5-B51）
- **阶段**：卡0 哨兵激活（12:45 CST）→ 即将进入卡1 三快修链路先行只读侦察。P5-B49 全 12 卡已闭合存档（见文末「P5-B49 闭合存档」，代码 `0b3dfb0`/docs `05f1593`）。
- **B50 范围（04-backlog 权威行）**：L127 采购组合域列表越权（补 store_id 强制过滤+越权用例）、L129 大屏 paidAt 直接 slice UTC（复用 utils/datetime.ts 转 Asia/Shanghai）、L132 target 列表行级 DataScope 未收窄（+越权用例）、L146 登录页挂全局壳无 token 四路 401（壳按 token 门控预拉取或登录页不挂壳）、L122 /verify 首处断链即返（实测两处真实断链 #380 2026-09-12 11:49 / #520 2026-09-13 20:01，均早于 B48 `4bfb658` 部署 09-14 09:41；append-only 存量保留，增强返回全量断链清单+前端链完整性 KPI）、L134 审计真实客户端 IP（现 RECHECK/impersonate 审计 ip 写 "web"，Go 网关采集 X-Real-IP 透传 audit append 链路）。
- **待拍板大项（白天 08:00–22:00 先建议后拍板，不擅自扩范围）**：L133 impersonate 真实身份切换（org-service 会话置换+权限重签，与 L46 超管 Impersonate 合并评估，范围大）、L124 无 GROUP 集团全域账号（组织/权限治理批）、L123 DSAR/consent/哈希验真 UI（远期，可与 L122 验真 UI 评估合并）。非本批：L89 setup-seed-db 保留 audit_log、L128 ai-service seed Flyway V17-V29 悬置。
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
1. ⬜ 卡1 链路先行只读侦察（铁律 -1，不动代码）：① store-service procurement 列表查询与 resolveReadStoreCode 三段守卫（L127，参照 consumable 端点）；② frontend m1Screen.ts paidAt.slice(11,16) 与 utils/datetime.ts 既有工具（L129，B48 卡4 已推广 56 处）；③ finance-service BizTargetService 列表查询与 DataScope 行级能力（L132）。产出链路映射+精确改法清单后再切卡
2. ⬜ 卡2 L127：M1 采购组合域列表补 store_id 强制过滤（不信任请求头上下文）+越权用例；curl 跨店头 403/空、本域 200 真验；一卡一 feat commit+push
3. ⬜ 卡3 L129：数据大屏成交流时间转 Asia/Shanghai（复用 utils/datetime.ts，禁新造格式化）；Chrome 时间与 PG `now()/AT TIME ZONE` 核对零误差；vue-tsc+vite build
4. ⬜ 卡4 L132：BizTarget 列表补 DataScope 行级过滤（区域岗仅见本域、门店岗仅见本店）+越权用例；curl E011 华东/他域账号四角真验
5. ⬜ 卡5 L146：CShellDesktop.vue 按 token 有无门控预拉取（无 token 不发 /auth/permissions、/stores×2、/txn/notifications；登录后全 200）；client.ts 401 对 /login 豁免已存在不动；Chrome 无 token 访问 /login console 零 401 截图为证
6. ⬜ 卡6 L122：audit-service verifyChain 增强——遍历全链返回全量断链清单（brokenAtId 首处口径保留兼容、新增 breaks 数组，#380/#520 两条 PG lag() 对账一致）+前端 M1AuditLogView 链完整性 KPI 展示全量断链；append-only 存量不动
7. ⬜ 卡7 L134：Go 网关采集 X-Real-IP/X-Forwarded-For 透传至 audit append 链路，审计 ip 字段实证为真实客户端 IP（RECHECK/impersonate 不再写 "web"）；网关镜像双栈部署
8. ⬜ 大项决策点：L133 impersonate / L124 GROUP 账号 / L123 验真 UI——侦察后白天给「建议+理由」报用户拍板是否纳入本批，不自行决定
9. ⬜ 每卡：铁律 7 三轨真验（curl 经网关 + PG/Chrome + 构建）→ 铁律 8 一卡一 feat commit 紧跟 push
10. ⬜ 批末：新建 DELIVERY-P5-B50 + 五分册回写（00 顶部简报/03 表底加卡行/04 勾行/02 备注/01 数字不变勾稽）+ 索引同批原子 docs 提交 + 铁律 9 汇报 + 哨兵 DORMANT/B51

## 中断恢复指引

- 心跳 <15 分钟：另一实例活跃，**只读不接管**，直接退出
- 心跳 ≥15 分钟且 STATUS=ACTIVE：前实例中断，从「下一步动作」第一个 ⬜ 续跑
- 429/模型上限：刷新本文件心跳后退出，等每小时定时任务重试
- 每卡开工前必须重读本文件 + 铁律 10 全读五分册

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
