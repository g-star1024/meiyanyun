# 美研云门店中台 · 自动开发接力哨兵（HANDOFF-AUTO）

> 用途：铁律 11 自主续跑的唯一事实源。任何接续会话/定时任务先读本文件，再按「开发前 checklist」执行。
> 维护规则：每批开工改 ACTIVE+心跳；批末（或中断前）改 DORMANT+存档。心跳格式 `YYYY-MM-DD HH:mm CST`。

<!-- MACHINE:STATUS=ACTIVE -->
<!-- MACHINE:HEARTBEAT=2026-09-16 05:10 CST -->
<!-- MACHINE:BATCH=P5-B52 -->
<!-- MACHINE:CARD=卡1 seed 栈服务别名加 seed- 前缀（用户 2026-09-16 凌晨拍板：优先处理 seed 栈 DNS 轮询串扰隐患，方案②seed 容器服务别名加 seed- 前缀；另拍板卡2 派单自由时段开放） -->

## 当前状态（人读区）

- **批次**：P5-B52（**2026-09-16 05:10 开工**）——卡1 seed 栈别名层纵深加固（用户拍板方案②服务别名加 seed- 前缀）+卡2 M1 调度派单自由时段开放（start 不锚定 apptTime，产品决策已拍板开放）+卡3 批末落账；哨兵 ACTIVE
- **用户拍板（原文照录）**：「1、优先处理 seed 栈 DNS 轮询串扰隐患，②seed 容器服务别名加 seed- 前缀；2、M1 调度派单自由时段（start 不锚定 apptTime）：卡5 已闭合改期联动半项，此半项属产品决策，开放；3、完成后开工 P5-B52」
- **用户四项拍板（持续指导本批施工）**：①施工序=按建议序（L143 DONE→L140 DEVICE→L145 改期）；②L140 同时放开 DEVICE 派单写（读+写全闭环）；③L142 appointment 加 sku_code 列；④L141/L144 都保留诚实空态
- **阶段**：卡0 哨兵激活（05:10）；卡1 侦察已闭合待施工——运行态双网已隔离（10-_meiyun 15 容器 / meiyun-seed_seed-net 14 容器零跨网附着），别名前缀系纵深加固非现网故障修复；另实证两连带点须一并处理（见下一步卡1）
- **本批范围**：卡1（04 新增登记行②）seed 栈 10 个 compose 服务键加 `seed-` 前缀根治别名层串扰，连带①frontend nginx 裸别名 `gateway` 改 envsubst 模板（双栈共享镜像，默认 gateway 保 app 栈零影响、seed 注入 meiyun-seed-gateway）②顺带修 seed marketing 崩溃（Exited(1) 31h，Flyway V11-14 pending 乱序，application.yml 缺 out-of-order:true，B38 audit 同款修法）；卡2（04 L146）派单自由时段（DispatchCmd 增可选 start，复用 409/422/审计；前端格子点击带 slot 时段）；卡3 批末落账。
- **保留登记（不在 B51 范围，随后续批次评估）**：L133 impersonate＝大（JWT act/realSub claim+meiyun-security 全服务回归+前端换 token，与 L46 合并）、L123 报告哈希验真 UI＝中（content_hash+规范化字节口径，DSAR/consent 远期）。非本批：L89 setup-seed-db 保留 audit_log、L128 ai-service seed Flyway V17-V29 悬置。
- **记账口径**：纵深缺口批以「缺口闭合即勾 04-backlog 行」为记账单位——完成度数字（✅108/166≈65%、⬜56、🔧1、域⑧ 34✅2🔧31⬜）仅当缺口对应功能真实落地才动；侦察无源的行保持登记不动、诚实说明。
- **上一批**：P5-B50 平台治理/还债批九卡全闭合（B49 四项观察项清零，见文末存档）

## 开工基线

- 代码 HEAD=`0c43981`（P5-B51 批末 docs 提交）；总 HEAD=`0c43981`，已 push origin/main；工作区仅本哨兵未提交修改
- 后端 19 服务 + 网关在线（卡1 开工用 `bash /tmp/meiyun-health.sh` 复核）；正式前端 http://localhost:8080 / 网关 8443；seed 前端 127.0.0.1:18080 / 网关 18443；**seed marketing Exited(1) 待卡1 修复**
- 登录：curl 通道 POST `https://127.0.0.1:8443/api/org/auth/login`（curl -k，E011/meiyun123=REGION_MGR 华东域，token 存 /tmp/meiyun_token.txt，验证前重新登录）；Chrome 通道 http://localhost:8080 手工填表（快捷登录已关闭）
- 铁律 10 开工读数：索引+00～04 五分册本批开工前已全部整读
- docs 提交坑：`docs/` 被 .gitignore 整体忽略；已跟踪文档用 `git add -u docs/`，仅新增 docs 文件用 `git add -f`；Grep 工具对 docs 超长中文行失效，用 shell `grep -rn` 或 Read

## 下一步动作（B52 卡序列）

0. ✅ 卡0 哨兵激活+侦察（05:11）：六册开工前已读齐；实证双网运行态已隔离（10-_meiyun 15 容器 / meiyun-seed_seed-net 14 容器零跨网附着，别名前缀系别名层纵深加固非现网抢修）+seed marketing Exited(1)（Flyway V11-14 乱序 pending，缺 out-of-order:true）+frontend nginx 4 处裸别名 gateway；卡2 全链路五环节锚定（Controller 透传零改/Service L265 start 锚定+record L451/api 类型/store 请求体漏传 start/view 格子不带 slot）
1. ⬜ 卡1 seed 栈别名层纵深（**施工中**）：
   - a. `docker-compose.seed.yml` 十服务键加 `seed-` 前缀（customer/txn/audit/store/org/finance/marketing/ai-service/gateway/frontend）；container_name 与 env 内 URL 全部不动（后端互访走容器名零影响）；仅两处 depends_on 同步（gateway→seed-ai-service、frontend→seed-gateway）；frontend 注入 `GATEWAY_UPSTREAM=meiyun-seed-gateway`
   - b. frontend nginx 模板化：`nginx.conf` 4 处 `proxy_pass https://gateway:8443` → 模板 `${GATEWAY_UPSTREAM}`，Dockerfile 改 COPY 到 `/etc/nginx/templates/default.conf.template`（官方 entrypoint envsubst；envsubst 不支持 `:-` 默认值，故 app compose frontend 显式注入 `GATEWAY_UPSTREAM=gateway`）；$host 等 nginx 运行时变量不在 env 中不会被误替换
   - c. marketing `application.yml` 补 `spring.flyway.out-of-order: true`（B38 audit 先例，V11-14 全 CREATE TABLE IF NOT EXISTS 幂等）；`mvn -pl marketing-service clean package -DskipTests`（必要时 -am）→ app compose 重建 marketing-service+frontend 镜像
   - d. seed 栈 down→up（项目名 meiyun-seed）→ `bash scripts/seed-network-connect.sh` 中间件重接；双栈 frontend 重新部署
   - e. 三轨真验：8443 持续 core M00x 数据/18443 持续 seed SC 数据；正式栈 txn name-map 零空 Map；seed 网内裸别名 `gateway` 不可解析而 `seed-gateway` 可解析（getent hosts/nslookup 实证）；seed marketing Up 且 V11-V14 补应用（flyway_schema_history）；双栈前端 /api 与 /healthz 200
   - f. 一卡一 commit+push
2. ⬜ 卡2 M1 派单自由时段（用户拍板产品决策开放）：DispatchCmd 增可选 `start`（空回落 apptTime 保回归），班次 422/重叠 409/审计 DISPATCH 全复用；api 类型加 start?、store 请求体透传 start、view 格子点击带 slot 且放开可点格（不再限定 apptTime 格）、文案改「班次内任意空闲时段」，样式 class 零改动；三轨真验（自由时段派单成功/越班次 422/重叠 409/空 start 回归 apptTime/审计 payload 含 start）；commit+push
3. ⬜ 卡3 批末：DELIVERY-P5-B52+五分册回写（03 表底新增 P5-B52 行、04 勾销自由时段行+seed 别名行）+哨兵 DORMANT/B53+原子 docs 提交 push+铁律 9 汇报

## 中断恢复指引

- 心跳 <15 分钟：另一实例活跃，**只读不接管**，直接退出
- 心跳 ≥15 分钟且 STATUS=ACTIVE：前实例中断，从「下一步动作」第一个 ⬜ 续跑
- 429/模型上限：刷新本文件心跳后退出，等每小时定时任务重试
- 每卡开工前必须重读本文件 + 铁律 10 全读五分册

## P5-B51 闭合存档（2026-09-16）

- **主题**：M1 调度 Backlog 纵深缺口批（04-backlog L140-145 六缺口，用户 2026-09-15 晚拍板「P5-B51开工吧」=顺序②授权落地），卡1 只读侦察+卡2 四项拍板+卡3–卡6 四卡施工全闭合（4 feat+1 fix 共 5 commit 均 push）
- **完成度**：纯纵深批数字一律不变——**✅108/166=约65%、🔧1、⬜56 约34%；域⑧ 34✅ 2🔧 31⬜**（同 B44/B45/B50 先例：均既有 ✅ 模块纵深挂载，无新页面、无模块状态跃迁、无 🔧 成因消解）
- 卡3 L143 assignment DONE 完成态联动 `79a8ae4`（9 files +150/-25，新建 DispatchCompletion AFTER_COMMIT REQUIRES_NEW 同服务同库联动 ConsultPlanService.treatDone——TREATING→DONE+EM 治疗记录+revision+audit+FollowupScheduler 先例；终态 422、isSlotBusy 排除 DONE 不占时段）+顺手 fix `2714287`（预约号 22P02 既有 bug）；三轨真验 8 项全绿
- 卡4 L140 DEVICE 接真+放开派单写 `86d4a65`（8 files +179/-21，store-service 新建 InternalEquipmentController 复刻 InternalRoomController 范式+txn StoreEquipmentClient 软降级；读侧 resources?type=DEVICE 真实 5 台 NORMAL（校准/维修/停用过滤）、写侧派 EQ-L001 成功 id=8/仅 NORMAL 404/同时段 409；「设备档案无源诚实空态」闭合为读+写全闭环）
- 卡5 L145 预约改期联动 `50a7eb7`（2 files +56/-3，reschedule 同事务 followReschedule——SCHEDULED 派单跟随新时段/重叠 409 预约回滚/越班次 422/audit 双轨 RESCHEDULE+RESCHEDULE_FOLLOW；自由时段派单系产品决策未施工，转 04 新登记行）
- 卡6 L142 appointment 加 sku_code 列 `777d456`（7 files +130/-18，schema 变更+创建选 SKU 403 降级+activeSkuDurationMap 60s 缓存+伪 SKU 400+resolveDurationMin 三消费点统一：绑 SKU 按 duration_min 真源 90 分钟实证/未绑回落 60min，替代原固定 end+60min）
- 批末回写：DELIVERY-P5-B51-2026-09-15.md（13 章）+五分册（00 顶部简报+B50 降级上一批/03 表底 P5-B51 行/04 四行勾销 L140/L142/L143/L145+两行保留登记 L141/L144+两行新增登记/02 调度行尾接+新建预约行挂卡6/01 L18+L36 尾接数字不动），同批原子 docs 提交
- **用户四项拍板（2026-09-15 晚，均已照办）**：①按建议序开工（L143→L140→L145→L142）②同时放开 DEVICE 派单写（读+写全闭环）③appointment 加 sku_code 列（schema 变更+durationMin 从 SKU 真源取）④L141 URGENT/L144 班次表都保留诚实空态（appointment 无 priority/urgent 列、库内无 shift/duty/roster 表，04 保留登记）
- **新增登记两项（待用户拍板）**：①M1 调度派单自由时段（start 不锚定 apptTime，产品决策）；②seed 栈与正式栈同 Docker 网络+同名服务别名致 DNS 轮询串扰（环境治理——seed 库客户号 SC 前缀，查 M002 → 200 空 Map，间歇性空数据而非报错；建议①seed 栈迁移独立 Docker 网络或②seed 容器服务别名加 seed- 前缀）
- **append-only 留档（不可删）**：dispatch id=6/7（卡3 DONE 链）、id=8（卡4 DEVICE+卡5 改期跟随）、预约 000007 改期链（卡5）、AP20260915-000001~000005+dispatch id=1/2+audit CREATE×4（卡6）
- **环境排障留档**：name-map 空 Map 之谜=seed 栈同网络别名 DNS 轮询，txn recreate 恢复
- **下一批 P5-B52 候选方向（待用户拍板）**：①04-backlog 存量纵深（L141 URGENT 加列/L144 班次表/派单自由时段——均需产品决策）；②seed 栈网络隔离环境治理；③L133 impersonate 大项（JWT act/realSub claim+meiyun-security 全服务回归+前端换 token）；④其他用户指定方向

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
